"use strict";

// ── Pure, side-effect-free server helpers ─────────────────────────────────────
// Extracted from index.js so they can be unit-tested with the Node built-in test
// runner without booting the HTTP server, Firebase Admin, or the Firestore
// listeners. index.js requires these exact implementations, so the tests cover
// the real code path — not a copy that could drift.

const crypto = require("crypto");

// Human-readable push body for a message document, keyed by its `type`.
function notificationBody(data) {
  if (!data || typeof data !== "object") return "New encrypted message";
  if (data.type === "image") return "Sent a photo 🖼";
  if (data.type === "video") return "Sent a video 🎬";
  if (data.type === "voice") return "Sent a voice note 🎙";
  if (data.type === "contact") return "Shared a contact card 📇";
  return "New encrypted message";
}

// Constant-time token comparison so an attacker cannot learn the admin token
// byte-by-byte through response-timing differences.
//
// S05-I3: the original implementation returned early — before ever calling
// timingSafeEqual — on a raw length mismatch. That branch is not itself a
// timing side-channel on the token's CONTENT, but it does make the supplied
// token's LENGTH a (very noisy, many-samples-needed) timing oracle, and
// S05-H1 means an attacker's sample budget against this comparison is not
// otherwise bounded. Hashing both sides to a fixed-width digest FIRST removes
// the length branch entirely: every comparison, regardless of the original
// input lengths, compares two 32-byte SHA-256 digests, so there is no
// length-dependent code path left to time. This does not weaken the
// comparison — two different inputs still compare unequal with cryptographic
// certainty — it only removes the earlier short-circuit.
function safeTokenEqual(a, b) {
  const digestA = crypto.createHash("sha256").update(String(a)).digest();
  const digestB = crypto.createHash("sha256").update(String(b)).digest();
  return crypto.timingSafeEqual(digestA, digestB);
}

/**
 * Constant-time comparison of two fixed-width hex digests.
 *
 * Deliberately distinct from safeTokenEqual above. That one is for opaque bearer
 * tokens and coerces with String(), which makes `undefined` compare equal to the
 * literal "undefined" and accepts non-string input without complaint. For
 * identity material the stored side may legitimately be absent or malformed, and
 * that state must read as "ownership not proven" rather than being coerced into
 * something comparable — see S07-H1, where a fail-open hash check issued tokens
 * for accounts whose stored hash was missing.
 *
 * Length is compared in the clear: a digest's width is not secret, and
 * crypto.timingSafeEqual throws rather than returning false on unequal lengths.
 * Buffer.from(x, "hex") stops at the first invalid character instead of throwing,
 * so the decoded length is re-checked — otherwise a malformed value could compare
 * equal to a prefix of the real digest.
 */
function timingSafeEqualHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  if (a.length !== b.length || a.length === 0) return false;
  if (a.length % 2 !== 0) return false;
  const bufA = Buffer.from(a, "hex");
  const bufB = Buffer.from(b, "hex");
  if (bufA.length !== a.length / 2 || bufB.length !== b.length / 2) return false;
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

// Whitelist for admin-supplied UIDs: printable, bounded length, free of path
// separators / control characters that could be used to traverse Firestore
// document paths or smuggle control bytes, and free of the document-id shapes
// Firestore itself treats specially.
//
// S05-L1: "." and ".." and any id matching /^__.*__$/ are reserved by Firestore
// (single-segment names, so the earlier slash/backslash check does not catch
// them) — passing one to `.doc(uid)` throws instead of returning a normal
// not-found result, which previously surfaced as an uncaught 500 rather than a
// 400 on every route that already called this whitelist. Rejecting them here
// makes that failure mode a deliberate, documented 400 everywhere this
// function gates a Firestore document id.
function validAdminUid(uid) {
  if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) return false;
  if (/[\/\\\u0000-\u001f]/.test(uid)) return false;
  if (uid === "." || uid === "..") return false;
  if (/^__.*__$/.test(uid)) return false;
  return true;
}

// Parse a single cookie value out of a Cookie header string. Accepts either a
// raw header string or a request-like object exposing `headers.cookie`.
function getCookie(reqOrHeader, name) {
  const raw = typeof reqOrHeader === "string"
    ? reqOrHeader
    : (reqOrHeader && reqOrHeader.headers && reqOrHeader.headers.cookie) || "";
  for (const part of raw.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 0) continue;
    const key = part.slice(0, separator).trim();
    if (key === name) return decodeURIComponent(part.slice(separator + 1).trim());
  }
  return "";
}

