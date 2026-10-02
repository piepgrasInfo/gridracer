package info.piepgras.gridracer.ui

import info.piepgras.gridracer.engine.AiLevel
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.piepgras.gridracer.GameViewModel
import info.piepgras.gridracer.MultiplayerState
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.PlacementState
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.Tracks
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.ui.racesetup.LAP_CHOICES
import info.piepgras.gridracer.ui.racesetup.LAP_RANGE
import info.piepgras.gridracer.ui.racesetup.MAX_PLAYERS
import info.piepgras.gridracer.ui.racesetup.MIN_PLAYERS

private const val LOBBY_LEFT_WEIGHT = 0.70f
private const val LOBBY_RIGHT_WEIGHT = 0.30f
private val CHECKBOX_COLUMN_WIDTH = 48.dp
/**
 * The host's per-row action column. Wide enough for the "Add AI" text button on an empty seat,
 * not just the remove icon - and one width for both, so the table does not shift as seats fill.
 */
private val HOST_ACTION_COLUMN_WIDTH = 84.dp

/**
 * What a server room looks like before the race proper: the lobby, or the remote placement
 * phase. Everything that used to sit in front of this - hosting, joining, the room list,
 * spectating - now lives on the unified race setup screen.
 */
@Composable
fun OnlineRoomScreen(
    viewModel: GameViewModel,
    onBack: () -> Unit,
) {
    val multiplayerState by viewModel.multiplayerState.collectAsState()
    val error by viewModel.multiplayerError.collectAsState()
    val kicked by viewModel.kicked.collectAsState()
    val allTracks by viewModel.allTracksForLobby.collectAsState()
    val room by viewModel.roomInfo.collectAsState()
    val networkTournament by viewModel.networkTournament.collectAsState()
    val gravelSupported by viewModel.serverSupportsGravelTrap.collectAsState()
    val aiLevelsSupported by viewModel.serverSupportsAiLevels.collectAsState()

    // One dialog at a time: being kicked also produces a RoomClosed-shaped error, and two
    // stacked dialogs meant dismissing one only revealed another.
    if (kicked != null) {
        MultiplayerErrorDialog(message = kicked!!, onDismiss = { viewModel.clearKicked() })
    }

    OnlineRoomContent(
        multiplayerState = multiplayerState,
        error = error.takeIf { kicked == null },
        allTracks = allTracks,
        roomCode = room?.roomCode,
        tournament = networkTournament,
        onClearError = { viewModel.clearMultiplayerError() },
        onReady = { viewModel.setOnlineReady(it) },
        onStart = { viewModel.startOnlineRace() },
        onLeave = { viewModel.leaveOnlineGame() },
        onChooseSlot = { viewModel.chooseOnlinePlacementSlot(it) },
        onKickPlayer = { viewModel.kickOnlinePlayer(it) },
        onAddAiPlayer = { viewModel.addOnlineAiPlayer() },
        onChangeTrack = { viewModel.changeOnlineTrack(it) },
        onChangeLaps = { viewModel.changeOnlineLaps(it) },
        onChangeMaxPlayers = { viewModel.changeOnlineMaxPlayers(it) },
        onChangeTurnTimeoutSeconds = { viewModel.changeOnlineTurnTimeoutSeconds(it) },
        onBack = onBack,
        gravelSupported = gravelSupported,
        aiLevelsSupported = aiLevelsSupported,
        onChangeGravelTrap = { viewModel.changeOnlineGravelTrap(it) },
        onChangeAiLevel = { seat, level -> viewModel.changeOnlineAiLevel(seat, level) },
    )
}

/**
 * The online room as plain parameters, so it can be previewed and tested without a ViewModel: the
 * lobby with its seats and host controls, or the placement phase, depending on [multiplayerState].
 */
