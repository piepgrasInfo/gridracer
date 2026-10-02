#!/usr/bin/env python3
"""Generate GridRacer's launcher icon set and Play listing icon assets.

The icon is authored here as vector geometry on the adaptive-icon canvas
(108x108, foreground fitted into the centered 72x72 safe zone) and shows one
turn of the game, using the same vocabulary the race view draws with: the red
player-1 car (proportions from TrackCanvas.drawCar()), its red trail fading out
behind it, the dashed green projector line running from the car's nose to where
it would coast to, and the 3x3 green selector of the nine points it may pick.

Outputs, all derived from the same geometry:

    app/src/main/res/mipmap-*dpi/         legacy + adaptive + monochrome layers
    app/src/main/res/mipmap-anydpi-v26/   adaptive-icon XML
    art/ic_launcher.svg                   1. main vector graphic, colored
    art/ic_launcher_monochrome.svg        2. monochrome, from the foreground
    art/ic_launcher_512.png               3. 512x512 32-bit PNG for Play

and, because they are byte copies of asset 3 that nothing else refreshes:

    fastlane/metadata/android/*/images/icon.png    the store listing's icon

Re-run whenever the icon needs regenerating - it's deterministic, matching the
generate_sounds.py pattern used for this project's other code-generated assets.
Needs Pillow and rsvg-convert (librsvg).

    python3 scripts/generate_launcher_icon.py
"""
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = REPO_ROOT / "app/src/main/res"
ART_DIR = REPO_ROOT / "art"
FASTLANE_ICONS = "fastlane/metadata/android/*/images/icon.png"

CANVAS = 108          # adaptive-icon canvas, in dp
SAFE_ZONE = 72        # centered square the foreground must stay inside
SAFE_ZONE_MIN = (CANVAS - SAFE_ZONE) / 2
SAFE_ZONE_MAX = (CANVAS + SAFE_ZONE) / 2
MASK_CIRCLE = 72      # widest round mask a launcher applies, inscribed in that square
KEYLINE_CIRCLE = 66   # Material's stricter keyline circle, reported on but not enforced
PLAYSTORE_SIZE = 512
PLAYSTORE_MAX_BYTES = 1024 * 1024
FIT_RENDER = 864      # resolution the foreground auto-fit measures at
# How much of the 72x72 safe zone the mark's bounding box has to span. Not
# 100%: the car and the selector have to stay inside the round mask's circle,
# and a circle only reaches a square's edges at their midpoints - a mark laid
# out along a diagonal spans that circle end to end (which this one does) and
# still leaves the square's corners to the trail alone.
MIN_SAFE_ZONE_FILL = 0.85

LEGACY_SIZES = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
ADAPTIVE_SIZES = {"mipmap-mdpi": 108, "mipmap-hdpi": 162, "mipmap-xhdpi": 216, "mipmap-xxhdpi": 324, "mipmap-xxxhdpi": 432}

BG_START = "#12182E"      # preview's dark blue, top-left
BG_END = "#202842"        # preview's dark blue, bottom-right

RED = "#E53935"           # PlayerColors[0], the player-1 car and its trail
GREEN = "#00C853"         # LegalMoveColor: the nine selector dots
# The projector line is the same green family - it belongs to the same "where
# you may go" language - but a lighter, mintier tint, so at launcher sizes the
# dashes read as their own element instead of merging with the selector's dots.
PROJECTOR_GREEN = "#69F0AE"
WHITE = "#FFFFFF"
WHEEL = "#1A1A1A"         # drawCar() wheel color
WINDSHIELD = "#B3E5FC"    # drawCar() 0x80B3E5FC
WINDSHIELD_OPACITY = 0.9  # drawCar() blends at 0x80; over red that mostly disappears

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""

# --- Geometry -------------------------------------------------------------
# Authored in safe-zone coordinates: the origin is the canvas center and +-36
# is the edge of the 72x72 safe zone. The mark runs along one diagonal, so the
# two things placed at its ends decide the framing - the trail's tail reaches
# into the near corner, the selector sits at the far end. Everything is fitted
# into the safe zone afterwards (see safe_zone_transform), so these are
# relative positions: nudge one and the whole mark re-frames around it.

