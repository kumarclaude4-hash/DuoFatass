"use strict";

// Unit tests for the admin session lifecycle store (S05-M3).
// Run with: npm test   (inside server/) — uses the Node built-in test runner.
//
// A fully deterministic fake clock (`now`) and a counting id generator
// (`randomId`) are injected so every scenario — including "8 hours later"
// and "idle timeout elapsed but absolute cap has not" — runs instantly and
// reproducibly, with no real waiting and no flakiness from Date.now() drift.

const test = require("node:test");
const assert = require("node:assert/strict");
const { createAdminSessionStore } = require("./adminSessionStore");

// Builds a store with a controllable clock. `clock.now` is a plain number the
// test advances directly; `clock.advance(ms)` is a small convenience wrapper.
function makeClock(startAt = 1_000_000) {
  const clock = { now: startAt };
  clock.advance = (ms) => { clock.now += ms; };
  return clock;
}

function makeStore(clock, overrides = {}) {
  let counter = 0;
  return createAdminSessionStore(Object.assign({
    idleTtlMs: 30 * 60 * 1000,      // 30 min, matches production default
    absoluteTtlMs: 8 * 60 * 60 * 1000, // 8h, matches production default
    now: () => clock.now,
    randomId: () => `session-${++counter}`,
  }, overrides));
}

const CTX = { ip: "iptag-abc123", userAgent: "duoshield-admin-e2e/1.0" };

// ── 1. Normal authenticated session ───────────────────────────────────────

test("1. a freshly created session validates as valid with matching context", () => {
  const clock = makeClock();
  const store = makeStore(clock);
  const sessionId = store.create(CTX);
  const result = store.validate(sessionId, CTX);
  assert.deepEqual(result, { valid: true });
});

test("1b. an unknown session id is rejected as not_found", () => {
  const clock = makeClock();
  const store = makeStore(clock);
  const result = store.validate("never-issued", CTX);
  assert.equal(result.valid, false);
  assert.equal(result.reason, "not_found");
});

// ── 2. Refresh before expiry ───────────────────────────────────────────────

test("2. validating with refresh (default) before idle expiry slides expiresAt forward", () => {
  const clock = makeClock();
  const store = makeStore(clock, { idleTtlMs: 1000, absoluteTtlMs: 100_000 });
  const sessionId = store.create(CTX);

  clock.advance(900); // just under the 1000ms idle TTL
  assert.equal(store.validate(sessionId, CTX).valid, true, "should still be valid before idle expiry");

  // Without the refresh that just happened, the ORIGINAL expiresAt (1000ms
  // after creation) would already be behind us here (900 + 900 > 1000).
  // The fact that it is still valid proves the previous validate() call
  // actually slid expiresAt forward rather than being a no-op read.
  clock.advance(900);
  assert.equal(
    store.validate(sessionId, CTX).valid,
    true,
    "refreshing before expiry should keep the session alive past its original idle TTL"
  );
});

test("2b. validating past idle expiry without an intervening refresh is rejected and deletes the session", () => {
  const clock = makeClock();
  const store = makeStore(clock, { idleTtlMs: 1000, absoluteTtlMs: 100_000 });
  const sessionId = store.create(CTX);

  clock.advance(1001); // past the 1000ms idle TTL, no refresh happened in between
  const result = store.validate(sessionId, CTX);
  assert.equal(result.valid, false);
  assert.equal(result.reason, "idle_expired");

  // The expired record must actually be gone, not just reported invalid once.
  assert.equal(store.size(), 0, "an idle-expired session must be deleted, not left lingering");
});

// ── 3. Refresh cannot extend beyond absolute lifetime ─────────────────────

test("3. continuous activity refreshes expiresAt but never past absoluteExpiresAt", () => {
  const clock = makeClock();
  const idleTtlMs = 1000;
  const absoluteTtlMs = 2500;
  const store = makeStore(clock, { idleTtlMs, absoluteTtlMs });
  const sessionId = store.create(CTX);

  // Poll well inside the idle window, repeatedly, well past what continuous
  // 1000ms refreshes would normally allow if uncapped — this is exactly the
  // pre-fix defect (S05-M3 finding #1: "every touch extends the session
  // another 30 minutes... indefinitely").
  for (let i = 0; i < 3; i++) {
    clock.advance(500);
    assert.equal(store.validate(sessionId, CTX).valid, true, `poll #${i} inside the window should stay valid`);
  }
  // We're now at t=1500 (three 500ms advances). idleTtlMs alone would put
  // expiresAt at 1500+1000=2500 — which happens to equal absoluteExpiresAt
  // here, so the cap is already binding. Advance one idle-tick past that,
  // and the ABSOLUTE ceiling — not idle timeout — must be what rejects it.
  clock.advance(1000); // now at t=2500, exactly the absolute ceiling
  const atCeiling = store.validate(sessionId, CTX);
  assert.equal(atCeiling.valid, false, "the absolute ceiling must reject even a request that would pass the idle check alone");
  assert.equal(atCeiling.reason, "absolute_expired");
});

