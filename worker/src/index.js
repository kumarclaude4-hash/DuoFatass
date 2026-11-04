import { AwsClient } from 'aws4fetch';
import { AdminLockoutCounter } from './adminLockoutDurableObject.js';

// Durable Object classes must be exported from this entry module for the
// `durable_objects` binding in wrangler.jsonc to resolve them — see
// adminLockoutDurableObject.js for what this class does and why it exists.
export { AdminLockoutCounter };

// ─── Hard limits ──────────────────────────────────────────────────────────────
// R2 free tier: 10 GB storage, 1 M Class A ops/month, 10 M Class B ops/month.
// Enforce at 95% = 9.5 GB to stay safely within the free tier.
const MAX_R2_BYTES = 9.5 * 1024 * 1024 * 1024; // 9.5 GB

// Workers free tier: 100,000 requests/day (not per month). Enforce at 90% = 90K/day.
const MAX_DAILY_REQUESTS = 90_000;

// B2 free tier: 10 GB — tracked for informational use only, no hard cap enforced.
const MAX_B2_BYTES = 10 * 1024 * 1024 * 1024;

// Maximum objects migrated per cron run. 3 subrequests per migration (get + put + delete)
// plus list overhead keeps total well under the 1,000 subrequest-per-invocation limit.
const MAX_MIGRATIONS_PER_RUN = 200;

// ─── Tier config ──────────────────────────────────────────────────────────────
function hotTierMs(env)  { return safeInt(env.HOT_TIER_DAYS || '30', 30) * 86_400_000; }
function maxFileSize(env){ return safeInt(env.MAX_FILE_SIZE  || '524288000', 524_288_000); }
function rateLimit(env)  { return safeInt(env.RATE_LIMIT_PER_MIN || '120', 120); }

// ─── Safe integer parsing ─────────────────────────────────────────────────────
// Guards against KV returning null/undefined/'NaN', which would poison all
// arithmetic and permanently corrupt counters.
function safeInt(val, fallback = 0) {
  const n = parseInt(val, 10);
  return Number.isFinite(n) ? n : fallback;
}

// ─── B2 S3-compatible client ──────────────────────────────────────────────────
function getB2Client(env) {
  return new AwsClient({
    accessKeyId:     env.B2_ACCESS_KEY_ID,
    secretAccessKey: env.B2_SECRET_ACCESS_KEY,
    region:          env.B2_REGION,
    service:         's3',
  });
}

function b2Url(env, key) {
  const encoded = key.split('/').map(encodeURIComponent).join('/');
  return `${env.B2_ENDPOINT}/${env.B2_BUCKET}/${encoded}`;
}

// ─── Object key format allow-list ─────────────────────────────────────────────
// DuoShield paths: media/<chatId|groupId>/<uuid>.<ext> | voice/<chatId|groupId>/<uuid>.<ext>
// Must stay byte-identical to MEDIA_KEY_FORMAT in server/index.js.
const KEY_FORMAT = /^(media|voice)\/[a-zA-Z0-9-]{16,80}\/[a-zA-Z0-9._-]{1,100}\.(jpg|mp4|m4a|3gp)$/;

// Profile photos: avatars/<ownerUid>_<millis>.jpg (SettingsActivity). This is a
// TWO-segment key, so it never matched KEY_FORMAT and every avatar PUT/GET was
// rejected 400 here, exactly as /mediaToken rejected it upstream — the
// user-visible "Upload failed: mediaToken denied [400]: Invalid key format".
// Kept as its own anchored pattern rather than by loosening the prefix above,
// so an avatar can only ever be a JPEG and media keys stay just as strict.
// Must stay byte-identical to AVATAR_KEY_FORMAT in server/lib/mediaScope.js:
// the two tiers accepting different key sets is what produced this bug.
const AVATAR_KEY_FORMAT = /^avatars\/[A-Za-z0-9-]{8,64}_\d{10,17}\.jpg$/;

/** Every key shape this Worker serves. Mirrors isAllowedMediaKey() on the server. */
function isAllowedKey(key) {
  return KEY_FORMAT.test(key) || AVATAR_KEY_FORMAT.test(key);
}

// ─── S03-M1: response Content-Type is derived from the key, never trusted ────
// The key extension is already tightly allow-listed by KEY_FORMAT, but before
// this fix the *declared* Content-Type header (fully attacker-controlled —
// nothing validates it against the extension) was stored verbatim at PUT time
// and replayed unmodified on every GET, with no `X-Content-Type-Options` and
// no `Content-Disposition`. A `write`-token holder could store an object at
// `…/x.jpg` served as `text/html` or `image/svg+xml` with attacker-chosen
// bytes; combined with permissive CORS and any future browser/WebView
// consumer of these URLs, that is a stored-content-type confusion, not just
// a cosmetic mismatch. Deriving the served type purely from the (immutable,
// allow-listed) extension — for both storage and serving, on both tiers —
// removes the client header from the trust boundary entirely, including for
// objects that were already stored with an attacker-chosen type before this
// fix shipped, since GET no longer reads the stored value.
const CONTENT_TYPE_BY_EXT = {
  jpg: 'image/jpeg',
  mp4: 'video/mp4',
  m4a: 'audio/mp4',
  '3gp': 'video/3gpp',
};

function contentTypeForKey(key) {
  // Derive the extension from the allow-listed pattern that actually matched.
  // Reading KEY_FORMAT's capture group alone would return null for an avatar
  // key and serve every profile photo as application/octet-stream, which Glide
  // would refuse to decode — a silently broken image rather than an error.
  // AVATAR_KEY_FORMAT permits only `.jpg`, so the type is fixed for that branch.
  if (AVATAR_KEY_FORMAT.test(key)) return CONTENT_TYPE_BY_EXT.jpg;
  const match = KEY_FORMAT.exec(key);
  const ext = match?.[2];
  return CONTENT_TYPE_BY_EXT[ext] || 'application/octet-stream';
}

// ─── Range requests ──────────────────────────────────────────────────────────
// Chunked media playback (format v2 — see B2StorageHelper.CHUNKED_FORMAT_VERSION)
// fetches a 16-byte header and then one 1 MiB chunk at a time, so the client can
// start playing and seek without pulling the whole object down. That only works
// if this Worker honours `Range`; before this it always streamed the full body
// from whichever tier held the object, and a "range" GET silently returned
// everything.
//
// Deliberately narrow: only a single `bytes=start-end` (or `bytes=start-`) range
// is supported. Multipart ranges would mean emitting a multipart/byteranges body
// that no client here asks for, and suffix ranges (`bytes=-N`) likewise have no
// caller — anything unrecognised is reported as such so the caller sees a clear
// 416 instead of a full-body 200 it would then mistake for its requested slice.
//
// Returns:
//   null                — no Range header; serve the whole object as before.
//   'invalid'           — header present but unusable, or start beyond EOF.
//   { start, end, length } — inclusive byte offsets, clamped to `size`.
function parseByteRange(header, size) {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match) return 'invalid';
  const [, rawStart, rawEnd] = match;
  // A suffix range has an empty start; not supported (see above).
  if (rawStart === '') return 'invalid';
  const start = Number(rawStart);
  if (!Number.isSafeInteger(start) || start < 0) return 'invalid';
  // An empty end means "to the last byte". Clamp an over-long end rather than
  // rejecting it — RFC 9110 requires the server to satisfy what it can.
  const end = rawEnd === '' ? size - 1 : Math.min(Number(rawEnd), size - 1);
  if (!Number.isSafeInteger(end) || end < start) return 'invalid';
  // `start === size` is unsatisfiable even for a zero-length object, which is
  // the one case where there is no valid first byte to serve.
  if (start >= size) return 'invalid';
  return { start, end, length: end - start + 1 };
}

