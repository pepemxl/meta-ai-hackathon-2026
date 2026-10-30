'use strict';

const fs = require('fs');
const path = require('path');
const express = require('express');
const multer = require('multer');
const storage = require('./storage');
const { verifyPassword, signToken, verifyToken, matchStaticToken, safeEqual } = require('./auth');
const { CONSENT_VERSION, parseClipConsent, parseConsentEvent, currentState, iso } = require('./consent');
const { MAX_REFERENCES, parsePitchMode, parseCamera, parseCalibration } = require('./calibration');

const VERSION = require('../package.json').version;

// Limits of POST /clips (docs/12-videos-largos-chunks.md): profiles dev = 60 s
// and demo = 300 s, overlap 2 to 5 s.
const LIMITS = {
  maxDurationMs: 6 * 3600 * 1000,
  chunkCount: [1, 1000],
  chunkSeconds: [60, 300],
  overlapMs: [2000, 5000],
};
const LOGIN_MAX_FAILURES = 10;
const LOGIN_WINDOW_MS = 15 * 60 * 1000;
// Uploads only while the clip is not being analyzed.
const UPLOAD_BLOCKED = ['queued', 'segmenting'];
// The coach's points can change until the worker takes the clip.
const CALIBRATION_OPEN = ['registered', 'uploading', 'queued'];
// A picture of the pitch the coach took (or a frame), compressed by the app.
const MAX_REFERENCE_BYTES = 2 * 1024 * 1024;

/**
 * The pp-coach-tracker API (contract in api/README.md):
 *
 *   GET  /health                           server, database and token check
 *   POST /auth/login                       email + password → JWT (30 days)
 *   POST /clips                            register (idempotent), needs consent → what is already here;
 *                                          analysis.pitch_mode: whether to compute the pitch;
 *                                          analysis.camera: static (one homography) or moving
 *   POST /clips/:id/calibration            the coach's points and painted lines on the pitch; 409 once the worker took the clip
 *   PUT  /clips/:id/references/:n          a reference picture of the pitch (JPEG, n = 1..6); 409 once the worker took the clip
 *   PUT  /clips/:id/chunks/:n/video        one raw part of a chunk at ?offset=
 *   PUT  /clips/:id/chunks/:n              close a chunk: yaml + sha256, checked on the assembled video
 *   POST /clips/:id/complete               chunks.yaml; all chunks there → analysis job queued
 *   GET  /clips/:id                        status, received and partial chunks
 *   GET  /clips/:id/analysis               the analysis the worker produced
 *   POST /consent, GET /consent            consent events and history
 *   GET  /internal/jobs/next               worker: claim the oldest queued job
 *   GET  /internal/clips/:id/files/:name   worker: download a chunk, its YAML, chunks.yaml or a reference picture
 *   POST /internal/jobs/:id/result         worker: done (with the analysis) or failed
 */
