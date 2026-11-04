const admin = require("firebase-admin");
const http = require("http");
const crypto = require("crypto");
const pure = require("./lib/pure");
const { createChallengeStore } = require("./lib/challengeStore");
const { verifyMintTokenSignature } = require("./lib/identityVerify");
const {
  decideScopeAccess,
  decideAvatarAccess,
  isAvatarKey,
  AVATAR_KEY_FORMAT,
  SCOPE_DENY,
} = require("./lib/mediaScope");
const { sanitizeMigratedUserFields, isValidDisplayName } = require("./lib/profileSanitize");
const { createAdminLockoutStore } = require("./lib/adminLockoutStore");
const { createAdminSessionStore } = require("./lib/adminSessionStore");
const { createAdminLockoutWorkerClient } = require("./lib/adminLockoutWorkerClient");
const invite = require("./lib/invite");

let serviceAccount;
try {
  const raw = process.env.GOOGLE_APPLICATION_CREDENTIALS_JSON || "{}";
  serviceAccount = JSON.parse(raw);
  if (serviceAccount.private_key) {
    serviceAccount.private_key = serviceAccount.private_key.replace(/\\n/g, "\n");
  }
} catch (e) {
  console.error("Failed to parse GOOGLE_APPLICATION_CREDENTIALS_JSON:", e.message);
  process.exit(1);
}

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
});

const db = admin.firestore();
const messaging = admin.messaging();
const FieldValue = admin.firestore.FieldValue;

const PORT = process.env.PORT || 3000;
const STARTED_AT_MS = Date.now();
const MAX_INITIAL_MESSAGE_AGE_MS = Number(process.env.MAX_INITIAL_MESSAGE_AGE_MS || 5 * 60 * 1000);
const stats = {
  delivered: 0,
  groupDelivered: 0,
  skippedMissingToken: 0,
  skippedOld: 0,
  failed: 0,
  startedAt: new Date(STARTED_AT_MS).toISOString(),
};

console.log("DuoShield push server started.");
console.log(`Initial Firestore snapshot will only process messages from the last ${MAX_INITIAL_MESSAGE_AGE_MS}ms.`);

// ── Startup permission check ──────────────────────────────────────────────────
(async () => {
  try {
    await messaging.send(
      { token: "permission-check-dummy-token", notification: { title: "check" } },
      true
    );
    console.log("✅ FCM permission OK");
  } catch (err) {
    if (err.code === "messaging/invalid-argument" || err.errorInfo?.code === "messaging/invalid-argument") {
      console.log("✅ FCM permission OK (invalid token expected for dry-run)");
    } else if (err.message && err.message.includes("cloudmessaging.messages.create")) {
      console.error("❌ FCM PERMISSION MISSING — add 'Firebase Cloud Messaging API Admin' role to the service account in Google Cloud IAM.");
    } else {
      console.warn("⚠️  FCM check inconclusive:", err.message);
    }
  }

  try {
    const testRef = db.collection("_server_health").doc("startup");
    await testRef.set({ ts: FieldValue.serverTimestamp() });
    await testRef.delete();
    console.log("✅ Firestore write permission OK");
  } catch (err) {
    if (err.message && err.message.includes("PERMISSION_DENIED")) {
      console.error("❌ FIRESTORE WRITE PERMISSION MISSING — add 'Cloud Datastore User' role to the service account in Google Cloud IAM.");
    } else {
      console.warn("⚠️  Firestore write check inconclusive:", err.message);
    }
  }
})();

function messageTimeMs(data) {
  const ts = data.timestamp || data.createdAt || data.serverTimestamp;
  if (ts && typeof ts.toMillis === "function") return ts.toMillis();
  if (typeof ts === "number") return ts;
  if (typeof ts === "string") {
    const parsed = Date.parse(ts);
    if (!Number.isNaN(parsed)) return parsed;
  }
  return STARTED_AT_MS;
}

function shouldSkipOldInitialMessage(change, data) {
  if (messageTimeMs(data) < STARTED_AT_MS - MAX_INITIAL_MESSAGE_AGE_MS) {
    stats.skippedOld++;
    return true;
  }
  return false;
}

const notificationBody = pure.notificationBody;

async function removeInvalidToken(uid, token, err) {
  const code = err.code || err.errorInfo?.code || "";
  if (!code.includes("registration-token-not-registered") && !code.includes("invalid-registration-token")) {
    return;
  }
  try {
    const userRef = db.collection("users").doc(uid);
    const snap = await userRef.get();
    if (snap.data()?.fcmToken === token) {
      await userRef.update({ fcmToken: FieldValue.delete() });
      console.warn(`Removed stale FCM token for ${uid}: ${code}`);
    }
  } catch (cleanupErr) {
    console.warn(`Could not remove stale FCM token for ${uid}:`, cleanupErr.message);
  }
}

async function getSenderName(senderUid) {
  if (!senderUid) return "DuoShield";
  try {
    const snap = await db.collection("users").doc(senderUid).get();
    const name = snap.data()?.displayName;
    return (typeof name === "string" && name.trim()) ? name.trim() : "DuoShield";
  } catch {
    return "DuoShield";
  }
}

async function sendPush({ recipientUid, senderUid, chatId, messageId, type, body }) {
  const userDoc = await db.collection("users").doc(recipientUid).get();
  const fcmToken = userDoc.data()?.fcmToken;
  if (typeof fcmToken !== "string" || fcmToken.trim() === "") {
    stats.skippedMissingToken++;
    console.warn(`No FCM token for recipient=${recipientUid}; cannot push messageId=${messageId}`);
    return false;
  }

  const senderName = await getSenderName(senderUid);

  try {
    // Send DATA-ONLY payload — no `notification` block.
    // With a notification block, Android auto-displays a system notification when
    // the app is in the background AND onMessageReceived fires a second one,
    // producing duplicate (or triplicate) notifications. Data-only ensures
    // onMessageReceived always handles display exactly once.
    await messaging.send({
      token: fcmToken,
      data: {
        type,
        chatId:      chatId      || "",
        messageId:   messageId   || "",
        senderUid:   senderUid   || "",
        senderName:  senderName,
        title:       senderName,
        body,
      },
      android: {
        priority: "high",
      },
    });
    return true;
  } catch (err) {
    stats.failed++;
    console.error(`Push failed for messageId=${messageId} recipient=${recipientUid}:`, err.message);
    await removeInvalidToken(recipientUid, fcmToken, err);
    return false;
  }
}

async function markDelivered(ref, messageId) {
  // Belt-and-suspenders ACK sent right after the push dispatch. If the recipient's
  // device already raced ahead and marked the message "read" (chat was open when
  // the push arrived), an unconditional update would stomp that back down to
  // "delivered" and the sender would never see the real-time read tick. Guard the
  // write in a transaction so it can only move status forward, never backward.
  try {
    await db.runTransaction(async (txn) => {
      const snap = await txn.get(ref);
      const currentStatus = snap.exists ? snap.get("status") : null;
      if (currentStatus === "read" || currentStatus === "delivered") return;
      txn.update(ref, {
        status: "delivered",
        deliveredAt: FieldValue.serverTimestamp(),
      });
    });
  } catch (updateErr) {
    console.warn(`Status update failed for ${messageId} (non-fatal):`, updateErr.message);
  }
}

// ── Single collectionGroup("messages") listener — routes by path prefix ───────
// Combining 1-to-1 and group handling into one listener halves the number of
// Firestore read operations and eliminates the race where two listeners both
// attempt to process the same message document.
db.collectionGroup("messages").onSnapshot(
  async (snapshot) => {
    for (const change of snapshot.docChanges()) {
      if (change.type !== "added") continue;

      const msgDoc = change.doc;
      const data   = msgDoc.data();
      const path   = msgDoc.ref.path;
      if (shouldSkipOldInitialMessage(change, data)) continue;

      const senderUid = data.sender;
      if (!senderUid) continue;

      const messageId = msgDoc.id;

      // ─������ 1-to-1 chat: chats/{chatId}/messages/{msgId} ─────────�����────────────
      if (path.startsWith("chats/")) {
        const chatId = path.split("/")[1];
        try {
          const chatDoc = await db.collection("chats").doc(chatId).get();
          if (!chatDoc.exists) continue;

          const participants = chatDoc.data().participants;
          if (!Array.isArray(participants) || participants.length < 2) continue;

          const recipientUid = participants.find((uid) => uid !== senderUid);
          if (!recipientUid) continue;

          const sent = await sendPush({
            recipientUid,
            senderUid,
            chatId,
            messageId,
            type: "new_message",
            body: notificationBody(data),
          });

          if (sent) {
            stats.delivered++;
            console.log(`Push sent: chatId=${chatId} messageId=${messageId} recipient=${recipientUid}`);
            await markDelivered(msgDoc.ref, messageId);
          }
        } catch (err) {
          stats.failed++;
          console.error(`1-to-1 push pipeline failed for ${messageId}:`, err.message);
        }
        continue;
      }

      // ── Group chat: groups/{groupId}/messages/{msgId} ─────────────────────
      if (path.startsWith("groups/")) {
        const groupId = path.split("/")[1];
        try {
          const groupDoc = await db.collection("groups").doc(groupId).get();
          if (!groupDoc.exists) continue;

          const members = groupDoc.data().members;
          if (!Array.isArray(members) || members.length === 0) continue;

          const recipients = members.filter((uid) => uid !== senderUid);
          const results = await Promise.all(
            recipients.map((recipientUid) => sendPush({
              recipientUid,
              senderUid,
              chatId: groupId,
              messageId,
              type: "new_group_message",
              body: notificationBody(data),
            }))
          );

          const sentCount = results.filter(Boolean).length;
          if (sentCount > 0) {
            stats.groupDelivered += sentCount;
            console.log(`Group push sent: groupId=${groupId} messageId=${messageId} recipients=${sentCount}`);
            await markDelivered(msgDoc.ref, messageId);
          }
        } catch (err) {
          stats.failed++;
          console.error(`Group push pipeline failed for ${messageId}:`, err.message);
        }
      }
    }
  },
  (err) => console.error("messages listener error:", err)
);

// ── Calls listener: FCM wakeup for incoming calls ────────────────────────────
// Watches calls/{callId} for new docs with status="ringing" and sends a
// high-priority data FCM push to the callee so the app can ring even when killed.
db.collection("calls").onSnapshot(
  async (snapshot) => {
    for (const change of snapshot.docChanges()) {
      if (change.type !== "added") continue;

      const callDoc = change.doc;
      const data    = callDoc.data();
      const callId  = callDoc.id;

      if (data.status !== "ringing") continue;
      if (shouldSkipOldInitialMessage(change, data)) continue;

      // Skip calls that are already older than the callee-side ring timeout (30 s).
      // This prevents a cold-starting Render instance from sending a call_invite FCM
      // for a call the callee can no longer answer (and avoids phantom ringing on the
      // callee's device long after the caller gave up).
      const RING_TIMEOUT_MS = 30_000;
      const callAgeMs = Date.now() - messageTimeMs(data);
      if (callAgeMs > RING_TIMEOUT_MS) {
        console.log(`Skipping stale call (age=${Math.round(callAgeMs / 1000)}s): callId=${callId}`);
        continue;
      }

      const callerId = data.callerId;
      const calleeId = data.calleeId;
      const isVideo  = data.type === "video";

      if (!calleeId || !callerId) continue;

      try {
        const [callerDoc, calleeDoc] = await Promise.all([
          db.collection("users").doc(callerId).get(),
          db.collection("users").doc(calleeId).get(),
        ]);

        const callerName = callerDoc.data()?.displayName || "DuoShield";
        const fcmToken   = calleeDoc.data()?.fcmToken;

        if (typeof fcmToken !== "string" || fcmToken.trim() === "") {
          console.warn(`No FCM token for callee=${calleeId}; cannot ring callId=${callId}`);
          continue;
        }

        await messaging.send({
          token: fcmToken,
          data: {
            type:       "call_invite",
            callId,
            callerId,
            callerName,
            isVideo:    isVideo ? "true" : "false",
          },
          android: { priority: "high" },
        });
        console.log(`Call invite FCM sent: callId=${callId} callee=${calleeId} video=${isVideo}`);
      } catch (err) {
        console.error(`Call invite FCM failed for callId=${callId}:`, err.message);
      }
    }
  },
  (err) => console.error("calls listener error:", err)
);

// ── Per-userId mint cooldown (in-memory rate limit) ───────────────────────────
// Allows at most one token mint per userId per 60 seconds.
const MINT_COOLDOWN_MS = 60_000;
const mintCooldown = new Map();

// S02-L3: unlike every sibling limiter Map below (ipHits, waitlistIpHits,
// authRateLimits), this one had no purge job — one entry accumulates per
// distinct userId ever seen (successful or merely attempted) and is never
// removed, so a long-lived Render instance grows this Map forever. userId is
// not a secret (see the S02-M1 comment further down), so an attacker can grow
// it arbitrarily just by POSTing distinct userIds. Purge on the same cadence
// as the other per-key limiters.
setInterval(() => {
  const now = Date.now();
  for (const key of pure.collectStaleKeys(mintCooldown, now, MINT_COOLDOWN_MS)) {
    mintCooldown.delete(key);
  }
}, 5 * 60 * 1000);

// S07-C1 remediation, part 1 of 2: single-use nonce issuance for /mintChallenge.
// NOT YET enforced by /mintToken — see that handler's comment. This store only
// issues and consumes nonces; signature verification is a separate, not-yet-done
// piece of work (see SESSION_PROTOCOL.md's "Next session" prompt).
const mintChallengeStore = createChallengeStore();

// ── Waitlist request-access rate limit (separate from mintToken's IP bucket) ──
// /requestAccess creation bucket: strict — only 5 submissions per IP per 15 min.
const WAITLIST_IP_WINDOW_MS = 15 * 60 * 1000;
const WAITLIST_IP_MAX_HITS  = 5;
const waitlistIpHits = new Map();
// /waitlistStatus polling bucket: permissive — the poll happens every few
// minutes and must not drain the creation bucket.  Kept separate so a user
// who polls their own status cannot accidentally lock themselves out of
// /requestAccess.  60 hits / 15 min ≈ one poll every 15 seconds.
const WAITLIST_POLL_WINDOW_MS = 15 * 60 * 1000;
const WAITLIST_POLL_MAX_HITS  = 60;
const waitlistPollHits = new Map();

setInterval(() => {
  const cutoff = Date.now() - WAITLIST_IP_WINDOW_MS;
  for (const [ip, rec] of waitlistIpHits) {
    if (rec.windowStart < cutoff) waitlistIpHits.delete(ip);
  }
}, 30 * 60 * 1000);

// S04-M1: key by pure.normalizeIpForRateLimit(ip), not the raw ip, so every
// address within an attacker's own delegated IPv6 /64 shares one bucket
// instead of each rotated address getting its own. See the helper's comment
// in lib/pure.js for the full rationale. IPv4 keys pass through unchanged.
function checkWaitlistIpRateLimit(ip) {
  const key = pure.normalizeIpForRateLimit(ip);
  const now = Date.now();
  const rec = waitlistIpHits.get(key);
  if (!rec || now - rec.windowStart >= WAITLIST_IP_WINDOW_MS) {
    waitlistIpHits.set(key, { count: 1, windowStart: now });
    return true;
  }
  if (rec.count >= WAITLIST_IP_MAX_HITS) return false;
  rec.count++;
  return true;
}

function checkWaitlistPollRateLimit(ip) {
  const key = pure.normalizeIpForRateLimit(ip);
  const now = Date.now();
  const rec = waitlistPollHits.get(key);
  if (!rec || now - rec.windowStart >= WAITLIST_POLL_WINDOW_MS) {
    waitlistPollHits.set(key, { count: 1, windowStart: now });
    return true;
  }
  if (rec.count >= WAITLIST_POLL_MAX_HITS) return false;
  rec.count++;
  return true;
}

// ── Per-IP rate limit ────────────────────────���────────────────────────────────
// Max 5 /mintToken attempts per IP in any rolling 15-minute window.
// Render appends its own entry to X-Forwarded-For; we use the RIGHTMOST value
// (proxy-appended, not client-controlled) via getClientIp(). See CRIT-1 fix.
const IP_WINDOW_MS  = 15 * 60 * 1000; // 15 minutes
const IP_MAX_HITS   = 5;
const ipHits = new Map(); // ip → { count, windowStart }

// Purge stale IP entries every 30 minutes so the Map doesn't grow forever.
setInterval(() => {
  const cutoff = Date.now() - IP_WINDOW_MS;
  for (const [ip, rec] of ipHits) {
    if (rec.windowStart < cutoff) ipHits.delete(ip);
  }
}, 30 * 60 * 1000);

// ── Per-UID authenticated-endpoint rate limiter ───────────────���───────────────
// Prevents an authenticated user from flooding the server-mediated endpoints
// below. Each endpoint has its own per-minute bucket.
//
// S04-I2: the `b2PresignedPut`/`b2PresignedGet`/`b2Delete` buckets were
// removed here. They rate-limited presigned-URL routes that no longer exist
// — the media data plane moved to per-object capability tokens (SEC-A01),
// served by the Cloudflare Worker, not by any presign route on this server —
// so the entries only made this table misreport the real attack surface.
const AUTH_RATE_WINDOW_MS = 60_000;
const AUTH_RATE_LIMITS = {
  createChat:        10,   // 10 chat creations / min per user
  migrateUid:         2,   //  2 migrations / min per user
  turnCredentials:   20,   // 20 TURN fetches / min per user
  removeGroupMember: 20,   // 20 removals / min per user
  linkPreview:       30,   // 30 link previews / min per user
  // Duress-lock nonce: very low limit — issuing a nonce writes a Firestore doc
  // and is never needed more than once per session.  Without this entry the
  // fallback default of 30/min would allow 30 Firestore writes/min per user.
  requestLockNonce:   3,   //  3 nonce requests / min per user
  // Scoped media capability tokens (SEC-A01). One token is minted per object
  // per operation, so a chat with many attachments legitimately needs a burst
  // when a conversation is first opened; 120/min covers that while still
  // bounding how fast a compromised account can enumerate media.
  mediaToken:       120,
  // S06-M6: one-shot completion call for the forced post-unfreeze rotation —
  // clears accountLock/{uid}.rotationRequired once both new-PIN screens finish.
  // Same reasoning as requestLockNonce: never legitimately needed more than a
  // couple of times per rotation (an initial call plus a retry or two if the
  // network drops), so a low limit costs nothing to a real user while bounding
  // how fast a compromised token can hammer the accountLock transaction.
  signalRegisterBundle: 2,
  signalAppendPreKeys: 6,
  signalUpdatePreKey: 6,
  signalClaimPreKey: 30,
  acknowledgeRotation: 3,
};
const authRateLimits = new Map(); // uid → { counts: {ep: n}, windowStart }

function checkAuthRateLimit(uid, endpoint) {
  const now   = Date.now();
  const limit = AUTH_RATE_LIMITS[endpoint] || 30;
  const rec   = authRateLimits.get(uid);
  // Per-endpoint fixed window. Reuse the pure windowing helper by projecting the
  // multi-endpoint record down to this endpoint's { count, windowStart }.
  const projected = rec
    ? { count: rec.counts[endpoint] || 0, windowStart: rec.windowStart }
    : undefined;
  const { allowed, record } = pure.evaluateFixedWindow(projected, now, AUTH_RATE_WINDOW_MS, limit);
  if (!allowed) return false;

  if (!rec || now - rec.windowStart >= AUTH_RATE_WINDOW_MS) {
    // Window rolled over (or first hit): start a fresh multi-endpoint record.
    authRateLimits.set(uid, { counts: { [endpoint]: record.count }, windowStart: record.windowStart });
  } else {
    rec.counts[endpoint] = record.count;
  }
  return true;
}

// Purge stale auth rate-limit entries every 5 minutes.
setInterval(() => {
  const cutoff = Date.now() - AUTH_RATE_WINDOW_MS;
  for (const [uid, rec] of authRateLimits) {
    if (rec.windowStart < cutoff) authRateLimits.delete(uid);
  }
}, 5 * 60 * 1000);

// ── /turnCredentials daily aggregate cap (S04-M2) ─────────────────────────────
// The per-minute bucket above (turnCredentials: 20) still allows ~28,800 mints
// per day from a single account. Each mint is a plain bearer username/password
// that works from anywhere — unbound to the account, device, or a specific
// call — so bulk minting is a resale/redistribution vector and direct
// bandwidth theft billed to this project via Cloudflare's metered TURN relay.
// This is a SEPARATE, longer window layered on top of the per-minute one, not
// a replacement for it: it reuses the same pure fixed-window evaluator with a
// 24h window instead of 60s. The default (100/day) comfortably covers even
// heavy legitimate use (dozens of calls a day between two people) while
// cutting the worst case by ~2-3 orders of magnitude.
const TURN_DAILY_WINDOW_MS = 24 * 60 * 60 * 1000;
const TURN_DAILY_CAP = Number(process.env.TURN_CRED_DAILY_CAP) || 100;
const turnDailyMints = new Map(); // uid → { count, windowStart }

function checkTurnDailyCap(uid) {
  const now = Date.now();
  const rec = turnDailyMints.get(uid);
  const { allowed, record } = pure.evaluateFixedWindow(rec, now, TURN_DAILY_WINDOW_MS, TURN_DAILY_CAP);
  turnDailyMints.set(uid, record);
  return allowed;
}

// Purge stale daily-cap entries once an hour — mirrors the per-minute purge
// above (S02-L3 precedent: every limiter Map needs one or it grows forever).
setInterval(() => {
  const cutoff = Date.now() - TURN_DAILY_WINDOW_MS;
  for (const [uid, rec] of turnDailyMints) {
    if (rec.windowStart < cutoff) turnDailyMints.delete(uid);
  }
}, 60 * 60 * 1000);

// ── Scoped media capability tokens (SEC-A01) ──────────────────────────────────
// Shared with the Cloudflare Worker ONLY. Set the identical value in both places:
//   server: MEDIA_TOKEN_SECRET env var
//   worker: npx wrangler secret put MEDIA_TOKEN_SECRET
// This value must NEVER be compiled into the Android app — that is precisely the
// weakness it replaces.
const MEDIA_TOKEN_SECRET = process.env.MEDIA_TOKEN_SECRET || "";
if (!MEDIA_TOKEN_SECRET) {
  console.warn(
    "MEDIA_TOKEN_SECRET is not set — /mediaToken will refuse to mint tokens and " +
    "media upload/download will fail. Set it here and in the Worker."
  );
}

// Short TTL: long enough to cover a slow upload on a poor connection, short
// enough that a token captured from logs or a proxy is quickly worthless.
const MEDIA_TOKEN_TTL_MS = 10 * 60 * 1000; // 10 minutes

const MEDIA_OPS = new Set(["read", "write", "delete"]);

// Must stay byte-identical to KEY_FORMAT in worker/src/index.js.
const MEDIA_KEY_FORMAT =
  /^(media|voice)\/[a-zA-Z0-9-]{16,80}\/[a-zA-Z0-9._-]{1,100}\.(jpg|mp4|m4a|3gp)$/;

/**
 * Every key shape the storage tier accepts: conversation media/voice, plus
 * profile avatars (`avatars/<uid>_<millis>.jpg`).
 *
 * Avatars were missing from the allow-list entirely, so /mediaToken answered
 * `400 Invalid key format` for every profile-photo upload while the client
 * surfaced it verbatim as "Upload failed: mediaToken denied [400]". Keeping the
 * two shapes as separate anchored patterns (rather than loosening the media
 * pattern's prefix) means avatars cannot smuggle in a video extension and media
 * keys are validated exactly as strictly as before.
 *
 * Must stay byte-identical to isAllowedKey() in worker/src/index.js.
 */
function isAllowedMediaKey(key) {
  return MEDIA_KEY_FORMAT.test(key) || AVATAR_KEY_FORMAT.test(key);
}

