'use strict';

const { test, beforeEach, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const request = require('supertest');
const { spawnSync } = require('child_process');
const jwt = require('jsonwebtoken');
const h = require('./helpers');
const { parseTokens, hashPassword } = require('../src/auth');
const { createMemoryRepo } = require('../src/repo/memory');

beforeEach(h.wipeTestData);
after(h.closeRepo);

const MB = 1024 * 1024;

async function register(app, body = h.clipBody(), auth = h.COACH1) {
  const res = await request(app).post('/clips').set('Authorization', auth).send(body);
  return res;
}

async function putPart(app, clipId, n, offset, buf, auth = h.COACH1) {
  return request(app)
    .put(`/clips/${clipId}/chunks/${n}/video?offset=${offset}`)
    .set('Authorization', auth)
    .set('Content-Type', 'application/octet-stream')
    .send(buf);
}

async function finishChunk(app, clipId, n, hash, yamlHash = hash, auth = h.COACH1) {
  return request(app)
    .put(`/clips/${clipId}/chunks/${n}`)
    .set('Authorization', auth)
    .field('sha256', hash)
    .attach('yaml', Buffer.from(h.chunkYaml(n, yamlHash)), 'video_part.yaml');
}

/** Uploads a whole chunk in parts of [partSize] and closes it. */
async function uploadChunk(app, clipId, n, buf, partSize = buf.length) {
  for (let off = 0; off < buf.length; off += partSize) {
    const r = await putPart(app, clipId, n, off, buf.subarray(off, off + partSize));
    assert.equal(r.status, 200, JSON.stringify(r.body));
  }
  const r = await finishChunk(app, clipId, n, h.sha(buf));
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r;
}

async function complete(app, clipId) {
  return request(app).post(`/clips/${clipId}/complete`).set('Authorization', h.COACH1).attach('index', Buffer.from('%YAML:1.0\n---\nversion: 1\ncount: 2\n'), 'chunks.yaml');
}

async function readyClip(app) {
  const reg = await register(app);
  const id = reg.body.clip_id;
  await uploadChunk(app, id, 1, Buffer.from('chunk-one'));
  await uploadChunk(app, id, 2, Buffer.from('chunk-two'));
  const done = await complete(app, id);
  assert.equal(done.status, 200, JSON.stringify(done.body));
  return { id, jobId: done.body.job_id };
}

test('health reports version, db, authentication and consent version', async () => {
  const { app } = h.makeApp();
  const anon = await request(app).get('/health');
  assert.equal(anon.status, 200);
  assert.equal(anon.body.ok, true);
  assert.equal(anon.body.db, true);
  assert.equal(anon.body.authenticated, false);
  assert.equal(anon.body.consent_version, '2026-10-27');
  const authed = await request(app).get('/health').set('Authorization', h.COACH1);
  assert.equal(authed.body.authenticated, true);
});

test('every route needs a token; worker routes need the worker secret', async () => {
  const { app } = h.makeApp();
  const coachRoutes = [
    ['post', '/clips'], ['put', '/clips/1/chunks/1/video?offset=0'], ['put', '/clips/1/chunks/1'],
    ['post', '/clips/1/complete'], ['get', '/clips/1'], ['get', '/clips/1/analysis'], ['post', '/consent'], ['get', '/consent'],
  ];
  for (const [method, url] of coachRoutes) {
    const r = await request(app)[method](url).set('Authorization', 'Bearer nope-nope-nope-nope');
    assert.equal(r.status, 401, `${method} ${url}`);
    assert.equal(r.body.error, 'invalid or missing token');
  }
  for (const [method, url] of [['get', '/internal/jobs/next'], ['get', '/internal/clips/1/files/chunks.yaml'], ['post', '/internal/jobs/1/result']]) {
    const r = await request(app)[method](url).set('Authorization', h.COACH1);
    assert.equal(r.status, 401, `${method} ${url}`);
  }
});

test('register is idempotent and validated', async () => {
  const { app } = h.makeApp();
  const body = h.clipBody();
  const a = await register(app, body);
  assert.equal(a.status, 200);
  assert.equal(a.body.status, 'registered');
  assert.deepEqual(a.body.received, []);
  assert.deepEqual(a.body.partial, []);
  const b = await register(app, body);
  assert.equal(b.body.clip_id, a.body.clip_id);

  const bad = await register(app, { ...h.clipBody(), title: '', duration_ms: 7 * 3600 * 1000, chunk_count: 0, match: { home: 'x' } });
  assert.equal(bad.status, 400);
  assert.equal(bad.body.error, 'invalid fields');
  for (const f of ['title', 'duration_ms', 'chunk_count', 'match.client_match_id', 'match.away']) assert.ok(bad.body.fields.includes(f), f);
});

test('chunk_seconds and overlap_ms outside the profiles are rejected', async () => {
  const { app } = h.makeApp();
  for (const [field, value] of [['chunk_seconds', 30], ['chunk_seconds', 301], ['overlap_ms', 1000], ['overlap_ms', 6000]]) {
    const r = await register(app, h.clipBody({ [field]: value }));
    assert.equal(r.status, 400, `${field}=${value}`);
    assert.deepEqual(r.body.fields, [field]);
  }
  const demo = await register(app, h.clipBody({ chunk_seconds: 300, overlap_ms: 5000 }));
  assert.equal(demo.status, 200);
});

test('a chunk uploads in several parts, closes with its sha256 and completes the clip', async () => {
  const { app, storageDir } = h.makeApp();
  const reg = await register(app);
  const id = reg.body.clip_id;
  const video = Buffer.alloc(2 * MB + 123, 7);
  const r1 = await putPart(app, id, 1, 0, video.subarray(0, MB));
  assert.deepEqual(r1.body, { number: 1, received_bytes: MB });
  assert.equal((await request(app).get(`/clips/${id}`).set('Authorization', h.COACH1)).body.status, 'uploading');
  await putPart(app, id, 1, MB, video.subarray(MB, 2 * MB));
  const r3 = await putPart(app, id, 1, 2 * MB, video.subarray(2 * MB));
  assert.equal(r3.body.received_bytes, video.length);
  const done = await finishChunk(app, id, 1, h.sha(video));
  assert.deepEqual(done.body, { number: 1, sha256: h.sha(video), size_bytes: video.length });
  const dir = fs.readdirSync(path.join(storageDir, `clip_${String(id).padStart(6, '0')}`)).sort();
  assert.deepEqual(dir, ['video_part_001.mp4', 'video_part_001.yaml']);

  const missing = await complete(app, id);
  assert.equal(missing.status, 409);
  assert.deepEqual(missing.body, { error: 'missing chunks', missing: [2] });

  await uploadChunk(app, id, 2, Buffer.from('second chunk'));
  const ok = await complete(app, id);
  assert.equal(ok.status, 200);
  assert.equal(ok.body.status, 'queued');
  assert.ok(ok.body.job_id > 0);
  assert.deepEqual(ok.body.received.map((c) => c.number), [1, 2]);
});

test('a wrong sha256 drops the assembled chunk and stores nothing', async () => {
  const { app } = h.makeApp();
  const id = (await register(app)).body.clip_id;
  const video = Buffer.from('some video bytes');
  await putPart(app, id, 1, 0, video);
  const wrong = 'a'.repeat(64);
  const r = await finishChunk(app, id, 1, wrong);
  assert.equal(r.status, 422);
  assert.equal(r.body.error, 'sha256 mismatch');
  assert.equal(r.body.got, h.sha(video));
  // The YAML disagreeing with the field also fails.
  await putPart(app, id, 1, 0, video);
  const r2 = await finishChunk(app, id, 1, h.sha(video), wrong);
  assert.equal(r2.status, 422);
  const state = await request(app).get(`/clips/${id}`).set('Authorization', h.COACH1);
  assert.deepEqual(state.body.received, []);
  assert.deepEqual(state.body.partial, []);
});

test('resuming mid-chunk: a wrong offset answers 409 with the expected offset', async () => {
  const { app } = h.makeApp();
  const body = h.clipBody();
  const id = (await register(app, body)).body.clip_id;
  const video = Buffer.alloc(3000, 1);
  await putPart(app, id, 1, 0, video.subarray(0, 1000));
  // The response of part 2 was lost; the app retries part 1.
  const again = await putPart(app, id, 1, 0, video.subarray(0, 1000));
  assert.equal(again.status, 409);
  assert.deepEqual(again.body, { error: 'offset mismatch', expected_offset: 1000 });
  const ahead = await putPart(app, id, 1, 2000, video.subarray(2000));
  assert.equal(ahead.body.expected_offset, 1000);
  // After closing the app: POST /clips says where to resume.
  const reg = await register(app, body);
  assert.deepEqual(reg.body.partial, [{ number: 1, received_bytes: 1000 }]);
  await putPart(app, id, 1, 1000, video.subarray(1000));
  assert.equal((await finishChunk(app, id, 1, h.sha(video))).status, 200);
});

test('closing a chunk without a partial upload is a conflict', async () => {
  const { app } = h.makeApp();
  const id = (await register(app)).body.clip_id;
  const r = await finishChunk(app, id, 1, 'b'.repeat(64));
  assert.equal(r.status, 409);
  assert.equal(r.body.error, 'no partial upload');
  const noYaml = await request(app).put(`/clips/${id}/chunks/1`).set('Authorization', h.COACH1).field('sha256', 'b'.repeat(64));
  assert.equal(noYaml.status, 400);
});

test('invalid chunk numbers, missing files, another coach, large parts and chunks', async () => {
  const { app } = h.makeApp({ maxPartBytes: 1000, maxChunkBytes: 1500 });
  const id = (await register(app)).body.clip_id;
  for (const n of ['0', '3', 'abc', '-1']) {
    const r = await putPart(app, id, n, 0, Buffer.from('x'));
    assert.equal(r.status, 400, n);
    assert.equal(r.body.error, 'invalid chunk number');
  }
  assert.equal((await putPart(app, id, 1, 'x', Buffer.from('x'))).status, 400);
  assert.equal((await putPart(app, id, 1, 0, Buffer.alloc(0))).status, 400);
  assert.equal((await putPart(app, 999999, 1, 0, Buffer.from('x'))).status, 404);
  assert.equal((await putPart(app, id, 1, 0, Buffer.from('x'), h.COACH2)).status, 404);
  assert.equal((await request(app).get(`/clips/${id}`).set('Authorization', h.COACH2)).status, 404);
  assert.equal((await request(app).get(`/clips/${id}/analysis`).set('Authorization', h.COACH2)).status, 404);

  const big = await putPart(app, id, 1, 0, Buffer.alloc(1001));
  assert.equal(big.status, 413);
  assert.equal(big.body.error, 'part too large');
  await putPart(app, id, 1, 0, Buffer.alloc(1000));
  const tooMuch = await putPart(app, id, 1, 1000, Buffer.alloc(600));
  assert.equal(tooMuch.status, 413);
  assert.equal(tooMuch.body.error, 'chunk too large');

  const noIndex = await request(app).post(`/clips/${id}/complete`).set('Authorization', h.COACH1);
  assert.equal(noIndex.status, 400);
  const w = await request(app).get(`/internal/clips/${id}/files/..%2F.env`).set('Authorization', h.WORKER);
  assert.equal(w.status, 404);
  assert.equal((await request(app).get(`/internal/clips/${id}/files/video_part_002.mp4`).set('Authorization', h.WORKER)).status, 404);
});

test('changing the chunk profile discards the chunks and partials', async () => {
  const { app } = h.makeApp();
  const body = h.clipBody();
  const id = (await register(app, body)).body.clip_id;
  await uploadChunk(app, id, 1, Buffer.from('dev chunk'));
  await putPart(app, id, 2, 0, Buffer.from('half'));
  const demo = await register(app, { ...body, chunk_count: 1, chunk_seconds: 300, overlap_ms: 3000 });
  assert.equal(demo.body.clip_id, id);
  assert.equal(demo.body.status, 'registered');
  assert.equal(demo.body.chunk_seconds, 300);
  assert.deepEqual(demo.body.received, []);
  assert.deepEqual(demo.body.partial, []);
});

test('with REQUIRE_META a simulator result is rejected and the clip fails', async () => {
  const { app } = h.makeApp({ requireMeta: true });
  const { id } = await readyClip(app);
  const job = await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER);
  const r = await request(app)
    .post(`/internal/jobs/${job.body.job_id}/result`)
    .set('Authorization', h.WORKER)
    .send({ status: 'done', analysis: { backends: { segmenter: 'sam_simulator', analyst: 'muse_spark' } } });
  assert.equal(r.status, 422);
  assert.equal(r.body.error, 'simulator result rejected');
  const state = await request(app).get(`/clips/${id}`).set('Authorization', h.COACH1);
  assert.equal(state.body.status, 'failed');
  assert.match(state.body.job.error, /simulator/);
  assert.equal((await request(app).get(`/clips/${id}/analysis`).set('Authorization', h.COACH1)).status, 404);
});

