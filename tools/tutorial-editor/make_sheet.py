#!/usr/bin/env python3
"""Renders every frame of every project into one contact-sheet HTML file.

The How-to-play animations are stepped by hand, so the only way to judge one is to look at its
frames side by side. `out/contact_sheet.html` does that in the browser without an emulator, and
is the thing to compare against emulator captures of the real screen: same frames, same order,
so a divergence is obvious.

The projects are embedded in the page rather than fetched, so the file works from `file://` and
a headless screenshot catches it fully drawn.

Usage:  python3 tools/tutorial-editor/make_sheet.py [project ...]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).parent
PROJECTS = HERE / "projects"
OUT = HERE / "out" / "contact_sheet.html"

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>How to play - frame contact sheet</title>
  <style>
    body {{ margin: 0; background: #16181d; color: #e6e8ec; font: 12px system-ui, sans-serif; }}
    h1 {{ font-size: 15px; margin: 12px 10px 2px; }}
    h2 {{ margin: 14px 10px 4px; font-size: 13px; color: #00c853; }}
    .sheet {{ display: flex; flex-wrap: wrap; gap: 6px; padding: 0 10px 10px; }}
    figure {{ margin: 0; width: {width}px; }}
    canvas {{ width: {width}px; border-radius: 6px; display: block; }}
    figcaption {{ font-size: 10px; color: #9aa1ae; padding: 3px 2px; min-height: 40px; }}
    .phase {{ color: #00c853; font-family: ui-monospace, monospace; }}
  </style>
</head>
<body>
<h1>GridRacer - How to play, every frame</h1>
<script type="module">
  import * as R from '{render_url}';
  const PROJECTS = {projects};
  for (const project of PROJECTS) {{
    const heading = document.createElement('h2');
    heading.textContent = `${{project.id}} - ${{project.name}} (${{project.frames.length}} frames)`;
    document.body.append(heading);
    const sheet = document.createElement('div');
    sheet.className = 'sheet';
    for (const frame of project.frames) {{
      const figure = document.createElement('figure');
      const canvas = document.createElement('canvas');
      canvas.width = 1148;
      canvas.height = 940;
      R.drawScene(canvas.getContext('2d'), canvas.width, canvas.height, project, frame.phase);
      const caption = document.createElement('figcaption');
      caption.innerHTML = `<span class="phase">${{frame.phase.toFixed(2)}}</span> ${{frame.caption}}`;
      figure.append(canvas, caption);
      sheet.append(figure);
    }}
    document.body.append(sheet);
  }}
</script>
</body>
</html>
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("projects", nargs="*", help="project ids; default is all of them")
    parser.add_argument("--width", type=int, default=280, help="thumbnail width in CSS pixels")
    args = parser.parse_args()

    ids = args.projects or sorted(path.stem for path in PROJECTS.glob("*.json"))
    projects = [json.loads((PROJECTS / f"{name}.json").read_text(encoding="utf-8")) for name in ids]
    if not projects:
        print("no projects", file=sys.stderr)
        return 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        TEMPLATE.format(
            width=args.width,
            render_url="../web/render.js",
            projects=json.dumps(projects),
        ),
        encoding="utf-8",
    )
    print(f"wrote {OUT}  ({len(projects)} project(s), {sum(len(p['frames']) for p in projects)} frames)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
