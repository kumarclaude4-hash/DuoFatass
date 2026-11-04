"use strict";

const fs = require("node:fs");
const path = require("node:path");
const SERVER_SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");

const test = require("node:test");
const assert = require("node:assert/strict");

const source = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");

test("every Firebase ID-token verification rejects revoked tokens", () => {
  const allCalls = [...source.matchAll(/verifyIdToken\s*\(/g)];
  const checkedCalls = [...source.matchAll(/verifyIdToken\s*\(\s*[^,]+,\s*true\s*\)/g)];
  assert.ok(allCalls.length > 0, "expected at least one verifyIdToken call site");
  assert.equal(checkedCalls.length, allCalls.length,
    "every verifyIdToken call must pass checkRevoked=true");
});

test("public liveness routes do not expose push delivery counters", () => {
  const statusStart = source.indexOf('if (parsedUrl === "/status")');
  const statusEnd = source.indexOf("// ── GET / —", statusStart);
  assert.ok(statusStart >= 0 && statusEnd > statusStart, "could not locate /status handler");
  const statusHandler = source.slice(statusStart, statusEnd);
  assert.match(statusHandler, /status:\s*"running"/);
  assert.doesNotMatch(statusHandler, /delivered|groupDelivered|skippedMissingToken|skippedOld|failed|uptime|startedAt/i);

  const rootStart = source.indexOf("// ── GET / — static service liveness page");
  const rootEnd = source.indexOf("// ── /linkPreviewImage", rootStart);
  assert.ok(rootStart >= 0 && rootEnd > rootStart, "could not locate root handler");
  const rootHandler = source.slice(rootStart, rootEnd);
  assert.doesNotMatch(rootHandler, /stats\.|delivered|groupDelivered|skippedMissingToken|skippedOld|failed|uptime|startedAt/i);
});
test("Signal pre-key append persists and advances a server-owned high-water mark", () => {
  const appendStart = SERVER_SOURCE.indexOf('req.url === "/signal/appendPreKeys"');
  const claimStart = SERVER_SOURCE.indexOf('req.url === "/signal/claimPreKey"');
  assert.ok(appendStart > 0 && claimStart > appendStart, "Signal append/claim route markers missing");
  const appendHandler = SERVER_SOURCE.slice(appendStart, claimStart);
  assert.match(appendHandler, /appendPreKeysWithHighWaterMark/);
  assert.match(appendHandler, /current\.oneTimePreKeyIdHighWaterMark/);
  assert.match(appendHandler, /firstPreKeyId/);
  assert.match(appendHandler, /oneTimePreKeyIdHighWaterMark:\s*merged\.highWaterMark/);
  const claimHandler = SERVER_SOURCE.slice(claimStart, SERVER_SOURCE.indexOf('req.url === "/turnCredentials"', claimStart));
  assert.match(claimHandler, /oneTimePreKeyIdHighWaterMark:/);
  assert.match(claimHandler, /getPreKeyHighWaterMark/);
});

