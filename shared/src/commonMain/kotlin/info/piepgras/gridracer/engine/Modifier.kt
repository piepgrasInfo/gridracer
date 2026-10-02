package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * The kinds of drivable hazard a track may carry.
 *
 * [OIL_SPILL] costs control: the next move is limited to three slip targets around where the car
 * would coast (see [GameEngine.oilSlipTargets]). [NAILS] costs speed: the next move may only keep
 * or reduce the heading, never accelerate.
 */
@Serializable
enum class ModifierType { OIL_SPILL, NAILS }

/**
 * A drivable hazard zone: unlike a [Wall], players may drive straight through the closed
 * polygon [area] - and driving through is exactly what triggers it. Any move whose path
 * touches the zone (see [GameEngine.modifiersOn], not just one that happens to end on a
 * grid point inside it) queues a one-turn effect on [PlayerState.pendingModifiers] for the
 * player's *next* move, consumed automatically the next time [GameEngine.legalMoves] runs
 * for them. [area] is authored on the same fine grid as [Wall] endpoints (see
 * [Track.subdivision]), closed the same way as a closed obstacle (first point repeated as
 * the last).
 *
 * Zones of different types may overlap, and a car in the overlap gets both effects.
 *
 * @property type Which effect the zone has.
 * @property area The zone as a closed polygon on the fine grid, first point repeated last.
 */
@Serializable
data class TrackModifier(val type: ModifierType, val area: List<Point>)
