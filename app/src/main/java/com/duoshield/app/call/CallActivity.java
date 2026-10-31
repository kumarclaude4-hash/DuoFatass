package com.duoshield.app.call;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.util.Rational;
import android.util.TypedValue;
import android.graphics.Color;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

    import com.duoshield.app.call.TurnBandwidthTracker;
    import com.duoshield.app.call.TurnCredentialFetcher;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import org.webrtc.RendererCommon;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full-screen call UI — voice or video.
 *
 * <p>Started by either:
 * <ul>
 *   <li>The caller (via {@link ChatMediaActivity}) with {@code is_caller=true}</li>
 *   <li>The callee (via {@link IncomingCallActivity}) with {@code is_caller=false}</li>
 * </ul>
 *
 * <h3>Layout overview</h3>
 * <ul>
 *   <li>Full-screen remote video / voice-only background fills the screen.</li>
 *   <li>A gradient header bar (top) shows the partner name, call duration, and audio-output
 *       picker trigger.</li>
 *   <li>The local video PiP (lower-right) carries the "You" label and flip-camera button.</li>
 *   <li>A gradient controls bar (bottom) holds Camera, Mute, Chat, and End-call buttons.</li>
 * </ul>
 */
public class CallActivity extends AppCompatActivity implements CallManager.CallListener {

    private static final String TAG = "CallActivity";
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();

    public static final String EXTRA_CALL_ID      = "call_id";
    public static final String EXTRA_CALLEE_ID    = "callee_id";
    public static final String EXTRA_CALLER_ID    = "caller_id";
    public static final String EXTRA_IS_VIDEO     = "is_video";
    public static final String EXTRA_IS_CALLER    = "is_caller";
    public static final String EXTRA_PARTNER_NAME = "partner_name";
    public static final String EXTRA_MY_UID       = "my_uid";
    /** F6: deterministic chatId for bilateral contact gate in Firestore rules. */
    public static final String EXTRA_CHAT_ID      = "chat_id";

    private CallManager callManager;

    /** Prevents duplicate history rows when both peers report the same call termination. */
    private String savedRecordId = null;

    // ── Views ─────────────────────────────────────────────────────────────────
    private SurfaceViewRenderer remoteVideoView;
    private SurfaceViewRenderer localVideoView;
    private View                localVideoPip;
    private View                voiceOnlyBg;
    private TextView            tvCallPartnerName;
    private TextView            tvCallStatus;
    private TextView            tvCallDuration;
    private TextView            tvCallStatusOverlay;
    private TextView            tvCallAvatarInitial;
    private ImageView           btnMute;
    private ImageView           btnCamera;
    private View                btnCameraLayout;
    private ImageView           btnEndCall;
    private ImageView           btnSpeaker;
    private ImageView           btnFlipCamera;     // inside PiP
    private View                btnFlipLayout;
    private View                btnBack;
    private View                callHeader;
    private View                avatarFrame;
    private TextView            tvPipPartnerName;
    private View                callControlsBar;
    private ImageView           btnChat;

    // TURN quota warning banner
    private View     bannerTurnWarning;
    private TextView tvTurnWarningText;

    // New-message banner (Google Meet-style pill)
    private View     bannerNewMessage;
    private TextView tvNewMsgPreview;
    private View     btnChatLayout;
    private final Handler         bannerDismissHandler = new Handler(Looper.getMainLooper());
    private ListenerRegistration  chatMessageListener;
    private final Set<String>     seenChatMsgIds = new HashSet<>();

    /**
     * Re-attach support for the in-call chat listener.
     *
     * <p>The caller attaches its listener immediately after {@code startCall()}, but the
     * {@code calls/{callId}} document is only written a moment later (inside the
     * createOffer → setLocalDescription callback, plus a network round-trip). The security
     * rule for {@code calls/{callId}/chat} resolves the parent call document to check
     * {@code callerId}/{@code calleeId}; while that parent does not exist yet the rule
     * evaluation fails and the query is rejected with PERMISSION_DENIED. Firestore treats
     * a rejected snapshot listener as permanently dead — it never retries — so the caller
     * ended up with no chat listener for the whole call while the callee (which attaches
     * after the document already exists) worked fine. That is the real
     * "pop-up only shows on one side" asymmetry.
     */
    private final Handler chatRetryHandler = new Handler(Looper.getMainLooper());
    private int           chatListenAttempts = 0;
    private static final int  CHAT_LISTEN_MAX_ATTEMPTS = 8;
    private static final long CHAT_LISTEN_RETRY_CAP_MS = 4_000L;

    /** Banner delivery state — a banner shown while the screen is not visible is invisible. */
    private boolean isActivityVisible = false;

    // ── Picture-in-Picture (Meet-style: video calls shrink to a floating window) ──
    /** True while a sub-screen (in-call chat, permission dialog) is launching: must not trigger PiP. */
    private boolean suppressAutoPip   = false;
    /** Visibility of the self-view tile / TURN banner to restore when leaving PiP. */
    private int     pipSavedLocalVis  = View.GONE;
    private int     pipSavedTurnVis   = View.GONE;
    private boolean chatScreenOpen    = false;
    private String  pendingBannerPreview;

    // ── Audio focus ───────────────────────────────────────────────────────────
    private AudioFocusRequest audioFocusRequest; // API 26+

    // ── Proximity screen-off (voice calls only) ───────────────────────────────
    /** Turns the screen off when the phone is held to the user's ear during voice calls. */
    private PowerManager.WakeLock proximityWakeLock;

