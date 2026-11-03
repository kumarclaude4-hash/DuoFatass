package com.duoshield.app.call;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * "Tap to return to call · 2:05" strip across the top of every in-app screen while a call is
 * live — the way back in when the call is minimised and its screen is not in front.
 *
 * <p>Added to the activity's content frame at runtime so no layout needs to change. The content
 * is pushed down by the bar's height (instead of being covered, which would hide each screen's
 * toolbar and back button); the bar itself is positioned back at the top with a negative margin,
 * and the frame's {@code clipToPadding} is switched off for the duration so it can draw there.
 * Everything is restored in {@link #dismiss()}. If a screen draws under the status bar, the
 * bar adds the overlap to its own top padding so its text stays readable.
 *
 * <p>Owned by {@code BaseActivity}: shown in {@code onResume()}, dismissed in {@code onPause()}.
 * Each activity instance gets its own bar; nothing is shared between instances.
 */
public final class ReturnToCallBar {

    private static final String VIEW_TAG = "return_to_call_bar";
    private static final int    BAR_DP   = 36;
    private static final int    GREEN    = 0xFF1E9E5A;

    /**
     * @return the bar, or {@code null} when there is no live call or nowhere to put it. The
     *         caller must {@link #dismiss()} it when the screen pauses.
     */
    public static ReturnToCallBar show(Activity activity) {
        if (!CallSession.isLive()) return null;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        ReturnToCallBar bar = new ReturnToCallBar(activity, content);
        // Wait for layout: the status-bar overlap can't be measured before the first pass.
        content.post(bar::install);
        return bar;
    }

    private final Activity  activity;
    private final ViewGroup content;
    private final Handler   handler = new Handler(Looper.getMainLooper());

    private TextView bar;
    private boolean  dismissed;

    private int     origPaddingTop;
    private boolean origClipToPadding;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (dismissed) return;
            if (!CallSession.isLive()) { dismiss(); return; }   // call ended while we were showing
            refreshText();
            handler.postDelayed(this, 1_000L);
        }
    };

    private ReturnToCallBar(Activity activity, ViewGroup content) {
        this.activity = activity;
        this.content  = content;
    }

    private void install() {
        if (dismissed || bar != null || activity.isFinishing() || activity.isDestroyed()) return;
        if (content.findViewWithTag(VIEW_TAG) != null) return;     // never stack two

        // How far the content frame reaches under the status bar (0 when the window fits system
        // windows, which is the default for this app).
        int overlap = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(content);
        if (insets != null) {
            int statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int[] loc = new int[2];
            content.getLocationOnScreen(loc);
            overlap = Math.max(0, statusBar - loc[1]);
        }
        int barHeight = dp(BAR_DP) + overlap;

        bar = new TextView(activity);
        bar.setTag(VIEW_TAG);
        bar.setBackgroundColor(GREEN);
        bar.setTextColor(Color.WHITE);
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bar.setTypeface(bar.getTypeface(), android.graphics.Typeface.BOLD);
        bar.setGravity(Gravity.CENTER);
        bar.setSingleLine(true);
        bar.setPadding(dp(12), overlap, dp(12), 0);
        bar.setContentDescription("Ongoing call. Tap to return to the call.");
        bar.setClickable(true);
        bar.setFocusable(true);
        bar.setOnClickListener(v -> CallSession.returnToCall(activity));
        ViewCompat.setElevation(bar, dp(8));

        origPaddingTop    = content.getPaddingTop();
        origClipToPadding = isClipToPadding();
        setClipToPadding(false);
        content.setPadding(content.getPaddingLeft(), origPaddingTop + barHeight,
                content.getPaddingRight(), content.getPaddingBottom());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.TOP);
        // The frame now has (origPaddingTop + barHeight) of top padding; pull the bar back to y=0.
        lp.topMargin = -(origPaddingTop + barHeight);
        content.addView(bar, lp);

        refreshText();
        handler.postDelayed(tick, 1_000L);
    }

    private void refreshText() {
        if (bar == null) return;
        CallSession s = CallSession.current();
        long start = s != null ? s.callStartMs() : 0;
        String text = "Tap to return to call";
        if (start > 0) {
            text += "  ·  " + CallSessionPolicy.formatDuration(System.currentTimeMillis() - start);
        }
        bar.setText(text);
    }

    /** Removes the bar and gives the content frame its original padding back. Idempotent. */
    public void dismiss() {
        if (dismissed) return;
        dismissed = true;
        handler.removeCallbacksAndMessages(null);
        if (bar == null) return;                       // install() never ran
        content.removeView(bar);
        content.setPadding(content.getPaddingLeft(), origPaddingTop,
                content.getPaddingRight(), content.getPaddingBottom());
        setClipToPadding(origClipToPadding);
        bar = null;
    }

    private boolean isClipToPadding() {
        return content instanceof ViewGroup && content.getClipToPadding();
    }

    private void setClipToPadding(boolean clip) {
        content.setClipToPadding(clip);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
