package com.duoshield.app.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * S10-N2 regression checks for release-surviving logs around user-scoped
 * Firestore requests and chat/media export. Diagnostics retain operation and
 * exception type without serialising raw user/path metadata.
 *
 * <p>Plain-JVM source checks: no Firebase initialization or Android device is
 * required. CI must compile and execute this test.
 */
public class ReleaseLogPrivacyTest {

    private static final String QUOTE_PLUS_EXCEPTION_MESSAGE =
            String.valueOf('"') + " + e.getMessage()";
    private static final String QUOTE_PLUS_CONVERSATION_ID =
            String.valueOf('"') + " + conversationId";
    private static final String QUOTE_PLUS_B2_PATH =
            String.valueOf('"') + " + b2Path";
    private static final String QUOTE_PLUS_EXCEPTION_TYPE =
            String.valueOf('"') + " + e.getClass().getSimpleName()";

    @Test
    public void contactLookupLogsOnlyExceptionType() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/contacts/ContactManager.java");
        assertTrue(source.contains("e.getClass().getSimpleName()"));
        assertFalse(source.contains(
                "resolveIdentityWithRetry: Firestore error, retrying — "
                        + QUOTE_PLUS_EXCEPTION_MESSAGE));
        assertFalse(source.contains(
                "fetchPartnerNameWithRetry: Firestore error — "
                        + QUOTE_PLUS_EXCEPTION_MESSAGE));
    }

    @Test
    public void presenceAndCallCleanupDoNotLogFirestoreExceptionText() throws IOException {
        String presence = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/PresenceThrottle.java");
        assertFalse(presence.contains(
                "Failed to write typing state: " + QUOTE_PLUS_EXCEPTION_MESSAGE));
        assertTrue(presence.contains("e.getClass().getSimpleName()"));

        String cleanup = readProjectFile(
                "app/src/main/java/com/duoshield/app/call/CallCleanupWorker.java");
        assertFalse(cleanup.contains(
                "Call cleanup failed — will retry next cycle: "
                        + QUOTE_PLUS_EXCEPTION_MESSAGE));
        assertTrue(cleanup.contains("e.getClass().getSimpleName()"));
    }

    @Test
    public void settingsAndExportLogsDoNotExposeUserScopedPaths() throws IOException {
        String settings = readProjectFile(
                "app/src/main/java/com/duoshield/app/ui/SettingsActivity.java");
        assertFalse(settings.contains(
                "propagateName non-critical: " + QUOTE_PLUS_EXCEPTION_MESSAGE));
        assertFalse(settings.contains(
                "propagateName query failed (non-critical): "
                        + QUOTE_PLUS_EXCEPTION_MESSAGE));

        String export = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/ChatExportHelper.java");
        assertFalse(export.contains(
                "Chat export failed for conv=" + QUOTE_PLUS_CONVERSATION_ID));
        assertFalse(export.contains("Failed to export media " + QUOTE_PLUS_B2_PATH));
        assertTrue(export.contains(
                "Chat export failed (" + QUOTE_PLUS_EXCEPTION_TYPE));
        assertTrue(export.contains(
                "Failed to export media (" + QUOTE_PLUS_EXCEPTION_TYPE));
    }

    @Test
    public void signalTrustMigrationLogsDoNotExposeRawPeerAddress() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java");
        assertTrue(source.contains("LogRedact.uid(address.getName())"));
        assertFalse(source.contains(
                "Migrated legacy uid-keyed trust record for \" + address"));
        assertFalse(source.contains(
                "Failed to deserialise stored identity for \" + address.getName()"));
    }

    private static String readProjectFile(String relativePath) throws IOException {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 7 && dir != null; i++) {
            File candidate = new File(dir, relativePath);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        throw new IOException("Could not locate " + relativePath
                + " from " + new File(".").getAbsolutePath());
    }
}
