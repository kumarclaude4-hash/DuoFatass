package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stores the app PIN hash in EncryptedSharedPreferences (SecurePrefs), scoped
 * to the currently signed-in Firebase UID.
 *
 * <h3>Why UID-scoped?</h3>
 * The duress logout intentionally preserves the PIN hash so a restore attempt
 * is still gated. But if a NEW user signs in on the same device after a duress
 * wipe they must not see a lock screen for a PIN they never set — that would be
 * suspicious. Keying by UID means each account has its own hash entry and a new
 * account starts with no hash → no lock screen.
 *
 * <h3>Migration</h3>
 * Existing installs that stored the hash under the legacy global key
 * {@code "app_pin_hash"} are migrated transparently on first access: the value
 * is moved to the UID-scoped key and the legacy entry is removed.
 *
 * <h3>Device-scoped gate PIN storage</h3>
 * The device-level gate PIN (see below) lives in its own SecurePrefs file
 * ({@link SecurePrefs#getDeviceGate}) — a physically separate container from
 * the account-scoped file this class otherwise uses. That split is load-
 * bearing: {@code DuressManager.performLogout()}, {@code WipeHelper.wipeAll()},
 * and the Danger Zone "unpair" flow all blank-clear() the account-scoped file
 * when wiping an account from this device, and the device gate must survive
 * every one of those wipes — it protects the device itself, not any one
 * account. Installs that set a device PIN before the split are migrated
 * transparently on first read, mirroring the legacy-key pattern above.
 */
public class PinManager {

    private static final String KEY_PIN_PREFIX    = "app_pin_hash_";
    private static final String KEY_PIN_LEGACY    = "app_pin_hash";
    // KEY_LEN_PREFIX is now read-only-for-migration: PinManager no longer WRITES a
    // plaintext PIN-length integer beside the salt:hash string (S08-L3 — an attacker
    // with app-process or decrypted-prefs read access previously learned the exact
    // PIN length, cutting the brute-force keyspace to a single length instead of the
    // full 4–6 digit range, for free and without doing any of the PBKDF2 work the
    // hash itself demands). setPin()/setDevicePin() now scrub this key instead of
    // populating it; getPinLength()/getDevicePinLength() return the fixed
    // MAX_PIN_LEN upper bound below for UI sizing only, never a per-account secret.
    private static final String KEY_LEN_PREFIX    = "app_pin_length_";
    private static final int    ITERATIONS        = 310_000;
    private static final int    KEY_LEN           = 256;

    /** Minimum PIN length accepted by setup screens (see SetupPinActivity, DevicePinGateActivity). */
    public static final int MIN_PIN_LEN = 4;
    /**
     * Maximum PIN length accepted by setup screens, and — since S08-L3 — the fixed
     * value {@link #getPinLength}/{@link #getDevicePinLength} return for sizing the
     * numpad's dot indicator and input buffer. It is an upper bound shared by every
     * account, not a disclosure of any one account's actual PIN length.
     */
    public static final int MAX_PIN_LEN = 6;
    private static final int DEFAULT_PIN_LEN = MAX_PIN_LEN;

    /**
     * S06-I3 — alphanumeric passphrase mode.
     *
     * <p>A 4–6 digit numeric PIN spans at most 10^6 ≈ 1M candidates. PBKDF2 at
     * {@link #ITERATIONS} iterations makes each guess expensive on-device, but an
     * attacker who has extracted the salt and hash can test that keyspace offline
     * on GPUs, where the whole 6-digit space is exhaustible. The PBKDF2 work factor
     * raises the constant; only more entropy raises the exponent. This lets a user
     * who wants that headroom choose a passphrase instead, without forcing a
     * behaviour change on the numeric-PIN users the numpad UI is built around.
     *
     * <p>{@link #MAX_PIN_LEN} deliberately stays at 6: it sizes the numpad's dot
     * indicator and input buffer ({@code getPinLength}), so widening it would
     * change the numeric lock screen for everyone. Passphrases are entered on a
     * text field instead and are bounded by {@link #MAX_PASSPHRASE_LEN}.
     *
     * <p>Both credential kinds are verified by the same salted-PBKDF2 path, so the
     * stored record does not reveal which kind it is — see {@link #isPassphraseMode}
     * for why the mode flag is stored separately from the hash and is only ever
     * used to pick the input UI.
     */
    public static final int MIN_PASSPHRASE_LEN = 8;
    /**
     * Upper bound on passphrase length. Present only to keep a pathological input
     * from turning 310k PBKDF2 iterations into a denial of service on the unlock
     * screen; it is far above any realistic passphrase.
     */
    public static final int MAX_PASSPHRASE_LEN = 128;

    /**
     * Marks the account/device credential as an alphanumeric passphrase rather than
     * a numeric PIN.
     *
     * <p>Stored separately from the hash record and read only to decide which input
     * UI to show. It is not a secret in the S08-L3 sense — it narrows the keyspace
     * to "not a 4–6 digit number", which widens rather than narrows the attacker's
     * work — but it is still never written for numeric PINs, so the common case
     * leaks nothing new.
     */
    private static final String KEY_PASSPHRASE_MODE_PREFIX = "app_cred_is_passphrase_";

    /**
     * Device-scoped PIN — independent of any Firebase UID. Gates
     * {@link com.duoshield.app.SignInActivity} (Welcome / Create / Restore)
     * on fresh installs, before any account exists. See
     * {@link com.duoshield.app.ui.DevicePinGateActivity}.
     */
    private static final String KEY_DEVICE_PIN_HASH = "device_gate_pin_hash";
    private static final String KEY_DEVICE_PIN_LEN  = "device_gate_pin_length";

    /**
     * True once the device-level PIN gate has been satisfied for the current
     * process — by successful verification, fresh setup, or because this
     * device predates the feature and is exempt ({@link #looksLikePreExistingDevice}).
     * Resets naturally on process death; {@code SignInActivity} re-checks on
     * every cold start so the gate can never be bypassed by relaunching.
     */
    public static volatile boolean deviceGateSatisfiedThisProcess = false;

    /**
     * Returns the UID-scoped SecurePrefs key for the currently signed-in user,
     * or {@code null} if no user is signed in.
     */
    private static String pinKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_PIN_PREFIX + user.getUid() : null;
    }

    /**
     * Stores {@code pin} as this account's primary PIN.
     *
     * @return true only if a hash was actually written for the current account.
     *         Returns false when no Firebase user is signed in (there is no
     *         UID to scope the key to) or the write/derivation threw.
     *
     *         <p>This used to be {@code void}, which made both failure paths
     *         completely silent: {@code SetupPinActivity} cleared its
     *         {@code pending_pin_setup_<uid>} marker and routed on to the
     *         conversation list regardless, so an account whose PIN never got
     *         stored looked fully set up. The next launch then found no hash,
     *         and the user was asked to set a PIN they had already set — the
     *         "it keeps prompting me to set up a PIN again" report. Callers must
     *         now branch on this value before treating setup as complete.
     */
    public static boolean setPin(Context ctx, String pin) {
        String key = pinKey();
        if (key == null) return false;
        try {
            SharedPreferences accountPreferences = SecurePrefs.get(ctx);
            SecurePrefs.SecurityTier accountTier = SecurePrefs.getTier();
            SharedPreferences devicePreferences = SecurePrefs.getDeviceGate(ctx);
            SecurePrefs.SecurityTier deviceTier = SecurePrefs.getDeviceGateTier();

            // Repair an interrupted two-store update before changing either credential.
            if (!PinHashPersistence.recoverPair(accountPreferences, accountTier,
                    devicePreferences, deviceTier, KEY_DEVICE_PIN_HASH)) {
                return false;
            }

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return false;
            boolean devicePinPresent = hasDevicePinSet(ctx);
            String accountHash = createStoredPinHash(pin);
            boolean saved;
            if (devicePinPresent) {
                // Keep independent salts across the account and device security domains.
                String deviceHash = createStoredPinHash(pin);
                saved = PinHashPersistence.persistPair(
                        accountPreferences, accountTier,
                        key, accountHash, KEY_PIN_LEGACY,
                        KEY_LEN_PREFIX + user.getUid(),
                        devicePreferences, deviceTier,
                        KEY_DEVICE_PIN_HASH, deviceHash, KEY_DEVICE_PIN_LEN);
            } else {
                saved = PinHashPersistence.persistSingle(
                        accountPreferences, accountTier, key, accountHash,
                        KEY_PIN_LEGACY, KEY_LEN_PREFIX + user.getUid());
            }
            if (!saved) return false;

            // Ensure every account's primary PIN has a slot-B decoy of identical shape.
            com.duoshield.app.security.DuressManager.ensureSecondarySlotInitialized(ctx);
            return true;
        } catch (Exception e) {
            android.util.Log.e("PinManager", "Failed to store PIN hash"
                    + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return false;
        }
    }

    // ── Wrong-PIN lockout (S06-L5) ───────────────────────────────────────────
    //
    // LockScreenActivity has no attempt limit by design — an exact match on the
    // secondary code is the only signal that triggers a duress wipe, and a
    // guess-count fallback was deliberately removed (see LockScreenActivity's
    // class javadoc) because it created false-positive lockouts. But that also
    // means anyone who picks up the device and mashes the keypad — a child, a
    // pocket, a curious colleague — can eventually type the secondary code by
    // accident, wiping the device and permanently locking the account pending
    // manual operator action. This adds a persisted, exponential-backoff delay
    // between attempts so accidental keypad mashing cannot realistically reach
    // the secondary code's keyspace, without ever refusing a *deliberate*
    // correct entry — the backoff only gates how soon the *next* attempt may be
    // submitted, never which PIN is accepted.
    private static final String KEY_FAIL_COUNT_PREFIX    = "pin_fail_count_";
    private static final String KEY_FAIL_UNTIL_PREFIX     = "pin_fail_until_";
    /**
     * S06-I3: high-water mark of the fail count, and the boot-relative deadline.
     * See {@link #recordFailedAttempt} for why both exist.
     */
    private static final String KEY_FAIL_HIGH_PREFIX      = "pin_fail_high_";
    private static final String KEY_FAIL_UNTIL_ELAPSED_PREFIX = "pin_fail_until_elapsed_";
    private static final String KEY_FAIL_BOOT_ID_PREFIX   = "pin_fail_boot_id_";
    private static final String KEY_PIN_CHANGE_PREFIX     = "pin_change_";
    private static final int    LOCKOUT_THRESHOLD         = 5;
    private static final long   LOCKOUT_BASE_MS           = 2_000L;
    private static final long   LOCKOUT_MAX_MS            = 5 * 60_000L;

    private static String failCountKey(Context ctx) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_FAIL_COUNT_PREFIX + user.getUid() : KEY_FAIL_COUNT_PREFIX + "device";
    }

    private static String failUntilKey(Context ctx) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_FAIL_UNTIL_PREFIX + user.getUid() : KEY_FAIL_UNTIL_PREFIX + "device";
    }

    private static String scopedKey(String prefix) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? prefix + user.getUid() : prefix + "device";
    }

    /**
     * Milliseconds the caller must still wait before the next attempt is allowed, or 0.
     *
     * <p>S06-I3: takes the <em>longer</em> of the wall-clock and boot-relative
     * remainders. The wall-clock deadline alone was defeated by changing the system
     * clock — Settings → Date &amp; time, no root needed — which made the stored
     * "until" timestamp appear to be in the past and cleared the lockout instantly,
     * so the exponential backoff protecting the duress-code keyspace could be
     * skipped at will. {@code elapsedRealtime()} cannot be set by the user and keeps
     * counting across sleep, so it holds the delay even when the clock moves. It
     * resets on reboot, which is why the wall-clock value is still consulted rather
     * than replaced: rebooting cannot shorten the wait either. Taking the max means
     * an attacker must defeat both to gain anything, and a legitimate user waiting
     * out a real lockout is unaffected by either.
     */
    public static long getLockoutRemainingMs(Context ctx) {
        try {
            SharedPreferences sp = SecurePrefs.get(ctx);
            long wallRemaining = Math.max(0L,
                    sp.getLong(failUntilKey(ctx), 0L) - System.currentTimeMillis());

            long elapsedRemaining = 0L;
            long untilElapsed = sp.getLong(scopedKey(KEY_FAIL_UNTIL_ELAPSED_PREFIX), 0L);
            if (untilElapsed > 0L
                    && sp.getLong(scopedKey(KEY_FAIL_BOOT_ID_PREFIX), -1L) == bootId()) {
                // Same boot session, so elapsedRealtime() is comparable to the stored value.
                elapsedRemaining = Math.max(0L, untilElapsed - SystemClock.elapsedRealtime());
            }
            return Math.max(wallRemaining, elapsedRemaining);
        } catch (Exception e) { return 0L; }
    }

    /**
     * Historical boot-session identifier used by the pre-existing wrong-PIN
     * backoff fields. Retained for compatibility with values already persisted
     * under that format; do not reuse for new security windows.
     */
    private static long bootId() {
        return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 10_000L;
    }

    /**
     * Stable OS boot counter for the Settings PIN-change throttle. Unlike
     * wall-clock-minus-uptime, BOOT_COUNT does not change when the user or
     * network adjusts the wall clock.
     *
     * <p>BOOT_COUNT is readable by ordinary apps on this app's supported API
     * range (26+). If the provider is temporarily unavailable, use a stable
     * sentinel: elapsedRealtime then remains conservative across reboot because
     * a reset clock cannot advance past an older persisted deadline early.
     */
    private static long pinChangeBootId(Context ctx) {
        try {
            int count = Settings.Global.getInt(
                    ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            if (count >= 0) return count;
        } catch (RuntimeException ignored) {
            // Fall through to the fail-closed sentinel.
        }
        return -1L;
    }

    /**
     * Atomically records a primary-PIN change attempt for the current account.
     *
     * <p>Settings calls this before checking whether the proposed primary PIN
     * collides with the hidden secondary code and before attempting the write.
     * Therefore accepted and rejected candidates consume the same allowance.
     * The helper persists its counter and wall/elapsed deadlines in SecurePrefs;
     * failure to persist is fail-closed.
     *
     * @return true when the attempt was recorded and may proceed, false while
     *         the account is cooling down or when durable storage failed.
     */
    public static boolean tryRecordPinChangeAttempt(Context ctx) {
        try {
            SharedPreferences preferences = SecurePrefs.get(ctx);
            return PinChangeThrottle.tryRecord(
                    preferences,
                    SecurePrefs.getTier(),
                    scopedKey(KEY_PIN_CHANGE_PREFIX),
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(),
                    pinChangeBootId(ctx));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Records a wrong PIN attempt and — once {@link #LOCKOUT_THRESHOLD} is reached —
     * sets an exponentially growing lockout window, capped at {@link #LOCKOUT_MAX_MS}.
     * Persisted, so a force-stop or reboot mid-lockout does not reset the counter.
     *
     * <p>S06-I3: the backoff is now computed from a monotonic high-water mark rather
     * than the live counter. The counter alone could be rolled back — restore an
     * older copy of the prefs file, or let any path that resets it run — returning
     * the attacker to a 2s delay after having already burned hundreds of attempts.
     * The high-water mark only ever increases, so the cost of guessing never
     * decreases. {@code commit()} rather than {@code apply()} because the process
     * may be force-stopped immediately after a wrong entry, and an asynchronous
     * write is exactly what an attacker force-stopping the app is trying to lose.
     */
    public static void recordFailedAttempt(Context ctx) {
        try {
            SharedPreferences sp = SecurePrefs.get(ctx);
            int count = sp.getInt(failCountKey(ctx), 0) + 1;
            int high  = Math.max(sp.getInt(scopedKey(KEY_FAIL_HIGH_PREFIX), 0), count);

            SharedPreferences.Editor ed = sp.edit()
                    .putInt(failCountKey(ctx), count)
                    .putInt(scopedKey(KEY_FAIL_HIGH_PREFIX), high);

            if (high >= LOCKOUT_THRESHOLD) {
                int overBy = high - LOCKOUT_THRESHOLD;
                long backoff = Math.min(LOCKOUT_MAX_MS, LOCKOUT_BASE_MS << Math.min(overBy, 10));
                ed.putLong(failUntilKey(ctx), System.currentTimeMillis() + backoff)
                  .putLong(scopedKey(KEY_FAIL_UNTIL_ELAPSED_PREFIX),
                          SystemClock.elapsedRealtime() + backoff)
                  .putLong(scopedKey(KEY_FAIL_BOOT_ID_PREFIX), bootId());
            }
            ed.commit();
        } catch (Exception ignored) {}
    }

    /**
     * Clears the attempt counter and any active lockout — call on a correct PIN.
     *
     * <p>S06-I3: deliberately does <em>not</em> clear the high-water mark. Clearing
     * it would hand an attacker a free reset of the backoff schedule via any single
     * correct entry (including a duress code, which is itself "correct"), which is
     * the rollback this item exists to prevent. The mark decays instead — see
     * {@link #decayFailHighWaterMark} — so an ordinary user who mistypes a few times
     * over months is not permanently penalised.
     */
    public static void clearFailedAttempts(Context ctx) {
        try {
            SecurePrefs.get(ctx).edit()
                    .remove(failCountKey(ctx))
                    .remove(failUntilKey(ctx))
                    .remove(scopedKey(KEY_FAIL_UNTIL_ELAPSED_PREFIX))
                    .remove(scopedKey(KEY_FAIL_BOOT_ID_PREFIX))
                    .commit();
            decayFailHighWaterMark(ctx);
        } catch (Exception ignored) {}
    }

    /**
     * Decays the high-water mark by one step on a successful unlock, never below
     * {@link #LOCKOUT_THRESHOLD}{@code  - 1} once it has been exceeded.
     *
     * <p>This is the release valve that keeps the monotonic mark from turning a
     * handful of honest mistypes into a permanent multi-minute delay: each correct
     * entry walks the penalty back down one exponential step. It cannot be used to
     * escape a lockout, because reaching this code path already required entering
     * the correct PIN, and one step down from a 5-minute cap is still minutes.
     */
    private static void decayFailHighWaterMark(Context ctx) {
        try {
            SharedPreferences sp = SecurePrefs.get(ctx);
            int high = sp.getInt(scopedKey(KEY_FAIL_HIGH_PREFIX), 0);
            if (high <= 0) return;

            // Below the threshold the mark imposes no delay at all, so there is
            // nothing to walk back — drop it entirely and keep the store tidy.
            // (Guarding this explicitly also avoids Math.max() raising a small
            // mark up to LOCKOUT_THRESHOLD - 1, which would invent a penalty the
            // user never earned.)
            if (high < LOCKOUT_THRESHOLD) {
                sp.edit().remove(scopedKey(KEY_FAIL_HIGH_PREFIX)).commit();
                return;
            }
            // At or above the threshold: step down by one, never below the last
            // rung that still carries no delay.
            sp.edit().putInt(scopedKey(KEY_FAIL_HIGH_PREFIX), high - 1).commit();
        } catch (Exception ignored) {}
    }

    /**
     * Returns {@link #MAX_PIN_LEN} — the fixed upper bound {@code LockScreenActivity}
     * uses to size the numpad's dot indicator and input buffer.
     *
     * <p>Before S08-L3, this returned the caller's actual, per-account PIN length,
     * read back from a plaintext integer stored beside the PIN's salt:hash. That let
     * anyone with app-process or decrypted-SecurePrefs read access learn the exact
     * PIN length for free, cutting a brute-force attempt down to a single length
     * out of the accepted {@link #MIN_PIN_LEN}–{@link #MAX_PIN_LEN} range instead of
     * needing to try all of them. Callers that used the old exact length purely to
     * know when to auto-submit no longer need to: the numpad now submits once
     * {@link #MAX_PIN_LEN} digits are entered, or after a short pause once at least
     * {@link #MIN_PIN_LEN} digits are entered (see {@code LockScreenActivity}/
     * {@code DevicePinGateActivity}'s debounced auto-submit) — neither needs to know
     * the account's real length in advance.
     */
    public static int getPinLength(Context ctx) {
        return MAX_PIN_LEN;
    }

    public static boolean hasPinSet(Context ctx) {
        String key = pinKey();
        if (key == null) return false;
        SharedPreferences sp = SecurePrefs.get(ctx);
        if (!recoverPinSync(ctx)) {
            // Preserve the lock-screen route if a credential exists but its
            // synchronization journal cannot be repaired. Verification fails closed.
            return sp.getString(key, null) != null
                    || sp.getString(KEY_PIN_LEGACY, null) != null;
        }
        if (sp.getString(key, null) != null) return true;

        // Migrate the legacy account hash synchronously; retain it if persistence fails.
        String legacy = sp.getString(KEY_PIN_LEGACY, null);
        if (legacy != null) {
            PinHashPersistence.persistSingle(
                    sp, SecurePrefs.getTier(), key, legacy,
                    KEY_PIN_LEGACY, KEY_LEN_PREFIX + FirebaseAuth.getInstance()
                            .getCurrentUser().getUid());
            return true; // verifyPin() keeps a read-only legacy fallback.
        }
        return false;
    }

    public static boolean verifyPin(Context ctx, String entered) {
        String key = pinKey();
        if (key == null || !recoverPinSync(ctx)) return false;
        SharedPreferences sp = SecurePrefs.get(ctx);
        if (!SecurePrefs.getTier().isDurable()) return false;
        String stored = sp.getString(key, null);
        if (stored == null) {
            // Fallback to legacy key (handles the window between hasPinSet and
            // verifyPin being called before migration has run)
            stored = sp.getString(KEY_PIN_LEGACY, null);
        }
        if (stored == null) return false;
        int sep = stored.indexOf(':');
        if (sep < 0) return false;
        try {
            byte[] salt     = hexToBytes(stored.substring(0, sep));
            byte[] expected = hexToBytes(stored.substring(sep + 1));
            byte[] actual   = pbkdf2(entered, salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) { return false; }
    }

    public static void clearPin(Context ctx) {
        String key = pinKey();
        if (key == null) return;
        SecurePrefs.get(ctx).edit()
                .remove(key)
                .remove(KEY_PIN_LEGACY)
                .apply();
    }

    // ── Device-scoped gate PIN ────────────────────────────────────────────
    //
    // Stored in SecurePrefs.getDeviceGate() — a separate physical file from
    // the account-scoped SecurePrefs.get() used everywhere above. See the
    // class javadoc "Device-scoped gate PIN storage" section for why.

    /**
     * True if a device-level gate PIN is set. Also performs a one-time,
     * transparent migration for installs that set their device PIN before
     * it was split into its own file: such installs stored it under the
     * same keys in the account-scoped file, which is exactly the file
     * {@code DuressManager.performLogout()} / {@code WipeHelper.wipeAll()}
     * blank-clear() — so any device PIN still sitting there is moved into
     * the isolated file (and removed from the old one) the first time it is
     * read after upgrading.
     */
    public static boolean hasDevicePinSet(Context ctx) {
        SharedPreferences devicePreferences = SecurePrefs.getDeviceGate(ctx);
        if (!recoverPinSync(ctx)) {
            // Do not expose setup mode when an existing device credential has
            // an unresolved journal.
            return devicePreferences.getString(KEY_DEVICE_PIN_HASH, null) != null
                    || SecurePrefs.get(ctx).getString(KEY_DEVICE_PIN_HASH, null) != null;
        }
        if (devicePreferences.getString(KEY_DEVICE_PIN_HASH, null) != null) return true;

        SharedPreferences accountPreferences = SecurePrefs.get(ctx);
        String legacy = accountPreferences.getString(KEY_DEVICE_PIN_HASH, null);
        if (legacy == null) return false;

        // Copy into the isolated device container before deleting the historical
        // account-store copy. If the copy fails, keep VERIFY mode and the fallback.
        if (!PinHashPersistence.persistSingle(
                devicePreferences, SecurePrefs.getDeviceGateTier(),
                KEY_DEVICE_PIN_HASH, legacy, KEY_DEVICE_PIN_LEN)) {
            return true;
        }
        try {
            accountPreferences.edit()
                    .remove(KEY_DEVICE_PIN_HASH)
                    .remove(KEY_DEVICE_PIN_LEN)
                    .commit();
        } catch (RuntimeException ignored) {
            // A duplicate legacy copy is safe; the verified device copy is authoritative.
        }
        android.util.Log.i("PinManager", "Migrated device-gate PIN hash to its isolated storage file.");
        return true;
    }

    public static boolean setDevicePin(Context ctx, String pin) {
        try {
            if (!recoverPinSync(ctx)) return false;
            String stored = createStoredPinHash(pin);
            return PinHashPersistence.persistSingle(
                    SecurePrefs.getDeviceGate(ctx), SecurePrefs.getDeviceGateTier(),
                    KEY_DEVICE_PIN_HASH, stored, KEY_DEVICE_PIN_LEN);
        } catch (Exception e) {
            android.util.Log.e("PinManager", "Failed to store device-level PIN hash"
                    + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return false;
        }
    }

    /**
     * Returns {@link #MAX_PIN_LEN}. See {@link #getPinLength} — the same S08-L3
     * fix applies to the device-scoped gate PIN.
     */
    public static int getDevicePinLength(Context ctx) {
        return MAX_PIN_LEN;
    }

    public static boolean verifyDevicePin(Context ctx, String entered) {
        if (!recoverPinSync(ctx)) return false;
        SharedPreferences sp = SecurePrefs.getDeviceGate(ctx);
        String stored = sp.getString(KEY_DEVICE_PIN_HASH, null);
        if (stored != null && !SecurePrefs.getDeviceGateTier().isDurable()) return false;
        if (stored == null) {
            // Fallback to the pre-split shared-file location (handles the window
            // between hasDevicePinSet() and verifyDevicePin() being called before
            // migration has run) — mirrors the legacy-key fallback in verifyPin().
            SharedPreferences legacyPreferences = SecurePrefs.get(ctx);
            if (!SecurePrefs.getTier().isDurable()) return false;
            stored = legacyPreferences.getString(KEY_DEVICE_PIN_HASH, null);
        }
        if (stored == null) return false;
        int sep = stored.indexOf(':');
        if (sep < 0) return false;
        try {
            byte[] salt     = hexToBytes(stored.substring(0, sep));
            byte[] expected = hexToBytes(stored.substring(sep + 1));
            byte[] actual   = pbkdf2(entered, salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) { return false; }
    }

    /**
     * Copies the device-level PIN hash into the just-created/just-restored
     * account's UID-scoped slot, so the existing AppLockManager /
     * LockScreenActivity background-lock mechanism (which reads
     * {@code app_pin_hash_<uid>}) works immediately without asking the user
     * to set a PIN a second time. Copying the stored {@code salt:hash}
     * string is sufficient — the plaintext PIN is never needed again.
     *
     * This is intentionally a copy, not a move: the device-gate PIN must
     * remain set in its own file afterwards so DevicePinGateActivity keeps
     * opening in verify mode for this device (future accounts on the same
     * device, wipe-and-restore, etc.), and so {@link #setPin} has a
     * device-level copy to keep in sync when the user changes their PIN.
     */
    public static boolean promoteDevicePinToCurrentUser(Context ctx) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return false;
        if (!recoverPinSync(ctx) || !hasDevicePinSet(ctx)) return false;
        SharedPreferences sp = SecurePrefs.getDeviceGate(ctx);
        if (!SecurePrefs.getDeviceGateTier().isDurable()) return false;
        String stored = sp.getString(KEY_DEVICE_PIN_HASH, null);
        if (stored == null) return false;
        // Re-commit and read back the current device credential before trusting
        // it as the source of an account promotion. This catches a prior
        // failed migration whose in-memory map looked populated.
        if (!PinHashPersistence.persistSingle(
                sp, SecurePrefs.getDeviceGateTier(),
                KEY_DEVICE_PIN_HASH, stored, KEY_DEVICE_PIN_LEN)) {
            return false;
        }
        // Copy only the salt:hash string; never propagate historical plaintext length data.
        if (!PinHashPersistence.persistSingle(
                SecurePrefs.get(ctx), SecurePrefs.getTier(),
                KEY_PIN_PREFIX + user.getUid(), stored,
                KEY_LEN_PREFIX + user.getUid())) {
            return false;
        }
        // An account that arrives via this path (creation / wipe-and-restore) never
        // goes through setPin(), so it used to end up with a primary PIN but no
        // slot B at all — reintroducing, for exactly those accounts, the
        // one-derivation-vs-two timing tell that ensureSecondarySlotInitialized()
        // exists to close, and leaving hasDuressPin() reading a key that was never
        // written. Initialise the decoy here too so every account with a primary
        // PIN has a slot B of identical shape, however it got that PIN.
        com.duoshield.app.security.DuressManager.ensureSecondarySlotInitialized(ctx);
        return true;
    }

    /**
     * True if this device shows signs of an account that existed before the
     * upfront device-PIN gate was introduced — a persisted {@code my_uid}, or
     * any pre-existing account-scoped PIN hash. Used to exempt pre-existing
     * installs from being retroactively forced through the new gate (see
     * {@link com.duoshield.app.ui.DevicePinGateActivity}); this fix only
     * applies going forward to genuinely fresh installs.
     */
    public static boolean looksLikePreExistingDevice(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        if (prefs.getString("my_uid", null) != null) return true;
        SharedPreferences sp = SecurePrefs.get(ctx);
        if (sp.getString(KEY_PIN_LEGACY, null) != null) return true;
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(KEY_PIN_PREFIX)) return true;
        }
        return false;
    }

    /** Repairs an interrupted account/device PIN synchronization before use. */
    private static boolean recoverPinSync(Context ctx) {
        try {
            return PinHashPersistence.recoverPair(
                    SecurePrefs.get(ctx), SecurePrefs.getTier(),
                    SecurePrefs.getDeviceGate(ctx), SecurePrefs.getDeviceGateTier(),
                    KEY_DEVICE_PIN_HASH);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Derives an independently salted hash and clears mutable intermediates. */
    private static String createStoredPinHash(String pin) throws Exception {
        byte[] salt = new byte[16];
        byte[] hash = null;
        try {
            new SecureRandom().nextBytes(salt);
            hash = pbkdf2(pin, salt);
            return bytesToHex(salt) + ":" + bytesToHex(hash);
        } finally {
            Arrays.fill(salt, (byte) 0);
            if (hash != null) Arrays.fill(hash, (byte) 0);
        }
    }

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        char[] password = pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LEN);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(password, (char) 0);
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
