"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SERVER_SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");
const HELPER_SOURCE = fs.readFileSync(path.join(__dirname, "duressLock.js"), "utf8");
const WORKER_SOURCE = fs.readFileSync(
  path.join(__dirname, "..", "..", "app", "src", "main", "java", "com", "duoshield",
    "app", "security", "AccountLockWorker.java"),
  "utf8"
);

function section(source, start, end) {
  const from = source.indexOf(start);
  assert.ok(from >= 0, "missing source anchor: " + start);
  const to = source.indexOf(end, from + start.length);
  assert.ok(to > from, "missing source boundary: " + end);
  return source.slice(from, to);
}

test("POST /duress-lock delegates nonce and account-lock writes to the shared transaction helper", () => {
  assert.ok(SERVER_SOURCE.includes('const { consumeDuressLockNonceInTransaction } = require("./lib/duressLock");'));
  const route = section(
    SERVER_SOURCE,
    'if (req.method === "POST" && req.url === "/duress-lock")',
    "// ── GET /admin"
  );
  assert.ok(route.includes("await db.runTransaction((tx) =>"));
  assert.ok(route.includes("consumeDuressLockNonceInTransaction({"));
  assert.ok(route.includes("if (lockOutcome.status)"));
  assert.ok(route.includes("res.writeHead(lockOutcome.status"));
  assert.ok(route.includes("const uid = lockOutcome.uid"));
  assert.ok(route.includes("admin.auth().revokeRefreshTokens(uid)"));
  assert.ok(route.includes('res.writeHead(200, { "Content-Type": "application/json" })'));
  assert.equal(route.includes("tx.delete(nonceRef)"), false,
    "the route must not maintain a second nonce-consumption implementation");
});

test("the transaction helper retains consumed nonces for idempotent confirmation until TTL", () => {
  assert.ok(HELPER_SOURCE.includes("tx.update(nonceRef, { consumedAt: FieldValue.serverTimestamp() })"));
  assert.ok(HELPER_SOURCE.includes("if (nonceData.consumedAt)"));
  assert.ok(HELPER_SOURCE.includes('lockData.lockedBy === "duress"'));
  assert.ok(HELPER_SOURCE.includes("return { uid, alreadyLocked: true, lockStillActive }"));
  assert.ok(HELPER_SOURCE.includes("const lockStillActive = lockData.locked === true && lockData.lockedBy === \"duress\""));
  assert.ok(HELPER_SOURCE.includes("tx.delete(nonceRef)"));
  assert.ok(HELPER_SOURCE.includes('return { status: 401, message: "Nonce expired" }'));
});

test("idempotent replay skips refresh-token revocation after an admin unfreeze", () => {
  const route = section(
    SERVER_SOURCE,
    'if (req.method === "POST" && req.url === "/duress-lock")',
    "// ── GET /admin"
  );
  assert.ok(route.includes("lockOutcome.alreadyLocked !== true || lockOutcome.lockStillActive === true"));
  assert.ok(route.includes("admin.auth().revokeRefreshTokens(uid)"));
  assert.ok(route.includes("lockStillActive"));
});

test("worker classifies only 200/204 as confirmed and preserves intent on permanent failure", () => {
  assert.ok(WORKER_SOURCE.includes("AccountLockHttpPolicy.classify(code)"));
  const confirmed = section(
    WORKER_SOURCE,
    "if (disposition == AccountLockHttpPolicy.Disposition.LOCK_CONFIRMED)",
    "if (disposition == AccountLockHttpPolicy.Disposition.PERMANENT_FAILURE)"
  );
  assert.ok(confirmed.includes("PendingLockStore.clearLockIntent"));
  assert.ok(confirmed.includes("return Result.success()"));

  const failed = section(
    WORKER_SOURCE,
    "if (disposition == AccountLockHttpPolicy.Disposition.PERMANENT_FAILURE)",
    "return retryOrGiveUp"
  );
  assert.ok(failed.includes("return Result.failure()"));
  assert.equal(failed.includes("PendingLockStore.clearLockIntent"), false);
  assert.ok(WORKER_SOURCE.includes("Missing nonce — account lock NOT confirmed"));
  assert.ok(WORKER_SOURCE.includes("PUSH_SERVER_URL not configured — account lock NOT confirmed"));
});

test("retry cap remains bounded and failure leaves the durable lock intent intact", () => {
  assert.match(WORKER_SOURCE, /MAX_ATTEMPTS\s*=\s*7/);
  const retry = section(WORKER_SOURCE, "private Result retryOrGiveUp", "\n    }\n}");
  assert.ok(retry.includes("return Result.failure()"));
  assert.ok(retry.includes("return Result.retry()"));
  assert.equal(retry.includes("PendingLockStore.clearLockIntent"), false);
});
