package info.piepgras.gridracer.engine

import info.piepgras.gridracer.replay.MoveRecord
import info.piepgras.gridracer.replay.ReplayData
import info.piepgras.gridracer.replay.replayFinalState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun loop(vararg points: Point): List<Wall> = points.indices.map { Wall(points[it], points[(it + 1) % points.size]) }

/**
 * A rectangle 50 x 30 with an island, at subdivision 1 so a fine unit is a coarse unit and the
 * band is exactly two grid points deep. The finish line runs from the island down to the outer
 * wall at x = 35 and the race crosses it leftwards.
 */
private val ring = Track(
    name = "Ring",
    width = 70,
    height = 50,
    walls = loop(Point(10, 10), Point(60, 10), Point(60, 40), Point(10, 40)) +
        loop(Point(20, 20), Point(50, 20), Point(50, 30), Point(20, 30)),
    finishLine = Wall(Point(35, 30), Point(35, 40)),
    finishDirection = Point(-1, 0),
    startPositions = listOf(Point(36, 35)),
    startZoneDepth = 1.5,
)

class GravelTrapTest {

    private val on = RaceRules.current(gravelTrap = true)
    private val off = RaceRules.current(gravelTrap = false)

    private fun racer(
        position: Point,
        velocity: Point,
        rules: RaceRules = on,
        brake: Int = 0,
        pending: Set<ModifierType> = emptySet(),
        track: Track = ring,
        others: List<Point> = emptyList(),
    ): GameState {
        val me = PlayerState(
            id = 0,
            position = position,
            lastVelocity = velocity,
            path = listOf(position - velocity, position),
            gravelBrakeTurns = brake,
            pendingModifiers = pending,
        )
        val rest = others.mapIndexed { i, p -> PlayerState(id = i + 1, position = p, path = listOf(p, p)) }
        return GameState(track = track, players = listOf(me) + rest, currentPlayerIndex = 0, totalLaps = 3, rules = rules)
    }

    private fun gravel(track: Track = ring) = assertNotNull(track.geometry.gravel)

    // ---- the band ---------------------------------------------------------------------------

