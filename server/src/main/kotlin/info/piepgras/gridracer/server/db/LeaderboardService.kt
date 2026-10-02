package info.piepgras.gridracer.server.db

import info.piepgras.gridracer.multiplayer.RatingEntry
import info.piepgras.gridracer.multiplayer.RatingsPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The leaderboard on the database, plus the write path that keeps DB calls off the room mutexes.
 *
 * Reads ([leaderboardPage]) run inline on the caller's coroutine (which is already off the
 * room mutex - the router calls this from the WebSocket read coroutine). Writes go through a
 * bounded [Channel] drained by one consumer on the [Database] thread: `Room.onRaceFinished`
 * runs under both `RoomManager.mutex` and `Room.mutex`, so a direct DB call there would
 * serialize every race-finished callback against every other room's race-finished callback
 * and against the lobby list. Enqueuing a [RaceRecord] and returning is O(1) and holds no
 * mutex; the consumer does the DB work asynchronously.
 *
 * The channel is bounded so a flood of finishes cannot heap up unbounded: a full channel
 * means the server is finishing races faster than it can rate them, which is the right
 * condition to drop a record on the floor (the race still finished; only the rating update
 * is lost) rather than to block the room mutex.
 */
class LeaderboardService(
    private val db: Database,
    private val accountDao: AccountDao = AccountDao(),
    private val leaderboardDao: LeaderboardDao = LeaderboardDao(),
    /** Override for tests; production uses [Dispatchers.Default]. */
    private val consumerScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val log = LoggerFactory.getLogger(LeaderboardService::class.java)
    private val channel = Channel<RaceRecord>(capacity = CHANNEL_CAPACITY)
    private val consumerJob: Job

    init {
        consumerJob = consumerScope.launch { drain() }
    }

    /**
     * One race to be rated. Only account-holding finishers are included; guests and AI are
     * dropped by the caller before enqueueing, which is what "rate only the account-holding
     * subset of a mixed race" means in the plan.
     */
    data class RaceRecord(
        /** Every account-holding player in the race, by account id with their finish rank (null for a DNF). */
        val finishers: List<Elo.Finisher>,
        /** Account ids of the players who finished first. */
        val winnerIds: Set<Long>,
    )

    /**
     * Enqueues a race for rating. Returns immediately; the rating update happens on the DB
     * thread. Returns false if the channel is full, in which case the record is dropped (the
     * race still finished; only the rating update is lost).
     */
    fun enqueue(record: RaceRecord): Boolean {
        val result = channel.trySend(record)
        return result.isSuccess
    }

    /** One page of the leaderboard, plus the caller's own row when they have an account. */
    suspend fun leaderboardPage(offset: Int, limit: Int, forAccountId: Long?): RatingsPage = db.read { conn ->
        val page = leaderboardDao.page(conn, offset, limit, forAccountId)
        RatingsPage(
            entries = page.entries.map { it.toWire() },
            offset = page.offset,
            totalEntries = page.totalEntries,
            you = page.you?.toWire(),
        )
    }

    /** Stops the consumer. Called when the server shuts down. */
    fun close() {
        consumerJob.cancel()
        channel.close()
    }

    private suspend fun drain() {
        for (record in channel) {
            runCatching { applyRace(record) }
                .onFailure { log.error("Failed to apply a race result to the leaderboard", it) }
        }
    }

    private suspend fun applyRace(record: RaceRecord) {
        if (record.finishers.size < 2) return
        db.write { conn ->
            // Read the current ratings for every finisher in one pass.
            val ratings = mutableMapOf<Long, Int>()
            for (finisher in record.finishers) {
                val account = accountDao.findById(conn, finisher.id) ?: return@write
                ratings[finisher.id] = account.rating
            }
            val deltas = Elo.deltas(record.finishers, ratings)
            leaderboardDao.applyRaceResult(conn, deltas, record.winnerIds)
        }
    }

    private fun LeaderboardEntry.toWire(): RatingEntry = RatingEntry(
        publicId = publicId,
        displayName = displayName,
        rating = rating,
        matches = matches,
        wins = wins,
        rank = rank,
    )

    companion object {
        /** Bounded so a flood of finishes cannot heap up unbounded. */
        private const val CHANNEL_CAPACITY = 256
    }
}
