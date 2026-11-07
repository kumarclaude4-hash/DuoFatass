// Regression tests for S3-11 (S03-H3, S03-L4, S08-I3).
//
// These are structural/behavioral tests against the actual exported handler
// and helper functions in src/index.js — run with plain `node --test`, no
// wrangler/Miniflare required. R2/KV/B2 are stubbed with minimal in-memory
// fakes; aws4fetch's AwsClient.fetch is not stubbed because these tests never
// exercise a cold-tier (B2) path.

import test from 'node:test';
import assert from 'node:assert/strict';
import { timingSafeEqual as nodeTimingSafeEqual } from 'node:crypto';
import worker from './index.js';

// `crypto.subtle.timingSafeEqual` is a Cloudflare Workers-runtime extension to
// the Web Crypto API — it exists in the real Workers runtime that src/index.js
// actually deploys to, but plain Node's `crypto.subtle` does not implement it
// (confirmed on Node 24). This is a test-environment gap, not a bug in the
// implementation, and not a security weakness to paper over: the shim below
// delegates to Node's own constant-time `crypto.timingSafeEqual` (the same
// primitive the server side of this repo already uses for its own
// timing-safe comparisons — see server/lib/pure.js) so the comparison under
// test is still genuinely constant-time, just running under Node instead of
// workerd.
if (typeof globalThis.crypto?.subtle?.timingSafeEqual !== 'function') {
  globalThis.crypto.subtle.timingSafeEqual = async (a, b) => {
    const bufA = Buffer.from(a);
    const bufB = Buffer.from(b);
    if (bufA.length !== bufB.length) return false;
    return nodeTimingSafeEqual(bufA, bufB);
  };
}

const MEDIA_TOKEN_SECRET = 'test-media-token-secret';

async function hmacSha256(secret, message) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(message)));
}

function bytesToB64url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomJti() {
  return bytesToB64url(crypto.getRandomValues(new Uint8Array(9)));
}

// S03-M3: wire format grew a `jti` segment — `v1.<op>.<expiresAt>.<holder>.<jti>.<sig>`.
// A caller can pass an explicit `jti` to test replay behavior deterministically;
// otherwise a fresh random one is minted, matching real server behavior where
// every mint gets its own.
async function mintToken(op, key, holder, ttlMs = 60_000, jti = randomJti()) {
  const expiresAt = Date.now() + ttlMs;
  const payload    = `v1|${op}|${expiresAt}|${holder}|${jti}|${key}`;
  const sig         = await hmacSha256(MEDIA_TOKEN_SECRET, payload);
  return `v1.${op}.${expiresAt}.${holder}.${jti}.${bytesToB64url(sig)}`;
}

// ─── Minimal fakes ─────────────────────────────────────────────────────────
function makeKv() {
  const store = new Map();
  return {
    store,
    async get(key) { return store.has(key) ? store.get(key) : null; },
    async put(key, value) { store.set(key, String(value)); },
  };
}

function makeHotBucket() {
  const objects = new Map(); // key -> { body, size, customMetadata, httpMetadata, httpEtag }
  return {
    objects,
    async put(key, body, opts) {
      const buf = body instanceof ArrayBuffer ? body : await new Response(body).arrayBuffer();
      objects.set(key, {
        size: buf.byteLength,
        customMetadata: opts?.customMetadata ?? {},
        httpMetadata: opts?.httpMetadata ?? {},
        httpEtag: `"etag-${key}"`,
      });
    },
    async head(key) {
      const o = objects.get(key);
      if (!o) return null;
      return { size: o.size, customMetadata: o.customMetadata, httpMetadata: o.httpMetadata, httpEtag: o.httpEtag };
    },
    async get(key) {
      const o = objects.get(key);
      if (!o) return null;
      return { ...o, body: new Uint8Array(o.size), arrayBuffer: async () => new ArrayBuffer(o.size) };
    },
    async delete(key) { objects.delete(key); },
  };
}

function makeR2BudgetNamespace(initial = {}) {
  const state = { monthKey: initial.monthKey || '', classA: initial.classA || 0 };
  const stub = {
    async fetch(request) {
      const body = await request.json();
      if (body.action !== 'reserveR2ClassA'
          || !/^\d{4}-(0[1-9]|1[0-2])$/.test(body.monthKey || '')
          || !Number.isSafeInteger(body.amount) || body.amount < 1 || body.amount > 100) {
        return Response.json({ error: 'Invalid R2 budget reservation' }, { status: 400 });
      }
      const used = state.monthKey === body.monthKey ? state.classA : 0;
      const limit = 850_000;
      if (used + body.amount > limit) {
        return Response.json({ allowed: false, used, requested: body.amount, limit, monthKey: body.monthKey });
      }
      state.monthKey = body.monthKey;
      state.classA = used + body.amount;
      return Response.json({ allowed: true, used: state.classA, limit, monthKey: state.monthKey });
    },
  };
  return {
    state,
    idFromName(name) { return name; },
    get() { return stub; },
  };
}

function makeEnv(overrides = {}) {
  return {
    MEDIA_TOKEN_SECRET,
    RATE_KV: makeKv(),
    HOT_BUCKET: makeHotBucket(),
    ADMIN_LOCKOUT_DO: makeR2BudgetNamespace(),
    MAX_FILE_SIZE: '524288000',
    // getB2Client() constructs an AwsClient unconditionally on every DELETE
    // that finds the object still in R2 (the migration race-guard fires a
    // best-effort alongside B2 delete — see index.js). aws4fetch's AwsClient
    // constructor throws synchronously if accessKeyId/secretAccessKey are
    // missing, so these dummy values are required for R2-path DELETE tests
    // to even reach the fetch() call, whose network failure is already
    // swallowed by the `.catch(() => {})` in the implementation. Real
    // deployments always set these via `wrangler secret put` (see README).
    B2_ACCESS_KEY_ID: 'test-b2-key-id',
    B2_SECRET_ACCESS_KEY: 'test-b2-secret',
    B2_REGION: 'eu-central-003',
    B2_ENDPOINT: 'https://b2.invalid.example',
    B2_BUCKET: 'test-bucket',
    ...overrides,
  };
}

