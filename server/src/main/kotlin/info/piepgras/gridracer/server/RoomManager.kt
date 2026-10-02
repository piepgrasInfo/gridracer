package info.piepgras.gridracer.server

import info.piepgras.gridracer.server.ai.AiPlannerService
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.ErrorCode
import info.piepgras.gridracer.multiplayer.RoomSummary
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.server.db.AuthService
import info.piepgras.gridracer.server.db.Database
import info.piepgras.gridracer.server.db.LeaderboardService
import info.piepgras.gridracer.server.db.TrackShareService
import info.piepgras.gridracer.server.log.RaceLogger
import info.piepgras.gridracer.server.security.Limits
import info.piepgras.gridracer.server.security.Validation
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * What the manager holds for each live room.
 *
 * The password sits here rather than inside [Room] deliberately: nothing in Room can then
 * put it into a RoomSummary, a broadcast, or a log line by accident, and Room's constructor
 * stays free of credentials.
 */
private class RoomRecord(
    val room: Room,
    /**
     * NFKC-normalised UTF-8 bytes of the room password, or null for a public room.
     *
     * Deliberately not hashed: the room lives for minutes, so a KDF buys nothing, and running
     * 600k PBKDF2 iterations per join attempt would hand out a denial-of-service lever.
     * Comparison is still constant-time - a short human-chosen password is exactly the kind
     * of secret a timing oracle helps with.
     */
    val password: ByteArray?,
)

