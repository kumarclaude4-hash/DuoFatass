'use strict';

/**
 * Round-trip tests for server/lib/xed25519.js (S07-C1 proof-of-possession).
 *
 * The production verifier must accept exactly the signatures that the Android
 * client produces via Signal's `Curve.calculateSignature`, which implements
 * XEdDSA (https://signal.org/docs/specifications/xeddsa/).
 *
 * These tests contain an independent XEdDSA *signer* built from the spec so the
 * verifier is checked against a real signature rather than against itself.
 *
 * XEdDSA sign(k, M, Z), spec §2.4:
 *     A, a = calculate_key_pair(k)
 *     r     = hash_1(a || M || Z)  (mod q)
 *     R     = rB
 *     h     = SHA-512(R || A || M) (mod q)
 *     s     = r + h*a              (mod q)
 *     return R || s
 *
 * verify(u, M, R||s), spec §2.5:
 *     A = convert_mont(u)          -- sign bit forced to 0
 *     h = SHA-512(R || A || M)     (mod q)
 *     check  R == sB - hA
 *
 * The critical detail: `hash_1`'s 32-byte domain prefix is consumed ONLY when
 * deriving the signing nonce `r`. The challenge hash `h` is taken over
 * `R || A || M` with the message UNPREFIXED, so a verifier must pass the raw
 * message to Ed25519 verification. Prefixing the message on the verify side
 * makes every legitimate signature fail.
 */

const test   = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { verifySignature } = require('../lib/xed25519');

// ── Ed25519 field / group constants ──────────────────────────────────────────
const P = (2n ** 255n) - 19n;
const Q = (2n ** 252n) + 27742317777372353535851937790883648493n;
const D = 37095705934669439343138083508754565189542113879843219016388785533085940283555n;

const BX = 15112221349535400772501151409588531511454012693041857206046113283949847762202n;
const BY = 46316835694926478169428394003475163141307993866256225615783033603165251855960n;

function mod(a, m = P) { return ((a % m) + m) % m; }

function modPow(b, e, m) {
  let r = 1n; b = mod(b, m);
  while (e > 0n) {
    if (e & 1n) r = (r * b) % m;
    e >>= 1n;
    b = (b * b) % m;
  }
  return r;
}

function inv(a, m = P) { return modPow(a, m - 2n, m); }

// ── Extended twisted-Edwards coordinates (X:Y:Z:T), a = -1 ──────────────────
const IDENTITY = [0n, 1n, 1n, 0n];

function toExtended(x, y) { return [x, y, 1n, mod(x * y)]; }

/** add-2008-hwcd-3 */
function pointAdd(p1, p2) {
  const [X1, Y1, Z1, T1] = p1;
  const [X2, Y2, Z2, T2] = p2;
  const A = mod((Y1 - X1) * (Y2 - X2));
  const B = mod((Y1 + X1) * (Y2 + X2));
  const C = mod(T1 * 2n * D * T2);
  const Dd = mod(Z1 * 2n * Z2);
  const E = mod(B - A);
  const F = mod(Dd - C);
  const G = mod(Dd + C);
  const H = mod(B + A);
  return [mod(E * F), mod(G * H), mod(F * G), mod(E * H)];
}

function scalarMul(p, k) {
  let result = IDENTITY;
  let addend = p;
  let n = k;
  while (n > 0n) {
    if (n & 1n) result = pointAdd(result, addend);
    addend = pointAdd(addend, addend);
    n >>= 1n;
  }
  return result;
}

function toAffine(p) {
  const [X, Y, Z] = p;
  const zi = inv(Z);
  return [mod(X * zi), mod(Y * zi)];
}

const BASE = toExtended(BX, BY);

// ── Encoding helpers ────────────────────────────────────────────────────────
function encodeLE(n, len = 32) {
  const b = Buffer.alloc(len);
  let t = n;
  for (let i = 0; i < len; i++) { b[i] = Number(t & 0xffn); t >>= 8n; }
  return b;
}

function decodeLE(buf) {
  let n = 0n;
  for (let i = buf.length - 1; i >= 0; i--) n = (n << 8n) | BigInt(buf[i]);
  return n;
}

/** Ed25519 point encoding: y little-endian, bit 255 = low bit of x. */
function encodePoint(p) {
  const [x, y] = toAffine(p);
  const b = encodeLE(y);
  if (x & 1n) b[31] |= 0x80;
  return b;
}

// ── XEdDSA ──────────────────────────────────────────────────────────────────

/**
 * calculate_key_pair(k) — spec §2.3.
 * Returns the Edwards public key with sign bit forced to 0, plus the scalar
 * `a` adjusted so that a*B has that same (sign-0) encoding.
 */
function calculateKeyPair(k) {
  const E = scalarMul(BASE, k);
  const [ex, ey] = toAffine(E);
  const signBit = ex & 1n;
  const a = signBit === 1n ? mod(-k, Q) : mod(k, Q);
  // A has sign bit 0 by construction.
  const A = encodeLE(ey);
  return { A, a };
}

/** Montgomery u-coordinate of an Edwards point: u = (1 + y) / (1 - y). */
function edwardsToMontgomeryU(p) {
  const [, y] = toAffine(p);
  return mod(mod(1n + y) * inv(mod(1n - y)));
}

/** X25519 private-scalar clamping (RFC 7748). */
function clampScalar(seed) {
  const k = Buffer.from(seed);
  k[0]  &= 248;
  k[31] &= 127;
  k[31] |= 64;
  return decodeLE(k);
}

