package info.piepgras.gridracer.engine

import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * Tracks the pre-race "pick your starting slot" phase: each configured player, in
 * [order], chooses one of [Track.startPositions] until everyone has one. Turn order
 * is decided once by [Placement.start] (sequential or shuffled) - not re-derived here.
 */
@Serializable
data class PlacementState(
    /** The track whose [Track.startPositions] are being handed out. */
    val track: Track,
    /** The seats in the race, in player-id order. */
    val configs: List<PlayerConfig>,
    /** Player indices in the order they pick, fixed once by [Placement.start]. */
    val order: List<Int>,
    /** How many players have picked so far; the next picker is `order[currentOrderIndex]`. */
    val currentOrderIndex: Int = 0,
    /** Slots chosen so far, keyed by player index. */
    val assignments: Map<Int, Point> = emptyMap(),
    /**
     * Monotonic revision number used by the multiplayer server to detect stale placement
     * picks and to resync clients. The placement engine preserves it through [PlacementState.copy].
     */
    val revision: Int = 0,
) {
    /**
     * Clamped rather than indexed raw: [choose] advances past the end of [order] on the last
     * pick, and although callers move straight to the race when [isComplete], one that
     * renders the completed state for a frame first shouldn't crash for it.
     */
    val currentPlayerIndex: Int get() =
        if (order.isEmpty()) 0 else order[currentOrderIndex.coerceIn(order.indices)]
    /** Start slots nobody has taken yet, in the track's own order. */
    val availableSlots: List<Point> get() = track.startPositions.filterNot { it in assignments.values }
    /** Whether every player has a slot, so [Placement.toStartAssignments] may be called. */
    val isComplete: Boolean get() = assignments.size == configs.size
}

/**
 * The rules of the pre-race placement phase, as pure functions over [PlacementState] - the same
 * shape as [GameEngine], so the server can run placement exactly as the app does.
 */
object Placement {

    /**
     * Begins placement for [configs] on [track].
     *
     * @param randomized Shuffle the picking order with [random] instead of keeping the configured
     *     order; see [PlacementOrder].
     * @param random Source of the shuffle - pass a seeded one for a reproducible order.
     * @return A state in which nobody has picked yet.
     * @throws IllegalArgumentException if [configs] is empty or has more seats than the track has
     *     start slots.
     */
    fun start(track: Track, configs: List<PlayerConfig>, randomized: Boolean, random: Random = Random.Default): PlacementState {
        require(configs.isNotEmpty()) { "configs must not be empty" }
        require(configs.size <= track.startPositions.size) {
            "configs.size (${configs.size}) must not exceed track.startPositions.size (${track.startPositions.size})"
        }
        val order = if (randomized) configs.indices.shuffled(random) else configs.indices.toList()
        return PlacementState(track = track, configs = configs, order = order)
    }

    /** Assigns [slot] to the current player and advances to the next one in [PlacementState.order]. */
    fun choose(state: PlacementState, slot: Point): PlacementState {
        require(slot in state.availableSlots) { "$slot is not an available start slot" }
        val updatedAssignments = state.assignments + (state.currentPlayerIndex to slot)
        return state.copy(assignments = updatedAssignments, currentOrderIndex = state.currentOrderIndex + 1)
    }

    /** Picks a uniformly random available slot for the current (AI) player. */
    fun autoChoose(state: PlacementState, random: Random = Random.Default): PlacementState =
        choose(state, state.availableSlots.random(random))

    /** Once [PlacementState.isComplete], the per-player start positions in player-id order, ready for [GameEngine.newGame]. */
    fun toStartAssignments(state: PlacementState): List<Point> {
        require(state.isComplete) { "placement is not complete" }
        return state.configs.indices.map { state.assignments.getValue(it) }
    }
}
