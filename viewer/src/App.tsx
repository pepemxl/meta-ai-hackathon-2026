import { useEffect, useMemo, useRef, useState } from 'react';
import { applyH, fmt, getJson, globalFrame, jersey, loadTracks, Result, RunSummary, SEVERITY, Tracks } from './data';

export function App() {
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getJson<RunSummary[]>('/api/runs')
      .then((r) => {
        setRuns(r);
        if (r.length) setSelected(r[0].id);
      })
      .catch((e) => setError(String(e)));
  }, []);

  return (
    <>
      <header className="topbar">
        <h1>pp-coach-tracker</h1>
        <span className="muted">Visor de corridas del worker</span>
      </header>
      <div className="layout">
        <aside>
          <div className="card">
            <h2>Corridas</h2>
            {error && <p className="muted">{error}</p>}
            {runs && !runs.length && <p className="muted">No hay corridas. Corre el worker con DEBUG_DIR o `analyze --debug-dir`.</p>}
            <ul className="runs">
              {runs?.map((r) => (
                <li key={r.id} className={r.id === selected ? 'selected' : ''} onClick={() => setSelected(r.id)}>
                  <div>
                    {r.match?.home || 'Local'} vs {r.match?.away || 'Visitante'} <SegmenterChip segmenter={r.segmenter} />
                  </div>
                  <div className="muted tnum">
                    clip {r.clip_id ?? '–'} · {r.chunks ?? '?'} chunks · {r.frames ?? '?'} cuadros
                  </div>
                  <div className="muted">{r.id}</div>
                </li>
              ))}
            </ul>
          </div>
        </aside>
        <main>{selected ? <RunView key={selected} run={selected} /> : <div className="card empty muted">Elige una corrida.</div>}</main>
      </div>
    </>
  );
}

function SegmenterChip({ segmenter }: { segmenter?: string }) {
  if (!segmenter) return null;
  return <span className={`chip ${segmenter === 'meta_sam' ? 'real' : 'sim'}`}>{segmenter}</span>;
}

function RunView({ run }: { run: string }) {
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
  if (!result || !tracks) return <div className="card muted">Cargando…</div>;
  const colors = { A: jersey(result.match.home_color), B: jersey(result.match.away_color) };
  return (
    <>
      {result.backends.segmenter !== 'meta_sam' && <div className="banner">Análisis de desarrollo: {result.backends.segmenter}, no SAM 3.1</div>}
      <Player run={run} tracks={tracks} result={result} colors={colors} />
      <div className="card summary">
        <p style={{ margin: 0 }}>{result.summary}</p>
        <p className="muted" style={{ marginBottom: 0 }}>
          <span className="spark-star">✦</span>
          {result.backends.sam_model} ({result.backends.segmenter}) · {result.backends.muse_spark_model} ({result.backends.analyst})
        </p>
      </div>
      <Stats result={result} colors={colors} />
      <div className="card">
        <h2>Hallazgos</h2>
        {!result.insights.length && <p className="muted">Sin hallazgos.</p>}
        {result.insights.map((i, n) => (
          <div className="insight" key={n}>
            <span className="spark-star">✦</span>
            <span className={`chip ${i.severity}`}>{SEVERITY[i.severity] || i.severity}</span>{' '}
            <span className="muted tnum">
              {fmt(i.t_start_ms)}–{fmt(i.t_end_ms)}
            </span>
            <div className="title">{i.title}</div>
            <div>{i.description}</div>
            {i.recommendation && <div className="muted">Qué hacer: {i.recommendation}</div>}
          </div>
        ))}
      </div>
      {(result.notes.length > 0 || result.limitations.length > 0) && (
        <div className="card">
          <h2>Avisos y límites</h2>
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
            <strong>Resultado completo (result.json)</strong>
          </summary>
          <pre>{JSON.stringify(result, null, 1)}</pre>
        </details>
      </div>
    </>
  );
}

function Player({ run, tracks, result, colors }: { run: string; tracks: Tracks; result: Result; colors: { A: string; B: string } }) {
  const video = useRef<HTMLVideoElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const [chunkIdx, setChunkIdx] = useState(0);
  const [frame, setFrame] = useState<number | null>(null);
  const [showBall, setShowBall] = useState(true);
  const [showIds, setShowIds] = useState(true);
  const [showPitch, setShowPitch] = useState(true);
  const [showMask, setShowMask] = useState(true);
  const chunk = tracks.chunks[chunkIdx];
  const timeOf = useMemo(() => new Map(tracks.frames), [tracks]);

  // Draw the boxes of the frame on screen, every video frame.
  useEffect(() => {
    let raf = 0;
    const draw = () => {
      const v = video.current;
      const c = canvas.current;
      if (v && c && chunk) {
        const g = globalFrame(chunk, v.currentTime);
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
          ctx.strokeRect(x, y, b.w * vw * scale, b.h * vh * scale);
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
  }, [chunk, tracks, showBall, showIds, showPitch, showMask, colors]);

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
          Balón
        </button>
        <button className={showIds ? 'on' : ''} onClick={() => setShowIds(!showIds)}>
          IDs
        </button>
        {tracks.masks.size > 0 && (
          <button className={showMask ? 'on' : ''} onClick={() => setShowMask(!showMask)}>
            Máscara
          </button>
        )}
        {tracks.pitch && (
          <button className={showPitch ? 'on' : ''} onClick={() => setShowPitch(!showPitch)}>
            Líneas calculadas
          </button>
        )}
        <span className="muted tnum">
          cuadro {frame ?? '– (traslape)'} · {nowMs !== null ? fmt(nowMs) : '–'} · {frame !== null ? tracks.byFrame.get(frame)?.length ?? 0 : 0} objetos
          {tracks.pitch && frame !== null ? ` · cancha: ${tracks.pitch.homographies.get(frame)?.source ?? 'sin calcular'}` : ''}
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
          {result.match.home || 'Local'}
        </span>
        <span>
          <span className="dot" style={{ background: colors.B }} />
          {result.match.away || 'Visitante'}
        </span>
        <span>Posesión por segundo, como la cuenta el worker; en gris, lo descartado sin cancha (toca para saltar)</span>
      </div>
    </div>
  );
}

function Stats({ result, colors }: { result: Result; colors: { A: string; B: string } }) {
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
      <h2>Métricas</h2>
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
          {row('Posesión', pct(A?.possession), pct(B?.possession))}
          {row('Jugadores por cuadro', String(A?.players_per_frame ?? '–'), String(B?.players_per_frame ?? '–'))}
          {row('Tracks', String(A?.tracks ?? '–'), String(B?.tracks ?? '–'))}
          {row('Posesión más larga', fmt(A?.longest_possession_ms ?? 0), fmt(B?.longest_possession_ms ?? 0))}
        </tbody>
      </table>
      <p className="muted tnum">
        Balón visto {pct(result.ball?.seen_ratio)} · {result.possession_changes} cambios de posesión · {result.video.chunks} chunks de {result.video.chunk_seconds} s
        (traslape {result.video.overlap_ms} ms) · {Object.entries(result.backends.usage || {}).map(([k, v]) => `${k} ${v}`).join(', ')}
      </p>
    </div>
  );
}
