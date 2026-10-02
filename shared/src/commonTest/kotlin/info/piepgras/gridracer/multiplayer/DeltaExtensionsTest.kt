package info.piepgras.gridracer.multiplayer

import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.Placement
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Tracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeltaExtensionsTest {

    @Test
    fun roundTripRaceDelta() {
        val track = Tracks.all.first()
        val assignments = track.startPositions.take(2)
        val state = GameEngine.newGame(track, assignments, totalLaps = 1)
        val playerId = state.currentPlayer.id
        val move = GameEngine.legalMoves(state, playerId).first()
        val next = GameEngine.applyMove(state, playerId, move)

        val delta = next.asDelta(state)
        val reconstructed = state.applyDelta(delta)

        assertEquals(next, reconstructed)
    }

    @Test
    fun roundTripPlacementDelta() {
        val track = Tracks.all.first()
        val configs = listOf(PlayerConfig("A", isAi = false), PlayerConfig("B", isAi = false))
        val placement = Placement.start(track, configs, randomized = false)
        val slot = placement.availableSlots.first()
        val next = Placement.choose(placement, slot)

        val delta = next.asDelta(placement)
        val reconstructed = placement.applyDelta(delta)

        assertEquals(next, reconstructed)
    }

    // -----------------------------------------------------------------------------------
    // Gap detection
    //
    // applyDelta used to overwrite `revision` with the delta's and return a state
    // unconditionally, so a client that dropped one frame applied the next to a stale board
    // and came away with a *wrong board carrying the server's revision number*. The server
    // compares revisions to spot stale moves, so that client's next move was then accepted as
    // current, and nothing on either side could see the divergence.
    // -----------------------------------------------------------------------------------

    /** Three consecutive states, as the server would produce them. */
    private fun raceStates(): Triple<info.piepgras.gridracer.engine.GameState, info.piepgras.gridracer.engine.GameState, info.piepgras.gridracer.engine.GameState> {
        val track = Tracks.all.first()
        val assignments = track.startPositions.take(2)
        val first = GameEngine.newGame(track, assignments, totalLaps = 1).copy(revision = 1)
        val second = GameEngine
            .applyMove(first, first.currentPlayer.id, GameEngine.legalMoves(first, first.currentPlayer.id).first())
            .copy(revision = 2)
        val third = GameEngine
            .applyMove(second, second.currentPlayer.id, GameEngine.legalMoves(second, second.currentPlayer.id).first())
            .copy(revision = 3)
        return Triple(first, second, third)
    }

    @Test
    fun aDeltaForADifferentRevisionIsRefusedRatherThanApplied() {
        val (first, second, third) = raceStates()

        // The client is still at `first` because it dropped the 1 -> 2 delta. The 2 -> 3 delta
        // must not be applied on top of it.
        val skipped = third.asDelta(second)

        assertNull(first.applyDelta(skipped))
        // ...and the one it should have got still applies.
        assertEquals(second, first.applyDelta(second.asDelta(first)))
    }

    @Test
    fun aPlacementDeltaForADifferentRevisionIsRefused() {
        val track = Tracks.all.first()
        val configs = listOf(PlayerConfig("A", isAi = false), PlayerConfig("B", isAi = false))
        val first = Placement.start(track, configs, randomized = false).copy(revision = 1)
        val second = Placement.choose(first, first.availableSlots.first()).copy(revision = 2)
        val third = Placement.choose(second, second.availableSlots.first()).copy(revision = 3)

        assertNull(first.applyDelta(third.asDelta(second)))
        assertEquals(second, first.applyDelta(second.asDelta(first)))
    }

    /**
     * `baseRevision == 0` means "the sender did not say" - a server older than protocol 5 -
     * and must be treated as unknown, not as a gap. Refusing those would break every client
     * talking to a server that has not been updated yet.
     */
    @Test
    fun aDeltaFromAServerThatStatesNoBaseRevisionIsStillApplied() {
        val (first, second, _) = raceStates()
        val legacyDelta = second.asDelta(first).copy(baseRevision = 0)

        assertEquals(second, first.applyDelta(legacyDelta))
    }

    @Test
    fun aPlacementDeltaFromAnOlderServerIsStillApplied() {
        val track = Tracks.all.first()
        val configs = listOf(PlayerConfig("A", isAi = false), PlayerConfig("B", isAi = false))
        val first = Placement.start(track, configs, randomized = false).copy(revision = 1)
        val second = Placement.choose(first, first.availableSlots.first()).copy(revision = 2)

        assertEquals(second, first.applyDelta(second.asDelta(first).copy(baseRevision = 0)))
    }

    @Test
    fun asDeltaRecordsTheRevisionItWasComputedAgainst() {
        val (first, second, _) = raceStates()

        assertEquals(first.revision, second.asDelta(first).baseRevision)
        assertEquals(second.revision, second.asDelta(first).revision)
    }
}
