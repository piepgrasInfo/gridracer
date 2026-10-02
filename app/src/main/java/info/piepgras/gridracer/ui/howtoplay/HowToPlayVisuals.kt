package info.piepgras.gridracer.ui.howtoplay

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.ui.drawCar

/*
 * The palette and drawing primitives every "How to play" animation shares.
 *
 * These were file-private helpers inside a 1800-line `HowToPlayScreen.kt` that held the step
 * machine and all five animations at once. Splitting that file meant promoting them to
 * `internal`, which is what they always were in spirit: one grid, one set of colours and one
 * arrow control, so the five animations look like one thing rather than five.
 *
 * The colours are fixed: these animations are drawn on the light track surface whatever the
 * app's theme, matching [info.piepgras.gridracer.ui.LightTrackPalette]. The race track itself
 * follows the dark-mode setting since 1.2; the walkthroughs do not yet, and
 * `tools/tutorial-editor/web/render.js` mirrors these values, so the two would have to change
 * together.
 */

/** The track surface the walkthroughs are drawn on; the light palette's background. */
internal val AnimTrackColor = Color(0xFFFAFAFA)
/** Grid dot color, matching the in-race grid. */
internal val AnimGridColor = Color(0xFFBDBDBD)
/** Wall color. */
internal val AnimWallColor = Color(0xFF212121)
/** Finish line color. */
internal val AnimFinishColor = Color(0xFF6D4C41)
/** Legal-move highlight color — the green grid point pickers. */
internal val AnimLegalColor = Color(0xFF00C853)
/** Selected-move highlight (brighter green ring). */
internal val AnimSelectedColor = Color(0xFF2E7D32)
/** Crash X color. */
internal val AnimCrashColor = Color(0xFFD32F2F)
/** Oil spill color. */
internal val AnimOilColor = Color(0xFF37474F)
/** Nails color. */
internal val AnimNailsColor = Color(0xFFFF6F00)
/** The demo car's color — red, like player 0. */
internal val AnimCarColor = Color(0xFFE53935)
/** The gravel band outside the outer wall; the light palette's gravel. */
internal val AnimGravelColor = Color(0xFFE6DAB8)
/** The barrier at the far edge of the gravel; the light palette's barrier. */
internal val AnimBarrierColor = Color(0xFF8D6E63)

/**
 * Draws a coarse grid of dots covering the canvas, like the in-race track background. The
 * [gridCols] x [gridRows] grid is centered in the canvas with a margin.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGridDots(
    cellSize: Float,
    originX: Float,
    originY: Float,
    gridCols: Int,
    gridRows: Int,
) {
    for (gx in 0 until gridCols) {
        for (gy in 0 until gridRows) {
            val cx = originX + gx * cellSize
            val cy = originY + gy * cellSize
            drawCircle(AnimGridColor, radius = cellSize * 0.05f, center = Offset(cx, cy))
        }
    }
}

/** Converts a grid (gx, gy) to a canvas center offset. */
internal fun gridToOffset(gx: Int, gy: Int, cellSize: Float, originX: Float, originY: Float): Offset =
    Offset(originX + gx * cellSize, originY + gy * cellSize)

/**
 * Draws the green grid point pickers — the legal-move highlights the game shows before a car
 * moves. Each [moves] entry is a grid point; a green filled circle is drawn at each, with a
 * brighter ring on the [selected] one to show which move will be / was picked.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMovePickers(
    moves: List<Point>,
    selected: Point?,
    cellSize: Float,
    originX: Float,
    originY: Float,
    alpha: Float = 1f,
) {
    for (move in moves) {
        val c = gridToOffset(move.x, move.y, cellSize, originX, originY)
        drawCircle(
            AnimLegalColor.copy(alpha = 0.55f * alpha),
            radius = cellSize * 0.32f,
            center = c,
        )
    }
    if (selected != null) {
        val c = gridToOffset(selected.x, selected.y, cellSize, originX, originY)
        drawCircle(
            AnimSelectedColor.copy(alpha = alpha),
            radius = cellSize * 0.32f,
            center = c,
            style = Stroke(width = cellSize * 0.06f),
        )
    }
}

// ----- Curved arrow overlay -----

/** Which way the arrow steps the animation. */
internal enum class ArrowDirection { FORWARD, BACKWARD }

