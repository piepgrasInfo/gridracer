package info.piepgras.gridracer.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How well a computer-driven seat drives. See [info.piepgras.gridracer.engine.planner.AiDriver].
 *
 * Serialized by name with [AiLevelSerializer], which reads a name it does not know as [EASY]: the
 * level travels in every [PlayerConfig] - over the network, in replays, in saved races - and a level
 * added by a later version must not make an older one throw the whole message or file away.
 */
@Serializable(with = AiLevelSerializer::class)
enum class AiLevel {
    /** The original opponent ([SimpleAi]): a cautious heuristic, no route planning. */
    EASY,

    /** Plans its route exactly, but with a lower top speed and the odd slightly-worse choice. */
    NORMAL,

    /** Drives the fastest route the rules allow. */
    BEST,
}

/** Writes an [AiLevel] as its name; reads an unknown name as [AiLevel.EASY]. */
object AiLevelSerializer : KSerializer<AiLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("info.piepgras.gridracer.engine.AiLevel", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AiLevel) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): AiLevel {
        val name = decoder.decodeString()
        return AiLevel.entries.firstOrNull { it.name == name } ?: AiLevel.EASY
    }
}
