#!/usr/bin/env python3
"""Imports the app's five hand-written walkthroughs into editable scene projects.

Each scene below is a transcription of one file under
`app/src/main/java/info/piepgras/gridracer/ui/howtoplay/`: the same grid metrics, the same
positions, the same phase intervals. Running this script rewrites `projects/*.json`, and
`codegen.py` turns those back into Kotlin that draws what the hand-written file draws — so the
round trip is checkable, and the editor starts from the real walkthroughs rather than from
blank pages.

Where a hand-written animation spelled an alpha out as a `when` chain, the transcription uses
the scene format's fade envelope, which composes to the same curve:

    alpha = min(ramp(phase, in[0], in[1]), rampDown(phase, out[0], out[1]))

Usage:  python3 tools/tutorial-editor/import_builtin.py [--check]

`--check` reports whether the files on disk already match, and changes nothing.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

PROJECTS = pathlib.Path(__file__).parent / "projects"


def env(fade_in=None, fade_out=None):
    """A fade envelope, omitted entirely when the element is simply always visible."""
    alpha = {}
    if fade_in:
        alpha["in"] = list(fade_in)
    if fade_out:
        alpha["out"] = list(fade_out)
    return {"alpha": alpha} if alpha else {}


def el(element_id, element_type, **fields):
    """One scene element. Elements draw in list order, so the list is also the z-order."""
    return {"id": element_id, "type": element_type, **fields}


# --------------------------------------------------------------- rule 1: velocity


def velocity_rule() -> dict:
    """VelocityRuleAnimation.kt — choosing a move from the coasted position."""
    return {
        "id": "velocity_rule",
        "symbol": "VelocityRule",
        # The app's caption function predates the "<symbol>Caption" convention; keeping the
        # name means a regenerated file is a drop-in replacement for the hand-written one.
        "captionSymbol": "velocityCaption",
        "name": "Choosing your move",
        "title": "Choosing your move",
        "titleRes": "how_to_play_step1_title",
        "bodyRes": "how_to_play_step1_body",
        "body": (
            "Each turn, you project your current speed forward. From this coasted position, you "
            "must choose any of the 8 neighboring positions or the coast itself as your next turn."
        ),
        "notes": (
            "Step 1: how a move is chosen from the coasted position.\n\n"
            "Seven frames: at rest, the nine points offered, the pick, the drive, the new coast "
            "with its nine points, the second pick, the second drive. This is the only rule that "
            "starts from rest, so it is also the one that shows that the coast of a stationary "
            "car is the car's own position."
        ),
        "grid": {
            "cols": 5,
            "rows": 3,
            "cellMode": "minDimension",
            "cellScale": 0.85,
            "cellDivisor": 7.0,
            "originMode": "cells",
        },
        "groups": {},
        "frames": [
            {"phase": 0.0, "caption": "Your car starts at rest."},
            {"phase": 0.14, "caption": "You haven't moved yet, so the coast is your current position."},
            {"phase": 0.24, "caption": "Choose any of the 9 marked positions."},
            {"phase": 0.38, "caption": "You drive to the selected position."},
            {
                "phase": 0.52,
                "caption": "Your new coast is determined by your current speed — one grid point ahead.",
            },
            {"phase": 0.62, "caption": "Choose any of the 9 marked positions."},
            {"phase": 0.80, "caption": "You drive to the selected position."},
        ],
        "elements": [
            el("trail_1", "trail", kind="follow", car="car", move=0),
            el("trail_2", "trail", kind="follow", car="car", move=1),
            el("projector", "projector", **{"from": [2, 1]}, to=[3, 1], color="legal",
               **env((0.38, 0.52), (0.62, 0.80))),
            el("pickers_1", "pickers", style="offered", around=[1, 1], **env((0.0, 0.14), (0.24, 0.38))),
            el("ring_1", "pickRing", at=[2, 1], **env((0.14, 0.24), (0.24, 0.38))),
            el("pickers_2", "pickers", style="offered", around=[3, 1], **env((0.38, 0.52), (0.62, 0.80))),
            el("ring_2", "pickRing", at=[4, 0], **env((0.52, 0.62), (0.62, 0.80))),
            el(
                "car",
                "car",
                rest=[1, 1],
                restHeading=[1, 0],
                moves=[
                    {"to": [2, 1], "start": 0.24, "end": 0.38, "heading": [1, 0]},
                    {"to": [4, 0], "start": 0.62, "end": 0.80, "heading": [2, -1]},
                ],
            ),
        ],
    }


# --------------------------------------------------------------- rule 2: walls


def wall_crash() -> dict:
    """WallCrashAnimation.kt — walls block, and a coast with nowhere to go is a crash."""
    wall_x = 4.75
    # The (4,2) -> (6,3) projection meets the wall at x=4.75: t=0.375, y=2.375.
    wreck_at = [4.75, 2.375]
    behind_wall = [[5, 0], [5, 1], [5, 2], [6, 0], [6, 1], [6, 2]]
    return {
        "id": "wall_crash",
        "symbol": "WallCrash",
        "name": "Walls and crashing",
        "title": "Walls and crashing",
        "titleRes": "how_to_play_step2_title",
        "bodyRes": "how_to_play_step2_body",
        "body": (
            "Walls block any move whose path crosses them. If your coast projects past a wall and "
            "every move would cross it, you crash and are out of the race. Plan your braking "
            "early: the faster you go, the longer it takes to slow down."
        ),
        "notes": (
            "Four-frame walkthrough of walls and crashing. You are already at speed (velocity "
            "(3,0)) approaching a wall at x=4.75, so three columns of grid points sit behind it.\n\n"
            "1. Your coast projects past the wall; only the car, its trail and the projector show.\n"
            "2. The selectors fade in: the points at x=4 are offered, the six behind the wall are "
            "withheld.\n"
            "3. You brake and steer to (4,2), but the new coast (6,3) still crosses the wall.\n"
            "4. Every candidate is behind the wall, so you crash where the projection meets it."
        ),
        "grid": {
            "cols": 8,
            "rows": 5,
            "cellMode": "fit",
            "cellScale": 0.85,
            "cellDivisor": 7.0,
            "originMode": "cells",
        },
        "groups": {},
        "frames": [
            {"phase": 0.0, "caption": "Your coast projects toward the wall."},
            {"phase": 0.25, "caption": "Green points are legal; dim points would cross the wall."},
            {
                "phase": 0.55,
                "caption": "You brake and steer to (4,2), but the new coast still crosses the wall.",
            },
            {"phase": 0.80, "caption": "You crash into the wall and are out of the race."},
        ],
        "elements": [
            el("wall", "wall", **{"from": [wall_x, -0.3]}, to=[wall_x, 5.3], width=0.15),
            el("trail_in", "trail", kind="static", **{"from": [0, 1]}, to=[2, 1]),
            el("trail_move", "trail", kind="follow", car="car", move=0),
            el("trail_crash", "trail", kind="static", **{"from": [4, 2]}, to=wreck_at,
               **env((0.65, 0.75))),
            el("projector_1", "projector", **{"from": [2, 1]}, to=[5, 1], color="legal",
               **env(None, (0.25, 0.40))),
            el("projector_2", "projector", **{"from": [4, 2]}, to=[6, 3], color="crash",
               **env((0.45, 0.55), (0.65, 0.75))),
            el("pickers_legal", "pickers", style="offered", around=[5, 1], exclude=behind_wall,
               **env((0.05, 0.20), (0.25, 0.40))),
            el("pickers_blocked", "pickers", style="withheld", points=behind_wall,
               **env((0.05, 0.20), (0.25, 0.40))),
            el("ring", "pickRing", at=[4, 2], **env((0.25, 0.35), (0.25, 0.40))),
            el("pickers_dead_end", "pickers", style="withheld", around=[6, 3],
               **env((0.45, 0.55), (0.65, 0.75))),
            el(
                "car",
                "car",
                rest=[2, 1],
                restHeading=[3, 0],
                moves=[{"to": [4, 2], "start": 0.35, "end": 0.45, "heading": [2, 1]}],
                **env(None, (0.65, 0.75)),
            ),
            el("wreck_car", "car", rest=wreck_at, restHeading=[2, 1], moves=[], **env((0.65, 0.75))),
            el("wreck", "wreck", at=wreck_at, showFrom=0.70),
        ],
    }


# --------------------------------------------------------------- rule 3: finish line


def finish_line() -> dict:
    """FinishLineAnimation.kt — crossing the line the right way, then the wrong way."""
    lane = 3
    line_x = 4.5
    return {
        "id": "finish_line",
        "symbol": "FinishLine",
        "name": "Finish line and laps",
        "title": "Finish line and laps",
        "titleRes": "how_to_play_step3_title",
        "bodyRes": "how_to_play_step3_body",
        "body": (
            "Cross the finish line in the direction of its arrow to complete a lap. Whoever "
            "completes all laps first wins. Crossing the line against the arrow crashes you instead."
        ),
        "notes": (
            "Six-frame walkthrough of the finish line: crossing it the right way, then the wrong "
            "way, on one scene. A copy of the in-race status frame shows the lap counter of a "
            "two-lap race.\n\n"
            "Right way: you approach at speed (2,0), all nine candidates are offered, you pick the "
            "point beyond the line and the lap counter ticks over.\n"
            "Wrong way: the same car creeps back toward the line. Its candidates beyond the line "
            "are offered like any other point — the engine does not withhold a backward crossing, "
            "it crashes the car that makes one (see GameEngine.applyMove)."
        ),
        "grid": {
            "cols": 9,
            "rows": 5,
            "cellMode": "fit",
            "cellScale": 0.85,
            "cellDivisor": 7.0,
            "originMode": "points",
        },
        "groups": {
            "right_way": {"alpha": {"out": [0.38, 0.46]}},
            "wrong_way": {"alpha": {"in": [0.46, 0.55]}},
        },
        "frames": [
            {
                "phase": 0.0,
                "caption": "The finish line is ahead. Its arrow shows the direction you must cross it in.",
            },
            {
                "phase": 0.18,
                "caption": "All nine points are offered as usual. The three beyond the line would cross it the right way.",
            },
            {
                "phase": 0.36,
                "caption": "You cross the line: one lap done. The lap counter in your status frame ticks up.",
            },
            {"phase": 0.55, "caption": "Later you come back toward the line, heading against its arrow."},
            {
                "phase": 0.70,
                "caption": "The points beyond the line are still offered, but crossing against the arrow is a crash.",
            },
            {
                "phase": 0.88,
                "caption": "You cross the wrong way, burn out on the line, and are out of the race.",
            },
        ],
        "elements": [
            el("line", "finishLine", x=line_x, y0=-0.35, y1=4.35, width=0.12,
               arrow={"y": 2.0, "direction": 1}),
            el("trail_right_in", "trail", kind="static", **{"from": [0, lane]}, to=[2, lane],
               group="right_way"),
            el("trail_right", "trail", kind="follow", car="car_right", move=0, group="right_way"),
            el("projector_right", "projector", **{"from": [2, lane]}, to=[4, lane], color="legal",
               group="right_way", **env(None, (0.18, 0.26))),
            el("pickers_right", "pickers", style="offered", around=[4, lane], group="right_way",
               **env((0.04, 0.14), (0.18, 0.26))),
            el("ring_right", "pickRing", at=[5, lane], group="right_way",
               **env((0.14, 0.18), (0.18, 0.26))),
            el(
                "car_right",
                "car",
                rest=[2, lane],
                restHeading=[1, 0],
                moves=[{"to": [5, lane], "start": 0.20, "end": 0.34, "heading": [1, 0]}],
                group="right_way",
            ),
            el("trail_wrong_in", "trail", kind="static", **{"from": [7, lane]}, to=[6, lane],
               group="wrong_way"),
            el("trail_wrong", "trail", kind="follow", car="car_wrong", move=0, group="wrong_way"),
            el("projector_wrong", "projector", **{"from": [6, lane]}, to=[5, lane], color="legal",
               group="wrong_way", **env(None, (0.70, 0.76))),
            el("pickers_wrong", "pickers", style="offered", around=[5, lane], group="wrong_way",
               **env((0.57, 0.65), (0.70, 0.76))),
            el("ring_wrong", "pickRing", at=[4, lane], group="wrong_way",
               **env((0.65, 0.70), (0.70, 0.76))),
            el(
                "car_wrong",
                "car",
                rest=[6, lane],
                restHeading=[-1, 0],
                moves=[{"to": [line_x, lane], "start": 0.72, "end": 0.82, "heading": [-1, 0]}],
                group="wrong_way",
            ),
            el("wreck", "wreck", at=[line_x, lane], showFrom=0.82),
            el(
                "status",
                "statusFrame",
                lap="1/2",
                lapAfter="2/2",
                switch={"kind": "carPastX", "car": "car_right", "x": line_x},
                crashedFrom=0.82,
            ),
        ],
    }


# --------------------------------------------------------------- rule 4: oil


def oil_spill() -> dict:
    """OilSpillAnimation.kt — an oil spill costs you your steering on the next move."""
    offered = [[6, 1], [5, 2], [7, 2]]
    return {
        "id": "oil_spill",
        "symbol": "OilSpill",
        "name": "Oil spills",
        "title": "Oil spills",
        "titleRes": "how_to_play_step4_title",
        "bodyRes": "how_to_play_step4_body",
        "body": (
            "Driving over an oil spill takes away your steering for the next turn. The coast is "
            "always offered, plus two randomly chosen nearby points — you get a choice, but not "
            "the usual one. Slow down before a spill, or steer around it."
        ),
        "notes": (
            "Six-frame walkthrough of an oil spill, following GameEngine.oilSlipTargets: a move "
            "whose path touches the spill costs the player their steering on the *next* move, when "
            "only the coast point and two randomly drawn neighbours of it are offered.\n\n"
            "The restricted set is centred on the coast point (position + lastVelocity), not on "
            "the car — centring it on the car would show moves the engine never offers."
        ),
        "grid": {
            "cols": 9,
            "rows": 5,
            "cellMode": "fit",
            "cellScale": 0.85,
            "cellDivisor": 7.0,
            "originMode": "points",
        },
        "groups": {},
        "frames": [
            {
                "phase": 0.0,
                "caption": "The dark patch ahead is an oil spill, and your coast point lies right on it.",
            },
            {
                "phase": 0.15,
                "caption": "Oil does not block you: all nine points are offered as usual. You keep your speed and take the coast.",
            },
            {
                "phase": 0.30,
                "caption": "You are on the oil. Driving through it costs you your steering on the next move.",
            },
            {
                "phase": 0.45,
                "caption": "Only your coast point and two random points beside it are offered. The other six are withheld.",
            },
            {
                "phase": 0.60,
                "caption": "You take one of the three and skid there. A spill is a loss of control, not a crash.",
            },
            {"phase": 0.75, "caption": "That move touched no oil, so all nine points are offered again."},
        ],
        "elements": [
            el(
                "spill",
                "hazard",
                color="oil",
                corners=[
                    [3.1, 0.9], [3.5, 0.25], [4.4, 0.15], [4.95, 0.7],
                    [4.85, 1.55], [4.2, 1.9], [3.4, 1.75], [3.05, 1.4],
                ],
            ),
            el("trail_in", "trail", kind="static", **{"from": [0, 1]}, to=[2, 1]),
            el("trail_onto_oil", "trail", kind="follow", car="car", move=0),
            el("trail_skid", "trail", kind="follow", car="car", move=1),
            el("projector_1", "projector", **{"from": [2, 1]}, to=[4, 1], color="legal",
               **env(None, (0.16, 0.22))),
            el("pickers_1", "pickers", style="offered", around=[4, 1], **env((0.03, 0.11), (0.16, 0.22))),
            el("ring_1", "pickRing", at=[4, 1], **env((0.11, 0.15), (0.16, 0.22))),
            el("projector_2", "projector", **{"from": [4, 1]}, to=[6, 1], color="legal",
               **env((0.26, 0.30), (0.50, 0.56))),
            el("pickers_slips", "pickers", style="offered", points=offered,
               **env((0.32, 0.40), (0.50, 0.56))),
            el("pickers_withheld", "pickers", style="withheld", around=[6, 1], exclude=offered,
               **env((0.32, 0.40), (0.50, 0.56))),
            el("ring_2", "pickRing", at=[5, 2], **env((0.46, 0.50), (0.50, 0.56))),
            el("projector_3", "projector", **{"from": [5, 2]}, to=[6, 3], color="legal",
               **env((0.62, 0.68))),
            el("pickers_free", "pickers", style="offered", around=[6, 3], **env((0.66, 0.75))),
            el(
                "car",
                "car",
                rest=[2, 1],
                restHeading=[1, 0],
                moves=[
                    {"to": [4, 1], "start": 0.17, "end": 0.28, "heading": [1, 0]},
                    {
                        "to": [5, 2],
                        "start": 0.50,
                        "end": 0.60,
                        "heading": [1, 1],
                        "shimmy": 0.10,
                        "shimmyCycles": 3.0,
                    },
                ],
            ),
        ],
    }


# --------------------------------------------------------------- rule 5: nails


def nails() -> dict:
    """NailsAnimation.kt — nails force a slow-down on the next move."""
    brake_set = [[5, 1], [5, 2], [5, 3]]
    crawl_set = [[5, 2], [6, 2], [5, 1], [5, 3]]
    return {
        "id": "nails",
        "symbol": "Nails",
        "name": "Nails",
        "title": "Nails",
        "titleRes": "how_to_play_step5_title",
        "bodyRes": "how_to_play_step5_body",
        "body": (
            "Nails force a slowdown. At speed, only the three hardest-braking moves remain. At "
            "speed one or less, you can stop, coast forward, or strafe sideways — but you cannot "
            "accelerate. Pick your line carefully before hitting nails."
        ),
        "notes": (
            "Eight-frame walkthrough of nails, following GameEngine.nailAllowedSet: a move whose "
            "path touches the nails limits the *next* move to a slow-down. At speed above one that "
            "is the three candidates nearest the car (the smallest resulting speed); at speed one "
            "it is stopping, rolling straight on, or moving to either side.\n\n"
            "Both restricted sets are centred on the coast point, not on the car."
        ),
        "grid": {
            "cols": 9,
            "rows": 5,
            "cellMode": "fit",
            "cellScale": 0.85,
            "cellDivisor": 7.0,
            "originMode": "points",
        },
        "groups": {},
        "frames": [
            {
                "phase": 0.0,
                "caption": "The orange patch ahead is a strip of nails, and your coast point lies on it.",
            },
            {
                "phase": 0.12,
                "caption": "Nails do not block you: all nine points are offered as usual. You keep your speed and take the coast.",
            },
            {
                "phase": 0.24,
                "caption": "You are on the nails. Driving over them forces a slow-down on your next move.",
            },
            {
                "phase": 0.36,
                "caption": "At speed, only the three hardest-braking points are offered: the three nearest your car.",
            },
            {
                "phase": 0.48,
                "caption": "You brake to speed one. That move started on the nails, so they act once more.",
            },
            {
                "phase": 0.60,
                "caption": "At speed one you may stop, roll straight on, or move to either side, but not accelerate.",
            },
            {"phase": 0.72, "caption": "You roll on, clear of the nails."},
            {"phase": 0.85, "caption": "That move touched no nails, so all nine points are offered again."},
        ],
        "elements": [
            el(
                "strip",
                "hazard",
                color="nails",
                corners=[
                    [3.15, 1.7], [3.35, 1.2], [4.1, 1.1], [4.8, 1.35],
                    [4.85, 2.3], [4.5, 2.85], [3.6, 2.9], [3.1, 2.4],
                ],
            ),
            el("trail_in", "trail", kind="static", **{"from": [0, 2]}, to=[2, 2]),
            el("trail_onto_nails", "trail", kind="follow", car="car", move=0),
            el("trail_brake", "trail", kind="follow", car="car", move=1),
            el("trail_roll", "trail", kind="follow", car="car", move=2),
            el("projector_1", "projector", **{"from": [2, 2]}, to=[4, 2], color="legal",
               **env(None, (0.13, 0.18))),
            el("pickers_1", "pickers", style="offered", around=[4, 2], **env((0.02, 0.09), (0.13, 0.18))),
            el("ring_1", "pickRing", at=[4, 2], **env((0.09, 0.12), (0.13, 0.18))),
            el("projector_2", "projector", **{"from": [4, 2]}, to=[6, 2], color="legal",
               **env((0.22, 0.24), (0.38, 0.42))),
            el("pickers_brake", "pickers", style="offered", points=brake_set,
               **env((0.26, 0.33), (0.38, 0.42))),
            el("pickers_brake_withheld", "pickers", style="withheld", around=[6, 2], exclude=brake_set,
               **env((0.26, 0.33), (0.38, 0.42))),
            el("ring_2", "pickRing", at=[5, 2], **env((0.33, 0.36), (0.38, 0.42))),
            el("projector_3", "projector", **{"from": [5, 2]}, to=[6, 2], color="legal",
               **env((0.46, 0.48), (0.62, 0.66))),
            el("pickers_crawl", "pickers", style="offered", points=crawl_set,
               **env((0.50, 0.57), (0.62, 0.66))),
            el("pickers_crawl_withheld", "pickers", style="withheld", around=[6, 2], exclude=crawl_set,
               **env((0.50, 0.57), (0.62, 0.66))),
            el("ring_3", "pickRing", at=[6, 2], **env((0.57, 0.60), (0.62, 0.66))),
            el("projector_4", "projector", **{"from": [6, 2]}, to=[7, 2], color="legal",
               **env((0.72, 0.76))),
            el("pickers_free", "pickers", style="offered", around=[7, 2], **env((0.76, 0.85))),
            el(
                "car",
                "car",
                rest=[2, 2],
                restHeading=[1, 0],
                moves=[
                    {"to": [4, 2], "start": 0.14, "end": 0.23, "heading": [1, 0]},
                    {"to": [5, 2], "start": 0.38, "end": 0.46, "heading": [1, 0]},
                    {"to": [6, 2], "start": 0.62, "end": 0.70, "heading": [1, 0]},
                ],
            ),
        ],
    }


BUILTINS = (velocity_rule, wall_crash, finish_line, oil_spill, nails)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report drift instead of writing")
    args = parser.parse_args()

    sys.path.insert(0, str(pathlib.Path(__file__).parent))
    import scene  # noqa: PLC0415 - kept local so the module path is set first

    PROJECTS.mkdir(exist_ok=True)
    drift = 0
    for build in BUILTINS:
        project = build()
        warnings = scene.validate(project)
        for warning in warnings:
            print(f"  ! {project['id']}: {warning}", file=sys.stderr)
        text = json.dumps(project, indent=2, ensure_ascii=False) + "\n"
        path = PROJECTS / f"{project['id']}.json"
        if args.check:
            current = path.read_text(encoding="utf-8") if path.exists() else ""
            state = "up to date" if current == text else "OUT OF DATE"
            if current != text:
                drift += 1
            print(f"{path.name}: {state}")
        else:
            path.write_text(text, encoding="utf-8")
            print(f"wrote {path.relative_to(pathlib.Path.cwd()) if path.is_relative_to(pathlib.Path.cwd()) else path}")
    return 1 if drift else 0


if __name__ == "__main__":
    raise SystemExit(main())
