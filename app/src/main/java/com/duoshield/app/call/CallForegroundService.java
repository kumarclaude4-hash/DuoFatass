package com.duoshield.app.call;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.duoshield.app.R;
import com.duoshield.app.notifications.NotificationHelper;
import com.duoshield.app.notifications.NotificationStyler;

/**
 * Foreground service that keeps the call process alive when the user backgrounds the app.
 *
 * <p>WhatsApp, Signal, and every production VoIP app run calls as a foreground service.
 * Without it, the OS can kill the process while the user is in another app, which
 * closes the WebRTC PeerConnection and drops the call.  With it, the process is
 * protected and an ongoing notification (with End / Mute quick actions) keeps the
 * user aware of the active call.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>Started from {@link CallActivity#onCreate} via {@code ACTION_START}.</li>
 *   <li>Stopped from {@link CallActivity#onDestroy} via {@code ACTION_STOP}.</li>
 * </ul>
 *
 * <h3>Notification actions</h3>
 * The "End" and "Mute" notification buttons send local broadcasts that
 * {@link CallActivity} listens for via a registered {@link android.content.BroadcastReceiver}.
 * If {@link CallActivity} has already been destroyed (call ended naturally), the
 * broadcasts are simply ignored.
 */
public class CallForegroundService extends Service {

    private static final String TAG = "CallForegroundService";

    // ── Intent actions ────────────────────────────────────────────────────────
    public static final String ACTION_START = "com.duoshield.call.START";
    public static final String ACTION_STOP  = "com.duoshield.call.STOP";
    public static final String ACTION_END   = "com.duoshield.call.END";
    public static final String ACTION_MUTE  = "com.duoshield.call.MUTE";

    // ── Extras (for ACTION_START) ─────────────────────────────────────────────
    public static final String EXTRA_PARTNER_NAME = "partner_name";
    public static final String EXTRA_CALL_ID      = "call_id";
    public static final String EXTRA_IS_VIDEO     = "is_video";

    // ── Local broadcasts delivered to CallActivity ─────────────────────────────
    /** Broadcast sent to {@link CallActivity} when the notification "End" button is tapped. */
    public static final String BROADCAST_END_CALL    = "com.duoshield.action.END_CALL";
    /** Broadcast sent to {@link CallActivity} when the notification "Mute" button is tapped. */
    public static final String BROADCAST_TOGGLE_MUTE = "com.duoshield.action.TOGGLE_MUTE";

    private static final int NOTIFICATION_ID = 9001;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: " + action);

        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_END.equals(action)) {
            // Relay to CallActivity so it can tear down the PeerConnection cleanly.
            // setPackage() scopes this broadcast to our own app — required since the
            // receiving BroadcastReceiver is registered non-exported.
            sendBroadcast(new Intent(BROADCAST_END_CALL).setPackage(getPackageName()));
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_MUTE.equals(action)) {
            sendBroadcast(new Intent(BROADCAST_TOGGLE_MUTE).setPackage(getPackageName()));
            return START_NOT_STICKY;
        }

        // ── ACTION_START ──────────────────────────────────────────────────────
        String  partnerName = intent.getStringExtra(EXTRA_PARTNER_NAME);
        String  callId      = intent.getStringExtra(EXTRA_CALL_ID);
        boolean isVideo     = intent.getBooleanExtra(EXTRA_IS_VIDEO, false);
        if (partnerName == null || partnerName.isEmpty()) partnerName = "DuoShield";

        NotificationHelper.createChannel(this);
        Notification notification = buildNotification(partnerName, callId, isVideo);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: declare foreground service type (microphone ± camera).
            int serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (isVideo) {
                serviceType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            startForeground(NOTIFICATION_ID, notification, serviceType);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        Log.d(TAG, "Foreground call service started — partner=" + partnerName + " video=" + isVideo);
        return START_NOT_STICKY;
    }

    // ── Notification builder ──────────────────────────────────────────────────

    private Notification buildNotification(String partnerName, String callId, boolean isVideo) {
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        // Tap notification → bring CallActivity to foreground
        Intent openIntent = new Intent(this, CallActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        // "End" action → stops call
        Intent endIntent = new Intent(this, CallForegroundService.class);
        endIntent.setAction(ACTION_END);
        PendingIntent endPi = PendingIntent.getService(this, 1, endIntent, piFlags);

        // "Mute" action → toggles mic
        Intent muteIntent = new Intent(this, CallForegroundService.class);
        muteIntent.setAction(ACTION_MUTE);
        PendingIntent mutePi = PendingIntent.getService(this, 2, muteIntent, piFlags);

        String contentText = isVideo ? "Video call in progress" : "Voice call in progress";

        return new NotificationCompat.Builder(this, NotificationStyler.CH_CALLS)
                .setContentTitle(partnerName)
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_call_phone)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_mic_off, "Mute", mutePi)
                .addAction(R.drawable.ic_call_end, "End", endPi)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
