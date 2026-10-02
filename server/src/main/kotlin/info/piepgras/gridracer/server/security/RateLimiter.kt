package info.piepgras.gridracer.server.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Token-bucket rate limiting, keyed by an arbitrary string so the same class covers
 * per-connection, per-IP and per-room budgets.
 *
 * [now] is injected so the tests can advance time instead of sleeping.
 *
 * Buckets are evicted once idle, or the map is itself an unbounded leak - which is the shape
 * of bug this whole class exists to prevent.
 */
class RateLimiter(private val now: () -> Long = System::currentTimeMillis) {

    /**
     * How many actions are allowed, and how fast the allowance comes back.
     *
     * @property capacity The bucket size: how many actions may be taken in a burst.
     * @property refillPerMinute How many tokens come back per minute, up to [capacity].
     */
    data class Budget(val capacity: Int, val refillPerMinute: Double) {
        companion object {
            /** A budget of [count] actions in a burst, refilling at [count] a minute. */
            fun perMinute(count: Int) = Budget(capacity = count, refillPerMinute = count.toDouble())
            /** A budget of [count] actions in a burst, refilling at [count] a second. */
            fun perSecond(count: Int) = Budget(capacity = count, refillPerMinute = count * 60.0)
            /** A budget of [count] actions in a burst, refilling at [count] an hour. */
            fun perHour(count: Int) = Budget(capacity = count, refillPerMinute = count / 60.0)
        }
    }

    private class Bucket(var tokens: Double, var lastRefillMillis: Long, var lastUsedMillis: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * Takes one token from [key]'s bucket. Returns true if the action is allowed.
     *
     * Denial is meant to drop the offending message, not the connection: a laggy client
     * resyncing bursts legitimately, and kicking it makes the lag worse.
     */
    fun allow(key: String, budget: Budget, cost: Int = 1): Boolean {
        val current = now()
        val bucket = buckets.computeIfAbsent(key) {
            Bucket(tokens = budget.capacity.toDouble(), lastRefillMillis = current, lastUsedMillis = current)
        }
        synchronized(bucket) {
            val elapsedMinutes = (current - bucket.lastRefillMillis).coerceAtLeast(0) / 60_000.0
            bucket.tokens = (bucket.tokens + elapsedMinutes * budget.refillPerMinute)
                .coerceAtMost(budget.capacity.toDouble())
            bucket.lastRefillMillis = current
            bucket.lastUsedMillis = current

            return if (bucket.tokens >= cost) {
                bucket.tokens -= cost
                true
            } else {
                false
            }
        }
    }

    /** Seconds until [key] has a token again, for a Retry-After style hint. */
    fun retryAfterSeconds(key: String, budget: Budget): Int {
        val bucket = buckets[key] ?: return 0
        synchronized(bucket) {
            if (bucket.tokens >= 1) return 0
            val needed = 1 - bucket.tokens
            return Math.ceil(needed / budget.refillPerMinute * 60.0).toInt().coerceAtLeast(1)
        }
    }

    /** Forgets a key outright - used when a connection closes. */
    fun forget(key: String) {
        buckets.remove(key)
    }

    /** Drops buckets nobody has touched for [idleMillis]. Called from the cleanup timer. */
    fun evictIdle(idleMillis: Long = DEFAULT_IDLE_EVICTION_MILLIS) {
        val cutoff = now() - idleMillis
        buckets.entries.removeIf { (_, bucket) ->
            synchronized(bucket) { bucket.lastUsedMillis < cutoff }
        }
    }

    /** Visible for tests. */
    internal fun size(): Int = buckets.size

    companion object {
        /** How long an untouched bucket survives before [evictIdle] drops it: ten minutes. */
        const val DEFAULT_IDLE_EVICTION_MILLIS = 10 * 60 * 1000L

        // Per connection.
        /** Every message on one connection: bursts of 60, sustained 30 a second. */
        val ALL_MESSAGES = Budget(capacity = 60, refillPerMinute = 30 * 60.0)
        /** Move submissions on one connection. */
        val MOVES = Budget.perSecond(10)
        /** Rooms one connection may create. */
        val HOST = Budget.perMinute(5)
        /** Join attempts on one connection. */
        val JOIN = Budget.perMinute(10)
        /** Leaderboard page requests on one connection. */
        val LEADERBOARD = Budget.perMinute(10)
        /**
         * Roster edits from the lobby (seating an AI). Each one broadcasts to the whole room,
         * and a room has at most four seats, so a host has no legitimate need to burst.
         */
        val LOBBY_EDIT = Budget.perMinute(30)
        /**
         * A resync answer is a full GameState. Generous enough that a client on a bad
         * connection can recover several times a minute, tight enough that asking in a loop
         * is not an amplification lever.
         */
        val RESYNC = Budget.perMinute(12)
        /**
         * Account creation and login are expensive (PBKDF2) and worth guessing at, so they
         * get their own tight budget keyed per connection.
         */
        val AUTH = Budget.perMinute(5)

        /**
         * Track sharing: uploads are expensive (server rebuilds the track) and a flood would
         * be a denial-of-service vector through the build dispatcher.
         */
        val UPLOAD_TRACK = Budget.perMinute(3)
        /** Shared-track list requests on one connection. */
        val TRACK_LIST = Budget.perMinute(20)
        /** Track reports one connection may file. */
        val REPORT_TRACK = Budget.perMinute(5)
        /** Ratings are cheap but a flood could skew aggregates, so budget per connection. */
        val RATE_TRACK = Budget.perMinute(20)

        /** Per (connection, room) - wrong room passwords. */
        val ROOM_PASSWORD_ATTEMPTS = Budget(capacity = 5, refillPerMinute = 1.0)

        // ---- Per IP ---------------------------------------------------------------------
        //
        // Everything above is keyed by Connection.id, which is a fresh UUID per socket - so a
        // client that wants more allowance only has to reconnect. These are the budgets that
        // actually bind on a determined client.
        //
        // They are set several times looser than their per-connection twins on purpose: a
        // household, a school or a mobile carrier's CGNAT pool all present as one address, and
        // a limit that four people playing together can hit is a bug, not a defence. The
        // numbers below are sized for "a dozen people behind one NAT playing normally" while
        // still being far below what a flood needs.

        /** Four players at the per-connection ceiling, plus headroom. */
        val IP_ALL_MESSAGES = Budget(capacity = 240, refillPerMinute = 120 * 60.0)

        /**
         * The one that matters. Twelve register/login attempts a minute from one address is
         * generous for a family sharing a router and useless for a password-guessing loop.
         */
        val IP_AUTH = Budget.perMinute(12)

        /** Rooms one address may create, across all its connections. */
        val IP_HOST = Budget.perMinute(20)
        /** Join attempts from one address, across all its connections. */
        val IP_JOIN = Budget.perMinute(40)
        /** Track uploads from one address, across all its connections. */
        val IP_UPLOAD_TRACK = Budget.perMinute(10)
    }
}
