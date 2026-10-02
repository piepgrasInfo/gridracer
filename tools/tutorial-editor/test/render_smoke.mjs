/**
 * Drives `web/render.js` over every project under Node with a recording canvas stub.
 *
 * The editor's preview is the only place a scene is seen before it becomes Kotlin, so a
 * renderer that throws — or silently draws nothing — on some phase would be invisible until
 * someone opened that frame in the browser. This walks every project across the whole phase
 * range and checks both that it survives and that it actually puts marks on the canvas, then
 * pins down the handful of derived values (car easing, fade envelopes, picker resolution) the
 * generated Kotlin has to agree with.
 *
 * Run:  node tools/tutorial-editor/test/render_smoke.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as R from '../web/render.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const PROJECTS = join(HERE, '..', 'projects');

let failures = 0;

function check(condition, message) {
  if (condition) return;
  failures += 1;
  console.error(`  FAIL ${message}`);
}

function close(actual, expected, message, tolerance = 1e-6) {
  check(Math.abs(actual - expected) <= tolerance, `${message} (got ${actual}, want ${expected})`);
}

/** A 2D context that records how many marks were made, and nothing else. */
function recordingContext() {
  const record = { fills: 0, strokes: 0, texts: 0 };
  const noop = () => {};
  return {
    record,
    save: noop,
    restore: noop,
    translate: noop,
    rotate: noop,
    beginPath: noop,
    closePath: noop,
    moveTo: noop,
    lineTo: noop,
    arc: noop,
    quadraticCurveTo: noop,
    bezierCurveTo: noop,
    setLineDash: noop,
    fill: () => { record.fills += 1; },
    stroke: () => { record.strokes += 1; },
    fillRect: () => { record.fills += 1; },
    strokeRect: () => { record.strokes += 1; },
    fillText: () => { record.texts += 1; },
    set fillStyle(value) { if (value == null) throw new Error('null fillStyle'); },
    set strokeStyle(value) { if (value == null) throw new Error('null strokeStyle'); },
    set lineWidth(value) { if (Number.isNaN(value)) throw new Error('NaN lineWidth'); },
    set lineCap(value) { void value; },
    set font(value) { void value; },
    set textAlign(value) { void value; },
    set textBaseline(value) { void value; },
  };
}

// ---------------------------------------------------------------- every project, every phase

const files = readdirSync(PROJECTS).filter((name) => name.endsWith('.json'));
check(files.length > 0, 'there are project files to render');

for (const file of files) {
  const project = JSON.parse(readFileSync(join(PROJECTS, file), 'utf-8'));
  let minimumFills = Infinity;
  for (let step = 0; step <= 100; step += 1) {
    const phase = step / 100;
    const ctx = recordingContext();
    try {
      const { grid } = R.drawScene(ctx, 1148, 940, project, phase);
      check(grid.cellSize > 0, `${file}: cell size is positive at ${phase}`);
    } catch (error) {
      failures += 1;
      console.error(`  FAIL ${file} threw at phase ${phase}: ${error.message}`);
      break;
    }
    minimumFills = Math.min(minimumFills, ctx.record.fills);
  }
  // The grid dots alone are one fill per point, so any real scene is well above this.
  const points = project.grid.cols * project.grid.rows;
  check(minimumFills >= points, `${file}: draws at least the grid at every phase`);
  console.log(`  ${file}: ok (${project.elements.length} elements, ${project.frames.length} frames)`);
}

// ---------------------------------------------------------------- derived values

// ramp / rampDown, the two halves of every fade envelope.
close(R.ramp(0.0, 0.0, 0.14), 0, 'ramp at its start is 0');
close(R.ramp(0.07, 0.0, 0.14), 0.5, 'ramp is linear across its span');
close(R.ramp(0.5, 0.0, 0.14), 1, 'ramp saturates at 1');
close(R.rampDown(0.2, 0.1, 0.3), 0.5, 'rampDown is a ramp the other way');

// The easing must be a real ease, not a straight line, and must pin both ends.
close(R.moveEasing(0), 0, 'easing starts at 0');
close(R.moveEasing(1), 1, 'easing ends at 1');
check(R.moveEasing(0.5) > 0.5, 'easing overshoots the midpoint, as CubicBezier(0.4, 0, 0.2, 1) does');

// A group fade multiplies its members. Built here rather than read off a project on disk:
// whether any given scene still uses a group is an authoring choice that changes whenever
// one is edited, and this is a check on the renderer, not on the authored content.
const grouped = {
  groups: { half: { alpha: { out: [0.38, 0.46] } } },
  elements: [
    { id: 'plain', type: 'pickRing', at: [2, 2], group: 'half' },
    { id: 'fading', type: 'pickRing', at: [3, 2], group: 'half', alpha: { in: [0.0, 0.4] } },
  ],
};
const [plain, fading] = grouped.elements;
close(R.alphaOf(plain, grouped, 0.3), 1, 'a grouped element is opaque before its group fades');
close(R.alphaOf(plain, grouped, 0.42), 0.5, 'a grouped element is half faded midway through');
close(R.alphaOf(plain, grouped, 0.5), 0, 'a grouped element is gone once its group has faded');
// The element's own envelope and its group's multiply, so the lower of the two wins.
close(R.alphaOf(fading, grouped, 0.2), 0.5, 'a member fading in on its own is not helped by its group');
close(R.alphaOf(fading, grouped, 0.5), 0, 'a member cannot outlive its group');

// A car holds each move's end position until the next move starts (HowToPlayVisuals.animCar).
const oil = JSON.parse(readFileSync(join(PROJECTS, 'oil_spill.json'), 'utf-8'));
const oilCar = oil.elements.find((element) => element.type === 'car');
const grid = R.computeGrid(1148, 940, oil.grid);
const onOil = grid.offset(4, 1);
for (const phase of [0.28, 0.35, 0.49]) {
  const car = R.animCar(oilCar, grid, phase);
  close(car.position.x, onOil.x, `oil car waits on the spill at ${phase} (x)`, 0.01);
  close(car.position.y, onOil.y, `oil car waits on the spill at ${phase} (y)`, 0.01);
}
const resting = R.animCar(oilCar, grid, 0);
close(resting.position.x, grid.offset(2, 1).x, 'oil car starts at its rest position');

// The skid wobbles off the straight line between the two points, then settles on it.
const midSkid = R.animCar(oilCar, grid, 0.55);
const straight = (() => {
  const from = grid.offset(4, 1);
  const to = grid.offset(5, 2);
  const t = R.moveEasing(R.ramp(0.55, 0.5, 0.6));
  return { x: from.x + (to.x - from.x) * t, y: from.y + (to.y - from.y) * t };
})();
check(Math.hypot(midSkid.position.x - straight.x, midSkid.position.y - straight.y) > 1,
  'the skidding car leaves the straight line between the two points');
const endSkid = R.animCar(oilCar, grid, 0.6);
close(endSkid.position.x, grid.offset(5, 2).x, 'the skid ends exactly on the target point', 0.01);

// Picker resolution: nine around a coast, minus the ones the hazard takes away.
const withheld = oil.elements.find((element) => element.id === 'pickers_withheld');
const points = R.resolvePickers(withheld);
check(points.length === 6, `oil withholds six of the nine points (got ${points.length})`);
check(!points.some(([x, y]) => x === 6 && y === 1), 'the coast point itself is not withheld');

console.log(failures ? `\n${failures} failure(s)` : '\nall render checks passed');
process.exit(failures ? 1 : 0);