test('API_TOKENS parsing', () => {
  assert.deepEqual(parseTokens('a:0123456789abcdef, b:fedcba9876543210'), [
    { coachId: 'a', token: '0123456789abcdef' },
    { coachId: 'b', token: 'fedcba9876543210' },
  ]);
  assert.deepEqual(parseTokens(''), []);
  assert.deepEqual(parseTokens('nocolon,:x0123456789abcdef,c:short'), []);
  // The token may contain colons; only the first one separates.
  assert.deepEqual(parseTokens('c:abc:0123456789xyz'), [{ coachId: 'c', token: 'abc:0123456789xyz' }]);
});

test('login returns a JWT that works on the coach routes; wrong passwords are limited', async () => {
  let clock = Date.parse('2026-10-28T12:00:00Z');
  const { app, repo } = h.makeApp({ now: () => clock });
  const email = `coach-${process.pid}@pp-test.example`;
  await repo.createUser({ email, name: 'Coach Uno', passwordHash: hashPassword('correct-horse-9') });

  const ok = await request(app).post('/auth/login').send({ email: email.toUpperCase(), password: 'correct-horse-9' });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.email, email);
  assert.equal(ok.body.name, 'Coach Uno');
  const claims = jwt.decode(ok.body.token);
  assert.match(claims.sub, /^u\d+$/);
  assert.equal(claims.exp - claims.iat, 30 * 24 * 3600);
  const reg = await register(app, h.clipBody(), `Bearer ${ok.body.token}`);
  assert.equal(reg.status, 200);

  assert.equal((await request(app).post('/auth/login').send({ email })).status, 400);
  for (let i = 0; i < 10; i++) {
    const r = await request(app).post('/auth/login').send({ email, password: 'wrong' });
    assert.equal(r.status, 401);
  }
  const limited = await request(app).post('/auth/login').send({ email, password: 'correct-horse-9' });
  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers['retry-after']) > 0);
  clock += 15 * 60 * 1000 + 1000;
  assert.equal((await request(app).post('/auth/login').send({ email, password: 'correct-horse-9' })).status, 200);
});