@Composable
fun OnlineRoomContent(
    multiplayerState: MultiplayerState,
    error: String?,
    allTracks: List<Track>,
    /** The code other players type to join. Null before the server has assigned one. */
    roomCode: String?,
    tournament: info.piepgras.gridracer.Tournament? = null,
    onClearError: () -> Unit,
    onReady: (Boolean) -> Unit,
    onStart: () -> Unit,
    onLeave: () -> Unit,
    onChooseSlot: (Point) -> Unit,
    onKickPlayer: (Int) -> Unit,
    /** Host-only: seat an AI driver in the next free slot. */
    onAddAiPlayer: () -> Unit,
    onChangeTrack: (Track) -> Unit,
    onChangeLaps: (Int) -> Unit,
    onChangeMaxPlayers: (Int) -> Unit,
    onChangeTurnTimeoutSeconds: (Int) -> Unit,
    /** Whether the server can race this room with the gravel trap; the switch and the status line need it. */
    gravelSupported: Boolean = false,
    /** Whether the server seats computer drivers at a chosen level; the level menu needs it. */
    aiLevelsSupported: Boolean = false,
    onChangeGravelTrap: (Boolean) -> Unit = {},
    onChangeAiLevel: (Int, AiLevel) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    // The only game screen that had no back handler: system back either did nothing or dropped
    // the player out of the app while they were sitting in a room.
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }

    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.multiplayer_leave_title),
            message = stringResource(R.string.multiplayer_leave_message),
            confirmLabel = stringResource(R.string.multiplayer_leave),
            onConfirm = onLeave,
            onDismiss = { confirmLeave = false },
        )
    }

    error?.let { message ->
        MultiplayerErrorDialog(message = message, onDismiss = onClearError)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        when (val state = multiplayerState) {
            is MultiplayerState.Idle -> ConnectingSpinner(onBack)
            is MultiplayerState.Lobby -> MultiplayerLobby(
                lobby = state.lobby,
                localPlayerId = state.localPlayerId,
                isHost = state.isHost,
                readOnly = state.readOnly,
                allTracks = allTracks,
                roomCode = roomCode,
                tournament = tournament,
                onReady = onReady,
                onStart = onStart,
                onLeave = { confirmLeave = true },
                onKickPlayer = onKickPlayer,
                onAddAiPlayer = onAddAiPlayer,
                onChangeTrack = onChangeTrack,
                onChangeLaps = onChangeLaps,
                onChangeMaxPlayers = onChangeMaxPlayers,
                onChangeTurnTimeoutSeconds = onChangeTurnTimeoutSeconds,
                gravelSupported = gravelSupported,
                aiLevelsSupported = aiLevelsSupported,
                onChangeGravelTrap = onChangeGravelTrap,
                onChangeAiLevel = onChangeAiLevel,
            )
            is MultiplayerState.Placement -> RemotePlacement(
                placement = state.placement,
                localPlayerId = state.localPlayerId,
                onChooseSlot = onChooseSlot,
                onQuit = { confirmLeave = true },
            )
        }
    }
}

@Composable
private fun ConnectingSpinner(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.multiplayer_connecting))
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
    }
}

