// The viewer's texts in Spanish and English. The language comes from ?lang=es|en,
// else the one chosen with the toggle (kept in this browser), else the browser's.
import { createContext, useContext } from 'react';

export type Lang = 'es' | 'en';

const KEY = 'viewer-lang';

export function initialLang(): Lang {
  const fromUrl = new URLSearchParams(location.search).get('lang');
  if (fromUrl === 'es' || fromUrl === 'en') return fromUrl;
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === 'es' || saved === 'en') return saved;
  } catch {
    // No storage (private window): the browser's language.
  }
  return navigator.language.toLowerCase().startsWith('es') ? 'es' : 'en';
}

export function saveLang(lang: Lang) {
  try {
    localStorage.setItem(KEY, lang);
  } catch {
    // Not kept; it still applies to this page.
  }
}

const es = {
  locale: 'es-MX',
  subtitle: 'Visor de corridas del worker',
  runs: 'Corridas',
  noRuns: 'No hay corridas. Corre el worker con DEBUG_DIR o `analyze --debug-dir`.',
  today: 'Hoy',
  yesterday: 'Ayer',
  undated: 'Sin fecha',
  home: 'Local',
  away: 'Visitante',
  compare: 'Comparar',
  frames: 'cuadros',
  compareHelp: 'Marca dos corridas del mismo video (un segmentador local contra SAM, o antes y después de un cambio).',
  pickRun: 'Elige una corrida.',
  loading: 'Cargando…',
  devBanner: (s: string) => `Análisis de desarrollo: ${s}, no SAM 3.1`,
  findings: 'Hallazgos',
  noFindings: 'Sin hallazgos.',
  whatToDo: 'Qué hacer',
  notes: 'Avisos y límites',
  fullResult: 'Resultado completo (result.json)',
  severity: { high: 'IMPORTANTE', medium: 'A REVISAR', low: 'DETALLE', info: 'DATO' } as Record<string, string>,
  ball: 'Balón',
  mask: 'Máscara',
  computedLines: 'Líneas calculadas',
  marks: 'Marcas',
  frame: 'cuadro',
  overlap: '– (traslape)',
  objects: 'objetos',
  pitchState: (s: string) => ` · cancha: ${s}`,
  notComputed: 'sin calcular',
  timelineLegend: 'Posesión por segundo, como la cuenta el worker; en gris, lo descartado sin cancha (toca para saltar)',
  topDownHelp: 'Desde arriba, con la homografía del cuadro: los jugadores por sus pies, en sombra lo que ve la cámara y, en violeta, cuánto se corre cada marca en la cancha.',
  topDownNoPitch: ' Este cuadro no tiene la cancha calculada.',
  topDownLabel: 'La cancha vista desde arriba',
  metrics: 'Métricas',
  possession: 'Posesión',
  playersPerFrame: 'Jugadores por cuadro',
  tracks: 'Tracks',
  longestPossession: 'Posesión más larga',
  statsLine: (ball: string, changes: number, chunks: number, secs: number, overlap: number) =>
    `Balón visto ${ball} · ${changes} cambios de posesión · ${chunks} chunks de ${secs} s (traslape ${overlap} ms)`,
  // Calibration
  pitchInMeters: 'Cancha en metros',
  calLine: (size: string, l: string, w: string, coach: boolean, still: boolean, pct: string) =>
    `Cancha de ${size} de ${l}×${w} m (${coach ? 'medidas del entrenador' : 'medidas por omisión'}) · cámara ${still ? 'fija' : 'en movimiento'} · calculada en ${pct} de los cuadros`,
  method: { manual: 'marcada', static: 'cámara fija', propagated: 'seguida', meta_sam: 'con SAM' } as Record<string, string>,
  medianError: (px: number) => ` · error mediano de las marcas ${px} px`,
  samLines: (yes: boolean) => ` · líneas de SAM: ${yes ? 'sí' : 'no'}`,
  keyHead: ['Cuadro marcado', 'Puntos', 'Líneas', 'Coinciden', 'Error mediano', 'Usado'],
  yes: 'sí',
  no: 'no',
  marksHelp: (median: string, max: string) =>
    `Cada marca contra donde la homografía de su cuadro pone ese punto: en la imagen y en la cancha. Mediana ${median}, máximo ${max}. En el video, la marca es el punto blanco y la línea violeta llega a donde la pone la homografía.`,
  marksHead: ['Marca', 'Cuadro', 'En la imagen', 'En la cancha'],
  // Compare
  loadingTwo: 'Cargando las dos corridas…',
  notSameVideo: 'Las dos corridas no tienen los mismos chunks ni los mismos cuadros: la comparación cuadro por cuadro no es válida.',
  side: 'Lado a lado',
  overlay: 'Superpuestas',
  diff: 'Diferencias',
  inBoth: 'en las dos',
  only: 'solo',
  overlayLegend: (a: string, b: string) => `${a} con línea continua, ${b} punteada; colores de equipo.`,
  onlyRun: (n: string) => `Solo ${n}`,
  diffLegend: 'En gris, lo que encontraron las dos (IoU ≥ 0.3); el balón en círculo',
  sideLegend: 'El video de la derecha sigue al de la izquierda; usa los controles de la izquierda.',
  players: 'jugadores',
  secondsTitle: (top: string) => `Jugadores por cuadro (promedio por segundo; máximo ${top})`,
  ballSeenBy: (n: string) => `Balón visto · ${n}`,
  segmenter: 'Segmentador',
  processing: 'Tiempo de proceso',
  samCost: 'Costo de SAM',
  noTeam: 'Sin equipo',
  boxHeight: 'Alto medio de la caja',
  trackMedian: 'Duración mediana del track',
  longTracks: 'Cajas en tracks de 60 s o más',
  ballDetected: 'Balón detectado',
  ballSeenFill: 'Balón visto (con relleno)',
  possessionOf: (h: string, a: string) => `Posesión ${h} / ${a}`,
  possessionChanges: 'Cambios de posesión',
  compareMetricsHelp: 'Medido igual en las dos desde sus cajas (tracks.json), salvo posesión, cambios y tiempo, que vienen de cada result.json.',
  agreement: 'Coincidencia cuadro por cuadro',
  agMatched: 'Jugadores que encontraron las dos (IoU ≥ 0.3)',
  agAlsoFound: (a: string, b: string) => `De ${a} que también encontró ${b}`,
  agOnly: (a: string, b: string) => `Solo ${a} / solo ${b}`,
  agSameTeam: 'Mismo equipo en las que encontraron las dos',
  agBall: (a: string, b: string) => `Cuadros con balón: las dos / solo ${a} / solo ${b}`,
  agHelp: 'Usa "Diferencias" en el video para ver en qué cuadros y dónde difieren.',
  segmenterName: { local_cv: 'CV local', sam_simulator: 'Simulador', run: 'Corrida' } as Record<string, string>,
};