    @Test
    fun band_isTwoUnitsDeepOutsideTheOuterWall_andRoundedAtTheCorners() {
        val g = gravel()
        assertEquals(GravelGeometry.INSIDE, g.classOf(Point(45, 39)))
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(45, 40)), "on the wall")
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(45, 41)))
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(45, 42)))
        assertEquals(GravelGeometry.BEYOND, g.classOf(Point(45, 43)))
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(8, 25)))
        assertEquals(GravelGeometry.BEYOND, g.classOf(Point(7, 25)))
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(9, 9)), "corner, sqrt(2) out")
        assertEquals(GravelGeometry.BEYOND, g.classOf(Point(8, 8)), "corner, sqrt(8) out")
    }

    @Test
    fun band_hasNoGravelInsideTheTrack_notEvenRoundTheIsland() {
        val g = gravel()
        assertEquals(GravelGeometry.INSIDE, g.classOf(Point(20, 19)))
        assertEquals(GravelGeometry.INSIDE, g.classOf(Point(35, 25)), "inside the island is inside the outer loop")
    }

    @Test
    fun band_isCutInLineWithTheFinishLine() {
        val g = gravel()
        for (y in 41..42) assertEquals(GravelGeometry.BEYOND, g.classOf(Point(35, y)), "stub at y=$y")
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(34, 41)))
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(36, 41)))
    }

    @Test
    fun band_onTheBuiltInOval_reachesTwoUnitsOutAndPastTheGridEdge() {
        val g = gravel(Tracks.OVAL)
        // The Oval's outer wall runs along coarse y = 19 at the bottom and x = 1 on the left.
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(8, 21)))
        assertEquals(GravelGeometry.BEYOND, g.classOf(Point(8, 22)))
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(-1, 10)))
        assertEquals(GravelGeometry.BEYOND, g.classOf(Point(-2, 10)))
        val view = assertNotNull(Tracks.OVAL.gravelView)
        assertEquals(Point(-1, -1), view.minCoarse)
        assertEquals(Point(33, 21), view.maxCoarse)
    }

    @Test
    fun band_isCutAcrossANarrowNotch_soItCannotBeUsedToSkipTheBend() {
        // A U: two arms joined at the top, with a notch five units wide between them. Each side's
        // band reaches two units in, so the two bands meet in the middle of the notch.
        val u = Track(
            name = "U",
            width = 40,
            height = 40,
            walls = loop(
                Point(0, 0), Point(30, 0), Point(30, 30), Point(18, 30),
                Point(18, 8), Point(13, 8), Point(13, 30), Point(0, 30),
            ),
            finishLine = Wall(Point(0, 20), Point(13, 20)),
            finishDirection = Point(0, 1),
            startPositions = listOf(Point(5, 19)),
        )
        val g = gravel(u)
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(14, 25)), "left side's band")
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(17, 25)), "right side's band")
        assertEquals(GravelGeometry.BEYOND, g.classOf(Point(15, 25)), "seam")
        assertEquals(GravelGeometry.BEYOND, g.classOf(Point(16, 25)), "seam")
        assertFalse(g.walkClear(Point(17, 25), Point(14, 25)), "straight across the notch")
        assertFalse(g.walkClear(Point(17, 25), Point(14, 22)), "diagonally across the notch")
        assertTrue(g.walkClear(Point(17, 25), Point(17, 20)), "along one side of it")
        // Round the end of the notch the two bands are one, as the boundary is.
        assertEquals(GravelGeometry.GRAVEL, g.classOf(Point(15, 9)))
    }

    // ---- moves -------------------------------------------------------------------------------

    @Test
    fun gravelOff_theOuterWallStopsACarExactlyAsToday() {
        val moves = GameEngine.legalMoves(racer(Point(45, 38), Point(0, 1), rules = off), 0)
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.y <= 39 }, "$moves")
    }

    @Test
    fun gravelOn_aCarMayRunIntoTheBand_butNotPastTheBarrier() {
        val moves = GameEngine.legalMoves(racer(Point(45, 37), Point(0, 4)), 0)
        assertEquals((44..46).flatMap { x -> listOf(Point(x, 40), Point(x, 41), Point(x, 42)) }.toSet(), moves)
    }

    @Test
    fun gravelOn_theFinishLineCannotBeBypassedThroughTheBand() {
        val moves = GameEngine.legalMoves(racer(Point(37, 41), Point(-2, 0), brake = 0), 0)
        assertFalse(Point(34, 41) in moves, "$moves")
        assertTrue(Point(36, 41) in moves, "$moves")
    }

    @Test
    fun aCornerCutOverTheOuterWallAndBack_isLegal_andBraked() {
        // A rectangular dent pushes the outer wall in between x = 30 and 40.
        val dented = ring.copy(
            walls = loop(
                Point(10, 10), Point(60, 10), Point(60, 40), Point(40, 40),
                Point(40, 36), Point(30, 36), Point(30, 40), Point(10, 40),
            ) + loop(Point(20, 20), Point(50, 20), Point(50, 30), Point(20, 30)),
            finishLine = Wall(Point(55, 30), Point(55, 40)),
        )
        val start = racer(Point(28, 38), Point(14, 0), track = dented)
        assertTrue(Point(42, 38) in GameEngine.legalMoves(start, 0))
        assertFalse(Point(42, 38) in GameEngine.legalMoves(start.copy(rules = off), 0))
        assertFalse(Point(42, 39) in GameEngine.legalMoves(start, 0), "y = 39 is three units from the dent")
        val after = GameEngine.applyMove(start, 0, Point(42, 38))
        assertEquals(2, after.players[0].gravelBrakeTurns)
    }

    @Test
    fun brake_shortensTheFasterAxisByTwo_keepingTheHeading() {
        val expected = mapOf(
            Point(5, 0) to Point(3, 0),
            Point(0, -5) to Point(0, -3),
            Point(3, 3) to Point(1, 1),
            Point(4, 1) to Point(2, 1),
            Point(3, 1) to Point(1, 0),
            Point(3, 2) to Point(1, 1),
            Point(5, 2) to Point(3, 1),
            Point(7, -4) to Point(5, -3),
            Point(2, 1) to Point(0, 0),
            Point(-2, -2) to Point(0, 0),
            Point(0, 0) to Point(0, 0),
        )
        for ((velocity, braked) in expected) assertEquals(braked, MoveRules.brake(velocity), "$velocity")
    }

    @Test
    fun aBrakedCar_choosesAroundItsBrakedCoastPoint() {
        val state = racer(Point(30, 41), Point(4, 0), brake = 2)
        assertEquals(Point(32, 41), GameEngine.coastPoint(state, state.players[0]))
        val moves = GameEngine.legalMoves(state, 0)
        assertEquals((31..33).flatMap { x -> (40..42).map { y -> Point(x, y) } }.toSet(), moves)
    }

    @Test
    fun aSlowCarInTheGravel_canAlwaysStayOrStepOneUnit() {
        val moves = GameEngine.legalMoves(racer(Point(45, 41), Point(0, 1), brake = 2), 0)
        assertTrue(Point(45, 41) in moves)
        assertTrue(Point(45, 40) in moves && Point(44, 41) in moves && Point(46, 41) in moves)
    }

    @Test
    fun theBrake_lastsWhileInTheGravelAndForTwoMovesAfterLeavingIt() {
        var state = racer(Point(45, 38), Point(0, 2))
        val counters = mutableListOf<Int>()
        fun step(target: Point) {
            state = GameEngine.applyMove(state, 0, target)
            counters += state.players[0].gravelBrakeTurns
        }
        step(Point(45, 41)) // into the band at speed 3; braked to 1, the choices are rows 41-43
        step(Point(45, 41)) // stop in it
        step(Point(45, 40)) // onto the wall line, still in the band
        step(Point(45, 39)) // back onto the track: a move from the band touches it too
        step(Point(45, 38)) // first clean move: one braked move left
        step(Point(45, 37)) // second: free again
        assertEquals(listOf(2, 2, 2, 2, 1, 0), counters)
        assertEquals(Point(45, 36), GameEngine.coastPoint(state, state.players[0]), "no longer braked")
    }

    @Test
    fun nailsAndOil_actOnTheBrakedCoast() {
        val nails = GameEngine.legalMoves(racer(Point(30, 41), Point(4, 0), brake = 2, pending = setOf(ModifierType.NAILS)), 0)
        assertEquals(setOf(Point(31, 41), Point(31, 40), Point(31, 42)), nails)
        val oil = GameEngine.legalMoves(racer(Point(30, 41), Point(4, 0), brake = 2, pending = setOf(ModifierType.OIL_SPILL)), 0)
        assertEquals(setOf(Point(32, 41)), oil)
    }

    @Test
    fun aCarArrivingTooFast_crashesIntoTheBarrierOnItsNextTurn() {
        val entered = GameEngine.applyMove(racer(Point(45, 37), Point(0, 5)), 0, Point(45, 42))
        val me = entered.players[0]
        assertEquals(PlayerStatus.CRASHED, me.status)
        assertEquals(Point(45, 42), me.wreckPosition)
    }

    @Test
    fun otherCars_blockInTheGravelAsOnTheTrack() {
        val moves = GameEngine.legalMoves(racer(Point(45, 38), Point(0, 3), others = listOf(Point(45, 41))), 0)
        assertFalse(Point(45, 41) in moves)
        assertFalse(Point(45, 42) in moves, "the path runs through the other car")
        assertTrue(Point(44, 41) in moves)
    }

    @Test
    fun aRaceWithTheGravelTrap_replaysExactlyFromItsRecord() {
        val random = Random(11)
        for (track in Tracks.all) {
            val start = GameEngine.newGame(track, 3, totalLaps = 2, rules = on)
            var state = start
            val moves = mutableListOf<MoveRecord>()
            var steps = 0
            while (state.phase == GamePhase.IN_PROGRESS && steps < 120) {
                val player = state.currentPlayer
                val choices = GameEngine.legalMoves(state, player.id).toList()
                // Favour the gravel so the replay actually exercises the brake.
                val pick = choices.maxByOrNull { if (track.geometry.gravel!!.classOf(it) == GravelGeometry.GRAVEL) random.nextInt(10, 20) else random.nextInt(0, 12) }!!
                val seed = random.nextLong()
                moves += MoveRecord(player.id, pick, seed)
                state = GameEngine.applyMove(state, player.id, pick, Random(seed))
                steps++
            }
            val replay = ReplayData(trackName = track.name, configs = List(3) { PlayerConfig("P$it", false) }, initialState = start, moves = moves)
            assertEquals(state, replay.replayFinalState(), track.name)
            assertTrue(moves.any { m -> track.geometry.gravel!!.classOf(m.target) == GravelGeometry.GRAVEL }, track.name)
        }
    }
}
