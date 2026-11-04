package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;

public class AppLockManager {

    private static final String PREFS               = "duoshield_prefs";
    private static final String KEY_LOCK_TS         = "app_lock_bg_ts";
    private static final String KEY_AUTO_SIGNOUT_MS = "auto_signout_ms";
    /** SharedPreferences key — written by SettingsActivity lock-timeout picker. */
    public  static final String KEY_LOCK_TIMEOUT_MS = "lock_timeout_ms";
    /** Default PIN lock timeout: 3 minutes (used when the user has not changed the setting). */
    public  static final long   DEFAULT_LOCK_TIMEOUT = 3 * 60 * 1000L;

    /**
     * Counts the number of Activities in the STARTED state.
     *
     * <p>The previous implementation tied the background timestamp to
     * {@code BaseActivity.onPause()} / the {@code onStart()} else-branch.  This
     * works for simple flows but breaks when system dialogs, permission prompts,
     * or picture-in-picture windows pause the current Activity without actually
     * backgrounding the app — the 3-minute timer would start even though the user
     * was still interacting with the app (BUG-D09).
     *
     * <p>A reference count lets us detect true app-level transitions:
     * <ul>
     *   <li>Count 0 → 1: app came to foreground — clear bgTs.</li>
     *   <li>Count 1 → 0: app went to background — record bgTs.</li>
     *   <li>Any other transition: no-op (navigating between Activities).</li>
     * </ul>
     */
    private static final java.util.concurrent.atomic.AtomicInteger startedCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static void init(Context ctx) {}

    /**
     * Call from {@code BaseActivity.onStart()} — increments the activity reference
     * count ONLY.  Does NOT clear the background timestamp; that must happen in the
     * {@code else} branch of the lock check AFTER {@link #shouldLock} and
     * {@link #shouldAutoSignOut} have had a chance to evaluate the stale bgTs.
     *
     * <p>Clearing bgTs here (before the lock check) would cause the lock to
     * never trigger — the background timeout would be zeroed out right before
     * the check runs.
     */
    public static void onActivityStarted() {
        startedCount.incrementAndGet();
    }

    /**
     * Call from {@code BaseActivity.onStop()}.  When the count falls from 1 to 0
     * all Activities are stopped — the app is in the background — so we record bgTs.
     * Navigating between activities only changes the count transiently without ever
     * hitting zero (BUG-D09).
     */
    public static void onActivityStopped(Context ctx) {
        int count = startedCount.decrementAndGet();
        if (count <= 0) {
            startedCount.set(0); // guard against spurious negatives
            onAppBackgrounded(ctx);
        }
    }

    public static void onAppBackgrounded(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putLong(KEY_LOCK_TS, System.currentTimeMillis()).apply();
    }

    /**
     * True while a background timestamp is pending — i.e. the app was in the background the
     * last time anything recorded it and nothing has since confirmed the user is back (and
     * unlocked). Read-only; used by screens that are not a {@code BaseActivity} (the call
     * screens) to decide whether counting themselves as a foreground activity would overwrite
     * an older timestamp when they stop.
     */
    public static boolean hasBackgroundTimestamp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LOCK_TS, 0) != 0;
    }

    public static void onAppForegrounded(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putLong(KEY_LOCK_TS, 0).apply();
    }

    /**
     * Returns true when the app has been in the background longer than the
     * user-configured auto sign-out threshold. Uses the same background timestamp
     * as the PIN lock so no extra storage is needed.
     *
     * When this returns true, BaseActivity signs the user out of Firebase entirely
     * (stronger than the PIN lock, which just shows the lock screen).
     */
    public static boolean shouldAutoSignOut(Context ctx) {
        // Always evaluate auto sign-out, even on cold start (process re-creation after kill).
        // Security rationale: if the user has been away longer than their configured inactivity
        // threshold — whether the app was backgrounded or fully killed — they should be signed
        // out.  The old "cold start guard" that returned false here allowed users to bypass the
        // timer simply by force-quitting the app, which is the opposite of secure behaviour.
        // The cold-start guard only applies to the PIN lock (shouldLock) where a "flash and
        // dismiss" loop would occur; auto sign-out has no such risk.
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long thresholdMs = prefs.getLong(KEY_AUTO_SIGNOUT_MS, 0);
        if (thresholdMs <= 0) return false;
        long bgTs = prefs.getLong(KEY_LOCK_TS, 0);
        if (bgTs == 0) return false;
        return (System.currentTimeMillis() - bgTs) > thresholdMs;
    }

    /**
     * Bug 9 fix: shouldLock() now only returns true when a PIN is actually set.
     *
     * Previously, biometric_enabled alone could trigger shouldLock(), which would
     * launch LockScreenActivity — but LockScreenActivity immediately finishes() if
     * no PIN is set (hasPinSet() == false), creating a flash-and-dismiss loop.
     * Worse, if biometric fails and no PIN is set, the user has no fallback.
     *
     * Biometric is an authentication METHOD used inside LockScreenActivity, not an
     * independent lock trigger. A PIN is always required as the root credential;
     * biometric just provides a faster path to unlock.
     */
    public static boolean shouldLock(Context ctx) {
        // Require PIN — biometric is auth method, not lock trigger (Bug 9 fix)
        if (!PinManager.hasPinSet(ctx)) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long bgTs = prefs.getLong(KEY_LOCK_TS, 0);
        if (bgTs == 0) return false;
        long timeout = prefs.getLong(KEY_LOCK_TIMEOUT_MS, DEFAULT_LOCK_TIMEOUT);
        // 0 = "Immediately" — lock whenever bgTs is set (any background excursion)
        if (timeout == 0L) return true;
        return (System.currentTimeMillis() - bgTs) > timeout;
    }
}
