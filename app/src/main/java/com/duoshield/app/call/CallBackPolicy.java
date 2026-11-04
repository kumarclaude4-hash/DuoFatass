package com.duoshield.app.call;

/**
 * Pure decision logic for what back / Home does on the call screen.
 *
 * <p>Kept free of Android types so it can be unit-tested on the JVM. The original bug —
 * back ended the call — was a lifecycle accident ({@code finish()} → {@code onDestroy()} →
 * {@code hangup()}); encoding the rule here, plus the structural test that forbids
 * {@code hangup()} in the back path, keeps it from coming back.
 */
final class CallBackPolicy {

    enum Action {
        /** Nothing left to keep alive — just close the screen. */
        CLOSE,
        /** Shrink the live call into a Picture-in-Picture window. */
        ENTER_PIP,
        /**
         * Send the call's task to the back. CallActivity has its own task, so this reveals the
         * screen the user came from (or Home if there is none); the call keeps running behind its
         * ongoing notification and can be resumed from it or from Recents.
         */
        BACKGROUND
    }

    private CallBackPolicy() { }

    /**
     * What back should do. Never "hang up": only the End button / notification End / PiP End
     * end a call.
     *
     * @param callActive   the call is still live (not ENDED / FAILED)
     * @param pipAvailable this is a video call on a device that supports PiP
     */
    static Action onBack(boolean callActive, boolean pipAvailable) {
        if (!callActive) return Action.CLOSE;
        return pipAvailable ? Action.ENTER_PIP : Action.BACKGROUND;
    }

    /**
     * Whether Android 12+ may auto-enter PiP when the user goes Home. Off while a sub-screen
     * (in-call chat, a permission dialog) is launching, because that is not "leaving the call".
     */
    static boolean autoEnterPip(boolean callActive, boolean suppressed, boolean pipAvailable) {
        return pipAvailable && callActive && !suppressed;
    }
}