/** Fixed size for the curved arrow tappable icons. */
private val ARROW_SIZE_DP = 48

/**
 * A curved arrow drawn as a Compose Canvas, overlaid on the animation. Tapping it steps the
 * animation forward or backward, over a semi-transparent dark circular background so it stays
 * visible against any animation. A disabled arrow (backward on the first frame) is faded.
 *
 * Uses `Modifier.clickable` rather than raw pointer input so drag/swipe gestures still reach
 * the enclosing HorizontalPager - only actual taps step the animation.
 */
@Composable
internal fun CurvedArrow(
    modifier: Modifier = Modifier,
    direction: ArrowDirection,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.25f
    Canvas(
        modifier = modifier
            .size(ARROW_SIZE_DP.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f

        // Semi-transparent dark circular background so the arrow is visible over any background.
        drawCircle(
            Color(0x99000000).copy(alpha = alpha * 0.6f),
            radius = radius * 0.85f,
            center = Offset(cx, cy),
        )

        val arrowColor = Color.White.copy(alpha = alpha)
        val stroke = radius * 0.10f

        // 36° arc (a tenth of a circle). The chord spans 60% of the icon width, centered
        // horizontally at cy. The circle's center sits below the chord so the arc curves
        // gently upward. Compose arcTo: 0° at 3 o'clock, angles increase clockwise (y-down).
        //
        // Forward:  start 252° (left end),  sweep +36° clockwise  → end 288° (right end)
        // Backward: start 288° (right end), sweep -36° counter-cw  → end 252° (left end)
        val halfAngleRad = Math.toRadians(18.0) // half of 36°
        val chord = w * 0.6f
        val arcRadius = (chord / 2f) / kotlin.math.sin(halfAngleRad).toFloat()
        val arcCenterY = cy + arcRadius * kotlin.math.cos(halfAngleRad).toFloat()
        val arcRect = Rect(
            cx - arcRadius,
            arcCenterY - arcRadius,
            cx + arcRadius,
            arcCenterY + arcRadius,
        )
        val (startAngle, sweepAngle) = if (direction == ArrowDirection.FORWARD) {
            252f to 36f
        } else {
            288f to -36f
        }

        val path = Path()
        path.arcTo(arcRect, startAngle, sweepAngle, forceMoveTo = true)
        drawPath(path, arrowColor, style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))

        // Arrowhead at the arc endpoint, aligned with the tangent direction.
        // Forward tangent at 288°: (cos 18°, sin 18°) — right, slightly down.
        // Backward tangent at 252°: (cos 162°, sin 162°) — left, slightly down.
        val tipAngleDeg = if (direction == ArrowDirection.FORWARD) 18f else 162f
        val tipAngleRad = Math.toRadians(tipAngleDeg.toDouble()).toFloat()
        val tipX = if (direction == ArrowDirection.FORWARD) cx + chord / 2f else cx - chord / 2f
        val tip = Offset(tipX, cy)
        val dirX = kotlin.math.cos(tipAngleRad)
        val dirY = kotlin.math.sin(tipAngleRad)
        val arrowSize = radius * 0.3f
        val backX = -dirX * arrowSize
        val backY = -dirY * arrowSize
        val perpX = -dirY * arrowSize * 0.5f
        val perpY = dirX * arrowSize * 0.5f
        val arrowPath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x + backX + perpX, tip.y + backY + perpY)
            lineTo(tip.x + backX - perpX, tip.y + backY - perpY)
            close()
        }
        drawPath(arrowPath, arrowColor)
    }
}

// ----- Shared scene helpers for the animations -----

/** The easing every car movement in the walkthrough uses, so the five rules move alike. */
internal val AnimMoveEasing: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** 0 until [start], 1 from [end] on, linear in between. Safe for an empty interval. */
internal fun ramp(phase: Float, start: Float, end: Float): Float = when {
    phase <= start -> 0f
    phase >= end -> 1f
    else -> (phase - start) / (end - start)
}

/** 1 until [start], 0 from [end] on: a [ramp] running the other way. */
internal fun rampDown(phase: Float, start: Float, end: Float): Float = 1f - ramp(phase, start, end)

