package info.piepgras.gridracer.multiplayer

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Tracks
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Protocol 9 - the gravel trap and computer levels - and that it costs older peers nothing.
 */
@OptIn(ExperimentalSerializationApi::class)
class Protocol9Test {

    private val serializer = MultiplayerFrameSerializer()

    @Test
    fun theNewMessagesAndFields_roundTrip() {
        val clientMessages = listOf(
            ClientMessage.Host(Tracks.OVAL, laps = 2, maxPlayers = 3, hostName = "H", gravelTrap = true),
            ClientMessage.HostTournament(listOf(Tracks.OVAL, Tracks.SQUARE), laps = 1, maxPlayers = 2, hostName = "H", gravelTrap = true),
            ClientMessage.AddAiPlayer(AiLevel.BEST),
            ClientMessage.AddAiPlayer(),
            ClientMessage.ChangeAiLevel(playerId = 2, level = AiLevel.NORMAL),
            ClientMessage.ChangeGravelTrap(enabled = true),
        )
        for (message in clientMessages) {
            assertEquals(message, serializer.decodeClientMessage(serializer.encodeClientMessage(message)))
        }
        val lobby = ServerMessage.LobbyUpdate(
            players = listOf(PlayerConfig("Host", false), PlayerConfig("AI 2", true, AiLevel.BEST)),
            ready = listOf(false, true),
            track = Tracks.OVAL,
            laps = 1,
            maxPlayers = 4,
            gravelTrap = true,
        )
        assertEquals(lobby, serializer.decodeServerMessage(serializer.encodeServerMessage(lobby)))
        val summaries = listOf(RoomSummary("AB12", hostName = "H", trackName = "Oval", laps = 1, players = 1, maxPlayers = 4, inProgress = false, gravelTrap = true))
        assertEquals(summaries, serializer.decodeRoomSummaries(serializer.encodeRoomSummaries(summaries)))
    }

    @Test
    fun aRaceCarriesItsRules_andADeltaKeepsThem() {
        val start = GameEngine.newGame(Tracks.OVAL, 2, rules = RaceRules.current(gravelTrap = true))
        val started = ServerMessage.RaceStarted(start, listOf(PlayerConfig("A", false), PlayerConfig("B", true, AiLevel.BEST)))
        val decoded = serializer.decodeServerMessage(serializer.encodeServerMessage(started)) as ServerMessage.RaceStarted
        assertEquals(start.rules, decoded.state.rules)
        val next = GameEngine.applyMove(start, 0, GameEngine.legalMoves(start, 0).first())
        assertEquals(next.rules, start.applyDelta(next.asDelta(start))?.rules)
    }

    /** The message as a v8 peer declared it: an object, under the same serial name. */
    @Serializable
    private sealed class OldClientMessage {
        @Serializable
        @SerialName("info.piepgras.gridracer.multiplayer.ClientMessage.AddAiPlayer")
        data object AddAiPlayer : OldClientMessage()
    }

    @Test
    fun addAiPlayerAtTheDefaultLevel_isByteForByteTheOldObject() {
        val cbor = Cbor { ignoreUnknownKeys = true }
        val json = Json { ignoreUnknownKeys = true }
        val old = OldClientMessage.AddAiPlayer
        val new = ClientMessage.AddAiPlayer()
        assertContentEquals(
            cbor.encodeToByteArray(OldClientMessage.serializer(), old),
            cbor.encodeToByteArray(ClientMessage.serializer(), new),
        )
        assertEquals(json.encodeToString(OldClientMessage.serializer(), old), json.encodeToString(ClientMessage.serializer(), new))
        // And what an old client sends reads as the EASY driver it always got.
        val fromOldClient = cbor.encodeToByteArray(OldClientMessage.serializer(), old)
        assertEquals(ClientMessage.AddAiPlayer(AiLevel.EASY), serializer.decodeClientMessage(fromOldClient))
    }

    @Test
    fun aNewerClientsLevel_isIgnoredByAnOldServer() {
        // What a v8 server makes of a level it does not know: the old object, no error.
        val cbor = Cbor { ignoreUnknownKeys = true }
        val withLevel = cbor.encodeToByteArray(ClientMessage.serializer(), ClientMessage.AddAiPlayer(AiLevel.BEST))
        assertEquals(OldClientMessage.AddAiPlayer, cbor.decodeFromByteArray(OldClientMessage.serializer(), withLevel))
    }
}
