#!/usr/bin/env python3
"""Drives the app on an emulator and captures the Play listing screenshots.

Seven screens per locale: the menu, race setup, a race a few rounds in, the track list, the
editor mid-draw, the editor's review step, and an online room with its code ready to share.

Buttons are found by their *localised* label, read out of the app's own res/values*/strings.xml,
so the same walk runs in English, German and Russian without per-language coordinates. The
coordinates that are hard-coded (start slots, editor tools, the shapes drawn) assume the
`GridRacerTest` AVD at its native 2340x1080 landscape.

The move pickers in a race are Canvas drawing and invisible to uiautomator, so `legal_moves`
finds them by colour; the freehand strokes in the editor have to go out as one batched
`input motionevent` call, because one call per point is slow enough that Compose reads the
whole stroke as a tap.

An online room needs a server. Run one locally and point the app at it:

    PORT=8099 GRIDRACER_DB_PATH=/tmp/shots.sqlite GRIDRACER_RACE_LOG=off ./gradlew :server:run
    adb shell "run-as info.piepgras.gridracer sh -c 'cat > files/settings.json'" <<< \
        '{"legalDisclaimerAccepted":true,"multiplayerServerUrl":"ws://10.0.2.2:8099/play",
          "crashReportingPromptShown":true,"playerName":"Alex"}'

It drives whatever plain `adb` reaches. With a phone attached as well, pin it to the emulator
first - `export ANDROID_SERIAL=emulator-5554` - or it either stops at "more than one device" or
plays on the phone.

Usage:
    python3 art/capture_screenshots.py OUT_DIR [en de ru]
    python3 art/prepare_screenshots.py fastlane/.../phoneScreenshots OUT_DIR/en/*.png
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PKG = "info.piepgras.gridracer"

def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw).stdout

def adb(*args):
    return sh("adb", *args)

def strings(lang):
    """The app's string resources for one language, name -> text."""
    d = "values" if lang == "en" else f"values-{lang}"
    tree = ET.parse(REPO / "app/src/main/res" / d / "strings.xml")
    out = {}
    for node in tree.getroot():
        if node.tag == "string" and node.get("name"):
            out[node.get("name")] = "".join(node.itertext())
    if lang != "en":  # fall back to English for untranslated strings
        base = strings("en")
        for k, v in base.items():
            out.setdefault(k, v)
    return out

def dump():
    for _ in range(6):
        adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
        xml = adb("shell", "cat", "/sdcard/u.xml")
        if "<hierarchy" in xml:
            return xml
        time.sleep(0.5)
    return ""

def find(text, xml=None):
    """Centre of the node whose text equals `text`, or None."""
    xml = xml if xml is not None else dump()
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1) == text:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None

def tap(text, timeout=8):
    end = time.time() + timeout
    while time.time() < end:
        at = find(text)
        if at:
            adb("shell", "input", "tap", str(at[0]), str(at[1]))
            time.sleep(1.2)
            return True
        time.sleep(0.5)
    print(f"  !! could not find {text!r}", file=sys.stderr)
    return False

def shot(out: Path, name):
    time.sleep(0.8)
    raw = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    (out / f"{name}.png").write_bytes(raw)
    print(f"  {name}.png ({len(raw)} bytes)")

def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.2)

def home_menu(s):
    """Get back to the main menu from wherever we are."""
    for _ in range(6):
        if find(s["menu_start_race"]) and find(s["menu_settings"]):
            return
        back()
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(5)

# ---------------------------------------------------------------- gestures

def stroke(points, hold=0.25):
    """A freehand stroke. DOWN+MOVEs go in one shell call, UP on its own —
    one call per point is slow enough that Compose reads the whole thing as a tap."""
    x0, y0 = points[0]
    cmd = [f"input motionevent DOWN {x0} {y0}"]
    cmd += [f"input motionevent MOVE {x} {y}" for x, y in points[1:]]
    adb("shell", "; ".join(cmd))
    time.sleep(hold)
    adb("shell", f"input motionevent UP {points[-1][0]} {points[-1][1]}")
    time.sleep(0.8)

