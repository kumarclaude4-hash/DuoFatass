package com.duoshield.app;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import com.duoshield.app.util.B2StorageHelper;
import com.duoshield.app.util.ChunkedMediaDataSource;
import com.duoshield.app.util.DevicePerformanceTier;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;

public class MediaViewerActivity extends BaseActivity {

    /** Immersive viewer: the return-to-call strip would shift the content. */
    @Override
    protected boolean showsReturnToCallBar() {
        return false;
    }

    public static final String EXTRA_URL       = "media_url";
    public static final String EXTRA_MEDIA_KEY = "media_key";
    /**
     * Whether the object named by {@link #EXTRA_URL} is stored in the chunked v2 format.
     *
     * <p>Carried as an intent extra rather than sniffed off the object's leading version byte:
     * the byte is attacker-influenced, and letting it select the decrypt path would mean a
     * remote party choosing which crypto code runs. The flag originates on the message document
     * that named the object, so it is as trustworthy as the media key beside it. Defaults to
     * false, which is the truth for every object written before this format existed.
     */
    public static final String EXTRA_CHUNKED  = "media_chunked";

    public static final String EXTRA_SENDER_NAME = "sender_name";
    public static final String EXTRA_TIMESTAMP   = "timestamp";
    public static final String EXTRA_CAPTION     = "caption";
    public static final String EXTRA_CONVERSATION_ID = "forward_conversation_id";
    public static final String EXTRA_MY_UID      = "forward_my_uid";
    public static final String EXTRA_PARTNER_UID = "forward_partner_uid";
    public static final String EXTRA_MESSAGE_ID  = "forward_message_id";

    private ExoPlayer   player;
    private PlayerView  playerView;
    private ProgressBar progressBar;
    private View        topBar;
    private TextView    captionView;
    private String      mediaRef;
    private String      mediaKey;
    private boolean     chunked;
    /**
     * Decrypted scratch file backing the player, deleted in {@link #onDestroy()}.
     *
     * <p>The previous implementation relied on {@code File.deleteOnExit()}, which only runs on
     * an orderly JVM shutdown — a process the OS kills to reclaim memory (the common fate of a
     * backgrounded app on a low-RAM device) never fires it, so decrypted video plaintext
     * accumulated in the cache directory indefinitely. Deleting on activity teardown is both
     * reliable and appropriate for an app whose whole premise is that plaintext does not sit
     * at rest.
     */
    private File        playbackFile;
    private int          videoRotationDegrees = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        playerView  = findViewById(R.id.player_view);
        progressBar = findViewById(R.id.progress);
        topBar      = findViewById(R.id.top_bar);
        captionView = findViewById(R.id.tv_caption);
        mediaRef    = getIntent().getStringExtra(EXTRA_URL);
        mediaKey    = getIntent().getStringExtra(EXTRA_MEDIA_KEY);
        chunked     = getIntent().getBooleanExtra(EXTRA_CHUNKED, false);

        bindHeader();

        ImageButton btnClose    = findViewById(R.id.btn_close);
        ImageButton btnDownload = findViewById(R.id.btn_download);
        ImageButton btnShare    = findViewById(R.id.btn_share);
        ImageButton btnForward  = findViewById(R.id.btn_forward);
        ImageButton btnRotate   = findViewById(R.id.btn_rotate);

        if (btnClose    != null) btnClose.setOnClickListener(v -> finish());
        if (btnDownload != null) btnDownload.setOnClickListener(v -> saveVideoToGallery());
        if (btnShare    != null) btnShare.setOnClickListener(v -> shareVideo());
        if (btnForward  != null) btnForward.setOnClickListener(v -> forwardVideo());
        if (btnRotate   != null) btnRotate.setOnClickListener(v -> rotateVideo());

        // Keep the custom top bar / caption in lock-step with ExoPlayer's own controller
        // so a single tap reveals or hides all chrome together (WhatsApp/Telegram feel).
        if (playerView != null) {
            playerView.setControllerVisibilityListener(
                    (PlayerView.ControllerVisibilityListener) visibility -> {
                        boolean show = visibility == View.VISIBLE;
                        syncChrome(show);
                    });
        }

