package info.piepgras.gridracer

import info.piepgras.gridracer.replay.ReplayData
import info.piepgras.gridracer.replay.isPlayable
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.AppSettings
import info.piepgras.gridracer.engine.CustomTrack
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.LeaderboardEntry
import info.piepgras.gridracer.engine.Placement
import info.piepgras.gridracer.engine.PlacementOrder
import info.piepgras.gridracer.engine.PlacementState
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Scoring
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.TrackDraft
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.engine.planner.AiDriver
import info.piepgras.gridracer.engine.planner.PlannerTable
import info.piepgras.gridracer.multiplayer.AccountInfo
import info.piepgras.gridracer.multiplayer.MultiplayerFrameSerializer
import info.piepgras.gridracer.multiplayer.RatingsPage
import info.piepgras.gridracer.multiplayer.RoomSummary
import info.piepgras.gridracer.multiplayer.ServerFeature
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.multiplayer.SharedTrackSummary
import info.piepgras.gridracer.ui.racesetup.MAX_PLAYERS
import info.piepgras.gridracer.ui.racesetup.RaceSetupPlan
import info.piepgras.gridracer.ui.racesetup.RaceSetupState
import info.piepgras.gridracer.ui.racesetup.SetupFormat
import info.piepgras.gridracer.ui.racesetup.SetupMode
import info.piepgras.gridracer.ui.racesetup.effectiveLaps
import info.piepgras.gridracer.ui.racesetup.isOnline
import info.piepgras.gridracer.ui.racesetup.plan
import info.piepgras.gridracer.ui.racesetup.usesRoomList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every screen the app can show, one case per screen.
 *
 * [MainActivity] renders exactly one of these at a time from [GameViewModel.screen]; there is no
 * navigation library and no back stack beyond what each screen's Back action sets.
 */
sealed interface Screen {
    /** The start screen. */
    data object MainMenu : Screen
    /** Gameplay, privacy, language, server and account settings. */
    data object Settings : Screen
    /** What each permission is for and whether the device has granted it. */
    data object Permissions : Screen
    /** Version, licence and the legal documents. */
    data object About : Screen
    /** The animated rules walkthrough. */
    data object HowToPlay : Screen

    /**
     * The one screen that sets up every kind of race - local, local tournament, online host,
     * join and spectate. Its form lives in [GameViewModel.raceSetup] rather than here,
     * so switching modes on it never rebuilds the screen (and a 100-track tournament itinerary
     * never has to fit through a Bundle).
     */
    data object RaceSetup : Screen
    /** Built-in, custom and shared tracks, with the way into the editor. */
    data object TracksBrowser : Screen
    /**
     * The freehand track editor.
     *
     * @property editingId The [CustomTrack.id] being edited, or null for a new track.
     */
    data class TrackEditor(val editingId: String? = null) : Screen
    /**
     * Which track the info screen is showing. Built-in tracks have no server/author identity;
     * custom tracks have a local id; shared tracks carry the full server metadata.
     */
    sealed interface TrackSource {
        /**
         * A track that ships with the app.
         *
         * @property track The track.
         */
        data class BuiltIn(val track: Track) : TrackSource
        /**
         * A track drawn on this device.
         *
         * @property customTrack The track with its editable draft.
         */
        data class Custom(val customTrack: CustomTrack) : TrackSource
        /**
         * A track from a server's shared library.
         *
         * @property summary Its list entry, with the server and author identity.
         */
        data class Shared(val summary: SharedTrackSummary) : TrackSource
    }

    /**
     * Details for any track: built-in, custom, or shared. Shows the track name, identity
     * (uuid / server id / user id), preview, and context-appropriate actions (download/rate
     * for shared, edit/delete/upload for custom, none for built-in).
     *
     * @property source Which track, and where it came from.
     */
    data class TrackInfo(val source: TrackSource) : Screen
    /** The local, on-device leaderboard. */
    data object Leaderboard : Screen

    /** In a server room: the lobby, the remote placement phase, or connecting to one of those. */
    data object OnlineRoom : Screen
    /**
     * Standings between two races of a local tournament.
     *
     * @property tournament The tournament, including the race just finished.
     * @property justFinishedState The final state of that race, shown alongside the standings.
     */
    data class TournamentStandingsInterstitial(val tournament: Tournament, val justFinishedState: GameState) : Screen
    /**
     * Final standings of a local tournament.
     *
     * @property tournament The completed tournament.
     */
    data class TournamentFinalResults(val tournament: Tournament) : Screen
    /**
     * The pre-race start-slot picking of a local race.
     *
     * @property placement The picking so far.
     * @property laps Laps the race will have.
     * @property tournament The local tournament this race belongs to, or null for a single race.
     * @property rules The rules the race will be played under.
     */
    data class Placement(
        val placement: PlacementState,
        val laps: Int,
        val tournament: Tournament? = null,
        val rules: RaceRules = RaceRules.current(gravelTrap = false),
    ) : Screen
    /**
     * A race in progress, local or online.
     *
     * @property state The race as it stands; snapshotted so it survives process death.
     * @property configs The seats, in player-id order.
     * @property tournament The local tournament this race belongs to, or null.
     * @property isMultiplayer Whether a server drives the race rather than this device.
     */
    data class Race(
        val state: GameState,
        val configs: List<PlayerConfig>,
        val tournament: Tournament? = null,
        val isMultiplayer: Boolean = false,
    ) : Screen
    /**
     * Results of a local single race.
     *
     * @property state The final state of the race.
     * @property configs The seats, in player-id order.
     */
    data class Results(val state: GameState, val configs: List<PlayerConfig>) : Screen
    /** Post-race results for a multiplayer race. Host can start a rematch; clients wait. */
    data class MultiplayerResults(
        /** Every player's result, as the server reported it. */
        val standings: List<info.piepgras.gridracer.multiplayer.RaceStanding>,
        /** Whether this device holds the room and may start a rematch. */
        val isHost: Boolean,
    ) : Screen

    /**
     * Standings of an online tournament, between races or at its end.
     *
     * @property tournament The tournament as the server reported it.
     * @property isHost Whether this device holds the room and moves it on.
     * @property isFinal Whether every race is done.
     */
    data class TournamentStandings(
        val tournament: Tournament,
        val isHost: Boolean,
        val isFinal: Boolean,
    ) : Screen
    /** The server's global Elo leaderboard. */
    data object Ratings : Screen
    /** Browse saved replays; reached from Settings. */
    data object ReplayBrowser : Screen
    /**
     * Watch a replay auto-play from start to finish.
     *
     * @property replay The race to play back.
     */
    data class ReplayViewer(val replay: ReplayData) : Screen
}

/**
 * What [Screen.OnlineRoom] is showing. Kept out of [Screen] because lobby and placement
 * updates arrive on every server delta: churning this flow re-renders one screen, whereas
 * churning `screen` re-evaluates the whole navigation `when`.
 */
sealed interface MultiplayerState {
    /** Connected (or connecting) but not in a room yet. */
    data object Idle : MultiplayerState
    /**
     * In a room, before the race.
     *
     * @property lobby The room's state as the server last sent it.
     * @property localPlayerId This device's seat, or -1 when only spectating.
     * @property isHost Whether this device holds the room.
     * @property readOnly Whether this device is spectating, so has no seat to act from.
     */
    data class Lobby(
        val lobby: ServerMessage.LobbyUpdate,
        val localPlayerId: Int,
        val isHost: Boolean,
        val readOnly: Boolean = false,
    ) : MultiplayerState
    /**
     * In a room, while the players pick their start slots.
     *
     * @property placement The picking so far, as the server last sent it.
     * @property laps Laps the race will have.
     * @property localPlayerId This device's seat, or -1 when only spectating.
     */
    data class Placement(
        val placement: info.piepgras.gridracer.engine.PlacementState,
        val laps: Int,
        val localPlayerId: Int,
    ) : MultiplayerState
}

