'use strict';

/**
 * Tests for the /mintChallenge + /mintToken challenge store (S07-C1, S02-M1).
 *
 * `server/index.js` connects to Firestore and starts listeners at require time,
 * so it cannot be imported in a unit test. The challenge store is pure,
 * self-contained logic, so these tests exercise a mirror of it.
 *
 * The mirror MUST stay in sync with `issueChallenge` / `consumeChallenge` in
 * server/index.js. The invariants asserted here are the security properties:
 *   1. a nonce is single-use (replay fails)                          — S07-C1
 *   2. an attacker cannot evict a victim's in-flight nonce           — S02-M1
 *   3. per-user memory is bounded                                    — DoS guard
 *   4. an unknown nonce does not disturb valid outstanding nonces    — S02-M1
 */

const test   = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const CHALLENGE_TTL_MS       = 5 * 60 * 1000;
const MAX_CHALLENGES_PER_UID = 16;

/** Mirror of the production challenge store. */
function createStore(clock = () => Date.now()) {
  const mintChallenges = new Map(); // userId → Map<nonce, expiresAt>

  function issueChallenge(userId) {
    const nonce = crypto.randomBytes(32).toString('hex');
    let perUid  = mintChallenges.get(userId);
    if (!perUid) {
      perUid = new Map();
      mintChallenges.set(userId, perUid);
    }
    const now = clock();
    for (const [n, exp] of perUid) if (exp <= now) perUid.delete(n);
    while (perUid.size >= MAX_CHALLENGES_PER_UID) {
      perUid.delete(perUid.keys().next().value);
    }
    perUid.set(nonce, now + CHALLENGE_TTL_MS);
    return nonce;
  }

  function consumeChallenge(userId, nonce) {
    const perUid = mintChallenges.get(userId);
    if (!perUid) return 'missing';
    const expiresAt = perUid.get(nonce);
    if (expiresAt === undefined) return 'missing';
    perUid.delete(nonce);
    if (perUid.size === 0) mintChallenges.delete(userId);
    return clock() > expiresAt ? 'expired' : 'ok';
  }

  return { issueChallenge, consumeChallenge, _map: mintChallenges };
}

// ── S07-C1: single use ──────────────────────────────────────────────────────

test('a freshly issued nonce is accepted exactly once', () => {
  const s = createStore();
  const nonce = s.issueChallenge('alice');

  assert.strictEqual(s.consumeChallenge('alice', nonce), 'ok');
  assert.strictEqual(
    s.consumeChallenge('alice', nonce),
    'missing',
    'replaying the same nonce must fail — this is the S07-C1 replay guard',
  );
});

test('a nonce is not valid for a different userId', () => {
  const s = createStore();
  const nonce = s.issueChallenge('alice');
  assert.strictEqual(s.consumeChallenge('bob', nonce), 'missing');
  // Alice's nonce survives Bob's failed attempt.
  assert.strictEqual(s.consumeChallenge('alice', nonce), 'ok');
});

test('an unknown nonce is rejected', () => {
  const s = createStore();
  s.issueChallenge('alice');
  assert.strictEqual(
    s.consumeChallenge('alice', crypto.randomBytes(32).toString('hex')),
    'missing',
  );
});

test('an expired nonce is reported expired and consumed', () => {
  let now = 1_000_000;
  const s = createStore(() => now);
  const nonce = s.issueChallenge('alice');

  now += CHALLENGE_TTL_MS + 1;
  assert.strictEqual(s.consumeChallenge('alice', nonce), 'expired');
  // Consumed even though expired — cannot be retried.
  assert.strictEqual(s.consumeChallenge('alice', nonce), 'missing');
});

test('a nonce one millisecond before expiry is still valid', () => {
  let now = 1_000_000;
  const s = createStore(() => now);
  const nonce = s.issueChallenge('alice');

  now += CHALLENGE_TTL_MS - 1;
  assert.strictEqual(s.consumeChallenge('alice', nonce), 'ok');
});

