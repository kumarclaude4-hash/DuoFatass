package com.duoshield.app.call;

import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.R;
import com.duoshield.app.util.DevicePerformanceTier;

import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ephemeral in-call chat screen.
 *
 * <p>Messages are stored in {@code calls/{callId}/chat} and are <strong>not</strong> persisted
 * to Room — they disappear when the call document is swept by the scheduled self-destruct
 * Cloud Function. This mirrors the "Messages won't be saved when the call ends" notice shown
 * in the UI (matching the design reference).
 *
 * <p>Uses {@link AppCompatActivity} (not BaseActivity) because:
 * <ul>
 *   <li>The user is already authenticated — they passed the full call setup flow.</li>
 *   <li>Messages are ephemeral; there is no persistent sensitive data at risk.</li>
 *   <li>Triggering the app-lock redirect mid-call would disrupt an active call.</li>
 * </ul>
 */
public class InCallChatActivity extends AppCompatActivity {

    private static final String TAG = "InCallChatActivity";

    /** Intent extras — set by CallActivity when opening this screen. */
    public static final String EXTRA_CALL_ID      = "incall_call_id";
    public static final String EXTRA_MY_UID       = "incall_my_uid";
    public static final String EXTRA_PARTNER_NAME = "incall_partner_name";
    /** False for voice-only calls, where there is no video to float. */
    public static final String EXTRA_IS_VIDEO     = "incall_is_video";

    private String callId;
    private String myUid;
    private String partnerName;
    private boolean isVideo;

    private RecyclerView rvMessages;
    private EditText     etMessage;

    // ── Floating call PiP ─────────────────────────────────────────────────────
    private FrameLayout         pipContainer;
    private SurfaceViewRenderer pipVideoView;      // local participant, left half
    private SurfaceViewRenderer pipSelfVideoView;  // remote participant, right half
    private ImageView           ivPipMuteBadge;
    private TextView            tvPipLabel;
    private TextView            tvPipPartnerLabel;

    // ── System Picture-in-Picture (Home from this screen on a video call) ─────
    private View                    chatColumn;
    private View                    pipLocalContainer;
    private FrameLayout.LayoutParams savedPipLp;
    private float                   savedPipTx, savedPipTy, savedPipElevation;
    private boolean                 lastMutedForPip = false;
    /** Counted by AppLockManager while visible (see onStart) — keeps the lock timer honest. */
    private boolean                 countsForLock   = false;

    /** True once {@link SurfaceViewRenderer#init} ran — guards a double release(). */
    private boolean pipInitialised = false;
    /**
     * The tracks each renderer is currently a sink of.
     *
     * <p>Tracked explicitly because they must be detached again in {@link #onPause()} /
     * {@link #onDestroy()}: attaching a second sink to a track that {@code CallActivity} is
     * already rendering is fine, but leaving it attached after this screen goes away leaks the
     * renderer and can crash libwebrtc when the track is disposed at the end of the call.
     */
    private VideoTrack pipMainTrack;
    private VideoTrack pipSelfTrack;

    /**
     * Repaint loop for the PiP's non-push state.
     *
     * <p>{@link CallManager} publishes tracks and the mic-mute flag but has no listener API a
     * secondary screen can subscribe to, and the remote track often arrives after this screen
     * is already open. A 1 s poll while resumed is cheap and avoids adding a second listener
     * path through the call session.
     */
    private final android.os.Handler pipHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pipRefresh = new Runnable() {
        @Override public void run() {
            if (isInPictureInPictureMode() && !CallSession.isLive()) {
                finish();                      // call ended while floating — close the window
                return;
            }
            syncPip();
            CallSession s = CallSession.current();
            if (s != null && s.isMuted() != lastMutedForPip) updatePipParams();
            pipHandler.postDelayed(this, 1_000L);
        }
    };

    private final List<InCallChatMessage> messages = new ArrayList<>();
    private InCallChatAdapter adapter;
    private ListenerRegistration chatListener;

