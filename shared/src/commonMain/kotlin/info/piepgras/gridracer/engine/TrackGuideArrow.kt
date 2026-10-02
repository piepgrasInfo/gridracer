package info.piepgras.gridracer.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The "this way round" arrow a track thumbnail is stamped with: a short run of the
 * racing line, starting on the finish line and following the corridor forward.
 *
 * Drawn from the track rather than laid over it, because an arrow floating across the
 * middle of a thumbnail says which way is forward but not *where* forward goes - and on
 * a track whose corridor doubles back it can end up pointing at a wall.
 *
 * The corridor is found by ray-casting outward from the middle of the track, so it needs
 * nothing but [Track.walls] and works for hand-authored built-in tracks that have no
 * boundary polygons to consult.
 */
object TrackGuideArrow {

    /** How much of one lap the arrow covers. */
    const val DEFAULT_LAP_FRACTION: Double = 0.05

    /** Never stretch past this much of a lap, however wide the corridor is. */
    const val MAX_LAP_FRACTION: Double = 0.15

    /**
     * How many corridor-widths long the arrow should be at minimum. On a short, wide track
     * 5% of a lap is less than the corridor's own width, and an arrow shorter than it is
     * wide reads as a smudge rather than as a direction, so the run is stretched (never past
     * [MAX_LAP_FRACTION]) until it has an arrow's proportions.
     */
    private const val MIN_LENGTH_IN_WIDTHS = 3.0

    /** Enough to look like a curve at thumbnail size without over-sampling the ray casts. */
    private const val SAMPLES = 16

    private const val TWO_PI = 2.0 * kotlin.math.PI

    /** Ray hits closer together than this are the same crossing seen twice (a shared vertex). */
    private const val HIT_EPSILON = 1e-6

    /**
     * [centreLine] runs forward along the middle of the corridor from the finish line, in
     * fine-grid coordinates; [halfWidthFine] is the narrowest half-width of the corridor
     * along it, which is how wide the arrow may be drawn without spilling over a wall.
     *
     * @property centreLine The arrow's path along the corridor, in fine-grid coordinates.
     * @property halfWidthFine The narrowest corridor half-width along it, in fine-grid units.
     */
    data class GuideArrow(val centreLine: List<Point>, val halfWidthFine: Double)

    /**
     * Null when the track's geometry doesn't yield a usable line - a degenerate finish
     * line, or a corridor the radial scan can't follow. Callers draw nothing rather than
     * substituting something misleading.
     */
    fun forTrack(track: Track, lapFraction: Double = DEFAULT_LAP_FRACTION): GuideArrow? {
        val first = trace(track, lapFraction) ?: return null
        val width = first.halfWidthFine * 2
        val needed = width * MIN_LENGTH_IN_WIDTHS
        val length = lengthOf(first)
        if (length <= 0.0 || length >= needed) return first
        val stretched = (lapFraction * needed / length).coerceAtMost(MAX_LAP_FRACTION)
        if (stretched <= lapFraction) return first
        return trace(track, stretched) ?: first
    }

    private fun lengthOf(arrow: GuideArrow): Double =
        arrow.centreLine.zipWithNext { a, b -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }.sum()

