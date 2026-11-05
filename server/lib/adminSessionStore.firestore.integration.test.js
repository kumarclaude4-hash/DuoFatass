"use strict";

// This suite deliberately runs against the Firestore emulator, not the
// in-memory FakeFirestore in adminSessionStore.test.js. The fake remains useful
// for fast edge-case coverage; this suite proves the transaction contract that
// protects sessions across independent server instances.

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { createAdminSessionStore } = require("./adminSessionStore");

const emulatorHost = process.env.FIRESTORE_EMULATOR_HOST;
const requireEmulator = process.env.DUOSHIELD_REQUIRE_FIRESTORE_EMULATOR === "1";

if (!emulatorHost) {
  if (requireEmulator) {
    test("Firestore emulator must be configured for integration tests", () => {
      assert.fail("FIRESTORE_EMULATOR_HOST is required; refusing to silently skip integration coverage");
    });
  } else {
    test("Firestore session integration (requires emulator)", { skip: true }, () => {});
  }
} else {
  test("Firestore transactions enforce durable admin sessions across independent instances", async (t) => {
    const projectId = process.env.GCLOUD_PROJECT || "duoshield-test";
    const suffix = crypto.randomBytes(8).toString("hex");
    const sessionsCollection = "it_admin_sessions_" + suffix;
    const controlCollection = "it_admin_session_control_" + suffix;
    const app = initializeApp({ projectId }, "admin-session-it-" + suffix);
    const db = getFirestore(app);
    const secret = crypto.randomBytes(32);
    const clock = { now: Date.now() };
    let nextId = 0;

    const makeStore = () => createAdminSessionStore({
      firestore: db,
      secret,
      now: () => clock.now,
      randomId: () => (++nextId).toString(16).padStart(64, "0"),
      idleTtlMs: 1_000,
      absoluteTtlMs: 5_000,
      sessionsCollection,
      controlCollection,
    });

    const instanceA = makeStore();
    const instanceB = makeStore();

    const cleanupCollection = async (name) => {
      const snapshot = await db.collection(name).get();
      await Promise.all(snapshot.docs.map((doc) => doc.ref.delete()));
    };

    t.after(async () => {
      try {
        await cleanupCollection(sessionsCollection);
        await cleanupCollection(controlCollection);
      } finally {
        await deleteApp(app);
      }
    });

    // Create on A and authenticate on B. This is the core multi-instance
    // property: no local Map or process affinity can make this pass.
    const cookie = await instanceA.create({ userAgent: "integration-test/1" });
    assert.deepEqual(
      await instanceB.validate(cookie, { userAgent: "different-webview-ua" }),
      { valid: true },
      "a session must survive a request reaching another server instance"
    );

    // Verify the persistent record is opaque: the raw random id and signed
    // cookie are both absent from Firestore.
    const parsed = instanceA.parseToken(cookie);
    const recordId = instanceA.sessionDocumentId(parsed.sid);
    assert.notEqual(recordId, parsed.sid);
    const recordSnapshot = await db.collection(sessionsCollection).doc(recordId).get();
    assert.equal(recordSnapshot.exists, true);
    const serializedRecord = JSON.stringify(recordSnapshot.data());
    assert.equal(serializedRecord.includes(cookie), false);
    assert.equal(serializedRecord.includes(parsed.sid), false);
    assert.equal(recordSnapshot.data().cookie, undefined);
    assert.equal(recordSnapshot.data().sessionId, undefined);

    // Single-session revocation must be visible immediately to the other
    // independent store instance.
    assert.equal(await instanceA.revoke(cookie), true);
    assert.equal((await instanceB.validate(cookie)).valid, false);

    // Create concurrent sessions through both instances. Then revoke-all must
    // advance one shared generation; every older session fails even though its
    // Firestore row is intentionally left in place.
    const oldCookies = await Promise.all([
      instanceA.create(),
      instanceB.create(),
      instanceA.create(),
    ]);
    const beforeBulkRevoke = await db.collection(controlCollection).doc("global").get();
    const oldGeneration = beforeBulkRevoke.data().generation;

    const generations = await Promise.all([
      instanceA.revokeAll(),
      instanceB.revokeAll(),
    ]);
    assert.deepEqual(
      generations.map((item) => item.generation).sort((a, b) => a - b),
      [oldGeneration + 1, oldGeneration + 2],
      "concurrent sign-out-everywhere calls must serialize without losing increments"
    );

    for (const oldCookie of oldCookies) {
      assert.equal(
        (await instanceB.validate(oldCookie)).valid,
        false,
        "bulk revocation must invalidate every cookie across instances"
      );
    }

    const freshCookie = await instanceB.create();
    assert.deepEqual(await instanceA.validate(freshCookie), { valid: true },
      "a session issued after the generation increment must remain valid");

    // Verify that expiry is enforced by the transactional validator, not by
    // asynchronous Firestore TTL cleanup.
    const idleCookie = await instanceA.create();
    clock.now += 1_001;
    assert.equal((await instanceB.validate(idleCookie)).reason, "idle_expired");
    const absoluteCookie = await instanceB.create();
    for (let i = 0; i < 4; i++) {
      clock.now += 900;
      assert.equal((await instanceA.validate(absoluteCookie)).valid, true,
        "activity refresh should extend idle expiry before the absolute cap");
    }
    clock.now += 1_500;
    assert.equal((await instanceB.validate(absoluteCookie)).reason, "absolute_expired",
      "activity must never extend the absolute session lifetime");

    // Leave no test data behind even if a later assertion fails.
  });
}
