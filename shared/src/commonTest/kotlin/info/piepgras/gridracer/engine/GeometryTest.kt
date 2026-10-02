package info.piepgras.gridracer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeometryTest {

    @Test
    fun segmentsIntersect_detectsACrossingPair() {
        assertTrue(Geometry.segmentsIntersect(Point(0, 0), Point(4, 4), Point(0, 4), Point(4, 0)))
    }

    @Test
    fun segmentsIntersect_falseForParallelNonTouchingSegments() {
        assertFalse(Geometry.segmentsIntersect(Point(0, 0), Point(4, 0), Point(0, 1), Point(4, 1)))
    }

    @Test
    fun segmentsIntersect_trueForCollinearOverlap() {
        assertTrue(Geometry.segmentsIntersect(Point(0, 0), Point(4, 0), Point(2, 0), Point(6, 0)))
    }

    @Test
    fun distancePointToSegment_isZeroOnTheSegment() {
        assertEquals(0.0, Geometry.distancePointToSegment(Point(2, 0), Point(0, 0), Point(4, 0)))
    }

    @Test
    fun distancePointToSegment_clampsToNearestEndpoint() {
        // Closest point on the segment to (10, 0) is the endpoint (4, 0), distance 6.
        assertEquals(6.0, Geometry.distancePointToSegment(Point(10, 0), Point(0, 0), Point(4, 0)))
    }

    @Test
    fun distancePointToSegment_perpendicularDistanceOffASegment() {
        assertEquals(3.0, Geometry.distancePointToSegment(Point(2, 3), Point(0, 0), Point(4, 0)))
    }

    @Test
    fun pointInPolygon_trueForPointInsideASquare() {
        val square = listOf(Point(0, 0), Point(10, 0), Point(10, 10), Point(0, 10))
        assertTrue(Geometry.pointInPolygon(Point(5, 5), square))
    }

    @Test
    fun pointInPolygon_falseForPointOutsideASquare() {
        val square = listOf(Point(0, 0), Point(10, 0), Point(10, 10), Point(0, 10))
        assertFalse(Geometry.pointInPolygon(Point(15, 5), square))
    }

    @Test
    fun pointInPolygon_respectsAConcaveNotch() {
        // A square with a notch cut out of the middle of its top edge, reaching to the center.
        val notchedSquare = listOf(
            Point(0, 0), Point(10, 0), Point(10, 10),
            Point(6, 10), Point(6, 5), Point(4, 5), Point(4, 10),
            Point(0, 10),
        )
        assertFalse(Geometry.pointInPolygon(Point(5, 8), notchedSquare))
        assertTrue(Geometry.pointInPolygon(Point(2, 8), notchedSquare))
    }

    @Test
    fun closedEdges_wrapsLastPointBackToFirst() {
        val square = listOf(Point(0, 0), Point(10, 0), Point(10, 10), Point(0, 10))
        val edges = Geometry.closedEdges(square)
        assertEquals(4, edges.size)
        assertEquals(Point(0, 10) to Point(0, 0), edges.last())
    }

    @Test
    fun segmentIntersectionPoint_findsACrossingPoint() {
        val hit = Geometry.segmentIntersectionPoint(Point(0, 0), Point(4, 4), Point(0, 4), Point(4, 0))
        assertEquals(Point(2, 2), hit)
    }

    @Test
    fun segmentIntersectionPoint_nullForParallelSegments() {
        assertEquals(null, Geometry.segmentIntersectionPoint(Point(0, 0), Point(4, 0), Point(0, 1), Point(4, 1)))
    }

    @Test
    fun segmentIntersectionPoint_nullWhenCrossingIsOutsideEitherSegmentsBounds() {
        // The lines cross, but only if segment 1 were extended well past its own endpoint.
        assertEquals(null, Geometry.segmentIntersectionPoint(Point(0, 0), Point(1, 1), Point(0, 4), Point(4, 0)))
    }
}
