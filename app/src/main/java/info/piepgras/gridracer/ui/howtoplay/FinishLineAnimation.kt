package info.piepgras.gridracer.ui.howtoplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.ui.drawWreck

/** The phases HowToPlayScreen steps [FinishLineAnimation] through, one per frame listed there. */
internal val FinishLineFrames = listOf(0f, 0.18f, 0.36f, 0.55f, 0.70f, 0.88f)

/** The column the finish line runs down, halfway between two grid columns. */
private const val LINE_X = 4.5f

/** The row both halves of the walkthrough drive along; the line's arrow sits on row 2. */
private const val LANE = 3

/**
 * Six-frame walkthrough of the finish line: crossing it the right way, then the wrong way,
 * on one scene.
 *
 * Grid: 9 columns (x=0..8), 5 rows (y=0..4). The finish line runs down x=4.5 with its arrow
 * pointing +x, drawn as `TrackCanvas` draws them, and a copy of the in-race status frame in
 * the top-left corner shows the lap counter of a two-lap race.
 *
 * Right way:
 * 1. You approach from (2,3) at speed (2,0). The coast (4,3) is just short of the line.
 * 2. The nine candidates around the coast are all offered; the three at x=5 cross the line.
 * 3. You pick (5,3) and drive across. The lap counter ticks from 1/2 to 2/2 the moment the
 *    car passes the line.
 *
 * Wrong way:
 * 4. Later the same car creeps back toward the line from (6,3) at speed (-1,0).
 * 5. Its candidates at x=4 are offered like any other point: the engine does not withhold a
 *    backward crossing, it crashes the car that makes one (see `GameEngine.applyMove`).
 * 6. You pick (4,3). The car burns where it meets the line and the status frame gets its
 *    red X.
 */
@Composable
internal fun FinishLineAnimation(phase: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val grid = drawAnimGrid(cols = 9, rows = 5)
        val cell = grid.cellSize

        drawLine(
            AnimFinishColor,
            grid.offset(LINE_X, -0.35f),
            grid.offset(LINE_X, 4.35f),
            strokeWidth = cell * 0.12f,
        )
        drawAnimFinishArrow(grid.offset(LINE_X, 2f), cell)

        // --- Right way: (2,3) → (5,3), crossing the line on the way. Fades out before frame 4. ---
        val start = grid.offset(Point(2, LANE))
        val coast = grid.offset(Point(4, LANE))
        val across = grid.offset(Point(5, LANE))
        val carPos = carAlong(start, across, phase, 0.20f, 0.34f)
        val crossed = carPos.x >= grid.offset(LINE_X, 0f).x
        val rightAlpha = rampDown(phase, 0.38f, 0.46f)
        if (rightAlpha > 0f) {
            drawTrailSegment(grid.offset(Point(0, LANE)), start, cell, rightAlpha)
            if (phase >= 0.20f) drawTrailSegment(start, carPos, cell, rightAlpha)
            val pickersAlpha = minOf(ramp(phase, 0.04f, 0.14f), rampDown(phase, 0.18f, 0.26f)) * rightAlpha
            drawProjector(start, coast, cell, rampDown(phase, 0.18f, 0.26f) * rightAlpha)
            drawOfferedPickers(grid.candidates(Point(4, LANE)), grid, pickersAlpha)
            drawPickRing(Point(5, LANE), grid, minOf(ramp(phase, 0.14f, 0.18f), pickersAlpha))
            drawDemoCar(carPos, cell, heading = Point(1, 0), alpha = rightAlpha)
        }

        // --- Wrong way: from (6,3) toward (4,3). The car stops burning where it meets the line. ---
        val wrongAlpha = ramp(phase, 0.46f, 0.55f)
        val crashed = phase >= 0.82f
        if (wrongAlpha > 0f) {
            val back = grid.offset(Point(6, LANE))
            val backCoast = grid.offset(Point(5, LANE))
            val onLine = grid.offset(LINE_X, LANE.toFloat())
            val backPos = carAlong(back, onLine, phase, 0.72f, 0.82f)
            drawTrailSegment(grid.offset(Point(7, LANE)), back, cell, wrongAlpha)
            if (phase >= 0.72f) drawTrailSegment(back, backPos, cell, wrongAlpha)
            val pickersAlpha = minOf(ramp(phase, 0.57f, 0.65f), rampDown(phase, 0.70f, 0.76f)) * wrongAlpha
            drawProjector(back, backCoast, cell, rampDown(phase, 0.70f, 0.76f) * wrongAlpha)
            drawOfferedPickers(grid.candidates(Point(5, LANE)), grid, pickersAlpha)
            drawPickRing(Point(4, LANE), grid, minOf(ramp(phase, 0.65f, 0.70f), pickersAlpha))
            drawDemoCar(backPos, cell, heading = Point(-1, 0), alpha = wrongAlpha)
            if (crashed) drawWreck(onLine, cell, AnimCarColor)
        }

        drawStatusFrame(grid, lapText = if (crossed) "2/2" else "1/2", crashed = crashed)
    }
}

/** The caption for the finish-line walkthrough at [phase], one translated string per frame. */
@Composable
internal fun finishLineCaption(phase: Float): String = when {
    phase < 0.18f -> stringResource(R.string.how_to_play_finish_line_caption_1)
    phase < 0.36f -> stringResource(R.string.how_to_play_finish_line_caption_2)
    phase < 0.55f -> stringResource(R.string.how_to_play_finish_line_caption_3)
    phase < 0.70f -> stringResource(R.string.how_to_play_finish_line_caption_4)
    phase < 0.88f -> stringResource(R.string.how_to_play_finish_line_caption_5)
    else -> stringResource(R.string.how_to_play_finish_line_caption_6)
}
