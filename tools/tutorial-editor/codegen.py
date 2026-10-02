#!/usr/bin/env python3
"""Turns a tutorial scene project into a Compose animation file.

The generated file is ordinary Kotlin in `info.piepgras.gridracer.ui.howtoplay`: it calls the
same drawing primitives (`drawAnimGrid`, `drawOfferedPickers`, `drawDemoCar`, ...) the
hand-written walkthroughs call, so nothing about the scene format leaks into the app. There is
no runtime, no parser and no asset - the app never learns that an editor exists.

Each project emits three declarations, named after the project's `symbol`:

    internal val <Symbol>Frames          the phases HowToPlayScreen steps through
    internal fun <Symbol>Animation(phase: Float)   the @Composable that draws the scene
    @Composable internal fun <symbol>Caption(phase: Float): String

which is exactly the shape `HowToPlayScreen.rememberHowToPlaySteps` already consumes, so a
generated file can replace a hand-written one without touching the screen.

The caption function is `@Composable` because it reads the frame captions out of string
resources rather than carrying them as Kotlin literals - the walkthroughs are translated like
the rest of the app. One resource per frame, named `how_to_play_<project id>_caption_<n>`;
the English text is written alongside the Kotlin as `<project id>_captions.xml`, and
`--install` merges any name the app does not have yet into `res/values/strings.xml` (an
existing name is left alone, so English copy edits made in the app are never clobbered).

Usage:
    python3 tools/tutorial-editor/codegen.py                      # every project -> out/
    python3 tools/tutorial-editor/codegen.py oil_spill nails      # just these
    python3 tools/tutorial-editor/codegen.py --install            # straight into the app
    python3 tools/tutorial-editor/codegen.py --check              # diff against the app's files
"""

from __future__ import annotations

import argparse
import difflib
import json
import pathlib
import sys
import textwrap

sys.path.insert(0, str(pathlib.Path(__file__).parent))

import scene  # noqa: E402

HERE = pathlib.Path(__file__).parent
PROJECTS = HERE / "projects"
OUT = HERE / "out"
APP_PACKAGE = (
    HERE.parent.parent / "app/src/main/java/info/piepgras/gridracer/ui/howtoplay"
)
APP_STRINGS = HERE.parent.parent / "app/src/main/res/values/strings.xml"

KOTLIN_CELL_MODES = {"fit": "AnimCellMode.FIT", "minDimension": "AnimCellMode.MIN_DIMENSION"}
KOTLIN_ORIGIN_MODES = {"points": "AnimOriginMode.POINTS", "cells": "AnimOriginMode.CELLS"}


class GenerationError(ValueError):
    """A project the generator cannot turn into valid Kotlin."""


# ---------------------------------------------------------------- formatting helpers


def f(value) -> str:
    """A Kotlin float literal: 2 -> `2f`, 0.17 -> `0.17f`, -0.30000001 -> `-0.3f`."""
    text = f"{float(value):.6f}".rstrip("0").rstrip(".")
    if text in ("", "-"):
        text = "0"
    return f"{text}f"


def i(value) -> str:
    """A Kotlin Int literal from a scene number that must land on the grid."""
    rounded = round(float(value))
    if abs(float(value) - rounded) > 1e-6:
        raise GenerationError(f"{value} must be a whole grid coordinate")
    return str(rounded)


def point(value) -> str:
    """`Point(x, y)` - engine grid coordinates, so both components must be whole."""
    return f"Point({i(value[0])}, {i(value[1])})"


def offset(value) -> str:
    """`grid.offset(x, y)` - a canvas position, which may sit between grid points."""
    return f"grid.offset({f(value[0])}, {f(value[1])})"


def ident(text: str) -> str:
    """`oil_spill` -> `oilSpill`; the Kotlin name for a scene id."""
    head, *rest = [part for part in text.replace("-", "_").split("_") if part]
    if not head:
        raise GenerationError(f"{text!r} has no usable name")
    name = head[0].lower() + head[1:] + "".join(part[:1].upper() + part[1:] for part in rest)
    if not name[0].isalpha():
        name = "e" + name
    return name


def kotlin_string(text: str) -> str:
    escaped = (
        text.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
    )
    return f'"{escaped}"'


