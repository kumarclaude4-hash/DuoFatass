package com.duoshield.app.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.view.KeyEvent;
import android.app.Instrumentation;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.duoshield.app.R;
import com.duoshield.app.SignInActivity;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.LockScreenCover;
import com.duoshield.app.util.SecurePrefs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device regression coverage for the device-level PIN gate
 * (see PIN_GATE_FIX write-up + {@code duoshield-lockscreen-active} /
 * device-gate memory entries).
 *
 * <p>This complements, rather than duplicates, the plain-JVM
 * {@code DeviceGatePinIsolationTest} in {@code app/src/test}. That test
 * injects fake {@code SharedPreferences} through {@link SecurePrefs}'s
 * test-only override seam and proves the storage-isolation *logic* in
 * milliseconds without a device. It cannot prove the behaviour holds
 * against the real {@code AndroidKeyStore}-backed
 * {@code EncryptedSharedPreferences} stack, and it never renders
 * {@link DevicePinGateActivity} itself — so a bug in which layout gets
 * inflated for which mode would slip past it entirely. Running here, on a
 * real instrumentation target, closes both gaps.</p>
 *
 * <p>Two scenarios, matching the write-up's own "verification before
 * calling this done" checklist:</p>
 * <ol>
 *   <li>{@link #freshDevice_opensInSetupMode_andSavingAPinWorks()} — a
 *       device with no gate PIN yet must render the two-field "create a
 *       PIN" layout, and completing it must leave {@code hasDevicePinSet()}
 *       true.</li>
 *   <li>{@link #devicePin_survivesSimulatedDuressWipe_andReopensInVerifyMode()}
 *       — the load-bearing scenario. After a device PIN is set and the
 *       account-scoped store is wiped exactly the way
 *       {@code DuressManager.performLogout()} / {@code WipeHelper.wipeAll()}
 *       do it ({@code SecurePrefs.get(ctx).edit().clear().commit()} — never
 *       touching {@code SecurePrefs.getDeviceGate()}), the gate must reopen
 *       in VERIFY mode, not SETUP mode, and the original PIN must still be
 *       accepted end-to-end through the real numpad UI.</li>
 * </ol>
 */
@RunWith(AndroidJUnit4.class)
public class DevicePinGateInstrumentedTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        wipeBothGateFiles();
        Intents.init();

        // Stub the hand-off target. DevicePinGateActivity's own onCreate() picks
        // setup-vs-verify mode purely from PinManager state — letting the real
        // SignInActivity actually spin up here would drag in unrelated Firebase
        // startup behaviour this test has no interest in exercising, so intercept
        // the intent instead of letting it launch.
        intending(hasComponent(SignInActivity.class.getName()))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, null));
    }

    @After
    public void tearDown() {
        Intents.release();
        wipeBothGateFiles();
    }

    /** Clears both SecurePrefs-backed files so every test starts from a clean device. */
    private void wipeBothGateFiles() {
        SecurePrefs.get(appContext).edit().clear().commit();
        SecurePrefs.getDeviceGate(appContext).edit().clear().commit();
        SecurePrefs.reset();
    }

    @Test
    public void freshDevice_opensInSetupMode_andSavingAPinWorks() {
        assertTrue("precondition: no device PIN set yet", !PinManager.hasDevicePinSet(appContext));

        try (ActivityScenario<DevicePinGateActivity> scenario =
                     ActivityScenario.launch(DevicePinGateActivity.class)) {

            // Setup-mode-only view. Its mere presence proves activity_setup_pin.xml
            // (not activity_lock_screen.xml) was inflated — the two layouts don't
            // share this id — so this assertion alone distinguishes the two modes.
            onView(withId(R.id.etNewPin)).check(matches(isDisplayed()));

            onView(withId(R.id.etNewPin)).perform(replaceText("135790"), closeSoftKeyboard());
            onView(withId(R.id.etConfirmPin)).perform(replaceText("135790"), closeSoftKeyboard());
            onView(withId(R.id.btnContinue)).perform(click());

            // PIN derivation and the durable commit run on DevicePinGateActivity's
            // background executor. Espresso does not track that executor, so wait
            // for the resulting hand-off instead of asserting synchronously.
            waitForIntentTo(SignInActivity.class, 10_000);
        }

        assertTrue("PIN must be persisted after setup completes",
                PinManager.hasDevicePinSet(appContext));
        assertTrue("the just-set PIN must verify",
                PinManager.verifyDevicePin(appContext, "135790"));
    }

    @Test
    public void devicePin_survivesSimulatedDuressWipe_andReopensInVerifyMode() {
        final String originalPin = "482913";

        // 1. Fresh install sets a device-gate PIN — exercises DevicePinGateActivity's
        //    own setup path so this test doesn't just call PinManager directly.
        try (ActivityScenario<DevicePinGateActivity> scenario =
                     ActivityScenario.launch(DevicePinGateActivity.class)) {
            onView(withId(R.id.etNewPin)).perform(replaceText(originalPin), closeSoftKeyboard());
            onView(withId(R.id.etConfirmPin)).perform(replaceText(originalPin), closeSoftKeyboard());
            onView(withId(R.id.btnContinue)).perform(click());
            waitForIntentTo(SignInActivity.class, 10_000);
        }

        assertTrue(PinManager.hasDevicePinSet(appContext));

        // 2. Reproduce DuressManager.performLogout() / WipeHelper.wipeAll()'s wipe
        //    step VERBATIM: a full clear() of the account-scoped store only. Neither
        //    call site — nor this line — ever touches SecurePrefs.getDeviceGate().
        SecurePrefs.get(appContext).edit().clear().commit();
        SecurePrefs.reset();

        // 3. The device gate must survive its own account's wipe. Re-launch the
        //    activity exactly as a cold app launch would after the wipe.
        try (ActivityScenario<DevicePinGateActivity> scenario =
                     ActivityScenario.launch(DevicePinGateActivity.class)) {

            // Verify-mode-only view — activity_setup_pin.xml has no such id, so this
            // conclusively proves VERIFY mode (not SETUP mode) was rendered.
            onView(withId(R.id.pinDotsView)).check(matches(isDisplayed()));

            // Drive the real numpad UI for the original PIN. checkPin() verifies on a
            // background thread and posts the result back via runOnUiThread(), which
            // Espresso's default synchronization does not track — so poll briefly
            // for the resulting SignInActivity intent instead of asserting immediately.
            for (char c : originalPin.toCharArray()) {
                onView(withId(keyIdForDigit(c))).perform(click());
            }

            waitForIntentTo(SignInActivity.class, 5_000);
        }

        assertTrue("original device PIN must still verify after the wipe",
                PinManager.verifyDevicePin(appContext, originalPin));
    }

    @Test
    public void lockScreenCover_blocksUnderlyingPinSetup_andCanBeRemoved() {
        try (ActivityScenario<DevicePinGateActivity> scenario =
                     ActivityScenario.launch(DevicePinGateActivity.class)) {
            onView(withId(R.id.etNewPin)).perform(replaceText("135790"), closeSoftKeyboard());
            onView(withId(R.id.etConfirmPin)).perform(replaceText("135790"), closeSoftKeyboard());

            final int[] originalChildCount = new int[1];
            scenario.onActivity(activity -> {
                View content = activity.findViewById(android.R.id.content);
                assertTrue("android.R.id.content must be a ViewGroup", content instanceof ViewGroup);
                ViewGroup root = (ViewGroup) content;
                originalChildCount[0] = root.getChildCount();

                LockScreenCover.show(activity);
                assertEquals("cover must be added above the existing activity content",
                        originalChildCount[0] + 1, root.getChildCount());
                View cover = root.getChildAt(root.getChildCount() - 1);
                assertTrue("cover must consume touch input", cover.isClickable());
                assertTrue("cover must be focusable", cover.isFocusable());
                assertTrue("cover must use an opaque color background",
                        cover.getBackground() instanceof ColorDrawable);
                assertEquals("cover must be fully opaque black",
                        Color.BLACK, ((ColorDrawable) cover.getBackground()).getColor());

                LockScreenCover.show(activity);
                assertEquals("show() must be idempotent", originalChildCount[0] + 1,
                        root.getChildCount());
            });

            // Espresso still targets the underlying button coordinates; the topmost
            // clickable cover must receive the touch instead of the PIN setup screen.
            onView(withId(R.id.btnContinue)).perform(click());
            assertFalse("covered activity must not process PIN setup",
                    PinManager.hasDevicePinSet(appContext));

            scenario.onActivity(LockScreenCover::hide);
            onView(withId(R.id.btnContinue)).perform(click());
            waitForIntentTo(SignInActivity.class, 10_000);
            assertTrue("removing the cover must restore normal interaction",
                    PinManager.hasDevicePinSet(appContext));
        }
    }

    @Test
    public void devicePinGate_consumesPlatformBackDispatch() {
        try (ActivityScenario<DevicePinGateActivity> scenario =
                     ActivityScenario.launch(DevicePinGateActivity.class)) {
            onView(withId(R.id.etNewPin)).check(matches(isDisplayed()));

            // Send BACK through Android's input dispatcher, rather than calling
            // Activity.onBackPressed() directly. On API 33+ with
            // enableOnBackInvokedCallback=true this exercises the platform back
            // dispatch path that bypassed legacy Activity overrides.
            InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(activity -> {
                assertFalse("security gate must not finish on platform BACK",
                        activity.isFinishing());
                assertTrue("security gate must remain resumed after BACK",
                        activity.hasWindowFocus());
            });
            onView(withId(R.id.etNewPin)).check(matches(isDisplayed()));
        }
    }

    private static int keyIdForDigit(char digit) {
        switch (digit) {
            case '0': return R.id.key0;
            case '1': return R.id.key1;
            case '2': return R.id.key2;
            case '3': return R.id.key3;
            case '4': return R.id.key4;
            case '5': return R.id.key5;
            case '6': return R.id.key6;
            case '7': return R.id.key7;
            case '8': return R.id.key8;
            case '9': return R.id.key9;
            default: throw new IllegalArgumentException("Not a digit: " + digit);
        }
    }

    /**
     * Polls for an intent targeting {@code activityClass} to show up in Espresso-Intents'
     * captured list. Needed only for the verify-mode path, whose success callback arrives
     * via a background thread's runOnUiThread() post rather than a same-thread click
     * handler — see the class javadoc.
     */
    private static void waitForIntentTo(Class<?> activityClass, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                intended(hasComponent(activityClass.getName()));
                return; // matched — done
            } catch (Throwable notYet) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }
            }
        }
        fail("Timed out waiting for an intent targeting " + activityClass.getName()
                + " — verify-mode PIN entry never completed.");
    }
}
