package com.duoshield.app.util;

import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;

public class VoiceMessagePlayer {

    private static final String TAG = "VoiceMessagePlayer";

    /** Speed cycle mirrors Telegram's tap-to-cycle voice-note control. */
    public static final float[] SPEED_STEPS = {1f, 1.5f, 2f};

    public interface PlayerListener {
        void onStart(int durationMs);
        void onProgress(int posMs);
        void onComplete();
        void onError(String msg);
    }

    private MediaPlayer                    player;
    private Handler                        handler;
    private Runnable                       progressRunnable;
    private WeakReference<PlayerListener>  listenerRef;
    // Persists across notes (like Telegram) so switching tracks keeps the chosen speed.
    private float                          currentSpeed = 1f;

    public void play(String url, PlayerListener cb) {
        listenerRef = new WeakReference<>(cb);
        releaseInternal();
        try {
            player = new MediaPlayer();
            player.setDataSource(url);
            // Listeners MUST be registered before prepareAsync() is called. Previously
            // prepareAsync() ran first — for a small, already-decrypted local temp file
            // (our B2 voice-note flow) prep can complete fast enough to race the very
            // next line, so onPrepared silently never fires: mp.start() never runs,
            // and startProgressPolling() (called from inside that same callback) never
            // starts either. Audio never plays, but ChatMediaActivity already flipped
            // the UI to the pause icon beforehand, so it looked like a frozen progress
            // dot/duration counter on a track that was actually never started.
            player.setOnPreparedListener(mp -> {
                mp.start();
                // Re-apply the persisted speed to the freshly started track.
                if (currentSpeed != 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        PlaybackParams params = mp.getPlaybackParams();
                        params.setSpeed(currentSpeed);
                        mp.setPlaybackParams(params);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to re-apply speed on new track: " + com.duoshield.app.util.LogRedact.error(e));
                    }
                }
                PlayerListener l = listenerRef != null ? listenerRef.get() : null;
                if (l != null) l.onStart(mp.getDuration());
                startProgressPolling();
            });
            player.setOnCompletionListener(mp -> {
                PlayerListener l = listenerRef != null ? listenerRef.get() : null;
                if (l != null) l.onComplete();
                releaseInternal();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
                PlayerListener l = listenerRef != null ? listenerRef.get() : null;
                if (l != null) l.onError("Playback error (" + what + ")");
                releaseInternal();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "play() failed for url=" + url + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            PlayerListener l = listenerRef != null ? listenerRef.get() : null;
            if (l != null) l.onError(e.getMessage() != null ? e.getMessage() : "Playback setup failed");
        }
    }

    /**
     * Returns the current total duration in ms, or 0 if the player is not ready.
     * Useful when MediaPlayer reported 0/-1 in onPrepared (a known OEM quirk) but
     * later becomes available — callers can poll this on progress ticks.
     */
    public int getDuration() {
        if (player == null) return 0;
        try { return Math.max(0, player.getDuration()); }
        catch (Exception e) { return 0; }
    }

    /** Seeks to the given position in milliseconds. No-op if no player is active. */
    public void seekTo(int posMs) {
        if (player == null) return;
        try { player.seekTo(Math.max(0, posMs)); }
        catch (Exception e) { Log.w(TAG, "seekTo(" + posMs + ") failed: " + com.duoshield.app.util.LogRedact.error(e)); }
    }

    public void pause() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (Exception e) {
                Log.w(TAG, "pause() failed: " + com.duoshield.app.util.LogRedact.error(e));
            }
        }
        stopProgressPolling();
    }

    public void resume() {
        if (player != null) {
            try {
                if (!player.isPlaying()) {
                    player.start();
                    startProgressPolling();
                }
            } catch (Exception e) {
                Log.w(TAG, "resume() failed: " + com.duoshield.app.util.LogRedact.error(e));
            }
        }
    }

    public boolean isPaused() {
        return player != null && !player.isPlaying();
    }

    public float getSpeed() {
        return currentSpeed;
    }

    /**
     * Cycles to the next speed in {@link #SPEED_STEPS} and applies it, returning the
     * new value. Persists even if no player is currently active, so the next play()
     * picks it up.
     */
    public float cycleSpeed() {
        int idx = 0;
        for (int i = 0; i < SPEED_STEPS.length; i++) {
            if (SPEED_STEPS[i] == currentSpeed) { idx = i; break; }
        }
        float next = SPEED_STEPS[(idx + 1) % SPEED_STEPS.length];
        setSpeed(next);
        return next;
    }

    /**
     * Sets playback speed via {@link PlaybackParams} (API 23+; no-op with the speed
     * still recorded on older devices, since PlaybackParams isn't available there).
     *
     * <p>Some OEM MediaPlayer implementations only reliably honour a new
     * PlaybackParams while the player is actively playing — applying it to a paused
     * player can silently fail to take effect (and on a few devices calling
     * setPlaybackParams() on a paused player has been observed to kick playback
     * back into a "playing" state as a side effect). To keep behaviour consistent
     * across devices, if the player is currently paused we briefly start it, apply
     * the params, then pause it again — restoring the exact paused state the caller
     * had before this call. We bypass {@link #resume()}/{@link #pause()} here (using
     * the MediaPlayer directly) so this bounce does not touch progress polling.
     */
    public void setSpeed(float speed) {
        currentSpeed = speed;
        if (player == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(speed);
            if (player.isPlaying()) {
                player.setPlaybackParams(params);
            } else {
                player.start();
                player.setPlaybackParams(params);
                player.pause();
            }
        } catch (Exception e) {
            Log.w(TAG, "setSpeed(" + speed + ") failed: " + com.duoshield.app.util.LogRedact.error(e));
        }
    }

    public void release() {
        releaseInternal();
        listenerRef = null;
    }

    private void startProgressPolling() {
        stopProgressPolling();
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        progressRunnable = new Runnable() {
            @Override public void run() {
                if (player == null) return;
                try {
                    if (player.isPlaying()) {
                        PlayerListener pl = listenerRef != null ? listenerRef.get() : null;
                        if (pl == null) {
                            stopProgressPolling();
                            return;
                        }
                        pl.onProgress(player.getCurrentPosition());
                    }
                } catch (Exception ignored) {
                    // Fall through and reschedule anyway — a single transient read
                    // failure (OEM state hiccup, brief stall) should not permanently
                    // kill the polling loop below.
                }
                // Always reschedule regardless of this tick's isPlaying()/exception
                // outcome. Previously this postDelayed() call lived *inside* the
                // isPlaying() branch, so a single tick where isPlaying() read false
                // (or getCurrentPosition() threw) stopped the loop forever — audio
                // kept playing to completion, but onProgress() never fired again,
                // freezing the waveform thumb/duration for the rest of the note.
                // Explicit pause()/releaseInternal() calls stopProgressPolling() to
                // actually cancel this, so it's safe to keep rescheduling here.
                handler.postDelayed(this, 50);
            }
        };
        handler.post(progressRunnable);
    }

    private void stopProgressPolling() {
        if (handler != null && progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void releaseInternal() {
        stopProgressPolling();
        if (player != null) {
            try {
                if (player.isPlaying()) player.pause();
                player.reset();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }
}
