package com.duoshield.app.call;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Periodic WorkManager job that purges stale {@code calls/*} Firestore documents
 * older than 24 hours with status in {@code ["ringing","missed","timeout"]}.
 *
 * <p>FIX #7: The previous implementation used a collection-wide query with no
 * participant filter, which Firestore denies under the per-document security rule
 * ({@code callerId == uid || calleeId == uid}).  Two separate queries — one scoped
 * to {@code callerId == uid}, one to {@code calleeId == uid} — satisfy that rule
 * without requiring a server-side Admin SDK bypass.
 */
public class CallCleanupWorker extends Worker {

    private static final String TAG = "CallCleanupWorker";
    static final String WORK_TAG = "call_cleanup_periodic";

    private static final List<String> STALE_STATUSES =
            Arrays.asList("ringing", "missed", "timeout");
    private static final long STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L;

    public CallCleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.d(TAG, "Not authenticated — skipping cleanup");
            return Result.success();
        }

        try {
            String uid = user.getUid();
            long cutoffMs = System.currentTimeMillis() - STALE_THRESHOLD_MS;
            Date cutoff = new Date(cutoffMs);
            FirebaseFirestore db = FirebaseFirestore.getInstance();

            // FIX #7: Two separate queries scoped to the current user's participant role.
            // A single unscoped query is denied by Firestore's per-doc security rule.
            // Note: each query requires a composite index on (callerId/calleeId + createdAt).
            QuerySnapshot callerSnap = Tasks.await(
                    db.collection("calls")
                      .whereEqualTo("callerId", uid)
                      .whereLessThan("createdAt", cutoff)
                      .get(),
                    30, TimeUnit.SECONDS);

            QuerySnapshot calleeSnap = Tasks.await(
                    db.collection("calls")
                      .whereEqualTo("calleeId", uid)
                      .whereLessThan("createdAt", cutoff)
                      .get(),
                    30, TimeUnit.SECONDS);

            // Merge results, dedup by document ID
            Set<String> seenIds = new HashSet<>();
            int deleted = 0;

            for (QueryDocumentSnapshot doc : callerSnap) {
                if (!seenIds.add(doc.getId())) continue;
                String status = doc.getString("status");
                if (status != null && STALE_STATUSES.contains(status)) {
                    deleteDocAndSubcollections(doc.getId());
                    deleted++;
                }
            }
            for (QueryDocumentSnapshot doc : calleeSnap) {
                if (!seenIds.add(doc.getId())) continue;
                String status = doc.getString("status");
                if (status != null && STALE_STATUSES.contains(status)) {
                    deleteDocAndSubcollections(doc.getId());
                    deleted++;
                }
            }

            Log.d(TAG, "Call cleanup complete — deleted " + deleted + " stale docs");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Call cleanup failed — will retry next cycle ("
                    + e.getClass().getSimpleName() + ")");
            return Result.retry();
        }
    }

    private void deleteDocAndSubcollections(String callId) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        // Include "chat" — in-call ephemeral messages must also be swept.
        String[] subcollections = {"callerCandidates", "calleeCandidates", "chat"};
        for (String sub : subcollections) {
            db.collection("calls").document(callId).collection(sub).get()
                    .addOnSuccessListener(snap -> {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) {
                            d.getReference().delete();
                        }
                    });
        }
        db.collection("calls").document(callId).delete();
    }

    public static void scheduleIfNeeded(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                CallCleanupWorker.class, 12, TimeUnit.HOURS)
                .addTag(WORK_TAG)
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_TAG,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                req);
        Log.d(TAG, "Call cleanup scheduled (12h periodic)");
    }
}
