package info.piepgras.gridracer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.Track

/** Wide enough for a legible thumbnail plus the name row's two toggle buttons. */
private val CARD_WIDTH = 130.dp
private val TOGGLE_SIZE = 24.dp

/** U+21BB clockwise arrow - decorative; the real label is the toggle's contentDescription. */
private const val ARROW_GLYPH = "\u21bb"
private const val HAZARD_GLYPH = "\u26a1"

/**
 * The one track tile used by both race setup screens, so picking a track for a single
 * race and picking one for a tournament itinerary look and behave the same.
 *
 * Racing direction and hazard zones live here rather than in separate controls below the
 * list: the preview redraws with its direction arrow flipped as soon as it's toggled, so
 * the choice is made against the picture of the track it applies to. [track] is expected
 * to already be in the chosen orientation - see `Track.reversed()`.
 *
 * Each card carries its own direction and hazard toggle so every track in the picker keeps
 * its individual setting rather than sharing a single global state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackPickerCard(
    track: Track,
    reversed: Boolean,
    onReversedChange: (Boolean) -> Unit,
    hazardsEnabled: Boolean,
    onHazardsChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.width(CARD_WIDTH),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            TrackPreview(track, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            // Name and the two on/off toggles share one row. Each toggle is a single glyph:
            // filled = on, outlined = off. Direction: on = normal, off = reversed.
            // Hazards: on = modifiers active, off = stripped.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CardToggle(
                    glyph = ARROW_GLYPH,
                    contentDescription = stringResource(R.string.setup_direction_normal),
                    on = !reversed,
                    onClick = { onReversedChange(!reversed) },
                )
                CardToggle(
                    glyph = HAZARD_GLYPH,
                    contentDescription = stringResource(R.string.setup_modifiers_label),
                    on = hazardsEnabled,
                    onClick = { onHazardsChange(!hazardsEnabled) },
                )
            }
            if (!enabled) {
                Text(
                    stringResource(R.string.setup_track_not_raceable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One on/off toggle inside a track card. A bare clickable glyph rather than a [FilterChip]:
 * the chip's 32dp height and 12dp side padding are exactly what stopped the controls fitting
 * beside the track name. Filled/primary background = on; transparent = off.
 */
@Composable
private fun CardToggle(
    glyph: String,
    contentDescription: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(TOGGLE_SIZE)
            .clip(CircleShape)
            .background(
                if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            .toggleable(
                value = on,
                onValueChange = { onClick() },
                role = Role.Switch,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleMedium,
            color = if (on) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
