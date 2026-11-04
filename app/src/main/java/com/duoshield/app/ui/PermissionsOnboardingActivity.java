package com.duoshield.app.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.duoshield.app.MainActivity;
import com.duoshield.app.R;
import com.duoshield.app.util.BackPressGuard;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run permissions screen.
 *
 * <p>Shown exactly once, on the very first launch, before the normal auth
 * routing takes over. It explains why each capability is needed and then
 * requests all of DuoShield's essential runtime (dangerous) permissions in a
 * single batch so the user isn't ambushed by scattered system dialogs later
 * while messaging, calling, or sharing media.</p>
 *
 * <p>Pre-auth screen — extends {@link AppCompatActivity}, NOT BaseActivity, so
 * it never triggers the lock/offline gates before the user has an account.</p>
 *
 * <p>Whatever the user grants or denies here, control always proceeds to
 * {@link MainActivity} (the routing trampoline), which decides between the
 * conversation list and sign-in. The one-shot flag guarantees this screen is
 * skipped on every subsequent launch — the individual feature screens still
 * re-request anything that was denied, exactly as before.</p>
 */
public class PermissionsOnboardingActivity extends AppCompatActivity {

    private static final String TAG   = "PermsOnboarding";
    private static final String PREFS = "duoshield_prefs";
    /** Set once the first-run permission prompt has been shown (granted OR denied). */
    public static final String KEY_FIRST_RUN_DONE = "first_run_permissions_done";

    private static final int REQ_ESSENTIAL = 900;

    /** True once this launch has already shown/completed first-run permissions. */
    public static boolean isCompleted(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_FIRST_RUN_DONE, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackPressGuard.onBack(this, this::finishAndProceed);
        setContentView(R.layout.activity_permissions_onboarding);

        // BLUETOOTH_CONNECT is only a runtime permission on Android 12 (API 31)+.
        // On older versions there is nothing to request, so hide that row to avoid
        // promising a prompt that will never appear.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            View btRow = findViewById(R.id.rowBluetooth);
            if (btRow != null) btRow.setVisibility(View.GONE);
        }

        MaterialButton btnContinue = findViewById(R.id.btnContinue);
        View tvSkip = findViewById(R.id.tvSkip);

        btnContinue.setOnClickListener(v -> requestEssentialPermissions());
        if (tvSkip != null) tvSkip.setOnClickListener(v -> finishAndProceed());
    }

    private void requestEssentialPermissions() {
        List<String> needed = new ArrayList<>();

        addIfNotGranted(needed, Manifest.permission.CAMERA);
        addIfNotGranted(needed, Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfNotGranted(needed, Manifest.permission.POST_NOTIFICATIONS);
            addIfNotGranted(needed, Manifest.permission.READ_MEDIA_IMAGES);
            addIfNotGranted(needed, Manifest.permission.READ_MEDIA_VIDEO);
            addIfNotGranted(needed, Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            // maxSdkVersion=32 in the manifest, so only meaningful below API 33.
            addIfNotGranted(needed, Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfNotGranted(needed, Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (needed.isEmpty()) {
            // Everything already granted (e.g. re-run before flag was set) — just move on.
            finishAndProceed();
            return;
        }

        ActivityCompat.requestPermissions(
                this, needed.toArray(new String[0]), REQ_ESSENTIAL);
    }

    private void addIfNotGranted(List<String> list, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
            list.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_ESSENTIAL) {
            int granted = 0;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) granted++;
            }
            Log.i(TAG, "Essential permissions result: granted " + granted
                    + " of " + grantResults.length);
            // DuoShield works whether or not each was granted; feature screens
            // re-prompt on demand for anything the user declined here.
            finishAndProceed();
        }
    }

    /** Persist the one-shot flag and hand off to the normal routing trampoline. */
    private void finishAndProceed() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FIRST_RUN_DONE, true).apply();

        Intent next = new Intent(this, MainActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(next);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
