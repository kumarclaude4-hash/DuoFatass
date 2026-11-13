package com.duoshield.app.util;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression tests for successful- and failed-candidate PIN-change throttling. */
public class PinChangeThrottleTest {

    private static final long WALL = 1_800_000_000_000L;
    private static final long ELAPSED = 50_000L;
    private static final long BOOT = 12345L;
    private static final String ACCOUNT_A = "pin_change_account_a";
    private static final String ACCOUNT_B = "pin_change_account_b";

    private FakeSharedPreferences preferences;

    @Before
    public void setUp() {
        preferences = new FakeSharedPreferences();
    }

    @Test
    public void ephemeralSecurePrefsTierCannotConsumeOrGrantAnAttempt() {
        assertFalse(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.NONE, ACCOUNT_A,
                WALL, ELAPSED, BOOT));
        assertTrue("ephemeral storage must not create a durable budget entry",
                preferences.getInt(ACCOUNT_A + "_count", 0) == 0);

        // A subsequent durable attempt remains the first allowed attempt.
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + 1, ELAPSED + 1, BOOT));
        assertTrue(preferences.getInt(ACCOUNT_A + "_count", 0) == 1);
    }

    @Test
    public void thirdChangeAttemptStartsCooldownAndFourthIsBlocked() {
        // Accepted and rejected candidates consume the same attempt allowance.
        assertTrue(PinChangeThrottle.tryRecord(preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL, ELAPSED, BOOT));
        assertTrue(PinChangeThrottle.tryRecord(preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + 1, ELAPSED + 1, BOOT));
        assertTrue(PinChangeThrottle.tryRecord(preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + 2, ELAPSED + 2, BOOT));
        assertFalse(PinChangeThrottle.tryRecord(preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + 3, ELAPSED + 3, BOOT));
    }

    @Test
    public void attemptWindowResetsWhenFirstTwoAttemptsAreMoreThanFifteenMinutesApart() {
        assertTrue(PinChangeThrottle.tryRecord(preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL, ELAPSED, BOOT));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + 10 * 60_000L, ELAPSED + 10 * 60_000L, BOOT));

        // More than 15 minutes after the first attempt begins a fresh window,
        // so this is attempt one rather than an immediate cooldown trigger.
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 1,
                ELAPSED + PinChangeThrottle.WINDOW_MS + 1,
                BOOT));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 2,
                ELAPSED + PinChangeThrottle.WINDOW_MS + 2,
                BOOT));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 3,
                ELAPSED + PinChangeThrottle.WINDOW_MS + 3,
                BOOT));
        assertFalse(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 4,
                ELAPSED + PinChangeThrottle.WINDOW_MS + 4,
                BOOT));
    }

    @Test
    public void attemptWindowCanStartAtBootElapsedTimeZero() {
        assertTrue(PinChangeThrottle.tryRecord(preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL, 0L, BOOT));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 1,
                PinChangeThrottle.WINDOW_MS + 1,
                BOOT));
    }

    @Test
    public void wallClockForwardJumpCannotResetWindowDuringSameBoot() {
        assertTrue(PinChangeThrottle.tryRecord(preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL, ELAPSED, BOOT));

        // A large wall-clock jump with the same stable boot counter does not
        // expire the attempt window; elapsedRealtime remains authoritative.
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 60 * 60_000L,
                ELAPSED + 1,
                BOOT));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 60 * 60_000L + 1,
                ELAPSED + 2,
                BOOT));
        assertFalse(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 60 * 60_000L + 2,
                ELAPSED + 3,
                BOOT));
    }

    @Test
    public void unavailableBootCounterUsesElapsedResetToDetectRebootForWindow() {
        assertTrue(PinChangeThrottle.tryRecord(preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL, ELAPSED, -1L));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + 1, ELAPSED + 1, -1L));

        // A reboot resets elapsedRealtime below the stored window anchor. When
        // BOOT_COUNT is unavailable, the helper falls back to wall time and
        // expires the old window instead of carrying attempts across the reboot.
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 1,
                1_000L,
                -1L));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 2,
                1_001L,
                -1L));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 3,
                1_002L,
                -1L));
        assertFalse(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.WINDOW_MS + 4,
                1_003L,
                -1L));
    }

    @Test
    public void unavailableBootCounterDoesNotCarryOldElapsedCooldownAcrossReboot() {
        for (int i = 0; i < PinChangeThrottle.MAX_ATTEMPTS; i++) {
            assertTrue(PinChangeThrottle.tryRecord(
                    preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + i, ELAPSED + i, -1L));
        }

        // The device reboots after the third attempt. Once the wall-clock
        // cooldown has elapsed, a reset elapsedRealtime value must not extend
        // the previous boot's deadline by the old uptime.
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.COOLDOWN_MS + 20,
                1_000L,
                -1L));
    }

    @Test
    public void cooldownIsPersistedAndWindowResetsAfterExpiry() {
        for (int i = 0; i < PinChangeThrottle.MAX_ATTEMPTS; i++) {
            assertTrue(PinChangeThrottle.tryRecord(
                    preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + i, ELAPSED + i, BOOT));
        }

        // Another helper invocation with the same preferences sees the durable
        // cooldown. The next attempt is refused until both clocks have passed.
        assertFalse(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + 10, ELAPSED + 10, BOOT));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences,
                SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.COOLDOWN_MS + 20,
                ELAPSED + PinChangeThrottle.COOLDOWN_MS + 20,
                BOOT));
    }

    @Test
    public void wallClockRollbackCannotShortenCooldownDuringSameBoot() {
        for (int i = 0; i < PinChangeThrottle.MAX_ATTEMPTS; i++) {
            assertTrue(PinChangeThrottle.tryRecord(
                    preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + i, ELAPSED + i, BOOT));
        }

        assertFalse(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL - 60_000L, ELAPSED + 5 * 60_000L, BOOT));

        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A,
                WALL + PinChangeThrottle.COOLDOWN_MS + 20,
                ELAPSED + PinChangeThrottle.COOLDOWN_MS + 20,
                BOOT));
    }

    @Test
    public void accountsHaveIndependentAttemptBudgets() {
        for (int i = 0; i < PinChangeThrottle.MAX_ATTEMPTS; i++) {
            assertTrue(PinChangeThrottle.tryRecord(
                    preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + i, ELAPSED + i, BOOT));
        }
        assertFalse(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_A, WALL + 4, ELAPSED + 4, BOOT));

        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_B, WALL + 4, ELAPSED + 4, BOOT));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_B, WALL + 5, ELAPSED + 5, BOOT));
        assertTrue(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_B, WALL + 6, ELAPSED + 6, BOOT));
        assertFalse(PinChangeThrottle.tryRecord(
                preferences, SecurePrefs.SecurityTier.SOFTWARE, ACCOUNT_B, WALL + 7, ELAPSED + 7, BOOT));
    }
}
