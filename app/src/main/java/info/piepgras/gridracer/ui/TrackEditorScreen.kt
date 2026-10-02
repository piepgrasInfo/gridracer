package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.ModifierType
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.TrackBuilder
import info.piepgras.gridracer.engine.TrackDraft
import info.piepgras.gridracer.engine.TrackEditorGeometry
import info.piepgras.gridracer.engine.TrackModifier
import info.piepgras.gridracer.engine.TrackValidationError
import info.piepgras.gridracer.engine.TrackValidator
import info.piepgras.gridracer.engine.Wall
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coarse (racing-grid) extent of the editor's drawable area. */
private const val COARSE_GRID_SIZE = 60

/** How many fine-grid units make up one coarse unit for newly created tracks - matches the built-in tracks. */
private const val FINE_SUBDIVISION = 5

/** Width fraction of the right-hand toolbar panel - the canvas takes whatever's left. */
private const val PANEL_WIDTH_FRACTION = 0.25f

private enum class EditorStep { DRAW_INNER, DRAW_OUTER, DRAW_OBSTACLES, PLACE_FINISH_LINE, REVIEW }

/** What the next traced loop on the [EditorStep.DRAW_OBSTACLES] step produces. */
private enum class DrawKind { OBSTACLE, OIL_SPILL, NAILS }

/**
 * A full copy of every editable field, taken right before a committing action (finishing
 * a boundary, adding an obstacle, accepting a finish line, choosing a direction, ...).
 * Undo always pops one of these and restores everything at once, including [step] - so
 * one button undoes "the last step" uniformly, whatever kind of step it was, instead of
 * needing separate bookkeeping per step type.
 */
private data class EditorSnapshot(
    val step: EditorStep,
    val inner: List<Point>,
    val outer: List<Point>,
    val obstacles: List<List<Point>>,
    val modifiers: List<TrackModifier>,
    val finishFrom: Point?,
    val finishTo: Point?,
    val finishDirection: Point,
)

/**
 * What finishing a stroke turned out to mean, computed off the main thread and then
 * applied to the editor's state. Modelled as data rather than as direct mutations so
 * the expensive part (repair + validation) has no contact with Compose state.
 */
private sealed interface StrokeOutcome {
    /** [repaired] is put back as the live stroke so the user can see what was rejected. */
    data class Rejected(val message: String, val repaired: List<Point>) : StrokeOutcome
    /**
     * [modifiers] is the hazard list re-clipped to the corridor this boundary describes, or
     * null when there was nothing to re-clip - moving a wall onto a hazard has to take the
     * hazard away, the same way drawing a hazard onto a wall trims the hazard.
     */
    data class BoundaryAccepted(
        val boundary: List<Point>,
        val isOuter: Boolean,
        val nextStep: EditorStep?,
        val modifiers: List<TrackModifier>? = null,
    ) : StrokeOutcome

    /** [modifiers] as for [BoundaryAccepted]: the new obstacle carves into any hazard under it. */
    data class ObstacleAccepted(val obstacle: List<Point>, val modifiers: List<TrackModifier>? = null) : StrokeOutcome

    /** The whole modifier list, post-merge - see [TrackEditorGeometry.mergeModifier]. */
    data class ModifiersAccepted(val modifiers: List<TrackModifier>) : StrokeOutcome
}

// The editor's ink follows the dark-mode setting like the rest of the track surface does -
// near-black boundary lines on a navy sheet would be ink you cannot see. See [TrackPalette].

/** Validation errors an automatic finish-line placement can actually resolve - anything else needs a manual redraw of the boundaries/obstacles themselves. */
private val FinishLineRelatedErrors = setOf(
    TrackValidationError.FINISH_LINE_MISSING,
    TrackValidationError.FINISH_LINE_TOO_SHORT,
    TrackValidationError.FINISH_LINE_NOT_ACROSS_TRACK,
    TrackValidationError.START_POSITIONS_INVALID,
)

private fun colorFor(palette: TrackPalette, kind: DrawKind): Color = when (kind) {
    DrawKind.OBSTACLE -> palette.editorObstacle
    DrawKind.OIL_SPILL -> palette.oilSpill
    DrawKind.NAILS -> palette.nails
}

