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

test("all Firebase ID-token verifications in the HTTP server check revocation", () => {
  const calls = [...SOURCE.matchAll(/verifyIdToken\(([^)]*)\)/g)];
  assert.ok(calls.length >= ROUTES.length, "expected live authenticated routes to verify ID tokens");
  for (const call of calls) {
    assert.match(call[1], /,\s*true\s*$/, `missing revocation check in: ${call[0]}`);
  }
});
