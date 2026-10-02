package info.piepgras.gridracer

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.TrackDraft
import info.piepgras.gridracer.multiplayer.AccountInfo
import info.piepgras.gridracer.multiplayer.ClientMessage
import info.piepgras.gridracer.multiplayer.ErrorCode
import info.piepgras.gridracer.multiplayer.MultiplayerFrameSerializer
import info.piepgras.gridracer.multiplayer.RatingsPage
import info.piepgras.gridracer.multiplayer.PROTOCOL_VERSION
import info.piepgras.gridracer.multiplayer.RoomSummary
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.multiplayer.TournamentStanding
import info.piepgras.gridracer.multiplayer.applyDelta
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Current connection state of the multiplayer client. */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Reconnecting,
    Connected,
    Error,
}

/** Which track-sharing operation is awaiting a server response, for error routing. */
private enum class PendingTrackOp { NONE, UPLOAD, DOWNLOAD, RATE }

/**
 * How long [playerId] has to move before the server takes their turn from them - not
 * necessarily the local player; this is broadcast to everyone in the room so the running clock
 * is visible whoever is on it.
 *
 * The server has always force-forfeited an unanswered turn, and the client had no way to know
 * that clock existed - `TurnReminder.secondsRemaining` was on the wire from the start and never
 * filled in. [receivedAtMillis] is when the deadline arrived, so the UI can count down from it
 * without the server having to send a tick.
 */
