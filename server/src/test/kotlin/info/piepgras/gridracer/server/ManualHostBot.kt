package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.ClientMessage
import info.piepgras.gridracer.multiplayer.MultiplayerFrameSerializer
import info.piepgras.gridracer.multiplayer.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A scripted second player, for driving the app by hand against a **running** server.
 *
 * Skipped unless `GRIDRACER_BOT=1` is set, because it needs a server already running on
 * `GRIDRACER_BOT_PORT` (default 8097) and a human at the other end. Run it deliberately:
 *
 * ```
 * GRIDRACER_BOT=1 ./gradlew :server:test --tests "*ManualHostBot*" --info
 * ```
 *
 * An `assumeTrue` rather than `@Ignore`: this way it compiles with the rest of the suite and
 * cannot rot unnoticed, but a normal run reports it as skipped rather than failing.
 *
 * It hosts a room, prints the code, holds it open while you join from a device, then leaves -
 * which is the one scenario a device cannot test on its own, because it needs two players and
 * the *other* one has to be the one who quits.
 */
@OptIn(ExperimentalSerializationApi::class)
class ManualHostBot {

    private val serializer = MultiplayerFrameSerializer()

    @Test
    fun hostARoomThenLeaveIt() = runBlocking {
        assumeTrue("set GRIDRACER_BOT=1 to run the manual host bot", System.getenv("GRIDRACER_BOT") == "1")
        val port = System.getenv("GRIDRACER_BOT_PORT")?.toIntOrNull() ?: 8097
        val holdSeconds = System.getenv("GRIDRACER_BOT_HOLD_SECONDS")?.toLongOrNull() ?: 45

        val http = HttpClient(CIO) { install(WebSockets) }
        val session = http.webSocketSession("ws://127.0.0.1:$port/play")

        suspend fun send(message: ClientMessage) =
            session.outgoing.send(Frame.Binary(true, serializer.encodeClientMessage(message)))

        // Print everything the server says, so the run doubles as a protocol trace.
        launch {
            runCatching {
                for (frame in session.incoming) {
                    if (frame !is Frame.Binary) continue
                    val message = runCatching { serializer.decodeServerMessage(frame.readBytes()) }.getOrNull()
                    when (message) {
                        is ServerMessage.RoomJoined ->
                            println("BOT: hosting room ${message.roomCode} — join this from the device")
                        is ServerMessage.LobbyUpdate ->
                            println("BOT: lobby seats=${message.maxPlayers} players=${message.players.map { it.name }} host=${message.hostPlayerId} connected=${message.connected}")
                        null -> Unit
                        else -> println("BOT: ${message::class.simpleName}")
                    }
                }
            }
        }

        send(ClientMessage.Hello())
        send(ClientMessage.Identify(null))
        withTimeoutOrNull(3_000) { delay(1_000) }
        send(ClientMessage.Host(track = Tracks.OVAL, laps = 1, maxPlayers = 4, hostName = "BotHost"))

        println("BOT: holding the room for ${holdSeconds}s — join now")
        delay(holdSeconds * 1000)

        println("BOT: leaving; the device should be promoted to host")
        send(ClientMessage.Leave)
        delay(1_500)
        runCatching { http.close() }
        println("BOT: gone")
    }
}
