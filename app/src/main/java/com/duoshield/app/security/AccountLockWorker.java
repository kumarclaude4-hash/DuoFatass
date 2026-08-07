package com.duoshield.app.security;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.duoshield.app.BuildConfig;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager job that writes {@code accountLock/{uid}.locked = true} via the
 * push server's {@code /duress-lock} endpoint, as a retry fallback for when
 * {@link DuressManager}'s primary synchronous Firestore lock write failed
 * (e.g. the device was offline at trigger time).
 *
 * <h3>Auth: server-issued one-time nonce</h3>
 * This job stores a single-use nonce in its WorkManager input data — not a
 * Firebase ID token or any APK-embedded shared secret. The nonce is issued by
 * the server's {@code /requestLockNonce} endpoint <em>before</em> sign-out,
 * while the Firebase session is still live, and is bound server-side to the
 * requesting uid. Properties:
 * <ul>
 *   <li>Not a Firebase credential — cannot authenticate to Firebase Auth.</li>
 *   <li>uid-bound — cannot be used to lock any other account.</li>
 *   <li>Single-use — the server deletes it atomically with the lock write.</li>
 *   <li>24-hour expiry — generous retry window for WorkManager backoff.</li>
 * </ul>
 *
 * <h3>Clearing the flag</h3>
 * Clearing an {@code accountLock} doc is a manual, out-of-band operation
 * (Firebase console / Admin SDK only — see Firestore rules).
 */
public class AccountLockWorker extends Worker {

    private static final String TAG        = "AccountLockWorker";
    private static final String DATA_UID   = "uid";
    private static final String DATA_NONCE = "nonce";

    /** Jitter window: 5-40 seconds, matching FcmUnregisterWorker. */
    private static final long JITTER_MIN_MS   = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    public AccountLockWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered account-lock retry. The nonce should have been obtained
     * via {@code /requestLockNonce} while the Firebase session was still live (before
     * sign-out and wipe). If {@code nonce} is null or empty (S06-H3: offline at
     * trigger time), the worker is still enqueued and will attempt UID-only nonce
     * recovery in {@link #doWork()} on its first retry once connectivity returns.
     */
    public static void enqueue(Context ctx, String uid, String nonce) {
        if (uid == null || uid.isEmpty()) {
            Log.w(TAG, "enqueue skipped — missing uid.");
            return;
        }
        // S06-H3: nonce may be absent when the device was offline at trigger time.
        // Enqueue unconditionally; doWork() will attempt UID-only nonce recovery.
        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);
        Data input = new Data.Builder()
                .putString(DATA_UID, uid)
                .putString(DATA_NONCE, nonce)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AccountLockWorker.class)
                .setInitialDelay(jitterMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(input)
                .addTag("account_lock_" + uid)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext()).enqueue(request);
        Log.d(TAG, "AccountLockWorker enqueued (nonce-based retry).");
    }

    /**
     * S06-H3: Attempts to obtain a lock nonce without a Firebase ID token, using the
     * uid in the POST body. The server enforces rate-limiting (one request per uid per
     * 10 minutes) and only issues a nonce if the uid's {@code accountLock} document is
     * in the expected pre-locked state. Returns the nonce string, or {@code null} on
     * failure (including HTTP errors and network unavailability).
     */
    private String tryRecoverNonce(String uid) {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) return null;
        String endpoint = serverUrl.endsWith("/")
                ? serverUrl + "requestLockNonce"
                : serverUrl + "/requestLockNonce";
        try {
            byte[] body = new org.json.JSONObject()
                    .put("uid", uid)
                    .toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(endpoint).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (java.io.OutputStream os = conn.getOutputStream()) { os.write(body); }
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "UID-only nonce recovery: server returned HTTP " + code);
                    return null;
                }
                java.io.InputStream is = conn.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[2048]; int n;
                while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
                String nonce = new org.json.JSONObject(buf.toString("UTF-8")).optString("nonce", null);
                return (nonce != null && !nonce.isEmpty()) ? nonce : null;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "UID-only nonce recovery failed: " + e.getMessage());
            return null;
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid   = getInputData().getString(DATA_UID);
        String nonce = getInputData().getString(DATA_NONCE);
        if (uid == null || uid.isEmpty()) {
            Log.w(TAG, "Missing uid — dropping job.");
            return Result.success();
        }

        // S06-H3: Nonce may be absent if the device was offline when the duress
        // wipe was triggered. Attempt UID-only nonce recovery via the server's
        // /requestLockNonce endpoint (uid-in-body path, no Firebase token required).
        if (nonce == null || nonce.isEmpty()) {
            nonce = tryRecoverNonce(uid);
            if (nonce == null || nonce.isEmpty()) {
                Log.w(TAG, "UID-only nonce recovery failed — will retry.");
                return Result.retry();
            }
            Log.d(TAG, "UID-only nonce recovery succeeded.");
        }

        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) {
            Log.w(TAG, "PUSH_SERVER_URL not configured — dropping lock job.");
            return Result.success();
        }

        try {
            String endpoint = serverUrl.endsWith("/")
                    ? serverUrl + "duress-lock"
                    : serverUrl + "/duress-lock";

            byte[] bodyBytes = new JSONObject()
                    .put("nonce", nonce)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }

                int code = conn.getResponseCode();
                if (code == 200 || code == 204) {
                    Log.d(TAG, "Account lock confirmed by push server.");
                    return Result.success();
                }
                if (code == 400 || code == 403) {
                    // Invalid / already-consumed nonce — retrying cannot recover this.
                    Log.w(TAG, "Push server rejected nonce (HTTP " + code + ") — dropping job.");
                    return Result.success();
                }
                if (code == 401) {
                    // Nonce expired — the 24-hour window has elapsed without network.
                    // No path to obtain a fresh credential exists post-wipe; drop the job.
                    // The synchronous lock write (step 1a in DuressManager) was already
                    // attempted; if that also failed the account was offline for >24 h.
                    Log.w(TAG, "Lock nonce expired (HTTP 401) — no retry path available post-wipe; dropping.");
                    return Result.success();
                }
                Log.w(TAG, "Push server returned HTTP " + code + " — will retry.");
                return Result.retry();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Account lock push failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }
}
