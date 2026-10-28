'use strict';

const fs = require('fs');
const path = require('path');
const { parseTokens } = require('./auth');

const ROOT = path.resolve(__dirname, '..', '..');

/** Loads the repo-root .env (never versioned) into process.env without overriding what is set. */
function loadEnv() {
  const file = path.join(ROOT, '.env');
  if (fs.existsSync(file)) process.loadEnvFile(file);
}

const int = (v, fallback) => (v === undefined || v === '' ? fallback : Number.parseInt(v, 10));

/** Configuration from environment variables (names in .env.example). */
function configFromEnv(env = process.env) {
  const storage = env.STORAGE_DIR || 'api/storage';
  return {
    host: env.HOST || '127.0.0.1',
    port: int(env.PORT, 3000),
    jwtSecret: env.JWT_SECRET || '',
    workerSecret: env.WORKER_CALLBACK_SECRET || '',
    tokens: parseTokens(env.API_TOKENS),
    repo: env.REPO || 'memory',
    db: {
      host: env.DB_HOST || '127.0.0.1',
      port: int(env.DB_PORT, 13306),
      database: env.DB_NAME || 'ppcoachtracker',
      user: env.DB_USER || 'ppcoachtracker',
      password: env.DB_PASSWORD || '',
    },
    storageDir: path.isAbsolute(storage) ? storage : path.join(ROOT, storage),
    maxPartBytes: int(env.MAX_PART_BYTES, 8 * 1024 * 1024),
    maxChunkBytes: int(env.MAX_CHUNK_BYTES, 160 * 1024 * 1024),
    requireMeta: env.PP_COACH_TRACKER_REQUIRE_META === '1',
  };
}

module.exports = { loadEnv, configFromEnv, ROOT };