def ellipse(cx, cy, rx, ry, n=28, start=0.0):
    import math
    return [(int(cx + rx * math.cos(start + 2 * math.pi * i / n)),
             int(cy + ry * math.sin(start + 2 * math.pi * i / n))) for i in range(n + 1)]

def drag(x1, y1, x2, y2, ms=400):
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
    time.sleep(1.0)

def scroll_to(text, tries=8, up_first=True):
    """Scroll the current list until `text` is on screen; returns its centre or None."""
    if up_first:
        for _ in range(6):
            adb("shell", "input", "swipe", "1200", "300", "1200", "900", "250")
        time.sleep(1.0)
    for _ in range(tries):
        at = find(text)
        if at:
            return at
        adb("shell", "input", "swipe", "1200", "800", "1200", "500", "250")
        time.sleep(0.8)
    return None

def wait_for(text, timeout=10):
    """Block until `text` is on screen. Tapping and shooting blind is how a run ends up with
    two identical screenshots and a silently shifted sequence."""
    end = time.time() + timeout
    while time.time() < end:
        if find(text):
            return True
        time.sleep(0.5)
    print(f"  !! {text!r} never appeared", file=sys.stderr)
    return False

def tap_at(at):
    adb("shell", "input", "tap", str(at[0]), str(at[1]))
    time.sleep(1.2)

# ---------------------------------------------------------------- race driving

def legal_moves():
    """Centres of the green legal-move dots on screen, found by colour.

    The move pickers are Canvas drawing, so they are invisible to uiautomator;
    the only way to press one from a script is to look at the pixels.
    """
    import numpy as np
    from PIL import Image
    import io
    raw = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    a = np.asarray(Image.open(io.BytesIO(raw)).convert("RGB")).astype(int)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    mask = (g > 120) & (r < g - 50) & (b < g - 50)          # green, and clearly not grey
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return []
    # cluster by proximity: the dots are far apart compared with their radius
    dots, used = [], np.zeros(len(xs), bool)
    for i in range(len(xs)):
        if used[i]:
            continue
        near = (np.abs(xs - xs[i]) < 25) & (np.abs(ys - ys[i]) < 25) & ~used
        used |= near
        if near.sum() > 40:
            dots.append((int(xs[near].mean()), int(ys[near].mean())))
    return dots

