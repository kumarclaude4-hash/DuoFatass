package com.duoshield.app.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

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

        assertFalse("a failed disk commit must never be reported as a durable save", saved);
        // Android SharedPreferences mutates its in-memory map before writing the
        // disk file. A false commit therefore cannot promise that the old values
        // remain visible in this process; the production caller must keep its
        // generic failure path and must not claim persistence.
        assertEquals("the fake models Android's in-memory update semantics",
                "salt:hash", preferences.getString(SLOT, null));
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
