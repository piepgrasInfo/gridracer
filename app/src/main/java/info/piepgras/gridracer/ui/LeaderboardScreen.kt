package info.piepgras.gridracer.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.LeaderboardEntry

private const val DISPLAY_LIMIT = 10

/** The local leaderboard: the top entries by points, with races and wins. */
@Composable
fun LeaderboardScreen(entries: List<LeaderboardEntry>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val displayed = entries.take(DISPLAY_LIMIT)
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.menu_leaderboard), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        Spacer(Modifier.height(16.dp))

        if (displayed.isEmpty()) {
            Text(stringResource(R.string.leaderboard_empty), style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn {
                itemsIndexed(displayed) { index, entry ->
                    val rank = index + 1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("$rank. ${entry.name}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.leaderboard_points, entry.points, entry.races, entry.wins),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}
