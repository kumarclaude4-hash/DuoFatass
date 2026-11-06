package com.duoshield.app.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.OutboxMessage;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Retries encrypted message writes after process death or transient network failure. */
public final class MessageOutboxWorker extends Worker {
    private static final String TAG = "MessageOutboxWorker";
    private static final String UNIQUE_WORK = "duoshield-message-outbox";
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 8;

    public MessageOutboxWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MessageOutboxWorker.class)
                .setConstraints(constraints)
                // Foreground send paths write directly to Firestore after enqueueing the
                // durable row. Give that write time to finish so the worker is recovery-only,
                // not a competing second writer for the same document ID.
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(UNIQUE_WORK)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppDatabase database = null;
        try {
            database = AppDatabase.getInstance(getApplicationContext());
            List<OutboxMessage> pending = database.outboxDao()
                    .getReady(System.currentTimeMillis(), BATCH_SIZE);
            if (pending.isEmpty()) return Result.success();

            FirebaseFirestore firestore = FirebaseFirestore.getInstance();
            String activeUid = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
            boolean retry = false;
            for (OutboxMessage item : pending) {
                // Never send an envelope created by a previous account on this device.
                if (activeUid == null || !activeUid.equals(item.senderUid)) {
                    database.outboxDao().delete(item.id);
                    continue;
                }
                if (isStopped()) return Result.retry();
                try {
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("id", item.id);
                    doc.put("conversationId", item.conversationId);
                    doc.put("sender", item.senderUid);
                    doc.put("text", item.ciphertext);
                    doc.put("isEncrypted", true);
                    doc.put("sigType", item.sigType);
                    String messageType = item.messageType != null ? item.messageType : "text";
                    doc.put("type", messageType);
                    if (item.mediaPath != null && !item.mediaPath.isEmpty()) {
                        doc.put("path", item.mediaPath);
                        doc.put("mediaType", messageType);
                        doc.put("mediaKey", item.mediaKey);
                        if (item.thumbnail != null && !item.thumbnail.isEmpty()) {
                            doc.put("thumb", item.thumbnail);
                        }
                        if (item.caption != null && !item.caption.isEmpty()) {
                            doc.put("caption", item.caption);
                        }
                        if (item.chunked) doc.put("chunked", true);
                        if ("voice".equals(messageType)) {
                            if (item.waveformJson != null && !item.waveformJson.isEmpty()) {
                                try {
                                    org.json.JSONArray arr = new org.json.JSONArray(item.waveformJson);
                                    ArrayList<Integer> amplitudes = new ArrayList<>();
                                    for (int i = 0; i < arr.length(); i++) {
                                        amplitudes.add(arr.optInt(i, 0));
                                    }
                                    doc.put("amplitudes", amplitudes);
                                } catch (org.json.JSONException ignored) {
                                    Log.w(TAG, "Ignoring malformed voice waveform for " + com.duoshield.app.util.LogRedact.metadata(item.id));
                                }
                            }
                            if (item.durationMs > 0) doc.put("durationMs", item.durationMs);
                        }
                    }
                    doc.put("status", "sent");
                    doc.put("expiresAt", item.expiresAt);
                    if (item.replyToId != null) doc.put("replyToId", item.replyToId);
                    if (item.replyPreview != null) doc.put("replyPreview", item.replyPreview);
                    doc.put("timestamp", FieldValue.serverTimestamp());

                    Tasks.await(firestore.collection("chats").document(item.conversationId)
                            .collection("messages").document(item.id).set(doc),
                            30, TimeUnit.SECONDS);
                    database.outboxDao().delete(item.id);
                    // The realtime listener may be detached after process death. Reconcile the
                    // local optimistic row immediately so a successful retry is not left visibly
                    // failed until the chat is opened again.
                    database.messageDao().updateStatus(item.id, "sent");
                } catch (Exception error) {
                    retry = true;
                    int attempts = item.attemptCount + 1;
                    long delay = Math.min(TimeUnit.HOURS.toMillis(6),
                            TimeUnit.SECONDS.toMillis(15L << Math.min(attempts, 8)));
                    database.outboxDao().recordFailure(item.id, attempts,
                            System.currentTimeMillis() + delay, safeMessage(error));
                    Log.w(TAG, "Retry failed for message " + com.duoshield.app.util.LogRedact.metadata(item.id) + " (attempt " + attempts + ")");
                    if (attempts >= MAX_ATTEMPTS) {
                        // Keep the row for manual recovery/diagnostics, but do not hot-loop.
                        database.outboxDao().recordFailure(item.id, attempts,
                                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(6),
                                "Retry limit reached: " + safeMessage(error));
                    }
                }
            }
            return retry ? Result.retry() : Result.success();
        } catch (Exception error) {
            Log.w(TAG, "Outbox worker unavailable; retrying" + " (" + com.duoshield.app.util.LogRedact.error(error) + ")");
            return Result.retry();
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "temporary failure" : message;
    }
}
