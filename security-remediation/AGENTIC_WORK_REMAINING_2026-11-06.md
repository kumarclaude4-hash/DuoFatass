# Agentic Work Remaining — current handoff, 2026-11-08

Repository: `kumarclaude4-hash/DuoFatass`
Base branch: `main`
Current main HEAD: `32175ccf86342a6c517459a0e2507f14c4d064e8`.

## Current closeout

All source-level PRs identified in the 2026-11-07 handoff are now integrated. There are **no open PRs**:
- PR #260 — Firebase credential newline normalization for the legacy invite UID cleanup utility.
- PR #261 — fail-closed invite lifecycle state validation and audited invalid-state revocation.
- PR #263 — S06-H4 regression for failed duress-code arming.
- PR #264 — admin UI Revoke action for invalid invite records.
- PR #265 — S06-H5 durable PIN-change attempt throttle and generic failure behavior.
- PR #256 had already superseded the older PR #254 range-read allowance.
- PRs #253/#258 were closed as superseded by #261/#260.

PR #265 exact head `ed8e47550763cf7766acb0f6ef3d53dc15039df3` passed CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048), including Android unit/lint, Espresso, API 35 predictive-back, debug APK, minified R8/mapping, Server/Worker, CodeQL, Gitleaks, SBOM, AST privacy and dependency integrity. It merged as `32175ccf86342a6c517459a0e2507f14c4d064e8`. Post-merge main workflows [42440446625](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440446625) (CI) and [42440446649](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440446649) were queued at the time this handoff was refreshed; recheck them before release.

The canonical tracker remains **118 register rows: 87 Fixed, 18 Partial, 1 Open, 12 Accepted**. S06-H4 remains Fixed; S06-H5 remains Partial because rate limiting and a generic failure response reduce but do not prove elimination of every success/failure observation. S05-H2 remains Partial pending owner and staging acceptance. `security-remediation/FINAL_SIGNOFF.md` remains **PENDING**.

The numbered Round 3 schedule is complete at S3-20. Do not invent S3-21 or reopen a fourth audit round. The physical-device matrix remains deferred until the owner declares the app feature-complete.

## Current next work

There is no unmerged source-code PR waiting for agent implementation. Remaining work is separated into:
1. **Owner decisions:** S03-M3 media-token reuse/TTL policy; S05-H2 invite-only product contract; S07-L2 native-key erasure residual.
2. **Authorized staging/operator acceptance:** S05-H2 legacy UID cleanup and invite lifecycle; S05-M3 admin-session lifecycle and Firestore rules/index/TTL; S06-L4 wipe/recovery; S10-N1 App Check; S10-N3 isolated Worker/R2/B2 scheduled migration; S02-I2 Render/Worker secret pairing and Durable Object proof; S02-I3 Firebase revoked-token checks across all 12 protected routes.
3. **Repository governance:** SC-12 requires effective review/check enforcement, protected version tags and production-release environment read-back. Current ruleset has zero required approving reviews and no required status checks; keep SC-12 Open.
4. **Feature-complete physical-device acceptance:** keep deferred per owner instruction; automated emulator CI remains required and has passed the current source batch.

Follow `security-remediation/NEXT_WORK_REPORT_2026-11-07.md`, `COMPREHENSIVE_REMAINING_SECURITY_REVIEW_2026-11-07.md` and `BUG_TRACKER.md` as current. The dated sections lower in this file are retained as historical investigation/runbook evidence; do not follow stale PR lists or old baseline SHAs from them.

---

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

## 2. Verified baseline — 2026-11-08

Current main HEAD: `32175ccf86342a6c517459a0e2507f14c4d064e8`.
PR #265 exact-head CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security Scan [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed all jobs on `ed8e47550763cf7766acb0f6ef3d53dc15039df3`; that head is merged as current main. The CI included Server/Worker tests, Android unit/lint, debug APK, Espresso, API 35 predictive-back, R8/mapping, AST privacy, wrapper/libsignal integrity and Worker build. Security included CodeQL, Gitleaks and SBOM.
Post-merge main workflows 42440446625 (CI) and 42440446649 (Security Scan) were queued at the time of this snapshot. Recheck before release.

