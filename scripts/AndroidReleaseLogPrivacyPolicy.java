import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.TypeCastTree;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
import javax.lang.model.element.Modifier;

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
 * This includes a conservative method-local alias pass plus bounded summaries for
 * uniquely resolvable app-owned helper returns, same-class and typed cross-class field writes/reads,
 * conservative known-override handling, recognized reflection boundaries, lambda callbacks and
 * same-owner method-reference callback targets. It is still syntax-only, not symbol-resolved
 * whole-program analysis: external/library dispatch, other method-reference receiver/target
 * shapes, unrecognized reflection APIs and third-party internals are not guessed. Keep the finding
 * Partial until residual source review and runtime logcat are separately reviewed and accepted.
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
        System.out.println("PASS: no raw sensitive interpolation, method-local/interprocedural taint, or Throwable stack traces found.");
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

            MethodReturnSummaryIndex returnSummaries = MethodReturnSummaryIndex.build(units);
            List<String> violations = new ArrayList<>();
            int[] inspectedCalls = new int[] {0};
            // Keep inferred Throwable names scoped to one compilation unit. A
            // project-global name set would misclassify unrelated local vars
            // (especially common names such as e) in different Java files.
            for (CompilationUnitTree unit : units) {
                Set<String> throwableVariables = new HashSet<>();
                new ThrowableVariableCollector(throwableVariables).scan(unit, null);
                new LogCallScanner(unit, positions, inspectedCalls, violations,
                        throwableVariables, returnSummaries).scan(unit, null);
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
                  + "  void log(Throwable failure, String peerId, Profile profile, String alias, HttpURLConnection connection, TurnBandwidthTracker tracker, Response httpResult) {\n"
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
                  + "    int safeCount = safeFields.size();\n"
                  + "    Log.w(\"TAG\", \"record count=\" + safeCount);\n"
                  + "    int statusCode = connection.getResponseCode();\n"
                  + "    Log.w(\"TAG\", \"HTTP status=\" + statusCode);\n"
                  + "    Log.w(\"TAG\", tracker.getSummary());\n"
                  + "    Log.w(\"TAG\", \"OkHttp status=\" + httpResult.code());\n"
                  + "  }\n"
                  + "  void callbackFailureName(java.util.function.Consumer<Exception> listener) { listener.accept(failure -> Log.w(\"TAG\", \"callback failed (\" + failure.getClass().getSimpleName() + \")\")); }\n"
                  + "}\n"
                  + "class B2StorageHelper {\n"
                  + "  String redactOperationName(String value) { return \"download:\" + LogRedact.metadata(value); }\n"
                  + "  void logAlias(String alias) { String safeOperation = redactOperationName(alias); Log.w(\"TAG\", safeOperation); }\n"
                  + "}\n"
                  + "class LegacyPlaintextMigrator {\n"
                  + "  int copyInto(Object target, Map<String, Object> legacy) { return legacy.size(); }\n"
                  + "  void logMigration(Profile profile) { Map<String, Object> legacy = new HashMap<>(); legacy.put(\"peer\", profile.getUserId()); int migrated = copyInto(null, legacy); Log.w(\"TAG\", \"migrated=\" + migrated); }\n"
                  + "}\n"
                  + "class DuressManager {\n"
                  + "  int postDuressLock(String nonce) { return 200; }\n"
                  + "  void logLock(String token) { int lockStatus = postDuressLock(token); Log.w(\"TAG\", \"lock HTTP status=\" + lockStatus); }\n"
                  + "}\n", StandardCharsets.UTF_8);

            ScanResult safeResult = scan(safe);
            if (!safeResult.violations.isEmpty() || safeResult.inspectedCalls != 20) {
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
                  + "  void messageLengthStillSensitive(String message) { int length = message.length(); Log.w(\"TAG\", \"message length=\" + length); }\n"
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
            requireViolation(unsafeResult.violations, "method-local sensitive alias/container reaches Log.w message");
            if (unsafeResult.violations.size() < 13) {
                throw new IllegalStateException("Unsafe fixture did not exercise every forbidden path: "
                        + join(unsafeResult.violations));
            }
            Path interprocedural = Files.createDirectories(root.resolve("interprocedural"));
            Files.writeString(interprocedural.resolve("HelperFlow.java"),
                    "class Profile { String getUserId() { return \"uid\"; } }\n"
                  + "class HelperFlow {\n"
                  + "  String cached;\n"
                  + "  String direct(Profile profile) { return profile.getUserId(); }\n"
                  + "  String pass(String value) { return value; }\n"
                  + "  String sanitized(Profile profile) { return LogRedact.uid(profile.getUserId()); }\n"
                  + "  String recursive(Profile profile, boolean recurse) { if (recurse) return profile.getUserId(); return recursive(profile, true); }\n"
                  + "  String safeRecursive(boolean again) { if (again) return safeRecursive(false); return \"safe\"; }\n"
                  + "  String overloaded(String value) { return \"safe\"; }\n"
                  + "  String overloaded(int value) { return \"safe\"; }\n"
                  + "  void saveField(Profile profile) { this.cached = profile.getUserId(); }\n"
                  + "  String readField() { return this.cached; }\n"
                  + "  void directHelper(Profile profile) { String x = direct(profile); Log.w(\"TAG\", x); }\n"
                  + "  void passThrough(Profile profile) { String x = pass(profile.getUserId()); Log.w(\"TAG\", x); }\n"
                  + "  void sanitizedHelper(Profile profile) { String x = sanitized(profile); Log.w(\"TAG\", x); }\n"
                  + "  void recursiveHelper(Profile profile) { String x = recursive(profile, true); Log.w(\"TAG\", x); }\n"
                  + "  void overloadHelper(Profile profile) { String x = overloaded(profile.getUserId()); Log.w(\"TAG\", x); }\n"
                  + "  void safeRecursionHelper() { String x = safeRecursive(true); Log.w(\"TAG\", x); }\n"
                  + "  void fieldHelper(Profile profile) { saveField(profile); String x = readField(); Log.w(\"TAG\", x); }\n"
                  + "  void operationalScalars() { boolean available = SecurePrefs.isAvailable(); SecurityTier tier = SecurePrefs.getTier(); boolean due = PendingLockStore.isRotationDue(null); int keyId = SignalKeyManager.getCurrentKyberPreKeyId(null); Log.w(\"TAG\", \"ops=\" + available + \"/\" + tier + \"/\" + due + \"/\" + keyId); }\n"
                  + "  void unrelatedSameName(Profile profile) { String x = UnsafeOther.isAvailable(profile.getUserId()); Log.w(\"TAG\", x); }\n"
                  + "  void bodylessHelperReturn(UnknownProfileSource source) { String x = source.fetch(); Log.w(\"TAG\", x); }\n"
                  + "}\n"
                  + "class CrossClassHolder { String memo; void setMemo(String value) { this.memo = value; } }\n"
                  + "class CrossClassFlow { void storeAndLog(Profile profile) { CrossClassHolder holder = new CrossClassHolder(); holder.setMemo(profile.getUserId()); Log.w(\"TAG\", holder.memo); } }\n"
                  + "class SafeBaseSource { String fetch(Profile profile) { return \"safe\"; } }\n"
                  + "class SensitiveDerivedSource extends SafeBaseSource { @Override String fetch(Profile profile) { return profile.getUserId(); } }\n"
                  + "class VirtualDispatchFlow { void log(Profile profile) { SafeBaseSource source = new SensitiveDerivedSource(); String value = source.fetch(profile); Log.w(\"TAG\", value); } }\n"
                  + "class AmbiguousReturnSource { String fetch(Profile profile) { return profile.getUserId(); } String fetch(String selector) { return \"safe\"; } }\n"
                  + "class AmbiguousOverloadFlow { void log(AmbiguousReturnSource source) { String result = source.fetch(\"ordinary\"); Log.w(\"TAG\", result); } }\n"
                  + "abstract class UnknownProfileSource { abstract String fetch(); }\n"
                  + "class SecurityTier { boolean isDurable() { return true; } }\n"
                  + "class SecurePrefs { static boolean initialized = true; static SecurityTier tier = new SecurityTier(); static boolean isAvailable() { return initialized && tier.isDurable(); } static SecurityTier getTier() { return tier; } }\n"
                  + "class PendingLockStore { static boolean isRotationDue(Object context) { return true; } }\n"
                  + "class SignalKeyManager { static int getCurrentKyberPreKeyId(Object context) { return 1; } }\n"
                  + "class UnsafeOther { static String isAvailable(String value) { return value; } }\n"
                  + "class NestedSecret { String memo; void setMemo(String value) { this.memo = value; } String getMemo() { return this.memo; } }\n"
                  + "class SecretWrapper { NestedSecret child; NestedSecret getChild() { return child; } }\n"
                  + "class MultiHopFieldFlow { void log(Profile profile, SecretWrapper wrapper) { wrapper.getChild().setMemo(profile.getUserId()); String resolvedMemo = wrapper.getChild().getMemo(); Log.w(\"TAG\", resolvedMemo); } }\n"
                  + "class InheritedSensitiveBase { String secret; String expose() { return secret; } }\n"
                  + "class InheritedSensitiveChild extends InheritedSensitiveBase { }\n"
                  + "class InheritedMethodFlow { void log(InheritedSensitiveChild source) { String inheritedValue = source.expose(); Log.w(\"TAG\", inheritedValue); } }\n"
                  + "class InheritedFieldBase { NestedSecret child; }\n"
                  + "class InheritedFieldChild extends InheritedFieldBase { }\n"
                  + "class InheritedFieldFlow { void log(Profile profile, InheritedFieldChild holder) { holder.child.setMemo(profile.getUserId()); String inheritedChildValue = holder.child.getMemo(); Log.w(\"TAG\", inheritedChildValue); } }\n"
                  + "class InheritedMemoBase { String diagnostic; void setDiagnostic(String value) { this.diagnostic = value; } }\n"
                  + "class InheritedMemoChild extends InheritedMemoBase { }\n"
                  + "class InheritedDirectFieldFlow { void log(Profile profile, InheritedMemoChild holder) { holder.setDiagnostic(profile.getUserId()); Log.w(\"TAG\", holder.diagnostic); } }\n"
                  + "class ExplicitSuperBase { String secret; String expose() { return secret; } }\n"
                  + "class ExplicitSuperChild extends ExplicitSuperBase { String exposeFromSuper() { return super.expose(); } }\n"
                  + "class ExplicitSuperFlow { void log(ExplicitSuperChild source) { String viaSuper = source.exposeFromSuper(); Log.w(\"TAG\", viaSuper); } }\n"
                  + "class DuplicateOuterA { static class DuplicateReceiver { String ordinary; String expose() { return ordinary; } } }\n"
                  + "class DuplicateOuterB { static class DuplicateReceiver { String privateMemo; String expose() { return privateMemo; } } }\n"
                  + "class DuplicateSimpleNameFlow { void log(DuplicateOuterB.DuplicateReceiver source) { String ambiguousValue = source.expose(); Log.w(\"TAG\", ambiguousValue); } }\n"
                  + "class QualifiedThisOuter { String sessionToken; String expose() { return sessionToken; } class Inner { String delegate() { return QualifiedThisOuter.this.expose(); } } }\n"
                  + "class QualifiedThisFlow { void log(QualifiedThisOuter.Inner inner) { String qualifiedThisValue = inner.delegate(); Log.w(\"TAG\", qualifiedThisValue); } }\n"
                  + "class GenericBox<T> { T getValue() { return null; } }\n"
                  + "class GenericReceiverFlow { void log(GenericBox<String> box) { String genericMemo = box.getValue().toString(); Log.w(\"TAG\", genericMemo); } }\n"
                  + "class ReflectionFlow { void log(String className, Object target) throws Exception { Class<?> dynamicType = Class.forName(className); java.lang.reflect.Method getter = dynamicType.getMethod(\"getName\"); Object reflected = getter.invoke(target); Log.w(\"TAG\", reflected); } }\n"
                  + "class CallbackApi { void register(java.util.function.Consumer<String> callback) { } }\n"
                  + "class CallbackFlow { void register(CallbackApi api) { api.register(value -> Log.w(\"TAG\", value.toString())); } }\n"
                  + "class CallbackMethodReferenceFlow { void callbackLog(String callbackPayload) { Log.w(\"TAG\", callbackPayload); } void register(CallbackApi api) { api.register(this::callbackLog); } }\n", StandardCharsets.UTF_8);

            ScanResult interproceduralResult = scan(interprocedural);
            if (interproceduralResult.inspectedCalls != 24
                    || interproceduralResult.violations.size() != 21) {
                throw new IllegalStateException("Interprocedural taint fixture mismatch: expected "
                        + "21 unsafe helper flows and 3 safe helper flows, got "
                        + interproceduralResult.violations.size() + " violations:\\n"
                        + join(interproceduralResult.violations));
            }
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message");
            requireViolation(interproceduralResult.violations,
                    "HelperFlow.java:22: method-local sensitive alias/container reaches Log.w message");
            requireViolation(interproceduralResult.violations, "field:memo");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [value]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [result]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [resolvedMemo]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [inheritedValue]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [inheritedChildValue]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [viaSuper]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [ambiguousValue]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [qualifiedThisValue]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [genericMemo]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [reflected]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [value]");
            requireViolation(interproceduralResult.violations,
                    "method-local sensitive alias/container reaches Log.w message [callbackPayload]");
            requireViolation(interproceduralResult.violations, "field:diagnostic");
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
     * Bounded, syntax-only summaries for uniquely resolvable app-owned helper methods.
     *
     * <p>Only a method with one same-name/same-arity definition in a uniquely named
     * source class is summarized. This avoids guessing which overload or virtual
     * target a call reaches. Unresolved calls retain the older conservative rule:
     * sensitive arguments make the call result tainted. Resolved helpers propagate
     * only values that flow to a return, allowing a helper that explicitly returns
     * LogRedact output to be a real sanitization boundary.
     *
     * <p>The fixed-point pass is iterative rather than recursive, so self-recursion
     * and mutual recursion cannot exhaust the Java stack. If the bounded pass does
     * not converge, value-returning summaries become unknown/tainted.
     */
    private static final class MethodReturnSummaryIndex {
        private static final int MAX_FIXED_POINT_ROUNDS = 32;

        private final Map<String, ClassSummary> classes = new HashMap<>();
        private final Set<String> ambiguousClasses = new HashSet<>();
        private final Map<String, MethodSummary> summaries = new HashMap<>();

        static MethodReturnSummaryIndex build(List<CompilationUnitTree> units) {
            MethodReturnSummaryIndex index = new MethodReturnSummaryIndex();
            for (CompilationUnitTree unit : units) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        String owner = node.getSimpleName().toString();
                        if (!owner.isEmpty()) {
                            ClassSummary info = new ClassSummary(owner);
                            for (com.sun.source.tree.TypeParameterTree parameter : node.getTypeParameters()) {
                                info.typeParameters.add(parameter.getName().toString());
                            }
                            info.isFinal = node.getModifiers().getFlags().contains(Modifier.FINAL);
                            if (node.getExtendsClause() != null) {
                                info.superClassType = simpleTypeName(node.getExtendsClause().toString());
                                info.superTypes.add(info.superClassType);
                            }
                            for (com.sun.source.tree.Tree implemented : node.getImplementsClause()) {
                                info.superTypes.add(simpleTypeName(implemented.toString()));
                            }
                            Map<String, List<MethodTree>> candidates = new HashMap<>();
                            for (com.sun.source.tree.Tree member : node.getMembers()) {
                                if (member instanceof MethodTree) {
                                    MethodTree method = (MethodTree) member;
                                    if (method.getReturnType() == null) continue;
                                    // Abstract/interface/native app-owned declarations still
                                    // describe a possible helper return. Keep them in the index
                                    // even without a body so callers fail closed instead of
                                    // treating an unresolved zero-argument getter as safe.
                                    String signature = method.getName() + "/" + method.getParameters().size();
                                    candidates.computeIfAbsent(signature, ignored -> new ArrayList<>())
                                            .add(method);
                                } else if (member instanceof VariableTree) {
                                    VariableTree field = (VariableTree) member;
                                    info.fieldNames.add(field.getName().toString());
                                    if (field.getType() != null) {
                                        info.fieldTypes.put(field.getName().toString(),
                                                field.getType().toString());
                                    }
                                }
                            }
                            for (Map.Entry<String, List<MethodTree>> entry : candidates.entrySet()) {
                                if (entry.getValue().size() == 1) {
                                    MethodTree method = entry.getValue().get(0);
                                    String key = methodKey(owner, method);
                                    info.methods.put(entry.getKey(), new MethodDefinition(owner, key, method));
                                    MethodSummary initial = new MethodSummary();
                                    if (method.getBody() == null) {
                                        // There is no source body to prove this return safe.
                                        // Abstract/interface/native app-owned declarations are
                                        // unknown data sources, not sanitizer boundaries.
                                        initial.unknownReturn = true;
                                    }
                                    index.summaries.put(key, initial);
                                } else {
                                    info.ambiguousMethods.add(entry.getKey());
                                }
                            }
                            // Method references can register an app-owned method as a callback
                            // whose arguments arrive later from a framework or SDK. Mark only
                            // unambiguous same-owner forms (this::method, Owner::method and
                            // Owner.this::method); the target method's parameters are tainted
                            // by the method-local pass below.
                            new TreeScanner<Void, Void>() {
                                @Override
                                public Void visitMemberReference(MemberReferenceTree reference, Void ignored) {
                                    String qualifier = reference.getQualifierExpression().toString();
                                    if ("this".equals(qualifier) || owner.equals(qualifier)
                                            || (owner + ".this").equals(qualifier)) {
                                        info.methodReferenceTargets.add(reference.getName().toString());
                                    }
                                    return super.visitMemberReference(reference, ignored);
                                }

                                @Override
                                public Void visitClass(ClassTree nested, Void ignored) {
                                    return null;
                                }
                            }.scan(node, null);
                            for (String field : info.fieldNames) {
                                if (SENSITIVE_IDENTIFIER.matcher(field).matches()
                                        && !SAFE_OPERATIONAL_COUNTERS.contains(field)) {
                                    info.sensitiveFields.add(field);
                                }
                            }
                            if (index.classes.putIfAbsent(owner, info) != null) {
                                index.ambiguousClasses.add(owner);
                            }
                        }
                        return super.visitClass(node, unused);
                    }
                }.scan(unit, null);
            }

            // A call through a base/interface declaration may dispatch to any
            // app-owned override. If a known subtype redeclares that virtual
            // signature, the base method's local body is not enough to prove the
            // runtime return safe. Mark the base summary unknown so every caller
            // that resolves only the declared type fails closed.
            index.markOverriddenVirtualReturnsUnknown();

            boolean converged = false;
            int rounds = Math.min(MAX_FIXED_POINT_ROUNDS,
                    Math.max(1, index.summaries.size() + 1));
            for (int round = 0; round < rounds; round++) {
                Map<String, MethodSummary> previous = copySummaries(index.summaries);
                boolean changed = false;
                for (ClassSummary info : index.classes.values()) {
                    if (index.ambiguousClasses.contains(info.owner)) continue;
                    for (MethodDefinition definition : info.methods.values()) {
                        MethodSummary analyzed = new MethodSummaryAnalyzer(
                                index, info, definition, previous).analyze();
                        MethodSummary current = index.summaries.get(definition.key);
                        if (current != null && current.merge(analyzed)) changed = true;
                        if (current != null) {
                            for (String fieldKey : current.directFieldWrites) {
                                if (index.addSensitiveField(fieldKey)) changed = true;
                            }
                        }
                    }
                }
                if (!changed) {
                    converged = true;
                    break;
                }
            }

            if (!converged) {
                // A deeply chained or otherwise unstable summary must not become a
                // false negative. Fail closed for app-owned helper return values.
                for (MethodDefinition definition : index.allDefinitions()) {
                    MethodSummary summary = index.summaries.get(definition.key);
                    if (summary != null && definition.hasValueReturn()) summary.unknownReturn = true;
                }
            }
            return index;
        }

        private void markOverriddenVirtualReturnsUnknown() {
            for (ClassSummary base : classes.values()) {
                if (ambiguousClasses.contains(base.owner) || base.isFinal) continue;
                for (Map.Entry<String, MethodDefinition> entry : base.methods.entrySet()) {
                    MethodDefinition baseMethod = entry.getValue();
                    if (!isOverridable(baseMethod)) continue;

                    for (ClassSummary candidate : classes.values()) {
                        if (candidate.owner.equals(base.owner)) continue;
                        if (!isSubtypeOf(candidate.owner, base.owner, new HashSet<>())) continue;
                        if (candidate.ambiguousMethods.contains(entry.getKey())) {
                            MethodSummary summary = summaries.get(baseMethod.key);
                            if (summary != null) summary.unknownReturn = true;
                            break;
                        }
                        MethodDefinition override = candidate.methods.get(entry.getKey());
                        if (override != null && isInstanceMethod(override)) {
                            MethodSummary summary = summaries.get(baseMethod.key);
                            if (summary != null) summary.unknownReturn = true;
                            break;
                        }
                    }
                }
            }
        }

        private boolean isSubtypeOf(String candidate, String expectedBase, Set<String> visited) {
            if (!visited.add(candidate)) return false;
            ClassSummary info = classes.get(candidate);
            if (info == null) return false;
            for (String superType : info.superTypes) {
                if (expectedBase.equals(superType) || isSubtypeOf(superType, expectedBase, visited)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isOverridable(MethodDefinition definition) {
            Set<Modifier> flags = definition.method.getModifiers().getFlags();
            return !flags.contains(Modifier.STATIC)
                    && !flags.contains(Modifier.PRIVATE)
                    && !flags.contains(Modifier.FINAL);
        }

        private static boolean isInstanceMethod(MethodDefinition definition) {
            Set<Modifier> flags = definition.method.getModifiers().getFlags();
            return !flags.contains(Modifier.STATIC) && !flags.contains(Modifier.PRIVATE);
        }

        private static String simpleTypeName(String type) {
            String normalized = type.replaceAll("<.*>", "").replace("[]", "").trim();
            int dot = normalized.lastIndexOf('.');
            return dot >= 0 ? normalized.substring(dot + 1) : normalized;
        }

        private static Map<String, MethodSummary> copySummaries(Map<String, MethodSummary> source) {
            Map<String, MethodSummary> result = new HashMap<>();
            for (Map.Entry<String, MethodSummary> entry : source.entrySet()) {
                result.put(entry.getKey(), new MethodSummary(entry.getValue()));
            }
            return result;
        }

        private List<MethodDefinition> allDefinitions() {
            List<MethodDefinition> result = new ArrayList<>();
            for (ClassSummary info : classes.values()) result.addAll(info.methods.values());
            return result;
        }

        boolean isMethodReferenceCallback(String owner, String methodName) {
            ClassSummary info = classes.get(owner);
            return info != null && info.methodReferenceTargets.contains(methodName);
        }

        MethodSummary resolve(String callerOwner, MethodInvocationTree invocation,
                              Map<String, String> localTypes) {
            MethodDefinition definition = resolveDefinition(callerOwner, invocation, localTypes);
            return definition == null ? null : summaries.get(definition.key);
        }

        boolean isAmbiguousAppOwnedCall(String callerOwner, MethodInvocationTree invocation,
                                         Map<String, String> localTypes) {
            String owner = resolveOwner(callerOwner, invocation, localTypes);
            if (owner == null) return false;
            String signature = methodName(invocation) + "/" + invocation.getArguments().size();
            return lookupMethod(owner, signature, new HashSet<>()).ambiguous;
        }

        /**
         * Generic return types whose declared value is a type variable cannot be
         * resolved safely without Java's attributed type model. Treat them as
         * unknown data, including when they appear as an intermediate receiver
         * (for example box.getValue().toString()).
         */
        boolean isUnresolvedGenericReturn(String callerOwner, MethodInvocationTree invocation,
                                          Map<String, String> localTypes) {
            MethodDefinition definition = resolveDefinition(callerOwner, invocation, localTypes);
            if (definition == null || definition.method.getReturnType() == null) return false;
            Set<String> parameters = new HashSet<>();
            ClassSummary owner = classes.get(definition.owner);
            if (owner != null) parameters.addAll(owner.typeParameters);
            for (com.sun.source.tree.TypeParameterTree parameter : definition.method.getTypeParameters()) {
                parameters.add(parameter.getName().toString());
            }
            if (parameters.isEmpty()) return false;
            for (String token : definition.method.getReturnType().toString()
                    .split("[^A-Za-z0-9_$]+")) {
                if (parameters.contains(token)) return true;
            }
            return false;
        }

        boolean isUnresolvedGenericReceiver(String callerOwner, MethodInvocationTree invocation,
                                            Map<String, String> localTypes) {
            ExpressionTree receiver = receiverExpression(invocation);
            while (receiver instanceof ParenthesizedTree || receiver instanceof TypeCastTree) {
                receiver = receiver instanceof ParenthesizedTree
                        ? ((ParenthesizedTree) receiver).getExpression()
                        : ((TypeCastTree) receiver).getExpression();
            }
            return receiver instanceof MethodInvocationTree
                    && isUnresolvedGenericReturn(callerOwner,
                            (MethodInvocationTree) receiver, localTypes);
        }

        /**
         * Reflection and dynamic class-loading results are opaque to this syntax
         * analyzer. They may expose private app data or invoke sensitive accessors
         * without a statically visible call edge, so their outputs fail closed.
         */
        boolean isDynamicReflectionCall(String callerOwner, MethodInvocationTree invocation,
                                        Map<String, String> localTypes) {
            String method = methodName(invocation);
            ExpressionTree receiver = receiverExpression(invocation);
            String receiverText = receiver == null ? "" : receiver.toString();
            String receiverType = resolveExpressionType(callerOwner, receiver, localTypes);
            if (receiverType == null && receiver instanceof IdentifierTree) {
                receiverType = localTypes.get(((IdentifierTree) receiver).getName().toString());
            }
            String type = simpleTypeName(receiverType == null ? receiverText : receiverType);
            if ("forName".equals(method) && "Class".equals(type)) return true;
            if ("loadClass".equals(method) && type.endsWith("ClassLoader")) return true;
            if (("getMethod".equals(method) || "getDeclaredMethod".equals(method)
                    || "getField".equals(method) || "getDeclaredField".equals(method))
                    && "Class".equals(type)) return true;
            if ("invoke".equals(method) && "Method".equals(type)) return true;
            if (("get".equals(method) || "set".equals(method)) && "Field".equals(type)) return true;
            if ("newInstance".equals(method)
                    && ("Constructor".equals(type) || "Class".equals(type))) return true;
            if (method.startsWith("invoke") && "MethodHandle".equals(type)) return true;
            return method.startsWith("find") && "Lookup".equals(type);
        }

        private MethodDefinition resolveDefinition(String callerOwner, MethodInvocationTree invocation,
                                                   Map<String, String> localTypes) {
            String owner = resolveOwner(callerOwner, invocation, localTypes);
            if (owner == null) return null;
            String signature = methodName(invocation) + "/" + invocation.getArguments().size();
            MethodLookup lookup = lookupMethod(owner, signature, new HashSet<>());
            return lookup.ambiguous ? null : lookup.definition;
        }

        /**
         * Resolve methods declared by the receiver type or inherited from an
         * app-owned superclass/interface. Multiple unrelated inherited candidates,
         * duplicate source types and ambiguous overloads are not guessed: callers
         * treat the return as unknown/tainted. Diamond paths to the same declaration
         * are deduplicated by its owner-qualified method key.
         */
        private MethodLookup lookupMethod(String owner, String signature, Set<String> visited) {
            if (!visited.add(owner)) return new MethodLookup(null, true);
            ClassSummary info = classes.get(owner);
            if (info == null) return new MethodLookup(null, false);
            if (ambiguousClasses.contains(owner) || info.ambiguousMethods.contains(signature)) {
                return new MethodLookup(null, true);
            }

            MethodDefinition declared = info.methods.get(signature);
            if (declared != null) return new MethodLookup(declared, false);

            Map<String, MethodDefinition> inherited = new HashMap<>();
            boolean ambiguous = false;
            for (String superType : info.superTypes) {
                if (!classes.containsKey(superType)) continue; // external parent: not indexed
                MethodLookup parent = lookupMethod(superType, signature, new HashSet<>(visited));
                ambiguous |= parent.ambiguous;
                if (parent.definition != null
                        && !parent.definition.method.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
                    inherited.put(parent.definition.key, parent.definition);
                }
            }
            if (ambiguous || inherited.size() > 1) return new MethodLookup(null, true);
            if (inherited.size() == 1) {
                return new MethodLookup(inherited.values().iterator().next(), false);
            }
            return new MethodLookup(null, false);
        }

        private static final class MethodLookup {
            final MethodDefinition definition;
            final boolean ambiguous;

            MethodLookup(MethodDefinition definition, boolean ambiguous) {
                this.definition = definition;
                this.ambiguous = ambiguous;
            }
        }

        private String resolveOwner(String callerOwner, MethodInvocationTree invocation,
                                     Map<String, String> localTypes) {
            ExpressionTree receiver = receiverExpression(invocation);
            if (receiver == null) return callerOwner;
            String receiverText = receiver.toString();
            if ("this".equals(receiverText)) return callerOwner;
            if (receiverText.endsWith(".this")) {
                String enclosing = simpleTypeName(receiverText.substring(
                        0, receiverText.length() - ".this".length()));
                return classes.containsKey(enclosing) ? enclosing : null;
            }
            if (receiverText.endsWith(".super")) return null; // qualified-super lexical owner is not indexed
            if ("super".equals(receiverText)) {
                ClassSummary caller = classes.get(callerOwner);
                String parent = caller == null ? null : caller.superClassType;
                return parent != null && classes.containsKey(parent) && !ambiguousClasses.contains(parent)
                        ? parent : null;
            }
            if (receiverText.endsWith(".super")) {
                String parent = simpleTypeName(receiverText.substring(
                        0, receiverText.length() - ".super".length()));
                return classes.containsKey(parent) && !ambiguousClasses.contains(parent) ? parent : null;
            }

            if (classes.containsKey(receiverText)) {
                return receiverText; // Preserve ambiguity so calls fail closed.
            }
            String resolvedType = resolveExpressionType(callerOwner, receiver, localTypes);
            String simple = simpleTypeName(resolvedType == null ? "" : resolvedType);
            // Return duplicate simple-name owners as well: resolveDefinition()
            // recognizes their ambiguous index entry and taints unknown returns.
            return classes.containsKey(simple) ? simple : null;
        }

        /**
         * Resolve the declared type of a bounded app-owned receiver expression.
         * This follows local variables, app-owned fields and uniquely summarized
         * helper return types through chains such as wrapper.getChild().getValue().
         * It deliberately does not guess external/generic dispatch or reflection.
         */
        private String resolveExpressionType(String callerOwner, ExpressionTree expression,
                                             Map<String, String> localTypes) {
            if (expression == null) return null;
            if (expression instanceof ParenthesizedTree) {
                return resolveExpressionType(callerOwner,
                        ((ParenthesizedTree) expression).getExpression(), localTypes);
            }
            if (expression instanceof TypeCastTree) {
                return ((TypeCastTree) expression).getType().toString();
            }
            if (expression instanceof NewClassTree) {
                return ((NewClassTree) expression).getIdentifier().toString();
            }
            if (expression instanceof IdentifierTree) {
                String name = ((IdentifierTree) expression).getName().toString();
                if ("this".equals(name)) return callerOwner;
                if ("super".equals(name)) {
                    ClassSummary caller = classes.get(callerOwner);
                    return caller == null ? null : caller.superClassType;
                }
                String type = localTypes.get(name);
                if (type != null) return type;
                return classes.containsKey(name) ? name : null;
            }
            if (expression instanceof MemberSelectTree) {
                MemberSelectTree member = (MemberSelectTree) expression;
                String receiverType = resolveExpressionType(
                        callerOwner, member.getExpression(), localTypes);
                if (receiverType == null) return null;
                String ownerType = simpleTypeName(receiverType);
                return resolveFieldType(ownerType, member.getIdentifier().toString(), new HashSet<>());
            }
            if (expression instanceof MethodInvocationTree) {
                MethodDefinition target = resolveDefinition(callerOwner,
                        (MethodInvocationTree) expression, localTypes);
                return target == null || target.method.getReturnType() == null
                        ? null : target.method.getReturnType().toString();
            }
            return null;
        }

        boolean isSensitiveField(String owner, String name) {
            return isSensitiveFieldInHierarchy(owner, name, new HashSet<>());
        }

        private boolean isSensitiveFieldInHierarchy(String owner, String name, Set<String> visited) {
            if (!visited.add(owner)) return false;
            ClassSummary info = classes.get(owner);
            if (info == null || ambiguousClasses.contains(owner)) return false;
            if (info.sensitiveFields.contains(name)) return true;
            for (String superType : info.superTypes) {
                if (isSensitiveFieldInHierarchy(superType, name, visited)) return true;
            }
            return false;
        }

        /**
         * Find an app-owned field's declared type through the source-backed type
         * hierarchy. Conflicting inherited declarations remain unresolved rather
         * than selecting an arbitrary type.
         */
        private String resolveFieldType(String owner, String name, Set<String> visited) {
            if (!visited.add(owner)) return null;
            ClassSummary info = classes.get(owner);
            if (info == null || ambiguousClasses.contains(owner)) return null;
            String declared = info.fieldTypes.get(name);
            if (declared != null) return declared;

            Set<String> inheritedTypes = new HashSet<>();
            for (String superType : info.superTypes) {
                String inherited = resolveFieldType(superType, name, new HashSet<>(visited));
                if (inherited != null) inheritedTypes.add(inherited);
            }
            return inheritedTypes.size() == 1 ? inheritedTypes.iterator().next() : null;
        }

        boolean isSensitiveField(String callerOwner, ExpressionTree receiver, String name,
                                 Map<String, String> localTypes) {
            String fieldOwner = resolveFieldOwner(callerOwner, receiver, localTypes);
            if (fieldOwner != null) return isSensitiveField(fieldOwner, name);

            // An unresolved app-owned receiver must not erase a field flow already
            // proven sensitive on another class. Prefer a conservative false positive
            // over treating an untyped field read as a sanitizer boundary.
            for (ClassSummary info : classes.values()) {
                if (!ambiguousClasses.contains(info.owner) && info.sensitiveFields.contains(name)) {
                    return true;
                }
            }
            return false;
        }

        private String resolveFieldOwner(String callerOwner, ExpressionTree receiver,
                                         Map<String, String> localTypes) {
            if (receiver == null) return callerOwner;
            String receiverText = receiver.toString();
            if ("this".equals(receiverText)) return callerOwner;
            if (receiverText.endsWith(".this")) {
                String enclosing = simpleTypeName(receiverText.substring(
                        0, receiverText.length() - ".this".length()));
                return classes.containsKey(enclosing) ? enclosing : null;
            }
            if (receiverText.endsWith(".super")) return null; // do not guess an enclosing superclass
            if ("super".equals(receiverText)) {
                ClassSummary caller = classes.get(callerOwner);
                return caller == null ? null : caller.superClassType;
            }
            if (receiverText.endsWith(".super")) {
                String parent = simpleTypeName(receiverText.substring(
                        0, receiverText.length() - ".super".length()));
                return classes.containsKey(parent) && !ambiguousClasses.contains(parent) ? parent : null;
            }
            if (classes.containsKey(receiverText)) return receiverText;
            String resolvedType = resolveExpressionType(callerOwner, receiver, localTypes);
            String simple = simpleTypeName(resolvedType == null ? "" : resolvedType);
            return classes.containsKey(simple) ? simple : null;
        }

        private boolean addSensitiveField(String fieldKey) {
            int separator = fieldKey.indexOf('#');
            if (separator <= 0 || separator == fieldKey.length() - 1) return false;
            String owner = fieldKey.substring(0, separator);
            String name = fieldKey.substring(separator + 1);
            ClassSummary info = classes.get(owner);
            return info != null && !ambiguousClasses.contains(owner)
                    && info.sensitiveFields.add(name);
        }

        private static String fieldKey(String owner, String name) {
            return owner + "#" + name;
        }

        /**
         * Exact source-backed scalar projections that return operational state
         * rather than the underlying stored secret/material. The class and method
         * signature must resolve uniquely; similarly named methods on any other
         * owner receive no exemption.
         */
        boolean isSafeOperationalHelper(String callerOwner, MethodInvocationTree invocation,
                                        Map<String, String> localTypes) {
            MethodDefinition target = resolveDefinition(callerOwner, invocation, localTypes);
            if (target == null) return false;
            String signature = target.key;
            return "SecurePrefs#isAvailable/0".equals(signature)
                    || "SecurePrefs#getTier/0".equals(signature)
                    || "PendingLockStore#isRotationDue/1".equals(signature)
                    || "SignalKeyManager#getCurrentKyberPreKeyId/1".equals(signature);
        }

        private static String methodKey(String owner, MethodTree method) {
            return owner + "#" + method.getName() + "/" + method.getParameters().size();
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

        private static final class ClassSummary {
            final String owner;
            final Set<String> typeParameters = new HashSet<>();
            final Set<String> superTypes = new HashSet<>();
            String superClassType;
            boolean isFinal;
            final Set<String> fieldNames = new HashSet<>();
            final Map<String, String> fieldTypes = new HashMap<>();
            final Set<String> sensitiveFields = new HashSet<>();
            final Set<String> ambiguousMethods = new HashSet<>();
            final Set<String> methodReferenceTargets = new HashSet<>();
            final Map<String, MethodDefinition> methods = new HashMap<>();

            ClassSummary(String owner) {
                this.owner = owner;
            }
        }

        private static final class MethodDefinition {
            final String owner;
            final String key;
            final MethodTree method;

            MethodDefinition(String owner, String key, MethodTree method) {
                this.owner = owner;
                this.key = key;
                this.method = method;
            }

            boolean hasValueReturn() {
                final boolean[] found = {false};
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitReturn(com.sun.source.tree.ReturnTree node, Void unused) {
                        if (node.getExpression() != null) found[0] = true;
                        return super.visitReturn(node, unused);
                    }

                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        return null;
                    }
                }.scan(method.getBody(), null);
                return found[0];
            }
        }

        private static final class MethodSummary {
            boolean directSensitiveReturn;
            boolean unknownReturn;
            final Set<Integer> returnedParameters = new HashSet<>();
            final Set<String> directFieldWrites = new HashSet<>();
            final Map<String, Set<Integer>> fieldWritesFromParameters = new HashMap<>();

            MethodSummary() {}

            MethodSummary(MethodSummary source) {
                directSensitiveReturn = source.directSensitiveReturn;
                unknownReturn = source.unknownReturn;
                returnedParameters.addAll(source.returnedParameters);
                directFieldWrites.addAll(source.directFieldWrites);
                for (Map.Entry<String, Set<Integer>> entry : source.fieldWritesFromParameters.entrySet()) {
                    fieldWritesFromParameters.put(entry.getKey(), new HashSet<>(entry.getValue()));
                }
            }

            boolean merge(MethodSummary other) {
                boolean changed = false;
                if (other.directSensitiveReturn && !directSensitiveReturn) {
                    directSensitiveReturn = true;
                    changed = true;
                }
                if (other.unknownReturn && !unknownReturn) {
                    unknownReturn = true;
                    changed = true;
                }
                if (returnedParameters.addAll(other.returnedParameters)) changed = true;
                if (directFieldWrites.addAll(other.directFieldWrites)) changed = true;
                for (Map.Entry<String, Set<Integer>> entry : other.fieldWritesFromParameters.entrySet()) {
                    Set<Integer> current = fieldWritesFromParameters.computeIfAbsent(
                            entry.getKey(), ignored -> new HashSet<>());
                    if (current.addAll(entry.getValue())) changed = true;
                }
                return changed;
            }
        }

        private static final class ValueTaint {
            boolean direct;
            final Set<Integer> parameters = new HashSet<>();

            void merge(ValueTaint other) {
                if (other == null) return;
                direct |= other.direct;
                parameters.addAll(other.parameters);
            }
        }

        private static final class MethodSummaryAnalyzer extends TreeScanner<Void, Void> {
            private final MethodReturnSummaryIndex index;
            private final ClassSummary classInfo;
            private final MethodDefinition definition;
            private final Map<String, MethodSummary> previous;
            private final Map<String, ValueTaint> locals = new HashMap<>();
            private final Map<String, String> localTypes = new HashMap<>();
            private final MethodSummary result = new MethodSummary();

            MethodSummaryAnalyzer(MethodReturnSummaryIndex index, ClassSummary classInfo,
                                  MethodDefinition definition, Map<String, MethodSummary> previous) {
                this.index = index;
                this.classInfo = classInfo;
                this.definition = definition;
                this.previous = previous;
                int parameterIndex = 0;
                for (VariableTree parameter : definition.method.getParameters()) {
                    String name = parameter.getName().toString();
                    String type = parameter.getType() == null ? "" : parameter.getType().toString();
                    localTypes.put(name, type);
                    ValueTaint value = new ValueTaint();
                    value.parameters.add(parameterIndex);
                    if ((SENSITIVE_IDENTIFIER.matcher(name).matches()
                            && !SAFE_OPERATIONAL_COUNTERS.contains(name)) || isThrowableType(type)) {
                        value.direct = true;
                    }
                    locals.put(name, value);
                    parameterIndex++;
                }
                if (definition.method.getBody() != null) {
                    new TreeScanner<Void, Void>() {
                        @Override
                        public Void visitVariable(VariableTree node, Void unused) {
                            if (node.getType() != null) {
                                localTypes.put(node.getName().toString(), node.getType().toString());
                            }
                            return super.visitVariable(node, unused);
                        }

                        @Override
                        public Void visitClass(ClassTree node, Void unused) {
                            return null;
                        }
                    }.scan(definition.method.getBody(), null);
                }
            }

            MethodSummary analyze() {
                scan(definition.method.getBody(), null);
                return result;
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                return null;
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                String name = node.getName().toString();
                if (node.getInitializer() != null) {
                    ValueTaint value = evaluate(node.getInitializer());
                    ValueTaint existing = locals.computeIfAbsent(name, ignored -> new ValueTaint());
                    existing.merge(value);
                } else {
                    locals.putIfAbsent(name, new ValueTaint());
                }
                return super.visitVariable(node, unused);
            }

            @Override
            public Void visitAssignment(com.sun.source.tree.AssignmentTree node, Void unused) {
                ValueTaint value = evaluate(node.getExpression());
                String field = assignedField(node.getVariable());
                if (field != null) {
                    if (value.direct) {
                        result.directFieldWrites.add(field);
                        index.addSensitiveField(field);
                    }
                    if (!value.parameters.isEmpty()) {
                        result.fieldWritesFromParameters.computeIfAbsent(field,
                                ignored -> new HashSet<>()).addAll(value.parameters);
                    }
                } else if (node.getVariable() instanceof IdentifierTree) {
                    String target = ((IdentifierTree) node.getVariable()).getName().toString();
                    locals.computeIfAbsent(target, ignored -> new ValueTaint()).merge(value);
                }
                return super.visitAssignment(node, unused);
            }

            @Override
            public Void visitReturn(com.sun.source.tree.ReturnTree node, Void unused) {
                resultValue(node.getExpression());
                return super.visitReturn(node, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                MethodDefinition target = index.resolveDefinition(definition.owner, node, localTypes);
                if (target != null) {
                    MethodSummary called = previous.get(target.key);
                    if (called != null) {
                        // Field-write summaries carry their declaring owner. A setter
                        // called on another app-owned object must taint the receiver's
                        // field, never an unrelated same-named field on this caller.
                        for (String fieldKey : called.directFieldWrites) {
                            result.directFieldWrites.add(fieldKey);
                            index.addSensitiveField(fieldKey);
                        }
                        for (Map.Entry<String, Set<Integer>> write
                                : called.fieldWritesFromParameters.entrySet()) {
                            for (int parameter : write.getValue()) {
                                if (parameter < node.getArguments().size()) {
                                    ValueTaint argument = evaluate(node.getArguments().get(parameter));
                                    if (argument.direct) {
                                        result.directFieldWrites.add(write.getKey());
                                        index.addSensitiveField(write.getKey());
                                    }
                                    if (!argument.parameters.isEmpty()) {
                                        result.fieldWritesFromParameters.computeIfAbsent(write.getKey(),
                                                ignored -> new HashSet<>()).addAll(argument.parameters);
                                    }
                                }
                            }
                        }
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }

            private void resultValue(ExpressionTree expression) {
                ValueTaint value = evaluate(expression);
                result.directSensitiveReturn |= value.direct;
                result.returnedParameters.addAll(value.parameters);
            }

            private String assignedField(ExpressionTree target) {
                if (target instanceof IdentifierTree) {
                    String name = ((IdentifierTree) target).getName().toString();
                    return classInfo.fieldNames.contains(name) && !locals.containsKey(name)
                            ? MethodReturnSummaryIndex.fieldKey(classInfo.owner, name) : null;
                }
                if (target instanceof MemberSelectTree) {
                    MemberSelectTree member = (MemberSelectTree) target;
                    String name = member.getIdentifier().toString();
                    String fieldOwner = index.resolveFieldOwner(
                            definition.owner, member.getExpression(), localTypes);
                    ClassSummary fieldClass = fieldOwner == null ? null : index.classes.get(fieldOwner);
                    return fieldClass != null && fieldClass.fieldNames.contains(name)
                            ? MethodReturnSummaryIndex.fieldKey(fieldOwner, name) : null;
                }
                return null;
            }

            private ValueTaint evaluate(ExpressionTree expression) {
                ValueTaint value = new ValueTaint();
                if (expression == null) return value;
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void unused) {
                        String name = node.getName().toString();
                        ValueTaint local = locals.get(name);
                        if (local != null) {
                            value.merge(local);
                        } else if (classInfo.sensitiveFields.contains(name)
                                || (SENSITIVE_IDENTIFIER.matcher(name).matches()
                                && !SAFE_OPERATIONAL_COUNTERS.contains(name))) {
                            value.direct = true;
                        }
                        return null;
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        String name = node.getIdentifier().toString();
                        if (index.isSensitiveField(definition.owner, node.getExpression(),
                                name, localTypes)
                                || (SENSITIVE_IDENTIFIER.matcher(name).matches()
                                && !SAFE_OPERATIONAL_COUNTERS.contains(name))) {
                            value.direct = true;
                        }
                        return super.visitMemberSelect(node, unused);
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        String name = methodName(node);
                        String receiver = receiverName(node);
                        if (index.isDynamicReflectionCall(definition.owner, node, localTypes)
                                || index.isUnresolvedGenericReturn(definition.owner, node, localTypes)
                                || index.isUnresolvedGenericReceiver(definition.owner, node, localTypes)) {
                            value.direct = true;
                            return null;
                        }
                        if (isRedactionCall(definition.owner, node)
                                || isSafeOperationalProjection(definition.owner, node, localTypes)
                                || index.isSafeOperationalHelper(definition.owner, node, localTypes)) {
                            return null;
                        }
                        if (SENSITIVE_ACCESSOR.matcher(name).matches()
                                || "getMessage".equals(name) || "getLocalizedMessage".equals(name)
                                || "getCause".equals(name) || "getException".equals(name)
                                || "getThrowable".equals(name) || "getFailure".equals(name)
                                || "getStackTraceString".equals(name)) {
                            value.direct = true;
                            return null;
                        }
                        if (index.isAmbiguousAppOwnedCall(definition.owner, node, localTypes)) {
                            value.direct = true;
                            return null;
                        }
                        MethodDefinition target = index.resolveDefinition(definition.owner, node, localTypes);
                        if (target != null) {
                            MethodSummary called = previous.get(target.key);
                            if (called == null || called.unknownReturn) {
                                value.direct = true;
                            } else {
                                value.direct |= called.directSensitiveReturn;
                                for (int parameter : called.returnedParameters) {
                                    if (parameter < node.getArguments().size()) {
                                        value.merge(evaluate(node.getArguments().get(parameter)));
                                    }
                                }
                            }
                            return null;
                        }
                        // An unresolved external/virtual call is not assumed to be
                        // a sanitizer. Preserve the previous conservative behavior.
                        value.merge(evaluate(receiverExpression(node)));
                        for (ExpressionTree argument : node.getArguments()) {
                            value.merge(evaluate(argument));
                        }
                        return null;
                    }

                    @Override
                    public Void visitNewClass(NewClassTree node, Void unused) {
                        if (isThrowableType(node.getIdentifier().toString())) value.direct = true;
                        for (ExpressionTree argument : node.getArguments()) {
                            value.merge(evaluate(argument));
                        }
                        return null;
                    }
                }.scan(expression, null);
                return value;
            }
        }

        private static boolean isRedactionCall(String owner, MethodInvocationTree node) {
            String method = methodName(node);
            String receiver = receiverName(node);
            if ("redactOperationName".equals(method) && "B2StorageHelper".equals(owner)
                    && (receiver.isEmpty() || receiver.endsWith(".B2StorageHelper"))) return true;
            return ("uid".equals(method) || "metadata".equals(method) || "error".equals(method))
                    && ("LogRedact".equals(receiver) || receiver.endsWith(".LogRedact"));
        }

        private static boolean isSafeOperationalProjection(String owner, MethodInvocationTree node,
                                                            Map<String, String> localTypes) {
            String method = methodName(node);
            ExpressionTree receiver = receiverExpression(node);
            String root = MethodLocalTaintScanner.rootIdentifier(receiver);
            String type = root == null ? "" : localTypes.getOrDefault(root, "");
            if ("size".equals(method)) {
                return type.matches("(?i).*(map|list|set|collection|bundle|jsonarray|jsonobject|contentvalues).*");
            }
            if ("code".equals(method)) return type.endsWith("Response");
            if ("getResponseCode".equals(method)) return type.endsWith("HttpURLConnection");
            if ("getSummary".equals(method)) return type.endsWith("TurnBandwidthTracker");
            if ("copyInto".equals(method)) return "LegacyPlaintextMigrator".equals(owner) && receiver == null;
            if ("postDuressLock".equals(method)) return "DuressManager".equals(owner) && receiver == null;
            return false;
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
        private final String ownerClass;
        private final MethodReturnSummaryIndex returnSummaries;
        private final Map<String, String> localTypes = new HashMap<>();
        private final Set<String> taintedLocals = new HashSet<>();
        private final Set<String> taintedContainers = new HashSet<>();
        private final Set<String> containerLocals = new HashSet<>();
        private final Set<String> throwableLocals = new HashSet<>();
        private final Set<String> callbackParameterLocals = new HashSet<>();

        MethodLocalTaintScanner(CompilationUnitTree unit, SourcePositions positions,
                                List<String> violations, String ownerClass,
                                MethodReturnSummaryIndex returnSummaries) {
            this.unit = unit;
            this.positions = positions;
            this.violations = violations;
            this.ownerClass = ownerClass == null ? "" : ownerClass;
            this.returnSummaries = returnSummaries;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            // A fresh pass is created for each method. Do not cross method or
            // nested-type boundaries with a name-only environment. If this
            // app-owned method is registered through a same-owner method reference,
            // its parameters are callback-supplied opaque data just like lambda args.
            boolean callbackTarget = returnSummaries.isMethodReferenceCallback(
                    ownerClass, node.getName().toString());
            for (VariableTree parameter : node.getParameters()) {
                String name = parameter.getName().toString();
                String type = parameter.getType() == null ? "" : parameter.getType().toString();
                localTypes.put(name, type);
                if (CONTAINER_TYPE.matcher(type).matches()) containerLocals.add(name);
                if (SENSITIVE_IDENTIFIER.matcher(name).matches()
                        && !SAFE_OPERATIONAL_COUNTERS.contains(name)) taintedLocals.add(name);
                boolean safeMediaErrorCode = "VoiceMessagePlayer".equals(ownerClass)
                        && ("what".equals(name) || "extra".equals(name));
                if (callbackTarget && !safeMediaErrorCode) {
                    taintedLocals.add(name);
                    callbackParameterLocals.add(name);
                }
                if (isThrowableType(type)) {
                    throwableLocals.add(name);
                    taintedLocals.add(name);
                }
            }
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree variable, Void ignored) {
                    if (variable.getType() != null) {
                        localTypes.put(variable.getName().toString(), variable.getType().toString());
                    }
                    return super.visitVariable(variable, ignored);
                }

                @Override
                public Void visitClass(ClassTree nested, Void ignored) {
                    return null;
                }
            }.scan(node.getBody(), null);
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
        public Void visitLambdaExpression(com.sun.source.tree.LambdaExpressionTree node, Void unused) {
            // Lambda parameters are an opaque callback boundary: framework/SDK
            // code can supply app data that is not visible at the call site.
            // Keep this taint scoped to the lambda; a name-only analysis must not
            // leak a shadowing parameter into an outer local/catch variable.
            Map<String, String> savedLocalTypes = new HashMap<>(localTypes);
            Set<String> savedTaintedLocals = new HashSet<>(taintedLocals);
            Set<String> savedTaintedContainers = new HashSet<>(taintedContainers);
            Set<String> savedContainerLocals = new HashSet<>(containerLocals);
            Set<String> savedThrowableLocals = new HashSet<>(throwableLocals);
            Set<String> savedCallbackParameterLocals = new HashSet<>(callbackParameterLocals);

            for (VariableTree parameter : node.getParameters()) {
                String name = parameter.getName().toString();
                String type = parameter.getType() == null ? "" : parameter.getType().toString();
                localTypes.put(name, type);
                callbackParameterLocals.add(name);
                // MediaPlayer.OnErrorListener's second and third parameters are
                // integer error codes, not user content. This is a narrow API /
                // owner-specific exception, not a generic "extra" allow-list.
                boolean safeMediaErrorCode = "VoiceMessagePlayer".equals(ownerClass)
                        && ("what".equals(name) || "extra".equals(name));
                if (!safeMediaErrorCode) taintedLocals.add(name);
                if (isThrowableType(type)) throwableLocals.add(name);
            }
            Void result = super.visitLambdaExpression(node, unused);

            // Mutable outer containers can be populated by a callback. Preserve
            // only newly tainted containers that existed before entering it.
            Set<String> capturedContainerTaint = new HashSet<>(taintedContainers);
            capturedContainerTaint.retainAll(savedContainerLocals);
            localTypes.clear();
            localTypes.putAll(savedLocalTypes);
            taintedLocals.clear();
            taintedLocals.addAll(savedTaintedLocals);
            taintedContainers.clear();
            taintedContainers.addAll(savedTaintedContainers);
            taintedContainers.addAll(capturedContainerTaint);
            containerLocals.clear();
            containerLocals.addAll(savedContainerLocals);
            throwableLocals.clear();
            throwableLocals.addAll(savedThrowableLocals);
            callbackParameterLocals.clear();
            callbackParameterLocals.addAll(savedCallbackParameterLocals);
            return result;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            String name = node.getName().toString();
            String type = node.getType() == null ? "" : node.getType().toString();
            localTypes.put(name, type);
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
                    && (throwableLocals.contains(variable)
                    || callbackParameterLocals.contains(variable));
        }

        private boolean isRedactedExpression(ExpressionTree expression) {
            if (!(expression instanceof MethodInvocationTree)) return false;
            MethodInvocationTree invocation = (MethodInvocationTree) expression;
            String method = methodName(invocation);
            String receiver = receiverName(invocation);
            if ("redactOperationName".equals(method)
                    && "B2StorageHelper".equals(ownerClass)
                    && (receiver.isEmpty() || receiver.endsWith(".B2StorageHelper"))) return true;
            return ("uid".equals(method) || "metadata".equals(method) || "error".equals(method))
                    && ("LogRedact".equals(receiver) || receiver.endsWith(".LogRedact"));
        }

        /**
         * Known projections that intentionally discard sensitive object contents.
         * These are narrowly enumerated so a numeric status/count/aggregate may be
         * logged without blessing the object from which it was derived.
         */
        private boolean isRedactedOperationName(MethodInvocationTree node) {
            return "redactOperationName".equals(methodName(node))
                    && "B2StorageHelper".equals(ownerClass)
                    && (receiverName(node).isEmpty()
                    || receiverName(node).endsWith(".B2StorageHelper"));
        }

        private boolean isSafeOperationalProjection(MethodInvocationTree node) {
            String method = methodName(node);
            ExpressionTree receiver = receiverExpression(node);
            String root = rootIdentifier(receiver);
            String receiverType = root == null ? "" : localTypes.getOrDefault(root, "");
            if ("size".equals(method)) {
                // Only collection/map sizes are treated as aggregate counts. A String
                // or byte-array length may expose content length and is not exempt.
                return root != null && containerLocals.contains(root);
            }
            if ("code".equals(method)) {
                return receiverType.endsWith("Response");
            }
            if ("getResponseCode".equals(method)) {
                return receiverType.endsWith("HttpURLConnection");
            }
            if ("getSummary".equals(method)) {
                return receiverType.endsWith("TurnBandwidthTracker");
            }
            if ("copyInto".equals(method)) {
                return "LegacyPlaintextMigrator".equals(ownerClass) && receiver == null;
            }
            if ("postDuressLock".equals(method)) {
                return "DuressManager".equals(ownerClass) && receiver == null;
            }
            return false;
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
                            || (isRedactedOperationName(node))
                            || isSafeOperationalProjection(node)) {
                        return null; // Exact redaction/projection boundary; do not inspect its receiver/arguments.
                    }
                    if (returnSummaries.isDynamicReflectionCall(ownerClass, node, localTypes)
                            || returnSummaries.isUnresolvedGenericReturn(ownerClass, node, localTypes)
                            || returnSummaries.isUnresolvedGenericReceiver(ownerClass, node, localTypes)) {
                        found[0] = true;
                        return null;
                    }
                    if (isSafeThrowableClassNameCall(node)
                            || returnSummaries.isSafeOperationalHelper(ownerClass, node, localTypes)) {
                        return null;
                    }
                    // Source-backed sensitive accessors are an unconditional taint
                    // source. Never let an app-defined stub/helper summary override
                    // this established boundary (e.g. a test double whose getUserId()
                    // body returns a literal must not make the accessor safe).
                    if (SENSITIVE_ACCESSOR.matcher(method).matches()
                            || "getMessage".equals(method) || "getLocalizedMessage".equals(method)
                            || "getCause".equals(method) || "getException".equals(method)
                            || "getThrowable".equals(method) || "getFailure".equals(method)
                            || "getStackTraceString".equals(method)) {
                        found[0] = true;
                        return null;
                    }
                    if (returnSummaries.isAmbiguousAppOwnedCall(ownerClass, node, localTypes)) {
                        // Multiple app-owned declarations share this name/arity signature.
                        // Without type-aware overload resolution, fail closed on the
                        // unknown return even when its visible arguments look harmless.
                        found[0] = true;
                        return null;
                    }
                    MethodReturnSummaryIndex.MethodSummary helperSummary =
                            returnSummaries.resolve(ownerClass, node, localTypes);
                    if (helperSummary != null) {
                        if (helperSummary.unknownReturn || helperSummary.directSensitiveReturn) {
                            found[0] = true;
                            return null;
                        }
                        for (int parameter : helperSummary.returnedParameters) {
                            if (parameter < node.getArguments().size()
                                    && (hasSensitiveSource(node.getArguments().get(parameter))
                                    || containsTrackedAlias(node.getArguments().get(parameter)))) {
                                found[0] = true;
                                return null;
                            }
                        }
                        // A resolved helper is summarized by what it returns. In
                        // particular, a helper returning LogRedact output does not
                        // leak merely because its input was sensitive.
                        return null;
                    }
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    String name = node.getName().toString();
                    if (taintedLocals.contains(name) || returnSummaries.isSensitiveField(ownerClass, name)
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
                    if (returnSummaries.isSensitiveField(ownerClass, node.getExpression(),
                            name, localTypes)
                            || (!SAFE_OPERATIONAL_COUNTERS.contains(name)
                            && (SENSITIVE_IDENTIFIER.matcher(name).matches()
                            || SENSITIVE_ACCESSOR.matcher(name).matches()))) {
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
                            || isRedactedOperationName(node)
                            || isSafeOperationalProjection(node)) {
                        return null;
                    }
                    if (returnSummaries.isDynamicReflectionCall(ownerClass, node, localTypes)) {
                        found.add("dynamic-reflection:" + method);
                        return null;
                    }
                    if (returnSummaries.isUnresolvedGenericReturn(ownerClass, node, localTypes)
                            || returnSummaries.isUnresolvedGenericReceiver(ownerClass, node, localTypes)) {
                        found.add("unresolved-generic:" + method);
                        return null;
                    }
                    if (isSafeThrowableClassNameCall(node)
                            || returnSummaries.isSafeOperationalHelper(ownerClass, node, localTypes)) return null;
                    if (returnSummaries.isAmbiguousAppOwnedCall(ownerClass, node, localTypes)) {
                        found.add("ambiguous-helper-return:" + method);
                        return null;
                    }
                    MethodReturnSummaryIndex.MethodSummary helperSummary =
                            returnSummaries.resolve(ownerClass, node, localTypes);
                    if (helperSummary != null) {
                        if (helperSummary.unknownReturn || helperSummary.directSensitiveReturn) {
                            found.add("helper-return:" + method);
                        } else {
                            for (int parameter : helperSummary.returnedParameters) {
                                if (parameter < node.getArguments().size()
                                        && (hasSensitiveSource(node.getArguments().get(parameter))
                                        || containsTrackedAlias(node.getArguments().get(parameter)))) {
                                    found.add("helper-parameter-return:" + method);
                                }
                            }
                        }
                        return null;
                    }
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    String name = node.getIdentifier().toString();
                    if (returnSummaries.isSensitiveField(ownerClass, node.getExpression(),
                            name, localTypes)) found.add("field:" + name);
                    return super.visitMemberSelect(node, unused);
                }

                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    String name = node.getName().toString();
                    if (taintedLocals.contains(name) || taintedContainers.contains(name)
                            || returnSummaries.isSensitiveField(ownerClass, name)) {
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
        private final MethodReturnSummaryIndex returnSummaries;

        LogCallScanner(CompilationUnitTree unit, SourcePositions positions,
                       int[] inspectedCalls, List<String> violations,
                       Set<String> throwableVariables,
                       MethodReturnSummaryIndex returnSummaries) {
            this.unit = unit;
            this.positions = positions;
            this.inspectedCalls = inspectedCalls;
            this.violations = violations;
            this.throwableVariables = throwableVariables;
            this.returnSummaries = returnSummaries;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            if (node.getBody() != null) {
                String ownerClass = "";
                com.sun.source.util.TreePath parent = getCurrentPath().getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree) {
                    ownerClass = ((ClassTree) parent.getLeaf()).getSimpleName().toString();
                }
                new MethodLocalTaintScanner(unit, positions, violations, ownerClass, returnSummaries)
                        .scan(node, null);
            }
            return super.visitMethod(node, unused);
        }

        private Set<String> callbackParameterNamesForCurrentLambda() {
            Set<String> names = new HashSet<>();
            com.sun.source.util.TreePath path = getCurrentPath();
            while (path != null) {
                if (path.getLeaf() instanceof com.sun.source.tree.LambdaExpressionTree) {
                    for (VariableTree parameter
                            : ((com.sun.source.tree.LambdaExpressionTree) path.getLeaf()).getParameters()) {
                        names.add(parameter.getName().toString());
                    }
                }
                path = path.getParentPath();
            }
            return names;
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
                    Set<String> visibleCallbackParameters = callbackParameterNamesForCurrentLambda();
                    new MessageScanner(node, throwableVariables, visibleCallbackParameters).scan(args.get(0), null);
                    new MessageScanner(node, throwableVariables, visibleCallbackParameters).scan(args.get(1), null);
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
            private final Set<String> callbackParameterNames;

            MessageScanner(MethodInvocationTree logCall, Set<String> throwableVariables,
                           Set<String> callbackParameterNames) {
                this.logCall = logCall;
                this.throwableVariables = throwableVariables;
                this.callbackParameterNames = callbackParameterNames;
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
                        && (throwableVariables.contains(variable)
                        || callbackParameterNames.contains(variable));
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
