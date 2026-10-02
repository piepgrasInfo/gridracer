package info.piepgras.gridracer.server.ai

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.planner.AiDriver
import info.piepgras.gridracer.engine.planner.AiTuning
import info.piepgras.gridracer.engine.planner.PlannerProfile
import info.piepgras.gridracer.engine.planner.PlannerTable
import info.piepgras.gridracer.engine.planner.RacePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

/**
 * The planning tables behind the NORMAL and BEST computer drivers, for every room on this server.
 *
 * A table is built on this service's own threads, never under a room's mutex (netcode invariant
 * 3): a room [request]s the tables its computer seats will need as soon as it knows them - when a
 * seat is added or changed, the track changes, the gravel trap is switched, a race starts - and
 * by the time a computer seat has to move, a [lookup] usually finds the table finished. When it
 * does not, the room waits for it outside its mutex; see `Room.advanceRace`.
 *
 * Tables are kept for the last [capacity] (track, rules, level) combinations, so rooms racing the
 * same built-in track share one.
 *
 * @param parallelism How many tables may build at once; the rest queue.
 * @param maxNodes The largest table built; a larger track is raced by the EASY driver instead.
 * @param build Builds one table; replaceable so tests can count or stall builds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiPlannerService(
    parallelism: Int = DEFAULT_PARALLELISM,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxNodes: Int = AiTuning.MAX_NODES,
    private val build: (Track, RaceRules, PlannerProfile, Int) -> PlannerTable? = { track, rules, profile, nodes ->
        RacePlanner.build(track, rules, profile, nodes)
    },
) {
    private val log = LoggerFactory.getLogger(AiPlannerService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(parallelism))

    private data class Key(val track: Track, val rules: RaceRules, val profile: PlannerProfile)

    /** Most recently used last. Guarded by itself. */
    private val entries = LinkedHashMap<Key, Deferred<PlannerTable?>>(capacity + 1, 0.75f, true)

    /** What [lookup] found. */
    sealed interface Lookup {
        /** The level plans nothing; it drives without a table. */
        data object NotNeeded : Lookup

        /** The build is done. [table] is null when the track was too large to plan. */
        data class Ready(val table: PlannerTable?) : Lookup

        /** The build is still running; [job] completes with the table. */
        data class Pending(val job: Deferred<PlannerTable?>) : Lookup
    }

    /** Starts building the table a [level] seat needs on [track] under [rules], unless it exists. Never blocks. */
    fun request(track: Track, rules: RaceRules, level: AiLevel) {
        val profile = AiDriver.profileFor(level) ?: return
        jobFor(Key(track, rules, profile))
    }

    /** The table a [level] seat drives by, if it is ready - starting its build if nobody has. Never blocks. */
    fun lookup(track: Track, rules: RaceRules, level: AiLevel): Lookup {
        val profile = AiDriver.profileFor(level) ?: return Lookup.NotNeeded
        val job = jobFor(Key(track, rules, profile))
        if (!job.isCompleted) return Lookup.Pending(job)
        return Lookup.Ready(runCatching { job.getCompleted() }.getOrNull())
    }

    /** Stops every build in progress; for server shutdown and tests. */
    fun close() {
        scope.cancel()
    }

    private fun jobFor(key: Key): Deferred<PlannerTable?> = synchronized(entries) {
        entries[key] ?: scope.async {
            val started = System.nanoTime()
            val table = runCatching { build(key.track, key.rules, key.profile, maxNodes) }
                .onFailure { log.warn("Planning {} failed", key.track.name, it) }
                .getOrNull()
            log.info(
                "Planned {} (gravel={}, top speed {}): {} states in {} ms",
                key.track.name,
                key.rules.gravelTrap,
                key.profile.maxSpeed,
                table?.size ?: "too many",
                (System.nanoTime() - started) / 1_000_000,
            )
            table
        }.also {
            entries[key] = it
            // An evicted build is not cancelled: a room may still be waiting for it.
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }

    private companion object {
        /** Builds take a core each; two leaves the rest of the server its CPU. */
        const val DEFAULT_PARALLELISM = 2

        /** A few tracks' worth of NORMAL and BEST tables. */
        const val DEFAULT_CAPACITY = 16
    }
}
