package com.duoshield.app.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;

import org.webrtc.VideoTrack;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide owner of the one live call, independent of any Activity.
 *
 * <h3>Why this exists</h3>
 * {@link CallActivity} used to own the whole call: it created the {@link CallManager}, listened
 * to it, saved the history row, handled the notification's End/Mute buttons, held audio focus
 * and hung up in {@code onDestroy()}. So anything that destroyed the screen — closing the
 * Picture-in-Picture window with the X, the system reclaiming it in the background, a
 * night-mode change recreating it — silently ended the call.
 *
 * <p>Now the session owns everything that is not UI. A call screen <em>attaches</em> to it
 * ({@link #attach}) and receives the same {@link CallManager.CallListener} callbacks as before;
 * when the screen goes away while the call is connected it <em>detaches</em> and the call carries
 * on behind its foreground-service notification. A new screen (notification, the
 * "return to call" bar, PiP) simply attaches again and re-renders the live tracks.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #begin} — by the call screen when a call starts. Registers the session-level
 *       receivers and installs itself as the {@code CallManager} listener.</li>
 *   <li>{@link #attach}/{@link #detach} — any number of times.</li>
 *   <li>{@code ENDED}/{@code FAILED} — the history row is written once; if no screen is attached
 *       the session tears itself down, otherwise the screen finishes and tears it down from
 *       {@code onDestroy()} (renderers must be released before the tracks are disposed).</li>
 *   <li>{@link #teardown} — idempotent: stops the notification, releases audio, hangs up /
 *       releases the {@code CallManager}, clears {@link #current()}.</li>
 * </ol>
 *
 * <p>Threading: {@code CallManager} callbacks may arrive on any thread. UI hooks are invoked on
 * the calling thread (the screen already marshals with {@code runOnUiThread}); receiver
 * callbacks and teardown run on the main thread.
 */
public final class CallSession {

    private static final String TAG = "CallSession";

    private static volatile CallSession current;

    /** The live (or just-ended, not yet torn down) session, or {@code null}. */
    public static CallSession current() { return current; }

    /** A call exists that has not ended — drives the "return to call" bar. */
    public static boolean isLive() {
        CallSession s = current;
        return s != null && s.isActive();
    }

    /**
     * Brings the call screen back to the front (notification, bar, lock-screen redirect). The
     * screen adopts the live session; if its previous instance still exists it is reordered to
     * the front instead.
     */
    public static void returnToCall(Context ctx) {
        Intent i = new Intent(ctx, CallActivity.class)
                .putExtra(CallActivity.EXTRA_RESUME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ctx.startActivity(i);
    }

    /** A screen that can be attached to the session. */
    public interface UiHooks extends CallManager.CallListener {
        /** Mute changed — from the screen's own button, the notification or the PiP window. */
        void onMuteChanged(boolean muted);

        /** The audio route changed without the screen asking (wired headset plugged/unplugged). */
        void onAudioRouteChanged();
    }

    // ── Identity ──────────────────────────────────────────────────────────────
    public final CallManager manager;
    public final boolean isCaller;
    public final String  partnerId;
    public final String  partnerName;
    public final String  myUid;
    private volatile String  callId;
    private volatile boolean video;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();

    private volatile UiHooks ui;
    private volatile boolean muted;
    private volatile boolean cameraOff;
    /** Speaker was the user's choice before a wired headset took over (restored on unplug). */
    private volatile boolean speakerPreferred;
    private volatile boolean wasConnected;
    /** Shared call-start instant in this device's clock; 0 until the anchor arrives. */
    private volatile long callStartMs;

    private String  savedRecordId;
    private boolean tornDown;
    private boolean receiversRegistered;
    private AudioFocusRequest focusRequest;

    private CallSession(Context appCtx, CallManager manager, boolean isCaller, boolean video,
                        String partnerId, String partnerName, String myUid, String callId) {
        this.appCtx      = appCtx;
        this.manager     = manager;
        this.isCaller    = isCaller;
        this.video       = video;
        this.partnerId   = partnerId;
        this.partnerName = partnerName;
        this.myUid       = myUid;
        this.callId      = callId;
    }

    /**
     * Starts owning {@code manager}. Any stale session (ended but not yet torn down) is cleaned
     * up first; the call screen never begins a second call while one is live.
     */
    public static CallSession begin(Context ctx, CallManager manager, boolean isCaller,
                                    boolean video, String partnerId, String partnerName,
                                    String myUid, String callId) {
        CallSession old = current;
        if (old != null) old.teardown();
        CallSession s = new CallSession(ctx.getApplicationContext(), manager, isCaller, video,
                partnerId, partnerName, myUid, callId);
        current = s;
        manager.setListener(s.hub);
        s.registerReceivers();
        return s;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  callId()        { return callId; }
    public boolean isVideo()       { return video; }
    public boolean isMuted()       { return muted; }
    public boolean isCameraOff()   { return cameraOff; }
    public boolean wasConnected()  { return wasConnected; }
    public long    callStartMs()   { return callStartMs; }

    public void setCallId(String id)        { if (id != null) callId = id; }
    public void setVideo(boolean v)         { video = v; }
    public void setCameraOff(boolean off)   { cameraOff = off; }
    public void setSpeakerPreferred(boolean on) { speakerPreferred = on; }

    public CallManager.CallState state() {
        return manager.getCurrentState();
    }

    /** Not ended/failed and not torn down. */
    public boolean isActive() {
        if (tornDown) return false;
        CallManager.CallState st = manager.getCurrentState();
        return st != CallManager.CallState.ENDED && st != CallManager.CallState.FAILED;
    }

    /** Connected at some point and still running — the only state that is safe to detach. */
    public boolean isConnectedLive() {
        return wasConnected && isActive();
    }

    // ── Screen attachment ─────────────────────────────────────────────────────

    public void attach(UiHooks hooks) { ui = hooks; }

    /** No-op if another screen has attached since. */
    public void detach(UiHooks hooks) { if (ui == hooks) ui = null; }

    // ── Controls (shared by the screen, the notification and the PiP window) ──

    /** The one "user ended the call" path. {@code hangup()} is a no-op once ENDED. */
    public void endCall() {
        manager.hangup();
    }

    public void setMuted(boolean m) {
        muted = m;
        manager.setMuted(m);
        UiHooks u = ui;
        if (u != null) u.onMuteChanged(m);
    }

    public void toggleMute() {
        setMuted(!muted);
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    /** Opening route + audio focus. Called once, when the call starts. */
    public void beginAudio() {
        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        // Opening route: a connected headset (Bluetooth or wired) wins, otherwise speaker for
        // video and earpiece for voice — selected by the controller with setCommunicationDevice()
        // on API 31+, where the legacy setSpeakerphoneOn()/startBluetoothSco() calls are ignored.
        AudioRouteController routes = manager.audioRoute();
        if (routes != null) {
            routes.beginSession(video);
            speakerPreferred = routes.currentKind() == AudioRouteController.Kind.SPEAKER;
        } else {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }

        // Exclusive audio focus so music apps pause and the OS knows a voice call is in
        // progress (Bluetooth routing, notification ducking). minSdk is 26, so the
        // AudioFocusRequest API is always available.
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    // LOSS(_TRANSIENT): another app took focus (e.g. a phone call). Mute
                    // ourselves so the partner doesn't hear feedback; don't hang up — the
                    // user may come back. GAIN restores the user's own mute choice.
                    if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS) {
                        manager.setMuted(true);
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        manager.setMuted(muted);
                    }
                })
                .build();
        am.requestAudioFocus(focusRequest);
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (focusRequest != null) {
            am.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    private boolean applyRouteKind(AudioRouteController.Kind kind) {
        AudioRouteController routes = manager.audioRoute();
        if (routes == null) return false;
        // MUTED is synthetic — it has no framework device, so it is never in availableRoutes().
        if (kind == AudioRouteController.Kind.MUTED) {
            return routes.apply(AudioRouteController.mutedRoute());
        }
        for (AudioRouteController.Route r : routes.availableRoutes()) {
            if (r.kind == kind) return routes.apply(r);
        }
        return false;
    }

    // ── Session-level receivers ───────────────────────────────────────────────

    /** Wired headset plug/unplug → re-route, whether or not a call screen is showing. */
    private final BroadcastReceiver headsetReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!AudioManager.ACTION_HEADSET_PLUG.equals(intent.getAction())) return;
            int state = intent.getIntExtra("state", -1);
            if (manager.audioRoute() == null) return;
            if (state == 1) {
                applyRouteKind(AudioRouteController.Kind.WIRED);   // prefer it over the speaker
                speakerPreferred = false;
            } else if (state == 0) {
                // Unplugged: fall back to what it was before the headset came in.
                applyRouteKind(speakerPreferred
                        ? AudioRouteController.Kind.SPEAKER
                        : AudioRouteController.Kind.EARPIECE);
            }
            UiHooks u = ui;
            if (u != null) u.onAudioRouteChanged();
        }
    };

    /** End / Mute from the ongoing-call notification or the PiP window. */
    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (CallForegroundService.BROADCAST_END_CALL.equals(action)) {
                endCall();
            } else if (CallForegroundService.BROADCAST_TOGGLE_MUTE.equals(action)) {
                toggleMute();
            }
        }
    };

    private void registerReceivers() {
        if (receiversRegistered) return;
        receiversRegistered = true;
        appCtx.registerReceiver(headsetReceiver, new IntentFilter(AudioManager.ACTION_HEADSET_PLUG));
        IntentFilter f = new IntentFilter();
        f.addAction(CallForegroundService.BROADCAST_END_CALL);
        f.addAction(CallForegroundService.BROADCAST_TOGGLE_MUTE);
        ContextCompat.registerReceiver(appCtx, actionReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterReceivers() {
        if (!receiversRegistered) return;
        receiversRegistered = false;
        try { appCtx.unregisterReceiver(headsetReceiver); } catch (Exception ignored) { }
        try { appCtx.unregisterReceiver(actionReceiver);  } catch (Exception ignored) { }
    }

    // ── CallManager → screen (and session bookkeeping) ────────────────────────

    private final CallManager.CallListener hub = new CallManager.CallListener() {
        @Override public void onCallStateChanged(CallManager.CallState state) {
            if (state == CallManager.CallState.CONNECTED) wasConnected = true;

            // The screen sees the state first so it can finish before the session cleans up.
            UiHooks u = ui;
            if (u != null) u.onCallStateChanged(state);

            if (state == CallManager.CallState.ENDED) {
                saveCallRecord(wasConnected ? CallRecord.OUTCOME_ANSWERED : CallRecord.OUTCOME_MISSED);
                tearDownIfHeadless();
            } else if (state == CallManager.CallState.FAILED) {
                saveCallRecord(CallRecord.OUTCOME_FAILED);
                tearDownIfHeadless();
            }
        }

        @Override public void onRemoteVideoTrack(VideoTrack track) {
            UiHooks u = ui;
            if (u != null) u.onRemoteVideoTrack(track);   // otherwise re-read via the manager on attach
        }

        @Override public void onLocalVideoTrack(VideoTrack track) {
            UiHooks u = ui;
            if (u != null) u.onLocalVideoTrack(track);
        }

        @Override public void onError(String message) {
            Log.e(TAG, "Call error: " + message);
            UiHooks u = ui;
            if (u != null) u.onError(message);
        }

        @Override public void onCallTimerAnchor(long callStartLocalMs) {
            callStartMs = callStartLocalMs;
            UiHooks u = ui;
            if (u != null) u.onCallTimerAnchor(callStartLocalMs);
        }

        @Override public void onLocalMirrorChanged(boolean mirror) {
            UiHooks u = ui;
            if (u != null) u.onLocalMirrorChanged(mirror);
        }
    };

    /**
     * With no screen attached nothing else will clean up. Posted so it runs after the
     * {@code CallManager} call that raised ENDED has returned (it is still mid-hangup here).
     */
    private void tearDownIfHeadless() {
        main.post(() -> { if (ui == null) teardown(); });
    }

    // ── Call history ──────────────────────────────────────────────────────────

    /**
     * Writes this call's history row, once. ENDED can arrive from both the local hangup and the
     * remote status write.
     */
    private void saveCallRecord(String outcome) {
        if (partnerId == null || myUid == null) return;
        long now = System.currentTimeMillis();
        CallRecord record = new CallRecord();
        synchronized (this) {
            if (savedRecordId != null) return;
            long start = callStartMs;
            record.id              = UUID.randomUUID().toString();
            record.partnerId       = partnerId;
            record.partnerName     = partnerName != null ? partnerName : partnerId;
            record.isVideo         = video;
            record.direction       = isCaller ? CallRecord.DIRECTION_OUTGOING : CallRecord.DIRECTION_INCOMING;
            record.outcome         = outcome;
            record.startedAt       = start > 0 ? start : now;
            record.durationSeconds = start > 0 ? (int) ((now - start) / 1000) : 0;
            savedRecordId          = record.id;
        }
        try {
            historyExecutor.execute(() ->
                    AppDatabase.getInstance(appCtx).callHistoryDao().insert(record));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not queue call history row", e);
        }
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    /**
     * Frees everything the call holds. Idempotent. When a screen is attached it must release its
     * video renderers BEFORE calling this, so tracks are not disposed under a live surface.
     */
    public void teardown() {
        synchronized (this) {
            if (tornDown) return;
            tornDown = true;
        }
        if (current == this) current = null;
        ui = null;
        unregisterReceivers();

        // Removes the ongoing notification. A plain stopService() can race a not-yet-started
        // foreground service, so ask it to stop itself first and fall back when the app is in
        // the background and may not start services.
        try {
            appCtx.startService(new Intent(appCtx, CallForegroundService.class)
                    .setAction(CallForegroundService.ACTION_STOP));
        } catch (RuntimeException e) {
            try { appCtx.stopService(new Intent(appCtx, CallForegroundService.class)); }
            catch (RuntimeException ignored) { }
        }

        // Release the route selection and restore the call volume before the tracks go away,
        // while the controller can still unmute the remote audio track it disabled.
        AudioRouteController routes = manager.audioRoute();
        if (routes != null) routes.endSession();

        CallManager.CallState st = manager.getCurrentState();
        if (st != CallManager.CallState.ENDED && st != CallManager.CallState.FAILED) {
            manager.hangup();     // write "ended" to Firestore, then free native resources
        } else {
            manager.release();    // remote ended it: still free the PeerConnection, camera, EGL
        }

        abandonAudioFocus();
        historyExecutor.shutdown();   // not shutdownNow(): a queued history insert must finish
    }
}
