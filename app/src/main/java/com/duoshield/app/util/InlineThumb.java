package com.duoshield.app.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Telegram-style inline thumbnails.
 *
 * <p><b>The problem this solves.</b> A media message document carries only a
 * {@code path} + {@code mediaKey}. To draw <em>anything</em> in the bubble the app had to
 * complete a full B2 download and an AES-GCM decrypt of the entire object first — so a
 * photo bubble stayed empty for the whole round trip, and a video bubble was worse: the
 * old {@code loadVideoThumbnail} downloaded and decrypted the <em>complete video</em>
 * just to pull one frame. A 200 MB clip meant a 200 MB transfer to paint a 100 dp preview,
 * which on a 2 GB device is not merely slow, it is an OOM.
 *
 * <p><b>The fix.</b> The sender already holds the full-resolution bitmap in memory, on the
 * local device, before the upload starts. Generating a ~1.5 KB postage-stamp JPEG at that
 * moment costs a few milliseconds and zero network. That blob is sealed under the same
 * media key, base64'd, and carried <em>inside the message document itself</em>. The
 * receiver therefore has a renderable preview the instant the Firestore snapshot lands —
 * no second network hop, no decrypt of the real object, and for video no download at all.
 *
 * <p><b>Size budget.</b> Firestore's per-document ceiling is 1 MiB, but the constraint
 * that actually matters is the message-stream snapshot: every byte here is paid on every
 * listener delivery. {@link #MAX_ENCODED_BYTES} caps the JPEG at 1800 bytes before
 * base64 (~2.4 KB after), reached by stepping quality down and, if still over, halving
 * the edge. That is small enough to be free relative to the document's own overhead.
 *
 * <p><b>Why deliberately blurry.</b> {@link #MAX_EDGE} is 72 px. The thumbnail is not
 * trying to be the image — it is a low-frequency stand-in that establishes colour, layout
 * and subject placement so the bubble never flashes empty. The full-resolution decrypt
 * swaps in over the top when it arrives.
 */
public final class InlineThumb {

    private static final String TAG = "InlineThumb";

    /** Longest edge of the generated thumbnail, in pixels. */
    private static final int MAX_EDGE = 72;

    /** Hard ceiling on the JPEG payload before base64 encoding. */
    private static final int MAX_ENCODED_BYTES = 1800;

    /** Starting JPEG quality; stepped down by {@link #QUALITY_STEP} until under budget. */
    private static final int START_QUALITY = 55;
    private static final int MIN_QUALITY   = 25;
    private static final int QUALITY_STEP  = 10;

    private InlineThumb() {}

    // ── Decode cache ─────────────────────────────────────────────────────────
    //
    // A RecyclerView re-binds the same row many times during a scroll. Without a cache
    // every bind would repeat base64-decode → AES-GCM decrypt → JPEG decode on the main
    // thread, which is exactly the kind of per-frame work that produces jank on the
    // low-end devices this is meant to help. Keyed by the ciphertext string, so it is
    // self-invalidating: a different thumbnail is a different key.
    //
    // 1.5 MB holds roughly 500 decoded stamps — far more than any screen needs.
    private static final LruCache<String, byte[]> DECODED =
            new LruCache<String, byte[]>(1536 * 1024) {
                @Override protected int sizeOf(String key, byte[] value) { return value.length; }
            };

    /** Sentinel stored in {@link #DECODED} for input that failed to decrypt, so we try once only. */
    private static final byte[] FAILED = new byte[0];

    // ── Generation (sender side) ─────────────────────────────────────────────

    /**
     * Builds an inline thumbnail from already-in-memory image bytes.
     *
     * <p>Uses a two-pass decode: the first pass reads only the JPEG header
     * ({@code inJustDecodeBounds}) to learn the true dimensions, so the second pass can
     * pick an {@code inSampleSize} that lets the decoder skip pixels in hardware rather
     * than materialising a full 12-megapixel bitmap just to shrink it. On a 4000x3000
     * photo this is the difference between a ~48 MB allocation and a ~150 KB one.
     *
     * @return base64 JPEG (not yet encrypted), or {@code null} if generation failed.
     */
    public static String fromImageBytes(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
            // RGB_565 halves the intermediate bitmap's footprint. At 72 px the loss of
            // per-channel precision is invisible, and the JPEG encoder discards more
            // than that anyway.
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap decoded = BitmapFactory.decodeByteArray(
                    imageBytes, 0, imageBytes.length, opts);
            if (decoded == null) return null;
            return encodeScaled(decoded);
        } catch (Throwable t) {
            Log.w(TAG, "fromImageBytes failed: " + com.duoshield.app.util.LogRedact.error(t));
            return null;
        }
    }

    /**
     * Builds an inline thumbnail from a local image {@link Uri} without ever holding the
     * full file in memory — the header pass and the subsampled pass each open their own
     * stream, so peak usage is bounded by the downscaled bitmap rather than the file.
     */
    public static String fromImageUri(ContentResolver cr, Uri uri) {
        if (cr == null || uri == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap decoded;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                decoded = BitmapFactory.decodeStream(in, null, opts);
            }
            if (decoded == null) return null;
            return encodeScaled(decoded);
        } catch (Throwable t) {
            Log.w(TAG, "fromImageUri failed: " + com.duoshield.app.util.LogRedact.error(t));
            return null;
        }
    }

    /**
     * Builds an inline thumbnail from a local video {@link Uri}.
     *
     * <p>This is the single most valuable entry point in the class. The frame is pulled
     * from the file already sitting on the sender's disk, so the receiver never has to
     * download a byte of video to see a preview — replacing a full-object download and
     * decrypt with ~1.5 KB carried in the message document.
     *
     * <p>The frame is taken at 0 µs with {@code OPTION_CLOSEST_SYNC}, which lands on the
     * first keyframe. Seeking further in would risk a slow non-keyframe decode on weak
     * hardware for no perceptual gain.
     */
    public static String fromVideoUri(android.content.Context ctx, Uri uri) {
        if (ctx == null || uri == null) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            retriever.setDataSource(ctx, uri);
            frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            return encodeScaled(frame);
        } catch (Throwable t) {
            Log.w(TAG, "fromVideoUri failed: " + com.duoshield.app.util.LogRedact.error(t));
            return null;
        } finally {
            if (frame != null) frame.recycle();
            try { retriever.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Seals a generated thumbnail under the media key and returns it base64-encoded,
     * ready to be written straight into the message document.
     *
     * <p>Reusing the media key rather than minting a new one means the thumbnail carries
     * exactly the same confidentiality as the object it previews — it can never become
     * the weak link that leaks a recognisable preview of media the reader could not
     * otherwise open.
     *
     * @return base64 of {@code [12-byte IV | ciphertext | 16-byte GCM tag]}, or
     *         {@code null} when there is nothing to seal.
     */
    public static String seal(String base64Jpeg, String mediaKeyBase64) {
        if (base64Jpeg == null || base64Jpeg.isEmpty()) return null;
        try {
            byte[] jpeg = Base64.decode(base64Jpeg, Base64.NO_WRAP);
            byte[] sealed = B2StorageHelper.encryptWithKey(jpeg, mediaKeyBase64);
            return Base64.encodeToString(sealed, Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.w(TAG, "seal failed: " + com.duoshield.app.util.LogRedact.error(t));
            return null;
        }
    }

    /** Convenience: generate from image bytes and seal in one step. */
    public static String sealedFromImageBytes(byte[] imageBytes, String mediaKeyBase64) {
        return seal(fromImageBytes(imageBytes), mediaKeyBase64);
    }

    /** Convenience: generate from a local video URI and seal in one step. */
    public static String sealedFromVideoUri(android.content.Context ctx, Uri uri,
                                            String mediaKeyBase64) {
        return seal(fromVideoUri(ctx, uri), mediaKeyBase64);
    }

    // ── Consumption (receiver side) ──────────────────────────────────────────

    /**
     * Decrypts an inline thumbnail into raw JPEG bytes ready for Glide.
     *
     * <p>Safe to call from the main thread and from a RecyclerView bind: the first call
     * for a given ciphertext does the base64 + AES work, every later call is an LruCache
     * hit. Failures are cached as negative results so a corrupt or key-mismatched
     * thumbnail cannot turn into repeated decrypt attempts on every scroll frame.
     *
     * @return JPEG bytes, or {@code null} if absent or undecryptable.
     */
    public static byte[] decode(String sealedBase64, String mediaKeyBase64) {
        if (sealedBase64 == null || sealedBase64.isEmpty()) return null;

        byte[] hit = DECODED.get(sealedBase64);
        if (hit != null) return hit == FAILED ? null : hit;

        try {
            byte[] sealed = Base64.decode(sealedBase64, Base64.NO_WRAP);
            byte[] jpeg   = B2StorageHelper.decryptAfterDownload(sealed, mediaKeyBase64);
            if (jpeg == null || jpeg.length == 0) {
                DECODED.put(sealedBase64, FAILED);
                return null;
            }
            DECODED.put(sealedBase64, jpeg);
            return jpeg;
        } catch (Throwable t) {
            // Not worth a warning per row — a legacy message simply has no usable thumb.
            DECODED.put(sealedBase64, FAILED);
            return null;
        }
    }

    /** True when {@code sealedBase64} looks like a usable inline thumbnail. */
    public static boolean isPresent(String sealedBase64) {
        return sealedBase64 != null && !sealedBase64.isEmpty();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Largest power-of-two subsample that still leaves the bitmap at or above
     * {@link #MAX_EDGE}. BitmapFactory rounds down to a power of two internally, so
     * computing it explicitly keeps the result predictable.
     */
    private static int sampleSizeFor(int width, int height) {
        int longest = Math.max(width, height);
        int sample  = 1;
        while (longest / (sample * 2) >= MAX_EDGE) sample *= 2;
        return sample;
    }

    /**
     * Scales {@code src} so its longest edge is {@link #MAX_EDGE}, then JPEG-encodes it,
     * stepping quality down until the result fits {@link #MAX_ENCODED_BYTES}. If even the
     * minimum quality overshoots — which happens with very noisy frames, where JPEG's
     * DCT cannot compact high-frequency detail — the edge is halved and the ladder is
     * retried once. Recycles both the source and the scaled intermediate.
     */
    private static String encodeScaled(Bitmap src) {
        try {
            String out = encodeLadder(src, MAX_EDGE);
            if (out != null) return out;
            // Fall back to a smaller stamp rather than blowing the size budget.
            return encodeLadder(src, MAX_EDGE / 2);
        } finally {
            if (!src.isRecycled()) src.recycle();
        }
    }

    private static String encodeLadder(Bitmap src, int maxEdge) {
        Bitmap scaled = null;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            float ratio = Math.min((float) maxEdge / w, (float) maxEdge / h);
            int tw = Math.max(1, Math.round(w * ratio));
            int th = Math.max(1, Math.round(h * ratio));

            scaled = Bitmap.createScaledBitmap(src, tw, th, true);

            for (int q = START_QUALITY; q >= MIN_QUALITY; q -= QUALITY_STEP) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(MAX_ENCODED_BYTES);
                scaled.compress(Bitmap.CompressFormat.JPEG, q, baos);
                byte[] jpeg = baos.toByteArray();
                if (jpeg.length <= MAX_ENCODED_BYTES) {
                    return Base64.encodeToString(jpeg, Base64.NO_WRAP);
                }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "encodeLadder failed: " + com.duoshield.app.util.LogRedact.error(t));
            return null;
        } finally {
            if (scaled != null && scaled != src && !scaled.isRecycled()) scaled.recycle();
        }
    }
}
