/**
 * The tutorial editor's UI: project loading, the canvas preview with drag handles, the frame
 * and element lists, the per-element inspector, and export.
 *
 * The whole app is one mutable `state.project` object — the same shape as the JSON on disk —
 * plus a `render()` that redraws every panel from it. There is no framework and no virtual DOM:
 * the scene is small (tens of elements), so rebuilding the lists on every change is cheaper
 * than keeping them in sync by hand, and it makes "what you see is the file" literally true.
 */

import * as R from './render.js';

const $ = (id) => document.getElementById(id);

/** Canvas shapes worth previewing. The app forces landscape and splits the row in half. */
const ASPECTS = [
  { id: 'phone', label: 'Phone landscape (as in app)', width: 1148, height: 940 },
  { id: 'wide', label: 'Wide phone (20:9 device)', width: 1250, height: 900 },
  { id: 'tablet', label: 'Tablet (4:3 device)', width: 940, height: 1000 },
  { id: 'square', label: 'Square', width: 1000, height: 1000 },
];

/** How long the app takes to tween from one frame to the next (HowToPlayScreen: tween(600)). */
const FRAME_TWEEN_MS = 600;

const state = {
  meta: null,
  projectId: null,
  project: null,
  saved: null, // the JSON text last written to disk, for the dirty flag
  selectedId: null,
  frameIndex: 0,
  phase: 0,
  aspect: ASPECTS[0],
  showHandles: true,
  easeBetweenFrames: true,
  playing: false,
  animation: null, // { from, to, startedAt, durationMs, loop }
  history: [],
  drag: null,
};

// ---------------------------------------------------------------- utilities

const clone = (value) => JSON.parse(JSON.stringify(value));
const round = (value, step) => Math.round(value / step) * step;
const fmt = (value, digits = 3) => Number(value).toFixed(digits);

function setStatus(message, kind = '') {
  const node = $('status');
  node.textContent = message;
  node.className = kind;
}

function markDirty() {
  const dirty = JSON.stringify(state.project) !== state.saved;
  $('dirty').classList.toggle('hidden', !dirty);
}

/** Snapshots the project so Ctrl+Z can put it back. Call *before* mutating. */
function pushHistory() {
  state.history.push(JSON.stringify(state.project));
  if (state.history.length > 100) state.history.shift();
}

function undo() {
  const previous = state.history.pop();
  if (!previous) return;
  state.project = JSON.parse(previous);
  render();
  setStatus('undo');
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || `${response.status} ${response.statusText}`);
  return payload;
}

const elementById = (id) => (state.project.elements || []).find((element) => element.id === id);
const selectedElement = () => elementById(state.selectedId);

/** A unique element id built from a base name, so "car", "car_2", "car_3". */
function freeId(base) {
  const taken = new Set((state.project.elements || []).map((element) => element.id));
  if (!taken.has(base)) return base;
  for (let n = 2; ; n += 1) if (!taken.has(`${base}_${n}`)) return `${base}_${n}`;
}

// ---------------------------------------------------------------- loading

/** The payload the server inlines into index.html, or null when the page came from a file. */
function bootstrap() {
  try {
    return JSON.parse(document.getElementById('bootstrap')?.textContent || 'null');
  } catch {
    return null;
  }
}

async function boot() {
  const inlined = bootstrap();
  state.meta = inlined?.meta ?? (await api('/api/meta'));
  const select = $('aspect-select');
  ASPECTS.forEach((aspect) => select.append(new Option(aspect.label, aspect.id)));
  const typeSelect = $('add-element-type');
  state.meta.elementTypes.forEach((type) => typeSelect.append(new Option(type, type)));
  typeSelect.onchange = () => {
    $('element-help').textContent = state.meta.elementHelp[typeSelect.value] || '';
  };
  typeSelect.onchange();

  fillProjectList(inlined?.projects ?? (await api('/api/projects')));
  if (inlined?.project) showProject(inlined.project, inlined.project.id);
  else if ($('project-select').value) await loadProject($('project-select').value);
  wireControls();
  requestAnimationFrame(tick);
}

function fillProjectList(projects) {
  const select = $('project-select');
  const current = state.projectId;
  select.replaceChildren();
  projects.forEach((project) => select.append(new Option(`${project.name} (${project.id})`, project.id)));
  if (current && projects.some((project) => project.id === current)) select.value = current;
}

async function refreshProjectList() {
  fillProjectList(await api('/api/projects'));
}

async function loadProject(id) {
  showProject(await api(`/api/projects/${id}`), id);
}

/** Makes a freshly-read project the one being edited, and draws everything from it. */
function showProject(project, id) {
  state.project = project;
  state.projectId = id;
  state.saved = JSON.stringify(state.project);
  state.history = [];
  state.selectedId = (state.project.elements || [])[0]?.id ?? null;
  state.frameIndex = 0;
  state.phase = state.project.frames?.[0]?.phase ?? 0;
  state.animation = null;
  $('project-select').value = id;
  render();
  setStatus(`loaded ${id}`, 'ok');
}

// ---------------------------------------------------------------- transport

/** Starts an eased move of the preview phase, the way the app tweens between frames. */
function animateTo(phase, durationMs = FRAME_TWEEN_MS) {
  if (!state.easeBetweenFrames || durationMs <= 0) {
    state.phase = phase;
    state.animation = null;
    render();
    return;
  }
  state.animation = { from: state.phase, to: phase, startedAt: performance.now(), durationMs };
}

function goToFrame(index) {
  const frames = state.project.frames || [];
  if (!frames.length) return;
  state.frameIndex = Math.max(0, Math.min(frames.length - 1, index));
  state.playing = false;
  $('btn-play').textContent = '▶ Play';
  animateTo(frames[state.frameIndex].phase);
  renderFrames();
}

