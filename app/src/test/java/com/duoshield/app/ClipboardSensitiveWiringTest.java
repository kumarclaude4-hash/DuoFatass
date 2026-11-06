package com.duoshield.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** S08-L2 regression guard for every app-owned clipboard write carrying user data. */
public class ClipboardSensitiveWiringTest {

    @Test
    public void clipboardHelperMarksCopiedMessagesBeforePublishing() throws IOException {
        String source = read("src/main/java/com/duoshield/app/util/ClipboardHelper.java");
        assertBefore(source, "markSensitive(clip)", "cm.setPrimaryClip(clip)");
        assertTrue("sensitive metadata must be applied only where Android supports it",
                source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"));
        assertTrue("Android 13+ must receive EXTRA_IS_SENSITIVE",
                source.contains("ClipDescription.EXTRA_IS_SENSITIVE"));
    }

    @Test
    public void copiedAccountIdsAndPeerIdsAreMarkedSensitive() throws IOException {
        String adapter = read("src/main/java/com/duoshield/app/ui/MessageAdapter.java");
        assertBefore(adapter, "ClipboardHelper.markSensitive(clip)", "cm.setPrimaryClip(clip)");

        String contacts = read("src/main/java/com/duoshield/app/ui/AddContactActivity.java");
        assertBefore(contacts, "ClipboardHelper.markSensitive(clip)", "cm.setPrimaryClip(clip)");
    }

    @Test
    public void copiedChatMessagesAndUploadDiagnosticsAreMarkedSensitive() throws IOException {
        String media = read("src/main/java/com/duoshield/app/ChatMediaActivity.java");
        int firstWrite = media.indexOf("cm.setPrimaryClip(clip)");
        assertTrue("chat message clipboard write must be found", firstWrite >= 0);
        assertBefore(media, "ClipboardHelper.markSensitive(clip)", "cm.setPrimaryClip(clip)");

        int errorWrite = media.indexOf("cm.setPrimaryClip(errorClip)");
        int errorMark = media.indexOf("ClipboardHelper.markSensitive(errorClip)");
        assertTrue("upload diagnostics must be marked before publication",
                errorMark >= 0 && errorWrite > errorMark);
        assertTrue("diagnostic content must not be published inline without metadata",
                !media.contains("setPrimaryClip(android.content.ClipData.newPlainText"));
    }

    private static void assertBefore(String source, String marker, String operation) {
        int write = source.indexOf(operation);
        int sensitive = source.lastIndexOf(marker, write);
        assertTrue("missing clipboard write: " + operation, write >= 0);
        assertTrue("sensitive metadata must be set before " + operation,
                sensitive >= 0 && sensitive < write);
    }

    private static String read(String relativePath) throws IOException {
        File file = new File(relativePath);
        if (!file.isFile()) file = new File("app/" + relativePath.replaceFirst("^src/", "src/"));
        if (!file.isFile()) throw new IOException("Could not locate " + relativePath);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
