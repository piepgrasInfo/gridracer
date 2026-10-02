package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.multiplayer.AccountInfo
import info.piepgras.gridracer.multiplayer.AuthRejection
import info.piepgras.gridracer.multiplayer.ClientMessage
import info.piepgras.gridracer.multiplayer.PROTOCOL_VERSION
import info.piepgras.gridracer.multiplayer.ErrorCode
import info.piepgras.gridracer.multiplayer.ServerFeature
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.server.db.AuthService
import info.piepgras.gridracer.server.db.TrackShareService
import info.piepgras.gridracer.server.security.Limits
import info.piepgras.gridracer.server.security.RateKeys
import info.piepgras.gridracer.server.security.RateLimiter
import info.piepgras.gridracer.server.security.Validation
import org.slf4j.LoggerFactory

/**
 * The oldest protocol this server still talks. Raise it only alongside a client release -
 * anything below is told to update rather than left to fail one message at a time.
 */
const val MIN_SUPPORTED_PROTOCOL_VERSION = 2

/**
 * What this build can do, advertised in [ServerMessage.Welcome] so clients can hide the rest.
 *
 * Under-advertising is not harmless: a client hides UI for anything the server does not list,
 * so a working feature left off this set is a feature the user cannot reach. Private rooms and
 * the leaderboard were both implemented and both missing from here.
 */
val SUPPORTED_FEATURES: Set<String> = setOf(
    ServerFeature.ACCOUNTS,
    ServerFeature.TRACK_SHARING,
    ServerFeature.PRIVATE_ROOMS,
    ServerFeature.LEADERBOARD,
    ServerFeature.ACCOUNT_DELETION,
    ServerFeature.GRAVEL_TRAP,
    ServerFeature.AI_LEVELS,
)

/**
 * Turns one decoded [ClientMessage] into server state changes and outbound messages.
 *
 * Lifted out of `Application.kt` so the protocol can be driven from tests with a fake
 * [Connection] - no HTTP, no WebSocket, no ports. Every authorization and validation decision
 * the server makes lives here, which is precisely the part that was previously untestable.
 */
