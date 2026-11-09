package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.R;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.ui.AddContactActivity;
import com.duoshield.app.util.ButtonPressAnimator;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.BackPressGuard;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mandatory PIN setup for accounts that don't have a PIN yet. Shown either
 * right after {@link SeedPhraseDisplayActivity} finishes key setup for a
 * brand-new account, or from {@link RestoreFromSeedActivity} when a restore
 * lands on a device that was exempted from the upfront device-PIN gate but
 * never actually had a device PIN promoted (see the comment at that call
 * site — {@code PinManager.looksLikePreExistingDevice()} can exempt a device
 * with no device PIN on file).
 *
 * <p>Every account must have a PIN — it's no longer something the user
 * can skip and configure later from Settings. Existing installs that already
 * skipped PIN setup before this screen existed are untouched; this only
 * applies to the new-account-creation and restore paths.</p>
 */
public class SetupPinActivity extends AppCompatActivity {

    /** Forwarded through unchanged to ConversationListActivity. */
    public static final String EXTRA_ACCOUNT_CREATED = SeedPhraseDisplayActivity.EXTRA_ACCOUNT_CREATED;

    /**
     * Set by {@link RestoreFromSeedActivity} when the restored account has no
     * conversation/partner yet, so completion should land on
     * {@link AddContactActivity} instead of {@link ConversationListActivity}.
     * Absent (or false) for the new-account-creation path, which always has
     * ConversationListActivity as its destination.
     */
    public static final String EXTRA_UNPAIRED = "setup_pin_unpaired";

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackPressGuard.block(this); // a new account must leave this screen with a PIN set
        setContentView(R.layout.activity_setup_pin);

        EditText etNewPin     = findViewById(R.id.etNewPin);
        EditText etConfirmPin = findViewById(R.id.etConfirmPin);
        TextView tvError      = findViewById(R.id.tvError);
        MaterialButton btnContinue = findViewById(R.id.btnContinue);
        ButtonPressAnimator.attach(btnContinue);

        btnContinue.setOnClickListener(v -> {
            String pin     = etNewPin.getText() != null ? etNewPin.getText().toString() : "";
            String confirm = etConfirmPin.getText() != null ? etConfirmPin.getText().toString() : "";

            if (pin.length() < 4 || pin.length() > 6) {
                showError(tvError, "PIN must be 4–6 digits.");
                return;
            }
            if (!pin.equals(confirm)) {
                showError(tvError, "PINs don't match. Try again.");
                etConfirmPin.setText("");
                return;
            }

            tvError.setVisibility(View.GONE);
            btnContinue.setEnabled(false);

            // Off the main thread: setPin() runs 310,000 PBKDF2 iterations, and on a
            // device that already has a device-gate PIN it runs two more derivations
            // (setDevicePin + ensureSecondarySlotInitialized). That is comfortably
            // long enough to block the UI thread visibly, and it was previously all
            // running inline in this click handler.
            bgExecutor.execute(() -> {
                // A restore can leave a still-armed secondary code from before the
                // wipe (duress logout deliberately keeps the hash so a restore of the
                // same account stays gated). Setting a primary PIN equal to that code
                // — or one that starts with it — would make an ordinary unlock trigger
                // the wipe branch, with no way to ever unlock the account. Reject both
                // here — the same check SecurityPrivacySettingsActivity.doSavePin()
                // performs. See DuressManager.isDuressPinOrPrefixOfIt for the residual
                // direction this does not cover (this pin being a short prefix of a
                // longer secondary code) and why.
                // Setup may follow a restore that preserved the old armed secondary
                // hash. Consume the same account-scoped candidate budget used by
                // Settings before inspecting that hidden value. Apply it regardless
                // of whether slot B is armed so the throttle itself does not reveal
                // whether a secondary code exists.
                boolean attemptAllowed = PinManager.tryRecordPinChangeAttempt(this);
                boolean clashWithSecondary = attemptAllowed
                        && DuressManager.isDuressPinOrPrefixOfIt(this, pin);
                boolean stored = attemptAllowed && !clashWithSecondary
                        && PinManager.setPin(this, pin);

                runOnUiThread(() -> {
                    btnContinue.setEnabled(true);

                    if (!stored) {
                        // This also covers throttle denial and a hidden secondary-code
                        // collision. Keep all rejected candidate states indistinguishable;
                        // only a successful durable PIN write may complete setup.
                        showError(tvError, "Couldn't save your PIN. Try again later.");
                        etConfirmPin.setText("");
                        return;
                    }

                    // Setup is genuinely complete — only now clear the "stuck
                    // mid-flow" marker so a future launch routes straight to
                    // ConversationListActivity instead of bouncing back here. See
                    // SeedPhraseDisplayActivity for where this flag is set and
                    // SignInActivity.route() for where it's read.
                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    if (user != null) {
                        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
                        prefs.edit().remove("pending_pin_setup_" + user.getUid()).apply();
                    }

                    boolean unpaired = getIntent().getBooleanExtra(EXTRA_UNPAIRED, false);
                    Class<?> dest = unpaired ? AddContactActivity.class : ConversationListActivity.class;
                    Intent intent = new Intent(this, dest);
                    intent.putExtra(EXTRA_ACCOUNT_CREATED, getIntent().getBooleanExtra(EXTRA_ACCOUNT_CREATED, false));
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
