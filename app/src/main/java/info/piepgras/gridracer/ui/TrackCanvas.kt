package info.piepgras.gridracer.ui

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.toSize
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.ModifierType
import info.piepgras.gridracer.engine.PlayerState
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.TrackGuideArrow
import info.piepgras.gridracer.engine.TrackModifier
import info.piepgras.gridracer.engine.Wall
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.roundToInt

// The track's colours live in [TrackPalette] - they follow the dark-mode setting, so they
// cannot be constants here. Everything below is geometry, which does not.

/**
 * Coarse grid dot radius, as a fraction of a cell.
 *
 * Was 0.06, which is about a tenth of the gap between two dots: legible on a desk, not on a
 * phone at arm's length with a car and a trail crossing it. The dots are the board - they are
 * the only thing saying where a move may land - so they are drawn nearer the size of the
 * legal-move markers ([LEGAL_MOVE_RADIUS_FRACTION]) than of a rounding error.
 */
private const val GRID_DOT_RADIUS_FRACTION = 0.1f

/** Radius of the "you may move here" markers, as a fraction of a cell. */
private const val LEGAL_MOVE_RADIUS_FRACTION = 0.22f

/** Fine (sub-grid) dot radius in the editor, as a fraction of a *fine* cell. */
private const val FINE_GRID_DOT_RADIUS_FRACTION = 0.05f

