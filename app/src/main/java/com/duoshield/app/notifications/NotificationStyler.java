package com.duoshield.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import com.duoshield.app.ChatMediaActivity;
import com.duoshield.app.R;

public class NotificationStyler {

    public static final String CH_MESSAGES = "duoshield_messages";
    public static final String CH_SILENT   = "duoshield_silent";
    public static final String CH_CALLS    = "duoshield_calls";

    private static final int NOTIF_ID_CALL_BASE = 9000;

    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel messages = new NotificationChannel(
            CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription("Incoming DuoShield messages");
        messages.enableVibration(true);
        nm.createNotificationChannel(messages);

        NotificationChannel silent = new NotificationChannel(
            CH_SILENT, "Silent", NotificationManager.IMPORTANCE_LOW);
        silent.setDescription("Background events");
        nm.createNotificationChannel(silent);

        NotificationChannel calls = new NotificationChannel(
            CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Incoming voice and video calls");
        calls.enableVibration(true);
        calls.setSound(
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
            new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        nm.createNotificationChannel(calls);
    }

    /**
     * Shows a full-screen-intent notification for an incoming call.
     * Used when the app is backgrounded or killed.
     */
    public static void showIncomingCall(Context ctx, String callerName,
                                        String callId, String callerId, boolean isVideo) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return;
        try {
            Intent fullScreenIntent = new Intent(ctx,
                    com.duoshield.app.call.IncomingCallActivity.class);
            fullScreenIntent.putExtra(
                    com.duoshield.app.call.IncomingCallActivity.EXTRA_CALL_ID, callId);
            fullScreenIntent.putExtra(
                    com.duoshield.app.call.IncomingCallActivity.EXTRA_CALLER_ID, callerId);
            fullScreenIntent.putExtra(
                    com.duoshield.app.call.IncomingCallActivity.EXTRA_CALLER_NAME, callerName);
            fullScreenIntent.putExtra(
                    com.duoshield.app.call.IncomingCallActivity.EXTRA_IS_VIDEO, isVideo);
            fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            PendingIntent fullScreenPi = PendingIntent.getActivity(ctx, callId.hashCode(),
                    fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent declineIntent = new Intent(ctx,
                    com.duoshield.app.call.IncomingCallActivity.class);
            declineIntent.putExtra(
                    com.duoshield.app.call.IncomingCallActivity.EXTRA_CALL_ID, callId);
            declineIntent.putExtra("auto_decline", true);
            PendingIntent declinePi = PendingIntent.getActivity(ctx, callId.hashCode() + 1,
                    declineIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String title = callerName + (isVideo ? " • Video call" : " • Voice call");

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CH_CALLS)
                    .setSmallIcon(R.drawable.ic_call_phone)
                    .setContentTitle("Incoming call")
                    .setContentText(title)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setFullScreenIntent(fullScreenPi, true)
                    .setContentIntent(fullScreenPi)
                    .addAction(R.drawable.ic_call_end, "Decline", declinePi)
                    .addAction(R.drawable.ic_call_phone, "Answer", fullScreenPi);

            int notifId = NOTIF_ID_CALL_BASE + callId.hashCode() % 1000;
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build());
        } catch (Exception e) {
            android.util.Log.w("NotificationStyler", "showIncomingCall failed: " + com.duoshield.app.util.LogRedact.error(e));
        }
    }

    /** Shows a missed-call notification. */
    public static void showMissedCall(Context ctx, String callerName, String callId) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return;
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CH_MESSAGES)
                    .setSmallIcon(R.drawable.ic_call_phone)
                    .setContentTitle("Missed call")
                    .setContentText("Missed call from " + callerName)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);
            int notifId = NOTIF_ID_CALL_BASE + 500 + (callId != null ? callId.hashCode() % 500 : 0);
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build());
        } catch (Exception ignored) {}
    }

    /**
     * Shows a privacy-preserving notification for an incoming message.
     *
     * <p>The notification body is always a fixed string — it never contains
     * decrypted message content (BUG-N01).  This prevents plaintext from
     * leaking on the Android lock screen, Quick Settings, and notification
     * history (Android 11+).
     *
     * <p>The notification ID is derived from {@code convId} so each
     * conversation gets its own notification slot rather than every new
     * message overwriting notification ID 1001 (BUG-N02).
     *
     * <p>A {@code areNotificationsEnabled()} guard replaces the removed
     * {@code @SuppressLint("MissingPermission")} so silent drops on
     * Android 13+ are handled explicitly rather than suppressed (BUG-N03).
     */
    public static void showMessage(Context ctx, String title, String body,
                                   String convId, String partnerUid, String myUid, int badgeCount) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return;

        try {
            Intent openIntent = new Intent(ctx, ChatMediaActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // Embed conversation context so ChatMediaActivity opens the correct chat,
            // even when the user has multiple conversations.
            if (convId != null && !convId.isEmpty()) {
                openIntent.putExtra("conversation_id", convId);
            }
            if (partnerUid != null && !partnerUid.isEmpty()) {
                openIntent.putExtra("partner_uid", partnerUid);
            }
            // F13 fix: derive per-conversation request codes so Android never reuses a
            // PendingIntent from a different conversation when FLAG_UPDATE_CURRENT is set.
            int convBase = (convId != null && !convId.isEmpty())
                    ? (convId.hashCode() & 0x0000FFFF) : 0;

            PendingIntent openPi = PendingIntent.getActivity(ctx, convBase, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent markReadIntent = new Intent(ctx, MarkReadReceiver.class);
            markReadIntent.putExtra(MarkReadReceiver.EXTRA_CONV_ID, convId);
            markReadIntent.putExtra(MarkReadReceiver.EXTRA_MY_UID,  myUid);
            PendingIntent markReadPi = PendingIntent.getBroadcast(ctx, convBase | 0x10000, markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // deleteIntent: clears the badge when the user swipes away the notification.
            // Intentionally does NOT mark messages as read — swiping a notification
            // is not the same as opening the chat and reading the messages.
            Intent dismissIntent = new Intent(ctx, NotificationDismissReceiver.class);
            dismissIntent.putExtra(NotificationDismissReceiver.EXTRA_CONV_ID, convId);
            PendingIntent deletePi = PendingIntent.getBroadcast(ctx, convBase | 0x30000, dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            RemoteInput remoteInput = new RemoteInput.Builder(MessageReplyReceiver.KEY_REPLY_TEXT)
                .setLabel("Reply").build();
            // F13 fix: include partnerUid so MessageReplyReceiver can encrypt without SharedPrefs
            Intent replyIntent = new Intent(ctx, MessageReplyReceiver.class);
            replyIntent.putExtra(MessageReplyReceiver.EXTRA_CONV_ID,     convId);
            replyIntent.putExtra(MessageReplyReceiver.EXTRA_MY_UID,      myUid);
            replyIntent.putExtra(MessageReplyReceiver.EXTRA_PARTNER_UID, partnerUid);
            PendingIntent replyPi = PendingIntent.getBroadcast(ctx, convBase | 0x20000, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_send, "Reply", replyPi)
                .addRemoteInput(remoteInput).build();

            // Fixed body — never contains decrypted content.
            // Per-conversation notification ID prevents messages from overwriting each other.
            int notifId = (convId != null && !convId.isEmpty()) ? convId.hashCode() : 1001;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_secure)
                .setContentTitle("DuoShield")
                .setContentText("New Message")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .setDeleteIntent(deletePi)
                .addAction(R.drawable.ic_tick_double_blue, "Mark Read", markReadPi)
                .addAction(replyAction)
                .setNumber(badgeCount);

            NotificationManagerCompat.from(ctx).notify(notifId, builder.build());
        } catch (Exception ignored) {}
    }
}