    private fun trace(track: Track, lapFraction: Double): GuideArrow? {
        if (track.walls.isEmpty()) return null
        val (cx, cy) = ringCentre(track) ?: return null

        val startX = (track.finishLine.from.x + track.finishLine.to.x) / 2.0
        val startY = (track.finishLine.from.y + track.finishLine.to.y) / 2.0
        val startRadius = hypot(startX - cx, startY - cy)
        if (startRadius <= 0.0) return null

        // Which way round the ring counts as forward: the sign of d(angle)/dt for a car
        // travelling in finishDirection, i.e. the cross product of the finish line's offset
        // from the centre with that direction. Reversing the track flips it.
        val cross = (startX - cx) * track.finishDirection.y - (startY - cy) * track.finishDirection.x
        val sweep = lapFraction * TWO_PI * (if (cross >= 0) 1.0 else -1.0)

        // Parity along a ray flips at every wall it crosses, so where the ray starts decides
        // which intervals are corridor. Normally the centre sits on the infield island.
        val centreInside = Geometry.insideWalls(Point(cx.roundToInt(), cy.roundToInt()), track.walls)

        val startAngle = atan2(startY - cy, startX - cx)
        val points = mutableListOf<Point>()
        var halfWidth = Double.MAX_VALUE
        var radius = startRadius

        for (i in 0..SAMPLES) {
            val angle = startAngle + sweep * i / SAMPLES
            val span = corridorSpanAt(cx, cy, angle, track.walls, centreInside, radius)
                ?: return if (points.size >= 2) GuideArrow(points, usableHalfWidth(halfWidth)) else null
            radius = (span.first + span.second) / 2.0
            halfWidth = minOf(halfWidth, (span.second - span.first) / 2.0)
            points.add(Point((cx + radius * cos(angle)).roundToInt(), (cy + radius * sin(angle)).roundToInt()))
        }
        if (points.size < 2 || halfWidth == Double.MAX_VALUE) return null
        return GuideArrow(points, usableHalfWidth(halfWidth))
    }

    /**
     * A radial half-width overstates how much room there really is where the corridor runs
     * at an angle to the ray, so the arrow only claims part of it.
     */
    private fun usableHalfWidth(radialHalfWidth: Double): Double = radialHalfWidth * 0.6

    /** The centre of the wall geometry - close enough to the infield to cast rays from. */
    private fun ringCentre(track: Track): Pair<Double, Double>? {
        val xs = track.walls.flatMap { listOf(it.from.x, it.to.x) }
        val ys = track.walls.flatMap { listOf(it.from.y, it.to.y) }
        if (xs.isEmpty()) return null
        return (xs.min() + xs.max()) / 2.0 to (ys.min() + ys.max()) / 2.0
    }

    /**
     * The corridor interval a ray from ([cx], [cy]) at [angle] passes through, as distances
     * along the ray, choosing the one nearest [preferredRadius] so the line stays on the same
     * lap of a corridor that wraps back over itself.
     */
    private fun corridorSpanAt(
        cx: Double,
        cy: Double,
        angle: Double,
        walls: List<Wall>,
        centreInside: Boolean,
        preferredRadius: Double,
    ): Pair<Double, Double>? {
        val dx = cos(angle)
        val dy = sin(angle)
        val hits = mutableListOf<Double>()
        for (wall in walls) {
            rayHit(cx, cy, dx, dy, wall)?.let(hits::add)
        }
        if (hits.isEmpty()) return null
        hits.sort()

        // Intervals alternate in/out; which one the first is depends on where the ray started.
        var best: Pair<Double, Double>? = null
        var bestDistance = Double.MAX_VALUE
        var inside = centreInside
        var previous = 0.0
        for (hit in hits) {
            if (hit - previous > HIT_EPSILON) {
                if (inside) {
                    val distance = distanceTo(previous, hit, preferredRadius)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = previous to hit
                    }
                }
                inside = !inside
                previous = hit
            }
        }
        return best
    }

    /** 0 inside the interval, otherwise how far outside it [value] lies. */
    private fun distanceTo(low: Double, high: Double, value: Double): Double = when {
        value < low -> low - value
        value > high -> value - high
        else -> 0.0
    }

    /** Distance from ([ox], [oy]) along unit ([dx], [dy]) to [wall], or null if it misses. */
    private fun rayHit(ox: Double, oy: Double, dx: Double, dy: Double, wall: Wall): Double? {
        val ex = (wall.to.x - wall.from.x).toDouble()
        val ey = (wall.to.y - wall.from.y).toDouble()
        val denominator = dx * ey - dy * ex
        if (abs(denominator) < 1e-12) return null // parallel
        val qx = wall.from.x - ox
        val qy = wall.from.y - oy
        val t = (qx * ey - qy * ex) / denominator
        val u = (qx * dy - qy * dx) / denominator
        if (t < 0.0 || u < 0.0 || u > 1.0) return null
        return t
    }
}
