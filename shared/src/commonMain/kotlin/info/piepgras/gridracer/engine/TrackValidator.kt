package info.piepgras.gridracer.engine

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Every reason [TrackValidator] can reject a hand-drawn track.
 *
 * A code, never prose: the editor maps each one to a translated message, so the engine has no
 * user-facing text and the same error reads correctly in every locale the app ships.
 */
enum class TrackValidationError {
    OUTER_TOO_FEW_POINTS,
    INNER_TOO_FEW_POINTS,
    OUTER_NOT_SIMPLE,
    INNER_NOT_SIMPLE,
    OUTER_TOO_SMALL,
    INNER_NOT_NESTED,
    CORRIDOR_TOO_NARROW,
    OBSTACLE_NOT_SIMPLE,
    OBSTACLE_TOUCHES_BOUNDARY,
    OBSTACLE_NARROWS_CORRIDOR,
    OBSTACLE_NOT_IN_TRACK,
    MODIFIER_NOT_SIMPLE,
    MODIFIER_NOT_IN_TRACK,
    FINISH_LINE_MISSING,
    FINISH_LINE_TOO_SHORT,
    FINISH_LINE_NOT_ACROSS_TRACK,
    START_POSITIONS_INVALID,
}

/**
 * The outcome of validating a whole [TrackDraft].
 *
 * @property errors Every rule the draft breaks; empty when the draft is valid.
 */
data class TrackValidationResult(val errors: Set<TrackValidationError>) {
    /** Whether the draft breaks no rule and may be built and saved. */
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Rejects hand-drawn tracks that would be impossible, degenerate, or "cheesable" -
 * self-crossing boundaries, an inner loop that isn't actually nested inside the outer
 * one, corridors pinched too narrow to race through, obstacles that touch a boundary
 * or choke the corridor, and a missing/nonsensical finish line. Pure and unit-testable;
 * used by both the editor (live per-step feedback) and before allowing a save.
 */
object TrackValidator {

    /** Minimum gap, in coarse grid units, between the two boundaries (or an obstacle and either boundary) anywhere along the loop. */
    const val MIN_CORRIDOR_WIDTH = 3.0

    /** Rejects trivially tiny loops - the "no cheesing" floor on outer-boundary perimeter. */
    const val MIN_OUTER_PERIMETER = 20.0

    /** Minimum finish-line length so it can host a row of 4 distinct rounded start positions. */
    const val MIN_FINISH_LINE_LENGTH = 5.0

    private const val EDGE_SAMPLE_STEP = 0.25

    /**
     * Runs every check against a complete draft, as the editor does before allowing a save.
     *
     * Obstacle, hazard and finish-line checks are skipped while the boundaries themselves are
     * invalid, so one bad stroke reports one problem instead of a cascade.
     *
     * @param draft The draft to check.
     * @return Every violation found; see [TrackValidationResult.isValid].
     */
    fun validate(draft: TrackDraft): TrackValidationResult {
        val subdivision = draft.subdivision
        val errors = mutableSetOf<TrackValidationError>()
        errors += validateBoundaries(draft.outerBoundary, draft.innerBoundary, subdivision)

        // Obstacle/finish-line checks assume valid boundaries; skip them otherwise to avoid noisy cascades.
        val boundariesOk = errors.none {
            it in setOf(
                TrackValidationError.OUTER_TOO_FEW_POINTS,
                TrackValidationError.INNER_TOO_FEW_POINTS,
                TrackValidationError.OUTER_NOT_SIMPLE,
                TrackValidationError.INNER_NOT_SIMPLE,
                TrackValidationError.INNER_NOT_NESTED,
            )
        }
        if (boundariesOk) {
            draft.obstacles.forEach { obstacle ->
                errors += validateObstacle(obstacle, draft.outerBoundary, draft.innerBoundary, subdivision)
            }
            draft.modifiers.forEach { modifier ->
                errors += validateModifier(modifier.area, draft.outerBoundary, draft.innerBoundary)
            }
            errors += validateFinishLine(draft)

            if (errors.none { it == TrackValidationError.FINISH_LINE_MISSING || it == TrackValidationError.FINISH_LINE_TOO_SHORT }) {
                val from = draft.finishLineFrom
                val to = draft.finishLineTo
                if (from != null && to != null) {
                    // Asks the builder the same question the built track will answer, on the
                    // same geometry: a start position is only valid if it is inside the track
                    // *and* has a legal opening move. Races need at least 2 players (see
                    // MIN_PLAYERS on the race setup screen), so anything less is unraceable.
                    val walls = TrackBuilder.wallsFor(draft.outerBoundary, draft.innerBoundary, draft.obstacles)
                    val startGrid = TrackBuilder.startGrid(walls, Wall(from, to), draft.finishDirection, subdivision)
                    if (startGrid.positions.size < 2) errors += TrackValidationError.START_POSITIONS_INVALID
                }
            }
        }
        return TrackValidationResult(errors)
    }

