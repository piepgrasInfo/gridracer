package info.piepgras.gridracer.engine

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TrackEditorGeometryTest {

    @Test
    fun autoClose_removesTrailingDuplicateFirstPoint() {
        val closed = listOf(Point(0, 0), Point(10, 0), Point(10, 10), Point(0, 0))
        val open = TrackEditorGeometry.autoClose(closed)
        assertEquals(Point(0, 0), open.first())
        assertEquals(Point(10, 10), open.last())
        assertEquals(3, open.size)
    }

    @Test
    fun autoClose_keepsOpenStrokeAsIs() {
        val stroke = listOf(Point(0, 0), Point(10, 0), Point(10, 10))
        val open = TrackEditorGeometry.autoClose(stroke)
        assertEquals(stroke, open)
    }

    @Test
    fun simplifyPolygon_removesCollinearPoints() {
        val square = listOf(
            Point(0, 0), Point(5, 0), Point(10, 0),
            Point(10, 5), Point(10, 10),
            Point(0, 10), Point(0, 0),
        )
        val simplified = TrackEditorGeometry.simplifyPolygon(square)
        assertEquals(listOf(Point(0, 0), Point(10, 0), Point(10, 10), Point(0, 10), Point(0, 0)), simplified)
    }

    @Test
    fun repairShape_keepsASimplePolygonRecognisable() {
        val square = listOf(Point(0, 0), Point(40, 0), Point(40, 40), Point(0, 40))
        val repaired = TrackEditorGeometry.repairShape(square)
        assertNoSelfCrossings(repaired)
        assertBoundingBox(repaired, 0, 0, 40, 40)
    }

    @Test
    fun repairShape_uncrossesABowtie() {
        // A bowtie: A-B and C-D cross. Under the even-odd rule the two lobes are the
        // filled area, and the larger one survives.
        val bowtie = listOf(Point(0, 0), Point(40, 40), Point(40, 0), Point(0, 40))
        val repaired = TrackEditorGeometry.repairShape(bowtie)
        assertTrue(repaired.size >= 3, "expected a polygon, got ${repaired.size} points")
        assertNoSelfCrossings(repaired)
    }

    @Test
    fun repairShape_reducesASpiralToItsOuterTurn() {
        val spiral = listOf(
            Point(0, 0), Point(80, 0), Point(80, 80), Point(20, 80),
            Point(20, 20), Point(60, 20), Point(60, 60), Point(0, 60),
        )
        val repaired = TrackEditorGeometry.repairShape(spiral)
        assertTrue(repaired.size >= 3, "a spiral must repair to a usable shape, not nothing")
        assertNoSelfCrossings(repaired)
        // The repaired shape is a real area covering a good part of the stroke's extent,
        // not a sliver: this is the "as close to the original as possible" guarantee.
        val area = kotlin.math.abs(TrackEditorGeometry.signedArea(repaired))
        assertTrue(area > 1000.0, "expected a substantial area, got $area")
    }

    /**
     * The regression test for the shipped ANR (the 2026-08 crash triage): the old
     * vertex-shuffling repair was quadratic per pass over a quadratic number of passes,
     * so a long self-crossing freehand stroke froze the main thread for minutes. This is
     * a realistically sized stroke - a tight 6-turn spiral traced at fine-grid
     * resolution - and it has to come back promptly.
     */
    @Test
    fun repairShape_handlesALongFreehandSpiralQuickly() {
        // As the editor would actually deliver it: a 5-turn spiral across most of the
        // 300x300 fine grid, with every fine cell along the path visited (TrackEditorCanvas
        // interpolates between pointer samples), which is a few thousand points.
        val stroke = interpolate(spiral(turns = 5, fromRadius = 130.0, toRadius = 15.0, centre = Point(150, 150)))
        assertTrue(stroke.size > 1000, "test stroke is unrealistically short: ${stroke.size}")

        val mark = TimeSource.Monotonic.markNow()
        val repaired = TrackEditorGeometry.repairShape(stroke)
        val elapsed = mark.elapsedNow()

        assertTrue(repaired.size >= 3, "spiral repaired to nothing")
        assertNoSelfCrossings(repaired)
        // It used to hang the app for minutes, and that is what this guards: the budget is
        // deliberately far above the real figure so that it catches a return of the quadratic
        // behaviour and nothing else. A wall-clock assertion is the only one in this suite, and
        // it has to survive the first run after a clone - cold JIT, cold page cache, a loaded
        // machine - which is exactly when a contributor runs it. Measured on this hardware:
        // 0.07-0.19 s warm, 1.24 s cold under load average 20, which failed a 1 s budget on
        // 2026-09-25 for no reason anyone would want to debug. Minutes is the regression; five
        // seconds is still nowhere near it.
        assertTrue(elapsed < 5.seconds, "repair took $elapsed")
    }

    private fun spiral(turns: Int, fromRadius: Double, toRadius: Double, centre: Point): List<Point> {
        val samples = turns * 120
        return (0 until samples).map { i ->
            val t = i.toDouble() / samples
            val angle = t * turns * 2.0 * kotlin.math.PI
            val radius = fromRadius + (toRadius - fromRadius) * t
            Point(
                (centre.x + radius * cos(angle)).roundToInt(),
                (centre.y + radius * sin(angle)).roundToInt(),
            )
        }
    }

    /** Fills in every grid cell between consecutive samples, as TrackEditorCanvas does. */
    private fun interpolate(path: List<Point>): List<Point> {
        val result = mutableListOf<Point>()
        path.forEachIndexed { index, point ->
            if (index == 0) {
                result.add(point)
                return@forEachIndexed
            }
            val from = path[index - 1]
            val steps = maxOf(kotlin.math.abs(point.x - from.x), kotlin.math.abs(point.y - from.y))
            for (step in 1..steps) {
                val t = step.toDouble() / steps
                result.add(
                    Point(
                        (from.x + (point.x - from.x) * t).roundToInt(),
                        (from.y + (point.y - from.y) * t).roundToInt(),
                    ),
                )
            }
        }
        return result
    }

    @Test
    fun mergeObstacleWithBoundary_unionGrowsInnerBoundary() {
        val inner = listOf(Point(0, 0), Point(50, 0), Point(50, 50), Point(0, 50))
        // Diamond attached to the right side; its right half is outside the boundary.
        val obstacle = listOf(Point(50, 15), Point(75, 25), Point(50, 35), Point(25, 25))
        val merged = TrackEditorGeometry.mergeObstacleWithBoundary(inner, obstacle, isOuter = false)
        assertNotNull(merged)
        assertNoSelfCrossings(merged)
        // The merged inner boundary should extend past x=50 (towards x=75).
        assertTrue(merged.any { it.x > 50 }, "union did not grow the boundary: $merged")
    }

    @Test
    fun mergeObstacleWithBoundary_differenceCarvesOuterBoundary() {
        val outer = listOf(Point(0, 0), Point(100, 0), Point(100, 100), Point(0, 100))
        // Diamond protruding into the track from the right side.
        val obstacle = listOf(Point(100, 15), Point(110, 25), Point(100, 35), Point(90, 25))
        val merged = TrackEditorGeometry.mergeObstacleWithBoundary(outer, obstacle, isOuter = true)
        assertNotNull(merged)
        assertNoSelfCrossings(merged)
        // The outer boundary should notch inward from x=100...
        assertTrue(merged.any { it.x in 90..95 }, "difference did not carve a notch: $merged")
        // ...while still reaching the original right side above and below the notch.
        assertTrue(merged.any { it.x >= 99 }, "difference lost the original edge: $merged")
    }

    @Test
    fun mergeObstacleWithBoundary_returnsNullForDisjointShapes() {
        val outer = listOf(Point(0, 0), Point(10, 0), Point(10, 10), Point(0, 10))
        val obstacle = listOf(Point(20, 20), Point(30, 20), Point(30, 30), Point(20, 30))
        assertNull(TrackEditorGeometry.mergeObstacleWithBoundary(outer, obstacle, isOuter = true))
    }

    @Test
    fun mergeObstacleWithBoundary_returnsNullForInteriorObstacleNotTouchingBoundary() {
        val outer = listOf(Point(0, 0), Point(30, 0), Point(30, 30), Point(0, 30))
        val obstacle = listOf(Point(12, 4), Point(17, 4), Point(17, 9), Point(12, 9), Point(12, 4))
        assertNull(TrackEditorGeometry.mergeObstacleWithBoundary(outer, obstacle, isOuter = true))
        assertNull(TrackEditorGeometry.mergeObstacleWithBoundary(outer, obstacle, isOuter = false))
    }

    @Test
    fun mergeObstacleWithBoundary_emulatorSizedOuterAndObstacle_doNotMerge() {
        val outer = listOf(Point(26, 18), Point(261, 18), Point(261, 253), Point(26, 253))
        val obstacle = listOf(Point(123, 44), Point(176, 44), Point(176, 70), Point(123, 70), Point(123, 44))
        assertNull(TrackEditorGeometry.mergeObstacleWithBoundary(outer, obstacle, isOuter = true))
        assertNull(TrackEditorGeometry.mergeObstacleWithBoundary(outer, obstacle, isOuter = false))
    }

    // --- modifier auto-merge --------------------------------------------------

    private val outerRing = listOf(Point(0, 0), Point(200, 0), Point(200, 200), Point(0, 200))
    private val innerRing = listOf(Point(80, 80), Point(120, 80), Point(120, 120), Point(80, 120))

    @Test
    fun mergeModifier_clipsAwayThePartOutsideTheCorridor() {
        // Overlaps the inner island: the barrier wins, the overlap is cut off the zone.
        val zone = TrackModifier(ModifierType.OIL_SPILL, listOf(Point(60, 90), Point(110, 90), Point(110, 110), Point(60, 110)))
        val merged = assertNotNull(TrackEditorGeometry.mergeModifier(zone, emptyList(), outerRing, innerRing))
        assertEquals(1, merged.size)
        assertTrue(
            merged.single().area.none { it.x > 82 && it.x < 118 && it.y > 82 && it.y < 118 },
            "zone still covers the inner island: ${merged.single().area}",
        )
    }

    @Test
    fun mergeModifier_unionsAnOverlappingZoneOfTheSameType() {
        val first = TrackModifier(ModifierType.NAILS, listOf(Point(10, 10), Point(60, 10), Point(60, 60), Point(10, 60)))
        val second = TrackModifier(ModifierType.NAILS, listOf(Point(40, 10), Point(90, 10), Point(90, 60), Point(40, 60)))
        val merged = assertNotNull(TrackEditorGeometry.mergeModifier(second, listOf(first), outerRing, innerRing))
        assertEquals(1, merged.size, "same-type zones should union into one: $merged")
        val area = merged.single().area
        assertTrue(area.any { it.x <= 11 } && area.any { it.x >= 89 }, "union does not span both zones: $area")
    }

    @Test
    fun mergeModifier_keepsOverlappingZonesOfDifferentTypesSeparate() {
        val oil = TrackModifier(ModifierType.OIL_SPILL, listOf(Point(10, 10), Point(60, 10), Point(60, 60), Point(10, 60)))
        val nails = TrackModifier(ModifierType.NAILS, listOf(Point(40, 10), Point(90, 10), Point(90, 60), Point(40, 60)))
        val merged = assertNotNull(TrackEditorGeometry.mergeModifier(nails, listOf(oil), outerRing, innerRing))
        assertEquals(2, merged.size, "different types must coexist: $merged")
        assertEquals(setOf(ModifierType.OIL_SPILL, ModifierType.NAILS), merged.map { it.type }.toSet())
        // The overlap belongs to both - neither was clipped.
        val nailsArea = merged.first { it.type == ModifierType.NAILS }.area
        assertTrue(nailsArea.any { it.x <= 41 }, "nails zone was clipped against the oil spill: $nailsArea")
    }

    @Test
    fun mergeModifier_dropsAZoneDrawnEntirelyOutsideTheCorridor() {
        val zone = TrackModifier(ModifierType.NAILS, listOf(Point(90, 90), Point(110, 90), Point(110, 110), Point(90, 110)))
        assertNull(TrackEditorGeometry.mergeModifier(zone, emptyList(), outerRing, innerRing))
    }

    @Test
    fun clipModifiersToCorridor_cutsAwayThePartOfAZoneUnderANewObstacle() {
        val zone = TrackModifier(ModifierType.OIL_SPILL, listOf(Point(10, 10), Point(70, 10), Point(70, 40), Point(10, 40)))
        // Sits over the right-hand third of the zone.
        val obstacle = listOf(Point(50, 0), Point(90, 0), Point(90, 60), Point(50, 60))

        val clipped = TrackEditorGeometry.clipModifiersToCorridor(listOf(zone), outerRing, innerRing, listOf(obstacle))

        assertEquals(1, clipped.size, "expected one surviving piece: $clipped")
        assertEquals(ModifierType.OIL_SPILL, clipped.single().type)
        val area = clipped.single().area
        assertTrue(area.maxOf { it.x } <= 50, "zone still reaches under the obstacle: $area")
        assertTrue(area.minOf { it.x } <= 11, "the part clear of the obstacle was lost too: $area")
    }

    @Test
    fun clipModifiersToCorridor_splitsAZoneAnObstacleCutsInHalf() {
        val zone = TrackModifier(ModifierType.NAILS, listOf(Point(10, 10), Point(70, 10), Point(70, 40), Point(10, 40)))
        val obstacle = listOf(Point(30, 0), Point(45, 0), Point(45, 60), Point(30, 60))

        val clipped = TrackEditorGeometry.clipModifiersToCorridor(listOf(zone), outerRing, innerRing, listOf(obstacle))

        assertEquals(2, clipped.size, "a zone cut in two should stay two zones: $clipped")
        assertTrue(clipped.all { it.type == ModifierType.NAILS })
    }

    @Test
    fun clipModifiersToCorridor_dropsAZoneCompletelyBuriedByABoundaryChange() {
        val zone = TrackModifier(ModifierType.NAILS, listOf(Point(85, 85), Point(115, 85), Point(115, 115), Point(85, 115)))
        // Entirely inside the infield island, so no part of it is on the track any more.
        assertEquals(emptyList(), TrackEditorGeometry.clipModifiersToCorridor(listOf(zone), outerRing, innerRing))
    }

    private fun assertBoundingBox(points: List<Point>, minX: Int, minY: Int, maxX: Int, maxY: Int, tolerance: Int = 1) {
        assertTrue(kotlin.math.abs(points.minOf { it.x } - minX) <= tolerance, "minX: $points")
        assertTrue(kotlin.math.abs(points.minOf { it.y } - minY) <= tolerance, "minY: $points")
        assertTrue(kotlin.math.abs(points.maxOf { it.x } - maxX) <= tolerance, "maxX: $points")
        assertTrue(kotlin.math.abs(points.maxOf { it.y } - maxY) <= tolerance, "maxY: $points")
    }

    private fun assertNoSelfCrossings(points: List<Point>) {
        val ring = if (points.size > 2 && points.first() == points.last()) points.dropLast(1) else points
        val n = ring.size
        if (n < 4) return
        for (i in 0 until n) {
            val a1 = ring[i]
            val a2 = ring[(i + 1) % n]
            for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue
                val b1 = ring[j]
                val b2 = ring[(j + 1) % n]
                assertTrue(
                    !Geometry.segmentsIntersect(a1, a2, b1, b2),
                    "found self-crossing between $a1-$a2 and $b1-$b2",
                )
            }
        }
    }
}
