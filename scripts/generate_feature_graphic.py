#!/usr/bin/env python3
"""Render Grid Racer's Play feature graphic, once per store locale.

art/feature_graphic.svg carries the English text. The "Grid Racer" wordmark is
the app's name and stays as-is in every locale; the tagline below it is the one
translatable run, marked with id="tagline" in the SVG.

Translations run longer than English - the German tagline is ~22% wider than
the English at the same size and would spill off the 1024px canvas - so each
locale's font-size is shrunk just enough to fit the measured width budget,
rather than the text being trimmed or allowed to overflow.

    python3 scripts/generate_feature_graphic.py

Writes one PNG per locale as art/feature_graphic.<locale>.png, plus
art/feature_graphic.png for the default locale (also the website banner). The
play-store-listing skill's build_play_listing.py copies them into the fastlane
tree with --feature-graphic 'art/feature_graphic.{locale}.png'.

Needs Pillow and rsvg-convert.
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageFont

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_SVG = REPO_ROOT / "art/feature_graphic.svg"
DEFAULT_PNG = REPO_ROOT / "art/feature_graphic.png"
ART_DIR = REPO_ROOT / "art"

WIDTH, HEIGHT = 1024, 500
FONT = "/usr/share/fonts/TTF/DejaVuSans.ttf"   # what "DejaVu Sans" resolves to here
TAGLINE_X = 452
TAGLINE_MAX_RIGHT = 964    # leaves a 60px margin; Play crops and overlays at the edges
BASE_FONT_SIZE = 26
MIN_FONT_SIZE = 20

DEFAULT_LOCALE = "en-US"
TAGLINES = {
    "en-US": "Race the grid. Draw your own tracks.",
    "de-DE": "Rennen im Raster. Eigene Strecken zeichnen.",
    "ru-RU": "Гонки по сетке. Рисуйте свои трассы.",
}


def fitted_font_size(text: str) -> int:
    """Largest size from BASE_FONT_SIZE down that keeps the run inside the canvas."""
    budget = TAGLINE_MAX_RIGHT - TAGLINE_X
    for size in range(BASE_FONT_SIZE, MIN_FONT_SIZE - 1, -1):
        if ImageFont.truetype(FONT, size).getlength(text) <= budget:
            return size
    return MIN_FONT_SIZE


def localized_svg(template: str, text: str, size: int) -> str:
    def replace(match: re.Match) -> str:
        attrs = re.sub(r'font-size="\d+"', f'font-size="{size}"', match.group(1))
        return f"<text{attrs}>{text}</text>"

    svg, count = re.subn(r'<text([^>]*\bid="tagline"[^>]*)>[^<]*</text>', replace, template)
    if count != 1:
        sys.exit(f'{SOURCE_SVG}: expected exactly one <text id="tagline">, found {count}')
    return svg


def render(svg: str, path: Path) -> None:
    if not shutil.which("rsvg-convert"):
        sys.exit("rsvg-convert not found; install librsvg to regenerate the feature graphic.")
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "in.svg"
        out = Path(tmp) / "out.png"
        src.write_text(svg)
        proc = subprocess.run(
            ["rsvg-convert", "-w", str(WIDTH), "-h", str(HEIGHT), "-f", "png", "-o", out, src],
            capture_output=True, text=True)
        if proc.returncode != 0 or not out.exists():
            sys.exit(f"rsvg-convert failed:\n{proc.stderr.strip()}")
        # Play wants the feature graphic flat: transparency renders as black.
        Image.open(out).convert("RGB").save(path, format="PNG", optimize=True)


def main() -> None:
    template = SOURCE_SVG.read_text()
    if TAGLINES[DEFAULT_LOCALE] not in template:
        sys.exit(f"{SOURCE_SVG}: tagline text does not match TAGLINES['{DEFAULT_LOCALE}']; "
                 "update one to match the other.")

    for locale, text in TAGLINES.items():
        size = fitted_font_size(text)
        width = ImageFont.truetype(FONT, size).getlength(text)
        svg = localized_svg(template, text, size)
        target = ART_DIR / f"feature_graphic.{locale}.png"
        render(svg, target)
        if locale == DEFAULT_LOCALE:
            render(svg, DEFAULT_PNG)

        rendered = Image.open(target)
        if rendered.size != (WIDTH, HEIGHT) or rendered.mode != "RGB":
            sys.exit(f"{target}: must be {WIDTH}x{HEIGHT} RGB, got {rendered.size} {rendered.mode}")
        note = "" if size == BASE_FONT_SIZE else f"  (shrunk from {BASE_FONT_SIZE}px to fit)"
        print(f"{locale}: {size}px, tagline {width:.0f}px wide, "
              f"ends at x={TAGLINE_X + width:.0f}/{TAGLINE_MAX_RIGHT}{note}")
        print(f"         {target.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
