package info.piepgras.gridracer.multiplayer

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlacementState
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.PlayerState
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.TrackDraft
import info.piepgras.gridracer.replay.ReplayData
import kotlinx.serialization.Serializable

/**
 * The wire format both peers speak. Bump when a change is not backward compatible; the server
 * reports its own version and the oldest it still accepts in [ServerMessage.Welcome].
 *
 * 1 - the original protocol, before any version handshake existed.
 * 2 - adds [ClientMessage.Hello]/[ServerMessage.Welcome] and unknown-message tolerance.
 * 3 - adds [ServerMessage.Welcome.serverId]/[serverName], cross-server track UUIDs, track
 *     ratings ([ClientMessage.RateTrack]/[ServerMessage.TrackRated]) and rating fields on
 *     [SharedTrackSummary]. All new fields default so an older client still decodes a v3 frame.
 * 4 - adds [ServerMessage.Kicked] (so a kicked player gets a clear message instead of a
 *     generic connection error), [ClientMessage.ChangeTrack]/
 *     [ClientMessage.ChangeTournamentTracks] (host can swap the track(s) mid-lobby), and
 *     [ClientMessage.ReturnToLobby] (host brings everyone back to the lobby after a race
 *     instead of immediately starting a rematch). All new messages are additive.
 * 5 - closes the silent-desync hole. [RaceDelta.baseRevision]/[PlacementDelta.baseRevision]
 *     say which revision a delta was computed against, so a client that missed one can tell -
 *     before this, a dropped delta was applied to a stale state that then carried the server's
 *     revision number, and the divergence was invisible to both sides. Adds
 *     [ClientMessage.RequestResync] to ask for a full state, and [ServerMessage.SeatAssigned]
 *     so a client learns when its seat is renumbered (which the server has always done on a
 *     kick or a lobby departure, and has never told anyone about).
 *
 *     Every addition defaults, so a v4 peer still decodes a v5 frame and a v5 peer reading a
 *     v4 frame sees `baseRevision == 0` - which it must treat as "unknown", never as a gap.
 *     [MIN_SUPPORTED_PROTOCOL_VERSION] therefore does not move.
 * 6 - adds [ClientMessage.AddAiPlayer], and with it a **behaviour change**: the server no
 *     longer fills empty seats with AI drivers when a race starts. The host adds them
 *     deliberately, so the field they are about to race is the field they can see. A v5 client
 *     talking to a v6 server therefore starts a race with only the seats that are taken - it
 *     has no button to add an AI, but nothing breaks. Additive on the wire.
 * 7 - the lobby settings that used to be fixed for the life of a room become host-editable:
 *     [ClientMessage.ChangeLaps], [ClientMessage.ChangeMaxPlayers] and
 *     [ClientMessage.ChangeTurnTimeoutSeconds], plus [ServerMessage.LobbyUpdate.turnTimeoutSeconds]
 *     so every client can see the room's current per-turn timeout (previously an unstated
 *     server-side constant). A v6 client simply has no controls for any of this and never sees
 *     a room's timeout deviate from the 120s default it already assumed. Additive on the wire.
 * 8 - adds account deletion: [ClientMessage.DeleteAccount] and [ServerMessage.AccountDeleted],
 *     which Google Play requires of any app that lets a user create an account in the first
 *     place. A v7 client simply never sends it, and a v7 server decodes it as `Unknown` and
 *     ignores it - which is why the server also advertises [ServerFeature.ACCOUNT_DELETION]
 *     rather than letting the client infer support from the version. Additive on the wire.
 * 9 - the gravel trap and computer levels. [ClientMessage.Host] and [ClientMessage.HostTournament]
 *     carry `gravelTrap`, [ClientMessage.ChangeGravelTrap] switches it in the lobby, and
 *     [ServerMessage.LobbyUpdate] and [RoomSummary] report it; [ClientMessage.AddAiPlayer] carries
 *     a level, [ClientMessage.ChangeAiLevel] changes one, and every [PlayerConfig] carries its
 *     seat's level. A race's rules travel inside its [GameState], so every new race - gravel or
 *     not - counts a pass over the finish line once (rules version 2). A client below 9 cannot
 *     draw the right moves for a gravel race, so the server keeps it out of gravel rooms, and it
 *     would replay a version-2 race by the old count, so the server does not send it race
 *     replays. Advertised as [ServerFeature.GRAVEL_TRAP] and [ServerFeature.AI_LEVELS]; additive
 *     on the wire - `AddAiPlayer` at its default level encodes exactly as the old object did.
 */
const val PROTOCOL_VERSION = 9

/**
 * Optional capabilities a server advertises in [ServerMessage.Welcome]. A client hides UI for
 * anything the server it is talking to does not list, which is far cheaper than scattering
 * version arithmetic through the screens.
 */
object ServerFeature {
    /** Registration, login and session tokens ([ClientMessage.Register], [ClientMessage.Login]). */
    const val ACCOUNTS = "accounts"
    /** Password-protected rooms ([ClientMessage.Host] with a password). */
    const val PRIVATE_ROOMS = "privateRooms"
    /** The shared track library ([ClientMessage.UploadTrack] and the rest of that family). */
    const val TRACK_SHARING = "trackSharing"
    /** The global Elo leaderboard ([ClientMessage.RequestLeaderboard]). */
    const val LEADERBOARD = "leaderboard"

