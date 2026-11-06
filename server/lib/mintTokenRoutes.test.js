"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const http = require("node:http");
const { PrivateKey } = require("@signalapp/libsignal-client");
const { createChallengeStore } = require("./challengeStore");
const { verifyMintTokenSignature, buildMintTokenChallenge } = require("./identityVerify");
const { createMintTokenRouteHandler } = require("./mintTokenRoutes");

const USER_ID = "mint-http-user-123";

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

async function fixture(t, overrides = {}) {
  const challengeStore = createChallengeStore();
  const mintCooldown = new Map();
  const calls = { claim: [], customToken: [], verify: 0 };
  const state = {
    ipAllowed: true,
    claimError: null,
    claimResult: { isNewAccount: true },
    now: 1_000_000,
    ...overrides,
  };
  const logger = { warn() {}, log() {}, error() {} };
  const handler = createMintTokenRouteHandler({
    collectBody,
    getClientIp: () => "127.0.0.1",
    checkIpRateLimit: () => state.ipAllowed,
    ipTag: () => "ip-tag",
    mintCooldown,
    mintCooldownMs: 60_000,
    mintChallengeStore: challengeStore,
    verifyMintTokenSignature(args) {
      calls.verify++;
      return verifyMintTokenSignature(args);
    },
    sha256hex(hex) {
      return crypto.createHash("sha256").update(Buffer.from(hex, "hex")).digest("hex");
    },
    async claimIdentity(args) {
      calls.claim.push(args);
      if (state.claimError) throw state.claimError;
      return state.claimResult;
    },
    async createCustomToken(userId) {
      calls.customToken.push(userId);
      return "custom-token-for-" + userId;
    },
    uidTag: () => "uid-tag",
    now: () => state.now,
    logger,
  });

  const server = http.createServer((req, res) => {
    if (handler(req, res)) return;
    res.writeHead(404);
    res.end("Not found");
  });
  t.after(() => new Promise((resolve) => {
    server.close(() => resolve());
    if (typeof server.closeAllConnections === "function") server.closeAllConnections();
  }));
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();

  return {
    state,
    calls,
    challengeStore,
    mintCooldown,
    issue(userId = USER_ID) { return challengeStore.issue(userId); },
    async request(payload) {
      const response = await fetch("http://127.0.0.1:" + address.port + "/mintToken", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: typeof payload === "string" ? payload : JSON.stringify(payload),
      });
      return { status: response.status, text: await response.text() };
    },
  };
}

test("mintToken HTTP rejects missing proof before cooldown or transaction", async (t) => {
  const f = await fixture(t);
  const response = await f.request({ userId: USER_ID, identityPubKeyHex: "05" + "11".repeat(32) });
  assert.equal(response.status, 400);
  assert.match(response.text, /nonce \/ signatureHex/);
  assert.equal(f.mintCooldown.has(USER_ID), false);
  assert.equal(f.calls.claim.length, 0);
  assert.equal(f.calls.customToken.length, 0);
});

test("mintToken HTTP rejects unknown nonce with generic 403 and never calls transaction", async (t) => {
  const f = await fixture(t);
  const identity = makeIdentity();
  const nonce = crypto.randomBytes(32).toString("hex");
  const response = await f.request({
    userId: USER_ID,
    identityPubKeyHex: identity.publicKeyHex,
    nonce,
    signatureHex: sign(identity.privateKey, USER_ID, nonce),
    inviteToken: "unused",
  });
  assert.equal(response.status, 403);
  assert.equal(response.text, "Key mismatch");
  assert.equal(f.calls.claim.length, 0);
  assert.equal(f.calls.customToken.length, 0);
  assert.equal(f.mintCooldown.has(USER_ID), false);
});

