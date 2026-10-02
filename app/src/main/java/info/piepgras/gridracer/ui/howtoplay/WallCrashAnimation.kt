package info.piepgras.gridracer.ui.howtoplay

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.ui.drawCar
import info.piepgras.gridracer.ui.drawWreck

/*
 * Four-frame walkthrough of walls and crashing. You are already at speed (velocity (3,0))
 * approaching a wall. The core mechanic: walls block any move whose path crosses them,
 * and if your coast projects past a wall and every move would cross it, you crash.
 *
 * Grid: 8 columns (x=0..7), 5 rows (y=0..4). Wall at x=4.75 (quarter grid left of x=5).
 * Three columns of grid points (x=5,6,7) are behind the wall.
 *
 * 1. Your coast projects past the wall. Only the car, its trail, and the projector line
 *    are visible — no selector points yet.
 * 2. The green selector points fade in: valid points (x≤4) at full size, illegal points
 *    (x≥5) small and dim.
 * 3. You select (4,2) and the car drives there; a new projector line fades in, showing
 *    the coast from (4,2) crossing the wall.
 * 4. You crash into the wall — a burning wreck appears at the projection/wall intersection.
 *
 * Frame phases: [WallCrashFrames].
 */

/** The phases HowToPlayScreen steps [WallCrashAnimation] through, one per frame listed above. */
internal val WallCrashFrames = listOf(0f, 0.25f, 0.55f, 0.80f)

/**
 * The wall-crash walkthrough: a move whose path touches a wall crashes the car. Drawn at [phase], 0
 * to 1; see [WallCrashFrames].
 */
