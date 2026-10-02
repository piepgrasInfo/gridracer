/**
 * The scene renderer: a port of the Compose primitives in
 * `app/src/main/java/info/piepgras/gridracer/ui/howtoplay/HowToPlayVisuals.kt`.
 *
 * The app is the source of truth for how a walkthrough looks; this file exists so the editor
 * can show the same picture without an Android device. Every function here is a transcription
 * of the Kotlin function of the same name, and the two must be changed together — a divergence
 * shows up as an editor preview that lies about what the app will draw.
 *
 * The module is deliberately free of DOM references: it takes a 2D context and a scene, which
 * lets `test/render_smoke.mjs` drive it under Node with a recording stub.
 */

export const PALETTE = {
  track: '#FAFAFA',
  grid: '#BDBDBD',
  wall: '#212121',
  finish: '#6D4C41',
  legal: '#00C853',
  selected: '#2E7D32',
  crash: '#D32F2F',
  oil: '#37474F',
  nails: '#FF6F00',
  car: '#E53935',
  gravel: '#E6DAB8',
  barrier: '#8D6E63',
};

/** `#RRGGBB` (or a palette name) plus an alpha, as a canvas fill/stroke string. */
export function rgba(color, alpha = 1) {
  const hex = (PALETTE[color] || color || '#000000').replace('#', '');
  const full = hex.length === 3 ? hex.split('').map((c) => c + c).join('') : hex;
  const r = parseInt(full.slice(0, 2), 16);
  const g = parseInt(full.slice(2, 4), 16);
  const b = parseInt(full.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${Math.max(0, Math.min(1, alpha))})`;
}

// ----- phase maths (HowToPlayVisuals.kt: ramp, rampDown, AnimMoveEasing) -----

/** 0 until `start`, 1 from `end` on, linear in between. */
export function ramp(phase, start, end) {
  if (phase <= start) return 0;
  if (phase >= end) return 1;
  return (phase - start) / (end - start);
}

/** 1 until `start`, 0 from `end` on. */
export function rampDown(phase, start, end) {
  return 1 - ramp(phase, start, end);
}

/**
 * `CubicBezierEasing(0.4f, 0f, 0.2f, 1f)` — the easing every car movement uses. Solves the
 * curve's x for the given t by bisection, which is plenty at preview resolution.
 */
export function moveEasing(t) {
  if (t <= 0) return 0;
  if (t >= 1) return 1;
  const [x1, y1, x2, y2] = [0.4, 0, 0.2, 1];
  const bezier = (a, b, u) => {
    const inverse = 1 - u;
    return 3 * inverse * inverse * u * a + 3 * inverse * u * u * b + u * u * u;
  };
  let low = 0;
  let high = 1;
  let guess = t;
  for (let i = 0; i < 32; i += 1) {
    guess = (low + high) / 2;
    if (bezier(x1, x2, guess) < t) low = guess;
    else high = guess;
  }
  return bezier(y1, y2, guess);
}

/** The alpha of an element at `phase`, including its group's fade. */
export function alphaOf(element, project, phase) {
  const envelope = (element && element.alpha) || {};
  let alpha = 1;
  if (envelope.in) alpha = Math.min(alpha, ramp(phase, envelope.in[0], envelope.in[1]));
  if (envelope.out) alpha = Math.min(alpha, rampDown(phase, envelope.out[0], envelope.out[1]));
  const groups = (project && project.groups) || {};
  const group = element && element.group ? groups[element.group] : null;
  if (group) alpha *= alphaOf({ alpha: group.alpha }, null, phase);
  return alpha;
}

// ----- grid metrics (HowToPlayVisuals.kt: drawAnimGrid, AnimGrid) -----

/** The cell size and origin a scene's grid gets on a `width` x `height` canvas. */
export function computeGrid(width, height, spec) {
  const cols = Math.max(1, spec.cols | 0);
  const rows = Math.max(1, spec.rows | 0);
  const cellSize =
    spec.cellMode === 'minDimension'
      ? Math.min(width, height) / (spec.cellDivisor || 7)
      : Math.min(width / cols, height / rows) * (spec.cellScale ?? 0.85);
  const spanX = spec.originMode === 'cells' ? cols : cols - 1;
  const spanY = spec.originMode === 'cells' ? rows : rows - 1;
  const originX = (width - cellSize * spanX) / 2;
  const originY = (height - cellSize * spanY) / 2;
  return {
    cols,
    rows,
    cellSize,
    originX,
    originY,
    /** Grid units to canvas pixels. */
    offset: (x, y) => ({ x: originX + x * cellSize, y: originY + y * cellSize }),
    /** Canvas pixels back to grid units — what a drag on the canvas means. */
    toGrid: (px, py) => ({ x: (px - originX) / cellSize, y: (py - originY) / cellSize }),
  };
}

// ----- primitives -----

function circle(ctx, x, y, radius, fill) {
  ctx.beginPath();
  ctx.arc(x, y, Math.max(0, radius), 0, Math.PI * 2);
  ctx.fillStyle = fill;
  ctx.fill();
}

function ringPath(ctx, x, y, radius, stroke, width) {
  ctx.beginPath();
  ctx.arc(x, y, Math.max(0, radius), 0, Math.PI * 2);
  ctx.strokeStyle = stroke;
  ctx.lineWidth = width;
  ctx.stroke();
}

function line(ctx, from, to, stroke, width, dash, cap) {
  ctx.save();
  ctx.beginPath();
  ctx.setLineDash(dash || []);
  ctx.lineCap = cap || 'butt';
  ctx.moveTo(from.x, from.y);
  ctx.lineTo(to.x, to.y);
  ctx.strokeStyle = stroke;
  ctx.lineWidth = width;
  ctx.stroke();
  ctx.restore();
}

function roundedRect(ctx, x, y, w, h, radius, fill) {
  const r = Math.min(radius, Math.abs(w) / 2, Math.abs(h) / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r);
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
  ctx.fillStyle = fill;
  ctx.fill();
}

/** `TrackCanvas.drawCar` — the little top-down race car, rotated to face `direction`. */
export function drawCar(ctx, center, cellSize, color, direction, alpha = 1) {
  if (alpha <= 0) return;
  const dir = direction && (direction[0] !== 0 || direction[1] !== 0) ? direction : [1, 0];
  const angle = Math.atan2(dir[1], dir[0]);
  const bodyLength = cellSize * 0.7;
  const bodyWidth = cellSize * 0.38;
  const halfLength = bodyLength / 2;
  const halfWidth = bodyWidth / 2;

  ctx.save();
  ctx.translate(center.x, center.y);
  ctx.rotate(angle);
  roundedRect(ctx, -halfLength, -halfWidth, bodyLength, bodyWidth, cellSize * 0.08, rgba(color, alpha));
  roundedRect(
    ctx,
    halfLength * 0.5,
    -(bodyWidth * 0.35) / 2,
    bodyLength * 0.25,
    bodyWidth * 0.35,
    cellSize * 0.03,
    `rgba(179, 229, 252, ${0.5 * alpha})`,
  );
  const wheelW = cellSize * 0.1;
  const wheelH = cellSize * 0.14;
  const wheelX = halfLength * 0.7;
  const wheelY = halfWidth * 0.7;
  for (const [wx, wy] of [
    [wheelX, -wheelY],
    [wheelX, wheelY],
    [-wheelX, wheelY],
    [-wheelX, -wheelY],
  ]) {
    roundedRect(ctx, wx - wheelW / 2, wy - wheelH / 2, wheelW, wheelH, cellSize * 0.03, rgba('#1A1A1A', alpha));
  }
  ctx.restore();
}

/** `TrackCanvas.drawWreck` — a burning wreck at a crash site. */
export function drawWreck(ctx, center, cellSize, playerColor) {
  const r = cellSize * 0.35;
  circle(ctx, center.x, center.y, r * 1.1, 'rgba(0, 0, 0, 0.4)');
  const flames = ['rgba(255, 51, 0, 0.8)', 'rgba(255, 136, 0, 0.8)', 'rgba(255, 221, 0, 0.8)'];
  flames.forEach((flame, index) => {
    const h = r * (0.9 - index * 0.2);
    const w = r * (0.6 - index * 0.12);
    ctx.beginPath();
    ctx.moveTo(center.x, center.y - h);
    ctx.bezierCurveTo(
      center.x + w, center.y - h * 0.5,
      center.x + w * 0.6, center.y + h * 0.2,
      center.x, center.y + h * 0.1,
    );
    ctx.bezierCurveTo(
      center.x - w * 0.6, center.y + h * 0.2,
      center.x - w, center.y - h * 0.5,
      center.x, center.y - h,
    );
    ctx.closePath();
    ctx.fillStyle = flame;
    ctx.fill();
  });
  const smoke = r * 0.9;
  circle(ctx, center.x - r * 0.2, center.y - smoke, r * 0.3, 'rgba(66, 66, 66, 0.33)');
  circle(ctx, center.x + r * 0.25, center.y - smoke * 1.3, r * 0.25, 'rgba(66, 66, 66, 0.27)');
  circle(ctx, center.x, center.y - smoke * 1.6, r * 0.2, 'rgba(66, 66, 66, 0.2)');
  circle(ctx, center.x, center.y, r * 0.15, rgba(playerColor, 0.5));
}

function drawGridDots(ctx, grid) {
  for (let gx = 0; gx < grid.cols; gx += 1) {
    for (let gy = 0; gy < grid.rows; gy += 1) {
      const at = grid.offset(gx, gy);
      circle(ctx, at.x, at.y, grid.cellSize * 0.05, rgba('grid'));
    }
  }
}

function drawTrailSegment(ctx, from, to, cellSize, alpha = 1) {
  if (alpha <= 0) return;
  line(ctx, from, to, rgba('car', 0.45 * alpha), cellSize * 0.08);
}

function drawProjector(ctx, from, to, cellSize, alpha, color = 'legal') {
  if (alpha <= 0) return;
  line(
    ctx,
    from,
    to,
    rgba(color, 0.7 * alpha),
    cellSize * 0.05,
    [cellSize * 0.15, cellSize * 0.1],
    'round',
  );
}

function drawOfferedPickers(ctx, points, grid, alpha) {
  if (alpha <= 0) return;
  for (const point of points) {
    const at = grid.offset(point[0], point[1]);
    circle(ctx, at.x, at.y, grid.cellSize * 0.32, rgba('legal', 0.55 * alpha));
  }
}

function drawWithheldPickers(ctx, points, grid, alpha) {
  if (alpha <= 0) return;
  for (const point of points) {
    const at = grid.offset(point[0], point[1]);
    circle(ctx, at.x, at.y, grid.cellSize * 0.14, rgba('legal', 0.2 * alpha));
  }
}

function drawPickRing(ctx, point, grid, alpha) {
  if (alpha <= 0) return;
  const at = grid.offset(point[0], point[1]);
  ringPath(ctx, at.x, at.y, grid.cellSize * 0.32, rgba('selected', alpha), grid.cellSize * 0.06);
}

function drawHazardZone(ctx, corners, color, alpha = 1) {
  if (alpha <= 0 || corners.length < 3) return;
  const mid = (a, b) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
  const n = corners.length;
  ctx.beginPath();
  const start = mid(corners[n - 1], corners[0]);
  ctx.moveTo(start.x, start.y);
  for (let i = 0; i < n; i += 1) {
    const next = mid(corners[i], corners[(i + 1) % n]);
    ctx.quadraticCurveTo(corners[i].x, corners[i].y, next.x, next.y);
  }
  ctx.closePath();
  ctx.fillStyle = rgba(color, 0.45 * alpha);
  ctx.fill();
}

/**
 * Port of `drawAnimGravel`: a band [depth] grid units deep on the right-hand side of the wall
 * from -> to (y down), the barrier on its far edge, and the grid dots inside it drawn again.
 */
function drawGravel(ctx, grid, from, to, depth = 2, alpha = 1) {
  if (alpha <= 0) return;
  const dx = to[0] - from[0];
  const dy = to[1] - from[1];
  const length = Math.hypot(dx, dy);
  if (length === 0) return;
  const nx = -dy / length;
  const ny = dx / length;
  const a = grid.offset(from[0], from[1]);
  const b = grid.offset(to[0], to[1]);
  const farA = grid.offset(from[0] + nx * depth, from[1] + ny * depth);
  const farB = grid.offset(to[0] + nx * depth, to[1] + ny * depth);
  ctx.beginPath();
  ctx.moveTo(a.x, a.y);
  ctx.lineTo(b.x, b.y);
  ctx.lineTo(farB.x, farB.y);
  ctx.lineTo(farA.x, farA.y);
  ctx.closePath();
  ctx.fillStyle = rgba('gravel', alpha);
  ctx.fill();
  line(ctx, farA, farB, rgba('barrier', alpha), grid.cellSize * 0.1);
  for (let gx = 0; gx < grid.cols; gx += 1) {
    for (let gy = 0; gy < grid.rows; gy += 1) {
      const along = ((gx - from[0]) * dx + (gy - from[1]) * dy) / length;
      const across = (gx - from[0]) * nx + (gy - from[1]) * ny;
      if (along >= 0 && along <= length && across >= 0 && across <= depth) {
        const at = grid.offset(gx, gy);
        circle(ctx, at.x, at.y, grid.cellSize * 0.05, rgba('grid', alpha));
      }
    }
  }
}

function drawFinishArrow(ctx, mid, cell, direction = 1, alpha = 1) {
  if (alpha <= 0) return;
  const tipX = mid.x + direction * cell * 0.45;
  const baseX = mid.x - direction * cell * 0.45;
  ctx.beginPath();
  ctx.moveTo(tipX, mid.y);
  ctx.lineTo(baseX, mid.y - cell * 0.35);
  ctx.lineTo(baseX, mid.y + cell * 0.35);
  ctx.closePath();
  ctx.fillStyle = rgba('#FFFFFF', alpha);
  ctx.fill();
  ctx.strokeStyle = rgba('#000000', alpha);
  ctx.lineWidth = cell * 0.03;
  ctx.stroke();
}

function drawStatusFrame(ctx, grid, lapText, crashed, alpha = 1) {
  if (alpha <= 0) return;
  const h = grid.cellSize * 1.1;
  const w = h * 1.414;
  const left = grid.originX - grid.cellSize * 0.5;
  const top = grid.originY - grid.cellSize * 0.5;
  ctx.fillStyle = rgba('car', 0.25 * alpha);
  ctx.fillRect(left, top, w, h);
  ctx.strokeStyle = rgba('car', alpha);
  ctx.lineWidth = h * 0.05;
  ctx.strokeRect(left, top, w, h);

  const carCell = h * 0.72;
  const pad = h * 0.06;
  drawCar(
    ctx,
    { x: left + carCell / 2 + pad, y: top + carCell / 2 + pad - carCell * 0.16 },
    carCell,
    'car',
    [1, 0],
    alpha,
  );
  ctx.save();
  ctx.fillStyle = rgba('#212121', alpha);
  ctx.font = `bold ${h * 0.3}px sans-serif`;
  ctx.textAlign = 'left';
  ctx.textBaseline = 'alphabetic';
  ctx.fillText(lapText, left + w * 0.05, top + h * 0.92);
  ctx.restore();
  if (crashed) {
    const stroke = h * 0.08;
    line(ctx, { x: left, y: top }, { x: left + w, y: top + h }, rgba('crash', alpha), stroke);
    line(ctx, { x: left + w, y: top }, { x: left, y: top + h }, rgba('crash', alpha), stroke);
  }
}

function drawLabel(ctx, grid, element, alpha) {
  if (alpha <= 0 || !element.text) return;
  const at = grid.offset(element.at[0], element.at[1]);
  ctx.save();
  ctx.fillStyle = rgba(element.color || 'wall', alpha);
  ctx.font = `bold ${grid.cellSize * (element.size ?? 0.3)}px sans-serif`;
  ctx.textAlign = element.centered === false ? 'left' : 'center';
  ctx.textBaseline = 'alphabetic';
  ctx.fillText(element.text, at.x, at.y);
  ctx.restore();
}

// ----- cars -----

/** `HowToPlayVisuals.moveLegs` equivalent: each move with its `from` filled in. */
export function moveLegs(car) {
  const legs = [];
  let position = [Number(car.rest[0]), Number(car.rest[1])];
  for (const move of car.moves || []) {
    const to = [Number(move.to[0]), Number(move.to[1])];
    legs.push({ ...move, from: position.slice(), to });
    position = to;
  }
  return legs;
}

/** `HowToPlayVisuals.animCar` — where a car is and which way it points at `phase`. */
export function animCar(car, grid, phase) {
  const legs = moveLegs(car);
  const restHeading = car.restHeading || [1, 0];
  let active = null;
  for (const leg of legs) if (phase >= leg.start) active = leg;
  if (!active) {
    return { position: grid.offset(car.rest[0], car.rest[1]), heading: restHeading, legs };
  }
  const t = ramp(phase, active.start, active.end);
  const from = grid.offset(active.from[0], active.from[1]);
  const to = grid.offset(active.to[0], active.to[1]);
  const eased = moveEasing(t);
  let position = { x: from.x + (to.x - from.x) * eased, y: from.y + (to.y - from.y) * eased };
  const shimmy = Number(active.shimmy || 0);
  if (shimmy > 0) {
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const length = Math.hypot(dx, dy);
    if (length > 0) {
      const amount =
        Math.sin(t * Math.PI * Number(active.shimmyCycles ?? 3)) * (1 - t) * grid.cellSize * shimmy;
      position = { x: position.x - (dy / length) * amount, y: position.y + (dx / length) * amount };
    }
  }
  return { position, heading: active.heading || restHeading, legs };
}

/** The grid points a `pickers` element draws — mirrors `scene.resolve_pickers`. */
export function resolvePickers(element) {
  const points = [];
  if (element.around) {
    const [cx, cy] = element.around;
    for (const dx of [-1, 0, 1]) for (const dy of [-1, 0, 1]) points.push([cx + dx, cy + dy]);
  }
  for (const point of element.points || []) points.push([Number(point[0]), Number(point[1])]);
  const excluded = new Set((element.exclude || []).map((p) => `${Number(p[0])},${Number(p[1])}`));
  const seen = new Set();
  const result = [];
  for (const point of points) {
    const key = `${point[0]},${point[1]}`;
    if (excluded.has(key) || seen.has(key)) continue;
    seen.add(key);
    result.push(point);
  }
  return result;
}

// ----- the scene -----

/**
 * Draws one project at one phase, exactly as the generated Compose animation would.
 *
 * Returns the grid and the resolved car states, which the editor uses for hit-testing and for
 * the drag handles — so what you can grab is always what was actually drawn.
 */
export function drawScene(ctx, width, height, project, phase) {
  const grid = computeGrid(width, height, project.grid || {});
  ctx.save();
  ctx.fillStyle = rgba('track');
  ctx.fillRect(0, 0, width, height);
  drawGridDots(ctx, grid);

  const cars = {};
  for (const element of project.elements || []) {
    if (element.type === 'car') cars[element.id] = animCar(element, grid, phase);
  }

  for (const element of project.elements || []) {
    const alpha = alphaOf(element, project, phase);
    switch (element.type) {
      case 'wall': {
        if (alpha <= 0) break;
        line(
          ctx,
          grid.offset(element.from[0], element.from[1]),
          grid.offset(element.to[0], element.to[1]),
          rgba('wall', alpha),
          grid.cellSize * (element.width ?? 0.15),
        );
        break;
      }
      case 'finishLine': {
        if (alpha <= 0) break;
        line(
          ctx,
          grid.offset(element.x, element.y0),
          grid.offset(element.x, element.y1),
          rgba('finish', alpha),
          grid.cellSize * (element.width ?? 0.12),
        );
        if (element.arrow) {
          drawFinishArrow(
            ctx,
            grid.offset(element.x, element.arrow.y ?? 0),
            grid.cellSize,
            element.arrow.direction ?? 1,
            alpha,
          );
        }
        break;
      }
      case 'gravel':
        drawGravel(ctx, grid, element.from, element.to, element.depth ?? 2, alpha);
        break;
      case 'hazard':
        drawHazardZone(
          ctx,
          (element.corners || []).map((c) => grid.offset(c[0], c[1])),
          element.color || 'oil',
          alpha,
        );
        break;
      case 'trail': {
        if ((element.kind || 'static') === 'static') {
          drawTrailSegment(
            ctx,
            grid.offset(element.from[0], element.from[1]),
            grid.offset(element.to[0], element.to[1]),
            grid.cellSize,
            alpha,
          );
          break;
        }
        const car = cars[element.car];
        if (!car) break;
        const leg = car.legs[element.move || 0];
        if (!leg || phase < leg.start) break;
        const from = grid.offset(leg.from[0], leg.from[1]);
        const to = phase <= leg.end ? car.position : grid.offset(leg.to[0], leg.to[1]);
        drawTrailSegment(ctx, from, to, grid.cellSize, alpha);
        break;
      }
      case 'projector':
        drawProjector(
          ctx,
          grid.offset(element.from[0], element.from[1]),
          grid.offset(element.to[0], element.to[1]),
          grid.cellSize,
          alpha,
          element.color === 'crash' ? 'crash' : 'legal',
        );
        break;
      case 'pickers': {
        const points = resolvePickers(element);
        if ((element.style || 'offered') === 'offered') drawOfferedPickers(ctx, points, grid, alpha);
        else drawWithheldPickers(ctx, points, grid, alpha);
        break;
      }
      case 'pickRing':
        drawPickRing(ctx, element.at, grid, alpha);
        break;
      case 'car': {
        const car = cars[element.id];
        if (car) drawCar(ctx, car.position, grid.cellSize * 0.9, 'car', car.heading, alpha);
        break;
      }
      case 'wreck':
        if (phase >= (element.showFrom ?? 0)) {
          drawWreck(ctx, grid.offset(element.at[0], element.at[1]), grid.cellSize, 'car');
        }
        break;
      case 'statusFrame': {
        let lap = element.lap || '';
        const rule = element.switch;
        if (rule && rule.kind === 'carPastX' && cars[rule.car]) {
          if (cars[rule.car].position.x >= grid.offset(rule.x, 0).x) lap = element.lapAfter || lap;
        } else if (rule && rule.kind === 'phase' && phase >= rule.at) {
          lap = element.lapAfter || lap;
        }
        const crashed = element.crashedFrom != null && phase >= element.crashedFrom;
        drawStatusFrame(ctx, grid, lap, crashed, alpha);
        break;
      }
      case 'label':
        drawLabel(ctx, grid, element, alpha);
        break;
      default:
        break;
    }
  }
  ctx.restore();
  return { grid, cars };
}

// ----- editing support -----

/**
 * The draggable points of one element, in grid units.
 *
 * `snap` says what the point may land on: 1 for anything a car can occupy or a picker can mark
 * (the engine only ever deals in whole grid points), a finer step for decoration such as wall
 * ends and hazard corners.
 */
export function anchorsOf(element) {
  const anchors = [];
  const push = (path, value, snap, label) =>
    anchors.push({ path, x: Number(value[0]), y: Number(value[1]), snap, label });
  switch (element.type) {
    case 'wall':
      push('from', element.from, 0.05, 'start');
      push('to', element.to, 0.05, 'end');
      break;
    case 'finishLine':
      anchors.push({ path: 'lineTop', x: element.x, y: element.y0, snap: 0.05, label: 'top' });
      anchors.push({ path: 'lineBottom', x: element.x, y: element.y1, snap: 0.05, label: 'bottom' });
      if (element.arrow) {
        anchors.push({ path: 'arrow', x: element.x, y: element.arrow.y ?? 0, snap: 0.05, label: 'arrow' });
      }
      break;
    case 'gravel':
      push('from', element.from, 0.05, 'start');
      push('to', element.to, 0.05, 'end');
      break;
    case 'hazard':
      (element.corners || []).forEach((corner, index) =>
        push(`corners.${index}`, corner, 0.05, `corner ${index + 1}`),
      );
      break;
    case 'trail':
      if ((element.kind || 'static') === 'static') {
        push('from', element.from, 1, 'start');
        push('to', element.to, 1, 'end');
      }
      break;
    case 'projector':
      push('from', element.from, 1, 'start');
      push('to', element.to, 1, 'coast');
      break;
    case 'pickers':
      if (element.around) push('around', element.around, 1, 'coast');
      (element.points || []).forEach((point, index) => push(`points.${index}`, point, 1, `point ${index + 1}`));
      break;
    case 'pickRing':
      push('at', element.at, 1, 'point');
      break;
    case 'car':
      push('rest', element.rest, 1, 'start');
      (element.moves || []).forEach((move, index) => push(`moves.${index}.to`, move.to, 1, `move ${index + 1}`));
      break;
    case 'wreck':
      push('at', element.at, 0.05, 'site');
      break;
    case 'label':
      push('at', element.at, 0.05, 'anchor');
      break;
    default:
      break;
  }
  return anchors;
}

/** Writes a dragged anchor back into the element. Mirrors the paths `anchorsOf` hands out. */
export function moveAnchor(element, path, x, y) {
  if (path === 'lineTop') {
    element.x = x;
    element.y0 = y;
    return;
  }
  if (path === 'lineBottom') {
    element.x = x;
    element.y1 = y;
    return;
  }
  if (path === 'arrow') {
    element.x = x;
    element.arrow = { ...(element.arrow || {}), y };
    return;
  }
  const parts = path.split('.');
  let target = element;
  for (let i = 0; i < parts.length - 1; i += 1) target = target[parts[i]];
  target[parts[parts.length - 1]] = [x, y];
}
