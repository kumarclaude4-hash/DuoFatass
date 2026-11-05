package com.duoshield.app.util;

import android.os.Handler;
import android.os.Looper;
import com.duoshield.app.BuildConfig;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches Open Graph / title metadata for a URL by routing requests through the
 * DuoShield push server's {@code /linkPreview} endpoint.
 *
 * <p><b>F12 fix:</b> the previous implementation fetched target URLs directly
 * from the sender's device, exposing the sender's real IP address to the target
 * server.  All preview fetches now go through the push-server proxy — only the
 * server's IP is ever seen by the target origin.
 *
 * <p>Results are bounded in-memory cache (up to {@link #MAX_CACHE} entries) so
 * each URL is only fetched once per session.  All callbacks run on the main thread.
 */
public class LinkPreviewFetcher {

    // ── Public API types ──────────────────────────────────────────────────────

    /** Stable, non-sensitive outcome returned by the server-side fetcher. */
    public enum Status {
        OK,
        NO_METADATA,
        UNSUPPORTED_CONTENT,
        BLOCKED,
        TIMEOUT,
        UPSTREAM_ERROR,
        UNAVAILABLE;

        public static Status fromWire(String value, boolean hasMetadata) {
            if (value == null) {
                // Backward compatibility applies only when the field is absent
                // from a pre-status server response.
                return hasMetadata ? OK : NO_METADATA;
            }
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.isEmpty()) return UNAVAILABLE;
            switch (normalized) {
                case "ok": return OK;
                case "no_metadata": return NO_METADATA;
                case "unsupported_content": return UNSUPPORTED_CONTENT;
                case "blocked": return BLOCKED;
                case "timeout": return TIMEOUT;
                case "upstream_error": return UPSTREAM_ERROR;
                case "unavailable": return UNAVAILABLE;
                default: return UNAVAILABLE;
            }
        }

        /** Only safe, deterministic outcomes should render a preview card. */
        public boolean isDisplayable() {
            return this == OK || this == NO_METADATA || this == UNSUPPORTED_CONTENT;
        }
    }

    public static class Preview {
        public final String url;
        public final String title;
        public final String domain;
        public final String imageUrl;
        public final Status status;

        /** Legacy constructor retained for in-process callers and older tests. */
        public Preview(String url, String title, String domain, String imageUrl) {
            this(url, title, domain, imageUrl,
                    (title != null || imageUrl != null) ? Status.OK : Status.NO_METADATA);
        }

        public Preview(String url, String title, String domain, String imageUrl, Status status) {
            this.url      = url;
            this.title    = title;
            this.domain   = domain;
            this.imageUrl = imageUrl;
            this.status   = status == null ? Status.UNAVAILABLE : status;
        }

        public boolean isDisplayable() {
            return status.isDisplayable();
        }
    }

    public interface Callback {
        void onResult(Preview preview);
    }

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Bounded in-memory LRU cache. Successful results and failed lookups each
     * occupy one entry, preventing repeated requests for a URL that has already
     * failed. The cap of 200 entries limits memory use (BUG-LP01).
     */
    private static final int                   MAX_CACHE   = 200;
    private static final LinkPreviewCache<Preview> cache = new LinkPreviewCache<>(MAX_CACHE);
    private static final ExecutorService       executor    = Executors.newFixedThreadPool(2);
    private static final Handler               mainHandler = new Handler(Looper.getMainLooper());

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch preview for a URL.  Returns cached value immediately if available,
     * otherwise fetches via the server in background.  Callback always runs on
     * the main thread.  {@code null} is passed back when the fetch fails.
     */
    public static void fetch(String url, Callback callback) {
        LinkPreviewCache.Lookup<Preview> cached = cache.get(url);
        if (cached.found) {
            callback.onResult(cached.value);
            return;
        }
        executor.execute(() -> {
            Preview preview = fetchViaServer(url);
            // The cache represents a failed fetch with an explicit negative
            // entry; it never inserts null into a map implementation that
            // forbids null values. The bounded LRU cache also evicts atomically.
            cache.put(url, preview);
            mainHandler.post(() -> callback.onResult(preview));
        });
    }

    /** Call on sign-out or app wipe to release cached previews. */
    public static void clearCache() {
        cache.clear();
    }

    // ── Server-proxied fetch ──────────────────────────────────────────────────

    /**
     * Makes a POST to {@code /linkPreview} on the push server with a Firebase
     * ID token for authentication.  The server fetches the URL and returns
     * extracted OG metadata so neither the sender's nor receiver's device ever
     * contacts the target URL directly.
     */
    private static Preview fetchViaServer(String urlStr) {
        HttpURLConnection conn = null;
        try {
            String idToken = getIdTokenSync();
            if (idToken == null) return null;

            URL endpoint = new URL(BuildConfig.PUSH_SERVER_URL + "/linkPreview");
            conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + idToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);

            JSONObject body = new JSONObject();
            body.put("url", urlStr);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                    // The server contract is tiny JSON. Bound client parsing too,
                    // so an unexpected/misconfigured endpoint cannot grow memory.
                    if (sb.length() > 64 * 1024) return null;
                }
            }

            JSONObject resp = new JSONObject(sb.toString());
            String title = resp.has("title") && !resp.isNull("title")
                    ? resp.getString("title") : null;
            String domain = resp.has("domain") && !resp.isNull("domain")
                    ? resp.getString("domain") : null;
            String imageUrl = resp.has("imageUrl") && !resp.isNull("imageUrl")
                    ? resp.getString("imageUrl") : null;
            Status status = Status.fromWire(
                    resp.isNull("status") ? null : resp.optString("status", null),
                    title != null || imageUrl != null);
            return new Preview(urlStr, title, domain, imageUrl, status);
        } catch (Exception e) {
            // Network and parse failures remain a null callback result; the
            // stable server-side categories are represented by Preview.status.
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Synchronously retrieves the current user's Firebase ID token.
     * Safe to call from background threads — uses a lock/notify pattern
     * compatible with the Firebase tasks API.
     */
    private static String getIdTokenSync() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return null;

        final String[] holder = {null};
        final Object   lock   = new Object();
        user.getIdToken(false)
            .addOnSuccessListener(r -> {
                synchronized (lock) { holder[0] = r.getToken(); lock.notifyAll(); }
            })
            .addOnFailureListener(e -> {
                synchronized (lock) { lock.notifyAll(); }
            });
        synchronized (lock) {
            if (holder[0] == null) {
                try { lock.wait(8_000); } catch (InterruptedException ignored) {}
            }
        }
        return holder[0];
    }
}
