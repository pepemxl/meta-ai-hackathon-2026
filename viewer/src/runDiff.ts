// Comparing two runs of the same video (a local segmenter vs SAM, or a change before and
// after): the same metrics for both, measured from their boxes, and which
// boxes of a frame one run found and the other did not.
import { Box, Result, Tracks } from './data';

/** Two boxes are the same player when they overlap this much (intersection over union). */
export const MATCH_IOU = 0.3;

export function iou(a: Box, b: Box): number {
  const iw = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x));
  const ih = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
  const inter = iw * ih;
  const union = a.w * a.h + b.w * b.h - inter;
  return union > 0 ? inter / union : 0;
}

export type FrameMatch = { pairs: [Box, Box][]; onlyA: Box[]; onlyB: Box[] };

/** Greedy matching of the players of one frame, best overlap first. */
export function matchFrame(a: Box[], b: Box[]): FrameMatch {
  const pa = a.filter((x) => x.label === 'player');
  const pb = b.filter((x) => x.label === 'player');
  const cand: [number, number, number][] = [];
  pa.forEach((x, i) => pb.forEach((y, j) => {
    const v = iou(x, y);
    if (v >= MATCH_IOU) cand.push([v, i, j]);
  }));
  cand.sort((p, q) => q[0] - p[0]);
  const ua = new Set<number>();
  const ub = new Set<number>();
  const pairs: [Box, Box][] = [];
  for (const [, i, j] of cand) {
    if (ua.has(i) || ub.has(j)) continue;
    ua.add(i);
    ub.add(j);
    pairs.push([pa[i], pb[j]]);
  }
  return { pairs, onlyA: pa.filter((_, i) => !ua.has(i)), onlyB: pb.filter((_, j) => !ub.has(j)) };
}

export type RunMetrics = {
  playersPerFrame: { A: number; B: number; none: number; total: number };
  tracks: number;
  trackSecondsMedian: number;
  boxesInLongTracks: number;
  boxHeight: number;
  ballDetected: number;
  ballSeen: number;
  possession: { A: number | null; B: number | null };
  possessionChanges: number | null;
  longestPossessionMs: { A: number; B: number };
  processingSeconds: number | null;
  framesSent: number | null;
  usd: number | null;
};

/** US$ per 1,000 frames and concept (SAM 3.1 on the Meta Model API). */
export const SAM_USD_PER_1000 = 0.2;

export function runMetrics(r: Result, t: Tracks): RunMetrics {
  const frames = Math.max(1, t.frames.length);
  const count = { A: 0, B: 0, none: 0 };
  const perTrack = new Map<number, number>();
  let heights = 0;
  let players = 0;
  let ballDet = 0;
  let ballSeen = 0;
  for (const boxes of t.byFrame.values()) {
    let ball = false;
    let det = false;
    for (const b of boxes) {
      if (b.label === 'ball') {
        ball = true;
        det ||= !b.interp;
        continue;
      }
      players++;
      heights += b.h;
      if (b.team === 'A' || b.team === 'B') count[b.team]++;
      else count.none++;
      perTrack.set(b.track, (perTrack.get(b.track) || 0) + 1);
    }
    if (ball) ballSeen++;
    if (det) ballDet++;
  }
  const fps = t.chunks[0]?.fps || 10;
  const secs = [...perTrack.values()].map((n) => n / fps).sort((a, b) => a - b);
  const long = secs.filter((s) => s >= 60).reduce((a, s) => a + s, 0);
  const all = secs.reduce((a, s) => a + s, 0);
  // Older runs counted changes inside `ball` and had no usage.
  const ball = r.ball as { possession_changes?: number };
  const sent = (r.backends.usage || {}).frames_sent ?? null;
  return {
    playersPerFrame: { A: count.A / frames, B: count.B / frames, none: count.none / frames, total: players / frames },
    tracks: perTrack.size,
    trackSecondsMedian: secs.length ? secs[Math.floor(secs.length / 2)] : 0,
    boxesInLongTracks: all ? long / all : 0,
    boxHeight: players ? heights / players : 0,
    ballDetected: ballDet / frames,
    ballSeen: ballSeen / frames,
    possession: { A: r.teams.A?.possession ?? null, B: r.teams.B?.possession ?? null },
    possessionChanges: r.possession_changes ?? ball?.possession_changes ?? null,
    longestPossessionMs: { A: r.teams.A?.longest_possession_ms ?? 0, B: r.teams.B?.longest_possession_ms ?? 0 },
    processingSeconds: (r.processing_seconds as number | undefined) ?? null,
    framesSent: sent,
    usd: r.backends.segmenter === 'meta_sam' && sent !== null ? (sent * SAM_USD_PER_1000) / 1000 : null,
  };
}

export type Agreement = {
  matched: number;
  onlyA: number;
  onlyB: number;
  sameTeam: number;
  bothTeam: number;
  ballBoth: number;
  ballOnlyA: number;
  ballOnlyB: number;
};

/** Over every frame: boxes both found, boxes only one found, team agreement and ball frames. */
export function agreement(a: Tracks, b: Tracks): Agreement {
  const out: Agreement = { matched: 0, onlyA: 0, onlyB: 0, sameTeam: 0, bothTeam: 0, ballBoth: 0, ballOnlyA: 0, ballOnlyB: 0 };
  const frames = new Set([...a.byFrame.keys(), ...b.byFrame.keys()]);
  for (const f of frames) {
    const fa = a.byFrame.get(f) || [];
    const fb = b.byFrame.get(f) || [];
    const m = matchFrame(fa, fb);
    out.matched += m.pairs.length;
    out.onlyA += m.onlyA.length;
    out.onlyB += m.onlyB.length;
    for (const [x, y] of m.pairs) {
      if (x.team && y.team) {
        out.bothTeam++;
        if (x.team === y.team) out.sameTeam++;
      }
    }
    const ba = fa.some((x) => x.label === 'ball');
    const bb = fb.some((x) => x.label === 'ball');
    if (ba && bb) out.ballBoth++;
    else if (ba) out.ballOnlyA++;
    else if (bb) out.ballOnlyB++;
  }
  return out;
}

export type Second = { t_ms: number; players: number; ball: boolean };

/** Per second of the original video: mean players per frame and whether the ball was seen. */
export function perSecond(t: Tracks): Second[] {
  const acc = new Map<number, { n: number; frames: number; ball: boolean }>();
  for (const [f, ms] of t.frames) {
    const s = Math.floor(ms / 1000);
    const e = acc.get(s) || { n: 0, frames: 0, ball: false };
    const boxes = t.byFrame.get(f) || [];
    e.frames++;
    e.n += boxes.filter((b) => b.label === 'player').length;
    e.ball ||= boxes.some((b) => b.label === 'ball');
    acc.set(s, e);
  }
  return [...acc].sort((p, q) => p[0] - q[0]).map(([s, e]) => ({ t_ms: s * 1000, players: e.n / e.frames, ball: e.ball }));
}

/** Whether the two runs read the same video: same frames and the same chunks. */
export function sameVideo(a: Tracks, b: Tracks): boolean {
  return a.frames.length === b.frames.length
    && a.chunks.length === b.chunks.length
    && a.chunks.every((c, i) => c.file === b.chunks[i].file && c.frames === b.chunks[i].frames && c.start_ms === b.chunks[i].start_ms);
}

/** A short name for a run: what segmented it. */
export function runName(r: Result): string {
  const s = r.backends.segmenter;
  if (s === 'meta_sam') return 'SAM 3.1';
  if (s === 'local_cv') return 'CV local';
  if (s === 'sam_simulator') return 'Simulador';
  return s || 'Corrida';
}