// SSRF guard: block loopback, RFC-1918 private ranges, link-local, and cloud
// metadata hostnames. Applied to the initial /linkPreview target AND to every
// redirect hop, so a public host cannot bounce the fetch to an internal address.
function isBlockedPreviewHost(hostname) {
  const host = String(hostname || "").toLowerCase();
  return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?)/.test(host) ||
    host === "metadata.google.internal" ||
    host === "169.254.169.254" ||
    host.endsWith(".internal") ||
    host.endsWith(".local");
}

// S04-I3: /linkPreview labeled every result with the hostname of the
// ORIGINALLY SUBMITTED url, even though title/imageUrl are scraped from the
// FINAL hop of a (validated) redirect chain — so a link on trusted.example
// that 302s to attacker.example rendered a preview card labelled
// trusted.example carrying attacker.example's title and image. Fed
// `finalUrl` (already checked per-hop by fetchFollowingSafeRedirects, so
// this never surfaces an unvalidated host), this returns the domain that
// content actually came from. Falls back to `fallbackHostname` (the
// originally-submitted host) only if `finalUrl` fails to parse — belt and
// suspenders, since a value that reached here already round-tripped through
// `new URL()` in fetchFollowingSafeRedirects.
function previewDomainFromUrl(finalUrl, fallbackHostname) {
  try {
    return new URL(finalUrl).hostname.replace(/^www\./, "");
  } catch {
    return String(fallbackHostname || "").replace(/^www\./, "");
  }
}

// S04-M2: `/turnCredentials` minted 24-hour Cloudflare TURN relay credentials
// (`ttl: 86400`) — wildly longer than any call needs, and long enough that a
// bulk-minted, resold, or leaked credential (a plain bearer username/password
// that works from anywhere, bound to nothing) stays useful for a full day.
// This clamps whatever TTL the caller asks for into a hard floor/ceiling that
// holds regardless of a misconfigured env var, so a typo'd
// `TURN_CRED_TTL_SECONDS` can never resurrect the original 24h exposure. The
// default ceiling (3600s / 1h) intentionally matches the Android client's own
// refresh assumption — `TurnCredentialCache.TTL_MS` (app-side) already treats
// a cached credential as stale after 1h, so this does not change client
// behavior, only how long a stolen credential remains valid server-side.
function clampTurnCredentialTtlSeconds(requestedSeconds, minSeconds, maxSeconds) {
  const min = Number.isFinite(minSeconds) ? minSeconds : 60;
  const max = Number.isFinite(maxSeconds) ? maxSeconds : 3600;
  const requested = Number(requestedSeconds);
  const fallback = max; // unset/non-numeric env var -> ceiling, never the old 24h default
  const value = Number.isFinite(requested) ? requested : fallback;
  return Math.min(max, Math.max(min, value));
}

// S06-L1: /duress-lock's nonce-expiry check used to be
// `new Date() > new Date(expiresAt.toDate ? expiresAt.toDate() : expiresAt)`
// inline inside the Firestore transaction, with no test coverage. Two ways
// that failed: a missing `expiresAt` threw a TypeError with no `.status`,
// which surfaced as a 500 that AccountLockWorker retries forever
// (5xx == retryable); a value `Date` cannot parse compared against
// `Invalid Date` as `false`, so the nonce was treated as NOT expired —
// fail-open, valid indefinitely. `_duressNonces` docs are Admin-SDK-only
// (never client-writable), so today the only way to hit either path is
// corrupted or hand-edited data — but the fix is extracted to a pure,
// directly-testable function precisely because the surrounding code is
// otherwise carefully fail-closed and this is the one path that wasn't.
function resolveNonceExpiry(expiresAt) {
  if (expiresAt && typeof expiresAt.toDate === "function") return expiresAt.toDate();
  if (expiresAt instanceof Date) return expiresAt;
  return null; // missing, string, number, or otherwise malformed — untrusted
}

// Fail-closed nonce validity: usable only if it names a uid AND its expiry
// resolves to a real, non-NaN Date AND that Date has not yet passed. Any
// other shape (no uid, no/garbage expiresAt) must be invalid, never
// "not yet expired".
function isNonceUsable(nonceUid, expiresAt, now) {
  const exp = resolveNonceExpiry(expiresAt);
  return Boolean(nonceUid) && exp instanceof Date && !Number.isNaN(exp.getTime()) && now <= exp.getTime();
}

