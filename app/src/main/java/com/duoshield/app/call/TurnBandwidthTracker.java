package com.duoshield.app.call;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tracks cumulative TURN relay bytes consumed in the current calendar month and
 * applies a device-local safety ceiling to estimated TURN egress. The counter is
 * not project-wide or tamper-proof; it protects normal use on this installation.
 *
 * <p><b>How it works:</b>
 * <ul>
 *   <li>Each call reports its total transport bytes (sent + received) via
 *       {@link #recordCallBytes(long)} when the call ends. Only bytes received
 *       by this client are counted, matching Cloudflare TURN egress billing direction.</li>
 *   <li>On the first call of a new calendar month the counter automatically resets.</li>
 *   <li>{@link #isLimitReached()} returns {@code true} when local usage ≥ 80 GB decimal.
 *       {@link com.duoshield.app.call.CallManager} checks this before adding any TURN
 *       ICE server entry, falling back to STUN-only mode so the user gets a clear
 *       "TURN quota exhausted" warning instead of a silent call failure.</li>
 * </ul>
 *
 * <p><b>Storage:</b> {@code duoshield_turn_tracker} SharedPreferences (plaintext — bytes
 * transferred are not sensitive data).
 */
public class TurnBandwidthTracker {

    private static final String TAG = "TurnBandwidthTracker";

    /**
     * 80 GB decimal local egress budget per installation. With one tracked
     * installation for each of 10 users, the combined app-side target is at
     * most 800 GB of Cloudflare's 1,000 GB monthly free Realtime allowance.
     */
    public static final long MONTHLY_LIMIT_BYTES = 80_000_000_000L;

    /** Warn at 70 GB decimal to leave time to reduce relay-heavy usage. */
    public static final long WARNING_THRESHOLD_BYTES = 70_000_000_000L;

    private static final String PREFS_NAME    = "duoshield_turn_tracker";
    private static final String KEY_MONTH     = "turn_month";       // "YYYY-MM"
    private static final String KEY_BYTES     = "turn_bytes";       // long
    private static final String KEY_CALLS     = "turn_calls";       // int count for diagnostics

    private static final SimpleDateFormat MONTH_FMT = new SimpleDateFormat("yyyy-MM", Locale.US);
    static { MONTH_FMT.setTimeZone(TimeZone.getTimeZone("UTC")); }

    private static volatile TurnBandwidthTracker instance;

    private final SharedPreferences prefs;

    private TurnBandwidthTracker(Context context) {
        prefs = context.getApplicationContext()
                       .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the singleton, creating it on first call. */
    public static TurnBandwidthTracker get(Context context) {
        if (instance == null) {
            synchronized (TurnBandwidthTracker.class) {
                if (instance == null) {
                    instance = new TurnBandwidthTracker(context);
                }
            }
        }
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Records estimated billable egress bytes (TURN server → this client)
     * consumed by a completed call.
     *
     * <p>Thread-safe; can be called from any thread.
     *
     * @param bytes estimated relay egress bytes received by this client
     */
    public synchronized void recordCallBytes(long bytes) {
        if (bytes <= 0) return;
        rollMonthIfNeeded();

        long current = prefs.getLong(KEY_BYTES, 0L);
        int  calls   = prefs.getInt(KEY_CALLS, 0);
        long updated = Long.MAX_VALUE - current < bytes ? Long.MAX_VALUE : current + bytes;

        prefs.edit()
             .putLong(KEY_BYTES, updated)
             .putInt(KEY_CALLS, calls == Integer.MAX_VALUE ? calls : calls + 1)
             .apply();

        Log.i(TAG, String.format(Locale.US,
                "TURN egress estimate recorded: +%s this call | month total: %s / %s GB",
                humanBytes(bytes), humanBytes(updated), humanBytes(MONTHLY_LIMIT_BYTES)));

        if (updated >= MONTHLY_LIMIT_BYTES) {
            Log.e(TAG, "TURN local monthly egress budget reached — TURN disabled until next month.");
        } else if (updated >= WARNING_THRESHOLD_BYTES) {
            Log.w(TAG, String.format(Locale.US,
                    "TURN egress warning: %.1f GB of 80 GB local budget used this month.",
                    updated / (1024.0 * 1024.0 * 1024.0)));
        }
    }

    /**
     * Returns {@code true} if this installation has reached its 80 GB local budget.
     * When this is {@code true} the app should skip adding TURN ICE servers.
     */
    public synchronized boolean isLimitReached() {
        rollMonthIfNeeded();
        return prefs.getLong(KEY_BYTES, 0L) >= MONTHLY_LIMIT_BYTES;
    }

    /**
     * Returns {@code true} if usage has crossed the 70 GB local warning threshold.
     * The call UI shows a banner when this is {@code true}.
     */
    public synchronized boolean isNearLimit() {
        rollMonthIfNeeded();
        long used = prefs.getLong(KEY_BYTES, 0L);
        return used >= WARNING_THRESHOLD_BYTES && used < MONTHLY_LIMIT_BYTES;
    }

    /** Returns bytes used so far this calendar month. */
    public synchronized long getUsedBytes() {
        rollMonthIfNeeded();
        return prefs.getLong(KEY_BYTES, 0L);
    }

    /** Returns bytes remaining before the monthly cap. */
    public synchronized long getRemainingBytes() {
        return Math.max(0L, MONTHLY_LIMIT_BYTES - getUsedBytes());
    }

    /** Human-readable used/total string, e.g. "3.42 GB / 100 GB". */
    public synchronized String getSummary() {
        long used = getUsedBytes();
        return String.format(Locale.US, "%.2f GB / 80 GB estimated egress this month",
                used / 1_000_000_000.0);
    }

    /** Used GB as a float (0.0 – 100.0). */
    public synchronized float getUsedGb() {
        return (float) (getUsedBytes() / 1_000_000_000.0);
    }

    /** 0–100 integer percentage of the monthly cap consumed. */
    public synchronized int getUsedPercent() {
        long used = getUsedBytes();
        return (int) Math.min(100L, used * 100L / MONTHLY_LIMIT_BYTES);
    }

    /** Returns the current month key (YYYY-MM). */
    public synchronized String getCurrentMonthKey() {
        return MONTH_FMT.format(new Date());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Resets the counter if we have entered a new calendar month.
     * Must be called inside a {@code synchronized} block.
     */
    private void rollMonthIfNeeded() {
        String thisMonth = MONTH_FMT.format(new Date());
        String storedMonth = prefs.getString(KEY_MONTH, "");
        if (!thisMonth.equals(storedMonth)) {
            long oldBytes = prefs.getLong(KEY_BYTES, 0L);
            Log.i(TAG, String.format(Locale.US,
                    "New month (%s → %s) — resetting TURN counter. Previous month used: %s",
                    storedMonth, thisMonth, humanBytes(oldBytes)));
            prefs.edit()
                 .putString(KEY_MONTH, thisMonth)
                 .putLong(KEY_BYTES, 0L)
                 .putInt(KEY_CALLS, 0)
                 .apply();
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0);
    }
}
