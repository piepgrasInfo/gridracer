package info.piepgras.gridracer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun validDraft(): TrackDraft = TrackDraft(
    outerBoundary = listOf(Point(0, 0), Point(20, 0), Point(20, 12), Point(0, 12)),
    innerBoundary = listOf(Point(7, 4), Point(13, 4), Point(13, 7), Point(7, 7)),
    finishLineFrom = Point(10, 7),
    finishLineTo = Point(10, 12),
    finishDirection = Point(-1, 0),
)

private fun wallsOf(draft: TrackDraft) =
    TrackBuilder.wallsFor(draft.outerBoundary, draft.innerBoundary, draft.obstacles)

class TrackBuilderTest {

    @Test
    fun startPositionsFor_returnsTheGridPointsAlongTheLine() {
        val draft = validDraft()
        val positions = TrackBuilder.startPositionsFor(
            wallsOf(draft), Wall(Point(10, 7), Point(10, 12)), Point(-1, 0), subdivision = 1,
        )
        // Only points on the line itself are within 1/sqrt(2) of it here; (10,7) and (10,12)
        // sit on a wall, where no opening move can get off the line.
        assertEquals(setOf(Point(10, 8), Point(10, 9), Point(10, 10), Point(10, 11)), positions.toSet())
    }

    @Test
    fun startPositionsFor_rejectsSlotsWithNoLegalOpeningMove() {
        val draft = validDraft()
        val walls = wallsOf(draft)
        val positions = TrackBuilder.startPositionsFor(walls, Wall(Point(10, 7), Point(10, 12)), Point(-1, 0), 1)
        // (10,7) is on the inner boundary's edge: every move off it runs along a wall.
        assertTrue(Point(10, 7) !in positions, "a slot sitting on a wall must not be offered")
        // ...and (10,12) is on the outer wall, i.e. outside the drivable area entirely.
        assertTrue(Point(10, 12) !in positions)
    }

    @Test
    fun startPositionsFor_excludesPointsPastTheLine() {
        val draft = validDraft()
        val positions = TrackBuilder.startPositionsFor(wallsOf(draft), Wall(Point(10, 7), Point(10, 12)), Point(-1, 0), 1)
        // finishDirection is -x, so the grid is on the +x side; nothing at x < 10 qualifies.
        assertTrue(positions.all { it.x >= 10 }, "found a slot already past the line: $positions")
    }

    @Test
    fun startGrid_deepensTheBoxUntilItHoldsAFullRow() {
        val draft = validDraft()
        val grid = TrackBuilder.startGrid(wallsOf(draft), Wall(Point(10, 7), Point(10, 12)), Point(-1, 0), 1)
        assertEquals(TrackBuilder.TARGET_START_POSITIONS, grid.positions.size)
        // This line already holds four cars at the shallowest depth - no deepening needed.
        assertEquals(Track.DEFAULT_START_ZONE_DEPTH, grid.depth)
    }

    @Test
    fun build_wallCountMatchesBoundaryAndObstacleEdges() {
        val draft = validDraft().copy(obstacles = listOf(listOf(Point(2, 5), Point(2, 6), Point(3, 6))))
        val track = TrackBuilder.build("Test", draft)
        // 4 outer edges (closed) + 4 inner edges (closed) + 2 obstacle segments (open).
        assertEquals(4 + 4 + 2, track.walls.size)
    }

    @Test
    fun build_closedObstacle_becomesClosedWalls() {
        val closedObstacle = listOf(Point(2, 5), Point(2, 6), Point(3, 6), Point(2, 5))
        val draft = validDraft().copy(obstacles = listOf(closedObstacle))
        val track = TrackBuilder.build("Test", draft)
        assertEquals(4 + 4 + 3, track.walls.size)
    }