test("3b. a session refreshed while inside the idle window is still capped at the absolute ceiling", () => {
  const clock = makeClock(1_000_000);
  const store = makeStore(clock, { idleTtlMs: 10_000, absoluteTtlMs: 12_000 });
  const sessionId = store.create(CTX);
  // createdAt=1_000_000, initial expiresAt=1_010_000, absoluteExpiresAt=1_012_000.

  clock.advance(5_000); // t=1_005_000 — inside BOTH the idle window and the absolute window
  assert.equal(store.validate(sessionId, CTX).valid, true);
  // If the refresh naively applied the full idle TTL, expiresAt would now be
  // 1_005_000 + 10_000 = 1_015_000 — past the 1_012_000 absolute ceiling.
  // Assert it was capped instead, using the diagnostic accessor.
  const rec = store._get(sessionId);
  assert.equal(rec.expiresAt, 1_012_000, "expiresAt must be capped at absoluteExpiresAt, not idleTtlMs past now()");

  clock.advance(7_001); // t=1_012_001 — past the capped ceiling
  const result = store.validate(sessionId, CTX);
  assert.equal(result.valid, false);
  assert.equal(result.reason, "absolute_expired");
});

// ── 4. IP/UA mismatch handling ──────────────────────────────────────────────

test("4. a request from a different ip tag is NOT rejected (S07-H2: IP is not in the reject path)", () => {
  const clock = makeClock();
  const store = makeStore(clock);
  const sessionId = store.create(CTX);

  // A phone switching WiFi→LTE, CGNAT reassignment, or an IPv4/IPv6 flip all
  // change the client address mid-session. validate() must not care — the IP
  // is recorded in the audit trail elsewhere, never enforced here.
  const otherIpCtx = { ip: "iptag-different", userAgent: CTX.userAgent };
  assert.equal(store.validate(sessionId, otherIpCtx).valid, true);
});

test("4b. (S05-M3) the DEFAULT store does NOT reject a changed User-Agent — the WebView fetch-vs-navigation bug", () => {
  const clock = makeClock();
  const store = makeStore(clock); // bindUserAgent defaults to false
  const sessionId = store.create(CTX);

  // An Android WebView sends the full "...; wv) ... Version/4.0..." UA on the
  // navigation that logs in, then a trimmed UA on the fetch() that loads data.
  // With binding off, that second request must still validate — otherwise the
  // operator is bounced to the gate half a second after a successful login.
  const webviewFetchCtx = { ip: CTX.ip, userAgent: "some-other-browser/9.0" };
  assert.equal(store.validate(sessionId, webviewFetchCtx).valid, true);
});

test("4c-ua. when bindUserAgent is opt-in, a different User-Agent is rejected as ua_mismatch without deleting the session", () => {
  const clock = makeClock();
  const store = makeStore(clock, { bindUserAgent: true });
  const sessionId = store.create(CTX);

  const spoofedUaCtx = { ip: CTX.ip, userAgent: "some-other-browser/9.0" };
  const result = store.validate(sessionId, spoofedUaCtx);
  assert.equal(result.valid, false);
  assert.equal(result.reason, "ua_mismatch");
  assert.equal(store.validate(sessionId, CTX).valid, true, "the legitimate context must still validate after a mismatched attempt");
});

test("4c. binding checks can be skipped per-field by omitting them from ctx (used by isolated TTL tests)", () => {
  const clock = makeClock();
  const store = makeStore(clock);
  const sessionId = store.create(CTX);
  // No ip/userAgent supplied at all -> binding is not evaluated, only TTL.
  assert.equal(store.validate(sessionId, {}).valid, true);
});

// ── 5. Revoked session rejection ────────────────────────────────────────────

test("5. a revoked session is rejected as not_found on every subsequent validate", () => {
  const clock = makeClock();
  const store = makeStore(clock);
  const sessionId = store.create(CTX);
  assert.equal(store.validate(sessionId, CTX).valid, true);

  assert.equal(store.revoke(sessionId), true, "revoke() should report the session existed");
  const result = store.validate(sessionId, CTX);
  assert.equal(result.valid, false);
  assert.equal(result.reason, "not_found");

  assert.equal(store.revoke(sessionId), false, "revoking an already-revoked session reports false, not a throw");
});

