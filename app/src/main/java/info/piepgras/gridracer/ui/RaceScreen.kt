package info.piepgras.gridracer.ui

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.piepgras.gridracer.GameEvent
import info.piepgras.gridracer.R
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.ModifierType
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.PlayerState
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.Point

/**
 * The in-race screen. The track canvas fills the entire screen (it's a physical surface,
 * not app chrome); the HUD - player status icons, active modifier icons, the race info box,
 * and the close button - is drawn on top of it in the corners so it never eats into the
 * playable area.
 */
@Composable
fun RaceScreen(
    state: GameState,
    configs: List<PlayerConfig>,
    events: List<GameEvent>,
    localPlayerId: Int?,
    onMove: (Point) -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
    // Optional multiplayer HUD info shown in the race info box. Null for local races.
    connectionLabel: String? = null,
    playerCountLabel: String? = null,
    /**
     * Seconds left on the current turn's clock before the server takes it away, or null between
     * turns (or in a local race, which has no server-enforced clock). Shown for whoever's turn
     * it is, not just the local player - `turnLabel` above it already names them, so the
     * countdown reads correctly either way. The server has always force-forfeited an unanswered
     * turn; without this the players lose turns to a rule they were never shown.
     */
    turnSecondsRemaining: Int? = null,
    /** Changes the quit warning: leaving an online race forfeits it for everyone else too. */
    isOnlineRace: Boolean = false,
    /**
     * Whether leaving costs anything. False for a replay, which can simply be watched again -
     * asking "quit this race?" there would be a confirmation for nothing.
     */
    confirmOnQuit: Boolean = true,
) {
    // Quitting a real race is irreversible - online it forfeits the race for you - and the two
    // ways to trigger it (system back and the corner button) were both one tap with no
    // confirmation, right next to the full-screen tap target used for every move.
    var confirmQuit by remember { mutableStateOf(false) }
    BackHandler { if (confirmOnQuit) confirmQuit = true else onQuit() }

    if (confirmQuit) {
        ConfirmDialog(
            title = stringResource(R.string.race_quit_title),
            message = stringResource(
                if (isOnlineRace) R.string.race_quit_message_online else R.string.race_quit_message_local,
            ),
            confirmLabel = stringResource(R.string.race_quit),
            onConfirm = onQuit,
            onDismiss = { confirmQuit = false },
        )
    }

    val currentConfig = configs.getOrNull(state.currentPlayer.id)
    val currentIsAi = currentConfig?.isAi == true
    val isInteractive = if (localPlayerId == null) {
        !currentIsAi
    } else {
        localPlayerId == state.currentPlayer.id
    }
    val legalMoves = remember(state, isInteractive) {
        if (!isInteractive) emptySet() else GameEngine.legalMoves(state, state.currentPlayer.id)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The whole screen is the track; the HUD overlays it rather than stacking above it.
        TrackCanvas(
            state = state,
            legalMoves = legalMoves,
            onPointTapped = onMove,
            modifier = Modifier.fillMaxSize(),
        )

        // Top-left HUD: a row of player info boxes, then the race info box. Each player box
        // is a fixed-size frame (aspect ratio sqrt(2)) with a translucent player-color
        // background and the car inside, the player's name above it, a lap counter beneath,
        // and a row of that player's active-modifier icons below that. The race info box is
        // a translucent panel showing whose turn it is and the current round, sized to span
        // from the top of the player names down to the bottom of the lap counters so it
        // reads as one aligned block with them.
        //
        // The whole HUD (and the close button) share HUD_ALPHA so they read as one layer
        // over the track. The close button keeps its own safe-drawing padding so it doesn't
        // disappear under a notch.
        //
        // The status area takes the *top* safe-drawing inset only, plus a small fixed inset so
        // rounded display corners don't clip it. Full safeDrawingPadding was tried and reverted
        // because in landscape it also applies the gesture/cutout inset on the start edge, which
        // pushed the HUD a long way in from a flat left edge for no reason. Dropping the top
        // inset with it was the overcorrection: the app draws edge to edge, so a bare 4dp put
        // the player names underneath the status bar clock.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 6.dp, top = 4.dp)
                .alpha(HUD_ALPHA),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            state.players.forEach { player ->
                PlayerInfoBox(
                    player = player,
                    name = configs.getOrNull(player.id)?.name ?: "P${player.id + 1}",
                    active = player.id == state.currentPlayer.id,
                    totalLaps = state.totalLaps,
                    gravelTrap = state.rules.gravelTrap,
                )
            }

            // The info box lines up with the 54dp frames, not with the 18dp name rows above
            // them, so it is pushed down by exactly one name row. A top padding rather than
            // the fixed-height Box this used to sit in: that Box capped the box's height, so
            // the info box could never grow past four lines however it was sized internally -
            // which is why the turn countdown never appeared.
            Box(modifier = Modifier.padding(top = PLAYER_NAME_ROW_HEIGHT)) {
                RaceInfoBox(
                    turnLabel = if (currentIsAi) {
                        stringResource(R.string.race_turn_label_ai, currentConfig.name)
                    } else {
                        stringResource(R.string.race_turn_label, currentConfig?.name ?: "")
                    },
                    roundLabel = stringResource(R.string.race_round_label, state.round),
                    connectionLabel = connectionLabel,
                    playerCountLabel = playerCountLabel,
                    turnSecondsRemaining = turnSecondsRemaining,
                )
            }
        }

        // Top-right close button: an "x" on a grey square background for visibility. Sized
        // down from the original 63dp and sharing HUD_ALPHA so it belongs to the same HUD
        // layer as the status area.
        IconButton(
            onClick = { if (confirmOnQuit) confirmQuit = true else onQuit() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(4.dp)
                .size(37.dp)
                .alpha(HUD_ALPHA)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Gray.copy(alpha = HUD_ALPHA)),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.race_quit))
        }
    }
}