/** Linear interpolation between two canvas points. */
internal fun lerp(from: Offset, to: Offset, t: Float): Offset =
    Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)

/**
 * Where a car driving from [from] to [to] during the phase interval [start]..[end] is at
 * [phase]: at [from] before the interval, at [to] after it, eased in between.
 */
internal fun carAlong(from: Offset, to: Offset, phase: Float, start: Float, end: Float): Offset =
    lerp(from, to, AnimMoveEasing.transform(ramp(phase, start, end)))

/**
 * The dotted coarse grid of one animation: [cols] x [rows] points centred in the canvas.
 * Scenes are described in grid coordinates - the same [Point]s the engine uses - so a
 * walkthrough can be checked against the rules by hand.
 *
 * @property cols Grid points across.
 * @property rows Grid points down.
 * @property cellSize Distance between neighbouring points, in pixels.
 * @property originX Canvas x of grid point (0, 0).
 * @property originY Canvas y of grid point (0, 0).
 */
internal class AnimGrid(
    val cols: Int,
    val rows: Int,
    val cellSize: Float,
    val originX: Float,
    val originY: Float,
) {
    /** The canvas position of grid point [p]. */
    fun offset(p: Point): Offset = gridToOffset(p.x, p.y, cellSize, originX, originY)

    /** A canvas position between grid points, for lines and hazard zones. */
    fun offset(x: Float, y: Float): Offset = Offset(originX + x * cellSize, originY + y * cellSize)

    /** The nine candidate points around [coast] - `GameEngine.candidateMoves`, in its order. */
    fun candidates(coast: Point): List<Point> = buildList {
        for (dx in -1..1) for (dy in -1..1) add(Point(coast.x + dx, coast.y + dy))
    }
}

/**
 * How a scene picks its cell size. [FIT] takes the largest cell that keeps a margin all round;
 * [MIN_DIMENSION] divides the canvas's short side by a fixed number, which is what rule 1 does
 * to keep its 5x3 scene large on a wide canvas.
 */
internal enum class AnimCellMode { FIT, MIN_DIMENSION }

/**
 * Where the grid sits. [POINTS] centres the grid *points* (the outermost dots touch the margin);
 * [CELLS] centres one more cell than there are gaps, which leaves the extra half-cell of room
 * rules 1 and 2 need for a wall and a status frame drawn outside the dots.
 */
internal enum class AnimOriginMode { POINTS, CELLS }

/**
 * Paints the track surface and a [cols] x [rows] dotted grid on it, and returns the grid for
 * placing everything else. The defaults are the [AnimCellMode.FIT] / [AnimOriginMode.POINTS]
 * layout rules 3-5 use; the other combinations exist so the tutorial editor
 * (`tools/tutorial-editor/`) can reproduce every scene's exact framing.
 */
internal fun DrawScope.drawAnimGrid(
    cols: Int,
    rows: Int,
    cellMode: AnimCellMode = AnimCellMode.FIT,
    cellScale: Float = 0.85f,
    cellDivisor: Float = 7f,
    originMode: AnimOriginMode = AnimOriginMode.POINTS,
): AnimGrid {
    drawRect(AnimTrackColor)
    val cellSize = when (cellMode) {
        AnimCellMode.FIT -> minOf(size.width / cols, size.height / rows) * cellScale
        AnimCellMode.MIN_DIMENSION -> size.minDimension / cellDivisor
    }
    val spanX = if (originMode == AnimOriginMode.POINTS) cols - 1 else cols
    val spanY = if (originMode == AnimOriginMode.POINTS) rows - 1 else rows
    val grid = AnimGrid(
        cols = cols,
        rows = rows,
        cellSize = cellSize,
        originX = (size.width - cellSize * spanX) / 2f,
        originY = (size.height - cellSize * spanY) / 2f,
    )
    drawGridDots(cellSize, grid.originX, grid.originY, cols, rows)
    return grid
}

/** One segment of the demo car's trail, in the in-race trail style. */
internal fun DrawScope.drawTrailSegment(from: Offset, to: Offset, cellSize: Float, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawLine(AnimCarColor.copy(alpha = 0.45f * alpha), from, to, strokeWidth = cellSize * 0.08f)
}

/**
 * The dashed coast projection: from the car to the point a zero-acceleration move would
 * reach, which is where the nine candidates are centred.
 */
