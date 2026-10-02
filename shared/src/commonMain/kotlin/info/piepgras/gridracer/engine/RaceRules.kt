package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * The rule set a race is played under, fixed when the race starts and carried in
 * [GameState.rules] so every [GameEngine] call - on the server, in the app, in a replay years
 * later - plays the race by the same rules it started with.
 *
 * Its defaults are the rules every race had before the field existed. That is what lets a
 * snapshot, a replay or a server log written by an older build decode and replay exactly as it
 * was played: a recorded move that was legal then is still legal now, because nothing in the
 * stored state asks for the newer rules.
 *
 * @property version Which revision of the core rules applies. 1 is the original engine. 2 counts a
 *     pass over the finish line exactly once (see [GameEngine.applyMove]); under version 1 a move
 *     that ended on the line counted a lap, and the next move off the line forward counted another.
 * @property gravelTrap Whether the run-off band outside the outer boundary is driveable. Off, the
 *     band is scenery and the outer wall stops a car exactly as every other wall does; on, a car
 *     may run into the band and is braked instead of stopped (see [GameEngine.legalMoves]).
 */
@Serializable
data class RaceRules(
    val version: Int = LEGACY_VERSION,
    val gravelTrap: Boolean = false,
) {
    /** Whether a pass over the finish line is counted once, the version 2 way. */
    val countsEachPassOnce: Boolean get() = version >= 2

    companion object {
        /** The rules every race had before rules were recorded. */
        const val LEGACY_VERSION = 1

        /** The newest core-rules revision this build plays; every new race starts on it. */
        const val CURRENT_VERSION = 2

        /** The rules of a race recorded before [GameState.rules] existed. */
        val LEGACY = RaceRules()

        /**
         * The rules a race started now is played under.
         *
         * @param gravelTrap Whether the gravel trap is switched on for this race.
         */
        fun current(gravelTrap: Boolean): RaceRules = RaceRules(version = CURRENT_VERSION, gravelTrap = gravelTrap)
    }
}
