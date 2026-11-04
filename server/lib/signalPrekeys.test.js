"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const signalPrekeys = require("./signalPrekeys");

const b64 = bytes => Buffer.alloc(bytes, 7).toString("base64");
const publicKey = (id, byte = 1) => ({ id, publicKey: Buffer.alloc(33, byte).toString("base64") });
const signedPreKey = (id = 1) => ({
  id,
  publicKey: b64(33),
  signature: b64(64),
  timestamp: 1_700_000_000_000,
});
const kyberPreKey = () => ({
  id: 1,
  publicKey: b64(1568),
  signature: b64(64),
  timestamp: 1_700_000_000_000,
});
const validBundle = () => ({
  identityKey: b64(33),
  registrationId: 123,
  signedPreKey: signedPreKey(),
  oneTimePreKeys: [publicKey(1), publicKey(2, 2)],
  kyberPreKey: kyberPreKey(),
});

test("accepts a well-formed initial public Signal bundle", () => {
  assert.equal(signalPrekeys.validInitialBundle(validBundle()), true);
});

test("rejects a malformed identity key, registration id, and signature", () => {
  const badIdentity = validBundle();
  badIdentity.identityKey = "not-base64";
  assert.equal(signalPrekeys.validInitialBundle(badIdentity), false);

  const badRegistration = validBundle();
  badRegistration.registrationId = 0;
  assert.equal(signalPrekeys.validInitialBundle(badRegistration), false);

  const badSignature = validBundle();
  badSignature.signedPreKey.signature = "AA==";
  assert.equal(signalPrekeys.validInitialBundle(badSignature), false);

  const badKyber = validBundle();
  badKyber.kyberPreKey.publicKey = b64(1567);
  assert.equal(signalPrekeys.validInitialBundle(badKyber), false);
});

test("rejects duplicate, malformed, and oversized one-time pre-key lists", () => {
  assert.equal(signalPrekeys.validPreKeyList([publicKey(1), publicKey(1, 2)]), false);
  assert.equal(signalPrekeys.validPreKeyList([{ id: 1, publicKey: "bad!" }]), false);
  const tooMany = Array.from({ length: signalPrekeys.MAX_ONE_TIME_PREKEYS + 1 }, (_, i) => publicKey(i));
  assert.equal(signalPrekeys.validPreKeyList(tooMany), false);
});

test("appends only unique IDs and preserves existing public keys", () => {
  const existing = [publicKey(1), publicKey(2, 2)];
  const additions = [publicKey(2, 2), publicKey(3, 3)];
  assert.deepEqual(
    signalPrekeys.appendPreKeys(existing, additions),
    [publicKey(1), publicKey(2, 2), publicKey(3, 3)]
  );
});

test("appending the same generated batch a second time is idempotent", () => {
  const batch = [publicKey(10), publicKey(11, 2)];
  assert.deepEqual(signalPrekeys.appendPreKeys(batch, batch), batch);
});

test("a retry cannot resurrect an already consumed pre-key", () => {
  const current = [publicKey(11, 2)];
  const retriedBatch = [publicKey(10), publicKey(11, 2)];
  assert.deepEqual(signalPrekeys.appendPreKeys(current, retriedBatch), current);
});

test("rejects a reused ID carrying a different public key", () => {
  assert.throws(
    () => signalPrekeys.appendPreKeys([publicKey(1)], [publicKey(1, 8)]),
    /different public key/
  );
});

test("rotation duplicate detection is idempotent and compares every field", () => {
  const spk = signedPreKey();
  assert.equal(signalPrekeys.sameSignedPreKey(spk, { ...spk }), true);
  assert.equal(signalPrekeys.sameSignedPreKey(spk, { ...spk, publicKey: b64(33) + "AA==" }), false);

  const kyber = kyberPreKey();
  assert.equal(signalPrekeys.sameKyberPreKey(kyber, { ...kyber }), true);
  assert.equal(signalPrekeys.sameKyberPreKey(kyber, { ...kyber, id: 2 }), false);
});

test("stored bundle validation rejects malformed identity and signed key data", () => {
  const good = validBundle();
  assert.equal(signalPrekeys.validStoredBundle(good), true);
  assert.equal(signalPrekeys.validStoredBundle({ ...good, identityKey: "bad" }), false);
  assert.equal(signalPrekeys.validStoredBundle({ ...good, signedPreKey: null }), false);
  assert.equal(signalPrekeys.validStoredBundle({ ...good, oneTimePreKeys: [{ id: 1, publicKey: "bad" }] }), false);
});

test("claim returns exactly one pre-key and leaves it out of the stored bundle", () => {
  const original = validBundle();
  const { remaining, response, claimed } = signalPrekeys.claimOneTimePreKey(original);
  assert.deepEqual(claimed, original.oneTimePreKeys[0]);
  assert.deepEqual(response.oneTimePreKeys, [original.oneTimePreKeys[0]]);
  assert.deepEqual(remaining.oneTimePreKeys, [original.oneTimePreKeys[1]]);
  assert.equal(original.oneTimePreKeys.length, 2);
});

test("claim on an exhausted pool returns a no-OTP bundle without changing other fields", () => {
  const original = { ...validBundle(), oneTimePreKeys: [] };
  const { remaining, response, claimed } = signalPrekeys.claimOneTimePreKey(original);
  assert.equal(claimed, null);
  assert.deepEqual(remaining.oneTimePreKeys, []);
  assert.deepEqual(response.oneTimePreKeys, []);
  assert.equal(response.identityKey, original.identityKey);
});
