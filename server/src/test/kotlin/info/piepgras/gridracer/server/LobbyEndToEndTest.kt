package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.ClientMessage
import info.piepgras.gridracer.multiplayer.MultiplayerFrameSerializer
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.server.db.AuthService
import info.piepgras.gridracer.server.db.Database
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import java.net.ServerSocket
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one test that goes over a real socket: a real Ktor server, two real WebSocket clients,
 * CBOR on the wire.
 *
 * Everything else drives [MessageRouter] and [Room] directly with a fake `Connection`, which is
 * faster and covers more - but it cannot catch a bug that lives in the *handoff* between the
 * two, and the bug this test was written for did: a client sent `Leave` and then immediately
 * closed its socket, so the frame was usually never written and the server saw a plain
 * disconnect instead. From the server's point of view the host had not quit, they had dropped -
 * which means something completely different for their seat and for who ends up holding the
 * room.
 */
@OptIn(ExperimentalSerializationApi::class)
class LobbyEndToEndTest {

    private val serializer = MultiplayerFrameSerializer()
    private lateinit var database: Database
    private lateinit var roomManager: RoomManager
    private var port = 0
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private val clients = mutableListOf<HttpClient>()

    @Before
    fun setUp() {
        database = Database(":memory:")
        roomManager = RoomManager(auth = AuthService(database))
        val router = MessageRouter(roomManager)
        port = ServerSocket(0).use { it.localPort }

        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing {
                webSocket("/play") {
                    val connection = WebSocketConnection(this, serializer, "127.0.0.1")
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val parsed = runCatching { serializer.decodeClientMessage(frame.readBytes()) }.getOrNull()
                            if (parsed != null) router.handle(parsed, connection)
                        }
                    } finally {
                        router.onDisconnected(connection)
                        connection.shutdown()
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        runCatching { server?.stop(0, 0) }
        database.close()
    }

    /** One player: a live socket plus everything the server has told them. */
    private inner class Player(val name: String) {
        private val http = HttpClient(ClientCIO) { install(ClientWebSockets) }.also { clients += it }
        lateinit var session: WebSocketSession
            private set
        val received = mutableListOf<ServerMessage>()

        suspend fun connect() {
            session = http.webSocketSession("ws://127.0.0.1:$port/play")
            send(ClientMessage.Hello())
            send(ClientMessage.Identify(null))
            awaitMessage<ServerMessage.Identified>()
        }

        suspend fun send(message: ClientMessage) {
            session.outgoing.send(Frame.Binary(true, serializer.encodeClientMessage(message)))
        }

        /**
         * Drops the socket without saying anything - a phone losing signal, as distinct from a
         * player pressing Leave.
         *
         * Cancels the session rather than closing the client: `HttpClient.close()` waits on a
         * long-lived WebSocket rather than tearing it down, so the server would not see the
         * drop for as long as the test was willing to wait.
         */
        fun dropConnection() {
            runCatching { (session as? kotlinx.coroutines.CoroutineScope)?.cancel() }
            runCatching { http.close() }
        }

        /** Reads until a message of type [T] arrives, or gives up. */
        suspend inline fun <reified T : ServerMessage> awaitMessage(timeoutMillis: Long = 4_000): T? =
            withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    val frame = session.incoming.receive()
                    if (frame !is Frame.Binary) continue
                    val message = runCatching { serializer.decodeServerMessage(frame.readBytes()) }.getOrNull()
                        ?: continue
                    received += message
                    if (message is T) return@withTimeoutOrNull message
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

        /** Drains whatever has arrived so far without waiting for anything in particular. */
        suspend fun drain(quietMillis: Long = 600) {
            while (true) {
                val frame = withTimeoutOrNull(quietMillis) {
                    runCatching { session.incoming.receive() }.getOrNull()
                } ?: return
                if (frame !is Frame.Binary) continue
                runCatching { serializer.decodeServerMessage(frame.readBytes()) }.getOrNull()?.let { received += it }
            }
        }

        inline fun <reified T : ServerMessage> last(): T? = received.filterIsInstance<T>().lastOrNull()
    }

    private fun hostMessage(maxPlayers: Int = 4) = ClientMessage.Host(
        track = Tracks.OVAL,
        laps = 1,
        maxPlayers = maxPlayers,
        hostName = "Host",
    )

    @Test
    fun aFourSeatRoomReportsFourSeatsFromTheMomentItOpens() = runBlocking {
        val host = Player("Host")
        host.connect()
        host.send(hostMessage(maxPlayers = 4))

        val lobby = host.awaitMessage<ServerMessage.LobbyUpdate>()

        assertNotNull(lobby)
        assertEquals("the lobby has to know how many seats to draw", 4, lobby!!.maxPlayers)
        assertEquals(1, lobby.players.size)
        assertEquals(0, lobby.hostPlayerId)
    }

    /**
     * The reported bug, end to end: host opens a room, someone joins, the host leaves. The
     * remaining player must end up holding a room they can actually start.
     */
    @Test
    fun whenTheHostLeavesTheRemainingPlayerHoldsTheRoom() = runBlocking {
        val host = Player("Host")
        host.connect()
        host.send(hostMessage(maxPlayers = 4))
        val room = host.awaitMessage<ServerMessage.RoomJoined>()!!

        val guest = Player("Guest")
        guest.connect()
        guest.send(ClientMessage.Join(room.roomCode, "Guest"))
        assertNotNull(guest.awaitMessage<ServerMessage.RoomJoined>())
        guest.received.clear()

        // The host leaves properly - the message goes out before the socket does.
        host.send(ClientMessage.Leave)
        host.dropConnection()

        // The guest is told, on the lobby update itself, that the room is now theirs.
        val lobby = guest.awaitMessage<ServerMessage.LobbyUpdate>()
        assertNotNull("the remaining player must get a fresh lobby", lobby)
        guest.drain()

        val finalLobby = guest.last<ServerMessage.LobbyUpdate>()!!
        assertEquals("the remaining player is renumbered to seat 0", 1, finalLobby.players.size)
        assertEquals("...and holds the room", 0, finalLobby.hostPlayerId)
        assertEquals(4, finalLobby.maxPlayers)
        assertEquals(listOf(true), finalLobby.connected)

        // The promotion is real: they can do the things only a host can. Seating an AI is one
        // of those, and it is also what makes a one-human room startable now that the server
        // no longer invents drivers.
        guest.received.clear()
        guest.send(ClientMessage.AddAiPlayer())
        val withAi = guest.awaitMessage<ServerMessage.LobbyUpdate>()
        assertNotNull("the promoted host must be able to seat an AI", withAi)
        assertEquals(2, withAi!!.players.size)
        assertTrue(withAi.players[1].isAi)

        guest.received.clear()
        guest.send(ClientMessage.SetReady(true))
        guest.send(ClientMessage.StartRace)
        val placement = guest.awaitMessage<ServerMessage.PlacementStarted>()
        assertNotNull("the promoted host must be able to start the race", placement)
        assertEquals("the grid is what the lobby showed: one human, one AI", 2, placement!!.placement.configs.size)
        assertEquals(1, placement.placement.configs.count { it.isAi })
    }

    /**
     * Seating and unseating an AI, seen from the other side of the wire: the guest is not the
     * one pressing the button, and has to learn about it from the lobby update alone.
     */
    @Test
    fun aSeatedAiAppearsAndDisappearsForEveryoneInTheRoom() = runBlocking {
        val host = Player("Host")
        host.connect()
        host.send(hostMessage(maxPlayers = 4))
        val room = host.awaitMessage<ServerMessage.RoomJoined>()!!

        val guest = Player("Guest")
        guest.connect()
        guest.send(ClientMessage.Join(room.roomCode, "Guest"))
        guest.awaitMessage<ServerMessage.RoomJoined>()
        guest.drain()
        guest.received.clear()

        host.send(ClientMessage.AddAiPlayer())
        guest.awaitMessage<ServerMessage.LobbyUpdate>()
        guest.drain()
        val seated = guest.last<ServerMessage.LobbyUpdate>()!!
        assertEquals(3, seated.players.size)
        assertTrue("the guest sees the AI the host seated", seated.players[2].isAi)

        guest.received.clear()
        host.send(ClientMessage.KickPlayer(2))
        guest.awaitMessage<ServerMessage.LobbyUpdate>()
        guest.drain()
        val removed = guest.last<ServerMessage.LobbyUpdate>()!!
        assertEquals("...and sees it go again", 2, removed.players.size)
        assertTrue(removed.players.none { it.isAi })
    }

    /** The same, when the host's connection simply dies rather than saying goodbye. */
    @Test
    fun whenTheHostDropsTheRemainingPlayerStillHoldsTheRoom() = runBlocking {
        val host = Player("Host")
        host.connect()
        host.send(hostMessage(maxPlayers = 2))
        val room = host.awaitMessage<ServerMessage.RoomJoined>()!!

        val guest = Player("Guest")
        guest.connect()
        guest.send(ClientMessage.Join(room.roomCode, "Guest"))
        guest.awaitMessage<ServerMessage.RoomJoined>()
        guest.received.clear()

        host.dropConnection()

        assertNotNull("a dropped host must hand the room on", guest.awaitMessage<ServerMessage.HostPromoted>())
        guest.drain()
        val lobby = guest.last<ServerMessage.LobbyUpdate>()!!
        assertEquals(guest.last<ServerMessage.RoomJoined>()?.playerId ?: 1, lobby.hostPlayerId)
        // The dropped host keeps their seat - they may be coming back - but the lobby says
        // plainly that they are not there.
        assertEquals(2, lobby.players.size)
        assertFalse("the dropped player is shown as not connected", lobby.connected[0])
    }

    /** A room with nobody left in it must close rather than linger. */
    @Test
    fun theLastPlayerLeavingClosesTheRoom() = runBlocking {
        val host = Player("Host")
        host.connect()
        host.send(hostMessage(maxPlayers = 2))
        val room = host.awaitMessage<ServerMessage.RoomJoined>()!!

        host.send(ClientMessage.Leave)
        host.drain()

        assertTrue("the room must be gone from the listing", roomManager.getRoom(room.roomCode)!!.isFinished())
    }

    @Test
    fun aJoiningPlayerSeesTheWholeRosterAndTheHost() = runBlocking {
        val host = Player("Host")
        host.connect()
        host.send(hostMessage(maxPlayers = 4))
        val room = host.awaitMessage<ServerMessage.RoomJoined>()!!

        val guest = Player("Guest")
        guest.connect()
        guest.send(ClientMessage.Join(room.roomCode, "Guest"))
        guest.awaitMessage<ServerMessage.LobbyUpdate>()
        guest.drain()

        val lobby = guest.last<ServerMessage.LobbyUpdate>()!!
        assertEquals(4, lobby.maxPlayers)
        assertEquals(2, lobby.players.size)
        assertEquals("the joiner must know the host is somebody else", 0, lobby.hostPlayerId)
        assertEquals(listOf(true, true), lobby.connected)
    }
}
