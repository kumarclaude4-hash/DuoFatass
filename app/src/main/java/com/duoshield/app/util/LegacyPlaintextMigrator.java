package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One-time rescue of secrets written by the old plaintext {@code MODE_PRIVATE}
 * fallback in {@link SecurePrefs} (S08-H5).
 *
 * <h3>Why a migrator is required rather than just deleting the fallback</h3>
 * Removing the plaintext fallback is the fix, but it cannot be done in
 * isolation. Installs that already ran on a device where all Keystore tiers
 * failed have their real secrets sitting in a plaintext XML file — including
 * the SQLCipher passphrase. If the new build simply stopped looking there,
 * {@code DatabaseKeyProvider} would find no key, see an existing database, and
 * throw {@code KeyUnavailableException} forever: the user's entire message
 * history would become permanently unreadable. So the fallback's removal and
 * this migration have to ship together.
 *
 * <h3>The file-sharing problem this has to work around</h3>
 * The old fallback used the <em>same file name</em> as
 * {@code EncryptedSharedPreferences} (ESP), so a device that failed init once
 * and succeeded later has both plaintext entries and ESP entries interleaved in
 * one XML file. ESP cannot see the plaintext ones: it encrypts key names before
 * looking them up, so {@code getString("db_cipher_key_v1")} searches for a
 * Base64 SIV blob and misses the literal key entirely. The two sets therefore
 * have to be told apart by inspecting the raw file.
 *
 * <p>The discriminator is {@link #looksLikeEspEntry}: ESP key names are Base64
 * of a 16-byte-or-larger SIV output, so anything that is not decodable Base64 of
 * sufficient length is a legacy plaintext key. This is a heuristic, and it is
 * worth being precise about which way it can fail:
 *
 * <ul>
 *   <li><b>A plaintext key mistaken for an ESP key</b> (possible only if an
 *       app-chosen key name happens to be valid Base64 of ≥16 bytes) would be
 *       skipped, leaving that one secret unmigrated. No data is destroyed —
 *       the plaintext file is only cleared after a verified read-back, and only
 *       for entries that were actually migrated.</li>
 *   <li><b>An ESP key mistaken for a plaintext key</b> would copy a ciphertext
 *       string into the encrypted store under a junk name. Harmless clutter; it
 *       shadows nothing, because real lookups go through encrypted key names.</li>
 * </ul>
 *
 * Neither direction can lose data, which is the property that matters here.
 */
final class LegacyPlaintextMigrator {

    private static final String TAG = "LegacyPrefsMigration";

    /**
     * ESP's own Tink keyset entries. These are stored under <em>literal</em>
     * (unencrypted) names, so they would otherwise be misread as legacy
     * plaintext secrets and pointlessly copied around.
     */
    private static final String ESP_BOOKKEEPING_PREFIX = "__androidx_security_crypto";

    /** Minimum decoded length of an ESP-encrypted key name (AES-SIV output). */
    private static final int ESP_MIN_DECODED_BYTES = 16;

    private LegacyPlaintextMigrator() {}

    /** Outcome of a migration attempt, for logging and gate decisions. */
    static final class Result {
        /** Number of legacy plaintext entries found in the raw file. */
        final int found;
        /** Number successfully copied into the encrypted store and verified. */
        final int migrated;
        /** True when legacy plaintext secrets remain on disk after this ran. */
        final boolean plaintextRemains;

        Result(int found, int migrated, boolean plaintextRemains) {
            this.found            = found;
            this.migrated         = migrated;
            this.plaintextRemains = plaintextRemains;
        }

        static Result none() { return new Result(0, 0, false); }
    }

    /**
     * Migrates legacy plaintext entries for {@code fileName} into {@code target}.
     *
     * @param canPersist whether {@code target} is a durable encrypted store. When
     *                   false (the ephemeral tier), values are still loaded into
     *                   memory so the current session keeps working, but the
     *                   plaintext file is <strong>not</strong> cleared — see the
     *                   inline comment for why deleting it would destroy data.
     */
    static Result migrate(Context appCtx, String fileName, SharedPreferences target,
                          boolean canPersist) {
        SharedPreferences raw;
        try {
            raw = appCtx.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        } catch (Exception e) {
            Log.w(TAG, "Cannot open raw prefs for " + fileName + ": "
                    + e.getClass().getSimpleName());
            return Result.none();
        }

        Map<String, Object> legacy = collectLegacyEntries(raw);
        if (legacy.isEmpty()) return Result.none();

        Log.w(TAG, "Found " + legacy.size() + " legacy plaintext entr"
                + (legacy.size() == 1 ? "y" : "ies") + " in " + fileName
                + " — migrating (canPersist=" + canPersist + ").");

        int migrated = copyInto(target, legacy);

        if (!canPersist) {
            // Ephemeral tier. The in-memory copy above keeps this session working,
            // but we must NOT clear the plaintext file: on the next process start
            // the memory is gone, and for an install whose database was encrypted
            // with a random (not PIN-derived) passphrase there is nothing to
            // re-derive it from. Deleting here would turn a degraded-but-working
            // install into permanent, total history loss.
            //
            // The plaintext therefore survives until the PIN-wrapped store can
            // take ownership of it (see PinKeyGate); DeviceSecurityGate reports
            // this state so onboarding/restore stays blocked in the meantime.
            Log.w(TAG, "Ephemeral tier: legacy plaintext retained on disk to avoid"
                    + " unrecoverable data loss. Onboarding/restore stays blocked.");
            return new Result(legacy.size(), migrated, true);
        }

        if (migrated != legacy.size()) {
            // Partial migration: leave everything in place rather than clearing a
            // file we only half-copied.
            Log.e(TAG, "Migrated only " + migrated + " of " + legacy.size()
                    + " entries for " + fileName + " — retaining plaintext for retry.");
            return new Result(legacy.size(), migrated, true);
        }

        boolean cleared = clearLegacyEntries(raw, legacy.keySet());
        return new Result(legacy.size(), migrated, !cleared);
    }

    /** Extracts the entries that were written by the old plaintext fallback. */
    private static Map<String, Object> collectLegacyEntries(SharedPreferences raw) {
        Map<String, Object> legacy = new LinkedHashMap<>();
        Map<String, ?> all;
        try {
            all = raw.getAll();
        } catch (Exception e) {
            Log.w(TAG, "Cannot enumerate raw prefs: " + e.getClass().getSimpleName());
            return legacy;
        }
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (key == null || key.startsWith(ESP_BOOKKEEPING_PREFIX)) continue;
            if (looksLikeEspEntry(key)) continue;
            if (e.getValue() != null) legacy.put(key, e.getValue());
        }
        return legacy;
    }

    /**
     * True when {@code key} has the shape of an ESP-encrypted key name. See the
     * class javadoc for the failure analysis of this heuristic.
     */
    private static boolean looksLikeEspEntry(String key) {
        if (key.length() < 4 || key.length() % 4 != 0) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean valid = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!valid) return false;
        }
        try {
            return Base64.decode(key, Base64.NO_WRAP).length >= ESP_MIN_DECODED_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Copies entries into {@code target}, skipping keys the target already has so
     * a re-run cannot clobber a newer value, and verifying each write.
     *
     * @return the number of entries that are present and correct in the target.
     */
    @SuppressWarnings("unchecked")
    private static int copyInto(SharedPreferences target, Map<String, Object> legacy) {
        int ok = 0;
        for (Map.Entry<String, Object> e : legacy.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            try {
                if (matchesTargetValue(target, key, val)) {
                    // A previous migration or a newer write already contains the
                    // exact value. Presence alone is not enough: clearing the raw
                    // source after a key/type collision would destroy the only
                    // recoverable copy of a secret.
                    ok++;
                    continue;
                }
                if (target.contains(key)) {
                    Log.e(TAG, "Existing encrypted value did not match legacy entry; retaining source.");
                    continue;
                }
                SharedPreferences.Editor ed = target.edit();
                if (val instanceof String)       ed.putString(key, (String) val);
                else if (val instanceof Set)     ed.putStringSet(key, (Set<String>) val);
                else if (val instanceof Integer) ed.putInt(key, (Integer) val);
                else if (val instanceof Long)    ed.putLong(key, (Long) val);
                else if (val instanceof Float)   ed.putFloat(key, (Float) val);
                else if (val instanceof Boolean) ed.putBoolean(key, (Boolean) val);
                else {
                    Log.w(TAG, "Skipping unsupported type for key: " + val.getClass().getName());
                    continue;
                }
                // commit(), not apply(): the plaintext source is cleared only after
                // this returns, so the copy has to be on disk first.
                if (!ed.commit()) {
                    Log.e(TAG, "commit() returned false while migrating an entry.");
                    continue;
                }
                if (matchesTargetValue(target, key, val)) ok++;
                else Log.e(TAG, "Migrated entry failed value read-back verification.");
            } catch (Exception ex) {
                Log.e(TAG, "Failed to migrate an entry: " + ex.getClass().getSimpleName());
            }
        }
        return ok;
    }

    /**
     * Verifies the actual stored value and type, not merely key presence.
     * SharedPreferences.getAll() returns decoded values for both ESP and the
     * in-memory test implementation; Set equality is value-based.
     */
    private static boolean matchesTargetValue(SharedPreferences target, String key,
                                              Object expected) {
        try {
            Map<String, ?> values = target.getAll();
            if (values == null || !values.containsKey(key)) return false;
            Object actual = values.get(key);
            if (actual == null || expected == null || actual.getClass() != expected.getClass()) {
                return false;
            }
            return expected.equals(actual);
        } catch (Exception e) {
            Log.e(TAG, "Unable to verify migrated value for key ("
                    + e.getClass().getSimpleName() + ").");
            return false;
        }
    }

    /** Removes the migrated plaintext entries from the raw file. */
    private static boolean clearLegacyEntries(SharedPreferences raw, Set<String> keys) {
        try {
            SharedPreferences.Editor ed = raw.edit();
            for (String key : keys) ed.remove(key);
            boolean cleared = ed.commit();
            if (cleared) {
                Log.i(TAG, "Cleared " + keys.size()
                        + " legacy plaintext entries after verified migration.");
            } else {
                Log.e(TAG, "Failed to clear legacy plaintext entries (commit false).");
            }
            return cleared;
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear legacy plaintext entries: "
                    + e.getClass().getSimpleName());
            return false;
        }
    }
}
