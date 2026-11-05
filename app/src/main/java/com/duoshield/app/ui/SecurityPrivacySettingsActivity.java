package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.PinManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SecurityPrivacySettingsActivity extends BaseActivity {

    // ── Auto sign-out options ─────────────────────────────────────────────────
    private static final long[]   SIGNOUT_MS  = {0, 3_600_000L, 28_800_000L, 86_400_000L, 604_800_000L};
    private static final String[] SIGNOUT_LBL = {"Never", "1 hour", "8 hours", "24 hours", "7 days"};

    // ── Lock timeout options ──────────────────────────────────────────────────
    private static final long[]   LOCK_MS  = {0L, 30_000L, 60_000L, 3 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L};
    private static final String[] LOCK_LBL = {"Immediately", "30 seconds", "1 minute", "3 minutes", "5 minutes", "15 minutes", "30 minutes"};

    private SharedPreferences prefs;
    private SwitchCompat      switchAppScreenshot, switchRelayOnlyCalls;
    private LinearLayout      rowManageUnlockCodes;
    private LinearLayout      layoutPinInputs, layoutPinSet;
    private EditText          etNewPin, etConfirmPin;
    private TextView          tvPinStatus, tvAutoSignOutSub, tvLockTimeoutSub;
    private Button            btnAutoSignOut, btnLockTimeout;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_privacy_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        switchAppScreenshot  = findViewById(R.id.switchAppScreenshot);
        switchRelayOnlyCalls = findViewById(R.id.switchRelayOnlyCalls);
        layoutPinInputs     = findViewById(R.id.layoutPinInputs);
        layoutPinSet        = findViewById(R.id.layoutPinSet);
        rowManageUnlockCodes = findViewById(R.id.rowManageUnlockCodes);
        etNewPin            = findViewById(R.id.etNewPin);
        etConfirmPin        = findViewById(R.id.etConfirmPin);
        tvPinStatus         = findViewById(R.id.tvPinStatus);
        btnAutoSignOut      = findViewById(R.id.btnAutoSignOut);
        tvAutoSignOutSub    = findViewById(R.id.tvAutoSignOutSub);
        btnLockTimeout      = findViewById(R.id.btnLockTimeout);
        tvLockTimeoutSub    = findViewById(R.id.tvLockTimeoutSub);

        Button btnSetPin        = findViewById(R.id.btnSetPin);
        Button btnCancelPinForm = findViewById(R.id.btnCancelPinForm);
        Button btnChangePinMode = findViewById(R.id.btnChangePinMode);

        // ── Restore saved state ───────────────────────────────────────────────
        // Screenshot default comes from BaseActivity.SCREENSHOTS_ALLOWED_BY_DEFAULT so the
        // switch matches the enforced window flag. The default is false: FLAG_SECURE is
        // applied on a fresh install unless the user explicitly opts in to screenshots.
        // The preference is an intentional user choice and is applied across activities.
        if (switchAppScreenshot != null)
            switchAppScreenshot.setChecked(prefs.getBoolean("app_screenshot_enabled",
                    com.duoshield.app.BaseActivity.SCREENSHOTS_ALLOWED_BY_DEFAULT));
        if (switchRelayOnlyCalls != null)
            switchRelayOnlyCalls.setChecked(prefs.getBoolean("relay_only_calls_enabled", false));
        updateAutoSignOutLabel();
        updateLockTimeoutLabel();
        refreshPinStatus();

        // "Manage unlock codes" row
        if (rowManageUnlockCodes != null) {
            rowManageUnlockCodes.setOnClickListener(v ->
                startActivity(new Intent(this, ManageUnlockCodesActivity.class)));
        }

        // ── App PIN ───────────────────────────────────────────────────────────
        if (btnSetPin != null) btnSetPin.setOnClickListener(v -> saveAppPin());
        if (btnChangePinMode != null) btnChangePinMode.setOnClickListener(v -> enterChangePinMode());
        if (btnCancelPinForm != null) btnCancelPinForm.setOnClickListener(v -> refreshPinStatus());

        // Shake to lock has no toggle: it is an implicit part of the duress /
        // secondary-code capability and is gated in BaseActivity, not here.

        // Screenshot toggle — requires the app PIN to change (security gate).
        if (switchAppScreenshot != null) attachScreenshotListener();

        // ── Relay-only calls (F8 fix) ────────────────────────────────────────
        // Forces WebRTC to use only TURN relay candidates so the call partner
        // never learns this device's IP address. See CallManager.createPeerConnection().
        if (switchRelayOnlyCalls != null) {
            switchRelayOnlyCalls.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("relay_only_calls_enabled", checked).apply());
        }

        // ── Auto sign-out ─────────────────────────────────────────────────────
        if (btnAutoSignOut != null) btnAutoSignOut.setOnClickListener(v -> showAutoSignOutPicker());

        // ── Lock timeout ──��─────────────────────────────────────────────────��─
        if (btnLockTimeout != null) btnLockTimeout.setOnClickListener(v -> showLockTimeoutPicker());
    }

    // ── App PIN logic ────────────────�����───────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read the server-side enrollment flag every time this screen is shown,
        // then re-render from the callback. Without this, an enrollment granted by
        // the operator while the account was already signed in would not surface
        // until the next full sign-in — the enrollment would look like a no-op to
        // the user. This also picks up the reverse cases: a revoked account loses
        // the row, and returning from ManageUnlockCodesActivity after a second
        // code was saved re-evaluates the row, which then hides for good.
        refreshPinStatus();
        DuressManager.refreshEligibility(this, () -> {
            // Only the row is re-evaluated here, never the full PIN UI state —
            // this callback lands asynchronously and applyPinUiState() clears the
            // PIN entry fields, which would wipe anything the user started typing
            // in the meantime.
            if (!isFinishing() && !isDestroyed()) {
                refreshManageUnlockCodesRow(PinManager.hasPinSet(this));
            }
        });
    }

    private void saveAppPin() {
        String pin     = etNewPin.getText().toString().trim();
        String confirm = etConfirmPin.getText().toString().trim();

        if (pin.length() < 4 || pin.length() > 6) {
            Toast.makeText(this, "PIN must be 4–6 digits.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!pin.equals(confirm)) {
            Toast.makeText(this, "PINs don't match. Try again.", Toast.LENGTH_SHORT).show();
            etConfirmPin.setText("");
            return;
        }

        if (PinManager.hasPinSet(this)) {
            promptCurrentPin("Enter your current PIN to change it", () -> doSavePin(pin));
        } else {
            doSavePin(pin);
        }
    }

    private void doSavePin(String pin) {
        Button btnSetPin = findViewById(R.id.btnSetPin);
        if (btnSetPin != null) btnSetPin.setEnabled(false);
        tvPinStatus.setText("Saving PIN…");

        bgExecutor.execute(() -> {
            // Catches an exact match against the existing secondary code and the
            // secondary code being a leading prefix of this new primary PIN. See
            // DuressManager.isDuressPinOrPrefixOfIt for the residual direction this
            // does not cover and why it can't be closed here.
            boolean clashWithDuress = DuressManager.isDuressPinOrPrefixOfIt(this, pin);
            // setPin()'s result was previously discarded, so a failed write (no
            // signed-in user, or a Keystore/EncryptedSharedPreferences error) still
            // reported "PIN set" and flipped the UI into its pin-is-set state while
            // no hash existed.
            boolean stored = !clashWithDuress && PinManager.setPin(this, pin);

            runOnUiThread(() -> {
                if (btnSetPin != null) btnSetPin.setEnabled(true);
                if (!clashWithDuress && !stored) {
                    refreshPinStatus();
                    Toast.makeText(this,
                        "Couldn't save your PIN. Try again.",
                        Toast.LENGTH_LONG).show();
                } else if (clashWithDuress) {
                    // Deliberately says nothing about *why* — "another unlock code"
                    // confirmed to anyone testing PINs here that a second code
                    // exists and that they'd just guessed it.
                    refreshPinStatus();
                    Toast.makeText(this,
                        "That PIN can't be used. Choose a different one.",
                        Toast.LENGTH_LONG).show();
                } else {
                    if (etNewPin != null) etNewPin.setText("");
                    if (etConfirmPin != null) etConfirmPin.setText("");
                    applyPinUiState(true);
                    Toast.makeText(this, R.string.settings_pin_set, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void promptCurrentPin(String title, Runnable onVerified) {
        EditText etCurrent = new EditText(this);
        etCurrent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etCurrent.setHint("Current PIN");
        etCurrent.setMaxLines(1);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(etCurrent);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(container)
                .setPositiveButton("Confirm", (d, w) -> {
                    String entered = etCurrent.getText().toString().trim();
                    bgExecutor.execute(() -> {
                        boolean ok = PinManager.verifyPin(this, entered);
                        runOnUiThread(() -> {
                            if (ok) onVerified.run();
                            else Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshPinStatus() {
        applyPinUiState(PinManager.hasPinSet(this));
    }

    private void applyPinUiState(boolean pinSet) {
        if (tvPinStatus == null) return;
        tvPinStatus.setText(pinSet ? "✓  App PIN is set" : "No PIN set — lock screen is disabled");
        tvPinStatus.setTextColor(getResources().getColor(
            pinSet ? R.color.online_green : R.color.text_hint, null));

        if (layoutPinInputs != null) {
            layoutPinInputs.setVisibility(pinSet ? View.GONE : View.VISIBLE);
        }
        if (layoutPinSet != null) {
            layoutPinSet.setVisibility(pinSet ? View.VISIBLE : View.GONE);
        }
        refreshManageUnlockCodesRow(pinSet);
        Button cancel = findViewById(R.id.btnCancelPinForm);
        if (cancel != null) cancel.setVisibility(View.GONE);
        if (etNewPin != null) etNewPin.setText("");
        if (etConfirmPin != null) etConfirmPin.setText("");
    }

    /**
     * Shows the "Manage unlock codes" entry point only in the one state where it
     * has anything to offer:
     *
     * <ul>
     *   <li><b>No app PIN</b> — there is no lock screen for a second code to work
     *       at, so nothing to configure.</li>
     *   <li><b>Not enrolled server-side</b> — the capability does not exist for
     *       this account. No row, so no dead-end screen and no hint that such a
     *       thing is even possible. An account created by anyone probing the app
     *       is never enrolled and never sees this.</li>
     *   <li><b>Already configured</b> — the row disappears and does not come back.
     *       From that point the second code exists <em>only</em> as a salted hash
     *       in {@link com.duoshield.app.util.SecurePrefs} and one branch in
     *       {@code LockScreenActivity}. Nothing in the running app names it,
     *       lists it, indicates it is set, or offers to change it.</li>
     * </ul>
     *
     * <p>Enrollment is what makes the row appear; using it is what makes the row
     * — and every other reference to the feature — vanish.
     */
    private void refreshManageUnlockCodesRow(boolean pinSet) {
        if (rowManageUnlockCodes == null) return;
        boolean offerSecondCode = pinSet
                && DuressManager.isDuressEligibleCached(this)
                && !DuressManager.hasDuressPin(this);
        rowManageUnlockCodes.setVisibility(offerSecondCode ? View.VISIBLE : View.GONE);
    }

    private void enterChangePinMode() {
        if (layoutPinInputs != null) layoutPinInputs.setVisibility(View.VISIBLE);
        if (layoutPinSet    != null) layoutPinSet.setVisibility(View.GONE);
        Button cancel = findViewById(R.id.btnCancelPinForm);
        if (cancel != null) cancel.setVisibility(View.VISIBLE);
        if (etNewPin != null) etNewPin.requestFocus();
    }

    // ── Screenshot toggle helpers ─────────────────────────────────────────────

    private boolean screenshotListenerGuard = false;

    private void attachScreenshotListener() {
        if (switchAppScreenshot == null) return;
        switchAppScreenshot.setOnCheckedChangeListener((btn, checked) -> {
            if (screenshotListenerGuard) return;

            if (!PinManager.hasPinSet(this)) {
                prefs.edit().putBoolean("app_screenshot_enabled", checked).apply();
                applyScreenshotFlag(checked);
                return;
            }

            screenshotListenerGuard = true;
            switchAppScreenshot.setChecked(!checked);
            screenshotListenerGuard = false;

            final boolean desired = checked;
            promptCurrentPin(
                checked ? "Enable screenshots" : "Disable screenshots",
                () -> {
                    screenshotListenerGuard = true;
                    switchAppScreenshot.setChecked(desired);
                    screenshotListenerGuard = false;
                    prefs.edit().putBoolean("app_screenshot_enabled", desired).apply();
                    applyScreenshotFlag(desired);
                });
        });
    }

    /**
     * S08-H2 fix: this previously ignored {@code allow} entirely and always cleared
     * FLAG_SECURE, so toggling the switch off did nothing on this screen (and, before
     * BaseActivity's matching fix, anywhere else either). Now applies the requested
     * state to this activity's own window immediately, for instant feedback in this
     * screen; BaseActivity.onCreate() re-reads the persisted preference and applies it
     * app-wide on every other screen, including this one on its next recreation.
     */
    private void applyScreenshotFlag(boolean allow) {
        if (allow) {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // ── Lock timeout ──────────────────────────────────────────────────────────

    private void showLockTimeoutPicker() {
        long current = prefs.getLong(AppLockManager.KEY_LOCK_TIMEOUT_MS, AppLockManager.DEFAULT_LOCK_TIMEOUT);
        int checked = 3;
        for (int i = 0; i < LOCK_MS.length; i++) {
            if (LOCK_MS[i] == current) { checked = i; break; }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Lock screen after")
            .setSingleChoiceItems(LOCK_LBL, checked, (d, which) -> {
                prefs.edit().putLong(AppLockManager.KEY_LOCK_TIMEOUT_MS, LOCK_MS[which]).apply();
                updateLockTimeoutLabel();
                d.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateLockTimeoutLabel() {
        if (btnLockTimeout == null) return;
        long ms = prefs.getLong(AppLockManager.KEY_LOCK_TIMEOUT_MS, AppLockManager.DEFAULT_LOCK_TIMEOUT);
        String label = "3 minutes";
        for (int i = 0; i < LOCK_MS.length; i++) {
            if (LOCK_MS[i] == ms) { label = LOCK_LBL[i]; break; }
        }
        btnLockTimeout.setText(label);
        if (tvLockTimeoutSub != null) {
            if (ms == 0L) {
                tvLockTimeoutSub.setText("Lock screen will appear as soon as you leave the app.");
            } else {
                tvLockTimeoutSub.setText("Lock screen will appear after " + label + " in the background.");
            }
        }
    }

    // ── Auto sign-out ─────────────────────────────────────────────────────────

    private void showAutoSignOutPicker() {
        long current = prefs.getLong("auto_signout_ms", 0);
        int checked = 0;
        for (int i = 0; i < SIGNOUT_MS.length; i++) {
            if (SIGNOUT_MS[i] == current) { checked = i; break; }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Auto sign-out")
            .setSingleChoiceItems(SIGNOUT_LBL, checked, (d, which) -> {
                prefs.edit().putLong("auto_signout_ms", SIGNOUT_MS[which]).apply();
                updateAutoSignOutLabel();
                d.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateAutoSignOutLabel() {
        if (btnAutoSignOut == null) return;
        long ms = prefs.getLong("auto_signout_ms", 0);
        String label = "Never";
        for (int i = 0; i < SIGNOUT_MS.length; i++) {
            if (SIGNOUT_MS[i] == ms) { label = SIGNOUT_LBL[i]; break; }
        }
        btnAutoSignOut.setText(label);
        if (tvAutoSignOutSub != null) {
            tvAutoSignOutSub.setText(ms <= 0
                ? "You will not be signed out automatically."
                : "You will be signed out after " + label + " of inactivity.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
