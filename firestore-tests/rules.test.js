/**
 * DuoShield — Firestore Security Rules Test Suite
 *
 * Run with: npm test  (requires Firebase Emulator — see README)
 *
 * Uses @firebase/rules-unit-testing v3 which handles emulator start/stop.
 */

const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const { readFileSync } = require('fs');
const { resolve } = require('path');
const { loadRulesWithAppCheckStubbed } = require('./appCheckRules');

// ── helpers ──────────────────────────────────────────────────────────────────

const PROJECT_ID = 'duoshield-test';
const RULES_PATH = resolve(__dirname, '../firestore.rules');

let testEnv;

/** Shorthand: get a Firestore handle as a specific authenticated user. */
function asUser(uid) {
  return testEnv.authenticatedContext(uid).firestore();
}

/** Shorthand: get a Firestore handle with no authentication. */
function asAnon() {
  return testEnv.unauthenticatedContext().firestore();
}

// ── setup / teardown ──────────────────────────────────────────────────────────

beforeAll(async () => {
  // Suite A: load the REAL rules but with appCheckVerified() forced to `true`.
  //
  // S08-H5 item 4a attached appCheckVerified() (== `request.app != null`) to the
  // seed-derived recovery/backup + duress paths. @firebase/rules-unit-testing v3
  // has no App Check support — request.app is ALWAYS null in the emulator — so
  // loading the rules verbatim would make every existing test against those paths
  // fail for a reason that has nothing to do with the auth/lock logic they cover.
  //
  // Stubbing the helper to `return true;` isolates this suite to exactly the
  // property it has always tested: that the auth/ownership/lock logic is correct.
  // The complementary proof — that the gate actually denies when App Check is
  // absent — lives in appcheck.test.js (Suite B), and the proof that every gated
  // path still carries the gate lives in appcheck-static.test.js (Suite C).
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: loadRulesWithAppCheckStubbed(RULES_PATH),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

afterAll(async () => {
  await testEnv.cleanup();
});

afterEach(async () => {
  await testEnv.clearFirestore();
});

// ── seed helpers ──────────────────────────────────────────────────────────────

/** Write a document bypassing rules (Admin SDK path via withSecurityRulesDisabled). */
async function seed(path, data) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().doc(path).set(data);
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// USERS
// ─────────────────────────────────────────────────────────────────────────────

describe('/users/{uid}', () => {
  beforeEach(async () => {
    await seed('users/alice', { displayName: 'Alice', fcmToken: 'tok_alice' });
  });

  test('any signed-in user can read any user doc', async () => {
    await assertSucceeds(asUser('bob').doc('users/alice').get());
  });

  test('unauthenticated user cannot read', async () => {
    await assertFails(asAnon().doc('users/alice').get());
  });

  test('owner can write their own doc', async () => {
    await assertSucceeds(
      asUser('alice').doc('users/alice').set({ displayName: 'Alice2' })
    );
  });

  test('non-owner cannot write another user doc', async () => {
    await assertFails(
      asUser('bob').doc('users/alice').set({ displayName: 'Hacked' })
    );
  });

  test('unauthenticated cannot write', async () => {
    await assertFails(
      asAnon().doc('users/alice').set({ displayName: 'X' })
    );
  });

  // ── S01-L2 regression tests ────────────────────────────────────────────────
  // `write` (create+update) had no field/shape validation, so the owner could
  // publish arbitrary content into this world-readable doc (same content-injection
  // class as S01-M2 on identities/{userId}, but here on users/{uid}).
  test('S01-L2: owner can write all allow-listed fields with valid shapes', async () => {
    await assertSucceeds(
      asUser('alice').doc('users/alice').set({
        displayName: 'Alice',
        fcmToken: 'tok_abc',
        platform: 'android',
        photoUrl: 'https://example.com/a.jpg',
        updatedAt: Date.now(),
      })
    );
  });

  test('S01-L2: owner cannot write a field outside the allow-list', async () => {
    await assertFails(
      asUser('alice').doc('users/alice').set({
        displayName: 'Alice',
        payload: '<img src=x onerror=alert(1)>',
      })
    );
  });

  test('S01-L2: owner cannot write an oversized displayName', async () => {
    await assertFails(
      asUser('alice').doc('users/alice').set({
        displayName: 'x'.repeat(500),
      })
    );
  });

  test('S01-L2: owner cannot write a non-string fcmToken', async () => {
    await assertFails(
      asUser('alice').doc('users/alice').set({
        displayName: 'Alice',
        fcmToken: { evil: true },
      })
    );
  });
});

describe('/users/{uid}/public_keys/{doc}', () => {
  beforeEach(async () => {
    await seed('users/alice/public_keys/bundle', {
      identityKey: 'ik_alice',
      preKeys: [{ id: 1, key: 'pk1' }],
      // S01-H1: a realistic pre-key pool so the cross-user size-decrease
      // (consume-only) invariant can be exercised.
      oneTimePreKeys: [
        { id: 1, key: 'otpk1' },
        { id: 2, key: 'otpk2' },
      ],
    });
  });

  test('any signed-in user can read a key bundle', async () => {
    await assertSucceeds(
      asUser('bob').doc('users/alice/public_keys/bundle').get()
    );
  });

  test('unauthenticated cannot read key bundle', async () => {
    await assertFails(asAnon().doc('users/alice/public_keys/bundle').get());
  });

  test('owner can create their key bundle', async () => {
    await assertSucceeds(
      asUser('carol').doc('users/carol/public_keys/bundle').set({
        identityKey: 'ik_carol',
        preKeys: [],
      })
    );
  });

  test('non-owner cannot create key bundle', async () => {
    await assertFails(
      asUser('bob').doc('users/carol/public_keys/bundle').set({
        identityKey: 'ik_hijack',
        preKeys: [],
      })
    );
  });

  test('any signed-in user can consume a one-time pre-key (oneTimePreKeys + updatedAt only)', async () => {
    // Legit consume: arrayRemove one entry → list shrinks from 2 → 1.
    await assertSucceeds(
      asUser('bob').doc('users/alice/public_keys/bundle').update({
        oneTimePreKeys: [{ id: 2, key: 'otpk2' }],
        updatedAt: 2000,
      })
    );
  });

  // ── S01-H1: cross-user prekey writes must be consume-only (list may only shrink) ──
  test('S01-H1: non-owner cannot GROW the pre-key pool (plant attacker prekeys)', async () => {
    await assertFails(
      asUser('bob').doc('users/alice/public_keys/bundle').update({
        oneTimePreKeys: [
          { id: 1, key: 'otpk1' },
          { id: 2, key: 'otpk2' },
          { id: 3, key: 'attacker_otpk' },
        ],
        updatedAt: 2000,
      })
    );
  });

  test('S01-H1: non-owner cannot SAME-SIZE swap the pool with attacker prekeys', async () => {
    await assertFails(
      asUser('bob').doc('users/alice/public_keys/bundle').update({
        oneTimePreKeys: [
          { id: 1, key: 'attacker_a' },
          { id: 2, key: 'attacker_b' },
        ],
        updatedAt: 2000,
      })
    );
  });

  test('S01-H1: owner CAN still grow their own pre-key pool (refresh / arrayUnion)', async () => {
    await assertSucceeds(
      asUser('alice').doc('users/alice/public_keys/bundle').update({
        oneTimePreKeys: [
          { id: 1, key: 'otpk1' },
          { id: 2, key: 'otpk2' },
          { id: 3, key: 'otpk3' },
        ],
        updatedAt: 2000,
      })
    );
  });

  test('non-owner cannot overwrite identityKey via cross-user update (F19)', async () => {
    await assertFails(
      asUser('bob').doc('users/alice/public_keys/bundle').update({
        identityKey: 'hijacked_ik',
      })
    );
  });

  test('non-owner cannot smuggle identityKey alongside oneTimePreKeys in the same update (F19)', async () => {
    await assertFails(
      asUser('bob').doc('users/alice/public_keys/bundle').update({
        oneTimePreKeys: [],
        identityKey: 'hijacked_ik',
      })
    );
  });

  test('owner can still update identityKey/signedPreKey on their own bundle', async () => {
    await assertSucceeds(
      asUser('alice').doc('users/alice/public_keys/bundle').update({
        identityKey: 'rotated_ik',
      })
    );
  });

  test('owner can delete their key bundle', async () => {
    await assertSucceeds(
      asUser('alice').doc('users/alice/public_keys/bundle').delete()
    );
  });

  test('non-owner cannot delete key bundle', async () => {
    await assertFails(
      asUser('bob').doc('users/alice/public_keys/bundle').delete()
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// CHATS
// ─────────────────────────────────────────────────────────────────────────────

describe('/chats/{chatId}', () => {
  const CHAT_ID = 'chat_ab';

  beforeEach(async () => {
    await seed(`chats/${CHAT_ID}`, {
      participants: ['alice', 'bob'],
      lastMessage: 'hey',
    });
  });

  test('participant can read the chat', async () => {
    await assertSucceeds(asUser('alice').doc(`chats/${CHAT_ID}`).get());
  });

  test('non-participant cannot read the chat', async () => {
    await assertFails(asUser('eve').doc(`chats/${CHAT_ID}`).get());
  });

  test('unauthenticated cannot read', async () => {
    await assertFails(asAnon().doc(`chats/${CHAT_ID}`).get());
  });

  test('client cannot create a chat (server-only via Admin SDK)', async () => {
    // The create rule is intentionally `if false` — chat docs are created exclusively
    // by the server's /createChat endpoint using the Admin SDK (F6 fix).
    await assertFails(
      asUser('alice').doc('chats/chat_ac').set({
        participants: ['alice', 'carol'],
      })
    );
  });

  test('user cannot create a chat without themselves', async () => {
    await assertFails(
      asUser('alice').doc('chats/chat_bc').set({
        participants: ['bob', 'carol'],
      })
    );
  });

  test('participant can update the chat (e.g. lastMessage)', async () => {
    await assertSucceeds(
      asUser('bob').doc(`chats/${CHAT_ID}`).update({ lastMessage: 'hi' })
    );
  });

  test('non-participant cannot update', async () => {
    await assertFails(
      asUser('eve').doc(`chats/${CHAT_ID}`).update({ lastMessage: 'owned' })
    );
  });

  // ── S01-H3: partner-owned display name / photo cannot be overwritten ──────────
  // Convention (inverted vs presence keys): a participant writes the key suffixed
  // with the PARTNER's uid (their own name, for the partner to read) and only
  // READS the key suffixed with their OWN uid. So writing partnerName_<self> is
  // never legitimate and is how the partner's shown name would be spoofed.
  test('S01-H3: participant can set the partner-suffixed name (their own display name)', async () => {
    await assertSucceeds(
      asUser('alice').doc(`chats/${CHAT_ID}`).update({ partnerName_bob: 'Alice' })
    );
  });

  test('S01-H3: participant CANNOT overwrite the self-suffixed partnerName (partner-owned)', async () => {
    await assertFails(
      asUser('alice').doc(`chats/${CHAT_ID}`).update({ partnerName_alice: 'Spoofed' })
    );
  });

  test('S01-H3: participant CANNOT overwrite the self-suffixed partnerPhotoUrl (partner-owned)', async () => {
    await assertFails(
      asUser('alice').doc(`chats/${CHAT_ID}`).update({ partnerPhotoUrl_alice: 'https://evil/x.png' })
    );
  });
});

describe('/chats/{chatId}/messages/{msgId}', () => {
  const CHAT_ID = 'chat_ab';
  const MSG_ID  = 'msg_1';

  beforeEach(async () => {
    await seed(`chats/${CHAT_ID}`, { participants: ['alice', 'bob'] });
    await seed(`chats/${CHAT_ID}/messages/${MSG_ID}`, {
      sender: 'alice',
      text: 'ciphertext_alice',
      sigType: 3,
      type: 'text',
      isEncrypted: true,
      status: 'sent',
      timestamp: 1000,
    });
  });

  test('participant can read messages', async () => {
    await assertSucceeds(
      asUser('alice').doc(`chats/${CHAT_ID}/messages/${MSG_ID}`).get()
    );
  });

  test('non-participant cannot read messages', async () => {
    await assertFails(
      asUser('eve').doc(`chats/${CHAT_ID}/messages/${MSG_ID}`).get()
    );
  });

  test('participant can write a message', async () => {
    await assertSucceeds(
      asUser('bob').doc(`chats/${CHAT_ID}/messages/msg_2`).set({
        sender: 'bob',
        text: 'hey',
        timestamp: 2000,
        isEncrypted: true,  // required by rules (F19/F21 fix)
      })
    );
  });

  test('non-participant cannot write a message', async () => {
    await assertFails(
      asUser('eve').doc(`chats/${CHAT_ID}/messages/msg_evil`).set({
        sender: 'eve',
        text: 'injection',
        timestamp: 3000,
      })
    );
  });

  // ── S01-H2: message content (ciphertext) is sender-only mutable ───────────────
  test('S01-H2: recipient CANNOT rewrite the ciphertext of a message they did not send', async () => {
    await assertFails(
      asUser('bob').doc(`chats/${CHAT_ID}/messages/${MSG_ID}`).update({
        text: 'forged_ciphertext',
      })
    );
  });

  test('S01-H2: recipient CANNOT downgrade an encrypted message to plaintext', async () => {
    await assertFails(
      asUser('bob').doc(`chats/${CHAT_ID}/messages/${MSG_ID}`).update({
        isEncrypted: false,
        text: 'plaintext',
      })
    );
  });

  test('S01-H2: original sender CAN edit their own message content (48h edit window)', async () => {
    await assertSucceeds(
      asUser('alice').doc(`chats/${CHAT_ID}/messages/${MSG_ID}`).update({
        text: 'ciphertext_alice_v2',
        sigType: 3,
        edited: true,
      })
    );
  });

  test('S01-H2: recipient CAN still update non-content fields (e.g. delivery status)', async () => {
    await assertSucceeds(
      asUser('bob').doc(`chats/${CHAT_ID}/messages/${MSG_ID}`).update({
        status: 'read',
      })
    );
  });
});

// ─────────────────────────────────────────��───────────────────────────────────
// GROUPS
// ───────────────────────────────────────────────────────────────────────────���─

describe('/groups/{groupId}', () => {
  const GROUP_ID = 'group_1';

  beforeEach(async () => {
    await seed(`groups/${GROUP_ID}`, {
      members: ['alice', 'bob'],
      createdBy: 'alice',
      name: 'Test Group',
    });
  });

  test('member can read the group', async () => {
    await assertSucceeds(asUser('alice').doc(`groups/${GROUP_ID}`).get());
  });

  test('non-member cannot read the group', async () => {
    await assertFails(asUser('eve').doc(`groups/${GROUP_ID}`).get());
  });

  test('user can create a group they are in', async () => {
    await assertSucceeds(
      asUser('carol').doc('groups/group_2').set({
        members: ['carol', 'dave'],
        createdBy: 'carol',
        name: 'New Group',
      })
    );
  });

  test('user cannot create group without themselves in members', async () => {
    await assertFails(
      asUser('eve').doc('groups/group_3').set({
        members: ['alice', 'bob'],
        createdBy: 'alice',
        name: 'Hijack',
      })
    );
  });

  // ── S03-H1 regression tests ────────────────────────────────────────────────
  // The media-token scope check treats a groups/{id} membership as proof of
  // access to conversation {id}. Chat IDs are deterministic (SHA-256 of the two
  // sorted UIDs), so a group whose ID collides with an existing chat is a
  // capability-escalation primitive, not a legitimate document.

  test('S03-H1: cannot create a group whose id collides with an existing chat', async () => {
    const SHADOWED_CHAT = 'chat_alice_bob';
    await seed(`chats/${SHADOWED_CHAT}`, {
      participants: ['alice', 'bob'],
    });

    // Eve self-asserts membership of a group named after Alice and Bob's chat.
    // Without the exists() guard this create succeeds and buys Eve a media
    // token for their conversation.
    await assertFails(
      asUser('eve').doc(`groups/${SHADOWED_CHAT}`).set({
        members: ['eve'],
        createdBy: 'eve',
        name: 'Shadow',
      })
    );
  });

  test('S03-H1: shadow-group create is denied even for a real chat participant', async () => {
    const SHADOWED_CHAT = 'chat_alice_bob_2';
    await seed(`chats/${SHADOWED_CHAT}`, {
      participants: ['alice', 'bob'],
    });

    // The ID-collision guard is unconditional: legitimate group IDs are random
    // UUIDs (CreateGroupActivity), so a collision is never a real client flow.
    await assertFails(
      asUser('alice').doc(`groups/${SHADOWED_CHAT}`).set({
        members: ['alice', 'bob'],
        createdBy: 'alice',
        name: 'Shadow',
      })
    );
  });

  test('S03-H1: cannot create a group claiming someone else as createdBy', async () => {
    await assertFails(
      asUser('carol').doc('groups/group_4').set({
        members: ['carol', 'alice'],
        createdBy: 'alice',
        name: 'Wrong creator',
      })
    );
  });

  test('S03-H1: cannot create a group whose createdBy is not in members', async () => {
    await assertFails(
      asUser('carol').doc('groups/group_5').set({
        members: ['carol'],
        createdBy: 'dave',
        name: 'Orphan creator',
      })
    );
  });

  // ── S01-L1 regression tests ────────────────────────────────────────────────
  // "full shape validation still absent" per BUG_TRACKER — the ID-squatting half
  // was already closed by S03-H1 above.

  test('S01-L1: cannot create a group with a field outside the allow-list', async () => {
    await assertFails(
      asUser('carol').doc('groups/group_6').set({
        members: ['carol'],
        createdBy: 'carol',
        name: 'New Group',
        adminOnly: true,
      })
    );
  });

  test('S01-L1: cannot create a group with an empty name', async () => {
    await assertFails(
      asUser('carol').doc('groups/group_7').set({
        members: ['carol'],
        createdBy: 'carol',
        name: '',
      })
    );
  });

  test('S01-L1: cannot create a group with a non-string name', async () => {
    await assertFails(
      asUser('carol').doc('groups/group_8').set({
        members: ['carol'],
        createdBy: 'carol',
        name: { evil: true },
      })
    );
  });

  test('S01-L1: cannot create a group with more than 256 members', async () => {
    const members = Array.from({ length: 257 }, (_, i) => `u${i}`);
    members[0] = 'carol';
    await assertFails(
      asUser('carol').doc('groups/group_9').set({
        members,
        createdBy: 'carol',
        name: 'Huge Group',
      })
    );
  });

  test('member can update the group', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}`).update({ name: 'Renamed' })
    );
  });

  test('member cannot change createdBy on the group (F27 escalation guard)', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}`).update({ createdBy: 'bob' })
    );
  });

  test('member can update other fields as long as createdBy is unchanged', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}`).update({
        name: 'Renamed Again',
        createdBy: 'alice',
      })
    );
  });

  test('creator can delete the group', async () => {
    await assertSucceeds(asUser('alice').doc(`groups/${GROUP_ID}`).delete());
  });

  test('non-creator member cannot delete the group', async () => {
    await assertFails(asUser('bob').doc(`groups/${GROUP_ID}`).delete());
  });
});

describe('/groups/{groupId}/messages/{msgId}', () => {
  const GROUP_ID = 'group_1';

  beforeEach(async () => {
    await seed(`groups/${GROUP_ID}`, {
      members: ['alice', 'bob'],
      createdBy: 'alice',
    });
    await seed(`groups/${GROUP_ID}/messages/msg_1`, {
      sender: 'alice',
      text: 'hi group',
    });
  });

  test('member can read group messages', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_1`).get()
    );
  });

  test('non-member cannot read group messages', async () => {
    await assertFails(
      asUser('eve').doc(`groups/${GROUP_ID}/messages/msg_1`).get()
    );
  });

  test('member can write a group message', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_2`).set({
        sender: 'bob',
        text: 'reply',
        isEncrypted: true,  // required by rules (F28 fix)
      })
    );
  });

  // ── S01-M1 regression tests ────────────────────────────────────────────────
  // No cap previously existed on message document size — a member could
  // bulk-write oversized documents (cost/DoS). The membership TOCTOU itself is
  // accepted per the audit's own recommendation and is not testable as a
  // single-rule-evaluation unit test.

  test('S01-M1: member cannot write an oversized group message', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_toolong`).set({
        sender: 'bob',
        text: 'x'.repeat(100000),
        isEncrypted: true,
      })
    );
  });

  test('S01-M1: member can still write an empty-text media message', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_media`).set({
        sender: 'bob',
        text: '',
        isEncrypted: true,
        type: 'image',
      })
    );
  });

  // ── S01-M4 regression tests ────────────────────────────────────────────────
  // delete previously checked only `sender == auth.uid`, with no re-check of
  // current membership (unlike read/create, which both call get() on the group).

  test('S01-M4: sender who is still a member can delete their own message', async () => {
    await assertSucceeds(
      asUser('alice').doc(`groups/${GROUP_ID}/messages/msg_1`).delete()
    );
  });

  test('S01-M4: removed ex-member cannot delete their own historical message', async () => {
    // Carol sent msg_carol while a member, then was removed from the group.
    await seed(`groups/${GROUP_ID}/messages/msg_carol`, {
      sender: 'carol',
      text: 'about to leave',
    });
    await seed(`groups/${GROUP_ID}`, {
      members: ['alice', 'bob'], // carol no longer present
      createdBy: 'alice',
    });
    await assertFails(
      asUser('carol').doc(`groups/${GROUP_ID}/messages/msg_carol`).delete()
    );
  });

  test('non-sender member cannot delete another member message', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_1`).delete()
    );
  });
});


  test('S07-L1: non-empty new ciphertext must set aadV1 and matching message id', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_no_aad`).set({
        id: 'msg_no_aad',
        sender: 'bob',
        text: 'ciphertext_without_aad_marker',
        isEncrypted: true,
      })
    );
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_wrong_id`).set({
        id: 'some_other_document',
        sender: 'bob',
        text: 'ciphertext_with_wrong_id',
        isEncrypted: true,
        aadV1: true,
      })
    );
  });

  test('S07-L1: new AAD-bound ciphertext with matching message id is accepted', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_aad`).set({
        id: 'msg_aad',
        sender: 'bob',
        text: 'ciphertext_with_aad',
        isEncrypted: true,
        aadV1: true,
      })
    );
  });

  test('S07-L1: empty-text media message remains compatible without aadV1', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_media_without_aad`).set({
        id: 'msg_media_without_aad',
        sender: 'bob',
        text: '',
        isEncrypted: true,
        type: 'image',
      })
    );
  });
