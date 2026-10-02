package info.piepgras.gridracer.ui.racesetup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.ConnectionState
import info.piepgras.gridracer.R
import info.piepgras.gridracer.ui.ConnectionSecurityIcon
import info.piepgras.gridracer.ui.SetupDimens
import info.piepgras.gridracer.ui.localizedMultiplayerError

/**
 * The right-hand panel: the few things that aren't selectors - where to connect, who
 * you are, and the button that starts the thing. Scrolls as a whole so all fields and the
 * submit button live in one scrollable list.
 */
@Composable
fun RaceSetupActionPane(
    state: RaceSetupState,
    plan: RaceSetupPlan,
    connectionState: ConnectionState,
    connectionEncrypted: Boolean,
    playerName: String,
    errorMessage: String?,
    onUpdate: ((RaceSetupState) -> RaceSetupState) -> Unit,
    onPlayerNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRatings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The server URL is no longer edited here - it lives in the Settings screen's Server
    // section, alongside login. The client picks it up from settings when it connects.

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (state.mode.usesRoomList) {
            OutlinedTextField(
                value = state.roomCode,
                onValueChange = { code -> onUpdate { it.copy(roomCode = code.trim()) } },
                label = { Text(stringResource(R.string.multiplayer_room_code), style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodySmall),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SetupDimens.LabelledFieldHeight),
            )
            Spacer(Modifier.height(FIELD_GAP))
        }

        // Host: blank leaves the room public. Join: only needed for a locked room, but
        // always offered - the list marks which rooms will ask.
        if (state.mode == SetupMode.HOST || state.mode == SetupMode.JOIN) {
            OutlinedTextField(
                value = state.roomPassword,
                onValueChange = { pw -> onUpdate { it.copy(roomPassword = pw) } },
                label = {
                    Text(
                        stringResource(
                            if (state.mode == SetupMode.HOST) {
                                R.string.multiplayer_room_password_optional
                            } else {
                                R.string.multiplayer_room_password
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodySmall),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SetupDimens.LabelledFieldHeight),
            )
            Spacer(Modifier.height(FIELD_GAP))
        }

        if (state.mode.needsPlayerName) {
            OutlinedTextField(
                value = playerName,
                onValueChange = { onPlayerNameChange(sanitizeName(it)) },
                label = { Text(stringResource(R.string.multiplayer_player_name), style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodySmall),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SetupDimens.LabelledFieldHeight),
            )
            Spacer(Modifier.height(FIELD_GAP))
        }

        plan.blocker?.messageRes?.let { res ->
            Text(
                stringResource(res),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (state.mode.isOnline) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusText = when {
                    errorMessage != null -> localizedMultiplayerError(errorMessage)
                    else -> stringResource(connectionState.labelRes)
                }
                val isError = errorMessage != null ||
                    connectionState == ConnectionState.Error ||
                    connectionState == ConnectionState.Disconnected
                // Once connected, say whether the line is encrypted: the open padlock is the
                // warning for a plain ws:// server, the closed one is earned by the handshake.
                if (connectionState == ConnectionState.Connected && errorMessage == null) {
                    ConnectionSecurityIcon(
                        encrypted = connectionEncrypted,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val connected = connectionState == ConnectionState.Connected
                Text(
                    stringResource(R.string.multiplayer_ratings),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clickable(enabled = connected, onClick = onRatings)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Button(
            onClick = {
                onSubmit()
            },
            enabled = plan.canSubmit,
            contentPadding = SetupDimens.ButtonPadding,
            modifier = Modifier
                .fillMaxWidth()
                .height(SetupDimens.ButtonHeight),
        ) {
            Text(
                stringResource(state.actionLabelRes),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val FIELD_GAP = SetupDimens.RowGap

private val ConnectionState.labelRes: Int
    get() = when (this) {
        ConnectionState.Disconnected -> R.string.multiplayer_disconnected
        ConnectionState.Connecting -> R.string.multiplayer_connecting
        ConnectionState.Reconnecting -> R.string.multiplayer_reconnecting
        ConnectionState.Connected -> R.string.multiplayer_connected
        ConnectionState.Error -> R.string.multiplayer_error
    }

private val RaceSetupState.actionLabelRes: Int
    get() = when (mode) {
        SetupMode.LOCAL ->
            if (format == SetupFormat.TOURNAMENT) R.string.tournament_start else R.string.setup_start_race
        SetupMode.HOST ->
            if (format == SetupFormat.TOURNAMENT) R.string.multiplayer_host_tournament else R.string.multiplayer_host
        SetupMode.JOIN -> R.string.multiplayer_join
        SetupMode.SPECTATE -> R.string.multiplayer_spectate
    }

/**
 * Null where the blocked control is its own explanation - an empty room-code box, or the
 * itinerary counter already sitting in the left pane.
 */
private val SetupBlocker.messageRes: Int?
    get() = when (this) {
        SetupBlocker.NO_TRACKS, SetupBlocker.TRACK_UNRACEABLE -> R.string.setup_track_no_start_positions
        SetupBlocker.NOT_ENOUGH_PLAYERS -> R.string.setup_not_enough_players
        SetupBlocker.DUPLICATE_HUMAN_NAMES -> R.string.setup_duplicate_names_warning
        SetupBlocker.ITINERARY_TOO_SHORT, SetupBlocker.ITINERARY_TOO_LONG, SetupBlocker.NO_ROOM_CODE -> null
    }