/**
 * [roomCode] is null for a local race and non-null for a multiplayer one. It exists so a
 * process-death restore can tell the two apart: without it, restoring always rebuilt a
 * [LocalRaceSession] regardless of what the race actually was, so a killed-and-reopened
 * multiplayer race silently turned into a fake offline replay of the last known board, driven
 * by [SimpleAi] instead of the real remote players - while the real race kept running
 * server-side and eventually force-forfeited the player's turn with nobody watching. See
 * [reconnectToSavedRoom].
 */
@Serializable
private data class RaceSnapshot(
    val state: GameState,
    val configs: List<PlayerConfig>,
    val tournament: Tournament? = null,
    val roomCode: String? = null,
)

private const val AI_PLACEMENT_DELAY_MILLIS = 500L
private const val RACE_SNAPSHOT_KEY = "race_snapshot"

/**
 * The app's one ViewModel: navigation, race orchestration, settings and the multiplayer connection.
 *
 * Every screen reads its state from the flows here and reports user actions back through the
 * functions here; nothing else holds app state. A local race is driven by a [LocalRaceSession] (AI
 * turns paced with `delay`), an online one by the server through [MultiplayerClient]. The race in
 * progress is snapshotted into the [SavedStateHandle] so it survives process death - see
 * `restoreRaceInProgress`.
 */
