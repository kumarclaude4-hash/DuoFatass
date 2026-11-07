// Global daily media upload and deletion budgets for the free-tier deployment.
//
// This Durable Object is reached only through the Worker's private binding,
// after a valid per-object media capability has been verified. It is not
// exposed as a public route. A single named instance serializes reservations
// across edge locations, unlike Workers KV's eventually-consistent counters.
//
// Upload and DELETE attempts have independent caps (GETs are not counted).
// Rejected/failed attempts consume a slot deliberately. Separate budgets
// ensure upload traffic cannot starve privacy/expiry cleanup. Each bucket is
// global across all users and resets at 00:00 UTC.

const STORAGE_KEY = "daily-media-mutations";
const DEFAULT_DAILY_UPLOAD_LIMIT = 120;
const DEFAULT_DAILY_DELETE_LIMIT = 120;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function utcDayAndReset(now) {
  const date = new Date(now);
  const dayKey = date.toISOString().slice(0, 10);
  const resetAt = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() + 1);
  return { dayKey, resetAt };
}

export class MediaMutationBudget {
  constructor(state, env, { now = () => Date.now() } = {}) {
    this.state = state;
    this.env = env;
    this.now = now;
  }

  async fetch(request) {
    if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "Malformed request body" }, 400);
    }
    if (!body || typeof body !== "object" || body.action !== "reserve") {
      return json({ error: "Invalid reservation request" }, 400);
    }

    const operation = body.operation;
    if (operation !== "upload" && operation !== "delete") {
      return json({ error: "Invalid media mutation operation" }, 400);
    }
    const defaultLimit = operation === "upload"
      ? DEFAULT_DAILY_UPLOAD_LIMIT : DEFAULT_DAILY_DELETE_LIMIT;
    const limit = body.limit === undefined ? defaultLimit : body.limit;
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 10_000) {
      return json({ error: "Invalid daily media operation limit" }, 400);
    }

    const { dayKey, resetAt } = utcDayAndReset(this.now());
    // Durable Object input/output gating serializes requests for this one
    // named instance. Separate buckets mean upload volume cannot consume the
    // allowance reserved for privacy and expiry cleanup.
    const existing = await this.state.storage.get(STORAGE_KEY);
    const legacyCount = existing?.dayKey === dayKey && Number.isSafeInteger(existing.count)
      ? Math.max(0, existing.count) : 0;
    const current = existing?.dayKey === dayKey ? existing : { dayKey, uploads: 0, deletes: 0 };
    const countKey = operation === "upload" ? "uploads" : "deletes";
    const count = Number.isSafeInteger(current[countKey]) && current[countKey] >= 0
      ? current[countKey] : legacyCount;

    if (count >= limit) {
      return json({ allowed: false, operation, count, limit, resetAt }, 429);
    }

    const nextCount = count + 1;
    const next = {
      dayKey,
      uploads: countKey === "uploads" ? nextCount : (current.uploads || legacyCount),
      deletes: countKey === "deletes" ? nextCount : (current.deletes || legacyCount),
    };
    await this.state.storage.put(STORAGE_KEY, next);
    return json({
      allowed: true,
      operation,
      count: nextCount,
      limit,
      remaining: limit - nextCount,
      resetAt,
    });
  }
}

export { DEFAULT_DAILY_UPLOAD_LIMIT, DEFAULT_DAILY_DELETE_LIMIT };
