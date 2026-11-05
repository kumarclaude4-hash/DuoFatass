package com.duoshield.app.security;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.duoshield.app.BuildConfig;
import com.duoshield.app.SignInActivity;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.backup.BackupScheduler;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.SecurePrefs;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class DuressManager {

    private static final String PREFS_NAME          = "duoshield_prefs";

    /**
     * Secondary unlock slot. Deliberately named as an ordinary "slot B" rather than
     * anything containing "duress": these keys live in SecurePrefs, but a neutral
     * name means a dump of that store still reads like routine PIN plumbing instead
     * of naming a feature that is supposed to be undiscoverable.
     *
     * <p>Superseded {@code duress_pin_hash_<uid>} / {@code duress_pin_hash}, which
     * are still read once for migration in {@link #migrateLegacySlot}.
     */
    private static final String KEY_SLOT_B_PREFIX   = "pin_slot_b_";
    private static final String KEY_SLOT_B_ARMED    = "pin_slot_b_armed_";
    private static final String KEY_DURESS_PREFIX   = "duress_pin_hash_";
    private static final String KEY_DURESS_LEGACY   = "duress_pin_hash";
    private static final String KEY_ELIGIBLE_PREFIX = "duress_eligible_";
    private static final int    ITERATIONS          = 310_000;
    private static final int    KEY_LEN             = 256;

    // Server-issued fallback nonces expire after one hour. Refresh with a
    // safety margin so an ordinary foreground session does not keep a dead token.
    private static final long LOCK_NONCE_REFRESH_INTERVAL_MS = 30L * 60 * 1000;
    private static final AtomicBoolean LOCK_CREDENTIAL_REFRESH_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean DURESS_TRIGGER_IN_PROGRESS = new AtomicBoolean(false);
    private static final Object LOCK_NONCE_ISSUE_MUTEX = new Object();

    /**
     * Neutral replacement for the old {@code duress_wipe_in_progress} flag.
     *
     * <p>The old flag was a self-describing English string written to the
     * <em>unencrypted</em> {@code duoshield_prefs} XML, and it is only cleared at the
     * end of the wipe — up to ~20s later. A force-stop, crash, reboot or dead battery
     * inside that window left a plaintext file on disk stating that a duress wipe had
     * happened here, which is strictly worse than any other residue in the app: it
     * needs no interpretation. Now stored in SecurePrefs under a name that reads like
     * ordinary session plumbing.
     */
    private static final String KEY_RESET_PENDING   = "session_migration_pending";
    /** Legacy plaintext flag; read once so an interrupted old-build wipe still routes. */
    private static final String KEY_RESET_PENDING_LEGACY = "duress_wipe_in_progress";

    /**
     * Returns the UID-scoped SecurePrefs key for the currently signed-in user,
     * or {@code null} if no user is signed in.
     *
     * <h3>Why UID-scoped?</h3>
     * Duress logout intentionally keeps the hash so that a restore attempt for
     * the same account is still gated. But a brand-new user signing in on the
     * same device must not inherit the old account's duress PIN — that would
     * be indistinguishable from the old account still being active.
     */
    private static String duressKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_SLOT_B_PREFIX + user.getUid() : null;
    }

    private static String armedKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_SLOT_B_ARMED + user.getUid() : null;
    }

    /**
     * Moves a pre-existing {@code duress_pin_hash*} value into slot B and marks it
     * armed. Idempotent; safe to call on every access.
     */
    private static void migrateLegacySlot(SharedPreferences sp, String slotKey, String armKey) {
        String legacyUidKey = legacyUidKey();
        String legacy = legacyUidKey != null ? sp.getString(legacyUidKey, null) : null;
        if (legacy == null) legacy = sp.getString(KEY_DURESS_LEGACY, null);
        if (legacy == null) return;
        SharedPreferences.Editor ed = sp.edit()
                .putString(slotKey, legacy)
                .putBoolean(armKey, true)
                .remove(KEY_DURESS_LEGACY);
        if (legacyUidKey != null) ed.remove(legacyUidKey);
        ed.apply();
    }

    /** Pre-migration UID-scoped key name, or null if no user is signed in. */
    private static String legacyUidKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_DURESS_PREFIX + user.getUid() : null;
    }

    /**
     * Guarantees slot B holds a well-formed {@code salt:hash} value for the current
     * account, writing an unmatchable random decoy if it is empty.
     *
     * <h3>Why every account needs a decoy</h3>
     * {@code isDuressPin()} used to return early when no secondary hash was stored,
     * so an account <em>with</em> a secondary code ran two PBKDF2 derivations per
     * unlock attempt while an account without ran one. At 310,000 iterations that is
     * a few hundred milliseconds — the "Verifying…" state was visibly about twice as
     * long, on every unlock and every wrong guess, with {@code DevicePinGateActivity}
     * conveniently providing a one-derivation baseline to compare against. No tooling
     * was required to detect it. That leak defeated the hidden UI row, the
     * server-side enrollment gate, and every other measure taken to keep the
     * capability undiscoverable.
     *
     * <p>Both halves matter and both are handled here: the <em>work</em> is now
     * constant (always exactly one derivation against slot B) and the <em>stored
     * shape</em> is constant (every account has a slot B of identical form, so its
     * presence discloses nothing). The decoy is 32 random bytes, so no PIN can ever
     * match it.
     *
     * <p>Called from {@code PinManager.setPin} so enrolled and non-enrolled accounts
     * are indistinguishable from the moment a PIN first exists.
     *
     * <p><strong>Residual risk, stated honestly:</strong> {@code pin_slot_b_armed_*}
     * still distinguishes a real secondary code from a decoy to anyone who can read
     * SecurePrefs itself (root on an unlocked device). Removing that last bit would
     * require deriving armed-ness from the entered PIN, which is a substantially
     * larger change. What this closes is the <em>remote/observational</em> leak that
     * needed nothing but a wall clock.
     */
    public static void ensureSecondarySlotInitialized(Context context) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        if (slotKey == null || armKey == null) return;
        SharedPreferences sp = SecurePrefs.get(context);
        migrateLegacySlot(sp, slotKey, armKey);
        if (sp.getString(slotKey, null) != null) return;
        try {
            byte[] salt  = new byte[16];
            byte[] decoy = new byte[KEY_LEN / 8];
            SecureRandom rng = new SecureRandom();
            rng.nextBytes(salt);
            rng.nextBytes(decoy);
            sp.edit()
              .putString(slotKey, bytesToHex(salt) + ":" + bytesToHex(decoy))
              .putBoolean(armKey, false)
              .apply();
        } catch (Exception e) {
            android.util.Log.e("DuressManager", "Failed to initialise secondary slot", e);
        }
    }

    /**
     * Arms slot B with a real secondary code.
     *
     * @return false if the code was rejected. The rejection reason is a length
     *         outside the app-wide accepted PIN range — see the length note in
     *         {@code ManageUnlockCodesActivity}, which enforces the identical
     *         {@code MIN_PIN_LEN}..{@code MAX_PIN_LEN} bound before ever reaching
     *         here — so the code stays enterable on the lock screen.
     *
     *         <p>Before S08-L3 this instead required an exact length match against
     *         {@code PinManager.getPinLength(context)} (the account's real primary
     *         PIN length, then still readable from plaintext storage). That method
     *         now always returns the fixed {@code MAX_PIN_LEN} upper bound — it no
     *         longer reflects any one account's real length — so an exact-match
     *         gate here would incorrectly reject every secondary code shorter than
     *         {@code MAX_PIN_LEN} digits even though {@code LockScreenActivity}'s
     *         debounced auto-submit (also S08-L3) accepts any length in the same
     *         {@code MIN_PIN_LEN}..{@code MAX_PIN_LEN} range the primary PIN can be.
     */
    public static boolean setDuressPin(Context context, String pin) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        if (slotKey == null || armKey == null) return false;
        if (pin == null
                || pin.length() < com.duoshield.app.util.PinManager.MIN_PIN_LEN
                || pin.length() > com.duoshield.app.util.PinManager.MAX_PIN_LEN) {
            return false;
        }
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash   = pbkdf2(pin, salt);
            String stored = bytesToHex(salt) + ":" + bytesToHex(hash);
            SecurePrefs.get(context).edit()
                    .putString(slotKey, stored)
                    .putBoolean(armKey, true)
                    .remove(KEY_DURESS_LEGACY)
                    .apply();
            return true;
        } catch (Exception e) {
            android.util.Log.e("DuressManager", "Failed to store secondary code", e);
            return false;
        }
    }

    /**
     * Constant-work check of the entered PIN against slot B.
     *
     * <p>Always performs exactly one PBKDF2 derivation for every account, armed or
     * not — including the no-user and malformed-value paths, which derive against an
     * ephemeral decoy rather than returning early. Do not add a fast path here; the
     * timing difference is directly observable by the user of the device. See
     * {@link #ensureSecondarySlotInitialized}.
     */
    public static boolean isDuressPin(Context context, String enteredPin) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        String stored  = null;
        boolean armed  = false;

        if (slotKey != null && armKey != null) {
            SharedPreferences sp = SecurePrefs.get(context);
            migrateLegacySlot(sp, slotKey, armKey);
            stored = sp.getString(slotKey, null);
            armed  = sp.getBoolean(armKey, false);
        }

        // Derive against a throwaway decoy when slot B is absent or malformed, so the
        // work performed is identical to the armed case.
        byte[] salt, expected;
        try {
            int sep = stored != null ? stored.indexOf(':') : -1;
            if (sep > 0) {
                salt     = hexToBytes(stored.substring(0, sep));
                expected = hexToBytes(stored.substring(sep + 1));
            } else {
                salt     = new byte[16];
                expected = new byte[KEY_LEN / 8];
                SecureRandom rng = new SecureRandom();
                rng.nextBytes(salt);
                rng.nextBytes(expected);
                armed = false;
            }
            byte[] actual = pbkdf2(enteredPin, salt);
            boolean match = constantTimeEquals(expected, actual);
            return armed && match;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Like {@link #isDuressPin}, but also treats {@code candidatePlaintext} as
     * colliding with the stored secondary code if any leading prefix of it (down to
     * {@code PinManager.MIN_PIN_LEN} digits) matches slot B — i.e. catches "the
     * secondary code is the first N digits of this candidate", not just an exact
     * match.
     *
     * <p>Use this wherever a new/changed <em>primary</em> PIN is being validated
     * against an existing secondary code (see {@code SetupPinActivity} and
     * {@code SecurityPrivacySettingsActivity.doSavePin}). An exact-match-only check
     * left a real gap: {@code LockScreenActivity} verifies on either reaching
     * {@code MAX_PIN_LEN} or an explicit confirm tap, not on a fixed length, so if
     * the secondary code happens to be a leading prefix of the primary PIN, a
     * deliberate confirm partway through typing the primary PIN — or, previously,
     * an accidental debounce timeout — reads as the secondary code instead.
     *
     * <p><strong>Does not catch the reverse direction</strong> — {@code
     * candidatePlaintext} being a short prefix of a <em>longer</em> stored
     * secondary code — because the secondary code's own remaining digits are
     * unknown and unrecoverable from its hash; closing that direction would mean
     * brute-forcing up to ~110 PBKDF2 derivations or asking the user to re-enter
     * their secondary code outside the lock screen, which this class deliberately
     * never does (see the no-trace design notes throughout this file). That
     * direction is closed instead in {@code ManageUnlockCodesActivity}, which
     * re-authenticates with the real primary PIN's plaintext before a secondary
     * code is ever saved and can therefore check both prefix directions directly,
     * with no hashing at all.
     *
     * <p>Deliberately does <em>not</em> preserve {@link #isDuressPin}'s
     * constant-work guarantee: it derives once per length tried (1–3 derivations
     * depending on {@code candidatePlaintext.length()}). That guarantee exists to
     * stop a remote/timing observer on the lock-screen unlock loop from learning
     * whether a secondary code exists at all. This method only ever runs from an
     * already-authenticated Settings/setup screen, checking the account owner's
     * own freshly-typed PIN — there is no equivalent third-party observer here for
     * a length-dependent run time to leak information to.
     */
    public static boolean isDuressPinOrPrefixOfIt(Context context, String candidatePlaintext) {
        if (candidatePlaintext == null) return false;
        if (isDuressPin(context, candidatePlaintext)) return true;
        for (int len = PinManager.MIN_PIN_LEN; len < candidatePlaintext.length(); len++) {
            if (isDuressPin(context, candidatePlaintext.substring(0, len))) return true;
        }
        return false;
    }

    /** Returns true if slot B holds a real secondary code (not a decoy). */
    public static boolean hasDuressPin(Context context) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        if (slotKey == null || armKey == null) return false;
        SharedPreferences sp = SecurePrefs.get(context);
        migrateLegacySlot(sp, slotKey, armKey);
        return sp.getBoolean(armKey, false);
    }

    /**
     * Disarms slot B by overwriting it with a fresh unmatchable decoy.
     *
     * <p>Deliberately not a {@code remove()}: deleting the entry would restore the
     * one-derivation-vs-two timing difference described in
     * {@link #ensureSecondarySlotInitialized}, so clearing a secondary code would
     * become observable as the unlock screen suddenly getting faster.
     */
    public static void clearDuressPin(Context context) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        if (slotKey == null || armKey == null) return;
        try {
            byte[] salt  = new byte[16];
            byte[] decoy = new byte[KEY_LEN / 8];
            SecureRandom rng = new SecureRandom();
            rng.nextBytes(salt);
            rng.nextBytes(decoy);
            SharedPreferences.Editor ed = SecurePrefs.get(context).edit()
                    .putString(slotKey, bytesToHex(salt) + ":" + bytesToHex(decoy))
                    .putBoolean(armKey, false)
                    .remove(KEY_DURESS_LEGACY);
            String legacyUidKey = legacyUidKey();
            if (legacyUidKey != null) ed.remove(legacyUidKey);
            ed.apply();
        } catch (Exception e) {
            android.util.Log.e("DuressManager", "Failed to disarm secondary slot", e);
        }
    }

    /**
     * True if a local reset is mid-flight or was interrupted. Routing screens use this
     * to send the user to sign-in instead of a half-wiped session.
     *
     * <p>Reads three locations in descending order of authority:
     * <ol>
     *   <li>{@link PendingLockStore} — the wipe-surviving container. This is the only
     *       one that can still answer after the erasure has begun.</li>
     *   <li>The account-scoped {@code SecurePrefs} key this flag used to live in.
     *       That location is <em>itself</em> destroyed by step 4 of the wipe, which is
     *       precisely why the resume path could never work: a crash anywhere after
     *       step 4 left the remaining steps undone with nothing left on disk saying so
     *       (S06-M5). Still read here so an upgrade that lands mid-wipe still routes.</li>
     *   <li>The pre-rename plaintext {@code duoshield_prefs} flag, for the same reason.</li>
     * </ol>
     */
    public static boolean isResetPending(Context context) {
        if (PendingLockStore.isResetPending(context)) return true;
        try {
            if (SecurePrefs.get(context).getBoolean(KEY_RESET_PENDING, false)) return true;
        } catch (Exception ignored) {}
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                      .getBoolean(KEY_RESET_PENDING_LEGACY, false);
    }

    /**
     * Re-runs an interrupted teardown to completion, then clears the marker.
     *
     * <h3>Why this is mandatory (S06-M5)</h3>
     * {@code performLogout} does real work before it erases anything: a panic sync, a
     * lock write, and previously a nonce round trip — up to ~30 seconds during which the
     * SQLCipher database, slot B, and all key material were fully intact. A
     * network-controlling adversary can <em>force</em> every one of those calls to its
     * full timeout, maximising the window deliberately. If the process died in that
     * window — force-stop, low-memory kill, dead battery, reboot — the erasure simply
     * never ran, and <strong>nothing retried it</strong>. {@code isResetPending} had two
     * consumers and both only <em>routed</em>; neither re-invoked the wipe. All data
     * survived.
     *
     * <p>Making the marker a resume trigger is what makes the wipe idempotent and
     * crash-safe, independent of how the ordering question is resolved.
     *
     * <p>Also completes the promote-and-rotate half: an interruption between the
     * promotion and the erasure could otherwise leave the old primary PIN valid again,
     * which is the two-valid-codes disclosure §8 warns about. The promotion is written
     * before the sync for exactly this reason, so by the time this method can be
     * reached the promotion has already landed and only erasure is outstanding.
     *
     * <p>Must not run on the main thread during normal operation; call it from
     * application startup before any UI, where blocking is correct.
     *
     * @return true if a teardown was resumed
     */
    public static boolean resumeInterruptedResetIfNeeded(Context context) {
        if (!isResetPending(context)) return false;
        android.util.Log.i("DuressManager", "Interrupted teardown detected — completing it.");
        try {
            com.duoshield.app.util.WipeHelper.eraseLocalData(
                    context, com.duoshield.app.util.WipeHelper.WipeMode.DURESS);
        } catch (Exception e) {
            // Leave the marker set: a wipe that failed must be retried on the next
            // launch, not forgotten. Returning true still keeps routing at sign-in.
            android.util.Log.e("DuressManager", "Resumed teardown failed; will retry next launch", e);
            return true;
        }
        clearResetMarkers(context);
        return true;
    }

    /** Clears the resume marker in all three locations it may live in. */
    private static void clearResetMarkers(Context context) {
        PendingLockStore.clearResetPending(context);
        try {
            SecurePrefs.get(context).edit().remove(KEY_RESET_PENDING).apply();
        } catch (Exception ignored) {}
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().remove(KEY_RESET_PENDING_LEGACY).apply();
        } catch (Exception ignored) {}
    }

    /**
     * "Sync then Wipe" — plausible-deniability duress logout.
     *
     * <p>Triggered exclusively by an exact duress-PIN match in
     * {@code LockScreenActivity}. There is no wrong-guess-count fallback — see
     * {@code LockScreenActivity}'s javadoc for why that was removed.
     *
     * <h3>Sequence</h3>
     * <ol>
     *   <li><b>Instant navigation</b> — {@code SignInActivity} starts immediately
     *       with {@code FLAG_ACTIVITY_CLEAR_TASK}. The chat screen disappears at once.</li>
     *   <li><b>Panic sync</b> — {@link BackupManager#syncIncrementalSync} uploads any
     *       unsynced messages to Firestore. Hard deadline: 10 seconds.</li>
     *   <li><b>Destructive local wipe</b>:
     *     <ul>
     *       <li>Room DB closed and deleted ({@code duoshield_db}).</li>
     *       <li>All {@link SecurePrefs} keys destroyed synchronously ({@code .commit()}).</li>
     *       <li>Local contact backup cleared.</li>
     *       <li>All SharedPreferences files cleared synchronously.</li>
     *     </ul>
     *   </li>
     *   <li><b>Firebase sign-out</b> — local only, no Firestore writes or deletes.</li>
     * </ol>
     *
     * <h3>Security guarantees</h3>
     * <ul>
     *   <li>No cloud deletion — Firestore data is preserved for recovery via seed phrase.</li>
     *   <li>Forensic resistance — SQLCipher DB file and all key material removed from NAND.</li>
     *   <li>Plausible deniability — device presents as unconfigured/factory-reset.</li>
     * </ul>
     *
     * <h3>Recovery</h3>
     * User opens the (now empty) app, selects "Restore Account", enters their 12-word
     * seed phrase. {@code RestoreFromSeedActivity} re-derives keys and pulls all chats
     * (including those uploaded by the panic sync) back from Firestore.
     *
     * <p><strong>Silent:</strong> no Toast, no dialog, no animation visible to an observer.
     *
     * @deprecated use {@link #performLogout(Context, String)} — this overload cannot
     *             perform the promote-and-rotate step (S06-C1) because it never receives
     *             the entered PIN's plaintext.
     */
    @Deprecated
    public static void performLogout(Context context) {
        performLogout(context, null);
    }

    /**
     * "Sync then Wipe" — plausible-deniability duress logout.
     *
     * @param enteredPin the plaintext duress PIN that was just matched, captured by the
     *                    caller before this call. Used only in-process, only to promote
     *                    it to the device-gate PIN (see "Promote-and-rotate" below), and
     *                    is never stored, logged, or transmitted.
     *
     * <h3>Sequence (S06-I4 / S06-I2 / S06-C1 corrected ordering)</h3>
     * <ol>
     *   <li><b>Instant navigation</b> — {@code SignInActivity} starts immediately.</li>
     *   <li><b>Promote-and-rotate (local, before anything else)</b> — see below.</li>
     *   <li><b>Durable lock intent</b> recorded in {@link PendingLockStore}, which the
     *       wipe does not touch (S06-H3).</li>
     *   <li><b>Panic sync</b> — uploads unsynced messages. Deliberately runs
     *       <em>before</em> the account-lock write: {@code firestore.rules}' backup
     *       collections now deny writes once {@code accountLock.locked == true}
     *       (S06-C2 part 3), so locking first would make the sync this step exists to
     *       perform fail for every online trigger (S06-I4).</li>
     *   <li><b>Account-lock write</b>, only now, with real success detection
     *       ({@code task.isSuccessful()}, not merely "the task completed") driving
     *       whether the durable intent is cleared or left for the drain/worker retry
     *       path (S06-I2).</li>
     *   <li><b>Destructive local wipe.</b></li>
     * </ol>
     *
     * <h3>Promote-and-rotate (S06-C1)</h3>
     * The device-level PIN gate ({@code SecurePrefs.getDeviceGate()}) is a container the
     * wipe deliberately never touches — see {@code PinManager}'s class javadoc — so after
     * the wipe, reopening the app on this device hits that gate holding whatever PIN was
     * last synced to it, almost always the <em>primary</em> PIN. An adversary who forces
     * the app closed and reopens it then finds a PIN gate on a supposedly "unconfigured"
     * device (tell #1) and gets "Wrong PIN" when re-entering the code the victim just
     * surrendered (tell #2) — proof the surrendered code was special. Overwriting the
     * device gate with the duress PIN itself, before any erasure runs, means the
     * surrendered code keeps working and opens the now-empty app: no gate-existence tell,
     * no wrong-PIN tell. Slot B is disarmed in the same step so re-entering that code
     * later cannot re-trigger the whole sequence a second time.
     *
     * <p><strong>Silent:</strong> no Toast, no dialog, no animation visible to an observer.
     */
    public static void performLogout(Context context, String enteredPin) {
        if (!DURESS_TRIGGER_IN_PROGRESS.compareAndSet(false, true)) {
            android.util.Log.w("DuressManager", "Duress teardown already in progress; ignoring duplicate trigger.");
            return;
        }
        try {
        // Capture the UID before anything below signs out or wipes prefs — both would
        // erase the one piece of information the delayed FCM de-registration job needs.
        FirebaseUser userBeforeWipe = FirebaseAuth.getInstance().getCurrentUser();
        String uidBeforeWipe = userBeforeWipe != null ? userBeforeWipe.getUid() : null;

        final Context appCtx = context.getApplicationContext();

        // Enqueue FcmUnregisterWorker immediately — no credential needed
        // (FirebaseMessaging.deleteToken() handles its own auth, and no uid is needed
        // either — see S06-H2, the job no longer carries one).
        com.duoshield.app.util.FcmUnregisterWorker.enqueue(appCtx);

        // ── Promote-and-rotate (S06-C1) — local-only, before anything else runs ──
        //
        // Order matters: this must land before the wipe destroys the account-scoped
        // PIN hash and before any network call that could be delayed or interrupted.
        // If the process dies between this line and the wipe completing, resuming the
        // wipe (resumeInterruptedResetIfNeeded) is safe to re-run this step — it is
        // idempotent, and PinManager.setDevicePin/DuressManager.clearDuressPin are both
        // plain overwrites.
        if (enteredPin != null && !enteredPin.isEmpty()) {
            try {
                com.duoshield.app.util.PinManager.setDevicePin(context, enteredPin);
            } catch (Exception e) {
                android.util.Log.e("DuressManager", "Device-gate promotion failed", e);
            }
            // Disarm slot B so re-entering this same code later (after the account is
            // restored and this code becomes the primary again) cannot re-trigger the
            // whole duress sequence a second time. Overwrites with a fresh decoy rather
            // than removing — see clearDuressPin's javadoc on why that matters for
            // timing parity.
            clearDuressPin(context);
        }

        // ── Durable lock intent (S06-H3) — recorded before any erasure, in a
        // container the wipe never touches. A warm (pre-fetched) nonce from
        // maintainLockCredential() covers the case where this trigger happens
        // offline, or the synchronous write below fails.
        if (uidBeforeWipe != null) {
            String warmToken = PendingLockStore.getWarmToken(appCtx);
            PendingLockStore.recordLockIntent(appCtx, uidBeforeWipe, warmToken);
        }

        // F30 fix, now backed by the wipe-surviving store (S06-M5): write the
        // routing-guard / resume flag to PendingLockStore's session-state file
        // BEFORE launching SignInActivity, so a process death anywhere in this
        // method — not just after step 3 starts — leaves something on disk that
        // says the teardown must be resumed on next launch. The legacy account-scoped
        // copy is also written for any code path that still reads it directly, but it
        // is the account-scoped file the wipe itself destroys, which is exactly why it
        // could never answer this question on its own (S06-M5).
        PendingLockStore.markResetPending(appCtx);
        try {
            SecurePrefs.get(context).edit().putBoolean(KEY_RESET_PENDING, true).commit();
        } catch (Exception e) {
            android.util.Log.e("DuressManager", "Failed to set legacy reset-pending flag", e);
        }

        // Drop any leftover forced-rotation intent from a PREVIOUS unfreeze cycle.
        //
        // The rotation flags live in the same wipe-surviving session-state file as
        // the marker above, precisely so a rotation survives being backgrounded
        // mid-flow. The cost is that they also survive a duress wipe, which is a
        // different kind of event entirely: this teardown ends the account's life on
        // this device, so whatever rotation was outstanding is now meaningless.
        // Leaving them behind is what made the secondary-PIN rotation screen crash
        // on any install whose data had not been cleared — a stale
        // session_rotation_duress_done sent the screen straight into its ack-retry
        // panel with no PIN ever collected. Cleared here, at the one point where the
        // rotation is definitively obsolete; the server re-sets rotationRequired from
        // scratch on the next unfreeze, so nothing legitimate is lost.
        PendingLockStore.clearRotationDue(appCtx);

        // 1. Instant navigation — removes chat screen from view immediately.
        //    To an observer, it looks like the app is simply processing the PIN.
        Intent intent = new Intent(context, SignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);

        // Cancel the daily backup sync — the session is intentionally destroyed.
        try { BackupScheduler.cancel(context); } catch (Exception ignored) {}

        // Full "sync then wipe" on a background thread
        try {
        new Thread(() -> {
          try {
            // 2. Panic sync — upload unsynced messages to Firestore before local wipe,
            //    and BEFORE the account is locked (S06-I4: the backup rules now deny
            //    writes to a locked account, so this order is no longer optional).
            //    Hard deadline: 10 seconds. If the sync doesn't finish in time,
            //    BackupManager aborts automatically and we proceed to the wipe.
            BackupManager.syncIncrementalSync(context);

            // 3. Synchronous account-lock write — now that the panic sync has had its
            //    chance to run against an unlocked account. A 5-second cap keeps the
            //    wipe responsive. task.isSuccessful() — not merely "the task
            //    completed" — is what determines whether the durable intent recorded
            //    above is cleared (S06-I2): a completed-but-failed task must fall
            //    through to the nonce-based retry, not be treated as done.
            boolean lockConfirmed = false;
            if (uidBeforeWipe != null) {
                try {
                    final Object    lockSync = new Object();
                    final boolean[] done     = {false};
                    final boolean[] ok       = {false};
                    java.util.Map<String, Object> lockData = new java.util.HashMap<>();
                    lockData.put("locked",   true);
                    lockData.put("lockedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                    FirebaseFirestore.getInstance()
                            .collection("accountLock")
                            .document(uidBeforeWipe)
                            .set(lockData)
                            .addOnCompleteListener(task -> {
                                synchronized (lockSync) {
                                    ok[0]   = task.isSuccessful();
                                    done[0] = true;
                                    lockSync.notifyAll();
                                }
                            });
                    synchronized (lockSync) {
                        if (!done[0]) lockSync.wait(5_000);
                        lockConfirmed = done[0] && ok[0];
                    }
                    android.util.Log.d("DuressManager",
                            "Synchronous account-lock write " + (lockConfirmed ? "confirmed." : "did not confirm."));
                } catch (Exception ignored) {
                    android.util.Log.w("DuressManager",
                            "Synchronous account-lock write failed — will attempt nonce retry.");
                }
            }

            if (lockConfirmed) {
                // Confirmed by the live session — the durable intent recorded above
                // has already done its job; drop it so a stale record does not sit
                // around and does not get redundantly retried by the drain path.
                PendingLockStore.clearLockIntentIfSubjectMatches(appCtx, uidBeforeWipe);
            } else if (uidBeforeWipe != null && userBeforeWipe != null) {
                // 3b. Request a server-issued one-time nonce for AccountLockWorker to
                //     use as a retry fallback. Done here — before sign-out — so the
                //     request is authenticated with the live Firebase session rather
                //     than any credential stored persistently in WorkManager input
                //     data. If this fails (offline), the warm token recorded into the
                //     durable intent above (if any) is what the drain path and
                //     AccountLockWorker fall back to.
                try {
                    final Object tokenSync   = new Object();
                    final String[] tokenHolder = {null};
                    userBeforeWipe.getIdToken(false)
                            .addOnSuccessListener(r -> {
                                synchronized (tokenSync) { tokenHolder[0] = r.getToken() != null ? r.getToken() : ""; tokenSync.notifyAll(); }
                            })
                            .addOnFailureListener(e -> {
                                synchronized (tokenSync) { tokenHolder[0] = ""; tokenSync.notifyAll(); }
                            });
                    synchronized (tokenSync) {
                        if (tokenHolder[0] == null) tokenSync.wait(5_000);
                    }
                    String idToken = tokenHolder[0] != null ? tokenHolder[0] : "";

                    if (!idToken.isEmpty()) {
                        synchronized (LOCK_NONCE_ISSUE_MUTEX) {
                            String nonce = requestLockNonce(idToken);
                            if (nonce != null && !nonce.isEmpty()) {
                                // The trigger's nonce is the newest server-issued
                                // credential for this uid; persist it before enqueuing.
                                PendingLockStore.putWarmToken(appCtx, nonce);
                                PendingLockStore.recordLockIntent(appCtx, uidBeforeWipe, nonce);
                                AccountLockWorker.enqueue(appCtx, nonce);
                                android.util.Log.d("DuressManager", "AccountLockWorker enqueued with nonce.");
                            }
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("DuressManager",
                            "Could not obtain lock nonce — WorkManager retry skipped: " + e.getMessage());
                }
            }

            // F35 / F16 fix: Write the sign-out event synchronously on THIS thread,
            // immediately before clearInstance(). Using logSync() (not the async log())
            // guarantees the insert lands in the database before we delete it.
            // Event type is SIGN_OUT — indistinguishable from a voluntary sign-out,
            // preserving plausible deniability in the Session Log.

            // 4. Destructive local wipe ─────────────────────────────────────────

            // 4a. Canonical local erasure — the SAME routine used by "Wipe & Exit" and
            //     "Unpair Device", running in DURESS mode. See WipeHelper for the full
            //     ordered step list (gallery media, decrypted-media disk cache, key
            //     material, SQLCipher DB, scheduled work, prefs, Firebase sign-out,
            //     temp/export cache).
            //
            //     Do NOT re-inline erasure steps here. This path previously maintained
            //     its own copy of the sequence and silently fell behind: it was missing
            //     both B2StorageHelper.clearDiskCache() and the cache-directory delete,
            //     so decrypted photos and videos survived a duress wipe in readable form.
            //     Any new erasure step belongs in WipeHelper.eraseLocalData().
            //
            //     DURESS mode additionally destroys the contact backup, so the
            //     "Restore Contacts" path cannot rebuild the social graph afterwards.
            try {
                com.duoshield.app.util.WipeHelper.eraseLocalData(
                        context, com.duoshield.app.util.WipeHelper.WipeMode.DURESS);
            } catch (Exception e) {
                android.util.Log.e("DuressManager", "eraseLocalData failed during duress wipe", e);
            }

            // S06-M5: clear the resume marker only once the erasure above has actually
            // completed — clearResetMarkers() also clears PendingLockStore's copy. If
            // eraseLocalData threw, the marker deliberately stays set so the next
            // launch's resumeInterruptedResetIfNeeded() retries the wipe to completion
            // instead of the app silently starting up half-wiped.
            clearResetMarkers(context);
          } finally {
            DURESS_TRIGGER_IN_PROGRESS.set(false);
          }
        }, "duress-logout").start();
        } catch (RuntimeException startError) {
            DURESS_TRIGGER_IN_PROGRESS.set(false);
            throw startError;
        }
        } catch (RuntimeException setupError) {
            DURESS_TRIGGER_IN_PROGRESS.set(false);
            throw setupError;
        }
    }

    // ── Server-side eligibility gate ──────────────────────────────────────────
    //
    // Whether the secondary-code feature is even offered to this account is
    // controlled server-side, NOT by anything the client can set. A generic
    // account created by anyone probing the app is never enrolled and never
    // sees the option. Enrollment happens out-of-band (the operator adds
    // duressEligibility/{accountId} in the Firebase console) — the client only
    // ever reads a yes/no flag for its own account, and the PIN itself never
    // leaves the device either way.
    //
    // The result is cached in SecurePrefs so the app keeps working offline and
    // doesn't need a network round trip on every launch; a later successful
    // check can still flip the cached value (including revoking it).

    private static String eligibilityCacheKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_ELIGIBLE_PREFIX + user.getUid() : null;
    }

    /** Cached (offline-safe) read of whether this account may configure a duress PIN. */
    public static boolean isDuressEligibleCached(Context context) {
        String key = eligibilityCacheKey();
        if (key == null) return false;
        return SecurePrefs.get(context).getBoolean(key, false);
    }

    /**
     * Refreshes the cached eligibility flag from Firestore. Safe to call on
     * every sign-in / app foreground — reads a single small document via the
     * account's own UID, which the Firestore rules restrict to that account.
     * No-ops silently on failure (offline, etc.) — the previously cached value
     * is left untouched either way.
     */
    public static void refreshEligibility(Context context) {
        refreshEligibility(context, null);
    }

    /**
     * Same as {@link #refreshEligibility(Context)}, but invokes {@code onComplete}
     * on the main thread once the cached flag has been updated (or the attempt
     * has failed and the previous value has been kept).
     *
     * <h3>Why a callback is needed</h3>
     * Enrollment is granted out-of-band by the operator, which means it almost
     * always lands while the account is <em>already signed in</em>. Refreshing
     * only at sign-in left a freshly-enrolled account unable to see the option
     * until it signed out and back in — the enrollment appeared to do nothing.
     * Screens that surface the option call this on resume and re-render from the
     * callback, so an enrollment granted seconds ago is visible on the next
     * visit to the screen.
     *
     * <p>Revocation travels the same path: a later refresh that reads
     * {@code eligible == false} flips the cached value back off.
     *
     * @param onComplete run on the main thread after the cache write; may be null
     */
    public static void refreshEligibility(Context context, Runnable onComplete) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String key = user != null ? eligibilityCacheKey() : null;
        if (user == null || key == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        String uid = user.getUid();
        Context appCtx = context.getApplicationContext();
        FirebaseFirestore.getInstance()
                .collection("duressEligibility")
                .document(uid)
                .get()
                .addOnSuccessListener(snap -> {
                    boolean eligible = snap != null && snap.exists()
                            && Boolean.TRUE.equals(snap.getBoolean("eligible"));
                    // .apply() is deliberate: it updates the in-memory map
                    // synchronously (so the callback below re-reads the new value
                    // via isDuressEligibleCached) while the disk write happens off
                    // the main thread. .commit() here would block the UI thread on
                    // an EncryptedSharedPreferences write for a single boolean.
                    SecurePrefs.get(appCtx).edit().putBoolean(key, eligible).apply();
                    if (onComplete != null) onComplete.run();
                })
                .addOnFailureListener(e -> {
                    /* keep last-known cached value */
                    if (onComplete != null) onComplete.run();
                });
    }

    // ── Lock-credential maintenance (S06-H3) ──────────────────────────────────

    /**
     * Pre-fetches a short-lived lock nonce during ordinary online foreground use
     * and stores it as a warm fallback in {@link PendingLockStore}.
     *
     * <p>The nonce is valid for one hour and is refreshed after 30 minutes. It can
     * help a duress trigger complete when connectivity is briefly unavailable, but
     * it is not a durable credential and cannot guarantee a remote lock if the
     * device has been offline long enough for the nonce to expire.
     *
     * <p>If a durable lock intent remains after a failed or expired nonce, a later
     * valid foreground session for the <em>same UID</em> obtains a fresh nonce,
     * rotates only that intent's token and enqueues a bounded WorkManager attempt.
     * If the account never authenticates again or connectivity never returns, the
     * server-side lock cannot be guaranteed; the local intent remains as an honest
     * indication that the account may still be unlocked.
     *
     * <p>Nonce issuance is serialized against the duress trigger because the server
     * retires older outstanding nonces for a UID. This prevents an ordinary
     * background refresh from invalidating the trigger's nonce or overwriting its
     * pending intent. This method is called from the valid, foreground session path
     * in {@code BaseActivity.onStart()} and performs network I/O on a background thread.
     */
    public static void maintainLockCredential(Context context) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        final Context appCtx = context.getApplicationContext();
        if (DURESS_TRIGGER_IN_PROGRESS.get()) return;
        if (PendingLockStore.getWarmTokenAgeMs(appCtx) < LOCK_NONCE_REFRESH_INTERVAL_MS) return;
        // Multiple Activity.onStart callbacks must not issue parallel nonces.
        // /requestLockNonce intentionally prunes older outstanding nonces per uid;
        // serializing this refresh prevents a late response from replacing a newer token.
        if (!LOCK_CREDENTIAL_REFRESH_IN_FLIGHT.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                final Object tokenSync = new Object();
                final String[] tokenHolder = {null};
                user.getIdToken(false)
                        .addOnSuccessListener(result -> {
                            synchronized (tokenSync) {
                                tokenHolder[0] = result.getToken() != null ? result.getToken() : "";
                                tokenSync.notifyAll();
                            }
                        })
                        .addOnFailureListener(error -> {
                            synchronized (tokenSync) {
                                tokenHolder[0] = "";
                                tokenSync.notifyAll();
                            }
                        });
                synchronized (tokenSync) {
                    if (tokenHolder[0] == null) tokenSync.wait(10_000);
                }
                String idToken = tokenHolder[0] != null ? tokenHolder[0] : "";
                if (idToken.isEmpty()) return;

                synchronized (LOCK_NONCE_ISSUE_MUTEX) {
                    // A duress trigger owns the nonce slot while panic sync and
                    // synchronous account locking run. Do not prune its credential
                    // with a background refresh that started on an earlier lifecycle.
                    if (DURESS_TRIGGER_IN_PROGRESS.get()) return;
                    if (PendingLockStore.hasLockIntent(appCtx)) {
                        LockIntentDrainResult prior =
                                drainPendingLockIntentWithResult(appCtx);
                        if (prior == LockIntentDrainResult.TRANSIENT_FAILURE) {
                            // Do not rotate/prune a nonce whose request may already
                            // have committed while its HTTP response was lost.
                            android.util.Log.w("DuressManager",
                                    "Pending lock retry was transient; preserving its nonce for the next drain.");
                            return;
                        }
                    }
                    if (DURESS_TRIGGER_IN_PROGRESS.get()) return;
                    String nonce = requestLockNonce(idToken);
                    if (nonce == null || nonce.isEmpty()) return;

                    PendingLockStore.putWarmToken(appCtx, nonce);
                    android.util.Log.d("DuressManager", "Lock credential refreshed.");

                    // If a previous offline/expired attempt left a durable intent for
                    // this same account, rotate that intent to the fresh nonce and
                    // enqueue a bounded retry. Never retarget another account's intent.
                    if (!DURESS_TRIGGER_IN_PROGRESS.get()
                            && PendingLockStore.replaceIntentTokenIfSubjectMatches(appCtx, user.getUid(), nonce)) {
                        android.util.Log.i("DuressManager",
                                "Refreshed credential for pending lock intent; scheduling bounded retry.");
                        AccountLockWorker.enqueue(appCtx, nonce);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("DuressManager",
                        "maintainLockCredential failed (non-fatal): " + e.getMessage());
            } finally {
                LOCK_CREDENTIAL_REFRESH_IN_FLIGHT.set(false);
            }
        }, "duress-credential-maintain").start();
    }
    /** Result of one attempt to drain a durable server-side account-lock intent. */
    public enum LockIntentDrainResult {
        NO_PENDING_INTENT,
        NO_CREDENTIAL,
        CONFIRMED,
        PERMANENT_FAILURE,
        TRANSIENT_FAILURE
    }

    /**
     * Drains a durable lock intent left by an interrupted or offline duress trigger
     * (S06-H3 / S06-M5). Call once at application startup, on a background thread.
     *
     * <p>Distinct from {@link #resumeInterruptedResetIfNeeded}: that method finishes
     * an interrupted <em>local wipe</em>; this one finishes an interrupted or
     * previously-impossible <em>server-side lock write</em>.
     *
     * @return true if an intent was present, even if the network attempt failed and
     *         the intent remains pending for the next launch or a WorkManager retry.
     */
    public static boolean drainPendingLockIntent(Context context) {
        return drainPendingLockIntentWithResult(context)
                != LockIntentDrainResult.NO_PENDING_INTENT;
    }

    /**
     * Attempts the durable nonce before any replacement nonce is requested.
     * Foreground credential maintenance uses the detailed result to avoid pruning
     * a nonce whose prior lock transaction may have committed while its response
     * was lost. Only a permanent rejection permits rotating to a fresh nonce;
     * transient outcomes preserve the old nonce for another idempotent attempt.
     */
    private static LockIntentDrainResult drainPendingLockIntentWithResult(Context context) {
        final Context appCtx = context.getApplicationContext();
        if (!PendingLockStore.hasLockIntent(appCtx)) {
            return LockIntentDrainResult.NO_PENDING_INTENT;
        }

        String token = PendingLockStore.getIntentToken(appCtx);
        if (token == null || token.isEmpty()) {
            android.util.Log.w("DuressManager",
                    "Pending lock intent has no usable credential — account believed UNLOCKED.");
            return LockIntentDrainResult.NO_CREDENTIAL;
        }

        try {
            int code = postDuressLock(token);
            if (code == 200 || code == 204) {
                PendingLockStore.clearLockIntentIfTokenMatches(appCtx, token);
                PendingLockStore.clearWarmTokenIfMatches(appCtx, token);
                android.util.Log.i("DuressManager", "Drained pending lock intent — confirmed.");
                return LockIntentDrainResult.CONFIRMED;
            }
            if (code >= 400 && code < 500 && code != 408 && code != 425 && code != 429) {
                // A consumed nonce returns 200 as an idempotent acknowledgement of
                // the original atomic lock commit, even if an administrator has
                // since unfrozen the account. A permanent 4xx means no commit exists.
                PendingLockStore.clearWarmTokenIfMatches(appCtx, token);
                android.util.Log.e("DuressManager",
                        "Pending lock intent rejected (HTTP " + code
                                + ") — account lock NOT confirmed; intent retained.");
                return LockIntentDrainResult.PERMANENT_FAILURE;
            }
            android.util.Log.w("DuressManager",
                    "Pending lock intent drain failed (HTTP " + code + ") — preserving nonce.");
            return LockIntentDrainResult.TRANSIENT_FAILURE;
        } catch (Exception e) {
            android.util.Log.w("DuressManager",
                    "Pending lock intent drain failed (offline?) — preserving nonce: " + e.getMessage());
            return LockIntentDrainResult.TRANSIENT_FAILURE;
        }
    }

    /** Shared HTTP POST to {@code /duress-lock}. Returns the HTTP status code. */
    private static int postDuressLock(String nonce) throws Exception {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) throw new IllegalStateException("PUSH_SERVER_URL not configured");
        String endpoint = serverUrl.endsWith("/") ? serverUrl + "duress-lock" : serverUrl + "/duress-lock";
        byte[] bodyBytes = new JSONObject().put("nonce", nonce).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    // ── Lock-nonce helper ─────────────────────────────────────────────────────

    /**
     * Requests a single-use account-lock nonce from the push server, authenticated
     * with the supplied Firebase ID token. The nonce is used by {@link AccountLockWorker}
     * as a retry credential — it has no auth power of its own, is uid-bound server-side,
     * expires in one hour and is marked consumed atomically with the lock. A pending intent can receive a fresh uid-bound nonce after the same account next authenticates.
     *
     * <p>Must NOT be called on the main thread (blocking HTTP call).
     *
     * @param idToken valid Firebase ID token captured before sign-out
     * @return nonce string, or {@code null} if the request failed
     */
    private static String requestLockNonce(String idToken) {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) return null;
        String endpoint = serverUrl.endsWith("/")
                ? serverUrl + "requestLockNonce"
                : serverUrl + "/requestLockNonce";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Authorization", "Bearer " + idToken);
                conn.setRequestProperty("Content-Length", "0");
                conn.setDoOutput(false);

                int code = conn.getResponseCode();
                if (code != 200) {
                    android.util.Log.w("DuressManager",
                            "requestLockNonce: server returned HTTP " + code);
                    return null;
                }
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[2048];
                int n;
                while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
                String body = buf.toString("UTF-8");
                JSONObject json = new JSONObject(body);
                String nonce = json.optString("nonce", null);
                if (nonce == null || nonce.isEmpty()) {
                    android.util.Log.w("DuressManager", "requestLockNonce: empty nonce in response");
                    return null;
                }
                return nonce;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            android.util.Log.w("DuressManager", "requestLockNonce failed: " + e.getMessage());
            return null;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LEN);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    private static void deleteDir(java.io.File dir) {
        if (dir == null) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }
}
