package com.duoshield.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.TestWorkerBuilder;

import org.json.JSONObject;
import org.junit.Test;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes the real AccountLockWorker#doWork() path using WorkManager's test
 * builder and deterministic HTTP/lock-store fakes. No live endpoint or secret
 * is used. The test exercises request construction, HTTP classification,
 * terminal/retry results and compare-and-clear intent ordering.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AccountLockWorkerRuntimeTest {

    private static final String NONCE = "synthetic-one-time-lock-nonce";

    @Test
    public void confirmed200PostsNonceAndClearsMatchingIntentAndWarmToken() throws Exception {
        FakeHttpURLConnection connection = connection(200);
        AtomicInteger opened = new AtomicInteger();
        FakeAcknowledger acknowledger = new FakeAcknowledger();

        ListenableWorker.Result result = buildWorker(connection, 0, NONCE, opened, acknowledger).doWork();

        assertResult(ListenableWorker.Result.success(), result);
        assertRequest(connection);
        assertEquals(1, opened.get());
        assertEquals(1, acknowledger.intentClears.get());
        assertEquals(1, acknowledger.warmTokenClears.get());
        assertTrue(connection.disconnected);
    }

    @Test
    public void confirmed204IsAlsoSuccess() throws Exception {
        FakeHttpURLConnection connection = connection(204);
        FakeAcknowledger acknowledger = new FakeAcknowledger();

        ListenableWorker.Result result = buildWorker(
                connection, 0, NONCE, new AtomicInteger(), acknowledger).doWork();

        assertResult(ListenableWorker.Result.success(), result);
        assertEquals(1, acknowledger.intentClears.get());
        assertEquals(1, acknowledger.warmTokenClears.get());
    }

    @Test
    public void permanentClientFailureRetainsIntentAndOnlyInvalidatesWarmToken() throws Exception {
        FakeHttpURLConnection connection = connection(401);
        FakeAcknowledger acknowledger = new FakeAcknowledger();

        ListenableWorker.Result result = buildWorker(
                connection, 0, NONCE, new AtomicInteger(), acknowledger).doWork();

        assertResult(ListenableWorker.Result.failure(), result);
        assertEquals(0, acknowledger.intentClears.get());
        assertEquals(1, acknowledger.warmTokenClears.get());
        assertTrue(connection.disconnected);
    }

    @Test
    public void transientHttpFailureRetriesBelowAttemptLimit() throws Exception {
        FakeHttpURLConnection connection = connection(503);
        FakeAcknowledger acknowledger = new FakeAcknowledger();

        ListenableWorker.Result result = buildWorker(
                connection, 0, NONCE, new AtomicInteger(), acknowledger).doWork();

        assertResult(ListenableWorker.Result.retry(), result);
        assertEquals(0, acknowledger.intentClears.get());
        assertEquals(0, acknowledger.warmTokenClears.get());
        assertTrue(connection.disconnected);
    }

    @Test
    public void transientHttpFailureBecomesFailureAtAttemptLimit() throws Exception {
        FakeHttpURLConnection connection = connection(503);
        FakeAcknowledger acknowledger = new FakeAcknowledger();

        ListenableWorker.Result result = buildWorker(
                connection, 6, NONCE, new AtomicInteger(), acknowledger).doWork();

        assertResult(ListenableWorker.Result.failure(), result);
        assertEquals(0, acknowledger.intentClears.get());
        assertEquals(0, acknowledger.warmTokenClears.get());
        assertTrue(connection.disconnected);
    }

    @Test
    public void networkExceptionRetriesAndDisconnects() throws Exception {
        FakeHttpURLConnection connection = connection(503);
        connection.responseFailure = new IOException("synthetic socket reset");
        FakeAcknowledger acknowledger = new FakeAcknowledger();

        ListenableWorker.Result result = buildWorker(
                connection, 0, NONCE, new AtomicInteger(), acknowledger).doWork();

        assertResult(ListenableWorker.Result.retry(), result);
        assertEquals(0, acknowledger.intentClears.get());
        assertEquals(0, acknowledger.warmTokenClears.get());
        assertTrue(connection.disconnected);
    }

    @Test
    public void missingNonceFailsWithoutOpeningNetworkOrClearingIntent() {
        AtomicInteger opened = new AtomicInteger();
        FakeAcknowledger acknowledger = new FakeAcknowledger();

        ListenableWorker.Result result = buildWorker(
                null, 0, "", opened, acknowledger).doWork();

        assertResult(ListenableWorker.Result.failure(), result);
        assertEquals(0, opened.get());
        assertEquals(0, acknowledger.intentClears.get());
        assertEquals(0, acknowledger.warmTokenClears.get());
    }

    private static FakeHttpURLConnection connection(int responseCode) throws Exception {
        return new FakeHttpURLConnection(
                new URL("https://duofat.onrender.com/duress-lock"), responseCode);
    }

    private static AccountLockWorker buildWorker(
            FakeHttpURLConnection connection,
            int runAttemptCount,
            String nonce,
            AtomicInteger opened,
            FakeAcknowledger acknowledger) {
        Context context = RuntimeEnvironment.getApplication();
        TestWorkerBuilder<AccountLockWorker> builder = TestWorkerBuilder.from(
                context, AccountLockWorker.class, Runnable::run);
        builder.setInputData(new Data.Builder().putString("nonce", nonce).build());
        builder.setRunAttemptCount(runAttemptCount);
        builder.setWorkerFactory(new WorkerFactory() {
            @Override
            public ListenableWorker createWorker(
                    @NonNull Context appContext,
                    @NonNull String workerClassName,
                    @NonNull WorkerParameters workerParameters) {
                return new AccountLockWorker(
                        appContext,
                        workerParameters,
                        url -> {
                            opened.incrementAndGet();
                            assertEquals("POST endpoint path", "/duress-lock", url.getPath());
                            if (connection == null) {
                                throw new AssertionError("unexpected HTTP connection");
                            }
                            return connection;
                        },
                        acknowledger);
            }
        });
        return builder.build();
    }

    private static void assertRequest(FakeHttpURLConnection connection) throws Exception {
        assertEquals("POST", connection.getRequestMethod());
        assertTrue(connection.getDoOutput());
        assertEquals("application/json; charset=utf-8",
                connection.getRequestProperty("Content-Type"));
        assertEquals(15_000, connection.getConnectTimeout());
        assertEquals(15_000, connection.getReadTimeout());
        JSONObject body = new JSONObject(
                new String(connection.body.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(NONCE, body.getString("nonce"));
    }

    private static void assertResult(ListenableWorker.Result expected, ListenableWorker.Result actual) {
        assertEquals(expected.getClass(), actual.getClass());
    }

    private static final class FakeAcknowledger
            implements AccountLockWorker.LockIntentAcknowledger {
        final AtomicInteger intentClears = new AtomicInteger();
        final AtomicInteger warmTokenClears = new AtomicInteger();

        @Override
        public void clearIntentIfTokenMatches(Context context, String nonce) {
            assertEquals(NONCE, nonce);
            intentClears.incrementAndGet();
        }

        @Override
        public void clearWarmTokenIfMatches(Context context, String nonce) {
            assertEquals(NONCE, nonce);
            warmTokenClears.incrementAndGet();
        }
    }

    private static final class FakeHttpURLConnection extends HttpURLConnection {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final int responseCode;
        boolean disconnected;
        IOException responseFailure;

        FakeHttpURLConnection(URL url, int responseCode) {
            super(url);
            this.responseCode = responseCode;
        }

        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public OutputStream getOutputStream() { return body; }

        @Override
        public int getResponseCode() throws IOException {
            if (responseFailure != null) throw responseFailure;
            return responseCode;
        }
    }
}
