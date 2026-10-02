package info.piepgras.gridracer.engine

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The thumbnail arrow is decorative, but "decorative" is exactly why it needs testing:
 * nothing at runtime notices an arrow drawn through a wall, and a preview is the last
 * chance a player has to understand a track before racing it.
 */
class TrackGuideArrowTest {

    private val allTracks: List<Track>
        get() = Tracks.all + TrackBuilder.build("user drawn", UserDrawnTrackFixture.draft)

    @Test
    fun arrow_staysInsideTheCorridorOnEveryTrackInEitherDirection() {
        for (track in allTracks) {
            for (oriented in listOf(track, track.reversed())) {
                val arrow = assertNotNull(TrackGuideArrow.forTrack(oriented), "no arrow for ${oriented.name}")
                assertTrue(arrow.centreLine.size >= 2, "${oriented.name}: arrow needs a line, not a dot")
                for (p in arrow.centreLine) {
                    assertTrue(
                        Geometry.insideWalls(p, oriented.walls),
                        "${oriented.name}: arrow point $p is not on the track",
                    )
                }
                assertTrue(arrow.halfWidthFine > 0.0, "${oriented.name}: arrow has no width to draw at")
            }
        }
    }

    @Test
    fun arrow_startsOnTheFinishLineAndLeadsAwayInTheRacingDirection() {
        for (track in allTracks) {
            val arrow = assertNotNull(TrackGuideArrow.forTrack(track))
            val start = arrow.centreLine.first()
            val distance = Geometry.distancePointToSegment(start, track.finishLine.from, track.finishLine.to)
            assertTrue(
                distance <= track.subdivision.toDouble(),
                "${track.name}: arrow starts $distance from the finish line, not on it",
            )
            val end = arrow.centreLine.last()
            val alignment = (end.x - start.x) * track.finishDirection.x + (end.y - start.y) * track.finishDirection.y
            assertTrue(alignment > 0, "${track.name}: arrow leads backwards down the track")
        }
    }

    @Test
    fun arrow_turnsRoundWithTheTrack() {
        for (track in allTracks) {
            val forward = assertNotNull(TrackGuideArrow.forTrack(track))
            val backward = assertNotNull(TrackGuideArrow.forTrack(track.reversed()))
            val forwardStep = forward.centreLine.last() - forward.centreLine.first()
            val backwardStep = backward.centreLine.last() - backward.centreLine.first()
            val alignment = forwardStep.x * backwardStep.x + forwardStep.y * backwardStep.y
            assertTrue(alignment < 0, "${track.name}: reversing the track did not reverse the arrow")
        }
    }

    @Test
    fun arrow_isNeverShorterThanItIsWide() {
        // 5% of a lap is less than the corridor's own width on a short, wide track, and an
        // arrow wider than it is long reads as a smudge - so the run gets stretched. This is
        // the check that the stretch actually happens, on the real tracks that need it.
        for (track in allTracks) {
            val arrow = assertNotNull(TrackGuideArrow.forTrack(track))
            val width = arrow.halfWidthFine * 2
            assertTrue(
                lengthOf(arrow) >= width,
                "${track.name}: arrow is ${lengthOf(arrow)} long but $width wide",
            )
        }
    }

    @Test
    fun arrow_growsWithTheRequestedShareOfTheLap() {
        // Past the length floor, the arrow is as long as it was asked to be - a whole lap's
        // worth would bury the track, which is what MAX_LAP_FRACTION is for.
        val track = Tracks.all.first()
        val short = assertNotNull(TrackGuideArrow.forTrack(track, lapFraction = 0.15))
        val long = assertNotNull(TrackGuideArrow.forTrack(track, lapFraction = 0.45))
        assertTrue(
            lengthOf(long) > lengthOf(short) * 2,
            "arrow length does not scale with the requested share of the lap",
        )
    }

    private fun lengthOf(arrow: TrackGuideArrow.GuideArrow): Double =
        arrow.centreLine.zipWithNext { a, b ->
            kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        }.sum()
}
