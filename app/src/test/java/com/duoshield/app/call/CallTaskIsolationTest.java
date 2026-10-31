package com.duoshield.app.call;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code CallActivity} runs in its own task ({@code android:taskAffinity}) so that back can send
 * the call to the background and reveal the screen the user came from, while the call keeps
 * running in its own Recents card.
 *
 * <p>An activity with a different affinity only gets its own task when started with
 * {@code FLAG_ACTIVITY_NEW_TASK}; without it the affinity is silently ignored and the call lands in
 * the caller's task, where "send to back" would hide the whole app. So the manifest attribute and
 * every launch site must stay in step — this pins both.
 */
public class CallTaskIsolationTest {

    @Test public void manifestGivesCallActivityItsOwnTaskAffinity() throws IOException {
        String manifest = read(find("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"));
        Matcher m = Pattern.compile("<activity\\b[^>]*android:name=\"\\.call\\.CallActivity\"[^>]*>",
                Pattern.DOTALL).matcher(manifest);
        assertTrue("CallActivity <activity> not found in manifest", m.find());
        assertTrue("CallActivity must declare android:taskAffinity",
                m.group().contains("android:taskAffinity="));
    }

    @Test public void everyCallActivityLaunchSetsNewTask() throws IOException {
        Pattern launch = Pattern.compile("new\\s+Intent\\([^;]*(?<![A-Za-z0-9_])CallActivity\\.class\\s*\\)");
        List<String> bad = new ArrayList<>();
        int sites = 0;
        for (Path p : javaSources()) {
            String name = p.getFileName().toString();
            if (name.equals("CallActivity.java")) continue;       // its own class literal, not a launch
            String src = read(p.toFile());
            Matcher m = launch.matcher(src);
            while (m.find()) {
                sites++;
                int end = Math.min(src.length(), m.end() + 1500);
                // Window: from the Intent construction to the end of the enclosing statement block.
                String window = src.substring(m.start(), end);
                int cut = indexOfAny(window, "startActivity(", "PendingIntent.getActivity(");
                String upToLaunch = cut > 0 ? window.substring(0, cut) : window;
                if (!upToLaunch.contains("FLAG_ACTIVITY_NEW_TASK")) bad.add(name);
            }
        }
        assertTrue("expected to find the CallActivity launch sites", sites >= 4);
        assertTrue("CallActivity launched without FLAG_ACTIVITY_NEW_TASK in: " + bad, bad.isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static int indexOfAny(String s, String... needles) {
        int best = -1;
        for (String n : needles) {
            int i = s.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static File find(String... candidates) {
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        throw new AssertionError("not found from " + new File(".").getAbsolutePath() + ": " + candidates[0]);
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<Path> javaSources() throws IOException {
        File root = new File("src/main/java");
        if (!root.isDirectory()) root = new File("app/src/main/java");
        assertTrue("src/main/java not found", root.isDirectory());
        try (Stream<Path> s = Files.walk(root.toPath())) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }
}