// ── 6. Bulk revocation invalidating all targeted sessions ──────────────────

test("6. revokeAll() invalidates every active session and reports the count revoked", () => {
  const clock = makeClock();
  const store = makeStore(clock);
  const ids = [store.create(CTX), store.create(CTX), store.create({ ip: "iptag-other", userAgent: "other-ua" })];
  assert.equal(store.size(), 3);

  const revokedCount = store.revokeAll();
  assert.equal(revokedCount, 3, "revokeAll() must report exactly how many sessions it cleared");
  assert.equal(store.size(), 0);

  for (const id of ids) {
    const result = store.validate(id, CTX);
    assert.equal(result.valid, false);
    assert.equal(result.reason, "not_found", `session ${id} must be gone after revokeAll()`);
  }
});

test("6b. revokeAll() on an empty store is a safe no-op that reports 0", () => {
  const clock = makeClock();
  const store = makeStore(clock);
  assert.equal(store.revokeAll(), 0);
});

// ── 7. Legitimate session continuity (idle refresh vs. unauthenticated read-only checks) ──

test("7. an unauthenticated, read-only check ({refresh:false}) does not extend the idle timeout", () => {
  // Models S05-M3 finding #2: GET /admin used to call the session check with
  // its default (refreshing) behavior purely to decide which view to render,
  // so a browser prefetch/restored tab/background reload kept a session
  // alive with no real operator activity. index.js now passes
  // {refresh:false} from that specific call site.
  const clock = makeClock();
  const store = makeStore(clock, { idleTtlMs: 1000, absoluteTtlMs: 100_000 });
  const sessionId = store.create(CTX);

  clock.advance(900);
  // A GET /admin-style check: read-only, must not slide expiresAt.
  assert.equal(store.validate(sessionId, CTX, { refresh: false }).valid, true);

  clock.advance(200); // now at t=1100, past the ORIGINAL 1000ms idle TTL
  const result = store.validate(sessionId, CTX, { refresh: false });
  assert.equal(
    result.valid,
    false,
    "a refresh:false check must not have kept the session alive past its un-refreshed idle TTL"
  );
  assert.equal(result.reason, "idle_expired");
});

test("7b. a real authenticated call (refresh:true, the default) keeps a legitimately-active session open across many polls", () => {
  const clock = makeClock();
  const idleTtlMs = 1000;
  const absoluteTtlMs = 50_000;
  const store = makeStore(clock, { idleTtlMs, absoluteTtlMs });
  const sessionId = store.create(CTX);

  // 20 authenticated polls, each well inside the idle window, spanning far
  // longer in aggregate than idleTtlMs alone would allow without refresh —
  // this is the "actively used admin panel stays open" behavior the pre-fix
  // sliding expiry was already meant to provide, and must still work.
  for (let i = 0; i < 20; i++) {
    clock.advance(800);
    const result = store.validate(sessionId, CTX);
    assert.equal(result.valid, true, `authenticated poll #${i} should keep the legitimate session open`);
  }
  // Total elapsed: 20 * 800ms = 16000ms, far past idleTtlMs (1000ms) alone,
  // and still short of absoluteTtlMs (50000ms) — continuity preserved.
  assert.ok(clock.now - 1_000_000 < absoluteTtlMs, "sanity: still within the absolute ceiling");
});

test("7c. login -> immediate use -> logout mirrors the real request flow end to end", () => {
  // Not a claim that this exercises the live HTTP routes (that remains
  // BLOCKED in this environment, same limitation as every other admin-panel
  // behavioural test in this file's siblings) — only that the store's public
  // surface, called in the exact sequence index.js calls it in, behaves
  // correctly across a realistic login/use/logout lifecycle.
  const clock = makeClock();
  const store = makeStore(clock);

  // POST /admin/login success -> createAdminSession(req)
  const sessionId = store.create(CTX);

  // A same-origin admin/api call shortly after login -> requireAdminAuth ->
  // evaluateAdminSession(req) with the default refresh:true.
  clock.advance(5000);
  assert.equal(store.validate(sessionId, CTX).valid, true);

  // POST /admin/logout -> adminSessionStore.revoke(sessionId)
  assert.equal(store.revoke(sessionId), true);

  // Any request replaying the now-logged-out cookie must fail.
  assert.equal(store.validate(sessionId, CTX).valid, false);
});
