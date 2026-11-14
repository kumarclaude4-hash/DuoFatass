package com.duoshield.app.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for durable primary/device PIN record persistence. */
public class PinPersistenceTest {

    private FakeSharedPreferences accountStore;
    private FakeSharedPreferences deviceGateStore;

    @Before
    public void setUp() {
        accountStore = new FakeSharedPreferences();
        deviceGateStore = new FakeSharedPreferences();
        SecurePrefs.setTestOverridesForUnitTests(
                accountStore, deviceGateStore, null,
                SecurePrefs.SecurityTier.SOFTWARE, SecurePrefs.SecurityTier.SOFTWARE);
    }

    @After
    public void tearDown() {
        SecurePrefs.clearTestOverridesForUnitTests();
    }

    @Test
    public void devicePinIsReportedSavedOnlyAfterDurableCommitAndReadBack() {
        assertTrue(PinManager.setDevicePin(null, "482913"));
        String stored = deviceGateStore.getString("device_gate_pin_hash", null);
        assertTrue("a persisted PIN record must contain salt and hash",
                stored != null && stored.matches("[0-9a-f]{32}:[0-9a-f]{64}"));
        assertNull("the plaintext PIN length must remain absent",
                deviceGateStore.getAll().get("device_gate_pin_length"));
        assertTrue("the committed record must verify",
                PinManager.verifyDevicePin(null, "482913"));
    }

    @Test
    public void devicePinWriteFailsClosedForEphemeralTier() {
        SecurePrefs.setTestOverridesForUnitTests(
                accountStore, deviceGateStore, null,
                SecurePrefs.SecurityTier.SOFTWARE, SecurePrefs.SecurityTier.NONE);

        assertFalse(PinManager.setDevicePin(null, "482913"));
        assertNull("ephemeral storage must not be represented as a saved device PIN",
                deviceGateStore.getString("device_gate_pin_hash", null));
    }

    @Test
    public void failedDevicePinCommitRestoresPreviousInProcessRecord() {
        assertTrue(PinManager.setDevicePin(null, "482913"));
        String original = deviceGateStore.getString("device_gate_pin_hash", null);

        // Android SharedPreferences may update its in-memory map even when
        // commit() reports a disk failure; PinManager must restore that view.
        deviceGateStore.setCommitSucceeds(false);
        assertFalse(PinManager.setDevicePin(null, "112233"));
        assertEquals("failed updates must not leave the current process on the new PIN",
                original, deviceGateStore.getString("device_gate_pin_hash", null));
    }

    @Test
    public void sharedPinHashWriterRequiresDurabilityAndCommitSuccess() {
        assertFalse(PinManager.persistPinHash(
                accountStore, SecurePrefs.SecurityTier.NONE,
                "app_pin_hash_test", "aa:bb"));
        assertNull(accountStore.getString("app_pin_hash_test", null));

        assertTrue(PinManager.persistPinHash(
                accountStore, SecurePrefs.SecurityTier.SOFTWARE,
                "app_pin_hash_test", "aa:bb", "app_pin_length_test"));
        assertEquals("aa:bb", accountStore.getString("app_pin_hash_test", null));
        assertFalse(accountStore.contains("app_pin_length_test"));

        accountStore.setCommitSucceeds(false);
        assertFalse(PinManager.persistPinHash(
                accountStore, SecurePrefs.SecurityTier.SOFTWARE,
                "app_pin_hash_test", "cc:dd"));
    }
}
