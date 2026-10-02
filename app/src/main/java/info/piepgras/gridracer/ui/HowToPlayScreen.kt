package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.ui.howtoplay.ArrowDirection
import info.piepgras.gridracer.ui.howtoplay.CurvedArrow
import info.piepgras.gridracer.ui.howtoplay.FinishLineAnimation
import info.piepgras.gridracer.ui.howtoplay.FinishLineFrames
import info.piepgras.gridracer.ui.howtoplay.GravelTrapAnimation
import info.piepgras.gridracer.ui.howtoplay.GravelTrapFrames
import info.piepgras.gridracer.ui.howtoplay.NailsAnimation
import info.piepgras.gridracer.ui.howtoplay.NailsFrames
import info.piepgras.gridracer.ui.howtoplay.OilSpillAnimation
import info.piepgras.gridracer.ui.howtoplay.OilSpillFrames
import info.piepgras.gridracer.ui.howtoplay.VelocityRuleAnimation
import info.piepgras.gridracer.ui.howtoplay.VelocityRuleFrames
import info.piepgras.gridracer.ui.howtoplay.WallCrashAnimation
import info.piepgras.gridracer.ui.howtoplay.WallCrashFrames
import info.piepgras.gridracer.ui.howtoplay.finishLineCaption
import info.piepgras.gridracer.ui.howtoplay.gravelTrapCaption
import info.piepgras.gridracer.ui.howtoplay.nailsCaption
import info.piepgras.gridracer.ui.howtoplay.oilSpillCaption
import info.piepgras.gridracer.ui.howtoplay.velocityCaption
import info.piepgras.gridracer.ui.howtoplay.wallCrashCaption

/**
 * A self-contained "How to play" walkthrough: a horizontal pager of six steps, each showing a
 * manually-stepped animated Compose visual of one game mechanic alongside a dynamic caption
 * and explanatory text. The animations are pure Compose drawing (no Lottie, no external
 * assets) and reuse `drawCar` for the car avatar so the onboarding visuals match the in-race
 * look.
 *
 * This file is now only the *shell*: the pager, one step's layout, and the table of steps. The
 * animations themselves - roughly 1600 lines of Canvas drawing across six mechanics - live one
 * per file under `ui/howtoplay/`, sharing the palette and grid primitives in
 * `HowToPlayVisuals.kt`. Adding or reworking one mechanic used to mean editing an 1800-line
 * file where every animation could see every other animation's private helpers.
 *
 * Layout (everything fits on screen, no scrolling):
 * - Top bar: "How to play" title on the left, swipe hint in the centre, "Back to menu" on right
 * - Content: a horizontal split - animation canvas (with curved forward/backward arrows) on
 *   the left, info panel (caption + title + body) on the right - filling the remaining space
 *
 * Autoplay is disabled. The user steps through each animation manually using the curved arrows
 * drawn on the animation canvas, and swipes left/right to move between rules.
 */
@Composable
fun HowToPlayScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val steps = rememberHowToPlaySteps()

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        // Top bar: title (left) + swipe hint (center) + back-to-menu (right).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.how_to_play_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.how_to_play_swipe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.how_to_play_back))
            }
        }
        Spacer(Modifier.height(12.dp))

        // Pager: each page is a horizontal split (animation left, info right) that fills the
        // remaining space. No scrolling — everything fits on screen.
        HorizontalPager(
            state = rememberPagerState(pageCount = { steps.size }),
            modifier = Modifier.weight(1f),
        ) { page ->
            StepPage(steps[page])
        }
    }
}

/**
 * One step of the walkthrough: a [title], a [body] of explanatory text, the [animation]
 * composable that draws the visual given the current phase (0..1), a [caption] function
 * that returns a short description of what is happening in the animation at a given phase,
 * and [framePhases] — the discrete phase values the user steps through with the arrows.
 *
 * [caption] is composable because each walkthrough reads its captions from string resources,
 * so they follow the app's language like every other piece of text on the screen.
 *
 * The frame phases are chosen so that every consecutive frame carries either new caption
 * text or a visibly different visual state (no two adjacent frames are static duplicates).
 */
private class HowToPlayStep(
    val title: String,
    val body: String,
    val framePhases: List<Float>,
    val animation: @Composable (phase: Float) -> Unit,
    val caption: @Composable (phase: Float) -> String,
)

