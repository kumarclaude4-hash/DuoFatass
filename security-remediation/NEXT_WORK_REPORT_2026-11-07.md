# DuoShield — Next Work Report
**Updated:** 2026-11-07  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Main baseline:** `0584823d9d60181802816598c845a50158e86ad8`  
**Current security batch:** PR #248 — https://github.com/kumarclaude4-hash/DuoFatass/pull/248

## Completed in the current batch

PR #248 adds conservative method-local taint propagation to the Android release-log AST policy:
- local declarations and aliases;
- reassignment with sticky taint;
- concatenation and nested expressions;
- StringBuilder/StringBuffer append/toString;
- common Map/Bundle/JSON/ContentValues/list/set/builder insertion/retrieval;
- both log tags and log messages;
- explicit redaction boundaries and owner/type-scoped operational projections.

Its exact code head `d676fb0e028fb578cbb2d8aa83d2bb0f3afe8f4b` passed:
- CI [42330785500](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42330785500): **10/10**.
- Security Scan [42330785396](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42330785396): **3/3**.
- AST scan: **204 production Java files / 407 release-surviving warning, error and fatal log calls / zero violations**.

See `security-remediation/sessions/SESSION-S10-N2-METHOD-LOCAL-TAINT-2026-11-07.md` for exact scope, fixtures and test gates. No production deployment or live credential/config change occurred. No physical-device tests were performed.

## Priority 1 — S10-N2 residual: app-owned interprocedural flows

The current scanner is conservative but method-local. The next codeable improvement is a narrowly scoped method-summary pass for app-owned Java helpers:
1. Identify direct helper parameters that receive sensitive values.
2. Record whether a method returns a parameter, field, or value derived from sensitive accessors/builders/containers.
3. Propagate summaries only across resolved app-owned call sites; do not guess third-party behavior.
4. Track simple instance/static field writes and reads where ownership can be resolved safely.
5. Add positive and negative fixtures for safe helper return, tainted pass-through, sanitized return, field write/read, recursion limits and overloaded methods.
6. Keep reflection, native methods, third-party callback internals and unresolved virtual dispatch explicitly out of scope unless a real symbol-resolution engine is adopted.

Do not simply add more generic method names to the safe-projection allow-list. Any new sanitizer/projection must be source-backed and narrowly scoped by exact owner/type/signature. Keep S10-N2 Partial until residual source review and post-feature-complete logcat QA are accepted.

## Priority 2 — S03-M3 owner decision before protocol code

Current behavior remains: random `jti`; DELETE replay is one-use and fail-closed; READ/WRITE remain reusable for retry/re-fetch compatibility; TTL remains 10 minutes. The owner must either ratify that residual or authorize a versioned redesign. Do not make READ/WRITE single-use or shorten TTL without tracing Android upload, retry, range-read and re-fetch flows. Decision template: `security-remediation/S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md`. Keep Partial pending a dated decision.

## Priority 3 — isolated staging/runtime acceptance

| Finding | Required acceptance |
|---|---|
| **S10-N3** | Isolated Cloudflare Worker + R2 + B2 migration, cron retry/recovery and interleaved delete/ETag/HEAD failure scenarios. Never use production bucket credentials in tests. |
| **S05-M3** | Staging admin-session behavior across independent instances/restarts, single/global revoke, expiry, Firestore outage fail-closed behavior, deployed rules/indexes and TTL. Synthetic sessions only. |
| **S02-I2** | Authorized read-back that Render's `ADMIN_LOCKOUT_WORKER_URL`/`ADMIN_LOCKOUT_WORKER_SECRET` pair with the Worker secret; verify Durable Object usage without exposing values. |
| **S02-I3** | Synthetic Firebase staging token: revoke refresh tokens, prove old token rejected on all 12 protected routes and newly issued token accepted. |
| **S05-H2** | Confirm invite-only onboarding contract, then stage invite creation/redeem/revoke and Android onboarding. |
| **S06-L4** | Stage lost-response/admin-unfreeze replay, retry exhaustion, wipe/reboot/sign-in recovery and production Firestore TTL/index/rules/service-account configuration. |

These actions need authorized staging/operator access and redacted evidence. Do not simulate their completion by changing production settings in a code PR.

## Priority 4 — upstream/API decision

### S07-L2: deterministic native key destruction

DuoFat clears its own derivation cache. Pinned libsignal 0.54.1 source has native destruction on Java finalization and Rust `StaticSecret` zeroize-on-drop, but the Java wrapper has no deterministic close/destroy API and clones/transient copies may remain until GC/drop. Do not use reflection or promise synchronous erasure. Remaining choices: upstream-supported deterministic API, a replacement/version with that capability, or an owner-approved residual-risk decision. Keep Partial.

## Priority 5 — physical-device acceptance after feature completion

Do not start these tests until the owner declares the app feature-complete. Then record device model, OEM, Android version, app build, steps, expected/actual result and sanitized evidence:
- **S11-H1:** predictive-back edge gestures on LockScreenActivity and all no-skip gates.
- **S11-M1:** active-call lock timeout, notification return, authentication and in-call continuation.
- **S08-H2:** screenshot/screen-recording/recents behavior on fresh install and explicit opt-in.
- **S08-L3:** 4-, 5- and 6-digit PIN entry, debounce, backspace, interrupted entry and duress prefix.
- **S08-L4:** lock cover prevents content flash and touch, then clears safely on unlock/sign-out/no-PIN.
- **S08-H5 / S07-M1:** real Android Keystore/EncryptedSharedPreferences migration and recovery, including un-migratable legacy installs.
- **S06-L4:** physical wipe/reboot/sign-in/network-loss/lost-response recovery.
- **S05-H2:** full invite onboarding against staging after contract approval.

## Status and sign-off

S10-N2 remains **Partial** for interprocedural/reflective/third-party residuals and device logcat acceptance. Other Partial findings must remain Partial until their listed acceptance evidence exists. `security-remediation/FINAL_SIGNOFF.md` stays **PENDING**. Green CI is not production security approval.

Branch protection/ruleset administration is excluded from this work plan, per the current scope.
