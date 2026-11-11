package com.duoshield.app.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Persistence-contract tests for the secondary-code slot write. */
public class DuressPinPersistenceTest {

    private static final String SLOT = "pin_slot_b_test";
    private static final String ARMED = "pin_slot_b_armed_test";
    private static final String LEGACY = "duress_pin_hash";

    @Test
    public void successfulCommitReadsBackHashAndArmedBitAndRemovesLegacyKey() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        preferences.edit().putString(LEGACY, "legacy-value").commit();

        boolean saved = DuressPinPersistence.persist(
                preferences, SLOT, ARMED, LEGACY, "salt:hash");

        assertTrue(saved);
        assertTrue("the new hash must be persisted", "salt:hash".equals(
                preferences.getString(SLOT, null)));
        assertTrue("slot B must be armed", preferences.getBoolean(ARMED, false));
        assertFalse("the legacy hash must be removed in the same commit",
                preferences.contains(LEGACY));
    }

    @Test
    public void failedCommitNeverReportsSuccessfulArming() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        preferences.edit().putString(LEGACY, "legacy-value").commit();
        preferences.setCommitSucceeds(false);

        boolean saved = DuressPinPersistence.persist(
                preferences, SLOT, ARMED, LEGACY, "salt:hash");

        assertFalse(saved);
        assertFalse("a failed commit must not publish the new hash in the fake store",
                preferences.contains(SLOT));
        assertFalse("a failed commit must not publish the armed bit",
                preferences.getBoolean(ARMED, false));
        assertTrue("the old value must remain available for recovery",
                preferences.contains(LEGACY));
    }

    @Test
    public void incompleteReadbackNeverReportsSuccessfulArming() {
        FakeSharedPreferences preferences = new FakeSharedPreferences() {
            @Override
            public boolean getBoolean(String key, boolean defValue) {
                if (ARMED.equals(key)) return false;
                return super.getBoolean(key, defValue);
            }
        };

        assertFalse(DuressPinPersistence.persist(
                preferences, SLOT, ARMED, LEGACY, "salt:hash"));
    }

    @Test
    public void invalidInputsFailClosedWithoutWriting() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();

        assertFalse(DuressPinPersistence.persist(
                preferences, null, ARMED, LEGACY, "salt:hash"));
        assertFalse(DuressPinPersistence.persist(
                preferences, SLOT, ARMED, LEGACY, null));
        assertTrue(preferences.getAll().isEmpty());
    }
}