function tick(now) {
  const animation = state.animation;
  if (animation) {
    const t = Math.min(1, (now - animation.startedAt) / animation.durationMs);
    state.phase = animation.from + (animation.to - animation.from) * t; // linear, like tween(600, Linear)
    if (t >= 1) {
      state.phase = animation.to;
      state.animation = null;
      if (animation.loop && state.playing) {
        animation.from = 0;
        animation.to = 1;
        animation.startedAt = now;
        state.animation = animation;
      }
    }
    drawPreview();
    renderPhaseReadout();
  }
  requestAnimationFrame(tick);
}

function togglePlay() {
  state.playing = !state.playing;
  $('btn-play').textContent = state.playing ? '❚❚ Pause' : '▶ Play';
  if (state.playing) {
    state.animation = { from: 0, to: 1, startedAt: performance.now(), durationMs: 5000, loop: true };
    state.phase = 0;
  } else {
    state.animation = null;
  }
}

// ---------------------------------------------------------------- canvas

function drawPreview() {
  const canvas = $('preview');
  canvas.width = state.aspect.width;
  canvas.height = state.aspect.height;
  const ctx = canvas.getContext('2d');
  const { grid, cars } = R.drawScene(ctx, canvas.width, canvas.height, state.project, state.phase);
  state.grid = grid;
  state.cars = cars;
  if (state.showHandles) drawHandles(ctx, grid);
  $('canvas-hint').textContent =
    `${state.aspect.width}x${state.aspect.height} · ${state.project.grid.cols}x${state.project.grid.rows} grid` +
    ` · drag a handle to move it · Shift = free, Alt = whole points`;
}

/** The draggable anchors of the selected element, drawn on top of the finished scene. */
function drawHandles(ctx, grid) {
  const element = selectedElement();
  if (!element) return;
  const anchors = R.anchorsOf(element);
  ctx.save();
  for (const anchor of anchors) {
    const at = grid.offset(anchor.x, anchor.y);
    ctx.beginPath();
    ctx.arc(at.x, at.y, grid.cellSize * 0.17, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255, 255, 255, 0.85)';
    ctx.fill();
    ctx.lineWidth = grid.cellSize * 0.045;
    ctx.strokeStyle = '#1565c0';
    ctx.stroke();
  }
  if (anchors.length > 1) {
    ctx.beginPath();
    anchors.forEach((anchor, index) => {
      const at = grid.offset(anchor.x, anchor.y);
      if (index === 0) ctx.moveTo(at.x, at.y);
      else ctx.lineTo(at.x, at.y);
    });
    ctx.setLineDash([grid.cellSize * 0.12, grid.cellSize * 0.12]);
    ctx.strokeStyle = 'rgba(21, 101, 192, 0.55)';
    ctx.lineWidth = grid.cellSize * 0.02;
    ctx.stroke();
  }
  ctx.restore();
}

/** Canvas pixel position of a pointer event, in the canvas's own coordinate space. */
function pointerAt(event) {
  const canvas = $('preview');
  const rect = canvas.getBoundingClientRect();
  return {
    x: ((event.clientX - rect.left) / rect.width) * canvas.width,
    y: ((event.clientY - rect.top) / rect.height) * canvas.height,
  };
}

function onCanvasDown(event) {
  const at = pointerAt(event);
  const grid = state.grid;
  if (!grid) return;

  // An anchor of the selected element wins, so a handle stays grabbable where elements overlap.
  const element = selectedElement();
  if (element) {
    for (const anchor of R.anchorsOf(element)) {
      const point = grid.offset(anchor.x, anchor.y);
      if (Math.hypot(point.x - at.x, point.y - at.y) <= grid.cellSize * 0.28) {
        pushHistory();
        state.drag = { elementId: element.id, path: anchor.path, snap: anchor.snap };
        $('preview').setPointerCapture(event.pointerId);
        return;
      }
    }
  }

  // Otherwise select whatever element has an anchor near the click.
  for (const candidate of [...(state.project.elements || [])].reverse()) {
    for (const anchor of R.anchorsOf(candidate)) {
      const point = grid.offset(anchor.x, anchor.y);
      if (Math.hypot(point.x - at.x, point.y - at.y) <= grid.cellSize * 0.3) {
        select(candidate.id);
        return;
      }
    }
  }
}

function onCanvasMove(event) {
  if (!state.drag || !state.grid) return;
  const at = pointerAt(event);
  const inGrid = state.grid.toGrid(at.x, at.y);
  let snap = state.drag.snap;
  if (event.shiftKey) snap = 0; // free placement
  if (event.altKey) snap = 1; // force a whole grid point
  const x = snap ? round(inGrid.x, snap) : Number(inGrid.x.toFixed(3));
  const y = snap ? round(inGrid.y, snap) : Number(inGrid.y.toFixed(3));
  const element = elementById(state.drag.elementId);
  if (!element) return;
  R.moveAnchor(element, state.drag.path, x, y);
  drawPreview();
  markDirty();
}

function onCanvasUp() {
  if (!state.drag) return;
  state.drag = null;
  render();
}

// ---------------------------------------------------------------- lists

function renderFrames() {
  const list = $('frame-list');
  list.replaceChildren();
  (state.project.frames || []).forEach((frame, index) => {
    const item = document.createElement('li');
    item.className = index === state.frameIndex ? 'active' : '';
    item.onclick = () => goToFrame(index);

    const phase = document.createElement('span');
    phase.className = 'phase';
    phase.textContent = fmt(frame.phase, 2);
    const caption = document.createElement('span');
    caption.className = 'caption';
    caption.textContent = frame.caption || '(no caption)';
    const remove = document.createElement('button');
    remove.className = 'tiny';
    remove.textContent = '✕';
    remove.title = 'Delete this frame';
    remove.onclick = (event) => {
      event.stopPropagation();
      pushHistory();
      state.project.frames.splice(index, 1);
      state.frameIndex = Math.max(0, state.frameIndex - 1);
      render();
    };
    item.append(phase, caption, remove);
    list.append(item);
  });
}

