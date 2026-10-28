'use strict';

/**
 * In-memory repository with the same interface as repo/mysql.js. For tests
 * and quick local runs: everything is lost when the process exits.
 */
function createMemoryRepo() {
  let seq = 0;
  const nextId = () => ++seq;
  const users = new Map();
  const matches = new Map();
  const clips = new Map();
  const chunks = new Map(); // clipId → Map(number → chunk)
  const jobs = new Map();
  const consentEvents = new Map();
  const clipConsent = new Map();
  const analyses = new Map();
  const now = () => new Date();
  const copy = (o) => (o ? { ...o } : null);

  return {
    kind: 'memory',

    async ping() {
      return true;
    },

    async createUser({ email, name, passwordHash }) {
      const key = email.toLowerCase();
      if ([...users.values()].some((u) => u.email === key)) throw Object.assign(new Error('duplicate email'), { code: 'DUPLICATE' });
      const id = nextId();
      users.set(id, { id, email: key, name, password_hash: passwordHash, created_at: now() });
      return id;
    },

    async findUserByEmail(email) {
      const key = String(email).toLowerCase();
      return copy([...users.values()].find((u) => u.email === key));
    },

    async deleteUser(id) {
      users.delete(id);
    },

    async upsertMatch(coachId, m) {
      const found = [...matches.values()].find((x) => x.coach_id === coachId && x.client_match_id === m.client_match_id);
      const row = {
        coach_id: coachId, client_match_id: m.client_match_id, home: m.home, away: m.away,
        home_color: m.home_color, away_color: m.away_color, match_date: m.date, venue: m.venue,
      };
      if (found) {
        Object.assign(found, row);
        return found.id;
      }
      const id = nextId();
      matches.set(id, { id, ...row, created_at: now() });
      return id;
    },

    async getMatch(id) {
      return copy(matches.get(id));
    },

    async findClip(coachId, clientClipId) {
      return copy([...clips.values()].find((c) => c.coach_id === coachId && c.client_clip_id === clientClipId));
    },

    async getClip(id) {
      return copy(clips.get(id));
    },

    async createClip(c) {
      const id = nextId();
      clips.set(id, { id, ...c, status: 'registered', created_at: now(), updated_at: now() });
      return id;
    },

    async updateClip(id, fields) {
      const c = clips.get(id);
      if (c) Object.assign(c, fields, { updated_at: now() });
    },

    async deleteChunks(clipId) {
      chunks.delete(clipId);
    },

    async putChunk(clipId, number, sha256, sizeBytes) {
      if (!chunks.has(clipId)) chunks.set(clipId, new Map());
      chunks.get(clipId).set(number, { number, sha256, size_bytes: sizeBytes, received_at: now() });
    },

    async listChunks(clipId) {
      return [...(chunks.get(clipId) || new Map()).values()].sort((a, b) => a.number - b.number).map(copy);
    },

    /** INSERT IGNORE: the same (coach, action, version, at) returns the existing id. */
    async addConsentEvent(coachId, ev, ip) {
      const at = ev.at.getTime();
      const found = [...consentEvents.values()].find(
        (e) => e.coach_id === coachId && e.action === ev.action && e.version === ev.version && e.at.getTime() === at,
      );
      if (found) return found.id;
      const id = nextId();
      const receivedAt = new Date(Math.floor(Date.now() / 1000) * 1000);
      consentEvents.set(id, { id, coach_id: coachId, action: ev.action, version: ev.version, at: ev.at, received_at: receivedAt, ip, app_version: ev.appVersion });
      return id;
    },

    async listConsentEvents(coachId) {
      return [...consentEvents.values()]
        .filter((e) => e.coach_id === coachId)
        .sort((a, b) => a.at - b.at || a.id - b.id)
        .map(copy);
    },

    async linkClipConsent(clipId, eventId) {
      clipConsent.set(clipId, eventId);
    },

    async openJob(clipId) {
      return copy([...jobs.values()].find((j) => j.clip_id === clipId && (j.status === 'queued' || j.status === 'running')));
    },

    async createJob(clipId) {
      const id = nextId();
      jobs.set(id, { id, clip_id: clipId, type: 'segment', status: 'queued', error: null, created_at: now(), started_at: null, finished_at: null });
      return id;
    },

    async getJob(id) {
      return copy(jobs.get(id));
    },

    async lastJob(clipId) {
      return copy([...jobs.values()].filter((j) => j.clip_id === clipId).sort((a, b) => b.id - a.id)[0]);
    },

    /** The oldest queued job → running, its clip → segmenting. Null when there is none. */
    async claimNextJob() {
      const job = [...jobs.values()].filter((j) => j.status === 'queued').sort((a, b) => a.id - b.id)[0];
      if (!job) return null;
      job.status = 'running';
      job.started_at = now();
      const clip = clips.get(job.clip_id);
      Object.assign(clip, { status: 'segmenting', updated_at: now() });
      return { job: copy(job), clip: copy(clip), match: copy(matches.get(clip.match_id)) };
    },

    async finishJob(id, status, error) {
      const j = jobs.get(id);
      if (j) Object.assign(j, { status, error: error || null, finished_at: now() });
    },

    async saveAnalysis(clipId, jobId, segmenter, analyst, result) {
      analyses.set(clipId, { clip_id: clipId, job_id: jobId, segmenter, analyst, result, created_at: now() });
    },

    async getAnalysis(clipId) {
      return copy(analyses.get(clipId));
    },

    async close() {},
  };
}

module.exports = { createMemoryRepo };