export type Texts = typeof es;

const en: Texts = {
  locale: 'en-US',
  subtitle: "Worker runs viewer",
  runs: 'Runs',
  noRuns: 'No runs. Run the worker with DEBUG_DIR or `analyze --debug-dir`.',
  today: 'Today',
  yesterday: 'Yesterday',
  undated: 'No date',
  home: 'Home',
  away: 'Away',
  compare: 'Compare',
  frames: 'frames',
  compareHelp: 'Tick two runs of the same video (a local segmenter against SAM, or before and after a change).',
  pickRun: 'Pick a run.',
  loading: 'Loading…',
  devBanner: (s: string) => `Development analysis: ${s}, not SAM 3.1`,
  findings: 'Findings',
  noFindings: 'No findings.',
  whatToDo: 'What to do',
  notes: 'Notes and limits',
  fullResult: 'Full result (result.json)',
  severity: { high: 'IMPORTANT', medium: 'TO REVIEW', low: 'DETAIL', info: 'DATA' },
  ball: 'Ball',
  mask: 'Mask',
  computedLines: 'Computed lines',
  marks: 'Marks',
  frame: 'frame',
  overlap: '– (overlap)',
  objects: 'objects',
  pitchState: (s: string) => ` · pitch: ${s}`,
  notComputed: 'not computed',
  timelineLegend: 'Possession per second, as the worker counts it; in gray, what was discarded without the pitch (tap to jump)',
  topDownHelp: "From above, with the frame's homography: the players by their feet, in shade what the camera sees and, in violet, how far each mark lands on the pitch.",
  topDownNoPitch: ' This frame has no pitch computed.',
  topDownLabel: 'The pitch seen from above',
  metrics: 'Metrics',
  possession: 'Possession',
  playersPerFrame: 'Players per frame',
  tracks: 'Tracks',
  longestPossession: 'Longest possession',
  statsLine: (ball: string, changes: number, chunks: number, secs: number, overlap: number) =>
    `Ball seen ${ball} · ${changes} possession changes · ${chunks} chunks of ${secs} s (overlap ${overlap} ms)`,
  pitchInMeters: 'Pitch in meters',
  calLine: (size: string, l: string, w: string, coach: boolean, still: boolean, pct: string) =>
    `${size}-a-side pitch of ${l}×${w} m (${coach ? "coach's measures" : 'default size'}) · ${still ? 'still' : 'moving'} camera · computed in ${pct} of the frames`,
  method: { manual: 'marked', static: 'still camera', propagated: 'followed', meta_sam: 'with SAM' },
  medianError: (px: number) => ` · median error of the marks ${px} px`,
  samLines: (yes: boolean) => ` · SAM lines: ${yes ? 'yes' : 'no'}`,
  keyHead: ['Marked frame', 'Points', 'Lines', 'Agree', 'Median error', 'Used'],
  yes: 'yes',
  no: 'no',
  marksHelp: (median: string, max: string) =>
    `Each mark against where its frame's homography puts that point: in the image and on the pitch. Median ${median}, max ${max}. In the video, the mark is the white dot and the violet line goes to where the homography puts it.`,
  marksHead: ['Mark', 'Frame', 'In the image', 'On the pitch'],
  loadingTwo: 'Loading both runs…',
  notSameVideo: 'The two runs do not have the same chunks or frames: the frame-by-frame comparison is not valid.',
  side: 'Side by side',
  overlay: 'Overlaid',
  diff: 'Differences',
  inBoth: 'in both',
  only: 'only',
  overlayLegend: (a: string, b: string) => `${a} with a solid line, ${b} dashed; team colors.`,
  onlyRun: (n: string) => `Only ${n}`,
  diffLegend: 'In gray, what both found (IoU ≥ 0.3); the ball as a circle',
  sideLegend: 'The right video follows the left one; use the controls on the left.',
  players: 'players',
  secondsTitle: (top: string) => `Players per frame (average per second; max ${top})`,
  ballSeenBy: (n: string) => `Ball seen · ${n}`,
  segmenter: 'Segmenter',
  processing: 'Processing time',
  samCost: 'SAM cost',
  noTeam: 'No team',
  boxHeight: 'Mean box height',
  trackMedian: 'Median track length',
  longTracks: 'Boxes in tracks of 60 s or more',
  ballDetected: 'Ball detected',
  ballSeenFill: 'Ball seen (with fill)',
  possessionOf: (h: string, a: string) => `Possession ${h} / ${a}`,
  possessionChanges: 'Possession changes',
  compareMetricsHelp: 'Measured the same way in both from their boxes (tracks.json), except possession, changes and time, which come from each result.json.',
  agreement: 'Frame-by-frame agreement',
  agMatched: 'Players both found (IoU ≥ 0.3)',
  agAlsoFound: (a: string, b: string) => `Of ${a}'s, also found by ${b}`,
  agOnly: (a: string, b: string) => `Only ${a} / only ${b}`,
  agSameTeam: 'Same team among those both found',
  agBall: (a: string, b: string) => `Frames with the ball: both / only ${a} / only ${b}`,
  agHelp: 'Use "Differences" on the video to see in which frames and where they differ.',
  segmenterName: { local_cv: 'Local CV', sam_simulator: 'Simulator', run: 'Run' },
};

export const TEXTS: Record<Lang, Texts> = { es, en };

export const LangContext = createContext<{ lang: Lang; t: Texts }>({ lang: 'es', t: es });

export function useT(): Texts {
  return useContext(LangContext).t;
}
