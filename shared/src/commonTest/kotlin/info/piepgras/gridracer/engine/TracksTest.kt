package info.piepgras.gridracer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TracksTest {

    @Test
    fun everyBuiltInTrackHasAtLeastFourDistinctStartPositions() {
        for (track in Tracks.all) {
            assertTrue(track.startPositions.size >= 4, "${track.name}: expected at least 4 start positions")
            assertEquals(
                track.startPositions.size,
                track.startPositions.distinct().size,
                "${track.name}: start positions must be distinct",
            )
        }
    }

    @Test
    fun everyStartPositionIsInsideTheTracksOwnStartBox() {
        for (track in Tracks.all) {
            for (pos in track.startPositions) {
                val fine = pos * track.subdivision
                val dist = Geometry.distancePointToSegment(fine, track.finishLine.from, track.finishLine.to)
                assertTrue(
                    dist <= track.startZoneDepth * track.subdivision + 1e-6,
                    "${track.name}: start position $pos is $dist fine units from the line, " +
                        "outside its ${track.startZoneDepth} coarse-unit start box",
                )
            }
        }
    }

    /**
     * The invariant the shipped code was missing: sitting in the start box is not enough,
     * a car parked there has to be able to leave it. See the 2026-08 crash triage.
     */
    @Test
    fun everyStartPositionCanMakeAnOpeningMoveInEitherDirection() {
        for (base in Tracks.all) {
            for (track in listOf(base, base.reversed())) {
                assertTrue(track.startPositions.size >= 4, "${track.name}: lost start slots when reversed")
                for (pos in track.startPositions) {
                    val state = GameEngine.newGame(track, listOf(pos))
                    assertTrue(
                        GameEngine.legalMoves(state, 0).isNotEmpty(),
                        "${track.name}: a car at $pos has no opening move (direction ${track.finishDirection})",
                    )
                }
            }
        }
    }

    @Test
    fun everyOuterBoundaryStaysInsideTheVisibleGrid() {
        // The outer boundary must keep a margin from the grid edge so the full wall outline
        // (including its stroke width) is visible on screen. Every wall endpoint should sit
        // at least one coarse unit in from each edge.
        for (track in Tracks.all) {
            for (wall in track.walls) {
                for (p in listOf(wall.from, wall.to)) {
                    val coarseX = p.x.toDouble() / track.subdivision
                    val coarseY = p.y.toDouble() / track.subdivision
                    assertTrue(coarseX >= 0.5, "${track.name}: wall point at coarse x=$coarseX is too close to the left edge")
                    assertTrue(coarseX <= track.width - 0.5, "${track.name}: wall point at coarse x=$coarseX is too close to the right edge")
                    assertTrue(coarseY >= 0.5, "${track.name}: wall point at coarse y=$coarseY is too close to the top edge")
                    assertTrue(coarseY <= track.height - 0.5, "${track.name}: wall point at coarse y=$coarseY is too close to the bottom edge")
                }
            }
        }
    }

    @Test
    fun everyBuiltInTrackSupportsUpToFourPlayers() {
        for (track in Tracks.all) {
            assertTrue(track.startPositions.size >= 4, "${track.name}: should support 4 players")
        }
    }
}
