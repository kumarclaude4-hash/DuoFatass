package com.duoshield.app.util;

import android.content.SharedPreferences;

import java.util.Map;
import java.util.UUID;

/**
 * Durable persistence primitives for primary and device PIN hashes.
 *
 * <p>A successful {@link SharedPreferences.Editor#commit()} is necessary but not
 * sufficient: the resolved SecurePrefs tier must be persistent and the values
 * must read back exactly. The paired writer uses a small journal in the two
 * existing isolated stores so a process death between the account and device
 * commits can be recovered without silently accepting a split credential.
 */
public final class PinHashPersistence {

    static final String DEVICE_PENDING_TX = "__pin_sync_pending_tx";
    static final String DEVICE_PENDING_ACCOUNT_KEY = "__pin_sync_pending_account_key";
    static final String DEVICE_PENDING_ACCOUNT_HASH = "__pin_sync_pending_account_hash";
    static final String DEVICE_PENDING_DEVICE_HASH = "__pin_sync_pending_device_hash";
    private static final String DEVICE_PENDING_ACCOUNT_LEGACY_KEY = "__pin_sync_pending_account_legacy_key";
    private static final String DEVICE_PENDING_ACCOUNT_LENGTH_KEY = "__pin_sync_pending_account_length_key";
    private static final String DEVICE_PENDING_DEVICE_LENGTH_KEY = "__pin_sync_pending_device_length_key";
    private static final String ACCOUNT_HASH_KEY_PREFIX = "app_pin_hash_";
    private static final String ACCOUNT_PENDING_TX_PREFIX = "__pin_sync_pending_tx_";
    private static final String ACCOUNT_PENDING_HASH_PREFIX = "__pin_sync_pending_hash_";

    private PinHashPersistence() {}

