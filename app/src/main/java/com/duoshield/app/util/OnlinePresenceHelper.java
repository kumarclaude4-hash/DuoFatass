package com.duoshield.app.util;

import android.content.Context;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;

public class OnlinePresenceHelper {

    /**
     * Marks this device as online.
     *
     * <p>Guarded by {@link FirebaseCostGuard} — one Firestore write per call.
     * Without the guard, rapid foreground/background transitions in {@code BaseActivity}
     * could exhaust the daily write budget (BUG-D11).
     */
    public static void setOnline(Context ctx, String convId, String myUid) {
        if (convId == null || myUid == null) return;
        if (!FirebaseCostGuard.getInstance(ctx).reserveWrite(1)) return;
        Map<String, Object> data = new HashMap<>();
        data.put("online_" + myUid, true);
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .set(data, SetOptions.merge());
    }

    /**
     * Marks this device as offline and records the last-seen timestamp.
     *
     * <p>Guarded by {@link FirebaseCostGuard} (BUG-D11).
     */
    public static void setOffline(Context ctx, String convId, String myUid) {
        if (convId == null || myUid == null) return;
        if (!FirebaseCostGuard.getInstance(ctx).canWrite(1)) return;
        Map<String, Object> data = new HashMap<>();
        data.put("online_"   + myUid, false);
        data.put("lastSeen_" + myUid, FieldValue.serverTimestamp());
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .set(data, SetOptions.merge());
    }
}
