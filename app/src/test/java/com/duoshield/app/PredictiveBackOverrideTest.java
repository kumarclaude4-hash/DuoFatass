package com.duoshield.app;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Regression guard for predictive back.
 *
 * <p>{@code AndroidManifest.xml} sets {@code android:enableOnBackInvokedCallback="true"}. From
 * Android 13 the framework's default back callback then calls a private
 * {@code Activity.onBackInvoked()}, NOT the public {@code Activity.onBackPressed()}, so any
 * activity that <i>overrides</i> {@code onBackPressed()} to block or redirect back is bypassed
 * by the back gesture / button. For {@code LockScreenActivity} that let back finish the lock
 * screen with {@code BaseActivity.lockScreenActive} still {@code true}, so the lock was never
 * re-shown.
 *
 * <p>Plain-JVM structural test (no Android SDK / Robolectric): it reads the sources off disk.
 * Screens that must intercept back register an {@code OnBackPressedCallback} through
 * {@code BackPressGuard} instead.
 */
public class PredictiveBackOverrideTest {

    /** Screens whose back handling is security- or flow-critical and must go through BackPressGuard. */
    private static final List<String> GUARDED = Arrays.asList(
            "LockScreenActivity.java",
            "DevicePinGateActivity.java",
            "SetupPinActivity.java",
            "ForcedPrimaryPinRotationActivity.java",
            "ForcedDuressRotationActivity.java",
            "PermissionsOnboardingActivity.java",
            "NoInternetActivity.java");

    private static final Pattern OVERRIDE_DECL =
            Pattern.compile("\\bvoid\\s+onBackPressed\\s*\\(");

    @Test
    public void noActivityOverridesOnBackPressed() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaSources()) {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
                if (OVERRIDE_DECL.matcher(t).find()) {
                    offenders.add(p.getFileName().toString());
                    break;
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("These classes override Activity.onBackPressed(), which predictive back bypasses "
                    + "on Android 13+. Use BackPressGuard.block()/onBack() instead: " + offenders);
        }
    }

    @Test
    public void guardedScreensRegisterThroughBackPressGuard() throws IOException {
        List<Path> sources = javaSources();
        for (String name : GUARDED) {
            Path match = null;
            for (Path p : sources) {
                if (p.getFileName().toString().equals(name)) { match = p; break; }
            }
            assertTrue(name + " not found under src/main/java", match != null);
            String src = new String(Files.readAllBytes(match), StandardCharsets.UTF_8);
            assertTrue(name + " must call BackPressGuard.block(...) or BackPressGuard.onBack(...)",
                    src.contains("BackPressGuard.block(") || src.contains("BackPressGuard.onBack("));
        }
    }

    @Test
    public void lockScreenBlocksBack() throws IOException {
        for (Path p : javaSources()) {
            if (p.getFileName().toString().equals("LockScreenActivity.java")) {
                String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                assertTrue("LockScreenActivity must swallow back with BackPressGuard.block(this)",
                        src.contains("BackPressGuard.block(this)"));
                return;
            }
        }
        fail("LockScreenActivity.java not found");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<Path> javaSources() throws IOException {
        // Gradle runs unit tests with the module directory (app/) as the working dir; fall back
        // to the repo root so the test also runs from an IDE configured at the root.
        File root = new File("src/main/java");
        if (!root.isDirectory()) root = new File("app/src/main/java");
        assertTrue("Could not locate src/main/java from " + new File(".").getAbsolutePath(),
                root.isDirectory());
        try (Stream<Path> s = Files.walk(root.toPath())) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }
}
