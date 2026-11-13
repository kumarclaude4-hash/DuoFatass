package com.duoshield.app.util;

import android.content.SharedPreferences;

/**
 * Durable attempt budget for changing an existing account PIN.
 *
 * <p>The budget is consumed before the caller checks the proposed PIN against
 * the hidden secondary code or attempts to persist the replacement. Successful
 * changes and rejected candidates therefore consume the same allowance.
 *
 * <p>Three attempts are permitted in a fixed 15-minute window beginning with
 * the first attempt. Reaching the limit starts a further 15-minute cooldown.
 * Both windows use wall-clock and same-boot elapsed-realtime anchors; a
 * wall-clock rollback during the same boot cannot shorten either window.
 */
final class PinChangeThrottle {

    static final int MAX_ATTEMPTS = 3;
    static final long WINDOW_MS = 15 * 60_000L;
    static final long COOLDOWN_MS = 15 * 60_000L;

    private PinChangeThrottle() {}

    static synchronized boolean tryRecord(
            SharedPreferences preferences,
            SecurePrefs.SecurityTier storageTier,
            String scope,
            long wallNow,
            long elapsedNow,
            long bootId) {
        // A successful SharedPreferences.commit() on SecurePrefs' NONE tier
        // only updates the in-memory fallback. It would disappear on process
        // death and silently reset the candidate budget, so do not inspect or
        // permit a candidate unless the resolved account store is durable.
        if (storageTier == null || !storageTier.isDurable()
                || preferences == null || scope == null || scope.isEmpty()
                || wallNow <= 0L || elapsedNow < 0L) {
            return false;
        }

        try {
            if (remainingMs(preferences, scope, wallNow, elapsedNow, bootId) > 0L) {
                return false;
            }

            int attempts = Math.max(0, preferences.getInt(key(scope, "count"), 0));
            long windowWall = preferences.getLong(key(scope, "window_wall"), 0L);
            long windowElapsed = preferences.getLong(key(scope, "window_elapsed"), 0L);
            long windowBootId = preferences.getLong(key(scope, "window_boot_id"), Long.MIN_VALUE);

            if (attempts > 0 && windowExpired(
                    windowWall, windowElapsed, windowBootId, wallNow, elapsedNow, bootId)) {
                attempts = 0;
            }

            SharedPreferences.Editor editor = preferences.edit();
            if (attempts == 0) {
                // Start a fresh fixed window. A successful or rejected change
                // consumes this slot because this method runs before inspection.
                windowWall = wallNow;
                windowElapsed = elapsedNow;
                windowBootId = bootId;
                editor.putLong(key(scope, "window_wall"), windowWall)
                        .putLong(key(scope, "window_elapsed"), windowElapsed)
                        .putLong(key(scope, "window_boot_id"), windowBootId);
            }

            attempts++;
            editor.putInt(key(scope, "count"), attempts);
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

    private static boolean windowExpired(
            long startWall,
            long startElapsed,
            long storedBootId,
            long wallNow,
            long elapsedNow,
            long bootId) {
        if (storedBootId == bootId && startElapsed >= 0L && elapsedNow >= startElapsed) {
            // Within the same boot, elapsed time is authoritative. If the
            // elapsed clock is now below its persisted anchor, the device has
            // rebooted (important when BOOT_COUNT itself is unavailable).
            return startWall > 0L && elapsedNow - startElapsed >= WINDOW_MS;
        }

        // Across boots (or when the elapsed clock proves a reboot despite an
        // unavailable boot counter), use wall time. A wall-clock rollback
        // leaves the old window in force rather than granting attempts.
        return startWall > 0L && wallNow >= startWall
                && wallNow - startWall >= WINDOW_MS;
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
            long windowElapsed = preferences.getLong(key(scope, "window_elapsed"), -1L);
            long windowBootId = preferences.getLong(key(scope, "window_boot_id"), Long.MIN_VALUE);
            boolean sameBoot = windowBootId == bootId
                    && windowElapsed >= 0L && elapsedNow >= windowElapsed;
            if (elapsedDeadline > 0L && storedBootId == bootId
                    && sameBoot && elapsedNow >= 0L && elapsedDeadline > elapsedNow) {
                elapsedRemaining = elapsedDeadline - elapsedNow;
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
