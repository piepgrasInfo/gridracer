package info.piepgras.gridracer.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Grid-point geometry helpers shared by [GameEngine] (collision/finish-line detection)
 * and [TrackValidator] (custom-track shape validation).
 */
internal object Geometry {

    /**
     * The turn direction of the triangle [a], [b], [c]: the sign of the cross product of `b - a`
     * and `c - a`, computed in `Long` so large fine-grid coordinates cannot overflow.
     *
     * @return 1 for one rotational sense, -1 for the other, 0 when the three points are collinear.
     */
    fun orientation(a: Point, b: Point, c: Point): Int {
        val v = (b.x - a.x).toLong() * (c.y - a.y).toLong() - (b.y - a.y).toLong() * (c.x - a.x).toLong()
        return when {
            v > 0 -> 1
            v < 0 -> -1
            else -> 0
        }
    }

    /**
     * Whether [p] lies inside the bounding box of [a]-[b]. Only meaningful once the three points
     * are known to be collinear, which is how [segmentsIntersect] calls it.
     *
     * @return `true` when [p] is within the segment's x and y ranges, endpoints included.
     */
    fun onSegment(a: Point, b: Point, p: Point): Boolean =
        p.x in min(a.x, b.x)..max(a.x, b.x) && p.y in min(a.y, b.y)..max(a.y, b.y)

    /**
     * Whether segment [p1]-[p2] touches segment [p3]-[p4], counting a shared endpoint and a
     * collinear overlap as touching - a car grazing a wall's tip has hit it.
     *
     * @return `true` if the two closed segments have at least one point in common.
     */
    fun segmentsIntersect(p1: Point, p2: Point, p3: Point, p4: Point): Boolean {
        val o1 = orientation(p1, p2, p3)
        val o2 = orientation(p1, p2, p4)
        val o3 = orientation(p3, p4, p1)
        val o4 = orientation(p3, p4, p2)

        if (o1 != o2 && o3 != o4) return true

        if (o1 == 0 && onSegment(p1, p2, p3)) return true
        if (o2 == 0 && onSegment(p1, p2, p4)) return true
        if (o3 == 0 && onSegment(p3, p4, p1)) return true
        if (o4 == 0 && onSegment(p3, p4, p2)) return true

        return false
    }

