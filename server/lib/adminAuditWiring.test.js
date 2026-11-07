"use strict";

// ── S05-H3 wiring test ────────────────────────────────────────────────────────
//
// WHAT THIS IS, HONESTLY: a source-level structural test, not a behavioural one.
// It reads `index.js` as TEXT and asserts that the admin audit sink is actually
// called from each authentication branch. It does NOT execute those branches, so
// it cannot prove a row reaches Firestore.
//
// WHY IT EXISTS ANYWAY: the defect it guards against is exactly the one this
// program keeps producing — a fix that exists, reads correctly, and is described
// in a comment as load-bearing, but has NO CALLERS. Cluster A found
// `maintainLockCredential()` dead. Cluster B's first pass then added
// `auditAdminEvent()` with a comment claiming "login success, login failure,
// lockout and logout" were covered, while wiring it into `requireAdminAuth()`
// ONLY — so the four highest-value events in that sentence were still silently
// unrecorded. A behavioural test needs Firebase Admin credentials and a live
// Firestore; this needs neither, and it fails the moment someone adds an auth
// branch without an audit call. That trade is worth taking. Per SESSION_PROTOCOL
// §8: "'The function exists' is not evidence; wiring is."
//
// A real behavioural test of the audit write remains BLOCKED in this environment
// (no Firestore emulator / no service-account credentials) and is recorded as
// such rather than asserted.

const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const SERVER_SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");
const INVITE_STORE_SOURCE = fs.readFileSync(path.join(__dirname, "inviteStore.js"), "utf8");
const INVITE_ROUTE_SOURCE = fs.readFileSync(path.join(__dirname, "inviteRoutes.js"), "utf8");

// Every admin auth event that must leave a durable trace. The action strings are
// asserted literally so a rename that breaks a forensic query fails here.
const REQUIRED_AUDIT_ACTIONS = [
  "admin_api_blocked_locked_out", // API hit while IP is locked out
  "admin_api_unauthorized",       // API hit with no valid token or session
  "admin_login_blocked_locked_out",
  "admin_login_unconfigured",     // login attempted with no ADMIN_TOKEN deployed
  "admin_login_failed",
  "admin_login_succeeded",
  "admin_logout",
  "admin_sessions_revoked_all", // S05-M3: bulk session revocation
];

test("every admin auth event is wired to the durable audit sink", () => {
  for (const action of REQUIRED_AUDIT_ACTIONS) {
    assert.ok(
      SERVER_SOURCE.includes(`auditAdminEvent("${action}"`),
      `no auditAdminEvent("${action}") call site in index.js — this event would ` +
      `exist only in Render's rolling console logs (S05-H3)`
    );
  }
});

