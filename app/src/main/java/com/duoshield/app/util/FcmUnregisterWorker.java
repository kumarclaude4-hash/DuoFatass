package com.duoshield.app.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager job that de-registers this device's FCM token some time after a
 * sign-out, so the push server stops delivering notifications to this device.
 *
 * <h3>Why not just call FcmTokenHelper.unregister() synchronously?</h3>
 * A synchronous "sign-out at time T → token cleared at time T" write is a network
 * event with a fixed, observable timing relationship to the sign-out. For a
 * security-triggered sign-out that relies on plausible deniability (see
 * {@link com.duoshield.app.security.DuressManager#performLogout}), an attacker
 * watching network traffic could correlate "notifications stopped arriving the
 * instant this PIN was entered" with the PIN just entered being significant.
 * A jittered, WorkManager-scheduled delete decouples that timing, and — because
 * WorkManager persists its queue — the job still runs even after a reboot.
 *
 * <p>This same jittered de-registration is used for every sign-out path (duress
 * and ordinary), not just the duress one, so there is nothing to correlate.
 *
 * <h3>Auth: FCM's own deleteToken() instead of a stored bearer token</h3>
 * Previous versions stored the user's Firebase ID token in WorkManager's persistent
 * input data to authenticate a post-sign-out Firestore write. Storing a reusable
 * owner credential on disk after a wipe is a security risk (it survives the wipe
 * and is valid for up to one hour). {@link FirebaseMessaging#deleteToken()} does
 * not require any caller-supplied auth — the FCM SDK manages its own registration
 * independently of the Firebase Auth session — so no credential is stored at all.
 *
 * <h3>No uid anywhere in this job (S06-H2)</h3>
 * {@code deleteToken()} operates on this device's own FCM registration and needs no
 * uid to do it, so none is accepted, stored in {@link Data}, or used as a WorkManager
 * tag. WorkManager persists both a job's input {@link Data} and its tags in its own
 * SQLite database ({@code androidx.work.workdb}), and neither is touched by
 * {@code WipeHelper.eraseLocalData()} — that database lives outside the app's own
 * encrypted prefs/SQLCipher stores this app controls directly. A tag like
 * {@code "fcm_unregister_<uid>"} (the previous design) would sit there in plaintext
 * indefinitely, including through a duress wipe: exactly the kind of forensic residue
 * a "the phone was wiped, nothing to find" defense depends on not existing.
 */
public class FcmUnregisterWorker extends Worker {

    private static final String TAG = "FcmUnregisterWorker";

    /** Jitter window: 5-40 seconds after the sign-out that scheduled this job. */
    private static final long JITTER_MIN_MS   = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    public FcmUnregisterWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered FCM token de-registration for the device this call runs on.
     * No bearer token, no uid — {@link FirebaseMessaging#deleteToken()} handles its own
     * authentication via the FCM SDK's device registration state, and needs to know
     * nothing about which account was signed out.
     */
    public static void enqueue(Context ctx) {
        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(FcmUnregisterWorker.class)
                .setInitialDelay(jitterMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext()).enqueue(request);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            // deleteToken() invalidates this device's FCM registration token at the
            // FCM protocol level — no Firebase Auth session required. The push server
            // will naturally stop delivering to a deleted token on its next attempt.
            Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 30, TimeUnit.SECONDS);
            Log.d(TAG, "FCM token deleted for this device.");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "FCM token delete failed — will retry (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return Result.retry();
        }
    }
}
