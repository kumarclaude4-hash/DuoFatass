"use strict";

const invite = require("./invite");

const INVITE_COLLECTION = "invites";
const DEFAULT_PAGE_SIZE = 25;
const MAX_PAGE_SIZE = 100;
const MAX_SCAN = 1000;

function invalidInvite() {
  return Object.assign(new Error("Valid invite required"), {
    status: 403,
    code: "INVITE_INVALID",
  });
}

/**
 * Firestore-backed invite lifecycle.
 *
 * All state transitions are transactionally coupled to their audit record.
 * The raw bearer token is returned once to the operator and is never persisted;
 * Firestore stores only its SHA-256 hash and short display fingerprint.
 */
function createInviteStore({
  firestore,
  FieldValue,
  Timestamp,
  now = () => Date.now(),
  randomToken = invite.createInviteToken,
  tagUserId,
  invitesCollection = INVITE_COLLECTION,
  stageAudit,
} = {}) {
  if (!firestore || typeof firestore.collection !== "function" ||
      typeof firestore.runTransaction !== "function") {
    throw new TypeError("createInviteStore requires a Firestore-compatible instance");
  }
  if (!FieldValue || typeof FieldValue.serverTimestamp !== "function" ||
      !Timestamp || typeof Timestamp.fromMillis !== "function") {
    throw new TypeError("createInviteStore requires Firestore FieldValue and Timestamp");
  }
  if (typeof stageAudit !== "function") {
    throw new TypeError("createInviteStore requires an audit staging function");
  }
  if (typeof tagUserId !== "function") {
    throw new TypeError("createInviteStore requires a user-id pseudonymizer");
  }

  const collection = firestore.collection(invitesCollection);

  async function create(input, requestContext) {
    const normalized = invite.validateCreateInput(input, now());
    const rawToken = randomToken();
    const tokenHash = invite.hashInviteToken(rawToken);
    const tokenFingerprint = invite.tokenFingerprint(rawToken);
    const ref = collection.doc();

    await firestore.runTransaction(async (tx) => {
      tx.create(ref, {
        tokenHash,
        tokenFingerprint,
        status: "active",
        label: normalized.label,
        labelSearch: normalized.labelSearch,
        notes: normalized.notes,
        createdAt: FieldValue.serverTimestamp(),
        expiresAt: Timestamp.fromMillis(normalized.expiresAtMs),
      });
      stageAudit(tx, "invite_created", requestContext, { inviteId: ref.id });
    });

    return { id: ref.id, token: rawToken };
  }

  /**
   * Redeems a modern invite as part of the caller's larger identity-claim
   * transaction. This keeps invite consumption and identity creation atomic.
   * Legacy waitlist IDs intentionally remain owned by the compatibility path
   * in server/index.js.
   */
  async function redeemInTransaction(tx, userId, rawToken, requestContext) {
    if (typeof userId !== "string" || !userId) throw invalidInvite();
    const normalized = invite.normalizeInviteToken(rawToken);
    if (!invite.isInviteToken(normalized)) throw invalidInvite();

    const query = collection.where("tokenHash", "==", invite.hashInviteToken(normalized)).limit(1);
    const snapshot = await tx.get(query);
    if (snapshot.empty) throw invalidInvite();

    const doc = snapshot.docs[0];
    if (invite.inviteStatus(doc.data(), now()) !== "active") throw invalidInvite();

    tx.update(doc.ref, {
      status: "used",
      usedByUserId: userId,
      usedAt: FieldValue.serverTimestamp(),
    });
    stageAudit(tx, "invite_redeemed", requestContext, {
      userIdTag: tagUserId(userId),
    });
    return { id: doc.id };
  }

  async function isActive(rawToken) {
    const normalized = invite.normalizeInviteToken(rawToken);
    if (!invite.isInviteToken(normalized)) return false;
    const snapshot = await collection
      .where("tokenHash", "==", invite.hashInviteToken(normalized))
      .limit(1)
      .get();
    return !snapshot.empty && invite.inviteStatus(snapshot.docs[0].data(), now()) === "active";
  }

  async function revoke(inviteId, reason, requestContext) {
    const normalizedId = typeof inviteId === "string" ? inviteId : "";
    const normalizedReason = typeof reason === "string" ? reason.trim() : "";
    if (!/^[A-Za-z0-9_-]{10,40}$/.test(normalizedId) || normalizedReason.length > 200) {
      throw new TypeError("Invalid revoke request");
    }

    const ref = collection.doc(normalizedId);
    return firestore.runTransaction(async (tx) => {
      const snapshot = await tx.get(ref);
      if (!snapshot.exists) return { status: "missing" };

      const current = invite.inviteStatus(snapshot.data(), now());
      if (current !== "active") return { status: "conflict", current };

      tx.update(ref, {
        status: "revoked",
        revokedAt: FieldValue.serverTimestamp(),
        revocationReason: normalizedReason,
      });
      stageAudit(tx, "invite_revoked", requestContext, { inviteId: normalizedId });
      return { status: "revoked" };
    });
  }

  async function list(options = {}) {
    const wantedStatus = options.status || "all";
    const search = typeof options.search === "string" ? options.search.trim().toLowerCase() : "";
    const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(10, Number(options.pageSize) || DEFAULT_PAGE_SIZE));
    const cursor = invite.decodeCursor(options.cursor || null);
    if (!["all", "active", "used", "expired", "revoked"].includes(wantedStatus) || search.length > 80) {
      throw new TypeError("Invalid filter");
    }

    const [snapshot, totalSnapshot] = await Promise.all([
      collection.orderBy("createdAt", "desc").limit(MAX_SCAN).get(),
      collection.count().get(),
    ]);
    const totalCount = Number(totalSnapshot.data().count) || 0;
    const nowMs = now();
    let records = snapshot.docs.map((doc) => {
      const data = doc.data();
      const createdAtMs = invite.timestampMs(data.createdAt);
      const expiresAtMs = invite.timestampMs(data.expiresAt);
      const usedAtMs = invite.timestampMs(data.usedAt);
      const revokedAtMs = invite.timestampMs(data.revokedAt);
      return {
        id: doc.id,
        label: data.label || "",
        notes: data.notes || "",
        fingerprint: data.tokenFingerprint || "",
        status: invite.inviteStatus(data, nowMs),
        createdAt: createdAtMs ? new Date(createdAtMs).toISOString() : null,
        createdAtMs,
        expiresAt: expiresAtMs ? new Date(expiresAtMs).toISOString() : null,
        usedAt: usedAtMs ? new Date(usedAtMs).toISOString() : null,
        usedByUserId: data.usedByUserId || null,
        revokedAt: revokedAtMs ? new Date(revokedAtMs).toISOString() : null,
      };
    });

    const counts = { active: 0, used: 0, expired: 0, revoked: 0 };
    for (const record of records) counts[record.status]++;
    records = records
      .filter((record) => wantedStatus === "all" || record.status === wantedStatus)
      .filter((record) => !search || record.label.toLowerCase().includes(search))
      .sort((a, b) => b.createdAtMs - a.createdAtMs || b.id.localeCompare(a.id));

    if (cursor) {
      records = records.filter((record) =>
        record.createdAtMs < cursor.createdAtMs ||
        (record.createdAtMs === cursor.createdAtMs && record.id < cursor.id)
      );
    }

    const page = records.slice(0, pageSize);
    const nextCursor = records.length > pageSize && page.length
      ? invite.encodeCursor(page[page.length - 1].createdAtMs, page[page.length - 1].id)
      : null;

    return {
      items: page.map(({ createdAtMs, ...record }) => record),
      nextCursor,
      counts,
      totalCount,
      capped: totalCount > snapshot.size,
    };
  }

  return Object.freeze({
    create,
    redeemInTransaction,
    isActive,
    revoke,
    list,
  });
}

module.exports = {
  createInviteStore,
};