const ctx = { waitUntil: async (p) => { await p; } };

// ─── S03-L4: rejections must carry CORS headers ───────────────────────────
test('S03-L4: a 400 (invalid key format) rejection carries the CORS headers, not just success responses', async () => {
  const env = makeEnv({ CORS_ALLOWED_ORIGINS: 'https://app.example.com' });
  const req = new Request('https://worker.example/bad key with spaces', {
    method: 'GET',
    headers: { Origin: 'https://app.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.status, 400);
  assert.equal(
    res.headers.get('Access-Control-Allow-Origin'),
    'https://app.example.com',
    'a rejection response must carry the same CORS headers as a success response, or a browser client cannot even read the error body'
  );
});

test('S03-L4: a 401 (missing capability token) rejection also carries CORS headers', async () => {
  const env = makeEnv({ CORS_ALLOWED_ORIGINS: 'https://app.example.com' });
  const req = new Request(`https://worker.example/media/${'a'.repeat(20)}/file.jpg`, {
    method: 'GET',
    headers: { Origin: 'https://app.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.status, 401);
  assert.equal(res.headers.get('Access-Control-Allow-Origin'), 'https://app.example.com');
});

// ─── S08-I3: no wildcard ACAO; only explicit allow-listed origins ─────────
test('S08-I3: Access-Control-Allow-Origin is never "*"', async () => {
  const env = makeEnv({ CORS_ALLOWED_ORIGINS: 'https://app.example.com' });
  const req = new Request('https://worker.example/health', {
    headers: { Origin: 'https://evil.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.notEqual(res.headers.get('Access-Control-Allow-Origin'), '*');
  assert.equal(
    res.headers.get('Access-Control-Allow-Origin'),
    null,
    'an origin not on the allow list must not be reflected back'
  );
});

test('S08-I3: an allow-listed Origin is reflected back with Vary: Origin, never "*"', async () => {
  const env = makeEnv({ CORS_ALLOWED_ORIGINS: 'https://app.example.com,https://other.example.com' });
  const req = new Request('https://worker.example/health', {
    headers: { Origin: 'https://other.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.headers.get('Access-Control-Allow-Origin'), 'https://other.example.com');
  assert.equal(res.headers.get('Vary'), 'Origin');
});

test('S08-I3: with no CORS_ALLOWED_ORIGINS configured, ACAO is omitted entirely (not defaulted to "*")', async () => {
  const env = makeEnv(); // no CORS_ALLOWED_ORIGINS
  const req = new Request('https://worker.example/health', {
    headers: { Origin: 'https://anything.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.headers.get('Access-Control-Allow-Origin'), null);
});

// ─── S03-H3: per-user storage quota ────────────────────────────────────────
const CHAT_ID = 'a'.repeat(20); // satisfies KEY_FORMAT's 16-80 char id segment

test('S03-H3: a single holder is rejected with 507 once their per-user quota is exhausted, even though the global R2 cap is nowhere close', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' }); // tiny quota to make the test fast
  const holder = 'user-abc';

  // First upload of 600 bytes succeeds (under the 1000-byte quota).
  const key1 = `media/${CHAT_ID}/one.jpg`;
  const token1 = await mintToken('write', key1, holder);
  const body1 = new Uint8Array(600);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key1}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '600' },
    body: body1,
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Second upload of 600 more bytes from the SAME holder must be rejected —
  // 600 + 600 > 1000 — even though MAX_R2_BYTES (9.5 GB) is untouched.
  const key2 = `media/${CHAT_ID}/two.jpg`;
  const token2 = await mintToken('write', key2, holder);
  const body2 = new Uint8Array(600);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key2}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}`, 'Content-Length': '600' },
    body: body2,
  }), env, ctx);
  assert.equal(put2.status, 507, await put2.clone().text());
  const payload = await put2.json();
  assert.match(payload.error, /per-user storage quota/i);
});

test('S03-H3: a different holder is unaffected by another holder\'s exhausted quota', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' });

  const keyA = `media/${CHAT_ID}/a.jpg`;
  const tokenA = await mintToken('write', keyA, 'user-a');
  const putA = await worker.fetch(new Request(`https://worker.example/${keyA}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${tokenA}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(putA.status, 200, await putA.clone().text());

  const keyB = `media/${CHAT_ID}/b.jpg`;
  const tokenB = await mintToken('write', keyB, 'user-b');
  const putB = await worker.fetch(new Request(`https://worker.example/${keyB}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${tokenB}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(putB.status, 200, 'user-b has their own independent 1000-byte quota, unaffected by user-a nearly exhausting theirs');
});

test('S03-H3: deleting an object credits the per-user quota back to the uploader', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' });
  const holder = 'user-recycle';

  const key1 = `media/${CHAT_ID}/one.jpg`;
  const token1 = await mintToken('write', key1, holder);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key1}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Delete it — the quota should be freed.
  const delToken = await mintToken('delete', key1, holder);
  const del = await worker.fetch(new Request(`https://worker.example/${key1}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${delToken}` },
  }), env, ctx);
  assert.equal(del.status, 200, await del.clone().text());

  // A fresh 900-byte upload from the same holder should now succeed again.
  const key2 = `media/${CHAT_ID}/two.jpg`;
  const token2 = await mintToken('write', key2, holder);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key2}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put2.status, 200, 'quota freed by the DELETE must let the same holder upload again');
});

test('S03-H3: a missing/unknowable Content-Length cannot be used to smuggle bytes past the per-user quota', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' });
  const holder = 'user-liar';

  // Spend most of the quota honestly first: 900 of the 1000-byte budget.
  const key1 = `media/${CHAT_ID}/honest.jpg`;
  const token1 = await mintToken('write', key1, holder);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key1}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Now upload another 900-byte body via a stream, whose length cannot be
  // known up front — Node's fetch neither preserves a caller-supplied
  // Content-Length alongside a stream body nor computes one itself, so
  // `declaredBytes` resolves to 0 via safeInt's fallback, exactly like a
  // client that omits the header entirely. The pre-check only sees
  // 900 (already used) + 0 (declared) = 900 <= 1000, so it trivially
  // passes — even though the real total would be 1800, well over quota.
  // The real 900-byte body is stored by R2 regardless of what was
  // declared, so only the post-upload re-check against the true HEAD size
  // can catch this and undo the write.
  const key2 = `media/${CHAT_ID}/lie.jpg`;
  const token2 = await mintToken('write', key2, holder);
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new Uint8Array(900));
      controller.close();
    },
  });
  const put2 = await worker.fetch(new Request(`https://worker.example/${key2}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}` },
    body: stream,
    duplex: 'half',
  }), env, ctx);
  assert.equal(put2.status, 507, await put2.clone().text());

  // The object must not be left behind in R2 after the rejection.
  const headAfterReject = await env.HOT_BUCKET.head(key2);
  assert.equal(headAfterReject, null, 'a rejected upload must not leave the object stored in R2');

  // Quota must not have been credited for the rejected upload either — the
  // holder must still be sitting at exactly 900/1000, unaffected by the
  // rejected write, and unable to fit another 900-byte upload.
  const key3 = `media/${CHAT_ID}/two.jpg`;
  const token3 = await mintToken('write', key3, holder);
  const put3 = await worker.fetch(new Request(`https://worker.example/${key3}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token3}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put3.status, 507, 'the rejected/deleted upload must not have consumed any of the quota — the holder is still at 900/1000, not 1800/1000');
});

// ─── S03-M2: tokens must be uploader-bound, not just scope-bound ───────────
// A capability token only proves the caller participates in the chat/group
// that owns the key (verifyMediaToken has no concept of "whose media this
// is") — every participant of a conversation can mint a valid write/delete
// token for any key inside it, including one another participant uploaded.
// These tests assert the Worker itself closes that gap at the object layer.

test('S03-M2: a different chat participant with a valid write token cannot overwrite media they did not upload', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const attacker = 'user-bob'; // also a participant of the same chat — has a legitimately-minted token

  const key = `media/${CHAT_ID}/photo.jpg`;
  const putToken1 = await mintToken('write', key, uploader);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken1}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Bob's token is entirely genuine — correctly signed, correct op, correct
  // key, unexpired — it is only bound to the wrong holder for this object.
  const putToken2 = await mintToken('write', key, attacker);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken2}`, 'Content-Length': '20' },
    body: new Uint8Array(20),
  }), env, ctx);
  assert.equal(put2.status, 403, await put2.clone().text());

  // The original bytes must be untouched — no swap occurred.
  const head = await env.HOT_BUCKET.head(key);
  assert.equal(head.size, 10, 'the object must be unchanged after the rejected overwrite attempt');
});