    /**
     * The server can delete an account on request ([ClientMessage.DeleteAccount]).
     *
     * Separate from [ACCOUNTS] because a server may have accounts and predate deletion: an
     * older build ignores the message silently, and a client that assumed support would show
     * a button that does nothing to the one action a user must be able to trust.
     */
    const val ACCOUNT_DELETION = "accountDeletion"

    /** Rooms can race with the gravel trap ([ClientMessage.ChangeGravelTrap] and the `gravelTrap` fields). */
    const val GRAVEL_TRAP = "gravelTrap"

    /**
     * Computer seats have a level ([ClientMessage.AddAiPlayer] with a level, [ClientMessage.ChangeAiLevel]).
     * An older server ignores the level and seats the original EASY driver, which is why a client only
     * offers the choice when this is listed.
     */
    const val AI_LEVELS = "aiLevels"
}

/**
 * Messages sent by the client to the multiplayer server.
 */
@Serializable
sealed class ClientMessage {
    /**
     * First message a client should send, before [Identify]. Declares which protocol version
     * it speaks so the server can refuse or adapt rather than failing message by message.
     */
    @Serializable
    data class Hello(
        /** The protocol version this client speaks; see [PROTOCOL_VERSION]. */
        val protocolVersion: Int = PROTOCOL_VERSION,
        /**
         * Free-form client build label for the server's logs, e.g. the app's versionName; optional.
         */
        val clientVersion: String? = null,
    ) : ClientMessage()

    /**
     * A message this build does not know. Decoding falls back to this instead of throwing, so
     * a peer speaking a newer protocol degrades to "ignored" rather than killing the frame
     * loop - see MultiplayerFrameSerializer.
     */
    @Serializable
    data object Unknown : ClientMessage()

    /**
     * Sent after [Hello]. [token] is a previously issued persistent player token, or null for a new
     * anonymous player. The server replies with [ServerMessage.Identified] before any room-related
     * flow can continue.
     *
     * @property token The persistent player or session token, or null for a new anonymous player.
     */
    @Serializable
    data class Identify(val token: String?) : ClientMessage()

    /**
     * Create a new room. The host preselects the track, lap count and the maximum number
     * of players (2..4). Room assignment is returned in [ServerMessage.RoomJoined].
     */
    @Serializable
    data class Host(
        /** The track the room will race on. */
        val track: Track,
        /** Laps per race. */
        val laps: Int,
        /** Seats in the room, 2 to 4. */
        val maxPlayers: Int,
        /** The host's display name in the lobby. */
        val hostName: String,
        /** Set to make the room private: joiners must supply the same password. */
        val password: String? = null,
        /** Optional label shown in the public room list instead of just the code. */
        val roomName: String? = null,
        /** Whether races in the room use the gravel trap (protocol 9). */
        val gravelTrap: Boolean = false,
    ) : ClientMessage()

    /** Join an existing room by the short lobby code shown to the host. */
    @Serializable
    data class Join(
        /** The room's short code, as the host shares it. */
        val roomCode: String,
        /** The name to show for this seat. */
        val playerName: String,
        /** Required when the room is password-protected; ignored otherwise. */
        val password: String? = null,
    ) : ClientMessage()

    /**
     * Re-join a room after a transient disconnect using the player token.
     *
     * @property roomCode The room this client was seated in before the connection dropped.
     */
    @Serializable
    data class Reconnect(val roomCode: String) : ClientMessage()

    /**
     * Toggle the local player's ready flag in the lobby.
     *
     * @property ready The new state of this player's ready flag.
     */
    @Serializable
    data class SetReady(val ready: Boolean) : ClientMessage()

    /** Host-only: start the placement/race phase once enough players are ready. */
    @Serializable
    data object StartRace : ClientMessage()

    /**
     * Host-only: remove a seat from the lobby. [playerId] is the seat index.
     *
     * Works on an AI seat as well as a human one. It used to refuse AI seats, which was fine while
     * the server placed them itself at the last moment - but now that the host adds them
     * deliberately, an add with no undo would let one stray tap block a seat for the life of the
     * room.
     *
     * @property playerId The seat to clear.
     */
    @Serializable
    data class KickPlayer(val playerId: Int) : ClientMessage()

    /**
     * Host-only: put an AI driver in one of the room's free seats.
     *
     * The server used to do this silently for every empty seat the instant a race started, so
     * the host never saw the field before committing to it and could not choose to race three
     * cars instead of four. Now the seats sit visibly empty until somebody - a human joining,
     * or the host pressing this - fills them, and whatever is seated at the start is what
     * races.
     *
     * Takes no seat index: the server picks the next free one, because the client's idea of
     * which seats are free is a snapshot that a join or a departure may already have
     * invalidated.
     *
     * A data object until protocol 9. At its default level it encodes exactly as the object did,
     * so an older client's request still reads as this, with the EASY driver it always got.
     *
     * @property level How well the new seat drives.
     */
    @Serializable
    data class AddAiPlayer(val level: AiLevel = AiLevel.EASY) : ClientMessage()

    /**
     * Host-only, lobby-only: change how well the computer seat [playerId] drives. Refused for a
     * seat that is not a computer - the id may have gone to a person since the host looked.
     *
     * @property playerId The computer seat.
     * @property level Its new level.
     */
    @Serializable
    data class ChangeAiLevel(val playerId: Int, val level: AiLevel) : ClientMessage()

    /**
     * Host-only, lobby-only: switch the gravel trap for the room's races. Refused while anyone in
     * the room runs a client too old to play by it.
     *
     * @property enabled Whether the next race uses the gravel trap.
     */
    @Serializable
    data class ChangeGravelTrap(val enabled: Boolean) : ClientMessage()

