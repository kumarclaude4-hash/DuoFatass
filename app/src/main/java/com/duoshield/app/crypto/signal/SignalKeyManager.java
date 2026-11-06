package com.duoshield.app.crypto.signal;

import com.duoshield.app.util.LogRedact;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.duoshield.app.util.SecurePrefs;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Signal Protocol key management — Steps 1 & 2 of the full Signal integration.
 *
 * <h3>Four key types managed here:</h3>
 * <ol>
 *   <li><b>Identity Key Pair</b> — long-lived Curve25519 device identity. Never rotated.</li>
 *   <li><b>Registration ID</b>   — unique random integer (1–16 382) identifying this device.</li>
 *   <li><b>Signed Pre-Key</b>    — medium-term Curve25519 key signed by the Identity Key.
 *                                  Should be rotated periodically (e.g. weekly).</li>
 *   <li><b>One-Time Pre-Keys</b> — batch of 50 Curve25519 keys; each is consumed once
 *                                  during X3DH session establishment and then discarded.</li>
 * </ol>
 *
 * <h3>Storage contract:</h3>
 * <ul>
 *   <li><b>Private key material</b> → {@link SecurePrefs} (EncryptedSharedPreferences).
 *       Stays on-device. Never serialised over the network.</li>
 *   <li><b>Public key material</b>  → Firestore at
 *       {@code /users/{uid}/public_keys/bundle}.
 *       Contains identity key, signed pre-key bundle, and one-time pre-key list.</li>
 * </ul>
 *
 * <h3>Call site:</h3>
 * {@code SignalKeyManager.ensureKeysInitialized(ctx, onSuccess, onFailure)} is invoked
 * from {@code CryptoInitializer.ensureKeyExists(Context)} on every sign-in.
 * It is idempotent — if keys already exist, it returns immediately without regenerating.
 */
public final class SignalKeyManager {

    private static final String TAG = "SignalKeyManager";

    // ── SecurePrefs key names (private key material) ──────────────────────────
    /** Serialised {@link IdentityKeyPair} — both private and public halves. */
    public static final String KEY_IDENTITY_KEY_PAIR   = "signal_identity_key_pair";
    /** Registration ID as a decimal string (1–16 382). */
    public static final String KEY_REGISTRATION_ID     = "signal_registration_id";
    /** Serialised {@link SignedPreKeyRecord} — includes private key + signature. */
    public static final String KEY_SIGNED_PREKEY       = "signal_signed_prekey";
    /**
     * Previous signed pre-key kept for one rotation cycle as a grace period.
     * {@link DuoShieldSignalStore#loadSignedPreKey} falls back to this if
     * the incoming message references an ID that no longer matches the current key.
     */
    public static final String KEY_SIGNED_PREKEY_PREV  = "signal_signed_prekey_prev";
    /**
     * Monotonically increasing signed pre-key ID counter (decimal string).
     * Seeded to {@code SIGNED_PREKEY_ID + 1} = 2 during initial key generation.
     */
    public static final String KEY_SIGNED_PREKEY_NEXT_ID = "signal_signed_prekey_next_id";
    /** Prefix for one-time pre-key storage: {@code signal_prekey_<id>}. */
    public static final String KEY_PREKEY_PREFIX       = "signal_prekey_";
    /** Comma-separated list of locally stored one-time pre-key IDs. */
    public static final String KEY_PREKEY_IDS          = "signal_prekey_ids";

    /**
     * Next available one-time pre-key ID (decimal string). Starts at
     * {@code ONE_TIME_PREKEY_ID_START + ONE_TIME_PREKEY_COUNT} after initial
     * generation, then incremented by {@link SignalPreKeyRefresher#BATCH_SIZE}
     * on each replenishment cycle. IDs are 24-bit (max 0xFFFFFF per Signal spec).
     */
    public static final String KEY_PREKEY_NEXT_ID      = "signal_prekey_next_id";

    // ── Kyber (PQXDH) last-resort pre-key storage ────────────────────────────
    /**
     * Prefix for Kyber pre-key storage: {@code signal_kyber_prekey_<id>}.
     * Currently DuoShield keeps exactly one last-resort Kyber key at a time,
     * but the indexed structure mirrors the OTP key design for future extension.
     */
    public static final String KEY_KYBER_PREKEY_PREFIX     = "signal_kyber_prekey_";
    /**
     * The ID of the currently active Kyber last-resort pre-key (decimal string).
     * Rotated on the same schedule as the signed pre-key.
     */
    public static final String KEY_KYBER_PREKEY_CURRENT_ID = "signal_kyber_prekey_current_id";

    private static final int SIGNED_PREKEY_ID          = 1;
    private static final int ONE_TIME_PREKEY_COUNT     = 50;
    private static final int ONE_TIME_PREKEY_ID_START  = 1;
    /** 24-bit maximum pre-key ID per the Signal specification. */
    private static final int MAX_PREKEY_ID             = 0xFFFFFF;
    /** Initial Kyber pre-key ID; incremented on each rotation. */
    private static final int KYBER_PREKEY_ID_START     = 1;

