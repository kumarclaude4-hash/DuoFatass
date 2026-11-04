"use strict";

// Pure validation and transformation helpers for server-managed Signal pre-key bundles.
// These functions deliberately do not perform I/O so their security invariants can
// be exercised with the Node built-in test runner.

const MAX_ONE_TIME_PREKEYS = 100;
const MAX_PREKEY_ID = 0xFFFFFF;
const BASE64_RE = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

function isBase64(value, minBytes, maxBytes) {
  if (typeof value !== "string" || value.length === 0
      || value.length < Math.ceil(minBytes * 4 / 3) - 4
      || value.length > Math.ceil(maxBytes * 4 / 3) + 8) {
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
    && isBase64(entry.signature, 64, 64)
    && Number.isSafeInteger(entry.timestamp) && entry.timestamp > 0
    && Object.keys(entry).every(key =>
      key === "id" || key === "publicKey" || key === "signature" || key === "timestamp");
}

function sameSignedPreKey(current, next) {
  return !!current && !!next && current.id === next.id
    && current.publicKey === next.publicKey
    && current.signature === next.signature
    && current.timestamp === next.timestamp;
}

function validKyberPreKey(entry) {
  // DuoShield currently generates libsignal KYBER_1024 public keys. Its
  // serialized KEMPublicKey is 1568 bytes; keep the accepted value exact.
  return !!entry && typeof entry === "object" && !Array.isArray(entry)
    && validId(entry.id)
    && isBase64(entry.publicKey, 1568, 1568)
    && isBase64(entry.signature, 64, 64)
    && Number.isSafeInteger(entry.timestamp) && entry.timestamp > 0
    && Object.keys(entry).every(key =>
      key === "id" || key === "publicKey" || key === "signature" || key === "timestamp");
}

function sameKyberPreKey(current, next) {
  return !!current && !!next && current.id === next.id
    && current.publicKey === next.publicKey
    && current.signature === next.signature
    && current.timestamp === next.timestamp;
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

function getPreKeyHighWaterMark(entries) {
  if (!Array.isArray(entries) || entries.length === 0) return null;
  // The pool is ordered oldest-to-newest: claims remove from the front and
  // appended batches are added at the end. The last entry is the legacy
  // high-water mark for bundles that predate the server-owned marker.
  return entries[entries.length - 1].id;
}

function preKeyForwardDistance(id, highWaterMark) {
  // IDs are generated in the inclusive 1..0xFFFFFF range and wrap to 1.
  return (id - highWaterMark + MAX_PREKEY_ID) % MAX_PREKEY_ID;
}

function previousPreKeyId(firstId) {
  if (!validId(firstId)) throw new TypeError("Invalid first one-time pre-key ID");
  return firstId <= 1 ? MAX_PREKEY_ID : firstId - 1;
}

function appendPreKeysWithHighWaterMark(
  existing,
  additions,
  max = MAX_ONE_TIME_PREKEYS,
  highWaterMark = null,
  firstId = null
) {
  if (!validPreKeyList(existing, max) || !validPreKeyList(additions, max)) {
    throw new TypeError("Invalid one-time pre-key list");
  }

  let mark = validId(highWaterMark) ? highWaterMark : getPreKeyHighWaterMark(existing);
  if (mark === null && additions.length > 0) {
    if (!validId(firstId) || firstId !== additions[0].id) {
      const error = new TypeError("Server pre-key high-water mark missing; first batch ID required");
      error.code = "PREKEY_HIGH_WATER_MARK_REQUIRED";
      throw error;
    }
    mark = previousPreKeyId(firstId);
  }

  const activeById = new Map(existing.map(entry => [entry.id, entry]));
  const newCandidates = [];
  for (const entry of additions) {
    const active = activeById.get(entry.id);
    if (active) {
      if (active.publicKey !== entry.publicKey) {
        throw new TypeError("Pre-key ID already exists with different public key");
      }
      continue;
    }
    if (mark === null) {
      const error = new TypeError("Cannot append pre-keys without a trustworthy high-water mark");
      error.code = "PREKEY_HIGH_WATER_MARK_REQUIRED";
      throw error;
    }
    const distance = preKeyForwardDistance(entry.id, mark);
    if (distance === 0 || distance > Math.floor(MAX_PREKEY_ID / 2)) continue;
    newCandidates.push({ entry, distance });
  }

  newCandidates.sort((a, b) => a.distance - b.distance);
  const appended = [];
  for (const candidate of newCandidates) {
    const entry = candidate.entry;
    const active = activeById.get(entry.id);
    if (active) {
      if (active.publicKey !== entry.publicKey) {
        throw new TypeError("Pre-key ID already exists with different public key");
      }
      continue;
    }
    const distance = preKeyForwardDistance(entry.id, mark);
    if (distance === 0 || distance > Math.floor(MAX_PREKEY_ID / 2)) continue;
    activeById.set(entry.id, entry);
    appended.push(entry);
    mark = entry.id;
  }

  if (existing.length + appended.length > max) {
    throw new RangeError("One-time pre-key pool limit exceeded");
  }
  return { oneTimePreKeys: existing.concat(appended), highWaterMark: mark };
}

function appendPreKeys(existing, additions, max = MAX_ONE_TIME_PREKEYS) {
  return appendPreKeysWithHighWaterMark(existing, additions, max).oneTimePreKeys;
}

function validStoredBundle(bundle) {
  return !!bundle && typeof bundle === "object" && !Array.isArray(bundle)
    && isBase64(bundle.identityKey, 33, 33)
    && Number.isInteger(bundle.registrationId)
    && bundle.registrationId >= 1 && bundle.registrationId <= 16383
    && validSignedPreKey(bundle.signedPreKey)
    && (bundle.kyberPreKey === undefined || validKyberPreKey(bundle.kyberPreKey))
    && validPreKeyList(bundle.oneTimePreKeys);
}

function claimOneTimePreKey(bundle) {
  if (!validStoredBundle(bundle)) {
    throw new TypeError("Invalid Signal key bundle");
  }
  const keys = bundle.oneTimePreKeys.slice();
  const claimed = keys.length ? keys.shift() : null;
  const remaining = { ...bundle, oneTimePreKeys: keys };
  const response = { ...bundle, oneTimePreKeys: claimed ? [claimed] : [] };
  return { remaining, response, claimed };
}

module.exports = {
  MAX_ONE_TIME_PREKEYS,
  validPublicPreKey,
  validSignedPreKey,
  sameSignedPreKey,
  validKyberPreKey,
  sameKyberPreKey,
  validPreKeyList,
  validInitialBundle,
  validStoredBundle,
  appendPreKeys,
  appendPreKeysWithHighWaterMark,
  getPreKeyHighWaterMark,
  previousPreKeyId,
  claimOneTimePreKey,
};