test('S03-M2: the original uploader may still overwrite their own object', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const key = `media/${CHAT_ID}/photo.jpg`;

  const putToken1 = await mintToken('write', key, uploader);
  await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken1}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);

  const putToken2 = await mintToken('write', key, uploader);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken2}`, 'Content-Length': '20' },
    body: new Uint8Array(20),
  }), env, ctx);
  assert.equal(put2.status, 200, await put2.clone().text());

  const head = await env.HOT_BUCKET.head(key);
  assert.equal(head.size, 20, 'the same uploader must be able to overwrite their own object');
});

test('S03-M2: a brand-new key (no existing object) may be written by any holder with a valid scope-bound token', async () => {
  const env = makeEnv();
  const key = `media/${CHAT_ID}/brand-new.jpg`;
  const token = await mintToken('write', key, 'user-anyone');
  const put = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Length': '5' },
    body: new Uint8Array(5),
  }), env, ctx);
  // Nothing exists yet at this key, so there is no prior uploader to protect —
  // the ownership check must only ever block overwriting SOMEONE ELSE's
  // existing object, never the initial upload itself.
  assert.equal(put.status, 200, await put.clone().text());
});

test('S03-M2: a different chat participant with a valid delete token cannot delete R2-tier media they did not upload', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const attacker = 'user-bob';
  const key = `media/${CHAT_ID}/voicemsg.m4a`;

  const putToken = await mintToken('write', key, uploader);
  await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);

  const deleteToken = await mintToken('delete', key, attacker);
  const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(del.status, 403, await del.clone().text());

  // The object must still exist — the delete must never have happened.
  const head = await env.HOT_BUCKET.head(key);
  assert.notEqual(head, null, 'the object must still exist after the rejected delete attempt');
});

test('S03-M2: the original uploader may still delete their own R2-tier object', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const key = `media/${CHAT_ID}/voicemsg.m4a`;

  const putToken = await mintToken('write', key, uploader);
  await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);

  const deleteToken = await mintToken('delete', key, uploader);
  const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(del.status, 200, await del.clone().text());
  assert.equal(await env.HOT_BUCKET.head(key), null, 'the uploader\'s own delete must actually remove the object');
});

test('S03-M2: a different chat participant with a valid delete token cannot delete B2 (cold-tier) media they did not upload', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const attacker = 'user-bob';
  const key = `media/${CHAT_ID}/coldfile.jpg`;

  // Simulate an object that has already tiered to B2: nothing in R2 (the
  // Worker's cold-tier branch is only reached when `HOT_BUCKET.head()` misses),
  // and stub the global `fetch` that aws4fetch's AwsClient wraps, so a HEAD on
  // the B2 URL returns the migrated object's carried-over uploader tag
  // (`x-amz-meta-uploader` — see the `scheduled()` migration handler, which
  // copies this from R2's customMetadata.uploader specifically for this check).
  const realFetch = globalThis.fetch;
  let deleteCalled = false;
  globalThis.fetch = async (input) => {
    const req = input instanceof Request ? input : new Request(input);
    if (req.method === 'HEAD') {
      return new Response(null, { status: 200, headers: { 'x-amz-meta-uploader': uploader } });
    }
    if (req.method === 'DELETE') {
      deleteCalled = true;
      return new Response(null, { status: 204 });
    }
    return realFetch(input);
  };

  try {
    const deleteToken = await mintToken('delete', key, attacker);
    const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${deleteToken}` },
    }), env, ctx);
    assert.equal(del.status, 403, await del.clone().text());
    assert.equal(deleteCalled, false, 'the B2 DELETE must never be issued once ownership fails the HEAD check');
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('S03-M2: the original uploader may still delete their own B2 (cold-tier) object', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const key = `media/${CHAT_ID}/coldfile.jpg`;

  const realFetch = globalThis.fetch;
  let deleteCalled = false;
  globalThis.fetch = async (input) => {
    const req = input instanceof Request ? input : new Request(input);
    if (req.method === 'HEAD') {
      return new Response(null, { status: 200, headers: { 'x-amz-meta-uploader': uploader } });
    }
    if (req.method === 'DELETE') {
      deleteCalled = true;
      return new Response(null, { status: 204 });
    }
    return realFetch(input);
  };

  try {
    const deleteToken = await mintToken('delete', key, uploader);
    const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${deleteToken}` },
    }), env, ctx);
    assert.equal(del.status, 200, await del.clone().text());
    assert.equal(deleteCalled, true, 'the uploader\'s own delete must actually reach the B2 DELETE call');
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('S03-M2: a B2 (cold-tier) object with no carried-over uploader tag (pre-S3-11 migration) may still be deleted by any holder with a valid scope-bound token', async () => {
  const env = makeEnv();
  const key = `media/${CHAT_ID}/legacycoldfile.jpg`;

  const realFetch = globalThis.fetch;
  let deleteCalled = false;
  globalThis.fetch = async (input) => {
    const req = input instanceof Request ? input : new Request(input);
    if (req.method === 'HEAD') {
      // No x-amz-meta-uploader header at all — this object predates the
      // migration fix that started carrying the uploader tag to B2.
      return new Response(null, { status: 200, headers: {} });
    }
    if (req.method === 'DELETE') {
      deleteCalled = true;
      return new Response(null, { status: 204 });
    }
    return realFetch(input);
  };

  try {
    const deleteToken = await mintToken('delete', key, 'user-anyone');
    const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${deleteToken}` },
    }), env, ctx);
    assert.equal(del.status, 200, await del.clone().text());
    assert.equal(deleteCalled, true, 'an untagged legacy object must not be permanently undeletable');
  } finally {
    globalThis.fetch = realFetch;
  }
});

