package info.piepgras.gridracer.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.ConnectionState
import info.piepgras.gridracer.GameViewModel
import info.piepgras.gridracer.R
import info.piepgras.gridracer.ReplaySession
import info.piepgras.gridracer.Screen
import info.piepgras.gridracer.Sound
import info.piepgras.gridracer.SoundEffects
import info.piepgras.gridracer.engine.CustomTrack
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.ui.racesetup.RaceSetupScreen
import kotlinx.coroutines.delay

/**
 * The app's navigation: one `when` over [Screen], one composable per case.
 *
 * There is no navigation library; [Screen] is a sealed interface and the ViewModel owns which
 * one is current. This lived inside `MainActivity.onCreate`'s `setContent`, where the heavier
 * cases had grown their own flow collection, `LaunchedEffect`s and toast plumbing inline - so
 * the Activity was mostly a 400-line `when` with the actual Activity concerns (theme,
 * onboarding gates, crash-reporting lifecycle) buried at the top of it. The branches that need
 * real setup are functions below; the rest stay one-liners here, where the map of the app is
 * readable in one screen.
 */
@Composable
fun AppNavigation(viewModel: GameViewModel, sound: SoundEffects) {
    val screen by viewModel.screen.collectAsState()
    val events by viewModel.events.collectAsState()
    val localPlayerId by viewModel.localPlayerId.collectAsState()
    val leaderboard by viewModel.leaderboard.collectAsState()
    val customTracks by viewModel.customTracks.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val onlineRatings by viewModel.onlineRatings.collectAsState()
    val lastReplay by viewModel.lastReplay.collectAsState()
    val savedReplays by viewModel.savedReplays.collectAsState()
    val allTracks = remember(customTracks) { Tracks.all + customTracks.map { it.track } }

    when (val current = screen) {
        is Screen.MainMenu -> MainMenuScreen(
            onSettings = { viewModel.goTo(Screen.Settings) },
            onStartRace = { viewModel.openRaceSetup() },
            onTracks = { viewModel.goTo(Screen.TracksBrowser) },
            onLeaderboard = { viewModel.goTo(Screen.Leaderboard) },
            onHowToPlay = { viewModel.goTo(Screen.HowToPlay) },
            onReplayBrowser = { viewModel.goTo(Screen.ReplayBrowser) },
        )
        is Screen.Settings -> SettingsRoute(viewModel, settings)
        is Screen.HowToPlay -> HowToPlayScreen(onBack = { viewModel.goTo(Screen.MainMenu) })
        is Screen.Permissions -> PermissionsScreen(onBack = { viewModel.goTo(Screen.Settings) })
        is Screen.About -> AboutScreen(onBack = { viewModel.goTo(Screen.MainMenu) })
        is Screen.RaceSetup -> RaceSetupRoute(viewModel, sound, allTracks, settings.playerName)
        is Screen.OnlineRoom -> OnlineRoomScreen(
            viewModel = viewModel,
            onBack = { viewModel.leaveOnlineGame() },
        )
        is Screen.TournamentStandings -> {
            val context = LocalContext.current
            TournamentMultiplayerStandingsScreen(
                tournament = current.tournament,
                isHost = current.isHost,
                isFinal = current.isFinal,
                lastReplay = viewModel.lastReplay.collectAsState().value,
                onWatchReplay = { viewModel.watchLastReplay() },
                onExportReplay = {
                    val replay = viewModel.lastReplay.value
                    if (replay != null) {
                        viewModel.buildReplayExportIntent(replay, context)?.let { context.startActivity(it) }
                    }
                },
                onStartRace = { viewModel.startOnlineRace() },
                onMainMenu = { viewModel.leaveOnlineGame() },
            )
        }
        is Screen.Ratings -> RatingsScreen(
            page = onlineRatings,
            onLoadMore = { viewModel.loadMoreLeaderboard() },
            // Back into the setup form as it was left, connection intact.
            onBack = { viewModel.goTo(Screen.RaceSetup) },
        )
        is Screen.TournamentStandingsInterstitial -> TournamentStandingsScreen(
            tournament = current.tournament,
            justFinishedState = current.justFinishedState,
            onContinue = {
                sound.play(Sound.CLICK)
                viewModel.continueTournament(current.tournament)
            },
            onMainMenu = { viewModel.goTo(Screen.MainMenu) },
        )
        is Screen.TournamentFinalResults -> TournamentStandingsScreen(
            tournament = current.tournament,
            justFinishedState = null,
            onContinue = {},
            onMainMenu = { viewModel.goTo(Screen.MainMenu) },
        )
        is Screen.TracksBrowser -> TracksBrowserRoute(viewModel, customTracks)
        is Screen.TrackInfo -> TrackInfoRoute(viewModel, current)
        is Screen.TrackEditor -> TrackEditorRoute(viewModel, sound, customTracks, current)
        is Screen.Placement -> PlacementScreen(
            placement = current.placement,
            onChooseSlot = viewModel::choosePlacementSlot,
            onQuit = { viewModel.goTo(Screen.MainMenu) },
        )
        is Screen.Leaderboard -> LeaderboardScreen(
            entries = leaderboard,
            onBack = { viewModel.goTo(Screen.MainMenu) },
        )
        is Screen.Race -> RaceRoute(viewModel, sound, current, events, localPlayerId)
        is Screen.Results -> ResultsScreen(
            state = current.state,
            configs = current.configs,
            onRaceAgain = {
                sound.play(Sound.CLICK)
                viewModel.startRace(current.state.track, current.configs, current.state.totalLaps)
            },
            onMainMenu = { viewModel.goTo(Screen.MainMenu) },
            onWatchReplay = {
                sound.play(Sound.CLICK)
                viewModel.watchLastReplay()
            },
            canWatchReplay = lastReplay != null,
        )
        is Screen.MultiplayerResults -> MultiplayerResultsRoute(viewModel, sound, current)
        is Screen.ReplayBrowser -> ReplayBrowserRoute(viewModel, sound, savedReplays)
        is Screen.ReplayViewer -> ReplayViewerRoute(viewModel)
    }
}

