package com.duoshield.app;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Single source of truth for outbound media size limits.
 *
 * <p>Before this class existed the upload-size cap was enforced on the single
 * photo/video path and the album path only. Documents and voice notes had no
 * cap at all, so an arbitrarily large file could be read fully into memory,
 * encrypted, and pushed at the upload endpoint — which then failed with an
 * opaque HTTP 413 after the user had already waited through the whole transfer.
 *
 * <p>Every outbound media path now calls {@link #checkOversize} <em>before</em>
 * any read, compression, encryption, or upload work begins.
 */
public final class MediaLimits {

    private MediaLimits() {}

    /**
     * Hard ceiling for a plaintext outbound file.
     *
     * <p>The Worker accepts at most 95,000,000 encrypted bytes. Whole-blob
     * AES-GCM adds a 12-byte IV and 16-byte tag; chunked media adds a 16-byte
     * header and one 16-byte tag per 1 MiB chunk. Keep 1 MB of headroom so
     * every permitted plaintext file still fits after encryption.
     */
    public static final long MAX_BYTES = 94_000_000L;

    /**
     * Videos above this get routed through {@link VideoTranscoder} first. It is
     * the same value as the hard cap: anything over the ceiling is a transcode
     * candidate rather than an immediate rejection.
     */
    public static final long TRANSCODE_TRIGGER_BYTES = MAX_BYTES;

    /**
     * Resolves the size of a content:// or file:// Uri in bytes.
     *
     * @return size in bytes, or -1 when the size genuinely cannot be determined.
     *         Callers must treat -1 as "unknown", NOT as "zero" — the previous
     *         inline helper returned 0 on failure, which silently bypassed the
     *         size check for any provider that does not report SIZE.
     */
    public static long sizeOf(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return -1;

        // Preferred: ask the provider for OpenableColumns.SIZE.
        try (Cursor c = ctx.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    long size = c.getLong(idx);
                    if (size > 0) return size;
                }
            }
        } catch (Exception ignored) {
            // fall through to the descriptor / file probes below
        }

        // Fallback for providers that do not implement SIZE.
        try (android.content.res.AssetFileDescriptor afd =
                     ctx.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null) {
                long len = afd.getLength();
                if (len >= 0 && len != android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH) {
                    return len;
                }
            }
        } catch (Exception ignored) {
            // fall through to the raw file probe
        }

        // Last resort: a direct file path (voice notes are recorded to app storage).
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists()) return f.length();
        }

        return -1;
    }

    /** Size of a plain {@link File}, or -1 when it does not exist. */
    public static long sizeOf(File f) {
        return (f != null && f.exists()) ? f.length() : -1;
    }

    /** True when {@code bytes} is a known size that exceeds the cap. */
    public static boolean isOversize(long bytes) {
        return bytes > MAX_BYTES;
    }

    /**
     * Checks a Uri against the cap.
     *
     * @return null when the file is acceptable (or its size is genuinely
     *         unknown), otherwise a user-facing rejection message.
     */
    public static String checkOversize(Context ctx, Uri uri, String label) {
        long size = sizeOf(ctx, uri);
        if (size < 0) return null;         // unknown size — let the upload path surface any error
        if (!isOversize(size)) return null;
        return tooLargeMessage(size, label);
    }

    /** Consistent rejection copy, so every path reports the cap the same way. */
    public static String tooLargeMessage(long actualBytes, String label) {
        String what = (label == null || label.isEmpty()) ? "File" : label;
        return what + " is too large (" + format(actualBytes)
                + "). The maximum is " + format(MAX_BYTES) + ".";
    }

    /** Human-readable byte count, e.g. "512 MB" / "1.4 GB". */
    public static String format(long bytes) {
        if (bytes < 0) return "unknown size";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(java.util.Locale.US, "%.2f GB", gb);
    }
}