@Composable
private fun MultiplayerLobby(
    lobby: ServerMessage.LobbyUpdate,
    localPlayerId: Int,
    isHost: Boolean,
    readOnly: Boolean,
    allTracks: List<Track>,
    roomCode: String?,
    tournament: info.piepgras.gridracer.Tournament? = null,
    onReady: (Boolean) -> Unit,
    onStart: () -> Unit,
    onLeave: () -> Unit,
    onKickPlayer: (Int) -> Unit,
    onAddAiPlayer: () -> Unit,
    onChangeTrack: (Track) -> Unit,
    onChangeLaps: (Int) -> Unit,
    onChangeMaxPlayers: (Int) -> Unit,
    onChangeTurnTimeoutSeconds: (Int) -> Unit,
    gravelSupported: Boolean,
    aiLevelsSupported: Boolean,
    onChangeGravelTrap: (Boolean) -> Unit,
    onChangeAiLevel: (Int, AiLevel) -> Unit,
) {
    var showTrackPicker by remember { mutableStateOf(false) }
    var pendingKick by remember { mutableStateOf<Int?>(null) }

    // Removing an AI driver and removing a person are the same operation and want completely
    // different words: one is undoing your own tap, the other is throwing somebody out.
    val kickingAi = pendingKick?.let { lobby.players.getOrNull(it)?.isAi } == true
    ConfirmDialogFor(
        target = pendingKick,
        title = stringResource(
            if (kickingAi) R.string.multiplayer_remove_ai_title else R.string.multiplayer_kick_title,
        ),
        message = { seat ->
            val name = lobby.players.getOrNull(seat)?.name
                ?: stringResource(R.string.multiplayer_kick_unnamed)
            stringResource(
                if (kickingAi) R.string.multiplayer_remove_ai_message else R.string.multiplayer_kick_message,
                name,
            )
        },
        confirmLabel = stringResource(
            if (kickingAi) R.string.multiplayer_remove_ai else R.string.multiplayer_kick_player,
        ),
        onConfirm = onKickPlayer,
        onDismiss = { pendingKick = null },
    )

    Row(modifier = Modifier.fillMaxSize()) {
        // Left pane (70%): track info, modifier indicators, and the player list, all as one
        // scrollable list. This used to be a fixed header and footer wrapped around just the
        // seat list scrolling on its own - which could clip the header (now three lines, since
        // it grew a settings summary) or the empty-seat notice on a short landscape screen.
        LazyColumn(
            modifier = Modifier
                .weight(LOBBY_LEFT_WEIGHT)
                .fillMaxHeight()
                .padding(end = 8.dp),
        ) {
            item {
                // Track name, laps, and modifier indicators on one line
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(lobby.track.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.multiplayer_laps, lobby.laps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Max players and the turn timeout used to be fixed for the life of the
                        // room and invisible here; now the host can change both from this same
                        // screen (see the settings dialog below), so every viewer - not just the
                        // host opening it - needs to see the current values at a glance.
                        Text(
                            stringResource(
                                R.string.multiplayer_room_settings_summary,
                                lobby.maxPlayers,
                                lobby.turnTimeoutSeconds,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (gravelSupported) {
                            Text(
                                stringResource(if (lobby.gravelTrap) R.string.lobby_gravel_on else R.string.lobby_gravel_off),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ModifierInfo(track = lobby.track)
                }
                if (isHost && !readOnly) {
                    TextButton(onClick = { showTrackPicker = true }) {
                        Text(stringResource(R.string.multiplayer_race_settings))
                    }
                }
                if (readOnly) {
                    Text(stringResource(R.string.multiplayer_read_only), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(12.dp))
            }

            if (tournament != null) {
                item(key = "tournament-info") {
                    TournamentInfoHeader(tournament)
                }
            }

            // Player list with "Ready?" header and aligned checkboxes
            item {
                LobbyTableRow(
                    modifier = Modifier.padding(bottom = 4.dp, start = 12.dp, end = 12.dp),
                    showActionColumn = isHost && !readOnly,
                    nameSlot = {
                        Text(
                            stringResource(R.string.multiplayer_players),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                    },
                    readySlot = {
                        Text(
                            stringResource(R.string.multiplayer_ready_header),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
            items(seatCount(lobby)) { seat ->
                SeatRow(
                    seat = LobbySeat.of(lobby, seat),
                    isLocal = seat == localPlayerId,
                    canManage = isHost && !readOnly,
                    readOnly = readOnly,
                    onReady = onReady,
                    onKick = { pendingKick = seat },
                    onAddAi = onAddAiPlayer,
                    showLevel = aiLevelsSupported,
                    onChangeLevel = { level -> onChangeAiLevel(seat, level) },
                )
                Spacer(Modifier.height(8.dp))
            }

            // What an empty seat means now that nothing fills it automatically: it simply is
            // not raced. Said out loud because the previous behaviour was the opposite, and
            // because a host looking at three empty seats needs to know Start is still allowed.
            val emptySeats = seatCount(lobby) - lobby.players.size
            if (emptySeats > 0) {
                item {
                    Text(
                        if (isHost && !readOnly) {
                            pluralStringResource(R.plurals.multiplayer_empty_seat_notice_host, emptySeats, emptySeats)
                        } else {
                            pluralStringResource(R.plurals.multiplayer_empty_seat_notice, emptySeats, emptySeats)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 12.dp),
                    )
                }
            }
        }

        VerticalDivider()

        // Right pane (30%): the room code at the top, actions at the bottom. This pane used to
        // hold nothing but two buttons pinned to the bottom, with the code shown nowhere at
        // all - so hosting a room and inviting somebody to it was impossible from inside the
        // app. The code is the single most important thing on this screen for a host.
        Column(
            modifier = Modifier
                .weight(LOBBY_RIGHT_WEIGHT)
                .fillMaxHeight()
                .padding(start = 8.dp),
        ) {
            RoomCodeCard(roomCode)

            Spacer(Modifier.weight(1f))

            if (isHost && !readOnly) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.multiplayer_start_race))
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.multiplayer_leave))
            }
        }
    }

    if (showTrackPicker) {
        RaceSettingsDialog(
            lobby = lobby,
            tracks = allTracks,
            allowTrackChange = (tournament == null),
            onDismiss = { showTrackPicker = false },
            onSelectTrack = onChangeTrack,
            onChangeLaps = onChangeLaps,
            onChangeMaxPlayers = onChangeMaxPlayers,
            onChangeTurnTimeoutSeconds = onChangeTurnTimeoutSeconds,
            gravelSupported = gravelSupported,
            onChangeGravelTrap = onChangeGravelTrap,
        )
    }
}

@Composable
private fun TournamentInfoHeader(tournament: info.piepgras.gridracer.Tournament) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.lobby_tournament_header, tournament.tracks.size),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            // Show track itinerary
            tournament.tracks.forEachIndexed { index, track ->
                val prefix = if (index == tournament.currentTrackIndex) "▶ " else "  "
                Text(
                    "$prefix${index + 1}. ${track.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (index == tournament.currentTrackIndex) {
                        androidx.compose.ui.text.font.FontWeight.Bold
                    } else {
                        androidx.compose.ui.text.font.FontWeight.Normal
                    },
                )
            }
            // Show standings if any races completed
            if (tournament.standings.any { it.points > 0 }) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tournament_standings_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                tournament.standings.sortedByDescending { it.points }.forEach { standing ->
                    Text(
                        stringResource(R.string.lobby_standings_row, standing.name, standing.points),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * How many rows the lobby draws: one per seat the room has, filled or not.
 *
 * A room's size is fixed when it is hosted, so a four-seat room shows four rows from the
 * moment it opens. It used to draw only the seats that were taken, so a host who had just
 * opened a room for four saw a single row and no indication that anyone else could join -
 * or that the rest would be filled with AI. `coerceAtLeast` guards the case where a server
 * predating `maxPlayers` on this message sends 0.
 */
private fun seatCount(lobby: ServerMessage.LobbyUpdate): Int =
    lobby.maxPlayers.coerceAtLeast(lobby.players.size)

/**
 * One seat in the lobby, resolved from the roster into the four states it can actually be in.
 *
 * The distinction that matters is between **empty** and **disconnected**: an empty seat is one
 * nobody has taken (and that an AI will fill at the start), while a disconnected seat belongs
 * to a player whose connection dropped and who is expected back. They look the same in a bare
 * roster and mean completely different things to a host deciding whether to wait.
 */
private sealed interface LobbySeat {
    val index: Int

    /** Nobody has taken this seat: a player can join into it, or the host can seat an AI driver. */
    data class Empty(override val index: Int) : LobbySeat

    /** A human is here. [connected] is false while they are dropped but holding the seat. */
    data class Human(
        override val index: Int,
        val name: String,
        val ready: Boolean,
        val connected: Boolean,
        val isHost: Boolean,
    ) : LobbySeat

    /** An AI driver the host has seated at [level]; the host can change its level or remove it again. */
    data class Ai(override val index: Int, val name: String, val level: AiLevel) : LobbySeat

    companion object {
        fun of(lobby: ServerMessage.LobbyUpdate, index: Int): LobbySeat {
            val config = lobby.players.getOrNull(index) ?: return Empty(index)
            if (config.isAi) return Ai(index, config.name, config.aiLevel)
            return Human(
                index = index,
                name = config.name,
                ready = lobby.ready.getOrNull(index) ?: false,
                // A server predating this field sends an empty list; assume connected rather
                // than showing every player as dropped.
                connected = lobby.connected.getOrNull(index) ?: true,
                isHost = index == lobby.hostPlayerId,
            )
        }
    }
}

/**
 * The three-column shape shared by the header row and every [SeatRow]: a flexible name/label
 * slot, a fixed-width ready-column slot, and an optional fixed-width host-action slot.
 *
 * The header and each row used to build this by hand, independently, which worked only as long
 * as both remembered to apply the same two width constants. Routing both through here means a
 * column added to one cannot be forgotten on the other.
 */
@Composable
private fun LobbyTableRow(
    modifier: Modifier = Modifier,
    showActionColumn: Boolean,
    nameSlot: @Composable RowScope.() -> Unit,
    readySlot: @Composable () -> Unit,
    actionSlot: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        nameSlot()
        // Centered, not the Box default of TopStart: exactly one row per viewer renders an
        // enabled, interactive Checkbox (the viewer's own seat) while every other row -
        // including, from a guest's screen, the host's own row - renders a disabled one.
        // Material3 does not guarantee an enabled and a disabled Checkbox measure to the same
        // width (the interactive one carries touch-target/ripple sizing the disabled one does
        // not), so at TopStart the two could sit at different left edges inside this same
        // fixed-width column - the viewer's own row looking shifted relative to everyone
        // else's. Centering makes the visible glyph land in the same spot regardless of the
        // control's own intrinsic size.
        Box(modifier = Modifier.width(CHECKBOX_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
            readySlot()
        }
        if (showActionColumn) {
            Box(
                modifier = Modifier.width(HOST_ACTION_COLUMN_WIDTH),
                contentAlignment = Alignment.CenterEnd,
            ) {
                actionSlot()
            }
        }
    }
}

/**
 * One row of the lobby table.
 *
 * Every seat gets a row whatever its state, and the columns line up across all of them - the
 * ready checkbox and the kick button keep their width even where they do not apply, so the
 * table does not reflow as players come and go.
 */
@Composable
private fun SeatRow(
    seat: LobbySeat,
    isLocal: Boolean,
    canManage: Boolean,
    readOnly: Boolean,
    onReady: (Boolean) -> Unit,
    onKick: () -> Unit,
    onAddAi: () -> Unit,
    showLevel: Boolean,
    onChangeLevel: (AiLevel) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        LobbyTableRow(
            modifier = Modifier.padding(12.dp),
            showActionColumn = canManage,
            nameSlot = {
                Column(modifier = Modifier.weight(1f)) {
                    when (seat) {
                        is LobbySeat.Empty -> Text(
                            stringResource(R.string.multiplayer_seat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is LobbySeat.Ai -> {
                            // Name on top, kind underneath - the same shape as a human row's
                            // "Host" badge. The server names these seats "AI 1", "AI 2", so
                            // appending "(AI)" to the name read as "AI 2 (AI)".
                            Text(seat.name, style = MaterialTheme.typography.bodyMedium)
                            if (showLevel) {
                                AiLevelLabel(level = seat.level, canChange = canManage, onChange = onChangeLevel)
                            } else {
                                Text(
                                    stringResource(R.string.multiplayer_seat_ai),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is LobbySeat.Human -> {
                            Text(
                                if (isLocal) stringResource(R.string.multiplayer_seat_you, seat.name) else seat.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (seat.connected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            // The host badge and the "not connected" note are the two things a
                            // player looks for here: who can start the race, and who is actually
                            // still in the room.
                            val note = buildList {
                                if (seat.isHost) add(stringResource(R.string.multiplayer_seat_host))
                                if (!seat.connected) add(stringResource(R.string.multiplayer_seat_disconnected))
                            }
                            if (note.isNotEmpty()) {
                                Text(
                                    note.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (seat.connected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                    }
                }
            },
            // Ready column. Always holds a Checkbox - even an empty seat gets a disabled,
            // unchecked one rather than nothing. A bare empty Box has no height, so next to
            // occupied rows (stretched to the Checkbox's ~48dp touch target) an empty seat's
            // row used to collapse to just its one line of text, breaking the column into a
            // ragged line instead of a straight one.
            readySlot = {
                when (seat) {
                    is LobbySeat.Empty ->
                        Checkbox(checked = false, onCheckedChange = null, enabled = false)
                    is LobbySeat.Ai ->
                        Checkbox(checked = true, onCheckedChange = null, enabled = false)
                    is LobbySeat.Human -> if (isLocal && !readOnly) {
                        Checkbox(checked = seat.ready, onCheckedChange = onReady)
                    } else {
                        Checkbox(checked = seat.ready, onCheckedChange = null, enabled = false)
                    }
                }
            },
            actionSlot = {
                when {
                    // An empty seat is an invitation: fill it with an AI driver, or leave
                    // it and race one car short.
                    seat is LobbySeat.Empty -> TextButton(onClick = onAddAi) {
                        Text(stringResource(R.string.multiplayer_add_ai))
                    }
                    // Anyone but the host can be removed - AI included, since the host put
                    // it there and needs to be able to take it back out. Confirms first:
                    // this is a one-tap icon next to a checkbox.
                    !isLocal -> IconButton(onClick = onKick) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(
                                if (seat is LobbySeat.Ai) {
                                    R.string.multiplayer_remove_ai
                                } else {
                                    R.string.multiplayer_kick_player
                                },
                            ),
                        )
                    }
                    else -> Unit
                }
            },
        )
    }
}

/**
 * "Computer · Normal" under an AI seat's name. For the host it opens a menu of the levels, the same
 * three the local setup offers; everyone else just reads it.
 */
@Composable
private fun AiLevelLabel(level: AiLevel, canChange: Boolean, onChange: (AiLevel) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val text = stringResource(R.string.multiplayer_seat_ai_level, stringResource(level.labelRes))
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (canChange) Modifier.clickable(onClickLabel = stringResource(R.string.multiplayer_change_ai_level)) { menuOpen = true } else Modifier,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (canChange) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canChange) {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            AiLevel.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_level_option, stringResource(option.labelRes), stringResource(option.descriptionRes)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = {
                        if (option == level) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        menuOpen = false
                        if (option != level) onChange(option)
                    },
                )
            }
        }
    }
}

/**
 * The room code, big enough to read aloud, with copy and share.
 *
 * The code is how anybody else gets into this room, and it was displayed nowhere - a host had
 * no way to find out what to tell their friend. It is deliberately monospaced and spaced out:
 * these codes get read over the phone, and the alphabet already excludes 0 and 1 for the same
 * reason (see `Validation.ROOM_CODE_ALPHABET`).
 */
@Composable
private fun RoomCodeCard(roomCode: String?) {
    if (roomCode.isNullOrBlank()) return
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.multiplayer_room_code_copied)
    val shareMessage = stringResource(R.string.multiplayer_room_code_share, roomCode)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.multiplayer_room_code),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                roomCode.toCharArray().joinToString(" "),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    // The platform clipboard service rather than Compose's
                    // LocalClipboardManager, which is deprecated in favour of a suspend API
                    // this does not need.
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(roomCode, roomCode))
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.action_copy))
                }
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareMessage)
                    }
                    context.startActivity(Intent.createChooser(share, null))
                }) {
                    Text(stringResource(R.string.action_share))
                }
            }
        }
    }
}