test('forged or expired JWTs are rejected', async () => {
  const { app } = h.makeApp();
  const forged = jwt.sign({ sub: 'u1' }, 'another-secret-0123456789abcdefghijk', { algorithm: 'HS256' });
  const expired = jwt.sign({ sub: 'u1', exp: Math.floor(Date.now() / 1000) - 10 }, h.JWT_SECRET, { algorithm: 'HS256' });
  const none = `${Buffer.from('{"alg":"none"}').toString('base64url')}.${Buffer.from('{"sub":"u1"}').toString('base64url')}.`;
  for (const t of [forged, expired, none]) {
    assert.equal((await request(app).get('/consent').set('Authorization', `Bearer ${t}`)).status, 401);
  }
});

test('consent: five rejections, stored in UTC, the same event only once', async () => {
  const { app } = h.makeApp();
  const cases = [
    { consent: undefined },
    { consent: { version: '2025-01-01', accepted_at: h.ACCEPTED_AT } },
    { consent: { version: '2026-10-27', accepted_at: 'yesterday' } },
    { consent: { version: '2026-10-27', accepted_at: new Date(Date.now() + 3 * 86400000).toISOString() } },
  ];
  for (const c of cases) {
    const r = await register(app, h.clipBody(c));
    assert.equal(r.status, 428);
    assert.deepEqual(r.body, { error: 'consent required', consent_version: '2026-10-27' });
  }
  // Withdrawn after the acceptance the app reports → 428 consent withdrawn.
  await request(app).post('/consent').set('Authorization', h.COACH1).send({ action: 'withdraw', version: '2026-10-27', at: '2026-10-27T16:00:00Z' });
  const withdrawn = await register(app, h.clipBody());
  assert.equal(withdrawn.status, 428);
  assert.equal(withdrawn.body.error, 'consent withdrawn');

  // A time-zone offset is stored and returned in UTC.
  const accept = await request(app).post('/consent').set('Authorization', h.COACH2).send({ action: 'accept', version: '2026-10-27', at: '2026-10-27T10:04:05.999-06:00', app_version: '0.1.0' });
  assert.equal(accept.status, 200);
  assert.equal(accept.body.at, '2026-10-27T16:04:05.000Z');
  const again = await request(app).post('/consent').set('Authorization', h.COACH2).send({ action: 'accept', version: '2026-10-27', at: '2026-10-27T16:04:05Z' });
  assert.equal(again.body.id, accept.body.id);
  const hist = await request(app).get('/consent').set('Authorization', h.COACH2);
  assert.equal(hist.body.events.length, 1);
  assert.equal(hist.body.events[0].at, '2026-10-27T16:04:05.000Z');

  for (const bad of [{ action: 'maybe', version: '2026-10-27', at: h.ACCEPTED_AT }, { action: 'accept', version: '2025-01-01', at: h.ACCEPTED_AT }, { action: 'withdraw', version: 'bad version!', at: h.ACCEPTED_AT }]) {
    assert.equal((await request(app).post('/consent').set('Authorization', h.COACH2).send(bad)).status, 400);
  }
});