    /**
     * Greatest common divisor by Euclid's algorithm.
     *
     * @return The gcd of [a] and [b], or 1 when both are zero, so a caller can always divide by it.
     */
    fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return if (x == 0) 1 else x
    }

    /** Lattice points strictly after [from] through [to] inclusive, at unit-fraction spacing. */
    fun latticePointsOn(from: Point, to: Point): List<Point> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        if (dx == 0 && dy == 0) return listOf(to)
        val steps = gcd(abs(dx), abs(dy))
        val stepX = dx / steps
        val stepY = dy / steps
        return (1..steps).map { Point(from.x + stepX * it, from.y + stepY * it) }
    }

    /** Squared distance from point (px,py) to the closest point on segment [a]-[b] (clamped projection). */
    fun distanceToSegmentSq(px: Double, py: Double, a: Point, b: Point): Double {
        val abx = (b.x - a.x).toDouble()
        val aby = (b.y - a.y).toDouble()
        val lengthSq = abx * abx + aby * aby
        if (lengthSq == 0.0) {
            val dx = px - a.x
            val dy = py - a.y
            return dx * dx + dy * dy
        }
        val apx = px - a.x
        val apy = py - a.y
        val t = ((apx * abx + apy * aby) / lengthSq).coerceIn(0.0, 1.0)
        val closestX = a.x + t * abx
        val closestY = a.y + t * aby
        val dx = px - closestX
        val dy = py - closestY
        return dx * dx + dy * dy
    }

    /** Squared distance from [p] to the closest point on segment [a]-[b] (clamped projection). */
    fun distancePointToSegmentSq(p: Point, a: Point, b: Point): Double =
        distanceToSegmentSq(p.x.toDouble(), p.y.toDouble(), a, b)

    /**
     * Euclidean distance from [p] to the closest point on segment [a]-[b].
     *
     * @return The distance in grid units; see [distancePointToSegmentSq] when only a comparison is
     * needed.
     */
    fun distancePointToSegment(p: Point, a: Point, b: Point): Double = sqrt(distancePointToSegmentSq(p, a, b))

    /** The point on segment [a]-[b] closest to [p] (clamped to the segment if the projection falls outside). */
    fun nearestPointOnSegment(p: Point, a: Point, b: Point): Point {
        val abx = (b.x - a.x).toDouble()
        val aby = (b.y - a.y).toDouble()
        val lengthSq = abx * abx + aby * aby
        if (lengthSq == 0.0) return a
        val apx = p.x - a.x
        val apy = p.y - a.y
        val t = ((apx * abx + apy * aby) / lengthSq).coerceIn(0.0, 1.0)
        return Point((a.x + t * abx).roundToInt(), (a.y + t * aby).roundToInt())
    }

    /** True if [p] lies inside the closed polygon [polygon] (edges between consecutive points, wrapping). Ray-casting, odd-even rule. */
    fun pointInPolygon(p: Point, polygon: List<Point>): Boolean = pointInPolygon(p.x.toDouble(), p.y.toDouble(), polygon)

    /** Double-coordinate variant of [pointInPolygon] for off-grid test points. */
    fun pointInPolygon(px: Double, py: Double, polygon: List<Point>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val intersects = ((pi.y > py) != (pj.y > py)) &&
                (px < (pj.x - pi.x).toDouble() * (py - pi.y) / (pj.y - pi.y).toDouble() + pi.x)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * True if [p] lies in the drivable part of a track described only by its [walls].
     *
     * The same even-odd ray cast as [pointInPolygon], but over a flat soup of wall
     * segments rather than one polygon - which is what a [Track] actually stores, and
     * all a built-in track ever had. Because every wall loop is closed, the parity
     * works out on its own: a point in the corridor crosses the outer loop an odd
     * number of times and each island (inner boundary, obstacle) an even number, so it
     * comes back odd, while a point inside an island or outside the track comes back
     * even.
     */
    fun insideWalls(p: Point, walls: List<Wall>): Boolean {
        val px = p.x.toDouble()
        val py = p.y.toDouble()
        var inside = false
        for (wall in walls) {
            val a = wall.from
            val b = wall.to
            // Half-open in y so a vertex shared by two walls is counted exactly once.
            if ((a.y > py) != (b.y > py)) {
                val crossingX = (b.x - a.x).toDouble() * (py - a.y) / (b.y - a.y).toDouble() + a.x
                if (px < crossingX) inside = !inside
            }
        }
        return inside
    }

    /** Consecutive edges of a closed polygon, wrapping last-to-first. */
    fun closedEdges(polygon: List<Point>): List<Pair<Point, Point>> =
        polygon.indices.map { i -> polygon[i] to polygon[(i + 1) % polygon.size] }

    /**
     * Where segments [p1]-[p2] and [p3]-[p4] actually cross, rounded to the nearest grid
     * point - null if they're parallel or don't cross within both segments' bounds. Unlike
     * [segmentsIntersect], this needs the real crossing point, not just a yes/no.
     */
    fun segmentIntersectionPoint(p1: Point, p2: Point, p3: Point, p4: Point): Point? {
        val x1 = p1.x.toDouble(); val y1 = p1.y.toDouble()
        val x2 = p2.x.toDouble(); val y2 = p2.y.toDouble()
        val x3 = p3.x.toDouble(); val y3 = p3.y.toDouble()
        val x4 = p4.x.toDouble(); val y4 = p4.y.toDouble()

        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denom) < 1e-9) return null

        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        val u = ((x1 - x3) * (y1 - y2) - (y1 - y3) * (x1 - x2)) / denom
        if (t < 0.0 || t > 1.0 || u < 0.0 || u > 1.0) return null

        return Point((x1 + t * (x2 - x1)).roundToInt(), (y1 + t * (y2 - y1)).roundToInt())
    }
}
