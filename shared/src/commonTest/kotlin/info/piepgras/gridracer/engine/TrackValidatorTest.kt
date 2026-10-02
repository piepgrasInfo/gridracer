package info.piepgras.gridracer.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A valid annular draft: a 20x12 outer square around a 6x3 inner square, corridor min width 4. */
private fun validDraft(): TrackDraft = TrackDraft(
    outerBoundary = listOf(Point(0, 0), Point(20, 0), Point(20, 12), Point(0, 12)),
    innerBoundary = listOf(Point(7, 4), Point(13, 4), Point(13, 7), Point(7, 7)),
    finishLineFrom = Point(10, 7),
    finishLineTo = Point(10, 12),
    finishDirection = Point(-1, 0),
)

class TrackValidatorTest {

    @Test
    fun validDraft_isAccepted() {
        val result = TrackValidator.validate(validDraft())
        assertTrue(result.isValid, "expected no errors, got ${result.errors}")
    }

    @Test
    fun bowtieOuterBoundary_isRejectedAsNotSimple() {
        // A self-crossing "bowtie" quadrilateral.
        val bowtie = listOf(Point(0, 0), Point(20, 12), Point(20, 0), Point(0, 12))
        val draft = validDraft().copy(outerBoundary = bowtie)
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.OUTER_NOT_SIMPLE in result.errors)
    }

    @Test
    fun innerBoundaryEntirelyOutsideOuter_isRejected() {
        val draft = validDraft().copy(innerBoundary = listOf(Point(30, 30), Point(36, 30), Point(36, 33), Point(30, 33)))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.INNER_NOT_NESTED in result.errors)
    }

    @Test
    fun innerBoundaryPartiallyOutsideOuter_isRejected() {
        // Most of the inner square is inside, but it pokes out past the right edge (outer maxX = 20).
        val draft = validDraft().copy(innerBoundary = listOf(Point(15, 4), Point(25, 4), Point(25, 7), Point(15, 7)))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.INNER_NOT_NESTED in result.errors)
    }

    @Test
    fun pinchedCorridor_isRejected() {
        // Inner square's left edge sits only 1 unit from the outer square's left edge.
        val draft = validDraft().copy(innerBoundary = listOf(Point(1, 4), Point(13, 4), Point(13, 7), Point(1, 7)))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.CORRIDOR_TOO_NARROW in result.errors)
    }

    @Test
    fun trivialLoop_isRejectedAsTooSmall() {
        val draft = TrackDraft(
            outerBoundary = listOf(Point(0, 0), Point(3, 0), Point(3, 3), Point(0, 3)),
            innerBoundary = listOf(Point(1, 1), Point(2, 1), Point(2, 2), Point(1, 2)),
            finishLineFrom = Point(1, 3),
            finishLineTo = Point(1, 0),
            finishDirection = Point(1, 0),
        )
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.OUTER_TOO_SMALL in result.errors)
    }

    @Test
    fun obstacleTouchingOuterBoundary_isRejected() {
        val draft = validDraft().copy(obstacles = listOf(listOf(Point(2, 2), Point(2, 0))))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.OBSTACLE_TOUCHES_BOUNDARY in result.errors)
    }

    @Test
    fun obstaclePinchingCorridor_isRejected() {
        // Sits in the left corridor (width 7 there) but only ~1 unit from the outer boundary.
        val draft = validDraft().copy(obstacles = listOf(listOf(Point(1, 5), Point(1, 6))))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.OBSTACLE_NARROWS_CORRIDOR in result.errors)
    }

    @Test
    fun obstacleClearOfBothBoundaries_isAccepted() {
        // Widen the left corridor to 9 units (x: 0 to 9) so a short obstacle at x=4 sits
        // comfortably (4 units, 5 units) clear of both sides - avoids an exact-threshold edge case.
        val wideInner = listOf(Point(9, 4), Point(15, 4), Point(15, 7), Point(9, 7))
        val draft = validDraft().copy(innerBoundary = wideInner, obstacles = listOf(listOf(Point(4, 5), Point(4, 6))))
        val result = TrackValidator.validate(draft)
        assertFalse(TrackValidationError.OBSTACLE_NARROWS_CORRIDOR in result.errors)
        assertFalse(TrackValidationError.OBSTACLE_TOUCHES_BOUNDARY in result.errors)
    }

    @Test
    fun closedObstacleInsideTrack_isAccepted() {
        // Use a large square track with the inner centered, so a 1x1 closed obstacle fits
        // with the minimum 3-unit clearance on every side.
        val draft = TrackDraft(
            outerBoundary = listOf(Point(0, 0), Point(20, 0), Point(20, 20), Point(0, 20)),
            innerBoundary = listOf(Point(7, 7), Point(13, 7), Point(13, 13), Point(7, 13)),
            obstacles = listOf(listOf(Point(16, 3), Point(17, 3), Point(17, 4), Point(16, 4), Point(16, 3))),
            finishLineFrom = Point(10, 13),
            finishLineTo = Point(10, 20),
            finishDirection = Point(-1, 0),
        )
        val result = TrackValidator.validate(draft)
        assertTrue(result.isValid, "expected closed obstacle to be valid, got ${result.errors}")
    }

    @Test
    fun closedObstacleInsideInner_isRejected() {
        val insideInner = listOf(Point(8, 5), Point(11, 5), Point(11, 6), Point(8, 6), Point(8, 5))
        val draft = validDraft().copy(obstacles = listOf(insideInner))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.OBSTACLE_NOT_IN_TRACK in result.errors)
    }

    @Test
    fun modifierInTheCorridor_isAccepted() {
        // Below the inner square (inner y range is 4..7), so this sits in the corridor, not the hole.
        val modifierArea = listOf(Point(9, 8), Point(11, 8), Point(11, 9), Point(9, 9), Point(9, 8))
        val draft = validDraft().copy(modifiers = listOf(TrackModifier(ModifierType.OIL_SPILL, modifierArea)))
        val result = TrackValidator.validate(draft)
        assertTrue(result.isValid, "expected a modifier in the corridor to be valid, got ${result.errors}")
    }

    @Test
    fun selfIntersectingModifier_isRejected() {
        val bowtie = listOf(Point(9, 8), Point(11, 9), Point(11, 8), Point(9, 9), Point(9, 8))
        val draft = validDraft().copy(modifiers = listOf(TrackModifier(ModifierType.NAILS, bowtie)))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.MODIFIER_NOT_SIMPLE in result.errors)
    }

    @Test
    fun modifierOutsideTheTrack_isRejected() {
        val outside = listOf(Point(30, 30), Point(32, 30), Point(32, 32), Point(30, 32), Point(30, 30))
        val draft = validDraft().copy(modifiers = listOf(TrackModifier(ModifierType.OIL_SPILL, outside)))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.MODIFIER_NOT_IN_TRACK in result.errors)
    }

    @Test
    fun missingFinishLine_isRejected() {
        val draft = validDraft().copy(finishLineFrom = null, finishLineTo = null)
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.FINISH_LINE_MISSING in result.errors)
    }

    @Test
    fun finishDirectionParallelToFinishLine_isRejected() {
        // The finish line runs vertically (dx=0); a direction that's also vertical can't sensibly cross it.
        val draft = validDraft().copy(finishDirection = Point(0, 1))
        val result = TrackValidator.validate(draft)
        assertTrue(TrackValidationError.FINISH_LINE_NOT_ACROSS_TRACK in result.errors)
    }

    @Test
    fun validDraft_stillAcceptedWhenReExpressedOnAFinerSubdivisionGrid() {
        // The exact same shape, just authored 5x finer - every threshold should scale with it.
        val fine = validDraft().copy(
            outerBoundary = validDraft().outerBoundary.map { it * 5 },
            innerBoundary = validDraft().innerBoundary.map { it * 5 },
            finishLineFrom = validDraft().finishLineFrom!! * 5,
            finishLineTo = validDraft().finishLineTo!! * 5,
            subdivision = 5,
        )
        val result = TrackValidator.validate(fine)
        assertTrue(result.isValid, "expected no errors, got ${result.errors}")
    }

    @Test
    fun pinchedCorridor_stillRejectedOnAFinerSubdivisionGrid() {
        // Same corridor-pinching shape as pinchedCorridor_isRejected, re-expressed 5x finer -
        // without subdivision-aware thresholds this would wrongly look plenty wide (5 fine units).
        val fine = validDraft().copy(
            outerBoundary = validDraft().outerBoundary.map { it * 5 },
            innerBoundary = listOf(Point(1, 4), Point(13, 4), Point(13, 7), Point(1, 7)).map { it * 5 },
            finishLineFrom = validDraft().finishLineFrom!! * 5,
            finishLineTo = validDraft().finishLineTo!! * 5,
            subdivision = 5,
        )
        val result = TrackValidator.validate(fine)
        assertTrue(TrackValidationError.CORRIDOR_TOO_NARROW in result.errors)
    }
}
