package info.piepgras.gridracer.server.log

import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.replay.ReplayData
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * One line of `races.jsonl`: enough to find a race without opening every replay file.
 *
 * Deliberately no move data and no personal data beyond the display names that were already
 * visible to everyone in the lobby - the index is for scanning, the replay file is the record.
 */
@Serializable
data class RaceLogSummary(
    /** The room the race was played in; empty for a race without server info. */
    val roomCode: String,
    /** Display name of the track. */
    val trackName: String,
    /** The replay file's name in the log directory, relative to it. */
    val file: String,
    /** When the race started, in epoch millis; 0 when unknown. */
    val startedAt: Long,
    /** When the race finished, in epoch millis. */
    val finishedAt: Long,
    /** How many moves the replay holds. */
    val moves: Int,
    /** Laps the race was set to. */
    val laps: Int,
    /** Whether the room came from the matchmaker, so the race counted for rating. */
    val isMatchmade: Boolean,
    /** Whether the race was one heat of a tournament. */
    val isTournament: Boolean,
    /** Final order, best first: seat, name, whether AI, finish rank (null if they did not finish). */
    val standings: List<RaceLogStanding>,
) {
    companion object {
        /**
         * Builds the index line for a finished race.
         *
         * @param replay The race as recorded.
         * @param finalState The state after the last move, for laps and finishing order.
         * @param file The replay file's name, once known.
         * @return The summary, standings ordered finishers first.
         */
        fun of(replay: ReplayData, finalState: GameState, file: String = ""): RaceLogSummary {
            val server = replay.server
            return RaceLogSummary(
                roomCode = server?.roomCode.orEmpty(),
                trackName = replay.trackName,
                file = file,
                startedAt = server?.startedAt ?: 0L,
                finishedAt = server?.finishedAt ?: replay.timestamp,
                moves = replay.moves.size,
                laps = finalState.totalLaps,
                isMatchmade = server?.isMatchmade ?: false,
                isTournament = server?.isTournament ?: false,
                standings = finalState.players
                    .map { player ->
                        RaceLogStanding(
                            playerId = player.id,
                            name = replay.configs.getOrNull(player.id)?.name.orEmpty(),
                            isAi = replay.configs.getOrNull(player.id)?.isAi ?: false,
                            finishRank = player.finishRank,
                            crashed = player.status == PlayerStatus.CRASHED,
                        )
                    }
                    // Finishers first in order, then everyone else.
                    .sortedBy { it.finishRank ?: Int.MAX_VALUE },
            )
        }
    }
}

/**
 * One seat's result, as recorded in the race index.
 *
 * @property playerId The seat's player id.
 * @property name The display name shown in the lobby at the time.
 * @property isAi Whether an AI drove the seat.
 * @property finishRank 1-based finishing position, or null if the player did not finish.
 * @property crashed Whether the player crashed out.
 */
@Serializable
data class RaceLogStanding(
    val playerId: Int,
    val name: String,
    val isAi: Boolean,
    val finishRank: Int?,
    val crashed: Boolean,
)

/**
 * Writes every finished race to disk in the app's own replay format, so a race can be watched
 * back rather than only argued about.
 *
 * ## Why files rather than the database
 *
 * The SQLite database has one writer, shared with accounts, sessions and the leaderboard.
 * Race volume is unbounded and none of it needs a query the filesystem cannot answer, so
 * putting it there would buy contention on the one connection that authentication depends on.
 * A file per race is also directly usable: it is byte-for-byte what the app's replay browser
 * imports, so handing someone a disputed race is `scp`, not an export tool.
 *
 * ## Why a channel
 *
 * [enqueue] is called from `Room.onRaceFinished`, which runs **under the room mutex**. File
 * I/O there would let a slow disk hold up every player in that room - and, through
 * `RoomManager`, the `/rooms` listing for the whole server. Same rule and same shape as
 * `LeaderboardService`: enqueue is O(1) and lock-free, one consumer does the work.
 */
interface RaceLogger {
    /** False for the disabled instance, so a caller can skip building a replay it will not use. */
    val enabled: Boolean

    /** This server's identity, stamped into every logged race. */
    val serverId: String
    /** This server's human-readable name, stamped into every logged race. */
    val serverName: String

    /** Hands a finished race to the writer. Never blocks and never throws. */
    suspend fun enqueue(replay: ReplayData, summary: RaceLogSummary)

    /** Deletes logs past the retention window. Called from the housekeeping timer. */
    suspend fun sweep(): Int

