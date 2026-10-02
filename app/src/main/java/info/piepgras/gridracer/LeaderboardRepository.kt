package info.piepgras.gridracer

import android.content.Context
import info.piepgras.gridracer.engine.LeaderboardEntry
import info.piepgras.gridracer.engine.Scoring
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

/**
 * Android-specific adapter: persists the full leaderboard as a JSON file. Every
 * player's complete history is kept - it's up to callers (e.g. the leaderboard
 * screen) to decide how many entries to display.
 */
class LeaderboardRepository(
    private val storageFile: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json { ignoreUnknownKeys = true }

    // Owned by this repository rather than injected: the file write it guards is an
    // implementation detail callers shouldn't need a scope to trigger. Only the
    // dispatcher is overridable, so tests can run writes synchronously.
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _entries = MutableStateFlow(load())
    /** The local leaderboard, highest points first. */
    val entries: StateFlow<List<LeaderboardEntry>> = _entries.asStateFlow()

    /** Folds each (playerName, finishRank) result into the leaderboard and persists it. */
    fun recordRace(results: List<Pair<String, Int?>>) {
        var updated = _entries.value
        for ((name, rank) in results) {
            updated = Scoring.recordResult(updated, name, rank)
        }
        _entries.value = updated
        ioScope.launch { runCatching { save(updated) } }
    }

    private fun load(): List<LeaderboardEntry> {
        if (!storageFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<LeaderboardEntry>>(storageFile.readText()) }
            .getOrDefault(emptyList())
    }

    private fun save(entries: List<LeaderboardEntry>) {
        storageFile.writeText(json.encodeToString(entries))
    }

    companion object {
        /** The repository backed by `leaderboard.json` in the app's private files directory. */
        fun create(context: Context): LeaderboardRepository =
            LeaderboardRepository(File(context.filesDir, "leaderboard.json"))
    }
}