test('consent history with a withdrawal and a new acceptance', async () => {
  const { app } = h.makeApp();
  const get = async () => (await request(app).get('/consent').set('Authorization', h.COACH1)).body;
  assert.equal((await get()).current, 'none');
  await register(app, h.clipBody());
  assert.equal((await get()).current, 'accepted');
  // Withdrawing an older version is allowed.
  await request(app).post('/consent').set('Authorization', h.COACH1).send({ action: 'withdraw', version: '2026-10-27', at: '2026-10-27T18:00:00Z' });
  assert.equal((await get()).current, 'withdrawn');
  const re = await register(app, h.clipBody({ consent: { version: '2026-10-27', accepted_at: '2026-10-27T19:00:00Z' } }));
  assert.equal(re.status, 200);
  const hist = await get();
  assert.equal(hist.current, 'accepted');
  assert.deepEqual(hist.events.map((e) => e.action), ['accept', 'withdraw', 'accept']);
});

test('worker claims a job, reports the result and the clip changes state', async () => {
  const { app } = h.makeApp();
  assert.equal((await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER)).status, 204);
  const { id, jobId } = await readyClip(app);
  const next = await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER);
  assert.equal(next.status, 200);
  assert.equal(next.body.job_id, jobId);
  assert.equal(next.body.type, 'segment');
  assert.equal((await request(app).get(`/clips/${id}`).set('Authorization', h.COACH1)).body.status, 'segmenting');
  assert.equal((await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER)).status, 204);

  const failed = await request(app).post(`/internal/jobs/${jobId}/result`).set('Authorization', h.WORKER).send({ status: 'failed', error: 'Meta API: 503 after 4 attempts' });
  assert.equal(failed.status, 200);
  const state = await request(app).get(`/clips/${id}`).set('Authorization', h.COACH1);
  assert.equal(state.body.status, 'failed');
  assert.equal(state.body.job.error, 'Meta API: 503 after 4 attempts');
  assert.equal((await request(app).post(`/internal/jobs/${jobId}/result`).set('Authorization', h.WORKER).send({ status: 'done' })).status, 409);
  assert.equal((await request(app).post('/internal/jobs/999999/result').set('Authorization', h.WORKER).send({ status: 'done' })).status, 404);
});

