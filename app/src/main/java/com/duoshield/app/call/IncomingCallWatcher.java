package com.duoshield.app.call;

import android.content.Context;
import android.util.Log;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import com.duoshield.app.util.LogRedact;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Firestore-based incoming call detector — backup to FCM push notifications.
 *
 * <h3>Why this exists</h3>
 * The FCM path relies on the push server (hosted on Render) being awake. Render's
 * free tier sleeps after ~15 min of inactivity. When a call arrives while Render is
 * sleeping, the server takes 30+ seconds to wake up, by which time the caller
 * has often given up. This watcher gives the callee's device a direct Firestore
 * real-time path — independent of the push server — so calls are never missed when
 * the app is in the foreground.
 *
 * <h3>Deduplication</h3>
 * Both this watcher and the FCM handler ({@link com.duoshield.app.notifications.DuoShieldMessagingService})
 * may fire for the same call. The shared static {@link #markShown} / {@link #isShown}
 * pair prevents the callee from seeing two ringing screens.
 *
 * <h3>Lifecycle</h3>
 * Start in {@code onStart()} and stop in {@code onStop()} of the host activity.
 * The watcher covers the foreground case; FCM covers background/killed.
 */
public class IncomingCallWatcher {

    private static final String TAG = "IncomingCallWatcher";

    /** Calls ring for at most 30 s — ignore docs older than this on initial snapshot. */
    private static final long RING_TIMEOUT_MS = 30_000L;

    // ── Shared dedup set (FCM path and Firestore path both use this) ──────────
    private static final Set<String> shownCallIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Called by FCM path before showing the incoming call so this watcher won't duplicate it. */
    public static void markShown(String callId) {
        if (callId == null) return;
        if (shownCallIds.size() > 100) shownCallIds.clear();
        shownCallIds.add(callId);
    }

    public static boolean isShown(String callId) {
        return callId != null && shownCallIds.contains(callId);
    }

    // ── Instance state ────────────────────────────────────────────────────────
    private final Context context;
    private final String  myUid;
    private ListenerRegistration reg;

    public IncomingCallWatcher(Context context, String myUid) {
        this.context = context.getApplicationContext();
        this.myUid   = myUid;
    }

    /**
     * Attaches the Firestore listener. Safe to call multiple times — idempotent.
     */
    public void start() {
        if (reg != null) return; // already attached
        if (myUid == null || myUid.isEmpty()) return;

        // Single-field query on calleeId only — no composite index required.
        //
        // A two-field query (.whereEqualTo("calleeId").whereEqualTo("status","ringing"))
        // requires a composite Firestore index. If that index hasn't been created in the
        // Firebase console, Firestore returns an error and addSnapshotListener silently
        // delivers nothing — every incoming call misses the watcher and falls back to FCM
        // via the Render push server, which can take 10+ seconds when Render cold-starts.
        //
        // Filtering "status == ringing" in handleCallDoc (client-side) is safe: each user
        // has at most one active call at a time, so the document volume is tiny.
        Query q = FirebaseFirestore.getInstance()
                .collection("calls")
                .whereEqualTo("calleeId", myUid);

        reg = q.addSnapshotListener((snap, err) -> {
            if (err != null) {
                // The query is scoped to myUid; avoid logging Firestore error text,
                // which may contain query/document details in release logcat.
                Log.w(TAG, "Incoming call watch failed (" + err.getClass().getSimpleName() + ")");
                return;
            }
            if (snap == null) return;

            for (DocumentSnapshot doc : snap.getDocuments()) {
                handleCallDoc(doc);
            }
        });

        Log.d(TAG, "Started watching for incoming calls (uid=" + myUid + ")");
    }

    /**
     * Removes the Firestore listener. Safe to call when already stopped.
     */
    public void stop() {
        if (reg != null) {
            reg.remove();
            reg = null;
            Log.d(TAG, "Stopped incoming call watcher");
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void handleCallDoc(DocumentSnapshot doc) {
        String callId = doc.getId();

        // Client-side status filter (replaces the removed Firestore whereEqualTo("status")).
        String status = doc.getString("status");
        if (!"ringing".equals(status)) return;

        // Dedup — FCM path may have already shown this call.
        if (isShown(callId)) return;

        // Skip stale docs that survived the ring timeout (Firestore returns all
        // matching docs on the initial snapshot, including old ones).
        Timestamp createdAt = doc.getTimestamp("createdAt");
        if (createdAt != null) {
            long ageMs = System.currentTimeMillis() - createdAt.toDate().getTime();
            if (ageMs > RING_TIMEOUT_MS) {
                Log.d(TAG, "Skipping stale call doc " + callId + " (age " + ageMs + " ms)");
                return;
            }
        }

        String callerId = doc.getString("callerId");
        boolean isVideo = "video".equals(doc.getString("type"));
        if (callerId == null) return;

        // Mark now (before the async fetch) so a racing FCM doesn't duplicate.
        markShown(callId);

        // Fetch the caller's display name, then show the ringing screen.
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(callerId)
                .get()
                .addOnSuccessListener(userSnap -> {
                    String name = userSnap.getString("displayName");
                    if (name == null || name.trim().isEmpty()) name = "DuoShield";

                    Log.d(TAG, "Incoming call via Firestore watcher: callId=" + callId
                            + " caller=" + name + " video=" + isVideo);

                    com.duoshield.app.notifications.NotificationHelper.createChannel(context);
                    com.duoshield.app.notifications.NotificationStyler.showIncomingCall(
                            context, name, callId, callerId, isVideo);
                })
                .addOnFailureListener(e -> {
                    // Caller doc unreadable — still ring with a generic name.
                    // S10-N2: callerId is a Firebase UID; Log.w survives release builds.
                    // Log only its process-salted tag and the exception type, not the raw ID
                    // or backend exception text (which may include document details).
                    Log.w(TAG, "Could not fetch caller name for " + LogRedact.uid(callerId)
                            + " (" + e.getClass().getSimpleName() + ")");
                    com.duoshield.app.notifications.NotificationHelper.createChannel(context);
                    com.duoshield.app.notifications.NotificationStyler.showIncomingCall(
                            context, "DuoShield", callId, callerId, isVideo);
                });
    }
}
