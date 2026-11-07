package com.duoshield.app.db;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import android.content.Context;
import android.util.Log;
import com.duoshield.app.models.Contact;
import com.duoshield.app.models.Group;
import com.duoshield.app.models.GroupMember;
import com.duoshield.app.models.Message;
import com.duoshield.app.models.SignalSessionRecord;
import com.duoshield.app.models.OutboxMessage;
import com.duoshield.app.db.CallRecord;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SupportFactory;

@Database(
    entities = {
        Message.class, SignalSessionRecord.class, OutboxMessage.class,
        Contact.class, Group.class, GroupMember.class, CallRecord.class
    },
    version = 30
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract MessageDao      messageDao();
    public abstract OutboxDao       outboxDao();
    public abstract SignalSessionDao signalSessionDao();
    public abstract ContactDao      contactDao();
    public abstract GroupDao        groupDao();
    public abstract CallHistoryDao  callHistoryDao();

    private static final String TAG = "AppDatabase";

    /**
     * BUG-D01 fix: open the Room database with SQLCipher AES-256 full-database
     * encryption.  The passphrase is a random 32-byte key generated on first
     * launch and stored in EncryptedSharedPreferences (see {@link DatabaseKeyProvider}).
     *
     * <p>If the database file was created before this change (plain SQLite),
     * SQLCipher cannot open it and throws an exception.  In that case we delete
     * the old unencrypted file and create a fresh encrypted one.  For a security
     * app this is the correct trade-off: privacy is worth more than migrating an
     * old plaintext cache.
     */
    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            SQLiteDatabase.loadLibs(context);
            // May throw DatabaseKeyProvider.KeyUnavailableException when a database
            // exists but its passphrase is temporarily unreadable. That is
            // deliberately NOT caught here: the data is still intact and may open
            // on a later launch once the Keystore recovers. Swallowing it would
            // send us into the destructive branch below and wipe the user's
            // entire message history (SEC-D02).
            byte[] passphrase = DatabaseKeyProvider.getOrCreate(context);
            SupportFactory factory = new SupportFactory(passphrase);
            java.util.Arrays.fill(passphrase, (byte) 0); // zero out after handing to factory
            try {
                instance = buildDatabase(context.getApplicationContext(), factory);
                // Force-open to detect a pre-existing unencrypted file early
                instance.getOpenHelper().getWritableDatabase();
            } catch (Exception e) {
                try { if (instance != null) instance.close(); } catch (Exception ignored) {}
                instance = null;

                // Only destroy the file for the one case this recovery exists for:
                // a legacy *unencrypted* database that SQLCipher cannot interpret.
                // Any other failure (failed migration, disk full, transient I/O)
                // must propagate — deleting on those turned recoverable faults
                // into permanent, silent loss of every message (SEC-D02).
                if (!isNotADatabaseError(e)) {
                    Log.e(TAG, "Database open failed and is NOT a legacy-plaintext upgrade — preserving the existing file (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                    throw new IllegalStateException("Unable to open encrypted database", e);
                }

                Log.w(TAG, "SQLCipher could not open existing DB — deleting and recreating encrypted (BUG-D01 first-run upgrade): " + com.duoshield.app.util.LogRedact.error(e));
                context.deleteDatabase("duoshield_db");
                // Re-derive passphrase (was zeroed above)
                byte[] p2 = DatabaseKeyProvider.getOrCreate(context);
                SupportFactory f2 = new SupportFactory(p2);
                java.util.Arrays.fill(p2, (byte) 0);
                instance = buildDatabase(context.getApplicationContext(), f2);
            }
        }
        return instance;
    }

    /**
     * True when the throwable chain indicates the file is not a SQLCipher
     * database at all — i.e. the legacy plaintext SQLite file that the
     * BUG-D01 upgrade is allowed to discard. SQLCipher reports this as
     * "file is not a database" / "file is encrypted or is not a database".
     */
    private static boolean isNotADatabaseError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(java.util.Locale.ROOT);
                if (m.contains("not a database") || m.contains("file is encrypted")) {
                    return true;
                }
            }
            if (c.getCause() == c) break;
        }
        return false;
    }

    private static AppDatabase buildDatabase(Context appCtx, SupportFactory factory) {
        return Room.databaseBuilder(appCtx, AppDatabase.class, "duoshield_db")
            .openHelperFactory(factory)
            // WAL mode: readers don't block the writer and vice-versa; yields
            // significantly lower latency when Firestore callbacks write to Room
            // while the UI thread reads (e.g. seeding the chat list on open).
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,                 MIGRATION_26_27,
                MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30)

            .addCallback(new RoomDatabase.Callback() {
                @Override public void onCreate(SupportSQLiteDatabase db) {
                    createSearchIndex(db);
                }
            })
            .build();
    }

    /**
     * Closes and nulls the singleton so the next {@link #getInstance} call opens a
     * fresh connection.  Must be called immediately after {@code ctx.deleteDatabase()}
     * in every wipe or duress-logout path — otherwise the cached singleton points to
     * the deleted file and subsequent DAO calls silently fail or recreate a stale DB.
     */
    public static synchronized void clearInstance() {
        if (instance != null) {
            try { instance.close(); } catch (Exception ignored) {}
            instance = null;
        }
    }

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS Message");
            db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY," +
                " conversationId TEXT, sender TEXT, text TEXT," +
                " timestamp INTEGER NOT NULL DEFAULT 0, isEncrypted INTEGER NOT NULL DEFAULT 0)");
        }
    };

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaUrl TEXT");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaType TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN delivered INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN seen INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToId TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN replyPreview TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN reaction TEXT");
        }
    };

    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT");
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS session_events " +
                "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "event_type TEXT, " +
                "timestamp INTEGER NOT NULL DEFAULT 0, " +
                "device_model TEXT, " +
                "android_version TEXT)");
        }
    };

    // v9: Signal Protocol session store.
    // address = "{firebaseUid}.{deviceId}" (always deviceId=1 for DuoShield's 1-to-1 model).
    // session_data = raw bytes of a serialised libsignal SessionRecord (Double Ratchet state).
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS signal_sessions (" +
                "address TEXT NOT NULL PRIMARY KEY, " +
                "session_data BLOB NOT NULL, " +
                "updated_at INTEGER NOT NULL DEFAULT 0)");
        }
    };

    // v10: Signal message type column.
    // sigType = 0 → legacy ECDH (no Signal); 2 → SignalMessage (WHISPER); 3 → PreKeySignalMessage.
    // NOT NULL DEFAULT 0 ensures all existing rows get the legacy-ECDH sentinel automatically.
    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN sigType INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v11: Per-media AES-256-GCM key column.
    // mediaKey = Base64-encoded 32-byte AES key generated per upload.
    // NULL for legacy messages (plaintext media from before this version).
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaKey TEXT");
        }
    };

    // v12: Group conversations.
    // contacts   — contacts added via ContactManager.addContact().
    // groups     — group conversations with shared AES-256 group key.
    // group_members — per-group member roster.
    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `contacts` (" +
                "`uid` TEXT NOT NULL, " +
                "`displayName` TEXT, " +
                "`conversationId` TEXT, " +
                "`avatarUrl` TEXT, " +
                "PRIMARY KEY(`uid`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `groups` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`avatarUrl` TEXT, " +
                "`createdBy` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`groupKey` TEXT, " +
                "`lastMessage` TEXT, " +
                "`lastMessageTs` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_members` (" +
                "`groupId` TEXT NOT NULL, " +
                "`memberUid` TEXT NOT NULL, " +
                "`displayName` TEXT, " +
                "`joinedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`groupId`, `memberUid`))");
        }
    };

    // v13: Starred messages.
    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v18: Persist voice-note waveform amplitudes so a chat reopened from local
    // Room cache shows the real recorded/received waveform instead of a
    // synthetic fake one (amplitudes was previously @Ignore-d).
    static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN amplitudes TEXT");
        }
    };

    // v19: Persist voice-note total duration so bubbles show the real recorded
    // length at rest instead of a static "0:00" placeholder.
    static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v14: Soft delete. isDeleted = 1 hides a message from the UI and excludes it
    // from backups without removing the Room row (preserves Signal ratchet continuity).
    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v17: Remove session_events table (session logging removed — privacy principle).
    static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS session_events");
        }
    };

    // v16: Add call_history table for local-only call log.
    static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS call_history (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "partnerId TEXT NOT NULL, " +
                    "partnerName TEXT NOT NULL, " +
                    "isVideo INTEGER NOT NULL DEFAULT 0, " +
                    "direction TEXT NOT NULL, " +
                    "outcome TEXT NOT NULL, " +
                    "startedAt INTEGER NOT NULL, " +
                    "durationSeconds INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_call_history_startedAt ON call_history (startedAt)");
        }
    };

    /**
     * v23: Add the {@code thumb} column — a base64, AES-GCM sealed ~1.5 KB JPEG preview
     * carried inside the message row so a photo or video bubble can paint immediately
     * instead of waiting on a full B2 download and decrypt.
     *
     * <p>Nullable with no default on purpose. Rows written before this version genuinely
     * have no thumbnail, and a sentinel like {@code ''} would be indistinguishable from
     * "generation failed" at the render site. NULL lets the adapter fall through to the
     * existing download path without ambiguity, so the upgrade is non-destructive and
     * old media keeps working exactly as before.
     */
    static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN thumb TEXT");
        }
    };

    /**
     * v24: Add the {@code chunked} flag — whether this message's media is stored in the
     * chunked, range-addressable v2 format (1 MiB independently authenticated chunks, so a
     * video can start playing and seek while still downloading) or the original whole-blob
     * AES-GCM layout.
     *
     * <p>NOT NULL DEFAULT 0 rather than nullable, which is the opposite of
     * {@link #MIGRATION_22_23}'s reasoning and deliberately so: {@code thumb} needed NULL
     * because "no thumbnail" and "generation failed" are different states a renderer has to
     * tell apart, whereas here every pre-existing row genuinely holds whole-blob media. The
     * default is the truth for old rows, not a sentinel standing in for one, so the upgrade is
     * non-destructive and existing media keeps decrypting through the unchanged legacy path.
     */
    /**
     * v25: per-user message reactions.
     *
     * <p>Adds a nullable {@code reactions} column holding a JSON uid → emoji object. The
     * original {@code reaction} column (added back in {@link #MIGRATION_4_5}) is deliberately
     * left in place rather than migrated or dropped: it still carries reactions written by
     * peers running older builds, and {@code Message.getReactionsMap()} reconciles the two at
     * read time. Nullable — and no backfill — because "no reactions" and "empty map" are the
     * same state here, so old rows need no rewriting.
     */
    static final Migration MIGRATION_24_25 = new Migration(24, 25) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reactions TEXT");
        }
    };

    /**
     * v26: Add the {@code recordingPath} column to {@code call_history} — the absolute local
     * path to the {@code .m4a} audio recording of a call, or NULL for calls that were not
     * recorded. Nullable with no default: "not recorded" and "recording since deleted" are the
     * same NULL state, and the history UI keys its play affordance off a non-null value, so old
     * rows need no backfill and the upgrade is non-destructive.
     */
    static final Migration MIGRATION_25_26 = new Migration(25, 26) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE call_history ADD COLUMN recordingPath TEXT");
        }
    };

    /**
     * v27: encrypted-local full-text index for already-decrypted message text.
     * The index lives inside the SQLCipher database and is never uploaded. It is
     * maintained by SQLite triggers so Room, Firestore listeners, retries, edits,
     * tombstones, and optimistic sends all follow the same invariant.
     */
    static final Migration MIGRATION_29_30 = new Migration(29, 30) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE outbox_messages ADD COLUMN waveformJson TEXT");
            db.execSQL("ALTER TABLE outbox_messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_28_29 = new Migration(28, 29) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE outbox_messages ADD COLUMN mediaPath TEXT");
            db.execSQL("ALTER TABLE outbox_messages ADD COLUMN mediaKey TEXT");
            db.execSQL("ALTER TABLE outbox_messages ADD COLUMN thumbnail TEXT");
            db.execSQL("ALTER TABLE outbox_messages ADD COLUMN caption TEXT");
            db.execSQL("ALTER TABLE outbox_messages ADD COLUMN chunked INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_27_28 = new Migration(27, 28) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS outbox_messages (" +
                    "id TEXT NOT NULL PRIMARY KEY, conversationId TEXT, recipientUid TEXT, " +
                    "senderUid TEXT, ciphertext TEXT, sigType INTEGER NOT NULL, " +
                    "messageType TEXT, replyToId TEXT, replyPreview TEXT, " +
                    "expiresAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                    "attemptCount INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, lastError TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_messages_nextAttemptAt " +
                    "ON outbox_messages(nextAttemptAt)");
        }
    };

    static final Migration MIGRATION_26_27 = new Migration(26, 27) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_search_fts USING fts5(" +
                    "message_id UNINDEXED, conversation_id UNINDEXED, text, " +
                    "sender UNINDEXED, timestamp UNINDEXED, media_type UNINDEXED, " +
                    "starred UNINDEXED, is_deleted UNINDEXED, tokenize='unicode61')");
            createSearchIndex(db);
        }
    };

    private static void createSearchIndex(SupportSQLiteDatabase db) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_search_fts USING fts5(" +
                "message_id UNINDEXED, conversation_id UNINDEXED, text, " +
                "sender UNINDEXED, timestamp UNINDEXED, media_type UNINDEXED, " +
                "starred UNINDEXED, is_deleted UNINDEXED, tokenize='unicode61')");
        db.execSQL("INSERT INTO message_search_fts " +
                "(message_id, conversation_id, text, sender, timestamp, media_type, starred, is_deleted) " +
                "SELECT m.id, m.conversationId, m.text, m.sender, m.timestamp, m.mediaType, m.starred, m.isDeleted " +
                "FROM messages AS m WHERE m.isEncrypted = 0 AND m.text IS NOT NULL AND m.text != '' " +
                "AND m.text NOT LIKE '[Decrypting%' AND m.text != '\u26d4 Message deleted' " +
                "AND NOT EXISTS (SELECT 1 FROM message_search_fts AS f WHERE f.message_id = m.id)");
        createSearchTriggers(db);
    }

    private static void createSearchTriggers(SupportSQLiteDatabase db) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_search_ai AFTER INSERT ON messages " +
                "WHEN new.isEncrypted = 0 AND new.text IS NOT NULL AND new.text != '' " +
                "AND new.text NOT LIKE '[Decrypting%' AND new.text != '\u26d4 Message deleted' " +
                "BEGIN INSERT INTO message_search_fts " +
                "(message_id, conversation_id, text, sender, timestamp, media_type, starred, is_deleted) " +
                "VALUES (new.id, new.conversationId, new.text, new.sender, new.timestamp, new.mediaType, new.starred, new.isDeleted); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_search_au AFTER UPDATE ON messages BEGIN " +
                "DELETE FROM message_search_fts WHERE message_id = old.id; " +
                "INSERT INTO message_search_fts " +
                "(message_id, conversation_id, text, sender, timestamp, media_type, starred, is_deleted) " +
                "SELECT new.id, new.conversationId, new.text, new.sender, new.timestamp, new.mediaType, new.starred, new.isDeleted " +
                "WHERE new.isEncrypted = 0 AND new.text IS NOT NULL AND new.text != '' " +
                "AND new.text NOT LIKE '[Decrypting%' AND new.text != '\u26d4 Message deleted'; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_search_ad AFTER DELETE ON messages BEGIN " +
                "DELETE FROM message_search_fts WHERE message_id = old.id; END");
    }


    static final Migration MIGRATION_23_24 = new Migration(23, 24) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN chunked INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v21: Add forwarded column to messages — tracks messages forwarded from other conversations.
    static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            // caption: text caption shown beneath a photo/video in the same bubble
            db.execSQL("ALTER TABLE messages ADD COLUMN caption TEXT NOT NULL DEFAULT ''");
            // media_items: JSON array for multi-photo/video album messages
            db.execSQL("ALTER TABLE messages ADD COLUMN media_items TEXT");
        }
    };

    static final Migration MIGRATION_20_21 = new Migration(20, 21) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v20: Add indices on contacts(displayName) and signal_sessions(address).
    // contacts is loaded with ORDER BY displayName ASC on every list open; without an index
    // SQLite does a full table scan + sort. signal_sessions is looked up by address on every
    // Signal encrypt/decrypt; the index turns that from O(n) to O(log n).
    static final Migration MIGRATION_19_20 = new Migration(19, 20) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_displayName ON contacts (displayName)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_signal_sessions_address ON signal_sessions (address)");
        }
    };

    // v15: Performance optimization — add secondary indices on frequently queried columns.
    // These indices dramatically speed up message retrieval, expiry cleanup, and status queries
    // without changing the schema or breaking encryption/disappearing message logic.
    static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            // Composite index for efficient message retrieval by conversation and timestamp
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_timestamp ON messages (conversationId, timestamp)");
            // Index for efficient expiry cleanup in SelfDestructWorker
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_expiresAt ON messages (expiresAt)");
            // Index for efficient undelivered message queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_status_sender ON messages (status, sender)");
            // Index for efficient group member lookups
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)");
            // Index for efficient group queries by creation timestamp
            db.execSQL("CREATE INDEX IF NOT EXISTS index_groups_createdAt ON groups (createdAt)");
        }
    };
}
