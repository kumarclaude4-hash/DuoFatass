# DuoShield — Next Work Report
**Date:** 2026-11-07  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Main HEAD:** `0584823d9d60181802816598c845a50158e86ad8`  
**Latest security-log merge:** PR #246 — https://github.com/kumarclaude4-hash/DuoFatass/pull/246

## Current state

PR #244 and PR #246 are merged. PR #246 merged at `0584823d9d60181802816598c845a50158e86ad8`; its exact head `59d31a8adf90eb84055c0f6e76a3d7142708cd8c` passed:
- CI [42327813230](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42327813230): **10/10 jobs passed**.
- Security Scan [42327813361](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42327813361): **3/3 jobs passed**.

There were no open pull requests when this report was prepared. No production deployment, live credential change or physical-device test was performed. The app is not declared feature-complete, so device acceptance remains intentionally deferred. `security-remediation/FINAL_SIGNOFF.md` remains **PENDING**.

## Priority 1 — next repository-side coding task

### S10-N2: strengthen method-local indirect-flow detection

PR #244 provides the broad AST guard; PR #246 also scans the Android log tag argument as well as the message argument and adds raw UID-tag regression fixtures. S10-N2 remains Partial because a compiler-AST pattern guard is not whole-program taint analysis.

Recommended next bounded implementation:
1. Track method-local aliases from known sensitive sources: exception message/path accessors, sensitive getters/fields, user/conversation IDs, media paths, URLs, tokens and credential-bearing values.
2. Propagate taint through local variable declarations, reassignment, string concatenation, `StringBuilder` append, and common map/builder insertion patterns before a value reaches `Log.w/e/wtf` tag or message arguments.
3. Treat `LogRedact.uid()`, `metadata()` and `error()` as explicit sanitization boundaries only for their returned values; do not mark sibling expressions safe.
4. Add positive and negative fixtures for reassignment, helper parameters, collections, builder composition, safe redaction and false-positive-prone numeric operational fields.
5. Keep the pass explicitly scoped as method-local conservative analysis. Do not claim interprocedural/reflection proof without symbol resolution or a dedicated static-analysis framework.

Acceptance: AST self-tests, full production Java scan, Android JVM tests, lint, debug APK, Espresso, API 35, release R8, Server + Worker and Security Scan all pass on the follow-up PR head. Keep S10-N2 Partial until post-feature-complete device logcat QA is also accepted.

## Priority 2 — owner/product decision before protocol code

### S03-M3: media capability-token residual

Current behavior:
- Tokens carry a random `jti`.
- DELETE replay is one-use and fail-closed.
- READ/WRITE tokens remain reusable for normal re-fetch/retry compatibility.
- TTL remains 10 minutes.

The next step is an owner decision: ratify the current READ/WRITE reuse and TTL residual, or authorize a versioned protocol redesign. Do not unilaterally make READ/WRITE single-use or shorten TTL; that can break media retry, range-read and re-fetch flows. Decision template: `security-remediation/S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md`. Until a dated decision is recorded, keep Partial.

## Priority 3 — isolated staging/runtime acceptance

These have code and automated coverage; what remains is authorized runtime evidence using synthetic identities/resources:

| Finding | Required acceptance |
|---|---|
| **S10-N3** | Isolated Cloudflare Worker + R2 + B2 migration, including cron retry/recovery and interleaved delete/ETag/HEAD failures. Never use production bucket credentials for tests. |
| **S05-M3** | Staging admin-session behavior across independent instances/restarts, single/global revoke, Firestore outage fail-closed behavior, deployed rules/indexes and TTL. Use synthetic operator sessions only. |
| **S02-I2** | Read back Render `ADMIN_LOCKOUT_WORKER_URL`/`ADMIN_LOCKOUT_WORKER_SECRET` and matching Worker secret presence/configuration without exposing values. Confirm requests reach the Durable Object rather than process-local fallback. |
| **S02-I3** | In isolated Firebase staging: issue synthetic ID token, revoke refresh tokens, prove old token rejected on all 12 protected routes and freshly issued token accepted. |
| **S05-H2** | Confirm invite-only onboarding is the intended product contract, then stage full invite creation/redeem/revoke and Android onboarding flow. |
| **S06-L4** | Stage lost-response/admin-unfreeze replay, worker retry exhaustion, reboot/sign-in recovery and production Firestore TTL/index/rules/service-account configuration. |

These are not code fixes to simulate by changing production configuration. Record redacted evidence and the exact environment/build used.

## Priority 4 — upstream/API decision

### S07-L2: deterministic native key destruction

The app clears its own derivation cache on wipe. Pinned libsignal 0.54.1 source shows native destruction on Java finalization and Rust `StaticSecret` zeroize-on-drop, but the Java wrapper has no deterministic close/destroy API and key clones/transient copies may remain until GC/drop. Do not use reflection or claim synchronous zeroization. Remaining choices: upstream-supported deterministic destruction API, approved library replacement/version with that capability, or explicit owner-approved residual-risk acceptance. Keep Partial.

## Priority 5 — post-feature-complete physical-device acceptance

Do not start this before the owner declares the app feature-complete. Then run the documented device/OEM matrix and collect sanitized evidence for:

- **S11-H1:** predictive-back edge gesture on LockScreenActivity and every no-skip gate across supported Android/OEM versions (API 35 key dispatch is not equivalent to an edge gesture).
- **S11-M1:** call lifecycle with lock timeout; minimise a call, wait beyond timeout, return through notification, authenticate and verify return to the call; test incoming-call path.
- **S08-H2:** fresh-install screenshot/screen-recording/recents defaults and explicit opt-in.
- **S08-L3:** 4-, 5- and 6-digit PIN entry, debounce, backspace, interrupted entry and duress-prefix behavior.
- **S08-L4:** lock transition cover blocks touch, prevents content flash and clears on unlock/sign-out/no-PIN paths.
- **S08-H5 / S07-M1:** real Android Keystore/EncryptedSharedPreferences migration, recovery and un-migratable legacy install support.
- **S06-L4:** physical wipe/reboot/sign-in/network-loss/lost-response recovery.
- **S05-H2:** full invite onboarding against staging after contract approval.

Do not promote these findings based only on JVM tests, emulator build success or source inspection.

## Excluded from this work plan

Branch protection/ruleset administration is intentionally excluded from this report, per the current work scope. It remains an administrative setting, not an application-code deliverable.

## Sign-off rule

Keep `security-remediation/FINAL_SIGNOFF.md` **PENDING**. PR #246's green CI proves the changed code passed its automated checks; it does not prove every indirect log-data flow safe, validate production configuration, or replace physical-device acceptance.
