package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * The user-editable shape of a hand-drawn track, before it's been validated and
 * compiled into a playable [Track] by [TrackBuilder]. Points are authored on a grid
 * [subdivision] times finer than the coarse grid cars actually move on - the same
 * fine/coarse split the built-in tracks use (see [Track.subdivision]) - so walls and
 * the finish line can land at any angle instead of being pinned to whole coarse cells.
 * Defaults to 1 (no subdivision) so drafts saved before this existed keep decoding at
 * their original coarse-only scale.
 */
@Serializable
data class TrackDraft(
    /** Closed polygon (edges connect consecutive points, wrapping last-to-first). */
    val outerBoundary: List<Point> = emptyList(),
    /** Closed polygon, must sit fully inside [outerBoundary]. */
    val innerBoundary: List<Point> = emptyList(),
    /** Each entry is a closed polygon (consecutive points plus a closing edge) - an obstacle loop. */
    val obstacles: List<List<Point>> = emptyList(),
    /** Drivable hazard loops - see [TrackModifier]. Unlike [obstacles], these are never merged into a boundary. */
    val modifiers: List<TrackModifier> = emptyList(),
    /** One end of the finish line, on the fine grid; null until the editor has placed it. */
    val finishLineFrom: Point? = null,
    /** The other end of the finish line, on the fine grid; null until the editor has placed it. */
    val finishLineTo: Point? = null,
    /**
     * Which way a valid finish crosses the line - see [Track.finishDirection]. `(0, 0)` until set.
     */
    val finishDirection: Point = Point(0, 0),
    /** Fine grid units per coarse unit for every point in this draft; see the class description. */
    val subdivision: Int = 1,
)
