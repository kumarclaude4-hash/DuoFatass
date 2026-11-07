# S10-N2 Method-Local Release-Log Taint Analysis — 2026-11-07

## Status

**Implemented and CI/security verified on PR #248; S10-N2 remains Partial.** This is not production security sign-off. Physical-device release-logcat acceptance remains deferred until the owner declares the app feature-complete.

- Base: `main` after PR #246 merge, `0584823d9d60181802816598c845a50158e86ad8`.
- Work branch: `security/s10-n2-method-local-log-taint-2026-11-07`.
- Code change head verified: `d676fb0e028fb578cbb2d8aa83d2bb0f3afe8f4b`.
- PR: https://github.com/kumarclaude4-hash/DuoFatass/pull/248
- CI: [42330785500](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42330785500), **10/10 jobs passed**.
- Security Scan: [42330785396](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42330785396), **3/3 jobs passed**.

## Implementation

PR #248 extends the JDK compiler-AST policy guard with a conservative, method-local data-flow pass.

### Sources and propagation

- Method parameters and local declarations are tracked when their names/types or initializer expressions identify sensitive data.
- Taint propagates through local aliases, assignment/reassignment, nested expressions and string concatenation.
- Reassignment is deliberately sticky for the remainder of the method. The analyzer does not attempt to prove that a branch dominates all uses; it prefers conservative false positives to unsafe clearing.
- StringBuilder/StringBuffer append and toString flows are tracked.
- Common Map/Bundle/JSON/ContentValues/list/set/builder insertion operations mark the container when a sensitive value is inserted; later reads and toString expressions are treated as tainted.
- Sensitive sources nested inside an opaque call argument propagate into the enclosing local initializer. Unknown helper calls are not assumed to sanitize inputs.
- Both Android warning/error/fatal log tag and message arguments are disclosure sinks.

### Sanitization and safe projections

- Explicit sanitizer boundaries: `LogRedact.uid()`, `LogRedact.metadata()`, `LogRedact.error()`.
- `B2StorageHelper.redactOperationName()` is accepted only when analyzed inside the `B2StorageHelper` owner class.
- Numeric/aggregate projections are constrained by source type or owner:
  - collection `size()` only for recognized container locals; string/byte-array `length()` is not exempt;
  - OkHttp `Response.code()` only when the receiver is a Response type;
  - `HttpURLConnection.getResponseCode()` only for an HttpURLConnection receiver;
  - `TurnBandwidthTracker.getSummary()` only for that receiver type;
  - `LegacyPlaintextMigrator.copyInto()` only from that owner class;
  - `DuressManager.postDuressLock()` only from that owner class.
- Sensitive arguments to the owner-scoped helper projections are not treated as sanitized outputs unless the method is one of the explicitly reviewed return-value projections.

## Regression fixtures

The AST self-test includes:
- positive safe direct redaction and redacted log tags;
- positive safe aliases, StringBuilder and map values after redaction;
- positive numeric status, aggregate count and reviewed helper-return projections;
- negative raw UID tag and message, Throwable values, stack traces, sensitive getters/fields;
- negative direct aliases, reassignment, concatenation, builder append, Map put/toString/get, and sensitive value nested in an opaque call argument;
- negative string-length projection to ensure message length is not silently allowed;
- owner/type-scoped safe projection behavior, preventing unrelated methods with identical names from inheriting these exemptions.

## Executed verification

On exact code head `d676fb0e028fb578cbb2d8aa83d2bb0f3afe8f4b`:

| Gate | Result |
|---|---|
| Android release-log AST self-test + full scan | Passed — 204 Java files, 407 `Log.w/e/wtf` calls, zero violations |
| Server + Worker tests | Passed |
| Android unit tests | Passed |
| Android lint | Passed |
| Debug APK | Passed |
| Espresso instrumented tests | Passed; workflow confirmed tests executed and uploaded report |
| API 35 predictive-back dispatch | Passed; workflow confirmed focused test executed |
| Minified release R8 | Passed; mapping output verified and uploaded |
| Gradle wrapper JAR integrity | Passed |
| Vendored libsignal JAR integrity | Passed |
| Gitleaks | Passed |
| CodeQL | Passed |
| SBOM generation | Passed |

## Limitations and acceptance

This implementation is syntax-only and method-local. It is not symbol-resolved whole-program taint analysis and does not prove arbitrary cross-method return/field/global flows, reflection, aliasing through unknown external libraries or third-party callback internals safe. Sticky taint can create false positives; the projection allow-list must remain small, source-backed and owner/type-scoped.

Keep S10-N2 **Partial** until residual source review and post-feature-complete physical release-logcat QA are accepted. Do not claim production acceptance from static analysis or green CI. Keep `security-remediation/FINAL_SIGNOFF.md` **PENDING**.
