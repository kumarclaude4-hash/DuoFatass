"use strict";

// ── S3-14 remediation wiring tests ────────────────────────────────────────────
//
// Same caveat as adminAuditWiring.test.js: these are source-level structural
// tests, not behavioural ones. They read index.js as TEXT and assert the fix
// for each S3-14 finding (S05-L1, S05-L2, S05-L4, S05-I3) is actually wired
// in, not merely described in a comment. A live-Firestore behavioural test
// remains BLOCKED in this environment (no emulator / no service-account
// credentials).
//
// Pure-function-level regression tests for S05-L1 (reserved-uid rejection)
// and S05-I3 (safeTokenEqual length-oracle removal) live in pure.test.js
// instead, since those are directly callable without a live server.

const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const SERVER_SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");

// ── S05-L1 ─────────────────────────────────────────────────────────────────
// /admin/api/account/lookup used to be the one uid-taking admin route that
// skipped validAdminUid(), accepting any non-empty <=128-char uid — including
// one containing "/", which .doc() resolves as a multi-segment path.

test("S05-L1: GET /admin/api/account/lookup validates uid with validAdminUid()", () => {
  const routeStart = SERVER_SOURCE.indexOf('pathname === "/admin/api/account/lookup"');
  assert.ok(routeStart > 0, "GET /admin/api/account/lookup route not found");
  const routeEnd = SERVER_SOURCE.indexOf("\n  }\n\n  if (", routeStart);
  const handler = SERVER_SOURCE.slice(routeStart, routeEnd > routeStart ? routeEnd : routeStart + 1500);
  assert.ok(
    /validAdminUid\(uid\)/.test(handler),
    "account/lookup must reject a uid that fails validAdminUid() — this was the one admin " +
    "route that used to check only length/emptiness, letting a slash-bearing uid reach .doc()"
  );
});

// ── S05-L2 ─────────────────────────────────────────────────────────────────
// Every admin POST route used to call collectBody() first and run
// requireAdminAuth() only inside the "end" callback — the exact inverse of
// what collectBody's own doc comment claimed. requireAdminAuthThenBody()
// fixes the ordering by construction: it is defined to call requireAdminAuth
// before ever invoking collectBody.

// NOTE: /admin/api/waitlist/approve and /admin/api/waitlist/deny were removed
// in the migration to the invite system (commit 042c414). Their admin-gating
// obligation carried over verbatim to the two invite POST routes that replaced
// them, so those are listed here instead — every admin POST route, old or new,
// must still run requireAdminAuth() before its body is read.
const ADMIN_POST_ROUTES = [
  "/admin/api/invites/create",
  "/admin/api/invites/revoke",
  "/admin/api/locked/unfreeze",
  "/admin/api/sessions/revoke-all",
  "/admin/api/duress/enroll",
  "/admin/api/duress/revoke",
];

test("S05-L2: requireAdminAuthThenBody runs auth before reading the body", () => {
  const fnStart = SERVER_SOURCE.indexOf("function requireAdminAuthThenBody(req, res, onBody)");
  assert.ok(fnStart > 0, "requireAdminAuthThenBody() helper not found");
  const fnEnd = SERVER_SOURCE.indexOf("\n}", fnStart);
  const body = SERVER_SOURCE.slice(fnStart, fnEnd);
  const authIdx = body.indexOf("requireAdminAuth(req, res)");
  const collectIdx = body.indexOf("collectBody(req, res, onBody)");
  assert.ok(authIdx > 0, "requireAdminAuthThenBody must call requireAdminAuth");
  assert.ok(collectIdx > 0, "requireAdminAuthThenBody must call collectBody");
  assert.ok(
    authIdx < collectIdx,
    "requireAdminAuth must run BEFORE collectBody, not after — this is the exact ordering bug"
  );
  assert.ok(
    /req\.destroy\(\)/.test(body),
    "on auth failure the request must be destroyed, not left to drain on its own"
  );
});

