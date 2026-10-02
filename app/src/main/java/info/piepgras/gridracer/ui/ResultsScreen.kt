package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import androidx.compose.ui.tooling.preview.Preview
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.PlayerState
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Scoring
import info.piepgras.gridracer.engine.Tracks

/**
 * Results of a local single race: finishing order, crashes, points, and the way to a rematch or the
 * replay.
 */
@Composable
fun ResultsScreen(
    state: GameState,
    configs: List<PlayerConfig>,
    onRaceAgain: () -> Unit,
    onMainMenu: () -> Unit,
    onWatchReplay: () -> Unit = {},
    canWatchReplay: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onMainMenu)
    val finished = state.players.filter { it.status == PlayerStatus.FINISHED }.sortedBy { it.finishRank }
    val crashed = state.players.filter { it.status == PlayerStatus.CRASHED }
    // Players still RACING when the race ended hit the round cap (GameEngine.MAX_ROUNDS)
    // rather than finishing or crashing; they score 0 and are listed separately so the
    // timeout is communicated honestly rather than silently dropping them.
    val timedOut = state.players.filter { it.status == PlayerStatus.RACING }

    // Hoisted rather than taken from the composable aiTag() helper, because tagOf is a plain
    // local function; naming it aiTag as well would shadow that helper in this file.
    val aiSuffix = stringResource(R.string.player_ai_tag)
    fun nameOf(playerId: Int) = configs.getOrNull(playerId)?.name ?: "P${playerId + 1}"
    fun tagOf(playerId: Int) = if (configs.getOrNull(playerId)?.isAi == true) aiSuffix else ""

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.results_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        if (finished.isNotEmpty()) {
            Text(stringResource(R.string.results_finished_label), style = MaterialTheme.typography.titleMedium)
            finished.forEach { player ->
                val points = Scoring.pointsForRank(player.finishRank)
                Text(
                    text = stringResource(
                        R.string.results_rank_row,
                        player.finishRank ?: 0,
                        nameOf(player.id),
                        tagOf(player.id),
                        points,
                    ),
                    color = PlayerColors[player.id % PlayerColors.size],
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (crashed.isNotEmpty()) {
            Text(stringResource(R.string.results_crashed_label), style = MaterialTheme.typography.titleMedium)
            crashed.forEach { player ->
                Text(
                    text = stringResource(R.string.results_name_row, nameOf(player.id), tagOf(player.id)),
                    color = PlayerColors[player.id % PlayerColors.size],
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (timedOut.isNotEmpty()) {
            Text(stringResource(R.string.results_dnf_label), style = MaterialTheme.typography.titleMedium)
            timedOut.forEach { player ->
                Text(
                    text = stringResource(
                        R.string.results_no_points_row, nameOf(player.id), tagOf(player.id),
                    ),
                    color = PlayerColors[player.id % PlayerColors.size],
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Row {
            Button(onClick = onRaceAgain) { Text(stringResource(R.string.results_race_again)) }
            Spacer(Modifier.width(12.dp))
            if (canWatchReplay) {
                OutlinedButton(onClick = onWatchReplay) { Text(stringResource(R.string.results_watch_replay)) }
                Spacer(Modifier.width(12.dp))
            }
            OutlinedButton(onClick = onMainMenu) { Text(stringResource(R.string.action_main_menu)) }
        }
    }
}

/** Android Studio preview of [ResultsScreen]. */
@Preview(showBackground = true)
@Composable
fun ResultsScreenPreview() {
    val state = GameState(
        track = Tracks.OVAL,
        players = listOf(
            PlayerState(id = 0, position = Point(0, 0), status = PlayerStatus.FINISHED, finishRank = 1),
            PlayerState(id = 1, position = Point(0, 0), status = PlayerStatus.FINISHED, finishRank = 2),
            PlayerState(id = 2, position = Point(0, 0), status = PlayerStatus.CRASHED),
            PlayerState(id = 3, position = Point(0, 0), status = PlayerStatus.RACING),
        ),
        currentPlayerIndex = 0,
        phase = GamePhase.RACE_OVER
    )
    val configs = listOf(
        PlayerConfig(name = "Alice", isAi = false),
        PlayerConfig(name = "Bob", isAi = true),
        PlayerConfig(name = "Charlie", isAi = false),
        PlayerConfig(name = "Dave", isAi = true)
    )

    MaterialTheme {
        ResultsScreen(
            state = state,
            configs = configs,
            onRaceAgain = {},
            onMainMenu = {},
            onWatchReplay = {},
            canWatchReplay = true,
        )
    }
}
