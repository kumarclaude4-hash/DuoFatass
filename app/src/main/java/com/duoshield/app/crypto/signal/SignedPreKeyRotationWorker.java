package com.duoshield.app.crypto.signal;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

/**
 * WorkManager {@link Worker} that rotates the device's Signal signed pre-key
 * every {@value #ROTATION_INTERVAL_MS} ms (7 days).
 *
 * <h3>Why rotate?</h3>
 * The signed pre-key (SPK) is the medium-term Curve25519 key included in every
 * X3DH key bundle. Rotating it weekly limits the window during which a compromised
 * SPK private key could be used to retroactively compute session secrets.
 *
 * <h3>Safety: grace period</h3>
 * {@link SignalKeyManager#rotateSignedPreKey} promotes the old SPK to
 * {@code signal_signed_prekey_prev} before replacing it. The store's
 * {@code loadSignedPreKey()} returns either the current <em>or</em> the previous
 * key, so messages sent just before rotation (whose bundle still references the
 * old SPK ID) continue to decrypt correctly for one full rotation cycle.
 *
 * <h3>Scheduling</h3>
 * Enqueued once in {@link SignedPreKeyScheduler#schedule} (called from
 * {@link com.duoshield.app.DuoShieldApp#onCreate()}) with a 1-day repeat
 * interval. The age check inside {@link #doWork()} means actual rotation only
 * happens when the key is at least 7 days old.
 */
public final class SignedPreKeyRotationWorker extends Worker {

    private static final String TAG = "SPKRotationWorker";

    /** Minimum age before a signed pre-key is considered stale and rotated. */
    static final long ROTATION_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L; // 7 days

    public SignedPreKeyRotationWorker(@NonNull Context ctx,
                                      @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        if (!SignalKeyManager.isInitialized(ctx)) {
            Log.d(TAG, "Signal keys not yet initialised — skipping rotation.");
            return Result.success();
        }

        SignedPreKeyRecord current = SignalKeyManager.getSignedPreKey(ctx);
        if (current == null) {
            Log.w(TAG, "No signed pre-key in store — skipping rotation.");
            return Result.success();
        }

        long ageMs = System.currentTimeMillis() - current.getTimestamp();
        long ageDays = ageMs / 86_400_000L;

        if (ageMs < ROTATION_INTERVAL_MS) {
            Log.d(TAG, "SPK is " + ageDays + " day(s) old — no rotation needed.");
            return Result.success();
        }

        Log.d(TAG, "SPK is " + ageDays + " day(s) old — rotating now.");

        try {
            SignalKeyManager.rotateSignedPreKey(ctx);

            // Co-rotate the Kyber last-resort pre-key on the same schedule.
            // This limits how long a compromised Kyber private key could be
            // exploited to break PQXDH forward secrecy.
            SignalKeyManager.rotateKyberPreKey(ctx);

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "SPK / Kyber rotation failed — will retry (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return Result.retry();
        }
    }
}
