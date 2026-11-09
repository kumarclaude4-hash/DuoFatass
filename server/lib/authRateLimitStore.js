"use strict";

const crypto = require("crypto");

const KEY_PREFIX = "duoshield:authrate:";
const DEFAULT_WINDOW_MS = 60_000;
const DEFAULT_MAX_LOCAL_ENTRIES = 20_000;

function durableKey(uid, endpoint) {
  if (typeof uid !== "string" || !uid || typeof endpoint !== "string" || !endpoint) {
    throw new TypeError("auth rate limiter requires a non-empty uid and endpoint");
  }
  // Do not send raw Firebase UIDs to the Durable Object as storage keys.
  const digest = crypto.createHash("sha256")
    .update(uid, "utf8")
    .update("\0", "utf8")
    .update(endpoint, "utf8")
    .digest("hex");
  return KEY_PREFIX + digest;
}

class AuthRateLimitUnavailableError extends Error {
  constructor(cause) {
    super("Shared authenticated API rate limiter is unavailable");
    this.name = "AuthRateLimitUnavailableError";
    this.code = "AUTH_RATE_LIMIT_UNAVAILABLE";
    this.status = 503;
    if (cause !== undefined) this.cause = cause;
  }
}

function createAuthRateLimitStore({
  client = null,
  requireDurable = false,
  windowMs = DEFAULT_WINDOW_MS,
  now = () => Date.now(),
  maxLocalEntries = DEFAULT_MAX_LOCAL_ENTRIES,
  onError = (op, err) => console.warn(`[authRateLimitStore] ${op} failed:`, err.message),
} = {}) {
  if (!Number.isSafeInteger(windowMs) || windowMs <= 0) {
    throw new TypeError("windowMs must be a positive safe integer");
  }
  if (!Number.isSafeInteger(maxLocalEntries) || maxLocalEntries < 1) {
    throw new TypeError("maxLocalEntries must be a positive safe integer");
  }

  const local = new Map();

  function trimLocal(nowMs) {
    const cutoff = nowMs - windowMs;
    for (const [key, record] of local) {
      if (record.windowStart <= cutoff) local.delete(key);
    }
    while (local.size > maxLocalEntries) {
      const oldest = local.keys().next().value;
      if (oldest === undefined) break;
      local.delete(oldest);
    }
  }

  function localCheck(key, limit) {
    const currentTime = now();
    trimLocal(currentTime);
    let record = local.get(key);
    if (!record || currentTime - record.windowStart >= windowMs) {
      record = { count: 1, windowStart: currentTime };
      local.delete(key);
      local.set(key, record);
    } else {
      record.count += 1;
      // Refresh insertion order so eviction is bounded LRU.
      local.delete(key);
      local.set(key, record);
    }
    trimLocal(currentTime);
    return {
      allowed: record.count <= limit,
      durable: false,
      count: record.count,
    };
  }

  return {
    durableEnabled: Boolean(client),

    /**
     * Records every authenticated request in the shared Durable Object when
     * configured. The DO's lock threshold is limit + 1 so the first 'limit'
     * requests are allowed and the following request is rejected.
     *
     * With requireDurable enabled, a missing or failed Worker is an explicit
     * availability error and never falls back to process-local state. The
     * bounded local fallback is only used by callers that opt out of strict
     * shared enforcement (local development/tests).
     */
    async check(uid, endpoint, limit) {
      if (!Number.isSafeInteger(limit) || limit < 1) {
        throw new TypeError("limit must be a positive safe integer");
      }
      const key = durableKey(uid, endpoint);
      if (!client) {
        if (requireDurable) throw new AuthRateLimitUnavailableError();
        return localCheck(key, limit);
      }
      try {
        const result = await client.recordFailure(key, {
          windowMs,
          maxFails: limit + 1,
        });
        if (!result || typeof result.locked !== "boolean" || typeof result.count !== "number") {
          throw new TypeError("Durable Object returned a malformed auth rate-limit result");
        }
        return {
          allowed: !result.locked,
          durable: true,
          count: result.count,
        };
      } catch (err) {
        onError("record", err);
        if (requireDurable) throw new AuthRateLimitUnavailableError(err);
      }
      return localCheck(key, limit);
    },
  };
}

module.exports = { createAuthRateLimitStore, durableKey, AuthRateLimitUnavailableError };
