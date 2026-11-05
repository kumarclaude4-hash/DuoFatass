package com.duoshield.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.ShakeDetector;
import com.google.firebase.auth.FirebaseAuth;

public class BaseActivity extends AppCompatActivity {

    private static final String TAG = "BaseActivity";

    /**
     * SharedPreferences key set to {@code true} before any intentional Firebase sign-out
     * (duress logout, auto sign-out timer, explicit wipe).  BaseActivity reads this flag
     * in onStart() to distinguish an intentional sign-out from a transient Firebase
     * token state (e.g. app process restart while the SDK re-initialises).
     *
     * <p>Cleared by BaseActivity when currentUser is non-null (valid session) and by
     * RestoreFromSeedActivity after a successful restore.</p>
     */
    public static final String KEY_EXPLICIT_SIGNOUT = "explicit_signout";
    private static final String PREFS_NAME          = "duoshield_prefs";

    /**
     * Prevents stacking multiple LockScreenActivity instances.
     * Set to {@code true} just before starting LockScreenActivity; cleared back
     * to {@code false} in {@link LockScreenActivity#unlock()} before finish().
     */
    public static volatile boolean lockScreenActive = false;

    private ShakeDetector shakeDetector;

    /**
     * SharedPreferences key for the user-facing "Allow screenshots" toggle in
     * SecurityPrivacySettingsActivity. Read here (not just there) so every
     * BaseActivity-derived screen actually enforces the choice — see S08-H2.
     */
    private static final String KEY_APP_SCREENSHOT_ENABLED = "app_screenshot_enabled";

    /**
     * Applies (or lifts) {@code FLAG_SECURE} on {@code activity}'s window based on the
     * {@code app_screenshot_enabled} preference.
     *
     * <p>S08-H2 fix: every one of {@link #onCreate}, {@link MainActivity#onCreate},
     * {@link LockScreenActivity#onCreate}, and
     * {@link com.duoshield.app.ui.SecurityPrivacySettingsActivity#applyScreenshotFlag}
     * unconditionally called {@code clearFlags(FLAG_SECURE)} — screenshots, screen
     * recording, and the recents-list thumbnail were <em>always</em> allowed app-wide,
     * regardless of what the "Allow screenshots" toggle said. This is the single point
     * BaseActivity-derived activities now route through, matching what GroupChatActivity's
     * and ChatMediaActivity's onResume() comments already claimed was happening globally.
     *
     * <p>Secure-by-default: on a fresh install, {@code getBoolean} returns {@code false}
     * and {@link WindowManager.LayoutParams#FLAG_SECURE} remains set unless the user
     * explicitly enables screenshots in Security & Privacy settings. The settings screen
     * reads this same constant so its initial switch state matches the enforced window flag.
     */
    public static final boolean SCREENSHOTS_ALLOWED_BY_DEFAULT = false;

    static void applyScreenshotSecurity(android.app.Activity activity) {
        boolean screenshotsAllowed = activity
                .getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_APP_SCREENSHOT_ENABLED, SCREENSHOTS_ALLOWED_BY_DEFAULT);
        if (screenshotsAllowed) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyScreenshotSecurity(this);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Increment the activity reference count (BUG-D09).
        AppLockManager.onActivityStarted();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 0. Firebase session check — only redirect to SignInActivity when we are
        //    SURE the sign-out was intentional (explicit_signout flag is set).
        //    Transient null currentUser (SDK re-init, token refresh) is silently ignored.
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            boolean wasExplicit = prefs.getBoolean(KEY_EXPLICIT_SIGNOUT, false);
            Log.d(TAG, getClass().getSimpleName() + ".onStart: currentUser=null"
                    + "  explicit=" + wasExplicit);
            if (wasExplicit) {
                Log.i(TAG, getClass().getSimpleName()
                        + ": intentional sign-out → redirecting to SignInActivity");
                prefs.edit().remove(KEY_EXPLICIT_SIGNOUT).apply();
                Intent intent = new Intent(this, SignInActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }
            // Not an intentional sign-out — Firebase SDK may still be restoring
            // its session.  Do NOT redirect; preserve bgTs for lock/sign-out timing.
            return;
        }

        // Valid session — clear any stale explicit_signout flag.
        prefs.edit().remove(KEY_EXPLICIT_SIGNOUT).apply();

