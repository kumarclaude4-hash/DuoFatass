package com.duoshield.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.security.PendingLockStore;
import com.duoshield.app.util.ButtonPressAnimator;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.BackPressGuard;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen 1 of the forced post-unfreeze rotation (S06-M6, {@code SESSION-06-DURESS.md
 * §8} — the promote-and-rotate design). Reached only from {@link
 * RestoreFromSeedActivity}, when the account's {@code accountLock/{uid}} doc has
 * {@code rotationRequired: true} — set by the admin unfreeze handler after a duress
 * wipe locked the account, and only cleared by {@code POST /acknowledgeRotation}
 * once both this screen and {@link ForcedDuressRotationActivity} complete.
 *
 * <p>Forces a brand-new primary PIN (P2). Deliberately does not carry the account's
 * existing PIN forward — the whole point of the rotation is that whatever PIN was in
 * use before the wipe is no longer trusted. No skip, no back-button escape: see
 * {@link #onBackPressed()}.
 *
 * <p>On success, routes to {@link ForcedDuressRotationActivity} to re-arm slot B —
 * never directly to the app. The account is not usable again until both screens
 * finish and the server ack succeeds.
 */
public class ForcedPrimaryPinRotationActivity extends AppCompatActivity {

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackPressGuard.block(this); // locked out until rotation completes
        setContentView(R.layout.activity_forced_primary_pin_rotation);

        EditText etNewPin     = findViewById(R.id.etNewPin);
        EditText etConfirmPin = findViewById(R.id.etConfirmPin);
        TextView tvError      = findViewById(R.id.tvError);
        MaterialButton btnContinue = findViewById(R.id.btnContinue);
        ButtonPressAnimator.attach(btnContinue);

        btnContinue.setOnClickListener(v -> {
            String pin     = etNewPin.getText() != null ? etNewPin.getText().toString() : "";
            String confirm = etConfirmPin.getText() != null ? etConfirmPin.getText().toString() : "";

            // Bounds come from PinManager rather than being restated here — the same
            // reasoning as the matching check on screen 2. PinManager.setPin() is the
            // thing that actually enforces MIN_PIN_LEN/MAX_PIN_LEN, so the UI must not
            // keep its own copy of those numbers and drift from it.
            if (pin.length() < PinManager.MIN_PIN_LEN || pin.length() > PinManager.MAX_PIN_LEN) {
                showError(tvError, "PIN must be " + PinManager.MIN_PIN_LEN + "–"
                        + PinManager.MAX_PIN_LEN + " digits.");
                return;
            }
            if (!pin.equals(confirm)) {
                showError(tvError, "PINs don't match. Try again.");
                etConfirmPin.setText("");
                return;
            }

            tvError.setVisibility(View.GONE);
            btnContinue.setEnabled(false);

            // Off the main thread — see SetupPinActivity for why: setPin() runs
            // 310,000 PBKDF2 iterations, plus the clash check below runs another.
            bgExecutor.execute(() -> {
                // Same clash guard as SetupPinActivity and
                // SecurityPrivacySettingsActivity.doSavePin(): reject a new primary
                // PIN that equals or is a prefix of whatever slot B currently holds.
                // Slot B at this point is whatever survived the wipe/restore (the
                // real armed code, since duress logout deliberately keeps it so a
                // restore of the same account stays gated) — screen 2 replaces it
                // with a fresh code, but that hasn't happened yet, so the check must
                // run against what's there right now.
                // The old secondary-code hash survives the duress wipe until screen 2
                // replaces it. Reuse the same account-scoped candidate budget as Settings
                // and SetupPin before inspecting that hidden value.
                boolean attemptAllowed = PinManager.tryRecordPinChangeAttempt(this);
                boolean clashWithSecondary = attemptAllowed
                        && DuressManager.isDuressPinOrPrefixOfIt(this, pin);
                boolean stored = attemptAllowed && !clashWithSecondary
                        && PinManager.setPin(this, pin);

                runOnUiThread(() -> {
                    btnContinue.setEnabled(true);

                    if (!stored) {
                        showError(tvError, "Couldn't save your PIN. Try again later.");
                        etConfirmPin.setText("");
                        return;
                    }

                    // Screen 1 is done — mark it so a relaunch mid-flow resumes at
                    // screen 2 instead of re-prompting for a primary PIN that was
                    // already set. Cleared, along with the overall rotation flag,
                    // only once the server ack (/acknowledgeRotation) succeeds.
                    PendingLockStore.setRotationPrimaryDone(this, true);

                    Intent intent = new Intent(this, ForcedDuressRotationActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });
            });
        });
    }

    private void showError(TextView tvError, String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdownNow();
    }
}
