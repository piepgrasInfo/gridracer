package info.piepgras.gridracer.replay

import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GameState
import kotlin.random.Random

/**
 * Folds a replay's moves back over its initial state and returns where the race ended up.
 *
 * This is the definition of "the log can recreate the race": if this does not reproduce the
 * standings the race actually finished with, the log is wrong. The app's [ReplaySession] walks
 * the same fold one move at a time to animate it; the server's test uses this to assert the
 * file it wrote is faithful.
 *
 * Deterministic because every [MoveRecord] carries the seed the engine was given, so the
 * oil-slip draw comes out the same way it did live.
 */
fun ReplayData.replayFinalState(): GameState =
    moves.fold(initialState) { state, record -> state.apply(record) }

/** Applies one recorded transition. The single definition of what each [MoveKind] means. */
fun GameState.apply(record: MoveRecord): GameState = when (record.kind) {
    MoveKind.MOVE -> GameEngine.applyMove(this, record.playerId, record.target, Random(record.randomSeed))
    MoveKind.FORFEIT -> GameEngine.forfeit(this, record.playerId)
    MoveKind.RETIRED -> GameEngine.retire(this, record.playerId)
}

/**
 * Whether this replay is well-formed enough to play back.
 *
 * Checked before a replay is imported or listed: a file can arrive from another device, from a
 * server's race log, or from a build with a different [GameState] shape, and the race screen
 * reads `currentPlayer` - which indexes straight into `players` - on its first frame.
 */
val ReplayData.isPlayable: Boolean
    get() = initialState.isConsistent &&
        configs.size == initialState.players.size &&
        moves.all { it.playerId in initialState.players.indices }