function createApp({
  repo,
  storageDir,
  tokens = [],
  jwtSecret,
  workerSecret,
  maxPartBytes = 8 * 1024 * 1024,
  maxChunkBytes = 160 * 1024 * 1024,
  requireMeta = false,
  now = () => Date.now(),
}) {
  if (!jwtSecret || jwtSecret.length < 32) throw new Error('JWT_SECRET must be at least 32 characters');
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 'loopback');

  const tmpDir = path.join(storageDir, 'tmp');
  fs.mkdirSync(tmpDir, { recursive: true });
  // Small multipart bodies only: the video goes as raw parts.
  const smallMultipart = multer({ dest: tmpDir, limits: { fileSize: 4 * 1024 * 1024, fieldSize: 4 * 1024 * 1024, files: 2, fields: 6 } });
  const analysisMultipart = multer({ dest: tmpDir, limits: { fileSize: 16 * 1024 * 1024, files: 1, fields: 4 } });
  const json = express.json({ limit: '64kb' });

  const fail = (res, status, error, extra = {}) => res.status(status).json({ error, ...extra });
  const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

  // ── auth ──────────────────────────────────────────────────────────────────
  function coachFor(req) {
    const m = /^Bearer (.+)$/.exec(req.get('authorization') || '');
    if (!m) return null;
    return verifyToken(jwtSecret, m[1]) || matchStaticToken(tokens, m[1]);
  }

  function auth(req, res, next) {
    const coachId = coachFor(req);
    if (!coachId) return fail(res, 401, 'invalid or missing token');
    req.coachId = coachId;
    next();
  }

  function workerAuth(req, res, next) {
    const m = /^Bearer (.+)$/.exec(req.get('authorization') || '');
    if (!workerSecret || !m || !safeEqual(m[1], workerSecret)) return fail(res, 401, 'invalid or missing token');
    next();
  }

  // email → failure timestamps (PHP keeps them in login_failures).
  const loginFailures = new Map();
  function recentFailures(email) {
    const since = now() - LOGIN_WINDOW_MS;
    const list = (loginFailures.get(email) || []).filter((t) => t > since);
    loginFailures.set(email, list);
    return list;
  }

  /** The clip of :id if it belongs to the coach; otherwise answers 404 and returns null. */
  async function ownClip(req, res) {
    const id = Number(req.params.id);
    const clip = Number.isInteger(id) && id > 0 ? await repo.getClip(id) : null;
    if (!clip || clip.coach_id !== req.coachId) {
      fail(res, 404, 'clip not found');
      return null;
    }
    return clip;
  }

  function chunkNumber(req, res, clip) {
    const n = /^\d{1,4}$/.test(req.params.n) ? Number(req.params.n) : NaN;
    if (!(n >= 1 && n <= clip.chunk_count)) {
      fail(res, 400, 'invalid chunk number', { chunk_count: clip.chunk_count });
      return null;
    }
    return n;
  }

  async function clipState(clip) {
    const chunks = await repo.listChunks(clip.id);
    return {
      clip_id: clip.id,
      status: clip.status,
      chunk_count: clip.chunk_count,
      chunk_seconds: clip.chunk_seconds,
      overlap_ms: clip.overlap_ms,
      pitch_mode: clip.pitch_mode ?? null,
      camera: clip.camera ?? null,
      video_deleted: Boolean(clip.video_deleted_at),
      received: chunks.map((c) => ({ number: c.number, sha256: c.sha256 })),
      partial: storage.partials(storageDir, clip.id),
    };
  }

  const cleanup = (files) => {
    for (const f of files || []) fs.rm(f.path, { force: true }, () => {});
  };
  const multerFiles = (req) => (req.files ? (Array.isArray(req.files) ? req.files : Object.values(req.files).flat()) : req.file ? [req.file] : []);
  /** A multipart value sent as a file or as a text field. */
  const partText = (req, name) => {
    const file = multerFiles(req).find((f) => f.fieldname === name);
    if (file) return fs.readFileSync(file.path, 'utf8');
    return typeof req.body?.[name] === 'string' ? req.body[name] : null;
  };

  // ── public ────────────────────────────────────────────────────────────────
  app.get('/health', wrap(async (req, res) => {
    res.json({ ok: true, version: VERSION, db: await repo.ping(), authenticated: !!coachFor(req), consent_version: CONSENT_VERSION });
  }));

  app.post('/auth/login', json, wrap(async (req, res) => {
    const email = typeof req.body?.email === 'string' ? req.body.email.trim().toLowerCase() : '';
    const password = typeof req.body?.password === 'string' ? req.body.password : '';
    if (!email || !password || email.length > 190) return fail(res, 400, 'email and password required');
    const failures = recentFailures(email);
    if (failures.length >= LOGIN_MAX_FAILURES) {
      const retry = Math.max(1, Math.ceil((failures[0] + LOGIN_WINDOW_MS - now()) / 1000));
      res.set('Retry-After', String(retry));
      return fail(res, 429, 'too many attempts', { retry_after: retry });
    }
    const user = await repo.findUserByEmail(email);
    if (!user || !verifyPassword(password, user.password_hash)) {
      failures.push(now());
      return fail(res, 401, 'invalid credentials');
    }
    res.json({ token: signToken(jwtSecret, user), email: user.email, name: user.name });
  }));

  // ── clips ─────────────────────────────────────────────────────────────────
  app.post('/clips', auth, json, wrap(async (req, res) => {
    const b = req.body || {};
    const m = b.match || {};
    const bad = [];
    const text = (v, key, min = 1) => {
      if (typeof v !== 'string' || v.trim().length < min || v.length > 160) bad.push(key);
    };
    const int = (v, key, lo, hi) => {
      if (!Number.isInteger(v) || v < lo || v > hi) bad.push(key);
    };
    text(b.client_clip_id, 'client_clip_id');
    text(b.title, 'title');
    int(b.duration_ms, 'duration_ms', 1, LIMITS.maxDurationMs);
    int(b.chunk_count, 'chunk_count', ...LIMITS.chunkCount);
    int(b.chunk_seconds, 'chunk_seconds', ...LIMITS.chunkSeconds);
    int(b.overlap_ms, 'overlap_ms', ...LIMITS.overlapMs);
    text(m.client_match_id, 'match.client_match_id');
    text(m.home, 'match.home');
    text(m.away, 'match.away');
    text(m.home_color, 'match.home_color');
    text(m.away_color, 'match.away_color');
    text(m.date, 'match.date');
    text(m.venue ?? '', 'match.venue', 0);
    const pitchMode = parsePitchMode(b.analysis);
    if (pitchMode.error) bad.push('analysis.pitch_mode');
    const camera = parseCamera(b.analysis);
    if (camera.error) bad.push('analysis.camera');
    if (bad.length) return fail(res, 400, 'invalid fields', { fields: bad });

    const consent = parseClipConsent(b.consent, now());
    if (!consent) return fail(res, 428, 'consent required', { consent_version: CONSENT_VERSION });
    const events = await repo.listConsentEvents(req.coachId);
    const lastWithdraw = events.filter((e) => e.action === 'withdraw').pop();
    if (lastWithdraw && consent.at.getTime() <= new Date(lastWithdraw.at).getTime()) {
      return fail(res, 428, 'consent withdrawn', { consent_version: CONSENT_VERSION });
    }
    const eventId = await repo.addConsentEvent(req.coachId, { action: 'accept', ...consent }, req.ip);

    const matchId = await repo.upsertMatch(req.coachId, { ...m, venue: m.venue ?? '' });
    let clip = await repo.findClip(req.coachId, b.client_clip_id);
    if (!clip) {
      const id = await repo.createClip({
        coach_id: req.coachId, client_clip_id: b.client_clip_id, match_id: matchId, title: b.title,
        duration_ms: b.duration_ms, chunk_count: b.chunk_count, chunk_seconds: b.chunk_seconds, overlap_ms: b.overlap_ms,
        pitch_mode: pitchMode.value, camera: camera.value,
      });
      clip = await repo.getClip(id);
    } else {
      const profileChanged = clip.chunk_count !== b.chunk_count || clip.chunk_seconds !== b.chunk_seconds || clip.overlap_ms !== b.overlap_ms;
      const fields = { title: b.title, duration_ms: b.duration_ms, match_id: matchId };
      // The answer is fixed once the worker took the clip.
      if (!UPLOAD_BLOCKED.includes(clip.status)) Object.assign(fields, { pitch_mode: pitchMode.value, camera: camera.value });
      if (profileChanged && !UPLOAD_BLOCKED.includes(clip.status)) {
        // The app regenerated the chunks with another profile: the old ones are useless.
        await repo.deleteChunks(clip.id);
        storage.wipeClip(storageDir, clip.id);
        Object.assign(fields, { chunk_count: b.chunk_count, chunk_seconds: b.chunk_seconds, overlap_ms: b.overlap_ms, status: 'registered' });
      }
      await repo.updateClip(clip.id, fields);
      clip = await repo.getClip(clip.id);
    }
    await repo.linkClipConsent(clip.id, eventId);
    res.json(await clipState(clip));
  }));

  app.post('/clips/:id/calibration', auth, express.json({ limit: '256kb' }), wrap(async (req, res) => {
    const clip = await ownClip(req, res);
    if (!clip) return;
    if (!CALIBRATION_OPEN.includes(clip.status)) return fail(res, 409, 'clip already taken by the worker', { status: clip.status });
    const cal = parseCalibration(req.body);
    if (cal.error) return fail(res, 400, 'invalid calibration', { detail: cal.error });
    await repo.updateClip(clip.id, { calibration: JSON.stringify(cal.value) });
    res.json({
      clip_id: clip.id, frames: cal.value.frames.length, points: cal.value.frames.reduce((n, f) => n + f.points.length, 0),
      lines: cal.value.frames.reduce((n, f) => n + (f.lines || []).length, 0), references: (cal.value.references || []).length,
    });
  }));

  app.put('/clips/:id/references/:n', auth, express.raw({ type: () => true, limit: MAX_REFERENCE_BYTES }), wrap(async (req, res) => {
    const clip = await ownClip(req, res);
    if (!clip) return;
    const n = Number(req.params.n);
    if (!/^\d+$/.test(req.params.n) || n < 1 || n > MAX_REFERENCES) return fail(res, 404, 'reference not found');
    if (!CALIBRATION_OPEN.includes(clip.status)) return fail(res, 409, 'clip already taken by the worker', { status: clip.status });
    const body = Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0);
    if (body.length < 4 || body[0] !== 0xff || body[1] !== 0xd8 || body[2] !== 0xff) return fail(res, 400, 'reference must be a JPEG');
    const dir = storage.clipDir(storageDir, clip.id);
    fs.mkdirSync(dir, { recursive: true });
    const tmp = path.join(tmpDir, `ref_${clip.id}_${n}_${process.pid}_${Date.now()}`);
    fs.writeFileSync(tmp, body);
    storage.moveInto(tmp, path.join(dir, storage.referenceName(n)));
    res.json({ clip_id: clip.id, n, bytes: body.length });
  }));

  app.put('/clips/:id/chunks/:n/video', auth, express.raw({ type: () => true, limit: maxPartBytes }), wrap(async (req, res) => {
    const clip = await ownClip(req, res);
    if (!clip) return;
    const n = chunkNumber(req, res, clip);
    if (n === null) return;
    if (UPLOAD_BLOCKED.includes(clip.status)) return fail(res, 409, 'clip is being analyzed', { status: clip.status });
    if (!/^\d{1,12}$/.test(String(req.query.offset ?? ''))) return fail(res, 400, 'invalid offset');
    const offset = Number(req.query.offset);
    const body = Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0);
    if (!body.length) return fail(res, 400, 'empty part');

    const have = storage.partialSize(storageDir, clip.id, n);
    if (offset !== have) return fail(res, 409, 'offset mismatch', { expected_offset: have });
    if (have + body.length > maxChunkBytes) return fail(res, 413, 'chunk too large', { max_chunk_bytes: maxChunkBytes });

    const dir = storage.clipDir(storageDir, clip.id);
    fs.mkdirSync(dir, { recursive: true });
    fs.appendFileSync(path.join(dir, storage.partialName(n)), body);
    if (clip.status === 'registered' || clip.status === 'failed' || clip.status === 'ready') {
      // A clip sent again gets its video back (retention had deleted it).
      await repo.updateClip(clip.id, { status: 'uploading', video_deleted_at: null });
    }
    res.json({ number: n, received_bytes: have + body.length });
  }));

  app.put('/clips/:id/chunks/:n', auth, smallMultipart.fields([{ name: 'yaml', maxCount: 1 }]), wrap(async (req, res) => {
    const files = multerFiles(req);
    try {
      const clip = await ownClip(req, res);
      if (!clip) return;
      const n = chunkNumber(req, res, clip);
      if (n === null) return;
      if (UPLOAD_BLOCKED.includes(clip.status)) return fail(res, 409, 'clip is being analyzed', { status: clip.status });
      const yaml = partText(req, 'yaml');
      const expected = String(req.body?.sha256 || '').toLowerCase();
      if (!yaml) return fail(res, 400, 'yaml required');
      if (!/^[0-9a-f]{64}$/.test(expected)) return fail(res, 400, 'invalid sha256');

      const dir = storage.clipDir(storageDir, clip.id);
      const partial = path.join(dir, storage.partialName(n));
      if (!fs.existsSync(partial)) return fail(res, 409, 'no partial upload');
      const got = await storage.sha256File(partial);
      const declared = storage.declaredSha(yaml);
      if (got !== expected || declared !== got) {
        fs.rmSync(partial, { force: true });
        return fail(res, 422, 'sha256 mismatch', { expected, got });
      }
      const size = fs.statSync(partial).size;
      fs.renameSync(partial, path.join(dir, storage.mp4Name(n)));
      fs.writeFileSync(path.join(dir, storage.yamlName(n)), yaml);
      await repo.putChunk(clip.id, n, got, size);
      res.json({ number: n, sha256: got, size_bytes: size });
    } finally {
      cleanup(files);
    }
  }));

  app.post('/clips/:id/complete', auth, smallMultipart.fields([{ name: 'index', maxCount: 1 }]), wrap(async (req, res) => {
    const files = multerFiles(req);
    try {
      const clip = await ownClip(req, res);
      if (!clip) return;
      const index = partText(req, 'index');
      if (!index) return fail(res, 400, 'index required');
      const received = new Set((await repo.listChunks(clip.id)).map((c) => c.number));
      const missing = [];
      for (let i = 1; i <= clip.chunk_count; i++) if (!received.has(i)) missing.push(i);
      if (missing.length) return fail(res, 409, 'missing chunks', { missing });

      fs.writeFileSync(path.join(storage.clipDir(storageDir, clip.id), storage.INDEX), index);
      const open = await repo.openJob(clip.id);
      const jobId = open ? open.id : await repo.createJob(clip.id);
      if (!open) await repo.updateClip(clip.id, { status: 'queued' });
      res.json({ ...(await clipState(await repo.getClip(clip.id))), job_id: jobId });
    } finally {
      cleanup(files);
    }
  }));

  app.get('/clips/:id', auth, wrap(async (req, res) => {
    const clip = await ownClip(req, res);
    if (!clip) return;
    const job = await repo.lastJob(clip.id);
    res.json({
      ...(await clipState(clip)),
      title: clip.title,
      duration_ms: clip.duration_ms,
      job: job ? { id: job.id, status: job.status, error: job.error || null } : null,
    });
  }));

  app.get('/clips/:id/analysis', auth, wrap(async (req, res) => {
    const clip = await ownClip(req, res);
    if (!clip) return;
    const a = await repo.getAnalysis(clip.id);
    if (!a) return fail(res, 404, 'analysis not found', { status: clip.status });
    res.json({ clip_id: clip.id, job_id: a.job_id, segmenter: a.segmenter, analyst: a.analyst, created_at: iso(a.created_at), result: JSON.parse(a.result) });
  }));

  // ── consent ───────────────────────────────────────────────────────────────
  app.post('/consent', auth, json, wrap(async (req, res) => {
    const ev = parseConsentEvent(req.body, now());
    if (ev.error) return fail(res, 400, ev.error, { consent_version: CONSENT_VERSION });
    const id = await repo.addConsentEvent(req.coachId, ev, req.ip);
    res.json({ id, action: ev.action, version: ev.version, at: iso(ev.at) });
  }));

  app.get('/consent', auth, wrap(async (req, res) => {
    const events = await repo.listConsentEvents(req.coachId);
    res.json({
      consent_version: CONSENT_VERSION,
      current: currentState(events),
      events: events.map((e) => ({ action: e.action, version: e.version, at: iso(e.at), received_at: iso(e.received_at), app_version: e.app_version || null })),
    });
  }));

  // ── worker ────────────────────────────────────────────────────────────────
  app.get('/internal/jobs/next', workerAuth, wrap(async (req, res) => {
    const claimed = await repo.claimNextJob();
    if (!claimed) return res.status(204).end();
    const { job, clip, match } = claimed;
    const files = [storage.INDEX];
    for (let i = 1; i <= clip.chunk_count; i++) files.push(storage.mp4Name(i), storage.yamlName(i));
    const references = storage.references(storageDir, clip.id);
    res.json({
      job_id: job.id,
      type: job.type,
      clip: {
        id: clip.id, title: clip.title, duration_ms: clip.duration_ms, chunk_count: clip.chunk_count,
        chunk_seconds: clip.chunk_seconds, overlap_ms: clip.overlap_ms,
        dir: path.basename(storage.clipDir(storageDir, clip.id)), index: storage.INDEX, files, references,
      },
      options: { pitch_mode: clip.pitch_mode ?? null, camera: clip.camera ?? null },
      calibration: clip.calibration ? JSON.parse(clip.calibration) : null,
      match: match ? { home: match.home, away: match.away, home_color: match.home_color, away_color: match.away_color, date: match.match_date, venue: match.venue } : null,
    });
  }));

  app.get('/internal/clips/:id/files/:name', workerAuth, wrap(async (req, res) => {
    const name = req.params.name;
    const id = Number(req.params.id);
    if (!storage.FILE_PATTERN.test(name) || !Number.isInteger(id)) return fail(res, 404, 'file not found');
    const file = path.join(storage.clipDir(storageDir, id), name);
    if (!fs.existsSync(file)) return fail(res, 404, 'file not found');
    const type = name.endsWith('.mp4') ? 'video/mp4' : name.endsWith('.jpg') ? 'image/jpeg' : 'text/yaml';
    res.type(type).sendFile(path.resolve(file));
  }));

  app.post('/internal/jobs/:id/result', workerAuth, (req, res, next) => {
    if (req.is('multipart/form-data')) return analysisMultipart.single('analysis')(req, res, next);
    return json(req, res, next);
  }, wrap(async (req, res) => {
    const files = multerFiles(req);
    try {
      const job = await repo.getJob(Number(req.params.id));
      if (!job) return fail(res, 404, 'job not found');
      if (job.status !== 'running') return fail(res, 409, 'job not running', { status: job.status });
      const status = req.body?.status;
      if (status !== 'done' && status !== 'failed') return fail(res, 400, 'invalid status');
      const error = typeof req.body?.error === 'string' ? req.body.error.slice(0, 500) : null;

      if (status === 'failed') {
        await repo.finishJob(job.id, 'failed', error || 'unknown error');
        await repo.updateClip(job.clip_id, { status: 'failed' });
        return res.json({ job_id: job.id, status: 'failed' });
      }

      let text = null;
      if (req.file) text = fs.readFileSync(req.file.path, 'utf8');
      else if (req.body?.analysis !== undefined) text = typeof req.body.analysis === 'string' ? req.body.analysis : JSON.stringify(req.body.analysis);
      if (text !== null) {
        let result;
        try {
          result = JSON.parse(text);
        } catch (e) {
          result = null;
        }
        if (!result || typeof result !== 'object' || Array.isArray(result)) return fail(res, 400, 'analysis must be a JSON object');
        const backends = result.backends || {};
        const segmenter = typeof backends.segmenter === 'string' ? backends.segmenter : null;
        const analyst = typeof backends.analyst === 'string' ? backends.analyst : null;
        if (requireMeta && segmenter !== 'meta_sam') {
          await repo.finishJob(job.id, 'failed', `simulator result rejected (segmenter ${segmenter})`);
          await repo.updateClip(job.clip_id, { status: 'failed' });
          return fail(res, 422, 'simulator result rejected', { segmenter });
        }
        await repo.saveAnalysis(job.clip_id, job.id, segmenter, analyst, text);
      }
      await repo.finishJob(job.id, 'done', null);
      await repo.updateClip(job.clip_id, { status: 'ready' });
      res.json({ job_id: job.id, status: 'done' });
    } finally {
      cleanup(files);
    }
  }));

  // ── errors ────────────────────────────────────────────────────────────────
  app.use((req, res) => fail(res, 404, 'not found'));
  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    if (err.type === 'entity.too.large' && /^\/clips\/\d+\/references\//.test(req.path)) {
      return fail(res, 413, 'reference too large', { max_reference_bytes: MAX_REFERENCE_BYTES });
    }
    if (err.type === 'entity.too.large' || err.code === 'LIMIT_FILE_SIZE' || err.code === 'LIMIT_FIELD_VALUE') {
      return fail(res, 413, 'part too large', { max_part_bytes: maxPartBytes });
    }
    if (err.type === 'entity.parse.failed') return fail(res, 400, 'invalid JSON');
    if (err instanceof multer.MulterError) return fail(res, 400, 'invalid multipart', { detail: err.code });
    console.error(err);
    fail(res, 500, 'internal error');
  });

  return app;
}

module.exports = { createApp, LIMITS, VERSION };
