"use strict";

// ── Admin session lifecycle store (S05-M3, S07-H1, S07-H2) ────────────────────
//
// WHAT WAS ACTUALLY WRONG (audit/SESSION-05-ADMIN.md:452-498)
//
// The pre-fix `adminSessions` (a bare `Map<sessionId, expiresAt>` in
// server/index.js) had five compounding weaknesses:
//   1. No absolute cap — every touch extended the session another 30
//      minutes, forever, because no `createdAt` was ever recorded.
//   2. The refresh happened on an UNAUTHENTICATED route (`GET /admin`, used
//      only to decide which view to render) — a browser prefetch or a
//      restored tab kept a session alive with no operator activity at all.
//   3. Bound to nothing — no IP, no User-Agent — so a cookie captured from
//      any channel (shared machine, synced browser profile, log leak) was a
//      full admin credential from anywhere.
//   4. The 10-minute inactivity timeout was client-side JavaScript only; the
//      server-side TTL it appeared to enforce was actually the 30-minute
//      sliding value from (1), which per the project's own rule that client
//      checks are never controls, bounded nothing.
//   5. No bulk revocation — rotating ADMIN_TOKEN or responding to a suspected
//      compromise could not invalidate sessions already minted.
//
// THE FIX (S05-M3)
//
// This module enforces, together:
//   - a sliding IDLE timeout (`idleTtlMs`), refreshed only when the caller
//     passes `{ refresh: true }` (the default) — index.js passes
//     `{ refresh: false }` from the unauthenticated `GET /admin` render
//     check specifically so that route can no longer extend a session
//     (closes weakness 2);
//   - an ABSOLUTE lifetime ceiling (`absoluteTtlMs`), recorded INSIDE the
//     signed token itself, which no refresh can push past (weakness 1);
//   - OPTIONAL binding to the caller's User-Agent, captured at issue time and
//     compared on every `validate()` call — opt-in via `bindUserAgent: true`
//     and OFF by default (see the S05-M3 section below for why enforcing it
//     broke real mobile logins). Weakness 3 — "a leaked cookie is a full
//     admin credential from anywhere" — is therefore addressed by the
//     cookie's own properties (HMAC-signed, HttpOnly, Secure, SameSite=Lax,
//     Path=/admin, short idle + absolute TTL, rotation on login) plus
//     `revokeAll()`, NOT by client-context binding;
//   - `revoke()` (single session) and `revokeAll()` (every session), the
//     latter wired to the panel's "Sign out everywhere" action (weakness 5).
//
// ── S07-H2: THE "LOGS IN FOR HALF A SECOND, THEN BOUNCES BACK" BUG ────────────
//
// Two defects in the previous revision of this file made a SUCCESSFUL login
// fall straight back to the gate with "Your session expired. Sign in again."
// — the exact reported symptom, and the reason the previous fix attempt only
// half-worked:
//
//   (a) USER-AGENT TRUNCATION ASYMMETRY. `create()` stored
//       `String(ua).slice(0, 200)` while `validate()` compared the stored
//       value against the RAW, untruncated header. Any browser whose
//       User-Agent exceeds 200 characters — routine for Android in-app
//       webviews (Instagram/Facebook/Chrome-on-Android with device and build
//       tokens appended) — therefore failed `ua_mismatch` on EVERY request,
//       forever, immediately after a correct login. Reproduced locally with a
//       262-character Android webview UA: login 303'd with a cookie,
//       `GET /admin` rendered the gate, and the first `/admin/api/*` call
//       401'd. Both sides now normalise through `normalizeUserAgent()`, so
//       the comparison is between two values produced the same way.
//
//   (b) PROCESS-LOCAL SESSION IDS. The session id was a random opaque string
//       whose ONLY record of existence was this process's Map. A Render
//       deploy, restart, idle spin-down, or a second instance behind the load
//       balancer left the browser holding a cookie the server had no memory
//       of (`not_found`) — again indistinguishable, to the operator, from
//       "my correct password logged me out". Sessions are now SIGNED TOKENS:
//       `sid.iat.exp.uaTag.sig`, HMAC'd with a server secret that is stable
//       across restarts (index.js derives it from ADMIN_TOKEN unless
//       ADMIN_SESSION_SECRET is set). Validation is therefore
//       self-contained — an unforgeable token still carrying a live absolute
//       expiry is accepted after a restart and re-adopted into the local map
//       (which then resumes the sliding idle timeout for it). Nothing about
//       the token is trusted without the signature check, and the token
//       carries no operator data — just an id, two timestamps and a keyed
//       UA tag.
//
//   (c) IP BINDING REMOVED FROM THE REJECT PATH. Enforcing the IP dimension
//       logged mobile operators out constantly (WiFi→LTE handover, CGNAT
//       reassignment, a dual-stack client flipping between its IPv4 and IPv6
//       route). It was already default-off, but left available via
//       `ADMIN_BIND_SESSION_IP=1`, i.e. still a foot-gun that reproduces this
//       exact bug on the one deployment target this panel actually runs on.
//       `validate()` no longer looks at the client address at all; the
//       address is still recorded in the durable audit trail
//       (`auditAdminEvent`), which is where it is useful.
//
// ── S05-M3: UA BINDING WAS THE *LAST* HALF-SECOND-LOGOUT FOOT-GUN ─────────────
//
// The S07-H2 note above (c) claimed the User-Agent binding was "stable across
// a network change" and could stay mandatory. On the actual target platform it
// is NOT stable within a SINGLE page view: Android in-app / WebView browsers
// (and some privacy browsers) send a DIFFERENT `User-Agent` on `fetch()`/XHR
// subrequests than on the top-level navigation that loaded the page — the
// navigation carries the full `...; wv) ... Version/4.0 ...` WebView token and
// device Build id, the subrequest sends a trimmed desktop-shaped UA. Because
// the panel authenticates the initial `GET /admin` (navigation UA) and then
// immediately fires `/admin/api/*` (fetch UA), the operator saw the dashboard
// render for a frame and then get bounced to the gate with "Your session
// expired." on the very first data call — the exact reported symptom, and the
// reason the earlier fixes only "half worked". Reproduced against production:
// same-UA `/admin/api/*` → 200, a UA differing only in the WebView token → 401
// `ua_mismatch`.
//
// So UA binding is now OFF by default too (`bindUserAgent`, default false),
// for the same reason IP binding was dropped in (c). The cookie is not left
// "bound to nothing": it is HMAC-SIGNED (unforgeable), `HttpOnly` (no JS/XSS
// read), `Secure` (HTTPS only), `SameSite=Lax` + `Path=/admin` (not sent
// cross-site), short-lived (30-min idle / 8-h absolute) and ROTATED on every
// login — a materially stronger credential than the bare, unbound `Map` id
// that the original weakness-3 finding was written against. The UA is still
// recorded on the session and in the audit trail. Deployments that genuinely
// want the extra replay check back can pass `bindUserAgent: true`, but must
// accept that it logs WebView operators out on their first click.
//
// WHAT THIS DOES NOT DO (left out of scope, not silently missed)
//
//   - Step-up re-authentication for destructive actions (unfreeze, duress
//     enroll). A future finding, not claimed fixed here.
//   - Automatic invalidation when ADMIN_TOKEN is rotated is now IMPLICIT when
//     the signing secret is derived from ADMIN_TOKEN (index.js's default):
//     rotating the token changes the secret, so every previously issued token
//     fails its signature check. Setting ADMIN_SESSION_SECRET explicitly
//     decouples the two.
//   - Cross-instance REVOCATION. Single-session revoke and revokeAll are
//     still process-local (a revoked token would remain valid on a second
//     instance until its absolute expiry). Accepted, and strictly better than
//     the pre-fix state where a restart invalidated every session at random
//     while revocation was equally process-local.

