package com.duoshield.app.crypto.signal;

import com.duoshield.app.util.LogRedact;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;


import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.SessionBuilder;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Step 3 — X3DH Session Initiation.
 *
 * <h3>What happens inside {@link #establishSession}:</h3>
 * <ol>
 *   <li>Request the recipient's public key bundle from the authenticated server API.</li>
 *   <li>The server atomically claims one one-time pre-key and returns it with the bundle.</li>
 *   <li>Construct a {@link PreKeyBundle} and call
 *       {@link SessionBuilder#process(PreKeyBundle)} — this runs the full X3DH
 *       handshake and stores the resulting Double Ratchet session in Room DB via
 *       {@link DuoShieldSignalStore}.</li>

 * </ol>
 *
 * After this call succeeds, {@code DuoShieldSignalStore} holds a live
 * {@code SessionRecord} for the recipient. Pass that store + the recipient's
 * {@link SignalProtocolAddress} into {@code SessionCipher} to encrypt/decrypt
 * messages (Step 4 / 5).
 *
 * <h3>Thread safety</h3>
 * Firestore callbacks come on the main thread; the X3DH computation and Room
 * write are dispatched to a dedicated single-thread executor so they never
 * block the UI.
 */
public final class SignalSessionManager {

    private static final String TAG      = "SignalSessionManager";
    /** Device ID is always 1 in DuoShield (single device per user). */
    public static final  int    DEVICE_ID = 1;

    private static final Executor executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "signal-session"));

    private SignalSessionManager() {}

    // ── Public callback interface ─────────────────────────────────────────────

    public interface SessionCallback {
        /**
         * Called on the main thread when the session is established and ready.
         * @param address The remote address — pass this to {@code SessionCipher}.
         * @param store   The store holding the fresh session — pass this to {@code SessionCipher}.
         */
        void onEstablished(SignalProtocolAddress address, DuoShieldSignalStore store);

        /**
         * Called on the main thread if anything goes wrong.
         * @param reason Human-readable description of the failure.
         */
        void onError(String reason);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Establishes (or refreshes) a Signal Protocol session with {@code recipientUid}.
     *
     * <p>If a session already exists in Room DB it is returned immediately without
     * hitting Firestore — the Double Ratchet handles key derivation per-message from
     * that point on.
     *
     * <p>If no session exists, the full X3DH handshake is performed:
     * claim bundle + OTP key server-side → build PreKeyBundle → SessionBuilder.process().
     *
     * @param ctx          Application or activity context.
     * @param recipientUid Firebase Auth UID of the message recipient.
     * @param callback     Delivered on the main thread.
     */
    public static void establishSession(Context ctx,
                                        String recipientUid,
                                        SessionCallback callback) {
        if (recipientUid == null || recipientUid.isEmpty()) {
            deliver(callback, null, null, "recipientUid must not be null or empty");
            return;
        }

        SignalProtocolAddress address =
                new SignalProtocolAddress(recipientUid, DEVICE_ID);
        DuoShieldSignalStore store =
                DuoShieldSignalStore.getInstance(ctx);

        // Fast path: session already established — no Firestore round-trip needed.
        executor.execute(() -> {
            if (store.containsSession(address)) {
                Log.d(TAG, "Session already exists for " + recipientUid + " — reusing.");
                deliver(callback, address, store, null);
                return;
            }
            // Slow path: fetch public key bundle and run X3DH.
            fetchBundleAndRunX3DH(ctx, recipientUid, address, store, callback);
        });
    }

    // ── Private: Firestore fetch ───────────────────────────────────────────────

    private static void fetchBundleAndRunX3DH(Context ctx,
                                               String recipientUid,
                                               SignalProtocolAddress address,
                                               DuoShieldSignalStore store,
                                               SessionCallback callback) {
        executor.execute(() -> {
            try {
                // The server atomically claims exactly one one-time pre-key before
                // returning the public bundle. No client can submit an array rewrite.
                Map<String, Object> bundle = SignalKeyApi.claimPreKey(recipientUid);
                runX3DH(ctx, recipientUid, address, store, bundle, callback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to claim Signal pre-key for " + LogRedact.uid(recipientUid), e);
                deliver(callback, null, null, "Could not fetch " + recipientUid
                        + "'s public keys: " + e.getMessage());
            }
        });
    }

    // ── Private: X3DH handshake ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void runX3DH(Context ctx,
                                 String recipientUid,
                                 SignalProtocolAddress address,
                                 DuoShieldSignalStore store,
                                 Map<String, Object> doc,
                                 SessionCallback callback) {
        if (doc == null || doc.isEmpty()) {
            deliver(callback, null, null,
                    recipientUid + " has not uploaded Signal keys yet — ask them to re-open the app.");
            return;
        }

        try {
            // ── 1. Parse identity key ─────────────────────────────────────────
            String identityKeyB64 = (String) doc.get("identityKey");
            if (identityKeyB64 == null)
                throw new IllegalArgumentException("identityKey missing from bundle");
            IdentityKey identityKey = new IdentityKey(
                    Base64.decode(identityKeyB64, Base64.NO_WRAP), 0);

            int recipientRegId = toInt(doc.get("registrationId"), 0);

            // ── 2. Parse signed pre-key ───────────────────────────────────────
            Map<String, Object> spkMap = (Map<String, Object>) doc.get("signedPreKey");
            if (spkMap == null)
                throw new IllegalArgumentException("signedPreKey missing from bundle");
            int         spkId  = toInt(spkMap.get("id"), 0);
            ECPublicKey spkPub = Curve.decodePoint(
                    Base64.decode((String) spkMap.get("publicKey"), Base64.NO_WRAP), 0);
            byte[] spkSig = Base64.decode((String) spkMap.get("signature"), Base64.NO_WRAP);

            // ── 3. Pick one one-time pre-key (optional but strongly recommended) ─
            List<Map<String, Object>> otpks = new ArrayList<>();
            Object oneTimePreKeys = doc.get("oneTimePreKeys");
            if (oneTimePreKeys instanceof List) {
                for (Object entry : (List<?>) oneTimePreKeys) {
                    if (entry instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> publicKeyEntry = (Map<String, Object>) entry;
                        otpks.add(publicKeyEntry);
                    }
                }
            }
            int                 otpkId    = -1;
            ECPublicKey         otpkPub   = null;

            if (otpks != null && !otpks.isEmpty()) {
                Map<String, Object> chosen = otpks.get(0); // server returns at most one claimed key
                otpkId  = toInt(chosen.get("id"), -1);
                String pubB64 = (String) chosen.get("publicKey");
                if (otpkId >= 0 && pubB64 != null) {
                    otpkPub = Curve.decodePoint(
                            Base64.decode(pubB64, Base64.NO_WRAP), 0);
                }
            }

            // ── 4. Parse Kyber last-resort pre-key (PQXDH), if present ──────────
            //
            // Added when both parties run DuoShield ≥ 1.5 (PQXDH support).
            // If absent (old bundle), fall back to classic X3DH — backward compatible.
            int         kyberPreKeyId  = -1;
            KEMPublicKey kyberPreKey   = null;
            byte[]       kyberPreKeySig = null;

            @SuppressWarnings("unchecked")
            Map<String, Object> kpkMap = (Map<String, Object>) doc.get("kyberPreKey");
            if (kpkMap != null) {
                try {
                    kyberPreKeyId   = toInt(kpkMap.get("id"), -1);
                    String kpkPubB64 = (String) kpkMap.get("publicKey");
                    String kpkSigB64 = (String) kpkMap.get("signature");
                    if (kyberPreKeyId >= 0 && kpkPubB64 != null && kpkSigB64 != null) {
                        kyberPreKey    = new KEMPublicKey(Base64.decode(kpkPubB64, Base64.NO_WRAP));
                        kyberPreKeySig = Base64.decode(kpkSigB64, Base64.NO_WRAP);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Kyber pre-key parse failed for " + LogRedact.uid(recipientUid)
                            + " — falling back to X3DH only.", e);
                    kyberPreKeyId = -1;
                    kyberPreKey   = null;
                    kyberPreKeySig = null;
                }
            }

            // ── 5. Build PreKeyBundle and run X3DH (or PQXDH) ───────────────
            //
            // PQXDH (with Kyber): adds post-quantum forward secrecy on top of X3DH.
            // Classic X3DH (no Kyber): used when partner has an older bundle.
            //
            // Pass preKeyId=0 / preKey=null when no one-time pre-key is available
            // (X3DH still works but forward secrecy is slightly reduced for this session).
            if (otpkPub == null) {
                Log.w(TAG, "No one-time pre-keys available for " + LogRedact.uid(recipientUid)
                        + " — session established without OTP key. Forward secrecy reduced for this session."
                        + " Partner should re-open the app to replenish their pre-key pool.");
            }

            PreKeyBundle bundle;
            if (kyberPreKey != null) {
                // PQXDH: X3DH + Kyber-1024 last-resort pre-key.
                bundle = new PreKeyBundle(
                        recipientRegId,
                        DEVICE_ID,
                        otpkId,  otpkPub,
                        spkId,   spkPub, spkSig,
                        identityKey,
                        kyberPreKeyId, kyberPreKey, kyberPreKeySig);
                Log.d(TAG, "PQXDH session initiated for " + recipientUid
                        + " (Kyber pre-key id=" + kyberPreKeyId + ")");
            } else {
                // Classic X3DH — partner pre-dates PQXDH support.
                bundle = new PreKeyBundle(
                        recipientRegId,
                        DEVICE_ID,
                        otpkId,  otpkPub,
                        spkId,   spkPub, spkSig,
                        identityKey);
                Log.d(TAG, "X3DH session initiated for " + recipientUid
                        + " (no Kyber pre-key — PQXDH unavailable for this peer)");
            }

            // ── Corrupt-Kyber guard ────────────────────────────────────────────
            //
            // If PQXDH bundle construction succeeded but SessionBuilder.process()
            // throws (e.g. malformed Kyber ciphertext, wrong key length after a
            // partial Firestore write), fall back to classic X3DH automatically
            // so a corrupt Kyber key never silently kills a new chat session.
            //
            // The fallback is logged as a warning; both sides will still negotiate
            // a fully-authenticated Double Ratchet session — just without the
            // post-quantum layer until the partner's Kyber key is rotated/repaired.
            try {
                new SessionBuilder(store, address).process(bundle);
            } catch (Exception pqxdhEx) {
                if (kyberPreKey != null) {
                    Log.w(TAG, "PQXDH SessionBuilder.process() failed for " + LogRedact.uid(recipientUid)
                            + " — partner's Kyber key may be corrupt; retrying with classic X3DH.",
                            pqxdhEx);
                    // Delete any partial session state written by the failed PQXDH attempt
                    // before retrying with the X3DH bundle. libsignal does not guarantee
                    // atomic rollback when process() throws mid-write, so an explicit
                    // deleteSession() here prevents a corrupted session record from
                    // blocking future decryption attempts on this address.
                    try { store.deleteSession(address); }
                    catch (Exception deleteEx) {
                        Log.w(TAG, "Could not delete partial session before X3DH fallback", deleteEx);
                    }
                    PreKeyBundle x3dhFallback = new PreKeyBundle(
                            recipientRegId,
                            DEVICE_ID,
                            otpkId,  otpkPub,
                            spkId,   spkPub, spkSig,
                            identityKey);
                    // Let this throw if the classic X3DH bundle also fails — that
                    // is a real key problem unrelated to Kyber (caught below).
                    new SessionBuilder(store, address).process(x3dhFallback);
                    Log.d(TAG, "X3DH fallback succeeded for " + recipientUid
                            + " (PQXDH disabled until partner rotates Kyber key)");
                } else {
                    throw pqxdhEx; // non-Kyber failure — re-throw for the outer catch
                }
            }
            // SessionBuilder.process() calls store.storeSession() internally,
            // persisting the Double Ratchet state to Room DB.

            Log.d(TAG, "X3DH complete for " + recipientUid
                    + (otpkPub != null ? " (OTP key #" + otpkId + " consumed)" : " (no OTP key)"));

            // The server atomically claimed (and removed) the selected OTP key
            // before it returned this bundle. If a local handshake subsequently
            // fails, the key is intentionally burned rather than made reusable.
            if (otpkId >= 0 && otpks != null && !otpks.isEmpty()) {
                Log.d(TAG, "A single-use OTP key was claimed for " + LogRedact.uid(recipientUid));
            }

            deliver(callback, address, store, null);

        } catch (InvalidKeyException | UntrustedIdentityException e) {
            Log.e(TAG, "X3DH failed for " + LogRedact.uid(recipientUid), e);
            deliver(callback, null, null, "Session establishment failed: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during X3DH for " + LogRedact.uid(recipientUid), e);
            deliver(callback, null, null, "Unexpected error: " + e.getMessage());
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private static void deliver(SessionCallback callback,
                                 SignalProtocolAddress address,
                                 DuoShieldSignalStore store,
                                 String errorMsg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (errorMsg != null) {
                callback.onError(errorMsg);
            } else {
                callback.onEstablished(address, store);
            }
        });
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); }
            catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
