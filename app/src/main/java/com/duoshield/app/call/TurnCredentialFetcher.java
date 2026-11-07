package com.duoshield.app.call;

import android.util.Log;

import com.duoshield.app.BuildConfig;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Fetches fresh Cloudflare TURN credentials from the push server's
 * {@code POST /turnCredentials} endpoint and stores them in
 * {@link TurnCredentialCache}.
 *
 * <p>Designed for SIM / CGNAT users: since all calls are forced through TURN
 * relay ({@code IceTransportsType.RELAY}), having valid credentials before ICE
 * starts is mandatory — not optional.  This class therefore retries failed
 * network attempts before giving up.
 *
 * <p>Call {@link #prefetch} early (e.g., in {@code CallActivity.onCreate}) so
 * credentials are ready before {@link CallManager#startCall} /
 * {@link CallManager#acceptCall} fires.  Subsequent calls within the 1-hour
 * TTL are no-ops.
 */
public class TurnCredentialFetcher {

    private static final String TAG  = "TurnCredFetcher";
    private static final MediaType JSON_TYPE =
            MediaType.get("application/json; charset=utf-8");

    /**
     * How many times to attempt the credential fetch before giving up.
     * SIM networks can have transient 2-3 s connectivity blips; 3 attempts
     * with a 2-second gap between each covers the majority of those cases.
     */
    private static final int  MAX_RETRIES       = 3;
    private static final long RETRY_DELAY_MS    = 2_000L;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    public interface Callback {
        void onResult(boolean success);
    }

    /** Fire-and-forget prefetch; no callback. */
    public static void prefetch() {
        prefetch(null);
    }

    /**
     * Fetches TURN credentials if the cache is empty or expired.
     * Retries up to {@link #MAX_RETRIES} times on failure.
     * Calls {@code callback.onResult(true/false)} on a background thread.
     */
    public static void prefetch(Callback callback) {
        if (TurnCredentialCache.get().isValid()) {
            if (callback != null) callback.onResult(true);
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "No signed-in user — skipping TURN credential fetch");
            if (callback != null) callback.onResult(false);
            return;
        }

        // getIdToken runs on the main thread; the retry loop moves to a bg thread.
        user.getIdToken(/* forceRefresh= */ false)
            .addOnSuccessListener(result -> {
                String idToken = result.getToken();
                if (idToken == null) {
                    Log.w(TAG, "getIdToken returned null");
                    if (callback != null) callback.onResult(false);
                    return;
                }
                new Thread(() -> doFetchWithRetry(idToken, MAX_RETRIES, callback),
                        "turn-cred-fetch").start();
            })
            .addOnFailureListener(e -> {
                Log.w(TAG, "getIdToken failed: " + com.duoshield.app.util.LogRedact.error(e));
                if (callback != null) callback.onResult(false);
            });
    }

    // ── Network call with retry (runs on background thread) ──────────────────

    /**
     * Attempts the credential fetch; if it fails and {@code retriesLeft > 0},
     * sleeps {@link #RETRY_DELAY_MS} then recurses.  Critical for SIM users
     * whose data radio may take 1-2 s to wake from sleep (3GPP RRC idle→active).
     */
    private static void doFetchWithRetry(String idToken, int retriesLeft, Callback callback) {
        boolean ok = doFetch(idToken);
        if (ok) {
            if (callback != null) callback.onResult(true);
            return;
        }
        if (retriesLeft > 0) {
            Log.w(TAG, "TURN fetch failed — retrying in " + RETRY_DELAY_MS
                    + " ms (" + retriesLeft + " attempt(s) left)");
            try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ignored) {}
            doFetchWithRetry(idToken, retriesLeft - 1, callback);
        } else {
            Log.e(TAG, "TURN credential fetch failed after " + MAX_RETRIES + " attempts");
            if (callback != null) callback.onResult(false);
        }
    }

    /**
     * Executes one HTTP attempt.  Returns {@code true} on success (credentials
     * stored in cache), {@code false} on any error.
     */
    private static boolean doFetch(String idToken) {
        String baseUrl = BuildConfig.PUSH_SERVER_URL;
        if (baseUrl == null || baseUrl.isEmpty()) {
            Log.w(TAG, "PUSH_SERVER_URL not configured — cannot fetch TURN credentials");
            return false;
        }

        Request request = new Request.Builder()
                .url(baseUrl + "/turnCredentials")
                .addHeader("Authorization", "Bearer " + idToken)
                .post(RequestBody.create("{}", JSON_TYPE))
                .build();

        try (Response httpResult = CLIENT.newCall(request).execute()) {
            if (!httpResult.isSuccessful()) {
                Log.w(TAG, "TURN credential fetch failed with HTTP status " + httpResult.code());
                return false;
            }

            String body = httpResult.body() != null ? httpResult.body().string() : "{}";
            JSONObject json = new JSONObject(body);

            JSONArray urlsArr = json.getJSONArray("urls");
            String[] urls = new String[urlsArr.length()];
            for (int i = 0; i < urlsArr.length(); i++) urls[i] = urlsArr.getString(i);

            String username   = json.getString("username");
            String credential = json.getString("credential");

            TurnCredentialCache.get().set(urls, username, credential);
            Log.d(TAG, "TURN credentials cached — " + urls.length + " URL(s)");
            return true;

        } catch (Exception e) {
            Log.w(TAG, "TURN credential fetch attempt failed: " + com.duoshield.app.util.LogRedact.error(e));
            return false;
        }
    }
}
