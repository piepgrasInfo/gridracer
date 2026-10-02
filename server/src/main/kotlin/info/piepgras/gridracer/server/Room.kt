package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.Placement
import info.piepgras.gridracer.engine.PlacementState
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.planner.AiDriver
import info.piepgras.gridracer.engine.planner.PlannerTable
import info.piepgras.gridracer.multiplayer.RoomSummary
import info.piepgras.gridracer.multiplayer.ErrorCode
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.server.security.Limits
import info.piepgras.gridracer.server.security.Validation
import info.piepgras.gridracer.multiplayer.asDelta
import info.piepgras.gridracer.replay.MoveKind
import info.piepgras.gridracer.replay.MoveRecord
import info.piepgras.gridracer.replay.ReplayData
import info.piepgras.gridracer.replay.ServerRaceInfo
import info.piepgras.gridracer.server.log.RaceLogSummary
import info.piepgras.gridracer.server.log.RaceLogger
import info.piepgras.gridracer.server.ai.AiPlannerService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.random.Random

private enum class RoomStatus { LOBBY, PLACEMENT, RACE, FINISHED }

/**
 * Authoritative server-side state for one multiplayer race or tournament.
 *
 * All public methods are `suspend` and serialised by an internal mutex, so the
 * calling Ktor layer can call them directly from any coroutine.
 */
