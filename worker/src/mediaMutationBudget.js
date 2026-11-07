// Global daily media-mutation budget for the free-tier deployment.
//
// This Durable Object is reached only through the Worker's private binding,
// after a valid per-object media capability has been verified. It is not
// exposed as a public route. A single named instance serializes reservations
// across edge locations, unlike Workers KV's eventually-consistent counters.
//
// The budget covers PUT and DELETE attempts (not GETs) and is intentionally
// conservative: rejected/failed attempts still consume a slot. This bounds
// the KV writes used by the existing per-object storage accounting and delete
// replay protection. The default is sized for the 10-user free-tier profile.

const STORAGE_KEY = "daily-media-mutations";
const DEFAULT_DAILY_LIMIT = 200;

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

    const limit = body.limit === undefined ? DEFAULT_DAILY_LIMIT : body.limit;
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 10_000) {
      return json({ error: "Invalid daily mutation limit" }, 400);
    }

    const { dayKey, resetAt } = utcDayAndReset(this.now());
    // Durable Object input/output gating serializes requests for this one
    // named instance. Keep the critical section to storage read/compute/write;
    // do not add outbound network I/O between these operations.
    const existing = await this.state.storage.get(STORAGE_KEY);
    const current = existing?.dayKey === dayKey && Number.isSafeInteger(existing.count)
      ? existing.count
      : 0;

    if (current >= limit) {
      return json({ allowed: false, count: current, limit, resetAt }, 429);
    }

    const count = current + 1;
    await this.state.storage.put(STORAGE_KEY, { dayKey, count });
    return json({ allowed: true, count, limit, remaining: limit - count, resetAt });
  }
}

export { DEFAULT_DAILY_LIMIT };