test('worker gets the match, downloads the files and posts a 100 KB analysis; only the owner reads it', async () => {
  const { app } = h.makeApp();
  const { id, jobId } = await readyClip(app);
  const next = await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER);
  assert.deepEqual(next.body.match, { home: 'Tigres Sub-12', away: 'Halcones', home_color: 'red', away_color: 'blue', date: '2026-10-25', venue: 'Cancha 3' });
  assert.deepEqual(next.body.clip.files, ['chunks.yaml', 'video_part_001.mp4', 'video_part_001.yaml', 'video_part_002.mp4', 'video_part_002.yaml']);
  assert.equal(next.body.clip.chunk_seconds, 60);
  const mp4 = await request(app).get(`/internal/clips/${id}/files/video_part_002.mp4`).set('Authorization', h.WORKER).buffer(true).parse((res, cb) => {
    const parts = [];
    res.on('data', (d) => parts.push(d));
    res.on('end', () => cb(null, Buffer.concat(parts)));
  });
  assert.equal(mp4.status, 200);
  assert.equal(mp4.body.toString(), 'chunk-two');
  const index = await request(app).get(`/internal/clips/${id}/files/chunks.yaml`).set('Authorization', h.WORKER);
  assert.match(index.text, /count: 2/);

  const analysis = { backends: { segmenter: 'meta_sam', analyst: 'muse_spark' }, padding: 'x'.repeat(100 * 1024) };
  const notJson = await request(app).post(`/internal/jobs/${jobId}/result`).set('Authorization', h.WORKER).field('status', 'done').attach('analysis', Buffer.from('[1,2]'), 'analysis.json');
  assert.equal(notJson.status, 400);
  const r = await request(app)
    .post(`/internal/jobs/${jobId}/result`)
    .set('Authorization', h.WORKER)
    .field('status', 'done')
    .attach('analysis', Buffer.from(JSON.stringify(analysis)), 'analysis.json');
  assert.equal(r.status, 200, JSON.stringify(r.body));
  const read = await request(app).get(`/clips/${id}/analysis`).set('Authorization', h.COACH1);
  assert.equal(read.status, 200);
  assert.equal(read.body.segmenter, 'meta_sam');
  assert.equal(read.body.analyst, 'muse_spark');
  assert.equal(read.body.job_id, jobId);
  assert.equal(read.body.result.padding.length, 100 * 1024);
  assert.equal((await request(app).get(`/clips/${id}`).set('Authorization', h.COACH1)).body.status, 'ready');
  assert.equal((await request(app).get(`/clips/${id}/analysis`).set('Authorization', h.COACH2)).status, 404);
});