function renderElements() {
  const list = $('element-list');
  list.replaceChildren();
  (state.project.elements || []).forEach((element, index) => {
    const item = document.createElement('li');
    item.className = element.id === state.selectedId ? 'active' : '';
    item.onclick = () => select(element.id);

    const visible = document.createElement('span');
    const alpha = R.alphaOf(element, state.project, state.phase);
    visible.className = alpha > 0.01 ? 'vis' : 'vis off';
    visible.title = `alpha ${fmt(alpha, 2)} at phase ${fmt(state.phase, 2)}`;

    const kind = document.createElement('span');
    kind.className = 'kind';
    kind.textContent = element.type;
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = element.id;

    const up = document.createElement('button');
    up.className = 'tiny';
    up.textContent = '↑';
    up.title = 'Draw earlier (further back)';
    up.onclick = (event) => {
      event.stopPropagation();
      moveElement(index, -1);
    };
    const down = document.createElement('button');
    down.className = 'tiny';
    down.textContent = '↓';
    down.title = 'Draw later (further forward)';
    down.onclick = (event) => {
      event.stopPropagation();
      moveElement(index, 1);
    };

    item.append(visible, kind, name, up, down);
    list.append(item);
  });
}

function moveElement(index, delta) {
  const elements = state.project.elements;
  const target = index + delta;
  if (target < 0 || target >= elements.length) return;
  pushHistory();
  const [element] = elements.splice(index, 1);
  elements.splice(target, 0, element);
  render();
}

function select(id) {
  state.selectedId = id;
  showTab('inspector');
  render();
}

// ---------------------------------------------------------------- form widgets

function field(label, control) {
  const wrapper = document.createElement('label');
  wrapper.className = 'field';
  const caption = document.createElement('span');
  caption.textContent = label;
  wrapper.append(caption, control);
  return wrapper;
}

function numberInput(value, onChange, step = 0.01) {
  const input = document.createElement('input');
  input.type = 'number';
  input.step = String(step);
  input.value = value ?? 0;
  input.onchange = () => {
    pushHistory();
    onChange(Number(input.value));
    render();
  };
  return input;
}

function textInput(value, onChange, placeholder = '') {
  const input = document.createElement('input');
  input.type = 'text';
  input.value = value ?? '';
  input.placeholder = placeholder;
  input.onchange = () => {
    pushHistory();
    onChange(input.value);
    render();
  };
  return input;
}

function textArea(value, onChange, rows = 4) {
  const area = document.createElement('textarea');
  area.rows = rows;
  area.style.minHeight = 'auto';
  area.value = value ?? '';
  area.onchange = () => {
    pushHistory();
    onChange(area.value);
    render();
  };
  return area;
}

function selectInput(value, options, onChange) {
  const select = document.createElement('select');
  options.forEach((option) => {
    const label = typeof option === 'string' ? option : option.label;
    const key = typeof option === 'string' ? option : option.value;
    select.append(new Option(label, key));
  });
  select.value = value;
  select.onchange = () => {
    pushHistory();
    onChange(select.value);
    render();
  };
  return select;
}

function checkboxInput(value, onChange) {
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.checked = !!value;
  input.onchange = () => {
    pushHistory();
    onChange(input.checked);
    render();
  };
  return input;
}

/** An x/y pair, the thing most scene fields are. */
function pointRow(label, point, onChange, step = 0.05) {
  const row = document.createElement('div');
  row.className = 'point-row';
  const caption = document.createElement('span');
  caption.className = 'label';
  caption.textContent = label;
  row.append(
    caption,
    numberInput(point?.[0], (x) => onChange([x, Number(point?.[1] ?? 0)]), step),
    numberInput(point?.[1], (y) => onChange([Number(point?.[0] ?? 0), y]), step),
  );
  return row;
}

/**
 * The fade editor: two optional spans, plus a little plot of the resulting alpha across the
 * whole animation with the frames ticked on it, so it is obvious which frames actually see
 * the element.
 */
function envelopeEditor(owner, title = 'Visibility') {
  const box = document.createElement('div');
  box.className = 'envelope';
  const heading = document.createElement('h4');
  heading.textContent = title;
  box.append(heading);

  const alpha = owner.alpha || {};
  const spanRow = (key, label) => {
    const row = document.createElement('div');
    row.className = 'row';
    const toggle = checkboxInput(!!alpha[key], (on) => {
      owner.alpha = owner.alpha || {};
      if (on) owner.alpha[key] = key === 'in' ? [0, 0.1] : [0.9, 1];
      else delete owner.alpha[key];
      if (!Object.keys(owner.alpha).length) delete owner.alpha;
    });
    const caption = document.createElement('span');
    caption.className = 'label';
    caption.textContent = label;
    row.append(toggle, caption);
    if (alpha[key]) {
      row.append(
        numberInput(alpha[key][0], (v) => {
          owner.alpha[key][0] = v;
        }),
        numberInput(alpha[key][1], (v) => {
          owner.alpha[key][1] = v;
        }),
      );
    }
    return row;
  };
  box.append(spanRow('in', 'fade in'), spanRow('out', 'fade out'));

  const plot = document.createElement('canvas');
  plot.width = 280;
  plot.height = 40;
  box.append(plot);
  drawEnvelopePlot(plot, owner);
  return box;
}