/**
 * XEdDSA sign — produces the same signature shape as Signal's
 * Curve.calculateSignature.
 */
function xeddsaSign(k, message, Z) {
  const { A, a } = calculateKeyPair(k);

  // hash_1(X) = SHA-512( (2^256 - 1 - 1) || X ), little-endian prefix.
  // 2^256 - 2  →  0xFE followed by 31 × 0xFF.
  const hash1Prefix = Buffer.alloc(32, 0xff);
  hash1Prefix[0] = 0xfe;

  const r = mod(
    decodeLE(
      crypto.createHash('sha512')
        .update(Buffer.concat([hash1Prefix, encodeLE(a), message, Z]))
        .digest(),
    ),
    Q,
  );

  const R = encodePoint(scalarMul(BASE, r));

  const h = mod(
    decodeLE(
      crypto.createHash('sha512').update(Buffer.concat([R, A, message])).digest(),
    ),
    Q,
  );

  const s = mod(r + h * a, Q);
  return Buffer.concat([R, encodeLE(s)]);
}

/** Build a matching (montgomery pubkey, signature) pair for a message. */
function makeIdentity(seedHex) {
  const k = clampScalar(Buffer.from(seedHex, 'hex'));
  const pubU = edwardsToMontgomeryU(scalarMul(BASE, k));
  return { k, pubKey: encodeLE(pubU) };
}

// ── Tests ───────────────────────────────────────────────────────────────────

test('accepts a genuine XEdDSA signature over the challenge nonce', () => {
  const { k, pubKey } = makeIdentity('a'.repeat(64));
  const nonce = crypto.randomBytes(32).toString('hex');
  const msg   = Buffer.from(nonce, 'utf8');
  const sig   = xeddsaSign(k, msg, crypto.randomBytes(64));

  assert.strictEqual(
    verifySignature(pubKey, msg, sig),
    true,
    'a signature produced by the XEdDSA signer must verify — if this fails, ' +
    'legitimate clients cannot mint tokens at all',
  );
});

test('accepts signatures across many independent identities', () => {
  for (let i = 0; i < 5; i++) {
    const { k, pubKey } = makeIdentity(crypto.randomBytes(32).toString('hex'));
    const msg = Buffer.from(crypto.randomBytes(32).toString('hex'), 'utf8');
    const sig = xeddsaSign(k, msg, crypto.randomBytes(64));
    assert.strictEqual(verifySignature(pubKey, msg, sig), true, `identity #${i} failed`);
  }
});

test('rejects a signature over a different nonce (replay of an old signature)', () => {
  const { k, pubKey } = makeIdentity('b'.repeat(64));
  const signedNonce = crypto.randomBytes(32).toString('hex');
  const otherNonce  = crypto.randomBytes(32).toString('hex');
  const sig = xeddsaSign(k, Buffer.from(signedNonce, 'utf8'), crypto.randomBytes(64));

  assert.strictEqual(
    verifySignature(pubKey, Buffer.from(otherNonce, 'utf8'), sig),
    false,
  );
});

test("rejects a valid signature checked against a different identity's key", () => {
  const victim   = makeIdentity('c'.repeat(64));
  const attacker = makeIdentity('d'.repeat(64));
  const msg = Buffer.from(crypto.randomBytes(32).toString('hex'), 'utf8');

  // Attacker signs the challenge with their own key, presents victim's pubkey.
  const sig = xeddsaSign(attacker.k, msg, crypto.randomBytes(64));

  assert.strictEqual(verifySignature(victim.pubKey, msg, sig), false);
});

test('rejects a tampered signature', () => {
  const { k, pubKey } = makeIdentity('e'.repeat(64));
  const msg = Buffer.from(crypto.randomBytes(32).toString('hex'), 'utf8');
  const sig = xeddsaSign(k, msg, crypto.randomBytes(64));

  const flipped = Buffer.from(sig);
  flipped[0] ^= 0x01;
  assert.strictEqual(verifySignature(pubKey, msg, flipped), false);

  const flippedTail = Buffer.from(sig);
  flippedTail[63] ^= 0x01;
  assert.strictEqual(verifySignature(pubKey, msg, flippedTail), false);
});

test('rejects malformed input instead of throwing (fail closed)', () => {
  const { pubKey } = makeIdentity('f'.repeat(64));
  const msg = Buffer.from('nonce', 'utf8');

  // Wrong signature length.
  assert.strictEqual(verifySignature(pubKey, msg, Buffer.alloc(0)), false);
  assert.strictEqual(verifySignature(pubKey, msg, Buffer.alloc(32)), false);
  assert.strictEqual(verifySignature(pubKey, msg, Buffer.alloc(65)), false);

  // Garbage signature of the right length.
  assert.strictEqual(verifySignature(pubKey, msg, crypto.randomBytes(64)), false);

  // Wrong public-key length must not throw.
  assert.strictEqual(verifySignature(Buffer.alloc(31), msg, Buffer.alloc(64)), false);
  assert.strictEqual(verifySignature(Buffer.alloc(33), msg, Buffer.alloc(64)), false);
});

test('an all-zero public key does not verify arbitrary signatures', () => {
  const msg = Buffer.from('nonce', 'utf8');
  assert.strictEqual(
    verifySignature(Buffer.alloc(32), msg, crypto.randomBytes(64)),
    false,
  );
});
