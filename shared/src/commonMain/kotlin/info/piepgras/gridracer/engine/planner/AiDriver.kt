package info.piepgras.gridracer.engine.planner

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlacementState
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.SimpleAi
import kotlin.random.Random

/**
 * The computer driver at every level: one entry point for choosing a move and one for choosing a
 * start slot, so the app and the server cannot drift apart on what a level means.
 *
 * EASY is [SimpleAi]. NORMAL and BEST take the legal move whose state has the fewest moves left in
 * the [PlannerTable] - the table built for that level's [profileFor] - with NORMAL's occasional slip
 * on top (see [AiTuning]). The legal moves always come from [GameEngine.legalMoves], so other cars
 * and the oil slips actually drawn are respected even though the table plans for a car alone.
 *
 * Whenever the table has nothing to say - it is missing, the track was too large for it, or every
 * move leads somewhere it never planned for - the driver falls back to [SimpleAi] rather than guess.
 */
object AiDriver {

    /** The table a seat of [level] needs, or null for a level that plans nothing. */
    fun profileFor(level: AiLevel): PlannerProfile? = when (level) {
        AiLevel.EASY -> null
        AiLevel.NORMAL -> AiTuning.NORMAL_PROFILE
        AiLevel.BEST -> AiTuning.BEST_PROFILE
    }

    /**
     * Picks a move for the computer-driven player [playerId].
     *
     * @param table The table for this race's track, rules and [level]; null makes any level drive
     *     like EASY.
     * @param random Settles ties and NORMAL's slips; seed it for a reproducible race.
     * @return The chosen target, or null when the player has no legal move.
     */
    fun chooseMove(
        state: GameState,
        playerId: Int,
        level: AiLevel,
        table: PlannerTable?,
        random: Random = Random.Default,
    ): Point? {
        if (level == AiLevel.EASY || table == null) return SimpleAi.chooseMove(state, playerId, random)
        val player = state.players.firstOrNull { it.id == playerId } ?: return null
        val moves = GameEngine.legalMoves(state, playerId).toList()
        if (moves.isEmpty()) return null
        // An oil-forced move onto another car crashes both: never worth it.
        val occupied = state.players
            .filter { it.id != playerId && it.status == PlayerStatus.RACING }
            .mapTo(mutableSetOf()) { it.position }
        val values = moves.mapNotNull { move ->
            if (move in occupied) return@mapNotNull null
            val value = table.valueOfMove(state, player, move) ?: return@mapNotNull null
            if (value == RacePlanner.DOOMED) null else move to value
        }
        if (values.isEmpty()) return SimpleAi.chooseMove(state, playerId, random)
        val best = values.minOf { it.second }
        val slips = level == AiLevel.NORMAL && random.nextDouble() < AiTuning.NORMAL_MISTAKE_PROBABILITY
        val limit = if (slips) best + AiTuning.NORMAL_MISTAKE_SLACK else best
        val pool = values.filter { it.second <= limit }
        return pool[random.nextInt(pool.size)].first
    }

    /**
     * Picks a start slot for the computer-driven player whose turn it is in [placement].
     *
     * BEST takes the slot with the shortest race ahead of it, NORMAL usually does, EASY takes any.
     *
     * @param laps How many laps the race will run.
     * @param table The table for [level], or null to pick at random.
     */
    fun chooseStartSlot(
        placement: PlacementState,
        laps: Int,
        level: AiLevel,
        table: PlannerTable?,
        random: Random = Random.Default,
    ): Point {
        val free = placement.availableSlots
        require(free.isNotEmpty()) { "no start slot left to choose" }
        if (level == AiLevel.EASY || table == null) return free.random(random)
        if (level == AiLevel.NORMAL && random.nextDouble() >= AiTuning.NORMAL_BEST_SLOT_PROBABILITY) return free.random(random)
        val values = free.mapNotNull { slot ->
            val value = table.startValue(slot, laps) ?: return@mapNotNull null
            if (value == RacePlanner.DOOMED) null else slot to value
        }
        if (values.isEmpty()) return free.random(random)
        val best = values.minOf { it.second }
        val tied = values.filter { it.second == best }
        return tied[random.nextInt(tied.size)].first
    }
}
