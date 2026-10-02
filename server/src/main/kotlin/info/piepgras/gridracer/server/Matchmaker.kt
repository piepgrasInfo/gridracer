package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.server.security.Limits
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A connection waiting in the matchmaking queue. */
internal data class QueuedConnection(
    /** The waiting client's socket; a group is told about its match through it. */
    val connection: Connection,
    /** The player's persistent token, so the matched room can seat them as themselves. */
    val token: String,
    /** The display name the matched room will show. */
    val name: String,
    /** The group size this player asked for; only equal requests are matched together. */
    val desiredPlayers: Int,
    /**
     * A required track, or null for any - players with different requirements are never grouped.
     */
    val track: Track?,
    /** A required lap count, or null for any. */
    val laps: Int?,
    /** When this player joined the queue, for the TTL sweep. */
    val queuedAtMillis: Long = System.currentTimeMillis(),
)

/** Result of entering or leaving the matchmaking queue. */
internal sealed class MatchmakingOutcome {
    /**
     * The player is still waiting; [bucket] is everyone in the same queue bucket and [playersInQueue] is its size.
     *
     * @property bucket Everyone waiting for the same group size, track and laps.
     * @property playersInQueue How many that is.
     */
    data class Queued(val bucket: List<QueuedConnection>, val playersInQueue: Int) : MatchmakingOutcome()

    /**
     * A full group has been matched.
     *
     * @property group The players who make up the new room, removed from the queue.
     */
    data class Matched(val group: List<QueuedConnection>) : MatchmakingOutcome()

    /** This connection is already waiting; the repeat request changed nothing. */
    data object AlreadyQueued : MatchmakingOutcome()
}

/**
 * Simple matchmaking queue.
 *
 * Players are grouped by desired player count, and optionally by track and laps.
 * When a bucket reaches the requested size the whole group is removed and matched.
 */
internal class Matchmaker(private val clock: () -> Long = System::currentTimeMillis) {
    private val queue = mutableListOf<QueuedConnection>()
    private val mutex = Mutex()

    /**
     * Adds [player] to the queue, matching a full group if this completes one.
     *
     * @return [MatchmakingOutcome.Matched] with the group when the bucket filled,
     *     [MatchmakingOutcome.AlreadyQueued] when this connection is already waiting, or
     *     [MatchmakingOutcome.Queued] with the bucket as it now stands.
     */
    suspend fun enter(player: QueuedConnection): MatchmakingOutcome = mutex.withLock {
        // A second `enter` from the same connection used to add a second entry, so one client
        // could be matched *with itself*: the group's seats all resolve to one socket, whose
        // playerId is then whichever seat was written last.
        if (queue.any { it.connection === player.connection }) return@withLock MatchmakingOutcome.AlreadyQueued

        queue.add(player)
        val bucket = queue.filter {
            it.desiredPlayers == player.desiredPlayers &&
                it.track == player.track &&
                it.laps == player.laps
        }
        if (bucket.size >= player.desiredPlayers) {
            val matched = bucket.take(player.desiredPlayers)
            queue.removeAll(matched)
            return@withLock MatchmakingOutcome.Matched(matched)
        }
        return@withLock MatchmakingOutcome.Queued(bucket, bucket.size)
    }

    /**
     * Removes [connection] from the queue.
     *
     * @return The bucket it left, so the others can be told the new count; null if it was not
     *     queued.
     */
    suspend fun leave(connection: Connection): MatchmakingOutcome? = mutex.withLock {
        val index = queue.indexOfFirst { it.connection === connection }
        if (index < 0) return null
        val removed = queue.removeAt(index)
        val bucket = queue.filter {
            it.desiredPlayers == removed.desiredPlayers &&
                it.track == removed.track &&
                it.laps == removed.laps
        }
        return MatchmakingOutcome.Queued(bucket, bucket.size)
    }

    /**
     * Drops entries that have waited longer than [Limits.MATCHMAKING_TTL_MILLIS] and returns
     * them, so the caller can tell those players they are no longer queued.
     *
     * Without this a player who is queued but whose bucket never fills waits forever with no
     * feedback, and the entry pins their [QueuedConnection] - and through it their
     * [Connection] - in memory for the lifetime of the server.
     */
    suspend fun reapExpired(): List<QueuedConnection> = mutex.withLock {
        val cutoff = clock() - Limits.MATCHMAKING_TTL_MILLIS
        val expired = queue.filter { it.queuedAtMillis < cutoff }
        queue.removeAll(expired)
        expired
    }

    /** Visible for tests. */
    internal suspend fun size(): Int = mutex.withLock { queue.size }
}
