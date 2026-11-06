"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");

// These routes require a live Firebase ID token. Firebase Admin's second
// verifyIdToken argument is checkRevoked; without it, revokeRefreshTokens()
// does not reject an already-issued ID token until that JWT expires.
// /duress-lock is intentionally excluded: it uses a single-use nonce because
// the client has already signed out and cannot present an ID token.
const ROUTES = [
  "/migrateUid",
  "/acknowledgeRotation",
  "/createChat",
  "/turnCredentials",
  "/mediaToken",
  "/linkPreview",
  "/removeGroupMember",
  "/requestLockNonce",
  "/signal/registerBundle",
  "/signal/appendPreKeys",
  "/signal/updatePreKey",
  "/signal/claimPreKey",
];

function handlerFor(marker) {
  const start = SOURCE.indexOf(marker);
  assert.ok(start >= 0, `could not locate handler marker: ${marker}`);
  const next = SOURCE.indexOf('req.url === "', start + marker.length);
  return SOURCE.slice(start, next < 0 ? SOURCE.length : next);
}

for (const route of ROUTES) {
  test(route + " verifies Firebase ID-token revocation", () => {
    const handler = handlerFor('req.url === "' + route + '"');
    assert.match(
      handler,
      /verifyIdToken\(\s*(?:idToken|tok)\s*,\s*true\s*\)/,
      route + " must call verifyIdToken(token, true); revoked sessions must be rejected"
    );
  });
}

test("/duress-lock remains nonce-authorized and does not require a signed-out ID token", () => {
  const handler = handlerFor('req.url === "/duress-lock"');
  assert.match(handler, /_duressNonces|duressNonces|nonce/i);
  assert.doesNotMatch(handler, /verifyIdToken\(/);
});


const RATE_LIMIT_KEY_BY_ROUTE = {
  "/migrateUid": "migrateUid",
  "/acknowledgeRotation": "acknowledgeRotation",
  "/createChat": "createChat",
  "/turnCredentials": "turnCredentials",
  "/mediaToken": "mediaToken",
  "/linkPreview": "linkPreview",
  "/removeGroupMember": "removeGroupMember",
  "/requestLockNonce": "requestLockNonce",
  "/signal/registerBundle": "signalRegisterBundle",
  "/signal/appendPreKeys": "signalAppendPreKeys",
  "/signal/updatePreKey": "signalUpdatePreKeys",
  "/signal/claimPreKey": "signalClaimPreKey",
};

for (const route of ROUTES) {
  test(route + " is protected by the shared authenticated endpoint limiter", () => {
    const handler = handlerFor('req.url === "' + route + '"');
    const limitKey = RATE_LIMIT_KEY_BY_ROUTE[route];
    assert.ok(limitKey, "route must have an explicit limiter inventory entry");
    assert.match(
      handler,
      new RegExp('checkAuthRateLimit\\\\(\\\\s*[A-Za-z_$][\\\\w$]*\\\\s*,\\\\s*["\\\\']' + limitKey + '["\\\\']\\\\s*\\\\)'),
      route + " must await checkAuthRateLimit() with its dedicated endpoint bucket"
    );
  });
}

test("authenticated route inventory and AUTH_RATE_LIMITS configuration stay in sync", () => {
  const config = SOURCE.match(/const AUTH_RATE_LIMITS = \\{([\\s\\S]*?)\\n\\};/);
  assert.ok(config, "could not locate AUTH_RATE_LIMITS configuration");
  const configuredKeys = [...config[1].matchAll(/^\\s*([A-Za-z][A-Za-z0-9]*):\\s*\\d+/gm)]
    .map((match) => match[1])
    .sort();
  const routeKeys = Object.values(RATE_LIMIT_KEY_BY_ROUTE).sort();
  assert.deepStrictEqual(configuredKeys, routeKeys,
    "every configured per-route limiter must correspond to exactly one authenticated route; remove stale buckets or update the route inventory");
});

test("all Firebase ID-token verifications in the HTTP server check revocation", () => {
  const calls = [...SOURCE.matchAll(/verifyIdToken\(([^)]*)\)/g)];
  assert.ok(calls.length >= ROUTES.length, "expected live authenticated routes to verify ID tokens");
  for (const call of calls) {
    assert.match(call[1], /,\s*true\s*$/, `missing revocation check in: ${call[0]}`);
  }
});
