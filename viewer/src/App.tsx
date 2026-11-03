import { useEffect, useMemo, useRef, useState } from 'react';
import { CompareView } from './Compare';
import { applyH, fmt, getJson, globalFrame, jersey, loadTracks, marksFor, Result, runsByDay, RunSummary, runTime, Tracks } from './data';
import { initialLang, Lang, LangContext, saveLang, TEXTS, useT } from './i18n';
import { Calibration, TopDown } from './PitchView';

export function App() {
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Up to two runs ticked to compare; `comparing` shows them instead of the selected run.
  const [ticked, setTicked] = useState<string[]>([]);
  const [comparing, setComparing] = useState<[string, string] | null>(null);
  // Days shown open in the list: the newest, and the one of a run opened by link.
  const [openDays, setOpenDays] = useState<Set<string>>(new Set());
  const tick = (id: string) => setTicked((t) => (t.includes(id) ? t.filter((x) => x !== id) : [...t, id].slice(-2)));
  const [lang, setLang] = useState<Lang>(initialLang);
  const t = TEXTS[lang];
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);
  const choose = (l: Lang) => {
    setLang(l);
    saveLang(l);
  };

  useEffect(() => {
    getJson<RunSummary[]>('/api/runs')
      .then((r) => {
        setRuns(r);
        // ?run=<id> opens that run (a link to it); else the newest.
        const wanted = new URLSearchParams(location.search).get('run');
        if (r.length) setSelected(r.find((x) => x.id === wanted)?.id ?? r[0].id);
        const days = runsByDay(r, TEXTS.es);
        setOpenDays(new Set(days.filter((g, i) => i === 0 || g.runs.some((x) => x.id === wanted)).map((g) => g.key)));
      })
      .catch((e) => setError(String(e)));
  }, []);

  return (
    <LangContext.Provider value={{ lang, t }}>
      <header className="topbar">
        <h1>pp-coach-tracker</h1>
        <span className="muted">{t.subtitle}</span>
        <div className="lang-toggle" role="group" aria-label="Idioma / Language">
          {(['es', 'en'] as Lang[]).map((l) => (
            <button key={l} className={lang === l ? 'on' : ''} aria-pressed={lang === l} onClick={() => choose(l)}>
              {l.toUpperCase()}
            </button>
          ))}
        </div>
      </header>
      <div className="layout">
        <aside>
          <div className="card">
            <h2>{t.runs}</h2>
            {error && <p className="muted">{error}</p>}
            {runs && !runs.length && <p className="muted">{t.noRuns}</p>}
            {runs && runsByDay(runs, t).map((g) => (
              <details key={g.key} className="run-day" open={openDays.has(g.key)}
                onToggle={(e) => {
                  const open = e.currentTarget.open;
                  setOpenDays((d) => (open === d.has(g.key) ? d : new Set(open ? [...d, g.key] : [...d].filter((x) => x !== g.key))));
                }}>
                <summary className="run-day-title">{g.day} <span className="tnum">· {g.runs.length}</span></summary>
                <ul className="runs">
                  {g.runs.map((r) => (
                    <li key={r.id} className={!comparing && r.id === selected ? 'selected' : ''} onClick={() => { setSelected(r.id); setComparing(null); }}>
                      <div className="run-row">
                        <span>
                          {r.match?.home || t.home} vs {r.match?.away || t.away} <SegmenterChip segmenter={r.segmenter} />
                        </span>
                        <input type="checkbox" title={t.compare} checked={ticked.includes(r.id)} onClick={(e) => e.stopPropagation()} onChange={() => tick(r.id)} />
                      </div>
                      <div className="muted tnum">
                        {runTime(r, t.locale) && `${runTime(r, t.locale)} · `}clip {r.clip_id ?? '–'} · {r.chunks ?? '?'} chunks · {r.frames ?? '?'} {t.frames}
                      </div>
                      <div className="muted">{r.id}</div>
                    </li>
                  ))}
                </ul>
              </details>
            ))}
            {runs && runs.length > 1 && (
              <div className="compare-bar">
                <button className="primary" disabled={ticked.length !== 2} onClick={() => setComparing([ticked[0], ticked[1]])}>
                  {t.compare} {ticked.length}/2
                </button>
                <span className="muted">{t.compareHelp}</span>
              </div>
            )}
          </div>
        </aside>
        <main>
          {comparing ? (
            <CompareView key={comparing.join('|')} ids={comparing} />
          ) : selected ? (
            <RunView key={selected} run={selected} />
          ) : (
            <div className="card empty muted">{t.pickRun}</div>
          )}
        </main>
      </div>
    </LangContext.Provider>
  );
}

