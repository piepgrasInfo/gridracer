package info.piepgras.gridracer.server.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private var now = 0L
    private val limiter = RateLimiter { now }

    private val tenPerMinute = RateLimiter.Budget.perMinute(10)

    @Test
    fun allowsUpToCapacityThenRefuses() {
        repeat(10) { assertTrue("call ${it + 1} should be allowed", limiter.allow("k", tenPerMinute)) }

        assertFalse(limiter.allow("k", tenPerMinute))
    }

    @Test
    fun refillsOverTime() {
        repeat(10) { limiter.allow("k", tenPerMinute) }
        assertFalse(limiter.allow("k", tenPerMinute))

        now += 6_000 // a tenth of a minute -> one token back
        assertTrue(limiter.allow("k", tenPerMinute))
        assertFalse(limiter.allow("k", tenPerMinute))
    }

    @Test
    fun refillNeverExceedsCapacity() {
        repeat(10) { limiter.allow("k", tenPerMinute) }
        now += 60 * 60 * 1000 // an hour

        repeat(10) { assertTrue(limiter.allow("k", tenPerMinute)) }
        assertFalse("capacity is a ceiling, not a running total", limiter.allow("k", tenPerMinute))
    }

    @Test
    fun keysAreIndependent() {
        repeat(10) { limiter.allow("a", tenPerMinute) }

        assertFalse(limiter.allow("a", tenPerMinute))
        assertTrue("a different key has its own budget", limiter.allow("b", tenPerMinute))
    }

    @Test
    fun reportsWhenToRetry() {
        repeat(10) { limiter.allow("k", tenPerMinute) }

        val retry = limiter.retryAfterSeconds("k", tenPerMinute)
        assertTrue("expected a positive retry hint, got $retry", retry in 1..60)
    }

    @Test
    fun forgettingAKeyResetsIt() {
        repeat(10) { limiter.allow("k", tenPerMinute) }
        assertFalse(limiter.allow("k", tenPerMinute))

        limiter.forget("k")

        assertTrue(limiter.allow("k", tenPerMinute))
    }

    @Test
    fun idleBucketsAreEvicted() {
        // Without eviction the bucket map is itself an unbounded leak - which is the shape of
        // bug this whole class exists to prevent.
        limiter.allow("a", tenPerMinute)
        limiter.allow("b", tenPerMinute)
        assertEquals(2, limiter.size())

        now += RateLimiter.DEFAULT_IDLE_EVICTION_MILLIS + 1
        limiter.allow("b", tenPerMinute) // keeps b fresh
        limiter.evictIdle()

        assertEquals(1, limiter.size())
        assertTrue(limiter.allow("b", tenPerMinute))
    }

    @Test
    fun perSecondAndPerHourBudgetsScale() {
        val perSecond = RateLimiter.Budget.perSecond(2)
        assertTrue(limiter.allow("s", perSecond))
        assertTrue(limiter.allow("s", perSecond))
        assertFalse(limiter.allow("s", perSecond))

        now += 500
        assertTrue("half a second should return one of two tokens", limiter.allow("s", perSecond))
    }
}