class MessageRouter(
    private val roomManager: RoomManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limiter: RateLimiter = RateLimiter(clock),
    /** This server's stable id, advertised in [ServerMessage.Welcome]. */
    private val serverId: String = "",
    /** This server's display name, advertised in [ServerMessage.Welcome]. */
    private val serverName: String = "",
) {
    private val log = LoggerFactory.getLogger(MessageRouter::class.java)

    /**
     * Dispatches one decoded client message: checks the rate budgets, validates its input, and
     * routes it to the room, the matchmaker, the account service or the track library.
     *
     * Refusals are answered on [connection] with a [ServerMessage.Error] or a typed rejection.
     *
     * @param message The decoded message.
     * @param connection The connection it arrived on, and where any reply goes.
     */
    suspend fun handle(message: ClientMessage, connection: Connection) {
        when (message) {
            is ClientMessage.Hello -> {
                handleHello(message, connection)
                return
            }
            // A message from a newer protocol than this build knows. Answering rather than
            // ignoring means the peer learns its version is unsupported instead of watching
            // messages disappear - see MultiplayerFrameSerializer.
            is ClientMessage.Unknown -> {
                log.debug("Ignoring a message this build does not understand")
                connection.send(ServerMessage.Error("Unsupported message", ErrorCode.UNSUPPORTED))
                return
            }
            is ClientMessage.Identify -> {
                handleIdentify(message, connection)
                return
            }
            is ClientMessage.Register -> {
                handleRegister(message, connection)
                return
            }
            is ClientMessage.Login -> {
                handleLogin(message, connection)
                return
            }
            is ClientMessage.Logout -> {
                handleLogout(connection)
                return
            }
            is ClientMessage.DeleteAccount -> {
                handleDeleteAccount(message, connection)
                return
            }
            else -> Unit
        }
        // Denial drops the message and keeps the socket: a laggy client resyncing bursts
        // legitimately, and kicking it only makes the lag worse.
        if (!allow(RateKeys.all(connection), RateLimiter.ALL_MESSAGES, connection)) return
        // The same allowance again, keyed by address rather than by socket. Without this the
        // per-connection budgets above are advisory: Connection.id is a fresh UUID, so a
        // client that wants more only has to open another socket.
        if (!allow(RateKeys.ip(connection.remoteIp), RateLimiter.IP_ALL_MESSAGES, connection)) return

        val perMessage = budgetFor(message)
        if (perMessage != null) {
            if (!allow(RateKeys.perMessage(connection, message), perMessage, connection)) return
            val ipBudget = ipBudgetFor(message)
            if (ipBudget != null &&
                !allow(RateKeys.ipPerMessage(connection.remoteIp, message), ipBudget, connection)
            ) {
                return
            }
        }

        // The one authorization check the server has today. Note that it is satisfied by
        // Identify(null), and that Spectate is exempt from it entirely.
        if (connection.token == null && message !is ClientMessage.Spectate) {
            connection.send(ServerMessage.Error("Identify first", ErrorCode.IDENTIFY_FIRST))
            return
        }
        dispatch(message, connection)
    }

    /** Tighter budgets for the messages that are expensive or worth guessing at. */
    private fun budgetFor(message: ClientMessage): RateLimiter.Budget? = when (message) {
        is ClientMessage.Host, is ClientMessage.HostTournament -> RateLimiter.HOST
        is ClientMessage.Join, is ClientMessage.Spectate -> RateLimiter.JOIN
        is ClientMessage.RequestLeaderboard -> RateLimiter.LEADERBOARD
        is ClientMessage.ChooseMove, is ClientMessage.ChoosePlacement -> RateLimiter.MOVES
        // A resync is a full state - cheap to ask for, expensive to answer - so it gets its
        // own budget. A correct client asks at most once per gap.
        is ClientMessage.RequestResync -> RateLimiter.RESYNC
        is ClientMessage.UploadTrack -> RateLimiter.UPLOAD_TRACK
        is ClientMessage.ListTracks, is ClientMessage.DownloadTrack -> RateLimiter.TRACK_LIST
        is ClientMessage.ReportTrack -> RateLimiter.REPORT_TRACK
        // Cheap, but it mutates the roster and broadcasts to everyone in the room, so it does
        // not get to be free. A host filling a four-seat room needs three presses.
        is ClientMessage.AddAiPlayer,
        is ClientMessage.ChangeAiLevel,
        is ClientMessage.ChangeGravelTrap,
        is ClientMessage.ChangeLaps,
        is ClientMessage.ChangeMaxPlayers,
        is ClientMessage.ChangeTurnTimeoutSeconds,
        -> RateLimiter.LOBBY_EDIT
        is ClientMessage.RateTrack -> RateLimiter.RATE_TRACK
        else -> null
    }

    /**
     * The per-address twin of [budgetFor], for the messages worth flooding.
     *
     * Deliberately not defined for [ClientMessage.ChooseMove]/[ClientMessage.ChoosePlacement]:
     * four people playing from one household is the normal case, and their moves are already
     * bounded per socket and by whose turn it is. Room creation and track uploads are the ones
     * that cost the *server* something regardless of who sent them.
     */
    private fun ipBudgetFor(message: ClientMessage): RateLimiter.Budget? = when (message) {
        is ClientMessage.Host, is ClientMessage.HostTournament -> RateLimiter.IP_HOST
        is ClientMessage.Join, is ClientMessage.Spectate -> RateLimiter.IP_JOIN
        is ClientMessage.UploadTrack -> RateLimiter.IP_UPLOAD_TRACK
        else -> null
    }

    /** Takes a token, telling the client why when there is none left. Returns false if denied. */
    private suspend fun allow(key: String, budget: RateLimiter.Budget, connection: Connection): Boolean {
        if (limiter.allow(key, budget)) return true
        connection.send(rateLimited(key, budget))
        return false
    }

    private fun rateLimited(key: String, budget: RateLimiter.Budget): ServerMessage.Error {
        val retry = limiter.retryAfterSeconds(key, budget)
        return ServerMessage.Error("Slow down - try again in ${retry}s", ErrorCode.RATE_LIMITED)
    }

    private suspend fun handleHello(message: ClientMessage.Hello, connection: Connection) {
        if (message.protocolVersion < MIN_SUPPORTED_PROTOCOL_VERSION) {
            log.info("Rejecting a client speaking protocol {}", message.protocolVersion)
            connection.send(ServerMessage.Error("Client too old - please update", ErrorCode.UNSUPPORTED))
            return
        }
        // Remembered for the rooms: one racing with the gravel trap keeps out a client that
        // could not draw its moves.
        connection.protocolVersion = message.protocolVersion
        connection.send(
            ServerMessage.Welcome(
                protocolVersion = PROTOCOL_VERSION,
                minSupportedVersion = MIN_SUPPORTED_PROTOCOL_VERSION,
                features = SUPPORTED_FEATURES,
                serverId = serverId,
                serverName = serverName,
            )
        )
    }

    private suspend fun handleIdentify(message: ClientMessage.Identify, connection: Connection) {
        val token = message.token
        // A session token resolves to an account; an anonymous token (or none) does not.
        // The order matters: validate the session first, so a logged-in player's token is
        // never silently demoted to a fresh anonymous one.
        if (token != null) {
            val account = roomManager.auth.validateSession(token)
            if (account != null) {
                connection.token = token
                connection.accountId = account.id
                connection.displayName = account.displayName
                connection.accountPublicId = account.publicId
                connection.send(ServerMessage.Identified(token, account.toInfo()))
                return
            }
        }
        val resolved = when {
            token == null -> roomManager.generateToken()
            roomManager.isKnown(token) -> token
            else -> roomManager.generateToken()
        }
        connection.token = resolved
        connection.accountId = null
        connection.displayName = null
        connection.accountPublicId = null
        connection.send(ServerMessage.Identified(resolved))
    }

    private suspend fun handleRegister(message: ClientMessage.Register, connection: Connection) {
        if (!allowAuth(connection)) return
        when (val result = roomManager.auth.register(message.username, message.password, message.displayName)) {
            is AuthService.Result.Ok -> {
                val success = result.success
                connection.token = success.sessionToken
                connection.accountId = success.account.id
                connection.displayName = success.account.displayName
                connection.accountPublicId = success.account.publicId
                connection.send(ServerMessage.Authenticated(success.account.toInfo(), success.sessionToken))
            }
            is AuthService.Result.Rejected -> connection.send(ServerMessage.AuthRejected(result.reason.toWire()))
        }
    }

    private suspend fun handleLogin(message: ClientMessage.Login, connection: Connection) {
        if (!allowAuth(connection)) return
        when (val result = roomManager.auth.login(message.username, message.password)) {
            is AuthService.Result.Ok -> {
                val success = result.success
                connection.token = success.sessionToken
                connection.accountId = success.account.id
                connection.displayName = success.account.displayName
                connection.accountPublicId = success.account.publicId
                connection.send(ServerMessage.Authenticated(success.account.toInfo(), success.sessionToken))
            }
            is AuthService.Result.Rejected -> connection.send(ServerMessage.AuthRejected(result.reason.toWire()))
        }
    }

    /**
     * Budgets a register/login attempt per socket *and* per address.
     *
     * Per socket alone was no brute-force defence at all: five guesses, then reconnect for
     * five more, at no cost. The per-address budget is the one that actually binds, and it is
     * the reason [Connection.remoteIp] exists.
     */
    private suspend fun allowAuth(connection: Connection): Boolean {
        val allowed = limiter.allow(RateKeys.auth(connection), RateLimiter.AUTH) &&
            limiter.allow(RateKeys.ipAuth(connection.remoteIp), RateLimiter.IP_AUTH)
        if (!allowed) {
            log.warn("Rate-limited an auth attempt from {}", connection.remoteIp)
            connection.send(ServerMessage.AuthRejected(AuthRejection.RATE_LIMITED))
        }
        return allowed
    }

    private suspend fun handleLogout(connection: Connection) {
        val token = connection.token ?: return
        roomManager.auth.logout(token)
        connection.accountId = null
        connection.displayName = null
        connection.accountPublicId = null
        // Issue a fresh anonymous token so the client is not left holding a now-invalid
        // session credential that the server would reject on reconnect.
        val anonymous = roomManager.generateToken()
        connection.token = anonymous
        connection.send(ServerMessage.Identified(anonymous))
    }

    /**
     * Deletes the caller's account, everything stored against it, and its trace in the race
     * logs. Google Play requires an in-app path to this for any app that offers sign-up.
     *
     * On the same auth budget as register and login, because it takes a password and would
     * otherwise be an unmetered oracle for guessing one.
     */
    private suspend fun handleDeleteAccount(message: ClientMessage.DeleteAccount, connection: Connection) {
        val accountId = connection.accountId
        if (accountId == null) {
            connection.send(ServerMessage.Error("Log in before deleting an account", ErrorCode.NOT_AUTHENTICATED))
            return
        }
        if (connection.room != null) {
            // The seat this connection holds caches the account id and would outlive the row.
            // forgetAccount() repairs seats held by *other* connections; for this one, asking
            // the player to leave first is both simpler and clearer than deleting an account
            // out from under a race they are in the middle of.
            connection.send(
                ServerMessage.Error("Leave the race before deleting your account", ErrorCode.ALREADY_IN_ROOM),
            )
            return
        }
        if (!allowAuth(connection)) return
        when (val result = roomManager.auth.deleteAccount(accountId, message.password)) {
            is AuthService.DeleteResult.Ok -> {
                // Any seat this account holds elsewhere becomes a guest seat, and the stored
                // race logs lose the name and handle. Both are best-effort follow-ups: the row
                // is already gone, which is the part the user asked for.
                runCatching { roomManager.forgetAccount(accountId) }
                    .onFailure { log.warn("Clearing seats for a deleted account failed", it) }
                runCatching { roomManager.purgeRaceLogsFor(result.publicId) }
                    .onFailure { log.warn("Scrubbing race logs for a deleted account failed", it) }
                connection.accountId = null
                connection.displayName = null
                connection.accountPublicId = null
                val anonymous = roomManager.generateToken()
                connection.token = anonymous
                log.info("Deleted an account on request from {}", connection.remoteIp)
                connection.send(ServerMessage.AccountDeleted(anonymous))
            }
            AuthService.DeleteResult.WrongPassword ->
                connection.send(ServerMessage.AuthRejected(AuthRejection.WRONG_PASSWORD))
            AuthService.DeleteResult.NoSuchAccount ->
                connection.send(ServerMessage.AuthRejected(AuthRejection.NO_SUCH_USER))
        }
    }

    private fun info.piepgras.gridracer.server.db.Account.toInfo(): AccountInfo = AccountInfo(
        publicId = publicId,
        username = username,
        displayName = displayName,
        rating = rating,
        matches = matches,
        wins = wins,
    )

    private fun AuthService.Rejection.toWire(): AuthRejection = when (this) {
        AuthService.Rejection.Register.INVALID_USERNAME -> AuthRejection.INVALID_USERNAME
        AuthService.Rejection.Register.INVALID_PASSWORD -> AuthRejection.INVALID_PASSWORD
        AuthService.Rejection.Register.INVALID_DISPLAY_NAME -> AuthRejection.INVALID_DISPLAY_NAME
        AuthService.Rejection.Register.USERNAME_TAKEN -> AuthRejection.USERNAME_TAKEN
        AuthService.Rejection.Login.NO_SUCH_USER -> AuthRejection.NO_SUCH_USER
        AuthService.Rejection.Login.WRONG_PASSWORD -> AuthRejection.WRONG_PASSWORD
    }

    // ---- Track sharing (Phase 7) ------------------------------------------------------

    private suspend fun handleUploadTrack(message: ClientMessage.UploadTrack, connection: Connection) {
        if (connection.accountId == null) {
            connection.send(ServerMessage.Error("Login required to upload tracks", ErrorCode.NOT_AUTHENTICATED))
            return
        }
        when (val result = roomManager.uploadTrack(connection, message.name, message.draft)) {
            is TrackShareService.UploadResult.Ok -> {
                connection.send(ServerMessage.TrackUploaded(result.summary.trackId, result.summary.name, result.track))
            }
            is TrackShareService.UploadResult.Rejected -> {
                val code = when (result.reason) {
                    TrackShareService.Rejection.NOT_AUTHENTICATED -> ErrorCode.NOT_AUTHENTICATED
                    TrackShareService.Rejection.QUOTA_EXCEEDED -> ErrorCode.QUOTA_EXCEEDED
                    TrackShareService.Rejection.TRACK_TOO_LARGE -> ErrorCode.PAYLOAD_TOO_LARGE
                    else -> ErrorCode.TRACK_INVALID
                }
                connection.send(ServerMessage.Error("Track rejected: ${result.reason}", code))
            }
        }
    }

    private suspend fun handleListTracks(message: ClientMessage.ListTracks, connection: Connection) {
        val result = roomManager.listTracks(connection, message.offset, message.limit)
        if (result == null) {
            connection.send(ServerMessage.Error("Track sharing is not available", ErrorCode.UNSUPPORTED))
            return
        }
        connection.send(ServerMessage.TrackList(result.tracks, result.offset, result.total))
    }

    private suspend fun handleDownloadTrack(message: ClientMessage.DownloadTrack, connection: Connection) {
        when (val result = roomManager.downloadTrack(connection, message.trackId)) {
            null -> connection.send(ServerMessage.Error("Track sharing is not available", ErrorCode.UNSUPPORTED))
            TrackShareService.DownloadResult.NotFound ->
                connection.send(ServerMessage.Error("Track not found", ErrorCode.ROOM_NOT_FOUND))
            is TrackShareService.DownloadResult.Ok ->
                connection.send(ServerMessage.TrackDownloaded(result.summary, result.draft, result.track))
        }
    }

    private suspend fun handleReportTrack(message: ClientMessage.ReportTrack, connection: Connection) {
        if (connection.accountId == null) {
            connection.send(ServerMessage.Error("Login required to report tracks", ErrorCode.NOT_AUTHENTICATED))
            return
        }
        when (val result = roomManager.reportTrack(connection, message.trackId, message.reason)) {
            TrackShareService.ReportResult.NotAuthenticated ->
                connection.send(ServerMessage.Error("Login required to report tracks", ErrorCode.NOT_AUTHENTICATED))
            TrackShareService.ReportResult.NotFound ->
                connection.send(ServerMessage.Error("Track not found", ErrorCode.ROOM_NOT_FOUND))
            is TrackShareService.ReportResult.Ok ->
                connection.send(ServerMessage.TrackReported(result.trackId))
        }
    }

    private suspend fun handleRateTrack(message: ClientMessage.RateTrack, connection: Connection) {
        if (connection.accountId == null) {
            connection.send(ServerMessage.Error("Login required to rate tracks", ErrorCode.NOT_AUTHENTICATED))
            return
        }
        when (val result = roomManager.rateTrack(connection, message.trackId, message.rating)) {
            TrackShareService.RateResult.NotAuthenticated ->
                connection.send(ServerMessage.Error("Login required to rate tracks", ErrorCode.NOT_AUTHENTICATED))
            TrackShareService.RateResult.NotFound ->
                connection.send(ServerMessage.Error("Track not found", ErrorCode.ROOM_NOT_FOUND))
            TrackShareService.RateResult.InvalidRating ->
                connection.send(ServerMessage.Error("Rating must be between 1 and 5", ErrorCode.INVALID_INPUT))
            is TrackShareService.RateResult.Ok -> connection.send(
                ServerMessage.TrackRated(result.trackId, result.yourRating, result.averageRating, result.ratingCount),
            )
        }
    }

    /**
     * Routes one message to its handler.
     *
     * Deliberately a table: every branch is either a one-liner or a named function, so this
     * reads as the list of things a client may ask for. [withSeat] guards everything that only
     * makes sense for a connection holding a seat in a room.
     */
    private suspend fun dispatch(message: ClientMessage, connection: Connection) {
        val room = connection.room
        val playerId = connection.playerId

        when (message) {
            is ClientMessage.Host -> handleHost(message, connection)
            is ClientMessage.HostTournament -> handleHostTournament(message, connection)
            is ClientMessage.Join -> handleJoin(message, connection)
            is ClientMessage.Spectate -> handleSpectate(message, connection)
            is ClientMessage.EnterMatchmaking -> handleEnterMatchmaking(message, connection)
            is ClientMessage.LeaveMatchmaking -> roomManager.leaveMatchmaking(connection)
            is ClientMessage.RequestLeaderboard ->
                roomManager.requestLeaderboard(connection, message.offset, message.limit)

            // ---- Track sharing -----------------------------------------------------------
            // These do not require a room; they operate on the shared-track store directly.
            is ClientMessage.UploadTrack -> handleUploadTrack(message, connection)
            is ClientMessage.ListTracks -> handleListTracks(message, connection)
            is ClientMessage.DownloadTrack -> handleDownloadTrack(message, connection)
            is ClientMessage.ReportTrack -> handleReportTrack(message, connection)
            is ClientMessage.RateTrack -> handleRateTrack(message, connection)

            is ClientMessage.Reconnect -> {
                val code = Validation.roomCode(message.roomCode)
                val reconnected = code != null && roomManager.reconnectRoom(code, connection)
                if (!reconnected) {
                    connection.send(ServerMessage.Error("Could not reconnect", ErrorCode.ROOM_NOT_FOUND))
                }
            }

            // ---- Seated actions ----------------------------------------------------------
            is ClientMessage.SetReady -> withSeat(connection, room, playerId) { it.setReady(playerId!!, message.ready) }
            is ClientMessage.StartRace -> withSeat(connection, room, playerId) { it.startRace(playerId!!) }
            is ClientMessage.KickPlayer -> withSeat(connection, room, playerId) { it.kickPlayer(playerId!!, message.playerId) }
            is ClientMessage.AddAiPlayer -> withSeat(connection, room, playerId) { it.addAiPlayer(playerId!!, message.level) }
            is ClientMessage.ChangeAiLevel ->
                withSeat(connection, room, playerId) { it.changeAiLevel(playerId!!, message.playerId, message.level) }
            is ClientMessage.ChangeGravelTrap ->
                withSeat(connection, room, playerId) { it.changeGravelTrap(playerId!!, message.enabled) }
            is ClientMessage.ReturnToLobby -> withSeat(connection, room, playerId) { it.returnToLobby(playerId!!) }
            is ClientMessage.ChoosePlacement ->
                withSeat(connection, room, playerId) { it.choosePlacement(playerId!!, message.revision, message.slot) }
            is ClientMessage.ChooseMove ->
                withSeat(connection, room, playerId) { it.chooseMove(playerId!!, message.revision, message.target) }

            // Both carry a client-supplied Track that the server will hold and rebroadcast to
            // every player and spectator on every lobby update, exactly like Host does - so
            // both go through the same size and sanity check Host does. Without it, a host
            // could swap in the million-wall track that Validation.checkTrack exists to refuse.
            is ClientMessage.ChangeTrack -> {
                if (!acceptTrack(message.track, connection)) return
                withSeat(connection, room, playerId) { it.changeTrack(playerId!!, message.track) }
            }
            is ClientMessage.ChangeTournamentTracks -> {
                if (!acceptItinerary(message.tracks, connection)) return
                withSeat(connection, room, playerId) { it.changeTournamentTracks(playerId!!, message.tracks) }
            }
            is ClientMessage.ChangeLaps ->
                withSeat(connection, room, playerId) { it.changeLaps(playerId!!, message.laps) }
            is ClientMessage.ChangeMaxPlayers ->
                withSeat(connection, room, playerId) { it.changeMaxPlayers(playerId!!, message.maxPlayers) }
            is ClientMessage.ChangeTurnTimeoutSeconds ->
                withSeat(connection, room, playerId) { it.changeTurnTimeoutSeconds(playerId!!, message.seconds) }

            // Spectators need this as much as players do - they follow the same delta stream -
            // so it deliberately does not go through `withSeat`.
            is ClientMessage.RequestResync -> {
                if (room == null) {
                    connection.send(ServerMessage.Error("Not in a room", ErrorCode.NOT_IN_ROOM))
                } else {
                    room.resync(connection)
                }
            }

            is ClientMessage.Leave -> {
                when {
                    room != null && playerId == -1 -> {
                        room.removeSpectator(connection)
                        connection.room = null
                        connection.playerId = null
                    }
                    room != null && playerId != null -> {
                        room.leave(playerId)
                        connection.room = null
                        connection.playerId = null
                    }
                }
            }

            is ClientMessage.Hello, is ClientMessage.Unknown, is ClientMessage.Identify,
            is ClientMessage.Register, is ClientMessage.Login, is ClientMessage.Logout,
            is ClientMessage.DeleteAccount -> {
                // Handled in [handle]; unreachable but required for exhaustiveness.
            }
        }
    }

    // ---- Rooms -------------------------------------------------------------------------
    //
    // One function per message rather than one 240-line `when`. The routing table below stays
    // readable as a table, and a reviewer can see a single message's authorization rules
    // without scrolling past nine other messages' worth of them - which is how a bug in the
    // rate-limit keys sat in the middle of that `when` unnoticed.

    private suspend fun handleHost(message: ClientMessage.Host, connection: Connection) {
            if (!canOpenRoom(connection)) return
            if (!acceptTrack(message.track, connection)) return
            val hostName = Validation.displayNameOr(message.hostName, DEFAULT_HOST_NAME)
            val laps = Validation.clampLaps(message.laps)
            val maxPlayers = Validation.clampMaxPlayers(message.maxPlayers)

            connection.token ?: roomManager.generateToken().also { connection.token = it }
            val newRoom = roomManager.createRoom(
                track = message.track,
                laps = laps,
                maxPlayers = maxPlayers,
                hostName = hostName,
                hostConnection = connection,
                password = message.password,
                roomName = message.roomName?.let { Validation.displayNameOr(it, "") }?.ifBlank { null },
                gravelTrap = message.gravelTrap,
            )
            connection.room = newRoom
            connection.playerId = 0
            log.info("Room {} created by host (private={})", newRoom.code, message.password != null)
            connection.send(ServerMessage.RoomJoined(newRoom.code, 0, isHost = true))
        // The room builds its own lobby update rather than the router assembling a lookalike.
        // The hand-built one drifted: it omitted maxPlayers (so a fresh room drew no empty
        // seats and looked full to its own host), and later omitted the host and the
        // connected flags too. There is now one definition of what a lobby update says.
        newRoom.broadcastLobbyUpdate()
    }

    private suspend fun handleHostTournament(message: ClientMessage.HostTournament, connection: Connection) {
            if (!canOpenRoom(connection)) return
            if (!acceptItinerary(message.tracks, connection)) return
            val hostName = Validation.displayNameOr(message.hostName, DEFAULT_HOST_NAME)
            val laps = Validation.clampLaps(message.laps)
            val maxPlayers = Validation.clampMaxPlayers(message.maxPlayers)

            connection.token ?: roomManager.generateToken().also { connection.token = it }
            val newRoom = roomManager.createTournamentRoom(
                tracks = message.tracks,
                laps = laps,
                maxPlayers = maxPlayers,
                hostName = hostName,
                hostConnection = connection,
                password = message.password,
                roomName = message.roomName?.let { Validation.displayNameOr(it, "") }?.ifBlank { null },
                gravelTrap = message.gravelTrap,
            )
            if (newRoom == null) {
                connection.send(ServerMessage.Error("Could not create tournament", ErrorCode.INVALID_INPUT))
                return
            }
            connection.room = newRoom
            connection.playerId = 0
            log.info("Tournament room {} created with {} tracks", newRoom.code, message.tracks.size)
            connection.send(ServerMessage.RoomJoined(newRoom.code, 0, isHost = true))
            connection.send(
                ServerMessage.TournamentStarted(
                    tracks = message.tracks,
                    laps = laps,
                    maxPlayers = maxPlayers,
                )
            )
        newRoom.broadcastLobbyUpdate()
    }

    private suspend fun handleJoin(message: ClientMessage.Join, connection: Connection) {
            if (connection.room != null) {
                connection.send(ServerMessage.Error("Already in a room", ErrorCode.ALREADY_IN_ROOM))
                return
            }
            val code = Validation.roomCode(message.roomCode)
            if (code == null) {
                connection.send(ServerMessage.Error("No such room", ErrorCode.ROOM_NOT_FOUND))
                return
            }
            val playerName = Validation.displayNameOr(message.playerName, DEFAULT_PLAYER_NAME)
            connection.token ?: roomManager.generateToken().also { connection.token = it }
            when (val outcome = roomManager.joinRoom(code, playerName, message.password, connection)) {
                is RoomManager.JoinOutcome.Joined -> {
                    connection.room = outcome.room
                    connection.playerId = outcome.playerId
                }
                RoomManager.JoinOutcome.NotFound ->
                    connection.send(ServerMessage.Error("No such room", ErrorCode.ROOM_NOT_FOUND))
                RoomManager.JoinOutcome.PasswordRequired ->
                    connection.send(ServerMessage.Error("This room needs a password", ErrorCode.ROOM_PASSWORD_REQUIRED))
                RoomManager.JoinOutcome.WrongPassword -> {
                    // Budgeted per (connection, room) so a wrong guess costs something,
                    // and the room's code space cannot be probed for free.
                    val key = RateKeys.roomPassword(connection, code)
                    if (!limiter.allow(key, RateLimiter.ROOM_PASSWORD_ATTEMPTS)) {
                        log.warn("Too many wrong room passwords from {}", connection.remoteIp)
                        connection.send(rateLimited(key, RateLimiter.ROOM_PASSWORD_ATTEMPTS))
                    } else {
                        connection.send(ServerMessage.Error("Wrong password", ErrorCode.WRONG_ROOM_PASSWORD))
                    }
                }
                // Room.join has already sent the specific reason (full, in progress).
                RoomManager.JoinOutcome.Refused -> Unit
            }
    }

    private suspend fun handleSpectate(message: ClientMessage.Spectate, connection: Connection) {
            // Spectating from inside a room used to strand the room you were playing in:
            // cleanup only ever follows one room per connection, so the seat you left
            // behind stalled that race on your turn forever.
            if (connection.room != null) {
                connection.send(ServerMessage.Error("Already in a room", ErrorCode.ALREADY_IN_ROOM))
                return
            }
            val code = Validation.roomCode(message.roomCode)
            if (code == null) {
                connection.send(ServerMessage.Error("No such room", ErrorCode.ROOM_NOT_FOUND))
                return
            }
            val ok = roomManager.spectateRoom(code, connection)
            if (!ok) {
                connection.send(ServerMessage.Error("Could not spectate room", ErrorCode.ROOM_NOT_FOUND))
            }
    }

    private suspend fun handleEnterMatchmaking(message: ClientMessage.EnterMatchmaking, connection: Connection) {
            // Matchmaking a seated player used to orphan the room they were sitting in:
            // being matched overwrites connection.room, and cleanup only ever follows one
            // room per connection, so the abandoned room was never finished, never reaped,
            // and stalled on that player's turn forever. This is the same hole `canOpenRoom`
            // closes on Host, reachable through a different door.
            if (connection.room != null) {
                connection.send(ServerMessage.Error("Already in a room", ErrorCode.ALREADY_IN_ROOM))
                return
            }
            if (message.desiredPlayers !in Limits.MIN_PLAYERS..Limits.MAX_PLAYERS) {
                connection.send(
                    ServerMessage.Error(
                        "Desired players must be ${Limits.MIN_PLAYERS}..${Limits.MAX_PLAYERS}",
                        ErrorCode.INVALID_INPUT,
                    )
                )
                return
            }
            val requestedTrack = message.track
            if (requestedTrack != null && !acceptTrack(requestedTrack, connection)) return
            roomManager.enterMatchmaking(
                connection = connection,
                desiredPlayers = message.desiredPlayers,
                track = message.track,
                laps = message.laps,
            )
    }

    /**
     * Bounds a client-supplied track before the server agrees to hold it. Returns false, having
     * already told the client why, when the track is refused.
     *
     * Shared by every path that accepts a `Track` from a client - hosting, hosting a
     * tournament, and swapping either mid-lobby. The mid-lobby paths were added later and
     * skipped this, which reopened the amplification hole the check exists to close.
     */
    private suspend fun acceptTrack(track: info.piepgras.gridracer.engine.Track, connection: Connection): Boolean {
        val error = Validation.checkTrack(track) ?: return true
        log.warn("Rejected a track from {}: {}", connection.remoteIp, error)
        connection.send(ServerMessage.Error("Track rejected: $error", ErrorCode.TRACK_INVALID))
        return false
    }

    /** [acceptTrack] for a whole tournament itinerary, including its length. */
    private suspend fun acceptItinerary(
        tracks: List<info.piepgras.gridracer.engine.Track>,
        connection: Connection,
    ): Boolean {
        if (tracks.size !in Limits.MIN_TOURNAMENT_TRACKS..Limits.MAX_TOURNAMENT_TRACKS) {
            connection.send(
                ServerMessage.Error(
                    "A tournament needs ${Limits.MIN_TOURNAMENT_TRACKS} to ${Limits.MAX_TOURNAMENT_TRACKS} tracks",
                    ErrorCode.INVALID_INPUT,
                )
            )
            return false
        }
        val badTrack = tracks.firstNotNullOfOrNull { Validation.checkTrack(it) }
        if (badTrack != null) {
            log.warn("Rejected a tournament track from {}: {}", connection.remoteIp, badTrack)
            connection.send(ServerMessage.Error("Track rejected: $badTrack", ErrorCode.TRACK_INVALID))
            return false
        }
        return true
    }

    /** Runs [block] only for a connection seated as a player - spectators hold playerId -1. */
    private suspend inline fun withSeat(
        connection: Connection,
        room: Room?,
        playerId: Int?,
        block: (Room) -> Unit,
    ) {
        if (room != null && playerId != null && playerId >= 0) {
            block(room)
        } else {
            connection.send(ServerMessage.Error("Not in a room", ErrorCode.NOT_IN_ROOM))
        }
    }

    /**
     * Refuses a second room on one connection.
     *
     * Hosting again used to overwrite `connection.room` and orphan the previous room, which
     * was never marked finished and so was never reaped - an unbounded leak reachable from a
     * one-line client loop. Rejecting is better than implicitly leaving, which would hide the
     * client bug that got here.
     */
    private suspend fun canOpenRoom(connection: Connection): Boolean {
        if (connection.room != null) {
            connection.send(ServerMessage.Error("Already in a room", ErrorCode.ALREADY_IN_ROOM))
            return false
        }
        if (roomManager.roomCount() >= Limits.MAX_TOTAL_ROOMS) {
            log.warn("Refusing a new room: at the {} room cap", Limits.MAX_TOTAL_ROOMS)
            connection.send(ServerMessage.Error("Server is busy - try again shortly", ErrorCode.SERVER_BUSY))
            return false
        }
        return true
    }

    /**
     * Cleans up whatever a dropped connection was holding. Called from the socket's `finally`,
     * so it must not throw.
     */
    suspend fun onDisconnected(connection: Connection) {
        val room = connection.room
        val playerId = connection.playerId
        when {
            room != null && playerId == -1 -> room.removeSpectator(connection)
            room != null && playerId != null -> room.disconnect(playerId)
        }
        // Without this a queued player who drops stays queued forever, and worse: they are
        // eventually "matched", fail to seat, and take the room down with them - breaking
        // every subsequent match in that bucket.
        roomManager.leaveMatchmaking(connection)
        limiter.forget(connection.id)
        connection.room = null
        connection.playerId = null
    }

    /** Drops rate-limit buckets nobody has touched. Called from the cleanup timer. */
    fun evictIdleLimiterState() {
        limiter.evictIdle()
    }

    private companion object {
        const val DEFAULT_HOST_NAME = "Host"
        const val DEFAULT_PLAYER_NAME = "Player"
    }
}