// ── Fixed-window rate-limit evaluation (pure) ─────────────────────────────────
// Given the caller's current record ({ count, windowStart } | undefined) and the
// clock, decide whether the request is allowed and return the record to persist.
// The caller owns storage (a Map); this only holds the windowing math so it can
// be tested deterministically.
function evaluateFixedWindow(rec, now, windowMs, max) {
  if (!rec || now - rec.windowStart >= windowMs) {
    return { allowed: true, record: { count: 1, windowStart: now } };
  }
  if (rec.count >= max) {
    return { allowed: false, record: rec };
  }
  return { allowed: true, record: { count: rec.count + 1, windowStart: rec.windowStart } };
}

// ── Stale-entry purge (pure) ──────────────────────────────────────────────────
// S02-L3: `mintCooldown` (one key per userId ever seen, value = last-mint
// timestamp) has no purge job, unlike every sibling limiter Map in index.js
// (`ipHits`, `waitlistIpHits`, `authRateLimits`), so on a long-lived Render
// instance it grows by one entry per distinct userId forever — unbounded
// memory growth is itself a DoS surface. This holds only the "which keys are
// stale" decision so it is testable against plain arrays/timestamps, with no
// Map or real clock involved; the caller (index.js) owns the actual Map and
// setInterval and does the `.delete()`.
//
// `entries` is any iterable of `[key, timestampMs]` pairs — a `Map` satisfies
// this directly via its default iterator, so callers can pass the live Map.
function collectStaleKeys(entries, now, ttlMs) {
  const stale = [];
  const cutoff = now - ttlMs;
  for (const [key, timestampMs] of entries) {
    if (timestampMs < cutoff) stale.push(key);
  }
  return stale;
}

// ── Client IP resolution with configurable proxy trust (pure) ────────────────
// S04-M3: `getClientIp()` unconditionally trusted the rightmost entry of
// X-Forwarded-For as proxy-appended, hardcoding "exactly one trusted hop"
// (Render's edge). That is correct for the current deployment topology but
// wrong — and silently insecure — for any other one: zero proxies (XFF absent
// or fully attacker-controlled) or more than one trusted hop (e.g. a CDN in
// front of Render) both need a different pick, and there was no way to
// configure it without editing code. `trustedHops` makes the pick explicit:
//   0         → ignore X-Forwarded-For entirely, always use the socket address.
//   N (>=1)   → trust that the terminating proxy appended exactly N hops of
//               its own, and pick the Nth-from-right entry (N=1 reproduces the
//               original "rightmost" behavior byte-for-byte).
// Malformed/insufficient entries fall back to the socket address rather than
// guessing, so a misconfigured hop count fails toward "less trust", not more.
//
// S07-H1 (the admin-panel "session expired" bug): picking a FIXED
// Nth-from-right entry is wrong on Render. Render's edge does not append
// exactly one entry — the request passes through an internal load balancer
// that appends its own private address too, and that address is drawn from a
// rotating internal pool (10.26.x.x on one request, 10.28.x.x on the next).
// So the "client IP" this returned changed on virtually every request, which
// (a) made every IP-keyed limiter/lockout bucket meaningless and (b) made the
// admin session's IP binding fail with `ip_mismatch` on the very first
// /admin/api/* call after a successful login — the operator was bounced
// straight back to the gate with "Your session expired".
//
// The fix walks LEFT from the Nth-from-right entry and returns the first
// entry that is not an internal/private address. That is safe even though
// the leftmost entries are client-controlled: a request arriving from the
// public internet always has a PUBLIC address appended by the terminating
// proxy, so the walk stops at that real address before it can ever reach a
// forged one further left. A client that forges
// `X-Forwarded-For: 203.0.113.9` only produces
// `203.0.113.9, <real public client>, 10.26.x.x` — the walk skips the one
// private hop and returns the real address, not the forged one.
function pickClientIp(forwardedHeader, remoteAddress, trustedHops) {
  const fallback = remoteAddress || "unknown";
  const hops = Number.isInteger(trustedHops) ? trustedHops : 1;
  if (hops <= 0 || !forwardedHeader) return fallback;

  const entries = String(forwardedHeader).split(",").map((s) => s.trim()).filter(Boolean);
  if (entries.length < hops) return fallback;

  for (let i = entries.length - hops; i >= 0; i--) {
    if (entries[i] && !isInternalIpAddress(entries[i])) return entries[i];
  }
  // Every trusted entry was internal (a same-network health check, an
  // internal probe, or local dev): there is no public client address to
  // report, so fall back rather than inventing one.
  return fallback;
}

