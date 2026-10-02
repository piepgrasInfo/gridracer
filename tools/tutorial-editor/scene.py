"""The tutorial scene format: schema constants, defaults and validation.

A *project* is one page of the app's "How to play" walkthrough: a grid, a list of frames the
reader steps through, and a list of drawing elements whose visibility is driven by the phase.
The GUI edits these files, `codegen.py` turns one into a Compose animation, and the five files
in `projects/` are the app's current walkthroughs imported into the format.

Everything is expressed in *grid units* (floats, so geometry can sit between grid points) and
in *phase*, a 0..1 number that runs the length of the animation. Nothing here knows about
pixels; the cell size is worked out at draw time from the canvas the animation is given.
"""

from __future__ import annotations

# ---------------------------------------------------------------- element types

ELEMENT_TYPES = (
    "wall",
    "finishLine",
    "gravel",
    "hazard",
    "trail",
    "projector",
    "pickers",
    "pickRing",
    "car",
    "wreck",
    "statusFrame",
    "label",
)

#: Human-readable one-liners, shown in the GUI's "add element" menu.
ELEMENT_HELP = {
    "wall": "A solid wall segment. Any move whose path crosses it is illegal.",
    "finishLine": "The finish line plus its direction arrow.",
    "gravel": "A gravel trap: a band beside a wall (on the right of from -> to), with the barrier at its far edge.",
    "hazard": "An oil spill / nail strip: a translucent blob through the given corners.",
    "trail": "A trail segment — either fixed, or growing behind a car as it moves.",
    "projector": "The dashed coast projection from the car to its zero-acceleration point.",
    "pickers": "Grid-point markers: the green points offered, or the dim points withheld.",
    "pickRing": "The ring on the point the walkthrough is about to drive to.",
    "car": "A demo car: where it rests, and the moves it drives.",
    "wreck": "A burning wreck at a crash site.",
    "statusFrame": "A copy of the in-race status frame, with the lap counter.",
    "label": "A free-standing text label anchored to a grid position.",
}

#: Named colours a hazard or label may use, mapped to the constants in HowToPlayVisuals.kt.
NAMED_COLORS = {
    "oil": ("AnimOilColor", "#37474F"),
    "nails": ("AnimNailsColor", "#FF6F00"),
    "wall": ("AnimWallColor", "#212121"),
    "finish": ("AnimFinishColor", "#6D4C41"),
    "legal": ("AnimLegalColor", "#00C853"),
    "selected": ("AnimSelectedColor", "#2E7D32"),
    "crash": ("AnimCrashColor", "#D32F2F"),
    "car": ("AnimCarColor", "#E53935"),
    "grid": ("AnimGridColor", "#BDBDBD"),
}

CELL_MODES = ("fit", "minDimension")
ORIGIN_MODES = ("points", "cells")
PROJECTOR_COLORS = ("legal", "crash")
PICKER_STYLES = ("offered", "withheld")
TRAIL_KINDS = ("static", "follow")

DEFAULT_GRID = {
    "cols": 9,
    "rows": 5,
    "cellMode": "fit",
    "cellScale": 0.85,
    "cellDivisor": 7.0,
    "originMode": "points",
}


def new_project(project_id: str, name: str) -> dict:
    """An empty project: one grid, one frame, one car, nothing else."""
    return {
        "id": project_id,
        "symbol": "".join(part.capitalize() for part in project_id.split("_")),
        "name": name,
        "title": name,
        "body": "",
        "titleRes": "",
        "bodyRes": "",
        "notes": "",
        "grid": dict(DEFAULT_GRID),
        "groups": {},
        "frames": [{"phase": 0.0, "caption": ""}],
        "elements": [
            {
                "id": "car",
                "type": "car",
                "rest": [2.0, 2.0],
                "restHeading": [1, 0],
                "moves": [],
            }
        ],
    }


# ---------------------------------------------------------------- validation


class SceneError(ValueError):
    """A project that cannot be drawn or generated from."""


