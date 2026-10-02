package info.piepgras.gridracer.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every colour the race track and its HUD are drawn in.
 *
 * These were nine top-level constants in TrackCanvas.kt, fixed regardless of the app's theme
 * on the reasoning that the track is a physical surface - paper - and paper does not invert.
 * That held until the surface itself became a choice: a near-white sheet filling a phone
 * screen is tiring in the dark, and it is what made the grid points hard to pick out, since
 * light grey dots on near-white is the lowest-contrast pairing on the board.
 *
 * So the track has a palette instead of constants, and the HUD colours live here too - they
 * are not app chrome either, they are "whatever contrasts with the surface", and keeping them
 * anywhere else is how you end up with dark HUD text on a dark track.
 *
 * Read it with `LocalTrackPalette.current` in a composable; the `DrawScope` helpers take it as
 * a parameter, since they are not composables.
 *
 * @property background The track surface behind everything else.
 * @property gridDot The coarse grid points - where a move may land.
 * @property fineGridDot The fine authoring grid, shown only in the editor.
 * @property wall Wall segments.
 * @property finishLine The finish line, and the start-zone shading around it.
 * @property finishArrow The arrow on the finish line showing which way counts.
 * @property legalMove The markers on the points the current player may move to.
 * @property oilSpill Oil-spill hazard zones.
 * @property nails Nail hazard zones, and the nails slot in the HUD.
 * @property infoBoxBackground The race info panel's background.
 * @property infoBoxText Text on the race info panel.
 * @property editorOuter The editor's outer-boundary ink; see [editorInner].
 * @property editorObstacle The editor's obstacle ink; see [editorInner].
 * @property gravel The gravel band outside the outer wall, and the gravel slot in the HUD.
 * @property gravelBarrier The barrier at the far edge of the gravel band, and the cuts across it.
 */
data class TrackPalette(
    val background: Color,
    val gridDot: Color,
    val fineGridDot: Color,
    val wall: Color,
    val finishLine: Color,
    val finishArrow: Color,
    val legalMove: Color,
    val oilSpill: Color,
    val nails: Color,
    /** HUD text over the track surface - player names, lap counts, the turn clock. */
    val hudText: Color,
    val infoBoxBackground: Color,
    val infoBoxText: Color,
    /** The track editor's ink: inner boundary, outer boundary, obstacle. */
    val editorInner: Color,
    val editorOuter: Color,
    val editorObstacle: Color,
    /**
     * How opaque a hazard zone (oil, nails) is painted over the track surface.
     *
     * Higher on the dark sheet: the same colour at the same alpha reads as a muddy brown on
     * navy but as bright orange on white, because compositing pulls it towards whatever is
     * behind it. Nails and oil change how a car behaves, so they have to be legible, not
     * merely present.
     */
    val modifierZoneAlpha: Float,
    val gravel: Color,
    val gravelBarrier: Color,
)

/** The look the app shipped with, unchanged - a white sheet with dark line work. */
val LightTrackPalette = TrackPalette(
    background = Color(0xFFFAFAFA),
    gridDot = Color(0xFF9E9E9E),
    fineGridDot = Color(0xFFE0E0E0),
    wall = Color(0xFF212121),
    finishLine = Color(0xFF6D4C41),
    finishArrow = Color(0xFFFFFFFF),
    legalMove = Color(0xFF00C853),
    oilSpill = Color(0xFF37474F),
    nails = Color(0xFFFF6F00),
    hudText = Color(0xFF212121),
    infoBoxBackground = Color(0xFF212121),
    infoBoxText = Color(0xFFFAFAFA),
    editorInner = Color(0xFF6D4C41),
    editorOuter = Color(0xFF212121),
    editorObstacle = Color(0xFFE53935),
    modifierZoneAlpha = 0.45f,
    // Sand, darker than the sheet so the band reads as ground beside the track, not as paper.
    gravel = Color(0xFFE6DAB8),
    gravelBarrier = Color(0xFF8D6E63),
)

/**
 * Sampled from the Play feature graphic (`art/`, `fastlane/.../featureGraphic.png`), which is
 * where this app's dark look was already decided: a deep navy sheet, pale blue-grey line work,
 * and the same green for the move you may make next. The values below are the graphic's own -
 * `#1A2038` is its most common background pixel, `#4CAF50` its trail green, `#E53935` its car.
 *
 * Grid dots are deliberately the brightest thing on the empty board here. On the light sheet
 * they could afford to be faint because everything around them was white; on navy they are the
 * only thing telling you where you may land, so they carry real contrast.
 */
val DarkTrackPalette = TrackPalette(
    background = Color(0xFF1A2038),
    gridDot = Color(0xFF8592C0),
    fineGridDot = Color(0xFF39426B),
    wall = Color(0xFFC9CFE3),
    finishLine = Color(0xFFE8EAF2),
    // Dark arrow on the pale finish line, the inverse of the light palette's white-on-brown.
    finishArrow = Color(0xFF1A2038),
    legalMove = Color(0xFF4CAF50),
    // A slick reads as *lighter* than navy asphalt; the light palette's dark slate would
    // disappear into the background here.
    oilSpill = Color(0xFF46527A),
    nails = Color(0xFFFF9E1B),
    hudText = Color(0xFFE3E7F2),
    infoBoxBackground = Color(0xFF0E1324),
    infoBoxText = Color(0xFFE8EAF2),
    editorInner = Color(0xFFD7A08C),
    editorOuter = Color(0xFFE3E7F2),
    editorObstacle = Color(0xFFFF7961),
    modifierZoneAlpha = 0.68f,
    // Sand in shadow: warm against the navy asphalt, and dark enough that the grid dots on it
    // keep their contrast.
    gravel = Color(0xFF4A4535),
    gravelBarrier = Color(0xFFB59A85),
)

/**
 * The palette for the user's dark-mode setting: [DarkTrackPalette] when [darkMode], else
 * [LightTrackPalette].
 */
fun trackPaletteFor(darkMode: Boolean): TrackPalette =
    if (darkMode) DarkTrackPalette else LightTrackPalette

/**
 * Static rather than dynamic: the palette changes only when the user flips the setting, and a
 * static local avoids every canvas in the tree re-subscribing on unrelated recompositions.
 */
val LocalTrackPalette = staticCompositionLocalOf { LightTrackPalette }
