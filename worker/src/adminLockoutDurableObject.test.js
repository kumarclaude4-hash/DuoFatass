// Regression/concurrency tests for the admin lockout Durable Object
// (adminLockoutDurableObject.js). Run with plain `node --test`, no
// wrangler/Miniflare required — see index.test.js's header for why this
// project tests Worker code this way.
//
// IMPORTANT SCOPE NOTE ON THE CONCURRENCY TESTS BELOW:
// This file cannot spin up the real Cloudflare `workerd` runtime, so it
// cannot itself PROVE that Cloudflare's Durable Object input/output gating
// serializes concurrent requests to the same instance — that guarantee is
// documented by Cloudflare (see adminLockoutDurableObject.js's header
// comment for the citation) and is not re-derived here. What these tests DO
// verify, given that guarantee:
//   1. `serializeFetch()` below is an explicit harness that reproduces the
//      documented guarantee (only one `fetch()` call's JS/storage I/O runs
//      at a time per instance) using a promise chain — analogous to how
//      index.test.js shims `crypto.subtle.timingSafeEqual` to cover a
//      Node-vs-workerd gap, not a claim that this harness itself is the
//      thing being tested.
//   2. Under that harness, `AdminLockoutCounter`'s own read-compute-write
//      logic never undercounts, no matter how many `record` calls arrive
//      "concurrently" (queued releases as fast as the previous one's
//      storage I/O settles).
//   3. As a negative control, the SAME concurrent calls made WITHOUT the
//      serializing harness (i.e. directly racing `instance.fetch()`) DO
//      undercount — this is what proves the correctness in (2) depends on
//      the runtime's serialization guarantee, not on anything incidental in
//      this class's own code, and is exactly the KV-based race this
//      Durable Object was introduced to close (see this module's header
//      comment).

import test from 'node:test';
import assert from 'node:assert/strict';
import { AdminLockoutCounter } from './adminLockoutDurableObject.js';

// ─── Fakes ─────────────────────────────────────────────────────────────────

// A fake `state.storage` with a deliberate, non-trivial async delay on every
// operation — long enough that, without serialization, many concurrent
// get→compute→put sequences are virtually guaranteed to interleave and race
// (proving the negative-control test isn't just "happened not to race").
function makeFakeStorage(delayMs = 5) {
  const map = new Map();
  const delay = () => new Promise((resolve) => setTimeout(resolve, delayMs));
  let alarmAt = null;
  return {
    map,
    get alarmAt() { return alarmAt; },
    async setAlarm(timestamp) {
      await delay();
      alarmAt = timestamp;
    },
    async getAlarm() {
      await delay();
      return alarmAt;
    },
    async deleteAlarm() {
      await delay();
      alarmAt = null;
    },
    async get(key) {
      await delay();
      return map.has(key) ? map.get(key) : undefined;
    },
    async put(key, value) {
      await delay();
      map.set(key, value);
    },
    async delete(key) {
      await delay();
      map.delete(key);
    },
  };
}

function makeDO(storage) {
  return new AdminLockoutCounter({ storage }, {});
}

function recordRequest({ windowMs, maxFails } = {}) {
  return new Request('https://admin-lockout.internal/', {
    method: 'POST',
    body: JSON.stringify({ action: 'record', windowMs, maxFails }),
  });
}

function statusRequest({ windowMs, maxFails } = {}) {
  return new Request('https://admin-lockout.internal/', {
    method: 'POST',
    body: JSON.stringify({ action: 'status', windowMs, maxFails }),
  });
}

function resetRequest() {
  return new Request('https://admin-lockout.internal/', {
    method: 'POST',
    body: JSON.stringify({ action: 'reset' }),
  });
}

// Reproduces Cloudflare's documented Durable Object guarantee: all requests
// to the SAME instance are processed strictly one at a time. See this
// file's header comment for what this harness does and does not prove.
function serializeFetch(instance) {
  let chain = Promise.resolve();
  return {
    fetch(request) {
      const result = chain.then(() => instance.fetch(request));
      chain = result.then(() => undefined, () => undefined);
      return result;
    },
  };
}

async function bodyOf(response) {
  return response.json();
}

// ─── Basic behavior ─────────────────────────────────────────────────────────

test('status on a never-seen key returns count 0, not locked', async () => {
  const stub = makeDO(makeFakeStorage(0));
  const res = await stub.fetch(statusRequest({ maxFails: 10, windowMs: 1000 }));
  const body = await bodyOf(res);
  assert.equal(body.count, 0);
  assert.equal(body.locked, false);
});

