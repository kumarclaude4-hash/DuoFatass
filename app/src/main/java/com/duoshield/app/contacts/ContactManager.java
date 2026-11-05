package com.duoshield.app.contacts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.duoshield.app.BuildConfig;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Contact;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import com.duoshield.app.util.SharedExecutors;
import java.util.regex.Pattern;

/**
 * Replaces PairingManager with a multi-contact flow.
 *
 * Add-contact flow:
 *  1. Resolve partner User ID (e.g. XXXXX-XXXXX-XXX) → Firebase UID via identities/{userId}.uid
 *  2. Upload own displayName to users/{myUid}
 *  3. Fetch partner displayName from users/{partnerUid} (8 retries / 2-second backoff)
 *  4. chatId = SHA-256(smaller_uid + "/" + larger_uid) — deterministic, identical on both devices
 *  5. Write chats/{chatId} with participants array and partnerName_<uid> fields
 *  6. Insert Contact row in Room
 *  7. Callback returns chatId + partnerUid so the caller can open the chat via Intent extras
 *
 * FIRESTORE RULE NOTE:
 *   The query whereArrayContains("participants", myUid) in ConversationListActivity requires
 *   that Firestore security rules allow:
 *     request.auth.uid in resource.data.participants
 *   Ensure this rule exists on the chats collection.
 *
 * No SharedPrefs keys (is_paired, conversation_id, partner_uid) are written here —
 * that is the old single-partner model.
 */
public class ContactManager {

    private static final String TAG                  = "ContactManager";
    private static final int    FETCH_RETRIES        = 8;
    private static final long   FETCH_DELAY_MS       = 2_000L;
    /** Retries for the identities lookup — handles propagation delay for brand-new accounts. */
    private static final int    IDENTITY_RETRIES     = 5;
    private static final long   IDENTITY_DELAY_MS    = 1_500L;

    /**
     * Canonical DuoShield Account ID format: three groups of unambiguous base32
     * characters (digits 2-9, letters A-Z excluding I/L/O), e.g. K3MNP-Q8RXA-7BC.
     * This is the single source of truth for what a valid Account ID looks like —
     * every entry point (manual entry, clipboard auto-paste, deep link, QR scan)
     * MUST canonicalize and validate against this pattern before the ID is used to
     * look up an {@code identities/{userId}} document (S08-L1). Firestore document
     * IDs otherwise accept almost any non-empty string up to 1500 bytes (no "/",
     * not "." or ".."), so without this gate a malicious deep link
     * (duoshield://add/<anything>) could feed arbitrary attacker-controlled input
     * straight into a live Firestore lookup and then into the visible "Account ID"
     * field and error/toast strings.
     *
     * <p>The pattern itself lives in {@link AccountIdValidator} — the single,
     * Android-independent source of truth shared with {@code AddContactActivity}'s
     * deep-link and clipboard entry points. This constant is kept, delegating to
     * that class, only so any external caller of the previous public field does
     * not silently regress to an unvalidated string.
     */
    public static final Pattern ACCOUNT_ID_PATTERN = AccountIdValidator.pattern();

    /**
     * Canonicalizes a raw, user- or link-supplied Account ID string (trims
     * whitespace, uppercases) and returns it only if it matches the canonical
     * Account ID shape; otherwise returns {@code null}. Delegates to
     * {@link AccountIdValidator#canonicalizeOrNull(String)} so every entry point
     * (manual entry, clipboard, deep link, QR scan) enforces the exact same rule.
     */
    public static String canonicalizeAccountId(String raw) {
        return AccountIdValidator.canonicalizeOrNull(raw);
    }

    private final Context           context;
    private final FirebaseFirestore db;

    public interface ContactCallback {
        void onAdded(String chatId, String partnerUid, String partnerDisplayName);
        void onError(String message);
    }

    public ContactManager(Context context) {
        this.context = context.getApplicationContext();
        this.db      = FirebaseFirestore.getInstance();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Add a contact by their DuoShield User ID (e.g. K3MNP-Q8RXA-7BC).
     * Safe to call from the UI thread — all Firestore work is async.
     */
    public void addContact(String partnerId, ContactCallback callback) {
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) {
            callback.onError("Not signed in. Please reopen the app.");
            return;
        }
        if (partnerId == null || partnerId.trim().isEmpty()) {
            callback.onError("Please enter your contact's User ID.");
            return;
        }
        // S08-L1: canonicalize + validate before this ID is used as a Firestore
        // document ID. This is the authoritative gate — it applies no matter which
        // entry point (manual entry, deep link, QR scan) supplied the raw string,
        // so a malformed/attacker-controlled value fails closed here even if an
        // upstream UI screen forgot to pre-validate.
        String id = canonicalizeAccountId(partnerId);
        if (id == null) {
            callback.onError("That doesn't look like a valid Account ID. It should look like K3MNP-Q8RXA-7BC.");
            return;
        }

        // Step 1 — resolve userId → Firebase UID (with retry for brand-new accounts
        // whose identities doc may not have propagated to Firestore yet)
        resolveIdentityWithRetry(myUid, id, callback, IDENTITY_RETRIES);
    }

