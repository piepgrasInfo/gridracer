package info.piepgras.gridracer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.ReplaySession

// A horizontal control bar overlaid at the bottom of the replay screen
/** The playback bar over a replay: play and pause, single steps, speed, and the move counter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayControls(
    currentIndex: Int,
    totalSteps: Int,
    playbackState: ReplaySession.PlaybackState,
    speedMultiplier: Float,
    onTogglePlayPause: () -> Unit,
    onStepForward: () -> Unit,
    onStepBackward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onStepBackward) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.replay_step_backward),
                        )
                    }
                    IconButton(onClick = onTogglePlayPause) {
                        if (playbackState == ReplaySession.PlaybackState.PLAYING) {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = stringResource(R.string.replay_pause),
                            )
                        } else {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.replay_play),
                            )
                        }
                    }
                    IconButton(onClick = onStepForward) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.replay_step_forward),
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))

                // Speeds
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(0.5f, 1f, 2f, 4f).forEach { speed ->
                        FilterChip(
                            selected = speedMultiplier == speed,
                            onClick = { onSpeedChange(speed) },
                            label = {
                                val factor = if (speed == 0.5f) "0.5" else speed.toInt().toString()
                                Text(stringResource(R.string.replay_speed_multiplier, factor))
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Step counter
                Text(
                    text = "$currentIndex / $totalSteps",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // Scrubbing Slider
            Slider(
                value = currentIndex.toFloat(),
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..(totalSteps.toFloat().coerceAtLeast(1f)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
