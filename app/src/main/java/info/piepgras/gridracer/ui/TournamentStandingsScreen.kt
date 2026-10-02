package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.Tournament
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlayerStatus

/**
 * Shown after each tournament race: [justFinishedState] non-null renders that race's
 * finished/crashed breakdown plus a "Continue" button; null (the final track) instead
 * renders a "Tournament Complete" heading with only a Main Menu button.
 */
@Composable
fun TournamentStandingsScreen(
    tournament: Tournament,
    justFinishedState: GameState?,
    onContinue: () -> Unit,
    onMainMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onMainMenu)
    fun nameOf(playerSlot: Int) = tournament.standings.firstOrNull { it.playerSlot == playerSlot }?.name ?: "P${playerSlot + 1}"

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (justFinishedState != null) {
            Text(
                stringResource(R.string.tournament_track_progress, tournament.currentTrackIndex, tournament.tracks.size),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(16.dp))
            val finished = justFinishedState.players.filter { it.status == PlayerStatus.FINISHED }.sortedBy { it.finishRank }
            val crashed = justFinishedState.players.filter { it.status == PlayerStatus.CRASHED }
            if (finished.isNotEmpty()) {
                Text(stringResource(R.string.results_finished_label), style = MaterialTheme.typography.titleMedium)
                finished.forEach { player ->
                    Text(
                        "${player.finishRank}. ${nameOf(player.id)}",
                        color = PlayerColors[player.id % PlayerColors.size],
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (crashed.isNotEmpty()) {
                Text(stringResource(R.string.results_crashed_label), style = MaterialTheme.typography.titleMedium)
                crashed.forEach { player ->
                    Text(nameOf(player.id), color = PlayerColors[player.id % PlayerColors.size], style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            Text(stringResource(R.string.tournament_complete_title), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.tournament_standings_label), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        tournament.standings.sortedByDescending { it.points }.forEachIndexed { rank, standing ->
            Text(
                stringResource(
                    R.string.tournament_standings_row,
                    rank + 1, standing.name, aiTag(standing.isAi), standing.points,
                ),
                color = PlayerColors[standing.playerSlot % PlayerColors.size],
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(32.dp))

        Row {
            if (justFinishedState != null) {
                Button(onClick = onContinue) { Text(stringResource(R.string.action_continue)) }
                Spacer(Modifier.width(12.dp))
            }
            OutlinedButton(onClick = onMainMenu) { Text(stringResource(R.string.action_main_menu)) }
        }
    }
}
