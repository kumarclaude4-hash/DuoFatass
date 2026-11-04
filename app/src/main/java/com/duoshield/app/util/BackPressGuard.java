package com.duoshield.app.util;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

/**
 * Registers back-press handling that keeps working with predictive back.
 *
 * <p>The manifest opts in to predictive back ({@code android:enableOnBackInvokedCallback="true"}).
 * From Android 13 the framework's default back callback then calls a private
 * {@code Activity.onBackInvoked()} directly — the public {@code Activity.onBackPressed()} is
 * <b>not</b> called, so a subclass that overrides it to <i>block</i> or <i>redirect</i> back is
 * silently bypassed. For the lock screen and the forced PIN screens that meant back could
 * dismiss a screen that exists precisely to be un-dismissable.
 *
 * <p>An {@link OnBackPressedCallback} on the activity's dispatcher is honoured on every API
 * level, with or without the opt-in, so screens that need to intercept back use this helper
 * instead of overriding {@code onBackPressed()}. A regression test
 * ({@code PredictiveBackOverrideTest}) fails the build if an override reappears.
 */
public final class BackPressGuard {

    private BackPressGuard() { }

    /** Swallows back: the screen cannot be dismissed with back / the back gesture. */
    public static void block(ComponentActivity activity) {
        onBack(activity, () -> { });
    }

    /**
     * Runs {@code action} instead of the default back behaviour. The callback is tied to the
     * activity's lifecycle and removed automatically when it is destroyed.
     */
    public static void onBack(ComponentActivity activity, Runnable action) {
        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                action.run();
            }
        });
    }
}