function drawEnvelopePlot(canvas, owner) {
  const ctx = canvas.getContext('2d');
  const { width, height } = canvas;
  ctx.fillStyle = '#12141a';
  ctx.fillRect(0, 0, width, height);

  // frame ticks
  ctx.strokeStyle = '#3a4049';
  ctx.lineWidth = 1;
  for (const frame of state.project.frames || []) {
    const x = frame.phase * width;
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, height);
    ctx.stroke();
  }

  ctx.beginPath();
  for (let px = 0; px <= width; px += 1) {
    const phase = px / width;
    const alpha = R.alphaOf({ alpha: owner.alpha, group: owner.group }, state.project, phase);
    const y = height - alpha * (height - 4) - 2;
    if (px === 0) ctx.moveTo(px, y);
    else ctx.lineTo(px, y);
  }
  ctx.strokeStyle = '#00c853';
  ctx.lineWidth = 1.5;
  ctx.stroke();

  // current phase
  ctx.strokeStyle = '#e53935';
  ctx.beginPath();
  ctx.moveTo(state.phase * width, 0);
  ctx.lineTo(state.phase * width, height);
  ctx.stroke();
}

// ---------------------------------------------------------------- inspector

function renderInspector() {
  const host = $('inspector');
  host.replaceChildren();
  const element = selectedElement();
  if (!element) {
    host.append(Object.assign(document.createElement('p'), {
      className: 'help',
      textContent: 'Select an element on the left, or click one of its handles on the canvas.',
    }));
    return;
  }

  host.append(field('Id (also its Kotlin variable name)', textInput(element.id, (value) => {
    const old = element.id;
    element.id = value;
    for (const other of state.project.elements) {
      if (other.type === 'trail' && other.car === old) other.car = value;
      if (other.type === 'statusFrame' && other.switch?.car === old) other.switch.car = value;
    }
    state.selectedId = value;
  })));

  const typeRow = document.createElement('p');
  typeRow.className = 'help';
  typeRow.textContent = state.meta.elementHelp[element.type] || '';
  host.append(field('Type', selectInput(element.type, state.meta.elementTypes, (value) => {
    Object.keys(element).forEach((key) => {
      if (key !== 'id' && key !== 'alpha' && key !== 'group') delete element[key];
    });
    Object.assign(element, defaultsFor(value, element.id));
  })), typeRow);

  const groups = Object.keys(state.project.groups || {});
  if (groups.length) {
    host.append(field('Group', selectInput(element.group || '', ['', ...groups], (value) => {
      if (value) element.group = value;
      else delete element.group;
    })));
  }

  host.append(...inspectorFields(element));
  if (element.type !== 'wreck') host.append(envelopeEditor(element));

  const actions = document.createElement('div');
  actions.className = 'row';
  const duplicate = document.createElement('button');
  duplicate.textContent = 'Duplicate';
  duplicate.onclick = () => {
    pushHistory();
    const copy = clone(element);
    copy.id = freeId(`${element.id}_copy`);
    state.project.elements.splice(state.project.elements.indexOf(element) + 1, 0, copy);
    state.selectedId = copy.id;
    render();
  };
  const remove = document.createElement('button');
  remove.className = 'danger';
  remove.textContent = 'Delete element';
  remove.onclick = () => {
    pushHistory();
    state.project.elements.splice(state.project.elements.indexOf(element), 1);
    state.selectedId = state.project.elements[0]?.id ?? null;
    render();
  };
  actions.append(duplicate, remove);
  host.append(actions);
}

