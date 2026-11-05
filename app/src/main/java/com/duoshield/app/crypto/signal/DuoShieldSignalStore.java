package com.duoshield.app.crypto.signal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.SignalSessionDao;
import com.duoshield.app.models.SignalSessionRecord;
import com.duoshield.app.util.LogRedact;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    // SecurePrefs key prefixes for trusted peer identities.
    //
    // S07-M2 fix: trust was previously anchored ONLY on this legacy, address-scoped
    // key (KEY_TRUSTED_IDENTITY_PREFIX + address, where address embeds Firebase's
    // mutable uid). If a uid were ever reassigned, or a user re-provisioned under
    // the same uid, stale trust recorded for "this uid" could silently apply to
    // whatever identity key next shows up at that uid — the trust record itself
    // carried no binding to the actual cryptographic identity it was meant to
    // protect. The two prefixes below anchor trust on the identity key's own
    // SHA-256 fingerprint (immutable — a new key always gets a new fingerprint,
    // and the same physical key always maps back to the same fingerprint even if
    // the uid pointing at it changes):
    //   - KEY_FP_POINTER_PREFIX + address  → fingerprint hex (a cache/index only;
    //     tells us which fingerprint slot currently answers for this address)
    //   - KEY_TRUST_BY_FP_PREFIX + fingerprint → base64 serialized identity key
    //     (the actual trust record; keyed on the immutable identity, not the uid)
    private static final String KEY_TRUSTED_IDENTITY_PREFIX = "signal_trusted_id_"; // legacy — read-only, for one-time migration
    private static final String KEY_FP_POINTER_PREFIX        = "signal_identity_fp_ptr_";
    private static final String KEY_TRUST_BY_FP_PREFIX        = "signal_trusted_key_fp_";

    private final Context ctx;
    private final SharedPreferences securePrefsOverride;
    private final SignalSessionDao sessionDaoOverride;

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
        this(ctx, null, null);
    }

    /**
     * Test seam for exercising the trust-migration and ratchet-store failure
     * paths without opening the device Keystore or the production SQLCipher DB.
     * Production construction continues to use SecurePrefs and AppDatabase.
     */
    DuoShieldSignalStore(Context ctx, SharedPreferences securePrefs,
                         SignalSessionDao sessionDao) {
        this.ctx = ctx.getApplicationContext();
        this.securePrefsOverride = securePrefs;
        this.sessionDaoOverride = sessionDao;
    }

    private SharedPreferences securePrefs() {
        return securePrefsOverride != null ? securePrefsOverride : SecurePrefs.get(ctx);
    }

    private SignalSessionDao sessionDao() {
        return sessionDaoOverride != null
                ? sessionDaoOverride : AppDatabase.getInstance(ctx).signalSessionDao();
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
    /**
     * S07-M2: SHA-256 hex fingerprint of a serialized identity key. This is the
     * immutable anchor trust is now keyed on — unlike a Firebase uid, it cannot
     * be reassigned to a different physical identity out from under a stored
     * trust record.
     */
    private static String fingerprintOf(byte[] serializedKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(serializedKey);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every Android API level DuoShield
            // targets; this branch is unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * S07-M2: reads the current trust record for {@code address}, transparently
     * migrating a legacy uid-keyed record (written before this fix) into the new
     * fingerprint-keyed storage on first read. After migration, the legacy key is
     * left in place (harmless, unread going forward) and every subsequent read/
     * write for this address goes through the fingerprint-keyed store.
     *
     * @return {@code null} if no trust record exists yet for this address (first
     *         contact); otherwise {fingerprintHex, base64(serializedKey)}.
     */
    String[] readTrustRecord(SignalProtocolAddress address) {
        SharedPreferences prefs = securePrefs();
        String pointerKey = KEY_FP_POINTER_PREFIX + address.toString();
        String fp = prefs.getString(pointerKey, null);
        if (fp != null) {
            String b64 = prefs.getString(KEY_TRUST_BY_FP_PREFIX + fp, null);
            if (b64 != null) return new String[]{fp, b64};
            // A dangling pointer is not a first-contact state. Try the legacy
            // record as a recoverable migration source, but never silently return
            // null when both representations are absent: callers interpret null
            // as TOFU and would otherwise accept an unverified replacement key.
        }
        // One-time migration: no usable fingerprint-keyed record yet — check the
        // legacy uid-keyed record and, if present, migrate it forward.
        String legacyKey = KEY_TRUSTED_IDENTITY_PREFIX + address.toString();
        String legacyB64 = prefs.getString(legacyKey, null);
        if (legacyB64 == null) {
            if (fp != null) {
                throw new TrustRecordCorruptionException(
                        "Stored identity trust pointer has no backing record");
            }
            return null; // genuinely first contact: neither pointer nor legacy record exists
        }
        final byte[] legacyBytes;
        try {
            legacyBytes = Base64.decode(legacyB64, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new TrustRecordCorruptionException(
                    "Legacy identity trust record is not valid Base64", e);
        }
        if (legacyBytes.length == 0) {
            throw new TrustRecordCorruptionException("Legacy identity trust record is empty");
        }
        String legacyFp = fingerprintOf(legacyBytes);
        prefs.edit()
            .putString(KEY_TRUST_BY_FP_PREFIX + legacyFp, legacyB64)
            .putString(pointerKey, legacyFp)
            .apply();
        Log.i(TAG, "Migrated legacy uid-keyed trust record for "
                + LogRedact.uid(address.getName()) + "." + address.getDeviceId()
                + " to fingerprint-keyed storage (S07-M2)");
        return new String[]{legacyFp, legacyB64};
    }

    @Override
    public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        SharedPreferences prefs = securePrefs();
        String[] existingRecord = readTrustRecord(address); // may migrate a legacy record
        String incomingB64 = Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP);
        String incomingFp  = fingerprintOf(identityKey.serialize());
        String pointerKey  = KEY_FP_POINTER_PREFIX + address.toString();

        if (existingRecord == null) {
            // F22 fix (still honoured): store the display-fingerprint key on first-use
            // too, not only on subsequent changes, so KeyFingerprintActivity has
            // something to show immediately after first pairing.
            // "signal_partner_identity_key_<name>" is a display-only cache read by
            // KeyFingerprintActivity/ChatMediaActivity — it is not used for trust
            // decisions, so it is untouched by the S07-M2 rekeying.
            prefs.edit()
                .putString(KEY_TRUST_BY_FP_PREFIX + incomingFp, incomingB64)
                .putString(pointerKey, incomingFp)
                .putString("signal_partner_identity_key_" + address.getName(), incomingB64)
                .apply();
            return true; // new identity — session can proceed
        }
        String existingB64 = existingRecord[1];
        if (!existingB64.equals(incomingB64)) {
            // S07-L4/S10-N2: Log.w survives release builds (proguard-rules.pro keeps
            // it deliberately for real failure diagnostics), so the peer uid must be
            // redacted here — never interpolate a raw uid into a Log.w/Log.e line.
            Log.w(TAG, "Identity key changed for " + LogRedact.uid(address.getName())
                    + " — storing new key (TOFU).");
            // Batch the SecurePrefs writes into one editor so only one apply() call
            // flushes to disk instead of three (BUG-CR03).
            prefs.edit()
                .putString(KEY_TRUST_BY_FP_PREFIX + incomingFp, incomingB64)
                .putString(pointerKey, incomingFp)
                .putString("signal_partner_identity_key_" + address.getName(), incomingB64)
                .apply();
            // The safety-number flag lives in a separate SharedPreferences file — must
            // be a separate apply() call.
            ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE)
               .edit().putBoolean("safety_num_changed_" + address.getName(), true).apply();
            // UX-1: the key the user previously verified is no longer the key in use, so any
            // recorded verification is void. Drop it here or the "Verified" badge would
            // outlive the key it vouched for.
            com.duoshield.app.util.VerificationStore
                    .clearVerification(ctx, address.getName());
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
     *
     * <p>S07-M2: the comparison itself is unchanged (still "does the incoming key
     * match what we last trusted for this address"); what changed is where that
     * "what we last trusted" record lives — see {@link #readTrustRecord}.
     */
    /**
     * Raised when a previously stored trust record is internally inconsistent
     * or cannot be decoded. It is intentionally distinct from {@code null},
     * which represents genuine first contact and permits TOFU.
     */
    public static final class TrustRecordCorruptionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        TrustRecordCorruptionException(String message) {
            super(message);
        }

        TrustRecordCorruptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
            // S07-L4/S10-N2: same redaction rationale as saveIdentity() above.
            Log.w(TAG, "Identity key changed for " + LogRedact.uid(address.getName())
                    + " — raising safety-number banner");
            ctx.getSharedPreferences("duoshield_prefs", android.content.Context.MODE_PRIVATE)
               .edit()
               .putBoolean("safety_num_changed_" + address.getName(), true)
               .apply();
            // UX-1: void any recorded verification — it vouched for the old key.
            com.duoshield.app.util.VerificationStore
                    .clearVerification(ctx, address.getName());
        }
        return trusted;
    }

    @Override
    public IdentityKey getIdentity(SignalProtocolAddress address) {
        String[] record = readTrustRecord(address); // transparently migrates legacy records
        if (record == null) return null;
        String b64 = record[1];
        try {
            return new IdentityKey(Base64.decode(b64, Base64.NO_WRAP), 0);
        } catch (Exception e) {
            // A corrupt stored trust record must not be reported as "no prior
            // identity": isTrustedIdentity treats null as first contact (TOFU).
            // Fail closed so corruption cannot silently downgrade trust.
            Log.e(TAG, "Failed to deserialise stored identity for "
                    + LogRedact.uid(address.getName()) + " ("
                    + e.getClass().getSimpleName() + "); refusing TOFU fallback.");
            throw new TrustRecordCorruptionException(
                    "Stored identity trust record is invalid", e);
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

    /**
     * S07-L4 / S10-N2: thrown by {@link #loadSession} when a stored session row
     * exists but fails to deserialize, instead of the old silent fallback to a
     * fresh, empty {@link SessionRecord}. That fallback let {@code
     * containsSession} keep reporting {@code true} (it only counts rows) for a
     * row that was actually unusable, so {@code establishSession}'s fast path
     * believed a session already existed, skipped X3DH renegotiation, and every
     * subsequent {@code encrypt} silently ran against an empty ratchet — a
     * ratchet reset with nothing telling the user or the peer why decryption
     * started failing. Callers must now handle this explicitly.
     */
    public static final class SessionDeserializationException extends RuntimeException {
        public SessionDeserializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        String key = toKey(address);
        SignalSessionRecord row = sessionDao().load(key);
        if (row == null) {
            return new SessionRecord(); // fresh (empty) session — genuinely no prior session
        }
        try {
            return new SessionRecord(row.sessionData);
        } catch (Exception e) {
            // S07-L4 fix: delete the corrupt row so containsSession() correctly
            // reports false afterward — the session can then be renegotiated
            // properly via X3DH instead of silently running against an empty,
            // never-persisted in-memory ratchet — and throw instead of returning
            // a fresh session, so the caller sees an explicit, typed failure
            // rather than a decryption/encryption that succeeds against the
            // wrong ratchet state with no indication anything went wrong.
            sessionDao().delete(key);
            // S07-L4/S10-N2: `key` embeds address.getName() (the peer uid) — redact
            // it before logging at a level (Log.e) that survives release builds.
            String redactedKey = LogRedact.uid(address.getName()) + "." + address.getDeviceId();
            Log.e(TAG, "Session deserialisation failed for " + redactedKey
                    + " — row deleted, session reset required.", e);
            throw new SessionDeserializationException(
                    "Session deserialisation failed for " + redactedKey, e);
        }
    }

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        String key = toKey(address);
        try {
            SignalSessionRecord row = new SignalSessionRecord(
                    key, record.serialize(), System.currentTimeMillis());
            sessionDao().store(row);
        } catch (Exception e) {
            // S07-L4/S10-N2: `key` embeds address.getName() (the peer uid) — redact
            // it before logging at a level (Log.e) that survives release builds.
            Log.e(TAG, "Failed to store session for " + LogRedact.uid(address.getName())
                    + "." + address.getDeviceId(), e);
        }
    }

    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        String key = toKey(address);
        return sessionDao().count(key) > 0;
    }

    @Override
    public void deleteSession(SignalProtocolAddress address) {
        sessionDao().delete(toKey(address));
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
        sessionDao().deleteAllForName(name);
    }

    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        List<String> addresses = sessionDao().getAddressesForName(name);
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