// -----------------------------------------------------------------------------------------
// Routes that need more than a call: their own flows, effects, or launchers.
// -----------------------------------------------------------------------------------------

@Composable
private fun SettingsRoute(viewModel: GameViewModel, settings: info.piepgras.gridracer.engine.AppSettings) {
    val account by viewModel.account.collectAsState()
    val connectionState by viewModel.multiplayerConnectionState.collectAsState()
    val connectionEncrypted by viewModel.multiplayerConnectionEncrypted.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val supportsAccounts by viewModel.serverSupportsAccounts.collectAsState()
    val supportsAccountDeletion by viewModel.serverSupportsAccountDeletion.collectAsState()
    val accountDeleted by viewModel.accountDeleted.collectAsState()
    // Open a connection if one is not already up, so the inlined server section can show the
    // login form as soon as the server is reachable. In a LaunchedEffect, not bare in the
    // composition: a side effect run on every recomposition is a bug waiting for the day it
    // stops being idempotent.
    LaunchedEffect(Unit) { viewModel.ensureMultiplayerConnected() }
    SettingsScreen(
        settings = settings,
        onSettingsChange = { updated -> viewModel.updateSettings { updated } },
        onOpenPermissions = { viewModel.goTo(Screen.Permissions) },
        onOpenAbout = { viewModel.goTo(Screen.About) },
        onOpenReplays = { viewModel.goTo(Screen.ReplayBrowser) },
        onBack = { viewModel.goTo(Screen.MainMenu) },
        serverUrl = settings.multiplayerServerUrl,
        onCommitServerUrl = { viewModel.commitServerUrl(it) },
        account = account,
        connectionState = connectionState,
        connectionEncrypted = connectionEncrypted,
        serverSupportsAccounts = supportsAccounts,
        serverSupportsAccountDeletion = supportsAccountDeletion,
        authError = authError,
        accountDeleted = accountDeleted,
        onRegister = { u, p, d -> viewModel.registerAccount(u, p, d) },
        onLogin = { u, p -> viewModel.loginAccount(u, p) },
        onLogout = { viewModel.logoutAccount() },
        onDeleteAccount = { password -> viewModel.deleteAccount(password) },
        onDismissError = { viewModel.clearAuthError() },
        onDismissAccountDeleted = { viewModel.clearAccountDeleted() },
    )
}

