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
const MINT_ROUTE_SOURCE = fs.readFileSync(path.join(__dirname, "mintTokenRoute.js"), "utf8");
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

test("production mintToken endpoint uses the tested handler and keeps proof-of-possession before claim", () => {
  assert.ok(SOURCE.includes('const { createMintTokenRouteHandler } = require("./lib/mintTokenRoute");'));
  const setup = section(
    SOURCE,
    "const handleMintTokenRoute = createMintTokenRouteHandler({",
    "const server = http.createServer"
  );
  for (const dependency of [
    "collectBody", "getClientIp", "checkIpRateLimit", "mintCooldown",
    "mintChallengeStore", "verifyMintTokenSignature", "sha256hex",
    "db,", "FieldValue,", "inviteStore,", "invite,", "claimIdentityInTransaction,",
    "auditAdminEventInTransaction,", "createCustomToken:"
  ]) {
    assert.ok(setup.includes(dependency), "production mintToken handler missing " + dependency);
  }
  const dispatch = section(
    SOURCE,
    "const server = http.createServer",
    "// ── POST /mintChallenge"
  );
  assert.ok(dispatch.includes("if (handleMintTokenRoute(req, res)) return;"),
    "production HTTP server must dispatch to the tested mintToken route");

  assert.ok(MINT_ROUTE_SOURCE.includes('req.method !== "POST" || req.url !== "/mintToken"'));
  assert.ok(MINT_ROUTE_SOURCE.includes("mintChallengeStore.consume(userId, nonce)"));
  assert.ok(MINT_ROUTE_SOURCE.includes("verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex: nonce, signatureHex })"));
  assert.ok(MINT_ROUTE_SOURCE.includes("db.runTransaction((tx) => claimIdentityInTransaction({"));
  assert.ok(MINT_ROUTE_SOURCE.includes("const token = await createCustomToken(userId)"));
  assert.ok(MINT_ROUTE_SOURCE.includes("releaseCooldown();"));
  assert.ok(MINT_ROUTE_SOURCE.indexOf("mintChallengeStore.consume(userId, nonce)") <
    MINT_ROUTE_SOURCE.indexOf("verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex: nonce, signatureHex })"));
  assert.ok(MINT_ROUTE_SOURCE.indexOf("verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex: nonce, signatureHex })") <
    MINT_ROUTE_SOURCE.indexOf("db.runTransaction((tx) => claimIdentityInTransaction({"));
  assert.ok(MINT_ROUTE_SOURCE.indexOf("db.runTransaction((tx) => claimIdentityInTransaction({") <
    MINT_ROUTE_SOURCE.indexOf("const token = await createCustomToken(userId)"));
  assert.ok(MINT_ROUTE_SOURCE.includes('res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" })'));
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