private fun colorFor(palette: TrackPalette, type: ModifierType): Color = when (type) {
    ModifierType.OIL_SPILL -> palette.oilSpill
    ModifierType.NAILS -> palette.nails
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 10f

/**
 * Every thumbnail is this shape regardless of the track's own proportions, so a row of
 * picker cards lines up instead of stepping up and down with each track's aspect ratio.
 */
private const val PREVIEW_ASPECT_RATIO = 16f / 9f

/** Visible enough to read the racing direction at a glance, faint enough not to hide the track. */
private const val DIRECTION_ARROW_ALPHA = 0.31f

/** How much wider than the shaft the head is, so it reads as a head at low opacity. */
private const val DIRECTION_ARROW_HEAD_FLARE = 2.6f

/** Head length as a multiple of its half-width - a head much longer than this looks like a shaft. */
private const val DIRECTION_ARROW_HEAD_ASPECT = 1.6f

/** The head may not eat more than this share of the arrow, or there is no shaft left to see. */
private const val DIRECTION_ARROW_MAX_HEAD_FRACTION = 0.55f

/** Barb tips flare this much wider than the head base, so the barbs read as thick barbs. */
private const val DIRECTION_ARROW_BARB_FLARE = 1.3f

/** How far back along the shaft the barb tips sweep, as a fraction of the head length. */
private const val DIRECTION_ARROW_BARB_NOTCH = 0.4f

/**
 * Static, non-interactive track thumbnail - used by the track picker and browser. The
 * track is letterboxed into a fixed 16:9 frame (rather than the frame being shaped by
 * the track) so it always appears whole and every card is the same size, and it carries
 * an arrow along the racing line showing which way this orientation of the track is raced.
 */
@Composable
fun TrackPreview(track: Track, modifier: Modifier = Modifier) {
    // Ray-casting the corridor is far too much work to redo on every frame, and it only
    // depends on the track, so it is worked out once and reused while this card is on screen.
    val guideArrow = remember(track) { TrackGuideArrow.forTrack(track) }
    val bounds = remember(track) { ViewBounds.of(track) }
    val palette = LocalTrackPalette.current
    Canvas(modifier = modifier.aspectRatio(PREVIEW_ASPECT_RATIO)) {
        val cellSize = fitCellSize(size, bounds)
        val origin = centeredOrigin(size, bounds, cellSize)
        val fineCellSize = cellSize / track.subdivision
        drawTrackGeometry(track, bounds, cellSize, palette) { p -> origin + Offset(p.x * cellSize, p.y * cellSize) }
        guideArrow?.let { arrow ->
            drawDirectionArrow(palette, arrow, fineCellSize) { p ->
                origin + Offset(p.x * fineCellSize, p.y * fineCellSize)
            }
        }
    }
}

/**
 * The racing-line arrow: a shaft that follows the corridor forward from the finish line
 * and a barbed head at its far end. Drawn along the track rather than across the middle of
 * the thumbnail so it says where forward goes and not merely which way it faces, and kept
 * inside the corridor's own width so it never straddles a wall.
 *
 * The whole arrow - shaft and head together - is one filled [Path], so head and shaft are a
 * single consistent-color body with no overlap (a stroked shaft under a filled head used to
 * double the alpha where they met and show a seam). The head is a barbed arrowhead: its base
 * is wider than the shaft ([DIRECTION_ARROW_HEAD_FLARE]) and the rear corners sweep back and
 * out into thick barbs ([DIRECTION_ARROW_BARB_FLARE], [DIRECTION_ARROW_BARB_NOTCH]).
 */
private fun DrawScope.drawDirectionArrow(
    palette: TrackPalette,
    arrow: TrackGuideArrow.GuideArrow,
    fineCellSize: Float,
    fineOffsetOf: (Point) -> Offset,
) {
    val points = arrow.centreLine.map(fineOffsetOf)
    if (points.size < 2) return
    val color = palette.wall.copy(alpha = DIRECTION_ARROW_ALPHA)

    val segmentLengths = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
    val totalLength = segmentLengths.sum()
    if (totalLength <= 0f) return

    // The arrow takes the width the corridor allows, and its head is sized from that width -
    // capped by length so a short run on a wide track keeps a visible shaft instead of
    // becoming a lone triangle.
    val headHalfWidth = arrow.halfWidthFine.toFloat() * fineCellSize
    val shaftWidth = headHalfWidth * 2f / DIRECTION_ARROW_HEAD_FLARE
    if (shaftWidth <= 0.5f) return
    val shaftHalfWidth = shaftWidth / 2f
    val headLength = minOf(
        headHalfWidth * DIRECTION_ARROW_HEAD_ASPECT,
        totalLength * DIRECTION_ARROW_MAX_HEAD_FRACTION,
    )

    val shaftEnd = totalLength - headLength
    val shaft = pointsUpTo(points, segmentLengths, shaftEnd)
    if (shaft.size < 2) return

    val tip = points.last()
    val headBase = shaft.last()
    val axis = tip - headBase
    val axisLength = hypot(axis.x, axis.y)
    if (axisLength <= 0f) return
    val unit = axis / axisLength
    val perp = Offset(-unit.y, unit.x)

    // Shaft edge points: each centreline point offset by ±shaftHalfWidth along the local
    // perpendicular, so the strip follows the corridor's curve rather than being a straight
    // band. The direction at a point is the normalised sum of its two adjacent segments
    // (or the single adjacent segment at the ends).
    val leftEdge = ArrayList<Offset>(shaft.size)
    val rightEdge = ArrayList<Offset>(shaft.size)
    for (i in shaft.indices) {
        val dir = when {
            shaft.size == 1 -> axis
            i == 0 -> shaft[1] - shaft[0]
            i == shaft.lastIndex -> shaft[i] - shaft[i - 1]
            else -> (shaft[i + 1] - shaft[i - 1])
        }
        val len = hypot(dir.x, dir.y)
        if (len <= 0f) {
            leftEdge += shaft[i]
            rightEdge += shaft[i]
            continue
        }
        val p = Offset(-dir.y / len, dir.x / len)
        leftEdge += shaft[i] + p * shaftHalfWidth
        rightEdge += shaft[i] - p * shaftHalfWidth
    }

    // Head vertices. The base corners sit at headHalfWidth; the barb tips flare out to
    // barbHalfWidth and sweep back along the shaft by barbBack, giving thick barbs.
    val barbHalfWidth = headHalfWidth * DIRECTION_ARROW_BARB_FLARE
    val barbBack = headLength * DIRECTION_ARROW_BARB_NOTCH
    val leftBase = headBase + perp * headHalfWidth
    val rightBase = headBase - perp * headHalfWidth
    val leftBarb = headBase + perp * barbHalfWidth - unit * barbBack
    val rightBarb = headBase - perp * barbHalfWidth - unit * barbBack

    // Outline clockwise from the shaft start: up the left edge, out to the left barb, into
    // the left base corner, to the tip, out to the right base corner, out to the right barb,
    // back down the right edge, and closed across the shaft start.
    val path = Path().apply {
        moveTo(leftEdge[0].x, leftEdge[0].y)
        for (i in 1 until leftEdge.size) lineTo(leftEdge[i].x, leftEdge[i].y)
        lineTo(leftBase.x, leftBase.y)
        lineTo(leftBarb.x, leftBarb.y)
        lineTo(tip.x, tip.y)
        lineTo(rightBarb.x, rightBarb.y)
        lineTo(rightBase.x, rightBase.y)
        for (i in rightEdge.lastIndex downTo 0) lineTo(rightEdge[i].x, rightEdge[i].y)
        close()
    }
    drawPath(path, color)
}

/** [points] truncated to the prefix [distance] long, interpolating within the final segment. */
private fun pointsUpTo(points: List<Offset>, segmentLengths: List<Float>, distance: Float): List<Offset> {
    if (distance <= 0f) return listOf(points.first())
    val result = mutableListOf(points.first())
    var travelled = 0f
    for (i in segmentLengths.indices) {
        val segment = segmentLengths[i]
        if (travelled + segment >= distance) {
            val t = if (segment > 0f) (distance - travelled) / segment else 0f
            result.add(points[i] + (points[i + 1] - points[i]) * t)
            return result
        }
        travelled += segment
        result.add(points[i + 1])
    }
    return result
}

/**
 * The interactive race view. Fills whatever space it's given (rather than being
 * shaped by the track's own aspect ratio) and letterbox-fits the track inside it,
 * so oversized tracks never overflow off-screen. Pinch to zoom in for a closer
 * look/more precise taps, drag to pan; both are clamped so the track can't be
 * scrolled entirely out of view.
 */
@Composable
fun TrackCanvas(
    state: GameState,
    legalMoves: Set<Point>,
    onPointTapped: (Point) -> Unit,
    modifier: Modifier = Modifier,
    showStartZone: Boolean = false,
) {
    val track = state.track
    // The band can run past the grid, so the view is sized to the band, not to the grid.
    val bounds = remember(track) { ViewBounds.of(track) }
    var scale by remember(track) { mutableFloatStateOf(MIN_SCALE) }
    var panOffset by remember(track) { mutableStateOf(Offset.Zero) }
    // The semantics block is not composable, so the TalkBack text is resolved through
    // Resources rather than stringResource.
    val res = LocalResources.current
    val palette = LocalTrackPalette.current

    Canvas(
        modifier = modifier
            .clipToBounds()
            .semantics {
                contentDescription = if (legalMoves.isEmpty()) {
                    res.getString(R.string.a11y_race_track)
                } else {
                    res.getString(R.string.a11y_race_track_moves, legalMoves.size)
                }
                // A raw Canvas has no touch targets TalkBack can land on, so each legal
                // move is exposed as a named action (e.g. "Accelerate up-right to (12, 5)")
                // reachable from the accessibility actions menu instead of a precise tap.
                customActions = legalMoves.map { target ->
                    CustomAccessibilityAction(describeMove(res, state, state.currentPlayer, target)) {
                        onPointTapped(target)
                        true
                    }
                }
            }
            .pointerInput(track) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val canvasSize = size.toSize()
                    val oldCellSize = fitCellSize(canvasSize, bounds) * scale
                    val oldOrigin = centeredOrigin(canvasSize, bounds, oldCellSize) + panOffset
                    val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    val newCellSize = fitCellSize(canvasSize, bounds) * newScale

                    // Keep the point under the fingers stationary while zooming, then apply the pan delta.
                    val contentUnderCentroid = centroid - oldOrigin
                    val newOrigin = centroid + pan - contentUnderCentroid * (newCellSize / oldCellSize)

                    scale = newScale
                    panOffset = clampPan(
                        newOrigin - centeredOrigin(canvasSize, bounds, newCellSize),
                        canvasSize,
                        bounds,
                        newCellSize,
                    )
                }
            }
            .pointerInput(legalMoves, track) {
                detectTapGestures { tapPosition ->
                    val tapped = gridPointFor(tapPosition, size.toSize(), bounds, scale, panOffset)
                    if (tapped in legalMoves) onPointTapped(tapped)
                }
            },
    ) {
        val cellSize = fitCellSize(size, bounds) * scale
        val origin = centeredOrigin(size, bounds, cellSize) + panOffset
        fun offsetOf(p: Point) = origin + Offset(p.x * cellSize, p.y * cellSize)

        drawTrackGeometry(track, bounds, cellSize, palette, showStartZone = showStartZone, showBarrierCuts = true, ::offsetOf)

        state.players.forEach { player -> drawTrail(player, cellSize, ::offsetOf) }

        // Crash trail: for crashed players, draw the path from their last position to the
        // fine-grid wreck site (wall/projector intersection or collision point).
        state.players.forEach { player ->
            if (player.status != PlayerStatus.CRASHED) return@forEach
            val wreck = player.wreckPosition ?: return@forEach
            val baseColor = PlayerColors[player.id % PlayerColors.size]
            val start = offsetOf(player.position)
            val sub = track.subdivision
            val end = origin + Offset(wreck.x * cellSize / sub, wreck.y * cellSize / sub)
            drawLine(baseColor.copy(alpha = TRAIL_BASE_ALPHA), start, end, strokeWidth = cellSize * 0.08f)
        }

        legalMoves.forEach { p ->
            drawCircle(palette.legalMove, radius = cellSize * LEGAL_MOVE_RADIUS_FRACTION, center = offsetOf(p))
        }

        state.players.forEach { player ->
            val color = PlayerColors[player.id % PlayerColors.size]
            when (player.status) {
                PlayerStatus.RACING -> drawCar(
                    center = offsetOf(player.position),
                    cellSize = cellSize,
                    color = color,
                    direction = player.lastVelocity,
                    trackFinishDirection = track.finishDirection,
                )
                PlayerStatus.CRASHED -> player.wreckPosition?.let { wreck ->
                    val sub = track.subdivision
                    val wreckOffset = origin + Offset(
                        wreck.x * cellSize / sub,
                        wreck.y * cellSize / sub,
                    )
                    // Draw the player's car at the crash site, then overlay the burning wreck
                    // so the wreck reads as a flaming car in the player's color.
                    drawCar(
                        center = wreckOffset,
                        cellSize = cellSize,
                        color = color,
                        direction = player.lastVelocity,
                        trackFinishDirection = track.finishDirection,
                    )
                    drawWreck(wreckOffset, cellSize, color)
                }
                PlayerStatus.FINISHED -> { /* finished cars have left the track */ }
            }
        }
    }
}

