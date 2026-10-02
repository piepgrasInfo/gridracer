#!/usr/bin/env python3
"""Tests for the scene format and the Kotlin generator.

The generator is the only thing standing between a scene edited in a browser and code that
ships, so what is checked here is: the imported projects still validate, generation is
deterministic, the three declarations `HowToPlayScreen` imports come out with the right names,
and each element type reaches the drawing primitive it is supposed to reach.

Run:  python3 tools/tutorial-editor/test/test_codegen.py
"""

from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE.parent))

import codegen  # noqa: E402
import import_builtin  # noqa: E402
import scene  # noqa: E402

PROJECTS = HERE.parent / "projects"


def load(project_id: str) -> dict:
    return json.loads((PROJECTS / f"{project_id}.json").read_text(encoding="utf-8"))


class SceneFormatTest(unittest.TestCase):
    def test_the_imported_walkthroughs_validate_without_warnings(self):
        """The transcriptions the editor starts from must be clean.

        Checked against `import_builtin.py`'s output rather than against `projects/` on disk,
        because a project that has since been edited in the editor is allowed to carry
        warnings - that is what warnings are for.
        """
        for build in import_builtin.BUILTINS:
            project = build()
            with self.subTest(project=project["id"]):
                self.assertEqual([], scene.validate(project))

    def test_every_project_on_disk_is_drawable(self):
        """However a project has been edited, it must still be valid enough to draw."""
        for path in sorted(PROJECTS.glob("*.json")):
            with self.subTest(project=path.stem):
                scene.validate(json.loads(path.read_text(encoding="utf-8")))

    def test_no_project_on_disk_carries_an_element_nobody_can_see(self):
        """Deleting frames strands the elements they showed, and the leftovers are invisible.

        Unlike the warnings a project is otherwise allowed to carry, this one always means
        dead weight: an element outside the phases the frames reach cannot be seen, cannot be
        edited into view without adding frames back, and is still generated into Kotlin.
        """
        for path in sorted(PROJECTS.glob("*.json")):
            with self.subTest(project=path.stem):
                warnings = scene.validate(json.loads(path.read_text(encoding="utf-8")))
                stranded = [w for w in warnings if "nobody can ever see it" in w]
                self.assertEqual([], stranded)

    def test_deleting_frames_strands_the_elements_they_showed(self):
        """The warning that catches a scene whose frames no longer reach all of its elements."""
        project = scene.new_project("demo", "Demo")
        project["frames"] = [{"phase": 0.0, "caption": "a"}, {"phase": 0.3, "caption": "b"}]
        project["elements"].append(
            {"id": "late", "type": "pickRing", "at": [2, 2], "alpha": {"in": [0.6, 0.7]}}
        )
        warnings = scene.validate(project)
        self.assertTrue(any("nobody can ever see it" in w for w in warnings), warnings)

    def test_an_element_seen_only_mid_transition_is_not_flagged(self):
        """The arrows animate between frames, so a peak in the gap is seen, not stranded."""
        project = scene.new_project("demo", "Demo")
        project["frames"] = [{"phase": 0.0, "caption": "a"}, {"phase": 0.6, "caption": "b"}]
        project["elements"].append(
            {"id": "flash", "type": "pickRing", "at": [2, 2],
             "alpha": {"in": [0.25, 0.35], "out": [0.25, 0.40]}}
        )
        self.assertEqual([], scene.validate(project))

    def test_every_project_on_disk_still_generates(self):
        """Whatever has been done to a project in the editor, it must still compile to Kotlin.

        This deliberately does *not* assert that `projects/` matches `import_builtin.py`:
        editing a project is what the editor is for, and a scene that has been reworked is
        expected to diverge from the transcription it started as. Run
        `import_builtin.py --check` when you want to know whether it has.
        """
        for path in sorted(PROJECTS.glob("*.json")):
            with self.subTest(project=path.stem):
                source = codegen.generate(json.loads(path.read_text(encoding="utf-8")))
                self.assertIn("@Composable", source)
                self.assertIn("Canvas(modifier = Modifier.fillMaxSize())", source)

    def test_a_new_project_is_valid(self):
        self.assertEqual([], scene.validate(scene.new_project("demo_rule", "Demo rule")))

    def test_unknown_element_type_is_rejected(self):
        project = scene.new_project("demo", "Demo")
        project["elements"].append({"id": "x", "type": "sparkles"})
        with self.assertRaises(scene.SceneError):
            scene.validate(project)

    def test_trail_following_a_missing_car_is_rejected(self):
        project = scene.new_project("demo", "Demo")
        project["elements"].append({"id": "t", "type": "trail", "kind": "follow", "car": "ghost", "move": 0})
        with self.assertRaises(scene.SceneError):
            scene.validate(project)

    def test_trail_following_a_move_that_does_not_exist_is_rejected(self):
        project = scene.new_project("demo", "Demo")
        project["elements"][0]["moves"] = [{"to": [3, 2], "start": 0.1, "end": 0.2, "heading": [1, 0]}]
        project["elements"].append({"id": "t", "type": "trail", "kind": "follow", "car": "car", "move": 4})
        with self.assertRaises(scene.SceneError):
            scene.validate(project)

    def test_a_gravel_band_needs_depth_and_length(self):
        for bad in ({"depth": 0}, {"depth": -1}, {"to": [-0.5, 2]}):
            with self.subTest(bad=bad):
                project = scene.new_project("demo", "Demo")
                project["elements"].append({"id": "g", "type": "gravel", "from": [-0.5, 2], "to": [8.5, 2], **bad})
                with self.assertRaises(scene.SceneError):
                    scene.validate(project)

    def test_gravel_at_the_default_depth_leaves_the_argument_out(self):
        project = scene.new_project("demo", "Demo")
        project["symbol"] = "Demo"
        project["elements"].append({"id": "g", "type": "gravel", "from": [-0.5, 2], "to": [8.5, 2]})
        self.assertIn("drawAnimGravel(grid, -0.5f, 2f, 8.5f, 2f)", codegen.generate(project))
        project["elements"][-1]["depth"] = 1.5
        self.assertIn("depth = 1.5f", codegen.generate(project))

    def test_duplicate_element_ids_are_rejected(self):
        project = scene.new_project("demo", "Demo")
        project["elements"].append({"id": "car", "type": "pickRing", "at": [1, 1]})
        with self.assertRaises(scene.SceneError):
            scene.validate(project)

    def test_out_of_order_frames_warn_rather_than_fail(self):
        project = scene.new_project("demo", "Demo")
        project["frames"] = [{"phase": 0.5, "caption": "b"}, {"phase": 0.2, "caption": "a"}]
        warnings = scene.validate(project)
        self.assertTrue(any("ascending" in warning for warning in warnings), warnings)

    def test_pickers_resolve_nine_around_a_coast_minus_exclusions(self):
        element = {"type": "pickers", "around": [5, 1], "exclude": [[5, 0], [6, 2]]}
        points = scene.resolve_pickers(element)
        self.assertEqual(7, len(points))
        self.assertNotIn([5.0, 0.0], points)
        self.assertIn([4.0, 0.0], points)

    def test_move_legs_chain_from_the_rest_position(self):
        car = {
            "rest": [2, 2],
            "moves": [{"to": [4, 2], "start": 0, "end": 0.1}, {"to": [5, 3], "start": 0.2, "end": 0.3}],
        }
        legs = scene.move_legs(car)
        self.assertEqual([2.0, 2.0], legs[0]["from"])
        self.assertEqual([4.0, 2.0], legs[1]["from"])


