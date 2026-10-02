package info.piepgras.gridracer.server

import info.piepgras.gridracer.server.ai.AiPlannerService
import info.piepgras.gridracer.multiplayer.MultiplayerFrameSerializer
import info.piepgras.gridracer.multiplayer.ServerMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import info.piepgras.gridracer.server.db.AuthService
import info.piepgras.gridracer.server.db.Database
import info.piepgras.gridracer.server.db.LeaderboardService
import info.piepgras.gridracer.server.db.TrackShareService
import info.piepgras.gridracer.server.log.FileRaceLogger
import info.piepgras.gridracer.server.log.RaceLogger
import info.piepgras.gridracer.server.security.ClientIp
import info.piepgras.gridracer.server.security.ConnectionRegistry
import info.piepgras.gridracer.server.security.Limits
import info.piepgras.gridracer.server.security.RateKeys
import info.piepgras.gridracer.server.security.RateLimiter
import kotlinx.serialization.ExperimentalSerializationApi
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("info.piepgras.gridracer.server.Application")

private const val ROOM_CLEANUP_INTERVAL_MILLIS = 60_000L

private val ROOM_LIST_BUDGET = RateLimiter.Budget.perMinute(30)

/**
 * Starts the server: reads [ServerConfig] from the environment, opens the database, and serves
 * `/play` (WebSocket), `/rooms` and `/health` until the process is stopped.
 */
@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val config = ServerConfig.fromEnv()
    val serializer = MultiplayerFrameSerializer()
    val database = Database(config.databasePath)
    val authService = AuthService(database)
    val leaderboardService = LeaderboardService(database)
    val trackShareService = TrackShareService(database, serverId = config.serverId, serverName = config.serverName)
    val raceLogger = if (config.raceLogEnabled) {
        FileRaceLogger(
            directory = FileRaceLogger.directoryOf(config.raceLogDirectory),
            serverId = config.serverId,
            serverName = config.serverName,
            retentionDays = config.raceLogRetentionDays,
        )
    } else {
        RaceLogger.Disabled
    }
    val roomManager = RoomManager(
        auth = authService,
        leaderboard = leaderboardService,
        trackShare = trackShareService,
        raceLogger = raceLogger,
        aiPlanners = AiPlannerService(),
    )
    val limiter = RateLimiter()
    val connections = ConnectionRegistry()
    val router = MessageRouter(roomManager, limiter = limiter, serverId = config.serverId, serverName = config.serverName)

    log.info("GridRacer server starting on {}:{}", config.host, config.port)
    if (config.raceLogEnabled) {
        log.info(
            "Race logging is on: writing to {}, keeping {} days",
            FileRaceLogger.directoryOf(config.raceLogDirectory),
            config.raceLogRetentionDays,
        )
    } else {
        log.info("Race logging is off (GRIDRACER_RACE_LOG)")
    }

    embeddedServer(CIO, port = config.port, host = config.host) {
        install(WebSockets) {
            pingPeriodMillis = 15_000
            timeoutMillis = 15_000
            maxFrameSize = Limits.MAX_FRAME_BYTES
            masking = false
        }

        routing {
            get("/health") {
                call.respondText("OK")
            }

            get("/rooms") {
                // The room list is public and unauthenticated, so without a budget it is a
                // free scraping firehose. Keyed per IP, not per connection - there is no
                // connection here.
                val ip = ClientIp.resolve(
                    remoteAddress = call.request.origin.remoteAddress,
                    forwardedForHeader = call.request.header("X-Forwarded-For"),
                    trustProxy = config.trustProxy,
                )
                if (!limiter.allow(RateKeys.roomList(ip), ROOM_LIST_BUDGET)) {
                    call.respond(HttpStatusCode.TooManyRequests, "Slow down")
                    return@get
                }
                val summaries = roomManager.listRooms()
                call.respondText(serializer.encodeRoomSummaries(summaries), ContentType.Application.Json)
            }

            webSocket("/play") {
                val remoteIp = ClientIp.resolve(
                    remoteAddress = call.request.origin.remoteAddress,
                    forwardedForHeader = call.request.header("X-Forwarded-For"),
                    trustProxy = config.trustProxy,
                )
                // Count the socket before reading a single frame. Every budget in RateLimiter
                // that is keyed by connection is bypassed by opening another one, so the cap
                // is what stops one address from holding thousands open.
                val rejection = connections.acquire(remoteIp)
                if (rejection != null) {
                    log.warn("Refusing a connection from {}: {}", remoteIp, rejection)
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, rejection.name))
                    return@webSocket
                }
                val connection = WebSocketConnection(this, serializer, remoteIp)
                try {
                    for (frame in incoming) {
                        val parsed = decode(frame, serializer)
                        if (parsed == null) {
                            connection.send(ServerMessage.Error("Could not parse message"))
                        } else {
                            router.handle(parsed, connection)
                        }
                    }
                } finally {
                    // Both must run, and the release must pair with the acquire above - a
                    // missed release drifts the count upward until the server locks itself out.
                    runCatching { router.onDisconnected(connection) }
                        .onFailure { log.warn("Cleanup for a dropped connection failed", it) }
                    connection.shutdown()
                    connections.release(remoteIp)
                }
            }
        }

        // Housekeeping, every minute. Order matters: closing idle rooms marks them finished,
        // and removeFinished on the *next* pass is what actually drops them - which gives any
        // client a full interval to see the RoomClosed message before the room is gone.
        launch {
            while (true) {
                delay(ROOM_CLEANUP_INTERVAL_MILLIS)
                runCatching {
                    roomManager.closeIdleRooms()
                    roomManager.reapExpiredMatchmaking()
                    raceLogger.sweep()
                    roomManager.removeFinished()
                    router.evictIdleLimiterState()
                    authService.reapExpiredSessions()
                }.onFailure { log.error("Housekeeping pass failed", it) }
            }
        }
    }.start(wait = true)
}

/** Binary frames are CBOR, text frames are JSON; anything else, and any garbage, is null. */
@OptIn(ExperimentalSerializationApi::class)
private fun decode(frame: Frame, serializer: MultiplayerFrameSerializer) = when (frame) {
    is Frame.Binary -> runCatching { serializer.decodeClientMessage(frame.readBytes()) }
        .onFailure { log.debug("Undecodable binary frame", it) }
        .getOrNull()
    is Frame.Text -> runCatching { serializer.decodeClientMessage(frame.readText()) }
        .onFailure { log.debug("Undecodable text frame", it) }
        .getOrNull()
    else -> null
}