    /**
     * Host-only: change the track of a non-tournament room while in the lobby. The server validates
     * the track and broadcasts a fresh [ServerMessage.LobbyUpdate]. Only valid in the lobby phase;
     * mid-race changes are refused.
     *
     * @property track The new track; the server validates it before accepting.
     */
    @Serializable
    data class ChangeTrack(val track: Track) : ClientMessage()

    /**
     * Host-only: replace the entire track list of a tournament room while in the lobby (or between
     * tournament races, while in the interstitial lobby phase). The server broadcasts a fresh
     * [ServerMessage.TournamentStarted] so all clients pick up the new itinerary.
     *
     * @property tracks The complete new itinerary, in racing order; see [TournamentRules] for the
     *     bounds.
     */
    @Serializable
    data class ChangeTournamentTracks(val tracks: List<Track>) : ClientMessage()

    /**
     * Host-only: change the lap count of a room while in the lobby. Works for both a plain room and
     * a tournament room - unlike the track itself, laps aren't tied to which track is being raced,
     * so there's no separate tournament variant of this message.
     *
     * @property laps The new lap count.
     */
    @Serializable
    data class ChangeLaps(val laps: Int) : ClientMessage()

    /**
     * Host-only: change how many seats a room has while in the lobby. The server refuses to shrink
     * below the number of seats already occupied - the host has to remove players first.
     *
     * @property maxPlayers The new seat count, 2 to 4 and no fewer than the seats already filled.
     */
    @Serializable
    data class ChangeMaxPlayers(val maxPlayers: Int) : ClientMessage()

    /**
     * Host-only: change how long a player has to submit a move before the server forfeits their
     * turn for them. Previously a fixed server-side constant for every room; now a per-room setting
     * the host can retune from the lobby, e.g. shorter for a quick game between friends who are all
     * watching the clock, or longer for a casual room where players may step away between turns.
     *
     * @property seconds The new per-turn time limit, in seconds.
     */
    @Serializable
    data class ChangeTurnTimeoutSeconds(val seconds: Int) : ClientMessage()

    /**
     * Host-only: after a non-tournament race finishes, bring all players back to the lobby
     * instead of immediately starting a rematch. The server broadcasts a
     * [ServerMessage.LobbyUpdate] so clients leave the results screen and show the lobby.
     */
    @Serializable
    data object ReturnToLobby : ClientMessage()

    /**
     * Submit a chosen target point for the current turn. [revision] is the [GameState.revision]
     * the move is based on; the server rejects stale revisions and resyncs.
     */
    @Serializable
    data class ChooseMove(
        /**
         * The [GameState.revision] the client saw when choosing; a stale one is rejected and
         * resynced.
         */
        val revision: Int,
        /** The coarse-grid point to move to; must be one of [GameEngine.legalMoves]. */
        val target: Point,
    ) : ClientMessage()

    /**
     * Pick a start slot during the remote placement phase. [revision] is the
     * [PlacementState.revision] the pick is based on; the server rejects stale revisions and resyncs.
     */
    @Serializable
    data class ChoosePlacement(
        /**
         * The [PlacementState.revision] the client saw when picking; a stale one is rejected and
         * resynced.
         */
        val revision: Int,
        /** The start slot to take; must be one of [PlacementState.availableSlots]. */
        val slot: Point,
    ) : ClientMessage()

    /**
     * Ask the server for a full [ServerMessage.StateSync] / [ServerMessage.PlacementUpdate]
     * instead of a delta.
     *
     * Sent when the client detects it has missed a delta - see [RaceDelta.baseRevision]. The
     * alternative, and what the client used to do, is to apply the delta anyway and hope; that
     * is precisely how a divergence became invisible. Asking is cheap and self-limiting: a
     * client only asks when it already knows it is wrong.
     */
    @Serializable
    data object RequestResync : ClientMessage()

    /** Leave the room or disconnect gracefully. */
    @Serializable
    data object Leave : ClientMessage()

    /** Enter the ranked matchmaking queue. */
    @Serializable
    data class EnterMatchmaking(
        /** How many players the matched race should have. */
        val desiredPlayers: Int,
        /** A preferred track, or null to let the server choose. */
        val track: Track? = null,
        /** A preferred lap count, or null to let the server choose. */
        val laps: Int? = null,
    ) : ClientMessage()

    /** Leave the ranked matchmaking queue. */
    @Serializable
    data object LeaveMatchmaking : ClientMessage()

    /**
     * Watch an in-progress room without participating.
     *
     * @property roomCode The room to watch.
     */
    @Serializable
    data class Spectate(val roomCode: String) : ClientMessage()

    /** Create a new tournament room with a list of tracks. */
    @Serializable
    data class HostTournament(
        /** The itinerary, in racing order; see [TournamentRules] for the bounds. */
        val tracks: List<Track>,
        /** Laps per race, the same for every track. */
        val laps: Int,
        /** Seats in the room, 2 to 4. */
        val maxPlayers: Int,
        /** The host's display name in the lobby. */
        val hostName: String,
        /** Set to make the room private; joiners must supply the same password. */
        val password: String? = null,
        /** Optional label shown in the public room list instead of just the code. */
        val roomName: String? = null,
        /** Whether every race of the tournament uses the gravel trap (protocol 9). */
        val gravelTrap: Boolean = false,
    ) : ClientMessage()