def color_expr(name: str, imports: set[str]) -> str:
    """A named palette colour, or a literal `Color(0xAARRGGBB)` for a hex value."""
    if name in scene.NAMED_COLORS:
        return scene.NAMED_COLORS[name][0]
    raw = name.lstrip("#")
    if len(raw) == 6:
        raw = "FF" + raw
    if len(raw) != 8 or any(c not in "0123456789abcdefABCDEF" for c in raw):
        raise GenerationError(f"{name!r} is not a palette name or an #RRGGBB / #AARRGGBB colour")
    imports.add("androidx.compose.ui.graphics.Color")
    return f"Color(0x{raw.upper()})"


def alpha_expr(element: dict, groups: dict) -> str | None:
    """The Kotlin expression for an element's alpha, or None when it is always fully opaque."""
    parts = []
    envelope = element.get("alpha") or {}
    fade_in, fade_out = envelope.get("in"), envelope.get("out")
    if fade_in and fade_out:
        parts.append(
            f"minOf(ramp(phase, {f(fade_in[0])}, {f(fade_in[1])}), "
            f"rampDown(phase, {f(fade_out[0])}, {f(fade_out[1])}))"
        )
    elif fade_in:
        parts.append(f"ramp(phase, {f(fade_in[0])}, {f(fade_in[1])})")
    elif fade_out:
        parts.append(f"rampDown(phase, {f(fade_out[0])}, {f(fade_out[1])})")
    group = element.get("group")
    if group:
        parts.append(f"{ident(group)}Alpha")
    return " * ".join(parts) if parts else None


def point_list(points, per_line: int = 3) -> str:
    """`listOf(Point(4, 0), Point(4, 1), ...)`, wrapped once it gets long."""
    items = [point(p) for p in points]
    if len(items) <= per_line:
        return "listOf(" + ", ".join(items) + ")"
    lines = ["listOf("]
    for start in range(0, len(items), per_line):
        lines.append("    " + ", ".join(items[start : start + per_line]) + ",")
    lines.append(")")
    return "\n".join(lines)


# ---------------------------------------------------------------- element emission


def _call(name: str, args: list[str], alpha: str | None, alpha_name: str = "alpha") -> list[str]:
    """One drawing call, laid out on one line when it fits and indented when it doesn't."""
    if alpha is not None:
        args = args + [f"{alpha_name} = {alpha}"]
    one_line = f"{name}({', '.join(args)})"
    if len(one_line) <= 96 and "\n" not in one_line:
        return [one_line]
    lines = [f"{name}("]
    for arg in args:
        for index, part in enumerate(arg.split("\n")):
            lines.append("    " + part + ("," if index == len(arg.split("\n")) - 1 else ""))
    lines.append(")")
    return lines


