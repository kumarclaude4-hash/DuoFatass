package com.duoshield.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import com.duoshield.app.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/**
 * Backblaze B2 storage helper — S3-compatible API, AWS Signature V4, AES-256-GCM E2EE.
 *
 * SECURITY RULES:
 *  1. All bytes are AES-256-GCM encrypted BEFORE upload using a per-file random key.
 *  2. The per-file key (Base64) is stored in the Firestore message doc as "mediaKey".
 *  3. Downloads are public (bucket allows public GET) — safe because content is E2EE.
 *  4. On-wire format (v1, whole-blob): [12-byte IV | ciphertext | 16-byte GCM auth tag].
 *     Large new video uploads instead use the chunked v2 format documented at
 *     {@link #CHUNKED_FORMAT_VERSION}, which is range-addressable so playback can
 *     start and seek while the object is still downloading. Which format an object
 *     uses is recorded on the message ("chunked"), never sniffed from its bytes.
 *  5. Paths are stored with "b2:" prefix to distinguish from Supabase paths.
 *
 * Firestore message doc:
 * {
 *   "type":     "image" | "video",
 *   "path":     "b2:media/<chatId>/<uuid>.jpg",
 *   "mediaKey": "<Base64 AES-256 key>",
 *   "isEncrypted": true,
 *   ...
 * }
 */
public final class B2StorageHelper {

    public static String getEndpoint() {
        String ep = BuildConfig.B2_ENDPOINT == null ? "" : BuildConfig.B2_ENDPOINT.trim();
        return ep.isEmpty() ? "https://s3.ca-east-006.backblazeb2.com" : ep;
    }
    public static String getRegion() {
        String r = BuildConfig.B2_REGION == null ? "" : BuildConfig.B2_REGION.trim();
        return r.isEmpty() ? "ca-east-006" : r;
    }
    private static String getKeyId() {
        return BuildConfig.B2_KEY_ID == null ? "" : BuildConfig.B2_KEY_ID.trim();
    }
    private static String getAppKey() {
        return BuildConfig.B2_APPLICATION_KEY == null ? "" : BuildConfig.B2_APPLICATION_KEY.trim();
    }
    /**
     * Returns the Cloudflare Worker URL for tiered storage, or empty string if not configured.
     * When non-empty, all PUT/GET/DELETE calls route through this Worker — no SigV4 needed.
     */
    public static String getWorkerUrl() {
        String w = BuildConfig.WORKER_URL == null ? "" : BuildConfig.WORKER_URL.trim();
        // Strip trailing slash so we can always append "/{key}" cleanly
        return w.endsWith("/") ? w.substring(0, w.length() - 1) : w;
    }
    /**
     * SEC-A01: the shared {@code WORKER_SECRET} is no longer used to authorize the
     * media data plane. GET/PUT/DELETE now carry a per-object capability token
     * minted by the push server ({@link #fetchMediaCapabilityToken}). The Worker
     * only still accepts {@code WORKER_SECRET} on its operator-only {@code /stats}
     * endpoint, which the app never calls.
     */
    /** Returns the Firebase UID for the X-Client-ID rate-limit header, or "anon". */
    private static String getClientId() {
        com.google.firebase.auth.FirebaseUser user =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        return (user != null && user.getUid() != null) ? user.getUid() : "anon";
    }
    /** @deprecated Use {@link #getEndpoint()} */
    public static final String ENDPOINT       = "https://s3.ca-east-006.backblazeb2.com";
    /** @deprecated Use {@link #getRegion()} */
    public static final String REGION         = "ca-east-006";
    public static final String SERVICE        = "s3";
    public static final String B2_PATH_PREFIX = "b2:";

    private static final int    CONNECT_TIMEOUT_MS  = 15_000;
    private static final int    READ_TIMEOUT_MS     = 30_000;
    private static final int    BUFFER_SIZE         = 32_768;
    private static final int    MAX_RETRIES         = 3;
    private static final long   INITIAL_BACKOFF_MS  = 1_000L;
    private static final long   MAX_BACKOFF_MS      = 30_000L;

    // ── Singleton OkHttpClient — shared across all B2 requests for TCP/TLS reuse ──────────────
    // ConnectionPool keeps up to 5 idle sockets alive for 5 minutes.
    // The first request pays the full TCP + TLS handshake cost; every subsequent
    // request to the same host reuses the open socket and costs only the HTTP round-trip
    // (~180-250 ms vs. ~900-1400 ms for a cold connection from India to ca-east-006).
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    // ── In-memory decrypted-media cache (adaptive: 1/5 of max heap, min 32 MB, max 96 MB) ─────
    private static final LruCache<String, byte[]> MEDIA_CACHE;
    /**
     * Largest single entry allowed into {@link #MEDIA_CACHE} (a quarter of the cache).
     *
     * <p>Without this cap the cache is a cache in name only. {@link LruCache} evicts
     * least-recently-used entries until the new value fits, so putting one 60 MB video
     * plaintext into a 96 MB cache flushes every avatar and image preview that the
     * conversation list and chat scroll depend on — the entries that actually benefit
     * from caching, since they are small, re-read constantly, and cheap to hold. Worse,
     * an entry larger than {@code maxSize} can never fit at all: {@code trimToSize}
     * evicts everything, including the value just inserted, so the video pays the full
     * eviction cost and still is not cached. A single video view therefore trashed the
     * whole cache for no benefit whatsoever.
     *
     * <p>Video playback and gallery saves no longer route plaintext through a
     * {@code byte[]} at all (see {@link #loadMediaToFile}), so refusing oversized
     * entries here costs nothing: the disk cache still absorbs the repeat-view case.
     */
    private static final int MAX_CACHE_ENTRY_BYTES;
    static {
        long maxHeap  = Runtime.getRuntime().maxMemory();
        int  cacheMax = (int) Math.min(96L * 1024 * 1024,
                               Math.max(32L * 1024 * 1024, maxHeap / 5));
        MEDIA_CACHE = new LruCache<String, byte[]>(cacheMax) {
            @Override protected int sizeOf(String key, byte[] value) { return value.length; }
        };
        MAX_CACHE_ENTRY_BYTES = cacheMax / 4;
    }

    /**
     * Inserts into {@link #MEDIA_CACHE} only when the value is small enough to be worth
     * caching. Every write to the media cache must go through here rather than calling
     * {@code MEDIA_CACHE.put} directly — see {@link #MAX_CACHE_ENTRY_BYTES} for why an
     * unbounded put is actively harmful.
     */
    private static void cachePut(String b2Path, byte[] value) {
        if (b2Path == null || value == null) return;
        if (value.length > MAX_CACHE_ENTRY_BYTES) {
            Log.d(TAG, "cachePut: skipping oversized entry " + b2Path
                    + " (" + value.length + " B > " + MAX_CACHE_ENTRY_BYTES + " B cap)");
            return;
        }
        MEDIA_CACHE.put(b2Path, value);
    }

    // ── In-flight request deduplication ──────────────────────────────────────
    // If 10 list rows all request the same avatar/media before the first download
    // completes, without this they would each fire a separate network call and
    // decryption. With this map, only the first fires; the rest queue on it and
    // all receive the result together once the single download finishes.
    private static final ConcurrentHashMap<String, List<MediaCallback>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    // ── Bounded thread pool for concurrent media downloads ───────────────────
    // Adaptive: 2 threads for low-RAM devices (JVM heap ≤ 256 MB, e.g. POCO C51
    // with 2–3 GB RAM where MIUI leaves ~192 MB for the app), 3 threads otherwise.
    // Each thread holds an encrypted + decrypted buffer simultaneously (up to 2×
    // the file size), so reducing from 3→2 cuts peak buffer pressure by ~33 MB
    // on a 10 MB video download — meaningful when total usable heap is ~150 MB.
    private static final ExecutorService MEDIA_POOL;
    static {
        long maxHeap = Runtime.getRuntime().maxMemory();
        int  threads = (maxHeap <= 256L * 1024 * 1024) ? 2 : 3;
        MEDIA_POOL = Executors.newFixedThreadPool(threads);
    }
    private static final int    GCM_IV_LEN         = 12;
    private static final int    GCM_TAG_LEN        = 128;
    private static final String TAG                = "B2Storage";
    private static final String EMPTY_BODY_HASH    =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private B2StorageHelper() {}

    // ── Retry infrastructure ────────────���─────────────────────────────────────

    /**
     * Thrown for HTTP 4xx responses — configuration errors that won't be fixed
     * by retrying (wrong credentials, bucket not found, bad request format).
     */
    static final class NonRetryableException extends IOException {
        final int httpCode;
        NonRetryableException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    @FunctionalInterface
    private interface B2Operation<T> {
        T call() throws Exception;
    }

