package com.duoshield.app;

import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.ui.FingerprintScanView;
import com.duoshield.app.ui.PinDotsView;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.ButtonPressAnimator;
import com.duoshield.app.util.HapticHelper;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.BackPressGuard;

/**
 * Lock screen shown after the background timeout.
 *
 * <h3>PIN-failure policy</h3>
 * <ul>
 *   <li>Any wrong PIN: "Incorrect PIN" error with shake animation. There is no
 *       attempt limit — an exact PIN B match is the only signal that triggers
 *       {@link DuressManager#performLogout(android.content.Context)}. A repeated-
 *       wrong-guess fallback was considered and explicitly removed: it created
 *       false-positive lockouts (typos, kids, a drunk owner) with no upside, since
 *       duress is meant to be a single deliberate, unambiguous signal.</li>
 * </ul>
 *
 * Input is now handled by an on-screen custom numpad — no system keyboard needed.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String PREFS_NAME     = "duoshield_prefs";

    // UI refs
    private PinDotsView      pinDotsView;
    private TextView         tvError;
    private Button           btnUnlock;   // kept GONE; ButtonPressAnimator still attaches
    private FingerprintScanView fingerprintScanView;
    private ImageView        ivLockShield;

    // Custom numpad keys (0–9) + backspace
    private final View[] digitKeys = new View[10];
    private View keyBackspace;

    /** Actual length of the user's PIN — read from PinManager on create. */
    private int pinLength = 6;

    /** Accumulated PIN digits — never leaves this Activity. */
    private StringBuilder pinBuffer = new StringBuilder(6);

    private boolean isVerifying = false;
    // NOTE: this screen used to auto-submit on a typing-pause debounce (Handler +
    // postDelayed) once MIN_PIN_LEN digits were entered, because it has no visible
    // confirm action and no longer knows the account's real PIN length (see the
    // length-disclosure fix referenced below). That timer submitted whatever was in
    // the buffer at the moment the pause was detected — including a partial, correct
    // PIN interrupted mid-entry. Since the primary and secondary (duress) PINs are
    // only checked for exact equality against each other, not for one being a prefix
    // of the other, an ordinary pause while typing a real PIN could get read as a
    // duress PIN and trigger an irreversible wipe, and the reverse could silently
    // swallow a genuine duress entry. Verification must only ever run from a
    // deliberate action: an explicit tap on keyConfirm, or reaching MAX_PIN_LEN where
    // there is no ambiguity left to wait out. Do not reintroduce a pause-based timer
    // here.
    private ImageView ivKeyConfirm;
    private View keyConfirm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackPressGuard.block(this); // user must authenticate

        // S08-H2: honor the "Allow screenshots" preference (secure by default) on the
        // PIN-entry screen instead of unconditionally allowing screenshots here too.
        BaseActivity.applyScreenshotSecurity(this);

        setContentView(R.layout.activity_lock_screen);

        pinDotsView         = findViewById(R.id.pinDotsView);
        tvError             = findViewById(R.id.tvError);
        btnUnlock           = findViewById(R.id.btnUnlock);
        fingerprintScanView = findViewById(R.id.fingerprintScanView);
        ivLockShield        = findViewById(R.id.ivLockShield);

        // If no PIN has been set yet, skip lock entirely
        if (!PinManager.hasPinSet(this)) {
            AppLockManager.onAppForegrounded(this);
            finish();
            return;
        }

        // Read the actual PIN length so dots and buffer match what the user set
        pinLength = PinManager.getPinLength(this);
        pinBuffer = new StringBuilder(pinLength);
        pinDotsView.setMaxCount(pinLength);

        // Light-mode dot colours for the lavender lock screen
        pinDotsView.setColors(
                getResources().getColor(R.color.ds_accent_deep, null),
                getResources().getColor(R.color.ls_dot_empty, null));

        // Hidden unlock button (GONE in layout — kept so ButtonPressAnimator doesn't NPE)
        ButtonPressAnimator.attach(btnUnlock);
        btnUnlock.setOnClickListener(v -> checkPin());

        // ── Wire numpad keys ──────────────────────────────────────────────
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
        keyConfirm    = findViewById(R.id.keyConfirm);
        ivKeyConfirm  = findViewById(R.id.ivKeyConfirm);

        for (int digit = 0; digit <= 9; digit++) {
            final int d = digit;
            if (digitKeys[d] != null) {
                digitKeys[d].setOnClickListener(v -> onDigitPressed(d));
            }
        }
        keyBackspace.setOnClickListener(v -> onBackspacePressed());
        // The only two ways checkPin() ever runs: this explicit tap, or reaching
        // MAX_PIN_LEN in onDigitPressed(). Never on a typing pause.
        keyConfirm.setOnClickListener(v -> checkPin());
        updateConfirmKeyState();
    }

    // ── Numpad input ──────────────────────────────────────────────────────

    private void onDigitPressed(int digit) {
        if (isVerifying || pinBuffer.length() >= pinLength) return;
        HapticHelper.lightPress(this);
        pinBuffer.append(digit);
        int len = pinBuffer.length();
        pinDotsView.setFilledCount(len);
        updateConfirmKeyState();
        if (len >= pinLength) {
            // Numpad's dot indicator/buffer are sized to PinManager.getPinLength(),
            // the fixed MAX_PIN_LEN upper bound (S08-L3) — not this account's real
            // PIN length, which is no longer stored anywhere. Reaching that bound
            // submits immediately since there is no more room to add digits, so
            // there's no ambiguity left to wait out.
            checkPin();
        }
        // S08-L3 follow-up, corrected: an account's real PIN can be anywhere from
        // MIN_PIN_LEN to MAX_PIN_LEN digits, and this screen can't size the buffer
        // to the exact real length without reintroducing the plaintext-length
        // disclosure that fix removed. This USED to debounce a typing pause and
        // auto-submit once MIN_PIN_LEN digits were reached — but a pause is not
        // the same signal as "the user is done." Because the primary and duress
        // PINs are only checked for exact equality against each other (not for
        // one being a prefix of the other), a routine mid-entry pause on a real
        // PIN could get read as a shorter duress PIN and trigger an irreversible
        // wipe, or a genuine duress entry could get swallowed by the same timer.
        // Below MAX_PIN_LEN, verification now only runs from an explicit tap on
        // keyConfirm (see updateConfirmKeyState() and its click listener).
    }

    private void onBackspacePressed() {
        if (isVerifying || pinBuffer.length() == 0) return;
        pinBuffer.deleteCharAt(pinBuffer.length() - 1);
        pinDotsView.setFilledCount(pinBuffer.length());
        updateConfirmKeyState();
        tvError.setVisibility(View.INVISIBLE);
    }

    /**
     * keyConfirm is only enabled once PinManager.MIN_PIN_LEN digits are buffered —
     * matching the shortest PIN any account can have — so the tap is always a
     * meaningful, deliberate submit rather than a no-op on an empty/near-empty
     * buffer. Disabled state is visually muted (bg_numpad_confirm_disabled) so it
     * reads clearly as "not yet ready" next to the always-active backspace key.
     */
    private void updateConfirmKeyState() {
        boolean canSubmit = pinBuffer.length() >= PinManager.MIN_PIN_LEN;
        keyConfirm.setEnabled(canSubmit);
        keyConfirm.setBackgroundResource(
                canSubmit ? R.drawable.bg_numpad_backspace : R.drawable.bg_numpad_confirm_disabled);
        ivKeyConfirm.setAlpha(canSubmit ? 1f : 0.55f);
    }

    // ── Scan animation ──────────────────────────────────────────────────────

    private void showScanAnim() {
        ivLockShield.setVisibility(View.GONE);
        fingerprintScanView.setVisibility(View.VISIBLE);
    }

    private void hideScanAnim() {
        fingerprintScanView.setVisibility(View.GONE);
        ivLockShield.setVisibility(View.VISIBLE);
    }

    // ── PIN verification ──────────────────────────────────────────────────

    private void checkPin() {
        if (isVerifying) return;

        String entered = pinBuffer.toString();
        if (entered.isEmpty()) {
            tvError.setText("Please enter your PIN.");
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        // S06-L5: a persisted, exponential-backoff delay between attempts, so an
        // accidental keypad mash (child, pocket, curious colleague) cannot realistically
        // reach the secondary code's keyspace and trigger an unrecoverable duress wipe.
        // This only gates how soon the next attempt may be *submitted* — a deliberate,
        // correct entry is still accepted immediately whenever it happens.
        long remainingMs = PinManager.getLockoutRemainingMs(this);
        if (remainingMs > 0) {
            long secs = (remainingMs + 999) / 1000;
            tvError.setText("Too many attempts. Try again in " + secs + "s.");
            tvError.setVisibility(View.VISIBLE);
            pinBuffer.setLength(0);
            pinDotsView.setFilledCount(0);
            updateConfirmKeyState();
            return;
        }

        isVerifying = true;
        setInputEnabled(false);
        tvError.setText("Verifying…");
        tvError.setVisibility(View.VISIBLE);
        showScanAnim();

        new Thread(() -> {
            // Both hashes are always computed, regardless of outcome. A short-circuit
            // here (skip PinManager.verifyPin() once isDuressPin() matches) would make
            // matching the duress PIN measurably faster than matching the real PIN —
            // an observable timing side-channel that could tip off an attacker watching
            // for exactly this kind of asymmetry.
            boolean duress  = DuressManager.isDuressPin(this, entered);
            boolean real    = PinManager.verifyPin(this, entered);
            boolean correct = real;

            // Defense in depth: if the entered code somehow matches BOTH slots, treat
            // it as an ordinary unlock rather than a wipe. Every path that *sets* a
            // code now rejects a value matching the other slot (SetupPinActivity,
            // SecurityPrivacySettingsActivity.doSavePin, ManageUnlockCodesActivity),
            // so this should be unreachable — but one path cannot check:
            // PinManager.promoteDevicePinToCurrentUser() copies a stored salt:hash
            // into the account slot during creation/restore and never sees the
            // plaintext, so it cannot compare against a secondary code that survived
            // a previous duress wipe of the same account. If that collision ever does
            // occur, the old `real && !duress` meant the user's correct primary PIN
            // was classified as duress and wiped the device on a normal unlock, with
            // no way to recover the account. Failing toward "unlock" is strictly safer
            // than failing toward an irreversible wipe.
            boolean wipe = duress && !real;

            runOnUiThread(() -> {
                if (wipe) {
                    PinManager.clearFailedAttempts(this);
                    // The entered PIN is captured here — the plaintext never leaves this
                    // call stack — so DuressManager can promote it to the primary/device
                    // gate PIN before anything is wiped (S06-C1's promote-and-rotate).
                    DuressManager.performLogout(this, entered);
                    return;
                }

                isVerifying = false;
                setInputEnabled(true);
                tvError.setVisibility(View.INVISIBLE);
                hideScanAnim();

                if (correct) {
                    PinManager.clearFailedAttempts(this);
                    unlock();
                } else {
                    PinManager.recordFailedAttempt(this);
                    handleWrongPin();
                }
            });
        }).start();
    }

    private void handleWrongPin() {
        HapticHelper.wrongPin(this);
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        pinDotsView.startAnimation(shake);

        // Clear the buffer
        pinBuffer.setLength(0);
        pinDotsView.setFilledCount(0);
        updateConfirmKeyState();

        tvError.setText(getString(R.string.wrong_pin));
        tvError.setVisibility(View.VISIBLE);
    }

    private void setInputEnabled(boolean enabled) {
        btnUnlock.setEnabled(enabled);
        keyBackspace.setEnabled(enabled);
        // Verification is in flight (isVerifying) — keyConfirm must not be tappable
        // regardless of the buffer length, same as every other key here. Re-enabling
        // still defers to updateConfirmKeyState()'s MIN_PIN_LEN check, not to this flag.
        keyConfirm.setEnabled(enabled && pinBuffer.length() >= PinManager.MIN_PIN_LEN);
        for (int i = 0; i <= 9; i++) {
            if (digitKeys[i] != null) digitKeys[i].setEnabled(enabled);
        }
    }

    private void unlock() {
        BaseActivity.lockScreenActive = false;
        AppLockManager.onAppForegrounded(this);
        finish();
    }
}