def emit_element(element: dict, project: dict, imports: set[str]) -> list[str]:
    """The Kotlin lines that draw one element, without the enclosing indentation."""
    groups = project.get("groups") or {}
    kind = element["type"]
    alpha = alpha_expr(element, groups)

    if kind == "wall":
        args = [
            "grid",
            f(element["from"][0]),
            f(element["from"][1]),
            f(element["to"][0]),
            f(element["to"][1]),
        ]
        if abs(float(element.get("width", 0.15)) - 0.15) > 1e-9:
            args.append(f"widthFactor = {f(element['width'])}")
        return _call("drawAnimWall", args, alpha)

    if kind == "finishLine":
        args = ["grid", f(element["x"]), f(element["y0"]), f(element["y1"])]
        if abs(float(element.get("width", 0.12)) - 0.12) > 1e-9:
            args.append(f"widthFactor = {f(element['width'])}")
        lines = _call("drawAnimFinishLine", args, alpha)
        arrow = element.get("arrow")
        if arrow:
            arrow_args = [
                f"grid.offset({f(element['x'])}, {f(arrow.get('y', 0))})",
                "cell",
            ]
            if int(arrow.get("direction", 1)) != 1:
                arrow_args.append(f"direction = {int(arrow['direction'])}")
            lines += _call("drawAnimFinishArrow", arrow_args, alpha)
        return lines

    if kind == "gravel":
        args = [
            "grid",
            f(element["from"][0]),
            f(element["from"][1]),
            f(element["to"][0]),
            f(element["to"][1]),
        ]
        if abs(float(element.get("depth", 2.0)) - 2.0) > 1e-9:
            args.append(f"depth = {f(element['depth'])}")
        return _call("drawAnimGravel", args, alpha)

    if kind == "hazard":
        corners = ",\n".join("    " + offset(c) for c in element["corners"])
        args = ["listOf(\n" + corners + ",\n)", color_expr(element.get("color", "oil"), imports)]
        return _call("drawHazardZone", args, alpha)

    if kind == "trail":
        if element.get("kind", "static") == "static":
            args = [offset(element["from"]), offset(element["to"]), "cell"]
            return _call("drawTrailSegment", args, alpha)
        car = ident(element["car"])
        args = [f"{car}Moves[{int(element.get('move', 0))}]", f"{car}.position", "phase", "cell"]
        return _call("drawFollowTrail", args, alpha)

    if kind == "projector":
        args = [offset(element["from"]), offset(element["to"]), "cell", alpha or "1f"]
        if element.get("color", "legal") == "crash":
            args.append("color = AnimCrashColor")
        return _call(args=args, name="drawProjector", alpha=None)

    if kind == "pickers":
        points = scene.resolve_pickers(element)
        if not points:
            return []
        name = "drawOfferedPickers" if element.get("style", "offered") == "offered" else "drawWithheldPickers"
        return _call(name, [point_list(points), "grid", alpha or "1f"], None)

    if kind == "pickRing":
        return _call("drawPickRing", [point(element["at"]), "grid", alpha or "1f"], None)

    if kind == "car":
        name = ident(element["id"])
        return _call("drawDemoCar", [f"{name}.position", "cell", f"{name}.heading", alpha or "1f"], None)

    if kind == "wreck":
        imports.add("info.piepgras.gridracer.ui.drawWreck")
        show_from = float(element.get("showFrom", 0.0))
        call = f"drawWreck({offset(element['at'])}, cell, AnimCarColor)"
        return [f"if (phase >= {f(show_from)}) {call}"]

    if kind == "statusFrame":
        lap = kotlin_string(element.get("lap", ""))
        switch = element.get("switch")
        if switch and switch.get("kind") == "carPastX":
            condition = f"{ident(switch['car'])}.position.x >= grid.offset({f(switch['x'])}, 0f).x"
            lap = f"if ({condition}) {kotlin_string(element.get('lapAfter', ''))} else {lap}"
        elif switch and switch.get("kind") == "phase":
            lap = f"if (phase >= {f(switch['at'])}) {kotlin_string(element.get('lapAfter', ''))} else {lap}"
        crashed = "false"
        if element.get("crashedFrom") is not None:
            crashed = f"phase >= {f(element['crashedFrom'])}"
        return _call("drawStatusFrame", ["grid", f"lapText = {lap}", f"crashed = {crashed}"], alpha)

    if kind == "label":
        args = [
            "grid",
            f(element["at"][0]),
            f(element["at"][1]),
            kotlin_string(element.get("text", "")),
        ]
        if abs(float(element.get("size", 0.3)) - 0.3) > 1e-9:
            args.append(f"sizeFactor = {f(element['size'])}")
        if element.get("color", "wall") != "wall":
            args.append(f"color = {color_expr(element['color'], imports)}")
        if not element.get("centered", True):
            args.append("centered = false")
        return _call("drawAnimLabel", args, alpha)

    raise GenerationError(f"no generator for element type {kind!r}")


# ---------------------------------------------------------------- file assembly


def grid_call(grid: dict) -> list[str]:
    args = [f"cols = {int(grid['cols'])}", f"rows = {int(grid['rows'])}"]
    if grid.get("cellMode", "fit") != "fit":
        args.append(f"cellMode = {KOTLIN_CELL_MODES[grid['cellMode']]}")
    if abs(float(grid.get("cellScale", 0.85)) - 0.85) > 1e-9:
        args.append(f"cellScale = {f(grid['cellScale'])}")
    if grid.get("cellMode") == "minDimension" and abs(float(grid.get("cellDivisor", 7.0)) - 7.0) > 1e-9:
        args.append(f"cellDivisor = {f(grid['cellDivisor'])}")
    if grid.get("originMode", "points") != "points":
        args.append(f"originMode = {KOTLIN_ORIGIN_MODES[grid['originMode']]}")
    one_line = f"val grid = drawAnimGrid({', '.join(args)})"
    if len(one_line) <= 96:
        return [one_line]
    return ["val grid = drawAnimGrid("] + [f"    {arg}," for arg in args] + [")"]


def doc_block(text: str, generated_from: str) -> list[str]:
    """A KDoc block, the prose wrapped to the width the rest of the codebase uses."""
    lines = ["/**"]
    for paragraph in (text or "").split("\n"):
        wrapped = textwrap.wrap(paragraph, width=96) or [""]
        for line in wrapped:
            lines.append(f" * {line}".rstrip())
    if text:
        lines.append(" *")
    lines.append(f" * Generated from {generated_from} by tools/tutorial-editor/codegen.py.")
    lines.append(" * Edit the scene in the tutorial editor and regenerate; do not edit this file by hand.")
    lines.append(" */")
    return lines


