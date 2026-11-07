package com.duoshield.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.TextView;

import com.duoshield.app.BuildConfig;

import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.R;
import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.auth.AuthTokenHelper;
import com.duoshield.app.ui.AddContactActivity;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.backup.BackupScheduler;
import com.duoshield.app.backup.MediaRestoreHelper;
import com.duoshield.app.contacts.AccountIdValidator;
import com.duoshield.app.crypto.BackupCryptoHelper;
import com.duoshield.app.crypto.SeedPhraseHelper;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.security.PendingLockStore;
import com.duoshield.app.util.B2StorageHelper;
import com.duoshield.app.util.DeviceSecurityGate;
import com.duoshield.app.util.FcmTokenHelper;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.SecurePrefs;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import org.signal.libsignal.protocol.IdentityKeyPair;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Restores a DuoShield identity using Account ID + 12-word seed phrase.
 *
 * Both factors are required:
 *  - Account ID:  provided by the user — must match the userId derived from the seed.
 *  - Seed phrase: 12 BIP39 words that deterministically derive the identity key pair.
 *
 * The derived userId must equal the entered Account ID, providing a second
 * factor without any server-side secret: a stolen seed alone cannot restore
 * the account unless the attacker also knows the Account ID.
 *
 * <h3>UID-mismatch fix</h3>
 * This activity now uses Firebase Custom Token authentication (via
 * {@link AuthTokenHelper}) instead of {@code signInAnonymously()}.  The
 * custom token's UID equals {@code userId}, so the Firebase UID is the same
 * on every restore — Firestore queries using that UID always find the right
 * data.
 *
 * If a prior anonymous UID exists in Firestore (old accounts created before
 * this fix), a one-time migration rewrites chat {@code participants} arrays
 * and copies the {@code users/} document so both sides of every conversation
 * continue to work without re-pairing.
 */
public class RestoreFromSeedActivity extends AppCompatActivity {

    private static final String PREFS_NAME        = "duoshield_prefs";
    private static final String KEY_USER_ID        = "my_user_id";
    private static final String KEY_MY_UID         = "my_uid";
    private static final String KEY_IS_PAIRED      = "is_paired";
    private static final String KEY_CONV_ID        = "conversation_id";
    private static final String KEY_PARTNER_UID    = "partner_uid";
    private static final String KEY_ECDH_SHARED    = "ecdh_shared_key";
    private static final String KEY_DISAPPEAR_MS   = "disappear_ms";

  /**
   * Single generic failure message for any restore-time credential mismatch —
   * wrong seed, wrong Account ID, a locked account, or an account that simply
   * doesn't exist. Deliberately does not distinguish which of those it is;
   * doing so would hand an attacker holding a coerced seed phrase a signal
   * about what to try next.
   *
   * <p>"Account does not exist" was chosen deliberately over a vaguer
   * "restore failed, check your details" wording: it reads as a dead end
   * rather than an invitation to keep retrying, and it is the one explanation
   * that is simultaneously true-sounding for a wrong seed, a wrong Account ID,
   * and an account that is actually locked — there is no version of this
   * screen where a locked account's owner benefits from the attacker knowing
   * the account is real but inaccessible instead of believing it never existed.
   *
   * <p><b>Scope limit — read before widening any pattern in
   * {@link #friendlyError(Throwable)}.</b> This message is reserved for
   * outcomes that are genuinely about <em>this account's</em> existence or
   * authorization: a wrong seed, a wrong Account ID, a locked account, or an
   * account that was never created. It must never be shown for a transient or
   * infrastructure failure — no network, request timeout, App Check or gateway
   * rejection, unavailable Firestore, contended local database, locked
   * Keystore. Those say nothing about whether the account exists, and claiming
   * non-existence to someone holding a correct recovery phrase reads as
   * permanent data loss and stops them retrying a restore that would have
   * worked. Two patterns previously broke this rule — a bare {@code "403"}
   * (which caught every gateway 403, not just /mintToken's authorization
   * refusal) and a bare {@code "locked"} (which caught
   * {@code "database is locked"}). Both are now matched precisely.
   */
  private static final String GENERIC_RESTORE_FAILURE = "Account does not exist.";

  /**
   * Shown when a restore could not be completed for a reason that is
   * <em>not</em> account-scoped and may well succeed on the next attempt. Kept
   * deliberately distinct from {@link #GENERIC_RESTORE_FAILURE}: it invites a
   * retry rather than reading as a dead end. It leaks nothing — an attacker
   * holding a coerced seed learns only that the request did not complete,
   * which they can already see from the absence of a restored account.
   */
  private static final String TRANSIENT_RESTORE_FAILURE =
          "Could not complete the restore. Please try again.";

    private TextInputEditText         etAccountId;
    private TextInputEditText         etSeedWords;
    private TextView                  tvError;
    private TextView                  tvMediaProgress;
    private MaterialButton            btnRestore;
    private LinearProgressIndicator   progressRestore;
    private LinearProgressIndicator   progressMediaCacheBar;
    private volatile boolean          restoreSessionEstablished;