def play_turns(n=4):
    """Take `n` turns, each time picking the middle offered point — the coast, near enough,
    which keeps the car on a straight rather than steering it into a wall."""
    for turn in range(n):
        dots = legal_moves()
        if not dots:
            print(f"  turn {turn}: no offered points (AI turn or race over)")
            time.sleep(2.5)
            continue
        dots.sort()
        x, y = dots[len(dots) // 2]
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(2.5)          # our move, then the AI's

# ---------------------------------------------------------------- the walk

def find_all(text, xml=None):
    xml = xml if xml is not None else dump()
    out = []
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1) == text:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            out.append(((x1 + x2) // 2, (y1 + y2) // 2))
    return out

def set_language(lang):
    """The app keeps its language in its own SharedPreferences (LocaleHelper), so the
    screenshots can be taken per locale without touching the emulator's system locale."""
    adb("shell", "am", "force-stop", PKG)
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
           f"<map>\n    <string name=\"selected_language\">{lang}</string>\n</map>\n")
    Path("/tmp/lp.xml").write_text(xml)
    subprocess.run(["adb", "push", "/tmp/lp.xml", "/data/local/tmp/lp.xml"], capture_output=True)
    adb("shell", "chmod", "666", "/data/local/tmp/lp.xml")
    adb("shell", "run-as", PKG, "mkdir", "-p", "shared_prefs")
    adb("shell", "run-as", PKG, "cp", "/data/local/tmp/lp.xml", "shared_prefs/grid_racer_locale_prefs.xml")

def launch():
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(5)

def capture_locale(lang, out: Path):
    s = strings(lang)
    out.mkdir(parents=True, exist_ok=True)
    set_language(lang)
    launch()
    print(f"[{lang}]")

    # 1. main menu
    shot(out, "01_menu")

    # 2. race setup - the mode row shows local and the three online modes
    tap(s["menu_start_race"])
    wait_for(s["setup_mode_host"])
    shot(out, "02_race_setup")

    # 3. a local race, a few rounds in
    confirm = sorted(find_all(s["menu_start_race"]))[-1]      # the button, not the heading
    tap_at(confirm)
    time.sleep(2)
    # The start slots are found by colour like the move pickers: their position depends on
    # how the board is fitted to the screen, which moved when the gravel band joined the view.
    # Each player takes the lowest free slot, whichever of them picks first (the placement
    # order may be random): those two are clear of the nails, so neither car is held back
    # and then steered by `play_turns` back over the line - which is a crash.
    for _ in range(2):
        slots = sorted(legal_moves(), key=lambda d: d[1])     # top to bottom along the line
        tap_at(slots[-1])
        time.sleep(1)
    time.sleep(1)
    play_turns(9)
    shot(out, "03_race")
    home_menu(s)

    # 4. the track list
    tap(s["menu_tracks"])
    wait_for(s["tracks_new_track"], timeout=4) or time.sleep(2)
    for _ in range(6):
        adb("shell", "input", "swipe", "1200", "300", "1200", "900", "250")   # back to the top
    time.sleep(1)
    shot(out, "04_tracks")

    # 5. the editor, mid-draw, with the hazard palette open
    at = scroll_to(s["tracks_new_track"])
    tap_at(at)
    time.sleep(2)
    # The grid the editor draws on is square and narrower than the landscape canvas, so a
    # stroke wider than it is clamped to its edge - which does not read as a clamp, it reads
    # as a track with a dead-straight wall down one side. The 1.1 listing screenshots shipped
    # exactly that on both flanks (outer rx was 610, half the grid is ~540). Keep the outer
    # loop inside the grid with margin to spare. The grid is not centred on the canvas: it
    # runs x 480..1439 on this AVD, centre ~960, so strokes are centred there and not on the
    # canvas midpoint - centring on 880 clipped the left flank while leaving the right clean.
    stroke(ellipse(960, 540, 270, 160))                       # inner loop
    stroke(ellipse(960, 540, 400, 360))                       # outer loop, clear of the edge
    tap(s["editor_draw_kind_oil_spill"])
    stroke(ellipse(960, 255, 95, 62, n=20))                   # a spill in the top straight
    shot(out, "05_editor")

    # 6. the review step, with the track named
    adb("shell", "input", "tap", "2192", "472")               # finish-line tool
    time.sleep(1.5)
    drag(960, 710, 960, 885, 500)                             # across the bottom straight
    tap(s["editor_direction_a_description"])
    adb("shell", "input", "tap", "1939", "250")               # save -> review
    time.sleep(2)
    name = find(s["editor_track_name_label"])
    if name:
        tap_at(name)
        # `input text` splits on spaces; %s is its escape for one.
        adb("shell", "input", "text", TRACK_NAME[lang].replace(" ", "%s"))
        adb("shell", "input", "keyevent", "111")              # ESC, to drop the keyboard
        time.sleep(1)
    shot(out, "06_editor_review")
    adb("shell", "input", "tap", "2270", "139")               # close the editor, saving nothing
    time.sleep(2)
    home_menu(s)

    # 7. an online room, with its code ready to share
    tap(s["menu_start_race"])
    wait_for(s["setup_mode_host"])
    tap(s["setup_mode_host"])
    time.sleep(2)
    tap(s["multiplayer_host"])
    time.sleep(3)
    tap(s["multiplayer_add_ai"])      # fill the second seat, so the lobby is not half empty
    time.sleep(1.5)
    shot(out, "07_online")
    home_menu(s)

TRACK_NAME = {"en": "Sunday Loop", "de": "Sonntagsrunde", "ru": "Grand Oval"}


if __name__ == "__main__":
    out = Path(sys.argv[1])
    for lang in (sys.argv[2:] or ["en", "de", "ru"]):
        capture_locale(lang, out / lang)
