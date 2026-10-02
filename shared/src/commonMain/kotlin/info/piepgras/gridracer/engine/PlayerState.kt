package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * Where a player stands in the race. [CRASHED] and [FINISHED] are final; only [RACING] players are
 * given turns.
 */
@Serializable
enum class PlayerStatus { RACING, CRASHED, FINISHED }

/**
 * One car in a race: where it is, how it is moving, and what has happened to it so far.
 *
 * @property id The player's id, which is also their index in [GameState.players].
 * @property position Current position on the coarse grid.
 * @property lastVelocity The vector of the move just made. The next move is this plus a step of at
 *     most one in each axis - the whole of the paper-race rule.
 * @property status Whether the player is still racing, crashed or finished.
 * @property path Every position the car has occupied, oldest first; drawn as its trail.
 * @property finishRank 1-based finishing position, or null while the player has not finished (a
 *     crash or the round cap also leaves it null, which scores nothing).
 */
@Serializable
data class PlayerState(
    val id: Int,
    val position: Point,
    val lastVelocity: Point = Point(0, 0),
    val status: PlayerStatus = PlayerStatus.RACING,
    val path: List<Point> = listOf(position),
    val finishRank: Int? = null,
    /** Number of times this player has crossed the finish line going the right way. */
    val lapsCompleted: Int = 0,
    /**
     * Effects queued by driving over [TrackModifier] zones during the move just made, and in
     * force for exactly the next one: [GameEngine.applyMove] recomputes this from each move's
     * own path, so a turn spent clear of every zone ends with an empty set, and touching a
     * zone again - the same one or another - queues the effect afresh.
     *
     * A set, not a single value: hazard zones of different types are allowed to overlap, and a
     * car that drives through the overlap gets all of them at once.
     */
    val pendingModifiers: Set<ModifierType> = emptySet(),
    /**
     * Which way the car slides off the line it was going to coast to, while [pendingModifiers]
     * holds [ModifierType.OIL_SPILL]: one-step offsets from the coast point, drawn when the
     * spill was hit (see [GameEngine.applyMove]) rather than when the move is chosen, so the
     * choice a player is shown is the choice they get. Empty means the spill offers the coast
     * point alone, which is also how a planner should treat a spill it has not yet hit.
     *
     * Exactly two distinct offsets once drawn: oil offers the coast point plus two randomly chosen
     * neighbouring grid points, so a spill is a loss of control rather than a fully scripted
     * move - there is still a decision to make, just not the usual one.
     */
    val oilSlips: List<Point> = emptyList(),
    /**
     * Fine-grid point where a burning wreck should be drawn for a [PlayerStatus.CRASHED]
     * player, or null if no wreck is applicable (backward finish-line crossing, forfeit).
     * Set by [GameEngine.startTurn] for wall traps (the intersection of the coast projection
     * line with the wall that trapped the car) and by [GameEngine.applyMove] for oil-forced
     * collisions (the target cell). Wrecks never block other players.
     */
    val wreckPosition: Point? = null,
    /**
     * How many more of this car's moves are braked by the gravel trap. Set to two by every move
     * that touches the gravel and counted down by each move that does not, so a car is braked for
     * every move it makes from the gravel and for the two after it is back on the track; while it
     * is above zero, [GameEngine.legalMoves] centres the car's choices on a coast point pulled back
     * along its heading. Always zero in a race without the gravel trap.
     */
    val gravelBrakeTurns: Int = 0,
)
