package info.piepgras.gridracer.multiplayer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Bidirectional serializer for [ClientMessage] and [ServerMessage].
 *
 * The WebSocket wire format is CBOR (compact, binary); the JSON path is for HTTP endpoints
 * and for debugging with a text-frame client.
 *
 * ## Why both formats tolerate the unknown
 *
 * Both are configured with `ignoreUnknownKeys` and with a polymorphic default that maps an
 * unrecognised message type to [ClientMessage.Unknown] / [ServerMessage.Unknown].
 *
 * That combination is what lets the protocol grow. Without it, `Cbor { }` defaults to
 * `ignoreUnknownKeys = false`, so the first time a peer sent a field the other side had never
 * heard of, decoding threw - and because both the app and the server wrap decoding in
 * `runCatching`, the message was silently dropped with no error anywhere. New optional fields
 * and new message types now degrade to "ignored" instead.
 *
 * `encodeDefaults` deliberately stays false: a field left at its default stays off the wire,
 * which is what keeps new fields invisible to peers that do not need them. Every new field
 * must therefore have a default.
 */
@OptIn(ExperimentalSerializationApi::class)
class MultiplayerFrameSerializer(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        serializersModule = unknownTolerantModule()
    },
    private val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        serializersModule = unknownTolerantModule()
    },
) {
    fun encodeClientMessage(message: ClientMessage): ByteArray =
        cbor.encodeToByteArray(ClientMessage.serializer(), message)

    fun decodeClientMessage(bytes: ByteArray): ClientMessage =
        cbor.decodeFromByteArray(ClientMessage.serializer(), bytes)

    fun decodeClientMessage(jsonText: String): ClientMessage =
        json.decodeFromString(ClientMessage.serializer(), jsonText)

    fun encodeServerMessage(message: ServerMessage): ByteArray =
        cbor.encodeToByteArray(ServerMessage.serializer(), message)

    fun decodeServerMessage(bytes: ByteArray): ServerMessage =
        cbor.decodeFromByteArray(ServerMessage.serializer(), bytes)

    fun decodeServerMessage(jsonText: String): ServerMessage =
        json.decodeFromString(ServerMessage.serializer(), jsonText)

    /** Convenience for HTTP JSON endpoints. */
    fun encodeServerMessageToJson(message: ServerMessage): String =
        json.encodeToString(ServerMessage.serializer(), message)

    fun encodeClientMessageToJson(message: ClientMessage): String =
        json.encodeToString(ClientMessage.serializer(), message)

    fun encodeRoomSummaries(rooms: List<RoomSummary>): String =
        json.encodeToString(ListSerializer(RoomSummary.serializer()), rooms)

    fun decodeRoomSummaries(jsonText: String): List<RoomSummary> =
        json.decodeFromString(ListSerializer(RoomSummary.serializer()), jsonText)
}

/**
 * Maps any message type this build does not recognise onto its `Unknown` variant.
 *
 * `ignoreUnknownKeys` covers unknown *fields*; it does nothing for an unknown *subclass* of a
 * sealed hierarchy, which is what a newly added message looks like to an older peer.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun unknownTolerantModule() = SerializersModule {
    polymorphicDefaultDeserializer(ClientMessage::class) { ClientMessage.Unknown.serializer() }
    polymorphicDefaultDeserializer(ServerMessage::class) { ServerMessage.Unknown.serializer() }
}
