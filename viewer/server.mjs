// Local server of the viewer: the built page (dist/) and the worker's saved
// runs (RUNS_DIR, read-only). RUNS_DIR may list several folders separated by
// commas; a run is any folder, up to 3 levels down, with result.json and
// tracks.json, and its id is its path joined with "~" (sam~run~2026…).
// Chunks that are not H.264 (tools/make_chunks.py writes MPEG-4) are
// converted once with ffmpeg into CACHE_DIR so the browser can play them.
// Only for the development machine: never deployed.
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOTS = (process.env.RUNS_DIR || path.join(here, '..', 'worker', '.local', 'runs')).split(',').map((d) => path.resolve(d.trim())).filter(Boolean);
const CACHE = path.resolve(process.env.CACHE_DIR || path.join(here, '.cache'));
const DIST = path.join(here, 'dist');
const PORT = Number(process.env.PORT || 8098);
const HOST = process.env.HOST || '127.0.0.1';
const SAFE = /^[\w.~-]+$/;
const DEPTH = 3;
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.mp4': 'video/mp4', '.yaml': 'text/yaml' };

function send(res, code, body, type = 'application/json') {
  res.writeHead(code, { 'Content-Type': type });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

/** Serves a file with Range support (the video element seeks with it). */
function sendFile(req, res, file) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) return send(res, 404, { error: 'not found' });
    const type = TYPES[path.extname(file)] || 'application/octet-stream';
    const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
    if (range) {
      const start = range[1] ? Number(range[1]) : 0;
      const end = range[2] ? Number(range[2]) : st.size - 1;
      res.writeHead(206, { 'Content-Type': type, 'Content-Range': `bytes ${start}-${end}/${st.size}`, 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1 });
      return fs.createReadStream(file, { start, end }).pipe(res);
    }
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Accept-Ranges': 'bytes' });
    fs.createReadStream(file).pipe(res);
  });
}

/** Every run under the roots: id → folder. With several roots, a repeated id gets the root's name in front. */
function findRuns() {
  const found = new Map();
  const walk = (root, dir, rel, depth) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    if (rel.length && entries.some((e) => e.name === 'result.json') && entries.some((e) => e.name === 'tracks.json')) {
      let id = rel.join('~');
      if (found.has(id)) id = `${path.basename(root)}~${id}`;
      if (SAFE.test(id)) found.set(id, dir);
      return;
    }
    if (depth >= DEPTH) return;
    for (const e of entries) if (e.isDirectory() && SAFE.test(e.name) && !e.name.startsWith('.') && e.name !== 'chunks') walk(root, path.join(dir, e.name), [...rel, e.name], depth + 1);
  };
  for (const root of ROOTS) walk(root, root, [], 0);
  return found;
}

let runDirs = new Map();
const runDir = (id) => {
  if (!runDirs.has(id)) runDirs = findRuns();
  return runDirs.get(id);
};

function listRuns() {
  runDirs = findRuns();
  return [...runDirs].map(([id, dir]) => {
    const r = JSON.parse(fs.readFileSync(path.join(dir, 'result.json'), 'utf8'));
    return {
      id, clip_id: r.clip_id, segmenter: r.backends?.segmenter, frames: r.frames, chunks: r.video?.chunks, duration_ms: r.video?.duration_ms,
      generated_at: r.generated_at, match: r.match,
    };
  }).sort((a, b) => ((a.generated_at || '') < (b.generated_at || '') ? 1 : -1));
}

const converting = new Map();
/** A browser-playable copy of a chunk (H.264 as is; anything else converted once). */
function playable(run, file) {
  const dir = runDir(run);
  // The worker copies the chunks into chunks/; older runs keep them next to result.json.
  const src = fs.existsSync(path.join(dir, 'chunks', file)) ? path.join(dir, 'chunks', file) : path.join(dir, file);
  const yaml = src.replace(/\.mp4$/, '.yaml');
  const codec = fs.existsSync(yaml) ? (/codec:\s*"?([\w]+)/.exec(fs.readFileSync(yaml, 'utf8')) || [])[1] : 'h264';
  if (codec === 'h264') return Promise.resolve(src);
  const out = path.join(CACHE, run, file);
  if (fs.existsSync(out)) return Promise.resolve(out);
  if (!converting.has(out)) {
    fs.mkdirSync(path.dirname(out), { recursive: true });
    converting.set(out, new Promise((resolve, reject) => {
      const p = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-i', src, '-an', '-c:v', 'libx264', '-preset', 'veryfast', '-pix_fmt', 'yuv420p', `${out}.tmp.mp4`]);
      p.on('error', reject);
      p.on('exit', (code) => {
        converting.delete(out);
        if (code === 0) {
          fs.renameSync(`${out}.tmp.mp4`, out);
          resolve(out);
        } else reject(new Error(`ffmpeg exited with ${code}`));
      });
    }));
  }
  return converting.get(out);
}

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const parts = url.pathname.split('/').filter(Boolean);
  try {
    if (parts[0] === 'api') {
      if (parts[1] === 'runs' && parts.length === 2) return send(res, 200, listRuns());
      const [, , run, kind, file] = parts;
      if (parts[1] !== 'runs' || !SAFE.test(run || '') || !runDir(run)) return send(res, 404, { error: 'not found' });
      if ((kind === 'result.json' || kind === 'tracks.json') && !file) return sendFile(req, res, path.join(runDir(run), kind));
      if (kind === 'video' && SAFE.test(file || '') && file.endsWith('.mp4')) return sendFile(req, res, await playable(run, file));
      return send(res, 404, { error: 'not found' });
    }
    const rel = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
    const file = path.join(DIST, path.normalize(rel));
    if (!file.startsWith(DIST)) return send(res, 404, { error: 'not found' });
    return sendFile(req, res, fs.existsSync(file) ? file : path.join(DIST, 'index.html'));
  } catch (e) {
    send(res, 500, { error: String(e.message || e) });
  }
}).listen(PORT, HOST, () => console.log(`viewer on http://${HOST}:${PORT} (runs in ${ROOTS.join(', ')})`));