@Composable
private fun RaceSetupRoute(
    viewModel: GameViewModel,
    sound: SoundEffects,
    allTracks: List<Track>,
    playerName: String,
) {
    val setup by viewModel.raceSetup.collectAsState()
    val connectionState by viewModel.multiplayerConnectionState.collectAsState()
    val connectionEncrypted by viewModel.multiplayerConnectionEncrypted.collectAsState()
    val serverSupportsGravelTrap by viewModel.serverSupportsGravelTrap.collectAsState()
    val rooms by viewModel.roomList.collectAsState()
    val roomsLoading by viewModel.roomListLoading.collectAsState()
    val multiplayerError by viewModel.multiplayerError.collectAsState()
    RaceSetupScreen(
        state = setup,
        allTracks = allTracks,
        connectionState = connectionState,
        connectionEncrypted = connectionEncrypted,
        serverSupportsGravelTrap = serverSupportsGravelTrap,
        rooms = rooms,
        roomsLoading = roomsLoading,
        playerName = playerName,
        errorMessage = multiplayerError,
        onUpdate = { transform -> viewModel.updateRaceSetup(transform) },
        onPlayerNameChange = { name -> viewModel.updateSettings { it.copy(playerName = name) } },
        onRefreshRooms = { viewModel.refreshRoomList() },
        onSubmit = {
            sound.play(Sound.CLICK)
            viewModel.submitRaceSetup()
        },
        onRatings = { viewModel.viewRatings() },
        onBack = { viewModel.goTo(Screen.MainMenu) },
    )
}

@Composable
private fun TracksBrowserRoute(viewModel: GameViewModel, customTracks: List<CustomTrack>) {
    val context = LocalContext.current
    // Resources, not context.getString: the toast text below is formatted inside a
    // LaunchedEffect, where stringResource cannot be called, and reading through the
    // context is not configuration-aware (lint LocalContextGetResourceValueCall).
    val resources = LocalResources.current
    val sharedTrackList by viewModel.sharedTrackList.collectAsState()
    val connectionState by viewModel.multiplayerConnectionState.collectAsState()

    // Auto-connect and fetch the shared-track list on entry so the server-tracks section
    // populates without an extra tap. Browsing is anonymous-OK.
    LaunchedEffect(Unit) { viewModel.ensureMultiplayerConnected() }
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.Connected) viewModel.listSharedTracks()
    }
    // Upload feedback. Uploading is started from the track info screen, but the result can
    // land after the user has navigated back here.
    val uploadResult by viewModel.trackUploadResult.collectAsState()
    LaunchedEffect(uploadResult) {
        if (uploadResult != null) {
            Toast.makeText(context, R.string.shared_tracks_uploaded, Toast.LENGTH_SHORT).show()
            viewModel.clearTrackUploadFeedback()
        }
    }
    val uploadError by viewModel.trackUploadError.collectAsState()
    LaunchedEffect(uploadError) {
        if (uploadError != null) {
            Toast.makeText(
                context,
                resources.getString(R.string.shared_tracks_upload_failed, uploadError),
                Toast.LENGTH_LONG,
            ).show()
            viewModel.clearTrackUploadFeedback()
        }
    }

    TracksBrowserScreen(
        customTracks = customTracks,
        sharedTrackList = sharedTrackList,
        connectionState = connectionState,
        onBack = { viewModel.goTo(Screen.MainMenu) },
        onNewTrack = { viewModel.goTo(Screen.TrackEditor()) },
        onOpenBuiltInTrack = { viewModel.goTo(Screen.TrackInfo(Screen.TrackSource.BuiltIn(it))) },
        onOpenCustomTrack = { viewModel.goTo(Screen.TrackInfo(Screen.TrackSource.Custom(it))) },
        onRefreshSharedTracks = { viewModel.listSharedTracks() },
        onOpenSharedTrack = { viewModel.goTo(Screen.TrackInfo(Screen.TrackSource.Shared(it))) },
        onOpenSettings = { viewModel.goTo(Screen.Settings) },
    )
}