// ─── Response helpers ─────────────────────────────────────────────────────────
// S03-L4 fix: json() always merges in whatever CORS headers apply to this
// request, so quota/rate-limit rejections (429/507/etc.) are just as
// readable to a browser client as the success paths already were — a future
// browser client no longer sees every failure as an opaque network error.
function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...extraHeaders },
  });
}

// S08-I3 fix: no wildcard Access-Control-Allow-Origin. The Android client
// never sends an `Origin` header and ignores CORS entirely — dropping the
// wildcard breaks nothing for it today. `Access-Control-Allow-Origin` is
// only ever set to a specific, explicitly allow-listed origin (never `*`),
// removing the "ACAO: * + Authorization" combination that reads as
// credential-bearing CORS and would otherwise be copied as a template.
// Configure via CORS_ALLOWED_ORIGINS (comma-separated) if a browser client
// is added later; until then every field below is present except ACAO.
function corsHeaders(request, env) {
  const headers = {
    'Access-Control-Allow-Methods': 'GET, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Client-ID',
  };
  const allowList = (env?.CORS_ALLOWED_ORIGINS || '')
    .split(',')
    .map((o) => o.trim())
    .filter(Boolean);
  const origin = request?.headers.get('Origin');
  if (origin && allowList.includes(origin)) {
    headers['Access-Control-Allow-Origin'] = origin;
    headers['Vary'] = 'Origin';
  }
  return headers;
}

// ─── Operator /stats authentication ─────────────────────────────────────────
// Set via: npx wrangler secret put STATS_SECRET
// The operator-only /stats view requires:
//   Authorization: Bearer <STATS_SECRET>
// Health check is intentionally unauthenticated.
//
// S08-H1: this gate deliberately consumes STATS_SECRET, NOT the historical
// WORKER_SECRET. WORKER_SECRET was compiled into every released APK
// (app/build.gradle BuildConfig), so any published release leaked it and it
// must be treated as a public value. It no longer authorizes anything: the
// media data plane moved to per-object capability tokens (verifyMediaToken,
// SEC-A01), and /stats — the last consumer — now requires a distinct
// operator-only secret that was never shipped to a client. A value recovered
// from any APK therefore opens nothing here, independent of whether the
// operator has rotated the old WORKER_SECRET yet (that rotation is still a
// tracked runbook item; this change means the leak is dead even if it lags).
//
// FAIL CLOSED if STATS_SECRET is unset — a missing secret must never widen
// access. (Previously the shared secret fell back to "open mode", silently
// exposing the stats view; that is gone.) Local dev should set a throwaway
// STATS_SECRET via `.dev.vars` / `wrangler secret put` rather than relying on
// any open mode.
async function isStatsAuthorized(request, env) {
  if (!env.STATS_SECRET) {
    console.error('STATS_SECRET is not configured — denying /stats (fail closed)');
    return false;
  }
  const supplied = request.headers.get('Authorization') ?? '';
  const expected = `Bearer ${env.STATS_SECRET}`;
  // Use constant-time comparison to prevent timing-oracle attacks that could
  // recover the secret byte-by-byte. JS string === short-circuits on the first
  // differing character, leaking secret length and content via response time.
  const enc = new TextEncoder();
  const a = enc.encode(supplied);
  const b = enc.encode(expected);
  // Lengths must be equal first; if they differ we still do a dummy comparison
  // on identically-sized buffers to avoid leaking the expected length.
  if (a.byteLength !== b.byteLength) {
    // Compare against itself so the timing is the same regardless of the
    // supplied length — no short-circuit possible.
    await crypto.subtle.digest('SHA-256', a); // consume time
    return false;
  }
  const match = await crypto.subtle.timingSafeEqual(a, b);
  return match;
}

// ─── /adminLockout authentication ───────────────────────────────────────────
// Set via: npx wrangler secret put ADMIN_LOCKOUT_SECRET
// Render (the ONLY intended caller — see server/lib/adminLockoutWorkerClient.js)
// authenticates with:
//   Authorization: Bearer <ADMIN_LOCKOUT_SECRET>
//
// Same fail-closed, constant-time-comparison shape as isStatsAuthorized
// above, deliberately kept as a separate secret rather than reusing
// STATS_SECRET: /adminLockout and /stats are different trust boundaries
// (one server-to-server credential meant only for Render, one operator
// bearer token), and a leak of one must not also grant the other.
async function isAdminLockoutAuthorized(request, env) {
  if (!env.ADMIN_LOCKOUT_SECRET) {
    console.error('ADMIN_LOCKOUT_SECRET is not configured — denying /adminLockout (fail closed)');
    return false;
  }
  const supplied = request.headers.get('Authorization') ?? '';
  const expected = `Bearer ${env.ADMIN_LOCKOUT_SECRET}`;
  const enc = new TextEncoder();
  const a = enc.encode(supplied);
  const b = enc.encode(expected);
  if (a.byteLength !== b.byteLength) {
    await crypto.subtle.digest('SHA-256', a); // consume time, no short-circuit on length
    return false;
  }
  return crypto.subtle.timingSafeEqual(a, b);
}

// ─── Scoped capability tokens (SEC-A01) ───────────────────────────────────────
// The data plane (GET/PUT/DELETE on an object key) is authorized per object, not
// by a shared bearer secret. The Android app never holds a long-lived credential:
// it exchanges its Firebase ID token at the push server's POST /mediaToken for a
// token bound to exactly one (key, operation) pair with a short expiry, and the
// server only issues one after confirming the caller participates in that chat
// or group.
//
// Why the old model was inadequate: WORKER_SECRET was compiled into every APK,
// so it was extractable by any user, and it authenticated "a copy of the app"
// rather than authorizing "this user for this object". Combined with object keys
// that legitimately travel through Firestore chat documents, a holder could
// read, overwrite or DELETE another user's media. Signing per object closes
// that: the signature covers the key, so a token stolen for one object grants
// nothing anywhere else.
//
// Set the same value here and on the push server:
//   npx wrangler secret put MEDIA_TOKEN_SECRET
//
// Wire format: v1.<op>.<expiresAt>.<uidTag>.<jti>.<base64url-hmac-sha256>
// Signed payload: `v1|<op>|<expiresAt>|<uidTag>|<jti>|<key>`
//
// S03-M3: `jti` (added alongside the pre-existing fields, not replacing any
// of them) is a random per-mint identifier. It exists so `delete` tokens —
// the one verb with no undo — can be marked single-use in KV once consumed
// (see isTokenAlreadyUsed/markTokenUsed below); `read`/`write` tokens still
// carry a jti for wire-format uniformity but are not tracked for reuse,
// since re-fetching or re-uploading the same object within a short TTL is
// normal client behavior, not something to block.
const METHOD_TO_OP = { GET: 'read', PUT: 'write', DELETE: 'delete' };

