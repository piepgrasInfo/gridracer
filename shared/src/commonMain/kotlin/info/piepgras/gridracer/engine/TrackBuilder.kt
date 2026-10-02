package info.piepgras.gridracer.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Compiles a validated [TrackDraft] into a playable [Track]. Callers should run [TrackValidator] first. */
object TrackBuilder {

    /** How many cars a start box should be able to hold; the box deepens until it can. */
    const val TARGET_START_POSITIONS = 4

    /** Deepening past this would put cars an unreasonable distance behind the line. */
    const val MAX_START_ZONE_DEPTH = 2.5

    private const val START_ZONE_DEPTH_STEP = 0.25

    /**
     * A start box: how deep it had to be, and every grid point in it a car can start from.
     *
     * @property depth How far back from the finish line the box reaches, in coarse units.
     * @property positions Every coarse grid point in the box a car can start from.
     */
    data class StartGrid(val depth: Double, val positions: List<Point>)

    /**
     * Compiles [draft] into a playable track named [name].
     *
     * Shifts the drawing so it sits one coarse unit inside the grid, turns every loop into walls
     * (see [wallsFor]), and derives the start slots from the finish line with [startGrid].
     *
     * @return The track, ready to race.
     * @throws IllegalArgumentException if the draft has no finish line. Other invalid shapes are
     *     not caught here - run [TrackValidator.validate] first.
     */
    fun build(name: String, draft: TrackDraft): Track {
        val finishFrom = requireNotNull(draft.finishLineFrom) { "finishLineFrom must be set" }
        val finishTo = requireNotNull(draft.finishLineTo) { "finishLineTo must be set" }
        val subdivision = draft.subdivision

        val allPoints = draft.outerBoundary + draft.innerBoundary + draft.obstacles.flatten() +
            draft.modifiers.flatMap { it.area } + listOf(finishFrom, finishTo)
        val offset = shiftFor(allPoints, subdivision)
        fun shift(p: Point) = p + offset

        val shiftedOuter = draft.outerBoundary.map(::shift)
        val shiftedInner = draft.innerBoundary.map(::shift)
        val shiftedObstacles = draft.obstacles.map { obstacle -> obstacle.map(::shift) }
        val shiftedModifiers = draft.modifiers.map { modifier -> modifier.copy(area = modifier.area.map(::shift)) }
        val finishLine = Wall(shift(finishFrom), shift(finishTo))

        val walls = wallsFor(shiftedOuter, shiftedInner, shiftedObstacles)

        val maxXFine = shiftedOuter.maxOf { it.x }
        val maxYFine = shiftedOuter.maxOf { it.y }

        val startGrid = startGrid(walls, finishLine, draft.finishDirection, subdivision)

        return Track(
            name = name,
            width = ceilDiv(maxXFine, subdivision) + 1,
            height = ceilDiv(maxYFine, subdivision) + 1,
            subdivision = subdivision,
            walls = walls,
            modifiers = shiftedModifiers,
            finishLine = finishLine,
            finishDirection = draft.finishDirection,
            startPositions = startGrid.positions,
            startZoneDepth = startGrid.depth,
        )
    }

    /**
     * How far to move a drawing so it sits one coarse unit in from the grid edge.
     * Deliberately a whole number of coarse units: shifting by a fraction of one would
     * move the drawing relative to the lattice cars move on, so the start grid computed
     * while validating a draft would not be the one the built track ends up with.
     */
    private fun shiftFor(points: List<Point>, subdivision: Int): Point {
        // The smallest whole number of coarse units that still leaves at least a
        // one-coarse-unit margin - so a drawing made far from the origin is pulled back
        // in, not just pushed away from the edge.
        fun axis(minValue: Int): Int {
            val need = subdivision - minValue
            val units = if (need >= 0) (need + subdivision - 1) / subdivision else -((-need) / subdivision)
            return units * subdivision
        }
        return Point(axis(points.minOf { it.x }), axis(points.minOf { it.y }))
    }

    /**
     * Turns the drawn shapes into wall segments.
     *
     * The outer and inner boundaries and every closed obstacle become closed loops, each edge one
     * wall, the last point joined back to the first. An open obstacle stays an open polyline.
     *
     * @return Every wall segment, outer boundary first.
     */
    fun wallsFor(outer: List<Point>, inner: List<Point>, obstacles: List<List<Point>>): List<Wall> {
        val obstacleWalls = obstacles.flatMap { obstacle ->
            val closed = obstacle.size > 2 && obstacle.first() == obstacle.last()
            if (closed) closedWalls(obstacle) else openWalls(obstacle)
        }
        return closedWalls(outer) + closedWalls(inner) + obstacleWalls
    }

