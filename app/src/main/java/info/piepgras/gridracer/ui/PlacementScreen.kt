package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlacementState
import info.piepgras.gridracer.engine.PlayerState
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.Point

/**
 * Pre-race "pick your starting slot" screen: reuses [TrackCanvas] by building a
 * synthetic [GameState] where already-placed players sit at their chosen slot and
 * everyone else sits off-track (at (-1,-1)) until it's their turn. Tapping an
 * available slot works exactly like a move-tap does during the race itself.
 *
 * The canvas fills the entire screen (same as the race screen); the prompt and close
 * button are overlaid on top so they never eat into the playable area.
 */
@Composable
fun PlacementScreen(
    placement: PlacementState,
    onChooseSlot: (Point) -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = placement.configs.getOrNull(placement.currentPlayerIndex)?.isAi?.not() == true,
) {
    BackHandler(onBack = onQuit)
    val currentConfig = placement.configs.getOrNull(placement.currentPlayerIndex)
    val currentIsAi = currentConfig?.isAi == true

    val syntheticState = remember(placement) {
        GameState(
            track = placement.track,
            players = placement.configs.indices.map { i ->
                val slot = placement.assignments[i]
                // A player who has not picked yet is not on the track. Parking them on a point
                // just off the grid used to hide them, but the gravel band now draws that point;
                // a car that has left the track (FINISHED) is never drawn anywhere.
                if (slot != null) {
                    PlayerState(id = i, position = slot)
                } else {
                    PlayerState(id = i, position = Point(-1, -1), status = PlayerStatus.FINISHED)
                }
            },
            currentPlayerIndex = placement.currentPlayerIndex,
        )
    }
    val legalMoves = if (!enabled || currentIsAi) emptySet() else placement.availableSlots.toSet()

    Box(modifier = modifier.fillMaxSize()) {
        // The whole screen is the track; the prompt and close button overlay it.
        TrackCanvas(
            state = syntheticState,
            legalMoves = legalMoves,
            onPointTapped = onChooseSlot,
            modifier = Modifier.fillMaxSize(),
            showStartZone = true,
        )

        // Top-center prompt, overlaid on the track. Capped width so a long name wraps
        // instead of running off the screen.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 16.dp)
                .width(220.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val label = if (currentIsAi) {
                stringResource(R.string.placement_choosing_ai, currentConfig?.name ?: "")
            } else {
                stringResource(R.string.placement_choose_prompt, currentConfig?.name ?: "")
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = PlayerColors[placement.currentPlayerIndex % PlayerColors.size],
                textAlign = TextAlign.Center,
            )
        }

        // Top-right close button: an "x" on a grey square background, matching the race screen.
        IconButton(
            onClick = onQuit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(8.dp)
                .size(63.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Gray.copy(alpha = 0.47f)),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.race_quit))
        }
    }
}
