package com.duoshield.app.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for S08-L3: PinManager must no longer persist the
 * plaintext PIN length beside the salt:hash string for either the
 * account-scoped PIN ({@code app_pin_length_<uid>}) or the device-scoped
 * gate PIN ({@code device_gate_pin_length}).
 *
 * <p>Exercises only the device-scoped surface ({@code setDevicePin} /
 * {@code hasDevicePinSet} / {@code getDevicePinLength}) via the same
 * SecurePrefs test-override seam {@code DeviceGatePinIsolationTest} uses —
 * the account-scoped surface ({@code setPin}) additionally calls
 * {@code FirebaseAuth.getInstance()}, which is not available in a plain JVM
 * unit test without a mocking framework this project does not have wired up.
 * The two code paths write the identical shape (salt:hash string, no length
 * key) using the same {@code KEY_LEN_PREFIX} scrub-on-write pattern, so this
 * is representative coverage for both, not exhaustive proof of the
 * account-scoped path specifically.
 *
 * <p>This test is part of {@code :app:testDebugUnitTest} and runs in the main CI
 * workflow. Device-level keypad timing and recovery acceptance are intentionally
 * deferred until the app's feature set is complete; this JVM test makes no claim
 * about those runtime flows.
 */
public class PinLengthNotPersistedTest {

    private FakeSharedPreferences deviceGateStore;

    @Before
    public void setUp() {
        // main store is unused by the device-scoped methods under test here,
        // but SecurePrefs.setTestOverridesForUnitTests requires both.
        deviceGateStore = new FakeSharedPreferences();
        SecurePrefs.setTestOverridesForUnitTests(new FakeSharedPreferences(), deviceGateStore);
    }

    @After
    public void tearDown() {
        SecurePrefs.clearTestOverridesForUnitTests();
    }

    @Test
    public void setDevicePin_neverWritesLengthKey_forShortPin() {
        assertTrue("device PIN must be durably persisted", PinManager.setDevicePin(null, "4821")); // 4-digit — MIN_PIN_LEN
        assertNull("a 4-digit device PIN must not leave a plaintext length key behind",
                deviceGateStore.getAll().get("device_gate_pin_length"));
    }

    @Test
    public void setDevicePin_neverWritesLengthKey_forLongPin() {
        assertTrue("device PIN must be durably persisted", PinManager.setDevicePin(null, "482913")); // 6-digit — MAX_PIN_LEN
        assertNull("a 6-digit device PIN must not leave a plaintext length key behind",
                deviceGateStore.getAll().get("device_gate_pin_length"));
    }

    @Test
    public void getDevicePinLength_returnsFixedUpperBound_regardlessOfActualPinLength() {
        // A 4-digit real PIN must not make getDevicePinLength() reveal "4" — it must
        // always return the fixed MAX_PIN_LEN upper bound shared by every device.
        assertTrue(PinManager.setDevicePin(null, "4821"));
        assertEquals("getDevicePinLength() must return the fixed upper bound, not the "
                        + "actual (shorter) PIN length — that discrepancy is the whole point "
                        + "of not persisting the real length",
                PinManager.MAX_PIN_LEN, PinManager.getDevicePinLength(null));

        // Setting a different (6-digit) PIN afterwards must not change the answer either
        // — it is a constant, never derived from stored state.
        assertTrue(PinManager.setDevicePin(null, "112233"));
        assertEquals(PinManager.MAX_PIN_LEN, PinManager.getDevicePinLength(null));
    }

    @Test
    public void legacyMigration_scrubsLengthKey_fromAccountScopedStore() {
        FakeSharedPreferences accountStore = new FakeSharedPreferences();
        SecurePrefs.setTestOverridesForUnitTests(accountStore, deviceGateStore);

        // Simulate a pre-S08-L3 install: hash + plaintext length both sitting in the
        // legacy (account-scoped) location, device-gate file empty.
        accountStore.edit()
                .putString("device_gate_pin_hash", "0123456789abcdef0123456789abcdef:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .putInt("device_gate_pin_length", 4)
                .commit();

        // First read after upgrading must migrate the hash but MUST NOT carry the
        // plaintext length key forward into the isolated device-gate file, and must
        // scrub it from the legacy location too.
        assertTrue("legacy device PIN must still be detected", PinManager.hasDevicePinSet(null));
        assertNull("migration must not copy the plaintext length key into the isolated file",
                deviceGateStore.getAll().get("device_gate_pin_length"));
        assertNull("migration must remove the plaintext length key from the legacy "
                        + "(account-scoped) file, not just leave it as an orphaned entry",
                accountStore.getAll().get("device_gate_pin_length"));
    }
}
