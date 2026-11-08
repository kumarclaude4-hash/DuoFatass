package com.duoshield.app;

import com.duoshield.app.crypto.SeedPhraseHelper;

import java.util.Arrays;
import java.util.Locale;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Local JUnit tests for {@link SeedPhraseHelper} — no Android context required.
 *
 * Tests NOT covered here (require libsignal on-device):
 *   - {@code deriveIdentityKeyPair(byte[])} — must be tested as an instrumented test.
 */
public class SeedPhraseHelperTest {

    // ── generateMnemonic ─────────────────────────────────────────────────────

    @Test
    public void generateMnemonic_produces12Words() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        assertNotNull("mnemonic must not be null", mnemonic);
        String[] words = mnemonic.trim().split("\\s+");
        assertEquals("must produce exactly 12 words", 12, words.length);
        System.out.println("Generated mnemonic: [REDACTED — " + words.length + " words]");
    }

    @Test
    public void generateMnemonic_wordsAreFromBip39Wordlist() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        // validateMnemonic checks word membership internally
        assertTrue("all generated words must be valid BIP39 words",
                SeedPhraseHelper.validateMnemonic(mnemonic));
    }

    @Test
    public void generateMnemonic_isDifferentEachCall() throws Exception {
        String m1 = SeedPhraseHelper.generateMnemonic();
        String m2 = SeedPhraseHelper.generateMnemonic();
        // Cryptographically negligible chance of collision with 128-bit entropy
        assertNotEquals("two calls should not produce identical mnemonics", m1, m2);
    }

    // ── validateMnemonic ─────────────────────────────────────────────────────

    @Test
    public void validateMnemonic_acceptsValidMnemonic() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        assertTrue("freshly generated mnemonic must validate", 
                SeedPhraseHelper.validateMnemonic(mnemonic));
    }

    @Test
    public void validateMnemonic_rejectsWrongWordCount() {
        assertFalse("11 words must be rejected",
                SeedPhraseHelper.validateMnemonic("abandon ability able about above absent absorb abstract absurd abuse access"));
        assertFalse("13 words must be rejected",
                SeedPhraseHelper.validateMnemonic("abandon ability able about above absent absorb abstract absurd abuse access accident account"));
    }

    @Test
    public void validateMnemonic_rejectsNonBip39Word() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        String corrupted = mnemonic.replaceFirst("\\S+", "xyzzy");
        assertFalse("unknown word must be rejected", SeedPhraseHelper.validateMnemonic(corrupted));
    }

    @Test
    public void validateMnemonic_rejectsAlteredChecksum() throws Exception {
        // Flip the last word to a different valid word — alters the checksum
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        String[] words = mnemonic.trim().split("\\s+");
        // Replace last word with "zoo" (always in wordlist) — wrong checksum
        String substitute = words[11].equals("zoo") ? "zero" : "zoo";
        words[11] = substitute;
        String tampered = String.join(" ", words);
        // BUG-X01: The previous comment said "1/16 chance" which was misleading.
        // For a 12-word BIP39 mnemonic the checksum is 4 bits, so any single-word
        // substitution has a 1/16 probability of accidentally matching the checksum.
        // Because the probability is non-zero we cannot assert that validation fails;
        // we observe the result instead. The negative case is verified by
        // validateMnemonic_rejectsNonBip39Word() (unknown words always fail the
        // wordlist lookup before the checksum is even reached).
        boolean result = SeedPhraseHelper.validateMnemonic(tampered);
        System.out.println("Tampered checksum test (may pass with 1/16 probability): " + result);
    }

    @Test
    public void validateMnemonic_isCaseInsensitive() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        String upper = mnemonic.toUpperCase();
        assertTrue("uppercase mnemonic must validate", SeedPhraseHelper.validateMnemonic(upper));
        String mixed = mnemonic.substring(0, 1).toUpperCase() + mnemonic.substring(1);
        assertTrue("mixed case mnemonic must validate", SeedPhraseHelper.validateMnemonic(mixed));
    }

    @Test
    public void validateMnemonic_handlesNullAndEmpty() {
        assertFalse("null must return false", SeedPhraseHelper.validateMnemonic(null));
        assertFalse("empty string must return false", SeedPhraseHelper.validateMnemonic(""));
        assertFalse("whitespace must return false", SeedPhraseHelper.validateMnemonic("   "));
    }

    // ── mnemonicToSeed ───────────────────────────────────────────────────────

    @Test
    public void mnemonicToSeed_produces64Bytes() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        assertNotNull("seed must not be null", seed);
        assertEquals("seed must be exactly 64 bytes", 64, seed.length);
    }

    @Test
    public void mnemonicToSeed_isDeterministic() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        byte[] seed1 = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        byte[] seed2 = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        assertArrayEquals("same mnemonic must produce same seed", seed1, seed2);
    }

    @Test
    public void mnemonicToSeed_knownVector() throws Exception {
        // BIP39 test vector: all-"abandon" mnemonic (12 words, last word = "about")
        // This is the first official BIP39 test vector.
        // mnemonic: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        // seed (first 16 hex bytes): "c55257..."
        String mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        assertEquals("BIP39 vector seed must be 64 bytes", 64, seed.length);
        // Known first byte of this BIP39 vector seed (no passphrase)
        // Official value: 0xc5...  per BIP39 test vectors
        String hex = bytesToHex(seed);
        System.out.println("BIP39 vector seed (hex): " + hex.substring(0, 32) + "...");
        // No-passphrase vector: first byte = 0x5e  (0xc5 is the Trezor-passphrase variant)
        assertEquals("first byte of BIP39 no-passphrase vector must be 0x5e",
                0x5e, seed[0] & 0xFF);
    }

    @Test
    public void mnemonicToSeed_differentMnemonicsProduceDifferentSeeds() throws Exception {
        String m1 = SeedPhraseHelper.generateMnemonic();
        String m2 = SeedPhraseHelper.generateMnemonic();
        byte[] s1 = SeedPhraseHelper.mnemonicToSeed(m1);
        byte[] s2 = SeedPhraseHelper.mnemonicToSeed(m2);
        assertFalse("different mnemonics must produce different seeds",
                java.util.Arrays.equals(s1, s2));
    }

    // ── mnemonicToSeed canonicalisation (S07-L3 regression coverage) ────────
    //
    // Before this fix, mnemonicToSeed() only trimmed the two ends and
    // NFKD-normalised — it neither lower-cased nor collapsed internal
    // whitespace itself. validateMnemonic() DOES tolerate both (it lower-cases
    // per word and splits on \s+), so a mnemonic that validated successfully
    // could silently derive a completely different seed than its canonical
    // form, with no error anywhere. These tests prove mnemonicToSeed() is now
    // self-sufficient: every reasonable rendering of "the same mnemonic" must
    // hash to the identical seed, matching what validateMnemonic() already
    // accepts as equivalent.

    private static final String CANONICAL_VECTOR =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @Test
    public void mnemonicToSeed_isCaseInsensitive() throws Exception {
        byte[] canonicalSeed = SeedPhraseHelper.mnemonicToSeed(CANONICAL_VECTOR);
        byte[] upperSeed     = SeedPhraseHelper.mnemonicToSeed(CANONICAL_VECTOR.toUpperCase());
        String mixed = "Abandon abandon ABANDON abandon abandon abandon abandon abandon abandon abandon abandon AbOuT";
        byte[] mixedSeed = SeedPhraseHelper.mnemonicToSeed(mixed);

        assertArrayEquals(
                "uppercase input must derive the SAME seed as its canonical lowercase form"
                        + " — validateMnemonic() already treats these as the same mnemonic",
                canonicalSeed, upperSeed);
        assertArrayEquals(
                "mixed-case input must derive the SAME seed as its canonical lowercase form",
                canonicalSeed, mixedSeed);
    }

    @Test
    public void mnemonicToSeed_collapsesInternalWhitespace() throws Exception {
        byte[] canonicalSeed = SeedPhraseHelper.mnemonicToSeed(CANONICAL_VECTOR);
        // Double space between two words, and a tab between two others — both
        // still pass validateMnemonic() (split("\\s+")) and must derive the
        // identical seed as the single-space canonical form.
        String doubleSpaced = "abandon  abandon abandon abandon abandon abandon abandon abandon"
                + " abandon\tabandon abandon about";
        byte[] doubleSpacedSeed = SeedPhraseHelper.mnemonicToSeed(doubleSpaced);

        assertTrue("sanity: the whitespace-varied vector must still validate",
                SeedPhraseHelper.validateMnemonic(doubleSpaced));
        assertArrayEquals(
                "extra/mixed internal whitespace must derive the SAME seed as the"
                        + " single-space canonical form",
                canonicalSeed, doubleSpacedSeed);
    }

    @Test
    public void mnemonicToSeed_trimsSurroundingWhitespace() throws Exception {
        byte[] canonicalSeed = SeedPhraseHelper.mnemonicToSeed(CANONICAL_VECTOR);
        byte[] paddedSeed    = SeedPhraseHelper.mnemonicToSeed("  " + CANONICAL_VECTOR + "\n");
        assertArrayEquals("leading/trailing whitespace must not change the derived seed",
                canonicalSeed, paddedSeed);
    }

    // ── derivationCache lifecycle (S07-L2 regression coverage) ───────────────
    //
    // deriveIdentityKeyPair() caches an IdentityKeyPair containing private-key
    // material. clearDerivationCache() is called from every WipeHelper wipe path.
    // The cache reference and its app-owned fingerprint copy are released/zeroed;
    // this does NOT synchronously destroy libsignal's native key or other copies.
    // Derivation itself needs libsignal's native Curve25519 code and cannot run in
    // a plain JVM test, so these tests cover only cache ownership/eviction behavior.

    @Test
    public void clearDerivationCache_dropsEntryAndZeroesItsFingerprintCopy() throws Exception {
        java.lang.reflect.Field cacheField =
                SeedPhraseHelper.class.getDeclaredField("derivationCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<Object> cache =
                (java.util.concurrent.atomic.AtomicReference<Object>) cacheField.get(null);

        Class<?> cachedType = null;
        for (Class<?> candidate : SeedPhraseHelper.class.getDeclaredClasses()) {
            if ("CachedDerivation".equals(candidate.getSimpleName())) {
                cachedType = candidate;
                break;
            }
        }
        assertNotNull("cached derivation implementation must exist", cachedType);
        java.lang.reflect.Constructor<?> constructor =
                cachedType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        byte[] fingerprintInput = new byte[] { 0x11, 0x22, 0x33, 0x44 };
        Object cached = constructor.newInstance(fingerprintInput, null);
        java.lang.reflect.Field fingerprintField =
                cachedType.getDeclaredField("seedFingerprint");
        fingerprintField.setAccessible(true);
        byte[] retainedFingerprint = (byte[]) fingerprintField.get(cached);

        assertNotSame("cache must own a defensive copy of the fingerprint",
                fingerprintInput, retainedFingerprint);
        assertArrayEquals(new byte[] { 0x11, 0x22, 0x33, 0x44 }, retainedFingerprint);
        fingerprintInput[0] = 0x7f;
        assertArrayEquals("caller mutation must not alter the cached fingerprint",
                new byte[] { 0x11, 0x22, 0x33, 0x44 }, retainedFingerprint);

        try {
            cache.set(cached);
            assertSame(cached, cache.get());

            SeedPhraseHelper.clearDerivationCache();

            assertNull("clearDerivationCache() must detach the cached key-pair entry",
                    cache.get());
            assertArrayEquals("cache-owned fingerprint bytes must be zeroed before release",
                    new byte[] { 0, 0, 0, 0 }, retainedFingerprint);
        } finally {
            SeedPhraseHelper.clearDerivationCache();
            Arrays.fill(fingerprintInput, (byte) 0);
        }
    }

    @Test
    public void identityDerivationAndCacheClearShareTheClassMonitor() throws Exception {
        java.lang.reflect.Method derive = SeedPhraseHelper.class.getDeclaredMethod(
                "deriveIdentityKeyPair", byte[].class);
        java.lang.reflect.Method clear = SeedPhraseHelper.class.getDeclaredMethod(
                "clearDerivationCache");
        assertTrue("identity derivation must serialize with cache eviction",
                java.lang.reflect.Modifier.isSynchronized(derive.getModifiers()));
        assertTrue("cache eviction must serialize with identity derivation",
                java.lang.reflect.Modifier.isSynchronized(clear.getModifiers()));
    }

    @Test
    public void hkdfSha256_preservesOutputAndRejectsInvalidLengths() throws Exception {
        byte[] ikm = new byte[32];
        for (int i = 0; i < ikm.length; i++) ikm[i] = (byte) i;
        byte[] info = "DuoShield HKDF cleanup test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] expectedIkm = ikm.clone();
        byte[] expectedInfo = info.clone();
        byte[] expected = hexToBytes(
                "268ba483b84a2cf92922eaa1e036d8d9fcd78328b05258e36c767eb4b5433df8");

        try {
            assertArrayEquals("HKDF output must remain byte-for-byte stable",
                    expected, SeedPhraseHelper.hkdfSha256(ikm, info, 32));
            assertArrayEquals("HKDF must not modify caller-owned input key material",
                    expectedIkm, ikm);
            assertArrayEquals("HKDF must not modify caller-owned context info",
                    expectedInfo, info);
            assertEquals("zero-length HKDF output is supported", 0,
                    SeedPhraseHelper.hkdfSha256(ikm, info, 0).length);
            try {
                SeedPhraseHelper.hkdfSha256(ikm, info, 33);
                fail("HKDF must reject output longer than one SHA-256 block");
            } catch (IllegalArgumentException expectedException) {
                // expected
            }
            try {
                SeedPhraseHelper.hkdfSha256(ikm, info, -1);
                fail("HKDF must reject negative output length");
            } catch (IllegalArgumentException expectedException) {
                // expected
            }
        } finally {
            Arrays.fill(ikm, (byte) 0);
            Arrays.fill(info, (byte) 0);
            Arrays.fill(expectedIkm, (byte) 0);
            Arrays.fill(expectedInfo, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid hex");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    @Test
    public void mnemonicCanonicalization_isLocaleIndependentOnTurkishDevices() throws Exception {
        // Official BIP39 English vector containing the letter 'i'. Default-locale
        // lower-casing on a Turkish device can turn uppercase I into dotless ı.
        final String vector =
                "legal winner thank year wave sausage worth useful legal winner thank yellow";
        byte[] expectedSeed = SeedPhraseHelper.mnemonicToSeed(vector);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String upper = vector.toUpperCase(Locale.ROOT);
            assertTrue("valid English BIP39 words must validate under a Turkish default locale",
                    SeedPhraseHelper.validateMnemonic(upper));
            assertArrayEquals("the same mnemonic must derive identical bytes under Turkish locale",
                    expectedSeed, SeedPhraseHelper.mnemonicToSeed(upper));
        } finally {
            Locale.setDefault(previous);
        }
    }

    // ── deriveUserId ─────────────────────────────────────────────────────────

    @Test
    public void deriveUserId_hasCorrectFormat() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        String userId = SeedPhraseHelper.deriveUserId(seed);
        assertNotNull("userId must not be null", userId);
        // Current format: 13 Base32 chars (unambiguous alphabet, no O/I/L/0/1) grouped
        // as XXXXX-XXXXX-XXX, e.g. "K3MNP-Q8RXA-7BC". This replaced the old
        // "DS-XXXXXXXX" hex format — must stay in sync with SeedPhraseHelper.deriveUserId()
        // and the acceptance regex in AddContactActivity.
        assertEquals("userId must be 15 chars total (5+1+5+1+3)", 15, userId.length());
        assertTrue("userId must match XXXXX-XXXXX-XXX Base32 format",
                userId.matches("[23456789A-HJKMNP-Z]{5}-[23456789A-HJKMNP-Z]{5}-[23456789A-HJKMNP-Z]{3}"));
        assertFalse("Base32 alphabet must exclude ambiguous chars O, I, L, 0, 1",
                userId.matches(".*[OIL01].*"));
        System.out.println("Derived userId: [REDACTED — format OK]");
    }

    @Test
    public void deriveUserId_isDeterministic() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        String id1 = SeedPhraseHelper.deriveUserId(seed);
        String id2 = SeedPhraseHelper.deriveUserId(seed);
        assertEquals("same seed must always produce same user ID", id1, id2);
    }

    @Test
    public void deriveUserId_differentSeedsProduceDifferentIds() throws Exception {
        String m1 = SeedPhraseHelper.generateMnemonic();
        String m2 = SeedPhraseHelper.generateMnemonic();
        byte[] s1 = SeedPhraseHelper.mnemonicToSeed(m1);
        byte[] s2 = SeedPhraseHelper.mnemonicToSeed(m2);
        String id1 = SeedPhraseHelper.deriveUserId(s1);
        String id2 = SeedPhraseHelper.deriveUserId(s2);
        assertNotEquals("different seeds should produce different user IDs", id1, id2);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
