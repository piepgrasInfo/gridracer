package info.piepgras.gridracer.engine

import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackGeometryTest {

    private val drawn: Track by lazy { TrackBuilder.build("Drawn", UserDrawnTrackFixture.draft) }
    private val tracks: List<Track> by lazy { Tracks.all + drawn }

    @Test
    fun outerLoop_ofEveryBuiltInTrack_isItsFirstWallLoop() {
        for (track in Tracks.all) {
            val geometry = track.geometry
            val loopSize = geometry.outerWallIndices.count()
            assertTrue(loopSize in 3..4, "${track.name}: outer loop has $loopSize walls")
            assertEquals(0, geometry.outerWallIndices.first, track.name)
            assertEquals(track.walls.take(loopSize).map { it.from }, geometry.outerLoop, track.name)
        }
    }

    @Test
    fun outerLoop_ofADrawnTrack_isTheOuterBoundaryItWasBuiltFrom() {
        val draft = UserDrawnTrackFixture.draft
        val outerWalls = TrackBuilder.wallsFor(draft.outerBoundary, draft.innerBoundary, draft.obstacles)
            .take(drawn.geometry.outerWallIndices.count())
        assertEquals(0, drawn.geometry.outerWallIndices.first)
        // Same shape as the drawn outer boundary, moved by the builder's shift.
        val shift = drawn.walls.first().from - outerWalls.first().from
        assertEquals(outerWalls.map { it.from + shift }, drawn.geometry.outerLoop)
    }

    @Test
    fun outerLoop_isNullWithoutAClosedLoop() {
        val open = Tracks.OVAL.copy(walls = listOf(Wall(Point(0, 0), Point(10, 0)), Wall(Point(10, 0), Point(10, 10))))
        assertNull(open.geometry.outerLoop)
        assertNull(open.geometry.gravel)
        assertNull(open.gravelView)
    }

    @Test
    fun outerLoop_aSecondLoopStartingWhereTheFirstEnds_isNotGluedOn() {
        val small = listOf(
            Wall(Point(0, 0), Point(4, 0)), Wall(Point(4, 0), Point(4, 4)),
            Wall(Point(4, 4), Point(0, 4)), Wall(Point(0, 4), Point(0, 0)),
        )
        val large = listOf(
            Wall(Point(0, 0), Point(-20, 0)), Wall(Point(-20, 0), Point(-20, -20)),
            Wall(Point(-20, -20), Point(0, -20)), Wall(Point(0, -20), Point(0, 0)),
        )
        assertEquals(4..7, TrackGeometry.findOuterLoop(small + large))
    }

    @Test
    fun wallIndex_answersExactlyWhatAScanOverEveryWallAnswers() {
        val random = Random(7)
        for (track in tracks) {
            val geometry = track.geometry
            val maxX = track.width * track.subdivision
            val maxY = track.height * track.subdivision
            repeat(4_000) {
                val from = Point(random.nextInt(-20, maxX + 20), random.nextInt(-20, maxY + 20))
                val to = from + Point(random.nextInt(-80, 81), random.nextInt(-80, 81))
                val all = track.walls.indices.filter { Geometry.segmentsIntersect(from, to, track.walls[it].from, track.walls[it].to) }
                assertEquals(all.isNotEmpty(), geometry.touchesAnyWall(from, to), "${track.name} $from-$to")
                assertEquals(all.any { geometry.isOuterWall(it) }, geometry.touchesOuterWall(from, to), "${track.name} $from-$to")
                assertEquals(all.any { !geometry.isOuterWall(it) }, geometry.touchesNonOuterWall(from, to), "${track.name} $from-$to")
            }
        }
    }

    @Test
    fun derivedGeometry_isNeitherSerializedNorPartOfEquality() {
        val json = Json { ignoreUnknownKeys = true }
        val before = json.encodeToString(Track.serializer(), Tracks.OVAL)
        assertNotNull(Tracks.OVAL.gravelView)
        val after = json.encodeToString(Track.serializer(), Tracks.OVAL)
        assertEquals(before, after)
        assertFalse("geometry" in after || "gravel" in after, after)
        val decoded = json.decodeFromString(Track.serializer(), after)
        assertEquals(Tracks.OVAL, decoded)
        assertEquals(Tracks.OVAL.hashCode(), decoded.hashCode())
    }
}
