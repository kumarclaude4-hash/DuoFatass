package com.duoshield.app.security;

import android.content.Context;
import android.content.SharedPreferences;

import com.duoshield.app.util.SecurePrefs;

/**
 * Wipe-surviving store for the account-lock intent and teardown resume marker.
 *
 * <h3>Why this exists (S06-H3)</h3>
 * An offline Firestore write queued locally is not durable across the subsequent
 * app-data wipe: the local mutation queue is erased before it can reach the server.
 * A remote lock therefore needs a request that reaches the server with a valid
 * credential.
 *
 * <h3>Durable intent and credential lifecycle</h3>
 * The lock <em>decision</em> is persisted before erasure in a container the wipe
 * deliberately preserves ({@link SecurePrefs#getSessionState}). The intent is
 * independent of its nonce. A warm, uid-bound server nonce may permit an immediate
 * attempt while offline, but it expires after one hour and may be absent when the
 * device has been offline.
 *
 * When the nonce is absent or expires, the intent is retained and the client does
 * <strong>not</strong> report the account as remotely locked. After the same account
 * next establishes a valid foreground session, {@link DuressManager#maintainLockCredential}
 * obtains a fresh nonce, rotates only that uid's pending intent and enqueues a bounded
 * retry. If the account never authenticates again or never regains connectivity, the
 * server-side lock cannot be guaranteed; the durable local intent remains an explicit
 * indication that the account may still be unlocked.
 *
 * <p>Nonce state is compare-and-cleared. A delayed WorkManager response carrying an
 * older token cannot erase a newer same-account intent or warm credential.
 *
 * <h3>Storage</h3>
 * All state is stored through {@link SecurePrefs#getSessionState}, which is preserved
 * by the canonical wipe path. No long-lived authentication token is persisted here;
 * the server-issued nonce is short-lived and single-use.
 *
 * <p><strong>Call-site invariant:</strong> {@link DuressManager#maintainLockCredential}
 * is called by {@code BaseActivity.onStart()} only for a valid foreground session.
 * It must remain wired there so a preserved intent can obtain a fresh nonce after the
 * same account next signs in.
 */

public final class PendingLockStore {

    private PendingLockStore() {}

    // Protect compare-and-clear transitions from late WorkManager responses racing
    // with a foreground refresh that has already installed a newer nonce.
    private static final Object LOCK_STATE_MUTEX = new Object();

    /** Teardown is in flight or was interrupted; drives the resume path (S06-M5). */
    private static final String KEY_RESET_PENDING = "session_migration_pending";

    /** Account the pending lock applies to. */
    private static final String KEY_SUBJECT       = "session_migration_subject";
    /** Server-issued single-use lock nonce for the pending intent. */
    private static final String KEY_INTENT_TOKEN  = "session_migration_token";
    /** When the intent was recorded (ms since epoch), for diagnostics only. */
    private static final String KEY_INTENT_TS     = "session_migration_ts";

    /** Pre-fetched nonce kept warm during normal online operation. */
    private static final String KEY_WARM_TOKEN    = "session_credential_token";
    private static final String KEY_WARM_TS       = "session_credential_ts";

    /**
     * Server said this account was unfrozen and owes a primary-PIN rotation.
     * Mirrors {@code accountLock/{uid}.rotationRequired} locally so the forced
     * rotation survives being backgrounded mid-flow (S06-M6).
     */
    private static final String KEY_ROTATION_DUE  = "session_rotation_due";

    /**
     * Screen 1 of the forced rotation (new primary PIN) has completed; screen 2
     * (fresh secondary/duress code) is what's outstanding. Lets a relaunch mid-flow
     * resume at the correct screen instead of restarting from the primary-PIN step.
     * Cleared together with {@link #KEY_ROTATION_DUE} only once the server ack
     * ({@code /acknowledgeRotation}) succeeds.
     */
    private static final String KEY_ROTATION_PRIMARY_DONE = "session_rotation_primary_done";

    /**
     * Screen 2 has armed a genuinely fresh secondary/duress code and is now only
     * waiting on the server ack. This is deliberately a separate flag from checking
     * {@code DuressManager.hasDuressPin()} directly: slot B can already be armed on
     * arrival at screen 2 in the wipe scenario (duress logout deliberately keeps the
     * old code armed so a restore of the same account stays gated), so "armed"
     * alone cannot distinguish "screen 2 hasn't run yet" from "screen 2 already
     * replaced it and is only waiting on the network call."
     */
    private static final String KEY_ROTATION_DURESS_DONE = "session_rotation_duress_done";

    /**
     * Whether the account this rotation applies to is unpaired (no conversation yet),
     * so the end of the 2-screen chain — or a relaunch mid-flow via
     * {@code MainActivity.route()} — lands on the correct final destination
     * ({@code AddContactActivity} vs {@code ConversationListActivity}).
     */
    private static final String KEY_ROTATION_UNPAIRED = "session_rotation_unpaired";