@Composable
private fun TrackInfoRoute(viewModel: GameViewModel, current: Screen.TrackInfo) {
    val context = LocalContext.current
    // See TracksBrowserRoute: the download-failure toast is formatted inside a LaunchedEffect.
    val resources = LocalResources.current
    val sharedTrackList by viewModel.sharedTrackList.collectAsState()
    val downloadedTrack by viewModel.downloadedTrack.collectAsState()
    val account by viewModel.account.collectAsState()
    val trackRated by viewModel.trackRated.collectAsState()
    val rateError by viewModel.trackRateError.collectAsState()
    val connectionState by viewModel.multiplayerConnectionState.collectAsState()
    LaunchedEffect(Unit) { viewModel.ensureMultiplayerConnected() }

    val sharedTrackId = (current.source as? Screen.TrackSource.Shared)?.summary?.trackId
    // Fetch the compiled track so the preview can be drawn. Anonymous-OK.
    LaunchedEffect(sharedTrackId, connectionState) {
        if (sharedTrackId != null &&
            connectionState == ConnectionState.Connected &&
            downloadedTrack?.summary?.trackId != sharedTrackId
        ) {
            viewModel.downloadSharedTrack(sharedTrackId)
        }
    }
    val downloadError by viewModel.trackDownloadError.collectAsState()
    LaunchedEffect(downloadError) {
        if (downloadError != null) {
            Toast.makeText(
                context,
                resources.getString(R.string.shared_tracks_download_failed, downloadError),
                Toast.LENGTH_LONG,
            ).show()
            viewModel.clearTrackDownloadError()
        }
    }
    LaunchedEffect(rateError) {
        if (rateError != null) {
            Toast.makeText(context, rateError, Toast.LENGTH_LONG).show()
            viewModel.clearTrackRateFeedback()
        }
    }

    // For a shared track, prefer the summary from the (possibly refreshed) list, so a rating
    // cast elsewhere shows here. Built-in and custom sources are carried directly.
    val resolvedSource: Screen.TrackSource? = when (current.source) {
        is Screen.TrackSource.BuiltIn, is Screen.TrackSource.Custom -> current.source
        is Screen.TrackSource.Shared ->
            sharedTrackList?.tracks?.firstOrNull { it.trackId == sharedTrackId }
                ?.let { Screen.TrackSource.Shared(it) }
                ?: current.source
    }

    TrackInfoScreen(
        source = resolvedSource,
        downloaded = downloadedTrack?.takeIf { it.summary.trackId == sharedTrackId },
        account = account,
        lastRated = trackRated,
        rateError = rateError,
        onDownload = {
            val downloaded = downloadedTrack
            if (downloaded != null && downloaded.summary.trackId == sharedTrackId) {
                viewModel.saveDownloadedTrack()
                Toast.makeText(context, R.string.shared_tracks_download_saved, Toast.LENGTH_SHORT).show()
            } else if (sharedTrackId != null) {
                viewModel.downloadSharedTrack(sharedTrackId)
            }
        },
        onRate = { rating -> if (sharedTrackId != null) viewModel.rateSharedTrack(sharedTrackId, rating) },
        onReport = { reason ->
            if (sharedTrackId != null) {
                viewModel.reportSharedTrack(sharedTrackId, reason)
                Toast.makeText(context, R.string.shared_tracks_reported, Toast.LENGTH_SHORT).show()
            }
        },
        onEdit = { viewModel.goTo(Screen.TrackEditor(it.id)) },
        onDelete = {
            viewModel.deleteCustomTrack(it.id)
            viewModel.goTo(Screen.TracksBrowser)
        },
        onUpload = { customTrack ->
            if (account == null) {
                // Anonymous: the server would refuse anyway, so point at the place to fix it
                // rather than firing a request that is doomed.
                Toast.makeText(context, R.string.shared_tracks_login_required, Toast.LENGTH_LONG).show()
                viewModel.goTo(Screen.Settings)
            } else {
                viewModel.uploadSharedTrack(customTrack.name, customTrack.draft)
            }
        },
        onOpenSettings = { viewModel.goTo(Screen.Settings) },
        onBack = { viewModel.goTo(Screen.TracksBrowser) },
    )
}