    // Restore loader views
    private View                      restoreLoaderFrame;
    private ShieldFillView            shieldFillView;
    private DecryptGridView           decryptGridView;
    private TextView                  tvRestoreStep;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_restore_from_seed);

        etAccountId     = findViewById(R.id.etAccountId);
        etSeedWords     = findViewById(R.id.etSeedWords);
        tvError         = findViewById(R.id.tvError);
        tvMediaProgress      = findViewById(R.id.tvMediaProgress);
        btnRestore           = findViewById(R.id.btnRestore);
        progressRestore      = findViewById(R.id.progressRestore);
        progressMediaCacheBar = findViewById(R.id.progressMediaCacheBar);

        restoreLoaderFrame = findViewById(R.id.restoreLoaderFrame);
        shieldFillView     = findViewById(R.id.shieldFillView);
        decryptGridView    = findViewById(R.id.decryptGridView);
        tvRestoreStep      = findViewById(R.id.tvRestoreStep);


        btnRestore.setOnClickListener(v -> attemptRestore());

        android.widget.ImageView btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());
    }

    /**
     * Hard "no offline use" wall for the recovery screen.
     *
     * <p>This is the screen the offline policy matters most on, and it is pre-auth so it
     * does not extend {@code BaseActivity} and never inherited the gate. Recovery is
     * exactly where the server-side account-lock latch is enforced: a locked account must
     * fail with the same generic message as an account that does not exist, and it must
     * start recovering normally the moment an admin unlocks it. Both of those facts live
     * only on the server. With no server reachable, the enforcement point is not merely
     * degraded — it is absent, and the user instead sees whatever transport-level error
     * happens to surface, which leaks a different failure shape than the deliberately
     * uniform one.
     *
     * <p>Gating at {@code onStart()} rather than inside {@link #attemptRestore()} means
     * the recovery phrase is never even typed into a session that cannot complete, so a
     * 12-word secret is not left sitting in an EditText waiting on a dead network.
     */
    @Override
    protected void onStart() {
        super.onStart();
        com.duoshield.app.util.NetworkStateHelper.blockIfOffline(this);
    }

    // ── Step A — Validate both inputs ────────────────────────────────────────

    private void attemptRestore() {
        hideError();

        // Fail closed before any key material is fetched or decrypted (S08-H5).
        // A restore onto a device with no working Keystore tier would recover the
        // identity key into a store that cannot survive process death: Step F's
        // commit() would report success, the history would appear restored, and the
        // account would be inaccessible on the next cold start — with the local
        // database already written and its passphrase already gone.
        //
        // Checked before input validation on purpose, so the user is not asked to
        // type a 12-word recovery phrase into a device that cannot use it.
        if (!DeviceSecurityGate.checkOrExplain(this, null)) {
            return;
        }

        String accountId = etAccountId != null && etAccountId.getText() != null
                ? etAccountId.getText().toString().trim() : "";
        if (accountId.isEmpty()) { showError("Please enter your Account ID."); return; }

        // A mistyped Account ID is the single most common way a legitimate owner
        // reached GENERIC_RESTORE_FAILURE — the derived-ID comparison below cannot
        // tell "you fat-fingered one character" from "this account never existed",
        // so a typo used to be reported as "Account does not exist." and read as
        // permanent data loss.
        //
        // Rejecting a *malformed* ID here leaks nothing: every real Account ID is
        // SeedPhraseHelper.deriveUserId()'s XXXXX-XXXXX-XXX over the unambiguous
        // base32 alphabet, so a string outside that shape cannot be any account,
        // existing or not. No lookup happens and no account is probed — this is a
        // pure client-side format check, exactly like the 12-word check below it.
        // Enumeration only becomes possible once a *well-formed* ID is treated
        // differently depending on whether it resolves, which is still collapsed
        // into the single generic message.
        if (!AccountIdValidator.isValid(accountId)) {
            showError("That Account ID doesn't look right. It should look like "
                    + "K3MNP-Q8RXA-7BC — 13 characters in three groups. "
                    + "Please check it and try again.");
            return;
        }
        // Canonical (trimmed + upper-cased) form, so a lower-case paste no longer
        // depends on the equalsIgnoreCase below to survive.
        accountId = AccountIdValidator.canonicalizeOrNull(accountId);

        String raw   = etSeedWords.getText() != null ? etSeedWords.getText().toString().trim() : "";
        String[] parts = raw.split("\\s+");
        if (parts.length != 12) { showError("Please enter all 12 words of your Recovery Phrase."); return; }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            // S07-L3: Locale.ROOT explicitly, not the platform default locale — see
            // SeedPhraseHelper.canonicalizeMnemonic()'s javadoc for why a
            // locale-sensitive lower-case must never decide which bytes this
            // mnemonic hashes to. SeedPhraseHelper.mnemonicToSeed() now also
            // canonicalises internally, so this pre-canonicalisation is
            // defense-in-depth, not the only thing standing between a typo here
            // and a wrong derived identity.
            sb.append(parts[i].toLowerCase(Locale.ROOT));
        }
        String mnemonic = sb.toString();

        if (!SeedPhraseHelper.validateMnemonic(mnemonic)) {
            showError("These words don't form a valid recovery phrase. Please check for typos.");
            return;
        }

        setLoading(true);
        setStep("Verifying identity…");
        final String finalMnemonic   = mnemonic;
        final String finalAccountId  = accountId;
        new Thread(() -> {
            try {
                restoreOnBackground(finalMnemonic, finalAccountId);
            } catch (Throwable e) {
                if (restoreSessionEstablished) {
                    rollbackFailedRestore();
                }
                runOnUiThread(() -> {
                    setLoading(false);
                    showError(friendlyError(e));
                });
            }
        }, "restore-seed").start();
    }

    // ── Background restore ────────────────────────────────────────────────────

    private void restoreOnBackground(String mnemonic, String enteredAccountId) throws Exception {

        // Captured BEFORE any local state is written below. Step F overwrites
        // KEY_USER_ID with the identity being restored, so re-reading it afterward
        // (as wipeStaleLocalIdentityIfSwitching used to do) can never see a mismatch —
        // this was the actual value of BUG-D-RESTORE01's guard being silently inert.
        // See wipeStaleLocalIdentityIfSwitching() for why this matters.
        final String existingUserIdBeforeRestore = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_USER_ID, null);

        // ── Step B — Derive seed and identity ────────────────────────────────
        byte[]          seed            = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        IdentityKeyPair identityKeyPair = SeedPhraseHelper.deriveIdentityKeyPair(seed);
        String          derivedUserId   = SeedPhraseHelper.deriveUserId(seed);
        byte[]          pubKeyBytes     = identityKeyPair.getPublicKey().serialize();

        if (!derivedUserId.equalsIgnoreCase(enteredAccountId.trim())) {
            // Deliberately generic: this message must be indistinguishable from any
            // other restore failure (wrong seed, wrong ID, account doesn't exist,
            // network error). Revealing "these two specific things don't match" is
            // itself information an attacker holding a coerced seed phrase could use
            // to narrow down what's wrong and keep guessing.
            runOnUiThread(() -> {
                setLoading(false);
                showError(GENERIC_RESTORE_FAILURE);
            });
            return;
        }

        // ── Step C — Authenticate with a custom token (UID = derivedUserId) ──
        //
        // Using signInWithCustomToken() instead of signInAnonymously() ensures the
        // Firebase UID is always derivedUserId, regardless of how many times the
        // user has signed out.  This permanently resolves the UID-mismatch bug.
        runOnUiThread(() -> setStep("Authenticating…"));

        final Object    authLock  = new Object();
        final String[]  uidHolder = {null};
        final Exception[] authErr = {null};

        // Passes the whole key pair, not just pubKeyBytes: the server now demands a
        // signature over a challenge nonce made with the identity private key
        // (S07-C1 proof of possession). The private key never leaves the device.
        AuthTokenHelper.signInWithSeed(derivedUserId, identityKeyPair, new AuthTokenHelper.Callback() {
            @Override public void onSuccess(String uid) {
                synchronized (authLock) { uidHolder[0] = uid; authLock.notifyAll(); }
            }
            @Override public void onFailure(Exception e) {
                synchronized (authLock) { authErr[0] = e; authLock.notifyAll(); }
            }
        });

        synchronized (authLock) {
            if (uidHolder[0] == null && authErr[0] == null) authLock.wait(60_000);
        }
        if (authErr[0] != null) throw authErr[0];
        if (uidHolder[0] == null) throw new Exception("Authentication timed out.");

        final String currentUid = uidHolder[0];
        if (!derivedUserId.equals(currentUid)) {
            FirebaseAuth.getInstance().signOut();
            throw new SecurityException("Authenticated UID does not match the recovery phrase account.");
        }
        restoreSessionEstablished = true;

        String identityPubKeyHash = sha256Hex(pubKeyBytes);

        // ── Steps D + D2 — Concurrent Firestore reads ────────────────────────
        //
        // Both reads are dispatched simultaneously so neither introduces extra
        // latency beyond the slower of the two round-trips. This also ensures
        // timing parity: a locked account and an unlocked account both wait for
        // exactly the same two in-flight Firestore requests before this method
        // can branch — the lock's existence cannot be inferred from response time.
        //
        // A locked account fails with the *exact same* GENERIC_RESTORE_FAILURE
        // string as a wrong seed phrase or wrong Account ID — never a distinct
        // message, and never logged anywhere an attacker holding the device
        // could see it.
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        com.google.android.gms.tasks.Task<DocumentSnapshot> idTask =
                db.collection("identities").document(derivedUserId).get();
        com.google.android.gms.tasks.Task<DocumentSnapshot> lockTask =
                db.collection("accountLock").document(derivedUserId).get();

        DocumentSnapshot idDoc   = awaitGet(idTask);
        DocumentSnapshot lockDoc = awaitGet(lockTask);
        boolean accountLocked = lockDoc.exists() && Boolean.TRUE.equals(lockDoc.getBoolean("locked"));
        if (accountLocked) {
            rollbackFailedRestore(); // also signs out; nothing else was written to disk yet
            runOnUiThread(() -> {
                setLoading(false);
                showError(GENERIC_RESTORE_FAILURE);
            });
            return;
        }

        // S06-M6: an unfreeze (admin API, locked:false) leaves rotationRequired:true on
        // this same doc, owing a fresh primary PIN + fresh secondary/duress code before
        // the account is usable again — a duress wipe destroys the local session, so this
        // read (the account's sole other client-side consultation of accountLock) is the
        // only place that can ever discover the flag. Recorded now, consumed at the
        // routing tail below and, if the app is killed mid-chain, resumed from
        // MainActivity.route().
        boolean rotationRequired = lockDoc.exists() && Boolean.TRUE.equals(lockDoc.getBoolean("rotationRequired"));

        String oldUid = null; // the previous anonymous UID, if any
        if (idDoc.exists()) {
            // Existing identity record.
            String storedHash = idDoc.getString("identityPubKeyHash");
            // Note: the server already verified storedHash == sha256(pubKey) before minting
            // the token, so a mismatch here means the Firestore record is inconsistent.
            if (storedHash != null && !storedHash.equals(identityPubKeyHash)) {
                throw new Exception("Identity hash mismatch (ID-COLLISION). Contact support.");
            }

            // Record the old anonymous UID so we can migrate Firestore data.
            String storedUid = idDoc.getString("uid");
            if (storedUid != null && !storedUid.equals(currentUid)) {
                oldUid = storedUid;
            }
        }

        // ── Step E — One-time Firestore migration (old anonymous UID → userId) ──
        //
        // Delegates to the push server's /migrateUid endpoint (Admin SDK) so it
        // can update chats/groups even though the client's Firestore rules only
        // allow writes by participants/members with the *current* auth UID.
        //
        // Idempotent: if oldUid == currentUid there's nothing to do.
        if (oldUid != null) {
            runOnUiThread(() -> setStep("Migrating account data…"));
            migrateOldUidViaServer(currentUid, oldUid);
        }

        // /mintToken normally claims this record before authentication. This write is
        // retained only for legacy records that predate the server-side identity claim.
        // ── Step F — Store identity locally ──────────────────────────────────
        boolean identityStored = SecurePrefs.get(this).edit()
                .putString(SignalKeyManager.KEY_IDENTITY_KEY_PAIR,
                        Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP))
                .commit();
        if (!identityStored) {
            throw new IllegalStateException("Unable to save the recovered identity on this device.");
        }
        // BUG-D-RESTORE01 fix: never let a previous account's pairing state
        // (conversation/partner) survive onto the newly restored identity. If the
        // device still holds is_paired/conversation_id/partner_uid from an account
        // that was merely auto-signed-out (not wiped), and the account being restored
        // here happens to have no chat of its own yet, Step I below would find nothing
        // to overwrite them with — leaving this identity looking "paired" with someone
        // else's partner. Clearing unconditionally means Step I's Firestore lookup is
        // the sole source of truth for pairing state after every restore.
        // Mirrors SeedPhraseDisplayActivity's "pending_pin_setup_" marker: if the
        // app is killed between now and the PIN routing below actually finishing
        // (device PIN promoted, or SetupPinActivity completed), MainActivity.route()
        // will send the next launch back to SetupPinActivity instead of letting a
        // PIN-less account reach ConversationListActivity/AddContactActivity. Cleared
        // in the same two places SeedPhraseDisplayActivity's flag is cleared: once a
        // device PIN is promoted below, or once SetupPinActivity itself finishes.
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER_ID, derivedUserId)
                .putString(KEY_MY_UID,  currentUid)
                .putBoolean("pending_pin_setup_" + currentUid, true)
                .remove("explicit_signout")
                .remove(KEY_IS_PAIRED)
                .remove(KEY_CONV_ID)
                .remove(KEY_PARTNER_UID)
                .remove(KEY_ECDH_SHARED)
                .remove(KEY_DISAPPEAR_MS)
                .commit();

        // ── Step G — Upload fresh Signal public key bundle ────────────────────
        runOnUiThread(() -> setStep("Uploading Signal keys…"));
        clearOldPrekeys();
        final Object    keyLock  = new Object();
        final boolean[] keyDone  = {false};
        final Exception[] keyErr = {null};
        SignalKeyManager.generateFromSeedDerivedKey(this,
                () -> { synchronized (keyLock) { keyDone[0] = true; keyLock.notifyAll(); } },
                () -> { synchronized (keyLock) { keyErr[0]  = new Exception("Key upload failed"); keyLock.notifyAll(); } },
                () -> setStep("Uploading Signal keys to server…"));
        synchronized (keyLock) {
            if (!keyDone[0] && keyErr[0] == null) keyLock.wait(60_000);
        }
        if (keyErr[0] != null) throw keyErr[0];

        // ── Step G2 — Wipe any previous account's local data before restoring ──
        //
        // Placed after the key upload succeeds (so we only destroy anything once
        // we're confident this restore attempt is going through) and before any
        // message/contact restore runs (so nothing from a previous identity can
        // still be sitting in Room when the new data is written).
        wipeStaleLocalIdentityIfSwitching(existingUserIdBeforeRestore, derivedUserId);

        // ── Step H — Derive backup key and restore message history ────────────
        runOnUiThread(() -> setStep("Restoring message history…"));
        int restoredCount = 0;
        try {
            BackupCryptoHelper.storeKey(this, mnemonic);
            byte[] backupKey = BackupCryptoHelper.deriveBackupKey(mnemonic);
            restoredCount    = BackupManager.restoreAllSync(this, currentUid, backupKey);
            if (restoredCount > 0) {
                final int rc = restoredCount;
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "Restored " + rc + " messages from backup",
                        android.widget.Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "backup restore failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // ── Step H2 — Restore contacts (chat list) ─────────────────────────────
        runOnUiThread(() -> setStep("Restoring contacts…"));
        try {
            int contactsRestored = BackupManager.restoreContactsSync(this, currentUid);
            android.util.Log.d("RestoreFromSeed", "contacts restored: " + contactsRestored);
        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "contact restore failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // ── Step H2 — Pre-cache B2 media for restored messages ───────────────
        // Downloads and decrypts every photo / video / voice-note referenced in
        // the restored messages and writes them to the persistent disk cache so
        // they open instantly — even without a network connection — after restore.
        // A determinate progress bar shows X / Y files as each completes.
        if (restoredCount > 0) {
            runOnUiThread(() -> setStep("Downloading media files…"));
            try {
                java.util.List<com.duoshield.app.models.Message> restoredMsgs =
                        com.duoshield.app.db.AppDatabase.getInstance(this)
                                .messageDao().getAllActiveMessages();

                MediaRestoreHelper.preCacheMedia(getApplicationContext(), restoredMsgs,
                        (done, total) -> runOnUiThread(() -> {
                            if (total <= 0) return;
                            // Switch from indeterminate to determinate on first callback
                            if (progressRestore != null) progressRestore.setVisibility(View.GONE);
                            if (progressMediaCacheBar != null) {
                                progressMediaCacheBar.setVisibility(View.VISIBLE);
                                int pct = (int) ((done / (float) total) * 100);
                                progressMediaCacheBar.setProgress(pct);
                                // Drive shield fill in sync with media progress bar
                                if (shieldFillView != null) {
                                    shieldFillView.setProgress(pct / 100f);
                                }
                            }
                            if (tvMediaProgress != null) {
                                tvMediaProgress.setVisibility(View.VISIBLE);
                                tvMediaProgress.setText(done + " / " + total + " files");
                            }
                        }));

                // Hide media progress UI when done
                runOnUiThread(() -> {
                    if (progressMediaCacheBar != null)
                        progressMediaCacheBar.setVisibility(View.GONE);
                    if (tvMediaProgress != null) tvMediaProgress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                android.util.Log.w("RestoreFromSeed", "media pre-cache failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
            }
        }

        // ── Step I — Restore conversation state from Firestore ────────────────
        //
        // Now that currentUid == derivedUserId (permanent), this query finds
        // the chat even after the migration rewrote participants.
        try {
            QuerySnapshot chatSnap = awaitQuery(
                    db.collection("chats").whereArrayContains("participants", currentUid).limit(1).get());
            if (!chatSnap.isEmpty()) {
                DocumentSnapshot chatDoc    = chatSnap.getDocuments().get(0);
                List<String>     parts      = (List<String>) chatDoc.get("participants");
                String           partnerUid = null;
                if (parts != null) {
                    for (String p : parts) {
                        if (!p.equals(currentUid)) { partnerUid = p; break; }
                    }
                }
                if (partnerUid != null) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putString(KEY_CONV_ID,    chatDoc.getId())
                            .putString(KEY_PARTNER_UID, partnerUid)
                            .putBoolean(KEY_IS_PAIRED,  true)
                            .apply();
                }
            }
        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "chat state restore failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // ── Step J — Schedule daily backup sync and navigate ─────────────────
        BackupScheduler.schedule(getApplicationContext());

        // If this device already collected a device-level PIN via the upfront
        // gate (DevicePinGateActivity, shown before Welcome/Restore), promote
        // it to this newly-restored account so the existing AppLockManager /
        // LockScreenActivity background-lock keeps working. Restoring never had
        // its own PIN step, so without this a device-gated install would
        // restore into an account with no local PIN at all.
        if (PinManager.hasDevicePinSet(this) && !PinManager.hasPinSet(this)) {
            PinManager.promoteDevicePinToCurrentUser(this);
        }

        SharedPreferences prefs   = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean           paired  = prefs.getBoolean(KEY_IS_PAIRED, false)
                                 && prefs.getString(KEY_CONV_ID, null) != null;
        boolean           unpaired = !paired;

        // S06-M6: a rotationRequired flag (read above, off the same accountLock doc)
        // takes priority over everything else at this routing tail — the account was
        // unfrozen after a duress wipe and owes a fresh primary PIN plus a fresh
        // secondary/duress code before it is usable again. This must run before the
        // Item-4 no-PIN fallback below: a promoted device PIN would otherwise satisfy
        // PinManager.hasPinSet() and let the account skip straight past the mandatory
        // rotation into the app.
        if (rotationRequired) {
            PendingLockStore.setRotationDue(this, unpaired);
            runOnUiThread(() -> {
                FcmTokenHelper.register(this);
                Intent intent = new Intent(this, ForcedPrimaryPinRotationActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
            restoreSessionEstablished = false;
            return;
        }

        // BUG-D-RESTORE-PIN fix: PinManager.looksLikePreExistingDevice() can exempt
        // this device from DevicePinGateActivity even when no device PIN was ever
        // actually set (e.g. stale my_uid/account-PIN-hash left over from a prior
        // account on the same device). The promotion above only covers the case
        // where a device PIN genuinely exists — without this fallback, a restore in
        // that gap landed permanently PIN-less in ConversationListActivity/
        // AddContactActivity with no forced setup screen anywhere (BaseActivity's
        // background-lock silently no-ops for PIN-less accounts). Mirror
        // SeedPhraseDisplayActivity's own else-branch: if there's still no PIN after
        // the promotion attempt, force SetupPinActivity before anything else.
        if (!PinManager.hasPinSet(this)) {
            runOnUiThread(() -> {
                FcmTokenHelper.register(this);
                Intent intent = new Intent(this, SetupPinActivity.class);
                intent.putExtra(SetupPinActivity.EXTRA_ACCOUNT_CREATED, false);
                intent.putExtra(SetupPinActivity.EXTRA_UNPAIRED, unpaired);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
            restoreSessionEstablished = false;
            return;
        }

        // PIN already present (promoted above, or already existed) — the
        // pending_pin_setup_ marker set earlier in this method is only meaningful
        // while no PIN exists yet, so clear it now that setup is genuinely done.
        prefs.edit().remove("pending_pin_setup_" + currentUid).apply();

        Class<?> dest = paired ? ConversationListActivity.class : AddContactActivity.class;

        runOnUiThread(() -> {
            FcmTokenHelper.register(this);
            Intent intent = new Intent(this, dest);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
        restoreSessionEstablished = false;
    }

    // ── One-time migration: old anonymous UID → permanent userId UID ──────────

    /**
     * Calls the push server's {@code /migrateUid} endpoint (Admin SDK) to
     * rewrite Firestore documents that still reference {@code oldUid}:
     * <ul>
     *   <li>users/{oldUid}   copied and deleted</li>
     *   <li>chats participants  updated to use userId</li>
     *   <li>groups members      updated to use userId</li>
     * </ul>
     *
     * <p>Delegating to the server is required because client Firestore rules
     * only allow a participant to update documents they already belong to.
     * After a UID migration the new UID is not yet in the participants array,
     * so the client write would be rejected with PERMISSION_DENIED.  The
     * server uses Admin SDK which bypasses rules, but still verifies the
     * caller's ID token before acting.</p>
     *
     * <p>Safe to call multiple times — the server endpoint is idempotent.</p>
     */
    private void migrateOldUidViaServer(String userId, String oldUid) throws Exception {
        try {
            // Fetch current Firebase ID token to authenticate the server call
            final String[]    tokenHolder = {null};
            final Exception[] tokenErr    = {null};
            final Object      tokenLock   = new Object();
            com.google.firebase.auth.FirebaseUser user =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                throw new IllegalStateException("Migration requires an authenticated session.");
            }
            user.getIdToken(false)
                    .addOnSuccessListener(result -> {
                        synchronized (tokenLock) { tokenHolder[0] = result.getToken(); tokenLock.notifyAll(); }
                    })
                    .addOnFailureListener(e -> {
                        synchronized (tokenLock) { tokenErr[0] = e; tokenLock.notifyAll(); }
                    });
            synchronized (tokenLock) {
                if (tokenHolder[0] == null && tokenErr[0] == null) tokenLock.wait(15_000);
            }
            if (tokenErr[0] != null) {
                throw new Exception("Could not authenticate account migration.", tokenErr[0]);
            }
            String idToken = tokenHolder[0];
            if (idToken == null) {
                throw new Exception("Could not authenticate account migration.");
            }

            // Call POST /migrateUid on the push server
            String serverUrl = BuildConfig.PUSH_SERVER_URL;
            java.net.URL url = new java.net.URL(serverUrl + "/migrateUid");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + idToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);

            // F34 fix: build JSON via JSONObject to prevent injection if either
            // userId or oldUid contains characters that would break string concat.
            org.json.JSONObject reqBodyObj = new org.json.JSONObject();
            reqBodyObj.put("userId", userId);
            reqBodyObj.put("oldUid", oldUid);
            String body = reqBodyObj.toString();
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            String response = "";
            java.io.InputStream responseStream = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            if (responseStream != null) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(responseStream, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) response += line;
                }
            }
            android.util.Log.d("RestoreFromSeed", "migrateOldUid server response: HTTP " + code);
            conn.disconnect();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("Account migration failed (HTTP " + code + "): " + response);
            }
        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "migrateOldUid failed: " + com.duoshield.app.util.LogRedact.error(e));
            throw e;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * BUG-D-RESTORE01 fix: if this device still holds another account's local
     * data — Room DB rows, cached decrypted media — from before this restore,
     * wipe it now, before the incoming identity's messages/contacts are restored
     * on top of it.
     *
     * <p>This matters because none of the local Room tables (messages, contacts,
     * groups, group_members, signal_sessions, call_history) are scoped by owner
     * UID — {@code ContactDao}/{@code MessageDao} queries have no WHERE clause on
     * any owner column. The schema assumes only one identity is ever active on a
     * device at a time, an assumption that {@link com.duoshield.app.util.WipeHelper}
     * and {@link com.duoshield.app.security.DuressManager} correctly enforce for
     * their own exit paths — but nothing enforced it here. The inactivity auto
     * sign-out in {@code BaseActivity} intentionally leaves Room DB and the
     * identity key pair in place (so the same user's session resumes quickly),
     * which means a device can legitimately reach this screen with a previous
     * account's local data still present. Restoring a DIFFERENT identity without
     * this wipe would leave that previous account's messages and contacts mixed
     * into the new account's conversation list.
     *
     * <p>No-op when restoring the SAME account that was last active on this
     * device (an ordinary re-restore after auto sign-out must not discard
     * local-only messages that had not yet been backed up), and no-op on a
     * clean device with no prior identity.
     *
     * <p><b>{@code existingUserId} must be read by the caller before Step F
     * overwrites {@code KEY_USER_ID} with the identity being restored.</b> This
     * method used to re-read {@code KEY_USER_ID} itself at call time, which is
     * always AFTER that overwrite — so {@code existingUserId} was always already
     * equal to {@code incomingUserId} and the switch could never be detected. That
     * left this guard permanently inert: a previous account's Room DB and media
     * cache silently persisted into a newly restored, different identity.
     */
    private void wipeStaleLocalIdentityIfSwitching(String existingUserId, String incomingUserId) {
        if (existingUserId == null || existingUserId.equals(incomingUserId)) {
            return; // fresh device, or restoring the account already active here
        }
        android.util.Log.i("RestoreFromSeed", "Switching local identity ("
                + existingUserId + " -> " + incomingUserId
                + "); wiping this device's local data for the previous account.");
        try {
            // clearInstance() BEFORE deleteDatabase() — releases Room's cached
            // connection first, matching the WipeHelper/DuressManager wipe order.
            AppDatabase.clearInstance();
            deleteDatabase("duoshield_db");
        } catch (Exception e) {
            android.util.Log.e("RestoreFromSeed", "Failed to clear previous account's local database: " + com.duoshield.app.util.LogRedact.error(e));
        }
        try {
            B2StorageHelper.clearDiskCache(this);
        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "clearDiskCache() failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }
    }

    private void clearOldPrekeys() {
        SharedPreferences sp = SecurePrefs.get(this);
        SharedPreferences.Editor ed = sp.edit();
        ed.remove(SignalKeyManager.KEY_SIGNED_PREKEY);
        String ids = sp.getString(SignalKeyManager.KEY_PREKEY_IDS, "");
        if (!ids.isEmpty()) {
            for (String part : ids.split(",")) {
                ed.remove(SignalKeyManager.KEY_PREKEY_PREFIX + part.trim());
            }
        }
        ed.remove(SignalKeyManager.KEY_PREKEY_IDS);
        ed.remove(SignalKeyManager.KEY_REGISTRATION_ID);
        ed.remove(SignalKeyManager.KEY_PREKEY_NEXT_ID);
        ed.remove(SignalKeyManager.KEY_SIGNED_PREKEY_NEXT_ID);
        ed.remove(SignalKeyManager.KEY_SIGNED_PREKEY_PREV);
        ed.remove(SignalKeyManager.KEY_KYBER_PREKEY_CURRENT_ID);
        ed.commit();
    }

    /**
     * Do not leave a partially recovered identity on a device after any post-auth
     * failure. Remote data is untouched; the user can retry with the same phrase.
     */
    private void rollbackFailedRestore() {
        try {
            clearOldPrekeys();
            SecurePrefs.get(this).edit()
                    .remove(SignalKeyManager.KEY_IDENTITY_KEY_PAIR)
                    .remove(BackupCryptoHelper.PREF_KEY)
                    .commit();
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove(KEY_USER_ID)
                    .remove(KEY_MY_UID)
                    .remove(KEY_IS_PAIRED)
                    .remove(KEY_CONV_ID)
                    .remove(KEY_PARTNER_UID)
                    .commit();
            FirebaseAuth.getInstance().signOut();
        } catch (Exception rollbackError) {
            android.util.Log.e("RestoreFromSeed", "Failed to fully roll back restore state: " + com.duoshield.app.util.LogRedact.error(rollbackError));
        } finally {
            restoreSessionEstablished = false;
        }
    }

    private DocumentSnapshot awaitGet(
            com.google.android.gms.tasks.Task<DocumentSnapshot> task) throws Exception {
        final Object[]      holder = {null};
        final Exception[]   err    = {null};
        final Object        lock   = new Object();
        task.addOnSuccessListener(d -> { synchronized (lock) { holder[0] = d; lock.notifyAll(); } })
            .addOnFailureListener(e -> { synchronized (lock) { err[0] = e;    lock.notifyAll(); } });
        synchronized (lock) {
            if (holder[0] == null && err[0] == null) lock.wait(30_000);
        }
        if (err[0] != null) throw err[0];
        if (holder[0] == null) throw new Exception("Firestore get timed out.");
        return (DocumentSnapshot) holder[0];
    }

    private QuerySnapshot awaitQuery(
            com.google.android.gms.tasks.Task<QuerySnapshot> task) throws Exception {
        final Object[]      holder = {null};
        final Exception[]   err    = {null};
        final Object        lock   = new Object();
        task.addOnSuccessListener(s -> { synchronized (lock) { holder[0] = s; lock.notifyAll(); } })
            .addOnFailureListener(e -> { synchronized (lock) { err[0] = e;    lock.notifyAll(); } });
        synchronized (lock) {
            if (holder[0] == null && err[0] == null) lock.wait(30_000);
        }
        if (err[0] != null) throw err[0];
        if (holder[0] == null) throw new Exception("Firestore query timed out.");
        return (QuerySnapshot) holder[0];
    }

    private void awaitVoid(com.google.android.gms.tasks.Task<Void> task) throws Exception {
        final boolean[] done  = {false};
        final Exception[] err = {null};
        final Object lock     = new Object();
        task.addOnSuccessListener(v -> { synchronized (lock) { done[0] = true; lock.notifyAll(); } })
            .addOnFailureListener(e -> { synchronized (lock) { err[0] = e;     lock.notifyAll(); } });
        synchronized (lock) {
            if (!done[0] && err[0] == null) lock.wait(30_000);
        }
        if (err[0] instanceof Exception) throw (Exception) err[0];
        if (!done[0] && err[0] == null) throw new Exception("Operation timed out.");
    }

    private String sha256Hex(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private static String friendlyError(Throwable e) {
        // An absent Throwable is not evidence of an absent account — it is an
        // internal bug. Telling the owner of a perfectly good account that it
        // "does not exist" is the worst possible reading of "we don't know".
        if (e == null) return TRANSIENT_RESTORE_FAILURE;
        String s = e.getMessage() != null ? e.getMessage() : e.toString();
        String lower = s.toLowerCase(Locale.ROOT);

        // ── Transient / infrastructure failures — classified FIRST ────────────
        //
        // These are ordered ahead of the credential bucket deliberately. A
        // dropped connection, an App Check rejection, a cold Firestore or a
        // contended local database says nothing whatsoever about whether the
        // account exists, so none of them may ever surface as
        // GENERIC_RESTORE_FAILURE. Getting "Account does not exist." while
        // holding a correct recovery phrase reads as permanent, irreversible
        // data loss: the owner stops retrying and assumes the account is gone.
        // That is a far more common outcome than the coerced-seed attack the
        // generic message defends against, and the two do not conflict — an
        // attacker learns nothing from "check your connection".
        if (s.contains("429") || s.contains("Too many"))
            return "Too many attempts. Please wait a moment and try again.";
        if (s.contains("PUSH_SERVER_URL"))
            return "The auth server is not configured. Contact support.";
        // Any non-200 the auth server reports through this wrapper is transport
        // or gateway noise (App Check, WAF, proxy, deploy in progress). The two
        // /mintToken refusals that *are* credential signals never reach here —
        // AuthTokenHelper converts both into the explicit strings matched in the
        // credential bucket below, so a bare status code must not be read as one.
        if (s.contains("Auth server returned HTTP"))
            return "Could not reach the auth server. Please try again in a moment.";
        if (s.contains("timeout") || s.contains("timed out")
                || s.contains("network") || s.contains("IOException")
                || s.contains("SocketException") || s.contains("SSLException")
                || s.contains("UnknownHost") || lower.contains("unable to resolve host")
                || lower.contains("failed to connect"))
            return "Could not reach the auth server. Check your internet connection and try again.";
        if (s.contains("FAILED_PRECONDITION") || s.contains("UNAVAILABLE")
                || s.contains("DEADLINE_EXCEEDED"))
            return "Could not reach the server. Check your internet connection and try again.";
        // "database is locked" (SQLCipher/Room contention) and a locked Keystore
        // are the exact strings the old broad `contains("locked")` test swallowed
        // into "Account does not exist." Both are local, retryable conditions.
        if (lower.contains("database is locked") || s.contains("SQLITE_LOCKED")
                || s.contains("KeyStore") || s.contains("Keystore")
                || s.contains("UserNotAuthenticated"))
            return "Could not unlock secure storage on this device. "
                 + "Unlock your screen and try again.";
        if (s.contains("NoClassDefFoundError") || s.contains("UnsatisfiedLink"))
            return "Encryption library failed to load. Please reinstall the app.";

        // ── Credential / lock / absent-account failures ───────────────────────
        //
        // Collapsed to one message, matching the client-side derivedUserId check
        // above — see GENERIC_RESTORE_FAILURE's javadoc for why distinguishing
        // them is unsafe. Every server-side *authorization* refusal of a restore
        // is folded in here, not just the key-mismatch ones. A locked
        // (duress-latched) account is rejected by /mintToken with the 403 body
        // "Access request not approved", and AuthTokenHelper deliberately
        // re-throws that reason string verbatim so the *signup* screen can tell
        // an unapproved invite apart from a bad key. On this screen that same
        // string used to fall through to the "Restore failed: <message>" tail,
        // printing "Restore failed: Access request not approved" — a message no
        // other failure produces, which told anyone holding a coerced seed phrase
        // that the account is real and was locked rather than never having
        // existed. That is exactly the inference GENERIC_RESTORE_FAILURE exists
        // to prevent, so match it (and the invite-gate string a restore of a
        // never-created account produces) before the tail can reach it.
        //
        // Every pattern here names an account-scoped refusal explicitly. Bare
        // "403" and bare "locked" used to live in this list and were the reason
        // ordinary users saw "Account does not exist.": the former caught every
        // gateway 403, the latter caught "database is locked".
        if (s.contains("Key mismatch")
                || s.contains("Recovery phrase does not match")
                || s.contains("Credential mismatch")
                // The UID-mismatch SecurityException thrown after sign-in. Left
                // unmatched it reached the verbatim tail as a message no other
                // failure produces — the same leak as "Access request not approved".
                || s.contains("Authenticated UID does not match")
                || lower.contains("access request")
                || lower.contains("valid invite required")
                || lower.contains("account is locked")
                || lower.contains("account locked")
                || lower.contains("account has been locked"))
            return GENERIC_RESTORE_FAILURE;

        return "Restore failed: " + s;
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void setStep(String label) {
        if (tvRestoreStep != null) {
            runOnUiThread(() -> tvRestoreStep.setText(label));
        }
    }

    private void setLoading(boolean loading) {
        btnRestore.setEnabled(!loading);
        progressRestore.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (restoreLoaderFrame != null) {
            restoreLoaderFrame.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (!loading && shieldFillView != null) {
            shieldFillView.setProgress(0f);
        }
    }
}
