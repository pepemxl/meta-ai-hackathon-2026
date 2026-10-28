'use strict';

// Contract tests of the PHP API over HTTP, .htaccess included:
//   npm run php:up && npm run php:migrate && npm run test:php
// The same contract as tests/api.test.js (Node). Accounts are created with
// bin/user.php and the database is read and cleaned with the mysql client,
// both inside Docker.

const { test, beforeEach, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const path = require('path');
const { execFileSync } = require('child_process');
const request = require('supertest');
const jwt = require('jsonwebtoken');
const h = require('../helpers');

const BASE = process.env.PHP_API_URL || 'http://127.0.0.1:8097/pp-coach-tracker';
// Same API with PP_COACH_TRACKER_REQUIRE_META=1 and MAX_CHUNK_BYTES=12 MB (php/docker/Dockerfile).
const STRICT = BASE.replace(/\/pp-coach-tracker$/, '/pp-coach-tracker-strict');
const API_DIR = path.join(__dirname, '..', '..');
const MB = 1024 * 1024;

function compose(args, env = {}) {
  const envArgs = Object.entries(env).flatMap(([k, v]) => ['-e', `${k}=${v}`]);
  return execFileSync('wsl', ['-e', 'docker', 'compose', 'exec', '-T', ...envArgs, ...args], {
    cwd: API_DIR,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

/** Runs SQL in the MySQL container; rows split by lines (never trim: the last column may be empty). */
function sql(query) {
  const out = compose(['mysql', 'mysql', '-N', '-uppcoachtracker', '-pppcoachtracker-dev', 'ppcoachtracker', '-e', query]);
  return out.split('\n').filter((l) => l !== '').map((l) => l.split('\t'));
}

const TEST_COACHES = "coach_id LIKE 'test-%' OR coach_id IN (SELECT CONCAT('u', id) FROM users WHERE email LIKE '%@pp-test.example')";
function wipe() {
  sql(`DELETE FROM clips WHERE ${TEST_COACHES}; DELETE FROM matches WHERE ${TEST_COACHES}; DELETE FROM consent_events WHERE ${TEST_COACHES};
       DELETE FROM users WHERE email LIKE '%@pp-test.example'; DELETE FROM login_failures WHERE email LIKE '%@pp-test.example';`);
}

function addUser(email, name, password) {
  return compose(['php', 'php', 'bin/user.php', 'add', email, name], { PP_COACH_TRACKER_PASSWORD: password });
}

beforeEach(wipe);
after(wipe);

const api = (base = BASE) => request(base);

async function register(body = h.clipBody(), auth = h.COACH1, base = BASE) {
  return api(base).post('/clips').set('Authorization', auth).send(body);
}

async function putPart(clipId, n, offset, buf, auth = h.COACH1, base = BASE) {
  return api(base)
    .put(`/clips/${clipId}/chunks/${n}/video?offset=${offset}`)
    .set('Authorization', auth)
    .set('Content-Type', 'application/octet-stream')
    .send(buf);
}

async function finishChunk(clipId, n, hash, yamlHash = hash, base = BASE) {
  return api(base)
    .put(`/clips/${clipId}/chunks/${n}`)
    .set('Authorization', h.COACH1)
    .field('sha256', hash)
    .attach('yaml', Buffer.from(h.chunkYaml(n, yamlHash)), 'video_part.yaml');
}

async function uploadChunk(clipId, n, buf, partSize = buf.length, base = BASE) {
  for (let off = 0; off < buf.length; off += partSize) {
    const r = await putPart(clipId, n, off, buf.subarray(off, off + partSize), h.COACH1, base);
    assert.equal(r.status, 200, JSON.stringify(r.body));
  }
  const r = await finishChunk(clipId, n, h.sha(buf), h.sha(buf), base);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r;
}

async function complete(clipId, base = BASE) {
  return api(base).post(`/clips/${clipId}/complete`).set('Authorization', h.COACH1).attach('index', Buffer.from('%YAML:1.0\n---\nversion: 1\ncount: 2\n'), 'chunks.yaml');
}

async function readyClip(base = BASE) {
  const id = (await register(h.clipBody(), h.COACH1, base)).body.clip_id;
  await uploadChunk(id, 1, Buffer.from('chunk-one'), undefined, base);
  await uploadChunk(id, 2, Buffer.from('chunk-two'), undefined, base);
  const done = await complete(id, base);
  assert.equal(done.status, 200, JSON.stringify(done.body));
  return { id, jobId: done.body.job_id };
}

/** Claims jobs until it gets [jobId] (other suites' leftovers are failed out of the way). */
async function claim(jobId, base = BASE) {
  for (let i = 0; i < 20; i++) {
    const r = await api(base).get('/internal/jobs/next').set('Authorization', h.WORKER);
    if (r.status === 204) return null;
    if (r.body.job_id === jobId) return r;
    await api(base).post(`/internal/jobs/${r.body.job_id}/result`).set('Authorization', h.WORKER).send({ status: 'failed', error: 'test cleanup' });
  }
  return null;
}

test('health reports version, db, authentication and consent version', async () => {
  const anon = await api().get('/health');
  assert.equal(anon.status, 200);
  assert.deepEqual({ ...anon.body, version: 'x' }, { ok: true, version: 'x', db: true, authenticated: false, consent_version: '2026-10-27' });
  assert.equal((await api().get('/health').set('Authorization', h.COACH1)).body.authenticated, true);
});

test('every route needs a token; worker routes need the worker secret', async () => {
  const coachRoutes = [
    ['post', '/clips'], ['put', '/clips/1/chunks/1/video?offset=0'], ['put', '/clips/1/chunks/1'],
    ['post', '/clips/1/complete'], ['get', '/clips/1'], ['get', '/clips/1/analysis'], ['post', '/consent'], ['get', '/consent'],
  ];
  for (const [method, url] of coachRoutes) {
    const r = await api()[method](url).set('Authorization', 'Bearer nope-nope-nope-nope');
    assert.equal(r.status, 401, `${method} ${url}`);
    assert.equal(r.body.error, 'invalid or missing token');
  }
  for (const [method, url] of [['get', '/internal/jobs/next'], ['get', '/internal/clips/1/files/chunks.yaml'], ['post', '/internal/jobs/1/result']]) {
    assert.equal((await api()[method](url).set('Authorization', h.COACH1)).status, 401, `${method} ${url}`);
  }
});

test('register is idempotent and validated', async () => {
  const body = h.clipBody();
  const a = await register(body);
  assert.equal(a.status, 200);
  assert.equal(a.body.status, 'registered');
  assert.deepEqual(a.body.received, []);
  assert.deepEqual(a.body.partial, []);
  assert.equal((await register(body)).body.clip_id, a.body.clip_id);
  const bad = await register({ ...h.clipBody(), title: '', duration_ms: 7 * 3600 * 1000, chunk_count: 0, match: { home: 'x' } });
  assert.equal(bad.status, 400);
  assert.equal(bad.body.error, 'invalid fields');
  for (const f of ['title', 'duration_ms', 'chunk_count', 'match.client_match_id', 'match.away']) assert.ok(bad.body.fields.includes(f), f);
});

test('chunk_seconds and overlap_ms outside the profiles are rejected', async () => {
  for (const [field, value] of [['chunk_seconds', 30], ['chunk_seconds', 301], ['overlap_ms', 1000], ['overlap_ms', 6000]]) {
    const r = await register(h.clipBody({ [field]: value }));
    assert.equal(r.status, 400, `${field}=${value}`);
    assert.deepEqual(r.body.fields, [field]);
  }
  assert.equal((await register(h.clipBody({ chunk_seconds: 300, overlap_ms: 5000 }))).status, 200);
});

test('a chunk uploads in several parts, closes with its sha256 and completes the clip', async () => {
  const id = (await register()).body.clip_id;
  const video = Buffer.alloc(2 * MB + 123, 7);
  assert.deepEqual((await putPart(id, 1, 0, video.subarray(0, MB))).body, { number: 1, received_bytes: MB });
  assert.equal((await api().get(`/clips/${id}`).set('Authorization', h.COACH1)).body.status, 'uploading');
  await putPart(id, 1, MB, video.subarray(MB, 2 * MB));
  assert.equal((await putPart(id, 1, 2 * MB, video.subarray(2 * MB))).body.received_bytes, video.length);
  assert.deepEqual((await finishChunk(id, 1, h.sha(video))).body, { number: 1, sha256: h.sha(video), size_bytes: video.length });
  const missing = await complete(id);
  assert.equal(missing.status, 409);
  assert.deepEqual(missing.body, { error: 'missing chunks', missing: [2] });
  await uploadChunk(id, 2, Buffer.from('second chunk'));
  const ok = await complete(id);
  assert.equal(ok.status, 200);
  assert.equal(ok.body.status, 'queued');
  assert.ok(ok.body.job_id > 0);
  assert.deepEqual(ok.body.received.map((c) => c.number), [1, 2]);
});

test('a 60 MB chunk (5 min at 720p) uploads in 8 MB parts with production limits', async () => {
  const id = (await register(h.clipBody({ chunk_count: 1, chunk_seconds: 300, overlap_ms: 3000 }))).body.clip_id;
  const video = crypto.randomBytes(60 * MB);
  const r = await uploadChunk(id, 1, video, 8 * MB);
  assert.equal(r.body.size_bytes, 60 * MB);
  assert.equal(r.body.sha256, h.sha(video));
});

test('a wrong sha256 drops the assembled chunk and stores nothing', async () => {
  const id = (await register()).body.clip_id;
  const video = Buffer.from('some video bytes');
  await putPart(id, 1, 0, video);
  const r = await finishChunk(id, 1, 'a'.repeat(64));
  assert.equal(r.status, 422);
  assert.equal(r.body.error, 'sha256 mismatch');
  assert.equal(r.body.got, h.sha(video));
  await putPart(id, 1, 0, video);
  assert.equal((await finishChunk(id, 1, h.sha(video), 'a'.repeat(64))).status, 422);
  const state = await api().get(`/clips/${id}`).set('Authorization', h.COACH1);
  assert.deepEqual(state.body.received, []);
  assert.deepEqual(state.body.partial, []);
});

test('resuming mid-chunk: a wrong offset answers 409 with the expected offset', async () => {
  const body = h.clipBody();
  const id = (await register(body)).body.clip_id;
  const video = Buffer.alloc(3000, 1);
  await putPart(id, 1, 0, video.subarray(0, 1000));
  const again = await putPart(id, 1, 0, video.subarray(0, 1000));
  assert.equal(again.status, 409);
  assert.deepEqual(again.body, { error: 'offset mismatch', expected_offset: 1000 });
  assert.equal((await putPart(id, 1, 2000, video.subarray(2000))).body.expected_offset, 1000);
  assert.deepEqual((await register(body)).body.partial, [{ number: 1, received_bytes: 1000 }]);
  await putPart(id, 1, 1000, video.subarray(1000));
  assert.equal((await finishChunk(id, 1, h.sha(video))).status, 200);
});

test('closing a chunk without a partial upload is a conflict', async () => {
  const id = (await register()).body.clip_id;
  const r = await finishChunk(id, 1, 'b'.repeat(64));
  assert.equal(r.status, 409);
  assert.equal(r.body.error, 'no partial upload');
  assert.equal((await api().put(`/clips/${id}/chunks/1`).set('Authorization', h.COACH1).field('sha256', 'b'.repeat(64))).status, 400);
});

test('invalid chunk numbers, missing files, another coach, large parts and chunks', async () => {
  const id = (await register()).body.clip_id;
  for (const n of ['0', '3', 'abc']) {
    const r = await putPart(id, n, 0, Buffer.from('x'));
    assert.equal(r.status, 400, n);
    assert.equal(r.body.error, 'invalid chunk number');
  }
  assert.equal((await putPart(id, 1, 'x', Buffer.from('x'))).status, 400);
  assert.equal((await putPart(id, 1, 0, Buffer.alloc(0))).status, 400);
  assert.equal((await putPart(999999, 1, 0, Buffer.from('x'))).status, 404);
  assert.equal((await putPart(id, 1, 0, Buffer.from('x'), h.COACH2)).status, 404);
  assert.equal((await api().get(`/clips/${id}`).set('Authorization', h.COACH2)).status, 404);
  assert.equal((await api().get(`/clips/${id}/analysis`).set('Authorization', h.COACH2)).status, 404);

  const big = await putPart(id, 1, 0, Buffer.alloc(9 * MB));
  assert.equal(big.status, 413);
  assert.equal(big.body.error, 'part too large');
  // The strict copy caps chunks at 12 MB.
  const sid = (await register(h.clipBody(), h.COACH1, STRICT)).body.clip_id;
  assert.equal((await putPart(sid, 1, 0, Buffer.alloc(8 * MB), h.COACH1, STRICT)).status, 200);
  const tooMuch = await putPart(sid, 1, 8 * MB, Buffer.alloc(5 * MB), h.COACH1, STRICT);
  assert.equal(tooMuch.status, 413);
  assert.equal(tooMuch.body.error, 'chunk too large');

  assert.equal((await api().post(`/clips/${id}/complete`).set('Authorization', h.COACH1)).status, 400);
  assert.equal((await api().get(`/internal/clips/${id}/files/..%2F.env`).set('Authorization', h.WORKER)).status, 404);
  assert.equal((await api().get(`/internal/clips/${id}/files/video_part_002.mp4`).set('Authorization', h.WORKER)).status, 404);
});

test('changing the chunk profile discards the chunks and partials', async () => {
  const body = h.clipBody();
  const id = (await register(body)).body.clip_id;
  await uploadChunk(id, 1, Buffer.from('dev chunk'));
  await putPart(id, 2, 0, Buffer.from('half'));
  const demo = await register({ ...body, chunk_count: 1, chunk_seconds: 300, overlap_ms: 3000 });
  assert.equal(demo.body.clip_id, id);
  assert.equal(demo.body.status, 'registered');
  assert.equal(demo.body.chunk_seconds, 300);
  assert.deepEqual(demo.body.received, []);
  assert.deepEqual(demo.body.partial, []);
});

test('with REQUIRE_META a simulator result is rejected and the clip fails', async () => {
  const { id, jobId } = await readyClip(STRICT);
  assert.ok(await claim(jobId, STRICT));
  const r = await api(STRICT).post(`/internal/jobs/${jobId}/result`).set('Authorization', h.WORKER)
    .send({ status: 'done', analysis: { backends: { segmenter: 'sam_simulator', analyst: 'muse_spark' } } });
  assert.equal(r.status, 422);
  assert.equal(r.body.error, 'simulator result rejected');
  const state = await api(STRICT).get(`/clips/${id}`).set('Authorization', h.COACH1);
  assert.equal(state.body.status, 'failed');
  assert.match(state.body.job.error, /simulator/);
});

test('API_TOKENS parsing: malformed and short tokens are not accepted', async () => {
  // The container has "test-coach1:test-token-0123456789,test-coach2:other-coach-token-9876".
  assert.equal((await api().get('/consent').set('Authorization', 'Bearer test-coach1:test-token-0123456789')).status, 401);
  assert.equal((await api().get('/consent').set('Authorization', 'Bearer test-token-012345678')).status, 401);
  assert.equal((await api().get('/consent').set('Authorization', h.COACH2)).status, 200);
});

test('login returns a JWT that works on the coach routes; wrong passwords are limited', async () => {
  const email = `coach-${process.pid}@pp-test.example`;
  addUser(email, 'Coach Uno', 'correct-horse-9');
  const ok = await api().post('/auth/login').send({ email: email.toUpperCase(), password: 'correct-horse-9' });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.email, email);
  assert.equal(ok.body.name, 'Coach Uno');
  const claims = jwt.verify(ok.body.token, h.JWT_SECRET, { algorithms: ['HS256'] });
  assert.match(claims.sub, /^u\d+$/);
  assert.equal(claims.exp - claims.iat, 30 * 24 * 3600);
  assert.equal((await register(h.clipBody(), `Bearer ${ok.body.token}`)).status, 200);

  assert.equal((await api().post('/auth/login').send({ email })).status, 400);
  for (let i = 0; i < 10; i++) assert.equal((await api().post('/auth/login').send({ email, password: 'wrong' })).status, 401);
  const limited = await api().post('/auth/login').send({ email, password: 'correct-horse-9' });
  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers['retry-after']) > 0);
  sql(`DELETE FROM login_failures WHERE email = '${email}'`);
  assert.equal((await api().post('/auth/login').send({ email, password: 'correct-horse-9' })).status, 200);
});

test('a token signed by the Node API works on the PHP API', async () => {
  const email = `interop-${process.pid}@pp-test.example`;
  addUser(email, 'Coach Interop', 'correct-horse-9');
  const id = sql(`SELECT id FROM users WHERE email = '${email}'`)[0][0];
  // Exactly what api/src/auth.js signs (jsonwebtoken, HS256, 30 days).
  const nodeToken = jwt.sign({ sub: `u${id}`, email }, h.JWT_SECRET, { algorithm: 'HS256', expiresIn: '30d' });
  const r = await register(h.clipBody(), `Bearer ${nodeToken}`);
  assert.equal(r.status, 200);
  assert.equal(sql(`SELECT coach_id FROM clips WHERE id = ${r.body.clip_id}`)[0][0], `u${id}`);
});

test('forged or expired JWTs are rejected', async () => {
  const forged = jwt.sign({ sub: 'u1' }, 'another-secret-0123456789abcdefghijk', { algorithm: 'HS256' });
  const expired = jwt.sign({ sub: 'u1', exp: Math.floor(Date.now() / 1000) - 10 }, h.JWT_SECRET, { algorithm: 'HS256' });
  const none = `${Buffer.from('{"alg":"none"}').toString('base64url')}.${Buffer.from('{"sub":"u1"}').toString('base64url')}.`;
  for (const t of [forged, expired, none]) assert.equal((await api().get('/consent').set('Authorization', `Bearer ${t}`)).status, 401);
});

test('consent: five rejections, stored in UTC, the same event only once', async () => {
  const cases = [
    { consent: undefined },
    { consent: { version: '2025-01-01', accepted_at: h.ACCEPTED_AT } },
    { consent: { version: '2026-10-27', accepted_at: 'yesterday' } },
    { consent: { version: '2026-10-27', accepted_at: new Date(Date.now() + 3 * 86400000).toISOString() } },
  ];
  for (const c of cases) {
    const r = await register(h.clipBody(c));
    assert.equal(r.status, 428);
    assert.deepEqual(r.body, { error: 'consent required', consent_version: '2026-10-27' });
  }
  await api().post('/consent').set('Authorization', h.COACH1).send({ action: 'withdraw', version: '2026-10-27', at: '2026-10-27T16:00:00Z' });
  const withdrawn = await register(h.clipBody());
  assert.equal(withdrawn.status, 428);
  assert.equal(withdrawn.body.error, 'consent withdrawn');

  const accept = await api().post('/consent').set('Authorization', h.COACH2).send({ action: 'accept', version: '2026-10-27', at: '2026-10-27T10:04:05.999-06:00', app_version: '0.1.0' });
  assert.equal(accept.status, 200);
  assert.equal(accept.body.at, '2026-10-27T16:04:05.000Z');
  assert.equal(sql("SELECT at FROM consent_events WHERE coach_id = 'test-coach2'")[0][0], '2026-10-27 16:04:05');
  const again = await api().post('/consent').set('Authorization', h.COACH2).send({ action: 'accept', version: '2026-10-27', at: '2026-10-27T16:04:05Z' });
  assert.equal(again.body.id, accept.body.id);
  const hist = await api().get('/consent').set('Authorization', h.COACH2);
  assert.equal(hist.body.events.length, 1);
  assert.equal(hist.body.events[0].at, '2026-10-27T16:04:05.000Z');
  for (const bad of [{ action: 'maybe', version: '2026-10-27', at: h.ACCEPTED_AT }, { action: 'accept', version: '2025-01-01', at: h.ACCEPTED_AT }, { action: 'withdraw', version: 'bad version!', at: h.ACCEPTED_AT }]) {
    assert.equal((await api().post('/consent').set('Authorization', h.COACH2).send(bad)).status, 400);
  }
});

test('consent history with a withdrawal and a new acceptance', async () => {
  const get = async () => (await api().get('/consent').set('Authorization', h.COACH1)).body;
  assert.equal((await get()).current, 'none');
  await register(h.clipBody());
  assert.equal((await get()).current, 'accepted');
  await api().post('/consent').set('Authorization', h.COACH1).send({ action: 'withdraw', version: '2026-10-27', at: '2026-10-27T18:00:00Z' });
  assert.equal((await get()).current, 'withdrawn');
  assert.equal((await register(h.clipBody({ consent: { version: '2026-10-27', accepted_at: '2026-10-27T19:00:00Z' } }))).status, 200);
  const hist = await get();
  assert.equal(hist.current, 'accepted');
  assert.deepEqual(hist.events.map((e) => e.action), ['accept', 'withdraw', 'accept']);
});

test('worker claims a job, reports the result and the clip changes state', async () => {
  const { id, jobId } = await readyClip();
  const next = await claim(jobId);
  assert.ok(next);
  assert.equal(next.body.type, 'segment');
  assert.equal((await api().get(`/clips/${id}`).set('Authorization', h.COACH1)).body.status, 'segmenting');
  const failed = await api().post(`/internal/jobs/${jobId}/result`).set('Authorization', h.WORKER).send({ status: 'failed', error: 'Meta API: 503 after 4 attempts' });
  assert.equal(failed.status, 200);
  const state = await api().get(`/clips/${id}`).set('Authorization', h.COACH1);
  assert.equal(state.body.status, 'failed');
  assert.equal(state.body.job.error, 'Meta API: 503 after 4 attempts');
  assert.equal((await api().post(`/internal/jobs/${jobId}/result`).set('Authorization', h.WORKER).send({ status: 'done' })).status, 409);
  assert.equal((await api().post('/internal/jobs/999999/result').set('Authorization', h.WORKER).send({ status: 'done' })).status, 404);
});

test('worker gets the match, downloads the files and posts a 100 KB analysis; only the owner reads it', async () => {
  const { id, jobId } = await readyClip();
  const next = await claim(jobId);
  assert.deepEqual(next.body.match, { home: 'Tigres Sub-12', away: 'Halcones', home_color: 'red', away_color: 'blue', date: '2026-10-25', venue: 'Cancha 3' });
  assert.deepEqual(next.body.clip.files, ['chunks.yaml', 'video_part_001.mp4', 'video_part_001.yaml', 'video_part_002.mp4', 'video_part_002.yaml']);
  const mp4 = await api().get(`/internal/clips/${id}/files/video_part_002.mp4`).set('Authorization', h.WORKER).buffer(true).parse((res, cb) => {
    const parts = [];
    res.on('data', (d) => parts.push(d));
    res.on('end', () => cb(null, Buffer.concat(parts)));
  });
  assert.equal(mp4.status, 200);
  assert.equal(mp4.body.toString(), 'chunk-two');
  assert.match((await api().get(`/internal/clips/${id}/files/chunks.yaml`).set('Authorization', h.WORKER)).text, /count: 2/);

  const notJson = await api().post(`/internal/jobs/${jobId}/result`).set('Authorization', h.WORKER).field('status', 'done').attach('analysis', Buffer.from('[1,2]'), 'analysis.json');
  assert.equal(notJson.status, 400);
  const analysis = { backends: { segmenter: 'meta_sam', analyst: 'muse_spark' }, padding: 'x'.repeat(100 * 1024) };
  const r = await api().post(`/internal/jobs/${jobId}/result`).set('Authorization', h.WORKER).field('status', 'done').attach('analysis', Buffer.from(JSON.stringify(analysis)), 'analysis.json');
  assert.equal(r.status, 200, JSON.stringify(r.body));
  const read = await api().get(`/clips/${id}/analysis`).set('Authorization', h.COACH1);
  assert.equal(read.status, 200);
  assert.equal(read.body.segmenter, 'meta_sam');
  assert.equal(read.body.analyst, 'muse_spark');
  assert.equal(read.body.job_id, jobId);
  assert.equal(read.body.result.padding.length, 100 * 1024);
  assert.equal((await api().get(`/clips/${id}`).set('Authorization', h.COACH1)).body.status, 'ready');
  assert.equal((await api().get(`/clips/${id}/analysis`).set('Authorization', h.COACH2)).status, 404);
});

test('completing twice does not create another job', async () => {
  const { id, jobId } = await readyClip();
  assert.equal((await complete(id)).body.job_id, jobId);
  assert.ok(await claim(jobId));
  assert.equal((await complete(id)).body.job_id, jobId);
});

test('a job stuck in running goes back to the queue', async () => {
  const { jobId } = await readyClip();
  assert.ok(await claim(jobId));
  sql(`UPDATE jobs SET started_at = UTC_TIMESTAMP() - INTERVAL 3 HOUR WHERE id = ${jobId}`);
  const again = await claim(jobId);
  assert.ok(again, 'the stale job is claimed again');
  assert.equal(sql(`SELECT status FROM jobs WHERE id = ${jobId}`)[0][0], 'running');
});

const CAL = {
  pitch: { size: 7, length_m: 60, width_m: 40 },
  frames: [{ t_ms: 1200, points: [
    { name: 'corner_far_left', x: 0.1, y: 0.2 }, { name: 'corner_far_right', x: 0.9, y: 0.2 },
    { name: 'corner_near_left', x: 0.05, y: 0.9 }, { name: 'corner_near_right', x: 0.95, y: 0.9 }, { name: 'center', x: 0.5, y: 0.5 },
  ] }],
};

test('pitch_mode is validated, stored and given to the worker', async () => {
  const bad = await register(h.clipBody({ analysis: { pitch_mode: 'maybe' } }));
  assert.equal(bad.status, 400);
  assert.deepEqual(bad.body.fields, ['analysis.pitch_mode']);
  assert.equal((await register(h.clipBody())).body.pitch_mode, null);
  const reg = await register(h.clipBody({ analysis: { pitch_mode: 'no_lines' } }));
  assert.equal(reg.body.pitch_mode, 'no_lines');
  const id = reg.body.clip_id;
  assert.equal((await api().post(`/clips/${id}/calibration`).set('Authorization', h.COACH1).send(CAL)).status, 200);
  await uploadChunk(id, 1, Buffer.from('a'));
  await uploadChunk(id, 2, Buffer.from('b'));
  const done = await complete(id);
  const next = await claim(done.body.job_id);
  assert.deepEqual(next.body.options, { pitch_mode: 'no_lines' });
  assert.deepEqual(next.body.calibration, CAL);
});

test('calibration: validated, only the owner, 409 once the worker took the clip', async () => {
  const { id, jobId } = await readyClip();
  const post = (body, auth = h.COACH1) => api().post(`/clips/${id}/calibration`).set('Authorization', auth).send(body);
  const pts = CAL.frames[0].points;
  const cases = [
    { ...CAL, pitch: { size: 9 } },
    { ...CAL, pitch: { size: 11, length_m: 500 } },
    { ...CAL, frames: [] },
    { ...CAL, frames: [{ points: pts }] },
    { ...CAL, frames: [{ t_ms: 0, points: pts.slice(0, 3) }] },
    { ...CAL, frames: [{ t_ms: 0, points: [...pts.slice(0, 4), { ...pts[0] }] }] },
    { ...CAL, frames: [{ t_ms: 0, points: [...pts.slice(0, 4), { name: 'x', x: 1.5, y: 0 }] }] },
    { ...CAL, frames: [{ t_ms: 0, points: [...pts.slice(0, 4), { name: 'Bad Name', x: 0, y: 0 }] }] },
  ];
  for (const c of cases) {
    const r = await post(c);
    assert.equal(r.status, 400, JSON.stringify(c).slice(0, 80));
    assert.equal(r.body.error, 'invalid calibration');
  }
  assert.equal((await post(CAL, h.COACH2)).status, 404);
  assert.deepEqual((await post({ ...CAL, frames: [{ frame: 12, points: pts }] })).body, { clip_id: id, frames: 1, points: 5 });
  const next = await claim(jobId);
  assert.equal(next.body.calibration.frames[0].frame, 12);
  assert.equal(next.body.options.pitch_mode, null);
  const late = await post(CAL);
  assert.equal(late.status, 409);
  assert.equal(late.body.status, 'segmenting');
});