// ─── S03-M1: served Content-Type is derived from the key, never trusted ───
// The extension is already tightly allow-listed, but the declared
// Content-Type header itself was fully attacker-controlled and replayed
// verbatim on every GET, with no nosniff/Content-Disposition.

test('S03-M1: an attacker-declared Content-Type at PUT time is ignored — GET serves the type derived from the key extension instead (hot tier)', async () => {
  const env = makeEnv();
  const holder = 'user-alice';
  const key = `media/${CHAT_ID}/photo.jpg`;

  const putToken = await mintToken('write', key, holder);
  const put = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: {
      Authorization: `Bearer ${putToken}`,
      'Content-Length': '10',
      // Attacker-chosen type that does not match the .jpg extension.
      'Content-Type': 'text/html',
    },
    body: new Uint8Array(10),
  }), env, ctx);
  assert.equal(put.status, 200, await put.clone().text());

  const getToken = await mintToken('read', key, holder);
  const get = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'GET',
    headers: { Authorization: `Bearer ${getToken}` },
  }), env, ctx);
  assert.equal(get.status, 200);
  assert.equal(get.headers.get('Content-Type'), 'image/jpeg', 'must be derived from the .jpg extension, not the attacker-declared text/html');
  assert.equal(get.headers.get('X-Content-Type-Options'), 'nosniff');
  assert.equal(get.headers.get('Content-Disposition'), 'attachment');
});

test('S03-M1: the cold (B2) tier also serves the key-derived Content-Type with nosniff/Content-Disposition, ignoring B2\'s own header', async () => {
  const env = makeEnv();
  const key = `media/${CHAT_ID}/voice.m4a`;

  const realFetch = globalThis.fetch;
  globalThis.fetch = async (input) => {
    const req = input instanceof Request ? input : new Request(input);
    if (req.method === 'GET') {
      return new Response(new Uint8Array(5), {
        status: 200,
        headers: { 'Content-Type': 'application/x-malicious', 'ETag': '"cold-etag"' },
      });
    }
    return realFetch(input);
  };

  try {
    const getToken = await mintToken('read', key, 'user-anyone');
    const get = await worker.fetch(new Request(`https://worker.example/${key}`, {
      method: 'GET',
      headers: { Authorization: `Bearer ${getToken}` },
    }), env, ctx);
    assert.equal(get.status, 200);
    assert.equal(get.headers.get('Content-Type'), 'audio/mp4', 'must be derived from the .m4a extension, not B2\'s stored/upstream header');
    assert.equal(get.headers.get('X-Content-Type-Options'), 'nosniff');
    assert.equal(get.headers.get('Content-Disposition'), 'attachment');
  } finally {
    globalThis.fetch = realFetch;
  }
});

