package com.duoshield.app.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import com.duoshield.app.models.Message;
import java.util.UUID;

/**
 * Forwards a message to the current conversation.
 *
 * <p><b>Text messages</b> are re-sent via {@link MessageBuilder} with the
 * {@code forwarded} flag set to {@code true}; no text prefix is added.
 *
 * <p><b>Media messages</b> (image / video / voice) are <em>re-encrypted</em>
 * with a fresh per-file AES-256-GCM key before forwarding (BUG-MB02).  The
 * original key is intentionally not reused: sharing the same {@code mediaKey}
 * across two separate conversations would allow a compromised conversation
 * partner to decrypt the other conversation's media.  The file bytes are
 * downloaded from B2 (or legacy Supabase), decrypted with the original key,
 * re-encrypted with a new key, and re-uploaded to B2 at a new path — ensuring
 * forward isolation.
 */
public class ForwardMessageHelper {

    private static final String TAG = "ForwardMessageHelper";

    public static void forward(Context ctx, Message msg,
                               String convId, String myUid, String partnerUid) {
        if (msg == null) return;
        String type = msg.getMediaType();
        if (type == null) type = "text";

        switch (type) {
            case "image":
            case "video":
            case "voice": {
                String origPath = msg.getMediaUrl();
                String origKey  = msg.getMediaKey();

                if (origPath == null || origPath.isEmpty()) {
                    Toast.makeText(ctx, "Cannot forward — media not available",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                final String mediaType = type;

                // Download + decrypt via B2, then re-encrypt + re-upload to B2
                B2StorageHelper.loadMedia(origPath, origKey, new B2StorageHelper.MediaCallback() {
                    @Override
                    public void onLoaded(byte[] plainBytes) {
                        reEncryptAndUploadToB2(ctx, plainBytes, mediaType, convId, myUid, partnerUid);
                    }
                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "B2 download failed for forward" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                        new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(ctx, "Cannot forward — download failed",
                                Toast.LENGTH_SHORT).show());
                    }
                });
                break;
            }
            default: {
                String plaintext = msg.getText() != null ? msg.getText() : "";
                if (plaintext.isEmpty()) {
                    Toast.makeText(ctx, "Nothing to forward", Toast.LENGTH_SHORT).show();
                    return;
                }
                MessageBuilder.sendTextMessage(ctx, convId, myUid, partnerUid,
                        plaintext, null, null, /* forwarded= */ true);
                break;
            }
        }
    }

    /**
     * Re-encrypts {@code plainBytes} with a fresh AES-256-GCM key and uploads to B2.
     * Always targets B2 regardless of where the original was stored.
     */
    private static void reEncryptAndUploadToB2(Context ctx, byte[] plainBytes,
                                                String mediaType, String convId,
                                                String myUid, String partnerUid) {
        if (plainBytes == null || plainBytes.length == 0) {
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, "Cannot forward — download failed",
                    Toast.LENGTH_SHORT).show());
            return;
        }

        new Thread(() -> {
            try {
                B2StorageHelper.EncryptedMedia em = B2StorageHelper.encryptForUpload(plainBytes);

                String ext  = "voice".equals(mediaType) ? ".3gp"
                            : "video".equals(mediaType) ? ".mp4" : ".jpg";
                String mime = "voice".equals(mediaType) ? "audio/3gpp"
                            : "video".equals(mediaType) ? "video/mp4" : "image/jpeg";
                String objectKey = "media/" + convId + "/" + UUID.randomUUID() + ext;
                String stored    = B2StorageHelper.uploadFile(em.data, objectKey, mime, null);

                MessageBuilder.sendMediaMessage(ctx, convId, myUid, partnerUid,
                        stored, mediaType, em.keyBase64, /* forwarded= */ true);

            } catch (Exception e) {
                Log.e(TAG, "Re-encrypt + B2 re-upload failed for forward" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, "Forward failed — check connection",
                        Toast.LENGTH_SHORT).show());
            }
        }, "forward-reencrypt-b2").start();
    }
}
