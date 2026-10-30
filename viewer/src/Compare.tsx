import { useEffect, useMemo, useRef, useState } from 'react';
import { agreement, matchFrame, perSecond, runMetrics, runName, sameVideo, Second } from './runDiff';
import { Box, fmt, getJson, globalFrame, jersey, loadTracks, Result, SEVERITY, Tracks } from './data';

type Run = { id: string; result: Result; tracks: Tracks; name: string };
type Mode = 'side' | 'overlay' | 'diff';

// One color per run where both share the screen: orange for the first, violet for the second.
const RUN_COLOR = ['#FB8C00', '#7B2FF7'];

/** Two runs of the same video: the boxes of both, where they differ, their metrics and their findings. */
export function CompareView({ ids }: { ids: [string, string] }) {
  const [runs, setRuns] = useState<[Run, Run] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    Promise.all(ids.map((id) => Promise.all([getJson<Result>(`/api/runs/${id}/result.json`), loadTracks(id)])))
      .then((both) => {
        const named = both.map(([result, tracks], i) => ({ id: ids[i], result, tracks, name: runName(result) }));
        // Two runs with the same segmenter: tell them apart by their id.
        if (named[0].name === named[1].name) named.forEach((r) => (r.name = `${r.name} · ${r.id}`));
        setRuns(named as [Run, Run]);
      })
      .catch((e) => setError(String(e)));
  }, [ids]);
  if (error) return <div className="card muted">{error}</div>;
  if (!runs) return <div className="card muted">Cargando las dos corridas…</div>;
  const [a, b] = runs;
  const colors = { A: jersey(a.result.match.home_color), B: jersey(a.result.match.away_color) };
  return (
    <>
      {!sameVideo(a.tracks, b.tracks) && (
        <div className="banner">Las dos corridas no tienen los mismos chunks ni los mismos cuadros: la comparación cuadro por cuadro no es válida.</div>
      )}
      <ComparePlayer runs={runs} colors={colors} />
      <CompareStats runs={runs} colors={colors} />
      <div className="compare-cols">
        {runs.map((r, i) => (
          <div className="card" key={r.id}>
            <h2>
              <span className="dot" style={{ background: RUN_COLOR[i] }} />
              Hallazgos · {r.name}
            </h2>
            <p className="muted">
              {r.result.backends.analyst} · {r.result.summary}
            </p>
            {!r.result.insights.length && <p className="muted">Sin hallazgos.</p>}
            {r.result.insights.map((x, n) => (
              <div className="insight" key={n}>
                <span className={`chip ${x.severity}`}>{SEVERITY[x.severity] || x.severity}</span>{' '}
                <span className="muted tnum">
                  {fmt(x.t_start_ms)}–{fmt(x.t_end_ms)}
                </span>
                <div className="title">{x.title}</div>
                <div>{x.description}</div>
              </div>
            ))}
          </div>
        ))}
      </div>
    </>
  );
}

type Geom = { ox: number; oy: number; sw: number; sh: number };

/** Clears the canvas and returns where the letterboxed video sits on it. */
function fit(v: HTMLVideoElement, c: HTMLCanvasElement): Geom {
  c.width = c.clientWidth * devicePixelRatio;
  c.height = c.clientHeight * devicePixelRatio;
  c.getContext('2d')!.clearRect(0, 0, c.width, c.height);
  const vw = v.videoWidth || 16;
  const vh = v.videoHeight || 9;
  const scale = Math.min(c.width / vw, c.height / vh);
  return { ox: (c.width - vw * scale) / 2, oy: (c.height - vh * scale) / 2, sw: vw * scale, sh: vh * scale };
}

