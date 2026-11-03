package com.duoshield.app.call;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Rational;

import com.duoshield.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Picture-in-Picture parameters shared by {@link CallActivity} and {@link InCallChatActivity},
 * so the floating window looks and behaves the same whichever call screen the user leaves from.
 *
 * <p>The Mute / End buttons on the window reuse the notification's broadcasts
 * ({@link CallForegroundService#BROADCAST_TOGGLE_MUTE} / {@link CallForegroundService#BROADCAST_END_CALL});
 * {@link CallSession} owns the receiver, so they work whether or not any call screen exists.
 */
final class CallPip {

    /** Portrait window; within the system's allowed range (1:2.39 – 2.39:1). */
    static final int ASPECT_W = 9;
    static final int ASPECT_H = 16;

    private CallPip() { }

    static boolean isSupported(Context c) {
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    static List<RemoteAction> actions(Context c, boolean muted) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent mutePi = PendingIntent.getBroadcast(c, 20,
                new Intent(CallForegroundService.BROADCAST_TOGGLE_MUTE).setPackage(c.getPackageName()),
                flags);
        PendingIntent endPi = PendingIntent.getBroadcast(c, 21,
                new Intent(CallForegroundService.BROADCAST_END_CALL).setPackage(c.getPackageName()),
                flags);
        List<RemoteAction> actions = new ArrayList<>();
        actions.add(new RemoteAction(
                Icon.createWithResource(c, muted ? R.drawable.ic_mic_off : R.drawable.ic_mic),
                muted ? "Unmute" : "Mute", "Toggle microphone", mutePi));
        actions.add(new RemoteAction(
                Icon.createWithResource(c, R.drawable.ic_call_end),
                "End", "End call", endPi));
        return actions;
    }

    /**
     * @param autoEnter Android 12+: enter PiP automatically when the user goes Home
     * @param hint      where the window should animate from, or {@code null}
     */
    static PictureInPictureParams params(Context c, boolean muted, boolean autoEnter, Rect hint) {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(ASPECT_W, ASPECT_H))
                .setActions(actions(c, muted));
        if (hint != null) b.setSourceRectHint(hint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(autoEnter);
        return b.build();
    }

    /**
     * Crops {@code [l,t,r,b]} (centred) to the PiP aspect ratio so the system does not have to
     * stretch the source rect. Pure integer math — unit-tested.
     *
     * @return {@code {left, top, right, bottom}}, or {@code null} for an empty rect
     */
    static int[] cropToPipAspect(int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        if (w <= 0 || h <= 0) return null;
        // Compare w/h with ASPECT_W/ASPECT_H without floating point: w*ASPECT_H vs h*ASPECT_W.
        long lhs = (long) w * ASPECT_H, rhs = (long) h * ASPECT_W;
        if (lhs > rhs) {                       // too wide → trim the sides
            int nw = (int) Math.round((double) h * ASPECT_W / ASPECT_H);
            int cut = (w - nw) / 2;
            return new int[]{l + cut, t, l + cut + nw, b};
        } else if (lhs < rhs) {                // too tall → trim top/bottom
            int nh = (int) Math.round((double) w * ASPECT_H / ASPECT_W);
            int cut = (h - nh) / 2;
            return new int[]{l, t + cut, r, t + cut + nh};
        }
        return new int[]{l, t, r, b};
    }
}
