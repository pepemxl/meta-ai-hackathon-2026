'use strict';

const crypto = require('crypto');
const jwt = require('jsonwebtoken');

/** A coach logs in once per season, not once per match. */
const TOKEN_TTL = '30d';

/** scrypt (N=16384, r=8, p=1), 16-byte salt, 64-byte hash. */
function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(password), salt, 64);
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`;
}

function verifyPassword(password, stored) {
  const [scheme, saltHex, hashHex] = String(stored || '').split('$');
  if (scheme !== 'scrypt' || !saltHex || !hashHex) return false;
  const expected = Buffer.from(hashHex, 'hex');
  const got = crypto.scryptSync(String(password), Buffer.from(saltHex, 'hex'), expected.length);
  return crypto.timingSafeEqual(expected, got);
}

/** The coach id stored with everything a logged-in user uploads. */
const coachIdOf = (userId) => `u${userId}`;

function signToken(secret, user, options = {}) {
  return jwt.sign({ sub: coachIdOf(user.id), email: user.email }, secret, {
    algorithm: 'HS256',
    expiresIn: options.expiresIn || TOKEN_TTL,
  });
}

/** The coach id inside a valid token, or null (bad signature, expired, malformed). */
function verifyToken(secret, token) {
  try {
    const payload = jwt.verify(token, secret, { algorithms: ['HS256'] });
    return typeof payload.sub === 'string' ? payload.sub : null;
  } catch (e) {
    return null;
  }
}

/**
 * API_TOKENS: "coach_id:token,coach_id:token" → [{coachId, token}].
 * Malformed entries are skipped; a token needs 16 or more characters.
 */
function parseTokens(spec) {
  return String(spec || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((entry) => {
      const i = entry.indexOf(':');
      if (i <= 0) return null;
      const coachId = entry.slice(0, i).trim();
      const token = entry.slice(i + 1).trim();
      return coachId && token.length >= 16 ? { coachId, token } : null;
    })
    .filter(Boolean);
}

/** Constant-time match of [given] against every static token: the coach id or null. */
function matchStaticToken(tokens, given) {
  const buf = Buffer.from(String(given));
  let found = null;
  for (const t of tokens) {
    const tb = Buffer.from(t.token);
    if (tb.length === buf.length && crypto.timingSafeEqual(tb, buf)) found = t.coachId;
  }
  return found;
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

module.exports = { TOKEN_TTL, hashPassword, verifyPassword, coachIdOf, signToken, verifyToken, parseTokens, matchStaticToken, safeEqual };