@Composable
private fun TrackEditorRoute(
    viewModel: GameViewModel,
    sound: SoundEffects,
    customTracks: List<CustomTrack>,
    current: Screen.TrackEditor,
) {
    val editing = customTracks.firstOrNull { it.id == current.editingId }
    TrackEditorScreen(
        editingId = editing?.id,
        existingName = editing?.name,
        existingDraft = editing?.draft,
        onSave = { id, name, draft, track ->
            sound.play(Sound.CLICK)
            viewModel.saveCustomTrack(id, name, draft, track)
        },
        onCancel = { viewModel.goTo(Screen.TracksBrowser) },
    )
}

@Composable
private fun RaceRoute(
    viewModel: GameViewModel,
    sound: SoundEffects,
    current: Screen.Race,
    events: List<info.piepgras.gridracer.GameEvent>,
    localPlayerId: Int?,
) {
    // In a multiplayer race the HUD also shows connection health and the field, so a player
    // can tell a stalled turn from a dropped connection.
    val connectionState by viewModel.multiplayerConnectionState.collectAsState()
    val connectionLabel = if (current.isMultiplayer) stringResource(connectionState.labelRes) else null
    val playerCountLabel = if (current.isMultiplayer) {
        stringResource(
            R.string.race_player_count,
            current.configs.count { !it.isAi },
            current.configs.count { it.isAi },
        )
    } else {
        null
    }

    // The server sends the turn deadline once, when the turn starts; the countdown between
    // ticks is local so it costs no message per second. This recomposes once a second only
    // while a clock is running - `deadline` is null the rest of the time.
    val deadline by viewModel.turnDeadline.collectAsState()
    var secondsLeft by remember(deadline) { mutableStateOf(deadline?.secondsRemaining) }
    LaunchedEffect(deadline) {
        val start = deadline ?: return@LaunchedEffect
        while (true) {
            val elapsed = (System.currentTimeMillis() - start.receivedAtMillis) / 1000
            val left = (start.secondsRemaining - elapsed).toInt()
            secondsLeft = left.coerceAtLeast(0)
            if (left <= 0) break
            delay(1000)
        }
    }

    RaceScreen(
        state = current.state,
        configs = current.configs,
        events = events,
        localPlayerId = localPlayerId,
        onMove = viewModel::makeMove,
        onQuit = {
            sound.play(Sound.CLICK)
            viewModel.goTo(Screen.MainMenu)
        },
        connectionLabel = connectionLabel,
        playerCountLabel = playerCountLabel,
        turnSecondsRemaining = secondsLeft,
        isOnlineRace = current.isMultiplayer,
    )
}

@Composable
private fun MultiplayerResultsRoute(
    viewModel: GameViewModel,
    sound: SoundEffects,
    current: Screen.MultiplayerResults,
) {
    val hostPromoted by viewModel.hostPromoted.collectAsState()
    val lastReplay by viewModel.lastReplay.collectAsState()
    MultiplayerResultsScreen(
        standings = current.standings,
        isHost = current.isHost,
        showHostPromotedDialog = hostPromoted != null,
        lastReplay = lastReplay,
        onWatchReplay = {
            sound.play(Sound.CLICK)
            viewModel.watchLastReplay()
        },
        onRematch = {
            sound.play(Sound.CLICK)
            viewModel.startRematch()
        },
        onLeave = {
            sound.play(Sound.CLICK)
            viewModel.leaveOnlineGame()
        },
        onDismissHostPromoted = { viewModel.clearHostPromoted() },
    )
}

