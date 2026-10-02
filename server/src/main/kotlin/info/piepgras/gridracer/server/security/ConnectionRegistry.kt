package info.piepgras.gridracer.server.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts live WebSocket connections, per address and in total, so the server can refuse a
 * socket rather than accept one it cannot afford.
 *
 * This is the floor under every rate budget. Budgets keyed by `Connection.id` are bypassed by
 * opening another socket - [RateKeys] says so - and the per-IP budgets in [RateLimiter] bound
 * how much *traffic* one address may send but not how many sockets it may hold open. Only a
 * count does that, and file descriptors are the resource that runs out first.
 *
 * [release] must run in the same `finally` that cleans the connection up, or the count drifts
 * upward and the server locks itself out.
 */
class ConnectionRegistry(
    private val maxPerIp: Int = Limits.MAX_CONNECTIONS_PER_IP,
    private val maxTotal: Int = Limits.MAX_TOTAL_CONNECTIONS,
) {
    /** Why a connection was refused, so the caller can pick a close reason and a log line. */
    enum class Rejection { TOO_MANY_FROM_IP, SERVER_FULL }

    private val perIp = ConcurrentHashMap<String, AtomicInteger>()
    private val total = AtomicInteger(0)

    /**
     * Reserves a slot for [remoteIp]. Returns null when the connection may proceed, or the
     * reason it may not - in which case nothing was reserved and [release] must not be called.
     */
    fun acquire(remoteIp: String): Rejection? {
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet()
            return Rejection.SERVER_FULL
        }
        // computeIfAbsent rather than getOrPut: two sockets from the same address can arrive on
        // different threads, and the counter they increment has to be the same object.
        val counter = perIp.computeIfAbsent(remoteIp) { AtomicInteger(0) }
        if (counter.incrementAndGet() > maxPerIp) {
            counter.decrementAndGet()
            total.decrementAndGet()
            return Rejection.TOO_MANY_FROM_IP
        }
        return null
    }

    /** Releases a slot previously reserved by a successful [acquire]. */
    fun release(remoteIp: String) {
        total.decrementAndGet()
        // Drop the entry once it reaches zero, or the map is an unbounded leak keyed by
        // attacker-chosen addresses - the same shape of bug RateLimiter.evictIdle exists for.
        perIp.computeIfPresent(remoteIp) { _, counter ->
            if (counter.decrementAndGet() <= 0) null else counter
        }
    }

    /** Visible for tests. */
    internal fun liveConnections(): Int = total.get()

    /** Visible for tests. */
    internal fun liveConnections(remoteIp: String): Int = perIp[remoteIp]?.get() ?: 0
}