    @Test
    fun build_modifiersAreShiftedThroughWithoutGeneratingWalls() {
        val modifierArea = listOf(Point(8, 5), Point(9, 5), Point(9, 6), Point(8, 6), Point(8, 5))
        val draft = validDraft().copy(modifiers = listOf(TrackModifier(ModifierType.NAILS, modifierArea)))
        val track = TrackBuilder.build("Test", draft)
        // No extra walls: unlike an obstacle, a modifier is drivable, not solid.
        assertEquals(4 + 4, track.walls.size)
        assertEquals(1, track.modifiers.size)
        assertEquals(ModifierType.NAILS, track.modifiers[0].type)
        // Shifted by one coarse unit; validDraft() starts at (0,0) with subdivision 1.
        assertEquals(modifierArea.map { it + Point(1, 1) }, track.modifiers[0].area)
    }

    @Test
    fun build_shiftsByAWholeNumberOfCoarseUnits() {
        // A draft whose minimum is not a multiple of the subdivision. Shifting by a
        // fraction of a coarse unit would move the drawing relative to the lattice cars
        // move on, so validation and the built track would disagree about the start grid.
        val draft = validDraft().copy(subdivision = 5).let { d ->
            d.copy(
                outerBoundary = d.outerBoundary.map { it * 5 + Point(3, 2) },
                innerBoundary = d.innerBoundary.map { it * 5 + Point(3, 2) },
                finishLineFrom = d.finishLineFrom!! * 5 + Point(3, 2),
                finishLineTo = d.finishLineTo!! * 5 + Point(3, 2),
            )
        }
        val track = TrackBuilder.build("Test", draft)
        val shiftX = track.finishLine.from.x - (draft.finishLineFrom!!.x)
        val shiftY = track.finishLine.from.y - (draft.finishLineFrom!!.y)
        assertEquals(0, shiftX % 5, "x shift $shiftX is not a whole number of coarse units")
        assertEquals(0, shiftY % 5, "y shift $shiftY is not a whole number of coarse units")
    }

    @Test
    fun build_startPositionsSitOnTheFinishLineAndOnlyAllowMovingForwardOffIt() {
        val track = TrackBuilder.build("Test", validDraft())
        assertTrue(TrackValidator.validate(validDraft()).isValid)

        val state = GameEngine.newGame(track, 1)
        val start = state.currentPlayer.position
        assertTrue(start in track.startPositions)

        // Moving off in finishDirection is legal; moving the opposite way (back over the line) isn't offered at all.
        val moves = GameEngine.legalMoves(state, 0)
        assertTrue(start + track.finishDirection in moves)
        assertTrue(start - track.finishDirection !in moves)

        // The starting move itself never counts as a lap crossing, even though it leaves a point on the line.
        val afterFirstMove = GameEngine.applyMove(state, 0, start + track.finishDirection)
        assertEquals(PlayerStatus.RACING, afterFirstMove.players[0].status)
        assertEquals(0, afterFirstMove.players[0].lapsCompleted)
    }

    @Test
    fun startPositionsFor_offGridLineUsesTheStartSideGridPoints() {
        // The corridor scaled up 5x, with a finish line half a coarse unit off the lattice.
        val draft = validDraft().copy(subdivision = 5).let { d ->
            d.copy(
                outerBoundary = d.outerBoundary.map { it * 5 },
                innerBoundary = d.innerBoundary.map { it * 5 },
            )
        }
        val line = Wall(Point(52, 35), Point(52, 60))
        val positions = TrackBuilder.startPositionsFor(wallsOf(draft), line, Point(-1, 0), subdivision = 5)
        assertTrue(positions.size >= 4, "expected a full row of slots, got $positions")
        positions.forEach { p ->
            val fine = p * 5
            assertTrue(
                Geometry.distancePointToSegment(fine, line.from, line.to) <= Track.DEFAULT_START_ZONE_DEPTH * 5 + 1e-6,
                "expected $p to be inside the start box",
            )
            // The line is at fine x=52 and cars run towards -x, so the grid is the x=55 column.
            assertTrue(p.x >= 11, "expected $p to be on the starting side of the line")
        }
    }