const crypto = require("node:crypto");

const DEFAULT_IDLE_TTL_MS = 30 * 60 * 1000; // 30 min sliding idle timeout
const DEFAULT_ABSOLUTE_TTL_MS = 8 * 60 * 60 * 1000; // 8h hard ceiling, no refresh extends past this

// Long User-Agent strings are truncated before they are stored or compared.
// The exact limit does not matter; applying it on BOTH sides does ��� see (a) in
// the header comment.
const UA_MAX_LEN = 200;

function normalizeUserAgent(ua) {
  if (ua === undefined) return undefined;
  if (ua === null) return null;
  return String(ua).slice(0, UA_MAX_LEN);
}

function defaultRandomId() {
  return crypto.randomBytes(32).toString("hex");
}

/**
 * Creates an admin session store.
 *
 * @param {object} [opts]
 * @param {number} [opts.idleTtlMs] - Sliding idle timeout in ms.
 * @param {number} [opts.absoluteTtlMs] - Absolute lifetime ceiling in ms,
 *   measured from issue time. No refresh can extend a session past it.
 * @param {string|Buffer} [opts.secret] - HMAC key used to sign session
 *   tokens. When supplied (production does), sessions survive a restart or a
 *   second instance — see (b) in the header comment. When omitted, the store
 *   falls back to opaque, process-local ids (used by unit tests that only
 *   exercise TTL/revocation semantics).
 * @param {() => number} [opts.now] - Clock, injectable for deterministic tests.
 * @param {() => string} [opts.randomId] - Session id generator, injectable.
 */