function b64urlToBytes(s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/');
  const pad = b64.length % 4 === 0 ? '' : '='.repeat(4 - (b64.length % 4));
  const bin = atob(b64 + pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function hmacSha256(secret, message) {
  const enc = new TextEncoder();
  const cryptoKey = await crypto.subtle.importKey(
    'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  return new Uint8Array(await crypto.subtle.sign('HMAC', cryptoKey, enc.encode(message)));
}

/**
 * Verifies the capability token for this exact request.
 *
 * @returns {Promise<{ok: true, holder: string} | {ok: false, status: number, error: string}>}
 */
async function verifyMediaToken(request, env, key) {
  if (!env.MEDIA_TOKEN_SECRET) {
    console.error('MEDIA_TOKEN_SECRET is not configured — denying (fail closed)');
    return { ok: false, status: 503, error: 'Storage auth not configured' };
  }

  const expectedOp = METHOD_TO_OP[request.method];
  if (!expectedOp) return { ok: false, status: 405, error: 'Method not allowed' };

  const header = request.headers.get('Authorization') ?? '';
  if (!header.startsWith('Bearer ')) {
    return { ok: false, status: 401, error: 'Missing capability token' };
  }
  const token = header.slice(7).trim();
  const parts = token.split('.');
  // S03-M3: wire format grew a 6th segment (jti). A 5-part token is the
  // pre-fix shape — rejecting it as malformed (rather than tolerating both
  // shapes) means a captured old-format token cannot be replayed forever;
  // the server and Worker deploy together (same invariant already relied on
  // for other wire-format fields here), so no legitimate client ever sends one.
  if (parts.length !== 6 || parts[0] !== 'v1') {
    return { ok: false, status: 401, error: 'Malformed capability token' };
  }

  const [, op, expRaw, holder, jti, sig] = parts;

  // Bind the token to this verb. A read token must not be replayable as a delete.
  if (op !== expectedOp) {
    return { ok: false, status: 403, error: 'Token not valid for this operation' };
  }

  const expiresAt = safeInt(expRaw, 0);
  if (expiresAt <= 0 || Date.now() > expiresAt) {
    return { ok: false, status: 401, error: 'Capability token expired' };
  }

  if (!jti) {
    return { ok: false, status: 401, error: 'Malformed capability token' };
  }

  // Recompute over the key from the request path — this is what scopes the
  // token to a single object.
  const payload  = `v1|${op}|${expiresAt}|${holder}|${jti}|${key}`;
  const expected = await hmacSha256(env.MEDIA_TOKEN_SECRET, payload);

  let supplied;
  try { supplied = b64urlToBytes(sig); }
  catch { return { ok: false, status: 401, error: 'Malformed token signature' }; }

  if (supplied.byteLength !== expected.byteLength) {
    return { ok: false, status: 403, error: 'Invalid capability token' };
  }
  if (!(await crypto.subtle.timingSafeEqual(supplied, expected))) {
    return { ok: false, status: 403, error: 'Invalid capability token' };
  }

  return { ok: true, holder, jti, expiresAt };
}

// ─── Single-use tracking for delete tokens (S03-M3) ───────────────────────────
// A capability token is otherwise a stateless bearer credential valid for its
// entire TTL — anything that observes one (a proxy log, a crash report) can
// replay it for as long as it remains unexpired. That is tolerable for
// `read`/`write` (re-fetching or re-uploading the same object is normal), but
// not for `delete`, the one verb with no undo. Marking a delete token's `jti`
// used in KV the first time it is actually consumed closes the replay window
// without touching the read/write paths at all.
//
// Same best-effort characteristics as the rest of this file's KV-backed
// counters (no Durable Objects on this project — see S03-I2): without
// `RATE_KV` configured there is no way to remember a used token at all, so
// this degrades to "not enforced" rather than failing closed. That mirrors
// every other KV-gated control here (rate limiting, quotas) and is a known,
// documented limitation of the dev/no-KV configuration, not a silent gap.
async function isTokenAlreadyUsed(env, jti) {
  if (!env.RATE_KV || !jti) {
    // DELETE is irreversible. If the replay store is unavailable, fail closed
    // rather than silently turning a single-use capability into a reusable one.
    throw new Error('Delete-token replay protection is unavailable');
  }
  try {
    return (await env.RATE_KV.get(`token:used:${jti}`)) !== null;
  } catch (err) {
    console.error('Delete-token replay lookup failed:', err?.message || err);
    throw new Error('Delete-token replay protection is unavailable');
  }
}

async function markTokenUsed(env, jti, ttlSeconds) {
  if (!env.RATE_KV || !jti) throw new Error('Delete-token replay protection is unavailable');
  try {
    await env.RATE_KV.put(`token:used:${jti}`, '1', {
      expirationTtl: Math.max(60, ttlSeconds),
    });
  } catch (err) {
    console.error('Delete-token replay write failed:', err?.message || err);
    throw new Error('Delete-token replay protection is unavailable');
  }
}

// ─── KV helpers ───────────────────────────────────────────────────────────────
function dayKey() {
  const d = new Date();
  return `global:req:${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
}

async function kvGet(env, key, fallback = '0') {
  if (!env.RATE_KV) return fallback;
  return (await env.RATE_KV.get(key)) ?? fallback;
}

async function kvSet(env, key, value, opts = {}) {
  if (!env.RATE_KV) return;
  try {
    await env.RATE_KV.put(key, String(value), opts);
  } catch (err) {
    // KV write quota exhausted (1K writes/day free tier) or transient error.
    // Safety controls degrade gracefully — log and continue rather than crashing.
    console.error(`KV write failed for ${key}: ${err.message}`);
  }
}

// ─── Daily request gate ───────────────────────────────────────────────────────
// Free tier: 100K requests/day. Enforce at 90K (90%).
//
// KV write cost is amortised via 1-in-10 sampling: each write adds 10 to the
// counter instead of 1. This keeps accuracy within ±9 while consuming only
// ~0.1 KV writes per request instead of 1, reducing write pressure ~10×.
async function checkDailyRequestLimit(env, cors) {
  if (!env.RATE_KV) return null;
  const key   = dayKey();
  const count = safeInt(await kvGet(env, key));
  if (count >= MAX_DAILY_REQUESTS) {
    return json({
      error: 'Daily request limit reached (90K/day). Resets at midnight UTC.',
      count,
      limit: MAX_DAILY_REQUESTS,
    }, 429, cors);
  }
  // Sampled write: fires ~10% of the time, adds 10 to preserve the expected value.
  if (Math.random() < 0.1) {
    await kvSet(env, key, count + 10, { expirationTtl: 2 * 86_400 }); // 48h TTL
  }
  return null;
}

// ─── Per-isolate in-memory rate limiter ───────────────────────────────────────
// Uses a module-level Map that persists for the lifetime of the Worker isolate
// (typically minutes to hours on a given Cloudflare edge node).
//
// This is advisory — it is not globally consistent across edge locations — but
// it prevents burst abuse within a single PoP without consuming any KV writes.
// KV-based rate limiting is ineffective on the free tier anyway (KV writes have
// ~60s eventual consistency, making cross-PoP enforcement impossible without
// Durable Objects, which require a paid plan).
//
// The bucket key is derived from a SHA-256 truncation of the Authorization
// header value, NOT from the client-supplied X-Client-ID header.
// Using X-Client-ID let any client:
//   (a) bypass the limit by cycling through arbitrary header values, or
//   (b) exhaust another client's quota by sending that client's known ID.
// The credential hash is non-spoofable (only the authorized app knows the
// WORKER_SECRET) and stable within a session.
const perUserCounts = new Map();

async function credentialBucketKey(request) {
  const auth = request.headers.get('Authorization') ?? 'anon';
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(auth));
  // Take the first 8 bytes (64 bits) as a hex string — enough entropy for
  // bucket identity, short enough to avoid memory blowup in the Map.
  return Array.from(new Uint8Array(hash).slice(0, 8))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

async function checkPerUserRateLimit(request, env, cors) {
  const limit     = rateLimit(env);
  const bucketId  = await credentialBucketKey(request);
  const minuteKey = `${bucketId}:${Math.floor(Date.now() / 60_000)}`;
  const count     = perUserCounts.get(minuteKey) ?? 0;
  if (count >= limit) {
    return json({ error: `Rate limit exceeded (${limit} req/min)` }, 429, cors);
  }
  perUserCounts.set(minuteKey, count + 1);
  // Prune stale minute buckets to prevent unbounded memory growth within the isolate.
  const nowMinute = Math.floor(Date.now() / 60_000);
  for (const [k] of perUserCounts) {
    const keyMinute = safeInt(k.split(':').at(-1), nowMinute);
    if (nowMinute - keyMinute > 2) perUserCounts.delete(k);
  }
  return null;
}

// ─── R2 storage tracking ──────────────────────────────────────────────────────
// Authoritative R2 bytes are written by the nightly cron (full object scan).
// Per-request writes keep the counter timely between cron runs.
// B2 bytes are reconciled exclusively by the cron via B2 ListObjectsV2 —
// never tracked per-request to conserve KV write quota.
async function getR2Bytes(env) {
  return safeInt(await kvGet(env, 'global:storage:r2'));
}

async function adjustR2(env, deltaBytes) {
  if (!env.RATE_KV || deltaBytes === 0) return;
  const cur = safeInt(await kvGet(env, 'global:storage:r2'));
  const next = Math.max(0, cur + deltaBytes);
  await kvSet(env, 'global:storage:r2', next);
  // Best-effort observability only: concurrent uploads can both pass the
  // pre-check in the PUT handler and land here before either write is
  // visible, so this counter is not a hard reservation (true atomicity
  // would need Durable Objects, not provisioned for this project). Log
  // loudly if we drift past the cap so it's visible in Worker logs rather
  // than silently over-accepting indefinitely.
  if (next > MAX_R2_BYTES) {
    console.warn(`R2 usage (${next} B) exceeds cap (${MAX_R2_BYTES} B) — likely concurrent uploads racing the pre-check`);
  }
}

// ─── Per-user storage quota (S03-H3) ───────────────────────────────────��──────
// The global MAX_R2_BYTES cap alone lets one uploader (~19 uploads at the
// default 500 MB file size) fill the entire 9.5 GB free-tier budget and stop
// uploads for every user for weeks (objects only leave R2 via the 30-day
// cron). This adds a second, per-holder ceiling — keyed on `cap.holder` from
// the capability token (SEC-A01), never on a client-supplied header — that
// is checked in the PUT path *before* `HOT_BUCKET.put`, alongside the
// existing global check, not instead of it.
//
// Same non-atomic, best-effort accounting model as `adjustR2` above (no
// Durable Objects on this tier — see S03-I2) — concurrent uploads from the
// same holder can overshoot slightly, but the exhaustion attack this closes
// needs dozens of sequential uploads, not a tight race.
function maxUserBytes(env) { return safeInt(env.MAX_USER_BYTES || '1073741824', 1_073_741_824); } // 1 GB default

async function getUserBytes(env, holder) {
  return safeInt(await kvGet(env, `user:storage:${holder}`));
}

async function adjustUserBytes(env, holder, deltaBytes) {
  if (!env.RATE_KV || !holder || deltaBytes === 0) return;
  const key  = `user:storage:${holder}`;
  const cur  = safeInt(await kvGet(env, key));
  const next = Math.max(0, cur + deltaBytes);
  await kvSet(env, key, next);
}

// ─── B2 ListObjectsV2 → authoritative byte total ──────────────────────────────
// Used during the scheduled cron to reconcile the B2 storage counter.
// Returns null on error so the caller can skip the KV update rather than
// overwriting a valid counter with 0.
async function getB2TotalBytes(b2, env) {
  let total             = 0;
  let continuationToken = '';
  do {
    const url = new URL(`${env.B2_ENDPOINT}/${env.B2_BUCKET}`);
    url.searchParams.set('list-type', '2');
    url.searchParams.set('max-keys', '1000');
    if (continuationToken) url.searchParams.set('continuation-token', continuationToken);

    const resp = await b2.fetch(url.toString());
    if (!resp.ok) {
      console.error(`B2 ListObjectsV2 failed: ${resp.status}`);
      return null;
    }
    const xml = await resp.text();
    // Simple regex — <Size> values in S3 XML are guaranteed to be plain integers.
    for (const m of xml.matchAll(/<Size>(\d+)<\/Size>/g)) {
      total += safeInt(m[1]);
    }
    continuationToken = xml.match(/<NextContinuationToken>([^<]+)<\/NextContinuationToken>/)?.[1] ?? '';
  } while (continuationToken);
  return total;
}

// ─── Main fetch handler ───────────────────────────────────────────────────────
export default {
  async fetch(request, env, ctx) {
    // Computed once per request: S08-I3 narrows this to a specific
    // allow-listed origin (never `*`); S03-L4 requires every response below
    // — success or rejection — to carry it.
    const cors    = corsHeaders(request, env);
    const respond = (data, status) => json(data, status, cors);

    // CORS preflight — no auth, no quota.
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: cors });
    }

    const url = new URL(request.url);

    // ── Health check — unauthenticated, does not count against quota ──────────
    if (url.pathname === '/' || url.pathname === '/health') {
      return respond({ status: 'ok', service: 'duoshield-storage' });
    }

    // ── Stats endpoint (admin view) — gated by the operator-only STATS_SECRET ─
    // /stats is an operator-only view, not a per-user object operation, so it
    // keeps a shared-secret bearer check — but against STATS_SECRET, a secret
    // that was never shipped to a client, NOT the APK-leaked WORKER_SECRET
    // (S08-H1). The data plane (GET/PUT/DELETE on an object key) does NOT use
    // any shared secret — it requires a per-object capability token
    // (verifyMediaToken) minted by the push server. See SEC-A01 below.
    if (url.pathname === '/stats') {
      if (!await isStatsAuthorized(request, env)) {
        return new Response(JSON.stringify({ error: 'Unauthorized' }), {
          status:  401,
          headers: { 'Content-Type': 'application/json', ...cors },
        });
      }
      const [r2Raw, b2Raw, reqRaw] = await Promise.all([
        kvGet(env, 'global:storage:r2'),
        kvGet(env, 'global:storage:b2'),
        kvGet(env, dayKey()),
      ]);
      const r2Bytes  = safeInt(r2Raw);
      const b2Bytes  = safeInt(b2Raw);
      const reqCount = safeInt(reqRaw);
      return respond({
        r2: {
          used_bytes:      r2Bytes,
          limit_bytes:     MAX_R2_BYTES,
          used_pct:        parseFloat((r2Bytes  / MAX_R2_BYTES  * 100).toFixed(2)),
          remaining_bytes: Math.max(0, MAX_R2_BYTES - r2Bytes),
          note:            'Capped at 9.5 GB (95% of 10 GB free tier). Reconciled nightly by cron.',
        },
        b2: {
          used_bytes:  b2Bytes,
          limit_bytes: MAX_B2_BYTES,
          used_pct:    parseFloat((b2Bytes / MAX_B2_BYTES * 100).toFixed(2)),
          note:        'Permanent storage (no auto-expiry). Reconciled nightly via B2 ListObjectsV2.',
        },
        requests: {
          today_approx:     reqCount,
          limit_per_day:    MAX_DAILY_REQUESTS,
          remaining_approx: Math.max(0, MAX_DAILY_REQUESTS - reqCount),
          note:             'Sampled counter (±10 accuracy). Resets at midnight UTC.',
        },
      });
    }

    // ── /adminLockout — atomic fixed-window counters for the Render server ──
    // Includes admin brute-force lockout and namespaced authenticated API
    // rate-limit buckets; gated by ADMIN_LOCKOUT_SECRET (server-to-server
    // only, not an operator-facing view like /stats). See adminLockoutDurableObject.js
    // for why this is a Durable Object rather than a KV counter, and
    // server/lib/adminLockoutWorkerClient.js for the caller.
    //
    // Request shape: POST /adminLockout, JSON body
    //   { action: 'record' | 'status' | 'reset', key: '<normalized-ip>', windowMs?, maxFails? }
    // `key` is opaque to this Worker — it is whatever
    // pure.normalizeIpForRateLimit produced on the Render side (S04-M1's
    // /64-collapsed IPv6 form or a plain IPv4 literal) and is used only as
    // the Durable Object instance name, never parsed or validated as an IP
    // here. Bounded length only, to keep `idFromName` inputs sane.
    if (url.pathname === '/adminLockout') {
      if (!await isAdminLockoutAuthorized(request, env)) {
        return respond({ error: 'Unauthorized' }, 401);
      }
      if (request.method !== 'POST') {
        return respond({ error: 'Method not allowed' }, 405);
      }
      if (!env.ADMIN_LOCKOUT_DO) {
        // Fail closed on the Worker's own response (the caller's fallback
        // is Render-side — see adminLockoutStore.js) rather than throwing
        // an unhandled error that could produce an ambiguous 5xx.
        return respond({ error: 'Admin lockout Durable Object not configured' }, 503);
      }

      let body;
      try {
        body = await request.json();
      } catch {
        return respond({ error: 'Malformed request body' }, 400);
      }
      const { action, key, windowMs, maxFails } = body ?? {};
      if (typeof key !== 'string' || key.length === 0 || key.length > 128) {
        return respond({ error: 'Missing or invalid key' }, 400);
      }
      if (!['record', 'status', 'reset'].includes(action)) {
        return respond({ error: 'Missing or invalid action' }, 400);
      }

      const id = env.ADMIN_LOCKOUT_DO.idFromName(key);
      const stub = env.ADMIN_LOCKOUT_DO.get(id);
      const doResponse = await stub.fetch('https://admin-lockout.internal/', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, windowMs, maxFails }),
      });
      const text = await doResponse.text();
      return new Response(text, {
        status: doResponse.status,
        headers: { 'Content-Type': 'application/json', ...cors },
      });
    }

    // Object key: everything after the leading slash.
    // DuoShield paths: media/<chatId|groupId>/<uuid>.<ext> | voice/<chatId|groupId>/<uuid>.<ext>
    //
    // S03-L2: a malformed percent-escape (e.g. a lone `%` or `%zz`) makes
    // `decodeURIComponent` throw `URIError` — previously uncaught, so a
    // request like `GET /media/x/%zz.jpg` fell through to a generic
    // 500/1101 instead of the normal 400 this file returns for every other
    // malformed-input case. No state or information is at stake, but this
    // was the one input-handling step that ran before every other control
    // in this file, unguarded.
    let key;
    try {
      key = decodeURIComponent(url.pathname.slice(1));
    } catch {
      return respond({ error: 'Invalid file key' }, 400);
    }
    if (!key) return respond({ error: 'Missing file key' }, 400);

    // Strict key format allow-list. The Android client only ever generates keys
    // matching this shape (see B2StorageHelper / ChatMediaActivity / GroupChatActivity).
    // Rejecting anything else closes off path traversal ("../"), null bytes, and
    // arbitrary-prefix keys that the shared-secret auth alone does not constrain.
    if (!isAllowedKey(key)) {
      return respond({ error: 'Invalid file key format' }, 400);
    }

    // ── Per-object authorization (SEC-A01) ────────────────────────────────────
    // The data plane is authorized per object, not by a shared secret. The client
    // must present a capability token minted by the push server's POST /mediaToken
    // — bound to exactly this key, this HTTP verb, this user and a short expiry —
    // which the server only issues after confirming the caller participates in the
    // chat/group named by the key's middle segment. A token stolen for one object
    // or verb grants nothing anywhere else.
    //
    // This runs BEFORE the quota/rate-limit gates below so that an unauthenticated
    // flood is rejected cheaply and can never consume the global daily request
    // budget (which would otherwise be a cost/DoS lever available to anyone).
    const cap = await verifyMediaToken(request, env, key);
    if (!cap.ok) {
      return respond({ error: cap.error }, cap.status);
    }

    // ── Daily request gate ────────────────────────────────────────────────────
    const dailyLimited = await checkDailyRequestLimit(env, cors);
    if (dailyLimited) return dailyLimited;

    // ── Per-isolate rate limit ────────────────────────────────────────────────
    const rateLimited = await checkPerUserRateLimit(request, env, cors);
    if (rateLimited) return rateLimited;

    // ── UPLOAD ────────────────────────────────────────────────────────────────
    if (request.method === 'PUT') {
      // S03-M2: a capability token only proves the caller participates in the
      // chat/group that owns this key (see SEC-A01 above) — every participant
      // can mint a write token for any key in the conversation, including one
      // another participant already uploaded to. Without this check, any chat
      // member could silently overwrite (swap the bytes of) media someone else
      // sent, using their own valid token — scope-bound, but not uploader-bound.
      // Only the very first write to a key (no existing object, nothing to
      // protect yet) is unrestricted; every write after that is locked to
      // whichever holder's uploader tag was recorded on the object at PUT time.
      const existing = await env.HOT_BUCKET.head(key).catch(() => null);
      const existingUploader = existing?.customMetadata?.uploader;
      if (existingUploader && existingUploader !== cap.holder) {
        return respond({ error: 'Only the original uploader may overwrite this object' }, 403);
      }

      // S03-H3 follow-up: the only overwrite this handler allows is a
      // same-holder overwrite (the check above already rejects anyone
      // else's). Both the pre-check below and the post-upload credit at the
      // bottom of this handler must treat that case as a DELTA — the new
      // size minus the size of the object being replaced — not as if the
      // old bytes never existed. Before this fix, a same-holder overwrite
      // added the *entire* new size to both the global R2 counter and the
      // holder's per-user counter every time, on top of whatever the old
      // object had already contributed: N overwrites of a 10 MB file
      // inflated the counters by 10*N MB while R2 itself only ever held
      // 10 MB, permanently and cumulatively — the counter never converges
      // back down on its own. That double-counting could make a holder's
      // own quota (and, through the shared global counter, the whole
      // deployment's free-tier budget) look exhausted without the data to
      // show for it, and — read the other way — meant a *shrinking*
      // replacement never gave back the headroom it should have, since the
      // old, larger size was never subtracted either.
      // `oldSize` is 0 for a brand-new key (no existing object — nothing to
      // subtract, matching the pre-fix behavior for first uploads exactly).
      const oldSize = existing?.size ?? 0;

      // Optimistic pre-check using the client-supplied Content-Length.
      // A spoofed or absent header is caught after the upload via R2 HEAD —
      // see the post-put size verification below.
      const declaredBytes = safeInt(request.headers.get('Content-Length'));

      if (declaredBytes > maxFileSize(env)) {
        return respond({ error: `File too large (max ${maxFileSize(env) / 1_048_576} MB)` }, 413);
      }

      const r2Bytes = await getR2Bytes(env);
      const projectedR2Bytes = r2Bytes - oldSize + declaredBytes;
      if (projectedR2Bytes > MAX_R2_BYTES) {
        return respond({
          error:           'Upload rejected — R2 storage limit (9.5 GB) reached. No new media accepted.',
          r2_used_bytes:   r2Bytes,
          r2_limit_bytes:  MAX_R2_BYTES,
          remaining_bytes: Math.max(0, MAX_R2_BYTES - r2Bytes),
        }, 507);
      }

      // S03-H3: per-holder budget, checked in addition to (not instead of)
      // the global cap above. Without this, one account's uploads alone can
      // exhaust the entire 9.5 GB global cap and stop uploads platform-wide
      // for weeks (objects only leave R2 via the 30-day tiering cron).
      const userBytes = await getUserBytes(env, cap.holder);
      const projectedUserBytes = userBytes - oldSize + declaredBytes;
      if (projectedUserBytes > maxUserBytes(env)) {
        return respond({
          error:            'Upload rejected — per-user storage quota reached.',
          user_used_bytes:  userBytes,
          user_limit_bytes: maxUserBytes(env),
        }, 507);
      }

      // S03-M1: the served/stored Content-Type is derived from the
      // allow-listed key extension, never from the attacker-controllable
      // client header — see contentTypeForKey() above for the full
      // rationale. The client's Content-Type header is intentionally never
      // read here.
      const contentType = contentTypeForKey(key);

      await env.HOT_BUCKET.put(key, request.body, {
        httpMetadata:   { contentType },
        // `uploader` records cap.holder for two purposes: storage-quota
        // accounting (so DELETE credits the right holder's quota back) and,
        // as of S03-M2, the ownership check above/in DELETE — the only write
        // this tag doesn't gate is the very first one, when there is nothing
        // to protect yet.
        customMetadata: { uploadedAt: Date.now().toString(), uploader: cap.holder },
      });

      // HEAD the object to get the real stored byte count.
      // This is the only source of truth — the client header is not trusted.
      const meta        = await env.HOT_BUCKET.head(key);
      const actualBytes = meta?.size ?? declaredBytes;

      // Post-upload size guard: catches missing or lying Content-Length headers.
      if (actualBytes > maxFileSize(env)) {
        await env.HOT_BUCKET.delete(key).catch(() => {});
        return respond({
          error: `Upload rejected — actual size (${actualBytes} B) exceeds the ${maxFileSize(env) / 1_048_576} MB limit`,
        }, 413);
      }

      // Post-upload quota re-check. The pre-checks above only bound
      // `declaredBytes` (the client-supplied Content-Length), which — like
      // the file-size guard immediately above — is untrusted: a missing or
      // understated header (e.g. chunked transfer with no Content-Length,
      // safeInt-defaulted to 0) sails through both pre-checks regardless of
      // how large the body actually is, then lands here already stored with
      // its true size unaccounted for. Re-checking `actualBytes` against
      // both caps before crediting anything closes that bypass for the same
      // reason the file-size guard exists — the client header is never
      // trusted for anything that gates a limit. Same delta treatment as
      // the pre-checks above: `oldSize` is subtracted here too, so a
      // same-holder overwrite is re-verified against the same "new minus
      // old" arithmetic, not the object's full new size on top of the old.
      const r2AfterUpload = (await getR2Bytes(env)) - oldSize + actualBytes;
      const userAfterUpload = (await getUserBytes(env, cap.holder)) - oldSize + actualBytes;
      if (r2AfterUpload > MAX_R2_BYTES || userAfterUpload > maxUserBytes(env)) {
        await env.HOT_BUCKET.delete(key).catch(() => {});
        return respond({
          error: 'Upload rejected — actual size exceeds the available storage quota (Content-Length was missing or understated).',
          bytes: actualBytes,
        }, 507);
      }

      // Increment R2 counter and the uploader's per-user counter by the
      // DELTA (actualBytes - oldSize), not the full actualBytes — a
      // same-holder overwrite's old bytes are no longer double-counted.
      // For a brand-new key oldSize is 0, so this is identical to crediting
      // actualBytes outright, exactly as before this fix. adjustR2/
      // adjustUserBytes already no-op on a zero delta and clamp at 0, so a
      // same-size replacement (delta 0) and a shrinking one (negative
      // delta) are both handled correctly.
      //
      // Residual limitation, not fixed here (documented, not hidden): this
      // is still the same best-effort, non-atomic counter model as the rest
      // of this file (no Durable Objects on this project — see S03-I2).
      // Two concurrent overwrites of the same key can still race between
      // the HEAD above and either write landing — the bound on that race is
      // the same one `adjustR2`'s own comment already describes for
      // concurrent fresh uploads, not a new gap this fix introduces. A
      // rejected overwrite (413/507 above) also cannot roll back to the
      // pre-overwrite bytes — `HOT_BUCKET.put` already replaced them by the
      // time the post-upload checks run, and R2 has no object versioning
      // configured here — so a same-holder overwrite that gets rejected
      // loses the old content as a side effect of the destructive write,
      // not of this accounting fix.
      const deltaBytes = actualBytes - oldSize;
      ctx.waitUntil(adjustR2(env, deltaBytes));
      ctx.waitUntil(adjustUserBytes(env, cap.holder, deltaBytes));

      return respond({ status: 'stored', key, tier: 'hot', bytes: actualBytes });
    }

    // ── DOWNLOAD ──────────────────────────────────────────────────────────────
    if (request.method === 'GET') {
      const rangeHeader = request.headers.get('Range');

      // 1. Hot tier: R2
      //
      // HEAD before GET so a range can be resolved against the real object size
      // (R2 needs an absolute offset+length, and an `end` clamped to EOF is only
      // knowable from the size). Costs one extra metadata lookup on the hot path,
      // which is why it is skipped entirely for a plain full-object GET.
      let r2Range = null;
      if (rangeHeader) {
        const r2Head = await env.HOT_BUCKET.head(key).catch(() => null);
        if (r2Head) {
          const parsed = parseByteRange(rangeHeader, r2Head.size);
          if (parsed === 'invalid') {
            return new Response(null, {
              status:  416,
              headers: new Headers({
                'Content-Range': `bytes */${r2Head.size}`,
                'Accept-Ranges': 'bytes',
                ...cors,
              }),
            });
          }
          r2Range = parsed ? { ...parsed, size: r2Head.size } : null;
        }
      }

      const r2Object = r2Range
        ? await env.HOT_BUCKET.get(key, {
            range: { offset: r2Range.start, length: r2Range.length },
          })
        : await env.HOT_BUCKET.get(key);
      if (r2Object) {
        const headers = new Headers({
          // S03-M1: derived from the key's allow-listed extension, not from
          // whatever was stored in httpMetadata.contentType — an object PUT
          // before this fix (or PUT by any client that spoofed the header
          // when this check didn't exist) may still have an attacker-chosen
          // stored value; reading it here would keep replaying that.
          'Content-Type':           contentTypeForKey(key),
          // r2Object.size is the size of the body actually returned, so it is
          // already the partial length on a ranged get.
          'Content-Length':         String(r2Object.size),
          'ETag':                   r2Object.httpEtag ?? '',
          'Cache-Control':          'private, max-age=3600',
          'X-Storage-Tier':         'hot',
          'Accept-Ranges':          'bytes',
          // S03-M1: a browser/WebView that ever opens this URL directly must
          // not MIME-sniff the body into executing as HTML/SVG/JS, and must
          // not render it inline — this is defense-in-depth today (the
          // Android client only ever fetches-then-decrypts) but the file
          // already ships permissive CORS anticipating a browser client.
          'X-Content-Type-Options': 'nosniff',
          'Content-Disposition':    'attachment',
          ...cors,
        });
        if (r2Range) {
          headers.set('Content-Range',
            `bytes ${r2Range.start}-${r2Range.end}/${r2Range.size}`);
          return new Response(r2Object.body, { status: 206, headers });
        }
        return new Response(r2Object.body, { headers });
      }

      // 2. Cold tier: B2 (transparent fallback — client always uses same URL)
      const b2 = getB2Client(env);
      let b2Response;
      try {
        // Forward the client's Range verbatim and let B2 resolve it — no HEAD needed
        // here, because S3 answers a range GET with the authoritative Content-Range
        // (including the total size) which is passed straight through below. A 416
        // from B2 is also passed through as-is by the !ok branch's status mapping.
        b2Response = await b2.fetch(b2Url(env, key),
          rangeHeader ? { headers: { Range: rangeHeader } } : undefined);
      } catch (err) {
        return respond({ error: 'B2 fetch failed', detail: err.message }, 502);
      }

      if (b2Response.status === 416) {
        return new Response(null, {
          status:  416,
          headers: new Headers({
            'Content-Range': b2Response.headers.get('Content-Range') ?? 'bytes */*',
            'Accept-Ranges': 'bytes',
            ...cors,
          }),
        });
      }

      if (b2Response.ok) {
        // Whitelist only safe, client-relevant headers.
        // Internal AWS/B2 headers (x-amz-request-id, x-amz-id-2, etc.) are
        // intentionally excluded — they reveal infrastructure details.
        const headers = new Headers({
          // S03-M1: same reasoning as the R2 branch above — derive from the
          // key, never trust the stored/upstream value (B2's own Content-Type
          // ultimately traces back to the same client-controlled PUT header).
          'Content-Type':           contentTypeForKey(key),
          'Content-Length':         b2Response.headers.get('Content-Length') ?? '',
          'ETag':                   b2Response.headers.get('ETag') ?? '',
          'Cache-Control':          'private, max-age=3600',
          'X-Storage-Tier':         'cold',
          'X-Content-Type-Options': 'nosniff',
          'Content-Disposition':    'attachment',
          'Accept-Ranges':          'bytes',
          ...cors,
        });
        // 206 only when B2 actually honoured the range. If it answered 200 to a
        // range request, say 200 — reporting 206 without a real partial body would
        // make the client treat a full object as the slice it asked for.
        if (b2Response.status === 206) {
          const contentRange = b2Response.headers.get('Content-Range');
          if (contentRange) headers.set('Content-Range', contentRange);
          return new Response(b2Response.body, { status: 206, headers });
        }
        return new Response(b2Response.body, { status: 200, headers });
      }

      if (b2Response.status === 404) return respond({ error: 'File not found', key }, 404);
      return respond({ error: 'B2 error', status: b2Response.status }, 502);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    if (request.method === 'DELETE') {
      // S03-M3: `delete` is the one verb with no undo, so its token is
      // single-use — replaying a captured delete token (a proxy log, a
      // crash report, anything that observed the Authorization header)
      // within its TTL must not be able to issue the same destructive
      // operation twice. Checked before touching R2/B2 at all, so a replay
      // never even reaches either tier's ownership check or delete call.
      let alreadyUsed;
      try {
        alreadyUsed = await isTokenAlreadyUsed(env, cap.jti);
      } catch {
        return respond({ error: 'Delete-token replay protection unavailable' }, 503);
      }
      if (alreadyUsed) {
        return respond({ error: 'Capability token already used' }, 403);
      }
      const remainingTtlSeconds = Math.ceil(Math.max(0, cap.expiresAt - Date.now()) / 1000);
      // Persist the single-use marker BEFORE mutating storage. A failed KV write
      // must not allow deletion without replay protection.
      try {
        await markTokenUsed(env, cap.jti, remainingTtlSeconds);
      } catch {
        return respond({ error: 'Delete-token replay protection unavailable' }, 503);
      }

      // Check R2 first (files < 30 days old live only in R2).
      const r2Head = await env.HOT_BUCKET.head(key).catch(() => null);

      if (r2Head) {
        // S03-M2: a capability token only proves the caller participates in
        // the chat/group that owns this key (SEC-A01) — every participant can
        // mint a delete token for any key in the conversation, including one
        // another participant uploaded. Enforce uploader binding here: only
        // the holder recorded in customMetadata.uploader at PUT time (or an
        // object with no uploader tag at all — a pre-S3-11 object migrated
        // before this metadata existed) may delete it.
        const r2Uploader = r2Head.customMetadata?.uploader;
        if (r2Uploader && r2Uploader !== cap.holder) {
          return respond({ error: 'Only the original uploader may delete this object' }, 403);
        }

        // File is in R2 — delete it and adjust the R2 counter.
        const r2Size = r2Head.size ?? 0;
        await env.HOT_BUCKET.delete(key).catch(() => {});
        if (r2Size > 0) ctx.waitUntil(adjustR2(env, -r2Size));
        // S03-H3: credit the per-user quota back to whichever holder
        // uploaded the object (recorded at PUT time) — now guaranteed by the
        // ownership check above to be the caller themselves.
        if (r2Uploader && r2Size > 0) ctx.waitUntil(adjustUserBytes(env, r2Uploader, -r2Size));
        // Race guard: the nightly migration PUTs to B2 and THEN deletes from R2
        // as two separate steps. If a client DELETE lands in that gap, the file
        // briefly exists in both tiers and this branch (R2-present) runs, which
        // would otherwise leave an orphaned copy in B2 forever. Fire a best-effort
        // B2 delete alongside — a 404 (object never migrated) is a normal, cheap
        // no-op, so this is safe to do unconditionally without checking B2 first.
        const b2 = getB2Client(env);
        ctx.waitUntil(
          b2.fetch(b2Url(env, key), { method: 'DELETE' }).catch(() => {})
        );
      } else {
        // File is not in R2 → it must have been migrated to B2 (cold tier).
        // B2 counter is reconciled nightly by the cron — no KV write here.
        const b2 = getB2Client(env);

        // S03-M2: same ownership rule as the R2 branch above. The migration
        // cron carries `x-amz-meta-uploader` across to B2 (see `scheduled()`
        // below), so a HEAD here can enforce it the same way R2's `head()`
        // metadata does. A HEAD miss (404) means there is nothing to protect
        // — fall through to the DELETE below, which will itself no-op 404.
        let headResp;
        try {
          headResp = await b2.fetch(b2Url(env, key), { method: 'HEAD' });
        } catch (err) {
          console.error(`B2 HEAD network error for ${key}: ${err.message}`);
          return respond({ error: 'B2 delete failed (network error)', key }, 502);
        }
        if (headResp.ok) {
          const b2Uploader = headResp.headers.get('x-amz-meta-uploader');
          if (b2Uploader && b2Uploader !== cap.holder) {
            return respond({ error: 'Only the original uploader may delete this object' }, 403);
          }
        } else if (headResp.status !== 404) {
          return respond({ error: 'B2 delete failed', status: headResp.status, key }, 502);
        }

        let delResp;
        try {
          delResp = await b2.fetch(b2Url(env, key), { method: 'DELETE' });
        } catch (err) {
          console.error(`B2 delete network error for ${key}: ${err.message}`);
          // Surface the failure — returning a false 200 here would tell the
          // Android client the file is gone when it isn't, making it
          // impossible to retry and leaving orphaned cold-tier objects forever.
          return respond({ error: 'B2 delete failed (network error)', key }, 502);
        }
        if (!delResp.ok && delResp.status !== 404) {
          console.warn(`B2 delete non-OK for ${key}: ${delResp.status}`);
          return respond({ error: 'B2 delete failed', status: delResp.status, key }, 502);
        }
      }

      return respond({ status: 'deleted', key });
    }

    return respond({ error: 'Method not allowed' }, 405);
  },

  // ─── Scheduled: daily R2→B2 tiering + full storage reconciliation ─────────
  //
  // Runs at 02:00 UTC every day (configured in wrangler.jsonc).
  //
  // Steps:
  //   1. Full R2 scan: migrate objects older than HOT_TIER_DAYS to B2.
  //      Migration is capped at MAX_MIGRATIONS_PER_RUN (200) to stay under the
  //      1,000-subrequest-per-invocation Worker limit. Objects that exceed the
  //      cap remain in R2 and are retried the next day.
  //   2. Write authoritative R2 byte count to KV.
  //   3. Reconcile B2 byte count via B2 ListObjectsV2 and write to KV.
  //      This corrects any drift from failed per-request counter updates.
  async scheduled(event, env, ctx) {
    const b2        = getB2Client(env);
    const now       = Date.now();
    const threshold = hotTierMs(env);

    let moved        = 0;
    let r2TotalBytes = 0;

    // ── Step 1: Scan R2 ─────────────────────────��──────────────────────────────
    let cursor;
    do {
      const list = await env.HOT_BUCKET.list({
        cursor,
        include: ['httpMetadata', 'customMetadata'],
      });

      for (const obj of list.objects) {
        const uploadedAt = obj.customMetadata?.uploadedAt
          ? safeInt(obj.customMetadata.uploadedAt)
          : new Date(obj.uploaded).getTime();

        if (now - uploadedAt < threshold) {
          // Still hot — count as R2 usage.
          r2TotalBytes += obj.size ?? 0;
          continue;
        }

        if (moved >= MAX_MIGRATIONS_PER_RUN) {
          // Migration cap reached for this cron run.
          // Count the object as still in R2 so the cap check stays conservative.
          // It will be retried tomorrow.
          r2TotalBytes += obj.size ?? 0;
          continue;
        }

        // ── Migrate: R2 → B2 ──────────────────────────────────────────────────
        const r2Obj = await env.HOT_BUCKET.get(obj.key);
        if (!r2Obj) continue; // concurrently deleted — skip

        const contentType = r2Obj.httpMetadata?.contentType
          || obj.httpMetadata?.contentType
          || 'application/octet-stream';

        // Buffer to memory so we can supply Content-Length to B2.
        // B2's S3-compatible API returns 411 Length Required without it.
        // Sequential processing keeps peak RAM ~= one file (≤ 10 MB).
        const body = await r2Obj.arrayBuffer();
        const readEtag = r2Obj.httpEtag;

        // S03-M2: carry the uploader tag across so ownership can still be
        // enforced on DELETE after an object has tiered to B2 — otherwise
        // the binding added in the R2 DELETE/PUT paths would silently stop
        // applying the moment an object ages past HOT_TIER_DAYS.
        const uploaderTag = r2Obj.customMetadata?.uploader;

        const putResp = await b2.fetch(b2Url(env, obj.key), {
          method: 'PUT',
          body,
          headers: {
            'Content-Type':           contentType,
            'Content-Length':         body.byteLength.toString(),
            'x-amz-meta-uploaded-at': uploadedAt.toString(),
            ...(uploaderTag ? { 'x-amz-meta-uploader': uploaderTag } : {}),
          },
        });

        if (putResp.ok) {
          // Race guard: a client PUT/DELETE could have landed on this key
          // between the get() above and now. Re-HEAD and only delete from R2
          // if the object is unchanged (same etag) — otherwise we'd delete
          // content that was never actually migrated to the B2 copy we just
          // wrote, losing data. If it changed, leave R2 alone; the (now
          // stale) B2 copy is harmless because GET always checks R2 first,
          // and the object will be reconsidered on tomorrow's run.
          const current = await env.HOT_BUCKET.head(obj.key).catch(() => null);
          if (current && current.httpEtag === readEtag) {
            await env.HOT_BUCKET.delete(obj.key);
            moved++;
            // Object now lives in B2 — do NOT add its size to r2TotalBytes.
            // S03-H3: the per-user quota is scoped to R2 (hot-tier) usage
            // only — B2 (cold tier) has no per-user cap (informational-only,
            // see MAX_B2_BYTES above) — so free the uploader's R2 quota the
            // same way the global R2 counter is freed on this line.
            const uploader = current.customMetadata?.uploader;
            if (uploader) ctx.waitUntil(adjustUserBytes(env, uploader, -(current.size ?? 0)));
          } else if (current) {
            console.warn(`Skipped R2 delete for ${obj.key} — object changed during migration`);
            r2TotalBytes += current.size ?? 0;
          } else {
            // S10-N3: deleted concurrently — and this is the one interleaving
            // the two existing race guards do NOT already cover. A client
            // DELETE that removed this key from R2 landed AFTER our get()
            // above but BEFORE the B2 PUT we just issued: it took the
            // R2-present branch, deleted from R2, and fired its own
            // best-effort B2 delete — which 404'd because our PUT had not
            // happened yet. Our PUT then recreated the object in B2. Nothing
            // in R2 now references it, GET falls back to B2 (X-Storage-Tier:
            // cold) so the "deleted" media would keep serving forever, and
            // the nightly ListObjectsV2 reconciliation (Step 3) would silently
            // count the orphan as legitimate B2 usage — hiding the leak from
            // the very counter that tracks it (S03-H3).
            //
            // We are the party that created the orphan and the only party
            // that knows it, so undo our own write. This is a compensating
            // delete, not an atomic one: there is no cross-tier transaction on
            // this project (no Durable Objects — see S03-I2), so if this
            // best-effort delete itself fails the orphan persists. That is why
            // it is logged rather than swallowed the way the pre-fix empty
            // branch was — a residual orphan is now visible in Worker logs
            // instead of silent. If this delete false-positives (a transient
            // R2 HEAD miss on an object that is actually still present), no
            // data is lost: R2 still holds the object, GET serves it from the
            // hot tier, and tomorrow's run re-migrates it.
            console.warn(`S10-N3: undoing orphaned B2 copy of ${obj.key} — R2 was deleted during migration`);
            ctx.waitUntil(b2.fetch(b2Url(env, obj.key), { method: 'DELETE' }).catch(() => {}));
          }
        } else {
          // B2 PUT failed — object stays in R2. Count it and retry tomorrow.
          r2TotalBytes += obj.size ?? 0;
          console.error(`Failed to tier ${obj.key} to B2: ${putResp.status}`);
        }
      }

      cursor = list.truncated ? list.cursor : null;
    } while (cursor);

    // ── Step 2: Persist authoritative R2 byte count ────────────────────────────
    await kvSet(env, 'global:storage:r2', r2TotalBytes);

    // ── Step 3: Reconcile B2 byte count via B2 ListObjectsV2 ─────────────────
    // This is the authoritative B2 figure. Per-request counter writes can drift
    // (KV quota exhaustion, failed deletes, etc.); the list always tells the truth.
    const b2TotalBytes = await getB2TotalBytes(b2, env);
    if (b2TotalBytes !== null) {
      await kvSet(env, 'global:storage:b2', b2TotalBytes);
    } else {
      console.warn('B2 list reconciliation failed — KV b2 counter unchanged this run');
    }

    console.log(
      `Tiering complete: migrated=${moved} (cap=${MAX_MIGRATIONS_PER_RUN}) | ` +
      `R2=${(r2TotalBytes / 1_048_576).toFixed(1)} MB / ${(MAX_R2_BYTES / 1_048_576).toFixed(0)} MB cap | ` +
      `B2=${b2TotalBytes !== null ? (b2TotalBytes / 1_048_576).toFixed(1) + ' MB (reconciled)' : 'reconciliation failed'}`
    );
  },
};
