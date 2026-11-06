package com.duoshield.app.notifications;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DuoShieldMessagingService extends FirebaseMessagingService {

    private static final String TAG = "DuoShieldFCM";

    /**
     * Dedup set — prevents showing two notifications for the same messageId if two
     * pushes arrive within the same process lifetime.  Bounded at 100 entries.
     */
    private static final Set<String> shownMessageIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onNewToken(@NonNull String token) {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();
        uploadTokenWithRetry(token, 0);
    }

    private void uploadTokenWithRetry(String token, int attempt) {
        String myUid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (myUid == null) {
            SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
            myUid = prefs.getString("my_uid", null);
        }
        if (myUid != null && !myUid.isEmpty()) {
            // Legacy single-token field (existing delivery path).
            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(myUid)
                    .set(Collections.singletonMap("fcmToken", token), SetOptions.merge());

            // Per-device registry (S08-H5 item 4c): keep every device reachable
            // so the server's restore-race warning has a target on token refresh.
            String deviceId = com.duoshield.app.util.DeviceIdProvider.get(this);
            Map<String, Object> dev = new HashMap<>();
            dev.put("fcmToken",  token);
            dev.put("platform",  "android");
            dev.put("updatedAt", FieldValue.serverTimestamp());
            FirebaseFirestore.getInstance()
                    .collection("users").document(myUid)
                    .collection("devices").document(deviceId)
                    .set(dev, SetOptions.merge());
        } else if (attempt < 3) {
            long delayMs = 2000L * (1L << attempt);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> uploadTokenWithRetry(token, attempt + 1), delayMs);
        } else {
            Log.w(TAG, "onNewToken: uid still null after 3 retries — token not uploaded yet");
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // ── 0. Handle call_invite separately before message flow ─────────────
        String msgType = remoteMessage.getData().get("type");
        if ("call_invite".equals(msgType)) {
            handleCallInvite(remoteMessage.getData());
            return;
        }

        // ── 1. Device-level delivery acknowledgement ──────────────────────────
        // For group messages the server sends chatId=groupId + type="new_group_message".
        // Route the ACK to the correct Firestore collection based on message type.
        String chatId    = remoteMessage.getData().get("chatId");
        String messageId = remoteMessage.getData().get("messageId");
        if (chatId != null && !chatId.isEmpty()
                && messageId != null && !messageId.isEmpty()) {
            acknowledgeDelivery(chatId, messageId, msgType);
        }

        // ── 2. Dedup: skip duplicate pushes for the same message ID ──────────
        if (messageId != null && !messageId.isEmpty()) {
            if (shownMessageIds.size() > 100) shownMessageIds.clear();
            if (!shownMessageIds.add(messageId)) {
                Log.d(TAG, "Duplicate FCM push for " + messageId + " — notification suppressed");
                return;
            }
        }

        // ── 3. Exact-chat suppression ───────────────────────────────────────
        // A foreground app is not enough to suppress a notification: the user may
        // be on the conversation list or another screen and still needs the alert.
        // Only the exact resumed chat is silent, like WhatsApp. This check is after
        // deduplication so a delayed duplicate cannot notify after the chat closes.
        if (chatId != null && NotificationVisibility.isConversationOpen(chatId)) {
            Log.d(TAG, "Message notification suppressed: conversation is open (" + chatId + ")");
            return;
        }

        // ── 4. Show notification (if enabled) ────────────────────────────────
        boolean notificationsEnabled = prefs.getBoolean("notifications_enabled", true);
        if (!notificationsEnabled) return;

        // Local fallback suppression: the primary defense is de-registering the FCM
        // token on sign-out (see FcmUnregisterWorker), but that write is deliberately
        // delayed and jittered, so there's a short window right after a security
        // sign-out where a push could still land on this device. If the wipe/sign-out
        // sequence is still in flight, surfacing a notification here would visibly
        // contradict the "freshly signed out" appearance the sign-out is trying to
        // present, so suppress it.
        if (com.duoshield.app.security.DuressManager.isResetPending(this)) {
            Log.d(TAG, "Sign-out in progress — notification suppressed");
            return;
        }

        // Sender name from data payload (set by push server from Firestore displayName).
        // Falls back to "DuoShield" for privacy if not present.
        String senderName = remoteMessage.getData().get("senderName");
        String title = (senderName != null && !senderName.isEmpty()) ? senderName : "DuoShield";

        // Privacy default: FCM payloads may contain a preview for delivery UX, but it must
        // never reach the lock screen unless the user explicitly enables previews locally.
        String body = "New encrypted message";
        boolean previewsEnabled = prefs.getBoolean("notification_previews_enabled", false);
        if (previewsEnabled) {
            String dataBody = remoteMessage.getData().get("body");
            if (dataBody != null && !dataBody.isEmpty()) body = dataBody;
            if (remoteMessage.getNotification() != null) {
                String nb = remoteMessage.getNotification().getBody();
                if (nb != null && !nb.isEmpty() && body.equals("New encrypted message")) body = nb;
            }
        }

        String senderUid = remoteMessage.getData().get("senderUid");
        NotificationHelper.showNotification(this, title, body, chatId, senderUid);
    }

    private void handleCallInvite(Map<String, String> data) {
        String callId     = data.get("callId");
        String callerId   = data.get("callerId");
        String callerName = data.get("callerName");
        String isVideoStr = data.get("isVideo");
        boolean isVideo   = "true".equals(isVideoStr);

        if (callId == null || callerId == null) {
            Log.w(TAG, "call_invite missing callId or callerId — ignored");
            return;
        }
        if (callerName == null || callerName.isEmpty()) callerName = "DuoShield";

        // Register in the shared dedup set BEFORE showing the notification so that
        // IncomingCallWatcher (the Firestore-based backup path) does not fire a
        // second ringing screen for the same call.
        com.duoshield.app.call.IncomingCallWatcher.markShown(callId);

        // Show full-screen intent notification (works from background/killed state)
        NotificationHelper.createChannel(this);
        NotificationStyler.showIncomingCall(this, callerName, callId, callerId, isVideo);

        // FIX #6: Removed dead CALL_INVITE LocalBroadcast — no receiver was ever registered
        // for "com.duoshield.app.CALL_INVITE", so the broadcast was silently discarded
        // on every incoming call. The full-screen intent above covers all entry points.

        Log.d(TAG, "call_invite handled: callId=" + callId + " caller=" + callerName);
    }

    private void acknowledgeDelivery(String chatId, String messageId, String msgType) {
        // The FCM data payload can still land after the recipient has already opened
        // the chat and marked the message "read" (e.g. the push was delayed, or the
        // chat was foregrounded from a different trigger). An unconditional
        // update({"status":"delivered"}) here would clobber that "read" status back
        // down to "delivered", which is what caused the sender's tick to silently
        // revert / never show real-time read receipts on one side. Guard the write
        // in a transaction so delivery ACKs can only ever move the status forward.
        //
        // Group messages (type="new_group_message") use the groups/ collection;
        // 1-to-1 messages use the chats/ collection.
        boolean isGroup = "new_group_message".equals(msgType);
        String topCollection = isGroup ? "groups" : "chats";
        com.google.firebase.firestore.DocumentReference ref = FirebaseFirestore.getInstance()
                .collection(topCollection).document(chatId)
                .collection("messages").document(messageId);

        FirebaseFirestore.getInstance().runTransaction(txn -> {
            com.google.firebase.firestore.DocumentSnapshot snap = txn.get(ref);
            String currentStatus = snap.getString("status");
            if ("read".equals(currentStatus) || "delivered".equals(currentStatus)) {
                return null; // already at or past "delivered" — never downgrade
            }
            Map<String, Object> update = new HashMap<>();
            update.put("status", "delivered");
            update.put("deliveredAt", FieldValue.serverTimestamp());
            txn.update(ref, update);
            return null;
        })
                .addOnSuccessListener(v ->
                        Log.d(TAG, "Delivery ACK written: " + messageId))
                .addOnFailureListener(e ->
                        Log.w(TAG, "Delivery ACK failed (non-critical): " + com.duoshield.app.util.LogRedact.error(e)));
    }
}
