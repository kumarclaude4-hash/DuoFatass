package com.duoshield.app;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;
import com.duoshield.app.util.B2StorageHelper;


import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.Executors;

public class FullScreenImageActivity extends BaseActivity {

    /** Immersive viewer: the return-to-call strip would shift the content. */
    @Override
    protected boolean showsReturnToCallBar() {
        return false;
    }

    public static final String EXTRA_URL         = "image_url";
    public static final String EXTRA_MEDIA_KEY   = "media_key";
    public static final String EXTRA_SENDER_NAME = "sender_name";
    public static final String EXTRA_TIMESTAMP   = "timestamp";
    public static final String EXTRA_CAPTION     = "caption";
    public static final String EXTRA_CONVERSATION_ID = "forward_conversation_id";
    public static final String EXTRA_MY_UID      = "forward_my_uid";
    public static final String EXTRA_PARTNER_UID = "forward_partner_uid";
    public static final String EXTRA_MESSAGE_ID  = "forward_message_id";
    /** When true, this is a bare profile-photo view: no chrome at all, tap/back to dismiss. */
    public static final String EXTRA_IS_PROFILE_PHOTO = "is_profile_photo";

    private PhotoView   photoView;
    private ProgressBar progressBar;
    private View        topBar;
    private View        bottomBar;
    private String      imageUrl;
    private String      mediaKey;

