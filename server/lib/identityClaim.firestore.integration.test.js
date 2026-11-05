"use strict";

// Real Firestore-emulator coverage for the exact transaction helper called by
// POST /mintToken. This covers the transaction boundary that structural route
// tests alone cannot prove: account-lock enforcement, one-time invite use,
// identity creation, and audit rollback all commit or abort together.

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const {
  getFirestore,
  FieldValue,
  Timestamp,
} = require("firebase-admin/firestore");
const { claimIdentityInTransaction } = require("./identityClaim");
const { createInviteStore } = require("./inviteStore");
const invite = require("./invite");

const emulatorHost = process.env.FIRESTORE_EMULATOR_HOST;
const requireEmulator = process.env.DUOSHIELD_REQUIRE_FIRESTORE_EMULATOR === "1";

if (!emulatorHost) {
  if (requireEmulator) {
    test("Firestore emulator must be configured for identity-claim integration tests", () => {
      assert.fail("FIRESTORE_EMULATOR_HOST is required; refusing to silently skip identity-claim coverage");
    });
  } else {
    test("Identity claim transaction integration (requires emulator)", { skip: true }, () => {});
  }
} else {
  test("mintToken identity claim atomically consumes an invite and creates the identity binding", async (t) => {
    const projectId = process.env.GCLOUD_PROJECT || "duoshield-test";
    const suffix = crypto.randomBytes(8).toString("hex");
    const collections = {
      invites: "it_claim_invites_" + suffix,
      audit: "it_claim_audit_" + suffix,
      identities: "it_claim_identities_" + suffix,
      locks: "it_claim_locks_" + suffix,
      waitlist: "it_claim_waitlist_" + suffix,
    };
    const appA = initializeApp({ projectId }, "identity-claim-it-a-" + suffix);
    const appB = initializeApp({ projectId }, "identity-claim-it-b-" + suffix);
    const dbA = getFirestore(appA);
    const dbB = getFirestore(appB);
    const clock = { now: Date.now() };
    const requestContext = { requestId: "identity-claim-it-" + suffix };

    const uidTag = (uid) => crypto.createHash("sha256")
      .update(uid, "utf8").digest("hex").slice(0, 12);
    const auditStage = (firestore, failure = false) => (tx, action, context, extra) => {
      if (failure) throw new Error("simulated audit staging failure");
      const ref = firestore.collection(collections.audit).doc();
      tx.create(ref, {
        action,
        requestId: context && context.requestId ? context.requestId : "unknown",
        at: FieldValue.serverTimestamp(),
        ...extra,
      });
    };
    const makeInviteStore = (firestore, failAudit = false) => createInviteStore({
      firestore,
      FieldValue,
      Timestamp,
      now: () => clock.now,
      invitesCollection: collections.invites,
      tagUserId: uidTag,
      stageAudit: auditStage(firestore, failAudit),
    });
    const storeA = makeInviteStore(dbA);
    const storeB = makeInviteStore(dbB);
    const failingStoreA = makeInviteStore(dbA, true);

    const legacyAudit = (firestore) => (tx, action, context, extra) => {
      const ref = firestore.collection(collections.audit).doc();
      tx.create(ref, {
        action,
        requestId: context && context.requestId ? context.requestId : "unknown",
        at: FieldValue.serverTimestamp(),
        ...extra,
      });
    };
    const incomingHash = (seed) => crypto.createHash("sha256")
      .update("serialized identity key:" + seed, "utf8").digest("hex");

    const dependencies = (firestore, inviteStore) => ({
      firestore,
      FieldValue,
      inviteStore,
      userId: "",
      incomingHash: "",
      inviteToken: "",
      requestContext,
      auditAdminEventInTransaction: legacyAudit(firestore),
      tagUserId: uidTag,
    });
    const claim = (firestore, inviteStore, userId, hash, token) => {
      const args = dependencies(firestore, inviteStore);
      args.userId = userId;
      args.incomingHash = hash;
      args.inviteToken = token;
      return firestore.runTransaction((tx) => claimIdentityInTransaction({ ...args, tx }));
    };
    const createInvite = (label) => storeA.create({
      label,
      notes: "identity claim integration",
      expiresAt: new Date(clock.now + 60 * 60 * 1000).toISOString(),
    }, requestContext);

    const cleanup = async (firestore, collectionName) => {
      const snapshot = await firestore.collection(collectionName).get();
      await Promise.all(snapshot.docs.map((doc) => doc.ref.delete()));
    };
    t.after(async () => {
      try {
        for (const name of Object.values(collections)) await cleanup(dbA, name);
      } finally {
        await Promise.all([deleteApp(appA), deleteApp(appB)]);
      }
    });

    // New-account claim: token consumption, transactionally staged audit and
    // identity binding must all be present after one successful transaction.
    const firstInvite = await createInvite("First claimant");
    const firstHash = incomingHash("first-user");
    const first = await claim(dbA, storeA, "seed-user-first-" + suffix, firstHash, firstInvite.token);
    assert.deepEqual(first, { isNewAccount: true });
    const firstUid = "seed-user-first-" + suffix;
    const firstIdentity = await dbA.collection(collections.identities).doc(firstUid).get();
    assert.equal(firstIdentity.exists, true);
    assert.equal(firstIdentity.data().identityPubKeyHash, firstHash);
    assert.equal(await storeA.isActive(firstInvite.token), false);
    const firstAudits = await dbA.collection(collections.audit)
      .where("action", "==", "invite_redeemed").get();
    assert.equal(firstAudits.size, 1);
    assert.equal(JSON.stringify(firstAudits.docs[0].data()).includes(firstUid), false,
      "audit linkage must not persist the raw user ID");
    assert.equal(firstAudits.docs[0].data().userIdTag, uidTag(firstUid));

    // Existing-account restore must validate the already-bound identity and
    // must not consume a fresh invite, even when a token is supplied.
    const unusedInvite = await createInvite("Must remain unused");
    const existing = await claim(dbB, storeB, firstUid, firstHash, unusedInvite.token);
    assert.deepEqual(existing, { isNewAccount: false });
    assert.equal(await storeB.isActive(unusedInvite.token), true);

    // A wrong cryptographic identity cannot claim an existing account, and
    // cannot burn an otherwise-valid invite as a side effect.
    await assert.rejects(
      () => claim(dbA, storeA, firstUid, incomingHash("wrong-key"), unusedInvite.token),
      (error) => error && error.status === 403 && error.message === "Key mismatch"
    );
    assert.equal(await storeA.isActive(unusedInvite.token), true);

    // Account lock is read in the same transaction. A locked claimant cannot
    // consume an invite or leave an identity binding behind.
    const lockedUid = "seed-user-locked-" + suffix;
    const lockedInvite = await createInvite("Locked account");
    await dbA.collection(collections.locks).doc(lockedUid).set({ locked: true });
    await assert.rejects(
      () => claim(dbA, storeA, lockedUid, incomingHash("locked"), lockedInvite.token),
      (error) => error && error.status === 403 && error.message === "Access request not approved"
    );
    assert.equal(await storeA.isActive(lockedInvite.token), true);
    assert.equal((await dbA.collection(collections.identities).doc(lockedUid).get()).exists, false);

    // Concurrent first claims on independent Firestore clients race for the
    // same one-time token. Firestore retries one transaction; exactly one
    // identity is created and the loser sees a used invite.
    const concurrentInvite = await createInvite("Concurrent claim");
    const concurrent = await Promise.allSettled([
      claim(dbA, storeA, "seed-user-race-a-" + suffix, incomingHash("race-a"), concurrentInvite.token),
      claim(dbB, storeB, "seed-user-race-b-" + suffix, incomingHash("race-b"), concurrentInvite.token),
    ]);
    const winners = concurrent.filter((result) => result.status === "fulfilled");
    const losers = concurrent.filter((result) => result.status === "rejected");
    assert.equal(winners.length, 1, "only one concurrent first claim may succeed");
    assert.equal(losers.length, 1, "the losing first claim must be rejected");
    assert.equal(losers[0].reason.status, 403);
    assert.equal(losers[0].reason.code, "INVITE_INVALID");
    const raceA = await dbA.collection(collections.identities).doc("seed-user-race-a-" + suffix).get();
    const raceB = await dbB.collection(collections.identities).doc("seed-user-race-b-" + suffix).get();
    assert.equal(Number(raceA.exists) + Number(raceB.exists), 1,
      "identity creation and invite consumption must not diverge");
    assert.equal(await storeA.isActive(concurrentInvite.token), false);

    // Failure to stage the invite audit must abort both writes in the identity
    // transaction. This catches the dangerous split-commit design in which an
    // identity could be created even though token/audit persistence failed.
    const rollbackUid = "seed-user-rollback-" + suffix;
    const rollbackInvite = await createInvite("Rollback on audit failure");
    await assert.rejects(
      () => claim(dbA, failingStoreA, rollbackUid, incomingHash("rollback"), rollbackInvite.token),
      /simulated audit staging failure/
    );
    assert.equal(await storeA.isActive(rollbackInvite.token), true);
    assert.equal((await dbA.collection(collections.identities).doc(rollbackUid).get()).exists, false);

    // Legacy approved waitlist IDs are a bounded transition path. They are
    // marked used, audited in the identity transaction, and not re-created.
    const legacyUid = "seed-user-legacy-" + suffix;
    const legacyId = crypto.randomBytes(16).toString("hex");
    await dbA.collection(collections.waitlist).doc(legacyId).set({ status: "approved" });
    const legacyResult = await claim(dbA, storeA, legacyUid, incomingHash("legacy"), legacyId);
    assert.deepEqual(legacyResult, { isNewAccount: true });
    const legacyDoc = await dbA.collection(collections.waitlist).doc(legacyId).get();
    assert.equal(legacyDoc.data().status, "used");
    assert.equal(legacyDoc.data().usedByUserId, legacyUid);
    assert.equal((await dbA.collection(collections.identities).doc(legacyUid).get()).exists, true);
    assert.equal(
      (await dbA.collection(collections.audit).where("action", "==", "invite_redeemed").get()).size,
      5,
      "only committed modern and legacy claims produce audit events"
    );
  });
}
