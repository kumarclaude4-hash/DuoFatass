package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Contact;
import com.duoshield.app.models.GroupMember;
import com.duoshield.app.models.Message;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a full conversation (1:1 or group) to a single ZIP archive:
 *
 * <pre>
 * DuoShield_Export_&lt;ts&gt;.zip
 *  ├─ Chat.txt                        (full plain-text transcript)
 *  ├─ Voice Messages/
 *  │   ├─ Me/...
 *  │   └─ &lt;Other sender&gt;/...
 *  ├─ Photos/
 *  │   ├─ Me/...
 *  │   └─ &lt;Other sender&gt;/...
 *  └─ Videos/
 *      ├─ Me/...
 *      └─ &lt;Other sender&gt;/...
 * </pre>
 *
 * Media is optional (user choice at export time) and, for groups, is split
 * into one sub-folder per sender rather than just two — the same per-sender
 * separation rule applies regardless of chat type. Category folders are only
 * created when they actually contain at least one file.
 *
 * <p>Each media file is streamed straight from B2 to disk and decrypted in
 * place (see {@link B2StorageHelper#downloadAndDecryptToFile}) — the full
 * plaintext of a large video is never held in memory at once, mirroring the
 * streaming guarantee already made on the upload side.
 *
 * <p>Progress is reported live via an {@link ExportProgressUi} dialog so a
 * chat with hundreds of messages and media files doesn't look stalled.
 */
public final class ChatExportHelper {

    private static final String TAG = "ChatExportHelper";

    private ChatExportHelper() {}

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Shows the unencrypted-export warning with an "include media" checkbox,
     * then kicks off the export on confirmation.
     *
     * @param partnerUid required for 1:1 chats (used to resolve the other
     *                   participant's display name); pass {@code null} for groups.
     */
    public static void showExportDialog(Activity activity, String conversationId,
                                         String partnerUid, boolean isGroup) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (24 * dp);
        container.setPadding(padH, (int) (4 * dp), padH, 0);

        CheckBox includeMediaBox = new CheckBox(activity);
        includeMediaBox.setText("Include photos, videos & voice messages");
        includeMediaBox.setChecked(true);
        container.addView(includeMediaBox);

        new MaterialAlertDialogBuilder(activity)
            .setTitle("Export Chat — Unencrypted")
            .setMessage(
                "This creates a ZIP file with your full chat as a text file, and "
                + "optionally the media that was sent, sorted into folders by sender.\n\n"
                + "⚠️  Nothing in the ZIP is encrypted. Anyone you share it with can "
                + "read every message and view every media file.")
            .setView(container)
            .setPositiveButton("Export", (d, w) ->
                exportChat(activity, conversationId, partnerUid, includeMediaBox.isChecked(), isGroup))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Runs the export on a background thread and shares the resulting ZIP when done. */
    public static void exportChat(Activity activity, String conversationId, String partnerUid,
                                   boolean includeMedia, boolean isGroup) {
        Context appCtx = activity.getApplicationContext();
        ExportProgressUi progress = ExportProgressUi.show(activity);
        new Thread(() -> {
            try {
                doExport(appCtx, conversationId, partnerUid, includeMedia, isGroup, progress);
            } catch (Exception e) {
                Log.e(TAG, "Chat export failed (" + e.getClass().getSimpleName() + ")");
                progress.dismiss();
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appCtx, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Core export ──────────────────────────────────────────────────────────

    private static void doExport(Context ctx, String conversationId, String partnerUid,
                                  boolean includeMedia, boolean isGroup,
                                  ExportProgressUi progress) throws IOException {
        AppDatabase db = AppDatabase.getInstance(ctx);
        List<Message> msgs = db.messageDao().getMessages(conversationId);

        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String myUid = prefs.getString("my_uid", null);

        Map<String, String> senderLabel = buildSenderLabels(db, conversationId, myUid, partnerUid, isGroup);

        File workDir = new File(ctx.getCacheDir(), "chat_export_" + System.currentTimeMillis());
        deleteRecursive(workDir);
        if (!workDir.mkdirs()) throw new IOException("Could not create export working directory");

        progress.update("Writing transcript…", 0, 0);
        writeTranscript(new File(workDir, "Chat.txt"), msgs, senderLabel);

        int saved = 0, failed = 0;
        if (includeMedia) {
            int total = msgs.size();
            int idx = 0;
            for (Message m : msgs) {
                idx++;
                progress.update("Exporting media — message " + idx + " of " + total
                    + " (" + saved + " saved" + (failed > 0 ? ", " + failed + " failed" : "") + ")",
                    idx, total);

                if (m.isDeleted()) continue;
                String sender = senderLabel.get(m.getSender());
                if (sender == null) sender = sanitize(shortUid(m.getSender()));
                String type = m.getMediaType();

                if ("album".equals(type) && m.getMediaItems() != null) {
                    try {
                        JSONArray arr = new JSONArray(m.getMediaItems());
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.getJSONObject(i);
                            String itemType = item.optString("type", "image");
                            boolean isVideo = "video".equals(itemType);
                            String category = isVideo ? "Videos" : "Photos";
                            String ext = isVideo ? ".mp4" : ".jpg";
                            boolean ok = saveMediaFile(workDir, category, sender,
                                item.optString("path", null), item.optString("key", null),
                                m.getId() + "_" + i + ext);
                            if (ok) saved++; else failed++;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "album mediaItems parse failed for msg=" + m.getId(), e);
                        failed++;
                    }
                } else if ("voice".equals(type)) {
                    if (saveMediaFile(workDir, "Voice Messages", sender,
                            m.getMediaUrl(), m.getMediaKey(), m.getId() + ".m4a")) saved++; else failed++;
                } else if ("image".equals(type)) {
                    if (saveMediaFile(workDir, "Photos", sender,
                            m.getMediaUrl(), m.getMediaKey(), m.getId() + ".jpg")) saved++; else failed++;
                } else if ("video".equals(type)) {
                    if (saveMediaFile(workDir, "Videos", sender,
                            m.getMediaUrl(), m.getMediaKey(), m.getId() + ".mp4")) saved++; else failed++;
                }
            }
        }

        progress.update("Compressing archive…", 0, 0);
        // S08-M2: write into the FileProvider-scoped shared/export/ subdir
        // rather than the cache root, so the grant issued by shareZip() below
        // is not scoped to the whole cache directory.
        File zipFile = new File(SharedCacheDir.export(ctx),
                "DuoShield_Export_" + System.currentTimeMillis() + ".zip");
        zipDirectory(workDir, zipFile, progress);
        deleteRecursive(workDir);

        progress.dismiss();
        int finalSaved = saved, finalFailed = failed;
        new Handler(Looper.getMainLooper()).post(() -> {
            shareZip(ctx, zipFile);
            String msg = includeMedia
                ? "Export ready — " + finalSaved + " media file(s) included"
                  + (finalFailed > 0 ? ", " + finalFailed + " failed" : "")
                : "Export ready";
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        });
    }

    // ── Sender → folder/label resolution ────────────────────────────────────

    private static Map<String, String> buildSenderLabels(AppDatabase db, String conversationId,
                                                           String myUid, String partnerUid, boolean isGroup) {
        Map<String, String> map = new HashMap<>();
        if (myUid != null) map.put(myUid, "Me");

        if (isGroup) {
            List<GroupMember> members = db.groupDao().getMembersOf(conversationId);
            for (GroupMember gm : members) {
                if (myUid != null && myUid.equals(gm.memberUid)) continue;
                map.put(gm.memberUid, labelFor(gm.displayName, gm.memberUid));
            }
        } else if (partnerUid != null) {
            Contact c = db.contactDao().getByUid(partnerUid);
            String name = (c != null) ? c.displayName : null;
            map.put(partnerUid, labelFor(name, partnerUid));
        }
        return map;
    }

    /** "DisplayName (a1b2c3d4)" when a name is known, otherwise just the short UID. */
    private static String labelFor(String displayName, String uid) {
        String uidShort = shortUid(uid);
        String label = (displayName != null && !displayName.trim().isEmpty())
            ? displayName.trim() + " (" + uidShort + ")"
            : uidShort;
        return sanitize(label);
    }

    private static String shortUid(String uid) {
        if (uid == null) return "unknown";
        return uid.substring(0, Math.min(8, uid.length()));
    }

    /** Strips characters that are illegal in file/folder names on Android's filesystem. */
    private static String sanitize(String name) {
        if (name == null) return "Unknown";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "Unknown" : cleaned;
    }

    // ── Transcript ───────────────────────────────────────────────────────────

    private static void writeTranscript(File outFile, List<Message> msgs,
                                         Map<String, String> senderLabel) throws IOException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());

        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write("DuoShield Chat Export\n");
            fw.write("Exported: " + sdf.format(new Date()) + "\n");
            fw.write("─────────────────────────────────────────────────\n");
            for (Message m : msgs) {
                if (m.isDeleted()) continue;
                String time   = sdf.format(new Date(m.getTimestamp()));
                String sender = senderLabel.getOrDefault(m.getSender(), shortUid(m.getSender()));
                String text   = MessageLabelHelper.describePlain(m) + (m.isEdited() ? " (edited)" : "");
                fw.write("[" + time + "] " + sender + ": " + text + "\n");
            }
        }
    }

    // ── Media download + save ───────────────────────────────────────────────

    /**
     * Saves one encrypted media item, decrypted, to {@code workDir/category/sender/fileName}.
     *
     * <p>When the plaintext is already sitting in {@link B2StorageHelper}'s in-memory cache
     * (small recently-viewed files), it's written out directly. Otherwise the file is
     * streamed from B2 straight to disk and decrypted in place via
     * {@link B2StorageHelper#downloadAndDecryptToFile} — the full plaintext of a large
     * video is never materialized as a single in-memory {@code byte[]}, so a group chat
     * with several large videos can't OOM the export.
     *
     * <p>Returns {@code false} without throwing on any failure so a single bad file never
     * aborts the whole export.
     */
    private static boolean saveMediaFile(File workDir, String category, String sender,
                                          String b2Path, String keyBase64, String fileName) {
        if (b2Path == null || b2Path.isEmpty() || !B2StorageHelper.isB2Path(b2Path)) return false;
        try {
            File dir = new File(new File(workDir, category), sender);
            if (!dir.exists() && !dir.mkdirs()) return false;
            File out = new File(dir, fileName);

            byte[] cached = B2StorageHelper.getCached(b2Path);
            if (cached != null) {
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(cached);
                }
            } else {
                B2StorageHelper.downloadAndDecryptToFile(b2Path, keyBase64, out);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to export media (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    // ── Zip + share ──────────────────────────────────────────────────────────

    private static void zipDirectory(File sourceDir, File zipFile,
                                      ExportProgressUi progress) throws IOException {
        int totalFiles = countFiles(sourceDir);
        int[] done = {0};
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            zipRecursive(sourceDir, sourceDir, zos, progress, done, totalFiles);
        }
    }

    private static int countFiles(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int count = 0;
        for (File f : children) {
            count += f.isDirectory() ? countFiles(f) : 1;
        }
        return count;
    }

    private static void zipRecursive(File root, File current, ZipOutputStream zos,
                                      ExportProgressUi progress, int[] done, int total) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                zipRecursive(root, f, zos, progress, done, total);
                continue;
            }
            String relPath = root.toURI().relativize(f.toURI()).getPath();
            zos.putNextEntry(new ZipEntry(relPath));
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) zos.write(buf, 0, n);
            }
            zos.closeEntry();
            done[0]++;
            if (total > 0) {
                progress.update("Compressing archive — " + done[0] + " of " + total + " files",
                    done[0], total);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private static void shareZip(Context ctx, File zipFile) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".provider", zipFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(intent, "Export Chat")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.e(TAG, "Share intent failed for " + zipFile, e);
            Toast.makeText(ctx, "Export saved but couldn't open the share sheet.", Toast.LENGTH_LONG).show();
        }
    }

    // ── Progress UI ──────────────────────────────────────────────────────────

    /**
     * Thin wrapper around a non-cancelable {@link AlertDialog} with a status line and a
     * progress bar, safe to update from a background thread. Holds only a
     * {@link WeakReference} to the {@link Activity} and no-ops once it's finishing/destroyed,
     * so a slow export can never crash or leak a window if the user navigates away.
     *
     * <p>A "Run in background" button lets the user dismiss the dialog without cancelling
     * the export — it keeps running and shares the ZIP via the share sheet when done.
     */
    private static final class ExportProgressUi {
        private final WeakReference<Activity> activityRef;
        private final AlertDialog dialog;
        private final ProgressBar bar;
        private final TextView statusView;

        private ExportProgressUi(Activity activity, AlertDialog dialog,
                                  ProgressBar bar, TextView statusView) {
            this.activityRef = new WeakReference<>(activity);
            this.dialog = dialog;
            this.bar = bar;
            this.statusView = statusView;
        }

        static ExportProgressUi show(Activity activity) {
            float dp = activity.getResources().getDisplayMetrics().density;
            LinearLayout container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.VERTICAL);
            int padH = (int) (24 * dp);
            int padV = (int) (8 * dp);
            container.setPadding(padH, padV, padH, padV);

            TextView statusView = new TextView(activity);
            statusView.setText("Preparing export…");
            container.addView(statusView);

            ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            bar.setMax(100);
            bar.setProgress(0);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            barParams.topMargin = (int) (16 * dp);
            container.addView(bar, barParams);

            AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Exporting Chat")
                .setView(container)
                .setCancelable(false)
                .setNegativeButton("Run in background", null)
                .create();
            dialog.show();
            return new ExportProgressUi(activity, dialog, bar, statusView);
        }

        /** {@code max == 0} shows an indeterminate spinner; otherwise a determinate bar. */
        void update(String status, int current, int max) {
            runOnMain(() -> {
                if (!isAlive()) return;
                statusView.setText(status);
                if (max > 0) {
                    bar.setIndeterminate(false);
                    bar.setMax(max);
                    bar.setProgress(Math.min(current, max));
                } else {
                    bar.setIndeterminate(true);
                }
            });
        }

        void dismiss() {
            runOnMain(() -> {
                if (isAlive() && dialog.isShowing()) dialog.dismiss();
            });
        }

        private boolean isAlive() {
            Activity a = activityRef.get();
            return a != null && !a.isFinishing() && !a.isDestroyed() && dialog.isShowing();
        }

        private void runOnMain(Runnable r) {
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else new Handler(Looper.getMainLooper()).post(r);
        }
    }
}
