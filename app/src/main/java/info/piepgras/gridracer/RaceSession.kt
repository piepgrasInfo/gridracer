package info.piepgras.gridracer

import info.piepgras.gridracer.replay.MoveKind
import info.piepgras.gridracer.replay.MoveRecord
import info.piepgras.gridracer.replay.ReplayData
import info.piepgras.gridracer.replay.apply
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.planner.AiDriver
import info.piepgras.gridracer.engine.planner.PlannerTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** What happened to a player on a race transition, for sound and HUD feedback. */
enum class EventType { CRASHED, FINISHED }

/**
 * One thing that happened to one player on a race transition.
 *
 * @property playerId The player it happened to.
 * @property type What happened.
 */
data class GameEvent(val playerId: Int, val type: EventType)

/**
 * A consistent snapshot of a race: state plus the events that happened on the last transition.
 *
 * @property state The race after the transition.
 * @property events What the transition did to each player; see [diffEvents].
 */
data class RaceFrame(val state: GameState, val events: List<GameEvent>)

/**
 * A single in-progress race from the local device’s point of view.
 *
 * Implementations keep an authoritative or mirrored [GameState], drive it forward in response
 * to [makeMove], and emit state + event updates that the UI can render.
 */
interface RaceSession {
    /** Consistent state + events pairs. Consumers should read this flow for race changes. */
    val frame: StateFlow<RaceFrame>

    /**
     * The player roster, by seat. A flow rather than a list because a network race's roster
     * can change under it: the server renumbers seats whenever the roster shrinks, and a
     * snapshot taken when the session was built could never learn about it.
     */
    val configs: StateFlow<List<PlayerConfig>>

    /** The local tournament this race belongs to, or null - always null online. */
    val tournament: Tournament?

    /**
     * The player id controlled by this device, or `null` for local hotseat play. Also a flow,
     * and for the same reason - a seat that moves mid-race has to reach the screen deciding
     * whose turn it is.
     */
    val localPlayerId: StateFlow<Int?>

    /** Submit a move for the current player, if it is this device’s turn. */
    fun makeMove(target: Point)

    /** Stop the session (cancel pending AI turns, close network connections, etc.). */
    fun quit()
}

/** Delay between an AI’s turn starting and its move being chosen. */
private const val AI_MOVE_DELAY_MILLIS = 500L

/**
 * Local race session: the device owns the state and runs the AI.
 *
 * This is the existing single-device behaviour extracted into a [RaceSession].
 *
 * @param planner The planning table a computer seat of a given level drives by in this race, or
 *     null for one that plans nothing; awaited only when that seat has to move. See [AiPlannerCache].
 */
