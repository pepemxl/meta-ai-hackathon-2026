// Shapes of a saved run (worker/ppcoachtracker_worker/debug.py) and helpers.

export type RunSummary = {
  id: string;
  clip_id: number | null;
  segmenter?: string;
  frames?: number;
  chunks?: number;
  generated_at?: string;
  duration_ms?: number;
  match?: { home?: string; away?: string; home_color?: string; away_color?: string };
};

export type ChunkInfo = { number: number; file: string; fps: number; frames: number; skip: number; base: number; start_ms: number; end_ms: number };

export type Box = { frame: number; t_ms: number; track: number; label: string; team: string; x: number; y: number; w: number; h: number; conf: number; interp: boolean };

export type Pitch = {
  homographies: Map<number, { source: string; h: number[] }>;
  lines: [string, [number, number], [number, number]][];
  circle: [number, number][];
};

export type Tracks = { byFrame: Map<number, Box[]>; frames: [number, number][]; chunks: ChunkInfo[]; pitch: Pitch | null; masks: Map<number, number[]> };

/** Pitch meters → normalized image (0..1) with a row-major 3×3 homography. */
export function applyH(h: number[], x: number, y: number): [number, number] | null {
  const w = h[6] * x + h[7] * y + h[8];
  if (Math.abs(w) < 1e-9) return null;
  return [(h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w];
}

export type Insight = { title: string; description: string; severity: string; t_start_ms: number; t_end_ms: number; recommendation: string };

export type Result = {
  clip_id: number | null;
  job_id: number | null;
  backends: { segmenter: string; analyst: string; sam_model: string; muse_spark_model: string; usage: Record<string, number> };
  match: { home?: string; away?: string; home_color?: string; away_color?: string };
  video: { duration_ms: number; frames: number; chunks: number; chunk_seconds: number; overlap_ms: number; width: number; height: number; frames_used?: number; discarded?: [number, number][] };
  teams: Record<'A' | 'B', { players_per_frame: number; tracks: number; possession: number | null; longest_possession_ms: number }>;
  ball: { seen_ratio: number };
  possession_changes: number;
  timeline: { t_ms: number; players_a: number; players_b: number; ball: boolean; owner: 'A' | 'B' | null }[];
  summary: string;
  insights: Insight[];
  notes: string[];
  limitations: string[];
  [key: string]: unknown;
};

// The same RGB as JerseyColor in the app and colors.py in the worker.
export const JERSEY: Record<string, string> = {
  red: '#E53935', white: '#F5F5F5', blue: '#1E5BD8', 'light blue': '#6EC6FF', black: '#212121',
  yellow: '#FDD835', green: '#2E7D32', orange: '#FB8C00', purple: '#7B1FA2', gray: '#9E9E9E',
};

export const jersey = (name?: string) => JERSEY[(name || '').toLowerCase()] || '#9E9E9E';

export async function getJson<T>(url: string): Promise<T> {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
  return r.json() as Promise<T>;
}

/** A chunk as the worker saves it, or in the older format (analysis_start, overlap_frames, no end_ms). */
type RawChunk = Partial<ChunkInfo> & { number: number; file: string; fps: number; frames: number; start_ms: number; analysis_start?: number; overlap_frames?: number };

function normalizeChunk(c: RawChunk): ChunkInfo {
  const skip = c.skip ?? c.overlap_frames ?? 0;
  return {
    number: c.number, file: c.file, fps: c.fps, frames: c.frames, skip, base: c.base ?? c.analysis_start ?? 0, start_ms: c.start_ms,
    end_ms: c.end_ms ?? Math.round(c.start_ms + (c.frames * 1000) / c.fps),
  };
}

export async function loadTracks(run: string): Promise<Tracks> {
  type RawPitch = { homographies: (number | string)[][]; lines: Pitch['lines']; circle: Pitch['circle'] };
  const raw = await getJson<{ columns: string[]; rows: unknown[][]; frames: [number, number][] | number; chunks: RawChunk[]; pitch?: RawPitch | number[][] | null; pitch_masks?: [number, number, number[]][] }>(`/api/runs/${run}/tracks.json`);
  const chunks = raw.chunks.map(normalizeChunk);
  const idx = Object.fromEntries(raw.columns.map((c, i) => [c, i]));
  const byFrame = new Map<number, Box[]>();
  for (const r of raw.rows) {
    const b: Box = {
      frame: r[idx.frame] as number, t_ms: r[idx.t_ms] as number, track: r[idx.track] as number, label: r[idx.label] as string,
      team: r[idx.team] as string, x: r[idx.x] as number, y: r[idx.y] as number, w: r[idx.w] as number, h: r[idx.h] as number, conf: r[idx.conf] as number, interp: idx.interp !== undefined && r[idx.interp] === 1,
    };
    const list = byFrame.get(b.frame);
    if (list) list.push(b);
    else byFrame.set(b.frame, [b]);
  }
  let pitch: Pitch | null = null;
  if (raw.pitch && !Array.isArray(raw.pitch) && raw.pitch.homographies) {
    const homographies = new Map<number, { source: string; h: number[] }>();
    for (const row of raw.pitch.homographies) homographies.set(row[0] as number, { source: row[1] as string, h: row.slice(2) as number[] });
    pitch = { homographies, lines: raw.pitch.lines, circle: raw.pitch.circle };
  }
  // The pitch mask of each frame: its polygon (empty = no pitch, the frame does not count).
  const masks = new Map<number, number[]>();
  for (const [f, , poly] of raw.pitch_masks || []) masks.set(f, poly);
  // The older format saved one polygon per frame in `pitch`.
  if (Array.isArray(raw.pitch)) (raw.pitch as number[][]).forEach((poly, f) => masks.set(f, poly));
  // The older format saved only the count of frames: their times come from each chunk.
  const frames: [number, number][] = Array.isArray(raw.frames)
    ? raw.frames
    : chunks.flatMap((c) => Array.from({ length: c.frames - c.skip }, (_, k): [number, number] => [c.base + k, Math.round(c.start_ms + ((c.skip + k) * 1000) / c.fps)]));
  return { byFrame, frames, chunks, pitch, masks };
}

/** Global frame shown by chunk [c] at [seconds] of its video; null inside the overlap already shown. */
export function globalFrame(c: ChunkInfo, seconds: number): number | null {
  const local = Math.min(c.frames - 1, Math.max(0, Math.floor(seconds * c.fps + 1e-3)));
  return local < c.skip ? null : c.base + local - c.skip;
}

export function fmt(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export const SEVERITY: Record<string, string> = { high: 'IMPORTANTE', medium: 'A REVISAR', low: 'DETALLE', info: 'DATO' };
