package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * An integer point: a position on the coarse racing grid, a position on the fine authoring grid, or
 * a velocity - the same type serves all three, and which one is meant depends on where it is used
 * (see [Track.subdivision]).
 *
 * @property x Horizontal coordinate, growing to the right.
 * @property y Vertical coordinate, growing downwards, as on screen.
 */
@Serializable
data class Point(val x: Int, val y: Int) {
    /**
     * Component-wise sum - a position moved by a velocity, or two velocities added.
     *
     * @param other The point or vector to add.
     * @return A new point; neither operand changes.
     */
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    /**
     * Component-wise difference - the velocity that carries [other] to this point.
     *
     * @param other The point or vector to subtract.
     * @return A new point; neither operand changes.
     */
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    /**
     * Scales both coordinates, e.g. a coarse-grid point up to the fine grid.
     *
     * @param scalar The factor, typically [Track.subdivision].
     * @return A new point; this one is unchanged.
     */
    operator fun times(scalar: Int) = Point(x * scalar, y * scalar)
}
