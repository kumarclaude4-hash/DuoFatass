package com.duoshield.app.db;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.duoshield.app.util.B2StorageHelper;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One-time WorkManager job that deletes a B2 media file 24 hours after upload.
 *
 * <p>When a media message is successfully written to Firestore, the sender's device
 * schedules this worker with a 24-hour initial delay. On execution it:
 * <ol>
 *   <li>Deletes the encrypted blob from B2 (404 is treated as success).</li>
 *   <li>Updates the Firestore message doc: nulls out "path" and sets "mediaExpired" = true
 *       so both devices can show an "Media expired" placeholder instead of a broken thumbnail.</li>
 * </ol>
 *
 * <p>Only the sender's device schedules the cleanup; the recipient's device reacts to the
 * Firestore MODIFIED event ({@code mediaExpired == true}) through the existing listener.
 */
public class B2CleanupWorker extends Worker {

    private static final String TAG = "B2CleanupWorker";

    public static final String DATA_B2_PATH    = "b2_path";
    public static final String DATA_CHAT_ID    = "chat_id";
    public static final String DATA_MESSAGE_ID = "message_id";

    public B2CleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String b2Path    = getInputData().getString(DATA_B2_PATH);
        String chatId    = getInputData().getString(DATA_CHAT_ID);
        String messageId = getInputData().getString(DATA_MESSAGE_ID);

        if (b2Path == null || b2Path.isEmpty()) {
            Log.w(TAG, "No b2_path provided — nothing to clean up.");
            return Result.success();
        }

        // F10 fix: verify path belongs to the conversation it was scheduled for.
        // B2CleanupWorker is always scheduled by the sender's own device with the path it
        // just uploaded, so in practice chatId always matches. The check here is
        // defense-in-depth against a tampered WorkManager job input or a future code path
        // that might schedule from external data.
        if (!B2StorageHelper.isOwnedB2Path(b2Path, chatId)) {
            Log.e(TAG, "F10: refusing to delete B2 path '" + com.duoshield.app.util.LogRedact.metadata(b2Path)
                    + "' — not owned by chat '" + com.duoshield.app.util.LogRedact.metadata(chatId) + "'. Dropping job.");
            return Result.success(); // don't retry — this path should never be trusted
        }

        try {
            B2StorageHelper.deleteFile(b2Path);
            Log.d(TAG, "B2 file deleted after 24 h: " + b2Path);
        } catch (Exception e) {
            Log.e(TAG, "B2 delete failed — will retry: " + com.duoshield.app.util.LogRedact.metadata(b2Path) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return Result.retry();
        }

        if (chatId != null && !chatId.isEmpty() && messageId != null && !messageId.isEmpty()) {
            try {
                Map<String, Object> update = new HashMap<>();
                update.put("path", null);
                update.put("mediaExpired", true);
                Tasks.await(
                    FirebaseFirestore.getInstance()
                        .collection("chats").document(chatId)
                        .collection("messages").document(messageId)
                        .update(update),
                    15, TimeUnit.SECONDS
                );
                Log.d(TAG, "Firestore path cleared for message: " + messageId);
            } catch (Exception e) {
                Log.w(TAG, "Firestore path clear failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
            }
        }

        return Result.success();
    }

    /**
     * Schedules a one-time 24-hour-delayed B2 cleanup for a just-uploaded media file.
     * Safe to call from any thread — WorkManager queues the request asynchronously.
     */
    public static void schedule(Context ctx, String b2Path, String chatId, String messageId) {
        if (b2Path == null || b2Path.isEmpty() || !B2StorageHelper.isB2Path(b2Path)) return;

        Data data = new Data.Builder()
                .putString(DATA_B2_PATH,    b2Path)
                .putString(DATA_CHAT_ID,    chatId)
                .putString(DATA_MESSAGE_ID, messageId)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(B2CleanupWorker.class)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setInputData(data)
                .addTag("b2_cleanup_" + messageId)
                .build();

        WorkManager.getInstance(ctx).enqueue(req);
        Log.d(TAG, "B2 cleanup scheduled in 24 h for: " + b2Path);
    }
}
