package com.duoshield.app;

import com.duoshield.app.util.LogRedact;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.crypto.GroupCipherHelper;
import com.duoshield.app.security.GroupKeyAuthorizationPolicy;
import com.duoshield.app.crypto.signal.SignalCipherHelper;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Group;
import com.duoshield.app.models.GroupMember;
import com.duoshield.app.models.Message;
import com.duoshield.app.notifications.NotificationVisibility;
import com.duoshield.app.ui.MessageAdapter;
import com.duoshield.app.util.B2StorageHelper;
import com.duoshield.app.util.ChatThemeHelper;
import com.duoshield.app.util.DevicePerformanceTier;
import com.duoshield.app.util.FirebaseCostGuard;
import com.duoshield.app.util.InlineThumb;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Group chat screen.
 *
 * <p>Encryption: messages are encrypted with a shared AES-256-GCM group key
 * ({@link GroupCipherHelper}). The group key is stored locally in Room
 * ({@code Group.groupKey}). If absent (newly added member), it is fetched from
 * Firestore {@code /groups/{id}/keys/{myUid}} and decrypted via the creator's
 * Signal session before being persisted locally.
 *
 * <p>Messages are written to Firestore {@code /groups/{id}/messages} and stored
 * in the local {@code messages} Room table using the {@code groupId} as their
 * {@code conversationId}, so all existing message queries work unchanged.
 *
 * <p>Architecture rules observed:
 * <ul>
 *   <li>Extends {@link BaseActivity} for app-lock integration.</li>
 *   <li>Single Firestore listener — attached in {@code onStart()}, removed in
 *       {@code onStop()}.</li>
 *   <li>{@link MessageAdapter} with DiffUtil drives the RecyclerView.</li>
 *   <li>{@link FirebaseCostGuard} records all reads, writes, and deletes.</li>
 * </ul>
 */
public class GroupChatActivity extends BaseActivity {

    private static final String TAG = "GroupChatActivity";
    private static final String PREF_GROUP_CURSOR_PREFIX = "group_last_server_ts_";

    // ── State ─────────────────────────────────────────────────────────────────
    private String groupId;
    private String groupName;
    private String myUid;
    /**
     * Written from the background executor (key decryption) and read from the
     * Firestore listener (main thread). {@code volatile} ensures visibility without
     * a full synchronization block (BUG-G03).
     */
    private volatile String groupKey;
    private String creatorUid;
    private List<String> memberUids = new ArrayList<>();

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseFirestore  db;
    private ListenerRegistration msgListener;
    private com.google.firebase.Timestamp latestKnownTimestamp;

    // ── UI ────────────────────────────────────────────────────────────────────
    private RecyclerView  recyclerView;
    private MessageAdapter adapter;
    private EditText      etMessage;

    // ── Local DB ──────────────────────────────────────────────────────────────
    private AppDatabase   localDb;
    private ExecutorService executor;
    /**
     * Dedicated to video re-encoding so a multi-minute transcode cannot starve
     * the queued uploads and Room writes running on {@link #executor}.
     */
    private ExecutorService transcodeExecutor;

    // ── Dedup guard ───────────────────────────────────────────────────────────
    private final Set<String> knownIds = new HashSet<>();

    // ── Media / camera ────────────────────────────────────────────────────────
    private static final int  REQUEST_CAMERA_GROUP   = 205;
    private static final long LARGE_FILE_THRESHOLD   = 50 * 1024 * 1024L;
    /** Temp URI for camera capture; consumed once TakePicture returns. */
    private Uri               cameraGroupPhotoUri    = null;
    private View              uploadGroupProgressContainer;
    private TextView          tvGroupUploadPct;
    private ImageView         btnGroupAttach;
    private ImageView         groupUploadThumb;
    private com.duoshield.app.ui.EmojiKeyboardHelper groupEmojiHelper;
    private String             pendingGroupCaption;
    private final Object       groupMultiUploadLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger groupMultiCompleted =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.List<String[]> pendingGroupItems = new java.util.ArrayList<>();
    private int    pendingGroupTotal = 0;
    private String pendingGroupAlbumCaption;