ANGLE = -42.0                     # car heading, degrees (up and to the right)
_rad = math.radians(ANGLE)
FWD = (math.cos(_rad), math.sin(_rad))                  # along the car's axis

CAR = (-7.0, 8.0)                 # car center, safe-zone coordinates
BODY_LEN = 34.0                   # long enough to carry the icon on its own
CELL = BODY_LEN / 0.7             # drawCar()'s cellSize, back-computed
BODY_W = CELL * 0.38
BODY_R = CELL * 0.08
WINDOW_OFF = BODY_LEN / 2 * 0.5   # drawCar() windowOffset
WINDOW_LEN = BODY_LEN * 0.25      # along the car's axis
WINDOW_W = BODY_W * 0.35
WINDOW_R = CELL * 0.03
WHEEL_W = CELL * 0.10
WHEEL_R = CELL * 0.03
WHEEL_OFF_FWD = BODY_LEN / 2 * 0.7
# Two deliberate departures from drawCar(), where the car is read at a whole
# cell's width rather than at 48px: taller wheels, set far enough out that they
# clear the bodywork. In-game they barely break the body's outline, and at
# launcher sizes that leaves the car reading as a plain rounded rectangle.
WHEEL_H = CELL * 0.165
WHEEL_OFF_SIDE = BODY_W / 2 * 0.95

# The nine points the player may move to, centered on where the car would
# coast to if it held its velocity - the same 3x3 the race view offers.
PROJECTION = 43.0                 # car center to selector center, along its heading
SELECTOR_SPACING = 10.0           # wide enough that the dots read as nine choices
SELECTOR_DOT_R = 3.0

# Dashed projector line, from the car's nose to the selector's center dot.
PROJECTOR_W = 2.4
PROJECTOR_DASH = (3.4, 2.2)
PROJECTOR_GAP_NOSE = 2.0          # clear of the bodywork
PROJECTOR_GAP_DOT = 1.2           # stops just short of the center dot

# Trail of the moves already driven, fading out behind the car the way
# drawTrail() fades a player's last few segments. Walked backwards from the
# car's rear: each step turns by TRAIL_TURN, so the trail arrives on a bend
# rather than reading as one straight spike out of the car's tail.
TRAIL_W = 4.4
TRAIL_GAP = 2.0                   # clear of the bodywork, so the car reads separately
TRAIL_STEPS = (10.0, 9.0, 8.0)
TRAIL_TURN = -10.0
TRAIL_ALPHAS = (0.9, 0.6, 0.35)   # freshest segment first, at the car


def add(a, b, k=1.0):
    return (a[0] + b[0] * k, a[1] + b[1] * k)


CAR_NOSE = add(CAR, FWD, BODY_LEN / 2)
CAR_REAR = add(CAR, FWD, -BODY_LEN / 2)
SELECTOR = add(CAR, FWD, PROJECTION)


def trail_points():
    """Trail nodes from the car's rear backwards, oldest last."""
    points = [add(CAR_REAR, FWD, -TRAIL_GAP)]
    heading = ANGLE + 180.0
    for step in TRAIL_STEPS:
        rad = math.radians(heading)
        points.append(add(points[-1], (math.cos(rad), math.sin(rad)), step))
        heading += TRAIL_TURN
    return points


TRAIL = trail_points()

if len(TRAIL_ALPHAS) != len(TRAIL_STEPS):
    sys.exit("TRAIL_ALPHAS needs one opacity per trail step, or segments go missing")


def f(value: float) -> str:
    """Format a coordinate compactly."""
    return f"{value:.3f}".rstrip("0").rstrip(".")


def c(point: tuple[float, float]) -> tuple[float, float]:
    """Safe-zone coordinates to canvas coordinates."""
    return (CANVAS / 2 + point[0], CANVAS / 2 + point[1])


