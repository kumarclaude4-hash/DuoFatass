"use strict";

// Source-level contract test for the authenticated /linkPreview endpoint and
// its Android consumer. Pure status classification is tested in pure.test.js;
// this guard verifies the production boundary emits only the bounded contract.

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SERVER = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");
const ANDROID_ROOT = path.join(__dirname, "..", "..", "app", "src", "main", "java", "com", "duoshield", "app");
const FETCHER = fs.readFileSync(path.join(ANDROID_ROOT, "util", "LinkPreviewFetcher.java"), "utf8");
const ADAPTER = fs.readFileSync(path.join(ANDROID_ROOT, "ui", "MessageAdapter.java"), "utf8");

const startMarker = 'if (req.method === "POST" && req.url === "/linkPreview")';
const endMarker = "// ── /removeGroupMember";
const start = SERVER.indexOf(startMarker);
const end = SERVER.indexOf(endMarker, start);
assert.ok(start >= 0 && end > start, "could not isolate production /linkPreview handler");
const ROUTE = SERVER.slice(start, end);

test("link-preview endpoint verifies a live Firebase token and applies endpoint rate limiting", () => {
  assert.ok(ROUTE.includes('admin.auth().verifyIdToken(tok, true)'),
    "link preview must verify token revocation, not only signature/expiry");
  assert.ok(ROUTE.includes('checkAuthRateLimit(lpUid, "linkPreview", res)'));
  assert.ok(ROUTE.includes("if (authRateLimitDecision === null) return;"),
    "shared-limiter infrastructure failures must stop the request after the 503 response");
  assert.ok(ROUTE.indexOf("if (authRateLimitDecision === null) return;")
      < ROUTE.indexOf("if (!authRateLimitDecision)"),
    "a genuine 429 quota decision must run only after the infrastructure-unavailable branch");
});

test("blocked original and redirect targets produce only the stable blocked status", () => {
  assert.ok(ROUTE.includes("isBlockedPreviewHost(parsed.hostname)"));
  assert.ok(ROUTE.includes('status: "blocked"'));
  assert.ok(ROUTE.includes("fetchFollowingSafeRedirects("),
    "redirect validation must run for every hop before fetch");
  assert.match(SERVER, /throw new Error\(`Blocked redirect target/);
  assert.ok(ROUTE.includes("pure.linkPreviewFailureStatus(fetchErr)"));
  assert.ok(ROUTE.includes("status,"));
  assert.equal(ROUTE.includes("fetchErr.message"), false,
    "network exception text must never be returned to the client");
  assert.equal(ROUTE.includes("res.end(JSON.stringify({ error: fetchErr"), false);
});

test("HTTP, content-type and metadata outcomes are distinguishable without changing the response schema", () => {
  for (const status of ["upstream_error", "unsupported_content", "no_metadata", "ok"]) {
    assert.ok(ROUTE.includes('"' + status + '"'), "server must emit " + status);
  }
  assert.ok(ROUTE.includes("Content-Type"));
  assert.ok(ROUTE.includes("previewDomainFromUrl(finalUrl, parsed.hostname)"),
    "the displayed domain must be the validated final redirect hop");
  assert.ok(ROUTE.includes("res.end(JSON.stringify(preview))"));
  assert.ok(ROUTE.includes("cleanupPreview()"), "upstream connection cleanup must run for every response");
});

test("Android parses every status, fails closed on unknown values and supports old servers", () => {
  for (const wireValue of ["ok", "no_metadata", "unsupported_content", "blocked", "timeout", "upstream_error", "unavailable"]) {
    assert.ok(FETCHER.includes('case "' + wireValue + '"'), "Android must parse " + wireValue);
  }
  assert.ok(FETCHER.includes("default: return UNAVAILABLE"));
  assert.ok(FETCHER.includes("hasMetadata ? OK : NO_METADATA"),
    "a missing status field from an older server must remain backward-compatible");
  assert.ok(FETCHER.includes("if (conn != null) conn.disconnect()"));
  assert.ok(FETCHER.includes("64 * 1024"), "client JSON response parsing must stay bounded");
});

test("blocked and transient outcomes are not rendered as message cards", () => {
  assert.ok(FETCHER.includes("this == OK || this == NO_METADATA || this == UNSUPPORTED_CONTENT"));
  assert.ok(ADAPTER.includes("preview == null || !preview.isDisplayable()"));
  assert.ok(ADAPTER.includes("h.linkPreviewCard.setVisibility(View.GONE)"),
    "recycled view holders must reset the preview card before binding a different message");
});
