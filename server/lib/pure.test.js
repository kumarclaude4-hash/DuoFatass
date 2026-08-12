"use strict";

// Unit tests for the pure server helpers extracted from index.js.
// Run with: npm test   (inside server/) — uses the Node built-in test runner.

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const pure = require("./pure");

test("notificationBody maps known types and falls back safely", () => {
  assert.equal(pure.notificationBody({ type: "image" }), "Sent a photo 🖼");
  assert.equal(pure.notificationBody({ type: "video" }), "Sent a video 🎬");
  assert.equal(pure.notificationBody({ type: "voice" }), "Sent a voice note 🎙");
  assert.equal(pure.notificationBody({ type: "contact" }), "Shared a contact card 📇");
  assert.equal(pure.notificationBody({ type: "text" }), "New encrypted message");
  assert.equal(pure.notificationBody({}), "New encrypted message");
  // Robust against non-objects (never throws on malformed input).
  assert.equal(pure.notificationBody(null), "New encrypted message");
  assert.equal(pure.notificationBody(undefined), "New encrypted message");
  assert.equal(pure.notificationBody("image"), "New encrypted message");
});

test("safeTokenEqual is correct and rejects length mismatches", () => {
  assert.equal(pure.safeTokenEqual("s3cr3t-token", "s3cr3t-token"), true);
  assert.equal(pure.safeTokenEqual("s3cr3t-token", "s3cr3t-tokeX"), false);
  // Length mismatch must return false, never throw (timingSafeEqual throws).
  assert.equal(pure.safeTokenEqual("short", "a-much-longer-token"), false);
  assert.equal(pure.safeTokenEqual("", ""), true);
  // Coerces non-strings rather than throwing.
  assert.equal(pure.safeTokenEqual(12345, "12345"), true);
  assert.equal(pure.safeTokenEqual(12345, 12346), false);
});

test("safeTokenEqual accepts an equal-length token containing NUL bytes", () => {
  const a = "abc\u0000def";
  const b = "abc\u0000def";
  assert.equal(pure.safeTokenEqual(a, b), true);
});

test("validAdminUid enforces the UID whitelist", () => {
  assert.equal(pure.validAdminUid("user_123"), true);
  assert.equal(pure.validAdminUid("a"), true);
  assert.equal(pure.validAdminUid("x".repeat(128)), true);
  // Boundaries / rejects.
  assert.equal(pure.validAdminUid(""), false);
  assert.equal(pure.validAdminUid("x".repeat(129)), false);
  assert.equal(pure.validAdminUid("a/b"), false);        // path separator
  assert.equal(pure.validAdminUid("a\\b"), false);       // backslash
  assert.equal(pure.validAdminUid("a\u0000b"), false);   // NUL
  assert.equal(pure.validAdminUid("a\nb"), false);       // control char
  assert.equal(pure.validAdminUid(123), false);          // non-string
  assert.equal(pure.validAdminUid(null), false);
  assert.equal(pure.validAdminUid(undefined), false);
});

test("getCookie parses from a raw header string", () => {
  const header = "theme=dark; admin_session=abc%2F123; last=1";
  assert.equal(pure.getCookie(header, "theme"), "dark");
  // Value is URL-decoded.
  assert.equal(pure.getCookie(header, "admin_session"), "abc/123");
  assert.equal(pure.getCookie(header, "last"), "1");
  assert.equal(pure.getCookie(header, "missing"), "");
});

test("getCookie parses from a request-like object (index.js call shape)", () => {
  const req = { headers: { cookie: "admin_session=tok; foo=bar" } };
  assert.equal(pure.getCookie(req, "admin_session"), "tok");
  assert.equal(pure.getCookie(req, "foo"), "bar");
  // No cookie header at all.
  assert.equal(pure.getCookie({ headers: {} }, "admin_session"), "");
  assert.equal(pure.getCookie({}, "admin_session"), "");
});

test("isBlockedPreviewHost blocks SSRF-prone targets", () => {
  const blocked = [
    "localhost",
    "127.0.0.1",
    "10.0.0.5",
    "192.168.1.10",
    "172.16.0.1",
    "172.31.255.255",
    "169.254.169.254",           // AWS/GCP metadata IP
    "metadata.google.internal",
    "db.internal",
    "printer.local",
    "::1",
    "LOCALHOST",                 // case-insensitive
  ];
  for (const h of blocked) {
    assert.equal(pure.isBlockedPreviewHost(h), true, `expected blocked: ${h}`);
  }
});