    /**
     * Executes {@code op} with up to {@link #MAX_RETRIES} automatic retries
     * on transient network failures, using exponential backoff.
     *
     * <ul>
     *   <li>{@link NonRetryableException} (HTTP 4xx) — re-thrown immediately, no retry.</li>
     *   <li>Any other {@link Exception} (IOException, timeout, 5xx) — retried up to
     *       {@link #MAX_RETRIES} times with delays of 1 s, 2 s, 4 s (capped at 30 s).</li>
     * </ul>
     *
     * @param opName  Operation label. Any object-key suffix is pseudonymised before logging.
     * @param op      The B2 network operation to execute.
     */
    private static <T> T withRetry(String opName, B2Operation<T> op) throws Exception {
        // Callers include B2 object keys (and sometimes ranges derived from them).
        // Preserve the operation label while pseudonymising its sensitive suffix.
        String safeOperation = redactOperationName(opName);
        long backoff = INITIAL_BACKOFF_MS;
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return op.call();
            } catch (NonRetryableException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_RETRIES) {
                    Log.w(TAG, safeOperation + " failed (attempt " + (attempt + 1) + "/" + MAX_RETRIES
                            + "), retrying in " + backoff + " ms — " + com.duoshield.app.util.LogRedact.error(e));
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                } else {
                    Log.e(TAG, safeOperation + " failed after " + MAX_RETRIES + " retries." + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                }
            }
        }
        throw last;
    }

    /**
     * Redacts the sensitive suffix of an operation label while preserving the
     * operation prefix for diagnostics. Object keys can contain account,
     * conversation and message identifiers.
     */
    public static String redactOperationName(String operation) {
        if (operation == null || operation.isEmpty()) return "storage operation";
        int separator = operation.indexOf(':');
        if (separator < 0 || separator == operation.length() - 1) {
            return com.duoshield.app.util.LogRedact.metadata(operation);
        }
        String prefix = operation.substring(0, separator + 1);
        String sensitiveSuffix = operation.substring(separator + 1);
        // Range requests append extra colon-delimited fields; redact the complete
        // suffix as one value so neither object keys nor ranges survive in logcat.
        return prefix + com.duoshield.app.util.LogRedact.metadata(sensitiveSuffix);
    }

    // ── Connection pre-warming ────────────────────────────────────────────────

    /**
     * Pre-warms the TCP + TLS connection to B2 on a new daemon thread.
     * Call once at app startup (e.g. from {@code DuoShieldApp.onCreate()}).
     * The HTTP response code does not matter — the goal is only to establish
     * and pool the socket so every subsequent upload/download skips the
     * ~700-1000 ms handshake overhead.
     */
    public static void warmConnection() {
        new Thread(() -> {
            try {
                String warmUrl = !getWorkerUrl().isEmpty()
                        ? getWorkerUrl() + "/health"
                        : getEndpoint() + "/" + getBucket() + "/";
                Request r = new Request.Builder().url(warmUrl).head().build();
                try (Response resp = HTTP_CLIENT.newCall(r).execute()) {
                    Log.d(TAG, "Storage connection pre-warmed (HTTP " + resp.code() + ").");
                }
            } catch (Exception e) {
                Log.d(TAG, "Storage pre-warm (non-fatal): " + e.getMessage());
            }
        }, "b2-warmup").start();
    }

    // ── Path helpers ─────────────────────────────────────────────────────────

    /** Returns true when value is a B2 storage path (starts with "b2:"). */
    public static boolean isB2Path(String value) {
        return value != null && value.startsWith(B2_PATH_PREFIX);
    }

    /**
     * F10 fix — verifies a B2 path belongs to the given conversation before deletion.
     *
     * <p>All paths written by this app follow the pattern
     * {@code b2:media/<conversationId>/...} or {@code b2:voice/<conversationId>/...}.
     * A crafted message doc with an attacker-chosen {@code path} value (e.g. pointing at
     * another conversation's media) would pass the plain {@link #isB2Path(String)} check,
     * so every deletion path that is driven by a Firestore document's {@code path} field
     * (SelfDestructWorker, B2CleanupWorker, GroupChatActivity) must call this method
     * instead of (or in addition to) {@link #isB2Path(String)}.
     *
     * @param b2Path         the path field read from a Firestore message document.
     * @param conversationId the conversation / group ID the message belongs to.
     * @return {@code true} only if the path is a valid B2 path AND its second segment
     *         equals {@code conversationId}.
     */
    public static boolean isOwnedB2Path(String b2Path, String conversationId) {
        if (!isB2Path(b2Path) || conversationId == null) return false;
        // Object key after the "b2:" prefix: "media/<convId>/..." or "voice/<convId>/..."
        String objectKey = toObjectKey(b2Path);
        String[] parts = objectKey.split("/", 3);
        // parts[0] = "media" or "voice", parts[1] = conversationId, parts[2] = filename
        return parts.length >= 2 && conversationId.equals(parts[1]);
    }

    /** Strips the "b2:" prefix to get the raw S3 object key. */
    public static String toObjectKey(String b2Path) {
        return b2Path.startsWith(B2_PATH_PREFIX)
                ? b2Path.substring(B2_PATH_PREFIX.length()) : b2Path;
    }

    /** Constructs the download URL for a B2 path (Worker or direct B2 endpoint). */
    public static String toPublicUrl(String b2Path) {
        String objectKey = toObjectKey(b2Path);
        String worker = getWorkerUrl();
        if (!worker.isEmpty()) {
            return worker + "/" + objectKey;
        }
        return getEndpoint() + "/" + getBucket() + "/" + objectKey;
    }

    public static String getBucket() {
        String b = (BuildConfig.B2_BUCKET == null) ? "" : BuildConfig.B2_BUCKET.trim();
        return b.isEmpty() ? "DuoShield-" : b;
    }

    /** Returns the first 4 and last 4 chars of the key ID, masked in the middle. */
    public static String getMaskedKeyId() {
        if (!getWorkerUrl().isEmpty()) return "(via Cloudflare Worker)";
        String k = BuildConfig.B2_KEY_ID == null ? "" : BuildConfig.B2_KEY_ID.trim();
        if (k.isEmpty()) return "(not set — using push-server presign)";
        if (k.length() <= 8) return k.substring(0, 2) + "****";
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    /**
     * Returns true when storage credentials are available via any path:
     * Cloudflare Worker URL, direct B2 key pair, or push-server presign.
     */
    public static boolean areCredentialsConfigured() {
        return !getWorkerUrl().isEmpty()
                || (!getKeyId().isEmpty() && !getAppKey().isEmpty());
    }

    /**
     * Same as {@link #testConnection()} but also returns how many milliseconds
     * the round-trip took. Thread-safe; call from a background thread.
     *
     * @return A two-element array: [errorMessage_or_null, latencyMs_string]
     */
    public static String[] testConnectionTimed() {
        long start = System.currentTimeMillis();
        String err = testConnection();
        long latencyMs = System.currentTimeMillis() - start;
        return new String[]{ err, String.valueOf(latencyMs) };
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** Encrypted bytes + the Base64 key to store in Firestore. */
    public static final class EncryptedMedia {
        public final byte[] data;
        public final String keyBase64;
        EncryptedMedia(byte[] data, String keyBase64) {
            this.data = data; this.keyBase64 = keyBase64;
        }
    }

    public interface ProgressCallback {
        /** Called on a background thread. */
        void onProgress(int percent);
    }

    public interface MediaCallback {
        void onLoaded(byte[] plainBytes);
        void onError(Exception e);
    }

    /**
     * Loads raw avatar bytes (profile/partner photos — uploaded as plain JPEGs,
     * not AES-GCM encrypted like message media) via an authenticated SigV4 GET.
     *
     * <p>Avatars were previously exposed to the UI as a "public" URL from
     * {@link #toPublicUrl(String)} and handed straight to Glide. The bucket only
     * accepts SigV4-signed requests (see {@link #downloadFile(String)}), so an
     * unauthenticated Glide fetch of that URL always returned 403 — the exact
     * "Photo uploaded, but couldn't load the preview" symptom. Route avatars
     * through the same authenticated pipeline as every other B2 object instead.
     */
    public static void loadAvatarBytes(String b2Path, MediaCallback cb) {
        if (b2Path == null || b2Path.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onError(new IOException("Avatar path is null or empty")));
            return;
        }
        byte[] cached = MEDIA_CACHE.get(b2Path);
        if (cached != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(cached));
            return;
        }
        // Deduplication: queue behind any already-in-flight request for this path
        synchronized (IN_FLIGHT) {
            List<MediaCallback> waiters = IN_FLIGHT.get(b2Path);
            if (waiters != null) { waiters.add(cb); return; }
            List<MediaCallback> list = new ArrayList<>();
            list.add(cb);
            IN_FLIGHT.put(b2Path, list);
        }
        MEDIA_POOL.execute(() -> {
            List<MediaCallback> waiters;
            synchronized (IN_FLIGHT) { waiters = IN_FLIGHT.remove(b2Path); }
            if (waiters == null) return;
            try {
                byte[] raw = downloadFile(b2Path);
                cachePut(b2Path, raw);
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (MediaCallback c : waiters) c.onLoaded(raw);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadAvatarBytes failed: " + com.duoshield.app.util.LogRedact.metadata(b2Path) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (MediaCallback c : waiters) c.onError(e);
                });
            }
        });
    }

    /**
     * OkHttp {@link RequestBody} that streams a byte array in {@link #BUFFER_SIZE}-chunks
     * and reports progress via {@link ProgressCallback} so the UI progress bar stays smooth.
     * Re-entrant: {@code writeTo()} can be called multiple times (needed for retries).
     */
    private static final class ProgressRequestBody extends RequestBody {
        private final byte[]           data;
        private final MediaType        mediaType;
        private final ProgressCallback cb;

        ProgressRequestBody(byte[] data, MediaType mediaType, ProgressCallback cb) {
            this.data = data;
            this.mediaType = mediaType;
            this.cb = cb;
        }

        @Override public MediaType contentType()   { return mediaType; }
        @Override public long      contentLength() { return data.length; }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            int written = 0;
            while (written < data.length) {
                int len = Math.min(BUFFER_SIZE, data.length - written);
                sink.write(data, written, len);
                written += len;
                if (cb != null) cb.onProgress((int) (100L * written / data.length));
            }
        }
    }

    // ── AES-256-GCM encryption ────────────────────────────────────────────────

    /**
     * AES-256-GCM encrypts {@code plain} with a fresh random key.
     * On-wire: [12-byte IV | ciphertext | 16-byte GCM auth tag]
     */
    public static EncryptedMedia encryptForUpload(byte[] plain) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey key = kg.generateKey();
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        byte[] ct = cipher.doFinal(plain);
        byte[] data = new byte[GCM_IV_LEN + ct.length];
        System.arraycopy(iv, 0, data, 0, GCM_IV_LEN);
        System.arraycopy(ct, 0, data, GCM_IV_LEN, ct.length);
        return new EncryptedMedia(data, Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP));
    }

    /**
     * AES-256-GCM encrypts {@code plain} with an <em>existing</em> key.
     *
     * <p>Used for inline thumbnails: the thumbnail must be sealed under the exact same
     * key as the full-size media it previews, otherwise the tiny preview would be
     * readable by anyone who can read the Firestore document even when the media
     * itself is not. Same wire format as {@link #encryptForUpload} so
     * {@link #decryptAfterDownload} decrypts it unchanged.
     */
    public static byte[] encryptWithKey(byte[] plain, String keyBase64) throws Exception {
        if (keyBase64 == null || keyBase64.isEmpty()) return plain;
        byte[] decodedKey = Base64.decode(keyBase64, Base64.NO_WRAP);
        SecretKey key = new SecretKeySpec(decodedKey, "AES");
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[GCM_IV_LEN + ct.length];
        System.arraycopy(iv, 0, out, 0, GCM_IV_LEN);
        System.arraycopy(ct, 0, out, GCM_IV_LEN, ct.length);
        return out;
    }

    /** AES-256-GCM decrypts bytes received from B2. Inverse of {@link #encryptForUpload}. */
    public static byte[] decryptAfterDownload(byte[] data, String keyBase64) throws Exception {
        if (keyBase64 == null || keyBase64.isEmpty()) return data;
        if (data.length < GCM_IV_LEN + 16)
            throw new IOException("Encrypted blob too short: " + data.length);
        byte[] decodedKey = Base64.decode(keyBase64, Base64.NO_WRAP);
        SecretKey key = new SecretKeySpec(decodedKey, "AES");
        byte[] iv = Arrays.copyOfRange(data, 0, GCM_IV_LEN);
        byte[] ct = Arrays.copyOfRange(data, GCM_IV_LEN, data.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        return cipher.doFinal(ct);
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Uploads {@code data} to B2 at {@code objectKey} using S3 PutObject + AWS SigV4.
     *
     * <p>Automatically retries up to {@link #MAX_RETRIES} times on transient network errors
     * with exponential backoff (1 s, 2 s, 4 s). HTTP 4xx responses are not retried.
     *
     * <p>Uses the shared {@link #HTTP_CLIENT} connection pool — first call pays TCP + TLS
     * overhead; subsequent calls to the same endpoint reuse the open socket.
     *
     * @return "b2:<objectKey>" — store this as the Firestore "path" field.
     * @throws NonRetryableException for HTTP 4xx (bad credentials, bad request).
     * @throws Exception             on network error after all retries exhausted.
     */
    public static String uploadFile(byte[] data, String objectKey,
                                    String contentType, ProgressCallback cb) throws Exception {
        // Tiered-storage Worker path (highest priority)
        if (!getWorkerUrl().isEmpty()) {
            return uploadViaWorker(data, objectKey, contentType, cb);
        }
        // F9 fix: when B2 credentials are not baked into the APK, obtain a
        // server-generated presigned PUT URL and upload directly — the B2 key
        // never touches the device in new builds.
        if (getKeyId().isEmpty()) {
            return uploadViaPresignedUrl(data, objectKey, contentType, cb);
        }
        String bucket = getBucket();
        String region = getRegion();
        String host   = "s3." + region + ".backblazeb2.com";

        return withRetry("upload:" + objectKey, () -> {
            Date now         = new Date();
            String dateStamp = utcFormat("yyyyMMdd", now);
            String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
            String bodyHash  = sha256Hex(data);
            String urlStr    = getEndpoint() + "/" + bucket + "/" + objectKey;

            String canonicalHeaders =
                    "content-length:" + data.length + "\n"
                    + "content-type:" + contentType + "\n"
                    + "host:" + host + "\n"
                    + "x-amz-content-sha256:" + bodyHash + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "content-length;content-type;host;x-amz-content-sha256;x-amz-date";
            String canonicalUri  = "/" + bucket + "/" + objectKey;

            String canonicalRequest = "PUT\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + bodyHash;
            String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey   = getSigningKey(dateStamp, region);
            String signature    = hmacSha256Hex(signingKey, stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential="
                    + getKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            MediaType mt   = MediaType.parse(contentType);
            RequestBody rb = new ProgressRequestBody(data, mt, cb);
            Request request = new Request.Builder()
                    .url(urlStr)
                    .put(rb)
                    .addHeader("Authorization",        authorization)
                    .addHeader("x-amz-date",           amzDate)
                    .addHeader("x-amz-content-sha256", bodyHash)
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 201) {
                    Log.d(TAG, "B2 uploaded: " + objectKey);
                    return B2_PATH_PREFIX + objectKey;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "B2 upload failed [" + code + "]: " + err);
                }
                throw new IOException("B2 upload failed [" + code + "]: " + err);
            }
        });
    }

    // ── Download (authenticated SigV4 GET) ───────────────────────────────────

    /**
     * Downloads raw encrypted bytes from B2 using a SigV4-authenticated GET.
     * Automatically retries on transient failures; HTTP 4xx is not retried.
     */
    public static byte[] downloadFile(String b2Path) throws Exception {
        // Tiered-storage Worker path (highest priority)
        if (!getWorkerUrl().isEmpty()) {
            return downloadViaWorker(b2Path);
        }
        // F9 fix: bucket is public-read (content is E2EE — no plaintext stored
        // at rest). Skip SigV4 when credentials are absent; use a simple GET.
        if (getKeyId().isEmpty()) {
            return downloadViaPublicUrl(b2Path);
        }
        String objectKey = toObjectKey(b2Path);
        String bucket    = getBucket();
        String region    = getRegion();
        String host      = "s3." + region + ".backblazeb2.com";

        return withRetry("download:" + objectKey, () -> {
            Date now         = new Date();
            String dateStamp = utcFormat("yyyyMMdd", now);
            String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
            String urlStr    = getEndpoint() + "/" + bucket + "/" + objectKey;

            String canonicalHeaders =
                    "host:" + host + "\n"
                    + "x-amz-content-sha256:" + EMPTY_BODY_HASH + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalUri  = "/" + bucket + "/" + objectKey;

            String canonicalRequest = "GET\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_BODY_HASH;
            String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey   = getSigningKey(dateStamp, region);
            String signature    = hmacSha256Hex(signingKey, stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential="
                    + getKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            Request request = new Request.Builder()
                    .url(urlStr)
                    .get()
                    .addHeader("Authorization",        authorization)
                    .addHeader("x-amz-date",           amzDate)
                    .addHeader("x-amz-content-sha256", EMPTY_BODY_HASH)
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    ResponseBody body = response.body();
                    if (body == null)
                        throw new IOException("B2 download: empty response body for " + objectKey);
                    byte[] bytes = body.bytes();
                    Log.d(TAG, "B2 downloaded: " + objectKey + " (" + bytes.length + " bytes)");
                    return bytes;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "B2 download failed [" + code + "]: " + err);
                }
                throw new IOException("B2 download failed [" + code + "]: " + err);
            }
        });
    }

    // ── loadMedia (download + decrypt, async) ─────────────────────────────────

    /**
     * Downloads and decrypts B2 media on the shared thread pool.
     *
     * Cache priority:
     *  1. Bounded in-memory LRU cache (same-process repeat views).
     *  2. Live authenticated B2 download and decryption (retried up to
     *     {@link #MAX_RETRIES} times).
     *
     * Decrypted bytes are never written to a reusable disk cache. Results are
     * delivered on the main thread.
     *
     * @param ctx retained for API compatibility; it is not used for disk caching.
     */
    public static void loadMedia(Context ctx, String b2Path, String keyBase64, MediaCallback cb) {
        if (b2Path == null || b2Path.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onError(new IOException("B2 path is null or empty")));
            return;
        }
        // 1. In-memory cache
        byte[] cached = MEDIA_CACHE.get(b2Path);
        if (cached != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(cached));
            return;
        }
        // 2. Download from B2 (with automatic retry) — deduplicated.
        // Decrypted media is never persisted to disk; only the bounded in-memory LRU is used.
        synchronized (IN_FLIGHT) {
            List<MediaCallback> waiters = IN_FLIGHT.get(b2Path);
            if (waiters != null) { waiters.add(cb); return; }
            List<MediaCallback> list = new ArrayList<>();
            list.add(cb);
            IN_FLIGHT.put(b2Path, list);
        }
        MEDIA_POOL.execute(() -> {
            List<MediaCallback> waiters;
            synchronized (IN_FLIGHT) { waiters = IN_FLIGHT.remove(b2Path); }
            if (waiters == null) return;
            try {
                byte[] raw   = downloadFile(b2Path);
                byte[] plain = decryptAfterDownload(raw, keyBase64);
                cachePut(b2Path, plain);
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (MediaCallback c : waiters) c.onLoaded(plain);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadMedia failed: " + com.duoshield.app.util.LogRedact.metadata(b2Path) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (MediaCallback c : waiters) c.onError(e);
                });
            }
        });
    }

    /**
     * Backwards-compatible overload (no disk cache).
     * Prefer the {@code (Context, ...)} variant where a Context is available.
     */
    public static void loadMedia(String b2Path, String keyBase64, MediaCallback cb) {
        loadMedia(null, b2Path, keyBase64, cb);
    }

    /** Returns cached decrypted bytes for {@code b2Path}, or {@code null} if not cached. */
    public static byte[] getCached(String b2Path) {
        return b2Path != null ? MEDIA_CACHE.get(b2Path) : null;
    }

    /**
     * Returns true only when the decrypted bytes for {@code b2Path} are in the
     * in-memory LRU cache. No decrypted media is persisted for reuse across process
     * restarts, so a cache miss requires an authenticated download and decryption.
     */
    public static boolean isLocallyAvailable(Context ctx, String b2Path) {
        if (b2Path == null || b2Path.isEmpty()) return false;
        // No decrypted media is persisted. A process restart requires a fresh
        // authenticated download and decryption.
        return MEDIA_CACHE.get(b2Path) != null;
    }

    // ── Video thumbnail generation (encrypted videos) ──────────────────────────
    //
    // Encrypted B2 videos can't be handed to Glide directly — Glide's frame
    // extraction only understands a real URI/URL, not an AES-256-GCM blob. Since
    // the video already has to be downloaded + decrypted for playback anyway, we
    // reuse that same pipeline here, then pull a single frame with
    // MediaMetadataRetriever and cache the resulting JPEG bytes separately so
    // repeat binds (RecyclerView scroll) never redo the decode.

    private static final LruCache<String, byte[]> THUMB_CACHE =
            new LruCache<String, byte[]>(8 * 1024 * 1024) {
                @Override protected int sizeOf(String key, byte[] value) { return value.length; }
            };

    public interface ThumbnailCallback {
        void onLoaded(byte[] jpegBytes);
        void onError(Exception e);
    }

    /** Returns a cached JPEG thumbnail for {@code b2Path}, or {@code null} if not yet generated. */
    public static byte[] getCachedThumb(String b2Path) {
        return b2Path != null ? THUMB_CACHE.get(b2Path) : null;
    }

    /**
     * Decrypts the video at {@code b2Path} to a scratch file and extracts a single JPEG
     * thumbnail frame from it. Delivers on the main thread.
     *
     * <p>Routes through {@link #loadMediaToFile} rather than {@link #loadMedia} for the same
     * reason playback does: {@link MediaMetadataRetriever} can only read a file, so the
     * {@code byte[]} that {@code loadMedia} produced was written straight back out to a temp
     * file and thrown away. Paying 2–3× the video size in heap to obtain one ~40 KB JPEG was
     * the worst ratio of the three video paths, and the most dangerous — thumbnails are
     * generated during RecyclerView scroll, so several could be in flight at once on exactly
     * the low-RAM devices least able to absorb them.
     *
     * <p>{@code ctx} is required here (unlike in {@code loadMedia}) because a scratch file
     * needs a cache directory; a null context fails fast rather than silently degrading.
     */
    public static void loadVideoThumbnail(Context ctx, String b2Path, String keyBase64,
                                           ThumbnailCallback cb) {
        if (b2Path == null || b2Path.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onError(new IOException("B2 path is null or empty")));
            return;
        }
        if (ctx == null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onError(new IOException("Context required for thumbnail extraction")));
            return;
        }
        byte[] cachedThumb = THUMB_CACHE.get(b2Path);
        if (cachedThumb != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(cachedThumb));
            return;
        }
        final File scratch;
        try {
            scratch = File.createTempFile("thumb_", ".mp4", ctx.getCacheDir());
        } catch (Exception e) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onError(e));
            return;
        }
        loadMediaToFile(ctx, b2Path, keyBase64, scratch, new FileCallback() {
            @Override public void onReady(File plainFile) {
                MEDIA_POOL.execute(() -> {
                    try {
                        byte[] jpeg = extractThumbnailJpeg(plainFile);
                        if (jpeg == null) throw new IOException("no frame extracted");
                        THUMB_CACHE.put(b2Path, jpeg);
                        new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(jpeg));
                    } catch (Exception e) {
                        Log.w(TAG, "loadVideoThumbnail: extraction failed for " + com.duoshield.app.util.LogRedact.metadata(b2Path) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                        new Handler(Looper.getMainLooper()).post(() -> cb.onError(e));
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        plainFile.delete();
                    }
                });
            }
            @Override public void onError(Exception e) {
                //noinspection ResultOfMethodCallIgnored
                scratch.delete();
                cb.onError(e);
            }
        });
    }

    /** Pulls a frame near the start of an already-decrypted video file and JPEG-encodes it. */
    private static byte[] extractThumbnailJpeg(File plainVideoFile) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(plainVideoFile.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, 82, baos);
            frame.recycle();
            return baos.toByteArray();
        } finally {
            retriever.release();
        }
    }

    /** Removes a single entry from the in-memory cache (call after delete). */
    public static void evictCache(String b2Path) {
        if (b2Path != null) MEDIA_CACHE.remove(b2Path);
    }

    /** Evicts a path from the volatile in-memory cache. Context is retained for API compatibility. */
    public static void evictCache(Context ctx, String b2Path) {
        if (b2Path != null) MEDIA_CACHE.remove(b2Path);
    }

    // ── Legacy plaintext cache cleanup ────────────────────────────────────────

    /**
     * Preloads and authenticates restored media without persisting decrypted bytes.
     * Small objects may remain in the bounded in-memory LRU; large objects are
     * discarded after verification and will be fetched on demand.
     *
     * @param ctx retained for API compatibility.
     */
    public static void preCacheSync(Context ctx, String b2Path, String keyBase64) {
        if (b2Path == null || b2Path.isEmpty()) return;
        if (MEDIA_CACHE.get(b2Path) != null) return;
        try {
            byte[] raw = downloadFile(b2Path);
            byte[] plain = decryptAfterDownload(raw, keyBase64);
            cachePut(b2Path, plain);
            Log.d(TAG, "preCacheSync: verified " + b2Path + " (" + plain.length + " B)");
        } catch (Exception e) {
            Log.w(TAG, "preCacheSync: skipped " + com.duoshield.app.util.LogRedact.metadata(b2Path) + " — " + com.duoshield.app.util.LogRedact.error(e));
        }
    }

    /**
     * Removes plaintext cache files created by older app versions and clears
     * volatile decrypted media during account changes or a local wipe.
     */
    public static void clearDiskCache(Context ctx) {
        if (ctx != null) {
            File dir = new File(ctx.getFilesDir(), "b2_cache");
            if (dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!file.delete()) {
                            Log.w(TAG, "Unable to remove legacy plaintext cache file: "
                                    + com.duoshield.app.util.LogRedact.metadata(file.getName()));
                        }
                    }
                }
                if (dir.exists() && !dir.delete()) {
                    Log.w(TAG, "Unable to remove legacy plaintext cache directory");
                }
            }
        }
        MEDIA_CACHE.evictAll();
        THUMB_CACHE.evictAll();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Schedules deletion of an unpublished B2 object on the process-wide media
     * pool. Unlike an Activity-owned executor, this remains available when the
     * screen that initiated an upload is destroyed before its Firestore callback.
     */
    public static void deleteFileAsync(String b2Path) {
        if (!isB2Path(b2Path)) return;
        MEDIA_POOL.execute(() -> {
            try {
                deleteFile(b2Path);
            } catch (Exception e) {
                Log.w(TAG, "Asynchronous media cleanup failed ("
                        + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        });
    }

    /**
     * Synchronously deletes the object at {@code b2Path} using S3 DeleteObject + AWS SigV4.
     * Retries on transient network errors. 404 is treated as success (already gone).
     * Call from a background thread.
     */
    public static void deleteFile(String b2Path) throws Exception {
        if (b2Path == null || b2Path.isEmpty()) return;
        // Tiered-storage Worker path (highest priority)
        if (!getWorkerUrl().isEmpty()) {
            deleteViaWorker(b2Path);
            return;
        }
        // F9 fix: route deletion through server endpoint when credentials are absent.
        if (getKeyId().isEmpty()) {
            deleteViaServer(b2Path);
            return;
        }
        String objectKey = toObjectKey(b2Path);
        String bucket    = getBucket();
        String region    = getRegion();
        String host      = "s3." + region + ".backblazeb2.com";

        withRetry("delete:" + objectKey, () -> {
            Date now       = new Date();
            String dateStamp = utcFormat("yyyyMMdd", now);
            String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
            String urlStr    = getEndpoint() + "/" + bucket + "/" + objectKey;

            String canonicalHeaders =
                    "host:" + host + "\n"
                    + "x-amz-content-sha256:" + EMPTY_BODY_HASH + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalUri  = "/" + bucket + "/" + objectKey;

            String canonicalRequest = "DELETE\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_BODY_HASH;
            String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey   = getSigningKey(dateStamp, region);
            String signature    = hmacSha256Hex(signingKey, stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential="
                    + getKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            Request request = new Request.Builder()
                    .url(urlStr)
                    .delete()
                    .addHeader("Authorization",        authorization)
                    .addHeader("x-amz-date",           amzDate)
                    .addHeader("x-amz-content-sha256", EMPTY_BODY_HASH)
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 204 || code == 404) {
                    Log.d(TAG, "B2 deleted: " + objectKey + " (" + code + ")");
                    return null;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "B2 delete failed [" + code + "]: " + err);
                }
                throw new IOException("B2 delete failed [" + code + "] for: " + objectKey);
            }
        });
    }

    // ── AWS SigV4 helpers ──────────────────────────────────────────────���──────

    private static byte[] getSigningKey(String dateStamp, String region) throws Exception {
        byte[] kSecret  = ("AWS4" + getAppKey()).getBytes(StandardCharsets.UTF_8);
        byte[] kDate    = hmacSha256(kSecret,   dateStamp);
        byte[] kRegion  = hmacSha256(kDate,     region);
        byte[] kService = hmacSha256(kRegion,   SERVICE);
        return hmacSha256(kService, "aws4_request");
    }

    /**
     * Tests connectivity to B2 by issuing a signed GET to the bucket root.
     * Not retried — this is a one-shot diagnostic check.
     * Call from a background thread.
     * @return null on success, error message on failure.
     */
    public static String testConnection() {
        try {
            // Worker path: plain GET to /health endpoint
            if (!getWorkerUrl().isEmpty()) {
                Request request = new Request.Builder()
                        .url(getWorkerUrl() + "/health")
                        .get()
                        .addHeader("X-Client-ID", getClientId())
                        .build();
                long start = System.currentTimeMillis();
                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    int code = response.code();
                    if (code == 200) {
                        Log.d(TAG, "Worker health OK in " + (System.currentTimeMillis() - start) + " ms");
                        return null; // success
                    }
                    ResponseBody errBody = response.body();
                    String body = errBody != null ? errBody.string() : "";
                    return "Worker returned HTTP " + code + ": " + body;
                }
            }
            if (!getKeyId().isEmpty() && !getAppKey().isEmpty()) {
                // Direct SigV4 test — credentials baked into APK
                String bucket  = getBucket();
                String region  = getRegion();
                String urlStr  = getEndpoint() + "/" + bucket + "?max-keys=1";
                String host    = "s3." + region + ".backblazeb2.com";
                Date now       = new Date();
                String dateStamp = utcFormat("yyyyMMdd", now);
                String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
                String canonicalHeaders =
                        "host:" + host + "\n"
                        + "x-amz-content-sha256:" + EMPTY_BODY_HASH + "\n"
                        + "x-amz-date:" + amzDate + "\n";
                String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
                String canonicalRequest = "GET\n/" + bucket + "\nmax-keys=1\n"
                        + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_BODY_HASH;
                String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
                String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                        + credentialScope + "\n"
                        + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
                byte[] signingKey   = getSigningKey(dateStamp, region);
                String signature    = hmacSha256Hex(signingKey, stringToSign);
                String authorization = "AWS4-HMAC-SHA256 Credential="
                        + getKeyId() + "/" + credentialScope
                        + ", SignedHeaders=" + signedHeaders
                        + ", Signature=" + signature;

                Request request = new Request.Builder()
                        .url(urlStr)
                        .get()
                        .addHeader("Authorization",        authorization)
                        .addHeader("x-amz-date",           amzDate)
                        .addHeader("x-amz-content-sha256", EMPTY_BODY_HASH)
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    int code = response.code();
                    if (code == 200 || code == 204) return null;
                    ResponseBody errBody = response.body();
                    String body = errBody != null ? errBody.string() : "";
                    // F36 fix: use masked key ID to avoid exposing credential in on-screen error card
                    if (code == 403) return "HTTP 403 — credentials rejected by B2 for bucket \""
                            + bucket + "\". Verify B2_KEY_ID='" + getMaskedKeyId()
                            + "' endpoint='" + getEndpoint()
                            + "' and B2_APPLICATION_KEY are correct.\nB2 response: " + body;
                    if (code == 404) return "HTTP 404 — bucket \"" + bucket
                            + "\" not found. Verify the B2_BUCKET secret matches your actual bucket name exactly.\nB2 response: " + body;
                    return "HTTP " + code + " — check bucket name and credentials.\nB2 response: " + body;
                }
            } else {
                // Presign-server path — credentials live on the server (F9 design).
                // Test by requesting a presigned URL for a probe key; if the server
                // returns one, B2 credentials are correctly configured on Render.
                String idToken = getIdTokenSync();
                if (idToken == null) {
                    return "Not signed in — open the app and sign in, then test again.";
                }
                java.net.URL url = new java.net.URL(
                        com.duoshield.app.BuildConfig.PUSH_SERVER_URL + "/b2PresignedPut");
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + idToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                org.json.JSONObject reqBody = new org.json.JSONObject();
                reqBody.put("objectKey", "_connection_test_/probe.bin");
                reqBody.put("contentType", "application/octet-stream");
                conn.getOutputStream().write(reqBody.toString().getBytes(StandardCharsets.UTF_8));
                conn.getOutputStream().close();
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    return null; // presign server returned a valid URL — B2 is configured
                } else if (code == 503) {
                    return "Push server returned 503 — B2_KEY_ID and B2_APPLICATION_KEY are not set "
                            + "on your Render deployment. Add them in the Render dashboard → "
                            + "Environment, then redeploy the service.";
                } else if (code == 401 || code == 403) {
                    return "Push server rejected auth (HTTP " + code + ") — "
                            + "sign out and back in, then test again.";
                } else {
                    return "Push server returned HTTP " + code + " — check server logs on Render.";
                }
            }
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ── Cloudflare Worker helpers (tiered storage: R2 hot → B2 cold) ─────────

    /**
     * Uploads {@code data} through the Cloudflare Worker via a plain HTTP PUT.
     * The Worker stores the encrypted blob in R2; the daily cron tiers it to B2
     * after HOT_TIER_DAYS and purges it after COLD_TIER_DAYS.
     *
     * @return "b2:<objectKey>" so the path is compatible with all existing Room/Firestore records.
     */
    private static String uploadViaWorker(byte[] data, String objectKey,
                                          String contentType, ProgressCallback cb)
            throws Exception {
        String url = getWorkerUrl() + "/" + objectKey;
        MediaType   mt   = MediaType.parse(contentType);
        // SEC-A01: authorize this exact object+verb with a short-lived capability
        // token from the push server instead of the shared WORKER_SECRET.
        String capToken = fetchMediaCapabilityToken(objectKey, "write");
        return withRetry("worker-upload:" + objectKey, () -> {
            RequestBody rb = new ProgressRequestBody(data, mt, cb);
            Request request = new Request.Builder()
                    .url(url)
                    .put(rb)
                    .header("Content-Type",   contentType)
                    .header("Content-Length", String.valueOf(data.length))
                    .header("X-Client-ID",    getClientId())
                    .header("Authorization",  "Bearer " + capToken)
                    .build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 201) {
                    Log.d(TAG, "Worker uploaded: " + objectKey);
                    return B2_PATH_PREFIX + objectKey;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "Worker PUT failed [" + code + "]: " + err);
                }
                throw new IOException("Worker PUT failed [" + code + "]: " + err);
            }
        });
    }

    /**
     * Downloads raw encrypted bytes via the Cloudflare Worker.
     * The Worker transparently serves from R2 (hot) or B2 (cold) based on object age.
     */
    private static byte[] downloadViaWorker(String b2Path) throws Exception {
        String objectKey = toObjectKey(b2Path);
        String url       = getWorkerUrl() + "/" + objectKey;
        // SEC-A01: per-object read capability token (replaces shared WORKER_SECRET).
        String capToken = fetchMediaCapabilityToken(objectKey, "read");
        return withRetry("worker-download:" + objectKey, () -> {
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("X-Client-ID",   getClientId())
                    .header("Authorization", "Bearer " + capToken)
                    .build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    ResponseBody body = response.body();
                    if (body == null)
                        throw new IOException("Worker GET: empty body for " + objectKey);
                    byte[] bytes = body.bytes();
                    Log.d(TAG, "Worker downloaded: " + objectKey
                            + " (" + bytes.length + " B, tier="
                            + response.header("X-Storage-Tier", "?") + ")");
                    return bytes;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code == 404) {
                    throw new NonRetryableException(404, "File not found: " + objectKey);
                }
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "Worker GET failed [" + code + "]: " + err);
                }
                throw new IOException("Worker GET failed [" + code + "]: " + err);
            }
        });
    }

    /**
     * Deletes an object via the Cloudflare Worker (removes from both R2 and B2).
     * 404 is treated as success. Call from a background thread.
     */
    private static void deleteViaWorker(String b2Path) throws Exception {
        String objectKey = toObjectKey(b2Path);
        String url       = getWorkerUrl() + "/" + objectKey;
        // SEC-A01: per-object delete capability token (replaces shared WORKER_SECRET).
        String capToken = fetchMediaCapabilityToken(objectKey, "delete");
        withRetry("worker-delete:" + objectKey, () -> {
            Request request = new Request.Builder()
                    .url(url)
                    .delete()
                    .header("X-Client-ID",   getClientId())
                    .header("Authorization", "Bearer " + capToken)
                    .build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 204 || code == 404) {
                    Log.d(TAG, "Worker deleted: " + objectKey + " (" + code + ")");
                    return null;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "Worker DELETE failed [" + code + "]: " + err);
                }
                throw new IOException("Worker DELETE failed [" + code + "]: " + err);
            }
        });
    }

    // ── F9: Presigned URL / server-proxy helpers ──────────────────────────────

    /**
     * Uploads {@code data} to B2 using a server-generated presigned PUT URL.
     * Used when {@code B2_KEY_ID} / {@code B2_APPLICATION_KEY} are empty in the APK.
     */
    private static String uploadViaPresignedUrl(byte[] data, String objectKey,
                                                String contentType, ProgressCallback cb)
            throws Exception {
        String presigned = fetchPresignedPutUrl(objectKey, contentType);
        MediaType   mt   = MediaType.parse(contentType);
        RequestBody rb   = new ProgressRequestBody(data, mt, cb);
        Request request  = new Request.Builder()
                .url(presigned)
                .put(rb)
                .header("Content-Type", contentType)
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            int code = response.code();
            if (code == 200 || code == 201) {
                Log.d(TAG, "B2 presigned uploaded: " + objectKey);
                return B2_PATH_PREFIX + objectKey;
            }
            ResponseBody errBody = response.body();
            String err = errBody != null ? errBody.string() : "";
            if (code >= 400 && code < 500) {
                throw new NonRetryableException(code,
                        "B2 presigned PUT failed [" + code + "]: " + err);
            }
            throw new IOException("B2 presigned PUT failed [" + code + "]: " + err);
        }
    }

    /**
     * Downloads from B2 using a server-generated presigned GET URL.
     *
     * <p>The bucket requires SigV4 authentication — unauthenticated public GETs return 401.
     * When B2 credentials are not baked into the APK ({@link #getKeyId()} is empty) we ask
     * the push server for a short-lived presigned URL (1-hour TTL) and perform a plain GET
     * against it, just like we do for uploads via {@link #uploadViaPresignedUrl}.
     */
    private static byte[] downloadViaPublicUrl(String b2Path) throws Exception {
        String objectKey = toObjectKey(b2Path);
        return withRetry("dl-presigned:" + objectKey, () -> {
            String presignedUrl = fetchPresignedGetUrl(objectKey);
            Request request = new Request.Builder().url(presignedUrl).get().build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    ResponseBody body = response.body();
                    if (body == null) throw new IOException("Empty B2 response body");
                    return body.bytes();
                }
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "B2 presigned GET failed [" + code + "]: " + objectKey);
                }
                throw new IOException("B2 presigned GET failed [" + code + "]: " + objectKey);
            }
        });
    }

    /**
     * Fetches a presigned S3 GET URL from the push server.
     * Blocks the calling thread — call only from a background thread.
     */
    private static String fetchPresignedGetUrl(String objectKey) throws Exception {
        String idToken = getIdTokenSync();
        if (idToken == null) throw new Exception("Not authenticated — presign GET unavailable");

        java.net.URL url = new java.net.URL(BuildConfig.PUSH_SERVER_URL + "/b2PresignedGet");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + idToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        org.json.JSONObject reqBody = new org.json.JSONObject();
        reqBody.put("objectKey", objectKey);
        conn.getOutputStream().write(reqBody.toString().getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().close();
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("Presign GET server returned HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return new org.json.JSONObject(sb.toString()).getString("url");
    }

    /**
     * Deletes an object via the push server's {@code /b2Delete} endpoint.
     * Used when credentials are not baked into the APK (F9 fix).
     */
    private static void deleteViaServer(String b2Path) throws Exception {
        String objectKey = toObjectKey(b2Path);
        String idToken   = getIdTokenSync();
        if (idToken == null) throw new Exception("Not authenticated — server delete unavailable");

        java.net.URL url = new java.net.URL(BuildConfig.PUSH_SERVER_URL + "/b2Delete");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + idToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("objectKey", objectKey);
        conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().close();
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200 && code != 204 && code != 404) {
            throw new IOException("Server /b2Delete returned HTTP " + code);
        }
        Log.d(TAG, "B2 server-deleted: " + objectKey);
    }

    /**
     * Fetches a presigned S3 PUT URL from the push server.
     * Blocks the calling thread for up to 10 seconds.
     */
    private static String fetchPresignedPutUrl(String objectKey, String contentType) throws Exception {
        String idToken = getIdTokenSync();
        if (idToken == null) throw new Exception("Not authenticated — presign unavailable");

        java.net.URL url = new java.net.URL(BuildConfig.PUSH_SERVER_URL + "/b2PresignedPut");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + idToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        org.json.JSONObject reqBody = new org.json.JSONObject();
        reqBody.put("objectKey", objectKey);
        reqBody.put("contentType", contentType);
        conn.getOutputStream().write(reqBody.toString().getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().close();
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("Presign server returned HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return new org.json.JSONObject(sb.toString()).getString("url");
    }

    /**
     * Synchronously retrieves the current user's Firebase ID token.
     * Background-thread safe (uses lock/notify — compatible with Firebase tasks API).
     */
    private static String getIdTokenSync() {
        com.google.firebase.auth.FirebaseUser user =
            com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
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
                try { lock.wait(10_000); } catch (InterruptedException ignored) {}
            }
        }
        return holder[0];
    }

    /**
     * Exchanges the caller's Firebase ID token for a short-lived, per-object
     * capability token from the push server's {@code POST /mediaToken}.
     *
     * <p>SEC-A01: this token — not the old shared {@code WORKER_SECRET} — is the
     * only credential the Cloudflare Worker accepts on the data plane. It is bound
     * to exactly this {@code (objectKey, op)} pair with a short expiry, and the
     * server only issues it after confirming the caller participates in the
     * chat/group named by the key. The signing secret never ships in the APK, so
     * decompiling the client yields nothing reusable and a captured token is
     * useless beyond one object, one verb, and a few minutes.
     *
     * @param objectKey full storage key, e.g. {@code media/<chatId>/<uuid>.jpg}
     * @param op        one of {@code "read"} (GET), {@code "write"} (PUT),
     *                  {@code "delete"} (DELETE)
     * @return the opaque capability token to send as {@code Authorization: Bearer <token>}
     */
    private static String fetchMediaCapabilityToken(String objectKey, String op) throws Exception {
        String idToken = getIdTokenSync();
        if (idToken == null) {
            throw new NonRetryableException(401, "Not authenticated — cannot obtain media token");
        }
        org.json.JSONObject reqJson = new org.json.JSONObject();
        reqJson.put("key", objectKey);
        reqJson.put("op", op);
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"), reqJson.toString());
        Request request = new Request.Builder()
                .url(BuildConfig.PUSH_SERVER_URL + "/mediaToken")
                .post(body)
                .header("Authorization", "Bearer " + idToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            int code = response.code();
            ResponseBody rb = response.body();
            String payload = rb != null ? rb.string() : "";
            if (code != 200) {
                // 4xx (not a participant, bad key, expired sign-in) won't be
                // fixed by retrying — surface as non-retryable so the caller
                // fails fast instead of hammering the server.
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "mediaToken denied [" + code + "]: " + payload);
                }
                throw new IOException("mediaToken failed [" + code + "]: " + payload);
            }
            String token = new org.json.JSONObject(payload).optString("token", "");
            if (token.isEmpty()) {
                throw new IOException("mediaToken response missing token");
            }
            return token;
        }
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(byte[] key, String data) throws Exception {
        return bytesToHex(hmacSha256(key, data));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String sha256Hex(String data) throws Exception {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String utcFormat(String pattern, Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }

    // ── Streaming upload (large files — avoids full file byte[] in memory) ────

    /** Chunk size for streaming AES-GCM encryption (256 KB). */
    private static final int ENCRYPT_STREAM_CHUNK = 256 * 1024;

    /**
     * OkHttp client for streaming uploads — same as {@link #HTTP_CLIENT} but
     * with a 1-hour write timeout so very large files don't time out mid-transfer.
     */
    private static final OkHttpClient STREAMING_HTTP_CLIENT =
            HTTP_CLIENT.newBuilder()
                       .writeTimeout(3600, TimeUnit.SECONDS)
                       .build();

    /**
     * OkHttp {@link RequestBody} that streams a {@link File} in
     * {@link #BUFFER_SIZE}-byte chunks and reports progress.
     * Unlike {@link ProgressRequestBody}, this never holds more than one chunk
     * in RAM at a time — safe for files up to 95 MB.
     */
    private static final class FileRequestBody extends RequestBody {
        private final File             file;
        private final MediaType        mediaType;
        private final ProgressCallback cb;

        FileRequestBody(File file, MediaType mediaType, ProgressCallback cb) {
            this.file      = file;
            this.mediaType = mediaType;
            this.cb        = cb;
        }

        @Override public MediaType contentType()   { return mediaType; }
        @Override public long      contentLength() { return file.length(); }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            long   total   = file.length();
            long   written = 0;
            byte[] buf     = new byte[BUFFER_SIZE];
            try (FileInputStream fis = new FileInputStream(file)) {
                int n;
                while ((n = fis.read(buf)) != -1) {
                    sink.write(buf, 0, n);
                    written += n;
                    if (cb != null && total > 0)
                        cb.onProgress((int) (100L * written / total));
                }
            }
        }
    }

    /**
     * AES-256-GCM encrypts the content at {@code uri} into {@code dest} using
     * streaming {@code Cipher.update()} so the full plaintext and ciphertext are
     * never held in memory simultaneously — safe for files up to 95 MB.
     *
     * <p>Output format: {@code [12-byte IV | ciphertext | 16-byte GCM auth tag]}.
     * Identical to {@link #encryptForUpload(byte[])} — decrypted by
     * {@link #decryptAfterDownload(byte[], String)} on the receiver's device.
     *
     * <p>Call from a background thread. Deleting {@code dest} after upload is the
     * caller's responsibility.
     *
     * @param cr   ContentResolver for opening the URI.
     * @param uri  Source URI (content:// from media picker or FileProvider).
     * @param dest Destination file — will be overwritten if it already exists.
     * @return Base64-encoded AES-256 key to store in Firestore as "mediaKey".
     */
    public static String encryptUriToFile(android.content.ContentResolver cr,
                                           android.net.Uri uri, File dest)
            throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey key = kg.generateKey();
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));

        byte[] buf = new byte[ENCRYPT_STREAM_CHUNK];
        try (InputStream       in  = cr.openInputStream(uri);
             FileOutputStream  out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("Cannot open URI for streaming: " + uri);
            out.write(iv); // IV prefix — same layout as encryptForUpload()
            int n;
            while ((n = in.read(buf)) != -1) {
                byte[] enc = cipher.update(buf, 0, n);
                if (enc != null && enc.length > 0) out.write(enc);
            }
            // doFinal() returns the last cipher block + 16-byte GCM auth tag.
            byte[] tail = cipher.doFinal();
            if (tail != null && tail.length > 0) out.write(tail);
        }
        return Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);
    }

    /**
     * Uploads {@code encFile} (already AES-256-GCM encrypted) to the configured
     * storage backend using a streaming {@link RequestBody} — no full-file byte[]
     * allocation during the upload phase.
     *
     * <p>Routes through the same priority chain as {@link #uploadFile}:
     * Cloudflare Worker → presigned URL → direct B2 SigV4 (UNSIGNED-PAYLOAD).
     *
     * @return "b2:<objectKey>" — store as the Firestore "path" field.
     */
    public static String uploadFileFromDisk(File encFile, String objectKey,
                                             String contentType, ProgressCallback cb)
            throws Exception {
        if (!getWorkerUrl().isEmpty())  return uploadFileViaWorkerStream(encFile, objectKey, contentType, cb);
        if (getKeyId().isEmpty())       return uploadFileViaPresignedStream(encFile, objectKey, contentType, cb);
        return uploadFileViaB2Stream(encFile, objectKey, contentType, cb);
    }

    /** Streaming Worker PUT. */
    private static String uploadFileViaWorkerStream(File encFile, String objectKey,
                                                     String contentType, ProgressCallback cb)
            throws Exception {
        String url = getWorkerUrl() + "/" + objectKey;
        MediaType mt = MediaType.parse(contentType);
        // SEC-A01: per-object write capability token (replaces shared WORKER_SECRET).
        String capToken = fetchMediaCapabilityToken(objectKey, "write");
        Request request = new Request.Builder()
                .url(url)
                .put(new FileRequestBody(encFile, mt, cb))
                .header("Content-Type",   contentType)
                .header("Content-Length", String.valueOf(encFile.length()))
                .header("X-Client-ID",    getClientId())
                .header("Authorization",  "Bearer " + capToken)
                .build();

        return withRetry("worker-stream:" + objectKey, () -> {
            try (Response response = STREAMING_HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 201) {
                    Log.d(TAG, "Worker stream-uploaded: " + objectKey
                            + " (" + encFile.length() + " B)");
                    return B2_PATH_PREFIX + objectKey;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500)
                    throw new NonRetryableException(code, "Worker stream-PUT [" + code + "]: " + err);
                throw new IOException("Worker stream-PUT [" + code + "]: " + err);
            }
        });
    }

    /** Streaming presigned URL PUT. */
    private static String uploadFileViaPresignedStream(File encFile, String objectKey,
                                                        String contentType, ProgressCallback cb)
            throws Exception {
        String presigned = fetchPresignedPutUrl(objectKey, contentType);
        MediaType mt = MediaType.parse(contentType);
        Request request = new Request.Builder()
                .url(presigned)
                .put(new FileRequestBody(encFile, mt, cb))
                .header("Content-Type", contentType)
                .build();
        try (Response response = STREAMING_HTTP_CLIENT.newCall(request).execute()) {
            int code = response.code();
            if (code == 200 || code == 201) {
                Log.d(TAG, "B2 presigned stream-uploaded: " + objectKey
                        + " (" + encFile.length() + " B)");
                return B2_PATH_PREFIX + objectKey;
            }
            ResponseBody errBody = response.body();
            String err = errBody != null ? errBody.string() : "";
            if (code >= 400 && code < 500)
                throw new NonRetryableException(code, "Presigned stream-PUT [" + code + "]: " + err);
            throw new IOException("Presigned stream-PUT [" + code + "]: " + err);
        }
    }

    /**
     * Streaming direct B2 SigV4 PUT with {@code UNSIGNED-PAYLOAD}.
     * B2 S3-compatible API accepts UNSIGNED-PAYLOAD, which avoids a full
     * pre-read pass to compute the body SHA-256 before the upload starts.
     */
    private static String uploadFileViaB2Stream(File encFile, String objectKey,
                                                 String contentType, ProgressCallback cb)
            throws Exception {
        final String UNSIGNED = "UNSIGNED-PAYLOAD";
        String bucket = getBucket();
        String region = getRegion();
        String host   = "s3." + region + ".backblazeb2.com";
        String urlStr = getEndpoint() + "/" + bucket + "/" + objectKey;

        return withRetry("b2-stream:" + objectKey, () -> {
            Date   now       = new Date();
            String dateStamp = utcFormat("yyyyMMdd", now);
            String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);

            String canonicalHeaders =
                    "content-length:" + encFile.length() + "\n"
                    + "content-type:" + contentType + "\n"
                    + "host:" + host + "\n"
                    + "x-amz-content-sha256:" + UNSIGNED + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "content-length;content-type;host;x-amz-content-sha256;x-amz-date";
            String canonicalUri  = "/" + bucket + "/" + objectKey;

            String canonicalRequest = "PUT\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + UNSIGNED;
            String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey   = getSigningKey(dateStamp, region);
            String signature    = hmacSha256Hex(signingKey, stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential="
                    + getKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            MediaType mt = MediaType.parse(contentType);
            Request request = new Request.Builder()
                    .url(urlStr)
                    .put(new FileRequestBody(encFile, mt, cb))
                    .addHeader("Authorization",        authorization)
                    .addHeader("x-amz-date",           amzDate)
                    .addHeader("x-amz-content-sha256", UNSIGNED)
                    .build();

            try (Response response = STREAMING_HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 201) {
                    Log.d(TAG, "B2 stream-uploaded: " + objectKey
                            + " (" + encFile.length() + " B)");
                    return B2_PATH_PREFIX + objectKey;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500)
                    throw new NonRetryableException(code, "B2 stream-upload [" + code + "]: " + err);
                throw new IOException("B2 stream-upload [" + code + "]: " + err);
            }
        });
    }

    // ── Streaming download + decrypt (large files — avoids full byte[] in memory) ──

    /**
     * Downloads raw encrypted bytes for {@code b2Path} directly to {@code dest} on disk,
     * streaming the HTTP response body in {@link #BUFFER_SIZE}-byte chunks. Unlike
     * {@link #downloadFile(String)}, this never holds the full encrypted blob in memory —
     * safe for large videos where the full file could exceed available heap.
     *
     * <p>Uses the same routing priority as {@link #downloadFile(String)} (Worker → presigned
     * URL → direct SigV4 GET). Call from a background thread.
     */
    public static void downloadFileToDisk(String b2Path, File dest) throws Exception {
        if (!getWorkerUrl().isEmpty()) {
            downloadViaWorkerToDisk(b2Path, dest);
            return;
        }
        if (getKeyId().isEmpty()) {
            downloadViaPublicUrlToDisk(b2Path, dest);
            return;
        }
        downloadViaSigV4ToDisk(b2Path, dest);
    }

    /** Streams an OkHttp response body straight to disk — never buffers the whole thing. */
    private static void streamResponseToFile(Response response, File dest) throws IOException {
        ResponseBody body = response.body();
        if (body == null) throw new IOException("Empty response body");
        try (InputStream in = body.byteStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static void downloadViaWorkerToDisk(String b2Path, File dest) throws Exception {
        String objectKey = toObjectKey(b2Path);
        String url       = getWorkerUrl() + "/" + objectKey;
        // SEC-A01: per-object read capability token (replaces shared WORKER_SECRET).
        String capToken = fetchMediaCapabilityToken(objectKey, "read");
        withRetry("worker-download-disk:" + objectKey, () -> {
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("X-Client-ID",   getClientId())
                    .header("Authorization", "Bearer " + capToken)
                    .build();
            try (Response response = STREAMING_HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    streamResponseToFile(response, dest);
                    Log.d(TAG, "Worker stream-downloaded: " + objectKey + " (" + dest.length() + " B)");
                    return null;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code == 404) throw new NonRetryableException(404, "File not found: " + objectKey);
                if (code >= 400 && code < 500)
                    throw new NonRetryableException(code, "Worker GET failed [" + code + "]: " + err);
                throw new IOException("Worker GET failed [" + code + "]: " + err);
            }
        });
    }

    private static void downloadViaPublicUrlToDisk(String b2Path, File dest) throws Exception {
        String objectKey = toObjectKey(b2Path);
        withRetry("dl-presigned-disk:" + objectKey, () -> {
            String presignedUrl = fetchPresignedGetUrl(objectKey);
            Request request = new Request.Builder().url(presignedUrl).get().build();
            try (Response response = STREAMING_HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    streamResponseToFile(response, dest);
                    return null;
                }
                if (code >= 400 && code < 500)
                    throw new NonRetryableException(code,
                            "B2 presigned GET failed [" + code + "]: " + objectKey);
                throw new IOException("B2 presigned GET failed [" + code + "]: " + objectKey);
            }
        });
    }

    private static void downloadViaSigV4ToDisk(String b2Path, File dest) throws Exception {
        String objectKey = toObjectKey(b2Path);
        String bucket    = getBucket();
        String region    = getRegion();
        String host      = "s3." + region + ".backblazeb2.com";

        withRetry("download-disk:" + objectKey, () -> {
            Date now         = new Date();
            String dateStamp = utcFormat("yyyyMMdd", now);
            String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
            String urlStr    = getEndpoint() + "/" + bucket + "/" + objectKey;

            String canonicalHeaders =
                    "host:" + host + "\n"
                    + "x-amz-content-sha256:" + EMPTY_BODY_HASH + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalUri  = "/" + bucket + "/" + objectKey;

            String canonicalRequest = "GET\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_BODY_HASH;
            String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey   = getSigningKey(dateStamp, region);
            String signature    = hmacSha256Hex(signingKey, stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential="
                    + getKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            Request request = new Request.Builder()
                    .url(urlStr)
                    .get()
                    .addHeader("Authorization",        authorization)
                    .addHeader("x-amz-date",           amzDate)
                    .addHeader("x-amz-content-sha256", EMPTY_BODY_HASH)
                    .build();

            try (Response response = STREAMING_HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    streamResponseToFile(response, dest);
                    return null;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500)
                    throw new NonRetryableException(code, "B2 download failed [" + code + "]: " + err);
                throw new IOException("B2 download failed [" + code + "]: " + err);
            }
        });
    }

    /**
     * AES-256-GCM decrypts {@code encFile} (format: {@code [12-byte IV | ciphertext | 16-byte
     * tag]}) into {@code dest}, streaming through {@link CipherInputStream} so the full
     * plaintext and ciphertext are never held in memory simultaneously — safe for files up to
     * 95 MB. Inverse of {@link #encryptUriToFile}; also decrypts blobs produced by
     * {@link #encryptForUpload(byte[])} since the on-wire format is identical.
     */
    public static void decryptFileToFile(File encFile, String keyBase64, File dest) throws Exception {
        if (keyBase64 == null || keyBase64.isEmpty()) {
            copyFile(encFile, dest);
            return;
        }
        if (encFile.length() < GCM_IV_LEN + 16)
            throw new IOException("Encrypted file too short: " + encFile.length());

        byte[] decodedKey = Base64.decode(keyBase64, Base64.NO_WRAP);
        SecretKey key = new SecretKeySpec(decodedKey, "AES");

        try (FileInputStream fis = new FileInputStream(encFile)) {
            byte[] iv = new byte[GCM_IV_LEN];
            int read = 0;
            while (read < GCM_IV_LEN) {
                int n = fis.read(iv, read, GCM_IV_LEN - read);
                if (n == -1) throw new IOException("Truncated file — missing IV");
                read += n;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));

            try (CipherInputStream cis = new CipherInputStream(fis, cipher);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[ENCRYPT_STREAM_CHUNK];
                int n;
                while ((n = cis.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /**
     * Downloads and decrypts {@code b2Path} directly to {@code dest} on disk, without ever
     * holding the full file in memory. Combines {@link #downloadFileToDisk} and
     * {@link #decryptFileToFile}, cleaning up the intermediate encrypted temp file even on
     * failure. Call from a background thread.
     */
    public static void downloadAndDecryptToFile(String b2Path, String keyBase64, File dest)
            throws Exception {
        File tmp = File.createTempFile("b2dl_", ".enc", dest.getParentFile());
        try {
            downloadFileToDisk(b2Path, tmp);
            decryptFileToFile(tmp, keyBase64, dest);
        } finally {
            tmp.delete();
        }
    }

    // ── Streaming media load to a plaintext file (large videos) ───────────────

    /** Async result of {@link #loadMediaToFile}. Both methods are called on the main thread. */
    public interface FileCallback {
        /** {@code dest} now holds the decrypted plaintext and is ready to hand to a player. */
        void onReady(File dest);
        void onError(Exception e);
    }

    /**
     * Decrypts the media at {@code b2Path} into {@code dest} on disk, never materializing the
     * plaintext as a single {@code byte[]}.
     *
     * <p>This is the file-oriented counterpart to {@link #loadMedia}, and the correct entry
     * point for anything whose end state is a file — video playback and gallery saves both
     * hand a path to the OS, so the intermediate {@code byte[]} that {@code loadMedia}
     * produces is pure overhead. Going through {@code loadMedia} for a video allocated the
     * encrypted blob and the decrypted plaintext simultaneously and then wrote the plaintext
     * to a temp file anyway, so peak usage was 2–3× the file size to reach a state that is
     * just "a file on disk". On a 2 GB device with roughly 150–190 MB of usable heap that
     * turns a 200 MB video from slow into a certain {@code OutOfMemoryError}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Bounded in-memory LRU hit — written to the caller's temporary destination.</li>
     *   <li>Live authenticated download and streaming decryption to that destination.</li>
     * </ol>
     *
     * <p>Unlike {@link #loadMedia} this does not deduplicate concurrent requests through
     * {@link #IN_FLIGHT}: each caller supplies its own {@code dest}, so there is no single
     * result to share, and the callers (a full-screen viewer and an explicit save action) are
     * inherently one-at-a-time rather than the many-rows-at-once pattern that made
     * deduplication worthwhile for avatars.
     *
     * @param ctx  retained for API compatibility; no plaintext disk cache is used.
     * @param dest file to write the plaintext into; its parent directory must exist.
     */
    public static void loadMediaToFile(Context ctx, String b2Path, String keyBase64,
                                        File dest, FileCallback cb) {
        loadMediaToFile(ctx, b2Path, keyBase64, dest, false, cb);
    }

    /**
     * Chunked-aware variant of {@link #loadMediaToFile(Context, String, String, File,
     * FileCallback)}.
     *
     * <p>{@code chunked} selects which on-wire format the object is in, and it must come from
     * the message that named the object — the two formats are not self-describing at this
     * layer, and guessing by sniffing the first byte would mean treating an attacker-influenced
     * byte as a format switch. A chunked object handed to the legacy decrypt path fails its tag
     * check rather than producing garbage, but failing for the right reason is worth the
     * explicit flag.
     */
    public static void loadMediaToFile(Context ctx, String b2Path, String keyBase64,
                                        File dest, boolean chunked, FileCallback cb) {
        if (b2Path == null || b2Path.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onError(new IOException("B2 path is null or empty")));
            return;
        }
        MEDIA_POOL.execute(() -> {
            try {
                byte[] cached = MEDIA_CACHE.get(b2Path);
                if (cached != null) {
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        fos.write(cached);
                    }
                } else {
                    if (chunked) {
                        downloadAndDecryptChunkedToFile(b2Path, keyBase64, dest);
                    } else {
                        downloadAndDecryptToFile(b2Path, keyBase64, dest);
                    }
                }
                new Handler(Looper.getMainLooper()).post(() -> cb.onReady(dest));
            } catch (Exception e) {
                Log.e(TAG, "loadMediaToFile failed: " + com.duoshield.app.util.LogRedact.metadata(b2Path) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                new Handler(Looper.getMainLooper()).post(() -> cb.onError(e));
            }
        });
    }

    // ── Chunked, range-addressable media format (v2) ───────────────────────────

    /**
     * On-wire layout of a chunked object:
     *
     * <pre>
     * [1 byte : 0x02 format version]
     * [7 bytes: random per-file nonce prefix]
     * [8 bytes: big-endian plaintext total length L]
     * chunk_0 ciphertext+tag  (CHUNK_PLAINTEXT_SIZE + 16 B, unless also last)
     * chunk_1 ciphertext+tag
     * ...
     * chunk_{n-1} ciphertext+tag  (last: (L - (n-1)*CHUNK_PLAINTEXT_SIZE) + 16 B)
     * </pre>
     *
     * <p>Why a new format at all: the legacy layout is one AES-GCM blob whose single tag
     * cannot be verified until the final byte has arrived. A range read of that blob would
     * either have to block until EOF anyway — buying nothing — or hand the player
     * unauthenticated plaintext, which is exactly the thing this app exists to not do. With
     * fixed-size chunks, every chunk is independently authenticated and every plaintext
     * offset maps to a chunk index arithmetically, so seeking costs one small range GET.
     *
     * <p>Every chunk but the last is exactly {@link #CHUNK_PLAINTEXT_SIZE}, which is what
     * makes offsets computable from {@code L} alone — no per-chunk length prefixes to parse,
     * and no index table to fetch before the first frame can play.
     */
    public static final byte CHUNKED_FORMAT_VERSION = 0x02;

    /** Plaintext bytes per chunk (1 MiB). */
    public static final int  CHUNK_PLAINTEXT_SIZE   = 1024 * 1024;
    /** GCM tag bytes appended to each chunk. */
    private static final int CHUNK_TAG_BYTES        = 16;
    /** On-wire size of a non-final chunk. */
    public static final int  CHUNK_CIPHERTEXT_SIZE  = CHUNK_PLAINTEXT_SIZE + CHUNK_TAG_BYTES;
    /** Fixed header size — fetched with one small range GET before any chunk. */
    public static final int  CHUNKED_HEADER_BYTES   = 16;
    private static final int NONCE_PREFIX_BYTES     = 7;

    /**
     * Top bit of the 4-byte chunk counter, set iff the chunk is the final one.
     *
     * <p>This is the STREAM construction (Rogaway/Shrimpton — the same idea behind Tink's
     * streaming AEAD and {@code age}), and it is what makes truncation fail closed: a
     * non-final chunk was authenticated under an IV with this bit clear, so an attacker who
     * drops the tail and relabels chunk k as final changes the IV, and GCM tag verification
     * fails. Overflow is not reachable — the app's 95 MB file cap means at most ~500 chunks
     * against a 2^31 counter space.
     */
    private static final int LAST_CHUNK_FLAG = 0x80000000;

    /**
     * Hard ceiling on chunk count, derived from the app's 95 MB upload cap with generous
     * headroom. A header claiming more chunks than this is malformed or hostile; rejecting it
     * up front keeps a corrupted 8-byte length field from turning into an unbounded fetch loop.
     */
    private static final int MAX_CHUNKS = 4096;

    /**
     * Cached per-object capability tokens.
     *
     * <p>Streaming playback issues one range GET per 1 MiB chunk, and minting a fresh
     * capability token for each would mean a push-server round trip per megabyte — slower than
     * the download it authorizes, and enough to trip the server's own per-user rate limit on a
     * large video. The server's tokens are valid for 10 minutes; caching them for 5 keeps a
     * comfortable margin against clock skew and in-flight requests while collapsing hundreds
     * of mint calls into one. Tokens are scoped to one object and one verb, so caching does
     * not widen what a leaked token could do — it only reuses it for the requests it was
     * already minted for.
     */
    private static final ConcurrentHashMap<String, CachedCapToken> CAP_TOKEN_CACHE =
            new ConcurrentHashMap<>();
    private static final long CAP_TOKEN_CACHE_MS = 5 * 60_000L;

    private static final class CachedCapToken {
        final String token;
        final long   cachedAtMs;
        CachedCapToken(String token, long cachedAtMs) {
            this.token = token; this.cachedAtMs = cachedAtMs;
        }
    }

    private static String capabilityTokenCached(String objectKey, String op) throws Exception {
        String cacheKey = op + ":" + objectKey;
        CachedCapToken hit = CAP_TOKEN_CACHE.get(cacheKey);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.cachedAtMs < CAP_TOKEN_CACHE_MS) return hit.token;
        String fresh = fetchMediaCapabilityToken(objectKey, op);
        CAP_TOKEN_CACHE.put(cacheKey, new CachedCapToken(fresh, now));
        return fresh;
    }

    /** Parsed 16-byte chunked header. */
    public static final class ChunkedHeader {
        /** 7-byte per-file nonce prefix. */
        public final byte[] noncePrefix;
        /** Total plaintext length in bytes. */
        public final long   plaintextLength;
        /** Number of chunks the object is made of (always ≥ 1). */
        public final int    chunkCount;

        ChunkedHeader(byte[] noncePrefix, long plaintextLength, int chunkCount) {
            this.noncePrefix     = noncePrefix;
            this.plaintextLength = plaintextLength;
            this.chunkCount      = chunkCount;
        }

        /** Byte offset of chunk {@code index} within the encrypted object. */
        public long chunkOffset(int index) {
            return CHUNKED_HEADER_BYTES + (long) index * CHUNK_CIPHERTEXT_SIZE;
        }

        /** On-wire (ciphertext + tag) length of chunk {@code index}. */
        public int chunkOnWireLength(int index) {
            if (index < chunkCount - 1) return CHUNK_CIPHERTEXT_SIZE;
            long tail = plaintextLength - (long) index * CHUNK_PLAINTEXT_SIZE;
            return (int) tail + CHUNK_TAG_BYTES;
        }
    }

    /** Per-chunk 12-byte GCM IV: 7-byte file nonce prefix + 4-byte big-endian counter. */
    private static byte[] chunkIv(byte[] noncePrefix, int index, boolean last) {
        int counter = last ? (index | LAST_CHUNK_FLAG) : index;
        return ByteBuffer.allocate(GCM_IV_LEN)
                .put(noncePrefix, 0, NONCE_PREFIX_BYTES)
                .putInt(counter)     // ByteBuffer is big-endian by default
                .array();
    }

    /**
     * Additional authenticated data for one chunk: the object key, the chunk counter, and the
     * final-chunk flag.
     *
     * <p>Defense-in-depth rather than load-bearing, and worth being explicit about: every file
     * already gets its own fresh random AES-256 key, so a chunk from one object can never
     * verify under another object's key — cross-file splicing is already prevented by key
     * separation alone. Binding the key name here simply means a future change that ever
     * reuses a key across objects does not silently become a splicing bug.
     */
    private static byte[] chunkAad(String objectKey, int index, boolean last) {
        byte[] keyBytes = objectKey.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(keyBytes.length + 5)
                .put(keyBytes)
                .putInt(index)
                .put((byte) (last ? 1 : 0))
                .array();
    }

    /** Reads up to {@code max} bytes, looping until the buffer is full or the stream ends. */
    private static int readUpTo(InputStream in, byte[] buf, int max) throws IOException {
        int total = 0;
        while (total < max) {
            int n = in.read(buf, total, max - total);
            if (n == -1) break;
            total += n;
        }
        return total;
    }

    private static SecretKey decodeMediaKey(String keyBase64) throws IOException {
        if (keyBase64 == null || keyBase64.isEmpty())
            throw new IOException("Chunked media requires a mediaKey");
        return new SecretKeySpec(Base64.decode(keyBase64, Base64.NO_WRAP), "AES");
    }

    /**
     * AES-256-GCM encrypts the content at {@code uri} into {@code dest} using the chunked v2
     * format, so the receiver can play and seek the object while it is still downloading.
     *
     * <p>{@code objectKey} is the B2 key the encrypted file will be uploaded to — the caller
     * already generates it before encrypting (see {@code ChatMediaActivity}) — and is bound
     * into every chunk's AAD.
     *
     * <p>The plaintext length is only known once the source stream ends, so the header is
     * written with a zero length placeholder and patched in place afterwards via
     * {@link RandomAccessFile}. Memory stays flat at two 1 MiB buffers regardless of file size.
     *
     * <p>Call from a background thread. Deleting {@code dest} after upload is the caller's
     * responsibility.
     *
     * @return Base64-encoded AES-256 key to store in Firestore as "mediaKey".
     */
    public static String encryptUriToChunkedFile(android.content.ContentResolver cr,
                                                 android.net.Uri uri,
                                                 String objectKey,
                                                 File dest) throws Exception {
        if (objectKey == null || objectKey.isEmpty())
            throw new IOException("Chunked encryption requires the destination object key");

        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey key = kg.generateKey();
        byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
        new SecureRandom().nextBytes(noncePrefix);

        long total = 0;
        try (InputStream in = cr.openInputStream(uri);
             RandomAccessFile out = new RandomAccessFile(dest, "rw")) {
            if (in == null) throw new IOException("Cannot open URI for streaming: " + uri);
            out.setLength(0);
            out.write(CHUNKED_FORMAT_VERSION);
            out.write(noncePrefix);
            out.write(new byte[8]); // plaintext length placeholder, patched below

            byte[] cur  = new byte[CHUNK_PLAINTEXT_SIZE];
            byte[] next = new byte[CHUNK_PLAINTEXT_SIZE];
            int curLen  = readUpTo(in, cur, CHUNK_PLAINTEXT_SIZE);
            int index   = 0;

            if (curLen == 0) {
                // Empty source: still emit exactly one (empty, final) chunk so the reader
                // never has to special-case a zero-chunk object.
                out.write(sealChunk(key, noncePrefix, objectKey, cur, 0, 0, true));
            } else {
                while (true) {
                    int nextLen  = readUpTo(in, next, CHUNK_PLAINTEXT_SIZE);
                    boolean last = (nextLen == 0);
                    if (index >= MAX_CHUNKS)
                        throw new IOException("Chunk count exceeds " + MAX_CHUNKS);
                    out.write(sealChunk(key, noncePrefix, objectKey, cur, curLen, index, last));
                    total += curLen;
                    index++;
                    if (last) break;
                    byte[] swap = cur; cur = next; next = swap;
                    curLen = nextLen;
                }
            }

            out.seek(8);
            out.write(ByteBuffer.allocate(8).putLong(total).array());
        }
        Log.d(TAG, "Chunked-encrypted " + objectKey + " (" + total + " B plaintext, "
                + dest.length() + " B on wire)");
        return Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);
    }

    private static byte[] sealChunk(SecretKey key, byte[] noncePrefix, String objectKey,
                                    byte[] plain, int plainLen, int index, boolean last)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_LEN, chunkIv(noncePrefix, index, last)));
        cipher.updateAAD(chunkAad(objectKey, index, last));
        return cipher.doFinal(plain, 0, plainLen);
    }

    /**
     * Decrypts one chunk and returns its plaintext.
     *
     * <p>The critical invariant of the whole streaming design lives here: the plaintext is
     * produced by a single {@code doFinal}, so it is returned only after the GCM tag has
     * verified. Nothing anywhere hands a player bytes from a chunk whose tag has not been
     * checked — a tampered or corrupted object breaks playback, which is the correct failure,
     * and never yields unauthenticated output.
     */
    private static byte[] openChunk(SecretKey key, byte[] noncePrefix, String objectKey,
                                    byte[] onWire, int onWireLen, int index, boolean last)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_LEN, chunkIv(noncePrefix, index, last)));
        cipher.updateAAD(chunkAad(objectKey, index, last));
        return cipher.doFinal(onWire, 0, onWireLen);
    }

    /** Parses and validates the fixed 16-byte chunked header. */
    public static ChunkedHeader parseChunkedHeader(byte[] header) throws IOException {
        if (header == null || header.length < CHUNKED_HEADER_BYTES)
            throw new IOException("Chunked header too short: "
                    + (header == null ? 0 : header.length));
        if (header[0] != CHUNKED_FORMAT_VERSION)
            throw new IOException("Unsupported media format version: " + header[0]);
        byte[] noncePrefix = Arrays.copyOfRange(header, 1, 1 + NONCE_PREFIX_BYTES);
        long len = ByteBuffer.wrap(header, 1 + NONCE_PREFIX_BYTES, 8).getLong();
        if (len < 0) throw new IOException("Negative plaintext length in header");
        int chunkCount = len == 0
                ? 1
                : (int) ((len + CHUNK_PLAINTEXT_SIZE - 1) / CHUNK_PLAINTEXT_SIZE);
        if (chunkCount > MAX_CHUNKS)
            throw new IOException("Header claims " + chunkCount + " chunks (max " + MAX_CHUNKS + ")");
        return new ChunkedHeader(noncePrefix, len, chunkCount);
    }

    /**
     * Fetches a byte range of an encrypted object through the Cloudflare Worker.
     *
     * <p>Worker-only by design. The SigV4 and presigned-URL fallbacks keep using the existing
     * full-download-then-decrypt flow — a known gap, called out rather than papered over: the
     * Worker is the production path, and teaching the other two to range-read would mean
     * signing range headers and re-deriving presign scopes for no user-visible benefit today.
     *
     * <p>The Worker applies a dedicated, bounded allowance to authenticated single byte-range
     * GETs while retaining the lower ordinary-request ceiling and one combined per-user cap.
     * The global daily request safety gate still applies. The 1 MiB chunk size is a cryptographic
     * format boundary, not a way to bypass request accounting.
     *
     * @param endInclusive last byte offset to fetch, inclusive (HTTP {@code Range} semantics).
     */
    private static byte[] fetchRangeViaWorker(String b2Path, long start, long endInclusive)
            throws Exception {
        if (getWorkerUrl().isEmpty())
            throw new NonRetryableException(501,
                    "Chunked streaming requires the storage Worker (WORKER_URL is not set)");
        String objectKey = toObjectKey(b2Path);
        String url       = getWorkerUrl() + "/" + objectKey;
        String range     = "bytes=" + start + "-" + endInclusive;
        int    expected  = (int) (endInclusive - start + 1);

        return withRetry("worker-range:" + objectKey + ":" + range, () -> {
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("X-Client-ID",   getClientId())
                    .header("Authorization", "Bearer " + capabilityTokenCached(objectKey, "read"))
                    .header("Range",         range)
                    .build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 206) {
                    ResponseBody body = response.body();
                    if (body == null) throw new IOException("Empty range body for " + objectKey);
                    byte[] bytes = body.bytes();
                    if (bytes.length != expected)
                        throw new IOException("Range " + range + " returned " + bytes.length
                                + " B, expected " + expected);
                    return bytes;
                }
                ResponseBody errBody = response.body();
                if (code == 200) {
                    // The server ignored Range and is about to stream the whole object. Do not
                    // silently buffer it — for a 95 MB video that is precisely the OOM this
                    // format exists to avoid. Fail loudly so the caller can fall back to the
                    // whole-file path instead.
                    throw new NonRetryableException(200,
                            "Storage tier ignored the Range header for " + objectKey);
                }
                String err = errBody != null ? errBody.string() : "";
                if (code == 404) throw new NonRetryableException(404, "File not found: " + objectKey);
                if (code >= 400 && code < 500)
                    throw new NonRetryableException(code,
                            "Worker range GET failed [" + code + "]: " + err);
                throw new IOException("Worker range GET failed [" + code + "]: " + err);
            }
        });
    }

    /** Fetches and parses the 16-byte header of a chunked object. */
    public static ChunkedHeader fetchChunkedHeader(String b2Path) throws Exception {
        return parseChunkedHeader(fetchRangeViaWorker(b2Path, 0, CHUNKED_HEADER_BYTES - 1));
    }

    /**
     * Fetches chunk {@code index} of a chunked object and returns its verified plaintext.
     * Call from a background thread — this performs network I/O.
     */
    public static byte[] fetchAndDecryptChunk(String b2Path, String keyBase64,
                                              ChunkedHeader header, int index) throws Exception {
        if (index < 0 || index >= header.chunkCount)
            throw new IOException("Chunk index " + index + " out of range (0.."
                    + (header.chunkCount - 1) + ")");
        long start   = header.chunkOffset(index);
        int  onWire  = header.chunkOnWireLength(index);
        byte[] bytes = fetchRangeViaWorker(b2Path, start, start + onWire - 1);
        return openChunk(decodeMediaKey(keyBase64), header.noncePrefix, toObjectKey(b2Path),
                bytes, bytes.length, index, index == header.chunkCount - 1);
    }

    /**
     * Decrypts a complete chunked file on disk into {@code dest}, one chunk at a time.
     *
     * <p>The streaming player never needs this, but "save to gallery" and the disk cache do:
     * they want the whole plaintext as a file, and a chunked object cannot be fed to
     * {@link #decryptFileToFile} (which assumes the single-tag legacy layout). Peak memory is
     * one chunk.
     */
    public static void decryptChunkedFileToFile(File encFile, String keyBase64,
                                                String objectKey, File dest) throws Exception {
        SecretKey key = decodeMediaKey(keyBase64);
        try (RandomAccessFile in = new RandomAccessFile(encFile, "r");
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] hdr = new byte[CHUNKED_HEADER_BYTES];
            in.readFully(hdr);
            ChunkedHeader header = parseChunkedHeader(hdr);
            byte[] buf = new byte[CHUNK_CIPHERTEXT_SIZE];
            for (int i = 0; i < header.chunkCount; i++) {
                int onWire = header.chunkOnWireLength(i);
                if (onWire > buf.length)
                    throw new IOException("Chunk " + i + " claims " + onWire + " B on wire");
                in.seek(header.chunkOffset(i));
                in.readFully(buf, 0, onWire);
                byte[] plain = openChunk(key, header.noncePrefix, objectKey, buf, onWire,
                        i, i == header.chunkCount - 1);
                out.write(plain);
            }
        }
    }

    /**
     * Downloads a chunked object in full and decrypts it to {@code dest}, cleaning up the
     * intermediate encrypted temp file even on failure. The chunked counterpart to
     * {@link #downloadAndDecryptToFile}.
     */
    public static void downloadAndDecryptChunkedToFile(String b2Path, String keyBase64, File dest)
            throws Exception {
        File tmp = File.createTempFile("b2dlc_", ".enc", dest.getParentFile());
        try {
            downloadFileToDisk(b2Path, tmp);
            decryptChunkedFileToFile(tmp, keyBase64, toObjectKey(b2Path), dest);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