/**
 * Describes a candidate move relative to the player's projected straight-line course
 * (the coast point [GameEngine.legalMoves] centres the nine choices on - braked, if the
 * gravel has the car) rather than relative to their absolute position - "accelerate left"
 * stays meaningful move after move, where an absolute compass direction would collide once a
 * player builds up any speed.
 */
private fun describeMove(res: Resources, state: GameState, player: PlayerState, target: Point): String {
    val projectedCenter = GameEngine.coastPoint(state, player)
    val offset = target - projectedCenter
    val label = directionLabel(res, offset)
    return if (label == null) {
        res.getString(R.string.a11y_move_coast, target.x, target.y)
    } else {
        res.getString(R.string.a11y_move_accelerate, label, target.x, target.y)
    }
}

/** Null for dead ahead (no steering), otherwise a compass label; unique per 3x3 offset. */
private fun directionLabel(res: Resources, offset: Point): String? {
    val vertical = when {
        offset.y < 0 -> res.getString(R.string.a11y_direction_up)
        offset.y > 0 -> res.getString(R.string.a11y_direction_down)
        else -> null
    }
    val horizontal = when {
        offset.x < 0 -> res.getString(R.string.a11y_direction_left)
        offset.x > 0 -> res.getString(R.string.a11y_direction_right)
        else -> null
    }
    return when {
        vertical != null && horizontal != null ->
            res.getString(R.string.a11y_direction_diagonal, vertical, horizontal)
        else -> vertical ?: horizontal
    }
}

/**
 * The part of the coarse grid a canvas shows: the track's own grid, widened to take in the gravel
 * band where the band runs past it. Coordinates can be negative.
 */
private class ViewBounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    /** Width in coarse units. */
    val width: Int get() = maxX - minX

    /** Height in coarse units. */
    val height: Int get() = maxY - minY

    companion object {
        /** The bounds that show all of [track], its gravel band included. */
        fun of(track: Track): ViewBounds {
            val view = track.gravelView ?: return ViewBounds(0, 0, track.width, track.height)
            return ViewBounds(view.minCoarse.x, view.minCoarse.y, view.maxCoarse.x, view.maxCoarse.y)
        }
    }
}