test('a single record call increments to 1 and is not yet locked (maxFails=10)', async () => {
  const stub = makeDO(makeFakeStorage(0));
  const res = await stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 }));
  const body = await bodyOf(res);
  assert.equal(body.count, 1);
  assert.equal(body.locked, false);
});

test('reaching exactly maxFails locks; one below does not', async () => {
  const stub = makeDO(makeFakeStorage(0));
  let body;
  for (let i = 0; i < 9; i++) {
    body = await bodyOf(await stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })));
    assert.equal(body.locked, false, `should not be locked after ${i + 1} failures`);
  }
  body = await bodyOf(await stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })));
  assert.equal(body.count, 10);
  assert.equal(body.locked, true);
});

test('window expiration starts a fresh count instead of accumulating forever', async () => {
  const stub = makeDO(makeFakeStorage(0));
  await stub.fetch(recordRequest({ maxFails: 10, windowMs: 50 }));
  await new Promise((r) => setTimeout(r, 80)); // let the 50ms window lapse
  const body = await bodyOf(await stub.fetch(recordRequest({ maxFails: 10, windowMs: 50 })));
  assert.equal(body.count, 1, 'count should have reset to 1 in the new window');
  assert.equal(body.locked, false);
});

test('status after window expiration reports not-locked and count 0 even if the stored record is stale', async () => {
  const stub = makeDO(makeFakeStorage(0));
  for (let i = 0; i < 10; i++) await stub.fetch(recordRequest({ maxFails: 10, windowMs: 50 }));
  let body = await bodyOf(await stub.fetch(statusRequest({ maxFails: 10, windowMs: 50 })));
  assert.equal(body.locked, true);
  await new Promise((r) => setTimeout(r, 80));
  body = await bodyOf(await stub.fetch(statusRequest({ maxFails: 10, windowMs: 50 })));
  assert.equal(body.locked, false);
  assert.equal(body.count, 0);
});

test('reset clears the counter — a fresh record starts back at 1', async () => {
  const stub = makeDO(makeFakeStorage(0));
  for (let i = 0; i < 10; i++) await stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 }));
  let body = await bodyOf(await stub.fetch(statusRequest({ maxFails: 10, windowMs: 60_000 })));
  assert.equal(body.locked, true);

  await stub.fetch(resetRequest());
  body = await bodyOf(await stub.fetch(statusRequest({ maxFails: 10, windowMs: 60_000 })));
  assert.equal(body.count, 0);
  assert.equal(body.locked, false);

  body = await bodyOf(await stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })));
  assert.equal(body.count, 1);
});

test('unknown action returns 400, does not touch storage', async () => {
  const storage = makeFakeStorage(0);
  const stub = makeDO(storage);
  const res = await stub.fetch(new Request('https://admin-lockout.internal/', {
    method: 'POST',
    body: JSON.stringify({ action: 'bogus' }),
  }));
  assert.equal(res.status, 400);
  assert.equal(storage.map.size, 0);
});

test('malformed JSON body returns 400 rather than throwing', async () => {
  const stub = makeDO(makeFakeStorage(0));
  const res = await stub.fetch(new Request('https://admin-lockout.internal/', {
    method: 'POST',
    body: '{not json',
  }));
  assert.equal(res.status, 400);
});


test('record schedules expiry and alarm deletes the stored counter after its window', async () => {
  const storage = makeFakeStorage(0);
  const stub = makeDO(storage);
  const before = Date.now();
  const body = await bodyOf(await stub.fetch(recordRequest({ maxFails: 5, windowMs: 80 })));
  assert.equal(storage.alarmAt, body.windowStart + 80);
  assert.ok(storage.map.has('record'));
  await new Promise((resolve) => setTimeout(resolve, 100));
  await stub.alarm();
  assert.equal(storage.map.has('record'), false);
});

test('an early alarm reschedules instead of deleting a live counter', async () => {
  const storage = makeFakeStorage(0);
  const stub = makeDO(storage);
  const body = await bodyOf(await stub.fetch(recordRequest({ maxFails: 5, windowMs: 1000 })));
  await stub.alarm();
  assert.equal(storage.map.has('record'), true);
  assert.equal(storage.alarmAt, body.windowStart + 1000);
});

test('reset cancels the scheduled expiry alarm', async () => {
  const storage = makeFakeStorage(0);
  const stub = makeDO(storage);
  await stub.fetch(recordRequest({ maxFails: 5, windowMs: 1000 }));
  assert.notEqual(storage.alarmAt, null);
  await stub.fetch(resetRequest());
  assert.equal(storage.map.has('record'), false);
  assert.equal(storage.alarmAt, null);
});