    @Test
    fun startPositionsFor_onAnAngledFineLine_landsNearButNotNecessarilyOnIt() {
        val draft = validDraft().copy(subdivision = 5).let { d ->
            d.copy(
                outerBoundary = d.outerBoundary.map { it * 5 },
                innerBoundary = d.innerBoundary.map { it * 5 },
            )
        }
        val line = Wall(Point(45, 60), Point(70, 35))
        val positions = TrackBuilder.startPositionsFor(wallsOf(draft), line, Point(-1, -1), subdivision = 5)
        assertTrue(positions.isNotEmpty(), "an angled line should still yield start slots")
        positions.forEach { p ->
            val fine = p * 5
            val dist = Geometry.distancePointToSegment(fine, line.from, line.to)
            assertTrue(dist <= 2.5 * 5, "expected $p (fine $fine) close to the angled line, was $dist fine units away")
        }
    }

    @Test
    fun capFinishLineToBoundaries_extendsARoughDragToTheCorridorEdges() {
        // Annular track: outer 0..20 x 0..12, inner 7..13 x 4..7. A short diagonal drag
        // roughly through the bottom corridor should cap out to the outer/inner edges.
        val outer = listOf(Point(0, 0), Point(20, 0), Point(20, 12), Point(0, 12))
        val inner = listOf(Point(7, 4), Point(13, 4), Point(13, 7), Point(7, 7))
        val capped = TrackBuilder.capFinishLineToBoundaries(Point(9, 8), Point(11, 10), outer, inner)
        assertTrue(capped != null, "expected the drag to cap to both boundaries")
        val (from, to) = capped
        // One end should land on the inner boundary's bottom edge (y=7), the other on the outer's (y=12).
        val ys = setOf(from.y, to.y)
        assertTrue(7 in ys, "expected one capped end on the inner boundary, got $capped")
        assertTrue(12 in ys, "expected one capped end on the outer boundary, got $capped")
    }

    @Test
    fun capFinishLineToBoundaries_nullWhenTheDragIsDegenerate() {
        val outer = listOf(Point(0, 0), Point(20, 0), Point(20, 12), Point(0, 12))
        val inner = listOf(Point(7, 4), Point(13, 4), Point(13, 7), Point(7, 7))
        assertEquals(null, TrackBuilder.capFinishLineToBoundaries(Point(9, 8), Point(9, 8), outer, inner))
    }

    @Test
    fun autoPlaceFinishLine_findsAValidLineAndDirectionOnAnAnnularTrack() {
        val outer = listOf(Point(0, 0), Point(20, 0), Point(20, 12), Point(0, 12))
        val inner = listOf(Point(7, 4), Point(13, 4), Point(13, 7), Point(7, 7))
        val result = TrackBuilder.autoPlaceFinishLine(outer, inner, subdivision = 1)
        assertTrue(result != null, "expected an automatic placement on a valid annular corridor")
        val (from, to, direction) = result
        val length = kotlin.math.hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
        assertTrue(length >= TrackValidator.MIN_FINISH_LINE_LENGTH, "line too short: $length")

        // Feeding the result back into a full draft should pass validation cleanly.
        val draft = TrackDraft(
            outerBoundary = outer,
            innerBoundary = inner,
            finishLineFrom = from,
            finishLineTo = to,
            finishDirection = direction,
        )
        assertTrue(TrackValidator.validate(draft).isValid, "auto-placed line failed validation: ${TrackValidator.validate(draft).errors}")
    }

    @Test
    fun autoPlaceFinishLine_worksAtAnySubdivision() {
        val outer = listOf(Point(0, 0), Point(20, 0), Point(20, 12), Point(0, 12)).map { it * 5 }
        val inner = listOf(Point(7, 4), Point(13, 4), Point(13, 7), Point(7, 7)).map { it * 5 }
        val result = TrackBuilder.autoPlaceFinishLine(outer, inner, subdivision = 5)
        assertTrue(result != null, "expected an automatic placement at subdivision 5")
        val (from, to, direction) = result
        val walls = TrackBuilder.wallsFor(outer, inner, emptyList())
        assertTrue(TrackBuilder.startGrid(walls, Wall(from, to), direction, 5).positions.size >= 2)
    }

