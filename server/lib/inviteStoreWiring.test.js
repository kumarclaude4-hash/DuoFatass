"use strict";

// Structural guard for the HTTP-to-store wiring. The route integration tests
// exercise the shared invite router over real HTTP; this test prevents the
// production server from bypassing that tested router or duplicating a second
// invite lifecycle implementation.

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");
const ROUTE_SOURCE = fs.readFileSync(path.join(__dirname, "inviteRoutes.js"), "utf8");
const CLAIM_SOURCE = fs.readFileSync(path.join(__dirname, "identityClaim.js"), "utf8");

function section(source, start, end) {
  const from = source.indexOf(start);
  assert.ok(from >= 0, "missing source anchor: " + start);
  const to = source.indexOf(end, from + start.length);
  assert.ok(to > from, "missing end anchor: " + end);
  return source.slice(from, to);
}

test("production creates inviteStore with required audit and pseudonymization dependencies", () => {
  assert.ok(SOURCE.includes('const { createInviteStore } = require("./lib/inviteStore");'));
  assert.ok(SOURCE.includes('const { claimIdentityInTransaction } = require("./lib/identityClaim");'));
  const setup = section(SOURCE, "const inviteStore = createInviteStore({", "// Constant-time comparison");
  assert.ok(setup.includes("firestore: db"));
  assert.ok(setup.includes("stageAudit: auditAdminEventInTransaction"));
  assert.ok(setup.includes("tagUserId: uidTag"));
  assert.match(SOURCE, /function auditAdminEventInTransaction[\s\S]*?tx\.create\(ref, buildAdminAuditRecord\(action, req, extra\)\)/);
});

test("production HTTP server dispatches invite endpoints through the shared tested router", () => {
  assert.ok(SOURCE.includes('const { createInviteRouteHandler } = require("./lib/inviteRoutes");'));
  const setup = section(
    SOURCE,
    "const handleInviteRoute = createInviteRouteHandler({",
    "const server = http.createServer"
  );
  for (const dependency of [
    "inviteStore", "invite", "db", "requireAdminAuth", "requireAdminAuthThenBody",
    "checkWaitlistPollRateLimit", "getClientIp", "readBody", "sendServerError",
  ]) {
    assert.match(setup, new RegExp("\\b" + dependency + "\\b"), "missing production dependency: " + dependency);
  }

  const dispatch = section(
    SOURCE,
    "const server = http.createServer",
    "// ── POST /mintChallenge"
  );
  assert.ok(dispatch.includes("if (handleInviteRoute(req, res)) return;"),
    "invite routes must be dispatched before later route branches");
  assert.equal(dispatch.includes('req.url === "/invite/validate"'), false,
    "validation must not retain a parallel inline production handler");
  assert.equal(dispatch.includes('req.url === "/admin/api/invites/create"'), false,
    "creation must not retain a parallel inline production handler");
  assert.equal(dispatch.includes('req.url === "/admin/api/invites/revoke"'), false,
    "revocation must not retain a parallel inline production handler");
});

test("shared invite router authenticates admin routes and delegates every lifecycle operation", () => {
  assert.ok(ROUTE_SOURCE.includes('req.method === "GET" && pathname === "/admin/api/invites"'));
  assert.ok(ROUTE_SOURCE.includes('req.method === "POST" && pathname === "/admin/api/invites/create"'));
  assert.ok(ROUTE_SOURCE.includes('req.method === "POST" && pathname === "/admin/api/invites/revoke"'));
  assert.ok(ROUTE_SOURCE.includes('req.method === "POST" && pathname === "/invite/validate"'));
  assert.ok(ROUTE_SOURCE.includes("requireAdminAuth(req, res)"));
  assert.ok(ROUTE_SOURCE.includes("requireAdminAuthThenBody(req, res"));
  assert.ok(ROUTE_SOURCE.includes("inviteStore.list({"));
  assert.ok(ROUTE_SOURCE.includes("inviteStore.create(parsed, req)"));
  assert.ok(ROUTE_SOURCE.includes("inviteStore.revoke("));
  assert.ok(ROUTE_SOURCE.includes("inviteStore.isActive(token)"));
  assert.equal(ROUTE_SOURCE.includes('.where("tokenHash"'), false,
    "HTTP handlers must not maintain a second invite persistence implementation");
});

test("mintToken preserves authentication and proof-of-possession before the identity transaction", () => {
  const mint = section(
    SOURCE,
    'if (req.method === "POST" && req.url === "/mintToken")',
    "// ── POST /migrateUid"
  );
  assert.ok(mint.includes("verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex: nonce, signatureHex })"),
    "the mandatory nonce/signature proof-of-possession must remain in front of the transaction");
  assert.ok(mint.includes("claimIdentityInTransaction({"));
  assert.ok(mint.includes("db.runTransaction((tx) => claimIdentityInTransaction("));
  assert.ok(mint.includes("inviteStore,"));
  assert.ok(mint.includes("auditAdminEventInTransaction,"));
  assert.ok(mint.includes("tagUserId: uidTag"));
  assert.equal(mint.includes("inviteStore.redeemInTransaction("), false,
    "the route must not duplicate the transactional identity/invite implementation");
  assert.equal(mint.includes('.where("tokenHash"'), false,
    "the route must not bypass the store's one-time-use transaction");
});

test("identity claim helper owns the lock, one-time invite, legacy and identity writes in one caller transaction", () => {
  assert.ok(CLAIM_SOURCE.includes("await tx.get(lockRef)"));
  assert.ok(CLAIM_SOURCE.includes("await tx.get(identityRef)"));
  assert.ok(CLAIM_SOURCE.includes("await inviteStore.redeemInTransaction(tx, userId, normalizedInvite, requestContext)"));
  assert.ok(CLAIM_SOURCE.includes("await tx.get(legacyRef)"));
  assert.ok(CLAIM_SOURCE.includes('auditAdminEventInTransaction(tx, "invite_redeemed"'));
  assert.ok(CLAIM_SOURCE.includes("tx.update(legacyRef"));
  assert.ok(CLAIM_SOURCE.includes("tx.set(identityRef"));
  assert.ok(CLAIM_SOURCE.includes("pure.timingSafeEqualHex(storedHash, incomingHash)"));
});

test("invite lifecycle audit writes are transaction-coupled, not fire-and-forget", () => {
  const audit = section(
    SOURCE,
    "function auditAdminEventInTransaction(tx, action, req, extra = {})",
    "// Constant-time comparison"
  );
  assert.ok(audit.includes("tx.create("));
  assert.ok(CLAIM_SOURCE.includes("await inviteStore.redeemInTransaction"),
    "modern invite redemption must call the audited store inside the caller's transaction");
  assert.ok(CLAIM_SOURCE.includes('auditAdminEventInTransaction(tx, "invite_redeemed"'),
    "legacy waitlist redemption must also audit inside the identity transaction");
});
