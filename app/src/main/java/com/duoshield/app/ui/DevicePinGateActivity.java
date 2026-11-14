package com.duoshield.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.SignInActivity;
import com.duoshield.app.util.ButtonPressAnimator;
import com.duoshield.app.util.HapticHelper;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.BackPressGuard;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Device-level PIN gate — the very first screen a fresh install must pass
 * before anything else is rendered (Welcome, Create account, Restore).
 *
 * <p>Unlike {@link com.duoshield.app.LockScreenActivity} (which guards an
 * already-signed-in account and is keyed by Firebase UID), this PIN is
 * device-scoped and exists independently of any account state — see
 * {@link PinManager#setDevicePin}. It is set once, before account creation
 * is even reachable, and verified on every subsequent cold launch so the
 * Welcome/Restore screens are never exposed unprotected.</p>
 *
 * <p>Reached only from {@link com.duoshield.app.SignInActivity}, and only
 * for devices that do not already show signs of a pre-existing account
 * ({@link PinManager#looksLikePreExistingDevice}) — this fix does not
 * retroactively force existing installs through it.</p>
 *
 * <p>Two modes, chosen automatically based on {@link PinManager#hasDevicePinSet}:
 * <ul>
 *   <li><b>Setup</b> (never set before) — reuses the "Set an app PIN" layout.</li>
 *   <li><b>Verify</b> (already set) — reuses the numpad lock-screen layout.</li>
 * </ul>
 * Both hand off to {@link SignInActivity} on success.</p>
 */
public class DevicePinGateActivity extends AppCompatActivity {

    private static final long AUTO_SUBMIT_DEBOUNCE_MS = 600L;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private boolean setupMode;

    // ── Verify-mode views ──────────────────────────────────────────────────
    private PinDotsView pinDotsView;
    private ImageView   ivLockShield;
    private FingerprintScanView fingerprintScanView;
    private final View[] digitKeys = new View[10];
    private View keyBackspace;
    private Button btnUnlockHidden;
    private int pinLength = 6;
    private StringBuilder pinBuffer = new StringBuilder(6);
    private boolean isVerifying = false;
    private final Handler autoSubmitHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAutoSubmit;

    // ── Setup-mode views ─────────────────────────────────────────────────
    private EditText etNewPin;
    private EditText etConfirmPin;

    private TextView tvError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackPressGuard.block(this); // no skipping — the gate must be satisfied

        setupMode = !PinManager.hasDevicePinSet(this);

        if (setupMode) {
            setContentView(R.layout.activity_setup_pin);
            initSetupUi();
        } else {
            setContentView(R.layout.activity_lock_screen);
            initVerifyUi();
        }
    }

    // ── SETUP: first-ever device PIN ────────────────────────────────────────

    private void initSetupUi() {
        etNewPin     = findViewById(R.id.etNewPin);
        etConfirmPin = findViewById(R.id.etConfirmPin);
        tvError      = findViewById(R.id.tvError);
        Button btnContinue = findViewById(R.id.btnContinue);
        ButtonPressAnimator.attach(btnContinue);

        btnContinue.setOnClickListener(v -> {
            String pin     = etNewPin.getText() != null ? etNewPin.getText().toString() : "";
            String confirm = etConfirmPin.getText() != null ? etConfirmPin.getText().toString() : "";

            if (pin.length() < 4 || pin.length() > 6) {
                showError("PIN must be 4–6 digits.");
                return;
            }
            if (!pin.equals(confirm)) {
                showError("PINs don't match. Try again.");
                etConfirmPin.setText("");
                return;
            }

            tvError.setVisibility(View.GONE);
            btnContinue.setEnabled(false);
            bgExecutor.execute(() -> {
                boolean saved = PinManager.setDevicePin(getApplicationContext(), pin);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnContinue.setEnabled(true);
                    if (!saved) {
                        showError("Couldn't securely save your device PIN. Try again.");
                        return;
                    }
                    proceedToWelcome();
                });
            });
        });
    }

    // ── VERIFY: PIN already set on this device ──────────────────────────────

    private void initVerifyUi() {
        pinDotsView         = findViewById(R.id.pinDotsView);
        tvError             = findViewById(R.id.tvError);
        btnUnlockHidden     = findViewById(R.id.btnUnlock);
        fingerprintScanView = findViewById(R.id.fingerprintScanView);
        ivLockShield        = findViewById(R.id.ivLockShield);

        pinLength = PinManager.getDevicePinLength(this);
        pinBuffer = new StringBuilder(pinLength);
        pinDotsView.setMaxCount(pinLength);
        pinDotsView.setColors(
                getResources().getColor(R.color.ds_accent_deep, null),
                getResources().getColor(R.color.ls_dot_empty, null));

        ButtonPressAnimator.attach(btnUnlockHidden);
        btnUnlockHidden.setOnClickListener(v -> checkPin());

        digitKeys[1] = findViewById(R.id.key1);
        digitKeys[2] = findViewById(R.id.key2);
        digitKeys[3] = findViewById(R.id.key3);
        digitKeys[4] = findViewById(R.id.key4);
        digitKeys[5] = findViewById(R.id.key5);
        digitKeys[6] = findViewById(R.id.key6);
        digitKeys[7] = findViewById(R.id.key7);
        digitKeys[8] = findViewById(R.id.key8);
        digitKeys[9] = findViewById(R.id.key9);
        digitKeys[0] = findViewById(R.id.key0);
        keyBackspace  = findViewById(R.id.keyBackspace);

        for (int digit = 0; digit <= 9; digit++) {
            final int d = digit;
            if (digitKeys[d] != null) {
                digitKeys[d].setOnClickListener(v -> onDigitPressed(d));
            }
        }
        keyBackspace.setOnClickListener(v -> onBackspacePressed());
    }

    private void onDigitPressed(int digit) {
        if (isVerifying || pinBuffer.length() >= pinLength) return;
        HapticHelper.lightPress(this);
        pinBuffer.append(digit);
        int len = pinBuffer.length();
        pinDotsView.setFilledCount(len);
        cancelPendingAutoSubmit();
        if (len >= pinLength) {
            // pinLength is PinManager.getDevicePinLength()'s fixed MAX_PIN_LEN upper
            // bound (S08-L3), not this device's real PIN length. Reaching it submits
            // immediately.
            checkPin();
        } else if (len >= PinManager.MIN_PIN_LEN) {
            // S08-L3 follow-up: the real device PIN can be MIN_PIN_LEN..MAX_PIN_LEN
            // digits (see initSetupUi()'s 4-6 bound), and this screen no longer knows
            // the exact real length. Debounce a short pause once at least
            // MIN_PIN_LEN digits are in so a shorter-than-max PIN can still submit —
            // every further digit press cancels this pending submit.
            pendingAutoSubmit = this::checkPin;
            autoSubmitHandler.postDelayed(pendingAutoSubmit, AUTO_SUBMIT_DEBOUNCE_MS);
        }
    }

    private void cancelPendingAutoSubmit() {
        if (pendingAutoSubmit != null) {
            autoSubmitHandler.removeCallbacks(pendingAutoSubmit);
            pendingAutoSubmit = null;
        }
    }

    private void onBackspacePressed() {
        if (isVerifying || pinBuffer.length() == 0) return;
        cancelPendingAutoSubmit();
        pinBuffer.deleteCharAt(pinBuffer.length() - 1);
        pinDotsView.setFilledCount(pinBuffer.length());
        tvError.setVisibility(View.INVISIBLE);
    }

    private void checkPin() {
        cancelPendingAutoSubmit();
        if (isVerifying) return;
        String entered = pinBuffer.toString();
        if (entered.isEmpty()) return;

        // S06-L5 parity fix: this screen re-verifies whatever PIN currently occupies
        // the device-gate slot, which after a duress wipe is the just-promoted
        // secondary/duress code (DuressManager.performLogout()'s promote-and-rotate —
        // see the class javadoc on PinManager's lockout section). Mashing the keypad
        // here has the same accidental-brute-force exposure LockScreenActivity's
        // lockout was built to close, but this screen had no lockout at all. Mirror
        // LockScreenActivity.checkPin() exactly: same PinManager methods, same
        // attempt count, same backoff curve, same storage/counter mechanism — no
        // separate policy.
        long remainingMs = PinManager.getLockoutRemainingMs(this);
        if (remainingMs > 0) {
            long secs = (remainingMs + 999) / 1000;
            tvError.setText("Too many attempts. Try again in " + secs + "s.");
            tvError.setVisibility(View.VISIBLE);
            pinBuffer.setLength(0);
            pinDotsView.setFilledCount(0);
            return;
        }

        isVerifying = true;
        setInputEnabled(false);
        tvError.setText("Verifying…");
        tvError.setVisibility(View.VISIBLE);
        showScanAnim();

        bgExecutor.execute(() -> {
            boolean correct = PinManager.verifyDevicePin(getApplicationContext(), entered);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                isVerifying = false;
                setInputEnabled(true);
                tvError.setVisibility(View.INVISIBLE);
                hideScanAnim();
                if (correct) {
                    PinManager.clearFailedAttempts(this);
                    proceedToWelcome();
                } else {
                    PinManager.recordFailedAttempt(this);
                    handleWrongPin();
                }
            });
        });
    }

    private void handleWrongPin() {
        HapticHelper.wrongPin(this);
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        pinDotsView.startAnimation(shake);
        pinBuffer.setLength(0);
        pinDotsView.setFilledCount(0);
        tvError.setText(getString(R.string.wrong_pin));
        tvError.setVisibility(View.VISIBLE);
    }

    private void setInputEnabled(boolean enabled) {
        btnUnlockHidden.setEnabled(enabled);
        keyBackspace.setEnabled(enabled);
        for (int i = 0; i <= 9; i++) {
            if (digitKeys[i] != null) digitKeys[i].setEnabled(enabled);
        }
    }

    private void showScanAnim() {
        ivLockShield.setVisibility(View.GONE);
        fingerprintScanView.setVisibility(View.VISIBLE);
    }

    private void hideScanAnim() {
        fingerprintScanView.setVisibility(View.GONE);
        ivLockShield.setVisibility(View.VISIBLE);
    }

    // ── Shared ───────────────────────────────────────────────────────────

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        cancelPendingAutoSubmit();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdownNow();
        super.onDestroy();
    }

    private void proceedToWelcome() {
        PinManager.deviceGateSatisfiedThisProcess = true;
        Intent intent = new Intent(this, SignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
