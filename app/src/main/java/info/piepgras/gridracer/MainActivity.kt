package info.piepgras.gridracer

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import info.piepgras.gridracer.ui.AppNavigation
import info.piepgras.gridracer.ui.CrashReportingConsentScreen
import info.piepgras.gridracer.ui.LegalDisclaimerScreen
import info.piepgras.gridracer.ui.LocalTrackPalette
import info.piepgras.gridracer.ui.trackPaletteFor
import java.io.File
import kotlinx.coroutines.launch

/**
 * The single Activity.
 *
 * It owns three things and nothing else: the app's theme, the crash-reporting lifecycle, and
 * the two first-run consent gates. Navigation - the `when` over [Screen] and everything each
 * screen needs wired up - lives in [AppNavigation]; it had grown to about four hundred lines
 * inside `setContent`, which left the actual Activity concerns buried at the top of it.
 */
class MainActivity : ComponentActivity() {

    // Shared with GameViewModel via the factory below - both must observe the same
    // repository instance so a live Settings toggle is picked up by SoundManager immediately,
    // not just after the next app restart re-reads the file into a second instance.
    private val settingsRepository: SettingsRepository by lazy { SettingsRepository.create(applicationContext) }
    private val soundManager: SoundManager by lazy { SoundManager(applicationContext, settingsRepository.settings) }
    private val turnNotifier: TurnNotifier by lazy { TurnNotifier(applicationContext) }
    private val viewModel: GameViewModel by viewModels {
        GameViewModel.factory(applicationContext, settingsRepository, soundManager)
    }

    // The OS decides whether to show the runtime prompt at all (denied-but-askable vs.
    // permanently denied); either way the app just quietly skips notifying if it never gets
    // granted, so there is nothing to do with the callback result itself.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * Ask for notification permission exactly once, ever.
     *
     * This used to fire on every `onCreate`, which is every launch *and* every configuration
     * change - so someone who had turned notifications off in Android settings got the prompt
     * again the moment they came back to the app, and the activity restart behind it dropped
     * them on the main menu. The flag is persisted rather than held in the activity because
     * "once" has to mean once per install, not once per process.
     *
     * Changing the answer afterwards is what Settings -> Permissions is for: it shows the
     * current state and taps through to the system switch.
     */
    private fun askForNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (settingsRepository.settings.value.notificationPromptShown) return
        settingsRepository.update { it.copy(notificationPromptShown = true) }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        grandfatherExistingInstalls()
        followCrashReportingSetting()
        askForNotificationPermissionOnce()
        observeTurnNotifications()

        setContent {
            val settings by viewModel.settings.collectAsState()
            // The app's own setting, not isSystemInDarkTheme(): the track surface is part of
            // what this switches, and "follow the system" would mean a race that changes colour
            // under you at sunset. Defaults to dark - see AppSettings.darkMode.
            val colorScheme = if (settings.darkMode) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                CompositionLocalProvider(LocalTrackPalette provides trackPaletteFor(settings.darkMode)) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val screen by viewModel.screen.collectAsState()

                        if (!ConsentGates(settings, screen)) return@Surface
                        AppNavigation(viewModel = viewModel, sound = soundManager)
                    }
                }
            }
        }
    }

    /**
     * The two non-dismissible first-run screens. Returns false when one of them is showing, so
     * the caller skips navigation entirely.
     *
     * Both let [Screen.About] through: the disclaimer links out to the full policy, which has
     * to stay readable *before* acceptance. Going back from About returns to MainMenu, which
     * re-triggers this gate - so the user lands back on the disclaimer, not in the app proper.
     */
    @Composable
    private fun ConsentGates(settings: info.piepgras.gridracer.engine.AppSettings, screen: Screen): Boolean {
        if (!settings.legalDisclaimerAccepted && screen !is Screen.About) {
            LegalDisclaimerScreen(
                onReadFullPolicy = { viewModel.goTo(Screen.About) },
                onAccept = { viewModel.updateSettings { it.copy(legalDisclaimerAccepted = true) } },
            )
            return false
        }
        // Gated on its own flag rather than on the disclaimer: installs grandfathered past the
        // disclaimer below predate crash reporting entirely, so they get asked once too.
        if (!settings.crashReportingPromptShown && screen !is Screen.About) {
            CrashReportingConsentScreen(
                onDecision = { enabled ->
                    viewModel.updateSettings {
                        it.copy(crashReportingEnabled = enabled, crashReportingPromptShown = true)
                    }
                },
            )
            return false
        }
        return true
    }

    /**
     * Skips the first-run consent screens for an install that predates them.
     *
     * If any of this app's data files already exist, this is not a fresh install, so a
     * returning player has already implicitly agreed by using the app - clicking through a
     * dialog for it would be theatre. `File.exists()` has no side effects, so this is safe to
     * run before anything else touches these files.
     */
    private fun grandfatherExistingInstalls() {
        val preexistingInstall = listOf("settings.json", "leaderboard.json", "custom_tracks.json")
            .any { File(applicationContext.filesDir, it).exists() }
        if (preexistingInstall && !settingsRepository.settings.value.legalDisclaimerAccepted) {
            settingsRepository.update { it.copy(legalDisclaimerAccepted = true) }
        }
        // Anyone who already switched crash reporting on in Settings has made exactly the
        // decision the consent screen asks for, so don't ask them again.
        if (settingsRepository.settings.value.crashReportingEnabled) {
            settingsRepository.update { it.copy(crashReportingPromptShown = true) }
        }
    }

    /**
     * Crash reporting is opt-in: Sentry's manifest auto-init is disabled, so it only starts
     * when the user turns the toggle on. Collecting the live flow (rather than reading it
     * once) means a mid-session toggle takes effect immediately, in both directions.
     */
    private fun followCrashReportingSetting() {
        lifecycleScope.launch {
            settingsRepository.settings.collect { settings ->
                if (settings.crashReportingEnabled) {
                    CrashReporting.init(applicationContext)
                } else {
                    CrashReporting.close()
                }
            }
        }
    }

    /**
     * Alerts a backgrounded player that it's their turn, so they have a way back in before the
     * server forfeits it for them (see [TurnNotifier]).
     *
     * [ProcessLifecycleOwner] rather than this Activity's own lifecycle: this is a
     * single-Activity app, but a system dialog or the notification shade can still stop this
     * Activity without the app actually leaving the foreground from the player's point of
     * view. `ProcessLifecycleOwner` only reports background once every Activity has stopped.
     */
    private fun observeTurnNotifications() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val deadline = viewModel.turnDeadline.value ?: return
                if (deadline.playerId != viewModel.localPlayerId.value) return
                val elapsedSeconds = (System.currentTimeMillis() - deadline.receivedAtMillis) / 1000
                val secondsLeft = (deadline.secondsRemaining - elapsedSeconds).toInt()
                if (secondsLeft > 0) turnNotifier.notifyTurn(secondsLeft)
            }

            override fun onStart(owner: LifecycleOwner) {
                turnNotifier.cancel()
            }
        })
    }
}