function b64url(buf) {
  return buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Mints a capability token bound to one object key, one operation, one user and
 * one expiry. The key itself is NOT carried in the token — the Worker already
 * knows it from the request path and re-derives the signature over it, so a
 * token cannot be replayed against a different object.
 *
 * Wire format: v1.<op>.<expiresAt>.<uidTag>.<jti>.<sig>
 *
 * S03-M3: `jti` is a random per-mint identifier, added alongside the
 * pre-existing fields (not replacing any of them). It lets the Worker mark a
 * `delete` token single-use in KV once consumed — a capability token is
 * otherwise a stateless bearer credential replayable for its entire TTL by
 * anything that observed the Authorization header, which is tolerable for
 * `read`/`write` but not for `delete`, the one verb with no undo. This
 * server and the Worker must stay in lockstep on the wire format — the
 * Worker rejects any token that isn't exactly 6 dot-separated segments.
 */
function signMediaToken({ op, key, uid, expiresAt }) {
  const holder  = uidTag(uid); // pseudonymous — lets the Worker rate-limit per user
  const jti     = crypto.randomBytes(9).toString("base64url");
  const payload = `v1|${op}|${expiresAt}|${holder}|${jti}|${key}`;
  const sig     = b64url(crypto.createHmac("sha256", MEDIA_TOKEN_SECRET).update(payload).digest());
  return `v1.${op}.${expiresAt}.${holder}.${jti}.${sig}`;
}

/**
 * True if {@code uid} participates in the chat or group named by {@code scopeId}.
 *
 * The object-key middle segment is either a chats/{id} or a groups/{id}, so both
 * collections are fetched. Membership fields mirror firestore.rules:
 * chats use `participants`, groups use `members`.
 *
 * S03-H1: this used to accept *either* document as proof, which a client-created
 * shadow `groups/{chatId}` could satisfy for a conversation the caller had
 * nothing to do with. The decision now lives in `lib/mediaScope.js`, which
 * requires the scope to resolve unambiguously — see that file for the full
 * attack description and `lib/mediaScope.test.js` for the regression tests.
 * This function is now only responsible for the I/O and for failing closed.
 */
async function callerMayAccessScope(uid, scopeId) {
  if (!uid || !scopeId) return false;
  try {
    const [chatDoc, groupDoc] = await Promise.all([
      db.collection("chats").doc(scopeId).get(),
      db.collection("groups").doc(scopeId).get(),
    ]);
    const { allowed, reason } = decideScopeAccess({ uid, chatDoc, groupDoc });
    if (!allowed) {
      // Logged at warn for the ambiguous case specifically: a scopeId naming
      // both a chat and a group is not a state any legitimate client flow
      // produces, so it is an active-attack signal worth surfacing, not noise.
      const level = reason === SCOPE_DENY.AMBIGUOUS ? "warn" : "debug";
      console[level](
        `callerMayAccessScope denied uid=${uidTag(uid)} scope=${scopeId} reason=${reason}`
      );
    }
    return allowed;
  } catch (e) {
    // Fail closed on lookup errors — never mint a token we could not authorize.
    console.error("callerMayAccessScope lookup failed:", e.message);
    return false;
  }
}

// ── Admin panel auth ──────────────────────────────────────────────────────────
// Gates /admin/api/* (waitlist approval, account-lock unfreeze). A single
// operator-held token (ADMIN_TOKEN env var), never shipped in the APK. The
// static /admin page itself carries no data — only the API calls it makes
// need the token — so serving the HTML shell without auth leaks nothing.
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || "";

// S05-H1: the line above used to be the ENTIRE validation of the credential
// guarding waitlist approval and account unfreeze — any non-empty string was
// accepted, so `ADMIN_TOKEN=admin` shipped a five-character admin password.
//
// Checked at startup rather than per request, because startup is the only moment
// a weak secret is still a fixable deployment error instead of an invisible
// standing exposure. Failing CLOSED (refusing to boot) is deliberate: booting
// with a guessable admin token is strictly worse than not booting, since the
// operator would have no signal until the panel was already abused.
//
// Note the asymmetry with "unset", which does NOT abort — an unset token leaves
// the panel returning 503 (see requireAdminAuth), which is already safe, and
// hard-failing there would break deployments that legitimately never enable the
// admin panel. A *weak* token is the dangerous case, because it is open.
const adminSecret = require("./lib/adminSecret");
if (ADMIN_TOKEN) {
  const verdict = adminSecret.evaluateSecretStrength("ADMIN_TOKEN", ADMIN_TOKEN);
  if (!verdict.ok) {
    console.error("FATAL: refusing to start with a weak ADMIN_TOKEN.");
    console.error(`  ${verdict.reason}`);
    console.error(`  ${verdict.remedy}`);
    // Abort rather than continue: see reasoning above.
    process.exit(1);
  }
  console.log(`admin auth: ADMIN_TOKEN accepted (~${verdict.bits} bits of entropy)`);
} else {
  console.warn(
    "admin auth: ADMIN_TOKEN is not set — the admin panel will refuse every " +
    "request with 503. Set it to enable operator actions (see server/README.md)."
  );
}

const ADMIN_IP_WINDOW_MS = 15 * 60 * 1000; // 15 minutes
const ADMIN_IP_MAX_FAILS = 10;
// S05-M3: split into a sliding IDLE timeout and a hard ABSOLUTE ceiling that
// no amount of activity can push past — see lib/adminSessionStore.js's doc
// comment for the full "what was wrong" writeup. ADMIN_SESSION_TTL_MS (the
// pre-fix single constant) is kept as an alias for ADMIN_SESSION_IDLE_TTL_MS
// so the cookie Max-Age below (a browser-side hint only, not a control)
// keeps its prior value unchanged.
const ADMIN_SESSION_IDLE_TTL_MS = 30 * 60 * 1000;
const ADMIN_SESSION_TTL_MS = ADMIN_SESSION_IDLE_TTL_MS;
const ADMIN_SESSION_ABSOLUTE_TTL_MS = 8 * 60 * 60 * 1000; // 8h
// S07-H2: admin sessions are SIGNED tokens, not process-local ids. The signing
// key must stay the same across restarts and across instances, otherwise the
// operator is thrown back to the login gate ("Your session expired") every
// time Render redeploys, spins the free instance back up, or routes the next
// request to a second instance — the reported "logs in for half a second, then
// exits" bug. ADMIN_SESSION_SECRET pins it explicitly; by default it is
// DERIVED from ADMIN_TOKEN, which is both stable across restarts and, as a
// deliberate side effect, invalidates every existing session when the admin
// token is rotated. The HKDF `info` label keeps this key distinct from the
// token itself, so the cookie can never be reversed into the credential.
// (No ADMIN_TOKEN → the panel already refuses every request, so a random
// throwaway key is fine.)
const ADMIN_SESSION_SECRET = (() => {
  const explicit = String(process.env.ADMIN_SESSION_SECRET || "").trim();
  if (explicit) return Buffer.from(explicit, "utf8");
  if (ADMIN_TOKEN) {
    return Buffer.from(
      crypto.hkdfSync("sha256", Buffer.from(ADMIN_TOKEN, "utf8"), Buffer.alloc(0), "duoshield-admin-session-v1", 32)
    );
  }
  return crypto.randomBytes(32);
})();
// S07-H2 (c) / S07-H3: neither the IP nor the User-Agent is part of the
// validation reject path any more. A phone switching WiFi to LTE, CGNAT
// reassigning an address, or a dual-stack client flipping between IPv4 and
// IPv6 all changed the client ADDRESS mid-session; Android in-app/WebView
// browsers send a different USER-AGENT on fetch()/XHR than on the top-level
// navigation that loaded the page. Either one surfaced as "Your session
// expired. Sign in again." on the very first /admin/api/* call after a
// SUCCESSFUL login — the reported "logs in for half a second, then exits"
// bug. Both dimensions are still recorded in the durable audit trail
// (auditAdminEvent), which is where they are actually useful. The session
// cookie itself remains the control: HMAC-signed, HttpOnly, Secure,
// SameSite=Lax, Path=/admin, short-lived, and rotated on every login.
// See lib/adminSessionStore.js's header comment (c) and the S07-H3 note.
if (process.env.ADMIN_BIND_SESSION_IP || process.env.ADMIN_BIND_SESSION_UA) {
  console.warn(
    "admin auth: ADMIN_BIND_SESSION_IP / ADMIN_BIND_SESSION_UA are not honored by default — " +
    "IP- and UA-bound admin sessions logged mobile/WebView operators out mid-session (S07-H2/H3)."
  );
}
const adminSessionStore = createAdminSessionStore({
  idleTtlMs: ADMIN_SESSION_IDLE_TTL_MS,
  absoluteTtlMs: ADMIN_SESSION_ABSOLUTE_TTL_MS,
  secret: ADMIN_SESSION_SECRET,
  // S07-H3: off — WebView fetch()/navigation UA mismatch bounced real logins.
  bindUserAgent: false,
});

setInterval(() => adminSessionStore.sweep(), 5 * 60 * 1000);

// S04-L3: the admin brute-force failure counter (formerly the `adminIpFails`
// Map right here) lived only in process memory, so a Render redeploy/crash/
// restart silently reset every IP's count to zero — the only ceiling in
// front of ADMIN_TOKEN (see lib/adminSecret.js's S05-H1 note) never actually
// accumulated across the instance's lifetime. `createAdminLockoutStore()`
// (lib/adminLockoutStore.js) backs the same counter with a Cloudflare
// Durable Object (via the project's existing `/worker`) so it survives
// restarts, is shared across instances, AND stays exactly atomic under
// concurrent failures — see that module's header comment for the full
// fail-safe/atomicity rationale and fix history (Upstash Redis → a
// same-day-superseded Cloudflare KV attempt → this Durable Object fix,
// all on 2026-08-14).
//
// ADMIN_LOCKOUT_WORKER_URL / ADMIN_LOCKOUT_WORKER_SECRET are required to
// enable the durable path; the secret must match the Worker's
// ADMIN_LOCKOUT_SECRET (see server/README.md and worker/wrangler.jsonc).
// Both are optional here: an operator who has not deployed the Worker's
// admin-lockout route still gets a working (process-local,
// pre-fix-equivalent) lockout rather than a crash.
const adminLockoutWorkerClient = (() => {
  const workerUrl = process.env.ADMIN_LOCKOUT_WORKER_URL;
  const workerSecret = process.env.ADMIN_LOCKOUT_WORKER_SECRET;
  if (!workerUrl || !workerSecret) {
    console.warn(
      "admin lockout: ADMIN_LOCKOUT_WORKER_URL/ADMIN_LOCKOUT_WORKER_SECRET " +
      "not fully set — falling back to process-local lockout state, which does NOT survive " +
      "a restart or span multiple instances. Set both to enable the durable, " +
      "Durable-Object-backed, atomic lockout (S04-L3)."
    );
    return null;
  }
  return createAdminLockoutWorkerClient({ workerUrl, workerSecret });
})();

const adminLockoutStore = createAdminLockoutStore({
  client: adminLockoutWorkerClient,
  windowMs: ADMIN_IP_WINDOW_MS,
  maxFails: ADMIN_IP_MAX_FAILS,
  normalizeIp: pure.normalizeIpForRateLimit,
  onError: (op, err) => console.warn(`admin lockout: Worker/Durable Object ${op} failed, using local fallback:`, err.message),
});
if (adminLockoutWorkerClient) {
  console.log("admin lockout: Durable-Object-backed (durable across restarts/instances, atomic under concurrency)");
}

// S04-M1: see the comment on checkWaitlistIpRateLimit above — same fix. The
// admin lockout is the highest-value target of this class of bypass (it
// gates the operator panel), so it must not be left keyed by raw IPv6
// address while the lower-stakes limiters get the fix. IP normalization is
// applied inside adminLockoutStore itself (via the normalizeIp option above),
// not here, so both the Redis-backed and local-fallback paths get it.
async function adminIpLocked(ip) {
  return adminLockoutStore.isLocked(ip);
}

async function recordAdminAuthFailure(ip) {
  return adminLockoutStore.recordFailure(ip);
}

// Called on every successful admin authentication (login form and, for
// symmetry, any request that clears the token check inside
// requireAdminAuth) so a legitimate operator's earlier mistyped attempts do
// not linger and count toward a future lockout window.
async function resetAdminAuthFailures(ip) {
  return adminLockoutStore.reset(ip);
}

// S05-H3: admin ACTIONS were already written to adminAuditLog (waitlist approve,
// unfreeze, duress enroll/revoke), so that half of the finding row is stale. What
// was genuinely missing is admin AUTHENTICATION: login success, login failure,
// lockout and logout left no durable trace at all. Only `console.warn` recorded
// them, and Render's logs roll, so the single most important forensic question —
// "did anyone other than us get into the admin panel, and when?" — was
// unanswerable after a few days.
//
// Wiring note (2026-08-10, cluster B completion): the first pass added this
// function and called it from `requireAdminAuth()` only, while this comment
// already claimed login/logout were covered. They were not — `POST /admin/login`
// and `POST /admin/logout` still logged to the console alone, so the highest-value
// events named above were still missing while the code read as if they were done.
// All six call sites now exist; if you add another admin auth branch, audit it
// here too rather than trusting this paragraph. Verify with:
//   grep -n 'auditAdminEvent(' server/index.js
//
// Writes are deliberately fire-and-forget: an audit sink that can block or fail
// a login would become an availability problem, and a failed write is itself
// logged. adminAuditLog is server-only (`allow read, write: if false` in
// firestore.rules), so entries cannot be read or tampered with by any client.
function auditAdminEvent(action, req, extra = {}) {
  db.collection("adminAuditLog")
    .add({
      action,
      // S05-M1: this used to be `getClientIp(req)` — the raw IP — written to
      // a permanent, no-TTL Firestore collection. adminAuditLog is worse than
      // a console log in exactly the way SEC-L01 (above) was written to
      // prevent: console output on Render rotates, this collection does not,
      // and the record is a join (IP + timestamp + action), not an isolated
      // value. `ipTag()` keeps the "same operator across two rows?"
      // correlation the finding asked to preserve, while an adversary who
      // reaches this collection (Firestore access or a legal order) no
      // longer gets a directly-identifying, resolvable IP for free.
      //
      // Caveat, documented rather than hidden: LOG_PEPPER is per-process and
      // never persisted (see its own comment above), so a server restart
      // makes pre-restart and post-restart tags uncorrelatable — acceptable
      // for "was this the same operator in one incident window?", not
      // sufficient for "was this the same operator six months apart?". That
      // tradeoff is the durable-audit vs. non-identifying tension the
      // finding calls out explicitly; this fix takes the non-identifying
      // side, matching its prescribed resolution.
      adminIp: ipTag(getClientIp(req)),
      // Retained because a rotating tag with a constant user-agent (or vice
      // versa) is the signal that distinguishes one operator on mobile data
      // from a distributed guessing attempt.
      userAgent: String(req.headers["user-agent"] || "").slice(0, 200),
      at: FieldValue.serverTimestamp(),
      ...extra,
    })
    .catch((err) => console.warn(`[admin] audit write failed (${action}):`, err.message));
}

// Constant-time comparison so token-guessing can't be timed byte-by-byte.
// Implementation lives in ./lib/pure (unit-tested there).
const safeTokenEqual = pure.safeTokenEqual;

const validAdminUid = pure.validAdminUid;

// S05-I3: `FORCE_SECURE_COOKIES` is an explicit escape hatch for an operator
// who knows their deployment terminates TLS somewhere `req.socket.encrypted`
// and X-Forwarded-Proto trust (below) can't see it. "true"/"false" pin the
// flag; anything else (including unset) falls through to the automatic
// detection in adminSessionCookie().
const FORCE_SECURE_COOKIES = (() => {
  const raw = String(process.env.FORCE_SECURE_COOKIES || "").trim().toLowerCase();
  if (raw === "true") return true;
  if (raw === "false") return false;
  return null;
})();

function adminSessionCookie(sessionId, req, maxAgeSeconds) {
  // S05-I3: `x-forwarded-proto` is client-suppliable, so the old
  // `forwardedProto === "https" || …` check let ANY caller decide whether
  // their own cookie got issued with `Secure` — never exploitable against a
  // third party, but still the wrong trust boundary on the system's most
  // privileged cookie. TRUSTED_PROXY_HOPS (above) is the operator's existing,
  // explicit declaration of "there is a proxy in front of me I trust to set
  // this header honestly" (S04-M3); reusing it here means the header is only
  // consulted when the operator has said a trusted proxy is actually present,
  // instead of unconditionally trusting whatever the client sends.
  const forwardedProto = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim().toLowerCase();
  const isHttps = FORCE_SECURE_COOKIES !== null
    ? FORCE_SECURE_COOKIES
    : (TRUSTED_PROXY_HOPS > 0 && forwardedProto === "https") || Boolean(req.socket.encrypted);
  // SameSite=Lax (not Strict): some Android browsers/in-app webviews decline to
  // attach a Strict cookie on the very next top-level navigation after the
  // login POST redirects to GET /admin, which silently drops the session and
  // looks like "nothing happens" after entering the correct token. Lax still
  // withholds the cookie on cross-site requests (the CSRF protection we
  // actually need) while reliably surviving same-site redirect navigation.
  return `duoshield_admin_session=${encodeURIComponent(sessionId)}; Path=/admin; Max-Age=${maxAgeSeconds}; HttpOnly;${isHttps ? " Secure;" : ""} SameSite=Lax`;
}

const getCookie = pure.getCookie;

// S05-M3 / S07-H2: binds the new session to this request's User-Agent, so a
// cookie captured from another channel (shared machine, synced browser
// profile, log leak) fails adminSessionStore's binding check when replayed by
// a different client. The client ADDRESS is deliberately not part of the
// binding any more (see the ADMIN_BIND_SESSION_IP note above): it changes
// mid-session on mobile networks and produced exactly the spurious
// "session expired" logouts this fix removes. It is still written to the
// durable audit trail via auditAdminEvent().
function createAdminSession(req) {
  return adminSessionStore.create({
    userAgent: req.headers["user-agent"] || "",
  });
}

// Returns the full validation result ({valid, reason?}) so callers that need
// to distinguish WHY a session was rejected (requireAdminAuth's audit entry)
// can, while hasValidAdminSession() below stays a simple boolean for the one
// caller (the GET /admin render check) that only needs yes/no.
//
// `opts.refresh` (default true) is forwarded to adminSessionStore.validate():
// GET /admin passes `{refresh: false}` so that unauthenticated, view-only
// route can no longer extend a session's idle timeout just by being loaded
// (S05-M3 finding #2) — every genuinely authenticated admin/api call still
// refreshes as before, capped at the session's absolute lifetime.
function evaluateAdminSession(req, opts = {}) {
  const sessionId = getCookie(req, "duoshield_admin_session");
  if (!sessionId) return { valid: false, reason: "missing" };
  return adminSessionStore.validate(
    sessionId,
    { ip: ipTag(getClientIp(req)), userAgent: req.headers["user-agent"] || "" },
    opts
  );
}

function hasValidAdminSession(req, opts = {}) {
  return evaluateAdminSession(req, opts).valid;
}

// Returns true and lets the caller proceed, or writes a 401/429/503 response
// and returns false. Every admin/api route must call this first.
//
// S04-L3: adminIpLocked()/recordAdminAuthFailure() are now backed by
// adminLockoutStore (Redis, with a local-fallback path — see that module and
// the constant declarations above), which is why this function is async.
// Every call site is already inside an async context (an `async () => {}`
// IIFE or an `async (body) => {}` collectBody callback) so this only adds
// `await`, not new restructuring.
async function requireAdminAuth(req, res) {
  // S05-I3 (CSRF note): the session cookie is `SameSite=Lax` (adminSessionCookie()
  // above documents why Strict is unusable here — some Android in-app webviews
  // drop a Strict cookie on the post-login top-level navigation). Lax already
  // withholds the cookie on cross-site sub-requests — fetch/XHR/form POST, i.e.
  // every mutating admin/api call — and CSP `form-action 'self'` covers form
  // posts, so this was flagged as defense-in-depth rather than a live gap. But
  // that left exactly one such mechanism and no anti-CSRF token, `Origin`, or
  // `Sec-Fetch-Site` check. `Sec-Fetch-Site` is
  // sent by every current browser (Fetch Metadata) and cannot be set by an
  // attacker page — it is set by the browser itself and describes the
  // relationship between the REQUESTING page and this origin. Reject only the
  // unambiguous cross-site case; leave `same-origin`/`same-site`/`none`
  // (direct navigation, no Referer, or an older browser that omits the
  // header) untouched so this is purely additive and cannot break a
  // legitimate same-origin admin-panel request.
  const secFetchSite = String(req.headers["sec-fetch-site"] || "").toLowerCase();
  if (secFetchSite === "cross-site") {
    auditAdminEvent("admin_api_blocked_cross_site", req, { path: String(req.url).slice(0, 200) });
    res.writeHead(403, { "Content-Type": "text/plain" });
    res.end("Cross-site request rejected");
    return false;
  }
  const ip = getClientIp(req);
  if (await adminIpLocked(ip)) {
    auditAdminEvent("admin_api_blocked_locked_out", req, { path: String(req.url).slice(0, 200) });
    res.writeHead(429, { "Content-Type": "text/plain" });
    res.end("Too many failed attempts — wait 15 min and retry");
    return false;
  }
  if (!ADMIN_TOKEN) {
    console.error("admin auth: ADMIN_TOKEN is not configured on the server");
    res.writeHead(503, { "Content-Type": "text/plain" });
    res.end("Admin panel not configured");
    return false;
  }
  const supplied = req.headers["x-admin-token"] || "";
  const tokenValid = supplied && safeTokenEqual(supplied, ADMIN_TOKEN);
  // Only evaluate the session when no valid token was supplied — token auth
  // does not touch/refresh the session store at all.
  const sessionCheck = tokenValid ? { valid: false, reason: "not_checked" } : evaluateAdminSession(req);
  if (!tokenValid && !sessionCheck.valid) {
    await recordAdminAuthFailure(ip);
    // Logged so an unexpected mass-401 (e.g. the in-memory session map was
    // wiped by a restart between login and this call) is visible in Render
    // logs instead of silently bouncing the browser back to the login gate.
    console.warn(`admin api: 401 ip=${ip} path=${req.url} hasCookie=${Boolean(getCookie(req, "duoshield_admin_session"))} reason=${sessionCheck.reason}`);
    auditAdminEvent("admin_api_unauthorized", req, {
      path: String(req.url).slice(0, 200),
      // Distinguishes "expired/wiped session" from "someone is guessing the
      // token", which look identical in a bare 401 count. S05-M3 adds
      // sessionInvalidReason so an ip_mismatch/ua_mismatch (a stolen-cookie
      // replay attempt) is distinguishable from an ordinary idle_expired/
      // absolute_expired/missing/not_found in the durable audit trail.
      hadSessionCookie: Boolean(getCookie(req, "duoshield_admin_session")),
      suppliedToken: Boolean(supplied),
      sessionInvalidReason: sessionCheck.reason,
    });
    res.writeHead(401, { "Content-Type": "text/plain" });
    res.end("Invalid admin token");
    return false;
  }
  return true;
}

// ── Global unhandled-rejection / exception guards ─────────────────────────────
// Prevents a single async exception from crashing the process.
process.on("unhandledRejection", (reason) => {
  console.error("Unhandled promise rejection:", reason instanceof Error ? reason.message : reason);
});
process.on("uncaughtException", (err) => {
  console.error("Uncaught exception:", err.message, "\n", err.stack);
});

// S04-M3: how many trusted proxy hops sit in front of this server and are
// expected to have appended their own entry to X-Forwarded-For. Render's edge
// is exactly one hop, so the default (1) reproduces the original hardcoded
// "always trust the rightmost entry" behavior for the deployment this server
// actually runs on. Set to 0 to disable XFF trust entirely (bare socket
// address only — e.g. local dev, or a topology with no trusted proxy at all),
// or higher if another trusted proxy/CDN is added in front of Render later.
// See pickClientIp() in lib/pure.js for the resolution logic this configures.
const TRUSTED_PROXY_HOPS = (() => {
  const raw = parseInt(process.env.TRUSTED_PROXY_HOPS, 10);
  return Number.isInteger(raw) && raw >= 0 ? raw : 1;
})();

function getClientIp(req) {
  // The leftmost X-Forwarded-For entries are client-controlled and trivially
  // spoofable; trusting them would let any attacker bypass every IP-based
  // rate limit and the admin lockout by forging: X-Forwarded-For: 1.2.3.4
  // pickClientIp() only trusts the entries appended by TRUSTED_PROXY_HOPS
  // trusted proxies, counted from the right.
  return pure.pickClientIp(
    req.headers["x-forwarded-for"],
    req.socket.remoteAddress,
    TRUSTED_PROXY_HOPS
  );
}

// ── Log hygiene ───────────────────────────────────────────────────────────────
// SEC-L01: raw client IPs were written to persistent logs. For a privacy tool
// whose threat model includes log seizure/subpoena, an IP is directly
// identifying and links an account to a physical location. Rate limiting only
// needs a *stable* bucket key, not a reversible one, so logs get a keyed,
// truncated digest while the in-memory limiter keeps using the real IP.
//
// The HMAC key is per-process and never persisted: restarting the server makes
// old log tags uncorrelatable with new ones, which is the desired property.
const LOG_PEPPER = crypto.randomBytes(32);

function ipTag(ip) {
  if (!ip || ip === "unknown") return "unknown";
  return crypto.createHmac("sha256", LOG_PEPPER).update(String(ip)).digest("hex").slice(0, 12);
}

/** Pseudonymises a user id for logs — same rationale as {@link ipTag}. */
function uidTag(uid) {
  if (!uid) return "none";
  return crypto.createHmac("sha256", LOG_PEPPER).update(String(uid)).digest("hex").slice(0, 12);
}

// S05-M1: waitlist requestIds are opaque 128-bit tokens, not directly
// identifying on their own — but a stdout `console.log` line naming one in
// full still lets anyone with log access join "this token was created/
// approved at time T" to the same token if it leaks from any other channel
// (a crash report, a proxy log, the client itself echoing it back). Same
// pepper/HMAC scheme as ipTag/uidTag, so a log line alone can no longer be
// replayed against a captured requestId to prove correlation.
function reqTag(id) {
  if (!id) return "none";
  return crypto.createHmac("sha256", LOG_PEPPER).update(String(id)).digest("hex").slice(0, 12);
}

// ── Duress latch enforcement (S06-H1 / S06-C2) ────────────────────────────────
// `accountLock/{uid}` is a one-way latch written by /duress-lock. It used to be
// consulted in exactly one place in the whole system: a client-side `if` in the
// restore UI that ran *after* authentication had already succeeded. Every server
// path that hands out a credential or grants access to backed-up data must
// consult it instead, so the latch survives an adversary who never runs the app.
//
// Fails CLOSED: if the lock cannot be read (Firestore unavailable, permission
// error), treat the account as locked. The alternative — failing open — means a
// transient backend fault silently re-enables the exact access the latch exists
// to deny, and the caller can often induce that fault at will.
async function isAccountLocked(uid) {
  if (!uid || typeof uid !== "string") return true;
  try {
    const snap = await db.collection("accountLock").doc(uid).get();
    return snap.exists && snap.data().locked === true;
  } catch (e) {
    console.error(`isAccountLocked: read failed for uid=${uidTag(uid)} — failing closed:`, e.message);
    return true;
  }
}

// SEC-L02: handlers responded with "Server error: " + e.message, echoing raw
// exception text to the caller. Firestore/Firebase errors routinely embed
// project ids, collection paths, index definitions and internal hostnames,
// handing an attacker a free map of the backend. Full detail now stays in the
// server log; the client gets a generic message plus a correlation id it can
// quote in a support request.
function sendServerError(res, tag, err, status = 500) {
  const ref = crypto.randomBytes(6).toString("hex");
  console.error(`${tag} error [ref=${ref}]:`, err && err.stack ? err.stack : err);
  if (res.headersSent) return;
  res.writeHead(status, { "Content-Type": "text/plain" });
  res.end(`Server error (ref: ${ref})`);
}

// S04-M1: see the comment on checkWaitlistIpRateLimit above — same fix.
function checkIpRateLimit(ip) {
  const key = pure.normalizeIpForRateLimit(ip);
  const now = Date.now();
  const rec = ipHits.get(key);
  if (!rec || now - rec.windowStart >= IP_WINDOW_MS) {
    ipHits.set(key, { count: 1, windowStart: now });
    return true; // allowed
  }
  if (rec.count >= IP_MAX_HITS) return false; // blocked
  rec.count++;
  return true; // allowed
}

function sha256hex(hexStr) {
  return crypto.createHash("sha256").update(Buffer.from(hexStr, "hex")).digest("hex");
}

// Constant-time hex-digest comparison used by the /mintToken identity check.
// Implementation lives in ./lib/pure (unit-tested there) so index.js and the
// tests exercise the same function rather than two copies that can drift.
const timingSafeEqualHex = pure.timingSafeEqualHex;

// ── SSRF guard helpers for /linkPreview ───────────────────────────────────────
// Block private/loopback addresses and cloud metadata endpoints. Applied both
// to the initial user-supplied URL and to every redirect hop (see
// fetchFollowingSafeRedirects below) — checking only the first URL would let
// a malicious server redirect the fetch to an internal address afterwards.
const isBlockedPreviewHost = pure.isBlockedPreviewHost;

// S04-H1: the predicate above inspects the hostname STRING only. It never
// resolves DNS and misses IPv6/alternate literal encodings, so a public-looking
// name with a private A record (or `http://2130706433/`, or `http://[fd00::1]/`)
// walked straight through it. `lib/egressGuard.js` closes those gaps and is
// unit-tested in `lib/egressGuard.test.js`. Both checks run on every hop —
// the old one is kept as cheap defence in depth, not replaced.
const egressGuard = require("./lib/egressGuard");

// S04-H1 (residual): undici's fetch + a per-request Agent whose connect.lookup
// is PINNED to the exact address we already validated with resolveAndCheckHost().
// Node's global fetch offers no way to pin the socket, so the validated name was
// re-resolved by the kernel at connect() time — the check-then-connect gap a
// sub-second-TTL DNS-rebinding attacker races (documented in egressGuard.js and
// RISK_REGISTER.md). Pinning closes that window: the socket can only reach an
// address that already passed the public-address check on this hop.
const { fetch: undiciFetch, Agent: UndiciAgent } = require("undici");
const net = require("net");

// Builds a net.lookup-compatible function that ALWAYS returns the pre-validated
// addresses, never touching DNS again. This is what pins the connection.
function makePinnedLookup(addresses) {
  return function pinnedLookup(_hostname, options, callback) {
    const cb = typeof options === "function" ? options : callback;
    const opts = typeof options === "function" ? {} : (options || {});
    const records = addresses.map((address) => ({
      address,
      family: net.isIPv6(address) ? 6 : 4,
    }));
    if (opts.all) return cb(null, records);
    return cb(null, records[0].address, records[0].family);
  };
}

// ── Link-preview image proxy (S04-H3 / S08-H4) ────────────────────────────────
// og:image URLs are rewritten to point at THIS server and signed with this
// secret, so the recipient's device never contacts the linked host directly (see
// lib/imageProxy.js for the full attack description).
//
// Deliberately reuses MEDIA_TOKEN_SECRET's operational shape but a SEPARATE key:
// this secret authorises "fetch a public image", while MEDIA_TOKEN_SECRET
// authorises B2 media access. Sharing one key across two different capabilities
// means a weakness in either widens the blast radius of both.
const imageProxy = require("./lib/imageProxy");

// Absolute origin of THIS server, as the client should address it. Needed
// because the proxied og:image URL is handed to Android's Glide, which cannot
// resolve a root-relative path ��� a relative URL would simply render no image.
// PUBLIC_BASE_URL wins when set (correct behind a proxy that rewrites Host);
// otherwise derive it from the request the same way adminSessionCookie() decides
// `Secure`, since Render terminates TLS upstream and req.socket is plain HTTP.
function publicOriginFor(req) {
  const configured = String(process.env.PUBLIC_BASE_URL || "").trim().replace(/\/+$/, "");
  if (configured) return configured;
  const forwardedProto = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim().toLowerCase();
  const proto = forwardedProto || (req.socket && req.socket.encrypted ? "https" : "http");
  const host = String(req.headers["x-forwarded-host"] || req.headers.host || "").split(",")[0].trim();
  if (!host) return "";
  return `${proto}://${host}`;
}

// A weak value here is treated as UNSET rather than fatal. The asymmetry with
// ADMIN_TOKEN is intentional: a forgeable proxy signature must not be trusted,
// but link previews are a cosmetic feature, and aborting the whole messaging
// server over one would trade a small exposure for a total outage. Degrading to
// "no preview images" fails closed without that cost.
const LINK_PREVIEW_PROXY_SECRET = (() => {
  const raw = process.env.LINK_PREVIEW_PROXY_SECRET || "";
  if (!raw) {
    console.warn(
      "LINK_PREVIEW_PROXY_SECRET is not set — link previews will omit images " +
      "rather than leaking recipient IPs to the linked host (S04-H3). Set it to a " +
      "random 32-byte hex value to enable preview images."
    );
    return "";
  }
  const verdict = adminSecret.evaluateSecretStrength("LINK_PREVIEW_PROXY_SECRET", raw);
  if (!verdict.ok) {
    console.error(
      `LINK_PREVIEW_PROXY_SECRET rejected (${verdict.reason}). Preview images are ` +
      `DISABLED until this is fixed — a guessable signing key would turn ` +
      `/linkPreviewImage into an open proxy. ${verdict.remedy}`
    );
    return "";
  }
  return raw;
})();

// Fetches targetUrl, manually validating and following redirects (instead of
// `redirect: "follow"`) so each hop is re-checked before it is fetched. Throws
// on a blocked/invalid hop or too many redirects.
async function fetchFollowingSafeRedirects(targetUrl, { headers, timeoutMs, maxRedirects = 5 }) {
  let current = targetUrl;
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const parsed = new URL(current);
    if (!["http:", "https:"].includes(parsed.protocol) || isBlockedPreviewHost(parsed.hostname)) {
      throw new Error(`Blocked redirect target: ${parsed.hostname}`);
    }
    // S04-H1: full literal-form + DNS-resolved check on THIS hop. Doing it per
    // hop matters as much as doing it at all: a public first host can 302 to
    // `http://169.254.169.254/` or to a name whose A record is 10.x.
    const targetVerdict = egressGuard.evaluatePreviewTarget(current);
    if (!targetVerdict.ok) {
      throw new Error(`Blocked redirect target (${targetVerdict.reason}): ${parsed.hostname}`);
    }
    const dnsVerdict = await egressGuard.resolveAndCheckHost(parsed.hostname);
    if (!dnsVerdict.ok || !Array.isArray(dnsVerdict.addresses) || dnsVerdict.addresses.length === 0) {
      throw new Error(`Blocked redirect target (${dnsVerdict.reason || "no validated address"}): ${parsed.hostname}`);
    }
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), timeoutMs);
    // Pin the connection to the address(es) we just validated. Building the
    // Agent per hop is deliberate: each redirect resolves and re-validates its
    // own host, so the pin must track the current hop, not the first one.
    const agent = new UndiciAgent({
      connect: { lookup: makePinnedLookup(dnsVerdict.addresses) },
    });
    const closeAgent = () => { agent.close().catch(() => {}); };
    let response;
    try {
      response = await undiciFetch(current, {
        headers,
        signal: ctrl.signal,
        redirect: "manual",
        dispatcher: agent,
      });
    } catch (e) {
      clearTimeout(t);
      closeAgent();
      throw e;
    } finally {
      clearTimeout(t);
    }
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get("location");
      // This response's body is discarded — cancel it and close its pinned
      // agent before following the redirect, so the socket isn't held open.
      try { await response.body?.cancel(); } catch { /* already closed */ }
      closeAgent();
      if (!location) throw new Error("Redirect with no Location header");
      current = new URL(location, current).toString();
      continue;
    }
    // `cleanup` closes the pinned agent; the caller must invoke it once the
    // response body has been fully read (or when bailing out), so the socket
    // pool doesn't leak.
    return { response, finalUrl: current, cleanup: closeAgent };
  }
  throw new Error("Too many redirects");
}

