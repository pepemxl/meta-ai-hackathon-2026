'use strict';

// Whether the worker computes the pitch (docs/prompts/13-cancha-en-metros.md):
// lines = the lines are clear; no_lines = only the coach's points; off = do not.
const PITCH_MODES = ['lines', 'no_lines', 'off'];
// Whether the camera stays still the whole video (a tripod or a panorama:
// one homography holds) or moves and zooms (a feed: it is carried frame by frame).
const CAMERA_MODES = ['static', 'moving'];
const POINT_NAME = /^[a-z0-9_]{1,40}$/;
const MAX_FRAMES = 20;

/** analysis.pitch_mode of POST /clips → {value} (null when absent) or {error}. */
function parsePitchMode(analysis) {
  if (analysis === undefined || analysis === null) return { value: null };
  if (typeof analysis !== 'object') return { error: true };
  const m = analysis.pitch_mode;
  if (m === undefined || m === null) return { value: null };
  return PITCH_MODES.includes(m) ? { value: m } : { error: true };
}

/** analysis.camera of POST /clips → {value} (null when absent) or {error}. */
function parseCamera(analysis) {
  if (analysis === undefined || analysis === null) return { value: null };
  if (typeof analysis !== 'object') return { error: true };
  const m = analysis.camera;
  if (m === undefined || m === null) return { value: null };
  return CAMERA_MODES.includes(m) ? { value: m } : { error: true };
}

const num = (v) => typeof v === 'number' && Number.isFinite(v);

// The play area the coach marks on a still camera: a polygon (the pitch's
// corners, and more points where a panorama bends the lines) and a margin
// around it, as a fraction of the frame height.
const PLAY_AREA_POINTS = [3, 16];
const PLAY_AREA_MAX_MARGIN = 0.25;

/**
 * analysis.play_area of POST /clips: {points: [{x, y}] (3..16, in 0..1, in
 * order around it), margin?: 0..0.25} → {value} (null when absent) or {error}.
 */
function parsePlayArea(analysis) {
  if (analysis === undefined || analysis === null || typeof analysis !== 'object') return { value: null };
  const a = analysis.play_area;
  if (a === undefined || a === null) return { value: null };
  if (typeof a !== 'object' || !Array.isArray(a.points)) return { error: true };
  const [lo, hi] = PLAY_AREA_POINTS;
  if (a.points.length < lo || a.points.length > hi || !a.points.every(inUnit)) return { error: true };
  const margin = a.margin === undefined || a.margin === null ? 0 : a.margin;
  if (!num(margin) || margin < 0 || margin > PLAY_AREA_MAX_MARGIN) return { error: true };
  return { value: { points: a.points.map((pt) => ({ x: pt.x, y: pt.y })), margin } };
}

const MAX_LINES = 20;
const MAX_REFERENCES = 6;
const MAX_STROKE_POINTS = 64;
const MAX_TOTAL_STROKE_POINTS = 3000;
const REFERENCE_SOURCES = ['photo', 'frame'];

const inUnit = (pt) => pt && num(pt.x) && num(pt.y) && pt.x >= 0 && pt.x <= 1 && pt.y >= 0 && pt.y <= 1;

/** A painted stroke: 2..64 points with x, y in 0..1 → {value: [{x, y}]} or {error}. */
function parseStroke(points, where) {
  if (!Array.isArray(points) || points.length < 2 || points.length > MAX_STROKE_POINTS) return { error: `${where}.points must have 2..${MAX_STROKE_POINTS} points` };
  if (!points.every(inUnit)) return { error: `${where}.points must have x and y in 0..1` };
  return { value: points.map((pt) => ({ x: pt.x, y: pt.y })) };
}

/**
 * The coach's marks on the pitch:
 *   {pitch: {size: 11|7, length_m?, width_m?},
 *    frames: [{t_ms | frame, points: [{name, x, y}], lines?: [{name | null, points: [{x, y}]}]}],
 *    references?: [{n: 1..6, source: photo|frame, t_ms?, strokes: [{points: [{x, y}]}]}]}
 * A frame has 0..40 points (no repeated names) and 0..20 lines painted over a
 * line of the pitch (named, or null when the coach only says "this is a line"),
 * and at least one of them. References are the coach's pictures of the pitch
 * (PUT /clips/:id/references/:n) with strokes over its lines, to show SAM what
 * a line looks like. x and y in 0..1. Returns {value} or {error: "..."}.
 */
