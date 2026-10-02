package info.piepgras.gridracer.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun emptyTrack(walls: List<Wall> = emptyList()) = Track(
    name = "Test",
    width = 100,
    height = 100,
    walls = walls,
    finishLine = Wall(Point(1000, 1000), Point(1001, 1000)),
    finishDirection = Point(0, 1),
    startPositions = listOf(Point(0, 0)),
)

class SimpleAiTest {

    @Test
    fun chooseMove_worksWhenTheAiIsNotTheFirstPlayerInTheList() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = Point(0, 0)),
                PlayerState(id = 1, position = Point(10, 0)),
                PlayerState(id = 2, position = Point(20, 0)),
            ),
            currentPlayerIndex = 2,
        )
        val move = SimpleAi.chooseMove(state, 2, Random(42))
        assertTrue(move in GameEngine.legalMoves(state, 2))
    }

    @Test
    fun chooseMove_returnsALegalMoveInTheOpen() {
        val state = GameState(
            track = emptyTrack(),
            players = listOf(PlayerState(id = 0, position = Point(0, 0))),
            currentPlayerIndex = 0,
        )
        val legalMoves = GameEngine.legalMoves(state, 0)
        val move = SimpleAi.chooseMove(state, 0, Random(42))
        assertTrue(move in legalMoves)
    }

    @Test
    fun chooseMove_returnsNullWhenThePlayerIsAlreadyBoxedIn() {
        val track = emptyTrack(walls = listOf(Wall(Point(1, -2), Point(1, 2))))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(0, 0), lastVelocity = Point(2, 0))),
            currentPlayerIndex = 0,
        )
        assertEquals(emptySet(), GameEngine.legalMoves(state, 0))
        assertNull(SimpleAi.chooseMove(state, 0, Random(1)))
    }

    @Test
    fun chooseMove_prefersCandidatesThatStillHaveAFollowUpMove() {
        // Continuing straight past x=5 runs into a dead end; every other direction stays open.
        val track = emptyTrack(walls = listOf(Wall(Point(5, -3), Point(5, 3))))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(0, 0), lastVelocity = Point(2, 0))),
            currentPlayerIndex = 0,
        )
        val player = state.players[0]
        val candidates = GameEngine.legalMoves(state, 0)
        val safe = candidates.filter { candidate ->
            val hypothetical = player.copy(position = candidate, lastVelocity = candidate - player.position)
            val hypotheticalState = state.copy(players = listOf(hypothetical))
            GameEngine.legalMoves(hypotheticalState, 0).isNotEmpty()
        }
        // Sanity-check the scenario itself actually has both safe and dead-end candidates.
        assertTrue(safe.isNotEmpty())
        assertTrue(safe.size < candidates.size)

        repeat(20) { seed ->
            val move = SimpleAi.chooseMove(state, 0, Random(seed))
            assertTrue(move in safe, "seed=$seed picked $move, not in safe set $safe")
        }
    }

    @Test
    fun chooseMove_underOilSpill_picksFromWhatTheSpillLeaves() {
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(0, 0),
                    lastVelocity = Point(2, 1),
                    pendingModifiers = setOf(ModifierType.OIL_SPILL),
                    oilSlips = listOf(Point(-1, 0)),
                ),
            ),
            currentPlayerIndex = 0,
        )
        val allowed = setOf(Point(2, 1), Point(1, 1))
        assertEquals(allowed, GameEngine.legalMoves(state, 0))
        repeat(20) { seed ->
            assertTrue(SimpleAi.chooseMove(state, 0, Random(seed)) in allowed)
        }
    }

    @Test
    fun chooseMove_neverCoastsToAStopWhileAnythingElseIsLegal() {
        // At rest in the open: standing still is legal and perfectly safe, and is exactly what
        // a purely safety-driven AI would settle for.
        val state = GameState(
            track = emptyTrack(),
            players = listOf(PlayerState(id = 0, position = Point(20, 20))),
            currentPlayerIndex = 0,
        )
        assertTrue(Point(20, 20) in GameEngine.legalMoves(state, 0), "the scenario needs stopping to be legal")
        repeat(30) { seed ->
            assertTrue(
                SimpleAi.chooseMove(state, 0, Random(seed)) != Point(20, 20),
                "seed=$seed chose to stand still with somewhere to go",
            )
        }
    }

    @Test
    fun chooseMove_stopsOnlyWhenNothingElseIsLegal() {
        // Boxed in on all sides at rest: the car's own square is the one move left, and
        // refusing to stop must not turn into refusing to move.
        val walls = listOf(
            Wall(Point(19, 19), Point(21, 19)),
            Wall(Point(21, 19), Point(21, 21)),
            Wall(Point(21, 21), Point(19, 21)),
            Wall(Point(19, 21), Point(19, 19)),
        )
        val state = GameState(
            track = emptyTrack(walls = walls),
            players = listOf(PlayerState(id = 0, position = Point(20, 20))),
            currentPlayerIndex = 0,
        )
        assertEquals(setOf(Point(20, 20)), GameEngine.legalMoves(state, 0))
        assertEquals(Point(20, 20), SimpleAi.chooseMove(state, 0, Random(3)))
    }

    @Test
    fun chooseMove_refusesToEnterASpillItCannotSlideOutOf() {
        // A spill immediately in front of a wall. Driving into it at speed hands the next move
        // to the oil, which carries the car straight into the wall - so the AI has to read the
        // spill's mechanics, not merely notice that a zone is there.
        val zone = TrackModifier(
            ModifierType.OIL_SPILL,
            listOf(Point(21, 15), Point(24, 15), Point(24, 25), Point(21, 25)),
        )
        val track = emptyTrack(walls = listOf(Wall(Point(26, 10), Point(26, 30))))
            .copy(subdivision = 1, modifiers = listOf(zone))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(20, 20), lastVelocity = Point(2, 0))),
            currentPlayerIndex = 0,
        )
        // Coasting on to (22,20) puts the car in the spill at speed 2, and the coast point it
        // would then be given, (24,20), leaves only (26,20) and beyond - all behind the wall.
        val doomed = Point(22, 20)
        assertTrue(doomed in GameEngine.legalMoves(state, 0), "the scenario needs the bad move to be legal")
        repeat(20) { seed ->
            assertTrue(
                SimpleAi.chooseMove(state, 0, Random(seed)) != doomed,
                "seed=$seed drove into a spill that slides it into a wall",
            )
        }
    }

    @Test
    fun chooseMove_steersRoundAHazardWhenThereIsRoomToPassIt() {
        // A spill across the far side of the corridor, leaving the rows at y = 0 and y = 1 clear.
        val zone = TrackModifier(
            ModifierType.OIL_SPILL,
            listOf(Point(0, 2), Point(40, 2), Point(40, 40), Point(0, 40)),
        )
        val track = emptyTrack().copy(subdivision = 1, modifiers = listOf(zone))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(20, 1), lastVelocity = Point(1, 0))),
            currentPlayerIndex = 0,
        )
        val clean = GameEngine.legalMoves(state, 0)
            .filter { GameEngine.modifiersOn(track, Point(20, 1), it).isEmpty() }
        assertTrue(clean.isNotEmpty(), "the scenario needs a way round the zone")

        repeat(20) { seed ->
            val move = SimpleAi.chooseMove(state, 0, Random(seed))
            assertTrue(move in clean, "seed=$seed drove into the spill ($move) with $clean available")
        }
    }

    @Test
    fun chooseMove_drivesThroughAHazardThatBlocksTheWholeCorridor() {
        // Same idea, but the zone covers every reachable point: avoiding it is not an option,
        // and preferring to must not leave the AI with nothing to pick.
        val zone = TrackModifier(
            ModifierType.NAILS,
            listOf(Point(-40, -40), Point(40, -40), Point(40, 40), Point(-40, 40)),
        )
        val track = emptyTrack().copy(subdivision = 1, modifiers = listOf(zone))
        val state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = Point(0, 0), lastVelocity = Point(1, 0))),
            currentPlayerIndex = 0,
        )
        val move = SimpleAi.chooseMove(state, 0, Random(3))
        assertTrue(move in GameEngine.legalMoves(state, 0), "expected a legal move, got $move")
    }

    @Test
    fun chooseMove_doesNotOscillateBetweenTwoPositions() {
        // A track where angular progress around the center is ~equal for all moves (a radial
        // corridor along the x-axis of a wide-open track). Without the revisit penalty the AI
        // can bounce A->B->A->B forever; with it, the position it just came from is penalized
        // and a different move is chosen.
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(50, 50),
                    lastVelocity = Point(1, 0),
                    // A path that just bounced: ... -> (49,50) -> (50,50), so (49,50) is 1 move ago.
                    path = listOf(Point(48, 50), Point(49, 50), Point(50, 50)),
                ),
            ),
            currentPlayerIndex = 0,
        )
        // The AI must not pick (49,50) - the position it just came from - even though its
        // angular progress is equal to every other candidate on this radial section.
        repeat(20) { seed ->
            val move = SimpleAi.chooseMove(state, 0, Random(seed))
            assertTrue(move != Point(49, 50), "seed=$seed moved back to (49,50), the position it just left")
        }
    }

    @Test
    fun chooseMove_breaksAThreePositionLoop() {
        // A path that went A -> B -> C -> B (oscillating between B and C). The AI must not
        // go back to C, breaking the B-C-B-C cycle.
        val track = emptyTrack()
        val state = GameState(
            track = track,
            players = listOf(
                PlayerState(
                    id = 0,
                    position = Point(50, 50),
                    lastVelocity = Point(0, 1),
                    path = listOf(Point(50, 48), Point(50, 49), Point(50, 50), Point(50, 49), Point(50, 50)),
                ),
            ),
            currentPlayerIndex = 0,
        )
        // (50,49) was visited 1 move ago and 3 moves ago - heavily penalized.
        repeat(20) { seed ->
            val move = SimpleAi.chooseMove(state, 0, Random(seed))
            assertTrue(move != Point(50, 49), "seed=$seed moved back to (50,49), continuing the loop")
        }
    }
}
