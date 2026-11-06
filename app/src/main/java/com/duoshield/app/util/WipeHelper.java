package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import com.duoshield.app.db.AppDatabase;

import com.duoshield.app.SignInActivity;
import com.duoshield.app.backup.BackupScheduler;
import java.io.File;

/**
 * Canonical local-data erasure for every wipe path in the app.
 *
 * <p><strong>Why this class is the single source of truth.</strong> The app previously had
 * three independently-written erasure routines — voluntary "Wipe &amp; Exit"
 * ({@link #wipeAll}), "Unpair Device" (DangerZoneSettingsActivity), and the duress-PIN
 * logout (DuressManager). They drifted apart over time, and the drift was silent because
 * nothing compared them. The most security-critical path (duress) had become the
 * <em>weakest</em> of the three: it left the decrypted-media disk cache
 * ({@code filesDir/b2_cache}) and the temp/export cache directory intact, so a forensic
 * extraction after a duress wipe still recovered readable photos and videos — data that
 * needs no key to read, making the surviving key-destruction irrelevant for it. Unpair
 * was leaking the same media cache plus gallery-saved media, and was leaving the Firebase
 * session signed in.
 *
 * <p>All three paths now funnel through {@link #eraseLocalData}, so a step added for one
 * path is automatically applied to the others. <strong>Do not add erasure steps directly
 * to a caller.</strong> Add them here, and gate them on {@link WipeMode} only when a mode
 * genuinely must differ (the differences are enumerated on {@link WipeMode}).
 */
public class WipeHelper {

    private static final String TAG = "WipeHelper";

    /**
     * Which wipe path is running. This controls <em>only</em> the handful of steps that
     * legitimately differ between paths; every destructive step is shared.
     *
     * <ul>
     *   <li>{@link #VOLUNTARY} / {@link #UNPAIR} — the user is expected back, so contacts
     *       are backed up to a separate prefs file first and survive for the next sign-in.</li>
     *   <li>{@link #DURESS} — the session is being destroyed under coercion. Contacts are
     *       <em>not</em> backed up, and any pre-existing backup is destroyed, so the
     *       "Restore Contacts" path cannot recover the social graph afterwards.</li>
     * </ul>
     */
    public enum WipeMode {
        /** User-initiated "Wipe &amp; Exit". Contacts preserved for re-sign-in. */
        VOLUNTARY,
        /** User-initiated "Unpair Device". Contacts preserved for re-pairing. */
        UNPAIR,
        /** Duress-PIN triggered. Contacts destroyed; nothing may be recoverable. */
        DURESS
    }

