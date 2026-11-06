package com.duoshield.app.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager worker that deletes AES-decrypted temp files from getCacheDir().
 *
 * DuoShield writes decrypted media bytes and other short-lived scratch files to
 * the cache directory during capture, upload, playback and thumbnailing. These
 * files must not persist — most contain plaintext media.
 *
 * Deletion policy (top level of getCacheDir(), PLUS getCacheDir()/shared/**
 * — see below):
 *   • Plaintext media swept: "voice_*.3gp" / "voice_*.m4a", "vid_*.mp4"
 *     (also covers "vid_view_*.mp4"), "share_*.jpg", "cam_*.jpg",
 *     "grp_cam_*.jpg", "thumb_*.mp4"
 *   • Scratch also swept:    "enc_*.tmp" (ciphertext upload scratch)
 *   • Legacy exports swept:  "duoshield_export_*.pdf" / ".txt"
 *   • Not handled here:      "b2dl_*.enc" lives inside the "chat_export_*"
 *     working dir (a subdirectory), so it is already removed by the recursive
 *     "chat_export_" directory sweep below rather than by a top-level rule.
 *   • Maximum age:      {@value MAX_AGE_MS} ms  (5 minutes)
 *   • Run schedule:     every {@value INTERVAL_MIN} minutes (WorkManager minimum)
 *
 * S08-M2 note: "share_*.jpg", "cam_*.jpg", "grp_cam_*.jpg", and
 * "DuoShield_Export_*.zip" now live under {@link SharedCacheDir}'s
 * subdirectories (getCacheDir()/shared/media|camera|export/) instead of the
 * cache root, because they are the only files ever handed to FileProvider —
 * see SharedCacheDir's class doc. The same filename rules below are applied
 * to files found in that tree (see {@link #sweepDir}), so relocating them
 * did not change what gets swept or when, only which files sit at the cache
 * root at all.
 *
 * Schedule once at app start via {@link #schedule(Context)}.
 * Uses {@link ExistingPeriodicWorkPolicy#KEEP} so subsequent app launches do
 * not reset the timer.
 */
public class TempFileCleaner extends Worker {

    private static final String TAG          = "TempFileCleaner";
    private static final String WORK_TAG     = "duoshield_temp_cleanup";
    private static final long   MAX_AGE_MS   = 5L * 60 * 1000;   // 5 minutes
    // ChatExportHelper zips can sit in a share target's queue (e.g. "save to
    // Drive") longer than the 5-minute plaintext-playback window, so they get
    // a longer grace period before being swept.
    private static final long   MAX_AGE_EXPORT_ZIP_MS = 24L * 60 * 60 * 1000; // 24 hours
    private static final int    INTERVAL_MIN = 15;                // WorkManager minimum

    public TempFileCleaner(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        File cacheDir = getApplicationContext().getCacheDir();
        if (cacheDir == null || !cacheDir.exists()) return Result.success();

        File[] files = cacheDir.listFiles();
        long now     = System.currentTimeMillis();
        int  deleted = (files == null) ? 0 : sweepDir(files, now);

        // S08-M2: the files FileProvider ever grants a Uri for
        // (share_/cam_/grp_cam_/DuoShield_Export_) now live under
        // getCacheDir()/shared/<category>/ instead of the cache root — sweep
        // each category subdirectory with the identical rules used above.
        File sharedRoot = SharedCacheDir.root(getApplicationContext());
        File[] sharedCategories = sharedRoot.listFiles();
        if (sharedCategories != null) {
            for (File categoryDir : sharedCategories) {
                if (!categoryDir.isDirectory()) continue; // defensive; only subdirs expected here
                File[] categoryFiles = categoryDir.listFiles();
                if (categoryFiles != null) deleted += sweepDir(categoryFiles, now);
            }
        }

        if (deleted > 0) {
            Log.i(TAG, "Cleaned " + deleted + " decrypted temp file(s) from cache.");
        }
        return Result.success();
    }

    /**
     * Applies the deletion policy to one flat list of files (either
     * getCacheDir()'s top level, or one getCacheDir()/shared/<category>/
     * subdirectory) and returns how many were deleted. Shared between both
     * call sites in {@link #doWork} so the two locations cannot drift.
     */
    private int sweepDir(File[] files, long now) {
        int deleted = 0;
        for (File f : files) {
            long ageMs = now - f.lastModified();

            // Orphaned ChatExportHelper working directory (only survives a crash
            // mid-export; the helper deletes it itself on the normal success path).
            if (f.isDirectory() && f.getName().startsWith("chat_export_")) {
                if (ageMs >= MAX_AGE_MS) {
                    deleteRecursive(f);
                    deleted++;
                    Log.d(TAG, "Deleted orphaned export working dir: " + f.getName());
                }
                continue;
            }

            if (isExportZip(f.getName())) {
                if (ageMs >= MAX_AGE_EXPORT_ZIP_MS) {
                    if (f.delete()) {
                        deleted++;
                        Log.d(TAG, "Deleted export zip: " + f.getName()
                                + " (age " + (ageMs / 1000) + "s)");
                    }
                }
                continue;
            }

            if (!isTempMediaFile(f.getName())) continue;
            if (ageMs >= MAX_AGE_MS) {
                if (f.delete()) {
                    deleted++;
                    Log.d(TAG, "Deleted temp media: " + f.getName()
                            + " (age " + (ageMs / 1000) + "s)");
                } else {
                    Log.w(TAG, "Could not delete: " + com.duoshield.app.util.LogRedact.metadata(f.getName()));
                }
            }
        }
        return deleted;
    }

    /**
     * Schedules the periodic cleanup job once at app launch.
     * Safe to call on every app start — {@link ExistingPeriodicWorkPolicy#KEEP}
     * leaves an already-queued job untouched.
     */
    public static void schedule(Context context) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                TempFileCleaner.class, INTERVAL_MIN, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build();
        WorkManager.getInstance(context)
                   .enqueueUniquePeriodicWork(
                           WORK_TAG,
                           ExistingPeriodicWorkPolicy.KEEP,
                           req);
        Log.d(TAG, "TempFileCleaner scheduled (interval=" + INTERVAL_MIN + "min).");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isTempMediaFile(String name) {
        return (name.startsWith("voice_")           && name.endsWith(".3gp"))
            || (name.startsWith("voice_")           && name.endsWith(".m4a"))  // F25 fix: recorder uses .m4a
            // "vid_" also covers MediaViewerActivity's "vid_view_*.mp4" scratch
            // files (they share the vid_ prefix), so no separate rule is needed.
            || (name.startsWith("vid_")             && name.endsWith(".mp4"))
            || (name.startsWith("share_")           && name.endsWith(".jpg"))
            // S08-H3: plaintext camera captures handed to the camera app via
            // FileProvider — DuoShield never deletes these itself, so they
            // persist in the cache until swept here. "cam_" is 1:1 chat,
            // "grp_cam_" is the group-chat variant.
            || (name.startsWith("cam_")             && name.endsWith(".jpg"))
            || (name.startsWith("grp_cam_")         && name.endsWith(".jpg"))
            // S08-H3: plaintext video frame-extraction scratch. Normally deleted
            // in a finally block right after thumbnailing; matched here as a
            // defense so a crash mid-extraction cannot leave plaintext behind.
            || (name.startsWith("thumb_")           && name.endsWith(".mp4"))
            // S08-H3: encrypt-to-disk upload scratch (ciphertext, not plaintext).
            // Also normally deleted in a finally block; swept here to catch the
            // crash-orphaned case.
            || (name.startsWith("enc_")             && name.endsWith(".tmp"))
            // Legacy single-file export formats (superseded by ChatExportHelper's
            // ZIP archives) — kept so any stale files are still swept up.
            || (name.startsWith("duoshield_export_") && name.endsWith(".pdf"))
            || (name.startsWith("duoshield_export_") && name.endsWith(".txt"));
    }

    /** ChatExportHelper's full chat-export archives, e.g. "DuoShield_Export_1234.zip". */
    private static boolean isExportZip(String name) {
        return name.startsWith("DuoShield_Export_") && name.endsWith(".zip");
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