@Composable
private fun StepPage(step: HowToPlayStep) {
    // Manual phase control: the user steps through the animation with the curved arrows.
    // phaseIndex indexes into [HowToPlayStep.framePhases]; the displayed phase is animated
    // smoothly to the target so each step transition looks like a short animation rather
    // than a jump.
    var phaseIndex by remember { mutableIntStateOf(0) }
    val maxIndex = step.framePhases.lastIndex
    val targetPhase = step.framePhases[phaseIndex.coerceIn(0, maxIndex)]
    val animatedPhase by animateFloatAsState(
        targetValue = targetPhase,
        animationSpec = tween(600, easing = LinearEasing),
        label = "phase",
    )

    // Horizontal split: animation on the left, info panel on the right.
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Animation canvas with curved arrows overlay: left half, fills the full height.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp)),
        ) {
            step.animation(animatedPhase)

            // Curved forward/backward arrows overlaid on the animation canvas, positioned
            // at the bottom corners so they don't overlap the animation content.
            CurvedArrow(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                direction = ArrowDirection.BACKWARD,
                enabled = phaseIndex > 0,
                onClick = { if (phaseIndex > 0) phaseIndex-- },
            )
            CurvedArrow(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                direction = ArrowDirection.FORWARD,
                enabled = phaseIndex < maxIndex,
                onClick = { if (phaseIndex < maxIndex) phaseIndex++ },
            )
        }

        // Info panel: right half — caption, title, and body stacked vertically.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Caption: a highlighted panel with a constant height of 3 rows of text.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CAPTION_HEIGHT_DP.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = step.caption(animatedPhase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }
            }

            // Step title
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            // Step body
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Height of the caption panel: 3 rows of bodySmall text + padding. */
private const val CAPTION_HEIGHT_DP = 64


/**
 * The 6 walkthrough steps. Rule 1 teaches the core move mechanic from rest. Rules 2–6 each
 * focus on a single hazard or objective, starting with the car already in motion, and do not
 * repeat the basics (selectors, coast, choosing a position) already covered by rule 1.
 * Every rule publishes its frame list next to its drawing code, so the phases stepped through
 * here cannot drift from the frames each animation documents.
 */
@Composable
private fun rememberHowToPlaySteps(): List<HowToPlayStep> {
    return listOf(
        HowToPlayStep(
            title = stringResource(R.string.how_to_play_step1_title),
            body = stringResource(R.string.how_to_play_step1_body),
            // 7 frames: rest → selectors → pick → drive → coast+selectors → pick → drive
            framePhases = VelocityRuleFrames,
            animation = { phase -> VelocityRuleAnimation(phase) },
            caption = { phase -> velocityCaption(phase) },
        ),
        HowToPlayStep(
            title = stringResource(R.string.how_to_play_step2_title),
            body = stringResource(R.string.how_to_play_step2_body),
            // 4 frames: coast+trail → selectors → pick (4,2) with new projector → crash
            framePhases = WallCrashFrames,
            animation = { phase -> WallCrashAnimation(phase) },
            caption = { phase -> wallCrashCaption(phase) },
        ),
        HowToPlayStep(
            title = stringResource(R.string.how_to_play_step3_title),
            body = stringResource(R.string.how_to_play_step3_body),
            // 6 frames: cross the line the right way (the lap ticks), then the wrong way (a crash)
            framePhases = FinishLineFrames,
            animation = { phase -> FinishLineAnimation(phase) },
            caption = { phase -> finishLineCaption(phase) },
        ),
        HowToPlayStep(
            title = stringResource(R.string.how_to_play_step4_title),
            body = stringResource(R.string.how_to_play_step4_body),
            // 6 frames: onto the oil → coast plus two slips offered → skid → control returns
            framePhases = OilSpillFrames,
            animation = { phase -> OilSpillAnimation(phase) },
            caption = { phase -> oilSpillCaption(phase) },
        ),
        HowToPlayStep(
            title = stringResource(R.string.how_to_play_step5_title),
            body = stringResource(R.string.how_to_play_step5_body),
            // 8 frames: onto the nails → brake to speed one → roll off → control returns
            framePhases = NailsFrames,
            animation = { phase -> NailsAnimation(phase) },
            caption = { phase -> nailsCaption(phase) },
        ),
        HowToPlayStep(
            title = stringResource(R.string.how_to_play_step6_title),
            body = stringResource(R.string.how_to_play_step6_body),
            // 6 frames: into the gravel → braked coast, barrier withheld → crawl out → brake holds.
            // Generated by tools/tutorial-editor from projects/gravel_trap.json.
            framePhases = GravelTrapFrames,
            animation = { phase -> GravelTrapAnimation(phase) },
            caption = { phase -> gravelTrapCaption(phase) },
        ),
    )
}
