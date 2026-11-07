package com.duoshield.app.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.firestore.FirebaseFirestore;
import com.duoshield.app.util.FirebaseCostGuard;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

public class PresenceThrottle {

    private final Context context;
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private final String   convId;
    private final String   myUid;
    private boolean        currentValue = false;

    public PresenceThrottle(Context context, String convId, String myUid) {
        this.context = context.getApplicationContext();
        this.convId = convId;
        this.myUid  = myUid;
    }

    public void setTyping(boolean typing) {
        if (typing) {
            if (!currentValue) {
                currentValue = true;
                write(true);
            }
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                currentValue = false;
                write(false);
            }, 3000);
        } else {
            if (currentValue) {
                currentValue = false;
                handler.removeCallbacksAndMessages(null);
                write(false);
            }
        }
    }

    private void write(boolean value) {
        if (convId == null || myUid == null) return;
        if (!FirebaseCostGuard.getInstance(context).reserveWrite(1)) return;
        Map<String, Object> data = new HashMap<>();
        data.put("typing_" + myUid, value);
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .set(data, SetOptions.merge())
            .addOnFailureListener(e ->
                android.util.Log.w("PresenceThrottle",
                    "Failed to write typing state (" + e.getClass().getSimpleName() + ")"));
    }

    public void clear() {
        handler.removeCallbacksAndMessages(null);
        currentValue = false;
        write(false);
    }
}
