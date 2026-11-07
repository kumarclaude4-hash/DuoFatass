package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Device-local, best-effort usage guard for explicitly instrumented Firestore
 * operations. This is NOT a project-wide quota counter: SharedPreferences are
 * local to one app installation and cannot account for other users/devices or
 * SDK operations that are not instrumented here.
 *
 * Firestore's free-tier quota resets at midnight America/Los_Angeles. Keep the
 * local diagnostic day boundary aligned with that reset rather than device time.
 *
 * Bug 13 fix: converted to a per-day singleton so the 80% quota warning fires
 * reliably even when a new instance is constructed after the threshold is
 * already crossed.
 *
 * Previous behaviour: `warnIfNearing()` compared `wasBelow` (true at construction
 * time) against `isAtOrAbove` (also at construction time). If the threshold was
 * already exceeded when the guard was constructed, both flags were computed from
 * the same `current` value and the condition `wasBelow && isAtOrAbove` could
 * never be true.
 *
 * New behaviour: a persistent `warned_<type>` boolean in SharedPreferences tracks
 * whether the warning has been shown today. It is reset alongside the counters in
 * `rolloverIfNeeded()`. This survives multiple instances and survives the process
 * being killed and relaunched within the same calendar day.
 */
public class FirebaseCostGuard {

    private static final String PREFS       = "duoshield_cost_guard";
    // Allocate 80% of each project-wide Spark quota evenly across 10 active
    // installations, leaving 20% project headroom for uninstrumented SDK work,
    // retries, other devices, and operational variance. These remain local,
    // best-effort limits — they are not a project-wide billing firewall.
    private static final int    MAX_READS   = 4_000;
    private static final int    MAX_WRITES  = 1_600;
    private static final int    MAX_DELETES = 1_600;
    private static final float  WARN_PCT    = 0.80f;
    private static final String QUOTA_TIME_ZONE = "America/Los_Angeles";

    private static FirebaseCostGuard instance;

    private final SharedPreferences prefs;
    private final Context           appContext;

    private FirebaseCostGuard(Context ctx) {
        appContext = ctx.getApplicationContext();
        prefs      = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        rolloverIfNeeded();
    }

    public static synchronized FirebaseCostGuard getInstance(Context ctx) {
        if (instance == null) {
            instance = new FirebaseCostGuard(ctx);
        } else {
            // Check for day rollover even on existing instance
            instance.rolloverIfNeeded();
        }
        return instance;
    }

    /**
     * Resets counters if the calendar day has changed.
     *
     * <p>The previous implementation stored {@code todayKey} as a {@code final} field
     * computed once at construction time.  Since this is a process-lifetime singleton,
     * the guard would compare a stale "yesterday" key against itself after midnight and
     * conclude no rollover was needed — counters never reset (BUG-T03).
     *
     * <p>Fix: compute the current day key fresh on every call so midnight crossings are
     * detected correctly.
     */
    private void rolloverIfNeeded() {
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dayFormat.setTimeZone(TimeZone.getTimeZone(QUOTA_TIME_ZONE));
        String currentDayKey = dayFormat.format(new Date());
        String stored = prefs.getString("day_key", "");
        if (!stored.equals(currentDayKey)) {
            prefs.edit()
                .putString("day_key",        currentDayKey)
                .putInt("reads",   0).putInt("writes",   0).putInt("deletes",   0)
                .putBoolean("warned_reads",   false)
                .putBoolean("warned_writes",  false)
                .putBoolean("warned_deletes", false)
                .apply();
        }
    }

    public synchronized boolean canRead(int n) {
        int current = prefs.getInt("reads", 0);
        warnIfNearing("reads", current, MAX_READS);
        return current + n <= MAX_READS;
    }

    public synchronized boolean canWrite(int n) {
        int current = prefs.getInt("writes", 0);
        warnIfNearing("writes", current, MAX_WRITES);
        return current + n <= MAX_WRITES;
    }

    public synchronized boolean canDelete(int n) {
        int current = prefs.getInt("deletes", 0);
        warnIfNearing("deletes", current, MAX_DELETES);
        return current + n <= MAX_DELETES;
    }

    /**
     * Atomically checks and reserves local budget BEFORE issuing a Firestore
     * operation. Reservations are conservative: a failed request still counts
     * for this device's local budget, avoiding concurrent preflight races.
     */
    public synchronized boolean reserveRead(int n) {
        return reserve("reads", n, MAX_READS);
    }

    public synchronized boolean reserveWrite(int n) {
        return reserve("writes", n, MAX_WRITES);
    }

    public synchronized boolean reserveDelete(int n) {
        return reserve("deletes", n, MAX_DELETES);
    }

    private boolean reserve(String key, int n, int max) {
        rolloverIfNeeded();
        if (n <= 0) return false;
        int current = prefs.getInt(key, 0);
        if (current > max - n) {
            warnIfNearing(key, current, max);
            return false;
        }
        int updated = current + n;
        prefs.edit().putInt(key, updated).apply();
        warnIfNearing(key, updated, max);
        return true;
    }

    public synchronized void recordReads(int n)   { add("reads", n); }
    public synchronized void recordWrites(int n)  { add("writes", n); }
    public synchronized void recordDeletes(int n) { add("deletes", n); }

    private void add(String key, int n) {
        rolloverIfNeeded();
        if (n <= 0) return;
        int current = prefs.getInt(key, 0);
        int updated = current > Integer.MAX_VALUE - n ? Integer.MAX_VALUE : current + n;
        prefs.edit().putInt(key, updated).apply();
        int max = "reads".equals(key) ? MAX_READS
                : "writes".equals(key) ? MAX_WRITES : MAX_DELETES;
        warnIfNearing(key, updated, max);
    }

    /**
     * Shows the warning toast at most once per day per quota type.
     * Uses a persistent "warned_<type>" flag so the warning fires correctly
     * even when a new instance is created after the threshold is already crossed.
     */
    private void warnIfNearing(String type, int current, int max) {
        String warnedKey = "warned_" + type;
        if (prefs.getBoolean(warnedKey, false)) return;   // already warned today
        if (current >= (int)(max * WARN_PCT)) {
            prefs.edit().putBoolean(warnedKey, true).apply();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(appContext,
                    "Warning: " + type + " quota at 80% for today",
                    Toast.LENGTH_LONG).show());
        }
    }

    public synchronized int getReads() {
        rolloverIfNeeded();
        return prefs.getInt("reads", 0);
    }

    public synchronized int getWrites() {
        rolloverIfNeeded();
        return prefs.getInt("writes", 0);
    }

    public synchronized int getDeletes() {
        rolloverIfNeeded();
        return prefs.getInt("deletes", 0);
    }
}
