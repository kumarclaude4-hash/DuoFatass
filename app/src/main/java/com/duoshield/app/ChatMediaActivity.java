package com.duoshield.app;

import android.Manifest;
import com.duoshield.app.util.SelfDestructScheduler;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.duoshield.app.crypto.signal.DuoShieldSignalStore;
import com.duoshield.app.crypto.signal.SignalCipherHelper;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.crypto.signal.SignalSessionManager;
import com.duoshield.app.db.AppDatabase;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import com.duoshield.app.models.Message;
// Biometric unlock removed — lock handled entirely by BaseActivity via PIN
import com.duoshield.app.ui.MessageAdapter;
import com.duoshield.app.ui.SettingsActivity;
import com.duoshield.app.ui.SwipeToReplyCallback;
import com.duoshield.app.ui.WaveformView;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.ConversationMetaUpdater;
import com.duoshield.app.util.DeliveryReceiptHelper;
import com.duoshield.app.util.FirebaseCostGuard;
import com.duoshield.app.util.SecurePrefs;
import com.duoshield.app.util.VoiceMessagePlayer;
import com.duoshield.app.util.VoiceRecorderHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.android.material.snackbar.Snackbar;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.util.EditMessageHelper;
import com.duoshield.app.util.ForwardMessageHelper;
import com.duoshield.app.util.B2StorageHelper;