private fun colorFor(palette: TrackPalette, type: ModifierType): Color = when (type) {
    ModifierType.OIL_SPILL -> palette.oilSpill
    ModifierType.NAILS -> palette.nails
}

@Composable
private fun hazardLabel(kind: DrawKind): String = when (kind) {
    DrawKind.OBSTACLE -> stringResource(R.string.editor_draw_kind_obstacle)
    DrawKind.OIL_SPILL -> stringResource(R.string.editor_draw_kind_oil_spill)
    DrawKind.NAILS -> stringResource(R.string.editor_draw_kind_nails)
}

/**
 * One cell of the top tool grid (section 1): an icon button with a tooltip, highlighted
 * when [active] (the tool matching the current step/action) and dimmed when ![enabled].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolGridButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit = {},
) {
    // The `weight()` in [modifier] must land on a plain layout node to be honored by the
    // parent Row - TooltipBox (a SubcomposeLayout wrapper) doesn't reliably forward it to
    // its content, so it's applied to this outer Box instead and TooltipBox just fills it.
    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            ) {
                // Shrunk from Material's default 48dp minimum touch target: with six of these
                // stacked in a 2x3 grid, the accessibility-mandated default doesn't fit the
                // landscape panel's height budget alongside sections 2-3 below it.
                IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(36.dp)) {
                    Icon(icon, contentDescription = label)
                }
            }
        }
    }
}

/** One row of the context sub-selector (section 2): an icon/rotation, a label, and a tap target. */
@Composable
private fun SelectorRow(
    icon: ImageVector,
    label: String,
    rotationDegrees: Float = 0f,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).rotate(rotationDegrees))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A [SelectorRow] with a leading radio dot instead of a plain icon - used for mutually
 * exclusive picks (hazard kind). Uses a plain icon rather than the real Material
 * `RadioButton` composable: that component carries its own 48dp minimum touch target,
 * which is redundant (and too tall to fit several stacked) since the whole row is
 * already the click target via [Modifier.clickable] above.
 */