/** Shared opacity for the in-race HUD (status area + close button) so it reads as one layer. */
private const val HUD_ALPHA = 0.73f

/*
 * The in-race HUD sits *on* the track, not on app chrome, so its colours come from
 * [TrackPalette] rather than from `MaterialTheme.colorScheme` — the theme's `onSurface` is
 * about the surface behind the app, and the surface behind this text is the track.
 * `LocalTrackPalette.current.hudText` is the dark ink on the light sheet and the light ink on
 * the dark one; it matches the track's own line work either way.
 */

/** Red used for the crash "X" drawn over a player's status frame. */
private val CrashXColor = Color(0xFFD32F2F)

/*
 * The race info box is a panel that deliberately contrasts with the track rather than blending
 * into it, so its two colours are [TrackPalette.infoBoxBackground] / [TrackPalette.infoBoxText]
 * — a dark panel on the light sheet, a darker-still panel on the navy one.
 */

/**
 * Height of a player status block (name row + frame). The lap counter and modifier icons now
 * live *inside* the frame, so the block is just the name row plus the frame. The race info
 * box is sized to this so it is vertically flush with the names at the top and the frames at
 * the bottom. Kept here as a constant so [PlayerInfoBox] and [RaceInfoBox] agree without one
 * having to thread a value to the other.
 */
private val STATUS_BLOCK_HEIGHT = 18.dp + 54.dp

/**
 * Side length of the per-modifier status icons drawn inside the player's status frame.
 * 23% of the previous 30dp icon's *area* → linear scale sqrt(0.23) ≈ 0.48 → ~14.4dp, rounded
 * to 14dp. Expressed as a fraction of the frame height so the Canvas can use it without a
 * density lookup: 14dp / 54dp ≈ 0.259.
 */
private const val MODIFIER_ICON_SIDE_FRAC = 0.259f

/**
 * One player's status area: the player's name (auto-scaled to fit the frame width) above a
 * fixed-size frame (aspect ratio sqrt(2)) with a translucent player-color background. Inside
 * the frame, top-left, sits the car avatar (raised so its top and left edge gaps match); in
 * the bottom-left corner the lap counter ("1/3"); in the bottom-right corner a pair of
 * modifier-icon squares (always present as empty frames so they never jump as modifiers
 * activate/deactivate, with the icon drawn inside only when active). If the player has
 * crashed, a red "X" is drawn corner-to-corner over the whole frame.
 *
 * [active] (whoever's turn it currently is) gets a dark highlight ring and a brighter
 * background tint - previously the race info box's text was the only place whose turn it was
 * got communicated at all, easy to miss at a glance next to four otherwise-identical frames.
 *
 * The name + frame sit in a fixed-height "status block" so the race info box to their right
 * can be sized to span exactly from the top of the names down to the bottom of the frames
 * (see [RaceInfoBox]).
 */
