package com.duoshield.app.db;

import android.content.SharedPreferences;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.duoshield.app.security.PinKeyGate;
import com.duoshield.app.security.SessionKeyHolder;
import com.duoshield.app.util.B2StorageHelper;
import com.duoshield.app.util.FirebaseCostGuard;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.List;

/**
 * Periodic WorkManager job that deletes expired messages from both the local
 * Room database and the Firestore conversation sub-collection.
 *
 * <p>Deletion is driven by per-message expiry: {@code message.expiresAt} — an absolute
 * epoch-millisecond deadline written at send time by
 * {@code ChatMediaActivity.sendMessage()} and {@code MessageBuilder}.
 * A value of {@code 0} means "never expire".
 *
 * <p>Runs every 15 minutes while any conversation has disappearing messages enabled
 * (scheduled by {@link com.duoshield.app.util.SelfDestructScheduler}).
 *
 * <p>F43 fix: A legacy global-TTL mechanism has been removed. It was dead code
 * whose controlling preference was never written by any live UI path, and it posed
 * a risk of silently deleting messages that should not have been deleted.
 */
public class SelfDestructWorker extends Worker {

    private static final String TAG         = "SelfDestructWorker";
    private static final int    BATCH_LIMIT = 100;

    public SelfDestructWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);

        String conversationId = prefs.getString("conversation_id", null);
        if (conversationId == null) {
            Log.d(TAG, "No conversation — nothing to delete.");
            return Result.success();
        }

        long now = System.currentTimeMillis();

        // S08-M3: the database key is PIN-wrapped, so this worker cannot open the
        // database unless the user has unlocked the app at least once since the
        // process started. Check before doing anything else so a locked device
        // does not burn Firestore reads on work that cannot be completed.
        //
        // Result.retry() is the important part. The alternative — Result.success()
        // — would tell WorkManager this pass is finished, silently skipping the
        // expiry sweep entirely; expired messages would then sit in Room and
        // Firestore until some later pass happened to run while unlocked, with
        // nothing recording that a sweep was missed.
        //
        // The accepted trade-off, called out explicitly because it is user-visible:
        // self-destruct timers fire LATE on a device whose app is never opened.
        // Deletion is deferred, never skipped — WorkManager redelivers with
        // backoff, and the very next unlocked pass deletes everything whose
        // expiresAt has since passed, because the sweep is driven by absolute
        // timestamps rather than by "what expired since the last run".
        if (!SessionKeyHolder.isUnlocked()) {
            Log.i(TAG, "Session locked — deferring the self-destruct sweep (will retry).");
            return Result.retry();
        }

        try {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());

            // Delete Room rows whose expiresAt deadline has passed.
            // expiresAt is an absolute timestamp set at send time; 0 means "never".
            int roomDeleted = db.messageDao().deleteExpired(now);
            Log.d(TAG, "Room: deleted " + roomDeleted + " message(s) with expiresAt ≤ " + now);

            // Mirror the deletion in Firestore so both sides stay in sync.
            int firestoreDeleted = deleteExpiredFromFirestore(conversationId, now);

            // S08-H3: a disappearing message's decrypted image/video thumbnail is
            // decoded through Glide (see MessageAdapter), which persists the decoded
            // plaintext bitmap in its own on-disk cache (getCacheDir()/glide_image_cache,
            // up to 150 MB — see DuoShieldGlideModule) independently of the Room row,
            // the Firestore doc, and the encrypted B2 blob deleted above. Without this,
            // a message that just "disappeared" everywhere else still has a readable,
            // unencrypted copy of its media sitting on disk — exactly the data a
            // disappearing message is supposed to not leave behind. Only pay for this
            // when something in this pass actually expired, so an ordinary poll with
            // nothing to delete does not evict unrelated, still-live thumbnails.
            // Glide.clearDiskCache() must not be called on the main thread — Worker.doWork()
            // already runs on a background thread, so this is safe here.
            if (roomDeleted > 0 || firestoreDeleted > 0) {
                try {
                    com.bumptech.glide.Glide.get(getApplicationContext()).clearDiskCache();
                    Log.d(TAG, "Cleared Glide disk cache after " +
                            (roomDeleted + firestoreDeleted) + " expired message(s).");
                } catch (Exception e) {
                    Log.w(TAG, "Glide clearDiskCache() failed after self-destruct (non-fatal)" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                }
            }

            return Result.success();

        } catch (DatabaseKeyProvider.DatabaseLockedException e) {
            // The session locked between the check above and the database open —
            // the user can background the app at any moment, and a duress trigger,
            // logout, or wipe can land mid-sweep. Not an error: defer rather than
            // taking the generic error path, so the log reads as the expected
            // condition it is instead of an unexplained failure. Deletion is
            // deferred until the next unlocked run, never skipped.
            Log.i(TAG, "Session locked mid-pass — deferring self-destruct until unlocked.");
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "SelfDestructWorker failed — will retry" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return Result.retry();
        }
    }

    /**
     * Batch-deletes Firestore messages whose {@code expiresAt} is non-zero
     * and earlier than {@code now}.
     *
     * <p>A 30-second timeout is applied to each {@code Tasks.await()} call so
     * that a slow or offline network cannot block the WorkManager thread pool
     * indefinitely (BUG-T05).
     */
    private int deleteExpiredFromFirestore(String conversationId, long now) throws Exception {
        FirebaseFirestore fs = FirebaseFirestore.getInstance();
        QuerySnapshot snap = Tasks.await(
                fs.collection("chats").document(conversationId)
                  .collection("messages")
                  .whereGreaterThan("expiresAt", 0L)
                  .whereLessThan("expiresAt", now)
                  .get(),
                30, java.util.concurrent.TimeUnit.SECONDS
        );
        FirebaseCostGuard.getInstance(getApplicationContext()).recordReads(snap.size());
        return commitBatchDelete(fs, snap, "expiresAt-expired");
    }

    private int commitBatchDelete(FirebaseFirestore fs,
                                   QuerySnapshot snap,
                                   String label) throws Exception {
        List<DocumentSnapshot> docs = snap.getDocuments();
        if (docs.isEmpty()) {
            Log.d(TAG, "Firestore [" + label + "]: no messages to delete.");
            return 0;
        }

        int total = 0, batchCount = 0;
        WriteBatch batch = fs.batch();

        for (DocumentSnapshot doc : docs) {
            // Delete associated B2 media blob before removing the Firestore doc
            // so the encrypted file does not linger after expiry (BUG-SD01).
            // F10 fix: guard with isOwnedB2Path() so an attacker-crafted "path" value
            // pointing at another conversation's media cannot trigger arbitrary deletions.
            String mediaPath = doc.getString("path");
            if (B2StorageHelper.isOwnedB2Path(mediaPath, doc.getReference()
                    .getParent().getParent().getId())) {
                try {
                    B2StorageHelper.deleteFile(mediaPath);
                } catch (Exception e) {
                    Log.w(TAG, "B2 delete failed for path=" + com.duoshield.app.util.LogRedact.metadata(mediaPath)
                            + " — Firestore doc will still be deleted." + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                }
            } else if (mediaPath != null && !mediaPath.isEmpty()) {
                Log.w(TAG, "F10: refusing to delete B2 path '" + com.duoshield.app.util.LogRedact.metadata(mediaPath)
                        + "' — not owned by this conversation.");
            }

            batch.delete(doc.getReference());
            batchCount++;
            total++;
            if (batchCount == BATCH_LIMIT) {
                Tasks.await(batch.commit(), 30, java.util.concurrent.TimeUnit.SECONDS);
                Log.d(TAG, "Firestore [" + label + "]: committed batch of " + batchCount);
                batch = fs.batch();
                batchCount = 0;
            }
        }
        if (batchCount > 0) Tasks.await(batch.commit(), 30, java.util.concurrent.TimeUnit.SECONDS);
        FirebaseCostGuard.getInstance(getApplicationContext()).recordDeletes(total);
        Log.d(TAG, "Firestore [" + label + "]: deleted " + total + " message(s).");
        return total;
    }
}