@Composable
private fun RadioSelectorRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The freehand track editor, as a step machine: inner loop, outer loop, obstacles and hazards,
 * finish line, review. Every stroke is cleaned up and validated as it is drawn.
 *
 * @param editingId The custom track being edited, or null for a new one.
 * @param existingName Its name, when editing.
 * @param existingDraft Its draft, when editing.
 * @param onSave Called with the validated, built track when the user saves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackEditorScreen(
    editingId: String?,
    existingName: String?,
    existingDraft: TrackDraft?,
    onSave: (id: String?, name: String, draft: TrackDraft, track: Track) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)

    val subdivision = remember { existingDraft?.subdivision?.takeIf { it > 0 } ?: FINE_SUBDIVISION }

    val innerBoundary = remember { mutableStateListOf<Point>().apply { existingDraft?.innerBoundary?.let(::addAll) } }
    val outerBoundary = remember { mutableStateListOf<Point>().apply { existingDraft?.outerBoundary?.let(::addAll) } }
    val currentStroke = remember { mutableStateListOf<Point>() }
    val obstacles = remember { mutableStateListOf<List<Point>>().apply { existingDraft?.obstacles?.let(::addAll) } }
    val modifiers = remember { mutableStateListOf<TrackModifier>().apply { existingDraft?.modifiers?.let(::addAll) } }
    val undoStack = remember { mutableStateListOf<EditorSnapshot>() }
    var drawKind by remember { mutableStateOf(DrawKind.OBSTACLE) }

    var step by remember { mutableStateOf(initialStep(existingDraft)) }
    var finishFrom by remember { mutableStateOf(existingDraft?.finishLineFrom) }
    var finishTo by remember { mutableStateOf(existingDraft?.finishLineTo) }
    var liveFinishFrom by remember { mutableStateOf<Point?>(null) }
    var liveFinishTo by remember { mutableStateOf<Point?>(null) }
    var finishDirection by remember { mutableStateOf(existingDraft?.finishDirection ?: Point(0, 0)) }
    var name by remember { mutableStateOf(existingName ?: "") }
    var stepError by remember { mutableStateOf<String?>(null) }
    var showStartOverDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Rejections are produced inside plain (non-composable) stroke handlers, so they are
    // resolved through Resources rather than stringResource.
    val res = LocalResources.current

    fun currentDraft() = TrackDraft(
        outerBoundary = outerBoundary.toList(),
        innerBoundary = innerBoundary.toList(),
        obstacles = obstacles.toList(),
        modifiers = modifiers.toList(),
        finishLineFrom = finishFrom,
        finishLineTo = finishTo,
        finishDirection = finishDirection,
        subdivision = subdivision,
    )

    fun errorSummary(errors: Set<TrackValidationError>) =
        errors.joinToString("\n") { res.getString(describeError(it)) }

    fun snapshot() = EditorSnapshot(
        step = step,
        inner = innerBoundary.toList(),
        outer = outerBoundary.toList(),
        obstacles = obstacles.toList(),
        modifiers = modifiers.toList(),
        finishFrom = finishFrom,
        finishTo = finishTo,
        finishDirection = finishDirection,
    )

    fun pushUndo() {
        undoStack.add(snapshot())
    }

    fun restore(s: EditorSnapshot) {
        innerBoundary.clear(); innerBoundary.addAll(s.inner)
        outerBoundary.clear(); outerBoundary.addAll(s.outer)
        obstacles.clear(); obstacles.addAll(s.obstacles)
        modifiers.clear(); modifiers.addAll(s.modifiers)
        finishFrom = s.finishFrom
        finishTo = s.finishTo
        finishDirection = s.finishDirection
        step = s.step
        currentStroke.clear()
        liveFinishFrom = null
        liveFinishTo = null
        stepError = null
    }

    /** Undoes the in-progress stroke if there is one, otherwise pops and restores the entire last committed step. */
    fun undo() {
        if (currentStroke.isNotEmpty()) {
            currentStroke.clear()
            stepError = null
            return
        }
        undoStack.removeLastOrNull()?.let(::restore)
    }

    val canUndo = !busy && (currentStroke.isNotEmpty() || undoStack.isNotEmpty())

    /** Tries [TrackBuilder.autoPlaceFinishLine]; on success commits it and jumps straight to REVIEW. */
    fun applyAutoFix(): Boolean {
        val result = TrackBuilder.autoPlaceFinishLine(outerBoundary.toList(), innerBoundary.toList(), subdivision)
            ?: return false
        val (from, to, direction) = result
        pushUndo()
        finishFrom = from
        finishTo = to
        finishDirection = direction
        stepError = null
        step = EditorStep.REVIEW
        return true
    }

    fun appendToCurrent(p: Point) {
        if (currentStroke.lastOrNull() != p) currentStroke.add(p)
    }

    /**
     * Applies whatever the (off-thread) repair worked out. [StrokeOutcome.Rejected]
     * puts the repaired shape back under the finger as the live stroke so the user can
     * see what was wrong and undo or redraw it.
     */
    fun applyOutcome(outcome: StrokeOutcome) {
        currentStroke.clear()
        when (outcome) {
            is StrokeOutcome.Rejected -> {
                stepError = outcome.message
                currentStroke.addAll(outcome.repaired)
            }
            is StrokeOutcome.BoundaryAccepted -> {
                pushUndo()
                val target = if (outcome.isOuter) outerBoundary else innerBoundary
                target.clear()
                target.addAll(outcome.boundary)
                outcome.modifiers?.let { modifiers.clear(); modifiers.addAll(it) }
                stepError = null
                outcome.nextStep?.let { step = it }
            }
            is StrokeOutcome.ObstacleAccepted -> {
                pushUndo()
                obstacles.add(outcome.obstacle)
                outcome.modifiers?.let { modifiers.clear(); modifiers.addAll(it) }
                stepError = null
            }
            is StrokeOutcome.ModifiersAccepted -> {
                pushUndo()
                modifiers.clear()
                modifiers.addAll(outcome.modifiers)
                stepError = null
            }
        }
    }

    /**
     * Runs a finished stroke through repair + validation on a background dispatcher and
     * applies the result. Repair is bounded work now (see [TrackEditorGeometry.repairShape]),
     * but it used to freeze the app from inside this very callback, so it deliberately
     * never runs on the main thread again - and the canvas goes [EditorGestureMode.VIEW_ONLY]
     * while it's in flight so a second stroke can't race the first.
     */
    fun processStroke(compute: () -> StrokeOutcome) {
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.Default) { compute() }
            applyOutcome(outcome)
            busy = false
        }
    }

    /**
     * The hazard list after a barrier change, or null when it is unchanged - which keeps
     * [StrokeOutcome] from rewriting hazard state on every ordinary boundary stroke and
     * makes the common case (no hazards drawn yet) free.
     */
    fun reclippedModifiers(
        existing: List<TrackModifier>,
        outer: List<Point>,
        inner: List<Point>,
        obstacles: List<List<Point>>,
    ): List<TrackModifier>? {
        if (existing.isEmpty() || outer.size < 3 || inner.size < 3) return null
        val clipped = TrackEditorGeometry.clipModifiersToCorridor(existing, outer, inner, obstacles)
        return if (clipped == existing) null else clipped
    }

    fun finishBoundary(boundaryStep: EditorStep, points: List<Point>, isOuter: Boolean, next: EditorStep) {
        if (points.size < 3) {
            stepError = res.getString(R.string.editor_error_boundary_too_few_points)
            return
        }
        val inner = innerBoundary.toList()
        val outer = outerBoundary.toList()
        val existingObstacles = obstacles.toList()
        val existingModifiers = modifiers.toList()
        processStroke {
            val repaired = TrackEditorGeometry.repairShape(points)
            if (repaired.size < 3) {
                return@processStroke StrokeOutcome.Rejected(
                    res.getString(R.string.editor_error_boundary_too_few_points), emptyList(),
                )
            }
            val errors = when (boundaryStep) {
                EditorStep.DRAW_INNER -> TrackValidator.validateInnerOnly(repaired)
                EditorStep.DRAW_OUTER -> TrackValidator.validateOuterOnly(repaired, subdivision) +
                    TrackValidator.validateBoundaries(repaired, inner, subdivision)
                else -> emptySet()
            }
            if (errors.isEmpty()) {
                StrokeOutcome.BoundaryAccepted(
                    repaired,
                    isOuter,
                    next,
                    reclippedModifiers(
                        existingModifiers,
                        outer = if (isOuter) repaired else outer,
                        inner = if (isOuter) inner else repaired,
                        obstacles = existingObstacles,
                    ),
                )
            } else {
                StrokeOutcome.Rejected(errorSummary(errors), repaired)
            }
        }
    }

    /** Finishes the current stroke on the DRAW_OBSTACLES step - an obstacle, oil spill, or nails loop depending on [drawKind]. */
    fun finishTracedShape() {
        val stroke = currentStroke.toList()
        if (stroke.size < 3) {
            stepError = res.getString(R.string.editor_error_shape_too_few_points)
            return
        }
        val outer = outerBoundary.toList()
        val inner = innerBoundary.toList()
        val existingObstacles = obstacles.toList()
        val existingModifiers = modifiers.toList()
        val kind = drawKind
        processStroke {
            val repaired = TrackEditorGeometry.repairShape(stroke)
            if (repaired.size < 3) {
                return@processStroke StrokeOutcome.Rejected(
                    res.getString(R.string.editor_error_shape_too_few_points), emptyList(),
                )
            }
            val closedLoop = TrackEditorGeometry.closeLoop(repaired)

            if (kind != DrawKind.OBSTACLE) {
                val type = if (kind == DrawKind.OIL_SPILL) ModifierType.OIL_SPILL else ModifierType.NAILS
                // Hazards auto-merge instead of being validated and rejected: anything
                // overlapping a wall or obstacle is trimmed away (the barrier wins), an
                // overlapping zone of the same type is absorbed, and a zone of another
                // type is left alone so the two can overlap and both apply.
                val merged = TrackEditorGeometry.mergeModifier(
                    TrackModifier(type, closedLoop), existingModifiers, outer, inner, existingObstacles,
                ) ?: return@processStroke StrokeOutcome.Rejected(
                    res.getString(describeError(TrackValidationError.MODIFIER_NOT_IN_TRACK)), repaired,
                )
                return@processStroke StrokeOutcome.ModifiersAccepted(merged)
            }

            val mergedInner = TrackEditorGeometry.mergeObstacleWithBoundary(inner, closedLoop, isOuter = false)
            val mergedOuter = TrackEditorGeometry.mergeObstacleWithBoundary(outer, closedLoop, isOuter = true)

            when {
                mergedInner != null && mergedOuter != null ->
                    StrokeOutcome.Rejected(
                        res.getString(R.string.editor_error_obstacle_crosses_both), repaired,
                    )
                mergedInner != null -> {
                    val errors = TrackValidator.validateBoundaries(outer, mergedInner, subdivision)
                    if (errors.isEmpty()) {
                        StrokeOutcome.BoundaryAccepted(
                            mergedInner, isOuter = false, nextStep = null,
                            modifiers = reclippedModifiers(existingModifiers, outer, mergedInner, existingObstacles),
                        )
                    } else {
                        StrokeOutcome.Rejected(errorSummary(errors), repaired)
                    }
                }
                mergedOuter != null -> {
                    val errors = TrackValidator.validateBoundaries(mergedOuter, inner, subdivision)
                    if (errors.isEmpty()) {
                        StrokeOutcome.BoundaryAccepted(
                            mergedOuter, isOuter = true, nextStep = null,
                            modifiers = reclippedModifiers(existingModifiers, mergedOuter, inner, existingObstacles),
                        )
                    } else {
                        StrokeOutcome.Rejected(errorSummary(errors), repaired)
                    }
                }
                else -> {
                    val errors = TrackValidator.validateObstacle(closedLoop, outer, inner, subdivision)
                    if (errors.isEmpty()) {
                        StrokeOutcome.ObstacleAccepted(
                            closedLoop,
                            modifiers = reclippedModifiers(
                                existingModifiers, outer, inner, existingObstacles + listOf(closedLoop),
                            ),
                        )
                    } else {
                        StrokeOutcome.Rejected(errorSummary(errors), repaired)
                    }
                }
            }
        }
    }

    fun autoFixOrFail() {
        if (!applyAutoFix()) {
            stepError = res.getString(R.string.editor_error_no_auto_placement)
        }
    }

    val palette = LocalTrackPalette.current
    val shapes = buildList {
        if (step != EditorStep.DRAW_INNER || currentStroke.isEmpty()) {
            add(EditorShape(innerBoundary, closed = true, color = palette.editorInner))
        }
        if (step != EditorStep.DRAW_OUTER || currentStroke.isEmpty()) {
            add(EditorShape(outerBoundary, closed = true, color = palette.editorOuter))
        }
        obstacles.forEach { add(EditorShape(it, closed = true, color = palette.editorObstacle)) }
        modifiers.forEach {
            add(EditorShape(it.area, closed = true, color = colorFor(palette, it.type), filled = true))
        }
        if (currentStroke.isNotEmpty()) {
            val color = when (step) {
                EditorStep.DRAW_INNER -> palette.editorInner
                EditorStep.DRAW_OUTER -> palette.editorOuter
                EditorStep.DRAW_OBSTACLES -> colorFor(palette, drawKind)
                else -> palette.editorObstacle
            }
            val filled = step == EditorStep.DRAW_OBSTACLES && drawKind != DrawKind.OBSTACLE
            add(EditorShape(currentStroke, closed = true, color = color, filled = filled))
        }
    }
    val previewFinishLine = when {
        liveFinishFrom != null && liveFinishTo != null -> Wall(liveFinishFrom!!, liveFinishTo!!)
        finishFrom != null && finishTo != null -> Wall(finishFrom!!, finishTo!!)
        else -> null
    }
    val mode = when {
        busy -> EditorGestureMode.VIEW_ONLY
        step == EditorStep.PLACE_FINISH_LINE -> EditorGestureMode.FINISH_LINE
        step == EditorStep.REVIEW -> EditorGestureMode.VIEW_ONLY
        else -> EditorGestureMode.TRACE
    }

    val reviewValidation = if (step == EditorStep.REVIEW) TrackValidator.validate(currentDraft()) else null
    val canAutoFixFinishLine = reviewValidation != null && !reviewValidation.isValid &&
        reviewValidation.errors.all { it in FinishLineRelatedErrors }
    val displayError = if (step == EditorStep.REVIEW) {
        reviewValidation?.takeIf { !it.isValid }?.let { errorSummary(it.errors) }
    } else {
        stepError
    }
    val instructionsText = when {
        busy -> stringResource(R.string.editor_tidying_up)
        step == EditorStep.PLACE_FINISH_LINE && finishFrom != null && finishTo != null ->
            stringResource(R.string.editor_pick_direction)
        else -> stepInstructions(step)
    }
    val defaultTrackName = stringResource(R.string.editor_default_track_name)
    val saveEnabled = !busy && step == EditorStep.REVIEW && reviewValidation?.isValid == true && name.isNotBlank()

    Row(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // The canvas fills whatever width the fixed-width panel doesn't need - see the
        // panel's own sections for why nothing there ever needs to scroll either.
        TrackEditorCanvas(
            coarseGridSize = COARSE_GRID_SIZE,
            subdivision = subdivision,
            shapes = shapes,
            finishLine = previewFinishLine,
            finishDirection = finishDirection,
            mode = mode,
            onTraceStart = {
                stepError = null
                currentStroke.clear()
            },
            onTracePoint = { p ->
                when (step) {
                    EditorStep.DRAW_INNER, EditorStep.DRAW_OUTER, EditorStep.DRAW_OBSTACLES -> appendToCurrent(p)
                    EditorStep.PLACE_FINISH_LINE, EditorStep.REVIEW -> {}
                }
            },
            onTraceEnd = {
                when (step) {
                    EditorStep.DRAW_INNER -> finishBoundary(
                        step,
                        currentStroke.toList(),
                        isOuter = false,
                        next = EditorStep.DRAW_OUTER,
                    )
                    EditorStep.DRAW_OUTER -> finishBoundary(
                        step,
                        currentStroke.toList(),
                        isOuter = true,
                        next = EditorStep.DRAW_OBSTACLES,
                    )
                    EditorStep.DRAW_OBSTACLES -> finishTracedShape()
                    EditorStep.PLACE_FINISH_LINE, EditorStep.REVIEW -> {}
                }
            },
            onFinishLineChange = { from, to ->
                liveFinishFrom = from
                liveFinishTo = to
            },
            onFinishLineEnd = {
                val from = liveFinishFrom
                val to = liveFinishTo
                liveFinishFrom = null
                liveFinishTo = null
                if (from != null && to != null) {
                    val capped = TrackBuilder.capFinishLineToBoundaries(from, to, outerBoundary, innerBoundary)
                    val (cappedFrom, cappedTo) = capped ?: (from to to)
                    val length = hypot((cappedTo.x - cappedFrom.x).toDouble(), (cappedTo.y - cappedFrom.y).toDouble())
                    if (length < TrackValidator.MIN_FINISH_LINE_LENGTH * subdivision) {
                        stepError = res.getString(describeError(TrackValidationError.FINISH_LINE_TOO_SHORT))
                    } else {
                        pushUndo()
                        stepError = null
                        finishFrom = cappedFrom
                        finishTo = cappedTo
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )

        VerticalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth(PANEL_WIDTH_FRACTION)
                .fillMaxHeight()
                .padding(8.dp),
        ) {
            // Header: title + close.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_close))
                }
            }
            Spacer(Modifier.height(2.dp))

            // Section 1: the persistent tool grid - always visible, greyed out until reachable.
            Column {
                Row(Modifier.fillMaxWidth()) {
                    ToolGridButton(
                        Icons.Filled.Save,
                        stringResource(R.string.editor_save),
                        enabled = saveEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val draft = currentDraft()
                            val trackName = name.ifBlank { defaultTrackName }
                            val track = TrackBuilder.build(trackName, draft)
                            onSave(editingId, trackName, draft, track)
                        },
                    )
                    ToolGridButton(
                        Icons.AutoMirrored.Filled.Undo,
                        stringResource(R.string.editor_undo),
                        enabled = canUndo,
                        modifier = Modifier.weight(1f),
                        onClick = ::undo,
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    ToolGridButton(
                        Icons.Filled.RadioButtonUnchecked,
                        stringResource(R.string.editor_tool_inner),
                        enabled = step == EditorStep.DRAW_INNER,
                        active = step == EditorStep.DRAW_INNER,
                        modifier = Modifier.weight(1f),
                    )
                    ToolGridButton(
                        Icons.Filled.Circle,
                        stringResource(R.string.editor_tool_outer),
                        enabled = step == EditorStep.DRAW_OUTER,
                        active = step == EditorStep.DRAW_OUTER,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    ToolGridButton(
                        Icons.Filled.Warning,
                        stringResource(R.string.editor_tool_obstacle),
                        enabled = step == EditorStep.DRAW_OBSTACLES,
                        active = step == EditorStep.DRAW_OBSTACLES,
                        modifier = Modifier.weight(1f),
                    )
                    ToolGridButton(
                        Icons.Filled.Flag,
                        stringResource(R.string.editor_tool_finish_line),
                        // Also enabled (as a "next step" trigger) from DRAW_OBSTACLES: obstacles
                        // are optional, so tapping this tool directly is how you move on from
                        // them - no separate "done" control needed elsewhere.
                        enabled = step == EditorStep.PLACE_FINISH_LINE || step == EditorStep.DRAW_OBSTACLES,
                        active = step == EditorStep.PLACE_FINISH_LINE,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (step == EditorStep.DRAW_OBSTACLES) {
                                pushUndo()
                                currentStroke.clear()
                                stepError = null
                                step = EditorStep.PLACE_FINISH_LINE
                            }
                        },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // Section 2: context-dependent sub-selector for whichever tool is current. Capped
            // and internally scrollable as a defensive safety net - sections 1 and 3 always
            // keep their space regardless, so only this middle section would ever need to
            // scroll, and only if some future addition pushed its item count past what fits.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (step) {
                    EditorStep.DRAW_INNER, EditorStep.DRAW_OUTER -> {}

                    EditorStep.DRAW_OBSTACLES -> {
                        listOf(DrawKind.OBSTACLE, DrawKind.OIL_SPILL, DrawKind.NAILS).forEach { kind ->
                            RadioSelectorRow(selected = drawKind == kind, label = hazardLabel(kind)) { drawKind = kind }
                        }
                    }

                    EditorStep.PLACE_FINISH_LINE -> {
                        val from = finishFrom
                        val to = finishTo
                        if (from != null && to != null) {
                            val dx = to.x - from.x
                            val dy = to.y - from.y
                            val optionA = Point(-dy, dx)
                            val optionB = Point(dy, -dx)
                            val angleA = Math.toDegrees(atan2(optionA.y.toDouble(), optionA.x.toDouble())).toFloat()
                            val angleB = Math.toDegrees(atan2(optionB.y.toDouble(), optionB.x.toDouble())).toFloat()
                            // Deliberately non-rotated-icon-agnostic: the rotation encodes an actual
                            // track-space direction, so a plain (non-AutoMirrored) arrow is used.
                            SelectorRow(
                                Icons.Filled.ArrowForward,
                                stringResource(R.string.editor_direction_a_description),
                                rotationDegrees = angleA,
                            ) { pushUndo(); finishDirection = optionA; step = EditorStep.REVIEW }
                            SelectorRow(
                                Icons.Filled.ArrowForward,
                                stringResource(R.string.editor_direction_b_description),
                                rotationDegrees = angleB,
                            ) { pushUndo(); finishDirection = optionB; step = EditorStep.REVIEW }
                            SelectorRow(Icons.Filled.Replay, stringResource(R.string.editor_redraw_finish_line)) {
                                finishFrom = null
                                finishTo = null
                                stepError = null
                            }
                        } else if (stepError != null) {
                            SelectorRow(Icons.Filled.AutoFixHigh, stringResource(R.string.editor_auto_fix), onClick = ::autoFixOrFail)
                        }
                    }

                    EditorStep.REVIEW -> {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.editor_track_name_label)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (canAutoFixFinishLine) {
                            SelectorRow(Icons.Filled.AutoFixHigh, stringResource(R.string.editor_auto_fix), onClick = ::autoFixOrFail)
                        }
                        SelectorRow(Icons.Filled.RestartAlt, stringResource(R.string.editor_start_over)) {
                            showStartOverDialog = true
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // Section 3: instructions/status box - takes whatever height sections 1-2 leave behind.
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(instructionsText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.editor_pan_zoom_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (displayError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        displayError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (showStartOverDialog) {
        AlertDialog(
            onDismissRequest = { showStartOverDialog = false },
            title = { Text(stringResource(R.string.editor_start_over_confirm_title)) },
            text = { Text(stringResource(R.string.editor_start_over_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showStartOverDialog = false
                    innerBoundary.clear(); outerBoundary.clear(); obstacles.clear(); modifiers.clear()
                    currentStroke.clear(); undoStack.clear(); drawKind = DrawKind.OBSTACLE
                    finishFrom = null; finishTo = null; liveFinishFrom = null; liveFinishTo = null
                    finishDirection = Point(0, 0)
                    stepError = null
                    step = EditorStep.DRAW_INNER
                }) { Text(stringResource(R.string.editor_start_over)) }
            },
            dismissButton = {
                TextButton(onClick = { showStartOverDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun initialStep(draft: TrackDraft?): EditorStep = when {
    draft == null -> EditorStep.DRAW_INNER
    draft.innerBoundary.size < 3 -> EditorStep.DRAW_INNER
    draft.outerBoundary.size < 3 -> EditorStep.DRAW_OUTER
    draft.finishLineFrom == null -> EditorStep.DRAW_OBSTACLES
    else -> EditorStep.REVIEW
}

@Composable
private fun stepInstructions(step: EditorStep): String = when (step) {
    EditorStep.DRAW_INNER -> stringResource(R.string.editor_step_inner)
    EditorStep.DRAW_OUTER -> stringResource(R.string.editor_step_outer)
    EditorStep.DRAW_OBSTACLES -> stringResource(R.string.editor_step_obstacles)
    EditorStep.PLACE_FINISH_LINE -> stringResource(R.string.editor_step_finish_line)
    EditorStep.REVIEW -> stringResource(R.string.editor_step_review)
}

@StringRes
private fun describeError(error: TrackValidationError): Int = when (error) {
    TrackValidationError.OUTER_TOO_FEW_POINTS -> R.string.editor_error_outer_too_few_points
    TrackValidationError.INNER_TOO_FEW_POINTS -> R.string.editor_error_inner_too_few_points
    TrackValidationError.OUTER_NOT_SIMPLE -> R.string.editor_error_outer_not_simple
    TrackValidationError.INNER_NOT_SIMPLE -> R.string.editor_error_inner_not_simple
    TrackValidationError.OUTER_TOO_SMALL -> R.string.editor_error_outer_too_small
    TrackValidationError.INNER_NOT_NESTED -> R.string.editor_error_inner_not_nested
    TrackValidationError.CORRIDOR_TOO_NARROW -> R.string.editor_error_corridor_too_narrow
    TrackValidationError.OBSTACLE_NOT_SIMPLE -> R.string.editor_error_obstacle_not_simple
    TrackValidationError.OBSTACLE_TOUCHES_BOUNDARY -> R.string.editor_error_obstacle_touches_boundary
    TrackValidationError.OBSTACLE_NARROWS_CORRIDOR -> R.string.editor_error_obstacle_narrows_corridor
    TrackValidationError.OBSTACLE_NOT_IN_TRACK -> R.string.editor_error_obstacle_not_in_track
    TrackValidationError.MODIFIER_NOT_SIMPLE -> R.string.editor_error_modifier_not_simple
    TrackValidationError.MODIFIER_NOT_IN_TRACK -> R.string.editor_error_modifier_not_in_track
    TrackValidationError.FINISH_LINE_MISSING -> R.string.editor_error_finish_line_missing
    TrackValidationError.FINISH_LINE_TOO_SHORT -> R.string.editor_error_finish_line_too_short
    TrackValidationError.FINISH_LINE_NOT_ACROSS_TRACK -> R.string.editor_error_finish_line_not_across
    TrackValidationError.START_POSITIONS_INVALID -> R.string.editor_error_start_positions_invalid
}
