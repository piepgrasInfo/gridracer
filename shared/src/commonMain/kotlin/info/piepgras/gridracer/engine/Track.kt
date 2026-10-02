package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * A playable race track: the grid cars move on, the walls they must not touch, the finish line they
 * race to, and the start slots they begin from.
 *
 * Built-in tracks are decoded from JSON in [Tracks]; user-drawn ones are compiled from a
 * [TrackDraft] by [TrackBuilder]. Either way a [Track] is final - play never validates or rebuilds
 * one.
 *
 * @property name Display name, shown in the track picker and on results and replays.
 * @property height Grid height in coarse units; see [width].
 * @property walls Every wall segment, on the fine grid. A move whose path touches one crashes.
 * @property finishLine The finish line, on the fine grid. Only a crossing in [finishDirection]
 *     counts.
 */
@Serializable
data class Track(
    val name: String,
    /** Grid dimensions in coarse (game-mechanic) units - what players actually move on. */
    val width: Int,
    val height: Int,
    /**
     * How many fine-grid units make up one coarse grid unit. [walls] and [finishLine] are
     * authored on this finer grid so wall corners aren't forced to land on the coarse
     * lattice players move on; collision detection scales player movement up to fine-grid
     * units before testing it against them (see GameEngine). The fine grid is never drawn
     * directly - only the coarse grid dots are visible during play. Defaults to 1 (no
     * subdivision, walls share the coarse grid) so code that doesn't care can ignore it.
     */
    val subdivision: Int = 1,
    val walls: List<Wall>,
    /** Drivable hazard zones - see [TrackModifier]. Distinct from [walls]: cars pass through these freely. */
    val modifiers: List<TrackModifier> = emptyList(),
    val finishLine: Wall,
    /**
     * The direction of travel that counts as a valid finish. A move only finishes
     * the race if it crosses [finishLine] AND its own direction has a positive dot
     * product with this vector - crossing the line the other way is legal (players
     * can drive backwards over their own start) but doesn't rank the player.
     */
    val finishDirection: Point,
    /**
     * Every coarse grid point inside the start zone that a car can actually start
     * from: inside the track, within [startZoneDepth] of the finish line on the
     * starting side, and with at least one first move that crosses the line the right
     * way. Derived, never hand-authored - see `TrackBuilder.startGrid`.
     */
    val startPositions: List<Point>,
    /**
     * How far back from [finishLine], in coarse grid units, the starting box reaches.
     * The editor grows this until the box holds a full grid of cars, so it varies per
     * track; it is also what the placement screen shades and what [GameEngine] treats
     * as "still on the start line" when deciding whether a move is a player's first.
     */
    val startZoneDepth: Double = DEFAULT_START_ZONE_DEPTH,
) {
    /**
     * Everything derived from [walls] that the rules need again and again - the outer boundary,
     * a wall index, the gravel band - worked out on first use and kept for the life of this
     * instance. A delegated property, so it is neither serialized nor part of [equals]: two
     * tracks with the same walls are the same track, wherever their geometry was computed.
     */
    internal val geometry: TrackGeometry by lazy { TrackGeometry(this) }

    /**
     * What a renderer needs to draw the gravel band, or null when the track has no closed outer
     * boundary to put one round. Drawn whether or not a race has the gravel trap switched on -
     * only the rules differ.
     */
    val gravelView: GravelView? by lazy {
        val loop = geometry.outerLoop ?: return@lazy null
        val band = GravelGeometry.BAND_WIDTH_COARSE * subdivision
        // The bounds come from the loop alone, so a thumbnail never builds the band's mask.
        GravelView(
            outerLoop = loop,
            bandWidth = band,
            minCoarse = Point(
                minOf(0, (loop.minOf { it.x } - band).floorDiv(subdivision)),
                minOf(0, (loop.minOf { it.y } - band).floorDiv(subdivision)),
            ),
            maxCoarse = Point(
                maxOf(width, -((-(loop.maxOf { it.x } + band)).floorDiv(subdivision))),
                maxOf(height, -((-(loop.maxOf { it.y } + band)).floorDiv(subdivision))),
            ),
            marks = { geometry.gravel?.barrierMarks.orEmpty() },
        )
    }

    companion object {
        /**
         * Half a diagonal step: the shallowest useful box, holding exactly the grid
         * points closest to the line. Also the value tracks saved before the start box
         * was configurable decode with, so their start grids stay exactly as they were.
         */
        const val DEFAULT_START_ZONE_DEPTH: Double = 0.70710678118 // 1/sqrt(2)
    }
}

/**
 * The gravel band of a [Track], as the renderer draws it.
 *
 * @property outerLoop The outer boundary on the fine grid, vertices in order, closing back to the
 *     first; the band is everything outside it within [bandWidth].
 * @property bandWidth Width of the band in fine-grid units.
 * @property minCoarse Top-left corner, in coarse units, of the area that has to be visible to show
 *     the whole track and its band - it can be negative where the band runs past the grid.
 * @property maxCoarse Bottom-right corner of that area, in coarse units.
 */
class GravelView(
    val outerLoop: List<Point>,
    val bandWidth: Int,
    val minCoarse: Point,
    val maxCoarse: Point,
    marks: () -> List<Point>,
) {
    /**
     * Fine-grid points cut out of the band - across narrow notches and in line with the finish
     * line - which behave like the barrier. Worked out on first use: a thumbnail never needs them.
     */
    val barrierMarks: List<Point> by lazy(marks)
}