internal fun DrawScope.drawProjector(
    from: Offset,
    to: Offset,
    cellSize: Float,
    alpha: Float,
    color: Color = AnimLegalColor,
) {
    if (alpha <= 0f) return
    drawLine(
        color.copy(alpha = 0.7f * alpha),
        from,
        to,
        strokeWidth = cellSize * 0.05f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(cellSize * 0.15f, cellSize * 0.1f), 0f),
        cap = StrokeCap.Round,
    )
}

/** The green pickers for the points the game offers this turn. */
internal fun DrawScope.drawOfferedPickers(points: List<Point>, grid: AnimGrid, alpha: Float) {
    if (alpha <= 0f) return
    drawMovePickers(points, null, grid.cellSize, grid.originX, grid.originY, alpha)
}

/**
 * The small, dim marks for candidate points the game withholds: rule 2's "would cross the
 * wall" points, and the moves a hazard takes away in rules 4 and 5.
 */
internal fun DrawScope.drawWithheldPickers(points: List<Point>, grid: AnimGrid, alpha: Float) {
    if (alpha <= 0f) return
    for (p in points) {
        drawCircle(AnimLegalColor.copy(alpha = 0.2f * alpha), radius = grid.cellSize * 0.14f, center = grid.offset(p))
    }
}

/** The ring on the point the walkthrough is about to drive to. */
internal fun DrawScope.drawPickRing(p: Point, grid: AnimGrid, alpha: Float) {
    if (alpha <= 0f) return
    drawCircle(
        AnimSelectedColor.copy(alpha = alpha),
        radius = grid.cellSize * 0.32f,
        center = grid.offset(p),
        style = Stroke(width = grid.cellSize * 0.06f),
    )
}

/**
 * A hazard zone as a smooth blob through [corners], filled the way TrackCanvas fills a
 * `TrackModifier` area: translucent, so it reads as part of the surface rather than a wall.
 */
internal fun DrawScope.drawHazardZone(corners: List<Offset>, color: Color, alpha: Float = 1f) {
    if (alpha <= 0f || corners.size < 3) return
    fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val n = corners.size
    val path = Path().apply {
        val start = mid(corners[n - 1], corners[0])
        moveTo(start.x, start.y)
        for (i in 0 until n) {
            val next = mid(corners[i], corners[(i + 1) % n])
            quadraticTo(corners[i].x, corners[i].y, next.x, next.y)
        }
        close()
    }
    drawPath(path, color.copy(alpha = 0.45f * alpha))
}

/** The demo car, drawn the way the race draws it. */
internal fun DrawScope.drawDemoCar(center: Offset, cellSize: Float, heading: Point, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawCar(
        center = center,
        cellSize = cellSize * 0.9f,
        color = AnimCarColor.copy(alpha = alpha),
        direction = heading,
        trackFinishDirection = Point(1, 0),
    )
}

// ----- Scene primitives shared with the tutorial editor -----
//
// Everything below is what `tools/tutorial-editor/` generates calls to. Each helper is one
// element type in the editor's scene format, so a scene authored in the GUI and the Kotlin
// generated from it draw the same thing without the generator having to emit any geometry.

/** A wall segment, in grid units (floats, so it can sit between grid points like rule 2's does). */
internal fun DrawScope.drawAnimWall(
    grid: AnimGrid,
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    widthFactor: Float = 0.15f,
    alpha: Float = 1f,
) {
    if (alpha <= 0f) return
    drawLine(
        AnimWallColor.copy(alpha = alpha),
        grid.offset(fromX, fromY),
        grid.offset(toX, toY),
        strokeWidth = grid.cellSize * widthFactor,
    )
}

/**
 * A gravel band along the wall from ([fromX], [fromY]) to ([toX], [toY]), [depth] grid units deep
 * on the right-hand side of that direction (on screen, y down: a wall drawn left to right gets its
 * gravel below it), with the barrier along its far edge - the run-off area `TrackCanvas` draws
 * outside a track's outer wall. The grid dots inside the band are drawn again on top of it,
 * because a car may stop on them.
 */