    /** Per-step feedback while only the outer boundary has been drawn so far. */
    fun validateOuterOnly(points: List<Point>, subdivision: Int = 1): Set<TrackValidationError> {
        val errors = mutableSetOf<TrackValidationError>()
        if (points.size < 3) {
            errors += TrackValidationError.OUTER_TOO_FEW_POINTS
            return errors
        }
        if (!isSimpleClosedPolygon(points)) errors += TrackValidationError.OUTER_NOT_SIMPLE
        if (perimeter(points) < MIN_OUTER_PERIMETER * subdivision) errors += TrackValidationError.OUTER_TOO_SMALL
        return errors
    }

    /** Per-step feedback while only the inner boundary has been drawn so far. */
    fun validateInnerOnly(points: List<Point>): Set<TrackValidationError> {
        val errors = mutableSetOf<TrackValidationError>()
        if (points.size < 3) {
            errors += TrackValidationError.INNER_TOO_FEW_POINTS
            return errors
        }
        if (!isSimpleClosedPolygon(points)) errors += TrackValidationError.INNER_NOT_SIMPLE
        return errors
    }

    /**
     * Checks the outer and inner loops together: enough points, no self-crossing, a large enough
     * outer loop, the inner one nested inside it, and a corridor at least [MIN_CORRIDOR_WIDTH] wide
     * all the way round.
     *
     * @param subdivision Fine grid units per coarse unit, to scale the coarse-unit thresholds.
     * @return The violations found; empty when both loops are acceptable.
     */
    fun validateBoundaries(outer: List<Point>, inner: List<Point>, subdivision: Int = 1): Set<TrackValidationError> {
        val errors = mutableSetOf<TrackValidationError>()
        if (outer.size < 3) errors += TrackValidationError.OUTER_TOO_FEW_POINTS
        if (inner.size < 3) errors += TrackValidationError.INNER_TOO_FEW_POINTS
        if (errors.isNotEmpty()) return errors

        if (!isSimpleClosedPolygon(outer)) errors += TrackValidationError.OUTER_NOT_SIMPLE
        if (!isSimpleClosedPolygon(inner)) errors += TrackValidationError.INNER_NOT_SIMPLE
        if (perimeter(outer) < MIN_OUTER_PERIMETER * subdivision) errors += TrackValidationError.OUTER_TOO_SMALL
        if (errors.contains(TrackValidationError.OUTER_NOT_SIMPLE) || errors.contains(TrackValidationError.INNER_NOT_SIMPLE)) {
            return errors
        }

        if (!isNested(inner, outer)) {
            errors += TrackValidationError.INNER_NOT_NESTED
            return errors
        }

        if (minCorridorWidth(outer, inner, subdivision) < MIN_CORRIDOR_WIDTH * subdivision) errors += TrackValidationError.CORRIDOR_TOO_NARROW
        return errors
    }