// ── Request body size limit ───────────────────────────────────────────────────
// Prevents DoS via oversized request bodies. 64 KB is plenty for all valid
// JSON payloads; media goes to B2 directly via presigned URLs, never here.
const MAX_BODY_BYTES = 64 * 1024; // 64 KB

function readBody(req, res) {
  return new Promise((resolve, reject) => {
    let body = "";
    let bytes = 0;
    req.on("data", (chunk) => {
      bytes += chunk.length;
      if (bytes > MAX_BODY_BYTES) {
        res.writeHead(413, { "Content-Type": "text/plain" });
        res.end("Request body too large");
        req.destroy();
        reject(new Error("body_too_large"));
        return;
      }
      body += chunk;
    });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

// Callback-style counterpart to readBody(), for handlers written in the
// on("data")/on("end") style (several routes below need to run
// requireAdminAuth or other checks before body parsing, so they never
// migrated to the Promise-based helper above). S05-L2: that ordering used to
// be aspirational — every admin route called this FIRST and ran
// requireAdminAuth() only inside the "end" callback, letting an
// unauthenticated caller make the server buffer a body and hold the socket
// open before ever being rejected. requireAdminAuthThenBody() (below) is now
// the actual enforcement point: it runs requireAdminAuth() first and only
// calls into this function on success, so the auth-before-body order this
// comment describes is real again. Enforces the same
// MAX_BODY_BYTES cap: the naive `body += chunk` pattern this replaces has
// no size limit of its own — it only inherited protection from the
// declared Content-Length pre-check up in the request handler, which a
// chunked-encoding request (no Content-Length header) bypasses entirely.
// Calls onComplete(body) only when the body stayed within the limit;
// otherwise the 413 response is already sent and onComplete is not called.
function collectBody(req, res, onComplete) {
  let body = "";
  // S02-L4/S04-L1: the cap was `body.length > MAX_BODY_BYTES`, i.e. the JS
  // string's UTF-16 code-unit count, not the wire byte count. `chunk` arrives
  // as a Buffer (no setEncoding() is ever called on `req`, deliberately — see
  // note below), and `body += chunk` coerces it via `chunk.toString("utf8")`.
  // A 4-byte UTF-8 sequence (e.g. many emoji) decodes to 2 UTF-16 code units,
  // so `body.length` undercounts true bytes by up to 2x — an attacker sending
  // such bodies could push roughly double MAX_BODY_BYTES onto the heap before
  // this check ever tripped. Track true byte length from the Buffer directly,
  // matching the (already-correct) pattern in readBody() above.
  let bytes = 0;
  let tooLarge = false;
  req.on("data", (chunk) => {
    if (tooLarge) return;
    bytes += chunk.length;
    body += chunk;
    if (bytes > MAX_BODY_BYTES) {
      tooLarge = true;
      res.writeHead(413, { "Content-Type": "text/plain" });
      res.end("Request body too large");
      req.destroy();
    }
  });
  req.on("end", () => {
    if (tooLarge) return;
    onComplete(body);
  });
}

// S05-L2: every admin POST route used to call collectBody() FIRST and run
// requireAdminAuth() only inside the "end" callback — the exact inverse of
// what the comment on collectBody() above claims. requireAdminAuth() reads
// only headers and the session cookie, so nothing about it requires waiting
// for the body; running it here means an unauthenticated caller is rejected
// (401/429/503) before the server buffers a single byte of body or holds the
// socket open trickling toward MAX_BODY_BYTES for as long as Node's default
// requestTimeout allows — previously free for an attacker to do repeatedly
// against a single-process server that also carries FCM delivery. On
// rejection the request is explicitly destroyed rather than left to drain on
// its own, so an unauthenticated caller cannot hold the connection open past
// its own 401/429/503 by continuing to trickle bytes it knows will never be
// read.
function requireAdminAuthThenBody(req, res, onBody) {
  (async () => {
    if (!(await requireAdminAuth(req, res))) {
      req.destroy();
      return;
    }
    collectBody(req, res, onBody);
  })();
}

// ── Admin panel HTML shell ─────────────────────────────────────────────────────
// Self-contained page (no build step, no external assets) served at GET /admin.
//
// S05-I2: this comment used to describe the page prompting for and holding the
// operator token in memory, then sending it as `x-admin-token` on every
// fetch. That is no longer how the page authenticates: the login form below
// posts the token once to POST /admin/login, which exchanges it for an
// HttpOnly, session-cookie-backed session (adminSessionCookie() /
// requireAdminAuth() above) — the page itself never sees or holds the token
// after that POST, and every /admin/api/* fetch below relies on the browser
// attaching that cookie automatically. `x-admin-token` remains a valid
// SECOND auth path for scripted/non-browser callers (requireAdminAuth
// accepts either), but the browser UI does not use it. All rendered values
// go through textContent, never innerHTML, so nothing from Firestore can
// execute as markup.
const ADMIN_PAGE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DuoShield Admin</title>
<meta name="robots" content="noindex, nofollow">
<style>
  :root { color-scheme: dark; --bg:#080c14; --panel:#0f1620; --panel-strong:#162232; --line:#263344; --text:#edf4ff; --muted:#96a6b8; --accent:#00c9e0; --accent-strong:#73f1ff; --danger:#ff6b72; font-family:Inter,ui-sans-serif,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
  * { box-sizing:border-box; }
  body { min-height:100vh; margin:0; background:radial-gradient(circle at 80% 0%,#123147 0,transparent 34rem),var(--bg); color:var(--text); }
  button,input { font:inherit; }
  button { -webkit-tap-highlight-color:transparent; }
  .gate { width:min(calc(100% - 32px),390px); margin:clamp(56px,15vh,140px) auto; padding:32px; text-align:center; background:rgba(15,22,32,.94); border:1px solid var(--line); border-radius:18px; box-shadow:0 24px 80px rgba(0,0,0,.35); }
  .brand-mark { display:grid; place-items:center; width:48px; height:48px; margin:0 auto 18px; border-radius:14px; color:var(--bg); background:linear-gradient(135deg,var(--accent-strong),var(--accent)); font-weight:800; }
  h1 { margin:0 0 6px; font-size:clamp(20px,3vw,28px); letter-spacing:-.02em; }
  h2 { margin:0; font-size:16px; }
  .sub { color:var(--muted); font-size:13px; line-height:1.5; }
  .gate .sub { margin-bottom:22px; }
  .gate input,.search-input { width:100%; min-height:46px; padding:11px 13px; border:1px solid var(--line); border-radius:10px; outline:0; background:#0b121c; color:var(--text); }
  .gate input:focus,.search-input:focus { border-color:var(--accent); box-shadow:0 0 0 3px rgba(0,201,224,.16); }
  .gate button { width:100%; min-height:46px; margin-top:12px; border:0; border-radius:10px; background:linear-gradient(135deg,#14d8ec,#008eb1); color:#001218; font-weight:750; cursor:pointer; }
  .gate button:disabled { opacity:.6; cursor:wait; }
  #app { display:none; width:min(calc(100% - 32px),1180px); margin:0 auto; padding:30px 0 64px; }
  .app-header { display:flex; align-items:flex-start; justify-content:space-between; gap:18px; margin-bottom:26px; }
  .eyebrow { margin:0 0 8px; color:var(--accent-strong); font-size:11px; font-weight:750; letter-spacing:.14em; text-transform:uppercase; }
  .header-actions { display:flex; gap:8px; flex-wrap:wrap; }
  .stats { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; margin-bottom:24px; }
  .stat,section { background:rgba(15,22,32,.88); border:1px solid var(--line); border-radius:14px; }
  .stat { padding:16px; }
  .stat-label { color:var(--muted); font-size:12px; }
  .stat-value { margin-top:6px; font-size:25px; font-weight:760; }
  section { margin-bottom:16px; overflow:hidden; }
  .section-head { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:18px 18px 14px; }
  .section-help { padding:0 18px 14px; color:var(--muted); font-size:12px; }
  .section-body { padding:0 18px 18px; }
  .search-row { display:flex; gap:8px; align-items:center; }
  .search-row .search-input { flex:1; font-family:ui-monospace,SFMono-Regular,monospace; }
  .invite-form,.invite-tools,.pager { display:flex; gap:8px; align-items:end; flex-wrap:wrap; }
  .invite-form { margin-bottom:14px; padding:14px; border:1px solid var(--line); border-radius:10px; background:#0b121c; }
  .field { display:flex; flex:1 1 180px; flex-direction:column; gap:6px; color:var(--muted); font-size:12px; }
  .field input,.field select { min-height:42px; padding:9px 11px; border:1px solid var(--line); border-radius:8px; outline:0; background:var(--panel); color:var(--text); }
  .token-reveal { margin:0 0 14px; padding:14px; border:1px solid rgba(0,201,224,.45); border-radius:10px; background:rgba(0,201,224,.07); }
  .token-reveal .mono { display:block; margin:8px 0; color:var(--accent-strong); }
  .invite-tools { margin-bottom:10px; }
  .invite-tools .search-input { flex:1 1 220px; }
  .status-pill { display:inline-flex; padding:4px 7px; border:1px solid var(--line); border-radius:999px; color:var(--muted); font-size:11px; text-transform:capitalize; }
  .pager { justify-content:flex-end; margin-top:12px; }
  .search-result { display:flex; align-items:center; justify-content:space-between; gap:14px; margin:12px 0 16px; padding:14px; border:1px solid var(--line); border-radius:10px; background:#0b121c; }
  .search-status { margin-top:4px; color:var(--muted); font-size:12px; }
  table { width:100%; border-collapse:collapse; font-size:13px; }
  th,td { padding:12px 10px; border-bottom:1px solid rgba(38,51,68,.72); text-align:left; vertical-align:middle; }
  th { color:var(--muted); font-size:11px; font-weight:650; letter-spacing:.06em; text-transform:uppercase; }
  tr:last-child td { border-bottom:0; }
  .action { min-height:36px; padding:7px 12px; border:1px solid var(--line); border-radius:8px; background:var(--panel-strong); color:var(--text); cursor:pointer; font-size:12px; font-weight:650; white-space:nowrap; }
  .action:hover { border-color:#4a6078; background:#1a2939; }
  .action:disabled { opacity:.55; cursor:wait; }
  .action.primary { border-color:rgba(0,201,224,.55); color:var(--accent-strong); }
  .action.danger { border-color:rgba(255,107,114,.7); color:var(--danger); }
  .empty,.loading { color:var(--muted); font-size:13px; padding:14px 0 2px; }
  .err { min-height:19px; color:var(--danger); font-size:13px; margin-top:10px; }
  .toast { position:fixed; right:18px; bottom:18px; z-index:10; max-width:min(420px,calc(100% - 36px)); padding:12px 15px; border:1px solid var(--line); border-radius:10px; background:#182434; box-shadow:0 12px 30px rgba(0,0,0,.3); font-size:13px; }
  .mono { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:12px; overflow-wrap:anywhere; }
  .mono.copyable { cursor:pointer; border-radius:4px; transition:color .12s ease,background .12s ease; }
  .mono.copyable:hover { color:var(--accent-strong); background:rgba(0,201,224,.08); }
  .mono.copyable:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
  .last-updated { margin-top:6px; font-size:12px; color:var(--muted); min-height:15px; }
  .refresh { margin-left:auto; }
  .refresh:disabled { opacity:.6; cursor:wait; }
  .sr-only { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
  @media (max-width:760px) { #app{width:min(calc(100% - 20px),620px);padding-top:18px}.app-header{flex-direction:column}.header-actions{width:100%}.header-actions .action{flex:1}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}.section-head{padding:15px 14px 12px}.section-help,.section-body{padding-left:14px;padding-right:14px}.table-scroll{overflow-x:auto;margin:0 -14px;padding:0 14px}.table-scroll table{min-width:720px}.search-row,.invite-form,.invite-tools{align-items:stretch;flex-direction:column}.search-row .action,.invite-form .action,.invite-tools .action{width:100%}.search-result{align-items:flex-start;flex-direction:column}.search-result .action{width:100%} }
  @media (max-width:390px) { .gate{width:calc(100% - 20px);padding:24px 18px}.stats{gap:8px}.stat{padding:13px}.stat-value{font-size:21px} }
</style>
</head>
<body>

  <div class="gate" id="gate" style="__GATE_STYLE__">
    <div class="brand-mark" aria-hidden="true">DS</div>
    <h1>DuoShield Admin</h1>
      <div class="sub">Secure operator access for invitations, account locks, and duress PIN eligibility.</div>
    <form id="gateForm" action="/admin/login" method="post">
      <label class="sr-only" for="tokenInput">Admin token</label>
      <input type="password" id="tokenInput" name="token" placeholder="Enter admin token" autofocus autocomplete="current-password" required>
      <button type="submit" id="unlockBtn">Unlock dashboard</button>
    </form>
    <div class="err" id="gateErr" role="alert">__GATE_ERROR__</div>
  </div>

  <main id="app" style="__APP_STYLE__">
    <header class="app-header">
      <div>
        <div class="eyebrow">Operator console</div>
        <h1>DuoShield Admin</h1>
        <div class="sub">Manage access and duress-PIN eligibility without opening the Firebase console.</div>
        <div class="sub last-updated" id="lastUpdated" aria-live="polite"></div>
      </div>
      <div class="header-actions">
        <button class="action" type="button" id="autoRefreshBtn" aria-pressed="false">Auto-refresh: Off</button>
        <button class="action" type="button" id="refreshAllBtn">Refresh all</button>
        <button class="action danger" type="button" id="revokeAllSessionsBtn" title="Signs out every admin session, including this one">Sign out everywhere</button>
        <button class="action" type="button" id="signOutBtn">Sign out</button>
      </div>
    </header>

    <div class="stats" aria-label="Account summary">
      <div class="stat"><div class="stat-label">Active invites</div><div class="stat-value" id="activeCount">—</div></div>
      <div class="stat"><div class="stat-label">Locked accounts</div><div class="stat-value" id="lockedCount">—</div></div>
      <div class="stat"><div class="stat-label">Duress enabled</div><div class="stat-value" id="duressCount">—</div></div>
      <div class="stat"><div class="stat-label">Recent actions</div><div class="stat-value" id="auditCount">—</div></div>
    </div>

    <section>
      <div class="section-head"><h2>Invitation access</h2><button class="action refresh" type="button" id="invitesRefreshBtn">Refresh</button></div>
      <div class="section-help">Issue a single-use code to a known recipient. The full code is shown once and never stored by the server.</div>
      <div class="section-body">
        <form class="invite-form" id="inviteCreateForm">
          <label class="field">Recipient label<input id="inviteLabel" maxlength="80" required autocomplete="off" placeholder="e.g. Jordan — beta cohort"></label>
          <label class="field">Internal notes<input id="inviteNotes" maxlength="500" autocomplete="off" placeholder="Optional"></label>
          <label class="field">Expires<input id="inviteExpiry" type="datetime-local" required></label>
          <button class="action primary" type="submit" id="inviteCreateBtn">Create invite</button>
        </form>
        <div class="token-reveal" id="inviteReveal" hidden role="status">
          <strong>Copy this code now — it will not be shown again.</strong>
          <span class="mono" id="inviteToken"></span>
          <button class="action primary" type="button" id="inviteCopyBtn">Copy invite code</button>
          <button class="action" type="button" id="inviteDismissBtn">Dismiss</button>
        </div>
        <div class="invite-tools">
          <label class="sr-only" for="inviteSearch">Search recipient labels</label>
          <input class="search-input" id="inviteSearch" maxlength="80" placeholder="Search recipient labels" autocomplete="off">
          <label class="sr-only" for="inviteStatus">Filter by status</label>
          <select class="action" id="inviteStatus"><option value="all">All statuses</option><option value="active">Active</option><option value="used">Used</option><option value="expired">Expired</option><option value="revoked">Revoked</option></select>
          <button class="action" type="button" id="inviteApplyBtn">Apply</button>
        </div>
        <div class="table-scroll"><table><thead><tr><th>Recipient</th><th>Status</th><th>Fingerprint</th><th>Created</th><th>Expires</th><th><span class="sr-only">Action</span></th></tr></thead><tbody id="invitesBody"></tbody></table></div>
        <div class="loading" id="invitesLoading">Loading…</div>
        <div class="empty" id="invitesEmpty" hidden>No invitations match these filters.</div>
        <div class="pager"><button class="action" type="button" id="invitesPrevBtn" disabled>Previous</button><button class="action" type="button" id="invitesNextBtn" disabled>Next</button></div>
      </div>
    </section>

    <section>
      <div class="section-head"><h2>Locked accounts</h2><button class="action refresh" type="button" id="lockedRefreshBtn">Refresh</button></div>
      <div class="section-body">
        <div class="table-scroll"><table><thead><tr><th>UID</th><th>Locked at</th><th><span class="sr-only">Action</span></th></tr></thead><tbody id="lockedBody"></tbody></table></div>
        <div class="loading" id="lockedLoading">Loading…</div>
        <div class="empty" id="lockedEmpty" hidden>No locked accounts.</div>
      </div>
    </section>

    <section>
      <div class="section-head"><h2>Duress PIN enrollment</h2><button class="action refresh" type="button" id="duressRefreshBtn">Refresh</button></div>
      <div class="section-help">Search a real account UID first. Enable makes the secondary-PIN setup available in the app; it does not set a PIN for the user.</div>
      <div class="section-body">
      <div class="search-row">
        <label class="sr-only" for="duressUidInput">Account UID</label>
        <input class="search-input" id="duressUidInput" type="text" placeholder="Search by account UID" autocomplete="off" spellcheck="false">
        <button class="action primary" id="duressSearchButton" type="button">Search account</button>
      </div>
      <div id="duressSearchResult" class="search-result" hidden>
          <div>
            <div class="mono" id="duressSearchUid"></div>
            <div class="search-status" id="duressSearchStatus"></div>
          </div>
          <button class="action" id="duressSearchAction" type="button"></button>
      </div>
      <div class="empty" id="duressSearchEmpty" hidden>No account found for that UID.</div>
      <div class="table-scroll"><table><thead><tr><th>UID</th><th>Enrolled at</th><th><span class="sr-only">Action</span></th></tr></thead><tbody id="duressBody"></tbody></table></div>
      <div class="loading" id="duressLoading">Loading…</div>
      <div class="empty" id="duressEmpty" hidden>No accounts enrolled.</div>
      </div>
    </section>

    <section>
      <div class="section-head"><h2>Audit log</h2><button class="action refresh" type="button" id="auditRefreshBtn">Refresh</button></div>
      <div class="section-body">
        <div class="table-scroll"><table><thead><tr><th>Action</th><th>Target</th><th>Admin IP</th><th>When</th></tr></thead><tbody id="auditBody"></tbody></table></div>
        <div class="loading" id="auditLoading">Loading…</div>
        <div class="empty" id="auditEmpty" hidden>No audit entries yet.</div>
      </div>
    </section>

    <div id="inactivityBanner" hidden style="position:fixed;top:0;left:0;right:0;background:#b83442;color:#fff;text-align:center;padding:10px 16px;font-size:13px;z-index:999;">
      Session will expire due to inactivity — <span id="inactivityCountdown">60</span>s remaining.
    </div>
  </main>

<script nonce="__SCRIPT_NONCE__">
let TOKEN = "";
let sessionActive = false;

function toast(msg) {
  const el = document.createElement("div");
  el.className = "toast";
  el.textContent = msg;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 3000);
}

async function api(path, opts) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  let res;
  try {
    res = await fetch(path, Object.assign({}, opts, {
      // The admin API authenticates via the HttpOnly duoshield_admin_session
      // cookie set at login. Older Android WebViews / in-app browsers (and some
      // privacy browsers) historically default fetch() credentials to "omit",
      // which strips that cookie and makes every /admin/api/* call 401 — the
      // panel then looks "static": buttons highlight on tap but nothing loads.
      // Send credentials explicitly so the session cookie is always attached.
      credentials: "same-origin",
      headers: Object.assign({ "x-admin-token": TOKEN, "Content-Type": "application/json" }, (opts && opts.headers) || {}),
      signal: controller.signal,
    }));
  } catch (e) {
    throw new Error(e.name === "AbortError" ? "Request timed out. Try again." : "Network error. Check the connection.");
  } finally {
    clearTimeout(timeout);
  }
  if (res.status === 401) {
    forceLogout(false);
    document.getElementById("gateErr").textContent = "Your session expired. Sign in again.";
    throw new Error("unauthorized");
  }
  if (res.status === 429) throw new Error("Too many attempts. Wait a few minutes and try again.");
  if (!res.ok) throw new Error(await res.text());
  const ct = res.headers.get("content-type") || "";
  return ct.includes("application/json") ? res.json() : null;
}

function showApp() {
  sessionActive = true;
  document.getElementById("gate").style.display = "none";
  document.getElementById("app").style.display = "block";
  document.getElementById("app").removeAttribute("hidden");
  resetInactivityTimer();
}

function setLoading(name, loading) {
  const el = document.getElementById(name + "Loading");
  if (el) el.hidden = !loading;
}

function setEmpty(name, empty) {
  const el = document.getElementById(name + "Empty");
  if (el) el.hidden = !empty;
}

function setCount(name, value) {
  const el = document.getElementById(name + "Count");
  if (el) el.textContent = String(value);
}

  function refreshAll() {
    return Promise.all([loadInvites(true), loadLocked(), loadDuressEnrolled(), loadAuditLog()]);
  }

// ── "Last updated" indicator ────────────────────────────────────────────────�����
function markUpdated() {
  const el = document.getElementById("lastUpdated");
  if (el) el.textContent = "Updated " + new Date().toLocaleTimeString();
}

// ── Refresh buttons: shared loading feedback ────────────────────────────────��
// Wraps a section (or "refresh all") load call so the triggering button shows a
// busy state and the "Last updated" stamp advances on success.
async function reload(btn, fn) {
  let prev;
  if (btn) { prev = btn.textContent; btn.disabled = true; btn.textContent = "Refreshing…"; }
  try {
    await fn();
    markUpdated();
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = prev || "Refresh"; }
  }
}

// ── Auto-refresh toggle (every 30s) ──────────────────────────────────────────
let autoRefreshTimer = null;
function toggleAutoRefresh() {
  const btn = document.getElementById("autoRefreshBtn");
  if (autoRefreshTimer) {
    clearInterval(autoRefreshTimer);
    autoRefreshTimer = null;
    if (btn) { btn.textContent = "Auto-refresh: Off"; btn.classList.remove("primary"); btn.setAttribute("aria-pressed", "false"); }
    toast("Auto-refresh disabled");
  } else {
    autoRefreshTimer = setInterval(() => {
      if (sessionActive) refreshAll().then(markUpdated).catch(() => {});
    }, 30000);
    if (btn) { btn.textContent = "Auto-refresh: On"; btn.classList.add("primary"); btn.setAttribute("aria-pressed", "true"); }
    toast("Auto-refresh every 30s");
  }
}

// ── Click-to-copy for identifiers (UIDs, request IDs, IPs) ───────────��───────
// Copies to the clipboard, with an execCommand fallback for older webviews.
function copyText(text) {
  const done = () => toast("Copied to clipboard");
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, () => fallbackCopy(text, done));
  } else {
    fallbackCopy(text, done);
  }
}
function fallbackCopy(text, done) {
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.style.position = "fixed";
  ta.style.top = "-1000px";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  try { document.execCommand("copy"); done(); } catch (_) { toast("Copy failed"); }
  ta.remove();
}
function copyValue(el) {
  const val = (((el.dataset && el.dataset.full) || el.textContent) || "").trim();
  if (!val || val === "—") return;
  copyText(val);
}

// Mark a monospace identifier cell as copyable (idempotent). New table rows are
// decorated automatically by the observer below; static nodes call this directly.
function decorateCopyable(el) {
  if (!el || (el.dataset && el.dataset.copyReady)) return;
  const val = (((el.dataset && el.dataset.full) || el.textContent) || "").trim();
  if (!val || val === "—") return;
  el.dataset.copyReady = "1";
  el.classList.add("copyable");
  el.setAttribute("role", "button");
  el.setAttribute("tabindex", "0");
  el.setAttribute("title", "Click to copy");
  el.setAttribute("aria-label", "Copy identifier to clipboard");
}
function scanCopyables(root) {
  const scope = root && root.querySelectorAll ? root : document;
  scope.querySelectorAll(".mono").forEach(decorateCopyable);
}
// Any .mono cell added to the DOM (every rendered table row) becomes copyable.
new MutationObserver((mutations) => {
  for (const m of mutations) {
    for (const node of m.addedNodes) {
      if (node.nodeType !== 1) continue;
      if (node.matches && node.matches(".mono")) decorateCopyable(node);
      if (node.querySelectorAll) scanCopyables(node);
    }
  }
}).observe(document.body, { childList: true, subtree: true });

document.addEventListener("click", (e) => {
  const cell = e.target.closest && e.target.closest(".mono.copyable");
  if (cell) copyValue(cell);
});
document.addEventListener("keydown", (e) => {
  if (e.key !== "Enter" && e.key !== " ") return;
  const cell = e.target.closest && e.target.closest(".mono.copyable");
  if (!cell) return;
  e.preventDefault();
  copyValue(cell);
});

let inviteCursor = null;
let inviteNextCursor = null;
let inviteCursorHistory = [];

async function loadInvites(reset) {
  if (reset) { inviteCursor = null; inviteCursorHistory = []; }
  setLoading("invites", true);
  try {
    const status = document.getElementById("inviteStatus").value;
    const search = document.getElementById("inviteSearch").value.trim();
    const params = new URLSearchParams({ status, search, pageSize: "25" });
    if (inviteCursor) params.set("cursor", inviteCursor);
    const data = await api("/admin/api/invites?" + params.toString());
    showApp();
    inviteNextCursor = data.nextCursor;
    document.getElementById("invitesPrevBtn").disabled = inviteCursorHistory.length === 0;
    document.getElementById("invitesNextBtn").disabled = !inviteNextCursor;
    setCount("active", data.counts.active);
    const body = document.getElementById("invitesBody");
    body.innerHTML = "";
    setEmpty("invites", !data.items.length);
    for (const item of data.items) {
      const tr = document.createElement("tr");
      const labelTd = document.createElement("td");
      labelTd.textContent = item.label;
      if (item.notes) labelTd.title = item.notes;
      tr.appendChild(labelTd);
      const statusTd = document.createElement("td");
      const pill = document.createElement("span");
      pill.className = "status-pill";
      pill.textContent = item.status;
      statusTd.appendChild(pill);
      tr.appendChild(statusTd);
      const fpTd = document.createElement("td");
      fpTd.className = "mono";
      fpTd.textContent = item.fingerprint || "—";
      tr.appendChild(fpTd);
      for (const value of [item.createdAt, item.expiresAt]) {
        const td = document.createElement("td");
        td.textContent = value ? new Date(value).toLocaleString() : "—";
        tr.appendChild(td);
      }
      const actionTd = document.createElement("td");
      if (item.status === "active") {
        const revokeBtn = document.createElement("button");
        revokeBtn.className = "action danger";
        revokeBtn.textContent = "Revoke";
        revokeBtn.onclick = () => revokeInvite(item.id, revokeBtn);
        actionTd.appendChild(revokeBtn);
      }
      tr.appendChild(actionTd);
      body.appendChild(tr);
    }
    if (data.capped) toast("Showing the newest 1,000 invites. Archive older records to restore complete counts.");
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load invites: " + e.message);
  } finally {
    setLoading("invites", false);
  }
}

async function createInvite(event) {
  event.preventDefault();
  const btn = document.getElementById("inviteCreateBtn");
  btn.disabled = true;
  try {
    const expiresAt = new Date(document.getElementById("inviteExpiry").value).toISOString();
    const data = await api("/admin/api/invites/create", { method: "POST", body: JSON.stringify({
      label: document.getElementById("inviteLabel").value,
      notes: document.getElementById("inviteNotes").value,
      expiresAt,
    }) });
    const token = document.getElementById("inviteToken");
    token.textContent = data.token;
    token.dataset.full = data.token;
    document.getElementById("inviteReveal").hidden = false;
    document.getElementById("inviteCreateForm").reset();
    setDefaultInviteExpiry();
    await Promise.all([loadInvites(true), loadAuditLog()]);
  } catch (e) {
    if (e.message !== "unauthorized") toast("Create failed: " + e.message);
  } finally { btn.disabled = false; }
}

async function revokeInvite(inviteId, btn) {
  if (!confirm("Revoke this unused invitation?")) return;
  btn.disabled = true;
  try {
    await api("/admin/api/invites/revoke", { method: "POST", body: JSON.stringify({ inviteId }) });
    toast("Invitation revoked.");
    await Promise.all([loadInvites(false), loadAuditLog()]);
  } catch (e) {
    if (e.message !== "unauthorized") toast("Revoke failed: " + e.message);
    btn.disabled = false;
  }
}

function setDefaultInviteExpiry() {
  const date = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  document.getElementById("inviteExpiry").value = date.toISOString().slice(0, 16);
}

function dismissInviteToken() {
  const token = document.getElementById("inviteToken");
  token.textContent = "";
  delete token.dataset.full;
  document.getElementById("inviteReveal").hidden = true;
}

async function loadLocked() {
  setLoading("locked", true);
  try {
    const data = await api("/admin/api/locked");
    showApp();
    const body = document.getElementById("lockedBody");
    body.innerHTML = "";
    setCount("locked", data.accounts.length);
    setEmpty("locked", !data.accounts.length);
    for (const a of data.accounts) {
      const tr = document.createElement("tr");

      const idTd = document.createElement("td");
      idTd.className = "mono";
      idTd.textContent = a.uid;
      tr.appendChild(idTd);

      const dateTd = document.createElement("td");
      dateTd.textContent = a.lockedAt ? new Date(a.lockedAt).toLocaleString() : "—";
      tr.appendChild(dateTd);

      const actionTd = document.createElement("td");
      const btn = document.createElement("button");
      btn.className = "action danger";
      btn.textContent = "Unfreeze";
      btn.onclick = () => unfreeze(a.uid, btn);
      actionTd.appendChild(btn);
      tr.appendChild(actionTd);

      body.appendChild(tr);
    }
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load locked accounts: " + e.message);
  } finally {
    setLoading("locked", false);
  }
}

async function unfreeze(uid, btn) {
  if (!confirm("Unfreeze account " + uid + "? This lets the app sign in again.")) return;
  btn.disabled = true;
  btn.textContent = "Unfreezing…";
  try {
    await api("/admin/api/locked/unfreeze", { method: "POST", body: JSON.stringify({ uid }) });
    toast("Unfroze " + uid);
    loadLocked();
    loadAuditLog();
  } catch (e) {
    if (e.message !== "unauthorized") { toast("Unfreeze failed: " + e.message); btn.disabled = false; btn.textContent = "Unfreeze"; }
  }
}

async function loadDuressEnrolled() {
  setLoading("duress", true);
  try {
    const data = await api("/admin/api/duress/enrolled");
    const body = document.getElementById("duressBody");
    body.innerHTML = "";
    setCount("duress", data.accounts.length);
    setEmpty("duress", !data.accounts.length);
    for (const a of data.accounts) {
      const tr = document.createElement("tr");

      const idTd = document.createElement("td");
      idTd.className = "mono";
      idTd.textContent = a.uid;
      tr.appendChild(idTd);

      const dateTd = document.createElement("td");
      dateTd.textContent = a.enrolledAt ? new Date(a.enrolledAt).toLocaleString() : "—";
      tr.appendChild(dateTd);

      const actionTd = document.createElement("td");
      const btn = document.createElement("button");
      btn.className = "action danger";
      btn.textContent = "Revoke";
      btn.onclick = () => revokeDuress(a.uid, btn);
      actionTd.appendChild(btn);
      tr.appendChild(actionTd);

      body.appendChild(tr);
    }
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load duress enrolled: " + e.message);
  } finally {
    setLoading("duress", false);
  }
}

let duressSearchUid = "";

async function searchDuressAccount() {
  const input = document.getElementById("duressUidInput");
  const uid = input.value.trim();
  if (!uid) { toast("Enter a UID first"); return; }
  const resultBox = document.getElementById("duressSearchResult");
  const emptyBox  = document.getElementById("duressSearchEmpty");
  resultBox.hidden = true;
  emptyBox.hidden = true;
  const searchButton = document.getElementById("duressSearchButton");
  searchButton.disabled = true;
  searchButton.textContent = "Searching…";
  try {
    const data = await api("/admin/api/account/lookup?uid=" + encodeURIComponent(uid));
    if (!data.accountExists) {
      emptyBox.hidden = false;
      return;
    }
    duressSearchUid = uid;
    const duressUidCell = document.getElementById("duressSearchUid");
    duressUidCell.textContent = uid;
    duressUidCell.dataset.full = uid;
    decorateCopyable(duressUidCell);
    document.getElementById("duressSearchStatus").textContent =
      data.duressEligible ? "Duress PIN: enabled" : "Duress PIN: not enabled";
    const btn = document.getElementById("duressSearchAction");
    btn.className = data.duressEligible ? "action danger" : "action";
    btn.textContent = data.duressEligible ? "Disable" : "Enable";
    btn.onclick = data.duressEligible
      ? () => revokeDuress(uid, btn, true)
      : () => enrollDuress(uid, btn);
    resultBox.hidden = false;
  } catch (e) {
    if (e.message !== "unauthorized") toast("Search failed: " + e.message);
  } finally {
    searchButton.disabled = false;
    searchButton.textContent = "Search account";
  }
}

async function enrollDuress(uid, btn) {
  if (!uid) { toast("Enter a UID first"); return; }
  if (btn) { btn.disabled = true; btn.textContent = "Enabling…"; }
  try {
    await api("/admin/api/duress/enroll", { method: "POST", body: JSON.stringify({ uid }) });
    toast("Enabled duress PIN for " + uid);
    document.getElementById("duressUidInput").value = "";
    document.getElementById("duressSearchResult").hidden = true;
    loadDuressEnrolled();
    loadAuditLog();
  } catch (e) {
    if (e.message !== "unauthorized") toast("Enable failed: " + e.message);
    if (btn) { btn.disabled = false; btn.textContent = "Enable"; }
  }
}

async function revokeDuress(uid, btn, fromSearch) {
  if (!confirm("Revoke duress PIN eligibility for " + uid + "?\\nThey will lose access to the secondary-PIN feature.")) return;
  const resetLabel = fromSearch ? "Disable" : "Revoke";
  btn.disabled = true;
  btn.textContent = "Revoking…";
  try {
    await api("/admin/api/duress/revoke", { method: "POST", body: JSON.stringify({ uid }) });
    toast("Revoked " + uid);
    if (fromSearch) {
      document.getElementById("duressSearchResult").hidden = true;
      document.getElementById("duressUidInput").value = "";
    }
    loadDuressEnrolled();
    loadAuditLog();
  } catch (e) {
    if (e.message !== "unauthorized") { toast("Revoke failed: " + e.message); btn.disabled = false; btn.textContent = resetLabel; }
  }
}

async function loadAuditLog() {
  setLoading("audit", true);
  try {
    const data = await api("/admin/api/auditlog");
    const body = document.getElementById("auditBody");
    body.innerHTML = "";
    setCount("audit", data.entries.length);
    setEmpty("audit", !data.entries.length);
    for (const e of data.entries) {
      const tr = document.createElement("tr");

      const actionTd = document.createElement("td");
      actionTd.textContent = e.action === "waitlist_approved" ? "✅ Waitlist approved"
                           : e.action === "waitlist_denied"   ? "🚫 Waitlist denied"
                           : e.action === "account_unfrozen"  ? "🔓 Account unfrozen"
                           : e.action === "duress_enrolled"   ? "🔐 Duress enrolled"
                           : e.action === "duress_revoked"    ? "❌ Duress revoked"
                           : e.action;
      tr.appendChild(actionTd);

      const targetTd = document.createElement("td");
      targetTd.className = "mono";
      targetTd.textContent = e.requestId ? e.requestId.slice(0, 12) + "…" : (e.uid || "—");
      targetTd.dataset.full = e.requestId || e.uid || "";
      tr.appendChild(targetTd);

      const ipTd = document.createElement("td");
      ipTd.className = "mono";
      ipTd.textContent = e.adminIp || "—";
      tr.appendChild(ipTd);

      const dateTd = document.createElement("td");
      dateTd.textContent = e.at ? new Date(e.at).toLocaleString() : "—";
      tr.appendChild(dateTd);

      body.appendChild(tr);
    }
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load audit log: " + e.message);
  } finally {
    setLoading("audit", false);
  }
}

// ── Inactivity auto-logout (10 minutes) ───����──────────────────────────────────
// Starts counting down once the session is unlocked. Any mouse, keyboard, or
// touch event resets the timer. A 60-second warning banner appears before logout.
const INACTIVITY_TIMEOUT_MS  = 10 * 60 * 1000; // 10 min
const INACTIVITY_WARNING_MS  = 60 * 1000;       // warn 60 s before
let inactivityTimer  = null;
let countdownTimer   = null;
let countdownSeconds = 60;

function resetInactivityTimer() {
  clearTimeout(inactivityTimer);
  clearInterval(countdownTimer);
  document.getElementById("inactivityBanner").hidden = true;
  inactivityTimer = setTimeout(startInactivityWarning, INACTIVITY_TIMEOUT_MS - INACTIVITY_WARNING_MS);
}

function startInactivityWarning() {
  countdownSeconds = 60;
  const banner = document.getElementById("inactivityBanner");
  banner.hidden = false;
  document.getElementById("inactivityCountdown").textContent = countdownSeconds;
  countdownTimer = setInterval(() => {
    countdownSeconds--;
    document.getElementById("inactivityCountdown").textContent = countdownSeconds;
    if (countdownSeconds <= 0) {
      clearInterval(countdownTimer);
      forceLogout();
    }
  }, 1000);
}

async function logout() {
  try { await fetch("/admin/logout", { method: "POST", credentials: "same-origin" }); } catch (_) {}
  forceLogout(false);
}

// S05-M3: bulk-revokes every admin session (including this one) via
// POST /admin/api/sessions/revoke-all, then resets this tab back to the
// login gate exactly like forceLogout — the server already cleared this
// browser's own cookie in the response, so no separate /admin/logout call
// is needed here.
async function revokeAllSessions(btn) {
  if (!confirm("Sign out every admin session, including this one, right now?")) return;
  btn.disabled = true;
  btn.textContent = "Signing out everywhere…";
  try {
    const data = await api("/admin/api/sessions/revoke-all", { method: "POST" });
    toast("Signed out " + data.revokedCount + " session(s).");
    forceLogout(false);
  } catch (e) {
    if (e.message !== "unauthorized") toast("Sign out everywhere failed: " + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "Sign out everywhere";
  }
}

function forceLogout(showMessage = true) {
  TOKEN = "";
  sessionActive = false;
  clearTimeout(inactivityTimer);
  clearInterval(countdownTimer);
  if (autoRefreshTimer) {
    clearInterval(autoRefreshTimer);
    autoRefreshTimer = null;
    const autoBtn = document.getElementById("autoRefreshBtn");
    if (autoBtn) { autoBtn.textContent = "Auto-refresh: Off"; autoBtn.classList.remove("primary"); autoBtn.setAttribute("aria-pressed", "false"); }
  }
  document.getElementById("inactivityBanner").hidden = true;
  document.getElementById("app").style.display = "none";
  document.getElementById("gate").style.display = "block";
  document.getElementById("gateErr").textContent = showMessage ? "Session expired due to inactivity." : "";
  document.getElementById("tokenInput").value = "";
  // Revoke the server-side session so the HttpOnly cookie cannot be reused
  // until the 30-minute TTL elapses. Fire-and-forget; UI is already reset.
  fetch("/admin/logout", { method: "POST", credentials: "same-origin" }).catch(() => {});
}

["mousemove", "mousedown", "keydown", "touchstart", "scroll"].forEach((evt) => {
  document.addEventListener(evt, () => {
    if (sessionActive) resetInactivityTimer();
  }, { passive: true });
});

const SESSION_AUTHENTICATED = __ADMIN_AUTHENTICATED__;
if (SESSION_AUTHENTICATED) {
  showApp();
  refreshAll().then(markUpdated).catch(() => {});
  // Turn auto-refresh on by default so requests submitted from the app appear
  // in the panel on their own, without the operator having to tap Refresh.
  toggleAutoRefresh();
} else {
  const loginError = new URLSearchParams(location.search).get("error");
  if (loginError === "invalid") document.getElementById("gateErr").textContent = "Invalid admin token.";
  if (loginError === "locked") document.getElementById("gateErr").textContent = "Too many failed attempts. Wait 15 minutes and try again.";
  if (loginError === "unconfigured") document.getElementById("gateErr").textContent = "Admin panel is not configured on the server.";
}

document.getElementById("gateForm").addEventListener("submit", () => {
  const btn = document.getElementById("unlockBtn");
  btn.disabled = true;
  btn.textContent = "Verifying…";
});

// ── Toolbar / section-refresh button wiring ────��─────────────────────────────
// These buttons cannot use inline onclick="" attributes: the /admin page ships a
// strict CSP (script-src 'nonce-...' with no 'unsafe-inline'/'unsafe-hashes'),
// under which browsers refuse to run inline event-handler attributes. That made
// the panel look "logged in but frozen" — data loaded via this nonced script, but
// every tap on Refresh / Sign out / Search / Auto-refresh did nothing. Binding
// here, inside the nonced script, is CSP-compliant and works on touch + desktop.
function bindClick(id, handler) {
  const el = document.getElementById(id);
  if (el) el.addEventListener("click", handler);
}
bindClick("autoRefreshBtn", () => toggleAutoRefresh());
bindClick("refreshAllBtn", function () { reload(this, refreshAll); });
bindClick("signOutBtn", () => logout());
bindClick("revokeAllSessionsBtn", function () { revokeAllSessions(this); });
bindClick("invitesRefreshBtn", function () { reload(this, () => loadInvites(false)); });
bindClick("inviteApplyBtn", () => loadInvites(true));
bindClick("inviteCopyBtn", () => copyValue(document.getElementById("inviteToken")));
bindClick("inviteDismissBtn", () => dismissInviteToken());
bindClick("invitesNextBtn", () => {
  if (!inviteNextCursor) return;
  inviteCursorHistory.push(inviteCursor);
  inviteCursor = inviteNextCursor;
  loadInvites(false);
});
bindClick("invitesPrevBtn", () => {
  if (!inviteCursorHistory.length) return;
  inviteCursor = inviteCursorHistory.pop();
  loadInvites(false);
});
document.getElementById("inviteCreateForm").addEventListener("submit", createInvite);
setDefaultInviteExpiry();
bindClick("lockedRefreshBtn", function () { reload(this, loadLocked); });
bindClick("duressRefreshBtn", function () { reload(this, loadDuressEnrolled); });
bindClick("duressSearchButton", () => searchDuressAccount());
bindClick("auditRefreshBtn", function () { reload(this, loadAuditLog); });

document.getElementById("duressUidInput").addEventListener("keydown", (event) => {
  if (!sessionActive) return;
  if (event.key === "Enter") {
    // Do not submit while a CJK IME composition is in progress.
    if (event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    searchDuressAccount();
  }
});
</script>
</body>
</html>
`;

// ── Security response headers ───────���───────────────────────────────��─────────
// Baseline defense-in-depth headers applied to *every* response via setHeader()
// at the top of the request handler. Node merges these with the object passed to
// res.writeHead(), and writeHead values take precedence — so the two HTML routes
// (GET / and GET /admin) override only Content-Security-Policy with a policy that
// permits their own inline <style>/<script>, while everything else keeps the
// strict `default-src 'none'` API policy below.
const CSP_API = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";
// The dashboard (GET /) is fully self-contained: inline <style>, no scripts, no
// network calls.
const CSP_DASHBOARD =
  "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; " +
  "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
// The admin panel (GET /admin) has a single inline <script> block.  We generate
// a fresh 128-bit random nonce on every request and embed it into both the
// <script nonce="…"> attribute and the CSP header.  This completely replaces
// 'unsafe-inline' so injected <script> tags without the nonce are blocked.
function buildAdminCsp(nonce) {
  return (
    `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; ` +
    "img-src 'self' data:; connect-src 'self'; form-action 'self'; " +
    "base-uri 'none'; frame-ancestors 'none'"
  );
}

function setBaselineSecurityHeaders(req, res) {
  // S05-L3: this function set seven security headers but never Cache-Control,
  // so every /admin/api/* JSON response (locked-account list, duress-enrolled
  // list, audit log, waitlist queue) fell through with none — a bare `200`
  // with no cache directives and no `Vary` is heuristically cacheable by the
  // browser's disk cache (recoverable from an operator's machine or backup —
  // this tool's threat model includes device seizure) and by any future
  // shared cache (S04-M3 flags "operator puts Cloudflare in front of the API
  // server" as realistic). GET /admin already set its own, stricter
  // Cache-Control further down (no-store, no-cache, must-revalidate, plus
  // Pragma/Expires) — writeHead()'s headers object always wins over a prior
  // setHeader() call for the same header name (verified: Node does not merge,
  // it overwrites), so setting the same-or-weaker `no-store` here for every
  // `/admin*` path is additive, not a regression on that route. `Vary: Cookie`
  // is set alongside it because authorization on every one of these routes
  // depends on the session cookie, so a shared cache must not conflate
  // responses for different cookies (or none) even if `no-store` were
  // ever weakened later.
  if (String(req.url || "").startsWith("/admin")) {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Vary", "Cookie");
  }
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
  res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
  res.setHeader(
    "Permissions-Policy",
    "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()"
  );
  res.setHeader("Content-Security-Policy", CSP_API);
  // HSTS only over HTTPS. Behind Render/most proxies TLS terminates upstream and
  // the original scheme arrives in X-Forwarded-Proto.
  const forwardedProto = String(req.headers["x-forwarded-proto"] || "")
    .split(",")[0].trim().toLowerCase();
  if (forwardedProto === "https" || req.socket.encrypted) {
    res.setHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
  }
}

// ── Health + status + mintToken HTTP server ──────────────────────────────────���
const server = http.createServer((req, res) => {

  // Baseline security headers on every response (merged with, and overridable by,
  // each route's writeHead — see setBaselineSecurityHeaders above).
  setBaselineSecurityHeaders(req, res);

  // Reject oversized bodies before any routing (DoS guard).
  // Content-Length may be absent (chunked), so also enforce via readBody().
  const declaredLength = parseInt(req.headers["content-length"] || "0", 10);
  if (declaredLength > MAX_BODY_BYTES) {
    res.writeHead(413, { "Content-Type": "text/plain" });
    res.end("Request body too large");
    return;
  }

  // ── POST /mintChallenge ─────────────────────────────────────────────────────
  //
  // Body (JSON): { userId }
  //
  // S07-C1 remediation, part 1 of 2 (see lib/challengeStore.js). Issues a fresh,
  // single-use nonce for userId that /mintToken requires the caller to sign with
  // the identity PRIVATE key.
  //
  // Now wired: /mintToken consumes this nonce via mintChallengeStore.consume()
  // and verifies an XEdDSA signature over it (lib/identityVerify.js) before it
  // will mint anything. The nonce is single-use and TTL'd, so a signature
  // captured off the wire cannot be replayed for a second token.
  //
  // Note this endpoint is intentionally unauthenticated and leaks nothing: it
  // returns a random 32-byte value for ANY userId string, existing or not, so it
  // cannot be used to enumerate which accounts exist.
  //
  if (req.method === "POST" && req.url === "/mintChallenge") {
    collectBody(req, res, async (body) => {
      try {
        const clientIp = getClientIp(req);
        if (!checkIpRateLimit(clientIp)) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }
        const { userId } = JSON.parse(body);
        if (!userId || typeof userId !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId");
          return;
        }
        const nonce = mintChallengeStore.issue(userId);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ nonce, ttlMs: require("./lib/challengeStore").NONCE_TTL_MS }));
      } catch (e) {
        console.error("mintChallenge error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── POST /mintToken ─────────────────────────────────────────────────────────
  //
  // Body (JSON): { userId, identityPubKeyHex, nonce, signatureHex, inviteToken? }
  //
  // Security model:
  //   • Proof of possession (S07-C1): the caller must present a nonce previously
  //     issued by POST /mintChallenge plus an XEdDSA signature over
  //     "DuoShield-mintToken-v1"||0||userId||0||nonce made with the identity
  //     PRIVATE key. The nonce is consumed single-use before the signature is
  //     checked, and verification is done by @signalapp/libsignal-client (same
  //     library/version as the Android client). See lib/identityVerify.js.
  //   • New accounts: identity slot claimed atomically inside a Firestore transaction
  //     before the token is minted.  First caller wins; concurrent first-claim attempts
  //     for the same userId are serialized by the transaction.
  //   • Existing accounts: sha256(identityPubKeyHex) is re-verified inside the same
  //     transaction.  Mismatch → 403. (Kept as cheap defence-in-depth behind the
  //     signature gate — it binds the *presented* key to the one on file; the
  //     signature is what proves the caller actually holds it.)
  //   • Rate limit: one successful mint per userId per 60 s (in-memory).
  //
  // History — why the signature gate exists (do not remove it):
  //   This handler used to accept sha256(identityPubKeyHex) as its only proof of
  //   ownership. identityPubKeyHex is world-readable by design (public_keys/* in
  //   firestore.rules, required for X3DH), so that hash proved nothing and any
  //   authenticated user could mint a Firebase token for any other account —
  //   full takeover without the seed phrase (audit finding S07-C1, Critical).
  //   Note also that a prior revision of SESSION-01.md claimed this was fixed by
  //   a file `server/lib/xed25519.js` that never existed; the real fix is
  //   lib/challengeStore.js (nonce) + lib/identityVerify.js (verification), both
  //   of which do exist and have passing tests you can run.
  //
  if (req.method === "POST" && req.url === "/mintToken") {
    collectBody(req, res, async (body) => {
      // Declared outside the try so the catch below can also release the
      // reserved per-userId cooldown slot (S02-M1). Assigned once the userId is
      // known and the slot has actually been claimed; stays a no-op before that.
      let releaseCooldown = () => {};
      try {
        // ── IP rate limit (checked before parsing body) ──────────────────────
        const clientIp = getClientIp(req);
        if (!checkIpRateLimit(clientIp)) {
          console.warn(`mintToken: IP rate limit hit ip=${ipTag(clientIp)}`);
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        const { userId, identityPubKeyHex, nonce, signatureHex, inviteToken } =
          JSON.parse(body);
        if (!userId || typeof userId !== "string" ||
            !identityPubKeyHex || typeof identityPubKeyHex !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId / identityPubKeyHex");
          return;
        }

        // ── S07-C1: proof-of-possession is MANDATORY ─────────────────────────
        // Shape-checked here, before the cooldown slot below is touched, so a
        // malformed or legacy request cannot perturb rate-limit state for the
        // account. A client build that predates the challenge protocol lands
        // here and gets a clear 400 rather than a silent auth bypass — that is
        // the intended fail-closed behaviour, NOT a bug to "fix" by making
        // these fields optional. Making them optional restores the takeover.
        if (!nonce || typeof nonce !== "string" ||
            !signatureHex || typeof signatureHex !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid nonce / signatureHex — call POST /mintChallenge first");
          return;
        }

        // ── Per-userId cooldown (prevents rapid re-auth from same account) ───
        //
        // S02-M1: this is a two-phase claim, and the ordering matters in both
        // directions.
        //
        // The cooldown must be *reserved* before the first `await` — a purely
        // post-mint write left a window in which two concurrent requests for the
        // same userId both read the old timestamp, both passed, and the 60 s
        // limit was bypassed entirely.
        //
        // But an unconditional pre-auth write turned this rate limit into a
        // denial-of-service primitive against a specific account: `userId` is
        // derived from a seed and is not a secret, so anyone could POST that uid
        // with garbage key material once a minute, forever. Each attempt was
        // rejected 403 yet still stamped the cooldown, so the legitimate owner —
        // who never authenticated successfully �� was held at 429 indefinitely.
        //
        // Resolution: reserve the slot now to close the race, then release it on
        // any failure path (see the `catch` below, and the finally-style release
        // on 403). Only a *successful* mint leaves the stamp in place, so failed
        // guesses cost the attacker nothing they can inflict on the victim.
        const now  = Date.now();
        const last = mintCooldown.get(userId) || 0;
        if (now - last < MINT_COOLDOWN_MS) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests — wait 60 s and retry");
          return;
        }
        mintCooldown.set(userId, now);
        // Restores the previous cooldown state when this attempt does not result
        // in an issued token. Set back to the prior timestamp rather than
        // deleting, so an in-flight legitimate cooldown is never cleared by a
        // concurrent failed attempt.
        releaseCooldown = () => {
          if (last === 0) mintCooldown.delete(userId);
          else            mintCooldown.set(userId, last);
        };

        // ── S07-C1 gate: consume the nonce, then verify the signature ────────
        //
        // Ordering rationale:
        //   • After the cooldown check, so a 429 does not burn a nonce the
        //     legitimate client would need for its retry.
        //   • consume() BEFORE verify(), and unconditionally: the nonce is spent
        //     by the attempt itself, so a captured signature cannot be replayed
        //     and an attacker cannot grind many signature guesses against one
        //     outstanding challenge. A legitimate client that fails here simply
        //     fetches a fresh challenge.
        //   • Both BEFORE the Firestore transaction, so unauthenticated callers
        //     never reach the (billable, lock-reading) transaction at all.
        if (!mintChallengeStore.consume(userId, nonce)) {
          releaseCooldown();
          console.warn(
            `mintToken: no valid outstanding challenge for uid=${uidTag(userId)} ` +
            `(unknown, expired, or already-used nonce) — refusing to mint (S07-C1)`
          );
          // Same 403 string as a key mismatch on purpose: the response must not
          // distinguish "your nonce was stale" from "wrong key" from "locked
          // account", or it becomes an oracle for probing account state.
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Key mismatch");
          return;
        }

        if (!verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex: nonce, signatureHex })) {
          releaseCooldown();
          console.warn(
            `mintToken: identity signature verification FAILED for uid=${uidTag(userId)} ` +
            `— refusing to mint (S07-C1)`
          );
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Key mismatch");
          return;
        }

        const incomingHash = sha256hex(identityPubKeyHex);

        const idRef = db.collection("identities").doc(userId);

        // F2 fix: claim the identities slot atomically before minting the token.
        // We run a Firestore transaction that either:
        //   a) Creates the doc for a new account (first caller wins; second sees it exists
        //      and verifies the hash — preventing concurrent first-claim races), or
        //   b) Verifies the hash for an existing account.
        // Only after the transaction succeeds do we mint the token, so there is no window
        // between "account doesn't exist" and "doc written".
        let isNewAccount = false;
        await db.runTransaction(async (tx) => {
          // ── S06-H1 / S06-C2 part 1: refuse to mint for a locked account ──────
          // The duress latch has to be enforced where the credential is issued.
          // Previously `accountLock` was consulted in exactly one place — a
          // client-side `if` in RestoreFromSeedActivity that runs *after*
          // signInWithCustomToken had already succeeded — so an adversary
          // holding a coerced seed could call this endpoint directly, get a
          // valid token for the victim's uid, and read `backups/{uid}` over the
          // Firestore REST API without ever executing the check.
          //
          // Read inside the transaction (not before it) so a concurrent
          // /duress-lock write cannot interleave between the check and the mint.
          const lockSnap = await tx.get(db.collection("accountLock").doc(userId));
          if (lockSnap.exists && lockSnap.data().locked === true) {
            // Reuse the existing 403 string verbatim: a locked account must stay
            // indistinguishable from a wrong seed or an unapproved invite, which
            // is the deniability property RestoreFromSeedActivity is careful
            // about client-side. Do not add a distinct message or status here.
            throw Object.assign(new Error("Access request not approved"), { status: 403 });
          }

          const snap = await tx.get(idRef);
          if (!snap.exists) {
            // New account — invite-only. Modern invite tokens are stored hash-only
            // and consumed in this same transaction as the identity claim. A narrow
            // legacy branch permits only already-approved 32-hex waitlist IDs while
            // pre-deployment approvals drain; no endpoint creates new legacy IDs.
            const normalizedInvite = invite.normalizeInviteToken(inviteToken);
            if (!normalizedInvite) {
              throw Object.assign(new Error("Valid invite required"), { status: 403 });
            }

            if (invite.isInviteToken(normalizedInvite)) {
              const tokenHash = invite.hashInviteToken(normalizedInvite);
              const inviteQuery = db.collection("invites").where("tokenHash", "==", tokenHash).limit(1);
              const inviteSnap = await tx.get(inviteQuery);
              if (inviteSnap.empty) {
                throw Object.assign(new Error("Valid invite required"), { status: 403 });
              }
              const inviteDoc = inviteSnap.docs[0];
              if (invite.inviteStatus(inviteDoc.data()) !== "active") {
                throw Object.assign(new Error("Valid invite required"), { status: 403 });
              }
              tx.update(inviteDoc.ref, {
                status: "used",
                usedByUserId: userId,
                usedAt: FieldValue.serverTimestamp(),
              });
            } else if (/^[0-9a-f]{32}$/.test(normalizedInvite)) {
              const legacyRef = db.collection("waitlist").doc(normalizedInvite);
              const legacySnap = await tx.get(legacyRef);
              if (!legacySnap.exists || legacySnap.data().status !== "approved") {
                throw Object.assign(new Error("Valid invite required"), { status: 403 });
              }
              tx.update(legacyRef, {
                status: "used",
                usedByUserId: userId,
                usedAt: FieldValue.serverTimestamp(),
              });
            } else {
              throw Object.assign(new Error("Valid invite required"), { status: 403 });
            }

            // First claim — atomically write the identity binding
            tx.set(idRef, {
              uid:                userId,
              identityPubKeyHash: incomingHash,
              createdAt:          FieldValue.serverTimestamp(),
            });
            isNewAccount = true;
          } else {
            // Existing account — re-verify the binding inside the transaction.
            //
            // S07-H1: this check used to read `if (storedHash && storedHash !==
            // incomingHash)`, which failed **open**. An identity document with a
            // missing, empty, or non-string `identityPubKeyHash` — a partially
            // written record, a legacy doc from before the field existed, or one
            // created by any other write path — made the guard vacuous, so the
            // caller was handed a token for that uid without proving anything at
            // all. A record we cannot evaluate must be treated as a failure to
            // prove ownership, never as permission.
            const storedHash = snap.data().identityPubKeyHash;
            if (typeof storedHash !== "string" || storedHash.length !== 64) {
              // Deliberately reuses the "Key mismatch" 403 rather than reporting
              // "unverifiable account": the response must not distinguish a
              // damaged identity record from a wrong key, or it becomes an
              // oracle for probing which uids are in a weak state.
              console.error(
                `mintToken: identity doc for uid=${uidTag(userId)} has no usable ` +
                `identityPubKeyHash — refusing to mint (S07-H1 fail-closed)`
              );
              throw Object.assign(new Error("Key mismatch"), { status: 403 });
            }
            if (!timingSafeEqualHex(storedHash, incomingHash)) {
              throw Object.assign(new Error("Key mismatch"), { status: 403 });
            }
          }
        });

        if (isNewAccount) {
          // Never attach the bearer token or its hash to audit records.
          auditAdminEvent("invite_redeemed", req, { userIdTag: uidTag(userId) });
        }

        // Mint custom token — uid = userId (permanent, seed-derived)
        // Token is minted only after the atomic identity-claim succeeds.
        const token = await admin.auth().createCustomToken(userId);

        console.log(`mintToken: issued token for userId=${uidTag(userId)} newAccount=${isNewAccount}`);

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token }));
      } catch (e) {
        // S02-M1: no token was issued on any path that lands here, so the
        // reserved cooldown slot must not be left burnt against the account. A
        // rejected attempt — wrong key, unapproved invite, locked account, or an
        // internal fault — must not be able to hold the legitimate owner at 429.
        releaseCooldown();
        if (e.status === 403) {
          // Thrown from inside the Firestore transaction: either a key mismatch
          // (F2 fix) or a missing/unapproved waitlist request for a new account.
          let attemptedUid = "none";
          try { attemptedUid = uidTag(JSON.parse(body || "{}").userId); } catch { /* unparsable body */ }
          console.warn(`mintToken: 403 (${e.message}) for userId=${attemptedUid}`);
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end(e.message);
        } else {
          console.error("mintToken error:", e.message);
          res.writeHead(500, { "Content-Type": "text/plain" });
          res.end("Internal server error");
        }
      }
    });
    return;
  }

  // ── POST /invite/validate ───────────────────────────────────────────────────
  // Generic preflight for the Android onboarding screen. Every invalid state
  // (unknown, malformed, expired, revoked, or used) returns the same payload so
  // this endpoint cannot be used as a lifecycle/state oracle.
  if (req.method === "POST" && req.url === "/invite/validate") {
    (async () => {
      try {
        if (!checkWaitlistPollRateLimit(getClientIp(req))) {
          res.writeHead(429, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ valid: false }));
          return;
        }
        const body = await readBody(req, res);
        let token = "";
        try { token = invite.normalizeInviteToken(JSON.parse(body).inviteToken); } catch { /* generic invalid */ }
        let valid = false;
        if (invite.isInviteToken(token)) {
          const snap = await db.collection("invites")
            .where("tokenHash", "==", invite.hashInviteToken(token))
            .limit(1)
            .get();
          valid = !snap.empty && invite.inviteStatus(snap.docs[0].data()) === "active";
        } else if (/^[0-9a-f]{32}$/.test(token)) {
          // Bounded migration support for previously approved waitlist IDs.
          const legacy = await db.collection("waitlist").doc(token).get();
          valid = legacy.exists && legacy.data().status === "approved";
        }
        res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ valid }));
      } catch (e) {
        console.error("invite/validate error:", e.message);
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ valid: false }));
      }
    })();
    return;
  }

  // ── POST /migrateUid ──────────────────────��──────────────────────────────────
  //
  // Body (JSON): { userId, oldUid }
  // Auth: Firebase ID token in Authorization: Bearer <token> header.
  //
  // Called during account restore when a user's old anonymous Firebase UID
  // differs from their permanent seed-derived userId.  Uses Admin SDK
  // (bypasses Firestore client rules) to:
  //   1. Copy users/{oldUid}  → users/{newUid}  (FCM token, display name, etc.)
  //   2. Copy backups/{oldUid} and its direct subcollections → new UID
  //   3. Rewrite chat participants: replace oldUid with newUid
  //   4. Rewrite group members:    replace oldUid with newUid
  //   5. Mark identities/{userId} as migrated only after all required work succeeds
  //
  // Security model:
  //   • Verifies the Firebase ID token (auth.uid must equal userId).
  //   • Confirms identities/{userId} exists and its stored uid matches oldUid.
  //   • Rate-limited: one call per userId per 60 s.
  //
  if (req.method === "POST" && req.url === "/migrateUid") {
    collectBody(req, res, async (body) => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }

        let decodedToken;
        try {
          decodedToken = await admin.auth().verifyIdToken(idToken);
        } catch (authErr) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        if (!checkAuthRateLimit(decodedToken.uid, "migrateUid")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded — slow down and retry");
          return;
        }

        const { userId, oldUid } = JSON.parse(body);
        if (!userId || !oldUid || typeof userId !== "string" || typeof oldUid !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId / oldUid");
          return;
        }

        // Caller's auth UID must equal the userId they claim to own
        if (decodedToken.uid !== userId) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Token UID does not match userId");
          return;
        }

        if (userId === oldUid) {
          // Nothing to migrate
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ migrated: false, reason: "same-uid" }));
          return;
        }

        // ── S06-H1: refuse migration for a locked account ────────────────────
        // This endpoint copies backup documents (`backupDocsCopied` below), so
        // leaving it ungated would let a token minted before the lock landed
        // relocate a duressed account's data to a uid the latch does not cover.
        // Check both sides: the destination uid and the source uid.
        if (await isAccountLocked(userId) || await isAccountLocked(oldUid)) {
          console.warn(`migrateUid: refused for locked account userId=${uidTag(userId)}`);
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Access request not approved");
          return;
        }

        // Verify identities/{userId} exists and its uid == oldUid
        const idDoc = await db.collection("identities").doc(userId).get();
        if (!idDoc.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("Identity not found");
          return;
        }
        const storedUid = idDoc.data().uid;

        // Case 1: already migrated — idempotent no-op, do NOT process caller-supplied oldUid.
        if (storedUid === userId) {
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ migrated: false, reason: "already-migrated" }));
          return;
        }

        // Case 2: the stored UID doesn't match the claimed oldUid — reject.
        // This prevents an authenticated user from rewriting another account's data.
        if (storedUid !== oldUid) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("oldUid does not match identity record");
          return;
        }

        // Only reaches here when storedUid === oldUid — legitimate first-time migration.

        const results = {
          chatsMigrated: 0,
          groupsMigrated: 0,
          userDocCopied: false,
          backupDocsCopied: 0,
        };

        // 1. Copy users/{oldUid} → users/{userId}. The old document is not
        // deleted: a failed later step must leave the migration retryable without
        // destroying the legacy account's visible profile.
        //
        // S02-H1: this used to `.set(data)` with the raw document verbatim. That
        // document is client-writable (via the `users/{uid}` create/update rule)
        // and this migration runs with the Admin SDK, which bypasses Firestore
        // rules entirely — so any field an attacker managed to stash on
        // `users/{oldUid}` (through a bug in the rule, a legacy doc predating a
        // rule tightening, or a field the rule allows but this endpoint should
        // never trust blindly) would be replayed onto `users/{userId}` with
        // elevated (rule-bypassing) privilege. Copy only the same allow-listed,
        // type/size-bounded fields the `users/{uid}` write rule itself permits
        // (`firestore.rules` create/update block, mirrored in
        // `lib/profileSanitize.js` so the decision is unit-tested) — never the
        // whole document.
        const oldUserSnap = await db.collection("users").doc(oldUid).get();
        if (oldUserSnap.exists) {
          const data = oldUserSnap.data();
          if (data) {
            const safeData = sanitizeMigratedUserFields(data);
            await db.collection("users").doc(userId).set({
              ...safeData,
              updatedAt: FieldValue.serverTimestamp(),
            });
            results.userDocCopied = true;
          }
        }

        // 2. Copy all backup content. Restore reads under the deterministic UID, so
        // missing this step makes an otherwise valid recovery phrase appear empty.
        const oldBackupRef = db.collection("backups").doc(oldUid);
        const newBackupRef = db.collection("backups").doc(userId);
        const oldBackupSnap = await oldBackupRef.get();
        if (oldBackupSnap.exists) {
          await newBackupRef.set(oldBackupSnap.data(), { merge: true });
          results.backupDocsCopied++;
        }
        for (const subcollection of ["messages", "contacts", "groups"]) {
          const snap = await oldBackupRef.collection(subcollection).get();
          for (const doc of snap.docs) {
            await newBackupRef.collection(subcollection).doc(doc.id).set(doc.data());
            results.backupDocsCopied++;
          }
        }

        // 3. Rewrite chat participants arrays.
        //
        // The swap MUST be atomic. The previous implementation issued arrayRemove(oldUid)
        // and arrayUnion(userId) as two separate updates; a crash, timeout, or partial
        // failure between them left the user removed from the chat but never re-added —
        // silently dropping them from the conversation. Firestore also forbids applying
        // arrayRemove and arrayUnion to the same field in one update, so we instead read
        // the current membership inside a transaction, compute the swapped array in
        // memory, and write it in a single atomic update. This is also idempotent: a
        // retry after oldUid is already gone is a no-op.
        const chatsSnap = await db.collection("chats")
          .where("participants", "array-contains", oldUid).get();
        for (const chatDoc of chatsSnap.docs) {
          await db.runTransaction(async (txn) => {
            const snap = await txn.get(chatDoc.ref);
            if (!snap.exists) return;
            const current = Array.isArray(snap.get("participants")) ? snap.get("participants") : [];
            if (!current.includes(oldUid)) return; // already migrated by an earlier run
            const next = Array.from(new Set(current.filter((u) => u !== oldUid).concat(userId)));
            txn.update(chatDoc.ref, { participants: next });
          });
          results.chatsMigrated++;
        }

        // 4. Rewrite group members arrays — same atomic read-swap-write as chats above.
        const groupsSnap = await db.collection("groups")
          .where("members", "array-contains", oldUid).get();
        for (const groupDoc of groupsSnap.docs) {
          await db.runTransaction(async (txn) => {
            const snap = await txn.get(groupDoc.ref);
            if (!snap.exists) return;
            const current = Array.isArray(snap.get("members")) ? snap.get("members") : [];
            if (!current.includes(oldUid)) return; // already migrated by an earlier run
            const next = Array.from(new Set(current.filter((u) => u !== oldUid).concat(userId)));
            txn.update(groupDoc.ref, { members: next });
          });
          results.groupsMigrated++;
        }

        // The identity UID is the migration completion marker. It is intentionally
        // written last so a retry remains authorized after any failed copy/patch.
        await idDoc.ref.update({
          uid: userId,
          migratedAt: FieldValue.serverTimestamp(),
        });

        // Clean up only after the completion marker is written. Backups are copied
        // rather than moved so an interrupted cleanup can never hide history from
        // a restore retry.
        if (oldUserSnap.exists) {
          await db.collection("users").doc(oldUid).delete();
        }

        console.log(
          `migrateUid: userId=${uidTag(userId)} oldUid=${uidTag(oldUid)} chats=${results.chatsMigrated} `
          + `groups=${results.groupsMigrated} backupDocs=${results.backupDocsCopied}`
        );

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ migrated: true, ...results }));
      } catch (e) {
        console.error("migrateUid error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── POST /acknowledgeRotation (S06-M6) ──────────────────────────────────────
  //
  // Body (JSON): { userId }
  // Auth: Firebase ID token in Authorization: Bearer <token> header.
  //
  // Clears accountLock/{userId}.rotationRequired once the client has completed
  // both forced-rotation screens (new primary PIN, then a fresh secondary/duress
  // code re-arming slot B) — see SESSION-06-DURESS.md §8 and
  // ForcedPrimaryPinRotationActivity / ForcedDuressRotationActivity on the client.
  //
  // Must go through the Admin SDK: firestore.rules denies client updates to
  // accountLock outright (`allow update: if false`), so the flag can only ever be
  // cleared server-side, the same reason /migrateUid exists for chat/group writes
  // the client's own rules don't permit it to make itself.
  //
  // Security model:
  //   • Verifies the Firebase ID token (auth.uid must equal userId).
  //   • Idempotent: already-cleared or never-set is a 200 no-op, not an error —
  //     a retry after a successful-but-unconfirmed first call must not fail.
  //   • Refuses (403) if the account is locked again at ack time. A stale client
  //     call must never be able to lift a lock that became real again since the
  //     unfreeze that originally triggered this rotation.
  //   • Rate-limited: a few calls per userId per 60 s (see AUTH_RATE_LIMITS).
  //
  if (req.method === "POST" && req.url === "/acknowledgeRotation") {
    collectBody(req, res, async (body) => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }

        let decodedToken;
        try {
          decodedToken = await admin.auth().verifyIdToken(idToken);
        } catch (authErr) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        if (!checkAuthRateLimit(decodedToken.uid, "acknowledgeRotation")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded — slow down and retry");
          return;
        }

        const { userId } = JSON.parse(body);
        if (!userId || typeof userId !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId");
          return;
        }

        // Caller's auth UID must equal the userId they claim to own — a stale or
        // stolen token for a different account must not be able to clear this
        // account's rotation flag.
        if (decodedToken.uid !== userId) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Token UID does not match userId");
          return;
        }

        const lockRef = db.collection("accountLock").doc(userId);
        // Branch logic (no-lock / relocked / not-due / due) lives in
        // pure.decideRotationAcknowledgement, tested in
        // server/lib/pure.test.js — this transaction only owns the two real
        // side effects: the read and, conditionally, the write.
        const result = await db.runTransaction(async (txn) => {
          const snap = await txn.get(lockRef);
          const decision = pure.decideRotationAcknowledgement(
            snap.exists,
            snap.exists ? snap.data() : null
          );
          if (decision.shouldClearFlag) {
            txn.update(lockRef, {
              rotationRequired: false,
              rotationAcknowledgedAt: FieldValue.serverTimestamp(),
            });
          }
          return decision;
        });

        if (result.relocked) {
          console.warn(`acknowledgeRotation: refused, account re-locked userId=${uidTag(userId)}`);
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Access request not approved");
          return;
        }

        console.log(
          `acknowledgeRotation: userId=${uidTag(userId)} acknowledged=${result.acknowledged} `
          + `reason=${result.reason || "n/a"}`
        );
        res.writeHead(200, { "Content-Type": "application/json" });
        // Reproduce the endpoint's original wire shape exactly: `reason` is
        // present only on the non-success branches, `relocked` and
        // `shouldClearFlag` are internal-only and were never serialized.
        res.end(JSON.stringify(
          result.reason !== undefined
            ? { acknowledged: result.acknowledged, reason: result.reason }
            : { acknowledged: result.acknowledged }
        ));
      } catch (e) {
        console.error("acknowledgeRotation error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── POST /createChat ──────────────────────────────────────────────────────────
  //
  // Body (JSON): { myUid, partnerUid, myDisplayName, partnerDisplayName }
  // Auth: Firebase ID token in Authorization: Bearer <token> header.
  //
  // Security model:
  //   �� Verifies the token with Firebase Admin SDK (auth.uid must equal myUid).
  //   • Verifies both UIDs exist in identities/{uid} (registered DuoShield accounts).
  //   • Uses set({ merge: true }) so both sides can call this independently and the
  //     result is idempotent (both writes converge on the same chatId doc).
  //   • chatId = SHA-256(lex-smaller uid + "/" + lex-larger uid) �� same logic as client.
  //   • Admin SDK bypasses Firestore client rules; the client-side create rule is
  //     set to deny, so only this server path can create chat docs (F6 fix).
  //
  if (req.method === "POST" && req.url === "/createChat") {
    collectBody(req, res, async (body) => {
      try {
        // Verify Firebase ID token from Authorization header
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }

        let decodedToken;
        try {
          decodedToken = await admin.auth().verifyIdToken(idToken);
        } catch (authErr) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        if (!checkAuthRateLimit(decodedToken.uid, "createChat")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded — slow down and retry");
          return;
        }

        const { myUid, partnerUid, myDisplayName, partnerDisplayName } = JSON.parse(body);
        if (!myUid || !partnerUid || typeof myUid !== "string" || typeof partnerUid !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid myUid / partnerUid");
          return;
        }

        // Confirm the authenticated user is who they claim to be
        if (decodedToken.uid !== myUid) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Token UID does not match myUid");
          return;
        }

        // Verify both accounts exist in identities collection
        const [myIdDoc, partnerIdDoc] = await Promise.all([
          db.collection("identities").doc(myUid).get(),
          db.collection("identities").doc(partnerUid).get(),
        ]);
        if (!myIdDoc.exists) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Caller not registered");
          return;
        }
        if (!partnerIdDoc.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("Partner not found");
          return;
        }

        // Compute deterministic chatId (matches ContactManager.buildChatId on Android)
        const sorted = [myUid, partnerUid].sort();
        const chatIdInput = sorted[0] + "/" + sorted[1];
        const chatId = require("crypto").createHash("sha256").update(chatIdInput).digest("hex");

        // Write/merge chat doc (idempotent — same as before, but now through server only)
        //
        // S02-L2: `myDisplayName`/`partnerDisplayName` come straight from the
        // request body with no type check or length bound before this fix, so
        // any authenticated caller could stash an arbitrarily large value (or a
        // non-string, which Firestore would still happily store) on a doc the
        // other participant reads — unbounded storage growth and a content-
        // injection surface into the partner's chat list UI. `isValidDisplayName`
        // (unit-tested in `lib/profileSanitize.js`) bounds it to a plain,
        // size-capped string, matching the `users/{uid}.displayName` allow-list
        // bound already enforced in `firestore.rules`.
        const chatDocData = {
          participants: [myUid, partnerUid],
        };
        if (isValidDisplayName(myDisplayName)) {
          chatDocData["partnerName_" + partnerUid] = myDisplayName;
        }
        if (isValidDisplayName(partnerDisplayName)) {
          chatDocData["partnerName_" + myUid] = partnerDisplayName;
        }

        await db.collection("chats").doc(chatId).set(chatDocData, { merge: true });
        console.log(`createChat: chatId=${chatId} participants=[${uidTag(myUid)},${uidTag(partnerUid)}]`);

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ chatId }));
      } catch (e) {
        console.error("createChat error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── POST /mediaToken — scoped capability token for the storage Worker ───────
  //
  // SEC-A01. Previously the Android app shipped WORKER_SECRET inside the APK and
  // sent it as a static `Authorization: Bearer` on every Worker request. That
  // single value was:
  //   • extractable from any installed APK in minutes, and
  //   • an *authentication* credential with no *authorization* attached —
  //     it proved "some copy of the app" was calling, never "this user may
  //     touch this object". Anyone holding it could overwrite or DELETE any
  //     other user's media given its object key, and the key travels through
  //     Firestore chat documents, so it is not a secret in any strong sense.
  //
  // The app now exchanges its Firebase ID token for a token scoped to exactly
  // one (object key, operation) pair with a short expiry. The signing secret
  // lives only on this server and in the Worker — never in the APK — so
  // decompiling the client yields nothing reusable, and a leaked token is
  // useless beyond one object, one verb, and a few minutes.
  //
  // Body (JSON): { key, op }  op ∈ read | write | delete
  // Response:    { token, expiresAt }
  if (req.method === "POST" && req.url === "/mediaToken") {
    collectBody(req, res, async (body) => {
      try {
        if (!MEDIA_TOKEN_SECRET) {
          // Fail closed: without the shared secret we cannot mint anything the
          // Worker would trust, and silently falling back to the old static
          // secret is what this change exists to remove.
          console.error("mediaToken: MEDIA_TOKEN_SECRET is not configured");
          res.writeHead(503, { "Content-Type": "text/plain" });
          res.end("Media tokens unavailable");
          return;
        }

        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }

        let uid;
        try {
          uid = (await admin.auth().verifyIdToken(idToken)).uid;
        } catch {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        if (!checkAuthRateLimit(uid, "mediaToken")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded — slow down and retry");
          return;
        }

        let parsed;
        try { parsed = JSON.parse(body || "{}"); }
        catch {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Malformed JSON body");
          return;
        }

        const key = typeof parsed.key === "string" ? parsed.key : "";
        const op  = typeof parsed.op  === "string" ? parsed.op  : "";

        if (!MEDIA_OPS.has(op)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid op");
          return;
        }
        // Same allow-list the Worker enforces. Validating here too means a
        // malformed key never even gets a signature.
        if (!isAllowedMediaKey(key)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid key format");
          return;
        }

        // ── Authorization ───────────────────────────────────────────────────
        // Two key families, two different questions.
        if (isAvatarKey(key)) {
          // Profile photos are owned by the UID embedded in the key, not scoped
          // to a conversation — running them through callerMayAccessScope would
          // look up chats/<uid>, find nothing, and deny the photo's own owner.
          const decision = decideAvatarAccess({ uid, key, op });
          if (!decision.allowed) {
            console.warn(
              `mediaToken: avatar denied uid=${uidTag(uid)} op=${op} reason=${decision.reason}`
            );
            res.writeHead(403, { "Content-Type": "text/plain" });
            res.end("Not allowed for this profile photo");
            return;
          }
        } else {
          // Key shape is <media|voice>/<chatId|groupId>/<uuid>.<ext>, so the
          // middle segment names the conversation the object belongs to.
          const scopeId = key.split("/")[1];
          const allowed = await callerMayAccessScope(uid, scopeId);
          if (!allowed) {
            console.warn(`mediaToken: denied uid=${uidTag(uid)} scope=${scopeId} op=${op}`);
            res.writeHead(403, { "Content-Type": "text/plain" });
            res.end("Not a participant of this conversation");
            return;
          }
        }

        const expiresAt = Date.now() + MEDIA_TOKEN_TTL_MS;
        const token     = signMediaToken({ op, key, uid, expiresAt });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token, expiresAt }));
      } catch (e) {
        sendServerError(res, "mediaToken", e);
      }
    });
    return;
  }

  // ── Signal Protocol public pre-key API ────────────────────────────────────
  //
  // The public key bundle is readable by authenticated peers, but every mutation
  // is performed here with the Admin SDK. In particular, callers never submit a
  // replacement oneTimePreKeys array: /signal/claimPreKey atomically reserves and
  // removes one server-selected entry in a Firestore transaction.
  const signalPrekeys = require("./lib/signalPrekeys");

  if (req.method === "POST" && req.url === "/signal/registerBundle") {
    collectBody(req, res, async (body) => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }
        let uid;
        try { uid = (await admin.auth().verifyIdToken(idToken, true)).uid; }
        catch {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid, expired, or revoked token");
          return;
        }
        if (!checkAuthRateLimit(uid, "signalRegisterBundle")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded");
          return;
        }
        if (await isAccountLocked(uid)) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Access request not approved");
          return;
        }
        let parsed;
        try { parsed = JSON.parse(body || "{}"); }
        catch {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Malformed JSON body");
          return;
        }
        const bundle = parsed.bundle;
        if (!signalPrekeys.validInitialBundle(bundle)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid Signal public key bundle");
          return;
        }
        const ref = db.collection("users").doc(uid).collection("public_keys").doc("bundle");
        await ref.set({ ...bundle, updatedAt: FieldValue.serverTimestamp() });
        res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ uploaded: true }));
      } catch (e) {
        sendServerError(res, "signal/registerBundle", e);
      }
    });
    return;
  }

  if (req.method === "POST" && req.url === "/signal/appendPreKeys") {
    collectBody(req, res, async (body) => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }
        let uid;
        try { uid = (await admin.auth().verifyIdToken(idToken, true)).uid; }
        catch {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid, expired, or revoked token");
          return;
        }
        if (!checkAuthRateLimit(uid, "signalAppendPreKeys")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded");
          return;
        }
        if (await isAccountLocked(uid)) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Access request not approved");
          return;
        }
        let parsed;
        try { parsed = JSON.parse(body || "{}"); }
        catch {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Malformed JSON body");
          return;
        }
        const additions = parsed.oneTimePreKeys;
        if (!signalPrekeys.validPreKeyList(additions, 25)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid one-time pre-keys");
          return;
        }
        const ref = db.collection("users").doc(uid).collection("public_keys").doc("bundle");
        const outcome = await db.runTransaction(async (tx) => {
          const snap = await tx.get(ref);
          if (!snap.exists) throw Object.assign(new Error("Signal bundle not found"), { status: 404 });
          const current = snap.data();
          const merged = signalPrekeys.appendPreKeys(current.oneTimePreKeys || [], additions);
          if (merged.length === (current.oneTimePreKeys || []).length) return "unchanged";
          tx.update(ref, {
            oneTimePreKeys: merged,
            updatedAt: FieldValue.serverTimestamp(),
          });
          return "updated";
        });
        res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ appended: additions.length, outcome }));
      } catch (e) {
        if (e.status) {
          res.writeHead(e.status, { "Content-Type": "text/plain" });
          res.end(e.message);
        } else sendServerError(res, "signal/appendPreKeys", e);
      }
    });
    return;
  }

  if (req.method === "POST" && req.url === "/signal/updatePreKey") {
    collectBody(req, res, async (body) => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }
        let uid;
        try { uid = (await admin.auth().verifyIdToken(idToken, true)).uid; }
        catch {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid, expired, or revoked token");
          return;
        }
        if (!checkAuthRateLimit(uid, "signalUpdatePreKey")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded");
          return;
        }
        if (await isAccountLocked(uid)) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Access request not approved");
          return;
        }
        let parsed;
        try { parsed = JSON.parse(body || "{}"); }
        catch {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Malformed JSON body");
          return;
        }
        const field = parsed.field;
        const value = parsed.value;
        if (!["signedPreKey", "kyberPreKey"].includes(field)
            || (field === "signedPreKey" && !signalPrekeys.validSignedPreKey(value))
            || (field === "kyberPreKey" && !signalPrekeys.validKyberPreKey(value))) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid pre-key update");
          return;
        }
        const ref = db.collection("users").doc(uid).collection("public_keys").doc("bundle");
        const outcome = await db.runTransaction(async (tx) => {
          const snap = await tx.get(ref);
          if (!snap.exists) throw Object.assign(new Error("Signal bundle not found"), { status: 404 });
          const current = snap.data()[field];
          const same = field === "signedPreKey"
            ? signalPrekeys.sameSignedPreKey(current, value)
            : signalPrekeys.sameKyberPreKey(current, value);
          if (same) return "unchanged";
          tx.update(ref, { [field]: value, updatedAt: FieldValue.serverTimestamp() });
          return "updated";
        });
        res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ updated: field, outcome }));
      } catch (e) {
        if (e.status) {
          res.writeHead(e.status, { "Content-Type": "text/plain" });
          res.end(e.message);
        } else sendServerError(res, "signal/updatePreKey", e);
      }
    });
    return;
  }

  if (req.method === "POST" && req.url === "/signal/claimPreKey") {
    collectBody(req, res, async (body) => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }
        let callerUid;
        try { callerUid = (await admin.auth().verifyIdToken(idToken, true)).uid; }
        catch {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid, expired, or revoked token");
          return;
        }
        if (!checkAuthRateLimit(callerUid, "signalClaimPreKey")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded");
          return;
        }
        if (await isAccountLocked(callerUid)) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Access request not approved");
          return;
        }
        let parsed;
        try { parsed = JSON.parse(body || "{}"); }
        catch {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Malformed JSON body");
          return;
        }
        const recipientUid = typeof parsed.recipientUid === "string" ? parsed.recipientUid : "";
        if (!recipientUid || recipientUid === callerUid || recipientUid.length > 128) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid recipient UID");
          return;
        }
        if (await isAccountLocked(recipientUid)) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Access request not approved");
          return;
        }
        const chatId = crypto.createHash("sha256")
          .update([callerUid, recipientUid].sort().join("/"))
          .digest("hex");
        const chatRef = db.collection("chats").doc(chatId);
        const bundleRef = db.collection("users").doc(recipientUid)
          .collection("public_keys").doc("bundle");
        const result = await db.runTransaction(async (tx) => {
          const [chatSnap, bundleSnap] = await Promise.all([
            tx.get(chatRef), tx.get(bundleRef),
          ]);
          const participants = chatSnap.exists ? chatSnap.data().participants : null;
          if (!Array.isArray(participants)
              || participants.length !== 2
              || !participants.includes(callerUid)
              || !participants.includes(recipientUid)) {
            throw Object.assign(new Error("Not a participant of this conversation"), { status: 403 });
          }
          if (!bundleSnap.exists) {
            throw Object.assign(new Error("Signal bundle not found"), { status: 404 });
          }
          const source = bundleSnap.data();
          const { remaining, response } = signalPrekeys.claimOneTimePreKey(source);
          if (response.oneTimePreKeys.length) {
            tx.update(bundleRef, {
              oneTimePreKeys: remaining.oneTimePreKeys,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
          return response;
        });
        res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ bundle: result }));
      } catch (e) {
        if (e.status) {
          res.writeHead(e.status, { "Content-Type": "text/plain" });
          res.end(e.message);
        } else sendServerError(res, "signal/claimPreKey", e);
      }
    });
    return;
  }

  // ── POST /turnCredentials — returns fresh Cloudflare TURN credentials ───────
  //
  // Requires a valid Firebase ID token in the Authorization header.
  // Calls Cloudflare's generate-credentials API server-side so that
  // TURN_TOKEN_ID and TURN_API_TOKEN never leave the server.
  //
  if (req.method === "POST" && req.url === "/turnCredentials") {
    // collectBody enforces MAX_BODY_BYTES on the drained (unused) body — the
    // raw req.on("data")/req.on("end") pattern skips the size guard entirely.
    collectBody(req, res, async () => {
      try {
        // ── Auth ��─────────────────────────────────���─────────────────────────
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }
        let turnUid;
        try {
          turnUid = (await admin.auth().verifyIdToken(idToken)).uid;
        } catch (authErr) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        if (!checkAuthRateLimit(turnUid, "turnCredentials")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded — slow down and retry");
          return;
        }

        // ── S04-M2: per-account daily aggregate cap ───────────────────────────
        // Layered on top of the per-minute limiter above — see
        // checkTurnDailyCap's doc comment for why the per-minute bucket alone
        // (~28,800 mints/day) was not enough.
        if (!checkTurnDailyCap(turnUid)) {
          console.warn(`[turnCredentials] daily cap hit uid=${uidTag(turnUid)}`);
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Daily TURN credential limit exceeded");
          return;
        }

        // ── Cloudflare credentials ─────────��─────────────────────────────────
        const tokenId  = process.env.TURN_TOKEN_ID  || "";
        const apiToken = process.env.TURN_API_TOKEN || "";
        if (!tokenId || !apiToken) {
          console.error("turnCredentials: TURN_TOKEN_ID or TURN_API_TOKEN not set");
          res.writeHead(503, { "Content-Type": "text/plain" });
          res.end("TURN not configured on server");
          return;
        }

        // ── S04-M2: TTL clamped, never the old 24h default ────────────────────
        // See clampTurnCredentialTtlSeconds's doc comment: the ceiling (1h)
        // matches what the Android client already assumes
        // (TurnCredentialCache.TTL_MS), so this changes nothing about how long
        // a credential stays USEFUL to the app, only how long a stolen one
        // stays valid to anyone else.
        const ttlSeconds = pure.clampTurnCredentialTtlSeconds(
          process.env.TURN_CRED_TTL_SECONDS,
          60,    // floor: short enough to matter, long enough not to break slow ICE negotiation
          3600,  // ceiling: matches the client's own 1h refresh assumption
        );

        // ── S04-M2: outbound timeout ───────────────────────────────────────────
        // Node's fetch has no default timeout. Without this, a stalling
        // Cloudflare connection holds the inbound socket (and this outbound
        // one) open indefinitely — 20/min/account of deliberately-stalled
        // upstreams is an unbounded resource leak. Mirrors /linkPreview's
        // existing AbortController pattern (timeoutMs: 6000 there).
        const cfCtrl = new AbortController();
        const cfTimeout = setTimeout(() => cfCtrl.abort(), 8000);
        let cfRes;
        try {
          const cfUrl = `https://rtc.live.cloudflare.com/v1/turn/keys/${tokenId}/credentials/generate`;
          cfRes = await fetch(cfUrl, {
            method: "POST",
            headers: {
              "Authorization": `Bearer ${apiToken}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({ ttl: ttlSeconds }),
            signal: cfCtrl.signal,
          });
        } catch (fetchErr) {
          const timedOut = fetchErr.name === "AbortError";
          console.error(
            `turnCredentials: Cloudflare fetch ${timedOut ? "timed out" : "failed"}:`,
            fetchErr.message
          );
          res.writeHead(504, { "Content-Type": "text/plain" });
          res.end(timedOut ? "Cloudflare TURN request timed out" : "Cloudflare TURN error");
          return;
        } finally {
          clearTimeout(cfTimeout);
        }

        if (!cfRes.ok) {
          const text = await cfRes.text().catch(() => "");
          console.error(`turnCredentials: Cloudflare returned ${cfRes.status}: ${text}`);
          res.writeHead(502, { "Content-Type": "text/plain" });
          res.end("Cloudflare TURN error");
          return;
        }

        const data = await cfRes.json();
        // ── S04-M2: validate shape before responding 200 ──────────────────────
        // A 200 with an unexpected/missing iceServers shape used to send
        // JSON.stringify(undefined) — an empty 200 body the client cannot
        // distinguish from a valid response. Fail loudly (502) instead of
        // silently handing the caller nothing.
        if (!data || !data.iceServers || typeof data.iceServers !== "object") {
          console.error("turnCredentials: Cloudflare response missing iceServers:", JSON.stringify(data));
          res.writeHead(502, { "Content-Type": "text/plain" });
          res.end("Cloudflare TURN error");
          return;
        }
        // data.iceServers = { urls: [...], username: "...", credential: "..." }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(data.iceServers));
      } catch (e) {
        console.error("turnCredentials error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // Strip query string once for all route checks below
  const parsedUrl = (req.url || "/").split("?")[0];

  // ── GET /health — minimal 200 for UptimeRobot / load-balancer probes ────────
  if (parsedUrl === "/health") {
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("OK");
    return;
  }

  // ── GET /status — machine-readable JSON stats ────────────────────────────────
  if (parsedUrl === "/status") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      status: "running",
      startedAt: stats.startedAt,
      uptime: Math.floor(process.uptime()) + "s",
      delivered: stats.delivered,
      groupDelivered: stats.groupDelivered,
      skippedMissingToken: stats.skippedMissingToken,
      skippedOld: stats.skippedOld,
      failed: stats.failed,
    }));
    return;
  }

  // ── GET / — live HTML dashboard ──────────���────────────────────────────────────
  if ((req.method === "GET" || req.method === "HEAD") && (parsedUrl === "/" || parsedUrl === "")) {
    const uptime  = Math.floor(process.uptime());
    const hours   = Math.floor(uptime / 3600);
    const minutes = Math.floor((uptime % 3600) / 60);
    const seconds = uptime % 60;
    const uptimeStr = `${hours}h ${minutes}m ${seconds}s`;
    const total = stats.delivered + stats.groupDelivered;
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width,initial-scale=1"/>
  <meta http-equiv="refresh" content="30"/>
  <title>DuoFat Push Server</title>
  <style>
    *{box-sizing:border-box;margin:0;padding:0}
    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
         background:#0a0e1a;color:#e2e8f0;min-height:100vh;
         display:flex;flex-direction:column;align-items:center;
         justify-content:center;padding:24px}
    h1{font-size:1.6rem;font-weight:700;color:#00c9e0;margin-bottom:4px}
    .sub{font-size:.85rem;color:#64748b;margin-bottom:36px}
    .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));
          gap:16px;width:100%;max-width:720px}
    .card{background:#0f1620;border:1px solid #1e293b;border-radius:12px;
          padding:20px 24px}
    .card .label{font-size:.75rem;color:#64748b;text-transform:uppercase;
                 letter-spacing:.08em;margin-bottom:6px}
    .card .value{font-size:2rem;font-weight:700;color:#f8fafc}
    .card .value.green{color:#22c55e}
    .card .value.red{color:#ef4444}
    .card .value.cyan{color:#00c9e0}
    .dot{display:inline-block;width:8px;height:8px;border-radius:50%;
         background:#22c55e;margin-right:6px;animation:pulse 2s infinite}
    @keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
    .footer{margin-top:28px;font-size:.75rem;color:#334155}
    a{color:#00c9e0;text-decoration:none}
  </style>
</head>
<body>
  <h1><span class="dot"></span>DuoFat Push Server</h1>
  <p class="sub">Started ${stats.startedAt} &nbsp;·&nbsp; Auto-refreshes every 30 s</p>
  <div class="grid">
    <div class="card">
      <div class="label">Uptime</div>
      <div class="value cyan">${uptimeStr}</div>
    </div>
    <div class="card">
      <div class="label">1-to-1 Delivered</div>
      <div class="value green">${stats.delivered}</div>
    </div>
    <div class="card">
      <div class="label">Group Delivered</div>
      <div class="value green">${stats.groupDelivered}</div>
    </div>
    <div class="card">
      <div class="label">Total Sent</div>
      <div class="value">${total}</div>
    </div>
    <div class="card">
      <div class="label">No Token (skipped)</div>
      <div class="value">${stats.skippedMissingToken}</div>
    </div>
    <div class="card">
      <div class="label">Too Old (skipped)</div>
      <div class="value">${stats.skippedOld}</div>
    </div>
    <div class="card">
      <div class="label">Failed</div>
      <div class="value ${stats.failed > 0 ? "red" : ""}">${stats.failed}</div>
    </div>
  </div>
  <p class="footer">
    JSON: <a href="/status">/status</a> &nbsp;·&nbsp;
    Health: <a href="/health">/health</a>
  </p>
</body>
</html>`;
    res.writeHead(200, {
      "Content-Type": "text/html; charset=utf-8",
      "Content-Security-Policy": CSP_DASHBOARD,
    });
    res.end(html);
    return;
  }

  // ── /linkPreviewImage — signed image proxy (S04-H3, client half S08-H4) ────
  // Serves an og:image on behalf of the client so the RECIPIENT's device never
  // contacts the linked host (which would hand the attacker an IP address and a
  // covert read receipt). Authorised by the HMAC in the URL rather than a bearer
  // token, because Glide cannot attach one — see lib/imageProxy.js for why that
  // is safe: the signature binds the exact target, so this is not an open proxy.
  if (req.method === "GET" && req.url.startsWith(`${imageProxy.PROXY_PATH}?`)) {
    (async () => {
      try {
        const query = new URL(req.url, "http://localhost").searchParams;
        const verdict = imageProxy.verifyImageUrl(query, LINK_PREVIEW_PROXY_SECRET);
        if (!verdict.ok) {
          // Deliberately terse: distinguishing "expired" from "bad signature" to
          // an unauthenticated caller is free information for a forgery attempt.
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Forbidden");
          return;
        }

        // A valid signature is NOT a licence to fetch anything. Re-run the full
        // egress check at fetch time: DNS records can change between the moment
        // the URL was signed and now, and defence in depth here costs one call.
        const targetVerdict = egressGuard.evaluatePreviewTarget(verdict.targetUrl);
        if (!targetVerdict.ok) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Forbidden address");
          return;
        }

        let upstream;
        let cleanupUpstream = () => {};
        try {
          const result = await fetchFollowingSafeRedirects(verdict.targetUrl, {
            headers: { "User-Agent": "Mozilla/5.0 (compatible; DuoShield/1.0)" },
            timeoutMs: 6000,
          });
          upstream = result.response;
          cleanupUpstream = result.cleanup;
        } catch (fetchErr) {
          console.warn("/linkPreviewImage fetch failed:", fetchErr.message);
          res.writeHead(502, { "Content-Type": "text/plain" });
          res.end("Upstream fetch failed");
          return;
        }

        try {
          const contentType = upstream.headers.get("content-type");
          if (!upstream.ok || !imageProxy.isAllowedImageType(contentType)) {
            // Allowlist, not blocklist: without this the endpoint would happily
            // relay HTML or SVG (which can carry script) from an arbitrary host.
            try { await upstream.body?.cancel(); } catch { /* already closed */ }
            res.writeHead(502, { "Content-Type": "text/plain" });
            res.end("Unsupported image response");
            return;
          }

          let capped;
          try {
            // Same S04-H2 reasoning as the HTML path: cap what we READ, not just
            // what we keep, so a multi-gigabyte "image" cannot exhaust the heap.
            capped = await egressGuard.readCappedBody(upstream, egressGuard.MAX_PREVIEW_IMAGE_BYTES);
          } catch (capErr) {
            console.warn("/linkPreviewImage body rejected:", capErr.message);
            res.writeHead(502, { "Content-Type": "text/plain" });
            res.end("Image too large");
            return;
          }

          res.writeHead(200, {
            "Content-Type": String(contentType).split(";")[0].trim(),
            "Content-Length": capped.buffer.length,
            // Cache aggressively: the bytes are immutable for the life of the
            // signature, and every cache hit is one fewer outbound fetch.
            "Cache-Control": "private, max-age=3600",
            // The bytes come from an arbitrary third-party host; forbid sniffing
            // them into anything executable.
            "X-Content-Type-Options": "nosniff",
            "Content-Security-Policy": "default-src 'none'; sandbox",
          });
          res.end(capped.buffer);
        } finally {
          // Close the pinned connection pool now that the body is fully read.
          cleanupUpstream();
        }
      } catch (e) {
        console.error("/linkPreviewImage error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    })();
    return;
  }

  // ── /linkPreview — server-side OG fetch (F12: prevents sender IP leakage) ──
  if (req.method === "POST" && req.url === "/linkPreview") {
    collectBody(req, res, async (body) => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        let lpUid;
        try { lpUid = (await admin.auth().verifyIdToken(tok)).uid; }
        catch { res.writeHead(401); res.end("Invalid token"); return; }
        if (!checkAuthRateLimit(lpUid, "linkPreview")) {
          res.writeHead(429); res.end("Rate limit exceeded — retry in 60 s"); return;
        }

        let targetUrl;
        try { targetUrl = JSON.parse(body).url; }
        catch { res.writeHead(400); res.end("Bad JSON"); return; }
        if (!targetUrl || typeof targetUrl !== "string") {
          res.writeHead(400); res.end("Missing url"); return;
        }
        let parsed;
        try { parsed = new URL(targetUrl); }
        catch { res.writeHead(400); res.end("Invalid URL"); return; }
        if (!["http:", "https:"].includes(parsed.protocol)) {
          res.writeHead(400); res.end("Invalid URL scheme"); return;
        }
        if (isBlockedPreviewHost(parsed.hostname)) {
          res.writeHead(403); res.end("Forbidden address"); return;
        }

        try {
          // SSRF guard, continued: `redirect: "follow"` would let a
          // malicious server 302 the fetch to an internal address
          // (127.0.0.1, a cloud metadata IP, etc.) after the initial host
          // already passed the check above — Node's fetch does not re-run
          // caller validation on redirect hops. Follow redirects manually
          // instead, so every hop's host is checked before it's fetched.
          const { response: r, finalUrl, cleanup: cleanupPreview } = await fetchFollowingSafeRedirects(targetUrl, {
            headers: { "User-Agent": "Mozilla/5.0 (compatible; DuoShield/1.0)" },
            timeoutMs: 6000,
          });
          try {
          // S04-I3: label the preview with the domain content actually came
          // from (the final hop of the redirect chain), not the originally
          // submitted host — see pure.previewDomainFromUrl's doc comment for
          // the phishing scenario this closes. `url` stays the
          // originally-submitted address (what the sender actually typed/
          // pasted into the chat), only `domain` — the field the UI renders
          // as the card's identity — changes.
          const preview = { url: targetUrl, domain: pure.previewDomainFromUrl(finalUrl, parsed.hostname) };
          if (r.ok && (r.headers.get("content-type") || "").includes("text/html")) {
            // S04-H2: `await r.text()` buffered the ENTIRE body before this
            // `.slice()` could bound it, so a host that streams gigabytes (or
            // advertises a small page and then doesn't stop) exhausted the heap
            // and took the whole server down — the slice limited what we KEPT,
            // never what we READ. readCappedBody() rejects an oversized declared
            // Content-Length up front and otherwise stops reading at the cap,
            // cancelling the upstream stream instead of draining it.
            const capped = await egressGuard.readCappedBody(r, egressGuard.MAX_PREVIEW_HTML_BYTES);
            const html = capped.buffer.toString("utf8").slice(0, 30000);
            const ogT = html.match(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']{1,200})["']/i)
                     || html.match(/<meta[^>]+content=["']([^"']{1,200})["'][^>]+property=["']og:title["']/i)
                     || html.match(/<title[^>]*>([^<]{1,200})<\/title>/i);
            const ogI = html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']{4,500})["']/i)
                     || html.match(/<meta[^>]+content=["']([^"']{4,500})["'][^>]+property=["']og:image["']/i);
            if (ogT) {
              // Decode the most common HTML entities that appear in <title> and
              // og:title content (&amp; &lt; &gt; &quot; &#39; &#NNN; &#xHHH;).
              // Without this, "BBC News &amp; Sport" is returned verbatim and
              // displayed as literal ampersand-entities to the user.
              //
              // SINGLE PASS, deliberately: the old chained .replace() calls
              // decoded `&amp;` first and then re-scanned the RESULT, so an
              // input of `&amp;#39;` became `&#39;` and then `'` — a classic
              // double-unescaping bug (an attacker could smuggle a character
              // past a caller that pre-encoded once). One regex with a single
              // left-to-right pass never re-examines what it just produced, so
              // each entity is decoded exactly once.
              const NAMED_ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };
              const rawTitle = ogT[1].trim().replace(/\s+/g, " ");
              preview.title = rawTitle.replace(
                /&(#x[0-9a-f]{1,5}|#\d{1,5}|amp|lt|gt|quot|apos);/gi,
                (whole, body) => {
                  const b = body.toLowerCase();
                  if (b[0] === "#") {
                    const code = b[1] === "x"
                      ? parseInt(b.slice(2), 16)
                      : parseInt(b.slice(1), 10);
                    return Number.isFinite(code) && code >= 0 && code <= 0x10ffff
                      ? String.fromCodePoint(code)
                      : whole;
                  }
                  return NAMED_ENTITIES[b] ?? whole;
                }
              );
            }
            if (ogI) {
              // Validate the extracted URL before returning it to the client.
              // A malicious page could set og:image to a javascript:, data:, or
              // internal-network URL — reject anything that isn't http(s):.
              const rawImageUrl = ogI[1].trim();
              try {
                const imageUrlParsed = new URL(rawImageUrl, targetUrl); // resolve relative URLs
                // S04-H3: returning imageUrlParsed.href directly is what leaked
                // the RECIPIENT's IP and a covert read-receipt to the linked
                // host �� MessageAdapter.java hands preview.imageUrl straight to
                // Glide, so every device that renders the message hits an
                // attacker-chosen server. Re-check the extracted host through the
                // egress guard (a page can point og:image at 169.254.169.254),
                // then hand the client a SIGNED URL BACK TO US instead.
                //
                // The rewrite happens server-side on purpose: an already-shipped
                // APK needs no change to benefit, which matters because the
                // Android app cannot be compiled or verified in this
                // environment (see PR-4 in RISK_REGISTER.md).
                const imageVerdict = egressGuard.evaluatePreviewTarget(imageUrlParsed.href);
                if (imageVerdict.ok) {
                  const signedPath = imageProxy.signImageUrl(
                    imageUrlParsed.href,
                    LINK_PREVIEW_PROXY_SECRET
                  );
                  // A null signature means no secret is configured. Omit the
                  // image in that case — never fall back to the raw URL, which
                  // would silently restore the leak.
                  //
                  // Must be ABSOLUTE: Glide receives this string verbatim and
                  // cannot resolve a root-relative path, so returning
                  // signedPath as-is would render no image at all. If the
                  // origin can't be determined we omit the image rather than
                  // emit a URL the client can't load.
                  const origin = publicOriginFor(req);
                  if (signedPath && origin) preview.imageUrl = `${origin}${signedPath}`;
                }
              } catch {
                // Malformed image URL — silently omit it.
              }
            }
          }
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify(preview));
          } finally {
            // Discard any unread body (non-HTML responses are never read) and
            // close the pinned connection pool for this preview fetch.
            try { await r.body?.cancel(); } catch { /* already consumed/closed */ }
            cleanupPreview();
          }
        } catch (fetchErr) {
          console.warn("/linkPreview fetch failed:", fetchErr.message);
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ url: targetUrl, domain: parsed.hostname.replace(/^www\./, "") }));
        }
      } catch (e) {
        console.error("/linkPreview error:", e.message);
        res.writeHead(500); res.end("Internal server error");
      }
    });
    return;
  }

  // ── /removeGroupMember — admin removes member + revokes key (F3) ─────────
  if (req.method === "POST" && req.url === "/removeGroupMember") {
    collectBody(req, res, async (body) => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        let callerUid;
        try { callerUid = (await admin.auth().verifyIdToken(tok)).uid; }
        catch { res.writeHead(401); res.end("Invalid token"); return; }
        if (!checkAuthRateLimit(callerUid, "removeGroupMember")) {
          res.writeHead(429); res.end("Rate limit exceeded — retry in 60 s"); return;
        }
        let groupId, memberUid;
        try { ({ groupId, memberUid } = JSON.parse(body)); }
        catch { res.writeHead(400); res.end("Bad JSON"); return; }
        if (!groupId || !memberUid) { res.writeHead(400); res.end("Missing groupId or memberUid"); return; }

        const db = admin.firestore();
        const gdoc = await db.collection("groups").doc(groupId).get();
        if (!gdoc.exists) { res.writeHead(404); res.end("Group not found"); return; }
        const gd = gdoc.data();
        if (gd.createdBy !== callerUid) { res.writeHead(403); res.end("Only the group creator can remove members"); return; }
        if (callerUid === memberUid) { res.writeHead(400); res.end("Creator cannot remove themselves"); return; }

        const batch = db.batch();
        batch.update(db.collection("groups").doc(groupId),
          { members: admin.firestore.FieldValue.arrayRemove(memberUid) });
        batch.delete(db.collection("groups").doc(groupId).collection("keys").doc(memberUid));
        await batch.commit();

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ removed: true }));
      } catch (e) {
        console.error("/removeGroupMember error:", e.message);
        res.writeHead(500); res.end("Internal server error");
      }
    });
    return;
  }

  // ── /requestLockNonce ──────────────────────────────────────────────────────
  //
  // Issues a single-use, uid-bound, 24-hour nonce for AccountLockWorker to
  // consume later via /duress-lock. Called by DuressManager on the background
  // thread before sign-out — while the Firebase session is still live — so
  // the nonce is obtained with a proper per-user verifiable credential (ID
  // token) rather than a static APK-embedded secret.
  //
  // Storing a nonce (random 32-byte hex string) in WorkManager's input data
  // is safe: unlike a Firebase ID token, a nonce has no intrinsic auth power.
  // It is bound server-side to the uid that requested it, so it cannot be used
  // to lock any other account. It is single-use — consumed and deleted on the
  // first successful /duress-lock call — so a leaked nonce cannot replay.
  //
  if (req.method === "POST" && req.url === "/requestLockNonce") {
    // collectBody enforces MAX_BODY_BYTES even though the body is unused here —
    // the bare req.on("data")/req.on("end") drain pattern bypasses the size
    // guard and allowed an unbounded POST body to stream through unchecked.
    collectBody(req, res, async () => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }

        let uid;
        try {
          uid = (await admin.auth().verifyIdToken(idToken)).uid;
        } catch (_) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        if (!checkAuthRateLimit(uid, "requestLockNonce")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded");
          return;
        }

        // ── S06-M1: eligibility is a server-side boundary, not a UI hint ──────
        // `duressEligibility/{uid}` was previously consulted only by a cached
        // client boolean that hid a button. Anyone could patch that check (or
        // write the cached pref) on an account of their own, run the whole flow,
        // and observe the wipe plus their own accountLock doc appear — learning
        // that the feature exists, how it fires, and what it writes. That is the
        // precise question the eligibility gate exists to keep unanswerable, so
        // the observable server-side consequences have to be gated too.
        //
        // 404 rather than 403: an ineligible account should not be able to tell
        // "this endpoint refused me" from "this endpoint does not exist".
        const eligSnap = await db.collection("duressEligibility").doc(uid).get();
        if (!eligSnap.exists || eligSnap.data().eligible !== true) {
          console.warn(`[requestLockNonce] refused for non-enrolled uid=${uidTag(uid)}`);
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("Not found");
          return;
        }

        // ── S06-M2: cap outstanding nonces at one per uid ─────────────────────
        // Documents were only ever removed when /duress-lock consumed one or an
        // already-expired one was presented. The common case is neither: the
        // synchronous lock write usually wins the race, so the fallback nonce is
        // never presented and the doc leaks permanently. Each leaked doc also
        // holds {uid, expiresAt} — a durable server-side record of exactly which
        // account triggered a duress wipe and when, which is the event the
        // feature is designed to make undetectable.
        //
        // A uid has no legitimate reason to hold two nonces, so drop any prior
        // ones before issuing. Single-field `uid` is auto-indexed, so this needs
        // no composite index. Belt and braces alongside the TTL policy on
        // `expiresAt` declared in firestore.indexes.json.
        try {
          const stale = await db.collection("_duressNonces").where("uid", "==", uid).get();
          if (!stale.empty) {
            const batch = db.batch();
            stale.docs.forEach((d) => batch.delete(d.ref));
            await batch.commit();
          }
        } catch (pruneErr) {
          // Non-fatal: failing to prune must not block the lock credential.
          console.warn(`[requestLockNonce] prune failed for uid=${uidTag(uid)}:`, pruneErr.message);
        }

        // Generate a 32-byte random nonce and store it in Firestore with the
        // authenticated uid. Using Admin SDK so Firestore rules never block
        // these writes (the collection is deny-all for clients).
        //
        // S06-M2: the window is 1 hour, not 24. The long runway existed because
        // the WorkManager fallback was the only way a lock could land after an
        // offline trigger; the wipe-surviving lock intent (S06-H3) now carries
        // that responsibility and re-requests a fresh nonce when it drains, so
        // the nonce no longer needs to outlive the retry schedule.
        const nonce = crypto.randomBytes(32).toString("hex");
        const expiresAt = new Date(Date.now() + 60 * 60 * 1000);
        await db.collection("_duressNonces").doc(nonce).set({ uid, expiresAt });

        // S06-M3: uidTag, never the raw uid. A log line pairing a cleartext uid
        // with a duress tag is a durable, timestamped record that this specific
        // account triggered a duress wipe, sitting in whatever aggregator the
        // server ships to ��� outside Firestore's access controls and outside the
        // operator's deletion workflow.
        console.log(`[requestLockNonce] nonce issued for uid=${uidTag(uid)}`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ nonce }));
      } catch (e) {
        console.error("[requestLockNonce] error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ─��� /duress-lock ──────────────────────────────────────────────────────────
  //
  // Writes accountLock/{uid}.locked = true via the Admin SDK. Called by
  // AccountLockWorker when the synchronous in-app lock write failed (offline
  // at trigger time) and connectivity has since been restored.
  //
  // Auth: single-use nonce issued by /requestLockNonce while the user was
  // still signed in. The nonce is bound to a specific uid server-side, so it
  // cannot be used to lock any other account. A static APK-embedded secret
  // (WORKER_SECRET) is explicitly NOT used here — it would let anyone who
  // reverse-engineered the APK lock arbitrary accounts.
  //
  if (req.method === "POST" && req.url === "/duress-lock") {
    collectBody(req, res, async (body) => {
      try {
        // ── S06-L2: rate limit the only unauthenticated mutating endpoint ─���───
        // This endpoint has to stay unauthenticated (the caller has been wiped
        // and signed out), so nothing throttled it at all. The risk is not nonce
        // brute force — 32 bytes of randomBytes is out of reach — it is that any
        // well-formed 64-char nonce drives an unauthenticated Firestore
        // transaction, letting an anonymous caller burn read quota and drown the
        // real signal in error noise. Kept generous: the legitimate client
        // retries with exponential backoff from 30 s.
        const clientIp = getClientIp(req);
        if (!checkIpRateLimit(clientIp)) {
          console.warn(`[duress-lock] IP rate limit hit ip=${ipTag(clientIp)}`);
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests");
          return;
        }

        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }

        const { nonce } = parsed;
        // S06-L1: validate shape strictly — hex only, not just length. A
        // non-hex 64-char string can never match an issued nonce, so reject it
        // before it costs a Firestore lookup.
        if (typeof nonce !== "string" || !/^[0-9a-f]{64}$/.test(nonce)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid nonce");
          return;
        }

        // Look up + consume the nonce and write the lock atomically in a single
        // transaction. This used to be a get() followed by a separate batch()
        // write, leaving a window where two concurrent requests carrying the
        // same nonce could both read it as valid before either deleted it.
        // Admin SDK bypasses Firestore rules either way.
        let uid;
        try {
          uid = await db.runTransaction(async (tx) => {
            const nonceRef  = db.collection("_duressNonces").doc(nonce);
            const nonceSnap = await tx.get(nonceRef);
            if (!nonceSnap.exists) {
              // Unknown nonce: already consumed, never issued, or corrupted.
              throw Object.assign(new Error("Invalid or already-consumed nonce"), { status: 403 });
            }
            const { uid: nonceUid, expiresAt } = nonceSnap.data();

            // ── S06-L1: validate the expiry shape and fail CLOSED ─────────────
            // The old check was `new Date() > new Date(expiresAt.toDate ? ...)`.
            // Two ways that went wrong: a missing `expiresAt` threw a TypeError
            // inside the transaction, which had no `.status` and so surfaced as a
            // 500 — and AccountLockWorker treats 5xx as retryable, so it retried
            // a permanently broken nonce forever. An unparseable string compared
            // against `Invalid Date` yields `false`, so the nonce was treated as
            // NOT expired: valid indefinitely, fail-open.
            if (!pure.isNonceUsable(nonceUid, expiresAt, Date.now())) {
              // Expired or malformed — delete to clean up and signal the client
              // not to retry. Deleting on this path is also what stops a broken
              // doc from lingering (S06-M2).
              tx.delete(nonceRef);
              throw Object.assign(new Error("Nonce expired"), { status: 401 });
            }

            // ── S06-L6: pin lockedAt to the first lock only ───────────────────
            // `update` being permitted at all meant a client holding the
            // victim's uid could re-set the doc with locked:true and a fresh
            // lockedAt repeatedly. The latch held; the forensic value did not.
            // The rules now forbid client writes outright, and this write only
            // sets lockedAt when the doc does not already carry one, so the real
            // trigger time survives a replayed lock.
            const lockRef  = db.collection("accountLock").doc(nonceUid);
            const lockSnap = await tx.get(lockRef);
            const lockData = { locked: true, lockedBy: "duress" };
            if (!lockSnap.exists || !lockSnap.data().lockedAt) {
              lockData.lockedAt = admin.firestore.FieldValue.serverTimestamp();
            }
            tx.set(lockRef, lockData, { merge: true });
            tx.delete(nonceRef); // single-use: consumed
            return nonceUid;
          });
        } catch (txErr) {
          if (txErr.status) {
            res.writeHead(txErr.status, { "Content-Type": "text/plain" });
            res.end(txErr.message);
            return;
          }
          throw txErr; // unexpected Firestore error — fall through to outer catch
        }

        // ── S06-C2 part 2: kill sessions minted before the lock landed ─────────
        // Gating /mintToken is necessary but not sufficient. signInWithCustomToken
        // yields a long-lived refresh token, so any session established *before*
        // the lock write keeps working indefinitely — and S06-M5 hands the
        // adversary a ~30 s window they can widen at will by holding the network
        // down. revokeRefreshTokens invalidates them, and the rules-level
        // `backups/` gate (part 3) catches whatever ID token is still in flight
        // until it expires.
        //
        // Deliberately after the transaction and non-fatal: the latch is the
        // durable guarantee and must not be rolled back if revocation fails. A
        // failure here is logged loudly because it means live sessions survive.
        try {
          await admin.auth().revokeRefreshTokens(uid);
        } catch (revokeErr) {
          console.error(
            `[duress-lock] token revocation FAILED for uid=${uidTag(uid)} — live sessions may persist:`,
            revokeErr.message
          );
        }

        // S06-M3: the uid is dropped from this message entirely. The operator's
        // legitimate view of who is locked is GET /admin/api/locked, which reads
        // live Firestore state and is covered by the audit log. A cleartext uid
        // next to a [duress-lock] tag is a permanent plaintext record that this
        // account triggered a duress wipe.
        console.log(`[duress-lock] accountLock written (uid=${uidTag(uid)})`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ locked: true }));
      } catch (e) {
        console.error("[duress-lock] error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── GET /admin ────────────────────────────────────────────────────────────
  //
  // Static HTML/JS shell for the operator admin panel — no server data is
  // embedded in the page itself, only the fetch calls it makes to
  // /admin/api/* carry the token, so serving this without auth is safe.
  // Match /admin with or without a query string. Mobile browsers and reverse
  // proxies may append cache-busting parameters (for example /admin?_r=...);
  // comparing req.url to the exact string "/admin" otherwise returns Not found.
  const requestPath = new URL(req.url, "http://localhost").pathname;

  // Native form login: this intentionally does not depend on client-side
  // JavaScript, which can be skipped by mobile browsers when a password is
  // autofilled. A successful token check becomes a short-lived HttpOnly
  // session cookie, so the admin API can authenticate normal same-origin
  // requests without exposing the token to page JavaScript.
  if (req.method === "POST" && requestPath === "/admin/login") {
    collectBody(req, res, async (body) => {
      const params = new URLSearchParams(body);
      const supplied = (params.get("token") || "").trim();
      const ip = getClientIp(req);
      // S07-H1 (second half of the reported bug): every failure branch below
      // used to redirect back to /admin WITHOUT touching the session cookie.
      // If a stale/al­ready-authenticated cookie was still in the jar, GET
      // /admin then rendered the full dashboard — so entering a WRONG token
      // visibly "logged you in" for a moment before the first API call 401'd
      // and bounced back to the gate. Failing a login must always leave the
      // browser with no session at all, so a wrong token can never render the
      // dashboard even for one frame. Built once here and reused by every
      // failure branch.
      const clearedSessionCookie = adminSessionCookie("", req, 0);
      const staleSessionId = getCookie(req, "duoshield_admin_session");
      const failLogin = (errorCode) => {
        // Also revoke server-side so the cookie cannot be replayed by anything
        // that captured it before this failed attempt.
        if (staleSessionId) adminSessionStore.revoke(staleSessionId);
        res.writeHead(303, {
          Location: `/admin?error=${errorCode}`,
          "Cache-Control": "no-store",
          "Set-Cookie": clearedSessionCookie,
        });
        res.end();
      };
      if (await adminIpLocked(ip)) {
        console.warn(`admin login: locked out ip=${ip}`);
        // S05-H3: the login gate is the event an investigator cares about most,
        // and until this call existed it was recorded ONLY in Render's rolling
        // console. Every branch below writes durably for the same reason.
        auditAdminEvent("admin_login_blocked_locked_out", req);
        failLogin("locked");
        return;
      }
      if (!ADMIN_TOKEN) {
        console.error("admin login: ADMIN_TOKEN is not configured on the server");
        auditAdminEvent("admin_login_unconfigured", req);
        failLogin("unconfigured");
        return;
      }
      if (!supplied || !safeTokenEqual(supplied, ADMIN_TOKEN)) {
        await recordAdminAuthFailure(ip);
        console.warn(`admin login: invalid token ip=${ip} suppliedLen=${supplied.length}`);
        // suppliedLength (not the token) distinguishes a fat-fingered operator
        // from a scripted guesser walking a wordlist. The secret itself is never
        // written to the audit log: a durable copy of a live credential would
        // turn the audit trail into a second place to steal it from.
        auditAdminEvent("admin_login_failed", req, {
          suppliedLength: supplied.length,
          failuresInWindow: await adminLockoutStore.count(ip),
        });
        failLogin("invalid");
        return;
      }
      // S04-L3: clear any accumulated failures now that this IP has proven it
      // holds the real token — mirrors the pre-fix intent that a legitimate
      // operator's earlier mistyped attempts shouldn't count toward a future
      // lockout window. (See adminLockoutStore.reset()'s doc comment.)
      await resetAdminAuthFailures(ip);
      // Session-id rotation on login: drop whatever session the browser
      // arrived with before minting the new one, so a pre-set (fixated) or
      // simply stale id is never the one that ends up authenticated.
      if (staleSessionId) adminSessionStore.revoke(staleSessionId);
      const sessionId = createAdminSession(req);
      console.log(`admin login: success ip=${ip}`);
      // Success is audited as deliberately as failure: "nobody failed" is not
      // the same as "nobody got in", and only this row can answer the latter.
      auditAdminEvent("admin_login_succeeded", req);
      res.writeHead(303, {
        Location: "/admin",
        "Cache-Control": "no-store",
        "Set-Cookie": adminSessionCookie(sessionId, req, Math.floor(ADMIN_SESSION_TTL_MS / 1000)),
      });
      res.end();
    });
    return;
  }

  // Explicitly revoke the in-memory session and expire the browser cookie.
  // Keeping this server-side means sign-out works consistently across browsers
  // and does not rely on JavaScript being able to access the HttpOnly cookie.
  if (req.method === "POST" && requestPath === "/admin/logout") {
    const sessionId = getCookie(req, "duoshield_admin_session");
    if (sessionId) adminSessionStore.revoke(sessionId);
    // S05-H3: closes the session's audit interval. Without a logout row, a
    // login at 02:00 looks open-ended forever, so every later action is
    // ambiguous as to whether that session was still the one in use.
    auditAdminEvent("admin_logout", req, { hadSession: Boolean(sessionId) });
    res.writeHead(303, {
      Location: "/admin",
      "Cache-Control": "no-store",
      "Set-Cookie": adminSessionCookie("", req, 0),
    });
    res.end();
    return;
  }

  if (req.method === "GET" && requestPath === "/admin") {
    // S05-M3 finding #2: this route requires no auth and previously called
    // hasValidAdminSession(req) with its default refresh behavior purely to
    // decide which view (gate vs. app) to render — the side effect being
    // that ANY request carrying the cookie (a browser prefetch, a restored
    // tab, a background reload) extended the session's idle timeout with no
    // real operator activity. {refresh: false} makes this call read-only.
    const authenticated = hasValidAdminSession(req, { refresh: false });
    // Generate a fresh 128-bit nonce for each response so the inline <script>
    // tag is the only code the browser will execute (blocks injected scripts).
    const nonce = crypto.randomBytes(16).toString("base64");

    // The gate's open/closed state and any login error are rendered directly
    // into the HTML here, not left for the inline <script> to decide after
    // the page loads. On some mobile browsers the inline script can be
    // delayed or fail to run at all (slow CPU, a content blocker, an in-app
    // webview) after the POST /admin/login redirect lands back on this page.
    // When that happens the old JS-only approach silently re-showed an empty,
    // error-less gate — a correct token looked like it "did nothing" and a
    // wrong one gave no feedback. Baking the real state into the markup means
    // the right screen (and the right error) shows up even if no JS runs at
    // all; the script below still runs its own checks, but only as a
    // (harmless, idempotent) enhancement on top of this.
    const ADMIN_LOGIN_ERRORS = {
      invalid: "Invalid admin token.",
      locked: "Too many failed attempts. Wait 15 minutes and try again.",
      unconfigured: "Admin panel is not configured on the server.",
    };
    const errorParam = new URL(req.url, "http://localhost").searchParams.get("error");
    const gateError = !authenticated && Object.prototype.hasOwnProperty.call(ADMIN_LOGIN_ERRORS, errorParam)
      ? ADMIN_LOGIN_ERRORS[errorParam]
      : "";

    res.writeHead(200, {
      "Content-Type": "text/html; charset=utf-8",
      "Content-Security-Policy": buildAdminCsp(nonce),
      "Cache-Control": "no-store, no-cache, must-revalidate",
      "Pragma": "no-cache",
      "Expires": "0",
    });
    res.end(
      ADMIN_PAGE_HTML
        .replace("__ADMIN_AUTHENTICATED__", String(authenticated))
        .replace("__SCRIPT_NONCE__", nonce)
        .replace("__GATE_STYLE__", authenticated ? "display:none" : "")
        .replace("__APP_STYLE__", authenticated ? "display:block" : "display:none")
        .replace("__GATE_ERROR__", gateError)
    );
    return;
  }

  // ── Admin-issued invitation lifecycle (S05-H2) ──────────────────────────────
  if (req.method === "GET" && (req.url || "").split("?")[0] === "/admin/api/invites") {
    (async () => {
      if (!(await requireAdminAuth(req, res))) return;
      try {
        const requestUrl = new URL(req.url, "http://localhost");
        const wantedStatus = requestUrl.searchParams.get("status") || "all";
        const search = (requestUrl.searchParams.get("search") || "").trim().toLowerCase();
        const pageSize = Math.min(100, Math.max(10, Number(requestUrl.searchParams.get("pageSize")) || 25));
        const cursor = invite.decodeCursor(requestUrl.searchParams.get("cursor"));
        if (!["all", "active", "used", "expired", "revoked"].includes(wantedStatus) || search.length > 80) {
          throw new TypeError("Invalid filter");
        }

        // Bounded scan with deterministic in-memory ordering avoids requiring a
        // fragile matrix of Firestore composite indexes for derived expiry + search.
        const snap = await db.collection("invites").limit(1000).get();
        const now = Date.now();
        let records = snap.docs.map((d) => {
          const data = d.data();
          const createdAtMs = invite.timestampMs(data.createdAt);
          return {
            id: d.id,
            label: data.label || "",
            notes: data.notes || "",
            fingerprint: data.tokenFingerprint || "",
            status: invite.inviteStatus(data, now),
            createdAt: createdAtMs ? new Date(createdAtMs).toISOString() : null,
            createdAtMs,
            expiresAt: invite.timestampMs(data.expiresAt) ? new Date(invite.timestampMs(data.expiresAt)).toISOString() : null,
            usedAt: invite.timestampMs(data.usedAt) ? new Date(invite.timestampMs(data.usedAt)).toISOString() : null,
            usedByUserId: data.usedByUserId || null,
            revokedAt: invite.timestampMs(data.revokedAt) ? new Date(invite.timestampMs(data.revokedAt)).toISOString() : null,
          };
        });
        const counts = { active: 0, used: 0, expired: 0, revoked: 0 };
        for (const record of records) counts[record.status]++;
        records = records
          .filter((r) => wantedStatus === "all" || r.status === wantedStatus)
          .filter((r) => !search || r.label.toLowerCase().includes(search))
          .sort((a, b) => b.createdAtMs - a.createdAtMs || b.id.localeCompare(a.id));
        if (cursor) {
          records = records.filter((r) => r.createdAtMs < cursor.createdAtMs ||
            (r.createdAtMs === cursor.createdAtMs && r.id < cursor.id));
        }
        const page = records.slice(0, pageSize);
        const nextCursor = records.length > pageSize && page.length
          ? invite.encodeCursor(page[page.length - 1].createdAtMs, page[page.length - 1].id)
          : null;
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({
          items: page.map(({ createdAtMs, ...item }) => item),
          nextCursor,
          counts,
          capped: snap.size === 1000,
        }));
      } catch (e) {
        if (e instanceof TypeError) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end(e.message);
        } else sendServerError(res, "admin/api/invites", e);
      }
    })();
    return;
  }

  if (req.method === "POST" && req.url === "/admin/api/invites/create") {
    requireAdminAuthThenBody(req, res, async (body) => {
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch { throw new TypeError("Invalid JSON"); }
        const input = invite.validateCreateInput(parsed);
        const rawToken = invite.createInviteToken();
        const ref = db.collection("invites").doc();
        await ref.create({
          tokenHash: invite.hashInviteToken(rawToken),
          tokenFingerprint: invite.tokenFingerprint(rawToken),
          status: "active",
          label: input.label,
          labelSearch: input.labelSearch,
          notes: input.notes,
          createdAt: FieldValue.serverTimestamp(),
          expiresAt: admin.firestore.Timestamp.fromMillis(input.expiresAtMs),
        });
        auditAdminEvent("invite_created", req, { inviteId: ref.id });
        res.writeHead(201, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ id: ref.id, token: rawToken }));
      } catch (e) {
        if (e instanceof TypeError) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end(e.message);
        } else sendServerError(res, "admin/api/invites/create", e);
      }
    });
    return;
  }

  if (req.method === "POST" && req.url === "/admin/api/invites/revoke") {
    requireAdminAuthThenBody(req, res, async (body) => {
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch { throw new TypeError("Invalid JSON"); }
        const inviteId = typeof parsed.inviteId === "string" ? parsed.inviteId : "";
        const reason = typeof parsed.reason === "string" ? parsed.reason.trim() : "";
        if (!/^[A-Za-z0-9_-]{10,40}$/.test(inviteId) || reason.length > 200) throw new TypeError("Invalid revoke request");
        const ref = db.collection("invites").doc(inviteId);
        let conflict = null;
        let missing = false;
        await db.runTransaction(async (tx) => {
          const snap = await tx.get(ref);
          if (!snap.exists) { missing = true; return; }
          const current = invite.inviteStatus(snap.data());
          if (current !== "active") { conflict = current; return; }
          tx.update(ref, {
            status: "revoked",
            revokedAt: FieldValue.serverTimestamp(),
            revocationReason: reason,
          });
        });
        if (missing) { res.writeHead(404); res.end("Invite not found"); return; }
        if (conflict) { res.writeHead(409); res.end(`Invite is ${conflict}`); return; }
        auditAdminEvent("invite_revoked", req, { inviteId });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        if (e instanceof TypeError) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end(e.message);
        } else sendServerError(res, "admin/api/invites/revoke", e);
      }
    });
    return;
  }

  // ── GET /admin/api/locked ─────────────────────────────────────────────────
  //
  // Auth: x-admin-token header, or an existing valid session (requireAdminAuth accepts either). Returns currently-locked accounts so the
  // operator can see who's frozen and pick one to unfreeze.
  if (req.method === "GET" && req.url === "/admin/api/locked") {
    (async () => {
      if (!(await requireAdminAuth(req, res))) return;
      try {
        // S05-L4: this query had no limit(), unlike the waitlist query
        // (limit(200)) and the audit log (limit(100)) elsewhere in this file.
        // Clients can create their own accountLock doc (firestore.rules), so
        // the locked set grows with the user base and with any attacker
        // holding multiple accounts, and the whole result set was previously
        // materialized into one JSON response on a single-process server.
        // limit(500) bounds that; the panel is not paginated beyond this cap,
        // which is an accepted, documented degradation, not a fix of the
        // finding's full ask.
        const snap = await db.collection("accountLock")
          .where("locked", "==", true)
          .limit(500)
          .get();
        const accounts = snap.docs.map((d) => {
          const data = d.data();
          const lockedAt = data.lockedAt && data.lockedAt.toDate ? data.lockedAt.toDate().toISOString() : null;
          return { uid: d.id, lockedAt };
        });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ accounts }));
      } catch (e) {
        sendServerError(res, "admin/api/locked", e);
      }
    })();
    return;
  }

  // ── POST /admin/api/locked/unfreeze ─────────────���───────────���─────────────
  //
  // Body: { uid }. Auth: x-admin-token header, or an existing valid session (requireAdminAuth accepts either).
  // Deletes the accountLock/{uid} doc — the only way this doc can ever be
  // removed, per firestore.rules (clients get `allow delete: if false`).
  if (req.method === "POST" && req.url === "/admin/api/locked/unfreeze") {
    requireAdminAuthThenBody(req, res, async (body) => {
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }
        const { uid } = parsed;
        if (!validAdminUid(uid)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid uid");
          return;
        }
        const ref = db.collection("accountLock").doc(uid);
        // ── §8 / S06-M6: unfreeze must hand back a RE-ARMABLE state ───────────
        // A plain delete used to leave the account in the promote-and-rotate
        // "duress fired" state permanently: the duress code D is now the primary
        // PIN and the device gate, slot B holds a disarmed decoy, and there is no
        // visible way to add a duress code again. The user gets a working account
        // with no duress protection and no signal that it is gone.
        //
        // So the lock is not simply removed — it is replaced by a durable
        // "rotation required" marker. The client sees this on next sign-in and
        // forces a fresh primary PIN (P2), which is the natural moment to re-arm
        // slot B with D. Keeping D as the duress code is safe: an adversary who
        // returns and enters it triggers duress again and gets nothing.
        //
        // This lives server-side rather than in SecurePrefs specifically so it
        // survives a reinstall — a local flag would be dropped by exactly the
        // wipe that necessitated the unfreeze.
        //
        // S05-L4: this used to be a plain get()-then-set() outside any
        // transaction, and it unconditionally wrote regardless of the doc's
        // current `locked` value. Two concurrent unfreeze calls both observed
        // the doc existed and both wrote — idempotent in the sense that the
        // final state was the same, but each write re-stamped unfrozenAt and
        // each was audited separately, producing two account_unfrozen rows
        // for one effective action. db.runTransaction() plus a `locked ===
        // true` guard closes both: only a doc that is actually currently
        // locked gets unfrozen and audited; a retried/losing transaction (or
        // a call against an already-unfrozen doc) takes the already-handled
        // branch below instead.
        let notFound = false;
        let alreadyUnlocked = false;
        await db.runTransaction(async (txn) => {
          const snap = await txn.get(ref);
          if (!snap.exists) { notFound = true; return; }
          if (snap.data().locked !== true) { alreadyUnlocked = true; return; }
          txn.set(ref, {
            locked:           false,
            rotationRequired: true,
            unfrozenAt:       FieldValue.serverTimestamp(),
            lockedAt:         snap.data().lockedAt || null,  // preserve trigger time (S06-L6)
          });
        });
        if (notFound) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("No lock found for this uid");
          return;
        }
        if (alreadyUnlocked) {
          res.writeHead(409, { "Content-Type": "text/plain" });
          res.end("Account is not currently locked");
          return;
        }
        // S06-M3: pseudonymise. GET /admin/api/locked is the operator's view of
        // real uids; the log does not need to hold a duress-linked cleartext id.
        console.log(`[admin] account unfrozen, rotation required: uid=${uidTag(uid)}`);

        // S05-M1: was a direct db.collection("adminAuditLog").add() with a raw
        // `adminIp: getClientIp(req)` — bypassing auditAdminEvent()'s ipTag().
        // The audit log intentionally KEEPS the real uid here (unlike the
        // duress writes below): it is the operator's accountable record,
        // inside Firestore's access controls and inside the deletion
        // workflow, unlike the server's stdout. Only the adminIp/userAgent
        // shape changes by routing through the shared sink.
        auditAdminEvent("account_unfrozen", req, { uid });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        sendServerError(res, "admin/api/locked/unfreeze", e);
      }
    });
    return;
  }

  // ── POST /admin/api/sessions/revoke-all ───────────────────────────────────
  //
  // Auth: x-admin-token header, or an existing valid session (requireAdminAuth
  // accepts either). S05-M3: bulk revocation was previously impossible at
  // all — rotating ADMIN_TOKEN never touched adminSessions (it was read once
  // at module load), so any session already minted survived a rotation until
  // it idled out, which, given the pre-fix unbounded sliding refresh (see
  // lib/adminSessionStore.js), could be never. This clears EVERY admin
  // session at once, including the caller's own — deliberately: a "sign out
  // everywhere" action that quietly spared the button-presser's own session
  // would not be a credible incident-response tool.
  if (req.method === "POST" && req.url === "/admin/api/sessions/revoke-all") {
    requireAdminAuthThenBody(req, res, async () => {
      try {
        const revokedCount = adminSessionStore.revokeAll();
        auditAdminEvent("admin_sessions_revoked_all", req, { revokedCount });
        res.writeHead(200, {
          "Content-Type": "application/json",
          // Expire the caller's own cookie too: revokeAll() already deleted
          // the session record it points to, so leaving the cookie in the
          // browser would just make the next request 401 instead of showing
          // a clean logged-out state immediately.
          "Set-Cookie": adminSessionCookie("", req, 0),
        });
        res.end(JSON.stringify({ ok: true, revokedCount }));
      } catch (e) {
        sendServerError(res, "admin/api/sessions/revoke-all", e);
      }
    });
    return;
  }

  // ── GET /admin/api/duress/enrolled ───────────────────────────────────────
  //
  // Auth: x-admin-token header, or an existing valid session (requireAdminAuth accepts either). Returns all accounts currently enrolled for
  // duress-PIN eligibility (duressEligibility/{uid}.eligible == true).
  if (req.method === "GET" && req.url === "/admin/api/duress/enrolled") {
    (async () => {
      if (!(await requireAdminAuth(req, res))) return;
      try {
        // S05-L4: same unbounded-.get() gap as GET /admin/api/locked above —
        // limit(500) bounds it; see that route's comment for the full
        // rationale.
        const snap = await db.collection("duressEligibility")
          .where("eligible", "==", true)
          .limit(500)
          .get();
        const accounts = snap.docs.map((d) => {
          const data = d.data();
          const enrolledAt = data.enrolledAt && data.enrolledAt.toDate
            ? data.enrolledAt.toDate().toISOString() : null;
          return { uid: d.id, enrolledAt };
        });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ accounts }));
      } catch (e) {
        sendServerError(res, "admin/api/duress/enrolled", e);
      }
    })();
    return;
  }

  // ── GET /admin/api/account/lookup?uid=... ──���────���────────────────────────
  //
  // Auth: x-admin-token header, or an existing valid session (requireAdminAuth accepts either). Looks up whether an account with this UID
  // actually exists (identities/{uid}) and its current duress-PIN eligibility
  // status. Used by the admin panel's "search by UID" step before enabling —
  // enrollment must never be granted blind to a UID that isn't a real account.
  if (req.method === "GET" && new URL(req.url, "http://localhost").pathname === "/admin/api/account/lookup") {
    (async () => {
      if (!(await requireAdminAuth(req, res))) return;
      try {
        const requestUrl = new URL(req.url, "http://localhost");
        const uid = requestUrl.searchParams.get("uid") || "";
        // S05-L1: this used to check only `!uid || uid.length > 128` — the one
        // uid-taking admin route that skipped validAdminUid(), which the other
        // three already use. `.doc()` accepts a slash-separated path, so
        // `?uid=a/b/c` resolved to `identities/a/b/c`, an existence oracle over
        // arbitrary nested document paths (plus a second one via
        // duressEligibility below). validAdminUid() also now rejects the
        // Firestore-reserved "."/".."/"__…__" shapes, so a malformed id yields
        // this 400 instead of an uncaught 500 from `.doc()`.
        if (!validAdminUid(uid)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid uid");
          return;
        }
        const [identitySnap, eligibilitySnap] = await Promise.all([
          db.collection("identities").doc(uid).get(),
          db.collection("duressEligibility").doc(uid).get(),
        ]);
        const accountExists  = identitySnap.exists;
        const duressEligible = accountExists && eligibilitySnap.exists && eligibilitySnap.data().eligible === true;
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ uid, accountExists, duressEligible }));
      } catch (e) {
        sendServerError(res, "admin/api/account/lookup", e);
      }
    })();
    return;
  }

  // ── POST /admin/api/duress/enroll ���────────────────────────────────────────
  //
  // Body: { uid }. Auth: x-admin-token header, or an existing valid session (requireAdminAuth accepts either).
  // Creates or updates duressEligibility/{uid} with eligible:true so the app
  // shows the secondary-PIN setup UI for that account on next eligibility check.
  // Requires the UID to correspond to a real account (identities/{uid}) —
  // enrollment is never granted blind to an unverified/nonexistent UID.
  if (req.method === "POST" && req.url === "/admin/api/duress/enroll") {
    requireAdminAuthThenBody(req, res, async (body) => {
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }
        const { uid } = parsed;
        if (!validAdminUid(uid)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid uid");
          return;
        }
        const identitySnap = await db.collection("identities").doc(uid).get();
        if (!identitySnap.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("No account found for this uid");
          return;
        }
        await db.collection("duressEligibility").doc(uid).set({
          eligible:   true,
          enrolledAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        // S06-M3/S05-M1: uidTag, never the raw uid. A cleartext uid next to
        // "duress enrollment" is a durable, plaintext, timestamped record of
        // which account has the duress feature enrolled at all — a precursor
        // signal for the exact event (S06-M3) this feature exists to make
        // undetectable, sitting outside Firestore's access controls in
        // whatever log aggregator the server ships to.
        console.log(`[admin] duress enrollment granted: uid=${uidTag(uid)}`);

        // S05-M1 (the finding's own "most sensitive" callout): this used to
        // write the RAW uid, plus a raw adminIp bypassing auditAdminEvent(),
        // into the permanent adminAuditLog — "action: duress_enrolled, uid:
        // <raw uid>" is a durable, plaintext, timestamped record of exactly
        // which account has the duress feature enrolled, i.e. the single
        // most dangerous fact in this system to disclose to a coercive
        // adversary who later reaches this collection. uidTag(uid) here
        // matches the redaction already applied to the console.log above.
        auditAdminEvent("duress_enrolled", req, { uid: uidTag(uid) });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        sendServerError(res, "admin/api/duress/enroll", e);
      }
    });
    return;
  }

  // ── POST /admin/api/duress/revoke ─────────────────────────────────────────
  //
  // Body: { uid }. Auth: x-admin-token header, or an existing valid session (requireAdminAuth accepts either).
  // Sets eligible:false on duressEligibility/{uid} — the client's cached flag
  // is updated on the next eligibility refresh (sign-in or foreground).
  if (req.method === "POST" && req.url === "/admin/api/duress/revoke") {
    requireAdminAuthThenBody(req, res, async (body) => {
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }
        const { uid } = parsed;
        if (!validAdminUid(uid)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid uid");
          return;
        }
        const ref = db.collection("duressEligibility").doc(uid);
        const snap = await ref.get();
        if (!snap.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("No eligibility record found for this uid");
          return;
        }
        await ref.update({ eligible: false, revokedAt: FieldValue.serverTimestamp() });
        // S06-M3/S05-M1: same redaction as the grant path above.
        console.log(`[admin] duress enrollment revoked: uid=${uidTag(uid)}`);

        // S05-M1: same fix as the grant path above — route through the
        // shared sink (fixes the raw adminIp) and pseudonymise the uid.
        auditAdminEvent("duress_revoked", req, { uid: uidTag(uid) });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        sendServerError(res, "admin/api/duress/revoke", e);
      }
    });
    return;
  }

  // ── GET /admin/api/auditlog ──────────────────────��────────────────────────
  //
  // Auth: x-admin-token header, or an existing valid session (requireAdminAuth accepts either). Returns the 100 most-recent admin actions
  // (waitlist approvals + account unfreezes) so the operator has a tamper-
  // evident record of who did what and when.
  if (req.method === "GET" && req.url === "/admin/api/auditlog") {
    (async () => {
      if (!(await requireAdminAuth(req, res))) return;
      try {
        const snap = await db.collection("adminAuditLog")
          .orderBy("at", "desc")
          .limit(100)
          .get();
        const entries = snap.docs.map((d) => {
          const data = d.data();
          const at = data.at && data.at.toDate ? data.at.toDate().toISOString() : null;
          return { id: d.id, action: data.action, requestId: data.requestId || null, uid: data.uid || null, adminIp: data.adminIp || null, at };
        });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ entries }));
      } catch (e) {
        sendServerError(res, "admin/api/auditlog", e);
      }
    })();
    return;
  }

  res.writeHead(404);
  res.end("Not found");

});

