package info.piepgras.gridracer

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.planner.AiDriver
import info.piepgras.gridracer.engine.planner.PlannerProfile
import info.piepgras.gridracer.engine.planner.PlannerTable
import info.piepgras.gridracer.engine.planner.RacePlanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The planning tables behind the NORMAL and BEST computer drivers, built in the background and kept
 * for the last few tracks raced.
 *
 * A table takes a fraction of a second on a built-in track and a few seconds on a large hand-drawn
 * one on a slow phone, which is why it is requested as soon as the field is known - when placement
 * starts - and only awaited when a computer driver actually has to choose. By then it is normally
 * done. One table serves every seat of its level on that track, and a tournament that comes back to
 * a track finds it still here.
 *
 * @param scope Owns the builds; cancelling it cancels any still running.
 * @param build Builds one table; replaceable so tests can count or slow builds.
 */
class AiPlannerCache(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val build: (Track, RaceRules, PlannerProfile) -> PlannerTable? = { track, rules, profile ->
        RacePlanner.build(track, rules, profile)
    },
) {
    private data class Key(val track: Track, val rules: RaceRules, val profile: PlannerProfile)

    /** Most recently used last, so the eldest entry is the first one. */
    private val entries = LinkedHashMap<Key, Deferred<PlannerTable?>>(capacity + 1, 0.75f, true)

    /** Starts building the tables seats of [levels] will need on [track], without waiting for them. */
    fun prefetch(track: Track, rules: RaceRules, levels: Collection<AiLevel>) {
        levels.mapNotNull(AiDriver::profileFor).distinct().forEach { tableFor(Key(track, rules, it)) }
    }

    /**
     * The table a seat of [level] drives by on [track], waiting up to [timeout] for it to be built.
     *
     * @return The table; or null when the level plans nothing, the track was too large to plan, or
     *     the build did not finish in time - in each case the seat drives like EASY.
     */
    suspend fun table(track: Track, rules: RaceRules, level: AiLevel, timeout: Duration = DEFAULT_WAIT): PlannerTable? {
        val profile = AiDriver.profileFor(level) ?: return null
        val pending = tableFor(Key(track, rules, profile))
        return withTimeoutOrNull(timeout) { pending.await() }
    }

    private fun tableFor(key: Key): Deferred<PlannerTable?> = synchronized(entries) {
        entries[key] ?: scope.async(dispatcher) { build(key.track, key.rules, key.profile) }.also {
            entries[key] = it
            // An evicted build is not cancelled: a driver may still be waiting for it.
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }

    private companion object {
        /** Two levels on the current track, plus the next track of a tournament. */
        const val DEFAULT_CAPACITY = 3

        /** Far above any build on a built-in track; a stuck build gives way to the EASY driver. */
        val DEFAULT_WAIT = 10.seconds
    }
}
