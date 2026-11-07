package com.duoshield.app.util;

import android.content.Context;

public class FirebaseQuotaSummary {

    public static String get(Context ctx) {
        FirebaseCostGuard g = FirebaseCostGuard.getInstance(ctx);
        return "Device-estimated reads today: " + g.getReads() + " / 40,000\n"
             + "Device-estimated writes today: " + g.getWrites() + " / 16,000\n"
             + "Device-estimated deletes today: " + g.getDeletes() + " / 16,000\n"
             + "Local app estimates only; not the shared Firebase project total.";
    }
}
