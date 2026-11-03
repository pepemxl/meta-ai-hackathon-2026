// The homography's results: the pitch seen from above with the players in
// meters and what the camera covers, and the calibration's report with the
// error of each of the coach's marks.
import { applyH, Box, invert3, Mark, Pitch, Result, toMeters } from './data';
import { useT } from './i18n';

const MARGIN_M = 6;
const VIOLET = '#7B2FF7';

/** The pitch from above at [frame]: its lines, the camera's view, the players and the ball, and the marks' error. */
export function TopDown({ pitch, h, boxes, marks, colors }: { pitch: Pitch; h: number[] | undefined; boxes: Box[]; marks: Mark[]; colors: { A: string; B: string } }) {
  const L = pitch.length_m;
  const W = pitch.width_m;
  const vw = L + 2 * MARGIN_M;
  const vh = W + 2 * MARGIN_M;
  // Pitch y > 0 is the far touchline: drawn on top, as the camera sees it.
  const sx = (x: number) => x + vw / 2;
  const sy = (y: number) => vh / 2 - y;
  const inv = h ? invert3(h) : null;
  const inView = (p: [number, number] | null): p is [number, number] => !!p && Math.abs(p[0]) < vw && Math.abs(p[1]) < vh;
  const t = useT();

  // What the camera sees: the image border taken to the pitch (only the part in front of the horizon).
  const view: [number, number][] = [];
  if (inv) {
    const border: [number, number][] = [];
    for (let k = 0; k <= 24; k++) border.push([k / 24, 0]);
    for (let k = 0; k <= 24; k++) border.push([1, k / 24]);
    for (let k = 24; k >= 0; k--) border.push([k / 24, 1]);
    for (let k = 24; k >= 0; k--) border.push([0, k / 24]);
    // Points behind the horizon are left out; the rest are clipped to the drawing.
    for (const [x, y] of border) {
      const p = toMeters(inv, x, y);
      if (p) view.push([Math.max(-vw / 2, Math.min(vw / 2, p[0])), Math.max(-vh / 2, Math.min(vh / 2, p[1]))]);
    }
  }
  const people = boxes.map((b) => {
    const foot: [number, number] | null = b.mx !== null && b.my !== null ? [b.mx, b.my] : inv ? toMeters(inv, b.x + b.w / 2, b.y + b.h) : null;
    return { b, p: foot };
  });

  return (
    <svg className="topdown" viewBox={`0 0 ${vw} ${vh}`} role="img" aria-label={t.topDownLabel}>
      <rect x={0} y={0} width={vw} height={vh} className="grass" />
      {view.length > 2 && <polygon points={view.map(([x, y]) => `${sx(x)},${sy(y)}`).join(' ')} className="view" />}
      {pitch.lines.map(([name, a, b]) => (
        <line key={name} x1={sx(a[0])} y1={sy(a[1])} x2={sx(b[0])} y2={sy(b[1])} className="pline" />
      ))}
      <polyline points={pitch.circle.map(([x, y]) => `${sx(x)},${sy(y)}`).join(' ')} className="pline" fill="none" />
      {inv &&
        marks.map((m) => {
          const t = pitch.points[m.name];
          const p = toMeters(inv, m.x, m.y);
          if (!t || !inView(p)) return null;
          return (
            <g key={`${m.frame}-${m.name}`}>
              <line x1={sx(t[0])} y1={sy(t[1])} x2={sx(p[0])} y2={sy(p[1])} stroke={VIOLET} strokeWidth={0.35} />
              <circle cx={sx(p[0])} cy={sy(p[1])} r={0.7} fill="#FFFFFF" stroke={VIOLET} strokeWidth={0.25} />
            </g>
          );
        })}
      {people.map(({ b, p }, n) =>
        inView(p) ? (
          b.label === 'ball' ? (
            <circle key={n} cx={sx(p[0])} cy={sy(p[1])} r={0.6} fill="#FFFFFF" stroke="#000000" strokeWidth={0.2} />
          ) : (
            <g key={n}>
              <circle cx={sx(p[0])} cy={sy(p[1])} r={1.1} fill={b.team === 'A' ? colors.A : b.team === 'B' ? colors.B : '#B0B3B8'} stroke="#000000" strokeWidth={0.2} />
              <text x={sx(p[0])} y={sy(p[1]) - 1.5} className="plabel">{b.track}</text>
            </g>
          )
        ) : null,
      )}
    </svg>
  );
}

