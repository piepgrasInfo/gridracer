# Tutorial editor

A local GUI for building the animations on the app's **How to play** screen, and a generator
that turns what you build into the Compose code the app ships.

```bash
python3 tools/tutorial-editor/server.py     # opens http://127.0.0.1:8765/
```

Nothing to install: Python's standard library and a browser. The app itself gains no runtime,
no parser and no asset — the editor's output is ordinary Kotlin in
`app/src/main/java/info/piepgras/gridracer/ui/howtoplay/`.

All six walkthroughs the app ships are in `projects/`, so the editor opens on the real thing
rather than a blank page. Five were transcribed from hand-written files: change one, export, and
it replaces the hand-written file. The sixth, the gravel trap, was authored here, and the app
ships the Kotlin generated from it.

## How the pieces fit

```
projects/*.json ──edit in the browser──▶ projects/*.json
       │                                      │
       │  render.js (preview)                 │  codegen.py
       ▼                                      ▼
 what you see in the editor          <Symbol>Animation.kt  ──▶  HowToPlayScreen.kt
```

| file | what it is |
| --- | --- |
| `server.py` | the editor: serves `web/`, owns `projects/`, runs the generator on Export |
| `web/render.js` | the preview renderer — a port of `HowToPlayVisuals.kt` / `drawCar` / `drawWreck` |
| `web/editor.js` | the UI: canvas handles, frame and element lists, inspector, export |
| `scene.py` | the scene format: element types, defaults, validation |
| `codegen.py` | scene JSON → a Compose animation file |
| `import_builtin.py` | the five shipped walkthroughs, transcribed into `projects/` |
| `make_sheet.py` | every frame of every project as one contact sheet, for eyeballing |
| `test/` | the format/generator tests and a renderer smoke test |

`web/render.js` and `HowToPlayVisuals.kt` draw the same picture twice, in two languages. They
have to be changed together: a divergence shows up as an editor preview that lies about what
the app will draw. The generator only ever *calls* the Kotlin primitives — it emits no geometry
of its own — so this is the only place the two can drift.

## Using it

**Stage.** The canvas shows the scene at the current phase, in the shape the animation gets on
a real device. Drag the white handles to move whatever is selected; hold **Shift** for free
placement and **Alt** to force a whole grid point. Click near any element's handle to select it.

**Playback.** `▶ Play` runs the whole 0..1 sweep. The `◀ ▶` buttons step frame to frame with
the same 600 ms tween the app uses, which is what the reader will actually see — the arrows
in the app step exactly these frames. The slider scrubs anywhere in between.

**Frames** (left, top) are what those arrows step through: a phase and the caption shown beside
the animation at that phase. `+ Frame here` adds one at the playhead.

**Elements** (left, bottom) are drawn in list order, so the list is the z-order; `↑`/`↓` move an
element back and forward. The dot on each row says whether it is visible at the current phase.

**Element tab** (right) is the inspector for the selected element, including its fade envelope
with a plot of the resulting alpha across the animation and the frames ticked on it — the quick
way to see which frames actually see the thing.

**Scene tab** is the grid (size, how the cell size is chosen, how it is centred) and the groups.
A *group* is a fade several elements share, which is how rule 3 shows a whole first act and then
replaces it with a second.

**Text tab** is the step title and body, the string-resource names, the generated file's doc
comment, and all the captions in one place.

**JSON tab** is the file itself; edit and Apply to load it back.

`Ctrl+S` saves, `Ctrl+Z` undoes, `←`/`→` step frames, `Space` plays, `Delete` removes the
selected element.

## Getting it into the app

**Export Kotlin** writes `out/<Symbol>Animation.kt`. **Install into app** writes the same file
straight into `ui/howtoplay/`, replacing what is there. Either way the file declares exactly the
three things `HowToPlayScreen` uses:

```kotlin
internal val <Symbol>Frames                    // the phases the arrows step through
internal fun <Symbol>Animation(phase: Float)   // the @Composable
internal fun <symbol>Caption(phase: Float)     // the caption for a phase
```

so a generated file is a drop-in replacement for the hand-written one of the same name. A *new*
rule needs one more step: add it to `rememberHowToPlaySteps()` in `HowToPlayScreen.kt`, with
`stringResource` entries for its title and body in `res/values*/strings.xml`.

Then build and look at it:

```bash
./gradlew :app:assembleDebug
python3 tools/tutorial-editor/make_sheet.py      # out/contact_sheet.html — every frame at once
```

The animations are stepped by hand, so the only real check is frame by frame. Capture the frames
on the emulator and compare each offered/withheld point against `GameEngine.candidateMoves`,
`oilSlipTargets` and `nailAllowedSet` — the contact sheet makes the comparison quick, but it is
the app's own frames that decide.

### Is the import faithful?

Yes, and it was checked rather than assumed. With the five generated files installed in place of
the hand-written ones, every frame of every rule was captured on the `GridRacerTest` emulator and
compared against the same capture from the hand-written build: **all 31 frames are pixel-identical
below the system status bar.** So switching a rule over to the editor is a change of source, not
of appearance. (Give each frame time to settle before the screenshot: at 0.6 s one capture caught
the tween still running, which looks exactly like a rendering difference and is not one.)

The app still ships the five hand-written animations. Nothing here replaces them until you press
**Install into app** or run `codegen.py --install`. `codegen.py --check` therefore reports those
five as differing from what the projects generate. Only `GravelTrapAnimation.kt` must come out
identical.

The command line does the same jobs without the GUI:

