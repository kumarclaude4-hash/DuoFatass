"use strict";

const crypto = require("crypto");

const TOKEN_PREFIX = "dsi_";
const TOKEN_PATTERN = /^dsi_[0-9a-f]{64}$/;
const LABEL_MAX = 80;
const NOTES_MAX = 500;
const MIN_TTL_MS = 60 * 60 * 1000;
const MAX_TTL_MS = 90 * 24 * 60 * 60 * 1000;

function createInviteToken() {
  return TOKEN_PREFIX + crypto.randomBytes(32).toString("hex");
}

function normalizeInviteToken(value) {
  return typeof value === "string" ? value.trim().toLowerCase() : "";
}

function isInviteToken(value) {
  return TOKEN_PATTERN.test(normalizeInviteToken(value));
}

function hashInviteToken(value) {
  const token = normalizeInviteToken(value);
  if (!isInviteToken(token)) throw new TypeError("Invalid invite token");
  return crypto.createHash("sha256").update(token, "utf8").digest("hex");
}

function tokenFingerprint(value) {
  const hash = hashInviteToken(value);
  return hash.slice(0, 12);
}

function timestampMs(value) {
  if (!value) return 0;
  if (typeof value.toMillis === "function") return value.toMillis();
  if (value instanceof Date) return value.getTime();
  if (typeof value === "number") return value;
  return Date.parse(value) || 0;
}

function inviteStatus(data, nowMs = Date.now()) {
  // Only an explicit active state with a valid future expiry may authorize
  // onboarding. Unknown or malformed persisted states fail closed.
  if (!data || typeof data !== "object" || Array.isArray(data)) return "invalid";
  if (data.status === "revoked") return "revoked";
  if (data.status === "used") return "used";
  if (data.status !== "active" || !Number.isFinite(nowMs)) return "invalid";

  let expiresAtMs;
  try {
    expiresAtMs = timestampMs(data.expiresAt);
  } catch {
    return "invalid";
  }
  if (!Number.isFinite(expiresAtMs) || expiresAtMs <= 0) return "invalid";
  return expiresAtMs <= nowMs ? "expired" : "active";
}

function validateCreateInput(input, nowMs = Date.now()) {
  const label = typeof input?.label === "string" ? input.label.trim() : "";
  const notes = typeof input?.notes === "string" ? input.notes.trim() : "";
  const expiresAtMs = Date.parse(input?.expiresAt || "");
  if (!label || label.length > LABEL_MAX) {
    throw new TypeError(`Label must be 1-${LABEL_MAX} characters`);
  }
  if (notes.length > NOTES_MAX) {
    throw new TypeError(`Notes must be at most ${NOTES_MAX} characters`);
  }
  if (!Number.isFinite(expiresAtMs) || expiresAtMs < nowMs + MIN_TTL_MS || expiresAtMs > nowMs + MAX_TTL_MS) {
    throw new TypeError("Expiry must be between 1 hour and 90 days from now");
  }
  return { label, labelSearch: label.toLowerCase(), notes, expiresAtMs };
}

function encodeCursor(createdAtMs, id) {
  if (!Number.isFinite(createdAtMs) || typeof id !== "string" || !id) throw new TypeError("Invalid cursor");
  return Buffer.from(JSON.stringify([createdAtMs, id]), "utf8").toString("base64url");
}

function decodeCursor(value) {
  if (!value) return null;
  try {
    const parsed = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    if (!Array.isArray(parsed) || parsed.length !== 2 || !Number.isFinite(parsed[0]) || typeof parsed[1] !== "string" || !parsed[1]) {
      throw new Error("shape");
    }
    return { createdAtMs: parsed[0], id: parsed[1] };
  } catch {
    throw new TypeError("Invalid cursor");
  }
}

module.exports = {
  LABEL_MAX,
  NOTES_MAX,
  createInviteToken,
  normalizeInviteToken,
  isInviteToken,
  hashInviteToken,
  tokenFingerprint,
  timestampMs,
  inviteStatus,
  validateCreateInput,
  encodeCursor,
  decodeCursor,
};
