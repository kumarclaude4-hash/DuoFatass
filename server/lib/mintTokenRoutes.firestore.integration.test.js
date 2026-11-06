"use strict";

// Drives the production mintToken HTTP handler through its real libsignal
// verifier and real Firestore-emulator identity/invite transaction. Firebase
// custom-token creation is injected because the emulator test must not require
// a service-account credential or mint credentials for a real account.

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const http = require("node:http");
const { PrivateKey } = require("@signalapp/libsignal-client");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { createChallengeStore } = require("./challengeStore");
const { verifyMintTokenSignature, buildMintTokenChallenge } = require("./identityVerify");
const { createMintTokenRouteHandler } = require("./mintTokenRoute");
const { claimIdentityInTransaction } = require("./identityClaim");
const { createInviteStore } = require("./inviteStore");
const invite = require("./invite");

const emulatorHost = process.env.FIRESTORE_EMULATOR_HOST;
const requireEmulator = process.env.DUOSHIELD_REQUIRE_FIRESTORE_EMULATOR === "1";

function makeIdentity() {
  const privateKey = PrivateKey.generate();
  return {
    privateKey,
    publicKeyHex: privateKey.getPublicKey().serialize().toString("hex"),
  };
}

function sign(privateKey, userId, nonce) {
  return privateKey.sign(buildMintTokenChallenge(userId, nonce)).toString("hex");
}

function collectBody(req, res, callback) {
  const chunks = [];
  let bytes = 0;
  let tooLarge = false;
  req.on("data", (chunk) => {
    if (tooLarge) return;
    bytes += chunk.length;
    if (bytes > 1024 * 1024) {
      tooLarge = true;
      res.writeHead(413, { "Content-Type": "text/plain" });
      res.end("Request body too large");
      req.destroy();
      return;
    }
    chunks.push(chunk);
  });
  req.on("end", () => {
    if (!tooLarge) callback(Buffer.concat(chunks).toString("utf8"));
  });
}