test("isBlockedPreviewHost allows legitimate public hosts", () => {
  const allowed = [
    "example.com",
    "www.vercel.com",
    "cdn.jsdelivr.net",
    "172.32.0.1",   // just outside the 172.16-31 private range
    "11.0.0.1",     // not RFC-1918
    "8.8.8.8",
  ];
  for (const h of allowed) {
    assert.equal(pure.isBlockedPreviewHost(h), false, `expected allowed: ${h}`);
  }
  // Never throws on empty/nullish input.
  assert.equal(pure.isBlockedPreviewHost(""), false);
  assert.equal(pure.isBlockedPreviewHost(null), false);
  assert.equal(pure.isBlockedPreviewHost(undefined), false);
});

test("previewDomainFromUrl labels the preview with the FINAL redirect hop, not the original host (S04-I3)", () => {
  // The phishing scenario the fix closes: trusted.example 302s to
  // attacker.example. The card must be labelled attacker.example, matching
  // where title/imageUrl actually came from — not trusted.example, which
  // would misattribute attacker content to a trusted-looking domain.
  assert.equal(
    pure.previewDomainFromUrl("https://attacker.example/landing", "trusted.example"),
    "attacker.example"
  );
});

test("previewDomainFromUrl strips a leading www. from the final host", () => {
  assert.equal(pure.previewDomainFromUrl("https://www.example.com/page", "example.com"), "example.com");
});

test("previewDomainFromUrl falls back to fallbackHostname on an unparseable finalUrl", () => {
  assert.equal(pure.previewDomainFromUrl("not a url", "example.com"), "example.com");
  assert.equal(pure.previewDomainFromUrl("not a url", "www.example.com"), "example.com");
  assert.equal(pure.previewDomainFromUrl(undefined, undefined), "");
});

test("clampTurnCredentialTtlSeconds caps a value above the ceiling (S04-M2, closes the 24h exposure)", () => {
  // 86400 (the old hardcoded default) must never pass through uncapped.
  assert.equal(pure.clampTurnCredentialTtlSeconds(86400, 60, 3600), 3600);
});

test("clampTurnCredentialTtlSeconds raises a value below the floor", () => {
  assert.equal(pure.clampTurnCredentialTtlSeconds(5, 60, 3600), 60);
});

test("clampTurnCredentialTtlSeconds passes through an in-range value unchanged", () => {
  assert.equal(pure.clampTurnCredentialTtlSeconds(600, 60, 3600), 600);
});

test("clampTurnCredentialTtlSeconds falls back to the ceiling (never the old 24h default) on a missing/non-numeric env value", () => {
  assert.equal(pure.clampTurnCredentialTtlSeconds(undefined, 60, 3600), 3600);
  assert.equal(pure.clampTurnCredentialTtlSeconds(NaN, 60, 3600), 3600);
  assert.equal(pure.clampTurnCredentialTtlSeconds("not-a-number", 60, 3600), 3600);
});

// Minimal stand-in for a Firestore Timestamp: exposes .toDate() like the real
// admin.firestore.Timestamp does, without requiring the Firestore SDK in a
// pure unit test.
function fakeTimestamp(date) {
  return { toDate: () => date };
}

test("resolveNonceExpiry unwraps a Firestore-Timestamp-shaped value via .toDate()", () => {
  const d = new Date("2030-01-01T00:00:00Z");
  assert.deepEqual(pure.resolveNonceExpiry(fakeTimestamp(d)), d);
});

test("resolveNonceExpiry passes through a plain Date unchanged", () => {
  const d = new Date("2030-01-01T00:00:00Z");
  assert.equal(pure.resolveNonceExpiry(d), d);
});

test("resolveNonceExpiry returns null for a missing/malformed expiresAt (S06-L1 — must not throw)", () => {
  assert.equal(pure.resolveNonceExpiry(undefined), null);
  assert.equal(pure.resolveNonceExpiry(null), null);
  assert.equal(pure.resolveNonceExpiry("not-a-date"), null);
  assert.equal(pure.resolveNonceExpiry(1234567890), null);
  assert.equal(pure.resolveNonceExpiry({}), null);
});