/** Creates, tracks and cleans up [Room]s, plus matchmaking and ratings. */
class RoomManager(
    private val random: Random = Random.Default,
    /**
     * The account/auth service. Defaults to an in-memory SQLite database so the server runs
     * without a persistent file in tests; production passes a file-backed [Database].
     */
    val auth: AuthService = AuthService(Database(":memory:")),
    /**
     * The leaderboard/rating service. Null in tests that don't exercise ratings; production
     * passes the file-backed [LeaderboardService]. When null, matchmade races still run but
     * their results are not persisted.
     */
    val leaderboard: LeaderboardService? = null,
    /**
     * The track-sharing service. Null in tests that don't exercise track sharing; production
     * passes the file-backed [TrackShareService]. The service itself holds the server id/name
     * used to namespace track UUIDs and tag summaries, so [RoomManager] does not.
     */
    val trackShare: TrackShareService? = null,
    /**
     * Where finished races are logged so they can be replayed later. Defaults to the disabled
     * instance; production passes the file-backed [FileRaceLogger].
     */
    private val raceLogger: RaceLogger = RaceLogger.Disabled,
    /**
     * Builds the planning tables NORMAL and BEST computer seats drive by, shared by every room.
     * Null in tests that do not care: every computer seat then drives like EASY.
     */
    private val aiPlanners: AiPlannerService? = null,
) {
    private val log = LoggerFactory.getLogger(RoomManager::class.java)
    private val rooms = mutableMapOf<String, RoomRecord>()
    private val mutex = Mutex()

    private val matchmaker = Matchmaker()

    /** Create a new room with [hostName] as the host. Returns the room. */
    suspend fun createRoom(
        track: Track,
        laps: Int,
        maxPlayers: Int,
        hostName: String,
        hostConnection: Connection,
        isTournament: Boolean = false,
        additionalTracks: List<Track> = emptyList(),
        password: String? = null,
        roomName: String? = null,
        gravelTrap: Boolean = false,
    ): Room = mutex.withLock {
        createRoomLocked(
            track,
            laps,
            maxPlayers,
            hostName,
            hostConnection,
            isTournament,
            additionalTracks,
            password,
            roomName,
            gravelTrap = gravelTrap,
        )
    }

    private suspend fun createRoomLocked(
        track: Track,
        laps: Int,
        maxPlayers: Int,
        hostName: String,
        hostConnection: Connection,
        isTournament: Boolean = false,
        additionalTracks: List<Track> = emptyList(),
        password: String? = null,
        roomName: String? = null,
        isMatchmade: Boolean = false,
        gravelTrap: Boolean = false,
    ): Room {
        val code = generateUniqueCode(rooms.keys)
        val room = Room(
            code = code,
            track = track,
            laps = laps,
            maxPlayers = maxPlayers,
            hostName = hostName,
            hostConnection = hostConnection,
            random = random,
            isTournament = isTournament,
            additionalTracks = additionalTracks,
            leaderboard = leaderboard,
            isMatchmade = isMatchmade,
            roomName = roomName,
            raceLogger = raceLogger,
            gravelTrap = gravelTrap,
            aiPlanners = aiPlanners,
        )
        rooms[code] = RoomRecord(room, password?.takeIf { it.isNotEmpty() }?.let(::normalizePassword))
        return room
    }

    /** Create a tournament room. */
    suspend fun createTournamentRoom(
        tracks: List<Track>,
        laps: Int,
        maxPlayers: Int,
        hostName: String,
        hostConnection: Connection,
        password: String? = null,
        roomName: String? = null,
        gravelTrap: Boolean = false,
    ): Room? {
        if (tracks.isEmpty()) return null
        return createRoom(
            track = tracks.first(),
            laps = laps,
            maxPlayers = maxPlayers,
            hostName = hostName,
            hostConnection = hostConnection,
            isTournament = true,
            additionalTracks = tracks.drop(1),
            password = password,
            roomName = roomName,
            gravelTrap = gravelTrap,
        )
    }

/**
     * Why a join did not happen. A sealed result rather than a null, so the router can send a
     * real reason - every failure used to collapse into one "Could not join room".
     */
    sealed interface JoinOutcome {
        /**
         * The player has a seat.
         *
         * @property room The room joined.
         * @property playerId The seat assigned, which is also the player's id in the race.
         */
        data class Joined(val room: Room, val playerId: Int) : JoinOutcome
        /** No live room has that code. */
        data object NotFound : JoinOutcome
        /** The room is private and no password was given. */
        data object PasswordRequired : JoinOutcome
        /** The room is private and the password did not match. */
        data object WrongPassword : JoinOutcome
        /** The room would not take the player - full, already racing, or closing. */
        data object Refused : JoinOutcome
    }

    /** Join an existing room, checking the room password first when it has one. */
    suspend fun joinRoom(
        code: String,
        playerName: String,
        password: String?,
        connection: Connection,
    ): JoinOutcome = mutex.withLock {
        val record = rooms[code] ?: return JoinOutcome.NotFound
        val expected = record.password
        if (expected != null) {
            val supplied = password?.takeIf { it.isNotEmpty() }?.let(::normalizePassword)
                ?: return JoinOutcome.PasswordRequired
            if (!MessageDigest.isEqual(expected, supplied)) return JoinOutcome.WrongPassword
        }
        val id = record.room.join(playerName, connection) ?: return JoinOutcome.Refused
        JoinOutcome.Joined(record.room, id)
    }

    /** Whether [code] names a room that will ask for a password. */
    suspend fun isPasswordProtected(code: String): Boolean = mutex.withLock {
        rooms[code]?.password != null
    }

    /**
     * Normalised the same way on both sides of the comparison, so a password typed with a
     * composed accent matches one typed with a combining accent.
     */
    private fun normalizePassword(raw: String): ByteArray =
        Normalizer.normalize(raw, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

    /** Attach a spectator to an existing room. */
    suspend fun spectateRoom(code: String, connection: Connection): Boolean = mutex.withLock {
        val room = rooms[code]?.room ?: return false
        room.spectate(connection)
    }

    /** Enter the matchmaking queue. */
    suspend fun enterMatchmaking(
        connection: Connection,
        desiredPlayers: Int,
        track: Track?,
        laps: Int?,
    ): Boolean = mutex.withLock {
        val token = connection.token ?: return false
        // The name for a matchmade seat comes from the logged-in account's display name, or
        // "Player" for a guest. Only account-holding finishers are rated, and only in
        // matchmade rooms.
        val name = connection.displayName ?: "Player"
        val queued = QueuedConnection(connection, token, name, desiredPlayers, track, laps)
        val roomTrack = track ?: Tracks.all.first()
        val roomLaps = laps ?: 3

        when (val outcome = matchmaker.enter(queued)) {
            // Already waiting - re-send the current queue size so a client that lost the
            // MatchmakingJoined reply still shows the right state, and change nothing else.
            MatchmakingOutcome.AlreadyQueued -> {
                connection.send(ServerMessage.MatchmakingJoined)
                true
            }
            is MatchmakingOutcome.Queued -> {
                connection.send(ServerMessage.MatchmakingJoined)
                val update = ServerMessage.MatchmakingUpdate(
                    playersInQueue = outcome.playersInQueue,
                    estimatedWaitSeconds = null,
                )
                outcome.bucket.forEach { it.connection.send(update) }
                true
            }
            is MatchmakingOutcome.Matched -> {
                val group = outcome.group
                val host = group.first()
                val room = createRoomLocked(
                    track = roomTrack,
                    laps = roomLaps,
                    maxPlayers = desiredPlayers,
                    hostName = host.name,
                    hostConnection = host.connection,
                    isMatchmade = true,
                )

                host.connection.room = room
                host.connection.playerId = 0
                host.connection.send(ServerMessage.RoomJoined(room.code, 0, isHost = true))
                // The room's own lobby update, not a lookalike built here - see the same
                // change in MessageRouter for why.
                room.broadcastLobbyUpdate()

                for (i in 1 until group.size) {
                    val player = group[i]
                    val id = room.join(player.name, player.connection)
                    if (id == null) {
                        player.connection.send(ServerMessage.Error("Could not join room"))
                    }
                }

                // If the match could not be fully seated, close the room and kick everyone back.
                if (room.playerCount() < desiredPlayers) {
                    room.closeRoom("Matchmaking error")
                    return false
                }

                room.broadcastLobbyUpdate()
                true
            }
        }
    }

    /** Leave the matchmaking queue. */
    suspend fun leaveMatchmaking(connection: Connection): Boolean = mutex.withLock {
        val outcome = matchmaker.leave(connection) as? MatchmakingOutcome.Queued ?: return false
        val update = ServerMessage.MatchmakingUpdate(
            playersInQueue = outcome.playersInQueue,
            estimatedWaitSeconds = null,
        )
        outcome.bucket.forEach { it.connection.send(update) }
        true
    }

    /** Send the current global ratings table. */
    suspend fun requestLeaderboard(connection: Connection, offset: Int, limit: Int) {
        val leaderboard = leaderboard ?: return
        val page = leaderboard.leaderboardPage(offset = offset, limit = limit, forAccountId = connection.accountId)
        connection.send(ServerMessage.LeaderboardUpdate(page))
    }

    // ---- Track sharing (Phase 7) ------------------------------------------------------

    /** Upload a shared track. The connection must be authenticated. */
    suspend fun uploadTrack(
        connection: Connection,
        name: String,
        draft: info.piepgras.gridracer.engine.TrackDraft,
    ): TrackShareService.UploadResult {
        val trackShare = trackShare ?: return TrackShareService.UploadResult.Rejected(TrackShareService.Rejection.NOT_AUTHENTICATED)
        val accountId = connection.accountId ?: return TrackShareService.UploadResult.Rejected(TrackShareService.Rejection.NOT_AUTHENTICATED)
        val authorName = connection.displayName ?: "Player"
        // Resolve the login name for authorship display; fall back to empty if the account
        // vanished between auth and upload (should not happen, but a deleted account must not
        // break the upload path).
        val authorUsername = auth.accountById(accountId)?.username ?: ""
        return trackShare.upload(name, draft, accountId, authorName, System.currentTimeMillis(), authorUsername)
    }

    /** Rate a shared track. The connection must be authenticated. */
    suspend fun rateTrack(
        connection: Connection,
        trackId: String,
        rating: Int,
    ): TrackShareService.RateResult {
        val trackShare = trackShare ?: return TrackShareService.RateResult.NotAuthenticated
        val accountId = connection.accountId ?: return TrackShareService.RateResult.NotAuthenticated
        return trackShare.rate(trackId, accountId, rating, System.currentTimeMillis())
    }

    /** List shared tracks. The connection's account (if any) fills in each row's own rating. */
    suspend fun listTracks(connection: Connection, offset: Int, limit: Int): TrackShareService.ListResult? {
        val trackShare = trackShare ?: return null
        return trackShare.list(offset, limit, forAccountId = connection.accountId)
    }

    /** Download a shared track by its public id. Anonymous-OK; see [listTracks]. */
    suspend fun downloadTrack(connection: Connection, trackId: String): TrackShareService.DownloadResult? {
        val trackShare = trackShare ?: return null
        return trackShare.download(trackId, forAccountId = connection.accountId)
    }

    /** Report a shared track. The connection must be authenticated. */
    suspend fun reportTrack(
        connection: Connection,
        trackId: String,
        reason: String,
    ): TrackShareService.ReportResult {
        val trackShare = trackShare ?: return TrackShareService.ReportResult.NotAuthenticated
        val accountId = connection.accountId ?: return TrackShareService.ReportResult.NotAuthenticated
        return trackShare.report(trackId, accountId, reason, System.currentTimeMillis())
    }

    /** Look up a room by its code. */
    suspend fun getRoom(code: String): Room? = mutex.withLock { rooms[code]?.room }

    /** How many rooms are live, for the total-room cap. */
    suspend fun roomCount(): Int = mutex.withLock { rooms.size }

    /** All non-finished rooms, suitable for the lobby list. */
    suspend fun listRooms(): List<RoomSummary> = mutex.withLock {
        rooms.values
            .filter { !it.room.isFinished() }
            .map { it.room.toSummary(isPasswordProtected = it.password != null) }
    }

    /** Remove rooms that have finished so they don’t leak memory. */
    suspend fun removeFinished() = mutex.withLock {
        val finished = rooms.values.filter { it.room.isFinished() }
        finished.forEach { rooms.remove(it.room.code) }
    }

    /**
     * Closes rooms nobody is using any more, and returns how many were closed.
     *
     * [Limits.EMPTY_LOBBY_GRACE_MILLIS], [Limits.IDLE_LOBBY_MILLIS] and
     * [Limits.IDLE_RACE_MILLIS] have existed since the limits file was written and were
     * enforced nowhere - the comment on the first one said as much ("never reaped today - it
     * lives forever"). A lobby whose players all closed the app without pressing Leave, or a
     * race whose last human dropped, held its seats, its track and its connections until the
     * process restarted.
     *
     * Called from the same cleanup timer as [removeFinished], which then reaps what this
     * closes on the following pass.
     */
    suspend fun closeIdleRooms(now: Long = System.currentTimeMillis()): Int {
        // Snapshot under the manager mutex, then act without it: closing a room takes that
        // room's mutex, and holding both while broadcasting to every recipient is exactly the
        // stall this release is removing.
        val candidates = mutex.withLock { rooms.values.map { it.room } }
        var closed = 0
        for (room in candidates) {
            val reason = room.idleReason(now) ?: continue
            log.info("Closing idle room {}: {}", room.code, reason)
            room.closeRoom(reason)
            closed++
        }
        return closed
    }

    /**
     * Drops matchmaking entries past their TTL and tells those players they are out of the
     * queue, so a bucket that never fills does not leave someone waiting on a screen forever.
     */
    suspend fun reapExpiredMatchmaking(): Int {
        val expired = matchmaker.reapExpired()
        for (entry in expired) {
            runCatching {
                entry.connection.send(
                    ServerMessage.Error("Matchmaking timed out - try again", ErrorCode.UNSPECIFIED),
                )
                entry.connection.send(ServerMessage.MatchmakingUpdate(playersInQueue = 0))
            }.onFailure { log.debug("Notifying an expired matchmaking entry failed", it) }
        }
        return expired.size
    }

    /**
     * Removes a deleted account from every seat it currently holds, anywhere on the server.
     *
     * The account may be seated through a *different* connection on another device, and that
     * seat caches the account id and public handle from the moment it was taken. Left alone,
     * it feeds a dangling id to [LeaderboardService] when the race finishes - which drops the
     * whole race's rating for everyone in it - and writes the deleted player's handle into a
     * race log created after they asked to be forgotten.
     */
    suspend fun forgetAccount(accountId: Long): Int {
        // Snapshot under the manager mutex, then act without it, as closeIdleRooms does: each
        // room takes its own mutex, and holding both is the stall this codebase avoids.
        val candidates = mutex.withLock { rooms.values.map { it.room } }
        return candidates.sumOf { it.forgetAccount(accountId) }
    }

    /**
     * Scrubs a deleted account out of the stored race logs. Delegates to the [RaceLogger] this
     * manager already owns, so the router does not need its own handle on the writer.
     */
    suspend fun purgeRaceLogsFor(accountPublicId: String): Int = raceLogger.purgeAccount(accountPublicId)

    /** Generate a new persistent player token. */
    fun generateToken(): String = UUID.randomUUID().toString()

    /** Returns true if the given token belongs to a player in any active room. */
    suspend fun isKnown(token: String): Boolean = mutex.withLock {
        rooms.values.any { it.room.hasToken(token) }
    }

    /** Reconnect a connection to an existing room by code using its token. */
    suspend fun reconnectRoom(code: String, connection: Connection): Boolean = mutex.withLock {
        val room = rooms[code]?.room ?: return false
        room.reconnect(connection.token ?: return false, connection)
    }

    private fun generateUniqueCode(existing: Set<String>): String {
        while (true) {
            val code = (1..Limits.ROOM_CODE_LENGTH)
                .map { Validation.ROOM_CODE_ALPHABET.random(random) }
                .joinToString("")
            if (code !in existing) return code
        }
    }
}
