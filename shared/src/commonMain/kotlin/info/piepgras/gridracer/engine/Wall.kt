package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * A straight wall segment on the fine grid; a car's move may not touch it.
 *
 * @property from One endpoint, in fine-grid units (see [Track.subdivision]).
 * @property to The other endpoint, in fine-grid units.
 */
@Serializable
data class Wall(val from: Point, val to: Point)