def trail_group(color: str, alphas: tuple[float, ...]) -> str:
    """The red trail: newest segment at the car, oldest fading out behind."""
    parts = []
    for (a, b), alpha in zip(zip(TRAIL, TRAIL[1:]), alphas):
        ax, ay = c(a)
        bx, by = c(b)
        parts.append(f'<line x1="{f(ax)}" y1="{f(ay)}" x2="{f(bx)}" y2="{f(by)}" '
                     f'stroke="{color}" stroke-opacity="{alpha:.3f}" '
                     f'stroke-width="{f(TRAIL_W)}" stroke-linecap="round"/>')
    return "\n    ".join(parts)


def projector_line(color: str) -> str:
    """Dashed line from the car's nose to the point it would coast to."""
    start = add(CAR_NOSE, FWD, PROJECTOR_GAP_NOSE)
    end = add(SELECTOR, FWD, -(SELECTOR_DOT_R + PROJECTOR_GAP_DOT))
    sx, sy = c(start)
    ex, ey = c(end)
    dash = f"{f(PROJECTOR_DASH[0])} {f(PROJECTOR_DASH[1])}"
    return (f'<line x1="{f(sx)}" y1="{f(sy)}" x2="{f(ex)}" y2="{f(ey)}" '
            f'stroke="{color}" stroke-width="{f(PROJECTOR_W)}" stroke-linecap="round" '
            f'stroke-dasharray="{dash}"/>')


def selector_dots(color: str) -> str:
    """The nine points the player may pick, on the coarse grid's lattice."""
    parts = []
    for dv in (-1, 0, 1):
        for du in (-1, 0, 1):
            px, py = c((SELECTOR[0] + du * SELECTOR_SPACING,
                        SELECTOR[1] + dv * SELECTOR_SPACING))
            parts.append(f'<circle cx="{f(px)}" cy="{f(py)}" r="{f(SELECTOR_DOT_R)}" fill="{color}"/>')
    return "\n    ".join(parts)


def selector_dots_monochrome() -> str:
    """As above, but the center dot - the one the projector line lands on - is
    a ring. Under a single tint a filled center dot is indistinguishable from
    its eight neighbours, and the line arriving at it is what makes the cluster
    read as a choice rather than as decoration."""
    outer, inner = SELECTOR_DOT_R, SELECTOR_DOT_R * 0.42
    parts = []
    for dv in (-1, 0, 1):
        for du in (-1, 0, 1):
            px, py = c((SELECTOR[0] + du * SELECTOR_SPACING,
                        SELECTOR[1] + dv * SELECTOR_SPACING))
            if du == 0 and dv == 0:
                parts.append(
                    f'<path fill="{WHITE}" fill-rule="evenodd" '
                    f'd="M{f(px - outer)},{f(py)} a{f(outer)},{f(outer)} 0 1 0 {f(2 * outer)},0 '
                    f'a{f(outer)},{f(outer)} 0 1 0 {f(-2 * outer)},0 Z '
                    f'M{f(px - inner)},{f(py)} a{f(inner)},{f(inner)} 0 1 0 {f(2 * inner)},0 '
                    f'a{f(inner)},{f(inner)} 0 1 0 {f(-2 * inner)},0 Z"/>')
            else:
                parts.append(f'<circle cx="{f(px)}" cy="{f(py)}" r="{f(SELECTOR_DOT_R)}" fill="{WHITE}"/>')
    return "\n    ".join(parts)