    /**
     * Writes one PIN hash synchronously and verifies the resulting state.
     * Removal keys are scrubbed in the same commit as the replacement.
     */
    public static synchronized boolean persistSingle(
            SharedPreferences preferences,
            SecurePrefs.SecurityTier tier,
            String hashKey,
            String storedHash,
            String... removalKeys) {
        if (preferences == null || tier == null || !tier.isDurable()
                || isBlank(hashKey) || !isValidHashRecord(storedHash)) {
            return false;
        }

        try {
            SharedPreferences.Editor editor = preferences.edit().putString(hashKey, storedHash);
            if (removalKeys != null) {
                for (String key : removalKeys) {
                    if (!isBlank(key) && !hashKey.equals(key)) editor.remove(key);
                }
            }
            if (!editor.commit()) return false;
            if (!storedHash.equals(preferences.getString(hashKey, null))) return false;
            if (removalKeys != null) {
                for (String key : removalKeys) {
                    if (!isBlank(key) && preferences.contains(key)) return false;
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Updates the account PIN and the already-configured device-gate PIN as one
     * recoverable operation. Each store receives an independently salted hash.
     *
     * <p>Both pending records must be durably staged before either active hash
     * changes. The account record is activated first, then the device record.
     * Pending records are retained until both active hashes have been committed
     * and read back. If the process dies in between, {@link #recoverPair} rolls
     * both stores forward to the same transaction.
     */
    public static synchronized boolean persistPair(
            SharedPreferences accountPreferences,
            SecurePrefs.SecurityTier accountTier,
            String accountHashKey,
            String accountStoredHash,
            String accountLegacyKey,
            String accountLengthKey,
            SharedPreferences devicePreferences,
            SecurePrefs.SecurityTier deviceTier,
            String deviceHashKey,
            String deviceStoredHash,
            String deviceLengthKey) {
        if (accountPreferences == null || devicePreferences == null
                || accountTier == null || !accountTier.isDurable()
                || deviceTier == null || !deviceTier.isDurable()
                || !isValidAccountHashKey(accountHashKey) || isBlank(deviceHashKey)
                || !isValidHashRecord(accountStoredHash)
                || !isValidHashRecord(deviceStoredHash)) {
            return false;
        }

        if (!recoverPair(accountPreferences, accountTier, devicePreferences,
                deviceTier, deviceHashKey)) {
            return false;
        }

        String transactionId = UUID.randomUUID().toString();
        String accountPendingTx = accountPendingTxKey(accountHashKey);
        String accountPendingHash = accountPendingHashKey(accountHashKey);

        try {
            boolean accountStaged = accountPreferences.edit()
                    .putString(accountPendingTx, transactionId)
                    .putString(accountPendingHash, accountStoredHash)
                    .commit();
            if (!accountStaged
                    || !transactionId.equals(accountPreferences.getString(accountPendingTx, null))
                    || !accountStoredHash.equals(accountPreferences.getString(accountPendingHash, null))) {
                clearAccountStage(accountPreferences, accountHashKey);
                return false;
            }

            boolean deviceStaged = devicePreferences.edit()
                    .putString(DEVICE_PENDING_TX, transactionId)
                    .putString(DEVICE_PENDING_ACCOUNT_KEY, accountHashKey)
                    .putString(DEVICE_PENDING_ACCOUNT_HASH, accountStoredHash)
                    .putString(DEVICE_PENDING_DEVICE_HASH, deviceStoredHash)
                    .putString(DEVICE_PENDING_ACCOUNT_LEGACY_KEY, accountLegacyKey)
                    .putString(DEVICE_PENDING_ACCOUNT_LENGTH_KEY, accountLengthKey)
                    .putString(DEVICE_PENDING_DEVICE_LENGTH_KEY, deviceLengthKey)
                    .commit();
            if (!deviceStaged
                    || !transactionId.equals(devicePreferences.getString(DEVICE_PENDING_TX, null))
                    || !accountHashKey.equals(devicePreferences.getString(DEVICE_PENDING_ACCOUNT_KEY, null))
                    || !accountStoredHash.equals(devicePreferences.getString(DEVICE_PENDING_ACCOUNT_HASH, null))
                    || !deviceStoredHash.equals(devicePreferences.getString(DEVICE_PENDING_DEVICE_HASH, null))
                    || !sameNullable(accountLegacyKey, devicePreferences.getString(DEVICE_PENDING_ACCOUNT_LEGACY_KEY, null))
                    || !sameNullable(accountLengthKey, devicePreferences.getString(DEVICE_PENDING_ACCOUNT_LENGTH_KEY, null))
                    || !sameNullable(deviceLengthKey, devicePreferences.getString(DEVICE_PENDING_DEVICE_LENGTH_KEY, null))) {
                // No active key has been changed yet. Clear the incomplete stage
                // best-effort; a surviving device journal is safely discarded by recovery.
                clearAccountStage(accountPreferences, accountHashKey);
                clearDeviceStage(devicePreferences);
                return false;
            }

            return recoverPair(accountPreferences, accountTier, devicePreferences,
                    deviceTier, deviceHashKey, accountLegacyKey, accountLengthKey, deviceLengthKey);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Completes or safely discards an interrupted paired update.
     *
     * <p>When a valid journal exists, both active hashes are written and verified
     * before the journal is removed. A lone/incomplete stage is discarded only
     * when neither active key has advanced; a one-sided active update fails
     * closed and keeps the journal for a later recovery attempt.
     */
    public static synchronized boolean recoverPair(
            SharedPreferences accountPreferences,
            SecurePrefs.SecurityTier accountTier,
            SharedPreferences devicePreferences,
            SecurePrefs.SecurityTier deviceTier,
            String deviceHashKey) {
        return recoverPair(accountPreferences, accountTier, devicePreferences,
                deviceTier, deviceHashKey, null, null, null);
    }

    private static boolean recoverPair(
            SharedPreferences accountPreferences,
            SecurePrefs.SecurityTier accountTier,
            SharedPreferences devicePreferences,
            SecurePrefs.SecurityTier deviceTier,
            String deviceHashKey,
            String accountLegacyKey,
            String accountLengthKey,
            String deviceLengthKey) {
        if (accountPreferences == null || devicePreferences == null
                || accountTier == null || deviceTier == null || isBlank(deviceHashKey)) {
            return false;
        }

        try {
            String transactionId = devicePreferences.getString(DEVICE_PENDING_TX, null);
            if (transactionId == null) {
                if (!hasAccountStages(accountPreferences)) return true;
                return accountTier.isDurable() && clearOrphanAccountStages(accountPreferences);
            }
            if (!accountTier.isDurable() || !deviceTier.isDurable()) return false;

            String accountHashKey = devicePreferences.getString(DEVICE_PENDING_ACCOUNT_KEY, null);
            String accountStoredHash = devicePreferences.getString(DEVICE_PENDING_ACCOUNT_HASH, null);
            String deviceStoredHash = devicePreferences.getString(DEVICE_PENDING_DEVICE_HASH, null);
            String journalAccountLegacyKey = devicePreferences.getString(DEVICE_PENDING_ACCOUNT_LEGACY_KEY, null);
            String journalAccountLengthKey = devicePreferences.getString(DEVICE_PENDING_ACCOUNT_LENGTH_KEY, null);
            String journalDeviceLengthKey = devicePreferences.getString(DEVICE_PENDING_DEVICE_LENGTH_KEY, null);
            String effectiveAccountLegacyKey = accountLegacyKey != null
                    ? accountLegacyKey : journalAccountLegacyKey;
            String effectiveAccountLengthKey = accountLengthKey != null
                    ? accountLengthKey : journalAccountLengthKey;
            String effectiveDeviceLengthKey = deviceLengthKey != null
                    ? deviceLengthKey : journalDeviceLengthKey;
            if (!isValidAccountHashKey(accountHashKey) || !isValidHashRecord(accountStoredHash)
                    || !isValidHashRecord(deviceStoredHash)) {
                return false;
            }

            String pendingTxKey = accountPendingTxKey(accountHashKey);
            String pendingHashKey = accountPendingHashKey(accountHashKey);
            String stagedTx = accountPreferences.getString(pendingTxKey, null);
            String stagedHash = accountPreferences.getString(pendingHashKey, null);
            boolean completeStage = transactionId.equals(stagedTx)
                    && accountStoredHash.equals(stagedHash);

            if (!completeStage) {
                boolean accountAlreadyAdvanced = accountStoredHash.equals(
                        accountPreferences.getString(accountHashKey, null));
                boolean deviceAlreadyAdvanced = deviceStoredHash.equals(
                        devicePreferences.getString(deviceHashKey, null));

                if (accountAlreadyAdvanced && deviceAlreadyAdvanced) {
                    clearCompletedJournal(accountPreferences, accountHashKey, devicePreferences);
                    return true;
                }
                if (!accountAlreadyAdvanced && !deviceAlreadyAdvanced) {
                    clearAccountStage(accountPreferences, accountHashKey);
                    clearDeviceStage(devicePreferences);
                    return !accountPreferences.contains(pendingTxKey)
                            && !accountPreferences.contains(pendingHashKey)
                            && !devicePreferences.contains(DEVICE_PENDING_TX);
                }
                // A one-sided active value with an incomplete journal is ambiguous.
                return false;
            }

            SharedPreferences.Editor accountEditor = accountPreferences.edit()
                    .putString(accountHashKey, accountStoredHash);
            if (!isBlank(effectiveAccountLegacyKey)) accountEditor.remove(effectiveAccountLegacyKey);
            if (!isBlank(effectiveAccountLengthKey)) accountEditor.remove(effectiveAccountLengthKey);
            boolean accountCommitted = accountEditor.commit();

            SharedPreferences.Editor deviceEditor = devicePreferences.edit()
                    .putString(deviceHashKey, deviceStoredHash);
            if (!isBlank(effectiveDeviceLengthKey)) deviceEditor.remove(effectiveDeviceLengthKey);
            boolean deviceCommitted = deviceEditor.commit();

            if (!accountCommitted || !deviceCommitted) return false;
            if (!accountStoredHash.equals(accountPreferences.getString(accountHashKey, null))
                    || !deviceStoredHash.equals(devicePreferences.getString(deviceHashKey, null))
                    || containsIfPresent(accountPreferences, effectiveAccountLegacyKey)
                    || containsIfPresent(accountPreferences, effectiveAccountLengthKey)
                    || containsIfPresent(devicePreferences, effectiveDeviceLengthKey)) {
                return false;
            }

            clearCompletedJournal(accountPreferences, accountHashKey, devicePreferences);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void clearCompletedJournal(
            SharedPreferences accountPreferences,
            String accountHashKey,
            SharedPreferences devicePreferences) {
        // The account stage is removed first. Thus a surviving device journal
        // always contains enough information to prove both active writes completed.
        clearAccountStage(accountPreferences, accountHashKey);
        if (!accountPreferences.contains(accountPendingTxKey(accountHashKey))
                && !accountPreferences.contains(accountPendingHashKey(accountHashKey))) {
            clearDeviceStage(devicePreferences);
        }
    }

    private static void clearAccountStage(SharedPreferences preferences, String accountHashKey) {
        try {
            preferences.edit()
                    .remove(accountPendingTxKey(accountHashKey))
                    .remove(accountPendingHashKey(accountHashKey))
                    .commit();
        } catch (RuntimeException ignored) {
            // Keeping the stage is safe; recovery is idempotent.
        }
    }

    private static void clearDeviceStage(SharedPreferences preferences) {
        try {
            preferences.edit()
                    .remove(DEVICE_PENDING_TX)
                    .remove(DEVICE_PENDING_ACCOUNT_KEY)
                    .remove(DEVICE_PENDING_ACCOUNT_HASH)
                    .remove(DEVICE_PENDING_DEVICE_HASH)
                    .remove(DEVICE_PENDING_ACCOUNT_LEGACY_KEY)
                    .remove(DEVICE_PENDING_ACCOUNT_LENGTH_KEY)
                    .remove(DEVICE_PENDING_DEVICE_LENGTH_KEY)
                    .commit();
        } catch (RuntimeException ignored) {
            // Keeping the journal is safe; the next PIN access retries recovery.
        }
    }

    private static boolean hasAccountStages(SharedPreferences preferences) {
        try {
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(ACCOUNT_PENDING_TX_PREFIX)
                        || key.startsWith(ACCOUNT_PENDING_HASH_PREFIX)) return true;
            }
            return false;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean clearOrphanAccountStages(SharedPreferences preferences) {
        try {
            Map<String, ?> all = preferences.getAll();
            SharedPreferences.Editor editor = preferences.edit();
            boolean found = false;
            for (String key : all.keySet()) {
                if (key.startsWith(ACCOUNT_PENDING_TX_PREFIX)
                        || key.startsWith(ACCOUNT_PENDING_HASH_PREFIX)) {
                    editor.remove(key);
                    found = true;
                }
            }
            if (!found) return true;
            if (!editor.commit()) return false;
            Map<String, ?> remaining = preferences.getAll();
            for (String key : remaining.keySet()) {
                if (key.startsWith(ACCOUNT_PENDING_TX_PREFIX)
                        || key.startsWith(ACCOUNT_PENDING_HASH_PREFIX)) return false;
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isValidAccountHashKey(String key) {
        return key != null && key.startsWith(ACCOUNT_HASH_KEY_PREFIX)
                && key.length() > ACCOUNT_HASH_KEY_PREFIX.length();
    }

    /** salt is 16 bytes (32 hex chars); PBKDF2 output is 32 bytes (64 hex chars). */
    private static boolean isValidHashRecord(String value) {
        if (value == null) return false;
        int separator = value.indexOf(':');
        if (separator != 32 || value.length() != 97) return false;
        for (int i = 0; i < value.length(); i++) {
            if (i == separator) continue;
            char ch = value.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9')
                    || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static boolean sameNullable(String expected, String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static boolean containsIfPresent(SharedPreferences preferences, String key) {
        return !isBlank(key) && preferences.contains(key);
    }

    static String accountPendingTxKey(String accountHashKey) {
        return ACCOUNT_PENDING_TX_PREFIX + accountHashKey;
    }

    static String accountPendingHashKey(String accountHashKey) {
        return ACCOUNT_PENDING_HASH_PREFIX + accountHashKey;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