        // 1. Auto sign-out — kills Firebase session after prolonged inactivity.
        if (AppLockManager.shouldAutoSignOut(this)) {
            Log.i(TAG, getClass().getSimpleName() + ": auto sign-out threshold exceeded → SignIn");
            // Only schedule the de-registration if there actually was a signed-in
            // session to de-register. FcmUnregisterWorker takes no uid and no bearer
            // token (S06-H2): FirebaseMessaging.deleteToken() acts on this device's own
            // FCM registration and handles its own auth, so nothing needs to be captured
            // before signOut() below runs. See FcmUnregisterWorker's javadoc.
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                com.duoshield.app.util.FcmUnregisterWorker
                        .enqueue(getApplicationContext());
            }
            prefs.edit()
                 .putBoolean(KEY_EXPLICIT_SIGNOUT, true)
                 .putBoolean("signed_out_reason_inactivity", true)
                 .remove("is_paired")
                 .remove("conversation_id")
                 .remove("partner_uid")
                 .remove("ecdh_shared_key")
                 .remove("disappear_ms")
                 .apply();
            try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}
            Intent intent = new Intent(this, SignInActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // 2. PIN lock — shows lock screen after background timeout.
        //
        // The lock screen check MUST run before the offline lockout below: the lock
        // screen (LockScreenActivity) is where the duress secondary PIN is entered, and
        // it must stay reachable offline so its local wipe can fire. Only once the user
        // is genuinely unlocked and about to see real app content do we enforce the
        // "no offline use" wall.
        if (AppLockManager.shouldLock(this)) {
            if (!lockScreenActive) {
                Log.d(TAG, getClass().getSimpleName() + ": lock timeout exceeded → LockScreenActivity");
                lockScreenActive = true;
                Intent intent = new Intent(this, LockScreenActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        } else {
            // Full offline lockout: an unlocked, foregrounded screen showing real app
            // content must not operate without a connection. Enforced here (not before
            // the lock check) so unlocking — including the offline duress path — is
            // never blocked. The server-side account lock still drains once online.
            if (com.duoshield.app.util.NetworkStateHelper.blockIfOffline(this)) {
                return;
            }

            AppLockManager.onAppForegrounded(this);

            // 3. Keep the duress lock credential warm (S06-H3).
            //
            // This is the ONLY caller of maintainLockCredential(), and without it the
            // whole offline branch of the S06-H3 fix is inert: performLogout() reads a
            // warm nonce out of PendingLockStore at trigger time, and if nothing ever
            // put one there, an offline duress trigger records an intent with a null
            // token, drainPendingLockIntent() finds nothing to send, and the account is
            // never locked — silently, which is the attacker's win condition. The nonce
            // fetch cannot happen on the duress path itself, because by then the app is
            // offline and (moments later) signed out.
            //
            // Deliberately placed in this else-branch: reaching it means there is a
            // valid session AND the app is genuinely foregrounded and unlocked, which is
            // exactly the "ordinary online foreground operation" the method's javadoc
            // requires. It self-throttles (no-ops if the warm nonce is under 30 minutes old),
            // no-ops when signed out or offline, and does its network I/O on its own
            // background thread, so calling it from onStart() adds no main-thread work.
            try {
                com.duoshield.app.security.DuressManager
                        .maintainLockCredential(getApplicationContext());
            } catch (Exception e) {
                // Never let credential upkeep break navigation into a screen.
                Log.w(TAG, "Lock credential upkeep skipped: " + e.getMessage());
            }
        }
    }

    /** "Tap to return to call" strip, shown while a call is live and its screen is not in front. */
    private com.duoshield.app.call.ReturnToCallBar returnToCallBar;

    /**
     * Whether this screen shows the return-to-call strip. Full-screen viewers opt out: shifting
     * their content down would break the immersive layout.
     */
    protected boolean showsReturnToCallBar() {
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (showsReturnToCallBar()) {
            returnToCallBar = com.duoshield.app.call.ReturnToCallBar.show(this);
        }
        // Shake to lock is not a user-facing preference. It ships as an implicit part
        // of the secondary-code / duress capability: accounts the operator enrolled,
        // and accounts that already armed a secondary code, get it always-on. Ordinary
        // accounts never arm the sensor and see no trace of the feature anywhere.
        if (isShakeToLockAvailable() && PinManager.hasPinSet(this)) {
            shakeDetector = new ShakeDetector(this, this::onShakeToLock);
            shakeDetector.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (returnToCallBar != null) {
            returnToCallBar.dismiss();
            returnToCallBar = null;
        }
        if (shakeDetector != null) {
            shakeDetector.stop();
            shakeDetector = null;
        }
    }

    /**
     * Whether shake to lock exists at all for the signed-in account.
     *
     * <p>True when the account is enrolled for the duress capability server-side, OR
     * when it already has a secondary code armed on this device. The second half of
     * that OR matters: a user who configured a secondary code must keep the feature
     * even if the cached eligibility flag later goes stale (revoked document, a failed
     * refresh while offline), because the code they rely on is still armed locally.
     *
     * <p>Both reads are cached/local, so this is safe to call on every resume and
     * works with no network.
     */
    private boolean isShakeToLockAvailable() {
        try {
            return com.duoshield.app.security.DuressManager.isDuressEligibleCached(this)
                    || com.duoshield.app.security.DuressManager.hasDuressPin(this);
        } catch (Exception e) {
            // Never let a prefs/keystore hiccup crash an activity resume. Failing
            // closed here only means the sensor stays off for this resume.
            Log.w(TAG, "Shake-to-lock availability check skipped: " + e.getMessage());
            return false;
        }
    }

    private void onShakeToLock() {
        if (!PinManager.hasPinSet(this)) return;
        // Re-checked here, not just at start(): the secondary code can be cleared or
        // eligibility revoked while this activity is resumed and the detector is live.
        if (!isShakeToLockAvailable()) return;
        if (lockScreenActive) return;
        lockScreenActive = true;
        Intent intent = new Intent(this, LockScreenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLockManager.onActivityStopped(this);
    }

    protected void navigateTo(Class<?> dest) {
        startActivity(new Intent(this, dest));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    protected void navigateBack() {
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
