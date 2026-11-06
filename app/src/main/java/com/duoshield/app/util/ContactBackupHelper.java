package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Contact;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Backs up and restores the contacts table to a SEPARATE SharedPreferences file
 * ({@code "duoshield_contacts_bak"}) that is NOT cleared during a Wipe & Exit.
 *
 * <p>Usage contract:
 * <ul>
 *   <li>{@link #backup} — called by {@link WipeHelper} BEFORE wiping Room DB.
 *       Must run on a background thread (Room reads).</li>
 *   <li>{@link #restoreIfNeeded} — called by ConversationListActivity after
 *       sign-in. Inserts contacts back into Room if the stored owner UID matches
 *       the current Firebase UID. Must run on a background thread.</li>
 *   <li>{@link #clearBackup} — called by {@link com.duoshield.app.security.DuressManager}
 *       during duress logout to prevent recovery after a security wipe.</li>
 * </ul>
 */
public class ContactBackupHelper {

    private static final String TAG        = "ContactBackupHelper";
    private static final String PREFS_NAME = "duoshield_contacts_bak";
    private static final String KEY_OWNER  = "owner_uid";
    private static final String KEY_DATA   = "contacts_json";

    private ContactBackupHelper() {}

    /**
     * Reads all contacts from Room and writes them as JSON to the backup prefs file.
     * Must be called on a background thread.
     *
     * @param ctx      application context
     * @param ownerUid Firebase UID of the current user (used to gate restore)
     * @param contacts list returned by {@code contactDao().getAll()}
     */
    public static void backup(Context ctx, String ownerUid, List<Contact> contacts) {
        if (ownerUid == null || contacts == null || contacts.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (Contact c : contacts) {
                JSONObject o = new JSONObject();
                o.put("uid",            c.uid);
                o.put("displayName",    c.displayName    != null ? c.displayName    : "");
                o.put("conversationId", c.conversationId != null ? c.conversationId : "");
                o.put("avatarUrl",      c.avatarUrl      != null ? c.avatarUrl      : "");
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit()
               .putString(KEY_OWNER, ownerUid)
               .putString(KEY_DATA,  arr.toString())
               .apply();
            Log.d(TAG, "Backed up " + contacts.size() + " contacts.");
        } catch (Exception e) {
            Log.e(TAG, "backup() failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
    }

    /**
     * If a backup exists for {@code myUid}, inserts all backed-up contacts into
     * the Room DB and clears the backup file. No-op if backup is absent or belongs
     * to a different user.
     * Must be called on a background thread.
     *
     * @param ctx   application context
     * @param myUid Firebase UID of the currently signed-in user
     */
    public static void restoreIfNeeded(Context ctx, String myUid) {
        if (myUid == null) return;
        SharedPreferences bak = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String ownerUid = bak.getString(KEY_OWNER, null);
        String json     = bak.getString(KEY_DATA,  null);
        if (ownerUid == null || json == null || !ownerUid.equals(myUid)) return;

        try {
            JSONArray arr = new JSONArray(json);
            AppDatabase db = AppDatabase.getInstance(ctx);
            int restored = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Contact c = new Contact(
                        o.getString("uid"),
                        o.optString("displayName", ""),
                        o.optString("conversationId", ""));
                String av = o.optString("avatarUrl", "");
                if (!av.isEmpty()) c.avatarUrl = av;
                db.contactDao().insert(c);
                restored++;
            }
            bak.edit().clear().apply();
            Log.d(TAG, "Restored " + restored + " contacts from backup.");
        } catch (Exception e) {
            Log.e(TAG, "restoreIfNeeded() failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
    }

    /**
     * Wipes the backup file. Called by {@link com.duoshield.app.security.DuressManager}
     * so that a duress-triggered wipe cannot be recovered by a subsequent sign-in.
     */
    public static void clearBackup(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
           .edit().clear().apply();
    }
}
