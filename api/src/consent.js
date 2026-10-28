'use strict';

/** The consent text in force (docs/legal/). A new version asks every coach again. */
const CONSENT_VERSION = '2026-10-27';
const VERSION_PATTERN = /^[\w.-]{1,20}$/;
const MAX_FUTURE_MS = 24 * 3600 * 1000;

/** A phone timestamp: a valid date, not more than 1 day ahead. A Date (to the second) or null. */
function parseAt(value, now = Date.now()) {
  if (typeof value !== 'string' || value.length > 40) return null;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  if (d.getTime() - now > MAX_FUTURE_MS) return null;
  // Stored to the second (DATETIME), so compare and dedupe at that precision.
  return new Date(Math.floor(d.getTime() / 1000) * 1000);
}

function appVersionOf(v) {
  return typeof v === 'string' && v.length <= 40 ? v : null;
}

/** The consent block of POST /clips → {version, at, appVersion}, or null (→ 428). */
function parseClipConsent(consent, now) {
  if (!consent || typeof consent !== 'object') return null;
  if (consent.version !== CONSENT_VERSION) return null;
  const at = parseAt(consent.accepted_at, now);
  if (!at) return null;
  return { version: CONSENT_VERSION, at, appVersion: appVersionOf(consent.app_version) };
}

/** The body of POST /consent → {action, version, at, appVersion} or {error}. */
function parseConsentEvent(body, now) {
  const b = body || {};
  if (b.action !== 'accept' && b.action !== 'withdraw') return { error: 'invalid action' };
  if (typeof b.version !== 'string' || !VERSION_PATTERN.test(b.version)) return { error: 'invalid version' };
  if (b.action === 'accept' && b.version !== CONSENT_VERSION) return { error: 'not the current version' };
  const at = parseAt(b.at, now);
  if (!at) return { error: 'invalid date' };
  return { action: b.action, version: b.version, at, appVersion: appVersionOf(b.app_version) };
}

/** none | accepted | withdrawn | outdated, from events sorted by time. */
function currentState(events) {
  if (!events.length) return 'none';
  const last = events[events.length - 1];
  if (last.action === 'withdraw') return 'withdrawn';
  return last.version === CONSENT_VERSION ? 'accepted' : 'outdated';
}

/** UTC 'YYYY-MM-DD HH:MM:SS' for DATETIME columns. */
const toSqlUtc = (d) => new Date(d).toISOString().slice(0, 19).replace('T', ' ');

/** ISO with milliseconds, as every consent date leaves the API. */
const iso = (d) => new Date(d).toISOString();

module.exports = { CONSENT_VERSION, VERSION_PATTERN, parseAt, parseClipConsent, parseConsentEvent, currentState, toSqlUtc, iso };