    // ── Wired headset routing ─────────────────────────────────────────────────
    /**
     * Re-routes audio when the user plugs/unplugs wired earphones mid-call.
     *
     * <p>This used to call {@code AudioManager.setSpeakerphoneOn()} itself <em>and</em> ask
     * {@link CallManager} to route as well, so two code paths fought over the route and the
     * legacy call was silently ignored on API 31+ anyway. Everything now goes through the one
     * {@link AudioRouteController} owned by the call session.
     */
    private final BroadcastReceiver headsetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AudioManager.ACTION_HEADSET_PLUG.equals(intent.getAction())) return;
            int state = intent.getIntExtra("state", -1);
            AudioRouteController routes = audioRoutes();
            if (routes == null) return;

            if (state == 1) {
                // Headset plugged in — prefer it over the speaker.
                applyRouteKind(AudioRouteController.Kind.WIRED);
                isSpeakerOn = false;
            } else if (state == 0) {
                // Headset unplugged — fall back to the speaker state from before it was in.
                applyRouteKind(isSpeakerOn
                        ? AudioRouteController.Kind.SPEAKER
                        : AudioRouteController.Kind.EARPIECE);
            }
            // Reflect the new audio route on the speaker button.
            runOnUiThread(CallActivity.this::updateSpeakerButtonIcon);
        }
    };

    // ── Notification quick-action receiver ────────────────────────────────────
    /** Handles "End" and "Mute" taps on the ongoing call notification. */
    private final BroadcastReceiver notifActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CallForegroundService.BROADCAST_END_CALL.equals(intent.getAction())) {
                endCall();
            } else if (CallForegroundService.BROADCAST_TOGGLE_MUTE.equals(intent.getAction())) {
                isMuted = !isMuted;
                if (callManager != null) callManager.setMuted(isMuted);
                if (btnMute != null) {
                    btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
                    btnMute.setAlpha(isMuted ? 0.5f : 1f);
                }
                updatePipParams();
            }
        }
    };

    // ── Audio output picker ───────────────────────────────────────────────────
    /** Runtime request for BLUETOOTH_CONNECT, needed only to read Bluetooth device names. */
    private static final int REQ_BLUETOOTH_CONNECT = 4711;
    /** The open output sheet, kept so it can be rebuilt after a permission grant. */
    private BottomSheetDialog audioOutputSheet;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isMuted      = false;
    private boolean isCameraOff  = false;
    private boolean isSpeakerOn  = false;
    private boolean isCaller;
    private boolean isVideo;
    private String  partnerName;
    private String  myUid;
    private String  callId;
    private String  partnerId;

    /** Cancels the TURN-credential wait if the activity is destroyed before it fires. */
    private Handler turnTimeoutHandler;

    // ── No-answer ring timeout (caller-side) ──────────────────────────────────
    /**
     * Fires after 45 seconds if the callee never picks up or declines.
     *
     * <p>Without this guard, a caller whose partner is offline would be stuck on the
     * "Ringing…" screen until CallManager's ICE 60-second timeout fires — which shows
     * "Call failed — check your network" (wrong message) and saves a FAILED record
     * (wrong outcome). The 45-second ring window matches Signal's behaviour and stays
     * well inside the 60-second ICE timeout so the two can never race.
     */
    private final Handler  noAnswerHandler  = new Handler(Looper.getMainLooper());
    private final Runnable noAnswerRunnable = () -> {
        if (callManager == null) return;
        if (callManager.getCurrentState() != CallManager.CallState.OUTGOING_RINGING) return;
        Log.w(TAG, "No answer after 45 s — timing out the outgoing call");
        Toast.makeText(CallActivity.this, "No answer", Toast.LENGTH_SHORT).show();
        // timeoutCall writes status:"timeout" to Firestore, deletes the call doc,
        // and calls setState(ENDED) → onCallStateChanged(ENDED) →
        // updateStatusUi(ENDED) → saveCallRecord(OUTCOME_MISSED) + finish().
        callManager.timeoutCall(callId);
    };

    private final Handler  durationHandler = new Handler(Looper.getMainLooper());
    /**
     * Absolute local-clock instant the call connected, derived from the shared Firestore
     * {@code connectedAt} anchor (see {@link CallManager#onCallTimerAnchor}) so both devices
     * display the same elapsed time. Zero until the anchor resolves.
     */
    private long           callStartMs     = 0;
    /**
     * Guards {@link #durationTick} so it is posted exactly once per call. The CONNECTED state
     * is re-entered on every ICE restart; without this the runnable would stack and the timer
     * would visibly tick several times per second.
     */
    private boolean        durationTickScheduled = false;
    /**
     * True once CONNECTED has been reached at least once. Tracked separately from
     * {@code callStartMs} because the shared anchor arrives asynchronously — a call that ends
     * before it lands is still an answered call, not a missed one.
     */
    private boolean        wasConnected    = false;
    private final Runnable durationTick    = new Runnable() {
        @Override
        public void run() {
            if (callStartMs > 0) {
                long elapsed = (System.currentTimeMillis() - callStartMs) / 1000;
                long min = elapsed / 60;
                long sec = elapsed % 60;
                String text = String.format(java.util.Locale.US, "%02d:%02d", min, sec);
                if (tvCallDuration != null) {
                    tvCallDuration.setText(text);
                    tvCallDuration.setVisibility(View.VISIBLE);
                }
            }
            durationHandler.postDelayed(this, 1000);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_call);

        isCaller    = getIntent().getBooleanExtra(EXTRA_IS_CALLER, true);
        isVideo     = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);
        partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        myUid       = getIntent().getStringExtra(EXTRA_MY_UID);
        callId      = getIntent().getStringExtra(EXTRA_CALL_ID);
        partnerId   = isCaller
                ? getIntent().getStringExtra(EXTRA_CALLEE_ID)
                : getIntent().getStringExtra(EXTRA_CALLER_ID);

        if (partnerName == null) partnerName = "Unknown";

        bindViews();
        setupButtons();

        // The manager must exist before setupAudio(): the route controller it owns is the
        // single source of truth for audio routing, and setupAudio() picks the opening route
        // through it.
        callManager = new CallManager(this);
        callManager.setListener(this);

        setupAudio();

        // Attach the in-call chat banner listener as early as we possibly can.
        //
        // It used to be attached only from inside doStartCall(), which fires when the TURN
        // prefetch callback returns *or* after a 3-second hard timeout. Every message the
        // partner sent during that window was lost, and since the two devices reach that
        // point at different times (TURN disk-cache warmth differs per device), the loss
        // landed on one side only — the other half of the "pop-up shows on one side" report.
        //
        // The callee already knows its callId here (it arrives in the Intent) so it can
        // start listening immediately; the caller has no callId until startCall() mints one,
        // so it attaches from doStartCall(). listenForInCallMessages() is idempotent, so
        // whichever path applies runs once and the other is a harmless no-op.
        listenForInCallMessages();

        // Start the foreground service — this keeps the process alive when the user
        // presses Home, preventing the OS from killing the WebRTC PeerConnection.
        startForegroundCallService();

        // Acquire the proximity wake lock so the screen turns off when the phone is
        // held to the user's ear during a voice call (same behaviour as WhatsApp).
        acquireProximityWakeLock();

        // Register receivers: (a) wired headset plug/unplug for auto audio-routing,
        // (b) End/Mute actions from the ongoing call notification.
        IntentFilter headsetFilter = new IntentFilter(AudioManager.ACTION_HEADSET_PLUG);
        registerReceiver(headsetReceiver, headsetFilter);
        IntentFilter notifFilter = new IntentFilter();
        notifFilter.addAction(CallForegroundService.BROADCAST_END_CALL);
        notifFilter.addAction(CallForegroundService.BROADCAST_TOGGLE_MUTE);
        ContextCompat.registerReceiver(this, notifActionReceiver, notifFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // ── TURN credential warm-up ───────────────────────────────────────────
        // ROOT CAUSE OF 20-SECOND LAG (fixed here):
        //
        // The previous code called TurnCredentialFetcher.prefetch() (fire-and-forget)
        // and then IMMEDIATELY called startCall()/acceptCall().  Both of those call
        // createPeerConnection() → buildIceServers() synchronously.  Because the async
        // HTTP prefetch had not completed yet, buildIceServers() found an empty cache
        // and created the PeerConnection with STUN-only — no TURN relay servers.
        //
        // On mobile networks with CGNAT or symmetric NAT, STUN-only ICE gathers
        // candidates but they never connect (both sides are behind non-traversable NAT).
        // ICE then waits the full 60-second CONNECTION_TIMEOUT_MS before failing, which
        // manifests as the 20-second lag the user observes before audio/video starts
        // (on lucky networks ICE stumbles into a working path eventually; on strict
        // networks it never connects at all).
        //
        // FIX: use the callback form of prefetch().  The call only starts after
        // credentials are confirmed cached (success path) or after a 3-second hard
        // timeout (failure path — avoids blocking the callee's ring-accept window).
        // For callees the disk-warmed cache (TurnCredentialCache.init) handles the
        // common cold-start case; the 3 s timeout covers the rare "first ever call"
        // case where no credentials were ever persisted.
        TurnCredentialCache.init(this); // load previously persisted creds from disk

        // Show TURN quota warning before the call starts so user knows what to expect.
        checkAndShowTurnWarning();

        final String    chatId      = isCaller ? getIntent().getStringExtra(EXTRA_CHAT_ID) : null;
        final boolean[] started     = {false};
        turnTimeoutHandler = new Handler(Looper.getMainLooper());

        // doStartCall is idempotent — the timeout and the callback both reference it;
        // the boolean guard ensures the PeerConnection is created exactly once.
        final Runnable doStartCall = () -> {
            if (started[0]) return;
            started[0] = true;

            // BUG FIX — local video was always blank:
            // startCall/acceptCall call createLocalTracks() which fires onLocalVideoTrack()
            // → track.addSink(localVideoView) SYNCHRONOUSLY.  If initVideoRenderers() runs
            // AFTER that call (the previous ordering), the sink is attached to an
            // uninitialised SurfaceViewRenderer and the "You" PiP stays blank for the
            // entire call.
            //
            // Fix: prepareEgl() calls initFactory() to create eglBase, then
            // initVideoRenderers() initialises both SurfaceViewRenderers.  Only THEN do
            // we start the call so addSink() always finds a ready renderer.
            callManager.prepareEgl();
            initVideoRenderers();

            // Guard: camera permission may have been revoked after the call was initiated
            // (e.g. user denied via system dialog that appeared concurrently).  Fall back to
            // audio-only rather than silently showing a permanently black local PiP.
            if (isVideo && ContextCompat.checkSelfPermission(CallActivity.this,
                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Camera permission not granted — falling back to audio-only");
                isVideo = false;
                runOnUiThread(() -> Toast.makeText(CallActivity.this,
                        "Camera unavailable — starting voice-only call",
                        Toast.LENGTH_LONG).show());
            }

            if (isCaller) {
                callManager.startCall(myUid, partnerId, isVideo, chatId);
                // BUG FIX — in-call chat always said "call not established" for the caller:
                // callId in CallActivity was read from the Intent (null for the caller —
                // ChatMediaActivity never puts EXTRA_CALL_ID).  The real callId is generated
                // inside CallManager.startCall(), so we sync it back here immediately.
                callId = callManager.getCallId();

                // Start the no-answer watchdog.  If the callee is offline (no FCM delivery)
                // or simply doesn't respond, the call doc stays "ringing" indefinitely.
                // After 45 s we surface "No answer" and tear down cleanly.  This fires
                // well before CallManager's 60-second ICE watchdog, so the two never race.
                noAnswerHandler.removeCallbacks(noAnswerRunnable);
                noAnswerHandler.postDelayed(noAnswerRunnable, 45_000);
            } else {
                callManager.acceptCall(myUid, callId, isVideo);
            }

            // Start listening for in-call chat messages so we can show Google Meet-style
            // banners when the partner sends a message.
            //
            // This is the CALLER's attach point: the callee already attached in onCreate
            // (its callId arrives in the Intent), but the caller's callId only comes into
            // existence when CallManager.startCall() generates it a few lines above.
            //
            // Deliberately NOT gated on isVideo any more. The old `if (isVideo)` gate was
            // justified as "audio calls have no chat", but the camera-permission fallback
            // above can flip isVideo to false *after* setupButtons() has already made the
            // chat button visible — leaving that device with a reachable chat whose
            // banners never fire, while the partner's still do. The chat path is pure
            // Firestore and does not depend on the video track at all.
            listenForInCallMessages();
        };

        // Hard deadline: start the call after 3 s even if TURN fetch is still in-flight.
        //
        // With IceTransportsType.ALL, host and STUN-reflexive candidates are gathered
        // regardless of TURN availability, so the call can still connect via P2P if
        // credentials are slow.  3 s is enough for a warm disk cache hit (<100 ms) or
        // a single fast network round-trip, while staying well inside the 30 s ring window.
        turnTimeoutHandler.postDelayed(() -> {
            if (!TurnCredentialCache.get().isValid()) {
                Log.w(TAG, "TURN credentials not ready after 3 s — starting with STUN/host only");
            }
            doStartCall.run();
        }, 3_000);

        // Preferred path: start as soon as credentials are confirmed ready (typically <1 s
        // if the disk cache is warm, or a few seconds for a fresh network fetch).
        TurnCredentialFetcher.prefetch(success -> runOnUiThread(() -> {
            Log.d(TAG, "TURN prefetch done (success=" + success + ") — starting call");
            turnTimeoutHandler.removeCallbacks(doStartCall);
            doStartCall.run();
        }));

        updateStatusUi(CallManager.CallState.OUTGOING_RINGING);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityVisible = true;
        // Coming back from a minimised call: the proximity lock was released on the way out.
        if (proximityWakeLock == null && !isFinishing() && callManager != null) {
            CallManager.CallState st = callManager.getCurrentState();
            if (st != CallManager.CallState.ENDED && st != CallManager.CallState.FAILED) {
                acquireProximityWakeLock();
            }
        }
        // Returning here means the in-call chat / permission dialog was dismissed.
        chatScreenOpen = false;
        suppressAutoPip = false;
        updatePipParams();
        // Coming back to the call from Home/Recents (the middle button) or another screen:
        // re-show the self-view PiP so it behaves like Google Meet. The SurfaceViewRenderer
        // itself recovers its frames automatically once the surface is recreated; we just have
        // to make sure the tile is visible again for a video call whose camera is still on.
        if (isVideo && !isCameraOff && localVideoPip != null && !isInPictureInPictureMode()) {
            localVideoPip.setVisibility(View.VISIBLE);
        }
        // Re-attach if a listener error killed the registration while we were away.
        listenForInCallMessages();
        // Surface anything that arrived while the call screen was not on top.
        if (pendingBannerPreview != null) {
            String preview = pendingBannerPreview;
            pendingBannerPreview = null;
            showNewMessageBanner(preview);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
    }

    /**
     * Fires on Home / Recents (and whenever this activity launches another one, which is why
     * {@link #suppressAutoPip} exists). Releases the proximity lock so it can't black out the
     * screen while another app is in front, and on Android 8-11 enters PiP for video calls.
     * Android 12+ does the same through {@code setAutoEnterEnabled} in {@link #buildPipParams()}.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        releaseProximityWakeLock();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !suppressAutoPip) {
            enterPip();
        }
    }

    /**
     * Back behaviour (Google Meet style): the call keeps running. Video calls shrink to a
     * Picture-in-Picture window; voice calls (or devices without PiP) go to the background and
     * the ongoing-call notification is the way back in. Only End hangs up. If the call has
     * already finished there is nothing to keep alive, so just close.
     */
    private void minimizeCall() {
        switch (CallBackPolicy.onBack(isCallActive(), canUsePip())) {
            case CLOSE:
                finish();
                return;
            case ENTER_PIP:
                if (enterPip()) return;
                // PiP refused (disabled in settings, not allowed right now): fall through.
            case BACKGROUND:
            default:
                releaseProximityWakeLock();
                moveTaskToBack(true);
        }
    }

    /**
     * The one "user ended the call" path: End button, notification End and PiP End all land
     * here. {@link CallManager#hangup()} is a no-op once the call is already ENDED, so this is
     * safe to reach more than once. Back / Home never call this.
     */
    private void endCall() {
        if (callManager != null) callManager.hangup();
        finish();
    }

    private boolean isCallActive() {
        if (callManager == null) return false;
        CallManager.CallState st = callManager.getCurrentState();
        return st != CallManager.CallState.ENDED && st != CallManager.CallState.FAILED;
    }

    // ── Picture-in-Picture ────────────────────────────────────────────────────

    private boolean canUsePip() {
        return isVideo
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private boolean enterPip() {
        if (!canUsePip() || !isCallActive() || isInPictureInPictureMode()) return false;
        try {
            return enterPictureInPictureMode(buildPipParams());
        } catch (IllegalStateException | IllegalArgumentException e) {
            // PiP disabled by the user in system settings, or not allowed right now.
            Log.w(TAG, "enterPictureInPictureMode failed — falling back to background", e);
            return false;
        }
    }

    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(9, 16))
                .setActions(buildPipActions());
        Rect hint = pipSourceRectHint();
        if (hint != null) b.setSourceRectHint(hint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(
                    CallBackPolicy.autoEnterPip(isCallActive(), suppressAutoPip, canUsePip()));
        }
        return b.build();
    }

    /**
     * Where the PiP window should animate from: the video surface, cropped (centred) to the
     * PiP aspect ratio so the system doesn't have to stretch it. {@code null} until the view
     * has been laid out, and while already in PiP.
     */
    private Rect pipSourceRectHint() {
        if (isInPictureInPictureMode()) return null;
        View src = (remoteVideoView != null && remoteVideoView.getVisibility() == View.VISIBLE)
                ? remoteVideoView : voiceOnlyBg;
        if (src == null || src.getWidth() == 0 || src.getHeight() == 0) return null;
        Rect r = new Rect();
        if (!src.getGlobalVisibleRect(r) || r.isEmpty()) return null;
        final float target = 9f / 16f;                       // width / height
        float have = (float) r.width() / r.height();
        if (have > target) {                                 // too wide → trim the sides
            int w = Math.round(r.height() * target);
            int cut = (r.width() - w) / 2;
            r.left += cut; r.right = r.left + w;
        } else {                                             // too tall → trim top/bottom
            int h = Math.round(r.width() / target);
            int cut = (r.height() - h) / 2;
            r.top += cut; r.bottom = r.top + h;
        }
        return r;
    }

    /** Pushes fresh PiP params (mute icon, auto-enter on/off). Safe to call at any time. */
    private void updatePipParams() {
        if (!canUsePip() || isFinishing() || isDestroyed()) return;
        try {
            setPictureInPictureParams(buildPipParams());
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "setPictureInPictureParams failed", e);
        }
    }

    /** Mute + End buttons shown on the PiP window. They reuse the notification broadcasts. */
    private List<RemoteAction> buildPipActions() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent mutePi = PendingIntent.getBroadcast(this, 20,
                new Intent(CallForegroundService.BROADCAST_TOGGLE_MUTE).setPackage(getPackageName()),
                flags);
        PendingIntent endPi = PendingIntent.getBroadcast(this, 21,
                new Intent(CallForegroundService.BROADCAST_END_CALL).setPackage(getPackageName()),
                flags);
        List<RemoteAction> actions = new ArrayList<>();
        actions.add(new RemoteAction(
                Icon.createWithResource(this, isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic),
                isMuted ? "Unmute" : "Mute", "Toggle microphone", mutePi));
        actions.add(new RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_call_end),
                "End", "End call", endPi));
        return actions;
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        applyPipUi(inPip);
    }

    /**
     * The voice-only background (shown while the partner's video is off or not yet arrived) is
     * sized for a full screen. In the small PiP window shrink the avatar and add the partner's
     * name, since the header that normally carries it is hidden.
     */
    private void setCompactAvatar(boolean compact) {
        if (avatarFrame != null) {
            int size = dp(compact ? 56 : 128);
            ViewGroup.LayoutParams lp = avatarFrame.getLayoutParams();
            lp.width = size; lp.height = size;
            avatarFrame.setLayoutParams(lp);
        }
        if (tvCallAvatarInitial != null) {
            int size = dp(compact ? 56 : 128);
            ViewGroup.LayoutParams lp = tvCallAvatarInitial.getLayoutParams();
            lp.width = size; lp.height = size;
            tvCallAvatarInitial.setLayoutParams(lp);
            tvCallAvatarInitial.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 24 : 52);
        }
        if (tvPipPartnerName != null) {
            tvPipPartnerName.setText(partnerName);
            tvPipPartnerName.setVisibility(compact ? View.VISIBLE : View.GONE);
        }
        if (tvCallStatus != null) {
            tvCallStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12 : 15);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) tvCallStatus.getLayoutParams();
            lp.topMargin = dp(compact ? 4 : 20);
            tvCallStatus.setLayoutParams(lp);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** In PiP only the remote video is shown; the full UI comes back when the window expands. */
    private void applyPipUi(boolean inPip) {
        if (inPip) {
            if (localVideoPip != null) pipSavedLocalVis = localVideoPip.getVisibility();
            if (bannerTurnWarning != null) pipSavedTurnVis = bannerTurnWarning.getVisibility();
            bannerDismissHandler.removeCallbacksAndMessages(null);
            if (bannerNewMessage != null) {
                bannerNewMessage.animate().cancel();
                bannerNewMessage.setVisibility(View.GONE);
            }
            setCompactAvatar(true);
            if (callHeader != null)         callHeader.setVisibility(View.GONE);
            if (callControlsBar != null)    callControlsBar.setVisibility(View.GONE);
            if (localVideoPip != null)      localVideoPip.setVisibility(View.GONE);
            if (bannerTurnWarning != null)  bannerTurnWarning.setVisibility(View.GONE);
        } else {
            setCompactAvatar(false);
            if (callHeader != null)         callHeader.setVisibility(View.VISIBLE);
            if (callControlsBar != null)    callControlsBar.setVisibility(View.VISIBLE);
            if (localVideoPip != null) {
                localVideoPip.setVisibility(isCameraOff ? View.GONE : pipSavedLocalVis);
            }
            if (bannerTurnWarning != null)  bannerTurnWarning.setVisibility(pipSavedTurnVis);
            // Surface a chat message that arrived while in PiP (onResume() also does this when
            // it fires; the null-out below makes the two paths idempotent).
            if (pendingBannerPreview != null && !chatScreenOpen) {
                String preview = pendingBannerPreview;
                pendingBannerPreview = null;
                showNewMessageBanner(preview);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop the foreground service (removes the ongoing notification).
        stopForegroundCallService();

        // Release the proximity wake lock — screen can turn back on normally.
        releaseProximityWakeLock();

        // Unregister dynamic broadcast receivers.
        try { unregisterReceiver(headsetReceiver);   } catch (Exception ignored) {}
        try { unregisterReceiver(notifActionReceiver); } catch (Exception ignored) {}

        historyExecutor.shutdownNow();
        durationHandler.removeCallbacksAndMessages(null);
        bannerDismissHandler.removeCallbacksAndMessages(null);
        noAnswerHandler.removeCallbacksAndMessages(null);
        chatRetryHandler.removeCallbacksAndMessages(null);
        if (chatMessageListener != null) { chatMessageListener.remove(); chatMessageListener = null; }
        // Cancel the TURN-credential wait if it hasn't fired yet.
        if (turnTimeoutHandler != null) turnTimeoutHandler.removeCallbacksAndMessages(null);

        // Release SurfaceViewRenderers FIRST — this removes the video sink from the tracks
        // so the tracks can be safely disposed by hangup()/release() without rendering to a
        // released surface (which can crash the GL thread in libwebrtc).
        releaseVideoRenderers();

        if (audioOutputSheet != null) {
            if (audioOutputSheet.isShowing()) audioOutputSheet.dismiss();
            audioOutputSheet = null;
        }

        // Release the route selection and restore the call volume *before* the tracks go away,
        // while the controller can still unmute the remote audio track it disabled.
        AudioRouteController routes = audioRoutes();
        if (routes != null) routes.endSession();

        if (callManager != null) {
            CallManager.CallState state = callManager.getCurrentState();
            if (state != CallManager.CallState.ENDED && state != CallManager.CallState.FAILED) {
                // Normal teardown: write "ended" to Firestore, then free native resources.
                callManager.hangup();
            } else {
                // Remote side ended the call: Firestore was already updated; we still must
                // free the PeerConnection, tracks, camera, EglBase, and factory — if we skip
                // this the process holds the camera open indefinitely and the GC cannot
                // collect the native peer-connection allocation.
                callManager.release();
            }
        }

        // Mode reset and device release are handled by endSession() above — doing it again
        // here with the deprecated calls would be a no-op on API 31+ anyway.
        abandonAudioFocus();
    }

    // ── View binding ─────────────────────────────────────────────��────────────

    private void bindViews() {
        remoteVideoView     = findViewById(R.id.remoteVideoView);
        // The very first onResume() can run before layout, when there is no rect to hint yet.
        remoteVideoView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) updatePipParams();
        });
        localVideoView      = findViewById(R.id.localVideoView);
        localVideoPip       = findViewById(R.id.localVideoPip);
        voiceOnlyBg         = findViewById(R.id.voiceOnlyBg);
        tvCallPartnerName   = findViewById(R.id.tvCallPartnerName);
        tvCallStatus        = findViewById(R.id.tvCallStatus);
        tvCallDuration      = findViewById(R.id.tvCallDuration);
        tvCallStatusOverlay = findViewById(R.id.tvCallStatusOverlay);
        tvCallAvatarInitial = findViewById(R.id.tvCallAvatarInitial);
        btnMute             = findViewById(R.id.btnMute);
        btnCamera           = findViewById(R.id.btnCamera);
        btnCameraLayout     = findViewById(R.id.btnCameraLayout);
        btnEndCall          = findViewById(R.id.btnEndCall);
        btnSpeaker          = findViewById(R.id.btnSpeaker);
        btnFlipCamera         = findViewById(R.id.btnFlipCamera);
        btnFlipLayout         = findViewById(R.id.btnFlipLayout);
        btnBack               = findViewById(R.id.btnBack);
        callHeader            = findViewById(R.id.callHeader);
        avatarFrame           = findViewById(R.id.avatarFrame);
        tvPipPartnerName      = findViewById(R.id.tvPipPartnerName);
        callControlsBar       = findViewById(R.id.callControlsBar);
        btnChat               = findViewById(R.id.btnChat);

        // TURN banner
        bannerTurnWarning = findViewById(R.id.bannerTurnWarning);
        tvTurnWarningText = findViewById(R.id.tvTurnWarningText);
        ImageView btnDismiss = findViewById(R.id.btnTurnWarningDismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> bannerTurnWarning.setVisibility(View.GONE));
        }

        // New-message banner
        bannerNewMessage = findViewById(R.id.bannerNewMessage);
        tvNewMsgPreview  = findViewById(R.id.tvNewMsgPreview);
        btnChatLayout    = findViewById(R.id.btnChatLayout);
        if (bannerNewMessage != null) {
            bannerNewMessage.setOnClickListener(v -> {
                bannerNewMessage.setVisibility(View.GONE);
                bannerDismissHandler.removeCallbacksAndMessages(null);
                openInCallChat();
            });
        }

        // Populate static text
        tvCallPartnerName.setText(partnerName);
        tvCallAvatarInitial.setText(partnerName.substring(0, 1).toUpperCase());

        // Camera, flip, and chat buttons are only relevant in video calls
        if (isVideo) {
            btnCameraLayout.setVisibility(View.VISIBLE);
            if (btnChatLayout          != null) btnChatLayout.setVisibility(View.VISIBLE);
            // btnFlipLayout lives inside localVideoPip; visible once PiP appears
        }
    }

    // ── Button listeners ─────────────────────────────────────────────────────��

    private void setupButtons() {
        // Back arrow: minimise the call (keeps it alive). Only the End button hangs up.
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> minimizeCall());
        }

        // System back (button / gesture / predictive back): same behaviour as the arrow.
        // OnBackPressedCallback is required because the manifest opts in to predictive back,
        // which bypasses Activity.onBackPressed() on Android 13+.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                minimizeCall();
            }
        });

        // Mute microphone
        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            callManager.setMuted(isMuted);
            btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            btnMute.setAlpha(isMuted ? 0.5f : 1f);
            updatePipParams();
        });

        // Camera toggle (video calls only) — turns camera on/off
        btnCamera.setOnClickListener(v -> {
            isCameraOff = !isCameraOff;
            callManager.setCameraEnabled(!isCameraOff);
            btnCamera.setImageResource(isCameraOff ? R.drawable.ic_videocam_off : R.drawable.ic_videocam);
            btnCamera.setAlpha(isCameraOff ? 0.5f : 1f);
            localVideoPip.setVisibility(isCameraOff ? View.GONE : View.VISIBLE);
        });

        // End call
        btnEndCall.setOnClickListener(v -> endCall());

        // Audio output — opens a bottom-sheet picker instead of simple toggle
        btnSpeaker.setOnClickListener(v -> showAudioOutputPicker());

        // Flip camera (PiP button — visible when local video is live)
        btnFlipCamera.setOnClickListener(v -> callManager.flipCamera());

        // Flip camera (controls-bar button — always accessible in video calls)

        // In-call chat
        if (btnChat != null) {
            btnChat.setOnClickListener(v -> openInCallChat());
        }

        // Draggable PiP — WhatsApp-style free drag, snaps to edge on release.
        setupPipDrag();
    }

    // ── Draggable local-video PiP ─────────────────────────────────────────────

    /**
     * Makes the "You" PiP freely draggable anywhere on screen.  On release it
     * snaps to whichever vertical edge (left or right) the PiP's centre is
     * closest to — the same behaviour as WhatsApp, FaceTime, and Signal.
     *
     * <p><b>Tap-vs-drag:</b> a displacement of less than 8 dp from the finger-down
     * point is treated as a tap, so the flip-camera button inside the PiP is still
     * perfectly clickable.
     *
     * <p><b>Snap animation:</b> 250 ms with {@link DecelerateInterpolator}, using a
     * hardware layer ({@code withLayer()}) for a smooth GPU composite over the
     * SurfaceViewRenderer.
     */
    private void setupPipDrag() {
        if (localVideoPip == null) return;

        final float[] dX      = {0f}; // view.getX() − rawX at ACTION_DOWN
        final float[] dY      = {0f}; // view.getY() − rawY at ACTION_DOWN
        final float[] downRX  = {0f}; // raw X at ACTION_DOWN (tap-vs-drag check)
        final float[] downRY  = {0f};
        final boolean[] moved = {false};

        localVideoPip.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel(); // stop any ongoing snap
                    dX[0]     = v.getX() - event.getRawX();
                    dY[0]     = v.getY() - event.getRawY();
                    downRX[0] = event.getRawX();
                    downRY[0] = event.getRawY();
                    moved[0]  = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float threshold =
                            8f * getResources().getDisplayMetrics().density;
                    if (!moved[0]
                            && (Math.abs(event.getRawX() - downRX[0]) > threshold
                             || Math.abs(event.getRawY() - downRY[0]) > threshold)) {
                        moved[0] = true;
                    }
                    if (moved[0]) {
                        View parent  = (View) v.getParent();
                        float newX   = Math.max(0, Math.min(
                                event.getRawX() + dX[0], parent.getWidth()  - v.getWidth()));
                        float newY   = Math.max(0, Math.min(
                                event.getRawY() + dY[0], parent.getHeight() - v.getHeight()));
                        v.setX(newX);
                        v.setY(newY);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!moved[0]) {
                        // Pure tap — forward to the flip-camera button if the
                        // finger landed inside its bounds.
                        if (btnFlipCamera != null) {
                            float relX = event.getX() - btnFlipCamera.getLeft();
                            float relY = event.getY() - btnFlipCamera.getTop();
                            if (relX >= 0 && relX <= btnFlipCamera.getWidth()
                                    && relY >= 0 && relY <= btnFlipCamera.getHeight()) {
                                btnFlipCamera.performClick();
                            }
                        }
                        return true;
                    }
                    // Snap to nearest vertical edge, WhatsApp-style.
                    View parent  = (View) v.getParent();
                    float margin = 16f * getResources().getDisplayMetrics().density;
                    float pipCX  = v.getX() + v.getWidth() / 2f;
                    float snapX  = (pipCX < parent.getWidth() / 2f)
                            ? margin
                            : parent.getWidth() - v.getWidth() - margin;
                    v.animate()
                            .x(snapX)
                            .setDuration(250)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer() // smooth composite over SurfaceViewRenderer
                            .start();
                    return true;
            }
            return false;
        });
    }

    // ── Audio setup ───────────────────────────────────────────────────────────

    private void setupAudio() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;

        // Opening route: a connected headset (Bluetooth or wired) wins, otherwise speaker for
        // video and earpiece for voice. The controller does that selection with
        // setCommunicationDevice() on API 31+, where the legacy setSpeakerphoneOn() /
        // startBluetoothSco() calls this method used to make were silently ignored — which is
        // why an already-connected headset was bypassed at call start on modern devices.
        AudioRouteController routes = audioRoutes();
        if (routes != null) {
            routes.beginSession(isVideo);
            isSpeakerOn = routes.currentKind() == AudioRouteController.Kind.SPEAKER;
        } else {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }
        // Sync speaker button icon to reflect the initial audio route.
        updateSpeakerButtonIcon();

        // Request exclusive audio focus so music / media apps pause automatically
        // and the OS knows a voice call is in progress (affects Bluetooth routing,
        // notification ducking, etc.). Mandatory for WhatsApp-quality call behaviour.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        // LOSS_TRANSIENT: another app briefly grabbed focus (e.g. TTS).
                        // LOSS: e.g. another call arrived; mute ourselves so the user
                        // doesn't hear feedback. We don't hang up automatically because
                        // the user may return to us.
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                                || focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            if (callManager != null) callManager.setMuted(true);
                        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                            if (callManager != null) callManager.setMuted(isMuted);
                        }
                    })
                    .build();
            am.requestAudioFocus(audioFocusRequest);
        } else {
            //noinspection deprecation
            am.requestAudioFocus(null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    /**
     * Updates the speaker/audio-output button icon in the header to reflect the
     * current routing state: loudspeaker, earpiece, or Bluetooth.
     *
     * <p>Called after every audio-route change (picker selection, headset plug/unplug,
     * initial setup) so the button always matches the active output device.
     */
    private void updateSpeakerButtonIcon() {
        if (btnSpeaker == null) return;
        AudioRouteController routes = audioRoutes();
        if (routes == null) return;
        // Read the live route back from the framework rather than a local boolean, so the icon
        // cannot drift from what the device is actually playing through.
        int iconRes;
        switch (routes.currentKind()) {
            case BLUETOOTH: iconRes = R.drawable.ic_bluetooth_audio; break;
            case SPEAKER:   iconRes = R.drawable.ic_speaker_on;      break;
            case MUTED:     iconRes = R.drawable.ic_volume_off;      break;
            default:        iconRes = R.drawable.ic_call_phone;      break;
        }
        btnSpeaker.setImageResource(iconRes);
    }

    // ── Audio route plumbing ──────────────────────────────────────────────────

    /** The call session's shared route controller, or {@code null} before the call exists. */
    private AudioRouteController audioRoutes() {
        return callManager != null ? callManager.audioRoute() : null;
    }

    /**
     * Applies the first available route of {@code kind}.
     *
     * @return {@code true} when the framework accepted it. Callers surface a failure to the
     *         user instead of leaving a checkmark on a route that never took effect.
     */
    private boolean applyRouteKind(AudioRouteController.Kind kind) {
        AudioRouteController routes = audioRoutes();
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

    /** Row icon for a route family, matching the reference design's sheet. */
    private static int iconForRoute(AudioRouteController.Kind kind) {
        switch (kind) {
            case SPEAKER:   return R.drawable.ic_speaker_on;
            case BLUETOOTH: return R.drawable.ic_bluetooth_audio;
            case MUTED:     return R.drawable.ic_volume_off;
            case WIRED:
            case EARPIECE:
            default:        return R.drawable.ic_call_phone;
        }
    }

    /**
     * Asks for BLUETOOTH_CONNECT so Bluetooth rows can show the real device name.
     *
     * <p>Deliberately non-blocking: the picker is shown either way and the routes still work,
     * the labels are just generic without the grant. Re-requested each time the sheet opens
     * only while the permission is still missing.
     */
    private void requestBluetoothNamePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            suppressAutoPip = true;
            updatePipParams();
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BLUETOOTH_CONNECT);
        } catch (Exception e) {
            Log.w(TAG, "BLUETOOTH_CONNECT request failed", e);
            suppressAutoPip = false;
            updatePipParams();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BLUETOOTH_CONNECT) return;
        // A request that resolves without showing a dialog never pauses the activity, so
        // onResume() would not clear this. Always release the PiP hold here.
        suppressAutoPip = false;
        updatePipParams();
        // Reopen the sheet on grant so the user immediately sees the named device rather than
        // having to tap the button a second time.
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && !isFinishing() && !isDestroyed()) {
            showAudioOutputPicker();
        }
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && audioFocusRequest != null) {
            am.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            //noinspection deprecation
            am.abandonAudioFocus(null);
        }
    }

    // ── Video renderer init ───────────────────────────────────────────────────

    private void initVideoRenderers() {
        EglBase eglBase = callManager.getEglBase();
        if (eglBase == null) return;
        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.setMirror(false);
        remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);

        // ── ROOT CAUSE OF "I can't see my own video" (the transparent PiP) ──
        // Both the remote feed and the local PiP are GPU SurfaceViews. By default every
        // SurfaceView punches a hole through the window and is composited BEHIND it, so two
        // overlapping SurfaceViews render in an undefined Z order — on most devices the
        // full-screen remote surface wins and the smaller PiP surface is drawn underneath it,
        // which is exactly why the "You" tile looked see-through (you saw the remote feed
        // through it) for the first seconds of every call.
        //
        // setZOrderMediaOverlay(true) lifts the local surface onto the media-overlay layer so it
        // composites ON TOP of the remote surface. It MUST be called before init() creates the
        // surface. This also makes the PiP survive returning to the call from Home/Recents (the
        // middle button): the overlay ordering is re-applied when the surface is recreated, so
        // the self-view comes back instead of going blank — matching Google Meet.
        localVideoView.setZOrderMediaOverlay(true);
        localVideoView.init(eglBase.getEglBaseContext(), null);
        // The capturer starts on the front camera, whose frames are un-mirrored (i.e. reversed
        // from the user's point of view). Mirroring the self-preview makes it behave like a
        // real mirror, matching WhatsApp. The rear camera must NOT be mirrored, so this flips
        // back and forth via onLocalMirrorChanged() whenever the camera is switched.
        localVideoView.setMirror(callManager.isFrontCamera());
        localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
    }

    // ── Audio output picker ───────────────────────────────────────────────────

    /**
     * Shows a bottom-sheet audio-output picker that mirrors the design in the
     * reference screenshot: Speaker / Phone (earpiece) / Bluetooth devices /
     * Turn off sound / Cancel. The active device is indicated by a purple
     * checkmark on the right.
     */
    private void showAudioOutputPicker() {
        AudioRouteController routes = audioRoutes();
        if (routes == null) return;

        // Bluetooth device *names* need BLUETOOTH_CONNECT on API 31+. Ask here rather than
        // gating the whole picker on it: without the grant the route still works, the row is
        // just labelled generically (see AudioRouteController.labelFor).
        requestBluetoothNamePermission();

        // Replace any sheet already on screen (e.g. the one open while the BLUETOOTH_CONNECT
        // dialog was answered) so grants never stack two sheets.
        if (audioOutputSheet != null && audioOutputSheet.isShowing()) audioOutputSheet.dismiss();

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        audioOutputSheet = dialog;
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_audio_output, null);
        dialog.setContentView(sheetView);

        // Make the BottomSheetDialog's wrapper FrameLayout transparent so the
        // rounded-corner bg_audio_sheet drawable on our content view shows correctly.
        sheetView.post(() -> {
            View parent = (View) sheetView.getParent();
            if (parent != null) parent.setBackgroundColor(Color.TRANSPARENT);
        });

        LinearLayout container = sheetView.findViewById(R.id.audioOutputContainer);

        // Rows come from the framework's live device list, so a row can never be one the
        // framework would refuse. The checkmark is read back from the active route (device id
        // included, which distinguishes two paired headsets) instead of a local boolean.
        AudioRouteController.Kind activeKind = routes.currentKind();
        int activeDeviceId = routes.currentDeviceId();

        for (AudioRouteController.Route route : routes.availableRoutes()) {
            boolean checked = route.kind == activeKind
                    && (activeDeviceId < 0 || activeDeviceId == route.deviceId);
            addAudioOutputItem(container, iconForRoute(route.kind), route.label,
                    checked, () -> {
                        if (routes.apply(route)) {
                            isSpeakerOn = route.kind == AudioRouteController.Kind.SPEAKER;
                        } else {
                            // Surfacing this beats leaving a checkmark on a route that the
                            // framework rejected — the exact failure mode this screen had.
                            Toast.makeText(CallActivity.this,
                                    "Couldn't switch to " + route.label,
                                    Toast.LENGTH_SHORT).show();
                        }
                        updateSpeakerButtonIcon();
                        dialog.dismiss();
                    });
        }

        // ── Turn off sound ──
        // Silences only what *we* hear, by disabling the remote audio track. The previous
        // version also muted the microphone, which cut our voice off for the partner — the
        // opposite of what this row promises. The mic button is left untouched.
        addAudioOutputItem(container, R.drawable.ic_volume_off, "Turn off sound",
                activeKind == AudioRouteController.Kind.MUTED, () -> {
                    if (!applyRouteKind(AudioRouteController.Kind.MUTED)) {
                        Toast.makeText(CallActivity.this,
                                "Couldn't turn off sound", Toast.LENGTH_SHORT).show();
                    }
                    isSpeakerOn = false;
                    updateSpeakerButtonIcon();
                    dialog.dismiss();
                });

        // ── Cancel ──
        addAudioOutputItem(container, R.drawable.ic_close, "Cancel",
                false, dialog::dismiss);

        dialog.show();
    }

    /** Inflates one row into the audio-output bottom sheet. */
    private void addAudioOutputItem(LinearLayout container, int iconRes,
                                    String label, boolean checked, Runnable onClick) {
        View item = getLayoutInflater().inflate(R.layout.item_audio_output, container, false);
        ((ImageView) item.findViewById(R.id.ivAudioIcon)).setImageResource(iconRes);
        ((TextView)  item.findViewById(R.id.tvAudioLabel)).setText(label);
        item.findViewById(R.id.ivAudioCheck)
                .setVisibility(checked ? View.VISIBLE : View.GONE);
        item.setOnClickListener(v -> onClick.run());
        container.addView(item);
    }

    // ── In-call message notifications ─────────────────────────────────────────

    /**
     * Listens for new messages in the in-call chat Firestore subcollection and shows a
     * Google Meet-style pill banner at the top of the screen when the partner sends one.
     * Only called for video calls (audio calls have no chat feature).
     */
    /**
     * Listens for new in-call chat messages and shows a banner for partner messages.
     *
     * <p><b>History of this bug.</b> The first implementation filtered by
     * {@code ts > listenStartMs}. With even a few seconds of clock skew between the
     * two devices the partner's messages carried a smaller ts and the query dropped
     * them, so one side never got a banner.
     *
     * <p>The follow-up fix removed the ts filter but added an "initial load" pass
     * that marked <em>every</em> document in the first snapshot as already-seen and
     * returned early. That reintroduced the same one-sided symptom for a different
     * reason: the content of Firestore's first snapshot is not deterministic. It is
     * raised from the local cache when there is cached data and from the server
     * otherwise, so whether a freshly-sent message lands *in* the first snapshot or
     * arrives *after* it as an ADDED change depends on cache warmth and round-trip
     * timing — which differ per device. On the device where the message happened to
     * be inside that first snapshot it was silently swallowed as "history" and the
     * banner never fired, while the other device saw it as ADDED and worked. Hence
     * "the pop-up only comes on one side".
     *
     * <p><b>Current approach.</b> The {@code calls/{callId}/chat} subcollection is
     * created fresh for each call and swept when the call document is deleted, so
     * there is no historical backlog that needs suppressing — the seed pass had no
     * legitimate purpose. Every ADDED change is therefore processed, with
     * {@link #seenChatMsgIds} as the only dedupe (it also absorbs the duplicate
     * cache→server delivery of the same document). No wall-clock comparison is
     * involved, so device clock skew cannot affect it either.
     *
     * <p><b>Third and actual cause of the one-sided report.</b> Neither fix above touched
     * listener <em>survival</em>. The caller attaches here right after {@code startCall()},
     * before the {@code calls/{callId}} document has been written, so the rule guarding
     * {@code calls/{callId}/chat} cannot resolve its parent and Firestore rejects the query.
     * A rejected snapshot listener is never retried by the SDK, so the caller stayed deaf
     * for the entire call while the callee — which attaches after the document exists —
     * worked normally. Sending still worked from both devices (the chat screen attaches
     * later, once the document is there), which is why the symptom looked purely cosmetic.
     * The error branch below now re-attaches with backoff instead of returning.
     */
    private void listenForInCallMessages() {
        if (callId == null || myUid == null) return;
        // Idempotent: the callee attaches from onCreate (callId arrives in the Intent)
        // and the caller attaches as soon as CallManager has generated the callId.
        // Whichever path runs first wins; a second call is a no-op rather than a
        // duplicate listener that would double-banner every message.
        if (chatMessageListener != null) return;

        chatMessageListener = FirebaseFirestore.getInstance()
                .collection("calls").document(callId)
                .collection("chat")
                .orderBy("ts", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        // Never fail silently: a PERMISSION_DENIED or transient network
                        // error here is invisible to the user and looks exactly like
                        // "the banner works for them but not for me".
                        //
                        // Firestore does NOT resurrect a rejected listener, so simply
                        // logging and returning left this device permanently deaf to the
                        // partner's messages. Drop the dead registration and re-attach with
                        // backoff: by then the parent call document exists and the rule
                        // passes. seenChatMsgIds survives the re-attach, so the replayed
                        // ADDED changes cannot double-banner, and any message sent during
                        // the dead window still surfaces on the first successful snapshot.
                        Log.w(TAG, "In-call chat listener error — re-attaching (attempt "
                                + chatListenAttempts + ")", e);
                        if (chatMessageListener != null) {
                            chatMessageListener.remove();
                            chatMessageListener = null;
                        }
                        if (isFinishing() || isDestroyed()) return;
                        if (chatListenAttempts >= CHAT_LISTEN_MAX_ATTEMPTS) {
                            Log.e(TAG, "In-call chat listener gave up after "
                                    + chatListenAttempts + " attempts — no message banners");
                            return;
                        }
                        long delay = Math.min(600L * (1L << chatListenAttempts),
                                              CHAT_LISTEN_RETRY_CAP_MS);
                        chatListenAttempts++;
                        chatRetryHandler.postDelayed(this::listenForInCallMessages, delay);
                        return;
                    }
                    if (snapshots == null) return;
                    // A snapshot arrived, so the listener is healthy: reset the backoff so a
                    // later transient network drop gets its full retry budget again.
                    chatListenAttempts = 0;
                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;
                        String senderId = dc.getDocument().getString("senderId");
                        if (myUid.equals(senderId)) continue; // skip my own messages
                        String text = dc.getDocument().getString("text");
                        if (text == null || text.isEmpty()) continue;
                        // Set.add() returns false when the id was already present, so this
                        // is both the "already bannered" check and the record, in one step.
                        if (!seenChatMsgIds.add(dc.getDocument().getId())) continue;
                        String preview = text.length() > 48 ? text.substring(0, 48) + "…" : text;
                        runOnUiThread(() -> deliverNewMessageBanner(preview));
                    }
                });
    }

    /**
     * Decides where a partner message announcement goes.
     *
     * <p>The listener keeps running while this activity is in the background, so animating
     * the pill there burned the 4-second auto-dismiss window against a screen nobody could
     * see — the user came back to the call and had no idea a message had arrived. Three
     * cases:
     * <ul>
     *   <li><b>In-call chat open</b> — the message is already visible in the thread, so a
     *       banner would be noise. Skip it.</li>
     *   <li><b>Call screen hidden</b> (home button, another app) — hold the
     *       latest preview and show it from {@link #onResume()}.</li>
     *   <li><b>Call screen visible</b> — show it now.</li>
     * </ul>
     */
    private void deliverNewMessageBanner(String preview) {
        if (chatScreenOpen) return;
        if (!isActivityVisible || isInPictureInPictureMode()) { pendingBannerPreview = preview; return; }
        showNewMessageBanner(preview);
    }

    /**
     * Slides in the new-message pill banner at the top of the screen.
     * Auto-dismisses after 4 seconds. Tapping it opens the in-call chat.
     */
    private void showNewMessageBanner(String preview) {
        if (bannerNewMessage == null || tvNewMsgPreview == null) return;
        float slideY = 44f * getResources().getDisplayMetrics().density;
        tvNewMsgPreview.setText(preview);
        bannerNewMessage.setVisibility(View.VISIBLE);
        bannerNewMessage.setAlpha(0f);
        bannerNewMessage.setTranslationY(-slideY);
        bannerNewMessage.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .start();
        // Reset the auto-dismiss timer on each new message so rapid messages extend the window
        bannerDismissHandler.removeCallbacksAndMessages(null);
        bannerDismissHandler.postDelayed(() -> {
            if (bannerNewMessage != null) {
                bannerNewMessage.animate()
                        .alpha(0f)
                        .translationY(-slideY)
                        .setDuration(180)
                        .withEndAction(() -> {
                            if (bannerNewMessage != null)
                                bannerNewMessage.setVisibility(View.GONE);
                        })
                        .start();
            }
        }, 4_000);
    }

    // ── In-call chat ────────────────────────────────────────��─────────────────

    private void openInCallChat() {
        if (callId == null || myUid == null) {
            Toast.makeText(this, "Chat unavailable — call not yet established",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // While the thread is on screen the partner's messages are already visible in it,
        // so suppress the pill instead of stacking a banner behind the chat UI. Cleared in
        // onResume() when the user comes back to the call.
        chatScreenOpen       = true;
        pendingBannerPreview = null;
        // Opening chat is not "leaving the call": keep it out of PiP.
        suppressAutoPip      = true;
        updatePipParams();

        Intent intent = new Intent(this, InCallChatActivity.class);
        intent.putExtra(InCallChatActivity.EXTRA_CALL_ID,      callId);
        intent.putExtra(InCallChatActivity.EXTRA_MY_UID,       myUid);
        intent.putExtra(InCallChatActivity.EXTRA_PARTNER_NAME, partnerName);
        // Drives whether the chat screen floats the video PiP — there is nothing to show on a
        // voice-only call.
        intent.putExtra(InCallChatActivity.EXTRA_IS_VIDEO, isVideo);
        startActivity(intent);
    }

    // ── TURN quota warning ────────────────────────────────────────��───────────

    /**
     * Shows or updates the TURN quota warning banner.
     *
     * <ul>
     *   <li><b>Near limit (≥800 GB):</b> amber banner — user can dismiss it.</li>
     *   <li><b>Hard limit reached (≥900 GB):</b> red-tinted banner — TURN is disabled.</li>
     * </ul>
     */
    private void checkAndShowTurnWarning() {
        if (bannerTurnWarning == null || tvTurnWarningText == null) return;
        if (isInPictureInPictureMode()) return;
        TurnBandwidthTracker tracker = TurnBandwidthTracker.get(this);

        if (tracker.isLimitReached()) {
            tvTurnWarningText.setText(
                    "⚠\uFE0F Monthly TURN data cap reached (100 GB). "
                    + "This call uses direct connection only — it may not connect on mobile data or corporate networks.");
            bannerTurnWarning.setBackgroundColor(0xCC8B1A00);
            bannerTurnWarning.setVisibility(View.VISIBLE);
        } else if (tracker.isNearLimit()) {
            tvTurnWarningText.setText(
                    "⚠\uFE0F TURN relay usage is above 90 GB this month ("
                    + tracker.getSummary() + "). "
                    + "Calls may stop relaying near month end.");
            bannerTurnWarning.setBackgroundColor(0xCC7B2F00);
            bannerTurnWarning.setVisibility(View.VISIBLE);
        } else {
            bannerTurnWarning.setVisibility(View.GONE);
        }
    }

    // ── CallManager.CallListener ──────────────────────────────────────────────

    @Override
    public void onCallStateChanged(CallManager.CallState state) {
        // Cancel the no-answer watchdog as soon as the call leaves OUTGOING_RINGING —
        // whether the callee answers, declines, or the connection otherwise progresses.
        if (state != CallManager.CallState.OUTGOING_RINGING) {
            noAnswerHandler.removeCallbacks(noAnswerRunnable);
        }
        runOnUiThread(() -> {
            updateStatusUi(state);
            updatePipParams();
            // Refresh TURN banner on CONNECTED (relay may have been skipped due to cap).
            if (state == CallManager.CallState.CONNECTED) {
                checkAndShowTurnWarning();
            }
        });
    }


    @Override
    public void onRemoteVideoTrack(VideoTrack track) {
        runOnUiThread(() -> {
            remoteVideoView.setVisibility(View.VISIBLE);
            voiceOnlyBg.setVisibility(View.GONE);
            track.addSink(remoteVideoView);
        });
    }

    @Override
    public void onLocalVideoTrack(VideoTrack track) {
        runOnUiThread(() -> {
            if (isInPictureInPictureMode()) {
                pipSavedLocalVis = View.VISIBLE;   // shown when the window expands
            } else {
                localVideoPip.setVisibility(View.VISIBLE);
            }
            track.addSink(localVideoView);
        });
    }

    @Override
    public void onLocalMirrorChanged(boolean mirror) {
        runOnUiThread(() -> localVideoView.setMirror(mirror));
    }

    /**
     * Receives the shared call-start instant, already translated into this device's clock, and
     * starts the duration ticker. Because the anchor is absolute rather than "time since I saw
     * CONNECTED", both parties show the same value and a reconnect resumes at the correct
     * elapsed time instead of restarting from 00:00.
     */
    @Override
    public void onCallTimerAnchor(long callStartLocalMs) {
        runOnUiThread(() -> {
            callStartMs = callStartLocalMs;
            if (!durationTickScheduled) {
                durationTickScheduled = true;
                durationHandler.post(durationTick);
            }
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Log.e(TAG, "Call error: " + message);
        });
    }

    // ── Status UI ─────────────────────────────────────────────────────────────

    private void updateStatusUi(CallManager.CallState state) {
        String statusText;
        switch (state) {
            case OUTGOING_RINGING: statusText = isCaller ? "Ringing…" : "Connecting…"; break;
            case INCOMING_RINGING: statusText = "Incoming call…"; break;
            case CONNECTING:       statusText = "Connecting…"; break;
            case CONNECTED:
                // The timer is NOT started here: it waits for the shared server anchor in
                // onCallTimerAnchor() so both devices count from the same instant.
                statusText   = "";
                wasConnected = true;
                break;
            case ENDED:
                statusText = "Call ended";
                durationHandler.removeCallbacksAndMessages(null);
                durationTickScheduled = false;
                saveCallRecord(wasConnected ? CallRecord.OUTCOME_ANSWERED : CallRecord.OUTCOME_MISSED);
                finish();
                return;
            case FAILED:
                statusText = "Call failed — network unavailable";
                durationHandler.removeCallbacksAndMessages(null);
                durationTickScheduled = false;
                saveCallRecord(CallRecord.OUTCOME_FAILED);
                Toast.makeText(this, "Call failed — check your network", Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(this::finish, 2000);
                break;
            default: statusText = ""; break;
        }
        if (tvCallStatus        != null) tvCallStatus.setText(statusText);
        if (tvCallStatusOverlay != null) {
            tvCallStatusOverlay.setText(statusText);
            tvCallStatusOverlay.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ── Call history ──────────────────────────────────────────────────────────

    /**
     * Writes this call's history row.
     *
     * <p>Called from {@code updateStatusUi(ENDED)}, which calls {@code finish()} immediately.
     * It is guarded because an ENDED status can arrive from both the local hangup and the remote
     * status write.
     */
    private void saveCallRecord(String outcome) {
        if (partnerId == null || myUid == null) return;
        if (savedRecordId != null) return;
        long now         = System.currentTimeMillis();
        int  durationSec = callStartMs > 0 ? (int) ((now - callStartMs) / 1000) : 0;
        String direction = isCaller ? CallRecord.DIRECTION_OUTGOING : CallRecord.DIRECTION_INCOMING;

        CallRecord record = new CallRecord();
        record.id              = UUID.randomUUID().toString();
        record.partnerId       = partnerId;
        record.partnerName     = partnerName != null ? partnerName : partnerId;
        record.isVideo         = isVideo;
        record.direction       = direction;
        record.outcome         = outcome;
        record.startedAt       = callStartMs > 0 ? callStartMs : now;
        record.durationSeconds = durationSec;
        savedRecordId          = record.id;

        Context appCtx = getApplicationContext();
        historyExecutor.execute(() -> {
            AppDatabase.getInstance(appCtx).callHistoryDao().insert(record);
        });
    }

    // ── Foreground service lifecycle ──────────────────────────────────────────

    /**
     * Starts {@link CallForegroundService}, which shows the ongoing call notification and
     * keeps the process alive when the user presses Home during a call.
     */
    private void startForegroundCallService() {
        Intent svcIntent = new Intent(this, CallForegroundService.class);
        svcIntent.setAction(CallForegroundService.ACTION_START);
        svcIntent.putExtra(CallForegroundService.EXTRA_PARTNER_NAME, partnerName);
        svcIntent.putExtra(CallForegroundService.EXTRA_CALL_ID, callId);
        svcIntent.putExtra(CallForegroundService.EXTRA_IS_VIDEO, isVideo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
    }

    /** Stops {@link CallForegroundService} — removes the ongoing notification. */
    private void stopForegroundCallService() {
        Intent svcIntent = new Intent(this, CallForegroundService.class);
        svcIntent.setAction(CallForegroundService.ACTION_STOP);
        startService(svcIntent);
    }

    // ── Proximity wake lock (screen off when held to ear) ─────────────────────

    /**
     * Acquires {@code PROXIMITY_SCREEN_OFF_WAKE_LOCK} for voice calls, which makes the OS
     * automatically turn the screen off when the proximity sensor detects a nearby object
     * (e.g. the user's cheek).  This prevents accidental touches and saves battery —
     * the same mechanism used by WhatsApp, Signal, and the stock Phone app.
     *
     * <p>Not used for video calls — the user needs to see the screen.
     */
    private void acquireProximityWakeLock() {
        if (isVideo) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "duoshield:proximity_call");
            proximityWakeLock.setReferenceCounted(false);
            proximityWakeLock.acquire();
            Log.d(TAG, "Proximity wake lock acquired");
        }
    }

    /**
     * Releases the proximity wake lock.
     *
     * <p>Passes {@code PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY} (flag value 1) so
     * the screen only turns back on once the sensor reports "far" — prevents a brief
     * screen flash if the phone is still against the user's ear when the call ends.
     */
    private void releaseProximityWakeLock() {
        if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
            proximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            proximityWakeLock = null;
            Log.d(TAG, "Proximity wake lock released");
        }
    }

    // ── Renderer release ──────────────────────────────────────────────────────

    private void releaseVideoRenderers() {
        try {
            if (remoteVideoView != null) remoteVideoView.release();
            if (localVideoView  != null) localVideoView.release();
        } catch (Exception ignored) { }
    }
}