type KeyFrame = { frame: number; t_ms: number; points: number; lines: number; used: boolean; inliers: number; median_error_px: number | null };
type Report = {
  pitch?: { size: number; length_m: number; width_m: number; source: string };
  camera?: string;
  mode?: string;
  key_frames?: KeyFrame[];
  frames_with_homography?: number;
  by_method?: Record<string, number>;
  median_error?: number | null;
  lines?: string;
};

/** The calibration's report (result.calibration) and the error of each mark in its frame, in pixels and meters. */
export function Calibration({ result, pitch, size }: { result: Result; pitch: Pitch | null; size: { width: number; height: number } }) {
  const t = useT();
  const r = result.calibration as Report | undefined;
  if (!r && !pitch) return null;
  const pct = (v?: number) => (v === undefined ? '–' : `${Math.round(v * 1000) / 10} %`);
  const rows = (pitch?.marks ?? []).map((m) => {
    const hom = pitch!.homographies.get(m.frame);
    const t = pitch!.points[m.name];
    if (!hom || !t) return { m, px: null, meters: null };
    const q = applyH(hom.h, t[0], t[1]);
    const inv = invert3(hom.h);
    const p = inv ? toMeters(inv, m.x, m.y) : null;
    return {
      m,
      px: q ? Math.hypot((q[0] - m.x) * size.width, (q[1] - m.y) * size.height) : null,
      meters: p ? Math.hypot(p[0] - t[0], p[1] - t[1]) : null,
    };
  });
  const errs = rows.flatMap((x) => (x.meters === null ? [] : [x.meters])).sort((a, b) => a - b);
  return (
    <div className="card">
      <h2>{t.pitchInMeters}</h2>
      {r && (
        <p className="tnum">
          {t.calLine(String(r.pitch?.size ?? '?'), String(r.pitch?.length_m), String(r.pitch?.width_m), r.pitch?.source === 'coach', r.camera === 'static', pct(r.frames_with_homography))}
          {r.by_method && ` (${Object.entries(r.by_method).filter(([, v]) => v > 0).map(([k, v]) => `${t.method[k] ?? k} ${pct(v)}`).join(', ')})`}
          {r.median_error !== undefined && r.median_error !== null && t.medianError(Math.round(r.median_error * size.height * 10) / 10)}
          {r.lines && t.samLines(r.lines === 'sam')}
        </p>
      )}
      {r?.key_frames && r.key_frames.length > 0 && (
        <table>
          <thead>
            <tr>{t.keyHead.map((h) => <th key={h}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {r.key_frames.map((k) => (
              <tr key={k.frame} className="tnum">
                <td>{k.frame}</td><td>{k.points}</td><td>{k.lines}</td><td>{k.inliers}</td>
                <td>{k.median_error_px === null ? '–' : `${k.median_error_px} px`}</td><td>{k.used ? t.yes : t.no}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {rows.length > 0 && (
        <>
          <p className="muted">
            {t.marksHelp(errs.length ? `${errs[Math.floor(errs.length / 2)].toFixed(2)} m` : '–', errs.length ? `${errs[errs.length - 1].toFixed(2)} m` : '–')}
          </p>
          <table>
            <thead>
              <tr>{t.marksHead.map((h) => <th key={h}>{h}</th>)}</tr>
            </thead>
            <tbody>
              {rows
                .slice()
                .sort((a, b) => (b.meters ?? -1) - (a.meters ?? -1))
                .map(({ m, px, meters }) => (
                  <tr key={`${m.frame}-${m.name}`} className="tnum">
                    <td>{m.name}</td><td>{m.frame}</td><td>{px === null ? '–' : `${px.toFixed(1)} px`}</td><td>{meters === null ? '–' : `${meters.toFixed(2)} m`}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
