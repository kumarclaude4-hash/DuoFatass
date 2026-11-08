package com.duoshield.app.util;

import android.content.SharedPreferences;

/**
 * Durable attempt budget for changing an existing account PIN.
 *
 * <p>The budget is consumed before the caller checks the proposed PIN against
 * the hidden secondary code or attempts to persist the replacement. Successful
 * changes and rejected candidates therefore consume the same allowance.
 *
 * <p>Three attempts are permitted in a 15-minute window. The cooldown uses
 * both wall-clock and elapsed-realtime deadlines, taking the longer remainder
 * while the device remains in the same boot session. A wall-clock rollback
 * during that session cannot shorten the active cooldown.
 */
final class PinChangeThrottle {

    static final int MAX_ATTEMPTS = 3;
    static final long COOLDOWN_MS = 15 * 60_000L;

    private PinChangeThrottle() {}

    static synchronized boolean tryRecord(
            SharedPreferences preferences,
            String scope,
            long wallNow,
            long elapsedNow,
            long bootId) {
        if (preferences == null || scope == null || scope.isEmpty()
                || wallNow <= 0L || elapsedNow < 0L) {
            return false;
        }

        try {
            if (remainingMs(preferences, scope, wallNow, elapsedNow, bootId) > 0L) {
                return false;
            }

            int attempts = Math.max(0, preferences.getInt(key(scope, "count"), 0));
            // The previous three-attempt window has fully expired. Begin a
            // fresh window rather than carrying an old count forward forever.
            if (attempts >= MAX_ATTEMPTS) attempts = 0;
            attempts++;

            SharedPreferences.Editor editor = preferences.edit()
                    .putInt(key(scope, "count"), attempts);
            if (attempts >= MAX_ATTEMPTS) {
                editor.putLong(key(scope, "until_wall"), saturatedAdd(wallNow, COOLDOWN_MS))
                        .putLong(key(scope, "until_elapsed"), saturatedAdd(elapsedNow, COOLDOWN_MS))
                        .putLong(key(scope, "boot_id"), bootId);
            } else {
                editor.remove(key(scope, "until_wall"))
                        .remove(key(scope, "until_elapsed"))
                        .remove(key(scope, "boot_id"));
            }

            // A failed durable write must not allow the candidate check or PIN
            // write to proceed; the caller treats false as a generic failure.
            return editor.commit();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static synchronized long remainingMs(
            SharedPreferences preferences,
            String scope,
            long wallNow,
            long elapsedNow,
            long bootId) {
        if (preferences == null || scope == null || scope.isEmpty()) {
            return COOLDOWN_MS;
        }

        try {
            long wallDeadline = preferences.getLong(key(scope, "until_wall"), 0L);
            long wallRemaining = Math.max(0L, wallDeadline - wallNow);

            long elapsedRemaining = 0L;
            long elapsedDeadline = preferences.getLong(key(scope, "until_elapsed"), 0L);
            long storedBootId = preferences.getLong(key(scope, "boot_id"), Long.MIN_VALUE);
            if (elapsedDeadline > 0L && storedBootId == bootId) {
                elapsedRemaining = Math.max(0L, elapsedDeadline - elapsedNow);
            }
            return Math.max(wallRemaining, elapsedRemaining);
        } catch (RuntimeException ignored) {
            // If state cannot be read reliably, do not permit a candidate check.
            return COOLDOWN_MS;
        }
    }

    private static String key(String scope, String suffix) {
        return scope + "_" + suffix;
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
