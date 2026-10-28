'use strict';

// Whether the worker computes the pitch (docs/prompts/13-cancha-en-metros.md):
// lines = the lines are clear; no_lines = only the coach's points; off = do not.
const PITCH_MODES = ['lines', 'no_lines', 'off'];
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

const num = (v) => typeof v === 'number' && Number.isFinite(v);

/**
 * The coach's points on the pitch:
 *   {pitch: {size: 11|7, length_m?, width_m?}, frames: [{t_ms | frame, points: [{name, x, y}]}]}
 * 4 to 40 points per frame, no repeated names, x and y in 0..1.
 * Returns {value} (the normalized object) or {error: "..."}.
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
  if (!Array.isArray(b.frames) || b.frames.length < 1 || b.frames.length > MAX_FRAMES) return { error: `frames must have 1..${MAX_FRAMES} entries` };
  const frames = [];
  for (const [i, f] of b.frames.entries()) {
    if (!f || typeof f !== 'object') return { error: `frames[${i}] is not an object` };
    const out = {};
    if (Number.isInteger(f.t_ms) && f.t_ms >= 0) out.t_ms = f.t_ms;
    else if (Number.isInteger(f.frame) && f.frame >= 0) out.frame = f.frame;
    else return { error: `frames[${i}] needs t_ms or frame` };
    if (!Array.isArray(f.points) || f.points.length < 4 || f.points.length > 40) return { error: `frames[${i}].points must have 4..40 points` };
    const names = new Set();
    out.points = [];
    for (const [j, pt] of f.points.entries()) {
      if (!pt || typeof pt.name !== 'string' || !POINT_NAME.test(pt.name)) return { error: `frames[${i}].points[${j}].name is invalid` };
      if (names.has(pt.name)) return { error: `frames[${i}] repeats ${pt.name}` };
      names.add(pt.name);
      if (!num(pt.x) || !num(pt.y) || pt.x < 0 || pt.x > 1 || pt.y < 0 || pt.y > 1) return { error: `frames[${i}].points[${j}] must have x and y in 0..1` };
      out.points.push({ name: pt.name, x: pt.x, y: pt.y });
    }
    frames.push(out);
  }
  return { value: { pitch, frames } };
}

module.exports = { PITCH_MODES, parsePitchMode, parseCalibration };