data class TurnDeadline(
    /** The player whose turn it is. */
    val playerId: Int,
    /** Seconds they had left when the deadline was sent. */
    val secondsRemaining: Int,
    /** When this device received it, in epoch millis - the zero point for counting down. */
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

/** Snapshot of a server-hosted tournament. */
data class TournamentInfo(
    /** The itinerary, in racing order. */
    val tracks: List<Track>,
    /** Laps per race. */
    val laps: Int,
    /** Seats in the room. */
    val maxPlayers: Int,
    /** Index of the track raced next. */
    val currentTrackIndex: Int,
    /** Running standings, highest points first. */
    val standings: List<TournamentStanding>,
    /** Whether every race is done. */
    val isFinished: Boolean = false,
)

/**
 * The account this connection is authenticated as, plus the session token to reuse.
 *
 * Carried out of [MultiplayerClient] so the app can persist both: the token goes to
 * `Identify` on future connections, the [account] drives the account screen and the
 * "logged in as" indicator.
 */
data class AccountSession(
    /** The logged-in account. */
    val account: AccountInfo,
    /** The session token to present to `Identify` on the next connection. */
    val sessionToken: String,
)

/**
 * WebSocket client that talks to the GridRacer multiplayer server.
 *
 * It owns the connection, parses incoming [ServerMessage]s, exposes game state as
 * reactive flows, and automatically reconnects after transient disconnects so a
 * backgrounded or roaming device can resume the same race.
 */
class MultiplayerClient(
    private val serverUrl: String,
    private val serializer: MultiplayerFrameSerializer,
    private val scope: CoroutineScope,
    private val initialToken: String? = null,
    private val onTokenAssigned: (String) -> Unit = {},
    /**
     * Called when the server attributes this connection to an account (on `Identify` after a
     * session token resolves, or on `Authenticated` after a register/login). Receives null on
     * logout or when the connection is anonymous. The app persists the session token and
     * display name from this callback.
     */
    private val onAccountChanged: (AccountSession?) -> Unit = {},
) {
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 15_000
        }
    }

    /**
     * Whether this client talks to its server over TLS (`wss://`).
     *
     * Decided by the address alone, and that is enough: CIO checks the certificate chain and the
     * host name during the handshake, so a `wss://` connection that reaches
     * [ConnectionState.Connected] is both encrypted and talking to the server it names. CIO does
     * its own TLS on raw sockets, so Android's cleartext policy never applies - `ws://` is allowed
     * here on purpose, for self-hosted and LAN servers, and the UI warns about it instead.
     */
    val isEncrypted: Boolean = isEncryptedUrl(serverUrl)

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    /** Where the WebSocket connection stands. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /**
     * The latest error: one of the `ERROR_*` / `AUTH_ERROR_*` codes below when the app raised it,
     * or the server's or an exception's own text, passed through untranslated.
     */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _kicked = MutableStateFlow<String?>(null)
    /** Non-null when the server has kicked this client. The UI shows a dialog and returns to setup. */
    val kicked: StateFlow<String?> = _kicked.asStateFlow()

    private val _room = MutableStateFlow<ServerMessage.RoomJoined?>(null)
    /** The room this client is seated in - code, seat and host flag - or null. */
    val room: StateFlow<ServerMessage.RoomJoined?> = _room.asStateFlow()

    private val _lobby = MutableStateFlow<ServerMessage.LobbyUpdate?>(null)
    /** The room's lobby state as the server last sent it, or null outside a lobby. */
    val lobby: StateFlow<ServerMessage.LobbyUpdate?> = _lobby.asStateFlow()

    private val _placement = MutableStateFlow<ServerMessage.PlacementStarted?>(null)
    /** Latest placement state, either from PlacementStarted or PlacementUpdate. */
    val placement: StateFlow<ServerMessage.PlacementStarted?> = _placement.asStateFlow()

    private val _configs = MutableStateFlow<List<PlayerConfig>>(emptyList())
    /** The seats of the race or placement in progress, in player-id order. */
    val configs: StateFlow<List<PlayerConfig>> = _configs.asStateFlow()

    private val _race = MutableStateFlow<RaceFrame?>(null)
    /** The latest race state with the events of its last transition, or null outside a race. */
    val race: StateFlow<RaceFrame?> = _race.asStateFlow()

    private val _turnDeadline = MutableStateFlow<TurnDeadline?>(null)
    /** Non-null while a race turn's clock is running, whoever's turn it is. See [TurnDeadline]. */
    val turnDeadline: StateFlow<TurnDeadline?> = _turnDeadline.asStateFlow()

    // Tournament and rating flows.
    private val _tournament = MutableStateFlow<TournamentInfo?>(null)
    /** The room's tournament as the server last described it, or null. */
    val tournament: StateFlow<TournamentInfo?> = _tournament.asStateFlow()

    private val _onlineRatings = MutableStateFlow<RatingsPage?>(null)
    /** The leaderboard page last requested, or null. */
    val onlineRatings: StateFlow<RatingsPage?> = _onlineRatings.asStateFlow()

    private val _serverFeatures = MutableStateFlow<Set<String>>(emptySet())
    /** What the connected server said it supports, from [ServerMessage.Welcome]. */
    val serverFeatures: StateFlow<Set<String>> = _serverFeatures.asStateFlow()

    private val _account = MutableStateFlow<AccountInfo?>(null)
    /** The account this connection is logged in as, or null when anonymous. */
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    /** The most recent auth rejection, for the account screen to show. Cleared on success. */
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _accountDeleted = MutableStateFlow(false)
    /**
     * Set when the server confirms an account deletion, so the account screen can say so.
     * One-shot: the UI clears it with [clearAccountDeleted] once it has been shown.
     */
    val accountDeleted: StateFlow<Boolean> = _accountDeleted.asStateFlow()

    // Track sharing flows (Phase 7).
    private val _sharedTrackList = MutableStateFlow<ServerMessage.TrackList?>(null)
    /** The shared-track page last requested, or null. */
    val sharedTrackList: StateFlow<ServerMessage.TrackList?> = _sharedTrackList.asStateFlow()

    private val _downloadedTrack = MutableStateFlow<ServerMessage.TrackDownloaded?>(null)
    /** The shared track last downloaded, or null. */
    val downloadedTrack: StateFlow<ServerMessage.TrackDownloaded?> = _downloadedTrack.asStateFlow()

    private val _trackUploadResult = MutableStateFlow<ServerMessage.TrackUploaded?>(null)
    /** The server's confirmation of the last upload, or null. */
    val trackUploadResult: StateFlow<ServerMessage.TrackUploaded?> = _trackUploadResult.asStateFlow()

    private val _trackUploadError = MutableStateFlow<String?>(null)
    /** Error from the last track upload attempt, or null if no error. Cleared on next upload. */
    val trackUploadError: StateFlow<String?> = _trackUploadError.asStateFlow()

    private val _trackDownloadError = MutableStateFlow<String?>(null)
    /** Error from the last track download attempt, or null if no error. Cleared on next download. */
    val trackDownloadError: StateFlow<String?> = _trackDownloadError.asStateFlow()

    private val _trackRated = MutableStateFlow<ServerMessage.TrackRated?>(null)
    /** Result of the last track-rating attempt: the rater's own value plus the new aggregate. */
    val trackRated: StateFlow<ServerMessage.TrackRated?> = _trackRated.asStateFlow()

    private val _trackRateError = MutableStateFlow<String?>(null)
    /** Error from the last track-rating attempt, or null if no error. Cleared on next rate. */
    val trackRateError: StateFlow<String?> = _trackRateError.asStateFlow()

    /** Tracks which track-sharing operation is pending so [ServerMessage.Error] can be routed. */
    private var pendingTrackOp: PendingTrackOp = PendingTrackOp.NONE

    /**
     * Set while a [ClientMessage.DeleteAccount] is in flight, so a [ServerMessage.Error] answer
     * to it reaches the delete dialog instead of the generic multiplayer error banner - the
     * same reason [pendingTrackOp] exists.
     */
    private var pendingAccountDeletion = false

    // ---- Race result / host promotion -------------------------------------------------
    private val _raceResult = MutableStateFlow<ServerMessage.RaceFinished?>(null)
    /** Final standings of the race just finished, or null. */
    val raceResult: StateFlow<ServerMessage.RaceFinished?> = _raceResult.asStateFlow()

    private val _raceReplay = MutableStateFlow<info.piepgras.gridracer.replay.ReplayData?>(null)
    /** The replay of the race just finished, sent by the server for saving, or null. */
    val raceReplay: StateFlow<info.piepgras.gridracer.replay.ReplayData?> = _raceReplay.asStateFlow()

    private val _hostPromoted = MutableStateFlow<ServerMessage.HostPromoted?>(null)
    /** Set when the server makes this client the room's host. */
    val hostPromoted: StateFlow<ServerMessage.HostPromoted?> = _hostPromoted.asStateFlow()

    private var session: io.ktor.websocket.WebSocketSession? = null
    private var receiveJob: Job? = null

    private var token: String? = initialToken
    /** Session token for a logged-in account, set by [register]/[login] or carried in from settings. */
    private var sessionToken: String? = null
    private var identified = false
    private var closed = false
    private var reconnectAttempts = 0

    /** When the last [requestResync] went out, so a burst of gaps costs one request. */
    private var lastResyncRequestMillis = 0L

    /**
     * Messages queued while the handshake is in flight or the socket is momentarily gone.
     *
     * Guarded because it is touched from three places on different threads: the UI thread via
     * [sendOrQueue], the receive loop via [flushPending], and [close]. It was a bare
     * `mutableListOf`, which is a lost message or a ConcurrentModificationException waiting for
     * the right timing - and a lost message here is a move the server never sees, after which
     * the turn timer forfeits the player's turn for them.
     */
    private val pendingLock = Any()
    private val pending = mutableListOf<ClientMessage>()

    /** Open a WebSocket to [serverUrl] and identify with the server. */
    suspend fun connect() {
        if (session != null) return
        closed = false
        val isReconnect = reconnectAttempts > 0
        _connectionState.value = if (isReconnect) ConnectionState.Reconnecting else ConnectionState.Connecting
        // Only clear the error on a fresh connect (not on retries): a reconnect that fails
        // again should keep showing the original error message in the status label rather
        // than flickering between the error and "Reconnecting…".
        if (!isReconnect) _error.value = null
        try {
            val newSession = client.webSocketSession(serverUrl)
            session = newSession
            receiveJob = scope.launch { receiveLoop(newSession) }
            // Hello first: it tells the server which protocol we speak, so a version
            // mismatch surfaces as one clear message instead of every later message
            // quietly failing to decode.
            sendFrame(ClientMessage.Hello())
            // Prefer the session token (a logged-in account) over the anonymous player token.
            // The server resolves a session token to an account and replies with it in
            // Identified; an anonymous token or null mints a fresh anonymous identity.
            sendFrame(ClientMessage.Identify(sessionToken ?: token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // On the first failure, show the error. On subsequent retries, stay in
            // Reconnecting so the label does not flicker between Error and Reconnecting.
            if (!isReconnect) {
                _connectionState.value = ConnectionState.Error
                _error.value = e.message ?: ERROR_CONNECT
            }
            if (!closed) startReconnect()
        }
    }

    /**
     * Stop reconnecting, synchronously. [close] is a suspend function, so callers that
     * launch it leave a window in which a pending [startReconnect] can fire one more round
     * against a client nobody is observing any more; calling this first shuts that window.
     */
    fun markClosed() {
        closed = true
    }

    /**
     * Close the WebSocket and stop automatic reconnects. Also releases the engine, which
     * makes the client single-use - build a new one to connect again (a changed server URL
     * needs that anyway, since [serverUrl] is fixed at construction).
     */
    suspend fun close() {
        closed = true
        identified = false
        reconnectAttempts = 0
        val old = session
        flushPendingDirect(old)
        session = null
        receiveJob?.cancel()
        receiveJob = null
        try {
            old?.close()
        } catch (_: Throwable) { }
        // Closing only the session leaks the CIO thread pool and selector behind this
        // HttpClient - which matters now that switching the race setup screen in and out
        // of an online mode builds one client per toggle.
        runCatching { client.close() }
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun flushPendingDirect(s: io.ktor.websocket.WebSocketSession?) {
        val drained = drainPending()
        s ?: return
        for (message in drained) {
            val bytes = runCatching { serializer.encodeClientMessage(message) }.getOrNull() ?: continue
            runCatching { s.outgoing.send(Frame.Binary(true, bytes)) }
        }
    }

    /** Fetch the public lobby list over HTTP. */
    suspend fun fetchRooms(): List<RoomSummary> {
        val httpUrl = serverUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .replace("/play", "/rooms")
        return try {
            val response = client.get(httpUrl)
            val text = response.bodyAsText()
            serializer.decodeRoomSummaries(text)
        } catch (e: Throwable) {
            _error.value = e.message ?: ERROR_ROOM_LIST
            emptyList()
        }
    }

    /** Create a room on the connected server, with the gravel trap if [gravelTrap] (a server without it ignores it). */
    fun host(track: Track, laps: Int, maxPlayers: Int, hostName: String, password: String? = null, gravelTrap: Boolean = false) {
        sendOrQueue(ClientMessage.Host(track, laps, maxPlayers, hostName, password?.ifBlank { null }, gravelTrap = gravelTrap))
    }

    /** Join a room by its short code. */
    fun join(roomCode: String, playerName: String, password: String? = null) {
        sendOrQueue(ClientMessage.Join(roomCode, playerName, password?.ifBlank { null }))
    }

    /**
     * Re-join a room this client was already seated in, using the persistent player token from
     * [initialToken]/[setSessionToken] rather than joining as a new player.
     *
     * This is the same message a live client sends itself after an unexpected disconnect (see
     * the `Identified` handling below), just invoked from a fresh client - a cold app start
     * that finds a saved multiplayer race in progress has no live `_room` to reconnect from, so
     * the room code has to come from outside (see `GameViewModel.restoreRaceInProgress`).
     */
    fun reconnectToRoom(roomCode: String) {
        sendOrQueue(ClientMessage.Reconnect(roomCode))
    }

    /** Toggle the local player's ready flag in the lobby. */
    fun setReady(ready: Boolean) {
        sendOrQueue(ClientMessage.SetReady(ready))
    }

    /** Host-only: start the placement/race phase. */
    fun startRace() {
        sendOrQueue(ClientMessage.StartRace)
    }

    /** Pick a start slot during the remote placement phase. */
    fun choosePlacement(revision: Int, slot: Point) {
        sendOrQueue(ClientMessage.ChoosePlacement(revision, slot))
    }

    /** Submit a chosen target point for the current turn. */
    fun chooseMove(revision: Int, target: Point) {
        sendOrQueue(ClientMessage.ChooseMove(revision, target))
    }

    /** Leave the current room. */
    fun leave() {
        sendOrQueue(ClientMessage.Leave)
    }

    /**
     * Leaves the room and closes the connection, in that order, waiting for the departure to
     * actually go out.
     *
     * The caller used to do `leave(); close()`, and [leave] only *schedules* a send on
     * [scope] - so [close], which tears down the whole HTTP engine, usually won for the race.
     * The server then saw a bare socket drop rather than a departure, and treated a player who
     * had deliberately quit as one who might come back: their seat stayed occupied, and if
     * they were the host, the room was handed on rather than closed. Both are right for a
     * dropped connection and wrong for someone who pressed Leave.
     */
    suspend fun leaveAndClose() {
        val session = this.session
        if (session != null) {
            runCatching {
                val bytes = serializer.encodeClientMessage(ClientMessage.Leave)
                session.outgoing.send(Frame.Binary(true, bytes))
            }.onFailure { android.util.Log.w("MultiplayerClient", "Sending Leave failed", it) }
        }
        close()
    }

    /** Watch an in-progress room without participating. */
    fun spectate(roomCode: String) {
        sendOrQueue(ClientMessage.Spectate(roomCode))
    }

    /** Create a new tournament room with a list of tracks. */
    fun hostTournament(
        tracks: List<Track>,
        laps: Int,
        maxPlayers: Int,
        hostName: String,
        password: String? = null,
        gravelTrap: Boolean = false,
    ) {
        sendOrQueue(ClientMessage.HostTournament(tracks, laps, maxPlayers, hostName, password?.ifBlank { null }, gravelTrap = gravelTrap))
    }

    /** Request the current global ratings table. */
    fun requestLeaderboard(offset: Int = 0, limit: Int = 50) {
        sendOrQueue(ClientMessage.RequestLeaderboard(offset, limit))
    }

    /**
     * Set the session token to present on the next [connect]. Called once at construction
     * time from the persisted settings so a reconnecting logged-in player is recognised.
     */
    fun setSessionToken(token: String?) {
        sessionToken = token
    }

    /** Register a new account. The server replies with [ServerMessage.Authenticated] or [ServerMessage.AuthRejected]. */
    fun register(username: String, password: String, displayName: String? = null) {
        _authError.value = null
        sendOrQueue(ClientMessage.Register(username, password, displayName))
    }

    /** Log in to an existing account. The server replies with [ServerMessage.Authenticated] or [ServerMessage.AuthRejected]. */
    fun login(username: String, password: String) {
        _authError.value = null
        sendOrQueue(ClientMessage.Login(username, password))
    }

    /** Log out and invalidate the current session. The server replies with an anonymous [ServerMessage.Identified]. */
    fun logout() {
        sendOrQueue(ClientMessage.Logout)
    }

    /**
     * Permanently delete the logged-in account. The server re-checks [password] before doing
     * anything, and replies with [ServerMessage.AccountDeleted] or [ServerMessage.AuthRejected].
     */
    fun deleteAccount(password: String) {
        _authError.value = null
        _accountDeleted.value = false
        pendingAccountDeletion = true
        sendOrQueue(ClientMessage.DeleteAccount(password))
    }

    /** Clears the one-shot deletion flag once the screen has acknowledged it. */
    fun clearAccountDeleted() {
        _accountDeleted.value = false
    }

    // ---- Track sharing (Phase 7) ------------------------------------------------------

    /** Upload the current editor draft as a shared track. Requires an authenticated account. */
    fun uploadTrack(name: String, draft: TrackDraft) {
        _trackUploadResult.value = null
        _trackUploadError.value = null
        pendingTrackOp = PendingTrackOp.UPLOAD
        sendOrQueue(ClientMessage.UploadTrack(name, draft))
    }

    /** Request a page of shared tracks. The server replies with [ServerMessage.TrackList]. */
    fun listTracks(offset: Int = 0, limit: Int = 50) {
        sendOrQueue(ClientMessage.ListTracks(offset, limit))
    }

    /** Download a shared track by its public id. The server replies with [ServerMessage.TrackDownloaded]. */
    fun downloadTrack(trackId: String) {
        _downloadedTrack.value = null
        _trackDownloadError.value = null
        pendingTrackOp = PendingTrackOp.DOWNLOAD
        sendOrQueue(ClientMessage.DownloadTrack(trackId))
    }

    /** Report a shared track for review. */
    fun reportTrack(trackId: String, reason: String) {
        sendOrQueue(ClientMessage.ReportTrack(trackId, reason))
    }

    /**
     * Rate a shared track 1..5. Re-rating replaces the prior rating. Requires an
     * authenticated account; the server replies with [ServerMessage.TrackRated] or an
     * [ServerMessage.Error] (NOT_AUTHENTICATED / ROOM_NOT_FOUND / INVALID_INPUT).
     */
    fun rateTrack(trackId: String, rating: Int) {
        _trackRated.value = null
        _trackRateError.value = null
        pendingTrackOp = PendingTrackOp.RATE
        sendOrQueue(ClientMessage.RateTrack(trackId, rating))
    }

    /** Clear the host-promoted notification after the UI has shown it. */
    fun clearHostPromoted() {
        _hostPromoted.value = null
    }

    /** Host-only: remove a seat from the lobby - a human or an AI driver. */
    fun kickPlayer(playerId: Int) {
        sendOrQueue(ClientMessage.KickPlayer(playerId))
    }

    /**
     * Host-only: seat an AI driver in the next free slot.
     *
     * No seat index: the server picks, because this client's view of which seats are free is
     * a snapshot a join or a departure may already have invalidated.
     *
     * @param level How well it drives. Send EASY to a server without [ServerFeature.AI_LEVELS]:
     *     that is what it seats whatever is asked.
     */
    fun addAiPlayer(level: AiLevel = AiLevel.EASY) {
        sendOrQueue(ClientMessage.AddAiPlayer(level))
    }

    /** Host-only: change how well the computer seat [playerId] drives, while in the lobby. */
    fun changeAiLevel(playerId: Int, level: AiLevel) {
        sendOrQueue(ClientMessage.ChangeAiLevel(playerId, level))
    }

    /** Host-only: switch the gravel trap for the room's races, while in the lobby. */
    fun changeGravelTrap(enabled: Boolean) {
        sendOrQueue(ClientMessage.ChangeGravelTrap(enabled))
    }

    /** Host-only: change the track of a non-tournament room while in the lobby. */
    fun changeTrack(track: Track) {
        sendOrQueue(ClientMessage.ChangeTrack(track))
    }

    /** Host-only: replace the track list of a tournament room while in the lobby. */
    fun changeTournamentTracks(tracks: List<Track>) {
        sendOrQueue(ClientMessage.ChangeTournamentTracks(tracks))
    }

    /** Host-only: change the lap count while in the lobby. */
    fun changeLaps(laps: Int) {
        sendOrQueue(ClientMessage.ChangeLaps(laps))
    }

    /** Host-only: change how many seats the room has while in the lobby. */
    fun changeMaxPlayers(maxPlayers: Int) {
        sendOrQueue(ClientMessage.ChangeMaxPlayers(maxPlayers))
    }

    /** Host-only: change the per-turn move timeout (seconds) while in the lobby. */
    fun changeTurnTimeoutSeconds(seconds: Int) {
        sendOrQueue(ClientMessage.ChangeTurnTimeoutSeconds(seconds))
    }

    /** Host-only: after a race finishes, bring all players back to the lobby. */
    fun returnToLobby() {
        sendOrQueue(ClientMessage.ReturnToLobby)
    }

    /** Clear the kicked notification after the UI has shown it. */
    fun clearKicked() {
        _kicked.value = null
    }

    // --------------------------------------------------------------------------------------------
    // Private helpers
    // --------------------------------------------------------------------------------------------

    private fun sendOrQueue(message: ClientMessage) {
        if (identified) {
            sendFrame(message)
        } else {
            enqueue(message)
        }
    }

    /**
     * Writes [message] to the socket, or queues it if there is no socket right now.
     *
     * The `session == null` case used to `return` - dropping the message on the floor. A
     * `ChooseMove` sent in the window between the socket dying and `identified` flipping false
     * simply vanished, and the player was then force-forfeited by the server's turn timer for
     * a move they had made. Queueing costs nothing and the reconnect flushes it.
     */
    private fun sendFrame(message: ClientMessage) {
        val s = session
        if (s == null) {
            enqueue(message)
            return
        }
        scope.launch {
            try {
                val bytes = serializer.encodeClientMessage(message)
                s.outgoing.send(Frame.Binary(true, bytes))
            } catch (e: Throwable) {
                _error.value = e.message ?: ERROR_SEND
            }
        }
    }

    private fun enqueue(message: ClientMessage) {
        synchronized(pendingLock) {
            // A queue that grows without bound is a memory leak on a connection that never
            // comes back. The oldest entries are also the least worth keeping: a stale move is
            // for a turn that has since been forfeited.
            //
            // removeAt(0), not removeFirst(): against compileSdk 35+ the latter resolves to
            // the java.util.List member Android only added in API 35, not the Kotlin
            // extension, so it is a NoSuchMethodError on every device below minSdk 35 - and
            // Play's pre-review scan rejects the bundle for it.
            if (pending.size >= MAX_PENDING_MESSAGES) pending.removeAt(0)
            pending.add(message)
        }
    }

    private fun drainPending(): List<ClientMessage> = synchronized(pendingLock) {
        val drained = pending.toList()
        pending.clear()
        drained
    }

    private fun flushPending() {
        drainPending().forEach { sendFrame(it) }
    }

    private suspend fun receiveLoop(s: io.ktor.websocket.WebSocketSession) {
        try {
            for (frame in s.incoming) {
                val parsed = when (frame) {
                    is Frame.Binary -> runCatching {
                        serializer.decodeServerMessage(frame.readBytes())
                    }.getOrNull()
                    is Frame.Text -> runCatching {
                        serializer.decodeServerMessage(frame.readText())
                    }.getOrNull()
                    else -> null
                }
                if (parsed != null) handleServerMessage(parsed)
            }
        } catch (e: CancellationException) {
            // normal close
        } catch (e: Throwable) {
            _error.value = e.message ?: ERROR_CONNECTION
        } finally {
            session = null
            identified = false
            // The reply this was waiting for is never arriving now; leaving it set would route
            // some later, unrelated error into the delete dialog.
            pendingAccountDeletion = false
            if (!closed) {
                _connectionState.value = ConnectionState.Disconnected
                startReconnect()
            }
        }
    }

    /**
     * Internal rather than private so a unit test can drive one decoded message through the
     * client without a socket - the account-deletion reply, in particular, has to clear four
     * pieces of state and a persisted token, and that is worth asserting directly.
     */
    internal fun handleServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
                _serverFeatures.value = message.features
                if (PROTOCOL_VERSION < message.minSupportedVersion) {
                    _error.value = ERROR_PROTOCOL_TOO_OLD
                }
            }

            // A message from a newer server than this build knows about. Dropped on
            // purpose - see MultiplayerFrameSerializer for why it decodes at all.
            is ServerMessage.Unknown -> Unit

            is ServerMessage.Identified -> {
                val account = message.account
                token = message.token
                onTokenAssigned(message.token)
                identified = true
                _connectionState.value = ConnectionState.Connected
                _error.value = null
                reconnectAttempts = 0
                // The server tells us whether the token resolved to an account.
                _account.value = account
                if (account != null) {
                    onAccountChanged(AccountSession(account, message.token))
                } else {
                    onAccountChanged(null)
                }
                // Try to resume an existing room first, then flush any host/join calls.
                _room.value?.let { room ->
                    synchronized(pendingLock) { pending.add(0, ClientMessage.Reconnect(room.roomCode)) }
                }
                flushPending()
            }
            is ServerMessage.Authenticated -> {
                val account = message.account
                sessionToken = message.sessionToken
                _account.value = account
                _authError.value = null
                onAccountChanged(AccountSession(account, message.sessionToken))
            }
            is ServerMessage.AuthRejected -> {
                pendingAccountDeletion = false
                _authError.value = message.reason.name
            }
            is ServerMessage.AccountDeleted -> {
                pendingAccountDeletion = false
                // The account is gone and so is its session token; the server has already
                // issued the anonymous token this connection continues under.
                sessionToken = null
                token = message.token
                onTokenAssigned(message.token)
                _account.value = null
                _authError.value = null
                onAccountChanged(null)
                _accountDeleted.value = true
            }
            is ServerMessage.RoomJoined -> {
                _tournament.value = null
                _room.value = message
                _lobby.value = null
                _placement.value = null
                _race.value = null
            }
            is ServerMessage.Reconnected -> {
                _tournament.value = null
                _room.value = ServerMessage.RoomJoined(message.roomCode, message.playerId, message.isHost)
                _lobby.value = null
                _placement.value = null
                _race.value = null
            }
            is ServerMessage.LobbyUpdate -> {
                _lobby.value = message
            }
            is ServerMessage.PlacementStarted -> {
                _placement.value = message
                _configs.value = message.placement.configs
                _race.value = null
            }
            is ServerMessage.PlacementUpdate -> {
                _placement.value = ServerMessage.PlacementStarted(message.placement, message.laps, message.maxPlayers)
                _configs.value = message.placement.configs
            }
            is ServerMessage.RaceStarted -> {
                _configs.value = message.configs
                _race.value = RaceFrame(message.state, emptyList())
                _placement.value = null
                _raceResult.value = null
                _raceReplay.value = null
                _turnDeadline.value = null
            }
            is ServerMessage.StateSync -> {
                val previous = _race.value?.state
                val events = previous?.let { diffEvents(it, message.state) } ?: emptyList()
                _race.value = RaceFrame(message.state, events)
                _configs.value = message.configs
                refreshTurnDeadline(message.state)
            }
            is ServerMessage.RaceDelta -> {
                val previous = _race.value?.state
                // Two ways this delta cannot be used: no base state at all (we missed
                // RaceStarted), or a base at the wrong revision (we missed a delta). Both used
                // to be shrugged off - the first silently, the second by applying the delta
                // anyway, which left a wrong board carrying the server's revision number and
                // no way for anyone to notice. Ask for the truth instead.
                val next = previous?.applyDelta(message.delta)
                if (next == null) {
                    requestResync(
                        if (previous == null) {
                            "a race delta arrived before any full state"
                        } else {
                            "race delta ${message.delta.revision} does not apply to revision ${previous.revision}"
                        }
                    )
                } else {
                    _race.value = RaceFrame(next, diffEvents(previous, next))
                    _configs.value = message.configs
                    refreshTurnDeadline(next)
                }
            }
            is ServerMessage.PlacementDelta -> {
                val previous = _placement.value?.placement
                val next = previous?.applyDelta(message.delta)
                if (next == null) {
                    requestResync(
                        if (previous == null) {
                            "a placement delta arrived before any full state"
                        } else {
                            "placement delta ${message.delta.revision} does not apply to revision ${previous.revision}"
                        }
                    )
                } else {
                    _placement.value = ServerMessage.PlacementStarted(next, message.laps, message.maxPlayers)
                }
            }
            is ServerMessage.SeatAssigned -> {
                // The room renumbered our seat. Everything downstream keys off _room.value's
                // playerId - which row is "you", whose turn it is - so this correction has to
                // land before the next LobbyUpdate is rendered against it.
                _room.value = ServerMessage.RoomJoined(message.roomCode, message.playerId, message.isHost)
            }
            is ServerMessage.TurnReminder -> {
                // The server sends a full StateSync to the current player right before
                // this reminder, so _race.value should already be up to date. If it's
                // null or the current player doesn't match, we have diverged - ask for a
                // full state rather than sit on a board we know is wrong.
                val race = _race.value
                if (race == null || race.state.currentPlayer.id != message.playerId) {
                    requestResync(
                        "TurnReminder for player ${message.playerId} but race state is " +
                            if (race == null) "null" else "at player ${race.state.currentPlayer.id}"
                    )
                }
                // The deadline the server will enforce. Broadcast to every player and
                // spectator (not just whoever is on the clock), so the countdown is visible
                // to everyone at the table - see [TurnDeadline].
                _turnDeadline.value = message.secondsRemaining
                    ?.takeIf { it > 0 }
                    ?.let { TurnDeadline(playerId = message.playerId, secondsRemaining = it) }
            }
            is ServerMessage.Error -> {
                // Only the two refusals a deletion can actually produce are claimed here, so
                // an unrelated error arriving in the same window still reaches the banner it
                // belongs to. A code, not the server's prose: the dialog shows a translated
                // message.
                if (pendingAccountDeletion && message.code in DELETE_ACCOUNT_ERRORS) {
                    pendingAccountDeletion = false
                    _authError.value = when (message.code) {
                        ErrorCode.ALREADY_IN_ROOM -> AUTH_ERROR_IN_ROOM
                        else -> AUTH_ERROR_DELETE_FAILED
                    }
                    return
                }
                // Route track-sharing errors to their dedicated flows so the UI can show
                // targeted feedback instead of a generic multiplayer error dialog.
                when (pendingTrackOp) {
                    PendingTrackOp.UPLOAD -> {
                        _trackUploadError.value = message.message
                        pendingTrackOp = PendingTrackOp.NONE
                    }
                    PendingTrackOp.DOWNLOAD -> {
                        _trackDownloadError.value = message.message
                        pendingTrackOp = PendingTrackOp.NONE
                    }
                    PendingTrackOp.RATE -> {
                        _trackRateError.value = message.message
                        pendingTrackOp = PendingTrackOp.NONE
                    }
                    PendingTrackOp.NONE -> _error.value = message.message
                }
            }
            is ServerMessage.Kicked -> {
                // The host kicked us. Set the kicked flow (not the error flow) so the UI can
                // show a "You were kicked" dialog instead of a generic connection error, then
                // clear room state — the connection is about to close.
                _kicked.value = message.reason
                _room.value = null
                _lobby.value = null
                _placement.value = null
                _race.value = null
                _tournament.value = null
                _raceResult.value = null
                _raceReplay.value = null
                _turnDeadline.value = null
            }
            is ServerMessage.RoomClosed -> {
                _error.value = message.reason
                _room.value = null
                _lobby.value = null
                _placement.value = null
                _race.value = null
                _tournament.value = null
                _raceResult.value = null
                _raceReplay.value = null
                _turnDeadline.value = null
                scope.launch { close() }
            }
            is ServerMessage.RaceFinished -> {
                _raceResult.value = message
                _race.value = null
                _turnDeadline.value = null
            }
            is ServerMessage.RaceReplay -> {
                _raceReplay.value = message.replay
            }
            is ServerMessage.HostPromoted -> {
                // Update the room state so isHost is true for this client.
                _room.value?.let { current ->
                    _room.value = current.copy(isHost = true)
                }
                _hostPromoted.value = message
            }
            is ServerMessage.RoomList -> { /* HTTP path uses [fetchRooms] instead */ }
            is ServerMessage.LeaderboardUpdate -> _onlineRatings.value = message.page
            // The shipped setup screen has no ranked mode, so this client never queues; the
            // server still supports matchmaking for clients that do.
            is ServerMessage.MatchmakingJoined, is ServerMessage.MatchmakingUpdate -> Unit
            is ServerMessage.TournamentStarted -> {
                _tournament.value = TournamentInfo(
                    tracks = message.tracks,
                    laps = message.laps,
                    maxPlayers = message.maxPlayers,
                    currentTrackIndex = 0,
                    standings = message.standings,
                )
            }
            is ServerMessage.TournamentUpdate -> {
                _tournament.value = _tournament.value?.copy(
                    currentTrackIndex = message.currentTrackIndex,
                    standings = message.standings,
                )
            }
            is ServerMessage.TournamentFinished -> {
                _tournament.value = _tournament.value?.copy(
                    currentTrackIndex = _tournament.value?.tracks?.size ?: 0,
                    standings = message.standings,
                    isFinished = true,
                ) ?: TournamentInfo(
                    tracks = emptyList(),
                    laps = 0,
                    maxPlayers = 0,
                    currentTrackIndex = 0,
                    standings = message.standings,
                    isFinished = true,
                )
            }
            // ---- Track sharing (Phase 7) ------------------------------------------------
            is ServerMessage.TrackUploaded -> {
                pendingTrackOp = PendingTrackOp.NONE
                _trackUploadResult.value = message
            }
            is ServerMessage.TrackList -> _sharedTrackList.value = message
            is ServerMessage.TrackDownloaded -> {
                pendingTrackOp = PendingTrackOp.NONE
                _downloadedTrack.value = message
            }
            is ServerMessage.TrackReported -> { /* UI can show a toast; no state to update */ }
            is ServerMessage.TrackRated -> {
                pendingTrackOp = PendingTrackOp.NONE
                _trackRated.value = message
            }
        }
    }

    /**
     * Asks the server for a full state after detecting a gap in the delta stream.
     *
     * Rate-limited locally as well as on the server: a burst of undeliverable deltas would
     * otherwise produce a burst of requests, and the answer to each is a whole [GameState].
     * One request per [RESYNC_MIN_INTERVAL_MS] is enough - the reply repairs every gap at once.
     */
    private fun requestResync(why: String) {
        android.util.Log.w("MultiplayerClient", "Requesting a resync: $why")
        val now = System.currentTimeMillis()
        if (now - lastResyncRequestMillis < RESYNC_MIN_INTERVAL_MS) return
        lastResyncRequestMillis = now
        sendOrQueue(ClientMessage.RequestResync)
    }

    private fun startReconnect() {
        if (closed) return
        _connectionState.value = ConnectionState.Reconnecting
        reconnectAttempts++
        val delayMs = (500L * reconnectAttempts).coerceAtMost(15_000L)
        scope.launch {
            delay(delayMs)
            if (!closed) {
                connect()
            }
        }
    }

    /**
     * Drops the turn deadline once the turn has moved on.
     *
     * The server only sends a [ServerMessage.TurnReminder] when a turn *starts*, so nothing
     * would otherwise clear a countdown that is no longer running - and a stale clock ticking
     * down during someone else's turn is worse than no clock at all.
     */
    private fun refreshTurnDeadline(state: info.piepgras.gridracer.engine.GameState) {
        val current = _turnDeadline.value ?: return
        if (state.currentPlayer.id != current.playerId) _turnDeadline.value = null
    }

    companion object {
        /**
         * Whether [serverUrl] names a TLS-protected (`wss://`) server. Used for the warning shown
         * before connecting, when there is no [MultiplayerClient] to ask yet.
         */
        fun isEncryptedUrl(serverUrl: String): Boolean = serverUrl.trim().startsWith("wss://", ignoreCase = true)

        /**
         * Codes for the failures the app raises itself, published on [error] in place of
         * English prose so the UI can translate them (`ui.localizedMultiplayerError`). A
         * message that came from the server, or an exception's own text, travels on the same
         * channel unchanged - there is no resource to map it to.
         */
        const val ERROR_CONNECT = "gridracer.error.connect"
        /** The room list could not be fetched. */
        const val ERROR_ROOM_LIST = "gridracer.error.roomList"
        /** A message could not be sent. */
        const val ERROR_SEND = "gridracer.error.send"
        /** The connection to the server failed or dropped. */
        const val ERROR_CONNECTION = "gridracer.error.connection"
        /** The server no longer accepts this app's protocol version; the app needs updating. */
        const val ERROR_PROTOCOL_TOO_OLD = "gridracer.error.protocolTooOld"

        /**
         * Auth-flow codes that are not [info.piepgras.gridracer.multiplayer.AuthRejection]
         * values: an account deletion can also be refused because the caller is still in a
         * room, or fail for a reason this build has no name for. Same shape as the `ERROR_*`
         * codes above - the UI maps them, nothing here is shown to a user.
         */
        const val AUTH_ERROR_IN_ROOM = "gridracer.account.inRoom"
        /** Account deletion failed for a reason this build has no specific code for. */
        const val AUTH_ERROR_DELETE_FAILED = "gridracer.account.deleteFailed"

        /** The error codes a [ClientMessage.DeleteAccount] can come back with. */
        private val DELETE_ACCOUNT_ERRORS = setOf(ErrorCode.ALREADY_IN_ROOM, ErrorCode.NOT_AUTHENTICATED)

        /**
         * Cap on the offline send queue. Without one, a connection that never comes back grows
         * it forever. Dropping the oldest is right: a stale move is for a turn the server has
         * already forfeited.
         */
        internal const val MAX_PENDING_MESSAGES = 64

        /** One resync request per gap burst - each answer is a whole GameState. */
        internal const val RESYNC_MIN_INTERVAL_MS = 2_000L
    }
}