    /**
     * Checks one obstacle against the corridor it sits in: it must be a simple shape, lie inside
     * the track, keep clear of both boundaries, and leave a raceable gap past it.
     *
     * An obstacle of fewer than two points is ignored rather than reported - it cannot form a wall.
     *
     * @param obstacle The obstacle, closed (first point repeated last) or an open polyline.
     * @param subdivision Fine grid units per coarse unit, to scale the coarse-unit thresholds.
     * @return The violations found; empty when the obstacle is acceptable.
     */
    fun validateObstacle(obstacle: List<Point>, outer: List<Point>, inner: List<Point>, subdivision: Int = 1): Set<TrackValidationError> {
        val errors = mutableSetOf<TrackValidationError>()
        if (obstacle.size < 2) return errors // a single point can't form a wall segment; ignore rather than error

        val closed = obstacle.size > 2 && obstacle.first() == obstacle.last()
        if (closed) {
            val inCorridor = obstacle.any { Geometry.pointInPolygon(it, outer) && !Geometry.pointInPolygon(it, inner) }
            if (!inCorridor) errors += TrackValidationError.OBSTACLE_NOT_IN_TRACK
        }

        if (hasSelfIntersectingSegments(obstacle)) errors += TrackValidationError.OBSTACLE_NOT_SIMPLE

        val obstacleSegments = obstacle.zipWithNext()
        val outerEdges = Geometry.closedEdges(outer)
        val innerEdges = Geometry.closedEdges(inner)
        val touchesBoundary = obstacleSegments.any { (a, b) ->
            outerEdges.any { (c, d) -> Geometry.segmentsIntersect(a, b, c, d) } ||
                innerEdges.any { (c, d) -> Geometry.segmentsIntersect(a, b, c, d) }
        }
        if (touchesBoundary) {
            errors += TrackValidationError.OBSTACLE_TOUCHES_BOUNDARY
            return errors
        }

        val minDistToOuter = obstacleSegments.minOf { (a, b) -> minDistanceSegmentToPolygon(a, b, outer, subdivision) }
        val minDistToInner = obstacleSegments.minOf { (a, b) -> minDistanceSegmentToPolygon(a, b, inner, subdivision) }
        val minCorridorWidth = MIN_CORRIDOR_WIDTH * subdivision
        if (minDistToOuter < minCorridorWidth || minDistToInner < minCorridorWidth) {
            errors += TrackValidationError.OBSTACLE_NARROWS_CORRIDOR
        }
        return errors
    }

    /**
     * A modifier zone only needs to be a simple polygon that actually sits in the track
     * corridor - unlike [validateObstacle], it doesn't block movement, so there's no
     * corridor-narrowing or touches-boundary check: it's fine for a hazard to touch a
     * wall or another obstacle/modifier.
     */
    fun validateModifier(area: List<Point>, outer: List<Point>, inner: List<Point>): Set<TrackValidationError> {
        val errors = mutableSetOf<TrackValidationError>()
        if (area.size < 3) {
            errors += TrackValidationError.MODIFIER_NOT_IN_TRACK
            return errors
        }
        if (hasSelfIntersectingSegments(area)) errors += TrackValidationError.MODIFIER_NOT_SIMPLE
        val inCorridor = area.any { Geometry.pointInPolygon(it, outer) && !Geometry.pointInPolygon(it, inner) }
        if (!inCorridor) errors += TrackValidationError.MODIFIER_NOT_IN_TRACK
        return errors
    }