    /**
     * Erases all local account data. <strong>Does not navigate and does not show UI</strong> —
     * the caller owns that, because the duress path deliberately navigates <em>before</em>
     * erasing (so the chat screen leaves the screen instantly) while the voluntary paths
     * navigate after.
     *
     * <p>Safe to call from a background thread; all writes use {@code commit()} rather than
     * {@code apply()} so that key material is provably gone before this method returns.
     *
     * <p><strong>Step order is load-bearing.</strong> See the inline comments — several
     * steps read state that a later step destroys.
     */
    public static void eraseLocalData(Context ctx, WipeMode mode) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        SharedPreferences prefs =
                appCtx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);

        // Step 1: Back up contacts BEFORE destroying the DB key, so they survive a
        // voluntary wipe/unpair. Must run while both the SQLCipher key (in SecurePrefs)
        // and the database file still exist. Skipped entirely for DURESS — that mode
        // destroys the backup in step 6 instead.
        if (mode != WipeMode.DURESS) {
            try {
                String myUid = prefs.getString("my_uid", null);
                if (myUid == null) {
                    com.google.firebase.auth.FirebaseUser fu =
                            com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                    if (fu != null) myUid = fu.getUid();
                }
                if (myUid != null) {
                    java.util.List<com.duoshield.app.models.Contact> contacts =
                            AppDatabase.getInstance(appCtx).contactDao().getAll();
                    ContactBackupHelper.backup(appCtx, myUid, contacts);
                }
            } catch (Exception e) {
                Log.w(TAG, "Contact backup failed (non-fatal) — proceeding with wipe (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        }

        // Step 2: Delete media saved to the public gallery (Pictures/DuoShield,
        // Movies/DuoShield) via the Save button. MUST run before the prefs clear in
        // step 7 — the list of saved URIs lives inside duoshield_prefs.
        try {
            MediaStoreWipeHelper.wipeAll(appCtx);
        } catch (Exception e) {
            Log.w(TAG, "MediaStoreWipeHelper.wipeAll() failed during wipe (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // Step 3: Wipe the persistent decrypted-media disk cache.
        // This lives in filesDir/b2_cache — NOT in getCacheDir() — so the cache-directory
        // delete in step 9 does not reach it. These files are already decrypted, so they
        // are readable without any key: leaving them behind both leaks conversation media
        // and contradicts the "fresh install" appearance a duress wipe is meant to present.
        try {
            B2StorageHelper.clearDiskCache(appCtx);
        } catch (Exception e) {
            Log.w(TAG, "clearDiskCache() failed during wipe (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // Step 3a: Delete the durable local profile photo. Like the b2_cache above, this
        // lives directly in filesDir — NOT getCacheDir() — so neither step 3 nor the
        // cache-directory delete in step 9 reaches it. It is a plain unencrypted JPEG of
        // the account holder's face, readable without any key, so leaving it behind
        // identifies the previous user and contradicts the fresh-install appearance a
        // duress wipe must present. The ".tmp" sibling is the staging file used by
        // SettingsActivity's atomic write-then-rename and can survive a crash mid-save.
        for (String avatarFile : new String[]{"own_avatar.jpg", "own_avatar.jpg.tmp"}) {
            try {
                java.io.File f = new java.io.File(appCtx.getFilesDir(), avatarFile);
                if (f.exists() && !f.delete()) {
                    Log.w(TAG, "Could not delete avatar file " + com.duoshield.app.util.LogRedact.metadata(avatarFile) + " during wipe");
                }
            } catch (Exception e) {
                Log.w(TAG, "Avatar delete failed during wipe (non-fatal): " + com.duoshield.app.util.LogRedact.metadata(avatarFile) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        }

        // Step 4: Destroy key material (Signal identity key pair, prekeys, PIN hashes,
        // SQLCipher DB key) synchronously.
        // NOTE: this clears the account-scoped SecurePrefs file only — the device-level
        // PIN gate lives in its own isolated file (SecurePrefs.getDeviceGate()) and must
        // survive this wipe by design; see PinManager's class javadoc. Do not "fix" this
        // by re-adding the device-gate keys here; that is the exact bug the isolated file
        // exists to prevent.
        try {
            SecurePrefs.get(appCtx).edit().clear().commit();
            SecurePrefs.reset(); // invalidate the cached instance so nothing reuses it
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear SecurePrefs during wipe (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
        // S07-L2: SecurePrefs is the on-disk copy of the Signal identity key pair —
        // but SeedPhraseHelper.deriveIdentityKeyPair() also caches the derived
        // IdentityKeyPair (private key material) in a static, process-lifetime
        // field that the on-disk clear above never touches. Without this, the
        // identity private key stayed resident in memory for the rest of the
        // process's life after every wipe path, including DURESS. See
        // SeedPhraseHelper.clearDerivationCache()'s javadoc for the full detail.
        try {
            com.duoshield.app.crypto.SeedPhraseHelper.clearDerivationCache();
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear SeedPhraseHelper derivation cache during wipe (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }

        // Step 5: Close the Room singleton BEFORE deleting the file, so the cached
        // instance cannot point at a deleted database on next access.
        try {
            AppDatabase.clearInstance();
            appCtx.deleteDatabase("duoshield_db");
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete Room DB file during wipe (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }

        // Step 6: Cancel scheduled background work — there is no session left to serve.
        try { BackupScheduler.cancel(appCtx); } catch (Exception e) {
            Log.w(TAG, "BackupScheduler.cancel() failed during wipe (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }
        try { com.duoshield.app.util.SelfDestructScheduler.cancel(appCtx); } catch (Exception e) {
            Log.w(TAG, "SelfDestructScheduler.cancel() failed during wipe (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // DURESS only: destroy the contact backup so "Restore Contacts" cannot rebuild
        // the social graph after a coerced wipe.
        if (mode == WipeMode.DURESS) {
            try { ContactBackupHelper.clearBackup(appCtx); } catch (Exception e) {
                Log.w(TAG, "clearBackup() failed during duress wipe (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
            }
        }

        // Step 7: Clear the plain prefs files synchronously.
        // duoshield_security_prefs holds pin_fail_count — a leftover counter must not
        // carry into the next account. (A previous comment here claimed duress
        // intentionally preserved this counter; the duress code cleared it anyway. The
        // comment was stale, and all paths now agree: it is cleared.)
        prefs.edit().clear().commit();
        appCtx.getSharedPreferences("duoshield_security_prefs", Context.MODE_PRIVATE)
              .edit().clear().commit();
        appCtx.getSharedPreferences("duoshield_contacts_bak", Context.MODE_PRIVATE)
              .edit().clear().commit();

        // Step 8: Sign out of Firebase so no valid session token remains. Without this a
        // forensic extraction could reuse the token against Firestore until it expires
        // (~1 hour). Local-only call: no network round trip, no Firestore writes.
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut();
        } catch (Exception e) {
            Log.e(TAG, "Firebase signOut failed during wipe (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // Step 9: Delete the temp/export cache directory (thumbnails, chat-export ZIPs,
        // transient decrypted files).
        try {
            deleteDir(appCtx.getCacheDir());
        } catch (Exception ignored) {}
    }

    /**
     * Voluntary "Wipe &amp; Exit": erase everything, then return to the sign-in screen.
     * Contacts are preserved for the next sign-in.
     */
    public static void wipeAll(Context ctx) {
        eraseLocalData(ctx, WipeMode.VOLUNTARY);

        Intent i = new Intent(ctx, SignInActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(i);
    }

    private static void deleteDir(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }
}
