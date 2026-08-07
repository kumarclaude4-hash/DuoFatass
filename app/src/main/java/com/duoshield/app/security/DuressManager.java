package com.duoshield.app.security;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.duoshield.app.BuildConfig;
import com.duoshield.app.SignInActivity;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.backup.BackupScheduler;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.util.ContactBackupHelper;
import com.duoshield.app.util.SecurePrefs;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class DuressManager {

    private static final String PREFS_NAME          = "duoshield_prefs";
    private static final String KEY_DURESS_PREFIX   = "duress_pin_hash_";
    private static final String KEY_DURESS_LEGACY   = "duress_pin_hash";
    private static final String KEY_ELIGIBLE_PREFIX = "duress_eligible_";
    private static final int    ITERATIONS          = 310_000;
    private static final int    KEY_LEN             = 256;

    /**
     * Returns the UID-scoped SecurePrefs key for the currently signed-in user,
     * or {@code null} if no user is signed in.
     *
     * <h3>Why UID-scoped?</h3>
     * Duress logout intentionally keeps the hash so that a restore attempt for
     * the same account is still gated. But a brand-new user signing in on the
     * same device must not inherit the old account's duress PIN — that would
     * be indistinguishable from the old account still being active.
     */
    private static String duressKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_DURESS_PREFIX + user.getUid() : null;
    }

    public static void setDuressPin(Context context, String pin) {
        String key = duressKey();
        if (key == null) return;
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash   = pbkdf2(pin, salt);
            String stored = bytesToHex(salt) + ":" + bytesToHex(hash);
            SecurePrefs.get(context).edit()
                    .putString(key, stored)
                    .remove(KEY_DURESS_LEGACY)
                    .apply();
        } catch (Exception ignored) {}
    }

    public static boolean isDuressPin(Context context, String enteredPin) {
        String key = duressKey();
        if (key == null) return false;
        SharedPreferences sp = SecurePrefs.get(context);
        String stored = sp.getString(key, null);
        if (stored == null) {
            // Fallback to legacy global key (migration window)
            stored = sp.getString(KEY_DURESS_LEGACY, null);
        }
        if (stored == null) return false;
        int sep = stored.indexOf(':');
        if (sep < 0) return false;
        try {
            byte[] salt     = hexToBytes(stored.substring(0, sep));
            byte[] expected = hexToBytes(stored.substring(sep + 1));
            byte[] actual   = pbkdf2(enteredPin, salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) { return false; }
    }

    /** Returns true if a duress PIN hash is stored for the current account. */
    public static boolean hasDuressPin(Context context) {
        String key = duressKey();
        if (key == null) return false;
        SharedPreferences sp = SecurePrefs.get(context);
        if (sp.getString(key, null) != null) return true;
        // Migration: move legacy global hash to UID-scoped key
        String legacy = sp.getString(KEY_DURESS_LEGACY, null);
        if (legacy != null) {
            sp.edit().putString(key, legacy).remove(KEY_DURESS_LEGACY).apply();
            return true;
        }
        return false;
    }

    /** Removes the duress PIN hash for the current account. */
    public static void clearDuressPin(Context context) {
        String key = duressKey();
        if (key == null) return;
        SecurePrefs.get(context).edit()
                .remove(key)
                .remove(KEY_DURESS_LEGACY)
                .apply();
    }

    /**
     * "Sync then Wipe" — plausible-deniability duress logout.
     *
     * <p>Triggered exclusively by an exact duress-PIN match in
     * {@code LockScreenActivity}. There is no wrong-guess-count fallback — see
     * {@code LockScreenActivity}'s javadoc for why that was removed.
     *
     * <h3>Sequence</h3>
     * <ol>
     *   <li><b>Instant navigation</b> — {@code SignInActivity} starts immediately
     *       with {@code FLAG_ACTIVITY_CLEAR_TASK}. The chat screen disappears at once.</li>
     *   <li><b>Panic sync</b> — {@link BackupManager#syncIncrementalSync} uploads any
     *       unsynced messages to Firestore. Hard deadline: 10 seconds.</li>
     *   <li><b>Destructive local wipe</b>:
     *     <ul>
     *       <li>Room DB closed and deleted ({@code duoshield_db}).</li>
     *       <li>All {@link SecurePrefs} keys destroyed synchronously ({@code .commit()}).</li>
     *       <li>Local contact backup cleared.</li>
     *       <li>All SharedPreferences files cleared synchronously.</li>
     *     </ul>
     *   </li>
     *   <li><b>Firebase sign-out</b> — local only, no Firestore writes or deletes.</li>
     * </ol>
     *
     * <h3>Security guarantees</h3>
     * <ul>
     *   <li>No cloud deletion — Firestore data is preserved for recovery via seed phrase.</li>
     *   <li>Forensic resistance — SQLCipher DB file and all key material removed from NAND.</li>
     *   <li>Plausible deniability — device presents as unconfigured/factory-reset.</li>
     * </ul>
     *
     * <h3>Recovery</h3>
     * User opens the (now empty) app, selects "Restore Account", enters their 12-word
     * seed phrase. {@code RestoreFromSeedActivity} re-derives keys and pulls all chats
     * (including those uploaded by the panic sync) back from Firestore.
     *
     * <p><strong>Silent:</strong> no Toast, no dialog, no animation visible to an observer.
     */
    public static void performLogout(Context context) {
        // Capture the UID before anything below signs out or wipes prefs — both would
        // erase the one piece of information the delayed FCM de-registration job needs.
        FirebaseUser userBeforeWipe = FirebaseAuth.getInstance().getCurrentUser();
        String uidBeforeWipe = userBeforeWipe != null ? userBeforeWipe.getUid() : null;

        // Enqueue FcmUnregisterWorker immediately — no credential needed
        // (FirebaseMessaging.deleteToken() handles its own auth).
        // AccountLockWorker is enqueued later, inside the background thread
        // (step 1b), once a server-issued one-time nonce has been obtained while
        // the Firebase session is still live. If nonce acquisition fails (offline),
        // the worker is not enqueued; the synchronous write in step 1a is the primary
        // mechanism and covers the online case.
        final Context appCtx = context.getApplicationContext();
        if (uidBeforeWipe != null) {
            com.duoshield.app.util.FcmUnregisterWorker.enqueue(appCtx, uidBeforeWipe);
        }

        // F30 fix: Write a synchronous routing-guard flag BEFORE launching SignInActivity.
        // SignInActivity (and SplashActivity / MainActivity) check this flag and skip the
        // returning-user auto-route while the background wipe is still in flight.
        // The flag is cleared at the very end of the background thread, after signOut(),
        // so SignInActivity cannot bounce back before both keys and session are destroyed.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean("duress_wipe_in_progress", true).commit();

        // 1. Instant navigation — removes chat screen from view immediately.
        //    To an observer, it looks like the app is simply processing the PIN.
        Intent intent = new Intent(context, SignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);

        // Cancel the daily backup sync — the session is intentionally destroyed.
        try { BackupScheduler.cancel(context); } catch (Exception ignored) {}

        // Full "sync then wipe" on a background thread
        new Thread(() -> {

            // 1a. Synchronous account-lock write — performed BEFORE the panic sync
            //     and BEFORE sign-out, while the Firebase session is still live.
            //     This closes the race window where a concurrent restore attempt on
            //     another device could succeed during the WorkManager jitter delay.
            //     A 5-second cap keeps the wipe responsive.
            if (uidBeforeWipe != null) {
                try {
                    final Object   lockSync = new Object();
                    final boolean[] written = {false};
                    java.util.Map<String, Object> lockData = new java.util.HashMap<>();
                    lockData.put("locked",   true);
                    lockData.put("lockedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                    FirebaseFirestore.getInstance()
                            .collection("accountLock")
                            .document(uidBeforeWipe)
                            .set(lockData)
                            .addOnCompleteListener(task -> {
                                synchronized (lockSync) { written[0] = true; lockSync.notifyAll(); }
                            });
                    synchronized (lockSync) {
                        if (!written[0]) lockSync.wait(5_000);
                    }
                    android.util.Log.d("DuressManager", "Synchronous account-lock write complete.");
                } catch (Exception ignored) {
                    android.util.Log.w("DuressManager",
                            "Synchronous account-lock write failed — will attempt nonce retry.");
                }
            }

            // 1b. Request a server-issued one-time nonce for AccountLockWorker to use
            //     as a retry fallback. Done here — before sign-out — so the nonce
            //     request is authenticated with the live Firebase session rather than
            //     any credential stored persistently in WorkManager input data.
            //     If this fails (offline), AccountLockWorker is not enqueued; the
            //     synchronous write in step 1a covers the online case.
            if (uidBeforeWipe != null && userBeforeWipe != null) {
                try {
                    // Get Firebase ID token synchronously (5-second timeout).
                    final Object tokenSync   = new Object();
                    final String[] tokenHolder = {null};
                    userBeforeWipe.getIdToken(false)
                            .addOnSuccessListener(r -> {
                                synchronized (tokenSync) { tokenHolder[0] = r.getToken() != null ? r.getToken() : ""; tokenSync.notifyAll(); }
                            })
                            .addOnFailureListener(e -> {
                                synchronized (tokenSync) { tokenHolder[0] = ""; tokenSync.notifyAll(); }
                            });
                    synchronized (tokenSync) {
                        if (tokenHolder[0] == null) tokenSync.wait(5_000);
                    }
                    String idToken = tokenHolder[0] != null ? tokenHolder[0] : "";

                    if (!idToken.isEmpty()) {
                        // POST /requestLockNonce authenticated with the live session.
                        String nonce = requestLockNonce(idToken);
                        if (nonce != null && !nonce.isEmpty()) {
                            AccountLockWorker.enqueue(appCtx, uidBeforeWipe, nonce);
                            android.util.Log.d("DuressManager", "AccountLockWorker enqueued with nonce.");
                        } else {
                            // S06-H3: Nonce fetch returned empty/null (server error) — enqueue
                            // without a nonce so AccountLockWorker can attempt UID-only nonce
                            // recovery on its first retry, once the device is back online.
                            AccountLockWorker.enqueue(appCtx, uidBeforeWipe, "");
                            android.util.Log.w("DuressManager",
                                    "Nonce empty — AccountLockWorker enqueued for UID-only retry.");
                        }
                    } else {
                        // S06-H3: Could not obtain an ID token (session already expired or
                        // unavailable) — still enqueue for UID-only nonce recovery.
                        AccountLockWorker.enqueue(appCtx, uidBeforeWipe, "");
                        android.util.Log.w("DuressManager",
                                "No ID token — AccountLockWorker enqueued for UID-only retry.");
                    }
                } catch (Exception e) {
                    // S06-H3: Exception during nonce acquisition (offline, timeout, etc.) —
                    // enqueue the worker anyway so it can retry once connectivity returns.
                    AccountLockWorker.enqueue(appCtx, uidBeforeWipe, "");
                    android.util.Log.w("DuressManager",
                            "Could not obtain lock nonce — AccountLockWorker enqueued for UID-only retry: "
                            + e.getMessage());
                }
            }

            // 2. Panic sync — upload unsynced messages to Firestore before local wipe.
            //    Hard deadline: 10 seconds. If the sync doesn't finish in time,
            //    BackupManager aborts automatically and we proceed to the wipe.
            BackupManager.syncIncrementalSync(context);

            // F35 / F16 fix: Write the sign-out event synchronously on THIS thread,
            // immediately before clearInstance(). Using logSync() (not the async log())
            // guarantees the insert lands in the database before we delete it.
            // Event type is SIGN_OUT — indistinguishable from a voluntary sign-out,
            // preserving plausible deniability in the Session Log.

            // 3. Destructive local wipe ─────────────────────────────────────────

            // 3a. Close and delete the SQLCipher database (messages, contacts, logs).
            //     clearInstance() must come first so Room's cached connection is
            //     released before the file is deleted.
            AppDatabase.clearInstance();
            context.deleteDatabase("duoshield_db");

            // 3b. Synchronously destroy all key material in SecurePrefs.
            //     .commit() (not .apply()) guarantees the keys are gone before we
            //     proceed — critical for forensic resistance.
            //     NOTE: this clears the account-scoped SecurePrefs file only. The
            //     device-level PIN gate lives in its own isolated file
            //     (SecurePrefs.getDeviceGate()) precisely so this wipe can never
            //     reach it — see PinManager's class javadoc. Do not "fix" this by
            //     re-adding the device-gate keys to this clear(); that is the exact
            //     bug the isolated file exists to prevent.
            try {
                SecurePrefs.get(context).edit().clear().commit();
                SecurePrefs.reset(); // invalidate cached instance
            } catch (Exception ignored) {}

            // 3c. Wipe the local contact backup so the "Restore Contacts" path
            //     cannot recover contact data after a duress-triggered wipe.
            ContactBackupHelper.clearBackup(context);

            // 3d. F37 fix: delete any media the user saved to the public gallery
            //     (Pictures/DuoShield, Movies/DuoShield). Must run BEFORE the prefs
            //     clear below because the URI list lives inside duoshield_prefs.
            try {
                com.duoshield.app.util.MediaStoreWipeHelper.wipeAll(context);
            } catch (Exception ignored) {}

            // 3e. Clear all SharedPreferences files synchronously.
            //     NOTE: duress_wipe_in_progress lives in PREFS_NAME and is cleared
            //     here along with all other keys — see step 4 below for the removal.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().clear().commit();
            context.getSharedPreferences("duoshield_security_prefs", Context.MODE_PRIVATE)
                   .edit().clear().commit();
            context.getSharedPreferences("duoshield_contacts_bak", Context.MODE_PRIVATE)
                   .edit().clear().commit();

            // 4. Firebase local sign-out (no network call, no Firestore writes).
            try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}

            // S06-H2: Remove WorkManager task history so a forensic analysis of
            // WorkManager's internal SQLite DB cannot reconstruct the duress-logout
            // event sequence. cancelAllWorkByTag() terminates any still-queued jobs;
            // pruneWork() deletes the FINISHED work records (the primary forensic
            // concern — completed jobs linger in WorkManager's DB until pruned).
            try {
                androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(appCtx);
                if (uidBeforeWipe != null) {
                    wm.cancelAllWorkByTag("account_lock_" + uidBeforeWipe);
                    wm.cancelAllWorkByTag("fcm_unregister_" + uidBeforeWipe);
                }
                wm.pruneWork();
            } catch (Exception ignored) {}

            // F30 fix: Clear the routing-guard flag LAST, after signOut() and after all
            // prefs are wiped. The step-3d clear above already removes it as part of the
            // full PREFS_NAME wipe, but this explicit remove is a safety net in case
            // step 3d failed — without it, SignInActivity would remain blocked forever.
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                       .edit().remove("duress_wipe_in_progress").apply();
            } catch (Exception ignored) {}

        }, "duress-logout").start();
    }

    // ── Server-side eligibility gate ──────────────────────────────────────────
    //
    // Whether the secondary-code feature is even offered to this account is
    // controlled server-side, NOT by anything the client can set. A generic
    // account created by anyone probing the app is never enrolled and never
    // sees the option. Enrollment happens out-of-band (the operator adds
    // duressEligibility/{accountId} in the Firebase console) — the client only
    // ever reads a yes/no flag for its own account, and the PIN itself never
    // leaves the device either way.
    //
    // The result is cached in SecurePrefs so the app keeps working offline and
    // doesn't need a network round trip on every launch; a later successful
    // check can still flip the cached value (including revoking it).

    private static String eligibilityCacheKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_ELIGIBLE_PREFIX + user.getUid() : null;
    }

    /** Cached (offline-safe) read of whether this account may configure a duress PIN. */
    public static boolean isDuressEligibleCached(Context context) {
        String key = eligibilityCacheKey();
        if (key == null) return false;
        return SecurePrefs.get(context).getBoolean(key, false);
    }

    /**
     * Refreshes the cached eligibility flag from Firestore. Safe to call on
     * every sign-in / app foreground — reads a single small document via the
     * account's own UID, which the Firestore rules restrict to that account.
     * No-ops silently on failure (offline, etc.) — the previously cached value
     * is left untouched either way.
     */
    public static void refreshEligibility(Context context) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();
        String key = eligibilityCacheKey();
        if (key == null) return;
        Context appCtx = context.getApplicationContext();
        FirebaseFirestore.getInstance()
                .collection("duressEligibility")
                .document(uid)
                .get()
                .addOnSuccessListener(snap -> {
                    boolean eligible = snap != null && snap.exists()
                            && Boolean.TRUE.equals(snap.getBoolean("eligible"));
                    SecurePrefs.get(appCtx).edit().putBoolean(key, eligible).apply();
                })
                .addOnFailureListener(e -> { /* keep last-known cached value */ });
    }

    // ── Lock-nonce helper ─────────────────────────────────────────────────────

    /**
     * Requests a single-use account-lock nonce from the push server, authenticated
     * with the supplied Firebase ID token. The nonce is used by {@link AccountLockWorker}
     * as a retry credential — it has no auth power of its own, is uid-bound server-side,
     * expires in 24 hours, and is deleted after one successful {@code /duress-lock} call.
     *
     * <p>Must NOT be called on the main thread (blocking HTTP call).
     *
     * @param idToken valid Firebase ID token captured before sign-out
     * @return nonce string, or {@code null} if the request failed
     */
    private static String requestLockNonce(String idToken) {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) return null;
        String endpoint = serverUrl.endsWith("/")
                ? serverUrl + "requestLockNonce"
                : serverUrl + "/requestLockNonce";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Authorization", "Bearer " + idToken);
                conn.setRequestProperty("Content-Length", "0");
                conn.setDoOutput(false);

                int code = conn.getResponseCode();
                if (code != 200) {
                    android.util.Log.w("DuressManager",
                            "requestLockNonce: server returned HTTP " + code);
                    return null;
                }
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[2048];
                int n;
                while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
                String body = buf.toString("UTF-8");
                JSONObject json = new JSONObject(body);
                String nonce = json.optString("nonce", null);
                if (nonce == null || nonce.isEmpty()) {
                    android.util.Log.w("DuressManager", "requestLockNonce: empty nonce in response");
                    return null;
                }
                return nonce;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            android.util.Log.w("DuressManager", "requestLockNonce failed: " + e.getMessage());
            return null;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LEN);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    private static void deleteDir(java.io.File dir) {
        if (dir == null) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }
}
