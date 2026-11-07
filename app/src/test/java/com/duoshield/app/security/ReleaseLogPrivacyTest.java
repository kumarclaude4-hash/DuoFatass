package com.duoshield.app.security;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void groupKeyAndDistributionWarningsPseudonymisePeerIdentifiers() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/GroupChatActivity.java");

        assertTrue(source.contains("LogRedact.uid(sender)"));
        assertTrue(source.contains("LogRedact.uid(creatorUid)"));
        assertTrue(source.contains("LogRedact.uid(uid)"));
        assertFalse(source.contains("senderUid (" + String.valueOf('"') + " + sender"));
        assertFalse(source.contains("group creator (" + String.valueOf('"') + " + creatorUid"));
        assertFalse(source.contains("Key dist failed for " + String.valueOf('"') + " + uid"));
    }

    @Test
    public void metadataPseudonymizationIsStableOnlyWithinTheProcess() {
        String sensitive = "media/private-conversation/message-123.jpg";
        String first = com.duoshield.app.util.LogRedact.metadata(sensitive);
        String second = com.duoshield.app.util.LogRedact.metadata(sensitive);

        assertFalse("raw storage metadata must not be returned to logcat",
                first.contains(sensitive));
        assertTrue("metadata tags must use the compact pseudonym format",
                first.matches("m:[0-9a-f]{8}"));
        assertTrue("one process must be able to correlate the same value",
                first.equals(second));
        assertEquals("null metadata has a stable non-identifying sentinel",
                "m:none", com.duoshield.app.util.LogRedact.metadata(null));
    }

    @Test
    public void exceptionDiagnosticsExposeOnlyTheExceptionType() {
        String diagnostic = com.duoshield.app.util.LogRedact.error(
                new IllegalStateException("secret path and response body"));
        assertEquals("IllegalStateException", diagnostic);
        assertFalse(diagnostic.contains("secret"));
        assertEquals("unknown", com.duoshield.app.util.LogRedact.error(null));
    }

    @Test
    public void storageAndCleanupWarningsPseudonymisePathsAndOmitThrowablePayloads()
            throws IOException {
        String storage = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/B2StorageHelper.java");
        String cleanup = readProjectFile(
                "app/src/main/java/com/duoshield/app/db/B2CleanupWorker.java");
        String selfDestruct = readProjectFile(
                "app/src/main/java/com/duoshield/app/db/SelfDestructWorker.java");

        assertTrue(storage.contains("LogRedact.metadata(b2Path)"));
        assertTrue(cleanup.contains("LogRedact.metadata(b2Path)"));
        assertTrue(selfDestruct.contains("LogRedact.metadata(mediaPath)"));
        assertFalse(storage.contains("loadMedia failed: \" + b2Path"));
        assertFalse(cleanup.contains("B2 delete failed — will retry: \" + b2Path"));
        assertFalse(selfDestruct.contains("path=\" + mediaPath"));
    }

    @Test
    public void callAndBackupWarningsDoNotIncludeExceptionMessagesOrRawMessageIds()
            throws IOException {
        String calls = readProjectFile(
                "app/src/main/java/com/duoshield/app/call/CallSignalRepository.java");
        String backup = readProjectFile(
                "app/src/main/java/com/duoshield/app/backup/BackupManager.java");
        String builder = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/MessageBuilder.java");

        assertFalse(calls.contains("e.getMessage()"));
        assertFalse(backup.contains("e.getMessage()"));
        assertFalse(builder.contains("msgId=\" + msgId"));
        assertTrue(builder.contains("LogRedact.metadata(msgId)"));
    }

    @Test
    public void voicePlaybackLogsNeverIncludeAliasedExceptionMessages() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/ChatMediaActivity.java");
        assertTrue(source.contains("String detail = e != null && e.getMessage() != null"));
        assertTrue(source.contains("Log.e(TAG, \"Voice note B2 load failed (\""));
        assertTrue(source.contains("LogRedact.error(e) + \")\""));
        assertFalse(source.contains("Log.e(TAG, \"Voice note B2 load failed: \" + detail"));
    }

    @Test
    public void storageRetryLogsRedactHelperParameterDerivedFromObjectKeys() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/B2StorageHelper.java");
        assertTrue(source.contains("String safeOperation = redactOperationName(opName);"));
        assertTrue(source.contains("Log.w(TAG, safeOperation +"));
        assertTrue(source.contains("Log.e(TAG, safeOperation +"));
        assertFalse(source.contains("Log.w(TAG, opName +"));
        assertFalse(source.contains("Log.e(TAG, opName +"));
        assertTrue(source.contains(
                "return prefix + com.duoshield.app.util.LogRedact.metadata(sensitiveSuffix);"));
        assertTrue(source.contains("withRetry(\"download:\" + objectKey"));
    }

    @Test
    public void storageOperationLabelsPreserveOnlyTheNonSensitivePrefix() {
        assertEquals("download:m:01234567",
                com.duoshield.app.util.B2StorageHelper.redactOperationName(
                        "download:media/private/message-123"));
        assertEquals("storage operation",
                com.duoshield.app.util.B2StorageHelper.redactOperationName(null));
        String compound = com.duoshield.app.util.B2StorageHelper.redactOperationName(
                "worker-range:media/private/message-123:bytes=0-65535");
        assertTrue(compound.startsWith("worker-range:m:"));
        assertFalse(compound.contains("media/private/message-123"));
        assertFalse(compound.contains("bytes=0-65535"));
        assertEquals("worker-range:m:01234567",
                com.duoshield.app.util.B2StorageHelper.redactOperationName(
                        "worker-range:media/private/message-123:bytes=0-65535"));
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
