package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.security.KeyStore;

/**
 * Returns SharedPreferences instances for storing crypto material
 * (Signal identity key pair, prekeys, PIN hashes).
 *
 * <h3>Two independent containers</h3>
 * {@link #get} returns the account-scoped file — Signal key material, the
 * app PIN hash, duress PIN hash, etc. It is blank-cleared wholesale by
 * {@code DuressManager.performLogout()}, {@code WipeHelper.wipeAll()}, and
 * the Danger Zone "unpair" flow whenever an account is wiped from this
 * device. {@link #getDeviceGate} returns a second, physically separate file
 * used only for the device-level PIN gate ({@code PinManager}'s device-scoped
 * methods, {@code DevicePinGateActivity}) — a protection that exists
 * independently of any signed-in account and must therefore survive every
 * one of those wipes. Keeping it in its own file makes that a structural
 * guarantee: none of the wipe call sites touch it, so there is no exclusion
 * list to keep in sync as the account-scoped file grows new keys over time.
 *
 * Initialisation strategy (three tiers, applied identically to whichever
 * file is being opened):
 *  1. Standard MasterKey with AES256_GCM — hardware-backed when TEE is available.
 *  2. Explicit KeyGenParameterSpec — no StrongBox, no user-auth required — works on
 *     budget devices (Helio G36, Android Go) where the default MasterKey.Builder fails
 *     due to a known security-crypto:1.1.0-alpha06 bug on some manufacturers' KeyStore
 *     implementations.  This is the same key strength (AES-256-GCM) just without
 *     optional hardware constraints that the buggy KeyStore rejects.
 *  3. Delete the corrupted KeyStore alias and retry tier 2 — handles the case where a
 *     previous failed init left a broken key entry in the KeyStore.
 *
 * If ALL three tiers fail, the app falls back to plaintext SharedPreferences AND
 * sets encryptionAvailable=false. Callers may check isAvailable() and degrade gracefully,
 * but they must NOT block the user — plaintext prefs are still protected by Android's
 * per-app file isolation (MODE_PRIVATE), which is the same level of protection WhatsApp
 * and Telegram use on devices without a hardware TEE.
 *
 * Both files share the same AndroidKeyStore master-key alias when hardware/software
 * key tiers succeed — that is safe: the alias only protects each file's own generated
 * data key, and knowing one file's ciphertext reveals nothing about the other's.
 */
public class SecurePrefs {

    private static final String TAG               = "SecurePrefs";
    private static final String FILE_NAME         = "duoshield_secure_prefs";
    private static final String DEVICE_GATE_FILE  = "device_gate_prefs";

    private static volatile SharedPreferences cached;
    private static volatile boolean           encryptionAvailable = false;
    private static volatile boolean           initialized         = false;

    private static volatile SharedPreferences deviceGateCached;
    private static volatile boolean           deviceGateEncryptionAvailable = false;

    // Test-only injection point (see FakeSharedPreferences / DeviceGatePinIsolationTest
    // in app/src/test). Both are null in production, so get()/getDeviceGate() behave
    // exactly as before; when set, they short-circuit before touching the real Context
    // or Android Keystore, which aren't available in a plain JVM unit test.
    private static volatile SharedPreferences testMainOverride;
    private static volatile SharedPreferences testDeviceGateOverride;

    public static SharedPreferences get(Context context) {
        if (testMainOverride != null) return testMainOverride;
        if (cached != null) return cached;
        synchronized (SecurePrefs.class) {
            if (cached != null) return cached;
            // S08-H5 / S07-M1: the account-scoped file stores crypto key material
            // (Signal identity keys, pre-keys, PIN hashes). A plaintext fallback
            // would expose that material on any device where the KeyStore is
            // unavailable or broken. Throw here instead so the startup flow can
            // surface a clear error rather than silently degrading to unencrypted
            // storage.  getDeviceGate() keeps the plaintext fallback because it
            // stores only the device-level PIN gate and must survive account wipes.
            Built built = buildTiered(context, FILE_NAME, /* throwOnFallback= */ true);
            cached              = built.prefs;
            encryptionAvailable = built.encryptionAvailable;
            initialized         = true;
            return cached;
        }
    }

