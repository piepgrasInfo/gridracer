package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * Whether a race is still being played. [RACE_OVER] is final: every player has finished or crashed,
 * or the round cap in [GameState.maxRounds] was reached.
 */
@Serializable
enum class GamePhase { IN_PROGRESS, RACE_OVER }

/**
 * The complete, immutable state of one race - the single value every [GameEngine] call takes and
 * returns.
 *
 * Nothing in a race lives anywhere else: the app snapshots this to survive process death, the
 * server holds one per room, and a replay is a sequence of them. Player ids must equal their index
 * in [players] (see [isConsistent]).
 *
 * @property track The track being raced.
 * @property players Every car in the race, indexed by player id.
 * @property currentPlayerIndex Index in [players] of the player whose move it is.
 * @property round The current round, starting at 1; it ticks over when the turn wraps back to the
 *     first player.
 * @property phase Whether the race is still running.
 * @property totalLaps Laps a player must complete to finish.
 * @property rules The rules the race is played under, fixed at the start. Defaults to the rules
 *     every race had before they were recorded, so a state saved by an older build plays on exactly
 *     as it was played.
 */
@Serializable
data class GameState(
    val track: Track,
    val players: List<PlayerState>,
    val currentPlayerIndex: Int,
    val round: Int = 1,
    val phase: GamePhase = GamePhase.IN_PROGRESS,
    val totalLaps: Int = 1,
    /**
     * Hard cap on rounds; once [round] would tick past this the race ends with
     * [GamePhase.RACE_OVER] and any player still [RACING][PlayerStatus.RACING] keeps a null
     * finishRank (scoring 0). Defaults to [GameEngine.MAX_ROUNDS] so a normal race is
     * bounded, but a caller (e.g. a simulation) may raise it to study raw AI finishing
     * ability without the cap cutting races short.
     */
    val maxRounds: Int = GameEngine.MAX_ROUNDS,
    /**
     * Monotonic revision number used by the multiplayer server to detect stale moves
     * and to resync clients. The engine preserves it through [GameState.copy].
     */
    val revision: Int = 0,
    val rules: RaceRules = RaceRules.LEGACY,
) {
    /** The player whose move it is - `players[currentPlayerIndex]`. */
    val currentPlayer: PlayerState get() = players[currentPlayerIndex]

    /**
     * Whether this state is one the engine can actually be driven from. A [GameState] is
     * `@Serializable` and gets restored from a saved snapshot written by an older build, so
     * a decode succeeding is not on its own proof the fields agree with each other - and
     * [currentPlayer] indexes straight into [players]. Anything reviving a stored state
     * checks this first and starts over rather than crashing on the first frame.
     */
    val isConsistent: Boolean
        get() = players.isNotEmpty() &&
            currentPlayerIndex in players.indices &&
            players.map { it.id }.toSet() == players.indices.toSet() &&
            totalLaps >= 1 &&
            maxRounds >= 1 &&
            rules.version in RaceRules.LEGACY_VERSION..RaceRules.CURRENT_VERSION &&
            track.startPositions.isNotEmpty()
}
