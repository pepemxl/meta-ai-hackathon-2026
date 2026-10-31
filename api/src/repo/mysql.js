'use strict';

const mysql = require('mysql2/promise');
const { toSqlUtc } = require('../consent');

/**
 * MySQL repository (db/schema.sql), same interface as repo/memory.js.
 * Dates are read and written in UTC (timezone 'Z'), so DATETIME values do
 * not depend on the machine's time zone.
 */
function createMysqlRepo({ host, port, database, user, password }) {
  const pool = mysql.createPool({
    host, port, database, user, password,
    connectionLimit: 5,
    timezone: 'Z',
    dateStrings: false,
    charset: 'utf8mb4',
  });
  const one = async (sql, params) => (await pool.query(sql, params))[0][0] || null;

  return {
    kind: 'mysql',
    pool,

    async ping() {
      try {
        await pool.query('SELECT 1');
        return true;
      } catch (e) {
        return false;
      }
    },

    async createUser({ email, name, passwordHash }) {
      try {
        const [r] = await pool.query('INSERT INTO users (email, name, password_hash) VALUES (?, ?, ?)', [email.toLowerCase(), name, passwordHash]);
        return r.insertId;
      } catch (e) {
        if (e.code === 'ER_DUP_ENTRY') throw Object.assign(new Error('duplicate email'), { code: 'DUPLICATE' });
        throw e;
      }
    },

    async findUserByEmail(email) {
      return one('SELECT id, email, name, password_hash FROM users WHERE email = ?', [String(email).toLowerCase()]);
    },

    async deleteUser(id) {
      await pool.query('DELETE FROM users WHERE id = ?', [id]);
    },

    async upsertMatch(coachId, m) {
      const [r] = await pool.query(
        `INSERT INTO matches (coach_id, client_match_id, home, away, home_color, away_color, match_date, venue)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), home = VALUES(home), away = VALUES(away),
           home_color = VALUES(home_color), away_color = VALUES(away_color), match_date = VALUES(match_date), venue = VALUES(venue)`,
        [coachId, m.client_match_id, m.home, m.away, m.home_color, m.away_color, m.date, m.venue],
      );
      return r.insertId;
    },

    async getMatch(id) {
      return one('SELECT * FROM matches WHERE id = ?', [id]);
    },

    async findClip(coachId, clientClipId) {
      return one('SELECT * FROM clips WHERE coach_id = ? AND client_clip_id = ?', [coachId, clientClipId]);
    },

    async getClip(id) {
      return one('SELECT * FROM clips WHERE id = ?', [id]);
    },

    async createClip(c) {
      const [r] = await pool.query(
        `INSERT INTO clips (coach_id, client_clip_id, match_id, title, duration_ms, chunk_count, chunk_seconds, overlap_ms, pitch_mode, camera, play_area, status)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'registered')`,
        [c.coach_id, c.client_clip_id, c.match_id, c.title, c.duration_ms, c.chunk_count, c.chunk_seconds, c.overlap_ms, c.pitch_mode ?? null, c.camera ?? null, c.play_area ?? null],
      );
      return r.insertId;
    },

    async updateClip(id, fields) {
      const keys = Object.keys(fields);
      if (!keys.length) return;
      await pool.query(
        `UPDATE clips SET ${keys.map((k) => `\`${k}\` = ?`).join(', ')} WHERE id = ?`,
        [...keys.map((k) => fields[k]), id],
      );
    },

    async deleteChunks(clipId) {
      await pool.query('DELETE FROM clip_chunks WHERE clip_id = ?', [clipId]);
    },

    async putChunk(clipId, number, sha256, sizeBytes) {
      await pool.query(
        `INSERT INTO clip_chunks (clip_id, number, sha256, size_bytes) VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE sha256 = VALUES(sha256), size_bytes = VALUES(size_bytes), received_at = CURRENT_TIMESTAMP`,
        [clipId, number, sha256, sizeBytes],
      );
    },

    async listChunks(clipId) {
      const [rows] = await pool.query('SELECT number, sha256, size_bytes FROM clip_chunks WHERE clip_id = ? ORDER BY number', [clipId]);
      return rows.map((r) => ({ ...r, size_bytes: Number(r.size_bytes) }));
    },

    async addConsentEvent(coachId, ev, ip) {
      const at = toSqlUtc(ev.at);
      await pool.query(
        `INSERT IGNORE INTO consent_events (coach_id, action, version, at, received_at, ip, app_version)
         VALUES (?, ?, ?, ?, UTC_TIMESTAMP(), ?, ?)`,
        [coachId, ev.action, ev.version, at, ip, ev.appVersion],
      );
      const row = await one('SELECT id FROM consent_events WHERE coach_id = ? AND action = ? AND version = ? AND at = ?', [coachId, ev.action, ev.version, at]);
      return row.id;
    },

    async listConsentEvents(coachId) {
      const [rows] = await pool.query(
        'SELECT id, action, version, at, received_at, app_version FROM consent_events WHERE coach_id = ? ORDER BY at, id',
        [coachId],
      );
      return rows;
    },

    async linkClipConsent(clipId, eventId) {
      await pool.query('REPLACE INTO clip_consent_events (clip_id, event_id) VALUES (?, ?)', [clipId, eventId]);
    },

    async openJob(clipId) {
      return one("SELECT * FROM jobs WHERE clip_id = ? AND status IN ('queued', 'running') ORDER BY id LIMIT 1", [clipId]);
    },

    async createJob(clipId) {
      const [r] = await pool.query("INSERT INTO jobs (clip_id, type, status) VALUES (?, 'segment', 'queued')", [clipId]);
      return r.insertId;
    },

    async getJob(id) {
      return one('SELECT * FROM jobs WHERE id = ?', [id]);
    },

    async lastJob(clipId) {
      return one('SELECT * FROM jobs WHERE clip_id = ? ORDER BY id DESC LIMIT 1', [clipId]);
    },

    async claimNextJob() {
      const conn = await pool.getConnection();
      try {
        await conn.beginTransaction();
        const [rows] = await conn.query("SELECT * FROM jobs WHERE status = 'queued' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
        const job = rows[0];
        if (!job) {
          await conn.commit();
          return null;
        }
        await conn.query("UPDATE jobs SET status = 'running', started_at = UTC_TIMESTAMP() WHERE id = ?", [job.id]);
        await conn.query("UPDATE clips SET status = 'segmenting' WHERE id = ?", [job.clip_id]);
        await conn.commit();
        const clip = await one('SELECT * FROM clips WHERE id = ?', [job.clip_id]);
        const match = await one('SELECT * FROM matches WHERE id = ?', [clip.match_id]);
        return { job: { ...job, status: 'running' }, clip, match };
      } catch (e) {
        await conn.rollback();
        throw e;
      } finally {
        conn.release();
      }
    },

    async finishJob(id, status, error) {
      await pool.query('UPDATE jobs SET status = ?, error = ?, finished_at = UTC_TIMESTAMP() WHERE id = ?', [status, error ? String(error).slice(0, 500) : null, id]);
    },

    async saveAnalysis(clipId, jobId, segmenter, analyst, result) {
      await pool.query(
        `REPLACE INTO clip_analyses (clip_id, job_id, segmenter, analyst, result, created_at)
         VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP())`,
        [clipId, jobId, segmenter, analyst, result],
      );
    },

    async getAnalysis(clipId) {
      return one('SELECT * FROM clip_analyses WHERE clip_id = ?', [clipId]);
    },

    async close() {
      await pool.end();
    },
  };
}

module.exports = { createMysqlRepo };