    private fun validateFinishLine(draft: TrackDraft): Set<TrackValidationError> {
        val from = draft.finishLineFrom
        val to = draft.finishLineTo
        if (from == null || to == null) return setOf(TrackValidationError.FINISH_LINE_MISSING)

        val errors = mutableSetOf<TrackValidationError>()
        val length = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
        if (length < MIN_FINISH_LINE_LENGTH * draft.subdivision) errors += TrackValidationError.FINISH_LINE_TOO_SHORT

        val dirLength = hypot(draft.finishDirection.x.toDouble(), draft.finishDirection.y.toDouble())
        if (dirLength == 0.0 || length == 0.0) {
            errors += TrackValidationError.FINISH_LINE_NOT_ACROSS_TRACK
        } else {
            // The direction of travel across a gate should be roughly perpendicular to the
            // gate itself; a direction nearly parallel to the line can't sensibly "cross" it.
            val lineX = (to.x - from.x) / length
            val lineY = (to.y - from.y) / length
            val dirX = draft.finishDirection.x / dirLength
            val dirY = draft.finishDirection.y / dirLength
            val cosAngle = kotlin.math.abs(lineX * dirX + lineY * dirY)
            if (cosAngle > 0.7) errors += TrackValidationError.FINISH_LINE_NOT_ACROSS_TRACK
        }
        return errors
    }

    private fun isSimpleClosedPolygon(points: List<Point>): Boolean {
        if (points.size < 3) return false
        if (points.toSet().size != points.size) return false
        val edges = Geometry.closedEdges(points)
        val n = edges.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val adjacent = j == i + 1 || (i == 0 && j == n - 1)
                if (adjacent) continue
                val (a1, a2) = edges[i]
                val (b1, b2) = edges[j]
                if (Geometry.segmentsIntersect(a1, a2, b1, b2)) return false
            }
        }
        return true
    }

    private fun hasSelfIntersectingSegments(points: List<Point>): Boolean {
        if (points.size < 3) return false
        val closed = points.size > 2 && points.first() == points.last()
        val segments = points.zipWithNext()
        val n = segments.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val adjacent = j == i + 1 || (closed && i == 0 && j == n - 1)
                if (adjacent) continue
                val (a1, a2) = segments[i]
                val (b1, b2) = segments[j]
                if (Geometry.segmentsIntersect(a1, a2, b1, b2)) return true
            }
        }
        return false
    }

    private fun isNested(inner: List<Point>, outer: List<Point>): Boolean {
        if (inner.any { !Geometry.pointInPolygon(it, outer) }) return false
        val outerEdges = Geometry.closedEdges(outer)
        val innerEdges = Geometry.closedEdges(inner)
        return innerEdges.none { (a, b) -> outerEdges.any { (c, d) -> Geometry.segmentsIntersect(a, b, c, d) } }
    }

    /** Global minimum gap between [outer] and [inner], sampling both boundaries' edges. */
    private fun minCorridorWidth(outer: List<Point>, inner: List<Point>, subdivision: Int = 1): Double {
        val outerToInner = Geometry.closedEdges(outer).minOf { (a, b) -> minDistanceSegmentToPolygon(a, b, inner, subdivision) }
        val innerToOuter = Geometry.closedEdges(inner).minOf { (a, b) -> minDistanceSegmentToPolygon(a, b, outer, subdivision) }
        return minOf(outerToInner, innerToOuter)
    }

    /** Samples segment [a]-[b] and returns the smallest distance from any sample to [polygon]'s edges. */
    private fun minDistanceSegmentToPolygon(a: Point, b: Point, polygon: List<Point>, subdivision: Int = 1): Double {
        val length = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        val steps = max(1, (length / (EDGE_SAMPLE_STEP * subdivision)).toInt())
        val edges = Geometry.closedEdges(polygon)
        var minDistSq = Double.MAX_VALUE
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val px = a.x + t * (b.x - a.x)
            val py = a.y + t * (b.y - a.y)
            for ((c, d) in edges) {
                val distSq = Geometry.distanceToSegmentSq(px, py, c, d)
                if (distSq < minDistSq) minDistSq = distSq
            }
        }
        return sqrt(minDistSq)
    }

    private fun perimeter(points: List<Point>): Double =
        Geometry.closedEdges(points).sumOf { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
}
