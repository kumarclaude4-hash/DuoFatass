package com.duoshield.app.call;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * S10-N2 regression guard: the incoming-call Firestore listener must never
 * interpolate a raw Firebase caller UID or backend exception text into a
 * release-surviving Log.w statement.
 *
 * <p>This is a plain JVM source test. It protects the exact logging boundary;
 * it does not require Firebase initialization or an Android device.
 */
public class IncomingCallWatcherLogRedactionTest {

    @Test
    public void callerUidAndFirestoreErrorDetailsAreNotLoggedRaw() throws IOException {
        String source = readWatcherSource();

        assertTrue(
                "caller UID must be pseudonymised before the release-surviving Log.w call",
                source.contains("LogRedact.uid(callerId)"));
        assertFalse(
                "raw callerId must not be concatenated into a log message",
                source.contains("\" + callerId +"));
        assertFalse(
                "Firestore exception text must not be copied into this UID-scoped log",
                source.contains("err.getMessage()") || source.contains("e.getMessage()"));
    }

    private static String readWatcherSource() throws IOException {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File moduleCandidate = new File(
                    dir, "src/main/java/com/duoshield/app/call/IncomingCallWatcher.java");
            if (moduleCandidate.isFile()) {
                return new String(Files.readAllBytes(moduleCandidate.toPath()), StandardCharsets.UTF_8);
            }
            File rootCandidate = new File(
                    dir, "app/src/main/java/com/duoshield/app/call/IncomingCallWatcher.java");
            if (rootCandidate.isFile()) {
                return new String(Files.readAllBytes(rootCandidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        throw new IOException("Could not locate IncomingCallWatcher.java from " + new File(".").getAbsolutePath());
    }
}