test("isNonceUsable accepts a Firestore Timestamp expiry that has not yet passed", () => {
  const now = Date.now();
  const future = fakeTimestamp(new Date(now + 60000));
  assert.equal(pure.isNonceUsable("uid-1", future, now), true);
});

test("isNonceUsable rejects an expiry that has already passed", () => {
  const now = Date.now();
  const past = fakeTimestamp(new Date(now - 1000));
  assert.equal(pure.isNonceUsable("uid-1", past, now), false);
});

test("isNonceUsable rejects a missing uid even with a valid future expiry", () => {
  const now = Date.now();
  const future = fakeTimestamp(new Date(now + 60000));
  assert.equal(pure.isNonceUsable(undefined, future, now), false);
  assert.equal(pure.isNonceUsable(null, future, now), false);
  assert.equal(pure.isNonceUsable("", future, now), false);
});

test("isNonceUsable fails CLOSED on a missing expiresAt, never throws (S06-L1 — old code threw a bare TypeError here)", () => {
  const now = Date.now();
  assert.doesNotThrow(() => pure.isNonceUsable("uid-1", undefined, now));
  assert.equal(pure.isNonceUsable("uid-1", undefined, now), false);
});

test("isNonceUsable fails CLOSED on an unparseable expiresAt string, never fail-open (S06-L1 — old code compared against Invalid Date and returned true)", () => {
  const now = Date.now();
  assert.equal(pure.isNonceUsable("uid-1", "not-a-real-date", now), false);
});

test("isNonceUsable treats the exact expiry instant as still usable (inclusive boundary)", () => {
  const now = Date.now();
  const exact = fakeTimestamp(new Date(now));
  assert.equal(pure.isNonceUsable("uid-1", exact, now), true);
});

test("evaluateFixedWindow opens a window on first hit", () => {
  const { allowed, record } = pure.evaluateFixedWindow(undefined, 1000, 60000, 5);
  assert.equal(allowed, true);
  assert.deepEqual(record, { count: 1, windowStart: 1000 });
});

test("evaluateFixedWindow increments within the window until the cap", () => {
  const windowMs = 60000;
  const max = 3;
  let rec = pure.evaluateFixedWindow(undefined, 0, windowMs, max).record;   // 1
  let r2 = pure.evaluateFixedWindow(rec, 10, windowMs, max);                // 2
  assert.equal(r2.allowed, true);
  assert.equal(r2.record.count, 2);
  let r3 = pure.evaluateFixedWindow(r2.record, 20, windowMs, max);          // 3
  assert.equal(r3.allowed, true);
  assert.equal(r3.record.count, 3);
  // 4th within the window is blocked and the record is unchanged.
  let r4 = pure.evaluateFixedWindow(r3.record, 30, windowMs, max);
  assert.equal(r4.allowed, false);
  assert.equal(r4.record.count, 3);
});

test("evaluateFixedWindow resets once the window elapses", () => {
  const windowMs = 60000;
  const max = 2;
  const first = pure.evaluateFixedWindow(undefined, 0, windowMs, max).record;
  const blocked = pure.evaluateFixedWindow(
    { count: max, windowStart: 0 }, 100, windowMs, max
  );
  assert.equal(blocked.allowed, false);
  // At exactly windowMs later, the window rolls over and a new one starts.
  const rolled = pure.evaluateFixedWindow(
    { count: max, windowStart: 0 }, windowMs, windowMs, max
  );
  assert.equal(rolled.allowed, true);
  assert.deepEqual(rolled.record, { count: 1, windowStart: windowMs });
  assert.ok(first);
});

// ── B2 SigV4 presign coverage removed (S03-L3 / S04-I2) ───────────────────────
// `buildB2PresignUrl` / `b2HmacKey` were tested here. Both were deleted from
// server/lib/pure.js because their only caller — the server's dead
// `b2PresignUrl` / `b2PresignUrlForUid` helpers and the `/b2PresignedPut`,
// `/b2PresignedGet`, `/b2Delete` routes they were meant to serve — never
// existed in the router table. See server/index.js for the full removal note.

