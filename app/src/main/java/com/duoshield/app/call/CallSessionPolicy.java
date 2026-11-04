package com.duoshield.app.call;

/**
 * Pure decision logic for the call-session lifecycle, kept free of Android types so it can be
 * unit-tested on the JVM (the surrounding classes need libwebrtc / Firebase and cannot load there).
 */
final class CallSessionPolicy {

    private CallSessionPolicy() { }

    /**
     * Should a destroyed call screen leave the call running (detach) instead of hanging up?
     *
     * <p>Yes only when the call is connected and live and the user did not press End. The
     * screen can be destroyed for reasons that are not the user's wish to hang up: closing the
     * PiP window with the X, the system reclaiming it in the background, or a night-mode /
     * locale change recreating it. A call that is still ringing or connecting is not detached:
     * the no-answer watchdog and TURN wait live on the screen.
     */
    static boolean shouldDetachOnDestroy(boolean endedByUser, boolean wasConnected, boolean callActive) {
        return !endedByUser && wasConnected && callActive;
    }

    /**
     * Is a launch of the call screen a request to return to the live call (as opposed to a
     * second, new call)? Only one call can exist at a time.
     *
     * @param explicitResume       the intent carries {@link CallActivity#EXTRA_RESUME}
     * @param restoredInstance     the activity is being recreated from saved state
     * @param intentCallId         call id in the launching intent, may be {@code null} (caller side)
     * @param sessionCallId        id of the live call, may be {@code null}
     * @param sameCallerPartner    caller-side launch targeting the partner already in the live call
     */
    static boolean isResumeRequest(boolean explicitResume, boolean restoredInstance,
                                   String intentCallId, String sessionCallId,
                                   boolean sameCallerPartner) {
        if (explicitResume || restoredInstance || sameCallerPartner) return true;
        return intentCallId != null && intentCallId.equals(sessionCallId);
    }

    /**
     * Should returning to a minimised call show the lock screen? The call screen is not a
     * {@code BaseActivity}, so without this the call (and the in-call chat) would be reachable
     * from the notification after the lock timeout without a PIN.
     *
     * @param returning        coming back after the call screen was stopped, or re-adopted
     * @param shouldLock       {@code AppLockManager.shouldLock()}
     * @param lockScreenActive a lock screen is already up
     */
    static boolean shouldLockOnReturn(boolean returning, boolean shouldLock, boolean lockScreenActive) {
        return returning && shouldLock && !lockScreenActive;
    }

    /** {@code m:ss}, or {@code h:mm:ss} from one hour; negative input clamps to {@code 0:00}. */
    static String formatDuration(long millis) {
        long s = Math.max(0, millis) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
                     : String.format(java.util.Locale.US, "%d:%02d", m, sec);
    }
}