/** The type-specific half of the inspector. */
function inspectorFields(element) {
  const fields = [];
  const colorNames = Object.keys(state.meta.colors);

  const pointList = (label, key, step, addDefault) => {
    const box = document.createElement('div');
    box.className = 'points';
    (element[key] || []).forEach((point, index) => {
      const row = pointRow(`${index + 1}`, point, (value) => {
        element[key][index] = value;
      }, step);
      const remove = document.createElement('button');
      remove.className = 'tiny';
      remove.textContent = '✕';
      remove.onclick = () => {
        pushHistory();
        element[key].splice(index, 1);
        render();
      };
      row.append(remove);
      box.append(row);
    });
    const add = document.createElement('button');
    add.className = 'tiny';
    add.textContent = '+ point';
    add.onclick = () => {
      pushHistory();
      element[key] = element[key] || [];
      element[key].push(addDefault());
      render();
    };
    box.append(add);
    fields.push(field(label, box));
  };

  switch (element.type) {
    case 'wall':
      fields.push(field('From / to (grid units)', wrap(
        pointRow('from', element.from, (v) => { element.from = v; }),
        pointRow('to', element.to, (v) => { element.to = v; }),
      )));
      fields.push(field('Thickness (cells)', numberInput(element.width ?? 0.15, (v) => { element.width = v; })));
      break;

    case 'finishLine':
      fields.push(field('Column x', numberInput(element.x, (v) => { element.x = v; })));
      fields.push(field('From row / to row', wrap(
        numberInput(element.y0, (v) => { element.y0 = v; }),
        numberInput(element.y1, (v) => { element.y1 = v; }),
      )));
      fields.push(field('Thickness (cells)', numberInput(element.width ?? 0.12, (v) => { element.width = v; })));
      fields.push(field('Arrow', checkboxInput(!!element.arrow, (on) => {
        if (on) element.arrow = { y: 2, direction: 1 };
        else delete element.arrow;
      })));
      if (element.arrow) {
        fields.push(field('Arrow row', numberInput(element.arrow.y, (v) => { element.arrow.y = v; })));
        fields.push(field('Arrow direction', selectInput(String(element.arrow.direction ?? 1), [
          { value: '1', label: '+x (rightwards)' },
          { value: '-1', label: '-x (leftwards)' },
        ], (v) => { element.arrow.direction = Number(v); })));
      }
      break;

    case 'gravel':
      fields.push(field('Wall from / to (gravel on the right)', wrap(
        pointRow('from', element.from, (v) => { element.from = v; }, 0.05),
        pointRow('to', element.to, (v) => { element.to = v; }, 0.05),
      )));
      fields.push(field('Depth (cells)', numberInput(element.depth ?? 2, (v) => { element.depth = v; })));
      break;

    case 'hazard':
      fields.push(field('Colour', selectInput(element.color || 'oil', colorNames, (v) => { element.color = v; })));
      pointList('Corners (a smooth blob through them)', 'corners', 0.05, () => [
        Number(((state.project.grid.cols - 1) / 2).toFixed(2)),
        Number(((state.project.grid.rows - 1) / 2).toFixed(2)),
      ]);
      break;

    case 'trail':
      fields.push(field('Kind', selectInput(element.kind || 'static', state.meta.trailKinds, (v) => {
        element.kind = v;
        if (v === 'follow') {
          delete element.from;
          delete element.to;
          element.car = carIds()[0] || '';
          element.move = 0;
        } else {
          delete element.car;
          delete element.move;
          element.from = [0, 0];
          element.to = [1, 0];
        }
      })));
      if ((element.kind || 'static') === 'static') {
        fields.push(field('From / to', wrap(
          pointRow('from', element.from, (v) => { element.from = v; }, 1),
          pointRow('to', element.to, (v) => { element.to = v; }, 1),
        )));
      } else {
        fields.push(field('Car', selectInput(element.car || '', carIds(), (v) => { element.car = v; })));
        const car = elementById(element.car);
        const moveCount = (car?.moves || []).length;
        fields.push(field('Move (leg of that car)', selectInput(String(element.move ?? 0),
          Array.from({ length: Math.max(1, moveCount) }, (_, i) => ({ value: String(i), label: `move ${i + 1}` })),
          (v) => { element.move = Number(v); })));
      }
      break;

    case 'projector':
      fields.push(field('From (the car) / to (the coast point)', wrap(
        pointRow('from', element.from, (v) => { element.from = v; }, 1),
        pointRow('coast', element.to, (v) => { element.to = v; }, 1),
      )));
      fields.push(field('Colour', selectInput(element.color || 'legal', [
        { value: 'legal', label: 'green (an ordinary coast)' },
        { value: 'crash', label: 'red (a coast into trouble)' },
      ], (v) => { element.color = v; })));
      break;

    case 'pickers': {
      fields.push(field('Style', selectInput(element.style || 'offered', [
        { value: 'offered', label: 'offered — the green points you may pick' },
        { value: 'withheld', label: 'withheld — the small dim points' },
      ], (v) => { element.style = v; })));
      fields.push(field('Nine around a coast point', checkboxInput(!!element.around, (on) => {
        if (on) element.around = [Math.round((state.project.grid.cols - 1) / 2), Math.round((state.project.grid.rows - 1) / 2)];
        else delete element.around;
      })));
      if (element.around) {
        fields.push(field('Coast point', pointRow('coast', element.around, (v) => { element.around = v; }, 1)));
      }
      pointList('Extra points', 'points', 1, () => [
        Math.round((state.project.grid.cols - 1) / 2),
        Math.round((state.project.grid.rows - 1) / 2),
      ]);
      pointList('Excluded points', 'exclude', 1, () => [
        Math.round((state.project.grid.cols - 1) / 2),
        Math.round((state.project.grid.rows - 1) / 2),
      ]);
      const resolved = document.createElement('p');
      resolved.className = 'help';
      resolved.textContent = `Draws ${R.resolvePickers(element).length} point(s).`;
      fields.push(resolved);
      break;
    }

    case 'pickRing':
      fields.push(field('Point', pointRow('at', element.at, (v) => { element.at = v; }, 1)));
      break;

    case 'car': {
      fields.push(field('Rest position', pointRow('rest', element.rest, (v) => { element.rest = v; }, 1)));
      fields.push(field('Heading before it moves', pointRow('dx, dy', element.restHeading || [1, 0], (v) => {
        element.restHeading = v;
      }, 1)));
      const moves = document.createElement('div');
      moves.className = 'points';
      (element.moves || []).forEach((move, index) => {
        const box = document.createElement('div');
        box.className = 'envelope';
        const heading = document.createElement('h4');
        heading.textContent = `Move ${index + 1}`;
        box.append(heading);
        box.append(pointRow('to', move.to, (v) => { move.to = v; }, 1));
        box.append(pointRow('heading', move.heading || [1, 0], (v) => { move.heading = v; }, 1));
        const timing = document.createElement('div');
        timing.className = 'point-row';
        const timingLabel = document.createElement('span');
        timingLabel.className = 'label';
        timingLabel.textContent = 'start/end';
        timing.append(
          timingLabel,
          numberInput(move.start, (v) => { move.start = v; }),
          numberInput(move.end, (v) => { move.end = v; }),
        );
        box.append(timing);
        const skid = document.createElement('div');
        skid.className = 'point-row';
        const skidLabel = document.createElement('span');
        skidLabel.className = 'label';
        skidLabel.textContent = 'skid';
        skid.append(
          skidLabel,
          numberInput(move.shimmy ?? 0, (v) => {
            if (v > 0) move.shimmy = v;
            else delete move.shimmy;
          }),
          numberInput(move.shimmyCycles ?? 3, (v) => { move.shimmyCycles = v; }, 0.5),
        );
        box.append(skid);
        const remove = document.createElement('button');
        remove.className = 'tiny danger';
        remove.textContent = 'remove move';
        remove.onclick = () => {
          pushHistory();
          element.moves.splice(index, 1);
          render();
        };
        box.append(remove);
        moves.append(box);
      });
      const addMove = document.createElement('button');
      addMove.className = 'tiny';
      addMove.textContent = '+ move';
      addMove.onclick = () => {
        pushHistory();
        element.moves = element.moves || [];
        const previous = element.moves[element.moves.length - 1];
        const from = previous ? previous.to : element.rest;
        const lastEnd = previous ? previous.end : 0;
        element.moves.push({
          to: [Number(from[0]) + 1, Number(from[1])],
          start: Math.min(0.9, lastEnd + 0.1),
          end: Math.min(1, lastEnd + 0.2),
          heading: [1, 0],
        });
        render();
      };
      moves.append(addMove);
      fields.push(field('Moves (each leg of the journey)', moves));
      break;
    }

    case 'wreck':
      fields.push(field('Crash site', pointRow('at', element.at, (v) => { element.at = v; })));
      fields.push(field('Appears from phase', numberInput(element.showFrom ?? 0, (v) => { element.showFrom = v; })));
      break;

    case 'statusFrame': {
      fields.push(field('Lap counter', textInput(element.lap || '', (v) => { element.lap = v; }, '1/2')));
      fields.push(field('Lap counter after the switch', textInput(element.lapAfter || '', (v) => {
        element.lapAfter = v;
      }, '2/2')));
      fields.push(field('Switch on', selectInput(element.switch?.kind || '', [
        { value: '', label: 'never — the counter is fixed' },
        { value: 'carPastX', label: 'a car passing a column' },
        { value: 'phase', label: 'a phase' },
      ], (v) => {
        if (!v) delete element.switch;
        else if (v === 'carPastX') element.switch = { kind: 'carPastX', car: carIds()[0] || '', x: 4.5 };
        else element.switch = { kind: 'phase', at: 0.5 };
      })));
      if (element.switch?.kind === 'carPastX') {
        fields.push(field('Car', selectInput(element.switch.car || '', carIds(), (v) => {
          element.switch.car = v;
        })));
        fields.push(field('Column x it must pass', numberInput(element.switch.x, (v) => { element.switch.x = v; })));
      } else if (element.switch?.kind === 'phase') {
        fields.push(field('At phase', numberInput(element.switch.at, (v) => { element.switch.at = v; })));
      }
      fields.push(field('Crossed out from phase', wrap(
        checkboxInput(element.crashedFrom != null, (on) => {
          if (on) element.crashedFrom = 0.8;
          else delete element.crashedFrom;
        }),
        element.crashedFrom != null
          ? numberInput(element.crashedFrom, (v) => { element.crashedFrom = v; })
          : document.createElement('span'),
      )));
      break;
    }

    case 'label':
      fields.push(field('Text', textInput(element.text || '', (v) => { element.text = v; })));
      fields.push(field('Anchor', pointRow('at', element.at, (v) => { element.at = v; })));
      fields.push(field('Size (cells)', numberInput(element.size ?? 0.3, (v) => { element.size = v; })));
      fields.push(field('Colour', selectInput(element.color || 'wall', colorNames, (v) => { element.color = v; })));
      fields.push(field('Centred on the anchor', checkboxInput(element.centered !== false, (v) => {
        element.centered = v;
      })));
      break;

    default:
      break;
  }
  return fields;
}