if (!emulatorHost) {
  if (requireEmulator) {
    test("Firestore emulator must be configured for mintToken HTTP integration tests", () => {
      assert.fail("FIRESTORE_EMULATOR_HOST is required; refusing to silently skip mintToken HTTP integration coverage");
    });
  } else {
    test("mintToken HTTP + Firestore integration (requires emulator)", { skip: true }, () => {});
  }
} else {
  test("mintToken HTTP enforces libsignal proof-of-possession before atomic invite/identity claim", async (t) => {
    const projectId = process.env.GCLOUD_PROJECT || "duoshield-test";
    const suffix = crypto.randomBytes(8).toString("hex");
    const collections = {
      invites: "it_mint_http_invites_" + suffix,
      audit: "it_mint_http_audit_" + suffix,
    };
    const app = initializeApp({ projectId }, "mint-http-it-" + suffix);
    const db = getFirestore(app);
    const challengeStore = createChallengeStore();
    const mintCooldown = new Map();
    const mintedUsers = [];
    const identityUids = [];
    const tagUserId = (uid) => crypto.createHash("sha256")
      .update(uid, "utf8").digest("hex").slice(0, 12);
    const inviteStore = createInviteStore({
      firestore: db,
      FieldValue,
      Timestamp,
      invitesCollection: collections.invites,
      tagUserId,
      stageAudit(tx, action, request, extra) {
        const ref = db.collection(collections.audit).doc();
        tx.create(ref, {
          action,
          requestId: request && request.headers && request.headers["x-request-id"] || "missing",
          at: FieldValue.serverTimestamp(),
          ...extra,
        });
      },
    });
    const handler = createMintTokenRouteHandler({
      collectBody,
      getClientIp: () => "127.0.0.1",
      checkIpRateLimit: () => true,
      ipTag: () => "127.0.0.1-tag",
      uidTag: tagUserId,
      mintCooldown,
      mintCooldownMs: 60_000,
      mintChallengeStore: challengeStore,
      verifyMintTokenSignature,
      sha256hex(hex) {
        return crypto.createHash("sha256").update(Buffer.from(hex, "hex")).digest("hex");
      },
      db,
      FieldValue,
      inviteStore,
      invite,
      claimIdentityInTransaction,
      auditAdminEventInTransaction(tx, action, request, extra) {
        const ref = db.collection(collections.audit).doc();
        tx.create(ref, {
          action,
          requestId: request && request.headers && request.headers["x-request-id"] || "missing",
          at: FieldValue.serverTimestamp(),
          ...extra,
        });
      },
      async createCustomToken(userId) {
        mintedUsers.push(userId);
        return "emulated-custom-token-" + userId;
      },
      now: Date.now,
      logger: { warn() {}, log() {}, error() {} },
    });

    const server = http.createServer((req, res) => {
      if (handler(req, res)) return;
      res.writeHead(404);
      res.end("Not found");
    });

    async function cleanupCollection(name) {
      const snapshot = await db.collection(name).get();
      await Promise.all(snapshot.docs.map((doc) => doc.ref.delete()));
    }

    t.after(async () => {
      await new Promise((resolve) => {
        server.close(() => resolve());
        if (typeof server.closeAllConnections === "function") server.closeAllConnections();
      });
      try {
        for (const name of Object.values(collections)) await cleanupCollection(name);
        await Promise.all(identityUids.map((uid) => db.collection("identities").doc(uid).delete()));
      } finally {
        await deleteApp(app);
      }
    });

    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", resolve);
    });
    const baseUrl = "http://127.0.0.1:" + server.address().port;
    async function post(payload, requestId) {
      const response = await fetch(baseUrl + "/mintToken", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-request-id": requestId || "mint-http-" + suffix,
        },
        body: JSON.stringify(payload),
      });
      return { status: response.status, text: await response.text() };
    }
    async function createInvite(label) {
      return inviteStore.create({
        label,
        expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
      }, { headers: { "x-request-id": "mint-http-invite-" + suffix } });
    }

    // 1. Public-key-only attacker: the challenge is valid and bound to the
    // victim UID, but the signature comes from an unrelated private key.
    const victim = makeIdentity();
    const attacker = makeIdentity();
    const victimUid = "mint-victim-" + suffix;
    identityUids.push(victimUid);
    const victimNonce = challengeStore.issue(victimUid);
    const forged = await post({
      userId: victimUid,
      identityPubKeyHex: victim.publicKeyHex,
      nonce: victimNonce,
      signatureHex: sign(attacker.privateKey, victimUid, victimNonce),
      inviteToken: "irrelevant",
    });
    assert.equal(forged.status, 403);
    assert.equal(forged.text, "Key mismatch");
    assert.equal((await db.collection("identities").doc(victimUid).get()).exists, false);
    assert.equal(mintedUsers.length, 0);
    assert.equal(challengeStore.consume(victimUid, victimNonce), false,
      "invalid signature attempt must consume its single-use nonce");

    // Replaying that nonce with the correct private key is still rejected;
    // possession of the right key cannot revive a spent challenge.
    const replay = await post({
      userId: victimUid,
      identityPubKeyHex: victim.publicKeyHex,
      nonce: victimNonce,
      signatureHex: sign(victim.privateKey, victimUid, victimNonce),
      inviteToken: "irrelevant",
    });
    assert.equal(replay.status, 403);
    assert.equal(replay.text, "Key mismatch");

    // 2. A valid signature without an invite cannot create an identity or
    // mint a Firebase credential. The Firestore transaction remains atomic.
    const noInviteUid = "mint-no-invite-" + suffix;
    identityUids.push(noInviteUid);
    const noInviteIdentity = makeIdentity();
    const noInviteNonce = challengeStore.issue(noInviteUid);
    const noInvite = await post({
      userId: noInviteUid,
      identityPubKeyHex: noInviteIdentity.publicKeyHex,
      nonce: noInviteNonce,
      signatureHex: sign(noInviteIdentity.privateKey, noInviteUid, noInviteNonce),
    });
    assert.equal(noInvite.status, 403);
    assert.equal(noInvite.text, "Valid invite required");
    assert.equal((await db.collection(collections.identities).doc(noInviteUid).get()).exists, false);
    assert.equal(mintedUsers.length, 0);

    // 3. Valid signature + valid invite: HTTP 200 is returned only after the
    // same Firestore transaction consumes the invite and creates the binding.
    const validUid = "mint-valid-" + suffix;
    identityUids.push(validUid);
    const validIdentity = makeIdentity();
    const issuedInvite = await createInvite("Mint HTTP valid claim");
    const validNonce = challengeStore.issue(validUid);
    const valid = await post({
      userId: validUid,
      identityPubKeyHex: validIdentity.publicKeyHex,
      nonce: validNonce,
      signatureHex: sign(validIdentity.privateKey, validUid, validNonce),
      inviteToken: issuedInvite.token,
    });
    assert.equal(valid.status, 200);
    assert.deepEqual(JSON.parse(valid.text), { token: "emulated-custom-token-" + validUid });
    const binding = await db.collection(collections.identities).doc(validUid).get();
    assert.equal(binding.exists, true);
    assert.equal(binding.data().identityPubKeyHash,
      crypto.createHash("sha256").update(Buffer.from(validIdentity.publicKeyHex, "hex")).digest("hex"));
    const storedInvite = await db.collection(collections.invites).doc(issuedInvite.id).get();
    assert.equal(storedInvite.data().status, "used");
    assert.equal(storedInvite.data().usedByUserId, validUid);
    assert.deepEqual(mintedUsers, [validUid]);

    const audits = await db.collection(collections.audit).get();
    assert.equal(audits.docs.filter(doc => doc.data().action === "invite_redeemed").length, 1);
    assert.equal(JSON.stringify(audits.docs.map(doc => doc.data())).includes(validUid), false,
      "audit records must use the redacted UID tag, not the raw UID");

    // 4. Existing-account restore still verifies possession of its key and
    // returns a token without consuming a new invite.
    const restoreNonce = challengeStore.issue(validUid);
    const restore = await post({
      userId: validUid,
      identityPubKeyHex: validIdentity.publicKeyHex,
      nonce: restoreNonce,
      signatureHex: sign(validIdentity.privateKey, validUid, restoreNonce),
    });
    assert.equal(restore.status, 200);
    assert.deepEqual(JSON.parse(restore.text), { token: "emulated-custom-token-" + validUid });
    assert.equal(mintedUsers.length, 2);
    assert.equal((await db.collection(collections.invites).count().get()).data().count, 1);
  });
}
