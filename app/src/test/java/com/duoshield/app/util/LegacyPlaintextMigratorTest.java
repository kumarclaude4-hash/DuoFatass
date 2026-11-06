package com.duoshield.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;


import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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
        Context context = RuntimeEnvironment.getApplication();
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
    public void existingValueWithWrongTypeDoesNotCountAsMigrated() {
        Context context = RuntimeEnvironment.getApplication();
        String fileName = uniqueFileName();
        SharedPreferences raw = context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        raw.edit().putString("database_key", "legacy-db-secret").commit();

        FakeSharedPreferences encrypted = new FakeSharedPreferences();
        encrypted.edit().putInt("database_key", 42).commit();

        LegacyPlaintextMigrator.Result result =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(1, result.found);
        assertEquals(0, result.migrated);
        assertTrue(result.plaintextRemains);
        assertEquals("legacy-db-secret", raw.getString("database_key", null));
        assertEquals(42, encrypted.getInt("database_key", 0));
        raw.edit().clear().commit();
    }

    @Test
    public void migrationCopiesAndVerifiesEverySupportedPreferenceType() {
        Context context = RuntimeEnvironment.getApplication();
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
        Context context = RuntimeEnvironment.getApplication();
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

    @Test
    public void inaccessibleRawPreferencesFailClosed() {
        Context base = RuntimeEnvironment.getApplication();
        Context inaccessible = new ContextWrapper(base) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new IllegalStateException("storage unavailable");
            }
        };

        LegacyPlaintextMigrator.Result result =
                LegacyPlaintextMigrator.migrate(inaccessible, uniqueFileName(),
                        new FakeSharedPreferences(), true);

        assertTrue("unknown source state must keep the onboarding gate closed",
                result.plaintextRemains);
    }

    @Test
    public void failedRawPreferenceEnumerationFailsClosed() {
        Context base = RuntimeEnvironment.getApplication();
        SharedPreferences unreadable = new FakeSharedPreferences() {
            @Override
            public Map<String, ?> getAll() {
                throw new IllegalStateException("cannot enumerate");
            }
        };
        Context context = new ContextWrapper(base) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return unreadable;
            }
        };

        LegacyPlaintextMigrator.Result result =
                LegacyPlaintextMigrator.migrate(context, uniqueFileName(),
                        new FakeSharedPreferences(), true);

        assertTrue("failed enumeration cannot be interpreted as an empty source",
                result.plaintextRemains);
    }


    @Test
    public void failedEncryptedCommitRetainsPlaintextForRetry() {
        Context context = RuntimeEnvironment.getApplication();
        String fileName = uniqueFileName();
        SharedPreferences raw = context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        raw.edit().putString("identity_key", "legacy-secret").commit();

        CommitFaultPreferences encrypted = new CommitFaultPreferences();
        encrypted.failNextCommit();

        LegacyPlaintextMigrator.Result first =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(1, first.found);
        assertEquals(0, first.migrated);
        assertTrue(first.plaintextRemains);
        assertEquals("legacy-secret", raw.getString("identity_key", null));
        assertFalse(encrypted.contains("identity_key"));

        LegacyPlaintextMigrator.Result second =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(1, second.migrated);
        assertFalse(second.plaintextRemains);
        assertFalse(raw.contains("identity_key"));
        assertEquals("legacy-secret", encrypted.getString("identity_key", null));
        raw.edit().clear().commit();
    }

    @Test
    public void failedEncryptedReadBackRetainsPlaintextEvenAfterCommit() {
        Context context = RuntimeEnvironment.getApplication();
        String fileName = uniqueFileName();
        SharedPreferences raw = context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        raw.edit().putString("identity_key", "legacy-secret").commit();

        CommitFaultPreferences encrypted = new CommitFaultPreferences();
        encrypted.failReadBackAfterNextCommit();

        LegacyPlaintextMigrator.Result result =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(1, result.found);
        assertEquals(0, result.migrated);
        assertTrue(result.plaintextRemains);
        assertEquals("legacy-secret", raw.getString("identity_key", null));
        assertEquals("the write may have landed, but the migrator cannot prove it by read-back",
                "legacy-secret", encrypted.getString("identity_key", null));
        raw.edit().clear().commit();
    }

    @Test
    public void failedPlaintextCleanupCommitRetainsSourceAndAllowsSafeRetry() {
        Context base = RuntimeEnvironment.getApplication();
        String fileName = uniqueFileName();
        CommitFaultPreferences raw = new CommitFaultPreferences();
        raw.edit().putString("database_key", "legacy-db-secret").commit();
        raw.failNextCommit();

        Context context = new ContextWrapper(base) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return raw;
            }
        };
        FakeSharedPreferences encrypted = new FakeSharedPreferences();

        LegacyPlaintextMigrator.Result first =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(1, first.migrated);
        assertTrue(first.plaintextRemains);
        assertEquals("legacy-db-secret", raw.getString("database_key", null));
        assertEquals("legacy-db-secret", encrypted.getString("database_key", null));

        LegacyPlaintextMigrator.Result second =
                LegacyPlaintextMigrator.migrate(context, fileName, encrypted, true);

        assertEquals(1, second.migrated);
        assertFalse(second.plaintextRemains);
        assertFalse(raw.contains("database_key"));
        assertEquals("legacy-db-secret", encrypted.getString("database_key", null));
    }

    /**
     * Fault-injecting SharedPreferences used to distinguish a durable write from
     * a verified write and a completed cleanup. Failures are one-shot so the tests
     * can exercise a real retry against the same stores.
     */
    private static final class CommitFaultPreferences extends FakeSharedPreferences {
        private boolean failNextCommit;
        private boolean failReadBackAfterCommit;
        private boolean failNextReadBack;

        void failNextCommit() {
            failNextCommit = true;
        }

        void failReadBackAfterNextCommit() {
            failReadBackAfterCommit = true;
        }

        @Override
        public Map<String, ?> getAll() {
            if (failNextReadBack) {
                failNextReadBack = false;
                throw new IllegalStateException("injected read-back failure");
            }
            return super.getAll();
        }

        @Override
        public SharedPreferences.Editor edit() {
            SharedPreferences.Editor delegate = super.edit();
            return new SharedPreferences.Editor() {
                @Override public SharedPreferences.Editor putString(String key, String value) {
                    delegate.putString(key, value); return this;
                }
                @Override public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
                    delegate.putStringSet(key, values); return this;
                }
                @Override public SharedPreferences.Editor putInt(String key, int value) {
                    delegate.putInt(key, value); return this;
                }
                @Override public SharedPreferences.Editor putLong(String key, long value) {
                    delegate.putLong(key, value); return this;
                }
                @Override public SharedPreferences.Editor putFloat(String key, float value) {
                    delegate.putFloat(key, value); return this;
                }
                @Override public SharedPreferences.Editor putBoolean(String key, boolean value) {
                    delegate.putBoolean(key, value); return this;
                }
                @Override public SharedPreferences.Editor remove(String key) {
                    delegate.remove(key); return this;
                }
                @Override public SharedPreferences.Editor clear() {
                    delegate.clear(); return this;
                }
                @Override public boolean commit() {
                    if (failNextCommit) {
                        failNextCommit = false;
                        return false;
                    }
                    boolean committed = delegate.commit();
                    if (committed && failReadBackAfterCommit) {
                        failReadBackAfterCommit = false;
                        failNextReadBack = true;
                    }
                    return committed;
                }
                @Override public void apply() {
                    delegate.apply();
                }
            };
        }
    }

}