// ── Internal/private address classification (pure) ───────────────────────────
// Used by pickClientIp() to recognise proxy hops that can never be a real
// public client. Covers loopback, RFC1918 private space, RFC6598 carrier-grade
// NAT, link-local, IPv6 loopback/link-local/unique-local, and the "unknown"
// sentinel getClientIp() falls back to. Anything unrecognised is treated as
// PUBLIC (i.e. a usable client address), so a parse failure cannot silently
// make the walk skip past a genuine client entry.
function isInternalIpAddress(ip) {
  if (typeof ip !== "string") return true;
  let addr = ip.trim().toLowerCase();
  if (!addr || addr === "unknown") return true;
  if (addr.startsWith("[")) addr = addr.replace(/^\[|\]$/g, "");
  addr = addr.split("%")[0];

  const mapped = addr.match(/^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/);
  if (mapped) addr = mapped[1];

  if (addr.includes(":")) {
    if (addr === "::" || addr === "::1") return true;
    if (/^fe[89ab]/.test(addr)) return true; // fe80::/10 link-local
    if (/^f[cd]/.test(addr)) return true;    // fc00::/7 unique-local
    return false;
  }

  const octets = addr.split(".");
  if (octets.length !== 4) return true; // not an address we can reason about
  const [a, b] = octets.map((o) => parseInt(o, 10));
  if (!Number.isInteger(a) || !Number.isInteger(b)) return true;
  if (a === 10 || a === 127 || a === 0) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 169 && b === 254) return true; // link-local
  if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT
  return false;
}

// ── IPv6 /64-aware rate-limit key normalization (pure) ────────────────────────
// S04-M1: every IP-keyed limiter (waitlistIpHits, waitlistPollHits, ipHits,
// adminIpFails) used the raw client IP string as its Map key. A residential
// ISP delegates a whole /64 (2^64 addresses) to a single customer, and an
// attacker on that customer's own connection can rotate the last 64 bits of
// their address per request (many OSes do this automatically for privacy —
// "privacy extensions" / RFC 4941) at zero cost, so keying by the full 128-bit
// address makes the limiter's actual granularity "one bucket per request" for
// any IPv6 attacker — the limit is defeated entirely, not just weakened.
// IPv4 has no such delegated-block problem (a /32 *is* the single address)
// and is returned unchanged. IPv4-mapped IPv6 addresses (::ffff:a.b.c.d, used
// by some dual-stack proxies) are unwrapped to their IPv4 form for the same
// reason. Malformed input is returned unchanged rather than guessed at, so a
// parse failure fails toward "no worse than pre-fix", not toward silently
// merging unrelated clients into one bucket.
function normalizeIpForRateLimit(ip) {
  if (typeof ip !== "string" || ip.length === 0) return ip;

  // Strip an IPv6 zone index (fe80::1%eth0) and enclosing brackets ([::1]),
  // both of which can appear on remoteAddress/XFF values but are irrelevant
  // to which /64 an address belongs to.
  let addr = ip.split("%")[0];
  if (addr.startsWith("[") && addr.endsWith("]")) addr = addr.slice(1, -1);

  if (!addr.includes(":")) return ip; // IPv4, or already-unparseable — leave as-is

  const mapped = addr.match(/^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/i);
  if (mapped) return mapped[1];

  // Expand "::" (at most one occurrence in a valid address) into explicit
  // zero groups so the /64 prefix (first 4 of 8 groups) is unambiguous
  // regardless of where the shorthand run of zeros falls.
  const halves = addr.split("::");
  if (halves.length > 2) return ip; // malformed (multiple "::") — don't guess

  let groups;
  if (halves.length === 2) {
    const left  = halves[0] ? halves[0].split(":") : [];
    const right = halves[1] ? halves[1].split(":") : [];
    const missing = 8 - left.length - right.length;
    if (missing < 0) return ip; // malformed — too many groups already
    groups = [...left, ...Array(missing).fill("0"), ...right];
  } else {
    groups = addr.split(":");
    if (groups.length !== 8) return ip; // malformed — not a full address
  }

  return groups.slice(0, 4).join(":");
}

