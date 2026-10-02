package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.multiplayer.RatingEntry
import info.piepgras.gridracer.multiplayer.RatingsPage

/**
 * The global leaderboard. [page] is null while the first page is in flight.
 *
 * Rows are keyed by an opaque public id rather than the player token this screen used to be
 * handed - see [RatingEntry].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingsScreen(
    page: RatingsPage?,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.ratings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                page == null -> Centered { CircularProgressIndicator() }
                page.entries.isEmpty() -> Centered { Text(stringResource(R.string.ratings_empty)) }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(page.entries, key = { it.publicId }) { entry ->
                            LeaderboardRow(entry, isYou = entry.publicId == page.you?.publicId)
                        }
                        // Your own row, when this page doesn't already contain it.
                        val you = page.you
                        if (you != null && page.entries.none { it.publicId == you.publicId }) {
                            item(key = "you") { LeaderboardRow(you, isYou = true) }
                        }
                        if (page.entries.size + page.offset < page.totalEntries) {
                            item(key = "more") {
                                OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.action_load_more))
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_back))
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(entry: RatingEntry, isYou: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isYou) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${entry.rank}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.ratings_row, entry.matches, entry.wins),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(entry.rating.toString(), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
