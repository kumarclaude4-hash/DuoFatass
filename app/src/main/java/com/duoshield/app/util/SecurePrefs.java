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
 * <h3>Three independent containers</h3>
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
 * <p>{@link #getSessionState} is a third file, added for the same structural
 * reason. It holds the small amount of state that must outlive the wipe
 * <em>because</em> the wipe is what it describes: the pending account-lock
 * intent and the resume marker (see {@code PendingLockStore}). Previously the
 * duress path's lock intent lived only in a Firestore write queued in the
 * account-scoped store, so being offline at trigger time meant the wipe deleted
 * the queued mutation and the account was never locked at all — the attacker
 * chose whether the lock happened by pulling the network (S06-H3). Storing the
 * intent here instead makes the lock decision durable before any erasure runs,
 * so it survives a reboot, a force-stop, and an indefinitely offline device.
 *
 * <p><strong>Do not add erasure of this file to a wipe path.</strong> That is
 * exactly the bug the separate file prevents. {@code PendingLockStore} clears
 * its own keys once the server has confirmed the lock, and nothing else should.
 *
 * Initialisation strategy (applied identically to whichever file is being
 * opened), in descending order of protection:
 *  1. Standard MasterKey with AES256_GCM — hardware-backed when TEE is available.
 *  1b. Explicit KeyGenParameterSpec <em>requesting StrongBox</em> (API 28+). Tried
 *     before any software tier so that StrongBox is actually preferred rather than
 *     never requested at all; devices without StrongBox fail this fast and fall
 *     through.
 *  2. Explicit KeyGenParameterSpec — no StrongBox, no user-auth required — works on
 *     budget devices (Helio G36, Android Go) where the default MasterKey.Builder fails
 *     due to a known security-crypto:1.1.0-alpha06 bug on some manufacturers' KeyStore
 *     implementations.  This is the same key strength (AES-256-GCM) just without
 *     optional hardware constraints that the buggy KeyStore rejects.
 *  3. Delete the corrupted KeyStore alias and retry tier 2 — handles the case where a
 *     previous failed init left a broken key entry in the KeyStore.
 *
 * <h3>Fail closed when every tier fails (S08-H5)</h3>
 * This class used to fall back to plaintext {@code MODE_PRIVATE} prefs and set
 * {@code encryptionAvailable=false}, with callers instructed not to block the
 * user. That was wrong: it wrote the SQLCipher passphrase, the Signal identity
 * key and the PIN hash to an XML file in the clear, so any root shell or adb
 * backup on such a device yielded the database key verbatim — while the only
 * indication was a boolean most callers ignored. The old javadoc claimed this
 * matched "the same level of protection WhatsApp and Telegram use"; that
 * comparison did not hold, because neither stores a database passphrase in
 * plaintext prefs.
 *
 * <p>There is now no plaintext tier. When every tier fails, {@link #getTier}
 * reports {@link SecurityTier#NONE} and the store handed out is an in-memory
 * {@link EphemeralSharedPreferences} that never touches disk, so no at-rest
 * artifact exists to steal. Because such a store cannot survive process death,
 * {@link DeviceSecurityGate} blocks onboarding and restore on those devices
 * rather than letting the user create data that is guaranteed to be lost.
 *
 * <p>Existing installs that already wrote plaintext are rescued rather than
 * bricked — see {@link LegacyPlaintextMigrator}, which runs on every open and
 * folds any legacy plaintext entries into the encrypted store.
 *
 * Both files share the same AndroidKeyStore master-key alias when hardware/software
 * key tiers succeed — that is safe: the alias only protects each file's own generated
 * data key, and knowing one file's ciphertext reveals nothing about the other's.
 */
public class SecurePrefs {

    /**
     * How well the resolved store is protected. Replaces the old
     * {@code encryptionAvailable} boolean, which could not distinguish
     * "hardware-backed" from "software-backed but still encrypted" from
     * "not persisted at all" — a distinction that decides whether onboarding
     * may proceed.
     */
    public enum SecurityTier {
        /** Keystore-backed key, StrongBox or TEE. Persisted and encrypted. */
        HARDWARE,
        /**
         * Keystore-backed key without hardware constraints (tier 2/3). Still
         * AES-256-GCM encrypted at rest and still Keystore-protected; the
         * distinction from {@link #HARDWARE} is that key material may be
         * extractable given a compromised OS image.
         */
        SOFTWARE,
        /**
         * No Keystore path worked. The store is in-memory only and is lost on
         * process death. Nothing is persisted, so there is no at-rest artifact —
         * but nothing durable can be stored either.
         */
        NONE;

        /** True when values written to this store survive a process restart. */
        public boolean isDurable() { return this != NONE; }
    }

    private static final String TAG                = "SecurePrefs";
    private static final String FILE_NAME          = "duoshield_secure_prefs";
    private static final String DEVICE_GATE_FILE   = "device_gate_prefs";
    /**
     * Deliberately neutral file name. This container's whole purpose is to hold
     * a marker that a teardown is in flight, so a file called anything like
     * "duress" or "wipe" would itself be the disclosure the marker is carefully
     * named to avoid — and unlike the other two files, an adversary has a
     * specific reason to go looking for this one.
     */
    private static final String SESSION_STATE_FILE = "session_state_prefs";

    private static volatile SharedPreferences cached;
    private static volatile SecurityTier      tier                = SecurityTier.NONE;
    private static volatile boolean           initialized         = false;

    /**
     * True when legacy plaintext secrets are still on disk after a migration
     * attempt. See {@link LegacyPlaintextMigrator} for why they are sometimes
     * deliberately retained instead of deleted.
     */
    private static volatile boolean           legacyPlaintextRemains = false;

    private static volatile SharedPreferences deviceGateCached;
    private static volatile SecurityTier      deviceGateTier = SecurityTier.NONE;

    private static volatile SharedPreferences sessionStateCached;

    // Test-only injection point (see FakeSharedPreferences / DeviceGatePinIsolationTest
    // in app/src/test). All are null in production, so get()/getDeviceGate()/
    // getSessionState() behave exactly as before; when set, they short-circuit before
    // touching the real Context or Android Keystore, which aren't available in a plain
    // JVM unit test.
    private static volatile SharedPreferences testMainOverride;
    private static volatile SharedPreferences testDeviceGateOverride;
    private static volatile SharedPreferences testSessionStateOverride;

    public static SharedPreferences get(Context context) {
        if (testMainOverride != null) return testMainOverride;
        if (cached != null) return cached;
        synchronized (SecurePrefs.class) {
            if (cached != null) return cached;
            Built built = buildTiered(context, FILE_NAME);
            cached                 = built.prefs;
            tier                   = built.tier;
            legacyPlaintextRemains = built.legacyPlaintextRemains;
            initialized            = true;
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
            Built built = buildTiered(context, DEVICE_GATE_FILE);
            deviceGateCached = built.prefs;
            deviceGateTier   = built.tier;
            return deviceGateCached;
        }
    }

    /**
     * Isolated container for wipe-surviving session/teardown state. See the class
     * javadoc for why this must never share a file with {@link #get}, and must
     * never be added to a wipe path.
     */
    public static SharedPreferences getSessionState(Context context) {
        if (testSessionStateOverride != null) return testSessionStateOverride;
        if (sessionStateCached != null) return sessionStateCached;
        synchronized (SecurePrefs.class) {
            if (sessionStateCached != null) return sessionStateCached;
            sessionStateCached = buildTiered(context, SESSION_STATE_FILE).prefs;
            return sessionStateCached;
        }
    }

    /** Result of {@link #buildTiered}: the resolved store plus which tier produced it. */
    private static final class Built {
        final SharedPreferences prefs;
        final SecurityTier      tier;
        final boolean           legacyPlaintextRemains;
        Built(SharedPreferences prefs, SecurityTier tier, boolean legacyPlaintextRemains) {
            this.prefs                  = prefs;
            this.tier                   = tier;
            this.legacyPlaintextRemains = legacyPlaintextRemains;
        }
    }

    /**
     * Runs the tiered EncryptedSharedPreferences initialisation strategy (see
     * class javadoc) against an arbitrary file name.
     *
     * <p>When every tier fails this returns an in-memory store and
     * {@link SecurityTier#NONE} — it never returns a plaintext on-disk store.
     * See the class javadoc for why the plaintext fallback was removed.
     */
    private static Built buildTiered(Context context, String fileName) {
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
            return finish(appCtx, fileName, sp, SecurityTier.HARDWARE);
        } catch (Exception e1) {
            Log.w(TAG, "ESP tier 1 failed for " + com.duoshield.app.util.LogRedact.metadata(fileName) + " ("
                    + com.duoshield.app.util.LogRedact.metadata(android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL)
                    + " API=" + android.os.Build.VERSION.SDK_INT + "): "
                    + e1.getClass().getSimpleName() + ": " + com.duoshield.app.util.LogRedact.error(e1));
        }

        // ── Tier 1b: explicit spec REQUESTING StrongBox (API 28+) ────────────
        // Attempted before any software tier so StrongBox is genuinely preferred.
        // Previously requireStrongBox was only ever passed as false, so the
        // strongest available backing was never actually asked for.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                SharedPreferences sp = buildWithExplicitSpec(appCtx, fileName, true);
                Log.i(TAG, "ESP ready (tier 1b — StrongBox) for " + fileName + ".");
                return finish(appCtx, fileName, sp, SecurityTier.HARDWARE);
            } catch (Exception eSb) {
                // Expected on the majority of devices: no StrongBox present.
                Log.d(TAG, "ESP tier 1b (StrongBox) unavailable for " + fileName + ": "
                        + eSb.getClass().getSimpleName());
                // A failed attempt can leave a half-created alias behind, which would
                // then poison tier 2. Clear it before falling through.
                deleteMasterKeyAlias("post-StrongBox-failure");
            }
        }

        // ── Tier 2: explicit spec — no StrongBox, no user-auth required ──────
        // Fixes known security-crypto bug on budget MediaTek / Android Go devices
        // where MasterKey.Builder.setKeyScheme() silently adds constraints the
        // device's KeyStore implementation rejects.
        try {
            SharedPreferences sp = buildWithExplicitSpec(appCtx, fileName, false);
            Log.i(TAG, "ESP ready (tier 2 — explicit software spec) for " + fileName + ".");
            return finish(appCtx, fileName, sp, SecurityTier.SOFTWARE);
        } catch (Exception e2) {
            Log.w(TAG, "ESP tier 2 failed for " + com.duoshield.app.util.LogRedact.metadata(fileName) + ": "
                    + e2.getClass().getSimpleName() + ": " + com.duoshield.app.util.LogRedact.error(e2));
        }

        // ── Tier 3: delete corrupted alias + retry ────────────────────────────
        try {
            deleteMasterKeyAlias("corrupted-alias-recovery");
            SharedPreferences sp = buildWithExplicitSpec(appCtx, fileName, false);
            Log.i(TAG, "ESP ready (tier 3 — alias cleared + software spec) for " + fileName + ".");
            return finish(appCtx, fileName, sp, SecurityTier.SOFTWARE);
        } catch (Exception e3) {
            Log.e(TAG, "ESP tier 3 (alias-clear + retry) failed for " + com.duoshield.app.util.LogRedact.metadata(fileName) + ": "
                    + e3.getClass().getSimpleName() + ": " + com.duoshield.app.util.LogRedact.error(e3)
                    + " — FAILING CLOSED to an in-memory store; nothing will be"
                    + " persisted for this file."
                    + " Device: " + com.duoshield.app.util.LogRedact.metadata(
                            android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL)
                    + " API=" + android.os.Build.VERSION.SDK_INT + " (" + com.duoshield.app.util.LogRedact.error(e3) + ")");
        }

        // ── Fail closed: in-memory only, never plaintext on disk ─────────────
        // Any legacy plaintext is still loaded into memory so an existing install
        // keeps working this session, but is deliberately left on disk rather than
        // deleted — see LegacyPlaintextMigrator for why removing it would cause
        // unrecoverable loss. DeviceSecurityGate blocks onboarding/restore here.
        SharedPreferences ephemeral = new EphemeralSharedPreferences();
        return finish(appCtx, fileName, ephemeral, SecurityTier.NONE);
    }

    /**
     * Applies the legacy-plaintext migration to a freshly resolved store and
     * packages the result. Centralised so no tier can accidentally skip it.
     */
    private static Built finish(Context appCtx, String fileName,
                                SharedPreferences store, SecurityTier resolvedTier) {
        LegacyPlaintextMigrator.Result r;
        try {
            r = LegacyPlaintextMigrator.migrate(appCtx, fileName, store, resolvedTier.isDurable());
        } catch (Exception e) {
            // Migration must never prevent the app from opening its store.
            Log.e(TAG, "Legacy plaintext migration threw for " + com.duoshield.app.util.LogRedact.metadata(fileName) + ": "
                    + e.getClass().getSimpleName() + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            // An exception means the raw source could not be proven empty or
            // fully migrated. Preserve the fail-closed onboarding/restore gate.
            r = new LegacyPlaintextMigrator.Result(0, 0, true);
        }
        return new Built(store, resolvedTier, r.plaintextRemains);
    }

    /** Best-effort removal of the shared master-key alias. */
    private static void deleteMasterKeyAlias(String reason) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS);
                Log.w(TAG, "Deleted KeyStore master-key alias (" + com.duoshield.app.util.LogRedact.metadata(reason) + ").");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not delete master-key alias (" + com.duoshield.app.util.LogRedact.metadata(reason) + "): "
                    + e.getClass().getSimpleName());
        }
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
     * Which tier resolved for the account-scoped file returned by {@link #get}.
     *
     * <p>Returns {@link SecurityTier#NONE} before {@link #get} has been called
     * even once, because no tier has been resolved yet. Callers that need to
     * distinguish "not yet initialised" from "genuinely unprotected" should
     * check {@link #isInitialized()} as well — {@link DeviceSecurityGate} does.
     */
    public static SecurityTier getTier() {
        return tier;
    }

    /** Which tier resolved for the device-gate file returned by {@link #getDeviceGate}. */
    public static SecurityTier getDeviceGateTier() {
        return deviceGateTier;
    }

    /** True once {@link #get} has resolved a tier for the account-scoped file. */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * True when legacy plaintext secrets are still present on disk after the
     * migration ran. See {@link LegacyPlaintextMigrator} for why they are
     * sometimes deliberately retained rather than deleted.
     */
    public static boolean legacyPlaintextRemains() {
        return legacyPlaintextRemains;
    }

    /**
     * Returns true if the account-scoped store from {@link #get} is encrypted and
     * persisted — i.e. a Keystore tier resolved.
     *
     * <p>Retained as a delegating shim over {@link #getTier} so the existing
     * call sites keep compiling and keep their original meaning. There is no
     * longer a plaintext on-disk fallback: false now means the store is
     * <em>in-memory only</em> ({@link SecurityTier#NONE}), not "plaintext on
     * disk" as the previous javadoc described.
     *
     * <p>Prefer {@link #getTier} in new code when the HARDWARE/SOFTWARE
     * distinction matters; this boolean deliberately collapses them.
     */
    public static boolean isAvailable() {
        return initialized && tier.isDurable();
    }

    /**
     * Resets all cached instances — intended for use in WipeHelper / tests only.
     * Safe to call even though the device-gate and session-state files' on-disk
     * contents are never touched by an account wipe: this only drops the in-memory
     * wrappers, forcing the next {@link #getDeviceGate} / {@link #getSessionState}
     * call to reopen the same untouched file.
     */
    public static void reset() {
        synchronized (SecurePrefs.class) {
            cached                 = null;
            tier                   = SecurityTier.NONE;
            initialized            = false;
            legacyPlaintextRemains = false;
            deviceGateCached       = null;
            deviceGateTier         = SecurityTier.NONE;
            sessionStateCached     = null;
        }
    }

    /**
     * Test-only. Injects fakes so classes built on top of SecurePrefs (e.g.
     * PinManager) can be exercised in a plain JUnit test without a real
     * Android runtime. See FakeSharedPreferences / DeviceGatePinIsolationTest.
     */
    static void setTestOverridesForUnitTests(SharedPreferences main, SharedPreferences deviceGate) {
        setTestOverridesForUnitTests(main, deviceGate, null);
    }

    /** Test-only. As above, additionally injecting the session-state container. */
    static void setTestOverridesForUnitTests(SharedPreferences main,
                                             SharedPreferences deviceGate,
                                             SharedPreferences sessionState) {
        testMainOverride         = main;
        testDeviceGateOverride   = deviceGate;
        testSessionStateOverride = sessionState;
        // Injected in-memory fakes represent a durable SOFTWARE/HARDWARE
        // backing store unless a test directly exercises the persistence helper
        // with an explicit NONE tier.
        tier                    = main == null ? SecurityTier.NONE : SecurityTier.SOFTWARE;
        initialized             = main != null;
        deviceGateTier          = deviceGate == null ? SecurityTier.NONE : SecurityTier.SOFTWARE;
    }

    /** Test-only. Clears injected fakes and resets caches. */
    static void clearTestOverridesForUnitTests() {
        testMainOverride         = null;
        testDeviceGateOverride   = null;
        testSessionStateOverride = null;
        reset();
    }
}