// ─── S03-M3: delete tokens are single-use and reject a legacy 5-part shape ─

test('S03-M3: DELETE fails closed when the replay KV namespace is absent', async () => {
  const env = makeEnv({ RATE_KV: undefined });
  const key = `media/${'z'.repeat(20)}/kv-unavailable.jpg`;
  const holder = 'user-alice';
  const putToken = await mintToken('write', key, holder);
  const put = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '3' },
    body: 'abc',
  }), env, ctx);
  assert.equal(put.status, 200);
  assert.ok(await env.HOT_BUCKET.head(key));

  const deleteToken = await mintToken('delete', key, holder);
  const deletion = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(deletion.status, 503);
  assert.match((await deletion.json()).error, /replay protection unavailable/i);
  assert.ok(await env.HOT_BUCKET.head(key), 'object must remain untouched when replay protection is unavailable');
});

test('S03-M3: DELETE fails closed when replay KV lookup throws', async () => {
  const env = makeEnv();
  const key = `media/${'z'.repeat(20)}/kv-error.jpg`;
  const holder = 'user-alice';
  const putToken = await mintToken('write', key, holder);
  const put = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '3' },
    body: 'abc',
  }), env, ctx);
  assert.equal(put.status, 200);
  const originalGet = env.RATE_KV.get.bind(env.RATE_KV);
  env.RATE_KV.get = async (kvKey) => {
    if (typeof kvKey === 'string' && kvKey.startsWith('token:used:')) {
      throw new Error('simulated replay KV outage');
    }
    return originalGet(kvKey);
  };

  const deleteToken = await mintToken('delete', key, holder);
  const deletion = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(deletion.status, 503);
  assert.ok(await env.HOT_BUCKET.head(key), 'object must remain untouched when KV lookup fails');
});

test('S03-M3: DELETE does not mutate storage when writing the single-use marker fails', async () => {
  const env = makeEnv();
  const key = `media/${'z'.repeat(20)}/kv-put-error.jpg`;
  const holder = 'user-alice';
  const putToken = await mintToken('write', key, holder);
  const put = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '3' },
    body: 'abc',
  }), env, ctx);
  assert.equal(put.status, 200);
  env.RATE_KV.put = async () => { throw new Error('simulated KV write outage'); };

  const deleteToken = await mintToken('delete', key, holder);
  const deletion = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(deletion.status, 503);
  assert.ok(await env.HOT_BUCKET.head(key), 'object must remain untouched when KV marker cannot be persisted');
});

test('S03-M3: a delete token can only be used once — replaying it after a successful delete is rejected', async () => {
  const env = makeEnv();
  const holder = 'user-single-use';
  const key = `media/${CHAT_ID}/onceonly.jpg`;

  const putToken = await mintToken('write', key, holder);
  const put = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);
  assert.equal(put.status, 200, await put.clone().text());

  const deleteToken = await mintToken('delete', key, holder);
  const del1 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(del1.status, 200, await del1.clone().text());

  // Replay the exact same token — must be rejected, without ever touching
  // R2/B2 again (the object is already gone; a non-replay-guarded path
  // would fall through to the cold-tier branch and attempt a real network
  // call, which this assertion also implicitly guards against hanging on).
  const del2 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(del2.status, 403, await del2.clone().text());
  const payload = await del2.json();
  assert.match(payload.error, /already used/i);
});

test('S03-M3: two independently-minted delete tokens for different keys are each single-use independently', async () => {
  const env = makeEnv();
  const holder = 'user-independent';
  const keyA = `media/${CHAT_ID}/a.jpg`;
  const keyB = `media/${CHAT_ID}/b.jpg`;

  for (const key of [keyA, keyB]) {
    const putToken = await mintToken('write', key, holder);
    await worker.fetch(new Request(`https://worker.example/${key}`, {
      method: 'PUT',
      headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '10' },
      body: new Uint8Array(10),
    }), env, ctx);
  }

  const deleteTokenA = await mintToken('delete', keyA, holder);
  const delA = await worker.fetch(new Request(`https://worker.example/${keyA}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteTokenA}` },
  }), env, ctx);
  assert.equal(delA.status, 200, await delA.clone().text());

  // A separate, never-before-used token for a different key must be
  // unaffected by keyA's token having just been consumed.
  const deleteTokenB = await mintToken('delete', keyB, holder);
  const delB = await worker.fetch(new Request(`https://worker.example/${keyB}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteTokenB}` },
  }), env, ctx);
  assert.equal(delB.status, 200, await delB.clone().text());
});

test('S03-M3: a legacy 5-part token (pre-jti wire format) is rejected as malformed', async () => {
  const env = makeEnv();
  const key = `media/${CHAT_ID}/legacy.jpg`;
  const holder = 'user-x';
  const expiresAt = Date.now() + 60_000;
  const payload = `v1|read|${expiresAt}|${holder}|${key}`; // old shape, no jti segment
  const sig = await hmacSha256(MEDIA_TOKEN_SECRET, payload);
  const legacyToken = `v1.read.${expiresAt}.${holder}.${bytesToB64url(sig)}`;

  const res = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'GET',
    headers: { Authorization: `Bearer ${legacyToken}` },
  }), env, ctx);
  assert.equal(res.status, 401);
  const payloadJson = await res.json();
  assert.match(payloadJson.error, /malformed/i);
});

// ─── S03-L2: a malformed percent-escape must 400, not throw ───────────────

test('S03-L2: a malformed percent-escape in the request path is rejected with 400, not an uncaught exception', async () => {
  const env = makeEnv();
  const req = new Request('https://worker.example/media/%zzinvalid', { method: 'GET' });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.status, 400);
});

test('S03-L2: a lone trailing "%" in the request path is rejected with 400, not an uncaught exception', async () => {
  const env = makeEnv();
  const req = new Request('https://worker.example/media/foo%', { method: 'GET' });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.status, 400);
});

