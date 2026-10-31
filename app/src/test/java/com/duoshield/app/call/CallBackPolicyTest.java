package com.duoshield.app.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the "back must not end the call" rule.
 *
 * <p>The original bug: {@code CallActivity} had no back handling, so back called
 * {@code finish()}, and {@code onDestroy()} hangs up a live call. {@link CallBackPolicy} holds
 * the decision as pure logic; the structural tests below pin that the activity actually uses it
 * and that the back path never reaches {@code hangup()}.
 */
public class CallBackPolicyTest {

    // ── pure decision table ──────────────────────────────────────────────────

    @Test public void endedCall_justCloses() {
        assertEquals(CallBackPolicy.Action.CLOSE, CallBackPolicy.onBack(false, true));
        assertEquals(CallBackPolicy.Action.CLOSE, CallBackPolicy.onBack(false, false));
    }

    @Test public void liveVideoCall_entersPip() {
        assertEquals(CallBackPolicy.Action.ENTER_PIP, CallBackPolicy.onBack(true, true));
    }

    @Test public void liveVoiceCall_goesToBackground() {
        assertEquals(CallBackPolicy.Action.BACKGROUND, CallBackPolicy.onBack(true, false));
    }

    @Test public void backNeverMeansHangUp() {
        // There is no "hang up" member to return at all; a live call is always kept alive.
        for (CallBackPolicy.Action a : CallBackPolicy.Action.values()) {
            assertFalse("unexpected action " + a, a.name().toLowerCase().contains("hang")
                    || a.name().toLowerCase().contains("end_call"));
        }
    }

    @Test public void autoEnterPip_onlyForLiveVideoCallsNotLaunchingASubScreen() {
        assertTrue(CallBackPolicy.autoEnterPip(true, false, true));
        assertFalse("ended call", CallBackPolicy.autoEnterPip(false, false, true));
        assertFalse("chat / permission dialog launching", CallBackPolicy.autoEnterPip(true, true, true));
        assertFalse("voice call or no PiP support", CallBackPolicy.autoEnterPip(true, false, false));
    }

    // ── structural: the activity really uses it, and back cannot reach hangup() ──

    @Test public void minimizeCall_usesPolicyAndNeverHangsUp() throws IOException {
        String body = methodBody(activitySource(), "private void minimizeCall()");
        assertTrue("minimizeCall() must decide through CallBackPolicy", body.contains("CallBackPolicy.onBack("));
        assertFalse("back path must never hang up", body.contains("hangup("));
        assertFalse("back path must never end the call", body.contains("endCall("));
    }

    @Test public void backHandlersRouteToMinimizeCall() throws IOException {
        String src = activitySource();
        assertTrue("system back must be an OnBackPressedCallback (predictive back bypasses onBackPressed)",
                src.contains("new OnBackPressedCallback(true)"));
        assertTrue(src.contains("btnBack.setOnClickListener(v -> minimizeCall())"));
        assertFalse("CallActivity must not override onBackPressed()",
                Pattern.compile("\\bvoid\\s+onBackPressed\\s*\\(").matcher(src).find());
    }

    @Test public void onlyEndCallPathsHangUp() throws IOException {
        String src = activitySource();
        String endCall = methodBody(src, "private void endCall()");
        assertTrue(endCall.contains("hangup("));
        // btnEndCall and the notification/PiP End receiver both go through endCall().
        assertTrue(src.contains("btnEndCall.setOnClickListener(v -> endCall())"));
        assertTrue(src.contains("BROADCAST_END_CALL.equals(intent.getAction())) {\n                endCall();"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String activitySource() throws IOException {
        File f = new File("src/main/java/com/duoshield/app/call/CallActivity.java");
        if (!f.isFile()) f = new File("app/src/main/java/com/duoshield/app/call/CallActivity.java");
        assertTrue("CallActivity.java not found from " + new File(".").getAbsolutePath(), f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Returns the text between the method's opening brace and its matching closing brace. */
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
