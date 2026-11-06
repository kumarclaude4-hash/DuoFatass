package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Shares decrypted media bytes with other apps via {@code ACTION_SEND}.
 *
 * <p>The previous implementation re-downloaded the Supabase blob (which is
 * AES-GCM ciphertext) and shared the raw ciphertext as a "JPEG".  Recipients
 * received an undecodable file, and expired signed URLs caused silent failures
 * (BUG-E02).
 *
 * <p>Callers must now pass already-decrypted bytes from memory.  This
 * eliminates the HTTP round-trip, the signed-URL race, and the ciphertext-
 * sharing bug.  The {@code share_*.jpg} temp file is removed by
 * {@link TempFileCleaner} (BUG-SD02 fix also included in Wave-1).
 */
public class SecureShareHelper {

    public static void shareImage(Context ctx, byte[] decryptedBytes) {
        new Thread(() -> {
            // S08-M2: write into the FileProvider-scoped shared/media/ subdir
            // rather than the cache root, so the grant below is not scoped to
            // the whole cache directory.
            File out = new File(SharedCacheDir.media(ctx),
                    "share_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(decryptedBytes);
            } catch (Exception e) {
                android.util.Log.e("SecureShareHelper", "Failed to write share temp file (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                return;
            }
            try {
                Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".provider", out);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/jpeg");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(intent, "Share Image")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                android.util.Log.e("SecureShareHelper", "Failed to launch share chooser (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        }).start();
    }

    /**
     * Shares an already-decrypted video that lives at {@code plainFile}.
     *
     * <p>Copies the plaintext into the FileProvider-scoped {@code shared/media/} subdir
     * (same posture as {@link #shareImage}) rather than exposing the player's live
     * scratch/cache file directly — the shared copy is disposable and swept by
     * {@link TempFileCleaner}, and the grant stays scoped to one file rather than the
     * whole cache dir. Copy runs on a background thread since a video can be large.
     */
    public static void shareVideo(Context ctx, File plainFile) {
        if (plainFile == null || !plainFile.exists() || plainFile.length() == 0) {
            android.util.Log.w("SecureShareHelper", "shareVideo: source file missing/empty");
            return;
        }
        new Thread(() -> {
            File out = new File(SharedCacheDir.media(ctx),
                    "share_" + System.currentTimeMillis() + ".mp4");
            try (java.io.FileInputStream in = new java.io.FileInputStream(plainFile);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            } catch (Exception e) {
                android.util.Log.e("SecureShareHelper", "Failed to write share video temp file (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                return;
            }
            try {
                Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".provider", out);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("video/mp4");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(intent, "Share Video")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                android.util.Log.e("SecureShareHelper", "Failed to launch video share chooser (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        }).start();
    }
}