// ── Additional edge-case coverage ─────────────────────────────────────────────

test("isBlockedPreviewHost blocks bracketed IPv6 loopback [::1]", () => {
  assert.equal(pure.isBlockedPreviewHost("[::1]"), true);
});

test("isBlockedPreviewHost treats edge 172.x boundary correctly", () => {
  // 172.15.x and 172.32.x are public — only 172.16–31 are RFC-1918.
  assert.equal(pure.isBlockedPreviewHost("172.15.255.255"), false);
  assert.equal(pure.isBlockedPreviewHost("172.16.0.1"), true);
  assert.equal(pure.isBlockedPreviewHost("172.31.255.255"), true);
  assert.equal(pure.isBlockedPreviewHost("172.32.0.0"), false);
});

test("evaluateFixedWindow allows exactly `max` requests in a window", () => {
  const windowMs = 60000;
  const max = 5;
  let rec;
  for (let i = 1; i <= max; i++) {
    const result = pure.evaluateFixedWindow(rec, i * 100, windowMs, max);
    assert.equal(result.allowed, true, `request ${i} should be allowed`);
    assert.equal(result.record.count, i);
    rec = result.record;
  }
  // Request max+1 must be blocked.
  const over = pure.evaluateFixedWindow(rec, (max + 1) * 100, windowMs, max);
  assert.equal(over.allowed, false);
  assert.equal(over.record.count, max); // record is unchanged when blocked
});

test("evaluateFixedWindow treats now exactly at windowStart + windowMs as a new window", () => {
  const windowMs = 60000;
  const max = 1;
  // Fill the window.
  const first = pure.evaluateFixedWindow(undefined, 0, windowMs, max).record;
  // Exactly at the boundary (elapsed === windowMs) should open a fresh window.
  const boundary = pure.evaluateFixedWindow(first, windowMs, windowMs, max);
  assert.equal(boundary.allowed, true);
  assert.deepEqual(boundary.record, { count: 1, windowStart: windowMs });
});

// ── collectStaleKeys (S02-L3) ─────────────────────────────────────────────────

test("collectStaleKeys returns keys older than the TTL", () => {
  const now = 100_000;
  const ttlMs = 60_000;
  const entries = [
    ["fresh", now - 1_000],   // well within TTL
    ["stale1", now - 61_000], // just past TTL
    ["stale2", now - 500_000],
  ];
  assert.deepEqual(pure.collectStaleKeys(entries, now, ttlMs), ["stale1", "stale2"]);
});

test("collectStaleKeys treats exactly-at-TTL as not yet stale", () => {
  const now = 100_000;
  const ttlMs = 60_000;
  const entries = [["boundary", now - ttlMs]];
  assert.deepEqual(pure.collectStaleKeys(entries, now, ttlMs), []);
});

test("collectStaleKeys works directly against a live Map (no copy needed)", () => {
  const now = 100_000;
  const ttlMs = 60_000;
  const map = new Map([
    ["a", now - 10],
    ["b", now - 999_999],
  ]);
  assert.deepEqual(pure.collectStaleKeys(map, now, ttlMs), ["b"]);
});

test("collectStaleKeys returns an empty array for an empty input", () => {
  assert.deepEqual(pure.collectStaleKeys([], 100_000, 60_000), []);
});

// ── pickClientIp (S04-M3) ──────────────────────────────────────────────────────

test("pickClientIp with the default 1 trusted hop reproduces the original rightmost-entry behavior", () => {
  assert.equal(
    pure.pickClientIp("203.0.113.1, 10.0.0.5", "10.0.0.5", 1),
    "10.0.0.5"
  );
});

test("pickClientIp with 0 trusted hops ignores X-Forwarded-For entirely", () => {
  assert.equal(
    pure.pickClientIp("1.2.3.4", "10.0.0.5", 0),
    "10.0.0.5"
  );
});

test("pickClientIp with 2 trusted hops picks the second-from-right entry", () => {
  assert.equal(
    pure.pickClientIp("203.0.113.1, 198.51.100.9, 10.0.0.5", "10.0.0.5", 2),
    "198.51.100.9"
  );
});

test("pickClientIp falls back to the socket address when there are fewer entries than trusted hops", () => {
  assert.equal(
    pure.pickClientIp("203.0.113.1", "10.0.0.5", 2),
    "10.0.0.5"
  );
});

