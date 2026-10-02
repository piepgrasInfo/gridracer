package info.piepgras.gridracer.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun emptyTrack(
    walls: List<Wall> = emptyList(),
    finishLine: Wall = Wall(Point(1000, 1000), Point(1001, 1000)),
    finishDirection: Point = Point(0, 1),
) = Track(
    name = "Test",
    width = 100,
    height = 100,
    walls = walls,
    finishLine = finishLine,
    finishDirection = finishDirection,
    startPositions = listOf(Point(0, 0)),
)

class GameEngineTest {

    @Test
    fun candidateMoves_withZeroVelocity_isThreeByThreeAroundCurrentPosition() {
        val player = PlayerState(id = 0, position = Point(5, 5))
        val expected = (4..6).flatMap { x -> (4..6).map { y -> Point(x, y) } }.toSet()
        assertEquals(expected, GameEngine.candidateMoves(player))
    }

    @Test
    fun candidateMoves_withVelocity_isCenteredOnProjectedPoint() {
        val player = PlayerState(id = 0, position = Point(5, 5), lastVelocity = Point(1, 0))
        val expected = (5..7).flatMap { x -> (4..6).map { y -> Point(x, y) } }.toSet()
        assertEquals(expected, GameEngine.candidateMoves(player))
    }

    @Test
    fun legalMoves_excludesTargetsBlockedByAWall() {
        val track = emptyTrack(walls = listOf(Wall(Point(5, 0), Point(5, 10))))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5), lastVelocity = Point(1, 0))),
            currentPlayerIndex = 0,
        )
        // Center is (5,5); every candidate with x=5 touches the wall, x=6 crosses it, only x=4 stays clear.
        assertEquals(setOf(Point(4, 4), Point(4, 5), Point(4, 6)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun legalMoves_wallCoordinatesAreScaledByTrackSubdivision() {
        // With subdivision=2, a wall at fine-grid x=11 sits at coarse x=5.5 - a position with
        // no coarse-grid equivalent, only expressible because walls are authored on the finer grid.
        val track = Track(
            name = "Test",
            width = 100,
            height = 100,
            subdivision = 2,
            walls = listOf(Wall(Point(11, -20), Point(11, 20))),
            finishLine = Wall(Point(1000, 1000), Point(1001, 1000)),
            finishDirection = Point(0, 1),
            startPositions = listOf(Point(0, 0)),
        )
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5), lastVelocity = Point(1, 0))),
            currentPlayerIndex = 0,
        )
        // Center is (5,5); every candidate with x=6 crosses fine-grid x=11 (coarse 5.5), x=5 and x=4 stay clear.
        val moves = GameEngine.legalMoves(state, 0)
        assertTrue(Point(5, 4) in moves)
        assertTrue(Point(4, 4) in moves)
        assertFalse(Point(6, 4) in moves)
        assertFalse(Point(6, 5) in moves)
        assertFalse(Point(6, 6) in moves)
    }

    @Test
    fun legalMoves_excludesTargetOccupiedByAnotherCar() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(0, 0)),
                PlayerState(id = 1, position = Point(1, 1)),
            ),
            currentPlayerIndex = 0,
        )
        val moves = GameEngine.legalMoves(state, 0)
        assertFalse(Point(1, 1) in moves)
        assertEquals(8, moves.size)
    }

    @Test
    fun legalMoves_excludesPathsThatPassThroughAnotherCarMidway() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(0, 0), lastVelocity = Point(3, 3)),
                PlayerState(id = 1, position = Point(2, 2)),
            ),
            currentPlayerIndex = 0,
        )
        val moves = GameEngine.legalMoves(state, 0)
        // (2,2) is a lattice point strictly between (0,0) and these candidates.
        assertFalse(Point(4, 4) in moves)
        assertFalse(Point(3, 3) in moves)
        assertFalse(Point(2, 2) in moves)
        // (4,3) doesn't pass through (2,2) and isn't blocked.
        assertTrue(Point(4, 3) in moves)
    }

    @Test
    fun legalMoves_atTheStartLine_onlyAllowsMovingForwardOffIt() {
        // Sitting right on the start/finish line (as newGame places players) - only the
        // three candidates that head in finishDirection should be offered.
        val track = emptyTrack(finishLine = Wall(Point(5, 0), Point(5, 10)), finishDirection = Point(1, 0))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(5, 5))),
            currentPlayerIndex = 0,
        )
        assertEquals(setOf(Point(6, 4), Point(6, 5), Point(6, 6)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun legalMoves_afterTheFirstMove_noLongerRestrictsDirectionAtTheLine() {
        // Once a player has actually made a move, sitting back on the line (e.g. having
        // looped around) is no longer treated as the special starting case.
        val track = emptyTrack(finishLine = Wall(Point(5, 0), Point(5, 10)), finishDirection = Point(1, 0))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(5, 5), path = listOf(Point(4, 5), Point(5, 5)))),
            currentPlayerIndex = 0,
        )
        val moves = GameEngine.legalMoves(state, 0)
        assertTrue(Point(4, 5) in moves)
        assertTrue(Point(6, 5) in moves)
    }

    @Test
    fun applyMove_leavingTheStartLineOnTheFirstMoveDoesNotCountAsALapCrossing() {
        val track = emptyTrack(finishLine = Wall(Point(5, 0), Point(5, 10)), finishDirection = Point(1, 0))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(5, 5))),
            currentPlayerIndex = 0,
        )
        val result = GameEngine.applyMove(state, 0, Point(6, 5))
        assertEquals(PlayerStatus.RACING, result.players[0].status)
        assertEquals(0, result.players[0].lapsCompleted)
        assertEquals(null, result.players[0].finishRank)
    }

    @Test
    fun applyMove_startingNearAnAngledFinishLineStillDoesNotCountAsALapCrossing() {
        // Start positions are the nearest *coarse* grid point to a spot on the line, so for
        // an angled line they generally sit close to it but not exactly on it - here (4,5) is
        // ~0.71 units from the diagonal finishLine (0,0)-(10,10), the worst-case rounding gap.
        val track = emptyTrack(finishLine = Wall(Point(0, 0), Point(10, 10)), finishDirection = Point(1, -1))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5))),
            currentPlayerIndex = 0,
        )
        // This move is a legal forward move, and geometrically crosses the diagonal line at
        // (4.5, 4.5) - without the near-line starting-move exemption, it would wrongly finish the race.
        val target = Point(5, 4)
        assertTrue(target in GameEngine.legalMoves(state, 0))
        val result = GameEngine.applyMove(state, 0, target)
        assertEquals(PlayerStatus.RACING, result.players[0].status)
        assertEquals(0, result.players[0].lapsCompleted)
        assertEquals(GamePhase.IN_PROGRESS, result.phase)
    }

    @Test
    fun legalMoves_firstMoveRequiresForwardFinishLineCrossing() {
        // From a valid non-congruent start slot (4,5) ~0.71 units from the diagonal finish
        // line, only moves that actually cross the start/finish line in the forward direction
        // are offered - sideways, backward, or non-crossing moves are not.
        val track = emptyTrack(finishLine = Wall(Point(0, 0), Point(10, 10)), finishDirection = Point(1, -1))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5))),
            currentPlayerIndex = 0,
        )
        val moves = GameEngine.legalMoves(state, 0)
        // Forward crossing moves:
        assertTrue(Point(4, 4) in moves)
        assertTrue(Point(5, 4) in moves)
        assertTrue(Point(5, 5) in moves)
        // Sideways, backwards, or non-crossing forward moves are not legal at the start.
        assertTrue(Point(4, 5) !in moves) // no movement
        assertTrue(Point(3, 4) !in moves)
        assertTrue(Point(3, 5) !in moves)
        assertTrue(Point(3, 6) !in moves)
        assertTrue(Point(4, 6) !in moves)
        assertTrue(Point(5, 6) !in moves)
    }

    @Test
    fun legalMoves_startingMoveWithAllForwardMovesBlockedOffersStopPoint() {
        // Player at (4,5) on a starting move, but walls block all three forward-crossing moves
        // ((4,4), (5,4), (5,5)). The stop point (4,5) is offered as a fallback so the player
        // can stay in place instead of being auto-crashed by startTurn.
        val track = emptyTrack(
            finishLine = Wall(Point(0, 0), Point(10, 10)),
            finishDirection = Point(1, -1),
            walls = listOf(
                Wall(Point(3, 4), Point(5, 4)), // blocks (4,4) and (5,4)
                Wall(Point(5, 4), Point(5, 6)), // blocks (5,5) and (5,4)
            ),
        )
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5))),
            currentPlayerIndex = 0,
        )
        val moves = GameEngine.legalMoves(state, 0)
        assertEquals(setOf(Point(4, 5)), moves)
    }

    @Test
    fun applyMove_startingMoveStayInPlaceKeepsStartingState() {
        // Staying in place on a starting move: the player stays RACING, path doesn't grow (so
        // isStartingMove is still true next turn), and the forward-crossing requirement still
        // applies — the only legal move next turn is still the stop point.
        val track = emptyTrack(
            finishLine = Wall(Point(0, 0), Point(10, 10)),
            finishDirection = Point(1, -1),
            walls = listOf(
                Wall(Point(3, 4), Point(5, 4)),
                Wall(Point(5, 4), Point(5, 6)),
            ),
        )
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5))),
            currentPlayerIndex = 0,
        )
        val result = GameEngine.applyMove(state, 0, Point(4, 5))
        assertEquals(PlayerStatus.RACING, result.players[0].status)
        assertEquals(1, result.players[0].path.size) // path didn't grow — still in starting state
        assertEquals(Point(4, 5), result.players[0].position) // didn't move
        // Next turn the forward-crossing requirement still applies: only the stop point is legal.
        val nextMoves = GameEngine.legalMoves(result, 0)
        assertEquals(setOf(Point(4, 5)), nextMoves)
    }

    @Test
    fun startTurn_crashesAPlayerWithNoLegalMoves() {
        val track = emptyTrack(walls = listOf(Wall(Point(1, -2), Point(1, 2))))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(0, 0), lastVelocity = Point(2, 0))),
            currentPlayerIndex = 0,
        )
        val result = GameEngine.startTurn(state)
        assertEquals(PlayerStatus.CRASHED, result.players[0].status)
        assertEquals(GamePhase.RACE_OVER, result.phase)
    }

    @Test
    fun applyMove_crossingFinishLineInTheRightDirectionFinishesThePlayer() {
        val track = emptyTrack(finishLine = Wall(Point(5, 0), Point(5, 10)), finishDirection = Point(1, 0))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5))),
            currentPlayerIndex = 0,
        )
        val result = GameEngine.applyMove(state, 0, Point(5, 5))
        assertEquals(PlayerStatus.FINISHED, result.players[0].status)
        assertEquals(1, result.players[0].finishRank)
        assertEquals(GamePhase.RACE_OVER, result.phase)
    }

    @Test
    fun applyMove_crossingFinishLineInReverseCrashesThePlayer() {
        val track = emptyTrack(finishLine = Wall(Point(5, 0), Point(5, 10)), finishDirection = Point(1, 0))
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(6, 5), lastVelocity = Point(-1, 0)),
                PlayerState(id = 1, position = Point(50, 50)),
            ),
            currentPlayerIndex = 0,
        )
        // Same gate, but travelling in the -x direction: driving backwards over the start/finish line crashes the player.
        val result = GameEngine.applyMove(state, 0, Point(5, 5))
        assertEquals(PlayerStatus.CRASHED, result.players[0].status)
        assertEquals(null, result.players[0].finishRank)
        assertEquals(GamePhase.IN_PROGRESS, result.phase)
    }

    @Test
    fun applyMove_crossingFinishLineBeforeFinalLapIncrementsLapsWithoutFinishing() {
        val track = emptyTrack(finishLine = Wall(Point(5, 0), Point(5, 10)), finishDirection = Point(1, 0))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5))),
            currentPlayerIndex = 0,
            totalLaps = 2,
        )
        val result = GameEngine.applyMove(state, 0, Point(5, 5))
        assertEquals(PlayerStatus.RACING, result.players[0].status)
        assertEquals(1, result.players[0].lapsCompleted)
        assertEquals(null, result.players[0].finishRank)
        assertEquals(GamePhase.IN_PROGRESS, result.phase)
    }

    @Test
    fun applyMove_crossingFinishLineOnTheFinalLapFinishesThePlayer() {
        val track = emptyTrack(finishLine = Wall(Point(5, 0), Point(5, 10)), finishDirection = Point(1, 0))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5), lapsCompleted = 1)),
            currentPlayerIndex = 0,
            totalLaps = 2,
        )
        val result = GameEngine.applyMove(state, 0, Point(5, 5))
        assertEquals(PlayerStatus.FINISHED, result.players[0].status)
        assertEquals(2, result.players[0].lapsCompleted)
        assertEquals(1, result.players[0].finishRank)
        assertEquals(GamePhase.RACE_OVER, result.phase)
    }

    @Test
    fun applyMove_rotatesTurnsAndIncrementsRoundOnWraparound() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(0, 0)),
                PlayerState(id = 1, position = Point(10, 0)),
                PlayerState(id = 2, position = Point(20, 0)),
            ),
            currentPlayerIndex = 0,
            round = 1,
        )
        val afterP0 = GameEngine.applyMove(state, 0, Point(1, 0))
        assertEquals(1, afterP0.currentPlayerIndex)
        assertEquals(1, afterP0.round)

        val afterP1 = GameEngine.applyMove(afterP0, 1, Point(11, 0))
        assertEquals(2, afterP1.currentPlayerIndex)
        assertEquals(1, afterP1.round)

        val afterP2 = GameEngine.applyMove(afterP1, 2, Point(21, 0))
        assertEquals(0, afterP2.currentPlayerIndex)
        assertEquals(2, afterP2.round)
    }

    @Test
    fun raceEndsOnlyOnceEveryPlayerHasCrashedOrFinished() {
        val track = emptyTrack(
            walls = listOf(Wall(Point(1, -2), Point(1, 2))),
            finishLine = Wall(Point(50, 51), Point(51, 51)),
            finishDirection = Point(0, 1),
        )
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(0, 0), lastVelocity = Point(2, 0)),
                PlayerState(id = 1, position = Point(50, 50)),
            ),
            currentPlayerIndex = 0,
        )

        val afterCrash = GameEngine.startTurn(state)
        assertEquals(PlayerStatus.CRASHED, afterCrash.players[0].status)
        assertEquals(1, afterCrash.currentPlayerIndex)
        assertEquals(GamePhase.IN_PROGRESS, afterCrash.phase)

        val afterFinish = GameEngine.applyMove(afterCrash, 1, Point(50, 51))
        assertEquals(PlayerStatus.FINISHED, afterFinish.players[1].status)
        assertEquals(1, afterFinish.players[1].finishRank)
        assertEquals(GamePhase.RACE_OVER, afterFinish.phase)
    }

    @Test
    fun realOvalTrack_lastRemainingRacerFinishingEndsTheRace() {
        val track = Tracks.OVAL
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = track.startPositions[0], status = PlayerStatus.CRASHED),
                PlayerState(id = 1, position = Point(17, 15), lastVelocity = Point(-1, 0)),
            ),
            currentPlayerIndex = 1,
        )
        assertTrue(Point(15, 15) in GameEngine.legalMoves(state, 1))

        val result = GameEngine.applyMove(state, 1, Point(15, 15))
        assertEquals(PlayerStatus.FINISHED, result.players[1].status)
        assertEquals(1, result.players[1].finishRank)
        assertEquals(GamePhase.RACE_OVER, result.phase)
    }

    @Test
    fun legalMoves_withPendingOilSpill_offersTheCoastPointAndTwoSlipPoints() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(5, 5),
                    lastVelocity = Point(2, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                    oilSlips = listOf(Point(0, 1), Point(0, -1)),
                ),
            ),
            currentPlayerIndex = 0,
        )
        // Coast point (7,5), plus the two grid points the car slid to alongside it.
        assertEquals(setOf(Point(7, 5), Point(7, 6), Point(7, 4)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun legalMoves_withPendingOilSpillAndNoSlipDrawn_offersOnlyTheCoastPoint() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(5, 5),
                    lastVelocity = Point(2, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                ),
            ),
            currentPlayerIndex = 0,
        )
        // The conservative reading, and what a planner looking at a spill it hasn't hit yet
        // has to assume - see SimpleAi. Only applyMove draws slips.
        assertEquals(setOf(Point(7, 5)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun legalMoves_slipPointOutsideTheNineCandidatesIsNotOffered() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    // At rest the 9 candidates surround the car itself, so a slip away from
                    // the coast point can land outside them; the car cannot teleport there.
                    position = Point(5, 5),
                    lastVelocity = Point(0, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                    oilSlips = listOf(Point(1, 1)),
                ),
            ),
            currentPlayerIndex = 0,
        )
        assertEquals(setOf(Point(5, 5), Point(6, 6)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun applyMove_endingOnAnOilSpill_drawsTwoSlipDirections() {
        val area = listOf(Point(9, -5), Point(30, -5), Point(30, 5), Point(9, 5))
        val track = emptyTrack().copy(modifiers = listOf(TrackModifier(ModifierType.OIL_SPILL, area)))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(5, 0), lastVelocity = Point(5, 0), path = listOf(Point(0, 0), Point(5, 0)))),
            currentPlayerIndex = 0,
        )
        val slipLists = (0 until 40).map { seed ->
            val player = GameEngine.applyMove(state, 0, Point(10, 0), Random(seed)).players[0]
            assertEquals(setOf(ModifierType.OIL_SPILL), player.pendingModifiers)
            assertEquals(2, player.oilSlips.size, "a spill must queue two slip directions")
            player.oilSlips
        }
        // Every offset is a one-step nudge, never (0,0).
        assertTrue(slipLists.flatten().none { it == Point(0, 0) }, "a spill always slips: $slipLists")
        assertTrue(slipLists.flatten().all { abs(it.x) <= 1 && abs(it.y) <= 1 }, "each slip is one grid point: $slipLists")
        // The two offsets on a single move are distinct.
        assertTrue(slipLists.all { it[0] != it[1] }, "the two slips on one move must be distinct: $slipLists")
        // Across moves, the slip directions vary.
        assertTrue(slipLists.flatten().toSet().size > 1, "the slip direction should not be fixed: ${slipLists.flatten().toSet()}")
    }

    @Test
    fun applyMove_aTurnClearOfEveryZone_dropsTheEffectAndTheSlips() {
        val area = listOf(Point(-5, -5), Point(6, -5), Point(6, 5), Point(-5, 5))
        val track = emptyTrack().copy(modifiers = listOf(TrackModifier(ModifierType.OIL_SPILL, area)))
        val onOil = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(20, 0),
                    lastVelocity = Point(2, 0),
                    path = listOf(Point(18, 0), Point(20, 0)),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                    oilSlips = listOf(Point(0, 1), Point(0, -1)),
                ),
            ),
            currentPlayerIndex = 0,
        )
        // A move that touches nothing: one clear turn is all it takes for the effect to lapse.
        val after = GameEngine.applyMove(onOil, 0, Point(22, 0), Random(1)).players[0]
        assertEquals(emptySet(), after.pendingModifiers)
        assertTrue(after.oilSlips.isEmpty(), "the slips must lapse with the effect that produced them")
    }

    @Test
    fun applyMove_touchingAZoneAgain_requeuesTheEffect() {
        val area = listOf(Point(19, -5), Point(40, -5), Point(40, 5), Point(19, 5))
        val track = emptyTrack().copy(modifiers = listOf(TrackModifier(ModifierType.NAILS, area)))
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(20, 0),
                    lastVelocity = Point(1, 0),
                    path = listOf(Point(19, 0), Point(20, 0)),
                    pendingModifiers = setOf(ModifierType.NAILS),
                ),
            ),
            currentPlayerIndex = 0,
        )
        // Still inside the zone at the end of the move, so the effect is queued afresh rather
        // than expiring - a car cannot sit on a hazard and have it wear off underneath it.
        // (20,1) is the left strafe nails leaves at speed 1, and stays in the zone.
        val after = GameEngine.applyMove(state, 0, Point(20, 1), Random(1)).players[0]
        assertEquals(setOf(ModifierType.NAILS), after.pendingModifiers)
    }

    @Test
    fun legalMoves_withPendingNails_atSpeedAboveOne_offersOnlyTheThreeSlowestCandidates() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(5, 5), lastVelocity = Point(2, 0), pendingModifiers = setOf(ModifierType.NAILS)),
            ),
            currentPlayerIndex = 0,
        )
        // Center is (7,5); the column at x=6 has the smallest resulting velocity magnitude
        // (1, dy) for each of the 3 dy offsets, so those are the only candidates offered.
        assertEquals(setOf(Point(6, 4), Point(6, 5), Point(6, 6)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun legalMoves_withPendingNails_atSpeedOne_offersStopFrontAndPerpendicularStrafes() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(5, 5), lastVelocity = Point(1, 0), pendingModifiers = setOf(ModifierType.NAILS)),
            ),
            currentPlayerIndex = 0,
        )
        // Speed 1 heading (1,0): stop (5,5), front (6,5), left strafe (5,4), right strafe (5,6).
        // No diagonal, no back. Front lets a crawling car roll forward over the nails.
        assertEquals(setOf(Point(5, 5), Point(6, 5), Point(5, 4), Point(5, 6)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun legalMoves_withPendingNails_atSpeedOneDiagonal_offersStopFrontAndAxisDecompositions() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(5, 5), lastVelocity = Point(1, 1), pendingModifiers = setOf(ModifierType.NAILS)),
            ),
            currentPlayerIndex = 0,
        )
        // Speed 1 diagonal heading (1,1): stop (5,5), front (6,6), plus the two axis-aligned
        // decompositions (6,5) and (5,6) - the two nearest cardinal points along the diagonal.
        // The perpendicular strafes (4,6) and (6,4) fall outside the 9-neighborhood (center is
        // (6,6), candidates are x,y in 5..7) so they are not offered, same as for any heading
        // whose strafes land outside the neighborhood. All four moves are slow-downs.
        assertEquals(
            setOf(Point(5, 5), Point(6, 6), Point(6, 5), Point(5, 6)),
            GameEngine.legalMoves(state, 0),
        )
    }

    @Test
    fun legalMoves_withPendingNails_stationary_fallsBackToThreeSlowest() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(5, 5), lastVelocity = Point(0, 0), pendingModifiers = setOf(ModifierType.NAILS)),
            ),
            currentPlayerIndex = 0,
        )
        // No heading: the 3 slowest are the car itself (distance 0) and two of its four
        // orthogonal neighbours (distance 1) - which two depends on the Set's iteration order
        // after sortedBy, so we just assert the car is included and all three are at distance <= 1.
        val moves = GameEngine.legalMoves(state, 0)
        assertEquals(3, moves.size)
        assertTrue(Point(5, 5) in moves, "the stop point must be offered")
        assertTrue(moves.all { (it.x - 5) * (it.x - 5) + (it.y - 5) * (it.y - 5) <= 1 }, "all must be at distance <= 1: $moves")
    }

    @Test
    fun legalMoves_withOverlappingHazards_appliesBothRestrictions() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(5, 5),
                    lastVelocity = Point(1, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL, ModifierType.NAILS),
                    oilSlips = listOf(Point(0, 1), Point(0, -1)),
                ),
            ),
            currentPlayerIndex = 0,
        )
        // Oil offers coast (6,5) + slips (6,6),(6,4). Nails at speed 1 allow stop (5,5),
        // front (6,5), left (5,4), right (5,6). The intersection is the coast point (6,5),
        // which is now in the nail set thanks to the front option, so it is offered directly
        // and the nails-fallback does not kick in.
        assertEquals(setOf(Point(6, 5)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun legalMoves_oilAndNails_coastInNailSet_returnsIntersection() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(5, 5),
                    lastVelocity = Point(1, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL, ModifierType.NAILS),
                    oilSlips = listOf(Point(1, 0), Point(-1, 0)),
                ),
            ),
            currentPlayerIndex = 0,
        )
        // Oil offers coast (6,5) + slips (7,5),(5,5). Nails at speed 1 allow (5,5),(6,5),(5,4),(5,6).
        // Intersection: (6,5) the coast, and (5,5) the slip that lands on the stop point.
        assertEquals(setOf(Point(6, 5), Point(5, 5)), GameEngine.legalMoves(state, 0))
    }

    @Test
    fun legalMoves_oilForcedOntoOccupiedCell_withFreeOption_dropsOccupied() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(5, 5),
                    lastVelocity = Point(2, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                    oilSlips = listOf(Point(0, 1), Point(0, -1)),
                ),
                PlayerState(id = 1, position = Point(7, 6)),
            ),
            currentPlayerIndex = 0,
        )
        // Oil offers (7,5),(7,6),(7,4). (7,6) is occupied by player 1 but (7,5) and (7,4)
        // are free, so the occupied cell is dropped.
        val moves = GameEngine.legalMoves(state, 0)
        assertEquals(setOf(Point(7, 5), Point(7, 4)), moves)
    }

    @Test
    fun legalMoves_oilForcedOntoOccupiedCell_onlyOption_offersIt() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(5, 5),
                    lastVelocity = Point(2, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                    oilSlips = listOf(Point(0, 1), Point(0, -1)),
                ),
                PlayerState(id = 1, position = Point(7, 5)),
                PlayerState(id = 2, position = Point(7, 6)),
                PlayerState(id = 3, position = Point(7, 4)),
            ),
            currentPlayerIndex = 0,
        )
        // All three oil targets are occupied. With no free option, the occupied cells are
        // offered anyway - the player must crash into one of them.
        val moves = GameEngine.legalMoves(state, 0)
        assertEquals(setOf(Point(7, 5), Point(7, 6), Point(7, 4)), moves)
    }

    @Test
    fun applyMove_oilForcedOntoOccupiedCell_crashesBothPlayers() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(5, 5),
                    lastVelocity = Point(2, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                    oilSlips = listOf(Point(0, 1), Point(0, -1)),
                ),
                PlayerState(id = 1, position = Point(7, 5)),
                PlayerState(id = 2, position = Point(7, 6)),
                PlayerState(id = 3, position = Point(7, 4)),
            ),
            currentPlayerIndex = 0,
        )
        val after = GameEngine.applyMove(state, 0, Point(7, 5), Random(0))
        val mover = after.players.first { it.id == 0 }
        val occupant = after.players.first { it.id == 1 }
        assertEquals(PlayerStatus.CRASHED, mover.status, "the mover crashes on collision")
        assertEquals(PlayerStatus.CRASHED, occupant.status, "the car already there crashes too")
    }

    @Test
    fun legalMoves_hazardNeverLeavesAPlayerWithNothingToDo() {
        val track = emptyTrack(walls = listOf(Wall(Point(6, -5), Point(6, 5))))
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(5, 0), lastVelocity = Point(1, 0), pendingModifiers = setOf(ModifierType.OIL_SPILL)),
            ),
            currentPlayerIndex = 0,
        )
        // The forced coast point (6,0) is behind a wall. Rather than crash the car - which
        // startTurn would do for an empty move set - the hazard is dropped so the player
        // still gets a turn. Hitting a wall has to be the driver's decision, not the oil's.
        val moves = GameEngine.legalMoves(state, 0)
        assertTrue(moves.isNotEmpty(), "an unreachable hazard outcome must not crash the car by itself")
        assertTrue(Point(6, 0) !in moves, "the wall still blocks the coast point")
    }

    @Test
    fun applyMove_drivingThroughAModifierZone_queuesItForTheNextMove() {
        val modifierArea = listOf(Point(3, 3), Point(7, 3), Point(7, 7), Point(3, 7), Point(3, 3))
        val track = emptyTrack().copy(modifiers = listOf(TrackModifier(ModifierType.OIL_SPILL, modifierArea)))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(4, 5), lastVelocity = Point(1, 0))),
            currentPlayerIndex = 0,
        )
        val result = GameEngine.applyMove(state, 0, Point(5, 5))
        assertEquals(setOf(ModifierType.OIL_SPILL), result.players[0].pendingModifiers)
    }

    /**
     * The bug behind "modifiers are not correctly applied": at racing speed a car steps
     * clean over a small zone without ever landing on a grid point inside it, and the old
     * landing-point-only check never fired.
     */
    @Test
    fun applyMove_crossingAModifierZoneWithoutLandingInIt_stillTriggersIt() {
        val modifierArea = listOf(Point(4, 3), Point(6, 3), Point(6, 7), Point(4, 7), Point(4, 3))
        val track = emptyTrack().copy(modifiers = listOf(TrackModifier(ModifierType.NAILS, modifierArea)))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(2, 5), lastVelocity = Point(5, 0))),
            currentPlayerIndex = 0,
        )
        // (2,5) -> (8,5) passes straight through the zone but starts and ends outside it.
        val result = GameEngine.applyMove(state, 0, Point(8, 5))
        assertEquals(setOf(ModifierType.NAILS), result.players[0].pendingModifiers)
    }

    @Test
    fun applyMove_movingClearOfEveryModifierZone_clearsThePendingEffects() {
        val modifierArea = listOf(Point(3, 3), Point(7, 3), Point(7, 7), Point(3, 7), Point(3, 3))
        val track = emptyTrack().copy(modifiers = listOf(TrackModifier(ModifierType.OIL_SPILL, modifierArea)))
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(20, 20), lastVelocity = Point(1, 0), pendingModifiers = setOf(ModifierType.OIL_SPILL)),
            ),
            currentPlayerIndex = 0,
        )
        // Under OIL_SPILL the only legal move is the coast point (21,20), well outside the modifier zone.
        val result = GameEngine.applyMove(state, 0, Point(21, 20))
        assertEquals(emptySet(), result.players[0].pendingModifiers)
    }

    @Test
    fun newGame_startsAllPlayersRacingAtTheirStartPositions() {
        val state = GameEngine.newGame(Tracks.OVAL, 4)
        assertEquals(4, state.players.size)
        assertEquals(0, state.currentPlayerIndex)
        assertEquals(GamePhase.IN_PROGRESS, state.phase)
        state.players.forEachIndexed { i, player ->
            assertEquals(Tracks.OVAL.startPositions[i], player.position)
            assertEquals(PlayerStatus.RACING, player.status)
        }
        assertTrue(GameEngine.legalMoves(state, 0).isNotEmpty())
    }

    @Test
    fun legalMoves_forAPlayerThatIsNotInTheStateIsEmptyRatherThanAThrow() {
        val state = GameEngine.newGame(Tracks.OVAL, 2)
        assertEquals(emptySet(), GameEngine.legalMoves(state, 99))
    }

    @Test
    fun forfeit_crashesThePlayerAndPassesTheTurnOn() {
        val state = GameEngine.newGame(Tracks.OVAL, 3)
        val after = GameEngine.forfeit(state, 0)

        assertEquals(PlayerStatus.CRASHED, after.players.first { it.id == 0 }.status)
        assertEquals(1, after.currentPlayer.id, "the turn should have moved on")
        assertEquals(GamePhase.IN_PROGRESS, after.phase)
    }

    @Test
    fun forfeit_ofTheLastRacingPlayerEndsTheRace() {
        val state = GameEngine.newGame(Tracks.OVAL, 1)
        assertEquals(GamePhase.RACE_OVER, GameEngine.forfeit(state, 0).phase)
    }

    @Test
    fun forfeit_ofAPlayerWhoIsNotRacingChangesNothing() {
        val state = GameEngine.newGame(Tracks.OVAL, 2)
        assertEquals(state, GameEngine.forfeit(state, 99))
    }

    @Test
    fun isConsistent_rejectsStatesThatWouldCrashOnTheFirstRead() {
        val good = GameEngine.newGame(Tracks.OVAL, 2)
        assertTrue(good.isConsistent)
        // Exactly what a snapshot written by a build with a different player list looks like.
        assertFalse(good.copy(currentPlayerIndex = 5).isConsistent)
        assertFalse(good.copy(players = emptyList()).isConsistent)
        assertFalse(good.copy(players = good.players.map { it.copy(id = it.id + 1) }).isConsistent)
        assertFalse(good.copy(track = good.track.copy(startPositions = emptyList())).isConsistent)
        assertFalse(good.copy(maxRounds = 0).isConsistent)
    }

    @Test
    fun applyMove_whenRoundCapReached_endsRaceWithRacingPlayersUnranked() {
        // A single racing player at the start of the last allowed round: one more move wraps
        // the round counter past the cap, so the race ends and the still-racing player keeps
        // a null finishRank (which Scoring maps to 0 points).
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(5, 5), lastVelocity = Point(1, 0))),
            currentPlayerIndex = 0,
            round = 3,
            maxRounds = 3,
        )
        val after = GameEngine.applyMove(state, 0, Point(6, 5))
        assertEquals(GamePhase.RACE_OVER, after.phase)
        assertEquals(PlayerStatus.RACING, after.players[0].status)
        assertNull(after.players[0].finishRank)
        assertEquals(0, Scoring.pointsForRank(after.players[0].finishRank))
    }

    @Test
    fun applyMove_belowRoundCap_keepsRaceInProgress() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(5, 5), lastVelocity = Point(1, 0))),
            currentPlayerIndex = 0,
            round = 2,
            maxRounds = 3,
        )
        val after = GameEngine.applyMove(state, 0, Point(6, 5))
        assertEquals(GamePhase.IN_PROGRESS, after.phase)
        assertEquals(3, after.round)
    }

    @Test
    fun newGame_defaultsToEngineMaxRoundsCap() {
        val state = GameEngine.newGame(Tracks.OVAL, 2)
        assertEquals(GameEngine.MAX_ROUNDS, state.maxRounds)
    }

    @Test
    fun legalMoves_crashedPlayerDoesNotBlockRacingPlayer() {
        // Player 0 is crashed at (3,0). Player 1 is at (1,0) with velocity (1,0), coast at (2,0).
        // Without the crashed player, all 9 candidates around (2,0) are legal. The crashed
        // player at (3,0) would have blocked (3,0) and paths through it, but since crashed
        // players don't block, (3,0) should be legal.
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(3, 0), status = PlayerStatus.CRASHED),
                PlayerState(id = 1, position = Point(1, 0), lastVelocity = Point(1, 0)),
            ),
            currentPlayerIndex = 1,
        )
        val moves = GameEngine.legalMoves(state, 1)
        assertTrue(Point(3, 0) in moves, "crashed player should not block (3,0)")
    }

    @Test
    fun startTurn_wallCrashSetsWreckPositionAtProjectionWallIntersection() {
        // Player at (0,0) with velocity (2,0), coast at (2,0). A wall at x=1 blocks
        // the projection. All 9 candidates around (2,0) are wall-blocked, so the player
        // crashes. The wreck should be at the intersection of the projection line and
        // the wall — on the fine grid (subdivision=1 here), that's (1,0).
        val track = emptyTrack(
            walls = listOf(
                Wall(Point(1, -2), Point(1, 2)),
            ),
        )
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(0, 0), lastVelocity = Point(2, 0))),
            currentPlayerIndex = 0,
        )
        val result = GameEngine.startTurn(state)
        assertEquals(PlayerStatus.CRASHED, result.players[0].status)
        val wreck = result.players[0].wreckPosition
        assertNotNull(wreck, "wall crash should produce a wreck position")
        assertEquals(Point(1, 0), wreck)
    }

    @Test
    fun applyMove_oilCollisionSetsWreckPositionAtTarget() {
        // Player 0 is oil-forced onto player 1's position. Both crash with wrecks at the target.
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(5, 5),
                    lastVelocity = Point(2, 0),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                    oilSlips = listOf(Point(0, 1), Point(0, -1)),
                ),
                PlayerState(id = 1, position = Point(7, 5)),
                PlayerState(id = 2, position = Point(7, 6)),
                PlayerState(id = 3, position = Point(7, 4)),
            ),
            currentPlayerIndex = 0,
        )
        val after = GameEngine.applyMove(state, 0, Point(7, 5), Random(0))
        val mover = after.players.first { it.id == 0 }
        val occupant = after.players.first { it.id == 1 }
        assertEquals(PlayerStatus.CRASHED, mover.status)
        assertEquals(PlayerStatus.CRASHED, occupant.status)
        assertNotNull(mover.wreckPosition, "oil-collision mover should have a wreck")
        assertNotNull(occupant.wreckPosition, "oil-collision occupant should have a wreck")
        assertEquals(Point(7, 5), mover.wreckPosition)
        assertEquals(Point(7, 5), occupant.wreckPosition)
    }

    @Test
    fun applyMove_backwardFinishLineCrashHasNoWreck() {
        val track = emptyTrack(finishLine = Wall(Point(5, 0), Point(5, 10)), finishDirection = Point(1, 0))
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(6, 5), lastVelocity = Point(-1, 0)),
                PlayerState(id = 1, position = Point(50, 50)),
            ),
            currentPlayerIndex = 0,
        )
        val result = GameEngine.applyMove(state, 0, Point(5, 5))
        assertEquals(PlayerStatus.CRASHED, result.players[0].status)
        assertNull(result.players[0].wreckPosition, "backward finish-line crash should not produce a wreck")
    }

    @Test
    fun forfeit_producesNoWreck() {
        val state = GameEngine.newGame(Tracks.OVAL, 3)
        val after = GameEngine.forfeit(state, 0)
        val forfeited = after.players.first { it.id == 0 }
        assertEquals(PlayerStatus.CRASHED, forfeited.status)
        assertNull(forfeited.wreckPosition, "forfeit should not produce a wreck")
    }
}
