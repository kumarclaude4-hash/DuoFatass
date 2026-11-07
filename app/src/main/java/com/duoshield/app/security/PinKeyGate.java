package com.duoshield.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import com.duoshield.app.util.SecurePrefs;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic PIN gate (S08-M3) — turns PIN verification from a boolean into
 * key recovery.
 *
 * <h3>The problem this fixes</h3>
 * {@code PinManager.verifyPin()} returns a {@code boolean}. Anything that
 * decides access by branching on a boolean can be defeated by making the
 * boolean lie: an attacker with a hooking framework (Frida, Xposed) or a
 * patched APK forces {@code verifyPin()} to return {@code true} and walks
 * straight in, because the database passphrase was sitting in
 * EncryptedSharedPreferences the whole time, recoverable without the PIN.
 * The PIN was a gate in front of an unlocked door.
 *
 * <h3>The construction</h3>
 * The database passphrase is stored wrapped in two nested layers, and
 * unwrapping requires both the user's PIN and this specific device:
 *
 * <pre>
 *   stored = KeystoreEncrypt( PinEncrypt( dbKey ) )
 *
 *   PinEncrypt  : AES-256-GCM under a key derived by
 *                 PBKDF2-HMAC-SHA256(pin, perInstallSalt, 310_000)
 *   KeystoreEncrypt : AES-256-GCM under a non-exportable AndroidKeyStore key
 * </pre>
 *
 * Unwrapping runs the layers in reverse. Each property is load-bearing:
 *
 * <ul>
 *   <li><b>The PIN is a required input.</b> There is no code path in this class
 *       that returns key material without one. Hooking a boolean gains nothing
 *       because no boolean is consulted — a wrong PIN produces a wrong AES key,
 *       which produces a GCM tag mismatch, which is an authenticated failure
 *       rather than a comparison an attacker can skip.</li>
 *   <li><b>The Keystore layer binds the wrap to the device.</b> The Keystore key
 *       is non-exportable, so lifting the prefs file off the device yields a
 *       blob that cannot be attacked offline at any iteration count. This is
 *       what makes the modest PIN keyspace survivable: brute force must run
 *       on-device, through the Keystore, one attempt at a time.</li>
 *   <li><b>Order matters.</b> The PIN layer is innermost so the PIN is what
 *       authenticates the plaintext. Reversing the layers would let the Keystore
 *       layer's tag verify first, leaking whether the blob was well-formed
 *       independently of the PIN.</li>
 * </ul>
 *
 * <h3>Iteration count is not the fix (S06-I3)</h3>
 * PBKDF2 is at 310,000 iterations, matching {@code PinManager}. Raising it
 * further is not the remedy for a 4–6 digit PIN and is not claimed to be: a
 * 6-digit keyspace is 10^6, so even at a punishing per-guess cost an on-device
 * attacker finishes. The real mitigations are the device binding above, the
 * hardware attempt counters in the opt-in device-lock tier below, and the
 * longer passphrase option in {@code PinManager}. See {@code PinManager}'s
 * lockout section for the software-counter hardening that complements this.
 *
 * <h3>Root/hook posture</h3>
 * Existing root and hook detection stays telemetry, deliberately. It is not
 * promoted into a gate here, because a detection boolean is exactly the kind of
 * control this class exists to replace. The cryptography is the gate; if it is
 * ever reduced back to a boolean check, this item has regressed.
 *
 * <h3>Two tiers</h3>
 * <ul>
 *   <li><b>Default (PIN-derived).</b> Keystore key created with
 *       {@code setUserAuthenticationRequired(false)}. This is required, not
 *       preferred: {@code SecurePrefs} documents real budget devices (Vivo Y11,
 *       POCO C51) whose Keystore rejects user-auth-bound keys outright, and a
 *       default that bricks those devices is not a default.</li>
 *   <li><b>Opt-in device lock.</b> A second alias with
 *       {@code setUserAuthenticationRequired(true)}, StrongBox-preferred, gated
 *       behind {@code BiometricPrompt}. This is the only tier that gets true
 *       TEE/StrongBox attempt counting. Opt-in, capability-probed before it can
 *       be enabled, and reversible — see {@link #disableDeviceLock}.</li>
 * </ul>
 *
 * @see SessionKeyHolder for how the unwrapped key is held across background work
 */
public final class PinKeyGate {

    private static final String TAG = "PinKeyGate";

    /** Wrapped database key, default (PIN-derived) tier. */
    private static final String KEY_WRAPPED       = "pin_gate_wrapped_v1";
    /** Wrapped database key, opt-in device-lock tier. */
    private static final String KEY_WRAPPED_DL    = "pin_gate_wrapped_devicelock_v1";
    /** Per-install PBKDF2 salt, base64. */
    private static final String KEY_SALT          = "pin_gate_salt_v1";

    private static final String ALIAS             = "duoshield_pin_gate_v1";
    private static final String ALIAS_DEVICELOCK  = "duoshield_pin_gate_dl_v1";

    /** Matches {@code PinManager.ITERATIONS}. See the javadoc on why this is not the fix. */
    private static final int    ITERATIONS        = 310_000;
    private static final int    DERIVED_KEY_BITS  = 256;
    private static final int    SALT_BYTES        = 16;
    private static final int    GCM_IV_BYTES      = 12;
    private static final int    GCM_TAG_BITS      = 128;

    private static final String TRANSFORMATION    = "AES/GCM/NoPadding";

    private PinKeyGate() {}

    // ── Failure modes ────────────────────────────────────────────────────────

    /**
     * The supplied PIN did not unwrap the key. Raised on GCM tag failure, so it
     * is an authenticated rejection — not the result of comparing two values.
     */
    public static class WrongPinException extends GeneralGateException {
        public WrongPinException(String m) { super(m); }
    }

    /**
     * The gate cannot operate right now: Keystore unavailable, alias invalidated,
     * or no wrapped blob present. Distinct from {@link WrongPinException} because
     * callers must never delete data on this — the condition is often transient
     * (the same reasoning as {@code DatabaseKeyProvider.KeyUnavailableException}).
     */
    public static class GateUnavailableException extends GeneralGateException {
        public GateUnavailableException(String m) { super(m); }
        public GateUnavailableException(String m, Throwable c) { super(m, c); }
    }

    /** Base type so callers can catch either failure in one clause. */
    public static class GeneralGateException extends Exception {
        public GeneralGateException(String m) { super(m); }
        public GeneralGateException(String m, Throwable c) { super(m, c); }
    }

    // ── Enrolment ────────────────────────────────────────────────────────────

    /** True when a PIN-wrapped database key exists in either tier. */
    public static boolean isEnrolled(Context ctx) {
        SharedPreferences sp = SecurePrefs.get(ctx.getApplicationContext());
        return sp.getString(KEY_WRAPPED, null) != null
                || sp.getString(KEY_WRAPPED_DL, null) != null;
    }

    /** True when the opt-in device-lock tier is the active wrap. */
    public static boolean isDeviceLockEnabled(Context ctx) {
        return SecurePrefs.get(ctx.getApplicationContext())
                .getString(KEY_WRAPPED_DL, null) != null;
    }

    /**
     * Wraps {@code dbKey} under {@code pin} and persists it.
     *
     * <p>Called when a PIN is first set, and by the migration path that folds an
     * existing directly-stored database key into the gate.
     *
     * <p>Persisted with {@code commit()} and read back through a full unwrap
     * before returning, mirroring {@code DatabaseKeyProvider}'s durability
     * contract. The read-back is not paranoia: if the wrap were persisted but
     * not actually recoverable, the caller would delete the plaintext key and
     * the user's entire history would become permanently unopenable. Verify
     * first, delete second.
     *
     * @throws GateUnavailableException if the wrap could not be created,
     *         persisted, or verified. The caller must keep any existing
     *         plaintext key when this throws.
     */
    public static void enroll(Context ctx, String pin, byte[] dbKey)
            throws GateUnavailableException {
        Context appCtx = ctx.getApplicationContext();
        if (pin == null || pin.isEmpty()) {
            throw new GateUnavailableException("Refusing to enroll with an empty PIN.");
        }
        if (dbKey == null || dbKey.length == 0) {
            throw new GateUnavailableException("Refusing to enroll an empty database key.");
        }
        if (!SecurePrefs.getTier().isDurable()) {
            // Nothing written here would survive process death, so an enrolment
            // that "succeeds" would strand the user next launch.
            throw new GateUnavailableException(
                    "Refusing to enroll the PIN gate on a device with no durable"
                            + " Keystore tier (tier=" + SecurePrefs.getTier() + ").");
        }

        SharedPreferences sp = SecurePrefs.get(appCtx);
        byte[] salt = readOrCreateSalt(sp);
        byte[] pinLayer = null;
        byte[] wrapped  = null;
        try {
            pinLayer = gcmEncrypt(derivePinKey(pin, salt), dbKey);
            wrapped  = gcmEncrypt(keystoreKey(ALIAS, false), pinLayer);

            String encoded = Base64.encodeToString(wrapped, Base64.NO_WRAP);
            boolean committed = sp.edit().putString(KEY_WRAPPED, encoded).commit();
            if (!committed) {
                throw new GateUnavailableException(
                        "Failed to persist the PIN-wrapped database key (commit returned false).");
            }

            // Full round-trip verification, not just "is the string there".
            byte[] check = unwrapKey(appCtx, pin);
            boolean ok = Arrays.equals(check, dbKey);
            Arrays.fill(check, (byte) 0);
            if (!ok) {
                sp.edit().remove(KEY_WRAPPED).commit();
                throw new GateUnavailableException(
                        "PIN gate enrolment failed read-back verification; wrap discarded.");
            }
            Log.i(TAG, "PIN gate enrolled (default PIN-derived tier).");
        } catch (GateUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new GateUnavailableException(
                    "PIN gate enrolment failed: " + e.getClass().getSimpleName(), e);
        } finally {
            if (pinLayer != null) Arrays.fill(pinLayer, (byte) 0);
            if (wrapped  != null) Arrays.fill(wrapped,  (byte) 0);
        }
    }

    // ── Unwrap ───────────────────────────────────────────────────────────────

    /**
     * Recovers the database key from {@code pin}.
     *
     * <p>This is the whole point of the class: the PIN is an input to
     * decryption, so there is no way to obtain the return value without it.
     *
     * @return the plaintext database passphrase. The caller owns it and must
     *         zero it (or hand it to {@link SessionKeyHolder#unlock}, which
     *         copies, and then zero it).
     * @throws WrongPinException        the PIN is wrong (GCM tag mismatch).
     * @throws GateUnavailableException the gate itself is broken or absent.
     */
    public static byte[] unwrapKey(Context ctx, String pin)
            throws WrongPinException, GateUnavailableException {
        Context appCtx = ctx.getApplicationContext();
        SharedPreferences sp = SecurePrefs.get(appCtx);

        boolean deviceLock = sp.getString(KEY_WRAPPED_DL, null) != null;
        String  stored     = sp.getString(deviceLock ? KEY_WRAPPED_DL : KEY_WRAPPED, null);
        if (stored == null) {
            throw new GateUnavailableException("No PIN-wrapped database key is enrolled.");
        }
        if (pin == null || pin.isEmpty()) {
            // Explicit, because a silent empty-PIN derivation would "work"
            // cryptographically and then fail as a tag mismatch, which would
            // misreport a programming error as a wrong PIN.
            throw new GateUnavailableException("A PIN is required to unwrap the database key.");
        }

        byte[] blob     = Base64.decode(stored, Base64.NO_WRAP);
        byte[] pinLayer = null;
        try {
            SecretKey ksKey = keystoreKeyForUnwrap(deviceLock ? ALIAS_DEVICELOCK : ALIAS);
            // Outer layer: device binding. A failure here is a device/Keystore
            // problem, never a wrong PIN, so it must not be reported as one —
            // otherwise a Keystore hiccup would burn the user's attempt counter.
            try {
                pinLayer = gcmDecrypt(ksKey, blob);
            } catch (KeyPermanentlyInvalidatedException e) {
                throw new GateUnavailableException(
                        "Keystore key was permanently invalidated (device lock changed?).", e);
            } catch (Exception e) {
                throw new GateUnavailableException(
                        "Keystore layer failed to decrypt: " + e.getClass().getSimpleName(), e);
            }

            // Inner layer: the PIN. A tag failure here — and only here — means
            // the PIN was wrong.
            try {
                return gcmDecrypt(derivePinKey(pin, readOrCreateSalt(sp)), pinLayer);
            } catch (Exception e) {
                throw new WrongPinException("Incorrect PIN (authenticated decryption failed).");
            }
        } finally {
            Arrays.fill(blob, (byte) 0);
            if (pinLayer != null) Arrays.fill(pinLayer, (byte) 0);
        }
    }

    /**
     * Convenience: unwrap and publish to {@link SessionKeyHolder} in one step,
     * zeroing the intermediate copy. This is what unlock screens should call, so
     * no call site has to remember the zeroing contract.
     */
    public static void unlockSession(Context ctx, String pin)
            throws WrongPinException, GateUnavailableException {
        byte[] key = unwrapKey(ctx, pin);
        try {
            SessionKeyHolder.unlock(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // ── Opt-in device-lock tier ──────────────────────────────────────────────

    /**
     * True when this device can actually create a user-auth-bound Keystore key.
     *
     * <p>Probed by creating a throwaway key and deleting it, rather than
     * inferring from API level — the devices this guard exists for advertise
     * support and then fail at generation time. Enabling a tier that cannot work
     * would lock the user out of their own history, so this must be a real
     * attempt, not a capability guess.
     */
    public static boolean canUseDeviceLock(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        final String probeAlias = ALIAS_DEVICELOCK + "_probe";
        try {
            deleteAlias(probeAlias);
            buildKeystoreKey(probeAlias, true, true);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Device-lock tier unavailable: " + e.getClass().getSimpleName());
            return false;
        } finally {
            deleteAlias(probeAlias);
        }
    }

    /**
     * Re-wraps the database key under the user-auth-bound alias.
     *
     * <p>The caller must have just completed a {@code BiometricPrompt}
     * successfully, and must supply the PIN so the inner layer is rebuilt — this
     * tier adds hardware attempt counting on top of the PIN, it does not replace
     * the PIN.
     *
     * <p>The default-tier wrap is removed only after the new wrap verifies, so a
     * failure part-way through leaves the user with a working gate.
     */
    public static void enableDeviceLock(Context ctx, String pin)
            throws WrongPinException, GateUnavailableException {
        Context appCtx = ctx.getApplicationContext();
        if (!canUseDeviceLock(appCtx)) {
            throw new GateUnavailableException(
                    "This device cannot create user-authentication-bound Keystore keys.");
        }
        SharedPreferences sp = SecurePrefs.get(appCtx);
        byte[] dbKey = unwrapKey(appCtx, pin);   // proves the PIN before re-wrapping
        byte[] pinLayer = null, wrapped = null;
        try {
            deleteAlias(ALIAS_DEVICELOCK);
            pinLayer = gcmEncrypt(derivePinKey(pin, readOrCreateSalt(sp)), dbKey);
            wrapped  = gcmEncrypt(buildKeystoreKey(ALIAS_DEVICELOCK, true, true), pinLayer);
            if (!sp.edit()
                    .putString(KEY_WRAPPED_DL, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                    .commit()) {
                throw new GateUnavailableException(
                        "Failed to persist the device-lock wrap (commit returned false).");
            }
            // Verify before discarding the fallback.
            byte[] check = unwrapKey(appCtx, pin);
            boolean ok = Arrays.equals(check, dbKey);
            Arrays.fill(check, (byte) 0);
            if (!ok) {
                sp.edit().remove(KEY_WRAPPED_DL).commit();
                deleteAlias(ALIAS_DEVICELOCK);
                throw new GateUnavailableException(
                        "Device-lock wrap failed verification; reverted to the default tier.");
            }
            sp.edit().remove(KEY_WRAPPED).commit();
            deleteAlias(ALIAS);
            Log.i(TAG, "Device-lock tier enabled — attempts now counted by secure hardware.");
        } catch (GateUnavailableException | WrongPinException e) {
            throw e;
        } catch (Exception e) {
            throw new GateUnavailableException(
                    "Enabling the device-lock tier failed: " + e.getClass().getSimpleName(), e);
        } finally {
            Arrays.fill(dbKey, (byte) 0);
            if (pinLayer != null) Arrays.fill(pinLayer, (byte) 0);
            if (wrapped  != null) Arrays.fill(wrapped,  (byte) 0);
        }
    }

    /**
     * Reverts to the default PIN-derived tier.
     *
     * <p>This exists so a user who removes their device lock — or whose biometric
     * enrolment changes, invalidating the alias — is not permanently locked out
     * of their own message history. That failure mode is the reason the tier is
     * opt-in, and a one-way door would make the opt-in a trap.
     */
    public static void disableDeviceLock(Context ctx, String pin)
            throws WrongPinException, GateUnavailableException {
        Context appCtx = ctx.getApplicationContext();
        byte[] dbKey = unwrapKey(appCtx, pin);
        try {
            deleteAlias(ALIAS);
            enroll(appCtx, pin, dbKey);          // writes + verifies KEY_WRAPPED
            SecurePrefs.get(appCtx).edit().remove(KEY_WRAPPED_DL).commit();
            deleteAlias(ALIAS_DEVICELOCK);
            Log.i(TAG, "Device-lock tier disabled — reverted to the default PIN-derived tier.");
        } finally {
            Arrays.fill(dbKey, (byte) 0);
        }
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    /**
     * Destroys the wrapped keys and their Keystore aliases.
     *
     * <p>Wipe/duress paths call this. Deleting the aliases makes any wrapped blob
     * that survives on disk cryptographically inert, which matters because file
     * deletion on flash storage is not a guarantee — the ciphertext may persist
     * in unallocated blocks, but without the non-exportable Keystore key it is
     * unopenable regardless.
     */
    public static void clear(Context ctx) {
        try {
            SecurePrefs.get(ctx.getApplicationContext()).edit()
                    .remove(KEY_WRAPPED)
                    .remove(KEY_WRAPPED_DL)
                    .remove(KEY_SALT)
                    .commit();
        } catch (Exception e) {
            Log.w(TAG, "Could not clear PIN gate prefs: " + e.getClass().getSimpleName());
        }
        deleteAlias(ALIAS);
        deleteAlias(ALIAS_DEVICELOCK);
        SessionKeyHolder.lock();
    }

    // ── Crypto helpers ───────────────────────────────────────────────────────

    private static byte[] readOrCreateSalt(SharedPreferences sp) {
        String existing = sp.getString(KEY_SALT, null);
        if (existing != null) {
            byte[] s = Base64.decode(existing, Base64.NO_WRAP);
            if (s.length == SALT_BYTES) return s;
            Log.w(TAG, "Stored PBKDF2 salt has wrong length — regenerating.");
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        // commit(), not apply(): the salt must be on disk before any wrap that
        // depends on it, or a process death here makes the wrap unopenable.
        sp.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).commit();
        return salt;
    }

    private static SecretKey derivePinKey(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, DERIVED_KEY_BITS);
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
        try {
            return new SecretKeySpec(derived, "AES");
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    /** Fetches the alias, creating it if absent. Used on the write path only. */
    private static SecretKey keystoreKey(String alias, boolean userAuthRequired)
            throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(alias)) {
            return (SecretKey) ks.getKey(alias, null);
        }
        // StrongBox preferred, then plain TEE — same descending strategy as
        // SecurePrefs, and for the same reason: requesting StrongBox and falling
        // back is how you get it on the devices that have it, instead of never
        // asking.
        try {
            return buildKeystoreKey(alias, userAuthRequired, true);
        } catch (Exception e) {
            Log.d(TAG, "StrongBox unavailable for " + alias + "; using TEE.");
            deleteAlias(alias);   // a failed generation can leave a broken entry
            return buildKeystoreKey(alias, userAuthRequired, false);
        }
    }

    /**
     * Fetches an existing alias for unwrapping. Deliberately never creates one:
     * a freshly generated key cannot decrypt an existing blob, so auto-creating
     * here would turn "Keystore lost the alias" into a confusing tag failure
     * reported as a wrong PIN.
     */
    private static SecretKey keystoreKeyForUnwrap(String alias)
            throws GateUnavailableException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey k = (SecretKey) ks.getKey(alias, null);
            if (k == null) {
                throw new GateUnavailableException(
                        "Keystore alias '" + alias + "' is missing; the wrapped key"
                                + " cannot be recovered on this device.");
            }
            return k;
        } catch (GateUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new GateUnavailableException(
                    "Could not load Keystore alias '" + alias + "': "
                            + e.getClass().getSimpleName(), e);
        }
    }

    private static SecretKey buildKeystoreKey(String alias, boolean userAuthRequired,
                                              boolean strongBox) throws Exception {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);

        if (userAuthRequired) {
            b.setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Timeout 0 = authenticate for every use, which is what makes the
                // hardware counter meaningful rather than a per-session formality.
                b.setUserAuthenticationParameters(0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG
                                | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            } else {
                b.setUserAuthenticationValidityDurationSeconds(-1);
            }
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            b.setIsStrongBoxBacked(true);
        }

        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    private static void deleteAlias(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);
        } catch (Exception e) {
            Log.w(TAG, "Could not delete keystore alias "
                    + com.duoshield.app.util.LogRedact.metadata(alias) + ": "
                    + e.getClass().getSimpleName());
        }
    }

    /** Returns {@code iv || ciphertext||tag}. */
    private static byte[] gcmEncrypt(SecretKey key, byte[] plaintext) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = c.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES) {
            throw new IllegalStateException(
                    "Unexpected GCM IV length: " + (iv == null ? "null" : iv.length));
        }
        byte[] ct  = c.doFinal(plaintext);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        Arrays.fill(ct, (byte) 0);
        return out;
    }

    /** Inverse of {@link #gcmEncrypt}. Throws on tag mismatch. */
    private static byte[] gcmDecrypt(SecretKey key, byte[] ivAndCt) throws Exception {
        if (ivAndCt.length <= GCM_IV_BYTES) {
            throw new IllegalArgumentException("Ciphertext too short to contain an IV.");
        }
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_BITS, ivAndCt, 0, GCM_IV_BYTES));
        return c.doFinal(ivAndCt, GCM_IV_BYTES, ivAndCt.length - GCM_IV_BYTES);
    }
}
