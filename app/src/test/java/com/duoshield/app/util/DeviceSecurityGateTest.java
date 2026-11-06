package com.duoshield.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for the onboarding/restore secure-storage and migration policy. */
public class DeviceSecurityGateTest {

    @Test
    public void hardwareAndSoftwareTiersAllowWhenMigrationIsComplete() {
        assertEquals(DeviceSecurityGate.Decision.ALLOWED,
                DeviceSecurityGate.evaluateState(SecurePrefs.SecurityTier.HARDWARE, false));
        assertEquals(DeviceSecurityGate.Decision.ALLOWED,
                DeviceSecurityGate.evaluateState(SecurePrefs.SecurityTier.SOFTWARE, false));
    }

    @Test
    public void noDurableTierBlocksRegardlessOfMigrationFlag() {
        assertEquals(DeviceSecurityGate.Decision.BLOCKED_NO_DURABLE_STORE,
                DeviceSecurityGate.evaluateState(SecurePrefs.SecurityTier.NONE, false));
        assertEquals(DeviceSecurityGate.Decision.BLOCKED_NO_DURABLE_STORE,
                DeviceSecurityGate.evaluateState(SecurePrefs.SecurityTier.NONE, true));
        assertEquals(DeviceSecurityGate.Decision.BLOCKED_NO_DURABLE_STORE,
                DeviceSecurityGate.evaluateState(null, false));
    }

    @Test
    public void durableTierStillBlocksNewOnboardingWhenLegacyPlaintextRemains() {
        assertEquals(DeviceSecurityGate.Decision.BLOCKED_LEGACY_PLAINTEXT_REMAINS,
                DeviceSecurityGate.evaluateState(SecurePrefs.SecurityTier.HARDWARE, true));
        assertEquals(DeviceSecurityGate.Decision.BLOCKED_LEGACY_PLAINTEXT_REMAINS,
                DeviceSecurityGate.evaluateState(SecurePrefs.SecurityTier.SOFTWARE, true));
    }
}
