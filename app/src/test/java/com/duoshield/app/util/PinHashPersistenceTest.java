package com.duoshield.app.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression tests for durable single-store and recoverable paired PIN writes. */
public class PinHashPersistenceTest {

    private static final String ACCOUNT_KEY = "app_pin_hash_uid-a";
    private static final String DEVICE_KEY = "device_gate_pin_hash";

    @Test
    public void singleStoreWriteRequiresDurableTierCommitAndExactReadback() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        preferences.edit().putString("legacy", "old").putInt("pin_length", 6).commit();

        assertTrue(PinHashPersistence.persistSingle(
                preferences, SecurePrefs.SecurityTier.SOFTWARE,
                ACCOUNT_KEY, "0123456789abcdef0123456789abcdef:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "legacy", "pin_length"));
        assertEquals("0123456789abcdef0123456789abcdef:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", preferences.getString(ACCOUNT_KEY, null));
        assertFalse(preferences.contains("legacy"));
        assertFalse(preferences.contains("pin_length"));
        assertFalse(PinHashPersistence.persistSingle(
                preferences, SecurePrefs.SecurityTier.NONE,
                ACCOUNT_KEY, "new-salt:new-hash"));
    }

    @Test
    public void incompleteReadbackNeverReportsDurableSuccess() {
        FakeSharedPreferences preferences = new FakeSharedPreferences() {
            @Override
            public String getString(String key, String defValue) {
                if (ACCOUNT_KEY.equals(key)) return "ffffffffffffffffffffffffffffffff:"
                        + "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
                return super.getString(key, defValue);
            }
        };
        assertFalse(PinHashPersistence.persistSingle(
                preferences, SecurePrefs.SecurityTier.SOFTWARE,
                ACCOUNT_KEY, "0123456789abcdef0123456789abcdef:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    @Test
    public void failedSingleStoreCommitNeverReportsDurableSuccess() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        preferences.setCommitSucceeds(false);

        assertFalse(PinHashPersistence.persistSingle(
                preferences, SecurePrefs.SecurityTier.SOFTWARE,
                ACCOUNT_KEY, "0123456789abcdef0123456789abcdef:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        // Android SharedPreferences can update its process map even when disk
        // persistence fails. The false result is the contract callers must obey.
        assertEquals("0123456789abcdef0123456789abcdef:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", preferences.getString(ACCOUNT_KEY, null));
    }

    @Test
    public void pairedWriteUsesSeparateHashesAndRemovesLegacyMetadata() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        account.edit().putString("app_pin_hash", "old-account")
                .putInt("app_pin_length_uid-a", 6).commit();
        device.edit().putInt("device_gate_pin_length", 6).commit();

        assertTrue(PinHashPersistence.persistPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                ACCOUNT_KEY, "0123456789abcdef0123456789abcdef:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "app_pin_hash", "app_pin_length_uid-a",
                device, SecurePrefs.SecurityTier.HARDWARE,
                DEVICE_KEY, "fedcba9876543210fedcba9876543210:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "device_gate_pin_length"));

        assertEquals("0123456789abcdef0123456789abcdef:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", account.getString(ACCOUNT_KEY, null));
        assertEquals("fedcba9876543210fedcba9876543210:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", device.getString(DEVICE_KEY, null));
        assertFalse(account.contains("app_pin_hash"));
        assertFalse(account.contains("app_pin_length_uid-a"));
        assertFalse(device.contains("device_gate_pin_length"));
        assertFalse(account.contains(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY)));
        assertFalse(device.contains(PinHashPersistence.DEVICE_PENDING_TX));
        assertTrue(PinHashPersistence.recoverPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                device, SecurePrefs.SecurityTier.HARDWARE, DEVICE_KEY));
    }

    @Test
    public void interruptedPairIsRolledForwardBeforeItCanBeUsed() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        String tx = "transaction-1";
        String accountHash = "00112233445566778899aabbccddeeff:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        String deviceHash = "ffeeddccbbaa99887766554433221100:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        account.edit().putString(ACCOUNT_KEY, "old-account")
                .putString(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY), tx)
                .putString(PinHashPersistence.accountPendingHashKey(ACCOUNT_KEY), accountHash)
                .commit();
        device.edit().putString(DEVICE_KEY, "old-device")
                .putString(PinHashPersistence.DEVICE_PENDING_TX, tx)
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_KEY, ACCOUNT_KEY)
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_HASH, accountHash)
                .putString(PinHashPersistence.DEVICE_PENDING_DEVICE_HASH, deviceHash)
                .commit();

        device.setCommitSucceeds(false);
        assertFalse(PinHashPersistence.recoverPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                device, SecurePrefs.SecurityTier.SOFTWARE, DEVICE_KEY));
        assertTrue("the recovery journal must remain for the next attempt",
                device.contains(PinHashPersistence.DEVICE_PENDING_TX));

        device.setCommitSucceeds(true);
        assertTrue(PinHashPersistence.recoverPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                device, SecurePrefs.SecurityTier.SOFTWARE, DEVICE_KEY));
        assertEquals(accountHash, account.getString(ACCOUNT_KEY, null));
        assertEquals(deviceHash, device.getString(DEVICE_KEY, null));
        assertFalse(device.contains(PinHashPersistence.DEVICE_PENDING_TX));
        assertFalse(account.contains(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY)));
    }

    @Test
    public void incompleteStageWithNoActiveChangeIsDiscardedWithoutChangingPins() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        account.edit().putString(ACCOUNT_KEY, "old-account").commit();
        device.edit().putString(DEVICE_KEY, "old-device")
                .putString(PinHashPersistence.DEVICE_PENDING_TX, "orphan")
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_KEY, ACCOUNT_KEY)
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_HASH, "00112233445566778899aabbccddeeff:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
                .putString(PinHashPersistence.DEVICE_PENDING_DEVICE_HASH, "ffeeddccbbaa99887766554433221100:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
                .commit();

        assertTrue(PinHashPersistence.recoverPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                device, SecurePrefs.SecurityTier.SOFTWARE, DEVICE_KEY));
        assertEquals("old-account", account.getString(ACCOUNT_KEY, null));
        assertEquals("old-device", device.getString(DEVICE_KEY, null));
        assertFalse(device.contains(PinHashPersistence.DEVICE_PENDING_TX));
    }

    @Test
    public void oneSidedActiveUpdateWithIncompleteJournalFailsClosed() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        account.edit().putString(ACCOUNT_KEY, "00112233445566778899aabbccddeeff:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc").commit();
        device.edit().putString(DEVICE_KEY, "old-device")
                .putString(PinHashPersistence.DEVICE_PENDING_TX, "orphan")
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_KEY, ACCOUNT_KEY)
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_HASH, "00112233445566778899aabbccddeeff:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
                .putString(PinHashPersistence.DEVICE_PENDING_DEVICE_HASH, "ffeeddccbbaa99887766554433221100:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
                .commit();

        assertFalse(PinHashPersistence.recoverPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                device, SecurePrefs.SecurityTier.SOFTWARE, DEVICE_KEY));
        assertTrue("ambiguous state must retain its journal",
                device.contains(PinHashPersistence.DEVICE_PENDING_TX));
    }
}