    /** Multi-select image picker — one WhatsApp-style album message. */
    private final ActivityResultLauncher<String> pickGroupImageLauncher =
        registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
            if (uris == null || uris.isEmpty()) return;
            launchGroupMediaPreview(new java.util.ArrayList<>(uris), "image");
        });

    /** Multi-select video picker — one WhatsApp-style album message. */
    private final ActivityResultLauncher<String> pickGroupVideoLauncher =
        registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
            if (uris == null || uris.isEmpty()) return;
            launchGroupMediaPreview(new java.util.ArrayList<>(uris), "video");
        });

    /** Camera still-capture for group chat. */
    private final ActivityResultLauncher<Uri> takeGroupPictureLauncher =
        registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success != null && success && cameraGroupPhotoUri != null) {
                launchGroupMediaPreview(
                        java.util.Collections.singletonList(cameraGroupPhotoUri), "image");
            }
            cameraGroupPhotoUri = null;
        });

    private final ActivityResultLauncher<Intent> groupMediaSendPreviewLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Intent data = result.getData();
            java.util.ArrayList<String> values =
                    data.getStringArrayListExtra(MediaSendPreviewActivity.EXTRA_URIS);
            if (values == null || values.isEmpty()) {
                String one = data.getStringExtra(MediaSendPreviewActivity.EXTRA_URI);
                if (one != null) values = new java.util.ArrayList<>(
                        java.util.Collections.singletonList(one));
            }
            if (values == null || values.isEmpty()) return;
            String type = data.getStringExtra(MediaSendPreviewActivity.EXTRA_MEDIA_TYPE);
            if (type == null || type.isEmpty()) type = "image";
            String caption = data.getStringExtra(MediaSendPreviewActivity.EXTRA_CAPTION);
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            for (String value : values) uris.add(Uri.parse(value));
            if (uris.size() > 1) {
                startGroupAlbumUpload(uris, type, caption);
            } else {
                pendingGroupCaption = caption == null || caption.trim().isEmpty()
                        ? null : caption.trim();
                uploadGroupMedia(uris.get(0), type);
            }
        });

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_chat);

        db      = FirebaseFirestore.getInstance();
        localDb = AppDatabase.getInstance(this);
        executor = Executors.newSingleThreadExecutor();
        transcodeExecutor = Executors.newSingleThreadExecutor();

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        myUid   = prefs.getString("my_uid", null);
        groupId = getIntent().getStringExtra("group_id");

        if (groupId == null || myUid == null) { finish(); return; }

        // Only persisted, resolved Firestore server timestamps are valid cursors.
        // Room messages include local-clock optimistic sends and must never seed this.
        long persistedServerMs = prefs.getLong(PREF_GROUP_CURSOR_PREFIX + groupId, 0L);
        if (persistedServerMs > 0L) {
            latestKnownTimestamp = new com.google.firebase.Timestamp(
                    new java.util.Date(persistedServerMs));
        }

        // FLAG_SECURE is applied globally in BaseActivity.onCreate()
        // based on the "app_screenshot_enabled" preference.

        // ── Views ──────────────────────────────────────────────────────────
        TextView tvGroupName   = findViewById(R.id.tv_group_name);
        TextView tvMemberCount = findViewById(R.id.tv_member_count);
        recyclerView           = findViewById(R.id.recycler_messages);
        etMessage              = findViewById(R.id.et_message);
        ImageView btnBack      = findViewById(R.id.btn_back);
        ImageView btnSend      = findViewById(R.id.btn_send);
        ImageView btnOverflow  = findViewById(R.id.btn_group_overflow);

        btnGroupAttach                = findViewById(R.id.btn_group_attach);
        ImageView groupEmojiButton    = findViewById(R.id.group_emoji_button);
        ImageView btnGroupCameraInline = findViewById(R.id.btn_group_camera_inline);
        android.view.View groupCameraContainer = findViewById(R.id.group_camera_container);
        uploadGroupProgressContainer  = findViewById(R.id.groupUploadProgressContainer);
        tvGroupUploadPct              = findViewById(R.id.tvGroupUploadPct);
        groupUploadThumb              = findViewById(R.id.groupUploadThumb);
        groupEmojiHelper              =
                new com.duoshield.app.ui.EmojiKeyboardHelper(this, etMessage);

        btnBack.setOnClickListener(v -> finish());
        // Tap the group header to view/manage members (admin can remove)
        tvGroupName.setOnClickListener(v -> showGroupInfoSheet());
        tvMemberCount.setOnClickListener(v -> showGroupInfoSheet());
        if (btnOverflow != null) {
            btnOverflow.setOnClickListener(v -> {
                android.widget.PopupMenu popup = new android.widget.PopupMenu(this, v);
                popup.getMenu().add(0, 1, 0, "Export Chat");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        com.duoshield.app.util.ChatExportHelper.showExportDialog(
                            this, groupId, null, true);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }
        if (btnGroupAttach != null) {
            btnGroupAttach.setOnClickListener(v -> showGroupMediaPickerSheet());
        }
        if (groupEmojiButton != null) groupEmojiButton.setOnClickListener(v -> {
            if (groupEmojiHelper != null) groupEmojiHelper.toggle();
        });
        if (btnGroupCameraInline != null) btnGroupCameraInline.setOnClickListener(v -> launchGroupCameraCapture());
        btnSend.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_PRESS);
            trySend();
        });

        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                boolean hasText = !s.toString().trim().isEmpty();
                btnSend.setAlpha(hasText ? 1.0f : 0.4f);
                if (groupCameraContainer != null)
                    groupCameraContainer.setVisibility(hasText ? android.view.View.GONE : android.view.View.VISIBLE);
            }
        });

        adapter = new MessageAdapter(new java.util.ArrayList<>(), myUid, null, null, this::retryMessage);
        LinearLayoutManager groupLlm = new LinearLayoutManager(this);
        groupLlm.setStackFromEnd(true);
        groupLlm.setInitialPrefetchItemCount(
                DevicePerformanceTier.get(this).recyclerViewPrefetchCount());
        recyclerView.setLayoutManager(groupLlm);
        recyclerView.setHasFixedSize(true);
        // Smaller off-screen view cache on LOW so cached rows do not pin decoded thumbnails
        // against the reduced Glide bitmap pool. See ChatMediaActivity for the reasoning.
        recyclerView.setItemViewCacheSize(
                DevicePerformanceTier.get(this).recyclerViewCacheSize());
        recyclerView.setAdapter(adapter);
        ChatThemeHelper.apply(recyclerView, getSharedPreferences("duoshield_prefs", MODE_PRIVATE));

        // ── Load group from Room, init group key ───────────────────────────
        executor.execute(() -> {
            Group g = localDb.groupDao().getGroupById(groupId);
            if (g == null) { runOnUiThread(this::finish); return; }

            groupName  = g.name;
            groupKey   = g.groupKey;
            creatorUid = g.createdBy;
            memberUids = localDb.groupDao().getMemberUidsOf(groupId);

            runOnUiThread(() -> {
                tvGroupName.setText(groupName);
                int memberCount = memberUids.size();
                tvMemberCount.setText(memberCount + " member" + (memberCount == 1 ? "" : "s"));
            });

            if (groupKey == null) {
                // Fetch encrypted key from Firestore and decrypt with Signal
                fetchGroupKey();
            } else {
                seedFromRoom();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply the chat theme and bubble style in case the user changed them in Settings.
        if (groupId != null) {
            NotificationVisibility.enterConversation(groupId, this);
        }
        if (recyclerView != null) {
            ChatThemeHelper.apply(recyclerView, getSharedPreferences("duoshield_prefs", MODE_PRIVATE));
        }
        if (adapter != null) adapter.notifyBubbleStyleChanged();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (groupKey != null && msgListener == null) listenForMessages();
    }

    @Override
    protected void onPause() {
        NotificationVisibility.leaveConversation(groupId, this);
        super.onPause();
    }

    @Override
    protected void onStop() {
        NotificationVisibility.leaveConversation(groupId, this);
        super.onStop();
        if (msgListener != null) { msgListener.remove(); msgListener = null; }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
        if (transcodeExecutor != null && !transcodeExecutor.isShutdown()) {
            transcodeExecutor.shutdownNow();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group key retrieval
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called when no local group key is stored. Fetches the Signal-encrypted
     * group key from Firestore, decrypts it, and saves it to Room.
     */
    private void fetchGroupKey() {
        FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
        if (!guard.canRead(1)) {
            runOnUiThread(() ->
                Toast.makeText(this, "Read quota reached — try again tomorrow",
                        Toast.LENGTH_LONG).show());
            return;
        }
        db.collection("groups").document(groupId)
          .collection("keys").document(myUid)
          .get()
          .addOnSuccessListener(snap -> {
              guard.recordReads(1);
              if (!snap.exists()) {
                  Toast.makeText(this, "Group key not found — ask the creator to re-add you",
                          Toast.LENGTH_LONG).show();
                  return;
              }
              String encryptedKey = snap.getString("encryptedKey");
              Object sigTypeObj   = snap.get("sigType");
              String sender       = snap.getString("senderUid");
              if (encryptedKey == null || sigTypeObj == null || sender == null) {
                  Toast.makeText(this, "Malformed group key doc", Toast.LENGTH_SHORT).show();
                  return;
              }
              // Defense in depth: even though the Firestore rule now restricts writes
              // to this collection to the group's creator, also verify the claimed
              // senderUid actually matches the locally-cached creatorUid before ever
              // decrypting/trusting it. This guards against a stale/misconfigured rule
              // or a future regression re-opening write access to other members.
              //
              // S07-L1 fix: this previously read `creatorUid != null && !creatorUid.equals(sender)`,
              // which fails OPEN when creatorUid is null (a null/missing local
              // creatorUid — e.g. a legacy Room row from before creatorUid was
              // populated, or a sync gap — short-circuited the check to false and
              // let ANY claimed sender through). A missing creatorUid means we have
              // no basis to trust this key doc's sender at all, so it must fail
              // CLOSED: null/missing creatorUid now denies unconditionally, exactly
              // like a mismatched one.
              if (!GroupKeyAuthorizationPolicy.isAuthorizedCreator(creatorUid, sender)) {
                  // S07-L4/S10-N2: creatorUid is a peer uid too — redact it, same as
                  // sender, before logging at a level (Log.w) that survives release
                  // builds (proguard-rules.pro keeps Log.w/Log.e deliberately).
                  Log.w(TAG, "Group key doc senderUid (" + LogRedact.uid(sender) + ") does not match "
                          + "group creator (" + LogRedact.uid(creatorUid) + ") — refusing to trust it");
                  Toast.makeText(this,
                          "Group key came from an unexpected sender — refusing for safety",
                          Toast.LENGTH_LONG).show();
                  return;
              }
              // Firestore returns numeric fields as Long on Android; guard against
              // other Number subtypes to avoid ClassCastException (BUG-D05).
              int sigType = sigTypeObj instanceof Number
                      ? ((Number) sigTypeObj).intValue() : 2;
              executor.execute(() -> {
                  try {
                      String decrypted = SignalCipherHelper.decrypt(
                              this, sender, encryptedKey, sigType);
                      groupKey = decrypted;
                      localDb.groupDao().updateGroupKey(groupId, groupKey);
                      seedFromRoom();
                  } catch (Exception e) {
                      Log.e(TAG, "Failed to decrypt group key" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                      runOnUiThread(() ->
                          Toast.makeText(this, "Could not decrypt group key", Toast.LENGTH_LONG).show());
                  }
              });
          })
          .addOnFailureListener(e ->
              Toast.makeText(this, "Failed to fetch group key", Toast.LENGTH_SHORT).show());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Message seeding (Room cache)
    // ═════════════════════════════════════════════════════════════════════════

    private void seedFromRoom() {
        executor.execute(() -> {
            List<Message> cached = localDb.messageDao().getMessages(groupId);
            for (Message m : cached) knownIds.add(m.getId());
            if (!cached.isEmpty()) {
                long lastTs = cached.get(cached.size() - 1).getTimestamp();
                // We use a placeholder timestamp; real latestKnownTimestamp set from Firestore
                Log.d(TAG, "Seeded " + cached.size() + " messages from Room");
            }
            runOnUiThread(() -> {
                adapter.setMessages(cached);
                scrollToBottom();
                // Now attach the Firestore listener
                if (msgListener == null) listenForMessages();
            });
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Firestore listener
    // ═════════════════════════════════════════════════════════════════════════

    private void listenForMessages() {
        if (groupKey == null || msgListener != null) return;

        Query q = db.collection("groups").document(groupId)
                    .collection("messages")
                    .orderBy("timestamp", Query.Direction.ASCENDING);
        if (latestKnownTimestamp != null) {
            q = q.startAfter(latestKnownTimestamp);
        }
        // Bound the window so an absent cursor cannot pull unbounded history.
        // Matches the 1:1 chat's 300-message seed.
        q = q.limitToLast(300);

        msgListener = q.addSnapshotListener((snaps, e) -> {
            if (e != null) { Log.e(TAG, "Message listener error" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")"); return; }
            if (snaps == null || snaps.isEmpty()) return;

            // Charge only documents changed in this event; snaps.size() is the entire result
            // window and dramatically overstates reads during active conversations.
            int changedCount = snaps.getDocumentChanges().size();
            if (changedCount > 0) {
                FirebaseCostGuard.getInstance(this).recordReads(changedCount);
            }

            for (DocumentChange dc : snaps.getDocumentChanges()) {
                if (dc.getType() != DocumentChange.Type.ADDED) continue;
                com.google.firebase.firestore.DocumentSnapshot doc = dc.getDocument();

                String id     = doc.getString("id");
                String sender = doc.getString("sender");
                String cipher = doc.getString("text");
                Object ts     = doc.get("timestamp");
                // Media fields (present only on image/video messages)
                String  mediaPath = doc.getString("path");
                String  mediaKey  = doc.getString("mediaKey");
                // Inline thumbnail — a ~1.5 KB sealed JPEG carried inside the document so
                // the bubble can paint before any B2 download starts. Null on legacy media
                // and on albums, where each item carries its own thumb inside mediaItems.
                String  mediaThumb = doc.getString("thumb");
                String  docType   = doc.getString("type");
                String mediaItems = doc.getString("mediaItems");
                String caption    = doc.getString("caption");
                Boolean fwdFlag   = doc.getBoolean("forwarded");
                // mediaType: "image" | "video" | null (text message)
                String mType = (mediaItems != null && !mediaItems.isEmpty())
                        ? "album"
                        : ((mediaPath != null && !mediaPath.isEmpty())
                            ? (docType != null ? docType : "image") : null);

                if (id == null || sender == null) continue;
                if (cipher == null) cipher = ""; // media messages may have empty/null text
                if (knownIds.contains(id)) continue;
                knownIds.add(id);

                // Update latest timestamp for next listener re-attach.
                //
                // INVARIANT (same as ChatMediaActivity#advanceCursor): the cursor may
                // only ever advance from a resolved *server* timestamp. The instanceof
                // check already excludes our own optimistic echo, whose serverTimestamp()
                // is still null — and hasPendingWrites() excludes it explicitly. Feeding
                // a local clock value in here would make startAfter() filter out the
                // partner's next message permanently on the next re-attach.
                if (ts instanceof com.google.firebase.Timestamp
                        && !doc.getMetadata().hasPendingWrites()) {
                    com.google.firebase.Timestamp fts = (com.google.firebase.Timestamp) ts;
                    if (latestKnownTimestamp == null ||
                            fts.compareTo(latestKnownTimestamp) > 0) {
                        latestKnownTimestamp = fts;
                        final long serverMs = fts.toDate().getTime();
                        executor.execute(() -> getSharedPreferences(
                                "duoshield_prefs", MODE_PRIVATE)
                                .edit()
                                .putLong(PREF_GROUP_CURSOR_PREFIX + groupId, serverMs)
                                .apply());
                    }
                }

                long tsMs = ts instanceof com.google.firebase.Timestamp
                    ? ((com.google.firebase.Timestamp) ts).toDate().getTime()
                    : System.currentTimeMillis();

                // Own optimistic inserts are already in knownIds (added at send time).
                // The knownIds.contains(id) guard above (line ~284) already covers this —
                // no second check needed here (BUG-G04/U04 dead-code removal).

                String plain;
                try {
                    Boolean isEncrypted = doc.getBoolean("isEncrypted");
                    // S07-H3: reconstruct the exact AAD used at encrypt time.
                    // Only messages written with the current, AAD-aware send
                    // path carry "aadV1" — a legacy message (predating this
                    // fix) has no bound context, so it must be decrypted with
                    // no AAD or the GCM tag check fails.
                    Boolean aadV1 = doc.getBoolean("aadV1");
                    byte[] aad = Boolean.TRUE.equals(aadV1)
                        ? GroupCipherHelper.buildAad(groupId, sender, id)
                        : null;
                    // Media messages intentionally carry an empty text body.
                    // Captions and mediaItems are separate plaintext metadata,
                    // so do not attempt to decrypt an empty cipher.
                    plain = (Boolean.TRUE.equals(isEncrypted) && !cipher.isEmpty())
                        ? GroupCipherHelper.decrypt(cipher, groupKey, aad)
                        : cipher;
                } catch (Exception ex) {
                    Log.e(TAG, "Decrypt failed for msg " + com.duoshield.app.util.LogRedact.metadata(id) + " (" + com.duoshield.app.util.LogRedact.error(ex) + ")");
                    plain = "[Decryption failed]";
                }
                final String displayPlain = plain;

                // Build Message — include media fields when present
                final Message msg;
                if (mType != null) {
                    msg = new Message(id, groupId, sender, displayPlain, tsMs, false, mediaPath, mType);
                    msg.setMediaKey(mediaKey);
                    if (mediaThumb != null) msg.setThumb(mediaThumb);
                    if (mediaItems != null && !mediaItems.isEmpty()) msg.setMediaItems(mediaItems);
                    if (caption != null && !caption.isEmpty()) msg.setCaption(caption);
                } else {
                    msg = new Message(id, groupId, sender, displayPlain, tsMs, false);
                }
                msg.forwarded = Boolean.TRUE.equals(fwdFlag);
                adapter.appendMessage(msg);
                scrollToBottom();

                // Persist decrypted message to Room
                final Message toRoom;
                if (mType != null) {
                    toRoom = new Message(id, groupId, sender, displayPlain, tsMs, false, mediaPath, mType);
                    toRoom.setMediaKey(mediaKey);
                    // Persist the stamp too, so reopening the chat offline still paints a
                    // preview instead of an empty bubble.
                    if (mediaThumb != null) toRoom.setThumb(mediaThumb);
                    if (mediaItems != null && !mediaItems.isEmpty()) toRoom.setMediaItems(mediaItems);
                    if (caption != null && !caption.isEmpty()) toRoom.setCaption(caption);
                } else {
                    toRoom = new Message(id, groupId, sender, displayPlain, tsMs, false);
                }
                toRoom.forwarded = Boolean.TRUE.equals(fwdFlag);
                executor.execute(() -> {
                    try { localDb.messageDao().insert(toRoom); }
                    catch (Exception ex) { Log.w(TAG, "Room insert conflict for " + com.duoshield.app.util.LogRedact.metadata(id)); }
                    String preview = caption != null && !caption.isEmpty()
                            ? caption
                            : (mediaItems != null && !mediaItems.isEmpty()
                                ? "📷 Media album"
                                : (mType != null
                                    ? ("video".equals(mType) ? "Video 🎬" : "Photo 🖼")
                                    : (displayPlain.length() > 80
                                        ? displayPlain.substring(0, 80) : displayPlain)));
                    localDb.groupDao().updateLastMessage(groupId, preview, tsMs);
                });
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Send message
    // ════��════════════════════════════════════════════════════════════════════

    private void trySend() {
        if (groupKey == null) {
            Toast.makeText(this, "Group key not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;

        etMessage.setText("");

        String msgId = UUID.randomUUID().toString();
        long   now   = System.currentTimeMillis();

        // ── Optimistic UI ─────────────────────────────────────────��────────
        Message optimistic = new Message(msgId, groupId, myUid, text, now, false);
        optimistic.setStatus("pending");
        adapter.appendMessage(optimistic);
        knownIds.add(msgId);
        scrollToBottom();

        // ── Encrypt + write to Firestore ────────────────────────────────��──
        executor.execute(() -> {
            String cipher;
            try {
                // S07-H3: bind (groupId, sender, msgId) into the GCM tag so this
                // ciphertext only decrypts under its own context — not just under
                // whatever a Firestore rule currently allows.
                byte[] aad = GroupCipherHelper.buildAad(groupId, myUid, msgId);
                cipher = GroupCipherHelper.encrypt(text, groupKey, aad);
            } catch (Exception ex) {
                Log.e(TAG, "Group encrypt failed" + " (" + com.duoshield.app.util.LogRedact.error(ex) + ")");
                runOnUiThread(() -> {
                    optimistic.setStatus("failed");
                    adapter.updateMessage(msgId, m -> m.setStatus("failed"));
                    executor.execute(() -> localDb.messageDao().insert(optimistic));
                    Toast.makeText(this, "Encryption failed. Tap to retry.", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            Map<String, Object> doc = new HashMap<>();
            doc.put("id",          msgId);
            doc.put("sender",      myUid);
            doc.put("text",        cipher);
            doc.put("isEncrypted", true);
            doc.put("type",        "text");
            doc.put("status",      "sent");
            doc.put("timestamp",   FieldValue.serverTimestamp());
            // S07-H3: marks that "text" was encrypted with AAD bound to
            // (groupId, sender, id) — lets readers reconstruct the exact
            // same AAD instead of guessing whether one was used.
            doc.put("aadV1",       true);

            FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
            if (!guard.canWrite(1)) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Write quota reached — try again tomorrow", Toast.LENGTH_LONG).show());
                return;
            }
            db.collection("groups").document(groupId)
              .collection("messages").document(msgId)
              .set(doc)
              .addOnSuccessListener(v -> {
                  guard.recordWrites(1);
                  adapter.updateMessage(msgId, m -> m.setStatus("sent"));

                  // Persist to Room
                  Message stored = new Message(msgId, groupId, myUid, text, now, false);
                  stored.setStatus("sent");
                  executor.execute(() -> localDb.messageDao().insert(stored));

                  // Update group lastMessage in Room
                  String preview = text.length() > 80 ? text.substring(0, 80) : text;
                  executor.execute(() ->
                      localDb.groupDao().updateLastMessage(groupId, preview, now));

                  // Nudge Firestore group doc so the Cloud Function sends FCM
                  db.collection("groups").document(groupId)
                    .update("lastActivity", FieldValue.serverTimestamp())
                    .addOnFailureListener(ex ->
                        Log.w(TAG, "nudge failed (non-critical): " + com.duoshield.app.util.LogRedact.error(ex)));
              })
              .addOnFailureListener(ex -> {
                  runOnUiThread(() -> {
                      optimistic.setStatus("failed");
                      adapter.updateMessage(msgId, m -> m.setStatus("failed"));
                      executor.execute(() -> localDb.messageDao().insert(optimistic));
                      Toast.makeText(this, "Failed to send. Tap to retry.",
                              Toast.LENGTH_SHORT).show();
                  });
              });
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_GROUP) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchGroupCameraCapture();
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.CAMERA)) {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void retryMessage(Message msg) {
        adapter.removeMessage(msg.getId());
        knownIds.remove(msg.getId());
        etMessage.setText(msg.getText());
        trySend();
    }

    private void scrollToBottom() {
        int last = adapter.getItemCount() - 1;
        if (last >= 0) recyclerView.scrollToPosition(last);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Media / camera support
    // ═════════════════════════════════════════���═══════════════════════════════

    private void launchGroupMediaPreview(java.util.List<Uri> uris, String mediaType) {
        if (uris == null || uris.isEmpty()) return;
        Intent preview = new Intent(this, MediaSendPreviewActivity.class);
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (Uri uri : uris) values.add(uri.toString());
        preview.putStringArrayListExtra(MediaSendPreviewActivity.EXTRA_URIS, values);
        preview.putExtra(MediaSendPreviewActivity.EXTRA_URI, values.get(0));
        preview.putExtra(MediaSendPreviewActivity.EXTRA_MEDIA_TYPE, mediaType);
        groupMediaSendPreviewLauncher.launch(preview);
    }

    private void startGroupAlbumUpload(java.util.List<Uri> uris, String mediaType,
                                       String caption) {
        synchronized (groupMultiUploadLock) {
            pendingGroupItems.clear();
            groupMultiCompleted.set(0);
            pendingGroupTotal = uris.size();
            pendingGroupAlbumCaption = caption == null || caption.trim().isEmpty()
                    ? null : caption.trim();
        }
        runOnUiThread(() -> showGroupUploadPreview(uris.get(0), mediaType));
        for (Uri uri : uris) uploadGroupMedia(uri, mediaType);
    }

    private void showGroupUploadPreview(Uri uri, String mediaType) {
        if (isFinishing() || isDestroyed()) return;
        if (uploadGroupProgressContainer != null)
            uploadGroupProgressContainer.setVisibility(View.VISIBLE);
        if (tvGroupUploadPct != null) tvGroupUploadPct.setText("Preparing…");
        if (groupUploadThumb != null) {
            groupUploadThumb.setVisibility(View.VISIBLE);
            if ("video".equals(mediaType)) {
                com.bumptech.glide.Glide.with(this).asBitmap().load(uri)
                        .placeholder(R.drawable.bg_media_rounded)
                        .error(R.drawable.bg_media_rounded)
                        .centerCrop().into(groupUploadThumb);
            } else {
                com.bumptech.glide.Glide.with(this).load(uri)
                        .placeholder(R.drawable.bg_media_rounded)
                        .centerCrop().into(groupUploadThumb);
            }
        }
    }

    private boolean isGroupAlbumUpload() {
        synchronized (groupMultiUploadLock) {
            return pendingGroupTotal > 0;
        }
    }

    /**
     * @param sealedThumb inline thumbnail sealed under {@code mediaKey}, or {@code null}
     *                    when generation failed — receivers then fall back to the
     *                    existing download-and-decrypt path.
     */
    private void onGroupUploadComplete(String storagePath, String mediaType,
                                       String mediaKey, String sealedThumb) {
        final java.util.List<String[]> completeItems;
        final String completeCaption;
        synchronized (groupMultiUploadLock) {
            if (pendingGroupTotal <= 0) {
                final String singleCaption = pendingGroupCaption;
                pendingGroupCaption = null;
                runOnUiThread(() -> sendGroupMediaMessage(
                        storagePath, mediaType, mediaKey, singleCaption, sealedThumb));
                return;
            }
            pendingGroupItems.add(new String[]{storagePath, mediaType, mediaKey, sealedThumb});
            int done = groupMultiCompleted.incrementAndGet();
            if (done < pendingGroupTotal) return;
            completeItems = new java.util.ArrayList<>(pendingGroupItems);
            completeCaption = pendingGroupAlbumCaption;
            pendingGroupItems.clear();
            pendingGroupTotal = 0;
            pendingGroupAlbumCaption = null;
        }
        runOnUiThread(() -> {
            hideGroupUploadPreview();
            sendGroupAlbumMessage(completeItems, completeCaption);
        });
    }

    private void onGroupUploadFailed() {
        boolean albumFinished = false;
        boolean singleFailed = false;
        synchronized (groupMultiUploadLock) {
            if (pendingGroupTotal > 0) {
                albumFinished = groupMultiCompleted.incrementAndGet() >= pendingGroupTotal;
                if (albumFinished) {
                    pendingGroupItems.clear();
                    pendingGroupTotal = 0;
                    pendingGroupAlbumCaption = null;
                }
            } else {
                pendingGroupCaption = null;
                singleFailed = true;
            }
        }
        final boolean completedAlbum = albumFinished;
        if (completedAlbum || singleFailed) {
            runOnUiThread(() -> {
                hideGroupUploadPreview();
                Toast.makeText(this, completedAlbum
                                ? "Some items failed to upload."
                                : "Upload failed after multiple attempts.",
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void hideGroupUploadPreview() {
        if (uploadGroupProgressContainer != null)
            uploadGroupProgressContainer.setVisibility(View.GONE);
        if (groupUploadThumb != null) {
            groupUploadThumb.setVisibility(View.GONE);
            groupUploadThumb.setImageDrawable(null);
        }
    }

    /** Shows the media-type picker bottom sheet (image / video / camera). */
    private void showGroupMediaPickerSheet() {
        if (groupKey == null) {
            Toast.makeText(this, "Group key not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_media_picker, null);
        sheet.setContentView(view);

        view.findViewById(R.id.mediaPickerImage).setOnClickListener(v -> {
            sheet.dismiss();
            pickGroupImageLauncher.launch("image/*");
        });
        view.findViewById(R.id.mediaPickerVideo).setOnClickListener(v -> {
            sheet.dismiss();
            pickGroupVideoLauncher.launch("video/*");
        });
        view.findViewById(R.id.mediaPickerCamera).setOnClickListener(v -> {
            sheet.dismiss();
            launchGroupCameraCapture();
        });
        // Hide the contact card option — not applicable in group chat
        View contactPicker = view.findViewById(R.id.mediaPickerContact);
        if (contactPicker != null) contactPicker.setVisibility(View.GONE);

        sheet.show();
    }

    /**
     * Requests CAMERA permission if needed, creates a FileProvider-backed temp file,
     * and fires the system camera intent via {@link #takeGroupPictureLauncher}.
     */
    private void launchGroupCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_GROUP);
            return;
        }
        try {
            // S08-M2: write into the FileProvider-scoped shared/camera/ subdir
            // rather than the cache root, so the grant below is not scoped to
            // the whole cache directory.
            java.io.File photoFile = java.io.File.createTempFile(
                    "grp_cam_", ".jpg", com.duoshield.app.util.SharedCacheDir.camera(this));
            cameraGroupPhotoUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".provider", photoFile);
            takeGroupPictureLauncher.launch(cameraGroupPhotoUri);
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to create group camera temp file" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            Toast.makeText(this, "Camera error — could not create photo file",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Encrypts and uploads the given media URI, then sends a group message with
     * the B2 path and per-file AES key.  Files over 500 MB are rejected up-front.
     *
     * @param fileUri   Content URI from picker or camera.
     * @param mediaType "image" or "video".
     */
    private void uploadGroupMedia(Uri fileUri, String mediaType) {
        uploadGroupMediaWithRetry(fileUri, mediaType, 0);
    }

    /**
     * Last-chance path for a video that exceeds {@link MediaLimits#MAX_BYTES}:
     * re-encode to 720p H.264 / 128 kbps AAC, then re-measure. If the result is
     * still over the cap the send is rejected and the message states the size,
     * so the outcome is never silent.
     */
    private void maybeTranscodeThenUploadGroup(Uri fileUri, long originalBytes) {
        if (isFinishing() || isDestroyed()) return;

        if (!VideoTranscoder.isSupported()) {
            Toast.makeText(this,
                    MediaLimits.tooLargeMessage(originalBytes, "This video")
                            + " This device has no video encoder available to compress it.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        final VideoTranscoder.Cancel cancel = new VideoTranscoder.Cancel();
        final androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.compressing_video_title)
                        .setMessage(getString(R.string.compressing_video_body,
                                MediaLimits.format(originalBytes), 0))
                        .setCancelable(false)
                        .setNegativeButton(android.R.string.cancel, (d, w) -> cancel.cancel())
                        .create();
        dialog.show();

        if (transcodeExecutor == null || transcodeExecutor.isShutdown()) return;
        transcodeExecutor.execute(() -> {
            final VideoTranscoder.Result result = VideoTranscoder.transcode(
                    getApplicationContext(), fileUri, MediaLimits.MAX_BYTES,
                    percent -> runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (dialog.isShowing()) {
                            dialog.setMessage(getString(R.string.compressing_video_body,
                                    MediaLimits.format(originalBytes), percent));
                        }
                    }),
                    cancel);

            runOnUiThread(() -> {
                if (dialog.isShowing()) dialog.dismiss();

                // Activity gone, or user cancelled: drop the partial output.
                if (isFinishing() || isDestroyed() || cancel.isCancelled()) {
                    if (result.output != null) result.output.delete();
                    return;
                }

                if (!result.success) {
                    Toast.makeText(GroupChatActivity.this,
                            MediaLimits.tooLargeMessage(originalBytes, "This video")
                                    + " Compression failed: " + result.error,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                long compressed = MediaLimits.sizeOf(result.output);
                if (MediaLimits.isOversize(compressed)) {
                    result.output.delete();
                    Toast.makeText(GroupChatActivity.this,
                            getString(R.string.video_still_too_large,
                                    MediaLimits.format(compressed),
                                    MediaLimits.format(MediaLimits.MAX_BYTES)),
                            Toast.LENGTH_LONG).show();
                    return;
                }

                Toast.makeText(GroupChatActivity.this,
                        getString(R.string.video_compressed_ok,
                                MediaLimits.format(originalBytes),
                                MediaLimits.format(compressed)),
                        Toast.LENGTH_SHORT).show();
                uploadGroupMediaWithRetry(Uri.fromFile(result.output), "video", 0);
            });
        });
    }

    private void uploadGroupMediaWithRetry(Uri fileUri, String mediaType, int retryCount) {
        if (isFinishing() || isDestroyed()) return;
        if (retryCount > 3) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (uploadGroupProgressContainer != null)
                    uploadGroupProgressContainer.setVisibility(View.GONE);
                Toast.makeText(this,
                        "Upload failed after multiple attempts. Please check your connection.",
                        Toast.LENGTH_LONG).show();
            });
            return;
        }

        // Cap check before any read, compression, encryption, or upload work.
        // Oversized videos are routed through the transcoder first; anything
        // else over the ceiling is rejected with its measured size.
        if (retryCount == 0) {
            long size = getGroupFileSize(fileUri);
            if (MediaLimits.isOversize(size)) {
                if ("video".equals(mediaType)) {
                    maybeTranscodeThenUploadGroup(fileUri, size);
                } else {
                    Toast.makeText(this,
                            MediaLimits.tooLargeMessage(size, "File"),
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
        }

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (uploadGroupProgressContainer != null) {
                uploadGroupProgressContainer.setVisibility(View.VISIBLE);
                if (tvGroupUploadPct != null) tvGroupUploadPct.setText("Preparing…");
            }
        });

        String ext  = "video".equals(mediaType) ? ".mp4" : ".jpg";
        String mime = "video".equals(mediaType) ? "video/mp4" : "image/jpeg";
        String path = "groups/" + groupId + "/" + UUID.randomUUID() + ext;

        if (executor.isShutdown()) return;
        executor.execute(() -> {
            // Videos larger than LARGE_FILE_THRESHOLD use the disk-streaming path to
            // avoid loading the full plaintext + ciphertext into memory simultaneously.
            boolean useLargeFilePath = "video".equals(mediaType)
                    && getGroupFileSize(fileUri) > LARGE_FILE_THRESHOLD;
            try {
                if (useLargeFilePath) {
                    // ── Streaming path: encrypt to disk → upload from disk ─────────
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && tvGroupUploadPct != null)
                            tvGroupUploadPct.setText("Encrypting…");
                    });
                    java.io.File encTmp = java.io.File.createTempFile("enc_", ".tmp", getCacheDir());
                    try {
                        String mediaKey = B2StorageHelper.encryptUriToFile(
                                getContentResolver(), fileUri, encTmp);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed() && tvGroupUploadPct != null)
                                tvGroupUploadPct.setText("0%");
                        });
                        // Pull the preview frame from the local file while the upload is still
                        // in flight. This is the whole point for video: every group member
                        // gets a visible frame without downloading a byte of a 500 MB object.
                        final String sealedThumb = InlineThumb.sealedFromVideoUri(
                                GroupChatActivity.this, fileUri, mediaKey);
                        String storagePath = B2StorageHelper.uploadFileFromDisk(
                                encTmp, path, mime,
                                pct -> runOnUiThread(() -> {
                                    if (!isFinishing() && !isDestroyed() && tvGroupUploadPct != null)
                                        tvGroupUploadPct.setText(pct + "%");
                                }));
                        final String finalMediaKey = mediaKey;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            onGroupUploadComplete(storagePath, mediaType, finalMediaKey,
                                    sealedThumb);
                        });
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        encTmp.delete();
                    }
                } else {
                    // ── In-memory path (images + small videos ≤ 50 MB) ───────────
                    byte[] plain = readGroupUriBytes(fileUri);
                    if (plain == null || plain.length == 0)
                        throw new java.io.IOException("Failed to read file or file is empty");

                    // Compress images to save bandwidth
                    if ("image".equals(mediaType)) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed() && tvGroupUploadPct != null)
                                tvGroupUploadPct.setText("Compressing…");
                        });
                        plain = compressGroupImage(plain);
                    }

                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && tvGroupUploadPct != null)
                            tvGroupUploadPct.setText("0%");
                    });

                    B2StorageHelper.EncryptedMedia enc = B2StorageHelper.encryptForUpload(plain);

                    // Build the stamp from the bytes already in hand — for images that is the
                    // post-compression buffer, so the preview matches what recipients will
                    // eventually see. Costs a few milliseconds and no network. Sealed under
                    // the media key, so it is exactly as private as the object it previews.
                    final String sealedThumb = "video".equals(mediaType)
                            ? InlineThumb.sealedFromVideoUri(
                                    GroupChatActivity.this, fileUri, enc.keyBase64)
                            : InlineThumb.sealedFromImageBytes(plain, enc.keyBase64);

                    String storagePath = B2StorageHelper.uploadFile(
                            enc.data, path, mime,
                            pct -> runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed() && tvGroupUploadPct != null)
                                    tvGroupUploadPct.setText(pct + "%");
                            }));

                    final String mediaKey = enc.keyBase64;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        onGroupUploadComplete(storagePath, mediaType, mediaKey, sealedThumb);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Group media upload failed (attempt " + retryCount + ")" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                if (retryCount >= 3) {
                    runOnUiThread(this::onGroupUploadFailed);
                    return;
                }
                long delayMs = (long) (1000 * Math.pow(2, retryCount));
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> uploadGroupMediaWithRetry(fileUri, mediaType, retryCount + 1),
                        delayMs);
            }
        });
    }

    /**
     * Writes a group media message to Firestore after a successful B2 upload.
     * The message carries the B2 path and per-file AES key; the group AES key
     * decrypts the B2 ciphertext on each recipient's device.
     *
     * @param sealedThumb ~1.5 KB inline thumbnail, AES-GCM sealed under {@code mediaKey}
     *                    and base64-encoded, produced from the local file before upload.
     *                    Rides inside the message document so every recipient's bubble
     *                    paints the instant the snapshot lands. {@code null} is fine.
     */
    private void sendGroupMediaMessage(String storagePath, String mediaType,
                                       String mediaKey, String caption, String sealedThumb) {
        if (groupKey == null) return;
        String msgId = UUID.randomUUID().toString();
        long   now   = System.currentTimeMillis();

        // Optimistic UI
        Message optimistic = new Message(msgId, groupId, myUid, "", now, false, storagePath, mediaType);
        optimistic.setMediaKey(mediaKey);
        optimistic.setThumb(sealedThumb);
        if (caption != null && !caption.isEmpty()) optimistic.setCaption(caption);
        optimistic.setStatus("pending");
        adapter.appendMessage(optimistic);
        knownIds.add(msgId);
        scrollToBottom();

        Map<String, Object> doc = new HashMap<>();
        doc.put("id",          msgId);
        doc.put("sender",      myUid);
        doc.put("text",        "");
        doc.put("isEncrypted", true);
        doc.put("type",        mediaType);
        doc.put("mediaType",   mediaType);
        doc.put("path",        storagePath);
        doc.put("mediaKey",    mediaKey);
        doc.put("status",      "sent");
        doc.put("timestamp",   FieldValue.serverTimestamp());
        if (caption != null && !caption.isEmpty()) doc.put("caption", caption);
        // Omitted entirely when absent — an empty string would cost bytes on every
        // listener delivery, for every member of the group, for no benefit.
        if (sealedThumb != null && !sealedThumb.isEmpty()) doc.put("thumb", sealedThumb);

        FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
        if (!guard.canWrite(1)) {
            runOnUiThread(() -> Toast.makeText(this,
                    "Write quota reached — try again tomorrow", Toast.LENGTH_LONG).show());
            return;
        }
        db.collection("groups").document(groupId)
          .collection("messages").document(msgId)
          .set(doc)
          .addOnSuccessListener(v -> {
              guard.recordWrites(1);
              adapter.updateMessage(msgId, m -> m.setStatus("sent"));
              // Persist to Room
              Message stored = new Message(msgId, groupId, myUid, "", now, false, storagePath, mediaType);
              stored.setMediaKey(mediaKey);
              stored.setThumb(sealedThumb);
              if (caption != null && !caption.isEmpty()) stored.setCaption(caption);
              stored.setStatus("sent");
              executor.execute(() -> localDb.messageDao().insert(stored));
              String preview = caption != null && !caption.isEmpty()
                      ? caption : ("video".equals(mediaType) ? "Video 🎬" : "Photo 🖼");
              executor.execute(() -> localDb.groupDao().updateLastMessage(groupId, preview, now));
              // Nudge group doc for FCM
              db.collection("groups").document(groupId)
                .update("lastActivity", FieldValue.serverTimestamp())
                .addOnFailureListener(ex ->
                    Log.w(TAG, "nudge failed (non-critical): " + com.duoshield.app.util.LogRedact.error(ex)));
          })
          .addOnFailureListener(ex -> {
              adapter.updateMessage(msgId, m -> m.setStatus("failed"));
              // Clean up orphaned B2 file
              if (B2StorageHelper.isB2Path(storagePath)) {
                  executor.execute(() -> {
                      try { B2StorageHelper.deleteFile(storagePath); }
                      catch (Exception ignored) {}
                  });
              }
              Toast.makeText(this, "Failed to send media.", Toast.LENGTH_SHORT).show();
          });
    }

    /** Sends all selected media as one group message, keeping one shared caption. */
    private void sendGroupAlbumMessage(java.util.List<String[]> items, String caption) {
        if (groupKey == null || items == null || items.isEmpty()) return;
        String msgId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        org.json.JSONArray array = new org.json.JSONArray();
        for (String[] item : items) {
            try {
                org.json.JSONObject value = new org.json.JSONObject();
                value.put("path", item[0]);
                value.put("type", item[1]);
                value.put("key", item[2]);
                // item[3] is that item's own sealed stamp. An album grid is the worst case
                // for the old behaviour — four video slots meant four full object downloads
                // kicked off during a single scroll pass.
                if (item.length > 3 && item[3] != null && !item[3].isEmpty()) {
                    value.put("thumb", item[3]);
                }
                array.put(value);
            } catch (org.json.JSONException ignored) {}
        }
        String mediaItems = array.toString();

        Message optimistic = new Message(msgId, groupId, myUid, "", now, false,
                null, "album");
        optimistic.setMediaItems(mediaItems);
        if (caption != null && !caption.isEmpty()) optimistic.setCaption(caption);
        optimistic.setStatus("pending");
        adapter.appendMessage(optimistic);
        knownIds.add(msgId);
        scrollToBottom();

        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId);
        doc.put("sender", myUid);
        doc.put("text", "");
        doc.put("isEncrypted", true);
        doc.put("type", "album");
        doc.put("mediaType", "album");
        doc.put("mediaItems", mediaItems);
        doc.put("status", "sent");
        doc.put("timestamp", FieldValue.serverTimestamp());
        if (caption != null && !caption.isEmpty()) doc.put("caption", caption);

        FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
        if (!guard.canWrite(1)) {
            adapter.updateMessage(msgId, m -> m.setStatus("failed"));
            return;
        }
        db.collection("groups").document(groupId)
                .collection("messages").document(msgId)
                .set(doc)
                .addOnSuccessListener(v -> {
                    guard.recordWrites(1);
                    adapter.updateMessage(msgId, m -> m.setStatus("sent"));
                    Message stored = new Message(msgId, groupId, myUid, "", now, false,
                            null, "album");
                    stored.setMediaItems(mediaItems);
                    if (caption != null && !caption.isEmpty()) stored.setCaption(caption);
                    stored.setStatus("sent");
                    executor.execute(() -> {
                        localDb.messageDao().insert(stored);
                        String preview = caption != null && !caption.isEmpty()
                                ? caption : "📷 " + items.size() + " media items";
                        localDb.groupDao().updateLastMessage(groupId, preview, now);
                    });
                    db.collection("groups").document(groupId)
                            .update("lastActivity", FieldValue.serverTimestamp())
                            .addOnFailureListener(ex ->
                                    Log.w(TAG, "nudge failed (non-critical): " + com.duoshield.app.util.LogRedact.error(ex)));
                })
                .addOnFailureListener(ex -> {
                    adapter.updateMessage(msgId, m -> m.setStatus("failed"));
                    Toast.makeText(this, "Failed to send album.", Toast.LENGTH_SHORT).show();
                });
    }

    /** Returns the file size in bytes from ContentResolver, or 0 if unavailable. */
    /** Size of a picked Uri, or -1 when unresolvable. See {@link MediaLimits#sizeOf}. */
    private long getGroupFileSize(Uri uri) {
        return MediaLimits.sizeOf(this, uri);
    }

    /** Reads all bytes from a content URI. Returns null on error. */
    private byte[] readGroupUriBytes(Uri uri) {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "readGroupUriBytes failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    /**
     * Compresses a raw image to max 1280px on the longest side at JPEG 85.
     * Returns the original bytes if compression would produce a larger result.
     */
    private byte[] compressGroupImage(byte[] raw) {
        try {
            final int MAX_DIM = 1280;
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
            int w = probe.outWidth, h = probe.outHeight;
            if (w <= 0 || h <= 0) return raw;

            int sampleSize = 1;
            while ((w / sampleSize) > MAX_DIM * 2 || (h / sampleSize) > MAX_DIM * 2) sampleSize *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(
                    raw, 0, raw.length, opts);
            if (bm == null) return raw;

            int bW = bm.getWidth(), bH = bm.getHeight();
            if (bW > MAX_DIM || bH > MAX_DIM) {
                float scale = (float) MAX_DIM / Math.max(bW, bH);
                bm = android.graphics.Bitmap.createScaledBitmap(
                        bm, Math.round(bW * scale), Math.round(bH * scale), true);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            bm.recycle();
            byte[] compressed = out.toByteArray();
            return compressed.length < raw.length ? compressed : raw;
        } catch (Exception e) {
            Log.w(TAG, "compressGroupImage failed — using original" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return raw;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group management — member removal + key rotation (F3)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Shows a dialog listing all members. The group admin sees a "Remove" button
     * next to each member (except themselves).
     */
    private void showGroupInfoSheet() {
        if (memberUids == null || memberUids.isEmpty()) return;
        boolean isAdmin = myUid != null && myUid.equals(creatorUid);
        float dp = getResources().getDisplayMetrics().density;

        android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.setPadding((int)(20*dp),(int)(12*dp),(int)(20*dp),(int)(12*dp));

        for (String uid : memberUids) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams rLp =
                new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = (int)(8*dp);
            row.setLayoutParams(rLp);

            android.widget.TextView tvUid = new android.widget.TextView(this);
            String disp = uid.length() > 22 ? uid.substring(0,22)+"…" : uid;
            tvUid.setText(myUid.equals(uid) ? disp + "  (You)" : disp);
            tvUid.setTextColor(0xFFDDDDDD);
            tvUid.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            android.widget.LinearLayout.LayoutParams tLp =
                new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvUid.setLayoutParams(tLp);
            row.addView(tvUid);

            if (isAdmin && !uid.equals(myUid)) {
                android.widget.TextView btnRm = new android.widget.TextView(this);
                btnRm.setText("Remove");
                btnRm.setTextColor(0xFFFF5252);
                btnRm.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                btnRm.setPadding((int)(12*dp), 0, 0, 0);
                final String target = uid;
                btnRm.setOnClickListener(v ->
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Remove member?")
                        .setMessage("This removes the member and rotates the group encryption key. "
                            + "They will not be able to read new messages.")
                        .setPositiveButton("Remove", (d2, w) -> removeMemberAndRotateKey(target))
                        .setNegativeButton("Cancel", null)
                        .show());
                row.addView(btnRm);
            }
            ll.addView(row);
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(ll);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Group: " + groupName)
            .setView(sv)
            .setPositiveButton("Close", null)
            .show();
    }

    /**
     * Removes {@code memberUid} from the group and rotates the AES-256-GCM group key
     * so they cannot decrypt future messages (F3 fix).
     *
     * <p>Steps (all on the executor thread):
     * <ol>
     *   <li>POST {@code /removeGroupMember} → server uses Admin SDK to atomically remove
     *       the member from the Firestore members array and delete their key document.</li>
     *   <li>Generate a new {@link com.duoshield.app.crypto.GroupCipherHelper#generateGroupKey()} key.</li>
     *   <li>Signal-encrypt the new key for every remaining member and write to
     *       {@code groups/{id}/keys/{memberUid}}.</li>
     *   <li>Persist the new key in Room and update the in-memory {@link #groupKey}.</li>
     * </ol>
     */
    private void removeMemberAndRotateKey(String memberUid) {
        if (myUid == null || !myUid.equals(creatorUid)) return;

        executor.execute(() -> {
            // ── Step 1: server call ────────────────────────────────────────
            try {
                com.google.firebase.auth.FirebaseUser user =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                if (user == null) throw new Exception("Not authenticated");

                final String[] tok = {null};
                final Object   lk  = new Object();
                user.getIdToken(false)
                    .addOnSuccessListener(r -> { synchronized(lk){tok[0]=r.getToken();lk.notifyAll();} })
                    .addOnFailureListener(e -> { synchronized(lk){lk.notifyAll();} });
                synchronized (lk) {
                    if (tok[0] == null) try { lk.wait(10_000); } catch (InterruptedException ig) {}
                }
                if (tok[0] == null) throw new Exception("Auth token unavailable");

                java.net.URL surl = new java.net.URL(
                    BuildConfig.PUSH_SERVER_URL + "/removeGroupMember");
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) surl.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + tok[0]);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                JSONObject reqBody = new JSONObject();
                reqBody.put("groupId", groupId);
                reqBody.put("memberUid", memberUid);
                conn.getOutputStream().write(reqBody.toString().getBytes("UTF-8"));
                conn.getOutputStream().close();
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code != 200) throw new Exception("Server returned HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "removeMember server call failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                runOnUiThread(() -> Toast.makeText(this,
                    "Failed to remove member: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }

            // ── Step 2: rotate the group key ──────────────────────────────
            String newKey;
            try {
                newKey = com.duoshield.app.crypto.GroupCipherHelper.generateGroupKey();
            } catch (Exception e) {
                Log.e(TAG, "generateGroupKey failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                runOnUiThread(() -> Toast.makeText(this,
                    "Member removed but key rotation failed.", Toast.LENGTH_LONG).show());
                return;
            }

            List<String> remaining = new ArrayList<>(memberUids);
            remaining.remove(memberUid);

            FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
            for (String uid : remaining) {
                if (myUid.equals(uid)) continue; // own key stored locally below
                try {
                    com.duoshield.app.crypto.signal.SignalCipherHelper.EncryptResult r =
                        com.duoshield.app.crypto.signal.SignalCipherHelper.encrypt(this, uid, newKey);
                    Map<String, Object> kd = new HashMap<>();
                    kd.put("encryptedKey", r.ciphertextB64);
                    kd.put("sigType",      r.sigType);
                    kd.put("senderUid",    myUid);
                    if (guard.canWrite(1)) {
                        db.collection("groups").document(groupId)
                          .collection("keys").document(uid)
                          .set(kd)
                          .addOnSuccessListener(v -> guard.recordWrites(1));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Key dist failed for " + LogRedact.uid(uid) + ": " + com.duoshield.app.util.LogRedact.error(e));
                }
            }

            // ── Step 3: persist locally ───────────────────────────────────
            groupKey   = newKey;
            memberUids = remaining;
            localDb.groupDao().updateGroupKey(groupId, newKey);
            localDb.groupDao().deleteMember(groupId, memberUid);

            runOnUiThread(() ->
                Toast.makeText(this, "Member removed. Group key rotated.",
                    Toast.LENGTH_SHORT).show());
        });
    }
}