    /** Request a page of the global leaderboard. The server clamps both bounds. */
    @Serializable
    data class RequestLeaderboard(
        /** Zero-based index of the first row wanted. */
        val offset: Int = 0,
        /** How many rows to return at most; the server caps it. */
        val limit: Int = 50,
    ) : ClientMessage()

    /**
     * Create a new account. On success the server replies with [ServerMessage.Authenticated]
     * carrying a fresh session token; the client should store it and reuse it in [Identify]
     * for future connections. On failure the reply is [ServerMessage.AuthRejected].
     */
    @Serializable
    data class Register(
        /** The login name to register; see [AccountRules.isUsernameValid] for the rules. */
        val username: String,
        /** The new account's password; see [AccountRules] for the length bounds. */
        val password: String,
        /** Optional display name; defaults to the username when omitted or refused. */
        val displayName: String? = null,
    ) : ClientMessage()

    /** Validate credentials and start a session. Replies with [ServerMessage.Authenticated] or [ServerMessage.AuthRejected]. */
    @Serializable
    data class Login(
        /** The account's login name. */
        val username: String,
        /** The account's password, checked against its stored PBKDF2 hash. */
        val password: String,
    ) : ClientMessage()

    /** Invalidate the current session token. No reply beyond a generic ack. */
    @Serializable
    data object Logout : ClientMessage()

    /**
     * Permanently delete the logged-in account and everything stored against it.
     *
     * The password is re-entered rather than taken on the session's word: a session token is a
     * bearer credential that lives for 90 days on the device, and it must not be enough on its own
     * to destroy an account. Replies with [ServerMessage.AccountDeleted] on success, or
     * [ServerMessage.AuthRejected] (`WRONG_PASSWORD`, `RATE_LIMITED`) - and with
     * [ServerMessage.Error] (`ALREADY_IN_ROOM`) if the caller is still seated in a room, whose
     * cached seat identity would otherwise outlive the account.
     *
     * @property password The account's password, re-entered to confirm the deletion.
     */
    @Serializable
    data class DeleteAccount(val password: String) : ClientMessage()

    // ---- Track sharing (Phase 7) -------------------------------------------------------

    /**
     * Upload a custom track for sharing. The client sends both its [draft] (the editor output)
     * and a compiled [track]; the server discards the [track] and rebuilds it from the [draft]
     * so a crafted `Track` whose walls disagree with its draft cannot become a cheat vector.
     *
     * Requires an authenticated account; the uploader's account id is recorded as the author.
     */
    @Serializable
    data class UploadTrack(
        /** The name the track is listed under. */
        val name: String,
        /** The editor output; the server validates and rebuilds the track from it. */
        val draft: TrackDraft,
    ) : ClientMessage()

    /** List shared tracks, newest first. */
    @Serializable
    data class ListTracks(
        /** Zero-based index of the first track wanted. */
        val offset: Int = 0,
        /** How many tracks to return at most; the server caps it. */
        val limit: Int = 50,
    ) : ClientMessage()

    /** Download a shared track by its id, getting back the draft and the server-built track. */
    @Serializable
    data class DownloadTrack(
        /** The shared track's server-side id, from a [SharedTrackSummary]. */
        val trackId: String,
    ) : ClientMessage()

    /**
     * Report a shared track for review. The reporter does not need to be the author; any
     * authenticated player can flag a track they believe is abusive. The server records the
     * report for an operator to act on (takedown is manual for now).
     */
    @Serializable
    data class ReportTrack(
        /** The shared track's server-side id. */
        val trackId: String,
        /** The reporter's free-text reason, for the operator reviewing the report. */
        val reason: String,
    ) : ClientMessage()

    /**
     * Rate a shared track 1..5. Requires an authenticated account; one rating per account per
     * track (a second rating replaces the first). Replies with [ServerMessage.TrackRated].
     */
    @Serializable
    data class RateTrack(
        /** The shared track's server-side id. */
        val trackId: String,
        /** The rating, 1 to 5. */
        val rating: Int,
    ) : ClientMessage()
}

/**
 * Messages sent by the server to the clients.
 */
@Serializable
sealed class ServerMessage {
    /**
     * Answers [ClientMessage.Hello]. [minSupportedVersion] is the oldest protocol the server
     * still accepts; a client below it should tell the user to update rather than carry on
     * and fail one message at a time.
     */
    @Serializable
    data class Welcome(
        /** The protocol version the server speaks. */
        val protocolVersion: Int = PROTOCOL_VERSION,
        /** The oldest client protocol the server still accepts. */
        val minSupportedVersion: Int = PROTOCOL_VERSION,
        /**
         * The [ServerFeature] values this server supports; the client hides anything not listed.
         */
        val features: Set<String> = emptySet(),
        /**
         * Stable identifier for this server deployment (protocol v3). Used by the client to
         * qualify cached track ids and to display authorship as "server @ user". Empty for
         * servers speaking protocol v2.
         */
        val serverId: String = "",
        /** Human-readable server name for authorship display. */
        val serverName: String = "",
    ) : ServerMessage()

    /** See [ClientMessage.Unknown] - a message this build does not know, decoded rather than thrown. */
    @Serializable
    data object Unknown : ServerMessage()

    /**
     * Acknowledges that the client has identified. [token] is the persistent player token
     * the client should store and reuse for reconnects and future authentication. [account]
     * is non-null when the token resolved to a logged-in account, so the client can show the
     * account name and rating without a separate round-trip.
     */
    @Serializable
    data class Identified(
        /**
         * The persistent player token to store and present again on the next
         * [ClientMessage.Identify].
         */
        val token: String,
        /** The logged-in account, or null for an anonymous player. */
        val account: AccountInfo? = null,
    ) : ServerMessage()

