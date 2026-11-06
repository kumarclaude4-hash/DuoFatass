package com.duoshield.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for lossless legacy plaintext-preference migration. */
@RunWith(RobolectricTestRunner.class)
public class LegacyPlaintextMigratorTest {

    private static String uniqueFileName() {
        return "legacy-migration-test-" + UUID.randomUUID();
    }

    @Test
    public void mismatchedExistingEncryptedValueRetainsPlaintextForRecovery() {
        Context context = ApplicationProvider.getApplicationContext();
        String fileName = uniqueFileName();
        SharedPreferences raw = context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        raw.edit().putString("identity_key", "legacy-secret").commit();

        FakeSharedPreferences encrypted = new FakeSharedPreferences();
        encrypted.edit().putString("identity_key", "different-secret").commit();

        LegacyPlaintextMigrator.Result first =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(1, first.found);
        assertEquals("a conflicting target value is not a successful migration", 0, first.migrated);
        assertTrue(first.plaintextRemains);
        assertEquals("legacy-secret", raw.getString("identity_key", null));
        assertEquals("different-secret", encrypted.getString("identity_key", null));

        // Once the encrypted value is demonstrably identical, a retry may safely
        // treat it as already migrated and clear the legacy plaintext source.
        encrypted.edit().putString("identity_key", "legacy-secret").commit();
        LegacyPlaintextMigrator.Result second =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(1, second.migrated);
        assertFalse(second.plaintextRemains);
        assertFalse(raw.contains("identity_key"));
        assertEquals("legacy-secret", encrypted.getString("identity_key", null));
        raw.edit().clear().commit();
    }

    @Test
    public void migrationCopiesAndVerifiesEverySupportedPreferenceType() {
        Context context = ApplicationProvider.getApplicationContext();
        String fileName = uniqueFileName();
        SharedPreferences raw = context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        Set<String> labels = new HashSet<>(Arrays.asList("one", "two"));
        raw.edit()
                .putString("string", "secret")
                .putStringSet("set", labels)
                .putInt("int", 17)
                .putLong("long", 9_000_000_001L)
                .putFloat("float", 1.25f)
                .putBoolean("boolean", true)
                .commit();

        FakeSharedPreferences encrypted = new FakeSharedPreferences();
        LegacyPlaintextMigrator.Result result =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(6, result.found);
        assertEquals(6, result.migrated);
        assertFalse(result.plaintextRemains);
        assertTrue(raw.getAll().isEmpty());

        Map<String, ?> copied = encrypted.getAll();
        assertEquals("secret", copied.get("string"));
        assertEquals(labels, copied.get("set"));
        assertEquals(17, copied.get("int"));
        assertEquals(9_000_000_001L, copied.get("long"));
        assertEquals(1.25f, (Float) copied.get("float"), 0.0f);
        assertEquals(true, copied.get("boolean"));
        raw.edit().clear().commit();
    }

    @Test
    public void ephemeralTierRetainsPlaintextEvenAfterInMemoryCopy() {
        Context context = ApplicationProvider.getApplicationContext();
        String fileName = uniqueFileName();
        SharedPreferences raw = context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        raw.edit().putString("database_key", "legacy-db-secret").commit();

        FakeSharedPreferences ephemeral = new FakeSharedPreferences();
        LegacyPlaintextMigrator.Result result =
                LegacyPlaintextMigrator.migrate(context, fileName, ephemeral, false);

        assertEquals(1, result.migrated);
        assertTrue(result.plaintextRemains);
        assertEquals("legacy-db-secret", ephemeral.getString("database_key", null));
        assertEquals("legacy-db-secret", raw.getString("database_key", null));
        raw.edit().clear().commit();
    }
}
