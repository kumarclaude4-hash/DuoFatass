import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiler-AST policy guard for Android release-surviving warning/error logs.
 *
 * Run with JDK 17:
 *   java scripts/AndroidReleaseLogPrivacyPolicy.java app/src/main/java
 *   java scripts/AndroidReleaseLogPrivacyPolicy.java --self-test
 *
 * Parsing is syntax-only: no app dependencies or generated Android classes are
 * needed. The policy deliberately rejects ambiguous exception diagnostics and
 * sensitive getters unless the value is passed through LogRedact.
 *
 * This is a source-policy tripwire, not whole-program taint analysis. Keep the
 * finding Partial until indirect data flow and runtime logcat are separately
 * reviewed and accepted.
 */
public final class AndroidReleaseLogPrivacyPolicy {
    private static final Pattern SENSITIVE_IDENTIFIER = Pattern.compile(
            "(?i).*(uid|account|caller|sender|recipient|peer|address|conversation|convId|"
          + "chatId|callId|messageId|msgId|mediaPath|storagePath|b2Path|filePath|uriStr|"
          + "uri|url|token|seed|pin|displayName|phone|email|contact|groupId|groupName|"
          + "zipFile|fileName|file|body|message|text|avatar|userId|secret|password|reason|"
          + "error|payload|content|input|output|query|request|response|session|cookie|"
          + "authorization|header|subject|title|username|profile|deviceId|passphrase|"
          + "throwable|exception|cause|failure|stacktrace).*");

    // Explicitly safe numeric operational metrics. Keep this allow-list narrow:
    // these values reveal aggregate counts, not user identifiers or content.
    private static final Set<String> SAFE_OPERATIONAL_COUNTERS =
            Collections.unmodifiableSet(new HashSet<>(Collections.singletonList("checksumFailures")));

    private static final Pattern SENSITIVE_ACCESSOR = Pattern.compile(
            "(?i)(get|is|to)(uid|userId|accountId|callerId|senderId|recipientId|peerId|"
          + "address|phone|phoneNumber|email|url|uri|path|absolutePath|canonicalPath|"
          + "token|seed|pin|displayName|groupId|groupName|conversationId|chatId|callId|"
          + "messageId|mediaPath|storagePath|b2Path|filePath|fileName|cookie|session|"
          + "authorization|content|body|text|payload|query|request|response|subject|"
          + "title|username|profile|deviceId).*");