        if (mediaRef != null) loadAndPlay();
    }

    private void rotateVideo() {
        if (playerView == null) return;
        videoRotationDegrees = (videoRotationDegrees + 90) % 360;
        playerView.animate().rotation(videoRotationDegrees).setDuration(180).start();
    }

    private void forwardVideo() {
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
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_MEDIA_URL, mediaRef);
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_MEDIA_KEY, mediaKey);
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_MEDIA_TYPE, "video");
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_TEXT,
                getIntent().getStringExtra(EXTRA_CAPTION));
        picker.putExtra(ConversationListActivity.EXTRA_FORWARD_TIMESTAMP,
                getIntent().getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis()));
        startActivity(picker);
    }

    /** Fills the top bar with the sender name, a friendly timestamp, and any caption. */
    private void bindHeader() {
        String sender  = getIntent().getStringExtra(EXTRA_SENDER_NAME);
        long   ts      = getIntent().getLongExtra(EXTRA_TIMESTAMP, 0L);
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
        if (captionView != null && caption != null && !caption.trim().isEmpty()) {
            captionView.setText(caption.trim());
            captionView.setVisibility(View.VISIBLE);
        }
    }

    /** Fades the custom chrome in/out to match the player controller's visibility. */
    private void syncChrome(boolean show) {
        View[] bars = { topBar, captionView };
        for (View bar : bars) {
            if (bar == null) continue;
            // Never reveal an empty caption strip.
            if (bar == captionView && captionView.getVisibility() == View.GONE
                    && captionView.getText().length() == 0) {
                continue;
            }
            bar.animate().cancel();
            if (show) {
                bar.setVisibility(View.VISIBLE);
                bar.animate().alpha(1f).setDuration(150).start();
            } else {
                bar.animate().alpha(0f).setDuration(150)
                        .withEndAction(() -> bar.setVisibility(View.GONE)).start();
            }
        }
    }

    /**
     * Shares the decrypted video. Reuses {@link #playbackFile} when it exists (the
     * disk-to-disk fast path), otherwise downloads + decrypts to a scratch file first,
     * mirroring {@link #saveVideoToGallery()}.
     */
    private void shareVideo() {
        if (mediaRef == null) return;

        File ready = playbackFile;
        if (ready != null && ready.exists() && ready.length() > 0) {
            com.duoshield.app.util.SecureShareHelper.shareVideo(this, ready);
            return;
        }
        if (!B2StorageHelper.isB2Path(mediaRef)) {
            Toast.makeText(this, "Cannot share this video", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Preparing…", Toast.LENGTH_SHORT).show();
        File scratch;
        try {
            scratch = File.createTempFile("vid_share_", ".mp4", getCacheDir());
        } catch (Exception e) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show();
            return;
        }
        B2StorageHelper.loadMediaToFile(this, mediaRef, mediaKey, scratch, chunked,
                new B2StorageHelper.FileCallback() {
                    @Override public void onReady(File plainFile) {
                        if (isDestroyed() || isFinishing()) return;
                        com.duoshield.app.util.SecureShareHelper.shareVideo(
                                MediaViewerActivity.this, plainFile);
                    }
                    @Override public void onError(Exception e) {
                        //noinspection ResultOfMethodCallIgnored
                        scratch.delete();
                        if (isDestroyed() || isFinishing()) return;
                        Toast.makeText(MediaViewerActivity.this,
                                "Share failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── Load + play ───────────────────────────────────────────────────────────

    /**
     * Resolves the media to a decrypted file on disk and hands that file to ExoPlayer.
     *
     * <p>Uses {@link B2StorageHelper#loadMediaToFile} rather than
     * {@link B2StorageHelper#loadMedia}: playback needs a file, and the old path allocated the
     * encrypted blob plus the full decrypted plaintext in memory only to then write that
     * plaintext to a temp file anyway. Peak memory was 2–3× the video size to reach a state
     * that is simply "a file in the cache dir", which is a guaranteed OOM for a large video on
     * a low-RAM device. The streaming path holds a 256 KB buffer instead, so playback memory is
     * now flat regardless of file size.
     */
    private void loadAndPlay() {
        if (B2StorageHelper.isB2Path(mediaRef) && chunked) {
            // Chunked v2 object: hand ExoPlayer a DataSource that decrypts 1 MiB at a time and
            // let it pull. Nothing is written to disk, so there is no playbackFile to clean up
            // and the first frame renders after one header read plus one chunk rather than
            // after the whole object has landed and been decrypted end to end.
            showProgress(true);
            initChunkedPlayer();
            return;
        }
        if (B2StorageHelper.isB2Path(mediaRef)) {
            showProgress(true);
            File dest;
            try {
                dest = File.createTempFile("vid_view_", ".mp4", getCacheDir());
            } catch (Exception e) {
                showProgress(false);
                Toast.makeText(this, "Failed to prepare video", Toast.LENGTH_SHORT).show();
                return;
            }
            playbackFile = dest;
            B2StorageHelper.loadMediaToFile(this, mediaRef, mediaKey, dest,
                    new B2StorageHelper.FileCallback() {
                        @Override public void onReady(File plainFile) {
                            if (isDestroyed() || isFinishing()) return;
                            showProgress(false);
                            initPlayer(Uri.fromFile(plainFile));
                        }
                        @Override public void onError(Exception e) {
                            if (isDestroyed() || isFinishing()) return;
                            showProgress(false);
                            Toast.makeText(MediaViewerActivity.this,
                                    "Failed to load video", Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            // Legacy public URL — play directly
            showProgress(false);
            initPlayer(Uri.parse(mediaRef));
        }
    }

    /**
     * Builds an {@link ExoPlayer} configured for this device's {@link DevicePerformanceTier}.
     *
     * <p>Both call sites previously used a bare {@code new ExoPlayer.Builder(this).build()},
     * which asks a budget SoC to do whatever the file demands. Three things are set here:
     *
     * <ul>
     *   <li><b>Track constraints on {@link DevicePerformanceTier#LOW}</b> — capped at 854x480 /
     *       30 fps. A PowerVR GE8320 (Helio P35) cannot sustain 1080p decode; handed a 1080p
     *       clip it drops frames and heats up rather than failing outright, which is worse
     *       than simply refusing the resolution. On a 720p-class screen the cap is not
     *       visible. MID and HIGH stay unconstrained.</li>
     *   <li><b>Decoder fallback, all tiers</b> — MT6765 OMX decoders reject some otherwise
     *       valid H.264 profiles. Without fallback that surfaces to the user as a hard
     *       "Failed to play video"; with it, playback drops to the software decoder.</li>
     *   <li><b>Tighter buffers on LOW</b> — 15 s/30 s instead of the 50 s default, so a long
     *       video does not hold tens of MB of sample data in a heap that is already tight.</li>
     * </ul>
     */
    @UnstableApi
    private ExoPlayer buildPlayer() {
        boolean low = DevicePerformanceTier.get(this) == DevicePerformanceTier.LOW;

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);
        if (low) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setMaxVideoSize(854, 480)
                    .setMaxVideoFrameRate(30));
        }

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);

        ExoPlayer.Builder builder = new ExoPlayer.Builder(this, renderersFactory)
                .setTrackSelector(trackSelector);

        if (low) {
            builder.setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            15_000,  // minBufferMs
                            30_000,  // maxBufferMs
                            1_000,   // bufferForPlaybackMs
                            2_000)   // bufferForPlaybackAfterRebufferMs
                    .build());
        }
        return builder.build();
    }

    @UnstableApi
    private void initPlayer(Uri uri) {
        if (isDestroyed() || isFinishing()) return;
        player = buildPlayer();
        if (playerView != null) playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.play();
    }

    /**
     * Builds a player that streams straight out of the encrypted object.
     *
     * <p>{@link ProgressiveMediaSource} drives a {@link ChunkedMediaDataSource} through the
     * standard extractor pipeline, so seeking needs no special handling here: the extractor
     * reopens the source at the requested plaintext position and the data source turns that
     * position into a chunk index arithmetically. The {@code duoshield-chunked://} URI is inert
     * — the factory reads the B2 path and media key from its own constructor arguments, and the
     * URI exists only because {@link MediaItem} requires one.
     *
     * <p>Marked {@code @UnstableApi} rather than opting in per call: {@code MediaSource},
     * {@code ProgressiveMediaSource} and {@code setMediaSource} are all unstable in media3
     * 1.3.1, and containing that to the one method that touches them keeps the rest of the
     * activity on the stable surface.
     */
    @UnstableApi
    private void initChunkedPlayer() {
        if (isDestroyed() || isFinishing()) return;
        player = buildPlayer();
        if (playerView != null) playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                // The spinner has to be dismissed by playback state rather than by a download
                // completing, because in streaming mode there is no download that completes.
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    showProgress(false);
                } else if (state == Player.STATE_BUFFERING) {
                    showProgress(true);
                }
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // A failed chunk tag surfaces here. The object is either tampered with or
                // truncated; either way there is no partial playback to fall back to, because
                // no chunk is ever exposed before its tag verifies.
                showProgress(false);
                if (isDestroyed() || isFinishing()) return;
                Toast.makeText(MediaViewerActivity.this,
                        "Failed to play video", Toast.LENGTH_SHORT).show();
            }
        });
        // Opaque (scheme:ssp) rather than hierarchical, so the B2 path's own "b2:" prefix cannot
        // be misread as an authority by anything that inspects the URI along the way.
        MediaItem item = MediaItem.fromUri(
                Uri.fromParts(ChunkedMediaDataSource.SCHEME, mediaRef, null));
        player.setMediaSource(new ProgressiveMediaSource.Factory(
                new ChunkedMediaDataSource.Factory(mediaRef, mediaKey)).createMediaSource(item));
        player.prepare();
        player.play();
    }

    // ── Download to gallery ───────────────────────────────────────────────────

    /**
     * Saves the video to the gallery by streaming a plaintext file into the MediaStore.
     *
     * <p>When the video is already playing, {@link #playbackFile} holds the decrypted bytes,
     * so the save reuses it instead of re-downloading and re-decrypting the whole thing —
     * pressing download on a video you are watching is now disk-to-disk only. Otherwise the
     * file is streamed down to a scratch file first. Either way the plaintext is copied to the
     * MediaStore in 256 KB chunks and never held in memory as one array.
     */
    private void saveVideoToGallery() {
        if (mediaRef == null) return;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();

        if (!B2StorageHelper.isB2Path(mediaRef)) {
            Toast.makeText(this, "Cannot save this video format", Toast.LENGTH_SHORT).show();
            return;
        }

        File ready = playbackFile;
        if (ready != null && ready.exists() && ready.length() > 0) {
            Executors.newSingleThreadExecutor().execute(() -> writeVideoToGallery(ready, false));
            return;
        }

        File scratch;
        try {
            scratch = File.createTempFile("vid_save_", ".mp4", getCacheDir());
        } catch (Exception e) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
            return;
        }
        // Saving is the one operation that genuinely needs every byte, so it does a full
        // download + decrypt even in streaming mode — there is no playback file to reuse
        // because streaming never wrote one. The chunked flag still has to be threaded through
        // so the decrypt loop matches the format the object is actually in.
        B2StorageHelper.loadMediaToFile(this, mediaRef, mediaKey, scratch, chunked,
                new B2StorageHelper.FileCallback() {
                    @Override public void onReady(File plainFile) {
                        Executors.newSingleThreadExecutor()
                                .execute(() -> writeVideoToGallery(plainFile, true));
                    }
                    @Override public void onError(Exception e) {
                        //noinspection ResultOfMethodCallIgnored
                        scratch.delete();
                        if (isDestroyed() || isFinishing()) return;
                        Toast.makeText(MediaViewerActivity.this,
                                "Download failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * Copies {@code plainFile} into the gallery. Runs on a background thread.
     *
     * @param deleteAfter whether {@code plainFile} is a throwaway scratch file this method
     *                    owns. The playback file is still in use by ExoPlayer, so it is left
     *                    for {@link #onDestroy()} to clean up.
     */
    private void writeVideoToGallery(File plainFile, boolean deleteAfter) {
        try {
            ContentValues values = new ContentValues();
            values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    "duoshield_" + System.currentTimeMillis() + ".mp4");
            values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/DuoShield");
            }
            Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (InputStream in = new FileInputStream(plainFile);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("MediaStore stream unavailable");
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                // F37 fix: record the URI so wipe/duress logout can delete it from the gallery.
                com.duoshield.app.util.MediaStoreWipeHelper.recordUri(
                        MediaViewerActivity.this, uri);
            }
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing())
                    Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing())
                    Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
            });
        } finally {
            if (deleteAfter) {
                //noinspection ResultOfMethodCallIgnored
                plainFile.delete();
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private void showProgress(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override protected void onPause()   { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onResume()  { super.onResume(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
        if (playbackFile != null) {
            //noinspection ResultOfMethodCallIgnored
            playbackFile.delete();
            playbackFile = null;
        }
    }
}
