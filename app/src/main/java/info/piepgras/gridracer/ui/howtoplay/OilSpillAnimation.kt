package info.piepgras.gridracer.ui.howtoplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.Point
import kotlin.math.PI
import kotlin.math.sin

/** The phases HowToPlayScreen steps [OilSpillAnimation] through, one per frame listed there. */
internal val OilSpillFrames = listOf(0f, 0.15f, 0.30f, 0.45f, 0.60f, 0.75f)

/**
 * Six-frame walkthrough of an oil spill, following `GameEngine.oilSlipTargets`: a move whose
 * path touches the spill costs the player their steering on the *next* move, when only the
 * coast point and two randomly drawn neighbours of it are offered.
 *
 * Grid: 9 columns (x=0..8), 5 rows (y=0..4). The spill is a blob around (4,1).
 *
 * 1. You approach from (2,1) at speed (2,0); the coast (4,1) lies on the spill.
 * 2. Oil does not block a move: all nine candidates are offered. You pick the coast.
 * 3. You drive onto the spill. The new coast is (6,1), two points on.
 * 4. Steering is gone: only (6,1) and the two drawn slips (5,2) and (7,2) are offered; the
 *    other six candidates are withheld.
 * 5. You pick (5,2) and skid there. The new speed is (1,1).
 * 6. That move touched no spill, so all nine candidates around the coast (6,3) return.
 */
@Composable
internal fun OilSpillAnimation(phase: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val grid = drawAnimGrid(cols = 9, rows = 5)
        val cell = grid.cellSize

        drawHazardZone(
            listOf(
                grid.offset(3.1f, 0.9f), grid.offset(3.5f, 0.25f), grid.offset(4.4f, 0.15f),
                grid.offset(4.95f, 0.7f), grid.offset(4.85f, 1.55f), grid.offset(4.2f, 1.9f),
                grid.offset(3.4f, 1.75f), grid.offset(3.05f, 1.4f),
            ),
            AnimOilColor,
        )

        val start = Point(2, 1)
        val onOil = Point(4, 1)                       // the coast from start at speed (2,0)
        val oilCoast = Point(6, 1)                    // the coast from onOil, still at (2,0)
        val slips = listOf(Point(5, 2), Point(7, 2))  // oilCoast plus the two drawn slip offsets
        val skid = Point(5, 2)                        // the slip we take: new speed (1,1)
        val freeCoast = Point(6, 3)                   // skid + (1,1)

        // --- Car: onto the oil at 0.17–0.28, the skid to (5,2) at 0.50–0.60 ---
        val carPos: Offset
        val heading: Point
        if (phase < 0.50f) {
            carPos = carAlong(grid.offset(start), grid.offset(onOil), phase, 0.17f, 0.28f)
            heading = Point(1, 0)
        } else {
            val t = ramp(phase, 0.50f, 0.60f)
            val along = lerp(grid.offset(onOil), grid.offset(skid), AnimMoveEasing.transform(t))
            // A damped shimmy across the (1,1) direction of travel: the car is skidding, not steering.
            val shimmy = sin(t * PI * 3).toFloat() * (1f - t) * cell * 0.10f
            carPos = Offset(along.x - shimmy * 0.7071f, along.y + shimmy * 0.7071f)
            heading = Point(1, 1)
        }

        // --- Trail ---
        drawTrailSegment(grid.offset(Point(0, 1)), grid.offset(start), cell)
        if (phase >= 0.17f) {
            drawTrailSegment(grid.offset(start), if (phase < 0.50f) carPos else grid.offset(onOil), cell)
        }
        if (phase >= 0.50f) drawTrailSegment(grid.offset(onOil), carPos, cell)

        // --- Frames 1–2: the ordinary nine around (4,1) ---
        drawProjector(grid.offset(start), grid.offset(onOil), cell, rampDown(phase, 0.16f, 0.22f))
        val plainAlpha = minOf(ramp(phase, 0.03f, 0.11f), rampDown(phase, 0.16f, 0.22f))
        drawOfferedPickers(grid.candidates(onOil), grid, plainAlpha)
        drawPickRing(onOil, grid, minOf(ramp(phase, 0.11f, 0.15f), plainAlpha))

        // --- Frames 3–5: the oil turn. Coast plus two slips offered, the other six withheld. ---
        drawProjector(
            grid.offset(onOil), grid.offset(oilCoast), cell,
            minOf(ramp(phase, 0.26f, 0.30f), rampDown(phase, 0.50f, 0.56f)),
        )
        val oilAlpha = minOf(ramp(phase, 0.32f, 0.40f), rampDown(phase, 0.50f, 0.56f))
        val offered = listOf(oilCoast) + slips
        drawOfferedPickers(offered, grid, oilAlpha)
        drawWithheldPickers(grid.candidates(oilCoast) - offered, grid, oilAlpha)
        drawPickRing(skid, grid, minOf(ramp(phase, 0.46f, 0.50f), oilAlpha))

        // --- Frame 6: control is back ---
        drawProjector(grid.offset(skid), grid.offset(freeCoast), cell, ramp(phase, 0.62f, 0.68f))
        drawOfferedPickers(grid.candidates(freeCoast), grid, ramp(phase, 0.66f, 0.75f))

        drawDemoCar(carPos, cell, heading)
    }
}

/** The caption for the oil-spill walkthrough at [phase], one translated string per frame. */
@Composable
internal fun oilSpillCaption(phase: Float): String = when {
    phase < 0.15f -> stringResource(R.string.how_to_play_oil_spill_caption_1)
    phase < 0.30f -> stringResource(R.string.how_to_play_oil_spill_caption_2)
    phase < 0.45f -> stringResource(R.string.how_to_play_oil_spill_caption_3)
    phase < 0.60f -> stringResource(R.string.how_to_play_oil_spill_caption_4)
    phase < 0.75f -> stringResource(R.string.how_to_play_oil_spill_caption_5)
    else -> stringResource(R.string.how_to_play_oil_spill_caption_6)
}
