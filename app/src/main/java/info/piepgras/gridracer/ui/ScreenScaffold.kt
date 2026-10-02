package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R

/**
 * The chrome every non-game screen shares: a title row with a Back control, safe-area padding,
 * and a system-back handler wired to the same action.
 *
 * This was copied by hand into about ten screens, each with its own idea of the padding and of
 * whether system back did anything - and one screen (the online lobby) had simply been missed,
 * so back either did nothing or dropped the player out of the app. Having it in one place is
 * what makes "every screen behaves the same way when you press back" true by construction
 * rather than by inspection.
 *
 * The in-race screens deliberately do *not* use this: the track is a full-bleed surface with
 * its own HUD, not a page with a title bar.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Extra controls in the title row, to the left of Back. */
    actions: @Composable RowScope.() -> Unit = {},
    /** Padding around the body. Screens with their own edge-to-edge content pass 0. */
    contentPadding: androidx.compose.ui.unit.Dp = DEFAULT_PADDING,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(contentPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** A labelled group inside a scaffolded screen: a heading, the controls, and a rule below. */
@Composable
fun SettingsSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * One on/off setting: a label, an optional explanation, and a [Switch].
 *
 * These were pairs of "Off"/"On" [androidx.compose.material3.FilterChip]s, which read as two
 * separate choices rather than one state, took twice the width, and gave no hint which way was
 * the default. A switch is the platform control for a boolean and says what it is at a glance.
 */
@Composable
fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private val DEFAULT_PADDING = 16.dp
