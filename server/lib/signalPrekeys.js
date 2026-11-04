"use strict";

// Pure validation and transformation helpers for server-managed Signal pre-key bundles.
// These functions deliberately do not perform I/O so their security invariants can
// be exercised with the Node built-in test runner.

const MAX_ONE_TIME_PREKEYS = 100;
const MAX_PREKEY_ID = 0xFFFFFF;
const BASE64_RE = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

function isBase64(value, minBytes, maxBytes) {
  if (typeof value !== "string" || value.length === 0 || value.length > Math.ceil(maxBytes * 4 / 3) + 8) {
    return false;
  }
  if (!BASE64_RE.test(value)) return false;
  const decoded = Buffer.from(value, "base64");
  if (decoded.length < minBytes || decoded.length > maxBytes) return false;
  // Buffer.from() is permissive; require a canonical encoding.
  return decoded.toString("base64") === value;
}

function validId(value) {
  return Number.isInteger(value) && value >= 0 && value <= MAX_PREKEY_ID;
}

function validPublicPreKey(entry) {
  return !!entry && typeof entry === "object" && !Array.isArray(entry)
    && validId(entry.id)
    && isBase64(entry.publicKey, 33, 33)
    && Object.keys(entry).every(key => key === "id" || key === "publicKey");
}

function validSignedPreKey(entry) {
  return !!entry && typeof entry === "object" && !Array.isArray(entry)
    && validId(entry.id)
    && isBase64(entry.publicKey, 33, 33)
    && isBase64(entry.signature, 64, 128)
    && Number.isSafeInteger(entry.timestamp) && entry.timestamp > 0
    && Object.keys(entry).every(key =>
      key === "id" || key === "publicKey" || key === "signature" || key === "timestamp");
}

function validKyberPreKey(entry) {
  // libsignal Kyber-1024 public-key/signature encodings are kept opaque here;
  // enforce bounded canonical base64 and the fixed schema, with stricter
  // cryptographic signature verification performed by the client protocol.
  return !!entry && typeof entry === "object" && !Array.isArray(entry)
    && validId(entry.id)
    && isBase64(entry.publicKey, 1000, 5000)
    && isBase64(entry.signature, 64, 128)
    && Number.isSafeInteger(entry.timestamp) && entry.timestamp > 0
    && Object.keys(entry).every(key =>
      key === "id" || key === "publicKey" || key === "signature" || key === "timestamp");
}

function validPreKeyList(entries, max = MAX_ONE_TIME_PREKEYS) {
  if (!Array.isArray(entries) || entries.length > max) return false;
  const ids = new Set();
  for (const entry of entries) {
    if (!validPublicPreKey(entry) || ids.has(entry.id)) return false;
    ids.add(entry.id);
  }
  return true;
}

function validInitialBundle(bundle) {
  if (!bundle || typeof bundle !== "object" || Array.isArray(bundle)) return false;
  const allowed = new Set([
    "identityKey", "registrationId", "signedPreKey",
    "oneTimePreKeys", "kyberPreKey"
  ]);
  if (!Object.keys(bundle).every(key => allowed.has(key))) return false;
  if (!isBase64(bundle.identityKey, 33, 33)) return false;
  if (!Number.isInteger(bundle.registrationId)
      || bundle.registrationId < 1 || bundle.registrationId > 16383) return false;
  if (!validSignedPreKey(bundle.signedPreKey)) return false;
  if (!validPreKeyList(bundle.oneTimePreKeys)) return false;
  if (bundle.kyberPreKey !== undefined && !validKyberPreKey(bundle.kyberPreKey)) return false;
  return true;
}

function appendPreKeys(existing, additions, max = MAX_ONE_TIME_PREKEYS) {
  if (!validPreKeyList(existing, max) || !validPreKeyList(additions, max)) {
    throw new TypeError("Invalid one-time pre-key list");
  }
  const byId = new Map(existing.map(entry => [entry.id, entry]));
  for (const entry of additions) {
    const current = byId.get(entry.id);
    if (current && current.publicKey !== entry.publicKey) {
      throw new TypeError("Pre-key ID already exists with different public key");
    }
    byId.set(entry.id, entry);
  }
  const result = Array.from(byId.values()).sort((a, b) => a.id - b.id);
  if (result.length > max) throw new RangeError("One-time pre-key pool limit exceeded");
  return result;
}

function claimOneTimePreKey(bundle) {
  if (!bundle || typeof bundle !== "object" || Array.isArray(bundle)) {
    throw new TypeError("Invalid Signal key bundle");
  }
  const keys = Array.isArray(bundle.oneTimePreKeys) ? bundle.oneTimePreKeys.slice() : [];
  if (!validPreKeyList(keys)) throw new TypeError("Stored Signal pre-key pool is invalid");
  const claimed = keys.length ? keys.shift() : null;
  const remaining = { ...bundle, oneTimePreKeys: keys };
  const response = { ...bundle, oneTimePreKeys: claimed ? [claimed] : [] };
  return { remaining, response, claimed };
}

module.exports = {
  MAX_ONE_TIME_PREKEYS,
  validPublicPreKey,
  validSignedPreKey,
  validKyberPreKey,
  validPreKeyList,
  validInitialBundle,
  appendPreKeys,
  claimOneTimePreKey,
};