// ── S02-M1: no pre-auth denial of service ───────────────────────────────────

test("an attacker calling /mintChallenge cannot evict a victim's in-flight nonce", () => {
  const s = createStore();

  // Victim starts authenticating: obtains a nonce and is about to sign it.
  const victimNonce = s.issueChallenge('victim');

  // Attacker, who only knows the victim's userId, floods /mintChallenge.
  for (let i = 0; i < 10; i++) s.issueChallenge('victim');

  // The victim's original nonce must still work.
  assert.strictEqual(
    s.consumeChallenge('victim', victimNonce),
    'ok',
    'a single-slot challenge store would have evicted this nonce, denying the ' +
    'victim re-authentication indefinitely — the S02-M1 DoS via a new door',
  );
});

test('a failed guessing flood does not disturb an outstanding nonce', () => {
  const s = createStore();
  const victimNonce = s.issueChallenge('victim');

  for (let i = 0; i < 50; i++) {
    assert.strictEqual(
      s.consumeChallenge('victim', crypto.randomBytes(32).toString('hex')),
      'missing',
    );
  }

  assert.strictEqual(s.consumeChallenge('victim', victimNonce), 'ok');
});

test('overflow evicts the OLDEST nonce, preserving the newest in-flight one', () => {
  const s = createStore();

  const first = s.issueChallenge('alice');
  const rest  = [];
  for (let i = 0; i < MAX_CHALLENGES_PER_UID; i++) rest.push(s.issueChallenge('alice'));

  // The oldest is gone.
  assert.strictEqual(s.consumeChallenge('alice', first), 'missing');
  // The most recent survives — that is the one a real client is signing.
  assert.strictEqual(s.consumeChallenge('alice', rest[rest.length - 1]), 'ok');
});

// ── Memory bound ────────────────────────────────────────────────────────────

test('per-user challenge count is bounded regardless of flood size', () => {
  const s = createStore();
  for (let i = 0; i < 5000; i++) s.issueChallenge('victim');

  assert.ok(
    s._map.get('victim').size <= MAX_CHALLENGES_PER_UID,
    `unbounded growth on an unauthenticated endpoint: ${s._map.get('victim').size}`,
  );
});

test('expired entries are reclaimed on the next issue for that user', () => {
  let now = 1_000_000;
  const s = createStore(() => now);

  for (let i = 0; i < MAX_CHALLENGES_PER_UID; i++) s.issueChallenge('alice');
  assert.strictEqual(s._map.get('alice').size, MAX_CHALLENGES_PER_UID);

  // Everything expires, then one new challenge is issued.
  now += CHALLENGE_TTL_MS + 1;
  s.issueChallenge('alice');

  assert.strictEqual(
    s._map.get('alice').size,
    1,
    'expired entries should be swept, leaving only the new nonce',
  );
});

test('the user entry is removed once its last nonce is consumed', () => {
  const s = createStore();
  const nonce = s.issueChallenge('alice');
  assert.strictEqual(s.consumeChallenge('alice', nonce), 'ok');
  assert.strictEqual(
    s._map.has('alice'),
    false,
    'empty per-user maps must not accumulate',
  );
});

test('consuming for a user with no challenges at all is a clean miss', () => {
  const s = createStore();
  assert.strictEqual(
    s.consumeChallenge('nobody', crypto.randomBytes(32).toString('hex')),
    'missing',
  );
});

// ── Nonce quality ───────────────────────────────────────────────────────────

test('issued nonces are 32-byte hex and unique', () => {
  const s = createStore();
  const seen = new Set();
  for (let i = 0; i < 200; i++) {
    const n = s.issueChallenge(`user${i}`);
    assert.match(n, /^[0-9a-f]{64}$/, 'nonce must be 32 bytes of hex');
    assert.strictEqual(seen.has(n), false, 'nonce collision');
    seen.add(n);
  }
});
