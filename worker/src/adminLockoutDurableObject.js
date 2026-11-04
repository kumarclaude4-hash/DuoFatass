// ── AdminLockoutCounter: atomic admin brute-force lockout counter ───────────
//
// WHY THIS EXISTS (follow-up to the 2026-08-14 Upstash → Cloudflare KV
// migration; see server/lib/adminLockoutStore.js's "MIGRATION" note and
// BUG_TRACKER.md's S04-L3 row for the full history)
//
// The first Cloudflare KV version of this counter used a plain
// GET → increment → PUT against KV's REST API, which has no INCR/CAS
// equivalent. That was flagged as a real race — not a theoretical one:
// concurrent failed admin-login requests can each read the same
// pre-increment count and each write count+1, so the lockout counter
// UNDERCOUNTS under concurrency. For a brute-force gate specifically, that
// is not an acceptable tradeoff to wave away by analogy to this repo's other
// (non-security) best-effort KV counters — it needs an actual fix, not a
// bigger comment.
//
// THE FIX: a Durable Object.
//
// Cloudflare Durable Objects guarantee that all requests routed to the SAME
// object instance are processed one at a time — the runtime's input/output
// gating means a `fetch()` handler's storage reads and writes cannot
// interleave with another concurrent `fetch()` call to that same instance,
// with no manual locking required. (See Cloudflare's Durable Objects docs:
// "Requests to the same Durable Object are delivered in order and processed
// one at a time.") Every opaque rate-limit bucket key (normalized IP for the
// admin gate, or a hashed UID+endpoint for authenticated API limits) maps to
// exactly one Durable Object instance (`idFromName(key)`). Incrementing that
// instance's counter is therefore a true atomic operation: concurrent
// requests for the same bucket are serialized, never collapsed into one.
//
// This is available on the Cloudflare Workers FREE plan as of the SQLite
// storage backend (`new_sqlite_classes` migration in wrangler.jsonc) — no
// paid plan is required, contrary to this repo's older assumption (see the
// superseded comment this replaces in adminLockoutStore.js and the Worker's
// S03-I2 "no Durable Objects" note, which predates this fix and no longer
// applies to this one counter).
//
// SCOPE: this Durable Object provides atomic, expiring fixed-window counters
// for the admin lockout gate and authenticated API rate limits. It is still
// separate from the Worker's KV-backed storage quotas and edge request
// counters, which have different consistency and throughput requirements.
//
// WHAT IS STORED: `{ count, windowStart, windowMs }` only, in this instance's
// own transactional storage (`state.storage`, SQLite-backed) — no tokens,
// secrets, or raw UIDs. Auth limiter keys are SHA-256 namespaced identifiers.
// A Durable Object alarm deletes each record when its window expires so
// inactive users/endpoints do not leave permanent rows behind. This Durable Object is called
// with a single atomic RPC per counter operation (`record` / `status` /
// `reset`), not raw get/put/delete, precisely because the
// atomicity guarantee lives in "one instance processes one request fully
// before starting the next", not in any single storage call.
//
// AUTHENTICATION: this class has no fetch handler of its own reachable from
// the public Internet — Durable Object instances are only reachable via a
// binding from within this same Worker (`env.ADMIN_LOCKOUT_DO`), never
// exposed on a public route directly. The Worker's own `/adminLockout` route
// (see index.js) is what's Internet-reachable, and that route enforces
// `ADMIN_LOCKOUT_SECRET` (same fail-closed, timing-safe pattern as
// `STATS_SECRET`) before ever touching this class. This module therefore
// does not re-check auth — by the time a request reaches here, the Worker
// has already authorized it.

const DEFAULT_WINDOW_MS = 15 * 60 * 1000;
const DEFAULT_MAX_FAILS = 10;
const STORAGE_KEY = "record";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export class AdminLockoutCounter {
  constructor(state, env, { now = () => Date.now() } = {}) {
    this.state = state;
    this.env = env;
    this.now = now;
  }

  async alarm() {
    const existing = await this.state.storage.get(STORAGE_KEY);
    if (!existing) {
      await this.state.storage.deleteAlarm();
      await this.state.storage.deleteAll();
      return;
    }

    // Older records did not include windowMs; their historical lifetime was
    // DEFAULT_WINDOW_MS, so use that as a migration-safe default.
    const lifetime = Number.isFinite(existing.windowMs) && existing.windowMs > 0
      ? existing.windowMs
      : DEFAULT_WINDOW_MS;
    const expiresAt = existing.windowStart + lifetime;
    if (this.now() >= expiresAt) {
      await this.state.storage.deleteAlarm();
      // Delete the entire SQLite-backed DO storage, not just the value: this
      // deallocates the idle per-bucket instance's storage after expiry.
      await this.state.storage.deleteAll();
      return;
    }
    // An early alarm (clock adjustment/runtime retry) must not delete a live
    // counter. Reschedule for the exact expiry boundary.
    await this.state.storage.setAlarm(expiresAt);
  }

  async fetch(request) {
    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "Malformed request body" }, 400);
    }
    if (!body || typeof body !== "object") {
      return json({ error: "Malformed request body" }, 400);
    }

    const { action } = body;
    const windowMs = Number.isFinite(body.windowMs) && body.windowMs > 0 ? body.windowMs : DEFAULT_WINDOW_MS;
    const maxFails = Number.isFinite(body.maxFails) && body.maxFails > 0 ? body.maxFails : DEFAULT_MAX_FAILS;
    const now = this.now();

    // Every branch below reads then writes `this.state.storage` at most
    // once each, with no I/O to anything else in between — this is what the
    // Durable Object runtime's per-instance serialization actually
    // protects. A version of this code that awaited some OTHER I/O (e.g. an
    // outbound fetch) between the read and the write would reopen exactly
    // the race this class exists to close, because the runtime only
    // guarantees no other REQUEST's JS interleaves — it does not turn
    // unrelated async gaps within a single request into a lock across
    // requests by itself. Keep it this way: read storage, compute, write
    // storage, return — nothing else in between.
    if (action === "record") {
      const existing = await this.state.storage.get(STORAGE_KEY);
      const next =
        !existing || now - existing.windowStart >= windowMs
          ? { count: 1, windowStart: now, windowMs }
          : { count: existing.count + 1, windowStart: existing.windowStart, windowMs };
      await this.state.storage.put(STORAGE_KEY, next);
      // Per-instance alarm removes this fixed-window record after it expires.
      // setAlarm is awaited inside the same serialized DO request.
      await this.state.storage.setAlarm(next.windowStart + windowMs);
      return json({ count: next.count, locked: next.count >= maxFails, windowStart: next.windowStart });
    }

    if (action === "status") {
      const existing = await this.state.storage.get(STORAGE_KEY);
      if (!existing || now - existing.windowStart >= windowMs) {
        if (existing) {
          await this.state.storage.deleteAlarm();
          await this.state.storage.deleteAll();
        }
        return json({ count: 0, locked: false, windowStart: null });
      }
      return json({ count: existing.count, locked: existing.count >= maxFails, windowStart: existing.windowStart });
    }

    if (action === "reset") {
      await this.state.storage.deleteAlarm();
      await this.state.storage.deleteAll();
      return json({ ok: true });
    }

    return json({ error: `Unknown action: ${String(action)}` }, 400);
  }
}

export { DEFAULT_WINDOW_MS, DEFAULT_MAX_FAILS };
