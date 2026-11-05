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
 *
 * <h3>No uid anywhere in this job (S06-H2)</h3>
 * The nonce is already uid-bound server-side ({@code /requestLockNonce} records which
 * uid issued it, and {@code /duress-lock} looks that up — see server/index.js), so this
 * job never needs a uid to do its work and none is accepted, stored in {@link Data}, or
 * used as a WorkManager tag. WorkManager persists both a job's input {@link Data} and
 * its tags in its own SQLite database ({@code androidx.work.workdb}), which lives
 * outside every store {@code WipeHelper.eraseLocalData()} touches — a tag like
 * {@code "account_lock_<uid>"} (the previous design) would survive a duress wipe
 * indefinitely in plaintext, naming exactly the account that was just wiped.
 */
public class AccountLockWorker extends Worker {

    private static final String TAG        = "AccountLockWorker";
    private static final String DATA_NONCE = "nonce";

    /** Jitter window: 5-40 seconds, matching FcmUnregisterWorker. */
    private static final long JITTER_MIN_MS   = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    /**
     * S06-L4: {@code Result.retry()} on a {@link androidx.work.OneTimeWorkRequest}
     * has no built-in attempt cap — returning it forever (e.g. the device stays
     * offline indefinitely) would have WorkManager reschedule this job forever,
     * silently, with no genuine-failure signal ever surfacing. 15 attempts of
     * {@code BackoffPolicy.EXPONENTIAL} starting at 30s and capped by WorkManager's
     * own 5-hour per-attempt ceiling accumulate to roughly 33 hours of retrying —
     * comfortably longer than the 24-hour server-side nonce expiry documented on
     * this class, so a merely-slow network still gets its full retry window, while
     * a permanently unreachable server or dead network eventually terminates
     * instead of retrying forever.
     */
    private static final int MAX_ATTEMPTS = 15;

    public AccountLockWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered account-lock retry authenticated with a server-issued
     * one-time nonce. The nonce must have been obtained via {@code /requestLockNonce}
     * while the Firebase session was still live (before sign-out and wipe).
     */
    public static void enqueue(Context ctx, String nonce) {
        if (nonce == null || nonce.isEmpty()) {
            Log.w(TAG, "enqueue skipped — missing nonce.");
            return;
        }
        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);
        Data input = new Data.Builder()
                .putString(DATA_NONCE, nonce)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AccountLockWorker.class)
                .setInitialDelay(jitterMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(input)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext()).enqueue(request);
        Log.d(TAG, "AccountLockWorker enqueued (nonce-based retry).");
    }

    @NonNull
    @Override
    public Result doWork() {
        String nonce = getInputData().getString(DATA_NONCE);
        if (nonce == null || nonce.isEmpty()) {
            Log.e(TAG, "Missing nonce — account lock NOT confirmed; durable lock intent retained.");
            return Result.failure();
        }

        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) {
            Log.e(TAG, "PUSH_SERVER_URL not configured — account lock NOT confirmed; durable lock intent retained.");
            return Result.failure();
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
                AccountLockHttpPolicy.Disposition disposition =
                        AccountLockHttpPolicy.classify(code);
                if (disposition == AccountLockHttpPolicy.Disposition.LOCK_CONFIRMED) {
                    Log.d(TAG, "Account lock confirmed by push server.");
                    // Clear the durable intent only after a confirmed 200/204.
                    PendingLockStore.clearLockIntent(getApplicationContext());
                    return Result.success();
                }
                if (disposition == AccountLockHttpPolicy.Disposition.PERMANENT_FAILURE) {
                    // Invalid/malformed/expired/unknown nonces cannot recover by
                    // retrying this exact request. Do NOT clear the durable intent:
                    // app startup can obtain a fresh nonce and drain it later.
                    Log.e(TAG, "Push server rejected lock request (HTTP " + code
                            + ") — account lock NOT confirmed; durable intent retained.");
                    return Result.failure();
                }
                return retryOrGiveUp("Push server returned HTTP " + code);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return retryOrGiveUp("Account lock push failed — " + e.getMessage());
        }
    }

    /**
     * Bounds retryable failures (5xx responses, network exceptions) to
     * {@link #MAX_ATTEMPTS} attempts (S06-L4). Below the cap this behaves exactly
     * as the unconditional {@code Result.retry()} it replaces — legitimate
     * transient-failure retry semantics are preserved. At/above the cap it reports
     * a genuine failure via {@code Log.e} (kept in release builds — see
     * SEC-L03 in proguard-rules.pro) and returns {@code Result.failure()} instead
     * of rescheduling forever. It deliberately does NOT clear the durable pending-
     * lock intent in {@link PendingLockStore}: the same "surface the failure
     * rather than silently deciding it doesn't matter" reasoning documented on the
     * HTTP 401 branch above applies here — {@code hasLockIntent()} keeps reporting
     * "believed unlocked" so this exhausted-retries failure is observable instead
     * of disappearing.
     */
    @NonNull
    private Result retryOrGiveUp(String reason) {
        int attempt = getRunAttemptCount();
        if (attempt + 1 >= MAX_ATTEMPTS) {
            Log.e(TAG, reason + " — giving up after " + (attempt + 1)
                    + " attempts (S06-L4 bounded retry cap). Account lock push NOT confirmed.");
            return Result.failure();
        }
        Log.w(TAG, reason + " — will retry (attempt " + (attempt + 1) + "/" + MAX_ATTEMPTS + ").");
        return Result.retry();
    }
}
