const admin = require("firebase-admin");
const http = require("http");
const crypto = require("crypto");
const dns = require("dns").promises;
const pure = require("./lib/pure");
// S07-C1 FIX: XEd25519 signature verification for Signal identity keys.
// Converts Curve25519 pubkeys to Edwards form before verifying Ed25519 sigs.
const xed25519 = require("./lib/xed25519");

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

  // S05-H1: ADMIN_TOKEN entropy floor check.
  // A short or low-entropy token is trivially guessable by an attacker who can
  // reach the /admin endpoints (e.g. through a misconfigured firewall rule).
  // Enforce a minimum of 32 printable ASCII characters and emit a clear startup
  // error that shows up in deployment logs before any admin request can succeed.
  // The server continues running so other endpoints remain available — the admin
  // panel simply returns 503 until the operator sets a strong token.
  const adminTokenRaw = process.env.ADMIN_TOKEN || "";
  if (!adminTokenRaw) {
    console.error("❌ S05-H1: ADMIN_TOKEN is not set — admin panel will be unavailable. " +
      "Generate a token with: openssl rand -base64 48");
  } else if (adminTokenRaw.length < 32) {
    console.error(
      `❌ S05-H1: ADMIN_TOKEN is too short (${adminTokenRaw.length} chars, minimum 32). ` +
      "Replace it with a high-entropy token: openssl rand -base64 48"
    );
  } else {
    console.log("✅ ADMIN_TOKEN entropy OK");
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

      // ── 1-to-1 chat: chats/{chatId}/messages/{msgId} ──────────────────────
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
const mintCooldown = new Map();

// ── S07-C1 FIX: per-userId challenge store ────────────────────────────────────
// /mintToken requires a proof-of-possession: the client must sign a
// server-issued nonce with their identity PRIVATE key.  A nonce is obtained
// from /mintChallenge and is valid for CHALLENGE_TTL_MS (5 minutes).
// Each nonce is single-use — consumed on first presentation, so replay fails.
// In-memory only; a server restart invalidates all pending challenges and the
// client simply calls /mintChallenge again.
//
// S02-M1 (second door): each userId holds a SET of outstanding nonces, not a
// single slot.  /mintChallenge is unauthenticated by necessity — the caller
// cannot prove anything before it receives a nonce to sign — so a single-slot
// store let an attacker who merely knows a victim's userId call /mintChallenge
// repeatedly and evict the victim's pending nonce between their own
// /mintChallenge and /mintToken, denying that account re-authentication
// indefinitely.  That is exactly the pre-auth DoS S02-M1 removed from the
// cooldown, reintroduced through the challenge path.  Holding several nonces
// concurrently means an attacker's requests ADD entries instead of destroying
// the victim's.
//
// The per-user cap bounds memory (an unauthenticated endpoint must not grow the
// heap without limit); evicting the OLDEST entry on overflow keeps the newest —
// and therefore the legitimate in-flight — challenge alive.
const CHALLENGE_TTL_MS       = 5 * 60 * 1000; // 5 minutes
const MAX_CHALLENGES_PER_UID = 16;
const mintChallenges         = new Map();     // userId → Map<nonce, expiresAt>

/** Issue a fresh single-use nonce for `userId`, bounded per user. */
function issueChallenge(userId) {
  const nonce = crypto.randomBytes(32).toString("hex");
  let perUid  = mintChallenges.get(userId);
  if (!perUid) {
    perUid = new Map();
    mintChallenges.set(userId, perUid);
  }
  // Drop expired entries first, then enforce the cap oldest-first (Map preserves
  // insertion order, so the first key is the oldest).
  const now = Date.now();
  for (const [n, exp] of perUid) if (exp <= now) perUid.delete(n);
  while (perUid.size >= MAX_CHALLENGES_PER_UID) {
    perUid.delete(perUid.keys().next().value);
  }
  perUid.set(nonce, now + CHALLENGE_TTL_MS);
  return nonce;
}

/**
 * Atomically consume `nonce` for `userId`.
 * Returns "ok" | "missing" | "expired". Consuming makes replay impossible.
 */
function consumeChallenge(userId, nonce) {
  const perUid = mintChallenges.get(userId);
  if (!perUid) return "missing";
  const expiresAt = perUid.get(nonce);
  if (expiresAt === undefined) return "missing";
  // Single-use: remove regardless of whether it had expired.
  perUid.delete(nonce);
  if (perUid.size === 0) mintChallenges.delete(userId);
  return Date.now() > expiresAt ? "expired" : "ok";
}

// Purge expired challenges every 10 minutes.
setInterval(() => {
  const now = Date.now();
  for (const [uid, perUid] of mintChallenges) {
    for (const [nonce, expiresAt] of perUid) {
      if (expiresAt <= now) perUid.delete(nonce);
    }
    if (perUid.size === 0) mintChallenges.delete(uid);
  }
}, 10 * 60 * 1000);

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

function checkWaitlistIpRateLimit(ip) {
  const now = Date.now();
  const rec = waitlistIpHits.get(ip);
  if (!rec || now - rec.windowStart >= WAITLIST_IP_WINDOW_MS) {
    waitlistIpHits.set(ip, { count: 1, windowStart: now });
    return true;
  }
  if (rec.count >= WAITLIST_IP_MAX_HITS) return false;
  rec.count++;
  return true;
}

function checkWaitlistPollRateLimit(ip) {
  const now = Date.now();
  const rec = waitlistPollHits.get(ip);
  if (!rec || now - rec.windowStart >= WAITLIST_POLL_WINDOW_MS) {
    waitlistPollHits.set(ip, { count: 1, windowStart: now });
    return true;
  }
  if (rec.count >= WAITLIST_POLL_MAX_HITS) return false;
  rec.count++;
  return true;
}

// ── Per-IP rate limit ───────────────────────────────���─────────────────────────
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

// ── Per-UID authenticated-endpoint rate limiter ───────────────────────────────
// Prevents an authenticated user from flooding server-mediated endpoints.
// Each endpoint has its own per-minute bucket.
const AUTH_RATE_WINDOW_MS = 60_000;
const AUTH_RATE_LIMITS = {
  // S04-I2: b2PresignedPut, b2PresignedGet, b2Delete removed — those routes no
  // longer exist in this codebase (media storage moved off B2 in an earlier cycle).
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

function b64url(buf) {
  return buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Mints a capability token bound to one object key, one operation, one user and
 * one expiry. The key itself is NOT carried in the token — the Worker already
 * knows it from the request path and re-derives the signature over it, so a
 * token cannot be replayed against a different object.
 *
 * Wire format: v1.<op>.<expiresAt>.<uidTag>.<sig>
 */
function signMediaToken({ op, key, uid, expiresAt }) {
  const holder  = uidTag(uid); // pseudonymous — lets the Worker rate-limit per user
  const payload = `v1|${op}|${expiresAt}|${holder}|${key}`;
  const sig     = b64url(crypto.createHmac("sha256", MEDIA_TOKEN_SECRET).update(payload).digest());
  return `v1.${op}.${expiresAt}.${holder}.${sig}`;
}

/**
 * True if {@code uid} participates in the chat or group named by {@code scopeId}.
 *
 * The object-key middle segment is either a chats/{id} or a groups/{id}, so both
 * collections are checked. Membership fields mirror firestore.rules:
 * chats use `participants`, groups use `members`.
 */
async function callerMayAccessScope(uid, scopeId) {
  if (!uid || !scopeId) return false;
  try {
    const [chatDoc, groupDoc] = await Promise.all([
      db.collection("chats").doc(scopeId).get(),
      db.collection("groups").doc(scopeId).get(),
    ]);
    if (chatDoc.exists) {
      const participants = chatDoc.data().participants;
      if (Array.isArray(participants) && participants.includes(uid)) return true;
    }
    if (groupDoc.exists) {
      const members = groupDoc.data().members;
      if (Array.isArray(members) && members.includes(uid)) return true;
    }
  } catch (e) {
    // Fail closed on lookup errors — never mint a token we could not authorize.
    console.error("callerMayAccessScope lookup failed:", e.message);
    return false;
  }
  return false;
}

// ── Admin panel auth ──────────────────────────────────────────────────────────
// Gates /admin/api/* (waitlist approval, account-lock unfreeze). A single
// operator-held token (ADMIN_TOKEN env var), never shipped in the APK. The
// static /admin page itself carries no data — only the API calls it makes
// need the token — so serving the HTML shell without auth leaks nothing.
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || "";
const ADMIN_IP_WINDOW_MS = 15 * 60 * 1000; // 15 minutes
const ADMIN_IP_MAX_FAILS = 10;
const adminIpFails = new Map(); // ip → { count, windowStart }
const ADMIN_SESSION_TTL_MS = 30 * 60 * 1000;
const adminSessions = new Map(); // opaque session id → expiry timestamp

setInterval(() => {
  const cutoff = Date.now() - ADMIN_IP_WINDOW_MS;
  for (const [ip, rec] of adminIpFails) {
    if (rec.windowStart < cutoff) adminIpFails.delete(ip);
  }
}, 30 * 60 * 1000);

setInterval(() => {
  const now = Date.now();
  for (const [sessionId, expiresAt] of adminSessions) {
    if (expiresAt <= now) adminSessions.delete(sessionId);
  }
}, 5 * 60 * 1000);

function adminIpLocked(ip) {
  const rec = adminIpFails.get(ip);
  if (!rec) return false;
  if (Date.now() - rec.windowStart >= ADMIN_IP_WINDOW_MS) return false;
  return rec.count >= ADMIN_IP_MAX_FAILS;
}

function recordAdminAuthFailure(ip) {
  const now = Date.now();
  const rec = adminIpFails.get(ip);
  if (!rec || now - rec.windowStart >= ADMIN_IP_WINDOW_MS) {
    adminIpFails.set(ip, { count: 1, windowStart: now });
  } else {
    rec.count++;
  }
}

// Constant-time comparison so token-guessing can't be timed byte-by-byte.
// Implementation lives in ./lib/pure (unit-tested there).
const safeTokenEqual = pure.safeTokenEqual;

const validAdminUid = pure.validAdminUid;

function adminSessionCookie(sessionId, req, maxAgeSeconds) {
  const forwardedProto = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim().toLowerCase();
  const isHttps = forwardedProto === "https" || Boolean(req.socket.encrypted);
  return `duoshield_admin_session=${encodeURIComponent(sessionId)}; Path=/admin; Max-Age=${maxAgeSeconds}; HttpOnly;${isHttps ? " Secure;" : ""} SameSite=Strict`;
}

const getCookie = pure.getCookie;

function createAdminSession() {
  const sessionId = crypto.randomBytes(32).toString("hex");
  adminSessions.set(sessionId, Date.now() + ADMIN_SESSION_TTL_MS);
  return sessionId;
}

function hasValidAdminSession(req) {
  const sessionId = getCookie(req, "duoshield_admin_session");
  if (!sessionId) return false;
  const expiresAt = adminSessions.get(sessionId);
  if (!expiresAt || expiresAt <= Date.now()) {
    adminSessions.delete(sessionId);
    return false;
  }
  // Sliding expiry keeps an actively used admin panel open.
  adminSessions.set(sessionId, Date.now() + ADMIN_SESSION_TTL_MS);
  return true;
}

// Returns true and lets the caller proceed, or writes a 401/429/503 response
// and returns false. Every admin/api route must call this first.
function requireAdminAuth(req, res) {
  const ip = getClientIp(req);
  if (adminIpLocked(ip)) {
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
  if (!tokenValid && !hasValidAdminSession(req)) {
    recordAdminAuthFailure(ip);
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

function getClientIp(req) {
  // Use the RIGHTMOST entry in X-Forwarded-For — the one appended by the
  // trusted terminating proxy (Render). The leftmost entries are client-
  // controlled and trivially spoofable; trusting them would let any attacker
  // bypass every IP-based rate limit and the admin lockout by forging:
  //   X-Forwarded-For: 1.2.3.4
  const forwarded = req.headers["x-forwarded-for"];
  if (forwarded) {
    const entries = forwarded.split(",");
    return entries[entries.length - 1].trim() || req.socket.remoteAddress || "unknown";
  }
  return req.socket.remoteAddress || "unknown";
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

function checkIpRateLimit(ip) {
  const now = Date.now();
  const rec = ipHits.get(ip);
  if (!rec || now - rec.windowStart >= IP_WINDOW_MS) {
    ipHits.set(ip, { count: 1, windowStart: now });
    return true; // allowed
  }
  if (rec.count >= IP_MAX_HITS) return false; // blocked
  rec.count++;
  return true; // allowed
}

function sha256hex(hexStr) {
  return crypto.createHash("sha256").update(Buffer.from(hexStr, "hex")).digest("hex");
}

// ── SSRF guard helpers for /linkPreview ───────────────────────────────────────
// Block private/loopback addresses and cloud metadata endpoints. Applied both
// to the initial user-supplied URL and to every redirect hop (see
// fetchFollowingSafeRedirects below) — checking only the first URL would let
// a malicious server redirect the fetch to an internal address afterwards.
const isBlockedPreviewHost = pure.isBlockedPreviewHost;

// S04-H1: Resolves a hostname to an IP via dns.lookup() and throws if the
// resolved IP falls in a private, loopback, or cloud-metadata range. Applied
// to every hop in fetchFollowingSafeRedirects so a DNS rebinding attack cannot
// swap a public IP for a private one between the hostname check and the fetch.
function isPrivateOrMetadataIp(ip) {
  const blocked = [
    /^127\./,                                           // IPv4 loopback
    /^10\./,                                            // RFC 1918 class A
    /^172\.(1[6-9]|2\d|3[01])\./,                       // RFC 1918 class B
    /^192\.168\./,                                      // RFC 1918 class C
    /^169\.254\./,                                      // link-local / GCP/AWS metadata
    /^100\.(6[4-9]|[7-9]\d|1([01]\d|2[0-7]))\./,       // RFC 6598 shared address space
    /^0\./,                                             // "this" network (RFC 1122)
    /^198\.1[89]\./,                                    // benchmarking (RFC 2544)
    /^::1$/,                                            // IPv6 loopback
    /^fc[0-9a-f]{2}:/i,                                 // IPv6 unique local (fc00::/7 first half)
    /^fd[0-9a-f]{2}:/i,                                 // IPv6 unique local (fc00::/7 second half)
    /^fe80:/i,                                          // IPv6 link-local
  ];
  return blocked.some((r) => r.test(ip));
}

async function resolveAndCheckHost(hostname) {
  let address;
  try {
    ({ address } = await dns.lookup(hostname, { verbatim: false }));
  } catch (e) {
    throw new Error(`DNS lookup failed for ${hostname}: ${e.message}`);
  }
  if (isPrivateOrMetadataIp(address)) {
    throw new Error(`Resolved ${hostname} → ${address} which is in a blocked range (SSRF guard)`);
  }
}

// S04-H2: Read at most maxBytes from a fetch Response body without buffering
// the entire response first. Returns a UTF-8 string. Falls back to r.text()
// on environments where r.body is unavailable (Node < 18).
const LINK_PREVIEW_MAX_HTML_BYTES = 100 * 1024; // 100 KB

async function readHtmlCapped(response, maxBytes) {
  // Fast path: declared Content-Length is within the cap — read all at once.
  const cl = parseInt(response.headers.get("content-length") || "0", 10);
  if (cl > 0 && cl <= maxBytes) {
    return response.text();
  }
  // Stream with cap; cancel the body as soon as we have enough bytes.
  if (response.body && typeof response.body.getReader === "function") {
    const reader = response.body.getReader();
    const chunks = [];
    let total = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.length;
        if (total > maxBytes) {
          // Keep only as many bytes as remain within the cap.
          chunks.push(value.slice(0, value.length - (total - maxBytes)));
          break;
        }
        chunks.push(value);
      }
    } finally {
      reader.cancel().catch(() => {});
    }
    return Buffer.concat(chunks).toString("utf-8");
  }
  // Fallback for older Node versions — truncate after the fact.
  return (await response.text()).slice(0, maxBytes);
}

// Fetches targetUrl, manually validating and following redirects (instead of
// `redirect: "follow"`) so each hop is re-checked against isBlockedPreviewHost
// AND has its DNS-resolved IP verified via resolveAndCheckHost (S04-H1) before
// it is fetched. Throws on a blocked/invalid hop or too many redirects.
async function fetchFollowingSafeRedirects(targetUrl, { headers, timeoutMs, maxRedirects = 5 }) {
  let current = targetUrl;
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const parsed = new URL(current);
    if (!["http:", "https:"].includes(parsed.protocol) || isBlockedPreviewHost(parsed.hostname)) {
      throw new Error(`Blocked redirect target: ${parsed.hostname}`);
    }
    // S04-H1: Resolve hostname → IP and reject if private/metadata address.
    await resolveAndCheckHost(parsed.hostname);
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), timeoutMs);
    let response;
    try {
      response = await fetch(current, { headers, signal: ctrl.signal, redirect: "manual" });
    } finally {
      clearTimeout(t);
    }
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get("location");
      if (!location) throw new Error("Redirect with no Location header");
      current = new URL(location, current).toString();
      continue;
    }
    return { response, finalUrl: current };
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
// migrated to the Promise-based helper above). Enforces the same
// MAX_BODY_BYTES cap: the naive `body += chunk` pattern this replaces has
// no size limit of its own — it only inherited protection from the
// declared Content-Length pre-check up in the request handler, which a
// chunked-encoding request (no Content-Length header) bypasses entirely.
// Calls onComplete(body) only when the body stayed within the limit;
// otherwise the 413 response is already sent and onComplete is not called.
function collectBody(req, res, onComplete) {
  let body = "";
  let tooLarge = false;
  req.on("data", (chunk) => {
    if (tooLarge) return;
    body += chunk;
    if (body.length > MAX_BODY_BYTES) {
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

// ── Admin panel HTML shell ─────────────────────────────────────────────────────
// Self-contained page (no build step, no external assets) served at GET /admin.
// Prompts for the operator token once, keeps it in memory only (never
// persisted to localStorage/cookies), and sends it as `x-admin-token` on
// every fetch to /admin/api/*. All rendered values go through textContent,
// never innerHTML, so nothing from Firestore can execute as markup.
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
  .refresh { margin-left:auto; }
  .sr-only { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
  @media (max-width:760px) { #app{width:min(calc(100% - 20px),620px);padding-top:18px}.app-header{flex-direction:column}.header-actions{width:100%}.header-actions .action{flex:1}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}.section-head{padding:15px 14px 12px}.section-help,.section-body{padding-left:14px;padding-right:14px}.table-scroll{overflow-x:auto;margin:0 -14px;padding:0 14px}.table-scroll table{min-width:570px}.search-row{align-items:stretch;flex-direction:column}.search-row .action{width:100%}.search-result{align-items:flex-start;flex-direction:column}.search-result .action{width:100%} }
  @media (max-width:390px) { .gate{width:calc(100% - 20px);padding:24px 18px}.stats{gap:8px}.stat{padding:13px}.stat-value{font-size:21px} }
</style>
</head>
<body>

  <div class="gate" id="gate">
    <div class="brand-mark" aria-hidden="true">DS</div>
    <h1>DuoShield Admin</h1>
    <div class="sub">Secure operator access for waitlist, account locks, and duress PIN eligibility.</div>
    <form id="gateForm" action="/admin/login" method="post">
      <label class="sr-only" for="tokenInput">Admin token</label>
      <input type="password" id="tokenInput" name="token" placeholder="Enter admin token" autofocus autocomplete="current-password" required>
      <button type="submit" id="unlockBtn">Unlock dashboard</button>
    </form>
    <div class="err" id="gateErr" role="alert"></div>
  </div>

  <main id="app">
    <header class="app-header">
      <div>
        <div class="eyebrow">Operator console</div>
        <h1>DuoShield Admin</h1>
        <div class="sub">Manage access and duress-PIN eligibility without opening the Firebase console.</div>
      </div>
      <div class="header-actions">
        <button class="action" type="button" onclick="refreshAll()">Refresh all</button>
        <button class="action" type="button" onclick="logout()">Sign out</button>
      </div>
    </header>

    <div class="stats" aria-label="Account summary">
      <div class="stat"><div class="stat-label">Pending access</div><div class="stat-value" id="pendingCount">—</div></div>
      <div class="stat"><div class="stat-label">Locked accounts</div><div class="stat-value" id="lockedCount">—</div></div>
      <div class="stat"><div class="stat-label">Duress enabled</div><div class="stat-value" id="duressCount">—</div></div>
      <div class="stat"><div class="stat-label">Recent actions</div><div class="stat-value" id="auditCount">—</div></div>
    </div>

    <section>
      <div class="section-head"><h2>Pending waitlist requests</h2><button class="action refresh" type="button" onclick="loadWaitlist()">Refresh</button></div>
      <div class="section-body">
        <div class="table-scroll"><table><thead><tr><th>Request ID</th><th>Requested</th><th><span class="sr-only">Action</span></th></tr></thead><tbody id="waitlistBody"></tbody></table></div>
        <div class="loading" id="waitlistLoading">Loading…</div>
        <div class="empty" id="waitlistEmpty" hidden>No pending requests.</div>
      </div>
    </section>

    <section>
      <div class="section-head"><h2>Locked accounts</h2><button class="action refresh" type="button" onclick="loadLocked()">Refresh</button></div>
      <div class="section-body">
        <div class="table-scroll"><table><thead><tr><th>UID</th><th>Locked at</th><th><span class="sr-only">Action</span></th></tr></thead><tbody id="lockedBody"></tbody></table></div>
        <div class="loading" id="lockedLoading">Loading…</div>
        <div class="empty" id="lockedEmpty" hidden>No locked accounts.</div>
      </div>
    </section>

    <section>
      <div class="section-head"><h2>Duress PIN enrollment</h2><button class="action refresh" type="button" onclick="loadDuressEnrolled()">Refresh</button></div>
      <div class="section-help">Search a real account UID first. Enable makes the secondary-PIN setup available in the app; it does not set a PIN for the user.</div>
      <div class="section-body">
      <div class="search-row">
        <label class="sr-only" for="duressUidInput">Account UID</label>
        <input class="search-input" id="duressUidInput" type="text" placeholder="Search by account UID" autocomplete="off" spellcheck="false">
        <button class="action primary" id="duressSearchButton" type="button" onclick="searchDuressAccount()">Search account</button>
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
      <div class="section-head"><h2>Audit log</h2><button class="action refresh" type="button" onclick="loadAuditLog()">Refresh</button></div>
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
  return Promise.all([loadWaitlist(), loadLocked(), loadDuressEnrolled(), loadAuditLog()]);
}

async function loadWaitlist() {
  setLoading("waitlist", true);
  try {
    const data = await api("/admin/api/waitlist");
    showApp();
    const body = document.getElementById("waitlistBody");
    body.innerHTML = "";
    setCount("pending", data.requests.length);
    setEmpty("waitlist", !data.requests.length);
    for (const r of data.requests) {
      const tr = document.createElement("tr");

      const idTd = document.createElement("td");
      idTd.className = "mono";
      idTd.textContent = r.requestId;
      tr.appendChild(idTd);

      const dateTd = document.createElement("td");
      dateTd.textContent = r.createdAt ? new Date(r.createdAt).toLocaleString() : "—";
      tr.appendChild(dateTd);

      const actionTd = document.createElement("td");
      const btn = document.createElement("button");
      btn.className = "action";
      btn.textContent = "Approve";
      btn.onclick = () => approve(r.requestId, btn);
      actionTd.appendChild(btn);
      tr.appendChild(actionTd);

      body.appendChild(tr);
    }
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load waitlist: " + e.message);
  } finally {
    setLoading("waitlist", false);
  }
}

async function approve(requestId, btn) {
  btn.disabled = true;
  btn.textContent = "Approving…";
  try {
    await api("/admin/api/waitlist/approve", { method: "POST", body: JSON.stringify({ requestId }) });
    toast("Approved " + requestId.slice(0, 8) + "…");
    loadWaitlist();
    loadAuditLog();
  } catch (e) {
    if (e.message !== "unauthorized") { toast("Approve failed: " + e.message); btn.disabled = false; btn.textContent = "Approve"; }
  }
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
    document.getElementById("duressSearchUid").textContent = uid;
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
  if (!confirm("Revoke duress PIN eligibility for " + uid + "?\nThey will lose access to the secondary-PIN feature.")) return;
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
                           : e.action === "account_unfrozen"  ? "🔓 Account unfrozen"
                           : e.action === "duress_enrolled"   ? "🔐 Duress enrolled"
                           : e.action === "duress_revoked"    ? "❌ Duress revoked"
                           : e.action;
      tr.appendChild(actionTd);

      const targetTd = document.createElement("td");
      targetTd.className = "mono";
      targetTd.textContent = e.requestId ? e.requestId.slice(0, 12) + "…" : (e.uid || "—");
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

// ── Inactivity auto-logout (10 minutes) ──────────────────────────────────────
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

function forceLogout(showMessage = true) {
  TOKEN = "";
  sessionActive = false;
  clearTimeout(inactivityTimer);
  clearInterval(countdownTimer);
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
  loadWaitlist();
  loadLocked();
  loadDuressEnrolled();
  loadAuditLog();
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

// ── Security response headers ───────────────────────────────────────��─────────
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

// ── Health + status + mintToken HTTP server ───────────────────────────────────
http.createServer((req, res) => {

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

  // ── POST /mintToken ───────────────────────────────������────────────────────────
  //
  // Body (JSON): { userId, identityPubKeyHex }
  //
  // Security model (F2 fix applied):
  //   • New accounts: identity slot claimed atomically inside a Firestore transaction
  //     before the token is minted.  First caller wins; concurrent first-claim attempts
  //     for the same userId are serialized by the transaction.
  //   • Existing accounts: sha256(identityPubKeyHex) is re-verified inside the same
  //     transaction.  Mismatch → 403.
  //   • Rate limit: one successful mint per userId per 60 s (in-memory).
  //
  // ── POST /mintChallenge ──────────────────────────────────────────────────────
  //
  // S07-C1 FIX — Step 1 of proof-of-possession.
  //
  // Body: { userId: string }
  // Response: { nonce: string }   — 32-byte hex string
  //
  // Issues a one-time challenge nonce bound to the given userId.  The client
  // must sign this nonce with their identity PRIVATE key and present the
  // signature in the subsequent /mintToken call.  The nonce expires after
  // CHALLENGE_TTL_MS (5 min) and is deleted on first use.
  //
  // Rate-limited by the same IP bucket as /mintToken.
  if (req.method === "POST" && req.url === "/mintChallenge") {
    collectBody(req, res, async (body) => {
      try {
        const clientIp = getClientIp(req);
        if (!checkIpRateLimit(clientIp)) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        const parsed = JSON.parse(body);
        const { userId } = parsed;
        if (!userId || typeof userId !== "string" || userId.length > 128) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId");
          return;
        }

        // Issue an ADDITIONAL single-use challenge for this userId. Deliberately
        // additive rather than replacing: see the S02-M1 note on the challenge
        // store — replacing would let an unauthenticated caller evict a victim's
        // in-flight nonce and deny them re-authentication.
        const nonce = issueChallenge(userId);

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ nonce }));
      } catch (e) {
        sendServerError(res, "mintChallenge", e);
      }
    });
    return;
  }

  if (req.method === "POST" && req.url === "/mintToken") {
    collectBody(req, res, async (body) => {
      try {
        // ── IP rate limit (checked before parsing body) ──────────────────────
        const clientIp = getClientIp(req);
        if (!checkIpRateLimit(clientIp)) {
          console.warn(`mintToken: IP rate limit hit ip=${ipTag(clientIp)}`);
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        const parsed = JSON.parse(body);
        const { userId, identityPubKeyHex, nonce, signatureHex, waitlistRequestId } = parsed;

        if (!userId || typeof userId !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId");
          return;
        }
        if (!identityPubKeyHex || typeof identityPubKeyHex !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing identityPubKeyHex");
          return;
        }

        // ── S07-C1 FIX: proof-of-possession via challenge/signature ──────────
        //
        // The client MUST have obtained a nonce from /mintChallenge and signed it
        // with their identity private key (Ed25519).  We verify the signature
        // against the provided public key, then check that public key's hash
        // against the stored record.  This proves the caller holds the private key
        // that corresponds to the registered identity, which is derived from the
        // seed phrase — a public value cannot be forged as a signature.
        //
        // S07-H1 / S02-L1 FIX: absence of a stored identity record is an explicit
        // deny for existing-account paths.  The only path that may proceed without
        // a pre-existing record is new-account creation (gated on a valid waitlist
        // request consumed atomically in the same Firestore transaction).
        //
        // S02-M1 FIX: the per-userId cooldown is stamped AFTER authentication
        // succeeds, not before.  Pre-auth cooldown stamping allowed an
        // unauthenticated attacker to supply any victim userId and lock out that
        // account's re-auth for 60 s.

        if (!nonce || typeof nonce !== "string" ||
            !signatureHex || typeof signatureHex !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing nonce or signatureHex — call /mintChallenge first");
          return;
        }

        // ── Validate and consume the challenge nonce ─────────────────────────
        // Single-use: consumeChallenge removes the nonce, so a replay of this
        // exact request body returns "missing" on the second attempt.  An unknown
        // nonce does NOT disturb this user's other outstanding challenges, so a
        // guessing flood cannot evict a legitimate in-flight nonce (S02-M1).
        const challengeState = consumeChallenge(userId, nonce);
        if (challengeState !== "ok") {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end(
            challengeState === "expired"
              ? "Challenge expired — call /mintChallenge again"
              : "No active challenge for this nonce — call /mintChallenge first",
          );
          return;
        }

        // ── Verify XEd25519 signature over the nonce ─────────────────────────
        // Signal's identity key is Curve25519 (Montgomery form). The client
        // signs with Curve.calculateSignature which uses XEd25519: the key is
        // converted to Edwards form and a standard Ed25519 sign + domain prefix
        // (32 × 0xFE) is applied.  server/lib/xed25519.js mirrors this transform
        // so we can verify with Node's built-in ed25519 after conversion.
        let sigValid = false;
        try {
          sigValid = xed25519.verifySignature(
            Buffer.from(identityPubKeyHex, "hex"),   // 32-byte Curve25519 pubkey
            Buffer.from(nonce, "utf8"),               // original nonce (prefix applied inside)
            Buffer.from(signatureHex, "hex"),         // 64-byte XEd25519 signature
          );
        } catch (_) {
          sigValid = false;
        }
        if (!sigValid) {
          let attemptedUid = "none";
          try { attemptedUid = uidTag(userId); } catch { /* ignore */ }
          console.warn(`mintToken: signature verification failed userId=${attemptedUid}`);
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Signature verification failed");
          return;
        }

        // ── S02-M1 FIX: cooldown gate for an AUTHENTICATED caller only ────────
        // Reachable only after the signature verifies, so an unauthenticated
        // caller can no longer pin a victim's cooldown by supplying their userId.
        //
        // Checked BEFORE the Firestore transaction because the transaction has
        // side effects that must not occur on a request we are about to reject:
        // gating afterwards would consume the caller's single-use waitlist invite
        // and write the identity binding, then return 429 — leaving the invite
        // marked "used" and the account unrecoverable on retry.
        //
        // Stamped only on SUCCESS (after the token is issued) so a request that
        // fails the lock check or key comparison does not start a 60 s cooldown.
        const mintStart = Date.now();
        const lastMint  = mintCooldown.get(userId) || 0;
        if (mintStart - lastMint < 60_000) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests — wait 60 s and retry");
          return;
        }

        // ── Compute hash of the provided public key ───────────────────────────
        const incomingHash = sha256hex(identityPubKeyHex);

        const idRef   = db.collection("identities").doc(userId);
        const lockRef = db.collection("accountLock").doc(userId);

        // ── Atomic Firestore transaction ──────────────────────────────────────
        // Reads and checks identities/{userId} and accountLock/{userId}
        // atomically.  Actions:
        //   a) New account: consume waitlist request + write identity binding.
        //   b) Existing account: verify hash matches stored record. Fail closed
        //      if no stored record exists (S07-H1 / S02-L1 fix).
        //   c) Either path: deny if accountLock/{userId}.locked == true (S06-H1).
        //
        // The token is minted only after this transaction succeeds, so there is
        // no window between "verified identity" and "token issued".
        let isNewAccount = false;
        await db.runTransaction(async (tx) => {
          const [idSnap, lockSnap] = await Promise.all([
            tx.get(idRef),
            tx.get(lockRef),
          ]);

          // S06-H1 FIX: accountLock is checked SERVER-SIDE inside the mint
          // transaction, not client-side after the token is already issued.
          // A locked account gets the same generic 403 as a key mismatch — no
          // information about whether the account is locked, which uid is locked,
          // or any other distinguishing signal.
          if (lockSnap.exists && lockSnap.data().locked === true) {
            throw Object.assign(new Error("Account locked"), { status: 403, generic: true });
          }

          if (!idSnap.exists) {
            // New account path — invite-only.
            if (!waitlistRequestId || typeof waitlistRequestId !== "string" ||
                !/^[0-9a-f]{32}$/.test(waitlistRequestId)) {
              throw Object.assign(new Error("Access request required"), { status: 403 });
            }
            const waitlistRef  = db.collection("waitlist").doc(waitlistRequestId);
            const waitlistSnap = await tx.get(waitlistRef);
            if (!waitlistSnap.exists || waitlistSnap.data().status !== "approved") {
              throw Object.assign(new Error("Access request not approved"), { status: 403 });
            }
            tx.update(waitlistRef, {
              status:       "used",
              usedByUserId: userId,
              usedAt:       FieldValue.serverTimestamp(),
            });
            // Store full public key hex (not just hash) so future logins can
            // verify solely by signature without needing the hash comparison.
            tx.set(idRef, {
              uid:                 userId,
              identityPubKeyHash:  incomingHash,
              identityPubKeyHex:   identityPubKeyHex,
              createdAt:           FieldValue.serverTimestamp(),
            });
            isNewAccount = true;
          } else {
            // Existing account path — verify the public key.
            const data       = idSnap.data();
            const storedHash = data.identityPubKeyHash;

            // S07-H1 / S02-L1 FIX: fail CLOSED when no hash is stored.
            // Previously: `if (storedHash && storedHash !== incomingHash)` — the
            // guard only fired when storedHash was truthy, so a missing/null/empty
            // hash allowed ANY public key through.  Now: absence of a stored hash
            // is an explicit error.
            if (!storedHash) {
              throw Object.assign(
                new Error("Identity record incomplete — contact support"),
                { status: 403 }
              );
            }
            if (storedHash !== incomingHash) {
              throw Object.assign(new Error("Key mismatch"), { status: 403 });
            }
            // Opportunistic upgrade: persist full pubkey hex for future logins
            // that may later drop the hash-comparison path.
            if (!data.identityPubKeyHex) {
              tx.update(idRef, { identityPubKeyHex: identityPubKeyHex });
            }
          }
        });

        // Mint custom token — uid = userId (permanent, seed-derived).
        // Issued only after signature verification AND atomic identity-claim.
        const token = await admin.auth().createCustomToken(userId);

        // Cooldown stamped only now, on the success path (see the gate above).
        mintCooldown.set(userId, Date.now());

        console.log(`mintToken: issued token uid=${uidTag(userId)} newAccount=${isNewAccount}`);

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token }));
      } catch (e) {
        if (e.status === 403) {
          let attemptedUid = "none";
          try { attemptedUid = uidTag(JSON.parse(body || "{}").userId); } catch { /* unparsable */ }
          // e.generic: do not surface internal reason (accountLock) to the caller.
          const msg = e.generic ? "Authentication failed" : e.message;
          console.warn(`mintToken: 403 (${e.message}) userId=${attemptedUid}`);
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end(msg);
        } else {
          sendServerError(res, "mintToken", e);
        }
      }
    });
    return;
  }

  // ── POST /requestAccess ──────────────────────────────────────────────────────
  //
  // Body: none required.
  //
  // Account creation is invite-only. A fresh install that wants a NEW account
  // calls this first to get a request token, which sits in Firestore as
  // "pending" until the operator manually approves it (Firebase console /
  // admin script — never from the app). The client polls GET /waitlistStatus
  // with the token and only proceeds to actual account creation once approved.
  // Restoring an EXISTING account never touches this endpoint.
  if (req.method === "POST" && req.url === "/requestAccess") {
    (async () => {
      try {
        const clientIp = getClientIp(req);
        if (!checkWaitlistIpRateLimit(clientIp)) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        // Drain the (empty) body so the connection closes cleanly.
        await readBody(req, res).catch(() => "");

        const requestId = crypto.randomBytes(16).toString("hex");
        await db.collection("waitlist").doc(requestId).set({
          status:    "pending",
          createdAt: FieldValue.serverTimestamp(),
        });

        console.log(`requestAccess: new waitlist entry requestId=${requestId}`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ requestId }));
      } catch (e) {
        console.error("requestAccess error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    })();
    return;
  }

  // ── GET /waitlistStatus?requestId=... ────────────────────────────────────────
  //
  // Returns { status: "pending" | "approved" | "used" | "not_found" }.
  // No auth required (the requestId itself is an unguessable 128-bit token,
  // and it reveals nothing beyond one account's own pending/approved state).
  if (req.method === "GET" && (req.url || "").split("?")[0] === "/waitlistStatus") {
    (async () => {
      try {
        const clientIp = getClientIp(req);
        // Use the dedicated poll bucket (60 hits / 15 min) so polling does
        // not drain the stricter /requestAccess creation bucket.
        if (!checkWaitlistPollRateLimit(clientIp)) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        const requestUrl = new URL(req.url, "http://localhost");
        const requestId = requestUrl.searchParams.get("requestId") || "";
        if (!/^[0-9a-f]{32}$/.test(requestId)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid requestId");
          return;
        }

        const snap = await db.collection("waitlist").doc(requestId).get();
        const status = snap.exists ? snap.data().status : "not_found";

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status }));
      } catch (e) {
        console.error("waitlistStatus error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    })();
    return;
  }

  // ── POST /migrateUid ─────────────────────────────────────────────────────────
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
          // S02-I3 FIX: pass checkRevoked=true so revoked tokens (e.g. from a
          // duress wipe or an admin-forced sign-out) are rejected immediately
          // rather than being accepted until their 1-hour JWT expiry.
          decodedToken = await admin.auth().verifyIdToken(idToken, true);
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
        const oldUserSnap = await db.collection("users").doc(oldUid).get();
        if (oldUserSnap.exists) {
          const data = oldUserSnap.data();
          if (data) {
            await db.collection("users").doc(userId).set(data);
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

  // ── POST /createChat ──────────────────────────────────────────────────────────
  //
  // Body (JSON): { myUid, partnerUid, myDisplayName, partnerDisplayName }
  // Auth: Firebase ID token in Authorization: Bearer <token> header.
  //
  // Security model:
  //   • Verifies the token with Firebase Admin SDK (auth.uid must equal myUid).
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
          decodedToken = await admin.auth().verifyIdToken(idToken, true); // S02-I3: checkRevoked
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
        const chatDocData = {
          participants: [myUid, partnerUid],
        };
        if (myDisplayName)      chatDocData["partnerName_" + partnerUid] = myDisplayName;
        if (partnerDisplayName) chatDocData["partnerName_" + myUid]      = partnerDisplayName;

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
          uid = (await admin.auth().verifyIdToken(idToken, true)).uid; // S02-I3: checkRevoked
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
        if (!MEDIA_KEY_FORMAT.test(key)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid key format");
          return;
        }

        // ── Authorization: caller must belong to the conversation ───────────
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
        // ── Auth ────────────────────────────────────────────────────────────
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }
        let turnUid;
        try {
          turnUid = (await admin.auth().verifyIdToken(idToken, true)).uid; // S02-I3: checkRevoked
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

        // ── Cloudflare credentials ───────────────────────────────────────────
        const tokenId  = process.env.TURN_TOKEN_ID  || "";
        const apiToken = process.env.TURN_API_TOKEN || "";
        if (!tokenId || !apiToken) {
          console.error("turnCredentials: TURN_TOKEN_ID or TURN_API_TOKEN not set");
          res.writeHead(503, { "Content-Type": "text/plain" });
          res.end("TURN not configured on server");
          return;
        }

        const cfUrl = `https://rtc.live.cloudflare.com/v1/turn/keys/${tokenId}/credentials/generate`;
        const cfRes = await fetch(cfUrl, {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${apiToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ ttl: 86400 }),
        });

        if (!cfRes.ok) {
          const text = await cfRes.text().catch(() => "");
          console.error(`turnCredentials: Cloudflare returned ${cfRes.status}: ${text}`);
          res.writeHead(502, { "Content-Type": "text/plain" });
          res.end("Cloudflare TURN error");
          return;
        }

        const data = await cfRes.json();
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

  // ── /linkPreview — server-side OG fetch (F12: prevents sender IP leakage) ──
  if (req.method === "POST" && req.url === "/linkPreview") {
    collectBody(req, res, async (body) => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        let lpUid;
        try { lpUid = (await admin.auth().verifyIdToken(tok, true)).uid; } // S02-I3: checkRevoked
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
          // S04-H2: 5 s timeout (down from 6 s) + body capped at 100 KB via readHtmlCapped.
          const { response: r, finalUrl } = await fetchFollowingSafeRedirects(targetUrl, {
            headers: { "User-Agent": "Mozilla/5.0 (compatible; DuoShield/1.0)" },
            timeoutMs: 5000,
          });
          const preview = { url: targetUrl, domain: parsed.hostname.replace(/^www\./, "") };
          if (r.ok && (r.headers.get("content-type") || "").includes("text/html")) {
            // S04-H2: readHtmlCapped streams at most LINK_PREVIEW_MAX_HTML_BYTES (100 KB)
            // and cancels the response body early instead of buffering the full page.
            const html = await readHtmlCapped(r, LINK_PREVIEW_MAX_HTML_BYTES);
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
              const rawTitle = ogT[1].trim().replace(/\s+/g, " ");
              preview.title = rawTitle
                .replace(/&amp;/gi,  "&")
                .replace(/&lt;/gi,   "<")
                .replace(/&gt;/gi,   ">")
                .replace(/&quot;/gi, '"')
                .replace(/&#39;/gi,  "'")
                .replace(/&apos;/gi, "'")
                .replace(/&#(\d{1,5});/g,   (_, dec) => String.fromCodePoint(parseInt(dec,  10)))
                .replace(/&#x([0-9a-f]{1,5});/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)));
            }
            if (ogI) {
              // S04-H3 / S08-H4: Do NOT return the raw og:image URL to the client — the
              // Android client would then load it directly, leaking the device IP to the
              // third-party image host. Instead return an opaque imageProxy URL that the
              // client can fetch safely: the server validates and proxies the image,
              // keeping the raw origin URL server-side. Only http(s) URLs are proxied.
              const rawImageUrl = ogI[1].trim();
              try {
                const imageUrlParsed = new URL(rawImageUrl, targetUrl); // resolve relative URLs
                if (["http:", "https:"].includes(imageUrlParsed.protocol)) {
                  // The imageUrl field now contains a server-relative proxy path, not the
                  // third-party URL. The client appends it to PUSH_SERVER_URL to load the image.
                  preview.imageUrl = "/imageProxy?url=" + encodeURIComponent(imageUrlParsed.href);
                }
              } catch {
                // Malformed image URL — silently omit it.
              }
            }
          }
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify(preview));
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
        try { callerUid = (await admin.auth().verifyIdToken(tok, true)).uid; } // S02-I3: checkRevoked
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

  // ── /requestLockNonce ─────────────────────────────────────────────────────
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
          uid = (await admin.auth().verifyIdToken(idToken, true)).uid; // S02-I3: checkRevoked
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

        // Generate a 32-byte random nonce and store it in Firestore with a 24-hour
        // expiry and the authenticated uid. Using Admin SDK so Firestore rules never
        // block these writes (the collection is deny-all for clients).
        const nonce = crypto.randomBytes(32).toString("hex");
        const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000);
        await db.collection("_duressNonces").doc(nonce).set({ uid, expiresAt });

        console.log(`[requestLockNonce] nonce issued for uid=${uid}`);
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
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }

        const { nonce } = parsed;
        if (typeof nonce !== "string" || nonce.length !== 64) {
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
            if (!nonceUid || new Date() > new Date(expiresAt.toDate ? expiresAt.toDate() : expiresAt)) {
              // Expired — delete to clean up and signal the client not to retry.
              tx.delete(nonceRef);
              throw Object.assign(new Error("Nonce expired"), { status: 401 });
            }
            tx.set(
              db.collection("accountLock").doc(nonceUid),
              { locked: true, lockedAt: admin.firestore.FieldValue.serverTimestamp() },
              { merge: true }
            );
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

        console.log(`[duress-lock] accountLock written for uid=${uid}`);
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
    collectBody(req, res, (body) => {
      const params = new URLSearchParams(body);
      const supplied = (params.get("token") || "").trim();
      const ip = getClientIp(req);
      if (adminIpLocked(ip)) {
        res.writeHead(303, { "Location": "/admin?error=locked", "Cache-Control": "no-store" });
        res.end();
        return;
      }
      if (!ADMIN_TOKEN) {
        console.error("admin login: ADMIN_TOKEN is not configured on the server");
        res.writeHead(303, { "Location": "/admin?error=unconfigured", "Cache-Control": "no-store" });
        res.end();
        return;
      }
      if (!supplied || !safeTokenEqual(supplied, ADMIN_TOKEN)) {
        recordAdminAuthFailure(ip);
        res.writeHead(303, { "Location": "/admin?error=invalid", "Cache-Control": "no-store" });
        res.end();
        return;
      }
      const sessionId = createAdminSession();
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
    if (sessionId) adminSessions.delete(sessionId);
    res.writeHead(303, {
      Location: "/admin",
      "Cache-Control": "no-store",
      "Set-Cookie": adminSessionCookie("", req, 0),
    });
    res.end();
    return;
  }

  if (req.method === "GET" && requestPath === "/admin") {
    const authenticated = hasValidAdminSession(req);
    // Generate a fresh 128-bit nonce for each response so the inline <script>
    // tag is the only code the browser will execute (blocks injected scripts).
    const nonce = crypto.randomBytes(16).toString("base64");
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
    );
    return;
  }

  // ── GET /admin/api/waitlist ──────────────────────────────────────��────────
  //
  // Auth: x-admin-token header. Returns pending waitlist requests, newest
  // first, so the operator can see who's asking for access.
  if (req.method === "GET" && req.url === "/admin/api/waitlist") {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const snap = await db.collection("waitlist")
          .where("status", "==", "pending")
          .orderBy("createdAt", "desc")
          .limit(200)
          .get();
        const requests = snap.docs.map((d) => {
          const data = d.data();
          const createdAt = data.createdAt && data.createdAt.toDate ? data.createdAt.toDate().toISOString() : null;
          return { requestId: d.id, createdAt };
        });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ requests }));
      } catch (e) {
        sendServerError(res, "admin/api/waitlist", e);
      }
    })();
    return;
  }

  // ── POST /admin/api/waitlist/approve ──────────────────────────────────────
  //
  // Body: { requestId }. Auth: x-admin-token header.
  // Flips a pending waitlist doc to status: "approved" so the requester's
  // next /waitlistStatus poll lets them proceed to account creation.
  if (req.method === "POST" && req.url === "/admin/api/waitlist/approve") {
    collectBody(req, res, async (body) => {
      if (!requireAdminAuth(req, res)) return;
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }
        const { requestId } = parsed;
        if (typeof requestId !== "string" || !/^[0-9a-f]{32}$/.test(requestId)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid requestId");
          return;
        }
        const ref = db.collection("waitlist").doc(requestId);
        const snap = await ref.get();
        if (!snap.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("Request not found");
          return;
        }
        if (snap.data().status !== "pending") {
          res.writeHead(409, { "Content-Type": "text/plain" });
          res.end(`Request is already "${snap.data().status}", not pending`);
          return;
        }
        await ref.update({ status: "approved", approvedAt: FieldValue.serverTimestamp() });
        console.log(`[admin] waitlist request approved: requestId=${requestId}`);

        // S05-H3: Durable audit write — awaited so a write failure is caught by
        // the outer try/catch and surfaces as a 500 rather than being silently
        // swallowed. Changed collection from "adminAuditLog" to "_adminAudit" so
        // the leading underscore marks it as server-internal (Firestore rules deny
        // all client reads/writes to underscore-prefixed collections).
        await db.collection("_adminAudit").add({
          action:    "waitlist_approved",
          requestId,
          adminIp:   getClientIp(req),
          at:        FieldValue.serverTimestamp(),
        });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        sendServerError(res, "admin/api/waitlist/approve", e);
      }
    });
    return;
  }

  // ── GET /admin/api/locked ─────────────────────────────────────────────────
  //
  // Auth: x-admin-token header. Returns currently-locked accounts so the
  // operator can see who's frozen and pick one to unfreeze.
  if (req.method === "GET" && req.url === "/admin/api/locked") {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const snap = await db.collection("accountLock")
          .where("locked", "==", true)
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

  // ── POST /admin/api/locked/unfreeze ───────────────────────────────────────
  //
  // Body: { uid }. Auth: x-admin-token header.
  // Deletes the accountLock/{uid} doc — the only way this doc can ever be
  // removed, per firestore.rules (clients get `allow delete: if false`).
  if (req.method === "POST" && req.url === "/admin/api/locked/unfreeze") {
    collectBody(req, res, async (body) => {
      if (!requireAdminAuth(req, res)) return;
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
        const snap = await ref.get();
        if (!snap.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("No lock found for this uid");
          return;
        }
        await ref.delete();
        console.log(`[admin] account unfrozen: uid=${uid}`);

        // S05-H3: Durable _adminAudit write (see waitlist_approved note above).
        await db.collection("_adminAudit").add({
          action:  "account_unfrozen",
          uid,
          adminIp: getClientIp(req),
          at:      FieldValue.serverTimestamp(),
        });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        sendServerError(res, "admin/api/locked/unfreeze", e);
      }
    });
    return;
  }

  // ── GET /admin/api/duress/enrolled ───────────────────────────────────────
  //
  // Auth: x-admin-token header. Returns all accounts currently enrolled for
  // duress-PIN eligibility (duressEligibility/{uid}.eligible == true).
  if (req.method === "GET" && req.url === "/admin/api/duress/enrolled") {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const snap = await db.collection("duressEligibility")
          .where("eligible", "==", true)
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

  // ── GET /admin/api/account/lookup?uid=... ────────────────────────────────
  //
  // Auth: x-admin-token header. Looks up whether an account with this UID
  // actually exists (identities/{uid}) and its current duress-PIN eligibility
  // status. Used by the admin panel's "search by UID" step before enabling —
  // enrollment must never be granted blind to a UID that isn't a real account.
  if (req.method === "GET" && req.url.startsWith("/admin/api/account/lookup")) {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const requestUrl = new URL(req.url, "http://localhost");
        const uid = requestUrl.searchParams.get("uid") || "";
        if (!uid || uid.length > 128) {
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

  // ── POST /admin/api/duress/enroll ─────────────────────────────────────────
  //
  // Body: { uid }. Auth: x-admin-token header.
  // Creates or updates duressEligibility/{uid} with eligible:true so the app
  // shows the secondary-PIN setup UI for that account on next eligibility check.
  // Requires the UID to correspond to a real account (identities/{uid}) —
  // enrollment is never granted blind to an unverified/nonexistent UID.
  if (req.method === "POST" && req.url === "/admin/api/duress/enroll") {
    collectBody(req, res, async (body) => {
      if (!requireAdminAuth(req, res)) return;
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
        console.log(`[admin] duress enrollment granted: uid=${uid}`);

        // S05-H3: Durable _adminAudit write.
        await db.collection("_adminAudit").add({
          action:  "duress_enrolled",
          uid,
          adminIp: getClientIp(req),
          at:      FieldValue.serverTimestamp(),
        });

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
  // Body: { uid }. Auth: x-admin-token header.
  // Sets eligible:false on duressEligibility/{uid} — the client's cached flag
  // is updated on the next eligibility refresh (sign-in or foreground).
  if (req.method === "POST" && req.url === "/admin/api/duress/revoke") {
    collectBody(req, res, async (body) => {
      if (!requireAdminAuth(req, res)) return;
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
        console.log(`[admin] duress enrollment revoked: uid=${uid}`);

        // S05-H3: Durable _adminAudit write.
        await db.collection("_adminAudit").add({
          action:  "duress_revoked",
          uid,
          adminIp: getClientIp(req),
          at:      FieldValue.serverTimestamp(),
        });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        sendServerError(res, "admin/api/duress/revoke", e);
      }
    });
    return;
  }

  // ── GET /admin/api/auditlog ───────────────────────────────────────────────
  //
  // Auth: x-admin-token header. Returns the 100 most-recent admin actions
  // (waitlist approvals + account unfreezes) so the operator has a tamper-
  // evident record of who did what and when.
  if (req.method === "GET" && req.url === "/admin/api/auditlog") {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const snap = await db.collection("_adminAudit")
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

}).listen(PORT, () => console.log(`Push server listening on port ${PORT}`));

// S04-I2: b2PresignUrl helper removed — all three B2 routes (b2PresignedPut,
// b2PresignedGet, b2Delete) were dead code: the routes were never wired into
// the HTTP handler and the function had no callers. The rate-limit entries
// for those endpoints were removed at the AUTH_RATE_LIMITS declaration above.
