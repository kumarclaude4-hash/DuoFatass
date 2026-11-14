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

        int accountSetAt = source.indexOf("public static boolean setPin(Context ctx, String pin)");
        int accountSetEnd = source.indexOf("// ── Wrong-PIN lockout", accountSetAt);
        String accountSetBody = source.substring(accountSetAt, accountSetEnd);
        assertTrue("account PIN writes must pass the UID-scoped plaintext length key to durable cleanup",
                accountSetBody.contains("KEY_LEN_PREFIX + user.getUid()")
                        && accountSetBody.contains("PinHashPersistence.persistSingle(")
                        && accountSetBody.contains("PinHashPersistence.persistPair("));

        int deviceSetAt = source.indexOf("public static boolean setDevicePin(Context ctx, String pin)");
        int deviceSetEnd = source.indexOf("public static int getDevicePinLength", deviceSetAt);
        String deviceSetBody = source.substring(deviceSetAt, deviceSetEnd);
        assertTrue("device PIN writes must pass their dedicated length key to durable cleanup",
                deviceSetBody.contains("KEY_DEVICE_PIN_LEN")
                        && deviceSetBody.contains("PinHashPersistence.persistSingle("));

        String persistence = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/PinHashPersistence.java");
        assertTrue("durable PIN writes must remove legacy keys in the same commit and verify cleanup",
                persistence.contains("for (String key : removalKeys)")
                        && persistence.contains("editor.remove(key)")
                        && persistence.contains("if (!isBlank(key) && preferences.contains(key)) return false;"));
        assertTrue("account PIN length getter must be present",
                source.contains("public static int getPinLength(Context ctx)"));
        assertTrue("device PIN length getter must be present",
                source.contains("public static int getDevicePinLength(Context ctx)"));
        assertTrue("the fixed upper bound is the shared non-disclosing length",
                source.contains("return MAX_PIN_LEN;"));
    }

    @Test
    public void duressCodeSaveReportsSuccessOnlyAfterSecureWrite() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/ui/ManageUnlockCodesActivity.java");
        int saveAt = source.indexOf("boolean saved = DuressManager.setDuressPin(this, codeToSave)");
        int failureAt = source.indexOf("if (!saved)", saveAt);
        int failureMessageAt = source.indexOf("Could not save. Try again.", failureAt);
        int failureReturnAt = source.indexOf("return;", failureMessageAt);
        int successMessageAt = source.indexOf("Toast.makeText(this, \"Code saved.\"", failureReturnAt);
        int finishAt = source.indexOf("finish();", successMessageAt);

        String manager = readProjectFile(
                "app/src/main/java/com/duoshield/app/security/DuressManager.java");
        assertTrue("secondary-code arming must reject an in-memory-only SecurePrefs tier",
                manager.contains("if (!SecurePrefs.getTier().isDurable()) return false;"));
        assertTrue("secondary-code arming must use the synchronous read-back persistence helper",
                manager.contains("DuressPinPersistence.persist("));
        assertTrue("the PBKDF2 salt and hash copies must be cleared after persistence",
                manager.contains("Arrays.fill(salt, (byte) 0)")
                        && manager.contains("Arrays.fill(hash, (byte) 0)"));

        assertTrue("the secure write result must be captured", saveAt >= 0);
        assertTrue("failed writes must take the explicit failure branch", failureAt > saveAt);
        assertTrue("the failure branch must show its generic retry message",
                failureMessageAt > failureAt);
        assertTrue("the failure branch must return before the success path",
                failureReturnAt > failureMessageAt && failureReturnAt < successMessageAt);
        assertTrue("success must be reported only after the failure branch returns",
                successMessageAt > failureReturnAt);
        assertTrue("the screen exits only after a successful save", finishAt > successMessageAt);
        assertTrue("the failure branch must not contain a finish call",
                !source.substring(failureAt, successMessageAt).contains("finish();"));
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

    @Test
    public void pinManagerUsesUidScopedDurablePinChangeThrottle() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/PinManager.java");
        assertTrue("Settings changes must use the dedicated durable throttle",
                source.contains("PinChangeThrottle.tryRecord("));
        int attemptAt = source.indexOf("public static boolean tryRecordPinChangeAttempt(Context ctx)");
        int prefsAt = source.indexOf("SharedPreferences preferences = SecurePrefs.get(ctx)", attemptAt);
        int throttleAt = source.indexOf("PinChangeThrottle.tryRecord(", prefsAt);
        assertTrue("the PIN throttle must resolve the account preference store first",
                prefsAt > attemptAt);
        assertTrue("the resolved durable storage tier must be supplied to the throttle",
                throttleAt > prefsAt
                        && source.substring(throttleAt, source.indexOf("} catch", throttleAt))
                                .contains("SecurePrefs.getTier()"));
        assertTrue("attempt state must be scoped to the current account",
                source.contains("scopedKey(KEY_PIN_CHANGE_PREFIX)"));
        assertTrue("a throttle storage error must fail closed",
                source.contains("return false;"));
        assertTrue("PIN-change cooldown must use the stable OS boot counter",
                source.contains("Settings.Global.BOOT_COUNT"));
        int legacyBootAt = source.indexOf("private static long bootId()");
        int pinChangeBootAt = source.indexOf("private static long pinChangeBootId(Context ctx)");
        int pinChangeAttemptAt = source.indexOf("public static boolean tryRecordPinChangeAttempt(Context ctx)");
        assertTrue("the legacy wrong-PIN boot-id helper must remain available", legacyBootAt >= 0);
        assertTrue("the stable boot-count helper must follow the legacy helper",
                pinChangeBootAt > legacyBootAt);
        assertTrue("the PIN-change attempt must follow and use its stable boot-count helper",
                pinChangeAttemptAt > pinChangeBootAt
                        && source.substring(pinChangeAttemptAt).contains("pinChangeBootId(ctx)"));

    }

    @Test
    public void firstTimeSetupPinCandidateCheckUsesThrottleAndGenericFailure() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/ui/SetupPinActivity.java");
        int clickAt = source.indexOf("btnContinue.setOnClickListener");
        int workerAt = source.indexOf("bgExecutor.execute(() -> {", clickAt);
        int throttleAt = source.indexOf("PinManager.tryRecordPinChangeAttempt(this)", workerAt);
        int duressAt = source.indexOf("DuressManager.isDuressPinOrPrefixOfIt(this, pin)", throttleAt);
        int setPinAt = source.indexOf("PinManager.setPin(this, pin)", duressAt);
        int failureAt = source.indexOf("Couldn't save your PIN. Try again later.", setPinAt);
        int successAt = source.indexOf("boolean unpaired = getIntent()", failureAt);

        assertTrue("setup must record a candidate attempt inside the worker", throttleAt > workerAt);
        assertTrue("setup must throttle before inspecting the preserved secondary code",
                duressAt > throttleAt);
        assertTrue("setup may write only after an allowed non-colliding candidate", setPinAt > duressAt);
        assertTrue("setup must use a generic failure before routing onward",
                failureAt > setPinAt && successAt > failureAt);
        String body = source.substring(workerAt, successAt);
        assertTrue("setup must not branch on the hidden-code collision result",
                !body.contains("if (clashWithSecondary)"));
        assertTrue("setup must not expose a collision-specific message",
                !body.contains("That PIN can't be used"));
    }

    @Test
    public void forcedPrimaryRotationUsesThrottleAndGenericFailure() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/ui/ForcedPrimaryPinRotationActivity.java");
        int clickAt = source.indexOf("btnContinue.setOnClickListener");
        int workerAt = source.indexOf("bgExecutor.execute(() -> {", clickAt);
        int throttleAt = source.indexOf("PinManager.tryRecordPinChangeAttempt(this)", workerAt);
        int duressAt = source.indexOf("DuressManager.isDuressPinOrPrefixOfIt(this, pin)", throttleAt);
        int setPinAt = source.indexOf("PinManager.setPin(this, pin)", duressAt);
        int failureAt = source.indexOf("Couldn't save your PIN. Try again later.", setPinAt);
        int successAt = source.indexOf("PendingLockStore.setRotationPrimaryDone(this, true)", failureAt);

        assertTrue("forced rotation must record the candidate attempt first", throttleAt > workerAt);
        assertTrue("forced rotation must throttle before inspecting the preserved secondary code",
                duressAt > throttleAt);
        assertTrue("forced rotation may write only after an allowed non-colliding candidate",
                setPinAt > duressAt);
        assertTrue("forced rotation must return through a generic failure before advancing",
                failureAt > setPinAt && successAt > failureAt);
        String body = source.substring(workerAt, successAt);
        assertTrue("forced rotation must not branch on the hidden-code collision result",
                !body.contains("if (clashWithSecondary)"));
        assertTrue("forced rotation must not expose a collision-specific message",
                !body.contains("That PIN can't be used"));
    }

    @Test
    public void primaryAndDevicePinWritesRequireDurableVerifiedPersistence() throws IOException {
        String manager = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/PinManager.java");
        int setAt = manager.indexOf("public static boolean setPin(Context ctx, String pin)");
        int setEnd = manager.indexOf("// ── Wrong-PIN lockout", setAt);
        String setBody = manager.substring(setAt, setEnd);
        assertTrue("primary PIN setup must use a durable single-store or recoverable paired write",
                setBody.contains("PinHashPersistence.persistSingle(")
                        && setBody.contains("PinHashPersistence.persistPair("));
        assertTrue("primary PIN setup must not use an asynchronous apply() write",
                !setBody.contains(".apply()"));
        assertTrue("primary PIN setup must recover a prior split-store transaction first",
                setBody.contains("PinHashPersistence.recoverPair("));

        int deviceSetAt = manager.indexOf("public static boolean setDevicePin(Context ctx, String pin)");
        int deviceSetEnd = manager.indexOf("public static int getDevicePinLength", deviceSetAt);
        String deviceSetBody = manager.substring(deviceSetAt, deviceSetEnd);
        assertTrue("device PIN setup must use the durable read-back helper",
                deviceSetBody.contains("PinHashPersistence.persistSingle("));
        assertTrue("device PIN setup must not report success from apply()",
                !deviceSetBody.contains(".apply()"));

        int promoteAt = manager.indexOf("public static boolean promoteDevicePinToCurrentUser(Context ctx)");
        int promoteEnd = manager.indexOf("public static boolean looksLikePreExistingDevice", promoteAt);
        String promoteBody = manager.substring(promoteAt, promoteEnd);
        assertTrue("device PIN promotion must require verified durable account persistence",
                promoteBody.contains("PinHashPersistence.persistSingle("));

        String persistence = readProjectFile(
                "app/src/main/java/com/duoshield/app/util/PinHashPersistence.java");
        assertTrue("paired writes must stage an account transaction record",
                persistence.contains("ACCOUNT_PENDING_HASH_PREFIX"));
        assertTrue("paired writes must retain a device-side recovery journal",
                persistence.contains("DEVICE_PENDING_TX"));
        assertTrue("paired writes must only activate after durable staging",
                persistence.contains("if (!accountStaged") && persistence.contains("if (!deviceStaged"));
        assertTrue("all reads must be able to recover an interrupted pair",
                manager.contains("private static boolean recoverPinSync(Context ctx)")
                        && manager.contains("PinHashPersistence.recoverPair("));

        String gate = readProjectFile(
                "app/src/main/java/com/duoshield/app/ui/DevicePinGateActivity.java");
        int saveAt = gate.indexOf("boolean saved = PinManager.setDevicePin(getApplicationContext(), pin)");
        int failureAt = gate.indexOf("if (!saved)", saveAt);
        int proceedAt = gate.indexOf("proceedToWelcome();", failureAt);
        assertTrue("device-gate setup must persist off the main thread",
                saveAt >= 0 && gate.contains("bgExecutor.execute(() -> {"));
        assertTrue("device-gate setup must stop instead of advancing after persistence failure",
                failureAt > saveAt && proceedAt > failureAt
                        && gate.substring(failureAt, proceedAt).contains("Couldn't securely save")
                        && gate.contains("btnContinue.setEnabled(false)"));
    }

    @Test
    public void duressTeardownAbortsBeforeSideEffectsWhenDeviceGatePromotionFails()
            throws IOException {
        String duress = readProjectFile(
                "app/src/main/java/com/duoshield/app/security/DuressManager.java");
        int methodAt = duress.indexOf(
                "public static boolean performLogout(Context context, String enteredPin)");
        int promotionGuardAt = duress.indexOf(
                "if (enteredPin == null || enteredPin.isEmpty()", methodAt);
        int promotionAt = duress.indexOf(
                "PinManager.setDevicePin(context, enteredPin)", promotionGuardAt);
        int failedReturnAt = duress.indexOf("return false;", promotionGuardAt);
        int clearAttemptsAt = duress.indexOf(
                "PinManager.clearFailedAttempts(context)", promotionAt);
        int unregisterAt = duress.indexOf("FcmUnregisterWorker.enqueue(appCtx)", methodAt);
        int disarmAt = duress.indexOf("clearDuressPin(context);", clearAttemptsAt);

        assertTrue("the boolean teardown operation must reject a missing recovery PIN",
                methodAt >= 0 && promotionGuardAt > methodAt);
        assertTrue("the device gate must be durably written before teardown side effects",
                promotionAt > promotionGuardAt && failedReturnAt > promotionAt
                        && failedReturnAt < unregisterAt);
        assertTrue("failed-attempt state must clear only after a confirmed device-gate write",
                clearAttemptsAt > promotionAt && clearAttemptsAt < unregisterAt);
        assertTrue("the secondary slot is disarmed only after successful promotion and before lock-intent recording",
                disarmAt > clearAttemptsAt && disarmAt < lockIntentAt);
        assertTrue("the compatibility overload cannot bypass the recovery-PIN requirement",
                duress.contains("public static boolean performLogout(Context context)"));
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

    @Test
    public void duressRecoveryPinIsPersistedOffMainThreadBeforeAnyWipeSideEffect()
            throws IOException {
        String lockScreen = readProjectFile(
                "app/src/main/java/com/duoshield/app/LockScreenActivity.java");
        int workerAt = lockScreen.indexOf("new Thread(() -> {");
        int wipeAt = lockScreen.indexOf("boolean wipe = duress && !real", workerAt);
        int logoutAt = lockScreen.indexOf("DuressManager.performLogout(this, entered)", wipeAt);
        int uiAt = lockScreen.indexOf("runOnUiThread(() -> {", wipeAt);

        assertTrue("duress classification must remain inside the PIN verification worker",
                workerAt >= 0 && wipeAt > workerAt);
        assertTrue("duress teardown must run before returning to the UI thread",
                logoutAt > wipeAt && uiAt > logoutAt);
        assertTrue("a failed durable promotion must leave the lock screen usable",
                lockScreen.contains("if (!teardownAccepted &&")
                        && lockScreen.contains("setInputEnabled(true)")
                        && lockScreen.contains("Unable to continue. Try again."));

        String manager = readProjectFile(
                "app/src/main/java/com/duoshield/app/security/DuressManager.java");
        int methodAt = manager.indexOf(
                "public static boolean performLogout(Context context, String enteredPin)");
        int promotionGuardAt = manager.indexOf("if (enteredPin == null || enteredPin.isEmpty()", methodAt);
        int promotionAt = manager.indexOf("PinManager.setDevicePin(context, enteredPin)", promotionGuardAt);
        int failedReturnAt = manager.indexOf("return false;", promotionGuardAt);
        int clearAttemptsAt = manager.indexOf("PinManager.clearFailedAttempts(context)", promotionAt);
        int unregisterAt = manager.indexOf("FcmUnregisterWorker.enqueue(appCtx)", methodAt);
        int lockIntentAt = manager.indexOf("PendingLockStore.recordLockIntent", unregisterAt);
        assertTrue("duress teardown must reject a missing plaintext recovery PIN", promotionGuardAt > methodAt);
        assertTrue("the device gate must be durably promoted before teardown proceeds",
                promotionAt > promotionGuardAt && failedReturnAt > promotionAt
                        && failedReturnAt < unregisterAt);
        assertTrue("failed-attempt state must clear only after successful promotion",
                clearAttemptsAt > promotionAt && clearAttemptsAt < unregisterAt);
        assertTrue("worker/lock-intent side effects must follow confirmed promotion",
                unregisterAt > clearAttemptsAt && lockIntentAt > unregisterAt);
        assertTrue("the compatibility overload must not bypass the promotion requirement",
                manager.contains("public static boolean performLogout(Context context)"));
    }

    @Test
    public void restoreTimeDevicePinPromotionRunsOffMainThreadAndRequiresSuccess()
            throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/ui/SeedPhraseDisplayActivity.java");
        int keyGenerationAt = source.indexOf("SignalKeyManager.generateFromSeedDerivedKey(");
        int workerAt = source.indexOf("new Thread(() -> {", keyGenerationAt);
        int hasDeviceAt = source.indexOf("PinManager.hasDevicePinSet(appContext)", workerAt);
        int promoteAt = source.indexOf("PinManager.promoteDevicePinToCurrentUser(appContext)", hasDeviceAt);
        int uiAt = source.indexOf("runOnUiThread(() -> {", promoteAt);
        int successfulRouteAt = source.indexOf("if (pinPromotionConfirmed)", uiAt);
        int setupRouteAt = source.indexOf("new Intent(this, SetupPinActivity.class)", successfulRouteAt);

        assertTrue("restore-time device PIN inspection must run off the main thread",
                keyGenerationAt >= 0 && workerAt > keyGenerationAt
                        && hasDeviceAt > workerAt && promoteAt > hasDeviceAt);
        assertTrue("account routing must return to the main thread after persistence completes",
                uiAt > promoteAt && successfulRouteAt > uiAt);
        assertTrue("the existing device PIN may skip setup only after confirmed promotion",
                successfulRouteAt > uiAt && setupRouteAt > successfulRouteAt);
        assertTrue("restore must not invoke synchronous promotion on the UI thread",
                !source.contains("PinManager.promoteDevicePinToCurrentUser(this)"));
    }
}