@Composable
private fun ReplayBrowserRoute(
    viewModel: GameViewModel,
    sound: SoundEffects,
    savedReplays: List<info.piepgras.gridracer.replay.ReplayData>,
) {
    val context = LocalContext.current
    // Any MIME type: a `.gridracer-replay` file has no registered type, so restricting to
    // application/json would hide the app's own exports (and the server's race logs) in the
    // system picker.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val message = when (viewModel.importReplay(uri, context)) {
            GameViewModel.ReplayImportResult.IMPORTED -> R.string.replay_import_done
            GameViewModel.ReplayImportResult.ALREADY_PRESENT -> R.string.replay_import_duplicate
            GameViewModel.ReplayImportResult.UNREADABLE -> R.string.replay_import_failed
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    ReplayBrowserScreen(
        replays = savedReplays,
        onPlayReplay = { replay ->
            sound.play(Sound.CLICK)
            viewModel.startReplayPlayback(replay)
        },
        onExportReplay = { replay ->
            val intent = viewModel.buildReplayExportIntent(replay, context)
            if (intent != null) {
                context.startActivity(android.content.Intent.createChooser(intent, null))
            } else {
                Toast.makeText(context, R.string.replay_export_error, Toast.LENGTH_SHORT).show()
            }
        },
        onDeleteReplay = { replay -> viewModel.deleteReplay(replay) },
        onImportReplay = { importLauncher.launch(arrayOf("*/*")) },
        // Back to the main menu, which is where this screen is entered from. It used to land
        // on Settings, so leaving put you somewhere you had never been.
        onBack = { viewModel.goTo(Screen.MainMenu) },
    )
}

@Composable
private fun ReplayViewerRoute(viewModel: GameViewModel) {
    // A replay drives the race screen's frame flow. The same screen, with no move input
    // (makeMove is a no-op on ReplaySession) and Back returning to the browser.
    val replaySession = viewModel.replaySession ?: return
    val replayFrame by replaySession.frame.collectAsState()
    val replayConfigs by replaySession.configs.collectAsState()
    
    val currentIndex by replaySession.currentIndex.collectAsState()
    val playbackState by replaySession.playbackState.collectAsState()
    val speedMultiplier by replaySession.speedMultiplier.collectAsState()

    androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        RaceScreen(
            state = replayFrame.state,
            configs = replayConfigs,
            events = replayFrame.events,
            localPlayerId = null,
            onMove = {},
            onQuit = { viewModel.goTo(Screen.ReplayBrowser) },
            confirmOnQuit = false,
        )
        ReplayControls(
            currentIndex = currentIndex,
            totalSteps = replaySession.totalSteps,
            playbackState = playbackState,
            speedMultiplier = speedMultiplier,
            onTogglePlayPause = { replaySession.togglePlayPause() },
            onStepForward = { replaySession.stepForward() },
            onStepBackward = { replaySession.stepBackward() },
            onSpeedChange = { replaySession.setSpeed(it) },
            onSeek = { replaySession.seekTo(it) },
            modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
        // A replay with a move that could not be played back ends early; say so rather than
        // let it look like the race simply stopped.
        if (replaySession.isTruncated) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            ) {
                Text(
                    stringResource(R.string.replay_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** The one place connection state is turned into a label, shared by the routes that show one. */
private val ConnectionState.labelRes: Int
    get() = when (this) {
        ConnectionState.Connected -> R.string.multiplayer_connected
        ConnectionState.Connecting -> R.string.multiplayer_connecting
        ConnectionState.Reconnecting -> R.string.multiplayer_reconnecting
        ConnectionState.Disconnected -> R.string.multiplayer_disconnected
        ConnectionState.Error -> R.string.multiplayer_error
    }