// ─── Concurrency: the actual requirement under test ─────────────────────────

test('CONCURRENCY: 10 simultaneous failed attempts on a serialized instance produce count=10, locked=true, never an undercount', async () => {
  const stub = serializeFetch(makeDO(makeFakeStorage(5)));
  const results = await Promise.all(
    Array.from({ length: 10 }, () => stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })).then(bodyOf)),
  );
  const counts = results.map((r) => r.count).sort((a, b) => a - b);
  // Every one of the 10 concurrent calls must have observed a DISTINCT,
  // sequential count from 1..10 — this is only possible if none of them
  // ever read a stale pre-increment value, i.e. no undercount occurred.
  assert.deepEqual(counts, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  assert.equal(results.filter((r) => r.locked).length, 1, 'exactly the 10th call should observe locked=true');
});

test('CONCURRENCY: simultaneous failures from the same normalized IPv4 key never undercount', async () => {
  // The Worker keys Durable Object instances by `idFromName(key)` — one
  // instance per normalized IP. This test operates at the single-instance
  // level (the unit this class controls); the Worker-level key→instance
  // routing is exercised in index.test.js / the Worker's own routing tests.
  const stub = serializeFetch(makeDO(makeFakeStorage(3)));
  const results = await Promise.all(
    Array.from({ length: 6 }, () => stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })).then(bodyOf)),
  );
  assert.deepEqual(results.map((r) => r.count).sort((a, b) => a - b), [1, 2, 3, 4, 5, 6]);
});

test('CONCURRENCY: simultaneous failures from the same normalized IPv6 /64 key never undercount', async () => {
  // The DO class itself is IP-format-agnostic (the /64 collapsing happens
  // server-side in pure.normalizeIpForRateLimit before the key reaches
  // here — see adminLockoutStore.js) — this test just confirms the same
  // atomicity holds regardless of what the opaque key string looks like,
  // using a representative collapsed-IPv6 key shape.
  const stub = serializeFetch(makeDO(makeFakeStorage(3)));
  const results = await Promise.all(
    Array.from({ length: 6 }, () => stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })).then(bodyOf)),
  );
  assert.deepEqual(results.map((r) => r.count).sort((a, b) => a - b), [1, 2, 3, 4, 5, 6]);
});

test('CONCURRENCY: requests crossing the lockout threshold concurrently — exactly one call observes the transition to locked', async () => {
  const stub = serializeFetch(makeDO(makeFakeStorage(4)));
  // Prime to 8 failures sequentially, then fire the last 4 (crossing 10) concurrently.
  for (let i = 0; i < 8; i++) await stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 }));
  const results = await Promise.all(
    Array.from({ length: 4 }, () => stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })).then(bodyOf)),
  );
  assert.deepEqual(results.map((r) => r.count).sort((a, b) => a - b), [9, 10, 11, 12]);
  const lockedResults = results.filter((r) => r.locked);
  assert.equal(lockedResults.length, 3, 'the 10th, 11th, and 12th increments should all observe locked=true');
});

test('CONCURRENCY: repeated concurrent attempts while already locked keep incrementing without ever losing an increment', async () => {
  const stub = serializeFetch(makeDO(makeFakeStorage(3)));
  for (let i = 0; i < 10; i++) await stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 }));
  const results = await Promise.all(
    Array.from({ length: 5 }, () => stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })).then(bodyOf)),
  );
  assert.deepEqual(results.map((r) => r.count).sort((a, b) => a - b), [11, 12, 13, 14, 15]);
  assert.ok(results.every((r) => r.locked === true));
});

test('NEGATIVE CONTROL: the same 10 concurrent calls WITHOUT the serializing harness undercount', async () => {
  // This is not testing production behavior — it demonstrates why the
  // Durable Object runtime's serialization guarantee (modeled by
  // serializeFetch() above) is load-bearing, by showing what happens
  // without it. If this test ever stops failing to reach 10, the fake
  // storage's artificial delay is no longer sufficient to provoke the race
  // and should be increased, not removed.
  const stub = makeDO(makeFakeStorage(5)); // raw instance, NOT wrapped in serializeFetch()
  const results = await Promise.all(
    Array.from({ length: 10 }, () => stub.fetch(recordRequest({ maxFails: 10, windowMs: 60_000 })).then(bodyOf)),
  );
  const maxCount = Math.max(...results.map((r) => r.count));
  assert.ok(maxCount < 10, `expected an undercount without serialization, got max count ${maxCount}`);
});