test("the audit sink is defined, and called more than once", () => {
  assert.ok(
    /function auditAdminEvent\s*\(/.test(SERVER_SOURCE),
    "auditAdminEvent() is not defined in index.js"
  );
  // A definition plus one call is the shape the incomplete first pass had.
  const callCount = (SERVER_SOURCE.match(/auditAdminEvent\("/g) || []).length;
  assert.ok(
    callCount >= REQUIRED_AUDIT_ACTIONS.length,
    `expected at least ${REQUIRED_AUDIT_ACTIONS.length} audit call sites, found ${callCount}`
  );
});

test("the login handler audits both outcomes, not just failure", () => {
  // Auditing only failures is a common half-fix: "nobody failed" is not the same
  // as "nobody got in", and only the success row answers the second question.
  const loginHandlerStart = SERVER_SOURCE.indexOf('requestPath === "/admin/login"');
  assert.ok(loginHandlerStart > 0, "could not locate the POST /admin/login handler");
  // Bound the slice to the handler that follows, so a match from elsewhere in
  // this 4000-line file cannot make the assertion pass spuriously.
  const loginHandlerEnd = SERVER_SOURCE.indexOf('requestPath === "/admin/logout"', loginHandlerStart);
  assert.ok(loginHandlerEnd > loginHandlerStart, "could not locate the POST /admin/logout handler");
  const loginHandler = SERVER_SOURCE.slice(loginHandlerStart, loginHandlerEnd);

  assert.ok(
    loginHandler.includes('auditAdminEvent("admin_login_succeeded"'),
    "successful admin logins are not audited"
  );
  assert.ok(
    loginHandler.includes('auditAdminEvent("admin_login_failed"'),
    "failed admin logins are not audited"
  );
});

test("the audit log is never written the live admin token", () => {
  // A durable copy of a live credential would turn the audit trail into a second
  // place to steal it from. The failure row records the supplied LENGTH only.
  const failureCall = SERVER_SOURCE.slice(
    SERVER_SOURCE.indexOf('auditAdminEvent("admin_login_failed"')
  ).slice(0, 400);
  assert.ok(
    !/\bADMIN_TOKEN\b/.test(failureCall),
    "the failed-login audit row must not include ADMIN_TOKEN"
  );
  assert.ok(
    !/suppliedToken:\s*supplied\b|token:\s*supplied\b/.test(failureCall),
    "the failed-login audit row must not include the supplied secret itself"
  );
  assert.ok(
    failureCall.includes("suppliedLength"),
    "the failed-login audit row should record the supplied length for triage"
  );
});

test("S05-M1: the audit sink pseudonymises the operator IP, never writes it raw", () => {
  // adminAuditLog has no TTL and no retention policy (unlike Render's rolling
  // console logs), so a raw IP written here is a permanent, resolvable,
  // directly-identifying record — worse than the console logging SEC-L01 was
  // written to fix, in the collection meant to be its more careful cousin.
  const sinkStart = SERVER_SOURCE.indexOf("function buildAdminAuditRecord");
  assert.ok(sinkStart > 0, "could not locate buildAdminAuditRecord()");
  const sinkEnd = SERVER_SOURCE.indexOf("\n}", sinkStart);
  const sinkBody = SERVER_SOURCE.slice(sinkStart, sinkEnd);

  assert.ok(
    /adminIp:\s*ipTag\(getClientIp\(req\)\)/.test(sinkBody),
    "buildAdminAuditRecord must write ipTag(getClientIp(req)), not the raw IP, as adminIp"
  );
  // The regression this guards against: `adminIp: getClientIp(req)` with no
  // ipTag() wrapper anywhere in the sink body.
  assert.ok(
    !/adminIp:\s*getClientIp\(req\)/.test(sinkBody),
    "buildAdminAuditRecord must not write the raw client IP as adminIp"
  );
});

test("S05-M1: no admin action writes adminAuditLog directly, bypassing the pseudonymising sink", () => {
  // The regression this guards against: auditAdminEvent() existed and
  // correctly wrapped adminIp in ipTag(), but four call sites (waitlist
  // approve/deny, account unfreeze, duress enroll/revoke) still called
  // `db.collection("adminAuditLog").add(...)` directly with a raw
  // `adminIp: getClientIp(req)` — the sink was correct, but most of its
  // callers weren't using it. "The function exists and is correct" is not
  // evidence that every write site goes through it.
  // Strip comments before checking the executable sink; comments that quote
  // the old vulnerable pattern must not be mistaken for live write call sites.
  const executableSource = SERVER_SOURCE.split("\n")
    .filter((line) => !line.trimStart().startsWith("//"))
    .join("\n");
  const allWrites = executableSource.match(/db\.collection\("adminAuditLog"\)\s*\.add\s*\(/g) || [];
  // Exactly one is legitimate: the sink's own definition. Any more means some
  // call site is writing directly instead of going through auditAdminEvent().
  assert.strictEqual(
    allWrites.length,
    1,
    `expected exactly 1 executable adminAuditLog .add() call site, found ${allWrites.length} — ` +
    `every other admin action must route through the pseudonymising sink`
  );
});

test("S05-M1: duress enrollment/revocation audit rows never carry the raw uid", () => {
  // The finding's own "most sensitive" callout: `action: "duress_enrolled",
  // uid: <raw uid>` in a permanent, no-TTL collection is a durable record of
  // exactly which account has the duress feature enabled — the single most
  // dangerous fact in this system to disclose to a coercive adversary who
  // later reaches this collection.
  for (const action of ["duress_enrolled", "duress_revoked"]) {
    const callStart = SERVER_SOURCE.indexOf(`auditAdminEvent("${action}"`);
    assert.ok(callStart > 0, `no auditAdminEvent("${action}") call site found`);
    const call = SERVER_SOURCE.slice(callStart, callStart + 200);
    assert.ok(
      /uid:\s*uidTag\(uid\)/.test(call),
      `auditAdminEvent("${action}", ...) must pass uid: uidTag(uid), not the raw uid`
    );
  }
});

test("S05-M1: waitlist requestId is never logged to stdout in the clear", () => {
  assert.ok(
    !/console\.(log|warn|error)\(`[^`]*requestId=\$\{requestId\}/.test(SERVER_SOURCE),
    "a console log line interpolates the raw requestId — use reqTag(requestId) instead"
  );
});

test("S05-H2: invite listing is bounded, paginated, and exposes only pseudonymous redemption linkage", () => {
  const routeStart = INVITE_ROUTE_SOURCE.indexOf("async function listInvites(req, res)");
  const nextRouteStart = INVITE_ROUTE_SOURCE.indexOf("function createInvite(req, res)", routeStart);
  assert.ok(routeStart >= 0 && nextRouteStart > routeStart, "shared invite list handler missing");
  const handler = INVITE_ROUTE_SOURCE.slice(routeStart, nextRouteStart);
  assert.ok(handler.includes("requireAdminAuth(req, res)"), "invite list must authenticate");
  assert.ok(handler.includes("inviteStore.list({"), "HTTP route must call the tested invite store");
  assert.ok(INVITE_STORE_SOURCE.includes('orderBy("createdAt", "desc")'), "invite scan must select newest records first");
  assert.ok(INVITE_STORE_SOURCE.includes(".limit(MAX_SCAN).get()"), "invite scan must remain bounded");
  assert.ok(INVITE_STORE_SOURCE.includes(".count().get()"), "response must include total invite count");
  assert.ok(INVITE_STORE_SOURCE.includes("invite.decodeCursor("), "store must accept an opaque pagination cursor");
  assert.ok(INVITE_STORE_SOURCE.includes("invite.encodeCursor("), "store must return a next-page cursor");
  assert.ok(INVITE_STORE_SOURCE.includes("usedByUserIdTag: data.usedByUserIdTag"), "list response must expose a pseudonymous redemption tag");
  assert.ok(INVITE_STORE_SOURCE.includes("tagUserId(userId)"), "redemption tag must use the injected UID pseudonymizer");
  assert.ok(!/usedByUserId:\s*userId/.test(INVITE_STORE_SOURCE), "new invite records must not persist raw UIDs");
  assert.ok(INVITE_STORE_SOURCE.includes("tagUserId(data.usedByUserId)"), "legacy raw UIDs must be masked before list projection");
  assert.ok(INVITE_STORE_SOURCE.includes("totalCount,"), "list response must include totalCount");
  assert.ok(INVITE_STORE_SOURCE.includes("capped: totalCount > snapshot.size"), "bounded result must disclose excluded records");
  assert.ok(INVITE_STORE_SOURCE.includes("counts,"), "list response must include bounded status counts");
  assert.ok(SERVER_SOURCE.includes('usedByTd.textContent = item.usedByUserIdTag || "—"'),
    "admin UI must render only the pseudonymous redemption tag with textContent");
  assert.ok(SERVER_SOURCE.includes("<th>Redeemed user tag</th>"), "admin UI must label the value as pseudonymous");
});

// ── S05-H2 / invite-management wiring tests ──────────────────────────────────
// The legacy waitlist approve/deny API has been replaced by operator-issued
// hashed invites. Guard the current create/revoke paths, not deleted routes.

test("S05-H2: invite creation is admin-gated, hash-only, and audited", () => {
  const routeStart = INVITE_ROUTE_SOURCE.indexOf("function createInvite(req, res)");
  const nextRouteStart = INVITE_ROUTE_SOURCE.indexOf("function revokeInvite(req, res)", routeStart);
  assert.ok(routeStart >= 0 && nextRouteStart > routeStart, "shared invite create handler missing");
  const handler = INVITE_ROUTE_SOURCE.slice(routeStart, nextRouteStart);
  assert.ok(handler.includes("requireAdminAuthThenBody(req, res"), "invite create must authenticate before reading its body");
  assert.ok(handler.includes("inviteStore.create(parsed, req)"), "HTTP handler must delegate to inviteStore");
  assert.ok(INVITE_STORE_SOURCE.includes("invite.hashInviteToken(rawToken)"), "only a token hash may be persisted");
  assert.ok(INVITE_STORE_SOURCE.includes("invite.tokenFingerprint(rawToken)"), "store must persist only the display fingerprint");
  assert.ok(INVITE_STORE_SOURCE.includes('status: "active"'), "new invites must start active");
  assert.ok(INVITE_STORE_SOURCE.includes('stageAudit(tx, "invite_created"'), "invite creation must stage audit in the transaction");
  assert.ok(INVITE_STORE_SOURCE.includes("tx.create(ref"), "invite creation must be transactional");
  assert.doesNotMatch(INVITE_STORE_SOURCE, /(?:^|\n)\s*token\s*:/m,
    "Firestore may store only tokenHash/tokenFingerprint, never the one-time raw token");
});

test("S05-H2 / S05-L4: invite revocation is admin-gated, transactional, state-checked, and audited", () => {
  const routeStart = INVITE_ROUTE_SOURCE.indexOf("function revokeInvite(req, res)");
  const nextRouteStart = INVITE_ROUTE_SOURCE.indexOf("async function validateInvite(req, res)", routeStart);
  assert.ok(routeStart >= 0 && nextRouteStart > routeStart, "shared invite revoke handler missing");
  const handler = INVITE_ROUTE_SOURCE.slice(routeStart, nextRouteStart);
  assert.ok(handler.includes("requireAdminAuthThenBody(req, res"), "invite revoke must authenticate before reading its body");
  assert.ok(handler.includes("inviteStore.revoke("), "HTTP handler must delegate to inviteStore");
  assert.ok(INVITE_STORE_SOURCE.includes("firestore.runTransaction(async (tx) =>"), "invite revoke must be transactional");
  assert.ok(INVITE_STORE_SOURCE.includes("tx.get(ref)"), "invite revoke transaction must read through tx.get");
  assert.ok(INVITE_STORE_SOURCE.includes("current !== \"active\""), "used/expired/revoked invites must not be re-revoked");
  assert.ok(INVITE_STORE_SOURCE.includes('status: "revoked"'), "revocation must persist its state");
  assert.ok(INVITE_STORE_SOURCE.includes('stageAudit(tx, "invite_revoked"'), "revocation audit must be in the transaction");
  assert.doesNotMatch(SERVER_SOURCE, /req\.url === "\/admin\/api\/waitlist\/(?:approve|deny)"/,
    "retired waitlist write routes must not silently reappear");
});

// ── S05-M3 wiring tests ──────────────────────────────────────────────────
//
// The pure absolute-lifetime/idle/binding/revoke-all LOGIC lives in
// lib/adminSessionStore.js and is exercised directly (not via source-text
// matching) in lib/adminSessionStore.test.js. These tests instead prove
// index.js is actually WIRED to that store correctly — same "wiring, not
// re-testing the logic" split S05-H2 above uses.

// S05-M3: this test previously required createAdminSession() to bind BOTH the
// pseudonymised IP tag and the User-Agent. Both of those client-context
// bindings have since been removed as enforced controls, because on real
// mobile clients they rejected legitimate operators rather than attackers:
//   - IP binding (removed first): mobile networks re-NAT mid-session, so the
//     tag changed under a session that was still perfectly valid.
//   - UA binding (S05-M3, removed here): an Android in-app WebView sends a
//     DIFFERENT User-Agent on `fetch()`/XHR subrequests than on the top-level
//     navigation, so every `/admin/api/*` call after a correct login failed
//     `ua_mismatch` → 401 → the false "Your session expired." bounce.
// The cookie's actual strength is unchanged and lives elsewhere: HMAC-signed,
// HttpOnly, Secure, SameSite=Lax, Path=/admin, short idle + absolute TTL, and
// rotation on login. UA is still CAPTURED (cheap, useful for the audit trail
// and for operators who opt into `bindUserAgent: true` on a fleet with stable
// clients), it is simply not enforced by default.
test("S05-M3: session creation captures UA context but binds neither IP nor UA by default", () => {
  const fnStart = SERVER_SOURCE.indexOf("function createAdminSession(req)");
  assert.ok(fnStart > 0, "createAdminSession(req) not found — must accept the request to capture client context");
  const fnEnd = SERVER_SOURCE.indexOf("\n}", fnStart);
  const body = SERVER_SOURCE.slice(fnStart, fnEnd);
  assert.ok(body.includes("adminSessionStore.create("), "createAdminSession must delegate to adminSessionStore.create()");
  assert.ok(
    /userAgent:\s*req\.headers\["user-agent"\]/.test(body),
    "the request's User-Agent must still be captured and passed to create()"
  );
  assert.ok(
    !/\bip:/.test(body),
    "createAdminSession must NOT bind any IP (raw or pseudonymised tag) — mobile re-NAT " +
    "made it reject valid sessions; re-adding it reintroduces that false-expiry bug"
  );

  // The opt-in default is the load-bearing half of this fix: capturing the UA
  // is harmless, but ENFORCING it by default is what bounced real logins.
  const storeStart = SERVER_SOURCE.indexOf("createAdminSessionStore({");
  assert.ok(storeStart > 0, "createAdminSessionStore({...}) construction not found in index.js");
  const storeEnd = SERVER_SOURCE.indexOf("});", storeStart);
  const storeOpts = SERVER_SOURCE.slice(storeStart, storeEnd);
  assert.ok(
    /firestore:\s*db/.test(storeOpts),
    "admin session store must use the shared Firestore database, not a process-local Map"
  );
  assert.ok(
    /bindUserAgent:\s*false/.test(storeOpts),
    "index.js must construct the store with `bindUserAgent: false` — UA binding is opt-in, " +
    "and must never be silently re-enabled (or left wired to a debug/env toggle) by default"
  );
});

test("S05-M3: GET /admin does not refresh (extend) the session it merely reads", () => {
  const routeStart = SERVER_SOURCE.indexOf('req.method === "GET" && requestPath === "/admin"');
  assert.ok(routeStart > 0, "GET /admin route not found");
  const slice = SERVER_SOURCE.slice(routeStart, routeStart + 800);
  assert.ok(
    /hasValidAdminSession\(req,\s*\{\s*refresh:\s*false\s*\}\)/.test(slice),
    "GET /admin must call hasValidAdminSession(req, { refresh: false }) — an unauthenticated view-only " +
    "request must not silently extend the session's idle timeout"
  );
});

test("S05-M3: POST /admin/api/sessions/revoke-all exists, is admin-gated, calls revokeAll, and audits", () => {
  const routeStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/sessions/revoke-all"');
  assert.ok(routeStart > 0, "no POST /admin/api/sessions/revoke-all route found — bulk revocation is missing");

  const nextRouteStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/duress/enrolled"', routeStart);
  assert.ok(nextRouteStart > routeStart, "could not bound the revoke-all handler");
  const handler = SERVER_SOURCE.slice(routeStart, nextRouteStart);

  // S05-L2: same auth-before-body wiring check as the deny test above.
  assert.ok(
    handler.includes("requireAdminAuthThenBody(req, res"),
    "revoke-all must be gated by requireAdminAuth *before* its body is read " +
    "(via requireAdminAuthThenBody), same as every other /admin/api POST route"
  );
  assert.ok(
    handler.includes("await adminSessionStore.revokeAll()"),
    "revoke-all must actually call the store's bulk-revoke, not just return ok"
  );
  assert.ok(
    handler.includes('auditAdminEvent("admin_sessions_revoked_all"'),
    "a bulk revocation must leave a durable audit trail"
  );
});

test("S05-M3: logout revokes the session in the durable store, not just deletes a cookie", () => {
  const routeStart = SERVER_SOURCE.indexOf('req.method === "POST" && requestPath === "/admin/logout"');
  assert.ok(routeStart > 0, "POST /admin/logout route not found");
  const slice = SERVER_SOURCE.slice(routeStart, routeStart + 400);
  assert.ok(
    slice.includes("await adminSessionStore.revoke(sessionId)"),
    "logout must call adminSessionStore.revoke(sessionId) so the session cannot be replayed after logout"
  );
});

test("adminAuditLog is server-only in firestore.rules", () => {
  // The audit trail is worthless if a client can read or rewrite it.
  const rules = fs.readFileSync(path.join(__dirname, "..", "..", "firestore.rules"), "utf8");
  const match = rules.match(/match\s+\/adminAuditLog\/\{[^}]*\}\s*\{([^}]*)\}/);
  assert.ok(match, "no adminAuditLog rule found in firestore.rules");
  assert.match(
    match[1],
    /allow\s+read,\s*write:\s*if\s+false/,
    "adminAuditLog must deny all client reads and writes"
  );
});

test("S05-M3: cookie authentication awaits durable validation and fails closed on Firestore errors", () => {
  const authStart = SERVER_SOURCE.indexOf("async function requireAdminAuth(req, res)");
  const authEnd = SERVER_SOURCE.indexOf("\n}\n\n// ── Global unhandled", authStart);
  assert.ok(authStart > 0 && authEnd > authStart, "requireAdminAuth body not found");
  const handler = SERVER_SOURCE.slice(authStart, authEnd);
  assert.ok(handler.includes("await evaluateAdminSession(req)"), "session validation must be awaited");
  assert.ok(handler.includes("durable session verification unavailable"), "Firestore outage path must be explicit");
  assert.ok(handler.includes("res.writeHead(503"), "Firestore outage must fail closed with 503");
  assert.ok(
    handler.indexOf("durable session verification unavailable") < handler.indexOf("await recordAdminAuthFailure(ip)"),
    "session-store infrastructure errors must not be counted as bad credentials"
  );
});

test("S05-M3: session creation and global revoke-all are asynchronous durable operations", () => {
  assert.match(SERVER_SOURCE, /async function evaluateAdminSession\(/);
  assert.match(SERVER_SOURCE, /async function hasValidAdminSession\(/);
  assert.match(SERVER_SOURCE, /firestore:\s*db,/);
  assert.match(SERVER_SOURCE, /const result = await adminSessionStore\.revokeAll\(\)/);
  assert.match(SERVER_SOURCE, /sessionGeneration:\s*result\.generation/);
  assert.match(SERVER_SOURCE, /ADMIN_SESSION_ABSOLUTE_TTL_MS \/ 1000/);
});

test("S05-M3: persistent session collections are deny-all to client SDKs", () => {
  const rules = fs.readFileSync(path.join(__dirname, "..", "..", "firestore.rules"), "utf8");
  for (const collection of ["adminSessions", "adminSessionControl"]) {
    const marker = "match /" + collection + "/{";
    const start = rules.indexOf(marker);
    assert.ok(start >= 0, "missing client-deny rule for " + collection);
    const blockEnd = rules.indexOf("\n    }", start + marker.length);
    assert.ok(blockEnd > start, "could not bound rules block for " + collection);
    const body = rules.slice(start, blockEnd);
    assert.ok(body.includes("allow read, write: if false"), collection + " must deny all client access");
  }
});