function drawBox(ctx: CanvasRenderingContext2D, g: Geom, b: Box, color: string, opts: { dash?: boolean; label?: string; width?: number }) {
  const x = g.ox + b.x * g.sw;
  const y = g.oy + b.y * g.sh;
  ctx.strokeStyle = color;
  ctx.lineWidth = (opts.width ?? 2) * devicePixelRatio;
  ctx.setLineDash(opts.dash || b.interp ? [4 * devicePixelRatio, 3 * devicePixelRatio] : []);
  if (b.label === 'ball') {
    // The ball as a circle, so it stands out from the players in every mode.
    ctx.beginPath();
    ctx.arc(x + (b.w * g.sw) / 2, y + (b.h * g.sh) / 2, Math.max(6 * devicePixelRatio, (b.w * g.sw) / 2 + 3 * devicePixelRatio), 0, 2 * Math.PI);
    ctx.stroke();
  } else ctx.strokeRect(x, y, b.w * g.sw, b.h * g.sh);
  ctx.setLineDash([]);
  if (opts.label) {
    ctx.fillStyle = color;
    ctx.fillText(opts.label, x, Math.max(12 * devicePixelRatio, y - 3 * devicePixelRatio));
  }
}

function ComparePlayer({ runs, colors }: { runs: [Run, Run]; colors: { A: string; B: string } }) {
  const [a, b] = runs;
  const videoA = useRef<HTMLVideoElement>(null);
  const videoB = useRef<HTMLVideoElement>(null);
  const canvasA = useRef<HTMLCanvasElement>(null);
  const canvasB = useRef<HTMLCanvasElement>(null);
  const [mode, setMode] = useState<Mode>('side');
  const [chunkIdx, setChunkIdx] = useState(0);
  const [frame, setFrame] = useState<number | null>(null);
  const [showBall, setShowBall] = useState(true);
  const [showIds, setShowIds] = useState(false);
  const chunk = a.tracks.chunks[chunkIdx];
  const timeOf = useMemo(() => new Map(a.tracks.frames), [a]);
  const seconds = useMemo(() => [perSecond(a.tracks), perSecond(b.tracks)] as [Second[], Second[]], [a, b]);

  useEffect(() => {
    let raf = 0;
    const teamColor = (x: Box) => (x.label === 'ball' ? '#FFFFFF' : x.team === 'A' ? colors.A : x.team === 'B' ? colors.B : '#B0B3B8');
    const draw = () => {
      const va = videoA.current;
      const ca = canvasA.current;
      if (va && ca && chunk) {
        const g = globalFrame(chunk, va.currentTime);
        setFrame(g);
        const boxesA = (g === null ? [] : a.tracks.byFrame.get(g) || []).filter((x) => showBall || x.label !== 'ball');
        const boxesB = (g === null ? [] : b.tracks.byFrame.get(g) || []).filter((x) => showBall || x.label !== 'ball');
        const geomA = fit(va, ca);
        const ctxA = ca.getContext('2d')!;
        ctxA.font = `${12 * devicePixelRatio}px Roboto, sans-serif`;
        if (mode === 'side') {
          for (const x of boxesA) drawBox(ctxA, geomA, x, teamColor(x), { label: showIds ? `${x.track}${x.team}` : undefined });
          // The second video follows the first: same chunk, same second, playing or paused with it.
          const vb = videoB.current;
          const cb = canvasB.current;
          if (vb && cb) {
            if (Math.abs(vb.currentTime - va.currentTime) > 0.15) vb.currentTime = va.currentTime;
            if (va.paused !== vb.paused) (va.paused ? vb.pause() : vb.play().catch(() => undefined));
            const geomB = fit(vb, cb);
            const ctxB = cb.getContext('2d')!;
            ctxB.font = ctxA.font;
            for (const x of boxesB) drawBox(ctxB, geomB, x, teamColor(x), { label: showIds ? `${x.track}${x.team}` : undefined });
          }
        } else if (mode === 'overlay') {
          // The first run solid and the second dashed, both in team colors.
          for (const x of boxesA) drawBox(ctxA, geomA, x, teamColor(x), { label: showIds ? `${x.track}${x.team}` : undefined });
          for (const x of boxesB) drawBox(ctxA, geomA, x, teamColor(x), { dash: true, width: 1.5, label: showIds ? `${x.track}${x.team}` : undefined });
        } else {
          // Differences: what both found in faint gray, what only one found in its color.
          const m = matchFrame(boxesA, boxesB);
          for (const [x] of m.pairs) drawBox(ctxA, geomA, x, 'rgba(255,255,255,0.35)', { width: 1 });
          for (const x of m.onlyA) drawBox(ctxA, geomA, x, RUN_COLOR[0], { width: 2.5, label: showIds ? `${x.track}${x.team}` : undefined });
          for (const x of m.onlyB) drawBox(ctxA, geomA, x, RUN_COLOR[1], { width: 2.5, label: showIds ? `${x.track}${x.team}` : undefined });
          for (const x of boxesA) if (x.label === 'ball') drawBox(ctxA, geomA, x, RUN_COLOR[0], {});
          for (const x of boxesB) if (x.label === 'ball') drawBox(ctxA, geomA, x, RUN_COLOR[1], { dash: true });
        }
      }
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [a, b, chunk, mode, showBall, showIds, colors]);

  const nowMs = frame !== null ? timeOf.get(frame) ?? null : null;
  const fa = frame === null ? [] : a.tracks.byFrame.get(frame) || [];
  const fb = frame === null ? [] : b.tracks.byFrame.get(frame) || [];
  const m = matchFrame(fa, fb);
  const players = (l: Box[]) => l.filter((x) => x.label === 'player').length;

  /** Jumps to a time of the original video, in the chunk that has it. */
  const seek = (ms: number) => {
    const i = Math.max(0, a.tracks.chunks.findIndex((c, n) => ms < c.end_ms || n === a.tracks.chunks.length - 1));
    const c = a.tracks.chunks[i];
    const go = () => {
      if (videoA.current) videoA.current.currentTime = Math.max(0, (ms - c.start_ms) / 1000);
    };
    if (i !== chunkIdx) {
      setChunkIdx(i);
      setTimeout(go, 300);
    } else go();
  };

  const src = (r: Run) => (chunk ? `/api/runs/${r.id}/video/${chunk.file}` : undefined);
  return (
    <div className="card">
      <div className={mode === 'side' ? 'compare-cols tight' : ''}>
        <div>
          {mode === 'side' && <RunLabel i={0} name={a.name} n={players(fa)} />}
          <div className="player">
            <video ref={videoA} src={src(a)} controls muted playsInline />
            <canvas ref={canvasA} style={{ pointerEvents: 'none' }} />
          </div>
        </div>
        {mode === 'side' && (
          <div>
            <RunLabel i={1} name={b.name} n={players(fb)} />
            <div className="player">
              <video ref={videoB} src={src(b)} muted playsInline />
              <canvas ref={canvasB} style={{ pointerEvents: 'none' }} />
            </div>
          </div>
        )}
      </div>
      <div className="controls">
        {(
          [
            ['side', 'Lado a lado'],
            ['overlay', 'Superpuestas'],
            ['diff', 'Diferencias'],
          ] as [Mode, string][]
        ).map(([k, label]) => (
          <button key={k} className={mode === k ? 'on' : ''} onClick={() => setMode(k)}>
            {label}
          </button>
        ))}
        {a.tracks.chunks.map((c, i) => (
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
      </div>
      <p className="muted tnum">
        cuadro {frame ?? '– (traslape)'} · {nowMs !== null ? fmt(nowMs) : '–'} · {a.name}: {players(fa)} · {b.name}: {players(fb)} · en las dos: {m.pairs.length} ·
        solo {a.name}: {m.onlyA.length} · solo {b.name}: {m.onlyB.length}
      </p>
      <div className="legend muted">
        {mode === 'overlay' && <span>{a.name} con línea continua, {b.name} punteada; colores de equipo.</span>}
        {mode === 'diff' && (
          <>
            <span>
              <span className="dot" style={{ background: RUN_COLOR[0] }} />
              Solo {a.name}
            </span>
            <span>
              <span className="dot" style={{ background: RUN_COLOR[1] }} />
              Solo {b.name}
            </span>
            <span>En gris, lo que encontraron las dos (IoU ≥ 0.3); el balón en círculo</span>
          </>
        )}
        {mode === 'side' && <span>El video de la derecha sigue al de la izquierda; usa los controles de la izquierda.</span>}
      </div>
      <SecondsChart seconds={seconds} names={[a.name, b.name]} nowMs={nowMs} onSeek={seek} />
    </div>
  );
}

function RunLabel({ i, name, n }: { i: number; name: string; n: number }) {
  return (
    <div className="run-label">
      <span className="dot" style={{ background: RUN_COLOR[i] }} />
      <strong>{name}</strong> <span className="muted tnum">{n} jugadores</span>
    </div>
  );
}

/** Players per frame each second for both runs, and below, the seconds where each saw the ball. Click to jump. */
function SecondsChart({ seconds, names, nowMs, onSeek }: { seconds: [Second[], Second[]]; names: [string, string]; nowMs: number | null; onSeek: (ms: number) => void }) {
  const all = [...seconds[0], ...seconds[1]];
  if (!all.length) return null;
  const t0 = Math.min(...all.map((s) => s.t_ms));
  const t1 = Math.max(...all.map((s) => s.t_ms)) + 1000;
  const top = Math.max(1, ...all.map((s) => s.players));
  const W = 1000;
  const H = 120;
  const x = (ms: number) => ((ms - t0) / (t1 - t0)) * W;
  const y = (v: number) => H - (v / top) * (H - 8);
  const path = (list: Second[]) => list.map((s, k) => `${k ? 'L' : 'M'}${x(s.t_ms + 500).toFixed(1)},${y(s.players).toFixed(1)}`).join('');
  const click = (e: React.MouseEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    onSeek(t0 + ((e.clientX - r.left) / r.width) * (t1 - t0));
  };
  return (
    <div className="seconds-chart" onClick={click}>
      <div className="muted">Jugadores por cuadro (promedio por segundo; máximo {top.toFixed(0)})</div>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none">
        {seconds.map((list, i) => (
          <path key={i} d={path(list)} fill="none" stroke={RUN_COLOR[i]} strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
        ))}
        {nowMs !== null && <line x1={x(nowMs)} x2={x(nowMs)} y1={0} y2={H} stroke="currentColor" strokeWidth={1.5} vectorEffect="non-scaling-stroke" />}
      </svg>
      {seconds.map((list, i) => (
        <div key={i} className="ball-strip" title={`Balón visto · ${names[i]}`}>
          {list.map((s, k) => (s.ball ? <div key={k} style={{ left: `${(x(s.t_ms) / W) * 100}%`, width: `${(1000 / (t1 - t0)) * 100}%`, background: RUN_COLOR[i] }} /> : null))}
          <span className="strip-name">Balón · {names[i]}</span>
        </div>
      ))}
    </div>
  );
}

function CompareStats({ runs, colors }: { runs: [Run, Run]; colors: { A: string; B: string } }) {
  const [a, b] = runs;
  const ma = useMemo(() => runMetrics(a.result, a.tracks), [a]);
  const mb = useMemo(() => runMetrics(b.result, b.tracks), [b]);
  const ag = useMemo(() => agreement(a.tracks, b.tracks), [a, b]);
  const pct = (v: number | null | undefined) => (v === null || v === undefined ? '–' : `${Math.round(v * 100)} %`);
  const num = (v: number | null | undefined, d = 1) => (v === null || v === undefined ? '–' : v.toFixed(d));
  const home = a.result.match.home || 'Local';
  const away = a.result.match.away || 'Visitante';
  const rows: [string, string, string][] = [
    ['Segmentador', a.result.backends.segmenter, b.result.backends.segmenter],
    ['Hallazgos', a.result.backends.analyst, b.result.backends.analyst],
    ['Tiempo de proceso', ma.processingSeconds === null ? '–' : `${Math.round(ma.processingSeconds)} s`, mb.processingSeconds === null ? '–' : `${Math.round(mb.processingSeconds)} s`],
    ['Costo de SAM', ma.usd === null ? '–' : `US$${ma.usd.toFixed(2)}`, mb.usd === null ? '–' : `US$${mb.usd.toFixed(2)}`],
    ['Jugadores por cuadro', num(ma.playersPerFrame.total), num(mb.playersPerFrame.total)],
    [`  ${home}`, num(ma.playersPerFrame.A), num(mb.playersPerFrame.A)],
    [`  ${away}`, num(ma.playersPerFrame.B), num(mb.playersPerFrame.B)],
    ['  Sin equipo', num(ma.playersPerFrame.none), num(mb.playersPerFrame.none)],
    ['Alto medio de la caja', pct(ma.boxHeight), pct(mb.boxHeight)],
    ['Tracks', String(ma.tracks), String(mb.tracks)],
    ['Duración mediana del track', `${num(ma.trackSecondsMedian)} s`, `${num(mb.trackSecondsMedian)} s`],
    ['Cajas en tracks de 60 s o más', pct(ma.boxesInLongTracks), pct(mb.boxesInLongTracks)],
    ['Balón detectado', pct(ma.ballDetected), pct(mb.ballDetected)],
    ['Balón visto (con relleno)', pct(ma.ballSeen), pct(mb.ballSeen)],
    [`Posesión ${home} / ${away}`, `${pct(ma.possession.A)} / ${pct(ma.possession.B)}`, `${pct(mb.possession.A)} / ${pct(mb.possession.B)}`],
    ['Cambios de posesión', String(ma.possessionChanges ?? '–'), String(mb.possessionChanges ?? '–')],
    ['Posesión más larga', `${fmt(ma.longestPossessionMs.A)} / ${fmt(ma.longestPossessionMs.B)}`, `${fmt(mb.longestPossessionMs.A)} / ${fmt(mb.longestPossessionMs.B)}`],
  ];
  const share = (n: number, d: number) => (d ? `${Math.round((100 * n) / d)} %` : '–');
  return (
    <div className="compare-cols">
      <div className="card">
        <h2>Métricas</h2>
        <table>
          <thead>
            <tr>
              <th />
              {runs.map((r, i) => (
                <th key={r.id}>
                  <span className="dot" style={{ background: RUN_COLOR[i] }} />
                  {r.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map(([label, x, y]) => (
              <tr key={label}>
                <td className="muted">{label}</td>
                <td className="tnum">{x}</td>
                <td className="tnum">{y}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="muted">
          Medido igual en las dos desde sus cajas (tracks.json), salvo posesión, cambios y tiempo, que vienen de cada result.json.{' '}
          <span className="dot" style={{ background: colors.A }} />
          {home} <span className="dot" style={{ background: colors.B }} />
          {away}
        </p>
      </div>
      <div className="card">
        <h2>Coincidencia cuadro por cuadro</h2>
        <table>
          <tbody>
            <tr>
              <td className="muted">Jugadores que encontraron las dos (IoU ≥ 0.3)</td>
              <td className="tnum">{ag.matched.toLocaleString('es-MX')}</td>
            </tr>
            <tr>
              <td className="muted">De {a.name} que también encontró {b.name}</td>
              <td className="tnum">{share(ag.matched, ag.matched + ag.onlyA)}</td>
            </tr>
            <tr>
              <td className="muted">De {b.name} que también encontró {a.name}</td>
              <td className="tnum">{share(ag.matched, ag.matched + ag.onlyB)}</td>
            </tr>
            <tr>
              <td className="muted">Solo {a.name} / solo {b.name}</td>
              <td className="tnum">
                {ag.onlyA.toLocaleString('es-MX')} / {ag.onlyB.toLocaleString('es-MX')}
              </td>
            </tr>
            <tr>
              <td className="muted">Mismo equipo en las que encontraron las dos</td>
              <td className="tnum">{share(ag.sameTeam, ag.bothTeam)}</td>
            </tr>
            <tr>
              <td className="muted">Cuadros con balón: las dos / solo {a.name} / solo {b.name}</td>
              <td className="tnum">
                {ag.ballBoth} / {ag.ballOnlyA} / {ag.ballOnlyB}
              </td>
            </tr>
          </tbody>
        </table>
        <p className="muted">Usa "Diferencias" en el video para ver en qué cuadros y dónde difieren.</p>
      </div>
    </div>
  );
}
