package com.duoshield.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.R;
import com.duoshield.app.util.FcmTokenHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.crypto.BackupCryptoHelper;
import com.duoshield.app.crypto.SeedPhraseHelper;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.SecurePrefs;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import org.signal.libsignal.protocol.IdentityKeyPair;

import java.util.HashMap;
import java.util.Map;

/**
 * Recovery Phrase screen — shown once during new account creation.
 *
 * <p>Displays the 12-word BIP39 mnemonic and requires the user to confirm
 * they have saved it before keys are derived and uploaded.  The mnemonic is
 * NEVER stored on-device — it is passed via Intent and lives only in memory.</p>
 *
 * <p>Accepts {@link #EXTRA_DISPLAY_NAME} from {@link com.duoshield.app.DisplayNameActivity}
 * and persists it to SharedPreferences + Firestore after identity setup.</p>
 */
public class SeedPhraseDisplayActivity extends AppCompatActivity {

    public static final String EXTRA_MNEMONIC      = "mnemonic";
    public static final String EXTRA_DISPLAY_NAME  = "display_name";

    public static final String EXTRA_ACCOUNT_CREATED = "account_created";

    private static final String PREFS_NAME  = "duoshield_prefs";
    private static final String KEY_USER_ID = "my_user_id";

    private String mnemonic;
    private String displayName;
    private boolean wordsVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seed_phrase_display);

        mnemonic    = getIntent().getStringExtra(EXTRA_MNEMONIC);
        displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);

        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            Toast.makeText(this, "No recovery phrase provided.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        CheckBox                cbSaved       = findViewById(R.id.cbSaved);
        MaterialButton          btnContinue   = findViewById(R.id.btnContinue);
        GridLayout              gridWords     = findViewById(R.id.gridWords);
        LinearLayout            gridWordsCard = findViewById(R.id.gridWordsCard);
        LinearProgressIndicator progress      = findViewById(R.id.progressSetup);
        TextView                tvStep        = findViewById(R.id.tvStep);
        MaterialButton          btnCopy       = findViewById(R.id.btnCopyPhrase);
        MaterialButton          btnViewQr     = findViewById(R.id.btnViewQr);
        MaterialButton          btnHide       = findViewById(R.id.btnHidePhrase);

        // ── Back ──────────────────────────────────────────────────────────────
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // ── Populate the 12-word grid ─────────────────────────────────────────
        // Each cell shows the word number in muted gray and the word in accent color,
        // e.g. "1. abandon". Numbers are essential — users must write words in order.
        String[] words = mnemonic.trim().split("\\s+");
        for (int i = 0; i < words.length && i < 12; i++) {
            String label = (i + 1) + ". " + words[i];
            SpannableString ss = new SpannableString(label);
            // Number part: muted gray
            int dotEnd = String.valueOf(i + 1).length() + 2; // "N. "
            ss.setSpan(new ForegroundColorSpan(0xFF777777), 0, dotEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Word part: accent purple
            ss.setSpan(new ForegroundColorSpan(0xFF9A81FF), dotEnd, label.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            TextView tv = new TextView(this);
            tv.setText(ss);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(15f);
            tv.setPadding(12, 8, 12, 8);
            tv.setGravity(Gravity.START);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.columnSpec = GridLayout.spec(i % 2, 1f);
            params.rowSpec    = GridLayout.spec(i / 2);
            params.width      = 0;
            params.setMargins(0, 2, 0, 2);
            tv.setLayoutParams(params);
            gridWords.addView(tv);
        }

        // ── Copy button — intentionally disabled ──────────────────────────────
        // The recovery phrase must NEVER be written to the system clipboard.
        // Clipboard history is accessible to other apps and persists after the user
        // leaves this screen; if an attacker reads it they have the master secret and
        // can restore the account in full. Use the QR button to transfer to a new device.
        if (btnCopy != null) {
            btnCopy.setVisibility(android.view.View.GONE);
        }

        // ── View QR ───────────────────────────────────────────────────────────
        if (btnViewQr != null) {
            btnViewQr.setOnClickListener(v -> showMnemonicQrDialog());
        }

        // ── Hide button — toggles word grid visibility ────────────────────────
        if (btnHide != null && gridWordsCard != null) {
            btnHide.setOnClickListener(v -> {
                wordsVisible = !wordsVisible;
                gridWordsCard.setVisibility(wordsVisible ? View.VISIBLE : View.GONE);
                btnHide.setText(wordsVisible ? "Hide" : "Show");
                btnHide.setTextColor(wordsVisible ? 0xFFFF4444 : 0xFF9A81FF);
                btnHide.setStrokeColor(
                        android.content.res.ColorStateList.valueOf(
                                wordsVisible ? 0xFFFF4444 : 0xFF9A81FF));
            });
        }

        // ── Checkbox gates Continue ───────────────────────────────────────────
        btnContinue.setEnabled(false);
        cbSaved.setOnCheckedChangeListener((btn, checked) ->
                btnContinue.setEnabled(checked));

        btnContinue.setOnClickListener(v ->
                deriveAndStore(mnemonic, btnContinue, progress, tvStep));
    }

    // ── Mnemonic QR dialog ────────────────────────────────────────────────────

    private void showMnemonicQrDialog() {
        if (mnemonic == null || mnemonic.trim().isEmpty()) return;
        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.encodeBitmap(mnemonic.trim(), BarcodeFormat.QR_CODE, 600, 600);

            int dp16 = dp(16);
            int dp8  = dp(8);

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp16, dp8, dp16, dp8);

            // UX-5 fix: this warning led with a "⚠" emoji, which renders inconsistently across
            // OEM emoji fonts and is announced awkwardly by TalkBack mid-sentence. It is now the
            // real ic_warning vector as a compound drawable, tinted to match the warning text.
            // Bounds are set explicitly (ic_warning's intrinsic size is meant for banner use)
            // and the instance is mutate()d so this tint cannot leak to other ic_warning users
            // sharing the same cached constant state.
            TextView warning = new TextView(this);
            warning.setText(R.string.seed_qr_private_warning);
            warning.setTextSize(13f);
            warning.setTextColor(0xFFE7B15D);
            warning.setPadding(0, 0, 0, dp16);
            android.graphics.drawable.Drawable warnIcon =
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_warning);
            if (warnIcon != null) {
                warnIcon = warnIcon.mutate();
                int wpx = dp(15);
                warnIcon.setBounds(0, 0, wpx, wpx);
                androidx.core.graphics.drawable.DrawableCompat.setTint(warnIcon, 0xFFE7B15D);
                warning.setCompoundDrawablesRelative(warnIcon, null, null, null);
                warning.setCompoundDrawablePadding(dp8);
            }
            container.addView(warning);

            ImageView qrView = new ImageView(this);
            qrView.setImageBitmap(bitmap);
            qrView.setBackgroundColor(Color.WHITE);
            int size = dp(260);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, 0, 0, dp8);
            qrView.setLayoutParams(lp);
            container.addView(qrView);

            TextView hint = new TextView(this);
            hint.setText("Scan with DuoShield on your new device to restore your account.");
            hint.setTextSize(12f);
            hint.setTextColor(0xFF9A8FB0);
            hint.setPadding(0, dp8, 0, 0);
            container.addView(hint);

            new AlertDialog.Builder(this)
                    .setTitle("Recovery Phrase QR")
                    .setView(container)
                    .setPositiveButton("Done", null)
                    .show();

        } catch (WriterException e) {
            Toast.makeText(this, "Could not generate QR code.", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final String TAG = "SeedPhraseDisplayActivity";

    // ── Identity setup ────────────────────────────────────────────────────────

    private void deriveAndStore(String mnemonic,
                                MaterialButton btnContinue,
                                LinearProgressIndicator progress,
                                TextView tvStep) {
        btnContinue.setEnabled(false);
        btnContinue.setText("Setting up…");
        progress.setVisibility(View.VISIBLE);
        setStep(tvStep, "Step 1/4 — Deriving identity key pair…");

        new Thread(() -> {
            try {
                Log.i(TAG, "deriveAndStore: START");

                // ── Step 1: derive seed + identity key pair ───────────────
                byte[]          seed            = SeedPhraseHelper.mnemonicToSeed(mnemonic);
                IdentityKeyPair identityKeyPair = SeedPhraseHelper.deriveIdentityKeyPair(seed);
                String          userId          = SeedPhraseHelper.deriveUserId(seed);
                Log.i(TAG, "[1/4] derived userId=" + userId);

                // ── Step 2: persist to encrypted prefs ────────────────────
                setStep(tvStep, "Step 2/4 — Saving identity key…");
                Log.d(TAG, "[2/4] writing identity key to SecurePrefs"
                        + "  (available=" + com.duoshield.app.util.SecurePrefs.isAvailable() + ")");

                boolean stored = SecurePrefs.get(this).edit()
                        .putString(SignalKeyManager.KEY_IDENTITY_KEY_PAIR,
                                Base64.encodeToString(
                                        identityKeyPair.serialize(), Base64.NO_WRAP))
                        .commit();

                if (!stored) throw new IllegalStateException(
                        "SecurePrefs write failed — encrypted storage unavailable.");
                Log.i(TAG, "[2/4] identity key committed to SecurePrefs");

                // Derive and store backup key while mnemonic is in memory
                BackupCryptoHelper.storeKey(this, mnemonic);

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_USER_ID, userId).apply();

                // BUG-F-12 fix: Persist my_uid immediately so SplashActivity can route correctly on cold start.
                // Also clear any stale explicit_signout flag so BaseActivity.onStart() in the
                // upcoming ConversationListActivity does not immediately redirect back to SignIn.
                com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user != null) {
                    Log.i(TAG, "[2/4] persisting my_uid=" + user.getUid());
                    // Marks this account as "mandatory PIN setup not yet finished".
                    // If the app is killed anywhere between here and SetupPinActivity
                    // successfully saving a PIN, SignInActivity's returning-user route()
                    // checks this flag and sends the user back to SetupPinActivity
                    // instead of ConversationListActivity, so a half-created account can
                    // never get stranded without a way to finish setup. Cleared by
                    // SetupPinActivity once PinManager.setPin() succeeds. Legacy accounts
                    // (created before this flow existed) never have this flag set, so
                    // they are never retroactively forced into PIN setup.
                    prefs.edit()
                         .putString("my_uid", user.getUid())
                         .putBoolean("pending_pin_setup_" + user.getUid(), true)
                         .remove(com.duoshield.app.BaseActivity.KEY_EXPLICIT_SIGNOUT)
                         .remove("signed_out_reason_inactivity")
                         .apply();
                } else {
                    Log.w(TAG, "[2/4] WARNING: FirebaseAuth.getCurrentUser() is null — my_uid not persisted");
                    prefs.edit()
                         .remove(com.duoshield.app.BaseActivity.KEY_EXPLICIT_SIGNOUT)
                         .apply();
                }

                // Save display name locally
                if (displayName != null && !displayName.isEmpty()) {
                    prefs.edit().putString("my_display_name", displayName).apply();
                }

                // ── Step 3: generate remaining Signal keys ─────────────────
                setStep(tvStep, "Step 3/4 — Generating Signal keys…");
                Log.d(TAG, "[3/4] calling generateFromSeedDerivedKey…");

                SignalKeyManager.generateFromSeedDerivedKey(
                        this,
                        () -> {
                            // onSuccess — back on main thread
                            Log.i(TAG, "[3/4] Signal keys generated and uploaded — navigating to ConversationList");
                            progress.setVisibility(View.GONE);
                            tvStep.setVisibility(View.GONE);
                            // Save display name to Firebase after keys are uploaded
                            if (displayName != null && !displayName.isEmpty()) {
                                saveDisplayNameToFirebase(displayName);
                            }
                            // Register identity so other users can look this account
                            // up by its User ID in ContactManager.addContact().
                            // RestoreFromSeedActivity does this correctly; new-account
                            // creation was missing this write — nobody could ever be
                            // found until it was added here.
                            registerIdentity(userId);
                            // BUG-F-12 fix: Register FCM token immediately so notifications work on first install.
                            FcmTokenHelper.register(this);
                            // Every new account must have a PIN before it can use the
                            // app. Devices that came through the new upfront device-PIN
                            // gate (DevicePinGateActivity, shown before Welcome) already
                            // collected one — promote it to this account instead of
                            // asking again. Devices that predate that gate (no device
                            // PIN on file) fall back to the original SetupPinActivity
                            // hand-off.
                            if (PinManager.hasDevicePinSet(this)) {
                                Log.i(TAG, "[3/4] device PIN already set — promoting to account, skipping SetupPinActivity");
                                PinManager.promoteDevicePinToCurrentUser(this);
                                Intent intent = new Intent(this, ConversationListActivity.class);
                                intent.putExtra(SetupPinActivity.EXTRA_ACCOUNT_CREATED, true);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                            } else {
                                Intent intent = new Intent(this, SetupPinActivity.class);
                                intent.putExtra(SetupPinActivity.EXTRA_ACCOUNT_CREATED, true);
                                startActivity(intent);
                            }
                            finish();
                        },
                        () -> {
                            // onFailure
                            showSetupError(btnContinue, progress, tvStep,
                                    "Could not upload your keys to the server. "
                                    + "Check your connection and try again.");
                        },
                        () -> {
                            // onUploadStarted
                            setStep(tvStep, "Step 4/4 — Uploading to server…");
                        });

            } catch (Throwable e) {
                runOnUiThread(() ->
                        showSetupError(btnContinue, progress, tvStep, friendlyError(e)));
            }
        }, "seed-derive").start();
    }

    private void registerIdentity(String userId) {
        // F2 fix: the server now creates the identities doc atomically during /mintToken
        // for new accounts. This client-side call is kept as an idempotent fallback
        // (e.g. for offline/delayed cases) but uses SetOptions.merge() so it never
        // overwrites the identityPubKeyHash already written by the server.
        try {
            com.google.firebase.auth.FirebaseUser user =
                    FirebaseAuth.getInstance().getCurrentUser();
            if (user == null || userId == null) return;
            Map<String, Object> idDoc = new HashMap<>();
            idDoc.put("uid", user.getUid());
            FirebaseFirestore.getInstance()
                    .collection("identities")
                    .document(userId)
                    .set(idDoc, SetOptions.merge())
                    .addOnFailureListener(e ->
                            android.util.Log.w("SeedPhraseDisplay",
                                    "identities write failed (non-fatal)", e));
        } catch (Exception e) {
            android.util.Log.w("SeedPhraseDisplay", "registerIdentity failed (non-fatal)", e);
        }
    }

    private void saveDisplayNameToFirebase(String name) {
        try {
            com.google.firebase.auth.FirebaseUser user =
                    FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;
            Map<String, Object> data = new HashMap<>();
            data.put("displayName", name);
            FirebaseFirestore.getInstance()
                    .collection("users").document(user.getUid())
                    .set(data, SetOptions.merge());
        } catch (Exception e) {
            android.util.Log.w("SeedPhraseDisplay", "saveDisplayNameToFirebase failed (non-fatal)", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setStep(TextView tvStep, String label) {
        runOnUiThread(() -> {
            tvStep.setText(label);
            tvStep.setVisibility(View.VISIBLE);
        });
    }

    private void showSetupError(MaterialButton btnContinue,
                                LinearProgressIndicator progress,
                                TextView tvStep,
                                String message) {
        btnContinue.setEnabled(true);
        btnContinue.setText("Try Again");
        progress.setVisibility(View.GONE);
        tvStep.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String friendlyError(Throwable e) {
        if (e == null) return "An unexpected error occurred. Please try again.";
        String s = e.toString();
        if (s.contains("NoClassDefFoundError") || s.contains("UnsatisfiedLink")
                || s.contains("ExceptionInInitializerError"))
            return "Encryption library failed to load. Please reinstall the app.";
        if (s.contains("SecurePrefs write failed"))
            return "Could not write identity keys to device storage. "
                 + "Please free up storage space and try again.";
        if (s.contains("FAILED_PRECONDITION") || s.contains("UNAVAILABLE")
                || s.contains("IOException") || s.contains("timeout")
                || s.contains("network") || s.contains("Connection"))
            return "Could not reach the server. Check your internet connection and try again.";
        if (e instanceof OutOfMemoryError)
            return "Not enough memory. Close background apps and try again.";
        if (s.contains("InvalidKeyException") || s.contains("InvalidKey"))
            return "Key derivation produced an invalid result. Please try again.";
        return "Identity setup failed. Please try again.";
    }
}