class LocalRaceSession(
    /** The race as it starts, positions already assigned. */
    val initialState: GameState,
    configs: List<PlayerConfig>,
    override val tournament: Tournament?,
    private val scope: CoroutineScope,
    private val planner: suspend (GameState, AiLevel) -> PlannerTable? = { _, _ -> null },
) : RaceSession {

    private val _frame = MutableStateFlow(RaceFrame(initialState, emptyList()))
    override val frame: StateFlow<RaceFrame> = _frame.asStateFlow()

    /** Fixed for a local race - the roster is decided on the setup screen and cannot change. */
    override val configs: StateFlow<List<PlayerConfig>> = MutableStateFlow(configs).asStateFlow()

    override val localPlayerId: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()

    private var aiJob: Job? = null

    private val _moveHistory = mutableListOf<MoveRecord>()
    /** Recorded moves for replay. Each entry captures the random seed so playback is deterministic. */
    val moveHistory: List<MoveRecord> get() = _moveHistory.toList()

    init {
        triggerAiIfNeeded()
    }

    override fun makeMove(target: Point) {
        val currentFrame = _frame.value
        val playerId = currentFrame.state.currentPlayer.id
        val config = configs.value.getOrNull(playerId)

        // Ignore taps during an AI turn or if the move is not legal.
        if (config?.isAi == true) return
        if (!GameEngine.isLegalMove(currentFrame.state, playerId, target)) return

        applyMove(currentFrame.state, playerId, target)
    }

    private fun applyMove(state: GameState, playerId: Int, target: Point) {
        val seed = Random.nextLong()
        val next = GameEngine.applyMove(state, playerId, target, Random(seed))
        _moveHistory.add(MoveRecord(playerId, target, seed))
        emitFrame(next)
        triggerAiIfNeeded()
    }

    private fun emitFrame(next: GameState) {
        val previous = _frame.value.state
        _frame.value = RaceFrame(next, diffEvents(previous, next))
    }

    private fun triggerAiIfNeeded() {
        if (_frame.value.state.phase != GamePhase.IN_PROGRESS) return

        val current = _frame.value.state.currentPlayer
        val config = configs.value.getOrNull(current.id)
        if (config?.isAi != true) return

        aiJob?.cancel()
        aiJob = scope.launch {
            delay(AI_MOVE_DELAY_MILLIS.milliseconds)

            val move = try {
                val state = _frame.value.state
                val table = planner(state, config.aiLevel)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    AiDriver.chooseMove(state, current.id, config.aiLevel, table)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (move == null || !GameEngine.isLegalMove(_frame.value.state, current.id, move)) {
                val next = GameEngine.forfeit(_frame.value.state, current.id)
                _moveHistory.add(MoveRecord(current.id, current.position, 0L, MoveKind.FORFEIT))
                emitFrame(next)
                triggerAiIfNeeded()
            } else {
                applyMove(_frame.value.state, current.id, move)
            }
        }
    }

    override fun quit() {
        aiJob?.cancel()
        aiJob = null
    }
}

/**
 * The events one transition produced: every player who was racing in [old] and has crashed or
 * finished in [new].
 *
 * @return One [GameEvent] per such player; empty when nothing changed status.
 */
internal fun diffEvents(old: GameState, new: GameState): List<GameEvent> =
    new.players.mapNotNull { player ->
        val previousStatus = old.players.firstOrNull { it.id == player.id }?.status
        if (previousStatus != PlayerStatus.RACING) return@mapNotNull null
        when (player.status) {
            PlayerStatus.CRASHED -> GameEvent(player.id, EventType.CRASHED)
            PlayerStatus.FINISHED -> GameEvent(player.id, EventType.FINISHED)
            PlayerStatus.RACING -> null
        }
    }

/** Total duration of an auto-replay, spread evenly across all moves. */
private const val REPLAY_TOTAL_MILLIS = 2000L

/**
 * Replay session: plays back a recorded [ReplayData], one move every 500 ms divided by
 * [speedMultiplier], with play, pause, single steps and seeking. Every intermediate state is
 * computed up front, so seeking is instant in either direction. Implements [RaceSession] so
 * the existing race UI can render it without changes.
 */
class ReplaySession(
    /** The recorded race being played back. */
    val replay: ReplayData,
    private val scope: CoroutineScope,
) : RaceSession {

    /** Whether playback is running, paused, or has reached the last move. */
    enum class PlaybackState { PLAYING, PAUSED, FINISHED }

    // Pre-compute all intermediate states for instant seeking. A recorded move the engine
    // refuses ends the replay there instead of taking the app down: a file can come from another
    // device, a damaged download, or a build whose rules this one does not know.
    private val states: List<GameState> = buildList {
        add(replay.initialState)
        var s = replay.initialState
        for (record in replay.moves) {
            s = runCatching { s.apply(record) }.getOrNull() ?: break
            add(s)
        }
    }

    /** Whether playback stops before the end of the record because a move could not be replayed. */
    val isTruncated: Boolean = states.size - 1 < replay.moves.size

    private val _currentIndex = MutableStateFlow(0)
    /** How many moves have been played back, 0 to [totalSteps]. */
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()
    /** How many moves can be played back - all of them, unless [isTruncated]. */
    val totalSteps: Int = states.size - 1

    private val _playbackState = MutableStateFlow(PlaybackState.PAUSED)
    /** Whether playback is running, paused or finished. */
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _speedMultiplier = MutableStateFlow(1f)
    /** Playback speed; 2 plays twice as fast as normal. */
    val speedMultiplier: StateFlow<Float> = _speedMultiplier.asStateFlow()

    private val baseStepDelayMs = 500L
    private var playbackJob: Job? = null

    private val _frame = MutableStateFlow(RaceFrame(replay.initialState, emptyList()))
    override val frame: StateFlow<RaceFrame> = _frame.asStateFlow()
    override val configs: StateFlow<List<PlayerConfig>> = MutableStateFlow(replay.configs).asStateFlow()
    override val tournament: Tournament? = null
    override val localPlayerId: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()

    /** Starts or resumes playback; from the end, it starts again from the beginning. */
    fun play() {
        if (_currentIndex.value >= totalSteps) {
            // Restart from beginning if at end
            seekTo(0)
        }
        _playbackState.value = PlaybackState.PLAYING
        startPlaybackLoop()
    }

    /** Stops playback where it is. */
    fun pause() {
        _playbackState.value = PlaybackState.PAUSED
        playbackJob?.cancel()
        playbackJob = null
    }

    /** Pauses when playing, plays otherwise. */
    fun togglePlayPause() {
        if (_playbackState.value == PlaybackState.PLAYING) pause() else play()
    }

    /** Pauses and moves one move forward. */
    fun stepForward() {
        pause()
        val next = (_currentIndex.value + 1).coerceAtMost(totalSteps)
        seekTo(next)
    }

    /** Pauses and moves one move back. */
    fun stepBackward() {
        pause()
        val prev = (_currentIndex.value - 1).coerceAtLeast(0)
        seekTo(prev)
    }

    /** Changes the playback speed, taking effect at once when playing; see [speedMultiplier]. */
    fun setSpeed(multiplier: Float) {
        _speedMultiplier.value = multiplier
        // Restart playback loop with new speed if playing
        if (_playbackState.value == PlaybackState.PLAYING) {
            playbackJob?.cancel()
            startPlaybackLoop()
        }
    }

    /**
     * Jumps to just after move [index], clamped to the replay. Reaching the end while playing stops
     * playback.
     */
    fun seekTo(index: Int) {
        val clamped = index.coerceIn(0, totalSteps)
        val previousState = states[_currentIndex.value]
        _currentIndex.value = clamped
        val newState = states[clamped]
        _frame.value = RaceFrame(newState, diffEvents(previousState, newState))
        if (clamped >= totalSteps && _playbackState.value == PlaybackState.PLAYING) {
            _playbackState.value = PlaybackState.FINISHED
            playbackJob?.cancel()
            playbackJob = null
        }
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (_currentIndex.value < totalSteps) {
                val delayMs = (baseStepDelayMs / _speedMultiplier.value).toLong().coerceAtLeast(1L)
                delay(delayMs)
                val next = _currentIndex.value + 1
                val previousState = states[_currentIndex.value]
                _currentIndex.value = next
                val newState = states[next]
                _frame.value = RaceFrame(newState, diffEvents(previousState, newState))
            }
            _playbackState.value = PlaybackState.FINISHED
        }
    }

    override fun makeMove(target: Point) {} // No-op for replay

    override fun quit() {
        playbackJob?.cancel()
        playbackJob = null
    }
}
