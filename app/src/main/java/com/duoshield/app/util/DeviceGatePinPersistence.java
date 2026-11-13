package com.duoshield.app.util;

import android.content.SharedPreferences;

/**
 * Fail-closed persistence contract for the separate device-gate PIN store.
 *
 * <p>Unlike an ordinary SharedPreferences write, a successful call here means
 * the resolved store was durable, the synchronous commit succeeded, the
 * stored hash reads back exactly, and legacy plaintext-length metadata is
 * absent. The NONE tier is an in-memory-only fallback and must never allow
 * device onboarding to proceed.
 */
final class DeviceGatePinPersistence {

    private DeviceGatePinPersistence() {}

    static boolean persist(SharedPreferences preferences,
                           SecurePrefs.SecurityTier storageTier,
                           String hashKey,
                           String legacyLengthKey,
                           String encodedHash) {
        if (preferences == null || storageTier == null || !storageTier.isDurable()
                || hashKey == null || hashKey.isEmpty()
                || legacyLengthKey == null || legacyLengthKey.isEmpty()
                || encodedHash == null || encodedHash.isEmpty()) {
            return false;
        }

        try {
            boolean committed = preferences.edit()
                    .putString(hashKey, encodedHash)
                    .remove(legacyLengthKey)
                    .commit();
            if (!committed) return false;

            return encodedHash.equals(preferences.getString(hashKey, null))
                    && !preferences.contains(legacyLengthKey);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