    /** Currently displayed bitmap, kept so Rotate can re-render it in place. */
    private Bitmap  currentBitmap;
    /**
     * The rotated bitmap actually on screen, used as a low-memory fallback when
     * baking rotation into a full-resolution save/share would OOM.
     */
    private Bitmap  displayBitmap;
    /**
     * Cumulative user rotation applied via the Rotate button, in degrees.
     *
     * <p>Volatile because Save and Share read it from the background threads that
     * do the decrypt/encode work, while the Rotate button writes it on the main thread.
     */
    private volatile int rotationDegrees = 0;
    /** Whether the top/bottom chrome is currently shown (toggled by a single tap). */
    private boolean chromeVisible = true;
    /** True for a bare profile-photo view — no toolbar/reply bar, tap or back to close. */
    private boolean isProfilePhoto = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_image);

        photoView   = findViewById(R.id.photo_view);
        progressBar = findViewById(R.id.progress_bar);
        topBar      = findViewById(R.id.top_bar);
        bottomBar   = findViewById(R.id.bottom_bar);
        imageUrl       = getIntent().getStringExtra(EXTRA_URL);
        mediaKey       = getIntent().getStringExtra(EXTRA_MEDIA_KEY);
        isProfilePhoto = getIntent().getBooleanExtra(EXTRA_IS_PROFILE_PHOTO, false);

        if (isProfilePhoto) {
            // Bare photo view, like WhatsApp/Telegram's profile picture viewer:
            // no toolbar, no reply bar — just the image. Tap or system back closes it.
            if (topBar != null) topBar.setVisibility(View.GONE);
            if (bottomBar != null) bottomBar.setVisibility(View.GONE);
            if (photoView != null) {
                photoView.setOnPhotoTapListener((view, x, y) -> finish());
                photoView.setOnOutsidePhotoTapListener(view -> finish());
            }
        } else {
            bindHeader();

            ImageButton btnClose = findViewById(R.id.btn_close);
            if (btnClose != null) btnClose.setOnClickListener(v -> finish());

            ImageButton btnSave = findViewById(R.id.btn_save);
            if (btnSave != null) btnSave.setOnClickListener(v -> saveImageToGallery());

            ImageButton btnShare = findViewById(R.id.btn_share);
            if (btnShare != null) btnShare.setOnClickListener(v -> shareImage());

            ImageButton btnMore = findViewById(R.id.btn_more);
            if (btnMore != null) btnMore.setOnClickListener(this::showMoreMenu);

            ImageButton btnForward = findViewById(R.id.btn_forward);
            if (btnForward != null) btnForward.setOnClickListener(v -> forwardImage());

            View.OnClickListener rotate = v -> rotateImage();
            ImageButton btnRotate = findViewById(R.id.btn_rotate);
            if (btnRotate != null) btnRotate.setOnClickListener(rotate);

            // Single tap on the photo toggles the chrome (immersive, like WhatsApp/Telegram).
            // PhotoView surfaces this via its own tap listener so it never fights pinch/zoom.
            if (photoView != null) {
                photoView.setOnPhotoTapListener((view, x, y) -> toggleChrome());
                photoView.setOnOutsidePhotoTapListener(view -> toggleChrome());
            }
        }

        if (imageUrl != null && photoView != null) loadImageIntoViewer();
    }

    /** Fills the top bar with the sender name and a friendly relative timestamp. */
    private void bindHeader() {
        String sender = getIntent().getStringExtra(EXTRA_SENDER_NAME);
        long   ts     = getIntent().getLongExtra(EXTRA_TIMESTAMP, 0L);
        String caption = getIntent().getStringExtra(EXTRA_CAPTION);

        TextView title = findViewById(R.id.tv_title);
        if (title != null && sender != null && !sender.trim().isEmpty()) {
            title.setText(sender);
        }
        TextView subtitle = findViewById(R.id.tv_subtitle);
        if (subtitle != null && ts > 0) {
            subtitle.setText(com.duoshield.app.util.MediaMetaFormatter.relativeDateTime(ts));
            subtitle.setVisibility(View.VISIBLE);
        }
        TextView cap = findViewById(R.id.tv_caption);
        if (cap != null && caption != null && !caption.trim().isEmpty()) {
            cap.setText(caption.trim());
            cap.setVisibility(View.VISIBLE);
        }
    }

    /** Shows/hides the top & bottom chrome with a short fade. */
    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        animateBar(topBar, chromeVisible);
        animateBar(bottomBar, chromeVisible);
    }

    private void animateBar(View bar, boolean show) {
        if (bar == null) return;
        bar.animate().cancel();
        if (show) {
            bar.setVisibility(View.VISIBLE);
            bar.animate().alpha(1f).setDuration(180).start();
        } else {
            bar.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> bar.setVisibility(View.GONE)).start();
        }
    }

    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Share");
        popup.getMenu().add(0, 2, 0, "Show in chat");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                shareImage();
                return true;
            }
            if (item.getItemId() == 2) {
                finish();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void forwardImage() {
        String conversationId = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
        String myUid = getIntent().getStringExtra(EXTRA_MY_UID);
        String partnerUid = getIntent().getStringExtra(EXTRA_PARTNER_UID);
        String messageId = getIntent().getStringExtra(EXTRA_MESSAGE_ID);
        if (conversationId == null || myUid == null || partnerUid == null || messageId == null) {
            Toast.makeText(this, "Forward is unavailable for this media", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent picker = new android.content.Intent(this, ConversationListActivity.class);
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_MODE, true);
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_MESSAGE_ID, messageId);
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_SOURCE_CONVERSATION, conversationId);
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_SOURCE_SENDER, "");
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_MEDIA_URL, imageUrl);
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_MEDIA_KEY, mediaKey);
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_MEDIA_TYPE, "image");
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_TEXT,
                getIntent().getStringExtra(EXTRA_CAPTION));
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_TIMESTAMP,
                getIntent().getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis()));
        startActivity(picker);
    }

    /** Rotates the shown image 90° clockwise per tap — handy for sideways photos. */
    private void rotateImage() {
        if (currentBitmap == null || photoView == null) return;
        rotationDegrees = (rotationDegrees + 90) % 360;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(rotationDegrees);
        try {
            Bitmap rotated = Bitmap.createBitmap(currentBitmap, 0, 0,
                    currentBitmap.getWidth(), currentBitmap.getHeight(), m, true);
            displayBitmap = rotated;
            photoView.setImageBitmap(rotated);
        } catch (OutOfMemoryError oom) {
            Toast.makeText(this, "Not enough memory to rotate", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Re-encodes {@code src} with the user's current rotation baked in, so a photo
     * straightened with the Rotate button does not come out sideways again in the
     * gallery or in the app it was shared to.
     *
     * <p>Rotation is applied to the freshly fetched full-resolution bytes rather than to
     * the on-screen bitmap, because the displayed copy is deliberately downsampled to the
     * view size ({@code inSampleSize}) — saving that would silently cost resolution.
     *
     * <p>A full-resolution decode is exactly the allocation the viewer avoids elsewhere, so
     * an {@link OutOfMemoryError} on a low-RAM device is a real outcome, not a theoretical
     * one. When it happens the already-rotated display bitmap is used instead: a correctly
     * oriented smaller image beats a sideways large one. Only if there is no display bitmap
     * either do the original bytes go through untouched.
     *
     * <p>Must be called off the main thread.
     *
     * @return rotated JPEG bytes, or {@code src} unchanged when no rotation is pending.
     */
    private byte[] withRotationBaked(byte[] src) {
        final int degrees = rotationDegrees;
        if (src == null || degrees == 0) return src;

        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(degrees);
        Bitmap full = null, rotated = null;
        try {
            full = BitmapFactory.decodeByteArray(src, 0, src.length);
            if (full == null) return src;
            rotated = Bitmap.createBitmap(full, 0, 0, full.getWidth(), full.getHeight(), m, true);
            return compressJpeg(rotated);
        } catch (OutOfMemoryError oom) {
            android.util.Log.w("FullScreenImage",
                    "Full-res rotate OOM — falling back to the display bitmap");
            Bitmap shown = displayBitmap;
            if (shown != null && !shown.isRecycled()) {
                try {
                    return compressJpeg(shown);
                } catch (OutOfMemoryError ignored) {
                    return src;
                }
            }
            return src;
        } catch (Exception e) {
            android.util.Log.w("FullScreenImage", "Rotate-on-export failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return src;
        } finally {
            // Only recycle the copies this method made; never the shared display bitmap.
            if (rotated != null && rotated != full) rotated.recycle();
            if (full != null && full != currentBitmap && full != displayBitmap) full.recycle();
        }
    }

    private static byte[] compressJpeg(Bitmap bmp) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
        return out.toByteArray();
    }

    // ── Image loading (decrypt-first for B2 / Supabase) ───────────────────────

    private void showProgress(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * Decodes bytes to a Bitmap on a background thread, then hands it to PhotoView.
     *
     * <p>Decoding runs off the main thread to avoid ANR on slow CPUs (Helio G36 /
     * POCO C51 takes ~300-800 ms for a high-res JPEG on its A53 cores).
     *
     * <p>{@code inSampleSize} is derived from the PhotoView's display area, so we
     * never allocate more pixels than the screen can show — critical on 2-4 GB
     * devices where a 12 MP image at full resolution would consume ~35 MB RAM.
     */
    private void displayBytes(byte[] plainBytes) {
        if (isDestroyed() || isFinishing()) return;
        final int reqW = (photoView != null) ? photoView.getWidth()  : 0;
        final int reqH = (photoView != null) ? photoView.getHeight() : 0;
        Executors.newSingleThreadExecutor().execute(() -> {
            // Pass 1: decode bounds only (no pixel allocation) to calculate inSampleSize
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length, opts);
            opts.inSampleSize      = calculateInSampleSize(opts, reqW, reqH);
            opts.inJustDecodeBounds = false;
            // Pass 2: decode at reduced resolution
            Bitmap bmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length, opts);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                showProgress(false);
                if (bmp != null) {
                    currentBitmap   = bmp;
                    displayBitmap   = null;
                    rotationDegrees = 0;
                    photoView.setImageBitmap(bmp);
                } else {
                    // fallback — let Glide try (may still work for some formats).
                    // Rotate stays disabled here because we never hold the raw bitmap.
                    Glide.with(FullScreenImageActivity.this).load(plainBytes).into(photoView);
                }
            });
        });
    }

    /**
     * Calculates the largest power-of-two {@code inSampleSize} such that the
     * decoded image is no larger than {@code reqWidth × reqHeight}.
     * Returns 1 (full resolution) when either dimension is unknown.
     */
    private static int calculateInSampleSize(BitmapFactory.Options options,
                                             int reqWidth, int reqHeight) {
        int rawH = options.outHeight;
        int rawW = options.outWidth;
        if (reqWidth <= 0 || reqHeight <= 0) return 1;
        int inSampleSize = 1;
        if (rawH > reqHeight || rawW > reqWidth) {
            int halfH = rawH / 2;
            int halfW = rawW / 2;
            while ((halfH / inSampleSize) >= reqHeight
                    && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // Timeout handler: if neither onLoaded nor onError fires within 30 s,
    // clear the spinner so the screen isn't stuck indefinitely.
    private final android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable loadTimeoutRunnable = () -> {
        if (isDestroyed() || isFinishing()) return;
        showProgress(false);
        Toast.makeText(this, "Image is taking too long to load. Check your connection.", Toast.LENGTH_LONG).show();
    };

    private void loadImageIntoViewer() {
        showProgress(true);
        timeoutHandler.postDelayed(loadTimeoutRunnable, 30_000);

        if (B2StorageHelper.isB2Path(imageUrl)) {
            if (mediaKey != null && !mediaKey.isEmpty()) {
                // Encrypted media (chat photos / videos)
                B2StorageHelper.loadMedia(this, imageUrl, mediaKey, new B2StorageHelper.MediaCallback() {
                    @Override public void onLoaded(byte[] plainBytes) {
                        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
                        displayBytes(plainBytes);
                    }
                    @Override public void onError(Exception e) {
                        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
                        if (isDestroyed() || isFinishing()) return;
                        showProgress(false);
                        android.util.Log.e("FullScreenImage", "B2 media load error (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                        Toast.makeText(FullScreenImageActivity.this,
                                "Failed to load image — " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                // Avatar (SigV4 authenticated, not AES-encrypted — use loadAvatarBytes)
                B2StorageHelper.loadAvatarBytes(imageUrl, new B2StorageHelper.MediaCallback() {
                    @Override public void onLoaded(byte[] bytes) {
                        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
                        displayBytes(bytes);
                    }
                    @Override public void onError(Exception e) {
                        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
                        if (isDestroyed() || isFinishing()) return;
                        showProgress(false);
                        android.util.Log.e("FullScreenImage", "B2 avatar load error (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                        Toast.makeText(FullScreenImageActivity.this,
                                "Could not load photo", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } else {
            // Local file:// URI or legacy plain HTTPS URL — Glide handles both
            timeoutHandler.removeCallbacks(loadTimeoutRunnable);
            showProgress(false);
            Glide.with(this).load(
                imageUrl.startsWith("file://") ? android.net.Uri.parse(imageUrl) : imageUrl
            ).into(photoView);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
    }

    // ── Save to gallery ───────────────────────────────────────────────────────

    private void saveImageToGallery() {
        if (imageUrl == null) return;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();

        if (B2StorageHelper.isB2Path(imageUrl)) {
            B2StorageHelper.loadMedia(this, imageUrl, mediaKey, new B2StorageHelper.MediaCallback() {
                @Override public void onLoaded(byte[] plainBytes) {
                    Executors.newSingleThreadExecutor().execute(() -> writeImageToGallery(plainBytes));
                }
                @Override public void onError(Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(FullScreenImageActivity.this,
                            "Failed to decrypt image", Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            // Legacy public URL
            Executors.newSingleThreadExecutor().execute(() -> {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
                    writeImageToGallery(baos.toByteArray());
                } catch (Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(FullScreenImageActivity.this,
                            "Save failed", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    /** Writes to the gallery. Runs on a background thread (rotation re-encodes here). */
    private void writeImageToGallery(byte[] rawBytes) {
        final byte[] plainBytes = withRotationBaked(rawBytes);
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "duoshield_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DuoShield");
            }
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out != null) {
                    try { out.write(plainBytes); } finally { out.close(); }
                }
                // F37 fix: record the URI so wipe/duress logout can delete it from the gallery.
                com.duoshield.app.util.MediaStoreWipeHelper.recordUri(
                        FullScreenImageActivity.this, uri);
            }
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing())
                    Toast.makeText(FullScreenImageActivity.this,
                            "Saved to gallery", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing())
                    Toast.makeText(FullScreenImageActivity.this,
                            "Save failed", Toast.LENGTH_SHORT).show();
            });
        }
    }

    // ── Share ────��────────────────────────────────────────────────────────────

    private void shareImage() {
        if (imageUrl == null) return;
        Toast.makeText(this, "Preparing image…", Toast.LENGTH_SHORT).show();

        if (B2StorageHelper.isB2Path(imageUrl)) {
            B2StorageHelper.loadMedia(this, imageUrl, mediaKey, new B2StorageHelper.MediaCallback() {
                @Override public void onLoaded(byte[] plainBytes) {
                    if (isDestroyed() || isFinishing()) return;
                    // Hop to a worker: withRotationBaked() does a full-res decode + JPEG
                    // encode, and this callback can land on the main thread.
                    Executors.newSingleThreadExecutor().execute(() -> {
                        final byte[] out = withRotationBaked(plainBytes);
                        if (isDestroyed() || isFinishing()) return;
                        runOnUiThread(() ->
                                com.duoshield.app.util.SecureShareHelper.shareImage(
                                        FullScreenImageActivity.this, out));
                    });
                }
                @Override public void onError(Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(FullScreenImageActivity.this,
                            "Share failed", Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            // Legacy public URL
            Executors.newSingleThreadExecutor().execute(() -> {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
                    final byte[] bytes = withRotationBaked(baos.toByteArray());
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() ->
                            com.duoshield.app.util.SecureShareHelper.shareImage(
                                    FullScreenImageActivity.this, bytes));
                } catch (Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(FullScreenImageActivity.this,
                            "Share failed", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }
}
