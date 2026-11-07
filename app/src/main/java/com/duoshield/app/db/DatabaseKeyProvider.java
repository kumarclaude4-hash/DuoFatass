package com.duoshield.app.db;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.duoshield.app.security.PinKeyGate;
import com.duoshield.app.security.SessionKeyHolder;
import com.duoshield.app.util.SecurePrefs;

import java.io.File;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Manages the SQLCipher full-database encryption passphrase (BUG-D01).
 *
 * <p>A random 32-byte key is generated on first launch and stored in
 * EncryptedSharedPreferences so it survives app restarts but is protected
 * by the Android Keystore.
 *
 * <h3>Durability contract (SEC-D02)</h3>
 * The passphrase is the <em>only</em> thing standing between the user and
 * total loss of their message history: {@link AppDatabase} deletes and
 * recreates the database whenever SQLCipher cannot open it. Two failure
 * modes previously caused silent, unrecoverable data loss:
 *
 * <ol>
 *   <li><b>Non-durable write.</b> The key was persisted with
 *       {@code apply()}, which returns before the value reaches disk. If the
 *       process was killed between "database created" and "key flushed",
 *       the next launch found no key, minted a different one, failed to
 *       open the database, and wiped it. Now persisted with
 *       {@code commit()} and read back before the key is returned, so the
 *       key is guaranteed on disk before any database file exists.</li>
 *   <li><b>Temporarily unreadable key.</b> {@link SecurePrefs}'s tier-3
 *       recovery path deletes the master-key alias, which makes previously
 *       stored ciphertext unreadable. (It used to also fall back to a
 *       plaintext store; that tier no longer exists — see
 *       {@link SecurePrefs.SecurityTier}.) The old code treated "cannot read key" as
 *       "first run" and minted a fresh key, which guaranteed the database
 *       got destroyed even though the data was still intact and would have
 *       been readable on a later boot when Keystore recovered. Now we only
 *       mint a new key when there is no database to lose; otherwise we
 *       raise {@link KeyUnavailableException} so the caller can retry
 *       instead of destroying data.</li>
 * </ol>
 *
 * <p>Callers must zero the returned array after passing it to
 * {@link net.sqlcipher.database.SupportFactory}.
 */
public final class DatabaseKeyProvider {

    private static final String TAG           = "DatabaseKeyProvider";
    private static final String KEY_DB_CIPHER = "db_cipher_key_v1";
    private static final int    KEY_BYTES     = 32;

    /** Name must stay in sync with {@link AppDatabase}'s Room database name. */
    static final String DB_NAME = "duoshield_db";

    private DatabaseKeyProvider() {}

    /**
     * Thrown when an encrypted database exists but its passphrase cannot be
     * read right now. This is a <em>recoverable</em> condition — the caller
     * must NOT delete the database, because the key may become readable
     * again once the Android Keystore recovers.
     */
    public static class KeyUnavailableException extends IllegalStateException {
        public KeyUnavailableException(String message) { super(message); }
    }

    /**
     * Thrown when the database key is PIN-wrapped ({@link PinKeyGate}) and the
     * session is locked, so no key is available without the user's PIN.
     *
     * <p>A subclass of {@link KeyUnavailableException} so every existing caller
     * that already treats that as "recoverable, do not delete anything" keeps
     * doing the right thing without modification — which matters given 52
     * {@code AppDatabase.getInstance()} call sites. Callers that can prompt for
     * a PIN should catch this specifically and do so; callers that cannot (e.g.
     * {@code SelfDestructWorker}) should defer and retry.
     */
    public static class DatabaseLockedException extends KeyUnavailableException {
        public DatabaseLockedException(String message) { super(message); }
    }

    public static byte[] getOrCreate(Context ctx) {
        Context appCtx = ctx.getApplicationContext();
        SharedPreferences prefs = SecurePrefs.get(appCtx);

        // S08-M3: once the PIN gate is enrolled, the unwrapped key exists only in
        // SessionKeyHolder, put there by the unlock screen. Check it before any
        // at-rest lookup — this is the path that makes PIN-binding work for the
        // background callers that have no PIN to offer.
        byte[] session = SessionKeyHolder.getKey();
        if (session != null) return session;

        if (PinKeyGate.isEnrolled(appCtx)) {
            // Enrolled but locked. There is deliberately no fallback to a
            // directly-stored key here: a fallback would be exactly the unlocked
            // door the PIN gate exists to close, and would make the whole item
            // decorative.
            throw new DatabaseLockedException(
                    "The database key is PIN-wrapped and this session is locked."
                            + " Prompt for the PIN (PinKeyGate.unlockSession) before"
                            + " opening the database.");
        }

        byte[] existing = readKey(prefs);
        if (existing != null) return existing;

        // No usable key. Decide whether this is a genuine first run or a lost key.
        if (databaseExists(appCtx)) {
            // A database exists but we cannot read its passphrase. Minting a new
            // key here would cause AppDatabase to delete every message the user
            // has. Refuse, and let the caller decide (retry / surface an error).
            throw new KeyUnavailableException(
                    "Encrypted database exists but its passphrase could not be read"
                            + " (tier=" + SecurePrefs.getTier() + ")."
                            + " Refusing to mint a replacement key, which would destroy"
                            + " the existing database.");
        }

        // Genuine first run. Refuse to mint a key we know cannot be persisted:
        // on SecurityTier.NONE the store is in-memory only, so the key would be
        // lost at process death, and the very next launch would hit the branch
        // above — a database that exists with an unreadable passphrase, i.e. a
        // permanently unopenable database. Failing here instead means no database
        // is ever created on such a device, so there is nothing to lose.
        //
        // DeviceSecurityGate is what keeps users from reaching this point; this
        // check is the backstop for any entry point that forgets to consult it.
        if (!SecurePrefs.getTier().isDurable()) {
            throw new KeyUnavailableException(
                    "Refusing to create a database key on a device with no working"
                            + " Keystore tier (tier=" + SecurePrefs.getTier() + ")."
                            + " The key could not be persisted, so the database would be"
                            + " unopenable after process death. See DeviceSecurityGate.");
        }

        return createAndPersist(prefs);
    }

