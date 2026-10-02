package info.piepgras.gridracer.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val MAX_TURNS = 300

/**
 * Drives realistic, full-length races (not a single hand-crafted move) through the
 * real engine, the real [SimpleAi], and every real track, to catch bugs that only
 * show up after many turns - as opposed to [GameEngineTest], which checks individual
 * rule mechanics in isolation.
 */
class FullRaceSimulationTest {

    @Test
    fun soleAiSurvivorAlwaysEndsTheRaceWithinABoundedNumberOfTurns() {
        var finishedCount = 0
        var total = 0

        for (track in Tracks.all) {
            for (seed in 0 until 8) {
                total++
                val outcome = simulate(track, seed)
                if (outcome == PlayerStatus.FINISHED) finishedCount++
            }
        }

        // The AI isn't required to finish every time (crashing is a legitimate outcome
        // too), but it should be *capable* of completing a lap, not just crashing always.
        assertTrue(finishedCount > total / 2, "expected most simulated races to finish, got $finishedCount/$total")
    }

    @Test
    fun soleAiSurvivorCanCompleteMultiLapRaces() {
        var finishedCount = 0
        var total = 0

        for (track in Tracks.all) {
            for (seed in 0 until 4) {
                total++
                val outcome = simulate(track, seed, totalLaps = 3)
                if (outcome == PlayerStatus.FINISHED) finishedCount++
            }
        }

        assertTrue(finishedCount > total / 2, "expected most 3-lap simulated races to finish, got $finishedCount/$total")
    }

    @Test
    fun soleAiSurvivorDrivesTheRightWayOnReversedTracks() {
        // Reversing a track negates finishDirection, which flips the rotational direction
        // the AI must travel. Before the direction-aware fix the AI kept going the old way
        // and looped forever against traffic, so this is the regression test for that.
        var finishedCount = 0
        var total = 0

        for (track in Tracks.all) {
            val reversed = track.copy(finishDirection = track.finishDirection * -1)
            for (seed in 0 until 8) {
                total++
                val outcome = simulate(reversed, seed)
                if (outcome == PlayerStatus.FINISHED) finishedCount++
            }
        }

        assertTrue(finishedCount > total / 2, "expected most reversed-track races to finish, got $finishedCount/$total")
    }

    @Test
    fun soleAiNeverCrashesOnCleanTrackWithNoOtherCars() {
        // The safety-set guarantee: with no modifiers in play and no other cars on the track,
        // the AI can never crash into a wall - every move it picks lands in a state from
        // which a wall-free line exists forever. So every one of these solo races must end
        // with the player FINISHED, never CRASHED. (A single car, no wreck to dodge, no
        // modifiers - the exact condition the guarantee covers.)
        for (track in Tracks.all) {
            // Strip modifiers: the guarantee is about walls only, and built-in tracks now
            // ship with oil/nails zones that can legitimately cause the AI to crash.
            val cleanTrack = track.copy(modifiers = emptyList())
            for (seed in 0 until 8) {
                val outcome = simulateSolo(cleanTrack, seed)
                assertEquals(
                    PlayerStatus.FINISHED,
                    outcome,
                    "${track.name} seed=$seed: AI crashed on a clean, car-free track (expected to finish). " +
                        "The safety-set guarantee says it shouldn't be able to.",
                )
            }
        }
    }

    /** Runs one race to conclusion (or fails if it doesn't conclude within [MAX_TURNS]) and returns player 0's final status. */
    private fun simulate(track: Track, seed: Int, totalLaps: Int = 1): PlayerStatus {
        var state = GameState(
            track = track,
            players = listOf(
                PlayerState(id = 0, position = track.startPositions[0]),
                PlayerState(id = 1, position = track.startPositions[1], status = PlayerStatus.CRASHED),
            ),
            currentPlayerIndex = 0,
            totalLaps = totalLaps,
            // These simulations test the AI's raw finishing ability, not the round cap, so
            // the cap is raised out of the way; [MAX_TURNS] still bounds the loop.
            maxRounds = MAX_TURNS,
        )
        val random = Random(seed)
        var turns = 0

        while (state.phase != GamePhase.RACE_OVER) {
            turns++
            if (turns > MAX_TURNS) {
                fail("${track.name} seed=$seed: race did not conclude within $MAX_TURNS turns; last state: $state")
            }
            val player = state.currentPlayer
            val move = SimpleAi.chooseMove(state, player.id, random)
                ?: fail("${track.name} seed=$seed: player ${player.id} has legal moves per the engine, but SimpleAi returned null")
            state = GameEngine.applyMove(state, player.id, move)
        }

        return state.players[0].status
    }

    /**
     * Like [simulate] but with a single player and no crashed wreck on the track - the pure
     * "AI alone on clean track" case the no-crash guarantee is about. The safety set is
     * computed against walls only, so with no other cars it matches reality exactly and the
     * AI is guaranteed to finish rather than crash.
     */
    private fun simulateSolo(track: Track, seed: Int, totalLaps: Int = 1): PlayerStatus {
        var state = GameState(
            track = track,
            players = listOf(PlayerState(id = 0, position = track.startPositions[0])),
            currentPlayerIndex = 0,
            totalLaps = totalLaps,
            maxRounds = MAX_TURNS,
        )
        val random = Random(seed)
        var turns = 0

        while (state.phase != GamePhase.RACE_OVER) {
            turns++
            if (turns > MAX_TURNS) {
                fail("${track.name} seed=$seed: solo race did not conclude within $MAX_TURNS turns; last state: $state")
            }
            val player = state.currentPlayer
            val move = SimpleAi.chooseMove(state, player.id, random)
                ?: fail("${track.name} seed=$seed: player ${player.id} has legal moves per the engine, but SimpleAi returned null")
            state = GameEngine.applyMove(state, player.id, move)
        }

        return state.players[0].status
    }
}
