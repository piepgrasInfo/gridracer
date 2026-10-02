package info.piepgras.gridracer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.MultiplayerClient
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.ui.racesetup.PlayerSlot
import info.piepgras.gridracer.ui.racesetup.defaultPlayerName
import info.piepgras.gridracer.ui.racesetup.isRacing

/**
 * The compact metrics the setup screen is drawn at. Material's defaults are sized for a
 * portrait phone with one thing on screen; this screen is landscape and puts a whole form
 * beside a whole action panel, so every control runs a step smaller than stock.
 */
object SetupDimens {
    /** Material's default is 56dp; these fields are single-line and don't need the height. */
    val FieldHeight = 48.dp
    /** Height of a text field with a floating label. */
    val LabelledFieldHeight = 54.dp
    /** Width of a player-name field in the seat rows. */
    val NameFieldWidth = 132.dp
    /** Height of the screen's buttons. */
    val ButtonHeight = 40.dp
    /** Inner padding of the screen's buttons. */
    val ButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    /** Space between rows within a section. */
    val RowGap = 6.dp
    /** Space between sections. */
    val SectionGap = 12.dp
}

/**
 * One seat in a local race: colour tag, editable name, and who is driving it.
 *
 * All four seats are always shown - [PlayerSlot.NONE] is how a smaller field is chosen, so
 * this row is also the player-count control. A computer seat's chip names its level and opens
 * the level menu, so the row needs no fourth control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSlotRow(
    index: Int,
    raceIndex: Int,
    name: String,
    slot: PlayerSlot,
    aiLevel: AiLevel,
    canFill: Boolean,
    canEmpty: Boolean,
    onNameChange: (String) -> Unit,
    onSlotChange: (PlayerSlot) -> Unit,
    onAiLevelChange: (AiLevel) -> Unit,
) {
    // The placeholder the field is seeded with; clearing to this on focus lets the user
    // start typing their own name without first backspacing through "Player N". A blank
    // box still falls back to "Player N" at race start (see RaceSetupState.plan), so
    // clearing is safe.
    val default = defaultPlayerName(index)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SetupDimens.RowGap),
    ) {
        Text(
            // Numbered by the seat it will actually race from, not by its row: emptying a
            // seat moves the ones behind it up a place, and the colour moves with them.
            text = if (slot.isRacing) "P${raceIndex + 1}" else "–",
            color = if (slot.isRacing) {
                PlayerColors[raceIndex % PlayerColors.size]
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.width(24.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            enabled = slot.isRacing,
            textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodySmall),
            modifier = Modifier
                .width(SetupDimens.NameFieldWidth)
                .height(SetupDimens.FieldHeight)
                .onFocusChanged { state ->
                    if (state.isFocused && name == default) onNameChange("")
                },
        )
        SlotChip(R.string.setup_human, slot == PlayerSlot.HUMAN, canFill) { onSlotChange(PlayerSlot.HUMAN) }
        AiSlotChip(
            selected = slot == PlayerSlot.AI,
            enabled = canFill,
            level = aiLevel,
            onSelect = { onSlotChange(PlayerSlot.AI) },
            onLevelChange = onAiLevelChange,
        )
        SlotChip(R.string.setup_none, slot == PlayerSlot.NONE, canEmpty) { onSlotChange(PlayerSlot.NONE) }
    }
}

/**
 * The "computer" choice of a seat. Unselected it reads "AI" and takes the seat; selected it reads
 * "AI: Normal" (or the seat's level) and opens a menu of the levels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSlotChip(
    selected: Boolean,
    enabled: Boolean,
    level: AiLevel,
    onSelect: () -> Unit,
    onLevelChange: (AiLevel) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            enabled = enabled || selected,
            onClick = { if (selected) menuOpen = true else onSelect() },
            label = {
                Text(
                    if (selected) {
                        stringResource(R.string.setup_ai_with_level, stringResource(level.labelRes))
                    } else {
                        stringResource(R.string.setup_ai)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            trailingIcon = if (selected) {
                { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            AiLevel.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_level_option, stringResource(option.labelRes), stringResource(option.descriptionRes)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    // Every item keeps the icon's room, so the names line up whichever is ticked.
                    leadingIcon = {
                        if (option == level) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onLevelChange(option)
                    },
                )
            }
        }
    }
}

/** The name of a computer driver's level. */
val AiLevel.labelRes: Int
    get() = when (this) {
        AiLevel.EASY -> R.string.ai_level_easy
        AiLevel.NORMAL -> R.string.ai_level_normal
        AiLevel.BEST -> R.string.ai_level_best
    }

/** One line on how a computer driver of this level drives. */
val AiLevel.descriptionRes: Int
    get() = when (this) {
        AiLevel.EASY -> R.string.ai_level_easy_description
        AiLevel.NORMAL -> R.string.ai_level_normal_description
        AiLevel.BEST -> R.string.ai_level_best_description
    }

/** Disabled rather than hidden: the seat still exists, this particular field size just can't have it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotChip(labelRes: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        // Already-selected chips stay tappable-looking so the row doesn't flicker between
        // enabled states as the field size changes.
        enabled = enabled || selected,
        onClick = onClick,
        label = {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
        },
    )
}

/**
 * The badge appended to an AI driver's name in a results or standings row, or the empty
 * string for a human. One resource behind one helper, so the four screens that list drivers
 * cannot drift apart.
 */
@Composable
fun aiTag(isAi: Boolean): String = if (isAi) stringResource(R.string.player_ai_tag) else ""

/**
 * Turns whatever landed in the multiplayer error channel into display text.
 *
 * [MultiplayerClient] publishes a stable code for the failures the app itself raises, the
 * same way the server sends an `AuthRejection` name (see `ServerSection.friendlyAuthError`):
 * anything else - a message from the server, or an exception's own text - is passed through
 * untouched, because there is nothing to translate it against.
 */
@Composable
fun localizedMultiplayerError(message: String): String = when (message) {
    MultiplayerClient.ERROR_CONNECT -> stringResource(R.string.multiplayer_error_connect)
    MultiplayerClient.ERROR_ROOM_LIST -> stringResource(R.string.multiplayer_error_room_list)
    MultiplayerClient.ERROR_SEND -> stringResource(R.string.multiplayer_error_send)
    MultiplayerClient.ERROR_CONNECTION -> stringResource(R.string.multiplayer_error)
    MultiplayerClient.ERROR_PROTOCOL_TOO_OLD -> stringResource(R.string.multiplayer_error_protocol_too_old)
    else -> message
}

/** Surfaces a server or connection error. Shared by the setup screen and the online room. */
@Composable
fun MultiplayerErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_continue)) }
        },
        title = { Text(stringResource(R.string.error_title)) },
        text = { Text(localizedMultiplayerError(message)) },
    )
}