describe('/groups/{groupId}/keys/{memberUid}', () => {
  const GROUP_ID = 'group_1';

  beforeEach(async () => {
    await seed(`groups/${GROUP_ID}`, {
      members: ['alice', 'bob'],
      createdBy: 'alice',
    });
    await seed(`groups/${GROUP_ID}/keys/bob`, { encryptedKey: 'enc_key_bob' });
  });

  test('member can read their own key', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/keys/bob`).get()
    );
  });

  test('member cannot read another member key', async () => {
    await assertFails(
      asUser('alice').doc(`groups/${GROUP_ID}/keys/bob`).get()
    );
  });

  test('group creator can write a key for any member (key distribution)', async () => {
    await assertSucceeds(
      asUser('alice').doc(`groups/${GROUP_ID}/keys/bob`).set({
        encryptedKey: 'new_key',
      })
    );
  });

  test('non-creator member cannot write another member key slot (F27)', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/keys/alice`).set({
        encryptedKey: 'substituted_key',
      })
    );
  });

  test('non-creator member cannot write even their own key slot (F27)', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/keys/bob`).set({
        encryptedKey: 'self_write_still_denied',
      })
    );
  });

  test('non-member cannot write a key', async () => {
    await assertFails(
      asUser('eve').doc(`groups/${GROUP_ID}/keys/alice`).set({
        encryptedKey: 'steal',
      })
    );
  });

  test('member cannot escalate to creator by rewriting createdBy then writing a key (F27 two-step bypass)', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}`).update({ createdBy: 'bob' })
    );
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/keys/alice`).set({
        encryptedKey: 'escalated_key',
      })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// CALLS
// ─────────────────────────────────────────────────────────────────────────────

describe('/calls/{callId}', () => {
  const CALL_ID = 'call_1';
  // The calls create rule (F6 fix) checks that the caller and callee are both
  // participants in an existing chat doc.  Seed that chat so the test passes.
  const CHAT_ID_AB    = 'chat_alice_bob';
  const CHAT_ID_AC    = 'chat_alice_carol';

  beforeEach(async () => {
    await seed(`chats/${CHAT_ID_AB}`, { participants: ['alice', 'bob']   });
    await seed(`chats/${CHAT_ID_AC}`, { participants: ['alice', 'carol'] });
    // Seed the pre-existing call used by read/update tests.
    await seed(`calls/${CALL_ID}`, {
      callerId: 'alice',
      calleeId: 'bob',
      chatId:   CHAT_ID_AB,
      status:   'ringing',
    });
  });

  test('caller can create a call doc', async () => {
    // Rules require: callerId == auth.uid, chatId exists, both parties in chat.
    await assertSucceeds(
      asUser('alice').doc('calls/call_2').set({
        callerId: 'alice',
        calleeId: 'carol',
        chatId:   CHAT_ID_AC,
        status:   'ringing',
      })
    );
  });

  test('cannot create a call with a different callerId', async () => {
    await assertFails(
      asUser('alice').doc('calls/call_3').set({
        callerId: 'bob',
        calleeId: 'carol',
        chatId:   CHAT_ID_AC,
        status:   'ringing',
      })
    );
  });

  test('caller can read call doc', async () => {
    await assertSucceeds(asUser('alice').doc(`calls/${CALL_ID}`).get());
  });

  test('callee can read call doc', async () => {
    await assertSucceeds(asUser('bob').doc(`calls/${CALL_ID}`).get());
  });

  test('outsider cannot read call doc', async () => {
    await assertFails(asUser('eve').doc(`calls/${CALL_ID}`).get());
  });

  test('participant can update call doc (accept/reject)', async () => {
    await assertSucceeds(
      asUser('bob').doc(`calls/${CALL_ID}`).update({ status: 'accepted' })
    );
  });
});

describe('/calls/{callId}/callerCandidates', () => {
  const CALL_ID = 'call_1';

  beforeEach(async () => {
    await seed(`calls/${CALL_ID}`, {
      callerId: 'alice',
      calleeId: 'bob',
      status: 'ringing',
    });
  });

  test('caller can write ICE candidates', async () => {
    await assertSucceeds(
      asUser('alice').doc(`calls/${CALL_ID}/callerCandidates/cand_1`).set({
        candidate: 'candidate:...',
      })
    );
  });

  test('callee can read caller ICE candidates', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc(`calls/${CALL_ID}/callerCandidates/cand_1`)
        .set({ candidate: 'candidate:...' });
    });
    await assertSucceeds(
      asUser('bob').doc(`calls/${CALL_ID}/callerCandidates/cand_1`).get()
    );
  });

  test('outsider cannot read or write ICE candidates', async () => {
    await assertFails(
      asUser('eve').doc(`calls/${CALL_ID}/callerCandidates/cand_1`).set({
        candidate: 'candidate:inject',
      })
    );
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// IDENTITIES
// ─────────────────────────────────────────────────────────────────────────────

describe('/identities/{userId}', () => {
  const USER_ID = 'ABCDE-FGHIJ-KLM';

  beforeEach(async () => {
    await seed(`identities/${USER_ID}`, {
      uid: USER_ID,
      identityPubKeyHash: 'hash_abc',
    });
  });

  test('any signed-in user can read an identity (contact lookup)', async () => {
    await assertSucceeds(asUser('bob').doc(`identities/${USER_ID}`).get());
  });

  test('unauthenticated cannot read identity', async () => {
    await assertFails(asAnon().doc(`identities/${USER_ID}`).get());
  });

  test('owner can re-write their identity with the same uid/hash (no-op merge)', async () => {
    await assertSucceeds(
      asUser(USER_ID).doc(`identities/${USER_ID}`).update({
        uid: USER_ID,
        identityPubKeyHash: 'hash_abc',
      })
    );
  });

  // S01-M2 regression test: identities/{userId} is world-readable (see the
  // "any signed-in user can read" test above), so an unconstrained update let the
  // owner publish arbitrary content into a globally-readable doc. No client write
  // path ever sets a field other than uid/identityPubKeyHash, so this previously
  // succeeding write (formerly `.update({ label: 'legacy' })`) must now be
  // rejected — 'label' was never a real field the app writes, it's standing in for
  // any attacker-chosen key.
  test('S01-M2: owner cannot add a field outside the uid/hash allow-list', async () => {
    await assertFails(
      asUser(USER_ID).doc(`identities/${USER_ID}`).update({ label: 'legacy' })
    );
  });

  test('S01-M2: owner cannot inject arbitrary content via an unexpected field', async () => {
    await assertFails(
      asUser(USER_ID).doc(`identities/${USER_ID}`).update({
        bio: '<script>alert(1)</script>',
      })
    );
  });

  test('cannot write identity with a different uid field (identity hijack)', async () => {
    await assertFails(
      asUser(USER_ID).doc(`identities/${USER_ID}`).set({
        uid: 'bob',
        identityPubKeyHash: 'hash_abc',
      })
    );
  });

  test('cannot overwrite someone else identity even with correct uid field mismatch', async () => {
    await assertFails(
      asUser('eve').doc(`identities/${USER_ID}`).set({
        uid: USER_ID,
        identityPubKeyHash: 'eve_hijack',
      })
    );
  });

  test('cannot overwrite another deterministic identity path (recovery takeover)', async () => {
    await assertFails(
      asUser('eve').doc(`identities/${USER_ID}`).set({
        uid: USER_ID,
        identityPubKeyHash: 'eve_hash',
      })
    );
  });

  test('owner cannot replace the bound public-key hash', async () => {
    await assertFails(
      asUser(USER_ID).doc(`identities/${USER_ID}`).update({
        identityPubKeyHash: 'different_key',
      })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// LEGACY COLLECTIONS — retired, must stay fully denied
// ─────────────────────────────────────────────────────────────────────────────
// 2026-08-04 security review: /rooms and /conversations have zero remaining
// callers in the app or server (PairingActivity/PairingManager were deleted;
// /chats supersedes /conversations). These tests guard against the rules ever
// being loosened back open for a flow the product no longer has.

describe('/rooms/{code} (retired)', () => {
  const CODE = 'ABC123';

  beforeEach(async () => {
    await seed(`rooms/${CODE}`, {
      creatorUid: 'alice',
      joinerUid: '',
      status: 'waiting',
    });
  });

  test('creator cannot read a legacy room', async () => {
    await assertFails(asUser('alice').doc(`rooms/${CODE}`).get());
  });

  test('no authenticated user can create a legacy room', async () => {
    await assertFails(
      asUser('alice').doc('rooms/new-code').set({ creatorUid: 'alice', joinerUid: '', status: 'waiting' })
    );
  });

  test('no authenticated user can update a legacy room', async () => {
    await assertFails(asUser('alice').doc(`rooms/${CODE}`).update({ joinerUid: 'bob' }));
  });

  test('no authenticated user can delete a legacy room', async () => {
    await assertFails(asUser('alice').doc(`rooms/${CODE}`).delete());
  });
});

describe('/conversations/{convId} (retired)', () => {
  const CONV_ID = 'legacy-conv-1';

  beforeEach(async () => {
    await seed(`conversations/${CONV_ID}`, { participants: ['alice', 'bob'] });
    await seed(`conversations/${CONV_ID}/messages/msg1`, { text: 'hi' });
  });

  test('participant cannot read a legacy conversation', async () => {
    await assertFails(asUser('alice').doc(`conversations/${CONV_ID}`).get());
  });

  test('no authenticated user can create a legacy conversation', async () => {
    await assertFails(
      asUser('alice').doc('conversations/new-conv').set({ participants: ['alice', 'bob'] })
    );
  });

  test('participant cannot update a legacy conversation', async () => {
    await assertFails(asUser('alice').doc(`conversations/${CONV_ID}`).update({ participants: ['alice'] }));
  });

  test('participant cannot read or write legacy conversation messages', async () => {
    await assertFails(asUser('alice').doc(`conversations/${CONV_ID}/messages/msg1`).get());
    await assertFails(
      asUser('alice').doc(`conversations/${CONV_ID}/messages/msg2`).set({ text: 'new' })
    );
  });
});

// ─────────────────────────────────────────────────────────���───────────────────
// RECOVERY
// ─────────────────────────────────────────────────────────────────────────────

describe('/recovery/{uid}', () => {
  beforeEach(async () => {
    await seed('recovery/alice', { blob: 'encrypted_recovery_blob' });
  });

  test('owner can read their recovery blob', async () => {
    await assertSucceeds(asUser('alice').doc('recovery/alice').get());
  });

  test('other user cannot read recovery blob', async () => {
    await assertFails(asUser('bob').doc('recovery/alice').get());
  });

  test('owner can write their recovery blob', async () => {
    await assertSucceeds(
      asUser('alice').doc('recovery/alice').set({ blob: 'updated_blob' })
    );
  });

  test('other user cannot write recovery blob', async () => {
    await assertFails(
      asUser('bob').doc('recovery/alice').set({ blob: 'stolen' })
    );
  });

  test('unauthenticated cannot read or write', async () => {
    await assertFails(asAnon().doc('recovery/alice').get());
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// BACKUPS
// ─────────────────────────────────────────────────────────────────────────────

describe('/backups/{userId}', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000, count: 5 });
  });

  test('owner can read backup meta', async () => {
    await assertSucceeds(asUser('alice').doc('backups/alice').get());
  });

  test('other user cannot read backup', async () => {
    await assertFails(asUser('bob').doc('backups/alice').get());
  });

  test('owner can create backup meta', async () => {
    await assertSucceeds(
      asUser('carol').doc('backups/carol').set({ lastBackupTs: 2000, count: 0 })
    );
  });

  test('backup meta delete is always denied', async () => {
    await assertFails(asUser('alice').doc('backups/alice').delete());
  });
});

describe('/backups/{userId}/messages/{msgId}', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000 });
    await seed('backups/alice/messages/msg_1', {
      ciphertext: 'enc_msg',
      isDeleted: false,
    });
  });

  test('owner can read backup messages', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/messages/msg_1').get()
    );
  });

  test('other user cannot read backup messages', async () => {
    await assertFails(
      asUser('bob').doc('backups/alice/messages/msg_1').get()
    );
  });

  test('owner can create a backup message', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/messages/msg_2').set({
        ciphertext: 'enc_msg_2',
        isDeleted: false,
      })
    );
  });

  test('backup message delete is always denied (use isDeleted:true)', async () => {
    await assertFails(
      asUser('alice').doc('backups/alice/messages/msg_1').delete()
    );
  });
});

describe('/backups/{userId}/contacts/{contactId}', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000 });
    await seed('backups/alice/contacts/contact_1', {
      displayName: 'Bob',
      conversationId: 'chat_ab',
    });
  });

  test('owner can read backup contacts', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/contacts/contact_1').get()
    );
  });

  test('other user cannot read backup contacts', async () => {
    await assertFails(
      asUser('bob').doc('backups/alice/contacts/contact_1').get()
    );
  });

  test('backup contact delete is always denied', async () => {
    await assertFails(
      asUser('alice').doc('backups/alice/contacts/contact_1').delete()
    );
  });
});

describe('/backups/{userId}/groups/{groupId}', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000 });
    await seed('backups/alice/groups/group_1', { id: 'group_1', name: 'Encrypted group' });
  });

  test('owner can read their group backup', async () => {
    await assertSucceeds(asUser('alice').doc('backups/alice/groups/group_1').get());
  });

  test('other user cannot read a group backup', async () => {
    await assertFails(asUser('bob').doc('backups/alice/groups/group_1').get());
  });

  test('owner can write their group backup', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/groups/group_2').set({ id: 'group_2', name: 'Encrypted group' })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// BACKUP LOGS
// ─────────────────────────────────────────────────────────────────────────────

describe('/backup_logs/{logId}', () => {
  // Matches the exact shape BackupManager.logEvent always writes:
  // {uid, event, ts, count, error?} — see BackupManager.java:1090-1108.
  test('owner can create a backup log entry with the real logEvent shape', async () => {
    await assertSucceeds(
      asUser('alice').doc('backup_logs/log_1').set({
        uid: 'alice',
        event: 'backup_complete',
        ts: 1000,
        count: 42,
      })
    );
  });

  test('owner can create a backup log entry that includes the optional error field', async () => {
    await assertSucceeds(
      asUser('alice').doc('backup_logs/log_1b').set({
        uid: 'alice',
        event: 'backup_failed',
        ts: 1000,
        count: 0,
        error: 'network timeout',
      })
    );
  });

  test('cannot create a log entry with a different uid', async () => {
    await assertFails(
      asUser('alice').doc('backup_logs/log_2').set({
        uid: 'bob',
        event: 'backup_complete',
        ts: 1000,
        count: 0,
      })
    );
  });

  // ── S01-M3 regression tests ──────────────────────────────────────────────
  // Previously only `uid` ownership was checked — no shape/size validation at
  // all, so an authenticated caller could write arbitrary fields/values into
  // unbounded backup_logs/* documents (write-amplification / storage-cost DoS
  // and a stored-content-injection primitive for any downstream log consumer).

  test('S01-M3: cannot create a log entry with a field outside the allow-list', async () => {
    await assertFails(
      asUser('alice').doc('backup_logs/log_3').set({
        uid: 'alice',
        event: 'backup_complete',
        ts: 1000,
        count: 0,
        payload: '<script>alert(1)</script>',
      })
    );
  });

  test('S01-M3: cannot create a log entry with an event outside the known set', async () => {
    await assertFails(
      asUser('alice').doc('backup_logs/log_4').set({
        uid: 'alice',
        event: 'totally_made_up_event',
        ts: 1000,
        count: 0,
      })
    );
  });

  test('S01-M3: cannot create a log entry missing required fields', async () => {
    await assertFails(
      asUser('alice').doc('backup_logs/log_5').set({
        uid: 'alice',
        event: 'backup_complete',
      })
    );
  });

  test('S01-M3: cannot create a log entry with an oversized error message', async () => {
    await assertFails(
      asUser('alice').doc('backup_logs/log_6').set({
        uid: 'alice',
        event: 'backup_failed',
        ts: 1000,
        count: 0,
        error: 'x'.repeat(2001),
      })
    );
  });

  test('S01-M3: cannot create a log entry with a non-numeric count', async () => {
    await assertFails(
      asUser('alice').doc('backup_logs/log_7').set({
        uid: 'alice',
        event: 'backup_complete',
        ts: 1000,
        count: 'not-a-number',
      })
    );
  });

  test('nobody can read backup logs', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('backup_logs/log_seed').set({
        uid: 'alice',
        event: 'backup_complete',
      });
    });
    await assertFails(asUser('alice').doc('backup_logs/log_seed').get());
  });

  test('nobody can delete backup logs', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('backup_logs/log_seed').set({ uid: 'alice' });
    });
    await assertFails(asUser('alice').doc('backup_logs/log_seed').delete());
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// SERVER HEALTH (internal only)
// ─────────────────────────────────────────────────────────────���───────────────

describe('/_server_health/{doc}', () => {
  test('no client can read server health docs', async () => {
    await assertFails(asUser('alice').doc('_server_health/status').get());
  });

  test('no client can write server health docs', async () => {
    await assertFails(
      asUser('alice').doc('_server_health/status').set({ ok: true })
    );
  });

  test('unauthenticated cannot read or write either', async () => {
    await assertFails(asAnon().doc('_server_health/status').get());
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// ACCOUNT LOCK  (Issue 1 — one-way latch enforcement)
// ──────────────────────────────────────────────────��──────────────────────────

describe('/accountLock/{accountId}', () => {
  // Client create now additionally requires duressEligibility/{uid}.eligible.
  // S06-M1: without that predicate any account could complete the whole duress
  // flow and watch its own lock doc appear, learning that the feature exists,
  // how it fires and what it writes — the question the eligibility gate exists
  // to keep unanswerable. Alice is enrolled throughout this block; carol is not.
  beforeEach(async () => {
    await seed('duressEligibility/alice', { eligible: true });
  });

  test('owner can read their own lock doc', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertSucceeds(asUser('alice').doc('accountLock/alice').get());
  });

  test('stranger cannot read another account\'s lock doc', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(asUser('bob').doc('accountLock/alice').get());
  });

  test('unauthenticated cannot read lock doc', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(asAnon().doc('accountLock/alice').get());
  });

  test('enrolled owner can create a lock doc with locked=true', async () => {
    await assertSucceeds(
      asUser('alice').doc('accountLock/alice').set({ locked: true, lockedAt: new Date() })
    );
  });

  // ── S06-M1: eligibility is now a rules-level boundary, not a UI hint ───────
  test('NON-ENROLLED owner CANNOT create a lock doc (no eligibility doc)', async () => {
    await assertFails(
      asUser('carol').doc('accountLock/carol').set({ locked: true, lockedAt: new Date() })
    );
  });

  test('owner with eligible=false CANNOT create a lock doc', async () => {
    await seed('duressEligibility/dave', { eligible: false });
    await assertFails(
      asUser('dave').doc('accountLock/dave').set({ locked: true, lockedAt: new Date() })
    );
  });

  test('owner CANNOT self-grant eligibility to unlock the create path', async () => {
    await assertFails(
      asUser('carol').doc('duressEligibility/carol').set({ eligible: true })
    );
  });

  test('owner CANNOT create a lock doc with locked=false', async () => {
    await assertFails(
      asUser('alice').doc('accountLock/alice').set({ locked: false })
    );
  });

  test('owner CANNOT create a lock doc without the locked field', async () => {
    await assertFails(
      asUser('alice').doc('accountLock/alice').set({ lockedAt: new Date() })
    );
  });

  // ── S06-L6: client updates are denied outright, not merely latch-guarded ───
  // Permitting update at all — even with the locked==true guard — let a client
  // holding the victim's uid re-set the doc with a fresh lockedAt, repeatedly.
  // The latch held; the forensic record of WHEN the duress event happened did
  // not. Locking an already-locked account is the server's job via /duress-lock,
  // which uses the Admin SDK and only writes lockedAt when one is not present.
  test('owner CANNOT update an existing lock doc even keeping locked=true', async () => {
    await seed('accountLock/alice', { locked: true, lockedAt: new Date(1000) });
    await assertFails(
      asUser('alice').doc('accountLock/alice').update({ locked: true, lockedAt: new Date() })
    );
  });

  test('owner CANNOT rewrite lockedAt to obscure the trigger time', async () => {
    await seed('accountLock/alice', { locked: true, lockedAt: new Date(1000) });
    await assertFails(
      asUser('alice').doc('accountLock/alice').update({ lockedAt: new Date() })
    );
  });

  test('owner CANNOT re-lock an unfrozen account to strip rotationRequired', async () => {
    await seed('accountLock/alice', { locked: false, rotationRequired: true, lockedAt: new Date(1000) });
    await assertFails(
      asUser('alice').doc('accountLock/alice').set({ locked: true, lockedAt: new Date() })
    );
  });

  test('owner CANNOT update doc to set locked=false (one-way latch)', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(
      asUser('alice').doc('accountLock/alice').update({ locked: false })
    );
  });

  test('owner CANNOT delete lock doc', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(asUser('alice').doc('accountLock/alice').delete());
  });

  test('stranger CANNOT write another account\'s lock doc', async () => {
    await assertFails(
      asUser('bob').doc('accountLock/alice').set({ locked: true })
    );
  });

  test('unauthenticated CANNOT write lock doc', async () => {
    await assertFails(
      asAnon().doc('accountLock/alice').set({ locked: true })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// DURESS ELIGIBILITY  (admin-only writes, owner-only read)
// ─────────────────────────────────────────────────────────────────────────────

describe('/duressEligibility/{accountId}', () => {
  beforeEach(async () => {
    await seed('duressEligibility/alice', { eligible: true });
  });

  test('owner can read their own eligibility doc', async () => {
    await assertSucceeds(asUser('alice').doc('duressEligibility/alice').get());
  });

  test('stranger cannot read another account\'s eligibility doc', async () => {
    await assertFails(asUser('bob').doc('duressEligibility/alice').get());
  });

  test('unauthenticated cannot read eligibility doc', async () => {
    await assertFails(asAnon().doc('duressEligibility/alice').get());
  });

  test('owner CANNOT write their own eligibility doc', async () => {
    await assertFails(
      asUser('alice').doc('duressEligibility/alice').set({ eligible: true })
    );
  });

  test('owner CANNOT update their own eligibility doc', async () => {
    await assertFails(
      asUser('alice').doc('duressEligibility/alice').update({ eligible: false })
    );
  });

  test('owner CANNOT delete their eligibility doc', async () => {
    await assertFails(asUser('alice').doc('duressEligibility/alice').delete());
  });

  test('stranger CANNOT write any eligibility doc', async () => {
    await assertFails(
      asUser('bob').doc('duressEligibility/carol').set({ eligible: true })
    );
  });

  test('unauthenticated CANNOT write any eligibility doc', async () => {
    await assertFails(
      asAnon().doc('duressEligibility/alice').set({ eligible: true })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// _duressNonces  (server-managed: no client reads or writes)  — S06-L3
//
// `allow read, write: if false`, so these are correct today. They were entirely
// untested, which is the finding: a future edit could relax the deny-all
// silently. A leaked nonce READ is the one that matters — a nonce is the sole
// credential /duress-lock accepts, so being able to read another account's
// pending nonce turns S06-H3's "the lock silently never happens" failure mode
// into an active attack: consume the victim's nonce yourself and their duress
// trigger can never lock the account.
//
// The nonce doc also holds {uid, expiresAt}, so client read access would let any
// authenticated user enumerate which accounts have a duress trigger in flight —
// the exact event the feature exists to make undetectable.
// ─────────────────────────────────────────────────────────��───────────────────

describe('/_duressNonces/{nonce}', () => {
  const NONCE = 'a'.repeat(64);

  beforeEach(async () => {
    await seed(`_duressNonces/${NONCE}`, {
      uid: 'alice',
      expiresAt: new Date(Date.now() + 86_400_000),
    });
  });

  test('owner of the bound uid CANNOT read their own nonce doc', async () => {
    await assertFails(asUser('alice').doc(`_duressNonces/${NONCE}`).get());
  });

  test('authenticated stranger CANNOT read a nonce doc', async () => {
    await assertFails(asUser('bob').doc(`_duressNonces/${NONCE}`).get());
  });

  test('unauthenticated CANNOT read a nonce doc', async () => {
    await assertFails(asAnon().doc(`_duressNonces/${NONCE}`).get());
  });

  test('authenticated user CANNOT create a nonce doc', async () => {
    await assertFails(
      asUser('alice').doc(`_duressNonces/${'b'.repeat(64)}`).set({
        uid: 'alice',
        expiresAt: new Date(Date.now() + 86_400_000),
      })
    );
  });

  test('authenticated user CANNOT re-bind an existing nonce to themselves', async () => {
    await assertFails(
      asUser('bob').doc(`_duressNonces/${NONCE}`).update({ uid: 'bob' })
    );
  });

  test('authenticated user CANNOT extend a nonce expiry', async () => {
    await assertFails(
      asUser('alice').doc(`_duressNonces/${NONCE}`).update({
        expiresAt: new Date(Date.now() + 10 * 365 * 86_400_000),
      })
    );
  });

  test('authenticated user CANNOT delete (consume) a nonce doc', async () => {
    await assertFails(asUser('alice').doc(`_duressNonces/${NONCE}`).delete());
  });

  test('unauthenticated CANNOT write a nonce doc', async () => {
    await assertFails(
      asAnon().doc(`_duressNonces/${'c'.repeat(64)}`).set({ uid: 'anon' })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// ACCOUNT LOCK ENFORCEMENT ON /backups  — S06-C2 part 3
//
// The duress latch used to be enforced in exactly one place in the whole system:
// a client-side `if` in RestoreFromSeedActivity, evaluated AFTER authentication
// succeeded. Since the backup key is a pure function of the seed phrase, an
// adversary holding a coerced seed could mint a token and read backups/{uid}
// straight off the REST API — never executing the client check.
//
// accountNotLocked() in the rules is the layer that survives a bypassed or
// compromised server. Rules do NOT cascade in Firestore, so it has to be
// repeated on every subcollection; gating only the parent would leave
// backups/{uid}/messages — the actual message history — fully readable. Each
// subcollection is asserted separately below for exactly that reason.
//
// The predicate is `locked != true`, not `!exists()`: unfreeze deliberately
// leaves the doc in place carrying locked:false + rotationRequired:true so the
// re-arm requirement survives a reinstall, and an exists() test would lock those
// users out of their own backups permanently.
// ─────────────────────────────────────────────────────────────────────────────

describe('/backups/{userId} under accountLock', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000, count: 5 });
    await seed('backups/alice/messages/msg_1', { ciphertext: 'enc', isDeleted: false });
    await seed('backups/alice/contacts/contact_1', { displayName: 'Bob' });
    await seed('backups/alice/groups/group_1', { id: 'group_1' });
    await seed('accountLock/alice', { locked: true, lockedAt: 1000 });
  });

  test('locked owner cannot read backup meta', async () => {
    await assertFails(asUser('alice').doc('backups/alice').get());
  });

  test('locked owner cannot read backup MESSAGES (the subcollection gate)', async () => {
    await assertFails(asUser('alice').doc('backups/alice/messages/msg_1').get());
  });

  test('locked owner cannot read backup CONTACTS', async () => {
    await assertFails(asUser('alice').doc('backups/alice/contacts/contact_1').get());
  });

  test('locked owner cannot read backup GROUPS', async () => {
    await assertFails(asUser('alice').doc('backups/alice/groups/group_1').get());
  });

  test('locked owner cannot write new backup messages', async () => {
    await assertFails(
      asUser('alice').doc('backups/alice/messages/msg_2').set({ ciphertext: 'enc2' })
    );
  });

  test('locked owner cannot overwrite an existing backup message', async () => {
    await assertFails(
      asUser('alice').doc('backups/alice/messages/msg_1').update({ ciphertext: 'tampered' })
    );
  });

  test('a lock on one account does not gate another account\'s backups', async () => {
    await assertSucceeds(asUser('carol').doc('backups/carol').set({ lastBackupTs: 1 }));
  });
});

describe('/backups/{userId} after unfreeze (locked:false + rotationRequired)', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000, count: 5 });
    await seed('backups/alice/messages/msg_1', { ciphertext: 'enc', isDeleted: false });
    // Unfreeze rewrites rather than deletes, so the doc survives with locked:false.
    await seed('accountLock/alice', {
      locked: false,
      rotationRequired: true,
      lockedAt: 1000,
      unfrozenAt: 2000,
    });
  });

  test('unfrozen owner CAN read backup meta again', async () => {
    await assertSucceeds(asUser('alice').doc('backups/alice').get());
  });

  test('unfrozen owner CAN read backup messages again', async () => {
    await assertSucceeds(asUser('alice').doc('backups/alice/messages/msg_1').get());
  });

  test('unfrozen owner CAN resume backing up', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/messages/msg_2').set({ ciphertext: 'enc2' })
    );
  });

  test('a stranger still cannot read an unfrozen account\'s backups', async () => {
    await assertFails(asUser('bob').doc('backups/alice/messages/msg_1').get());
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// WAITLIST  (server-managed: no client reads or writes)
// ─────────────────────────────────────────────────────────────────────────────

describe('/waitlist/{requestId}', () => {
  beforeEach(async () => {
    await seed('waitlist/req_001', { userId: 'alice', approved: false });
  });

  test('authenticated user CANNOT read waitlist docs', async () => {
    await assertFails(asUser('alice').doc('waitlist/req_001').get());
  });

  test('unauthenticated CANNOT read waitlist docs', async () => {
    await assertFails(asAnon().doc('waitlist/req_001').get());
  });

  test('authenticated user CANNOT create waitlist docs', async () => {
    await assertFails(
      asUser('alice').doc('waitlist/req_002').set({ userId: 'alice' })
    );
  });

  test('authenticated user CANNOT update waitlist docs', async () => {
    await assertFails(
      asUser('alice').doc('waitlist/req_001').update({ approved: true })
    );
  });

  test('authenticated user CANNOT delete waitlist docs', async () => {
    await assertFails(asUser('alice').doc('waitlist/req_001').delete());
  });

  test('unauthenticated CANNOT write waitlist docs', async () => {
    await assertFails(
      asAnon().doc('waitlist/req_003').set({ userId: 'anon' })
    );
  });
});