internal fun DrawScope.drawAnimGravel(
    grid: AnimGrid,
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    depth: Float = 2f,
    alpha: Float = 1f,
) {
    if (alpha <= 0f) return
    val dx = toX - fromX
    val dy = toY - fromY
    val length = kotlin.math.hypot(dx, dy)
    if (length == 0f) return
    // Unit normal on the right-hand side, in grid units.
    val nx = -dy / length
    val ny = dx / length
    val a = grid.offset(fromX, fromY)
    val b = grid.offset(toX, toY)
    val farA = grid.offset(fromX + nx * depth, fromY + ny * depth)
    val farB = grid.offset(toX + nx * depth, toY + ny * depth)
    val band = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(farB.x, farB.y)
        lineTo(farA.x, farA.y)
        close()
    }
    drawPath(band, AnimGravelColor.copy(alpha = alpha))
    drawLine(AnimBarrierColor.copy(alpha = alpha), farA, farB, strokeWidth = grid.cellSize * 0.1f)
    for (gx in 0 until grid.cols) {
        for (gy in 0 until grid.rows) {
            val along = ((gx - fromX) * dx + (gy - fromY) * dy) / length
            val across = (gx - fromX) * nx + (gy - fromY) * ny
            if (along in 0f..length && across in 0f..depth) {
                drawCircle(AnimGridColor.copy(alpha = alpha), radius = grid.cellSize * 0.05f, center = grid.offset(gx.toFloat(), gy.toFloat()))
            }
        }
    }
}

/** The finish line down column [x] from row [y0] to row [y1], as `TrackCanvas` draws one. */
internal fun DrawScope.drawAnimFinishLine(
    grid: AnimGrid,
    x: Float,
    y0: Float,
    y1: Float,
    widthFactor: Float = 0.12f,
    alpha: Float = 1f,
) {
    if (alpha <= 0f) return
    drawLine(
        AnimFinishColor.copy(alpha = alpha),
        grid.offset(x, y0),
        grid.offset(x, y1),
        strokeWidth = grid.cellSize * widthFactor,
    )
}

/**
 * The finish direction arrow, as `TrackCanvas.drawFinishArrow` draws it. [direction] is +1 for
 * an arrow pointing along +x and -1 for one pointing back along -x.
 */
internal fun DrawScope.drawAnimFinishArrow(mid: Offset, cell: Float, direction: Int = 1, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val tipX = mid.x + direction * cell * 0.45f
    val baseX = mid.x - direction * cell * 0.45f
    val arrow = Path().apply {
        moveTo(tipX, mid.y)
        lineTo(baseX, mid.y - cell * 0.35f)
        lineTo(baseX, mid.y + cell * 0.35f)
        close()
    }
    drawPath(arrow, Color.White.copy(alpha = alpha))
    drawPath(arrow, Color.Black.copy(alpha = alpha), style = Stroke(width = cell * 0.03f))
}

/**
 * A copy of the in-race player status frame (RaceScreen's `PlayerInfoBox`) in the top-left
 * corner: a translucent box in the player's colour with the car avatar top-left and the lap
 * counter bottom-left, crossed out in red once the player has crashed. The lap counter
 * reads "lap you are on / laps in the race", as it does in the race.
 */
internal fun DrawScope.drawStatusFrame(grid: AnimGrid, lapText: String, crashed: Boolean, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val h = grid.cellSize * 1.1f
    val w = h * 1.414f
    val left = grid.originX - grid.cellSize * 0.5f
    val top = grid.originY - grid.cellSize * 0.5f
    val topLeft = Offset(left, top)
    val box = Size(w, h)
    drawRect(AnimCarColor.copy(alpha = 0.25f * alpha), topLeft, box)
    drawRect(AnimCarColor.copy(alpha = alpha), topLeft, box, style = Stroke(width = h * 0.05f))

    val carCell = h * 0.72f
    val pad = h * 0.06f
    drawCar(
        center = Offset(left + carCell / 2f + pad, top + carCell / 2f + pad - carCell * 0.16f),
        cellSize = carCell,
        color = AnimCarColor.copy(alpha = alpha),
        direction = Point(1, 0),
        trackFinishDirection = Point(1, 0),
    )
    drawIntoCanvas {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb((alpha * 255f).toInt().coerceIn(0, 255), 0x21, 0x21, 0x21)
            textSize = h * 0.3f
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        it.nativeCanvas.drawText(lapText, left + w * 0.05f, top + h * 0.92f, paint)
    }
    if (crashed) {
        val stroke = h * 0.08f
        val crash = AnimCrashColor.copy(alpha = alpha)
        drawLine(crash, topLeft, Offset(left + w, top + h), strokeWidth = stroke)
        drawLine(crash, Offset(left + w, top), Offset(left, top + h), strokeWidth = stroke)
    }
}