    /** A successful [ClientMessage.Register] or [ClientMessage.Login]. */
    @Serializable
    data class Authenticated(
        /** The account that is now logged in. */
        val account: AccountInfo,
        /** Session token to present to [ClientMessage.Identify] on future connections. */
        val sessionToken: String,
    ) : ServerMessage()

    /**
     * A failed [ClientMessage.Register] or [ClientMessage.Login].
     *
     * @property reason Why the request was refused.
     */
    @Serializable
    data class AuthRejected(val reason: AuthRejection) : ServerMessage()

    /**
     * A successful [ClientMessage.DeleteAccount]: the account is gone.
     *
     * [token] is a fresh **anonymous** player token, for the same reason a logout issues one - the
     * session token the client was holding has just been deleted along with the account, and
     * reconnecting with it would be rejected.
     *
     * @property token A fresh anonymous player token to use from now on.
     */
    @Serializable
    data class AccountDeleted(val token: String) : ServerMessage()

    /** Acknowledges that the client has joined a room and gives them their player id. */
    @Serializable
    data class RoomJoined(
        /** The room's short code. */
        val roomCode: String,
        /** This client's seat, which is also its player id in the race. */
        val playerId: Int,
        /** Whether this client holds the room and may use the host-only messages. */
        val isHost: Boolean,
    ) : ServerMessage()

    /** Acknowledges that the client has reconnected to an existing room. */
    @Serializable
    data class Reconnected(
        /** The room rejoined. */
        val roomCode: String,
        /**
         * This client's seat; it may differ from before the disconnect if seats were renumbered.
         */
        val playerId: Int,
        /** Whether this client holds the room. */
        val isHost: Boolean,
    ) : ServerMessage()

    /**
     * Your seat number in this room has changed.
     *
     * The server renumbers seats whenever the roster shrinks - a kick, or someone leaving the
     * lobby - because [GameState] requires player ids to be exactly the list indices. It has
     * always done this and never told anyone: a client went on believing the id it was given
     * when it joined, so after a mid-lobby departure it highlighted the wrong row as "you",
     * offered the ready checkbox for someone else's seat, and during a race could not tell
     * whose turn it was. [RoomJoined] is sent once and cannot carry a correction, which is why
     * this exists separately.
     */
    @Serializable
    data class SeatAssigned(
        /** The room the new seat is in. */
        val roomCode: String,
        /** This client's new seat number. */
        val playerId: Int,
        /** Whether this client holds the room. */
        val isHost: Boolean,
    ) : ServerMessage()

    /**
     * Lobby state update: the seats, their ready flags, the chosen track and the lap count.
     *
     * [players] and [ready] are indexed by seat, and [maxPlayers] is how many seats the room
     * has - so `players.size until maxPlayers` are the empty ones. The lobby draws every seat,
     * filled or not, because "three of four seats are still open" is the thing a host needs to
     * know and cannot otherwise see.
     *
     * [hostPlayerId] and [connected] carry state the client used to have to *infer*: who may
     * press Start, and which seats are actually attached right now. Both were previously
     * spread across separate messages ([HostPromoted], [SeatAssigned]) that a client could
     * miss, leaving it with a stale idea of a room it was looking straight at. Carrying them
     * on the same message as the roster makes that impossible.
     */
    @Serializable
    data class LobbyUpdate(
        /** Every filled seat, indexed by seat. */
        val players: List<PlayerConfig>,
        /** Each filled seat's ready flag, indexed like [players]. */
        val ready: List<Boolean>,
        /** The track the next race will use. */
        val track: Track,
        /** Laps per race. */
        val laps: Int,
        /** How many seats the room has; `players.size until maxPlayers` are empty. */
        val maxPlayers: Int = 0,
        /**
         * How many seconds a player has to submit a move before the server forfeits their
         * turn, host-editable from the lobby (protocol v7, [ClientMessage.ChangeTurnTimeoutSeconds]).
         * Defaults to a new room's starting value (`Room`'s `TURN_TIMEOUT_MS`), so a v6 server
         * (which never sent this field) still round-trips to something valid.
         */
        val turnTimeoutSeconds: Int = 20,
        /**
         * The seat that currently holds the room. -1 only while a room is being torn down; a
         * live room always has exactly one host - see `Room.findAndPromoteHost`.
         */
        val hostPlayerId: Int = 0,
        /**
         * Whether each seat has a live connection, indexed like [players]. A seat can be
         * held by a player who has dropped and is expected back, which is different from an
         * empty seat and from a player who is present but not ready.
         */
        val connected: List<Boolean> = emptyList(),
        /** Whether the room's races use the gravel trap (protocol 9). */
        val gravelTrap: Boolean = false,
    ) : ServerMessage()

    /** Race starts; the initial full [GameState] and configs are provided. */
    @Serializable
    data class RaceStarted(
        /** The race as it starts, positions assigned. */
        val state: GameState,
        /** The seats, in player-id order, for names and AI flags. */
        val configs: List<PlayerConfig>,
    ) : ServerMessage()

    /** Sent after every valid move. Contains the authoritative new [GameState]. */
    @Serializable
    data class StateSync(
        /** The authoritative race state after the move. */
        val state: GameState,
        /** The seats, in player-id order. */
        val configs: List<PlayerConfig>,
    ) : ServerMessage()