@Composable
private fun PlayerInfoBox(player: PlayerState, name: String, active: Boolean, totalLaps: Int, gravelTrap: Boolean) {
    val color = PlayerColors[player.id % PlayerColors.size]
    // Dim everything for players that are out of the race so their status reads without text.
    val outOfRace = player.status == PlayerStatus.CRASHED || player.status == PlayerStatus.FINISHED
    val carColor = if (outOfRace) color.copy(alpha = 0.4f) else color
    val crashed = player.status == PlayerStatus.CRASHED

    // Frame dimensions: 54dp tall × 76dp wide (aspect ratio sqrt(2)), +41% area vs the old
    // 54×54 square. Fixed so neighbors never reflow when the active player changes.
    val frameWidth = 76.dp
    val frameHeight = 54.dp
    // Fixed single-line height for the name row so the status block (name + frame) has a
    // deterministic total height the race info box can match exactly.
    val nameRowHeight = 18.dp

    val lap = minOf(player.lapsCompleted + 1, totalLaps)
    val lapText = "$lap/$totalLaps"
    val nailsActive = ModifierType.NAILS in player.pendingModifiers
    val oilActive = ModifierType.OIL_SPILL in player.pendingModifiers

    // The whole TalkBack description is built here rather than in the semantics block, so
    // every part of it can come from a string resource.
    val braked = if (gravelTrap) player.gravelBrakeTurns else 0
    val hazards = buildList {
        if (nailsActive) add(stringResource(R.string.a11y_hazard_nails))
        if (oilActive) add(stringResource(R.string.a11y_hazard_oil))
        if (braked > 0) add(stringResource(R.string.a11y_hazard_gravel))
    }
    val statusText = if (crashed) {
        stringResource(R.string.a11y_player_crashed, name, lap, totalLaps)
    } else {
        stringResource(R.string.a11y_player, name, lap, totalLaps)
    }
    val description = if (hazards.isEmpty()) {
        statusText
    } else {
        stringResource(R.string.a11y_player_hazards, statusText, hazards.joinToString())
    }
    val res = LocalResources.current
    val palette = LocalTrackPalette.current

    Column(
        modifier = Modifier
            .width(frameWidth)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status block: name + frame, fixed total height so the info box aligns to it.
        Column(modifier = Modifier.height(STATUS_BLOCK_HEIGHT)) {
            // Player name above the frame, auto-scaled so it never stretches wider than the frame.
            Box(modifier = Modifier.height(nameRowHeight), contentAlignment = Alignment.Center) {
                AutoFitText(
                    text = name,
                    maxWidth = frameWidth,
                    maxFontSize = 11.sp,
                    minFontSize = 6.sp,
                    color = palette.hudText,
                )
            }

            // Frame with translucent player-color background + car inside, lap counter
            // bottom-left, modifier icons bottom-right, and a red X over everything if crashed.
            Canvas(modifier = Modifier.size(width = frameWidth, height = frameHeight)) {
                val w = size.width
                val h = size.height
                // Translucent player-color background so the car is still visible through it.
                // Boosted for whoever is currently on the clock, alongside the highlight ring
                // below - the info box's text used to be the only place this was communicated.
                drawRect(color = color.copy(alpha = if (active) 0.4f else 0.25f))

                // Highlight ring for the active player, drawn under the frame's own colored
                // border so it reads as a bright halo regardless of that player's own color.
                // Dark rather than a new accent hue: the track is a fixed near-white physical
                // surface (see TrackPalette.hudText), so a ring in the HUD ink is the one that reads
                // clearly against all four player tints without introducing a fifth color that
                // competes with the actual player colors for meaning.
                if (active) {
                    drawRect(color = palette.hudText, style = Stroke(width = minOf(w, h) * 0.16f))
                }
                // Frame border in the player's color.
                drawRect(color = color, style = Stroke(width = minOf(w, h) * 0.05f))

                // Car avatar - same drawing used on the track itself, anchored to the top-left
                // of the frame with a small inset, and shrunk 23% so the frame reads as a
                // status badge around a small car rather than a full-bleed avatar. The car
                // points in +x so its body is carCell*0.7 long (x) and carCell*0.38 wide (y);
                // the body's left edge gap is carCell*0.15 + pad while its top edge gap is
                // carCell*0.31 + pad. Shifting the y-center up by carCell*0.16 equalises them
                // so the spacing to the top and left edges of the frame is the same.
                val carCell = minOf(w, h) * 0.77f
                val pad = minOf(w, h) * 0.06f
                drawCar(
                    center = Offset(carCell / 2f + pad, carCell / 2f + pad - carCell * 0.16f),
                    cellSize = carCell,
                    color = carColor,
                    direction = Point(1, 0),
                    trackFinishDirection = Point(1, 0),
                )

                // Lap counter inside the frame, bottom-left corner.
                drawLapText(lapText, w, h, palette.hudText)

                // Modifier status icons inside the frame, bottom-right corner. Each type
                // always has a square frame slot so nothing shifts; the icon is only drawn
                // inside when that modifier is active for this player.
                drawModifierSlots(w, h, nailsActive = nailsActive, oilActive = oilActive, slotColor = palette.nails)
                // The gravel slot only exists in a race with the gravel trap on, so it never
                // appears or vanishes mid-race. Top right: the bottom row is full.
                if (gravelTrap) drawGravelSlot(w, h, braked, palette.gravel, palette.gravelBarrier)

                // Red "X" over the whole frame if the player has crashed.
                if (crashed) {
                    val stroke = minOf(w, h) * 0.08f
                    drawLine(CrashXColor, Offset(0f, 0f), Offset(w, h), strokeWidth = stroke)
                    drawLine(CrashXColor, Offset(w, 0f), Offset(0f, h), strokeWidth = stroke)
                }

                // Finish rank overlaid and centered on the frame, on top of everything else,
                // so a finished player's placement reads as the dominant element of their box.
                val rank = player.finishRank
                if (player.status == PlayerStatus.FINISHED && rank != null) {
                    drawFinishRank(ordinalWithSuffix(res, rank), w, h)
                }
            }
        }
    }
}

