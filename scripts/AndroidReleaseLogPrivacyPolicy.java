import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

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
 *
 * This is intentionally a standalone JDK source-file launcher rather than an
 * Android unit test: the Android boot classpath does not expose jdk.compiler's
 * com.sun.source APIs. Parsing is syntax-only; no app dependencies or generated
 * Android classes are needed.
 */
public final class AndroidReleaseLogPrivacyPolicy {
    private static final Pattern SENSITIVE_IDENTIFIER = Pattern.compile(
            "(?i).*(uid|account|caller|sender|recipient|peer|address|conversation|convId|"
          + "chatId|callId|messageId|msgId|mediaPath|storagePath|b2Path|filePath|uriStr|"
          + "uri|url|token|seed|pin|displayName|phone|email|contact|groupId|groupName|"
          + "zipFile|fileName|file|body|message|text|avatar|userId|secret|password|reason|error|payload|content|input|output|query|request|response|session|cookie|authorization|header|subject|title|username|profile|deviceId|"
          + "passphrase).*");

    private AndroidReleaseLogPrivacyPolicy() {}

    public static void main(String[] args) throws Exception {
        Path sourceRoot = args.length == 0
                ? Paths.get("app/src/main/java")
                : Paths.get(args[0]);
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
            Iterable<? extends JavaFileObject> units =
                    manager.getJavaFileObjectsFromFiles(javaFiles);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, manager, diagnostics, Collections.singletonList("-proc:none"),
                    null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
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
            for (CompilationUnitTree unit : parsed) {
                new LogCallScanner(unit, positions, inspectedCalls, violations).scan(unit, null);
            }

            System.out.println("Android release-log AST scan: " + javaFiles.size()
                    + " Java files, " + inspectedCalls[0] + " Log.w/Log.e/Log.wtf calls inspected.");
            if (inspectedCalls[0] == 0) {
                throw new IllegalStateException("No warning/error calls inspected; scanner is not wired");
            }
            if (!violations.isEmpty()) {
                System.err.println("Release log privacy violations (" + violations.size() + "):");
                for (String violation : violations) System.err.println(" - " + violation);
                System.exit(1);
            }
            System.out.println("PASS: no raw sensitive interpolation or Throwable stack traces found.");
        }
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
            if (("w".equals(method) || "e".equals(method) || "wtf".equals(method))
                    && ("Log".equals(receiver) || receiver.endsWith(".Log"))) {
                inspectedCalls[0]++;
                List<? extends ExpressionTree> args = node.getArguments();
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
            violations.add(unit.getSourceFile().getName() + ":" + line + ": " + reason);
        }

        private final class MessageScanner extends TreeScanner<Void, Void> {
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
                        return null; // The redactor owns and pseudonymises the entire argument subtree.
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
