package info.piepgras.gridracer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.ConnectionState
import info.piepgras.gridracer.CrashReporting
import info.piepgras.gridracer.LocaleHelper
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.AppSettings
import info.piepgras.gridracer.engine.PlacementOrder
import info.piepgras.gridracer.multiplayer.AccountInfo

/**
 * Settings, grouped into sections.
 *
 * The booleans are [SettingsSwitch]es rather than the "Off"/"On" chip pairs they used to be:
 * two chips read as two independent choices, and neither one told you which was the default.
 * The flat ladder of 24dp spacers is gone with them - [SettingsSection] carries the rhythm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenReplays: () -> Unit,
    onBack: () -> Unit,
    // ---- Server connection + authentication (inlined from the former AccountScreen) ----
    serverUrl: String,
    onCommitServerUrl: (String) -> Unit,
    account: AccountInfo?,
    connectionState: ConnectionState,
    connectionEncrypted: Boolean,
    serverSupportsAccounts: Boolean,
    serverSupportsAccountDeletion: Boolean,
    authError: String?,
    accountDeleted: Boolean,
    onRegister: (username: String, password: String, displayName: String?) -> Unit,
    onLogin: (username: String, password: String) -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: (password: String) -> Unit,
    onDismissError: () -> Unit,
    onDismissAccountDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val currentLanguageCode = remember { LocaleHelper.getPersistedLanguage(context) }

    ScreenScaffold(title = stringResource(R.string.menu_settings), onBack = onBack, modifier = modifier) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            // Server connection + account, in one place: the URL used to be on the race-setup
            // screen and login used to be a separate Account screen reached from here. Both now
            // live inline so a user manages their server identity without navigating away.
            SettingsSection(stringResource(R.string.settings_server_section)) {
                ServerSection(
                    serverUrl = serverUrl,
                    onCommitServerUrl = onCommitServerUrl,
                    account = account,
                    connectionState = connectionState,
                    connectionEncrypted = connectionEncrypted,
                    serverSupportsAccounts = serverSupportsAccounts,
                    serverSupportsAccountDeletion = serverSupportsAccountDeletion,
                    authError = authError,
                    accountDeleted = accountDeleted,
                    onRegister = onRegister,
                    onLogin = onLogin,
                    onLogout = onLogout,
                    onDeleteAccount = onDeleteAccount,
                    onDismissError = onDismissError,
                    onDismissAccountDeleted = onDismissAccountDeleted,
                )
            }

            SettingsSection(stringResource(R.string.settings_gameplay_section)) {
                Text(
                    stringResource(R.string.settings_placement_order_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_placement_order_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // Genuinely two alternatives rather than an on/off, so chips stay here.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.placementOrder == PlacementOrder.SEQUENTIAL,
                        onClick = { onSettingsChange(settings.copy(placementOrder = PlacementOrder.SEQUENTIAL)) },
                        label = { Text(stringResource(R.string.settings_placement_sequential)) },
                    )
                    FilterChip(
                        selected = settings.placementOrder == PlacementOrder.RANDOM,
                        onClick = { onSettingsChange(settings.copy(placementOrder = PlacementOrder.RANDOM)) },
                        label = { Text(stringResource(R.string.settings_placement_random)) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsSwitch(
                    label = stringResource(R.string.settings_sound_label),
                    checked = settings.soundEffectsEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(soundEffectsEnabled = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.settings_dark_mode_label),
                    description = stringResource(R.string.settings_dark_mode_description),
                    checked = settings.darkMode,
                    onCheckedChange = { onSettingsChange(settings.copy(darkMode = it)) },
                )
            }

            // A build made from the published source has no crash-reporting endpoint
            // compiled in, so there is nothing for this switch to turn on - and a switch
            // that does nothing is worse than no switch. See CrashReporting.isConfigured.
            if (CrashReporting.isConfigured) {
                SettingsSection(stringResource(R.string.settings_privacy_section)) {
                    SettingsSwitch(
                        label = stringResource(R.string.settings_crash_reporting_label),
                        description = stringResource(R.string.settings_crash_reporting_description),
                        checked = settings.crashReportingEnabled,
                        onCheckedChange = { onSettingsChange(settings.copy(crashReportingEnabled = it)) },
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_language_label)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocaleHelper.SUPPORTED_LANGUAGES.forEach { language ->
                        FilterChip(
                            selected = currentLanguageCode == language.code,
                            onClick = {
                                if (currentLanguageCode != language.code) {
                                    LocaleHelper.setLocale(context, language.code)
                                    activity?.recreate()
                                }
                            },
                            label = { Text(language.nativeName) },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.settings_app_info_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            // Replays are reachable from the main menu too; listed here as well because this is
            // where a player looks for "things this app has stored about me".
            TextButton(onClick = onOpenReplays) { Text(stringResource(R.string.menu_replay_browser)) }
            TextButton(onClick = onOpenPermissions) { Text(stringResource(R.string.nav_permissions)) }
            TextButton(onClick = onOpenAbout) { Text(stringResource(R.string.nav_about)) }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
