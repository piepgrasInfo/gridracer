"""Turn raw emulator screenshots into Play listing assets.

Three steps, in order:

1. Crop Android's system bars away. Their heights are not guessed: read them off the device
   with `adb shell dumpsys window | grep ITYPE_STATUS_BAR` (and ITYPE_NAVIGATION_BAR) and pass
   them in, because they change with the display size and the navigation style. Cropping the
   status bar matters - it would otherwise publish whichever notification icons happened to be
   on the phone at capture time.
2. Pad the result to exactly 2:1 with the app's own background colour. Play wants the longest
   side no more than twice the shortest; a 20:9 phone capture is 2.17:1 and just misses, so the
   letterboxing is what makes the shot legal, where a crop would cut the UI instead.
3. Save as 24-bit RGB. Play specifies PNG without an alpha channel.

Usage:
    python3 art/prepare_screenshots.py OUT_DIR IN.png [IN.png ...] [--top 66] [--bottom 66]
"""

import argparse
from pathlib import Path

from PIL import Image

TARGET_RATIO = 2.0       # Play's maximum long:short side


def prepare(src: Path, out_dir: Path, top: int, bottom: int) -> Path:
    image = Image.open(src).convert("RGB")
    cropped = image.crop((0, top, image.width, image.height - bottom))

    target_height = max(cropped.height, round(cropped.width / TARGET_RATIO))
    if target_height > cropped.height:
        # Pad with the app's own background - the colour of the cropped image's top-left pixel,
        # which is page background on every screen this is used for.
        canvas = Image.new("RGB", (cropped.width, target_height), cropped.getpixel((0, 0)))
        canvas.paste(cropped, (0, (target_height - cropped.height) // 2))
        cropped = canvas

    out_dir.mkdir(parents=True, exist_ok=True)
    destination = out_dir / src.name
    cropped.save(destination)
    print(f"{src.name}: {image.width}x{image.height} -> {cropped.width}x{cropped.height}")
    return destination


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out_dir", type=Path)
    parser.add_argument("sources", nargs="+", type=Path)
    parser.add_argument("--top", type=int, default=66, help="status bar height in px")
    parser.add_argument("--bottom", type=int, default=66, help="navigation bar height in px")
    args = parser.parse_args()
    for path in args.sources:
        prepare(path, args.out_dir, args.top, args.bottom)


if __name__ == "__main__":
    main()
