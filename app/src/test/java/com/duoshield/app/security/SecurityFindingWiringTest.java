package com.duoshield.app.security;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Source-wiring regression tests for security helpers whose pure policy tests
 * cannot prove the Android call site actually invokes them.
 *
 * <p>These are JVM tests, not a substitute for later device acceptance. They
 * protect the fail-closed call ordering and shared validator/cache-clear wiring
 * from accidental removal while the app is still under active development.
 */
public class SecurityFindingWiringTest {

    @Test
    public void groupKeyFetchRejectsUntrustedCreatorBeforeDecrypting() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/GroupChatActivity.java");
        String guard = "if (!GroupKeyAuthorizationPolicy.isAuthorizedCreator(creatorUid, sender))";
        int guardAt = source.indexOf(guard);
        int decryptAt = source.indexOf("SignalCipherHelper.decrypt(", guardAt);

        assertTrue("group-key fetch must use the fail-closed creator policy", guardAt >= 0);
        assertTrue("the creator policy must run before encrypted group key decryption",
                decryptAt > guardAt);
    }

    @Test
    public void everyLocalWipePathClearsTheInProcessIdentityDerivationCache() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/WipeHelper.java");
        int eraseAt = source.indexOf("public static void eraseLocalData(");
        int clearAt = source.indexOf("SeedPhraseHelper.clearDerivationCache();", eraseAt);
        int nextPublicOperation = source.indexOf("public static void wipeAll(", eraseAt);

        assertTrue("eraseLocalData must exist", eraseAt >= 0);
        assertTrue("the derivation cache must be cleared inside eraseLocalData",
                clearAt > eraseAt && (nextPublicOperation < 0 || clearAt < nextPublicOperation));
    }

    @Test
    public void accountIdIsValidatedAtDeepLinkClipboardAndFirestoreUseBoundaries()
            throws IOException {
        String activity = readProjectFile(
                "app/src/main/java/com/duoshield/app/ui/AddContactActivity.java");
        String contacts = readProjectFile(
                "app/src/main/java/com/duoshield/app/contacts/ContactManager.java");

        assertTrue("deep-link path must pass through the canonical validator",
                activity.contains("AccountIdValidator.canonicalizeOrNull(rawUserId)"));
        assertTrue("clipboard input must pass through the same canonical validator",
                activity.contains("AccountIdValidator.canonicalizeOrNull(text.toString())"));
        assertTrue("the public compatibility pattern must delegate to the shared validator",
                contacts.contains("AccountIdValidator.pattern()"));
        assertTrue("manual/direct callers must be canonicalized before identity lookup",
                contacts.contains("String id = canonicalizeAccountId(partnerId);")
                        && contacts.contains("resolveIdentityWithRetry(myUid, id, callback"));
        assertTrue("the canonicalization method must delegate to AccountIdValidator",
                contacts.contains("return AccountIdValidator.canonicalizeOrNull(raw);"));
    }

    @Test
    public void pinLengthWritersRemoveLegacyMetadataInsteadOfPersistingIt() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/PinManager.java");

        assertTrue("account PIN writer must scrub the historical plaintext length key",
                source.contains("ed.remove(KEY_LEN_PREFIX + user.getUid())"));
        assertTrue("the device PIN writer must retain its dedicated length-key cleanup",
                source.contains("remove(KEY_DEVICE_PIN_LEN)"));
        assertTrue("account PIN length getter must be present",
                source.contains("public static int getPinLength(Context ctx)"));
        assertTrue("device PIN length getter must be present",
                source.contains("public static int getDevicePinLength(Context ctx)"));
        assertTrue("the fixed upper bound is the shared non-disclosing length",
                source.contains("return MAX_PIN_LEN;"));
    }

    @Test
    public void settingsPinChangeUsesAThrottleAndOneGenericFailureOutcome() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/ui/SecurityPrivacySettingsActivity.java");
        int saveAt = source.indexOf("private void doSavePin(String pin)");
        int throttleAt = source.indexOf("PinManager.tryRecordPinChangeAttempt(this)", saveAt);
        int duressAt = source.indexOf("DuressManager.isDuressPinOrPrefixOfIt(this, pin)", saveAt);
        int genericFailureAt = source.indexOf("Couldn't update your PIN. Try again later.", saveAt);
        int successAt = source.indexOf("R.string.settings_pin_set", saveAt);
        int nextMethodAt = source.indexOf("private void promptCurrentPin(", saveAt);

        assertTrue("Settings PIN change must record the attempt first", throttleAt > saveAt);
        assertTrue("the throttle must run before the secondary-code candidate check",
                duressAt > throttleAt);
        assertTrue("all rejected change paths must use one generic message",
                genericFailureAt > duressAt);
        assertTrue("the ordinary success path must remain available", successAt > genericFailureAt);
        assertTrue("the checked method body must be well bounded",
                nextMethodAt > successAt);
        String body = source.substring(saveAt, nextMethodAt);
        assertTrue("the old collision-specific response must be removed",
                !body.contains("That PIN can't be used"));
        assertTrue("the old collision-specific branch must be removed",
                !body.contains("else if (clashWithDuress)"));
    }

    private static String readProjectFile(String relativePath) throws IOException {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
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
