package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.ConnectionState
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.CustomTrack
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.multiplayer.SharedTrackSummary

/**
 * The tracks screen: built-in tracks, the user's custom tracks, and the server's shared-track
 * catalogue, all in one scrolling list.
 *
 * The shared-track section used to live behind a separate "Shared tracks" button and screen;
 * it is now inline so browsing/downloading community tracks does not require navigating away.
 * Tapping a shared-track row opens [TrackInfoScreen] for details, rating and download.
 * Browsing and downloading work anonymously; only rating requires an account, enforced on the
 * server and surfaced in the info screen.
 */
@Composable
fun TracksBrowserScreen(
    customTracks: List<CustomTrack>,
    sharedTrackList: ServerMessage.TrackList?,
    connectionState: ConnectionState,
    onBack: () -> Unit,
    onNewTrack: () -> Unit,
    onOpenBuiltInTrack: (Track) -> Unit,
    onOpenCustomTrack: (CustomTrack) -> Unit,
    onRefreshSharedTracks: () -> Unit,
    onOpenSharedTrack: (SharedTrackSummary) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.menu_tracks), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(Tracks.all) { track ->
                TrackRow(track, onClick = { onOpenBuiltInTrack(track) })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tracks_custom_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onNewTrack) { Text(stringResource(R.string.tracks_new_track)) }
                Spacer(Modifier.height(8.dp))
            }
            items(customTracks, key = { it.id }) { customTrack ->
                CustomTrackRow(customTrack, onClick = { onOpenCustomTrack(customTrack) })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.shared_tracks_title), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onRefreshSharedTracks) {
                        Text(stringResource(R.string.shared_tracks_refresh))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item {
                SharedTracksStatus(
                    connectionState = connectionState,
                    trackList = sharedTrackList,
                    onOpenSettings = onOpenSettings,
                )
            }
            items(sharedTrackList?.tracks ?: emptyList(), key = { it.trackId }) { summary ->
                SharedTrackRow(summary, onClick = { onOpenSharedTrack(summary) })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackPreview(track, modifier = Modifier.width(97.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.track_dimensions, track.width, track.height),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One custom track in the library: the same shape as a built-in row, i.e. one tap target.
 *
 * Edit / Delete / Upload used to sit *inside* this row while the row itself was clickable, so
 * three small buttons competed with a full-width target wrapped around them - and Delete had
 * no confirmation. All three live on the info screen the row opens, which is where the track's
 * identity and preview already are.
 */
@Composable
private fun CustomTrackRow(
    customTrack: CustomTrack,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackPreview(customTrack.track, modifier = Modifier.width(97.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(customTrack.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.track_dimensions, customTrack.track.width, customTrack.track.height),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (customTrack.sharedTrackId != null) {
                Text(
                    stringResource(R.string.tracks_downloaded_marker),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SharedTracksStatus(
    connectionState: ConnectionState,
    trackList: ServerMessage.TrackList?,
    onOpenSettings: () -> Unit,
) {
    when {
        connectionState == ConnectionState.Connecting || connectionState == ConnectionState.Reconnecting -> {
            Text(stringResource(R.string.shared_tracks_loading), style = MaterialTheme.typography.bodyMedium)
        }
        connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error -> {
            Text(stringResource(R.string.shared_tracks_not_connected), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.shared_tracks_open_settings)) }
        }
        trackList == null -> {
            Text(stringResource(R.string.shared_tracks_loading), style = MaterialTheme.typography.bodyMedium)
        }
        trackList.tracks.isEmpty() -> {
            Text(stringResource(R.string.shared_tracks_empty), style = MaterialTheme.typography.bodyMedium)
        }
        else -> Unit
    }
}

@Composable
private fun SharedTrackRow(summary: SharedTrackSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(summary.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val author = buildString {
                if (summary.serverName.isNotBlank()) append(summary.serverName)
                if (summary.authorUsername.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append('@').append(summary.authorUsername)
                }
                if (summary.authorName.isNotBlank() && summary.authorName != summary.authorUsername) {
                    if (isNotEmpty()) append(" · ")
                    append(summary.authorName)
                }
            }
            if (author.isNotBlank()) {
                Text(author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (summary.ratingCount > 0) {
                Text(
                    stringResource(
                        R.string.shared_tracks_rating_summary,
                        summary.averageRating,
                        summary.ratingCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