@Composable
internal fun WallCrashAnimation(phase: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(AnimTrackColor)

        // Grid: 8 columns (x=0..7), 5 rows (y=0..4)
        val gridCols = 8
        val gridRows = 5
        val minY = 0
        val cellSize = kotlin.math.min(size.width / gridCols, size.height / gridRows) * 0.85f
        val originX = (size.width - cellSize * gridCols) / 2f
        val originY = (size.height - cellSize * gridRows) / 2f

        fun offset(p: Point) = Offset(
            originX + p.x * cellSize,
            originY + (p.y - minY) * cellSize,
        )

        // --- Grid dots ---
        for (gx in 0 until gridCols) {
            for (gy in minY until minY + gridRows) {
                drawCircle(AnimGridColor, radius = cellSize * 0.05f, center = offset(Point(gx, gy)))
            }
        }

        // --- Wall at x=4.75 (quarter grid left of x=5) ---
        val wallGridX = 4.75f
        val wallX = originX + wallGridX * cellSize
        drawLine(
            AnimWallColor,
            start = Offset(wallX, originY - cellSize * 0.3f),
            end = Offset(wallX, originY + gridRows * cellSize + cellSize * 0.3f),
            strokeWidth = cellSize * 0.15f,
        )

        // Crash site where the (4,2)→(6,3) projection meets the wall.
        // Parametric: (4+2t, 2+t), at x=4.75: t=0.375, y=2.375.
        val wreckGridX = 4.75f
        val wreckGridY = 2.375f
        val wreckCenter = Offset(
            originX + wreckGridX * cellSize,
            originY + (wreckGridY - minY) * cellSize,
        )

        // --- Key positions ---
        val carStart = Point(2, 1)      // starting position
        val coast1 = Point(5, 1)        // carStart + velocity (3,0)
        val pickGrid = Point(4, 2)      // braking + steer right
        val coast2 = Point(6, 3)        // pickGrid + new velocity (2,1)
        val trailStart = Point(0, 1)    // visible trail origin

        // --- Car position & direction ---
        val carPos: Offset
        val carDir: Point
        val carAlpha: Float
        when {
            phase < 0.35f -> {
                carPos = offset(carStart)
                carDir = Point(3, 0)
                carAlpha = 1f
            }
            phase < 0.45f -> {
                val t = ((phase - 0.35f) / 0.10f).coerceIn(0f, 1f)
                val eased = CubicBezierEasing(0.4f, 0f, 0.2f, 1f).transform(t)
                val s = offset(carStart)
                val e = offset(pickGrid)
                carPos = Offset(s.x + (e.x - s.x) * eased, s.y + (e.y - s.y) * eased)
                carDir = Point(2, 1)
                carAlpha = 1f
            }
            phase < 0.65f -> {
                carPos = offset(pickGrid)
                carDir = Point(2, 1)
                carAlpha = 1f
            }
            else -> {
                // Car fades out as wreck appears
                carPos = offset(pickGrid)
                carDir = Point(2, 1)
                carAlpha = (1f - (phase - 0.65f) / 0.10f).coerceIn(0f, 1f)
            }
        }

        // --- Trail ---
        val trailColor = AnimCarColor.copy(alpha = 0.45f)
        val trailWidth = cellSize * 0.08f
        // Static trail: trailStart → carStart (always visible)
        drawLine(trailColor, offset(trailStart), offset(carStart), strokeWidth = trailWidth)
        // Growing trail: carStart → pickGrid (during/after the move)
        if (phase >= 0.35f) {
            val trailEnd = if (phase < 0.45f) {
                val t = ((phase - 0.35f) / 0.10f).coerceIn(0f, 1f)
                val eased = CubicBezierEasing(0.4f, 0f, 0.2f, 1f).transform(t)
                val s = offset(carStart)
                val e = offset(pickGrid)
                Offset(s.x + (e.x - s.x) * eased, s.y + (e.y - s.y) * eased)
            } else offset(pickGrid)
            drawLine(trailColor, offset(carStart), trailEnd, strokeWidth = trailWidth)
        }
        // Crash trail: pickGrid → wreckCenter, fades in as the crash happens.
        val crashTrailAlpha = when {
            phase < 0.65f -> 0f
            phase < 0.75f -> (phase - 0.65f) / 0.10f
            else -> 1f
        }
        if (crashTrailAlpha > 0f) {
            drawLine(
                trailColor.copy(alpha = trailColor.alpha * crashTrailAlpha),
                offset(pickGrid),
                wreckCenter,
                strokeWidth = trailWidth,
            )
        }

        // --- Coast projections (dashed) ---
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(cellSize * 0.15f, cellSize * 0.1f), 0f)

        // First projection: carStart → coast1 (visible in frames 1-2, fades as car moves)
        val dash1Alpha = when {
            phase < 0.25f -> 1f
            phase < 0.40f -> 1f - (phase - 0.25f) / 0.15f
            else -> 0f
        }
        if (dash1Alpha > 0f) {
            drawLine(
                AnimLegalColor.copy(alpha = dash1Alpha * 0.7f),
                offset(carStart),
                offset(coast1),
                strokeWidth = cellSize * 0.05f,
                pathEffect = dashEffect,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        // Second projection: pickGrid → coast2 (fades in as the car arrives, then out during crash)
        val dash2Alpha = when {
            phase < 0.45f -> 0f
            phase < 0.55f -> (phase - 0.45f) / 0.10f
            phase < 0.65f -> 1f
            phase < 0.75f -> 1f - (phase - 0.65f) / 0.10f
            else -> 0f
        }
        if (dash2Alpha > 0f) {
            drawLine(
                AnimCrashColor.copy(alpha = dash2Alpha * 0.7f),
                offset(pickGrid),
                offset(coast2),
                strokeWidth = cellSize * 0.05f,
                pathEffect = dashEffect,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        // --- Selectors with legal/illegal distinction ---
        // Fade in during frame 2, then fade out once (4,2) is selected.
        val picker1Alpha = when {
            phase < 0.05f -> 0f
            phase < 0.20f -> (phase - 0.05f) / 0.15f
            phase < 0.25f -> 1f
            phase < 0.40f -> 1f - (phase - 0.25f) / 0.15f
            else -> 0f
        }
        if (picker1Alpha > 0f) {
            for (dx in -1..1) for (dy in -1..1) {
                val p = Point(coast1.x + dx, coast1.y + dy)
                val legal = p.x < wallGridX // x=4 is before wall (4.75), x>=5 is past it
                if (legal) {
                    // Valid: standard size, same as rule 1
                    drawCircle(
                        AnimLegalColor.copy(alpha = 0.55f * picker1Alpha),
                        radius = cellSize * 0.32f,
                        center = offset(p),
                    )
                } else {
                    // Illegal: smaller and dimmer
                    drawCircle(
                        AnimLegalColor.copy(alpha = 0.2f * picker1Alpha),
                        radius = cellSize * 0.14f,
                        center = offset(p),
                    )
                }
            }
            // Pick ring on (4,2)
            val ringAlpha = when {
                phase < 0.25f -> 0f
                phase < 0.35f -> (phase - 0.25f) / 0.10f
                else -> picker1Alpha
            }
            if (ringAlpha > 0f) {
                drawCircle(
                    AnimSelectedColor.copy(alpha = ringAlpha),
                    radius = cellSize * 0.32f,
                    center = offset(pickGrid),
                    style = Stroke(width = cellSize * 0.06f),
                )
            }
        }

        // Second set: 9 candidates around coast2 (6,3) — all illegal (x=5,6,7).
        // Fades in alongside the second projector line and out during the crash.
        val picker2Alpha = when {
            phase < 0.45f -> 0f
            phase < 0.55f -> (phase - 0.45f) / 0.10f
            phase < 0.65f -> 1f
            phase < 0.75f -> 1f - (phase - 0.65f) / 0.10f
            else -> 0f
        }
        if (picker2Alpha > 0f) {
            for (dx in -1..1) for (dy in -1..1) {
                val p = Point(coast2.x + dx, coast2.y + dy)
                // All behind wall — all illegal
                drawCircle(
                    AnimLegalColor.copy(alpha = 0.2f * picker2Alpha),
                    radius = cellSize * 0.14f,
                    center = offset(p),
                )
            }
        }

        // --- Car (fades out in crash frame) ---
        if (carAlpha > 0f) {
            drawCar(
                center = carPos,
                cellSize = cellSize * 0.9f,
                color = AnimCarColor.copy(alpha = carAlpha),
                direction = carDir,
                trackFinishDirection = Point(1, 0),
            )
        }

        // --- Crash car (fades in at the impact site) ---
        val wreckCarAlpha = when {
            phase < 0.65f -> 0f
            phase < 0.75f -> (phase - 0.65f) / 0.10f
            else -> 1f
        }
        if (wreckCarAlpha > 0f) {
            drawCar(
                center = wreckCenter,
                cellSize = cellSize * 0.9f,
                color = AnimCarColor.copy(alpha = wreckCarAlpha),
                direction = Point(2, 1),
                trackFinishDirection = Point(1, 0),
            )
        }

        // --- Burning wreck ---
        if (phase >= 0.70f) {
            drawWreck(wreckCenter, cellSize, AnimCarColor)
        }
    }
}

/** The caption for the wall-crash walkthrough at [phase], one translated string per frame. */
@Composable
internal fun wallCrashCaption(phase: Float): String = when {
    phase < 0.25f -> stringResource(R.string.how_to_play_wall_crash_caption_1)
    phase < 0.55f -> stringResource(R.string.how_to_play_wall_crash_caption_2)
    phase < 0.80f -> stringResource(R.string.how_to_play_wall_crash_caption_3)
    else -> stringResource(R.string.how_to_play_wall_crash_caption_4)
}