// ─── S03-H3 follow-up: same-holder overwrite must account for the DELTA ───
// A same-holder PUT-over-an-existing-key is the one overwrite S03-M2 allows.
// Before this fix, crediting the full new size on top of the old size (never
// subtracted) double-counted every overwrite in both the global R2 counter
// and the holder's per-user counter.

test('S03-H3 follow-up: a same-size same-holder overwrite does not inflate the per-user quota counter', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '500' });
  const holder = 'user-samesize';
  const key = `media/${CHAT_ID}/same.jpg`;

  const token1 = await mintToken('write', key, holder);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '500' },
    body: new Uint8Array(500),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Overwrite the SAME key with another 500-byte body. If the old 500 bytes
  // were never subtracted, the projected total would be 500 (already
  // counted) + 500 (new) = 1000 > 500, and this would be wrongly rejected.
  const token2 = await mintToken('write', key, holder);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}`, 'Content-Length': '500' },
    body: new Uint8Array(500),
  }), env, ctx);
  assert.equal(put2.status, 200, await put2.clone().text());
});

test('S03-H3 follow-up: a growing same-holder overwrite is charged only the delta, not the full new size on top of the old', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' });
  const holder = 'user-growing';
  const key = `media/${CHAT_ID}/grow.jpg`;

  const token1 = await mintToken('write', key, holder);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '400' },
    body: new Uint8Array(400),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Growing 400 -> 900 is a delta of +500, so the true projected total is
  // 400 (already counted) - 400 (old, subtracted) + 900 (new) = 900 <= 1000.
  // The pre-fix arithmetic (userBytes + declaredBytes, no subtraction) would
  // have computed 400 + 900 = 1300 > 1000 and wrongly rejected this.
  const token2 = await mintToken('write', key, holder);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put2.status, 200, await put2.clone().text());
});

test('S03-H3 follow-up: a shrinking same-holder overwrite frees the quota headroom it should', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' });
  const holder = 'user-shrinking';
  const key = `media/${CHAT_ID}/shrink.jpg`;

  const token1 = await mintToken('write', key, holder);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Shrink 900 -> 100 (delta -800). If the old 900 is never subtracted, the
  // per-user counter stays stuck at 900 + 900 = 1800 forever, and the
  // subsequent 900-byte upload to a NEW key below would be wrongly rejected.
  const token2 = await mintToken('write', key, holder);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}`, 'Content-Length': '100' },
    body: new Uint8Array(100),
  }), env, ctx);
  assert.equal(put2.status, 200, await put2.clone().text());

  // Now 100/1000 used. A fresh 900-byte upload to a different key must fit:
  // 100 + 900 = 1000 <= 1000.
  const key2 = `media/${CHAT_ID}/shrink-followup.jpg`;
  const token3 = await mintToken('write', key2, holder);
  const put3 = await worker.fetch(new Request(`https://worker.example/${key2}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token3}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put3.status, 200, 'the shrinking overwrite must have freed the 800 bytes of headroom it gave back');
});

// ─── S10-N3: a client DELETE racing the nightly migration must not orphan ──
// the object in the B2 cold tier. The migration's steps are
// get R2 → PUT B2 → HEAD R2 → delete R2. If a client DELETE lands after the
// get() but before the PUT, it removes R2 and its own best-effort B2 delete
// 404s (nothing there yet); the migration's PUT then recreates the object in
// B2. The post-PUT HEAD sees R2 empty and, before this fix, did nothing —
// leaving an unreferenced B2 copy that GET would keep serving forever. The
// fix makes that branch fire a compensating B2 delete of the copy it wrote.

// A migration-capable R2 bucket fake: supports list()/get()/head()/delete().
// `onGet` lets a test simulate a concurrent client DELETE landing during the
// migration's B2 PUT by mutating the bucket at a controlled point.
function makeMigrationBucket(initial = []) {
  const objects = new Map();
  for (const o of initial) {
    objects.set(o.key, {
      size: o.size,
      customMetadata: o.customMetadata ?? {},
      httpMetadata: o.httpMetadata ?? {},
      httpEtag: o.httpEtag ?? `"etag-${o.key}"`,
      uploaded: o.uploaded ?? new Date().toISOString(),
    });
  }
  return {
    objects,
    async list() {
      return {
        objects: [...objects.entries()].map(([key, o]) => ({
          key,
          size: o.size,
          customMetadata: o.customMetadata,
          httpMetadata: o.httpMetadata,
          uploaded: o.uploaded,
        })),
        truncated: false,
        cursor: null,
      };
    },
    async get(key) {
      const o = objects.get(key);
      if (!o) return null;
      return {
        ...o,
        httpEtag: o.httpEtag,
        arrayBuffer: async () => new ArrayBuffer(o.size),
      };
    },
    async head(key) {
      const o = objects.get(key);
      if (!o) return null;
      return { size: o.size, customMetadata: o.customMetadata, httpMetadata: o.httpMetadata, httpEtag: o.httpEtag };
    },
    async delete(key) { objects.delete(key); },
  };
}

const FORTY_DAYS_AGO = (Date.now() - 40 * 86_400_000).toString();

test('S10-N3: a client DELETE that removes R2 during the migration PUT fires a compensating B2 delete so the migrated copy is not orphaned in cold tier', async () => {
  const key = `media/${CHAT_ID}/racing.jpg`;
  const bucket = makeMigrationBucket([
    { key, size: 100, customMetadata: { uploadedAt: FORTY_DAYS_AGO, uploader: 'user-alice' } },
  ]);
  const env = makeEnv({ HOT_BUCKET: bucket });

  const b2Calls = [];
  // Cloudflare keeps waitUntil() work alive after the scheduled handler returns.
  // The test must explicitly drain those promises before restoring the mocked
  // global fetch, or the compensating B2 DELETE can run against the real fetch.
  const waitUntilTasks = [];
  const testCtx = { waitUntil: (promise) => { waitUntilTasks.push(Promise.resolve(promise)); } };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const req = input instanceof Request ? input : new Request(input, init);
    const method = req.method;
    b2Calls.push({ method, url: req.url });
    if (method === 'PUT') {
      // Simulate the client DELETE landing in the window between the
      // migration's get() and this PUT: R2 loses the object right now.
      await bucket.delete(key);
      return new Response(null, { status: 200 });
    }
    if (method === 'DELETE') {
      return new Response(null, { status: 204 });
    }
    // Step 3 ListObjectsV2 reconciliation — return an empty bucket listing.
    return new Response('<ListBucketResult></ListBucketResult>', { status: 200 });
  };

  try {
    await worker.scheduled({}, env, testCtx);
    // Match the Workers runtime contract: waitUntil tasks remain alive after
    // scheduled() resolves. Drain them while the B2 fetch mock is still active.
    await Promise.all(waitUntilTasks);
  } finally {
    globalThis.fetch = realFetch;
  }

  const putForKey = b2Calls.some((c) => c.method === 'PUT' && c.url.includes('racing.jpg'));
  const compensatingDelete = b2Calls.some((c) => c.method === 'DELETE' && c.url.includes('racing.jpg'));
  assert.equal(putForKey, true, 'the migration must have PUT the object to B2 first');
  assert.equal(
    compensatingDelete,
    true,
    'once the post-PUT HEAD finds R2 empty, the migration must delete the B2 copy it just wrote — otherwise the user-deleted object survives in cold tier and GET keeps serving it'
  );
});

test('S10-N3: a normal migration (no racing delete) tiers the object to B2 and does NOT fire a compensating B2 delete', async () => {
  const key = `media/${CHAT_ID}/normal.jpg`;
  const bucket = makeMigrationBucket([
    { key, size: 100, customMetadata: { uploadedAt: FORTY_DAYS_AGO, uploader: 'user-alice' } },
  ]);
  const env = makeEnv({ HOT_BUCKET: bucket });

  const b2Calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const req = input instanceof Request ? input : new Request(input, init);
    b2Calls.push({ method: req.method, url: req.url });
    if (req.method === 'PUT') return new Response(null, { status: 200 });   // object stays in R2
    if (req.method === 'DELETE') return new Response(null, { status: 204 });
    return new Response('<ListBucketResult></ListBucketResult>', { status: 200 });
  };

  try {
    await worker.scheduled({}, env, ctx);
  } finally {
    globalThis.fetch = realFetch;
  }

  const compensatingDelete = b2Calls.some((c) => c.method === 'DELETE' && c.url.includes('normal.jpg'));
  assert.equal(compensatingDelete, false, 'the compensating B2 delete must fire only on the concurrent-delete race, never on a healthy migration');
  assert.equal(await bucket.head(key), null, 'a healthy migration must remove the object from R2 after tiering it to B2');
});


test('S10-N3: a changed R2 ETag keeps the newer hot object and counts its bytes', async () => {
  const key = `media/${CHAT_ID}/changed-etag.jpg`;
  const bucket = makeMigrationBucket([
    { key, size: 100, customMetadata: { uploadedAt: FORTY_DAYS_AGO, uploader: 'user-alice' } },
  ]);
  const originalHead = bucket.head.bind(bucket);
  bucket.head = async (objectKey) => {
    const current = await originalHead(objectKey);
    return current ? { ...current, httpEtag: '"newer-client-write"' } : null;
  };
  const env = makeEnv({ HOT_BUCKET: bucket });
  const b2Calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const req = input instanceof Request ? input : new Request(input, init);
    b2Calls.push({ method: req.method, url: req.url });
    if (req.method === 'PUT') return new Response(null, { status: 200 });
    if (req.method === 'DELETE') return new Response(null, { status: 204 });
    return new Response('<ListBucketResult></ListBucketResult>', { status: 200 });
  };

  try {
    await worker.scheduled({}, env, ctx);
  } finally {
    globalThis.fetch = realFetch;
  }

  assert.ok(await originalHead(key), 'a changed object must remain in R2');
  assert.equal(b2Calls.some((call) => call.method === 'DELETE'), false,
    'migration must not delete an R2 object whose ETag changed after the GET');
  assert.equal(env.RATE_KV.store.get('global:storage:r2'), '100',
    'the changed hot object must remain included in the authoritative R2 byte count');
});

test('S10-N3: a failed post-PUT R2 HEAD retains the hot source and its storage accounting', async () => {
  const key = `media/${CHAT_ID}/head-failure.jpg`;
  const bucket = makeMigrationBucket([
    { key, size: 123, customMetadata: { uploadedAt: FORTY_DAYS_AGO, uploader: 'user-alice' } },
  ]);
  bucket.head = async () => { throw new Error('transient R2 lookup failure'); };
  const env = makeEnv({ HOT_BUCKET: bucket });
  const b2Calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const req = input instanceof Request ? input : new Request(input, init);
    b2Calls.push({ method: req.method, url: req.url });
    if (req.method === 'PUT') return new Response(null, { status: 200 });
    if (req.method === 'DELETE') return new Response(null, { status: 204 });
    return new Response('<ListBucketResult></ListBucketResult>', { status: 200 });
  };

  try {
    await worker.scheduled({}, env, ctx);
  } finally {
    globalThis.fetch = realFetch;
  }

  assert.ok(bucket.objects.has(key), 'a failed HEAD is not proof of deletion; retain the R2 object');
  assert.equal(b2Calls.some((call) => call.method === 'DELETE'), false,
    'an ambiguous HEAD failure must not trigger the compensating delete');
  assert.equal(env.RATE_KV.store.get('global:storage:r2'), '123',
    'bytes must remain charged to R2 until a later run confirms migration');
});

test('S10-N3: a failed B2 PUT leaves the source in R2 and accounts it for retry', async () => {
  const key = `media/${CHAT_ID}/put-failure.jpg`;
  const bucket = makeMigrationBucket([
    { key, size: 234, customMetadata: { uploadedAt: FORTY_DAYS_AGO, uploader: 'user-alice' } },
  ]);
  const env = makeEnv({ HOT_BUCKET: bucket });
  const b2Calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const req = input instanceof Request ? input : new Request(input, init);
    b2Calls.push({ method: req.method, url: req.url });
    if (req.method === 'PUT') return new Response('temporary B2 failure', { status: 503 });
    if (req.method === 'DELETE') return new Response(null, { status: 204 });
    return new Response('<ListBucketResult></ListBucketResult>', { status: 200 });
  };

  try {
    await worker.scheduled({}, env, ctx);
  } finally {
    globalThis.fetch = realFetch;
  }

  assert.ok(bucket.objects.has(key), 'a failed B2 upload must not delete the hot source');
  assert.equal(b2Calls.some((call) => call.method === 'DELETE'), false,
    'a failed B2 upload must not issue a destructive delete');
  assert.equal(env.RATE_KV.store.get('global:storage:r2'), '234',
    'the source object remains included in R2 accounting so tomorrow\'s scheduled run can retry');
});

test('S10-N3: a failed compensating B2 DELETE is observable without logging the media key', async () => {
  const key = `media/${CHAT_ID}/compensation-failure.jpg`;
  const bucket = makeMigrationBucket([
    { key, size: 100, customMetadata: { uploadedAt: FORTY_DAYS_AGO, uploader: 'user-alice' } },
  ]);
  bucket.head = async () => { await bucket.delete(key); return null; }; // simulate a confirmed client DELETE from R2
  const env = makeEnv({ HOT_BUCKET: bucket });
  const b2Calls = [];
  const errorLogs = [];
  const warnLogs = [];
  const waitUntilTasks = [];
  const testCtx = { waitUntil: (promise) => { waitUntilTasks.push(Promise.resolve(promise)); } };
  const realFetch = globalThis.fetch;
  const realError = console.error;
  const realWarn = console.warn;
  globalThis.fetch = async (input, init) => {
    const req = input instanceof Request ? input : new Request(input, init);
    b2Calls.push({ method: req.method, url: req.url });
    if (req.method === 'PUT') return new Response(null, { status: 200 });
    if (req.method === 'DELETE') return new Response('temporary B2 delete failure', { status: 503 });
    return new Response('<ListBucketResult></ListBucketResult>', { status: 200 });
  };
  console.error = (...args) => errorLogs.push(args.join(' '));
  console.warn = (...args) => warnLogs.push(args.join(' '));

  try {
    await worker.scheduled({}, env, testCtx);
    await Promise.all(waitUntilTasks);
  } finally {
    globalThis.fetch = realFetch;
    console.error = realError;
    console.warn = realWarn;
  }

  assert.equal(bucket.objects.has(key), false, 'the test represents a confirmed client deletion from R2');
  assert.equal(b2Calls.some((call) => call.method === 'DELETE' && call.url.includes(key)), true,
    'the migration must attempt a compensating delete for its own B2 write');
  assert.ok(errorLogs.some((line) => /S10-N3: compensating B2 DELETE failed with status 503; orphan may remain/.test(line)),
    'a failed compensation must be visible to an operator');
  assert.equal(errorLogs.some((line) => line.includes(key)), false,
    'the failure diagnostic must not expose the conversation/media key');
  assert.equal(warnLogs.some((line) => line.includes(key)), false,
    'migration warnings must not expose the conversation/media key');
  assert.ok(errorLogs.some((line) => line.includes('compensating B2 DELETE failed')),
    'the compensation failure must remain visible even though the key is redacted');
});


test('free-tier guard: B2 at its safety cap retains aged media in R2 without uploading', async () => {
  const key = `media/${CHAT_ID}/b2-full.jpg`;
  const bucket = makeMigrationBucket([
    { key, size: 100, customMetadata: { uploadedAt: FORTY_DAYS_AGO, uploader: 'user-alice' } },
  ]);
  const env = makeEnv({ HOT_BUCKET: bucket });
  const calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const req = input instanceof Request ? input : new Request(input, init);
    calls.push(req.method);
    if (req.method === 'PUT') return new Response(null, { status: 200 });
    return new Response('<ListBucketResult><Contents><Size>10200547328</Size></Contents></ListBucketResult>', { status: 200 });
  };
  try {
    await worker.scheduled({}, env, ctx);
  } finally {
    globalThis.fetch = realFetch;
  }
  assert.equal(calls.includes('PUT'), false, 'B2 cap must be checked before any cold-tier upload');
  assert.ok(await bucket.head(key), 'the hot source must remain available for a later retry');
  assert.equal(env.RATE_KV.store.get('global:storage:r2'), '100');
});

test('free-tier guard: failed B2 preflight reconciliation fails closed and skips migration', async () => {
  const key = `media/${CHAT_ID}/b2-unavailable.jpg`;
  const bucket = makeMigrationBucket([
    { key, size: 100, customMetadata: { uploadedAt: FORTY_DAYS_AGO, uploader: 'user-alice' } },
  ]);
  const env = makeEnv({ HOT_BUCKET: bucket });
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response('unavailable', { status: 503 });
  try {
    await worker.scheduled({}, env, ctx);
  } finally {
    globalThis.fetch = realFetch;
  }
  assert.ok(await bucket.head(key), 'uncertain B2 usage must never cause the hot source to be deleted');
  assert.equal(env.RATE_KV.store.get('global:storage:r2'), undefined,
    'the scheduled job exits before changing storage accounting');
});
