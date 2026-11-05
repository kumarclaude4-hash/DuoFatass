package com.duoshield.app.security;

/**
 * Classifies responses from the unauthenticated, single-use duress-lock endpoint.
 *
 * <p>A successful status means the server committed (or idempotently confirmed)
 * the account lock. Permanent 4xx responses mean the exact nonce/request cannot
 * succeed by retrying. Timeouts/rate limits and server errors remain retryable.
 */
public final class AccountLockHttpPolicy {

    public enum Disposition {
        LOCK_CONFIRMED,
        PERMANENT_FAILURE,
        RETRYABLE_FAILURE
    }

    private AccountLockHttpPolicy() { }

    public static Disposition classify(int statusCode) {
        if (statusCode == 200 || statusCode == 204) {
            return Disposition.LOCK_CONFIRMED;
        }
        if (statusCode >= 400 && statusCode < 500
                && statusCode != 408 && statusCode != 425 && statusCode != 429) {
            return Disposition.PERMANENT_FAILURE;
        }
        return Disposition.RETRYABLE_FAILURE;
    }
}
