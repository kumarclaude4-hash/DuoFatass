package com.duoshield.app.util;

import android.content.Context;

public class FirebaseQuotaSummary {

    public static String get(Context ctx) {
        FirebaseCostGuard g = FirebaseCostGuard.getInstance(ctx);
        return "Device-estimated reads today: " + g.getReads() + " / 4,000\n"
             + "Device-estimated writes today: " + g.getWrites() + " / 1,600\n"
             + "Device-estimated deletes today: " + g.getDeletes() + " / 1,600\n"
             + "Per-installation allowance for a 10-user profile; local estimates only, not a project-wide quota enforcer.";
    }
}
