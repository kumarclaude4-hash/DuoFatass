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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // S08-H2: Prevent screenshots and screen recording across the entire app.
        // FLAG_SECURE is set here in the base class so every Activity subclass
        // inherits it without needing individual opt-in. Any Activity that
        // legitimately needs to be excluded must call clearFlags explicitly and
        // document the reason in a code comment.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
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
            // Capture the UID before signOut() clears it, so the delayed FCM
            // de-registration job below has something to act on. Also kick off an
            // ID-token capture on the still-live user object right now — the job runs
            // 5-40s from now, after signOut() below has already killed the ambient
            // session it would otherwise need. See FcmUnregisterWorker's javadoc.
            com.google.firebase.auth.FirebaseUser userBeforeSignOut =
                    FirebaseAuth.getInstance().getCurrentUser();
            String uidBeforeSignOut = userBeforeSignOut != null ? userBeforeSignOut.getUid() : null;
            if (uidBeforeSignOut != null) {
                // FcmUnregisterWorker now uses FirebaseMessaging.deleteToken() directly —
                // no ID token capture needed. Enqueue immediately.
                com.duoshield.app.util.FcmUnregisterWorker
                        .enqueue(getApplicationContext(), uidBeforeSignOut);
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
        if (AppLockManager.shouldLock(this)) {
            if (!lockScreenActive) {
                Log.d(TAG, getClass().getSimpleName() + ": lock timeout exceeded → LockScreenActivity");
                lockScreenActive = true;
                Intent intent = new Intent(this, LockScreenActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        } else {
            AppLockManager.onAppForegrounded(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean shakeEnabled = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("shake_to_lock_enabled", false);
        if (shakeEnabled && PinManager.hasPinSet(this)) {
            shakeDetector = new ShakeDetector(this, this::onShakeToLock);
            shakeDetector.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shakeDetector != null) {
            shakeDetector.stop();
            shakeDetector = null;
        }
    }

    private void onShakeToLock() {
        if (!PinManager.hasPinSet(this)) return;
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
