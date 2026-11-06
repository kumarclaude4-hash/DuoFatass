package com.duoshield.app.util;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Opaque, input-blocking cover installed before a timed-out BaseActivity launches
 * LockScreenActivity. The activity content has already been inflated by the time
 * onStart runs, but the first frame has not yet been drawn; placing this cover on
 * android.R.id.content in onStart prevents that frame (and any recents capture
 * taken during the lock-screen transition) from exposing the underlying screen.
 *
 * The cover is removed only when BaseActivity next confirms that the app is
 * unlocked. It is deliberately independent of FLAG_SECURE because users may
 * explicitly opt into screenshots while still expecting the app lock to hide
 * authenticated content.
 */
public final class LockScreenCover {

    private static final String TAG = "duoshield-lock-screen-cover";

    private LockScreenCover() {}

    /** Installs an opaque, touch-consuming cover. Safe to call more than once. */
    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        ViewGroup container = (ViewGroup) content;
        if (container.findViewWithTag(TAG) != null) return;

        View cover = new View(activity);
        cover.setTag(TAG);
        cover.setBackgroundColor(Color.BLACK);
        cover.setClickable(true);
        cover.setFocusable(true);
        cover.setFocusableInTouchMode(true);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cover.setContentDescription("App locked");
        container.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        cover.bringToFront();
        container.invalidate();
    }

    /** Removes only this helper's cover; never removes application-owned views. */
    public static void hide(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        ViewGroup container = (ViewGroup) content;
        View cover = container.findViewWithTag(TAG);
        if (cover != null && cover.getParent() == container) {
            container.removeView(cover);
            container.invalidate();
        }
    }
}
