package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.Tournament
import info.piepgras.gridracer.replay.ReplayData

/** Standings of an online tournament, between races or at its end; the host moves the room on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TournamentMultiplayerStandingsScreen(
    tournament: Tournament,
    isHost: Boolean,
    isFinal: Boolean,
    lastReplay: ReplayData?,
    onWatchReplay: () -> Unit,
    onExportReplay: () -> Unit,
    onStartRace: () -> Unit,
    onMainMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Backing out mid-tournament means leaving the room, same as the Main menu button - there
    // is nowhere behind this while the server still has you in the standings.
    BackHandler(onBack = onMainMenu)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (isFinal) stringResource(R.string.tournament_complete_title)
                        else stringResource(R.string.tournament_standings_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMainMenu) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isFinal) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            R.string.tournament_track_progress,
                            tournament.currentTrackIndex,
                            tournament.tracks.size,
                        ),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Text(stringResource(R.string.tournament_standings_label), style = MaterialTheme.typography.titleMedium)
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

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onWatchReplay,
                enabled = lastReplay != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.results_watch_replay))
            }
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onExportReplay,
                enabled = lastReplay != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save_replay))
            }
            Spacer(Modifier.height(8.dp))

            if (!isFinal && isHost) {
                Button(onClick = onStartRace, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tournament_standings_start_next))
                }
            } else if (!isFinal && !isHost) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.tournament_lobby_waiting),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            OutlinedButton(onClick = onMainMenu, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_main_menu))
            }
        }
    }
}