/**
 * Draws the lap counter (e.g. "1/3") in the bottom-left corner of the status frame, using a
 * native Paint so it can be drawn inside the same Canvas as the car/icons. Sized as a fraction
 * of the frame height so it scales with the frame and stays clear of the car avatar above.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLapText(
    text: String,
    w: Float,
    h: Float,
    textColor: Color,
) {
    drawIntoCanvas {
        val paint = android.graphics.Paint().apply {
            color = textColor.toArgb()
            textSize = h * 0.22f
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD,
            )
        }
        val inset = w * 0.04f
        // Baseline near the bottom edge with a small inset; the text sits just inside the
        // bottom-left corner of the frame.
        it.nativeCanvas.drawText(text, inset, h - inset, paint)
    }
}

/**
 * Turns a finish rank (1, 2, 3, 4, ...) into an ordinal string ("1st", "2nd", "3rd", "4th").
 * The classic English rule: 11th-13th are all "th", otherwise by the last digit. The four
 * suffixes are resources, so a language that just numbers the places (German and Russian
 * both write "1.") translates all four to the same string and the rule collapses to a no-op.
 */
private fun ordinalWithSuffix(res: Resources, rank: Int): String {
    val suffix = when (rank % 100) {
        11, 12, 13 -> R.string.race_ordinal_th
        else -> when (rank % 10) {
            1 -> R.string.race_ordinal_st
            2 -> R.string.race_ordinal_nd
            3 -> R.string.race_ordinal_rd
            else -> R.string.race_ordinal_th
        }
    }
    return "$rank${res.getString(suffix)}"
}

