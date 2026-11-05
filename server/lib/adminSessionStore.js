"use strict";

const crypto = require("node:crypto");

const DEFAULT_IDLE_TTL_MS = 30 * 60 * 1000;
const DEFAULT_ABSOLUTE_TTL_MS = 8 * 60 * 60 * 1000;
const UA_MAX_LEN = 200;
const SESSION_SCHEMA_VERSION = 1;

function normalizeUserAgent(ua) {
  if (ua === undefined) return undefined;
  if (ua === null) return null;
  return String(ua).slice(0, UA_MAX_LEN);
}

function defaultRandomId() {
  return crypto.randomBytes(32).toString("hex");
}

/**
 * Firestore-backed admin session ledger.
 *
 * Firestore is authoritative: no process-local cache participates in request
 * authorization. Session records are keyed by an HMAC of the random session
 * id, and contain no raw cookie/token or raw session id. A separate control
 * document holds a monotonically increasing generation. revokeAll() advances
 * it in one transaction; sessions from older generations immediately fail on
 * every server instance.
 *
 * validate() is asynchronous and must be awaited. Firestore errors are allowed
 * to propagate so the HTTP layer can fail closed with 503 rather than treating
 * an infrastructure failure as an invalid password/session.
 */
function createAdminSessionStore({
  firestore,
  idleTtlMs = DEFAULT_IDLE_TTL_MS,
  absoluteTtlMs = DEFAULT_ABSOLUTE_TTL_MS,
  secret,
  now = () => Date.now(),
  randomId = defaultRandomId,
  bindUserAgent = false,
  sessionsCollection = "adminSessions",
  controlCollection = "adminSessionControl",
} = {}) {
  if (!firestore || typeof firestore.runTransaction !== "function" || typeof firestore.collection !== "function") {
    throw new TypeError("createAdminSessionStore requires a Firestore-compatible instance");
  }
  if (!secret || !(Buffer.isBuffer(secret) || typeof secret === "string")) {
    throw new TypeError("createAdminSessionStore requires a non-empty signing secret");
  }
  if (!Number.isSafeInteger(idleTtlMs) || idleTtlMs <= 0 ||
      !Number.isSafeInteger(absoluteTtlMs) || absoluteTtlMs < idleTtlMs) {
    throw new TypeError("Admin session TTL values are invalid");
  }

  const signingKey = Buffer.isBuffer(secret) ? Buffer.from(secret) : Buffer.from(secret, "utf8");
  if (signingKey.length < 32) {
    throw new TypeError("Admin session signing secret must be at least 32 bytes");
  }

  const sessions = firestore.collection(sessionsCollection);
  const controlRef = firestore.collection(controlCollection).doc("global");

  function hmac(label, value) {
    return crypto.createHmac("sha256", signingKey)
      .update(label, "utf8")
      .update(value, "utf8")
      .digest("hex");
  }

  function signature(payload) {
    return hmac("duoshield-admin-session-token-v1:", payload);
  }

  function tagUserAgent(ua) {
    if (ua === null || ua === undefined || ua === "") return "-";
    return hmac("duoshield-admin-session-ua-v1:", ua).slice(0, 12);
  }

  function sessionDocumentId(sid) {
    return hmac("duoshield-admin-session-record-v1:", sid);
  }

  function parseToken(token) {
    if (typeof token !== "string" || token.length > 256) return null;
    const parts = token.split(".");
    if (parts.length !== 5) return null;
    const [sid, iatRaw, expRaw, uaTag, suppliedSignature] = parts;
    if (!/^[a-f0-9]{64}$/.test(sid) ||
        !/^[0-9a-z]+$/.test(iatRaw) ||
        !/^[0-9a-z]+$/.test(expRaw) ||
        !/^(?:-|[a-f0-9]{12})$/.test(uaTag) ||
        !/^[a-f0-9]{64}$/.test(suppliedSignature)) {
      return null;
    }

    const payload = [sid, iatRaw, expRaw, uaTag].join(".");
    const expected = signature(payload);
    if (!crypto.timingSafeEqual(Buffer.from(suppliedSignature, "hex"), Buffer.from(expected, "hex"))) {
      return null;
    }

    const issuedAtMs = parseInt(iatRaw, 36);
    const absoluteExpiresAtMs = parseInt(expRaw, 36);
    if (!Number.isSafeInteger(issuedAtMs) || !Number.isSafeInteger(absoluteExpiresAtMs) ||
        issuedAtMs <= 0 || absoluteExpiresAtMs <= issuedAtMs) {
      return null;
    }
    return { sid, issuedAtMs, absoluteExpiresAtMs, uaTag };
  }

  function invalid(reason) {
    return { valid: false, reason };
  }

  function readGeneration(data) {
    const generation = data && data.generation;
    if (!Number.isSafeInteger(generation) || generation < 0) {
      throw new Error("Admin session control document is missing a valid generation");
    }
    return generation;
  }

  async function create(ctx = {}) {
    const issuedAtMs = now();
    if (!Number.isSafeInteger(issuedAtMs) || issuedAtMs <= 0) {
      throw new TypeError("Admin session clock returned an invalid timestamp");
    }

    const sid = randomId();
    if (typeof sid !== "string" || !/^[a-f0-9]{64}$/.test(sid)) {
      throw new TypeError("Admin session id generator must return 32-byte lowercase hex");
    }

    const absoluteExpiresAtMs = issuedAtMs + absoluteTtlMs;
    if (!Number.isSafeInteger(absoluteExpiresAtMs)) {
      throw new TypeError("Admin session expiry exceeds the safe timestamp range");
    }

    const userAgent = normalizeUserAgent(ctx.userAgent === undefined ? null : ctx.userAgent);
    const uaTag = tagUserAgent(userAgent);
    const tokenPayload = [
      sid,
      issuedAtMs.toString(36),
      absoluteExpiresAtMs.toString(36),
      uaTag,
    ].join(".");
    const token = tokenPayload + "." + signature(tokenPayload);
    const sessionRef = sessions.doc(sessionDocumentId(sid));

    await firestore.runTransaction(async (tx) => {
      const controlSnap = await tx.get(controlRef);
      let generation = 0;
      if (controlSnap.exists) {
        generation = readGeneration(controlSnap.data());
      } else {
        tx.create(controlRef, {
          generation: 0,
          updatedAtMs: issuedAtMs,
          schemaVersion: SESSION_SCHEMA_VERSION,
        });
      }

      tx.create(sessionRef, {
        schemaVersion: SESSION_SCHEMA_VERSION,
        generation,
        issuedAtMs,
        idleExpiresAtMs: Math.min(issuedAtMs + idleTtlMs, absoluteExpiresAtMs),
        absoluteExpiresAtMs,
        lastSeenAtMs: issuedAtMs,
        userAgentTag: uaTag,
        purgeAt: new Date(absoluteExpiresAtMs),
      });
    });

    return token;
  }

  async function validate(token, ctx = {}, opts = {}) {
    if (!token) return invalid("missing");
    const parsed = parseToken(token);
    if (!parsed) return invalid("not_found");

    const checkedAtMs = now();
    if (!Number.isSafeInteger(checkedAtMs)) {
      throw new Error("Admin session clock returned an invalid timestamp");
    }
    if (checkedAtMs >= parsed.absoluteExpiresAtMs) return invalid("absolute_expired");

    if (bindUserAgent && ctx.userAgent !== undefined) {
      const currentTag = tagUserAgent(normalizeUserAgent(ctx.userAgent));
      if (currentTag !== parsed.uaTag) return invalid("ua_mismatch");
    }

    const sessionRef = sessions.doc(sessionDocumentId(parsed.sid));
    const refresh = opts.refresh !== false;

    return firestore.runTransaction(async (tx) => {
      const snapshots = await Promise.all([
        tx.get(sessionRef),
        tx.get(controlRef),
      ]);
      const sessionSnap = snapshots[0];
      const controlSnap = snapshots[1];

      if (!sessionSnap.exists || !controlSnap.exists) return invalid("not_found");

      const controlData = controlSnap.data();
      const sessionData = sessionSnap.data();
      const generation = readGeneration(controlData);

      if (sessionData.schemaVersion !== SESSION_SCHEMA_VERSION ||
          sessionData.generation !== generation ||
          sessionData.issuedAtMs !== parsed.issuedAtMs ||
          sessionData.absoluteExpiresAtMs !== parsed.absoluteExpiresAtMs ||
          sessionData.userAgentTag !== parsed.uaTag) {
        return invalid("not_found");
      }

      if (checkedAtMs >= sessionData.absoluteExpiresAtMs) {
        tx.delete(sessionRef);
        return invalid("absolute_expired");
      }

      const idleExpiresAtMs = sessionData.idleExpiresAtMs;
      if (!Number.isSafeInteger(idleExpiresAtMs)) {
        tx.delete(sessionRef);
        return invalid("corrupt_session");
      }
      if (checkedAtMs >= idleExpiresAtMs) {
        tx.delete(sessionRef);
        return invalid("idle_expired");
      }

      if (bindUserAgent && ctx.userAgent !== undefined &&
          tagUserAgent(normalizeUserAgent(ctx.userAgent)) !== sessionData.userAgentTag) {
        return invalid("ua_mismatch");
      }

      if (refresh) {
        const nextIdleExpiresAtMs = Math.min(checkedAtMs + idleTtlMs, sessionData.absoluteExpiresAtMs);
        if (nextIdleExpiresAtMs > idleExpiresAtMs) {
          tx.update(sessionRef, {
            idleExpiresAtMs: nextIdleExpiresAtMs,
            lastSeenAtMs: checkedAtMs,
          });
        }
      }

      return { valid: true };
    });
  }

  async function revoke(token) {
    const parsed = parseToken(token);
    if (!parsed) return false;

    const sessionRef = sessions.doc(sessionDocumentId(parsed.sid));
    return firestore.runTransaction(async (tx) => {
      const snap = await tx.get(sessionRef);
      if (!snap.exists) return false;
      tx.delete(sessionRef);
      return true;
    });
  }

  async function revokeAll() {
    const changedAtMs = now();
    return firestore.runTransaction(async (tx) => {
      const snap = await tx.get(controlRef);
      const currentGeneration = snap.exists ? readGeneration(snap.data()) : 0;
      if (currentGeneration >= Number.MAX_SAFE_INTEGER) {
        throw new Error("Admin session generation exhausted");
      }
      const generation = currentGeneration + 1;
      tx.set(controlRef, {
        generation,
        updatedAtMs: changedAtMs,
        schemaVersion: SESSION_SCHEMA_VERSION,
      });
      return { generation };
    });
  }

  // Best-effort physical cleanup only. Expiry and revocation are enforced by
  // validate(), never by this sweeper or Firestore TTL timing.
  async function sweep(maxDocs = 200) {
    const limit = Number.isSafeInteger(maxDocs) ? Math.max(1, Math.min(maxDocs, 500)) : 200;
    const snapshot = await sessions.where("purgeAt", "<=", new Date(now())).limit(limit).get();
    let removed = 0;
    for (const doc of snapshot.docs) {
      await doc.ref.delete();
      removed++;
    }
    return removed;
  }

  return Object.freeze({
    create,
    validate,
    revoke,
    revokeAll,
    sweep,
    parseToken,
    sessionDocumentId,
  });
}

module.exports = {
  createAdminSessionStore,
  normalizeUserAgent,
};