test("pickClientIp falls back to the socket address when X-Forwarded-For is absent", () => {
  assert.equal(
    pure.pickClientIp(undefined, "10.0.0.5", 1),
    "10.0.0.5"
  );
});

test("pickClientIp defaults to 1 hop when trustedHops is not an integer", () => {
  assert.equal(
    pure.pickClientIp("203.0.113.1, 10.0.0.5", "10.0.0.5", undefined),
    "10.0.0.5"
  );
});

// ── normalizeIpForRateLimit (S04-M1) ──────────────────────────────────────────

test("normalizeIpForRateLimit leaves an IPv4 address unchanged", () => {
  assert.equal(pure.normalizeIpForRateLimit("203.0.113.7"), "203.0.113.7");
});

test("normalizeIpForRateLimit collapses two addresses in the same /64 to the same key", () => {
  const a = pure.normalizeIpForRateLimit("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd");
  const b = pure.normalizeIpForRateLimit("2001:db8:1234:5678:1111:2222:3333:4444");
  assert.equal(a, b);
});

test("normalizeIpForRateLimit gives different keys for different /64 blocks", () => {
  const a = pure.normalizeIpForRateLimit("2001:db8:1234:5678::1");
  const b = pure.normalizeIpForRateLimit("2001:db8:9999:5678::1");
  assert.notEqual(a, b);
});

test("normalizeIpForRateLimit expands '::' shorthand consistently regardless of where it falls", () => {
  // Both represent the same /64 prefix (2001:db8:0:0) via different shorthand.
  const a = pure.normalizeIpForRateLimit("2001:db8::1");
  const b = pure.normalizeIpForRateLimit("2001:db8:0:0:0:0:0:1");
  assert.equal(a, b);
});

test("normalizeIpForRateLimit unwraps an IPv4-mapped IPv6 address to its IPv4 form", () => {
  assert.equal(pure.normalizeIpForRateLimit("::ffff:203.0.113.7"), "203.0.113.7");
});

test("normalizeIpForRateLimit strips an IPv6 zone index before normalizing", () => {
  assert.equal(
    pure.normalizeIpForRateLimit("fe80::1%eth0"),
    pure.normalizeIpForRateLimit("fe80::1")
  );
});

test("normalizeIpForRateLimit strips enclosing brackets", () => {
  assert.equal(
    pure.normalizeIpForRateLimit("[2001:db8::1]"),
    pure.normalizeIpForRateLimit("2001:db8::1")
  );
});

test("normalizeIpForRateLimit returns malformed input unchanged rather than guessing", () => {
  assert.equal(pure.normalizeIpForRateLimit("2001:db8::1::2"), "2001:db8::1::2");
  assert.equal(pure.normalizeIpForRateLimit("not:an:ip:at:all:but:has:colons:here"), "not:an:ip:at:all:but:has:colons:here");
});

test("normalizeIpForRateLimit passes through non-string / empty input unchanged", () => {
  assert.equal(pure.normalizeIpForRateLimit(""), "");
  assert.equal(pure.normalizeIpForRateLimit("unknown"), "unknown");
});

test("getCookie returns empty string when name is not present but header is non-empty", () => {
  const header = "a=1; b=2; c=3";
  assert.equal(pure.getCookie(header, "d"), "");
  assert.equal(pure.getCookie(header, ""), "");
});

test("getCookie handles a cookie value that contains encoded equals signs", () => {
  // Values may contain %3D (encoded '=').
  const header = "token=abc%3Ddef";
  assert.equal(pure.getCookie(header, "token"), "abc=def");
});

test("notificationBody returns fallback for unknown type strings", () => {
  assert.equal(pure.notificationBody({ type: "file" }), "New encrypted message");
  assert.equal(pure.notificationBody({ type: "" }), "New encrypted message");
  assert.equal(pure.notificationBody({ type: 42 }), "New encrypted message");
});

test("validAdminUid rejects UIDs with other control characters", () => {
  assert.equal(pure.validAdminUid("a\tb"), false);   // tab (0x09)
  assert.equal(pure.validAdminUid("a\rb"), false);   // carriage return (0x0d)
  assert.equal(pure.validAdminUid("a\u001fb"), false); // unit separator (0x1f)
});