    /**
     * The start box for a finish line, deepened from [Track.DEFAULT_START_ZONE_DEPTH]
     * until it holds [TARGET_START_POSITIONS] cars (or [MAX_START_ZONE_DEPTH] is
     * reached, at which point the deepest attempt wins).
     *
     * Shallowest-that-works rather than a fixed depth on purpose: a deep box stacks
     * cars in rows behind each other, where the back row's path off the line can be
     * blocked by the front row.
     */
    fun startGrid(walls: List<Wall>, finishLine: Wall, finishDirection: Point, subdivision: Int): StartGrid {
        var best = StartGrid(Track.DEFAULT_START_ZONE_DEPTH, emptyList())
        var depth = Track.DEFAULT_START_ZONE_DEPTH
        while (depth <= MAX_START_ZONE_DEPTH + 1e-9) {
            val positions = startPositionsFor(walls, finishLine, finishDirection, subdivision, depth)
            if (positions.size > best.positions.size) best = StartGrid(depth, positions)
            if (positions.size >= TARGET_START_POSITIONS) return StartGrid(depth, positions)
            depth += START_ZONE_DEPTH_STEP
        }
        return best
    }

    /**
     * Every coarse grid point a car may be placed on for this finish line: within
     * [depth] coarse units of the line, on the starting side (or exactly on it), inside
     * the track, and - the check whose absence made cars silently crash on the grid -
     * with at least one first move that both clears the walls and crosses the line the
     * right way, which is what [GameEngine.legalMoves] demands of an opening move.
     *
     * Ordered nearest-the-line first, then along the line, so the front row of the grid
     * is handed out before any row behind it.
     */
    fun startPositionsFor(
        walls: List<Wall>,
        finishLine: Wall,
        finishDirection: Point,
        subdivision: Int,
        depth: Double = Track.DEFAULT_START_ZONE_DEPTH,
    ): List<Point> {
        val from = finishLine.from
        val to = finishLine.to
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0.0) return emptyList()

        val reachFine = depth * subdivision
        val reachCoarse = ceil(reachFine / subdivision).toInt() + 1
        val xMin = floor(minOf(from.x, to.x).toDouble() / subdivision).toInt() - reachCoarse
        val xMax = ceil(maxOf(from.x, to.x).toDouble() / subdivision).toInt() + reachCoarse
        val yMin = floor(minOf(from.y, to.y).toDouble() / subdivision).toInt() - reachCoarse
        val yMax = ceil(maxOf(from.y, to.y).toDouble() / subdivision).toInt() + reachCoarse

        // A tiny slack so a point sitting exactly `depth` away isn't lost to rounding.
        val reachSq = reachFine * reachFine + 1e-6