def _num(value, where: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise SceneError(f"{where}: expected a number, got {value!r}")
    return float(value)


def _point(value, where: str) -> list[float]:
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        raise SceneError(f"{where}: expected [x, y], got {value!r}")
    return [_num(value[0], where), _num(value[1], where)]


def _envelope(element: dict, where: str) -> None:
    alpha = element.get("alpha")
    if alpha is None:
        return
    if not isinstance(alpha, dict):
        raise SceneError(f"{where}.alpha: expected an object with 'in' and/or 'out'")
    for key in ("in", "out"):
        span = alpha.get(key)
        if span is None:
            continue
        if not isinstance(span, (list, tuple)) or len(span) != 2:
            raise SceneError(f"{where}.alpha.{key}: expected [from, to]")
        first, second = _num(span[0], f"{where}.alpha.{key}"), _num(span[1], f"{where}.alpha.{key}")
        if second < first:
            raise SceneError(f"{where}.alpha.{key}: {second} ends before it starts at {first}")


def validate(project: dict) -> list[str]:
    """Raises [SceneError] on anything undrawable; returns non-fatal warnings.

    Hard errors are things the generator cannot express (an unknown element type, a trail that
    follows a car that isn't there). Warnings are things that are legal but almost certainly a
    mistake — frames out of order, an element that is never visible.
    """
    warnings: list[str] = []

    if not project.get("id"):
        raise SceneError("project: missing 'id'")
    if not project.get("symbol"):
        raise SceneError("project: missing 'symbol' (the Kotlin name prefix)")
    if not project["symbol"][0].isupper():
        raise SceneError("project.symbol: must start with an upper-case letter")

    grid = project.get("grid") or {}
    for key in ("cols", "rows"):
        if not isinstance(grid.get(key), int) or grid[key] < 1:
            raise SceneError(f"grid.{key}: expected a positive integer")
    if grid.get("cellMode", "fit") not in CELL_MODES:
        raise SceneError(f"grid.cellMode: expected one of {CELL_MODES}")
    if grid.get("originMode", "points") not in ORIGIN_MODES:
        raise SceneError(f"grid.originMode: expected one of {ORIGIN_MODES}")

    frames = project.get("frames") or []
    if not frames:
        raise SceneError("project.frames: a walkthrough needs at least one frame")
    phases = []
    for i, frame in enumerate(frames):
        phase = _num(frame.get("phase"), f"frames[{i}].phase")
        if not 0.0 <= phase <= 1.0:
            raise SceneError(f"frames[{i}].phase: {phase} is outside 0..1")
        phases.append(phase)
    if phases != sorted(phases):
        warnings.append("frames are not in ascending phase order; the arrows will jump about")
    if len(set(phases)) != len(phases):
        warnings.append("two frames share a phase, so one of them can never be reached")

    groups = project.get("groups") or {}
    if not isinstance(groups, dict):
        raise SceneError("project.groups: expected an object keyed by group id")
    for group_id, group in groups.items():
        _envelope(group, f"groups.{group_id}")

    cars = {e["id"]: e for e in project.get("elements", []) if e.get("type") == "car"}
    seen_ids: set[str] = set()

    for i, element in enumerate(project.get("elements") or []):
        where = f"elements[{i}]"
        kind = element.get("type")
        if kind not in ELEMENT_TYPES:
            raise SceneError(f"{where}.type: unknown element type {kind!r}")
        element_id = element.get("id")
        if not element_id:
            raise SceneError(f"{where}: missing 'id'")
        if element_id in seen_ids:
            raise SceneError(f"{where}: duplicate element id {element_id!r}")
        seen_ids.add(element_id)
        group = element.get("group")
        if group and group not in groups:
            raise SceneError(f"{where}.group: no group named {group!r}")
        _envelope(element, where)

        if kind == "wall":
            _point(element.get("from"), f"{where}.from")
            _point(element.get("to"), f"{where}.to")
        elif kind == "finishLine":
            for key in ("x", "y0", "y1"):
                _num(element.get(key), f"{where}.{key}")
        elif kind == "gravel":
            _point(element.get("from"), f"{where}.from")
            _point(element.get("to"), f"{where}.to")
            depth = element.get("depth", 2.0)
            _num(depth, f"{where}.depth")
            if float(depth) <= 0:
                raise SceneError(f"{where}.depth: must be greater than 0")
            if list(element["from"]) == list(element["to"]):
                raise SceneError(f"{where}: from and to are the same point, so the band has no length")
        elif kind == "hazard":
            corners = element.get("corners") or []
            if len(corners) < 3:
                raise SceneError(f"{where}.corners: a hazard needs at least 3 corners")
            for j, corner in enumerate(corners):
                _point(corner, f"{where}.corners[{j}]")
        elif kind == "trail":
            trail_kind = element.get("kind", "static")
            if trail_kind not in TRAIL_KINDS:
                raise SceneError(f"{where}.kind: expected one of {TRAIL_KINDS}")
            if trail_kind == "static":
                _point(element.get("from"), f"{where}.from")
                _point(element.get("to"), f"{where}.to")
            else:
                car_id = element.get("car")
                if car_id not in cars:
                    raise SceneError(f"{where}.car: no car element named {car_id!r}")
                index = element.get("move", 0)
                moves = cars[car_id].get("moves") or []
                if not isinstance(index, int) or not 0 <= index < len(moves):
                    raise SceneError(f"{where}.move: car {car_id!r} has no move {index}")
        elif kind == "projector":
            _point(element.get("from"), f"{where}.from")
            _point(element.get("to"), f"{where}.to")
            if element.get("color", "legal") not in PROJECTOR_COLORS:
                raise SceneError(f"{where}.color: expected one of {PROJECTOR_COLORS}")
        elif kind == "pickers":
            if element.get("style", "offered") not in PICKER_STYLES:
                raise SceneError(f"{where}.style: expected one of {PICKER_STYLES}")
            if not resolve_pickers(element):
                warnings.append(f"{where}: resolves to no points, so it draws nothing")
            for point in resolve_pickers(element):
                if point != [round(point[0]), round(point[1])]:
                    warnings.append(
                        f"{where}: point {point} is not a grid point; cars can only stop on integers"
                    )
        elif kind == "pickRing":
            _point(element.get("at"), f"{where}.at")
        elif kind == "car":
            _point(element.get("rest"), f"{where}.rest")
            for j, move in enumerate(element.get("moves") or []):
                _point(move.get("to"), f"{where}.moves[{j}].to")
                start = _num(move.get("start"), f"{where}.moves[{j}].start")
                end = _num(move.get("end"), f"{where}.moves[{j}].end")
                if end < start:
                    raise SceneError(f"{where}.moves[{j}]: ends at {end} before it starts at {start}")
        elif kind == "wreck":
            _point(element.get("at"), f"{where}.at")
            _num(element.get("showFrom", 0.0), f"{where}.showFrom")
        elif kind == "statusFrame":
            switch = element.get("switch")
            if switch:
                if switch.get("kind") == "carPastX":
                    if switch.get("car") not in cars:
                        raise SceneError(f"{where}.switch.car: no car named {switch.get('car')!r}")
                    _num(switch.get("x"), f"{where}.switch.x")
                elif switch.get("kind") == "phase":
                    _num(switch.get("at"), f"{where}.switch.at")
                else:
                    raise SceneError(f"{where}.switch.kind: expected 'carPastX' or 'phase'")
        elif kind == "label":
            _point(element.get("at"), f"{where}.at")
            if not element.get("text"):
                warnings.append(f"{where}: empty label text")

        if kind not in ("wreck",) and _never_visible(element, groups):
            warnings.append(f"{where}: its fade-out finishes before its fade-in starts")
        elif not _reachable(element, groups, phases):
            warnings.append(
                f"{where} ({element_id!r}) lies outside the phases the frames step through "
                f"({min(phases):g}..{max(phases):g}), so nobody can ever see it"
            )

    return warnings


def _alpha_at(element: dict, groups: dict, phase: float) -> float:
    """An element's alpha at one phase: its own envelope, times its group's."""
    def envelope(alpha: dict) -> float:
        value = 1.0
        fade_in, fade_out = alpha.get("in"), alpha.get("out")
        if fade_in:
            start, end = fade_in
            value = min(value, 0.0 if phase <= start else 1.0 if phase >= end else (phase - start) / (end - start))
        if fade_out:
            start, end = fade_out
            value = min(value, 1.0 if phase <= start else 0.0 if phase >= end else 1 - (phase - start) / (end - start))
        return value

    alpha = envelope(element.get("alpha") or {})
    group = groups.get(element.get("group") or "")
    if group:
        alpha *= envelope(group.get("alpha") or {})
    return alpha


def _reachable(element: dict, groups: dict, phases: list[float]) -> bool:
    """True if the reader can ever see this element.

    The arrows do not jump between frames, they animate across the phases in between, so the
    range the reader actually sees is the whole closed interval from the first frame to the
    last - an element that only peaks mid-transition is seen, and is not a mistake. What this
    catches is an element living entirely outside that interval, which is what happens when
    frames are deleted from a scene and its elements are left behind.
    """
    low, high = min(phases), max(phases)
    if element["type"] == "wreck":
        return high >= float(element.get("showFrom", 0.0))
    steps = 200
    return any(
        _alpha_at(element, groups, low + (high - low) * i / steps) > 0.01
        for i in range(steps + 1)
    )


def _never_visible(element: dict, groups: dict) -> bool:
    """True when an element's own envelope can never reach a non-zero alpha."""
    alpha = element.get("alpha") or {}
    fade_in, fade_out = alpha.get("in"), alpha.get("out")
    if fade_in and fade_out and fade_out[1] <= fade_in[0]:
        return True
    group = groups.get(element.get("group") or "", {}).get("alpha") or {}
    group_in, group_out = group.get("in"), group.get("out")
    if group_in and group_out and group_out[1] <= group_in[0]:
        return True
    return False


def resolve_pickers(element: dict) -> list[list[float]]:
    """The grid points a `pickers` element actually draws.

    A set is built from the nine candidates around `around` (the coast point — the same
    9-neighbourhood `GameEngine.candidateMoves` offers) plus any explicit `points`, minus
    `exclude`. Order is preserved so the generated Kotlin lists points the way the editor does.
    """
    points: list[list[float]] = []
    around = element.get("around")
    if around:
        cx, cy = float(around[0]), float(around[1])
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                points.append([cx + dx, cy + dy])
    for point in element.get("points") or []:
        points.append([float(point[0]), float(point[1])])
    excluded = {(float(p[0]), float(p[1])) for p in element.get("exclude") or []}
    seen: set[tuple[float, float]] = set()
    result = []
    for point in points:
        key = (point[0], point[1])
        if key in excluded or key in seen:
            continue
        seen.add(key)
        result.append(point)
    return result


def move_legs(car: dict) -> list[dict]:
    """A car's moves with their `from` filled in: rest, then each move's predecessor's `to`."""
    legs = []
    position = [float(car["rest"][0]), float(car["rest"][1])]
    for move in car.get("moves") or []:
        target = [float(move["to"][0]), float(move["to"][1])]
        legs.append({**move, "from": list(position), "to": target})
        position = target
    return legs