@OptIn(ExperimentalSerializationApi::class)
class GameViewModel(
    private val leaderboardRepository: LeaderboardRepository,
    private val customTrackRepository: CustomTrackRepository,
    private val settingsRepository: SettingsRepository,
    private val replayRepository: ReplayRepository,
    private val soundEffects: SoundEffects,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _screen = MutableStateFlow<Screen>(Screen.MainMenu)
    /** The screen to show; see [Screen]. [MainActivity] renders exactly this one. */
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _events = MutableStateFlow<List<GameEvent>>(emptyList())
    /**
     * Crashes and finishes from the race's last transition, for sound and HUD feedback.
     */
    val events: StateFlow<List<GameEvent>> = _events.asStateFlow()

    private val _localPlayerId = MutableStateFlow<Int?>(null)
    /** This device's seat in an online race, or null in a local race. */
    val localPlayerId: StateFlow<Int?> = _localPlayerId.asStateFlow()

    private val _turnDeadline = MutableStateFlow<TurnDeadline?>(null)
    /**
     * How long the local player has left on the current turn, or null when they are not on the
     * clock. The server force-forfeits an unanswered turn; the race screen shows this so that
     * rule is visible rather than a surprise.
     */
    val turnDeadline: StateFlow<TurnDeadline?> = _turnDeadline.asStateFlow()

    private val _raceSetup = MutableStateFlow(RaceSetupState())
    /** The race-setup form, held here so switching its modes never rebuilds the screen. */
    val raceSetup: StateFlow<RaceSetupState> = _raceSetup.asStateFlow()

    private var multiplayerClient: MultiplayerClient? = null
    private var multiplayerJob: Job? = null
    /**
     * The URL the live client was built against. [MultiplayerClient] fixes its server URL at
     * construction, so editing the address on the setup screen has to rebuild the client.
     */
    private var connectedUrl: String? = null
    private val _multiplayerState = MutableStateFlow<MultiplayerState>(MultiplayerState.Idle)
    /** What the online-room screen shows; see [MultiplayerState]. */
    val multiplayerState: StateFlow<MultiplayerState> = _multiplayerState.asStateFlow()

    private val _roomInfo = MutableStateFlow<ServerMessage.RoomJoined?>(null)
    /**
     * The room this client is in: its code, this client's seat, and whether it is the host.
     *
     * Exposed separately from [multiplayerState] because the *code* is what a host has to
     * read out to invite somebody, and it was never surfaced anywhere in the UI - the lobby
     * knew the roster and the track but not the one string a guest has to type.
     */
    val roomInfo: StateFlow<ServerMessage.RoomJoined?> = _roomInfo.asStateFlow()

    private val _roomList = MutableStateFlow<List<RoomSummary>>(emptyList())
    /** Open rooms on the server, for the join list. */
    val roomList: StateFlow<List<RoomSummary>> = _roomList.asStateFlow()
    private val _roomListLoading = MutableStateFlow(false)
    /** Whether the room list is being fetched. */
    val roomListLoading: StateFlow<Boolean> = _roomListLoading.asStateFlow()
    private var roomListJob: Job? = null
    private val _multiplayerConnectionState = MutableStateFlow(ConnectionState.Disconnected)
    /** The connection to the multiplayer server, for the status line. */
    val multiplayerConnectionState: StateFlow<ConnectionState> = _multiplayerConnectionState.asStateFlow()
    private val _multiplayerConnectionEncrypted = MutableStateFlow(false)
    /**
     * Whether the live connection is up and encrypted - connected over `wss://`, so the
     * certificate was checked. False while connecting, so the padlock never shows ahead of the
     * handshake that earns it.
     */
    val multiplayerConnectionEncrypted: StateFlow<Boolean> = _multiplayerConnectionEncrypted.asStateFlow()
    private val _multiplayerError = MutableStateFlow<String?>(null)
    /** The latest connection or request error, as an error code or a server message. */
    val multiplayerError: StateFlow<String?> = _multiplayerError.asStateFlow()

    private val _kicked = MutableStateFlow<String?>(null)
    /** Non-null when the server kicked this client; the UI shows a dialog and returns to setup. */
    val kicked: StateFlow<String?> = _kicked.asStateFlow()

    private val _onlineRatings = MutableStateFlow<RatingsPage?>(null)
    /** The server's global Elo table. Distinct from [leaderboard], which is on-device only. */
    val onlineRatings: StateFlow<RatingsPage?> = _onlineRatings.asStateFlow()

    private val _networkTournament = MutableStateFlow<Tournament?>(null)
    /** The online tournament this device is in, as the server last described it, or null. */
    val networkTournament: StateFlow<Tournament?> = _networkTournament.asStateFlow()

    private val _account = MutableStateFlow<AccountInfo?>(null)
    /** The account the multiplayer client is logged in as, or null when anonymous. */
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    private val _accountDeleted = MutableStateFlow(false)
    /** Set when the server confirms the account was deleted, for the settings screen to show. */
    val accountDeleted: StateFlow<Boolean> = _accountDeleted.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    /** The most recent auth rejection from the server, for the account screen to display. */
    val authError: StateFlow<String?> = _authError.asStateFlow()

    /**
     * The server's shared-track library: browsing, downloading, uploading, rating, reporting.
     *
     * A holder of its own rather than seven more flows here - see [SharedTrackState]. The
     * flows below are re-exposed unchanged so the screens did not have to move with it.
     */
    private val sharedTracks = SharedTrackState(viewModelScope, customTrackRepository)

    /** The page of shared tracks last fetched; see [listSharedTracks]. */
    val sharedTrackList: StateFlow<ServerMessage.TrackList?> get() = sharedTracks.list
    /** The shared track last downloaded, until it is saved or dismissed. */
    val downloadedTrack: StateFlow<ServerMessage.TrackDownloaded?> get() = sharedTracks.downloaded
    /** The server's confirmation of the last upload, until cleared. */
    val trackUploadResult: StateFlow<ServerMessage.TrackUploaded?> get() = sharedTracks.uploadResult
    /** Why the last upload failed, until cleared. */
    val trackUploadError: StateFlow<String?> get() = sharedTracks.uploadError
    /** Why the last download failed, until cleared. */
    val trackDownloadError: StateFlow<String?> get() = sharedTracks.downloadError
    /** The server's confirmation of the last rating, with the new average. */
    val trackRated: StateFlow<ServerMessage.TrackRated?> get() = sharedTracks.rated
    /** Why the last rating failed, until cleared. */
    val trackRateError: StateFlow<String?> get() = sharedTracks.rateError

    // ---- Multiplayer race results / host promotion ------------------------------------
    private val _raceResult = MutableStateFlow<ServerMessage.RaceFinished?>(null)
    /** Final standings of the online race just finished, or null. */
    val raceResult: StateFlow<ServerMessage.RaceFinished?> = _raceResult.asStateFlow()

    private val _hostPromoted = MutableStateFlow<ServerMessage.HostPromoted?>(null)
    /** Set when this device has become the room's host, for a one-time notice. */
    val hostPromoted: StateFlow<ServerMessage.HostPromoted?> = _hostPromoted.asStateFlow()

    /** The on-device leaderboard, highest points first. */
    val leaderboard: StateFlow<List<LeaderboardEntry>> = leaderboardRepository.entries
    /** Every custom track on this device - drawn here, or downloaded from a server's library. */
    val customTracks: StateFlow<List<CustomTrack>> = customTrackRepository.tracks
    /** The user's settings; update them with [updateSettings]. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
    /** Every replay saved on this device, oldest first; at most 20 are kept. */
    val savedReplays: StateFlow<List<ReplayData>> = replayRepository.replays

    private val _lastReplay = MutableStateFlow<ReplayData?>(null)
    /** The most recently finished local race's replay, for the "Watch Replay" button. */
    val lastReplay: StateFlow<ReplayData?> = _lastReplay.asStateFlow()

    private var _raceSession: RaceSession? = null
    /** The race being driven or played back right now - local, online or a replay - or null. */
    val raceSession: RaceSession? get() = _raceSession
    /** [raceSession] when it is a replay being played back, otherwise null. */
    val replaySession: ReplaySession? get() = _raceSession as? ReplaySession
    private var raceJob: Job? = null

    /** Planning tables for the computer drivers of local races; see [AiPlannerCache]. */
    private val aiPlanners = AiPlannerCache(viewModelScope)

    /** How a local race's computer seats get their planning tables. */
    private val localPlanner: suspend (GameState, AiLevel) -> PlannerTable? = { state, level ->
        aiPlanners.table(state.track, state.rules, level)
    }

    init {
        val snapshot = restoreRaceInProgress()
        when {
            snapshot == null -> _screen.value = Screen.MainMenu
            snapshot.roomCode != null -> reconnectToSavedRoom(snapshot.roomCode)
            else -> {
                val session = LocalRaceSession(
                    initialState = snapshot.state,
                    configs = snapshot.configs,
                    tournament = snapshot.tournament,
                    scope = viewModelScope,
                    planner = localPlanner,
                )
                // Set the screen immediately so process-death restore doesn’t flash MainMenu.
                _screen.value = Screen.Race(snapshot.state, snapshot.configs, snapshot.tournament)
                startRaceSession(session)
            }
        }
    }

    /**
     * Reconnects to the live room after a cold start finds a saved snapshot of a multiplayer
     * race - see [RaceSnapshot.roomCode]. The stale [RaceSnapshot.state]/[RaceSnapshot.configs]
     * are deliberately not used for anything: the server is authoritative and may have already
     * moved on (finished the race, auto-forfeited the stale turn) while the process was dead,
     * so the only correct board is whatever it sends back. [Screen.OnlineRoom] shows its
     * existing connecting spinner until that arrives.
     *
     * [connectMultiplayer] clears the saved snapshot as one of its normal side effects (via
     * [endRaceSession]) the moment this attempt starts, same as it does for any other fresh
     * connection - if the reconnect then succeeds, the running race immediately starts saving a
     * fresh one every frame anyway, same as always.
     */
    private fun reconnectToSavedRoom(roomCode: String) {
        _screen.value = Screen.OnlineRoom
        connectMultiplayer()
        multiplayerClient?.reconnectToRoom(roomCode)

        viewModelScope.launch {
            // The first error after a cold-start reconnect attempt, before ever landing in the
            // room, means the room is gone (reaped, race long finished) - there is nothing left
            // to reconnect to. An error arriving *after* _roomInfo is populated is an ordinary
            // in-race error and already has its own handling; this is not that.
            multiplayerError.filterNotNull().first()
            if (_roomInfo.value == null) {
                clearRaceSnapshot()
                closeMultiplayer()
                _screen.value = Screen.MainMenu
            }
        }
    }

    /**
     * Shows [screen].
     *
     * Leaving the race screens ends the race session, and leaving the online screens closes the
     * server connection, so neither keeps running behind a screen nobody is looking at.
     */
    fun goTo(screen: Screen) {
        // Leaving any race-related screen should stop the active race session so it cannot
        // continue to consume coroutine resources in the background.
        if (_raceSession != null &&
            screen !is Screen.Race &&
            screen !is Screen.Results &&
            screen !is Screen.Placement &&
            screen !is Screen.TournamentStandings &&
            screen !is Screen.ReplayViewer
        ) {
            endRaceSession()
        }
        // Leaving the multiplayer flow should disconnect from the server.
        if (multiplayerClient != null && !keepsMultiplayerConnection(screen)) {
            closeMultiplayer()
        }
        _screen.value = screen
    }

    /**
     * Screens that a live server connection has to survive. [Screen.RaceSetup] is on the list
     * because toggling its mode chips must not tear the socket down - that is handled by
     * [updateRaceSetup] instead, which knows whether the new mode is online at all.
     */
    private fun keepsMultiplayerConnection(screen: Screen): Boolean = when (screen) {
        is Screen.Settings,
        is Screen.RaceSetup,
        is Screen.OnlineRoom,
        is Screen.TournamentStandings,
        is Screen.Ratings,
        is Screen.Placement,
        is Screen.Race,
        is Screen.MultiplayerResults,
        is Screen.TrackInfo,
        is Screen.TrackEditor -> true
        else -> false
    }

    // --------------------------------------------------------------------------------------------
    // Race setup
    // --------------------------------------------------------------------------------------------

    /**
     * Opens the unified setup screen on a fresh form. Local by default, so it opens no socket. The
     * gravel trap and the level of a new computer seat start as they were last raced.
     */
    fun openRaceSetup() {
        val settings = settingsRepository.settings.value
        _raceSetup.value = RaceSetupState(
            gravelTrap = settings.gravelTrapEnabled,
            aiLevels = List(MAX_PLAYERS) { settings.defaultAiLevel },
        )
        goTo(Screen.RaceSetup)
    }

    /**
     * The single mutator for the setup form. Connecting is a side effect of picking an online
     * mode rather than a separate step, so "paste an address and press Host" is one gesture -
     * [MultiplayerClient] queues anything sent before the handshake completes.
     */
    fun updateRaceSetup(transform: (RaceSetupState) -> RaceSetupState) {
        val before = _raceSetup.value
        val after = transform(before)
        if (after == before) return
        _raceSetup.value = after
        if (before.mode.isOnline != after.mode.isOnline) {
            if (after.mode.isOnline) connectMultiplayer() else closeMultiplayer()
        }
        if (after.mode.usesRoomList && !before.mode.usesRoomList) refreshRoomList()
    }

    /**
     * Persists the typed server address, reconnecting if the live client is on a different one.
     * Call on focus loss or IME-done, never per keystroke - a keystroke-level reconnect would
     * tear down a WebSocket for every character typed.
     */
    fun commitServerUrl(url: String) {
        val trimmed = url.trim()
        settingsRepository.update { current ->
            if (current.multiplayerServerUrl.trim() == trimmed) {
                current
            } else {
                // A session token belongs to the server that issued it. Kept, it would be handed
                // to the new server on Identify - a credential for the public server sent to a
                // stranger's, and over plain ws:// to anyone on that network. The new server
                // would not have recognised it anyway, so nothing is lost but the leak.
                current.copy(multiplayerServerUrl = trimmed, sessionToken = null, accountDisplayName = null)
            }
        }
        if (multiplayerClient != null && connectedUrl != trimmed) {
            closeMultiplayer()
            connectMultiplayer()
        }
    }

    /** Starts connecting to the configured server. Idempotent, and never navigates. */
    private fun connectMultiplayer() {
        if (multiplayerClient != null) return
        endRaceSession()
        _multiplayerState.value = MultiplayerState.Idle
        _multiplayerError.value = null
        _multiplayerConnectionState.value = ConnectionState.Connecting

        val settings = settingsRepository.settings.value
        connectedUrl = settings.multiplayerServerUrl
        val client = MultiplayerClient(
            serverUrl = settings.multiplayerServerUrl,
            serializer = MultiplayerFrameSerializer(json),
            scope = viewModelScope,
            initialToken = settings.multiplayerToken,
            onTokenAssigned = { token ->
                settingsRepository.update { it.copy(multiplayerToken = token) }
            },
            onAccountChanged = { session ->
                if (session != null) {
                    settingsRepository.update {
                        it.copy(sessionToken = session.sessionToken, accountDisplayName = session.account.displayName)
                    }
                } else {
                    settingsRepository.update { it.copy(sessionToken = null, accountDisplayName = null) }
                }
            },
        )
        client.setSessionToken(settings.sessionToken)
        multiplayerClient = client

        multiplayerJob = viewModelScope.launch {
            observeMultiplayerClient(client)
        }
        viewModelScope.launch {
            try {
                client.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _multiplayerError.value = e.message ?: MultiplayerClient.ERROR_CONNECT
            }
        }
    }

    private fun closeMultiplayer() {
        multiplayerJob?.cancel()
        multiplayerJob = null
        roomListJob?.cancel()
        roomListJob = null
        val client = multiplayerClient
        multiplayerClient = null
        connectedUrl = null
        // Synchronously, before the suspending close is even scheduled: otherwise a pending
        // reconnect can fire one more round against a client nobody observes any more.
        client?.markClosed()
        viewModelScope.launch { client?.close() }
        _multiplayerState.value = MultiplayerState.Idle
        _multiplayerConnectionState.value = ConnectionState.Disconnected
        _multiplayerConnectionEncrypted.value = false
        _multiplayerError.value = null
        _roomInfo.value = null
        _turnDeadline.value = null
        _kicked.value = null
        _localPlayerId.value = null
        _onlineRatings.value = null
        _roomList.value = emptyList()
        _roomListLoading.value = false
        _account.value = null
        _authError.value = null
        _serverFeatures.value = emptySet()
        sharedTracks.reset()
        _raceResult.value = null
        _hostPromoted.value = null
    }

    // ---- Account / auth ---------------------------------------------------------------

    /**
     * Ensures a multiplayer connection is open and returns whether the server advertises
     * account support. The account screen calls this before showing the login form so a
     * server that does not support accounts can be reported up front.
     */
    fun ensureMultiplayerConnected() {
        if (multiplayerClient == null) connectMultiplayer()
    }

    private val _serverFeatures = MutableStateFlow<Set<String>>(emptySet())
    /**
     * What the connected server said it supports, from its `Welcome`.
     *
     * A flow, not a function. It was read straight from the client during composition, so
     * nothing recomposed when the handshake landed - the login form appeared only because the
     * connection-state flow happened to change at roughly the same moment. That is luck, and it
     * breaks the first time the ordering shifts.
     */
    val serverFeatures: StateFlow<Set<String>> = _serverFeatures.asStateFlow()

    /** Whether the connected server advertises the `accounts` feature. */
    val serverSupportsAccounts: StateFlow<Boolean> = _serverFeatures
        .map { ServerFeature.ACCOUNTS in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether the connected server can delete an account on request.
     *
     * Separate from [serverSupportsAccounts]: a server may predate account deletion, and the
     * delete dialog falls back to the email route rather than sending a message that would be
     * silently ignored.
     */
    val serverSupportsAccountDeletion: StateFlow<Boolean> = _serverFeatures
        .map { ServerFeature.ACCOUNT_DELETION in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the connected server can race a room with the gravel trap; see [ServerFeature.GRAVEL_TRAP]. */
    val serverSupportsGravelTrap: StateFlow<Boolean> = _serverFeatures
        .map { ServerFeature.GRAVEL_TRAP in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether the connected server seats computer drivers at a chosen level; see
     * [ServerFeature.AI_LEVELS]. Without it every computer seat is the original EASY driver.
     */
    val serverSupportsAiLevels: StateFlow<Boolean> = _serverFeatures
        .map { ServerFeature.AI_LEVELS in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Register a new account on the connected server. */
    fun registerAccount(username: String, password: String, displayName: String? = null) {
        ensureMultiplayerConnected()
        multiplayerClient?.register(username, password, displayName)
    }

    /** Log in to an existing account on the connected server. */
    fun loginAccount(username: String, password: String) {
        ensureMultiplayerConnected()
        multiplayerClient?.login(username, password)
    }

    /** Log out and clear the persisted session token. */
    fun logoutAccount() {
        multiplayerClient?.logout()
        settingsRepository.update { it.copy(sessionToken = null, accountDisplayName = null) }
        _account.value = null
    }

    /**
     * Permanently delete the account on the connected server, after the server re-checks
     * [password]. The persisted session token goes with it, whatever the server answers - it
     * is either invalid (the account is gone) or unchanged (the password was wrong, and the
     * user is still logged in through the live connection).
     */
    fun deleteAccount(password: String) {
        ensureMultiplayerConnected()
        multiplayerClient?.deleteAccount(password)
    }

    /** Clears the one-shot "account deleted" flag once the settings screen has shown it. */
    fun clearAccountDeleted() {
        _accountDeleted.value = false
        multiplayerClient?.clearAccountDeleted()
    }

    /** Clears the last auth rejection, so the account screen can dismiss an old error. */
    fun clearAuthError() {
        _authError.value = null
        multiplayerClient?.let { /* the client's own flow is the source of truth; nothing to push */ }
    }

    /**
     * Dispatches the configured race. The single exit from the setup screen: every mode's
     * start button lands here, and [RaceSetupState.plan] has already decided whether it may.
     */
    fun submitRaceSetup() {
        val state = _raceSetup.value
        val plan = state.plan(allTracks())
        if (!plan.canSubmit) return
        val laps = state.effectiveLaps
        val name = settingsRepository.settings.value.playerName
        rememberSetupChoices(state, plan)
        when (state.mode) {
            SetupMode.LOCAL -> when (state.format) {
                SetupFormat.SINGLE_RACE -> startRace(plan.track ?: return, plan.configs, laps, plan.rules)
                SetupFormat.TOURNAMENT -> startTournament(plan.itinerary, plan.configs, laps, plan.rules.gravelTrap)
            }
            SetupMode.HOST -> when (state.format) {
                SetupFormat.SINGLE_RACE ->
                    hostGame(plan.track ?: return, laps, plan.effectivePlayerCount, name, state.roomPassword, plan.rules.gravelTrap)
                SetupFormat.TOURNAMENT ->
                    hostTournament(plan.itinerary, laps, plan.effectivePlayerCount, name, state.roomPassword, plan.rules.gravelTrap)
            }
            SetupMode.JOIN -> joinGame(state.roomCode, name, state.roomPassword)
            SetupMode.SPECTATE -> spectate(state.roomCode)
        }
    }

    /**
     * Carries the gravel trap choice, and the level of the last computer seat raced, over to the
     * next time the setup screen opens.
     */
    private fun rememberSetupChoices(state: RaceSetupState, plan: RaceSetupPlan) {
        if (state.mode != SetupMode.LOCAL && state.mode != SetupMode.HOST) return
        val lastAiLevel = plan.configs.lastOrNull { it.isAi }?.aiLevel
        settingsRepository.update { settings ->
            settings.copy(
                gravelTrapEnabled = state.gravelTrap,
                defaultAiLevel = lastAiLevel ?: settings.defaultAiLevel,
            )
        }
    }

    /** Built-in tracks plus the user's own - the catalogue every setup mode picks from. */
    private fun allTracks(): List<Track> = Tracks.all + customTracks.value.map { it.track }

    /** Same as [allTracks] but as a flow, for the lobby track-picker. */
    val allTracksForLobby: StateFlow<List<Track>> = customTracks
        .map { Tracks.all + it.map { ct -> ct.track } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Tracks.all)

    private suspend fun observeMultiplayerClient(client: MultiplayerClient) = coroutineScope {
        try {
            launch {
                client.connectionState.collect {
                    _multiplayerConnectionState.value = it
                    _multiplayerConnectionEncrypted.value = it == ConnectionState.Connected && client.isEncrypted
                }
            }
            launch { client.error.collect { _multiplayerError.value = it } }
            launch { client.kicked.collect { onKicked(it) } }
            launch { client.room.collect { onRoomJoined(it, client) } }
            launch { client.lobby.filterNotNull().collect { onLobbyUpdate(it, client) } }
            launch { client.placement.filterNotNull().collect { onPlacementUpdate(it, client) } }
            launch { client.race.collect { onRaceUpdate(it, client) } }
            launch { client.tournament.collect { onTournamentUpdate(it, client) } }
            launch { client.onlineRatings.collect { _onlineRatings.value = it } }
            launch { client.account.collect { _account.value = it } }
            launch { client.serverFeatures.collect { _serverFeatures.value = it } }
            launch { client.authError.collect { _authError.value = it } }
            launch {
                client.accountDeleted.collect { deleted ->
                    _accountDeleted.value = deleted
                    if (deleted) {
                        // The session token the settings file holds names an account that no
                        // longer exists; leaving it there would have the next connection
                        // identify with a credential the server has already thrown away.
                        settingsRepository.update { it.copy(sessionToken = null, accountDisplayName = null) }
                    }
                }
            }
            sharedTracks.bind(client)
            launch { client.raceResult.collect { onRaceResult(it) } }
            launch {
                client.raceReplay.collect { replay ->
                    if (replay != null) {
                        _lastReplay.value = replayRepository.save(replay)
                    }
                }
            }
            launch { client.hostPromoted.collect { _hostPromoted.value = it } }
        } catch (e: CancellationException) {
            // expected on close
        }
    }

    private fun onRoomJoined(room: ServerMessage.RoomJoined?, client: MultiplayerClient) {
        _roomInfo.value = room
        if (room == null) {
            _localPlayerId.value = null
            return
        }
        _localPlayerId.value = room.playerId
        leaveSetupForRoom()
    }

    /**
     * Server messages may only carry the user *off* the setup screen - never between the
     * screens downstream of it. RoomJoined, TournamentStarted and LobbyUpdate arrive on three
     * separate flows with no ordering guarantee between them, so a handler that navigated
     * unconditionally could yank a tournament host back out of the tournament lobby.
     */
    private fun leaveSetupForRoom() {
        if (_screen.value is Screen.RaceSetup) _screen.value = Screen.OnlineRoom
    }

    private fun onLobbyUpdate(lobby: ServerMessage.LobbyUpdate, client: MultiplayerClient) {
        val room = client.room.value
        _localPlayerId.value = room?.playerId
        val readOnly = room?.playerId == -1
        val seat = room?.playerId ?: -1
        // Who holds the room comes from the lobby update itself, not from a HostPromoted the
        // client may never have seen. A promotion that arrives as its own message can be lost
        // - dropped frame, reconnect, a client that was on another screen - and the result was
        // a room whose new host did not know they were the host and whose Start button
        // therefore never appeared. The roster and the host now travel together, so the next
        // lobby update repairs it whatever was missed. Falls back to the room's own flag for a
        // server that predates the field (hostPlayerId defaults to 0 there).
        val isHost = if (lobby.connected.isEmpty() && lobby.hostPlayerId == 0) {
            room?.isHost ?: false
        } else {
            seat >= 0 && seat == lobby.hostPlayerId
        }
        _multiplayerState.value = MultiplayerState.Lobby(
            lobby = lobby,
            localPlayerId = seat,
            isHost = isHost,
            readOnly = readOnly,
        )
        // When the host calls ReturnToLobby after a race, the server broadcasts a LobbyUpdate
        // that pulls everyone off the results screen back into the lobby.
        if (_screen.value is Screen.MultiplayerResults || _screen.value is Screen.TournamentStandings) {
            _screen.value = Screen.OnlineRoom
        }
        leaveSetupForRoom()
    }

    private fun onPlacementUpdate(msg: ServerMessage.PlacementStarted, client: MultiplayerClient) {
        val room = client.room.value
        _localPlayerId.value = room?.playerId
        // The one handler that may pull the user off a downstream screen: between tournament
        // races a placement starts while the standings are up. Safe to do unconditionally -
        // the server never sends a placement mid-race, and PlacementStarted clears the race
        // frame client-side anyway.
        if (_screen.value !is Screen.OnlineRoom) _screen.value = Screen.OnlineRoom
        _multiplayerState.value = MultiplayerState.Placement(
            placement = msg.placement,
            laps = msg.laps,
            localPlayerId = room?.playerId ?: -1,
        )
    }

    private fun onRaceUpdate(frame: RaceFrame?, client: MultiplayerClient) {
        if (frame == null || _raceSession != null) return
        val tournament = buildNetworkTournament(client.tournament.value, client.configs.value)
        val session = NetworkRaceSession(client, viewModelScope, tournament)
        startRaceSession(session)
    }

    private fun onTournamentUpdate(info: TournamentInfo?, client: MultiplayerClient) {
        if (info == null) return
        if (info.currentTrackIndex > 0 || info.isFinished) {
            endRaceSession()
        }
        val tournament = buildNetworkTournament(info, client.configs.value) ?: return
        _networkTournament.value = tournament
        _screen.value = when {
            info.isFinished -> Screen.TournamentStandings(
                tournament = tournament,
                isHost = client.room.value?.isHost == true,
                isFinal = true,
            )
            info.currentTrackIndex == 0 -> Screen.OnlineRoom
            else -> Screen.TournamentStandings(
                tournament = tournament,
                isHost = client.room.value?.isHost == true,
                isFinal = false,
            )
        }
    }

    private fun buildNetworkTournament(info: TournamentInfo?, configs: List<PlayerConfig>): Tournament? {
        info ?: return null
        return Tournament(
            tracks = info.tracks,
            configs = configs,
            laps = info.laps,
            currentTrackIndex = info.currentTrackIndex,
            standings = info.standings.map { standing ->
                TournamentStanding(
                    playerSlot = standing.playerSlot,
                    name = standing.name,
                    isAi = standing.isAi,
                    points = standing.points,
                )
            },
        )
    }

    private val placementRandomized: Boolean
        get() = settingsRepository.settings.value.placementOrder == PlacementOrder.RANDOM

    /**
     * Starts a local single race: placement first, then the race.
     *
     * @param track The track to race.
     * @param configs The seats, in player-id order.
     * @param laps Laps to race.
     * @param rules The rules to race under.
     */
    fun startRace(
        track: Track,
        configs: List<PlayerConfig>,
        laps: Int = 1,
        rules: RaceRules = RaceRules.current(gravelTrap = false),
    ) {
        endRaceSession()
        _events.value = emptyList()
        val placement = beginPlacement(track, configs, rules) ?: return
        setPlacementScreen(placement, laps, tournament = null, rules = rules)
    }

    /**
     * Null when this track cannot seat this field. The setup screens already refuse to offer
     * that combination, so reaching here means a track changed under a saved selection - which
     * is worth bouncing back to the menu for, and not worth taking the process down for.
     */
    private fun beginPlacement(track: Track, configs: List<PlayerConfig>, rules: RaceRules): PlacementState? {
        val placement = runCatching { Placement.start(track, configs, randomized = placementRandomized) }.getOrNull()
        if (placement == null) {
            _screen.value = Screen.MainMenu
            return null
        }
        // The computer drivers' tables build while the players pick their slots.
        aiPlanners.prefetch(track, rules, configs.filter { it.isAi }.map { it.aiLevel })
        return placement
    }

    private fun startTournament(tracks: List<Track>, configs: List<PlayerConfig>, laps: Int, gravelTrap: Boolean) {
        endRaceSession()
        require(tracks.size in MIN_TOURNAMENT_TRACKS..MAX_TOURNAMENT_TRACKS) {
            "a tournament needs $MIN_TOURNAMENT_TRACKS to $MAX_TOURNAMENT_TRACKS tracks"
        }
        val tournament = Tournament(
            tracks = tracks,
            configs = configs,
            laps = laps,
            standings = configs.mapIndexed { i, c -> TournamentStanding(playerSlot = i, name = c.name, isAi = c.isAi) },
            gravelTrap = gravelTrap,
        )
        _events.value = emptyList()
        val placement = beginPlacement(tournament.currentTrack, configs, tournament.rules) ?: return
        setPlacementScreen(placement, laps, tournament, tournament.rules)
    }

    /** From the standings interstitial's Continue button - begins placement for the next track. */
    fun continueTournament(tournament: Tournament) {
        endRaceSession()
        _events.value = emptyList()
        val placement = beginPlacement(tournament.currentTrack, tournament.configs, tournament.rules) ?: return
        setPlacementScreen(placement, tournament.laps, tournament, tournament.rules)
    }

    /**
     * Takes [slot] for the human player whose turn it is to pick in a local placement. Ignored when
     * it is an AI's turn to pick.
     */
    fun choosePlacementSlot(slot: Point) {
        val current = (_screen.value as? Screen.Placement) ?: return
        val playerId = current.placement.currentPlayerIndex
        if (current.placement.configs.getOrNull(playerId)?.isAi == true) return
        advancePlacement(Placement.choose(current.placement, slot), current.laps, current.tournament, current.rules)
    }

    /** Applies [transform] to the settings and persists the result. */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsRepository.update(transform)
    }

    // --------------------------------------------------------------------------------------------
    // Multiplayer
    // --------------------------------------------------------------------------------------------

    /**
     * Refreshes the public lobby list behind the setup screen's Join and Spectate modes.
     * Cancel-and-replace, so hammering Refresh can't leave overlapping requests racing to
     * publish. Plain HTTP - it only needs a client to exist, not a live WebSocket.
     */
    fun refreshRoomList() {
        val client = multiplayerClient ?: return
        roomListJob?.cancel()
        _roomListLoading.value = true
        roomListJob = viewModelScope.launch {
            try {
                _roomList.value = client.fetchRooms()
            } finally {
                _roomListLoading.value = false
            }
        }
    }

    private fun hostGame(track: Track, laps: Int, maxPlayers: Int, hostName: String, password: String?, gravelTrap: Boolean) {
        multiplayerClient?.host(track, laps, maxPlayers, hostName.ifBlank { "Host" }, password, gravelTrap)
    }

    private fun joinGame(roomCode: String, playerName: String, password: String?) {
        multiplayerClient?.join(roomCode, playerName.ifBlank { "Player" }, password)
    }

    /** Sets this player's ready flag in the online lobby. */
    fun setOnlineReady(ready: Boolean) {
        multiplayerClient?.setReady(ready)
    }

    /** Host-only: asks the server to start the room's race. */
    fun startOnlineRace() {
        multiplayerClient?.startRace()
    }

    /** Host-only: remove a seat from the online lobby - a human or an AI driver. */
    fun kickOnlinePlayer(playerId: Int) {
        multiplayerClient?.kickPlayer(playerId)
    }

    /**
     * Host-only: seat an AI driver in the next free slot, at the level a new computer seat starts
     * on - or EASY on a server without levels, which is what it would seat anyway.
     */
    fun addOnlineAiPlayer() {
        val level = if (serverSupportsAiLevels.value) settingsRepository.settings.value.defaultAiLevel else AiLevel.EASY
        multiplayerClient?.addAiPlayer(level)
    }

    /** Host-only: change how well the computer seat [playerId] drives; remembered for the next seat. */
    fun changeOnlineAiLevel(playerId: Int, level: AiLevel) {
        settingsRepository.update { it.copy(defaultAiLevel = level) }
        multiplayerClient?.changeAiLevel(playerId, level)
    }

    /** Host-only: switch the gravel trap for the room's races. */
    fun changeOnlineGravelTrap(enabled: Boolean) {
        multiplayerClient?.changeGravelTrap(enabled)
    }

    /**
     * Called when the server sends [ServerMessage.RaceFinished]. Ends the race session and
     * shows the multiplayer results screen. The host can start a rematch from there; clients
     * wait for the host.
     */
    private fun onRaceResult(result: ServerMessage.RaceFinished?) {
        if (result == null) return
        endRaceSession()
        val isHost = multiplayerClient?.room?.value?.isHost ?: false
        _screen.value = Screen.MultiplayerResults(result.standings, isHost)
    }

    /** Host-only: start a rematch from the multiplayer results screen. */
    fun startRematch() {
        multiplayerClient?.returnToLobby()
    }

    /**
     * Called when the server kicks this client. Sets the kicked flow for the UI to show a
     * dialog, ends any active race session, and returns to the race setup screen (keeping
     * the multiplayer connection alive so the user can re-host or join another room).
     */
    private fun onKicked(reason: String?) {
        if (reason == null) return
        endRaceSession()
        _kicked.value = reason
        _multiplayerState.value = MultiplayerState.Idle
        goTo(Screen.RaceSetup)
    }

    /** Clear the kicked notification after the UI has shown it. */
    fun clearKicked() {
        _kicked.value = null
        multiplayerClient?.clearKicked()
    }

    /** Host-only: change the track of a non-tournament room while in the lobby. */
    fun changeOnlineTrack(track: Track) {
        multiplayerClient?.changeTrack(track)
    }

    /** Host-only: replace the track list of a tournament room while in the lobby. */
    fun changeOnlineTournamentTracks(tracks: List<Track>) {
        multiplayerClient?.changeTournamentTracks(tracks)
    }

    /** Host-only: change the lap count while in the lobby. */
    fun changeOnlineLaps(laps: Int) {
        multiplayerClient?.changeLaps(laps)
    }

    /** Host-only: change how many seats the room has while in the lobby. */
    fun changeOnlineMaxPlayers(maxPlayers: Int) {
        multiplayerClient?.changeMaxPlayers(maxPlayers)
    }

    /** Host-only: change the per-turn move timeout (seconds) while in the lobby. */
    fun changeOnlineTurnTimeoutSeconds(seconds: Int) {
        multiplayerClient?.changeTurnTimeoutSeconds(seconds)
    }

    /** Clear the host-promoted notification after the UI has shown it. */
    fun clearHostPromoted() {
        _hostPromoted.value = null
        multiplayerClient?.clearHostPromoted()
    }

    /**
     * Picks [slot] in an online placement, based on the placement revision this device last saw.
     */
    fun chooseOnlinePlacementSlot(slot: Point) {
        val placement = (_multiplayerState.value as? MultiplayerState.Placement)?.placement ?: return
        multiplayerClient?.choosePlacement(placement.revision, slot)
    }

    /**
     * Leaves the room and disconnects, waiting until the server has the Leave - so it can tell
     * quitting from a dropped connection.
     */
    fun leaveOnlineGame() {
        viewModelScope.launch {
            // One call, and it waits: the Leave has to reach the server *before* the socket
            // goes, or the server cannot tell "I quit" from "my phone dropped" - and those
            // mean opposite things for the seat and for who ends up holding the room.
            multiplayerClient?.leaveAndClose()
            closeMultiplayer()
            // Through goTo, not a bare assignment: leaving mid-network-race has to end the
            // race session too, or its job keeps collecting a client that is already gone.
            goTo(Screen.MainMenu)
        }
    }

    /**
     * A repeat failure with the identical message doesn't re-emit from the client's own error
     * flow, so a dismissed dialog stays dismissed while a bad address keeps retrying.
     */
    fun clearMultiplayerError() {
        _multiplayerError.value = null
    }

    // Tournament, spectator and ratings flows.

    private fun hostTournament(
        itinerary: List<Track>,
        laps: Int,
        maxPlayers: Int,
        hostName: String,
        password: String?,
        gravelTrap: Boolean,
    ) {
        if (itinerary.isEmpty()) return
        multiplayerClient?.hostTournament(itinerary, laps, maxPlayers, hostName.ifBlank { "Host" }, password, gravelTrap)
    }

    private fun spectate(roomCode: String) {
        multiplayerClient?.spectate(roomCode)
    }

    /** Asks for the next page and appends it, keeping the rows already on screen. */
    fun loadMoreLeaderboard() {
        val current = _onlineRatings.value ?: return
        val nextOffset = current.offset + current.entries.size
        if (nextOffset >= current.totalEntries) return
        multiplayerClient?.requestLeaderboard(offset = nextOffset)
    }

    /** Shows the global Elo leaderboard and requests its first page. */
    fun viewRatings() {
        _screen.value = Screen.Ratings
        _onlineRatings.value = null
        multiplayerClient?.requestLeaderboard()
    }

    // --------------------------------------------------------------------------------------------
    // Placement / race
    // --------------------------------------------------------------------------------------------

    private fun setPlacementScreen(placement: PlacementState, laps: Int, tournament: Tournament?, rules: RaceRules) {
        _screen.value = Screen.Placement(placement, laps, tournament, rules)
        triggerAiPlacementIfNeeded(placement, laps, tournament, rules)
    }

    private fun triggerAiPlacementIfNeeded(placement: PlacementState, laps: Int, tournament: Tournament?, rules: RaceRules) {
        if (placement.isComplete) return
        val current = placement.configs.getOrNull(placement.currentPlayerIndex) ?: return
        if (!current.isAi) return
        viewModelScope.launch {
            delay(AI_PLACEMENT_DELAY_MILLIS.milliseconds)
            // The stronger drivers pick the slot with the shortest race ahead of it, which takes
            // their table; it has been building since placement began.
            val table = aiPlanners.table(placement.track, rules, current.aiLevel)
            if ((_screen.value as? Screen.Placement)?.placement != placement) return@launch
            val slot = AiDriver.chooseStartSlot(placement, laps, current.aiLevel, table)
            advancePlacement(Placement.choose(placement, slot), laps, tournament, rules)
        }
    }

    private fun advancePlacement(next: PlacementState, laps: Int, tournament: Tournament?, rules: RaceRules) {
        if (next.isComplete) {
            val assignments = Placement.toStartAssignments(next)
            val state = GameEngine.newGame(next.track, assignments, laps, rules)
            soundEffects.play(Sound.PISTOL)
            val session = LocalRaceSession(
                initialState = state,
                configs = next.configs,
                tournament = tournament,
                scope = viewModelScope,
                planner = localPlanner,
            )
            startRaceSession(session)
        } else {
            setPlacementScreen(next, laps, tournament, rules)
        }
    }

    /**
     * Saves a track from the editor and returns to the track browser.
     *
     * @param id The id of the track being edited, or null to save a new one.
     */
    fun saveCustomTrack(id: String?, name: String, draft: TrackDraft, track: Track) {
        customTrackRepository.save(id, name, draft, track)
        goTo(Screen.TracksBrowser)
    }

    /** Deletes the custom track with [id] from this device. */
    fun deleteCustomTrack(id: String) {
        customTrackRepository.delete(id)
    }

    // ---- Track sharing -----------------------------------------------------------------
    // Thin pass-throughs to [SharedTrackState], which owns this whole surface. They stay here
    // so the screens keep talking to one object.

    /** Fetches the shared-track list; it arrives on [sharedTrackList]. */
    fun listSharedTracks() = sharedTracks.refresh()

    /** Downloads a shared track; it arrives on [downloadedTrack]. */
    fun downloadSharedTrack(trackId: String) = sharedTracks.download(trackId)

    /** Saves [downloadedTrack] to this device's tracks, once per shared track. */
    fun saveDownloadedTrack() = sharedTracks.saveDownloaded()

    /** Uploads a custom track to the server's library; needs a logged-in account. */
    fun uploadSharedTrack(name: String, draft: TrackDraft) = sharedTracks.upload(name, draft)

    /** Rates a shared track 1 to 5; needs a logged-in account. */
    fun rateSharedTrack(trackId: String, rating: Int) = sharedTracks.rate(trackId, rating)

    /** Reports a shared track for an operator to review; needs a logged-in account. */
    fun reportSharedTrack(trackId: String, reason: String) = sharedTracks.report(trackId, reason)

    /** Clears the upload result and error, so they are not shown again. */
    fun clearTrackUploadFeedback() = sharedTracks.clearUploadFeedback()

    /** Clears the download error, so it is not shown again. */
    fun clearTrackDownloadError() = sharedTracks.clearDownloadError()

    /** Clears the rating result and error, so they are not shown again. */
    fun clearTrackRateFeedback() = sharedTracks.clearRateFeedback()

    /** Moves the local player to [target] in the race being driven; ignored without one. */
    fun makeMove(target: Point) {
        _raceSession?.makeMove(target)
    }

    // ---- Replay -----------------------------------------------------------------------

    /** Start auto-playing the most recently finished local race's replay. */
    fun watchLastReplay() {
        _lastReplay.value?.let { startReplayPlayback(it) }
    }

    /** Start auto-playing a specific saved replay. */
    fun startReplayPlayback(replay: ReplayData) {
        endRaceSession()
        _events.value = emptyList()
        val session = ReplaySession(replay, viewModelScope)
        _screen.value = Screen.ReplayViewer(replay)
        startReplaySession(session)
    }

    /** Delete a saved replay from the repository. */
    fun deleteReplay(replay: ReplayData) {
        replayRepository.delete(replay)
    }

    /** How an [importReplay] attempt ended, so the UI can say something specific. */
    enum class ReplayImportResult { IMPORTED, ALREADY_PRESENT, UNREADABLE }

    /**
     * Imports a `.gridracer-replay` file picked from the device.
     *
     * The counterpart to the existing export intent, and what makes a race logged by the
     * *server* watchable: the server writes exactly this format, so a race handed to you as a
     * file plays back here with no conversion.
     *
     * Nothing here trusts the file. It may come from another device, from a server running a
     * different build, or from someone who edited it - and the race screen reads
     * `state.currentPlayer`, which indexes straight into `players`, on its first frame. A file
     * that does not hang together is refused rather than crashed on.
     */
    fun importReplay(uri: android.net.Uri, context: Context): ReplayImportResult {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return ReplayImportResult.UNREADABLE
        return importReplayJson(text)
    }

    /**
     * The decision half of [importReplay], separated from reading the file so it can be tested
     * without a `ContentResolver` - which is where all the judgement is: what counts as
     * readable, what counts as playable, and what counts as one you already have.
     */
    internal fun importReplayJson(text: String): ReplayImportResult {
        val replay = runCatching { json.decodeFromString<ReplayData>(text) }.getOrNull()
            ?: return ReplayImportResult.UNREADABLE
        if (!replay.isPlayable) return ReplayImportResult.UNREADABLE
        if (replay.id.isNotBlank() && replayRepository.contains(replay.id)) {
            return ReplayImportResult.ALREADY_PRESENT
        }
        replayRepository.save(replay)
        return ReplayImportResult.IMPORTED
    }

    /**
     * Exports a replay as a JSON file via [Intent.ACTION_SEND]. The replay is written to the
     * app's cache directory and shared through a [FileProvider], so the recipient gets a
     * `.gridracer-replay` file they can import later.
     *
     * Returns the launched [Intent] so the caller (Activity) can start it, or null on failure.
     */
    fun buildReplayExportIntent(replay: ReplayData, context: Context): android.content.Intent? {
        return runCatching {
            val exportDir = java.io.File(context.cacheDir, "replays").apply { mkdirs() }
            val safeName = replay.trackName.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = java.io.File(exportDir, "${safeName}_${replay.timestamp}.gridracer-replay")
            file.writeText(json.encodeToString(replay))
            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.replay_export_subject))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
    }

    /** Wire a [ReplaySession] into the screen/events flow, similar to [startRaceSession] but
     *  without snapshot saving or result recording — replays are view-only. */
    private fun startReplaySession(session: ReplaySession) {
        endRaceSession()
        _raceSession = session
        _localPlayerId.value = null
        _events.value = session.frame.value.events
        raceJob = viewModelScope.launch {
            var previousFrame: RaceFrame? = null
            session.frame.collect { frame ->
                val (state, events) = frame
                if (previousFrame != null && state.phase == GamePhase.IN_PROGRESS) {
                    if (session.playbackState.value == ReplaySession.PlaybackState.PLAYING) {
                        soundEffects.play(Sound.MOTOR)
                    }
                }
                (events - (previousFrame?.events ?: emptyList())).forEach { event ->
                    if (session.playbackState.value == ReplaySession.PlaybackState.PLAYING) {
                        soundEffects.play(if (event.type == EventType.CRASHED) Sound.CRASH else Sound.FINISH)
                    }
                }
                previousFrame = frame
                _events.value = events
                _screen.value = Screen.ReplayViewer(session.replay)
            }
        }
    }

    /** Wires a new [RaceSession] into the ViewModel's screen, events and saved-state handling. */
    private fun startRaceSession(session: RaceSession) {
        endRaceSession()

        _raceSession = session
        _localPlayerId.value = session.localPlayerId.value
        _events.value = session.frame.value.events

        raceJob = viewModelScope.launch {
            // The seat can be renumbered mid-race, so it is followed rather than read once -
            // the race screen decides whose turn it is from this.
            launch { session.localPlayerId.collect { _localPlayerId.value = it } }
            // Likewise the roster: a screen already showing a race has to pick up a changed
            // name or a seat that became an AI, so re-emit the Race screen when it changes.
            launch {
                session.configs.collect { configs ->
                    val current = _screen.value
                    if (current is Screen.Race && current.configs != configs) {
                        _screen.value = current.copy(configs = configs)
                    }
                }
            }
            if (session is NetworkRaceSession) {
                launch { session.turnDeadline.collect { _turnDeadline.value = it } }
            }

            var previousFrame: RaceFrame? = null

            session.frame.collect { frame ->
                val (state, events) = frame
                val configs = session.configs.value

                if (previousFrame != null && state.phase == GamePhase.IN_PROGRESS) {
                    soundEffects.play(Sound.MOTOR)
                }
                (events - (previousFrame?.events ?: emptyList())).forEach { event ->
                    soundEffects.play(if (event.type == EventType.CRASHED) Sound.CRASH else Sound.FINISH)
                }
                previousFrame = frame

                _events.value = events

                val tournament = session.tournament
                when (state.phase) {
                    GamePhase.IN_PROGRESS -> {
                        val snapshotTournament = tournament.takeIf { session is LocalRaceSession }
                        val roomCode = _roomInfo.value?.roomCode.takeIf { session is NetworkRaceSession }
                        saveRaceSnapshot(RaceSnapshot(state, configs, snapshotTournament, roomCode))
                        _screen.value = Screen.Race(state, configs, tournament, isMultiplayer = session is NetworkRaceSession)
                    }
                    GamePhase.RACE_OVER -> {
                        clearRaceSnapshot()
                        recordResults(state, configs)
                        // Capture replay data from local races for the "Watch Replay" button.
                        if (session is LocalRaceSession) {
                            val replay = ReplayData(
                                trackName = state.track.name,
                                configs = configs,
                                initialState = session.initialState,
                                moves = session.moveHistory,
                                timestamp = System.currentTimeMillis(),
                            )
                            // save() assigns the stable id; keep the stored copy so "Watch
                            // Replay" and the browser row are the same replay.
                            _lastReplay.value = replayRepository.save(replay)
                        }
                        if (tournament != null && session is LocalRaceSession) {
                            finishTournamentRace(state, tournament)
                        } else if (tournament == null && session is LocalRaceSession) {
                            _screen.value = Screen.Results(state, configs)
                        }
                        // For network non-tournament races the server sends RaceFinished and
                        // [onRaceResult] transitions to Screen.MultiplayerResults.
                        // For network tournaments the server sends TournamentUpdate/TournamentFinished
                        // and [onTournamentUpdate] will take over the screen.
                    }
                }
            }
        }
    }

    private fun endRaceSession() {
        raceJob?.cancel()
        raceJob = null
        _raceSession?.quit()
        _raceSession = null
        _localPlayerId.value = null
        _turnDeadline.value = null
        _events.value = emptyList()
        clearRaceSnapshot()
    }

    private fun saveRaceSnapshot(snapshot: RaceSnapshot) {
        savedStateHandle[RACE_SNAPSHOT_KEY] = json.encodeToString(snapshot)
    }

    private fun clearRaceSnapshot() {
        savedStateHandle.remove<String>(RACE_SNAPSHOT_KEY)
    }

    private fun recordResults(state: GameState, configs: List<PlayerConfig>) {
        val humanResults = state.players.mapNotNull { player ->
            val config = configs.getOrNull(player.id) ?: return@mapNotNull null
            if (config.isAi) null else config.name to player.finishRank
        }
        if (humanResults.isNotEmpty()) leaderboardRepository.recordRace(humanResults)
    }

    private fun finishTournamentRace(state: GameState, tournament: Tournament) {
        val updatedStandings = tournament.standings.map { standing ->
            val player = state.players.firstOrNull { it.id == standing.playerSlot }
            standing.copy(points = standing.points + Scoring.pointsForRank(player?.finishRank))
        }
        val updated = tournament.copy(
            standings = updatedStandings,
            currentTrackIndex = tournament.currentTrackIndex + 1,
        )
        _screen.value = if (tournament.isLastTrack) {
            Screen.TournamentFinalResults(updated)
        } else {
            // The next track's tables build while the standings are on screen.
            aiPlanners.prefetch(updated.currentTrack, updated.rules, updated.configs.filter { it.isAi }.map { it.aiLevel })
            Screen.TournamentStandingsInterstitial(updated, state)
        }
    }

    /**
     * Picks a race back up if the process died mid-race; a finished/never-started race yields
     * null, and so does a snapshot that decodes but doesn't hang together. A snapshot can have
     * been written by an older build with different fields, and the race screen reads
     * [GameState.currentPlayer] straight away - dropping a bad one costs an unfinished race,
     * restoring it crashes the app on every launch until the process is force-stopped.
     */
    private fun restoreRaceInProgress(): RaceSnapshot? {
        val snapshotJson = savedStateHandle.get<String>(RACE_SNAPSHOT_KEY) ?: return null
        val snapshot = runCatching { json.decodeFromString<RaceSnapshot>(snapshotJson) }.getOrNull() ?: return null
        if (snapshot.state.phase != GamePhase.IN_PROGRESS) return null
        if (!snapshot.state.isConsistent) {
            clearRaceSnapshot()
            return null
        }
        return snapshot
    }

    companion object {
        /**
         * The factory [MainActivity] builds this ViewModel with.
         *
         * @param settingsRepository Shared with the activity, so a settings change reaches both at
         *     once.
         * @param soundEffects Where race events are played.
         */
        fun factory(
            context: Context,
            settingsRepository: SettingsRepository,
            soundEffects: SoundEffects,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GameViewModel(
                    LeaderboardRepository.create(context.applicationContext),
                    CustomTrackRepository.create(context.applicationContext),
                    settingsRepository,
                    ReplayRepository.create(context.applicationContext),
                    soundEffects,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