        val candidates = mutableListOf<Triple<Point, Double, Double>>()
        for (x in xMin..xMax) for (y in yMin..yMax) {
            val p = Point(x, y)
            val pFine = p * subdivision
            val nearest = Geometry.nearestPointOnSegment(pFine, from, to)
            val side = (pFine.x - nearest.x) * finishDirection.x + (pFine.y - nearest.y) * finishDirection.y
            if (side > 0) continue // already past the line: that's the race, not the grid
            val distSq = Geometry.distancePointToSegmentSq(pFine, from, to)
            if (distSq > reachSq) continue
            if (!Geometry.insideWalls(pFine, walls)) continue
            if (!hasForwardFirstMove(p, walls, finishLine, finishDirection, subdivision)) continue
            val t = ((nearest.x - from.x) * dx + (nearest.y - from.y) * dy) / lengthSq
            candidates.add(Triple(p, distSq, t))
        }
        return candidates
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
    }

    /**
     * Whether a car parked at [start] has any opening move at all - mirrors the rule in
     * [GameEngine.legalMoves] that a player's first move has to cross the finish line
     * in [finishDirection]. Other cars are not considered: which slots are taken isn't
     * known until placement, and this is a property of the track.
     */
    private fun hasForwardFirstMove(
        start: Point,
        walls: List<Wall>,
        finishLine: Wall,
        finishDirection: Point,
        subdivision: Int,
    ): Boolean {
        val fineStart = start * subdivision
        for (dx in -1..1) for (dy in -1..1) {
            if (dx * finishDirection.x + dy * finishDirection.y <= 0) continue
            val fineTarget = (start + Point(dx, dy)) * subdivision
            if (!Geometry.segmentsIntersect(fineStart, fineTarget, finishLine.from, finishLine.to)) continue
            if (walls.any { Geometry.segmentsIntersect(fineStart, fineTarget, it.from, it.to) }) continue
            return true
        }
        return false
    }

    /**
     * Extends the rough line the user dragged from [from] to [to] in both directions until it
     * hits [outer] and [inner], and returns those crossing points instead - so a finish line
     * only needs to be drawn roughly across the corridor, at any angle, and its exact span gets
     * capped to the track automatically. Null if the drag doesn't clearly cross both boundaries
     * (e.g. it's degenerate, or runs parallel to them).
     */
    fun capFinishLineToBoundaries(from: Point, to: Point, outer: List<Point>, inner: List<Point>): Pair<Point, Point>? {
        if (outer.size < 3 || inner.size < 3) return null
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        val length = hypot(dx, dy)
        if (length == 0.0) return null
        val ux = dx / length
        val uy = dy / length

        val extentPoints = outer + inner + listOf(from, to)
        val span = hypot(
            (extentPoints.maxOf { it.x } - extentPoints.minOf { it.x }).toDouble(),
            (extentPoints.maxOf { it.y } - extentPoints.minOf { it.y }).toDouble(),
        )
        val extension = span * 2 + 10.0

        val midX = (from.x + to.x) / 2.0
        val midY = (from.y + to.y) / 2.0
        val mid = Point(midX.roundToInt(), midY.roundToInt())
        val posEnd = Point((midX + ux * extension).roundToInt(), (midY + uy * extension).roundToInt())
        val negEnd = Point((midX - ux * extension).roundToInt(), (midY - uy * extension).roundToInt())

        val posHit = firstCrossing(mid, posEnd, outer, inner) ?: return null
        val negHit = firstCrossing(mid, negEnd, outer, inner) ?: return null
        if (posHit == negHit) return null
        return negHit to posHit
    }

    /**
     * Finds a finish line automatically, for when a user's own drag doesn't produce a
     * valid one. Tries each outer-boundary edge in turn: probes for its inward normal,
     * then extends along it using the same crossing logic as [capFinishLineToBoundaries]
     * (seeded from the probe instead of a user drag) to get a corridor-spanning segment.
     * Keeps the first candidate that's long enough and has at least 2 valid start
     * positions in one of its two perpendicular directions. Returns null only if no outer
     * edge works - shouldn't happen on a draft that already passed
     * [TrackValidator.validateBoundaries].
     */
    fun autoPlaceFinishLine(outer: List<Point>, inner: List<Point>, subdivision: Int): Triple<Point, Point, Point>? {
        if (outer.size < 3 || inner.size < 3) return null
        val probeStep = subdivision.toDouble()

        for ((a, b) in Geometry.closedEdges(outer)) {
            val midX = (a.x + b.x) / 2.0
            val midY = (a.y + b.y) / 2.0
            val edgeLength = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            if (edgeLength == 0.0) continue
            val nx = -(b.y - a.y) / edgeLength
            val ny = (b.x - a.x) / edgeLength

            val (dx, dy) = listOf(nx to ny, -nx to -ny).firstOrNull { (candX, candY) ->
                val probe = Point((midX + candX * probeStep).roundToInt(), (midY + candY * probeStep).roundToInt())
                Geometry.pointInPolygon(probe, outer) && !Geometry.pointInPolygon(probe, inner)
            } ?: continue

            val seedFrom = Point((midX + dx * probeStep).roundToInt(), (midY + dy * probeStep).roundToInt())
            val seedTo = Point((midX + dx * probeStep * 2).roundToInt(), (midY + dy * probeStep * 2).roundToInt())
            val (from, to) = capFinishLineToBoundaries(seedFrom, seedTo, outer, inner) ?: continue
            val length = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
            if (length < TrackValidator.MIN_FINISH_LINE_LENGTH * subdivision) continue

            val lineDx = to.x - from.x
            val lineDy = to.y - from.y
            val walls = wallsFor(outer, inner, emptyList())
            val direction = listOf(Point(-lineDy, lineDx), Point(lineDy, -lineDx))
                .firstOrNull { candidate ->
                    startGrid(walls, Wall(from, to), candidate, subdivision).positions.size >= 2
                }
                ?: continue

            return Triple(from, to, direction)
        }
        return null
    }

    /** The first point where segment [from]-[to] crosses either polygon's edges, closest to [from]. */
    private fun firstCrossing(from: Point, to: Point, outer: List<Point>, inner: List<Point>): Point? {
        val edges = Geometry.closedEdges(outer) + Geometry.closedEdges(inner)
        var best: Point? = null
        var bestDistSq = Double.MAX_VALUE
        for ((c, d) in edges) {
            val hit = Geometry.segmentIntersectionPoint(from, to, c, d) ?: continue
            val ddx = (hit.x - from.x).toDouble()
            val ddy = (hit.y - from.y).toDouble()
            val distSq = ddx * ddx + ddy * ddy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = hit
            }
        }
        return best
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private fun closedWalls(points: List<Point>): List<Wall> {
        val open = if (points.size > 2 && points.first() == points.last()) points.dropLast(1) else points
        return open.indices.map { i -> Wall(open[i], open[(i + 1) % open.size]) }
    }

    private fun openWalls(points: List<Point>): List<Wall> =
        points.zipWithNext { a, b -> Wall(a, b) }
}

/**
 * The same track raced the other way round.
 *
 * Flipping [Track.finishDirection] alone is not enough: it moves the start line's
 * "starting side" to the opposite side, so the previously computed [Track.startPositions]
 * end up past the line, where a car's forced opening crossing is impossible and the engine
 * crashes it on the spot. The start box has to be rebuilt for the new direction, which is
 * what this does.
 */
fun Track.reversed(): Track {
    val flipped = finishDirection * -1
    val grid = TrackBuilder.startGrid(walls, finishLine, flipped, subdivision)
    return copy(
        finishDirection = flipped,
        startPositions = grid.positions,
        startZoneDepth = grid.depth,
    )
}
