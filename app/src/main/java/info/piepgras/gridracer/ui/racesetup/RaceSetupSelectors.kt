package info.piepgras.gridracer.ui.racesetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.MAX_TOURNAMENT_TRACKS
import info.piepgras.gridracer.MIN_TOURNAMENT_TRACKS
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.reversed
import info.piepgras.gridracer.ui.PlayerSlotRow
import info.piepgras.gridracer.ui.SetupDimens
import info.piepgras.gridracer.ui.TrackPickerCard

/** The mode and format chips - the head of the selector pane's list. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RaceSetupModeChips(
    state: RaceSetupState,
    onUpdate: ((RaceSetupState) -> RaceSetupState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionLabel(stringResource(R.string.setup_mode_label))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SetupMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { onUpdate { it.copy(mode = mode) } },
                    label = { Text(stringResource(mode.labelRes)) },
                )
            }
        }

        // Joiners and spectators are handed a format by the server.
        if (state.mode.choosesFormat) {
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.setup_format_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SetupFormat.entries.forEach { format ->
                    FilterChip(
                        selected = state.format == format,
                        onClick = { onUpdate { it.copy(format = format) } },
                        label = { Text(stringResource(format.labelRes)) },
                    )
                }
            }
        }
    }
}

/**
 * Everything below the mode chips for the modes that describe a race: which track(s), who is
 * driving (or how many seats the room offers), and how many laps.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RaceSetupSelectors(
    state: RaceSetupState,
    plan: RaceSetupPlan,
    allTracks: List<Track>,
    onUpdate: ((RaceSetupState) -> RaceSetupState) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the gravel trap can be offered: always locally, online only on a server that has it. */
    gravelAvailable: Boolean = true,
) {
    val tournament = state.mode.choosesFormat && state.format == SetupFormat.TOURNAMENT
    val picksTrack = state.mode.picksTrack

    Column(modifier = modifier) {
        if (picksTrack) {
            if (tournament) {
                TrackItinerarySection(state, allTracks, onUpdate)
            } else {
                SingleTrackSection(state, plan, allTracks, onUpdate)
            }
            Spacer(Modifier.height(SetupDimens.SectionGap))
        }

        if (state.mode.configuresField) {
            // All four seats, always. Emptying one is what makes it a smaller race, so this
            // section is both the field and the field-size control.
            SectionLabel(stringResource(R.string.setup_players_label))
            for (i in 0 until MAX_PLAYERS) {
                val slot = plan.slots.getOrElse(i) { PlayerSlot.NONE }
                PlayerSlotRow(
                    index = i,
                    raceIndex = plan.raceIndexOf(i),
                    name = state.names.getOrElse(i) { defaultPlayerName(i) },
                    slot = slot,
                    aiLevel = state.aiLevels.getOrElse(i) { info.piepgras.gridracer.engine.AiLevel.NORMAL },
                    // Don't offer a seat this track can't hold, or let the field drop below
                    // the two cars a race needs - the chips just stop responding at the edges
                    // rather than letting the user build something the start button refuses.
                    canFill = plan.effectivePlayerCount < plan.seatCap,
                    canEmpty = plan.effectivePlayerCount > MIN_PLAYERS,
                    onNameChange = { typed ->
                        onUpdate { s -> s.copy(names = s.names.replaceAt(i, sanitizeName(typed))) }
                    },
                    onSlotChange = { picked ->
                        onUpdate { s -> s.copy(slots = plan.slots.replaceAt(i, picked)) }
                    },
                    onAiLevelChange = { level ->
                        onUpdate { s -> s.copy(aiLevels = s.aiLevels.replaceAt(i, level)) }
                    },
                )
                Spacer(Modifier.height(SetupDimens.RowGap))
            }
            Spacer(Modifier.height(SetupDimens.SectionGap))
        } else {
            // A hosted room is sized up front and the server fills whatever nobody claims.
            SectionLabel(stringResource(R.string.multiplayer_max_players))
            Row(horizontalArrangement = Arrangement.spacedBy(SetupDimens.RowGap)) {
                (MIN_PLAYERS..plan.seatCap).forEach { n ->
                    CompactChip(
                        selected = plan.effectivePlayerCount == n,
                        onClick = { onUpdate { it.copy(maxPlayers = n) } },
                        label = n.toString(),
                    )
                }
            }
            Spacer(Modifier.height(SetupDimens.SectionGap))
        }

        SectionLabel(stringResource(R.string.setup_laps_label))
        var customLapsActive by remember { mutableStateOf(state.effectiveLaps !in LAP_CHOICES) }
        var customLapsText by remember { mutableStateOf(if (state.effectiveLaps !in LAP_CHOICES) state.effectiveLaps.toString() else "") }
        Row(horizontalArrangement = Arrangement.spacedBy(SetupDimens.RowGap)) {
            LAP_CHOICES.forEach { n ->
                CompactChip(
                    selected = state.effectiveLaps == n && !customLapsActive,
                    onClick = {
                        customLapsActive = false
                        onUpdate { it.copy(laps = n) }
                    },
                    label = n.toString(),
                )
            }
            CompactChip(
                selected = customLapsActive,
                onClick = {
                    customLapsActive = true
                    if (customLapsText.isBlank()) customLapsText = state.effectiveLaps.toString()
                    val parsed = customLapsText.toIntOrNull()?.coerceIn(LAP_RANGE) ?: 1
                    onUpdate { it.copy(laps = parsed) }
                },
                label = stringResource(R.string.setup_laps_custom),
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
                    onUpdate { it.copy(laps = parsed) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(80.dp),
                singleLine = true
            )
        }

        if (gravelAvailable) {
            Spacer(Modifier.height(SetupDimens.SectionGap))
            GravelTrapRow(
                enabled = state.gravelTrap,
                onChange = { on -> onUpdate { it.copy(gravelTrap = on) } },
            )
        }
    }
}

/**
 * The gravel trap switch, with one line on what it changes. The band is drawn either way; the
 * switch decides whether running into it brakes a car or stops it like the wall beside it.
 */
@Composable
private fun GravelTrapRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel(stringResource(R.string.setup_gravel_label))
            Text(
                stringResource(R.string.setup_gravel_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row handles the toggle, so the switch itself does not take a second focus stop.
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactChip(selected: Boolean, onClick: () -> Unit, label: String, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SingleTrackSection(
    state: RaceSetupState,
    plan: RaceSetupPlan,
    allTracks: List<Track>,
    onUpdate: ((RaceSetupState) -> RaceSetupState) -> Unit,
) {
    val safeIndex = state.selectedTrackIndex.coerceIn(allTracks.indices)
    SectionLabel(stringResource(R.string.setup_track_label))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        allTracks.forEachIndexed { index, track ->
            val isSelected = index == safeIndex
            val isReversed = index in state.reversedTracks
            TrackPickerCard(
                // Only the selected card previews the chosen direction; the others show
                // their own orientation, which is what picking them would start with.
                track = if (isSelected) plan.track ?: track else track,
                reversed = isReversed,
                onReversedChange = { rev ->
                    onUpdate { s ->
                        s.copy(
                            selectedTrackIndex = index,
                            reversedTracks = if (rev) s.reversedTracks + index else s.reversedTracks - index,
                        )
                    }
                },
                hazardsEnabled = index !in state.hazardsDisabledTracks,
                onHazardsChange = { on ->
                    onUpdate { s ->
                        s.copy(
                            selectedTrackIndex = index,
                            hazardsDisabledTracks = if (on) s.hazardsDisabledTracks - index else s.hazardsDisabledTracks + index,
                        )
                    }
                },
                onClick = { onUpdate { it.copy(selectedTrackIndex = index) } },
                selected = isSelected,
                enabled = track.startPositions.size >= MIN_PLAYERS,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TrackItinerarySection(
    state: RaceSetupState,
    allTracks: List<Track>,
    onUpdate: ((RaceSetupState) -> RaceSetupState) -> Unit,
) {
    SectionLabel(stringResource(R.string.tournament_available_tracks_label))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        allTracks.forEachIndexed { index, track ->
            // Direction and hazards are per card here: the itinerary stores tracks already
            // oriented and with hazards baked in, so each entry keeps whichever way round and
            // whichever hazard state it was added with.
            val isSelected = index == state.selectedTrackIndex
            val isReversed = index in state.reversedTracks
            val hazardsOn = index !in state.hazardsDisabledTracks
            val oriented = if (isReversed) track.reversed() else track
            TrackPickerCard(
                track = oriented,
                reversed = isReversed,
                onReversedChange = { rev ->
                    onUpdate { s ->
                        s.copy(
                            selectedTrackIndex = index,
                            reversedTracks = if (rev) s.reversedTracks + index else s.reversedTracks - index,
                        )
                    }
                },
                hazardsEnabled = hazardsOn,
                onHazardsChange = { on ->
                    onUpdate { s ->
                        s.copy(
                            selectedTrackIndex = index,
                            hazardsDisabledTracks = if (on) s.hazardsDisabledTracks - index else s.hazardsDisabledTracks + index,
                        )
                    }
                },
                onClick = {
                    onUpdate { s ->
                        if (s.itinerary.size >= MAX_TOURNAMENT_TRACKS) return@onUpdate s
                        // Bake both direction and hazard state into the itinerary entry.
                        val keepReversed = s.selectedTrackIndex == index && index in s.reversedTracks
                        val keepHazardsOff = s.selectedTrackIndex == index && index in s.hazardsDisabledTracks
                        val entry = if (keepReversed) track.reversed() else track
                        val finalEntry = if (keepHazardsOff) entry.copy(modifiers = emptyList()) else entry
                        s.copy(
                            selectedTrackIndex = index,
                            itinerary = s.itinerary + finalEntry,
                        )
                    }
                },
                selected = isSelected,
                enabled = oriented.startPositions.size >= MIN_PLAYERS,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(
            R.string.tournament_itinerary_label,
            state.itinerary.size,
            MIN_TOURNAMENT_TRACKS,
            MAX_TOURNAMENT_TRACKS,
        ),
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.itinerary.forEachIndexed { index, track ->
            val suffix = if (isReversedFrom(track, allTracks)) {
                " (${stringResource(R.string.setup_direction_reversed)})"
            } else {
                ""
            }
            AssistChip(
                onClick = {
                    onUpdate { s ->
                        s.copy(itinerary = s.itinerary.filterIndexed { i, _ -> i != index })
                    }
                },
                label = { Text("${index + 1}. ${track.name}$suffix ✕") },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
}

private val SetupMode.labelRes: Int
    get() = when (this) {
        SetupMode.LOCAL -> R.string.setup_mode_local
        SetupMode.HOST -> R.string.setup_mode_host
        SetupMode.JOIN -> R.string.setup_mode_join
        SetupMode.SPECTATE -> R.string.multiplayer_spectate
    }

private val SetupFormat.labelRes: Int
    get() = when (this) {
        SetupFormat.SINGLE_RACE -> R.string.race_mode_single
        SetupFormat.TOURNAMENT -> R.string.race_mode_tournament
    }

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, existing -> if (i == index) value else existing }
