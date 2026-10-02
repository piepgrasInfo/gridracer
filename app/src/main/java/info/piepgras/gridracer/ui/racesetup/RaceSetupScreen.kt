package info.piepgras.gridracer.ui.racesetup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.ConnectionState
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.RoomSummary

/**
 * The one screen that sets up every kind of race. Two panes: on the left one scrolling list
 * that runs from the mode chips through everything those chips imply, and on the right a
 * fixed action panel holding the things that are the same shape whatever you are starting -
 * where to connect, who you are, and the button that starts it.
 *
 * Its form state lives in the ViewModel rather than in `rememberSaveable`, so a detour to the
 * ratings table and back comes home to the same setup, and a 100-track tournament itinerary
 * never has to fit through a Bundle.
 */
@Composable
fun RaceSetupScreen(
    state: RaceSetupState,
    allTracks: List<Track>,
    connectionState: ConnectionState,
    connectionEncrypted: Boolean,
    /** Whether the connected server can race with the gravel trap; a local race always can. */
    serverSupportsGravelTrap: Boolean,
    rooms: List<RoomSummary>,
    roomsLoading: Boolean,
    playerName: String,
    errorMessage: String?,
    onUpdate: ((RaceSetupState) -> RaceSetupState) -> Unit,
    onPlayerNameChange: (String) -> Unit,
    onRefreshRooms: () -> Unit,
    onSubmit: () -> Unit,
    onRatings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val plan = remember(state, allTracks) { state.plan(allTracks) }

    Row(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // One list, top to bottom: what kind of race, then everything that configures it.
        // A LazyColumn rather than a scrolling Column because in Join and Spectate the tail
        // of this list is the server's room list, which has no bounded length.
        LazyColumn(
            modifier = Modifier
                .weight(SELECTOR_PANE_WEIGHT)
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.menu_start_race),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                }
            }
            item(key = "mode") {
                RaceSetupModeChips(state = state, onUpdate = onUpdate)
            }
            item(key = "divider") { HorizontalDivider() }

            if (state.mode.usesRoomList) {
                item(key = "rooms-header") { RoomListHeader(onRefresh = onRefreshRooms) }
                if (rooms.isEmpty()) {
                    item(key = "rooms-empty") { RoomListPlaceholder(loading = roomsLoading) }
                } else {
                    items(rooms, key = { it.roomCode }) { room ->
                        RoomCard(
                            room = room,
                            selected = room.roomCode == state.roomCode,
                            enabled = room.isSelectableFor(state.mode == SetupMode.SPECTATE),
                            onClick = { onUpdate { it.copy(roomCode = room.roomCode) } },
                        )
                    }
                }
            } else {
                item(key = "selectors") {
                    RaceSetupSelectors(
                        state = state,
                        plan = plan,
                        allTracks = allTracks,
                        onUpdate = onUpdate,
                        gravelAvailable = !state.mode.isOnline || serverSupportsGravelTrap,
                    )
                }
            }
        }

        VerticalDivider()

        RaceSetupActionPane(
            state = state,
            plan = plan,
            connectionState = connectionState,
            connectionEncrypted = connectionEncrypted,
            playerName = playerName,
            errorMessage = errorMessage,
            onUpdate = onUpdate,
            onPlayerNameChange = onPlayerNameChange,
            onSubmit = onSubmit,
            onRatings = onRatings,
            modifier = Modifier
                .weight(ACTION_PANE_WEIGHT)
                .fillMaxHeight()
                .padding(12.dp),
        )
    }
}

private const val SELECTOR_PANE_WEIGHT = 0.70f
private const val ACTION_PANE_WEIGHT = 0.30f

/** Android Studio preview of [RaceSetupScreen] set up for a local single race. */
@Preview(showBackground = true, widthDp = 800, heightDp = 380)
@Composable
fun RaceSetupScreenPreview() {
    MaterialTheme {
        RaceSetupScreen(
            state = RaceSetupState(),
            allTracks = Tracks.all,
            connectionState = ConnectionState.Disconnected,
            connectionEncrypted = false,
            serverSupportsGravelTrap = false,
            rooms = emptyList(),
            roomsLoading = false,

            playerName = "",
            errorMessage = null,
            onUpdate = {},
            onPlayerNameChange = {},
            onRefreshRooms = {},
            onSubmit = {},
            onRatings = {},
            onBack = {},
        )
    }
}

/** Android Studio preview of [RaceSetupScreen] hosting an online tournament. */
@Preview(showBackground = true, widthDp = 800, heightDp = 380)
@Composable
fun RaceSetupScreenHostTournamentPreview() {
    MaterialTheme {
        RaceSetupScreen(
            state = RaceSetupState(
                mode = SetupMode.HOST,
                format = SetupFormat.TOURNAMENT,
                itinerary = listOf(Tracks.OVAL, Tracks.SQUARE),
            ),
            allTracks = Tracks.all,
            connectionState = ConnectionState.Connected,
            connectionEncrypted = true,
            serverSupportsGravelTrap = true,
            rooms = emptyList(),
            roomsLoading = false,

            playerName = "Martin",
            errorMessage = null,
            onUpdate = {},
            onPlayerNameChange = {},
            onRefreshRooms = {},
            onSubmit = {},
            onRatings = {},
            onBack = {},
        )
    }
}
