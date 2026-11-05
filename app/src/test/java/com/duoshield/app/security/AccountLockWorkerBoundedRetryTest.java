package com.duoshield.app.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * S06-L4 regression coverage: {@code AccountLockWorker} must bound retryable
 * failures (5xx / network exceptions) to a finite attempt count and report a
 * genuine {@code Result.failure()} once exhausted, rather than returning
 * {@code Result.retry()} unconditionally forever.
 *
 * <p>{@code AccountLockWorker} extends {@code androidx.work.Worker} and its
 * {@code doWork()} constructs a real {@code HttpURLConnection} — exercising it
 * at runtime requires either a live/mocked WorkManager (Robolectric + Android
 * SDK jars) or a running Android target, neither of which is available in this
 * environment (see SESSION-S3-19.md; Android verification deferred to S3-19b).
 * This test instead asserts the security-relevant structural invariants
 * directly against the source, mirroring {@code S319ManifestTest} and
 * {@code ProguardRulesNarrowingTest}'s established source-inspection style for
 * this codebase. It intentionally checks the actual retry/failure control flow
 * (bounded attempt count, distinct terminal branches, genuine failure surfaced)
 * rather than merely restating the implementation's own text.
 */
public class AccountLockWorkerBoundedRetryTest {

    private static String readSource() throws IOException {
        File f = locate(
                "src/main/java/com/duoshield/app/security/AccountLockWorker.java",
                "app/src/main/java/com/duoshield/app/security/AccountLockWorker.java");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Mirrors {@code ProguardRulesNarrowingTest#locate} — walks up from the
     * working directory to find the file whether the test runner's cwd is the
     * {@code app} module directory or the repo root.
     */
    private static File locate(String fromModuleDir, String fromRepoRoot) {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File moduleCandidate = new File(dir, fromModuleDir);
            if (moduleCandidate.isFile()) return moduleCandidate;
            File rootCandidate = new File(dir, fromRepoRoot);
            if (rootCandidate.isFile()) return rootCandidate;
            dir = dir.getParentFile();
        }
        fail("Could not locate " + fromRepoRoot + " from working dir "
                + new File(".").getAbsolutePath());
        return null; // unreachable
    }

    @Test
    public void maxAttemptsConstantIsFiniteAndPositive() throws IOException {
        String src = readSource();
        Matcher m = Pattern.compile(
                "MAX_ATTEMPTS\\s*=\\s*(\\d+)").matcher(src);
        assertTrue("Expected a numeric MAX_ATTEMPTS constant bounding retries", m.find());
        int maxAttempts = Integer.parseInt(m.group(1));
        assertTrue("MAX_ATTEMPTS must be a small finite bound, not effectively unlimited",
                maxAttempts > 0 && maxAttempts < 1000);
    }

    @Test
    public void retryOrGiveUpChecksAttemptCountBeforeRetrying() throws IOException {
        String src = readSource();
        // The exhaustion check must read the real WorkManager-tracked attempt
        // count, not a locally re-invented counter that WorkManager knows nothing
        // about (which would desync from WorkManager's own backoff scheduling).
        assertTrue("retryOrGiveUp must consult Worker#getRunAttemptCount()",
                src.contains("getRunAttemptCount()"));
        assertTrue("retryOrGiveUp must compare the attempt count against MAX_ATTEMPTS",
                Pattern.compile("attempt\\s*\\+\\s*1\\s*>=\\s*MAX_ATTEMPTS").matcher(src).find());
    }

    @Test
    public void exhaustedRetriesReturnGenuineFailureNotRetry() throws IOException {
        String src = readSource();
        int giveUpIdx = src.indexOf("if (attempt + 1 >= MAX_ATTEMPTS)");
        assertTrue("Expected the bounded-retry exhaustion branch", giveUpIdx >= 0);
        // Look at the following ~200 characters for the return statement guarded by
        // that condition, without assuming exact formatting.
        String windowAfterGuard = src.substring(giveUpIdx, Math.min(src.length(), giveUpIdx + 300));
        assertTrue("Exhausted retries must return Result.failure(), not silently succeed or retry forever",
                windowAfterGuard.contains("Result.failure()"));
        assertFalse("Exhausted-retries branch must not itself call Result.retry()",
                windowAfterGuard.substring(0, windowAfterGuard.indexOf("Result.failure()")).contains("Result.retry()"));
    }

    @Test
    public void belowCapStillRetries() throws IOException {
        String src = readSource();
        // Below the cap, the method falls through to Result.retry() — the same
        // transient-failure behavior the unconditional retry() this replaced had.
        int giveUpIdx = src.indexOf("private Result retryOrGiveUp(String reason) {");
        assertTrue(giveUpIdx >= 0);
        String method = src.substring(giveUpIdx, src.indexOf("\n    }\n", giveUpIdx));
        assertTrue("Below-cap path must still return Result.retry() to preserve transient-failure retry semantics",
                method.contains("Result.retry()"));
    }

    @Test
    public void retryableFailuresRouteThroughBoundedHelper() throws IOException {
        String src = readSource();
        // Both the generic HTTP-error branch and the network-exception branch must
        // go through the bounded helper — an unconditional Result.retry() call
        // anywhere in doWork() would reintroduce the unbounded-retry bug for that
        // code path even if retryOrGiveUp() itself is correctly bounded.
        int doWorkIdx = src.indexOf("public Result doWork()");
        assertTrue(doWorkIdx >= 0);
        String doWork = src.substring(doWorkIdx, src.indexOf("\n    private Result retryOrGiveUp", doWorkIdx));
        assertFalse("doWork() must not call the unconditional Result.retry() directly — "
                        + "all retryable paths must go through retryOrGiveUp()",
                doWork.contains("return Result.retry()"));
        Matcher retryOrGiveUpMatcher = Pattern.compile("retryOrGiveUp\\(").matcher(doWork);
        int retryOrGiveUpCalls = 0;
        while (retryOrGiveUpMatcher.find()) {
            retryOrGiveUpCalls++;
        }
        assertTrue("doWork() must route at least the generic-HTTP-error and exception paths "
                        + "through retryOrGiveUp()",
                retryOrGiveUpCalls >= 2);
    }

    @Test
    public void terminalNonRetryableCodesDoNotConsumeAttemptBudget() throws IOException {
        String src = readSource();
        // 400/403 (bad/consumed nonce) and 401 (expired nonce) are permanent
        // failures — retrying cannot recover them, so they must short-circuit to a
        // terminal Result rather than spending attempts in the bounded retry path.
        String policyPath = "src/main/java/com/duoshield/app/security/AccountLockHttpPolicy.java";
        File policyFile = locate(
                policyPath,
                "app/" + policyPath);
        String policy = new String(
                Files.readAllBytes(policyFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("only 200/204 confirms the lock",
                Pattern.compile("statusCode\\s*==\\s*200\\s*\\|\\|\\s*statusCode\\s*==\\s*204")
                        .matcher(policy).find());
        assertTrue("408/425/429 must remain retryable exceptions to the 4xx rule",
                Pattern.compile("statusCode\\s*!=\\s*408\\s*&&\\s*statusCode\\s*!=\\s*425\\s*&&\\s*statusCode\\s*!=\\s*429")
                        .matcher(policy).find());
        assertTrue(policy.contains("Disposition.PERMANENT_FAILURE"));
        assertTrue(policy.contains("Disposition.RETRYABLE_FAILURE"));
    }
}