import com.duoshield.app.util.PresenceThrottle;
import com.duoshield.app.util.ButtonPressAnimator;
import com.duoshield.app.util.HapticHelper;
import android.content.ClipData;
import android.content.ClipboardManager;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class ChatMediaActivity extends BaseActivity {

    private static final String TAG                  = "ChatMediaActivity";
    private static final int    MAX_PINS             = 3;
    private static final int    REQUEST_RECORD_AUDIO    = 201;
    private static final int    REQUEST_CALL_VOICE      = 202;
    private static final int    REQUEST_CALL_VIDEO      = 203;
    private static final int    REQUEST_CAMERA_PHOTO    = 204;
    /**
     * Files above this threshold are encrypted and uploaded via a streaming path
     * ({@link B2StorageHelper#encryptUriToFile} + {@link B2StorageHelper#uploadFileFromDisk})
     * so the full plaintext and ciphertext are never loaded into a single byte[].
     * Files at or below the threshold use the existing in-memory path.
     */
    private static final long   LARGE_FILE_THRESHOLD    = 50 * 1024 * 1024L; // 50 MB
    private boolean             pendingCallIsVideo      = false;
    /** URI of the temp file created for a camera capture — consumed after TakePicture returns. */
    private Uri                 cameraPhotoUri          = null;

    // Typing debounce
    private PresenceThrottle typingThrottle;

    // Disappearing messages timer
    private final Handler disappearHandler = new Handler(Looper.getMainLooper());
    private final Runnable disappearTicker = new Runnable() {
        @Override
        public void run() {
            removeExpiredMessages();
            disappearHandler.postDelayed(this, 1000);
        }
    };

    private void removeExpiredMessages() {
        long now = System.currentTimeMillis();
        List<Message> msgs = adapter.getMessages();
        List<String> toRemove = new ArrayList<>();
        for (Message m : msgs) {
            if (m.getExpiresAt() > 0 && now > m.getExpiresAt()) {
                toRemove.add(m.getId());
            }
        }
        for (String id : toRemove) {
            adapter.removeMessage(id);
        }
    }

    // Reply state
    private String pendingReplyId      = null;
    private String pendingReplyPreview = null;

    // Pinned messages
    private List<Map<String, Object>> pinnedList    = new ArrayList<>();
    private int                       pinnedViewIdx = 0;

    private FirebaseFirestore db;

    // Header views
    private ImageView    ivPartnerAvatar;
    private TextView     tvAvatarInitial, tvPartnerName, tvOnlineStatus;
    private View         headerOnlineDot;
    private String       currentPartnerPhotoUrl = null;

    // Chat views
    private EditText     messageInput;
    private ImageView    sendButton, uploadButton, micButton, btnCameraInline;
    private ProgressBar  uploadProgress;
    private View         uploadProgressContainer;
    private android.widget.TextView tvUploadPct;
    private ImageView    ivUploadThumb;
    private View         uploadThumbDim, uploadPlainBg;
    // Multi-media album upload state
    private final Object multiUploadLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger pendingMultiCompleted =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.List<String[]> pendingMultiItems = new java.util.ArrayList<>();
    private int    pendingMultiTotal   = 0;
    private String pendingMultiCaption = null;
    // Emoji keyboard
    private com.duoshield.app.ui.EmojiKeyboardHelper emojiHelper;
    private RecyclerView recyclerView;
    private LinearLayout typingIndicatorRow;
    private TextView     typingIndicator;
    private View         replyPreviewBar;
    private TextView     replyPreviewBarText;
    private ImageView    cancelReplyBtn;
    private View         btnScrollToBottom;
    /** Wrapper holding the FAB + its unread badge; animated as one unit. */
    private View         scrollToBottomContainer;
    private TextView     tvUnreadBadge;
    private TextView     stickyDatePill;
    /**
     * Count of messages that arrived while the user was scrolled away from the bottom.
     * Distinct from the conversation's unread count in the chat list: this only tracks
     * "arrived behind your current viewport in this session", and resets to 0 the moment
     * the user returns to the bottom or taps the FAB.
     */
    private int          unreadWhileScrolledUp = 0;
    /** Last label rendered into the sticky pill; avoids re-setting identical text per frame. */
    private String       stickyDateLabelShown  = null;

    // Pinned banner
    private LinearLayout pinnedBanner;
    private TextView     pinnedText, pinnedCount;
    private ImageView    pinnedCloseBtn;
    private LinearLayout disappearTimerBanner;
    private TextView     tvDisappearTimer;
    // Safety-number-changed banner (shown when partner's Signal identity key rotates)
    private LinearLayout safetyNumberBanner;

    private static final long[]   DISAPPEAR_OPTS_MS    = {0, 5_000L, 30_000L, 60_000L, 300_000L, 3_600_000L, 86_400_000L, 604_800_000L};
    private static final String[] DISAPPEAR_OPTS_LBL   = {"Off", "5 seconds", "30 seconds", "1 minute", "5 minutes", "1 hour", "1 day", "1 week"};
    private static final String[] DISAPPEAR_OPTS_EMOJI  = {"🚫", "��", "⏱", "1️⃣", "5️⃣", "🕐", "📅", "📆"};
    private static final String   DESTRUCT_WORK_TAG  = "self_destruct_work";

    // Voice recording
    private View         voiceRecordingBar;
    private WaveformView recordingWaveform;
    private TextView     recordingTimer;
    private ImageView    cancelRecordingBtn, stopRecordingBtn;

    private final VoiceRecorderHelper recorder = new VoiceRecorderHelper();
    private final VoiceMessagePlayer  player   = new VoiceMessagePlayer();
    /** Last decrypted voice-note temp file — deleted when a new note starts or activity destroys. */
    private File currentVoiceTmpFile = null;
    private final Handler recordingTimerHandler = new Handler(Looper.getMainLooper());
    private int    recordingSeconds    = 0;
    private String currentlyPlayingId = null;
    private String pausedMessageId    = null;
    /** Backing state for MessageAdapter.OnVoicePlayListener#getPlaybackProgress/ElapsedMs —
     * lets a row that gets rebound mid-playback (scrolled off/on screen, or a rebind
     * triggered by an unrelated new message) seed itself correctly instead of resetting
     * to a static 0/blank state that never updates again. */
    private float  currentPlaybackFraction   = 0f;
    private int    currentPlaybackElapsedMs  = 0;

    private MessageAdapter adapter;
    private String conversationId;
    private String myUid;
    private String partnerUid;
    private String pendingImageCaption = null;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ListenerRegistration  msgListener;
    private ListenerRegistration  convListener;

    // Bug 1 & 5 fix: keyPending prevents listenForMessages() from starting before
    // the ECDH derivation completes. Without this guard the Firestore watchdog (4 s)
    // could start the listener with the OLD (or absent) AES key; then when derivation
    // finished the second listenForMessages() call was a no-op (msgListener != null),
    // leaving historical messages shown with the wrong key indefinitely.
    private volatile boolean keyPending = false;

    // §3.4 fix: messages that arrive before the ECDH shared key is ready are kept here
    // (raw ciphertext, isEncrypted=true) until retryPendingDecryption() is called once
    // key derivation completes. Shown as placeholders in the UI but NOT saved to Room.
    private final List<Message>   pendingDecryptQueue   = new ArrayList<>();
    // Tracks the Signal sigType (WHISPER_TYPE=2 / PREKEY_TYPE=3) for each queued message.
    // Messages without an entry (legacy ECDH) default to 0.
    private final Map<String, Integer> queuedSigTypes = new HashMap<>();
    // Guards a single re-derive attempt when ECDH decryption fails with a non-null (wrong) key.
    private boolean decryptRetryScheduled = false;
    // F-07: O(1) duplicate guard replaces the O(n) for-loop inside the snapshot listener.
    private final Set<String>     knownIds              = new java.util.HashSet<>();
    // F-07: latest message timestamp we have locally; used for Firestore startAfter().
    private long                  latestKnownTimestamp  = 0;
    // Tracks the last "last_read_<partnerUid>" timestamp seen from the conv doc.
    // Used to gate retroactive blue-tick updates so we only fire when the field changes.
    private long                  lastPartnerReadMs     = 0;
    // Prevents the Room seed from running twice when listenForMessages() is called
    // before the first dbExecutor task completes (e.g. onEstablished fires twice).
    private final java.util.concurrent.atomic.AtomicBoolean seeded = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final ActivityResultLauncher<Intent> mediaSendPreviewLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String uriStr  = result.getData().getStringExtra(MediaSendPreviewActivity.EXTRA_URI);
                java.util.ArrayList<String> uriStrings =
                        result.getData().getStringArrayListExtra(MediaSendPreviewActivity.EXTRA_URIS);
                String mediaType = result.getData().getStringExtra(
                        MediaSendPreviewActivity.EXTRA_MEDIA_TYPE);
                String caption = result.getData().getStringExtra(MediaSendPreviewActivity.EXTRA_CAPTION);
                if (uriStr != null) {
                    if (mediaType == null || mediaType.isEmpty()) mediaType = "image";
                    if (uriStrings != null && uriStrings.size() > 1) {
                        startAlbumUpload(
                                new java.util.ArrayList<>(toUris(uriStrings)),
                                mediaType,
                                (caption != null && !caption.isEmpty()) ? caption : null);
                    } else {
                        pendingImageCaption = (caption != null && !caption.isEmpty()) ? caption : null;
                        uploadMedia(Uri.parse(uriStr), mediaType);
                    }
                }
            }
        });

    /** Multi-select image picker — shows preview for single picks; groups multiples as album. */
    private final ActivityResultLauncher<String> pickImageLauncher =
        registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
            if (uris == null || uris.isEmpty()) return;
            if (uris.size() == 1) {
                // Single pick: show caption/crop preview before uploading.
                Intent preview = new Intent(ChatMediaActivity.this, MediaSendPreviewActivity.class);
                preview.putExtra(MediaSendPreviewActivity.EXTRA_URI, uris.get(0).toString());
                preview.putExtra(MediaSendPreviewActivity.EXTRA_MEDIA_TYPE, "image");
                mediaSendPreviewLauncher.launch(preview);
            } else {
                // Multiple picks: group as a single album message.
                launchMediaPreview(new java.util.ArrayList<>(uris), "image");
            }
        });

    /** Multi-select video picker — previews media and keeps the caption with the send. */
    private final ActivityResultLauncher<String> pickVideoLauncher =
        registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
            if (uris == null || uris.isEmpty()) return;
            launchMediaPreview(new java.util.ArrayList<>(uris), "video");
        });

    /** Camera still-capture — photo saved to a FileProvider URI, then sent through preview. */
    private final ActivityResultLauncher<Uri> takePictureLauncher =
        registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success != null && success && cameraPhotoUri != null) {
                Intent preview = new Intent(ChatMediaActivity.this, MediaSendPreviewActivity.class);
                preview.putExtra(MediaSendPreviewActivity.EXTRA_URI, cameraPhotoUri.toString());
                preview.putExtra(MediaSendPreviewActivity.EXTRA_MEDIA_TYPE, "image");
                mediaSendPreviewLauncher.launch(preview);
            }
        });

    private final ActivityResultLauncher<Intent> searchLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String msgId = result.getData().getStringExtra(MessageSearchActivity.EXTRA_MSG_ID);
                if (msgId != null) scrollToAndHighlight(msgId);
            }
        });

    private final ActivityResultLauncher<String> pickWallpaperLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit()
                    .putString("wallpaper_type", "image")
                    .putString("wallpaper_uri", uri.toString()).apply();
                applyWallpaper();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        // Lock-screen redirect is handled by BaseActivity.onResume() →
        // AppLockManager.shouldLock(). Calling BiometricHelper here caused
        // a silent finish() when no biometrics were enrolled.
        setupChat();
    }

    private void setupChat() {
        setContentView(R.layout.activity_chat_media);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // Read from Intent extras first (new multi-contact model).
        // Fall back to SharedPrefs for back-compat with existing single-conversation users.
        conversationId = getIntent().getStringExtra("conversation_id");
        partnerUid     = getIntent().getStringExtra("partner_uid");
        if (conversationId == null) {
            conversationId = prefs.getString("conversation_id", null);
            partnerUid     = prefs.getString("partner_uid", null);
        }
        // F22 fix: persist partner_uid so KeyFingerprintActivity (and ConversationListActivity
        // global menu) can resolve the most-recently-chatted partner when no Intent extra is
        // available (e.g. launched from overflow menu rather than from a chat banner).
        if (partnerUid != null) {
            prefs.edit().putString("partner_uid", partnerUid).apply();
        }

        myUid = prefs.getString("my_uid", null);
        if (myUid == null) {
            com.google.firebase.auth.FirebaseUser fu = FirebaseAuth.getInstance().getCurrentUser();
            if (fu != null) myUid = fu.getUid();
        }

        if (conversationId == null) {
            Toast.makeText(this, "No active conversation. Please add a contact first.", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        if (partnerUid == null) {
            Toast.makeText(this, "Could not identify contact. Please open the chat from your contact list.", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        if (myUid == null) {
            Toast.makeText(this, "Authentication error. Please sign in again.", Toast.LENGTH_LONG).show();
            finish(); return;
        }

        // ── Header ──────────────────────────────────────────────────
        ivPartnerAvatar  = findViewById(R.id.ivPartnerAvatar);
        tvAvatarInitial  = findViewById(R.id.tvAvatarInitial);
        ivPartnerAvatar.setOnClickListener(v -> openPartnerPhotoViewer());
        tvPartnerName    = findViewById(R.id.tvPartnerName);
        tvOnlineStatus   = findViewById(R.id.tvOnlineStatus);
        headerOnlineDot  = findViewById(R.id.headerOnlineDot);

        ImageView btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());

        // ── Call buttons ─────────────────────────────────────────────────────
        ImageView btnVoiceCall = findViewById(R.id.btnVoiceCall);
        if (btnVoiceCall != null) btnVoiceCall.setOnClickListener(v -> requestCallPermissions(false));

        ImageView btnVideoCall = findViewById(R.id.btnVideoCall);
        if (btnVideoCall != null) btnVideoCall.setOnClickListener(v -> requestCallPermissions(true));

        ImageView btnOverflow = findViewById(R.id.btnOverflow);
        if (btnOverflow != null) btnOverflow.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Settings");
            popup.getMenu().add(0, 2, 0, "Set Wallpaper");
            popup.getMenu().add(0, 3, 0, "Search Messages");
            popup.getMenu().add(0, 4, 0, "Disappearing Messages");
            // F-17 fix: ExportHelper was complete but had no UI entry point.
            // Wired here where conversationId is always in scope.
            popup.getMenu().add(0, 5, 0, "Export Chat");
            // UX audit item #7: per-chat entry point for fingerprint verification,
            // using THIS conversation's partnerUid rather than a global "last active" guess.
            popup.getMenu().add(0, 6, 0, "Encryption");
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) { startActivity(new Intent(this, SettingsActivity.class)); return true; }
                if (id == 2) { showWallpaperDialog(); return true; }
                if (id == 3) { searchLauncher.launch(new Intent(this, MessageSearchActivity.class)); return true; }
                if (id == 4) { showDisappearPicker(); return true; }
                if (id == 5) {
                    com.duoshield.app.util.ChatExportHelper.showExportDialog(
                        this, conversationId, partnerUid, false);
                    return true;
                }
                if (id == 6) {
                    startActivity(new Intent(this, KeyFingerprintActivity.class)
                            .putExtra("partner_uid", partnerUid));
                    return true;
                }
                return false;
            });
            popup.show();
        });

        // ── Chat views ──────────────────────────────────────────────
        messageInput        = findViewById(R.id.messageInput);
        sendButton          = findViewById(R.id.sendButton);
        uploadButton          = findViewById(R.id.uploadButton);
        micButton             = findViewById(R.id.micButton);
        btnCameraInline       = findViewById(R.id.btnCameraInline);
        ImageView emojiButton = findViewById(R.id.emojiButton);
        uploadProgress          = findViewById(R.id.uploadProgress);
        uploadProgressContainer = findViewById(R.id.uploadProgressContainer);
        tvUploadPct             = findViewById(R.id.tvUploadPct);
        ivUploadThumb           = findViewById(R.id.ivUploadThumb);
        uploadThumbDim          = findViewById(R.id.uploadThumbDim);
        uploadPlainBg           = findViewById(R.id.uploadPlainBg);
        if (emojiButton != null) {
            emojiHelper = new com.duoshield.app.ui.EmojiKeyboardHelper(this, messageInput);
        }
        recyclerView        = findViewById(R.id.messageRecycler);
        typingIndicatorRow  = findViewById(R.id.typingIndicatorRow);
        typingIndicator     = findViewById(R.id.typingIndicator);
        replyPreviewBar     = findViewById(R.id.replyPreviewBar);
        replyPreviewBarText = findViewById(R.id.replyPreviewBarText);
        cancelReplyBtn      = findViewById(R.id.cancelReplyBtn);
        pinnedBanner        = findViewById(R.id.pinnedBanner);
        pinnedText          = findViewById(R.id.pinnedText);
        pinnedCount         = findViewById(R.id.pinnedCount);
        pinnedCloseBtn      = findViewById(R.id.pinnedCloseBtn);
        disappearTimerBanner = findViewById(R.id.disappearTimerBanner);
        tvDisappearTimer     = findViewById(R.id.tvDisappearTimer);
        safetyNumberBanner   = findViewById(R.id.safetyNumberBanner);

        // Voice recording
        voiceRecordingBar  = findViewById(R.id.voiceRecordingBar);
        recordingWaveform  = findViewById(R.id.recordingWaveform);
        recordingTimer     = findViewById(R.id.recordingTimer);
        cancelRecordingBtn = findViewById(R.id.cancelRecordingBtn);
        stopRecordingBtn   = findViewById(R.id.stopRecordingBtn);

        // ── Critical-view null guard ───────────────────��─────────────────────
        // If the layout is missing any of these we cannot function — bail safely.
        if (recyclerView == null || messageInput == null
                || sendButton == null || micButton == null) {
            Toast.makeText(this,
                    "Chat layout failed to load. Please reinstall the app.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        adapter = new MessageAdapter(new ArrayList<>(), myUid,
            new MessageAdapter.OnVoicePlayListener() {
                @Override public void onVoicePlay(Message m, ImageView playPauseBtn,
                        WaveformView waveform, TextView durationView, View bubble) {
                    ChatMediaActivity.this.onVoicePlay(m, playPauseBtn, waveform, durationView, bubble);
                }
                @Override public float getPlaybackProgress(String msgId) {
                    return msgId != null && msgId.equals(currentlyPlayingId) ? currentPlaybackFraction : 0f;
                }
                @Override public int getPlaybackElapsedMs(String msgId) {
                    return msgId != null && msgId.equals(currentlyPlayingId) ? currentPlaybackElapsedMs : 0;
                }
            },
            (msg, anchor) -> showMessageActionDialog(msg),
            this::retryMessage);
        adapter.setOnReplyTapListener(this::scrollToAndHighlight);
        adapter.setOnVoiceSpeedToggleListener(this::onVoiceSpeedToggle);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        llm.setInitialPrefetchItemCount(12);
        recyclerView.setLayoutManager(llm);
        // setHasFixedSize(true): RecyclerView dimensions are fixed to the screen;
        // adding/removing messages doesn't change the RecyclerView's own size.
        // This skips requestLayout() on every data-set change — measurable on
        // slow CPUs (Helio G36) with large chat histories (200+ messages).
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setAdapter(adapter);
        if (recyclerView.getItemAnimator() != null) {
            recyclerView.getItemAnimator().setChangeDuration(0);
            recyclerView.getItemAnimator().setAddDuration(120);
            recyclerView.getItemAnimator().setRemoveDuration(80);
        }

        // ── Keep bottom pinned across keyboard show/hide (reply mode, emoji, etc.) ──
        // stackFromEnd only anchors the RecyclerView to the bottom on its *initial*
        // layout. When the IME opens/closes under windowSoftInputMode="adjustResize"
        // the RecyclerView's height changes and LinearLayoutManager re-derives the
        // visible window from its existing anchor, which for a moment can land above
        // the last messages — reading as messages "disappearing" until the layout
        // settles a frame or two later. Explicitly re-pinning to the last item on every
        // height change removes that flash instead of relying on the default anchor
        // recovery.
        final View chatRoot = findViewById(android.R.id.content);
        chatRoot.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            private int lastHeight = -1;
            @Override public void onGlobalLayout() {
                int h = recyclerView.getHeight();
                if (h == 0) return;
                if (lastHeight != -1 && h != lastHeight && adapter.getItemCount() > 0) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm != null) {
                        int lastVisible = lm.findLastVisibleItemPosition();
                        boolean wasAtBottom = lastVisible >= adapter.getItemCount() - 2;
                        if (wasAtBottom || h < lastHeight) {
                            recyclerView.scrollToPosition(adapter.getItemCount() - 1);
                        }
                    }
                }
                lastHeight = h;
            }
        });

        // ── Scroll-to-bottom FAB (+ unread badge) and sticky date pill ────────
        // The FAB and its badge live in one container so a single scale/alpha animation
        // moves both together; the badge is positioned to overlap the FAB's top-end corner.
        btnScrollToBottom     = findViewById(R.id.btnScrollToBottom);
        scrollToBottomContainer = findViewById(R.id.scrollToBottomContainer);
        tvUnreadBadge         = findViewById(R.id.tvUnreadBadge);
        stickyDatePill        = findViewById(R.id.stickyDatePill);
        View.OnClickListener jumpToLatest = v -> {
            int last = adapter.getItemCount() - 1;
            if (last >= 0) recyclerView.smoothScrollToPosition(last);
            // Tapping the FAB is the user acknowledging the backlog, so the count resets
            // here rather than waiting for the smooth scroll to actually reach the bottom.
            setUnreadWhileScrolledUp(0);
        };
        if (btnScrollToBottom != null) btnScrollToBottom.setOnClickListener(jumpToLatest);
        recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = adapter.getItemCount() - 1;
                if (last < 0) return;

                // ── FAB visibility ──
                if (scrollToBottomContainer != null) {
                    int lastVisible = lm.findLastVisibleItemPosition();
                    boolean farFromBottom = (last - lastVisible) > 3;
                    if (farFromBottom && scrollToBottomContainer.getVisibility() != View.VISIBLE) {
                        scrollToBottomContainer.setVisibility(View.VISIBLE);
                        scrollToBottomContainer.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(180).start();
                    } else if (!farFromBottom && scrollToBottomContainer.getVisibility() == View.VISIBLE) {
                        scrollToBottomContainer.animate().scaleX(0f).scaleY(0f).alpha(0f)
                            .setDuration(150)
                            .withEndAction(() -> scrollToBottomContainer.setVisibility(View.GONE))
                            .start();
                        // Back at the bottom means everything below has been seen.
                        setUnreadWhileScrolledUp(0);
                    }
                }

                // ── Sticky date pill ──
                updateStickyDatePill(lm);
            }
        });
        // The pill is a scroll affordance, not a permanent chrome element: it fades in while
        // the finger is moving through history and fades back out once the list settles, so
        // it never sits on top of a bubble the user is trying to read.
        recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv, int newState) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    hideStickyDatePill();
                }
            }
        });

        // Attach right-swipe-to-reply callback
        //
        // NOTE: do NOT call adapter.notifyItemChanged() here. ItemTouchHelper is still
        // actively tracking this ViewHolder's translationX while the user's finger is
        // down; forcing a rebind mid-gesture rips the view out from under the touch
        // (re-binding resets translationX to 0 while the helper still thinks it owns
        // an in-flight drag), which is what produced the visible arrow/row flicker.
        // ItemTouchHelper animates the row back to rest on its own once the finger
        // lifts (clearView), so no manual reset is needed.
        new androidx.recyclerview.widget.ItemTouchHelper(new SwipeToReplyCallback(this) {
            @Override
            public void onSwipeTriggered(int position) {
                Object item = adapter.getItemAt(position);
                if (item instanceof Message) {
                    recyclerView.performHapticFeedback(
                            android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    enterReplyMode((Message) item);
                }
            }
        }).attachToRecyclerView(recyclerView);

        db         = FirebaseFirestore.getInstance();

        applyWallpaper();
        loadPartnerInfo();
        listenForConvUpdates();
        // ensureSignalSession establishes the Signal Protocol session and starts the
        // message listener only after the session is ready so messages can be decrypted.
        ensureSignalSession();

        typingThrottle = new PresenceThrottle(conversationId, myUid);

        ButtonPressAnimator.attach(sendButton);
        sendButton.setOnClickListener(v -> {
            String text = messageInput.getText() != null
                    ? messageInput.getText().toString().trim() : "";
            if (!text.isEmpty()) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_PRESS);
                sendMessage(text);
                messageInput.setText("");
            }
        });
        if (uploadButton    != null) uploadButton.setOnClickListener(v -> showMediaTypePopup());
        micButton.setOnClickListener(v -> startVoiceRecording());
        if (btnCameraInline != null) btnCameraInline.setOnClickListener(v -> launchCameraCapture());
        if (emojiButton     != null) emojiButton.setOnClickListener(v -> {
            if (emojiHelper != null) {
                if (emojiHelper.isShowing()) {
                    emojiHelper.dismiss();
                    // Re-show keyboard
                    messageInput.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(messageInput,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                } else {
                    // Hide soft keyboard first, then show emoji panel
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(messageInput.getWindowToken(), 0);
                    emojiHelper.show();
                }
            }
        });
        if (cancelReplyBtn  != null) cancelReplyBtn.setOnClickListener(v -> clearReplyMode());
        if (cancelRecordingBtn != null) cancelRecordingBtn.setOnClickListener(v -> cancelVoiceRecording());
        if (stopRecordingBtn   != null) stopRecordingBtn.setOnClickListener(v -> stopAndSendVoiceRecording());

        messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                typingThrottle.setTyping(s.length() > 0);
                boolean hasText = s.length() > 0;
                sendButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
                micButton.setVisibility(hasText  ? View.GONE   : View.VISIBLE);
                if (btnCameraInline != null)
                    btnCameraInline.setVisibility(hasText ? View.GONE : View.VISIBLE);
            }
        });

        if (pinnedBanner   != null) pinnedBanner.setOnClickListener(v -> cycleAndScrollToPin());
        if (pinnedCloseBtn != null && pinnedBanner != null)
            pinnedCloseBtn.setOnClickListener(v -> pinnedBanner.setVisibility(View.GONE));

        checkSafetyNumberBanner();
    }

    // ══════════════════════════════════════════════════════════════
    /** Truncates a plaintext message to an 80-char conversation-list preview. */
    private static String previewFor(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    // PARTNER INFO IN HEADER
    // ══════════════════════════════════════════════════════════════

    private void loadPartnerInfo() {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        String storedName  = prefs.getString("partner_name", null);
        String storedPhoto = prefs.getString("partner_photo_url", null);

        // Only show cached name if it is real (not the old generic default)
        if (storedName != null && !storedName.isEmpty()
                && !storedName.equals("DuoShield User")) {
            tvPartnerName.setText(storedName);
            setAvatarInitial(storedName);
            if (adapter != null) adapter.setPartnerAvatar(storedPhoto, initialFrom(storedName));
        }
        if (storedPhoto != null && !storedPhoto.isEmpty()) {
            tvAvatarInitial.setVisibility(View.GONE);
            ivPartnerAvatar.setVisibility(View.VISIBLE);
            com.duoshield.app.util.GlideHelper.loadAvatar(this, storedPhoto, ivPartnerAvatar);
            currentPartnerPhotoUrl = storedPhoto;
        }

        if (partnerUid != null) {
            db.collection("users").document(partnerUid).get()
              .addOnSuccessListener(doc -> {
                  if (!doc.exists()) return;

                  // Try every field name that could carry the display name
                  String nameStr = null;
                  for (String field : new String[]{"displayName", "name", "username"}) {
                      Object v = doc.get(field);
                      if (v != null && !v.toString().trim().isEmpty()
                              && !v.toString().equals("DuoShield User")) {
                          nameStr = v.toString().trim();
                          break;
                      }
                  }
                  // Fallback: accept "DuoShield User" only if nothing else is available
                  if (nameStr == null) {
                      Object v = doc.get("displayName");
                      if (v != null && !v.toString().trim().isEmpty()) {
                          nameStr = v.toString().trim();
                      }
                  }

                  if (nameStr != null) {
                      final String finalName = nameStr;
                      tvPartnerName.setText(finalName);
                      setAvatarInitial(finalName);
                      if (adapter != null) adapter.setPartnerName(finalName);
                      if (adapter != null) adapter.setPartnerAvatar(storedPhoto, initialFrom(finalName));
                      getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                          .edit()
                          .putString("partner_name", finalName)
                          .apply();
                  }
                  Object photo = doc.get("photoUrl");
                  if (photo != null && !photo.toString().isEmpty()) {
                      String photoStr = photo.toString();
                      tvAvatarInitial.setVisibility(View.GONE);
                      ivPartnerAvatar.setVisibility(View.VISIBLE);
                      com.duoshield.app.util.GlideHelper.loadAvatar(this, photoStr, ivPartnerAvatar);
                      currentPartnerPhotoUrl = photoStr;
                      if (adapter != null) {
                          String initial = tvAvatarInitial.getText() != null
                                  ? tvAvatarInitial.getText().toString() : "?";
                          adapter.setPartnerAvatar(photoStr, initial);
                      }
                      getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                          .edit()
                          .putString("partner_photo_url", photoStr)
                          .apply();
                      // Write partner's photo URL into the conversation doc so the
                      // conversation list can display it as the contact's avatar.
                      if (conversationId != null && myUid != null) {
                          db.collection("chats").document(conversationId)
                            .update("partnerPhotoUrl_" + myUid, photoStr)
                            .addOnFailureListener(e ->
                                Log.w(TAG, "partnerPhotoUrl write non-critical: " + e.getMessage()));
                      }
                  }
              });
        }
    }

    private void setAvatarInitial(String name) {
        String initial = initialFrom(name);
        tvAvatarInitial.setText(initial);
        tvAvatarInitial.setVisibility(View.VISIBLE);
        ivPartnerAvatar.setVisibility(View.INVISIBLE);
    }

    private void openPartnerPhotoViewer() {
        if (currentPartnerPhotoUrl == null || currentPartnerPhotoUrl.isEmpty()) return;
        Intent i = new Intent(this, com.duoshield.app.FullScreenImageActivity.class);
        i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_URL, currentPartnerPhotoUrl);
        // Avatars are uploaded as plain (unencrypted) JPEGs — no media key to pass.
        startActivity(i);
    }

    private static String initialFrom(String name) {
        return (name == null || name.isEmpty()) ? "?" : String.valueOf(name.charAt(0)).toUpperCase();
    }

    /**
     * UX-5 fix: the encrypted state used to render a "🔒" emoji inline in this trust-critical
     * status line. Emoji glyphs vary per OEM emoji font and TalkBack announces them awkwardly
     * ("lock" mid-sentence), so the lock is now a real tinted vector drawn as a compound
     * drawable on the same TextView. Using a compound drawable keeps the single-TextView
     * header layout intact, and the explicit reset in the other two branches matters because
     * this method is called repeatedly on presence updates — without it a recycled "encrypted"
     * icon would stick around next to "online" or "last seen ...".
     */
    private void updateOnlineStatus(boolean online, long lastSeenMs) {
        headerOnlineDot.setVisibility(online ? View.VISIBLE : View.GONE);
        if (online) {
            tvOnlineStatus.setText("online");
            tvOnlineStatus.setTextColor(0xFF6BBF8A);
            tvOnlineStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            tvOnlineStatus.setContentDescription(null);
        } else if (lastSeenMs > 0) {
            tvOnlineStatus.setText("last seen " + formatLastSeen(lastSeenMs));
            tvOnlineStatus.setTextColor(0xFF9A8FB0);
            tvOnlineStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            tvOnlineStatus.setContentDescription(null);
        } else {
            tvOnlineStatus.setText(R.string.encrypted_badge_desc);
            tvOnlineStatus.setTextColor(0xFF9A8FB0);
            // ic_lock is a 24dp vector with a baked-in accent tint, which is far too large
            // and the wrong colour beside this 12sp secondary-text line. Size it down to
            // ~13dp explicitly (so setBounds, not the 24dp intrinsic bounds, wins) and
            // re-tint it to match the status text.
            android.graphics.drawable.Drawable lock =
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_lock);
            if (lock != null) {
                lock = lock.mutate();
                int px = (int) (13 * getResources().getDisplayMetrics().density);
                lock.setBounds(0, 0, px, px);
                androidx.core.graphics.drawable.DrawableCompat.setTint(lock, 0xFF9A8FB0);
                tvOnlineStatus.setCompoundDrawablesRelative(lock, null, null, null);
                tvOnlineStatus.setCompoundDrawablePadding(
                        (int) (5 * getResources().getDisplayMetrics().density));
            }
            // The label text already reads "End-to-end encrypted", so the icon needs no
            // separate announcement and the default TextView reading is correct as-is.
        }
    }

    private String formatLastSeen(long epochMs) {
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < 60_000) return "just now";
        if (diff < 3600_000) return (diff / 60_000) + "m ago";
        if (diff < 86400_000) return (diff / 3600_000) + "h ago";
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            .format(new java.util.Date(epochMs));
    }

    // ══════════════════════════════════════════════════════════════
    // CALLING
    // ══════════════════════════════════════════════════════════════

    private void requestCallPermissions(boolean isVideo) {
        pendingCallIsVideo = isVideo;
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (isVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (needed.isEmpty()) {
            launchCallActivity(isVideo);
        } else {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]),
                    isVideo ? REQUEST_CALL_VIDEO : REQUEST_CALL_VOICE);
        }
    }

    private void launchCallActivity(boolean isVideo) {
        if (partnerUid == null || myUid == null) {
            Toast.makeText(this, "Cannot start call — missing contact info", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, com.duoshield.app.call.CallActivity.class);
        intent.putExtra(com.duoshield.app.call.CallActivity.EXTRA_IS_CALLER,    true);
        intent.putExtra(com.duoshield.app.call.CallActivity.EXTRA_IS_VIDEO,     isVideo);
        intent.putExtra(com.duoshield.app.call.CallActivity.EXTRA_MY_UID,       myUid);
        intent.putExtra(com.duoshield.app.call.CallActivity.EXTRA_CALLEE_ID,    partnerUid);
        intent.putExtra(com.duoshield.app.call.CallActivity.EXTRA_CHAT_ID,      conversationId);
        // Pass partner display name if available from the header TextView
        String name = tvPartnerName != null ? tvPartnerName.getText().toString() : partnerUid;
        intent.putExtra(com.duoshield.app.call.CallActivity.EXTRA_PARTNER_NAME, name);
        startActivity(intent);
    }

    // VOICE RECORDING
    // ══════════════════════════════════════════════════════════════

    private void startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
            return;
        }
        recordingSeconds = 0;
        if (recordingTimer != null) recordingTimer.setText("0:00");
        if (recordingWaveform != null) recordingWaveform.clear();
        if (voiceRecordingBar == null) {
            Toast.makeText(this, "Voice recording unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        voiceRecordingBar.setVisibility(View.VISIBLE);
        View inputBar = findViewById(R.id.inputBar);
        if (inputBar != null) inputBar.setVisibility(View.GONE);
        recordingTimerHandler.post(timerTick);

        recorder.start(this, new VoiceRecorderHelper.RecorderListener() {
            @Override public void onAmplitude(int amp)  {
                if (recordingWaveform != null) recordingWaveform.addAmplitude(amp);
            }
            @Override public void onStopped(String filePath, List<Integer> amplitudes) {
                // Amplitudes are sampled every 100ms (first sample delayed 300ms to let
                // the mic warm up) — size*100 is an accurate-enough total duration
                // without needing to change VoiceRecorderHelper's callback signature.
                int durationMs = amplitudes.size() * 100;
                uploadVoiceNote(filePath, amplitudes, durationMs);
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    Toast.makeText(ChatMediaActivity.this, "Recording error: " + msg, Toast.LENGTH_SHORT).show();
                    dismissRecordingUI();
                });
            }
        });
    }

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            recordingSeconds++;
            recordingTimer.setText(String.format(Locale.US, "%d:%02d",
                recordingSeconds / 60, recordingSeconds % 60));
            recordingTimerHandler.postDelayed(this, 1000);
        }
    };

    private void cancelVoiceRecording() {
        recordingTimerHandler.removeCallbacks(timerTick);
        recorder.cancel();
        dismissRecordingUI();
    }

    private void stopAndSendVoiceRecording() {
        recordingTimerHandler.removeCallbacks(timerTick);
        recorder.stop();
        dismissRecordingUI();
    }

    private void dismissRecordingUI() {
        voiceRecordingBar.setVisibility(View.GONE);
        View inputBar = findViewById(R.id.inputBar);
        if (inputBar != null) inputBar.setVisibility(View.VISIBLE);
    }

    private void uploadVoiceNote(String filePath, List<Integer> amplitudes, int durationMs) {
        uploadVoiceNoteWithRetry(filePath, amplitudes, durationMs, 0);
    }

    // BUG-U01 fix: add retry logic for voice uploads with exponential backoff
    private void uploadVoiceNoteWithRetry(String filePath, List<Integer> amplitudes, int durationMs, int retryCount) {
        if (isFinishing() || isDestroyed()) return;
        if (retryCount > 3) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                uploadProgressContainer.setVisibility(View.GONE);
                showB2ErrorDialog("Voice upload failed after 3 attempts.", null);
            });
            return;
        }

        File f = new File(filePath);
        if (!f.exists()) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                uploadProgressContainer.setVisibility(View.GONE);
                Toast.makeText(ChatMediaActivity.this, R.string.voice_file_not_found, Toast.LENGTH_SHORT).show();
            });
            return;
        }

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            uploadProgressContainer.setVisibility(View.VISIBLE);
            tvUploadPct.setText("0%");
        });
        String objectKey = "voice/" + conversationId + "/" + UUID.randomUUID() + ".m4a";
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            try {
                byte[] plain = readFileBytes(f);
                B2StorageHelper.EncryptedMedia enc = B2StorageHelper.encryptForUpload(plain);
                String storagePath = B2StorageHelper.uploadFile(
                        enc.data, objectKey, "audio/mp4",
                        pct -> runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) tvUploadPct.setText(pct + "%");
                        }));
                final String mediaKey = enc.keyBase64;
                final String finalPath = storagePath;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    uploadProgressContainer.setVisibility(View.GONE);
                    sendVoiceMessage(finalPath, mediaKey, amplitudes, durationMs);
                });
                f.delete();
            } catch (Exception e) {
                Log.e(TAG, "Voice upload failed (attempt " + (retryCount + 1) + "/4)", e);
                final String errMsg = e.getMessage();
                if (retryCount >= 3) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        uploadProgressContainer.setVisibility(View.GONE);
                        showB2ErrorDialog("Voice note upload failed.", errMsg);
                    });
                    return;
                }
                // Exponential backoff: 1s, 2s, 4s
                long delayMs = (long) (1000 * Math.pow(2, retryCount));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed() && !executor.isShutdown()) {
                        uploadVoiceNoteWithRetry(filePath, amplitudes, durationMs, retryCount + 1);
                    }
                }, delayMs);
            }
        });
    }

    private static byte[] readFileBytes(File f) throws java.io.IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private byte[] readUriBytes(Uri uri) throws java.io.IOException {
        java.io.InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new java.io.IOException("Cannot open URI: " + uri);
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally { is.close(); }
    }

    private List<Integer> downsampleAmplitudes(List<Integer> raw, int target) {
        if (raw == null || raw.isEmpty()) return new java.util.ArrayList<>();
        if (raw.size() <= target) return new java.util.ArrayList<>(raw);
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>(target);
        float ratio = (float) raw.size() / target;
        for (int i = 0; i < target; i++) out.add(raw.get((int)(i * ratio)));
        return out;
    }

    private void sendVoiceMessage(String storagePath, String mediaKey, List<Integer> amplitudes, int durationMs) {
        String msgId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long exp = getDisappearMs() > 0 ? now + getDisappearMs() : 0;

        List<Integer> sampledAmps = downsampleAmplitudes(amplitudes, 60);

        // Optimistic UI — show immediately (same pattern as sendMediaMessage)
        Message m = new Message(msgId, conversationId, myUid, "", now, false, storagePath, "voice");
        m.setExpiresAt(exp);
        m.setMediaKey(mediaKey);
        m.setStatus("pending");
        m.setWaveAmplitudes(sampledAmps);
        m.setDurationMs(durationMs);
        adapter.appendMessage(m);
        knownIds.add(msgId);
        recyclerView.scrollToPosition(adapter.getItemCount() - 1);

        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); doc.put("conversationId", conversationId);
        doc.put("sender", myUid); doc.put("text", "");
        doc.put("path", storagePath);
        doc.put("mediaType", "voice");
        doc.put("type", "voice");
        doc.put("isEncrypted", true);
        doc.put("mediaKey", mediaKey);
        doc.put("status", "sent");
        doc.put("amplitudes", sampledAmps);
        doc.put("durationMs", durationMs);
        doc.put("expiresAt", exp); doc.put("timestamp", FieldValue.serverTimestamp());
        db.collection("chats").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              FirebaseCostGuard.getInstance(ChatMediaActivity.this).recordWrites(1);
              m.setStatus("sent");
              adapter.updateMessage(msgId, msg -> msg.setStatus("sent"));
              saveToRoom(m);
              notifyPartner("DuoShield", "Sent a voice note 🎙", msgId);
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Failed to send voice message: " + e.getMessage());
              m.setStatus("failed");
              adapter.updateMessage(msgId, msg -> msg.setStatus("failed"));
              saveToRoom(m);
              Toast.makeText(ChatMediaActivity.this, "Failed to send voice note. Tap to retry.", Toast.LENGTH_SHORT).show();
          });
    }

    // ══════════════════════════════════════════════════════════════
    // VOICE PLAYBACK
    // ══════════════════════════════════════════════════════════════

    /**
     * Tapped the "1x"-style pill on a currently-playing voice note. Cycles
     * 1x → 1.5x → 2x → 1x and applies it to whichever note is actually playing
     * (which is always the one this pill belongs to, since the pill only shows
     * up on the playing row — see MessageAdapter#bindVoiceTrailingSlot).
     */
    private void onVoiceSpeedToggle(Message msg, TextView pillView) {
        float newSpeed = player.cycleSpeed();
        String label = (newSpeed == Math.floor(newSpeed))
                ? ((int) newSpeed) + "x"
                : newSpeed + "x";
        // Immediate feedback on the tapped view...
        if (msg.getId() != null && msg.getId().equals(pillView.getTag())) {
            pillView.setText(label);
        }
        // ...and persist it so future binds of any voice row show the right label.
        adapter.setCurrentSpeedLabel(label);
    }

    private void onVoicePlay(Message msg, ImageView playPauseBtn,
                             WaveformView waveform, TextView durationView, View bubble) {
        final String msgId = msg.getId();

        // ── Pause: same message is currently playing ──────────────────────
        if (msgId.equals(currentlyPlayingId)) {
            player.pause();
            pausedMessageId    = msgId;
            currentlyPlayingId = null;
            adapter.setPlayingMessageId(null);
            if (msgId.equals(playPauseBtn.getTag()))
                playPauseBtn.setImageResource(R.drawable.ic_play_audio);
            if (msgId.equals(bubble.getTag()))
                MessageAdapter.stopBreathingAnim(bubble);
            return;
        }

        // ── Resume: same message was previously paused ────────────────────
        if (msgId.equals(pausedMessageId)) {
            pausedMessageId    = null;
            currentlyPlayingId = msgId;
            adapter.setPlayingMessageId(msgId);
            if (msgId.equals(playPauseBtn.getTag()))
                playPauseBtn.setImageResource(R.drawable.ic_pause_audio);
            player.resume();
            return;
        }
        // The previously-playing row (if any) gets rebound to rest via
        // adapter.setPlayingMessageId() below, which resets its bubble scale.

        // ── New voice note: release previous, start fresh ─────────────────
        player.release();
        // Delete the previous temp file now that its player has been released.
        if (currentVoiceTmpFile != null) {
            currentVoiceTmpFile.delete();
            currentVoiceTmpFile = null;
        }
        pausedMessageId    = null;
        currentlyPlayingId = msgId;
        adapter.setPlayingMessageId(msgId);
        if (msgId.equals(playPauseBtn.getTag()))
            playPauseBtn.setImageResource(R.drawable.ic_pause_audio);

        // Seed with the persisted duration from when this note was recorded/sent.
        // MediaPlayer.getDuration() is unreliable immediately after prepare for some
        // locally-decrypted temp files — it can silently report 0/-1 depending on how
        // that particular recording finalized its duration metadata, which previously
        // blocked ALL waveform progress/dot updates for the rest of playback (audio
        // still played fine, so this looked like a random per-note glitch). Falling
        // back to the known-good persisted value keeps progress working either way.
        final int[] totalDurHolder = {msg.getDurationMs() > 0 ? msg.getDurationMs() : 0};
        // NOTE: every callback below looks up the CURRENT row for msgId via
        // adapter.getLiveVoiceViews() instead of writing to the waveform/durationView/
        // playPauseBtn/bubble params captured at tap-time. The RecyclerView can recycle
        // that row to a different position (or a different message entirely) while this
        // note keeps playing in the background — e.g. the user scrolls, or a new incoming
        // message forces a rebind of visible rows. Writing to the stale captured Views in
        // that case silently updates a View that's no longer on screen, which is why
        // playback used to show no moving progress indicator at all.
        VoiceMessagePlayer.PlayerListener listener = new VoiceMessagePlayer.PlayerListener() {
            @Override public void onStart(int durationMs) {
                // Only trust the live value when it's actually real; otherwise keep
                // the persisted fallback already seeded above.
                if (durationMs > 0) {
                    totalDurHolder[0] = durationMs;
                }
                final int displayDur = totalDurHolder[0] > 0 ? totalDurHolder[0] : durationMs;
                runOnUiThread(() -> {
                    MessageAdapter.LiveVoiceViews live = adapter.getLiveVoiceViews(recyclerView, msgId);
                    if (live != null) live.durationView.setText(MessageAdapter.formatDuration(displayDur));
                });
            }
            @Override public void onProgress(int posMs) {
                currentPlaybackElapsedMs = posMs;
                // If onStart received 0/-1 from MediaPlayer (a known OEM quirk for
                // locally-decrypted temp files), try fetching the live duration now —
                // it becomes available a short time after prepare completes on most
                // devices. Without this, totalDurHolder stays 0 and the waveform thumb
                // never moves for the entire playback.
                if (totalDurHolder[0] <= 0) {
                    int liveDur = player.getDuration();
                    if (liveDur > 0) totalDurHolder[0] = liveDur;
                }
                runOnUiThread(() -> {
                    MessageAdapter.LiveVoiceViews live = adapter.getLiveVoiceViews(recyclerView, msgId);
                    if (totalDurHolder[0] > 0) {
                        currentPlaybackFraction = (float) posMs / totalDurHolder[0];
                    }
                    if (live == null) return; // row is off-screen right now; state above will re-seed it on rebind
                    live.durationView.setText(MessageAdapter.formatDuration(posMs));
                    if (totalDurHolder[0] > 0) {
                        live.waveform.setProgress(currentPlaybackFraction);
                        // "Breathe" the bubble with the actual amplitude at this
                        // point in the track — same data the waveform bars show.
                        MessageAdapter.applyBreathingAmplitude(
                            live.bubble, live.waveform.getAmplitudeAt(currentPlaybackFraction));
                    }
                });
            }
            @Override public void onComplete() {
                runOnUiThread(() -> {
                    currentlyPlayingId = null;
                    pausedMessageId    = null;
                    currentPlaybackFraction  = 0f;
                    currentPlaybackElapsedMs = 0;
                    adapter.setPlayingMessageId(null);
                    MessageAdapter.LiveVoiceViews live = adapter.getLiveVoiceViews(recyclerView, msgId);
                    if (live != null) {
                        live.playPauseBtn.setImageResource(R.drawable.ic_play_audio);
                        live.waveform.setProgress(0f);
                        MessageAdapter.stopBreathingAnim(live.bubble);
                        if (totalDurHolder[0] > 0)
                            live.durationView.setText(MessageAdapter.formatDuration(totalDurHolder[0]));
                    }
                });
            }
            @Override public void onError(String err) {
                runOnUiThread(() -> {
                    Toast.makeText(ChatMediaActivity.this, "Playback error", Toast.LENGTH_SHORT).show();
                    currentlyPlayingId = null;
                    pausedMessageId    = null;
                    currentPlaybackFraction  = 0f;
                    currentPlaybackElapsedMs = 0;
                    adapter.setPlayingMessageId(null);
                    MessageAdapter.LiveVoiceViews live = adapter.getLiveVoiceViews(recyclerView, msgId);
                    if (live != null) {
                        live.playPauseBtn.setImageResource(R.drawable.ic_play_audio);
                        MessageAdapter.stopBreathingAnim(live.bubble);
                    }
                });
            }
        };

        // Allow the user to scrub to any position by touching the waveform.
        waveform.setSeekListener(fraction -> {
            if (totalDurHolder[0] > 0) {
                int seekPos = (int) (fraction * totalDurHolder[0]);
                player.seekTo(seekPos);
                currentPlaybackFraction  = fraction;
                currentPlaybackElapsedMs = seekPos;
                // Update the live duration counter immediately (don't wait for next poll tick)
                MessageAdapter.LiveVoiceViews live = adapter.getLiveVoiceViews(recyclerView, msgId);
                if (live != null) live.durationView.setText(MessageAdapter.formatDuration(seekPos));
            }
        });

        String voiceRef = msg.getMediaUrl();
        if (B2StorageHelper.isB2Path(voiceRef)) {
            // B2-backed voice note — download, decrypt, write to temp file
            B2StorageHelper.loadMedia(this, voiceRef, msg.getMediaKey(), new B2StorageHelper.MediaCallback() {
                @Override public void onLoaded(byte[] plainBytes) {
                    if (!msgId.equals(currentlyPlayingId)) return;
                    try {
                        File tmp = File.createTempFile("voice_", ".m4a", getCacheDir());
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                            fos.write(plainBytes);
                        }
                        currentVoiceTmpFile = tmp; // tracked for cleanup
                        player.play(tmp.getAbsolutePath(), listener);
                    } catch (Exception ex) {
                        runOnUiThread(() -> {
                            Toast.makeText(ChatMediaActivity.this,
                                    "Playback error", Toast.LENGTH_SHORT).show();
                            currentlyPlayingId = null;
                            pausedMessageId    = null;
                            adapter.setPlayingMessageId(null);
                            if (msgId.equals(playPauseBtn.getTag()))
                                playPauseBtn.setImageResource(R.drawable.ic_play_audio);
                        });
                    }
                }
                @Override public void onError(Exception e) {
                    String detail = e != null && e.getMessage() != null ? e.getMessage() : "unknown error";
                    Log.e(TAG, "Voice note B2 load failed: " + detail, e);
                    runOnUiThread(() -> {
                        Toast.makeText(ChatMediaActivity.this,
                                "Couldn't load voice note: " + detail, Toast.LENGTH_LONG).show();
                        currentlyPlayingId = null;
                        pausedMessageId    = null;
                        adapter.setPlayingMessageId(null);
                        if (msgId.equals(playPauseBtn.getTag()))
                            playPauseBtn.setImageResource(R.drawable.ic_play_audio);
                    });
                }
            });
        } else {
            // Legacy Firebase Storage URL — play directly
            player.play(voiceRef, listener);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecording();
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.RECORD_AUDIO)) {
                // Permanently denied — offer to open App Settings
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_mic_title)
                    .setMessage(R.string.perm_mic_message)
                    .setPositiveButton(R.string.perm_open_settings, (d, w) -> {
                        Intent intent = new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    })
                    .setNegativeButton(R.string.perm_not_now, null)
                    .show();
            } else {
                Toast.makeText(this, R.string.perm_mic_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_CAMERA_PHOTO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCameraCapture();
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.CAMERA)) {
                new MaterialAlertDialogBuilder(this)
                    .setTitle("Camera permission required")
                    .setMessage("Grant camera access in Settings to take photos.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        Intent intent = new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    })
                    .setNegativeButton("Not now", null)
                    .show();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_CALL_VOICE || requestCode == REQUEST_CALL_VIDEO) {
            boolean isVideo = (requestCode == REQUEST_CALL_VIDEO);
            boolean audioGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean cameraGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (!audioGranted) {
                Toast.makeText(this, "Microphone permission is required for calls", Toast.LENGTH_LONG).show();
                return;
            }
            if (isVideo && !cameraGranted) {
                // Graceful audio-only fallback when camera denied
                Toast.makeText(this, "Camera denied — starting audio-only call", Toast.LENGTH_SHORT).show();
                launchCallActivity(false);
            } else {
                launchCallActivity(isVideo);
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // F-19 mirror (same guard as ConversationListActivity): if BaseActivity.onStart()
        // just launched LockScreenActivity, do not load or display any data until the user
        // unlocks. onResume() will fire again after unlock with shouldLock() == false.
        if (AppLockManager.shouldLock(this)) return;
        // FLAG_SECURE is now applied globally in BaseActivity.onCreate()
        // based on the "app_screenshot_enabled" preference.
        markMessagesAsReadAndSeen();
        clearBadge();
        applyWallpaper();
        // Refresh bubble style/colour and font size in case the user changed them in Settings.
        if (adapter != null) adapter.notifyBubbleStyleChanged();
        // Restart Firestore listeners that were detached in onStop (e.g. after opening Settings)
        if (conversationId != null) {
            if (msgListener  == null) ensureSignalSession(); // ensures session then starts listener
            if (convListener == null) listenForConvUpdates();
        }
        // Mark this user as online in the active conversation so the partner's conversation
        // list and chat header show the online dot immediately.
        if (conversationId != null && myUid != null) {
            com.duoshield.app.util.OnlinePresenceHelper.setOnline(this, conversationId, myUid);
        }
        updateDisappearBanner();
        checkSafetyNumberBanner();
        disappearHandler.post(disappearTicker);
    }

    @Override protected void onStop() {
        super.onStop();
        if (msgListener  != null) { msgListener.remove();  msgListener  = null; }
        if (convListener != null) { convListener.remove(); convListener = null; }
        if (typingThrottle != null) typingThrottle.clear();
        disappearHandler.removeCallbacks(disappearTicker);
        recordingTimerHandler.removeCallbacks(timerTick);
        player.release();
        // Mark offline so the partner's conversation list / chat header reflects the
        // correct presence state as soon as this chat goes to background.
        if (conversationId != null && myUid != null) {
            com.duoshield.app.util.OnlinePresenceHelper.setOffline(this, conversationId, myUid);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (!executor.isShutdown())   executor.shutdownNow();
        if (!dbExecutor.isShutdown()) dbExecutor.shutdownNow();
        // Clean up any decrypted voice temp file left from the last playback.
        if (currentVoiceTmpFile != null) {
            currentVoiceTmpFile.delete();
            currentVoiceTmpFile = null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // FIRESTORE LISTENERS
    // ════════════════════════════════════════════════════════��═════

    private void listenForConvUpdates() {
        convListener = db.collection("chats").document(conversationId)
          .addSnapshotListener((snap, e) -> {
              if (snap == null) return;

              // Pinned messages
              Object pinnedRaw = snap.get("pinnedMessages");
              List<Map<String, Object>> raw = null;
              if (pinnedRaw instanceof List) {
                  try {
                      //noinspection unchecked
                      raw = (List<Map<String, Object>>) pinnedRaw;
                  } catch (ClassCastException ignored) {}
              }
              pinnedList = raw != null ? raw : new ArrayList<>();
              Set<String> ids = new HashSet<>();
              for (Map<String, Object> m : pinnedList) {
                  Object id = m.get("id");
                  if (id instanceof String) ids.add((String) id);
              }
              adapter.updatePinnedIds(ids);
              refreshPinnedBanner();

              // Typing — smooth fade in/out
              Object typing = snap.get("typing_" + partnerUid);
              boolean isTyping = Boolean.TRUE.equals(typing);
              if (isTyping && typingIndicatorRow.getVisibility() != View.VISIBLE) {
                  typingIndicatorRow.setAlpha(0f);
                  typingIndicatorRow.setVisibility(View.VISIBLE);
                  typingIndicatorRow.animate().alpha(1f).setDuration(180).start();
              } else if (!isTyping && typingIndicatorRow.getVisibility() == View.VISIBLE) {
                  typingIndicatorRow.animate().alpha(0f).setDuration(150)
                      .withEndAction(() -> typingIndicatorRow.setVisibility(View.GONE)).start();
              }

              // Online / last seen
              Object online   = snap.get("online_"   + partnerUid);
              Object lastSeen = snap.get("lastSeen_" + partnerUid);
              long lastSeenMs = 0;
              if (lastSeen instanceof com.google.firebase.Timestamp)
                  lastSeenMs = ((com.google.firebase.Timestamp) lastSeen).toDate().getTime();
              updateOnlineStatus(Boolean.TRUE.equals(online), lastSeenMs);

              // ── Disappearing-messages partner sync (Feature B) ──────────────
              // If the partner changed the timer we update the local pref and
              // show a Snackbar. The `disappear_set_by` field tells us who made
              // the change; we only act when it was NOT us.
              Object fsDisappearMs = snap.get("disappear_ms");
              Object fsSetBy       = snap.get("disappear_set_by");
              if (fsDisappearMs instanceof Long && fsSetBy instanceof String
                      && !myUid.equals(fsSetBy)) {
                  long partnerMs = (Long) fsDisappearMs;
                  android.content.SharedPreferences sp =
                          getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
                  if (partnerMs != sp.getLong("disappear_ms_" + conversationId, 0)) {
                      sp.edit().putLong("disappear_ms_" + conversationId, partnerMs).apply();
                      scheduleOrCancelDestruct(partnerMs);
                      updateDisappearBanner();
                      String label = "off";
                      for (int i = 0; i < DISAPPEAR_OPTS_MS.length; i++) {
                          if (DISAPPEAR_OPTS_MS[i] == partnerMs) {
                              label = DISAPPEAR_OPTS_LBL[i]; break;
                          }
                      }
                      String msg = partnerMs > 0
                          ? "Partner set messages to disappear after " + label
                          : "Partner turned off disappearing messages";
                      Snackbar.make(recyclerView, msg, Snackbar.LENGTH_LONG).show();
                  }
              }

              // ── Blue-tick retroactive update ──────────────────────────────────
              // DeliveryReceiptHelper.markRead() writes "last_read_<partnerUid>"
              // to this doc when the partner reads messages. Because our Firestore
              // message listener uses startAfter(latestKnownTimestamp), MODIFIED
              // events for older messages never arrive. Detecting the field here
              // lets us retroactively flip the tick on ALL our sent messages.
              //
              // Guard: only act when the timestamp actually changed vs. the last
              // value we saw. This prevents marking fresh outgoing messages as read
              // on every snapshot and prevents an infinite update storm.
              Object lastReadRaw = snap.get("last_read_" + partnerUid);
              if (lastReadRaw instanceof com.google.firebase.Timestamp) {
                  long lastReadMs = ((com.google.firebase.Timestamp) lastReadRaw).toDate().getTime();
                  if (lastReadMs > lastPartnerReadMs) {
                      lastPartnerReadMs = lastReadMs;
                      // Mark messages we sent whose timestamp <= lastReadMs as "read".
                      // batchUpdateStatus() fires exactly one notifyItemChanged per row
                      // instead of N separate adapter.updateMessage() calls — O(n) total.
                      final long readCutoff = lastReadMs;
                      List<Message> allMsgs = adapter.getMessages();
                      List<String> toUpdateRoom = new ArrayList<>();
                      for (Message m : allMsgs) {
                          if (myUid != null && myUid.equals(m.getSender())
                                  && !"read".equals(m.getStatus())
                                  && m.getTimestamp() <= readCutoff) {
                              if (m.getId() != null) toUpdateRoom.add(m.getId());
                          }
                      }
                      if (!toUpdateRoom.isEmpty()) {
                          adapter.batchUpdateStatus(toUpdateRoom, "read");
                          dbExecutor.execute(() -> {
                              for (String id : toUpdateRoom) {
                                  AppDatabase.getInstance(ChatMediaActivity.this)
                                             .messageDao().updateStatus(id, "read");
                              }
                          });
                      }
                  }
              }
          });
    }

    private void listenForMessages() {
        // Bug 1 & 5 fix: if ECDH derivation is in-flight, bail out. The executor's
        // finally block will clear keyPending and call us again once the key is stored.
        if (keyPending) return;
        if (msgListener != null) return;

        if (knownIds.isEmpty()) {
            // F-07: First open — seed the adapter from the Room cache instantly so the
            // UI is populated without a Firestore round-trip, then attach a listener
            // that starts *after* our latest local timestamp (fetches only new messages,
            // not the full history on every foreground).
            // Duplicate-seed guard: if a second call arrives before the first dbExecutor
            // task completes (e.g. onEstablished fires twice), skip re-seeding and go
            // straight to attachFirestoreListener() once the first task posts to UI thread.
            if (!seeded.compareAndSet(false, true)) {
                return; // first seed already in flight; its runOnUiThread will call attachFirestoreListener
            }
            dbExecutor.execute(() -> {
                // Load only the latest 300 messages from Room — avoids loading thousands
                // of messages into memory on first open. Older history is already in Room
                // and can be paginated later; Firestore streams only new messages.
                List<Message> local = AppDatabase.getInstance(this)
                        .messageDao().getLatestMessages(conversationId, 300);
                // getLatestMessages returns DESC; reverse to ASC for display
                java.util.Collections.reverse(local);

                for (Message m : local) {
                    if (m.getId() == null) continue;
                    // Re-queue placeholder messages from prior session so retryPendingDecryption()
                    // can decrypt them once the correct ECDH key is available.
                    String t = m.getText();
                    if ("[Decryption failed]".equals(t)
                            || "[Waiting for encryption key\u2026]".equals(t)) {
                        m.setEncrypted(true);
                        pendingDecryptQueue.add(m);
                    }
                    knownIds.add(m.getId());
                    if (m.getTimestamp() > latestKnownTimestamp) {
                        latestKnownTimestamp = m.getTimestamp();
                    }
                }
                runOnUiThread(() -> {
                    for (Message m : local) adapter.appendMessage(m);
                    if (!local.isEmpty()) {
                        recyclerView.scrollToPosition(adapter.getItemCount() - 1);
                    }
                    // Receipt fix: the Firestore listener uses startAfter(latestKnownTimestamp)
                    // so older messages loaded from Room never pass through the ADDED handler.
                    // Call markRead here for any incoming messages that haven't been read yet,
                    // so the sender sees double-ticks even when the receiver's chat was opened
                    // from a cached (Room) load rather than a fresh Firestore download.
                    if (myUid != null) {
                        List<Message> unread = new ArrayList<>();
                        for (Message m : local) {
                            if (!myUid.equals(m.getSender())
                                    && !"read".equals(m.getStatus())) {
                                unread.add(m);
                            }
                        }
                        if (!unread.isEmpty()) {
                            DeliveryReceiptHelper.markRead(conversationId, unread, myUid);
                        }
                    }
                    attachFirestoreListener();
                });
            });
        } else {
            // Re-attach after ECDH key re-derive — adapter already has local messages;
            // startAfter(latestKnownTimestamp) picks up only what arrived since then.
            attachFirestoreListener();
        }
    }

    /**
     * Attaches the Firestore real-time snapshot listener.
     * <p>
     * F-07: Queries only messages newer than {@link #latestKnownTimestamp} so we
     * never re-fetch the full conversation history on foreground.
     * F-10: Records the Firestore read count via {@link FirebaseCostGuard}.
     */
    private void attachFirestoreListener() {
        if (msgListener != null) return; // guard against double-attach

        com.google.firebase.firestore.Query q =
                db.collection("chats").document(conversationId)
                  .collection("messages").orderBy("timestamp");

        // F-07: skip messages we already hold locally
        if (latestKnownTimestamp > 0) {
            q = q.startAfter(new java.util.Date(latestKnownTimestamp));
        }

        msgListener = q.addSnapshotListener((snaps, e) -> {
            if (snaps == null) return;

            // F-10: record Firestore reads for quota tracking
            int snapSize = snaps.size();
            if (snapSize > 0) {
                FirebaseCostGuard.getInstance(ChatMediaActivity.this).recordReads(snapSize);
            }

            boolean newMessageAdded = false;
            boolean signalMsgQueued = false; // tracks if any Signal msgs were queued this snapshot
            List<Message> newIncoming = new ArrayList<>();

            for (DocumentChange dc : snaps.getDocumentChanges()) {
                if (dc.getType() == DocumentChange.Type.ADDED) {
                    String id    = dc.getDocument().getString("id");
                    String convo = dc.getDocument().getString("conversationId");
                    String from  = dc.getDocument().getString("sender");
                    String text  = dc.getDocument().getString("text");
                    // "path" = B2 private path (new); "mediaUrl" = legacy Firebase URL
                    String mUrl  = dc.getDocument().getString("path");
                    if (mUrl == null) mUrl = dc.getDocument().getString("mediaUrl");
                    String mType = dc.getDocument().getString("mediaType");
                    String mKey  = dc.getDocument().getString("mediaKey");
                    String  rpId    = dc.getDocument().getString("replyToId");
                    String  rpPrv   = dc.getDocument().getString("replyPreview");
                    Long    expAt   = dc.getDocument().getLong("expiresAt");
                    Boolean fwdFlag = dc.getDocument().getBoolean("forwarded");
                    long    ts      = System.currentTimeMillis();

                    com.google.firebase.Timestamp serverTs =
                            dc.getDocument().getTimestamp("timestamp");
                    if (serverTs != null) ts = serverTs.toDate().getTime();

                    if (id == null) continue;

                    // F42 fix: handle deleted-for-everyone on initial load (ADDED events for
                    // existing messages) so previously deleted messages never render as live content.
                    Boolean deletedForAllAdded = dc.getDocument().getBoolean("deletedForAll");
                    if (Boolean.TRUE.equals(deletedForAllAdded)) {
                        if (knownIds.contains(id)) {
                            // Already in adapter (from Room load) — overwrite with tombstone in UI
                            // and persist so stale content cannot reappear after restart.
                            final String existingDelId = id;
                            adapter.updateMessage(existingDelId, m -> {
                                m.setText("\u26d4 Message deleted");
                                m.setMediaUrl(null);
                                m.setMediaType(null);
                                m.setDeleted(true);
                            });
                            dbExecutor.execute(() ->
                                AppDatabase.getInstance(ChatMediaActivity.this)
                                    .messageDao().markTombstone(existingDelId));
                        } else {
                            // Not yet rendered — build tombstone and append it.
                            // Use insert(REPLACE) so a new row is created if none exists locally;
                            // markTombstone() is UPDATE-only and would silently create 0 rows here.
                            Message tombstone = new Message(id, convo, from,
                                    "\u26d4 Message deleted", ts, false, null, null);
                            tombstone.setDeleted(true);
                            if (!isExpired(tombstone)) {
                                knownIds.add(id);
                                if (ts > latestKnownTimestamp) latestKnownTimestamp = ts;
                                adapter.appendMessage(tombstone);
                                newMessageAdded = true;
                            }
                            final Message finalTombstone = tombstone;
                            dbExecutor.execute(() ->
                                AppDatabase.getInstance(ChatMediaActivity.this)
                                    .messageDao().insert(finalTombstone));
                        }
                        continue; // skip all further ADDED processing for this doc
                    }

                    // F-07: O(1) duplicate check via HashSet (was O(n) for-loop over adapter list)
                    boolean exists = knownIds.contains(id);
                    boolean isPlaceholder = false;
                    if (exists) {
                        // Check if it's a placeholder queued for retry (small list, usually 0)
                        for (Message p : pendingDecryptQueue) {
                            if (id.equals(p.getId())) { isPlaceholder = true; break; }
                        }
                        if (!isPlaceholder) {
                            // Already decrypted correctly — just sync status tick
                            String fsStatus = dc.getDocument().getString("status");
                            if (fsStatus != null) {
                                adapter.updateMessage(id, msg -> msg.setStatus(fsStatus));
                            } else {
                                adapter.updateMessage(id, msg -> {
                                    if ("pending".equals(msg.getStatus())) msg.setStatus("sent");
                                });
                            }
                            continue;
                        }
                    }

                    Boolean isEncFlag = dc.getDocument().getBoolean("isEncrypted");
                    boolean wasEncrypted = Boolean.TRUE.equals(isEncFlag);
                    Long sigTypeLong = dc.getDocument().getLong("sigType");
                    int  sigType     = sigTypeLong != null ? sigTypeLong.intValue() : 0;

                    String displayText = text;
                    // §3.4 fix: placeholder text must NOT be persisted to Room — doing so
                    // permanently stores "[Waiting…]" / "[Decryption failed]" as the message body.
                    boolean shouldPersist = true;

                    if (wasEncrypted && text != null && !text.isEmpty()) {
                        boolean isSignalMsg = (sigType == CiphertextMessage.WHISPER_TYPE
                                              || sigType == CiphertextMessage.PREKEY_TYPE);
                        if (isSignalMsg) {
                            // Signal Protocol message: queue for async decryption on dbExecutor
                            Message pending = new Message(id, convo, from, text, ts, true, mUrl, mType);
                            if (rpId  != null) pending.setReplyToId(rpId);
                            if (rpPrv != null) pending.setReplyPreview(rpPrv);
                            if (expAt != null) pending.setExpiresAt(expAt);
                            if (mKey  != null) pending.setMediaKey(mKey);
                            pending.forwarded = Boolean.TRUE.equals(fwdFlag);
                            String fsStatus = dc.getDocument().getString("status");
                            if (fsStatus != null) pending.setStatus(fsStatus);
                            pendingDecryptQueue.add(pending);
                            queuedSigTypes.put(id, sigType);
                            displayText     = "[Decrypting\u2026]";
                            shouldPersist   = false;
                            signalMsgQueued = true;
                        } else {
                            // sigType == 0: message pre-dates Signal Protocol.
                            Log.w(TAG, "listenForMessages: sigType=0 msg=" + id + " — legacy, not decryptable");
                            displayText   = "[Legacy message — not decryptable]";
                            shouldPersist = false;
                        }
                    }

                    String statusFromFs = dc.getDocument().getString("status");
                    // Bug E fix: store isEncrypted=false so ForwardMessageHelper doesn't
                    // double-decrypt the already-decrypted displayText.
                    Message m = new Message(id, convo, from, displayText, ts, false, mUrl, mType);
                    if (rpId      != null) m.setReplyToId(rpId);
                    if (rpPrv     != null) m.setReplyPreview(rpPrv);
                    if (expAt     != null) m.setExpiresAt(expAt);
                    if (mKey      != null) m.setMediaKey(mKey);
                    if (statusFromFs != null) m.setStatus(statusFromFs);
                    m.forwarded = Boolean.TRUE.equals(fwdFlag);
                    // Read album + caption fields (never encrypted, safe to read directly)
                    String fsMediaItems = dc.getDocument().getString("mediaItems");
                    if (fsMediaItems != null && !fsMediaItems.isEmpty()) m.setMediaItems(fsMediaItems);
                    String fsCaption = dc.getDocument().getString("caption");
                    if (fsCaption != null && !fsCaption.isEmpty()) m.setCaption(fsCaption);
                    // Populate waveform bars for voice messages from Firestore amplitudes field
                    if ("voice".equals(mType)) {
                        Object rawAmps = dc.getDocument().get("amplitudes");
                        if (rawAmps instanceof java.util.List) {
                            java.util.List<Integer> amps = new java.util.ArrayList<>();
                            for (Object o : (java.util.List<?>) rawAmps) {
                                if (o instanceof Long) amps.add(((Long) o).intValue());
                                else if (o instanceof Number) amps.add(((Number) o).intValue());
                            }
                            if (!amps.isEmpty()) m.setWaveAmplitudes(amps);
                        }
                        Object rawDur = dc.getDocument().get("durationMs");
                        if (rawDur instanceof Number) m.setDurationMs(((Number) rawDur).intValue());
                    }
                    if (isExpired(m)) continue;

                    if (isPlaceholder) {
                        // Replace "[Decryption failed]" / "[Waiting…]" in-place in the adapter
                        final Message finalM = m;
                        adapter.updateMessage(id, existing -> {
                            existing.setText(finalM.getText());
                            existing.setEncrypted(false);
                            if (finalM.getStatus() != null) existing.setStatus(finalM.getStatus());
                        });
                        // Remove from pendingDecryptQueue — it's been resolved
                        pendingDecryptQueue.removeIf(p -> id.equals(p.getId()));
                    } else {
                        knownIds.add(id);
                        if (ts > latestKnownTimestamp) latestKnownTimestamp = ts;
                        adapter.appendMessage(m);
                        newMessageAdded = true;
                        if (!myUid.equals(from)) newIncoming.add(m);
                    }
                    if (shouldPersist) saveToRoom(m);

                } else if (dc.getType() == DocumentChange.Type.MODIFIED) {
                    // Handle status updates (ticks), reaction updates, edited messages,
                    // and cross-device deletes.
                    String  id            = dc.getDocument().getString("id");
                    Boolean deletedForAll = dc.getDocument().getBoolean("deletedForAll");

                    // Cross-device delete: sender called "Delete for everyone"
                    // F42 fix: show a tombstone instead of silently removing the message
                    // so both parties can see that a message was deleted.
                    if (Boolean.TRUE.equals(deletedForAll) && id != null) {
                        final String fDelId = id;
                        runOnUiThread(() -> adapter.updateMessage(fDelId, m -> {
                            m.setText("\u26d4 Message deleted");
                            m.setMediaUrl(null);
                            m.setMediaType(null);
                            m.setDeleted(true);
                        }));
                        dbExecutor.execute(() ->
                            AppDatabase.getInstance(ChatMediaActivity.this)
                                .messageDao().markTombstone(fDelId));
                        com.duoshield.app.backup.BackupManager.markDeleted(fDelId);
                        continue; // skip reaction/status/edit processing for this doc
                    }

                    String  reaction   = dc.getDocument().getString("reaction");
                    String  status     = dc.getDocument().getString("status");
                    Boolean isEdited   = dc.getDocument().getBoolean("edited");

                    // Parse readAt — server timestamp written by DeliveryReceiptHelper.markRead()
                    com.google.firebase.Timestamp readAtTs =
                            dc.getDocument().getTimestamp("readAt");
                    final long readAtMs = readAtTs != null ? readAtTs.toDate().getTime() : 0L;

                    if (id != null) {
                        final String finalReaction = reaction;
                        final String finalStatus   = status;
                        adapter.updateMessage(id, msg -> {
                            if (finalReaction != null) msg.setReaction(finalReaction);
                            if (finalStatus   != null) msg.setStatus(finalStatus);
                            if (readAtMs > 0)          msg.setReadAt(readAtMs);
                        });
                        // Persist status to Room so ticks survive app restarts.
                        if (finalStatus != null) {
                            final String persistId     = id;
                            final String persistStatus = finalStatus;
                            dbExecutor.execute(() ->
                                AppDatabase.getInstance(ChatMediaActivity.this)
                                        .messageDao().updateStatus(persistId, persistStatus));
                        }

                        // B-3: Re-decrypt edited messages from the partner.
                        // Own edits are already applied in showEditDialog() +
                        // EditMessageHelper (which also persists to Room).
                        if (Boolean.TRUE.equals(isEdited)) {
                            Boolean isEnc    = dc.getDocument().getBoolean("isEncrypted");
                            String  cipher   = dc.getDocument().getString("text");
                            Long    stLong   = dc.getDocument().getLong("sigType");
                            String  sender   = dc.getDocument().getString("sender");
                            int     sigType  = stLong != null ? stLong.intValue() : 0;
                            boolean isSignal = sigType == org.signal.libsignal.protocol.message.CiphertextMessage.WHISPER_TYPE
                                           || sigType == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE;
                            if (Boolean.TRUE.equals(isEnc) && isSignal
                                    && cipher != null && sender != null
                                    && !myUid.equals(sender)) {
                                final String fCipher  = cipher;
                                final String fSender  = sender;
                                final String fId      = id;
                                final int    fSigType = sigType;
                                dbExecutor.execute(() -> {
                                    try {
                                        String plain = SignalCipherHelper.decrypt(
                                                ChatMediaActivity.this, fSender, fCipher, fSigType);
                                        runOnUiThread(() -> adapter.updateMessage(fId, m -> {
                                            m.setText(plain);
                                            m.setEncrypted(false);
                                        }));
                                        AppDatabase.getInstance(ChatMediaActivity.this)
                                                .messageDao().updateText(fId, plain);
                                    } catch (Exception ex) {
                                        Log.w(TAG, "MODIFIED edit re-decrypt failed for " + fId, ex);
                                    }
                                });
                            }
                        }
                    }
                }
            }

            // Async-decrypt any Signal Protocol messages queued in this snapshot
            if (signalMsgQueued) retryPendingDecryption();

            // Whether the user was already parked at the bottom BEFORE this batch landed.
            // Measured here, not after the scroll below, because the scroll itself would
            // otherwise make every batch look like "user was at the bottom".
            boolean wasAtBottomBeforeBatch = true;
            if (newMessageAdded) {
                int last = adapter.getItemCount() - 1;
                if (last >= 0) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    int lastVisible = lm != null ? lm.findLastVisibleItemPosition() : last;
                    wasAtBottomBeforeBatch = (last - lastVisible) <= 3;
                    // Follow the conversation only when the user is already near the bottom.
                    // The previous else-branch called scrollToPosition(last) unconditionally,
                    // which yanked a user reading back through history down to the newest
                    // message the instant one arrived — and made the unread badge pointless,
                    // since nothing could ever accumulate behind the viewport. Someone
                    // scrolled up now keeps their place and gets the badge + FAB instead.
                    if (wasAtBottomBeforeBatch) {
                        recyclerView.smoothScrollToPosition(last);
                    }
                }
            }

            // Badge counts partner messages only: our own sends are never "unread", and the
            // auto-scroll above already carries the user to them.
            if (!newIncoming.isEmpty() && !wasAtBottomBeforeBatch) {
                addUnreadIfScrolledUp(newIncoming.size());
            }

            // Chat is in the foreground → user can see these messages → mark as "read"
            // (teal double tick for the sender). Background delivery uses
            // DuoShieldMessagingService.acknowledgeDelivery() via FCM data payload.
            if (!newIncoming.isEmpty()) {
                DeliveryReceiptHelper.markRead(conversationId, newIncoming, myUid);

                // Partner is actively in the chat (they just sent messages), which
                // proves they received our previous messages. Locally advance any of
                // OUR messages still stuck on "sent" → "delivered" so the UI reflects
                // reality without waiting for the FCM delivery receipt to arrive.
                final List<String> sentIds = new ArrayList<>();
                for (Message m : adapter.getMessages()) {
                    if (myUid.equals(m.getSender()) && "sent".equals(m.getStatus())) {
                        sentIds.add(m.getId());
                    }
                }
                if (!sentIds.isEmpty()) {
                    // batchUpdateStatus: single notifyItemChanged per row vs N adapter.updateMessage() calls.
                    adapter.batchUpdateStatus(sentIds, "delivered");
                    // Persist to Firestore so ticks survive app restarts
                    DeliveryReceiptHelper.markDeliveredByIds(conversationId, sentIds);
                }
            }
        });
    }

    // ══════════════════════════════════════════════════════════════
    // PINNING
    // ══════════════════════════════════════════════════════════════

    private void refreshPinnedBanner() {
        if (pinnedBanner == null) return;
        if (pinnedList.isEmpty()) { pinnedBanner.setVisibility(View.GONE); return; }
        pinnedBanner.setVisibility(View.VISIBLE);
        if (pinnedViewIdx >= pinnedList.size()) pinnedViewIdx = 0;
        Map<String, Object> pin = pinnedList.get(pinnedViewIdx);
        Object preview = pin.get("preview");
        if (pinnedText  != null) pinnedText.setText(preview != null ? preview.toString() : "Pinned message");
        if (pinnedCount != null) pinnedCount.setText(pinnedList.size() > 1 ? (pinnedViewIdx + 1) + "/" + pinnedList.size() : "");
    }

    private void cycleAndScrollToPin() {
        if (pinnedList.isEmpty()) return;
        pinnedViewIdx = (pinnedViewIdx + 1) % pinnedList.size();
        refreshPinnedBanner();
        Map<String, Object> pin = pinnedList.get(pinnedViewIdx);
        Object targetId = pin.get("id");
        if (targetId == null) return;
        List<Message> msgs = adapter.getMessages();
        for (int i = 0; i < msgs.size(); i++) {
            if (targetId.toString().equals(msgs.get(i).getId())) {
                recyclerView.smoothScrollToPosition(i); break;
            }
        }
    }

    private void pinMessage(Message msg) {
        for (Map<String, Object> p : pinnedList)
            if (msg.getId().equals(p.get("id"))) { Toast.makeText(this, R.string.action_already_pinned, Toast.LENGTH_SHORT).show(); return; }
        if (pinnedList.size() >= MAX_PINS) { Toast.makeText(this, getString(R.string.action_max_pins, MAX_PINS), Toast.LENGTH_SHORT).show(); return; }
        // F39 fix: never write plaintext message text into Firestore pinnedMessages[]
        Map<String, Object> entry = new HashMap<>(); entry.put("id", msg.getId());
        db.collection("chats").document(conversationId)
          .update("pinnedMessages", FieldValue.arrayUnion(entry))
          .addOnSuccessListener(v -> Toast.makeText(this, R.string.action_pinned, Toast.LENGTH_SHORT).show())
          .addOnFailureListener(ex -> {
              Map<String, Object> d = new HashMap<>(); d.put("pinnedMessages", Arrays.asList(entry));
              db.collection("chats").document(conversationId)
                .set(d, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(v2 -> Toast.makeText(this, R.string.action_pinned, Toast.LENGTH_SHORT).show());
          });
    }

    private void unpinMessage(Message msg) {
        Map<String, Object> toRemove = null;
        for (Map<String, Object> p : pinnedList) if (msg.getId().equals(p.get("id"))) { toRemove = p; break; }
        if (toRemove == null) { Toast.makeText(this, R.string.action_not_pinned, Toast.LENGTH_SHORT).show(); return; }
        db.collection("chats").document(conversationId)
          .update("pinnedMessages", FieldValue.arrayRemove(toRemove))
          .addOnSuccessListener(v -> Toast.makeText(this, R.string.action_unpinned, Toast.LENGTH_SHORT).show());
    }

    private boolean isPinned(Message msg) {
        for (Map<String, Object> p : pinnedList) if (msg.getId().equals(p.get("id"))) return true;
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // MESSAGE ACTION DIALOG
    // ══════════════════════════════════════════════════════════════

    private void showMessageActionDialog(Message msg) {
        boolean pinned  = isPinned(msg);
        boolean mine    = myUid != null && myUid.equals(msg.getSender());
        boolean hasText = msg.getText() != null && !msg.getText().isEmpty();
        boolean canEdit = mine && hasText
                && EditMessageHelper.canEdit(msg.getTimestamp(), msg.getSender(), myUid);

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.view.View root = getLayoutInflater().inflate(
                R.layout.bottom_sheet_message_actions, null);
        sheet.setContentView(root);
        
        // WhatsApp style: Transparent background for the bottom sheet so it feels like a floating menu
        android.view.View parent = (android.view.View) root.getParent();
        if (parent != null) {
            parent.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        com.google.android.material.bottomsheet.BottomSheetBehavior.from(parent)
                .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);

        // ── Quick reactions row ────────────────────────────────────────────────
        android.widget.LinearLayout reactionRow = root.findViewById(R.id.reactionRow);
        String[] quickEmojis = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
        for (String emoji : quickEmojis) {
            android.widget.TextView tv = new android.widget.TextView(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            tv.setText(emoji);
            tv.setTextSize(28f);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(0, 12, 0, 12);
            tv.setOnClickListener(v -> {
                db.collection("chats").document(conversationId)
                  .collection("messages").document(msg.getId())
                  .update("reaction", emoji);
                adapter.updateMessage(msg.getId(), m -> m.setReaction(emoji));
                sheet.dismiss();
            });
            reactionRow.addView(tv);
        }

        // ── Action rows ────────────────────────────────────────────────────────
        android.widget.LinearLayout actions = root.findViewById(R.id.actionsContainer);

        addMsgAction(actions, R.drawable.ic_reply,
                "Reply", false, sheet, () -> enterReplyMode(msg));

        if (hasText) addMsgAction(actions, R.drawable.ic_copy,
                "Copy", false, sheet, () -> copyMessage(msg));

        if (canEdit) addMsgAction(actions, R.drawable.ic_edit,
                "Edit", false, sheet, () -> showEditDialog(msg));

        addMsgAction(actions, R.drawable.ic_forward,
                "Forward", false, sheet,
                () -> ForwardMessageHelper.forward(this, msg, conversationId, myUid, partnerUid));

        addMsgAction(actions, R.drawable.ic_pin,
                pinned ? "Unpin" : "Pin", false, sheet,
                () -> { if (pinned) unpinMessage(msg); else pinMessage(msg); });

        addMsgAction(actions, R.drawable.ic_star,
                msg.starred ? "Unstar" : "Star", false, sheet,
                () -> toggleStar(msg));

        addMsgAction(actions, R.drawable.ic_delete,
                "Delete locally", true, sheet, () -> {
                    final String dId = msg.getId();
                    adapter.removeMessage(dId);
                    dbExecutor.execute(() ->
                        AppDatabase.getInstance(ChatMediaActivity.this)
                            .messageDao().deleteMessage(dId));
                    com.duoshield.app.backup.BackupManager.markDeleted(dId);
                });

        // F21 fix: only the original sender may delete for everyone.
        if (mine) addMsgAction(actions, R.drawable.ic_delete,
                "Delete for everyone", true, sheet,
                () -> deleteForEveryone(msg));

        sheet.show();
    }

    private void addMsgAction(android.widget.LinearLayout container,
                               int iconRes, String label, boolean danger,
                               com.google.android.material.bottomsheet.BottomSheetDialog sheet,
                               Runnable action) {
        android.view.View row = getLayoutInflater().inflate(
                R.layout.item_message_action, container, false);
        android.widget.ImageView icon = row.findViewById(R.id.ivActionIcon);
        android.widget.TextView  text = row.findViewById(R.id.tvActionLabel);
        icon.setImageResource(iconRes);
        text.setText(label);
        int color = danger ? 0xFFD96A7C : getColor(R.color.ds_text_primary);
        icon.setColorFilter(color);
        text.setTextColor(color);
        row.setOnClickListener(v -> { action.run(); sheet.dismiss(); });
        container.addView(row);
    }

    private void copyMessage(Message msg) {
        if (msg.getText() == null || msg.getText().isEmpty()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("message", msg.getText());
            com.duoshield.app.util.ClipboardHelper.markSensitive(clip); // S08-L2
            cm.setPrimaryClip(clip);
        }
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void showEditDialog(Message msg) {
        EditText input = new EditText(this);
        input.setText(msg.getText());
        input.setSelection(input.getText().length());
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_message_title)
            .setView(input)
            .setPositiveButton(R.string.save, (d, w) -> {
                String newText = input.getText().toString().trim();
                if (!newText.isEmpty()) {
                    EditMessageHelper.editMessage(this, conversationId, msg.getId(), partnerUid, newText);
                    adapter.updateMessage(msg.getId(), m -> m.setText(newText));
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void toggleStar(Message msg) {
        boolean newState = !msg.starred;
        adapter.updateMessage(msg.getId(), m -> m.starred = newState);
        dbExecutor.execute(() -> {
            AppDatabase.getInstance(this).messageDao().updateStarred(msg.getId(), newState);
        });
        Toast.makeText(this, newState ? "Message starred" : "Message unstarred", Toast.LENGTH_SHORT).show();
    }

    private void deleteForEveryone(Message msg) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_for_everyone_title)
            .setMessage(R.string.delete_for_everyone_message)
            .setPositiveButton(R.string.delete, (d, w) -> {
                // Signal both devices via Firestore — MODIFIED listener on both sides cleans up
                db.collection("chats").document(conversationId)
                  .collection("messages").document(msg.getId())
                  .update("deletedForAll", true)
                  .addOnSuccessListener(v -> {
                      // F42 fix: show tombstone immediately so sender doesn't wait for the echo.
                      final String dfeId = msg.getId();
                      adapter.updateMessage(dfeId, m -> {
                          m.setText("\u26d4 Message deleted");
                          m.setMediaUrl(null);
                          m.setMediaType(null);
                          m.setDeleted(true);
                      });
                      dbExecutor.execute(() ->
                          AppDatabase.getInstance(ChatMediaActivity.this)
                              .messageDao().markTombstone(dfeId));
                      com.duoshield.app.backup.BackupManager.markDeleted(dfeId);
                  })
                  .addOnFailureListener(e -> runOnUiThread(() ->
                      Toast.makeText(this, R.string.action_delete_failed, Toast.LENGTH_SHORT).show()));
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }


    // ══════════════════════════════════════════════════════════════
    // TYPING
    // ══════════════════════════════════════════════════════════════

    // Typing indicator is now handled by typingThrottle.setTyping(true) in TextWatcher

    // ══════════════════════════════════════════════════════════════
    // SCROLL AFFORDANCES — unread badge + sticky date pill
    // ══════════════════════════════════════════════════════════════

    /**
     * Sets the "arrived while you were scrolled up" count and syncs the badge.
     *
     * <p>Counts above 99 render as "99+" so the badge never grows wide enough to spill past
     * the FAB it is anchored to. Passing 0 hides the badge entirely rather than drawing an
     * empty circle.
     */
    private void setUnreadWhileScrolledUp(int count) {
        unreadWhileScrolledUp = Math.max(0, count);
        if (tvUnreadBadge == null) return;
        if (unreadWhileScrolledUp == 0) {
            tvUnreadBadge.setVisibility(View.GONE);
            if (btnScrollToBottom != null) {
                btnScrollToBottom.setContentDescription(
                        getString(R.string.scroll_to_latest));
            }
            return;
        }
        tvUnreadBadge.setText(unreadWhileScrolledUp > 99
                ? "99+" : String.valueOf(unreadWhileScrolledUp));
        tvUnreadBadge.setVisibility(View.VISIBLE);
        // Announce the backlog for screen readers via the button the badge belongs to —
        // the badge itself is decorative once the FAB carries the count in its description.
        if (btnScrollToBottom != null) {
            btnScrollToBottom.setContentDescription(getResources().getQuantityString(
                    R.plurals.scroll_to_latest_with_unread,
                    unreadWhileScrolledUp, unreadWhileScrolledUp));
        }
    }

    /**
     * Adds {@code delta} newly-arrived messages to the unread badge.
     *
     * <p>Callers are responsible for only invoking this when the user was scrolled away from
     * the bottom when the batch landed — the check cannot live in here, because by the time
     * the snapshot handler finishes, any auto-follow scroll has already run and the viewport
     * would report "at the bottom" for every batch.
     */
    private void addUnreadIfScrolledUp(int delta) {
        if (delta <= 0) return;
        setUnreadWhileScrolledUp(unreadWhileScrolledUp + delta);
    }

    /**
     * Shows/updates the floating date pill for whichever day owns the top of the viewport.
     *
     * <p>Hidden when the list's own inline date header for that day is the top visible row —
     * otherwise the label would appear twice, stacked. The label text comes from
     * {@link MessageAdapter#getDateLabelFor(int)}, which reads the header already placed in
     * the list rather than re-deriving a "now"-relative label per frame.
     */
    private void updateStickyDatePill(LinearLayoutManager lm) {
        if (stickyDatePill == null || adapter == null || lm == null) return;
        int first = lm.findFirstVisibleItemPosition();
        if (first < 0) { hideStickyDatePill(); return; }
        if (adapter.isDateHeaderAt(first)) { hideStickyDatePill(); return; }

        String label = adapter.getDateLabelFor(first);
        if (label == null) { hideStickyDatePill(); return; }
        if (!label.equals(stickyDateLabelShown)) {
            stickyDatePill.setText(label);
            stickyDateLabelShown = label;
        }
        if (stickyDatePill.getVisibility() != View.VISIBLE) {
            stickyDatePill.setVisibility(View.VISIBLE);
            stickyDatePill.animate().cancel();
            stickyDatePill.animate().alpha(1f).setDuration(120).start();
        }
    }

    /** Fades the sticky date pill out once scrolling settles. */
    private void hideStickyDatePill() {
        if (stickyDatePill == null || stickyDatePill.getVisibility() != View.VISIBLE) return;
        stickyDatePill.animate().cancel();
        stickyDatePill.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    stickyDatePill.setVisibility(View.GONE);
                    stickyDateLabelShown = null;
                })
                .start();
    }

    // ══════════════════════════════════════════════════════════════
    // REPLY
    // ══════════════════════════════════════════════════════════════

    /**
     * Called when the user taps a reply-quote strip inside a bubble.
     * Scrolls the RecyclerView to the original message and briefly highlights it.
     */
    private void scrollToAndHighlight(String originalMsgId) {
        if (adapter == null || recyclerView == null || originalMsgId == null) return;
        int pos = adapter.findPositionById(originalMsgId);
        if (pos < 0) {
            Toast.makeText(this, "Original message not in view", Toast.LENGTH_SHORT).show();
            return;
        }
        recyclerView.scrollToPosition(pos);
        // Post so the scroll completes before we trigger the highlight redraw
        recyclerView.post(() -> adapter.highlightMessage(originalMsgId));
    }

    private void enterReplyMode(Message msg) {
        pendingReplyId      = msg.getId();
        // Text messages show their own text; every other type gets a specific label
        // (via MessageLabelHelper) instead of the previous generic, unhelpful "[media]"
        // placeholder that didn't tell the user what they were replying to. This same
        // string is stored as the message's replyPreview and rendered verbatim by
        // MessageAdapter in the quoted strip once the reply is sent.
        pendingReplyPreview = com.duoshield.app.util.MessageLabelHelper.describe(msg);
        replyPreviewBarText.setText("↩  " + pendingReplyPreview);
        replyPreviewBar.setVisibility(View.VISIBLE);
        messageInput.requestFocus();
    }

    private void clearReplyMode() {
        pendingReplyId = null; pendingReplyPreview = null;
        replyPreviewBar.setVisibility(View.GONE);
    }

    // ══════════════════════════════════════════════════════════════
    // DISAPPEARING / EXPIRED
    // ══════════════════════════════════════════════════════════════

    private long getDisappearMs() {
        // F26 fix: scope disappear_ms per conversation to avoid cross-conversation timer bleed
        return getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getLong("disappear_ms_" + conversationId, 0);
    }

    private void showDisappearPicker() {
        long current = getDisappearMs();

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.view.View root = getLayoutInflater().inflate(
                R.layout.bottom_sheet_timer, null);
        sheet.setContentView(root);

        // Round top corners
        com.google.android.material.bottomsheet.BottomSheetBehavior<?> behaviour =
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(
                        (android.view.View) root.getParent());
        behaviour.setState(
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);

        android.widget.LinearLayout container = root.findViewById(R.id.timerOptionsContainer);

        for (int i = 0; i < DISAPPEAR_OPTS_MS.length; i++) {
            final long ms = DISAPPEAR_OPTS_MS[i];
            android.view.View row = getLayoutInflater().inflate(
                    R.layout.item_timer_option, container, false);

            android.widget.TextView tvEmoji = row.findViewById(R.id.tvTimerEmoji);
            android.widget.TextView tvLabel = row.findViewById(R.id.tvTimerLabel);
            android.widget.ImageView ivCheck = row.findViewById(R.id.ivTimerCheck);

            tvEmoji.setText(DISAPPEAR_OPTS_EMOJI[i]);
            tvLabel.setText(DISAPPEAR_OPTS_LBL[i]);

            boolean isActive = (ms == current);
            ivCheck.setVisibility(isActive ? android.view.View.VISIBLE : android.view.View.GONE);
            if (isActive) {
                tvLabel.setTextColor(getResources().getColor(R.color.ds_accent, null));
                ivCheck.setColorFilter(getResources().getColor(R.color.ds_accent, null));
            }

            row.setOnClickListener(v -> {
                getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                        .edit().putLong("disappear_ms_" + conversationId, ms).apply();
                scheduleOrCancelDestruct(ms);
                updateDisappearBanner();
                syncDisappearToFirestore(ms);
                sheet.dismiss();
            });

            container.addView(row);
        }

        sheet.show();
    }

    /**
     * Writes {@code disappear_ms} to the Firestore chat doc so the partner's
     * {@code convListener} can pick it up and apply it on their end.
     * Also writes {@code disappear_set_by} so the partner knows the change
     * came from us (not from their own preference update).
     */
    private void syncDisappearToFirestore(long ms) {
        if (conversationId == null || myUid == null) return;
        FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
        if (!guard.canWrite(1)) return;
        Map<String, Object> update = new HashMap<>();
        update.put("disappear_ms",     ms);
        update.put("disappear_set_by", myUid);
        db.collection("chats").document(conversationId)
          .update(update)
          .addOnSuccessListener(v -> guard.recordWrites(1));
    }

    private void updateDisappearBanner() {
        long ms = getDisappearMs();

        if (tvDisappearTimer == null || disappearTimerBanner == null) return;
        if (ms <= 0) {
            disappearTimerBanner.setVisibility(View.GONE);
            return;
        }
        String label = "Unknown";
        for (int i = 0; i < DISAPPEAR_OPTS_MS.length; i++) {
            if (DISAPPEAR_OPTS_MS[i] == ms) { label = DISAPPEAR_OPTS_LBL[i]; break; }
        }
        tvDisappearTimer.setText("\u23F1  Messages disappear after " + label);
        disappearTimerBanner.setVisibility(View.VISIBLE);
    }

    /**
     * Shows or hides the safety-number-changed banner based on the flag written
     * by {@link com.duoshield.app.crypto.signal.DuoShieldSignalStore#saveIdentity}
     * when the partner's Signal identity key differs from the previously trusted one.
     *
     * <p>VERIFY → hides banner for this session + opens {@link KeyFingerprintActivity};
     * the {@code safety_num_changed_} flag is cleared only after a successful QR-scan match
     * inside {@link KeyFingerprintActivity} (F23 fix — not cleared on tap alone).
     *
     * <p>UX-2 fix: ✕ dismiss used to hide the banner instantly, exactly like VERIFY did,
     * which taught users that tapping ✕ was as good as verifying. It now opens a
     * confirmation explaining that dismissing verifies nothing, and offers "Verify now" as
     * the primary action. The flag always persists until a successful QR verification, so
     * the banner returns on the next {@code onResume()} either way.
     */
    private void checkSafetyNumberBanner() {
        if (safetyNumberBanner == null || partnerUid == null) return;
        boolean changed = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("safety_num_changed_" + partnerUid, false);
        if (!changed) {
            safetyNumberBanner.setVisibility(View.GONE);
            return;
        }
        safetyNumberBanner.setVisibility(View.VISIBLE);
        android.widget.Button btnVerify = safetyNumberBanner.findViewById(R.id.btnVerifySafetyNumber);
        android.view.View btnDismiss   = safetyNumberBanner.findViewById(R.id.btnDismissSafetyNumber);
        if (btnVerify != null) btnVerify.setOnClickListener(v -> {
            // F23 fix: do NOT clear the safety_num_changed flag here.
            // The flag is cleared only after a successful QR-scan match inside
            // KeyFingerprintActivity (via EXTRA_CLEAR_SAFETY_NUM_ON_MATCH).
            // The banner is hidden locally for the session (same as dismiss), but
            // it reappears on the next onResume() if the user never completed QR verification.
            safetyNumberBanner.setVisibility(View.GONE);
            startActivity(new Intent(this, KeyFingerprintActivity.class)
                    .putExtra("partner_uid", partnerUid)
                    .putExtra("clear_safety_num_on_match", true));
        });
        if (btnDismiss != null) btnDismiss.setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.safety_dismiss_title)
                        .setMessage(R.string.safety_dismiss_body)
                        // Verification is the action we want, so it gets the positive slot.
                        .setPositiveButton(R.string.safety_dismiss_verify, (d, w) -> {
                            safetyNumberBanner.setVisibility(View.GONE);
                            startActivity(new Intent(this, KeyFingerprintActivity.class)
                                    .putExtra("partner_uid", partnerUid)
                                    .putExtra("clear_safety_num_on_match", true));
                        })
                        .setNegativeButton(R.string.safety_dismiss_confirm, (d, w) ->
                                safetyNumberBanner.setVisibility(View.GONE))
                        .show());
    }

    // 4.2 fix: refactor to use SelfDestructScheduler exclusively instead of duplicating
    // WorkManager logic here. This centralizes scheduling logic and reduces code duplication.
    private void scheduleOrCancelDestruct(long ms) {
        // F26 fix: pass conversationId so scheduler checks the per-conversation pref
        com.duoshield.app.util.SelfDestructScheduler.schedule(this, conversationId);
    }

    private boolean isExpired(Message m) {
        return m.getExpiresAt() > 0 && System.currentTimeMillis() > m.getExpiresAt();
    }

    // ══════════════════════════════════════════════════════════════
    // WALLPAPER
    // ══════════════════════════════════════════════════════════════

    private void applyWallpaper() {
        if (recyclerView == null) return;
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // ChatThemeHelper takes precedence over the legacy colour/image wallpaper.
        // Only fall through to the old system when the theme is "default".
        String theme = prefs.getString(com.duoshield.app.util.ChatThemeHelper.PREF_KEY,
                com.duoshield.app.util.ChatThemeHelper.THEME_DEFAULT);
        if (!theme.equals(com.duoshield.app.util.ChatThemeHelper.THEME_DEFAULT)) {
            com.duoshield.app.util.ChatThemeHelper.apply(recyclerView, prefs);
            return;
        }

        switch (prefs.getString("wallpaper_type", "none")) {
            case "color":
                recyclerView.setBackgroundColor(prefs.getInt("wallpaper_color", Color.TRANSPARENT)); break;
            case "image":
                String u = prefs.getString("wallpaper_uri", null);
                if (u != null) Glide.with(this).load(Uri.parse(u)).centerCrop()
                    .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                        @Override public void onResourceReady(android.graphics.drawable.Drawable r, com.bumptech.glide.request.transition.Transition<? super android.graphics.drawable.Drawable> t) { recyclerView.setBackground(r); }
                        @Override public void onLoadCleared(android.graphics.drawable.Drawable p) { recyclerView.setBackground(null); }
                    });
                break;
            default: recyclerView.setBackground(null);
        }
    }

    private void showWallpaperDialog() {
        String[] opts   = {"None", "Soft Blue", "Forest Green", "Dark Night", "Blush Pink", "Pick from gallery…"};
        int[]    colors = {0, 0xFFDCEEFB, 0xFFD7EDDC, 0xFF191620, 0xFFFDE8EC};
        new MaterialAlertDialogBuilder(this).setTitle("Chat wallpaper")
            .setItems(opts, (d, w) -> {
                SharedPreferences.Editor ed = getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit();
                if (w == opts.length - 1) pickWallpaperLauncher.launch("image/*");
                else if (w == 0) { ed.putString("wallpaper_type", "none").apply(); applyWallpaper(); }
                else { ed.putString("wallpaper_type", "color").putInt("wallpaper_color", colors[w]).apply(); applyWallpaper(); }
            }).show();
    }

    // ══════════════════════════════════════════════════════════════
    // BADGE
    // ══════════════════════════════════════════════════════════════

    private void clearBadge() {
        NotificationManagerCompat.from(this).cancelAll();
        getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit().putInt("badge_count", 0).apply();
    }

    // ══════════════════════════════════════════════════════════════
    // SEND MESSAGE
    // ══════════════════════════════════════════════════════════════

    private void showMediaTypePopup() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_DuoShield_BottomSheet);
        android.view.View view = getLayoutInflater().inflate(R.layout.bottom_sheet_media_picker, null);
        sheet.setContentView(view);

        view.findViewById(R.id.mediaPickerImage).setOnClickListener(v -> {
            sheet.dismiss();
            pickImageLauncher.launch("image/*");
        });
        view.findViewById(R.id.mediaPickerVideo).setOnClickListener(v -> {
            sheet.dismiss();
            pickVideoLauncher.launch("video/*");
        });
        view.findViewById(R.id.mediaPickerCamera).setOnClickListener(v -> {
            sheet.dismiss();
            launchCameraCapture();
        });
        view.findViewById(R.id.mediaPickerContact).setOnClickListener(v -> {
            sheet.dismiss();
            sendContactCard();
        });

        sheet.show();
    }

    private void launchMediaPreview(java.util.ArrayList<Uri> uris, String mediaType) {
        if (uris == null || uris.isEmpty()) return;
        Intent preview = new Intent(this, MediaSendPreviewActivity.class);
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (Uri uri : uris) values.add(uri.toString());
        preview.putStringArrayListExtra(MediaSendPreviewActivity.EXTRA_URIS, values);
        preview.putExtra(MediaSendPreviewActivity.EXTRA_URI, values.get(0));
        preview.putExtra(MediaSendPreviewActivity.EXTRA_MEDIA_TYPE, mediaType);
        mediaSendPreviewLauncher.launch(preview);
    }

    private java.util.List<Uri> toUris(java.util.ArrayList<String> values) {
        java.util.ArrayList<Uri> result = new java.util.ArrayList<>();
        if (values == null) return result;
        for (String value : values) {
            if (value != null && !value.isEmpty()) result.add(Uri.parse(value));
        }
        return result;
    }

    /**
     * Requests CAMERA permission if needed, creates a FileProvider-backed temp file,
     * and launches the system camera via {@link #takePictureLauncher}.
     * The photo is captured into the temp URI so the result is received by the app
     * (unlike a plain startActivity which discards the result).
     */
    private void launchCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PHOTO);
            return;
        }
        try {
            // S08-M2: write into the FileProvider-scoped shared/camera/ subdir
            // rather than the cache root, so the grant below is not scoped to
            // the whole cache directory.
            File photoFile = File.createTempFile(
                    "cam_", ".jpg", com.duoshield.app.util.SharedCacheDir.camera(this));
            cameraPhotoUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".provider", photoFile);
            takePictureLauncher.launch(cameraPhotoUri);
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to create camera temp file", e);
            Toast.makeText(this, "Camera error — could not create photo file",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Upload progress helpers (WhatsApp-style thumbnail preview)
    // ──────────────────────────────────────────────────────────────────────────

    /** Shows the upload progress container with a thumbnail preview of what's being sent. */
    private void showUploadProgress(Uri previewUri, String mediaType) {
        if (isFinishing() || isDestroyed()) return;
        uploadProgressContainer.setVisibility(View.VISIBLE);
        if (tvUploadPct != null) {
            tvUploadPct.setText("Preparing…");
        }
        if (ivUploadThumb != null && previewUri != null) {
            ivUploadThumb.setVisibility(View.VISIBLE);
            if (uploadThumbDim  != null) uploadThumbDim.setVisibility(View.VISIBLE);
            if (uploadPlainBg   != null) uploadPlainBg.setVisibility(View.GONE);
            loadUploadPreview(previewUri, mediaType, ivUploadThumb);
        }
    }

    /** Hides the upload progress container and resets thumbnail state. */
    private void hideUploadContainer() {
        if (isFinishing() || isDestroyed()) return;
        uploadProgressContainer.setVisibility(View.GONE);
        if (ivUploadThumb != null) {
            ivUploadThumb.setVisibility(View.GONE);
            if (uploadThumbDim != null) uploadThumbDim.setVisibility(View.GONE);
            if (uploadPlainBg  != null) uploadPlainBg.setVisibility(View.VISIBLE);
        }
    }

    private void loadUploadPreview(Uri uri, String mediaType, ImageView target) {
        if (uri == null || target == null) return;
        if ("video".equals(mediaType)) {
            Glide.with(this).asBitmap().load(uri)
                    .placeholder(R.drawable.bg_media_rounded)
                    .error(R.drawable.bg_media_rounded)
                    .centerCrop().into(target);
        } else {
            Glide.with(this).load(uri).centerCrop().into(target);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Album (multi-media) send — groups multiple photos/videos into one message
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Shows a caption-input dialog, then uploads all URIs and sends a single
     * album message so all photos/videos appear grouped like WhatsApp/Telegram.
     */
    private void showAlbumSendDialog(java.util.List<Uri> uris, String mediaType) {
        android.widget.EditText captionInput = new android.widget.EditText(this);
        captionInput.setHint("Add a caption (optional)");
        captionInput.setMaxLines(3);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        captionInput.setPadding(pad, pad / 2, pad, pad / 2);
        captionInput.setTextColor(0xFFFFFFFF);
        captionInput.setHintTextColor(0xFF888888);
        int label = "image".equals(mediaType) ? uris.size() : uris.size();
        String noun  = "image".equals(mediaType) ? "photos" : "videos";

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Send " + label + " " + noun)
            .setView(captionInput)
            .setPositiveButton("Send", (d, w) -> {
                String cap = captionInput.getText().toString().trim();
                startAlbumUpload(uris, mediaType, cap.isEmpty() ? null : cap);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Uploads each URI; once all complete fires a single album message. */
    private void startAlbumUpload(java.util.List<Uri> uris, String mediaType, String caption) {
        synchronized (multiUploadLock) {
            pendingMultiItems.clear();
            pendingMultiCompleted.set(0);
            pendingMultiTotal   = uris.size();
            pendingMultiCaption = caption;
        }
        // Show thumbnail of the first item while uploading
        runOnUiThread(() -> showUploadProgress(uris.get(0), mediaType));
        for (Uri uri : uris) {
            uploadAlbumItemWithRetry(uri, mediaType, 0);
        }
    }

    private void uploadAlbumItemWithRetry(Uri fileUri, String mediaType, int retryCount) {
        if (isFinishing() || isDestroyed()) return;
        if (retryCount > 3) { onAlbumItemFailed(); return; }
        if (retryCount == 0 && getFileSize(fileUri) > 500 * 1024 * 1024L) {
            Toast.makeText(this, "One file is too large (max 500 MB).", Toast.LENGTH_SHORT).show();
            onAlbumItemFailed();
            return;
        }
        String ext  = "video".equals(mediaType) ? ".mp4" : ".jpg";
        String mime = "video".equals(mediaType) ? "video/mp4" : "image/jpeg";
        String path = "media/" + conversationId + "/" + UUID.randomUUID() + ext;
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            try {
                byte[] plain = readUriBytes(fileUri);
                if (plain == null || plain.length == 0) throw new java.io.IOException("Empty file");
                if ("image".equals(mediaType)) plain = compressImage(plain);
                B2StorageHelper.EncryptedMedia enc = B2StorageHelper.encryptForUpload(plain);
                String storagePath = B2StorageHelper.uploadFile(enc.data, path, mime,
                    pct -> runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) tvUploadPct.setText(pct + "%");
                    }));
                synchronized (multiUploadLock) {
                    pendingMultiItems.add(new String[]{ storagePath, mediaType, enc.keyBase64 });
                }
                onAlbumItemComplete();
            } catch (Exception e) {
                Log.e(TAG, "Album item upload failed (attempt " + (retryCount + 1) + "): " + e.getMessage());
                if (retryCount >= 3) { onAlbumItemFailed(); return; }
                long delay = (long) (2000 * Math.pow(2, retryCount));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) uploadAlbumItemWithRetry(fileUri, mediaType, retryCount + 1);
                }, delay);
            }
        });
    }

    private void onAlbumItemComplete() {
        int done = pendingMultiCompleted.incrementAndGet();
        if (done >= pendingMultiTotal) {
            java.util.List<String[]> items;
            String caption;
            synchronized (multiUploadLock) {
                items   = new java.util.ArrayList<>(pendingMultiItems);
                caption = pendingMultiCaption;
                pendingMultiItems.clear();
                pendingMultiTotal   = 0;
                pendingMultiCaption = null;
            }
            final java.util.List<String[]> finalItems = items;
            final String finalCap = caption;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                hideUploadContainer();
                sendAlbumMessage(finalItems, finalCap);
            });
        }
    }

    private void onAlbumItemFailed() {
        int done = pendingMultiCompleted.incrementAndGet();
        if (done >= pendingMultiTotal) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                hideUploadContainer();
                Toast.makeText(this, "Some items failed to upload.", Toast.LENGTH_LONG).show();
            });
        }
    }

    /**
     * Sends a single Firestore message with all uploaded items in the mediaItems JSON array.
     * The receiver's MessageAdapter will display them as a 2×2 grid (WhatsApp/Telegram style).
     */
    private void sendAlbumMessage(java.util.List<String[]> items, String caption) {
        if (items.isEmpty()) return;
        org.json.JSONArray jsonArray = new org.json.JSONArray();
        for (String[] item : items) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("path", item[0]);
                obj.put("type", item[1]);
                obj.put("key",  item[2]);
                jsonArray.put(obj);
            } catch (org.json.JSONException ignored) {}
        }
        String mediaItemsJson = jsonArray.toString();

        String msgId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long exp = getDisappearMs() > 0 ? now + getDisappearMs() : 0;

        Message m = new Message(msgId, conversationId, myUid, "", now, false, null, "album");
        m.setExpiresAt(exp);
        m.setMediaItems(mediaItemsJson);
        if (caption != null && !caption.isEmpty()) m.setCaption(caption);
        m.setStatus("pending");
        adapter.appendMessage(m);
        knownIds.add(msgId);
        recyclerView.scrollToPosition(adapter.getItemCount() - 1);

        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId);
        doc.put("conversationId", conversationId);
        doc.put("sender", myUid);
        doc.put("text", "");
        doc.put("mediaType", "album");
        doc.put("type", "album");
        doc.put("mediaItems", mediaItemsJson);
        if (caption != null && !caption.isEmpty()) doc.put("caption", caption);
        doc.put("isEncrypted", true);
        doc.put("expiresAt", exp);
        doc.put("timestamp", FieldValue.serverTimestamp());
        doc.put("status", "sent");

        db.collection("chats").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              FirebaseCostGuard.getInstance(this).recordWrites(1);
              m.setStatus("sent");
              adapter.updateMessage(msgId, msg -> msg.setStatus("sent"));
              saveToRoom(m);
              ConversationMetaUpdater.update(ChatMediaActivity.this, conversationId, myUid,
                  partnerUid, caption != null && !caption.isEmpty()
                      ? previewFor(caption)
                      : "📷 " + items.size() + " media items");
              String noun = items.get(0)[1].equals("video") ? "videos 🎬" : "photos 🖼";
              notifyPartner("DuoShield", "Sent " + items.size() + " " + noun, msgId);
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Failed to send album message: " + e.getMessage());
              adapter.updateMessage(msgId, msg -> msg.setStatus("failed"));
              m.setStatus("failed");
              saveToRoom(m);
              Toast.makeText(this, "Failed to send album. Please try again.", Toast.LENGTH_LONG).show();
          });
    }

    private void uploadMedia(Uri fileUri, String mediaType) {
        uploadMediaWithRetry(fileUri, mediaType, 0);
    }

    // BUG-U01 fix: add retry logic for failed uploads with exponential backoff
    private void uploadMediaWithRetry(Uri fileUri, String mediaType, int retryCount) {
        if (isFinishing() || isDestroyed()) return;
        if (retryCount > 3) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                hideUploadContainer();
                Toast.makeText(ChatMediaActivity.this, "Upload failed after multiple attempts. Please check your connection.", Toast.LENGTH_LONG).show();
            });
            return;
        }

        // Reject files over 500 MB before any upload work starts.
        // Applies to both images and videos — limit raised from 20 MB.
        if (retryCount == 0) {
            long size = getFileSize(fileUri);
            if (size > 500 * 1024 * 1024L) {
                Toast.makeText(this,
                        "File is too large (max 500 MB). Please choose a smaller file.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        final Uri thumbUri = fileUri;
        final String thumbType = mediaType;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            showUploadProgress(thumbUri, thumbType);
            tvUploadPct.setText("Preparing…");
        });

        String ext  = "video".equals(mediaType) ? ".mp4" : ".jpg";
        String mime = "video".equals(mediaType) ? "video/mp4" : "image/jpeg";
        String path = "media/" + conversationId + "/" + UUID.randomUUID() + ext;

        if (executor.isShutdown()) return;
        executor.execute(() -> {
            // Videos larger than LARGE_FILE_THRESHOLD are stream-encrypted to a temp file
            // so we never hold the full plaintext + ciphertext in memory simultaneously.
            // Images always go through the in-memory path because they are compressed first.
            boolean useLargeFilePath = "video".equals(mediaType)
                    && getFileSize(fileUri) > LARGE_FILE_THRESHOLD;
            try {
                if (useLargeFilePath) {
                    // ── Streaming path: encrypt to disk → upload from disk ─────────
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) tvUploadPct.setText("Encrypting…");
                    });
                    java.io.File encTmp = java.io.File.createTempFile("enc_", ".tmp", getCacheDir());
                    try {
                        String mediaKey = B2StorageHelper.encryptUriToFile(
                                getContentResolver(), fileUri, encTmp);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) tvUploadPct.setText("0%");
                        });
                        String storagePath = B2StorageHelper.uploadFileFromDisk(
                                encTmp, path, mime,
                                pct -> runOnUiThread(() -> {
                                    if (!isFinishing() && !isDestroyed()) tvUploadPct.setText(pct + "%");
                                }));
                        final String finalMediaKey = mediaKey;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            hideUploadContainer();
                            sendMediaMessage(storagePath, mediaType, finalMediaKey, null);
                        });
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        encTmp.delete();
                    }
                } else {
                    // ── In-memory path (images + small videos ≤ 50 MB) ───────────
                    byte[] plain = readUriBytes(fileUri);
                    if (plain == null || plain.length == 0) {
                        throw new java.io.IOException("Failed to read file or file is empty");
                    }

                    // Compress images to save bandwidth — can take 200-500ms on large photos
                    if ("image".equals(mediaType)) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) tvUploadPct.setText("Compressing…");
                        });
                        plain = compressImage(plain);
                    }

                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) tvUploadPct.setText("0%");
                    });
                    B2StorageHelper.EncryptedMedia enc = B2StorageHelper.encryptForUpload(plain);
                    String storagePath = B2StorageHelper.uploadFile(
                            enc.data, path, mime,
                            pct -> runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) tvUploadPct.setText(pct + "%");
                    }));

                    final String mediaKey = enc.keyBase64;
                    final String captionToSend = pendingImageCaption;
                    pendingImageCaption = null;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        hideUploadContainer();
                        // Caption goes in the same message bubble, not as a separate message
                        sendMediaMessage(storagePath, mediaType, mediaKey, captionToSend);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "B2 media upload failed (attempt " + (retryCount + 1) + "/4): " + e.getMessage());
                final String errMsg = e.getMessage();
                if (retryCount >= 3) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        hideUploadContainer();
                        showB2ErrorDialog("Media upload failed.", errMsg);
                    });
                    return;
                }
                // Exponential backoff: 2s, 4s, 8s for better recovery
                long delayMs = (long) (2000 * Math.pow(2, retryCount));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed() && !executor.isShutdown()) {
                        uploadMediaWithRetry(fileUri, mediaType, retryCount + 1);
                    }
                }, delayMs);
            }
        });
    }

    /**
     * Shows a diagnostic dialog when a B2 upload fails.
     * Parses the error message to give specific, actionable advice.
     *
     * @param title  Short description of what failed (e.g. "Media upload failed.")
     * @param detail Raw exception message from B2StorageHelper — may be null.
     */
    private void showB2ErrorDialog(String title, String detail) {
        if (isFinishing() || isDestroyed()) return;
        String advice;
        String errorCode = "";

        if (detail != null && detail.contains("[403]")) {
            errorCode = "HTTP 403 — Permission denied";
            advice = "Your B2 application key is missing the writeFiles permission.\n\n"
                   + "Fix:\n"
                   + "1. Go to Backblaze → App Keys\n"
                   + "2. Generate a new key for your bucket\n"
                   + "3. Enable: readFiles, writeFiles, deleteFiles, listAllBucketNames\n"
                   + "4. Update the B2_APPLICATION_KEY secret in Replit and rebuild";
        } else if (detail != null && detail.contains("[404]")) {
            errorCode = "HTTP 404 — Bucket not found";
            advice = "The B2 bucket name in your secrets does not match any bucket on your account.\n\n"
                   + "Fix:\n"
                   + "1. Go to Backblaze → Buckets and copy the exact bucket name\n"
                   + "2. Update the B2_BUCKET secret in Replit (case-sensitive) and rebuild";
        } else if (detail != null && detail.contains("[401]")) {
            errorCode = "HTTP 401 — Authentication failed";
            advice = "Your B2 Key ID or Application Key is incorrect.\n\n"
                   + "Fix:\n"
                   + "1. Verify B2_KEY_ID and B2_APPLICATION_KEY in Replit secrets\n"
                   + "2. Generate a fresh key pair in Backblaze if unsure\n"
                   + "3. Rebuild the APK after updating";
        } else if (detail != null && (detail.contains("Unable to resolve host")
                || detail.contains("timeout") || detail.contains("connect"))) {
            errorCode = "Network error";
            advice = "Could not reach Backblaze B2. Check your internet connection and try again.";
        } else if (detail != null && detail.contains("[413]")) {
            errorCode = "HTTP 413 — File too large";
            advice = "This file exceeds the server's upload size limit (max 500 MB).\n\n"
                   + "Choose a smaller file, or compress the video before sending.";
        } else {
            errorCode = "Unknown error";
            advice = "An unexpected error occurred during upload.\n\nDetails: "
                   + (detail != null ? detail : "none");
        }

        final String finalCode   = errorCode;
        final String finalAdvice = advice;

        new MaterialAlertDialogBuilder(this)
            .setTitle("\u26a0 Upload Failed — " + finalCode)
            .setMessage(finalAdvice)
            .setPositiveButton("Got it", null)
            .setNeutralButton("Copy error", (d, w) -> {
                android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "B2 error", finalCode + "\n" + (detail != null ? detail : "")));
                    Toast.makeText(this, "Error copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    /** Returns the file size in bytes from ContentResolver, or 0 if unavailable. */
    private long getFileSize(Uri uri) {
        android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
        if (c == null) return 0;
        try {
            if (!c.moveToFirst()) return 0;
            int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
            return idx >= 0 ? c.getLong(idx) : 0;
        } finally { c.close(); }
    }

    /**
     * Compresses a raw image to max 1280px on the longest side at JPEG 85.
     * Uses inSampleSize for memory-efficient decode, then precise scaling.
     * Returns original bytes if compression would make it larger.
     */
    private byte[] compressImage(byte[] raw) {
        try {
            final int MAX_DIM = 1280;

            // Step 1: measure dimensions without loading pixels into memory
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
            int origW = probe.outWidth, origH = probe.outHeight;

            // Step 2: compute power-of-2 sub-sample factor so the decoded bitmap
            //         is as small as possible while still >= target size
            int sampleSize = 1;
            int tmpW = origW, tmpH = origH;
            while (tmpW / 2 >= MAX_DIM || tmpH / 2 >= MAX_DIM) {
                sampleSize *= 2;
                tmpW /= 2;
                tmpH /= 2;
            }

            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            if (bmp == null) return raw;

            // Step 3: precise scale to max 1280 on the longest side
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (bw > MAX_DIM || bh > MAX_DIM) {
                float scale = (float) MAX_DIM / Math.max(bw, bh);
                int nw = Math.max(1, Math.round(bw * scale));
                int nh = Math.max(1, Math.round(bh * scale));
                android.graphics.Bitmap scaled =
                        android.graphics.Bitmap.createScaledBitmap(bmp, nw, nh, true);
                bmp.recycle();
                bmp = scaled;
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            bmp.recycle();
            byte[] compressed = out.toByteArray();
            return compressed.length < raw.length ? compressed : raw;
        } catch (Exception e) {
            Log.w(TAG, "Image compression failed, using original: " + e.getMessage());
            return raw;
        }
    }

    private void sendMediaMessage(String storagePath, String mediaType, String mediaKey, String caption) {
        String msgId = UUID.randomUUID().toString(); 
        long now = System.currentTimeMillis();
        long exp = getDisappearMs() > 0 ? now + getDisappearMs() : 0;
        
        // Optimistic UI for media
        Message m = new Message(msgId, conversationId, myUid, "", now, false, storagePath, mediaType);
        m.setExpiresAt(exp);
        m.setMediaKey(mediaKey);
        if (caption != null && !caption.isEmpty()) m.setCaption(caption);
        m.setStatus("pending");
        adapter.appendMessage(m);
        knownIds.add(msgId);
        recyclerView.scrollToPosition(adapter.getItemCount() - 1);

        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); 
        doc.put("conversationId", conversationId);
        doc.put("sender", myUid); 
        doc.put("text", "");
        doc.put("path", storagePath);
        doc.put("mediaType", mediaType);
        doc.put("isEncrypted", true);
        doc.put("mediaKey", mediaKey);
        doc.put("type", mediaType);
        doc.put("expiresAt", exp); 
        doc.put("timestamp", FieldValue.serverTimestamp());
        doc.put("status", "sent");
        if (caption != null && !caption.isEmpty()) doc.put("caption", caption);

        db.collection("chats").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              FirebaseCostGuard.getInstance(ChatMediaActivity.this).recordWrites(1);
              m.setStatus("sent");
              adapter.updateMessage(msgId, msg -> msg.setStatus("sent"));
              saveToRoom(m);
              ConversationMetaUpdater.update(ChatMediaActivity.this, conversationId, myUid,
                  partnerUid, caption != null && !caption.isEmpty()
                      ? previewFor(caption)
                      : ("video".equals(mediaType) ? "Video 🎬" : "Photo 🖼"));
              notifyPartner("DuoShield", "video".equals(mediaType) ? "Sent a video 🎬" : "Sent a photo 🖼", msgId);
              // Schedule B2 file deletion 24 hours after upload
              if (B2StorageHelper.isB2Path(storagePath)) {
                  com.duoshield.app.db.B2CleanupWorker.schedule(
                          ChatMediaActivity.this, storagePath, conversationId, msgId);
              }
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Failed to send media message to Firestore: " + e.getMessage());
              // Delete the orphaned B2 file — it can never be found without a Firestore doc
              if (B2StorageHelper.isB2Path(storagePath)) {
                  executor.execute(() -> {
                      try { B2StorageHelper.deleteFile(storagePath); }
                      catch (Exception ex) { Log.w(TAG, "B2 cleanup failed: " + ex.getMessage()); }
                  });
                  m.setMediaUrl(null); // clear so retry shows "re-select media"
                  adapter.updateMessage(msgId, msg -> { msg.setStatus("failed"); msg.setMediaUrl(null); });
              } else {
                  adapter.updateMessage(msgId, msg -> msg.setStatus("failed"));
              }
              m.setStatus("failed");
              saveToRoom(m);
              Toast.makeText(ChatMediaActivity.this, "Failed to send media. Please re-select and try again.", Toast.LENGTH_LONG).show();
          });
    }

    private void sendContactCard() {
        // F24 fix: encrypt contact card text with Signal before writing to Firestore
        String cardText = "DuoShield User|" + myUid;
        String msgId    = UUID.randomUUID().toString();
        long   now      = System.currentTimeMillis();

        // Optimistic UI — show as pending immediately
        Message m = new Message(msgId, conversationId, myUid, cardText, now, false, null, "contact_card");
        m.setStatus("pending");
        adapter.appendMessage(m);
        knownIds.add(msgId);
        recyclerView.scrollToPosition(adapter.getItemCount() - 1);

        // Encrypt + Firestore write on Signal executor (must not run on main thread)
        dbExecutor.execute(() -> {
            try {
                SignalCipherHelper.EncryptResult r =
                        SignalCipherHelper.encrypt(ChatMediaActivity.this, partnerUid, cardText);

                Map<String, Object> doc = new HashMap<>();
                doc.put("id", msgId); doc.put("conversationId", conversationId);
                doc.put("sender", myUid); doc.put("text", r.ciphertextB64);
                doc.put("mediaType", "contact_card"); doc.put("type", "contact_card");
                doc.put("isEncrypted", true); doc.put("sigType", r.sigType);
                doc.put("timestamp", FieldValue.serverTimestamp());

                db.collection("chats").document(conversationId)
                  .collection("messages").document(msgId).set(doc)
                  .addOnSuccessListener(v -> {
                      FirebaseCostGuard.getInstance(ChatMediaActivity.this).recordWrites(1);
                      m.setStatus("sent");
                      adapter.updateMessage(msgId, msg -> msg.setStatus("sent"));
                      saveToRoom(m);  // stores plaintext in Room for local display
                      notifyPartner("DuoShield", "Shared a contact card 📇", msgId);
                  })
                  .addOnFailureListener(e -> {
                      m.setStatus("failed");
                      adapter.updateMessage(msgId, msg -> msg.setStatus("failed"));
                      saveToRoom(m);
                      runOnUiThread(() -> Toast.makeText(ChatMediaActivity.this,
                              "Failed to share contact. Tap to retry.", Toast.LENGTH_SHORT).show());
                  });
            } catch (Exception e) {
                Log.e(TAG, "Contact card encryption failed", e);
                m.setStatus("failed");
                adapter.updateMessage(msgId, msg -> msg.setStatus("failed"));
                saveToRoom(m);
                runOnUiThread(() -> Toast.makeText(ChatMediaActivity.this,
                        "Failed to share contact. Tap to retry.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void retryMessage(Message msg) {
        adapter.removeMessage(msg.getId());
        knownIds.remove(msg.getId());
        if ("image".equals(msg.getMediaType()) || "video".equals(msg.getMediaType())) {
            // If it failed at the Firestore step, we still have the B2 path.
            if (msg.getMediaUrl() != null && !msg.getMediaUrl().isEmpty()) {
                sendMediaMessage(msg.getMediaUrl(), msg.getMediaType(), msg.getMediaKey(), msg.getCaption());
            } else {
                Toast.makeText(this, "Please re-select the media to retry.", Toast.LENGTH_LONG).show();
            }
        } else if ("contact_card".equals(msg.getMediaType())) {
            sendContactCard();
        } else {
            sendMessage(msg.getText());
        }
    }

    private void sendMessage(String plaintext) {
        if (!SignalKeyManager.isInitialized(this)) {
            Toast.makeText(this,
                    "Identity not ready yet — please wait a moment.", Toast.LENGTH_SHORT).show();
            ensureSignalSession();
            return;
        }

        // If the X3DH session handshake is still in flight, block sending until it resolves.
        if (keyPending) {
            Toast.makeText(this,
                    "Establishing secure connection — please try again in a moment.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable send button for the duration of the async send to prevent double-sends
        // from rapid taps before the TextWatcher hides the button.
        if (sendButton != null) sendButton.setEnabled(false);

        String msgId = UUID.randomUUID().toString();
        long   now   = System.currentTimeMillis();
        long   exp   = getDisappearMs() > 0 ? now + getDisappearMs() : 0;
        String rId   = pendingReplyId;
        String rPrv  = pendingReplyPreview;
        clearReplyMode();

        // ── Optimistic UI: show the message immediately before Firestore write ──
        Message optimistic = new Message(msgId, conversationId, myUid, plaintext, now, false);
        optimistic.setStatus("pending");
        optimistic.setExpiresAt(exp);
        if (rId != null) { optimistic.setReplyToId(rId); optimistic.setReplyPreview(rPrv); }
        adapter.appendMessage(optimistic);
        HapticHelper.send(this);
        knownIds.add(msgId); // prevent Firestore ADDED event from appending a duplicate
        int last = adapter.getItemCount() - 1;
        if (last >= 0) recyclerView.scrollToPosition(last);

        // Signal encryption + Firestore write on background thread.
        // SessionCipher.encrypt() mutates ratchet state — must be single-threaded via dbExecutor.
        final String finalRId = rId, finalRPrv = rPrv;
        dbExecutor.execute(() -> {
            try {
                SignalCipherHelper.EncryptResult r =
                        SignalCipherHelper.encrypt(ChatMediaActivity.this, partnerUid, plaintext);

                Map<String, Object> doc = new HashMap<>();
                doc.put("id", msgId); doc.put("conversationId", conversationId);
                doc.put("sender", myUid); doc.put("text", r.ciphertextB64);
                doc.put("isEncrypted", true); doc.put("sigType", r.sigType);
                doc.put("type", "text"); doc.put("status", "sent");
                doc.put("expiresAt", exp);
                if (finalRId != null) { doc.put("replyToId", finalRId); doc.put("replyPreview", finalRPrv); }
                doc.put("timestamp", FieldValue.serverTimestamp());

                db.collection("chats").document(conversationId)
                  .collection("messages").document(msgId).set(doc)
                  .addOnSuccessListener(v -> {
                      runOnUiThread(() -> { if (sendButton != null) sendButton.setEnabled(true); });
                      FirebaseCostGuard.getInstance(ChatMediaActivity.this).recordWrites(1);
                      adapter.updateMessage(msgId, m -> m.setStatus("sent"));
                      // Store plaintext (not ciphertext) in Room — search & export use Room
                      Message stored = new Message(msgId, conversationId, myUid, plaintext, now, false);
                      stored.setExpiresAt(exp);
                      stored.setStatus("sent");
                      if (finalRId != null) { stored.setReplyToId(finalRId); stored.setReplyPreview(finalRPrv); }
                      saveToRoom(stored);
                      // Conversation list preview: show the actual message text (truncated),
                      // same as WhatsApp/Signal. ConversationMetaUpdater writes this straight
                      // into the chat doc as plaintext (not the Signal ciphertext), so it is
                      // only ever readable by participants via Firestore rules already scoping
                      // "chats/{id}" reads to users in the participants array.
                      ConversationMetaUpdater.update(ChatMediaActivity.this, conversationId, myUid,
                          partnerUid, previewFor(plaintext));
                      notifyPartner("DuoShield", "New message", msgId);
                  })
                  .addOnFailureListener(e -> {
                      runOnUiThread(() -> { if (sendButton != null) sendButton.setEnabled(true); });
                      optimistic.setStatus("failed");
                      adapter.updateMessage(msgId, m -> m.setStatus("failed"));
                      saveToRoom(optimistic);
                      Toast.makeText(ChatMediaActivity.this,
                              "Failed to send. Tap to retry.", Toast.LENGTH_SHORT).show();
                  });

            } catch (Exception e) {
                Log.e(TAG, "Signal encryption failed for msg " + msgId, e);
                // Check whether this is a "no session" failure — if so, trigger X3DH automatically
                // so the user only needs to tap Send once more after the toast clears.
                String exName = e.getClass().getSimpleName();
                String exMsg  = e.getMessage() != null ? e.getMessage().toLowerCase(java.util.Locale.US) : "";
                boolean noSession = exName.contains("NoSession") || exMsg.contains("no session")
                        || exMsg.contains("nosession");
                runOnUiThread(() -> {
                    if (sendButton != null) sendButton.setEnabled(true);
                    optimistic.setStatus("failed");
                    adapter.updateMessage(msgId, m -> m.setStatus("failed"));
                    saveToRoom(optimistic);
                    if (noSession) {
                        Toast.makeText(ChatMediaActivity.this,
                                "Secure session lost — re-establishing. Tap send to retry.",
                                Toast.LENGTH_LONG).show();
                        ensureSignalSession();
                    } else {
                        Toast.makeText(ChatMediaActivity.this,
                                "Encryption error — session may need re-establishing.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /**
     * Ensures a Signal Protocol session exists with the partner, then starts the
     * Firestore message listener.
     *
     * <p>Replaces the legacy {@code reEnsureEcdhKey()} ECDH derivation flow (Phase 3).
     *
     * <ul>
     *   <li>If a session already exists in Room, {@link SignalSessionManager} returns
     *       immediately without a Firestore round-trip.</li>
     *   <li>If no session exists, the full X3DH handshake is performed.</li>
     * </ul>
     *
     * <p>The {@code keyPending} flag prevents {@link #listenForMessages()} from starting
     * while session establishment is in progress (same guard as the old ECDH flow).
     * A 10-second watchdog starts the listener unconditionally if X3DH takes too long.
     */
    private void ensureSignalSession() {
        if (partnerUid == null) {
            partnerUid = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                    .getString("partner_uid", null);
            if (partnerUid == null) {
                Log.e(TAG, "ensureSignalSession: partnerUid null — cannot establish session");
                Toast.makeText(this,
                        "Partner info missing. Please re-pair in Settings.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        keyPending = true;

        // Watchdog: if session establishment takes > 10 s, start the listener anyway
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (keyPending) {
                Log.w(TAG, "Signal session watchdog: timed out — starting listener anyway");
                keyPending = false;
                if (msgListener != null) { msgListener.remove(); msgListener = null; }
                listenForMessages();
                retryPendingDecryption();
            }
        }, 10_000);

        SignalSessionManager.establishSession(this, partnerUid,
                new SignalSessionManager.SessionCallback() {
            @Override
            public void onEstablished(
                    org.signal.libsignal.protocol.SignalProtocolAddress address,
                    DuoShieldSignalStore store) {
                keyPending = false;
                if (msgListener != null) { msgListener.remove(); msgListener = null; }
                listenForMessages();
                retryPendingDecryption();
                // Identity key may have changed during X3DH — check the banner
                runOnUiThread(() -> checkSafetyNumberBanner());
            }

            @Override
            public void onError(String reason) {
                Log.w(TAG, "ensureSignalSession: " + reason);
                keyPending = false;
                listenForMessages(); // start listener even without a fresh session
                retryPendingDecryption();
            }
        });
    }

    /**
     * Marks own last-seen timestamp AND batch-updates all of the partner's
     * messages in this chat to "read" so the sender sees blue double-ticks.
     */
    private void markMessagesAsReadAndSeen() {
        if (conversationId == null || myUid == null) return;

        // Update lastSeen timestamp
        db.collection("chats").document(conversationId)
          .update("lastSeen_" + myUid, FieldValue.serverTimestamp());

        // Bug B fix: reset the unread counter so the badge clears when chat is opened.
        // UnreadCountHelper.reset() sets unread_<myUid> to 0 on the conversation doc.
        com.duoshield.app.util.UnreadCountHelper.reset(conversationId, myUid);

        if (partnerUid == null) return;

        // Batch-update partner's sent/delivered messages → "read"
        db.collection("chats").document(conversationId)
          .collection("messages")
          .whereEqualTo("sender", partnerUid)
          .get()
          .addOnSuccessListener(snaps -> {
              if (snaps.isEmpty()) return;
              WriteBatch batch = db.batch();
              int count = 0;
              for (DocumentSnapshot doc : snaps.getDocuments()) {
                  String st = doc.getString("status");
                  if ("read".equals(st)) continue;
                  batch.update(doc.getReference(), "status", "read");
                  if (++count == 450) { batch.commit(); batch = db.batch(); count = 0; }
              }
              if (count > 0) batch.commit();
          });
    }

    private void saveToRoom(Message m) {
        dbExecutor.execute(() -> {
            AppDatabase.getInstance(this).messageDao().insert(m);
            if (!"failed".equals(m.getStatus())) {
                BackupManager.backup(this, m);
            }
        });
    }

    /**
     * Pings the partner via Firestore so the Cloud Function (notifyOnMessage) can
     * deliver the FCM push. We also write a "nudge" timestamp so the receiver
     * device wakes its Firestore listener even while backgrounded.
     *
     * The heavy lifting (FCM send) is done server-side by the Cloud Function that
     * triggers on every new message document creation — no service-account.json
     * needed in the APK.
     */
    private void notifyPartner(String title, String body, String msgId) {
        if (conversationId == null || partnerUid == null) return;
        db.collection("chats").document(conversationId)
          .update("lastActivity", com.google.firebase.firestore.FieldValue.serverTimestamp())
          .addOnFailureListener(e -> Log.w(TAG, "nudge update failed (non-critical): " + e.getMessage()));
    }

    /**
     * Async-decrypts all messages in {@link #pendingDecryptQueue}.
     *
     * <p>Must be called from the <strong>main thread</strong>.  The actual crypto and
     * Room I/O are dispatched to {@link #dbExecutor} (single-threaded), preserving the
     * Signal ratchet order.  UI updates are posted back to the main thread.
     *
     * <h3>Routing</h3>
     * <ul>
     *   <li>Signal messages ({@code sigType} 1 or 3): {@link SignalCipherHelper#decrypt}.</li>
     *   <li>{@code sigType} 0 (pre-migration rows): shown as "[Legacy message — not decryptable]"
     *       and removed from the queue. No real users have such rows.</li>
     * </ul>
     */
    private void retryPendingDecryption() {
        if (pendingDecryptQueue.isEmpty() || keyPending) return;

        // Snapshot queue on main thread; crypto runs on dbExecutor
        final List<Message>        snapshot    = new ArrayList<>(pendingDecryptQueue);
        final Map<String, Integer> sigTypes    = new HashMap<>(queuedSigTypes);

        dbExecutor.execute(() -> {
            List<String>         resolved    = new ArrayList<>();
            List<Message>        reQueue     = new ArrayList<>();
            Map<String, Integer> reQueueSigs = new HashMap<>();

            for (Message pending : snapshot) {
                String id      = pending.getId();
                int    sigType = sigTypes.containsKey(id) ? sigTypes.get(id) : 0;
                try {
                    String decrypted;
                    boolean isSignalMsg = (sigType == CiphertextMessage.WHISPER_TYPE
                                         || sigType == CiphertextMessage.PREKEY_TYPE);
                    if (isSignalMsg) {
                        decrypted = SignalCipherHelper.decrypt(
                                ChatMediaActivity.this, pending.getSender(),
                                pending.getText(), sigType);
                    } else {
                        // sigType == 0: legacy ECDH — no decryption path (CryptoHelper deleted).
                        // Mark resolved so the message stops re-queuing, show explanatory text.
                        Log.w(TAG, "retryPendingDecryption: sigType=0 msg=" + id + " — legacy, skipped");
                        runOnUiThread(() -> adapter.updateMessage(id, m -> {
                            m.setText("[Legacy message — not decryptable]");
                            m.setEncrypted(false);
                        }));
                        resolved.add(id);
                        continue;
                    }

                    resolved.add(id);
                    final String finalDecrypted = decrypted;
                    runOnUiThread(() -> adapter.updateMessage(id, m -> {
                        m.setText(finalDecrypted);
                        m.setEncrypted(false);
                    }));

                    // Persist to Room from dbExecutor (already on bg thread)
                    Message toSave = new Message(
                            pending.getId(), pending.getConversationId(), pending.getSender(),
                            decrypted, pending.getTimestamp(), false,
                            pending.getMediaUrl(), pending.getMediaType());
                    if (pending.getReplyToId()    != null) toSave.setReplyToId(pending.getReplyToId());
                    if (pending.getReplyPreview() != null) toSave.setReplyPreview(pending.getReplyPreview());
                    toSave.setExpiresAt(pending.getExpiresAt());
                    if (pending.getStatus() != null) toSave.setStatus(pending.getStatus());
                    AppDatabase.getInstance(ChatMediaActivity.this).messageDao().insert(toSave);
                    BackupManager.backup(ChatMediaActivity.this, toSave);
                    Log.d(TAG, "retryPendingDecryption: OK msg=" + id + " sigType=" + sigType);

                } catch (Exception ex) {
                    Log.w(TAG, "retryPendingDecryption: still failed msg=" + id
                            + " sigType=" + sigType, ex);
                    reQueue.add(pending);
                    if (sigType != 0) reQueueSigs.put(id, sigType);
                }
            }

            runOnUiThread(() -> {
                // Remove all snapshot entries (resolved + newly failed), re-add failures
                for (Message m : snapshot) {
                    String id = m.getId();
                    pendingDecryptQueue.removeIf(p -> id.equals(p.getId()));
                    queuedSigTypes.remove(id);
                }
                pendingDecryptQueue.addAll(reQueue);
                queuedSigTypes.putAll(reQueueSigs);

                // Legacy ECDH: if messages failed with a non-null key, schedule one re-derive
                boolean anyEcdhFailed = reQueue.stream().anyMatch(
                        p -> !reQueueSigs.containsKey(p.getId()));
                if (anyEcdhFailed && !decryptRetryScheduled) {
                    decryptRetryScheduled = true;
                    Log.w(TAG, "retryPendingDecryption: ECDH key stale — scheduling re-derive");
                    ensureSignalSession();
                }
            });
        });
    }

}