test('completing twice does not create another job', async () => {
  const { app } = h.makeApp();
  const { id, jobId } = await readyClip(app);
  const again = await complete(app, id);
  assert.equal(again.status, 200);
  assert.equal(again.body.job_id, jobId);
  await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER);
  const whileRunning = await complete(app, id);
  assert.equal(whileRunning.body.job_id, jobId);
  assert.equal((await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER)).status, 204);
});

test('the app refuses a short JWT_SECRET and the server does not start without one', () => {
  assert.throws(() => h.makeApp({ jwtSecret: 'short', repo: createMemoryRepo() }), /JWT_SECRET/);
  const r = spawnSync(process.execPath, [path.join(__dirname, '..', 'src', 'server.js')], {
    env: { PATH: process.env.PATH, JWT_SECRET: 'too-short', SYSTEMROOT: process.env.SYSTEMROOT },
    encoding: 'utf8',
    timeout: 10000,
  });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /JWT_SECRET/);
});

const CAL = {
  pitch: { size: 7, length_m: 60, width_m: 40 },
  frames: [{ t_ms: 1200, points: [
    { name: 'corner_far_left', x: 0.1, y: 0.2 }, { name: 'corner_far_right', x: 0.9, y: 0.2 },
    { name: 'corner_near_left', x: 0.05, y: 0.9 }, { name: 'corner_near_right', x: 0.95, y: 0.9 }, { name: 'center', x: 0.5, y: 0.5 },
  ] }],
};

