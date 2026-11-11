package com.duoshield.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Regression guards for the user-directed app-wide screenshot policy.
 *
 * <p>Capture is intentionally allowed on every Activity until app finalization.
 * The Application lifecycle callback enforces that policy for Activities both
 * inside and outside the BaseActivity hierarchy. Android FLAG_SECURE must not
 * be reintroduced because it also blocks screen recording and recents capture.
 */
public class ScreenshotProtectionLifecycleTest {

    @Test
    public void applicationEnforcesAllowedPolicyForEveryCreatedAndResumedActivity() throws IOException {
        String app = read("src/main/java/com/duoshield/app/DuoShieldApp.java");
        assertTrue("DuoShieldApp must install the capture policy during process startup",
                app.contains("installScreenshotAllowedLifecycle();"));
        assertTrue("policy must be registered through Application lifecycle callbacks",
                app.contains("registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks()"));

        String created = methodBody(app, "public void onActivityCreated", "public void onActivityResumed");
        assertTrue("new Activities must have FLAG_SECURE cleared before their first visible frame",
                created.contains("BaseActivity.applyScreenshotSecurity(activity)"));

        String resumed = methodBody(app, "public void onActivityResumed", "public void onActivityStarted");
        assertTrue("returning Activities must have stale secure flags cleared again",
                resumed.contains("BaseActivity.applyScreenshotSecurity(activity)"));
    }

    @Test
    public void baseActivityAlwaysAllowsScreenshotsAndIgnoresLegacyPreference() throws IOException {
        String base = read("src/main/java/com/duoshield/app/BaseActivity.java");
        assertTrue(base.contains("SCREENSHOTS_ALLOWED_BY_DEFAULT = true"));
        String policy = methodBody(base, "static void applyScreenshotSecurity", "@Override");
        assertTrue("capture policy must clear FLAG_SECURE",
                policy.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"));
        assertFalse("capture policy must never add FLAG_SECURE",
                policy.contains("addFlags(WindowManager.LayoutParams.FLAG_SECURE)"));
        assertFalse("legacy screenshot preference must not control the capture policy",
                policy.contains("app_screenshot_enabled") || policy.contains("getBoolean"));
    }

    @Test
    public void settingsCannotDisableAppWideScreenshotPolicy() throws IOException {
        String settings = read("src/main/java/com/duoshield/app/ui/SecurityPrivacySettingsActivity.java");
        String layout = read("src/main/res/layout/activity_security_privacy_settings.xml");
        assertFalse(settings.contains("switchAppScreenshot"));
        assertFalse(settings.contains("app_screenshot_enabled"));
        assertFalse(settings.contains("applyScreenshotFlag"));
        assertFalse(layout.contains("switchAppScreenshot"));
        assertTrue(layout.contains("Screenshots allowed"));
        assertTrue(layout.contains("every screen"));
    }

    @Test
    public void applicationWindowSourcesDoNotReenableFlagSecure() throws IOException {
        String app = read("src/main/java/com/duoshield/app/DuoShieldApp.java");
        String base = read("src/main/java/com/duoshield/app/BaseActivity.java");
        String settings = read("src/main/java/com/duoshield/app/ui/SecurityPrivacySettingsActivity.java");
        String groupChat = read("src/main/java/com/duoshield/app/GroupChatActivity.java");
        String chatMedia = read("src/main/java/com/duoshield/app/ChatMediaActivity.java");
        for (String source : new String[]{app, base, settings, groupChat, chatMedia}) {
            assertFalse("No app Activity may re-enable FLAG_SECURE",
                    source.contains("addFlags(WindowManager.LayoutParams.FLAG_SECURE)")
                            || source.contains("addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)"));
        }
    }

    @Test
    public void manifestUsesApplicationWithGlobalLifecyclePolicy() throws IOException {
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue("manifest must install DuoShieldApp as the process Application",
                manifest.contains("android:name=\".DuoShieldApp\""));
    }

    private static String methodBody(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) return "";
        return source.substring(from, to);
    }

    private static String read(String relativePath) throws IOException {
        File file = new File(relativePath);
        if (!file.isFile()) file = new File("app/" + relativePath.replaceFirst("^src/", "src/"));
        if (!file.isFile()) throw new IOException("Could not locate " + relativePath);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
