package com.duoshield.app.crypto.signal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.duoshield.app.db.SignalSessionDao;
import com.duoshield.app.models.SignalSessionRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the security-sensitive Signal storage recovery paths.
 *
 * <p>These tests use Robolectric preferences and an in-memory DAO fake so they
 * exercise the production store logic without opening Android Keystore or the
 * SQLCipher database. They do not replace instrumented/device testing of the
 * native libsignal implementation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public class DuoShieldSignalStoreRegressionTest {

    private static final String PREFS_NAME = "signal-store-regression-test";
    private static final String LEGACY_PREFIX = "signal_trusted_id_";
    private static final String POINTER_PREFIX = "signal_identity_fp_ptr_";
    private static final String TRUST_BY_FP_PREFIX = "signal_trusted_key_fp_";

    private Context context;
    private SharedPreferences prefs;
    private InMemorySignalSessionDao sessionDao;
    private DuoShieldSignalStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        sessionDao = new InMemorySignalSessionDao();
        store = new DuoShieldSignalStore(context, prefs, sessionDao);
    }

    @After
    public void tearDown() {
        prefs.edit().clear().commit();
    }

    @Test
    public void firstContactWithoutPointerOrLegacyRecordRemainsTofuEligible() {
        SignalProtocolAddress address = address("first-contact");

        assertNull("genuine first contact has no previously stored identity",
                store.readTrustRecord(address));
    }

    @Test
    public void legacyTrustRecordMigratesToItsOwnFingerprintAndRemainsReadable() throws Exception {
        SignalProtocolAddress address = address("legacy-peer");
        byte[] serializedIdentity = new byte[] {
                0x05, 0x11, 0x23, 0x37, 0x42, 0x55, 0x68, 0x7a
        };
        String legacyB64 = Base64.encodeToString(serializedIdentity, Base64.NO_WRAP);
        String legacyKey = LEGACY_PREFIX + address.toString();
        prefs.edit().putString(legacyKey, legacyB64).commit();

        String expectedFingerprint = sha256Hex(serializedIdentity);
        String[] migrated = store.readTrustRecord(address);

        assertArrayEquals(
                "migration must preserve the legacy identity value and bind it to its fingerprint",
                new String[] {expectedFingerprint, legacyB64},
                migrated);
        assertEquals(legacyB64,
                prefs.getString(TRUST_BY_FP_PREFIX + expectedFingerprint, null));
        assertEquals(expectedFingerprint,
                prefs.getString(POINTER_PREFIX + address.toString(), null));
        assertEquals("migration must not delete the old value before the new pointer is durable",
                legacyB64, prefs.getString(legacyKey, null));

        // The fingerprint-keyed record is authoritative after migration; it
        // remains readable even when the legacy compatibility value is absent.
        prefs.edit().remove(legacyKey).commit();
        assertArrayEquals(migrated, store.readTrustRecord(address));
    }

    @Test
    public void danglingFingerprintPointerFailsClosedInsteadOfBecomingFirstContact() {
        SignalProtocolAddress address = address("dangling-peer");
        String fingerprint = "ffffffffffffffffffffffffffffffff"
                + "ffffffffffffffffffffffffffffffff";
        prefs.edit()
                .putString(POINTER_PREFIX + address.toString(), fingerprint)
                .commit();

        assertThrows(
                "a missing fingerprint target plus missing legacy record is corruption, not TOFU",
                DuoShieldSignalStore.TrustRecordCorruptionException.class,
                () -> store.readTrustRecord(address));
    }

    @Test
    public void malformedLegacyBase64FailsClosed() {
        SignalProtocolAddress address = address("malformed-legacy-peer");
        prefs.edit()
                .putString(LEGACY_PREFIX + address.toString(), "not-valid-base64!")
                .commit();

        assertThrows(
                DuoShieldSignalStore.TrustRecordCorruptionException.class,
                () -> store.readTrustRecord(address));
    }

    @Test
    public void malformedFingerprintStoredIdentityDoesNotBecomeTofu() {
        SignalProtocolAddress address = address("malformed-fingerprint-peer");
        String fingerprint = "test-fingerprint";
        String malformedKey = Base64.encodeToString(new byte[] {0x01}, Base64.NO_WRAP);
        prefs.edit()
                .putString(POINTER_PREFIX + address.toString(), fingerprint)
                .putString(TRUST_BY_FP_PREFIX + fingerprint, malformedKey)
                .commit();

        assertThrows(
                "a present but undecodable trusted key must not be reported as an absent identity",
                DuoShieldSignalStore.TrustRecordCorruptionException.class,
                () -> store.getIdentity(address));
    }

    @Test
    public void corruptPersistedRatchetRowIsDeletedAndTypedFailureIsRaised() {
        SignalProtocolAddress address = address("corrupt-ratchet-peer");
        String storageKey = address.getName() + "." + address.getDeviceId();
        // 0x0f has an invalid protobuf wire type and cannot be a serialized
        // libsignal SessionRecord.
        sessionDao.store(new SignalSessionRecord(storageKey, new byte[] {0x0f}, 123L));

        assertTrue(store.containsSession(address));
        assertThrows(
                DuoShieldSignalStore.SessionDeserializationException.class,
                () -> store.loadSession(address));

        assertFalse("the corrupt row must be removed so containsSession no longer lies",
                store.containsSession(address));
        assertNull("the corrupt serialized bytes must no longer remain in the DAO",
                sessionDao.load(storageKey));
    }

    @Test
    public void absentRatchetRowReturnsFreshSessionWithoutPersistingIt() {
        SignalProtocolAddress address = address("new-ratchet-peer");

        SessionRecord fresh = store.loadSession(address);

        assertNotNull(fresh);
        assertFalse("loading a genuinely absent session must not create a persisted row",
                store.containsSession(address));
    }

    private static SignalProtocolAddress address(String peer) {
        return new SignalProtocolAddress(peer, 1);
    }

    private static String sha256Hex(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    private static final class InMemorySignalSessionDao implements SignalSessionDao {
        private final Map<String, SignalSessionRecord> rows = new HashMap<>();

        @Override
        public SignalSessionRecord load(String address) {
            return rows.get(address);
        }

        @Override
        public void store(SignalSessionRecord record) {
            rows.put(record.address, record);
        }

        @Override
        public void delete(String address) {
            rows.remove(address);
        }

        @Override
        public void deleteAllForName(String namePrefix) {
            String prefix = namePrefix + ".";
            rows.keySet().removeIf(key -> key.startsWith(prefix));
        }

        @Override
        public void deleteAll() {
            rows.clear();
        }

        @Override
        public int count(String address) {
            return rows.containsKey(address) ? 1 : 0;
        }

        @Override
        public List<String> getAddressesForName(String namePrefix) {
            String prefix = namePrefix + ".";
            List<String> result = new ArrayList<>();
            for (String address : rows.keySet()) {
                if (address.startsWith(prefix)) result.add(address);
            }
            return result;
        }
    }
}
