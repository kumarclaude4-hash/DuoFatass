package com.duoshield.app.backup;

import com.duoshield.app.util.LogRedact;

import android.content.Context;
import android.util.Log;

import com.duoshield.app.crypto.BackupCryptoHelper;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Contact;
import com.duoshield.app.models.Message;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages cloud backup of decrypted messages to Firestore.
 *
 * Firestore layout:
 *   backups/{uid}                     → meta doc  {lastBackupTs, count}
 *   backups/{uid}/messages/{msgId}    → {enc, hmac, compressed, ts, conversationId}
 *   backup_logs/{autoId}              → {uid, event, ts, count, error?}
 *
 * "enc" is an AES-256-GCM blob produced by BackupCryptoHelper (GZIP-compressed
 * for new writes, uncompressed for old docs — distinguished by the "compressed" field).
 * The doc's own id + conversationId + compressed flag are bound into the GCM tag
 * as associated data (S07-M3), so none of those fields can be tampered with
 * independently of "enc".
 * "hmac" is a keyed HMAC-SHA256 of the plaintext JSON, verified on restore
 * (S07-H2 — replaces a legacy unkeyed "checksum" field still read for docs
 * written before this fix; an unkeyed digest is an offline guess-confirmation
 * oracle, which a keyed HMAC is not).
 *
 * The backup key is derived from the user's seed phrase and stored in SecurePrefs.
 * The server never sees plaintext.
 *
 * Incremental sync: tracks last_backup_ts in SharedPreferences ("duoshield_prefs").
 * BackupSyncWorker calls syncIncremental() which only uploads messages newer than that
 * timestamp; falls back to syncAll() on the very first backup.
 *
 * Size limit: syncAll() and syncIncremental() log a warning when > 10 000 messages are
 * pending (no hard cap — backup proceeds; the warning surfaces in logcat and backup_logs).
 *
 * All Firestore writes are fire-and-forget (failures logged, never crash the UI).
 * Restore is synchronous (designed to run on a background thread).
 */
public final class BackupManager {

    private static final String TAG          = "BackupManager";
    private static final String COL_BACKUPS  = "backups";
    private static final String COL_MSGS     = "messages";
    private static final String COL_CONTACTS = "contacts";
    private static final String COL_GROUPS   = "groups";
    private static final String COL_LOGS     = "backup_logs";
    private static final int    PAGE_LIMIT   = 1000;

