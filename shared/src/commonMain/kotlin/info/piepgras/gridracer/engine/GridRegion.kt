package info.piepgras.gridracer.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A filled area on the fine grid, stored as a bitmap of unit cells rather than as a
 * polygon. Cell `(x, y)` covers the square `[x, x+1] x [y, y+1]`, so a region's
 * boundary always runs along lattice lines and every shape it can express is, by
 * construction, a set of simple non-self-crossing rings.
 *
 * That property is the whole point. Freehand editor strokes self-cross constantly
 * (a spiral crosses itself dozens of times), and repairing them by shuffling polygon
 * vertices is both fragile and, in the shipped `removeSelfCrossings`, quadratic per
 * pass over a quadratic number of passes - it froze the app (see
 * the 2026-08 crash triage). Rasterising instead bounds the work by grid area
 * (at most ~90k cells for the editor's 300x300 fine grid) no matter how pathological
 * the stroke is, and makes boolean merges between shapes trivially correct.
 *
 * Coordinates are the same fine-grid integers [TrackDraft] and [Wall] use.
 */
internal class GridRegion private constructor(
    private val minX: Int,
    private val minY: Int,
    private val width: Int,
    private val height: Int,
    private val cells: BooleanArray,
) {

    /** Whether no cell is filled. */
    val isEmpty: Boolean get() = cells.none { it }

    /** The number of filled cells - the region's area in fine-grid square units. */
    val cellCount: Int get() = cells.count { it }

    private fun index(x: Int, y: Int): Int = (y - minY) * width + (x - minX)

    private fun filled(x: Int, y: Int): Boolean =
        x >= minX && y >= minY && x < minX + width && y < minY + height && cells[index(x, y)]

    /** True if the unit cell containing [p]'s lower-left corner is filled. */
    fun containsCell(p: Point): Boolean = filled(p.x, p.y)

    /**
     * True if [p] - a lattice *point*, not a cell - touches this region, i.e. any of
     * the (up to) four cells meeting at it is filled. Used for "is this grid point
     * inside the track corridor" style questions, where a point sitting exactly on a
     * cell corner should still count.
     */
    fun touchesPoint(p: Point): Boolean =
        filled(p.x, p.y) || filled(p.x - 1, p.y) || filled(p.x, p.y - 1) || filled(p.x - 1, p.y - 1)

    /**
     * Every cell filled in this region or in [other].
     *
     * @return A new region; neither operand changes.
     */
    fun union(other: GridRegion): GridRegion = combine(other, Op.UNION)

    /**
     * Only the cells filled in both this region and [other].
     *
     * @return A new region; neither operand changes.
     */
    fun intersect(other: GridRegion): GridRegion = combine(other, Op.INTERSECT)

    /**
     * This region with every cell of [other] removed.
     *
     * @return A new region; neither operand changes.
     */
    fun subtract(other: GridRegion): GridRegion = combine(other, Op.SUBTRACT)

    /** True if both regions cover exactly the same cells (frames may differ). */
    fun coversSameAs(other: GridRegion): Boolean {
        if (cellCount != other.cellCount) return false
        for (y in minY until minY + height) {
            for (x in minX until minX + width) {
                if (filled(x, y) != other.filled(x, y)) return false
            }
        }
        return true
    }

    private enum class Op { UNION, INTERSECT, SUBTRACT }

    private fun combine(other: GridRegion, op: Op): GridRegion {
        val lowX: Int
        val lowY: Int
        val highX: Int
        val highY: Int
        when (op) {
            // A union can grow; an intersection can only shrink; a difference stays
            // inside the left operand. Sizing the frame accordingly keeps these cheap.
            Op.UNION -> {
                lowX = min(minX, other.minX); lowY = min(minY, other.minY)
                highX = max(minX + width, other.minX + other.width)
                highY = max(minY + height, other.minY + other.height)
            }
            Op.INTERSECT -> {
                lowX = max(minX, other.minX); lowY = max(minY, other.minY)
                highX = min(minX + width, other.minX + other.width)
                highY = min(minY + height, other.minY + other.height)
            }
            Op.SUBTRACT -> {
                lowX = minX; lowY = minY; highX = minX + width; highY = minY + height
            }
        }
        val w = highX - lowX
        val h = highY - lowY
        if (w <= 0 || h <= 0) return empty()
        val out = BooleanArray(w * h)
        for (y in lowY until highY) {
            for (x in lowX until highX) {
                val a = filled(x, y)
                val b = other.filled(x, y)
                out[(y - lowY) * w + (x - lowX)] = when (op) {
                    Op.UNION -> a || b
                    Op.INTERSECT -> a && b
                    Op.SUBTRACT -> a && !b
                }
            }
        }
        return GridRegion(lowX, lowY, w, h, out)
    }

    /**
     * The region's boundary as closed rings of lattice points, largest first. Outer
     * rings have positive [TrackEditorGeometry.signedArea]; holes are negative. Each
     * ring is an open list (the closing edge from last back to first is implicit),
     * thinned by [simplifyEpsilon] fine units so a rasterised freehand stroke doesn't
     * turn into thousands of one-unit staircase steps.
     */
    fun contours(simplifyEpsilon: Double = DEFAULT_SIMPLIFY_EPSILON): List<List<Point>> {
        val edges = boundaryEdges()
        if (edges.isEmpty()) return emptyList()

        val outgoing = HashMap<Point, MutableList<Edge>>()
        for (edge in edges) outgoing.getOrPut(edge.from) { mutableListOf() }.add(edge)

        val used = HashSet<Edge>()
        val rings = mutableListOf<List<Point>>()
        for (start in edges) {
            if (start in used) continue
            val ring = mutableListOf<Point>()
            var current = start
            while (true) {
                used.add(current)
                ring.add(current.from)
                val candidates = outgoing[current.to]?.filter { it !in used } ?: emptyList()
                if (candidates.isEmpty()) break
                current = pickNext(current, candidates)
            }
            val cleaned = finishRing(ring, simplifyEpsilon)
            if (cleaned != null) rings.add(cleaned)
        }
        return rings.sortedByDescending { abs(TrackEditorGeometry.signedArea(it)) }
    }

    /** [contours] filtered to filled areas, dropping holes. */
    fun filledContours(simplifyEpsilon: Double = DEFAULT_SIMPLIFY_EPSILON): List<List<Point>> =
        contours(simplifyEpsilon).filter { TrackEditorGeometry.signedArea(it) > 0.0 }

    private data class Edge(val from: Point, val to: Point)

    /**
     * Every unit edge that separates a filled cell from an unfilled one, directed so
     * the filled cell is on its right (screen coordinates, y pointing down). That
     * consistent handedness is what lets [pickNext] walk each component separately.
     */
    private fun boundaryEdges(): List<Edge> {
        val result = mutableListOf<Edge>()
        for (y in minY until minY + height) {
            for (x in minX until minX + width) {
                if (!filled(x, y)) continue
                if (!filled(x, y - 1)) result.add(Edge(Point(x, y), Point(x + 1, y)))
                if (!filled(x + 1, y)) result.add(Edge(Point(x + 1, y), Point(x + 1, y + 1)))
                if (!filled(x, y + 1)) result.add(Edge(Point(x + 1, y + 1), Point(x, y + 1)))
                if (!filled(x - 1, y)) result.add(Edge(Point(x, y + 1), Point(x, y)))
            }
        }
        return result
    }

    /**
     * At a vertex where two cells meet only diagonally there are two ways to continue.
     * Always taking the sharpest right turn hugs the component we're already tracing,
     * so corner-touching blobs come back as two simple rings instead of one ring that
     * crosses itself at the pinch.
     */
    private fun pickNext(current: Edge, candidates: List<Edge>): Edge {
        val dx = current.to.x - current.from.x
        val dy = current.to.y - current.from.y
        return candidates.minByOrNull { candidate ->
            val cx = candidate.to.x - candidate.from.x
            val cy = candidate.to.y - candidate.from.y
            when {
                cx == -dy && cy == dx -> 0 // right
                cx == dx && cy == dy -> 1 // straight
                cx == dy && cy == -dx -> 2 // left
                else -> 3 // reversal
            }
        } ?: candidates.first()
    }

    companion object {
        /** In fine-grid units. One unit is the authoring resolution, so this only removes staircase noise. */
        const val DEFAULT_SIMPLIFY_EPSILON: Double = 1.0

        /**
         * How many times to retry thinning at half the tolerance when the thinned ring comes
         * back self-intersecting. Each retry keeps more vertices and costs more to verify, so
         * this gives up quickly and keeps the un-thinned ring instead.
         */
        private const val MAX_SIMPLIFY_ATTEMPTS: Int = 3

        /** A region with no cells, the identity for [union]. */
        fun empty(): GridRegion = GridRegion(0, 0, 0, 0, BooleanArray(0))

        /**
         * The area enclosed by the closed polygon [points] (even-odd rule), rasterised
         * by testing each candidate cell's centre. A self-crossing polygon is perfectly
         * acceptable input - that's the point - and its overlapping lobes cancel out
         * exactly as the even-odd rule says they should.
         */
        fun fromPolygon(points: List<Point>): GridRegion {
            if (points.size < 3) return empty()
            val minX = points.minOf { it.x }
            val minY = points.minOf { it.y }
            val width = points.maxOf { it.x } - minX
            val height = points.maxOf { it.y } - minY
            if (width <= 0 || height <= 0) return empty()
            val cells = BooleanArray(width * height)

            // Scanline fill: one pass per row, collecting where the polygon's edges cross
            // that row and filling the spans between alternate crossings. Testing every cell
            // centre against every edge instead would be O(cells x vertices), and a freehand
            // stroke has thousands of both - that product is seconds of work per stroke.
            val crossings = ArrayList<Double>()
            for (y in 0 until height) {
                val rowY = minY + y + 0.5
                crossings.clear()
                var j = points.size - 1
                for (i in points.indices) {
                    val a = points[j]
                    val b = points[i]
                    j = i
                    if ((a.y > rowY) != (b.y > rowY)) {
                        crossings.add((b.x - a.x).toDouble() * (rowY - a.y) / (b.y - a.y).toDouble() + a.x)
                    }
                }
                if (crossings.isEmpty()) continue
                crossings.sort()
                var k = 0
                while (k + 1 < crossings.size) {
                    val spanStart = crossings[k]
                    val spanEnd = crossings[k + 1]
                    k += 2
                    // Cell x covers [x, x+1], so its centre is at minX + x + 0.5.
                    var cx = ceil(spanStart - minX - 0.5).toInt().coerceAtLeast(0)
                    val last = (ceil(spanEnd - minX - 0.5).toInt() - 1).coerceAtMost(width - 1)
                    while (cx <= last) {
                        cells[y * width + cx] = true
                        cx++
                    }
                }
            }
            return GridRegion(minX, minY, width, height, cells)
        }

        /**
         * The union of several closed polygons, each rasterised with [fromPolygon].
         *
         * @param polygons Closed loops in fine-grid coordinates.
         * @return The cells inside at least one of them.
         */
        fun fromPolygons(polygons: List<List<Point>>): GridRegion =
            polygons.fold(empty()) { acc, polygon -> acc.union(fromPolygon(polygon)) }

        /**
         * The corridor of a track drawn as an outer loop with an inner island: the
         * area inside [outer] but outside [inner] and outside every entry in
         * [obstacles].
         */
        fun corridor(outer: List<Point>, inner: List<Point>, obstacles: List<List<Point>> = emptyList()): GridRegion =
            fromPolygon(outer).subtract(fromPolygon(inner)).subtract(fromPolygons(obstacles))

        /**
         * Turns one raw boundary walk into a usable simple polygon, or null if there
         * isn't one. Two things can go wrong and both are handled here:
         *
         * - the walk can pass through the same lattice point twice, where two parts of
         *   one 4-connected blob meet at a corner. That is a figure-eight, not a simple
         *   ring, so it is cut at the repeat and the largest lobe kept.
         * - Douglas-Peucker can push two nearly-touching arms of a tight spiral through
         *   each other. So the thinned result is checked and the tolerance halved until
         *   it comes back simple, falling back to the un-thinned ring, which is simple
         *   by construction once the repeats are gone.
         */
        private fun finishRing(rawRing: List<Point>, epsilon: Double): List<Point>? {
            val simpleLoop = largestSimpleLoop(rawRing) ?: return null
            val base = dropCollinear(simpleLoop)
            if (base.size < 3) return null
            var eps = epsilon
            repeat(MAX_SIMPLIFY_ATTEMPTS) {
                val candidate = douglasPeuckerClosed(base, eps)
                if (candidate.size >= 3 && isSimpleRing(candidate)) return candidate
                eps /= 2.0
            }
            // The un-thinned ring is simple by construction, so this always terminates with
            // a usable answer - just a more detailed one than the caller asked for.
            return base
        }

        private fun douglasPeuckerClosed(ring: List<Point>, epsilon: Double): List<Point> {
            if (ring.size < 4) return ring
            // Douglas-Peucker needs fixed endpoints; on a closed ring, split it at the
            // vertex furthest from the first one so neither half can collapse.
            val anchor = ring.first()
            val farIndex = ring.indices.maxByOrNull { i ->
                val dx = (ring[i].x - anchor.x).toDouble()
                val dy = (ring[i].y - anchor.y).toDouble()
                dx * dx + dy * dy
            } ?: return ring
            if (farIndex < 1 || farIndex > ring.size - 2) return ring
            val firstHalf = douglasPeucker(ring.subList(0, farIndex + 1), epsilon)
            val secondHalf = douglasPeucker(ring.subList(farIndex, ring.size) + anchor, epsilon)
            val merged = firstHalf + secondHalf.drop(1).dropLast(1)
            return if (merged.size >= 3) merged else ring
        }

        /**
         * Splits [ring] wherever it revisits a lattice point and returns the
         * largest-area piece, so the result never touches itself.
         */
        private fun largestSimpleLoop(ring: List<Point>): List<Point>? {
            val loops = mutableListOf<List<Point>>()
            val stack = mutableListOf<Point>()
            val seenAt = HashMap<Point, Int>()
            for (p in ring) {
                val previous = seenAt[p]
                if (previous == null) {
                    seenAt[p] = stack.size
                    stack.add(p)
                    continue
                }
                val loop = stack.subList(previous, stack.size).toList()
                if (loop.size >= 3) loops.add(loop)
                for (k in stack.size - 1 downTo previous) {
                    seenAt.remove(stack[k])
                    stack.removeAt(k)
                }
                seenAt[p] = stack.size
                stack.add(p)
            }
            if (stack.size >= 3) loops.add(stack.toList())
            return loops.maxByOrNull { abs(TrackEditorGeometry.signedArea(it)) }
        }

        private fun isSimpleRing(ring: List<Point>): Boolean {
            val n = ring.size
            if (n < 4) return true
            if (ring.toSet().size != n) return false
            for (i in 0 until n) {
                val a1 = ring[i]
                val a2 = ring[(i + 1) % n]
                for (j in i + 2 until n) {
                    if (i == 0 && j == n - 1) continue
                    if (Geometry.segmentsIntersect(a1, a2, ring[j], ring[(j + 1) % n])) return false
                }
            }
            return true
        }

        private fun dropCollinear(ring: List<Point>): List<Point> {
            val n = ring.size
            if (n < 3) return ring
            val result = mutableListOf<Point>()
            for (i in 0 until n) {
                val prev = ring[(i - 1 + n) % n]
                val curr = ring[i]
                val next = ring[(i + 1) % n]
                if (Geometry.orientation(prev, curr, next) != 0) result.add(curr)
            }
            return if (result.size >= 3) result else ring
        }

        private fun douglasPeucker(points: List<Point>, epsilon: Double): List<Point> {
            if (points.size < 3) return points
            val first = points.first()
            val last = points.last()
            var worstIndex = 0
            var worstDistSq = 0.0
            for (i in 1 until points.size - 1) {
                val distSq = Geometry.distancePointToSegmentSq(points[i], first, last)
                if (distSq > worstDistSq) {
                    worstDistSq = distSq
                    worstIndex = i
                }
            }
            if (worstDistSq <= epsilon * epsilon) return listOf(first, last)
            val left = douglasPeucker(points.subList(0, worstIndex + 1), epsilon)
            val right = douglasPeucker(points.subList(worstIndex, points.size), epsilon)
            return left + right.drop(1)
        }
    }
}