def car_colored() -> str:
    cx, cy = c(CAR)
    wheels = "\n      ".join(
        f'<rect x="{f(WHEEL_OFF_FWD * sf - WHEEL_W / 2)}" y="{f(WHEEL_OFF_SIDE * ss - WHEEL_H / 2)}" '
        f'width="{f(WHEEL_W)}" height="{f(WHEEL_H)}" rx="{f(WHEEL_R)}" fill="{WHEEL}"/>'
        for sf in (1, -1) for ss in (-1, 1))
    return f"""<g transform="translate({f(cx)},{f(cy)}) rotate({f(ANGLE)})">
      <rect x="{f(-BODY_LEN / 2)}" y="{f(-BODY_W / 2)}" width="{f(BODY_LEN)}" height="{f(BODY_W)}" rx="{f(BODY_R)}" fill="{RED}"/>
      <rect x="{f(WINDOW_OFF)}" y="{f(-WINDOW_W / 2)}" width="{f(WINDOW_LEN)}" height="{f(WINDOW_W)}" rx="{f(WINDOW_R)}" fill="{WINDSHIELD}" fill-opacity="{WINDSHIELD_OPACITY:.3f}"/>
      {wheels}
    </g>"""


def car_monochrome() -> str:
    """Body with the windshield knocked out, so the car still reads as a car
    once Android repaints the whole layer in a single tint. Wheels stay solid:
    they extend just past the body edge, which keeps the silhouette's notches."""
    cx, cy = c(CAR)
    bl, bw = BODY_LEN / 2, BODY_W / 2
    body = (f"M{f(-bl + BODY_R)},{f(-bw)} h{f(BODY_LEN - 2 * BODY_R)} "
            f"a{f(BODY_R)},{f(BODY_R)} 0 0 1 {f(BODY_R)},{f(BODY_R)} v{f(BODY_W - 2 * BODY_R)} "
            f"a{f(BODY_R)},{f(BODY_R)} 0 0 1 {f(-BODY_R)},{f(BODY_R)} h{f(-(BODY_LEN - 2 * BODY_R))} "
            f"a{f(BODY_R)},{f(BODY_R)} 0 0 1 {f(-BODY_R)},{f(-BODY_R)} v{f(-(BODY_W - 2 * BODY_R))} "
            f"a{f(BODY_R)},{f(BODY_R)} 0 0 1 {f(BODY_R)},{f(-BODY_R)} Z")
    # Shorter than the colored windshield, which runs flush to the body's nose:
    # as a cutout it has to keep a rim of body around it, or it reads as a bite
    # taken out of the front rather than as a cockpit.
    window_len = WINDOW_LEN * 0.7
    window = (f"M{f(WINDOW_OFF)},{f(-WINDOW_W / 2)} h{f(window_len)} v{f(WINDOW_W)} "
              f"h{f(-window_len)} Z")
    wheels = "\n      ".join(
        f'<rect x="{f(WHEEL_OFF_FWD * sf - WHEEL_W / 2)}" y="{f(WHEEL_OFF_SIDE * ss - WHEEL_H / 2)}" '
        f'width="{f(WHEEL_W)}" height="{f(WHEEL_H)}" rx="{f(WHEEL_R)}" fill="{WHITE}"/>'
        for sf in (1, -1) for ss in (-1, 1))
    return f"""<g transform="translate({f(cx)},{f(cy)}) rotate({f(ANGLE)})">
      {wheels}
      <path fill="{WHITE}" fill-rule="evenodd" d="{body} {window}"/>
    </g>"""


def background_group() -> str:
    """Plain gradient. Deliberately empty otherwise: the grid dots this used to
    carry competed with the selector's dots at launcher sizes."""
    return f"""  <g id="background">
    <rect x="0" y="0" width="{CANVAS}" height="{CANVAS}" fill="url(#bg)"/>
  </g>"""


def foreground_group(transform: str) -> str:
    return f"""  <g id="foreground"{transform}>
    {trail_group(RED, TRAIL_ALPHAS)}
    {projector_line(PROJECTOR_GREEN)}
    {selector_dots(GREEN)}
    {car_colored()}
  </g>"""


def monochrome_group(transform: str) -> str:
    # Android masks this layer by its alpha, so the trail keeps fading; it is
    # only lifted off the floor a little, since a tint over a dark wallpaper
    # loses the faintest steps entirely.
    alphas = tuple(0.45 + 0.55 * a for a in TRAIL_ALPHAS)
    return f"""  <g id="foreground"{transform}>
    {trail_group(WHITE, alphas)}
    {projector_line(WHITE)}
    {selector_dots_monochrome()}
    {car_monochrome()}
  </g>"""


