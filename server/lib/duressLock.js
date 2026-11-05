"use strict";

const pure = require("./pure");

/**
 * Consume a uid-bound duress-lock nonce and set the account lock atomically.
 *
 * The consumed nonce is retained until its existing expiresAt TTL so a replay
 * can be answered idempotently: if the corresponding duress lock is already
 * present, the server confirms success instead of returning an ambiguous 403.
 * Unknown or consumed-but-unlocked nonces are denied.
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

  const nonceRef = firestore.collection("_duressNonces").doc(nonce);
  const nonceSnapshot = await tx.get(nonceRef);
  if (!nonceSnapshot.exists) {
    return { status: 403, message: "Invalid or already-consumed nonce" };
  }

  const nonceData = nonceSnapshot.data() || {};
  const uid = nonceData.uid;
  const lockRef = typeof uid === "string" && uid.length > 0
      ? firestore.collection("accountLock").doc(uid)
      : null;

  // A consumed nonce is kept until expiresAt TTL specifically to make a lost
  // HTTP response/retry idempotent. Confirm only when the matching account is
  // still latched by this duress flow; never turn a consumed nonce into a
  // general success oracle for another kind of account lock.
  if (nonceData.consumedAt) {
    if (!lockRef) return { status: 403, message: "Invalid or already-consumed nonce" };
    const lockSnapshot = await tx.get(lockRef);
    const lockData = lockSnapshot.exists ? (lockSnapshot.data() || {}) : {};
    if (lockData.locked === true && lockData.lockedBy === "duress") {
      return { uid, alreadyLocked: true };
    }
    return { status: 403, message: "Invalid or already-consumed nonce" };
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
