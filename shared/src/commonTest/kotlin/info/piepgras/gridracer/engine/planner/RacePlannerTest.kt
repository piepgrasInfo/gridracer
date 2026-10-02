package info.piepgras.gridracer.engine.planner

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.ModifierType
import info.piepgras.gridracer.engine.Placement
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.TrackBuilder
import info.piepgras.gridracer.engine.TrackModifier
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.engine.Wall
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun loop(vararg points: Point): List<Wall> = points.indices.map { Wall(points[it], points[(it + 1) % points.size]) }

/** A small ring at subdivision 1, small enough to solve by brute force. */
private fun smallRing(withNails: Boolean): Track {
    val walls = loop(Point(0, 0), Point(13, 0), Point(13, 10), Point(0, 10)) +
        loop(Point(4, 3), Point(9, 3), Point(9, 7), Point(4, 7))
    val finishLine = Wall(Point(6, 7), Point(6, 10))
    val direction = Point(-1, 0)
    val grid = TrackBuilder.startGrid(walls, finishLine, direction, 1)
    return Track(
        name = "Small",
        width = 13,
        height = 10,
        walls = walls,
        modifiers = if (withNails) listOf(TrackModifier(ModifierType.NAILS, listOf(Point(5, 0), Point(8, 0), Point(8, 3), Point(5, 3)))) else emptyList(),
        finishLine = finishLine,
        finishDirection = direction,
        startPositions = grid.positions,
        startZoneDepth = grid.depth,
    )
}

class RacePlannerTest {

    /**
     * The fewest moves a lone car needs to finish [laps] laps from the best start slot, by brute
     * force: a breadth-first search over real game states, every move made by [GameEngine.applyMove].
     */
    private fun bruteForce(track: Track, rules: RaceRules, laps: Int): Int? {
        data class Key(val p: Point, val v: Point, val laps: Int, val brake: Int, val pending: Set<ModifierType>, val starting: Boolean)
        fun keyOf(s: GameState): Key {
            val me = s.players[0]
            return Key(me.position, me.lastVelocity, me.lapsCompleted, me.gravelBrakeTurns, me.pendingModifiers, me.path.size == 1)
        }
        var frontier = track.startPositions.map { GameEngine.newGame(track, listOf(it), laps, rules) }
        val seen = frontier.mapTo(mutableSetOf()) { keyOf(it) }
        var depth = 0
        while (frontier.isNotEmpty() && depth < 150) {
            depth++
            val next = mutableListOf<GameState>()
            for (state in frontier) {
                for (move in GameEngine.legalMoves(state, 0)) {
                    val after = GameEngine.applyMove(state, 0, move, Random(0))
                    val me = after.players[0]
                    if (me.status == PlayerStatus.FINISHED) return depth
                    if (me.status != PlayerStatus.RACING || after.phase != GamePhase.IN_PROGRESS) continue
                    if (seen.add(keyOf(after))) next += after
                }
            }
            frontier = next
        }
        return null
    }

    private fun bestStart(table: PlannerTable, track: Track, laps: Int): Int =
        track.startPositions.mapNotNull { table.startValue(it, laps) }.min()

    @Test
    fun theTable_matchesABruteForceSearch_onAndOffTheGravel_withAndWithoutNails() {
        for (nails in listOf(false, true)) {
            val track = smallRing(nails)
            for (gravel in listOf(false, true)) {
                val rules = RaceRules.current(gravelTrap = gravel)
                val table = assertNotNull(RacePlanner.build(track, rules, AiTuning.BEST_PROFILE))
                for (laps in 1..2) {
                    val expected = assertNotNull(bruteForce(track, rules, laps), "nails=$nails gravel=$gravel laps=$laps")
                    assertEquals(expected, bestStart(table, track, laps), "nails=$nails gravel=$gravel laps=$laps")
                }
            }
        }
    }

