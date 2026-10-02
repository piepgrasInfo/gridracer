package info.piepgras.gridracer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import info.piepgras.gridracer.R

/**
 * "Are you sure?" for an action that cannot be undone.
 *
 * The app is landscape-locked, which is why the whole dialog is built by hand rather than
 * using [AlertDialog]'s `title`/`text`/`confirmButton` slots: in landscape the `text` slot is
 * a few lines tall and *scrolls*, so anything placed in it - including buttons - can end up
 * out of reach. Every control here sits **below** the scrolling body instead.
 *
 * Used for quitting a race (which forfeits it online), deleting a track or a replay, and
 * kicking a player - the four places where one stray tap used to be irreversible.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Colours the confirm button as a warning. On for anything that destroys data. */
    destructive: Boolean = true,
    dismissLabel: String = stringResource(R.string.action_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            // Bounded and scrollable: a long message must not push the buttons off a short
            // landscape viewport.
            Column(
                modifier = Modifier
                    .heightIn(max = MAX_BODY_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                Button(
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(confirmLabel)
                }
            }
        },
    )
}

/**
 * Two lines of a landscape dialog is about all the body can have before the buttons - which
 * live outside the scrolling area - start to crowd the top of the screen.
 */
private val MAX_BODY_HEIGHT = 120.dp

/**
 * Convenience wrapper: the same dialog driven by a nullable "what are we confirming" value.
 *
 * [message] is `@Composable` so callers can build it from `stringResource` with the subject's
 * own name in it - "Delete the replay of Oval?" rather than "Delete this?".
 */
@Composable
fun <T : Any> ConfirmDialogFor(
    target: T?,
    title: String,
    message: @Composable (T) -> String,
    confirmLabel: String,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val subject = target ?: return
    ConfirmDialog(
        title = title,
        message = message(subject),
        confirmLabel = confirmLabel,
        onConfirm = { onConfirm(subject) },
        onDismiss = onDismiss,
    )
}