function wrap(...nodes) {
  const box = document.createElement('div');
  box.className = 'points';
  box.append(...nodes);
  return box;
}

const carIds = () => (state.project.elements || []).filter((e) => e.type === 'car').map((e) => e.id);

/** A sensible starting shape for each element type, centred on the grid. */
function defaultsFor(type, id) {
  const cx = Math.round((state.project.grid.cols - 1) / 2);
  const cy = Math.round((state.project.grid.rows - 1) / 2);
  switch (type) {
    case 'wall':
      return { type, from: [cx, -0.3], to: [cx, state.project.grid.rows - 0.7], width: 0.15 };
    case 'finishLine':
      return { type, x: cx + 0.5, y0: -0.35, y1: state.project.grid.rows - 0.65, width: 0.12, arrow: { y: cy, direction: 1 } };
    case 'gravel':
      return { type, from: [-0.5, cy], to: [state.project.grid.cols - 0.5, cy], depth: 2 };
    case 'hazard':
      return {
        type,
        color: 'oil',
        corners: [[cx - 1, cy - 0.8], [cx + 0.9, cy - 0.9], [cx + 1, cy + 0.8], [cx - 0.9, cy + 0.9]],
      };
    case 'trail':
      return { type, kind: 'static', from: [cx - 2, cy], to: [cx, cy] };
    case 'projector':
      return { type, from: [cx, cy], to: [cx + 2, cy], color: 'legal' };
    case 'pickers':
      return { type, style: 'offered', around: [cx, cy] };
    case 'pickRing':
      return { type, at: [cx, cy] };
    case 'car':
      return { type, rest: [Math.max(0, cx - 2), cy], restHeading: [1, 0], moves: [] };
    case 'wreck':
      return { type, at: [cx, cy], showFrom: 0.8 };
    case 'statusFrame':
      return { type, lap: '1/2', lapAfter: '2/2' };
    case 'label':
      return { type, at: [cx, cy], text: id || 'text', size: 0.3, color: 'wall', centered: true };
    default:
      return { type };
  }
}

// ---------------------------------------------------------------- scene & text tabs

