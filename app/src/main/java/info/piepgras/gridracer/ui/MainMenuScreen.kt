package info.piepgras.gridracer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R

/**
 * Main menu: the app is landscape-locked, so the screen is split down the middle - the feature
 * graphic on the left, the buttons on the right. The graphic already carries the wordmark, which
 * is why there is no separate title here.
 *
 * "Start Race" is the largest button and sits on top, spanning exactly the width of the
 * two-column grid below it (2 × 150dp + 12dp gap = 312dp). `verticalScroll` stays on the button
 * column as a safety net for very short viewports.
 */
@Composable
fun MainMenuScreen(
    onSettings: () -> Unit,
    onStartRace: () -> Unit,
    onTracks: () -> Unit,
    onLeaderboard: () -> Unit,
    onHowToPlay: () -> Unit,
    onReplayBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.feature_graphic),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = onStartRace,
                modifier = Modifier
                    .width(BUTTON_GRID_WIDTH)
                    .height(56.dp),
            ) {
                Text(
                    stringResource(R.string.menu_start_race),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))

            // Remaining buttons in a 2-column grid below.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onTracks, modifier = Modifier.width(BUTTON_WIDTH)) { Text(stringResource(R.string.menu_tracks)) }
                Button(onClick = onLeaderboard, modifier = Modifier.width(BUTTON_WIDTH)) { Text(stringResource(R.string.menu_leaderboard)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onReplayBrowser, modifier = Modifier.width(BUTTON_WIDTH)) { Text(stringResource(R.string.menu_replay_browser)) }
                Button(onClick = onHowToPlay, modifier = Modifier.width(BUTTON_WIDTH)) { Text(stringResource(R.string.menu_how_to_play)) }
            }
            Spacer(Modifier.height(12.dp))
            // Settings spans the grid rather than sitting alone in a half-width slot with a
            // hole beside it, which read as a missing button.
            Button(
                onClick = onSettings,
                modifier = Modifier.width(BUTTON_GRID_WIDTH),
            ) {
                Text(stringResource(R.string.menu_settings))
            }
        }
    }
}

private val BUTTON_WIDTH = 150.dp
private val BUTTON_GRID_WIDTH = 312.dp

/** Android Studio preview of [MainMenuScreen]. */
@Preview(showBackground = true, widthDp = 800, heightDp = 380)
@Composable
fun MainMenuScreenPreview() {
    MaterialTheme {
        MainMenuScreen(
            onSettings = {},
            onStartRace = {},
            onTracks = {},
            onLeaderboard = {},
            onHowToPlay = {},
            onReplayBrowser = {},
        )
    }
}