    /**
     * Folds an existing directly-stored database key into the PIN gate (S08-M3).
     *
     * <p>Call this immediately after a successful PIN entry on an install that
     * predates the gate, and after {@code setPin()} on a fresh install. It wraps
     * the current key under the PIN, verifies the wrap round-trips, and only then
     * removes the unwrapped copy — so an interruption at any point leaves the
     * user with a key they can still open their database with.
     *
     * <p>Ordering is the entire safety argument here. Removing the plaintext key
     * before verifying the wrap would, on any device where the wrap silently
     * fails, destroy every message the user has. {@link PinKeyGate#enroll}
     * performs its own full unwrap round-trip and throws rather than returning
     * on failure, so reaching the removal below means the wrap is proven good.
     *
     * @return true if the key is now PIN-wrapped (including when it already was).
     */
    public static boolean enrollInPinGate(Context ctx, String pin) {
        Context appCtx = ctx.getApplicationContext();
        if (PinKeyGate.isEnrolled(appCtx)) return true;

        SharedPreferences prefs = SecurePrefs.get(appCtx);
        byte[] existing = readKey(prefs);
        if (existing == null) {
            // Nothing to migrate. Not an error: a brand-new install enrolls when
            // its first key is minted, and a session may legitimately already be
            // running from SessionKeyHolder.
            byte[] session = SessionKeyHolder.getKey();
            if (session == null) return false;
            existing = session;
        }
        try {
            PinKeyGate.enroll(appCtx, pin, existing);
            // Verified inside enroll(); safe to drop the unwrapped copy now.
            prefs.edit().remove(KEY_DB_CIPHER).commit();
            SessionKeyHolder.unlock(existing);
            Log.i(TAG, "Database key migrated into the PIN gate; unwrapped copy removed.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not migrate the database key into the PIN gate — leaving the existing key in place so the user keeps access (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return false;
        } finally {
            Arrays.fill(existing, (byte) 0);
        }
    }

    /**
     * Reads and validates the stored passphrase.
     *
     * @return the 32-byte key, or {@code null} if absent/corrupt.
     */
    private static byte[] readKey(SharedPreferences prefs) {
        String stored;
        try {
            stored = prefs.getString(KEY_DB_CIPHER, null);
        } catch (Exception e) {
            // EncryptedSharedPreferences throws when the underlying master key
            // can no longer decrypt this file (rotated / deleted alias).
            Log.e(TAG, "Failed to read stored DB key: " + e.getClass().getSimpleName());
            return null;
        }
        if (stored == null) return null;

        byte[] key;
        try {
            key = Base64.decode(stored, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Stored DB key is not valid Base64 — treating as absent.");
            return null;
        }
        if (key.length != KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            Log.e(TAG, "Stored DB key has wrong length — treating as absent.");
            return null;
        }
        return key;
    }

    /**
     * Generates a fresh key and persists it <em>durably</em> before returning,
     * so the key can never be newer than the database it protects.
     */
    private static byte[] createAndPersist(SharedPreferences prefs) {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.encodeToString(key, Base64.NO_WRAP);

        // commit() (not apply()) — must reach disk before the database is created.
        boolean committed;
        try {
            committed = prefs.edit().putString(KEY_DB_CIPHER, encoded).commit();
        } catch (Exception e) {
            Arrays.fill(key, (byte) 0);
            throw new KeyUnavailableException(
                    "Failed to persist new database key: " + e.getClass().getSimpleName());
        }
        if (!committed) {
            Arrays.fill(key, (byte) 0);
            throw new KeyUnavailableException(
                    "Failed to persist new database key (commit returned false).");
        }

        // Read back to confirm the value is retrievable, not just written. This
        // catches an ESP store that accepts writes but cannot decrypt its own
        // output — which would otherwise surface later as a wiped database.
        byte[] verify = readKey(prefs);
        if (verify == null || !Arrays.equals(verify, key)) {
            if (verify != null) Arrays.fill(verify, (byte) 0);
            Arrays.fill(key, (byte) 0);
            throw new KeyUnavailableException(
                    "New database key failed read-back verification.");
        }
        Arrays.fill(verify, (byte) 0);

        if (SecurePrefs.getTier() == SecurePrefs.SecurityTier.SOFTWARE) {
            Log.w(TAG, "DB passphrase stored under a software-backed Keystore key:"
                    + " encrypted at rest, but the key material may be extractable"
                    + " given a compromised OS image.");
        }
        return key;
    }

    /** True if a Room/SQLCipher database file already exists on disk. */
    private static boolean databaseExists(Context appCtx) {
        File db = appCtx.getDatabasePath(DB_NAME);
        if (db != null && db.exists() && db.length() > 0) return true;
        // WAL mode: the main file can be zero-length while data sits in the -wal.
        File wal = new File(db == null ? "" : db.getPath() + "-wal");
        return wal.exists() && wal.length() > 0;
    }
}
