// Local server of the viewer: the built page (dist/) and the worker's saved
// runs (RUNS_DIR, read-only). Chunks that are not H.264 (tools/make_chunks.py
// writes MPEG-4) are converted once with ffmpeg into CACHE_DIR so the browser
// can play them. Only for the development machine: never deployed.
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const RUNS = path.resolve(process.env.RUNS_DIR || path.join(here, '..', 'worker', '.local', 'runs'));
const CACHE = path.resolve(process.env.CACHE_DIR || path.join(here, '.cache'));
const DIST = path.join(here, 'dist');
const PORT = Number(process.env.PORT || 8098);
const HOST = process.env.HOST || '127.0.0.1';
const SAFE = /^[\w.-]+$/;
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

function listRuns() {
  if (!fs.existsSync(RUNS)) return [];
  return fs.readdirSync(RUNS, { withFileTypes: true })
    .filter((d) => d.isDirectory() && fs.existsSync(path.join(RUNS, d.name, 'result.json')))
    .map((d) => {
      const r = JSON.parse(fs.readFileSync(path.join(RUNS, d.name, 'result.json'), 'utf8'));
      return { id: d.name, clip_id: r.clip_id, segmenter: r.backends?.segmenter, frames: r.frames, chunks: r.video?.chunks, generated_at: r.generated_at, match: r.match };
    })
    .sort((a, b) => (a.id < b.id ? 1 : -1));
}

const converting = new Map();
/** A browser-playable copy of a chunk (H.264 as is; anything else converted once). */
function playable(run, file) {
  const src = path.join(RUNS, run, 'chunks', file);
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
      if (parts[1] !== 'runs' || !SAFE.test(run || '')) return send(res, 404, { error: 'not found' });
      if ((kind === 'result.json' || kind === 'tracks.json') && !file) return sendFile(req, res, path.join(RUNS, run, kind));
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
}).listen(PORT, HOST, () => console.log(`viewer on http://${HOST}:${PORT} (runs in ${RUNS})`));