class Room(
    /** The room's short code - its identity, and what players type to join. */
    val code: String,
    track: Track,
    laps: Int,
    maxPlayers: Int,
    hostName: String,
    hostConnection: Connection,
    private val random: Random = Random.Default,
    isTournament: Boolean = false,
    private val additionalTracks: List<Track> = emptyList(),
    /**
     * The leaderboard service, used to enqueue rating updates after a matchmade race. Null in
     * tests that don't care about ratings. See [LeaderboardService] for why this is a channel
     * and not a direct DB call: `onRaceFinished` runs under this room's mutex.
     */
    private val leaderboard: info.piepgras.gridracer.server.db.LeaderboardService? = null,
    /**
     * True for rooms created by the matchmaker. Only matchmade races affect ratings; a private
     * room where friends invite each other is not a rated contest.
     */
    private val isMatchmade: Boolean = false,
    /** Optional label shown in the public room list instead of just the code. */
    private val roomName: String? = null,
    /**
     * Where finished races are logged so they can be replayed later. Defaults to the disabled
     * instance, so a room that is not logging has no conditionals in its hot path.
     */
    private val raceLogger: RaceLogger = RaceLogger.Disabled,
    /** Injected so the idle-reaping tests can advance time instead of sleeping. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Overridden by tests so a timeout can be observed in milliseconds rather than minutes.
     * `var` rather than `val`: host-editable from the lobby, see [changeTurnTimeoutSeconds].
     */
    private var turnTimeoutMs: Long = TURN_TIMEOUT_MS,
    private var placementTimeoutMs: Long = PLACEMENT_TIMEOUT_MS,
    /** Whether the room's races start with the gravel trap on; host-editable, see [changeGravelTrap]. */
    gravelTrap: Boolean = false,
    /**
     * Where the planning tables of NORMAL and BEST computer seats come from. Null drives every
     * computer seat like EASY - tests that do not care about levels.
     */
    private val aiPlanners: AiPlannerService? = null,
    /** How long a computer seat's turn may wait for its table before it drives like EASY. */
    private val aiPlanWaitMs: Long = AI_PLAN_WAIT_MS,
) {
    /** The track the next race uses; host-editable from the lobby, see [changeTrack]. */
    var track: Track
        private set

    /** Host-editable from the lobby - see [changeLaps]. Fixed once a race is under way. */
    var laps: Int = laps
        private set

    /** Host-editable from the lobby - see [changeMaxPlayers]. Fixed once a race is under way. */
    var maxPlayers: Int = maxPlayers
        private set

    /**
     * Whether the room's races use the gravel trap. Host-editable from the lobby, see
     * [changeGravelTrap]; never on while anyone in the room runs a client older than
     * [GRAVEL_PROTOCOL], which could not show its moves.
     */
    var gravelTrap: Boolean = gravelTrap
        private set

    /** Whether this room runs a tournament - created as one, or given more than one track. */
    val isTournament: Boolean = isTournament || additionalTracks.isNotEmpty()

    private val log = LoggerFactory.getLogger(Room::class.java)
    private val mutex = Mutex()
    private val players = mutableListOf<RoomPlayer>()
    private val spectators = mutableListOf<Connection>()
    private var status: RoomStatus = RoomStatus.LOBBY
    private var placementState: PlacementState? = null
    private var gameState: GameState? = null
    private var lastBroadcastedPlacement: PlacementState? = null
    private var lastBroadcastedGameState: GameState? = null
    private var nextPlayerId = 1

    /**
     * The itinerary and standings. A single-race room holds one of these with one track, so
     * `tournament.currentTrack` is the answer either way and no caller has to branch.
     */
    private val tournament = RoomTournament(listOf(track) + additionalTracks, laps, maxPlayers)

    /**
     * A timeout job that auto-forfeits the current human player's turn if they don't respond
     * within [TURN_TIMEOUT_MS]. Prevents a race from freezing permanently when a client's
     * state diverges (e.g. a dropped delta) and they don't know it's their turn, or when a
     * client disconnects without the server noticing.
     */
    private val timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var turnTimeoutJob: Job? = null

    /**
     * The same idea for the placement phase, which had no timeout at all: a client that hangs
     * while choosing a start slot froze the room permanently, with no forfeit path and no
     * reaping, because [advancePlacement] simply sends a reminder and waits.
     */
    private var placementTimeoutJob: Job? = null

    /**
     * When anything last happened here, for the idle sweep. Updated on every state change and
     * every broadcast, so a lobby full of people chatting about which track to pick stays
     * alive while one that everybody walked away from does not.
     */
    @Volatile
    private var lastActivityMillis: Long = clock()

    /** Set when the last human connection drops, cleared when one returns. See [idleReason]. */
    @Volatile
    private var emptySinceMillis: Long? = null

    /**
     * The race currently being recorded, or null when no race is running or logging is off.
     *
     * Every state transition goes through [applyRecordedMove] / [applyRecordedForfeit] /
     * [applyRecordedRetire] so the log cannot silently miss one: the recording and the engine
     * call happen in the same place, and there is no way to advance the race without both.
     */
    private class RaceRecording(val initialState: GameState, val startedAt: Long) {
        val moves = mutableListOf<MoveRecord>()
    }

    private var recording: RaceRecording? = null

    /**
     * The wait for a computer seat's planning table, which runs outside the mutex (netcode
     * invariant 3) and re-enters it to carry on - see [waitForPlanner].
     */
    private var plannerWaitJob: Job? = null

    /**
     * The placement or race revision whose computer turn has already waited once for its table.
     * A second look at the same turn does not wait again: it drives like EASY instead.
     */
    private var plannerWaitedAt: Int = -1

    /**
     * Set when a wait for a table ran out in the current placement or race. From then on its
     * computer turns do not wait any more: one slow build should cost one pause, not one per turn.
     */
    private var plannerWaitExpired: Boolean = false

    private class RoomPlayer(
        var id: Int,
        var name: String,
        val isAi: Boolean,
        var isHost: Boolean,
        val token: String,
        /**
         * The account id when this seat belongs to a logged-in player, else null (guest/AI).
         *
         * `var` because an account can be deleted while its owner is seated here - see
         * [forgetAccount]. A seat that outlives the account it names would hand a dangling id
         * to the leaderboard and write a deleted player's handle into a fresh race log.
         */
        var accountId: Long?,
        /** The account's opaque public handle, for race logs. Null for a guest or an AI. */
        var accountPublicId: String? = null,
        var ready: Boolean = false,
        /** How well this seat drives, if [isAi]. Host-editable, see [changeAiLevel]. */
        var aiLevel: AiLevel = AiLevel.EASY,
        /**
         * The protocol version of the client that last held this seat - kept after it drops, so a
         * seat waiting for an old client to come back still counts when the gravel trap is
         * switched on. [GRAVEL_PROTOCOL] for a computer seat, which plays by any rules.
         */
        var protocolVersion: Int = GRAVEL_PROTOCOL,
        var left: Boolean = false,
        var connection: Connection? = null,
    )

    init {
        this.track = track
        val token = hostConnection.token ?: generateToken().also { hostConnection.token = it }
        players.add(
            RoomPlayer(
                id = 0,
                name = hostName,
                isAi = false,
                isHost = true,
                token = token,
                accountId = hostConnection.accountId,
                accountPublicId = hostConnection.accountPublicId,
                ready = false,
                left = false,
                connection = hostConnection,
                protocolVersion = hostConnection.protocolVersion,
            )
        )
        hostConnection.playerId = 0
        hostConnection.room = this
    }

    /**
     * Drops every trace of [accountId] from this room's seats, keeping the player in their
     * seat as a guest. Called when the account is deleted.
     *
     * Field writes only: no DB call and no file I/O runs under the room mutex (netcode
     * invariant 3), and the seat's name is left alone because it is the name everyone in the
     * lobby is already looking at - the *account link* is what deletion removes.
     */
    suspend fun forgetAccount(accountId: Long): Int = mutex.withLock {
        var forgotten = 0
        for (player in players) {
            if (player.accountId == accountId) {
                player.accountId = null
                player.accountPublicId = null
                forgotten++
            }
        }
        forgotten
    }

    /** Snapshot for the lobby list. Thread-safe. */
    suspend fun toSummary(isPasswordProtected: Boolean = false): RoomSummary = mutex.withLock {
        RoomSummary(
            roomCode = code,
            roomName = roomName,
            hostName = players.firstOrNull { it.isHost }?.name ?: "",
            trackName = track.name,
            laps = laps,
            players = players.size,
            maxPlayers = maxPlayers,
            inProgress = status == RoomStatus.PLACEMENT || status == RoomStatus.RACE,
            isTournament = isTournament,
            isPasswordProtected = isPasswordProtected,
            gravelTrap = gravelTrap,
        )
    }

    /** Whether the room has closed for good and only awaits removal. */
    suspend fun isFinished(): Boolean = mutex.withLock { status == RoomStatus.FINISHED }

    /**
     * How many seats currently claim to be the host. Visible for tests, which assert the
     * invariant this room is built around: a live room has exactly one.
     */
    internal suspend fun hostCountForTest(): Int = mutex.withLock { players.count { it.isHost } }

    /** Returns true if any player in this room is identified by [token]. */
    internal suspend fun hasToken(token: String): Boolean = mutex.withLock {
        players.any { it.token == token && !it.isAi }
    }

    /** Number of active players. */
    internal suspend fun playerCount(): Int = mutex.withLock { players.size }

    /** Adds a human player to the lobby. Returns the new player id, or null if the room is full. */
    suspend fun join(name: String, connection: Connection): Int? = mutex.withLock {
        // Check if this player already has a disconnected seat they can reclaim.
        // This happens when a player manually rejoins via the race setup screen
        // (sending Join) rather than through auto-reconnect (which sends Reconnect).
        val connectionToken = connection.token
        if (connectionToken != null) {
            val existing = players.find {
                it.token == connectionToken && !it.left && !it.isAi && it.connection == null
            }
            if (existing != null) {
                if (refusesForGravel(connection)) return@withLock null
                // Reclaim the disconnected seat — same logic as reconnect()
                existing.connection = connection
                existing.protocolVersion = connection.protocolVersion
                existing.name = name  // Allow name update on rejoin
                markActive()
                if (players.none { it.isHost }) existing.isHost = true
                connection.playerId = existing.id
                connection.room = this
                // Send Reconnected so the client knows it's a rejoin, not a fresh seat
                connection.send(ServerMessage.Reconnected(code, existing.id, existing.isHost))
                if (isTournament) {
                    connection.send(tournament.startedMessage())
                    connection.send(tournament.updateMessage())
                }
                when (status) {
                    RoomStatus.LOBBY -> sendBroadcast(lobbyUpdate())
                    RoomStatus.PLACEMENT -> {
                        val placement = placementState ?: return@withLock existing.id
                        connection.send(ServerMessage.PlacementStarted(placement, laps, maxPlayers))
                        connection.send(ServerMessage.PlacementUpdate(placement, laps, maxPlayers))
                        if (placement.currentPlayerIndex == existing.id) {
                            sendTo(existing.id, ServerMessage.TurnReminder(existing.id, (placementTimeoutMs / 1000).toInt()))
                        }
                    }
                    RoomStatus.RACE -> {
                        val state = gameState ?: return@withLock existing.id
                        connection.send(ServerMessage.RaceStarted(state, playerConfigs()))
                        connection.send(ServerMessage.StateSync(state, playerConfigs()))
                        sendTo(
                            state.currentPlayer.id,
                            ServerMessage.TurnReminder(state.currentPlayer.id, (turnTimeoutMs / 1000).toInt()),
                        )
                    }
                    RoomStatus.FINISHED -> {}
                }
                return@withLock existing.id
            }
        }

        if (status != RoomStatus.LOBBY) {
            connection.send(ServerMessage.Error("Room is not in the lobby"))
            return@withLock null
        }
        if (isTournament && tournament.hasStarted) {
            connection.send(ServerMessage.Error("Tournament already started"))
            return@withLock null
        }
        if (players.size >= maxPlayers) {
            connection.send(ServerMessage.Error("Room is full"))
            return@withLock null
        }
        if (refusesForGravel(connection)) return@withLock null
        val token = connection.token ?: generateToken().also { connection.token = it }
        val id = nextPlayerId++
        players.add(
            RoomPlayer(
                id = id,
                name = name,
                isAi = false,
                isHost = false,
                token = token,
                accountId = connection.accountId,
                accountPublicId = connection.accountPublicId,
                ready = false,
                left = false,
                connection = connection,
                protocolVersion = connection.protocolVersion,
            )
        )
        connection.playerId = id
        connection.room = this
        connection.send(ServerMessage.RoomJoined(code, id, isHost = false))
        if (isTournament) {
            connection.send(tournament.startedMessage())
            connection.send(tournament.updateMessage())
        }
        sendBroadcast(lobbyUpdate())
        id
    }

    /** Attach a spectator to this room. */
    suspend fun spectate(connection: Connection): Boolean = mutex.withLock {
        if (status == RoomStatus.FINISHED) {
            connection.send(ServerMessage.Error("Room finished"))
            return false
        }
        if (spectators.size >= Limits.MAX_SPECTATORS_PER_ROOM) {
            connection.send(ServerMessage.Error("Too many spectators", ErrorCode.ROOM_FULL))
            return false
        }
        if (refusesForGravel(connection)) return false
        // Repeated Spectate calls used to append duplicates, and removeSpectator drops only
        // the first match - so every duplicate leaked and got duplicate broadcasts.
        if (connection !in spectators) spectators.add(connection)
        connection.playerId = -1
        connection.room = this
        markActive()

        if (isTournament) {
            connection.send(tournament.startedMessage())
            connection.send(tournament.updateMessage())
        }

        when (status) {
            RoomStatus.LOBBY -> connection.send(lobbyUpdate())
            RoomStatus.PLACEMENT -> {
                val placement = placementState ?: return true
                connection.send(ServerMessage.PlacementStarted(placement, laps, maxPlayers))
                connection.send(ServerMessage.PlacementUpdate(placement, laps, maxPlayers))
            }
            RoomStatus.RACE -> {
                val state = gameState ?: return true
                connection.send(ServerMessage.RaceStarted(state, playerConfigs()))
                connection.send(ServerMessage.StateSync(state, playerConfigs()))
            }
            RoomStatus.FINISHED -> {}
        }
        true
    }

    /** Remove a spectator. */
    suspend fun removeSpectator(connection: Connection) = mutex.withLock {
        spectators.remove(connection)
        connection.room = null
        connection.playerId = null
        markActive()
    }

    /** Reconnects an existing player in this room using their persistent token. */
    suspend fun reconnect(token: String, connection: Connection): Boolean = mutex.withLock {
        if (status == RoomStatus.FINISHED) return false
        val player = players.find { it.token == token && !it.left && !it.isAi } ?: return false
        if (refusesForGravel(connection)) return false
        player.connection = connection
        player.protocolVersion = connection.protocolVersion
        player.left = false
        // Clears the empty-room clock: somebody is back, so the grace period must not reap it.
        markActive()

        if (players.none { it.isHost }) {
            player.isHost = true
        }

        connection.token = token
        connection.playerId = player.id
        connection.room = this
        connection.send(ServerMessage.Reconnected(code, player.id, player.isHost))

        if (isTournament) {
            connection.send(tournament.startedMessage())
            connection.send(tournament.updateMessage())
        }

        when (status) {
            RoomStatus.LOBBY -> sendBroadcast(lobbyUpdate())
            RoomStatus.PLACEMENT -> {
                val placement = placementState ?: return@withLock false
                connection.send(ServerMessage.PlacementStarted(placement, laps, maxPlayers))
                connection.send(ServerMessage.PlacementUpdate(placement, laps, maxPlayers))
                if (placement.currentPlayerIndex == player.id) {
                    sendTo(player.id, ServerMessage.TurnReminder(player.id, (placementTimeoutMs / 1000).toInt()))
                }
            }
            RoomStatus.RACE -> {
                val state = gameState ?: return@withLock false
                connection.send(ServerMessage.RaceStarted(state, playerConfigs()))
                connection.send(ServerMessage.StateSync(state, playerConfigs()))
                // The reconnecting player has missed part of their own clock, so this is a
                // fresh full budget rather than the remaining one - the timeout job that is
                // actually running was scheduled when the turn began. Erring in the player's
                // favour: showing more time than they have is better than fewer.
                sendTo(
                    state.currentPlayer.id,
                    ServerMessage.TurnReminder(state.currentPlayer.id, (turnTimeoutMs / 1000).toInt()),
                )
            }
            RoomStatus.FINISHED -> {}
        }
        true
    }

    /** Handles a transient disconnect (the client may reconnect later). */
    suspend fun disconnect(playerId: Int) = mutex.withLock {
        val player = players.find { it.id == playerId } ?: return@withLock
        player.connection = null
        // Starts the empty-room grace clock when this was the last human attached. A dropped
        // non-host player takes no other action here, so without this the room would sit with
        // nobody in it and nothing to notice that.
        markActive()

        // A *transient* drop deliberately does not close the room, even when the host is the
        // one who dropped and nobody can be promoted. Closing immediately made the reconnect
        // path unreachable for a solo host: `Reconnect` is refused once the room is FINISHED,
        // so a host whose phone changed networks lost the room they were sitting in, along
        // with everyone waiting in it. The room is now held for
        // [Limits.EMPTY_LOBBY_GRACE_MILLIS] and reaped by the idle sweep if nobody returns -
        // which is what that limit was written for. An explicit [leave] still closes at once,
        // because there the player said goodbye.
        if (player.isHost) findAndPromoteHost(player)

        when (status) {
            RoomStatus.LOBBY -> sendBroadcast(lobbyUpdate())
            RoomStatus.PLACEMENT -> {
                placementState?.let { current ->
                    if (current.currentPlayerIndex == playerId) {
                        val next = Placement.autoChoose(current, random).copy(revision = current.revision + 1)
                        placementState = next
                        broadcastPlacement(next)
                        advancePlacement()
                    }
                }
            }
            RoomStatus.RACE -> {
                gameState?.let { currentState ->
                    if (currentState.currentPlayer.id == playerId) {
                        val next = applyRecordedForfeit(currentState, playerId).copy(revision = currentState.revision + 1)
                        gameState = next
                        broadcastState(next, playerConfigs())
                        if (next.phase == GamePhase.RACE_OVER) onRaceFinished() else advanceRace()
                    }
                }
            }
            RoomStatus.FINISHED -> {}
        }
    }

    /** Toggles a human player's ready flag in the lobby. */
    suspend fun setReady(playerId: Int, ready: Boolean) = mutex.withLock {
        val player = players.find { it.id == playerId } ?: return@withLock
        if (player.isAi || player.left) return@withLock
        player.ready = ready
        sendBroadcast(lobbyUpdate())
    }

    /** Host-only: starts the placement/race phase once enough players are ready. */
    suspend fun startRace(playerId: Int) = mutex.withLock {
        val player = players.find { it.id == playerId } ?: return@withLock
        if (!player.isHost) {
            sendTo(player.id, ServerMessage.Error("Only the host can start the race"))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(player.id, ServerMessage.Error("Race has already started"))
            return@withLock
        }
        // `connection == null` means the player dropped without pressing Leave - their seat is
        // held for a reconnect, but they are not there to press Ready. Counting them used to
        // block the host permanently: a guest whose phone lost signal in the lobby left the
        // room unstartable with no way to clear it short of the host leaving too.
        if (players.any { !it.isAi && !it.left && it.connection != null && !it.ready }) {
            sendTo(player.id, ServerMessage.Error("Not all players are ready"))
            return@withLock
        }

        // Deliberately no AI fill here. The server used to top the room up to `maxPlayers`
        // with AI drivers at exactly this moment, so the host committed to a race without
        // having seen the field - and could not choose to run three cars instead of four.
        // Seats are added by hand now ([addAiPlayer]); whatever is seated is what races.
        if (players.size < 2) {
            sendTo(
                player.id,
                ServerMessage.Error("A race needs at least 2 drivers - add an AI or wait for another player"),
            )
            return@withLock
        }

        val configs = playerConfigs()
        val placement = runCatching {
            // Revision 1, not the engine's default 0. `RaceDelta.baseRevision` uses 0 as its
            // "not stated" sentinel for pre-protocol-5 servers, so a delta computed against a
            // revision-0 state would be indistinguishable from one sent by an old server -
            // and a client that missed it would apply the next delta anyway. Starting at 1
            // means every delta this server emits carries a base a client can actually check.
            Placement.start(track, configs, randomized = true, random = random).copy(revision = 1)
        }.getOrElse {
            sendTo(player.id, ServerMessage.Error(it.message ?: "Could not start placement"))
            return@withLock
        }

        requestPlanners()
        plannerWaitExpired = false
        status = RoomStatus.PLACEMENT
        placementState = placement
        lastBroadcastedPlacement = placement
        sendBroadcast(ServerMessage.PlacementStarted(placement, laps, maxPlayers))
        advancePlacement()
    }

    /**
     * Host-only: remove a seat from the lobby, human or AI.
     *
     * It used to refuse AI seats, which was right while the server placed them itself at the
     * last moment. Now the host adds them deliberately ([addAiPlayer]), so removal has to be
     * possible: an add with no undo would let one stray tap hold a seat - and lock a human out
     * of it - for the life of the room.
     */
    suspend fun kickPlayer(hostId: Int, targetId: Int) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can remove players"))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only remove players from the lobby"))
            return@withLock
        }
        val target = players.find { it.id == targetId } ?: return@withLock
        if (target.isHost) {
            sendTo(host.id, ServerMessage.Error("Cannot remove the host"))
            return@withLock
        }
        target.left = true
        // Tell the kicked player why their connection is about to drop, so the client can
        // show "You were kicked" instead of a generic connection-error dialog.
        runCatching { sendTo(target.id, ServerMessage.Kicked("You were kicked by the host")) }
        runCatching { target.connection?.close() }
            .onFailure { log.debug("Closing a kicked connection in room {} failed", code, it) }
        target.connection = null
        players.remove(target)
        reindex()
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only: seat an AI driver in the next free slot.
     *
     * The server used to do this for every empty seat the instant a race began, which meant
     * the host never saw the grid they were committing to. Now the seats sit visibly empty and
     * this is how they get filled - so the lobby shows the field that will actually race.
     *
     * The seat is chosen here rather than by the client: the client's view of which seats are
     * free is a snapshot, and a join or a departure may already have invalidated it.
     *
     * An AI is `ready` from the moment it is seated - it has nothing to decide - so it never
     * holds up a start.
     */
    suspend fun addAiPlayer(hostId: Int, level: AiLevel = AiLevel.EASY) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can add AI drivers", ErrorCode.NOT_HOST))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only add AI drivers from the lobby", ErrorCode.ROOM_IN_PROGRESS))
            return@withLock
        }
        if (players.size >= maxPlayers) {
            sendTo(host.id, ServerMessage.Error("The room is full", ErrorCode.ROOM_FULL))
            return@withLock
        }

        val seat = nextPlayerId++
        players.add(
            RoomPlayer(
                id = seat,
                // Named for the seat it lands in, not for the roster size: two adds either
                // side of a departure would otherwise produce the same name twice.
                name = "AI ${seat + 1}",
                isAi = true,
                isHost = false,
                token = generateToken(),
                accountId = null,
                ready = true,
                left = false,
                connection = null,
                aiLevel = level,
            )
        )
        log.info("Room {}: host seated a {} AI driver at {}", code, level, seat)
        requestPlanners()
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only, lobby-only: change how well the computer seat [playerId] drives. Refused for a
     * seat that is not a computer: the host's id may be stale, and a person's seat has no level.
     */
    suspend fun changeAiLevel(hostId: Int, playerId: Int, level: AiLevel) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can change a driver's level", ErrorCode.NOT_HOST))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only change levels from the lobby", ErrorCode.ROOM_IN_PROGRESS))
            return@withLock
        }
        val target = players.find { it.id == playerId }
        if (target == null || !target.isAi) {
            sendTo(host.id, ServerMessage.Error("That seat is not a computer driver", ErrorCode.INVALID_INPUT))
            return@withLock
        }
        target.aiLevel = level
        requestPlanners()
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only, lobby-only: switch the gravel trap for the room's races. Switching it on is
     * refused while anyone in the room - seated, dropped and expected back, or watching - runs a
     * client older than [GRAVEL_PROTOCOL]: that client would show them the wrong moves.
     */
    suspend fun changeGravelTrap(hostId: Int, enabled: Boolean) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can switch the gravel trap", ErrorCode.NOT_HOST))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only switch the gravel trap from the lobby", ErrorCode.ROOM_IN_PROGRESS))
            return@withLock
        }
        val outdated = players.any { !it.isAi && it.protocolVersion < GRAVEL_PROTOCOL } ||
            spectators.any { it.protocolVersion < GRAVEL_PROTOCOL }
        if (enabled && outdated) {
            sendTo(
                host.id,
                ServerMessage.Error("Someone in the room needs to update the app first", ErrorCode.UNSUPPORTED),
            )
            return@withLock
        }
        gravelTrap = enabled
        requestPlanners()
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only: change the track of a non-tournament room while in the lobby. Broadcasts a
     * fresh [ServerMessage.LobbyUpdate] so all clients pick up the new track. Refused outside
     * the lobby phase or for tournament rooms (use [changeTournamentTracks] for those).
     */
    suspend fun changeTrack(hostId: Int, newTrack: Track) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can change the track"))
            return@withLock
        }
        if (isTournament) {
            sendTo(host.id, ServerMessage.Error("Use the tournament track picker for tournament rooms"))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only change the track from the lobby"))
            return@withLock
        }
        track = newTrack
        requestPlanners()
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only: replace the entire track list of a tournament room. Valid in the lobby and
     * interstitial (between-races) phases. Broadcasts [ServerMessage.TournamentStarted] so all
     * clients pick up the new itinerary and reset to the first track.
     */
    suspend fun changeTournamentTracks(hostId: Int, newTracks: List<Track>) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can change the tracks"))
            return@withLock
        }
        if (!isTournament) {
            sendTo(host.id, ServerMessage.Error("This is not a tournament room"))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only change tracks from the lobby"))
            return@withLock
        }
        if (newTracks.isEmpty()) {
            sendTo(host.id, ServerMessage.Error("A tournament needs at least one track"))
            return@withLock
        }
        tournament.replaceTracks(newTracks)
        track = tournament.currentTrack
        requestPlanners()
        sendBroadcast(tournament.startedMessage())
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only: change the lap count while in the lobby. Unlike [changeTrack], this works for
     * tournament rooms too - the lap count applies to every race in the itinerary and has
     * nothing to do with which track is currently selected.
     */
    suspend fun changeLaps(hostId: Int, newLaps: Int) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can change the lap count", ErrorCode.NOT_HOST))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only change laps from the lobby", ErrorCode.ROOM_IN_PROGRESS))
            return@withLock
        }
        laps = Validation.clampLaps(newLaps)
        tournament.updateSettings(laps, maxPlayers)
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only: change how many seats the room has while in the lobby. Refuses to shrink below
     * the number of seats already occupied - shrinking is not the same as kicking, so the host
     * has to remove players first rather than have seats disappear out from under them.
     */
    suspend fun changeMaxPlayers(hostId: Int, newMaxPlayers: Int) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can change the seat count", ErrorCode.NOT_HOST))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only change seats from the lobby", ErrorCode.ROOM_IN_PROGRESS))
            return@withLock
        }
        val clamped = Validation.clampMaxPlayers(newMaxPlayers)
        if (clamped < players.size) {
            sendTo(
                host.id,
                ServerMessage.Error("Remove players before lowering the seat count", ErrorCode.INVALID_INPUT),
            )
            return@withLock
        }
        maxPlayers = clamped
        tournament.updateSettings(laps, maxPlayers)
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only: change how long a player has to submit a move before the server forfeits
     * their turn, while in the lobby. Takes effect on the *next* turn scheduled by
     * [scheduleTurnTimeout] - a turn already on the clock keeps the deadline it was given, so a
     * change here never surprises the player currently moving with a suddenly-shorter clock.
     */
    suspend fun changeTurnTimeoutSeconds(hostId: Int, seconds: Int) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can change the turn timeout", ErrorCode.NOT_HOST))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only change the turn timeout from the lobby", ErrorCode.ROOM_IN_PROGRESS))
            return@withLock
        }
        turnTimeoutMs = Validation.clampTurnTimeoutSeconds(seconds) * 1000L
        sendBroadcast(lobbyUpdate())
    }

    /**
     * Host-only: after a race finishes, bring all players back to the lobby. The room is
     * already in LOBBY status (set by [onRaceFinished]); this just broadcasts the lobby update
     * so clients leave the results screen - or, between tournament races, the standings
     * screen, which [onRaceFinished] leaves up until the host sends this.
     */
    suspend fun returnToLobby(hostId: Int) = mutex.withLock {
        val host = players.find { it.id == hostId } ?: return@withLock
        if (!host.isHost) {
            sendTo(host.id, ServerMessage.Error("Only the host can return to the lobby"))
            return@withLock
        }
        if (status != RoomStatus.LOBBY) {
            sendTo(host.id, ServerMessage.Error("Can only return to the lobby between races"))
            return@withLock
        }
        sendBroadcast(lobbyUpdate())
    }

    /** Convenience overload that submits a placement using the current server revision. */
    suspend fun choosePlacement(playerId: Int, slot: Point) =
        choosePlacement(playerId, placementState?.revision ?: 0, slot)

    /** Picks a start slot during the remote placement phase. */
    suspend fun choosePlacement(playerId: Int, revision: Int, slot: Point) = mutex.withLock {
        if (status != RoomStatus.PLACEMENT) {
            sendTo(playerId, ServerMessage.Error("Not in placement phase"))
            return@withLock
        }
        val current = placementState ?: return@withLock
        if (current.currentPlayerIndex != playerId) {
            sendTo(playerId, ServerMessage.PlacementUpdate(current, laps, maxPlayers))
            return@withLock
        }
        if (current.revision != revision) {
            sendTo(playerId, ServerMessage.PlacementUpdate(current, laps, maxPlayers))
            return@withLock
        }
        val next = runCatching { Placement.choose(current, slot) }.getOrElse {
            sendTo(playerId, ServerMessage.Error(it.message ?: "Invalid slot"))
            return@withLock
        }
        cancelPlacementTimeout()
        val updated = next.copy(revision = current.revision + 1)
        placementState = updated
        broadcastPlacement(updated)
        advancePlacement()
    }

    /** Convenience overload that submits a move using the current server revision. */
    suspend fun chooseMove(playerId: Int, target: Point) =
        chooseMove(playerId, gameState?.revision ?: 0, target)

    /** Applies a human player's chosen move and continues through any subsequent AI turns. */
    suspend fun chooseMove(playerId: Int, revision: Int, target: Point) = mutex.withLock {
        if (status != RoomStatus.RACE) {
            sendTo(playerId, ServerMessage.Error("Race is not in progress"))
            return@withLock
        }
        val currentState = gameState ?: return@withLock
        if (currentState.currentPlayer.id != playerId) {
            sendTo(playerId, ServerMessage.StateSync(currentState, playerConfigs()))
            return@withLock
        }
        if (currentState.revision != revision) {
            sendTo(playerId, ServerMessage.StateSync(currentState, playerConfigs()))
            return@withLock
        }
        if (!GameEngine.isLegalMove(currentState, playerId, target)) {
            // An illegal move from a well-behaved client *is* the symptom of divergence: it
            // picked a target that is legal on the board it thinks it has. Sending only an
            // error left it stuck on that wrong board until its next turn. The full state
            // corrects it now.
            sendTo(playerId, ServerMessage.Error("Illegal move"))
            sendTo(playerId, ServerMessage.StateSync(currentState, playerConfigs()))
            return@withLock
        }
        cancelTurnTimeout()
        val next = applyRecordedMove(currentState, playerId, target)
            .copy(revision = currentState.revision + 1)
        gameState = next
        broadcastState(next, playerConfigs())
        if (next.phase == GamePhase.RACE_OVER) {
            onRaceFinished()
            return@withLock
        }
        advanceRace()
    }

    /** Handles a player disconnecting or pressing Leave. */
    suspend fun leave(playerId: Int) = mutex.withLock {
        val player = players.find { it.id == playerId } ?: return@withLock

        player.left = true
        runCatching { player.connection?.close() }
            .onFailure { log.debug("Closing a dropped connection in room {} failed", code, it) }
        player.connection = null

        if (player.isHost) {
            val newHost = findAndPromoteHost(player)
            if (newHost == null) {
                closeRoomLocked("Host left")
                return@withLock
            }
        }

        when (status) {
            RoomStatus.LOBBY -> {
                players.remove(player)
                reindex()
                sendBroadcast(lobbyUpdate())
            }
            RoomStatus.PLACEMENT -> {
                placementState?.let { current ->
                    if (current.currentPlayerIndex == playerId) {
                        val next = Placement.autoChoose(current, random).copy(revision = current.revision + 1)
                        placementState = next
                        broadcastPlacement(next)
                        advancePlacement()
                    } else {
                        broadcastPlacement(current)
                    }
                }
            }
            RoomStatus.RACE -> {
                cancelTurnTimeout()
                gameState?.let { currentState ->
                    // Two different transitions: a player who leaves on their own turn
                    // forfeits it and the turn passes on; one who leaves at any other time
                    // retires without moving the turn, because somebody else is on the clock.
                    val next = if (currentState.currentPlayer.id == playerId) {
                        applyRecordedForfeit(currentState, playerId)
                    } else {
                        applyRecordedRetire(currentState, playerId)
                    }
                    gameState = next.copy(revision = currentState.revision + 1)
                    broadcastState(gameState!!, playerConfigs())
                    if (gameState!!.phase == GamePhase.RACE_OVER) onRaceFinished() else advanceRace()
                }
            }
            RoomStatus.FINISHED -> {}
        }
    }

    /**
     * Sends [connection] the full current state for whatever phase this room is in, instead of
     * the delta it would otherwise get.
     *
     * The client asks for this when it detects a revision gap - see [RaceDelta.baseRevision].
     * Answering with a full state is the only way out: a delta stream has no way to repair
     * itself, and the alternative the client used to take (apply it anyway) is what made a
     * divergence permanent and invisible.
     */
    internal suspend fun resync(connection: Connection) = mutex.withLock {
        when (status) {
            RoomStatus.LOBBY -> connection.send(lobbyUpdate())
            RoomStatus.PLACEMENT -> placementState?.let {
                connection.send(ServerMessage.PlacementUpdate(it, laps, maxPlayers))
            }
            RoomStatus.RACE -> gameState?.let {
                connection.send(ServerMessage.StateSync(it, playerConfigs()))
            }
            RoomStatus.FINISHED -> connection.send(ServerMessage.RoomClosed("Room finished"))
        }
    }

    /** Broadcast a fresh lobby update to everyone in the room. */
    internal suspend fun broadcastLobbyUpdate() = mutex.withLock { sendBroadcast(lobbyUpdate()) }

    /** Broadcast a message to every player and spectator. */
    internal suspend fun broadcast(message: ServerMessage) = mutex.withLock { sendBroadcast(message) }

    /** Close the room with [reason] and disconnect all attached clients. */
    internal suspend fun closeRoom(reason: String) = mutex.withLock { closeRoomLocked(reason) }

    // --------------------------------------------------------------------------------------------
    // Private helpers
    // --------------------------------------------------------------------------------------------

    // --------------------------------------------------------------------------------------
    // Recorded state transitions
    //
    // Every way the race can move forward goes through one of these three. They draw the RNG
    // seed *before* calling the engine and record it, which is what makes a logged race
    // reproducible: `applyMove` was previously handed the room's shared Random, so the
    // oil-slip draw depended on how many other moves had happened first and no log could
    // reconstruct it. Same shape as LocalRaceSession, which has always done this.
    // --------------------------------------------------------------------------------------

    private fun applyRecordedMove(state: GameState, playerId: Int, target: Point): GameState {
        val seed = random.nextLong()
        recording?.moves?.add(MoveRecord(playerId, target, seed, MoveKind.MOVE, clock()))
        return GameEngine.applyMove(state, playerId, target, Random(seed))
    }

    /** The player is on the clock and cannot play: their turn passes on. */
    private fun applyRecordedForfeit(state: GameState, playerId: Int): GameState {
        recording?.moves?.add(
            MoveRecord(playerId, positionOf(state, playerId), 0L, MoveKind.FORFEIT, clock()),
        )
        return GameEngine.forfeit(state, playerId)
    }

    /** The player walked out when it was somebody else's turn: the turn does not move. */
    private fun applyRecordedRetire(state: GameState, playerId: Int): GameState {
        recording?.moves?.add(
            MoveRecord(playerId, positionOf(state, playerId), 0L, MoveKind.RETIRED, clock()),
        )
        return GameEngine.retire(state, playerId)
    }

    private fun positionOf(state: GameState, playerId: Int): Point =
        state.players.firstOrNull { it.id == playerId }?.position ?: Point(0, 0)

    private fun playerConfigs(): List<PlayerConfig> =
        players.map { PlayerConfig(it.name, it.isAi, if (it.isAi) it.aiLevel else AiLevel.EASY) }

    /** The rules the room's next race is played under. */
    private fun rules(): RaceRules = RaceRules.current(gravelTrap = gravelTrap)

    /**
     * Refuses [connection] - with a reason it can show - if the room races with the gravel trap and
     * the client is too old to play by it.
     *
     * @return Whether it was refused.
     */
    private suspend fun refusesForGravel(connection: Connection): Boolean {
        if (!gravelTrap || connection.protocolVersion >= GRAVEL_PROTOCOL) return false
        connection.send(
            ServerMessage.Error("This room races with the gravel trap - update the app to join it", ErrorCode.UNSUPPORTED),
        )
        return true
    }

    /** Starts building the tables this room's computer seats need for its next race. Never blocks. */
    private fun requestPlanners() {
        val planners = aiPlanners ?: return
        val rules = rules()
        for (p in players) if (p.isAi) planners.request(track, rules, p.aiLevel)
    }

    /**
     * The table a computer seat of [level] drives by on [track], or - when it is still being built
     * and this turn ([revision]) has not waited for it yet - null after arranging to wait for it
     * outside the mutex and call [resume] once it is ready or the wait runs out. [Waiting] tells
     * the caller to stop and leave the turn to [resume].
     */
    private fun plannerFor(track: Track, level: AiLevel, revision: Int, resume: suspend () -> Unit): PlannerResult {
        val planners = aiPlanners ?: return PlannerResult.Table(null)
        return when (val found = planners.lookup(track, rules(), level)) {
            AiPlannerService.Lookup.NotNeeded -> PlannerResult.Table(null)
            is AiPlannerService.Lookup.Ready -> PlannerResult.Table(found.table)
            is AiPlannerService.Lookup.Pending -> {
                if (plannerWaitExpired || plannerWaitedAt == revision) {
                    PlannerResult.Table(null)
                } else {
                    waitForPlanner(found.job, revision, resume)
                    PlannerResult.Waiting
                }
            }
        }
    }

    private sealed interface PlannerResult {
        data class Table(val table: PlannerTable?) : PlannerResult
        data object Waiting : PlannerResult
    }

    /**
     * Waits for a table outside the mutex - a build can take seconds on a large track, and the
     * room has to keep answering everyone else meanwhile - then re-enters it and carries on.
     * [resume] checks the room is still where it was: a player may have left, or the room closed.
     */
    private fun waitForPlanner(job: Deferred<PlannerTable?>, revision: Int, resume: suspend () -> Unit) {
        plannerWaitedAt = revision
        plannerWaitJob?.cancel()
        plannerWaitJob = timeoutScope.launch {
            withTimeoutOrNull(aiPlanWaitMs) { job.await() }
            mutex.withLock {
                if (!job.isCompleted) plannerWaitExpired = true
                resume()
            }
        }
    }

    private fun cancelPlannerWait() {
        plannerWaitJob?.cancel()
        plannerWaitJob = null
    }

    private fun lobbyUpdate(): ServerMessage.LobbyUpdate =
        ServerMessage.LobbyUpdate(
            players = playerConfigs(),
            ready = players.map { it.ready },
            track = track,
            laps = laps,
            maxPlayers = maxPlayers,
            turnTimeoutSeconds = (turnTimeoutMs / 1000).toInt(),
            // Who holds the room, and who is actually attached, on the same message as the
            // roster. A client that missed a HostPromoted or a SeatAssigned still ends up
            // right, because the next lobby update tells it everything again.
            hostPlayerId = players.firstOrNull { it.isHost }?.id ?: -1,
            connected = players.map { it.connection != null },
            gravelTrap = gravelTrap,
        )

    /**
     * Hands the room to somebody else, or returns null if there is nobody to hand it to.
     *
     * **The invariant this exists for: a live room always has exactly one host.** A room with
     * players but no host cannot be started, cannot change track and cannot be returned to the
     * lobby - nobody can manage it, and it sits there until the idle sweep notices. Every path
     * that can take the host away goes through here, and closes the room when this returns null.
     *
     * Candidates are ranked, not filtered:
     *
     * 1. A **connected** human, lowest seat first - the person who has been there longest.
     * 2. A human who has **dropped but not left**, i.e. is mid-reconnect. Their seat is being
     *    held for them, so handing them the room is better than closing it: closing takes away
     *    the room they are in the middle of coming back to. They get told when they reconnect,
     *    via `Reconnected.isHost`, since there is no socket to tell them on now.
     *
     * AI seats are never candidates - an AI cannot press Start.
     */
    private suspend fun findAndPromoteHost(formerHost: RoomPlayer): RoomPlayer? {
        val candidates = players.filter { !it.isAi && !it.left && it.id != formerHost.id }
        val newHost = candidates.filter { it.connection != null }.minByOrNull { it.id }
            ?: candidates.minByOrNull { it.id }
            ?: return null

        formerHost.isHost = false
        newHost.isHost = true
        // Inform the promoted player so the client can show a notification and enable
        // host-only actions. A player who is mid-reconnect has no connection to tell right
        // now; `reconnect` sends them `Reconnected(isHost = true)` when they come back, and
        // every `LobbyUpdate` carries the host's seat regardless.
        newHost.connection?.let { conn ->
            runCatching { conn.send(ServerMessage.HostPromoted(code)) }
                .onFailure { log.debug("Sending HostPromoted in room {} failed", code, it) }
        }
        log.info("Room {}: host moved from seat {} to seat {}", code, formerHost.id, newHost.id)
        return newHost
    }

    /**
     * Renumbers seats to 0..n-1 after the roster shrinks, and **tells the clients whose seat
     * moved**.
     *
     * The renumbering itself is required: `GameState.isConsistent` demands player ids be
     * exactly the list indices, and `advanceRace` indexes `players` by id. What was missing is
     * the second half. `RoomJoined` is sent once and carries the id the client keeps forever,
     * so after a kick or a lobby departure a client went on believing a seat number that now
     * belonged to somebody else - highlighting the wrong row as "you", offering its ready
     * checkbox for another player's seat, and failing to recognise its own turn.
     */
    private suspend fun reindex() {
        players.sortBy { it.id }
        val moved = mutableListOf<RoomPlayer>()
        players.forEachIndexed { index, p ->
            if (p.id != index) {
                p.id = index
                p.connection?.playerId = index
                moved += p
            }
        }
        for (p in moved) {
            p.connection?.let { conn ->
                runCatching { conn.send(ServerMessage.SeatAssigned(code, p.id, p.isHost)) }
                    .onFailure { log.debug("Sending SeatAssigned in room {} failed", code, it) }
            }
        }
        nextPlayerId = players.size
    }

    private fun clearReadyForNextRace() {
        for (p in players) {
            p.ready = p.isAi
        }
    }

    /**
     * Hands the finished race to the [RaceLogger] and stops recording.
     *
     * Enqueuing only - this runs under the room mutex, so no file I/O may happen here. Same
     * rule and same reason as [recordRaceRatings]: a slow disk must not be able to hold up
     * every player in the room, let alone (through `RoomManager`) the `/rooms` listing.
     */
    private suspend fun logFinishedRace(state: GameState) {
        val recorded = recording ?: return
        recording = null
        val replay = ReplayData(
            trackName = state.track.name,
            configs = playerConfigs(),
            initialState = recorded.initialState,
            moves = recorded.moves.toList(),
            timestamp = clock(),
            server = ServerRaceInfo(
                roomCode = code,
                serverId = raceLogger.serverId,
                serverName = raceLogger.serverName,
                protocolVersion = info.piepgras.gridracer.multiplayer.PROTOCOL_VERSION,
                isMatchmade = isMatchmade,
                isTournament = isTournament,
                startedAt = recorded.startedAt,
                finishedAt = clock(),
                // Opaque public handles only - never a token, never the account row id. A race
                // log has to be traceable to a player without being a credential.
                accountPublicIds = players.map { it.accountPublicId },
            ),
        )
        raceLogger.enqueue(replay, RaceLogSummary.of(replay, state))
        // A client below protocol 9 replays by the old rules, under which a race played by the
        // new ones may not replay at all - and the old app crashes when a replay breaks. It
        // gets no replay rather than one it cannot play.
        val legacy = replay.initialState.rules == RaceRules.LEGACY
        markActive()
        val recipients = players.mapNotNull { it.connection } + spectators
        for (conn in recipients) {
            if (!legacy && conn.protocolVersion < GRAVEL_PROTOCOL) continue
            runCatching { conn.send(ServerMessage.RaceReplay(replay)) }
                .onFailure { log.debug("Sending a replay in room {} failed", code, it) }
        }
    }

    private suspend fun recordRaceRatings(state: GameState) {
        // Only matchmade races are rated, and only account-holding finishers count: a guest or
        // AI in the same race is skipped, so a mixed field is rated as the pairwise subset of
        // accounts. Enqueuing is O(1) and holds no mutex; the DB work happens on the
        // LeaderboardService's consumer thread.
        if (!isMatchmade) return
        val leaderboard = leaderboard ?: return
        val finishers = players
            .filter { !it.isAi && it.accountId != null }
            .mapNotNull { p ->
                val accountId = p.accountId ?: return@mapNotNull null
                val rank = state.players.firstOrNull { it.id == p.id }?.finishRank
                info.piepgras.gridracer.server.db.Elo.Finisher(id = accountId, finishRank = rank)
            }
        if (finishers.size < 2) return
        val winners = players
            .filter { !it.isAi && it.accountId != null }
            .filter { p -> state.players.firstOrNull { it.id == p.id }?.finishRank == 1 }
            .mapNotNull { it.accountId }
            .toSet()
        leaderboard.enqueue(info.piepgras.gridracer.server.db.LeaderboardService.RaceRecord(finishers, winners))
    }

    private suspend fun advancePlacement() {
        var current = placementState ?: return
        while (status == RoomStatus.PLACEMENT && !current.isComplete) {
            val currentPlayer = players.getOrNull(current.currentPlayerIndex) ?: break
            if (currentPlayer.isAi || currentPlayer.connection == null) {
                val slot = if (currentPlayer.isAi) {
                    val waiting = current
                    when (val planned = plannerFor(track, currentPlayer.aiLevel, waiting.revision) { resumePlacement(waiting.revision) }) {
                        PlannerResult.Waiting -> return
                        is PlannerResult.Table -> AiDriver.chooseStartSlot(current, laps, currentPlayer.aiLevel, planned.table, random)
                    }
                } else {
                    current.availableSlots.random(random)
                }
                current = Placement.choose(current, slot).copy(revision = current.revision + 1)
                placementState = current
                broadcastPlacement(current)
                if (current.isComplete) {
                    startRaceFromPlacement()
                    return
                }
            } else {
                broadcastPlacement(current)
                sendTo(
                    currentPlayer.id,
                    ServerMessage.TurnReminder(currentPlayer.id, (placementTimeoutMs / 1000).toInt()),
                )
                schedulePlacementTimeout(currentPlayer.id)
                break
            }
        }
        if (current.isComplete) {
            startRaceFromPlacement()
        }
    }

    /**
     * Auto-chooses for [playerId] if they never pick a start slot.
     *
     * The race phase has had a forfeit timer since it was written; placement had none, so one
     * client that hung on the placement screen held the room - and everyone else in it -
     * forever. Auto-choosing rather than forfeiting because a start slot is not a play: the
     * player has not lost anything by having one picked for them, and the race can begin.
     */
    private fun schedulePlacementTimeout(playerId: Int) {
        cancelPlacementTimeout()
        placementTimeoutJob = timeoutScope.launch {
            delay(placementTimeoutMs)
            autoChooseTimedOutPlacement(playerId)
        }
    }

    private fun cancelPlacementTimeout() {
        placementTimeoutJob?.cancel()
        placementTimeoutJob = null
    }

    private suspend fun autoChooseTimedOutPlacement(playerId: Int) = mutex.withLock {
        if (status != RoomStatus.PLACEMENT) return@withLock
        val current = placementState ?: return@withLock
        // The player picked in time, or someone else's disconnect already advanced past them.
        if (current.currentPlayerIndex != playerId) return@withLock
        log.info("Player {} timed out during placement in room {}; choosing for them", playerId, code)
        val next = Placement.autoChoose(current, random).copy(revision = current.revision + 1)
        placementState = next
        broadcastPlacement(next)
        advancePlacement()
    }

    /** Carries placement on after a computer seat waited for its table, if nothing moved meanwhile. */
    private suspend fun resumePlacement(revision: Int) {
        if (status == RoomStatus.PLACEMENT && placementState?.revision == revision) advancePlacement()
    }

    private suspend fun startRaceFromPlacement() {
        cancelPlacementTimeout()
        val placement = placementState ?: return
        val assignments = Placement.toStartAssignments(placement)
        val state = GameEngine.newGame(track, assignments, laps, rules())
            .copy(revision = placement.revision + 1)
        gameState = state
        lastBroadcastedGameState = state
        status = RoomStatus.RACE
        // Start recording from the state the race actually begins in. Everything after this
        // is a MoveRecord, so folding them back over this state reproduces the race exactly.
        recording = if (raceLogger.enabled) RaceRecording(state, clock()) else null
        sendBroadcast(ServerMessage.RaceStarted(state, playerConfigs()))
        advanceRace()
    }

    private suspend fun advanceRace() {
        var currentState = gameState ?: return
        while (status == RoomStatus.RACE && currentState.phase == GamePhase.IN_PROGRESS) {
            val currentPlayer = players.getOrNull(currentState.currentPlayer.id) ?: break
            if (currentPlayer.isAi) {
                val waiting = currentState
                val table = when (
                    val planned = plannerFor(waiting.track, currentPlayer.aiLevel, waiting.revision) { resumeRace(waiting.revision) }
                ) {
                    // The state everyone has is already broadcast; the move follows once the
                    // table is ready, from outside this lock.
                    PlannerResult.Waiting -> return
                    is PlannerResult.Table -> planned.table
                }
                val move = runCatching { AiDriver.chooseMove(currentState, currentPlayer.id, currentPlayer.aiLevel, table, random) }
                    .onFailure { log.warn("AI move selection failed in room {}", code, it) }
                    .getOrNull()
                currentState = if (move != null && GameEngine.isLegalMove(currentState, currentPlayer.id, move)) {
                    applyRecordedMove(currentState, currentPlayer.id, move)
                        .copy(revision = currentState.revision + 1)
                } else {
                    applyRecordedForfeit(currentState, currentPlayer.id)
                        .copy(revision = currentState.revision + 1)
                }
                gameState = currentState
                broadcastState(currentState, playerConfigs())
                if (currentState.phase == GamePhase.RACE_OVER) {
                    onRaceFinished()
                    return
                }
            } else {
                // Send a FULL StateSync to the current player, not just a delta. If any
                // previous RaceDelta was dropped (decode failure, transient disconnect,
                // _race.value was null), the player's client state would be stale and they
                // wouldn't know it's their turn — the server would wait forever for a move.
                // A full sync guarantees the current player has the correct state. Other
                // players get a delta (they're just observing, and a stale observer state
                // is harmless — it self-corrects on the next full sync).
                broadcastState(currentState, playerConfigs())
                sendTo(currentPlayer.id, ServerMessage.StateSync(currentState, playerConfigs()))
                // Carrying the deadline: the server force-forfeits an unanswered turn, and a
                // player who cannot see that clock loses turns to a rule they were never told
                // about. secondsRemaining has been on the wire since the protocol was written
                // and was never filled in. Broadcast rather than sent only to the player on the
                // clock, so everyone else - and spectators - can watch the same countdown
                // instead of it being invisible to them.
                sendBroadcast(ServerMessage.TurnReminder(currentPlayer.id, (turnTimeoutMs / 1000).toInt()))
                scheduleTurnTimeout(currentPlayer.id)
                break
            }
        }
        if (status == RoomStatus.RACE && currentState.phase == GamePhase.RACE_OVER) {
            onRaceFinished()
        }
    }

    /** Carries the race on after a computer seat waited for its table, if nothing moved meanwhile. */
    private suspend fun resumeRace(revision: Int) {
        if (status == RoomStatus.RACE && gameState?.revision == revision) advanceRace()
    }

    /**
     * How long the server waits for a human player to submit a move before auto-forfeiting
     * their turn, for a room that hasn't changed it. Host-editable per room, 5-20s (see
     * `Limits.MIN/MAX_TURN_TIMEOUT_SECONDS`) via [changeTurnTimeoutSeconds] - this is only the
     * value a brand-new room starts with.
     */
    private companion object {
        /**
         * The protocol version that knows the gravel trap. A client below it draws a gravel
         * race's moves wrongly and replays version-2 races by the old lap count.
         */
        const val GRAVEL_PROTOCOL = 9

        /**
         * How long a computer seat's turn waits for its planning table before it drives like EASY.
         * Every built-in track builds in a fraction of this; the limit is for a huge track.
         */
        const val AI_PLAN_WAIT_MS = 3_000L

        const val TURN_TIMEOUT_MS = 20_000L // 20 seconds
        const val TURN_TIMEOUT_SECONDS = (TURN_TIMEOUT_MS / 1000).toInt()

        /**
         * Shorter than a turn: picking one of four start slots is not a decision worth two
         * minutes, and everyone else in the room is watching a spinner until it is made.
         */
        const val PLACEMENT_TIMEOUT_MS = 45_000L
        const val PLACEMENT_TIMEOUT_SECONDS = (PLACEMENT_TIMEOUT_MS / 1000).toInt()
    }

    /**
     * Schedules a timeout that auto-forfeits [playerId]'s turn if they don't submit a move
     * within [TURN_TIMEOUT_MS]. Cancelled by [cancelTurnTimeout] when a move arrives or the
     * race ends. The timeout re-enters the room mutex and calls [chooseMove]'s forfeit path
     * indirectly via [advanceRace] after crashing the player.
     */
    private fun scheduleTurnTimeout(playerId: Int) {
        cancelTurnTimeout()
        turnTimeoutJob = timeoutScope.launch {
            delay(turnTimeoutMs)
            // Re-enter the mutex and forfeit the timed-out player's turn. If the race has
            // already moved on (the player moved just in time, or the race ended), the
            // state check inside will no-op.
            forfeitTimedOutPlayer(playerId)
        }
    }

    private fun cancelTurnTimeout() {
        turnTimeoutJob?.cancel()
        turnTimeoutJob = null
    }

    private suspend fun forfeitTimedOutPlayer(playerId: Int) = mutex.withLock {
        // Only forfeit if it's still this player's turn and the race is still in progress.
        if (status != RoomStatus.RACE) return@withLock
        val state = gameState ?: return@withLock
        if (state.currentPlayer.id != playerId) return@withLock
        val player = players.find { it.id == playerId } ?: return@withLock
        if (player.isAi) return@withLock
        log.info("Player {} timed out in room {}; auto-forfeiting turn", playerId, code)
        val next = applyRecordedForfeit(state, playerId).copy(revision = state.revision + 1)
        gameState = next
        broadcastState(next, playerConfigs())
        if (next.phase == GamePhase.RACE_OVER) {
            onRaceFinished()
        } else {
            advanceRace()
        }
    }

    private suspend fun onRaceFinished() {
        cancelTurnTimeout()
        cancelPlannerWait()
        val state = gameState ?: return
        recordRaceRatings(state)
        logFinishedRace(state)

        if (isTournament) {
            tournament.recordRace(state, players.map { RoomTournament.TournamentSeat(it.id, it.name, it.isAi) })

            if (tournament.isComplete) {
                sendBroadcast(tournament.finishedMessage())
                closeRoomLocked("Tournament finished")
            } else {
                track = tournament.currentTrack
                status = RoomStatus.LOBBY
                gameState = null
                placementState = null
                clearReadyForNextRace()
                players.removeAll { it.left }
                reindex()
                sendBroadcast(tournament.updateMessage())
            }
        } else {
            // Non-tournament: send the final standings and transition the room back to LOBBY
            // status, but do NOT broadcast a LobbyUpdate yet. The host must send
            // ReturnToLobby to bring everyone back to the lobby screen; this lets the results
            // screen stay up until the host decides to start a new race. The room stays alive
            // until the host leaves.
            val standings = players.map { p ->
                val playerState = state.players.firstOrNull { it.id == p.id }
                info.piepgras.gridracer.multiplayer.RaceStanding(
                    playerId = p.id,
                    name = p.name,
                    isAi = p.isAi,
                    finishRank = playerState?.finishRank,
                    crashed = playerState?.status == PlayerStatus.CRASHED,
                )
            }
            sendBroadcast(ServerMessage.RaceFinished(standings))

            status = RoomStatus.LOBBY
            gameState = null
            placementState = null
            lastBroadcastedGameState = null
            lastBroadcastedPlacement = null
            clearReadyForNextRace()
            players.removeAll { it.left }
            reindex()
        }
    }

    private suspend fun closeRoomLocked(reason: String) {
        status = RoomStatus.FINISHED
        cancelTurnTimeout()
        cancelPlacementTimeout()
        cancelPlannerWait()
        sendBroadcast(ServerMessage.RoomClosed(reason))
        players.mapNotNull { it.connection }.forEach { conn ->
            runCatching { conn.close() }.onFailure { log.debug("Closing a player socket failed", it) }
        }
        spectators.forEach { conn ->
            runCatching { conn.close() }.onFailure { log.debug("Closing a spectator socket failed", it) }
        }
    }

    /** Broadcast a placement update. Uses a delta when a previous state exists. */
    private suspend fun broadcastPlacement(placement: PlacementState, forceFull: Boolean = false) {
        val previous = lastBroadcastedPlacement
        lastBroadcastedPlacement = placement
        val message = if (!forceFull && previous != null) {
            ServerMessage.PlacementDelta(placement.asDelta(previous), laps, maxPlayers)
        } else {
            ServerMessage.PlacementUpdate(placement, laps, maxPlayers)
        }
        sendBroadcast(message)
    }

    /** Broadcast a race update. Uses a delta when a previous state exists. */
    private suspend fun broadcastState(state: GameState, configs: List<PlayerConfig>, forceFull: Boolean = false) {
        val previous = lastBroadcastedGameState
        lastBroadcastedGameState = state
        val message = if (!forceFull && previous != null) {
            ServerMessage.RaceDelta(state.asDelta(previous), configs)
        } else {
            ServerMessage.StateSync(state, configs)
        }
        sendBroadcast(message)
    }

    private suspend fun sendBroadcast(message: ServerMessage) {
        markActive()
        val all = players.mapNotNull { it.connection } + spectators
        for (conn in all) {
            runCatching { conn.send(message) }
                .onFailure { log.debug("Broadcast to a room {} recipient failed", code, it) }
        }
    }

    /**
     * Records that something happened, and whether anyone is still attached.
     *
     * Both are read by [idleReason] without the mutex, which is why they are `@Volatile`
     * rather than guarded: the sweep only needs a recent value, and taking this room's mutex
     * from the cleanup timer would let one busy room delay the sweep for all the others.
     */
    private fun markActive() {
        lastActivityMillis = clock()
        val anyoneHere = players.any { it.connection != null && !it.isAi } || spectators.isNotEmpty()
        emptySinceMillis = if (anyoneHere) null else emptySinceMillis ?: clock()
    }

    /**
     * Why this room should be closed, or null to leave it alone.
     *
     * Three separate cases, because they have genuinely different timeouts:
     *
     * - **Empty** - every human has dropped. A short grace period, because the common cause is
     *   a phone changing networks and the player is about to reconnect.
     * - **Idle lobby** - people are attached but nothing has happened for half an hour. They
     *   have walked away; the seats are worth more than the room.
     * - **Idle race** - a race with no move for fifteen minutes. The per-turn forfeit should
     *   have caught this, so reaching here means the room is stuck in a way the turn timer
     *   cannot see.
     */
    internal fun idleReason(now: Long = clock()): String? {
        if (status == RoomStatus.FINISHED) return null
        emptySinceMillis?.let { since ->
            if (now - since > Limits.EMPTY_LOBBY_GRACE_MILLIS) return "Everyone left"
        }
        val idleFor = now - lastActivityMillis
        return when (status) {
            RoomStatus.LOBBY -> if (idleFor > Limits.IDLE_LOBBY_MILLIS) "Lobby idle" else null
            RoomStatus.PLACEMENT, RoomStatus.RACE ->
                if (idleFor > Limits.IDLE_RACE_MILLIS) "Race idle" else null
            RoomStatus.FINISHED -> null
        }
    }

    private suspend fun sendTo(playerId: Int, message: ServerMessage) {
        players.find { it.id == playerId }?.connection?.let { conn ->
            runCatching { conn.send(message) }
                .onFailure { log.debug("Broadcast to a room {} recipient failed", code, it) }
        }
    }

    private fun generateToken(): String = UUID.randomUUID().toString()
}
