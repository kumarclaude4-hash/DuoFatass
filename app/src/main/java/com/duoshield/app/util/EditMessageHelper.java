package com.duoshield.app.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.duoshield.app.crypto.signal.SignalCipherHelper;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class EditMessageHelper {

    private static final long EDIT_WINDOW_MS = 48 * 60 * 60 * 1000L;

    public static boolean canEdit(long timestamp, String sender, String myUid) {
        return myUid.equals(sender)
            && (System.currentTimeMillis() - timestamp) < EDIT_WINDOW_MS;
    }

    /**
     * Re-encrypts {@code newText} via the Signal ratchet session with
     * {@code partnerUid} and updates the Firestore message document.
     *
     * <p>Runs encryption on a background thread; any Toast is posted back to
     * the main thread via a {@link Handler}.
     *
     * @param ctx        Activity/Application context (must not be null)
     * @param convId     Firestore conversation document ID
     * @param messageId  Firestore message document ID to update
     * @param partnerUid Firebase UID of the conversation partner (needed by Signal)
     * @param newText    Plaintext replacement body
     */
    public static void editMessage(Context ctx, String convId, String messageId,
                                   String partnerUid, String newText) {
        if (partnerUid == null) {
            Toast.makeText(ctx, "Edit failed — partner not identified.", Toast.LENGTH_SHORT).show();
            return;
        }
        Handler ui = new Handler(Looper.getMainLooper());
        SharedExecutors.executeSerial(ctx, () -> {
            try {
                SignalCipherHelper.EncryptResult r =
                        SignalCipherHelper.encrypt(ctx, partnerUid, newText);
                
                // F-05 fix: guard against null encryption output. If encryption fails,
                // r will be null. Proceeding would write null to Firestore, silently
                // destroying the message content.
                if (r == null || r.ciphertextB64 == null) {
                    ui.post(() -> Toast.makeText(ctx,
                            "Edit failed — encryption not ready. Please try again.",
                            Toast.LENGTH_SHORT).show());
                    return;
                }
                
                Map<String, Object> updates = new HashMap<>();
                updates.put("text",    r.ciphertextB64);
                updates.put("sigType", r.sigType);
                updates.put("edited",  true);

                // Write Room DB FIRST so local state is immediately consistent even
                // when Firestore is unreachable (BUG-T04). The encrypted ciphertext
                // is stored in Firestore; Room stores the plaintext for fast local display.
                com.duoshield.app.db.AppDatabase.getInstance(ctx)
                    .messageDao().updateText(messageId, newText);

                FirebaseFirestore.getInstance()
                    .collection("chats").document(convId)
                    .collection("messages").document(messageId)
                    .update(updates)
                    .addOnFailureListener(e ->
                        ui.post(() -> Toast.makeText(ctx,
                            "Edit saved locally — cloud sync failed. Retry when online.",
                            Toast.LENGTH_SHORT).show()));
            } catch (Exception e) {
                android.util.Log.e("EditMessageHelper", "Encryption failed for edit" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                ui.post(() -> Toast.makeText(ctx,
                        "Edit failed — encryption error. Please try again.",
                        Toast.LENGTH_SHORT).show());
            }
        });
    }
}
