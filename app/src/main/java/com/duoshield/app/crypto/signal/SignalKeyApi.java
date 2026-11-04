package com.duoshield.app.crypto.signal;

import android.util.Base64;
import android.util.Log;

import com.duoshield.app.BuildConfig;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authenticated client for the server-managed Signal public-key bundle API.
 *
 * All methods are synchronous and must be called from a background thread.
 * Private key material is never accepted by or sent through this class.
 */
final class SignalKeyApi {
    private static final String TAG = "SignalKeyApi";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private SignalKeyApi() {}

    static Map<String, Object> claimPreKey(String recipientUid) throws Exception {
        JSONObject body = new JSONObject();
        body.put("recipientUid", recipientUid);
        JSONObject response = post("/signal/claimPreKey", body);
        JSONObject bundle = response.optJSONObject("bundle");
        if (bundle == null) throw new IOException("Signal bundle response missing");
        return toMap(bundle);
    }

    static void registerBundle(Map<String, Object> bundle) throws Exception {
        JSONObject body = new JSONObject();
        body.put("bundle", toJsonObject(bundle));
        post("/signal/registerBundle", body);
    }

    static void appendPreKeys(List<Map<String, Object>> entries, int firstPreKeyId) throws Exception {
        if (entries == null || entries.isEmpty() || entries.get(0).get("id") == null
                || ((Number) entries.get(0).get("id")).intValue() != firstPreKeyId) {
            throw new IllegalArgumentException("First pre-key ID must match the append batch");
        }
        JSONObject body = new JSONObject();
        body.put("firstPreKeyId", firstPreKeyId);
        body.put("oneTimePreKeys", toJsonArray(entries));
        post("/signal/appendPreKeys", body);
    }

    static void updatePreKey(String field, Map<String, Object> value) throws Exception {
        JSONObject body = new JSONObject();
        body.put("field", field);
        body.put("value", toJsonObject(value));
        post("/signal/updatePreKey", body);
    }

    private static JSONObject post(String path, JSONObject body) throws Exception {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) throw new IOException("Not authenticated");
        String token = getFreshIdToken(user);
        if (token == null || token.isEmpty()) throw new IOException("Unable to obtain Firebase ID token");
        String base = BuildConfig.PUSH_SERVER_URL == null
                ? "" : BuildConfig.PUSH_SERVER_URL.trim();
        if (base.isEmpty()) throw new IOException("Push server URL is not configured");

        HttpURLConnection connection = null;
        try {
            URL url = new URL(base.replaceAll("/+$", "") + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int code = connection.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            String payload = stream == null ? "" : readBounded(stream);
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Signal API returned HTTP " + code + " for " + path);
                throw new IOException("Signal key service rejected request (HTTP " + code + ")");
            }
            try {
                return payload.isEmpty() ? new JSONObject() : new JSONObject(payload);
            } catch (JSONException e) {
                throw new IOException("Signal key service returned invalid JSON", e);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String getFreshIdToken(FirebaseUser user) throws Exception {
        final Object lock = new Object();
        final String[] token = {null};
        final Exception[] failure = {null};
        final boolean[] finished = {false};
        user.getIdToken(true)
                .addOnSuccessListener(result -> {
                    synchronized (lock) {
                        token[0] = result.getToken();
                        finished[0] = true;
                        lock.notifyAll();
                    }
                })
                .addOnFailureListener(error -> {
                    synchronized (lock) {
                        failure[0] = new IOException("Firebase token refresh failed", error);
                        finished[0] = true;
                        lock.notifyAll();
                    }
                });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
            while (!finished[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) throw new IOException("Timed out refreshing Firebase ID token");
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while refreshing Firebase ID token", e);
                }
            }
        }
        if (failure[0] != null) throw failure[0];
        return token[0];
    }

    private static String readBounded(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("Signal API response too large");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JSONObject toJsonObject(Map<String, Object> map) throws JSONException {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) value;
                result.put(entry.getKey(), toJsonObject(child));
            } else if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> child = (List<Map<String, Object>>) value;
                result.put(entry.getKey(), toJsonArray(child));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static JSONArray toJsonArray(List<Map<String, Object>> list) throws JSONException {
        JSONArray result = new JSONArray();
        for (Map<String, Object> item : list) result.put(toJsonObject(item));
        return result;
    }

    private static Map<String, Object> toMap(JSONObject object) throws JSONException {
        Map<String, Object> result = new LinkedHashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.get(key);
            if (value instanceof JSONObject) {
                result.put(key, toMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                result.put(key, toList((JSONArray) value));
            } else if (value == JSONObject.NULL) {
                result.put(key, null);
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    private static List<Object> toList(JSONArray array) throws JSONException {
        List<Object> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONObject) {
                result.add(toMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                result.add(toList((JSONArray) value));
            } else if (value == JSONObject.NULL) {
                result.add(null);
            } else {
                result.add(value);
            }
        }
        return result;
    }
}
