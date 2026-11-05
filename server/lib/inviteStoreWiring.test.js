"use strict";

// Structural guard for the HTTP-to-store wiring. The Firestore emulator suite
// executes the same identity-claim transaction helper called by /mintToken;
// these tests prevent production routes from drifting back to a parallel,
// untested Firestore implementation or weakening the proof-of-possession gate.

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");
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

test("admin invite listing is authenticated and delegates filtering/pagination to inviteStore", () => {
  const route = section(
    SOURCE,
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
    SOURCE,
    'if (req.method === "POST" && req.url === "/admin/api/invites/create")',
    'if (req.method === "POST" && req.url === "/admin/api/invites/revoke")'
  );
  assert.ok(createRoute.includes("requireAdminAuthThenBody(req, res"));
  assert.ok(createRoute.includes("inviteStore.create(parsed, req)"));
  assert.ok(createRoute.includes('"Cache-Control": "no-store"'));

  const revokeRoute = section(
    SOURCE,
    'if (req.method === "POST" && req.url === "/admin/api/invites/revoke")',
    "// ── GET /admin/api/locked"
  );
  assert.ok(revokeRoute.includes("requireAdminAuthThenBody(req, res"));
  assert.ok(revokeRoute.includes("inviteStore.revoke("));
  assert.ok(revokeRoute.includes("outcome.status === \"conflict\""));
});

test("mintToken preserves authentication and proof-of-possession before the identity transaction", () => {
  const mint = section(
    SOURCE,
    'if (req.method === "POST" && req.url === "/mintToken")',
    "// ── POST /invite/validate"
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

  const validate = section(
    SOURCE,
    'req.method === "POST" && req.url === "/invite/validate"',
    'req.method === "POST" && req.url === "/migrateUid"'
  );
  assert.ok(validate.includes("inviteStore.isActive(token)"));
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
