package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.Screen.TrackSource
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.CustomTrack
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.multiplayer.AccountInfo
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.multiplayer.SharedTrackSummary

/**
 * Details for any track: built-in, custom, or shared.
 *
 * Shows the track preview, name, and identity (uuid / server id / user id). For shared tracks
 * it also shows authorship, rating aggregate, a download button (anonymous-OK), and a 1..5
 * rating control (authenticated only). For custom tracks it shows edit/delete/upload buttons.
 * Built-in tracks are read-only — the info page is informational only.
 */
@Composable
fun TrackInfoScreen(
    source: TrackSource?,
    downloaded: ServerMessage.TrackDownloaded?,
    account: AccountInfo?,
    lastRated: ServerMessage.TrackRated?,
    rateError: String?,
    onDownload: () -> Unit,
    onRate: (Int) -> Unit,
    /** Flags a shared track for operator review, with a free-text reason. */
    onReport: (String) -> Unit,
    onEdit: (CustomTrack) -> Unit,
    onDelete: (CustomTrack) -> Unit,
    onUpload: (CustomTrack) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        Spacer(Modifier.height(16.dp))

        if (source == null) {
            Text(stringResource(R.string.track_info_not_found), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        when (source) {
            is TrackSource.BuiltIn -> BuiltInTrackInfo(source.track)
            is TrackSource.Custom -> CustomTrackInfo(
                customTrack = source.customTrack,
                onEdit = { onEdit(source.customTrack) },
                onDelete = { onDelete(source.customTrack) },
                onUpload = { onUpload(source.customTrack) },
                onOpenSettings = onOpenSettings,
                canUpload = account != null,
            )
            is TrackSource.Shared -> SharedTrackInfo(
                summary = source.summary,
                downloaded = downloaded,
                account = account,
                lastRated = lastRated,
                rateError = rateError,
                onDownload = onDownload,
                onRate = onRate,
                onReport = onReport,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun BuiltInTrackInfo(track: Track) {
    TrackPreview(track, modifier = Modifier.fillMaxWidth().height(180.dp))
    Spacer(Modifier.height(16.dp))
    Text(track.name, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.track_dimensions, track.width, track.height),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    InfoRow(stringResource(R.string.track_info_uuid), stringResource(R.string.track_info_not_applicable))
    InfoRow(stringResource(R.string.track_info_server_id), stringResource(R.string.track_info_not_applicable))
    InfoRow(stringResource(R.string.track_info_user_id), stringResource(R.string.track_info_not_applicable))
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.track_info_builtin_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CustomTrackInfo(
    customTrack: CustomTrack,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
    onOpenSettings: () -> Unit,
    canUpload: Boolean,
) {
    TrackPreview(customTrack.track, modifier = Modifier.fillMaxWidth().height(180.dp))
    Spacer(Modifier.height(16.dp))
    Text(customTrack.name, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.track_dimensions, customTrack.track.width, customTrack.track.height),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    InfoRow(stringResource(R.string.track_info_uuid), customTrack.id)
    InfoRow(stringResource(R.string.track_info_server_id), stringResource(R.string.track_info_not_applicable))
    InfoRow(stringResource(R.string.track_info_user_id), stringResource(R.string.track_info_not_applicable))
    val sharedId = customTrack.sharedTrackId
    if (sharedId != null) {
        InfoRow(stringResource(R.string.track_info_shared_id), sharedId)
    }
    // Deleting a hand-drawn track is irreversible and there is no undo, so it asks first.
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.tracks_delete_title),
            message = stringResource(R.string.tracks_delete_message, customTrack.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
        TextButton(onClick = { confirmDelete = true }) {
            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
        }
    }
    Spacer(Modifier.height(8.dp))
    if (canUpload) {
        Button(onClick = onUpload) { Text(stringResource(R.string.shared_tracks_upload)) }
    } else {
        Text(stringResource(R.string.shared_tracks_login_required), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.shared_tracks_open_settings)) }
    }
}

@Composable
private fun SharedTrackInfo(
    summary: SharedTrackSummary,
    downloaded: ServerMessage.TrackDownloaded?,
    account: AccountInfo?,
    lastRated: ServerMessage.TrackRated?,
    rateError: String?,
    onDownload: () -> Unit,
    onRate: (Int) -> Unit,
    onReport: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    downloaded?.let {
        TrackPreview(it.track, modifier = Modifier.fillMaxWidth().height(180.dp))
        Spacer(Modifier.height(16.dp))
    }

    Text(summary.name, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))

    // Identity: uuid (trackId), server id, user id (author username).
    InfoRow(stringResource(R.string.track_info_uuid), summary.trackId)
    InfoRow(stringResource(R.string.track_info_server_id), summary.serverId.ifBlank { stringResource(R.string.track_info_not_applicable) })
    InfoRow(stringResource(R.string.track_info_user_id), summary.authorUsername.ifBlank { stringResource(R.string.track_info_not_applicable) })
    Spacer(Modifier.height(8.dp))

    // Authorship: server name, @username, display name.
    val authorLine = buildString {
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
    if (authorLine.isNotBlank()) {
        Text(authorLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }

    // Rating aggregate.
    if (summary.ratingCount > 0) {
        Text(
            stringResource(R.string.track_info_rating_aggregate, summary.averageRating, summary.ratingCount),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(stringResource(R.string.track_info_no_ratings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Your own score, preferring a rating cast in this session over the one the server sent
    // with the summary - so the control updates the moment you tap it rather than after the
    // next list refresh. Before the server started sending `yourRating`, only the first of
    // these existed, so a track you had rated last week showed as unrated.
    val yourRating = lastRated?.takeIf { it.trackId == summary.trackId }?.yourRating ?: summary.yourRating
    if (yourRating != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.track_info_your_rating, yourRating),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(16.dp))

    // Download (anonymous-OK).
    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.shared_tracks_download))
    }
    Spacer(Modifier.height(16.dp))

    // Rating control.
    Text(stringResource(R.string.track_info_rate_label), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (account == null) {
        Text(stringResource(R.string.track_info_sign_in_to_rate), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.shared_tracks_open_settings)) }
    } else {
        RatingChips(selected = yourRating, onSelect = onRate)
    }
    if (rateError != null) {
        Spacer(Modifier.height(8.dp))
        Text(rateError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(24.dp))
    ReportTrackSection(
        trackName = summary.name,
        signedIn = account != null,
        onReport = onReport,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * "Report this track", with a reason.
 *
 * The whole path behind this - `ClientMessage.ReportTrack`, the `track_reports` table, the
 * operator review - already existed and was reachable from nowhere in the app. Shared tracks
 * are user-generated content shown to other users, and an in-app way to flag them is not
 * optional: Google Play's UGC policy requires it, and without it the only route was to email
 * the developer.
 */
@Composable
private fun ReportTrackSection(
    trackName: String,
    signedIn: Boolean,
    onReport: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    if (signedIn) {
        TextButton(onClick = { showDialog = true }) {
            Text(
                stringResource(R.string.track_info_report),
                color = MaterialTheme.colorScheme.error,
            )
        }
    } else {
        // Reports are tied to an account so a flood can be traced and rate-limited; say so
        // rather than showing a button that would be refused.
        Text(
            stringResource(R.string.track_info_sign_in_to_report),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.shared_tracks_open_settings)) }
    }

    if (showDialog) {
        ReportTrackDialog(
            trackName = trackName,
            onSubmit = { reason ->
                onReport(reason)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ReportTrackDialog(
    trackName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_info_report_title)) },
        text = {
            // Bounded height and the controls outside it: in landscape the dialog body is only
            // a few lines tall and scrolls, so anything inside it can end up out of reach.
            Column(modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.track_info_report_message, trackName),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= MAX_REPORT_REASON) reason = it },
                    label = { Text(stringResource(R.string.track_info_report_reason)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onSubmit(reason.trim()) },
                    enabled = reason.isNotBlank(),
                ) {
                    Text(stringResource(R.string.track_info_report_submit))
                }
            }
        },
    )
}

/** Matches the server's `TrackShareService.MAX_REPORT_REASON_LENGTH`, which truncates silently. */
private const val MAX_REPORT_REASON = 500

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun RatingChips(selected: Int?, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in 1..5) {
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(value.toString()) },
            )
        }
    }
}
