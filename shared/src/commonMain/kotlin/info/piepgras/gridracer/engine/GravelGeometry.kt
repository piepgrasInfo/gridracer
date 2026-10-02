package info.piepgras.gridracer.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The gravel band: every fine-grid point outside a track's outer boundary that lies within two
 * coarse grid units of it. Beyond it is the barrier.
 *
 * Kept as a mask over the fine lattice rather than as a polygon, because an offset polygon of a
 * hand-drawn boundary is a hard thing to build robustly, while "is this point within distance d of
 * the boundary" is a one-line integer test. Every membership decision is exact integer arithmetic,
 * so the server and every app version that has this class classify every point identically.
 *
 * Two things are carved out of the band, both so it cannot be used to skip part of a lap:
 * - **Seams** where two stretches of the boundary face each other across a narrow notch (a hairpin,
 *   a U-shaped track). There the two stretches' bands meet, and without a seam a car could cross
 *   from one to the other through the gravel.
 * - **Stubs** continuing the finish line outward through the band, so a car cannot drive round the
 *   end of the line instead of over it.
 *
 * Both are marked [BEYOND] on *both* sides of the cut, which is what stops an 8-connected walk
 * ([walkClear]) from slipping through diagonally.
 */
internal class GravelGeometry private constructor(
    private val originX: Int,
    private val originY: Int,
    private val width: Int,
    private val height: Int,
    private val mask: ByteArray,
    private val subdivision: Int,
    /** Fine points cut out of the band by a seam or a finish-line stub, for drawing. */
    val barrierMarks: List<Point>,
) {

    /** The class of fine-grid point ([x], [y]): [INSIDE], [GRAVEL] or [BEYOND]. */
    fun fineClass(x: Int, y: Int): Byte {
        val ix = x - originX
        val iy = y - originY
        if (ix < 0 || iy < 0 || ix >= width || iy >= height) return BEYOND
        return mask[iy * width + ix]
    }

    /** The class of coarse grid point [p]. */
    fun classOf(p: Point): Byte = fineClass(p.x * subdivision, p.y * subdivision)

    /**
     * Whether the fine-grid segment [from]-[to] stays out of [BEYOND] all the way.
     *
     * The segment is walked one fine unit at a time along its longer axis, rounding the other, so
     * consecutive samples are 8-neighbours; the cut-outs are marked on both sides, so no step can
     * cross one. Accurate to half a fine unit - a tenth of a coarse unit at the usual subdivision.
     */
    fun walkClear(from: Point, to: Point): Boolean = lastClearBefore(from, to) == null

    /**
     * The last sample of the walk from [from] towards [to] before it enters [BEYOND], or null if it
     * never does. When the very first sample is already [BEYOND], that sample is returned.
     */
    fun lastClearBefore(from: Point, to: Point): Point? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val steps = max(abs(dx), abs(dy))
        var previous = from
        for (i in 0..steps) {
            val x = if (steps == 0) from.x else from.x + roundedFraction(i * dx, steps)
            val y = if (steps == 0) from.y else from.y + roundedFraction(i * dy, steps)
            if (fineClass(x, y) == BEYOND) return previous
            previous = Point(x, y)
        }
        return null
    }

    companion object {
        /** Strictly inside the outer boundary: the track. */
        const val INSIDE: Byte = 0

        /** On the outer boundary or in the band outside it. */
        const val GRAVEL: Byte = 1

        /** Past the barrier, or cut out of the band. */
        const val BEYOND: Byte = 2

        /** Width of the band in coarse grid units. */
        const val BAND_WIDTH_COARSE = 2

        /**
         * The largest mask built, in fine lattice points (one byte each, plus eight bytes of scratch
         * while building). Far above any track the editor can make; a larger track sent by a client
         * simply has no gravel band, and the gravel trap cannot be switched on for it.
         */
        const val MAX_POINTS = 1_500_000

        /**
         * How far apart along the boundary two neighbouring band points' nearest boundary points
         * may be, in multiples of the band width, before they count as belonging to different
         * stretches of the boundary and a seam is cut between them.
         */
        private const val SEAM_ARC_FACTOR = 4

        /** `round(numerator / denominator)` for a positive denominator, halves rounding up. */
        fun roundedFraction(numerator: Int, denominator: Int): Int =
            (2 * numerator + denominator).floorDiv(2 * denominator)

        /**
         * Builds the band around [loop], or returns null when it would exceed [MAX_POINTS].
         *
         * @param loop The outer boundary, fine grid, as a closed polygon whose last vertex joins the
         *     first.
         * @param subdivision Fine units per coarse unit.
         * @param finishLine The finish line, fine grid; any end of it on the boundary gets a stub.
         */
        fun build(loop: List<Point>, subdivision: Int, finishLine: Wall): GravelGeometry? {
            if (loop.size < 3) return null
            val band = BAND_WIDTH_COARSE * subdivision
            val margin = band + 2
            val minX = loop.minOf { it.x } - margin
            val minY = loop.minOf { it.y } - margin
            val maxX = loop.maxOf { it.x } + margin
            val maxY = loop.maxOf { it.y } + margin
            val width = maxX - minX + 1
            val height = maxY - minY + 1
            if (width.toLong() * height > MAX_POINTS) return null

            val mask = ByteArray(width * height) { BEYOND }
            markInside(loop, minX, minY, width, height, mask)

            // Band: every point within `band` of an edge that is not strictly inside. The distance
            // test is exact; the nearest edge and the arc position of the nearest point on it are
            // remembered (approximately - they only decide where seams go) for the seam pass.
            val bandSq = band.toLong() * band
            val nearestDist = FloatArray(width * height) { Float.MAX_VALUE }
            val nearestArc = FloatArray(width * height)
            val edgeStartArc = FloatArray(loop.size)
            var perimeter = 0.0
            for (e in loop.indices) {
                edgeStartArc[e] = perimeter.toFloat()
                val a = loop[e]
                val b = loop[(e + 1) % loop.size]
                perimeter += sqrt(((b.x - a.x).toDouble() * (b.x - a.x) + (b.y - a.y).toDouble() * (b.y - a.y)))
            }
            for (e in loop.indices) {
                val a = loop[e]
                val b = loop[(e + 1) % loop.size]
                val abx = (b.x - a.x).toLong()
                val aby = (b.y - a.y).toLong()
                val lengthSq = abx * abx + aby * aby
                val length = sqrt(lengthSq.toDouble())
                val x0 = max(min(a.x, b.x) - band, minX)
                val x1 = min(max(a.x, b.x) + band, maxX)
                val y0 = max(min(a.y, b.y) - band, minY)
                val y1 = min(max(a.y, b.y) + band, maxY)
                for (y in y0..y1) {
                    for (x in x0..x1) {
                        val apx = (x - a.x).toLong()
                        val apy = (y - a.y).toLong()
                        val dot = apx * abx + apy * aby
                        // Squared distance times lengthSq, so the comparison stays in integers.
                        val scaledDistSq: Long
                        val t: Double
                        if (lengthSq == 0L || dot <= 0L) {
                            scaledDistSq = (apx * apx + apy * apy) * max(lengthSq, 1L)
                            t = 0.0
                        } else if (dot >= lengthSq) {
                            val bpx = (x - b.x).toLong()
                            val bpy = (y - b.y).toLong()
                            scaledDistSq = (bpx * bpx + bpy * bpy) * lengthSq
                            t = 1.0
                        } else {
                            scaledDistSq = (apx * apx + apy * apy) * lengthSq - dot * dot
                            t = dot.toDouble() / lengthSq
                        }
                        if (scaledDistSq > bandSq * max(lengthSq, 1L)) continue
                        val i = (y - minY) * width + (x - minX)
                        val onBoundary = scaledDistSq == 0L
                        if (!onBoundary && mask[i] == INSIDE) continue
                        mask[i] = GRAVEL
                        val dist = (scaledDistSq.toDouble() / max(lengthSq, 1L)).toFloat()
                        if (dist < nearestDist[i]) {
                            nearestDist[i] = dist
                            nearestArc[i] = (edgeStartArc[e] + t * length).toFloat()
                        }
                    }
                }
            }

            val cut = BooleanArray(width * height)
            cutSeams(mask, nearestArc, width, height, perimeter.toFloat(), (SEAM_ARC_FACTOR * band).toFloat(), cut)
            cutFinishStubs(loop, finishLine, subdivision, band + subdivision, mask, minX, minY, width, height, cut)
            val marks = ArrayList<Point>()
            for (i in cut.indices) {
                if (!cut[i]) continue
                mask[i] = BEYOND
                marks += Point(minX + i % width, minY + i / width)
            }

            return GravelGeometry(
                originX = minX,
                originY = minY,
                width = width,
                height = height,
                mask = mask,
                subdivision = subdivision,
                barrierMarks = marks,
            )
        }

        /**
         * Marks every lattice point strictly inside [loop] as [INSIDE] with an even-odd scanline.
         * Crossings are kept as doubles: a lattice point is never closer to a crossing than
         * 1 / (edge height) unless it is exactly on the edge, and points on an edge are reclassified
         * as gravel by the band pass anyway.
         */
        private fun markInside(loop: List<Point>, minX: Int, minY: Int, width: Int, height: Int, mask: ByteArray) {
            val crossings = DoubleArray(loop.size)
            for (row in 0 until height) {
                val y = minY + row
                var count = 0
                for (e in loop.indices) {
                    val a = loop[e]
                    val b = loop[(e + 1) % loop.size]
                    if ((a.y > y) != (b.y > y)) {
                        crossings[count++] = a.x + (y - a.y).toDouble() * (b.x - a.x) / (b.y - a.y)
                    }
                }
                if (count < 2) continue
                crossings.sort(0, count)
                var k = 0
                while (k + 1 < count) {
                    val from = kotlin.math.ceil(crossings[k]).toInt()
                    val until = crossings[k + 1]
                    var x = from
                    while (x < until) {
                        val ix = x - minX
                        if (ix in 0 until width) mask[row * width + ix] = INSIDE
                        x++
                    }
                    k += 2
                }
            }
        }

        /**
         * Cuts the band where two of its points are neighbours but their nearest boundary points are
         * far apart along the boundary - the two sides of a notch. Both points of every such pair
         * are cut, so the cut is closed to diagonal steps.
         */
        private fun cutSeams(
            mask: ByteArray,
            arc: FloatArray,
            width: Int,
            height: Int,
            perimeter: Float,
            limit: Float,
            cut: BooleanArray,
        ) {
            val offsets = arrayOf(1 to 0, 0 to 1, 1 to 1, -1 to 1)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    if (mask[i] != GRAVEL) continue
                    for ((ox, oy) in offsets) {
                        val nx = x + ox
                        val ny = y + oy
                        if (nx < 0 || nx >= width || ny >= height) continue
                        val j = ny * width + nx
                        if (mask[j] != GRAVEL) continue
                        val direct = abs(arc[i] - arc[j])
                        val apart = min(direct, perimeter - direct)
                        if (apart > limit) {
                            cut[i] = true
                            cut[j] = true
                        }
                    }
                }
            }
        }

        /**
         * Continues the finish line outward from each of its ends that lies on the boundary (within
         * one coarse unit of it), as a 4-connected line of cut points [length] fine units long -
         * longer than the band is wide, so it reaches the barrier.
         */
        private fun cutFinishStubs(
            loop: List<Point>,
            finishLine: Wall,
            subdivision: Int,
            length: Int,
            mask: ByteArray,
            minX: Int,
            minY: Int,
            width: Int,
            height: Int,
            cut: BooleanArray,
        ) {
            val ends = listOf(finishLine.from to finishLine.to, finishLine.to to finishLine.from)
            for ((end, other) in ends) {
                if (end == other) continue
                val reach = subdivision.toDouble() * subdivision
                val nearBoundary = loop.indices.any { e ->
                    Geometry.distancePointToSegmentSq(end, loop[e], loop[(e + 1) % loop.size]) <= reach
                }
                if (!nearBoundary) continue
                val dirX = (end.x - other.x).toDouble()
                val dirY = (end.y - other.y).toDouble()
                val norm = sqrt(dirX * dirX + dirY * dirY)
                val target = Point(
                    end.x + kotlin.math.round(dirX / norm * length).toInt(),
                    end.y + kotlin.math.round(dirY / norm * length).toInt(),
                )
                forEachFourConnected(end, target) { x, y ->
                    val ix = x - minX
                    val iy = y - minY
                    if (ix in 0 until width && iy in 0 until height) {
                        val i = iy * width + ix
                        if (mask[i] == GRAVEL) cut[i] = true
                    }
                }
            }
        }

        /** Visits a 4-connected digital line from [from] to [to]: every diagonal step is split in two. */
        private inline fun forEachFourConnected(from: Point, to: Point, visit: (Int, Int) -> Unit) {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val steps = max(abs(dx), abs(dy))
            var px = from.x
            var py = from.y
            visit(px, py)
            for (i in 1..steps) {
                val x = from.x + roundedFraction(i * dx, steps)
                val y = from.y + roundedFraction(i * dy, steps)
                if (x != px && y != py) visit(x, py)
                visit(x, y)
                px = x
                py = y
            }
        }
    }
}