test("S05-L2: every admin POST route is gated via requireAdminAuthThenBody, not a bare collectBody", () => {
  for (const route of ADMIN_POST_ROUTES) {
    const routeStart = SERVER_SOURCE.indexOf(`req.url === "${route}"`);
    assert.ok(routeStart > 0, `POST ${route} route not found`);
    const slice = SERVER_SOURCE.slice(routeStart, routeStart + 400);
    assert.ok(
      /requireAdminAuthThenBody\(req, res/.test(slice),
      `POST ${route} must be wired through requireAdminAuthThenBody(), not a bare collectBody() ` +
      `call that runs requireAdminAuth only after the body has been read`
    );
  }
});

test("S05-L2: server.headersTimeout and server.requestTimeout are explicitly bounded", () => {
  assert.ok(
    /server\.headersTimeout\s*=\s*\d/.test(SERVER_SOURCE),
    "no explicit server.headersTimeout override — Node's 60s default applied silently"
  );
  assert.ok(
    /server\.requestTimeout\s*=\s*\d/.test(SERVER_SOURCE),
    "no explicit server.requestTimeout override — Node's 300s default (per package.json's " +
    "pinned Node 20) applied silently, letting an unauthenticated caller hold a socket open " +
    "for up to 5 minutes"
  );
});

// ── S05-L3 ─────────────────────────────────────────────────────────────────
// No /admin/api/* JSON response set Cache-Control at all, unlike GET /admin
// which correctly set no-store/no-cache/must-revalidate.

test("S05-L3: setBaselineSecurityHeaders sets Cache-Control: no-store and Vary: Cookie for /admin*", () => {
  const fnStart = SERVER_SOURCE.indexOf("function setBaselineSecurityHeaders(req, res)");
  assert.ok(fnStart > 0, "setBaselineSecurityHeaders() not found");
  const fnEnd = SERVER_SOURCE.indexOf("\n}", fnStart);
  const body = SERVER_SOURCE.slice(fnStart, fnEnd);
  assert.ok(
    /startsWith\("\/admin"\)/.test(body),
    "setBaselineSecurityHeaders must gate the admin-only headers on the request path"
  );
  assert.ok(
    /res\.setHeader\("Cache-Control",\s*"no-store"\)/.test(body),
    "every /admin* response must get Cache-Control: no-store by default — previously only " +
    "GET /admin (the login shell) and the login/logout redirects set this; the JSON API " +
    "routes (locked accounts, duress-enrolled, audit log, waitlist) set nothing"
  );
  assert.ok(
    /res\.setHeader\("Vary",\s*"Cookie"\)/.test(body),
    "every /admin* response must set Vary: Cookie — authorization on these routes depends " +
    "entirely on the session cookie"
  );
});

// ── S05-L4 ─────────────────────────────────────────────────────────────────
// invite-revoke/unfreeze used to get()-then-write() outside any transaction,
// and GET /admin/api/locked + GET /admin/api/duress/enrolled had no limit().
//
// The old waitlist approve route this test guarded was removed in the invite
// migration (commit 042c414); POST /admin/api/invites/revoke inherited the
// identical read-check-write-in-a-transaction obligation (its transaction
// callback binds `tx`, not `txn`), so the TOCTOU assertion is made against it.

test("S05-L4: invites revoke wraps its read-check-write in db.runTransaction", () => {
  const routeStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/invites/revoke"');
  assert.ok(routeStart > 0, "POST /admin/api/invites/revoke route not found");
  const nextRouteStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/locked"', routeStart);
  assert.ok(nextRouteStart > routeStart, "could not bound the revoke handler");
  const handler = SERVER_SOURCE.slice(routeStart, nextRouteStart);
  assert.ok(
    /db\.runTransaction\(async \(tx\) => \{/.test(handler),
    "revoke must wrap its get()-branch-write() in db.runTransaction to close the TOCTOU " +
    "window between two concurrent revocations of the same inviteId"
  );
  assert.ok(/tx\.get\(ref\)/.test(handler), "revoke's transaction must read via tx.get(), not a bare ref.get()");
  assert.ok(/tx\.update\(ref/.test(handler), "revoke's transaction must write via tx.update(), not a bare ref.update()");
});

test("S05-L4: locked/unfreeze wraps its read-check-write in db.runTransaction and re-checks locked===true", () => {
  const routeStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/locked/unfreeze"');
  assert.ok(routeStart > 0, "POST /admin/api/locked/unfreeze route not found");
  const handler = SERVER_SOURCE.slice(routeStart, routeStart + 3000);
  assert.ok(
    /db\.runTransaction\(async \(txn\) => \{/.test(handler),
    "unfreeze must wrap its get()-branch-write() in db.runTransaction to close the TOCTOU " +
    "window between two concurrent unfreezes of the same uid"
  );
  assert.ok(
    /snap\.data\(\)\.locked !== true/.test(handler),
    "unfreeze's transaction must re-check locked === true inside the transaction, not just " +
    "that the doc exists, so a second concurrent call takes the already-handled branch " +
    "instead of re-writing and re-auditing"
  );
});

test("S05-L4: GET /admin/api/locked and GET /admin/api/duress/enrolled both bound their query with limit()", () => {
  const lockedStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/locked"');
  assert.ok(lockedStart > 0, "GET /admin/api/locked route not found");
  const lockedHandler = SERVER_SOURCE.slice(lockedStart, lockedStart + 1200);
  assert.ok(
    /collection\("accountLock"\)[\s\S]*?\.limit\(\d+\)/.test(lockedHandler),
    "GET /admin/api/locked's accountLock query must be bounded with .limit() — clients can " +
    "create their own accountLock doc, so the unbounded set grows with the user base"
  );

  const enrolledStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/duress/enrolled"');
  assert.ok(enrolledStart > 0, "GET /admin/api/duress/enrolled route not found");
  const enrolledHandler = SERVER_SOURCE.slice(enrolledStart, enrolledStart + 1200);
  assert.ok(
    /collection\("duressEligibility"\)[\s\S]*?\.limit\(\d+\)/.test(enrolledHandler),
    "GET /admin/api/duress/enrolled's duressEligibility query must be bounded with .limit()"
  );
});

// ── S05-I3 ─────────────────────────────────────────────────────────────────
// adminSessionCookie() used to decide the Secure flag from the client-
// suppliable x-forwarded-proto header unconditionally. It must now only
// trust that header when the operator has declared a trusted proxy is
// actually present (TRUSTED_PROXY_HOPS > 0), or via an explicit override.

test("S05-I3: adminSessionCookie only trusts x-forwarded-proto when a trusted proxy hop is configured", () => {
  const fnStart = SERVER_SOURCE.indexOf("function adminSessionCookie(sessionId, req, maxAgeSeconds)");
  assert.ok(fnStart > 0, "adminSessionCookie() not found");
  const fnEnd = SERVER_SOURCE.indexOf("\n}", fnStart);
  const body = SERVER_SOURCE.slice(fnStart, fnEnd);
  assert.ok(
    /TRUSTED_PROXY_HOPS\s*>\s*0\s*&&\s*forwardedProto\s*===\s*"https"/.test(body),
    "the Secure flag must gate x-forwarded-proto trust on TRUSTED_PROXY_HOPS > 0 (the " +
    "operator's existing explicit trusted-proxy declaration), not trust the client-suppliable " +
    "header unconditionally"
  );
  assert.ok(
    /FORCE_SECURE_COOKIES/.test(body),
    "an explicit FORCE_SECURE_COOKIES override must exist for operators whose topology the " +
    "automatic detection can't see"
  );
});

// ── S05-I3 (Sec-Fetch-Site CSRF defense-in-depth) ────────────────────────────
// The session cookie is SameSite=Lax (not Strict — some Android in-app
// webviews drop a Strict cookie on the post-login top-level navigation), so
// Lax withholds the cookie on cross-site sub-requests (fetch/XHR/form POST)
// but still attaches it on a cross-site top-level GET navigation. requireAdminAuth()
// therefore layers a Fetch-Metadata `Sec-Fetch-Site: cross-site` reject as a
// second, browser-set (attacker-unforgeable) CSRF signal. It must:
//   1. run inside requireAdminAuth() so EVERY admin/api route inherits it;
//   2. reject ONLY the unambiguous "cross-site" value (leaving same-origin/
//      same-site/none/absent untouched, so it is purely additive and cannot
//      break a legitimate same-origin request or an older browser);
//   3. reject with 403 and audit the event; and
//   4. run BEFORE the token/session evaluation, so a cross-site request is
//      turned away without ever touching the credential-comparison path.

test("S05-I3: requireAdminAuth rejects Sec-Fetch-Site: cross-site with a 403 before any auth work", () => {
  const fnStart = SERVER_SOURCE.indexOf("async function requireAdminAuth(req, res)");
  assert.ok(fnStart > 0, "requireAdminAuth() not found");
  const fnEnd = SERVER_SOURCE.indexOf("\n}", fnStart);
  const body = SERVER_SOURCE.slice(fnStart, fnEnd);

  const secFetchIdx = body.indexOf('req.headers["sec-fetch-site"]');
  assert.ok(
    secFetchIdx > 0,
    "requireAdminAuth must read the browser-set Sec-Fetch-Site header — a same-origin admin " +
    "request carries same-origin/same-site, a forged cross-site POST carries cross-site, and the " +
    "value cannot be set by an attacker page (Fetch Metadata is set by the browser itself)"
  );
  assert.ok(
    /secFetchSite\s*===\s*"cross-site"/.test(body),
    "the check must key on the exact value \"cross-site\" only — matching same-site/none/'' too " +
    "would break legitimate same-origin admin-panel fetches and older browsers that omit the header"
  );

  // The reject must be a 403 and must be audited.
  const crossSiteBranch = body.slice(secFetchIdx, secFetchIdx + 400);
  assert.ok(
    /res\.writeHead\(403/.test(crossSiteBranch),
    "a cross-site admin request must be rejected with 403, not silently allowed through"
  );
  assert.ok(
    /auditAdminEvent\("admin_api_blocked_cross_site"/.test(crossSiteBranch),
    "the cross-site rejection must be audited (admin_api_blocked_cross_site) so a CSRF attempt is " +
    "visible in the durable audit trail, not just dropped"
  );

  // Defense-in-depth ordering: the cross-site reject must fire BEFORE the
  // token comparison (safeTokenEqual) and before the IP-lockout check, so a
  // forged cross-site request never reaches the credential path at all.
  const tokenIdx = body.indexOf("safeTokenEqual(supplied, ADMIN_TOKEN)");
  assert.ok(tokenIdx > 0, "requireAdminAuth must still compare the admin token");
  assert.ok(
    secFetchIdx < tokenIdx,
    "the Sec-Fetch-Site cross-site reject must run BEFORE the token comparison, so a forged " +
    "cross-site request is turned away without touching the credential-comparison path"
  );
});

// The cookie the CSRF story depends on is SameSite=Lax, and the comment
// describing the CSRF defense must not claim SameSite=Strict — an inaccurate
// comment about the exact mechanism in force is the same class of defect
// S05-I2 removed elsewhere in this file, and it directly undercuts the audit
// reviewer's ability to trust the S05-I3 rationale.
test("S05-I3/S05-I2: the session cookie is SameSite=Lax and the CSRF comment does not misstate it as Strict", () => {
  const cookieFnStart = SERVER_SOURCE.indexOf("function adminSessionCookie(sessionId, req, maxAgeSeconds)");
  assert.ok(cookieFnStart > 0, "adminSessionCookie() not found");
  const cookieFnEnd = SERVER_SOURCE.indexOf("\n}", cookieFnStart);
  const cookieBody = SERVER_SOURCE.slice(cookieFnStart, cookieFnEnd);
  assert.ok(
    /SameSite=Lax/.test(cookieBody),
    "the admin session cookie is issued SameSite=Lax"
  );

  const authFnStart = SERVER_SOURCE.indexOf("async function requireAdminAuth(req, res)");
  const authFnEnd = SERVER_SOURCE.indexOf("\n}", authFnStart);
  const authBody = SERVER_SOURCE.slice(authFnStart, authFnEnd);
  assert.ok(
    !/SameSite=Strict/.test(authBody),
    "the requireAdminAuth CSRF comment must not claim SameSite=Strict — the cookie is issued " +
    "SameSite=Lax, and a comment stating otherwise misdescribes the exact defense in force (the " +
    "same stale-comment class S05-I2 removed)"
  );
});
