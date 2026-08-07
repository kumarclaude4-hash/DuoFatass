package com.duoshield.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.duoshield.app.R;
import com.duoshield.app.models.Message;
import com.duoshield.app.util.DateHeaderHelper;
import com.duoshield.app.util.LinkPreviewFetcher;
import com.duoshield.app.util.LinkPreviewHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnVoicePlayListener {
        void onVoicePlay(Message m, ImageView playPauseBtn, WaveformView waveform, TextView durationView, View bubble);
        /** Current playback fraction (0–1) for this message id, or 0 if it isn't the playing note. */
        float getPlaybackProgress(String msgId);
        /** Current elapsed playback ms for this message id, or 0 if it isn't the playing note. */
        int getPlaybackElapsedMs(String msgId);
    }

    /** Snapshot of the views belonging to the CURRENTLY bound, on-screen row for a voice
     * message, fetched fresh from the RecyclerView rather than captured once at tap-time.
     * A row can be recycled to a different position while its note keeps playing (new
     * messages arriving, or the user scrolling); progress callbacks that kept writing to
     * the originally-captured View silently updated a View that was no longer on screen,
     * which is why playback used to show no moving indicator at all after any scroll/list
     * update. Looking the live holder up per-tick fixes that. */
    public static class LiveVoiceViews {
        public final WaveformView waveform;
        public final ImageView    playPauseBtn;
        public final TextView     durationView;
        public final View         bubble;
        LiveVoiceViews(WaveformView w, ImageView p, TextView d, View b) {
            waveform = w; playPauseBtn = p; durationView = d; bubble = b;
        }
    }

    /** Looks up the currently-bound ViewHolder for msgId, if its row is on screen right now. */
    public LiveVoiceViews getLiveVoiceViews(RecyclerView recyclerView, String msgId) {
        int pos = findPositionById(msgId);
        if (pos < 0 || recyclerView == null) return null;
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(pos);
        if (!(vh instanceof MsgViewHolder)) return null;
        MsgViewHolder h = (MsgViewHolder) vh;
        return new LiveVoiceViews(h.voiceWaveform, h.voicePlayPauseBtn, h.voiceDuration, h.bubble);
    }
    public interface OnVoiceSpeedToggleListener {
        /** Tapped the speed pill on a currently-playing voice note. */
        void onVoiceSpeedToggle(Message m, TextView pillView);
    }
    public interface OnMessageLongPressListener {
        void onLongPress(Message m, View anchor);
    }
    public interface OnRetryListener {
        void onRetry(Message m);
    }
    public interface OnReplyTapListener {
        /** Called when the user taps the reply-quote strip inside a bubble. */
        void onReplyTap(String originalMessageId);
    }

    private static final int TYPE_DATE = 0;
    private static final int TYPE_MSG  = 1;

    private List<Message>                    messages       = new ArrayList<>();
    private List<Object>                     displayItems   = new ArrayList<>(); // String | Message
    private final String                     myUid;
    private final OnVoicePlayListener        voiceListener;
    private final OnMessageLongPressListener longPressListener;
    private final OnRetryListener            retryListener;
    private final Set<String>                pinnedIds      = new HashSet<>();
    private String                           playingMsgId   = null;
    private String                           partnerName    = null;
    private String                           partnerAvatarUrl = null;
    private String                           partnerInitial   = "?";
    private String                           currentSpeedLabel = "1x";
    private String                           highlightedMsgId = null;
    private OnReplyTapListener               replyTapListener = null;
    private OnVoiceSpeedToggleListener       voiceSpeedListener = null;
    /** O(1) msgId → senderUid lookup built in rebuildDisplay(); eliminates O(n) scan in onBindViewHolder. */
    private final Map<String, String>        senderByMsgId  = new HashMap<>();
    /** O(1) msgId → adapter position lookup; built by rebuildDisplay() and kept in sync by appendMessage(). */
    private final Map<String, Integer>       positionById   = new HashMap<>();
    /** O(1) msgId → Message object lookup; eliminates the O(n) linear scan in updateMessage(). */
    private final Map<String, Message>       messagesById   = new HashMap<>();
    /** Timestamp of the last date-header boundary written; lets appendMessage() skip a full rebuildDisplay(). */
    private long                             lastHeaderTimestamp = -1;
    /** ID of the single outgoing message that should play bubble_fade_in on next bind. Cleared after one use. */
    private String                           pendingAnimMsgId = null;
    /** Lazily-loaded send animation — parsed once from XML and reused; never reloaded on scroll. */
    private Animation                        bubbleFadeInAnim = null;

    public MessageAdapter(List<Message> messages, String myUid,
                          OnVoicePlayListener vl, OnMessageLongPressListener ll,
                          OnRetryListener rl) {
        this.messages          = messages != null ? messages : new ArrayList<>();
        this.myUid             = myUid;
        this.voiceListener     = vl;
        this.longPressListener = ll;
        this.retryListener     = rl;
        setHasStableIds(true);
        rebuildDisplay();
    }

    /**
     * Call after the user changes any bubble style/colour or font-size preference so all
     * currently-visible rows are rebound with the new look.
     */
    public void notifyBubbleStyleChanged() {
        notifyDataSetChanged();
    }

    /** Called from ChatMediaActivity once the partner's display name is loaded from Firestore. */
    public void setPartnerName(String name) {
        this.partnerName = name;
    }

    /**
     * Called from ChatMediaActivity once the partner's avatar (or fallback initial) is
     * known, so the voice-note trailing slot can show it. Rebinds visible voice rows.
     */
    public void setPartnerAvatar(String photoUrl, String initial) {
        this.partnerAvatarUrl = (photoUrl != null && !photoUrl.isEmpty()) ? photoUrl : null;
        this.partnerInitial   = (initial != null && !initial.isEmpty()) ? initial : "?";
        notifyItemRangeChanged(0, displayItems.size());
    }

    public void setOnVoiceSpeedToggleListener(OnVoiceSpeedToggleListener l) {
        this.voiceSpeedListener = l;
    }

    /**
     * Called after the speed is cycled so future binds (e.g. re-entering the screen
     * or scrolling the currently-playing row off/on screen) show the right label.
     */
    public void setCurrentSpeedLabel(String label) {
        this.currentSpeedLabel = label;
    }

    public void setOnReplyTapListener(OnReplyTapListener l) {
        this.replyTapListener = l;
    }

    /**
     * Briefly flashes the item at the given message id — used when the user taps a reply quote
     * and the RecyclerView scrolls to the original message.
     */
    public void highlightMessage(String msgId) {
        String prev = highlightedMsgId;
        highlightedMsgId = msgId;
        if (prev != null) notifyMsgById(prev);
        notifyMsgById(msgId);
        // Auto-clear so the flash doesn't linger if the item is recycled later
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (msgId.equals(highlightedMsgId)) {
                highlightedMsgId = null;
                notifyMsgById(msgId);
            }
        }, 1300);
    }

    /** Returns the adapter position of a message by its id, or -1 if not in the display list. O(1). */
    public int findPositionById(String msgId) {
        if (msgId == null) return -1;
        Integer pos = positionById.get(msgId);
        return pos != null ? pos : -1;
    }

    /** Replace entire list — uses DiffUtil to animate changes. */
    public void setMessages(List<Message> newList) {
        if (newList == null) newList = new ArrayList<>();
        final List<Object> oldDisplay = new ArrayList<>(displayItems);
        messages = newList;
        rebuildDisplay();
        final List<Object> newDisplay = new ArrayList<>(displayItems);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldDisplay.size(); }
            @Override public int getNewListSize() { return newDisplay.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                Object o = oldDisplay.get(oldPos);
                Object n = newDisplay.get(newPos);
                if (o instanceof Message && n instanceof Message)
                    return java.util.Objects.equals(((Message) o).getId(), ((Message) n).getId());
                if (o instanceof String && n instanceof String)
                    return o.equals(n);
                return false;
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                // For date-separator strings, identity is content.
                // For messages, re-bind on every update (they mutate in place).
                Object o = oldDisplay.get(oldPos);
                Object n = newDisplay.get(newPos);
                if (o instanceof String && n instanceof String) return o.equals(n);
                return false;
            }
        });
        diff.dispatchUpdatesTo(this);
    }

    /** Append a single message — O(1) incremental update, no full rebuildDisplay(). */
    public void appendMessage(Message m) {
        messages.add(m);
        int insertStart = displayItems.size();
        // Only check if a date header is needed for this specific message,
        // rather than iterating the entire list via rebuildDisplay().
        if (DateHeaderHelper.needsHeader(lastHeaderTimestamp, m.getTimestamp())) {
            displayItems.add(DateHeaderHelper.getLabel(m.getTimestamp()));
            lastHeaderTimestamp = m.getTimestamp();
        }
        if (m.getId() != null) {
            positionById.put(m.getId(), displayItems.size()); // size() == next free index
            messagesById.put(m.getId(), m);
            if (m.getSender() != null) senderByMsgId.put(m.getId(), m.getSender());
        }
        displayItems.add(m);
        // Mark outgoing message for bubble_fade_in on its first bind only.
        if (m.getId() != null && myUid != null && myUid.equals(m.getSender())) {
            pendingAnimMsgId = m.getId();
        }
        // 1 item (message only) or 2 items (date header + message) may be inserted.
        notifyItemRangeInserted(insertStart, displayItems.size() - insertStart);
    }

    /**
     * Update a single message in-place (reaction, status, text, etc.).
     *
     * <p>displayItems holds references to the same Message objects as messages, so
     * the mutation is already visible in displayItems immediately. rebuildDisplay()
     * is intentionally NOT called — date separators do not change when a message
     * field changes. Only notifyItemChanged() for the exact position is needed.
     *
     * <p>O(1): uses the messagesById HashMap for direct lookup instead of a linear scan.
     */
    public void updateMessage(String msgId, java.util.function.Consumer<Message> mutator) {
        if (msgId == null || mutator == null) return;
        Message msg = messagesById.get(msgId);
        if (msg != null) {
            mutator.accept(msg);
            Integer pos = positionById.get(msgId);
            if (pos != null) {
                notifyItemChanged(pos);
            } else {
                // Safety fallback — shouldn't happen since positionById mirrors displayItems.
                notifyDataSetChanged();
            }
        }
    }

    /**
     * Batch-update the {@code status} field on multiple messages in a single pass.
     *
     * <p>Compared to calling {@link #updateMessage} N times, this fires exactly one
     * {@code notifyItemChanged()} per affected position — avoiding N separate RecyclerView
     * rebind dispatches when a burst of receipts arrives (e.g. "all sent → read" on chat open).
     *
     * @param ids    message IDs to update
     * @param status new status string (e.g. {@code "read"}, {@code "delivered"})
     */
    public void batchUpdateStatus(List<String> ids, String status) {
        if (ids == null || ids.isEmpty() || status == null) return;
        for (String msgId : ids) {
            Message msg = messagesById.get(msgId);
            if (msg == null) continue;
            msg.setStatus(status);
            Integer pos = positionById.get(msgId);
            if (pos != null) notifyItemChanged(pos);
        }
    }

    /** Remove a message by id — uses DiffUtil to animate the deletion. */
    public void removeMessage(String msgId) {
        if (msgId == null) return;
        final List<Object> oldDisplay = new ArrayList<>(displayItems);
        messages.removeIf(m -> msgId.equals(m.getId()));
        rebuildDisplay();
        final List<Object> newDisplay = new ArrayList<>(displayItems);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldDisplay.size(); }
            @Override public int getNewListSize() { return newDisplay.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                Object o = oldDisplay.get(oldPos);
                Object n = newDisplay.get(newPos);
                if (o instanceof Message && n instanceof Message)
                    return java.util.Objects.equals(((Message) o).getId(), ((Message) n).getId());
                if (o instanceof String && n instanceof String) return o.equals(n);
                return false;
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                Object o = oldDisplay.get(oldPos);
                Object n = newDisplay.get(newPos);
                if (o instanceof String && n instanceof String) return o.equals(n);
                return false;
            }
        });
        diff.dispatchUpdatesTo(this);
    }

    public void updatePinnedIds(Set<String> ids) {
        Set<String> oldIds = new HashSet<>(pinnedIds);
        pinnedIds.clear();
        if (ids != null) pinnedIds.addAll(ids);
        
        // PERF-OPT-04: Only notify items that changed pinned status (not the entire list).
        // This eliminates unnecessary rebinds and visual flickers.
        Set<String> changed = new HashSet<>();
        for (String id : oldIds) {
            if (!pinnedIds.contains(id)) changed.add(id);
        }
        for (String id : pinnedIds) {
            if (!oldIds.contains(id)) changed.add(id);
        }
        for (String msgId : changed) {
            notifyMsgById(msgId);
        }
    }

    /**
     * Mark a voice message as currently playing (or pass null to stop all).
     *
     * <p>Only two items need to be rebound: the previously-playing message
     * (pause icon → play icon) and the newly-playing message (play icon → pause
     * icon). Everything else in the list is unchanged.
     */
    public void setPlayingMessageId(String msgId) {
        String oldId = playingMsgId;
        playingMsgId = msgId;
        notifyMsgById(oldId);   // revert old playing item to play icon
        notifyMsgById(msgId);   // set new playing item to pause icon
    }

    /** Find a message by id and call notifyItemChanged for its position only. O(1). */
    private void notifyMsgById(String msgId) {
        if (msgId == null) return;
        Integer pos = positionById.get(msgId);
        if (pos != null) notifyItemChanged(pos);
    }

    public List<Message> getMessages() { return messages; }

    public Object getItemAt(int position) {
        if (position >= 0 && position < displayItems.size()) {
            return displayItems.get(position);
        }
        return null;
    }

    private void rebuildDisplay() {
        // §3.7 fix: the old approach called DateHeaderHelper.getLabel() (a "now"-relative
        // function) to both DECIDE whether to insert a header and to FORMAT its text.
        // Because getLabel() returns "Today"/"Yesterday" relative to the current clock,
        // any rebuildDisplay() call that crosses midnight recomputed every message's label
        // against a different "now", producing repeating "Today / Yesterday / Today / …"
        // headers whenever the adapter was refreshed after midnight.
        //
        // Fix: use DateHeaderHelper.needsHeader(prevTs, currentTs) — which compares two
        // absolute timestamps against each other (not against "now") — to DECIDE placement,
        // and use getLabel() only to FORMAT the text of a header that has already been placed.
        displayItems.clear();
        senderByMsgId.clear();
        positionById.clear();
        messagesById.clear();
        lastHeaderTimestamp = -1;
        long prevHeaderTs = -1;
        for (Message m : messages) {
            if (DateHeaderHelper.needsHeader(prevHeaderTs, m.getTimestamp())) {
                displayItems.add(DateHeaderHelper.getLabel(m.getTimestamp()));
                prevHeaderTs = m.getTimestamp();
                lastHeaderTimestamp = m.getTimestamp();
            }
            // Record the adapter position before adding the message to the list.
            if (m.getId() != null) {
                positionById.put(m.getId(), displayItems.size());
                messagesById.put(m.getId(), m);
                // Build O(1) sender lookup for reply-author resolution in onBindViewHolder.
                if (m.getSender() != null) senderByMsgId.put(m.getId(), m.getSender());
            }
            displayItems.add(m);
        }
    }

    // ── Item counts & types ──────────────────────────────────────────

    @Override public int getItemCount() { return displayItems.size(); }

    @Override public long getItemId(int position) {
        Object item = displayItems.get(position);
        if (item instanceof Message) return ((Message) item).getId().hashCode() & 0xFFFFFFFFL;
        return item.hashCode() & 0xFFFFFFFFL;
    }

    @Override public int getItemViewType(int position) {
        return (displayItems.get(position) instanceof String) ? TYPE_DATE : TYPE_MSG;
    }

    // ── ViewHolder creation ──────────────────────────────────────────

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DATE) {
            return new DateViewHolder(inf.inflate(R.layout.item_date_header, parent, false));
        }
        return new MsgViewHolder(inf.inflate(R.layout.item_message, parent, false));
    }

    // ── Binding ──────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_DATE) {
            ((DateViewHolder) holder).label.setText((String) displayItems.get(position));
            return;
        }
        Message msg = (Message) displayItems.get(position);
        bindMessage((MsgViewHolder) holder, msg);
        // Apply bubble_fade_in only once for the newly-inserted outgoing message.
        if (msg.getId() != null && msg.getId().equals(pendingAnimMsgId)) {
            pendingAnimMsgId = null;
            // Load the animation XML once, reuse forever — avoids ResourceManager
            // hit and Animator object allocation on every outgoing message send.
            if (bubbleFadeInAnim == null) {
                bubbleFadeInAnim = AnimationUtils.loadAnimation(
                        holder.itemView.getContext(), R.anim.bubble_fade_in);
            }
            bubbleFadeInAnim.reset();
            holder.itemView.startAnimation(bubbleFadeInAnim);
        }
    }

    private void bindMessage(MsgViewHolder h, Message msg) {
        boolean mine = myUid != null && myUid.equals(msg.getSender());
        String  type = msg.getMediaType();
        Context ctx  = h.itemView.getContext();

        // Reset all content views
        h.textView.setVisibility(View.GONE);
        h.imageView.setVisibility(View.GONE);
        h.videoContainer.setVisibility(View.GONE);
        h.contactCardContainer.setVisibility(View.GONE);
        h.voiceNoteContainer.setVisibility(View.GONE);
        h.replyPreviewContainer.setVisibility(View.GONE);
        h.reactionText.setVisibility(View.GONE);
        if (h.replyAuthorText   != null) h.replyAuthorText.setVisibility(View.GONE);
        if (h.mediaCaptionText  != null) h.mediaCaptionText.setVisibility(View.GONE);
        if (h.mediaGridContainer != null) h.mediaGridContainer.setVisibility(View.GONE);
        h.senderLabel.setVisibility(View.GONE);
        h.pinIndicatorRow.setVisibility(View.GONE);
        h.linkPreviewCard.setVisibility(View.GONE);
        h.starIcon.setVisibility(msg.starred ? View.VISIBLE : View.GONE);
        // Forwarded label — shown when message was forwarded from another chat
        if (h.forwardedLabel != null) {
            boolean isForwarded = msg.isForwarded()
                    || (msg.getText() != null && msg.getText().startsWith("[Forwarded] "));
            h.forwardedLabel.setVisibility(isForwarded ? View.VISIBLE : View.GONE);
        }

        // Restore default bubble padding (overridden to 0 for image/video below)
        int p13 = dp(ctx, 13); int p9 = dp(ctx, 9); int p7 = dp(ctx, 7);
        h.bubble.setPadding(p13, p9, p13, p7);

        // ── Bubble alignment ────────────────────────────────────────
        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams) h.bubble.getLayoutParams();
        lp.gravity = mine ? Gravity.END : Gravity.START;
        h.bubble.setLayoutParams(lp);

        // ── Bubble width cap (80 % of screen width) ───────────────────
        // LinearLayout ignores android:maxWidth; enforce it here so long
        // messages never stretch the bubble to the full screen width.
        h.bubble.setMaxWidth(
                (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.80f));

        // ── Bubble background (customisable style + colour) ──────────
        android.content.SharedPreferences bubblePrefs =
                ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        h.bubble.setBackground(
                com.duoshield.app.util.ChatCustomizationHelper.buildBubble(
                        mine, bubblePrefs,
                        ctx.getResources().getDisplayMetrics().density));

        // ── Partner sender label ────────────────────────────────────
        if (!mine) {
            h.senderLabel.setVisibility(View.VISIBLE);
            h.senderLabel.setText(partnerName != null && !partnerName.isEmpty()
                    ? partnerName : "");
        }

        // ── Pin indicator ───────────────────────────────────────────
        if (pinnedIds.contains(msg.getId())) {
            h.pinIndicatorRow.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams pinLp =
                (LinearLayout.LayoutParams) h.pinIndicatorRow.getLayoutParams();
            pinLp.gravity = mine ? Gravity.END : Gravity.START;
            h.pinIndicatorRow.setLayoutParams(pinLp);
        }

        // ── Reply preview (WhatsApp-style: sender name + preview text) ─
        String rp = msg.getReplyPreview();
        String replyId = msg.getReplyToId();
        if (rp != null && !rp.isEmpty()) {
            h.replyPreviewContainer.setVisibility(View.VISIBLE);
            h.replyPreviewText.setText(rp);

            // Tap reply strip → scroll to the original message
            final String replyIdFinal = replyId;
            h.replyPreviewContainer.setOnClickListener(v -> {
                if (replyTapListener != null && replyIdFinal != null) {
                    replyTapListener.onReplyTap(replyIdFinal);
                }
            });

            // Resolve sender name via O(1) map (pre-built in rebuildDisplay).
            if (h.replyAuthorText != null && replyId != null) {
                String originalSenderUid = senderByMsgId.get(replyId);
                if (originalSenderUid != null) {
                    String authorLabel = originalSenderUid.equals(myUid)
                            ? "You"
                            : (partnerName != null ? partnerName : "Partner");
                    h.replyAuthorText.setText(authorLabel);
                    // Tint: green for "You", accent for partner
                    h.replyAuthorText.setTextColor(originalSenderUid.equals(myUid)
                            ? 0xFF6BBF8A
                            : ContextCompat.getColor(ctx, R.color.ds_accent));
                    h.replyAuthorText.setVisibility(View.VISIBLE);
                }
            }
        }

        // ── Content ─────────────────────────────────────────────────
        if ("video".equals(type)) {
            // Media goes edge-to-edge on start/top like WhatsApp, but the meta row
            // (timestamp + ticks) lives inside this same padding box — a hard 0 end
            // padding left it glued to the bubble's rounded corner with no breathing
            // room at all, which read as "the video bubble has no padding". Keep a
            // small end/bottom margin so the media still hugs the bubble while the
            // timestamp/ticks get a bit of clearance.
            float density = ctx.getResources().getDisplayMetrics().density;
            h.bubble.setPadding(0, 0, (int)(6 * density), (int)(7 * density));
            h.videoContainer.setVisibility(View.VISIBLE);
            String vidRef = msg.getMediaUrl();
            String vidKey = msg.getMediaKey();
            if (com.duoshield.app.util.B2StorageHelper.isB2Path(vidRef)) {
                // B2 encrypted video — tap opens MediaViewerActivity (ExoPlayer).
                // Real thumbnails require downloading + decrypting the video, so show a
                // clean dark preview immediately and swap in the extracted frame once ready.
                h.videoThumbnail.setTag(vidRef);
                Glide.with(ctx).clear(h.videoThumbnail);
                h.videoThumbnail.setImageDrawable(null);
                h.videoThumbnail.setBackgroundColor(0xFF0D1825);
                byte[] cachedThumb = com.duoshield.app.util.B2StorageHelper.getCachedThumb(vidRef);
                if (cachedThumb != null) {
                    Glide.with(ctx).load(cachedThumb).centerCrop().into(h.videoThumbnail);
                } else {
                    com.duoshield.app.util.B2StorageHelper.loadVideoThumbnail(ctx, vidRef, vidKey,
                            new com.duoshield.app.util.B2StorageHelper.ThumbnailCallback() {
                        @Override public void onLoaded(byte[] jpegBytes) {
                            if (vidRef.equals(h.videoThumbnail.getTag())) {
                                Glide.with(ctx).load(jpegBytes).centerCrop().into(h.videoThumbnail);
                            }
                        }
                        @Override public void onError(Exception e) {
                            // Keep the static placeholder — non-fatal, video still plays fine.
                        }
                    });
                }
                h.videoContainer.setOnClickListener(v -> {
                    Intent i = new Intent(ctx, com.duoshield.app.MediaViewerActivity.class);
                    i.putExtra(com.duoshield.app.MediaViewerActivity.EXTRA_URL, vidRef);
                    i.putExtra(com.duoshield.app.MediaViewerActivity.EXTRA_MEDIA_KEY, vidKey);
                    ctx.startActivity(i);
                });

            } else {
                // Legacy Firebase Storage URL
                Glide.with(ctx).asBitmap().load(vidRef)
                     .placeholder(R.drawable.bg_media_rounded)
                     .error(R.drawable.bg_media_rounded).centerCrop().into(h.videoThumbnail);
                h.videoContainer.setOnClickListener(v -> {
                    Intent i = new Intent(ctx, com.duoshield.app.MediaViewerActivity.class);
                    i.putExtra(com.duoshield.app.MediaViewerActivity.EXTRA_URL, vidRef);
                    ctx.startActivity(i);
                });
            }

        } else if ("voice".equals(type)) {
            h.voiceNoteContainer.setVisibility(View.VISIBLE);
            boolean playing = msg.getId() != null && msg.getId().equals(playingMsgId);

            // Reset recycled state first so a previously playing row does not leak scale/progress.
            stopBreathingAnim(h.bubble);

            h.voicePlayPauseBtn.setImageResource(
                playing ? R.drawable.ic_pause_audio : R.drawable.ic_play_audio);
            // Tag views with message ID so async callbacks can detect stale ViewHolders
            h.voicePlayPauseBtn.setTag(msg.getId());
            h.voiceWaveform.setTag(msg.getId());
            h.voiceDuration.setTag(msg.getId());
            // At-rest label: the real recorded/received total length, so the user can see
            // how long a voice note is before playing it (previously stuck on a static
            // "0:00" placeholder — durationMs was never persisted or displayed here).
            // While actively playing, ChatMediaActivity's progress callback overwrites
            // this same TextView with the live elapsed position — unless this row was just
            // (re)bound mid-playback (e.g. scrolled back into view), in which case we seed
            // it from the listener below so it doesn't flash back to the static length.
            if (!playing) {
                h.voiceDuration.setText(msg.getDurationMs() > 0
                        ? formatDuration(msg.getDurationMs())
                        : "0:00");
            }
            h.voiceDuration.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    com.duoshield.app.util.ChatCustomizationHelper.getMetaFontSizeSp(bubblePrefs));
            // Load waveform bars for display (sender has them from recording; receiver from Firestore).
            // Messages loaded from Room cache before this fix (or truly legacy messages sent
            // before amplitudes were persisted) lack amplitudes — fall back to a deterministic
            // synthetic waveform from the message ID so the UI never shows a flat line.
            // NOTE: setAmplitudes() always resets its own internal progress to 0 — any
            // setProgress() call meant to seed a rebind mid-playback must come AFTER this.
            List<Integer> amps = msg.getWaveAmplitudes();
            if (amps != null && !amps.isEmpty()) {
                h.voiceWaveform.setAmplitudes(amps);
            } else {
                h.voiceWaveform.setAmplitudes(syntheticAmplitudes(msg.getId()));
            }

            // This row may be getting (re)bound while its note is mid-playback — e.g. it
            // scrolled off screen and back, or a new incoming message forced a rebind of
            // visible rows. Without this, the scrubber dot and elapsed time would silently
            // reset to 0 and never move again, because ChatMediaActivity's per-tick updates
            // target whatever View is CURRENTLY bound (see getLiveVoiceViews) — they don't
            // reach into the adapter to force a rebind. Seeding here keeps the row correct
            // the instant it (re)appears.
            if (playing && voiceListener != null) {
                float frac = voiceListener.getPlaybackProgress(msg.getId());
                int elapsedMs = voiceListener.getPlaybackElapsedMs(msg.getId());
                h.voiceWaveform.setProgress(frac);
                if (elapsedMs > 0) h.voiceDuration.setText(formatDuration(elapsedMs));
                applyBreathingAmplitude(h.bubble, h.voiceWaveform.getAmplitudeAt(frac));
            } else {
                h.voiceWaveform.setProgress(0f);
            }
            h.voicePlayPauseBtn.setOnClickListener(v -> {
                if (voiceListener != null)
                    voiceListener.onVoicePlay(msg, h.voicePlayPauseBtn,
                        h.voiceWaveform, h.voiceDuration, h.bubble);
            });
            // Bubble "breathes" (subtly scales) in sync with the live audio amplitude
            // while this note is playing — driven tick-by-tick from ChatMediaActivity's
            // playback progress callback via applyBreathingAmplitude(). Tag it so stale
            // async ticks from a recycled/switched row can detect the mismatch and no-op.
            h.bubble.setTag(msg.getId());

            // ── Trailing slot: partner avatar at rest, speed pill while playing ──
            // Own outgoing notes show nothing at rest (no point avatar-ing yourself)
            // but still surface the speed pill once playing, since it's a genuinely
            // useful control regardless of who sent the note.
            bindVoiceTrailingSlot(h, msg, ctx, mine, playing);

        } else if ("contact_card".equals(type)) {
            h.contactCardContainer.setVisibility(View.VISIBLE);
            String[] p = (msg.getText() != null ? msg.getText() : "").split("\\|", 2);
            h.cardName.setText(p.length > 0 ? p[0] : "DuoShield User");
            String uid = p.length > 1 ? p[1] : "";
            h.cardUid.setText(uid.isEmpty() ? "" : "ID: " + uid);
            h.cardCopyBtn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("uid", uid));
                    Toast.makeText(ctx, "UID copied", Toast.LENGTH_SHORT).show();
                }
            });

        } else if (msg.getMediaItems() != null && !msg.getMediaItems().isEmpty()) {
            // ── Multi-media album grid ────────────────────────────────
            float density = ctx.getResources().getDisplayMetrics().density;
            h.bubble.setPadding(0, 0, (int)(6 * density), (int)(7 * density));
            if (h.mediaGridContainer != null) {
                h.mediaGridContainer.setVisibility(View.VISIBLE);
                bindMediaGrid(h, msg, ctx);
            }

        } else if (msg.getMediaUrl() != null && !msg.getMediaUrl().isEmpty()) {
            // Media goes edge-to-edge — remove start/top/end padding, keep bottom for timestamp
            float density = ctx.getResources().getDisplayMetrics().density;
            h.bubble.setPadding(0, 0, (int)(6 * density), (int)(7 * density));
            h.imageView.setVisibility(View.VISIBLE);
            String imgRef = msg.getMediaUrl();
            String imgKey = msg.getMediaKey();

            // Tap → full-screen image viewer (PhotoView pinch-zoom)
            h.imageView.setOnClickListener(v -> {
                Intent i = new Intent(ctx, com.duoshield.app.FullScreenImageActivity.class);
                i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_URL, imgRef);
                i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_MEDIA_KEY, imgKey);
                ctx.startActivity(i);
            });

            if (com.duoshield.app.util.B2StorageHelper.isB2Path(imgRef)) {
                // B2 encrypted image — serve from cache instantly, or download+decrypt async
                h.imageView.setTag(imgRef);
                // Check the in-memory cache first — avoids showing a placeholder for already-loaded images
                byte[] cached = com.duoshield.app.util.B2StorageHelper.getCached(imgRef);
                if (cached != null) {
                    Glide.with(ctx).load(cached).centerCrop().into(h.imageView);
                } else {
                    Glide.with(ctx).load(R.drawable.ic_image).centerCrop().into(h.imageView);
                    com.duoshield.app.util.B2StorageHelper.loadMedia(ctx, imgRef, imgKey,
                            new com.duoshield.app.util.B2StorageHelper.MediaCallback() {
                        @Override public void onLoaded(byte[] plainBytes) {
                            if (imgRef.equals(h.imageView.getTag())) {
                                Glide.with(ctx).load(plainBytes).centerCrop().into(h.imageView);
                            }
                        }
                        @Override public void onError(Exception e) {
                            if (imgRef.equals(h.imageView.getTag())) {
                                Glide.with(ctx).load(android.R.drawable.ic_dialog_alert)
                                     .into(h.imageView);
                            }
                        }
                    });
                }
            } else {
                // Legacy Firebase Storage URL
                Glide.with(ctx).load(imgRef)
                     .placeholder(android.R.drawable.ic_menu_gallery).into(h.imageView);
            }

        } else {
            // Plain text — show text and check for link preview
            h.textView.setVisibility(View.VISIBLE);
            h.textView.setText(msg.getText());
            h.textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    com.duoshield.app.util.ChatCustomizationHelper.getMsgFontSizeSp(
                            ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE)));
            bindLinkPreview(h, msg, ctx);
        }

        // ── Media caption (photo / video / album) ───────────────────
        if (h.mediaCaptionText != null) {
            String cap = msg.getCaption();
            if (cap != null && !cap.isEmpty()) {
                h.mediaCaptionText.setText(cap);
                h.mediaCaptionText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                        com.duoshield.app.util.ChatCustomizationHelper.getCaptionFontSizeSp(bubblePrefs));
                h.mediaCaptionText.setVisibility(View.VISIBLE);
                // Restore horizontal padding so caption text has breathing room
                if (h.bubble.getPaddingStart() == 0) {
                    float d = h.itemView.getContext().getResources().getDisplayMetrics().density;
                    h.bubble.setPadding((int)(4 * d), 0, (int)(8 * d), (int)(7 * d));
                }
            } else {
                h.mediaCaptionText.setVisibility(View.GONE);
            }
        }

        // ── Edited label ────────────────────────────────────────────
        h.editedLabel.setVisibility(msg.isEdited() ? View.VISIBLE : View.GONE);

        // ── Timestamp ───────────────────────────────────────────────
        long ts = msg.getTimestamp();
        if (ts > 0) {
            h.timestampView.setText(new java.text.SimpleDateFormat("HH:mm",
                java.util.Locale.getDefault()).format(new java.util.Date(ts)));
        }
        h.timestampView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                com.duoshield.app.util.ChatCustomizationHelper.getMetaFontSizeSp(bubblePrefs));

        // ── Delivery ticks ──────────────────────────────────────────
        com.duoshield.app.util.MessageStatusHelper.bind(h.tickIcon, msg,
            myUid != null ? myUid : "");

        // ── Seen-at label ────────────────────────────────────────────
        // Show "Seen HH:mm" below the blue ticks for my outgoing messages only.
        if (h.tvSeenAt != null) {
            boolean isMine = myUid != null && myUid.equals(msg.getSender());
            boolean isRead = "read".equals(msg.getStatus());
            long readAt    = msg.getReadAt();
            if (isMine && isRead && readAt > 0) {
                String seenTime = new java.text.SimpleDateFormat("HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(readAt));
                h.tvSeenAt.setText("Seen " + seenTime);
                h.tvSeenAt.setVisibility(android.view.View.VISIBLE);
            } else {
                h.tvSeenAt.setVisibility(android.view.View.GONE);
            }
        }

        // ── Reply-quote highlight flash ─────────────────────────────
        if (msg.getId() != null && msg.getId().equals(highlightedMsgId)) {
            // Accent-tinted flash on the whole row; fades as highlightedMsgId clears
            h.itemView.setBackgroundColor(0x2200A884);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> h.itemView.setBackgroundColor(0x00000000), 1100);
        } else {
            h.itemView.setBackgroundColor(0x00000000);
        }

        // ── Reaction (WhatsApp style: floating emoji pill below bubble corner) ──
        String reaction = msg.getReaction();
        if (reaction != null && !reaction.isEmpty()) {
            h.reactionText.setVisibility(View.VISIBLE);
            h.reactionText.setText(reaction);
            h.reactionText.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_reaction_badge));
            LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) h.reactionText.getLayoutParams();
            float reactionDensity = ctx.getResources().getDisplayMetrics().density;
            int margin8 = (int) (8 * reactionDensity);
            // Align pill to same side as bubble; pull it up to float against bubble bottom
            rlp.gravity     = mine ? Gravity.END : Gravity.START;
            rlp.topMargin   = -(int)(20 * reactionDensity);
            rlp.leftMargin  = mine ? 0 : margin8;
            rlp.rightMargin = mine ? margin8 : 0;
            h.reactionText.setLayoutParams(rlp);
        }

        h.bubble.setOnClickListener(v -> {
            if ("failed".equals(msg.getStatus()) && retryListener != null) {
                retryListener.onRetry(msg);
            }
        });

        h.bubble.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(msg, v);
            return true;
        });
    }

    // ── Link preview ──────────────────────────────────────────────────

    private void bindLinkPreview(MsgViewHolder h, Message msg, Context ctx) {
        String text = msg.getText();
        if (text == null || text.isEmpty()) return;

        String url = LinkPreviewHelper.extractFirstUrl(text);
        if (url == null) return;

        // Tag the card with the message id so stale async callbacks don't corrupt recycled views
        h.linkPreviewCard.setTag(msg.getId());

        // PERF-OPT-05: Fetch link previews asynchronously without blocking the bind thread.
        // If the preview is already cached, it will be returned immediately from the cache
        // (which is the common case for repeated URLs). Only uncached URLs trigger network I/O.
        LinkPreviewFetcher.fetch(url, preview -> {
            // Verify this view holder still belongs to the same message
            if (!msg.getId().equals(h.linkPreviewCard.getTag())) return;
            if (preview == null) return;

            h.linkPreviewCard.setVisibility(View.VISIBLE);

            // Domain
            h.linkPreviewDomain.setText(preview.domain != null ? preview.domain : "");

            // Title (hide row if empty)
            if (preview.title != null && !preview.title.isEmpty()) {
                h.linkPreviewTitle.setVisibility(View.VISIBLE);
                h.linkPreviewTitle.setText(preview.title);
            } else {
                h.linkPreviewTitle.setVisibility(View.GONE);
            }

            // OG image — S04-H3 / S08-H4: never hand the raw og:image URL directly to
            // Glide. Route it through the server-side /imageProxy endpoint instead so
            // (a) the device IP is never disclosed to the third-party image host and
            // (b) the server can enforce its own SSRF / content-type allow-list before
            // the bytes ever reach the client.
            if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
                h.linkPreviewImage.setVisibility(View.VISIBLE);
                String proxyUrl = preview.imageUrl.startsWith("/imageProxy")
                        ? com.duoshield.app.BuildConfig.PUSH_SERVER_URL + preview.imageUrl
                        : com.duoshield.app.BuildConfig.PUSH_SERVER_URL
                            + "/imageProxy?url="
                            + android.net.Uri.encode(preview.imageUrl);
                Glide.with(ctx).load(proxyUrl)
                     .centerCrop()
                     .placeholder(android.R.drawable.ic_menu_gallery)
                     .into(h.linkPreviewImage);
            } else {
                h.linkPreviewImage.setVisibility(View.GONE);
            }

            // Tap to open in browser
            h.linkPreviewCard.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(preview.url));
                ctx.startActivity(i);
            });
        });
    }

    // ── Voice message "breathing" bubble ────────────────────────────────────────
    // While a voice note plays, its bubble gently expands/contracts in sync with
    // the actual audio amplitude at the current playback position. Driven by
    // ChatMediaActivity's playback progress ticks via applyBreathingAmplitude();
    // stopBreathingAnim() resets a bubble back to rest (paused/stopped/recycled).

    /** Tag key used to store the running ViewPropertyAnimator state on the bubble view. */
    private static final int TAG_BREATHING_ANIM = R.id.voicePlayPauseBtn;

    /** Max extra scale at full amplitude — keeps the effect subtle, not jumpy. */
    private static final float BREATH_MAX_SCALE_DELTA = 0.06f;

    /**
     * Scales the bubble toward a target derived from the current audio amplitude
     * (0f–1f). Called on every playback progress tick (~200ms) for a smooth,
     * "breathing" expand/contract rather than a discrete jump.
     */
    // ── Multi-media album grid binding ──────────────────────────────────────
    private void bindMediaGrid(MsgViewHolder h, Message msg, Context ctx) {
        if (h.gridImg1 == null) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(msg.getMediaItems());
            int count = arr.length();

            ImageView[] slots  = { h.gridImg1, h.gridImg2, h.gridImg3, h.gridImg4 };
            // Show only as many rows as needed
            android.view.ViewGroup row2 = (android.view.ViewGroup)
                    h.mediaGridContainer.getChildAt(2); // second LinearLayout row

            boolean showRow2 = count > 2;
            if (row2 != null) row2.setVisibility(showRow2 ? View.VISIBLE : View.GONE);
            // The gap View between rows is at index 1
            h.mediaGridContainer.getChildAt(1).setVisibility(showRow2 ? View.VISIBLE : View.GONE);

            for (int i = 0; i < 4; i++) {
                if (i >= count) {
                    slots[i].setVisibility(View.GONE);
                    if (i == 3 && h.tvGridMore != null) h.tvGridMore.setVisibility(View.GONE);
                    continue;
                }
                slots[i].setVisibility(View.VISIBLE);
                org.json.JSONObject item = arr.getJSONObject(i);
                String url  = item.optString("url", null);
                // Album senders store private B2 paths under "path"; older
                // clients used "url". Accept both so albums render after upload
                // and after a Room restore.
                if (url == null || url.isEmpty()) url = item.optString("path", null);
                String key  = item.optString("key", null);
                String itemType = item.optString("type", "image");
                final ImageView slot = slots[i];
                slot.setTag(url);

                if (i == 3 && count > 4 && h.tvGridMore != null) {
                    h.tvGridMore.setText("+" + (count - 4));
                    h.tvGridMore.setVisibility(View.VISIBLE);
                }

                if (url == null) continue;
                if (com.duoshield.app.util.B2StorageHelper.isB2Path(url)) {
                    byte[] cached = com.duoshield.app.util.B2StorageHelper.getCached(url);
                    if (cached != null) {
                        Glide.with(ctx).load(cached).centerCrop().into(slot);
                    } else {
                        Glide.with(ctx).load(R.drawable.ic_image).centerCrop().into(slot);
                        final String finalUrl = url;
                        final String finalKey = key;
                        if ("video".equals(itemType)) {
                            byte[] cachedThumb =
                                    com.duoshield.app.util.B2StorageHelper.getCachedThumb(url);
                            if (cachedThumb != null) {
                                Glide.with(ctx).load(cachedThumb).centerCrop().into(slot);
                            } else {
                                com.duoshield.app.util.B2StorageHelper.loadVideoThumbnail(
                                        ctx, url, key,
                                        new com.duoshield.app.util.B2StorageHelper.ThumbnailCallback() {
                                    @Override public void onLoaded(byte[] jpegBytes) {
                                        if (finalUrl.equals(slot.getTag())) {
                                            Glide.with(ctx).load(jpegBytes).centerCrop().into(slot);
                                        }
                                    }
                                    @Override public void onError(Exception e) {}
                                });
                            }
                        } else {
                            com.duoshield.app.util.B2StorageHelper.loadMedia(ctx, url, key,
                                new com.duoshield.app.util.B2StorageHelper.MediaCallback() {
                                    @Override public void onLoaded(byte[] plainBytes) {
                                        if (finalUrl.equals(slot.getTag())) {
                                            Glide.with(ctx).load(plainBytes).centerCrop().into(slot);
                                        }
                                    }
                                    @Override public void onError(Exception e) {}
                                });
                        }
                    }
                } else {
                    if ("video".equals(itemType)) {
                        Glide.with(ctx).asBitmap().load(Uri.parse(url))
                                .placeholder(R.drawable.ic_image).centerCrop().into(slot);
                    } else {
                        Glide.with(ctx).load(url).placeholder(R.drawable.ic_image)
                                .centerCrop().into(slot);
                    }
                }
            }

            // Tap any slot → open gallery (first image for simplicity)
            for (int i = 0; i < Math.min(count, 4); i++) {
                int fi = i;
                org.json.JSONObject item = arr.getJSONObject(fi);
                String iUrl = item.optString("url", null);
                if (iUrl == null || iUrl.isEmpty()) iUrl = item.optString("path", null);
                String iKey = arr.getJSONObject(fi).optString("key", null);
                String iType = item.optString("type", "image");
                final String finalUrl = iUrl;
                final String finalKey = iKey;
                final String finalType = iType;
                slots[i].setOnClickListener(v -> {
                    Intent intent;
                    if ("video".equals(finalType)) {
                        intent = new Intent(ctx, com.duoshield.app.MediaViewerActivity.class);
                        intent.putExtra(com.duoshield.app.MediaViewerActivity.EXTRA_URL, finalUrl);
                        intent.putExtra(com.duoshield.app.MediaViewerActivity.EXTRA_MEDIA_KEY, finalKey);
                    } else {
                        intent = new Intent(ctx, com.duoshield.app.FullScreenImageActivity.class);
                        intent.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_URL, finalUrl);
                        intent.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_MEDIA_KEY, finalKey);
                    }
                    ctx.startActivity(intent);
                });
            }
        } catch (org.json.JSONException e) {
            android.util.Log.w("MessageAdapter", "bindMediaGrid: bad JSON — " + e.getMessage());
        }
    }

    public static void applyBreathingAmplitude(View bubbleView, float amplitude) {
        if (bubbleView == null) return;
        float clamped = Math.max(0f, Math.min(1f, amplitude));
        float target = 1f + clamped * BREATH_MAX_SCALE_DELTA;
        bubbleView.setTag(TAG_BREATHING_ANIM, Boolean.TRUE);
        bubbleView.animate().cancel();
        bubbleView.animate()
            .scaleX(target).scaleY(target)
            .setDuration(180)
            .start();
    }

    /** Stops the breathing animation and resets scale to 1.0. */
    public static void stopBreathingAnim(View bubbleView) {
        if (bubbleView == null) return;
        bubbleView.animate().cancel();
        bubbleView.setScaleX(1f);
        bubbleView.setScaleY(1f);
        bubbleView.setTag(TAG_BREATHING_ANIM, null);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof MsgViewHolder) {
            stopBreathingAnim(((MsgViewHolder) holder).bubble);
        }
    }

    // ── ViewHolders ──────────────────────────────────────────────────

    static class DateViewHolder extends RecyclerView.ViewHolder {
        TextView label;
        DateViewHolder(View v) {
            super(v);
            label = v.findViewById(R.id.tvDateLabel);
        }
    }

    static class MsgViewHolder extends RecyclerView.ViewHolder {
        TextView     senderLabel, textView, cardName, cardUid,
                     replyAuthorText, replyPreviewText, reactionText, editedLabel,
                     timestampView, voiceDuration,
                     linkPreviewDomain, linkPreviewTitle,
                     tvSeenAt;
        TextView     voiceAvatarInitial, voiceSpeedPill;
        /** Caption text shown beneath a photo, video, or album bubble. */
        TextView     mediaCaptionText;
        /** "+N more" overlay on the 4th cell of a media grid. */
        TextView     tvGridMore;
        ImageView    imageView, videoThumbnail, videoPlayBtn,
                     tickIcon, starIcon, voicePlayPauseBtn, linkPreviewImage, voiceAvatarImg;
        /** Four slots for multi-media album grid (2×2). */
        ImageView    gridImg1, gridImg2, gridImg3, gridImg4;
        MaxWidthLinearLayout bubble;
        LinearLayout voiceNoteContainer, pinIndicatorRow, linkPreviewCard;
        /** Container for the 2×2 album grid. */
        LinearLayout mediaGridContainer;
        FrameLayout  bubbleWrapper, voiceTrailingSlot;
        View         videoContainer, contactCardContainer, replyPreviewContainer, forwardedLabel;
        WaveformView voiceWaveform;
        Button       cardCopyBtn;

        MsgViewHolder(View v) {
            super(v);
            senderLabel           = v.findViewById(R.id.senderLabel);
            pinIndicatorRow       = v.findViewById(R.id.pinIndicatorRow);
            forwardedLabel        = v.findViewById(R.id.forwardedLabel);
            bubbleWrapper         = v.findViewById(R.id.bubbleWrapper);
            bubble                = v.findViewById(R.id.messageBubble);
            textView              = v.findViewById(R.id.messageText);
            imageView             = v.findViewById(R.id.messageImage);
            videoContainer        = v.findViewById(R.id.videoContainer);
            videoThumbnail        = v.findViewById(R.id.videoThumbnail);
            videoPlayBtn          = v.findViewById(R.id.videoPlayBtn);
            contactCardContainer  = v.findViewById(R.id.contactCardContainer);
            cardName              = v.findViewById(R.id.cardName);
            cardUid               = v.findViewById(R.id.cardUid);
            cardCopyBtn           = v.findViewById(R.id.cardCopyBtn);
            tickIcon              = v.findViewById(R.id.tickIcon);
            starIcon              = v.findViewById(R.id.starIcon);
            replyPreviewContainer = v.findViewById(R.id.replyPreviewContainer);
            replyAuthorText       = v.findViewById(R.id.replyAuthorText);
            replyPreviewText      = v.findViewById(R.id.replyPreviewText);
            reactionText          = v.findViewById(R.id.reactionText);
            editedLabel           = v.findViewById(R.id.editedLabel);
            timestampView         = v.findViewById(R.id.messageTimestamp);
            voiceNoteContainer    = v.findViewById(R.id.voiceNoteContainer);
            voicePlayPauseBtn     = v.findViewById(R.id.voicePlayPauseBtn);
            voiceWaveform         = v.findViewById(R.id.voiceWaveform);
            voiceDuration         = v.findViewById(R.id.voiceDuration);
            voiceTrailingSlot     = v.findViewById(R.id.voiceTrailingSlot);
            voiceAvatarImg        = v.findViewById(R.id.voiceAvatarImg);
            voiceAvatarInitial    = v.findViewById(R.id.voiceAvatarInitial);
            voiceSpeedPill        = v.findViewById(R.id.voiceSpeedPill);
            // Link preview
            linkPreviewCard       = v.findViewById(R.id.linkPreviewCard);
            linkPreviewImage      = v.findViewById(R.id.linkPreviewImage);
            linkPreviewDomain     = v.findViewById(R.id.linkPreviewDomain);
            linkPreviewTitle      = v.findViewById(R.id.linkPreviewTitle);
            tvSeenAt              = v.findViewById(R.id.tvSeenAt);
            // Media caption + grid
            mediaCaptionText      = v.findViewById(R.id.mediaCaptionText);
            mediaGridContainer    = v.findViewById(R.id.mediaGridContainer);
            gridImg1              = v.findViewById(R.id.gridImg1);
            gridImg2              = v.findViewById(R.id.gridImg2);
            gridImg3              = v.findViewById(R.id.gridImg3);
            gridImg4              = v.findViewById(R.id.gridImg4);
            tvGridMore            = v.findViewById(R.id.tvGridMore);
        }
    }

    /**
     * Binds the voice-note trailing slot (avatar / initial / speed pill).
     *
     * <p>Recycling correctness: every branch below either issues a fresh Glide
     * {@code load().into(voiceAvatarImg)} call or explicitly {@code clear()}s the
     * target. Glide cancels any prior request already attached to a given target
     * view as soon as a new request is issued against it (or cleared), so a stale
     * async load from whatever message previously occupied this recycled row can
     * never land on the ImageView after it's been reused — as long as we never skip
     * touching {@code voiceAvatarImg} for a bind. We deliberately never skip it.
     */
    private void bindVoiceTrailingSlot(MsgViewHolder h, Message msg, Context ctx,
                                        boolean mine, boolean playing) {
        h.voiceSpeedPill.setTag(msg.getId());

        if (playing) {
            h.voiceTrailingSlot.setVisibility(View.VISIBLE);
            h.voiceAvatarImg.setVisibility(View.GONE);
            h.voiceAvatarInitial.setVisibility(View.GONE);
            h.voiceSpeedPill.setVisibility(View.VISIBLE);
            h.voiceSpeedPill.setText(currentSpeedLabel);
            Glide.with(ctx).clear(h.voiceAvatarImg);
            h.voiceSpeedPill.setOnClickListener(v -> {
                if (voiceSpeedListener != null) voiceSpeedListener.onVoiceSpeedToggle(msg, h.voiceSpeedPill);
            });
            return;
        }

        h.voiceSpeedPill.setOnClickListener(null);

        if (mine) {
            // Own idle note — nothing to show in the trailing slot.
            h.voiceTrailingSlot.setVisibility(View.GONE);
            h.voiceSpeedPill.setVisibility(View.GONE);
            h.voiceAvatarImg.setVisibility(View.GONE);
            h.voiceAvatarInitial.setVisibility(View.GONE);
            Glide.with(ctx).clear(h.voiceAvatarImg);
            return;
        }

        // Partner's idle note — show their avatar, falling back to their initial.
        h.voiceTrailingSlot.setVisibility(View.VISIBLE);
        h.voiceSpeedPill.setVisibility(View.GONE);
        if (partnerAvatarUrl != null) {
            h.voiceAvatarInitial.setVisibility(View.GONE);
            h.voiceAvatarImg.setVisibility(View.VISIBLE);
            com.duoshield.app.util.GlideHelper.loadAvatar(ctx, partnerAvatarUrl, h.voiceAvatarImg);
            // Tap avatar → full-screen photo viewer
            final String avatarSnapshot = partnerAvatarUrl;
            h.voiceAvatarImg.setOnClickListener(v -> {
                android.content.Intent i = new android.content.Intent(
                        ctx, com.duoshield.app.FullScreenImageActivity.class);
                i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_URL, avatarSnapshot);
                ctx.startActivity(i);
            });
        } else {
            h.voiceAvatarImg.setVisibility(View.GONE);
            h.voiceAvatarImg.setOnClickListener(null);
            Glide.with(ctx).clear(h.voiceAvatarImg);
            h.voiceAvatarInitial.setVisibility(View.VISIBLE);
            h.voiceAvatarInitial.setText(partnerInitial);
        }
    }

    /**
     * Generates a deterministic pseudo-random amplitude list for voice notes that were
     * loaded from Room cache (where the @Ignore {@code waveAmplitudes} field is null).
     * Uses the message ID as a seed so the waveform is stable across re-binds.
     */
    private static List<Integer> syntheticAmplitudes(String msgId) {
        long seed = msgId != null ? msgId.hashCode() : 0L;
        java.util.Random rng = new java.util.Random(seed);
        List<Integer> amps = new ArrayList<>(40);
        // Generate a speech-like envelope: rises, peaks, then tapers
        for (int i = 0; i < 40; i++) {
            float envelope = (float) Math.sin(Math.PI * i / 39.0);
            int amp = (int)(200 + envelope * 2800 * (0.4f + 0.6f * rng.nextFloat()));
            amps.add(Math.max(100, amp));
        }
        return amps;
    }

    /** Convert dp to pixels. */
    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    /** Format milliseconds → "m:ss" */
    public static String formatDuration(int ms) {
        int secs = ms / 1000;
        return String.format(Locale.US, "%d:%02d", secs / 60, secs % 60);
    }
}