    private SignalKeyManager() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Ensures Signal Protocol keys exist on this device. If they do not exist they are
     * generated on a background thread, persisted to {@link SecurePrefs}, and the public
     * half is uploaded to Firestore. If they already exist this method returns immediately.
     *
     * <p>Both callbacks are delivered on the main thread.
     *
     * @param ctx       Application or activity context.
     * @param onSuccess Called once public keys are safely stored in Firestore.
     * @param onFailure Called if generation or the Firestore write fails.
     */
    public static void ensureKeysInitialized(Context ctx, Runnable onSuccess, Runnable onFailure) {
        if (isInitialized(ctx)) {
            Log.d(TAG, "Signal keys already present — skipping generation.");
            // Upgrade legacy accounts that were created before PQXDH support:
            // generate + upload a Kyber last-resort key if one doesn't exist yet.
            ensureKyberKeyExists(ctx, null);
            if (onSuccess != null) new Handler(Looper.getMainLooper()).post(onSuccess);
            return;
        }

        new Thread(() -> {
            try {
                generate(ctx);
                uploadPublicBundle(ctx, onSuccess, onFailure);
            } catch (Exception e) {
                Log.e(TAG, "Signal key generation failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                if (onFailure != null)
                    new Handler(Looper.getMainLooper()).post(onFailure);
            }
        }, "signal-keygen").start();
    }

    /**
     * Generates the remaining Signal Protocol keys (registration ID, signed pre-key,
     * one-time pre-keys) using an identity key pair that was <em>already</em> stored
     * in {@link SecurePrefs} via seed derivation (BIP39 → Curve25519).
     *
     * <p>Unlike {@link #ensureKeysInitialized} this method does NOT regenerate or
     * overwrite the identity key pair — it reads the seed-derived one that was
     * stored in the previous step and uses it to sign the new signed pre-key.</p>
     *
     * <p>Callbacks are posted to the main thread.</p>
     *
     * @param onSuccess       called after public bundle is successfully uploaded
     * @param onFailure       called if generation or upload fails
     * @param onUploadStarted called just before the Firestore upload begins
     */
    public static void generateFromSeedDerivedKey(Context ctx,
                                                  Runnable onSuccess,
                                                  Runnable onFailure,
                                                  Runnable onUploadStarted) {
        new Thread(() -> {
            try {
                // Read the identity key pair that was pre-stored from seed derivation.
                IdentityKeyPair identityKeyPair = getIdentityKeyPair(ctx);
                if (identityKeyPair == null) {
                    Log.e(TAG, "generateFromSeedDerivedKey: no identity key pair in SecurePrefs");
                    if (onFailure != null)
                        new Handler(Looper.getMainLooper()).post(onFailure);
                    return;
                }

                // Registration ID
                int registrationId = new SecureRandom().nextInt(16382) + 1;

                // Signed Pre-Key — signed by the seed-derived identity key
                ECKeyPair    spkPair      = Curve.generateKeyPair();
                byte[]       spkSignature = Curve.calculateSignature(
                        identityKeyPair.getPrivateKey(),
                        spkPair.getPublicKey().serialize());
                SignedPreKeyRecord signedPreKey = new SignedPreKeyRecord(
                        SIGNED_PREKEY_ID,
                        System.currentTimeMillis(),
                        spkPair,
                        spkSignature);

                // One-Time Pre-Keys
                List<PreKeyRecord> oneTimePreKeys = new ArrayList<>(ONE_TIME_PREKEY_COUNT);
                for (int id = ONE_TIME_PREKEY_ID_START;
                     id < ONE_TIME_PREKEY_ID_START + ONE_TIME_PREKEY_COUNT; id++) {
                    oneTimePreKeys.add(new PreKeyRecord(id, Curve.generateKeyPair()));
                }

                // Kyber last-resort pre-key (PQXDH)
                KyberPreKeyRecord kyberPreKey =
                        buildKyberPreKeyRecord(identityKeyPair, KYBER_PREKEY_ID_START);

                // Persist (identity key pair already stored — only write the new keys)
                SharedPreferences.Editor editor = SecurePrefs.get(ctx).edit();
                editor.putString(KEY_REGISTRATION_ID, String.valueOf(registrationId));
                editor.putString(KEY_SIGNED_PREKEY,
                        Base64.encodeToString(signedPreKey.serialize(), Base64.NO_WRAP));
                StringBuilder idsCsv = new StringBuilder();
                for (PreKeyRecord pk : oneTimePreKeys) {
                    editor.putString(KEY_PREKEY_PREFIX + pk.getId(),
                            Base64.encodeToString(pk.serialize(), Base64.NO_WRAP));
                    if (idsCsv.length() > 0) idsCsv.append(',');
                    idsCsv.append(pk.getId());
                }
                editor.putString(KEY_PREKEY_IDS, idsCsv.toString());
                editor.putString(KEY_PREKEY_NEXT_ID,
                        String.valueOf(ONE_TIME_PREKEY_ID_START + ONE_TIME_PREKEY_COUNT));
                editor.putString(KEY_SIGNED_PREKEY_NEXT_ID,
                        String.valueOf(SIGNED_PREKEY_ID + 1));
                editor.putString(KEY_KYBER_PREKEY_PREFIX + KYBER_PREKEY_ID_START,
                        Base64.encodeToString(kyberPreKey.serialize(), Base64.NO_WRAP));
                editor.putString(KEY_KYBER_PREKEY_CURRENT_ID,
                        String.valueOf(KYBER_PREKEY_ID_START));
                editor.apply();

                Log.d(TAG, "Seed-derived keys generated — registrationId=" + registrationId
                        + "  prekeys=" + oneTimePreKeys.size()
                        + "  kyberPreKeyId=" + KYBER_PREKEY_ID_START);

                // Signal upload start on main thread
                if (onUploadStarted != null)
                    new Handler(Looper.getMainLooper()).post(onUploadStarted);

                uploadPublicBundle(ctx, onSuccess, onFailure);

            } catch (Exception e) {
                Log.e(TAG, "generateFromSeedDerivedKey failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                if (onFailure != null)
                    new Handler(Looper.getMainLooper()).post(onFailure);
            }
        }, "seed-keygen").start();
    }

    /**
     * Returns {@code true} when the Identity Key Pair has been generated and stored.
     *
     * <p>BUG-S10 fix: also checks that EncryptedSharedPreferences initialised
     * successfully.  If SecurePrefs fell back to plaintext the Signal identity key
     * will not be present in plaintext prefs, so the result would be {@code false}
     * regardless — but an explicit isAvailable() guard makes the intent clear and
     * prevents a future code path from reading stale plaintext material.
     */
    public static boolean isInitialized(Context ctx) {
        // Check whether the identity key pair is present in whichever SharedPreferences
        // instance SecurePrefs is using (encrypted or plaintext fallback).
        //
        // NOTE: do NOT gate this on SecurePrefs.isAvailable().  On devices where all
        // three EncryptedSharedPreferences tiers fail, SecurePrefs falls back to a plain
        // MODE_PRIVATE store — the identity key IS written there during account creation,
        // so isAvailable()==false does NOT mean "no key".  Returning false here when
        // isAvailable()==false produces a permanent "Continue → back to Sign In" loop
        // (MainActivity routes to SignInActivity because isInitialized() says false even
        // though keys exist in the plaintext store).
        SharedPreferences prefs = SecurePrefs.get(ctx);
        String preKeyIds = prefs.getString(KEY_PREKEY_IDS, "");
        return prefs.getString(KEY_IDENTITY_KEY_PAIR, null) != null
                && prefs.getString(KEY_REGISTRATION_ID, null) != null
                && prefs.getString(KEY_SIGNED_PREKEY, null) != null
                && !preKeyIds.trim().isEmpty();
    }

    /**
     * Returns this device's Account ID — a 66-character lowercase hex string
     * of the serialised identity public key (0x05 prefix + 32-byte X25519 key).
     *
     * <p>Returns {@code null} if keys have not been generated yet or if the
     * stored key pair cannot be deserialised.</p>
     */
    public static String getAccountId(Context ctx) {
        IdentityKeyPair ikp = getIdentityKeyPair(ctx);
        if (ikp == null) return null;
        byte[] pub = ikp.getPublicKey().serialize(); // 33 bytes: 0x05 + 32-byte key
        StringBuilder sb = new StringBuilder(66);
        for (byte b : pub) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    // ── Key retrieval helpers (for SessionBuilder / SessionCipher in Step 5) ──

    /**
     * Returns the locally stored {@link IdentityKeyPair}, or {@code null} if not yet generated.
     */
    public static IdentityKeyPair getIdentityKeyPair(Context ctx) {
        String b64 = SecurePrefs.get(ctx).getString(KEY_IDENTITY_KEY_PAIR, null);
        if (b64 == null) return null;
        try {
            return new IdentityKeyPair(Base64.decode(b64, Base64.NO_WRAP));
        } catch (Exception e) {
            Log.e(TAG, "IdentityKeyPair deserialisation failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    /**
     * Returns the locally stored registration ID, or {@code -1} if not yet generated.
     */
    public static int getRegistrationId(Context ctx) {
        String s = SecurePrefs.get(ctx).getString(KEY_REGISTRATION_ID, null);
        if (s == null) return -1;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return -1; }
    }

    /**
     * Returns the locally stored {@link SignedPreKeyRecord}, or {@code null} if not yet generated.
     */
    public static SignedPreKeyRecord getSignedPreKey(Context ctx) {
        String b64 = SecurePrefs.get(ctx).getString(KEY_SIGNED_PREKEY, null);
        if (b64 == null) return null;
        try {
            return new SignedPreKeyRecord(Base64.decode(b64, Base64.NO_WRAP));
        } catch (Exception e) {
            Log.e(TAG, "SignedPreKeyRecord deserialisation failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    /**
     * Returns the previous {@link SignedPreKeyRecord} stored during the last rotation,
     * or {@code null} if no rotation has occurred yet or the grace-period copy is absent.
     *
     * <p>Used by {@link DuoShieldSignalStore#loadSignedPreKey} to decrypt messages
     * that were encrypted against the old key just before a rotation took place.
     */
    public static SignedPreKeyRecord getPrevSignedPreKey(Context ctx) {
        String b64 = SecurePrefs.get(ctx).getString(KEY_SIGNED_PREKEY_PREV, null);
        if (b64 == null) return null;
        try {
            return new SignedPreKeyRecord(Base64.decode(b64, Base64.NO_WRAP));
        } catch (Exception e) {
            Log.e(TAG, "Prev SignedPreKeyRecord deserialisation failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    /**
     * Rotates the signed pre-key:
     * <ol>
     *   <li>Promotes the current SPK to {@link #KEY_SIGNED_PREKEY_PREV} (grace period).</li>
     *   <li>Generates a new Curve25519 key pair signed by the identity key.</li>
     *   <li>Persists the new SPK to {@link #KEY_SIGNED_PREKEY} in SecurePrefs.</li>
     *   <li>Uploads the new public half through the authenticated Signal key service.</li>
     * </ol>
     *
     * <p>Called by {@link SignedPreKeyRotationWorker} when the current SPK is ≥ 7 days old.
     * The local rotation is synchronous; the authenticated service update runs on the caller's thread.
     *
     * @throws InvalidKeyException If Curve25519 signature generation fails.
     */
    public static void rotateSignedPreKey(Context ctx) throws InvalidKeyException {
        IdentityKeyPair idPair = getIdentityKeyPair(ctx);
        if (idPair == null)
            throw new IllegalStateException("Identity key pair not initialised.");

        SharedPreferences prefs = SecurePrefs.get(ctx);

        // Promote current → previous (grace period: one full rotation cycle).
        String currentB64 = prefs.getString(KEY_SIGNED_PREKEY, null);
        SharedPreferences.Editor editor = prefs.edit();
        if (currentB64 != null) {
            editor.putString(KEY_SIGNED_PREKEY_PREV, currentB64);
        }

        // Generate new signed pre-key.
        int newId = getAndIncrementNextSignedPreKeyId(ctx);
        ECKeyPair spkPair = Curve.generateKeyPair();
        byte[] spkSig = Curve.calculateSignature(
                idPair.getPrivateKey(),
                spkPair.getPublicKey().serialize());
        SignedPreKeyRecord newSpk = new SignedPreKeyRecord(
                newId, System.currentTimeMillis(), spkPair, spkSig);

        editor.putString(KEY_SIGNED_PREKEY,
                Base64.encodeToString(newSpk.serialize(), Base64.NO_WRAP));
        editor.apply();

        Log.d(TAG, "Signed pre-key rotated — old key saved as prev, new key id=" + newId);

        // Upload public half through the authenticated API (local rotation is already committed).
        uploadRotatedSignedPreKey(ctx, newSpk);
    }

    // ── Public: Kyber pre-key access ──────────────────────────────────────────

    /**
     * Returns the Kyber last-resort pre-key for the given ID, or {@code null} if absent.
     */
    public static KyberPreKeyRecord getKyberPreKey(Context ctx, int id) {
        String b64 = SecurePrefs.get(ctx)
                .getString(KEY_KYBER_PREKEY_PREFIX + id, null);
        if (b64 == null) return null;
        try {
            return new KyberPreKeyRecord(Base64.decode(b64, Base64.NO_WRAP));
        } catch (Exception e) {
            Log.e(TAG, "KyberPreKeyRecord #" + id + " deserialisation failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    /**
     * Returns the current active Kyber last-resort pre-key, or {@code null} if not yet
     * generated (e.g. legacy account pre-dating PQXDH support).
     */
    public static KyberPreKeyRecord getCurrentKyberPreKey(Context ctx) {
        String idStr = SecurePrefs.get(ctx)
                .getString(KEY_KYBER_PREKEY_CURRENT_ID, null);
        if (idStr == null) return null;
        try {
            return getKyberPreKey(ctx, Integer.parseInt(idStr));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the ID of the current Kyber last-resort pre-key, or {@code -1} if absent.
     */
    public static int getCurrentKyberPreKeyId(Context ctx) {
        String idStr = SecurePrefs.get(ctx)
                .getString(KEY_KYBER_PREKEY_CURRENT_ID, null);
        if (idStr == null) return -1;
        try { return Integer.parseInt(idStr); }
        catch (NumberFormatException e) { return -1; }
    }

    /**
     * Ensures this device has a Kyber-1024 last-resort pre-key for PQXDH.
     *
     * <p>Accounts created before PQXDH support was added will not have a Kyber key
     * in SecurePrefs. This method generates one and uploads the public half to
     * Firestore so new contacts receive a PQXDH session instead of falling back to
     * classic X3DH. Safe to call on every launch — exits immediately if a key exists.
     *
     * @param ctx    Application or Activity context.
     * @param onDone Called on the main thread when complete (succeeds silently on error
     *               so callers never block on this upgrade path).
     */
    public static void ensureKyberKeyExists(Context ctx, Runnable onDone) {
        int existingId = getCurrentKyberPreKeyId(ctx);
        if (existingId >= 0) {
            // ID pointer exists — also verify the key record blob is actually present
            // and loadable. If it is missing or corrupt (e.g. partial SecurePrefs write),
            // fall through to regenerate rather than silently leaving PQXDH unavailable.
            if (getKyberPreKey(ctx, existingId) != null) {
                // Both pointer and record are healthy — nothing to do.
                if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
                return;
            }
            Log.w(TAG, "ensureKyberKeyExists: Kyber key ID=" + existingId
                    + " is recorded but the key record is missing or corrupt — regenerating.");
        }

        new Thread(() -> {
            try {
                IdentityKeyPair idPair = getIdentityKeyPair(ctx);
                if (idPair == null) {
                    Log.w(TAG, "ensureKyberKeyExists: no identity key pair — skipping.");
                    return;
                }
                KyberPreKeyRecord kpk = buildKyberPreKeyRecord(idPair, KYBER_PREKEY_ID_START);
                SecurePrefs.get(ctx).edit()
                        .putString(KEY_KYBER_PREKEY_PREFIX + KYBER_PREKEY_ID_START,
                                Base64.encodeToString(kpk.serialize(), Base64.NO_WRAP))
                        .putString(KEY_KYBER_PREKEY_CURRENT_ID,
                                String.valueOf(KYBER_PREKEY_ID_START))
                        .apply();
                Log.d(TAG, "ensureKyberKeyExists: Kyber key generated for legacy account — id="
                        + KYBER_PREKEY_ID_START);
                uploadRotatedKyberPreKey(ctx, kpk);
            } catch (Exception e) {
                Log.e(TAG, "ensureKyberKeyExists: failed — PQXDH unavailable until next rotation" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            } finally {
                if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
            }
        }, "kyber-key-ensure").start();
    }

    /**
     * Rotates the Kyber last-resort pre-key:
     * <ol>
     *   <li>Generates a new Kyber-1024 key pair signed by the identity key.</li>
     *   <li>Stores the private half in {@link SecurePrefs}.</li>
     *   <li>Uploads the public half + signature through the authenticated Signal key service.</li>
     * </ol>
     *
     * <p>The old Kyber key is NOT kept as a grace-period copy — Kyber last-resort
     * keys can be reused until replaced, so there is no decryption gap.</p>
     *
     * @throws InvalidKeyException if signature generation fails.
     */
    public static void rotateKyberPreKey(Context ctx) throws InvalidKeyException {
        IdentityKeyPair idPair = getIdentityKeyPair(ctx);
        if (idPair == null)
            throw new IllegalStateException("Identity key pair not initialised.");

        // Derive next ID (wrap at 24-bit limit).
        int currentId = getCurrentKyberPreKeyId(ctx);
        int newId = (currentId < 0 || currentId >= MAX_PREKEY_ID)
                ? KYBER_PREKEY_ID_START
                : currentId + 1;

        KyberPreKeyRecord newKpk = buildKyberPreKeyRecord(idPair, newId);

        SecurePrefs.get(ctx).edit()
                .putString(KEY_KYBER_PREKEY_PREFIX + newId,
                        Base64.encodeToString(newKpk.serialize(), Base64.NO_WRAP))
                .putString(KEY_KYBER_PREKEY_CURRENT_ID, String.valueOf(newId))
                .apply();

        // Remove old key from SecurePrefs to avoid unbounded growth.
        if (currentId >= 0 && currentId != newId) {
            SecurePrefs.get(ctx).edit()
                    .remove(KEY_KYBER_PREKEY_PREFIX + currentId)
                    .apply();
        }

        Log.d(TAG, "Kyber pre-key rotated — new id=" + newId);
        uploadRotatedKyberPreKey(ctx, newKpk);
    }

    // ── Private: Kyber helpers ─────────────────────────────────────────────────

    /**
     * Generates and persists the initial Kyber last-resort pre-key.
     * Called once during initial key generation.
     */
    private static KyberPreKeyRecord buildKyberPreKeyRecord(IdentityKeyPair idPair, int id)
            throws InvalidKeyException {
        KEMKeyPair kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        byte[] kyberSig = Curve.calculateSignature(
                idPair.getPrivateKey(),
                kyberPair.getPublicKey().serialize());
        return new KyberPreKeyRecord(id, System.currentTimeMillis(), kyberPair, kyberSig);
    }

    private static void uploadRotatedKyberPreKey(Context ctx, KyberPreKeyRecord kpk) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "Cannot upload rotated Kyber pre-key — no signed-in user.");
            return;
        }
        try {
            SignalKeyApi.updatePreKey("kyberPreKey", buildKyberPreKeyMap(kpk));
            Log.d(TAG, "Rotated Kyber pre-key uploaded (id=" + kpk.getId() + ").");
        } catch (Exception e) {
            Log.e(TAG, "Kyber pre-key upload failed — will retry on next rotation." + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
    }

    private static Map<String, Object> buildKyberPreKeyMap(KyberPreKeyRecord kpk) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",        kpk.getId());
        m.put("publicKey", Base64.encodeToString(
                kpk.getKeyPair().getPublicKey().serialize(), Base64.NO_WRAP));
        m.put("signature", Base64.encodeToString(kpk.getSignature(), Base64.NO_WRAP));
        m.put("timestamp", kpk.getTimestamp());
        return m;
    }

    // ── Private: signed pre-key rotation helpers ───────────────────────────────

    private static int getAndIncrementNextSignedPreKeyId(Context ctx) {
        SharedPreferences prefs = SecurePrefs.get(ctx);
        String stored = prefs.getString(KEY_SIGNED_PREKEY_NEXT_ID, null);
        int nextId;
        if (stored == null) {
            // Legacy devices without the counter — start at 2 (initial key used ID=1).
            nextId = SIGNED_PREKEY_ID + 1;
        } else {
            try { nextId = Integer.parseInt(stored); }
            catch (NumberFormatException e) { nextId = SIGNED_PREKEY_ID + 1; }
        }
        // Wrap signed pre-key IDs at the 24-bit Signal spec limit (BUG-S12).
        int next = nextId + 1;
        if (next > MAX_PREKEY_ID) next = 1;
        prefs.edit().putString(KEY_SIGNED_PREKEY_NEXT_ID, String.valueOf(next)).apply();
        return nextId;
    }

    private static void uploadRotatedSignedPreKey(Context ctx, SignedPreKeyRecord spk) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "Cannot upload rotated SPK — no signed-in user.");
            return;
        }
        Map<String, Object> spkMap = new HashMap<>();
        spkMap.put("id",        spk.getId());
        spkMap.put("publicKey", Base64.encodeToString(
                spk.getKeyPair().getPublicKey().serialize(), Base64.NO_WRAP));
        spkMap.put("signature", Base64.encodeToString(spk.getSignature(), Base64.NO_WRAP));
        spkMap.put("timestamp", spk.getTimestamp());
        try {
            SignalKeyApi.updatePreKey("signedPreKey", spkMap);
            Log.d(TAG, "Rotated signed pre-key uploaded (id=" + spk.getId() + ").");
        } catch (Exception e) {
            Log.e(TAG, "Signed pre-key upload failed — will retry on next rotation cycle." + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }
    }

    /**
     * Returns the locally stored {@link PreKeyRecord} for the given ID, or {@code null} if absent.
     */
    public static PreKeyRecord getPreKey(Context ctx, int id) {
        String b64 = SecurePrefs.get(ctx).getString(KEY_PREKEY_PREFIX + id, null);
        if (b64 == null) return null;
        try {
            return new PreKeyRecord(Base64.decode(b64, Base64.NO_WRAP));
        } catch (Exception e) {
            Log.e(TAG, "PreKeyRecord #" + id + " deserialisation failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    /**
     * Removes a one-time pre-key from local storage after it has been consumed by X3DH.
     * Called by the session-establishment layer (Step 4) once the key is used.
     *
     * <p>After removing the key, {@link SignalPreKeyRefresher#checkAndReplenish} is
     * invoked. If the pool has dropped below the replenishment threshold it will
     * automatically generate and upload a fresh batch in the background.
     */
    public static void consumePreKey(Context ctx, int id) {
        SharedPreferences prefs = SecurePrefs.get(ctx);
        String idsStr = prefs.getString(KEY_PREKEY_IDS, "");

        // Remove the individual key
        prefs.edit().remove(KEY_PREKEY_PREFIX + id).apply();

        // Remove the ID from the master list
        if (!idsStr.isEmpty()) {
            StringBuilder remaining = new StringBuilder();
            for (String part : idsStr.split(",")) {
                if (!part.trim().equals(String.valueOf(id))) {
                    if (remaining.length() > 0) remaining.append(',');
                    remaining.append(part.trim());
                }
            }
            prefs.edit().putString(KEY_PREKEY_IDS, remaining.toString()).apply();
        }
        Log.d(TAG, "One-time pre-key #" + id + " consumed and removed from local store.");

        // Replenish in the background if the pool is running low.
        SignalPreKeyRefresher.checkAndReplenish(ctx);
    }

    // ── Public helpers for SignalPreKeyRefresher ───────────────────────────────

    /**
     * Returns the number of one-time pre-keys currently in the local pool.
     * Used by {@link SignalPreKeyRefresher} to decide whether to replenish.
     */
    public static int getPreKeyCount(Context ctx) {
        String idsStr = SecurePrefs.get(ctx).getString(KEY_PREKEY_IDS, "");
        if (idsStr.isEmpty()) return 0;
        return idsStr.split(",").length;
    }

    /**
     * Reads the next available pre-key ID from {@link #KEY_PREKEY_NEXT_ID},
     * advances the counter by {@code count}, and persists the new value.
     *
     * <p>IDs wrap at {@code 0xFFFFFF} (24-bit Signal spec limit).
     *
     * @param ctx   Application or activity context.
     * @param count Number of IDs to reserve.
     * @return The first ID in the reserved range; callers use {@code [start, start+count)}.
     */
    public static int getAndIncrementNextPreKeyId(Context ctx, int count) {
        SharedPreferences prefs = SecurePrefs.get(ctx);
        String stored = prefs.getString(KEY_PREKEY_NEXT_ID, null);
        int nextId;
        if (stored == null) {
            // Legacy devices that pre-date this field: start beyond the initial batch.
            nextId = ONE_TIME_PREKEY_ID_START + ONE_TIME_PREKEY_COUNT;
        } else {
            try { nextId = Integer.parseInt(stored); }
            catch (NumberFormatException e) {
                nextId = ONE_TIME_PREKEY_ID_START + ONE_TIME_PREKEY_COUNT;
            }
        }
        int newNext = nextId + count;
        // Correct 1-based wrap at the 24-bit Signal spec limit (BUG-S12).
        // Previous formula: (newNext % MAX) + 1 is off by one when newNext == MAX+1:
        //   (MAX+1) % MAX = 1, then +1 = 2 instead of 1.
        // Correct formula: ((newNext - 1) % MAX) + 1
        if (newNext > MAX_PREKEY_ID) newNext = ((newNext - 1) % MAX_PREKEY_ID) + 1;
        prefs.edit().putString(KEY_PREKEY_NEXT_ID, String.valueOf(newNext)).apply();
        return nextId;
    }

    /**
     * Persists a batch of freshly-generated {@link PreKeyRecord} objects to
     * {@link SecurePrefs} and appends their IDs to the {@link #KEY_PREKEY_IDS} CSV.
     *
     * <p>Called by {@link SignalPreKeyRefresher} after key generation so that the
     * private key bytes are safely stored before the public upload is attempted.
     *
     * @param ctx     Application or activity context.
     * @param newKeys Newly-generated pre-key records.
     * @throws java.io.IOException If {@link PreKeyRecord#serialize()} fails.
     */
    public static void storeNewPreKeys(Context ctx, List<PreKeyRecord> newKeys)
            throws java.io.IOException {
        SharedPreferences prefs  = SecurePrefs.get(ctx);
        String            idsStr = prefs.getString(KEY_PREKEY_IDS, "");
        StringBuilder     ids    = new StringBuilder(idsStr);

        SharedPreferences.Editor editor = prefs.edit();
        for (PreKeyRecord pk : newKeys) {
            editor.putString(KEY_PREKEY_PREFIX + pk.getId(),
                    Base64.encodeToString(pk.serialize(), Base64.NO_WRAP));
            if (ids.length() > 0) ids.append(',');
            ids.append(pk.getId());
        }
        editor.putString(KEY_PREKEY_IDS, ids.toString());
        editor.apply();
        Log.d(TAG, "storeNewPreKeys: persisted " + newKeys.size() + " keys locally.");
    }

    // ── Private: key generation ────────────────────────────────────────────────

    /**
     * Generates all four Signal key types and persists ONLY the private material
     * to {@link SecurePrefs}. Runs on a background thread.
     */
    private static void generate(Context ctx)
            throws InvalidKeyException {

        Log.d(TAG, "Generating Signal Protocol keys…");

        // 1. Identity Key Pair — Curve25519, long-lived device identity.
        IdentityKeyPair identityKeyPair = IdentityKeyPair.generate();

        // 2. Registration ID — unique device identifier, random 1–16 382.
        //    Range matches the Signal spec: 1 ≤ id ≤ 16 383 – 1.
        int registrationId = new SecureRandom().nextInt(16382) + 1;

        // 3. Signed Pre-Key — Curve25519 key pair signed by the Identity Key.
        //    The signature lets the recipient verify the key is genuinely from this device.
        ECKeyPair    spkPair      = Curve.generateKeyPair();
        byte[]       spkSignature = Curve.calculateSignature(
                identityKeyPair.getPrivateKey(),
                spkPair.getPublicKey().serialize());
        SignedPreKeyRecord signedPreKey = new SignedPreKeyRecord(
                SIGNED_PREKEY_ID,
                System.currentTimeMillis(),
                spkPair,
                spkSignature);

        // 4. One-Time Pre-Keys — 50 Curve25519 key pairs.
        //    Each is used exactly once during X3DH and then discarded (step 4 / 5).
        List<PreKeyRecord> oneTimePreKeys = new ArrayList<>(ONE_TIME_PREKEY_COUNT);
        for (int id = ONE_TIME_PREKEY_ID_START;
             id < ONE_TIME_PREKEY_ID_START + ONE_TIME_PREKEY_COUNT; id++) {
            ECKeyPair kp = Curve.generateKeyPair();
            oneTimePreKeys.add(new PreKeyRecord(id, kp));
        }

        // 5. Kyber last-resort pre-key (PQXDH) — Kyber-1024 key pair signed by the
        //    identity key.  Provides post-quantum forward secrecy on top of X3DH.
        KyberPreKeyRecord kyberPreKey = buildKyberPreKeyRecord(identityKeyPair, KYBER_PREKEY_ID_START);

        // Persist everything to EncryptedSharedPreferences.
        // Private key bytes NEVER leave the device from this point onward.
        SharedPreferences.Editor editor = SecurePrefs.get(ctx).edit();

        editor.putString(KEY_IDENTITY_KEY_PAIR,
                Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP));
        editor.putString(KEY_REGISTRATION_ID,
                String.valueOf(registrationId));
        editor.putString(KEY_SIGNED_PREKEY,
                Base64.encodeToString(signedPreKey.serialize(), Base64.NO_WRAP));

        StringBuilder idsCsv = new StringBuilder();
        for (PreKeyRecord pk : oneTimePreKeys) {
            editor.putString(KEY_PREKEY_PREFIX + pk.getId(),
                    Base64.encodeToString(pk.serialize(), Base64.NO_WRAP));
            if (idsCsv.length() > 0) idsCsv.append(',');
            idsCsv.append(pk.getId());
        }
        editor.putString(KEY_PREKEY_IDS, idsCsv.toString());

        // Seed the monotonic OTP prekey ID counter.
        editor.putString(KEY_PREKEY_NEXT_ID,
                String.valueOf(ONE_TIME_PREKEY_ID_START + ONE_TIME_PREKEY_COUNT));

        // Seed the signed pre-key ID counter so rotations never reuse ID=1.
        editor.putString(KEY_SIGNED_PREKEY_NEXT_ID,
                String.valueOf(SIGNED_PREKEY_ID + 1));

        // Kyber last-resort pre-key.
        editor.putString(KEY_KYBER_PREKEY_PREFIX + KYBER_PREKEY_ID_START,
                Base64.encodeToString(kyberPreKey.serialize(), Base64.NO_WRAP));
        editor.putString(KEY_KYBER_PREKEY_CURRENT_ID,
                String.valueOf(KYBER_PREKEY_ID_START));

        editor.apply(); // async write — EncryptedSharedPrefs encrypts before disk flush

        Log.d(TAG, "Keys generated and stored locally. registrationId=" + registrationId
                + "  oneTimePreKeys=" + oneTimePreKeys.size() + "  kyberPreKeyId=" + KYBER_PREKEY_ID_START);
    }

    // ── Private: Firestore upload (public keys only) ───────────────────────────

    /**
     * Uploads ONLY the public halves of the Signal keys through the authenticated service to:
     * {@code /users/{uid}/public_keys/bundle}
     *
     * Structure stored:
     * <pre>
     * {
     *   "identityKey":    "<Base64 Curve25519 public key>",
     *   "registrationId": 12345,
     *   "signedPreKey":   { "id": 1, "publicKey": "<Base64>", "signature": "<Base64>", "timestamp": … },
     *   "oneTimePreKeys": [ { "id": 1, "publicKey": "<Base64>" }, … ],
     *   "updatedAt":      <ServerTimestamp>
     * }
     * </pre>
     */
    private static void uploadPublicBundle(Context ctx, Runnable onSuccess, Runnable onFailure) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Cannot upload public keys — no signed-in user.");
            if (onFailure != null) new Handler(Looper.getMainLooper()).post(onFailure);
            return;
        }
        String uid = user.getUid();

        // Re-load from SecurePrefs (they were just written by generate())
        IdentityKeyPair    idPair = getIdentityKeyPair(ctx);
        SignedPreKeyRecord spk    = getSignedPreKey(ctx);
        int                regId  = getRegistrationId(ctx);
        String             idsStr = SecurePrefs.get(ctx).getString(KEY_PREKEY_IDS, "");

        if (idPair == null || spk == null || regId < 0) {
            Log.e(TAG, "Keys missing after generate() — cannot upload.");
            if (onFailure != null) new Handler(Looper.getMainLooper()).post(onFailure);
            return;
        }

        // ── Identity public key ───────────────────────────────────────────────
        // IdentityKey.serialize() = 33 bytes: 0x05 prefix + 32-byte X25519 public key
        IdentityKey identityKey     = idPair.getPublicKey();
        String      identityKeyB64  = Base64.encodeToString(
                identityKey.serialize(), Base64.NO_WRAP);

        // ── Signed pre-key bundle (public half only) ──────────────────────────
        Map<String, Object> spkMap = new HashMap<>();
        spkMap.put("id",        spk.getId());
        spkMap.put("publicKey", Base64.encodeToString(
                spk.getKeyPair().getPublicKey().serialize(), Base64.NO_WRAP));
        spkMap.put("signature", Base64.encodeToString(
                spk.getSignature(), Base64.NO_WRAP));
        spkMap.put("timestamp", spk.getTimestamp());

        // ── One-time pre-key public list ──────────────────────────────────────
        List<Map<String, Object>> otpkList = new ArrayList<>();
        if (!idsStr.isEmpty()) {
            for (String part : idsStr.split(",")) {
                try {
                    int id = Integer.parseInt(part.trim());
                    PreKeyRecord pk = getPreKey(ctx, id);
                    if (pk != null) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("id",        pk.getId());
                        entry.put("publicKey", Base64.encodeToString(
                                pk.getKeyPair().getPublicKey().serialize(), Base64.NO_WRAP));
                        otpkList.add(entry);
                    }
                } catch (Exception ignored) {}
            }
        }

        // ── Kyber last-resort pre-key (PQXDH) ───────────────────────────────
        Map<String, Object> kyberMap = null;
        KyberPreKeyRecord kpk = getCurrentKyberPreKey(ctx);
        if (kpk != null) {
            kyberMap = buildKyberPreKeyMap(kpk);
        }

        // ── Firestore document ────────────────────────────────────────────────
        Map<String, Object> bundle = new HashMap<>();
        bundle.put("identityKey",    identityKeyB64);
        bundle.put("registrationId", regId);
        bundle.put("signedPreKey",   spkMap);
        bundle.put("oneTimePreKeys", otpkList);
        if (kyberMap != null) bundle.put("kyberPreKey", kyberMap);
        // The server supplies updatedAt after validating the public bundle.

        try {
            // UID is always derived from the verified Firebase ID token by the server.
            SignalKeyApi.registerBundle(bundle);
            Log.d(TAG, "Public Signal key bundle uploaded via server ✓ uid=" + LogRedact.uid(uid));
            if (onSuccess != null)
                new Handler(Looper.getMainLooper()).post(onSuccess);
        } catch (Exception e) {
            Log.e(TAG, "Signal key bundle upload failed for uid=" + LogRedact.uid(uid) + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            if (onFailure != null)
                new Handler(Looper.getMainLooper()).post(onFailure);
        }
    }
}