```bash
python3 tools/tutorial-editor/codegen.py                  # every project -> out/
python3 tools/tutorial-editor/codegen.py oil_spill        # just one
python3 tools/tutorial-editor/codegen.py --install        # straight into the app
python3 tools/tutorial-editor/codegen.py --check          # diff against the app's files*
python3 tools/tutorial-editor/import_builtin.py --check   # has a project been hand-edited?
```

\* `--check` compares the generated source against the file of the same name in the app, so it
only says something useful about a rule whose generated file is actually installed; against a
hand-written file it will always report a difference, pixel-identical output or not.

## The scene format

A project is one page of the walkthrough. Everything is in **grid units** (floats, so geometry
can sit between grid points) and in **phase**, a 0..1 number running the length of the
animation. Nothing is in pixels: the cell size is worked out from whatever canvas the animation
is given.

```jsonc
{
  "id": "oil_spill",              // file name, and the id the API uses
  "symbol": "OilSpill",           // OilSpillFrames / OilSpillAnimation / oilSpillCaption
  "captionSymbol": "…",           // optional override for the caption function's name
  "name": "Oil spills",           // shown in the editor
  "title": "…", "body": "…",      // the step's text, mirroring the string resources
  "titleRes": "…", "bodyRes": "…",// the string resources the app actually renders
  "notes": "…",                   // becomes the generated file's doc comment
  "grid": { "cols": 9, "rows": 5, "cellMode": "fit", "cellScale": 0.85,
            "cellDivisor": 7.0, "originMode": "points" },
  "groups": { "right_way": { "alpha": { "out": [0.38, 0.46] } } },
  "frames": [ { "phase": 0.0, "caption": "…" } ],
  "elements": [ /* drawn in order */ ]
}
```

**Grid.** `cellMode` `fit` takes the largest cell that keeps a margin (`cellScale`); `minDimension`
divides the canvas's short side by `cellDivisor`. `originMode` `points` centres the grid points;
`cells` leaves half a cell of room all round, which is what a wall or a status frame drawn
outside the dots needs.

**Fades.** Every element (and every group) may carry
`"alpha": { "in": [a, b], "out": [c, d] }`, either half optional. The alpha is
`min(ramp(phase, a, b), rampDown(phase, c, d))`, times its group's alpha — the same composition
the hand-written animations spelled out as `when` chains.

**Elements.** Each has an `id` (which is also its Kotlin variable name), a `type`, an optional
`group`, and an optional `alpha`.

| type | fields |
| --- | --- |
| `wall` | `from`, `to`, `width` |
| `finishLine` | `x`, `y0`, `y1`, `width`, `arrow: {y, direction}` |
| `gravel` | `from`, `to`, `depth` (default 2): the band on the right-hand side of `from` -> `to` (y down), with the barrier on its far edge |
| `hazard` | `corners` (a smooth blob through them), `color` (`oil`, `nails`, or `#rrggbb`) |
| `trail` | `kind: "static"` with `from`/`to`, or `kind: "follow"` with `car` and `move` |
| `projector` | `from`, `to`, `color` (`legal` or `crash`) — the dashed coast projection |
| `pickers` | `style` (`offered`/`withheld`), `around` (nine candidates), `points`, `exclude` |
| `pickRing` | `at` — the ring on the point about to be driven to |
| `car` | `rest`, `restHeading`, `moves: [{to, start, end, heading, shimmy, shimmyCycles}]` |
| `wreck` | `at`, `showFrom` |
| `statusFrame` | `lap`, `lapAfter`, `switch: {kind, …}`, `crashedFrom` |
| `label` | `at`, `text`, `size`, `color`, `centered` |

A `car` both defines and draws: its position is available to trails wherever it sits in the
list, so put it where you want it drawn (usually last) and let the trails reference it. Each
move holds its end position until the next move starts, so a car never teleports between legs.
`shimmy` adds a damped wobble across the direction of travel — a car that is skidding rather
than steering.

Points a car can occupy or a picker can mark must be **whole** grid coordinates: the engine only
ever deals in whole points, and the generator refuses anything else. Walls, gravel, hazard
corners, wrecks and labels are decoration and may sit anywhere.

## Keeping the imports honest

`projects/*.json` is where the five hand-written walkthroughs live, transcribed by
`import_builtin.py`. (`gravel_trap.json` has no hand-written original. The importer does not
know it and never overwrites it.) Two checks keep that meaningful:

- `import_builtin.py --check` reports which projects have diverged from the transcription. It
  is a question you ask, not a test that fails: editing a project is the point of the editor,
  so a reworked scene is *expected* to diverge.
- The importer overwrites `projects/` when run without `--check`. Once a project has real edits
  in it, move them into `import_builtin.py` or drop that rule from `BUILTINS`, or the next
  import will throw your work away.

## Tests

```bash
python3 tools/tutorial-editor/test/test_codegen.py    # format + generator
node    tools/tutorial-editor/test/render_smoke.mjs   # the preview renderer
```

The renderer test walks every project across the whole phase range with a recording canvas
stub, so a scene that throws (or draws nothing) at some phase fails here rather than in a
browser tab nobody opened.

Validation warnings are worth reading after an editing session. The one that matters most:

    elements[12] ('car_wrong') lies outside the phases the frames step through (0..0.36),
    so nobody can ever see it

That is what deleting frames does — the elements those frames showed stay in the scene, still
drawn, still generated, and permanently off-screen. The arrows animate *between* frames rather
than jumping, so an element that only peaks in the gap between two frames is fine and is not
flagged; only one stranded outside the whole range is.