def essentials_group(transform: str) -> str:
    """Car and selector only - the parts a launcher mask must not cut into.
    The trail is deliberately left out: it is what reaches into the corners of
    the safe zone, and a round mask trimming its tail costs nothing."""
    return f"""  <g id="foreground"{transform}>
    {selector_dots(GREEN)}
    {car_colored()}
  </g>"""


def svg_document(body: str, header: str, defs: str = "") -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
{header}
<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}" viewBox="0 0 {CANVAS} {CANVAS}">
{defs}{body}
</svg>
"""


MAIN_HEADER = f"""<!-- GridRacer launcher icon: adaptive-icon architecture on the {CANVAS}x{CANVAS}
     canvas. <g id="background"> is full-bleed and opaque; <g id="foreground">
     spans the centered {SAFE_ZONE}x{SAFE_ZONE} safe zone. One turn of the game: the red
     player-1 car, its trail fading out behind it, the dashed projector line
     running from its nose to the point it would coast to, and the nine green
     points it may move to.
     Generated by scripts/generate_launcher_icon.py - edit the geometry there,
     not this file. -->"""

MONO_HEADER = """<!-- GridRacer monochrome layer, derived from the foreground of ic_launcher.svg:
     same geometry and framing, one color, no background. Android 13+ repaints
     it in a wallpaper tint, so detail that read as a color contrast is drawn
     as a cutout instead - the windshield is knocked out of the car body, and
     the point the projector line lands on is a ring rather than a filled dot.
     Generated by scripts/generate_launcher_icon.py - edit the geometry there. -->"""

BG_DEFS = f"""  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="{CANVAS}" y2="{CANVAS}" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{BG_START}"/>
      <stop offset="1" stop-color="{BG_END}"/>
    </linearGradient>
  </defs>
"""


# --- Rendering ------------------------------------------------------------

def render(svg: str, size: int) -> Image.Image:
    if not shutil.which("rsvg-convert"):
        sys.exit("rsvg-convert not found; install librsvg to regenerate the icon.")
    with tempfile.TemporaryDirectory() as tmp:
        src, out = Path(tmp) / "in.svg", Path(tmp) / "out.png"
        src.write_text(svg)
        proc = subprocess.run(
            ["rsvg-convert", "-w", str(size), "-h", str(size), "-f", "png", "-o", out, src],
            capture_output=True, text=True)
        if proc.returncode != 0 or not out.exists():
            sys.exit(f"rsvg-convert failed:\n{proc.stderr.strip()}")
        return Image.open(out).convert("RGBA")


def content_bounds(layer_svg: str) -> tuple[float, float, float, float]:
    """Bounding box of a layer's art, in canvas units."""
    probe = render(layer_svg, FIT_RENDER)
    alpha = np.array(probe.getchannel("A"))
    ys, xs = np.nonzero(alpha > 8)
    if len(xs) == 0:
        sys.exit("foreground rendered empty")
    unit = FIT_RENDER / CANVAS
    return (xs.min() / unit, ys.min() / unit, (xs.max() + 1) / unit, (ys.max() + 1) / unit)


def content_reach(layer_svg: str, center: tuple[float, float] | None = None) -> float:
    """How far a layer's art gets from [center] (the canvas center by default),
    in canvas units - the radius of the smallest circular mask around that
    point which would not cut it."""
    probe = render(layer_svg, FIT_RENDER)
    alpha = np.array(probe.getchannel("A"))
    ys, xs = np.nonzero(alpha > 8)
    if len(xs) == 0:
        sys.exit("layer rendered empty")
    unit = FIT_RENDER / CANVAS
    cx, cy = center if center else (CANVAS / 2, CANVAS / 2)
    return float(np.hypot((xs + 0.5) / unit - cx, (ys + 0.5) / unit - cy).max())


