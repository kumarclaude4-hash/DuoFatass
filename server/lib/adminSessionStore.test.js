"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { createAdminSessionStore } = require("./adminSessionStore");

const SECRET = Buffer.from("unit-test-admin-session-secret-32-bytes-minimum");
const CTX = { userAgent: "DuoShield admin test/1.0" };

function makeClock(startAt = 1_000_000) {
  return {
    now: startAt,
    advance(ms) { this.now += ms; },
  };
}

function clone(value) {
  if (value === undefined) return undefined;
  return structuredClone(value);
}

class FakeFirestore {
  constructor() {
    this.collections = new Map();
    this.queue = Promise.resolve();
    this.failNextTransaction = false;
    this.idCounter = 0;
  }

  nextSessionId() {
    return (++this.idCounter).toString(16).padStart(64, "0");
  }

  collection(name) {
    return new FakeCollection(this, name);
  }

  _map(name) {
    if (!this.collections.has(name)) this.collections.set(name, new Map());
    return this.collections.get(name);
  }

  runTransaction(callback) {
    const run = this.queue.then(async () => {
      if (this.failNextTransaction) {
        this.failNextTransaction = false;
        throw new Error("simulated Firestore outage");
      }

      const writes = [];
      const tx = {
        get: async (ref) => ref._snapshot(),
        create: (ref, data) => writes.push({ type: "create", ref, data: clone(data) }),
        set: (ref, data, options = {}) => writes.push({ type: "set", ref, data: clone(data), merge: options.merge === true }),
        update: (ref, data) => writes.push({ type: "update", ref, data: clone(data) }),
        delete: (ref) => writes.push({ type: "delete", ref }),
      };

      const result = await callback(tx);
      for (const write of writes) {
        const map = this._map(write.ref.collectionName);
        const exists = map.has(write.ref.id);
        if (write.type === "create" && exists) {
          const error = new Error("document already exists");
          error.code = 6;
          throw error;
        }
        if (write.type === "update" && !exists) throw new Error("document does not exist");
        if (write.type === "delete") {
          map.delete(write.ref.id);
        } else if (write.type === "set" && write.merge && exists) {
          map.set(write.ref.id, { ...map.get(write.ref.id), ...clone(write.data) });
        } else {
          map.set(write.ref.id, clone(write.data));
        }
      }
      return result;
    });

    this.queue = run.catch(() => {});
    return run;
  }
}

class FakeCollection {
  constructor(db, name) {
    this.db = db;
    this.name = name;
  }

  doc(id) {
    return new FakeDocumentReference(this.db, this.name, id);
  }

  where(field, operator, value) {
    if (operator !== "<=") throw new Error("FakeFirestore only implements <= queries");
    return new FakeQuery(this.db, this.name, field, value);
  }
}

class FakeDocumentReference {
  constructor(db, collectionName, id) {
    this.db = db;
    this.collectionName = collectionName;
    this.id = id || "generated-id";
  }

  async get() {
    return this._snapshot();
  }

  _snapshot() {
    const data = this.db._map(this.collectionName).get(this.id);
    return {
      exists: data !== undefined,
      id: this.id,
      ref: this,
      data: () => clone(data),
    };
  }

  async delete() {
    this.db._map(this.collectionName).delete(this.id);
  }
}

class FakeQuery {
  constructor(db, collectionName, field, value) {
    this.db = db;
    this.collectionName = collectionName;
    this.field = field;
    this.value = value;
    this.max = 100;
  }

  limit(value) {
    this.max = value;
    return this;
  }

  async get() {
    const docs = [];
    for (const [id, data] of this.db._map(this.collectionName)) {
      const fieldValue = data[this.field];
      const matches = fieldValue instanceof Date && this.value instanceof Date
        ? fieldValue.getTime() <= this.value.getTime()
        : fieldValue <= this.value;
      if (!matches) continue;
      const ref = new FakeDocumentReference(this.db, this.collectionName, id);
      docs.push({ id, ref, data: () => clone(data) });
      if (docs.length >= this.max) break;
    }
    return { docs, size: docs.length };
  }
}

function makeStore(db, clock, overrides = {}) {
  return createAdminSessionStore({
    firestore: db,
    idleTtlMs: 30 * 60 * 1000,
    absoluteTtlMs: 8 * 60 * 60 * 1000,
    secret: SECRET,
    now: () => clock.now,
    randomId: () => db.nextSessionId(),
    ...overrides,
  });
}

test("requires durable Firestore and a strong signing secret", () => {
  assert.throws(() => createAdminSessionStore({ secret: SECRET }), /Firestore-compatible/);
  assert.throws(() => createAdminSessionStore({ firestore: new FakeFirestore(), secret: "weak" }), /at least 32 bytes/);
});

test("a session created on one instance validates on another instance", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const instanceA = makeStore(db, clock);
  const instanceB = makeStore(db, clock);

  const cookie = await instanceA.create(CTX);
  assert.deepEqual(await instanceB.validate(cookie, CTX), { valid: true });
});

test("session records contain no raw session id or cookie", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const store = makeStore(db, clock);
  const cookie = await store.create(CTX);
  const parsed = store.parseToken(cookie);
  const recordId = store.sessionDocumentId(parsed.sid);
  const record = db._map("adminSessions").get(recordId);

  assert.ok(record);
  assert.notEqual(recordId, parsed.sid);
  assert.equal(record.sessionId, undefined);
  assert.equal(record.cookie, undefined);
  assert.equal(JSON.stringify(record).includes(cookie), false);
  assert.equal(JSON.stringify(record).includes(parsed.sid), false);
  assert.equal(record.schemaVersion, 1);
  assert.equal(record.generation, 0);
});