function SegmenterChip({ segmenter }: { segmenter?: string }) {
  if (!segmenter) return null;
  return <span className={`chip ${segmenter === 'meta_sam' ? 'real' : 'sim'}`}>{segmenter}</span>;
}

function RunView({ run }: { run: string }) {
  const t = useT();
  const [result, setResult] = useState<Result | null>(null);
  const [tracks, setTracks] = useState<Tracks | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    Promise.all([getJson<Result>(`/api/runs/${run}/result.json`), loadTracks(run)])
      .then(([r, t]) => {
        setResult(r);
        setTracks(t);
      })
      .catch((e) => setError(String(e)));
  }, [run]);
  if (error) return <div className="card muted">{error}</div>;
  if (!result || !tracks) return <div className="card muted">{t.loading}</div>;
  const colors = { A: jersey(result.match.home_color), B: jersey(result.match.away_color) };
  return (
    <>
      {result.backends.segmenter !== 'meta_sam' && <div className="banner">{t.devBanner(result.backends.segmenter)}</div>}
      <Player run={run} tracks={tracks} result={result} colors={colors} />
      <Calibration result={result} pitch={tracks.pitch} size={{ width: result.video.width, height: result.video.height }} />
      <div className="card summary">
        <p style={{ margin: 0 }}>{result.summary}</p>
        <p className="muted" style={{ marginBottom: 0 }}>
          <span className="spark-star">✦</span>
          {result.backends.sam_model || result.backends.segmenter} ({result.backends.segmenter}) · {result.backends.muse_spark_model || result.backends.analyst} ({result.backends.analyst})
        </p>
      </div>
      <Stats result={result} colors={colors} />
      <div className="card">
        <h2>{t.findings}</h2>
        {!result.insights.length && <p className="muted">{t.noFindings}</p>}
        {result.insights.map((i, n) => (
          <div className="insight" key={n}>
            <span className="spark-star">✦</span>
            <span className={`chip ${i.severity}`}>{t.severity[i.severity] || i.severity}</span>{' '}
            <span className="muted tnum">
              {fmt(i.t_start_ms)}–{fmt(i.t_end_ms)}
            </span>
            <div className="title">{i.title}</div>
            <div>{i.description}</div>
            {i.recommendation && <div className="muted">{t.whatToDo}: {i.recommendation}</div>}
          </div>
        ))}
      </div>
      {(result.notes.length > 0 || result.limitations.length > 0) && (
        <div className="card">
          <h2>{t.notes}</h2>
          <ul>
            {[...result.notes, ...result.limitations].map((l, n) => (
              <li key={n} className="muted">
                {l}
              </li>
            ))}
          </ul>
        </div>
      )}
      <div className="card">
        <details>
          <summary>
            <strong>{t.fullResult}</strong>
          </summary>
          <pre>{JSON.stringify(result, null, 1)}</pre>
        </details>
      </div>
    </>
  );
}

