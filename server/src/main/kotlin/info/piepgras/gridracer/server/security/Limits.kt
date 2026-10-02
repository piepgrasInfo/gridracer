package info.piepgras.gridracer.server.security

import info.piepgras.gridracer.multiplayer.AccountRules
import info.piepgras.gridracer.multiplayer.TournamentRules

/**
 * Every bound the server enforces, in one place.
 *
 * Before this existed the only numeric check in the entire server was `desiredPlayers in 2..4`,
 * which meant a single client could host a room with a million walls, a lap count of
 * Int.MAX_VALUE, and ten thousand AI players - each of which the server would then broadcast
 * to every participant on every state update.
 */
object Limits {

    // ---- Names -------------------------------------------------------------------------

    /** Display names: room host/player names and, later, account display names. */
    const val DISPLAY_NAME_MIN_CODE_POINTS = 1
    /** Longest display name, in Unicode code points; shared with the app via [AccountRules]. */
    const val DISPLAY_NAME_MAX_CODE_POINTS = AccountRules.DISPLAY_NAME_MAX_CODE_POINTS

    /**
     * Secondary bounds in UTF-16 units and UTF-8 bytes. At 24 code points these are implied
     * (24 astral code points is exactly 48 UTF-16 units and at most 96 UTF-8 bytes), so they
     * never bind on their own today - they are here so raising the code-point limit cannot
     * silently blow up storage or a fixed-width column.
     */
    const val DISPLAY_NAME_MAX_UTF16 = 48
    /** UTF-8 byte ceiling for a display name; see [DISPLAY_NAME_MAX_UTF16]. */
    const val DISPLAY_NAME_MAX_UTF8_BYTES = 96

    /** More than this many stacked combining marks is Zalgo, not a name. */
    const val MAX_CONSECUTIVE_COMBINING_MARKS = 2

    // ---- Usernames ---------------------------------------------------------------------

    /**
     * Account usernames: ASCII letters/digits/underscore, must start with a letter, 4..11
     * characters. The tighter ceiling keeps usernames readable in the track-info authorship
     * line and the leaderboard.
     *
     * Defined in the shared module so the app can show and pre-check the same rule. They were
     * server-only, the app kept its own copy, and the two drifted - the sign-up form promised
     * "3-24 characters" while registration refused anything outside 4-11.
     */
    const val USERNAME_MIN_LENGTH = AccountRules.USERNAME_MIN_LENGTH
    /** Longest username, in characters; see [USERNAME_MIN_LENGTH]. */
    const val USERNAME_MAX_LENGTH = AccountRules.USERNAME_MAX_LENGTH

    // ---- Passwords ---------------------------------------------------------------------

    /** Account passwords. The upper bound guards against pathological inputs to PBKDF2. */
    const val PASSWORD_MIN_LENGTH = AccountRules.PASSWORD_MIN_LENGTH
    /** Longest password, in characters; see [PASSWORD_MIN_LENGTH]. */
    const val PASSWORD_MAX_LENGTH = AccountRules.PASSWORD_MAX_LENGTH

    // ---- Rooms -------------------------------------------------------------------------

    /** Fewest seats a room may have. */
    const val MIN_PLAYERS = 2
    /** Most seats a room may have. */
    const val MAX_PLAYERS = 4

    /** A turn-based race is hundreds of turns per lap, so the ceiling is low on purpose. */
    const val MIN_LAPS = 1
    /** Most laps a race may be set to; see [MIN_LAPS]. */
    const val MAX_LAPS = 99

    /**
     * How long a player has to submit a move before the server forfeits their turn,
     * host-editable from the lobby. Matches the three values the lobby's timeout chips offer
     * (5s/10s/20s) - the bound exists to reject anything else a client might send, not to
     * describe a wider range the UI doesn't actually expose.
     */
    const val MIN_TURN_TIMEOUT_SECONDS = 5
    /** Longest per-turn time limit; see [MIN_TURN_TIMEOUT_SECONDS]. */
    const val MAX_TURN_TIMEOUT_SECONDS = 20

    /** Shared with the app - see [TournamentRules]. */
    const val MIN_TOURNAMENT_TRACKS = TournamentRules.MIN_TRACKS
    /** Most tracks in one tournament; see [MIN_TOURNAMENT_TRACKS]. */
    const val MAX_TOURNAMENT_TRACKS = TournamentRules.MAX_TRACKS

    /** Rooms the server holds at once; hosting beyond this is refused with `SERVER_BUSY`. */
    const val MAX_TOTAL_ROOMS = 500

    /** Spectators one room accepts; each receives every broadcast, so the count is bounded. */
    const val MAX_SPECTATORS_PER_ROOM = 20

    // ---- Room codes --------------------------------------------------------------------

    /**
     * 5 characters over a 34-symbol alphabet is ~45 million codes, against ~1.3 million at 4.
     * Codes are public (they are listed by /rooms) so this is not a secret - it just stops a
     * room being stumbled into.
     */
    const val ROOM_CODE_LENGTH = 5

    // ---- Track payloads ----------------------------------------------------------------

    /**
     * Applied to hosted tracks, not just uploads. A hosted track is rebroadcast to every
     * player and spectator on every lobby update and every full state sync, so an oversized
     * one is an amplification vector.
     */
    const val MAX_WALLS = 4000
    /** Most hazard zones a track may carry. */
    const val MAX_MODIFIERS = 32
    /** Largest track width or height, in coarse units. */
    const val MAX_TRACK_DIMENSION = 500
    /** Largest [info.piepgras.gridracer.engine.Track.subdivision] a track may use. */
    const val MAX_SUBDIVISION = 10

    // ---- Transport ---------------------------------------------------------------------

    /**
     * Was Long.MAX_VALUE. The largest legitimate frame is a tournament of 8 tracks; ktor
     * closes anything larger with TOO_BIG, turning a possible OOM into a clean close.
     */
    const val MAX_FRAME_BYTES = 512L * 1024

    /**
     * Concurrent WebSocket connections accepted from one address.
     *
     * Every rate budget keyed by connection is bypassed by opening another socket, so without
     * a cap the per-IP budgets are the only thing standing between the server and a client
     * that opens ten thousand of them. Generous enough for a household with several devices
     * (and for a player with the app open twice), low enough that socket exhaustion is not
     * reachable from one address.
     */
    const val MAX_CONNECTIONS_PER_IP = 16

    /** Total concurrent connections. A hard ceiling so the server degrades rather than dies. */
    const val MAX_TOTAL_CONNECTIONS = 2000

    // ---- Idle reaping ------------------------------------------------------------------

    /** A room whose humans have all dropped is held this long for a reconnect, then reaped by `Room.idleReason`. */
    const val EMPTY_LOBBY_GRACE_MILLIS = 2 * 60 * 1000L
    /** A lobby with no activity for this long is reaped. */
    const val IDLE_LOBBY_MILLIS = 30 * 60 * 1000L
    /** A race with no activity for this long is reaped. */
    const val IDLE_RACE_MILLIS = 15 * 60 * 1000L

    /** A queued player who disconnects without leaving would otherwise stay queued forever. */
    const val MATCHMAKING_TTL_MILLIS = 5 * 60 * 1000L
}
