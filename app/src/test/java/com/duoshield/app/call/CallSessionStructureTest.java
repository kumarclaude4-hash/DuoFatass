package com.duoshield.app.call;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Structural guards for the call-session design (plain JVM, reads sources — the real classes
 * need libwebrtc/Firebase and cannot load here).
 *
 * <p>The design rule: the call belongs to {@link CallSession}, not to any screen. These tests pin
 * the places where that rule is easiest to break by accident.
 */
public class CallSessionStructureTest {

    private static final String ROOT = "com/duoshield/app/";

    // ── the screen must not own the call ─────────────────────────────────────

    @Test public void onDestroy_decidesThroughPolicy_andNeverHangsUpItself() throws IOException {
        String body = methodBody(src("call/CallActivity.java"), "protected void onDestroy()");
        assertTrue(body.contains("CallSessionPolicy.shouldDetachOnDestroy("));
        assertTrue("detach path", body.contains("session.detach(this)"));
        assertTrue("teardown path", body.contains("session.teardown()"));
        assertFalse("onDestroy must not hang up directly — a destroyed screen is not a hang-up",
                body.contains(".hangup("));
        assertFalse(body.contains(".release()"));
        // Renderers are released before the session can dispose the tracks.
        assertTrue("sinks detached before renderers released",
                body.indexOf("detachVideoSinks()") >= 0
                && body.indexOf("detachVideoSinks()") < body.indexOf("releaseVideoRenderers()"));
        assertTrue("renderers released before teardown",
                body.indexOf("releaseVideoRenderers()") < body.indexOf("session.teardown()"));
    }

    @Test public void screenOwnsNoCallState() throws IOException {
        String a = src("call/CallActivity.java");
        assertFalse("history rows are written by CallSession", a.contains("saveCallRecord("));
        assertFalse("audio focus is owned by CallSession", a.contains("AudioFocusRequest"));
        assertFalse("receivers are owned by CallSession", a.contains("registerReceiver("));
        assertFalse(a.contains("historyExecutor"));
        assertTrue("screen is a UI attached to the session", a.contains("implements CallSession.UiHooks"));
    }

    // ── session invariants ───────────────────────────────────────────────────

    @Test public void session_teardownIsIdempotent_andRecordIsWrittenOnce() throws IOException {
        String s = src("call/CallSession.java");
        String teardown = methodBody(s, "public void teardown()");
        assertTrue("guarded by tornDown", teardown.contains("if (tornDown) return;"));
        String save = methodBody(s, "private void saveCallRecord(String outcome)");
        assertTrue("guarded by savedRecordId", save.contains("if (savedRecordId != null) return;"));
        assertTrue("queued history insert must finish: shutdown(), not shutdownNow()",
                teardown.contains("historyExecutor.shutdown();") && !teardown.contains("historyExecutor.shutdownNow("));
    }

    @Test public void session_headlessTeardownRunsAfterTheManagerCallReturns() throws IOException {
        String s = src("call/CallSession.java");
        String m = methodBody(s, "private void tearDownIfHeadless()");
        assertTrue("posted, because ENDED is raised from inside CallManager.hangup()", m.contains("main.post("));
        assertTrue("only when no screen is attached", m.contains("ui == null"));
    }

    @Test public void session_notificationAndPipActionsWorkWithoutAScreen() throws IOException {
        String s = src("call/CallSession.java");
        assertTrue(s.contains("BROADCAST_END_CALL"));
        assertTrue(s.contains("BROADCAST_TOGGLE_MUTE"));
        assertTrue("registered on the application context, not a screen", s.contains("appCtx.registerReceiver("));
    }

    // ── app lock ─────────────────────────────────────────────────────────────

    @Test public void callScreen_checksTheLockOnReturnOnly_andNeverOverwritesAPendingTimestamp() throws IOException {
        String a = src("call/CallActivity.java");
        String onStart = methodBody(a, "protected void onStart()");
        assertTrue(onStart.contains("boolean returning = hasBeenStopped || adopted;"));
        assertTrue(onStart.contains("CallSessionPolicy.shouldLockOnReturn("));
        assertTrue("must not count itself when a background timestamp is pending",
                onStart.contains("AppLockManager.hasBackgroundTimestamp(this)"));
        String onStop = methodBody(a, "protected void onStop()");
        assertTrue(onStop.contains("if (countsForLock)"));
    }

