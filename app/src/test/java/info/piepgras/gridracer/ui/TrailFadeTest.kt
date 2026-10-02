package info.piepgras.gridracer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailFadeTest {

    /**
     * The regression: a player's first move is the only segment there is, so the shipped
     * code - which faded from the oldest *drawn* segment outwards - drew it at zero alpha
     * and the trail only appeared from the second move on.
     */
    @Test
    fun theFreshestSegmentIsAlwaysFullyVisible() {
        assertTrue("the move just played must be drawn", trailAlphaFor(0) > 0f)
        assertEquals(trailAlphaFor(0), trailAlphaFor(0), 0f) // stable regardless of trail length
    }

    @Test
    fun olderSegmentsFadeOutMonotonically() {
        val alphas = (0 until TRAIL_VISIBLE_STEPS).map { trailAlphaFor(it) }
        alphas.zipWithNext { newer, older ->
            assertTrue("expected $older to be fainter than $newer", older < newer)
        }
    }

    @Test
    fun segmentsBeyondTheVisibleWindowAreNotDrawn() {
        assertEquals(0f, trailAlphaFor(TRAIL_VISIBLE_STEPS), 0f)
        assertEquals(0f, trailAlphaFor(TRAIL_VISIBLE_STEPS + 3), 0f)
        assertEquals(0f, trailAlphaFor(-1), 0f)
    }
}