/**
 * Draws the finish rank (e.g. "1st") centered on the status frame, on top of the car and all
 * other frame contents, so a finished player's placement is the dominant element of their
 * box. Bright white text with a dark shadow so it reads over any player-color background.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFinishRank(text: String, w: Float, h: Float) {
    drawIntoCanvas {
        val textSize = h * 0.42f
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD,
            )
            // Dark shadow so white text stays readable over a light player color (yellow).
            setShadowLayer(textSize * 0.12f, 0f, 0f, android.graphics.Color.argb(220, 0, 0, 0))
        }
        val textWidth = paint.measureText(text)
        // Center the text on the frame.
        val x = (w - textWidth) / 2f
        // Vertically centered baseline: ascent ~ textSize*0.25 above baseline, so baseline at
        // h/2 + textSize*0.35 lands the visual middle of the glyphs on the frame's middle.
        val y = h / 2f + textSize * 0.35f
        it.nativeCanvas.drawText(text, x, y, paint)
    }
}

/**
 * Draws the two modifier status slots (nails, oil spill) as small solid-filled squares in
 * the bottom-right corner of the status frame, side by side, with the modifier's own icon
 * drawn on top of a slot only when that modifier is active. Both slots share the same solid
 * background color - [TrackPalette.nails] (orange) - with no border: inactive slots are a faded
 * orange fill, active slots are a full-orange fill with the icon on top. Side length is
 * [MODIFIER_ICON_SIDE_FRAC] of the frame height (~14dp at the default 54dp frame), i.e. 23%
 * of the old 30dp icon's area. Reuses [drawNailIcon] / [drawOilPuddleIcon] which already use
 * s-relative coordinates, so they shrink correctly.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawModifierSlots(
    w: Float,
    h: Float,
    nailsActive: Boolean,
    oilActive: Boolean,
    slotColor: Color,
) {
    val side = h * MODIFIER_ICON_SIDE_FRAC
    val gap = side * 0.15f
    val inset = w * 0.04f
    // Two slots side by side, anchored to the bottom-right corner.
    val totalWidth = side * 2f + gap
    val leftSlotX = w - inset - totalWidth
    val rightSlotX = w - inset - side
    val slotY = h - inset - side

    fun drawSlot(x: Float, active: Boolean, icon: (Float) -> Unit) {
        // Solid filled square (no border) in the nails colour; faded when inactive, full when active.
        drawRect(
            color = slotColor.copy(alpha = if (active) 1f else 0.3f),
            topLeft = Offset(x, slotY),
            size = Size(side, side),
        )
        if (active) {
            withTransform({ translate(left = x, top = slotY) }) { icon(side) }
        }
    }

    drawSlot(leftSlotX, nailsActive) { s -> drawNailIcon(s) }
    drawSlot(rightSlotX, oilActive) { s -> drawOilPuddleIcon(s) }
}

/**
 * The gravel-trap slot: a square in the top-right corner of the status frame, faded while the car
 * is not braked, and full with one pip per braked move still to come while it is - so a player can
 * see when the gravel lets go of the car.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGravelSlot(
    w: Float,
    h: Float,
    brakedMoves: Int,
    slotColor: Color,
    pipColor: Color,
) {
    val side = h * MODIFIER_ICON_SIDE_FRAC
    val inset = w * 0.04f
    val x = w - inset - side
    val y = inset
    drawRect(slotColor.copy(alpha = if (brakedMoves > 0) 1f else 0.3f), topLeft = Offset(x, y), size = Size(side, side))
    if (brakedMoves <= 0) return
    val radius = side * 0.16f
    val spacing = side / (brakedMoves + 1)
    for (i in 1..brakedMoves) {
        drawCircle(pipColor, radius = radius, center = Offset(x + spacing * i, y + side / 2f))
    }
}

/**
 * The race info box: a dark translucent panel right of the player icons, showing whose turn
 * it is and the current round. Its height matches the player status *frames* (54dp, not the
 * full name+frame block) so it is vertically flush with the colored frames of the players;
 * the width is fixed so it never reflows as names or rounds change. It takes its two colours
 * from [TrackPalette] so it stays a distinct panel over the track surface in either theme.
 */
