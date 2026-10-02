package info.piepgras.gridracer

import info.piepgras.gridracer.engine.CustomTrack
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.TrackBuilder
import info.piepgras.gridracer.engine.TrackDraft
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CustomTrackRepositoryTest {

    private lateinit var storageFile: File

    private val json = Json { ignoreUnknownKeys = true }

    private val draft = TrackDraft(
        outerBoundary = listOf(Point(0, 0), Point(20, 0), Point(20, 12), Point(0, 12)),
        innerBoundary = listOf(Point(7, 4), Point(13, 4), Point(13, 7), Point(7, 7)),
        finishLineFrom = Point(10, 7),
        finishLineTo = Point(10, 12),
        finishDirection = Point(-1, 0),
    )

    @Before
    fun setUp() {
        storageFile = File.createTempFile("custom_tracks", ".json")
        storageFile.delete()
    }

    @After
    fun tearDown() {
        storageFile.delete()
    }

    private fun repository() = CustomTrackRepository(storageFile, Dispatchers.Unconfined)

    @Test
    fun save_thenReload_keepsTheTrack() {
        repository().save(null, "Loop", draft, TrackBuilder.build("Loop", draft))

        val reloaded = repository().tracks.value
        assertEquals(1, reloaded.size)
        assertEquals("Loop", reloaded.single().name)
    }

    /**
     * Tracks saved by an older build carry a compiled [Track] whose start grid was
     * computed by the old, broken rule - some of its slots have no legal opening move.
     * Loading has to recompile from the stored draft, or a track drawn before the fix
     * stays broken forever. See the 2026-08 crash triage.
     */
    @Test
    fun load_recompilesStaleTracksFromTheirDraft() {
        val stale = CustomTrack(
            id = "stale",
            name = "Loop",
            draft = draft,
            track = TrackBuilder.build("Loop", draft).copy(startPositions = listOf(Point(0, 0))),
        )
        storageFile.writeText(json.encodeToString(listOf(stale)))

        val loaded = repository().tracks.value.single().track
        assertEquals(TrackBuilder.build("Loop", draft).startPositions, loaded.startPositions)
        assertTrue("stale single start position should have been replaced", loaded.startPositions.size > 1)
    }

    @Test
    fun load_keepsATrackWhoseDraftCannotBeRebuilt() {
        val built = TrackBuilder.build("Loop", draft)
        val unbuildable = CustomTrack(
            id = "legacy",
            name = "Loop",
            // No finish line in the draft: TrackBuilder.build would throw.
            draft = draft.copy(finishLineFrom = null, finishLineTo = null),
            track = built,
        )
        storageFile.writeText(json.encodeToString(listOf(unbuildable)))

        assertEquals(built.startPositions, repository().tracks.value.single().track.startPositions)
    }
}
