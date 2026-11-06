package com.duoshield.app;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import com.duoshield.app.util.ArchiveHelper;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Conversation;
import com.duoshield.app.models.Group;
import com.duoshield.app.models.Message;
import com.duoshield.app.util.ForwardMessageHelper;
import com.duoshield.app.ui.ConversationAdapter;
import com.duoshield.app.ui.CreateGroupActivity;
import com.duoshield.app.ui.SeedPhraseDisplayActivity;

import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.backup.BackupScheduler;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.ContactBackupHelper;
import com.duoshield.app.util.DevicePerformanceTier;
import com.duoshield.app.util.FcmTokenHelper;
import com.duoshield.app.util.FirebaseCostGuard;
import com.duoshield.app.util.MotionBudget;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConversationListActivity extends BaseActivity {

    private static final int MAX_LIVE_CONVERSATIONS = 100;

    public static final String EXTRA_FORWARD_MODE = "forward_mode";
    public static final String EXTRA_FORWARD_MESSAGE_ID = "forward_message_id";
    public static final String EXTRA_FORWARD_SOURCE_CONVERSATION = "forward_source_conversation";
    public static final String EXTRA_FORWARD_SOURCE_SENDER = "forward_source_sender";
    public static final String EXTRA_FORWARD_MEDIA_URL = "forward_media_url";
    public static final String EXTRA_FORWARD_MEDIA_KEY = "forward_media_key";
    public static final String EXTRA_FORWARD_MEDIA_TYPE = "forward_media_type";
    public static final String EXTRA_FORWARD_TEXT = "forward_text";
    public static final String EXTRA_FORWARD_TIMESTAMP = "forward_timestamp";

    private RecyclerView        recyclerView;
    private ConversationAdapter adapter;
    private LinearLayout        emptyState;
    private LinearLayout        searchBar;
    private EditText            etSearch;
    private SwipeRefreshLayout  swipeRefresh;
    private View                shimmerContainer;
    private View                archivedBanner;
    private ObjectAnimator      shimmerAnimator;
    private boolean             firstLoadDone   = false;
    private boolean             showArchived    = false;

    private FirebaseFirestore    db;
    private ListenerRegistration listener;
    private String               myUid;
    private List<Conversation>   allConversations    = new ArrayList<>();
    private List<Conversation>   directConversations = new ArrayList<>();
    private List<Conversation>   groupConversations  = new ArrayList<>();
    private ExecutorService      executor;
    private AppDatabase          localDb;
    /** Incremented each time a new filter pass starts; stale async results are discarded. */
    private volatile int         filterSeq           = 0;
    private boolean               forwardMode        = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation_list);
        forwardMode = getIntent().getBooleanExtra(EXTRA_FORWARD_MODE, false);
        if (forwardMode) {
            android.widget.TextView title = findViewById(R.id.tv_screen_title);
            if (title != null) title.setText("Forward to…");
            View callHistory = findViewById(R.id.btn_call_history);
            View searchToggle = findViewById(R.id.btn_search_toggle);
            View menu = findViewById(R.id.btn_menu);
            if (callHistory != null) callHistory.setVisibility(View.GONE);
            if (searchToggle != null) searchToggle.setVisibility(View.GONE);
            if (menu != null) menu.setVisibility(View.GONE);
        }

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        myUid = prefs.getString("my_uid", null);
        if (myUid == null) {
            com.google.firebase.auth.FirebaseUser fu =
                    FirebaseAuth.getInstance().getCurrentUser();
            if (fu != null) myUid = fu.getUid();
        }
        // BUG-U03: myUid may still be null after the Firebase fallback (no signed-in user).
        // Continuing with a null myUid would produce NullPointerExceptions at call sites
        // that build Firestore field paths like "partnerName_" + myUid (→ "partnerName_null").
        if (myUid == null) {
            android.util.Log.w("ConversationListActivity", "myUid is null after recovery — redirecting to sign-in.");
            startActivity(new Intent(this, SignInActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        executor = Executors.newSingleThreadExecutor();
        localDb  = AppDatabase.getInstance(this);

        // Restore contacts from backup (created by WipeHelper before Wipe & Exit).
        // No-op if no backup exists or the backup belongs to a different UID.
        final String uidForRestore = myUid;
        executor.execute(() -> ContactBackupHelper.restoreIfNeeded(getApplicationContext(), uidForRestore));

        // Schedule the daily background backup sync (KEEP policy — safe to call every launch).
        BackupScheduler.schedule(this);

        // Ensure FCM token is uploaded for this user on every app launch.
        // Covers the case where onNewToken() fired before sign-in completed (new users).
        FcmTokenHelper.register(this);

        // Show "Account Created" snackbar on first launch after sign-up
        if (getIntent().getBooleanExtra(SeedPhraseDisplayActivity.EXTRA_ACCOUNT_CREATED, false)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                View root = getWindow().getDecorView().getRootView();
                Snackbar.make(root, "🎉 Welcome to DuoShield! Your account has been created.",
                        Snackbar.LENGTH_LONG)
                        .setBackgroundTint(0xFF9A81FF)
                        .setTextColor(0xFFFFFFFF)
                        .show();
            }, 600);
        }

        recyclerView     = findViewById(R.id.recyclerConversations);
        emptyState       = findViewById(R.id.tvEmpty);
        searchBar        = findViewById(R.id.searchBar);
        etSearch         = findViewById(R.id.etSearch);
        swipeRefresh     = findViewById(R.id.swipeRefresh);
        shimmerContainer = findViewById(R.id.shimmerContainer);

        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(0xFF00C9E0);
            swipeRefresh.setProgressBackgroundColorSchemeColor(0xFF0F1620);
            swipeRefresh.setOnRefreshListener(() -> {
                firstLoadDone = false;
                if (shimmerContainer != null) shimmerContainer.setVisibility(View.VISIBLE);
                // Keep the live listener attached across ordinary background/foreground
                // transitions. Removing it here forces Firestore to bill the complete query
                // result again when the screen is reopened.
                if (listener == null) listenForConversation();
                loadGroupsFromRoom();
            });
        }

        if (shimmerContainer != null && !MotionBudget.disableHeavyDecoration(this)) {
            shimmerAnimator = ObjectAnimator.ofFloat(shimmerContainer, "alpha", 0.4f, 1.0f);
            shimmerAnimator.setDuration(900);
            shimmerAnimator.setRepeatMode(ObjectAnimator.REVERSE);
            shimmerAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            shimmerAnimator.start();
        } else if (shimmerContainer != null) {
            shimmerContainer.setAlpha(1.0f);
        }

        android.widget.Button btnEmptyAdd = findViewById(R.id.btnEmptyAddContact);
        if (btnEmptyAdd != null) {
            btnEmptyAdd.setOnClickListener(v ->
                    startActivity(new Intent(this, com.duoshield.app.ui.AddContactActivity.class)));
        }

        archivedBanner = findViewById(R.id.archived_banner);
        if (archivedBanner != null) {
            archivedBanner.setOnClickListener(v -> {
                showArchived = !showArchived;
                mergeAndFilter();
            });
        }

        adapter = new ConversationAdapter(new ConversationAdapter.OnConversationClickListener() {
            @Override public void onClick(Conversation conv)     { openChat(conv); }
            @Override public void onLongClick(Conversation conv) {
                if (!forwardMode) openContactDetail(conv);
            }
        });

        LinearLayoutManager convLlm = new LinearLayoutManager(this);
        convLlm.setInitialPrefetchItemCount(
                DevicePerformanceTier.get(this).recyclerViewPrefetchCount());
        recyclerView.setLayoutManager(convLlm);
        recyclerView.setHasFixedSize(true);
        // Smaller off-screen view cache on LOW so cached rows do not pin decoded avatars
        // against the reduced Glide bitmap pool. See ChatMediaActivity for the reasoning.
        recyclerView.setItemViewCacheSize(
                DevicePerformanceTier.get(this).recyclerViewCacheSize());
        recyclerView.setAdapter(adapter);
        db = FirebaseFirestore.getInstance();

        // Call history
        ImageView btnCallHistory = findViewById(R.id.btn_call_history);
        if (btnCallHistory != null) {
            btnCallHistory.setOnClickListener(v ->
                    startActivity(new Intent(this, com.duoshield.app.call.CallHistoryActivity.class)));
        }

        // Search toggle — search replaces the header instead of stacking beneath it,
        // so the top bar stays a single 64dp row like WhatsApp/Telegram.
        View headerBar = findViewById(R.id.headerBar);
        ImageView btnSearchToggle = findViewById(R.id.btn_search_toggle);
        ImageView btnCloseSearch  = findViewById(R.id.btn_close_search);
        btnSearchToggle.setOnClickListener(v -> {
            if (headerBar != null) headerBar.setVisibility(View.GONE);
            searchBar.setVisibility(View.VISIBLE);
            etSearch.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etSearch,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        btnCloseSearch.setOnClickListener(v -> {
            searchBar.setVisibility(View.GONE);
            if (headerBar != null) headerBar.setVisibility(View.VISIBLE);
            etSearch.setText("");
            filterConversations("");
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterConversations(s.toString().trim());
            }
        });

        // Overflow menu
        ImageView btnMenu = findViewById(R.id.btn_menu);
        btnMenu.setOnClickListener(v -> {
            // UX audit item #7: the old global "Key Fingerprint" entry here resolved
            // to whichever chat was "last active", which was confusing in the
            // multi-contact case. Fingerprint verification now lives per-chat, in
            // each conversation's own overflow menu (ChatMediaActivity → Encryption),
            // where the partner is unambiguous.
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Settings");
            popup.getMenu().add(0, 3, 0, "New Chat");
            popup.getMenu().add(0, 5, 0, "New Group");
            popup.getMenu().add(0, 2, 0, "Quota");
            popup.getMenu().add(0, 4, 0, "Wipe & Exit");
            popup.getMenu().add(0, 6, 0, "Search messages");
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) { startActivity(new Intent(this, com.duoshield.app.ui.SettingsActivity.class)); return true; }
                if (id == 3) { startActivity(new Intent(this, com.duoshield.app.ui.AddContactActivity.class)); return true; }
                if (id == 5) { startActivity(new Intent(this, CreateGroupActivity.class)); return true; }
                if (id == 2) { showQuotaDialog(); return true; }
                if (id == 4) { confirmWipeAndExit(); return true; }
                if (id == 6) {
                    Intent search = new Intent(this, MessageSearchActivity.class);
                    search.putExtra(MessageSearchActivity.EXTRA_GLOBAL_SEARCH, true);
                    startActivity(search);
                    return true;
                }
                return false;
            });
            popup.show();
        });

        // FAB → bottom sheet with New Chat / New Group
        com.google.android.material.floatingactionbutton.FloatingActionButton fabNewChat =
                findViewById(R.id.fabNewChat);
        if (fabNewChat != null) {
            fabNewChat.setOnClickListener(v -> {
                com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                    new com.google.android.material.bottomsheet.BottomSheetDialog(
                        this, R.style.Theme_DuoShield_BottomSheet);
                android.view.View sheetView = getLayoutInflater()
                    .inflate(R.layout.bottom_sheet_new_conversation, null);
                sheet.setContentView(sheetView);
                sheetView.findViewById(R.id.btnNewChat).setOnClickListener(b -> {
                    sheet.dismiss();
                    startActivity(new Intent(this, com.duoshield.app.ui.AddContactActivity.class));
                });
                sheetView.findViewById(R.id.btnNewGroup).setOnClickListener(b -> {
                    sheet.dismiss();
                    startActivity(new Intent(this, CreateGroupActivity.class));
                });
                sheet.show();
            });
        }

        setupSwipeActions();

        // NOTE: DO NOT call listenForConversation() here.
        // It is attached in onStart() so it is properly detached/re-attached
        // across the activity lifecycle without leaking a second registration.
    }

    /**
     * Wipe & Exit is irreversible and unrecoverable — it must never be a single
     * mis-tap away. This mirrors the friction of the Settings "Unpair Device"
     * confirmation and adds a typed confirmation step since this entry point
     * sits in a generic overflow menu next to routine actions like "New Chat".
     */
    private void showQuotaDialog() {
        String summary = com.duoshield.app.util.FirebaseQuotaSummary.get(this);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Firebase Quota (Today)")
                .setMessage(summary)
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmWipeAndExit() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Wipe & Exit")
                .setMessage("This will permanently delete all messages, media, and contacts on this device and sign you out. This cannot be undone.")
                .setPositiveButton("Continue", (d, w) -> showWipeTypeToConfirmDialog())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showWipeTypeToConfirmDialog() {
        final TextInputEditText input = new TextInputEditText(this);
        input.setHint("Type DELETE to confirm");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(input);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Type DELETE to confirm")
                .setView(container)
                .setPositiveButton("Wipe & Exit", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            android.widget.Button positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            positive.setEnabled(false);
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    positive.setEnabled("DELETE".contentEquals(s));
                }
            });
            positive.setOnClickListener(v -> {
                dialog.dismiss();
                com.duoshield.app.util.WipeHelper.wipeAll(ConversationListActivity.this);
            });
        });
        dialog.show();
    }

    /**
     * Listens to all chats the current user participates in.
     *
     * FIRESTORE RULE REQUIREMENT:
     *   The query whereArrayContains("participants", myUid) requires a security rule that allows:
     *     request.auth.uid in resource.data.participants
     *   on the chats collection.
     */
    private void listenForConversation() {
        listener = db.collection("chats")
            .whereArrayContains("participants", myUid)
            .limit(MAX_LIVE_CONVERSATIONS)
            .addSnapshotListener((snapshots, e) -> {
                if (e != null) {
                    android.util.Log.w("ConversationListActivity", "listenForConversation: error — " + com.duoshield.app.util.LogRedact.error(e));
                    return;
                }
                if (snapshots == null) return;

                // PERF-OPT-03: Use DocumentChange events instead of rebuilding the entire list.
                // This processes only conversations that changed (added/modified/removed),
                // dramatically reducing CPU work on each snapshot, especially for many chats.
                boolean hasChanges = false;
                for (com.google.firebase.firestore.DocumentChange change : snapshots.getDocumentChanges()) {
                    com.google.firebase.firestore.DocumentSnapshot doc = change.getDocument();
                    String chatId = doc.getId();

                    @SuppressWarnings("unchecked")
                    java.util.List<String> participants =
                            (java.util.List<String>) doc.get("participants");
                    String partnerUid = null;
                    if (participants != null) {
                        for (String p : participants) {
                            if (p != null && !p.equals(myUid)) { partnerUid = p; break; }
                        }
                    }
                    if (partnerUid == null) continue;

                    Conversation conv = new Conversation();
                    conv.id         = chatId;
                    conv.partnerUid = partnerUid;

                    Object pName = doc.get("partnerName_" + myUid);
                    conv.partnerName = (pName != null && !pName.toString().isEmpty())
                            ? pName.toString() : "Partner";

                    // ConversationMetaUpdater writes the plaintext preview (≤80 chars) here.
                    Object last = doc.get("lastMessage");
                    conv.lastMessage = (last != null) ? last.toString() : "";

                    Object ts = doc.get("lastMessageTs");
                    conv.lastMessageTs = ts instanceof com.google.firebase.Timestamp
                            ? ((com.google.firebase.Timestamp) ts).toDate().getTime() : 0;
                    conv.sortTs = resolveSortTs(doc, conv.lastMessageTs);

                    Object unread = doc.get("unread_" + myUid);
                    conv.unreadCount = unread instanceof Long ? ((Long) unread).intValue() : 0;

                    Object typing = doc.get("typing_" + partnerUid);
                    conv.isTyping = Boolean.TRUE.equals(typing);

                    Object online = doc.get("online_" + partnerUid);
                    conv.isOnline = Boolean.TRUE.equals(online);

                    Object lastSeenObj = doc.get("lastSeen_" + partnerUid);
                    conv.lastSeen = com.duoshield.app.util.PresenceFormatter.timestampMillis(lastSeenObj);

                    Object muted = doc.get("muted_" + myUid);
                    conv.isMuted = Boolean.TRUE.equals(muted);

                    Object photoUrl = doc.get("partnerPhotoUrl_" + myUid);
                    conv.avatarUrl = photoUrl != null ? photoUrl.toString() : null;

                    // Handle change based on type (ADDED, MODIFIED, or REMOVED)
                    if (change.getType() == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                        directConversations.removeIf(c -> c.id.equals(chatId));
                        hasChanges = true;
                    } else {
                        // ADDED or MODIFIED — find existing or add new
                        boolean found = false;
                        for (int i = 0; i < directConversations.size(); i++) {
                            if (directConversations.get(i).id.equals(chatId)) {
                                directConversations.set(i, conv);
                                found = true;
                                break;
                            }
                        }
                        if (!found) directConversations.add(conv);
                        hasChanges = true;
                    }
                }

                // Count changed documents, not the full listener result, because snapshots may
                // contain the complete conversation window on every metadata update.
                int changedCount = snapshots.getDocumentChanges().size();
                if (changedCount > 0) {
                    FirebaseCostGuard.getInstance(ConversationListActivity.this)
                            .recordReads(changedCount);
                }
                if (hasChanges) mergeAndFilter();
            });
    }

    /**
     * Picks the timestamp a chat row should sort on, newest first.
     *
     * <p>{@code lastMessageTs} alone is not enough:
     * <ul>
     *   <li>A chat created by "New Chat" has no messages, so the field is absent → 0.</li>
     *   <li>{@code ConversationMetaUpdater} writes it with {@code serverTimestamp()}, which is
     *       {@code null} in the local (latency-compensated) snapshot until the server round-trip
     *       lands — so a chat the user just messaged momentarily reads 0 too.</li>
     * </ul>
     * Every such row collapsed to the same key, and {@link Collections#sort} is stable, so they
     * kept whatever order Firestore returned them in — which is why a brand-new conversation
     * appeared below an older one. Falling back to the pending server value and then to the
     * chat's own creation time gives each row a distinct, monotonic key.
     */
    private static long resolveSortTs(com.google.firebase.firestore.DocumentSnapshot doc,
                                      long lastMessageTs) {
        if (lastMessageTs > 0) return lastMessageTs;

        // An unresolved serverTimestamp() reads null locally; ESTIMATE fills it with the
        // device's best guess so the row sorts to the top immediately instead of falling to 0
        // and then jumping into place once the server confirms.
        try {
            Object est = doc.get("lastMessageTs",
                    com.google.firebase.firestore.DocumentSnapshot
                            .ServerTimestampBehavior.ESTIMATE);
            if (est instanceof com.google.firebase.Timestamp) {
                long ms = ((com.google.firebase.Timestamp) est).toDate().getTime();
                if (ms > 0) return ms;
            }
        } catch (RuntimeException ignored) {
            // Field missing or not a timestamp — fall through to createdAt.
        }

        Object created = doc.get("createdAt");
        if (created instanceof com.google.firebase.Timestamp) {
            return ((com.google.firebase.Timestamp) created).toDate().getTime();
        }
        if (created instanceof Long) return (Long) created;
        return 0L;
    }

    /** Loads groups from Room and merges them with the direct conversation list. */
    private void loadGroupsFromRoom() {
        executor.execute(() -> {
            List<Group> groups = localDb.groupDao().getAllGroups();
            List<Conversation> convGroups = new ArrayList<>();
            for (Group g : groups) convGroups.add(Conversation.fromGroup(g));
            runOnUiThread(() -> {
                groupConversations.clear();
                groupConversations.addAll(convGroups);
                mergeAndFilter();
            });
        });
    }

    /**
     * Combines direct + group conversations, newest first, and re-applies the active filter.
     *
     * <p>Sorts on {@code sortTs} rather than {@code lastMessageTs} so chats that have no message
     * yet (or a server timestamp still in flight) still order by recency instead of tying at 0.
     * Name is the tiebreaker so two rows that genuinely share a timestamp — e.g. groups restored
     * from a backup — keep a stable, predictable order across snapshots rather than shuffling.
     */
    private void mergeAndFilter() {
        allConversations.clear();
        allConversations.addAll(directConversations);
        allConversations.addAll(groupConversations);
        Collections.sort(allConversations, (a, b) -> {
            int byTime = Long.compare(b.sortTs, a.sortTs);
            if (byTime != 0) return byTime;
            String an = a.partnerName != null ? a.partnerName : "";
            String bn = b.partnerName != null ? b.partnerName : "";
            return an.compareToIgnoreCase(bn);
        });
        filterConversations(etSearch.getText().toString().trim());
    }

    private void filterConversations(String query) {
        // Increment the generation counter so any in-flight filter pass from a previous
        // mergeAndFilter() call is treated as stale and its adapter update is suppressed.
        // Without this guard, two rapid mergeAndFilter() calls (e.g. Firestore MODIFIED +
        // group Room reload arriving within milliseconds) can produce out-of-order adapter
        // updates — the stale one lands last and re-sorts the list incorrectly.
        final int mySeq = ++filterSeq;
        final List<Conversation> snapshot = new ArrayList<>(allConversations);
        final String lq = query.toLowerCase();
        final boolean incArchived = showArchived;
        executor.execute(() -> {
            List<Conversation> archiveFiltered = new ArrayList<>();
            for (Conversation c : snapshot) {
                boolean archived = ArchiveHelper.isArchived(
                        ConversationListActivity.this, c.id);
                if (archived == incArchived) archiveFiltered.add(c);
            }
            final List<Conversation> result;
            if (query.isEmpty()) {
                result = archiveFiltered;
            } else {
                List<Conversation> filtered = new ArrayList<>();
                for (Conversation c : archiveFiltered) {
                    String name = c.partnerName != null ? c.partnerName.toLowerCase() : "";
                    if (name.contains(lq)) filtered.add(c);
                }
                result = filtered;
            }
            runOnUiThread(() -> {
                // Discard this result if a newer filter pass has already started.
                if (mySeq != filterSeq) return;
                adapter.setConversations(result);
                showEmpty(adapter.getItemCount() == 0);
            });
        });
    }

    private void openChat(Conversation conv) {
        if (forwardMode) {
            if (conv.isGroup || conv.partnerUid == null || conv.partnerUid.isEmpty()) {
                Toast.makeText(this, "Choose a direct conversation", Toast.LENGTH_SHORT).show();
                return;
            }
            String id = getIntent().getStringExtra(EXTRA_FORWARD_MESSAGE_ID);
            String sourceConversation = getIntent().getStringExtra(EXTRA_FORWARD_SOURCE_CONVERSATION);
            String url = getIntent().getStringExtra(EXTRA_FORWARD_MEDIA_URL);
            String key = getIntent().getStringExtra(EXTRA_FORWARD_MEDIA_KEY);
            String type = getIntent().getStringExtra(EXTRA_FORWARD_MEDIA_TYPE);
            Message source = new Message(id != null ? id : java.util.UUID.randomUUID().toString(),
                    sourceConversation, getIntent().getStringExtra(EXTRA_FORWARD_SOURCE_SENDER),
                    getIntent().getStringExtra(EXTRA_FORWARD_TEXT),
                    getIntent().getLongExtra(EXTRA_FORWARD_TIMESTAMP, System.currentTimeMillis()),
                    true, url, type);
            source.setMediaKey(key);
            ForwardMessageHelper.forward(this, source, conv.id, myUid, conv.partnerUid);
            Toast.makeText(this, "Forwarding…", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (conv.isGroup) {
            Intent i = new Intent(this, GroupChatActivity.class);
            i.putExtra("group_id", conv.groupId);
            startActivity(i);
        } else {
            Intent i = new Intent(this, ChatMediaActivity.class);
            i.putExtra("conversation_id", conv.id);
            i.putExtra("partner_uid", conv.partnerUid);
            startActivity(i);
        }
    }

    private void openContactDetail(Conversation conv) {
        if (conv.isGroup) return;
        Intent i = new Intent(this, com.duoshield.app.ui.ContactDetailActivity.class);
        i.putExtra(com.duoshield.app.ui.ContactDetailActivity.EXTRA_PARTNER_UID,  conv.partnerUid);
        i.putExtra(com.duoshield.app.ui.ContactDetailActivity.EXTRA_PARTNER_NAME, conv.partnerName);
        i.putExtra(com.duoshield.app.ui.ContactDetailActivity.EXTRA_CONV_ID,      conv.id);
        startActivity(i);
    }

    private void updateArchivedBanner() {
        if (archivedBanner == null) return;
        int count = ArchiveHelper.getArchivedCount(this);
        if (count > 0) {
            archivedBanner.setVisibility(View.VISIBLE);
            android.widget.TextView tvCount = archivedBanner.findViewById(R.id.tv_archived_count);
            if (tvCount != null) tvCount.setText(String.valueOf(count));
        } else {
            archivedBanner.setVisibility(View.GONE);
            showArchived = false;
        }
    }

    private void setupSwipeActions() {
        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

            private final Paint bgPaint = new Paint();

            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                    @NonNull RecyclerView.ViewHolder a,
                    @NonNull RecyclerView.ViewHolder b) { return false; }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getAdapterPosition();
                if (pos < 0) return;
                Conversation conv = adapter.getItemAt(pos);
                if (conv == null) return;

                if (dir == ItemTouchHelper.LEFT) {
                    ArchiveHelper.archive(ConversationListActivity.this, conv.id);
                    mergeAndFilter();
                    updateArchivedBanner();
                    final String cid = conv.id;
                    Snackbar.make(recyclerView, "Conversation archived", Snackbar.LENGTH_LONG)
                            .setAction("Undo", v -> {
                                ArchiveHelper.unarchive(ConversationListActivity.this, cid);
                                mergeAndFilter();
                                updateArchivedBanner();
                            })
                            .setActionTextColor(0xFF00C9E0)
                            .setBackgroundTint(0xFF1E2535)
                            .setTextColor(0xFFFFFFFF)
                            .show();
                } else {
                    boolean nowMuted = !conv.isMuted;
                    for (Conversation c : directConversations) {
                        if (c.id != null && c.id.equals(conv.id)) { c.isMuted = nowMuted; break; }
                    }
                    mergeAndFilter();
                    db.collection("chats").document(conv.id)
                            .update("muted_" + myUid, nowMuted);
                    Snackbar.make(recyclerView,
                            nowMuted ? "Notifications muted" : "Notifications unmuted",
                            Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(0xFF1E2535)
                            .setTextColor(0xFFFFFFFF)
                            .show();
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
                    @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                    int actionState, boolean isCurrentlyActive) {
                if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
                    super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
                    return;
                }
                android.view.View item = vh.itemView;
                float top = item.getTop(), bottom = item.getBottom();
                float left = item.getLeft(), right = item.getRight();
                float density = getResources().getDisplayMetrics().density;
                int iconSz = (int)(24 * density);
                int margin = (int)(18 * density);
                int iconTop = (int)(top + (bottom - top - iconSz) / 2f);

                if (dX > 0) {
                    bgPaint.setColor(0xFFF59E0B);
                    c.drawRect(left, top, left + dX, bottom, bgPaint);
                    Drawable icon = ContextCompat.getDrawable(
                            ConversationListActivity.this, R.drawable.ic_mute);
                    if (icon != null) {
                        icon.setBounds((int)left + margin, iconTop,
                                (int)left + margin + iconSz, iconTop + iconSz);
                        icon.draw(c);
                    }
                } else if (dX < 0) {
                    bgPaint.setColor(0xFF6366F1);
                    c.drawRect(right + dX, top, right, bottom, bgPaint);
                    Drawable icon = ContextCompat.getDrawable(
                            ConversationListActivity.this, R.drawable.ic_archive);
                    if (icon != null) {
                        icon.setBounds((int)right - margin - iconSz, iconTop,
                                (int)right - margin, iconTop + iconSz);
                        icon.draw(c);
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
            }
        };
        new ItemTouchHelper(cb).attachToRecyclerView(recyclerView);
    }

    private void showEmpty(boolean empty) {
        if (!firstLoadDone) {
            firstLoadDone = true;
            if (shimmerContainer != null) shimmerContainer.setVisibility(View.GONE);
        }
        if (swipeRefresh != null && swipeRefresh.isRefreshing()) {
            swipeRefresh.setRefreshing(false);
        }
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ── Incoming call watcher (Firestore backup to FCM) ──────────────────────
    // Detects incoming calls via a real-time Firestore query instead of relying
    // solely on push notifications from the Render-hosted push server.  This
    // ensures calls ring even when Render is sleeping (free-tier cold start ≥30 s).
    private com.duoshield.app.call.IncomingCallWatcher incomingCallWatcher;

    // ── Lifecycle: attach listener on start, detach on stop ──────────────────

    @Override protected void onStart() {
        super.onStart();
        // Bug 10 fix: removed AppLockManager.onAppForegrounded() call from here.
        // BaseActivity.onStart() now resets the lock timer for ALL activities in the
        // else-branch of shouldLock(). Calling it here a second time was redundant and
        // made ConversationListActivity the only screen that reset the timer correctly —
        // so any other activity could still trigger the lock on return.

        // F-19 fix: super.onStart() may have launched LockScreenActivity but execution
        // continues in this method regardless. Guard the listener attachment so we do
        // NOT fetch and decrypt conversation data before the PIN gate fires. When the
        // user unlocks, onStart() runs again with shouldLock() == false and attaches normally.
        if (AppLockManager.shouldLock(this)) return;

        updateArchivedBanner();

        // Restore contacts that survived a voluntary Wipe & Exit (if any).
        // Runs on the background executor — Room must not be accessed on main thread.
        final String uid = myUid;
        executor.execute(() -> com.duoshield.app.util.ContactBackupHelper.restoreIfNeeded(this, uid));

        if (listener == null) listenForConversation();
        loadGroupsFromRoom();

        // Start the Firestore-based incoming call watcher — backup to FCM so calls
        // are never silently missed when the push server is sleeping.
        if (incomingCallWatcher == null && myUid != null) {
            incomingCallWatcher = new com.duoshield.app.call.IncomingCallWatcher(this, myUid);
        }
        if (incomingCallWatcher != null) incomingCallWatcher.start();
    }

    @Override protected void onStop() {
        super.onStop();
        // Deliberately keep the conversation listener alive. Reattaching it on every
        // background/foreground transition causes a billed initial read of the whole query.
        if (incomingCallWatcher != null) incomingCallWatcher.stop();
    }

    @Override protected void onDestroy() {
        if (shimmerAnimator != null) {
            shimmerAnimator.cancel();
            shimmerAnimator.removeAllListeners();
            shimmerAnimator = null;
        }
        if (listener != null) { listener.remove(); listener = null; }
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (AppLockManager.shouldLock(this)) return;
        // Re-guard: if the Firestore listener was detached for any reason (e.g. the system
        // called onStop without a matching onStart — a rare but real lifecycle edge-case on
        // some OEMs), re-attach it here so the list never stays permanently frozen.
        if (listener == null) listenForConversation();
        // Groups are stored in Room (no Firestore real-time listener for groups). Reload
        // them on every resume so that new group messages written to Room while this
        // activity was in the background are reflected immediately when the user returns.
        loadGroupsFromRoom();
    }
}
