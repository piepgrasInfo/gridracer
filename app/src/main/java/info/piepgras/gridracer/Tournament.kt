package info.piepgras.gridracer

import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.multiplayer.TournamentRules
import kotlinx.serialization.Serializable

/** The bounds both peers agree on - see [TournamentRules] for why they are shared. */
const val MIN_TOURNAMENT_TRACKS = TournamentRules.MIN_TRACKS
/** Most tracks a tournament may hold; see [MIN_TOURNAMENT_TRACKS]. */
const val MAX_TOURNAMENT_TRACKS = TournamentRules.MAX_TRACKS

/**
 * One seat's running total in a local tournament.
 *
 * @property playerSlot The seat's player id, the same in every race of the tournament.
 * @property name The seat's name.
 * @property isAi Whether an AI drives the seat.
 * @property points Points so far, from [info.piepgras.gridracer.engine.Scoring.pointsForRank].
 */
@Serializable
data class TournamentStanding(
    val playerSlot: Int,
    val name: String,
    val isAi: Boolean,
    val points: Int = 0,
)

/**
 * A multi-track championship: the same [configs] race every track in [tracks] (in order,
 * repeats allowed), with [Scoring.pointsForRank] tallied into [standings] after each one.
 * Orchestrated entirely in the app module (see [GameViewModel]) - it's a wrapper around
 * repeated single-race engine calls, not a new game rule, so it doesn't belong in `shared`.
 */
@Serializable
data class Tournament(
    /** The itinerary, in racing order; repeats allowed. */
    val tracks: List<Track>,
    /** The seats, in player-id order, the same for every race. */
    val configs: List<PlayerConfig>,
    /** Laps per race. */
    val laps: Int,
    /** Index of the track being raced, or just raced; past the end once the last race is over. */
    val currentTrackIndex: Int = 0,
    /** Running standings, highest points first. */
    val standings: List<TournamentStanding>,
    /**
     * Whether every race of the tournament is played with the gravel trap on. False for a
     * tournament saved before the trap existed, which then carries on without it.
     */
    val gravelTrap: Boolean = false,
) {
    /** The rules each race of the tournament is played under. */
    val rules: RaceRules get() = RaceRules.current(gravelTrap = gravelTrap)

    /** Whether the current track is the final one. */
    val isLastTrack: Boolean get() = currentTrackIndex >= tracks.size - 1

    /**
     * Clamped: [currentTrackIndex] is advanced past the last track when the final race ends
     * (the standings carry it), so reading this on a finished tournament must not be fatal.
     */
    val currentTrack: Track get() = tracks[currentTrackIndex.coerceIn(tracks.indices)]
}
