import { AwsClient } from 'aws4fetch';

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

// ─── Response helpers ─────────────────────────────────────────────────────────
function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function corsHeaders() {
  // Access-Control-Allow-Origin: * is intentional for Android app usage.
  // CORS is a browser-only mechanism; the Android HTTP client ignores it.
  // If you add a browser-based client, restrict this to your known origin.
  // Authorization is included so preflight passes for authenticated requests.
  return {
    'Access-Control-Allow-Origin':  '*',
    'Access-Control-Allow-Methods': 'GET, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Client-ID',
  };
}

// ─── Authentication ───────────────────────────────────────────────────────────
// Set via: npx wrangler secret put WORKER_SECRET
// All data-plane and stats requests must include:
//   Authorization: Bearer <WORKER_SECRET>
// Health check is intentionally unauthenticated.
// FAIL CLOSED if WORKER_SECRET is unset — a missing secret must never widen
// access. (Previously this fell back to "open mode", which meant a deploy
// that forgot to set the secret silently exposed every user's media with
// zero authentication. Local dev should set a throwaway WORKER_SECRET via
// `.dev.vars` / `wrangler secret put` rather than relying on an open mode.)
async function isAuthorized(request, env) {
  if (!env.WORKER_SECRET) {
    console.error('WORKER_SECRET is not configured — denying all requests (fail closed)');
    return false;
  }
  const supplied = request.headers.get('Authorization') ?? '';
  const expected = `Bearer ${env.WORKER_SECRET}`;
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
// Wire format: v1.<op>.<expiresAt>.<uidTag>.<base64url-hmac-sha256>
// Signed payload: `v1|<op>|<expiresAt>|<uidTag>|<key>`
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
  if (parts.length !== 5 || parts[0] !== 'v1') {
    return { ok: false, status: 401, error: 'Malformed capability token' };
  }

  const [, op, expRaw, holder, sig] = parts;

  // Bind the token to this verb. A read token must not be replayable as a delete.
  if (op !== expectedOp) {
    return { ok: false, status: 403, error: 'Token not valid for this operation' };
  }

  const expiresAt = safeInt(expRaw, 0);
  if (expiresAt <= 0 || Date.now() > expiresAt) {
    return { ok: false, status: 401, error: 'Capability token expired' };
  }

  // Recompute over the key from the request path — this is what scopes the
  // token to a single object.
  const payload  = `v1|${op}|${expiresAt}|${holder}|${key}`;
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

  return { ok: true, holder };
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
async function checkDailyRequestLimit(env) {
  if (!env.RATE_KV) return null;
  const key   = dayKey();
  const count = safeInt(await kvGet(env, key));
  if (count >= MAX_DAILY_REQUESTS) {
    return json({
      error: 'Daily request limit reached (90K/day). Resets at midnight UTC.',
      count,
      limit: MAX_DAILY_REQUESTS,
    }, 429);
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

async function checkPerUserRateLimit(request, env) {
  const limit     = rateLimit(env);
  const bucketId  = await credentialBucketKey(request);
  const minuteKey = `${bucketId}:${Math.floor(Date.now() / 60_000)}`;
  const count     = perUserCounts.get(minuteKey) ?? 0;
  if (count >= limit) {
    return json({ error: `Rate limit exceeded (${limit} req/min)` }, 429);
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
    // CORS preflight — no auth, no quota.
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }

    const url = new URL(request.url);

    // ── Health check — unauthenticated, does not count against quota ──────────
    if (url.pathname === '/' || url.pathname === '/health') {
      return json({ status: 'ok', service: 'duoshield-storage' });
    }

    // ── Stats endpoint (admin view) — gated by the shared WORKER_SECRET ───────
    // /stats is an operator-only view, not a per-user object operation, so it
    // keeps the shared-secret bearer check. The data plane (GET/PUT/DELETE on an
    // object key) does NOT use this secret — it requires a per-object capability
    // token (verifyMediaToken) minted by the push server. See SEC-A01 below.
    if (url.pathname === '/stats') {
      if (!await isAuthorized(request, env)) {
        return new Response(JSON.stringify({ error: 'Unauthorized' }), {
          status:  401,
          headers: { 'Content-Type': 'application/json', ...corsHeaders() },
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
      return json({
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

    // Object key: everything after the leading slash.
    // DuoShield paths: media/<chatId|groupId>/<uuid>.<ext> | voice/<chatId|groupId>/<uuid>.<ext>
    const key = decodeURIComponent(url.pathname.slice(1));
    if (!key) return json({ error: 'Missing file key' }, 400);

    // Strict key format allow-list. The Android client only ever generates keys
    // matching this shape (see B2StorageHelper / ChatMediaActivity / GroupChatActivity).
    // Rejecting anything else closes off path traversal ("../"), null bytes, and
    // arbitrary-prefix keys that the shared-secret auth alone does not constrain.
    const KEY_FORMAT = /^(media|voice)\/[a-zA-Z0-9-]{16,80}\/[a-zA-Z0-9._-]{1,100}\.(jpg|mp4|m4a|3gp)$/;
    if (!KEY_FORMAT.test(key)) {
      return json({ error: 'Invalid file key format' }, 400);
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
      return json({ error: cap.error }, cap.status);
    }

    // ── Daily request gate ────────────────────────────────────────────────────
    const dailyLimited = await checkDailyRequestLimit(env);
    if (dailyLimited) return dailyLimited;

    // ── Per-isolate rate limit ────────────────────────────────────────────────
    const rateLimited = await checkPerUserRateLimit(request, env);
    if (rateLimited) return rateLimited;

    // ── UPLOAD ────────────────────────────────────────────────────────────────
    if (request.method === 'PUT') {
      // Optimistic pre-check using the client-supplied Content-Length.
      // A spoofed or absent header is caught after the upload via R2 HEAD —
      // see the post-put size verification below.
      const declaredBytes = safeInt(request.headers.get('Content-Length'));

      if (declaredBytes > maxFileSize(env)) {
        return json({ error: `File too large (max ${maxFileSize(env) / 1_048_576} MB)` }, 413);
      }

      const r2Bytes = await getR2Bytes(env);
      if (r2Bytes + declaredBytes > MAX_R2_BYTES) {
        return json({
          error:           'Upload rejected — R2 storage limit (9.5 GB) reached. No new media accepted.',
          r2_used_bytes:   r2Bytes,
          r2_limit_bytes:  MAX_R2_BYTES,
          remaining_bytes: Math.max(0, MAX_R2_BYTES - r2Bytes),
        }, 507);
      }

      const contentType = request.headers.get('Content-Type') || 'application/octet-stream';

      await env.HOT_BUCKET.put(key, request.body, {
        httpMetadata:   { contentType },
        customMetadata: { uploadedAt: Date.now().toString() },
      });

      // HEAD the object to get the real stored byte count.
      // This is the only source of truth — the client header is not trusted.
      const meta        = await env.HOT_BUCKET.head(key);
      const actualBytes = meta?.size ?? declaredBytes;

      // Post-upload size guard: catches missing or lying Content-Length headers.
      if (actualBytes > maxFileSize(env)) {
        await env.HOT_BUCKET.delete(key).catch(() => {});
        return json({
          error: `Upload rejected — actual size (${actualBytes} B) exceeds the ${maxFileSize(env) / 1_048_576} MB limit`,
        }, 413);
      }

      // Increment R2 counter with the actual stored size, not the declared size.
      ctx.waitUntil(adjustR2(env, actualBytes));

      return json({ status: 'stored', key, tier: 'hot', bytes: actualBytes });
    }

    // ── DOWNLOAD ──────────────────────────────────────────────────────────────
    if (request.method === 'GET') {
      // 1. Hot tier: R2
      const r2Object = await env.HOT_BUCKET.get(key);
      if (r2Object) {
        const headers = new Headers({
          'Content-Type':   r2Object.httpMetadata?.contentType || 'application/octet-stream',
          'Content-Length': String(r2Object.size),
          'ETag':           r2Object.httpEtag ?? '',
          'Cache-Control':  'private, max-age=3600',
          'X-Storage-Tier': 'hot',
          ...corsHeaders(),
        });
        return new Response(r2Object.body, { headers });
      }

      // 2. Cold tier: B2 (transparent fallback — client always uses same URL)
      const b2 = getB2Client(env);
      let b2Response;
      try {
        b2Response = await b2.fetch(b2Url(env, key));
      } catch (err) {
        return json({ error: 'B2 fetch failed', detail: err.message }, 502);
      }

      if (b2Response.ok) {
        // Whitelist only safe, client-relevant headers.
        // Internal AWS/B2 headers (x-amz-request-id, x-amz-id-2, etc.) are
        // intentionally excluded — they reveal infrastructure details.
        const headers = new Headers({
          'Content-Type':   b2Response.headers.get('Content-Type') || 'application/octet-stream',
          'Content-Length': b2Response.headers.get('Content-Length') ?? '',
          'ETag':           b2Response.headers.get('ETag') ?? '',
          'Cache-Control':  'private, max-age=3600',
          'X-Storage-Tier': 'cold',
          ...corsHeaders(),
        });
        return new Response(b2Response.body, { status: 200, headers });
      }

      if (b2Response.status === 404) return json({ error: 'File not found', key }, 404);
      return json({ error: 'B2 error', status: b2Response.status }, 502);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    if (request.method === 'DELETE') {
      // Check R2 first (files < 30 days old live only in R2).
      const r2Head = await env.HOT_BUCKET.head(key).catch(() => null);

      if (r2Head) {
        // File is in R2 — delete it and adjust the R2 counter.
        const r2Size = r2Head.size ?? 0;
        await env.HOT_BUCKET.delete(key).catch(() => {});
        if (r2Size > 0) ctx.waitUntil(adjustR2(env, -r2Size));
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
        let delResp;
        try {
          delResp = await b2.fetch(b2Url(env, key), { method: 'DELETE' });
        } catch (err) {
          console.error(`B2 delete network error for ${key}: ${err.message}`);
          // Surface the failure — returning a false 200 here would tell the
          // Android client the file is gone when it isn't, making it
          // impossible to retry and leaving orphaned cold-tier objects forever.
          return json({ error: 'B2 delete failed (network error)', key }, 502);
        }
        if (!delResp.ok && delResp.status !== 404) {
          console.warn(`B2 delete non-OK for ${key}: ${delResp.status}`);
          return json({ error: 'B2 delete failed', status: delResp.status, key }, 502);
        }
      }

      return json({ status: 'deleted', key });
    }

    return json({ error: 'Method not allowed' }, 405);
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

    // ── Step 1: Scan R2 ────────────────────────────────────────────────────────
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

        const putResp = await b2.fetch(b2Url(env, obj.key), {
          method: 'PUT',
          body,
          headers: {
            'Content-Type':           contentType,
            'Content-Length':         body.byteLength.toString(),
            'x-amz-meta-uploaded-at': uploadedAt.toString(),
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
          } else if (current) {
            console.warn(`Skipped R2 delete for ${obj.key} — object changed during migration`);
            r2TotalBytes += current.size ?? 0;
          } else {
            // Deleted concurrently — nothing left in R2, nothing to count.
            // But the client's DELETE ran before our PUT above landed, so its
            // best-effort B2 cleanup (see the DELETE handler) was a no-op on a
            // B2 object that didn't exist yet. We just created it. Undo that
            // write here or the deleted object survives forever in B2 cold
            // tier, unreferenced and unreachable by any later cron run (S10-N3).
            ctx.waitUntil(
              b2.fetch(b2Url(env, obj.key), { method: 'DELETE' }).catch(() => {})
            );
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
