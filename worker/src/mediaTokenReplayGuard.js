// Atomic single-use claims for irreversible media DELETE capabilities.
//
// A KV GET followed by PUT is not an atomic compare-and-set: two edge
// requests can both observe an unused jti and both proceed to delete. Each
// token is routed to one of 64 Durable Object shards by its first base64url
// character. Cloudflare serializes requests to one DO instance, so the
// read/check/write below is a single ordered claim for that shard.
//
// Only opaque random jtis and expiry timestamps are stored. No object keys,
// user IDs, bearer tokens, or media contents enter this namespace.

const KEY_PREFIX = "used:";
const JTI_PATTERN = /^[A-Za-z0-9_-]{12}$/;
const MAX_TTL_SECONDS = 3600;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

export class MediaTokenReplayGuard {
  constructor(state, env, { now = () => Date.now() } = {}) {
    this.state = state;
    this.env = env;
    this.now = now;
  }

  async fetch(request) {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/consume") {
      return json({ error: "Not found" }, 404);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "Malformed request body" }, 400);
    }

    const jti = body && body.jti;
    const ttlSeconds = body && body.ttlSeconds;
    if (typeof jti !== "string" || !JTI_PATTERN.test(jti) ||
        !Number.isInteger(ttlSeconds) || ttlSeconds < 1 ||
        ttlSeconds > MAX_TTL_SECONDS) {
      return json({ error: "Invalid replay claim" }, 400);
    }

    const now = this.now();
    const expiresAt = now + ttlSeconds * 1000;
    const key = KEY_PREFIX + jti;

    // Do not add unrelated network I/O between this storage read and write.
    // Requests to this Durable Object instance are serialized by the runtime.
    const existingExpiry = await this.state.storage.get(key);
    if (Number.isSafeInteger(existingExpiry) && existingExpiry > now) {
      return json({ consumed: false });
    }

    await this.state.storage.put(key, expiresAt);

    // Keep the earliest expiry scheduled; alarm() prunes all expired entries
    // and reschedules for the next live claim. The storage entry itself carries
    // its expiry, so an alarm failure cannot turn a used token into an unused one.
    const currentAlarm = await this.state.storage.getAlarm();
    if (currentAlarm === null || currentAlarm > expiresAt) {
      await this.state.storage.setAlarm(expiresAt);
    }

    return json({ consumed: true, expiresAt });
  }

  async alarm() {
    const now = this.now();
    const entries = await this.state.storage.list({ prefix: KEY_PREFIX });
    let nextExpiry = Number.POSITIVE_INFINITY;

    for (const [key, expiry] of entries) {
      if (!Number.isSafeInteger(expiry) || expiry <= now) {
        await this.state.storage.delete(key);
      } else {
        nextExpiry = Math.min(nextExpiry, expiry);
      }
    }

    if (Number.isFinite(nextExpiry)) {
      await this.state.storage.setAlarm(nextExpiry);
    } else {
      await this.state.storage.deleteAlarm();
    }
  }
}
