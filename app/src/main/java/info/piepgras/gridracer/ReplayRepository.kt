package info.piepgras.gridracer

import info.piepgras.gridracer.replay.ReplayData
import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Maximum number of replays kept on disk; older entries are evicted as new ones arrive. */
private const val MAX_REPLAYS = 20

/**
 * Persists finished-race replays as a JSON file in `context.filesDir`, following the same
 * pattern as [CustomTrackRepository] and [LeaderboardRepository]: a [MutableStateFlow] seeded
 * from disk, synchronous in-memory updates, async writes on an internal IO scope.
 */
class ReplayRepository(
    private val storageFile: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _replays = MutableStateFlow(load())
    /** Saved replays, oldest first; at most 20 are kept. */
    val replays: StateFlow<List<ReplayData>> = _replays.asStateFlow()

    /**
     * Saves a replay, evicting the oldest entries past [MAX_REPLAYS].
     *
     * Fills in [ReplayData.id] and [ReplayData.timestamp] if the caller left them blank - the
     * shared type has no clock and no UUID source of its own, and an imported file may carry
     * neither. Returns the stored replay so a caller can navigate to it.
     */
    fun save(replay: ReplayData): ReplayData {
        val stored = replay.withIdentity()
        // Re-saving an already-stored replay (an import of a file already imported) replaces
        // it rather than making a second copy.
        val updated = (_replays.value.filterNot { it.id == stored.id } + stored).takeLast(MAX_REPLAYS)
        _replays.value = updated
        ioScope.launch { runCatching { save(updated) } }
        return stored
    }

    /** True if a replay with this id is already stored, so an import can say so. */
    fun contains(id: String): Boolean = _replays.value.any { it.id == id }

    /** Removes [replay], in memory at once and on disk in the background. */
    fun delete(replay: ReplayData) {
        val updated = _replays.value.filterNot { it.id == replay.id }
        _replays.value = updated
        ioScope.launch { runCatching { save(updated) } }
    }

    /** Removes every saved replay and deletes the file. */
    fun clear() {
        _replays.value = emptyList()
        ioScope.launch { runCatching { storageFile.delete() } }
    }

    private fun load(): List<ReplayData> {
        if (!storageFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ReplayData>>(storageFile.readText()) }
            .getOrDefault(emptyList())
            // Replays written before ids existed are given one on load, so the list keys and
            // the delete path have something stable to work with from the first frame.
            .map { it.withIdentity() }
    }

    private fun ReplayData.withIdentity(): ReplayData {
        if (id.isNotBlank() && timestamp != 0L) return this
        return copy(
            id = id.ifBlank { java.util.UUID.randomUUID().toString() },
            timestamp = if (timestamp == 0L) System.currentTimeMillis() else timestamp,
        )
    }

    private fun save(replays: List<ReplayData>) {
        storageFile.writeText(json.encodeToString(replays))
    }

    companion object {
        /** The repository backed by `replays.json` in the app's private files directory. */
        fun create(context: Context): ReplayRepository =
            ReplayRepository(File(context.filesDir, "replays.json"))
    }
}
