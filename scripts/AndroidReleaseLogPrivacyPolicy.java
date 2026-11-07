import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
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
 * This includes a conservative method-local alias pass, but is not whole-program
 * taint analysis. Keep the finding Partial until interprocedural data flow and
 * runtime logcat are separately reviewed and accepted.
 */
public final class AndroidReleaseLogPrivacyPolicy {
    private static final Pattern SENSITIVE_IDENTIFIER = Pattern.compile(
            "(?i).*(uid|account|caller|sender|recipient|peer|address|conversation|convId|"
          + "chatId|callId|messageId|msgId|mediaPath|storagePath|b2Path|filePath|uriStr|"
          + "uri|url|token|seed|pin|displayName|phone|email|contact|groupId|groupName|"
          + "zipFile|fileName|file|objectKey|objectName|opName|detail|body|message|text|avatar|userId|secret|password|reason|"
          + "error|payload|content|input|output|query|request|response|session|cookie|"
          + "authorization|header|subject|title|username|profile|deviceId|passphrase|"
          + "alias|keyAlias|keystoreAlias|manufacturer|model|deviceManufacturer|deviceModel|"
          + "throwable|exception|cause|failure|stacktrace).*");

    // Explicitly safe numeric/operational identifiers. Keep this allow-list narrow:
    // these are aggregate counters or fixed numeric protocol constants, not user data.
    private static final Set<String> SAFE_OPERATIONAL_COUNTERS =
            Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
                    "checksumFailures", "CHUNK_PLAINTEXT_SIZE")));

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
        System.out.println("PASS: no raw sensitive interpolation, method-local tainted aliases, or Throwable stack traces found.");
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
                  + "  void log(Throwable failure, String peerId, Profile profile, String alias) {\n"
                  + "    Log.w(\"TAG\", \"request failed: \" + LogRedact.error(failure));\n"
                  + "    Log.e(\"TAG\", \"peer=\" + LogRedact.uid(peerId));\n"
                  + "    Log.e(\"TAG\", \"peer=\" + LogRedact.uid(profile.userId));\n"
                  + "    Log.wtf(\"TAG\", \"static invariant failure\");\n"
                  + "    Log.w(\"TAG\", \"exception class=\" + failure.getClass().getSimpleName());\n"
                  + "    long checksumFailures = 2;\n"
                  + "    Log.w(\"TAG\", \"integrity failures=\" + checksumFailures);\n"
                  + "    Log.w(\"TAG\", \"alias=\" + LogRedact.metadata(alias));\n"
                  + "    Log.w(\"TAG\", \"device=\" + LogRedact.metadata(Build.MODEL) + \"/\" + LogRedact.metadata(Build.MANUFACTURER));\n"
                  + "    Log.w(LogRedact.uid(peerId), \"redacted tag is safe\");\n"
                  + "    String safeValue = LogRedact.metadata(alias);\n"
                  + "    Log.w(\"TAG\", safeValue);\n"
                  + "    StringBuilder safeBuilder = new StringBuilder();\n"
                  + "    safeBuilder.append(LogRedact.uid(peerId));\n"
                  + "    Log.w(\"TAG\", safeBuilder.toString());\n"
                  + "    Map<String, Object> safeFields = new HashMap<>();\n"
                  + "    safeFields.put(\"peer\", LogRedact.uid(peerId));\n"
                  + "    Log.w(\"TAG\", safeFields.toString());\n"
                  + "  }\n"
                  + "}\n", StandardCharsets.UTF_8);

            ScanResult safeResult = scan(safe);
            if (!safeResult.violations.isEmpty() || safeResult.inspectedCalls != 12) {
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
                  + "  void field(Profile profile) { Log.e(\"TAG\", \"uid=\" + profile.userId); }\n"
                  + "  void constant() { Log.e(\"TAG\", \"uid=\" + Config.ACCOUNT_ID); }\n"
                  + "  void messageAlias(Exception e) { String detail = e.getMessage(); Log.e(\"TAG\", detail); }\n"
                  + "  void helperParameter(String opName) { Log.w(\"TAG\", opName); }\n"
                  + "  void rawAlias(String alias) { Log.w(\"TAG\", \"alias=\" + alias); }\n"
                  + "  void rawDeviceModel() { Log.w(\"TAG\", \"device=\" + Build.MODEL + \"/\" + Build.MANUFACTURER); }\n"
                  + "  void rawTag(String uid) { Log.w(uid, \"static message\"); }\n"
                  + "  void localAlias(Profile profile) { String value = profile.getUserId(); Log.e(\"TAG\", value); }\n"
                  + "  void reassignedAlias(Profile profile) { String value = \"ready\"; value = profile.getUserId(); Log.w(\"TAG\", value); }\n"
                  + "  void builderAlias(Profile profile) { StringBuilder builder = new StringBuilder(); builder.append(profile.getUserId()); Log.e(\"TAG\", builder.toString()); }\n"
                  + "  void mapAlias(Profile profile) { Map<String, Object> fields = new HashMap<>(); fields.put(\"peer\", profile.getUserId()); Log.w(\"TAG\", fields.toString()); }\n"
                  + "  void mapReadAlias(Profile profile) { Map<String, Object> fields = new HashMap<>(); fields.put(\"peer\", profile.getUserId()); String value = (String) fields.get(\"peer\"); Log.e(\"TAG\", value); }\n"
                  + "  void concatenatedAlias(Profile profile) { String value = profile.getUserId(); Log.w(\"TAG\", \"peer=\" + value); }\n"
                  + "  void opaqueCallArgumentAlias(Profile profile) { String value = identityEcho(profile.getUserId()); Log.e(\"TAG\", value); }\n"
                  + "}\n", StandardCharsets.UTF_8);

            ScanResult unsafeResult = scan(unsafe);
            requireViolation(unsafeResult.violations, "Throwable variable");
            requireViolation(unsafeResult.violations, "stack-trace");
            requireViolation(unsafeResult.violations, "sensitive accessor");
            requireViolation(unsafeResult.violations, "raw sensitive member");
            requireViolation(unsafeResult.violations, "raw sensitive identifier 'detail'");
            requireViolation(unsafeResult.violations, "raw sensitive identifier 'opName'");
            requireViolation(unsafeResult.violations, "raw sensitive identifier 'alias'");
            requireViolation(unsafeResult.violations, "raw sensitive member 'MODEL'");
            requireViolation(unsafeResult.violations, "raw sensitive member 'MANUFACTURER'");
            requireViolation(unsafeResult.violations, "raw sensitive identifier 'uid'");
            requireViolation(unsafeResult.violations, "method-local sensitive alias/container reaches Log.e message");
            if (unsafeResult.violations.size() < 13) {
                throw new IllegalStateException("Unsafe fixture did not exercise every forbidden path: "
                        + join(unsafeResult.violations));
            }
            System.out.println("PASS: AST policy self-tests (redacted messages accepted; "
                    + "Throwable overloads, stack traces, aliases, device fingerprint fields and sensitive getters rejected).");
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

    /**
     * Conservative method-local data-flow pass for release-log sinks.
     *
     * <p>Tracks sensitive values through local declarations, reassignment,
     * concatenated expressions, StringBuilder/StringBuffer operations, and
     * common map/bundle/JSON/builder insertion and retrieval patterns. Taint is
     * sticky for the remainder of a method: this deliberately prefers a false
     * positive over clearing a value after a branch or a reassignment that the
     * syntax-only pass cannot prove dominates every path.
     *
     * <p>This is not symbol-resolved or interprocedural analysis. It does not
     * prove arbitrary helper return values, reflection or third-party internals.
     */
    private static final class MethodLocalTaintScanner extends TreeScanner<Void, Void> {
        private static final Pattern CONTAINER_TYPE = Pattern.compile(
                "(?i).*(stringbuilder|stringbuffer|stringjoiner|map|bundle|intent|"
              + "jsonobject|jsonarray|contentvalues|list|set|collection|builder|values).*");
        private static final Pattern MUTATOR = Pattern.compile(
                "(?i)(append|add|put|set|with|addProperty|addField|putExtra|putString|"
              + "putSerializable|putParcelable|putInt|putLong|putBoolean).*");

        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final List<String> violations;
        private final Set<String> taintedLocals = new HashSet<>();
        private final Set<String> taintedContainers = new HashSet<>();
        private final Set<String> containerLocals = new HashSet<>();
        private final Set<String> throwableLocals = new HashSet<>();

        MethodLocalTaintScanner(CompilationUnitTree unit, SourcePositions positions,
                                List<String> violations) {
            this.unit = unit;
            this.positions = positions;
            this.violations = violations;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            // A fresh pass is created for each method. Do not cross method or
            // nested-type boundaries with a name-only environment.
            for (VariableTree parameter : node.getParameters()) {
                String name = parameter.getName().toString();
                String type = parameter.getType() == null ? "" : parameter.getType().toString();
                if (SENSITIVE_IDENTIFIER.matcher(name).matches()
                        && !SAFE_OPERATIONAL_COUNTERS.contains(name)) taintedLocals.add(name);
                if (isThrowableType(type)) {
                    throwableLocals.add(name);
                    taintedLocals.add(name);
                }
            }
            scan(node.getBody(), null);
            return null;
        }

        @Override
        public Void visitClass(com.sun.source.tree.ClassTree node, Void unused) {
            // Anonymous/local class methods are analyzed independently when the
            // outer LogCallScanner reaches them.
            return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            String name = node.getName().toString();
            String type = node.getType() == null ? "" : node.getType().toString();
            ExpressionTree initializer = node.getInitializer();
            if (CONTAINER_TYPE.matcher(type).matches()
                    || isContainerConstruction(initializer)) {
                containerLocals.add(name);
            }
            if ((SENSITIVE_IDENTIFIER.matcher(name).matches()
                    && !SAFE_OPERATIONAL_COUNTERS.contains(name)
                    && (initializer == null || !isRedactedExpression(initializer)))
                    || (initializer != null && (hasSensitiveSource(initializer)
                    || containsTrackedAlias(initializer)))) {
                taintedLocals.add(name);
            }
            if (containerLocals.contains(name) && initializer != null
                    && (hasSensitiveSource(initializer) || containsTrackedAlias(initializer))) {
                taintedContainers.add(name);
            }
            if (isThrowableType(type)) {
                throwableLocals.add(name);
                taintedLocals.add(name);
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitAssignment(com.sun.source.tree.AssignmentTree node, Void unused) {
            String target = node.getVariable() instanceof IdentifierTree
                    ? ((IdentifierTree) node.getVariable()).getName().toString() : null;
            if (target != null && (hasSensitiveSource(node.getExpression())
                    || containsTrackedAlias(node.getExpression()))) {
                taintedLocals.add(target);
                if (containerLocals.contains(target)) taintedContainers.add(target);
            }
            return super.visitAssignment(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            String method = methodName(node);
            String receiver = receiverName(node);
            boolean logSink = ("w".equals(method) || "e".equals(method) || "wtf".equals(method))
                    && ("Log".equals(receiver) || receiver.endsWith(".Log"));
            if (logSink && node.getArguments().size() >= 2) {
                List<? extends ExpressionTree> args = node.getArguments();
                String tagAliases = trackedReferenceSummary(args.get(0));
                if (!tagAliases.isEmpty()) {
                    addViolation(node, "method-local sensitive alias/container reaches Log."
                            + method + " tag [" + tagAliases + "]");
                }
                String messageAliases = trackedReferenceSummary(args.get(1));
                if (!messageAliases.isEmpty()) {
                    addViolation(node, "method-local sensitive alias/container reaches Log."
                            + method + " message [" + messageAliases + "]");
                }
            }

            if (MUTATOR.matcher(method).matches()) {
                String root = rootIdentifier(receiverExpression(node));
                if (root != null && containerLocals.contains(root)
                        && hasSensitiveArgument(node.getArguments())) {
                    taintedContainers.add(root);
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        private boolean hasSensitiveArgument(List<? extends ExpressionTree> arguments) {
            for (ExpressionTree argument : arguments) {
                if (hasSensitiveSource(argument) || containsTrackedAlias(argument)) return true;
            }
            return false;
        }

        private boolean isSafeThrowableClassNameCall(MethodInvocationTree node) {
            if (!"getSimpleName".equals(methodName(node))) return false;
            String receiver = receiverName(node);
            String suffix = ".getClass()";
            if (!receiver.endsWith(suffix)) return false;
            String variable = receiver.substring(0, receiver.length() - suffix.length());
            return variable.matches("[A-Za-z_$][A-Za-z0-9_$]*")
                    && throwableLocals.contains(variable);
        }

        private boolean isRedactedExpression(ExpressionTree expression) {
            if (!(expression instanceof MethodInvocationTree)) return false;
            MethodInvocationTree invocation = (MethodInvocationTree) expression;
            String method = methodName(invocation);
            String receiver = receiverName(invocation);
            if ("redactOperationName".equals(method)) return true;
            return ("uid".equals(method) || "metadata".equals(method) || "error".equals(method))
                    && ("LogRedact".equals(receiver) || receiver.endsWith(".LogRedact"));
        }

        /**
         * Known projections that intentionally discard sensitive object contents.
         * These are narrowly enumerated so a numeric status/count/aggregate may be
         * logged without blessing the object from which it was derived.
         */
        private boolean isSafeOperationalProjection(MethodInvocationTree node) {
            String method = methodName(node);
            return "size".equals(method)
                    || "length".equals(method)
                    || "code".equals(method)
                    || "getResponseCode".equals(method)
                    || "getSummary".equals(method)
                    || "copyInto".equals(method);
        }

        private boolean isContainerConstruction(ExpressionTree expression) {
            if (expression instanceof NewClassTree) {
                return CONTAINER_TYPE.matcher(
                        ((NewClassTree) expression).getIdentifier().toString()).matches();
            }
            return false;
        }

        private boolean hasSensitiveSource(ExpressionTree expression) {
            if (expression == null) return false;
            final boolean[] found = {false};
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    String method = methodName(node);
                    String receiver = receiverName(node);
                    if ((("uid".equals(method) || "metadata".equals(method) || "error".equals(method))
                            && ("LogRedact".equals(receiver) || receiver.endsWith(".LogRedact")))
                            || "redactOperationName".equals(method)
                            || isSafeOperationalProjection(node)) {
                        return null; // Exact redaction/projection boundary; do not inspect its receiver/arguments.
                    }
                    if (isSafeThrowableClassNameCall(node)) {
                        return null;
                    }
                    if (SENSITIVE_ACCESSOR.matcher(method).matches()
                            || "getMessage".equals(method) || "getLocalizedMessage".equals(method)
                            || "getCause".equals(method) || "getException".equals(method)
                            || "getThrowable".equals(method) || "getFailure".equals(method)
                            || "getStackTraceString".equals(method)) {
                        found[0] = true;
                    }
                    return found[0] ? null : super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    String name = node.getName().toString();
                    if (taintedLocals.contains(name)
                            || (SENSITIVE_IDENTIFIER.matcher(name).matches()
                            && !SAFE_OPERATIONAL_COUNTERS.contains(name))
                            || throwableLocals.contains(name)) {
                        found[0] = true;
                    }
                    return null;
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    String name = node.getIdentifier().toString();
                    if (!SAFE_OPERATIONAL_COUNTERS.contains(name)
                            && (SENSITIVE_IDENTIFIER.matcher(name).matches()
                            || SENSITIVE_ACCESSOR.matcher(name).matches())) {
                        found[0] = true;
                    }
                    return found[0] ? null : super.visitMemberSelect(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    if (isThrowableType(node.getIdentifier().toString())) found[0] = true;
                    return found[0] ? null : super.visitNewClass(node, unused);
                }
            }.scan(expression, null);
            return found[0];
        }

        private boolean containsTrackedAlias(ExpressionTree expression) {
            return !trackedReferenceSummary(expression).isEmpty();
        }

        private String trackedReferenceSummary(ExpressionTree expression) {
            if (expression == null) return "";
            final Set<String> found = new java.util.TreeSet<>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    String method = methodName(node);
                    String receiver = receiverName(node);
                    if ((("uid".equals(method) || "metadata".equals(method) || "error".equals(method))
                            && ("LogRedact".equals(receiver) || receiver.endsWith(".LogRedact")))
                            || "redactOperationName".equals(method)
                            || isSafeOperationalProjection(node)) {
                        return null;
                    }
                    if (isSafeThrowableClassNameCall(node)) return null;
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    String name = node.getName().toString();
                    if (taintedLocals.contains(name) || taintedContainers.contains(name)) {
                        found.add(name);
                    }
                    return null;
                }
            }.scan(expression, null);
            return String.join(",", found);
        }

        private void addViolation(com.sun.source.tree.Tree tree, String reason) {
            long position = positions.getStartPosition(unit, tree);
            long line = position < 0 ? -1 : unit.getLineMap().getLineNumber(position);
            String violation = unit.getSourceFile().getName() + ":" + line + ": " + reason;
            if (!violations.contains(violation)) violations.add(violation);
        }

        private static boolean isThrowableType(String type) {
            String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
            return normalized.contains("throwable")
                    || normalized.contains("exception")
                    || normalized.contains("error");
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
            ExpressionTree receiver = receiverExpression(node);
            return receiver == null ? "" : receiver.toString();
        }

        private static ExpressionTree receiverExpression(MethodInvocationTree node) {
            return node.getMethodSelect() instanceof MemberSelectTree
                    ? ((MemberSelectTree) node.getMethodSelect()).getExpression() : null;
        }

        private static String rootIdentifier(ExpressionTree expression) {
            if (expression instanceof IdentifierTree) {
                return ((IdentifierTree) expression).getName().toString();
            }
            if (expression instanceof MemberSelectTree) {
                return rootIdentifier(((MemberSelectTree) expression).getExpression());
            }
            if (expression instanceof MethodInvocationTree) {
                return rootIdentifier(receiverExpression((MethodInvocationTree) expression));
            }
            return null;
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
        public Void visitMethod(MethodTree node, Void unused) {
            if (node.getBody() != null) {
                new MethodLocalTaintScanner(unit, positions, violations).scan(node, null);
            }
            return super.visitMethod(node, unused);
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
                    // The tag is emitted to logcat alongside the message. Treat both
                    // arguments as disclosure sinks; a dynamic tag can leak the same
                    // UID/path/credential metadata as the message itself.
                    new MessageScanner(node, throwableVariables).scan(args.get(0), null);
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

            MessageScanner(MethodInvocationTree logCall, Set<String> throwableVariables) {
                this.logCall = logCall;
                this.throwableVariables = throwableVariables;
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
                if ("getSimpleName".equals(name) && isSafeThrowableClassNameCall(node)) {
                    // Exception class labels are safe diagnostics; skip only this
                    // exact expression so its Throwable receiver is not serialized.
                    return null;
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

            private boolean isSafeThrowableClassNameCall(MethodInvocationTree node) {
                String receiver = receiverName(node);
                String suffix = ".getClass()";
                if (!receiver.endsWith(suffix)) return false;
                String variable = receiver.substring(0, receiver.length() - suffix.length());
                return variable.matches("[A-Za-z_$][A-Za-z0-9_$]*")
                        && throwableVariables.contains(variable);
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
                if (throwableVariables.contains(name)) {
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
                // MemberSelectTree identifiers (for example profile.userId or
                // Config.ACCOUNT_ID) are not visited as IdentifierTree nodes.
                // Inspect the selected field explicitly; method-shaped accessors
                // are handled by visitMethodInvocation to avoid duplicate reports.
                if (!SENSITIVE_ACCESSOR.matcher(identifier).matches()
                        && SENSITIVE_IDENTIFIER.matcher(identifier).matches()
                        && !SAFE_OPERATIONAL_COUNTERS.contains(identifier)) {
                    addViolation(logCall, "raw sensitive member '" + identifier + "' is interpolated");
                }
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
