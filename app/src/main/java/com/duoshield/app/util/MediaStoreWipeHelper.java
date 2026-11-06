package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

/**
 * Tracks MediaStore URIs that DuoShield has written to the gallery (Pictures/DuoShield,
 * Movies/DuoShield) and deletes them on wipe or duress logout.
 *
 * F37 fix: saved photos/videos previously survived both wipe paths because neither
 * WipeHelper.wipeAll() nor DuressManager.performLogout() knew which URIs had been
 * written to the public gallery.  This helper closes that gap.
 *
 * Storage: URI strings are persisted as a JSON array in "duoshield_prefs" under the
 * key "saved_media_uris".  This file is cleared by both wipe paths, so the list is
 * self-cleaning after a wipe completes.
 *
 * Threading: recordUri() may be called from any thread (SharedPrefs writes are
 * thread-safe for our single-writer pattern).  wipeAll() must be called from a
 * background thread because ContentResolver.delete() can be slow.
 */
public class MediaStoreWipeHelper {

    private static final String TAG      = "MediaStoreWipeHelper";
    private static final String PREFS    = "duoshield_prefs";
    private static final String KEY_URIS = "saved_media_uris";

    // Guards the read-modify-write in recordUri() against concurrent gallery saves
    // (e.g. user taps Save on two images in quick succession from different threads).
    private static final Object RECORD_LOCK = new Object();

    /** Call this immediately after a successful ContentResolver.insert() for gallery saves. */
    public static void recordUri(Context ctx, Uri uri) {
        if (uri == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (RECORD_LOCK) {
            String raw = prefs.getString(KEY_URIS, "[]");
            try {
                JSONArray arr = new JSONArray(raw);
                arr.put(uri.toString());
                prefs.edit().putString(KEY_URIS, arr.toString()).commit(); // commit() for atomicity
            } catch (JSONException e) {
                Log.w(TAG, "Failed to record media URI — resetting list (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                JSONArray arr = new JSONArray();
                arr.put(uri.toString());
                prefs.edit().putString(KEY_URIS, arr.toString()).commit();
            }
        }
    }

    /**
     * Deletes every gallery URI that was recorded via recordUri().
     * Must be called on a background thread.
     * The "duoshield_prefs" clear that follows in both wipe paths will also remove KEY_URIS,
     * so there is no risk of a stale list on the next sign-in.
     */
    public static void wipeAll(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_URIS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            int deleted = 0;
            for (int i = 0; i < arr.length(); i++) {
                String uriStr = arr.optString(i, null);
                if (uriStr == null) continue;
                try {
                    Uri uri = Uri.parse(uriStr);
                    int rows = ctx.getContentResolver().delete(uri, null, null);
                    if (rows > 0) deleted++;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to delete gallery URI " + com.duoshield.app.util.LogRedact.metadata(uriStr) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                }
            }
            Log.d(TAG, "wipeAll: deleted " + deleted + "/" + arr.length() + " gallery items");
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse saved_media_uris — nothing deleted", e);
        }
        // Clear the list now; the outer prefs.clear() will also cover it, but being
        // explicit here means the list is gone even if the caller forgets to clear prefs.
        prefs.edit().remove(KEY_URIS).apply();
    }
}