// S05-L2: Node's defaults (headersTimeout 60s, requestTimeout 300s in Node 20,
// which package.json:115 pins) previously applied with no override anywhere
// in this file, so an unauthenticated caller could hold a socket open for up
// to five minutes trickling a body toward MAX_BODY_BYTES against a
// single-process server that also carries FCM delivery. requireAdminAuth()
// now runs before any body is read (see requireAdminAuthThenBody() above),
// which closes the main gap; these bound the remaining unauthenticated
// exposure (header-only requests, and any body-reading route) to a much
// smaller window.
server.headersTimeout = 15_000;
server.requestTimeout = 30_000;

server.listen(PORT, () => console.log(`Push server listening on port ${PORT}`));

// ── B2 SigV4 presign surface removed (S03-L3 / S04-I2) ────────────────────────
// `b2PresignUrl` and its lock-bound wrapper `b2PresignUrlForUid` used to live
// here, along with the `/b2PresignedPut`, `/b2PresignedGet` and `/b2Delete`
// routes they were meant to serve. That whole surface was SEC-A01 residue:
// presigned-URL issuance was replaced by per-object capability tokens minted
// at `/mediaToken` and enforced by the Cloudflare Worker, so the presign
// helpers had no callers (they were even declared after `.listen()`), and the
// three routes never existed in the router table. Keeping them made
// `B2_KEY_ID` / `B2_APPLICATION_KEY` — a live, bucket-wide Backblaze
// credential — look like required server config whose only remaining purpose
// was to be leaked. The helpers, the signing math in `./lib/pure`
// (`b2HmacKey` / `buildB2PresignUrl`), and the B2 env reads are all gone.
//
// OPERATOR RUNBOOK (cannot be done from source — tracked, still required):
// revoke the Backblaze B2 application key that `B2_KEY_ID` /
// `B2_APPLICATION_KEY` referred to and delete both from the server
// environment. Until the key is revoked at Backblaze, any copy of it that
// already leaked (see S03-L1: it was compiled into released APKs) remains a
// live bucket-wide credential even though nothing in this codebase uses it.
// The duress latch that this wrapper once carried (S06-C2) is now moot on the
// server: there is no server-side path that can mint a B2 URL at all, so a
// token minted before a duress lock can no longer be used to reach media
// here.

