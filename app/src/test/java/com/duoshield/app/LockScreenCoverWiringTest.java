package com.duoshield.app;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Source-level regression guards for the lock transition's opaque content cover. */
public class LockScreenCoverWiringTest {

    @Test
    public void coverIsInstalledBeforeLockScreenLaunch() throws IOException {
        String source = read("src/main/java/com/duoshield/app/BaseActivity.java");
        int lockCheck = source.indexOf("if (AppLockManager.shouldLock(this))");
        int show = source.indexOf("LockScreenCover.show(this)", lockCheck);
        int launch = source.indexOf("startActivity(intent)", show);
        assertTrue("BaseActivity must retain the timed-lock check", lockCheck >= 0);
        assertTrue("opaque cover must be installed in the timed-lock branch", show > lockCheck);
        assertTrue("cover must be installed before LockScreenActivity is launched",
                launch > show);
    }

    @Test
    public void coverIsRemovedOnlyOnTheUnlockedBranch() throws IOException {
        String source = read("src/main/java/com/duoshield/app/BaseActivity.java");
        int lockCheck = source.indexOf("if (AppLockManager.shouldLock(this))");
        int lockCover = source.indexOf("LockScreenCover.show(this)", lockCheck);
        int unlockedBranch = source.indexOf("} else {", lockCover);
        int hide = source.indexOf("LockScreenCover.hide(this)", unlockedBranch);
        int offlineGate = source.indexOf("NetworkStateHelper.blockIfOffline(this)", hide);
        assertTrue("unlocked branch must follow the lock branch", unlockedBranch > lockCover);
        assertTrue("cover must be removed when the lock check confirms the app is unlocked",
                hide > unlockedBranch);
        assertTrue("cover removal must happen before normal unlocked content resumes",
                offlineGate > hide);
    }

    @Test
    public void coverHelperIsOpaqueInputBlockingAndIdempotent() throws IOException {
        String source = read("src/main/java/com/duoshield/app/util/LockScreenCover.java");
        assertTrue(source.contains("Color.BLACK"));
        assertTrue(source.contains("setClickable(true)"));
        assertTrue(source.contains("setFocusable(true)"));
        assertTrue(source.contains("findViewWithTag(TAG)"));
        assertTrue(source.contains("MATCH_PARENT"));
        assertTrue(source.contains("removeView(cover)"));
        assertFalse("cover must not clear FLAG_SECURE or mutate screenshot preference",
                source.contains("clearFlags") || source.contains("app_screenshot_enabled"));
    }

    private static String read(String relativePath) throws IOException {
        File file = new File(relativePath);
        if (!file.isFile()) file = new File("app/" + relativePath.replaceFirst("^src/", "src/"));
        if (!file.isFile()) {
            fail("Could not locate " + relativePath + " from " + new File(".").getAbsolutePath());
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
