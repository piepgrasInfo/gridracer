package info.piepgras.gridracer.engine

import kotlin.math.hypot

/**
 * Geometry helpers used only by the track editor: polygon cleanup, auto-closing,
 * repairing self-crossing freehand strokes, and merging shapes into one another.
 *
 * Everything that has to cope with arbitrary freehand input goes through
 * [GridRegion] rather than manipulating polygon vertices directly - see that class
 * for why.
 */
object TrackEditorGeometry {

    /**
     * Remove a trailing duplicate of the first point so the polygon is represented as
     * an open list of vertices (the closing edge is implicit: last -> first).
     * If the stroke ends within [tolerance] of the start, treat it as closed by
     * dropping the trailing point - the closing edge is then from the previous vertex
     * back to the first one, which is already short.
     */
    fun autoClose(points: List<Point>, tolerance: Double = 2.0): List<Point> {
        val cleaned = removeConsecutiveDuplicates(points)
        if (cleaned.size < 3) return cleaned
        val first = cleaned.first()
        val last = cleaned.last()
        val dist = hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble())
        return if (last == first || dist <= tolerance) {
            cleaned.dropLast(1)
        } else {
            cleaned
        }
    }

    /**
     * Remove duplicate consecutive points and redundant collinear middle points.
     * Keeps at least 3 points so the polygon remains closed (first == last).
     */
    fun simplifyPolygon(points: List<Point>): List<Point> {
        val cleaned = removeConsecutiveDuplicates(points)
        if (cleaned.size < 4) return cleaned

        val isClosed = cleaned.first() == cleaned.last()
        val distinct = if (isClosed) cleaned.dropLast(1) else cleaned
        if (distinct.size < 3) return cleaned

        val result = mutableListOf<Point>()
        val n = distinct.size
        for (i in 0 until n) {
            val prev = distinct[(i - 1 + n) % n]
            val curr = distinct[i]
            val next = distinct[(i + 1) % n]
            if (Geometry.orientation(prev, curr, next) == 0 && Geometry.onSegment(prev, next, curr)) {
                // curr lies on the straight line between prev and next - drop it.
            } else {
                result.add(curr)
            }
        }

        val simplified = if (result.size < 3) distinct.toMutableList() else result
        return if (isClosed) simplified + cleaned.first() else simplified
    }

    /**
     * Turns any freehand stroke - including one that crosses itself, doubles back, or
     * spirals - into the closest simple closed polygon, as an open vertex list.
     *
     * The stroke is treated as a closed polygon, filled under the even-odd rule, and
     * the largest resulting blob's outline is handed back. For a spiral that is its
     * outer turn: the closest valid shape to what was drawn, rather than an error the
     * user can only undo. Returns an empty list if the stroke encloses no area at all
     * (a straight scribble), which callers report as "needs at least 3 points".
     *
     * Cost is bounded by the bounding box's area, not by the stroke's length or its
     * number of self-crossings - unlike the vertex-shuffling approach this replaced,
     * which was quadratic per pass over a quadratic number of passes and hung the UI
     * (see the 2026-08 crash triage).
     */
    fun repairShape(points: List<Point>): List<Point> {
        val closed = autoClose(points)
        if (closed.size < 3) return emptyList()
        val largest = GridRegion.fromPolygon(closed).filledContours().firstOrNull() ?: return emptyList()
        return simplifyPolygon(largest)
    }

    /** Shoelace signed area of a closed polygon (first == last or not). */
    fun signedArea(points: List<Point>): Double {
        var area = 0.0
        val n = points.size
        for (i in 0 until n) {
            val a = points[i]
            val b = points[(i + 1) % n]
            area += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return area / 2.0
    }

    /** Returns the input with counter-clockwise orientation, reversing if necessary. */
    fun ensureCcw(points: List<Point>): List<Point> =
        if (signedArea(points) < 0) points.reversed() else points

    /**
     * Merge an obstacle that intersects a track boundary.
     * - [isOuter] = true  -> carve the obstacle out of the boundary  (boundary \ obstacle)
     * - [isOuter] = false -> grow the boundary to include the obstacle (boundary u obstacle)
     *
     * Returns null when the merge shouldn't happen: the shapes don't overlap, the
     * result would be unchanged (an obstacle already swallowed by the boundary), or
     * the result wouldn't be a single simple loop - an obstacle floating inside the
     * outer boundary punches a hole rather than a notch, and belongs in
     * [TrackDraft.obstacles] instead.
     */
    fun mergeObstacleWithBoundary(boundary: List<Point>, obstacle: List<Point>, isOuter: Boolean): List<Point>? {
        if (boundary.size < 3 || obstacle.size < 3) return null
        val boundaryRegion = GridRegion.fromPolygon(boundary)
        val obstacleRegion = GridRegion.fromPolygon(obstacle)
        if (boundaryRegion.isEmpty || obstacleRegion.isEmpty) return null
        if (boundaryRegion.intersect(obstacleRegion).isEmpty) return null

        val merged = if (isOuter) boundaryRegion.subtract(obstacleRegion) else boundaryRegion.union(obstacleRegion)
        if (merged.isEmpty || merged.coversSameAs(boundaryRegion)) return null

        val rings = merged.contours()
        // More than one ring means either several disconnected pieces or a hole:
        // neither can be expressed as a single boundary loop.
        if (rings.size != 1) return null
        val ring = rings.single()
        if (signedArea(ring) <= 0.0) return null
        return simplifyPolygon(ring)
    }

    /**
     * Applies the auto-merge rules for a newly drawn hazard zone against the track it
     * was drawn on, returning the full replacement modifier list (or an empty list if
     * the new zone was entirely consumed):
     *
     * - **Against walls and obstacles** - the barrier wins. Anything of the new zone
     *   that lies outside the drivable corridor described by [outer]/[inner]/[obstacles]
     *   is cut away; a zone can end up split into several pieces, which are kept as
     *   separate same-type entries.
     * - **Against a zone of the same type** - the two are unioned into one.
     * - **Against a zone of a different type** - both are kept, overlapping. The
     *   overlap belongs to both, and a car in it gets both effects.
     *
     * Null means the new zone contributed nothing at all - it was drawn entirely
     * outside the corridor - which the caller reports rather than silently accepting.
     */
    fun mergeModifier(
        newModifier: TrackModifier,
        existing: List<TrackModifier>,
        outer: List<Point>,
        inner: List<Point>,
        obstacles: List<List<Point>> = emptyList(),
    ): List<TrackModifier>? {
        var region = GridRegion.fromPolygon(newModifier.area)
            .intersect(GridRegion.corridor(outer, inner, obstacles))
        if (region.isEmpty) return null

        val sameType = existing.filter { it.type == newModifier.type }
        val otherTypes = existing.filter { it.type != newModifier.type }
        for (zone in sameType) {
            region = region.union(GridRegion.fromPolygon(zone.area))
        }
        val mergedPieces = region.filledContours()
            .filter { it.size >= 3 }
            .map { TrackModifier(newModifier.type, closeLoop(it)) }
        return otherTypes + mergedPieces
    }

    /**
     * Re-applies the "a barrier always wins over a hazard" rule to zones that were already
     * on the track when its geometry changed - a boundary redrawn, an obstacle added, an
     * obstacle merged into a boundary. [mergeModifier] enforces the rule for a zone drawn
     * onto existing barriers; this enforces it for barriers drawn onto existing zones, and
     * the two together mean the order the user works in never leaves a hazard sitting on
     * top of a wall.
     *
     * Each surviving zone is re-traced from the clipped region, so one cut in half by a new
     * obstacle becomes two entries of the same type; one buried entirely disappears.
     */
    fun clipModifiersToCorridor(
        modifiers: List<TrackModifier>,
        outer: List<Point>,
        inner: List<Point>,
        obstacles: List<List<Point>> = emptyList(),
    ): List<TrackModifier> {
        if (modifiers.isEmpty()) return modifiers
        val corridor = GridRegion.corridor(outer, inner, obstacles)
        if (corridor.isEmpty) return emptyList()
        return modifiers.flatMap { zone ->
            val clipped = GridRegion.fromPolygon(zone.area).intersect(corridor)
            if (clipped.isEmpty) {
                emptyList()
            } else {
                clipped.filledContours()
                    .filter { it.size >= 3 }
                    .map { TrackModifier(zone.type, closeLoop(it)) }
            }
        }
    }

    /** Repeats the first point at the end, the storage convention for a closed loop. */
    fun closeLoop(points: List<Point>): List<Point> =
        if (points.isEmpty() || points.first() == points.last()) points else points + points.first()

    /** Remove consecutive duplicate points; preserve first and last. */
    private fun removeConsecutiveDuplicates(points: List<Point>): List<Point> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf(points.first())
        for (i in 1 until points.size) {
            if (points[i] != result.last()) result.add(points[i])
        }
        return result
    }
}