    /**
     * Isolated container for the device-level PIN gate. See the class javadoc
     * for why this must never share a file with {@link #get}.
     */
    public static SharedPreferences getDeviceGate(Context context) {
        if (testDeviceGateOverride != null) return testDeviceGateOverride;
        if (deviceGateCached != null) return deviceGateCached;
        synchronized (SecurePrefs.class) {
            if (deviceGateCached != null) return deviceGateCached;
            // Device-gate stores only the device-level PIN — no crypto key material.
            // A plaintext fallback here is acceptable and must survive account wipes.
            Built built = buildTiered(context, DEVICE_GATE_FILE, /* throwOnFallback= */ false);
            deviceGateCached              = built.prefs;
            deviceGateEncryptionAvailable = built.encryptionAvailable;
            return deviceGateCached;
        }
    }

    /** Result of {@link #buildTiered}: the resolved store plus which tier produced it. */
    private static final class Built {
        final SharedPreferences prefs;
        final boolean           encryptionAvailable;
        Built(SharedPreferences prefs, boolean encryptionAvailable) {
            this.prefs               = prefs;
            this.encryptionAvailable = encryptionAvailable;
        }
    }

    /**
     * Runs the three-tier EncryptedSharedPreferences initialisation strategy
     * (see class javadoc) against an arbitrary file name.
     *
     * @param throwOnFallback if {@code true}, throws {@link IllegalStateException}
     *   instead of returning a plaintext-backed {@link Built} when every encryption
     *   tier fails. Must be {@code true} for files that store crypto key material
     *   ({@link #FILE_NAME}); {@code false} for files whose plaintext fallback is
     *   acceptable ({@link #DEVICE_GATE_FILE}).
     */
    private static Built buildTiered(Context context, String fileName, boolean throwOnFallback) {
        Context appCtx = context.getApplicationContext();

        // ── Tier 1: standard MasterKey (hardware-backed when available) ──────
        try {
            MasterKey masterKey = new MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            SharedPreferences sp = EncryptedSharedPreferences.create(
                    appCtx, fileName, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            Log.d(TAG, "ESP ready (tier 1 — hardware key) for " + fileName + ".");
            return new Built(sp, true);
        } catch (Exception e1) {
            Log.w(TAG, "ESP tier 1 failed for " + fileName + " ("
                    + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " API=" + android.os.Build.VERSION.SDK_INT + "): "
                    + e1.getClass().getSimpleName() + ": " + e1.getMessage());
        }

        // ── Tier 2: explicit spec — no StrongBox, no user-auth required ──────
        // Fixes known security-crypto bug on budget MediaTek / Android Go devices
        // where MasterKey.Builder.setKeyScheme() silently adds constraints the
        // device's KeyStore implementation rejects.
        try {
            SharedPreferences sp = buildWithExplicitSpec(appCtx, fileName, false);
            Log.i(TAG, "ESP ready (tier 2 — explicit software spec) for " + fileName + ".");
            return new Built(sp, true);
        } catch (Exception e2) {
            Log.w(TAG, "ESP tier 2 failed for " + fileName + ": "
                    + e2.getClass().getSimpleName() + ": " + e2.getMessage());
        }

        // ── Tier 3: delete corrupted alias + retry ────────────────────────────
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS);
                Log.w(TAG, "Deleted corrupted KeyStore alias — retrying (" + fileName + ").");
            }
            SharedPreferences sp = buildWithExplicitSpec(appCtx, fileName, false);
            Log.i(TAG, "ESP ready (tier 3 — alias cleared + software spec) for " + fileName + ".");
            return new Built(sp, true);
        } catch (Exception e3) {
            Log.e(TAG, "ESP tier 3 (alias-clear + retry) failed for " + fileName + ": "
                    + e3.getClass().getSimpleName() + ": " + e3.getMessage()
                    + " — falling back to plaintext MODE_PRIVATE prefs."
                    + " Device: " + android.os.Build.MANUFACTURER
                    + " " + android.os.Build.MODEL
                    + " API=" + android.os.Build.VERSION.SDK_INT, e3);
        }

        // ── Fallback path ─────────────────────────────────────────────────────
        // S08-H5 / S07-M1: Files that store crypto key material must not fall
        // back to plaintext — throw so the caller can surface a fatal error rather
        // than silently storing private keys unencrypted.
        if (throwOnFallback) {
            throw new IllegalStateException(
                    "EncryptedSharedPreferences unavailable for '" + fileName
                    + "' and plaintext fallback is not permitted for this file. "
                    + "Device: " + android.os.Build.MANUFACTURER
                    + " " + android.os.Build.MODEL
                    + " API=" + android.os.Build.VERSION.SDK_INT);
        }
        // Still protected by Android's per-app file isolation (MODE_PRIVATE) —
        // same posture as WhatsApp/Telegram on devices without a hardware TEE.
        SharedPreferences sp = appCtx.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        return new Built(sp, false);
    }

    /**
     * Builds an EncryptedSharedPreferences with an explicit KeyGenParameterSpec that
     * avoids optional constraints (StrongBox, user-auth) which some budget devices reject.
     */
    private static SharedPreferences buildWithExplicitSpec(Context appCtx, String fileName,
                                                            boolean requireStrongBox)
            throws Exception {
        KeyGenParameterSpec.Builder specBuilder = new KeyGenParameterSpec.Builder(
                MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
                // Explicitly NOT setting setUserAuthenticationRequired(true) — that is
                // what causes the "screen lock required" failure on Vivo Y11 / POCO C51
                // when security-crypto sets it implicitly on some API levels.
        // setIsStrongBoxBacked() requires API 28; minSdk is 26. Devices below 28 never
        // have StrongBox anyway, so requireStrongBox is only ever true when SDK_INT >= 28.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(requireStrongBox);
        }
        KeyGenParameterSpec spec = specBuilder.build();
        MasterKey masterKey = new MasterKey.Builder(appCtx)
                .setKeyGenParameterSpec(spec)
                .build();
        return EncryptedSharedPreferences.create(
                appCtx, fileName, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /**
     * Returns true if EncryptedSharedPreferences initialised successfully for the
     * account-scoped file returned by {@link #get}.
     * False means the fallback plaintext store is in use — crypto material is still
     * scoped to this app (MODE_PRIVATE) but not hardware/software encrypted.
     */
    public static boolean isAvailable() {
        return initialized && encryptionAvailable;
    }

    /**
     * Resets both cached instances — intended for use in WipeHelper / tests only.
     * Safe to call even though the device-gate file's on-disk contents are never
     * touched by an account wipe: this only drops the in-memory wrapper, forcing
     * the next {@link #getDeviceGate} call to reopen the same untouched file.
     */
    public static void reset() {
        synchronized (SecurePrefs.class) {
            cached                        = null;
            encryptionAvailable           = false;
            initialized                   = false;
            deviceGateCached              = null;
            deviceGateEncryptionAvailable = false;
        }
    }

    /**
     * Test-only. Injects fakes so classes built on top of SecurePrefs (e.g.
     * PinManager) can be exercised in a plain JUnit test without a real
     * Android runtime. See FakeSharedPreferences / DeviceGatePinIsolationTest.
     */
    static void setTestOverridesForUnitTests(SharedPreferences main, SharedPreferences deviceGate) {
        testMainOverride       = main;
        testDeviceGateOverride = deviceGate;
    }

    /** Test-only. Clears injected fakes and resets caches. */
    static void clearTestOverridesForUnitTests() {
        testMainOverride       = null;
        testDeviceGateOverride = null;
        reset();
    }
}