    @Test
    fun build_withSubdivision_scalesWallsAndReportsCoarseWidthHeight() {
        val draft = validDraft().copy(subdivision = 5).let { d ->
            // Re-express the same annular shape on a 5x finer grid.
            d.copy(
                outerBoundary = d.outerBoundary.map { it * 5 },
                innerBoundary = d.innerBoundary.map { it * 5 },
                finishLineFrom = d.finishLineFrom!! * 5,
                finishLineTo = d.finishLineTo!! * 5,
            )
        }
        val track = TrackBuilder.build("Test", draft)
        assertEquals(5, track.subdivision)
        // Coarse width/height should match the original (unscaled) draft's, not the fine coordinates.
        val unscaledTrack = TrackBuilder.build("Test", validDraft())
        assertEquals(unscaledTrack.width, track.width)
        assertEquals(unscaledTrack.height, track.height)
    }

    // --- the real hand-drawn track that exposed the bug -----------------------

    @Test
    fun build_pullsADrawingMadeFarFromTheOriginBackOntoTheGrid() {
        // The editor canvas is 60 coarse units wide, so a track drawn in its far corner
        // starts at large coordinates. It has to be translated back, or the compiled grid
        // is mostly empty space and the track is drawn tiny.
        val near = TrackBuilder.build("Near", UserDrawnTrackFixture.draft)
        val far = TrackBuilder.build(
            "Far",
            UserDrawnTrackFixture.draft.let { d ->
                val offset = Point(100, 100)
                d.copy(
                    outerBoundary = d.outerBoundary.map { it + offset },
                    innerBoundary = d.innerBoundary.map { it + offset },
                    modifiers = d.modifiers.map { m -> m.copy(area = m.area.map { it + offset }) },
                    finishLineFrom = d.finishLineFrom!! + offset,
                    finishLineTo = d.finishLineTo!! + offset,
                )
            },
        )
        assertEquals(near.width, far.width)
        assertEquals(near.height, far.height)
        assertEquals(near.startPositions, far.startPositions)
    }

    @Test
    fun build_userDrawnTrack_seatsAFullFieldInBothDirections() {
        val track = TrackBuilder.build("rddd", UserDrawnTrackFixture.draft)
        assertTrue(
            track.startPositions.size >= TrackBuilder.TARGET_START_POSITIONS,
            "only ${track.startPositions.size} start slots - this track used to offer 3, capping races at 3 players",
        )
        assertTrue(
            track.reversed().startPositions.size >= TrackBuilder.TARGET_START_POSITIONS,
            "reversing lost start slots: ${track.reversed().startPositions.size}",
        )
    }

    @Test
    fun build_userDrawnTrack_everyStartSlotCanActuallyStart() {
        val forward = TrackBuilder.build("rddd", UserDrawnTrackFixture.draft)
        listOf(forward, forward.reversed()).forEach { track ->
            track.startPositions.forEach { slot ->
                val state = GameEngine.newGame(track, listOf(slot))
                assertEquals(
                    PlayerStatus.RACING,
                    state.players[0].status,
                    "a car placed at $slot is crashed before it moves (direction ${track.finishDirection})",
                )
                assertTrue(
                    GameEngine.legalMoves(state, 0).isNotEmpty(),
                    "no legal opening move from $slot (direction ${track.finishDirection})",
                )
            }
        }
    }

    @Test
    fun validate_userDrawnTrackIsStillAcceptedAsDrawn() {
        assertTrue(
            TrackValidator.validate(UserDrawnTrackFixture.draft).isValid,
            "the user's own saved track no longer validates: ${TrackValidator.validate(UserDrawnTrackFixture.draft).errors}",
        )
    }
}
