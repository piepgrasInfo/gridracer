package info.piepgras.gridracer.replay

import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Point
import kotlinx.serialization.Serializable

/*
 * The replay format, shared between the app and the server.
 *
 * It lived in the `app` module, which is why the server could not produce a replay anyone
 * could watch. It is here now so a race logged on the server is byte-for-byte the same thing
 * the app's replay browser already reads - one format, one player, no converter to drift.
 *
 * The field shapes are unchanged from the app-module version, so replay files written by
 * earlier builds still decode.
 */

/** What a [MoveRecord] records. */
@Serializable
enum class MoveKind {
    /** A chosen target point. */
    MOVE,

    /**
     * The player could not or did not play their turn: an AI drew a blank, or a human's turn
     * timed out. Replays as `GameEngine.forfeit`, which crashes them and passes the turn on.
     */
    FORFEIT,

    /**
     * The player walked out of a race that was not on their turn - only reachable in a
     * multiplayer race. Replays as `GameEngine.retire`. Distinct from [FORFEIT] because it
     * does not advance the turn: it is somebody else's turn already.
     */
    RETIRED,
}

/**
 * One recorded move: which player moved, where they moved to, and the random seed used for
 * oil-spill slip generation so the replay is bit-for-bit deterministic. [MoveKind.FORFEIT]
 * entries have an unused target/seed — the player was crashed by the engine.
 */
@Serializable
data class MoveRecord(
    /** The player who moved; their index in [GameState.players]. */
    val playerId: Int,
    /** The coarse-grid point moved to. Unused for [MoveKind.FORFEIT] and [MoveKind.RETIRED]. */
    val target: Point,
    /** Seeds the `Random` handed to `GameEngine.applyMove`, so oil-spill slips replay exactly. */
    val randomSeed: Long,
    /** Whether this is a real move or a forfeit or retirement the engine applied. */
    val kind: MoveKind = MoveKind.MOVE,
    /**
     * Wall-clock time the move was made, or 0 when not recorded. Server logs fill this in so a
     * race can be read back with its real pacing (and so a suspiciously fast sequence of moves
     * is visible); on-device replays leave it at 0 and lose nothing - playback is evenly paced.
     */
    val timestamp: Long = 0L,
)

/**
 * Where a replay came from, when it came from a server. Null for a race played on the device.
 *
 * Recorded so a logged race can be traced back to the room and build that produced it - which
 * is the entire point of keeping the log. Deliberately holds no secret: [accountPublicIds] are
 * the opaque leaderboard handles, never tokens, and never the account row ids.
 */
@Serializable
data class ServerRaceInfo(
    /** The room code the race was played in. */
    val roomCode: String,
    /** Stable id of the server deployment; see `GRIDRACER_SERVER_ID` in the server README. */
    val serverId: String = "",
    /** Human-readable server name at the time of the race. */
    val serverName: String = "",
    /**
     * Wire-protocol version the server spoke, so a log can be matched to the build that wrote it.
     */
    val protocolVersion: Int = 0,
    /** True when the room was created by the matchmaker, i.e. the race counted for rating. */
    val isMatchmade: Boolean = false,
    /** True when the race was one heat of a multiplayer tournament. */
    val isTournament: Boolean = false,
    /** When the race started and finished, in epoch millis. */
    val startedAt: Long = 0L,
    /** When the race finished, in epoch millis; see [startedAt]. */
    val finishedAt: Long = 0L,
    /**
     * The opaque public account id per seat, or null for a guest or an AI. Indexed by seat, so
     * it lines up with [ReplayData.configs].
     */
    val accountPublicIds: List<String?> = emptyList(),
)

/**
 * Everything needed to replay a race from start to finish: the initial state (track, grid,
 * player positions), the player configs for labels, and the ordered list of moves with
 * their random seeds.
 *
 * Replaying is folding [moves] over [initialState] - see [replayFinalState], which is also
 * what the server's own test uses to prove a log really does reconstruct the race.
 */
@Serializable
data class ReplayData(
    /** Display name of the track, for listing the replay without decoding [initialState]. */
    val trackName: String,
    /** The seats, in player-id order, for names and colours during playback. */
    val configs: List<PlayerConfig>,
    /** The race as it stood before the first move: track, players and their start positions. */
    val initialState: GameState,
    /**
     * Every move in the order it was played; folding them over [initialState] reproduces the race.
     */
    val moves: List<MoveRecord>,
    /**
     * When the race finished, in epoch millis. Defaults to 0 because this type is
     * multiplatform and has no clock of its own; whoever records a replay sets it.
     */
    val timestamp: Long = 0L,
    /**
     * Stable identity, assigned by whoever stores the replay.
     *
     * The app used to identify a replay by its [timestamp], which is not an identity: two
     * races finishing in the same millisecond collide, and an imported file may carry no
     * timestamp at all. List keys and deletion both go through this instead. Empty for a
     * replay that has not been stored yet.
     */
    val id: String = "",
    /** Non-null when this replay was logged by a multiplayer server. */
    val server: ServerRaceInfo? = null,
) {
    /** A short human-readable label for list views. */
    fun label(): String = "$trackName — ${moves.size} moves"
}