test('pitch_mode and camera are validated, stored and given to the worker', async () => {
  const { app } = h.makeApp();
  const bad = await register(app, h.clipBody({ analysis: { pitch_mode: 'maybe' } }));
  assert.equal(bad.status, 400);
  assert.deepEqual(bad.body.fields, ['analysis.pitch_mode']);
  const badCamera = await register(app, h.clipBody({ analysis: { camera: 'tripod' } }));
  assert.equal(badCamera.status, 400);
  assert.deepEqual(badCamera.body.fields, ['analysis.camera']);
  const none = await register(app, h.clipBody());
  assert.equal(none.body.pitch_mode, null);
  const reg = await register(app, h.clipBody({ analysis: { pitch_mode: 'no_lines', camera: 'static' } }));
  assert.equal(reg.body.pitch_mode, 'no_lines');
  assert.equal(reg.body.camera, 'static');
  const id = reg.body.clip_id;
  assert.equal((await request(app).post(`/clips/${id}/calibration`).set('Authorization', h.COACH1).send(CAL)).status, 200);
  await uploadChunk(app, id, 1, Buffer.from('a'));
  await uploadChunk(app, id, 2, Buffer.from('b'));
  await complete(app, id);
  let next;
  // Other tests may leave jobs queued in MySQL: claim until this clip's.
  do next = await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER); while (next.status === 200 && next.body.clip.id !== id);
  assert.deepEqual(next.body.options, { pitch_mode: 'no_lines', camera: 'static' });
  assert.deepEqual(next.body.calibration, CAL);
});

test('calibration: validated, only the owner, 409 once the worker took the clip', async () => {
  const { app } = h.makeApp();
  const { id } = await readyClip(app);
  const post = (body, auth = h.COACH1) => request(app).post(`/clips/${id}/calibration`).set('Authorization', auth).send(body);
  const pts = CAL.frames[0].points;
  const cases = [
    { ...CAL, pitch: { size: 9 } },
    { ...CAL, pitch: { size: 11, length_m: 500 } },
    { ...CAL, frames: [] },
    { ...CAL, frames: [{ points: pts }] },
    { ...CAL, frames: [{ t_ms: 0, points: [] }] },
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
  const ok = await post({ ...CAL, frames: [{ frame: 12, points: pts }] });
  assert.deepEqual(ok.body, { clip_id: id, frames: 1, points: 5, lines: 0, references: 0 });
  let next;
  do next = await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER); while (next.status === 200 && next.body.clip.id !== id);
  assert.equal(next.body.calibration.frames[0].frame, 12);
  assert.equal(next.body.options.pitch_mode, null);
  const late = await post(CAL);
  assert.equal(late.status, 409);
  assert.equal(late.body.status, 'segmenting');
});