    /** Backoff state for re-attaching a listener that Firestore rejected (see listenForMessages). */
    private final android.os.Handler retryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int listenAttempts = 0;
    private static final int MAX_LISTEN_ATTEMPTS = 8;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incall_chat);

        callId      = getIntent().getStringExtra(EXTRA_CALL_ID);
        myUid       = getIntent().getStringExtra(EXTRA_MY_UID);
        partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        isVideo     = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);
        if (partnerName == null) partnerName = "Unknown";

        if (callId == null || myUid == null) {
            Log.e(TAG, "Missing callId or myUid — closing in-call chat");
            finish();
            return;
        }

        bindViews();
        initPip();
        chatColumn        = findViewById(R.id.chatColumn);
        pipLocalContainer = findViewById(R.id.pipLocalContainer);
        listenForMessages();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // This screen sits inside the call task and is not a BaseActivity. Without counting it,
        // opening the chat would drop the foreground count to zero and start the lock timer
        // while the user is looking at the screen. Skipped when a background timestamp is already
        // pending, so an older timestamp is never overwritten (same rule as CallActivity).
        countsForLock = !com.duoshield.app.util.AppLockManager.hasBackgroundTimestamp(this);
        if (countsForLock) com.duoshield.app.util.AppLockManager.onActivityStarted();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (countsForLock) {
            countsForLock = false;
            com.duoshield.app.util.AppLockManager.onActivityStopped(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Attach the sink only while visible: a backgrounded renderer would keep decoding
        // frames into a surface nobody can see.
        syncPip();
        updatePipParams();
        pipHandler.removeCallbacks(pipRefresh);
        pipHandler.postDelayed(pipRefresh, 1_000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pipHandler.removeCallbacks(pipRefresh);
        // In the system PiP window the activity is paused but its video must keep playing.
        if (!isInPictureInPictureMode()) detachPipSink();
    }

    // ── System Picture-in-Picture ─────────────────────────────────────────────

    private boolean canUsePip() {
        return isVideo && pipInitialised && CallPip.isSupported(this);
    }

    private PictureInPictureParams buildPipParams() {
        CallSession s = CallSession.current();
        boolean muted = s != null && s.isMuted();
        lastMutedForPip = muted;
        return CallPip.params(this, muted, CallSession.isLive(), null);
    }

    /** Pushes fresh params (auto-enter on Android 12+, mute icon on the window's action). */
    private void updatePipParams() {
        if (!canUsePip() || isFinishing() || isDestroyed()) return;
        try {
            setPictureInPictureParams(buildPipParams());
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "setPictureInPictureParams failed", e);
        }
    }

    /**
     * Home from the chat on a live video call: float the remote video, like the call screen.
     * Android 12+ does this itself through auto-enter; 8–11 enter here.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                && canUsePip() && CallSession.isLive() && !isInPictureInPictureMode()) {
            try {
                enterPictureInPictureMode(buildPipParams());
            } catch (IllegalStateException | IllegalArgumentException e) {
                Log.w(TAG, "enterPictureInPictureMode failed", e);
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        applyPipUi(inPip);
    }

    /**
     * In the system window only the partner's video is shown: the chat column is hidden and the
     * floating call tile is stretched over the whole window with the local half removed. All of
     * it is put back when the window expands.
     */
    private void applyPipUi(boolean inPip) {
        if (pipContainer == null) return;
        if (inPip) {
            // Keyboard would otherwise cover the little window.
            View focus = getCurrentFocus();
            if (focus != null) {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
            ViewGroup.LayoutParams cur = pipContainer.getLayoutParams();
            if (cur instanceof FrameLayout.LayoutParams) {
                savedPipLp        = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) cur);
                savedPipTx        = pipContainer.getTranslationX();
                savedPipTy        = pipContainer.getTranslationY();
                savedPipElevation = pipContainer.getElevation();
                FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                pipContainer.setLayoutParams(fill);
            }
            pipContainer.setTranslationX(0f);
            pipContainer.setTranslationY(0f);
            pipContainer.setElevation(0f);
            if (chatColumn != null)        chatColumn.setVisibility(View.GONE);
            if (pipLocalContainer != null) pipLocalContainer.setVisibility(View.GONE);
            syncPip();                                   // make sure the remote sink is attached
            pipHandler.removeCallbacks(pipRefresh);
            pipHandler.postDelayed(pipRefresh, 1_000L);  // notice the call ending while floating
        } else {
            if (savedPipLp != null) {
                pipContainer.setLayoutParams(savedPipLp);
                pipContainer.setTranslationX(savedPipTx);
                pipContainer.setTranslationY(savedPipTy);
                pipContainer.setElevation(savedPipElevation);
                savedPipLp = null;
            }
            if (chatColumn != null)        chatColumn.setVisibility(View.VISIBLE);
            if (pipLocalContainer != null) pipLocalContainer.setVisibility(View.VISIBLE);
            syncPip();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pipHandler.removeCallbacksAndMessages(null);
        retryHandler.removeCallbacksAndMessages(null);
        if (chatListener != null) { chatListener.remove(); chatListener = null; }

        // Order matters: drop the sink from the (still live) track before releasing the
        // renderer, otherwise repeatedly opening and closing this screen leaks renderers and
        // can crash the GL thread in libwebrtc.
        detachPipSink();
        if (pipInitialised) {
            if (pipVideoView     != null) pipVideoView.release();
            if (pipSelfVideoView != null) pipSelfVideoView.release();
            pipInitialised = false;
        }
    }

    // ── View setup ────────────────────────────────────────────────────────────

    private void bindViews() {
        rvMessages = findViewById(R.id.rvInCallMessages);
        etMessage  = findViewById(R.id.etInCallMessage);

        // RecyclerView — stack from end so newest messages appear at the bottom
        adapter = new InCallChatAdapter(messages, partnerName);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        DevicePerformanceTier tier = DevicePerformanceTier.effectiveTier(this);
        llm.setInitialPrefetchItemCount(tier.recyclerViewPrefetchCount());
        rvMessages.setLayoutManager(llm);
        rvMessages.setHasFixedSize(true);
        rvMessages.setItemViewCacheSize(tier.recyclerViewCacheSize());
        rvMessages.setAdapter(adapter);
        if (rvMessages.getItemAnimator() != null) {
            rvMessages.getItemAnimator().setChangeDuration(0L);
        }

        // Header navigation
        View btnMinimize = findViewById(R.id.btnMinimizeChat);
        View btnClose    = findViewById(R.id.btnCloseChat);
        if (btnMinimize != null) btnMinimize.setOnClickListener(v -> finish());
        if (btnClose    != null) btnClose.setOnClickListener(v -> finish());

        // Emoji button — opens the soft keyboard so the user can switch to emoji panel
        ImageView btnEmoji = findViewById(R.id.btnEmoji);
        if (btnEmoji != null) {
            btnEmoji.setOnClickListener(v -> {
                etMessage.requestFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }

        // Send button
        ImageView btnSend = findViewById(R.id.btnSendInCall);
        if (btnSend != null) btnSend.setOnClickListener(v -> sendMessage());

        // IME "Send" action key
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    // ── Floating call PiP ─────────────────────────────────────────────────────

    /**
     * Prepares the floating video window.
     *
     * <p>This screen is a separate activity from {@link CallActivity}, so it cannot own a
     * camera or a peer connection of its own — starting a second capture would fight the live
     * call for the camera. Instead it borrows the running session's {@link EglBase} and adds an
     * extra sink to the tracks {@link CallManager} already owns, reached through
     * {@link CallManager#getActive()}.
     *
     * <p>The PiP stays hidden for voice-only calls and whenever no call session is reachable.
     */
    private void initPip() {
        pipContainer        = findViewById(R.id.callPipContainer);
        pipVideoView        = findViewById(R.id.pipVideoView);
        pipSelfVideoView    = findViewById(R.id.pipSelfVideoView);
        ivPipMuteBadge      = findViewById(R.id.ivPipMuteBadge);
        tvPipLabel          = findViewById(R.id.tvPipLabel);
        tvPipPartnerLabel   = findViewById(R.id.tvPipPartnerLabel);
        if (pipContainer == null || pipVideoView == null || pipSelfVideoView == null) return;

        if (!isVideo) {
            // Voice-only call — nothing to float.
            pipContainer.setVisibility(View.GONE);
            return;
        }

        CallManager call = CallManager.getActive();
        EglBase egl = call != null ? call.getEglBase() : null;
        if (egl == null) {
            // No live session (e.g. the call ended while this screen was opening).
            pipContainer.setVisibility(View.GONE);
            return;
        }

        pipVideoView.init(egl.getEglBaseContext(), null);
        pipVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        pipVideoView.setEnableHardwareScaler(true);

        pipSelfVideoView.init(egl.getEglBaseContext(), null);
        pipSelfVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        pipSelfVideoView.setEnableHardwareScaler(true);
        pipInitialised = true;

        if (tvPipLabel != null) tvPipLabel.setText("You");
        if (tvPipPartnerLabel != null) tvPipPartnerLabel.setText(partnerName);

        setupPipGestures();
    }

    /**
     * Drag the complete 1:1 tile to reposition it.
     *
     * <p>Free-float rather than corner-snapping: this window shares the screen with a message
     * list and an input row, so the user needs to be able to park it anywhere that is not
     * covering the text they are reading.
     */
    private void setupPipGestures() {
        final float[]   downX  = {0f};
        final float[]   downY  = {0f};
        final float[]   offX   = {0f};
        final float[]   offY   = {0f};
        final boolean[] dragged = {false};
        final float threshold = 8f * getResources().getDisplayMetrics().density;

        pipContainer.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    offX[0]    = v.getX() - event.getRawX();
                    offY[0]    = v.getY() - event.getRawY();
                    downX[0]   = event.getRawX();
                    downY[0]   = event.getRawY();
                    dragged[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (!dragged[0]
                            && (Math.abs(event.getRawX() - downX[0]) > threshold
                             || Math.abs(event.getRawY() - downY[0]) > threshold)) {
                        dragged[0] = true;
                    }
                    if (dragged[0]) {
                        View parent = (View) v.getParent();
                        v.setX(Math.max(0, Math.min(event.getRawX() + offX[0],
                                parent.getWidth() - v.getWidth())));
                        v.setY(Math.max(0, Math.min(event.getRawY() + offY[0],
                                parent.getHeight() - v.getHeight())));
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        });
    }

    /**
     * Points each renderer at its fixed participant and refreshes the overlays.
     *
     * <p>Called on resume and once a second: the remote track usually arrives after this screen
     * is already open, and the mic-mute badge has to track the call's state without a listener.
     * When one track is unavailable its half remains dark, preserving the strict 1:1 layout.
     */
    private void syncPip() {
        if (!pipInitialised || pipContainer == null) return;

        CallManager call = CallManager.getActive();
        if (call == null) {
            // Call ended while the chat was open — stop rendering tracks that are about to be
            // disposed rather than leaving a frozen frame on screen.
            detachPipSink();
            pipContainer.setVisibility(View.GONE);
            return;
        }

        VideoTrack local  = call.getLocalVideoTrack();
        VideoTrack remote = call.getRemoteVideoTrack();
        if (local == null && remote == null) {
            detachPipSink();
            pipContainer.setVisibility(View.GONE);
            return;
        }

        if (!attachPipSink(true, local)) return;
        attachPipSink(false, remote);

        // Role never changes: local is mirrored on the left, partner is unmirrored on the right.
        pipVideoView.setMirror(true);
        pipSelfVideoView.setMirror(false);
        pipContainer.setVisibility(View.VISIBLE);

        if (tvPipLabel != null) tvPipLabel.setText("You");
        if (tvPipPartnerLabel != null) tvPipPartnerLabel.setText(partnerName);
        if (ivPipMuteBadge != null) {
            ivPipMuteBadge.setVisibility(local != null && call.isMicMuted()
                    ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Makes one renderer the sink of {@code target}, detaching whatever it rendered before.
     *
     * @param main true for the large tile, false for the inset
     * @return false only when the large tile could not be attached, i.e. the window was hidden
     */
    private boolean attachPipSink(boolean main, VideoTrack target) {
        SurfaceViewRenderer view = main ? pipVideoView : pipSelfVideoView;
        VideoTrack current       = main ? pipMainTrack : pipSelfTrack;
        if (current == target) return true;

        if (current != null) {
            try {
                current.removeSink(view);
            } catch (Exception e) {
                // The track may already be disposed if the call ended first — nothing to undo.
                Log.w(TAG, "PiP removeSink failed", e);
            }
        }
        if (main) pipMainTrack = null; else pipSelfTrack = null;

        if (target == null) return true;
        try {
            target.addSink(view);
            if (main) pipMainTrack = target; else pipSelfTrack = target;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "PiP addSink failed", e);
            if (main) pipContainer.setVisibility(View.GONE);
            return false;
        }
    }

    /** Detaches both renderers from whatever they render. Safe to call repeatedly. */
    private void detachPipSink() {
        attachPipSink(true, null);
        attachPipSink(false, null);
    }

    // ── Firestore ─────────────────────────────────────────────────────────────

    private void sendMessage() {
        if (etMessage == null) return;
        String text = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        etMessage.setText("");

        Map<String, Object> doc = new HashMap<>();
        doc.put("senderId", myUid);
        doc.put("text", text);
        doc.put("ts", System.currentTimeMillis());

        FirebaseFirestore.getInstance()
                .collection("calls").document(callId)
                .collection("chat")
                .add(doc)
                .addOnFailureListener(e -> Log.w(TAG, "send failed", e));
    }

    private void listenForMessages() {
        if (chatListener != null) return;
        chatListener = FirebaseFirestore.getInstance()
                .collection("calls").document(callId)
                .collection("chat")
                .orderBy("ts", Query.Direction.ASCENDING)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        // A rejected listener is dead for good in Firestore. Opening this
                        // screen in the first moment of a call can race the creation of the
                        // parent calls/{callId} document that the security rule resolves,
                        // which used to leave the thread permanently empty on that device.
                        // Drop the dead registration and re-attach with backoff.
                        Log.w(TAG, "listener error — re-attaching (attempt "
                                + listenAttempts + ")", e);
                        if (chatListener != null) { chatListener.remove(); chatListener = null; }
                        if (isFinishing() || isDestroyed()) return;
                        if (listenAttempts >= MAX_LISTEN_ATTEMPTS) {
                            Log.e(TAG, "in-call chat listener gave up after "
                                    + listenAttempts + " attempts");
                            return;
                        }
                        long delay = Math.min(600L * (1L << listenAttempts), 4_000L);
                        listenAttempts++;
                        retryHandler.postDelayed(this::listenForMessages, delay);
                        return;
                    }
                    if (snapshots == null) return;
                    listenAttempts = 0;

                    List<InCallChatMessage> updated = new ArrayList<>();
                    for (DocumentSnapshot ds : snapshots.getDocuments()) {
                        String senderId = ds.getString("senderId");
                        String text     = ds.getString("text");
                        Long   ts       = ds.getLong("ts");
                        if (TextUtils.isEmpty(text) || senderId == null) continue;
                        updated.add(new InCallChatMessage(
                                ds.getId(), senderId, text,
                                ts != null ? ts : 0L,
                                senderId.equals(myUid)));
                    }

                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        adapter.submitMessages(updated);
                        if (!messages.isEmpty()) {
                            rvMessages.scrollToPosition(messages.size() - 1);
                        }
                    });
                });
    }
}