function parseCalibration(body) {
  const b = body || {};
  const p = b.pitch;
  if (!p || typeof p !== 'object' || (p.size !== 11 && p.size !== 7)) return { error: 'pitch.size must be 11 or 7' };
  const pitch = { size: p.size };
  for (const [key, lo, hi] of [['length_m', 20, 130], ['width_m', 15, 100]]) {
    if (p[key] === undefined || p[key] === null) continue;
    if (!num(p[key]) || p[key] < lo || p[key] > hi) return { error: `pitch.${key} must be ${lo}..${hi}` };
    pitch[key] = p[key];
  }
  const rawFrames = b.frames === undefined ? [] : b.frames;
  const rawRefs = b.references === undefined ? [] : b.references;
  if (!Array.isArray(rawFrames) || rawFrames.length > MAX_FRAMES) return { error: `frames must have 0..${MAX_FRAMES} entries` };
  if (!Array.isArray(rawRefs) || rawRefs.length > MAX_REFERENCES) return { error: `references must have 0..${MAX_REFERENCES} entries` };
  if (rawFrames.length + rawRefs.length < 1) return { error: 'frames or references must have an entry' };
  let strokePoints = 0;
  const frames = [];
  for (const [i, f] of rawFrames.entries()) {
    if (!f || typeof f !== 'object') return { error: `frames[${i}] is not an object` };
    const out = {};
    if (Number.isInteger(f.t_ms) && f.t_ms >= 0) out.t_ms = f.t_ms;
    else if (Number.isInteger(f.frame) && f.frame >= 0) out.frame = f.frame;
    else return { error: `frames[${i}] needs t_ms or frame` };
    const points = f.points === undefined ? [] : f.points;
    const lines = f.lines === undefined ? [] : f.lines;
    if (!Array.isArray(points) || points.length > 40) return { error: `frames[${i}].points must have 0..40 points` };
    if (!Array.isArray(lines) || lines.length > MAX_LINES) return { error: `frames[${i}].lines must have 0..${MAX_LINES} lines` };
    if (points.length + lines.length < 1) return { error: `frames[${i}] needs points or lines` };
    const names = new Set();
    out.points = [];
    for (const [j, pt] of points.entries()) {
      if (!pt || typeof pt.name !== 'string' || !POINT_NAME.test(pt.name)) return { error: `frames[${i}].points[${j}].name is invalid` };
      if (names.has(pt.name)) return { error: `frames[${i}] repeats ${pt.name}` };
      names.add(pt.name);
      if (!inUnit(pt)) return { error: `frames[${i}].points[${j}] must have x and y in 0..1` };
      out.points.push({ name: pt.name, x: pt.x, y: pt.y });
    }
    if (lines.length) {
      out.lines = [];
      for (const [j, ln] of lines.entries()) {
        const where = `frames[${i}].lines[${j}]`;
        if (!ln || typeof ln !== 'object') return { error: `${where} is not an object` };
        const name = ln.name === undefined ? null : ln.name;
        if (name !== null && (typeof name !== 'string' || !POINT_NAME.test(name))) return { error: `${where}.name is invalid` };
        const stroke = parseStroke(ln.points, where);
        if (stroke.error) return stroke;
        strokePoints += stroke.value.length;
        out.lines.push({ name, points: stroke.value });
      }
    }
    frames.push(out);
  }
  const references = [];
  const numbers = new Set();
  for (const [i, r] of rawRefs.entries()) {
    const where = `references[${i}]`;
    if (!r || typeof r !== 'object') return { error: `${where} is not an object` };
    if (!Number.isInteger(r.n) || r.n < 1 || r.n > MAX_REFERENCES || numbers.has(r.n)) return { error: `${where}.n must be a distinct 1..${MAX_REFERENCES}` };
    numbers.add(r.n);
    if (!REFERENCE_SOURCES.includes(r.source)) return { error: `${where}.source must be photo or frame` };
    const out = { n: r.n, source: r.source };
    if (r.t_ms !== undefined && r.t_ms !== null) {
      if (!Number.isInteger(r.t_ms) || r.t_ms < 0) return { error: `${where}.t_ms is invalid` };
      out.t_ms = r.t_ms;
    }
    const strokes = r.strokes === undefined ? [] : r.strokes;
    if (!Array.isArray(strokes) || strokes.length > MAX_LINES) return { error: `${where}.strokes must have 0..${MAX_LINES} strokes` };
    out.strokes = [];
    for (const [j, st] of strokes.entries()) {
      const stroke = parseStroke(st && st.points, `${where}.strokes[${j}]`);
      if (stroke.error) return stroke;
      strokePoints += stroke.value.length;
      out.strokes.push({ points: stroke.value });
    }
    references.push(out);
  }
  if (strokePoints > MAX_TOTAL_STROKE_POINTS) return { error: `at most ${MAX_TOTAL_STROKE_POINTS} stroke points in all` };
  const value = { pitch, frames };
  if (references.length) value.references = references;
  return { value };
}

module.exports = { PITCH_MODES, CAMERA_MODES, MAX_REFERENCES, parsePitchMode, parseCamera, parsePlayArea, parseCalibration };
