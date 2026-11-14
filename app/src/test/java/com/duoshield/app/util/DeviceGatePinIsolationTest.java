package com.duoshield.app.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the device-gate PIN storage split.
 *
 * <p>Before this fix, PinManager's device-scoped PIN (hasDevicePinSet /
 * setDevicePin / verifyDevicePin) was stored in the SAME SecurePrefs file
 * that DuressManager.performLogout() blank-clears during a duress wipe (and
 * that WipeHelper.wipeAll() / the Danger Zone unpair flow blank-clear too).
 * That meant a duress wipe silently erased the device-gate PIN along with
 * the account material: on the next cold launch, hasDevicePinSet() would
 * return false and DevicePinGateActivity would open in SETUP mode instead of
 * VERIFY mode — anyone holding the wiped device could set a brand-new device
 * PIN and walk straight past the gate it exists to enforce.
 *
 * <p>This test reproduces that exact sequence against the fix (device gate
 * now lives in SecurePrefs.getDeviceGate(), a physically separate file):
 * <ol>
 *   <li>Set a device-gate PIN, as DevicePinGateActivity's setup mode does.</li>
 *   <li>Simulate DuressManager.performLogout()'s wipe step verbatim: a full
 *       clear() of the account-scoped store only — never the device-gate
 *       store.</li>
 *   <li>Assert hasDevicePinSet() is still true and the original PIN still
 *       verifies, i.e. DevicePinGateActivity computes
 *       {@code setupMode = !hasDevicePinSet(...)} as false (VERIFY mode) on
 *       the next cold launch — never SETUP mode.</li>
 * </ol>
 *
 * <p>There is no Robolectric / instrumented test setup in this project, and
 * a real "kill the app, cold launch, watch which Activity mode renders" test
 * needs a real Android runtime this repo can't run headlessly. Hand-rolled
 * in-memory SharedPreferences (FakeSharedPreferences), injected through
 * SecurePrefs' test-only override seam, let this test exercise the real
 * PinManager / SecurePrefs classes — the actual code the app runs — rather
 * than a re-implementation of their logic.
 */
public class DeviceGatePinIsolationTest {

    private FakeSharedPreferences accountStore;
    private FakeSharedPreferences deviceGateStore;

    @Before
    public void setUp() {
        accountStore    = new FakeSharedPreferences();
        deviceGateStore = new FakeSharedPreferences();
        SecurePrefs.setTestOverridesForUnitTests(accountStore, deviceGateStore);
    }

    @After
    public void tearDown() {
        SecurePrefs.clearTestOverridesForUnitTests();
    }

    @Test
    public void devicePin_survivesAccountStoreWipe_andStillVerifies() {
        // 1. Fresh install sets a device-gate PIN (DevicePinGateActivity setup mode).
        assertTrue(PinManager.setDevicePin(null, "482913"));
        assertTrue("device PIN must be set right after setup", PinManager.hasDevicePinSet(null));

        // 2. Reproduce DuressManager.performLogout()'s wipe step verbatim: a full
        //    clear() of the account-scoped store ONLY (mirrors "SecurePrefs.get(ctx)
        //    .edit().clear().commit()" — WipeHelper.wipeAll() and the Danger Zone
        //    unpair flow do the same thing to the same file).
        accountStore.edit().clear().commit();

        // 3. The device gate must survive its own account's wipe — the entire point
        //    of giving it a separate file. This is the exact assertion
        //    DevicePinGateActivity's mode check depends on:
        //    setupMode = !PinManager.hasDevicePinSet(ctx).
        assertTrue("device PIN must survive an account-store wipe (else the gate "
                        + "reopens in SETUP mode instead of VERIFY mode post-duress)",
                PinManager.hasDevicePinSet(null));
        assertFalse("DevicePinGateActivity must compute setupMode=false (VERIFY) after the wipe",
                !PinManager.hasDevicePinSet(null));
        assertTrue("the original device PIN must still verify after the wipe",
                PinManager.verifyDevicePin(null, "482913"));
    }

    @Test
    public void devicePin_neverWrittenToAccountScopedStore() {
        assertTrue(PinManager.setDevicePin(null, "112233"));

        assertFalse("device-gate store must hold the hash", deviceGateStore.getAll().isEmpty());
        assertTrue("account-scoped store must never receive device-gate keys — an "
                        + "exclusion list is not how this guarantee is made, a separate "
                        + "file is",
                accountStore.getAll().isEmpty());
    }

    @Test
    public void legacyDevicePin_migratesFromAccountStore_onFirstRead() {
        // Simulate an install that set its device PIN before the storage split —
        // both keys used to live in the account-scoped file under the same names.
        assertTrue(PinManager.setDevicePin(null, "999000")); // writes into deviceGateStore today
        // Move it back to simulate the pre-fix layout for this test.
        String hash = deviceGateStore.getString("device_gate_pin_hash", null);
        int len = deviceGateStore.getInt("device_gate_pin_length", 6);
        deviceGateStore.edit().clear().commit();
        accountStore.edit()
                .putString("device_gate_pin_hash", hash)
                .putInt("device_gate_pin_length", len)
                .commit();

        // First read after "upgrading" must migrate it into the isolated file...
        assertTrue("legacy device PIN must still be detected", PinManager.hasDevicePinSet(null));
        assertTrue("migration must copy the hash into the isolated file",
                deviceGateStore.getString("device_gate_pin_hash", null) != null);
        assertTrue("migration must remove the stale copy from the account-scoped file "
                        + "so a future account wipe finding it there is a non-issue",
                accountStore.getString("device_gate_pin_hash", null) == null);

        // ...and it must still verify against the original PIN.
        assertTrue(PinManager.verifyDevicePin(null, "999000"));
    }
}