/**
 * Host-only lobby dialog: track (with direction/hazards), lap count, seat count, and per-turn
 * timeout, all in one place, all applied immediately on change - there is no separate "Apply"
 * step, matching every other lobby control ([SetReady], [ChangeTrack], [AddAiPlayer]).
 *
 * Bounded height rather than an unbounded list: the app is landscape-locked, so an unbounded
 * `text` slot pushes the dialog's own button off the bottom of a short viewport. The whole body
 * scrolls as one plain [Column] - the track list is rendered eagerly with `tracks.forEach`
 * rather than as a nested [LazyColumn]. A `LazyColumn` inside a `verticalScroll` `Column` looks
 * like the standard "bounded lazy list in a scrolling parent" pattern and compiles without
 * complaint, but it does not compose the way that pattern implies: the inner list ends up
 * capturing scroll gestures for itself, so everything below it in the outer column - here, the
 * hazards toggle and every chip row - becomes permanently unreachable rather than merely
 * scrolled out of view. The track list is short enough (built-ins plus a handful of custom
 * tracks) that eager composition costs nothing, and it's the only thing here that ever grows.
 */
@Composable
private fun RaceSettingsDialog(
    lobby: ServerMessage.LobbyUpdate,
    tracks: List<Track>,
    allowTrackChange: Boolean = true,
    onDismiss: () -> Unit,
    onSelectTrack: (Track) -> Unit,
    onChangeLaps: (Int) -> Unit,
    onChangeMaxPlayers: (Int) -> Unit,
    onChangeTurnTimeoutSeconds: (Int) -> Unit,
    gravelSupported: Boolean,
    onChangeGravelTrap: (Boolean) -> Unit,
) {
    // Hazards are re-derived from the catalog track every time rather than remembered, so
    // toggling back on restores the track's real hazard set instead of whatever was left over
    // from a previous toggle - the same lookup-by-name `ModifierInfo` already relies on (and
    // the same caveat: a custom track sharing a built-in's name is compared to the wrong one).
    val canonicalTrack = tracks.find { it.name == lobby.track.name } ?: lobby.track
    val hazardsOn = lobby.track.modifiers.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multiplayer_race_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = RACE_SETTINGS_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (allowTrackChange) {
                    SettingsSectionLabel(stringResource(R.string.setup_track_label))
                    tracks.forEach { track ->
                        TextButton(
                            onClick = { onSelectTrack(track) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                track.name,
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = if (track.name == lobby.track.name) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.setup_modifiers_label),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = hazardsOn,
                            onCheckedChange = { on ->
                                onSelectTrack(
                                    canonicalTrack.copy(
                                        modifiers = if (on) canonicalTrack.modifiers else emptyList(),
                                    ),
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    SettingsSectionLabel(stringResource(R.string.setup_laps_label))
                    var customLapsActive by remember { mutableStateOf(lobby.laps !in LAP_CHOICES) }
                    var customLapsText by remember { mutableStateOf(if (lobby.laps !in LAP_CHOICES) lobby.laps.toString() else "") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LAP_CHOICES.forEach { n ->
                            SettingsChip(
                                selected = lobby.laps == n && !customLapsActive,
                                onClick = {
                                    customLapsActive = false
                                    onChangeLaps(n)
                                },
                                label = n.toString()
                            )
                        }
                        SettingsChip(
                            selected = customLapsActive,
                            onClick = {
                                customLapsActive = true
                                if (customLapsText.isBlank()) customLapsText = lobby.laps.toString()
                                val parsed = customLapsText.toIntOrNull()?.coerceIn(LAP_RANGE) ?: 1
                                onChangeLaps(parsed)
                            },
                            label = stringResource(R.string.setup_laps_custom)
                        )
                    }
                    if (customLapsActive) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customLapsText,
                            onValueChange = { typed ->
                                val filtered = typed.filter { it.isDigit() }.take(2)
                                customLapsText = filtered
                                val parsed = filtered.toIntOrNull()?.coerceIn(LAP_RANGE) ?: 1
                                onChangeLaps(parsed)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                            singleLine = true
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                }
                SettingsSectionLabel(stringResource(R.string.multiplayer_max_players))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (MIN_PLAYERS..MAX_PLAYERS).forEach { n ->
                        SettingsChip(
                            selected = lobby.maxPlayers == n,
                            onClick = { onChangeMaxPlayers(n) },
                            label = n.toString(),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                SettingsSectionLabel(stringResource(R.string.multiplayer_turn_timeout_label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TURN_TIMEOUT_CHOICES_SECONDS.forEach { seconds ->
                        SettingsChip(
                            selected = lobby.turnTimeoutSeconds == seconds,
                            onClick = { onChangeTurnTimeoutSeconds(seconds) },
                            label = stringResource(R.string.multiplayer_turn_timeout_seconds, seconds),
                        )
                    }
                }

                // For every race the room runs, tournaments included - unlike the track, it is a
                // rule rather than a place, so it is not tied to allowTrackChange.
                if (gravelSupported) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setup_gravel_label), style = MaterialTheme.typography.labelLarge)
                            Text(
                                stringResource(R.string.setup_gravel_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = lobby.gravelTrap, onCheckedChange = onChangeGravelTrap)
                    }
                }
            }
        },
        // Material3 renders an empty confirmButton as a gap; the single action belongs here.
        // "OK" rather than "Cancel": every control above already applied on tap, there is
        // nothing left to discard by dismissing.
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}

/** Chip choices for the per-turn timeout, seconds. Bounded by `Limits.MIN/MAX_TURN_TIMEOUT_SECONDS`. */
private val TURN_TIMEOUT_CHOICES_SECONDS = listOf(5, 10, 20)

/** Tall enough for every section to be visible with a little scrolling room to spare. */
private val RACE_SETTINGS_MAX_HEIGHT = 320.dp

/**
 * The identity of a row in the track picker: its **position**, never its name.
 *
 * A `Track` has no id - `CustomTrack` has one, but the picker is handed a flat
 * `Tracks.all + customTracks.map { it.track }`, which has thrown the ids away by then. Keying
 * on `track.name` looks obvious and is wrong: names are typed by the user and nothing makes
 * them unique, so a player with two custom tracks both called "mine" crashed the dialog the
 * moment it scrolled, with `IllegalArgumentException: Key "mine" was already used`.
 *
 * Position is a legitimate key here because the list is a static snapshot for as long as the
 * dialog is open - it cannot reorder underneath the user. Named rather than inlined so the
 * property can be asserted; see `TrackPickerKeyTest`.
 */
internal fun trackPickerKey(index: Int, track: Track): Any = index

/**
 * Shows the race modifier indicators (direction and hazards) based on the track's
 * finish direction and modifier list. Direction is determined by comparing the track's
 * finishDirection against the original built-in track of the same name.
 *
 * The name lookup below is the same name-as-identity shortcut that
 * [trackPickerKey] exists to avoid, and it is wrong in the same way: a *custom* track named
 * "Oval" is compared against the built-in Oval's finish direction. It misreports an arrow, not
 * a crash, so it is left alone here - but `Track` growing a real id would fix both.
 */
@Composable
private fun ModifierInfo(track: Track) {
    val original = Tracks.all.find { it.name == track.name }
    val isReversed = original != null && track.finishDirection != original.finishDirection
    val hazardsOn = track.modifiers.isNotEmpty()

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (isReversed) {
                stringResource(R.string.setup_direction_reversed)
            } else {
                stringResource(R.string.setup_direction_normal)
            },
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            if (hazardsOn) {
                stringResource(R.string.lobby_hazards_on)
            } else {
                stringResource(R.string.lobby_hazards_off)
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun RemotePlacement(
    placement: PlacementState,
    localPlayerId: Int,
    onChooseSlot: (Point) -> Unit,
    onQuit: () -> Unit,
) {
    val myTurn = placement.currentPlayerIndex == localPlayerId
    PlacementScreen(
        placement = placement,
        onChooseSlot = onChooseSlot,
        onQuit = onQuit,
        enabled = myTurn,
    )
}

/** Android Studio preview of a lobby, seen by the host. */
@Preview(showBackground = true)
@Composable
fun OnlineRoomLobbyPreview() {
    MaterialTheme {
        OnlineRoomContent(
            multiplayerState = MultiplayerState.Lobby(
                lobby = ServerMessage.LobbyUpdate(
                    players = listOf(
                        PlayerConfig("Alice", isAi = false),
                        PlayerConfig("Bob", isAi = false),
                        PlayerConfig("Charlie", isAi = true),
                    ),
                    ready = listOf(true, false, false),
                    track = Tracks.OVAL,
                    laps = 3,
                    maxPlayers = 4,
                    hostPlayerId = 0,
                    // Bob has dropped and is expected back; the fourth seat is simply empty.
                    connected = listOf(true, false, true),
                ),
                localPlayerId = 0,
                isHost = true,
                readOnly = false,
            ),
            error = null,
            allTracks = Tracks.all,
            roomCode = "AB3XK",
            tournament = null,
            onClearError = {},
            onReady = {},
            onStart = {},
            onLeave = {},
            onChooseSlot = {},
            onKickPlayer = {},
            onAddAiPlayer = {},
            onChangeTrack = {},
            onChangeLaps = {},
            onChangeMaxPlayers = {},
            onChangeTurnTimeoutSeconds = {},
        ) {}
    }
}

/** Android Studio preview of the online placement phase. */
@Preview(showBackground = true)
@Composable
fun OnlineRoomPlacementPreview() {
    MaterialTheme {
        OnlineRoomContent(
            multiplayerState = MultiplayerState.Placement(
                placement = PlacementState(
                    track = Tracks.OVAL,
                    configs = listOf(
                        PlayerConfig("Alice", isAi = false),
                        PlayerConfig("Bob", isAi = false),
                    ),
                    order = listOf(0, 1),
                    currentOrderIndex = 0,
                    assignments = emptyMap(),
                ),
                laps = 3,
                localPlayerId = 0,
            ),
            error = null,
            allTracks = Tracks.all,
            roomCode = "AB3XK",
            tournament = null,
            onClearError = {},
            onReady = {},
            onStart = {},
            onLeave = {},
            onChooseSlot = {},
            onKickPlayer = {},
            onAddAiPlayer = {},
            onChangeTrack = {},
            onChangeLaps = {},
            onChangeMaxPlayers = {},
            onChangeTurnTimeoutSeconds = {},
        ) {}
    }
}

/** Android Studio preview of the screen before a room has been joined. */
@Preview(showBackground = true)
@Composable
fun OnlineRoomIdlePreview() {
    MaterialTheme {
        OnlineRoomContent(
            multiplayerState = MultiplayerState.Idle,
            error = null,
            allTracks = Tracks.all,
            roomCode = "AB3XK",
            tournament = null,
            onClearError = {},
            onReady = {},
            onStart = {},
            onLeave = {},
            onChooseSlot = {},
            onKickPlayer = {},
            onAddAiPlayer = {},
            onChangeTrack = {},
            onChangeLaps = {},
            onChangeMaxPlayers = {},
            onChangeTurnTimeoutSeconds = {},
        ) {}
    }
}