/** The cell size (px/grid-unit) at which the whole view just fits inside [canvasSize]. */
private fun fitCellSize(canvasSize: Size, bounds: ViewBounds): Float =
    minOf(canvasSize.width / bounds.width, canvasSize.height / bounds.height)

/** Converts a raw screen position into the nearest grid point, given the current fit/zoom/pan. */
private fun gridPointFor(position: Offset, canvasSize: Size, bounds: ViewBounds, scale: Float, panOffset: Offset): Point {
    val cellSize = fitCellSize(canvasSize, bounds) * scale
    val origin = centeredOrigin(canvasSize, bounds, cellSize) + panOffset
    return Point(
        ((position.x - origin.x) / cellSize).roundToInt(),
        ((position.y - origin.y) / cellSize).roundToInt(),
    )
}

/** Fine grid points along the straight line from [from] to [to] (excluding [from], including [to]).
 *  Uses Bresenham-style stepping so a fast diagonal or shallow drag still visits every
 *  fine cell the segment crosses, not just the two endpoints. */
private fun gridPointsBetween(from: Point, to: Point): List<Point> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    if (dx == 0 && dy == 0) return emptyList()

    val result = mutableListOf<Point>()
    val steps = maxOf(abs(dx), abs(dy))
    repeat(steps) { i ->
        val t = (i + 1).toDouble() / steps
        val x = (from.x + dx * t).roundToInt()
        val y = (from.y + dy * t).roundToInt()
        result.add(Point(x, y))
    }
    return result
}

/** Where grid point (0,0) lands when the view is centred in [canvasSize] at [cellSize], before panning. */
private fun centeredOrigin(canvasSize: Size, bounds: ViewBounds, cellSize: Float): Offset = Offset(
    (canvasSize.width - bounds.width * cellSize) / 2f - bounds.minX * cellSize,
    (canvasSize.height - bounds.height * cellSize) / 2f - bounds.minY * cellSize,
)

/** Keeps the track roughly in view: no panning once it already fits on an axis, otherwise no dragging past its edges. */
private fun clampPan(pan: Offset, canvasSize: Size, bounds: ViewBounds, cellSize: Float): Offset {
    val viewWidth = bounds.width * cellSize
    val viewHeight = bounds.height * cellSize
    // Where the view's top-left corner sits on screen before panning.
    val centered = centeredOrigin(canvasSize, bounds, cellSize)
    val left = centered.x + bounds.minX * cellSize
    val top = centered.y + bounds.minY * cellSize

    val clampedX = if (viewWidth <= canvasSize.width) {
        0f
    } else {
        (left + pan.x).coerceIn(canvasSize.width - viewWidth, 0f) - left
    }
    val clampedY = if (viewHeight <= canvasSize.height) {
        0f
    } else {
        (top + pan.y).coerceIn(canvasSize.height - viewHeight, 0f) - top
    }
    return Offset(clampedX, clampedY)
}

private fun DrawScope.drawTrackGeometry(
    track: Track,
    bounds: ViewBounds,
    cellSize: Float,
    palette: TrackPalette,
    showStartZone: Boolean = false,
    showBarrierCuts: Boolean = false,
    offsetOf: (Point) -> Offset,
) {
    drawRect(palette.background, size = size)

    // Walls/finish line/modifiers are authored on a grid track.subdivision times finer than
    // the coarse points offsetOf expects (see Track.subdivision) - never shown directly, only
    // used here to place this geometry more precisely than the visible grid dots.
    val origin = offsetOf(Point(0, 0))
    val fineCellSize = cellSize / track.subdivision
    fun fineOffsetOf(p: Point) = origin + Offset(p.x * fineCellSize, p.y * fineCellSize)

    // The gravel band goes down first, so the grid dots a car can stop on show on top of it.
    // Drawn whether or not this race has the gravel trap on - only the rules change.
    track.gravelView?.let { view ->
        drawGravelBand(view, palette, fineCellSize, showBarrierCuts, ::fineOffsetOf)
    }

    for (x in bounds.minX..bounds.maxX) {
        for (y in bounds.minY..bounds.maxY) {
            drawCircle(
                palette.gridDot,
                radius = cellSize * GRID_DOT_RADIUS_FRACTION,
                center = offsetOf(Point(x, y)),
            )
        }
    }

    // Drawn before walls/cars so hazards read as part of the track surface, not on top of it.
    track.modifiers.forEach { modifier -> drawModifierZone(modifier, palette, ::fineOffsetOf) }

    if (showStartZone) drawStartZone(track, palette, ::fineOffsetOf, cellSize)

    track.walls.forEach { wall ->
        drawLine(palette.wall, fineOffsetOf(wall.from), fineOffsetOf(wall.to), strokeWidth = cellSize * 0.18f)
    }

    drawLine(
        palette.finishLine,
        fineOffsetOf(track.finishLine.from),
        fineOffsetOf(track.finishLine.to),
        strokeWidth = cellSize * 0.12f,
    )

    drawFinishArrow(track, palette, ::fineOffsetOf, cellSize)
}

/**
 * The gravel band outside the outer wall: the outer loop stroked twice its band width with round
 * joins, clipped to the outside of the loop. A round-joined stroke of half-width d is exactly the
 * set of points within d of the loop, so what is drawn is what the rules call gravel. The barrier
 * is the rim of a slightly wider stroke underneath; [showBarrierCuts] adds the places the band is
 * cut - across narrow notches and in line with the finish line - which a thumbnail leaves out.
 */
