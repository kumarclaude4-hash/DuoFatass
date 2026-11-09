"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { createAuthRateLimitStore, durableKey } = require("./authRateLimitStore");

test("durable keys are namespaced, stable, and do not expose raw UIDs", () => {
  const key = durableKey("firebase-user-123", "mediaToken");
  assert.match(key, /^duoshield:authrate:[a-f0-9]{64}$/);
  assert.equal(key, durableKey("firebase-user-123", "mediaToken"));
  assert.notEqual(key, durableKey("firebase-user-456", "mediaToken"));
  assert.notEqual(key, durableKey("firebase-user-123", "linkPreview"));
  assert.doesNotMatch(key, /firebase-user-123/);
});

test("durable counter allows exactly the configured limit, then rejects", async () => {
  const calls = [];
  const store = createAuthRateLimitStore({
    windowMs: 60_000,
    client: {
      async recordFailure(key, options) {
        calls.push({ key, options });
        const count = calls.length;
        return { count, locked: count >= options.maxFails };
      },
    },
  });
  assert.equal((await store.check("u1", "createChat", 2)).allowed, true);
  assert.equal((await store.check("u1", "createChat", 2)).allowed, true);
  assert.equal((await store.check("u1", "createChat", 2)).allowed, false);
  assert.equal(calls[0].options.windowMs, 60_000);
  assert.equal(calls[0].options.maxFails, 3);
  assert.equal(calls[0].key, calls[1].key);
  assert.equal(store.durableEnabled, true);
});

test("durable counters isolate users and endpoint buckets", async () => {
  const keys = [];
  const store = createAuthRateLimitStore({
    client: {
      async recordFailure(key) {
        keys.push(key);
        return { count: 1, locked: false };
      },
    },
  });
  await store.check("user-a", "mediaToken", 10);
  await store.check("user-b", "mediaToken", 10);
  await store.check("user-a", "linkPreview", 10);
  assert.equal(new Set(keys).size, 3);
});

test("local fallback enforces fixed windows and resets at the boundary", async () => {
  let time = 1000;
  const store = createAuthRateLimitStore({ now: () => time, windowMs: 1000 });
  assert.equal((await store.check("u", "e", 2)).allowed, true);
  assert.equal((await store.check("u", "e", 2)).allowed, true);
  assert.equal((await store.check("u", "e", 2)).allowed, false);
  time = 2000;
  assert.equal((await store.check("u", "e", 2)).allowed, true);
});

test("local fallback isolates users/endpoints and caps retained state", async () => {
  const store = createAuthRateLimitStore({ maxLocalEntries: 2 });
  assert.equal((await store.check("a", "x", 1)).allowed, true);
  assert.equal((await store.check("a", "x", 1)).allowed, false);
  assert.equal((await store.check("b", "x", 1)).allowed, true);
  assert.equal((await store.check("c", "x", 1)).allowed, true);
  assert.equal((await store.check("b", "x", 1)).allowed, false);
  assert.equal((await store.check("a", "x", 1)).allowed, true);
});

test("Worker failures fall back locally and report degraded durability", async () => {
  const errors = [];
  const store = createAuthRateLimitStore({
    client: { async recordFailure() { throw new Error("Worker unavailable"); } },
    onError: (op, err) => errors.push({ op, message: err.message }),
  });
  const result = await store.check("u", "e", 1);
  assert.deepEqual(errors, [{ op: "record", message: "Worker unavailable" }]);
  assert.deepEqual(result, { allowed: true, durable: false, count: 1 });
});


test("production mode rejects when the shared Durable Object is not configured", async () => {
  const store = createAuthRateLimitStore({ requireDurable: true });
  await assert.rejects(
    () => store.check("u", "e", 1),
    (err) => err.code === "AUTH_RATE_LIMIT_UNAVAILABLE" && err.status === 503,
  );
});

test("production mode rejects Worker outages instead of spending a process-local allowance", async () => {
  const errors = [];
  const store = createAuthRateLimitStore({
    requireDurable: true,
    client: { async recordFailure() { throw new Error("private-worker-detail"); } },
    onError: (op, err) => errors.push({ op, message: err.message }),
  });
  await assert.rejects(
    () => store.check("u", "e", 1),
    (err) => err.code === "AUTH_RATE_LIMIT_UNAVAILABLE"
      && err.status === 503
      && err.message === "Shared authenticated API rate limiter is unavailable",
  );
  assert.deepEqual(errors, [{ op: "record", message: "private-worker-detail" }]);
});
