package com.duoshield.app.crypto.signal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.SignalSessionRecord;
import com.duoshield.app.util.SecurePrefs;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyStore;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SessionStore;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.groups.state.SenderKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link SignalProtocolStore} implementation for DuoShield.
 *
 * <p>Backed by two storage layers:
 * <ul>
 *   <li>{@link SecurePrefs} (EncryptedSharedPreferences) — identity keys, pre-keys,
 *       signed pre-keys, and trusted peer identities. All private key material lives here.</li>
 *   <li>Room DB ({@code signal_sessions} table) — Double Ratchet session state.
 *       Updated after every sent/received message.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All methods are synchronous. Callers (SessionBuilder, SessionCipher) must execute
 * on a background thread — never on the main thread — because Room enforces this.
 */
public final class DuoShieldSignalStore
        implements SignalProtocolStore {

    private static final String TAG = "DuoShieldSignalStore";

    // SecurePrefs key prefixes for trusted peer identities
    private static final String KEY_TRUSTED_IDENTITY_PREFIX = "signal_trusted_id_";

    private final Context ctx;

    /**
     * Process-lifetime singleton.
     *
     * <p>A new instance was previously created on every caller site, resulting in
     * multiple objects backed by the same persistent storage.  The store holds no
     * mutable in-memory state (all reads/writes go to SecurePrefs / Room), so a
     * single shared instance is safe and avoids redundant construction (BUG-SS01).
     */
    private static volatile DuoShieldSignalStore instance;

    public static DuoShieldSignalStore getInstance(Context ctx) {
        if (instance == null) {
            synchronized (DuoShieldSignalStore.class) {
                if (instance == null) {
                    instance = new DuoShieldSignalStore(ctx);
                }
            }
        }
        return instance;
    }

    private DuoShieldSignalStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IdentityKeyStore
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        IdentityKeyPair kp = SignalKeyManager.getIdentityKeyPair(ctx);
        if (kp == null) throw new IllegalStateException(
                "Signal identity key pair not initialised — call ensureKeysInitialized() first.");
        return kp;
    }

    @Override
    public int getLocalRegistrationId() {
        int id = SignalKeyManager.getRegistrationId(ctx);
        if (id < 0) throw new IllegalStateException("Signal registration ID not initialised.");
        return id;
    }

    /**
     * Persists the remote party's identity key in SecurePrefs.
     * Returns {@code true} if the key is new (first encounter) or changed.
     *
     * <p>Trust model: TOFU (Trust On First Use). Any identity is accepted on first
     * encounter. A subsequent different identity is flagged but still stored — the
     * key-fingerprint screen (already in Settings) gives users out-of-band verification.
     */
    @Override
    public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        String prefsKey = KEY_TRUSTED_IDENTITY_PREFIX + address.toString();
        SharedPreferences prefs = SecurePrefs.get(ctx);
        String existing = prefs.getString(prefsKey, null);
        String incoming = Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP);

        if (existing == null) {
            // F22 fix: store the fingerprint key on first-use too, not only on subsequent
            // changes. Without this, KeyFingerprintActivity has nothing to show immediately
            // after first pairing (the safety-number screen was blank on a fresh install).
            // "signal_partner_identity_key_<name>" (address-scoped) is the correct key —
            // "signal_partner_identity_key" (global, no suffix) was the old unscoped write.
            prefs.edit()
                .putString(prefsKey, incoming)
                .putString("signal_partner_identity_key_" + address.getName(), incoming)
                .apply();
            return true; // new identity — session can proceed
        }
        if (!existing.equals(incoming)) {
            Log.w(TAG, "Identity key changed for <redacted> — storing new key (TOFU).");
            // Batch the SecurePrefs writes into one editor so only one apply() call
            // flushes to disk instead of three (BUG-CR03).
            prefs.edit()
                .putString(prefsKey, incoming)
                .putString("signal_partner_identity_key_" + address.getName(), incoming)
                .apply();
            // The safety-number flag lives in a separate SharedPreferences file — must
            // be a separate apply() call.
            ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE)
               .edit().putBoolean("safety_num_changed_" + address.getName(), true).apply();
            return true; // changed — caller may warn the user
        }
        return false; // unchanged
    }

    /**
     * BUG-S05 fix: compare incoming identity against the stored one.
     *
     * <ul>
     *   <li>First contact (no stored key) → trust on first use (TOFU).
     *   <li>Key matches stored → trusted.
     *   <li>Key changed → untrusted; set {@code safety_num_changed_<uid>} flag so
     *       {@code ChatMediaActivity.checkSafetyNumberBanner()} shows the verification
     *       banner.  {@link SignalCipherHelper} will throw
     *       {@link org.signal.libsignal.protocol.UntrustedIdentityException} and the
     *       message will appear as "[Decryption failed]" until the user verifies.
     * </ul>
     */
    @Override
    public boolean isTrustedIdentity(SignalProtocolAddress address,
                                     IdentityKey identityKey,
                                     IdentityKeyStore.Direction direction) {
        IdentityKey stored = getIdentity(address);
        if (stored == null) {
            return true; // First contact — TOFU
        }
        boolean trusted = stored.equals(identityKey);
        if (!trusted) {
            Log.w(TAG, "Identity key changed for <redacted> — raising safety-number banner");
            ctx.getSharedPreferences("duoshield_prefs", android.content.Context.MODE_PRIVATE)
               .edit()
               .putBoolean("safety_num_changed_" + address.getName(), true)
               .apply();
        }
        return trusted;
    }

    @Override
    public IdentityKey getIdentity(SignalProtocolAddress address) {
        String prefsKey = KEY_TRUSTED_IDENTITY_PREFIX + address.toString();
        String b64 = SecurePrefs.get(ctx).getString(prefsKey, null);
        if (b64 == null) return null;
        try {
            return new IdentityKey(Base64.decode(b64, Base64.NO_WRAP), 0);
        } catch (InvalidKeyException e) {
            Log.e(TAG, "Failed to deserialise stored identity for <redacted>", e);
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PreKeyStore  (one-time pre-keys — our own, used for incoming sessions)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
        PreKeyRecord pk = SignalKeyManager.getPreKey(ctx, preKeyId);
        if (pk == null) throw new InvalidKeyIdException(
                "No one-time pre-key found for id=" + preKeyId);
        return pk;
    }

    @Override
    public void storePreKey(int preKeyId, PreKeyRecord record) {
        // New pre-keys are generated by SignalKeyManager.generate() in batch.
        // Individual storePreKey() calls happen during key replenishment (future step).
        try {
            SecurePrefs.get(ctx).edit()
                    .putString(SignalKeyManager.KEY_PREKEY_PREFIX + preKeyId,
                            Base64.encodeToString(record.serialize(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "storePreKey failed for id=" + preKeyId, e);
        }
    }

    @Override
    public boolean containsPreKey(int preKeyId) {
        return SecurePrefs.get(ctx)
                .getString(SignalKeyManager.KEY_PREKEY_PREFIX + preKeyId, null) != null;
    }

    @Override
    public void removePreKey(int preKeyId) {
        SignalKeyManager.consumePreKey(ctx, preKeyId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SignedPreKeyStore  (medium-term signed pre-keys)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId)
            throws InvalidKeyIdException {
        // Check current SPK first.
        SignedPreKeyRecord spk = SignalKeyManager.getSignedPreKey(ctx);
        if (spk != null && spk.getId() == signedPreKeyId) return spk;

        // Fall back to the previous SPK kept for one rotation cycle as a grace
        // period — messages sent just before rotation still reference the old ID.
        SignedPreKeyRecord prev = SignalKeyManager.getPrevSignedPreKey(ctx);
        if (prev != null && prev.getId() == signedPreKeyId) {
            Log.d(TAG, "loadSignedPreKey(" + signedPreKeyId + "): using prev SPK (grace period).");
            return prev;
        }

        throw new InvalidKeyIdException("No signed pre-key found for id=" + signedPreKeyId);
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        List<SignedPreKeyRecord> result = new ArrayList<>();
        SignedPreKeyRecord spk = SignalKeyManager.getSignedPreKey(ctx);
        if (spk != null) result.add(spk);
        return result;
    }

    @Override
    public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
        try {
            SecurePrefs.get(ctx).edit()
                    .putString(SignalKeyManager.KEY_SIGNED_PREKEY,
                            Base64.encodeToString(record.serialize(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "storeSignedPreKey failed for id=" + signedPreKeyId, e);
        }
    }

    @Override
    public boolean containsSignedPreKey(int signedPreKeyId) {
        SignedPreKeyRecord spk = SignalKeyManager.getSignedPreKey(ctx);
        if (spk != null && spk.getId() == signedPreKeyId) return true;
        SignedPreKeyRecord prev = SignalKeyManager.getPrevSignedPreKey(ctx);
        return prev != null && prev.getId() == signedPreKeyId;
    }

    @Override
    public void removeSignedPreKey(int signedPreKeyId) {
        // Signed pre-keys are rotated on a schedule — removal is handled separately.
        Log.d(TAG, "removeSignedPreKey(" + signedPreKeyId + ") — scheduled for future rotation step.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SessionStore  (Double Ratchet session state → Room DB)
    // ══════════════════════════════════════════════════════════════════════════

    private static String toKey(SignalProtocolAddress address) {
        return address.getName() + "." + address.getDeviceId();
    }

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        String key = toKey(address);
        SignalSessionRecord row = AppDatabase.getInstance(ctx)
                .signalSessionDao().load(key);
        if (row == null) {
            return new SessionRecord(); // fresh (empty) session
        }
        try {
            return new SessionRecord(row.sessionData);
        } catch (Exception e) {
            Log.e(TAG, "Session deserialisation failed for <redacted> — returning fresh.", e);
            return new SessionRecord();
        }
    }

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        String key = toKey(address);
        try {
            SignalSessionRecord row = new SignalSessionRecord(
                    key, record.serialize(), System.currentTimeMillis());
            AppDatabase.getInstance(ctx).signalSessionDao().store(row);
        } catch (Exception e) {
            Log.e(TAG, "Failed to store session for <redacted>", e);
        }
    }

    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        String key = toKey(address);
        return AppDatabase.getInstance(ctx).signalSessionDao().count(key) > 0;
    }

    @Override
    public void deleteSession(SignalProtocolAddress address) {
        AppDatabase.getInstance(ctx).signalSessionDao().delete(toKey(address));
    }

    @Override
    public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) {
        List<SessionRecord> result = new ArrayList<>();
        for (SignalProtocolAddress address : addresses) {
            if (containsSession(address)) {
                result.add(loadSession(address));
            }
        }
        return result;
    }

    @Override
    public void deleteAllSessions(String name) {
        AppDatabase.getInstance(ctx).signalSessionDao().deleteAllForName(name);
    }

    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        List<String> addresses = AppDatabase.getInstance(ctx)
                .signalSessionDao().getAddressesForName(name);
        List<Integer> deviceIds = new ArrayList<>();
        for (String addr : addresses) {
            int dot = addr.lastIndexOf('.');
            if (dot >= 0) {
                try {
                    int deviceId = Integer.parseInt(addr.substring(dot + 1));
                    if (deviceId != 1) deviceIds.add(deviceId); // exclude primary device
                } catch (NumberFormatException ignored) {}
            }
        }
        return deviceIds;
    }

    // ── SenderKeyStore (unused in 1-to-1 mode; stubs satisfy the interface) ──

    @Override
    public void storeSenderKey(SignalProtocolAddress sender, UUID distributionId,
                               SenderKeyRecord record) {
        // No-op: sender keys are only needed for group messaging.
    }

    @Override
    public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
        try {
            return new SenderKeyRecord(new byte[0]);
        } catch (Exception e) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KyberPreKeyStore  (PQXDH last-resort Kyber-1024 pre-keys)
    //
    // DuoShield uses a single "last-resort" Kyber pre-key per device, mirroring
    // Signal's own PQXDH design.  Last-resort keys are never deleted after use —
    // markKyberPreKeyUsed() is intentionally a no-op.  The key is rotated on the
    // same 7-day schedule as the signed pre-key (see SignedPreKeyRotationWorker).
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId)
            throws InvalidKeyIdException {
        KyberPreKeyRecord kpk = SignalKeyManager.getKyberPreKey(ctx, kyberPreKeyId);
        if (kpk == null) {
            throw new InvalidKeyIdException("No Kyber pre-key found for id=" + kyberPreKeyId);
        }
        return kpk;
    }

    @Override
    public List<KyberPreKeyRecord> loadKyberPreKeys() {
        List<KyberPreKeyRecord> result = new ArrayList<>();
        KyberPreKeyRecord current = SignalKeyManager.getCurrentKyberPreKey(ctx);
        if (current != null) result.add(current);
        return result;
    }

    @Override
    public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
        try {
            SecurePrefs.get(ctx).edit()
                    .putString(SignalKeyManager.KEY_KYBER_PREKEY_PREFIX + kyberPreKeyId,
                            android.util.Base64.encodeToString(
                                    record.serialize(), android.util.Base64.NO_WRAP))
                    .putString(SignalKeyManager.KEY_KYBER_PREKEY_CURRENT_ID,
                            String.valueOf(kyberPreKeyId))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "storeKyberPreKey failed for id=" + kyberPreKeyId, e);
        }
    }

    @Override
    public boolean containsKyberPreKey(int kyberPreKeyId) {
        return SignalKeyManager.getKyberPreKey(ctx, kyberPreKeyId) != null;
    }

    /**
     * Last-resort Kyber pre-keys are never deleted — they persist until the next
     * scheduled rotation.  This matches Signal's own PQXDH behaviour: the key is
     * reused across multiple sessions rather than being consumed like a one-time key.
     */
    @Override
    public void markKyberPreKeyUsed(int kyberPreKeyId) {
        Log.d(TAG, "markKyberPreKeyUsed(" + kyberPreKeyId
                + ") — last-resort key; no action taken.");
    }
}
