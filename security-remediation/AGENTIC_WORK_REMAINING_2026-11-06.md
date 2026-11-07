# Agentic Work Remaining — 2026-11-06

Repository: kumarclaude4-hash/DuoFatass
Base branch: main
Latest merged mainline: PR #244 at 4fb33fd9978249761ba25f1c3d64f68c534978a4 (CI latest attempt 4: 10/10; Security Scan: 3/3).
Previous work branch: security/release-log-privacy-ast-guard-2026-11-06 (PR #244, merged).
Current work branch: security/s10-n2-indirect-release-log-review-2026-11-07 (follow-up PR not yet opened at time of this handoff update).
Status: PR #244 is merged. Its current head 328731b948e15bb1cc93ca2e61343be4b2325c75 passed CI run 42325532834 latest attempt 4 (10/10 jobs) and Security Scan 42325532774 (3/3). The new S10-N2 follow-up removes the DeviceSecurityGate manufacturer/model release-log fields and pseudonymises the PinKeyGate Keystore alias diagnostic; it extends the AST identifier inventory/fixtures and adds JVM source regression coverage. The follow-up branch's CI/security workflows are not yet verified. Source-level security sign-off remains pending the new workflows, residual indirect-flow review and post-feature-complete device logcat QA.

This is the current execution handoff for the next coding agent. It supersedes the task ordering in older agent handoff notes. Finish the source-level, JVM, backend and isolated-emulator work while the app is being completed. Physical-device acceptance must happen only after the owner declares the app feature-complete.

## 1. Binding instructions

1. Start from the latest main on a fresh branch. Do not commit directly to main, force-push, or reuse an unrelated PR branch.
2. Keep PRs narrow and independently reviewable. Add a regression test for every security behavior changed. Never promote a finding based on comments, source inspection alone, or a build that does not execute the relevant test.
3. Do not perform manual device testing yet. The owner explicitly deferred physical-device and app-flow acceptance until the application is feature-complete. Do not install APKs, begin manual test sessions, or spend time on the device matrix before that milestone. Existing automated CI emulator jobs remain enabled and must keep passing.
4. You may continue source-level work, pure JVM tests, backend tests, static analysis, lint/build checks, Firestore emulator tests and isolated local Worker tests. These do not count as physical-device acceptance.
5. DuoShield identity is seed-derived. Never request or use a real user's mnemonic, seed or private key. Use synthetic fixtures only in an isolated test/staging project with explicit authorization.
6. Never print, log, upload or commit mnemonics, seeds, private keys, Firebase ID tokens, session cookies, Worker secrets, service-account JSON, B2 credentials, or any bearer secret. Evidence may contain variable names, boolean presence checks, timestamps and redacted resource identifiers only.
7. Do not deploy production server, Worker, Firestore rules, Android APK, or change live credentials/configuration as a side effect of a code PR. Prepare runbooks and staging instructions; an authorized owner performs live changes.
8. Keep security-remediation/FINAL_SIGNOFF.md PENDING. Green CI is not production security acceptance.
9. Read security-remediation/SESSION_PROTOCOL.md, START_HERE.md, ROUND3_REMEDIATION_PLAN.md, the current BUG_TRACKER.md row, the relevant audit session report, source and tests before changing a finding.

## 2. Verified baseline

PR #237 is merged to main at 745f1601448a2b35ffe4ff024b8cde0e9454ebcb.

Post-merge CI run 42162494191 completed successfully with 9/9 jobs:
- Server + Worker tests
- Android unit tests
- Lint
- Debug APK
- Espresso instrumentation
- Focused API 35 back-dispatch regression
- Minified release R8 build
- Vendored libsignal integrity
- Gradle wrapper validation

Post-merge security scan 42162494131 completed successfully. These are mainline results before the follow-up work in the current branch; the branch's own PR CI must pass before any new test or tracker evidence is marked verified.

The row-level tracker recount before this branch's CI is 116 findings: 83 Fixed, 20 Partial, 1 Open and 12 Accepted. Do not copy the old 74/29/1/12 historical summary over the current rows.

## 3. Agentic work to do now — no manual device testing

### A. S10-N2 — compiler-AST release-log guard implemented; residual review remains

PR #244 adds `scripts/AndroidReleaseLogPrivacyPolicy.java`, a JDK compiler-AST policy guard wired into CI. It scans the complete production Java source tree and checks multiline/concatenated warning/error log expressions without requiring Android app dependencies. It rejects Throwable overloads, exception message/stack-trace extraction, sensitive getters, raw sensitive identifiers and direct sensitive member fields/constants; redaction boundaries through `LogRedact.uid()`, `metadata()` and `error()` are accepted. The self-test fixtures include both unsafe direct fields/constants and a safe redacted member-field case.

**Executed evidence:** PR #244 head `b0308437613ecc449dea7aa3e523f65651ac0848` passed CI run `42315880847` (10/10 jobs) and Security scan `42315880789` (3/3 jobs). The AST job passed its positive/negative self-tests and scanned **204 production Java files and 407 `Log.w` / `Log.e` / `Log.wtf` calls**, with zero policy violations. The same CI run passed Server + Worker tests, Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified release R8, vendored libsignal integrity and Gradle wrapper validation.

**Follow-up implementation (2026-11-07):** the manual review found two source leaks not caught by the original syntactic scanner. ChatMediaActivity logged an aliased exception message from B2 media loading; B2StorageHelper.withRetry logged an operation parameter populated with raw object keys. Both paths are now fixed: UI detail remains UI-only, error logs show exception class only, and storage retry diagnostics retain the operation prefix while pseudonymising the full suffix (including compound range labels). Regression tests cover both source paths and actual metadata-redaction behavior. New workflow results must be green before promoting the tracker row.

**Post-merge manual source review (2026-11-07):** after PR #244 merged, the current warning/error search inventory returned 45 production Java source files; their release-surviving `Log.w`/`Log.e` call sites and the key dynamic helper sources were inspected. The `SignalKeyApi.post()` failure path logs only a path selected from four fixed literal endpoint strings; BackupManager's `event` value is supplied only from fixed event literals; MediaPlayer error fields and Signal key/device IDs in diagnostic output are numeric status/protocol identifiers; all reviewed exception messages are passed through `LogRedact.error()` or reduced to the exception class; reviewed UID, message ID, B2 path, URL and preference-file values are pseudonymised. Release build configuration was checked: release has `minifyEnabled true` and includes `proguard-rules.pro`, which removes `Log.v/d/i`; identity-bearing `Log.i` source statements found by search are not emitted by the minified release variant.

Two additional direct issues were identified and fixed on this branch: `DeviceSecurityGate.evaluate()` no longer logs `Build.MANUFACTURER` or `Build.MODEL` (unnecessary device-fingerprinting metadata); `PinKeyGate.deleteAlias()` logs only a process-salted metadata pseudonym for the Keystore alias. The AST sensitive-name inventory and positive/negative fixtures now cover `alias`, `MODEL` and `MANUFACTURER`, and `ReleaseLogPrivacyTest` asserts both production boundaries. Branch commits currently include `eacb727`, `1a98953`, `01f196e`, `61bfb47`, `2f910e0`, `a1ad4e3` and the tracker update `898aa7f`; CI has not yet run for this branch.

The source audit is not whole-program taint analysis. It does not prove every builder/map/reflection/opaque helper-return path safe, and no physical logcat evidence is available yet. Keep S10-N2 Partial.

**Remaining:** inspect builder/map/reflection/opaque-helper-return and external-library exception paths beyond the confirmed fixes; the AST scanner is still a syntactic tripwire, not whole-program taint analysis. Review direct/indirect data sources for any remaining user, conversation, content, path, capability or credential values. Physical-device release-logcat acceptance is deferred until the owner declares the app feature-complete. Keep S10-N2 Partial until the manual audit and release QA residuals are accepted; do not treat CI success as production sign-off.

### B. S03-M3 — decide the media capability-token residual

Current token format carries a random jti; DELETE replay is single-use and fail-closed. The 10-minute TTL remains, and READ/WRITE tokens remain reusable to preserve normal upload/download/retry behavior. The analysis and owner-decision template are documented in `security-remediation/S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md`. A dated decision record is now available at `security-remediation/S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md`.

The decision record compares the current residual, a versioned one-shot protocol and TTL-only shortening; it recommends retaining the current behavior until the owner either ratifies the residual or approves a protocol redesign. Do not shorten TTL or make READ/WRITE single-use without tracing Android upload, retry, range-read and re-fetch flows. The owner decision remains **PENDING**, so keep S03-M3 Partial.

### C. S10-N3 — expand deterministic R2/B2 race tests

The Worker suite now also has deterministic failure-injection cases for changed ETag, transient post-PUT R2 HEAD failure, B2 PUT failure and failed compensating B2 DELETE. The handler was corrected so a failed HEAD is not treated as proof of deletion: it retains the R2 source, counts its bytes and lets a later cron retry. Failed compensation now emits an operator-visible, key-free diagnostic, and migration warnings no longer expose object keys. PR #242 CI has now executed the new tests successfully: the Server + Worker tests job passed on commit `92cf469dd0b574cf87816cfd0d9c3df59be5cb20` (workflow run `42181494097`). The tracker records the four added regressions as CI-verified; S10-N3 remains Partial pending isolated live Worker/R2/B2 cron-retry acceptance.

Remaining: live isolated Cloudflare Worker + R2 + B2 acceptance, including cron retry/recovery and confirmation that the production runtime behaves like the deterministic fakes. Never use production bucket credentials in local tests. Keep S10-N3 Partial until isolated live-runtime evidence is recorded.

### D. S07-L2 — document and prove the key destruction boundary

WipeHelper calls SeedPhraseHelper.clearDerivationCache(), and JVM tests prove the AtomicReference is nulled. The exact pinned libsignal 0.54.1 source was checked: Java ECPrivateKey.finalize() releases the native handle, while its Rust PrivateKey owns x25519-dalek 2.0.0 StaticSecret; the default zeroize feature applies Zeroize on drop. However, the Java wrapper exposes no deterministic close/destroy API, finalization is GC-timed, and PrivateKey is Clone. The native key buffer is therefore zeroized on actual drop, not synchronously when the DuoFat wipe clears the cache. See `security-remediation/sessions/SESSION-S07-L2-LIBSIGNAL-BOUNDARY-2026-11-06.md`. Keep Partial; do not reflectively mutate native internals or invent a synchronous-erasure guarantee.

### E. S05-M3 — durable admin-session emulator coverage

Existing JVM/server tests cover independent store instances, cross-instance validation, single/global revocation, concurrent global revoke, idle/absolute expiry and explicit Firestore outage propagation (callers can fail closed). The Firestore emulator integration covers durable transactional behavior across independent instances. The remaining gap is staging/production acceptance with deployed rules/indexes/TTL, restart/outage behavior in the actual environment and authorized synthetic session cookies; do not use a real operator cookie. Do not call emulator evidence production verification; production Firestore rules/index/TTL deployment and staging acceptance remain external.

### F. S02-I2 — ensure every authenticated route uses the shared limiter

The server uses the Cloudflare Durable Object through adminLockoutWorkerClient; local tests cover request construction, malformed responses, timeouts and fail-closed errors. The current PR adds a route-inventory regression to `server/lib/revokedIdTokenWiring.test.js`: all 12 protected routes must call `checkAuthRateLimit()` with their dedicated endpoint bucket, and the `AUTH_RATE_LIMITS` map must exactly match the inventory. The route-inventory guard passed in PR #242's Server + Worker CI (run `42181494097`). Its source-level coverage is verified; S02-I2 remains Partial until an authorized operator reads back the Render `ADMIN_LOCKOUT_WORKER_URL` / `ADMIN_LOCKOUT_WORKER_SECRET` pairing with the Worker's `ADMIN_LOCKOUT_SECRET`, without exposing values.

### G. S02-I3 — revoked Firebase ID-token integration acceptance

All protected verifyIdToken call sites pass checkRevoked=true and the static route-inventory guard is in CI. This is not proof of live Firebase token revocation. Prepare exact staging steps for an authorized operator using synthetic test identities: issue a token, revoke refresh tokens, prove old tokens are rejected on every protected route, and prove a newly issued token succeeds. Do not use a real user's identity or seed and do not claim this complete until redacted staging evidence is returned.

### H. S08-H5 / S07-M1 — legacy preference recovery source work

Review of migration callers, rollback paths and recovery routes is source-complete for the current code. Existing regressions cover unverified schema types, conflicting destination values, retry and preservation of original data. PR #243 added deterministic one-shot fault injection for failed encrypted destination commit, post-write read-back exception, and failed plaintext cleanup commit; retry behavior is covered. The merged PR #243 head `a98d79e46919fa4c472b36a9ec33075d09479a2a` passed CI run `42185303041` (9/9 jobs, including Android unit tests, Espresso, predictive-back and minified R8) plus security run `42185303045`. Do not delete retained legacy data before exact encrypted destination read-back is proven. S08-H5/S07-M1 remain Partial until the real Android Keystore/EncryptedSharedPreferences upgrade/recovery flow and a verified support/recovery path for un-migratable installs are accepted on physical devices after app feature completion.

### I. S05-H2 — invite-only backend contract

Review invite create/list/revoke/validate and mint-token/nonce replay paths, including transaction boundaries, rate limits and fail-closed behavior. Maintain backend HTTP and Firestore emulator tests. Prepare the staging test matrix and onboarding contract, but do not perform Android device onboarding until the app is feature-complete.

## 4. Physical-device acceptance — explicitly deferred

Do not run this section now. Once the owner declares the app feature-complete and freezes the relevant flows, perform one dedicated acceptance pass and record device model, OEM, Android version, app build, steps, expected/actual result and sanitized evidence.

- S11-H1: real predictive-back edge gestures on LockScreenActivity and every no-skip gate across supported Android/OEM versions. API 35 key dispatch is not a physical gesture test.
- S11-M1: start/minimize an active call beyond lock timeout, reopen through the ongoing-call notification, verify lock appears, unlock and return to call; verify incoming calls still work.
- S08-H2: fresh-install screenshot, screen-recording and recents-thumbnail protection defaults, plus explicit user opt-in behavior.
- S08-L3: 4-, 5- and 6-digit PIN flows, confirm/debounce, backspace, interrupted entry and duress-prefix safety.
- S08-L4: opaque lock transition cover blocks interaction and prevents underlying content flashes; it clears correctly on unlock, sign-out and no-PIN paths.
- S08-H5 / S07-M1: upgrade/recovery with Android Keystore and EncryptedSharedPreferences, including legacy un-migratable fixtures.
- S06-L4: duress wipe, reboot, sign-in, network loss, lost response/admin-unfreeze replay and durable pending intent.
- S05-H2: full Android invite onboarding against staging after backend contract is stable.

Do not mark these Fixed or Accepted based on JVM tests, emulator build success or source inspection.

## 5. Owner/operator actions

These require authorized access and must not be performed as side effects of a code PR:
- Revoke/rotate previously exposed Firebase/GCP, Worker, B2, admin/session and deployment credentials; record secret-manager/audit evidence without values.
- Verify production Firestore rules revision, indexes, TTL policies, Admin SDK service-account scopes and enforcement state.
- Review App Check monitoring; enable Firestore/Storage enforcement and rules-side request.app only after compatible client adoption is proven.
- Verify Render-to-Worker Durable Object URL/secret pairing and Cloudflare secrets without exposing values.
- Execute staging Firebase revoked-token, admin-session, invite and duress scenarios using authorized synthetic fixtures.
- Configure and read back SC-12 branch protections: required approving reviews, CODEOWNERS/last-push policy, required CI/security checks, immutable release tags and protected production-release environment.
- Re-check the Vercel preview quota before retrying; avoid repeated deployments while rate-limited.
- Ratify any accepted residual for S03-M3 and approve the final risk register.

## 6. PR evidence and definition of done

Every PR must state finding IDs, threat path, exact changed files, test names and commands, workflow run, result/count, compatibility/migration/retry behavior, residual risk, operator dependencies and the resulting tracker disposition. No secret values or personal data in evidence.

Close source-only tasks only after CI executes the tests. Keep device-only items deferred until app feature completion. Keep operator blockers explicit. Keep FINAL_SIGNOFF.md PENDING until every required criterion is evidenced or formally deferred and approved by the owner.