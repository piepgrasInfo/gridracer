package info.piepgras.gridracer.server.log

import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.replay.ReplayData
import info.piepgras.gridracer.replay.ServerRaceInfo
import info.piepgras.gridracer.replay.isPlayable
import info.piepgras.gridracer.replay.replayFinalState
import info.piepgras.gridracer.server.FakeConnection
import info.piepgras.gridracer.server.Room
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers race logging end to end: a race is played through a real [Room], the log lands on
 * disk, and folding the logged moves back over the logged initial state reproduces the race.
 *
 * That last assertion is the whole feature. A log that cannot reconstruct the race it recorded
 * is not a log, it is a file.
 */
class RaceLoggerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val tempDirs = mutableListOf<Path>()

    @After
    fun cleanup() {
        tempDirs.forEach { dir ->
            runCatching {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun tempDir(): Path =
        Files.createTempDirectory("gridracer-racelog").also { tempDirs.add(it) }

    /**
     * Plays a race through a real [Room] to completion, without a client.
     *
     * The seat the host holds is driven by the *timeouts*: placement is auto-chosen, and the
     * host's first turn forfeits, after which the AI finishes the race on its own. That covers
     * both recorded transition kinds - a forfeit and a run of ordinary moves - and needs no
     * fake client loop.
     */
    private fun playARace(logger: RaceLogger): FakeConnection {
        val host = FakeConnection()
        val room = Room(
            code = "LOG01",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
            raceLogger = logger,
            placementTimeoutMs = 50L,
            turnTimeoutMs = 50L,
        )
        runBlocking {
            room.setReady(0, true)
            // The opponent is seated deliberately: the server no longer invents one when the
            // race starts, so a solo host cannot start at all.
            room.addAiPlayer(0)
            room.startRace(0)
            awaitRaceFinished(host)
        }
        return host
    }

    /** Waits (bounded) for the room to broadcast RaceFinished, then for the writer to flush. */
    private suspend fun awaitRaceFinished(host: FakeConnection) {
        repeat(200) {
            if (host.sent.any { m -> m is ServerMessage.RaceFinished }) {
                // The log writer is on its own thread; give it a moment to land the file.
                delay(500)
                return
            }
            delay(50)
        }
        error("the race never finished; nothing to log")
    }

    @Test
    fun aFinishedRaceIsWrittenAsAReplayThatReconstructsIt() {
        val dir = tempDir()
        val logger = FileRaceLogger(directory = dir, serverId = "test-server", serverName = "Test")
        try {
            val host = playARace(logger)
            val actual = host.sent.filterIsInstance<ServerMessage.RaceFinished>().last()

            val files = Files.list(dir).use { it.toList() }
                .filter { it.fileName.toString().endsWith(FileRaceLogger.EXTENSION) }
            assertEquals("exactly one race should have been logged", 1, files.size)

            val replay = json.decodeFromString(ReplayData.serializer(), files.single().toFile().readText())
            assertTrue("a logged replay must be playable", replay.isPlayable)
            assertTrue("a race that ran must have recorded moves", replay.moves.isNotEmpty())

            // The assertion this whole feature exists for: folding the log back over the
            // logged starting state must land on the same result the server actually
            // broadcast. Comparing against the real standings rather than against a guess at
            // them is what makes this a test of *faithfulness* rather than of plausibility.
            val reconstructed = replay.replayFinalState()
            assertEquals(GamePhase.RACE_OVER, reconstructed.phase)
            for (standing in actual.standings) {
                val player = reconstructed.players.first { it.id == standing.playerId }
                assertEquals(
                    "seat ${standing.playerId} (${standing.name}) finished differently on replay",
                    standing.finishRank,
                    player.finishRank,
                )
                assertEquals(
                    "seat ${standing.playerId} (${standing.name}) crashed differently on replay",
                    standing.crashed,
                    player.status == info.piepgras.gridracer.engine.PlayerStatus.CRASHED,
                )
            }
            // Both recorded transition kinds are exercised: the host's turn timed out.
            assertTrue(
                "the timed-out turn should be recorded as a forfeit",
                replay.moves.any { it.kind == info.piepgras.gridracer.replay.MoveKind.FORFEIT },
            )
            assertTrue(
                "the AI's turns should be recorded as moves",
                replay.moves.any { it.kind == info.piepgras.gridracer.replay.MoveKind.MOVE },
            )
        } finally {
            logger.close()
        }
    }

    @Test
    fun theLoggedReplayCarriesItsServerProvenance() {
        val dir = tempDir()
        val logger = FileRaceLogger(directory = dir, serverId = "server-abc", serverName = "Wembley")
        try {
            playARace(logger)

            val file = Files.list(dir).use { it.toList() }
                .first { it.fileName.toString().endsWith(FileRaceLogger.EXTENSION) }
            val replay = json.decodeFromString(ReplayData.serializer(), file.toFile().readText())

            val server = replay.server
            assertNotNull("a server-logged replay must say where it came from", server)
            assertEquals("LOG01", server!!.roomCode)
            assertEquals("server-abc", server.serverId)
            assertEquals("Wembley", server.serverName)
            assertTrue(server.finishedAt >= server.startedAt)
            // Guests and AI seats carry no account handle - and never the internal row id.
            assertTrue(server.accountPublicIds.all { it == null })
        } finally {
            logger.close()
        }
    }

    @Test
    fun anIndexLineIsAppendedPerRace() {
        val dir = tempDir()
        val logger = FileRaceLogger(directory = dir, serverId = "s", serverName = "s")
        try {
            playARace(logger)

            val index = dir.resolve(FileRaceLogger.INDEX_FILE).toFile()
            assertTrue("an index line should have been appended", index.exists())
            val lines = index.readLines().filter { it.isNotBlank() }
            assertEquals(1, lines.size)

            val summary = json.decodeFromString(RaceLogSummary.serializer(), lines.single())
            assertEquals("LOG01", summary.roomCode)
            assertTrue(summary.moves > 0)
            // The index must point at a file that is actually there.
            assertTrue(dir.resolve(summary.file).toFile().exists())
            // Best-first ordering: a finisher sorts ahead of anyone who did not finish.
            val ranks = summary.standings.map { it.finishRank ?: Int.MAX_VALUE }
            assertEquals("standings must be ordered best first", ranks.sorted(), ranks)
        } finally {
            logger.close()
        }
    }

    @Test
    fun loggingDisabledWritesNothing() {
        val dir = tempDir()
        playARace(RaceLogger.Disabled)

        assertEquals("nothing must be written when logging is off", 0, Files.list(dir).use { it.count() })
    }

    @Test
    fun theSweepDeletesLogsPastTheRetentionWindow() {
        val dir = tempDir()
        var now = 1_700_000_000_000L
        val logger = FileRaceLogger(
            directory = dir,
            retentionDays = 7,
            clock = { now },
        )
        try {
            Files.createDirectories(dir)
            val old = dir.resolve("2020-01-01T00-00-00Z_OLD01${FileRaceLogger.EXTENSION}")
            val fresh = dir.resolve("2030-01-01T00-00-00Z_NEW01${FileRaceLogger.EXTENSION}")
            old.toFile().writeText("{}")
            fresh.toFile().writeText("{}")
            Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(now - 30L * 86_400_000))
            Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.fromMillis(now - 3_600_000))

            val deleted = runBlocking { logger.sweep() }

            assertEquals(1, deleted)
            assertFalse("the log past the retention window must be gone", Files.exists(old))
            assertTrue("a recent log must be kept", Files.exists(fresh))
        } finally {
            logger.close()
        }
    }

    @Test
    fun aZeroRetentionKeepsEverything() {
        val dir = tempDir()
        val logger = FileRaceLogger(directory = dir, retentionDays = 0)
        try {
            Files.createDirectories(dir)
            val file = dir.resolve("2020-01-01T00-00-00Z_OLD01${FileRaceLogger.EXTENSION}")
            file.toFile().writeText("{}")
            Files.setLastModifiedTime(
                file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3650L * 86_400_000),
            )

            assertEquals(0, runBlocking { logger.sweep() })
            assertTrue(Files.exists(file))
        } finally {
            logger.close()
        }
    }

    // ---- Purging a deleted account ------------------------------------------------------

    /**
     * Builds a two-seat race log by hand and writes it through the logger, so the purge tests
     * can name the account handles they expect to see scrubbed. Playing a real race would only
     * produce guest seats, which carry no handle at all.
     */
    private fun logARace(
        logger: FileRaceLogger,
        roomCode: String,
        names: List<String>,
        publicIds: List<String?>,
        finishedAt: Long = 1_700_000_000_000L,
    ): String {
        val state = GameEngine.newGame(Tracks.all.first(), playerCount = names.size, totalLaps = 1)
        val replay = ReplayData(
            trackName = state.track.name,
            configs = names.map { PlayerConfig(name = it, isAi = false) },
            initialState = state,
            moves = emptyList(),
            timestamp = finishedAt,
            server = ServerRaceInfo(
                roomCode = roomCode,
                serverId = "test-server",
                serverName = "Test",
                startedAt = finishedAt - 60_000,
                finishedAt = finishedAt,
                accountPublicIds = publicIds,
            ),
        )
        val summary = RaceLogSummary.of(replay, state)
        runBlocking {
            logger.enqueue(replay, summary)
            delay(300)
        }
        return Files.list(directoryOfLogger(logger)).use { it.toList() }
            .map { it.fileName.toString() }
            .first { it.contains(roomCode) }
    }

    private fun directoryOfLogger(logger: FileRaceLogger): Path = loggerDirs.getValue(logger)

    private val loggerDirs = mutableMapOf<FileRaceLogger, Path>()

    private fun loggerIn(dir: Path, retentionDays: Long = 30L, clock: () -> Long = System::currentTimeMillis) =
        FileRaceLogger(directory = dir, serverId = "test-server", serverName = "Test", retentionDays = retentionDays, clock = clock)
            .also { loggerDirs[it] = dir }

    @Test
    fun purgingAnAccountScrubsItsSeatButKeepsTheRace() {
        val dir = tempDir()
        val logger = loggerIn(dir)
        try {
            val file = logARace(
                logger,
                roomCode = "PURG1",
                names = listOf("Alice", "Bob"),
                publicIds = listOf("alice-public-id", "bob-public-id"),
            )

            val scrubbed = runBlocking { logger.purgeAccount("alice-public-id") }
            assertEquals(1, scrubbed)

            val replay = json.decodeFromString(ReplayData.serializer(), dir.resolve(file).toFile().readText())
            assertEquals(RaceLogger.DELETED_PLAYER_NAME, replay.configs[0].name)
            assertEquals("the co-racer must be untouched", "Bob", replay.configs[1].name)
            assertNull(replay.server!!.accountPublicIds[0])
            assertEquals("bob-public-id", replay.server!!.accountPublicIds[1])
            // The race itself survives: this is a deletion of a name, not of everyone's replay.
            assertTrue(replay.isPlayable)

            val line = dir.resolve(FileRaceLogger.INDEX_FILE).toFile().readLines().single { it.isNotBlank() }
            val summary = json.decodeFromString(RaceLogSummary.serializer(), line)
            assertEquals(RaceLogger.DELETED_PLAYER_NAME, summary.standings.single { it.playerId == 0 }.name)
            assertEquals("Bob", summary.standings.single { it.playerId == 1 }.name)
        } finally {
            logger.close()
        }
    }

    @Test
    fun purgingAnAccountLeavesRacesItDidNotDriveIn() {
        val dir = tempDir()
        val logger = loggerIn(dir)
        try {
            val other = logARace(
                logger,
                roomCode = "OTHER",
                names = listOf("Carol", "Dave"),
                publicIds = listOf("carol-public-id", null),
            )

            assertEquals(0, runBlocking { logger.purgeAccount("alice-public-id") })

            val replay = json.decodeFromString(ReplayData.serializer(), dir.resolve(other).toFile().readText())
            assertEquals("Carol", replay.configs[0].name)
            assertEquals("carol-public-id", replay.server!!.accountPublicIds[0])
        } finally {
            logger.close()
        }
    }

    /**
     * The index outlives the replay files unless the sweep prunes it too - and it carries
     * display names, which is exactly what the retention window exists to expire.
     */
    @Test
    fun theSweepAlsoPrunesTheIndexOfTheLogsItDeleted() {
        var now = 1_700_000_000_000L
        val dir = tempDir()
        val logger = loggerIn(dir, retentionDays = 7L, clock = { now })
        try {
            val old = logARace(logger, "OLD01", listOf("Alice", "Bob"), listOf(null, null), finishedAt = now)
            val fresh = logARace(logger, "NEW01", listOf("Carol", "Dave"), listOf(null, null), finishedAt = now)
            Files.setLastModifiedTime(
                dir.resolve(old),
                java.nio.file.attribute.FileTime.fromMillis(now - 30L * 86_400_000),
            )

            assertEquals(1, runBlocking { logger.sweep() })

            val lines = dir.resolve(FileRaceLogger.INDEX_FILE).toFile().readLines().filter { it.isNotBlank() }
            assertEquals("the swept race's index line must go with its file", 1, lines.size)
            val kept = json.decodeFromString(RaceLogSummary.serializer(), lines.single())
            assertEquals(fresh, kept.file)
        } finally {
            logger.close()
        }
    }
}