    @Test
    fun aSpeedCappedTable_isTheBestRaceAtThatSpeed() {
        val track = smallRing(withNails = false)
        val rules = RaceRules.current(gravelTrap = false)
        val capped = assertNotNull(RacePlanner.build(track, rules, PlannerProfile(maxSpeed = 2)))
        val free = assertNotNull(RacePlanner.build(track, rules, AiTuning.BEST_PROFILE))
        assertTrue(bestStart(capped, track, 1) >= bestStart(free, track, 1))
    }

    @Test
    fun bestDrivesItsOwnPlan_movesLeftDropByOneEveryMove() {
        for (track in Tracks.all) {
            for (gravel in listOf(false, true)) {
                val rules = RaceRules.current(gravelTrap = gravel)
                val table = assertNotNull(RacePlanner.build(track.copy(modifiers = emptyList()), rules, AiTuning.BEST_PROFILE))
                val clean = track.copy(modifiers = emptyList())
                val slot = clean.startPositions.minBy { table.startValue(it, 2)!! }
                var state = GameEngine.newGame(clean, listOf(slot), totalLaps = 2, rules = rules)
                var expected = assertNotNull(table.startValue(slot, 2))
                val random = Random(3)
                while (state.phase == GamePhase.IN_PROGRESS) {
                    val me = state.players[0]
                    val move = assertNotNull(AiDriver.chooseMove(state, 0, AiLevel.BEST, table, random))
                    val value = assertNotNull(table.valueOfMove(state, me, move))
                    assertEquals(expected - 1, value, "${track.name} gravel=$gravel at ${me.position}")
                    expected = value
                    state = GameEngine.applyMove(state, 0, move, Random(0))
                }
                assertEquals(PlayerStatus.FINISHED, state.players[0].status, track.name)
                assertEquals(0, expected)
            }
        }
    }

    @Test
    fun lapTimesRepeat_soLongRacesExtendTheTableExactly() {
        for (track in Tracks.all) {
            val table = assertNotNull(RacePlanner.build(track, RaceRules.current(gravelTrap = true), AiTuning.BEST_PROFILE))
            for (slot in track.startPositions) {
                val v2 = table.startValue(slot, 2) ?: continue
                val v3 = table.startValue(slot, 3)!!
                val v4 = table.startValue(slot, 4)!!
                if (v2 == RacePlanner.DOOMED) continue
                assertEquals(v3 - v2, v4 - v3, "${track.name} from $slot")
                assertEquals(v4 - v3, table.lapLength, "${track.name} from $slot")
                assertEquals(v4 + 3 * table.lapLength, table.startValue(slot, 7), "${track.name} from $slot")
            }
        }
    }

    @Test
    fun aTrackTooLargeForTheBudget_hasNoTable_andTheDriverFallsBackToEasy() {
        val state = GameEngine.newGame(Tracks.OVAL, 2)
        assertNull(RacePlanner.build(Tracks.OVAL, state.rules, AiTuning.BEST_PROFILE, maxNodes = 50))
        val move = assertNotNull(AiDriver.chooseMove(state, 0, AiLevel.BEST, table = null))
        assertTrue(move in GameEngine.legalMoves(state, 0))
    }

    @Test
    fun bestPicksTheStartSlotWithTheShortestRace() {
        val track = Tracks.SPEEDWAY
        val rules = RaceRules.current(gravelTrap = false)
        val table = assertNotNull(RacePlanner.build(track, rules, AiTuning.BEST_PROFILE))
        val placement = Placement.start(track, List(3) { PlayerConfig("AI $it", true, AiLevel.BEST) }, randomized = false)
        val chosen = AiDriver.chooseStartSlot(placement, laps = 3, level = AiLevel.BEST, table = table, random = Random(1))
        val best = track.startPositions.minOf { table.startValue(it, 3)!! }
        assertEquals(best, table.startValue(chosen, 3))
    }

    @Test
    fun theBuiltInTracks_fitComfortablyInTheBudget() {
        for (track in Tracks.all) {
            for (gravel in listOf(false, true)) {
                val table = assertNotNull(RacePlanner.build(track, RaceRules.current(gravel), AiTuning.BEST_PROFILE))
                assertTrue(table.size < AiTuning.MAX_NODES / 10, "${track.name}: ${table.size} states")
            }
        }
    }
}
