"use strict";

const pure = require("./pure");

/**
 * Consume a uid-bound duress-lock nonce and set the account lock atomically.
 *
 * The consumed nonce is retained until its existing expiresAt TTL so a replay
 * can be answered idempotently from the transactionally written consumed marker.
 * The current lock state is returned separately so a retry after an administrator
 * has unfrozen the account does not re-lock it or revoke newly issued sessions.
 *
 * Expired/malformed records are deleted and the failure is returned as data
 * (not thrown inside the transaction), so Firestore commits the cleanup.
 *
 * @returns {Promise<{uid?: string, alreadyLocked?: boolean, status?: number, message?: string}>}
 */
async function consumeDuressLockNonceInTransaction({
  tx,
  firestore,
  FieldValue,
  nonce,
  nonceCollection = "_duressNonces",
  accountLockCollection = "accountLock",
  now = Date.now(),
} = {}) {
  if (!tx || typeof tx.get !== "function" ||
      typeof tx.set !== "function" || typeof tx.update !== "function" ||
      typeof tx.delete !== "function") {
    throw new TypeError("consumeDuressLockNonceInTransaction requires an active Firestore transaction");
  }
  if (!firestore || typeof firestore.collection !== "function") {
    throw new TypeError("consumeDuressLockNonceInTransaction requires Firestore");
  }
  if (!FieldValue || typeof FieldValue.serverTimestamp !== "function") {
    throw new TypeError("consumeDuressLockNonceInTransaction requires FieldValue");
  }
  if (typeof nonce !== "string" || !/^[0-9a-f]{64}$/.test(nonce)) {
    return { status: 400, message: "Missing or invalid nonce" };
  }

  const nonceRef = firestore.collection(nonceCollection).doc(nonce);
  const nonceSnapshot = await tx.get(nonceRef);
  if (!nonceSnapshot.exists) {
    return { status: 403, message: "Invalid or already-consumed nonce" };
  }

  const nonceData = nonceSnapshot.data() || {};
  const uid = nonceData.uid;

  // The uid is server-authenticated when the nonce is issued. A missing or
  // malformed uid therefore means the stored nonce is corrupt, not a valid
  // credential; delete it and fail permanently before constructing a doc ref.
  if (typeof uid !== "string" || uid.length === 0) {
    tx.delete(nonceRef);
    return { status: 401, message: "Nonce expired or malformed" };
  }
  const lockRef = firestore.collection(accountLockCollection).doc(uid);

  // consumedAt is written in the same transaction as the original accountLock
  // write. It is therefore the durable proof that this nonce already committed.
  // A retry after a lost HTTP response must acknowledge that commit even if an
  // operator has since unfrozen the account; it must not re-lock that account.
  // The current lock state is returned separately so the route can avoid
  // revoking sessions again after an intentional administrative unfreeze.
  if (nonceData.consumedAt) {
    const lockSnapshot = await tx.get(lockRef);
    const lockData = lockSnapshot.exists ? (lockSnapshot.data() || {}) : {};
    const lockStillActive = lockData.locked === true && lockData.lockedBy === "duress";
    return { uid, alreadyLocked: true, lockStillActive };
  }

  if (!pure.isNonceUsable(uid, nonceData.expiresAt, now)) {
    // Return instead of throwing: throwing aborts the transaction and would
    // roll this delete back, leaving malformed/expired nonce documents behind.
    tx.delete(nonceRef);
    return { status: 401, message: "Nonce expired" };
  }

  const lockSnapshot = await tx.get(lockRef);
  const lockData = { locked: true, lockedBy: "duress" };
  if (!lockSnapshot.exists || !(lockSnapshot.data() || {}).lockedAt) {
    lockData.lockedAt = FieldValue.serverTimestamp();
  }
  tx.set(lockRef, lockData, { merge: true });
  tx.update(nonceRef, { consumedAt: FieldValue.serverTimestamp() });
  return { uid, alreadyLocked: false };
}

module.exports = { consumeDuressLockNonceInTransaction };