// ── Backblaze B2 SigV4 presign helpers removed (S03-L3 / S04-I2) ──────────────
// `b2HmacKey` and `buildB2PresignUrl` used to live here. They were the signing
// math behind the server's `b2PresignUrl` helper, which was itself dead code
// (SEC-A01 replaced presigned-URL issuance with per-object capability tokens).
// Removed along with that helper and the B2 credential env reads in
// server/index.js so the unused signing path can no longer imply the server
// needs `B2_KEY_ID` / `B2_APPLICATION_KEY`.

// Maps a failed /linkPreview fetch to a small, stable client contract.
// Never return the underlying error text: it can contain internal hostnames,
// DNS answers, URLs, or network topology. These labels describe the retry/UI
// class only, not the target's private state.
function linkPreviewFailureStatus(error) {
  const message = error && typeof error.message === "string" ? error.message : "";
  if (/^Blocked redirect target\b|^Blocked preview target\b/.test(message)) {
    return "blocked";
  }
  const code = error && (error.code || (error.cause && error.cause.code));
  if ((error && error.name === "AbortError") ||
      ["ABORT_ERR", "ETIMEDOUT", "ECONNABORTED", "UND_ERR_CONNECT_TIMEOUT", "UND_ERR_HEADERS_TIMEOUT"].includes(code)) {
    return "timeout";
  }
  return "unavailable";
}

// ── /acknowledgeRotation transaction decision (pure) ─────────────────────────
// S06-M6: the four-branch decision inside /acknowledgeRotation's Firestore
// transaction had no regression coverage — the same gap S06-L1 closed for
// /duress-lock's nonce-expiry check, and for the same reason: this endpoint's
// gate exists precisely because a real, currently-active lock must never look
// like a successful (or even a benign no-op) acknowledgement, so a silent
// regression here is a fail-OPEN, not a fail-closed, bug. Extracted as a pure
// function so the branches are directly testable without a Firestore
// emulator; index.js calls this from inside the real transaction and only
// owns the actual txn.update() side effect, so the tested logic IS the code
// path that runs, not a copy that could drift.
//
// `lockExists` / `lockData` mirror a Firestore DocumentSnapshot's `.exists`
// and `.data()` (pass `snap.exists` and, only when it's true, `snap.data()`).
//
// Returns `{ acknowledged, reason, relocked, shouldClearFlag }`:
//   - `shouldClearFlag` is the ONLY signal telling the caller to txn.update()
//     `rotationRequired: false` — every other branch must leave the document
//     untouched.
//   - `relocked` is the one case the caller must turn into an HTTP 403, never
//     a 200: it means `locked === true` right now, so this device's ack must
//     be refused even though it may hold two perfectly valid new codes.
//   - `reason` is omitted (left `undefined`) only on the success branch, so
//     callers that spread it into a JSON response reproduce the endpoint's
//     original `{ acknowledged: true }` (no `reason` key) wire shape.
function decideRotationAcknowledgement(lockExists, lockData) {
  if (!lockExists) {
    // Nothing to acknowledge — there was never a lock doc for this uid.
    return { acknowledged: false, reason: "no-lock", relocked: false, shouldClearFlag: false };
  }
  const data = lockData || {};
  // A real, currently-active lock must never be lifted by this endpoint — this
  // call only ever clears the *rotation* flag, never `locked` itself. If the
  // account was locked again since the unfreeze that set rotationRequired, a
  // client still mid-flow from the earlier unfreeze must not be able to clear
  // anything here.
  if (data.locked === true) {
    return { acknowledged: false, reason: "locked", relocked: true, shouldClearFlag: false };
  }
  if (data.rotationRequired !== true) {
    // Already cleared by an earlier call, or never set — idempotent no-op so
    // a retry after a successful-but-unconfirmed first attempt succeeds
    // rather than erroring.
    return { acknowledged: false, reason: "not-due", relocked: false, shouldClearFlag: false };
  }
  return { acknowledged: true, reason: undefined, relocked: false, shouldClearFlag: true };
}

module.exports = {
  notificationBody,
  safeTokenEqual,
  timingSafeEqualHex,
  validAdminUid,
  getCookie,
  isBlockedPreviewHost,
  previewDomainFromUrl,
  linkPreviewFailureStatus,
  clampTurnCredentialTtlSeconds,
  resolveNonceExpiry,
  isNonceUsable,
  evaluateFixedWindow,
  collectStaleKeys,
  pickClientIp,
  isInternalIpAddress,
  normalizeIpForRateLimit,
  decideRotationAcknowledgement,
};
