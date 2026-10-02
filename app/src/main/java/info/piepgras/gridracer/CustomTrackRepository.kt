package info.piepgras.gridracer

import android.content.Context
import info.piepgras.gridracer.engine.CustomTrack
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.TrackBuilder
import info.piepgras.gridracer.engine.TrackDraft
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Android-specific adapter: persists user-created tracks as a JSON file, mirroring [LeaderboardRepository]'s pattern. */
class CustomTrackRepository(
    private val storageFile: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _tracks = MutableStateFlow(load())
    /** Every saved custom track, in the order they were last saved. */
    val tracks: StateFlow<List<CustomTrack>> = _tracks.asStateFlow()

    /** Saves a new track (id == null) or overwrites an existing one (id matches a saved entry). */
    fun save(id: String?, name: String, draft: TrackDraft, track: Track, sharedTrackId: String? = null) {
        val entry = CustomTrack(id = id ?: UUID.randomUUID().toString(), name = name, draft = draft, track = track, sharedTrackId = sharedTrackId)
        val updated = _tracks.value.filterNot { it.id == entry.id } + entry
        _tracks.value = updated
        ioScope.launch { runCatching { save(updated) } }
    }

    /** Returns true if a track with the given [sharedTrackId] is already in the library. */
    fun existsBySharedTrackId(sharedTrackId: String): Boolean =
        _tracks.value.any { it.sharedTrackId == sharedTrackId }

    /** Removes the track with [id], in memory at once and on disk in the background. */
    fun delete(id: String) {
        val updated = _tracks.value.filterNot { it.id == id }
        _tracks.value = updated
        ioScope.launch { runCatching { save(updated) } }
    }

    private fun load(): List<CustomTrack> {
        if (!storageFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<CustomTrack>>(storageFile.readText()) }
            .getOrDefault(emptyList())
            .map { it.copy(track = rebuilt(it)) }
    }

    /**
     * Recompiles a saved track from the draft it was drawn from, so tracks saved by an
     * older build pick up fixes to [TrackBuilder] - specifically the start grid, which
     * used to be computed in a way that could leave a car unable to move off the line at
     * all (see the 2026-08 crash triage). This is exactly why [CustomTrack] keeps
     * the draft alongside the compiled track.
     *
     * Falls back to the stored track if the draft won't build: a track that used to work
     * should keep working, even if it is one this build's rules would now reject.
     */
    private fun rebuilt(entry: CustomTrack): Track =
        runCatching { TrackBuilder.build(entry.name, entry.draft) }.getOrDefault(entry.track)

    private fun save(tracks: List<CustomTrack>) {
        storageFile.writeText(json.encodeToString(tracks))
    }

    companion object {
        /** The repository backed by `custom_tracks.json` in the app's private files directory. */
        fun create(context: Context): CustomTrackRepository =
            CustomTrackRepository(File(context.filesDir, "custom_tracks.json"))
    }
}
