package info.piepgras.gridracer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import info.piepgras.gridracer.R

/**
 * The second step of first-run onboarding, straight after the terms disclaimer: an
 * explicit opt-in for crash reporting.
 *
 * Deliberately its own dialog rather than a checkbox on the disclaimer. Accepting the
 * terms and agreeing to send data off the device are different decisions, and bundling
 * them would make the opt-in less than freely given.
 *
 * The choice is a switch, not a pair of buttons: a switch shows its current state, which
 * is off, so continuing without touching it is visibly the same "no" that Settings will
 * show afterwards. Two buttons make opting out an action, and an action is easy to design
 * - accidentally or otherwise - so that one of the two looks like the way forward.
 */
@Composable
fun CrashReportingConsentScreen(
    onDecision: (enabled: Boolean) -> Unit,
    initiallyEnabled: Boolean = false,
) {
    var enabled by remember { mutableStateOf(initiallyEnabled) }

    AlertDialog(
        onDismissRequest = { /* Not dismissible: the user has to make a choice. */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.crash_consent_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.crash_consent_body))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.crash_consent_permission_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.crash_consent_change_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column {
                // The switch sits below the scrolling body rather than inside it: in landscape
                // the dialog gives the body a few lines, and the one control the whole dialog
                // exists for must never be the part that scrolls out of sight.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.crash_consent_toggle),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Button(
                    onClick = { onDecision(enabled) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.crash_consent_continue))
                }
            }
        },
    )
}
