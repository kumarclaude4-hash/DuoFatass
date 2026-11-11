package com.duoshield.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Runtime regressions for the app-level foreground reference count used by call screens.
 *
 * <p>In particular, moving from the main Activity into a call or its in-call chat must
 * not start the inactivity timer while at least one Activity remains started. Conversely,
 * only the transition from one started Activity to zero records the background timestamp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AppLockManagerLifecycleTest {

    private static final String PREFS = "duoshield_prefs";
    private static final String KEY_BACKGROUND_TIMESTAMP = "app_lock_bg_ts";

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test
    public void navigationBetweenActivitiesDoesNotStartBackgroundTimerUntilLastStop() {
        // Main activity + call activity are both started during navigation.
        AppLockManager.onActivityStarted();
        AppLockManager.onActivityStarted();

        // The originating Activity stops, but the call remains started.
        AppLockManager.onActivityStopped(context);
        assertEquals("navigation must not create a background timestamp",
                0L, prefs.getLong(KEY_BACKGROUND_TIMESTAMP, 0L));
        assertFalse(AppLockManager.hasBackgroundTimestamp(context));

        // Only the call stopping takes the app from one started Activity to zero.
        AppLockManager.onActivityStopped(context);
        long backgroundAt = prefs.getLong(KEY_BACKGROUND_TIMESTAMP, 0L);
        assertTrue("last Activity stop must record a background timestamp", backgroundAt > 0L);
        assertTrue(AppLockManager.hasBackgroundTimestamp(context));
    }

    @Test
    public void startingActivityDoesNotErasePendingTimestampBeforeLockDecision() {
        long pendingTimestamp = System.currentTimeMillis() - 60_000L;
        prefs.edit().putLong(KEY_BACKGROUND_TIMESTAMP, pendingTimestamp).commit();

        assertTrue(AppLockManager.hasBackgroundTimestamp(context));

        // This is the contract relied on by CallActivity: it evaluates shouldLock()
        // before an unlocked return calls onAppForegrounded().
        AppLockManager.onActivityStarted();
        assertEquals("onActivityStarted must not erase an older lock timestamp",
                pendingTimestamp, prefs.getLong(KEY_BACKGROUND_TIMESTAMP, 0L));
        assertTrue(AppLockManager.hasBackgroundTimestamp(context));

        AppLockManager.onActivityStopped(context);
        assertTrue("stopping a counted Activity preserves the fact that app is backgrounded",
                AppLockManager.hasBackgroundTimestamp(context));
    }

    @Test
    public void successfulForegroundReturnClearsPendingTimestamp() {
        prefs.edit().putLong(KEY_BACKGROUND_TIMESTAMP, System.currentTimeMillis() - 10_000L).commit();

        AppLockManager.onAppForegrounded(context);

        assertEquals(0L, prefs.getLong(KEY_BACKGROUND_TIMESTAMP, -1L));
        assertFalse(AppLockManager.hasBackgroundTimestamp(context));
    }
}
