package com.duoshield.app.util;

import android.content.Context;

import com.duoshield.app.models.Message;
import com.duoshield.app.util.FirebaseCostGuard;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes delivery and read receipt status updates to Firestore in batches.
 *
 * <h3>Status ladder</h3>
 * <ul>
 *   <li>{@code pending} — optimistic local insert, not yet written to Firestore</li>
 *   <li>{@code sent} — Firestore write acknowledged</li>
 *   <li>{@code delivered} — FCM reached partner's device
 *       (set by {@link com.duoshield.app.notifications.DuoShieldMessagingService}
 *       on device receipt, and belt-and-suspenders by the Cloud Function when FCM
 *       is queued)</li>
 *   <li>{@code read} — partner has the chat open and saw the message
 *       (set by {@link #markRead} when {@code ChatMediaActivity} is in the foreground)</li>
 * </ul>
 */
public class DeliveryReceiptHelper {

    /**
     * Advances incoming messages from {@code sent} → {@code delivered}.
     * Used when the app is in the <em>background</em> and only the FCM
     * notification woke it; the foreground path uses {@link #markRead} directly.
     */
    public static void markDelivered(Context context, String convId, List<Message> messages, String myUid) {
        if (context == null || messages == null || messages.isEmpty()) return;
        int expectedWrites = 0;
        for (Message message : messages) {
            if (!myUid.equals(message.getSender())
                    && !"delivered".equals(message.getStatus())
                    && !"read".equals(message.getStatus())) expectedWrites++;
        }
        if (expectedWrites == 0 || !FirebaseCostGuard.getInstance(context).reserveWrite(expectedWrites)) return;
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        int count = 0;
        for (Message m : messages) {
            if (!myUid.equals(m.getSender())
                    && !"delivered".equals(m.getStatus())
                    && !"read".equals(m.getStatus())) {
                batch.update(
                    db.collection("chats").document(convId)
                      .collection("messages").document(m.getId()),
                    "status", "delivered");
                if (++count == 450) {
                    batch.commit();
                    batch = db.batch();
                    count = 0;
                }
            }
        }
        if (count > 0) batch.commit();
    }

    /**
     * Advances OUR own messages to {@code delivered} by ID, without needing the full Message
     * object. Used when the partner sends a new message (proving they have the chat open),
     * so we know they received our previous messages. Writes to Firestore so the status
     * persists across app restarts.
     */
    public static void markDeliveredByIds(Context context, String convId, List<String> messageIds) {
        if (context == null || convId == null || messageIds == null || messageIds.isEmpty()) return;
        int expectedWrites = 0;
        for (String id : messageIds) if (id != null) expectedWrites++;
        if (expectedWrites == 0 || !FirebaseCostGuard.getInstance(context).reserveWrite(expectedWrites)) return;
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        int count = 0;
        for (String id : messageIds) {
            if (id == null) continue;
            batch.update(
                db.collection("chats").document(convId)
                  .collection("messages").document(id),
                "status", "delivered");
            if (++count == 450) {
                batch.commit();
                batch = db.batch();
                count = 0;
            }
        }
        if (count > 0) batch.commit();
    }

    /**
     * Advances incoming messages to {@code read}.
     *
     * <p>Call this when {@code ChatMediaActivity} is in the foreground and the
     * user can see the messages. The sender will see the tick turn teal (✓✓ blue).
     * Messages already {@code read} are skipped to avoid unnecessary writes.
     */
    public static void markRead(Context context, String convId, List<Message> messages, String myUid) {
        if (context == null || messages == null || messages.isEmpty()) return;
        int expectedWrites = 1; // includes last_read_<uid> conversation signal
        for (Message message : messages) {
            if (!myUid.equals(message.getSender()) && !"read".equals(message.getStatus())) expectedWrites++;
        }
        if (!FirebaseCostGuard.getInstance(context).reserveWrite(expectedWrites)) return;
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        int count = 0;
        for (Message m : messages) {
            if (!myUid.equals(m.getSender())
                    && !"read".equals(m.getStatus())) {
                Map<String, Object> update = new HashMap<>();
                update.put("status", "read");
                update.put("readAt", FieldValue.serverTimestamp());
                batch.update(
                    db.collection("chats").document(convId)
                      .collection("messages").document(m.getId()),
                    update);
                if (++count == 450) {
                    batch.commit();
                    batch = db.batch();
                    count = 0;
                }
            }
        }
        if (count > 0) batch.commit();

        // Write a "last_read_<myUid>" timestamp to the conversation doc.
        // The sender's listenForConvUpdates() listener watches this field and
        // retroactively marks older messages (outside the startAfter() window)
        // as read in the adapter + Room DB, fixing the missing-blue-ticks bug.
        Map<String, Object> readSignal = new HashMap<>();
        readSignal.put("last_read_" + myUid, FieldValue.serverTimestamp());
        db.collection("chats").document(convId)
          .update(readSignal)
          .addOnFailureListener(e -> {
              // Conv doc may not exist yet; use set with merge as fallback.
              // Reserve separately because this second write only occurs on failure.
              if (FirebaseCostGuard.getInstance(context).reserveWrite(1)) {
                  db.collection("chats").document(convId)
                    .set(readSignal, com.google.firebase.firestore.SetOptions.merge());
              }
          });
    }
}