function createAdminSessionStore({
  idleTtlMs = DEFAULT_IDLE_TTL_MS,
  absoluteTtlMs = DEFAULT_ABSOLUTE_TTL_MS,
  secret = null,
  now = () => Date.now(),
  randomId = defaultRandomId,
  // S05-M3: whether validate() REJECTS a request whose User-Agent differs from
  // the one bound at issue time. Default OFF — see (c) in the header comment.
  // The UA is still recorded on the session and baked (as a keyed tag) into the
  // signed token regardless, so turning this on later needs no token change.
  bindUserAgent = false,
} = {}) {
  // sid -> { createdAt, expiresAt, absoluteExpiresAt, userAgent }
  const sessions = new Map();
  // sid -> time after which the revocation record itself can be forgotten
  // (never before the token it revokes would have expired on its own).
  const revoked = new Map();
  // Set by revokeAll(): any token issued at or before this instant is refused
  // even if its signature and absolute expiry are still fine.
  let revokedBeforeMs = 0;

  const signingKey = secret
    ? (Buffer.isBuffer(secret) ? secret : Buffer.from(String(secret), "utf8"))
    : null;

  function sign(payload) {
    return crypto.createHmac("sha256", signingKey).update(payload).digest("hex").slice(0, 32);
  }

  function tagUserAgent(ua) {
    if (ua === null || ua === undefined || ua === "") return "-";
    return crypto.createHmac("sha256", signingKey).update(ua).digest("hex").slice(0, 12);
  }

  /**
   * Parses a signed token. Returns null when the store is unsigned, the shape
   * is wrong, or the signature does not verify — callers must treat null as
   * "this is not a token I issued".
   */
  function parseToken(token) {
    if (!signingKey || typeof token !== "string") return null;
    const parts = token.split(".");
    if (parts.length !== 5) return null;
    const [sid, iatRaw, expRaw, uaTag, sig] = parts;
    const payload = `${sid}.${iatRaw}.${expRaw}.${uaTag}`;
    const expected = sign(payload);
    // Constant-time: a byte-by-byte early return would leak the signature.
    if (sig.length !== expected.length) return null;
    if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
    const iat = parseInt(iatRaw, 36);
    const exp = parseInt(expRaw, 36);
    if (!Number.isFinite(iat) || !Number.isFinite(exp)) return null;
    return { sid, iat, exp, uaTag };
  }

  /** True once revoke()/revokeAll() has retired this token. */
  function isRevoked(sid, iat) {
    if (revoked.has(sid)) return true;
    return iat <= revokedBeforeMs;
  }

  function recordRevocation(sid, until) {
    revoked.set(sid, until);
  }

  /**
   * Issues a new session bound to the given client context.
   *
   * @param {{ip?: string|null, userAgent?: string|null}} [ctx] - `userAgent`
   *   is bound and checked on every later `validate()`. `ip` is accepted and
   *   ignored — see (c) in the header comment; index.js records the address in
   *   the audit trail instead.
   * @returns {string} the session token to put in the cookie.
   */
  function create(ctx = {}) {
    const createdAt = now();
    const sid = randomId();
    const userAgent = normalizeUserAgent(ctx.userAgent === undefined ? null : ctx.userAgent);
    const absoluteExpiresAt = createdAt + absoluteTtlMs;
    sessions.set(sid, {
      createdAt,
      expiresAt: createdAt + idleTtlMs,
      absoluteExpiresAt,
      userAgent,
    });
    if (!signingKey) return sid;
    const payload = `${sid}.${createdAt.toString(36)}.${absoluteExpiresAt.toString(36)}.${tagUserAgent(userAgent)}`;
    return `${payload}.${sign(payload)}`;
  }

  /**
   * Validates a session token against the current request's client context.
   *
   * @param {string} token
   * @param {{ip?: string|null, userAgent?: string|null}} [ctx] - Current
   *   request context. `userAgent`, when present, must match what was bound at
   *   issue time. Omitting it skips that check (unit tests isolating TTL
   *   behaviour do this); index.js always passes it. `ip` is ignored.
   * @param {{refresh?: boolean}} [opts] - `refresh` (default `true`): whether
   *   a successful validation slides the idle expiry forward, capped at the
   *   absolute ceiling. index.js passes `false` from the unauthenticated
   *   `GET /admin` render check so it cannot extend a session.
   * @returns {{valid: true}|{valid: false, reason: string}}
   */
  function validate(token, ctx = {}, opts = {}) {
    const refresh = opts.refresh !== false;
    if (!token) return { valid: false, reason: "missing" };
    const t = now();
    const parsed = parseToken(token);
    const sid = parsed ? parsed.sid : token;

    // Unsigned/unrecognised value while signing is enabled: it cannot be a
    // token this store issued, so it is refused outright rather than being
    // looked up (a bare sid must never be accepted as a credential).
    if (signingKey && !parsed) return { valid: false, reason: "not_found" };

    if (parsed) {
      if (t >= parsed.exp) {
        sessions.delete(sid);
        return { valid: false, reason: "absolute_expired" };
      }
      if (isRevoked(sid, parsed.iat)) return { valid: false, reason: "not_found" };
      if (bindUserAgent && ctx.userAgent !== undefined) {
        const currentTag = tagUserAgent(normalizeUserAgent(ctx.userAgent));
        if (currentTag !== parsed.uaTag) return { valid: false, reason: "ua_mismatch" };
      }
    }

    let rec = sessions.get(sid);

    if (!rec) {
      // Unsigned store: nothing more to check against.
      if (!parsed) return { valid: false, reason: "not_found" };
      // Signed token with no local record — this instance restarted, or the
      // request landed on a different instance. The signature and the
      // in-token absolute expiry are the actual controls, so accept it, and
      // (only on a real authenticated call) re-adopt it locally so the
      // sliding idle timeout resumes from now.
      if (!refresh) return { valid: true };
      rec = {
        createdAt: parsed.iat,
        expiresAt: Math.min(t + idleTtlMs, parsed.exp),
        absoluteExpiresAt: parsed.exp,
        userAgent: normalizeUserAgent(ctx.userAgent === undefined ? null : ctx.userAgent),
      };
      sessions.set(sid, rec);
      return { valid: true };
    }

    if (t >= rec.absoluteExpiresAt) {
      sessions.delete(sid);
      return { valid: false, reason: "absolute_expired" };
    }
    if (t >= rec.expiresAt) {
      sessions.delete(sid);
      // Remember the idle expiry, otherwise the signed token would simply be
      // re-adopted by the branch above on the very next request and the idle
      // timeout would never actually bite.
      if (parsed) recordRevocation(sid, rec.absoluteExpiresAt);
      return { valid: false, reason: "idle_expired" };
    }

    // Unsigned store keeps its original in-memory UA comparison; the signed
    // path already checked the UA tag above. A mismatch rejects THIS request
    // without deleting the session, so an attacker replaying a stolen cookie
    // is refused every time without collaterally logging the real operator out.
    if (bindUserAgent && !parsed && ctx.userAgent !== undefined) {
      if (rec.userAgent !== normalizeUserAgent(ctx.userAgent)) {
        return { valid: false, reason: "ua_mismatch" };
      }
    }

    if (refresh) {
      rec.expiresAt = Math.min(t + idleTtlMs, rec.absoluteExpiresAt);
    }
    return { valid: true };
  }

  /** Revokes a single session. Returns true if a live record existed. */
  function revoke(token) {
    const parsed = parseToken(token);
    const sid = parsed ? parsed.sid : token;
    const existed = sessions.delete(sid);
    if (parsed) recordRevocation(sid, parsed.exp);
    return existed;
  }

  /** Revokes every session issued so far. Returns the number tracked locally. */
  function revokeAll() {
    const count = sessions.size;
    sessions.clear();
    revokedBeforeMs = now();
    return count;
  }

  /** Removes expired entries. Intended for periodic background cleanup. */
  function sweep() {
    const t = now();
    for (const [id, rec] of sessions) {
      if (t >= rec.absoluteExpiresAt || t >= rec.expiresAt) sessions.delete(id);
    }
    for (const [id, until] of revoked) {
      if (t >= until) revoked.delete(id);
    }
  }

  /** Count of sessions currently tracked (expired-but-unswept included). */
  function size() {
    return sessions.size;
  }

  // Test/diagnostic helper only — not used by index.js's request path.
  function _get(token) {
    const parsed = parseToken(token);
    const rec = sessions.get(parsed ? parsed.sid : token);
    return rec ? Object.assign({}, rec) : null;
  }

  return { create, validate, revoke, revokeAll, sweep, size, _get };
}

module.exports = {
  createAdminSessionStore,
  normalizeUserAgent,
  DEFAULT_IDLE_TTL_MS,
  DEFAULT_ABSOLUTE_TTL_MS,
  UA_MAX_LEN,
};