Recent merged PR evidence:
- #251 — bounded interprocedural S10-N2 analyzer, merge `fc3dcd948a2410887c23ff8c29913c6c5d92604a`.
- #255 — invite redemption UID pseudonymization, merge `89ac44cf85df1c90530196fdbb01bf93ff2e8994`.
- #256 — range-aware per-holder Worker accounting for valid single-range GET.
- #257 — S05-H2 privacy documentation.
- #260 — Firebase credential newline normalization; CI/Firestore/Security all passed.
- #261 — fail-closed invite lifecycle and invalid-state audited revoke; CI/Firestore/Security all passed.
- #263 — S06-H4 save-failure wiring regression; CI/Security passed.
- #264 — invalid-invite admin revoke UI action; CI/Firestore/Security passed.
- #265 — S06-H5 PIN-change attempt throttle; exact-head CI/Security passed.

The canonical tracker remains 118 register rows: 87 Fixed, 18 Partial, 1 Open, 12 Accepted. Do not infer staging, provider-console, credential rotation, governance or physical-device sign-off from PR merges or automated CI.

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

PR #255 pseudonymizes new redemption linkage and masks legacy raw UID values in admin projections. PR #260 fixes escaped Firebase service-account private-key newline normalization in the dry-run-first legacy UID cleanup utility; synthetic fixtures avoid PEM-shaped false positives, and the Firestore test now uses a safe expiry margin. PR #261 requires explicit active state and valid finite future expiry; missing, unknown or malformed invite records are invalid, cannot redeem, can be filtered and can be revoked through the existing audited transaction path. PR #264 adds the Revoke action to invalid rows in the admin UI and uses generic confirmation wording.

The old PR #253 and #258 branches are closed as superseded by #261 and #260. All exact-head CI/Security/Firestore checks for #260/#261/#264 passed.

**Remaining:** owner confirmation that invite-only onboarding is the intended product contract; authorized staging review of aggregate UID cleanup dry-run counts; explicit approval before any staging --apply; repeat dry-run with zero candidates; complete synthetic invite lifecycle testing and Android new-user onboarding/existing-account restore after feature completion. No live Firestore UID migration has been run. Keep S05-H2 Partial.

### J. S06-H4 / S06-H5 — duress PIN arming and Settings candidate probing

- **S06-H4 — Fixed:** `ManageUnlockCodesActivity.onConfirmExplain()` checks the boolean result of `DuressManager.setDuressPin()`; a failed secure write produces a generic retry message and returns before the success toast or finish. Only success produces “Code saved.” and exits. PR #263 adds a source-wiring regression for the false path; exact-head Android CI and Security passed.
- **S06-H5 — Partial, mitigation merged in PR #265:** the Settings PIN-change flow records a persisted UID-scoped attempt before checking or writing the proposed PIN. Three attempts are permitted per fixed 15-minute window; the third starts a 15-minute cooldown. Successful and rejected candidates consume the same allowance. The throttle uses Android `Settings.Global.BOOT_COUNT`, same-boot elapsed-time anchors, an elapsed-reset fallback when BOOT_COUNT is unavailable, and fails closed on preference read/write errors. Collision, cooldown and storage failures share one generic response; the collision-specific message is removed. PR #265 merged at `32175ccf86342a6c517459a0e2507f14c4d064e8`; exact-head CI and Security passed (runs 42440025067 and 42440025048).

Keep S06-H5 Partial: the throttle bounds candidate probing and hides the collision-specific message but does not prove every success/failure observation indistinguishable; it is local protection rather than server-side throttling. Independent security review and later feature-complete physical-device acceptance remain.

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