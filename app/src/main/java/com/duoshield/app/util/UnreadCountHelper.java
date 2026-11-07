package com.duoshield.app.util;

import android.content.Context;

import com.google.firebase.firestore.FirebaseFirestore;

public class UnreadCountHelper {
    public static void reset(Context context, String convId, String myUid) {
        if (convId == null || myUid == null) return;
        if (!FirebaseCostGuard.getInstance(context).reserveWrite(1)) return;
        FirebaseFirestore.getInstance().collection("chats").document(convId)
            .update("unread_" + myUid, 0L);
    }
}
