package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * One seat as configured before a race starts.
 *
 * @property name The name shown in the HUD and on the results.
 * @property isAi Whether the computer drives this seat instead of a person.
 * @property aiLevel How well the computer drives it, when [isAi]. Defaults to [AiLevel.EASY] -
 *     the only computer driver there was - so a seat recorded before levels existed keeps the
 *     driver it had.
 */
@Serializable
data class PlayerConfig(val name: String, val isAi: Boolean, val aiLevel: AiLevel = AiLevel.EASY)