def safe_zone_transform() -> str:
    """Measure the mark and frame it in the safe zone.

    Two things bound how large the mark can be drawn, and they pull against
    each other: the whole of it has to sit inside the 72x72 safe zone, and the
    car and the selector have to sit inside the circle a round launcher mask
    cuts to. Only the trail may cross that circle - it is the one part whose
    tail can be trimmed without the icon looking broken, which is what buys the
    mark the room to reach the corners of the safe zone at all.

    So: grow the mark until whichever of the two binds first, placing it as
    close to centered as both allow. Keeps the layout parameters above free of
    hand-tuned framing constants - move a shape and the whole mark re-frames.
    """
    x0, y0, x1, y1 = content_bounds(svg_document(foreground_group(""), MAIN_HEADER))
    ex0, ey0, ex1, ey1 = content_bounds(svg_document(essentials_group(""), MAIN_HEADER))
    center = ((ex0 + ex1) / 2, (ey0 + ey1) / 2)
    radius = content_reach(svg_document(essentials_group(""), MAIN_HEADER), center)

    lo, hi = 0.1, min(SAFE_ZONE / (x1 - x0), SAFE_ZONE / (y1 - y0))
    for _ in range(48):
        mid = (lo + hi) / 2
        if _offset_for(mid, (x0, y0, x1, y1), center, radius):
            lo = mid
        else:
            hi = mid
    offset = _offset_for(lo, (x0, y0, x1, y1), center, radius)
    return f' transform="translate({f(offset[0])},{f(offset[1])}) scale({f(lo)})"'


def _offset_for(scale, bounds, center, radius):
    """Translation placing the scaled mark inside the safe zone with the car
    and selector inside the mask circle, or None if [scale] admits neither.

    The safe zone allows a rectangle of translations, the mask circle a disc
    around the one that centers the car and selector exactly; the corner of
    the rectangle nearest that disc's center is the most centered translation
    both accept.
    """
    x0, y0, x1, y1 = bounds
    margin = (MASK_CIRCLE / 2) - scale * radius
    if margin < 0:
        return None
    box = [(SAFE_ZONE_MIN - scale * lo_, SAFE_ZONE_MAX - scale * hi_)
           for lo_, hi_ in ((x0, x1), (y0, y1))]
    if any(lo_ > hi_ for lo_, hi_ in box):
        return None
    ideal = (CANVAS / 2 - scale * center[0], CANVAS / 2 - scale * center[1])
    nearest = tuple(min(max(ideal[i], box[i][0]), box[i][1]) for i in (0, 1))
    if math.dist(nearest, ideal) > margin:
        return None
    return nearest


def write_png(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format="PNG")


