package com.duoshield.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * S08-H2 regression tests for app-wide screenshot/screen-recording policy.
 *
 * <p>FLAG_SECURE belongs to each Activity window. BaseActivity alone cannot
 * enforce it for direct AppCompatActivity subclasses, so DuoShieldApp must
 * apply the same preference from the Application lifecycle to every screen.
 */
public class ScreenshotProtectionLifecycleTest {

    @Test
    public void applicationAppliesPolicyWhenEveryActivityIsCreatedAndResumed() throws IOException {
        String app = read("src/main/java/com/duoshield/app/DuoShieldApp.java");
        assertTrue("DuoShieldApp must install the lifecycle policy during process startup",
                app.contains("installScreenshotProtectionLifecycle();"));
        assertTrue("policy must be registered through Application lifecycle callbacks",
                app.contains("registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks()"));

        String created = methodBody(app, "public void onActivityCreated", "public void onActivityResumed");
        assertTrue("new Activities must receive FLAG_SECURE before first frame",
                created.contains("BaseActivity.applyScreenshotSecurity(activity)"));

        String resumed = methodBody(app, "public void onActivityResumed", "public void onActivityStarted");
        assertTrue("returning Activities must re-read the latest user preference",
                resumed.contains("BaseActivity.applyScreenshotSecurity(activity)"));
    }

    @Test
    public void policyDefaultsToSecureAndOnlyClearsOnExplicitOptIn() throws IOException {
        String base = read("src/main/java/com/duoshield/app/BaseActivity.java");
        assertTrue(base.contains("SCREENSHOTS_ALLOWED_BY_DEFAULT = false"));
        assertTrue(base.contains("getBoolean(KEY_APP_SCREENSHOT_ENABLED, SCREENSHOTS_ALLOWED_BY_DEFAULT)"));
        assertTrue(base.contains("addFlags(WindowManager.LayoutParams.FLAG_SECURE)"));
        assertTrue(base.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"));
    }

    @Test
    public void userSettingPersistsAndAppliesToCurrentWindow() throws IOException {
        String settings = read("src/main/java/com/duoshield/app/ui/SecurityPrivacySettingsActivity.java");
        assertTrue(settings.contains("putBoolean(\"app_screenshot_enabled\", checked)"));
        assertTrue(settings.contains("applyScreenshotFlag(checked)"));
        assertTrue(settings.contains("applyScreenshotFlag(desired)"));
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
