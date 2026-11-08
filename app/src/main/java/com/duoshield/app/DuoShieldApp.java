package com.duoshield.app;

import android.app.Application;
import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.os.Looper;
import android.os.Bundle;
import android.util.Log;
import com.bumptech.glide.Glide;
import androidx.work.Configuration;
import com.duoshield.app.crypto.signal.SignedPreKeyScheduler;
import com.duoshield.app.notifications.NotificationStyler;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.DevicePerformanceTier;
import com.duoshield.app.util.StorageCleanupWorker;
import com.duoshield.app.util.TempFileCleaner;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.security.PinKeyGate;
import com.duoshield.app.security.SessionKeyHolder;
import com.duoshield.app.util.B2StorageHelper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;
import org.signal.libsignal.protocol.ecc.Curve;

public class DuoShieldApp extends Application implements Configuration.Provider {

    private static final String TAG = "DuoShieldApp";
    private static final String PREF_GLIDE_CACHE_MIGRATION = "duoshield_cache_migration";
    private static final String KEY_GLIDE_PRIVATE_MEDIA_CACHE_PURGED =
            "glide_private_media_disk_cache_purged_v1";

    @Override
    public void onCreate() {
        super.onCreate();

        // S08-H2: enforce the screenshot preference for EVERY Activity, not only
        // screens that happen to extend BaseActivity. Several sensitive flows
        // (calls, PIN/recovery/onboarding and update/preview screens) are direct
        // AppCompatActivity subclasses and otherwise bypass BaseActivity's
        // onCreate hook. onActivityCreated runs before the first Activity frame;
        // onActivityResumed reapplies the current preference after returning from
        // external UI or changing the setting while this process stays alive.
        installScreenshotProtectionLifecycle();

        // S08-H3 upgrade cleanup: older releases used Glide's shared disk cache
        // for decrypted private B2 media. New MessageAdapter requests disable
        // disk caching, but an upgraded install can still have old plaintext
        // cache entries. Purge the old Glide disk cache once, off the main
        // thread, before the user can scroll message history. The completion
        // marker is written only after clearDiskCache() succeeds, so a process
        // death or I/O failure retries on the next launch.
        purgeLegacyPrivateMediaCacheOnce();

        // ── S10-N1: App Check provider registration ────────────────────────────
        // Registers the attestation provider that lets Firestore/Storage tell a
        // genuine build of this app apart from a scripted client presenting a
        // valid Firebase Auth session (audit/SESSION-10-SYNTHESIS.md §S10-N1 —
        // grepping the whole repo for "appcheck"/"App Check" previously returned
        // nothing at all). Must run before FirebaseApp.getInstance() is used by
        // any other Firebase SDK call in this method (Firestore settings, below),
        // because the attestation provider has to be installed before the first
        // App Check token is requested — installing it later can race the first
        // network call and silently ship that call with no token attached.
        //
        // Play Integrity (release/debug builds alike) is the real-device
        // attestor; debug builds additionally layer in the Debug provider via
        // AppCheckDebugProvider.install(), which mints a per-install debug
        // token instead of requiring a real Play Integrity verdict, so
        // debug/CI/emulator builds are never blocked by an attestation check
        // they cannot pass. AppCheckDebugProvider has a debug-variant
        // implementation (app/src/debug/...) and a release-variant no-op
        // (app/src/release/...) — see that class for why: DebugAppCheckProvider-
        // Factory ships in firebase-appcheck-debug, a debugImplementation-only
        // dependency, so the release source set can never import it directly.
        // Registering it does nothing unless the resulting token is added as a
        // "debug token" for this app in the Firebase console — until then it
        // just fails open exactly like the Play Integrity factory does below.
        //
        // NOTE — provider registration supplies tokens; it does not itself
        // enable enforcement. Firebase App Check enforces tokens at the service
        // layer when an authorized operator enables Firestore/Storage enforcement
        // in Firebase Console after reviewing monitoring metrics, compatible-client
        // adoption and rollback readiness. Firestore Security Rules cannot inspect
        // App Check token state: request.app is not a supported Rules field. Do not
        // add request.app checks or App Check helper functions to firestore.rules.
        // With service enforcement enabled, Firebase rejects missing/invalid tokens
        // before Firestore Rules are evaluated. Until then, App Check remains in
        // monitoring mode. App Check raises attacker cost but is not a trust
        // boundary against a compromised client (see README.md threat model).
        try {
            FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance(FirebaseApp.getInstance());
            appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance());
            if (BuildConfig.DEBUG) {
                AppCheckDebugProvider.install(appCheck);
            }
            Log.i(TAG, "App Check provider factory installed ("
                    + (BuildConfig.DEBUG ? "Debug+PlayIntegrity" : "PlayIntegrity") + ").");
        } catch (Exception e) {
            // Fail open, deliberately: App Check is a cost-raising attestation
            // layer, not the app's only line of defense (Firestore/Storage rules
            // and server-side auth still apply). A provider-install failure must
            // not crash app startup for every user on a device/Play Services
            // combination that cannot attest.
            Log.w(TAG, "App Check provider install failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // ── libsignal native library explicit load ────────────────────────────
        // libsignal-android 0.54.x removed the automatic System.loadLibrary()
        // call from its Java static initializers.  Apps must now load the .so
        // explicitly before any JNI method is called.  Without this, every call
        // to Curve / ECPrivateKey / SignalCipher throws UnsatisfiedLinkError,
        // which the friendlyError() handler maps to "Encryption library failed
        // to load".
        //
        // This MUST run on the main thread, synchronously, before any other
        // code (including the Firestore init below) can trigger a background
        // thread that might call libsignal earlier.
        try {
            System.loadLibrary("signal_jni");
            Log.i(TAG, "signal_jni native library loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            // The .so is missing or the device ABI is not supported.
            // This is a fatal configuration error — log the full stack so
            // adb logcat -s DuoShieldApp surfaces it immediately.
            Log.e(TAG, "FATAL: signal_jni could not be loaded. "
                    + "Verify libsignal-android AAR is in the APK and the "
                    + "device ABI is one of arm64-v8a / armeabi-v7a / x86_64 / x86." + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
        }

        // Firestore offline cache, sized by DevicePerformanceTier rather than by
        // getMemoryClass() alone.
        //
        // The old check was `isLowRamDevice() || getMemoryClass() <= 128`, which a 3–4 GB
        // budget phone never trips: a Helio P35 (MT6765, 8x Cortex-A53) reports a
        // memoryClass of 192–256 MB, so it took the 100 MB "well-provisioned" path even
        // though its storage and CPU are firmly budget class. DevicePerformanceTier keys off
        // CPU microarchitecture first, so that device now correctly lands on the smaller
        // cache, freeing ~50 MB of internal storage that is worth far more to SQLCipher WAL
        // files and the B2 media cache.
        try {
            DevicePerformanceTier tier = DevicePerformanceTier.get(this);
            long firestoreCacheBytes = tier.firestoreCacheBytes();
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(firestoreCacheBytes)
                            .build())
                    .build();
            FirebaseFirestore.getInstance().setFirestoreSettings(settings);
        } catch (Exception e) {
            Log.w(TAG, "Firestore persistence setup failed (may already be configured): " + com.duoshield.app.util.LogRedact.error(e));
        }

        // Warm up Room/SQLCipher and run the libsignal JNI diagnostic on a single
        // background thread so the first chat open does not block the main thread.
        // Combining both tasks into one thread avoids the overhead of creating two
        // separate OS-level threads for sequential work that has no parallelism benefit.
        new Thread(() -> {
            // Step 1: open Room/SQLCipher so the first chat screen is instant.
            //
            // S08-M3: this is now conditional. Once the PIN gate is enrolled, the
            // database key exists only after the user has entered their PIN, so
            // opening the database at process start is no longer possible — and
            // attempting it unconditionally would throw DatabaseLockedException on
            // every single cold start, turning a performance warm-up into a
            // guaranteed logged exception. Warm up only when a session key is
            // already available (the process survived an unlock) or this install
            // has no gate enrolled. When locked, the first screen that needs the
            // database prompts for the PIN, and the warm-up simply does not apply.
            try {
                if (SessionKeyHolder.isUnlocked()
                        || !PinKeyGate.isEnrolled(getApplicationContext())) {
                    AppDatabase.getInstance(getApplicationContext());
                    Log.i(TAG, "Room/SQLCipher warm-up complete.");
                } else {
                    Log.i(TAG, "Skipping Room warm-up — PIN gate enrolled, session locked.");
                }
            } catch (Exception e) {
                Log.w(TAG, "Room warm-up failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
            }
            // Step 2: confirm libsignal JNI is wired up correctly end-to-end.
            try {
                Curve.generateKeyPair();
                Log.i(TAG, "libsignal JNI check PASSED — Curve.generateKeyPair() succeeded.");
            } catch (Throwable e) {
                Log.e(TAG, "libsignal JNI check FAILED — identity generation will crash. "
                        + "Check that libsignal-android AAR is correctly packaged in the APK "
                        + "and that the device ABI is supported (arm64-v8a / armeabi-v7a / x86_64)." + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        }, "startup-warmup").start();

        // Notification channels and the app-lock lifecycle observer must exist before the
        // first Activity or an inbound FCM notification can reach either of them, so they
        // stay on the critical path.
        NotificationStyler.createChannels(this);
        AppLockManager.init(this);

        scheduleDeferredStartupWork();
    }


    /**
     * Applies the app-wide screenshot policy to every Activity window.
     *
     * <p>FLAG_SECURE is a window property, not an Application property. Applying
     * it through Application.ActivityLifecycleCallbacks closes the inheritance
     * gap for screens that do not extend BaseActivity, while the BaseActivity
     * hook remains as defense in depth. The callback is registered once per
     * process and is automatically scoped to this Application instance.
     */
    private void installScreenshotProtectionLifecycle() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                BaseActivity.applyScreenshotSecurity(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                BaseActivity.applyScreenshotSecurity(activity);
            }

            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    /**
     * Removes decrypted private-media entries left by app versions whose
     * MessageAdapter requests used Glide's persistent disk cache.
     */
    private void purgeLegacyPrivateMediaCacheOnce() {
        android.content.SharedPreferences migrationPrefs = getSharedPreferences(
                PREF_GLIDE_CACHE_MIGRATION, MODE_PRIVATE);
        if (migrationPrefs.getBoolean(KEY_GLIDE_PRIVATE_MEDIA_CACHE_PURGED, false)) return;

        Thread cleanup = new Thread(() -> {
            try {
                Glide.get(getApplicationContext()).clearDiskCache();
                boolean recorded = migrationPrefs.edit()
                        .putBoolean(KEY_GLIDE_PRIVATE_MEDIA_CACHE_PURGED, true)
                        .commit();
                if (!recorded) {
                    Log.w(TAG, "Legacy private-media cache was cleared, but its migration marker "
                            + "could not be persisted; cleanup will retry next launch.");
                }
            } catch (Exception e) {
                Log.w(TAG, "Legacy private-media cache cleanup failed; will retry next launch." + " (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        }, "glide-legacy-cache-purge");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    /**
     * Runs the startup work that is not needed to render the first frame once the main thread
     * goes idle.
     *
     * <p>Everything below used to execute inline in {@link #onCreate()}: a B2 connection
     * warm-up, three {@link WorkManager} periodic-work enqueues and a pre-key scheduler check.
     * Each one is individually cheap on an out-of-order CPU and individually expensive on an
     * in-order Cortex-A53 — WorkManager's first {@code getInstance()} alone initialises a Room
     * database on the calling thread. Together they measurably delay the launch activity on
     * budget hardware, and none of them has to happen before the user sees the app.
     *
     * <p>An idle handler is used rather than a delayed post because it is defined relative to
     * actual main-thread quiescence: the work runs after the first frame has been drawn, not
     * after an arbitrary timeout that may land in the middle of inflation. Returning
     * {@code false} removes the handler so this runs exactly once per process.
     */
    private void scheduleDeferredStartupWork() {
        Looper.myQueue().addIdleHandler(() -> {
            try {
                // Pre-warm the TCP + TLS connection to B2 so the first upload/download
                // reuses a pooled socket instead of paying the full ~900-1400 ms handshake.
                B2StorageHelper.warmConnection();

                // Delete decrypted temp media files (voice_*.3gp, vid_*.mp4) older than 5 min.
                // Runs every 15 minutes in the background; KEEP policy avoids re-queuing on
                // each launch.
                TempFileCleaner.schedule(this);

                // Trim the app cache to 50 MB once per day (Bug 16 — was never scheduled).
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                        "StorageCleanup",
                        ExistingPeriodicWorkPolicy.KEEP,
                        new PeriodicWorkRequest.Builder(
                                StorageCleanupWorker.class, 1, TimeUnit.DAYS)
                                .setConstraints(new Constraints.Builder()
                                        .setRequiresBatteryNotLow(true)
                                        .setRequiresStorageNotLow(true)
                                        .build())
                                .build());

                // Rotate Signal signed pre-key when it is older than 7 days (checked daily).
                SignedPreKeyScheduler.schedule(this);

                // Purge stale call docs (ringing/missed/timeout older than 24 h) every 12 h.
                com.duoshield.app.call.CallCleanupWorker.scheduleIfNeeded(this);

                Log.i(TAG, "Deferred startup work scheduled.");
            } catch (Exception e) {
                // Housekeeping only: a failure here must never take down the process, and the
                // work is all periodic so the next launch retries it.
                Log.w(TAG, "Deferred startup work failed (non-fatal): " + com.duoshield.app.util.LogRedact.error(e));
            }
            return false; // one-shot
        });
    }

    /**
     * Release Glide's in-memory caches when the OS asks the app to yield RAM.
     * This is especially important on low-RAM devices (e.g. POCO C51, 2 GB) where
     * multiple apps compete for a tight heap budget.  We clear on MODERATE or worse;
     * on UI_HIDDEN we just trim Glide's pool without a full clear.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Glide.get(this).clearMemory();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Glide.get(this).trimMemory(level);
        }
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build();
    }
}