    /** The remote placement phase has started; clients should show the placement UI. */
    @Serializable
    data class PlacementStarted(
        /** The placement state as it starts - nobody has picked yet. */
        val placement: PlacementState,
        /** Laps the race will have. */
        val laps: Int,
        /** Seats in the room. */
        val maxPlayers: Int,
    ) : ServerMessage()

    /** Placement state changed (slot chosen or auto-chosen by AI). */
    @Serializable
    data class PlacementUpdate(
        /** The authoritative placement state after the pick. */
        val placement: PlacementState,
        /** Laps the race will have. */
        val laps: Int,
        /** Seats in the room. */
        val maxPlayers: Int,
    ) : ServerMessage()

    /** Optional nudge for the client whose turn it is (may include a turn timer). */
    @Serializable
    data class TurnReminder(
        /** The player whose turn it is. */
        val playerId: Int,
        /** Seconds left before the server forfeits the turn, or null without a turn timer. */
        val secondsRemaining: Int? = null,
    ) : ServerMessage()

    /** Problem with the last client request, or a room-level error. */
    @Serializable
    data class Error(
        /** Human-readable description, passed through to the user untranslated. */
        val message: String,
        /** What kind of failure it is, for clients to react to without parsing [message]. */
        val code: ErrorCode = ErrorCode.UNSPECIFIED,
    ) : ServerMessage()

    /**
     * Room was closed (host left, race finished, or server shutdown).
     *
     * @property reason Human-readable reason, passed through to the user untranslated.
     */
    @Serializable
    data class RoomClosed(val reason: String) : ServerMessage()

    /**
     * You were kicked from the room by the host. Distinct from [RoomClosed] so the client can show
     * a "You were kicked" message and return to the race setup screen instead of treating the
     * disconnect as a generic error. Sent only to the kicked player, immediately before the server
     * closes their connection.
     *
     * @property reason Human-readable reason, passed through to the user untranslated.
     */
    @Serializable
    data class Kicked(val reason: String) : ServerMessage()

    /**
     * A non-tournament race just finished. The room transitions back to the lobby so the
     * host can start a rematch. [standings] carries the final result for the results screen.
     * Sent instead of [RoomClosed] for non-tournament races.
     */
    @Serializable
    data class RaceFinished(
        /** Every player's result, for the results screen. */
        val standings: List<RaceStanding>,
    ) : ServerMessage()

    /**
     * Sent after a race finishes, carrying the full replay for client-side saving.
     *
     * @property replay The complete replay of the race just finished.
     */
    @Serializable
    data class RaceReplay(val replay: ReplayData) : ServerMessage()

    /**
     * You have been promoted to host (the previous host left or disconnected).
     * The client should inform the player and enable host-only actions like starting a race.
     */
    @Serializable
    data class HostPromoted(
        /** The room in which this client is now host. */
        val roomCode: String,
    ) : ServerMessage()

    /**
     * List of open rooms, returned by the HTTP lobby endpoint.
     *
     * @property rooms Every room the list currently shows.
     */
    @Serializable
    data class RoomList(val rooms: List<RoomSummary>) : ServerMessage()

    /**
     * A page of the global leaderboard.
     *
     * @property page The requested slice of the leaderboard.
     */
    @Serializable
    data class LeaderboardUpdate(val page: RatingsPage) : ServerMessage()

    /** Entered the ranked matchmaking queue. */
    @Serializable
    data object MatchmakingJoined : ServerMessage()

    /** Queue size / wait time while matchmaking. */
    @Serializable
    data class MatchmakingUpdate(
        /** How many players are waiting in the queue. */
        val playersInQueue: Int,
        /** A rough wait estimate, or null when the server cannot give one. */
        val estimatedWaitSeconds: Int? = null,
    ) : ServerMessage()

    /** A tournament room was created or joined; gives the full track list. */
    @Serializable
    data class TournamentStarted(
        /** The tournament's itinerary, in racing order. */
        val tracks: List<Track>,
        /** Laps per race. */
        val laps: Int,
        /** Seats in the room. */
        val maxPlayers: Int,
        /** Standings so far; empty before the first race. */
        val standings: List<TournamentStanding> = emptyList(),
    ) : ServerMessage()

    /** Standings update between tournament races. */
    @Serializable
    data class TournamentUpdate(
        /** Index into the itinerary of the track raced next. */
        val currentTrackIndex: Int,
        /** Running standings after the race just finished. */
        val standings: List<TournamentStanding>,
    ) : ServerMessage()

    /** Tournament complete, final standings. */
    @Serializable
    data class TournamentFinished(
        /** Final standings, highest points first. */
        val standings: List<TournamentStanding>,
    ) : ServerMessage()

    /** Compressed race state delta. Apply to the previous [GameState] to reconstruct. */
    @Serializable
    data class RaceDelta(
        /** The changes since the client's last known state; see [RaceDelta.baseRevision]. */
        val delta: info.piepgras.gridracer.multiplayer.RaceDelta,
        /** The seats, in player-id order. */
        val configs: List<PlayerConfig>,
    ) : ServerMessage()

    /** Compressed placement state delta. Apply to the previous [PlacementState]. */
    @Serializable
    data class PlacementDelta(
        /** The changes since the client's last known placement state. */
        val delta: info.piepgras.gridracer.multiplayer.PlacementDelta,
        /** Laps the race will have. */
        val laps: Int,
        /** Seats in the room. */
        val maxPlayers: Int,
    ) : ServerMessage()

    // ---- Track sharing (Phase 7) -------------------------------------------------------