    private static final String PREF_FILE      = "duoshield_prefs";
    private static final String PREF_LAST_BAK  = "last_backup_ts";
    private static final int    SIZE_WARN_LIMIT = 10_000;
    /** Max docs per WriteBatch. Firestore limit is 500; we use 200 to stay well inside the 10 MB request cap. */
    private static final int    BATCH_SIZE      = 200;

    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "backup-worker");
                t.setDaemon(true);
                return t;
            });

    private BackupManager() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget: encrypt {@code msg} and write it to Firestore.
     * No-op if no backup key is available (e.g. legacy account without seed restore).
     */
    public static void backup(Context ctx, Message msg) {
        if (msg == null || msg.getId() == null) return;
        byte[] key = BackupCryptoHelper.getStoredKey(ctx);
        if (key == null) return;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();

        executor.execute(() -> backupWithRetry(ctx, msg, uid, key, 0));
    }

    private static void backupWithRetry(Context ctx, Message msg, String uid,
                                        byte[] key, int attempt) {
        try {
            String json = toJson(msg);
            // S07-M3: bind doc id + conversationId + the "compressed" flag itself
            // into the GCM tag so none of them can be tampered with independently
            // of the payload — see BackupCryptoHelper.buildAad().
            byte[] aad  = BackupCryptoHelper.buildAad(msg.getId(), msg.getConversationId(), true);
            // S07-H2: keyed HMAC replaces the legacy unkeyed SHA-256 checksum.
            String hmac = BackupCryptoHelper.computeHmac(key, json);
            String enc  = BackupCryptoHelper.encryptCompressed(key, json, aad);

            FirebaseFirestore db = FirebaseFirestore.getInstance();

            Map<String, Object> doc = new HashMap<>();
            doc.put("enc",            enc);
            doc.put("ts",             msg.getTimestamp());
            doc.put("conversationId", msg.getConversationId());
            doc.put("hmac",           hmac);
            doc.put("compressed",     true);

            final boolean[] succeeded = {false};
            final Object lock = new Object();

            db.collection(COL_BACKUPS).document(uid)
              .collection(COL_MSGS).document(msg.getId())
              .set(doc)
              .addOnSuccessListener(v -> {
                  synchronized (lock) { succeeded[0] = true; lock.notifyAll(); }
              })
              .addOnFailureListener(e -> {
                  Log.w(TAG, "backup write failed (attempt " + (attempt + 1) + "): " + com.duoshield.app.util.LogRedact.error(e));
                  synchronized (lock) { lock.notifyAll(); }
              });

            synchronized (lock) {
                if (!succeeded[0]) lock.wait(8_000);
            }

            if (succeeded[0]) {
                updateMeta(db, uid);
            } else if (attempt < 2) {
                // Retry with exponential back-off: 2 s, 4 s
                Thread.sleep(2_000L * (1 << attempt));
                backupWithRetry(ctx, msg, uid, key, attempt + 1);
            } else {
                Log.e(TAG, "backup: gave up after 3 attempts for msg=" + com.duoshield.app.util.LogRedact.metadata(msg.getId()));
            }

        } catch (Exception e) {
            Log.e(TAG, "backup: failed for msg=" + com.duoshield.app.util.LogRedact.metadata(msg.getId()) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
    }

    /**
     * Synchronous restore: pulls ALL backed-up messages from Firestore (paginated),
     * decrypts them, verifies integrity checksums, and inserts them into Room.
     *
     * Compressed docs (compressed:true) are decompressed after decryption.
     * Legacy docs without the flag are decrypted with the uncompressed path.
     * Docs where the checksum does not match are logged and skipped — data
     * integrity failure is surfaced loudly rather than silently restoring corrupted data.
     *
     * Must be called from a background thread.
     *
     * @return number of messages successfully restored, or -1 on fatal error.
     */
    public static int restoreAllSync(Context ctx, String uid, byte[] backupKey) {
        if (uid == null || backupKey == null) return 0;

        try {
            FirebaseFirestore db     = FirebaseFirestore.getInstance();
            AppDatabase       roomDb = AppDatabase.getInstance(ctx);
            int               count  = 0;
            int               checksumFailures = 0;

            com.google.firebase.firestore.DocumentSnapshot lastVisible = null;

            while (true) {
                final Object[]    holder = {null};
                final Exception[] err    = {null};
                final Object      lock   = new Object();

                com.google.firebase.firestore.Query query =
                        db.collection(COL_BACKUPS).document(uid)
                          .collection(COL_MSGS)
                          .orderBy("ts")
                          .limit(PAGE_LIMIT);

                if (lastVisible != null) {
                    query = query.startAfter(lastVisible);
                }

                query.get()
                     .addOnSuccessListener(snap -> { synchronized (lock) { holder[0] = snap; lock.notifyAll(); } })
                     .addOnFailureListener(e    -> { synchronized (lock) { err[0]    = e;    lock.notifyAll(); } });

                synchronized (lock) {
                    if (holder[0] == null && err[0] == null) lock.wait(30_000);
                }

                if (err[0] != null) {
                    Log.e(TAG, "restoreAllSync: Firestore fetch failed" + " (" + com.duoshield.app.util.LogRedact.error(err[0]) + ")");
                    return count > 0 ? count : -1;
                }
                if (holder[0] == null) {
                    Log.w(TAG, "restoreAllSync: page timed out after " + count + " messages");
                    break;
                }

                com.google.firebase.firestore.QuerySnapshot snap =
                        (com.google.firebase.firestore.QuerySnapshot) holder[0];

                List<DocumentSnapshot> docs = snap.getDocuments();

                for (DocumentSnapshot docSnap : docs) {
                    try {
                        Boolean deleted = docSnap.getBoolean("isDeleted");
                        if (Boolean.TRUE.equals(deleted)) continue;

                        String  encBlob        = docSnap.getString("enc");
                        String  checksum       = docSnap.getString("checksum"); // legacy (S07-H2)
                        String  hmac           = docSnap.getString("hmac");     // current (S07-H2)
                        String  conversationId = docSnap.getString("conversationId");
                        Boolean compressed     = docSnap.getBoolean("compressed");
                        if (encBlob == null) continue;

                        // S07-M3: reconstruct the exact AAD used at write time. A doc
                        // written before this fix has no bound AAD, so only apply one
                        // when a "hmac" field is present (the marker for "written by
                        // the current, AAD-aware path") — an old doc keeps decrypting
                        // exactly as it always did.
                        boolean isCurrentFormat = hmac != null && !hmac.isEmpty();
                        byte[] aad = isCurrentFormat
                                ? BackupCryptoHelper.buildAad(docSnap.getId(), conversationId,
                                        Boolean.TRUE.equals(compressed))
                                : null;

                        // Decrypt — choose path based on compression flag
                        String json;
                        if (Boolean.TRUE.equals(compressed)) {
                            json = BackupCryptoHelper.decryptCompressed(backupKey, encBlob, aad);
                        } else {
                            json = BackupCryptoHelper.decrypt(backupKey, encBlob);
                        }

                        // Integrity check — skip corrupted/tampered docs rather than
                        // restoring bad data. Prefer the keyed HMAC; only a doc that
                        // predates this fix falls back to the legacy unkeyed checksum.
                        boolean integrityOk = isCurrentFormat
                                ? BackupCryptoHelper.verifyHmac(backupKey, json, hmac)
                                : BackupCryptoHelper.verifyChecksum(json, checksum);
                        if (!integrityOk) {
                            checksumFailures++;
                            Log.e(TAG, "restoreAllSync: INTEGRITY CHECK FAILED for doc "
                                    + com.duoshield.app.util.LogRedact.metadata(docSnap.getId()) + " — skipping (corruption or tampering detected)");
                            continue;
                        }

                        Message msg = fromJson(json);
                        if (msg != null && !msg.isDeleted()) {
                            roomDb.messageDao().insert(msg);
                            count++;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "restoreAllSync: skipping doc " + com.duoshield.app.util.LogRedact.metadata(docSnap.getId()) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                    }
                }

                if (docs.size() < PAGE_LIMIT) {
                    break;
                }
                lastVisible = docs.get(docs.size() - 1);
                Log.d(TAG, "restoreAllSync: page done, total so far=" + count + "; fetching next page…");
            }

            if (checksumFailures > 0) {
                Log.e(TAG, "restoreAllSync: " + checksumFailures
                        + " doc(s) failed integrity check and were skipped");
            }
            Log.d(TAG, "restoreAllSync: restored " + count + " messages (all pages)");
            return count;

        } catch (Exception e) {
            Log.e(TAG, "restoreAllSync: unexpected error" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return -1;
        }
    }

    /**
     * Fire-and-forget: marks the backup doc for {@code messageId} as deleted.
     * Safe to call after a local soft-delete so restores never replay a deleted message.
     */
    public static void markDeleted(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();

        executor.execute(() -> {
            try {
                Map<String, Object> patch = new HashMap<>();
                patch.put("isDeleted", true);
                FirebaseFirestore.getInstance()
                    .collection(COL_BACKUPS).document(uid)
                    .collection(COL_MSGS).document(messageId)
                    .set(patch, SetOptions.merge())
                    .addOnFailureListener(e ->
                        Log.w(TAG, "markDeleted failed for " + com.duoshield.app.util.LogRedact.metadata(messageId) + ": " + com.duoshield.app.util.LogRedact.error(e)));
            } catch (Exception e) {
                Log.e(TAG, "markDeleted: unexpected error for msg=" + com.duoshield.app.util.LogRedact.metadata(messageId) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        });
    }

    // ── Full sync ─────────────────────────────────────────────────────────────

    public static void syncAll(Context ctx) {
        syncAll(ctx, null);
    }

    /**
     * Backs up ALL active messages from Room to Firestore.
     * Updates last_backup_ts in SharedPreferences on success.
     * Logs backup_started / backup_complete / backup_failed events to backup_logs.
     * Warns (does not abort) if the message count exceeds {@value #SIZE_WARN_LIMIT}.
     *
     * <p><b>Speed:</b> messages are pre-encrypted in a tight CPU loop and then
     * written to Firestore in {@value #BATCH_SIZE}-doc WriteBatches, so the number
     * of network round trips is N/200 instead of N (typically 50–100× faster).
     */
    public static void syncAll(Context ctx, SyncCallback callback) {
        byte[] key = BackupCryptoHelper.getStoredKey(ctx);
        if (key == null) {
            Log.d(TAG, "syncAll: no backup key — skipping");
            if (callback != null) callback.onComplete(new SyncResult(0, 0, 0));
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            if (callback != null) callback.onComplete(new SyncResult(0, 0, 0));
            return;
        }
        String uid = user.getUid();

        executor.execute(() -> {
            int written = 0, failed = 0, total = 0;
            logEvent(uid, "backup_started", 0, null);
            try {
                AppDatabase db    = AppDatabase.getInstance(ctx);
                List<Message> all = db.messageDao().getAllActiveMessages();
                if (all == null || all.isEmpty()) {
                    logEvent(uid, "backup_complete", 0, null);
                    if (callback != null) callback.onComplete(new SyncResult(0, 0, 0));
                    return;
                }
                total = all.size();

                if (total > SIZE_WARN_LIMIT) {
                    Log.w(TAG, "syncAll: message count (" + total + ") exceeds "
                            + SIZE_WARN_LIMIT + " — backup will proceed but may be slow");
                    logEvent(uid, "backup_size_warning", total, "count exceeds " + SIZE_WARN_LIMIT);
                }

                FirebaseFirestore fdb = FirebaseFirestore.getInstance();

                // Phase 1 — CPU: encrypt all messages; no I/O yet.
                List<String>              encDocIds = new ArrayList<>(all.size());
                List<Map<String, Object>> encDocs   = new ArrayList<>(all.size());
                for (Message msg : all) {
                    if (msg.getId() == null) { failed++; continue; }
                    try {
                        String json = toJson(msg);
                        // S07-M3/S07-H2: bind id+conversationId+compressed into the GCM
                        // tag and replace the unkeyed checksum with a keyed HMAC.
                        byte[] aad  = BackupCryptoHelper.buildAad(msg.getId(), msg.getConversationId(), true);
                        String hmac = BackupCryptoHelper.computeHmac(key, json);
                        String enc  = BackupCryptoHelper.encryptCompressed(key, json, aad);
                        Map<String, Object> doc = new HashMap<>();
                        doc.put("enc",            enc);
                        doc.put("ts",             msg.getTimestamp());
                        doc.put("conversationId", msg.getConversationId());
                        doc.put("hmac",           hmac);
                        doc.put("compressed",     true);
                        encDocIds.add(msg.getId());
                        encDocs.add(doc);
                    } catch (Exception e) {
                        Log.w(TAG, "syncAll: encryption failed for " + com.duoshield.app.util.LogRedact.metadata(msg.getId()) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                        failed++;
                    }
                }

                // Phase 2 — I/O: commit in WriteBatch chunks of BATCH_SIZE.
                int[] batchResult = commitBatched(fdb, uid, encDocIds, encDocs);
                written += batchResult[0];
                failed  += batchResult[1];

                updateMetaAbsolute(fdb, uid, written);

                // Mark last successful full backup timestamp
                ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                   .edit().putLong(PREF_LAST_BAK, System.currentTimeMillis()).apply();

                Log.d(TAG, "syncAll: wrote " + written + "/" + total
                        + " messages (" + failed + " failed)");
                logEvent(uid, "backup_complete", written, null);

                // Also back up contacts and groups (non-blocking)
                try {
                    AppDatabase rdb = AppDatabase.getInstance(ctx);
                    
                    // 1. Contacts (F5 fix: displayName encrypted before leaving the device)
                    List<Contact> contacts = rdb.contactDao().getAll();
                    if (contacts != null && !contacts.isEmpty()) {
                        for (Contact c : contacts) {
                            if (c.uid == null) continue;
                            String encName = encMeta(key, c.displayName);
                            if (encName == null) continue; // encryption failed — don't leak plaintext
                            Map<String, Object> cdoc = new HashMap<>();
                            cdoc.put("partnerUid",     c.uid);
                            cdoc.put("displayName",    encName);
                            cdoc.put("encMeta",        true);
                            cdoc.put("conversationId", c.conversationId != null ? c.conversationId : "");
                            fdb.collection(COL_BACKUPS).document(uid)
                               .collection(COL_CONTACTS).document(c.uid)
                               .set(cdoc);
                        }
                    }

                    // 2. Groups — metadata + members array (E2EE group backup, F5 fix: name encrypted)
                    List<com.duoshield.app.models.Group> groups = rdb.groupDao().getAllGroups();
                    if (groups != null && !groups.isEmpty()) {
                        for (com.duoshield.app.models.Group g : groups) {
                            // Collect member UID list
                            List<String> memberUids = new ArrayList<>();
                            try {
                                List<com.duoshield.app.models.GroupMember> mems =
                                        rdb.groupDao().getMembersOf(g.id);
                                if (mems != null) {
                                    for (com.duoshield.app.models.GroupMember m : mems) {
                                        if (m.memberUid != null) memberUids.add(m.memberUid);
                                    }
                                }
                            } catch (Exception ignored) {}

                            String encGroupName = encMeta(key, g.name);
                            if (encGroupName == null) continue; // encryption failed — don't leak plaintext

                            Map<String, Object> gdoc = new HashMap<>();
                            gdoc.put("id",          g.id);
                            gdoc.put("name",        encGroupName);
                            gdoc.put("encMeta",     true);
                            gdoc.put("createdBy",   g.createdBy != null ? g.createdBy : "");
                            gdoc.put("createdAt",   g.createdAt);
                            gdoc.put("members",     memberUids);
                            fdb.collection(COL_BACKUPS).document(uid)
                               .collection(COL_GROUPS).document(g.id)
                               .set(gdoc);
                        }
                    }
                    Log.d(TAG, "syncAll: queued contacts/groups+members backup");
                } catch (Exception ce) {
                    Log.w(TAG, "syncAll: metadata backup error (non-fatal)" + " (" + com.duoshield.app.util.LogRedact.error(ce) + ")");
                }

            } catch (Exception e) {
                Log.e(TAG, "syncAll: unexpected error" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                logEvent(uid, "backup_failed", written, com.duoshield.app.util.LogRedact.error(e));
            }
            final SyncResult result = new SyncResult(written, failed, total);
            if (callback != null) callback.onComplete(result);
        });
    }

    // ── Incremental sync ──────────────────────────────────────────────────────

    /**
     * Backs up only messages newer than the last successful backup timestamp.
     * Falls back to a full {@link #syncAll} if no previous backup has been recorded.
     *
     * This is the preferred entry point for {@link BackupSyncWorker} because it avoids
     * re-uploading the entire message history on every 24-hour run.
     */
    public static void syncIncremental(Context ctx, SyncCallback callback) {
        long lastTs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                        .getLong(PREF_LAST_BAK, 0);

        if (lastTs == 0) {
            Log.d(TAG, "syncIncremental: no previous backup — performing full sync");
            syncAll(ctx, callback);
            return;
        }

        byte[] key = BackupCryptoHelper.getStoredKey(ctx);
        if (key == null) {
            Log.d(TAG, "syncIncremental: no backup key — skipping");
            if (callback != null) callback.onComplete(new SyncResult(0, 0, 0));
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            if (callback != null) callback.onComplete(new SyncResult(0, 0, 0));
            return;
        }
        String uid = user.getUid();

        executor.execute(() -> {
            int written = 0, failed = 0, total = 0;
            logEvent(uid, "backup_started", 0, null);
            try {
                List<Message> newMsgs = AppDatabase.getInstance(ctx)
                                                   .messageDao()
                                                   .getMessagesSince(lastTs);

                if (newMsgs == null || newMsgs.isEmpty()) {
                    Log.d(TAG, "syncIncremental: no new messages since " + lastTs);
                    logEvent(uid, "backup_complete", 0, null);
                    if (callback != null) callback.onComplete(new SyncResult(0, 0, 0));
                    return;
                }

                total = newMsgs.size();
                Log.d(TAG, "syncIncremental: backing up " + total + " new message(s) since " + lastTs);

                if (total > SIZE_WARN_LIMIT) {
                    Log.w(TAG, "syncIncremental: incremental batch size (" + total + ") exceeds "
                            + SIZE_WARN_LIMIT + " — consider triggering a full sync");
                    logEvent(uid, "backup_size_warning", total,
                            "incremental batch exceeds " + SIZE_WARN_LIMIT);
                }

                FirebaseFirestore fdb = FirebaseFirestore.getInstance();

                // Phase 1 — CPU: encrypt all new messages; no I/O.
                List<String>              encDocIds = new ArrayList<>(newMsgs.size());
                List<Map<String, Object>> encDocs   = new ArrayList<>(newMsgs.size());
                for (Message msg : newMsgs) {
                    if (msg.getId() == null) { failed++; continue; }
                    try {
                        String json = toJson(msg);
                        // S07-M3/S07-H2: bind id+conversationId+compressed into the GCM
                        // tag and replace the unkeyed checksum with a keyed HMAC.
                        byte[] aad  = BackupCryptoHelper.buildAad(msg.getId(), msg.getConversationId(), true);
                        String hmac = BackupCryptoHelper.computeHmac(key, json);
                        String enc  = BackupCryptoHelper.encryptCompressed(key, json, aad);
                        Map<String, Object> doc = new HashMap<>();
                        doc.put("enc",            enc);
                        doc.put("ts",             msg.getTimestamp());
                        doc.put("conversationId", msg.getConversationId());
                        doc.put("hmac",           hmac);
                        doc.put("compressed",     true);
                        encDocIds.add(msg.getId());
                        encDocs.add(doc);
                    } catch (Exception e) {
                        Log.w(TAG, "syncIncremental: encryption failed for " + com.duoshield.app.util.LogRedact.metadata(msg.getId()) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                        failed++;
                    }
                }

                // Phase 2 — I/O: commit in WriteBatch chunks of BATCH_SIZE.
                int[] batchResult = commitBatched(fdb, uid, encDocIds, encDocs);
                written += batchResult[0];
                failed  += batchResult[1];

                if (written > 0) {
                    ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                       .edit().putLong(PREF_LAST_BAK, System.currentTimeMillis()).apply();
                    updateMeta(fdb, uid);
                }

                logEvent(uid, "backup_complete", written, null);
                Log.d(TAG, "syncIncremental: wrote " + written + "/" + total
                        + " (" + failed + " failed)");

                // Also sync contacts/groups on every incremental run — otherwise new contacts
                // added after the first full backup are never cloud-backed until a manual sync.
                try {
                    AppDatabase rdb = AppDatabase.getInstance(ctx);
                    List<Contact> contacts = rdb.contactDao().getAll();
                    if (contacts != null && !contacts.isEmpty()) {
                        for (Contact c : contacts) {
                            if (c.uid == null) continue;
                            String encName = encMeta(key, c.displayName);
                            if (encName == null) continue;
                            Map<String, Object> cdoc = new HashMap<>();
                            cdoc.put("partnerUid",     c.uid);
                            cdoc.put("displayName",    encName);
                            cdoc.put("encMeta",        true);
                            cdoc.put("conversationId", c.conversationId != null ? c.conversationId : "");
                            fdb.collection(COL_BACKUPS).document(uid)
                               .collection(COL_CONTACTS).document(c.uid)
                               .set(cdoc);
                        }
                    }
                    List<com.duoshield.app.models.Group> groups = rdb.groupDao().getAllGroups();
                    if (groups != null && !groups.isEmpty()) {
                        for (com.duoshield.app.models.Group g : groups) {
                            List<String> memberUids = new ArrayList<>();
                            try {
                                List<com.duoshield.app.models.GroupMember> mems =
                                        rdb.groupDao().getMembersOf(g.id);
                                if (mems != null) {
                                    for (com.duoshield.app.models.GroupMember m : mems) {
                                        if (m.memberUid != null) memberUids.add(m.memberUid);
                                    }
                                }
                            } catch (Exception ignored) {}
                            String encGroupName = encMeta(key, g.name);
                            if (encGroupName == null) continue;
                            Map<String, Object> gdoc = new HashMap<>();
                            gdoc.put("id",          g.id);
                            gdoc.put("name",        encGroupName);
                            gdoc.put("encMeta",     true);
                            gdoc.put("createdBy",   g.createdBy != null ? g.createdBy : "");
                            gdoc.put("createdAt",   g.createdAt);
                            gdoc.put("members",     memberUids);
                            fdb.collection(COL_BACKUPS).document(uid)
                               .collection(COL_GROUPS).document(g.id)
                               .set(gdoc);
                        }
                    }
                    Log.d(TAG, "syncIncremental: queued contacts/groups backup");
                } catch (Exception ce) {
                    Log.w(TAG, "syncIncremental: contacts/groups backup error (non-fatal)" + " (" + com.duoshield.app.util.LogRedact.error(ce) + ")");
                }

            } catch (Exception e) {
                Log.e(TAG, "syncIncremental: unexpected error" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                logEvent(uid, "backup_failed", written, com.duoshield.app.util.LogRedact.error(e));
            }
            final SyncResult result = new SyncResult(written, failed, total);
            if (callback != null) callback.onComplete(result);
        });
    }

    // ── Panic sync (duress) ─────────────────────────────────────────���─────────

    /**
     * Synchronous, time-bounded incremental backup called on the duress path.
     *
     * <p>Uploads every message newer than {@code last_backup_ts} to Firestore.
     * The entire operation is capped at <b>10 seconds</b>; if time runs out the
     * method returns immediately so the caller can proceed to the destructive wipe.
     *
     * <p><strong>Must be called from a background thread.</strong>
     *
     * @param ctx Application context
     */
    public static void syncIncrementalSync(Context ctx) {
        // PERF-OPT: Increase deadline for panic sync to 10 seconds to improve reliability,
        // but prioritize the most recent messages first (timestamp DESC).
        final long DEADLINE_MS = 10_000L;
        final long startMs     = System.currentTimeMillis();

        byte[] key = BackupCryptoHelper.getStoredKey(ctx);
        if (key == null) {
            Log.d(TAG, "syncIncrementalSync: no backup key — skipping panic sync");
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.d(TAG, "syncIncrementalSync: no signed-in user — skipping panic sync");
            return;
        }
        String uid = user.getUid();

        long lastTs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                        .getLong(PREF_LAST_BAK, 0);

        try {
            // PERF-OPT: Order by timestamp DESC so the most recent messages are backed up first
            // in case the deadline is hit.
            List<Message> msgs = AppDatabase.getInstance(ctx)
                                            .messageDao()
                                            .getMessagesSinceDesc(lastTs);

            if (msgs == null || msgs.isEmpty()) {
                Log.d(TAG, "syncIncrementalSync: no new messages — nothing to panic-sync");
                return;
            }

            Log.d(TAG, "syncIncrementalSync: panic-syncing " + msgs.size()
                    + " message(s) with 10 s deadline");

            FirebaseFirestore fdb = FirebaseFirestore.getInstance();
            int written = 0;

            for (Message msg : msgs) {
                if (msg.getId() == null) continue;

                long elapsed = System.currentTimeMillis() - startMs;
                if (elapsed >= DEADLINE_MS) {
                    Log.w(TAG, "syncIncrementalSync: 10 s deadline reached after "
                            + written + "/" + msgs.size() + " messages — aborting to proceed with wipe");
                    return;
                }

                try {
                    String json = toJson(msg);
                    // S07-M3/S07-H2: bind id+conversationId+compressed into the GCM
                    // tag and replace the unkeyed checksum with a keyed HMAC.
                    byte[] aad  = BackupCryptoHelper.buildAad(msg.getId(), msg.getConversationId(), true);
                    String hmac = BackupCryptoHelper.computeHmac(key, json);
                    String enc  = BackupCryptoHelper.encryptCompressed(key, json, aad);

                    Map<String, Object> doc = new HashMap<>();
                    doc.put("enc",            enc);
                    doc.put("ts",             msg.getTimestamp());
                    doc.put("conversationId", msg.getConversationId());
                    doc.put("hmac",           hmac);
                    doc.put("compressed",     true);

                    final boolean[] success = {false};
                    final Object    lock    = new Object();

                    long remaining = DEADLINE_MS - (System.currentTimeMillis() - startMs);
                    if (remaining <= 0) {
                        Log.w(TAG, "syncIncrementalSync: deadline hit before upload of "
                                + com.duoshield.app.util.LogRedact.metadata(msg.getId()));
                        return;
                    }

                    fdb.collection(COL_BACKUPS).document(uid)
                       .collection(COL_MSGS).document(msg.getId())
                       .set(doc)
                       .addOnSuccessListener(v -> {
                           synchronized (lock) { success[0] = true; lock.notifyAll(); }
                       })
                       .addOnFailureListener(e -> {
                           Log.w(TAG, "syncIncrementalSync: upload failed for "
                                   + com.duoshield.app.util.LogRedact.metadata(msg.getId()) + ": " + com.duoshield.app.util.LogRedact.error(e));
                           synchronized (lock) { lock.notifyAll(); }
                       });

                    synchronized (lock) {
                        remaining = DEADLINE_MS - (System.currentTimeMillis() - startMs);
                        if (!success[0] && remaining > 0) lock.wait(remaining);
                    }

                    if (success[0]) written++;

                } catch (Exception e) {
                    Log.w(TAG, "syncIncrementalSync: skipping msg " + com.duoshield.app.util.LogRedact.metadata(msg.getId()) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                }
            }

            Log.d(TAG, "syncIncrementalSync: panic-sync complete — uploaded "
                    + written + "/" + msgs.size() + " messages in "
                    + (System.currentTimeMillis() - startMs) + " ms");

            // ── Panic-sync group metadata + members (best-effort, within remaining deadline) ──
            long remaining = DEADLINE_MS - (System.currentTimeMillis() - startMs);
            if (remaining > 500) {
                try {
                    AppDatabase rdb2 = AppDatabase.getInstance(ctx);
                    List<com.duoshield.app.models.Group> groups = rdb2.groupDao().getAllGroups();
                    if (groups != null && !groups.isEmpty()) {
                        for (com.duoshield.app.models.Group g : groups) {
                            if (DEADLINE_MS - (System.currentTimeMillis() - startMs) <= 0) break;
                            List<String> memberUids = new ArrayList<>();
                            try {
                                List<com.duoshield.app.models.GroupMember> mems =
                                        rdb2.groupDao().getMembersOf(g.id);
                                if (mems != null) {
                                    for (com.duoshield.app.models.GroupMember m : mems) {
                                        if (m.memberUid != null) memberUids.add(m.memberUid);
                                    }
                                }
                            } catch (Exception ignored) {}
                            String encGroupName = encMeta(key, g.name);
                            if (encGroupName == null) continue; // encryption failed — don't leak plaintext

                            Map<String, Object> gdoc = new HashMap<>();
                            gdoc.put("id",        g.id);
                            gdoc.put("name",      encGroupName);
                            gdoc.put("encMeta",   true);
                            gdoc.put("createdBy", g.createdBy != null ? g.createdBy : "");
                            gdoc.put("createdAt", g.createdAt);
                            gdoc.put("members",   memberUids);
                            fdb.collection(COL_BACKUPS).document(uid)
                               .collection(COL_GROUPS).document(g.id)
                               .set(gdoc);
                        }
                        Log.d(TAG, "syncIncrementalSync: queued group metadata panic-backup");
                    }
                } catch (Exception ge) {
                    Log.w(TAG, "syncIncrementalSync: group panic-backup failed (non-fatal)" + " (" + com.duoshield.app.util.LogRedact.error(ge) + ")");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "syncIncrementalSync: unexpected error (non-fatal)" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
    }


    // ── Contact backup / restore ────────────────────────────────────────────��─

    /**
     * Encrypts a metadata string field (contact/group display name, etc.) before
     * it leaves the device for cloud backup.
     *
     * <p><b>F5 fix:</b> contact and group metadata used to be written to
     * {@code backups/{uid}/contacts} and {@code backups/{uid}/groups} in
     * plaintext. It is now AES-256-GCM encrypted with the same per-user backup
     * key used for message bodies, so Firestore only ever sees ciphertext.
     *
     * @return the ciphertext wire string, or {@code null} if encryption failed
     *         (callers must skip writing the field rather than fall back to plaintext).
     */
    private static String encMeta(byte[] key, String plaintext) {
        try {
            return BackupCryptoHelper.encryptCompressed(key, plaintext != null ? plaintext : "");
        } catch (Exception e) {
            Log.w(TAG, "encMeta: encryption failed — field will be omitted" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    /**
     * Decrypts a metadata field written by {@link #encMeta}. Falls back to the
     * raw stored value on failure so legacy plaintext backups (written before
     * the F5 fix) still restore correctly.
     */
    private static String decMeta(byte[] key, String stored) {
        if (stored == null || stored.isEmpty() || key == null) return stored;
        try {
            return BackupCryptoHelper.decryptCompressed(key, stored);
        } catch (Exception e) {
            return stored; // legacy plaintext doc, or foreign/rotated key — best effort
        }
    }

    /**
     * Backs up all Room contacts to {@code backups/{uid}/contacts/{partnerUid}}.
     */
    public static void backupContacts(Context ctx) {
        byte[] key = BackupCryptoHelper.getStoredKey(ctx);
        if (key == null) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();
        executor.execute(() -> {
            try {
                List<Contact> contacts = AppDatabase.getInstance(ctx).contactDao().getAll();
                if (contacts == null || contacts.isEmpty()) return;
                FirebaseFirestore fdb = FirebaseFirestore.getInstance();
                for (Contact c : contacts) {
                    if (c.uid == null) continue;
                    String encName = encMeta(key, c.displayName);
                    if (encName == null) continue; // encryption failed — don't leak plaintext
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("partnerUid",     c.uid);
                    doc.put("displayName",    encName);
                    doc.put("encMeta",        true);
                    doc.put("conversationId", c.conversationId != null ? c.conversationId : "");
                    fdb.collection(COL_BACKUPS).document(uid)
                       .collection(COL_CONTACTS).document(c.uid)
                       .set(doc)
                       .addOnFailureListener(e ->
                           Log.w(TAG, "backupContacts: write failed for " + LogRedact.uid(c.uid)));
                }
                Log.d(TAG, "backupContacts: backed up " + contacts.size() + " contacts");
            } catch (Exception e) {
                Log.e(TAG, "backupContacts: error" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        });
    }

    /**
     * Restores contacts from Firestore into Room. Designed to run on a background thread.
     */
    public static int restoreContactsSync(Context ctx, String uid) {
        if (uid == null) return 0;
        int count = 0;
        byte[] key = BackupCryptoHelper.getStoredKey(ctx); // F5 fix: needed to decrypt displayName/name
        try {
            FirebaseFirestore fdb = FirebaseFirestore.getInstance();
            AppDatabase db = AppDatabase.getInstance(ctx);

            // 1. Restore Contacts
            final QuerySnapshot[] cSnap = {null};
            final Object cLock = new Object();
            fdb.collection(COL_BACKUPS).document(uid).collection(COL_CONTACTS).get()
               .addOnSuccessListener(s -> { synchronized(cLock){ cSnap[0]=s; cLock.notifyAll(); } })
               .addOnFailureListener(e -> { synchronized(cLock){ cLock.notifyAll(); } });
            synchronized(cLock){ if(cSnap[0]==null) cLock.wait(10_000); }

            if (cSnap[0] != null) {
                for (DocumentSnapshot doc : cSnap[0].getDocuments()) {
                    String pUid = doc.getString("partnerUid");
                    if (pUid == null) continue;
                    if (db.contactDao().getByUid(pUid) == null) {
                        String displayName = decMeta(key, doc.getString("displayName"));
                        db.contactDao().insert(new Contact(pUid,
                            displayName, doc.getString("conversationId")));
                        count++;
                    }
                }
            }

            // 2. Restore Groups (PERF-OPT: perfect restore of group metadata)
            final QuerySnapshot[] gSnap = {null};
            final Object gLock = new Object();
            fdb.collection(COL_BACKUPS).document(uid).collection(COL_GROUPS).get()
               .addOnSuccessListener(s -> { synchronized(gLock){ gSnap[0]=s; gLock.notifyAll(); } })
               .addOnFailureListener(e -> { synchronized(gLock){ gLock.notifyAll(); } });
            synchronized(gLock){ if(gSnap[0]==null) gLock.wait(10_000); }

            if (gSnap[0] != null) {
                for (DocumentSnapshot doc : gSnap[0].getDocuments()) {
                    String gid = doc.getString("id");
                    if (gid == null) continue;
                    if (db.groupDao().getGroupById(gid) == null) {
                        com.duoshield.app.models.Group g = new com.duoshield.app.models.Group();
                        g.id = gid;
                        g.name = decMeta(key, doc.getString("name"));
                        g.createdBy = doc.getString("createdBy") != null ? doc.getString("createdBy") : "";
                        Long ca = doc.getLong("createdAt");
                        g.createdAt = ca != null ? ca : 0;
                        db.groupDao().insertGroup(g);
                    }
                    // Restore group members from backed-up members array
                    try {
                        @SuppressWarnings("unchecked")
                        List<String> memberUids = (List<String>) doc.get("members");
                        if (memberUids != null) {
                            for (String muid : memberUids) {
                                if (muid == null) continue;
                                List<com.duoshield.app.models.GroupMember> existing =
                                        db.groupDao().getMembersOf(gid);
                                boolean found = false;
                                if (existing != null) {
                                    for (com.duoshield.app.models.GroupMember em : existing) {
                                        if (muid.equals(em.memberUid)) { found = true; break; }
                                    }
                                }
                                if (!found) {
                                    com.duoshield.app.models.GroupMember m =
                                            new com.duoshield.app.models.GroupMember();
                                    m.groupId   = gid;
                                    m.memberUid = muid;
                                    db.groupDao().insertMember(m);
                                }
                            }
                        }
                    } catch (Exception me) {
                        Log.w(TAG, "restoreContactsSync: member restore failed for group " + com.duoshield.app.util.LogRedact.metadata(gid) + " (" + com.duoshield.app.util.LogRedact.error(me) + ")");
                    }
                }
            }

            Log.d(TAG, "restoreContactsSync: restored " + count + " contacts, groups, and group members");
        } catch (Exception e) {
            Log.e(TAG, "restoreContactsSync: unexpected error" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return -1;
        }
        return count;
    }

    /**
     * Loads backup metadata (last backup timestamp + message count) for Settings display.
     */
    public static void loadMeta(String uid, OnMetaLoadedListener listener) {
        if (uid == null) { listener.onLoaded(-1, -1); return; }
        FirebaseFirestore.getInstance()
                .collection(COL_BACKUPS).document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) { listener.onLoaded(0, 0); return; }
                    Long ts    = doc.getLong("lastBackupTs");
                    Long count = doc.getLong("count");
                    listener.onLoaded(ts != null ? ts : 0, count != null ? count : 0);
                })
                .addOnFailureListener(e -> listener.onLoaded(-1, -1));
    }

    /**
     * Returns the number of messages that have not yet been synced to Firestore backup.
     * A message is considered unsynced if its timestamp is newer than {@code PREF_LAST_BAK}.
     *
     * <p>Must be called from a background thread (Room query).
     *
     * @param ctx application context
     * @return number of unsynced messages, or -1 if the DB is unavailable
     */
    public static int getUnsyncedCount(Context ctx) {
        try {
            long lastTs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                             .getLong(PREF_LAST_BAK, 0);
            List<Message> pending = AppDatabase.getInstance(ctx)
                                               .messageDao()
                                               .getMessagesSince(lastTs);
            return pending == null ? 0 : pending.size();
        } catch (Exception e) {
            Log.w(TAG, "getUnsyncedCount: error" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return -1;
        }
    }

    // ── Callback / result types ───────────────────────────────────────────────

    public static class SyncResult {
        public final int written;
        public final int failed;
        public final int total;
        SyncResult(int written, int failed, int total) {
            this.written = written; this.failed = failed; this.total = total;
        }
    }

    public interface SyncCallback {
        void onComplete(SyncResult result);
    }

    public interface OnMetaLoadedListener {
        void onLoaded(long lastBackupTs, long count);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Writes pre-encrypted message documents to Firestore using WriteBatch.
     *
     * <p>Batching reduces the number of network round trips from N (one per message)
     * to N/BATCH_SIZE (one per batch), making large backups dramatically faster.
     *
     * @param fdb       Firestore instance
     * @param uid       owner UID — documents go in backups/{uid}/messages/{docId}
     * @param docIds    parallel list of document IDs
     * @param docs      parallel list of pre-encrypted Firestore document maps
     * @return int[2] where [0] = written count, [1] = failed count
     */
    private static int[] commitBatched(FirebaseFirestore fdb, String uid,
                                       List<String> docIds,
                                       List<Map<String, Object>> docs) {
        int written = 0, failed = 0;
        int size = docIds.size();
        for (int start = 0; start < size; start += BATCH_SIZE) {
            final int batchStart = start;                         // effectively final for lambda capture
            final int batchEnd   = Math.min(start + BATCH_SIZE, size);
            WriteBatch batch = fdb.batch();
            for (int i = batchStart; i < batchEnd; i++) {
                batch.set(
                    fdb.collection(COL_BACKUPS).document(uid)
                       .collection(COL_MSGS).document(docIds.get(i)),
                    docs.get(i));
            }
            // Commit this batch and wait synchronously (we are on the backup-worker thread).
            final boolean[] success = {false};
            final Object    lock    = new Object();
            batch.commit()
                 .addOnSuccessListener(v -> { synchronized (lock) { success[0] = true; lock.notifyAll(); } })
                 .addOnFailureListener(e -> {
                     Log.w(TAG, "commitBatched: batch [" + batchStart + ".." + batchEnd
                             + ") failed: " + com.duoshield.app.util.LogRedact.error(e));
                     synchronized (lock) { lock.notifyAll(); }
                 });
            try {
                synchronized (lock) { if (!success[0]) lock.wait(15_000); }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (success[0]) {
                written += (batchEnd - batchStart);
            } else {
                // Retry once with a short back-off.
                // IMPORTANT: WriteBatch is single-use after commit() — rebuild a fresh one
                // from the same document slice; do NOT reuse the original batch instance.
                Log.w(TAG, "commitBatched: first attempt failed for batch ["
                        + batchStart + ".." + batchEnd + ") — rebuilding batch and retrying once");
                try { Thread.sleep(1_500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                WriteBatch retryBatch = fdb.batch();
                for (int i = batchStart; i < batchEnd; i++) {
                    retryBatch.set(
                        fdb.collection(COL_BACKUPS).document(uid)
                           .collection(COL_MSGS).document(docIds.get(i)),
                        docs.get(i));
                }
                final boolean[] retry = {false};
                final Object    rLock = new Object();
                retryBatch.commit()
                          .addOnSuccessListener(v -> { synchronized (rLock) { retry[0] = true; rLock.notifyAll(); } })
                          .addOnFailureListener(e -> { synchronized (rLock) { rLock.notifyAll(); } });
                try { synchronized (rLock) { if (!retry[0]) rLock.wait(15_000); } }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                if (retry[0]) {
                    written += (batchEnd - batchStart);
                } else {
                    failed += (batchEnd - batchStart);
                    Log.e(TAG, "commitBatched: batch [" + batchStart + ".." + batchEnd
                            + ") failed after retry — " + (batchEnd - batchStart) + " docs lost");
                }
            }
            Log.d(TAG, "commitBatched: batch " + (batchStart / BATCH_SIZE + 1)
                    + " done — " + written + " written so far");
        }
        return new int[]{written, failed};
    }

    private static void updateMeta(FirebaseFirestore db, String uid) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("lastBackupTs", System.currentTimeMillis());
        meta.put("count",        FieldValue.increment(1));
        db.collection(COL_BACKUPS).document(uid)
          .set(meta, SetOptions.merge())
          .addOnFailureListener(e -> Log.w(TAG, "updateMeta failed: " + com.duoshield.app.util.LogRedact.error(e)));
    }

    private static void updateMetaAbsolute(FirebaseFirestore db, String uid, int count) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("lastBackupTs", System.currentTimeMillis());
        meta.put("count",        (long) count);
        db.collection(COL_BACKUPS).document(uid)
          .set(meta, SetOptions.merge())
          .addOnFailureListener(e -> Log.w(TAG, "updateMetaAbsolute failed: " + com.duoshield.app.util.LogRedact.error(e)));
    }

    /**
     * Writes a monitoring event to backup_logs/{autoId}.
     * Firestore security rules allow owner-write only; no client-side reads.
     * Non-fatal: failures are logged but never propagate to the caller.
     */
    private static void logEvent(String uid, String event, int count, String error) {
        if (uid == null) return;
        try {
            Map<String, Object> entry = new HashMap<>();
            entry.put("uid",   uid);
            entry.put("event", event);
            entry.put("ts",    System.currentTimeMillis());
            entry.put("count", count);
            if (error != null && !error.isEmpty()) entry.put("error", error);

            FirebaseFirestore.getInstance()
                .collection(COL_LOGS)
                .add(entry)
                .addOnFailureListener(e ->
                    Log.w(TAG, "logEvent(" + event + ") failed: " + com.duoshield.app.util.LogRedact.error(e)));
        } catch (Exception e) {
            Log.w(TAG, "logEvent: unexpected error for event=" + event + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
    }

    /** Package-private for unit testing. */
    static String toJson(Message m) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id",            nvl(m.getId()));
        o.put("conversationId",nvl(m.getConversationId()));
        o.put("sender",        nvl(m.getSender()));
        o.put("text",          nvl(m.getText()));
        o.put("isEncrypted",   m.isEncrypted());
        o.put("sigType",        m.sigType);
        o.put("timestamp",     m.getTimestamp());
        o.put("mediaUrl",      nvl(m.getMediaUrl()));
        o.put("mediaType",     nvl(m.getMediaType()));
        o.put("mediaKey",      nvl(m.getMediaKey()));
        o.put("replyToId",     nvl(m.getReplyToId()));
        o.put("replyPreview",  nvl(m.getReplyPreview()));
        o.put("reaction",      nvl(m.getReaction()));
        o.put("reactions",     nvl(m.getReactions()));
        o.put("status",        nvl(m.getStatus()));
        o.put("isDeleted",     m.isDeleted());
        o.put("forwarded",     m.isForwarded());
        o.put("edited",        m.isEdited());
        o.put("starred",       m.starred);
        return o.toString();
    }

    /** Package-private for unit testing. */
    static Message fromJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        String id     = o.optString("id",             null);
        String convId = o.optString("conversationId", null);
        String sender = o.optString("sender",         null);
        String text   = o.optString("text",           "");
        boolean encrypted = o.optBoolean("isEncrypted", false);
        int sigType = o.optInt("sigType", 0);
        long   ts     = o.optLong("timestamp",        0);

        if (id == null || convId == null || sender == null) return null;

        Message m = new Message(id, convId, sender, text, ts, encrypted,
                o.optString("mediaUrl",  null),
                o.optString("mediaType", null));
        m.sigType = sigType;

        String rId   = o.optString("replyToId",    null);
        String rPrev = o.optString("replyPreview", null);
        String react = o.optString("reaction",     null);
        String reacts= o.optString("reactions",    null);
        String stat  = o.optString("status",       null);
        String mKey  = o.optString("mediaKey",     null);

        if (rId   != null && !rId.isEmpty())   m.setReplyToId(rId);
        if (rPrev != null && !rPrev.isEmpty())  m.setReplyPreview(rPrev);
        if (react != null && !react.isEmpty())  m.setReaction(react);
        if (reacts!= null && !reacts.isEmpty()) m.setReactions(reacts);
        if (stat  != null && !stat.isEmpty())   m.setStatus(stat);
        if (mKey  != null && !mKey.isEmpty())   m.setMediaKey(mKey);

        // Fields added in later Room versions — default to false for legacy docs
        m.setForwarded(o.optBoolean("forwarded", false));
        m.setEdited(o.optBoolean("edited",       false));
        m.starred = o.optBoolean("starred",      false);

        return m;
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
