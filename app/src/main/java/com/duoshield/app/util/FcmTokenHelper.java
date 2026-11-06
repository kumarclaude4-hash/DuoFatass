package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper for registering and unregistering the FCM token in Firestore.
 *
 * <h3>New-account race</h3>
 * {@link FirebaseMessaging#getToken()} is async. If the UID isn't in SharedPrefs or
 * FirebaseAuth yet when the callback fires (e.g. the user is mid-onboarding), the upload
 * is retried up to 3 times with 3 s → 6 s → 12 s exponential back-off rather than
 * silently dropped.  The push server requires {@code users/{uid}.fcmToken} to exist
 * before it can deliver notifications.
 *
 * <h3>Per-device registry (S08-H5 item 4c)</h3>
 * In addition to the legacy single {@code users/{uid}.fcmToken} field (kept for
 * backward compatibility with the existing push server delivery path), this
 * helper now also writes a per-device document at
 * {@code users/{uid}/devices/{deviceId}} keyed by {@link DeviceIdProvider}. This
 * is what lets a login on a NEW device leave every PRIOR device's token intact,
 * so the server's restore-race logic has real targets to warn. The single-field
 * write and the per-device write are issued together; the per-device one is the
 * source of truth for multi-device fan-out.
 */
public class FcmTokenHelper {

    private static final String TAG      = "FcmTokenHelper";
    private static final int    MAX_RETRY = 3;

    public static void register(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                if (token == null || token.isEmpty()) {
                    Log.w(TAG, "register: received null/empty FCM token — skipping upload");
                    return;
                }
                prefs.edit().putString("fcm_token", token).apply();
                uploadWithRetry(ctx.getApplicationContext(), token, 0);
            })
            .addOnFailureListener(e ->
                Log.w(TAG, "register: getToken() failed: " + com.duoshield.app.util.LogRedact.error(e)));
    }

    /**
     * Uploads {@code token} to Firestore under {@code users/{uid}.fcmToken}.
     * Retries up to {@value #MAX_RETRY} times with exponential back-off when the UID
     * is not yet available (fresh-install race between getToken() and sign-in completion).
     */
    private static void uploadWithRetry(Context appCtx, String token, int attempt) {
        SharedPreferences prefs = appCtx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);

        String myUid = prefs.getString("my_uid", null);
        if (myUid == null || myUid.isEmpty()) {
            myUid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        }

        if (myUid != null && !myUid.isEmpty()) {
            final String uid = myUid;
            // Legacy single-token field: kept so the existing push delivery path
            // keeps working during rollout. Overwrites on each device, as before.
            Map<String, Object> data = new HashMap<>();
            data.put("fcmToken",  token);
            data.put("platform",  "android");
            data.put("updatedAt", FieldValue.serverTimestamp());
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .set(data, SetOptions.merge())
                .addOnSuccessListener(v -> Log.d(TAG, "FCM token uploaded successfully"))
                .addOnFailureListener(e -> Log.w(TAG, "FCM token upload failed: " + com.duoshield.app.util.LogRedact.error(e)));

            // Per-device registry (item 4c): one doc per install, so a new-device
            // login never erases another device's token. This is the fan-out
            // source of truth the server's restore-race notify reads from.
            String deviceId = DeviceIdProvider.get(appCtx);
            Map<String, Object> dev = new HashMap<>();
            dev.put("fcmToken",  token);
            dev.put("platform",  "android");
            dev.put("updatedAt", FieldValue.serverTimestamp());
            // No client-set createdAt: with merge it would be clobbered on every
            // refresh. First-seen tracking is the server's job (it records the
            // device on the first /mintToken it observes for this deviceId).
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("devices").document(deviceId)
                .set(dev, SetOptions.merge())
                .addOnSuccessListener(v -> Log.d(TAG, "Per-device token registered: " + deviceId))
                .addOnFailureListener(e -> Log.w(TAG, "Per-device token upload failed: " + com.duoshield.app.util.LogRedact.error(e)));
        } else if (attempt < MAX_RETRY) {
            long delayMs = 3000L * (1L << attempt); // 3 s, 6 s, 12 s
            Log.d(TAG, "register: uid not ready, retry " + (attempt + 1) + " in " + delayMs + "ms");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> uploadWithRetry(appCtx, token, attempt + 1), delayMs);
        } else {
            Log.w(TAG, "register: uid still null after " + MAX_RETRY + " retries — token not uploaded");
        }
    }

    public static void unregister(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String myUid = prefs.getString("my_uid", null);
        if (myUid == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("fcmToken", "");
        FirebaseFirestore.getInstance()
            .collection("users").document(myUid)
            .set(data, SetOptions.merge());

        // Retire THIS device's registry entry so a signed-out device is not left
        // as a stale push target. Only this install's deviceId is removed; other
        // devices keep their own entries. The device may re-create it on next
        // register() (the rule allows owner delete + re-create).
        String deviceId = DeviceIdProvider.get(ctx);
        FirebaseFirestore.getInstance()
            .collection("users").document(myUid)
            .collection("devices").document(deviceId)
            .delete();

        prefs.edit().remove("fcm_token").apply();
    }
}
