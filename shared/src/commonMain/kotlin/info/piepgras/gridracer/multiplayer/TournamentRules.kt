package info.piepgras.gridracer.multiplayer

/**
 * How long a tournament itinerary may be, defined once for both peers.
 *
 * The app allowed up to 100 tracks while the server refused anything past 8, so a host could
 * build an itinerary the setup screen accepted and the server then rejected with a bare
 * `INVALID_INPUT`. Same shape of drift as the username rules that [AccountRules] fixed, same
 * remedy: the bound lives here and the two sides read it.
 *
 * The ceiling is also what sizes the server's largest legitimate frame
 * (`Limits.MAX_FRAME_BYTES` is budgeted for a tournament of this many tracks), so raising it is
 * a server change first.
 */
object TournamentRules {
    /** Fewer than two tracks is just a race. */
    const val MIN_TRACKS = 2
    /**
     * Most tracks one tournament may hold. The server's frame-size budget is sized for this, so
     * raise that first.
     */
    const val MAX_TRACKS = 8
}
