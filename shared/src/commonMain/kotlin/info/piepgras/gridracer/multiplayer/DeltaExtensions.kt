package info.piepgras.gridracer.multiplayer

import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlacementState

/**
 * Whether [baseRevision] says this delta was computed against a state at [currentRevision].
 *
 * A `baseRevision` of 0 means the sender did not state one - a server older than protocol 5 -
 * and is treated as "unknown", never as a gap. Refusing those would break every client talking
 * to a server that has not been updated yet; accepting them is exactly the behaviour that
 * shipped before, which is the right fallback.
 */
private fun appliesTo(baseRevision: Int, currentRevision: Int): Boolean =
    baseRevision == 0 || baseRevision == currentRevision

/**
 * Returns a new [GameState] by applying [delta], or **null** when [delta] was computed against
 * a different revision than this state is at.
 *
 * The null is the whole point. This used to return a state unconditionally, overwriting
 * `revision` with the delta's - so a client that dropped one frame applied the next one to a
 * stale board and came away with a *wrong board carrying the server's revision number*. The
 * server's staleness check compares revisions, so the next move from that client was accepted
 * as current. Nothing anywhere could see the divergence. A caller that gets null here must ask
 * for a full state rather than guess ([ClientMessage.RequestResync]).
 */
fun GameState.applyDelta(delta: RaceDelta): GameState? {
    if (!appliesTo(delta.baseRevision, revision)) return null
    return copy(
        revision = delta.revision,
        currentPlayerIndex = delta.currentPlayerIndex ?: currentPlayerIndex,
        round = delta.round ?: round,
        phase = delta.phase ?: phase,
        track = delta.track ?: track,
        totalLaps = delta.totalLaps ?: totalLaps,
        maxRounds = delta.maxRounds ?: maxRounds,
        players = players.mapIndexed { index, player ->
            delta.playerChanges[index] ?: player
        },
    )
}

/** Returns a new [PlacementState] by applying [delta], or null on a revision gap - see above. */
fun PlacementState.applyDelta(delta: PlacementDelta): PlacementState? {
    if (!appliesTo(delta.baseRevision, revision)) return null
    return copy(
        revision = delta.revision,
        track = delta.track ?: track,
        configs = delta.configs ?: configs,
        order = delta.order ?: order,
        currentOrderIndex = delta.currentOrderIndex ?: currentOrderIndex,
        assignments = assignments + delta.assignments,
    )
}

/** Returns a [RaceDelta] describing the changes from [previous] to this [GameState]. */
fun GameState.asDelta(previous: GameState): RaceDelta = RaceDelta(
    revision = revision,
    baseRevision = previous.revision,
    currentPlayerIndex = currentPlayerIndex.takeIf { it != previous.currentPlayerIndex },
    round = round.takeIf { it != previous.round },
    phase = phase.takeIf { it != previous.phase },
    track = track.takeIf { it != previous.track },
    totalLaps = totalLaps.takeIf { it != previous.totalLaps },
    maxRounds = maxRounds.takeIf { it != previous.maxRounds },
    playerChanges = players.mapIndexedNotNull { index, player ->
        val old = previous.players.getOrNull(index)
        if (player != old) index to player else null
    }.toMap(),
)

/** Returns a [PlacementDelta] describing the changes from [previous] to this [PlacementState]. */
fun PlacementState.asDelta(previous: PlacementState): PlacementDelta = PlacementDelta(
    revision = revision,
    baseRevision = previous.revision,
    currentOrderIndex = currentOrderIndex.takeIf { it != previous.currentOrderIndex },
    assignments = assignments.filter { (key, value) ->
        previous.assignments[key] != value
    },
    track = track.takeIf { it != previous.track },
    configs = configs.takeIf { it != previous.configs },
    order = order.takeIf { it != previous.order },
)
