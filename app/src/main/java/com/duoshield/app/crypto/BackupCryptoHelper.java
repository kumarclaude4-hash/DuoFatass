package com.duoshield.app.crypto;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.duoshield.app.util.SecurePrefs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives and manages the per-user backup encryption key.
 *
 * Key derivation (deterministic, never stored on server):
 *   mnemonic → 64-byte seed (PBKDF2-SHA512 via SeedPhraseHelper)
 *               → 32-byte AES key (HKDF-SHA256, info = "DUOSHIELD_BACKUP_V1")
 *
 * The 32-byte key is stored locally in SecurePrefs (EncryptedSharedPreferences
 * backed by Android Keystore).  It is re-derived from the seed phrase on restore.
 *
 * Encryption: AES-256-GCM, random 12-byte IV per message.
 * Wire format: Base64(IV) + ":" + Base64(ciphertext||GCM-tag)
 *
 * Compressed variant (preferred for new writes):
 *   plaintext → GZIP → AES-256-GCM → same wire format
 *   Indicated by a "compressed:true" field stored alongside in Firestore.
 *
 * <p><b>Associated data (S07-M3):</b> callers that also store metadata fields
 * (doc id, conversationId, the "compressed" flag itself, …) outside the
 * ciphertext should pass that metadata as {@code aad} to the {@code
 * *(..., byte[] aad)} overloads below. AES-GCM authenticates AAD without
 * encrypting it, so those fields become tamper-evident — flipping "compressed"
 * or splicing one document's ciphertext under a different id/conversationId
 * now fails the GCM tag check instead of silently succeeding. AAD-less callers
 * (and old stored blobs) keep working: passing {@code null}/empty AAD is
 * equivalent to the pre-existing behaviour.
 *
 * <p><b>Integrity (S07-H2):</b> new writes use {@link #computeHmac} — HMAC-SHA256
 * keyed with a value derived from the backup key via HKDF, distinct from the
 * AES-GCM key itself. {@link #computeChecksum}/{@link #verifyChecksum} (plain,
 * unkeyed SHA-256) are kept ONLY to verify documents written before this fix;
 * an unkeyed digest of plaintext is an offline oracle — anyone holding the
 * digest and a guessed plaintext can confirm the guess with no key at all —
 * so no new caller should compute one. New callers must use
 * {@link #computeHmac}/{@link #verifyHmac} instead.
 */
public final class BackupCryptoHelper {

    private static final String TAG          = "BackupCryptoHelper";
    public  static final String PREF_KEY     = "backup_key_b64";
    private static final String HKDF_INFO    = "DUOSHIELD_BACKUP_V1";
    private static final String HMAC_INFO    = "DUOSHIELD_BACKUP_HMAC_V1";
    private static final int    GCM_TAG_BITS = 128;
    private static final int    IV_LEN       = 12;

    private BackupCryptoHelper() {}

    // ── Key derivation ────────────────────────────────────────────────────────

    /**
     * Derives the 32-byte backup AES key from a 12-word BIP39 mnemonic.
     * Deterministic: same mnemonic always yields the same key.
     */
    public static byte[] deriveBackupKey(String mnemonic) throws Exception {
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic.trim());
        return SeedPhraseHelper.hkdfSha256(seed, HKDF_INFO.getBytes(StandardCharsets.UTF_8), 32);
    }

    /**
     * Derives the backup key from the mnemonic and stores it in SecurePrefs.
     * Call this at account creation and at restore time.
     */
    public static void storeKey(Context ctx, String mnemonic) {
        try {
            byte[] key = deriveBackupKey(mnemonic);
            boolean stored = SecurePrefs.get(ctx).edit()
                    .putString(PREF_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
                    .commit();
            if (!stored) {
                throw new IllegalStateException("Unable to persist backup key");
            }
        } catch (Exception e) {
            Log.e(TAG, "storeKey: derivation failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            throw new IllegalStateException("Unable to prepare encrypted backup restore", e);
        }
    }

    /**
     * Returns the stored 32-byte backup key, or null if not yet derived.
     * Callers must handle null (e.g. backup silently skipped).
     */
    public static byte[] getStoredKey(Context ctx) {
        if (!SecurePrefs.isAvailable()) return null;
        String b64 = SecurePrefs.get(ctx).getString(PREF_KEY, null);
        if (b64 == null) return null;
        try {
            return Base64.decode(b64, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "getStoredKey: decode failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    // ── Integrity checksum (LEGACY — unkeyed, do not use for new writes) ──────

    /**
     * @deprecated (S07-H2) Plain SHA-256 of plaintext with no key is an offline
     * dictionary-recovery oracle: anyone who has this digest and a guessed
     * plaintext can confirm the guess without ever touching the backup key.
     * Kept ONLY so {@link #verifyChecksum} can still validate documents that
     * were written before this fix shipped. New writes must call
     * {@link #computeHmac} instead — never call this for a new document.
     *
     * @return lowercase hex string of the SHA-256 digest
     */
    @Deprecated
    public static String computeChecksum(String plaintext) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
        return toHex(hash);
    }

    /**
     * @deprecated (S07-H2) Verifies the legacy unkeyed checksum format. Only
     * called on restore for old documents that have a "checksum" field but no
     * "hmac" field — see {@link #verifyHmac} for the current, keyed check.
     *
     * @param plaintext        the decrypted plaintext string
     * @param expectedChecksum hex SHA-256 digest previously stored in Firestore
     * @return true if checksums match; false on mismatch or computation failure
     */
    @Deprecated
    public static boolean verifyChecksum(String plaintext, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isEmpty()) return true; // legacy doc
        try {
            return expectedChecksum.equalsIgnoreCase(computeChecksum(plaintext));
        } catch (Exception e) {
            Log.e(TAG, "verifyChecksum: failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return false;
        }
    }

    // ── Integrity HMAC (S07-H2 fix — keyed, use for all new writes) ───────────

    /**
     * Derives the HMAC integrity key from the backup key via HKDF-SHA256 with
     * a distinct {@code info} string, so the HMAC key is never the same bytes
     * as the AES-GCM key even though both trace back to the same seed phrase.
     */
    private static byte[] deriveHmacKey(byte[] backupKey) throws Exception {
        return SeedPhraseHelper.hkdfSha256(
                backupKey, HMAC_INFO.getBytes(StandardCharsets.UTF_8), 32);
    }

    /**
     * Computes a keyed HMAC-SHA256 of the plaintext, replacing the legacy
     * unkeyed SHA-256 checksum (S07-H2). Without the backup key, an attacker
     * cannot use the stored tag to confirm a guessed plaintext offline — the
     * exact property the plain digest lacked.
     *
     * @return lowercase hex string of the HMAC-SHA256 tag
     */
    public static String computeHmac(byte[] backupKey, String plaintext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(deriveHmacKey(backupKey), "HmacSHA256"));
        byte[] tag = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return toHex(tag);
    }

    /**
     * Verifies a plaintext against a stored HMAC tag using a constant-time
     * comparison (avoids leaking a timing oracle on the tag bytes).
     *
     * @param expectedHmacHex hex HMAC-SHA256 tag previously stored in Firestore,
     *                        or null/empty for a legacy doc with no "hmac" field
     *                        (caller should fall back to {@link #verifyChecksum}).
     */
    public static boolean verifyHmac(byte[] backupKey, String plaintext, String expectedHmacHex) {
        if (expectedHmacHex == null || expectedHmacHex.isEmpty()) return false; // caller must use legacy path
        try {
            byte[] expected = hexToBytes(expectedHmacHex);
            byte[] actual   = hexToBytes(computeHmac(backupKey, plaintext));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            Log.e(TAG, "verifyHmac: failed" + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    // ── Associated data (S07-M3) ───────────────────────────────────────────────

    /**
     * Builds canonical AAD bytes binding a backup document's own id/context to
     * its ciphertext, so those fields (which otherwise live outside the AEAD
     * boundary as plain Firestore fields) are authenticated by the GCM tag.
     * Tampering with any of them — including flipping "compressed" — now fails
     * decryption instead of silently succeeding or misapplying the wrong
     * decompression path.
     */
    public static byte[] buildAad(String docId, String conversationId, boolean compressed) {
        String s = (docId == null ? "" : docId) + '\u0000'
                + (conversationId == null ? "" : conversationId) + '\u0000'
                + compressed;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── AES-256-GCM encrypt / decrypt (legacy — uncompressed) ─────────────────

    /**
     * Encrypts {@code plaintext} with AES-256-GCM using {@code key}.
     * Returns "ivBase64:ciphertextBase64" (ciphertext includes the GCM tag).
     *
     * Kept for backward compatibility when reading old backup docs that were
     * written without compression. For new writes use {@link #encryptCompressed}.
     */
    public static String encrypt(byte[] key, String plaintext) throws Exception {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] enc = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
    }

    /**
     * Decrypts a blob produced by {@link #encrypt}.
     */
    public static String decrypt(byte[] key, String blob) throws Exception {
        String[] parts = blob.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid backup blob format");

        byte[] iv  = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
    }

    // ── AES-256-GCM with GZIP compression (preferred for new writes) ──────────

    /**
     * GZIP-compresses {@code plaintext} then AES-256-GCM encrypts the result.
     *
     * Wire format is identical to {@link #encrypt} — "ivBase64:ciphertextBase64" —
     * so the same parsing logic applies. The caller must store {@code compressed:true}
     * in Firestore so the restore path knows to call {@link #decryptCompressed}.
     *
     * Expected size reduction: 40–60% for typical JSON message blobs.
     */
    public static String encryptCompressed(byte[] key, String plaintext) throws Exception {
        return encryptCompressed(key, plaintext, null);
    }

    /**
     * Same as {@link #encryptCompressed(byte[], String)} but also authenticates
     * {@code aad} (see {@link #buildAad}) via AES-GCM's associated-data input
     * (S07-M3). {@code aad} is never encrypted or stored in the wire format —
     * the caller must independently store/know it and pass the identical bytes
     * back into {@link #decryptCompressed(byte[], String, byte[])}, or the GCM
     * tag check fails.
     */
    public static String encryptCompressed(byte[] key, String plaintext, byte[] aad) throws Exception {
        // Step 1: GZIP compress
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        byte[] compressed = bos.toByteArray();

        // Step 2: AES-256-GCM encrypt
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        byte[] enc = cipher.doFinal(compressed);

        return Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
    }

    /**
     * Decrypts a blob produced by {@link #encryptCompressed(byte[], String)} and
     * GZIP-decompresses it. No AAD is checked — only for blobs written without one.
     */
    public static String decryptCompressed(byte[] key, String blob) throws Exception {
        return decryptCompressed(key, blob, null);
    }

    /**
     * Same as {@link #decryptCompressed(byte[], String)} but verifies {@code aad}
     * against the GCM tag (S07-M3). Must be called with the exact same AAD bytes
     * passed to {@link #encryptCompressed(byte[], String, byte[])} — a mismatch
     * (including a caller passing AAD for a blob that was written without one,
     * or vice versa) throws an AEAD authentication exception rather than
     * returning tampered/garbage plaintext.
     */
    public static String decryptCompressed(byte[] key, String blob, byte[] aad) throws Exception {
        String[] parts = blob.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid backup blob format");

        byte[] iv  = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);

        // Step 1: AES-256-GCM decrypt
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        byte[] compressed = cipher.doFinal(enc);

        // Step 2: GZIP decompress
        ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(bis)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gis.read(buf)) != -1) out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