function Player({ run, tracks, result, colors }: { run: string; tracks: Tracks; result: Result; colors: { A: string; B: string } }) {
  const t = useT();
  const video = useRef<HTMLVideoElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const [chunkIdx, setChunkIdx] = useState(0);
  const [frame, setFrame] = useState<number | null>(null);
  const [showBall, setShowBall] = useState(true);
  const [showIds, setShowIds] = useState(true);
  const [showPitch, setShowPitch] = useState(true);
  const [showMask, setShowMask] = useState(true);
  const [showMarks, setShowMarks] = useState(true);
  const chunk = tracks.chunks[chunkIdx];
  const timeOf = useMemo(() => new Map(tracks.frames), [tracks]);

  // Draw the boxes of the frame on screen, every video frame.
  useEffect(() => {
    let raf = 0;
    const draw = () => {
      const v = video.current;
      const c = canvas.current;
      if (v && c && chunk) {
        const g = globalFrame(chunk, v.currentTime, v.duration);
        setFrame(g);
        c.width = c.clientWidth * devicePixelRatio;
        c.height = c.clientHeight * devicePixelRatio;
        const ctx = c.getContext('2d')!;
        ctx.clearRect(0, 0, c.width, c.height);
        // The video is letterboxed inside the 16:9 box.
        const vw = v.videoWidth || 16;
        const vh = v.videoHeight || 9;
        const scale = Math.min(c.width / vw, c.height / vh);
        const ox = (c.width - vw * scale) / 2;
        const oy = (c.height - vh * scale) / 2;
        // The computed pitch ("Líneas calculadas"), in violet from the Spark gradient.
        const hom = g === null || !showPitch || !tracks.pitch ? undefined : tracks.pitch.homographies.get(g);
        if (hom && tracks.pitch) {
          const toPx = (p: [number, number] | null) => (p ? [ox + p[0] * vw * scale, oy + p[1] * vh * scale] : null);
          ctx.strokeStyle = '#7B2FF7';
          ctx.lineWidth = 1.5 * devicePixelRatio;
          const segs: [number, number][][] = tracks.pitch.lines.map(([, a, b]) => [a, b]);
          segs.push(tracks.pitch.circle);
          for (const seg of segs) {
            ctx.beginPath();
            seg.forEach(([x, y], k) => {
              const p = toPx(applyH(hom.h, x, y));
              if (!p) return;
              if (k === 0) ctx.moveTo(p[0], p[1]);
              else ctx.lineTo(p[0], p[1]);
            });
            ctx.stroke();
          }
          // The coach's marks (white) and where the homography puts their points (the violet line's end).
          if (showMarks && g !== null) {
            ctx.font = `${11 * devicePixelRatio}px Roboto, sans-serif`;
            for (const m of marksFor(tracks.pitch, g)) {
              const t = tracks.pitch.points[m.name];
              const q = t ? toPx(applyH(hom.h, t[0], t[1])) : null;
              const p = toPx([m.x, m.y]);
              if (!p) continue;
              if (q) {
                ctx.strokeStyle = '#7B2FF7';
                ctx.lineWidth = 2 * devicePixelRatio;
                ctx.beginPath();
                ctx.moveTo(p[0], p[1]);
                ctx.lineTo(q[0], q[1]);
                ctx.stroke();
                ctx.fillStyle = '#7B2FF7';
                ctx.beginPath();
                ctx.arc(q[0], q[1], 2.5 * devicePixelRatio, 0, 2 * Math.PI);
                ctx.fill();
              }
              ctx.fillStyle = '#FFFFFF';
              ctx.beginPath();
              ctx.arc(p[0], p[1], 3.5 * devicePixelRatio, 0, 2 * Math.PI);
              ctx.fill();
              ctx.fillText(m.name, p[0] + 5 * devicePixelRatio, p[1] - 5 * devicePixelRatio);
            }
          }
        }
        // The pitch mask: the polygon where boxes count (dashed); a frame without it is discarded.
        const poly = g === null || !showMask ? undefined : tracks.masks.get(g);
        if (poly && poly.length >= 6) {
          ctx.save();
          ctx.setLineDash([6 * devicePixelRatio, 4 * devicePixelRatio]);
          ctx.strokeStyle = 'rgba(255,255,255,0.8)';
          ctx.lineWidth = 1.5 * devicePixelRatio;
          ctx.beginPath();
          for (let k = 0; k < poly.length; k += 2) {
            const px = ox + poly[k] * vw * scale;
            const py = oy + poly[k + 1] * vh * scale;
            if (k === 0) ctx.moveTo(px, py);
            else ctx.lineTo(px, py);
          }
          ctx.closePath();
          ctx.stroke();
          ctx.restore();
        } else if (poly) {
          ctx.fillStyle = 'rgba(0,0,0,0.45)';
          ctx.fillRect(ox, oy, vw * scale, vh * scale);
        }
        const boxes = g === null ? [] : tracks.byFrame.get(g) || [];
        ctx.lineWidth = 2 * devicePixelRatio;
        ctx.font = `${12 * devicePixelRatio}px Roboto, sans-serif`;
        for (const b of boxes) {
          if (b.label === 'ball' && !showBall) continue;
          const color = b.label === 'ball' ? '#FFFFFF' : b.team === 'A' ? colors.A : b.team === 'B' ? colors.B : '#B0B3B8';
          const x = ox + b.x * vw * scale;
          const y = oy + b.y * vh * scale;
          ctx.strokeStyle = color;
          // A ball filled inside its trajectory (not detected) is dashed.
          ctx.setLineDash(b.interp ? [3 * devicePixelRatio, 3 * devicePixelRatio] : []);
          ctx.strokeRect(x, y, b.w * vw * scale, b.h * vh * scale);
          ctx.setLineDash([]);
          if (showIds) {
            ctx.fillStyle = color;
            ctx.fillText(`${b.track}${b.team}`, x, Math.max(12 * devicePixelRatio, y - 3 * devicePixelRatio));
          }
        }
      }
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [chunk, tracks, showBall, showIds, showPitch, showMask, showMarks, colors]);

  const t0 = result.timeline[0]?.t_ms ?? 0;
  const t1 = (result.timeline[result.timeline.length - 1]?.t_ms ?? t0) + 1000;
  const nowMs = frame !== null ? timeOf.get(frame) ?? null : null;

  /** Jumps to a time of the original video: the chunk that has it (outside an overlap) and the second inside it. */
  const seek = (ms: number) => {
    const i = tracks.chunks.findIndex((c, n) => ms < c.end_ms || n === tracks.chunks.length - 1);
    const c = tracks.chunks[Math.max(0, i)];
    const go = () => {
      if (video.current) video.current.currentTime = Math.max(0, (ms - c.start_ms) / 1000);
    };
    if (i !== chunkIdx) {
      setChunkIdx(Math.max(0, i));
      setTimeout(go, 300);
    } else go();
  };

  return (
    <div className="card">
      <div className="player">
        <video ref={video} src={chunk ? `/api/runs/${run}/video/${chunk.file}` : undefined} controls muted playsInline />
        <canvas ref={canvas} style={{ pointerEvents: 'none' }} />
      </div>
      <div className="controls">
        {tracks.chunks.map((c, i) => (
          <button key={c.number} className={i === chunkIdx ? 'on' : ''} onClick={() => setChunkIdx(i)}>
            Chunk {c.number}
          </button>
        ))}
        <button className={showBall ? 'on' : ''} onClick={() => setShowBall(!showBall)}>
          {t.ball}
        </button>
        <button className={showIds ? 'on' : ''} onClick={() => setShowIds(!showIds)}>
          IDs
        </button>
        {tracks.masks.size > 0 && (
          <button className={showMask ? 'on' : ''} onClick={() => setShowMask(!showMask)}>
            {t.mask}
          </button>
        )}
        {tracks.pitch && (
          <button className={showPitch ? 'on' : ''} onClick={() => setShowPitch(!showPitch)}>
            {t.computedLines}
          </button>
        )}
        {tracks.pitch && tracks.pitch.marks.length > 0 && (
          <button className={showMarks ? 'on' : ''} onClick={() => setShowMarks(!showMarks)}>
            {t.marks}
          </button>
        )}
        <span className="muted tnum">
          {t.frame} {frame ?? t.overlap} · {nowMs !== null ? fmt(nowMs) : '–'} · {frame !== null ? tracks.byFrame.get(frame)?.length ?? 0 : 0} {t.objects}
          {tracks.pitch && frame !== null ? t.pitchState(tracks.pitch.homographies.get(frame)?.source ?? t.notComputed) : ''}
        </span>
      </div>
      <div className="timeline" onClick={(e) => {
        const r = e.currentTarget.getBoundingClientRect();
        seek(t0 + ((e.clientX - r.left) / r.width) * (t1 - t0));
      }}>
        {result.timeline.map((s, n) =>
          s.owner ? (
            <div key={n} className="seg" style={{ left: `${((s.t_ms - t0) / (t1 - t0)) * 100}%`, width: `${(1000 / (t1 - t0)) * 100}%`, background: colors[s.owner] }} />
          ) : null,
        )}
        {(result.video.discarded || []).map(([a, b], n) => (
          <div key={`d${n}`} className="seg discarded" style={{ left: `${((a - t0) / (t1 - t0)) * 100}%`, width: `${((b - a) / (t1 - t0)) * 100}%` }} />
        ))}
        {nowMs !== null && <div className="head" style={{ left: `${((nowMs - t0) / (t1 - t0)) * 100}%` }} />}
      </div>
      <div className="legend muted">
        <span>
          <span className="dot" style={{ background: colors.A }} />
          {result.match.home || t.home}
        </span>
        <span>
          <span className="dot" style={{ background: colors.B }} />
          {result.match.away || t.away}
        </span>
        <span>{t.timelineLegend}</span>
      </div>
      {tracks.pitch && (
        <div className="topdown-wrap">
          <TopDown
            pitch={tracks.pitch}
            h={frame === null ? undefined : tracks.pitch.homographies.get(frame)?.h}
            boxes={frame === null ? [] : (tracks.byFrame.get(frame) || []).filter((b) => b.label !== 'ball' || showBall)}
            marks={frame === null ? [] : marksFor(tracks.pitch, frame)}
            colors={colors}
          />
          <p className="muted">
            {t.topDownHelp}
            {frame !== null && !tracks.pitch.homographies.get(frame) && t.topDownNoPitch}
          </p>
        </div>
      )}
    </div>
  );
}

function Stats({ result, colors }: { result: Result; colors: { A: string; B: string } }) {
  const t = useT();
  const row = (label: string, a: string, b: string) => (
    <tr>
      <td className="muted">{label}</td>
      <td className="tnum">{a}</td>
      <td className="tnum">{b}</td>
    </tr>
  );
  const pct = (v: number | null | undefined) => (v === null || v === undefined ? '–' : `${Math.round(v * 100)} %`);
  const A = result.teams.A;
  const B = result.teams.B;
  return (
    <div className="card">
      <h2>{t.metrics}</h2>
      <table>
        <thead>
          <tr>
            <th />
            <th>
              <span className="dot" style={{ background: colors.A }} />
              {result.match.home || 'A'}
            </th>
            <th>
              <span className="dot" style={{ background: colors.B }} />
              {result.match.away || 'B'}
            </th>
          </tr>
        </thead>
        <tbody>
          {row(t.possession, pct(A?.possession), pct(B?.possession))}
          {row(t.playersPerFrame, String(A?.players_per_frame ?? '–'), String(B?.players_per_frame ?? '–'))}
          {row(t.tracks, String(A?.tracks ?? '–'), String(B?.tracks ?? '–'))}
          {row(t.longestPossession, fmt(A?.longest_possession_ms ?? 0), fmt(B?.longest_possession_ms ?? 0))}
        </tbody>
      </table>
      <p className="muted tnum">
        {t.statsLine(pct(result.ball?.seen_ratio), result.possession_changes, result.video.chunks, result.video.chunk_seconds, result.video.overlap_ms)} ·{' '}
        {Object.entries(result.backends.usage || {}).map(([k, v]) => `${k} ${v}`).join(', ')}
      </p>
    </div>
  );
}