    private static SharedPreferences sp(Context ctx) {
        return SecurePrefs.getSessionState(ctx);
    }

    // ── Teardown resume marker (S06-M5) ───────────────────────────────────────

    /**
     * Marks a teardown as in flight. Uses {@code commit()} deliberately: this must be
     * on disk before the caller starts anything destructive, or a crash in the first
     * few milliseconds leaves an un-resumable half-wiped install.
     */
    public static void markResetPending(Context ctx) {
        try { sp(ctx).edit().putBoolean(KEY_RESET_PENDING, true).commit(); }
        catch (Exception ignored) {}
    }

    public static boolean isResetPending(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_RESET_PENDING, false); }
        catch (Exception e) { return false; }
    }

    public static void clearResetPending(Context ctx) {
        try { sp(ctx).edit().remove(KEY_RESET_PENDING).commit(); }
        catch (Exception ignored) {}
    }

    // ── Durable lock intent (S06-H3) ──────────────────────────────────────────

    /**
     * Records that {@code uid} must be locked, with whatever credential is available.
     * A null/empty nonce is still worth recording: the drain path can do nothing with
     * it, but {@link #hasLockIntent} then correctly reports that the account is
     * <em>believed unlocked</em>, which is what surfaces the failure instead of
     * swallowing it (S06-L4).
     */
    public static void recordLockIntent(Context ctx, String uid, String nonce) {
        if (uid == null || uid.isEmpty()) return;
        synchronized (LOCK_STATE_MUTEX) {
            try {
                SharedPreferences.Editor ed = sp(ctx).edit()
                        .putString(KEY_SUBJECT, uid)
                        .putLong(KEY_INTENT_TS, System.currentTimeMillis());
                if (nonce != null && !nonce.isEmpty()) ed.putString(KEY_INTENT_TOKEN, nonce);
                else ed.remove(KEY_INTENT_TOKEN); // never inherit a previous account's credential
                ed.commit();
            } catch (Exception ignored) {}
        }
    }

    public static boolean hasLockIntent(Context ctx) {
        try { return sp(ctx).getString(KEY_SUBJECT, null) != null; }
        catch (Exception e) { return false; }
    }

    public static String getSubject(Context ctx) {
        try { return sp(ctx).getString(KEY_SUBJECT, null); }
        catch (Exception e) { return null; }
    }

    public static String getIntentToken(Context ctx) {
        try { return sp(ctx).getString(KEY_INTENT_TOKEN, null); }
        catch (Exception e) { return null; }
    }

    /**
     * Replaces an expired/old intent credential only when the pending intent is
     * still for the same uid that authenticated the newly issued nonce. This is
     * called after sign-in/foreground credential refresh; it must never retarget
     * a pending lock for a different account.
     */
    public static boolean replaceIntentTokenIfSubjectMatches(Context ctx, String uid, String nonce) {
        if (ctx == null || uid == null || uid.isEmpty() || nonce == null || nonce.isEmpty()) {
            return false;
        }
        synchronized (LOCK_STATE_MUTEX) {
            try {
                SharedPreferences prefs = sp(ctx);
                if (!uid.equals(prefs.getString(KEY_SUBJECT, null))) return false;
                return prefs.edit().putString(KEY_INTENT_TOKEN, nonce).commit();
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** Clears the pending intent only when it still belongs to the completed account. */
    public static boolean clearLockIntentIfSubjectMatches(Context ctx, String uid) {
        if (ctx == null || uid == null || uid.isEmpty()) return false;
        synchronized (LOCK_STATE_MUTEX) {
            try {
                SharedPreferences prefs = sp(ctx);
                if (!uid.equals(prefs.getString(KEY_SUBJECT, null))) return false;
                return prefs.edit()
                        .remove(KEY_SUBJECT)
                        .remove(KEY_INTENT_TOKEN)
                        .remove(KEY_INTENT_TS)
                        .commit();
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * Destroys the intent. Call this <strong>only</strong> once the server has
     * confirmed the lock with a 200/204 response. An invalid, expired, or unknown
     * nonce is not evidence that a lock landed; those paths must retain the intent.
     */
    public static void clearLockIntent(Context ctx) {
        synchronized (LOCK_STATE_MUTEX) {
            try {
                sp(ctx).edit()
                       .remove(KEY_SUBJECT)
                       .remove(KEY_INTENT_TOKEN)
                       .remove(KEY_INTENT_TS)
                       .commit();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Clears a completed request only if the intent still carries that request's
     * nonce. A delayed response from an older WorkManager task must not erase a
     * newer same-account intent that has already been rotated to a fresh nonce.
     */
    public static boolean clearLockIntentIfTokenMatches(Context ctx, String nonce) {
        if (ctx == null || nonce == null || nonce.isEmpty()) return false;
        synchronized (LOCK_STATE_MUTEX) {
            try {
                SharedPreferences prefs = sp(ctx);
                if (!nonce.equals(prefs.getString(KEY_INTENT_TOKEN, null))) return false;
                return prefs.edit()
                        .remove(KEY_SUBJECT)
                        .remove(KEY_INTENT_TOKEN)
                        .remove(KEY_INTENT_TS)
                        .commit();
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ── Warm lock credential (S06-H3 secondary) ───────────────────────────────

    /**
     * Parks a short-lived nonce fetched during ordinary online operation. It can
     * support an offline trigger only while it remains within the server's one-hour
     * validity window; durable intent recovery obtains a fresh nonce after sign-in.
     */
    public static void putWarmToken(Context ctx, String nonce) {
        if (nonce == null || nonce.isEmpty()) return;
        synchronized (LOCK_STATE_MUTEX) {
            try {
                sp(ctx).edit()
                       .putString(KEY_WARM_TOKEN, nonce)
                       .putLong(KEY_WARM_TS, System.currentTimeMillis())
                       .commit();
            } catch (Exception ignored) {}
        }
    }

    public static String getWarmToken(Context ctx) {
        try { return sp(ctx).getString(KEY_WARM_TOKEN, null); }
        catch (Exception e) { return null; }
    }

    /** Age of the warm nonce in ms, or {@link Long#MAX_VALUE} if there isn't one. */
    public static long getWarmTokenAgeMs(Context ctx) {
        try {
            if (sp(ctx).getString(KEY_WARM_TOKEN, null) == null) return Long.MAX_VALUE;
            long ts = sp(ctx).getLong(KEY_WARM_TS, 0L);
            if (ts <= 0L) return Long.MAX_VALUE;
            return Math.max(0L, System.currentTimeMillis() - ts);
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    public static void clearWarmToken(Context ctx) {
        synchronized (LOCK_STATE_MUTEX) {
            try { sp(ctx).edit().remove(KEY_WARM_TOKEN).remove(KEY_WARM_TS).commit(); }
            catch (Exception ignored) {}
        }
    }

    /** Clears the cached nonce only when it is still the one that failed or completed. */
    public static boolean clearWarmTokenIfMatches(Context ctx, String nonce) {
        if (ctx == null || nonce == null || nonce.isEmpty()) return false;
        synchronized (LOCK_STATE_MUTEX) {
            try {
                SharedPreferences prefs = sp(ctx);
                if (!nonce.equals(prefs.getString(KEY_WARM_TOKEN, null))) return false;
                return prefs.edit().remove(KEY_WARM_TOKEN).remove(KEY_WARM_TS).commit();
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ── Forced rotation after unfreeze (S06-M6) ───────────────────────────────

    /** Sets the rotation intent for the given account shape. Resets screen-1/2 progress. */
    public static void setRotationDue(Context ctx, boolean unpaired) {
        try {
            sp(ctx).edit()
                   .putBoolean(KEY_ROTATION_DUE, true)
                   .putBoolean(KEY_ROTATION_UNPAIRED, unpaired)
                   .remove(KEY_ROTATION_PRIMARY_DONE)
                   .remove(KEY_ROTATION_DURESS_DONE)
                   .commit();
        } catch (Exception ignored) {}
    }

    /** Clears the rotation intent entirely. Call only once the server ack succeeds. */
    public static void clearRotationDue(Context ctx) {
        try {
            sp(ctx).edit()
                   .remove(KEY_ROTATION_DUE)
                   .remove(KEY_ROTATION_PRIMARY_DONE)
                   .remove(KEY_ROTATION_DURESS_DONE)
                   .remove(KEY_ROTATION_UNPAIRED)
                   .commit();
        } catch (Exception ignored) {}
    }

    public static boolean isRotationDue(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_ROTATION_DUE, false); }
        catch (Exception e) { return false; }
    }

    public static void setRotationPrimaryDone(Context ctx, boolean done) {
        try {
            if (done) sp(ctx).edit().putBoolean(KEY_ROTATION_PRIMARY_DONE, true).commit();
            else      sp(ctx).edit().remove(KEY_ROTATION_PRIMARY_DONE).commit();
        } catch (Exception ignored) {}
    }

    public static boolean isRotationPrimaryDone(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_ROTATION_PRIMARY_DONE, false); }
        catch (Exception e) { return false; }
    }

    public static void setRotationDuressDone(Context ctx, boolean done) {
        try {
            if (done) sp(ctx).edit().putBoolean(KEY_ROTATION_DURESS_DONE, true).commit();
            else      sp(ctx).edit().remove(KEY_ROTATION_DURESS_DONE).commit();
        } catch (Exception ignored) {}
    }

    public static boolean isRotationDuressDone(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_ROTATION_DURESS_DONE, false); }
        catch (Exception e) { return false; }
    }

    /** Account shape recorded when the rotation was set — drives the final destination. */
    public static boolean isRotationUnpaired(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_ROTATION_UNPAIRED, false); }
        catch (Exception e) { return false; }
    }
}
