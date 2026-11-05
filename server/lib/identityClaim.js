"use strict";

const defaultInvite = require("./invite");
const pure = require("./pure");

/**
 * Claims the seed-derived identity binding inside the caller's Firestore
 * transaction.
 *
 * The caller owns the HTTP/authentication layer and must verify the Firebase
 * token, mandatory nonce/signature proof-of-possession, and request shape
 * before calling this function. This helper owns the transaction boundary:
 * account-lock check, invite consumption, identity-document creation, and
 * the legacy waitlist compatibility mutation all commit or roll back together.
 *
 * @returns {Promise<{isNewAccount: boolean}>}
 * @throws {Error & {status?: number, code?: string}} on a denied claim.
 */
async function claimIdentityInTransaction({
  tx,
  firestore,
  FieldValue,
  inviteStore,
  invite = defaultInvite,
  legacyWaitlistCollection = "waitlist",
  userId,
  incomingHash,
  inviteToken,
  requestContext,
  auditAdminEventInTransaction,
  tagUserId,
} = {}) {
  if (!tx || typeof tx.get !== "function" ||
      typeof tx.set !== "function" || typeof tx.update !== "function") {
    throw new TypeError("claimIdentityInTransaction requires an active Firestore transaction");
  }
  if (!firestore || typeof firestore.collection !== "function") {
    throw new TypeError("claimIdentityInTransaction requires Firestore");
  }
  if (!FieldValue || typeof FieldValue.serverTimestamp !== "function") {
    throw new TypeError("claimIdentityInTransaction requires FieldValue");
  }
  if (!inviteStore || typeof inviteStore.redeemInTransaction !== "function") {
    throw new TypeError("claimIdentityInTransaction requires inviteStore");
  }
  if (typeof userId !== "string" || userId.length === 0) {
    throw Object.assign(new Error("Missing or invalid userId"), { status: 400 });
  }
  if (typeof incomingHash !== "string" || !/^[0-9a-f]{64}$/.test(incomingHash)) {
    throw Object.assign(new Error("Key mismatch"), { status: 403 });
  }
  if (typeof auditAdminEventInTransaction !== "function" ||
      typeof tagUserId !== "function") {
    throw new TypeError("claimIdentityInTransaction requires audit and UID-redaction dependencies");
  }

  const lockRef = firestore.collection("accountLock").doc(userId);
  const identityRef = firestore.collection("identities").doc(userId);

  // Read the lock and identity inside the same transaction as invite
  // consumption. A lock written concurrently causes Firestore to retry this
  // callback and the retry will observe the locked state before any claim is
  // committed.
  const lockSnapshot = await tx.get(lockRef);
  if (lockSnapshot.exists && lockSnapshot.data().locked === true) {
    // Preserve the existing deniable response: callers must not learn whether
    // a seed-derived UID is locked or simply lacks an approved invite.
    throw Object.assign(new Error("Access request not approved"), { status: 403 });
  }

  const identitySnapshot = await tx.get(identityRef);
  if (identitySnapshot.exists) {
    const storedHash = identitySnapshot.data().identityPubKeyHash;
    // Missing, malformed or mismatched identity material is a failure to prove
    // ownership; never interpret it as an unbound account.
    if (typeof storedHash !== "string" ||
        !/^[0-9a-f]{64}$/.test(storedHash) ||
        !pure.timingSafeEqualHex(storedHash, incomingHash)) {
      throw Object.assign(new Error("Key mismatch"), { status: 403 });
    }
    return { isNewAccount: false };
  }

  const normalizedInvite = invite.normalizeInviteToken(inviteToken);
  if (!normalizedInvite) {
    throw Object.assign(new Error("Valid invite required"), { status: 403 });
  }

  if (invite.isInviteToken(normalizedInvite)) {
    await inviteStore.redeemInTransaction(tx, userId, normalizedInvite, requestContext);
  } else if (/^[0-9a-f]{32}$/.test(normalizedInvite)) {
    // Narrow compatibility path for already-approved legacy waitlist IDs.
    // No endpoint creates new legacy IDs. Keep its audit event in this exact
    // transaction so the identity claim cannot commit without its audit.
    const legacyRef = firestore.collection(legacyWaitlistCollection).doc(normalizedInvite);
    const legacySnapshot = await tx.get(legacyRef);
    if (!legacySnapshot.exists || legacySnapshot.data().status !== "approved") {
      throw Object.assign(new Error("Valid invite required"), { status: 403 });
    }
    tx.update(legacyRef, {
      status: "used",
      usedByUserId: userId,
      usedAt: FieldValue.serverTimestamp(),
    });
    auditAdminEventInTransaction(tx, "invite_redeemed", requestContext, {
      userIdTag: tagUserId(userId),
    });
  } else {
    throw Object.assign(new Error("Valid invite required"), { status: 403 });
  }

  // Invite consumption and the permanent key binding are atomic. The caller
  // mints the Firebase custom token only after runTransaction resolves.
  tx.set(identityRef, {
    uid: userId,
    identityPubKeyHash: incomingHash,
    createdAt: FieldValue.serverTimestamp(),
  });
  return { isNewAccount: true };
}

module.exports = { claimIdentityInTransaction };
