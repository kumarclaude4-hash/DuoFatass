"use strict";

// Structural guard for the HTTP-to-store wiring. The emulator integration suite
// executes inviteStore's transaction behavior; this file ensures production
// routes actually call that tested implementation instead of reintroducing a
// parallel, untested Firestore implementation in index.js.

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");

function section(start, end) {
  const from = SOURCE.indexOf(start);
  assert.ok(from >= 0, "missing source anchor: " + start);
  const to = SOURCE.indexOf(end, from + start.length);
  assert.ok(to > from, "missing end anchor: " + end);
  return SOURCE.slice(from, to);
}

test("production creates inviteStore with required audit and pseudonymization dependencies", () => {
  assert.ok(SOURCE.includes('const { createInviteStore } = require("./lib/inviteStore");'));
  const setup = section("const inviteStore = createInviteStore({", "// Constant-time comparison");
  assert.ok(setup.includes("firestore: db"));
  assert.ok(setup.includes("stageAudit: auditAdminEventInTransaction"));
  assert.ok(setup.includes("tagUserId: uidTag"));
  assert.ok(SOURCE.includes("function auditAdminEventInTransaction(tx, action, req, extra = {})"));
  assert.match(SOURCE, /function auditAdminEventInTransaction[\s\S]*?tx\.create\(ref, buildAdminAuditRecord\(action, req, extra\)\)/);
});

test("admin invite listing is authenticated and delegates filtering/pagination to inviteStore", () => {
  const route = section(
    'if (req.method === "GET" && (req.url || "").split("?")[0] === "/admin/api/invites")',
    'if (req.method === "POST" && req.url === "/admin/api/invites/create")'
  );
  assert.ok(route.includes("requireAdminAuth(req, res)"), "listing must authenticate before reading invite data");
  assert.ok(route.includes("inviteStore.list({"));
  assert.ok(route.includes("status: requestUrl.searchParams.get(\"status\")"));
  assert.ok(route.includes("cursor: requestUrl.searchParams.get(\"cursor\")"));
  assert.equal(route.includes('.where("tokenHash"'), false,
    "the HTTP handler must not maintain a second invite-list implementation");
});

test("create and revoke routes authenticate before body parsing and delegate to the store", () => {
  const createRoute = section(
    'if (req.method === "POST" && req.url === "/admin/api/invites/create")',
    'if (req.method === "POST" && req.url === "/admin/api/invites/revoke")'
  );
  assert.ok(createRoute.includes("requireAdminAuthThenBody(req, res"));
  assert.ok(createRoute.includes("inviteStore.create(parsed, req)"));
  assert.ok(createRoute.includes('"Cache-Control": "no-store"'));

  const revokeRoute = section(
    'if (req.method === "POST" && req.url === "/admin/api/invites/revoke")',
    "// ── GET /admin/api/locked"
  );
  assert.ok(revokeRoute.includes("requireAdminAuthThenBody(req, res"));
  assert.ok(revokeRoute.includes("inviteStore.revoke("));
  assert.ok(revokeRoute.includes("outcome.status === \"conflict\""));
});

test("modern invite redemption and preflight call the same transactional store", () => {
  const mint = section(
    'requestPath === "/mintToken"',
    'requestPath === "/mintChallenge"'
  );
  assert.ok(mint.includes("inviteStore.redeemInTransaction(tx, userId, normalizedInvite, req)"));
  assert.equal(mint.includes('.where("tokenHash"'), false,
    "mintToken must not bypass the store's one-time-use transaction");
  assert.ok(mint.includes('auditAdminEventInTransaction(tx, "invite_redeemed"'),
    "legacy waitlist compatibility redemption must also audit inside the claim transaction");

  const validate = section(
    'req.method === "POST" && req.url === "/invite/validate"',
    'req.method === "POST" && req.url === "/migrateUid"'
  );
  assert.ok(validate.includes("inviteStore.isActive(token)"));
});

test("invite lifecycle audit writes are transaction-coupled, not fire-and-forget", () => {
  const audit = section(
    "function auditAdminEventInTransaction(tx, action, req, extra = {})",
    "// Constant-time comparison"
  );
  assert.ok(audit.includes("tx.create("));
  const modernRedemption = section(
    "await inviteStore.redeemInTransaction",
    "// First claim — atomically write the identity binding"
  );
  assert.ok(modernRedemption.includes("await inviteStore.redeemInTransaction"));
  assert.equal(modernRedemption.includes('auditAdminEvent("invite_redeemed"'), false,
    "no asynchronous post-commit audit write may substitute for transactional audit");
});