    /**
     * Removes a deleted account from every stored race: their seat's display name becomes
     * [DELETED_PLAYER_NAME] and their public account id is dropped, in both the replay files
     * and the index.
     *
     * The race itself is kept. A replay belongs to everyone who drove in it, and destroying
     * four people's race to erase a fifth is a worse answer than erasing the fifth - which is
     * all "delete my data" actually asks for. See `legal/privacy-policy.md`.
     */
    suspend fun purgeAccount(publicId: String): Int

    /** The no-op used when `GRIDRACER_RACE_LOG=off`, so callers need no conditionals. */
    object Disabled : RaceLogger {
        override val enabled = false
        override val serverId = ""
        override val serverName = ""
        override suspend fun enqueue(replay: ReplayData, summary: RaceLogSummary) = Unit
        override suspend fun sweep() = 0
        override suspend fun purgeAccount(publicId: String) = 0
    }

    companion object {
        /**
         * What a deleted account's seat is called in a kept race log. Not a string resource:
         * race logs are server-side diagnostics, written in one language on purpose.
         */
        const val DELETED_PLAYER_NAME = "Deleted player"
    }
}

/** The real [RaceLogger]: one file per race plus an index line, on its own writer thread. */
class FileRaceLogger(
    private val directory: Path,
    override val serverId: String = "",
    override val serverName: String = "",
    /** Logs older than this are swept. */
    private val retentionDays: Long = DEFAULT_RETENTION_DAYS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "gridracer-racelog").apply { isDaemon = true } }
            .asCoroutineDispatcher(),
) : RaceLogger, AutoCloseable {

    private val log = LoggerFactory.getLogger(FileRaceLogger::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override val enabled = true

    /**
     * Bounded on purpose. If races finish faster than the disk can absorb them, dropping the
     * newest log line is the right failure: a race log is diagnostic, and blocking
     * `onRaceFinished` to protect it would stall the actual game.
     */
    private val queue = Channel<Pair<ReplayData, RaceLogSummary>>(capacity = QUEUE_CAPACITY)

    private val consumer = scope.launch {
        for ((replay, summary) in queue) {
            runCatching { write(replay, summary) }
                .onFailure { log.warn("Writing a race log for room {} failed", summary.roomCode, it) }
        }
    }

    override suspend fun enqueue(replay: ReplayData, summary: RaceLogSummary) {
        if (queue.trySend(replay to summary).isFailure) {
            log.warn("Race log queue is full; dropping the log for room {}", summary.roomCode)
        }
    }

    private fun write(replay: ReplayData, summary: RaceLogSummary) {
        Files.createDirectories(directory)
        val name = fileName(summary)
        val replayFile = directory.resolve(name)
        replayFile.toFile().writeText(json.encodeToString(ReplayData.serializer(), replay))

        val indexed = summary.copy(file = name)
        Files.write(
            directory.resolve(INDEX_FILE),
            (json.encodeToString(RaceLogSummary.serializer(), indexed) + "\n").toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        log.info("Logged race {} ({} moves) to {}", summary.roomCode, summary.moves, name)
    }

    /**
     * `<ISO-8601 instant>_<room code>.gridracer-replay`, with the colons stripped so the name
     * is valid on every filesystem. Sorts chronologically as text, which is what makes the
     * retention sweep and a plain `ls` both useful.
     */
    private fun fileName(summary: RaceLogSummary): String {
        val at = Instant.ofEpochMilli(summary.finishedAt.takeIf { it > 0 } ?: clock())
        val stamp = TIMESTAMP_FORMAT.format(at.atOffset(ZoneOffset.UTC))
        val room = summary.roomCode.ifBlank { "unknown" }
        return "${stamp}_$room$EXTENSION"
    }

    /**
     * Runs on the writer thread, like every other mutation here: [rewriteIndex] replaces
     * `races.jsonl` wholesale, and doing that while [write] is midway through appending a line
     * would lose the line. One thread owns the directory; that is the whole rule.
     */
    override suspend fun sweep(): Int = withContext(dispatcher) {
        if (retentionDays <= 0) return@withContext 0
        val cutoff = clock() - retentionDays * MILLIS_PER_DAY
        val deletedFiles = mutableSetOf<String>()
        runCatching {
            if (!Files.isDirectory(directory)) return@withContext 0
            Files.newDirectoryStream(directory, "*$EXTENSION").use { entries ->
                for (entry in entries) {
                    val modified = runCatching { Files.getLastModifiedTime(entry).toMillis() }.getOrNull() ?: continue
                    if (modified < cutoff && runCatching { Files.deleteIfExists(entry) }.getOrDefault(false)) {
                        deletedFiles += entry.fileName.toString()
                    }
                }
            }
        }.onFailure { if (it !is IOException) throw it else log.warn("Race log sweep failed", it) }
        if (deletedFiles.isNotEmpty()) {
            // The index outlives the files it points at unless it is pruned too - and it holds
            // display names, which is precisely the data the retention window exists to expire.
            rewriteIndex { summary -> if (summary.file in deletedFiles) null else summary }
            log.info("Swept {} race logs older than {} days", deletedFiles.size, retentionDays)
        }
        deletedFiles.size
    }

    override suspend fun purgeAccount(publicId: String): Int = withContext(dispatcher) {
        if (publicId.isBlank() || !Files.isDirectory(directory)) return@withContext 0
        // file name -> the seats that belonged to the deleted account, so the index can be
        // scrubbed to match without decoding every replay a second time.
        val scrubbedSeats = mutableMapOf<String, Set<Int>>()
        runCatching {
            Files.newDirectoryStream(directory, "*$EXTENSION").use { entries ->
                for (entry in entries) {
                    val replay = runCatching {
                        json.decodeFromString(ReplayData.serializer(), entry.toFile().readText())
                    }.getOrNull() ?: continue
                    val server = replay.server ?: continue
                    val seats = server.accountPublicIds
                        .mapIndexedNotNull { seat, id -> seat.takeIf { id == publicId } }
                        .toSet()
                    if (seats.isEmpty()) continue
                    val scrubbed = replay.copy(
                        configs = replay.configs.mapIndexed { seat, config ->
                            if (seat in seats) config.copy(name = RaceLogger.DELETED_PLAYER_NAME) else config
                        },
                        server = server.copy(
                            accountPublicIds = server.accountPublicIds.mapIndexed { seat, id ->
                                if (seat in seats) null else id
                            },
                        ),
                    )
                    entry.toFile().writeText(json.encodeToString(ReplayData.serializer(), scrubbed))
                    scrubbedSeats[entry.fileName.toString()] = seats
                }
            }
        }.onFailure { if (it !is IOException) throw it else log.warn("Scrubbing race logs failed", it) }
        if (scrubbedSeats.isNotEmpty()) {
            rewriteIndex { summary ->
                val seats = scrubbedSeats[summary.file] ?: return@rewriteIndex summary
                summary.copy(
                    standings = summary.standings.map { standing ->
                        if (standing.playerId in seats) {
                            standing.copy(name = RaceLogger.DELETED_PLAYER_NAME)
                        } else {
                            standing
                        }
                    },
                )
            }
            log.info("Scrubbed a deleted account out of {} race logs", scrubbedSeats.size)
        }
        scrubbedSeats.size
    }

    /**
     * Rewrites `races.jsonl` by mapping every line through [transform]; a null result drops the
     * line. Written to a temp file and moved into place, so a crash mid-rewrite leaves the old
     * index intact rather than half a file. Caller must already be on [dispatcher].
     *
     * A line that does not parse is kept verbatim: it is not this function's job to decide that
     * someone else's data is garbage.
     */
    private fun rewriteIndex(transform: (RaceLogSummary) -> RaceLogSummary?) {
        val index = directory.resolve(INDEX_FILE)
        if (!Files.isRegularFile(index)) return
        runCatching {
            val rewritten = buildList {
                for (line in Files.readAllLines(index)) {
                    if (line.isBlank()) continue
                    val summary = runCatching { json.decodeFromString(RaceLogSummary.serializer(), line) }.getOrNull()
                    if (summary == null) {
                        add(line)
                        continue
                    }
                    val mapped = transform(summary) ?: continue
                    add(json.encodeToString(RaceLogSummary.serializer(), mapped))
                }
            }
            val temp = directory.resolve("$INDEX_FILE.tmp")
            Files.write(
                temp,
                rewritten.joinToString(separator = "") { "$it\n" }.toByteArray(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { if (it !is IOException) throw it else log.warn("Rewriting the race log index failed", it) }
    }

    override fun close() {
        queue.close()
        consumer.cancel()
        runCatching { dispatcher.close() }
    }

    companion object {
        /** Name of the append-only index of logged races, one [RaceLogSummary] per line. */
        const val INDEX_FILE = "races.jsonl"
        /** File extension of a logged replay - the same one the app's replay browser opens. */
        const val EXTENSION = ".gridracer-replay"
        /**
         * How long a logged race is kept unless `GRIDRACER_RACE_LOG_RETENTION_DAYS` says otherwise.
         */
        const val DEFAULT_RETENTION_DAYS = 30L
        private const val QUEUE_CAPACITY = 256
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")

        /** Resolves a configured directory string, relative to the working directory. */
        fun directoryOf(path: String): Path = Paths.get(path).toAbsolutePath()
    }
}
