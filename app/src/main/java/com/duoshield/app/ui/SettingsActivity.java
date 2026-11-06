package com.duoshield.app.ui;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.signature.ObjectKey;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.call.TurnBandwidthTracker;
import com.duoshield.app.util.AppUpdateHelper;
import com.duoshield.app.util.B2StorageHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.FirebaseFirestore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class SettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    private ImageView         ivProfilePhoto;
    private TextView          tvProfileDisplayName;

    private ActivityResultLauncher<String> pickPhotoLauncher;

    private static final String TAG = "Settings";
    private static final String AVATAR_FILE = "own_avatar.jpg";
    private static final int AVATAR_DIMENSION_PX = 1024;
    private static final int MAX_SOURCE_DIMENSION_PX = 20_000;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final int JPEG_QUALITY = 88;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    /** Monotonically increases for every photo load/selection; stale callbacks are ignored. */
    private final AtomicInteger photoGeneration = new AtomicInteger(0);
    private File pendingApkForInstall;
    private Dialog updateDownloadDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);

        // Register the profile photo picker before setContentView / super lifecycle
        pickPhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadProfilePhoto(uri); });

        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // ── Profile header ────────────────────────────────────────────────────
        ivProfilePhoto       = findViewById(R.id.ivProfilePhoto);
        tvProfileDisplayName = findViewById(R.id.tvProfileDisplayName);
        View btnChangePhoto  = findViewById(R.id.btnChangePhoto);
        View layoutProfileNameRow = findViewById(R.id.layoutProfileNameRow);

        // Populate name
        String myName = prefs.getString("my_display_name", null);
        if (tvProfileDisplayName != null)
            tvProfileDisplayName.setText(myName != null && !myName.isEmpty() ? myName : "—");

        // Load profile photo — local disk cache first (instant, no network),
        // then fall back to authenticated B2 download.
        loadProfilePhoto();

        // Restore display name from the cloud the same way the photo is restored:
        // a fresh install/new device has no local "my_display_name" pref yet, so
        // fetch the previously-saved Firestore value and repopulate it.
        reconcileDisplayName(myName);

        if (layoutProfileNameRow != null)
            layoutProfileNameRow.setOnClickListener(v -> showNameEditDialog());
        if (btnChangePhoto != null)
            btnChangePhoto.setOnClickListener(v -> pickPhotoLauncher.launch("image/*"));
        if (ivProfilePhoto != null)
            ivProfilePhoto.setOnClickListener(v -> openProfilePhotoViewer());

        // ── Navigation rows ───────────────────────────────────────────────────
        LinearLayout rowSecurityPrivacy = findViewById(R.id.rowSecurityPrivacy);
        if (rowSecurityPrivacy != null) {
            rowSecurityPrivacy.setOnClickListener(v ->
                startActivity(new Intent(this, SecurityPrivacySettingsActivity.class)));
        }

        LinearLayout rowAppearanceNotifications = findViewById(R.id.rowAppearanceNotifications);
        if (rowAppearanceNotifications != null) {
            rowAppearanceNotifications.setOnClickListener(v ->
                startActivity(new Intent(this, AppearanceNotificationsSettingsActivity.class)));
        }

        LinearLayout rowStarredMessages = findViewById(R.id.rowStarredMessages);
        if (rowStarredMessages != null) {
            rowStarredMessages.setOnClickListener(v -> {
                Intent intent = new Intent(this, com.duoshield.app.MessageSearchActivity.class);
                intent.putExtra(com.duoshield.app.MessageSearchActivity.EXTRA_GLOBAL_SEARCH, true);
                intent.putExtra(com.duoshield.app.MessageSearchActivity.EXTRA_STARRED_ONLY, true);
                startActivity(intent);
            });
        }

        LinearLayout rowBackupStorage = findViewById(R.id.rowBackupStorage);
        if (rowBackupStorage != null) {
            rowBackupStorage.setOnClickListener(v ->
                startActivity(new Intent(this, BackupStorageSettingsActivity.class)));
        }

        LinearLayout rowDangerZone = findViewById(R.id.rowDangerZone);
        if (rowDangerZone != null) {
            rowDangerZone.setOnClickListener(v ->
                startActivity(new Intent(this, DangerZoneSettingsActivity.class)));
        }

        // ── Call data usage row ───────────────────────────────────────────────
        LinearLayout rowCallDataUsage = findViewById(R.id.rowCallDataUsage);
        TextView     tvCallDataSubtitle = findViewById(R.id.tvCallDataSubtitle);

        TurnBandwidthTracker tracker = TurnBandwidthTracker.get(this);
        String subtitleText = String.format(Locale.US, "%.2f GB / 100 GB used this month",
                tracker.getUsedGb());
        if (tvCallDataSubtitle != null) tvCallDataSubtitle.setText(subtitleText);

        if (rowCallDataUsage != null) {
            rowCallDataUsage.setOnClickListener(v -> showCallDataDialog());
        }

        // ── App version footer ────────────────────────────────────────────────
        TextView tvAppVersion = findViewById(R.id.tvAppVersion);
        if (tvAppVersion != null) {
            String vName = com.duoshield.app.util.AppUpdateHelper.getVersionName(this);
            int    vCode = com.duoshield.app.util.AppUpdateHelper.getVersionCode(this);
            tvAppVersion.setText("DuoShield v" + vName + " (" + vCode + ")");
        }

        LinearLayout rowCheckForUpdates = findViewById(R.id.rowCheckForUpdates);
        if (rowCheckForUpdates != null) {
            rowCheckForUpdates.setOnClickListener(v -> checkForUpdates(true));
        }
        // Keep the feature discoverable without interrupting normal use: opening
        // Settings performs a silent check and only shows a dialog when a release exists.
        checkForUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingApkForInstall != null
                && AppUpdateHelper.canInstallPackages(this)) {
            File apk = pendingApkForInstall;
            pendingApkForInstall = null;
            AppUpdateHelper.install(this, apk, new AppUpdateHelper.InstallCallback() {
                @Override public void onPermissionRequired() { pendingApkForInstall = apk; }
                @Override public void onStarted() { }
                @Override public void onError(String message) {
                    Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void checkForUpdates(boolean showUpToDate) {
        TextView summary = findViewById(R.id.tvUpdateSummary);
        if (summary != null) summary.setText("Checking for updates…");
        AppUpdateHelper.checkForUpdate(this, new AppUpdateHelper.CheckCallback() {
            @Override public void onUpdateAvailable(AppUpdateHelper.ReleaseInfo info) {
                if (isFinishing() || isDestroyed()) return;
                if (summary != null) summary.setText("Version " + info.versionName + " is available");
                showUpdateDialog(info);
            }

            @Override public void onUpToDate(String currentVersion) {
                if (isFinishing() || isDestroyed()) return;
                if (summary != null) summary.setText("You’re up to date");
                if (showUpToDate) {
                    Toast.makeText(SettingsActivity.this,
                            "You’re already using the latest version.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                if (summary != null) summary.setText("Tap to check again");
                if (showUpToDate) {
                    Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showUpdateDialog(AppUpdateHelper.ReleaseInfo info) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("New DuoShield update")
                .setMessage("Version " + info.versionName
                        + " is ready to download. Your account and local data stay on this device, "
                        + "so you won’t need to log in again.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Download", (dialog, which) -> downloadUpdate(info))
                .show();
    }

    private void downloadUpdate(AppUpdateHelper.ReleaseInfo info) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int dp = (int) getResources().getDisplayMetrics().density;
        content.setPadding(24 * dp, 8 * dp, 24 * dp, 4 * dp);

        TextView status = new TextView(this);
        status.setText("Preparing secure download…");
        status.setTextColor(getResources().getColor(R.color.ds_text_secondary));
        status.setTextSize(14);
        content.addView(status);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8 * dp);
        progressParams.topMargin = 16 * dp;
        content.addView(progress, progressParams);

        updateDownloadDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Downloading update")
                .setView(content)
                .setCancelable(false)
                .show();

        AppUpdateHelper.download(this, info, new AppUpdateHelper.DownloadCallback() {
            @Override public void onProgress(int percent) {
                if (progress.getHandler() == null) return;
                progress.setProgress(Math.max(0, percent));
                status.setText(percent >= 0 ? "Downloading… " + percent + "%" : "Downloading…");
            }

            @Override public void onDownloaded(File apk) {
                if (updateDownloadDialog != null) updateDownloadDialog.dismiss();
                AppUpdateHelper.install(SettingsActivity.this, apk, new AppUpdateHelper.InstallCallback() {
                    @Override public void onPermissionRequired() {
                        pendingApkForInstall = apk;
                        new androidx.appcompat.app.AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("Allow installation")
                                .setMessage("Android needs permission to install updates downloaded inside DuoShield.")
                                .setNegativeButton("Later", null)
                                .setPositiveButton("Open settings", (d, w) ->
                                        AppUpdateHelper.openInstallPermissionSettings(SettingsActivity.this))
                                .show();
                    }
                    @Override public void onStarted() {
                        Toast.makeText(SettingsActivity.this,
                                "Opening Android installer…", Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onError(String message) {
                        Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override public void onError(String message) {
                if (updateDownloadDialog != null) updateDownloadDialog.dismiss();
                Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ── Call data usage dialog ────────────────────────────────────────────────

    private void showCallDataDialog() {
        TurnBandwidthTracker tracker = TurnBandwidthTracker.get(this);
        float usedGb      = tracker.getUsedGb();
        float remainingGb = (float) (tracker.getRemainingBytes() / (1024.0 * 1024.0 * 1024.0));
        int   percent     = tracker.getUsedPercent();

        // Compute next reset date (1st of next month)
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.add(Calendar.MONTH, 1);
        String resetDate = new SimpleDateFormat("MMMM d, yyyy", Locale.US).format(cal.getTime());

        // Build the dialog content view
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int dp = (int) getResources().getDisplayMetrics().density;
        root.setPadding(24 * dp, 20 * dp, 24 * dp, 8 * dp);

        // Progress bar
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(percent);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 10 * dp));
        // Tint: green → amber → red
        int barColor;
        if (percent < 70)      barColor = 0xFF00C9E0; // cyan accent
        else if (percent < 90) barColor = 0xFFFFB300; // amber
        else                   barColor = 0xFFFF5252; // red
        bar.getProgressDrawable().setColorFilter(
                barColor, android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(bar);

        // Percentage label
        TextView tvPct = new TextView(this);
        tvPct.setText(String.format(Locale.US, "%d%% of 100 GB used", percent));
        tvPct.setTextColor(barColor);
        tvPct.setTextSize(13f);
        LinearLayout.LayoutParams pctParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pctParams.topMargin = 8 * dp;
        tvPct.setLayoutParams(pctParams);
        root.addView(tvPct);

        // Stats rows
        root.addView(makeDialogRow("Used this month",
                String.format(Locale.US, "%.2f GB", usedGb), dp));
        root.addView(makeDialogRow("Remaining",
                String.format(Locale.US, "%.2f GB", remainingGb), dp));
        root.addView(makeDialogRow("Resets on", resetDate, dp));

        // Note
        TextView tvNote = new TextView(this);
        tvNote.setText("Only calls relayed through TURN count toward this limit. "
                + "Direct peer-to-peer calls are free and not included.");
        tvNote.setTextSize(11f);
        tvNote.setTextColor(0xFF888888);
        tvNote.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = 16 * dp;
        tvNote.setLayoutParams(noteParams);
        root.addView(tvNote);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Call Data Usage")
                .setView(root)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Helper: a two-column row for the call data dialog. */
    private LinearLayout makeDialogRow(String label, String value, int dp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = 12 * dp;
        row.setLayoutParams(rowParams);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(0xFFAAAAAA);
        tvLabel.setTextSize(13f);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(Color.WHITE);
        tvValue.setTextSize(13f);
        tvValue.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(tvValue);

        return row;
    }

    // ── Profile name edit ─────────────────────────────────────────────────────

    private void showNameEditDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setHint("Your display name");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        String current = prefs.getString("my_display_name", "");
        et.setText(current);
        et.setSelection(et.getText().length());

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(et);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Change Display Name")
                .setView(container)
                .setPositiveButton("Save", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name cannot be empty.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString("my_display_name", name).apply();
                    if (tvProfileDisplayName != null) tvProfileDisplayName.setText(name);
                    // Persist to Firestore so other users/devices see the new name
                    saveNameToFirestore(name);
                    // ConversationListActivity reads the denormalized partnerName_{uid}
                    // field on each chat doc rather than users/{uid}.displayName, so
                    // existing conversations must be updated directly or they keep
                    // showing the old name.
                    propagateNameToConversations(name);
                    Toast.makeText(this, "Name updated.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Publishes the user and denormalized chat references in one Firestore batch. */
    private Task<Void> publishPhotoReferences(String myUid, String photoUrl) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        return db.collection("chats").whereArrayContains("participants", myUid).get()
                .continueWithTask(queryTask -> {
                    if (!queryTask.isSuccessful()) {
                        Exception error = queryTask.getException();
                        throw error != null ? error : new Exception("Conversation query failed");
                    }
                    com.google.firebase.firestore.WriteBatch batch = db.batch();
                    batch.set(db.collection("users").document(myUid),
                            Collections.singletonMap("photoUrl", photoUrl),
                            com.google.firebase.firestore.SetOptions.merge());
                    for (com.google.firebase.firestore.DocumentSnapshot doc
                            : queryTask.getResult().getDocuments()) {
                        java.util.List<String> participants =
                                (java.util.List<String>) doc.get("participants");
                        if (participants == null) continue;
                        String partnerUid = null;
                        for (String p : participants) {
                            if (p != null && !p.equals(myUid)) { partnerUid = p; break; }
                        }
                        if (partnerUid != null) {
                            batch.update(doc.getReference(),
                                    "partnerPhotoUrl_" + partnerUid, photoUrl);
                        }
                    }
                    return batch.commit();
                });
    }

    private void propagateNameToConversations(String myName) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String myUid = user.getUid();
        FirebaseFirestore.getInstance()
                .collection("chats")
                .whereArrayContains("participants", myUid)
                .get()
                .addOnSuccessListener(snap -> {
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                        java.util.List<String> participants =
                                (java.util.List<String>) doc.get("participants");
                        if (participants == null) continue;
                        String partnerUid = null;
                        for (String p : participants) {
                            if (p != null && !p.equals(myUid)) { partnerUid = p; break; }
                        }
                        if (partnerUid == null) continue;
                        doc.getReference()
                           .update("partnerName_" + partnerUid, myName)
                           .addOnFailureListener(e ->
                               android.util.Log.w("Settings", "propagateName non-critical ("
                                       + e.getClass().getSimpleName() + ")"));
                    }
                })
                .addOnFailureListener(e ->
                        android.util.Log.w("Settings", "propagateName query failed (non-critical, "
                                + e.getClass().getSimpleName() + ")"));
    }

    private void saveNameToFirestore(String name) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .set(Collections.singletonMap("displayName", name),
                        com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener(e ->
                        android.util.Log.w("Settings", "displayName Firestore write failed ("
                                + e.getClass().getSimpleName() + ")"));
    }

    // ── Profile photo upload (B2) ─────────────────────────────────────────────

    private void uploadProfilePhoto(Uri uri) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show();
            return;
        }
        // Invalidate every earlier remote reconciliation as soon as the user chooses a new
        // image. This is the same last-write-wins rule users expect from WhatsApp.
        final int generation = photoGeneration.incrementAndGet();
        Toast.makeText(this, "Preparing photo…", Toast.LENGTH_SHORT).show();
        bgExecutor.execute(() -> {
            try {
                byte[] jpeg = normalizeAvatar(uri);
                String objectKey = "avatars/" + user.getUid() + "_"
                        + System.currentTimeMillis() + ".jpg";
                String b2Path = B2StorageHelper.uploadFile(jpeg, objectKey, "image/jpeg", null);
                runOnUiThread(() -> {
                    if (generation == photoGeneration.get()) {
                        publishUploadedPhoto(b2Path, jpeg, user, generation);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e(TAG, "Photo upload failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                runOnUiThread(() -> {
                    if (generation == photoGeneration.get()) {
                        Toast.makeText(this, "Upload failed: " + safeMessage(e),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** Local-first display followed by an authoritative Firestore reconciliation. */
    /**
     * Mirrors {@link #loadProfilePhoto()}: if this device has no locally-saved
     * display name (fresh install / new device), fetch the previously-saved
     * name from {@code users/{uid}.displayName} and repopulate it, both in
     * prefs and on screen.
     */
    private void reconcileDisplayName(String localName) {
        if (localName != null && !localName.trim().isEmpty()) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        FirebaseFirestore.getInstance().collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    if (isFinishing() || isDestroyed()) return;
                    String remoteName = doc.getString("displayName");
                    if (remoteName == null || remoteName.trim().isEmpty()) return;
                    // Another write may have landed while this request was in flight.
                    String currentLocal = prefs.getString("my_display_name", null);
                    if (currentLocal != null && !currentLocal.trim().isEmpty()) return;

                    prefs.edit().putString("my_display_name", remoteName).apply();
                    if (tvProfileDisplayName != null) tvProfileDisplayName.setText(remoteName);
                })
                .addOnFailureListener(e ->
                        android.util.Log.w(TAG, "Could not reconcile display name (" + com.duoshield.app.util.LogRedact.error(e) + ")"));
    }

    private void loadProfilePhoto() {
        if (ivProfilePhoto == null) return;
        applyPlaceholderAvatarView();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        final int generation = photoGeneration.incrementAndGet();
        File localAvatar = new File(getFilesDir(), AVATAR_FILE);
        String cachedUid = prefs.getString("own_avatar_uid", null);
        if (localAvatar.exists() && user.getUid().equals(cachedUid)) {
            displayLocalAvatar(localAvatar);
        } else if (localAvatar.exists() && !localAvatar.delete()) {
            android.util.Log.w(TAG, "Could not remove avatar belonging to another session");
        }

        FirebaseFirestore.getInstance().collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    String remotePath = doc.getString("photoUrl");
                    if (generation != photoGeneration.get()
                            || !isOwnedAvatarPath(remotePath, user.getUid())) return;
                    String cachedPath = prefs.getString("my_photo_url", null);
                    boolean validLocal = localAvatar.exists() && localAvatar.length() > 0
                            && user.getUid().equals(prefs.getString("own_avatar_uid", null));
                    if (remotePath.equals(cachedPath) && validLocal) return;
                    restoreAvatarFromB2(remotePath, user.getUid(), generation);
                })
                .addOnFailureListener(e ->
                        android.util.Log.w(TAG, "Could not reconcile profile photo (" + com.duoshield.app.util.LogRedact.error(e) + ")"));
    }

    private void restoreAvatarFromB2(String b2Path, String uid, int generation) {
        B2StorageHelper.loadAvatarBytes(b2Path, new B2StorageHelper.MediaCallback() {
            @Override public void onLoaded(byte[] bytes) {
                if (generation != photoGeneration.get()) return;
                if (!isValidAvatarJpeg(bytes)) {
                    android.util.Log.w(TAG, "Rejected invalid remote avatar bytes");
                    return;
                }
                bgExecutor.execute(() -> {
                    if (generation != photoGeneration.get()) return;
                    try {
                        saveOwnAvatarToDisk(bytes);
                        if (generation != photoGeneration.get()) return;
                        prefs.edit().putString("my_photo_url", b2Path)
                                .putString("own_avatar_uid", uid).commit();
                        runOnUiThread(() -> {
                            if (generation == photoGeneration.get()
                                    && !isFinishing() && !isDestroyed()) {
                                displayLocalAvatar(new File(getFilesDir(), AVATAR_FILE));
                            }
                        });
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "Failed to cache restored avatar (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                    }
                });
            }
            @Override public void onError(Exception e) {
                android.util.Log.w(TAG, "Remote avatar restore failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            }
        });
    }

    private void publishUploadedPhoto(String b2Path, byte[] jpeg, FirebaseUser user,
                                      int generation) {
        String previousPath = prefs.getString("my_photo_url", null);
        // publishPhotoReferences writes users/{uid}.photoUrl and every
        // partnerPhotoUrl_<uid> chat field in a single atomic batch, so the
        // user document can never land without the denormalized chat copies.
        publishPhotoReferences(user.getUid(), b2Path)
                .addOnSuccessListener(ignored -> bgExecutor.execute(() -> {
                    if (generation != photoGeneration.get()) return;
                    try {
                        saveOwnAvatarToDisk(jpeg);
                        if (generation != photoGeneration.get()) return;
                        prefs.edit().putString("my_photo_url", b2Path)
                                .putString("own_avatar_uid", user.getUid()).commit();
                        if (isOwnedAvatarPath(previousPath, user.getUid())
                                && !previousPath.equals(b2Path)) {
                            try { B2StorageHelper.deleteFile(previousPath); }
                            catch (Exception e) {
                                android.util.Log.w(TAG, "Old avatar cleanup failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                            }
                        }
                        runOnUiThread(() -> {
                            if (generation == photoGeneration.get()) finishPhotoPublication(jpeg);
                        });
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "Local avatar commit failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                        runOnUiThread(() -> Toast.makeText(this,
                                "Photo published, but local cache failed.", Toast.LENGTH_LONG).show());
                    }
                }))
                .addOnFailureListener(e -> {
                    android.util.Log.e(TAG, "Photo publication failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                    bgExecutor.execute(() -> {
                        try { B2StorageHelper.deleteFile(b2Path); }
                        catch (Exception cleanupError) {
                            android.util.Log.w(TAG, "Orphan avatar cleanup failed (" + com.duoshield.app.util.LogRedact.error(cleanupError) + ")");
                        }
                    });
                    Toast.makeText(this, "Photo update failed: " + safeMessage(e),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void finishPhotoPublication(byte[] jpeg) {
        if (ivProfilePhoto != null && !isDestroyed() && !isFinishing()) {
            prepareRealAvatarView();
            Glide.with(this).clear(ivProfilePhoto);
            Glide.with(this).load(jpeg)
                    .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                    .placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                    .transform(new CircleCrop()).into(ivProfilePhoto);
        }
        Toast.makeText(this, "Photo updated!", Toast.LENGTH_SHORT).show();
    }

    private void displayLocalAvatar(File file) {
        if (ivProfilePhoto == null || file == null || !file.exists() || file.length() <= 0) return;
        prepareRealAvatarView();
        Glide.with(this).clear(ivProfilePhoto);
        Glide.with(this).load(file)
                .signature(new ObjectKey(file.lastModified() + ":" + file.length()))
                .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                .placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                .transform(new CircleCrop()).into(ivProfilePhoto);
    }

    /** Removes placeholder-only styling before a real image is bound. */
    private void prepareRealAvatarView() {
        if (ivProfilePhoto == null) return;
        ivProfilePhoto.setPadding(0, 0, 0, 0);
        ivProfilePhoto.clearColorFilter();
        ivProfilePhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    /** Applies placeholder-only styling for the default ic_person avatar. */
    private void applyPlaceholderAvatarView() {
        if (ivProfilePhoto == null) return;
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        ivProfilePhoto.setPadding(pad, pad, pad, pad);
        ivProfilePhoto.setColorFilter(0xFF9A81FF);
        ivProfilePhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivProfilePhoto.setImageResource(R.drawable.ic_person);
    }

    private void saveOwnAvatarToDisk(byte[] bytes) throws Exception {
        File destination = new File(getFilesDir(), AVATAR_FILE);
        File temporary = new File(getFilesDir(), AVATAR_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            throw new Exception("Could not replace local avatar");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new Exception("Could not commit local avatar");
        }
    }

    private byte[] normalizeAvatar(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Cannot open selected image");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > MAX_SOURCE_DIMENSION_PX
                || bounds.outHeight > MAX_SOURCE_DIMENSION_PX
                || (long) bounds.outWidth * bounds.outHeight > MAX_SOURCE_PIXELS) {
            throw new Exception("Image dimensions are invalid or too large");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample)
                > AVATAR_DIMENSION_PX * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Cannot reopen selected image");
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new Exception("Unsupported or damaged image");

        Bitmap oriented = applyExifOrientation(decoded, readExifOrientation(uri));
        int side = Math.min(oriented.getWidth(), oriented.getHeight());
        Bitmap square = Bitmap.createBitmap(oriented,
                (oriented.getWidth() - side) / 2, (oriented.getHeight() - side) / 2,
                side, side);
        Bitmap output = square;
        if (side > AVATAR_DIMENSION_PX) {
            output = Bitmap.createScaledBitmap(square,
                    AVATAR_DIMENSION_PX, AVATAR_DIMENSION_PX, true);
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        boolean compressed = output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, encoded);
        byte[] result = encoded.toByteArray();
        if (output != square) output.recycle();
        if (square != oriented) square.recycle();
        if (oriented != decoded) oriented.recycle();
        decoded.recycle();
        if (!compressed || result.length == 0 || result.length > MAX_AVATAR_BYTES) {
            throw new Exception("Processed photo is too large");
        }
        return result;
    }

    private int readExifOrientation(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            return new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            android.util.Log.d(TAG, "No readable EXIF orientation", e);
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private Bitmap applyExifOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private boolean isValidAvatarJpeg(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_AVATAR_BYTES) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return "image/jpeg".equals(bounds.outMimeType)
                && bounds.outWidth > 0 && bounds.outHeight > 0
                && bounds.outWidth <= AVATAR_DIMENSION_PX
                && bounds.outHeight <= AVATAR_DIMENSION_PX;
    }

    private boolean isOwnedAvatarPath(String path, String uid) {
        if (path == null || uid == null) return false;
        String prefix = "b2:avatars/" + uid + "_";
        if (!path.startsWith(prefix) || !path.endsWith(".jpg")) return false;
        String timestamp = path.substring(prefix.length(), path.length() - 4);
        return timestamp.matches("[0-9]{10,17}");
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? "Unknown error" : e.getMessage();
    }

    private void openProfilePhotoViewer() {
        // Prefer the locally cached file — instant load, no network needed.
        java.io.File localFile = new java.io.File(getFilesDir(), "own_avatar.jpg");
        String urlToShow;
        if (localFile.exists() && localFile.length() > 0) {
            urlToShow = localFile.toURI().toString(); // file:///data/user/0/.../own_avatar.jpg
        } else {
            urlToShow = prefs.getString("my_photo_url", null);
        }
        if (urlToShow == null || urlToShow.isEmpty()) {
            Toast.makeText(this, "No profile photo set yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, com.duoshield.app.FullScreenImageActivity.class);
        i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_URL, urlToShow);
        i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_IS_PROFILE_PHOTO, true);
        // No media key — avatars are plain JPEGs (not AES-encrypted).
        startActivity(i);
    }

    private byte[] readUriBytes(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buf = new byte[32_768];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            android.util.Log.e("Settings", "readUriBytes failed (" + com.duoshield.app.util.LogRedact.error(e) + ")");
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        photoGeneration.incrementAndGet();
        if (updateDownloadDialog != null && updateDownloadDialog.isShowing()) {
            updateDownloadDialog.dismiss();
            updateDownloadDialog = null;
        }
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
