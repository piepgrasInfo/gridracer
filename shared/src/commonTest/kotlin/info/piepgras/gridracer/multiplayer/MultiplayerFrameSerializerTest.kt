package info.piepgras.gridracer.multiplayer

import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Tracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Guards the forward-compatibility strategy the whole protocol rests on.
 *
 * If [unknownDecodesRatherThanThrowing] ever fails, adding a message type or a field becomes a
 * breaking change for every peer that has not been rebuilt - and because both sides swallow
 * decode failures, it breaks *silently*.
 */
class MultiplayerFrameSerializerTest {

    private val serializer = MultiplayerFrameSerializer()

    @Test
    fun clientMessagesRoundTripThroughCbor() {
        val messages = listOf(
            ClientMessage.Hello(),
            ClientMessage.Identify("a-token"),
            ClientMessage.Identify(null),
            ClientMessage.Host(Tracks.OVAL, laps = 3, maxPlayers = 4, hostName = "Martin"),
            ClientMessage.Join("AB12", "Guest"),
            ClientMessage.ChooseMove(revision = 7, target = Point(3, 4)),
            ClientMessage.Leave,
            ClientMessage.RequestLeaderboard(),
        )

        for (message in messages) {
            assertEquals(message, serializer.decodeClientMessage(serializer.encodeClientMessage(message)))
        }
    }

    @Test
    fun serverMessagesRoundTripThroughCbor() {
        val messages = listOf(
            ServerMessage.Welcome(features = setOf(ServerFeature.ACCOUNTS)),
            ServerMessage.Identified("a-token"),
            ServerMessage.RoomJoined("AB12", playerId = 1, isHost = false),
            ServerMessage.Error("nope"),
            ServerMessage.RoomClosed("done"),
        )

        for (message in messages) {
            assertEquals(message, serializer.decodeServerMessage(serializer.encodeServerMessage(message)))
        }
    }

    @Test
    fun messagesRoundTripThroughJson() {
        val message = ClientMessage.Join("AB12", "Guest")
        val text = serializer.encodeClientMessageToJson(message)

        assertEquals(message, serializer.decodeClientMessage(text))
    }

    @Test
    fun unknownDecodesRatherThanThrowing() {
        // What a message added in a later version looks like to this build: a discriminator
        // naming a type that does not exist here.
        val futureClient = """{"type":"info.piepgras.gridracer.multiplayer.ClientMessage.FutureThing","payload":42}"""
        val futureServer = """{"type":"info.piepgras.gridracer.multiplayer.ServerMessage.FutureThing","payload":42}"""

        assertIs<ClientMessage.Unknown>(serializer.decodeClientMessage(futureClient))
        assertIs<ServerMessage.Unknown>(serializer.decodeServerMessage(futureServer))
    }

    @Test
    fun anUnknownFieldOnAKnownMessageIsIgnored() {
        // What a field added in a later version looks like: a known type carrying extra keys.
        val withExtra = """
            {"type":"info.piepgras.gridracer.multiplayer.ClientMessage.Join",
             "roomCode":"AB12","playerName":"Guest","password":"hunter2","future":true}
        """.trimIndent()

        val decoded = serializer.decodeClientMessage(withExtra)

        assertIs<ClientMessage.Join>(decoded)
        assertEquals("AB12", decoded.roomCode)
        assertEquals("Guest", decoded.playerName)
    }

    @Test
    fun defaultsStayOffTheWire() {
        // encodeDefaults = false is what makes a new optional field invisible to peers that
        // do not need it. If this flips, every added field becomes a compatibility problem.
        val text = serializer.encodeClientMessageToJson(ClientMessage.Hello(clientVersion = null))

        assertTrue("clientVersion" !in text, "clientVersion should be omitted when null, got: $text")
    }

    @Test
    fun roomSummariesRoundTripThroughJson() {
        val rooms = listOf(
            RoomSummary(
                roomCode = "AB12",
                hostName = "Martin",
                trackName = "Oval",
                laps = 1,
                players = 1,
                maxPlayers = 4,
                inProgress = false,
            )
        )

        assertEquals(rooms, serializer.decodeRoomSummaries(serializer.encodeRoomSummaries(rooms)))
    }

    @Test
    fun anUnknownFieldOnARoomSummaryIsIgnored() {
        // /rooms is the one place a newer server talks to an older client routinely.
        val text = """[{"roomCode":"AB12","hostName":"M","trackName":"Oval","laps":1,
                       "players":1,"maxPlayers":4,"inProgress":false,"isPasswordProtected":true}]"""

        val rooms = serializer.decodeRoomSummaries(text)

        assertEquals(1, rooms.size)
        assertEquals("AB12", rooms.first().roomCode)
    }
}
