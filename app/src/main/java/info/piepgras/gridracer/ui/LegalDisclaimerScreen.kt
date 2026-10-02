package info.piepgras.gridracer.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Shown once, before the app is otherwise usable - see references/in_app_presentation.md in
 * the legal-structure-generator skill.
 *
 * The dialog carries only what has to be read to make the decision: a short summary, two
 * one-line notes, and a link. The Privacy Policy and Terms of Service themselves are pages
 * of text that nobody reads in a modal, so they live on the About screen and the link is
 * an abbreviation of where they are, not the documents inlined.
 *
 * Not dismissible by back-press or by tapping outside: the only way through is the Continue
 * button, which stays disabled until the checkbox is ticked.
 */
@Composable
fun LegalDisclaimerScreen(
    onReadFullPolicy: () -> Unit,
    onAccept: () -> Unit,
) {
    var accepted by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { /* Not dismissible: the terms have to be explicitly accepted. */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.legal_disclaimer_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.legal_disclaimer_body))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.legal_disclaimer_storage_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.legal_disclaimer_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column {
                // Below the scrolling body rather than in it: in landscape the dialog gives the
                // body only a couple of lines, and the one control that has to be reachable
                // before agreeing to something is the way to read what it is.
                TextButton(
                    onClick = onReadFullPolicy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.legal_disclaimer_read_full))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(
                        stringResource(R.string.legal_disclaimer_checkbox),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = onAccept,
                    enabled = accepted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.legal_disclaimer_continue))
                }
            }
        },
    )
}
