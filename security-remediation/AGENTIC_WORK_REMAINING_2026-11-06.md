# Agentic Work Remaining — current handoff, 2026-11-07

Repository: `kumarclaude4-hash/DuoFatass`
Base branch: `main`
Current main HEAD: `98f76cdf374eeaf5c8144befad068ef05805a20b` (PR #256 merged).
Recent merged work:
- PR #251 — bounded interprocedural Android release-log taint analysis; S10-N2 remains Partial.
- PR #255 — invite redemption UID pseudonymization; S05-H2 remains Partial pending legacy-data and staging acceptance.
- PR #256 — bounded range-read request accounting for encrypted chunked video playback.
- PR #257 — documentation reconciliation for the S05-H2 privacy closeout.
Mainline CI [42387261365](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261365) and Security Scan [42387261135](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261135) both passed on `98f76cdf`.

## Current open code PRs — observed 2026-11-07

| PR | Scope | Exact head | Base used by PR | Current state |
|---|---|---|---|---|
| [#253](https://github.com/kumarclaude4-hash/DuoFatass/pull/253) | Fail closed on invalid invite lifecycle states (S05-H2) | `231af6f3a499b60286d567a6628876abfb5d6d31` | `3d41501cf55d706d47bd7bec1a24ccb82d60ae1a` | CI 10/10 and Security 3/3 passed, but GitHub reports **dirty/non-mergeable**; rebase/resolve against current main and rerun before merge. |
| [#254](https://github.com/kumarclaude4-hash/DuoFatass/pull/254) | Half-unit accounting for valid single-range Worker GETs (video range playback) | `1d5583584572859bcb07b39bba0628c554226c84` | `3d41501cf55d706d47bd7bec1a24ccb82d60ae1a` | CI 10/10 and Security 3/3 passed, but GitHub reports **dirty/non-mergeable**; rebase/resolve against current main and rerun before merge. |
| [#258](https://github.com/kumarclaude4-hash/DuoFatass/pull/258) | Normalize escaped PEM newlines in invite UID migration utility | `b20a5bf628c2a58b29b41f5cf71668d88d445b0d` | `89ac44cf85df1c90530196fdbb01bf93ff2e8994` | Mergeable but unstable. CI run [42387218211](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387218211) passed; Security [42387218255](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387218255) failed Gitleaks on a synthetic PEM-shaped test fixture; Firestore Rules/server transaction run [42387218230](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387218230) failed one invite expiry integration test with `Expiry must be between 1 hour and 90 days from now`. Fix the fixture false positive and make the expiry test deterministic (injected clock or safe margin), then rerun all gates. |

Do not merge PRs #253/#254 until rebased and fresh checks pass. Do not merge #258 until both failures are corrected. The branches were based before the current mainline; their passing checks are against their then-current merge bases, not the exact current main.

S10-N2 remains **Partial**: syntax-only analysis is not whole-program proof, and physical-device release-logcat acceptance is deferred until feature-complete. `security-remediation/FINAL_SIGNOFF.md` remains **PENDING**.

This is the current execution handoff. Start new work from the latest main on a fresh branch. Physical-device acceptance must wait until the owner declares the app feature-complete.

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

Current main HEAD: `98f76cdf374eeaf5c8144befad068ef05805a20b`.
Main CI [42387261365](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261365) and Security Scan [42387261135](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261135) both completed successfully. The exact mainline check runs include Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified R8, Server/Worker tests, release-log AST, Cloudflare Worker build validation, CodeQL, Gitleaks, SBOM, Gradle wrapper and vendored libsignal integrity.

Latest relevant merged PR evidence:
- PR #251: bounded interprocedural S10-N2 analyzer; exact head `d6041db2033b1950cdf006b900ef5cd8f2d95eba`, merge `fc3dcd948a2410887c23ff8c29913c6c5d92604a`; CI 10/10, Security 3/3.
- PR #255: invite redemption UID pseudonymization; exact head `af8f676c6bdbeb125eb46c01e4aa2b56af30c4f0`, merge `89ac44cf85df1c90530196fdbb01bf93ff2e8994`; CI 10/10, Security 3/3.
- PR #256: range-aware per-holder Worker accounting for valid single-range GET; merge `98f76cdf374eeaf5c8144befad068ef05805a20b`; the latest main CI/security runs above passed.
- PR #257: documentation-only S05-H2 privacy closeout; merged `7e42005e294a45db4e6ca06c7e4bb100a4a4be72`.

The canonical tracker now restores the previously omitted Session 06 findings S06-H4 and S06-H5. Current reconciled count: **116 findings — 86 Fixed, 17 Partial, 1 Open, 12 Accepted**. S06-H4 is Fixed on current source; S06-H5 remains Partial because the Settings-based candidate oracle remains distinguishable.

Do not copy historical workflow totals or treat PR-head checks from outdated merge bases as current-main verification. Always use the exact SHA and current check runs for each new PR.

## 3. Remaining agentic/operator work — no physical-device acceptance yet

### A. S10-N2 — release-log guard and bounded interprocedural taint analysis (code merged)

PR #244 introduced the JDK compiler-AST release-log guard; PR #246 hardened identifier and tag/message coverage; PR #248 added method-local alias/container tracking; PR #251 added the bounded helper-summary extension and reduced `DeviceSecurityGate` log detail. PR #251 is merged to main at `fc3dcd948a2410887c23ff8c29913c6c5d92604a`.

The merged analyzer now:
- Tracks local aliases, sticky reassignment, concatenation, StringBuilder/StringBuffer flows and common Map/Bundle/JSON/container insertion and retrieval.
- Uses bounded fixed-point summaries for uniquely resolvable app-owned helper return values, parameter pass-through and same-class field writes/reads, including helper-mediated flows.
- Accepts explicit redaction only when source analysis proves the return is redacted.
- Treats ambiguous overloads and unresolved/external dispatch conservatively; recursive summaries are iterative and fail closed if not converged.
- Checks both `Log.w/e/wtf` tag and message arguments.

**Executed evidence on exact PR #251 head `d6041db2033b1950cdf006b900ef5cd8f2d95eba`:**
- CI [42363163588](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42363163588): **10/10 jobs passed**.
- Security Scan [42363163447](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42363163447): **3/3 jobs passed**.
- AST scan: **204 production Java files; 414 Log.w/Log.e/Log.wtf calls; zero violations**.
- Espresso: **4 tests executed**; API 35 predictive-back: **1 focused test executed**.
- Android unit tests, lint, debug APK, release R8/mapping, Server/Worker tests, CodeQL, Gitleaks, SBOM, dependency-integrity and Cloudflare Worker build validation passed.

**Remaining:** retain S10-N2 as **Partial**. The analyzer is syntax-only, not symbol-resolved whole-program taint analysis; inheritance/virtual dispatch, reflection, cross-class field aliasing, native methods, unresolved external calls and third-party callback internals remain outside proven coverage. Complete residual source review and physical-device release-logcat QA only after the owner declares the app feature-complete. Do not use real user seeds, tokens or credentials. Keep `security-remediation/FINAL_SIGNOFF.md` **PENDING**.

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

### I. S05-H2 — invite-only backend contract and redemption privacy (core code merged)

PRs #226/#227 provide the opaque invite lifecycle, production-shared HTTP handlers and real Firestore transaction coverage. PR #255 (merged at `89ac44cf85df1c90530196fdbb01bf93ff2e8994`) corrected the redemption-linkage privacy gap: new invites persist only `usedByUserIdTag`; list responses mask legacy `usedByUserId` values; the admin UI renders only the pseudonymous tag. Exact PR #255 head passed CI [42385827187](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827187) 10/10 and Security [42385827198](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827198) 3/3, including Firestore invite/mintToken integrations, Server/Worker tests, Android unit/lint/build/R8, Espresso (4) and API 35 predictive-back (1).

A dry-run-first cleanup script at `server/scripts/pseudonymizeInviteRedemptionUids.js` is available for historical invite documents. It has not been run against any live project. An authorized operator must run `--dry-run` in the intended staging project, review aggregate counts, obtain approval before `--apply`, then repeat dry-run and require zero candidates.

**Subsequent lifecycle hardening:** PR #253 adds explicit `status === "active"` and finite future-expiry requirements, plus an invalid-state admin filter/revocation path and regression coverage. It is open at head `231af6f3a499b60286d567a6628876abfb5d6d31`, currently dirty/non-mergeable against its older base, despite its original CI 10/10 and Security 3/3. Rebase and rerun before merging. PR #258 fixes escaped service-account PEM newlines in the dry-run migration utility; it remains open because Gitleaks flags the synthetic PEM-shaped test literal and one Firestore integration expiry test fails. Do not execute the migration until its code PR is validated and an authorized operator approves a staging dry-run.

**Remaining:** product owner confirmation that invite-only onboarding is intended; authorized staging create/list/search/paginate/revoke/expiry/validation and one-time redemption; synthetic Android new-user onboarding and existing-account restore after feature completion. Keep S05-H2 Partial until those gates pass.


### J. S06-H4 / S06-H5 — restore missing Session 06 findings to the canonical register

The 2026-11-07 tracker reconciliation restored two high-severity IDs that were present in `audit/SESSION-06-DURESS.md` but absent from the row-level BUG_TRACKER:
- **S06-H4 — Fixed:** `ManageUnlockCodesActivity.onConfirmExplain()` checks the boolean from `DuressManager.setDuressPin()`; failure now produces a generic retry message and does not finish the flow or claim success. `setDuressPin()` validates the common 4–6 digit supported range. The main Android CI is green. A focused UI regression for false/true outcomes is still a worthwhile coverage improvement.
- **S06-H5 — Partial:** wrong-PIN attempts now have persisted exponential backoff, and primary-PIN changes reject an exact duress match or duress prefix. However, `SecurityPrivacySettingsActivity.doSavePin()` still distinguishes a collision from a successful change. A person who knows the current primary PIN can repeatedly rotate it through candidate values; successful changes do not consume the wrong-PIN counter. The Settings route remains an observable oracle. A non-oracular change flow plus throttling across successful changes and regression tests are still needed.

The tracker now has 116 enumerated findings and reconciles to 86 Fixed, 17 Partial, 1 Open and 12 Accepted. Do not drop either ID in future summaries.


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