"use strict";

// Real Firestore-emulator coverage for the admin-issued invite lifecycle.
// The fast helper tests remain in invite.test.js; this suite covers transaction
// semantics, single-use races, audit atomicity, expiry and cursor pagination.

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { createInviteStore } = require("./inviteStore");
const invite = require("./invite");

const emulatorHost = process.env.FIRESTORE_EMULATOR_HOST;
const requireEmulator = process.env.DUOSHIELD_REQUIRE_FIRESTORE_EMULATOR === "1";

if (!emulatorHost) {
  if (requireEmulator) {
    test("Firestore emulator must be configured for invite integration tests", () => {
      assert.fail("FIRESTORE_EMULATOR_HOST is required; refusing to silently skip integration coverage");
    });
  } else {
    test("Invite lifecycle integration (requires emulator)", { skip: true }, () => {});
  }
} else {
  test("invite creation, one-time redemption, audit, listing, expiry and revocation use real Firestore transactions", async (t) => {
    const projectId = process.env.GCLOUD_PROJECT || "duoshield-test";
    const suffix = crypto.randomBytes(8).toString("hex");
    const invitesCollection = "it_invites_" + suffix;
    const auditCollection = "it_invite_audit_" + suffix;
    const appA = initializeApp({ projectId }, "invite-it-a-" + suffix);
    const appB = initializeApp({ projectId }, "invite-it-b-" + suffix);
    const dbA = getFirestore(appA);
    const dbB = getFirestore(appB);
    const clock = { now: Date.now() };
    const requestContext = { requestId: "invite-integration-test" };

    const cleanupCollection = async (firestore, name) => {
      const snapshot = await firestore.collection(name).get();
      await Promise.all(snapshot.docs.map((doc) => doc.ref.delete()));
    };

    const makeStore = (firestore) => createInviteStore({
      firestore,
      FieldValue,
      Timestamp,
      now: () => clock.now,
      invitesCollection,
      tagUserId: (uid) => crypto.createHash("sha256").update(uid).digest("hex").slice(0, 12),
      stageAudit(tx, action, context, extra) {
        const auditRef = firestore.collection(auditCollection).doc();
        tx.create(auditRef, {
          action,
          requestId: context && context.requestId ? context.requestId : "unknown",
          at: FieldValue.serverTimestamp(),
          ...extra,
        });
      },
    });

    const storeA = makeStore(dbA);
    const storeB = makeStore(dbB);
    const auditFailureStore = createInviteStore({
      firestore: dbA,
      FieldValue,
      Timestamp,
      now: () => clock.now,
      invitesCollection,
      tagUserId: (uid) => crypto.createHash("sha256").update(uid).digest("hex").slice(0, 12),
      stageAudit() {
        throw new Error("simulated audit staging failure");
      },
    });
    const create = (label) => storeA.create({
      label,
      notes: "emulator integration",
      expiresAt: new Date(clock.now + 60 * 60 * 1000).toISOString(),
    }, requestContext);

    t.after(async () => {
      try {
        await cleanupCollection(dbA, invitesCollection);
        await cleanupCollection(dbA, auditCollection);
      } finally {
        await Promise.all([deleteApp(appA), deleteApp(appB)]);
      }
    });

    // Audit staging is part of the transaction contract. A failure must
    // abort invite creation/revocation/redemption instead of committing an
    // unaudited state transition.
    const countBeforeFailedCreate = (await dbA.collection(invitesCollection).count().get()).data().count;
    await assert.rejects(
      () => auditFailureStore.create({
        label: "Must roll back",
        expiresAt: new Date(clock.now + 60 * 60 * 1000).toISOString(),
      }, requestContext),
      /simulated audit staging failure/
    );
    assert.equal(
      (await dbA.collection(invitesCollection).count().get()).data().count,
      countBeforeFailedCreate,
      "failed audit staging must roll back the invite document"
    );

    // Create through the same store used by the HTTP handlers. The plaintext
    // bearer token is returned once but only its hash/fingerprint are persisted.
    const created = await create("Concurrent redemption");
    assert.match(created.id, /^[A-Za-z0-9_-]{10,40}$/);
    assert.match(created.token, /^dsi_[0-9a-f]{64}$/);
    assert.equal(await storeA.isActive(created.token), true);

    const storedSnapshot = await dbB.collection(invitesCollection)
      .where("tokenHash", "==", invite.hashInviteToken(created.token))
      .limit(1).get();
    assert.equal(storedSnapshot.size, 1);
    const storedJson = JSON.stringify(storedSnapshot.docs[0].data());
    assert.equal(storedJson.includes(created.token), false,
      "the raw invite bearer token must never be stored");
    assert.equal(storedSnapshot.docs[0].data().tokenFingerprint,
      invite.tokenFingerprint(created.token));

    // Two independent app instances attempt to consume the same token at
    // once. Firestore must serialize the transaction so exactly one account
    // wins; the loser sees the now-used invite on transaction retry.
    const redemption = await Promise.allSettled([
      dbA.runTransaction((tx) => storeA.redeemInTransaction(tx, "seed-derived-user-a", created.token, requestContext)),
      dbB.runTransaction((tx) => storeB.redeemInTransaction(tx, "seed-derived-user-b", created.token, requestContext)),
    ]);
    const winners = redemption.filter((item) => item.status === "fulfilled");
    const losers = redemption.filter((item) => item.status === "rejected");
    assert.equal(winners.length, 1, "one and only one concurrent redemption may succeed");
    assert.equal(losers.length, 1, "the concurrent loser must be rejected");
    assert.equal(losers[0].reason.code, "INVITE_INVALID");
    assert.equal(await storeA.isActive(created.token), false);

    const consumed = await dbB.collection(invitesCollection).doc(created.id).get();
    assert.equal(consumed.data().status, "used");
    assert.ok(["seed-derived-user-a", "seed-derived-user-b"].includes(consumed.data().usedByUserId));

    const usedAudit = await dbB.collection(auditCollection).where("action", "==", "invite_redeemed").get();
    assert.equal(usedAudit.size, 1, "the successful redemption and its audit row commit together");
    const auditPayload = JSON.stringify(usedAudit.docs[0].data());
    assert.equal(auditPayload.includes("seed-derived-user-a"), false);
    assert.equal(auditPayload.includes("seed-derived-user-b"), false);
    assert.match(usedAudit.docs[0].data().userIdTag, /^[0-9a-f]{12}$/);

    // An audit failure during redemption must abort token consumption.
    const rollbackRedemption = await create("Redemption audit rollback");
    await assert.rejects(
      () => dbA.runTransaction((tx) =>
        auditFailureStore.redeemInTransaction(tx, "seed-derived-user-rollback", rollbackRedemption.token, requestContext)
      ),
      /simulated audit staging failure/
    );
    assert.equal(await storeA.isActive(rollbackRedemption.token), true,
      "failed redemption audit staging must leave the invite active");

    // Repeated use must fail without creating a second audit event.
    await assert.rejects(
      () => dbB.runTransaction((tx) => storeB.redeemInTransaction(tx, "seed-derived-user-c", created.token, requestContext)),
      (error) => error && error.code === "INVITE_INVALID"
    );
    assert.equal((await dbB.collection(auditCollection).where("action", "==", "invite_redeemed").get()).size, 1);

    // Exercise bounded pagination and stable cursor continuation. Equal
    // millisecond timestamps are intentional; the opaque doc id is the
    // deterministic tie-breaker and prevents gaps/duplicates.
    const pageInvites = [];
    for (let i = 0; i < 11; i++) pageInvites.push(await create("Pagination " + String(i).padStart(2, "0")));
    const firstPage = await storeA.list({ status: "active", search: "pagination", pageSize: 10 });
    assert.equal(firstPage.items.length, 10);
    assert.ok(firstPage.nextCursor);
    assert.equal(firstPage.items.every((item) => item.label.toLowerCase().includes("pagination")), true);

    const secondPage = await storeB.list({ status: "active", search: "pagination", pageSize: 10, cursor: firstPage.nextCursor });
    assert.equal(secondPage.items.length, 1);
    assert.equal(secondPage.nextCursor, null);
    const pageIds = [...firstPage.items, ...secondPage.items].map((item) => item.id);
    assert.equal(new Set(pageIds).size, 11, "cursor pagination must not duplicate records");
    assert.deepEqual(
      new Set(pageIds),
      new Set(pageInvites.map((item) => item.id)),
      "cursor pagination must return every matching invite exactly once"
    );

    // Revoke is a state transition with an atomic audit record. Repeating the
    // request is a conflict, not a second successful revocation.
    const toRevoke = await create("Revoke me");
    await assert.rejects(
      () => auditFailureStore.revoke(toRevoke.id, "must roll back", requestContext),
      /simulated audit staging failure/
    );
    assert.equal(await storeB.isActive(toRevoke.token), true,
      "failed revoke audit staging must leave the invite active");
    assert.deepEqual(await storeA.revoke(toRevoke.id, "owner requested", requestContext), { status: "revoked" });
    assert.equal(await storeB.isActive(toRevoke.token), false);
    assert.deepEqual(await storeB.revoke(toRevoke.id, "duplicate", requestContext), {
      status: "conflict",
      current: "revoked",
    });
    assert.equal((await dbB.collection(auditCollection).where("action", "==", "invite_revoked").get()).size, 1);

    const missing = await storeA.revoke("missing_invite_123", "not found", requestContext);
    assert.deepEqual(missing, { status: "missing" });

    // Expiry is checked by application logic, not by delayed Firestore TTL
    // deletion. The record remains present while becoming unusable.
    const expiring = await create("Expire me");
    clock.now += 60 * 60 * 1000 + 1;
    assert.equal(await storeA.isActive(expiring.token), false);
    const expiredPage = await storeA.list({ status: "expired", search: "expire" });
    assert.equal(expiredPage.items.some((item) => item.id === expiring.id), true);
    assert.deepEqual(await storeA.revoke(expiring.id, "too late", requestContext), {
      status: "conflict",
      current: "expired",
    });
    assert.equal((await dbB.collection(invitesCollection).doc(expiring.id).get()).exists, true,
      "expiry must not depend on physical TTL cleanup");

    // Corrupt/legacy states must never inherit eligibility from a future
    // expiry. Missing and unknown status values are visible as invalid,
    // cannot validate/redeem, and can be explicitly revoked by an operator.
    const pendingToken = "dsi_" + "b".repeat(64);
    const missingStatusToken = "dsi_" + "c".repeat(64);
    const pendingRef = dbA.collection(invitesCollection).doc("it_invalid_pending_" + suffix);
    const missingStatusRef = dbA.collection(invitesCollection).doc("it_invalid_missing_" + suffix);
    const futureExpiry = Timestamp.fromMillis(clock.now + 24 * 60 * 60 * 1000);
    await pendingRef.set({
      tokenHash: invite.hashInviteToken(pendingToken),
      tokenFingerprint: invite.tokenFingerprint(pendingToken),
      status: "pending",
      label: "Invalid pending state",
      createdAt: Timestamp.fromMillis(clock.now),
      expiresAt: futureExpiry,
    });
    await missingStatusRef.set({
      tokenHash: invite.hashInviteToken(missingStatusToken),
      tokenFingerprint: invite.tokenFingerprint(missingStatusToken),
      label: "Invalid missing state",
      createdAt: Timestamp.fromMillis(clock.now),
      expiresAt: futureExpiry,
    });

    assert.equal(await storeA.isActive(pendingToken), false,
      "a future expiry must not make an unknown status active");
    assert.equal(await storeB.isActive(missingStatusToken), false,
      "a missing status must fail closed");
    await assert.rejects(
      () => dbA.runTransaction((tx) =>
        storeA.redeemInTransaction(tx, "seed-derived-invalid-state", pendingToken, requestContext)),
      (error) => error && error.code === "INVITE_INVALID"
    );
    const invalidPage = await storeA.list({ status: "invalid", search: "invalid" });
    assert.equal(invalidPage.items.length, 2);
    assert.ok(invalidPage.items.some((item) => item.id === pendingRef.id));
    assert.ok(invalidPage.items.some((item) => item.id === missingStatusRef.id));

    assert.deepEqual(await storeA.revoke(pendingRef.id, "invalid state cleanup", requestContext), {
      status: "revoked",
    });
    assert.equal(await storeA.isActive(pendingToken), false);
    assert.equal((await dbB.collection(invitesCollection).doc(pendingRef.id).get()).data().status, "revoked");
  });
}
