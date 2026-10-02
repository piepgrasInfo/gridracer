package info.piepgras.gridracer.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What the engine works out about a [Track] once and then reuses: which walls form the outer
 * boundary, a bucket index that makes "does this move touch a wall" cheap, and the gravel band.
 *
 * None of it is stored or sent. A [Track] carries only its walls, and everything here follows
 * from them, so the server derives exactly what the app derives and has no client-supplied
 * geometry to trust. Reached through [Track.geometry], built on first use.
 *
 * Every query answers exactly what a scan over all walls with [Geometry.segmentsIntersect] would:
 * the index only skips walls whose bounding box cannot reach the move.
 */
internal class TrackGeometry(track: Track) {

    private val walls: List<Wall> = track.walls

    /** Indices into the wall list of the outer boundary's walls, or empty when no closed loop exists. */
    val outerWallIndices: IntRange

    /**
     * The outer boundary as a closed polygon on the fine grid, vertices in wall order, not repeated
     * at the end; null when the walls contain no closed loop (a track no editor produces, but one a
     * client could send).
     */
    val outerLoop: List<Point>?

    init {
        val loop = findOuterLoop(walls)
        outerWallIndices = loop ?: IntRange.EMPTY
        outerLoop = loop?.map { walls[it].from }
    }

    private val isOuter = BooleanArray(walls.size) { it in outerWallIndices }

    private val index = WallIndex(walls, cellSize = max(2, 2 * track.subdivision))

    /** Each hazard zone's bounding box on the fine grid, four ints per zone: min x, min y, max x, max y. */
    private val zoneBoxes = IntArray(track.modifiers.size * 4).also { boxes ->
        for ((i, zone) in track.modifiers.withIndex()) {
            val area = zone.area.ifEmpty { listOf(Point(0, 0)) }
            boxes[4 * i] = area.minOf { it.x }
            boxes[4 * i + 1] = area.minOf { it.y }
            boxes[4 * i + 2] = area.maxOf { it.x }
            boxes[4 * i + 3] = area.maxOf { it.y }
        }
    }

    /**
     * Whether the fine-grid segment [from]-[to] can touch hazard zone [i] at all: false when their
     * bounding boxes are apart, which settles almost every move without looking at the polygon.
     */
    fun mayTouchZone(i: Int, from: Point, to: Point): Boolean =
        max(from.x, to.x) >= zoneBoxes[4 * i] && min(from.x, to.x) <= zoneBoxes[4 * i + 2] &&
            max(from.y, to.y) >= zoneBoxes[4 * i + 1] && min(from.y, to.y) <= zoneBoxes[4 * i + 3]

    /**
     * The gravel band outside [outerLoop], or null when there is no loop or the band would be too
     * large to rasterise. Built on first use - only a race with the gravel trap switched on, and
     * the renderer, ever ask.
     */
    val gravel: GravelGeometry? by lazy {
        outerLoop?.let { GravelGeometry.build(it, track.subdivision, track.finishLine) }
    }

    /** Whether the fine-grid segment [from]-[to] touches any wall. */
    fun touchesAnyWall(from: Point, to: Point): Boolean = index.anyTouching(from, to) { true }

    /** Whether the fine-grid segment [from]-[to] touches a wall that is not part of the outer boundary. */
    fun touchesNonOuterWall(from: Point, to: Point): Boolean = index.anyTouching(from, to) { !isOuter[it] }

    /** Whether the fine-grid segment [from]-[to] touches a wall of the outer boundary. */
    fun touchesOuterWall(from: Point, to: Point): Boolean = index.anyTouching(from, to) { isOuter[it] }

    /**
     * The point nearest [from] where the fine-grid segment [from]-[to] meets a wall accepted by
     * [include], or null if it meets none - where a wreck is drawn.
     */
    fun nearestHit(from: Point, to: Point, include: (Int) -> Boolean): Point? {
        var nearest: Point? = null
        var nearestDistSq = Long.MAX_VALUE
        for ((i, wall) in walls.withIndex()) {
            if (!include(i)) continue
            val hit = Geometry.segmentIntersectionPoint(from, to, wall.from, wall.to) ?: continue
            val dx = (hit.x - from.x).toLong()
            val dy = (hit.y - from.y).toLong()
            val distSq = dx * dx + dy * dy
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearest = hit
            }
        }
        return nearest
    }

    /** Whether wall [i] belongs to the outer boundary. */
    fun isOuterWall(i: Int): Boolean = isOuter[i]

    companion object {
        /**
         * The outer boundary among [walls]: the closed loop of chained walls with the largest area.
         *
         * Every track in existence lists each boundary as a run of walls in which one wall ends
         * where the next begins - [TrackBuilder.wallsFor] does, and so does every built-in track -
         * so the loops can be read off in order. The outer boundary encloses every other loop, so
         * it is the largest; open obstacle polylines never close and are skipped.
         *
         * @return The indices of the outer loop's walls, or null if the walls hold no closed loop.
         */
        fun findOuterLoop(walls: List<Wall>): IntRange? {
            var best: IntRange? = null
            var bestArea = 0L
            var i = 0
            while (i < walls.size) {
                var j = i
                // Stop as soon as the run closes, so a second loop that happens to start where the
                // first one ended is never glued onto it.
                while (walls[j].to != walls[i].from && j + 1 < walls.size && walls[j].to == walls[j + 1].from) j++
                val closed = walls[j].to == walls[i].from && j - i + 1 >= 3
                if (closed) {
                    val area = abs(twiceArea(walls, i, j))
                    if (area > bestArea) {
                        bestArea = area
                        best = i..j
                    }
                }
                i = j + 1
            }
            return best
        }

        private fun twiceArea(walls: List<Wall>, first: Int, last: Int): Long {
            var sum = 0L
            for (k in first..last) {
                val a = walls[k].from
                val b = walls[k].to
                sum += a.x.toLong() * b.y - b.x.toLong() * a.y
            }
            return sum
        }
    }
}