test("a malformed or tampered cookie is rejected without a Firestore read", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const store = makeStore(db, clock);
  const cookie = await store.create(CTX);
  const before = db._map("adminSessions").size;

  assert.equal((await store.validate(cookie + "0", CTX)).valid, false);
  assert.equal((await store.validate("not-a-session", CTX)).reason, "not_found");
  assert.equal(db._map("adminSessions").size, before);
});

test("refresh slides idle expiry, while read-only validation does not", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const store = makeStore(db, clock, { idleTtlMs: 1000, absoluteTtlMs: 10_000 });
  const cookie = await store.create(CTX);
  const parsed = store.parseToken(cookie);
  const recordId = store.sessionDocumentId(parsed.sid);

  clock.advance(900);
  assert.equal((await store.validate(cookie, CTX, { refresh: false })).valid, true);
  assert.equal(db._map("adminSessions").get(recordId).idleExpiresAtMs, 1_001_000);

  clock.advance(200);
  assert.equal((await store.validate(cookie, CTX, { refresh: false })).reason, "idle_expired");
  assert.equal(db._map("adminSessions").has(recordId), false);
});

test("authenticated refreshes preserve an active session until the absolute ceiling", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const store = makeStore(db, clock, { idleTtlMs: 1000, absoluteTtlMs: 5000 });
  const cookie = await store.create(CTX);

  for (let i = 0; i < 4; i++) {
    clock.advance(800);
    const result = await store.validate(cookie, CTX);
    assert.equal(result.valid, true, "refresh #" + (i + 1) + " at " + clock.now + " rejected: " + JSON.stringify(result));
  }
  clock.advance(1800);
  assert.equal((await store.validate(cookie, CTX)).reason, "absolute_expired");
});

test("a single-session logout revokes the cookie across instances", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const instanceA = makeStore(db, clock);
  const instanceB = makeStore(db, clock);
  const cookie = await instanceA.create(CTX);

  assert.equal(await instanceA.revoke(cookie), true);
  assert.equal((await instanceB.validate(cookie, CTX)).reason, "not_found");
  assert.equal(await instanceB.revoke(cookie), false);
});

test("sign out everywhere atomically revokes all older sessions across instances", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const instanceA = makeStore(db, clock);
  const instanceB = makeStore(db, clock);
  const oldCookies = await Promise.all([
    instanceA.create(CTX),
    instanceA.create(CTX),
    instanceB.create(CTX),
  ]);

  const result = await instanceA.revokeAll();
  assert.deepEqual(result, { generation: 1 });

  for (const cookie of oldCookies) {
    assert.equal((await instanceB.validate(cookie, CTX)).reason, "not_found");
  }

  const newCookie = await instanceB.create(CTX);
  assert.deepEqual(await instanceA.validate(newCookie, CTX), { valid: true });
});

test("concurrent revoke-all operations serialize and advance the generation", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const instanceA = makeStore(db, clock);
  const instanceB = makeStore(db, clock);

  const results = await Promise.all([instanceA.revokeAll(), instanceB.revokeAll()]);
  assert.deepEqual(results.map((r) => r.generation).sort(), [1, 2]);
  assert.equal(db._map("adminSessionControl").get("global").generation, 2);
});

test("Firestore outages propagate so callers can fail closed", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const store = makeStore(db, clock);
  const cookie = await store.create(CTX);

  db.failNextTransaction = true;
  await assert.rejects(store.validate(cookie, CTX), /simulated Firestore outage/);
});

test("User-Agent mismatch is not enforced by default, but remains available as an opt-in", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const permissive = makeStore(db, clock);
  const cookie = await permissive.create({ userAgent: "navigation UA" });
  assert.deepEqual(await permissive.validate(cookie, { userAgent: "fetch UA" }), { valid: true });

  const boundDb = new FakeFirestore();
  const bound = makeStore(boundDb, clock, { bindUserAgent: true });
  const boundCookie = await bound.create({ userAgent: "navigation UA" });
  assert.equal((await bound.validate(boundCookie, { userAgent: "fetch UA" })).reason, "ua_mismatch");
  assert.deepEqual(await bound.validate(boundCookie, { userAgent: "navigation UA" }), { valid: true });
});

test("expired-record sweeper is cleanup only and bounded", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const store = makeStore(db, clock, { idleTtlMs: 1000, absoluteTtlMs: 2000 });
  const cookie = await store.create(CTX);
  assert.equal((await store.validate(cookie, CTX)).valid, true);

  clock.advance(2000);
  assert.equal(await store.sweep(1), 1);
  assert.equal(db._map("adminSessions").size, 0);
});

test("generation mismatch is rejected even if a stale record remains", async () => {
  const db = new FakeFirestore();
  const clock = makeClock();
  const store = makeStore(db, clock);
  const cookie = await store.create(CTX);
  const parsed = store.parseToken(cookie);
  const recordId = store.sessionDocumentId(parsed.sid);

  await store.revokeAll();
  // Simulate a stale doc left behind by a failed/background cleanup.
  db._map("adminSessions").set(recordId, {
    ...db._map("adminSessions").get(recordId),
    generation: 0,
  });
  assert.equal((await store.validate(cookie, CTX)).reason, "not_found");
});