def main() -> None:
    transform = safe_zone_transform()

    main_svg = svg_document(background_group() + "\n" + foreground_group(transform), MAIN_HEADER, BG_DEFS)
    mono_svg = svg_document(monochrome_group(transform), MONO_HEADER)
    fg_svg = svg_document(foreground_group(transform), MAIN_HEADER)
    bg_svg = svg_document(background_group(), MAIN_HEADER, BG_DEFS)

    for bucket, size in LEGACY_SIZES.items():
        flat = render(main_svg, size)
        write_png(flat, RES_DIR / bucket / "ic_launcher.png")
        write_png(flat, RES_DIR / bucket / "ic_launcher_round.png")

    for bucket, size in ADAPTIVE_SIZES.items():
        write_png(render(fg_svg, size), RES_DIR / bucket / "ic_launcher_foreground.png")
        write_png(render(bg_svg, size), RES_DIR / bucket / "ic_launcher_background.png")
        write_png(render(mono_svg, size), RES_DIR / bucket / "ic_launcher_monochrome.png")

    anydpi_dir = RES_DIR / "mipmap-anydpi-v26"
    anydpi_dir.mkdir(parents=True, exist_ok=True)
    (anydpi_dir / "ic_launcher.xml").write_text(ADAPTIVE_XML)
    (anydpi_dir / "ic_launcher_round.xml").write_text(ADAPTIVE_XML)

    ART_DIR.mkdir(parents=True, exist_ok=True)
    (ART_DIR / "ic_launcher.svg").write_text(main_svg)
    (ART_DIR / "ic_launcher_monochrome.svg").write_text(mono_svg)
    play_png = ART_DIR / "ic_launcher_512.png"
    render(main_svg, PLAYSTORE_SIZE).save(play_png, format="PNG", optimize=True)

    # --- Checks the Play Console and the launcher masks would otherwise catch ---
    # Both layers share one transform, so they stay registered; what still has
    # to hold is that the mark fills the safe zone without the car or the
    # selector straying outside the circle a round mask cuts to.
    for label, layer in (("foreground", fg_svg), ("monochrome", mono_svg)):
        x0, y0, x1, y1 = content_bounds(layer)
        fill = min(x1 - x0, y1 - y0) / SAFE_ZONE
        if max(x1 - x0, y1 - y0) > SAFE_ZONE + 0.5:
            sys.exit(f"{label} art spans {x1 - x0:.1f}x{y1 - y0:.1f}, past the {SAFE_ZONE} safe zone")
        if fill < MIN_SAFE_ZONE_FILL:
            sys.exit(f"{label} art spans {x1 - x0:.1f}x{y1 - y0:.1f}, filling only "
                     f"{fill:.0%} of the {SAFE_ZONE}x{SAFE_ZONE} safe zone")
    essential_reach = content_reach(svg_document(essentials_group(transform), MAIN_HEADER))
    if essential_reach > MASK_CIRCLE / 2 + 0.05:
        sys.exit(f"car/selector reach {essential_reach:.1f} from the canvas center; "
                 f"a round mask clips anything past {MASK_CIRCLE / 2}")
    if render(bg_svg, ADAPTIVE_SIZES["mipmap-xxxhdpi"]).getchannel("A").getextrema()[0] < 255:
        sys.exit("background layer is not fully opaque")
    play = Image.open(play_png)
    if play.size != (PLAYSTORE_SIZE, PLAYSTORE_SIZE) or play.mode != "RGBA":
        sys.exit(f"{play_png} must be {PLAYSTORE_SIZE}x{PLAYSTORE_SIZE} RGBA, got {play.size} {play.mode}")
    if play_png.stat().st_size > PLAYSTORE_MAX_BYTES:
        sys.exit(f"{play_png} is {play_png.stat().st_size // 1024} KB, over Play's 1024 KB cap")

    # The listing icon exists a second time as a byte copy per store locale,
    # put there by the play-store-listing skill's build_play_listing.py. Nothing
    # regenerates those when this script runs, and a stale one is invisible
    # until the old icon shows up on the store page - so refresh them here.
    exported = sorted(REPO_ROOT.glob(FASTLANE_ICONS))
    stale = [t for t in exported if t.read_bytes() != play_png.read_bytes()]
    for target in stale:
        shutil.copyfile(play_png, target)

    x0, y0, x1, y1 = content_bounds(fg_svg)
    print(f"Mark spans {x1 - x0:.1f}x{y1 - y0:.1f} of the {SAFE_ZONE}x{SAFE_ZONE} safe zone; "
          f"car and selector stay within a {2 * essential_reach:.1f}dp circle "
          f"(round mask cuts at {MASK_CIRCLE}, Material's keyline at {KEYLINE_CIRCLE})")
    print(f"Wrote legacy + adaptive + monochrome launcher icons under {RES_DIR}")
    print(f"Wrote Play listing assets to {ART_DIR}/ic_launcher.svg, ic_launcher_monochrome.svg, "
          f"ic_launcher_512.png ({play_png.stat().st_size // 1024} KB)")
    if stale:
        print(f"Refreshed the fastlane listing icon in {len(stale)} of {len(exported)} locale(s): "
              f"{', '.join(p.parents[1].name for p in stale)}")
    elif exported:
        print(f"Fastlane listing icon already current in all {len(exported)} locale(s)")
    else:
        print(f"No fastlane listing icons found at {FASTLANE_ICONS}")


if __name__ == "__main__":
    main()