private fun DrawScope.drawGravelBand(
    view: info.piepgras.gridracer.engine.GravelView,
    palette: TrackPalette,
    fineCellSize: Float,
    showBarrierCuts: Boolean,
    fineOffsetOf: (Point) -> Offset,
) {
    val loop = view.outerLoop
    if (loop.size < 3) return
    val path = Path().apply {
        val first = fineOffsetOf(loop[0])
        moveTo(first.x, first.y)
        for (i in 1 until loop.size) {
            val p = fineOffsetOf(loop[i])
            lineTo(p.x, p.y)
        }
        close()
    }
    val band = view.bandWidth * fineCellSize
    val barrier = maxOf(fineCellSize * 0.6f, 1.5f)
    clipPath(path, clipOp = ClipOp.Difference) {
        drawPath(path, palette.gravelBarrier, style = Stroke(width = 2 * band + barrier, join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawPath(path, palette.gravel, style = Stroke(width = 2 * band - barrier, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
    if (showBarrierCuts) {
        for (mark in view.barrierMarks) {
            val center = fineOffsetOf(mark)
            drawRect(
                palette.gravelBarrier,
                topLeft = Offset(center.x - fineCellSize / 2f, center.y - fineCellSize / 2f),
                size = Size(fineCellSize, fineCellSize),
            )
        }
    }
}

/**
 * A translucent capsule (stadium) around the finish line, exactly [Track.startZoneDepth]
 * coarse units deep - the same box the builder draws start slots from, so the shaded area
 * really is where a car can be placed. The depth is per-track: the editor deepens the box
 * until it holds a full grid of cars. Drawn during the placement phase.
 */
private fun DrawScope.drawStartZone(
    track: Track,
    palette: TrackPalette,
    fineOffsetOf: (Point) -> Offset,
    cellSize: Float,
) {
    val a = fineOffsetOf(track.finishLine.from)
    val b = fineOffsetOf(track.finishLine.to)
    val radius = track.startZoneDepth.toFloat() * cellSize
    val color = palette.finishLine.copy(alpha = 0.15f)

    val along = b - a
    val length = along.getDistance()
    if (length == 0f) {
        drawCircle(color, radius = radius, center = a)
        return
    }
    val unit = along / length
    val perp = Offset(-unit.y, unit.x)

    // Filled rectangle spanning the line, widened by radius on each side, capped with disks.
    val rect = Path().apply {
        moveTo(a.x + perp.x * radius, a.y + perp.y * radius)
        lineTo(b.x + perp.x * radius, b.y + perp.y * radius)
        lineTo(b.x - perp.x * radius, b.y - perp.y * radius)
        lineTo(a.x - perp.x * radius, a.y - perp.y * radius)
        close()
    }
    drawPath(rect, color)
    drawCircle(color, radius = radius, center = a)
    drawCircle(color, radius = radius, center = b)
}

/** An arrowhead at the finish line's midpoint pointing along [Track.finishDirection]. */
private fun DrawScope.drawFinishArrow(
    track: Track,
    palette: TrackPalette,
    fineOffsetOf: (Point) -> Offset,
    cellSize: Float,
) {
    val dirX = track.finishDirection.x.toFloat()
    val dirY = track.finishDirection.y.toFloat()
    val dirLength = hypot(dirX, dirY)
    if (dirLength == 0f) return
    val unit = Offset(dirX / dirLength, dirY / dirLength)
    val perp = Offset(-unit.y, unit.x)

    val mid = (fineOffsetOf(track.finishLine.from) + fineOffsetOf(track.finishLine.to)) / 2f
    val length = cellSize * 0.9f
    val halfWidth = cellSize * 0.35f
    val tip = mid + unit * (length / 2f)
    val baseCenter = mid - unit * (length / 2f)
    val baseLeft = baseCenter + perp * halfWidth
    val baseRight = baseCenter - perp * halfWidth

    val arrow = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseLeft.x, baseLeft.y)
        lineTo(baseRight.x, baseRight.y)
        close()
    }
    drawPath(arrow, palette.finishArrow)
    drawPath(arrow, Color.Black, style = Stroke(width = cellSize * 0.03f))
}

/** A drivable hazard zone, filled translucently so it reads as part of the track surface rather than a wall. */
private fun DrawScope.drawModifierZone(
    modifier: TrackModifier,
    palette: TrackPalette,
    fineOffsetOf: (Point) -> Offset,
) {
    val points = modifier.area
    if (points.size < 3) return
    val path = Path().apply {
        moveTo(fineOffsetOf(points[0]).x, fineOffsetOf(points[0]).y)
        for (i in 1 until points.size) {
            val p = fineOffsetOf(points[i])
            lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(path, colorFor(palette, modifier.type).copy(alpha = palette.modifierZoneAlpha))
}

/** How many recent steps of a player's trail are drawn - older steps vanish. */
internal const val TRAIL_VISIBLE_STEPS = 5

/** Opacity of the freshest segment; everything behind it is a fraction of this. */
private const val TRAIL_BASE_ALPHA = 0.45f

/**
 * How solid a trail segment is, given how many moves ago it was made ([age] 0 is the move
 * just played). Fades linearly back to nothing over [TRAIL_VISIBLE_STEPS].
 *
 * Keyed on age rather than on position within the visible window on purpose: the shipped
 * version faded from the oldest *drawn* segment, which meant a player's very first move -
 * the only segment there is - was both the oldest and the newest, and got drawn at zero
 * alpha. The trail only appeared from the second move onwards.
 */
internal fun trailAlphaFor(age: Int): Float =
    if (age < 0 || age >= TRAIL_VISIBLE_STEPS) 0f
    else TRAIL_BASE_ALPHA * (1f - age.toFloat() / TRAIL_VISIBLE_STEPS)

private fun DrawScope.drawTrail(player: PlayerState, cellSize: Float, offsetOf: (Point) -> Offset) {
    val baseColor = PlayerColors[player.id % PlayerColors.size]
    val totalSegments = player.path.size - 1
    if (totalSegments <= 0) return
    for (i in maxOf(0, totalSegments - TRAIL_VISIBLE_STEPS) until totalSegments) {
        val color = baseColor.copy(alpha = trailAlphaFor(totalSegments - 1 - i))
        drawLine(color, offsetOf(player.path[i]), offsetOf(player.path[i + 1]), strokeWidth = cellSize * 0.08f)
    }
}

/** A little top-down race car, centered and rotated to face the player's [direction] (or the track's finish direction at the start). */
internal fun DrawScope.drawCar(
    center: Offset,
    cellSize: Float,
    color: Color,
    direction: Point,
    trackFinishDirection: Point,
) {
    val dir = if (direction.x != 0 || direction.y != 0) direction else trackFinishDirection
    val angleRad = if (dir.x == 0 && dir.y == 0) 0.0 else atan2(dir.y.toDouble(), dir.x.toDouble())

    // Car dimensions
    val bodyLength = cellSize * 0.7f
    val bodyWidth = cellSize * 0.38f
    val bodyHalfLength = bodyLength / 2f
    val bodyHalfWidth = bodyWidth / 2f
    val bodyCornerRadius = androidx.compose.ui.geometry.CornerRadius(cellSize * 0.08f)

    // Wheel dimensions
    val wheelSize = Size(cellSize * 0.10f, cellSize * 0.14f)
    val wheelRadius = androidx.compose.ui.geometry.CornerRadius(cellSize * 0.03f)
    val wheelOffsetX = bodyHalfLength * 0.7f
    val wheelOffsetY = bodyHalfWidth * 0.7f

    // Windshield dimensions
    val windowOffset = bodyHalfLength * 0.5f
    val windowWidth = bodyWidth * 0.35f
    val windowHeight = bodyLength * 0.25f

    // Use withTransform to rotate and translate, drawing all car parts in local unrotated coordinates
    withTransform({
        translate(left = center.x, top = center.y)
        // rotate()'s default pivot is the *canvas* center (based on the untranslated
        // DrawScope size), not the local origin - after translate(), the car's local
        // origin is at `center`, i.e. (0, 0) in the now-translated coordinate space.
        rotate(degrees = Math.toDegrees(angleRad).toFloat(), pivot = Offset.Zero)
    }) {
        // Draw body with rounded corners (in local car coordinates, centered at origin)
        drawRoundRect(
            color = color,
            topLeft = Offset(-bodyHalfLength, -bodyHalfWidth),
            size = Size(bodyLength, bodyWidth),
            cornerRadius = bodyCornerRadius,
        )

        // Draw windshield (glass-like with transparency)
        drawRoundRect(
            color = Color(0x80B3E5FC),
            topLeft = Offset(windowOffset, -windowWidth / 2f),
            size = Size(windowHeight, windowWidth),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cellSize * 0.03f),
        )

        // Draw wheels at their local positions
        val wheelLocalPositions = listOf(
            Offset(wheelOffsetX, -wheelOffsetY),   // front-left
            Offset(wheelOffsetX, wheelOffsetY),    // front-right
            Offset(-wheelOffsetX, wheelOffsetY),  // rear-right
            Offset(-wheelOffsetX, -wheelOffsetY), // rear-left
        )
        
        wheelLocalPositions.forEach { local ->
            drawRoundRect(
                color = Color(0xFF1A1A1A),
                topLeft = Offset(local.x - wheelSize.width / 2f, local.y - wheelSize.height / 2f),
                size = wheelSize,
                cornerRadius = wheelRadius,
            )
        }
    }
}

/**
 * A burning wreck drawn at a crash site (wall impact or oil-collision). Flame shapes in
 * orange/red with dark smoke puffs above, tinted with the player's color so wrecks remain
 * identifiable at a glance.
 */
internal fun DrawScope.drawWreck(center: Offset, cellSize: Float, playerColor: Color) {
    val r = cellSize * 0.35f

    // Dark scorch mark on the ground
    drawCircle(Color(0x66000000), radius = r * 1.1f, center = center)

    // Flame shapes: three overlapping teardrops in red, orange, and yellow
    val flameColors = listOf(
        Color(0xCCFF3300), // red-orange outer
        Color(0xCCFF8800), // orange middle
        Color(0xCCFFDD00), // yellow inner
    )
    flameColors.forEachIndexed { i, flameColor ->
        val flameHeight = r * (0.9f - i * 0.2f)
        val flameWidth = r * (0.6f - i * 0.12f)
        val flamePath = Path().apply {
            moveTo(center.x, center.y - flameHeight)
            cubicTo(
                center.x + flameWidth, center.y - flameHeight * 0.5f,
                center.x + flameWidth * 0.6f, center.y + flameHeight * 0.2f,
                center.x, center.y + flameHeight * 0.1f,
            )
            cubicTo(
                center.x - flameWidth * 0.6f, center.y + flameHeight * 0.2f,
                center.x - flameWidth, center.y - flameHeight * 0.5f,
                center.x, center.y - flameHeight,
            )
            close()
        }
        drawPath(flamePath, flameColor)
    }

    // Smoke puffs above the flames
    val smokeOffset = r * 0.9f
    drawCircle(Color(0x55424242), radius = r * 0.3f, center = Offset(center.x - r * 0.2f, center.y - smokeOffset))
    drawCircle(Color(0x44424242), radius = r * 0.25f, center = Offset(center.x + r * 0.25f, center.y - smokeOffset * 1.3f))
    drawCircle(Color(0x33424242), radius = r * 0.2f, center = Offset(center.x, center.y - smokeOffset * 1.6f))

    // Small player-color accent so the wreck is identifiable
    drawCircle(playerColor.copy(alpha = 0.5f), radius = r * 0.15f, center = center)
}

/**
 * A single shape being drawn in the track editor - either a closed polygon (boundary) or an
 * open polyline (obstacle, or a boundary still mid-edit). Points are in fine-grid units (see
 * [TrackEditorCanvas]). [filled] draws a translucent fill plus a thin outline instead of a
 * thick solid-looking stroke - used for modifier zones, to read as a drivable surface rather
 * than a wall.
 *
 * @property points The shape's vertices, in fine-grid units.
 * @property closed Whether the last vertex joins back to the first.
 * @property color What to draw it in, from the editor ink of the [TrackPalette].
 * @property filled Draw as a translucent zone rather than a stroke; see the description.
 */
data class EditorShape(val points: List<Point>, val closed: Boolean, val color: Color, val filled: Boolean = false)

/** What a single-finger drag does in [TrackEditorCanvas]; a two-finger drag always pinch-zooms/pans regardless of mode. */
enum class EditorGestureMode {
    /** Traces a continuous freehand stroke, reporting every fine grid point it crosses. */
    TRACE,

    /** Drags out a single straight line from the press point to the current point. */
    FINISH_LINE,

    /** No single-finger interaction - used once there's nothing left to draw (e.g. reviewing). */
    VIEW_ONLY,
}

/**
 * Interactive drawing surface for [TrackEditorScreen]: a [coarseGridSize]x[coarseGridSize]
 * grid of coarse (racing) dots, further subdivided [subdivision] times into a finer grid of
 * lighter dots - the same fine/coarse split the built-in tracks use, shown here so the user
 * can see exactly how precisely they're drawing relative to where cars will actually move.
 * All shape/finish-line points are in *fine* grid units. A one-finger drag draws (tracing a
 * boundary/obstacle in [EditorGestureMode.TRACE], or a single line in [EditorGestureMode.FINISH_LINE]);
 * a two-finger drag always pinch-zooms/pans instead, so both work without conflicting.
 */
@Composable
fun TrackEditorCanvas(
    coarseGridSize: Int,
    subdivision: Int,
    shapes: List<EditorShape>,
    finishLine: Wall?,
    finishDirection: Point?,
    mode: EditorGestureMode,
    onTraceStart: () -> Unit,
    onTracePoint: (Point) -> Unit,
    onTraceEnd: () -> Unit,
    onFinishLineChange: (from: Point, to: Point) -> Unit,
    onFinishLineEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember(coarseGridSize) { mutableFloatStateOf(MIN_SCALE) }
    var panOffset by remember(coarseGridSize) { mutableStateOf(Offset.Zero) }
    val bounds = ViewBounds(0, 0, coarseGridSize, coarseGridSize)
    val fineGridSize = coarseGridSize * subdivision
    val palette = LocalTrackPalette.current

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(coarseGridSize, subdivision, mode) {
                var lastPoint: Point? = null

                fun report(p: Point) {
                    if (p.x in 0..fineGridSize && p.y in 0..fineGridSize) onTracePoint(p)
                }

                fun traceTo(from: Point, to: Point) {
                    gridPointsBetween(from, to).forEach { pt ->
                        if (pt != lastPoint) {
                            report(pt)
                            lastPoint = pt
                        }
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val canvasSize = size.toSize()
                    val downPoint = fineGridPointFor(down.position, canvasSize, bounds, subdivision, scale, panOffset)

                    var started = false
                    var isPinch = false
                    var lineStart: Point? = null
                    var prevCentroid: Offset? = null
                    var prevDistance: Float? = null

                    fun startAt(p: Point) {
                        started = true
                        lastPoint = p
                        lineStart = p
                        when (mode) {
                            EditorGestureMode.TRACE -> { onTraceStart(); report(p) }
                            EditorGestureMode.FINISH_LINE -> onFinishLineChange(p, p)
                            EditorGestureMode.VIEW_ONLY -> {}
                        }
                    }

                    startAt(downPoint)

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            if (!isPinch) {
                                isPinch = true
                                if (started && mode == EditorGestureMode.TRACE) onTraceEnd()
                                started = false
                                lastPoint = null
                            }
                            val c1 = pressed[0].position
                            val c2 = pressed[1].position
                            val centroid = Offset((c1.x + c2.x) / 2f, (c1.y + c2.y) / 2f)
                            val distance = (c1 - c2).getDistance().coerceAtLeast(1f)
                            val lastC = prevCentroid
                            val lastD = prevDistance
                            if (lastC != null && lastD != null) {
                                val oldCellSize = fitCellSize(canvasSize, bounds) * scale
                                val oldOrigin = centeredOrigin(canvasSize, bounds, oldCellSize) + panOffset
                                val newScale = (scale * (distance / lastD)).coerceIn(MIN_SCALE, MAX_SCALE)
                                val newCellSize = fitCellSize(canvasSize, bounds) * newScale
                                val pan = centroid - lastC
                                val contentUnderCentroid = centroid - oldOrigin
                                val newOrigin = centroid + pan - contentUnderCentroid * (newCellSize / oldCellSize)
                                scale = newScale
                                panOffset = clampPan(newOrigin - centeredOrigin(canvasSize, bounds, newCellSize), canvasSize, bounds, newCellSize)
                            }
                            prevCentroid = centroid
                            prevDistance = distance
                            pressed.forEach { it.consume() }
                        } else if (!isPinch) {
                            val active = pressed.find { it.id == down.id } ?: pressed.first()
                            active.consume()

                            // Process every position the framework reports for this pointer,
                            // including historical samples that happened between frames, so a
                            // fast swipe doesn't skip over fine cells.
                            val positions = active.historical.map { it.position } + active.position
                            for (raw in positions) {
                                val p = fineGridPointFor(raw, canvasSize, bounds, subdivision, scale, panOffset)
                                if (p != lastPoint) {
                                    if (mode == EditorGestureMode.TRACE) {
                                        val last = lastPoint
                                        if (last != null) traceTo(last, p) else startAt(p)
                                    } else if (mode == EditorGestureMode.FINISH_LINE) {
                                        onFinishLineChange(lineStart ?: p, p)
                                        lastPoint = p
                                    }
                                }
                            }
                        }
                    }

                    if (started && !isPinch) {
                        when (mode) {
                            EditorGestureMode.TRACE -> onTraceEnd()
                            EditorGestureMode.FINISH_LINE -> onFinishLineEnd()
                            EditorGestureMode.VIEW_ONLY -> {}
                        }
                    }
                }
            },
    ) {
        val coarseCellSize = fitCellSize(size, bounds) * scale
        val fineCellSize = coarseCellSize / subdivision
        val origin = centeredOrigin(size, bounds, coarseCellSize) + panOffset
        fun fineOffsetOf(p: Point) = origin + Offset(p.x * fineCellSize, p.y * fineCellSize)

        drawRect(palette.background, size = size)
        drawGridDots(size, origin, fineCellSize, fineGridSize, palette.fineGridDot, fineCellSize * FINE_GRID_DOT_RADIUS_FRACTION)
        drawGridDots(size, origin, coarseCellSize, coarseGridSize, palette.gridDot, coarseCellSize * GRID_DOT_RADIUS_FRACTION)

        shapes.forEach { shape ->
            val points = shape.points
            if (points.size < 2) return@forEach
            if (shape.filled && points.size >= 3) {
                val path = Path().apply {
                    val first = fineOffsetOf(points[0])
                    moveTo(first.x, first.y)
                    for (i in 1 until points.size) {
                        val p = fineOffsetOf(points[i])
                        lineTo(p.x, p.y)
                    }
                    close()
                }
                drawPath(path, shape.color.copy(alpha = palette.modifierZoneAlpha))
            }
            val strokeWidth = if (shape.filled) fineCellSize * 0.15f else fineCellSize * 0.5f
            val segments = if (shape.closed) points.indices else 0 until (points.size - 1)
            segments.forEach { i ->
                val a = points[i]
                val b = points[(i + 1) % points.size]
                drawLine(shape.color, fineOffsetOf(a), fineOffsetOf(b), strokeWidth = strokeWidth)
            }
        }

        if (finishLine != null) {
            drawLine(palette.finishLine, fineOffsetOf(finishLine.from), fineOffsetOf(finishLine.to), strokeWidth = fineCellSize * 0.6f)
            if (finishDirection != null && (finishDirection.x != 0 || finishDirection.y != 0)) {
                drawFinishArrow(
                    Track(
                        name = "", width = coarseGridSize, height = coarseGridSize, subdivision = subdivision, walls = emptyList(),
                        finishLine = finishLine, finishDirection = finishDirection, startPositions = emptyList(),
                    ),
                    palette,
                    { p -> fineOffsetOf(p) },
                    coarseCellSize,
                )
            }
        }
    }
}

/** Zoomed out far enough, individual dots would be sub-pixel anyway - cap how many we ever try to draw in one frame so a big grid can't tank frame time (and with it, touch responsiveness). */
private const val MAX_GRID_DOTS_PER_FRAME = 4000

/** Draws grid dots at every multiple of [cellSize] up to [maxIndex] on each axis, culled to what's actually visible in [canvasSize]. */
private fun DrawScope.drawGridDots(canvasSize: Size, origin: Offset, cellSize: Float, maxIndex: Int, color: Color, radius: Float) {
    if (cellSize <= 0f || radius <= 0f) return
    val minXi = ((0f - origin.x) / cellSize).toInt().coerceAtLeast(0)
    val maxXi = ((canvasSize.width - origin.x) / cellSize).toInt().coerceAtMost(maxIndex)
    val minYi = ((0f - origin.y) / cellSize).toInt().coerceAtLeast(0)
    val maxYi = ((canvasSize.height - origin.y) / cellSize).toInt().coerceAtMost(maxIndex)
    if (minXi > maxXi || minYi > maxYi) return
    val visibleCount = (maxXi - minXi + 1).toLong() * (maxYi - minYi + 1).toLong()
    if (visibleCount > MAX_GRID_DOTS_PER_FRAME) return
    for (x in minXi..maxXi) {
        for (y in minYi..maxYi) {
            drawCircle(color, radius = radius, center = origin + Offset(x * cellSize, y * cellSize))
        }
    }
}

/** Converts a raw screen position into the nearest *fine* grid point, given the current fit/zoom/pan. */
private fun fineGridPointFor(
    position: Offset,
    canvasSize: Size,
    bounds: ViewBounds,
    subdivision: Int,
    scale: Float,
    panOffset: Offset,
): Point {
    val coarseCellSize = fitCellSize(canvasSize, bounds) * scale
    val fineCellSize = coarseCellSize / subdivision
    val origin = centeredOrigin(canvasSize, bounds, coarseCellSize) + panOffset
    return Point(
        ((position.x - origin.x) / fineCellSize).roundToInt(),
        ((position.y - origin.y) / fineCellSize).roundToInt(),
    )
}
