'use strict';

const { loadEnv, configFromEnv } = require('./config');
const { createApp } = require('./app');
const { createMemoryRepo } = require('./repo/memory');
const { createMysqlRepo } = require('./repo/mysql');

loadEnv();
const config = configFromEnv();

if (config.jwtSecret.length < 32) {
  console.error('JWT_SECRET must be set and at least 32 characters (see .env.example)');
  process.exit(1);
}

const repo = config.repo === 'mysql' ? createMysqlRepo(config.db) : createMemoryRepo();
const app = createApp({ ...config, repo });

app.listen(config.port, config.host, () => {
  console.log(`pp-coach-tracker API on http://${config.host}:${config.port} (repo ${repo.kind}, storage ${config.storageDir})`);
  if (!config.workerSecret) console.warn('WORKER_CALLBACK_SECRET is empty: /internal/* will answer 401');
  if (config.requireMeta) console.log('PP_COACH_TRACKER_REQUIRE_META=1: only SAM 3.1 results are accepted');
});
