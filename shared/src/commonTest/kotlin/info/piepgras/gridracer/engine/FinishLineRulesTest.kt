package info.piepgras.gridracer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How passes over the finish line are counted under each rules version. The line here runs from
 * (10,0) to (10,20) and the race crosses it in +x.
 */
class FinishLineRulesTest {

    private val track = Track(
        name = "Line",
        width = 40,
        height = 40,
        walls = emptyList(),
        finishLine = Wall(Point(10, 0), Point(10, 20)),
        finishDirection = Point(1, 0),
        startPositions = listOf(Point(9, 5), Point(10, 6)),
        startZoneDepth = 1.5,
    )

    private val v1 = RaceRules.LEGACY
    private val v2 = RaceRules.current(gravelTrap = false)

    private fun racer(position: Point, velocity: Point, rules: RaceRules, starting: Boolean = false): GameState {
        val path = if (starting) listOf(position) else listOf(position - velocity, position)
        val player = PlayerState(id = 0, position = position, lastVelocity = velocity, path = path)
        return GameState(track = track, players = listOf(player), currentPlayerIndex = 0, totalLaps = 5, rules = rules)
    }

    private fun GameState.move(target: Point): GameState = GameEngine.applyMove(this, 0, target)
    private val GameState.me: PlayerState get() = players[0]

    @Test
    fun version1_countsAMoveOntoTheLineAndTheMoveOffItAsTwoLaps() {
        val after = racer(Point(8, 5), Point(1, 0), v1).move(Point(10, 5)).move(Point(11, 5))
        assertEquals(2, after.me.lapsCompleted)
    }

    @Test
    fun version2_countsThatSamePassOnce() {
        val onLine = racer(Point(8, 5), Point(1, 0), v2).move(Point(10, 5))
        assertEquals(1, onLine.me.lapsCompleted, "reaching the line counts")
        assertEquals(1, onLine.move(Point(11, 5)).me.lapsCompleted, "leaving it forward does not count again")
    }

    @Test
    fun version2_aMoveStraightOverTheLineCountsOnce() {
        assertEquals(1, racer(Point(8, 5), Point(2, 0), v2).move(Point(11, 5)).me.lapsCompleted)
    }

    @Test
    fun bothVersions_crashACarThatBacksOffTheLine() {
        for (rules in listOf(v1, v2)) {
            val after = racer(Point(10, 5), Point(0, 1), rules).move(Point(9, 6))
            assertEquals(PlayerStatus.CRASHED, after.me.status, "version ${rules.version}")
        }
    }

    @Test
    fun version2_backingOntoTheLineFromAheadIsNotYetACrash_butCrossingItIs() {
        val onLine = racer(Point(11, 5), Point(-1, 0), v2).move(Point(10, 5))
        assertEquals(PlayerStatus.RACING, onLine.me.status)
        assertEquals(0, onLine.me.lapsCompleted)
        assertEquals(PlayerStatus.CRASHED, onLine.move(Point(9, 5)).me.status)
        // Version 1 called the first of those moves a crash already.
        assertEquals(PlayerStatus.CRASHED, racer(Point(11, 5), Point(-1, 0), v1).move(Point(10, 5)).me.status)
    }

    @Test
    fun version2_aCarDrivingAlongTheLineNeitherScoresNorCrashes() {
        val along = racer(Point(10, 5), Point(0, 1), v2).move(Point(10, 7))
        assertEquals(PlayerStatus.RACING, along.me.status)
        assertEquals(0, along.me.lapsCompleted)
    }

    @Test
    fun startSlotOnTheLine_stillLeavesForward_andTheOpeningMoveIsNoLap() {
        for (rules in listOf(v1, v2)) {
            val start = racer(Point(10, 6), Point(0, 0), rules, starting = true)
            val moves = GameEngine.legalMoves(start, 0)
            assertTrue(moves.isNotEmpty() && moves.all { it.x == 11 }, "version ${rules.version}: $moves")
            assertEquals(0, start.move(Point(11, 6)).me.lapsCompleted, "version ${rules.version}")
        }
    }

    @Test
    fun anOpeningMoveEndingOnTheLine_isNoLap_andUnderVersion2NeitherIsLeavingIt() {
        val onLine = racer(Point(9, 5), Point(0, 0), v2, starting = true).move(Point(10, 5))
        assertEquals(0, onLine.me.lapsCompleted)
        assertEquals(0, onLine.move(Point(11, 5)).me.lapsCompleted)
        // Version 1 handed out a free lap here.
        val legacy = racer(Point(9, 5), Point(0, 0), v1, starting = true).move(Point(10, 5)).move(Point(11, 5))
        assertEquals(1, legacy.me.lapsCompleted)
    }

    @Test
    fun aRealLap_isCountedOnceAroundTheTrackUnderVersion2() {
        // Behind the line, across, round the back and across again.
        var state = racer(Point(9, 5), Point(0, 0), v2, starting = true).move(Point(10, 5))
        state = state.copy(players = listOf(state.me.copy(position = Point(8, 9), lastVelocity = Point(1, 0))))
        state = state.move(Point(10, 9))
        assertEquals(1, state.me.lapsCompleted)
        state = state.move(Point(12, 9))
        assertEquals(1, state.me.lapsCompleted)
    }

    @Test
    fun newGame_startsOnTheCurrentRules_andAStoredStateWithoutRulesIsLegacy() {
        assertEquals(RaceRules.CURRENT_VERSION, GameEngine.newGame(Tracks.OVAL, 2).rules.version)
        assertEquals(RaceRules.LEGACY, GameState(track = track, players = listOf(PlayerState(0, Point(9, 5))), currentPlayerIndex = 0).rules)
    }
}