@Composable
private fun RaceInfoBox(
    turnLabel: String,
    roundLabel: String,
    connectionLabel: String? = null,
    playerCountLabel: String? = null,
    turnSecondsRemaining: Int? = null,
) {
    val palette = LocalTrackPalette.current
    // Sized to its content, with a floor rather than a fixed height. It used to be a hard
    // `size(...)` picked per case, which silently clipped whatever did not fit: a multiplayer
    // race already lost the bottom of its fourth line, and the turn countdown - a fifth -
    // never appeared at all even though the value behind it was correct.
    //
    // The floor is the player frames' height, so a local race still lines up with them exactly.
    Column(
        modifier = Modifier
            .width(140.dp)
            .heightIn(min = INFO_BOX_MIN_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.infoBoxBackground.copy(alpha = HUD_ALPHA))
            .padding(6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = turnLabel,
            style = MaterialTheme.typography.labelSmall,
            color = palette.infoBoxText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = roundLabel,
            style = MaterialTheme.typography.labelSmall,
            color = palette.infoBoxText,
            maxLines = 1,
        )
        if (connectionLabel != null) {
            Text(
                text = connectionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = palette.infoBoxText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playerCountLabel != null) {
            Text(
                text = playerCountLabel,
                style = MaterialTheme.typography.labelSmall,
                color = palette.infoBoxText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (turnSecondsRemaining != null) {
            // Turns red inside the last ten seconds. This is the only warning the player gets
            // before the server forfeits the turn for them, so it has to be able to shout.
            Text(
                text = stringResource(R.string.race_turn_seconds_remaining, turnSecondsRemaining),
                style = MaterialTheme.typography.labelSmall,
                color = if (turnSecondsRemaining <= TURN_WARNING_SECONDS) TurnWarningColor else palette.infoBoxText,
                fontWeight = if (turnSecondsRemaining <= TURN_WARNING_SECONDS) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/** The player status frames' height: the info box never sits shorter than they are. */
private val INFO_BOX_MIN_HEIGHT = 54.dp

/** The name row above each player frame. The info box is offset by it to align with the frames. */
private val PLAYER_NAME_ROW_HEIGHT = 18.dp

/** Below this many seconds left, the turn clock turns red. */
private const val TURN_WARNING_SECONDS = 10

/** Bright red for the turn clock's final seconds, readable on the dark info box. */
private val TurnWarningColor = Color(0xFFFF6B6B)

/**
 * A single line of text that auto-scales its font size down so it fits within [maxWidth]
 * without wrapping or ellipsizing. Used for player names above the status frame, where a
 * 12-character name must not stretch wider than the frame regardless of the characters.
 */
@Composable
private fun AutoFitText(
    text: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    maxFontSize: androidx.compose.ui.unit.TextUnit,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
) {
    val density = LocalDensity.current
    val maxPx = with(density) { maxWidth.toPx() }
    // Binary search for the largest font size that fits the text on one line within maxPx.
    var lo = with(density) { minFontSize.toPx() }
    var hi = with(density) { maxFontSize.toPx() }
    var best = lo
    val steps = 6
    repeat(steps) {
        val mid = (lo + hi) / 2f
        val style = TextStyle(fontSize = with(density) { mid.toSp() }, fontWeight = FontWeight.Medium)
        // measure is a TextMeasurer call; using a simple Paint-based width check instead to
        // avoid the TextMeasurer allocation on every recomposition.
        val paint = android.graphics.Paint().apply {
            textSize = mid
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val width = paint.measureText(text)
        if (width <= maxPx) {
            best = mid
            lo = mid
        } else {
            hi = mid
        }
    }
    Text(
        text = text,
        style = TextStyle(
            fontSize = with(density) { best.toSp() },
            fontWeight = FontWeight.Medium,
        ),
        color = color,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(maxWidth),
    )
}

/** A single nail pointing down, rotated 31 degrees for a natural tilted look. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNailIcon(s: Float) {
    val headColor = Color(0xFFB0BEC5)
    val shaftColor = Color(0xFF37474F)
    val headWidth = s * 0.50f
    val headHeight = s * 0.11f
    val headTop = s * 0.12f
    val shaftWidth = s * 0.09f
    val shaftTop = headTop + headHeight
    val shaftBottom = s * 0.70f
    val tip = s * 0.88f

    withTransform({ rotate(31f, pivot = Offset(s / 2f, s / 2f)) }) {
        drawRoundRect(
            color = headColor,
            topLeft = Offset((s - headWidth) / 2f, headTop),
            size = Size(headWidth, headHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(headHeight / 2f),
        )
        drawRect(
            color = shaftColor,
            topLeft = Offset((s - shaftWidth) / 2f, shaftTop),
            size = Size(shaftWidth, shaftBottom - shaftTop),
        )
        val tipPath = Path().apply {
            moveTo((s - shaftWidth) / 2f, shaftBottom)
            lineTo((s + shaftWidth) / 2f, shaftBottom)
            lineTo(s / 2f, tip)
            close()
        }
        drawPath(tipPath, shaftColor)
    }
}

/** A little black puddle: a round blob (not square) with a faint highlight so it reads as liquid. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOilPuddleIcon(s: Float) {
    val puddleColor = Color(0xFF1A1A1A)
    val center = Offset(s / 2f, s / 2f)
    // Main puddle body - a circle so it reads as a round spill, not a tile.
    drawCircle(puddleColor, radius = s * 0.36f, center = center)
    // A small bulge off to one side to make it look like an organic spill rather than a dot.
    drawCircle(puddleColor, radius = s * 0.18f, center = Offset(s * 0.68f, s * 0.62f))
    // Tiny off-center highlight so it reads as a liquid surface.
    drawCircle(
        color = Color.White.copy(alpha = 0.20f),
        radius = s * 0.09f,
        center = Offset(s * 0.40f, s * 0.38f),
    )
}