    // ── Step 1b — resolve identities/{userId} with retry ─────────────────────

    /**
     * Retries the identities lookup up to {@code retriesLeft} times with a
     * {@link #IDENTITY_DELAY_MS} delay between attempts.  Needed because
     * SeedPhraseDisplayActivity writes the identities doc in an async Firestore
     * call; a fast partner who scans the QR immediately after account creation
     * may hit a momentary "doc not found" before the write propagates.
     */
    private void resolveIdentityWithRetry(String myUid, String userId,
                                          ContactCallback callback, int retriesLeft) {
        db.collection("identities").document(userId).get()
            .addOnSuccessListener(snap -> {
                String partnerUid = snap.exists() ? snap.getString("uid") : null;
                if (partnerUid != null && !partnerUid.isEmpty()) {
                    if (partnerUid.equals(myUid)) {
                        callback.onError("You cannot add yourself as a contact.");
                        return;
                    }
                    uploadMyNameThenFetch(myUid, partnerUid, callback);
                } else if (retriesLeft > 0) {
                    Log.d(TAG, "resolveIdentityWithRetry: not found, retrying (" + retriesLeft + " left)");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> resolveIdentityWithRetry(myUid, userId, callback, retriesLeft - 1),
                            IDENTITY_DELAY_MS);
                } else {
                    callback.onError("User ID not found. Double-check the ID and try again.");
                }
            })
            .addOnFailureListener(e -> {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (retriesLeft > 0 && !msg.contains("permission") && !msg.contains("offline")) {
                    Log.w(TAG, "resolveIdentityWithRetry: Firestore error, retrying ("
                            + e.getClass().getSimpleName() + ")");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> resolveIdentityWithRetry(myUid, userId, callback, retriesLeft - 1),
                            IDENTITY_DELAY_MS);
                } else if (msg.contains("offline") || msg.contains("unavailable")) {
                    callback.onError("No connection. Check your internet and try again.");
                } else {
                    callback.onError("Failed to reach server. Please try again.");
                }
            });
    }

    // ── Step 2 — upload own display name, then fetch partner name ─────────────

    private void uploadMyNameThenFetch(String myUid, String partnerUid, ContactCallback callback) {
        String displayName = getMyDisplayName();
        Map<String, Object> myProfile = new HashMap<>();
        myProfile.put("displayName", displayName);

        db.collection("users").document(myUid)
            .set(myProfile, SetOptions.merge())
            .addOnSuccessListener(v ->
                fetchPartnerNameWithRetry(myUid, partnerUid, callback, FETCH_RETRIES))
            .addOnFailureListener(e ->
                fetchPartnerNameWithRetry(myUid, partnerUid, callback, FETCH_RETRIES));
    }

    // ── Step 3 — fetch partner display name (retry on null) ───────────────────

    private void fetchPartnerNameWithRetry(String myUid, String partnerUid,
                                            ContactCallback callback, int retriesLeft) {
        db.collection("users").document(partnerUid).get()
            .addOnSuccessListener(doc -> {
                String partnerDisplayName = doc.getString("displayName");
                if (partnerDisplayName != null) {
                    finalizeContact(myUid, partnerUid, partnerDisplayName, callback);
                } else if (retriesLeft > 0) {
                    Log.d(TAG, "fetchPartnerNameWithRetry: name null, retrying (" + retriesLeft + " left)");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> fetchPartnerNameWithRetry(myUid, partnerUid, callback, retriesLeft - 1),
                            FETCH_DELAY_MS);
                } else {
                    finalizeContact(myUid, partnerUid, null, callback);
                }
            })
            .addOnFailureListener(e -> {
                Log.w(TAG, "fetchPartnerNameWithRetry: Firestore error ("
                        + e.getClass().getSimpleName() + ")");
                if (retriesLeft > 0) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> fetchPartnerNameWithRetry(myUid, partnerUid, callback, retriesLeft - 1),
                            FETCH_DELAY_MS);
                } else {
                    finalizeContact(myUid, partnerUid, null, callback);
                }
            });
    }

    // ── Steps 4–6 — call server /createChat, persist Contact ────────────────
    //
    // F6 fix: chats/{chatId} docs are now created exclusively by the server
    // endpoint POST /createChat, which verifies both UIDs exist in identities
    // before writing the doc via Admin SDK (bypassing client rules).
    // The client-side chats create rule is deny — this is the only valid path.

    private void finalizeContact(String myUid, String partnerUid,
                                  String partnerDisplayName, ContactCallback callback) {
        String chatId        = buildChatId(myUid, partnerUid);
        String myDisplayName = getMyDisplayName();
        final String partnerName = partnerDisplayName != null ? partnerDisplayName : "DuoShield User";

        // Get Firebase ID token first, then call server on background thread
        FirebaseAuth.getInstance().getCurrentUser().getIdToken(false)
            .addOnSuccessListener(result -> {
                String idToken = result.getToken();
                SharedExecutors.executeIo(context, () -> {
                    try {
                        String computedChatId = callCreateChatServer(
                                idToken, myUid, partnerUid, myDisplayName, partnerName);
                        // Use the server-returned chatId (should match local buildChatId)
                        String finalChatId = (computedChatId != null && !computedChatId.isEmpty())
                                ? computedChatId : chatId;
                        Contact contact = new Contact(partnerUid, partnerName, finalChatId);
                        AppDatabase.getInstance(context).contactDao().insert(contact);
                        new Handler(Looper.getMainLooper()).post(() ->
                                callback.onAdded(finalChatId, partnerUid, partnerName));
                    } catch (Exception e) {
                        Log.e(TAG, "createChat server call failed ("
                                + e.getClass().getSimpleName() + ")");
                        new Handler(Looper.getMainLooper()).post(() ->
                                callback.onError("Could not create conversation. " + e.getMessage()));
                    }
                });
            })
            .addOnFailureListener(e ->
                callback.onError("Authentication error. Please try again."));
    }

    /**
     * Calls POST /createChat on the push server.
     * Runs on a background thread.
     * @return the chatId returned by the server
     */
    private String callCreateChatServer(String idToken, String myUid, String partnerUid,
                                        String myDisplayName, String partnerDisplayName)
            throws Exception {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new Exception("PUSH_SERVER_URL not configured");
        }
        String endpoint = (serverUrl.endsWith("/") ? serverUrl : serverUrl + "/") + "createChat";

        JSONObject body = new JSONObject();
        body.put("myUid",              myUid);
        body.put("partnerUid",         partnerUid);
        body.put("myDisplayName",      myDisplayName != null ? myDisplayName : "");
        body.put("partnerDisplayName", partnerDisplayName != null ? partnerDisplayName : "");
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + idToken);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String errBody = "";
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) errBody = new String(readAllBytesCompat(es), StandardCharsets.UTF_8);
                }
                throw new Exception("Server returned HTTP " + code + ": " + errBody);
            }

            try (InputStream is = conn.getInputStream()) {
                String json = new String(readAllBytesCompat(is), StandardCharsets.UTF_8);
                return new JSONObject(json).getString("chatId");
            }
        } finally {
            conn.disconnect();
        }
    }

    // ── Utility ─────────���─────────────────────────────────────────────────────

    /** InputStream#readAllBytes() requires API 33; minSdk is 26, so read manually. */
    private static byte[] readAllBytesCompat(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private String getMyDisplayName() {
        // Custom-token auth never sets Firebase displayName — read from SharedPreferences
        // where SettingsActivity saves the user-chosen name ("my_display_name").
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("duoshield_prefs",
                        android.content.Context.MODE_PRIVATE);
        String saved = prefs.getString("my_display_name", null);
        if (saved != null && !saved.trim().isEmpty()) return saved.trim();

        // Fall back to Firebase auth fields (populated on some auth paths)
        FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) return "DuoShield User";
        String dn    = me.getDisplayName();
        String email = me.getEmail();
        if (dn != null && !dn.trim().isEmpty()) return dn.trim();
        if (email != null && !email.isEmpty())  return email.split("@")[0];
        return "DuoShield User";
    }

    /**
     * Deterministic SHA-256 chat ID from two Firebase UIDs.
     * Sorts lexicographically so the result is identical regardless of
     * which user initiates the connection.
     */
    public static String buildChatId(String uidA, String uidB) {
        String a   = uidA.compareTo(uidB) < 0 ? uidA : uidB;
        String b   = uidA.compareTo(uidB) < 0 ? uidB  : uidA;
        String raw = a + "/" + b;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte bt : hash) hex.append(String.format("%02x", bt));
            return hex.toString();
        } catch (Exception e) {
            return raw.replaceAll("[^a-zA-Z0-9]", "");
        }
    }
}
