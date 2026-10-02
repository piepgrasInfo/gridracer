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

/* Step 1: how a move is chosen from the coasted position. */

/** The phases HowToPlayScreen steps [VelocityRuleAnimation] through, one per frame. */
internal val VelocityRuleFrames = listOf(0f, 0.14f, 0.24f, 0.38f, 0.52f, 0.62f, 0.80f)

/**
 * Rule 1 of the walkthrough: the car coasts to where its last move would carry it, and the next
 * move is one of the nine points around that spot. Drawn at [phase], 0 to 1; see
 * [VelocityRuleFrames].
 */
@Composable
internal fun VelocityRuleAnimation(phase: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(AnimTrackColor)
        val cellSize = size.minDimension / 7f
        val originX = (size.width - cellSize * 5) / 2f
        val originY = (size.height - cellSize * 3) / 2f
        drawGridDots(cellSize, originX, originY, 5, 3)

        val startPos = Point(1, 1)
        val firstMove = Point(2, 1)
        val coast2 = Point(3, 1)
        val secondMove = Point(4, 0)

        fun offset(p: Point) = gridToOffset(p.x, p.y, cellSize, originX, originY)

        // --- Car position & direction ---
        // 0.00–0.24: at startPos (1,1), at rest → faces right via trackFinishDirection
        // 0.24–0.38: moving (1,1) → (2,1), velocity (1,0)
        // 0.38–0.62: at (2,1)
        // 0.62–0.80: moving (2,1) → (3,0), velocity (1,-1)
        // 0.80+    : at (3,0)
        val carPos: Offset
        val carDir: Point
        when {
            phase < 0.24f -> {
                carPos = offset(startPos)
                carDir = Point(1, 0)
            }
            phase < 0.38f -> {
                val t = ((phase - 0.24f) / 0.14f).coerceIn(0f, 1f)
                val eased = CubicBezierEasing(0.4f, 0f, 0.2f, 1f).transform(t)
                val s = offset(startPos)
                val e = offset(firstMove)
                carPos = Offset(s.x + (e.x - s.x) * eased, s.y + (e.y - s.y) * eased)
                carDir = Point(1, 0)
            }
            phase < 0.62f -> {
                carPos = offset(firstMove)
                carDir = Point(1, 0)
            }
            phase < 0.80f -> {
                val t = ((phase - 0.62f) / 0.18f).coerceIn(0f, 1f)
                val eased = CubicBezierEasing(0.4f, 0f, 0.2f, 1f).transform(t)
                val s = offset(firstMove)
                val e = offset(secondMove)
                carPos = Offset(s.x + (e.x - s.x) * eased, s.y + (e.y - s.y) * eased)
                carDir = Point(2, -1)
            }
            else -> {
                carPos = offset(secondMove)
                carDir = Point(2, -1)
            }
        }

        // --- Trail (matches in-game drawTrail: player color at 0.45 alpha, cellSize*0.08 stroke) ---
        val trailColor = AnimCarColor.copy(alpha = 0.45f)
        val trailWidth = cellSize * 0.08f

        // Segment 1: startPos → firstMove (grows during first move, full after)
        if (phase >= 0.24f) {
            val trailEnd = if (phase < 0.38f) {
                val t = ((phase - 0.24f) / 0.14f).coerceIn(0f, 1f)
                val eased = CubicBezierEasing(0.4f, 0f, 0.2f, 1f).transform(t)
                val s = offset(startPos)
                val e = offset(firstMove)
                Offset(s.x + (e.x - s.x) * eased, s.y + (e.y - s.y) * eased)
            } else offset(firstMove)
            drawLine(trailColor, offset(startPos), trailEnd, strokeWidth = trailWidth)
        }

        // Segment 2: firstMove → secondMove (grows during second move, full after)
        if (phase >= 0.62f) {
            val trailEnd = if (phase < 0.80f) {
                val t = ((phase - 0.62f) / 0.18f).coerceIn(0f, 1f)
                val eased = CubicBezierEasing(0.4f, 0f, 0.2f, 1f).transform(t)
                val s = offset(firstMove)
                val e = offset(secondMove)
                Offset(s.x + (e.x - s.x) * eased, s.y + (e.y - s.y) * eased)
            } else offset(secondMove)
            drawLine(trailColor, offset(firstMove), trailEnd, strokeWidth = trailWidth)
        }

        // --- Dashed projection line: firstMove → coast2 (the coast point) ---
        val dashAlpha = when {
            phase < 0.38f -> 0f
            phase < 0.52f -> (phase - 0.38f) / 0.14f
            phase < 0.62f -> 1f
            phase < 0.80f -> 1f - (phase - 0.62f) / 0.18f
            else -> 0f
        }
        if (dashAlpha > 0f) {
            val dashEffect = PathEffect.dashPathEffect(
                floatArrayOf(cellSize * 0.15f, cellSize * 0.1f), 0f,
            )
            drawLine(
                AnimLegalColor.copy(alpha = dashAlpha * 0.7f),
                offset(firstMove),
                offset(coast2),
                strokeWidth = cellSize * 0.05f,
                pathEffect = dashEffect,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        // --- First selector set: 9 dots centered on coast1 = startPos (1,1) ---
        // Fade in 0→0.14 (frames 1→2), full 0.14→0.24 (frames 2→3), fade out 0.24→0.38 (frame 3→4).
        val picker1Alpha = when {
            phase < 0.14f -> phase / 0.14f
            phase < 0.24f -> 1f
            phase < 0.38f -> 1f - (phase - 0.24f) / 0.14f
            else -> 0f
        }
        if (picker1Alpha > 0f) {
            val candidates1 = buildList {
                for (dx in -1..1) for (dy in -1..1) add(Point(startPos.x + dx, startPos.y + dy))
            }
            drawMovePickers(candidates1, null, cellSize, originX, originY, picker1Alpha)
            // Highlight ring on the picked point (2,1): fades in 0.14→0.24 (frames 2→3),
            // then follows the picker fade-out.
            val ring1Alpha = when {
                phase < 0.14f -> 0f
                phase < 0.24f -> (phase - 0.14f) / 0.10f
                phase < 0.38f -> picker1Alpha
                else -> 0f
            }
            if (ring1Alpha > 0f) {
                val c = offset(firstMove)
                drawCircle(
                    AnimSelectedColor.copy(alpha = ring1Alpha),
                    radius = cellSize * 0.32f,
                    center = c,
                    style = Stroke(width = cellSize * 0.06f),
                )
            }
        }

        // --- Second selector set: 9 dots centered on coast2 = (3,1) ---
        // Fade in 0.38→0.52 (frames 4→5), full 0.52→0.62 (frames 5→6), fade out 0.62→0.80 (frame 6→7).
        val picker2Alpha = when {
            phase < 0.38f -> 0f
            phase < 0.52f -> (phase - 0.38f) / 0.14f
            phase < 0.62f -> 1f
            phase < 0.80f -> 1f - (phase - 0.62f) / 0.18f
            else -> 0f
        }
        if (picker2Alpha > 0f) {
            val candidates2 = buildList {
                for (dx in -1..1) for (dy in -1..1) add(Point(coast2.x + dx, coast2.y + dy))
            }
            drawMovePickers(candidates2, null, cellSize, originX, originY, picker2Alpha)
            // Highlight ring on the picked point (3,0): fades in 0.52→0.62 (frames 5→6),
            // then follows the picker fade-out.
            val ring2Alpha = when {
                phase < 0.52f -> 0f
                phase < 0.62f -> (phase - 0.52f) / 0.10f
                phase < 0.80f -> picker2Alpha
                else -> 0f
            }
            if (ring2Alpha > 0f) {
                val c = offset(secondMove)
                drawCircle(
                    AnimSelectedColor.copy(alpha = ring2Alpha),
                    radius = cellSize * 0.32f,
                    center = c,
                    style = Stroke(width = cellSize * 0.06f),
                )
            }
        }

        // --- Car (drawn on top of trail and selectors) ---
        drawCar(
            center = carPos,
            cellSize = cellSize * 0.9f,
            color = AnimCarColor,
            direction = carDir,
            trackFinishDirection = Point(1, 0),
        )
    }
}

/** The caption for the velocity-rule walkthrough at [phase], one translated string per frame. */
@Composable
internal fun velocityCaption(phase: Float): String = when {
    phase < 0.14f -> stringResource(R.string.how_to_play_velocity_rule_caption_1)
    phase < 0.24f -> stringResource(R.string.how_to_play_velocity_rule_caption_2)
    phase < 0.38f -> stringResource(R.string.how_to_play_velocity_rule_caption_3)
    phase < 0.52f -> stringResource(R.string.how_to_play_velocity_rule_caption_4)
    phase < 0.62f -> stringResource(R.string.how_to_play_velocity_rule_caption_5)
    phase < 0.80f -> stringResource(R.string.how_to_play_velocity_rule_caption_6)
    else -> stringResource(R.string.how_to_play_velocity_rule_caption_7)
}
