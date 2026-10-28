'use strict';

const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { createApp } = require('../src/app');
const { createMemoryRepo } = require('../src/repo/memory');
const { createMysqlRepo } = require('../src/repo/mysql');
const { parseTokens } = require('../src/auth');

const JWT_SECRET = 'test-jwt-secret-0123456789abcdefghijkl';
const WORKER_SECRET = 'test-worker-secret-0123456789';
const TOKENS = 'test-coach1:test-token-0123456789,test-coach2:other-coach-token-9876';
const COACH1 = 'Bearer test-token-0123456789';
const COACH2 = 'Bearer other-coach-token-9876';
const WORKER = `Bearer ${WORKER_SECRET}`;
const USE_MYSQL = process.env.TEST_MYSQL === '1';
// Fixed past dates: the server rejects acceptances more than 1 day ahead.
const ACCEPTED_AT = '2026-10-27T15:04:05.123Z';

let mysqlRepo = null;
function sharedRepo() {
  if (!USE_MYSQL) return createMemoryRepo();
  if (!mysqlRepo) {
    mysqlRepo = createMysqlRepo({
      host: process.env.DB_HOST || '127.0.0.1',
      port: Number(process.env.DB_PORT || 13306),
      database: process.env.DB_NAME || 'ppcoachtracker',
      user: process.env.DB_USER || 'ppcoachtracker',
      password: process.env.DB_PASSWORD || 'ppcoachtracker-dev',
    });
  }
  return mysqlRepo;
}

/** Deletes everything the tests create (MySQL only; memory starts empty). */
async function wipeTestData() {
  if (!USE_MYSQL) return;
  const p = sharedRepo().pool;
  await p.query("DELETE FROM clips WHERE coach_id LIKE 'test-%' OR coach_id IN (SELECT CONCAT('u', id) FROM users WHERE email LIKE '%@pp-test.example')");
  await p.query("DELETE FROM matches WHERE coach_id LIKE 'test-%' OR coach_id IN (SELECT CONCAT('u', id) FROM users WHERE email LIKE '%@pp-test.example')");
  await p.query("DELETE FROM consent_events WHERE coach_id LIKE 'test-%' OR coach_id IN (SELECT CONCAT('u', id) FROM users WHERE email LIKE '%@pp-test.example')");
  await p.query("DELETE FROM users WHERE email LIKE '%@pp-test.example'");
}

async function closeRepo() {
  if (mysqlRepo) {
    await wipeTestData();
    await mysqlRepo.close();
    mysqlRepo = null;
  }
}

function makeApp(overrides = {}) {
  const storageDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ppct-api-'));
  const repo = overrides.repo || sharedRepo();
  const app = createApp({
    repo,
    storageDir,
    tokens: parseTokens(TOKENS),
    jwtSecret: JWT_SECRET,
    workerSecret: WORKER_SECRET,
    ...overrides,
  });
  return { app, repo, storageDir };
}

const sha = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const chunkYaml = (n, hash) => `%YAML:1.0\n---\nversion: 1\nchunk:\n  number: ${n}\n  file: "video_part_${String(n).padStart(3, '0')}.mp4"\nsha256: "${hash}"\n`;

let clipSeq = 0;
function clipBody(extra = {}) {
  clipSeq += 1;
  return {
    client_clip_id: `clip-${process.pid}-${clipSeq}`,
    title: 'Jugada 1',
    duration_ms: 75000,
    chunk_count: 2,
    chunk_seconds: 60,
    overlap_ms: 2000,
    match: { client_match_id: 'match-1', home: 'Tigres Sub-12', away: 'Halcones', home_color: 'red', away_color: 'blue', date: '2026-10-25', venue: 'Cancha 3' },
    consent: { version: '2026-10-27', accepted_at: ACCEPTED_AT, app_version: '0.1.0' },
    ...extra,
  };
}

module.exports = {
  JWT_SECRET, WORKER_SECRET, TOKENS, COACH1, COACH2, WORKER, USE_MYSQL, ACCEPTED_AT,
  makeApp, sharedRepo, wipeTestData, closeRepo, sha, chunkYaml, clipBody,
};