def caption_symbol(project: dict) -> str:
    explicit = project.get("captionSymbol")
    if explicit:
        return explicit
    symbol = project["symbol"]
    return symbol[0].lower() + symbol[1:] + "Caption"


def caption_resources(project: dict) -> list[tuple[str, str]]:
    """`(resource name, English text)` for each frame, in frame order.

    One resource per frame rather than one per distinct string: two frames that happen to
    share wording today are still two captions, and a translator may need to word them
    differently anyway.
    """
    return [
        (f"how_to_play_{project['id']}_caption_{index + 1}", frame.get("caption", ""))
        for index, frame in enumerate(project["frames"])
    ]


def android_string_value(text: str) -> str:
    """A string resource body: XML-escaped, plus the escapes aapt needs."""
    escaped = (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
    )
    # aapt trims leading/trailing whitespace unless it is written as an escape.
    if escaped[:1] == " ":
        escaped = "\\u0020" + escaped[1:]
    if escaped[-1:] == " ":
        escaped = escaped[:-1] + "\\u0020"
    return escaped


def caption_strings_xml(project: dict) -> str:
    """The `res/values` fragment holding this project's captions."""
    # An XML comment may not contain a double hyphen, so the install flag is named in prose.
    lines = [
        "<resources>",
        f"    <!-- Captions for the {project['id']} walkthrough, generated by",
        "         tools/tutorial-editor/codegen.py. Merge into app/src/main/res/values/strings.xml",
        "         (the install flag does it for you), then translate them in values-de and",
        "         values-ru. -->",
    ]
    for name, text in caption_resources(project):
        lines.append(f'    <string name="{name}">{android_string_value(text)}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def merge_caption_strings(project: dict, path: pathlib.Path) -> list[str]:
    """Adds this project's caption resources to [path] if they are not there already.

    Returns the names that were added. A name the file already has is left exactly as it is:
    the app's English copy is allowed to have been edited past what the scene says, and
    silently overwriting it would also silently invalidate both translations.
    """
    source = path.read_text(encoding="utf-8")
    added = []
    block = []
    for name, text in caption_resources(project):
        if f'name="{name}"' in source:
            continue
        added.append(name)
        block.append(f'    <string name="{name}">{android_string_value(text)}</string>')
    if not added:
        return []
    insert = (
        f"\n    <!-- Captions for the {project['id']} walkthrough, from\n"
        f"         tools/tutorial-editor/projects/{project['id']}.json. -->\n"
        + "\n".join(block)
        + "\n"
    )
    head, sep, tail = source.rpartition("</resources>")
    if not sep:
        raise GenerationError(f"{path} has no </resources> to insert before")
    path.write_text(head + insert + sep + tail, encoding="utf-8")
    return added


def generate(project: dict) -> str:
    """The full Kotlin source for one project."""
    scene.validate(project)
    symbol = project["symbol"]
    frames = project["frames"]
    imports = {
        "androidx.compose.foundation.Canvas",
        "androidx.compose.foundation.layout.fillMaxSize",
        "androidx.compose.runtime.Composable",
        "androidx.compose.ui.Modifier",
    }

    cars = [e for e in project["elements"] if e["type"] == "car"]
    names = {}
    for element in project["elements"]:
        name = ident(element["id"])
        if name in names:
            raise GenerationError(
                f"elements {names[name]!r} and {element['id']!r} both become the Kotlin name {name!r}"
            )
        names[name] = element["id"]
    if cars:
        imports.add("info.piepgras.gridracer.engine.Point")

    body: list[str] = []
    body += grid_call(project["grid"])
    body.append("val cell = grid.cellSize")

    groups = project.get("groups") or {}
    if groups:
        body.append("")
        body.append("// Group fades: every element in a group is multiplied by its group's alpha.")
        for group_id, group in groups.items():
            expression = alpha_expr(group, {}) or "1f"
            body.append(f"val {ident(group_id)}Alpha = {expression}")

    for car in cars:
        legs = scene.move_legs(car)
        name = ident(car["id"])
        body.append("")
        if legs:
            body.append(f"val {name}Moves = listOf(")
            for leg in legs:
                args = [
                    offset(leg["from"]),
                    offset(leg["to"]),
                    f(leg["start"]),
                    f(leg["end"]),
                    point(leg.get("heading", [1, 0])),
                ]
                if float(leg.get("shimmy", 0)) > 0:
                    args.append(f"shimmy = {f(leg['shimmy'])}")
                    if abs(float(leg.get("shimmyCycles", 3.0)) - 3.0) > 1e-9:
                        args.append(f"shimmyCycles = {f(leg['shimmyCycles'])}")
                body.append(f"    AnimMove({', '.join(args)}),")
            body.append(")")
        else:
            body.append(f"val {name}Moves = emptyList<AnimMove>()")
        body.append(
            f"val {name} = animCar({offset(car['rest'])}, {point(car.get('restHeading', [1, 0]))}, "
            f"{name}Moves, phase, cell)"
        )

    body.append("")
    for element in project["elements"]:
        lines = emit_element(element, project, imports)
        if not lines:
            continue
        body.append(f"// {element['id']}")
        body += lines

    # Captions come from string resources, so the walkthrough is translated with the rest of
    # the app; each branch runs until the *next* frame's phase begins.
    resources = [name for name, _ in caption_resources(project)]
    caption_lines = [
        f"    phase < {f(frames[index + 1]['phase'])} -> stringResource(R.string.{resources[index]})"
        for index in range(len(frames) - 1)
    ]
    caption_lines.append(f"    else -> stringResource(R.string.{resources[-1]})")
    imports.add("androidx.compose.ui.res.stringResource")
    imports.add("info.piepgras.gridracer.R")

    out: list[str] = ["package info.piepgras.gridracer.ui.howtoplay", ""]
    out += [f"import {name}" for name in sorted(imports)]
    out.append("")
    out += doc_block(project.get("notes", ""), f"tools/tutorial-editor/projects/{project['id']}.json")
    out.append("")
    out.append(f"/** The phases HowToPlayScreen steps [{symbol}Animation] through, one per frame. */")
    out.append(
        f"internal val {symbol}Frames = listOf(" + ", ".join(f(frame["phase"]) for frame in frames) + ")"
    )
    out.append("")
    out.append("@Composable")
    out.append(f"internal fun {symbol}Animation(phase: Float) {{")
    out.append("    Canvas(modifier = Modifier.fillMaxSize()) {")
    for line in body:
        out.append(f"        {line}" if line else "")
    out.append("    }")
    out.append("}")
    out.append("")
    out.append("@Composable")
    out.append(f"internal fun {caption_symbol(project)}(phase: Float): String = when {{")
    out += caption_lines
    out.append("}")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- CLI


def load(project_id: str) -> dict:
    path = PROJECTS / f"{project_id}.json"
    if not path.exists():
        raise GenerationError(f"no project {project_id!r} in {PROJECTS}")
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("projects", nargs="*", help="project ids; default is all of them")
    parser.add_argument(
        "--install",
        action="store_true",
        help="write into the app's ui/howtoplay package, replacing the file that is there",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="diff the generated source against the app's file; exit non-zero if they differ",
    )
    args = parser.parse_args()

    ids = args.projects or sorted(path.stem for path in PROJECTS.glob("*.json"))
    target_dir = APP_PACKAGE if (args.install or args.check) else OUT
    target_dir.mkdir(parents=True, exist_ok=True)

    differences = 0
    for project_id in ids:
        project = load(project_id)
        source = generate(project)
        path = target_dir / f"{project['symbol']}Animation.kt"
        if args.check:
            current = path.read_text(encoding="utf-8") if path.exists() else ""
            missing = [
                name for name, _ in caption_resources(project)
                if f'name="{name}"' not in APP_STRINGS.read_text(encoding="utf-8")
            ]
            if missing:
                differences += 1
                print(f"{project_id}: captions not in strings.xml: {', '.join(missing)}")
            if current == source:
                print(f"{path.name}: identical")
                continue
            differences += 1
            print(f"{path.name}: differs")
            for line in difflib.unified_diff(
                current.splitlines(), source.splitlines(), "app", "generated", lineterm="", n=2
            ):
                print("  " + line)
        else:
            path.write_text(source, encoding="utf-8")
            print(f"wrote {path}")
            if args.install:
                added = merge_caption_strings(project, APP_STRINGS)
                if added:
                    print(f"  added {len(added)} caption string(s) to {APP_STRINGS}")
                    print(f"  translate them in values-de/ and values-ru/: {', '.join(added)}")
                else:
                    print("  captions already in strings.xml, left untouched")
            else:
                captions = OUT / f"{project_id}_captions.xml"
                captions.write_text(caption_strings_xml(project), encoding="utf-8")
                print(f"wrote {captions}")
    return 1 if differences else 0


if __name__ == "__main__":
    raise SystemExit(main())
