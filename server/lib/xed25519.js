'use strict';

/**
 * XEd25519 signature verification for Node.js.
 *
 * Signal's Curve.calculateSignature / Curve.verifySignature use XEd25519 —
 * the Montogomery-form Curve25519 key is converted to an Edwards-form Ed25519
 * key before the standard Ed25519 signature operation is performed.
 *
 * RFC / Spec: https://signal.org/docs/specifications/xeddsa/
 * The conversion from a Curve25519 (X25519) public key u to an Ed25519 public
 * key A is the birational equivalence (Montogomery ↔ Edwards):
 *
 *   A.y = (u - 1) / (u + 1)  mod p       (Edwards y-coordinate)
 *   A.x sign from u: sign bit = 0         (canonical conversion)
 *
 * where p = 2^255 - 19.
 *
 * Limitations and scope:
 *   - Verification only; no signing.
 *   - Uses Node.js built-in `crypto` (Ed25519 supported in Node 12+).
 *   - Pure-JS big-int arithmetic; not timing-safe. Used only for verification
 *     of a signature over a nonce, not for key material protection.
 *   - The message is verified UNPREFIXED. XEdDSA's 32-byte domain-separation
 *     prefix belongs to `hash_1`, which the signer uses only to derive the
 *     per-signature nonce `r`; it never enters the challenge hash. Verification
 *     recomputes h = SHA-512(R || A || M) over the raw message, so prefixing
 *     `message` here would reject every legitimate signature. See §2.4/§2.5 of
 *     the XEdDSA spec, and `server/test/xed25519.test.js`, which signs with an
 *     independent XEdDSA implementation and would fail if a prefix were applied.
 *
 * Usage:
 *   const { verifySignature } = require('./xed25519');
 *   const ok = verifySignature(curve25519PubKeyBytes, messageBytes, signatureBytes);
 */

const crypto = require('crypto');

// p = 2^255 - 19
const P = (2n ** 255n) - 19n;

/**
 * Modular inverse via Fermat's little theorem: a^(p-2) mod p.
 * p is prime so this is always well-defined for a ≠ 0.
 */
function modInv(a, p) {
  return modPow(a, p - 2n, p);
}

function modPow(base, exp, mod) {
  let result = 1n;
  base = base % mod;
  while (exp > 0n) {
    if (exp % 2n === 1n) result = (result * base) % mod;
    exp >>= 1n;
    base = (base * base) % mod;
  }
  return result;
}

/**
 * Convert a raw 32-byte Curve25519 (X25519 / Montogomery-form) public key to
 * a raw 32-byte Ed25519 (Edwards-form) public key.
 *
 * Birational map (RFC 7748 §4.1 + XEd25519 §2):
 *   y = (u - 1) * inv(u + 1)  mod p
 *
 * The sign bit (high bit of the last byte) is set to 0 (canonical form).
 * This matches Signal's XEd25519 convention where the sign is always 0.
 *
 * @param  {Buffer} curve25519Pub  32-byte Curve25519 public key (little-endian)
 * @returns {Buffer}               32-byte Ed25519 public key (little-endian)
 */
function montgomeryToEdwards(curve25519Pub) {
  if (curve25519Pub.length !== 32) {
    throw new Error('Curve25519 public key must be 32 bytes');
  }
  // Decode little-endian u-coordinate.
  // Clamp high bit (bit 255 is always 0 in X25519 per RFC 7748 §4.1).
  const clamped = Buffer.from(curve25519Pub);
  clamped[31] &= 0x7f;

  let u = 0n;
  for (let i = 31; i >= 0; i--) {
    u = (u << 8n) | BigInt(clamped[i]);
  }

  // y = (u - 1) * inv(u + 1) mod p
  const num = ((u - 1n) % P + P) % P;
  const den = modInv((u + 1n) % P, P);
  const y   = (num * den) % P;

  // Encode y as 32-byte little-endian; sign bit (bit 255) = 0 (canonical).
  const yBytes = Buffer.alloc(32);
  let tmp = y;
  for (let i = 0; i < 32; i++) {
    yBytes[i] = Number(tmp & 0xffn);
    tmp >>= 8n;
  }
  // Sign bit stays 0 — canonical XEd25519 key.
  return yBytes;
}

/**
 * Verify an XEd25519 signature produced by Signal's Curve.calculateSignature.
 *
 * XEdDSA verification is standard Ed25519 verification against the Edwards-form
 * public key, over the message exactly as signed. The signer's `hash_1` domain
 * prefix (XEdDSA §2.4) is applied only when deriving the secret nonce `r` and is
 * invisible to a verifier, so no prefix is applied to `message` here.
 *
 * @param  {Buffer} curve25519PubKey  32-byte Curve25519 public key
 * @param  {Buffer} message           Message as signed, unprefixed
 * @param  {Buffer} signature         64-byte XEd25519 signature
 * @returns {boolean}                 true if the signature is valid
 */
function verifySignature(curve25519PubKey, message, signature) {
  try {
    if (signature.length !== 64) return false;

    // Convert Curve25519 pubkey → Edwards pubkey bytes.
    const edwardsPubKeyBytes = montgomeryToEdwards(curve25519PubKey);

    // Construct a DER-encoded SubjectPublicKeyInfo for Ed25519 so Node's
    // crypto.createPublicKey can consume it.
    // OID for Ed25519 is 1.3.101.112 → DER = 06 03 2B 65 70
    // Full SPKI:
    //   30 2A              SEQUENCE
    //     30 05            SEQUENCE (AlgorithmIdentifier)
    //       06 03 2B 65 70 OID Ed25519
    //     03 21            BIT STRING (33 bytes)
    //       00             unused-bits = 0
    //       <32 bytes>     Ed25519 public key
    const spki = Buffer.alloc(44);
    spki.writeUInt8(0x30, 0);  // SEQUENCE
    spki.writeUInt8(0x2a, 1);  // length 42
    spki.writeUInt8(0x30, 2);  // SEQUENCE (AlgId)
    spki.writeUInt8(0x05, 3);  // length 5
    spki.writeUInt8(0x06, 4);  // OID
    spki.writeUInt8(0x03, 5);  // length 3
    spki.writeUInt8(0x2b, 6);  // 1.3
    spki.writeUInt8(0x65, 7);  // .101
    spki.writeUInt8(0x70, 8);  // .112 → Ed25519
    spki.writeUInt8(0x03, 9);  // BIT STRING
    spki.writeUInt8(0x21, 10); // length 33
    spki.writeUInt8(0x00, 11); // unused bits = 0
    edwardsPubKeyBytes.copy(spki, 12);

    const pubKeyObj = crypto.createPublicKey({ key: spki, format: 'der', type: 'spki' });

    // Message is verified as signed — no domain prefix (see the note above).
    return crypto.verify(null, message, pubKeyObj, signature);
  } catch (_) {
    return false;
  }
}

module.exports = { verifySignature };