    @Test public void lockScreen_redirectsToTheCallOnlyAfterAuthentication() throws IOException {
        String body = methodBody(src("LockScreenActivity.java"), "private void unlock()");
        int fg = body.indexOf("AppLockManager.onAppForegrounded(this)");
        int redirect = body.indexOf("CallSession.returnToCall(this)");
        assertTrue(fg >= 0 && redirect > fg);
        assertTrue("guarded by a live call", body.contains("CallSession.isLive()"));
        assertTrue("opt-in extra only", body.contains("EXTRA_RETURN_TO_CALL"));
    }

    @Test public void chatScreen_isCountedByTheLockManager() throws IOException {
        String c = src("call/InCallChatActivity.java");
        assertTrue(c.contains("AppLockManager.hasBackgroundTimestamp(this)"));
        assertTrue(c.contains("AppLockManager.onActivityStarted()"));
        assertTrue(c.contains("AppLockManager.onActivityStopped(this)"));
    }

    // ── return-to-call bar & PiP wiring ──────────────────────────────────────

    @Test public void baseActivity_showsAndDismissesTheBarWithTheScreen() throws IOException {
        String b = src("BaseActivity.java");
        assertTrue(methodBody(b, "protected void onResume()").contains("ReturnToCallBar.show(this)"));
        assertTrue(methodBody(b, "protected void onPause()").contains("returnToCallBar.dismiss()"));
        assertTrue(b.contains("protected boolean showsReturnToCallBar()"));
    }

    @Test public void immersiveViewers_optOutOfTheBar() throws IOException {
        for (String f : new String[]{"FullScreenImageActivity.java", "MediaViewerActivity.java"}) {
            String s = src(f);
            assertTrue(f + " must opt out", s.contains("protected boolean showsReturnToCallBar()")
                    && methodBody(s, "protected boolean showsReturnToCallBar()").contains("return false;"));
        }
    }

    @Test public void manifest_declaresPipOnBothCallScreens() throws IOException {
        File mf = new File("src/main/AndroidManifest.xml");
        if (!mf.isFile()) mf = new File("app/src/main/AndroidManifest.xml");
        assertTrue("AndroidManifest.xml not found", mf.isFile());
        String m = new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8);
        for (String name : new String[]{".call.CallActivity", ".call.InCallChatActivity"}) {
            int i = m.indexOf("android:name=\"" + name + "\"");
            assertTrue(name + " not in manifest", i >= 0);
            String tag = m.substring(i, m.indexOf(">", i));
            assertTrue(name + " supportsPictureInPicture", tag.contains("android:supportsPictureInPicture=\"true\""));
            assertTrue(name + " configChanges (resize must not recreate the video surfaces)",
                    tag.contains("android:configChanges=") && tag.contains("screenSize"));
        }
    }

    @Test public void chatScreen_usesTheSharedPipParams_andShowsOnlyRemoteVideoInPip() throws IOException {
        String c = src("call/InCallChatActivity.java");
        assertTrue(c.contains("CallPip.params("));
        String apply = methodBody(c, "private void applyPipUi(boolean inPip)");
        assertTrue(apply.contains("chatColumn.setVisibility(View.GONE)"));
        assertTrue(apply.contains("pipLocalContainer.setVisibility(View.GONE)"));
        assertTrue("restored on expand", apply.contains("chatColumn.setVisibility(View.VISIBLE)"));
        // The video sinks must survive onPause while floating.
        assertTrue(methodBody(c, "protected void onPause()").contains("if (!isInPictureInPictureMode()) detachPipSink();"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static File file(String rel) {
        File f = new File("src/main/java/" + ROOT + rel);
        if (!f.isFile()) f = new File("app/src/main/java/" + ROOT + rel);
        return f;
    }

    private static String src(String rel) throws IOException {
        File f = file(rel);
        assertTrue(rel + " not found from " + new File(".").getAbsolutePath(), f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Text between the method's opening brace and its matching close brace. */
    private static String methodBody(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue("method not found: " + signature, i >= 0);
        int open = src.indexOf('{', i);
        int depth = 0;
        for (int k = open; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open + 1, k);
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
