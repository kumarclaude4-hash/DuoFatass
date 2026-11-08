"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const invite = require("./invite");

const NOW = Date.UTC(2026, 0, 1);
const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

test("created invite tokens have the expected high-entropy opaque format", () => {
  const first = invite.createInviteToken();
  const second = invite.createInviteToken();
  assert.match(first, /^dsi_[0-9a-f]{64}$/);
  assert.match(second, /^dsi_[0-9a-f]{64}$/);
  assert.notEqual(first, second);
});

test("token normalization is case-insensitive and trims whitespace", () => {
  const token = invite.createInviteToken();
  assert.equal(invite.normalizeInviteToken(`  ${token.toUpperCase()}  `), token);
  assert.equal(invite.isInviteToken(`  ${token.toUpperCase()}  `), true);
  assert.equal(invite.normalizeInviteToken(null), "");
  assert.equal(invite.isInviteToken("not-an-invite"), false);
});

test("hash and fingerprint are deterministic without storing the raw token", () => {
  const token = invite.createInviteToken();
  const hash = invite.hashInviteToken(token);
  assert.match(hash, /^[0-9a-f]{64}$/);
  assert.equal(invite.hashInviteToken(` ${token.toUpperCase()} `), hash);
  assert.equal(invite.tokenFingerprint(token), hash.slice(0, 12));
  assert.notEqual(hash, token);
  assert.throws(() => invite.hashInviteToken("invalid"), TypeError);
});

test("invite status distinguishes active, expired, used, and revoked states", () => {
  assert.equal(invite.inviteStatus(null, NOW), "invalid");
  assert.equal(invite.inviteStatus({ status: "revoked", expiresAt: new Date(NOW + DAY) }, NOW), "revoked");
  assert.equal(invite.inviteStatus({ status: "used", expiresAt: new Date(NOW - DAY) }, NOW), "used");
  assert.equal(invite.inviteStatus({ status: "active", expiresAt: new Date(NOW + 1) }, NOW), "active");
  assert.equal(invite.inviteStatus({ status: "active", expiresAt: new Date(NOW) }, NOW), "expired");
  assert.equal(invite.inviteStatus({ status: "active", expiresAt: new Date(NOW - 1) }, NOW), "expired");
});

test("unknown or malformed persisted invite state fails closed", () => {
  const future = new Date(NOW + DAY);
  assert.equal(invite.inviteStatus({ status: "pending", expiresAt: future }, NOW), "invalid");
  assert.equal(invite.inviteStatus({ status: "approved", expiresAt: future }, NOW), "invalid");
  assert.equal(invite.inviteStatus({ expiresAt: future }, NOW), "invalid");
  assert.equal(invite.inviteStatus({ status: "active" }, NOW), "invalid");
  assert.equal(invite.inviteStatus({ status: "active", expiresAt: Infinity }, NOW), "invalid");
  assert.equal(invite.inviteStatus({ status: "active", expiresAt: "not-a-date" }, NOW), "invalid");
  assert.equal(invite.inviteStatus({ status: "active", expiresAt: future }, NaN), "invalid");
  assert.equal(invite.inviteStatus({ status: "active", expiresAt: future }, NOW), "active");
});

test("create input trims fields and accepts the exact one-hour and 90-day TTL boundaries", () => {
  const input = { label: "  Beta Cohort  ", notes: "  invited by ops  ", expiresAt: new Date(NOW + HOUR).toISOString() };
  assert.deepEqual(invite.validateCreateInput(input, NOW), {
    label: "Beta Cohort",
    labelSearch: "beta cohort",
    notes: "invited by ops",
    expiresAtMs: NOW + HOUR,
  });

  const max = invite.validateCreateInput({
    label: "Max TTL",
    expiresAt: new Date(NOW + 90 * DAY).toISOString(),
  }, NOW);
  assert.equal(max.expiresAtMs, NOW + 90 * DAY);
});

test("create input rejects invalid labels, oversized notes, and out-of-range expiries", () => {
  assert.throws(() => invite.validateCreateInput({ label: " ", expiresAt: new Date(NOW + HOUR).toISOString() }, NOW), TypeError);
  assert.throws(() => invite.validateCreateInput({ label: "x".repeat(invite.LABEL_MAX + 1), expiresAt: new Date(NOW + HOUR).toISOString() }, NOW), TypeError);
  assert.throws(() => invite.validateCreateInput({ label: "valid", notes: "x".repeat(invite.NOTES_MAX + 1), expiresAt: new Date(NOW + HOUR).toISOString() }, NOW), TypeError);
  assert.throws(() => invite.validateCreateInput({ label: "valid", expiresAt: "not-a-date" }, NOW), TypeError);
  assert.throws(() => invite.validateCreateInput({ label: "valid", expiresAt: new Date(NOW + HOUR - 1).toISOString() }, NOW), TypeError);
  assert.throws(() => invite.validateCreateInput({ label: "valid", expiresAt: new Date(NOW + 90 * DAY + 1).toISOString() }, NOW), TypeError);
});

test("pagination cursor round-trips and rejects malformed cursor payloads", () => {
  const cursor = invite.encodeCursor(123456789, "invite_123");
  assert.deepEqual(invite.decodeCursor(cursor), {
    createdAtMs: 123456789,
    id: "invite_123",
  });
  assert.equal(invite.decodeCursor(""), null);
  assert.throws(() => invite.decodeCursor("not-json"), TypeError);
  assert.throws(() => invite.encodeCursor(NaN, "invite_123"), TypeError);
  assert.throws(() => invite.encodeCursor(1, ""), TypeError);
});

test("timestamp conversion handles Firestore timestamps, Dates, numbers, and ISO strings", () => {
  const timestamp = { toMillis: () => NOW };
  assert.equal(invite.timestampMs(timestamp), NOW);
  assert.equal(invite.timestampMs(new Date(NOW)), NOW);
  assert.equal(invite.timestampMs(NOW), NOW);
  assert.equal(invite.timestampMs(new Date(NOW).toISOString()), NOW);
  assert.equal(invite.timestampMs(null), 0);
});
