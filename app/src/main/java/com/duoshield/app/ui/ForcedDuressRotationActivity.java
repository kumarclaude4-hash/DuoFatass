package com.duoshield.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.BaseActivity;
import com.duoshield.app.BuildConfig;
import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.R;
import com.duoshield.app.SignInActivity;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.security.PendingLockStore;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.BackPressGuard;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen 2 of the forced post-unfreeze rotation (S06-M6, {@code
 * SESSION-06-DURESS.md §8}). Reached only from {@link ForcedPrimaryPinRotationActivity}
 * once the new primary PIN (P2) is set.
 *
 * <p>Forces a fresh secondary/duress code into slot B — never carries forward
 * whatever slot B held going into rotation, because a restore-from-seed can happen
 * on a fresh device where the real code was never stored locally, in which case
 * slot B holds nothing but a decoy at this point. Unlike {@link
 * ManageUnlockCodesActivity} (opt-in, gated behind server-side duress eligibility),
 * this screen is unconditional once {@code rotationRequired} is set: the account
 * only reaches this flow because slot B was already armed and actually used to
 * trigger a real wipe, so there is nothing to gate.
 *
 * <p>Reuses {@code ManageUnlockCodesActivity}'s confirm-current-PIN and
 * prefix-overlap-rejection pattern, but confirms against the PIN set moments ago on
 * screen 1 (P2) rather than an existing PIN carried across the Activity boundary —
 * no plaintext PIN is ever passed between screens.
 *
 * <p>On save, calls {@code POST /acknowledgeRotation} to clear the server-side flag.
 * If that call fails, both local codes stay set (nothing unsafe about retrying) and
 * the user is shown a retry panel rather than being sent back to either PIN screen —
 * see {@link #panelAckRetry}. No skip, no back-button escape.
 *
 * <p><b>Exception — account re-locked mid-flow.</b> The server's ack transaction
 * checks {@code accountLock.locked === true} ahead of every other branch (see
 * {@code server/lib/pure.js#decideRotationAcknowledgement}) and refuses with HTTP
 * 403 if the account was locked again after the unfreeze that started this chain.
 * Retrying can never succeed in that case, so rather than stranding the user on
 * {@link #panelAckRetry} forever, {@link #exitRotationDenied()} signs out through
 * {@link BaseActivity}'s own explicit-sign-out path and returns to
 * {@link SignInActivity} — the same generic outcome as any other sign-out, so this
 * screen still reveals nothing about lock state either way.
 */
public class ForcedDuressRotationActivity extends AppCompatActivity {

    // Matches BaseActivity's private PREFS_NAME literal — not exposed publicly,
    // so duplicated here rather than reached across the package.
    private static final String PREFS_NAME = "duoshield_prefs";

    private LinearLayout panelEntry, panelExplain, panelAckRetry;
    private EditText etNewCode, etConfirmCode;
    private TextView tvError;
    /**
     * Held as a field rather than re-resolved via {@code findViewById} inside the
     * async ack callbacks. Those callbacks run after a network round trip, by which
     * point this Activity may already have been destroyed (rotation, background
     * kill) — {@code findViewById} then returns null and dereferencing it threw an
     * NPE that presented as "the secondary PIN rotation crashes the app."
     */
    private MaterialButton btnRetryAck;

    private String pendingCode; // held between the entry panel and the explanation panel

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    /**
     * Hard "no offline use" wall for the post-unlock rotation flow.
     *
     * <p>This screen does not extend {@code BaseActivity}, so it never inherited the
     * gate. That mattered more here than on an ordinary screen: the whole point of
     * this flow is to replace the secondary code <em>and</em> tell the server it was
     * replaced. Only the local half of that can happen offline, and a local-only
     * rotation is the worst possible outcome — the user is shown a success path,
     * slot B is overwritten with the new code, and the server still believes the old
     * rotation is outstanding. The account then stays flagged as rotation-due
     * forever while the code needed to satisfy it no longer exists anywhere.</p>
     *
     * <p>Gating at {@code onStart()} rather than mid-submit is deliberate: it keeps
     * the irreversible local write from ever being reached without the server, so
     * there is no partial state to reconcile. The retry panel exists for a server
     * ack that failed <em>after</em> a successful connection, not for one that was
     * never possible.</p>
     */
    @Override
    protected void onStart() {
        super.onStart();
        if (com.duoshield.app.util.NetworkStateHelper.blockIfOffline(this)) {
            return;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackPressGuard.block(this); // locked out until the server confirms rotation
        setContentView(R.layout.activity_forced_duress_rotation);

        panelEntry    = findViewById(R.id.panelEntry);
        panelExplain  = findViewById(R.id.panelExplain);
        panelAckRetry = findViewById(R.id.panelAckRetry);
        etNewCode     = findViewById(R.id.etNewCode);
        etConfirmCode = findViewById(R.id.etConfirmCode);
        tvError       = findViewById(R.id.tvError);

        MaterialButton btnContinueEntry = findViewById(R.id.btnContinueEntry);
        MaterialButton btnConfirmExplain = findViewById(R.id.btnConfirmExplain);
        btnRetryAck = findViewById(R.id.btnRetryAck);

        btnContinueEntry.setOnClickListener(v -> onContinueEntry());
        btnConfirmExplain.setOnClickListener(v -> onConfirmExplain());
        btnRetryAck.setOnClickListener(v -> retryAcknowledge());

        // Offline gate registered below in onStart(); see its javadoc for why this
        // screen in particular must not be reachable without the server.

        // A relaunch mid-flow (app killed after slot B was armed but before the
        // server ack succeeded) lands straight back here via MainActivity.route().
        // Both local codes are already set at that point, so skip re-collecting
        // either PIN and go straight to retrying the one thing that didn't finish.
        //
        // Deliberately checks the dedicated rotation-scoped flag rather than
        // DuressManager.hasDuressPin() directly: slot B can already be armed on a
        // fresh arrival at this screen in the wipe scenario (duress logout
        // deliberately keeps the old code armed so a restore of the same account
        // stays gated), so "armed" alone can't distinguish "screen 2 hasn't run
        // yet" from "screen 2 already replaced it and is only waiting on the ack."
        //
        // Validated on entry rather than trusted outright. Two things must hold for
        // the resume path to make sense, and neither did on an install whose data was
        // never cleared:
        //
        //  * The rotation must still be due. session_rotation_* lives in the
        //    wipe-surviving session-state file, so before the companion fix in
        //    DuressManager.performLogout() these flags outlived the duress wipe and
        //    even a restore. A leftover duress_done from a previous cycle sent this
        //    screen straight into the ack-retry panel on a genuinely fresh arrival —
        //    no code was ever collected, pendingCode stayed null, and the flow was a
        //    dead end that then crashed in acknowledgeRotation().
        //  * There must be a signed-in session. acknowledgeRotationViaServer()
        //    throws outright without one, so retrying could only ever fail.
        //
        // A stale flag is self-healed back to the entry panel instead of being acted
        // on: screen 2's job is to arm a fresh code, and doing that again is always
        // safe.
        boolean rotationDue     = PendingLockStore.isRotationDue(this);
        boolean signedIn        = FirebaseAuth.getInstance().getCurrentUser() != null;
        boolean duressDoneFlag  = PendingLockStore.isRotationDuressDone(this);

        if (duressDoneFlag && rotationDue && signedIn) {
            showAckRetryPanel();
        } else if (duressDoneFlag) {
            android.util.Log.w("ForcedDuressRotation",
                    "onCreate: stale rotation progress flag (rotationDue=" + rotationDue
                            + " signedIn=" + signedIn + ") — restarting at code entry");
            PendingLockStore.setRotationDuressDone(this, false);
        }
    }

    private void onContinueEntry() {
        String code    = etNewCode.getText().toString().trim();
        String confirm = etConfirmCode.getText().toString().trim();

        // Bounds come from PinManager rather than being written out again here.
        // DuressManager.setDuressPin() enforces MIN_PIN_LEN/MAX_PIN_LEN and returns
        // false outside them; a hardcoded 4–6 here happens to agree today, but if
        // either constant ever moves this screen would accept a code the save then
        // silently rejects, surfacing as "Could not save. Try again." with no
        // indication of what is actually wrong.
        if (code.length() < PinManager.MIN_PIN_LEN || code.length() > PinManager.MAX_PIN_LEN) {
            Toast.makeText(this,
                    "Code must be " + PinManager.MIN_PIN_LEN + "–"
                            + PinManager.MAX_PIN_LEN + " digits.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!code.equals(confirm)) {
            Toast.makeText(this, "Codes don't match.", Toast.LENGTH_SHORT).show();
            return;
        }

        promptCurrentPinAndValidate(code);
    }

    /**
     * Re-authenticates against the primary PIN set on screen 1 (P2), then uses its
     * plaintext — held only for the duration of this method, never persisted or
     * logged — to reject any prefix overlap with {@code candidateCode}. Identical
     * reasoning to {@code ManageUnlockCodesActivity.promptCurrentPinAndValidate}.
     */
    private void promptCurrentPinAndValidate(String candidateCode) {
        EditText etCurrent = new EditText(this);
        etCurrent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etCurrent.setHint("Your new PIN");
        etCurrent.setMaxLines(1);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(etCurrent);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Confirm your new PIN to continue")
                .setView(container)
                .setCancelable(false) // mandatory screen — no dismiss-and-strand
                .setPositiveButton("Confirm", (d, w) -> {
                    String currentPin = etCurrent.getText().toString().trim();
                    bgExecutor.execute(() -> {
                        boolean authentic = PinManager.verifyPin(this, currentPin);
                        boolean overlaps = authentic && (
                                currentPin.equals(candidateCode)
                                        || currentPin.startsWith(candidateCode)
                                        || candidateCode.startsWith(currentPin));
                        runOnUiThread(() -> {
                            if (!authentic) {
                                Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show();
                            } else if (overlaps) {
                                Toast.makeText(this,
                                    "This code is already in use. Choose a different one.",
                                    Toast.LENGTH_LONG).show();
                            } else {
                                pendingCode = candidateCode;
                                showExplainPanel();
                            }
                        });
                    });
                })
                // No negative/cancel button — this screen is mandatory.
                .show();
    }

    private void showExplainPanel() {
        panelEntry.setVisibility(View.GONE);
        panelExplain.setVisibility(View.VISIBLE);
        panelAckRetry.setVisibility(View.GONE);
    }

    private void showAckRetryPanel() {
        panelEntry.setVisibility(View.GONE);
        panelExplain.setVisibility(View.GONE);
        panelAckRetry.setVisibility(View.VISIBLE);
    }

    private void onConfirmExplain() {
        if (pendingCode == null) return;
        String codeToSave = pendingCode;
        bgExecutor.execute(() -> {
            boolean saved = DuressManager.setDuressPin(this, codeToSave);
            runOnUiThread(() -> {
                // setDuressPin() runs PBKDF2, so this callback can land well after
                // the user backgrounded the screen. If the Activity is gone the
                // panel fields are unsafe to touch; the durable flag below is what
                // the resume path reads, so state stays correct either way.
                if (isFinishing() || isDestroyed()) {
                    if (saved) PendingLockStore.setRotationDuressDone(this, true);
                    return;
                }
                if (!saved) {
                    Toast.makeText(this, "Could not save. Try again.", Toast.LENGTH_LONG).show();
                    panelExplain.setVisibility(View.GONE);
                    panelEntry.setVisibility(View.VISIBLE);
                    return;
                }
                pendingCode = null;
                // Slot B is armed on this device now — mark screen 2 as genuinely
                // done (see the onCreate resume check above for why this can't be
                // inferred from DuressManager.hasDuressPin() alone) and retry the
                // server ack from here rather than re-collecting either PIN if it
                // fails.
                PendingLockStore.setRotationDuressDone(this, true);
                showAckRetryPanel();
                acknowledgeRotation();
            });
        });
    }

    private void retryAcknowledge() {
        acknowledgeRotation();
    }

    private void acknowledgeRotation() {
        if (tvError != null) tvError.setVisibility(View.GONE);
        if (btnRetryAck != null) btnRetryAck.setEnabled(false);
        bgExecutor.execute(() -> {
            Exception failure = null;
            try {
                acknowledgeRotationViaServer();
            } catch (Exception e) {
                failure = e;
            }
            final Exception finalFailure = failure;
            runOnUiThread(() -> {
                // This callback lands after a network round trip of up to 45s
                // (15s connect + 30s read), so the Activity may well be gone by
                // now. Touching views or starting an Activity from a destroyed
                // instance is what turned an ordinary ack failure into a crash.
                // The durable state that matters was already committed before the
                // call, so bailing out here loses nothing — the next launch
                // resumes via MainActivity.route().
                if (isFinishing() || isDestroyed()) {
                    android.util.Log.i("ForcedDuressRotation",
                            "acknowledgeRotation: activity gone before result — "
                                    + "will resume on next launch");
                    return;
                }
                if (btnRetryAck != null) btnRetryAck.setEnabled(true);
                if (finalFailure instanceof RotationDeniedException) {
                    // Retrying can never succeed — the server's own gate already
                    // said the account is locked right now. Exit instead of
                    // looping the retry button forever; see exitRotationDenied().
                    android.util.Log.w("ForcedDuressRotation",
                            "acknowledgeRotation: account re-locked mid-flow — exiting to sign-in ("
                                    + com.duoshield.app.util.LogRedact.error(finalFailure) + ")");
                    exitRotationDenied();
                    return;
                }
                if (finalFailure != null) {
                    android.util.Log.w("ForcedDuressRotation", "acknowledgeRotation failed (" + com.duoshield.app.util.LogRedact.error(finalFailure) + ")");
                    showError("Couldn't confirm with the server. Check your connection and try again.");
                    return;
                }

                // Only now — server confirmed — clear the rotation intent entirely.
                // Both PINs are already durably saved locally; this just lifts the
                // routing block that was keeping the user on these two screens.
                PendingLockStore.clearRotationDue(this);

                boolean unpaired = PendingLockStore.isRotationUnpaired(this);
                Class<?> dest = unpaired ? AddContactActivity.class : ConversationListActivity.class;
                Intent intent = new Intent(this, dest);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        });
    }

    /**
     * Modeled on {@code RestoreFromSeedActivity.migrateOldUidViaServer()}: Bearer
     * Firebase ID token, {@code POST /acknowledgeRotation}, JSON body built via
     * {@code JSONObject} rather than string concatenation.
     */
    private void acknowledgeRotationViaServer() throws Exception {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            throw new IllegalStateException("Rotation acknowledgement requires an authenticated session.");
        }
        final String[]    tokenHolder = {null};
        final Exception[] tokenErr    = {null};
        final Object      tokenLock   = new Object();
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
            throw new Exception("Could not authenticate rotation acknowledgement.", tokenErr[0]);
        }
        String idToken = tokenHolder[0];
        if (idToken == null) {
            throw new Exception("Could not authenticate rotation acknowledgement.");
        }

        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        java.net.URL url = new java.net.URL(serverUrl + "/acknowledgeRotation");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + idToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);

        JSONObject reqBodyObj = new JSONObject();
        reqBodyObj.put("userId", user.getUid());
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
        android.util.Log.d("ForcedDuressRotation", "acknowledgeRotation server response: HTTP " + code);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            // 403 from THIS endpoint is unambiguous: the token-UID-mismatch branch
            // (also a 403) can never fire here because userId is always
            // user.getUid() from the very token being sent, so a 403 can only be
            // the transaction's `relocked` branch — see decideRotationAcknowledgement
            // in server/lib/pure.js. Every other non-2xx (401/429/500, or an
            // IOException from the network layer before a response code even
            // exists) stays a plain IOException so the retry panel's existing
            // "check your connection" copy is unchanged for those.
            if (code == 403) {
                throw new RotationDeniedException(
                        "Rotation acknowledgement refused (HTTP 403): " + response);
            }
            throw new java.io.IOException("Rotation acknowledgement failed (HTTP " + code + "): " + response);
        }
    }

    /**
     * Thrown only for the HTTP 403 the server returns when {@code accountLock.locked}
     * is {@code true} at ack time — i.e. the account was locked again after the
     * unfreeze that started this rotation chain. Distinguished from every other
     * failure (network blip, 401, 429, 500) so the UI can recognise "retrying can
     * never succeed" and stop offering a retry, instead of looping the user forever
     * on {@link #panelAckRetry}.
     */
    private static final class RotationDeniedException extends java.io.IOException {
        RotationDeniedException(String message) {
            super(message);
        }
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    /**
     * The account was re-locked between the unfreeze that put this device on the
     * rotation chain and this ack attempt (server-confirmed via HTTP 403 — see
     * {@link RotationDeniedException}). Retrying here can never succeed: the
     * server's own gate checks {@code locked === true} ahead of every other
     * branch, so no ack will clear until another unfreeze happens, and this
     * device has no way to observe that on its own.
     *
     * <p>Rather than stranding the user in a retry loop that can never succeed,
     * sign out through the exact path {@link BaseActivity} already uses for its
     * own auto-sign-out — set {@link BaseActivity#KEY_EXPLICIT_SIGNOUT} first, so
     * any later session check reads this as an intentional sign-out rather than a
     * transient null user — and land on {@link SignInActivity}. This shows no new
     * copy and reveals nothing: it is indistinguishable from any other sign-out,
     * which is the point. The lock state itself must not leak through this
     * screen; it is only ever discoverable through the operator's own unfreeze
     * action, exactly as before.
     *
     * <p>Deliberately does NOT clear {@code PendingLockStore}'s rotation flags.
     * Both PINs are already durably saved from this session; if the account is
     * unfrozen again later, the server sets {@code rotationRequired} fresh
     * regardless, so a stale local flag costs nothing, and
     * {@code MainActivity.route()} never consults it without a signed-in session.
     */
    private void exitRotationDenied() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(BaseActivity.KEY_EXPLICIT_SIGNOUT, true)
                .apply();
        try {
            FirebaseAuth.getInstance().signOut();
        } catch (Exception ignored) {
            // Best-effort — the account is being abandoned either way.
        }
        Intent intent = new Intent(this, SignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdownNow();
    }
}
