package info.piepgras.gridracer

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.engine.planner.PlannerTable
import info.piepgras.gridracer.engine.planner.RacePlanner
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class AiPlannerCacheTest {

    private val rules = RaceRules.current(gravelTrap = true)

    @Test
    fun aTableIsBuiltOncePerTrackRulesAndLevel_andSharedBySeats() = runBlocking {
        val builds = AtomicInteger()
        val cache = AiPlannerCache(this, Dispatchers.Default, build = { track, r, profile ->
            builds.incrementAndGet()
            RacePlanner.build(track, r, profile)
        })
        cache.prefetch(Tracks.OVAL, rules, listOf(AiLevel.BEST, AiLevel.BEST, AiLevel.NORMAL, AiLevel.EASY))
        assertNotNull(cache.table(Tracks.OVAL, rules, AiLevel.BEST))
        assertNotNull(cache.table(Tracks.OVAL, rules, AiLevel.NORMAL))
        // An equal track decoded elsewhere finds the same table.
        assertNotNull(cache.table(Tracks.OVAL.copy(), rules, AiLevel.BEST))
        assertEquals(2, builds.get())
    }

    @Test
    fun easy_needsNoTable() = runBlocking {
        val cache = AiPlannerCache(this, Dispatchers.Default)
        assertNull(cache.table(Tracks.OVAL, rules, AiLevel.EASY))
    }

    @Test
    fun aBuildThatTakesTooLong_leavesTheSeatToDriveLikeEasy() = runBlocking {
        val cache = AiPlannerCache(this, Dispatchers.Default, build = { _, _, _ ->
            Thread.sleep(500)
            null as PlannerTable?
        })
        assertNull(cache.table(Tracks.OVAL, rules, AiLevel.BEST, timeout = 50.milliseconds))
    }
}
