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
                .putString("app_pin_hash", "old-legacy-account")
                .putInt("app_pin_length_uid-a", 6)
                .putString(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY), tx)
                .putString(PinHashPersistence.accountPendingHashKey(ACCOUNT_KEY), accountHash)
                .commit();
        device.edit().putString(DEVICE_KEY, "old-device")
                .putInt("device_gate_pin_length", 6)
                .putString(PinHashPersistence.DEVICE_PENDING_TX, tx)
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_KEY, ACCOUNT_KEY)
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_HASH, accountHash)
                .putString(PinHashPersistence.DEVICE_PENDING_DEVICE_HASH, deviceHash)
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_LEGACY_KEY, "app_pin_hash")
                .putString(PinHashPersistence.DEVICE_PENDING_ACCOUNT_LENGTH_KEY, "app_pin_length_uid-a")
                .putString(PinHashPersistence.DEVICE_PENDING_DEVICE_LENGTH_KEY, "device_gate_pin_length")
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
        assertFalse("legacy account hash must be scrubbed during crash recovery",
                account.contains("app_pin_hash"));
        assertFalse("plaintext account PIN length must be scrubbed during crash recovery",
                account.contains("app_pin_length_uid-a"));
        assertFalse("plaintext device PIN length must be scrubbed during crash recovery",
                device.contains("device_gate_pin_length"));
        assertFalse(device.contains(PinHashPersistence.DEVICE_PENDING_TX));
        assertFalse(account.contains(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY)));
    }

    @Test
    public void pairedWriteRejectsEphemeralOrMalformedCredentialRecords() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        String validAccount = "0123456789abcdef0123456789abcdef:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String validDevice = "fedcba9876543210fedcba9876543210:"
                + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        assertFalse(PinHashPersistence.persistPair(
                account, SecurePrefs.SecurityTier.NONE,
                ACCOUNT_KEY, validAccount, "app_pin_hash", "pin_length",
                device, SecurePrefs.SecurityTier.HARDWARE,
                DEVICE_KEY, validDevice, "device_gate_pin_length"));
        assertFalse(PinHashPersistence.persistPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                ACCOUNT_KEY, "malformed", "app_pin_hash", "pin_length",
                device, SecurePrefs.SecurityTier.HARDWARE,
                DEVICE_KEY, validDevice, "device_gate_pin_length"));
        assertTrue("rejected writes must not stage or mutate either active PIN",
                account.getAll().isEmpty() && device.getAll().isEmpty());
    }

    @Test
    public void failedDeviceJournalCommitDoesNotActivateEitherNewCredential() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        String oldAccount = "00112233445566778899aabbccddeeff:"
                + "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        String oldDevice = "ffeeddccbbaa99887766554433221100:"
                + "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        String newAccount = "0123456789abcdef0123456789abcdef:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String newDevice = "fedcba9876543210fedcba9876543210:"
                + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        account.edit().putString(ACCOUNT_KEY, oldAccount).commit();
        device.edit().putString(DEVICE_KEY, oldDevice).commit();
        device.setCommitSucceeds(false);

        assertFalse(PinHashPersistence.persistPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                ACCOUNT_KEY, newAccount, "app_pin_hash", "pin_length",
                device, SecurePrefs.SecurityTier.SOFTWARE,
                DEVICE_KEY, newDevice, "device_gate_pin_length"));
        assertEquals(oldAccount, account.getString(ACCOUNT_KEY, null));
        assertEquals(oldDevice, device.getString(DEVICE_KEY, null));
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

    @Test
    public void orphanAccountStageWithAdvancedActiveHashFailsClosed() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        String advancedHash = "0123456789abcdef0123456789abcdef:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        account.edit()
                .putString(ACCOUNT_KEY, advancedHash)
                .putString(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY), "transaction-lost-device-journal")
                .putString(PinHashPersistence.accountPendingHashKey(ACCOUNT_KEY), advancedHash)
                .commit();

        assertFalse("an active hash matching an orphan stage may be a one-sided update",
                PinHashPersistence.recoverPair(
                        account, SecurePrefs.SecurityTier.SOFTWARE,
                        device, SecurePrefs.SecurityTier.SOFTWARE, DEVICE_KEY));
        assertEquals(advancedHash, account.getString(ACCOUNT_KEY, null));
        assertTrue("the account stage must remain available for diagnosis/recovery",
                account.contains(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY)));
        assertTrue("the staged hash must remain available for diagnosis/recovery",
                account.contains(PinHashPersistence.accountPendingHashKey(ACCOUNT_KEY)));
    }

    @Test
    public void orphanAccountStageBeforeActivationCanBeSafelyDiscarded() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        String oldHash = "00112233445566778899aabbccddeeff:"
                + "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        String stagedHash = "0123456789abcdef0123456789abcdef:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        account.edit()
                .putString(ACCOUNT_KEY, oldHash)
                .putString(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY), "transaction-before-device-stage")
                .putString(PinHashPersistence.accountPendingHashKey(ACCOUNT_KEY), stagedHash)
                .commit();

        assertTrue(PinHashPersistence.recoverPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                device, SecurePrefs.SecurityTier.SOFTWARE, DEVICE_KEY));
        assertEquals("pre-transaction account PIN must remain active",
                oldHash, account.getString(ACCOUNT_KEY, null));
        assertFalse(account.contains(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY)));
        assertFalse(account.contains(PinHashPersistence.accountPendingHashKey(ACCOUNT_KEY)));
    }

    @Test
    public void malformedOrphanAccountStageFailsClosed() {
        FakeSharedPreferences account = new FakeSharedPreferences();
        FakeSharedPreferences device = new FakeSharedPreferences();
        account.edit()
                .putString(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY), "incomplete")
                .commit();

        assertFalse(PinHashPersistence.recoverPair(
                account, SecurePrefs.SecurityTier.SOFTWARE,
                device, SecurePrefs.SecurityTier.SOFTWARE, DEVICE_KEY));
        assertTrue(account.contains(PinHashPersistence.accountPendingTxKey(ACCOUNT_KEY)));
    }
}
