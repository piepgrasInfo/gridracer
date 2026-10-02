package info.piepgras.gridracer

import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A [RaceSession] backed by a [MultiplayerClient].
 *
 * Created once the server sends `RaceStarted`; forwards [info.piepgras.gridracer.engine.GameState]
 * updates from the server into [frame] and sends local moves back.
 *
 * [localPlayerId] and [configs] track the client's flows rather than snapshotting them at
 * construction, which is what they used to do. A seat renumbered mid-race - the server does
 * this whenever the roster shrinks - could never reach the race screen, so the screen went on
 * believing an id that now belonged to someone else and could not tell whose turn it was.
 */
class NetworkRaceSession(
    private val client: MultiplayerClient,
    private val scope: CoroutineScope,
    override val tournament: Tournament? = null,
) : RaceSession {

    private val _frame = MutableStateFlow(client.race.value ?: error("Race has not started"))
    override val frame: StateFlow<RaceFrame> = _frame.asStateFlow()

    override val configs: StateFlow<List<PlayerConfig>> =
        client.configs.stateIn(scope, SharingStarted.Eagerly, client.configs.value)

    override val localPlayerId: StateFlow<Int?> = client.room
        .map { it?.playerId }
        .stateIn(scope, SharingStarted.Eagerly, client.room.value?.playerId)

    /** How long the local player has left this turn, or null when they are not on the clock. */
    val turnDeadline: StateFlow<TurnDeadline?> = client.turnDeadline

    private var raceJob: Job? = null

    init {
        raceJob = scope.launch {
            client.race.filterNotNull().collect { _frame.value = it }
        }
    }

    override fun makeMove(target: Point) {
        client.chooseMove(_frame.value.state.revision, target)
    }

    override fun quit() {
        // Do not send a server Leave here; the ViewModel calls leave() when the
        // player explicitly wants to quit, and leaving would be wrong between
        // tournament races where the same room is reused.
        raceJob?.cancel()
        raceJob = null
    }
}