    private AndroidReleaseLogPrivacyPolicy() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            runSelfTests();
            return;
        }

        Path sourceRoot = args.length == 0
                ? Paths.get("app/src/main/java")
                : Paths.get(args[0]);
        ScanResult result = scan(sourceRoot);

        System.out.println("Android release-log AST scan: " + result.javaFiles
                + " Java files, " + result.inspectedCalls
                + " Log.w/Log.e/Log.wtf calls inspected.");
        if (result.inspectedCalls == 0) {
            throw new IllegalStateException("No warning/error calls inspected; scanner is not wired");
        }
        if (!result.violations.isEmpty()) {
            System.err.println("Release log privacy violations (" + result.violations.size() + "):");
            for (String violation : result.violations) System.err.println(" - " + violation);
            System.exit(1);
        }
        System.out.println("PASS: no raw sensitive interpolation or Throwable stack traces found.");
    }

    private static ScanResult scan(Path sourceRoot) throws IOException {
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException("Android Java source root does not exist: "
                    + sourceRoot);
        }

        List<File> javaFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(path -> Files.isRegularFile(path)
                    && path.toString().endsWith(".java"))
                    .forEach(path -> javaFiles.add(path.toFile()));
        }
        if (javaFiles.isEmpty()) {
            throw new IllegalStateException("No Java files found under " + sourceRoot);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A full JDK is required; no system Java compiler found");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> inputs =
                    manager.getJavaFileObjectsFromFiles(javaFiles);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, manager, diagnostics, Collections.singletonList("-proc:none"),
                    null, inputs);

            List<CompilationUnitTree> units = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) units.add(unit);
            SourcePositions positions = Trees.instance(task).getSourcePositions();

            List<String> syntaxErrors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    syntaxErrors.add(diagnostic.getSource() + ":" + diagnostic.getLineNumber()
                            + ": " + diagnostic.getMessage(Locale.ROOT));
                }
            }
            if (!syntaxErrors.isEmpty()) {
                throw new IllegalStateException("Java parsing failed:\n" + join(syntaxErrors));
            }

            List<String> violations = new ArrayList<>();
            int[] inspectedCalls = new int[] {0};
            // Keep inferred Throwable names scoped to one compilation unit. A
            // project-global name set would misclassify unrelated local vars
            // (especially common names such as e) in different Java files.
            for (CompilationUnitTree unit : units) {
                Set<String> throwableVariables = new HashSet<>();
                new ThrowableVariableCollector(throwableVariables).scan(unit, null);
                new LogCallScanner(unit, positions, inspectedCalls, violations,
                        throwableVariables).scan(unit, null);
            }
            return new ScanResult(javaFiles.size(), inspectedCalls[0], violations);
        }
    }

    private static void runSelfTests() throws Exception {
        Path root = Files.createTempDirectory("android-log-privacy-policy");
        try {
            Path safe = Files.createDirectories(root.resolve("safe"));
            Files.writeString(safe.resolve("SafeFixture.java"),
                    "class SafeFixture {\n"
                  + "  void log(Throwable failure, String peerId) {\n"
                  + "    Log.w(\"TAG\", \"request failed: \" + LogRedact.error(failure));\n"
                  + "    Log.e(\"TAG\", \"peer=\" + LogRedact.uid(peerId));\n"
                  + "    Log.wtf(\"TAG\", \"static invariant failure\");\n"
                  + "    Log.w(\"TAG\", \"exception class=\" + failure.getClass().getSimpleName());\n"
                  + "    long checksumFailures = 2;\n"
                  + "    Log.w(\"TAG\", \"integrity failures=\" + checksumFailures);\n"
                  + "  }\n"
                  + "}\n", StandardCharsets.UTF_8);

            ScanResult safeResult = scan(safe);
            if (!safeResult.violations.isEmpty() || safeResult.inspectedCalls != 5) {
                throw new IllegalStateException("Safe redacted fixture failed: "
                        + join(safeResult.violations));
            }

            Path unsafe = Files.createDirectories(root.resolve("unsafe"));
            Files.writeString(unsafe.resolve("UnsafeFixture.java"),
                    "class UnsafeFixture {\n"
                  + "  void warn(Throwable throwable) { Log.w(\"TAG\", throwable); }\n"
                  + "  void fatal(Exception problem) { Log.wtf(\"TAG\", problem); }\n"
                  + "  void stack(Throwable cause) { Log.e(\"TAG\", Log.getStackTraceString(cause)); }\n"
                  + "  void getter(Profile profile) { Log.e(\"TAG\", \"uid=\" + profile.getUserId()); }\n"
                  + "}\n", StandardCharsets.UTF_8);

            ScanResult unsafeResult = scan(unsafe);
            requireViolation(unsafeResult.violations, "Throwable variable");
            requireViolation(unsafeResult.violations, "stack-trace");
            requireViolation(unsafeResult.violations, "sensitive accessor");
            if (unsafeResult.violations.size() < 4) {
                throw new IllegalStateException("Unsafe fixture did not exercise every forbidden path: "
                        + join(unsafeResult.violations));
            }
            System.out.println("PASS: AST policy self-tests (redacted messages accepted; "
                    + "two-argument Throwable overloads, stack traces and sensitive getters rejected).");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Collections.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // The temporary directory is test-only; cleanup must not hide test results.
                    }
                });
            }
        }
    }

    private static void requireViolation(List<String> violations, String expected) {
        for (String violation : violations) {
            if (violation.contains(expected)) return;
        }
        throw new IllegalStateException("Expected violation was not detected: " + expected
                + "\nActual violations:\n" + join(violations));
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) out.append("\n - ").append(value);
        return out.toString();
    }

    private static boolean isThrowableType(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return normalized.contains("throwable")
                || normalized.contains("exception")
                || normalized.contains("error");
    }

    private static final class ScanResult {
        final int javaFiles;
        final int inspectedCalls;
        final List<String> violations;

        ScanResult(int javaFiles, int inspectedCalls, List<String> violations) {
            this.javaFiles = javaFiles;
            this.inspectedCalls = inspectedCalls;
            this.violations = violations;
        }
    }

    private static final class ThrowableVariableCollector extends TreeScanner<Void, Void> {
        private final Set<String> throwableVariables;

        ThrowableVariableCollector(Set<String> throwableVariables) {
            this.throwableVariables = throwableVariables;
        }

        @Override
        public Void visitCatch(CatchTree node, Void unused) {
            throwableVariables.add(node.getParameter().getName().toString());
            return super.visitCatch(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (isThrowableType(node.getType() == null ? "" : node.getType().toString())) {
                throwableVariables.add(node.getName().toString());
            }
            return super.visitVariable(node, unused);
        }
    }

    private static final class LogCallScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final int[] inspectedCalls;
        private final List<String> violations;
        private final Set<String> throwableVariables;

        LogCallScanner(CompilationUnitTree unit, SourcePositions positions,
                       int[] inspectedCalls, List<String> violations,
                       Set<String> throwableVariables) {
            this.unit = unit;
            this.positions = positions;
            this.inspectedCalls = inspectedCalls;
            this.violations = violations;
            this.throwableVariables = throwableVariables;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            String method = methodName(node);
            String receiver = receiverName(node);
            if (("w".equals(method) || "e".equals(method) || "wtf".equals(method))
                    && ("Log".equals(receiver) || receiver.endsWith(".Log"))) {
                inspectedCalls[0]++;
                List<? extends ExpressionTree> args = node.getArguments();
                if (args.size() >= 3) {
                    addViolation(node, "Throwable/third-argument overload is forbidden");
                }
                if (args.size() >= 2) {
                    new MessageScanner(node, throwableVariables).scan(args.get(1), null);
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        private void addViolation(com.sun.source.tree.Tree tree, String reason) {
            long position = positions.getStartPosition(unit, tree);
            long line = position < 0 ? -1 : unit.getLineMap().getLineNumber(position);
            violations.add(unit.getSourceFile().getName() + ":" + line + ": " + reason);
        }

        private final class MessageScanner extends TreeScanner<Void, Void> {
            private final MethodInvocationTree logCall;
            private final Set<String> throwableVariables;
            private Tree parentTree;
            private Tree currentTree;

            MessageScanner(MethodInvocationTree logCall, Set<String> throwableVariables) {
                this.logCall = logCall;
                this.throwableVariables = throwableVariables;
            }

            @Override
            public Void scan(Tree tree, Void unused) {
                Tree previousParent = parentTree;
                Tree previousCurrent = currentTree;
                parentTree = previousCurrent;
                currentTree = tree;
                try {
                    return super.scan(tree, unused);
                } finally {
                    parentTree = previousParent;
                    currentTree = previousCurrent;
                }
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                String name = methodName(node);
                String receiver = receiverName(node);
                if ("uid".equals(name) || "metadata".equals(name) || "error".equals(name)) {
                    if ("LogRedact".equals(receiver) || receiver.endsWith(".LogRedact")) {
                        return null; // The redactor owns and pseudonymises the entire argument subtree.
                    }
                }
                if ("getMessage".equals(name) || "getLocalizedMessage".equals(name)
                        || "printStackTrace".equals(name) || "getStackTrace".equals(name)
                        || "getStackTraceString".equals(name)) {
                    addViolation(logCall, "exception message/stack-trace data is interpolated");
                }
                if ("getCause".equals(name) || "getException".equals(name)
                        || "getThrowable".equals(name) || "getFailure".equals(name)) {
                    addViolation(logCall, "Throwable-producing accessor is interpolated");
                }
                if (SENSITIVE_ACCESSOR.matcher(name).matches()) {
                    addViolation(logCall, "sensitive accessor '" + name + "' is interpolated");
                }
                if (throwableVariables.contains(receiver) && "toString".equals(name)) {
                    addViolation(logCall, "Throwable toString() is interpolated");
                }
                return super.visitMethodInvocation(node, unused);
            }

            private boolean isSafeThrowableClassNameUse(IdentifierTree identifier) {
                if (!(parentTree instanceof MemberSelectTree)) return false;
                MemberSelectTree parent = (MemberSelectTree) parentTree;
                // The identifier must be the receiver in exception.getClass().
                // Only the class label is allowed; the exception itself, message,
                // cause and stack remain forbidden.
                return "getClass".equals(parent.getIdentifier())
                        && parent.getExpression().toString().equals(identifier.getName().toString());
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void unused) {
                if (isThrowableType(node.getIdentifier().toString())) {
                    addViolation(logCall, "new Throwable/Exception/Error is interpolated");
                }
                return super.visitNewClass(node, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                String name = node.getName().toString();
                if (throwableVariables.contains(name) && !isSafeThrowableClassNameUse(node)) {
                    addViolation(logCall, "Throwable variable '" + name + "' is interpolated");
                } else if (SENSITIVE_IDENTIFIER.matcher(name).matches()
                        && !SAFE_OPERATIONAL_COUNTERS.contains(name)) {
                    addViolation(logCall, "raw sensitive identifier '" + name + "' is interpolated");
                }
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                String identifier = node.getIdentifier().toString();
                if ("getMessage".equals(identifier) || "getLocalizedMessage".equals(identifier)
                        || "printStackTrace".equals(identifier) || "getStackTraceString".equals(identifier)
                        || "getPath".equals(identifier) || "getAbsolutePath".equals(identifier)
                        || "getCanonicalPath".equals(identifier)) {
                    addViolation(logCall, "raw exception text, stack trace or filesystem path is interpolated");
                }
                return super.visitMemberSelect(node, unused);
            }

            private void addViolation(com.sun.source.tree.Tree ignored, String reason) {
                LogCallScanner.this.addViolation(logCall, reason);
            }
        }

        private static String methodName(MethodInvocationTree node) {
            if (node.getMethodSelect() instanceof MemberSelectTree) {
                return ((MemberSelectTree) node.getMethodSelect()).getIdentifier().toString();
            }
            if (node.getMethodSelect() instanceof IdentifierTree) {
                return ((IdentifierTree) node.getMethodSelect()).getName().toString();
            }
            return "";
        }

        private static String receiverName(MethodInvocationTree node) {
            if (node.getMethodSelect() instanceof MemberSelectTree) {
                return ((MemberSelectTree) node.getMethodSelect()).getExpression().toString();
            }
            return "";
        }
    }
}
