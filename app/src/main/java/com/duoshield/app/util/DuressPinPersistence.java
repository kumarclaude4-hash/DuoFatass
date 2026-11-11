package com.duoshield.app.util;

import android.content.SharedPreferences;

/**
 * Persists the secondary-code slot as one synchronous, verified preference update.
 *
 * <p>The caller must separately verify that the resolved SecurePrefs tier is durable.
 * A successful in-memory commit on the ephemeral tier is not a persistence guarantee.
 */
public final class DuressPinPersistence {

    private DuressPinPersistence() {}

    /**
     * Writes the hash, armed bit and legacy-key removal together, then reads them back.
     *
     * @return true only when commit succeeds and every resulting value matches.
     */
    public static boolean persist(
            SharedPreferences preferences,
            String slotKey,
            String armedKey,
            String legacyKey,
            String storedHash) {
        if (preferences == null || isBlank(slotKey) || isBlank(armedKey)
                || isBlank(legacyKey) || storedHash == null || storedHash.isEmpty()) {
            return false;
        }

        try {
            boolean committed = preferences.edit()
                    .putString(slotKey, storedHash)
                    .putBoolean(armedKey, true)
                    .remove(legacyKey)
                    .commit();
            if (!committed) return false;

            return storedHash.equals(preferences.getString(slotKey, null))
                    && preferences.getBoolean(armedKey, false)
                    && !preferences.contains(legacyKey);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
