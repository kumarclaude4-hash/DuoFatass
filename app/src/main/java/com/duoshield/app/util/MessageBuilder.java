package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.duoshield.app.crypto.signal.SignalCipherHelper;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.util.ConversationMetaUpdater;
import com.duoshield.app.util.FirebaseCostGuard;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Utility for sending text messages outside of {@code ChatMediaActivity}
 * (e.g. from {@code MessageReplyReceiver}, {@code ForwardMessageHelper}).
 *
 * <p>Uses {@link SignalCipherHelper} for encryption. If no Signal session
 * exists yet, the send is aborted silently — the caller should inform the
 * user to open the app to re-establish the session.
 *
 * <p>Reads {@code disappear_ms} from {@code duoshield_prefs} and sets
 * {@code expiresAt} on both the local Room row and the Firestore doc so that
 * {@link com.duoshield.app.db.SelfDestructWorker} can clean up on schedule.
 */
public class MessageBuilder {

    private static final String TAG = "MessageBuilder";

    /** Truncates a plaintext message to an 80-char conversation-list preview. */
    private static String previewFor(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    /** Convenience overload — not forwarded (normal reply, notification reply, etc.). */
    public static void sendTextMessage(Context ctx, String convId, String myUid,
                                       String partnerUid, String text,
                                       String replyToId, String replyPreview) {
        sendTextMessage(ctx, convId, myUid, partnerUid, text, replyToId, replyPreview, false);
    }

    public static void sendTextMessage(Context ctx, String convId, String myUid,
                                       String partnerUid, String text,
                                       String replyToId, String replyPreview,
                                       boolean forwarded) {
        if (!SignalKeyManager.isInitialized(ctx)) {
            Log.w(TAG, "sendTextMessage: Signal keys not initialised — message NOT sent.");
            return;
        }

        SharedExecutors.executeSerial(ctx, () -> {
            try {
                SignalCipherHelper.EncryptResult r =
                        SignalCipherHelper.encrypt(ctx, partnerUid, text);

                String msgId = UUID.randomUUID().toString();
                long   now   = System.currentTimeMillis();

                // F26 fix: read per-conversation disappear_ms pref
                SharedPreferences prefs =
                        ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
                long disappearMs = prefs.getLong("disappear_ms_" + convId, 0);
                long expiresAt   = disappearMs > 0 ? now + disappearMs : 0;

                // ── Local Room insert ─────────────────────────────────────────
                Message local = new Message();
                local.setId(msgId);
                local.setConversationId(convId);
                local.setSender(myUid);
                local.setText(text);
                local.setTimestamp(now);
                local.setStatus("pending");
                local.expiresAt = expiresAt;
                local.forwarded = forwarded;
                AppDatabase.getInstance(ctx).messageDao().insert(local);
                BackupManager.backup(ctx, local);

                // ── Firestore doc ─────────────────────────────────────────────
                Map<String, Object> doc = new HashMap<>();
                doc.put("id",          msgId);
                doc.put("sender",      myUid);
                doc.put("text",        r.ciphertextB64);
                doc.put("sigType",     r.sigType);
                doc.put("type",        "text");
                doc.put("timestamp",   FieldValue.serverTimestamp());
                doc.put("status",      "sent");
                doc.put("isEncrypted", true);
                doc.put("expiresAt",   expiresAt);
                if (replyToId != null) {
                    doc.put("replyToId",    replyToId);
                    doc.put("replyPreview", replyPreview != null ? replyPreview : "");
                }
                if (forwarded) doc.put("forwarded", true);

                FirebaseCostGuard guard = FirebaseCostGuard.getInstance(ctx);
                if (!guard.reserveWrite(1)) {
                    AppDatabase.getInstance(ctx).messageDao().updateStatus(msgId, "failed");
                    return;
                }
                FirebaseFirestore.getInstance()
                    .collection("chats").document(convId)
                    .collection("messages").document(msgId)
                    .set(doc)
                    .addOnSuccessListener(v -> {
                        AppDatabase.getInstance(ctx).messageDao().updateStatus(msgId, "sent");
                        ConversationMetaUpdater.update(ctx, convId, myUid, partnerUid,
                            previewFor(text));
                        if (guard.reserveWrite(1)) {
                            FirebaseFirestore.getInstance().collection("chats").document(convId)
                                .update("lastActivity", FieldValue.serverTimestamp());
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "sendTextMessage: Firestore set failed for msgId=" + com.duoshield.app.util.LogRedact.metadata(msgId) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                        AppDatabase.getInstance(ctx).messageDao().updateStatus(msgId, "failed");
                    });

            } catch (Exception e) {
                Log.e(TAG, "sendTextMessage: Signal encryption failed — message NOT sent" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        });
    }

    /**
     * Sends a media message (image / video / voice) by re-using an existing
     * storage path.  Used by {@link ForwardMessageHelper} to forward
     * media.
     *
     * @param storagePath Storage path of the already-uploaded file
     * @param mediaType   "image", "video", or "voice"
     * @param mediaKey    Base64 AES-256-GCM key; null for unencrypted legacy files
     */
    /** Convenience overload — not forwarded (normal media send). */
    public static void sendMediaMessage(Context ctx, String convId, String myUid,
                                        String partnerUid, String storagePath,
                                        String mediaType, String mediaKey) {
        sendMediaMessage(ctx, convId, myUid, partnerUid, storagePath, mediaType, mediaKey, false);
    }

    public static void sendMediaMessage(Context ctx, String convId, String myUid,
                                        String partnerUid, String storagePath,
                                        String mediaType, String mediaKey,
                                        boolean forwarded) {
        if (storagePath == null || storagePath.isEmpty()) {
            Log.w(TAG, "sendMediaMessage: storagePath is null — message NOT sent.");
            return;
        }

        SharedExecutors.executeSerial(ctx, () -> {
            String msgId  = UUID.randomUUID().toString();
            long   now    = System.currentTimeMillis();

            SharedPreferences prefs =
                    ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
            // F26 fix: read per-conversation disappear_ms pref
            long disappearMs = prefs.getLong("disappear_ms_" + convId, 0);
            long expiresAt   = disappearMs > 0 ? now + disappearMs : 0;

            // ── Local Room insert ──────────────────────────────────────────
            Message local = new Message(msgId, convId, myUid, "", now, false,
                    storagePath, mediaType);
            local.setStatus("pending");
            local.setExpiresAt(expiresAt);
            if (mediaKey != null) local.setMediaKey(mediaKey);
            local.forwarded = forwarded;
            AppDatabase.getInstance(ctx).messageDao().insert(local);
            BackupManager.backup(ctx, local);

            // ── Firestore doc ──────────────────────────────────────────────
            Map<String, Object> doc = new HashMap<>();
            doc.put("id",          msgId);
            doc.put("conversationId", convId);
            doc.put("sender",      myUid);
            doc.put("text",        "");
            doc.put("path",        storagePath);
            doc.put("mediaType",   mediaType);
            doc.put("type",        mediaType);
            doc.put("isEncrypted", mediaKey != null);
            if (mediaKey != null) doc.put("mediaKey", mediaKey);
            doc.put("expiresAt",   expiresAt);
            doc.put("timestamp",   FieldValue.serverTimestamp());
            doc.put("status",      "sent");
            if (forwarded) doc.put("forwarded", true);

            FirebaseCostGuard guard = FirebaseCostGuard.getInstance(ctx);
            if (!guard.reserveWrite(1)) {
                AppDatabase.getInstance(ctx).messageDao().updateStatus(msgId, "failed");
                return;
            }
            FirebaseFirestore.getInstance()
                .collection("chats").document(convId)
                .collection("messages").document(msgId)
                .set(doc)
                .addOnSuccessListener(v -> {
                    AppDatabase.getInstance(ctx).messageDao().updateStatus(msgId, "sent");
                    String preview = "voice".equals(mediaType) ? "Sent a voice note 🎤"
                            : "video".equals(mediaType) ? "Sent a video 🎬"
                            : "Sent a photo 🖼";
                    ConversationMetaUpdater.update(ctx, convId, myUid, partnerUid, preview);
                    if (guard.reserveWrite(1)) {
                        FirebaseFirestore.getInstance().collection("chats").document(convId)
                            .update("lastActivity", FieldValue.serverTimestamp());
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "sendMediaMessage: Firestore set failed for msgId=" + com.duoshield.app.util.LogRedact.metadata(msgId) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                    AppDatabase.getInstance(ctx).messageDao().updateStatus(msgId, "failed");
                });
        });
    }
}
