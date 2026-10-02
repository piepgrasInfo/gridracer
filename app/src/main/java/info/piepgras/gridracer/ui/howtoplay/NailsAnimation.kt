package info.piepgras.gridracer.ui.howtoplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.Point

/** The phases HowToPlayScreen steps [NailsAnimation] through, one per frame listed there. */
internal val NailsFrames = listOf(0f, 0.12f, 0.24f, 0.36f, 0.48f, 0.60f, 0.72f, 0.85f)

/**
 * Eight-frame walkthrough of nails, following `GameEngine.nailAllowedSet`: a move whose path
 * touches the nails limits the *next* move to a slow-down. At speed above one that is the
 * three candidates nearest the car (the smallest resulting speed); at speed one it is
 * stopping, rolling straight on, or moving to either side.
 *
 * Grid: 9 columns (x=0..8), 5 rows (y=0..4). The nails are a blob around (4,2).
 *
 * 1. You approach from (2,2) at speed (2,0); the coast (4,2) lies on the nails.
 * 2. Nails do not block a move: all nine candidates are offered. You pick the coast.
 * 3. You drive onto the nails. The new coast is (6,2).
 * 4. At speed two, only (5,1), (5,2) and (5,3) are offered - the three nearest the car.
 * 5. You brake to (5,2), speed (1,0). That move started on the nails, so they act again.
 * 6. At speed one, stop (5,2), roll on (6,2) and the two sideways points (5,1), (5,3) are
 *    offered; the five that would accelerate or turn diagonally are withheld.
 * 7. You roll on to (6,2), clear of the nails.
 * 8. That move touched no nails, so all nine candidates around the coast (7,2) return.
 */
@Composable
internal fun NailsAnimation(phase: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val grid = drawAnimGrid(cols = 9, rows = 5)
        val cell = grid.cellSize

        drawHazardZone(
            listOf(
                grid.offset(3.15f, 1.7f), grid.offset(3.35f, 1.2f), grid.offset(4.1f, 1.1f),
                grid.offset(4.8f, 1.35f), grid.offset(4.85f, 2.3f), grid.offset(4.5f, 2.85f),
                grid.offset(3.6f, 2.9f), grid.offset(3.1f, 2.4f),
            ),
            AnimNailsColor,
        )

        val start = Point(2, 2)
        val onNails = Point(4, 2)                                       // the coast from start at (2,0)
        val fastCoast = Point(6, 2)                                     // the coast from onNails, still at (2,0)
        val brakeSet = listOf(Point(5, 1), Point(5, 2), Point(5, 3))    // the three candidates nearest onNails
        val braked = Point(5, 2)                                        // new speed (1,0)
        val slowCoast = Point(6, 2)                                     // braked + (1,0)
        val crawlSet = listOf(braked, slowCoast, Point(5, 1), Point(5, 3)) // stop, front, the two strafes
        val rolled = Point(6, 2)
        val freeCoast = Point(7, 2)                                     // rolled + (1,0)

        // --- Car: three moves, each eased over its own interval ---
        val carPos = when {
            phase < 0.38f -> carAlong(grid.offset(start), grid.offset(onNails), phase, 0.14f, 0.23f)
            phase < 0.62f -> carAlong(grid.offset(onNails), grid.offset(braked), phase, 0.38f, 0.46f)
            else -> carAlong(grid.offset(braked), grid.offset(rolled), phase, 0.62f, 0.70f)
        }

        // --- Trail ---
        drawTrailSegment(grid.offset(Point(0, 2)), grid.offset(start), cell)
        if (phase >= 0.14f) {
            drawTrailSegment(grid.offset(start), if (phase < 0.38f) carPos else grid.offset(onNails), cell)
        }
        if (phase >= 0.38f) {
            drawTrailSegment(grid.offset(onNails), if (phase < 0.62f) carPos else grid.offset(braked), cell)
        }
        if (phase >= 0.62f) drawTrailSegment(grid.offset(braked), carPos, cell)

        // --- Frames 1–2: the ordinary nine around (4,2) ---
        drawProjector(grid.offset(start), grid.offset(onNails), cell, rampDown(phase, 0.13f, 0.18f))
        val plainAlpha = minOf(ramp(phase, 0.02f, 0.09f), rampDown(phase, 0.13f, 0.18f))
        drawOfferedPickers(grid.candidates(onNails), grid, plainAlpha)
        drawPickRing(onNails, grid, minOf(ramp(phase, 0.09f, 0.12f), plainAlpha))

        // --- Frames 3–5: at speed two, only the three hardest-braking candidates ---
        drawProjector(
            grid.offset(onNails), grid.offset(fastCoast), cell,
            minOf(ramp(phase, 0.22f, 0.24f), rampDown(phase, 0.38f, 0.42f)),
        )
        val brakeAlpha = minOf(ramp(phase, 0.26f, 0.33f), rampDown(phase, 0.38f, 0.42f))
        drawOfferedPickers(brakeSet, grid, brakeAlpha)
        drawWithheldPickers(grid.candidates(fastCoast) - brakeSet, grid, brakeAlpha)
        drawPickRing(braked, grid, minOf(ramp(phase, 0.33f, 0.36f), brakeAlpha))

        // --- Frames 5–7: at speed one, stop / roll on / sideways ---
        drawProjector(
            grid.offset(braked), grid.offset(slowCoast), cell,
            minOf(ramp(phase, 0.46f, 0.48f), rampDown(phase, 0.62f, 0.66f)),
        )
        val crawlAlpha = minOf(ramp(phase, 0.50f, 0.57f), rampDown(phase, 0.62f, 0.66f))
        drawOfferedPickers(crawlSet, grid, crawlAlpha)
        drawWithheldPickers(grid.candidates(slowCoast) - crawlSet, grid, crawlAlpha)
        drawPickRing(rolled, grid, minOf(ramp(phase, 0.57f, 0.60f), crawlAlpha))

        // --- Frame 8: clear of the nails ---
        drawProjector(grid.offset(rolled), grid.offset(freeCoast), cell, ramp(phase, 0.72f, 0.76f))
        drawOfferedPickers(grid.candidates(freeCoast), grid, ramp(phase, 0.76f, 0.85f))

        drawDemoCar(carPos, cell, heading = Point(1, 0))
    }
}

/** The caption for the nails walkthrough at [phase], one translated string per frame. */
@Composable
internal fun nailsCaption(phase: Float): String = when {
    phase < 0.12f -> stringResource(R.string.how_to_play_nails_caption_1)
    phase < 0.24f -> stringResource(R.string.how_to_play_nails_caption_2)
    phase < 0.36f -> stringResource(R.string.how_to_play_nails_caption_3)
    phase < 0.48f -> stringResource(R.string.how_to_play_nails_caption_4)
    phase < 0.60f -> stringResource(R.string.how_to_play_nails_caption_5)
    phase < 0.72f -> stringResource(R.string.how_to_play_nails_caption_6)
    phase < 0.85f -> stringResource(R.string.how_to_play_nails_caption_7)
    else -> stringResource(R.string.how_to_play_nails_caption_8)
}
