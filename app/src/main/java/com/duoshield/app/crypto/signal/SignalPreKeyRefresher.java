package com.duoshield.app.crypto.signal;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.state.PreKeyRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic one-time pre-key replenishment for the Signal Protocol pool.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Each inbound {@code PreKeySignalMessage} causes libsignal to call
 *       {@link DuoShieldSignalStore#removePreKey}, which calls
 *       {@link SignalKeyManager#consumePreKey} and then
 *       {@link #checkAndReplenish}.</li>
 *   <li>If the local pool has fewer than {@value #THRESHOLD} keys, a background
 *       thread generates {@value #BATCH_SIZE} new Curve25519 key pairs.</li>
 *   <li>Private bytes are stored in {@link com.duoshield.app.util.SecurePrefs}
 *       via {@link SignalKeyManager#storeNewPreKeys}.</li>
 *   <li>Public bytes are appended through the authenticated server API, which
 *       validates uniqueness and performs a transaction so existing keys are not displaced.</li>
 * </ol>
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li>Only one replenishment runs at a time (guarded by {@link AtomicBoolean}).</li>
 *   <li>Pre-key IDs are monotonically increasing and stored in
 *       {@link SignalKeyManager#KEY_PREKEY_NEXT_ID} to prevent reuse.</li>
 *   <li>Server upload failure is logged. The private keys remain stored locally;
 *       a later replenishment attempt can retry with newly generated keys.</li>
 * </ul>
 */
public final class SignalPreKeyRefresher {

    private static final String TAG = "SignalPreKeyRefresher";

    /** Trigger replenishment when fewer than this many keys remain locally. */
    static final int THRESHOLD  = 10;
    /** Number of new keys to generate per replenishment cycle. */
    static final int BATCH_SIZE = 25;

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);

    private SignalPreKeyRefresher() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Checks the local pre-key pool count and, if below {@value #THRESHOLD},
     * kicks off an asynchronous replenishment. This method is non-blocking and
     * safe to call from any thread including the UI thread.
     *
     * <p>Idempotent — a second concurrent call while replenishment is already
     * running is silently ignored.
     *
     * @param ctx Application or activity context.
     */
    public static void checkAndReplenish(Context ctx) {
        if (!SignalKeyManager.isInitialized(ctx)) return;

        int count = SignalKeyManager.getPreKeyCount(ctx);
        if (count >= THRESHOLD) return;

        if (!sRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Replenishment already in progress — skipping.");
            return;
        }

        Log.d(TAG, "Pre-key pool low (" + count + " remaining) — replenishing "
                + BATCH_SIZE + " keys.");

        new Thread(() -> {
            try {
                replenish(ctx);
            } catch (Exception e) {
                Log.e(TAG, "Pre-key replenishment failed", e);
            } finally {
                sRunning.set(false);
            }
        }, "signal-prekey-replenish").start();
    }

    // ── Private: replenishment ────────────────────────────────────────────────

    private static void replenish(Context ctx) throws Exception {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "Cannot replenish — no signed-in user.");
            return;
        }

        // ── 1. Generate fresh Curve25519 key pairs ────────────────────────────
        int startId = SignalKeyManager.getAndIncrementNextPreKeyId(ctx, BATCH_SIZE);
        List<PreKeyRecord> newKeys = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            ECKeyPair kp = Curve.generateKeyPair();
            newKeys.add(new PreKeyRecord(startId + i, kp));
        }

        // ── 2. Persist private bytes + update IDs CSV in SecurePrefs ─────────
        SignalKeyManager.storeNewPreKeys(ctx, newKeys);
        Log.d(TAG, "Stored " + BATCH_SIZE + " new pre-keys locally (IDs "
                + startId + " – " + (startId + BATCH_SIZE - 1) + ").");

        // ── 3. Build public-key entries for Firestore ─────────────────────────
        List<Map<String, Object>> publicEntries = new ArrayList<>(BATCH_SIZE);
        for (PreKeyRecord pk : newKeys) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", pk.getId());
            entry.put("publicKey", Base64.encodeToString(
                    pk.getKeyPair().getPublicKey().serialize(), Base64.NO_WRAP));
            publicEntries.add(entry);
        }

        // ── 4. Append through the authenticated, transaction-backed server API ──
        // Private halves remain in SecurePrefs; only bounded public entries are sent.
        SignalKeyApi.appendPreKeys(publicEntries);
        Log.d(TAG, "Uploaded " + BATCH_SIZE + " pre-keys through the server API (IDs "
                + startId + " – " + (startId + BATCH_SIZE - 1) + ").");
    }
}