class CodegenTest(unittest.TestCase):
    def test_generation_is_deterministic(self):
        project = load("oil_spill")
        self.assertEqual(codegen.generate(project), codegen.generate(project))

    def test_declares_the_three_symbols_how_to_play_screen_imports(self):
        expected = {
            "velocity_rule": ("VelocityRuleFrames", "VelocityRuleAnimation", "velocityCaption"),
            "wall_crash": ("WallCrashFrames", "WallCrashAnimation", "wallCrashCaption"),
            "finish_line": ("FinishLineFrames", "FinishLineAnimation", "finishLineCaption"),
            "oil_spill": ("OilSpillFrames", "OilSpillAnimation", "oilSpillCaption"),
            "nails": ("NailsFrames", "NailsAnimation", "nailsCaption"),
            "gravel_trap": ("GravelTrapFrames", "GravelTrapAnimation", "gravelTrapCaption"),
        }
        for project_id, (frames, animation, caption) in expected.items():
            with self.subTest(project=project_id):
                source = codegen.generate(load(project_id))
                self.assertIn(f"internal val {frames} = listOf(", source)
                self.assertIn(f"internal fun {animation}(phase: Float) {{", source)
                self.assertIn(f"internal fun {caption}(phase: Float): String = when {{", source)

    def test_frame_phases_match_the_project(self):
        project = load("nails")
        source = codegen.generate(project)
        listed = source.split("internal val NailsFrames = listOf(")[1].split(")")[0]
        self.assertEqual(
            [codegen.f(frame["phase"]) for frame in project["frames"]],
            [part.strip() for part in listed.split(",")],
        )

    def test_each_element_type_reaches_its_drawing_primitive(self):
        primitives = {
            "wall": "drawAnimWall(",
            "finishLine": "drawAnimFinishLine(",
            "gravel": "drawAnimGravel(",
            "hazard": "drawHazardZone(",
            "projector": "drawProjector(",
            "pickers": "drawOfferedPickers(",
            "pickRing": "drawPickRing(",
            "car": "drawDemoCar(",
            "wreck": "drawWreck(",
            "statusFrame": "drawStatusFrame(",
            "label": "drawAnimLabel(",
        }
        for element_type, call in primitives.items():
            with self.subTest(element=element_type):
                project = scene.new_project("demo", "Demo")
                project["symbol"] = "Demo"
                element = {"id": f"e_{element_type.lower()}", **_sample(element_type)}
                project["elements"].append(element)
                self.assertIn(call, codegen.generate(project))

    def test_a_following_trail_indexes_its_cars_move_list(self):
        project = scene.new_project("demo", "Demo")
        project["symbol"] = "Demo"
        project["elements"][0]["moves"] = [{"to": [4, 2], "start": 0.1, "end": 0.2, "heading": [1, 0]}]
        project["elements"].append({"id": "t", "type": "trail", "kind": "follow", "car": "car", "move": 0})
        source = codegen.generate(project)
        self.assertIn("drawFollowTrail(carMoves[0], car.position, phase, cell)", source)

    def test_a_skid_passes_its_shimmy_through(self):
        source = codegen.generate(load("oil_spill"))
        self.assertIn("shimmy = 0.1f", source)

    def test_group_fades_become_one_multiplied_variable(self):
        """Built here rather than from a project on disk: none of the five uses a group now,
        and a scene edited in the editor is free to stop using one at any time."""
        project = scene.new_project("demo", "Demo")
        project["symbol"] = "Demo"
        project["frames"] = [{"phase": 0.0, "caption": "a"}, {"phase": 0.5, "caption": "b"}]
        project["groups"] = {"right_way": {"alpha": {"out": [0.38, 0.46]}}}
        project["elements"].append(
            {"id": "ring", "type": "pickRing", "at": [2, 2], "group": "right_way"}
        )
        source = codegen.generate(project)
        self.assertIn("val rightWayAlpha = rampDown(phase, 0.38f, 0.46f)", source)
        self.assertIn("rightWayAlpha", source.split("// ring")[1])

    def test_non_default_grid_metrics_are_emitted(self):
        source = codegen.generate(load("velocity_rule"))
        self.assertIn("cellMode = AnimCellMode.MIN_DIMENSION", source)
        self.assertIn("originMode = AnimOriginMode.CELLS", source)

    def test_default_grid_metrics_are_left_out(self):
        source = codegen.generate(load("nails"))
        self.assertIn("val grid = drawAnimGrid(cols = 9, rows = 5)", source)

    def test_captions_are_a_when_chain_over_the_frame_boundaries(self):
        source = codegen.generate(load("wall_crash"))
        # Caption i runs until the *next* frame's phase begins, and reads a string resource
        # so the walkthrough is translated with the rest of the app.
        self.assertIn(
            "phase < 0.25f -> stringResource(R.string.how_to_play_wall_crash_caption_1)", source
        )
        self.assertIn(
            "phase < 0.55f -> stringResource(R.string.how_to_play_wall_crash_caption_2)", source
        )
        self.assertIn("else -> stringResource(R.string.how_to_play_wall_crash_caption_4)", source)
        self.assertIn("import androidx.compose.ui.res.stringResource", source)
        self.assertIn("import info.piepgras.gridracer.R", source)

    def test_caption_resources_carry_the_english_text_out_as_xml(self):
        project = scene.new_project("demo", "Demo")
        project["frames"] = [
            {"phase": 0.0, "caption": "Ampersands & angle <brackets>"},
            {"phase": 0.5, "caption": "Don't trim me "},
        ]
        fragment = codegen.caption_strings_xml(project)
        self.assertIn(
            '<string name="how_to_play_demo_caption_1">'
            "Ampersands &amp; angle &lt;brackets&gt;</string>",
            fragment,
        )
        # An apostrophe and a trailing space both need an escape, or aapt rejects/trims them.
        self.assertIn(r"Don\'t trim me\u0020</string>", fragment)
        # And the whole thing has to be parseable XML.
        ET.fromstring(fragment)

    def test_installing_captions_never_overwrites_the_apps_english(self):
        project = scene.new_project("demo", "Demo")
        project["frames"] = [{"phase": 0.0, "caption": "from the scene"}]
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "strings.xml"
            path.write_text("<resources>\n</resources>\n", encoding="utf-8")

            self.assertEqual(["how_to_play_demo_caption_1"], codegen.merge_caption_strings(project, path))
            self.assertIn("from the scene", path.read_text(encoding="utf-8"))

            # An English edit made in the app survives a regeneration.
            path.write_text(
                path.read_text(encoding="utf-8").replace("from the scene", "edited in the app"),
                encoding="utf-8",
            )
            self.assertEqual([], codegen.merge_caption_strings(project, path))
            self.assertIn("edited in the app", path.read_text(encoding="utf-8"))
            self.assertNotIn("from the scene", path.read_text(encoding="utf-8"))

    def test_scene_text_that_is_not_a_caption_is_escaped_for_kotlin(self):
        project = scene.new_project("demo", "Demo")
        project["symbol"] = "Demo"
        project["elements"].append(
            {"id": "note", "type": "label", "at": [2, 2],
             "text": 'a "quote", a $dollar and a \\ slash'}
        )
        source = codegen.generate(project)
        self.assertIn(r'"a \"quote\", a \$dollar and a \\ slash"', source)

    def test_a_point_off_the_grid_lattice_is_refused(self):
        project = scene.new_project("demo", "Demo")
        project["symbol"] = "Demo"
        project["elements"].append({"id": "ring", "type": "pickRing", "at": [2.5, 1]})
        with self.assertRaises(codegen.GenerationError):
            codegen.generate(project)

    def test_element_ids_that_collide_as_kotlin_names_are_refused(self):
        project = scene.new_project("demo", "Demo")
        project["symbol"] = "Demo"
        project["elements"].append({"id": "carRight", "type": "pickRing", "at": [2, 1]})
        project["elements"].append({"id": "car_right", "type": "pickRing", "at": [3, 1]})
        with self.assertRaises(codegen.GenerationError):
            codegen.generate(project)

    def test_float_literals_are_kotlin_shaped(self):
        self.assertEqual("2f", codegen.f(2))
        self.assertEqual("0.17f", codegen.f(0.17))
        self.assertEqual("-0.3f", codegen.f(-0.3))
        self.assertEqual("0f", codegen.f(0))

    def test_identifiers_camel_case_scene_ids(self):
        self.assertEqual("carRight", codegen.ident("car_right"))
        self.assertEqual("oilSpill", codegen.ident("oil-spill"))
        self.assertEqual("car", codegen.ident("car"))


def _sample(element_type: str) -> dict:
    """A minimal valid element of each type, for the primitive-coverage test."""
    return {
        "wall": {"type": "wall", "from": [2, 0], "to": [2, 4]},
        "finishLine": {"type": "finishLine", "x": 4.5, "y0": 0, "y1": 4, "arrow": {"y": 2, "direction": 1}},
        "gravel": {"type": "gravel", "from": [-0.5, 2], "to": [8.5, 2]},
        "hazard": {"type": "hazard", "color": "oil", "corners": [[1, 1], [3, 1], [3, 3], [1, 3]]},
        "projector": {"type": "projector", "from": [2, 2], "to": [4, 2]},
        "pickers": {"type": "pickers", "style": "offered", "around": [4, 2]},
        "pickRing": {"type": "pickRing", "at": [4, 2]},
        "car": {"type": "car", "rest": [1, 1], "restHeading": [1, 0], "moves": []},
        "wreck": {"type": "wreck", "at": [4.5, 2], "showFrom": 0.5},
        "statusFrame": {"type": "statusFrame", "lap": "1/2"},
        "label": {"type": "label", "at": [2, 2], "text": "hello"},
    }[element_type]


if __name__ == "__main__":
    unittest.main(verbosity=2)