function renderSceneTab() {
  const host = $('scene-form');
  host.replaceChildren();
  const grid = state.project.grid;

  host.append(field('Columns', numberInput(grid.cols, (v) => { grid.cols = Math.max(1, Math.round(v)); }, 1)));
  host.append(field('Rows', numberInput(grid.rows, (v) => { grid.rows = Math.max(1, Math.round(v)); }, 1)));
  host.append(field('Cell size', selectInput(grid.cellMode || 'fit', [
    { value: 'fit', label: 'fit — largest cell that keeps a margin' },
    { value: 'minDimension', label: 'minDimension — short side ÷ a divisor' },
  ], (v) => { grid.cellMode = v; })));
  if ((grid.cellMode || 'fit') === 'fit') {
    host.append(field('Margin factor (0.85 = 15% margin)', numberInput(grid.cellScale ?? 0.85, (v) => {
      grid.cellScale = v;
    })));
  } else {
    host.append(field('Divisor', numberInput(grid.cellDivisor ?? 7, (v) => { grid.cellDivisor = v; }, 0.5)));
  }
  host.append(field('Centring', selectInput(grid.originMode || 'points', [
    { value: 'points', label: 'points — outermost dots touch the margin' },
    { value: 'cells', label: 'cells — leaves half a cell of room all round' },
  ], (v) => { grid.originMode = v; })));

  const groupHost = $('group-form');
  groupHost.replaceChildren();
  const groups = state.project.groups || {};
  Object.entries(groups).forEach(([groupId, group]) => {
    const box = document.createElement('div');
    box.className = 'envelope';
    const heading = document.createElement('h4');
    heading.textContent = groupId;
    box.append(heading);
    box.append(envelopeEditor(group, 'Group fade'));
    const members = (state.project.elements || []).filter((e) => e.group === groupId).length;
    const note = document.createElement('p');
    note.className = 'help';
    note.textContent = `${members} element(s) in this group`;
    const remove = document.createElement('button');
    remove.className = 'tiny danger';
    remove.textContent = 'remove group';
    remove.onclick = () => {
      pushHistory();
      delete state.project.groups[groupId];
      for (const element of state.project.elements) if (element.group === groupId) delete element.group;
      render();
    };
    box.append(note, remove);
    groupHost.append(box);
  });
  const add = document.createElement('button');
  add.className = 'tiny';
  add.textContent = '+ group';
  add.onclick = () => {
    const name = prompt('Group id (letters, digits and _):', 'fade_out');
    if (!name) return;
    pushHistory();
    state.project.groups = state.project.groups || {};
    state.project.groups[name] = { alpha: { out: [0.8, 0.9] } };
    render();
  };
  groupHost.append(add);
}

function renderTextTab() {
  const host = $('text-form');
  host.replaceChildren();
  const project = state.project;

  host.append(field('Rule name (editor only)', textInput(project.name, (v) => { project.name = v; })));
  host.append(field('Kotlin symbol prefix', textInput(project.symbol, (v) => { project.symbol = v; })));
  host.append(field('Caption function name (blank = <symbol>Caption)', textInput(project.captionSymbol || '', (v) => {
    if (v) project.captionSymbol = v;
    else delete project.captionSymbol;
  })));
  host.append(field('Step title', textInput(project.title || '', (v) => { project.title = v; })));
  host.append(field('Step body', textArea(project.body || '', (v) => { project.body = v; }, 5)));
  host.append(field('String resource for the title', textInput(project.titleRes || '', (v) => {
    project.titleRes = v;
  }, 'how_to_play_stepN_title')));
  host.append(field('String resource for the body', textInput(project.bodyRes || '', (v) => {
    project.bodyRes = v;
  }, 'how_to_play_stepN_body')));
  host.append(field('Notes (become the generated file’s doc comment)', textArea(project.notes || '', (v) => {
    project.notes = v;
  }, 8)));

  const heading = document.createElement('h3');
  heading.textContent = 'Frame captions';
  host.append(heading);
  (project.frames || []).forEach((frame, index) => {
    const box = document.createElement('div');
    box.className = 'envelope';
    const title = document.createElement('h4');
    title.textContent = `Frame ${index + 1}`;
    box.append(title);
    const phaseRow = document.createElement('div');
    phaseRow.className = 'point-row';
    const label = document.createElement('span');
    label.className = 'label';
    label.textContent = 'phase';
    phaseRow.append(label, numberInput(frame.phase, (v) => { frame.phase = v; }));
    const here = document.createElement('button');
    here.className = 'tiny';
    here.textContent = 'from playhead';
    here.onclick = () => {
      pushHistory();
      frame.phase = Number(state.phase.toFixed(3));
      render();
    };
    phaseRow.append(here);
    box.append(phaseRow, textArea(frame.caption || '', (v) => { frame.caption = v; }, 3));
    host.append(box);
  });
}

function renderJsonTab() {
  $('json-view').value = JSON.stringify(state.project, null, 2);
}

// ---------------------------------------------------------------- the app's info panel

function captionAt(phase) {
  const frames = state.project.frames || [];
  for (let index = 0; index < frames.length - 1; index += 1) {
    if (phase < frames[index + 1].phase) return frames[index].caption || '';
  }
  return frames[frames.length - 1]?.caption || '';
}

function renderPhaseReadout() {
  $('phase-readout').textContent = fmt(state.phase);
  $('phase-slider').value = state.phase;
  $('preview-caption').textContent = captionAt(state.phase);
}

// ---------------------------------------------------------------- render

function render() {
  if (!state.project) return;
  renderFrames();
  renderElements();
  renderInspector();
  renderSceneTab();
  renderTextTab();
  renderJsonTab();
  $('preview-title').textContent = state.project.title || state.project.name || '';
  $('preview-body').textContent = state.project.body || '';
  renderPhaseReadout();
  drawPreview();
  markDirty();
}

function showTab(name) {
  document.querySelectorAll('.tab').forEach((tab) => tab.classList.toggle('active', tab.dataset.tab === name));
  document.querySelectorAll('.tab-body').forEach((body) => body.classList.toggle('hidden', body.id !== `tab-${name}`));
}

// ---------------------------------------------------------------- controls