test("safeTokenEqual handles empty strings consistently", () => {
  assert.equal(pure.safeTokenEqual("", ""), true);
  assert.equal(pure.safeTokenEqual("", "x"), false);
  assert.equal(pure.safeTokenEqual("x", ""), false);
});

// ── timingSafeEqualHex — S07-H1 regression ────────────────────────────────────
// The finding: /mintToken verified account ownership with
// `if (storedHash && storedHash !== incomingHash) reject`, which fails OPEN.
// Any identity doc whose identityPubKeyHash was absent, empty, or not a string
// made the condition falsy, so the guard was skipped and a custom token was
// minted for that uid without the caller proving anything. These tests pin the
// replacement's behaviour: unusable input is never "equal".

test("timingSafeEqualHex matches identical digests", () => {
  const h = crypto.createHash("sha256").update("duoshield").digest("hex");
  assert.equal(pure.timingSafeEqualHex(h, h), true);
  assert.equal(pure.timingSafeEqualHex(h, h.toUpperCase()), true,
    "hex is case-insensitive: the same digest in either case must compare equal");
});

test("timingSafeEqualHex rejects differing digests of equal length", () => {
  const a = crypto.createHash("sha256").update("a").digest("hex");
  const b = crypto.createHash("sha256").update("b").digest("hex");
  assert.equal(pure.timingSafeEqualHex(a, b), false);
  // Single-nibble difference in the final position — the hardest case for any
  // early-exit comparison to get right.
  assert.equal(pure.timingSafeEqualHex(a, a.slice(0, -1) + (a.endsWith("0") ? "1" : "0")), false);
});

test("timingSafeEqualHex treats absent or non-string input as not-equal (fail closed)", () => {
  const h = crypto.createHash("sha256").update("x").digest("hex");
  // This is the exact S07-H1 exploit shape: a stored hash that is missing or of
  // the wrong type must NOT be treated as a pass.
  for (const bad of [undefined, null, "", 0, false, NaN, {}, [], Buffer.from(h, "hex")]) {
    assert.equal(pure.timingSafeEqualHex(bad, h), false, `stored=${String(bad)} must not authenticate`);
    assert.equal(pure.timingSafeEqualHex(h, bad), false, `incoming=${String(bad)} must not authenticate`);
  }
  // Both sides absent must also fail — "nothing equals nothing" would authorise
  // every caller against an empty identity record.
  assert.equal(pure.timingSafeEqualHex(undefined, undefined), false);
  assert.equal(pure.timingSafeEqualHex("", ""), false);
  assert.equal(pure.timingSafeEqualHex(null, null), false);
});

test("timingSafeEqualHex rejects length mismatches without throwing", () => {
  const h = crypto.createHash("sha256").update("y").digest("hex");
  // crypto.timingSafeEqual throws on unequal buffer lengths; the wrapper must
  // return false instead, or a truncated stored hash becomes a 500 (and an
  // information leak about which accounts are malformed).
  assert.equal(pure.timingSafeEqualHex(h.slice(0, 32), h), false);
  assert.equal(pure.timingSafeEqualHex(h, h + "00"), false);
});

test("timingSafeEqualHex rejects malformed hex instead of comparing a prefix", () => {
  // Buffer.from("zz…", "hex") does not throw — it stops at the first invalid
  // character and returns a SHORTER buffer. Without the decoded-length re-check,
  // a value like "zz" would decode to an empty buffer and could compare equal to
  // another unparsable value, so garbage would authenticate garbage.
  const bogus = "z".repeat(64);
  const real  = crypto.createHash("sha256").update("z").digest("hex");
  assert.equal(pure.timingSafeEqualHex(bogus, real), false);
  assert.equal(pure.timingSafeEqualHex(bogus, bogus), false,
    "two identical-but-invalid hex strings must not authenticate each other");
  // Valid hex prefix followed by invalid characters must not match the prefix.
  assert.equal(pure.timingSafeEqualHex(real.slice(0, 60) + "zzzz", real), false);
  // Odd-length input can never be a whole-byte digest.
  assert.equal(pure.timingSafeEqualHex("abc", "abd"), false);
});
