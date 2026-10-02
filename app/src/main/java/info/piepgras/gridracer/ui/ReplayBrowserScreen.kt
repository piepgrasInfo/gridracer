package info.piepgras.gridracer.ui

import info.piepgras.gridracer.replay.ReplayData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import info.piepgras.gridracer.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved replays, newest at the top, with play, export, import and delete. */
@Composable
fun ReplayBrowserScreen(
    replays: List<ReplayData>,
    onPlayReplay: (ReplayData) -> Unit,
    onExportReplay: (ReplayData) -> Unit,
    onDeleteReplay: (ReplayData) -> Unit,
    /** Opens a file picker; the caller hands the chosen document to the ViewModel. */
    onImportReplay: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    // Deleting a replay is irreversible and the button sits next to Play, so it asks first.
    var pendingDelete by remember { mutableStateOf<ReplayData?>(null) }

    ConfirmDialogFor(
        target = pendingDelete,
        title = stringResource(R.string.replay_delete_title),
        message = { stringResource(R.string.replay_delete_message, it.trackName) },
        confirmLabel = stringResource(R.string.replay_delete),
        onConfirm = onDeleteReplay,
        onDismiss = { pendingDelete = null },
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.menu_replay_browser), style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The counterpart to Export. A race logged by a server is written in exactly
                // this format, so importing one is how you watch a multiplayer race back.
                OutlinedButton(onClick = onImportReplay) { Text(stringResource(R.string.replay_import)) }
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (replays.isEmpty()) {
            Text(
                stringResource(R.string.replay_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(replays.reversed(), key = { it.id }) { replay ->
                    ReplayRow(
                        replay = replay,
                        onPlay = { onPlayReplay(replay) },
                        onExport = { onExportReplay(replay) },
                        onDelete = { pendingDelete = replay },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplayRow(
    replay: ReplayData,
    onPlay: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(replay.trackName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.replay_row_summary,
                    replay.moves.size,
                    dateFormat.format(Date(replay.timestamp)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Where a server-logged race came from, so an imported file is not anonymous.
            replay.server?.let { server ->
                Text(
                    stringResource(
                        R.string.replay_row_server,
                        server.serverName.ifBlank { stringResource(R.string.replay_row_unknown_server) },
                        server.roomCode,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPlay) { Text(stringResource(R.string.replay_play)) }
                TextButton(onClick = onExport) { Text(stringResource(R.string.replay_export)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.replay_delete)) }
            }
        }
    }
}