/**
 * A free-standing caption drawn on the scene itself, anchored at a grid position. Editor-only
 * scenes use it to label a piece of geometry the side panel's caption cannot point at; the five
 * built-in walkthroughs do not use it.
 */
internal fun DrawScope.drawAnimLabel(
    grid: AnimGrid,
    x: Float,
    y: Float,
    text: String,
    sizeFactor: Float = 0.3f,
    color: Color = AnimWallColor,
    alpha: Float = 1f,
    centered: Boolean = true,
) {
    if (alpha <= 0f || text.isEmpty()) return
    val at = grid.offset(x, y)
    drawIntoCanvas {
        val paint = android.graphics.Paint().apply {
            this.color = color.copy(alpha = color.alpha * alpha).toArgb()
            textSize = grid.cellSize * sizeFactor
            isAntiAlias = true
            textAlign = if (centered) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        it.nativeCanvas.drawText(text, at.x, at.y, paint)
    }
}

// ----- Car motion -----

/**
 * One leg of a demo car's journey: it sits at [from] until [start], eases to [to] by [end], and
 * stays there until the next move begins. [shimmy] adds a damped wobble across the direction of
 * travel, in cell widths - a car that is skidding rather than steering (rule 4's oil spill).
 *
 * @property from Where the car sits before the move.
 * @property to Where it ends up.
 * @property start Animation phase, 0 to 1, at which it sets off.
 * @property end Animation phase at which it arrives.
 * @property heading Which way the car points while making this move.
 * @property shimmyCycles How many wobbles the shimmy makes before it dies out.
 */
internal class AnimMove(
    val from: Offset,
    val to: Offset,
    val start: Float,
    val end: Float,
    val heading: Point,
    /** Width of the skid wobble across the direction of travel, in cell widths; 0 for none. */
    val shimmy: Float = 0f,
    val shimmyCycles: Float = 3f,
)

/**
 * Where a car is and which way it points at one phase.
 *
 * @property position Where the car is on the canvas.
 * @property heading Which way it points.
 */
internal class AnimCar(val position: Offset, val heading: Point)

/**
 * The car's state at [phase]: at [rest] facing [restHeading] until the first move starts, then
 * on whichever move has most recently begun. Because a move holds its end position until the
 * next one starts, a car described this way never teleports between legs.
 */
internal fun animCar(
    rest: Offset,
    restHeading: Point,
    moves: List<AnimMove>,
    phase: Float,
    cellSize: Float,
): AnimCar {
    val active = moves.lastOrNull { phase >= it.start } ?: return AnimCar(rest, restHeading)
    val t = ramp(phase, active.start, active.end)
    val along = lerp(active.from, active.to, AnimMoveEasing.transform(t))
    if (active.shimmy <= 0f) return AnimCar(along, active.heading)
    val dx = active.to.x - active.from.x
    val dy = active.to.y - active.from.y
    val length = kotlin.math.hypot(dx, dy)
    if (length <= 0f) return AnimCar(along, active.heading)
    // A damped shimmy across the direction of travel: the wobble dies out as the car arrives.
    val amount =
        kotlin.math.sin(t * kotlin.math.PI.toFloat() * active.shimmyCycles) * (1f - t) * cellSize * active.shimmy
    return AnimCar(Offset(along.x - dy / length * amount, along.y + dx / length * amount), active.heading)
}

/**
 * The trail segment a [move] leaves: nothing before it starts, growing behind the car while it
 * runs, and the full segment once it is over.
 */
internal fun DrawScope.drawFollowTrail(
    move: AnimMove,
    carPosition: Offset,
    phase: Float,
    cellSize: Float,
    alpha: Float = 1f,
) {
    if (phase < move.start) return
    drawTrailSegment(move.from, if (phase <= move.end) carPosition else move.to, cellSize, alpha)
}
