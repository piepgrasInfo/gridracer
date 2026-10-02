package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.Scoring
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.multiplayer.TournamentStanding

/**
 * The itinerary and running standings of a multiplayer tournament.
 *
 * Lifted out of [Room], where the same fact was stored three times: `trackIndex` and
 * `tournamentState.trackIndex` were kept in lockstep by hand, and `tournamentStandings`
 * mirrored `tournamentState.standings` for no reader. Three variables for one piece of state
 * is three chances to update two of them.
 *
 * A non-tournament room holds one of these too, with a single track - which is what lets
 * [Room] read `currentTrack` without asking whether it is in a tournament first.
 */
internal class RoomTournament(
    tracks: List<Track>,
    private var laps: Int,
    private var maxPlayers: Int,
) {
    /** Where a tournament is between races. Meaningless for a single-race room. */
    enum class Phase { LOBBY, INTERSTITIAL, FINISHED }

    /** The itinerary, in racing order. Host-editable in the lobby via [replaceTracks]. */
    var tracks: List<Track> = tracks
        private set

    /** Index of the track raced next; equal to `tracks.size` once the tournament is over. */
    var trackIndex: Int = 0
        private set

    /** Running standings, highest points first; empty before the first race. */
    var standings: List<TournamentStanding> = emptyList()
        private set

    /** Where the tournament is between races. */
    var phase: Phase = Phase.LOBBY
        private set

    /** The track being raced now. Clamped, so a corrupt index cannot index out of bounds. */
    val currentTrack: Track get() = tracks[trackIndex.coerceIn(tracks.indices)]

    /** True once at least one race has been run, i.e. the itinerary is under way. */
    val hasStarted: Boolean get() = trackIndex > 0

    /** Whether every track has been raced. */
    val isComplete: Boolean get() = trackIndex >= tracks.size

    /** Replaces the whole itinerary and resets to its first track. Host action, lobby only. */
    fun replaceTracks(newTracks: List<Track>) {
        require(newTracks.isNotEmpty()) { "a tournament needs at least one track" }
        tracks = newTracks
        trackIndex = 0
        phase = Phase.LOBBY
    }

    /**
     * Folds a finished race into the standings and advances to the next track.
     *
     * [seats] is the room's roster in seat order; points come from [Scoring.pointsForRank], so
     * a player who did not finish scores zero rather than being dropped from the table.
     */
    fun recordRace(state: GameState, seats: List<TournamentSeat>) {
        val pointsBySlot = standings.associate { it.playerSlot to it.points }
        standings = seats
            .map { seat ->
                val rank = state.players.firstOrNull { it.id == seat.id }?.finishRank
                TournamentStanding(
                    playerSlot = seat.id,
                    name = seat.name,
                    isAi = seat.isAi,
                    points = pointsBySlot.getOrDefault(seat.id, 0) + Scoring.pointsForRank(rank),
                )
            }
            .sortedByDescending { it.points }

        trackIndex++
        phase = if (isComplete) Phase.FINISHED else Phase.INTERSTITIAL
    }

    /** Host retuned laps/seat count from the lobby - see `Room.changeLaps`/`changeMaxPlayers`. */
    fun updateSettings(laps: Int, maxPlayers: Int) {
        this.laps = laps
        this.maxPlayers = maxPlayers
    }

    /** The message announcing the tournament, with its itinerary and current standings. */
    fun startedMessage(): ServerMessage.TournamentStarted =
        ServerMessage.TournamentStarted(tracks, laps, maxPlayers, standings)

    /** The message sent between races, with the next track's index and the standings. */
    fun updateMessage(): ServerMessage.TournamentUpdate =
        ServerMessage.TournamentUpdate(trackIndex, standings)

    /** The message ending the tournament, with the final standings. */
    fun finishedMessage(): ServerMessage.TournamentFinished =
        ServerMessage.TournamentFinished(standings)

    /**
     * The bits of a room seat the standings need. Keeps [Room]'s player type private.
     *
     * @property id The seat's player id.
     * @property name The seat's display name.
     * @property isAi Whether an AI drives the seat.
     */
    data class TournamentSeat(val id: Int, val name: String, val isAi: Boolean)
}
