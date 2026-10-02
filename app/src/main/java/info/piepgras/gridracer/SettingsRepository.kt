package info.piepgras.gridracer

import android.content.Context
import info.piepgras.gridracer.engine.AppSettings
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

/** Android-specific adapter: persists app settings as a JSON file, mirroring [LeaderboardRepository]'s pattern. */
class SettingsRepository(
    private val storageFile: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _settings = MutableStateFlow(load())
    /** The current settings; a fresh install starts from [AppSettings]' defaults. */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Applies [transform] to the settings - in memory at once, on disk in the background. */
    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        ioScope.launch { runCatching { save(updated) } }
    }

    private fun load(): AppSettings {
        if (!storageFile.exists()) return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(storageFile.readText()) }
            .getOrDefault(AppSettings())
            .withCurrentServerUrl()
    }

    private fun save(settings: AppSettings) {
        storageFile.writeText(json.encodeToString(settings))
    }

    companion object {
        /** The repository backed by `settings.json` in the app's private files directory. */
        fun create(context: Context): SettingsRepository =
            SettingsRepository(File(context.filesDir, "settings.json"))
    }
}
