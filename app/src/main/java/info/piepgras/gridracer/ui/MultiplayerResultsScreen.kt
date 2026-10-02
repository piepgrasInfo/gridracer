package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.Scoring
import info.piepgras.gridracer.multiplayer.RaceStanding

/**
 * Post-race results for a multiplayer race. The host can start a rematch; clients wait for
 * the host. Both can leave the room. If the client was promoted to host (because the previous
 * host left), [showHostPromotedDialog] triggers a one-time dialog explaining the new role.
 */
@Composable
fun MultiplayerResultsScreen(
    standings: List<RaceStanding>,
    isHost: Boolean,
    showHostPromotedDialog: Boolean,
    lastReplay: info.piepgras.gridracer.replay.ReplayData?,
    onRematch: () -> Unit,
    onWatchReplay: () -> Unit,
    onLeave: () -> Unit,
    onDismissHostPromoted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onLeave)

    if (showHostPromotedDialog) {
        AlertDialog(
            onDismissRequest = onDismissHostPromoted,
            title = { Text(stringResource(R.string.results_host_promoted_title)) },
            text = { Text(stringResource(R.string.results_host_promoted_body)) },
            confirmButton = {
                TextButton(onClick = onDismissHostPromoted) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    val finished = standings.filter { it.finishRank != null }.sortedBy { it.finishRank }
    val crashed = standings.filter { it.crashed && it.finishRank == null }
    val timedOut = standings.filter { !it.crashed && it.finishRank == null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.results_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        if (finished.isNotEmpty()) {
            Text(stringResource(R.string.results_finished_label), style = MaterialTheme.typography.titleMedium)
            finished.forEach { standing ->
                val points = Scoring.pointsForRank(standing.finishRank)
                Text(
                    text = stringResource(
                        R.string.results_rank_row,
                        standing.finishRank ?: 0,
                        standing.name,
                        aiTag(standing.isAi),
                        points,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (crashed.isNotEmpty()) {
            Text(stringResource(R.string.results_crashed_label), style = MaterialTheme.typography.titleMedium)
            crashed.forEach { standing ->
                Text(
                    text = stringResource(R.string.results_name_row, standing.name, aiTag(standing.isAi)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (timedOut.isNotEmpty()) {
            Text(stringResource(R.string.results_dnf_label), style = MaterialTheme.typography.titleMedium)
            timedOut.forEach { standing ->
                Text(
                    text = stringResource(
                        R.string.results_no_points_row, standing.name, aiTag(standing.isAi),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onWatchReplay,
            enabled = lastReplay != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.results_watch_replay))
        }
        Spacer(Modifier.height(16.dp))

        if (isHost) {
            Row {
                Button(onClick = onRematch) { Text(stringResource(R.string.results_rematch)) }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.results_leave)) }
            }
        } else {
            Text(
                stringResource(R.string.results_waiting_for_host),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.results_leave)) }
        }
    }
}