function wireControls() {
  $('project-select').onchange = (event) => loadProject(event.target.value);

  $('btn-new').onclick = async () => {
    const id = prompt('New project id (a-z, digits, underscores):', 'new_rule');
    if (!id) return;
    const name = prompt('Rule name:', 'New rule') || id;
    try {
      await api('/api/projects', { method: 'POST', body: JSON.stringify({ id, name }) });
      await refreshProjectList();
      await loadProject(id);
    } catch (error) {
      setStatus(error.message, 'error');
    }
  };

  $('btn-duplicate').onclick = async () => {
    const id = prompt('Id for the copy:', `${state.projectId}_copy`);
    if (!id) return;
    const copy = clone(state.project);
    copy.id = id;
    copy.symbol = `${copy.symbol}Copy`;
    delete copy.captionSymbol;
    try {
      await api('/api/projects', { method: 'POST', body: JSON.stringify({ id, project: copy }) });
      await refreshProjectList();
      await loadProject(id);
    } catch (error) {
      setStatus(error.message, 'error');
    }
  };

  $('btn-delete').onclick = async () => {
    if (!confirm(`Delete ${state.projectId}.json? This cannot be undone.`)) return;
    await api(`/api/projects/${state.projectId}`, { method: 'DELETE' });
    state.projectId = null;
    await refreshProjectList();
    const next = $('project-select').value;
    if (next) await loadProject(next);
  };

  $('btn-revert').onclick = () => loadProject(state.projectId);
  $('btn-save').onclick = save;
  $('btn-export').onclick = () => exportKotlin(false);
  $('btn-install').onclick = () => {
    if (!confirm(
      `Generate ${state.project.symbol}Animation.kt straight into the app package, replacing the file there?`,
    )) return;
    exportKotlin(true);
  };

  $('btn-add-frame').onclick = () => {
    pushHistory();
    state.project.frames = state.project.frames || [];
    state.project.frames.push({ phase: Number(state.phase.toFixed(3)), caption: '' });
    state.project.frames.sort((a, b) => a.phase - b.phase);
    state.frameIndex = state.project.frames.findIndex((frame) => frame.phase === Number(state.phase.toFixed(3)));
    render();
  };
  $('btn-sort-frames').onclick = () => {
    pushHistory();
    state.project.frames.sort((a, b) => a.phase - b.phase);
    render();
  };

  $('btn-add-element').onclick = () => {
    pushHistory();
    const type = $('add-element-type').value;
    const id = freeId(type);
    state.project.elements.push({ id, ...defaultsFor(type, id) });
    state.selectedId = id;
    render();
  };

  $('btn-frame-prev').onclick = () => goToFrame(state.frameIndex - 1);
  $('btn-frame-next').onclick = () => goToFrame(state.frameIndex + 1);
  $('btn-play').onclick = togglePlay;

  $('phase-slider').oninput = (event) => {
    state.playing = false;
    $('btn-play').textContent = '▶ Play';
    state.animation = null;
    state.phase = Number(event.target.value);
    drawPreview();
    renderPhaseReadout();
    renderElements();
  };

  $('aspect-select').onchange = (event) => {
    state.aspect = ASPECTS.find((aspect) => aspect.id === event.target.value);
    drawPreview();
  };
  $('toggle-handles').onchange = (event) => {
    state.showHandles = event.target.checked;
    drawPreview();
  };
  $('toggle-step').onchange = (event) => {
    state.easeBetweenFrames = event.target.checked;
  };

  document.querySelectorAll('.tab').forEach((tab) => {
    tab.onclick = () => showTab(tab.dataset.tab);
  });

  $('btn-apply-json').onclick = () => {
    try {
      pushHistory();
      state.project = JSON.parse($('json-view').value);
      state.selectedId = state.project.elements?.[0]?.id ?? null;
      render();
      setStatus('JSON applied — not saved yet', 'ok');
    } catch (error) {
      setStatus(`bad JSON: ${error.message}`, 'error');
    }
  };

  const canvas = $('preview');
  canvas.addEventListener('pointerdown', onCanvasDown);
  canvas.addEventListener('pointermove', onCanvasMove);
  canvas.addEventListener('pointerup', onCanvasUp);
  canvas.addEventListener('pointercancel', onCanvasUp);

  window.addEventListener('keydown', (event) => {
    if (event.target.matches('input, textarea, select')) return;
    if ((event.ctrlKey || event.metaKey) && event.key === 's') {
      event.preventDefault();
      save();
    } else if ((event.ctrlKey || event.metaKey) && event.key === 'z') {
      event.preventDefault();
      undo();
    } else if (event.key === 'ArrowRight') {
      goToFrame(state.frameIndex + 1);
    } else if (event.key === 'ArrowLeft') {
      goToFrame(state.frameIndex - 1);
    } else if (event.key === ' ') {
      event.preventDefault();
      togglePlay();
    } else if (event.key === 'Delete' && state.selectedId) {
      pushHistory();
      const index = state.project.elements.findIndex((element) => element.id === state.selectedId);
      if (index >= 0) state.project.elements.splice(index, 1);
      state.selectedId = state.project.elements[0]?.id ?? null;
      render();
    }
  });

  window.addEventListener('beforeunload', (event) => {
    if (JSON.stringify(state.project) !== state.saved) event.preventDefault();
  });
}

async function save() {
  try {
    const result = await api(`/api/projects/${state.projectId}`, {
      method: 'PUT',
      body: JSON.stringify(state.project),
    });
    state.saved = JSON.stringify(state.project);
    markDirty();
    const warnings = result.warnings || [];
    setStatus(
      warnings.length ? `saved with ${warnings.length} warning(s): ${warnings.join('; ')}` : `saved ${result.saved}`,
      warnings.length ? 'error' : 'ok',
    );
    await refreshProjectList();
  } catch (error) {
    setStatus(error.message, 'error');
  }
}

async function exportKotlin(install) {
  try {
    if (JSON.stringify(state.project) !== state.saved) await save();
    const result = await api('/api/export', {
      method: 'POST',
      body: JSON.stringify({ ids: [state.projectId], install }),
    });
    setStatus(`generated ${result.written.join(', ')}`, 'ok');
  } catch (error) {
    setStatus(error.message, 'error');
  }
}

// Top-level await: the page's load event waits for the first project, so the UI never appears
// half-built (and a headless screenshot of it catches the real thing).
try {
  await boot();
} catch (error) {
  setStatus(error.message, 'error');
}