test("mintToken HTTP consumes a challenge and rejects a public-key-only attacker", async (t) => {
  const f = await fixture(t);
  const victim = makeIdentity();
  const attacker = makeIdentity();
  const nonce = f.issue();
  const response = await f.request({
    userId: USER_ID,
    identityPubKeyHex: victim.publicKeyHex,
    nonce,
    signatureHex: sign(attacker.privateKey, USER_ID, nonce),
    inviteToken: "unused",
  });
  assert.equal(response.status, 403);
  assert.equal(response.text, "Key mismatch");
  assert.equal(f.calls.claim.length, 0);
  assert.equal(f.calls.customToken.length, 0);
  assert.equal(f.challengeStore.consume(USER_ID, nonce), false,
    "the failed signature attempt must consume the one-time nonce");
  assert.equal(f.mintCooldown.has(USER_ID), false,
    "a failed proof must not burn the per-user cooldown");
});

test("mintToken HTTP mints only after a valid signature and successful identity claim", async (t) => {
  const f = await fixture(t);
  const identity = makeIdentity();
  const nonce = f.issue();
  const payload = {
    userId: USER_ID,
    identityPubKeyHex: identity.publicKeyHex,
    nonce,
    signatureHex: sign(identity.privateKey, USER_ID, nonce),
    inviteToken: "opaque-invite-token",
  };
  const response = await f.request(payload);
  assert.equal(response.status, 200);
  assert.deepEqual(JSON.parse(response.text), { token: "custom-token-for-" + USER_ID });
  assert.equal(f.calls.claim.length, 1);
  assert.equal(f.calls.claim[0].userId, USER_ID);
  assert.equal(f.calls.claim[0].inviteToken, "opaque-invite-token");
  assert.equal(f.calls.claim[0].incomingHash,
    crypto.createHash("sha256").update(Buffer.from(identity.publicKeyHex, "hex")).digest("hex"));
  assert.deepEqual(f.calls.customToken, [USER_ID]);
  assert.equal(f.mintCooldown.has(USER_ID), true);

  const replay = await f.request(payload);
  assert.equal(replay.status, 429,
    "a successfully minted account remains under the successful-mint cooldown");
  assert.equal(f.calls.customToken.length, 1);
});

test("mintToken HTTP releases cooldown after a denied invite claim and does not mint a token", async (t) => {
  const f = await fixture(t, {
    claimError: Object.assign(new Error("Valid invite required"), { status: 403 }),
  });
  const identity = makeIdentity();
  const nonce = f.issue();
  const response = await f.request({
    userId: USER_ID,
    identityPubKeyHex: identity.publicKeyHex,
    nonce,
    signatureHex: sign(identity.privateKey, USER_ID, nonce),
    inviteToken: "invalid-invite",
  });
  assert.equal(response.status, 403);
  assert.equal(response.text, "Valid invite required");
  assert.equal(f.calls.claim.length, 1);
  assert.equal(f.calls.customToken.length, 0);
  assert.equal(f.mintCooldown.has(USER_ID), false);
});

test("mintToken HTTP enforces IP limiting before parsing JSON", async (t) => {
  const f = await fixture(t, { ipAllowed: false });
  const response = await f.request("{malformed");
  assert.equal(response.status, 429);
  assert.match(response.text, /15 min/);
  assert.equal(f.calls.claim.length, 0);
  assert.equal(f.calls.verify, 0);
});

test("mintToken HTTP returns internal errors generically and releases cooldown", async (t) => {
  const f = await fixture(t, {
    claimError: new Error("sensitive Firestore backend detail"),
  });
  const identity = makeIdentity();
  const nonce = f.issue();
  const response = await f.request({
    userId: USER_ID,
    identityPubKeyHex: identity.publicKeyHex,
    nonce,
    signatureHex: sign(identity.privateKey, USER_ID, nonce),
    inviteToken: "some-token",
  });
  assert.equal(response.status, 500);
  assert.equal(response.text, "Internal server error");
  assert.equal(response.text.includes("sensitive Firestore backend detail"), false);
  assert.equal(f.calls.customToken.length, 0);
  assert.equal(f.mintCooldown.has(USER_ID), false);
});
