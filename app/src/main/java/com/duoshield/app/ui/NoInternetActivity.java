package com.duoshield.app.ui;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.util.NetworkStateHelper;
import com.duoshield.app.util.BackPressGuard;
import com.google.android.material.button.MaterialButton;

/**
 * Full-screen blocking gate shown whenever the app cannot reach its server.
 *
 * <p>The app enforces a hard "no offline use" policy: {@code NetworkStateHelper.blockIfOffline}
 * launches this screen from every functional screen's {@code onStart()}. It cannot be
 * dismissed by the back button (that only sends the task to the background); it finishes
 * itself the moment the push server is confirmed reachable.
 *
 * <p><b>Dismissal requires a server round-trip, not merely a transport.</b> A
 * {@link ConnectivityManager.NetworkCallback} firing {@code onAvailable} proves only that a
 * link came up — which is exactly what a captive portal or a dead-end AP provides — so a
 * callback is treated as a <em>trigger to re-probe</em>, never as proof on its own. Every
 * dismissal path funnels through {@link #attemptDismiss(boolean)}, which runs
 * {@code NetworkStateHelper.probeServerBlocking()} off the main thread and finishes only on
 * success. While a validated transport is present the screen also re-probes on a timer, so
 * a server that comes back up releases the gate without the user touching anything.
 *
 * <p>Deliberately does NOT extend BaseActivity and is never launched over the lock screen
 * or device PIN gate — the duress secondary PIN must remain enterable offline so its local
 * wipe can fire.
 */
public class NoInternetActivity extends AppCompatActivity {

    /** Auto-retry cadence while this screen is up and a transport looks usable. */
    private static final long AUTO_RETRY_INTERVAL_MS = 5000L;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private TextView tvStatus;
    private MaterialButton btnRetry;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoRetry = new Runnable() {
        @Override public void run() {
            attemptDismiss(false);
            handler.postDelayed(this, AUTO_RETRY_INTERVAL_MS);
        }
    };

    /** True while a probe thread is running, so taps and timers do not stack probes. */
    private volatile boolean probing = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackPressGuard.onBack(this, () -> moveTaskToBack(true)); // back must not escape into the app while offline
        setContentView(R.layout.activity_no_internet);

        tvStatus = findViewById(R.id.tvOfflineStatus);
        btnRetry = findViewById(R.id.btnRetry);
        if (btnRetry != null) btnRetry.setOnClickListener(v -> attemptDismiss(true));

        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        NetworkStateHelper.noInternetActive = true;
        // Any cached "server was reachable" verdict is void the moment this gate is up:
        // the gate exists because reachability is in doubt, and trusting the cache here
        // would let the screen dismiss itself without proving anything.
        NetworkStateHelper.invalidateServerContact();
        registerCallback();
        // Covers the race where connectivity was restored between the check that
        // launched us and this screen actually coming up.
        attemptDismiss(false);
        handler.postDelayed(autoRetry, AUTO_RETRY_INTERVAL_MS);
    }

    private void registerCallback() {
        if (connectivityManager == null || networkCallback != null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> attemptDismiss(false));
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                // Only a VALIDATED network is worth spending a probe on.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    runOnUiThread(() -> attemptDismiss(false));
                }
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
    }

    /**
     * The single dismissal path. Verifies a validated transport first (cheap, synchronous)
     * and then proves the push server actually answers (network I/O, background thread).
     * Finishes only when both hold.
     *
     * @param fromRetry true when the user tapped "Try again", which is the only case that
     *                  should surface failure feedback — the timer and the connectivity
     *                  callback must fail silently.
     */
    private void attemptDismiss(boolean fromRetry) {
        if (probing) {
            if (fromRetry) showChecking();
            return;
        }
        if (!NetworkStateHelper.hasValidatedTransport(this)) {
            if (fromRetry) showStillOffline();
            return;
        }

        probing = true;
        showChecking();
        if (btnRetry != null) btnRetry.setEnabled(false);

        new Thread(() -> {
            final boolean reached = NetworkStateHelper.probeServerBlocking();
            runOnUiThread(() -> {
                probing = false;
                if (btnRetry != null) btnRetry.setEnabled(true);
                if (isFinishing() || isDestroyed()) return;
                if (reached) {
                    finish();
                    overridePendingTransition(0, 0);
                } else if (fromRetry) {
                    showStillOffline();
                }
            });
        }, "offline-gate-probe").start();
    }

    private void showChecking() {
        if (tvStatus == null) return;
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.offline_status_checking);
    }

    private void showStillOffline() {
        Toast.makeText(this, R.string.offline_still_offline, Toast.LENGTH_SHORT).show();
        if (tvStatus != null) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(R.string.offline_still_offline);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(autoRetry);
        NetworkStateHelper.noInternetActive = false;
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
    }
}
