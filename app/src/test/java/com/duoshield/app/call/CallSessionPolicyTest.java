package com.duoshield.app.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** Decision tables for the call-session lifecycle, plus the pure PiP crop math. */
public class CallSessionPolicyTest {

    // ── shouldDetachOnDestroy ────────────────────────────────────────────────

    @Test public void destroyedWhileConnected_detachesAndKeepsTheCall() {
        // PiP closed with the X, system reclaim, night-mode recreation.
        assertTrue(CallSessionPolicy.shouldDetachOnDestroy(false, true, true));
    }

    @Test public void userPressedEnd_neverDetaches() {
        assertFalse(CallSessionPolicy.shouldDetachOnDestroy(true, true, true));
    }

    @Test public void endedCall_neverDetaches() {
        assertFalse(CallSessionPolicy.shouldDetachOnDestroy(false, true, false));
    }

    @Test public void callThatNeverConnected_isNotDetached() {
        // The no-answer watchdog and TURN wait live on the screen, so ringing/connecting calls
        // keep the old behaviour.
        assertFalse(CallSessionPolicy.shouldDetachOnDestroy(false, false, true));
    }

    // ── isResumeRequest ──────────────────────────────────────────────────────

    @Test public void explicitResume_isResume() {
        assertTrue(CallSessionPolicy.isResumeRequest(true, false, null, "c1", false));
    }

    @Test public void recreatedInstance_isResume() {
        assertTrue(CallSessionPolicy.isResumeRequest(false, true, null, "c1", false));
    }

    @Test public void sameCallId_isResume() {
        assertTrue(CallSessionPolicy.isResumeRequest(false, false, "c1", "c1", false));
    }

    @Test public void callerRelaunchToSamePartner_isResume() {
        assertTrue(CallSessionPolicy.isResumeRequest(false, false, null, "c1", true));
    }

    @Test public void differentCallOrNoCallId_isASecondCall() {
        assertFalse(CallSessionPolicy.isResumeRequest(false, false, "c2", "c1", false));
        assertFalse(CallSessionPolicy.isResumeRequest(false, false, null, "c1", false));
        assertFalse(CallSessionPolicy.isResumeRequest(false, false, null, null, false));
    }

    // ── shouldLockOnReturn ───────────────────────────────────────────────────

    @Test public void lockOnlyOnReturnPastTheTimeoutWhenNotAlreadyLocked() {
        assertTrue(CallSessionPolicy.shouldLockOnReturn(true, true, false));
        assertFalse("first start never locks", CallSessionPolicy.shouldLockOnReturn(false, true, false));
        assertFalse("within the timeout", CallSessionPolicy.shouldLockOnReturn(true, false, false));
        assertFalse("lock screen already up", CallSessionPolicy.shouldLockOnReturn(true, true, true));
    }

    // ── formatDuration ───────────────────────────────────────────────────────

    @Test public void formatDuration() {
        assertEquals("0:00", CallSessionPolicy.formatDuration(0));
        assertEquals("0:00", CallSessionPolicy.formatDuration(-5000));
        assertEquals("0:59", CallSessionPolicy.formatDuration(59_999));
        assertEquals("1:05", CallSessionPolicy.formatDuration(65_000));
        assertEquals("59:59", CallSessionPolicy.formatDuration(3_599_000));
        assertEquals("1:00:00", CallSessionPolicy.formatDuration(3_600_000));
        assertEquals("2:03:04", CallSessionPolicy.formatDuration(7_384_000));
    }

    // ── CallPip.cropToPipAspect (9:16) ───────────────────────────────────────

    @Test public void crop_alreadyThePipAspect_isUnchanged() {
        assertTrue(Arrays.equals(new int[]{0, 0, 900, 1600}, CallPip.cropToPipAspect(0, 0, 900, 1600)));
    }

    @Test public void crop_tooTall_trimsTopAndBottomCentred() {
        // 900 x 2000 → 900 x 1600, 200 trimmed from each end.
        assertTrue(Arrays.equals(new int[]{0, 200, 900, 1800}, CallPip.cropToPipAspect(0, 0, 900, 2000)));
    }

    @Test public void crop_tooWide_trimsSidesCentred() {
        // 1600 x 1600 → 900 x 1600, 350 trimmed from each side.
        assertTrue(Arrays.equals(new int[]{350, 0, 1250, 1600}, CallPip.cropToPipAspect(0, 0, 1600, 1600)));
    }

    @Test public void crop_respectsOffsetOrigin() {
        int[] c = CallPip.cropToPipAspect(100, 50, 1000, 2050);   // 900 x 2000 at (100, 50)
        assertTrue(Arrays.equals(new int[]{100, 250, 1000, 1850}, c));
    }

    @Test public void crop_emptyRect_isNull() {
        assertTrue(CallPip.cropToPipAspect(10, 10, 10, 50) == null);
        assertTrue(CallPip.cropToPipAspect(10, 10, 50, 10) == null);
        assertTrue(CallPip.cropToPipAspect(50, 50, 10, 10) == null);
    }
}
