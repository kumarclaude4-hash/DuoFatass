"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { consumeDuressLockNonceInTransaction } = require("./duressLock");

const emulatorHost = process.env.FIRESTORE_EMULATOR_HOST;
const requireEmulator = process.env.DUOSHIELD_REQUIRE_FIRESTORE_EMULATOR === "1";

if (!emulatorHost) {
  if (requireEmulator) {
    test("Firestore emulator must be configured for duress-lock integration tests", () => {
      assert.fail("FIRESTORE_EMULATOR_HOST is required; refusing to silently skip duress-lock coverage");
    });
  } else {
    test("Duress-lock nonce transaction integration (requires emulator)", { skip: true }, () => {});
  }
} else {
  test("duress-lock nonce is single-use, idempotent after success, and failures remain distinguishable", async (t) => {
    const projectId = process.env.GCLOUD_PROJECT || "duoshield-test";
    const suffix = crypto.randomBytes(8).toString("hex");
    const nonceCollection = "it_duress_nonce_" + suffix;
    const accountLockCollection = "it_duress_lock_" + suffix;
    const appA = initializeApp({ projectId }, "duress-lock-it-a-" + suffix);
    const appB = initializeApp({ projectId }, "duress-lock-it-b-" + suffix);
    const dbA = getFirestore(appA);
    const dbB = getFirestore(appB);
    const now = Date.now();

    const consume = (db, nonce, at = now) => db.runTransaction((tx) =>
      consumeDuressLockNonceInTransaction({
        tx,
        firestore: db,
        FieldValue,
        nonce,
        nonceCollection,
        accountLockCollection,
        now: at,
      })
    );
    const makeNonce = () => crypto.randomBytes(32).toString("hex");
    const makeUid = (label) => "duress-user-" + label + "-" + suffix;

    const cleanup = async (db, collection) => {
      const snap = await db.collection(collection).get();
      await Promise.all(snap.docs.map((doc) => doc.ref.delete()));
    };
    t.after(async () => {
      try {
        await cleanup(dbA, nonceCollection);
        await cleanup(dbA, accountLockCollection);
      } finally {
        await Promise.all([deleteApp(appA), deleteApp(appB)]);
      }
    });

    // Initial delivery atomically latches the account and records consumption.
    const nonce = makeNonce();
    const uid = makeUid("primary");
    await dbA.collection(nonceCollection).doc(nonce).set({
      uid,
      expiresAt: Timestamp.fromMillis(now + 60 * 60 * 1000),
    });
    const first = await consume(dbA, nonce);
    assert.deepEqual(first, { uid, alreadyLocked: false });
    const consumed = await dbA.collection(nonceCollection).doc(nonce).get();
    assert.equal(consumed.exists, true, "consumed marker remains until nonce TTL");
    assert.ok(consumed.data().consumedAt);
    const locked = await dbA.collection(accountLockCollection).doc(uid).get();
    assert.equal(locked.data().locked, true);
    assert.equal(locked.data().lockedBy, "duress");
    assert.ok(locked.data().lockedAt);

    // Simulate the server committing the lock but the client losing the HTTP
    // response. A replay must confirm the same lock, never re-write lockedAt.
    const replay = await consume(dbB, nonce, now + 1000);
    assert.deepEqual(replay, { uid, alreadyLocked: true });
    const afterReplay = await dbA.collection(accountLockCollection).doc(uid).get();
    assert.equal(afterReplay.data().lockedAt.toMillis(), locked.data().lockedAt.toMillis());

    // Concurrent duplicate requests converge to one first application and one
    // idempotent confirmation on independent Firestore clients.
    const raceNonce = makeNonce();
    const raceUid = makeUid("race");
    await dbA.collection(nonceCollection).doc(raceNonce).set({
      uid: raceUid,
      expiresAt: Timestamp.fromMillis(now + 60 * 60 * 1000),
    });
    const raced = await Promise.all([
      consume(dbA, raceNonce),
      consume(dbB, raceNonce),
    ]);
    assert.equal(raced.filter(x => x.alreadyLocked === false).length, 1);
    assert.equal(raced.filter(x => x.alreadyLocked === true).length, 1);
    const raceLock = await dbA.collection(accountLockCollection).doc(raceUid).get();
    assert.equal(raceLock.data().locked, true);
    assert.equal(raceLock.data().lockedBy, "duress");

    // An expired nonce is deleted in a committed transaction and reports a
    // permanent failure; it is not accidentally rolled back by a thrown error.
    const expiredNonce = makeNonce();
    const expiredUid = makeUid("expired");
    await dbA.collection(nonceCollection).doc(expiredNonce).set({
      uid: expiredUid,
      expiresAt: Timestamp.fromMillis(now - 1),
    });
    const expired = await consume(dbA, expiredNonce);
    assert.deepEqual(expired, { status: 401, message: "Nonce expired" });
    assert.equal((await dbA.collection(nonceCollection).doc(expiredNonce).get()).exists, false);
    assert.equal((await dbA.collection(accountLockCollection).doc(expiredUid).get()).exists, false);

    // A consumed token is not a blanket success: if an operator has since
    // unlocked the account or the lock no longer bears the duress marker, replay
    // is denied rather than re-locking an unrelated state.
    const unlockedNonce = makeNonce();
    const unlockedUid = makeUid("unlocked");
    await dbA.collection(nonceCollection).doc(unlockedNonce).set({
      uid: unlockedUid,
      expiresAt: Timestamp.fromMillis(now + 60 * 60 * 1000),
      consumedAt: Timestamp.fromMillis(now),
    });
    await dbA.collection(accountLockCollection).doc(unlockedUid).set({
      locked: false,
      lockedBy: "admin",
    });
    const invalidReplay = await consume(dbA, unlockedNonce);
    assert.deepEqual(invalidReplay, { status: 403, message: "Invalid or already-consumed nonce" });

    // A malformed stored uid is treated as corrupt server state, deleted,
    // and never allowed to construct a Firestore document reference.
    const malformedUidNonce = makeNonce();
    await dbA.collection(nonceCollection).doc(malformedUidNonce).set({
      uid: { not: "a string" },
      expiresAt: Timestamp.fromMillis(now + 60 * 60 * 1000),
    });
    assert.deepEqual(
      await consume(dbA, malformedUidNonce),
      { status: 401, message: "Nonce expired or malformed" }
    );
    assert.equal((await dbA.collection(nonceCollection).doc(malformedUidNonce).get()).exists, false);

    // Unknown/garbled credentials also fail permanently rather than becoming a
    // successful WorkManager result.
    assert.deepEqual(
      await consume(dbA, "f".repeat(64)),
      { status: 403, message: "Invalid or already-consumed nonce" }
    );
    assert.deepEqual(
      await consume(dbA, "not-a-hex-nonce"),
      { status: 400, message: "Missing or invalid nonce" }
    );
  });
}