const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(200, 7)]);
const STROKE = [{ x: 0.1, y: 0.8 }, { x: 0.5, y: 0.79 }, { x: 0.9, y: 0.8 }];

test('painted lines and reference pictures: validated, stored and given to the worker', async () => {
  const { app } = h.makeApp();
  const { id } = await readyClip(app);
  const post = (body) => request(app).post(`/clips/${id}/calibration`).set('Authorization', h.COACH1).send(body);
  const pts = CAL.frames[0].points.slice(0, 3);
  const body = {
    pitch: { size: 7 },
    frames: [{ t_ms: 1000, points: pts, lines: [{ name: 'touch_near', points: STROKE }, { name: null, points: STROKE }] }],
    references: [{ n: 1, source: 'photo', strokes: [{ points: STROKE }] }, { n: 2, source: 'frame', t_ms: 4000, strokes: [] }],
  };
  const bad = [
    { ...body, frames: [{ t_ms: 0, lines: [{ name: 'touch_near', points: STROKE.slice(0, 1) }] }] },
    { ...body, frames: [{ t_ms: 0, lines: [{ name: 'Touch Near', points: STROKE }] }] },
    { ...body, frames: [{ t_ms: 0, lines: [{ points: [{ x: 2, y: 0 }, { x: 0, y: 0 }] }] }] },
    { ...body, references: [{ n: 7, source: 'photo', strokes: [] }] },
    { ...body, references: [{ n: 1, source: 'photo', strokes: [] }, { n: 1, source: 'frame', strokes: [] }] },
    { ...body, references: [{ n: 1, source: 'drawing', strokes: [] }] },
    { pitch: { size: 7 }, frames: [], references: [] },
    { ...body, frames: Array.from({ length: 20 }, (_, i) => ({ t_ms: i, lines: Array.from({ length: 3 }, () => ({ name: null, points: Array(60).fill(STROKE[0]) })) })) },
  ];
  for (const c of bad) {
    const r = await post(c);
    assert.equal(r.status, 400, JSON.stringify(c).slice(0, 120));
  }
  const ok = await post(body);
  assert.deepEqual(ok.body, { clip_id: id, frames: 1, points: 3, lines: 2, references: 2 });
  assert.equal((await post({ pitch: { size: 7 }, references: [{ n: 1, source: 'photo', strokes: [{ points: STROKE }] }] })).status, 200, 'references alone');
  assert.equal((await post(body)).status, 200);

  const put = (n, buf, auth = h.COACH1) => request(app).put(`/clips/${id}/references/${n}`).set('Authorization', auth).set('Content-Type', 'image/jpeg').send(buf);
  assert.equal((await put(1, Buffer.from('not a jpeg'))).status, 400);
  assert.equal((await put(7, JPEG)).status, 404);
  assert.equal((await put(1, JPEG, h.COACH2)).status, 404);
  assert.equal((await put(1, Buffer.concat([JPEG, Buffer.alloc(2 * MB)]))).status, 413);
  assert.deepEqual((await put(1, JPEG)).body, { clip_id: id, n: 1, bytes: JPEG.length });

  let next;
  do next = await request(app).get('/internal/jobs/next').set('Authorization', h.WORKER); while (next.status === 200 && next.body.clip.id !== id);
  assert.deepEqual(next.body.calibration, body);
  assert.deepEqual(next.body.clip.references, ['reference_1.jpg']);
  const img = await request(app).get(`/internal/clips/${id}/files/reference_1.jpg`).set('Authorization', h.WORKER).buffer(true).parse((res, cb) => {
    const parts = [];
    res.on('data', (d) => parts.push(d));
    res.on('end', () => cb(null, Buffer.concat(parts)));
  });
  assert.equal(img.status, 200);
  assert.match(img.headers['content-type'], /image\/jpeg/);
  assert.ok(img.body.equals(JPEG));
  const late = await put(2, JPEG);
  assert.equal(late.status, 409);
});