    /** A shared track was uploaded successfully. The [track] is the server-rebuilt one. */
    @Serializable
    data class TrackUploaded(
        /** The new shared track's server-side id. */
        val trackId: String,
        /** The name it is listed under. */
        val name: String,
        /** The track as the server rebuilt it from the uploaded draft. */
        val track: Track,
    ) : ServerMessage()

    /** A page of shared tracks, newest first. */
    @Serializable
    data class TrackList(
        /** This page of summaries, newest first. */
        val tracks: List<SharedTrackSummary>,
        /** Zero-based index of the first summary on this page. */
        val offset: Int,
        /** How many shared tracks the server holds in all. */
        val total: Int,
    ) : ServerMessage()

    /** The draft and server-built track for a download request. */
    @Serializable
    data class TrackDownloaded(
        /** The list entry for the downloaded track. */
        val summary: SharedTrackSummary,
        /** The editable source, so the track can be opened in the editor. */
        val draft: TrackDraft,
        /** The server-built playable track. */
        val track: Track,
    ) : ServerMessage()

    /** A track report was recorded. The server does not auto-takedown; an operator reviews. */
    @Serializable
    data class TrackReported(
        /** The reported track's server-side id. */
        val trackId: String,
    ) : ServerMessage()

    /**
     * A track rating was recorded (or replaced). Carries the rater's own [yourRating], the new
     * [averageRating] across all raters, and the [ratingCount], so the client can update the
     * info screen without a separate list refresh.
     */
    @Serializable
    data class TrackRated(
        /** The rated track's server-side id. */
        val trackId: String,
        /** The rating this client's account has now given, 1 to 5. */
        val yourRating: Int,
        /** The new mean across all raters. */
        val averageRating: Double,
        /** How many accounts have rated the track. */
        val ratingCount: Int,
    ) : ServerMessage()
}

/**
 * Summary of a shared track for the list view.
 *
 * [authorName] is the author's display name; [authorUsername] is their login name. The two
 * are kept distinct so the info screen can show "Server @ username (display name)" and so a
 * copied display name still cannot impersonate another author.
 *
 * [serverId]/[serverName] identify which server the track lives on, so a client that has
 * browsed multiple servers can disambiguate. [ratingCount]/[averageRating] are the aggregate
 * of all 1..5 ratings; both default to 0 for tracks from a server that predates ratings.
 */
@Serializable
data class SharedTrackSummary(
    /** The track's server-side id. */
    val trackId: String,
    /** The name the track is listed under. */
    val name: String,
    /** The author's display name at upload time. */
    val authorName: String,
    /** When the track was uploaded, in epoch millis. */
    val uploadedAt: Long,
    /** The author's login name; empty from servers that predate it. */
    val authorUsername: String = "",
    /** Stable id of the server holding the track. */
    val serverId: String = "",
    /** Human-readable name of that server. */
    val serverName: String = "",
    /** How many accounts have rated the track. */
    val ratingCount: Int = 0,
    /** Mean rating, 1 to 5; 0 when nobody has rated it. */
    val averageRating: Double = 0.0,
    /**
     * The *requesting* account's own rating of this track, or null if they have not rated it
     * (or are not logged in). Resolved per requester, which is why it lives on the summary
     * rather than being derivable from the aggregate.
     *
     * Without it the info screen could only show a rating you had cast in the current session:
     * reopening the app showed "not rated" for a track you had rated months ago, and rating it
     * again silently replaced your own score with the same value.
     */
    val yourRating: Int? = null,
)

/**
 * One player's final result in a multiplayer race, sent in [ServerMessage.RaceFinished].
 * [finishRank] is null for players who crashed or timed out.
 */
@Serializable
data class RaceStanding(
    /** The player's id in the race. */
    val playerId: Int,
    /** The player's display name. */
    val name: String,
    /** Whether an AI drove this seat. */
    val isAi: Boolean,
    /** 1-based finishing position, or null. */
    val finishRank: Int?,
    /** Whether the player crashed out. */
    val crashed: Boolean,
)

/** Snapshot of a room for the lobby list. */
@Serializable
data class RoomSummary(
    /** The room's short code. */
    val roomCode: String,
    /** The host's label for the room, if they gave one. */
    val roomName: String? = null,
    /** The host's display name. */
    val hostName: String,
    /** The track the room races, or the current one in a tournament. */
    val trackName: String,
    /** Laps per race. */
    val laps: Int,
    /** Seats filled. */
    val players: Int,
    /** Seats in total. */
    val maxPlayers: Int,
    /** Whether a race is running, so the room can only be spectated. */
    val inProgress: Boolean,
    /** Whether the room is a tournament. */
    val isTournament: Boolean = false,
    /**
     * Private rooms are still listed - the password is the security boundary, not the code.
     * Room codes are published by /rooms and this design does not pretend otherwise.
     */
    val isPasswordProtected: Boolean = false,
    /** Whether the room races with the gravel trap, which a client below protocol 9 cannot join. */
    val gravelTrap: Boolean = false,
)

/**
 * A logged-in account's public view. [publicId] is the opaque, wire-safe handle (a UUID)
 * that the leaderboard and any future profile feature point at - it is generated
 * independently of every secret and is **never accepted as authentication**.
 *
 * [username] is the login name; [displayName] is what shows in lobbies and the leaderboard.
 */
@Serializable
data class AccountInfo(
    /** Opaque, stable handle; never accepted as authentication. */
    val publicId: String,
    /** Login name. */
    val username: String,
    /** Name shown in lobbies and on the leaderboard. */
    val displayName: String,
    /** Current Elo rating. */
    val rating: Int,
    /** Rated races played. */
    val matches: Int,
    /** Rated races won. */
    val wins: Int,
)

