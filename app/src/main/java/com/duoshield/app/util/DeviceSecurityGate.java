package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

/**
 * Single decision point for "may this device create or restore an account?" (S08-H5).
 *
 * <h3>Why this class exists</h3>
 * {@link SecurePrefs} no longer has a plaintext fallback. When every Keystore tier
 * fails it hands out an in-memory {@link EphemeralSharedPreferences}, which means
 * nothing survives process death — not the SQLCipher passphrase, not the Signal
 * identity key, not the PIN hash. Letting a user onboard or restore on such a
 * device would produce an account that silently evaporates on the next cold start,
 * and (worse) a database whose passphrase is gone while the database file remains,
 * which {@link com.duoshield.app.db.DatabaseKeyProvider} must then refuse to open.
 *
 * <p>Centralising the check here rather than repeating it at each entry point is
 * deliberate: the failure mode of a duplicated policy is one copy drifting, and the
 * drifted copy is the one that lets a user create unrecoverable data.
 *
 * <h3>What this deliberately does NOT block</h3>
 * <ul>
 *   <li><b>{@link SecurePrefs.SecurityTier#SOFTWARE}.</b> Still AES-256-GCM
 *       encrypted and still Keystore-protected; it is durable, so an account
 *       created on it works correctly. Blocking it would lock out the exact
 *       budget-device population (Helio G36, Android Go) that tier 2 was added
 *       to support. It is a weaker tier, not a broken one.</li>
 *   <li><b>Existing installs.</b> Ordinary sign-in and access to already
 *       recoverable data do not consult this gate. New onboarding/restore is
 *       blocked while legacy plaintext secrets remain unverified, so the app
 *       cannot silently add new secrets on top of an incomplete migration.</li>
 * </ul>
 */
public final class DeviceSecurityGate {

    private static final String TAG = "DeviceSecurityGate";

    private DeviceSecurityGate() {}

    /**
     * Why onboarding/restore was refused, so callers can log and explain rather
     * than showing a generic failure.
     */
    public enum Decision {
        /** A durable, encrypted store resolved. Onboarding/restore may proceed. */
        ALLOWED,
        /**
         * Every Keystore tier failed; the store is in-memory only. Any account
         * created now would be lost at process death.
         */
        BLOCKED_NO_DURABLE_STORE,
        /** Legacy plaintext secrets remain because migration was incomplete or unverifiable. */
        BLOCKED_LEGACY_PLAINTEXT_REMAINS;

        public boolean isAllowed() { return this == ALLOWED; }
    }

    /**
     * Evaluates the gate. Forces {@link SecurePrefs#get} to resolve a tier first,
     * so this is meaningful even when called before anything else has touched the
     * store — otherwise the tier would still read {@code NONE} simply because no
     * attempt had been made yet, and the gate would block every device.
     */
    @NonNull
    public static Decision evaluate(@NonNull Context context) {
        // Resolving the tier is the whole point of this call; the returned store
        // is intentionally unused.
        SecurePrefs.get(context);

        SecurePrefs.SecurityTier tier = SecurePrefs.getTier();
        Decision decision = evaluateState(tier, SecurePrefs.legacyPlaintextRemains());
        if (decision == Decision.BLOCKED_NO_DURABLE_STORE) {
            // Do not emit manufacturer/model: together they are device-fingerprinting
            // metadata and add no actionable diagnostic beyond the security tier/API.
            Log.e(TAG, "Blocking onboarding/restore: no durable secure store"
                    + " (API=" + android.os.Build.VERSION.SDK_INT + ").");
        } else if (decision == Decision.BLOCKED_LEGACY_PLAINTEXT_REMAINS) {
            Log.e(TAG, "Blocking onboarding/restore: legacy plaintext migration is incomplete.");
        } else {
            Log.i(TAG, "Onboarding/restore permitted.");
        }
        return decision;
    }

    /** Pure policy function so migration and storage-tier decisions are independently testable. */
    static Decision evaluateState(SecurePrefs.SecurityTier tier, boolean legacyPlaintextRemains) {
        if (tier == null || !tier.isDurable()) return Decision.BLOCKED_NO_DURABLE_STORE;
        if (legacyPlaintextRemains) return Decision.BLOCKED_LEGACY_PLAINTEXT_REMAINS;
        return Decision.ALLOWED;
    }

    /**
     * Convenience wrapper for entry points: evaluates the gate and, when blocked,
     * shows the explanatory dialog and returns false.
     *
     * <p>The dialog is deliberately not cancellable and finishes the activity on
     * dismissal — a gate the user can tap past is not a gate. Callers should
     * {@code return} immediately when this returns false.
     *
     * @param onBlockedDismissed optional extra action to run after the activity is
     *                           finished, e.g. navigating back to a sign-in screen.
     * @return true when the caller may proceed.
     */
    public static boolean checkOrExplain(@NonNull Activity activity,
                                         @Nullable Runnable onBlockedDismissed) {
        Decision decision = evaluate(activity);
        if (decision.isAllowed()) return true;

        if (activity.isFinishing() || activity.isDestroyed()) return false;

        DialogInterface.OnClickListener close = (d, w) -> {
            activity.finish();
            if (onBlockedDismissed != null) onBlockedDismissed.run();
        };

        boolean migrationBlocked = decision == Decision.BLOCKED_LEGACY_PLAINTEXT_REMAINS;
        new AlertDialog.Builder(activity)
                .setTitle(migrationBlocked
                        ? com.duoshield.app.R.string.device_legacy_migration_title
                        : com.duoshield.app.R.string.device_unsupported_title)
                .setMessage(migrationBlocked
                        ? com.duoshield.app.R.string.device_legacy_migration_message
                        : com.duoshield.app.R.string.device_unsupported_message)
                .setCancelable(false)
                .setPositiveButton(com.duoshield.app.R.string.device_unsupported_dismiss, close)
                .show();
        return false;
    }
}
