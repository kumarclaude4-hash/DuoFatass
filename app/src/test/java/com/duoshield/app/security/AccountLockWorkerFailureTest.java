package com.duoshield.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Regression tests for the WorkManager duress-lock failure contract (S06-L4). */
public class AccountLockWorkerFailureTest {

    @Test
    public void onlyCommittedOrIdempotentlyConfirmedResponsesAreSuccess() {
        assertEquals(AccountLockHttpPolicy.Disposition.LOCK_CONFIRMED,
                AccountLockHttpPolicy.classify(200));
        assertEquals(AccountLockHttpPolicy.Disposition.LOCK_CONFIRMED,
                AccountLockHttpPolicy.classify(204));
    }

    @Test
    public void permanentClientFailuresDoNotBecomeSuccessfulWork() {
        for (int code : new int[] {400, 401, 403, 404, 409, 422}) {
            assertEquals("HTTP " + code,
                    AccountLockHttpPolicy.Disposition.PERMANENT_FAILURE,
                    AccountLockHttpPolicy.classify(code));
        }
    }

    @Test
    public void transientFailuresRemainRetryable() {
        for (int code : new int[] {408, 425, 429, 500, 502, 503, 504}) {
            assertEquals("HTTP " + code,
                    AccountLockHttpPolicy.Disposition.RETRYABLE_FAILURE,
                    AccountLockHttpPolicy.classify(code));
        }
    }

    @Test
    public void workerKeepsTheDurableIntentUnlessTheServerConfirmsTheLock() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/duoshield/app/security/AccountLockWorker.java");
        String permanent = section(source,
                "if (disposition == AccountLockHttpPolicy.Disposition.PERMANENT_FAILURE)",
                "return retryOrGiveUp");
        assertTrue(permanent.contains("return Result.failure()"));
        assertFalse("a rejected nonce must not clear the pending lock intent",
                permanent.contains("PendingLockStore.clearLockIntent"));
        assertTrue("a rejected nonce must invalidate the cached warm credential",
                permanent.contains("PendingLockStore.clearWarmToken"));

        String confirmed = section(source,
                "if (disposition == AccountLockHttpPolicy.Disposition.LOCK_CONFIRMED)",
                "if (disposition == AccountLockHttpPolicy.Disposition.PERMANENT_FAILURE)");
        assertTrue(confirmed.contains("PendingLockStore.clearLockIntent"));
        assertTrue(confirmed.contains("PendingLockStore.clearWarmToken"));
        assertTrue(confirmed.contains("return Result.success()"));

        assertTrue(source.contains("Missing nonce — account lock NOT confirmed"));
        assertTrue(source.contains("PUSH_SERVER_URL not configured — account lock NOT confirmed"));
        assertTrue(source.contains("return Result.failure()"));
    }

    @Test
    public void stalePendingIntentIsRetainedAndCanBeRefreshedOnlyForItsOriginalUid() throws IOException {
        String store = readProjectFile(
                "app/src/main/java/com/duoshield/app/security/PendingLockStore.java");
        assertTrue(store.contains("replaceIntentTokenIfSubjectMatches"));
        assertTrue(store.contains("clearLockIntentIfTokenMatches"));
        assertTrue(store.contains("clearLockIntentIfSubjectMatches"));
        assertTrue(store.contains("clearWarmTokenIfMatches"));
        assertTrue(store.contains("synchronized (LOCK_STATE_MUTEX)"));
        assertTrue(store.contains("uid.equals(prefs.getString(KEY_SUBJECT, null))"));
        assertTrue(store.contains("else ed.remove(KEY_INTENT_TOKEN)"));

        String manager = readProjectFile(
                "app/src/main/java/com/duoshield/app/security/DuressManager.java");
        assertTrue(manager.contains("LOCK_NONCE_REFRESH_INTERVAL_MS = 30L * 60 * 1000"));
        assertTrue(manager.contains("PendingLockStore.replaceIntentTokenIfSubjectMatches"));
        assertTrue(manager.contains("AccountLockWorker.enqueue(appCtx, nonce)"));

        String drain = section(manager,
                "public static boolean drainPendingLockIntent(Context context)",
                "private static int postDuressLock");
        assertTrue(drain.contains("PendingLockStore.clearLockIntentIfTokenMatches(appCtx, token)"));
        String rejected = section(drain,
                "else if (code >= 400 && code < 500",
                "else {");
        assertFalse("a permanent 4xx must not erase an unconfirmed lock intent",
                rejected.contains("PendingLockStore.clearLockIntent"));
        assertTrue(rejected.contains("account lock NOT confirmed"));
        assertTrue(rejected.contains("PendingLockStore.clearWarmTokenIfMatches(appCtx, token)"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        if (from < 0) throw new AssertionError("Missing source section: " + start);
        int to = source.indexOf(end, from + start.length());
        if (to < 0) throw new AssertionError("Missing source section end: " + end);
        return source.substring(from, to);
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
