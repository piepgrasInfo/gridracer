package info.piepgras.gridracer.ui.racesetup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.multiplayer.RoomSummary

/*
 * The room list shown in Join and Spectate. Emitted piecewise rather than as one composable
 * so the selector pane's single LazyColumn can lay the cards out as its own items - the whole
 * pane scrolls as one list, and an unbounded room count still only composes what's visible.
 *
 * Tapping a room fills the room code in the action pane rather than joining outright, since
 * joining still needs a name.
 */

/** The heading of the room list, with its refresh button. */
@Composable
fun RoomListHeader(onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.setup_tap_room_hint),
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
    }
}

/** Placeholder for the two states with no cards to show, sized so the list doesn't jump. */
@Composable
fun RoomListPlaceholder(loading: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PLACEHOLDER_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Text(stringResource(R.string.multiplayer_no_rooms))
        }
    }
}

/** True when this room is a legal target for the current mode. */
fun RoomSummary.isSelectableFor(spectating: Boolean): Boolean =
    // A spectator wants exactly the rooms a joiner can't have: in progress, or already full.
    spectating || (!inProgress && players < maxPlayers)

private val PLACEHOLDER_HEIGHT = 96.dp

/**
 * One room in the list: its name or code, host, track, laps and seats.
 *
 * @param selected Whether this room's code is the one filled in.
 * @param enabled Whether the room can be chosen in the current mode; see [isSelectableFor].
 */
@Composable
fun RoomCard(
    room: RoomSummary,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(
        colors = colors,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (room.isPasswordProtected) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.multiplayer_room_private),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        room.roomName ?: room.roomCode,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text("${room.players}/${room.maxPlayers}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${room.hostName} · ${room.trackName} · ${room.laps}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (room.gravelTrap) {
                Text(
                    stringResource(R.string.lobby_gravel_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!enabled) {
                Text(
                    stringResource(R.string.multiplayer_room_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