/** Machine-readable reason a [ClientMessage.Register] or [ClientMessage.Login] was refused. */
@Serializable
enum class AuthRejection {
    INVALID_USERNAME,
    INVALID_PASSWORD,
    INVALID_DISPLAY_NAME,
    USERNAME_TAKEN,
    NO_SUCH_USER,
    WRONG_PASSWORD,
    RATE_LIMITED,
}

/**
 * One row of the global leaderboard.
 *
 * [publicId] is a stable, opaque handle for a player - it exists so a client can highlight
 * its own row and so a future profile or report feature has something to point at. It is
 * generated independently of every secret and is **never accepted as authentication**;
 * see MessageRouterTest for the test that holds that line.
 *
 * This type replaced `PlayerRating`, which carried the player's `token` - the same bearer
 * credential `Identify` accepts - and was broadcast to any client that asked.
 */
@Serializable
data class RatingEntry(
    /** Opaque, stable handle for the player; never accepted as authentication. */
    val publicId: String,
    /** The player's display name. */
    val displayName: String,
    /** Current Elo rating. */
    val rating: Int,
    /** Rated races played. */
    val matches: Int,
    /** Rated races won. */
    val wins: Int,
    /** 1-based position on the leaderboard. */
    val rank: Int,
)

/** A slice of the leaderboard, plus the requesting player's own row when they have one. */
@Serializable
data class RatingsPage(
    /** The rows in this slice, highest rating first. */
    val entries: List<RatingEntry> = emptyList(),
    /** Zero-based leaderboard position of the first row. */
    val offset: Int = 0,
    /** How many players the leaderboard holds in all. */
    val totalEntries: Int = 0,
    /** The requesting player's own row, or null when they have none. */
    val you: RatingEntry? = null,
)

/**
 * Machine-readable reason a request was refused, so clients can react to the kind of failure
 * rather than string-matching [ServerMessage.Error.message].
 */
@Serializable
enum class ErrorCode {
    UNSPECIFIED,
    NOT_AUTHENTICATED,
    IDENTIFY_FIRST,
    UNSUPPORTED,
    RATE_LIMITED,
    ROOM_NOT_FOUND,
    ROOM_FULL,
    ROOM_IN_PROGRESS,
    WRONG_ROOM_PASSWORD,
    ROOM_PASSWORD_REQUIRED,
    ALREADY_IN_ROOM,
    NOT_IN_ROOM,
    NOT_HOST,
    INVALID_INPUT,
    PAYLOAD_TOO_LARGE,
    TRACK_INVALID,
    QUOTA_EXCEEDED,
    SERVER_BUSY,
    INTERNAL,
}

/** Tournament standing for a player. */
@Serializable
data class TournamentStanding(
    /** The player's seat in the tournament room. */
    val playerSlot: Int,
    /** The player's display name. */
    val name: String,
    /** Whether an AI drives this seat. */
    val isAi: Boolean,
    /** Tournament points so far, from [Scoring.pointsForRank] per race. */
    val points: Int,
)

/**
 * Delta for [GameState]. All fields except [revision] are optional; null means unchanged.
 *
 * [baseRevision] is what makes a delta safe to apply. Without it, `applyDelta` overwrote the
 * revision with the server's and had no way to notice it had been handed a delta for a state
 * it did not have - so one dropped frame left a client with a wrong board carrying the right
 * revision number, which then sailed through the server's staleness check on the next move.
 */
@Serializable
data class RaceDelta(
    /** The [GameState.revision] after applying this delta. */
    val revision: Int,
    /**
     * The [GameState.revision] this delta was computed against. 0 means "not stated" - a
     * server older than protocol 5 - and must be treated as unknown rather than as a gap.
     */
    val baseRevision: Int = 0,
    /** New [GameState.currentPlayerIndex], or null if unchanged. */
    val currentPlayerIndex: Int? = null,
    /** New [GameState.round], or null if unchanged. */
    val round: Int? = null,
    /** New [GameState.phase], or null if unchanged. */
    val phase: GamePhase? = null,
    /** New [GameState.track], or null if unchanged. */
    val track: Track? = null,
    /** New [GameState.totalLaps], or null if unchanged. */
    val totalLaps: Int? = null,
    /** New [GameState.maxRounds], or null if unchanged. */
    val maxRounds: Int? = null,
    /** Map of changed player slot to the new [PlayerState]. */
    val playerChanges: Map<Int, PlayerState> = emptyMap(),
)

/** Delta for [PlacementState]. All fields except [revision] are optional; null means unchanged. */
@Serializable
data class PlacementDelta(
    /** The [PlacementState.revision] after applying this delta. */
    val revision: Int,
    /** See [RaceDelta.baseRevision]. 0 means "not stated", not "gap". */
    val baseRevision: Int = 0,
    /** New [PlacementState.currentOrderIndex], or null if unchanged. */
    val currentOrderIndex: Int? = null,
    /** Map of changed player slot to the chosen start [Point]. */
    val assignments: Map<Int, Point> = emptyMap(),
    /** New [PlacementState.track], or null if unchanged. */
    val track: Track? = null,
    /** New [PlacementState.configs], or null if unchanged. */
    val configs: List<PlayerConfig>? = null,
    /** New [PlacementState.order], or null if unchanged. */
    val order: List<Int>? = null,
)