/**
 * Walls bucketed by the grid cells their bounding boxes cover, so a move is tested only against the
 * walls near it. Immutable after construction, so one instance serves every thread at once - the
 * server races many rooms on the same built-in track concurrently.
 */
private class WallIndex(private val walls: List<Wall>, private val cellSize: Int) {
    private val originX: Int
    private val originY: Int
    private val columns: Int
    private val rows: Int
    private val cellStart: IntArray
    private val cellWalls: IntArray
    private val wallCellX0 = IntArray(walls.size)
    private val wallCellY0 = IntArray(walls.size)

    init {
        if (walls.isEmpty()) {
            originX = 0; originY = 0; columns = 1; rows = 1
            cellStart = IntArray(2)
            cellWalls = IntArray(0)
        } else {
            originX = walls.minOf { min(it.from.x, it.to.x) }
            originY = walls.minOf { min(it.from.y, it.to.y) }
            val maxX = walls.maxOf { max(it.from.x, it.to.x) }
            val maxY = walls.maxOf { max(it.from.y, it.to.y) }
            columns = (maxX - originX) / cellSize + 1
            rows = (maxY - originY) / cellSize + 1
            val counts = IntArray(columns * rows)
            for ((i, wall) in walls.withIndex()) {
                val x0 = cellX(min(wall.from.x, wall.to.x))
                val y0 = cellY(min(wall.from.y, wall.to.y))
                wallCellX0[i] = x0
                wallCellY0[i] = y0
                forEachCell(x0, y0, cellX(max(wall.from.x, wall.to.x)), cellY(max(wall.from.y, wall.to.y))) { c -> counts[c]++ }
            }
            cellStart = IntArray(columns * rows + 1)
            for (c in counts.indices) cellStart[c + 1] = cellStart[c] + counts[c]
            cellWalls = IntArray(cellStart.last())
            val fill = cellStart.copyOf(columns * rows)
            for ((i, wall) in walls.withIndex()) {
                forEachCell(
                    wallCellX0[i],
                    wallCellY0[i],
                    cellX(max(wall.from.x, wall.to.x)),
                    cellY(max(wall.from.y, wall.to.y)),
                ) { c -> cellWalls[fill[c]++] = i }
            }
        }
    }

    private fun cellX(x: Int): Int = ((x - originX).floorDiv(cellSize)).coerceIn(0, columns - 1)
    private fun cellY(y: Int): Int = ((y - originY).floorDiv(cellSize)).coerceIn(0, rows - 1)

    private inline fun forEachCell(x0: Int, y0: Int, x1: Int, y1: Int, action: (Int) -> Unit) {
        for (cy in y0..y1) for (cx in x0..x1) action(cy * columns + cx)
    }

    /**
     * Whether segment [from]-[to] touches any wall accepted by [include].
     *
     * A wall registered in several of the cells the query covers is tested once: only from the
     * first cell where its cell range and the query's overlap. That keeps the index stateless -
     * there is no visited set to share between threads.
     */
    inline fun anyTouching(from: Point, to: Point, include: (Int) -> Boolean): Boolean {
        if (walls.isEmpty()) return false
        val qx0 = cellX(min(from.x, to.x))
        val qy0 = cellY(min(from.y, to.y))
        val qx1 = cellX(max(from.x, to.x))
        val qy1 = cellY(max(from.y, to.y))
        for (cy in qy0..qy1) {
            for (cx in qx0..qx1) {
                val c = cy * columns + cx
                for (k in cellStart[c] until cellStart[c + 1]) {
                    val i = cellWalls[k]
                    if (max(wallCellX0[i], qx0) != cx || max(wallCellY0[i], qy0) != cy) continue
                    if (!include(i)) continue
                    val wall = walls[i]
                    if (Geometry.segmentsIntersect(from, to, wall.from, wall.to)) return true
                }
            }
        }
        return false
    }
}
