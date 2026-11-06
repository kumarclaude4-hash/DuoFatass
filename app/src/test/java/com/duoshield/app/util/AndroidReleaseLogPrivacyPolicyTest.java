package com.duoshield.app.util;

import static org.junit.Assert.assertTrue;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * AST-aware policy guard for Android release-surviving warning/error logs.
 *
 * <p>Log.w/Log.e output is retained in release builds. Their message argument
 * must not interpolate account identifiers, peer/conversation/message data,
 * paths, URLs, tokens, secrets, or user-controlled text, and must not attach a
 * Throwable (whose stack trace and nested messages can disclose the same data).
 *
 * <p>Safe dynamic diagnostics must pass through LogRedact.uid(), metadata(), or
 * error(). The scanner parses Java syntax rather than matching physical lines,
 * so multiline invocations and concatenated expressions are covered.
 */
public class AndroidReleaseLogPrivacyPolicyTest {

    private static final Pattern SENSITIVE_IDENTIFIER = Pattern.compile(
            "(?i).*(uid|account|caller|sender|recipient|peer|address|conversation|convId|"
          + "chatId|callId|messageId|msgId|mediaPath|storagePath|b2Path|filePath|uriStr|"
          + "uri|url|token|seed|pin|displayName|phone|email|contact|groupId|groupName|"
          + "zipFile|fileName|file|body|message|text|avatar|userId|secret|password|"
          + "passphrase).*");

    @Test
    public void releaseWarningAndErrorLogsDoNotExposeSensitiveData() throws Exception {
        Path sourceRoot = findAndroidSourceRoot();
        List<File> javaFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(sourceRoot)) {
            stream.filter(path -> Files.isRegularFile(path)
                    && path.toString().endsWith(".java"))
                    .forEach(path -> javaFiles.add(path.toFile()));
        }
        assertTrue("Android source tree was empty: " + sourceRoot, !javaFiles.isEmpty());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue("A full JDK is required for the release-log AST guard", compiler != null);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                    manager.getJavaFileObjectsFromFiles(javaFiles);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, manager, diagnostics, Collections.singletonList("-proc:none"),
                    null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            SourcePositions positions = Trees.instance(task).getSourcePositions();

            List<String> violations = new ArrayList<>();
            int[] inspectedCalls = new int[] {0};
            for (CompilationUnitTree unit : parsed) {
                new LogCallScanner(unit, positions, inspectedCalls, violations).scan(unit, null);
            }

            assertTrue("No Log.w/Log.e calls were inspected; guard is not wired",
                    inspectedCalls[0] > 0);
            assertTrue("Release log privacy violations (" + violations.size() + "):\n"
                    + join(violations), violations.isEmpty());
        }
    }

    private static Path findAndroidSourceRoot() {
        Path cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null) {
            Path candidate = cursor.resolve("app/src/main/java");
            if (Files.isDirectory(candidate)) return candidate;
            if ("app".equals(String.valueOf(cursor.getFileName()))) {
                candidate = cursor.resolve("src/main/java");
                if (Files.isDirectory(candidate)) return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("Could not locate app/src/main/java from "
                + System.getProperty("user.dir"));
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) out.append("\n - ").append(value);
        return out.toString();
    }

    private static final class LogCallScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final int[] inspectedCalls;
        private final List<String> violations;

        LogCallScanner(CompilationUnitTree unit, SourcePositions positions,
                       int[] inspectedCalls, List<String> violations) {
            this.unit = unit;
            this.positions = positions;
            this.inspectedCalls = inspectedCalls;
            this.violations = violations;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            String method = methodName(node);
            String receiver = receiverName(node);
            if (("w".equals(method) || "e".equals(method))
                    && ("Log".equals(receiver) || receiver.endsWith(".Log"))) {
                inspectedCalls[0]++;
                List<? extends com.sun.source.tree.ExpressionTree> args = node.getArguments();
                if (args.size() >= 3) {
                    addViolation(node, "Throwable/third-argument overload is forbidden");
                }
                if (args.size() >= 2) {
                    new MessageScanner(node).scan(args.get(1), null);
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        private void addViolation(com.sun.source.tree.Tree tree, String reason) {
            long position = positions.getStartPosition(unit, tree);
            long line = position < 0 ? -1 : unit.getLineMap().getLineNumber(position);
            String source = unit.getSourceFile().getName();
            violations.add(source + ":" + line + ": " + reason);
        }

        private final class MessageScanner
                extends com.sun.source.util.TreeScanner<Void, Void> {
            private final MethodInvocationTree logCall;

            MessageScanner(MethodInvocationTree logCall) {
                this.logCall = logCall;
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                String name = methodName(node);
                if ("uid".equals(name) || "metadata".equals(name) || "error".equals(name)) {
                    String receiver = receiverName(node);
                    if ("LogRedact".equals(receiver) || receiver.endsWith(".LogRedact")) {
                        return null;
                    }
                }
                if ("getMessage".equals(name) || "printStackTrace".equals(name)) {
                    addViolation(logCall, "exception message/stack trace is interpolated");
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                if (SENSITIVE_IDENTIFIER.matcher(node.getName().toString()).matches()) {
                    addViolation(logCall, "raw sensitive identifier '" + node.getName()
                            + "' is interpolated");
                }
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                String identifier = node.getIdentifier().toString();
                if ("getMessage".equals(identifier) || "getPath".equals(identifier)
                        || "getAbsolutePath".equals(identifier)) {
                    addViolation(logCall, "raw exception text or filesystem path is interpolated");
                }
                return super.visitMemberSelect(node, unused);
            }
        }

        private String methodName(MethodInvocationTree node) {
            if (node.getMethodSelect() instanceof MemberSelectTree) {
                return ((MemberSelectTree) node.getMethodSelect()).getIdentifier().toString();
            }
            if (node.getMethodSelect() instanceof IdentifierTree) {
                return ((IdentifierTree) node.getMethodSelect()).getName().toString();
            }
            return "";
        }

        private String receiverName(MethodInvocationTree node) {
            if (node.getMethodSelect() instanceof MemberSelectTree) {
                return ((MemberSelectTree) node.getMethodSelect()).getExpression().toString();
            }
            return "";
        }
    }
}
