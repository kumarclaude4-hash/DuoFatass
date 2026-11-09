## 2026-11-09 addendum — PR #288 merge and PR #289 S10-N2 callback follow-up

PR #288 is the latest source-changing merge on main: `78a99b478e90ec3337df3c92997de3ac16efbe5e`.
It makes the authenticated per-user/endpoint Durable Object limiter fail closed outside explicit
development/test. Missing or unavailable shared limiting returns HTTP 503 on all 12 protected
authenticated routes rather than consuming process-local counters; actual quota breaches remain
HTTP 429. Exact-head CI [42583301825](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301825)
passed 10/10, Security [42583301910](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301910)
passed 3/3, and Firestore Rules/server transactions [42583301878](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301878)
passed. S02-I2 remains Partial only for authorized Render/Worker secret-pair read-back and production
runtime confirmation; this PR did not access live secrets or deploy services.

PR #289 is the active S10-N2 method-reference callback follow-up. It extends the bounded AST scanner
to recognize same-owner callback registrations using `this::method`, `Owner::method`, and
`Owner.this::method`, then treats the registered method's callback-supplied parameters as tainted.
The synthetic regression proves a non-sensitive `callbackArg` passed through
`CallbackApi.register(this::callbackLog)` is rejected at `Log.w`. Exact-head CI/Security/Firestore
results are pending. This is a targeted source-level improvement only: other method-reference
receiver/target forms, third-party callback internals, native/dynamic behavior, arbitrary generic
semantics, and whole-program symbol resolution remain limitations. Physical release/minified APK
logcat acceptance remains deferred until feature completion. Keep S10-N2 Partial.

Documentation for PR #289 is updated in the same PR: BUG_TRACKER, START_HERE, NEXT_WORK_REPORT,
FINAL_SIGNOFF, this independent re-verification, agent handoff, SESSION_INDEX and a new session record.

---
## 2026-11-09 addendum — latest S10-N2 source merge PR #286

PR #286 is the latest source-changing merge on main (26daea8e8b2e45764a68000d02a1f93a486d2fde,
tree 7bb1d14010ecc7702ac3323fab53646c9da06d57). It extends bounded AST release-log analysis
to fail closed on unresolved generic returns/chained receivers, recognized Java reflection and
class-loading APIs, and opaque callback-lambda parameters. Exact-head CI
[42575704977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704977) passed
10/10 and Security [42575704959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704959)
passed 3/3. The AST scanned 205 production Java files / 414 warning/error log sinks with zero
violations; Espresso 4, API 35 predictive-back 1, debug APK, minified R8, Server + Worker and
security jobs passed. PR #287 is a documentation-only close-out merged as
690d0ef5043c2ee585abeff72c3e1ce0be66a336.

S10-N2 remains Partial: this bounded AST analyzer is not symbol-resolved whole-program proof,
and physical release/minified APK logcat acceptance is still deferred until feature completion.

## 2026-11-09 addendum — merged S02-I2 implementation PR #288

PR #288 (security/s02-i2-production-fail-closed-2026-11-09) changes authenticated per-user
rate limiting so production/unset-NODE_ENV never silently spends a process-local allowance when
the shared Durable Object is missing or unreachable. The store raises a typed 503 error; all 12
protected routes return HTTP 503 with Retry-After: 30 and stop before the normal HTTP 429 quota
branch. Explicit development/test processes retain the bounded local fallback. Regression tests
cover missing DO configuration, Worker outage, and all 12 route-handling paths.

PR #288 merged as 78a99b478e90ec3337df3c92997de3ac16efbe5e. Exact-head CI
[42583301825](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301825) passed
10/10; Security [42583301910](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301910)
passed 3/3; Firestore Rules/server transactions
[42583301878](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301878) passed.
S02-I2 remains Partial until an authorized operator verifies the Render
ADMIN_LOCKOUT_WORKER_URL / ADMIN_LOCKOUT_WORKER_SECRET pairing against Worker
ADMIN_LOCKOUT_SECRET and confirms the production Durable Object path at runtime. PR #288 did not
read or change live credentials, deploy services, or claim production acceptance.

---
# Independent Security Tracker Re-verification — 2026-11-08

## 2026-11-09 addendum — S10-N2 PR #284

**Latest source-changing merge:** PR #284 exact head `23eb17ac71ef6832195d77d6765106ca3bda143c`, merged as `30329d7b56ec0b26fd9b40495cdbb3d3bd999cae`; application-code tree `03e780af884f4d123b06ffc68484970066597fba`. PR #285 is a documentation-only tracker/handoff closeout and does not change this source tree.

PR #284 extends `scripts/AndroidReleaseLogPrivacyPolicy.java` with bounded, fail-closed lookup of inherited app-owned methods and fields; inherited sensitive-field recognition; explicit `super` method resolution; qualified enclosing-instance resolution (`Outer.this`); and preservation of ambiguity for duplicate simple-name receiver types. Regressions cover inherited sensitive helper returns, inherited field types with setter/read flows, inherited direct-field writes, explicit superclass dispatch, duplicate simple-name ambiguity and qualified enclosing receiver dispatch.

**Exact-head verification:** CI [42567271589](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42567271589) passed 10/10; Security [42567271633](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42567271633) passed 3/3. The AST self-test passed and the complete app-source scan inspected 205 Java files / 414 `Log.w/e/wtf` calls with zero violations. Espresso executed 4 tests; API 35 predictive-back executed 1 test; both passed. Debug APK and minified R8 builds passed. Server/Worker tests, unit tests, lint, Gradle-wrapper and vendored-libsignal integrity checks, Gitleaks, CodeQL and SBOM also passed.

**Disposition remains Partial.** The analyzer is bounded syntax/AST analysis, not symbol-resolved whole-program proof. External/library subclass dispatch, arbitrary alias/receiver shapes beyond modeled syntax, reflection, dynamic class loading, native implementations, third-party callback internals, arbitrary generic/container semantics and full symbol/generic type resolution remain residual limitations. Physical release/minified APK logcat acceptance remains deferred until the owner declares the app feature-complete. No production deployment or production security sign-off is claimed; `security-remediation/FINAL_SIGNOFF.md` remains PENDING.

---

**Repository:** `kumarclaude4-hash/DuoFatass`  
**Branch:** `main`  
**Latest audited application-code HEAD/tree:** `665146f8195409274b29b563333de25c591f10a9` / `c33b13b178fb92582834ade431cbe3d666bcb1f0` (PR #281 is the latest source-changing merge)  
**Subsequent documentation sync:** PR #282 merged as `be55818a4b5c6d1c210f736a39fe38bb2b9e5cba`; it does not modify the audited application-code tree.  
**Canonical register:** [`BUG_TRACKER.md`](../BUG_TRACKER.md)

## 1. Repository and PR reconciliation

After PR #282 merged, GitHub returned **zero open pull requests and zero open issues** for this repository (rechecked 2026-11-09). PRs #278-#280 and #282 modified documentation only; PR #281 changed the bounded S10-N2 AST analyzer and remains the latest audited application-code baseline. The latest relevant PR chain is:

| PR | Change | Merge commit | Exact-head CI | Security |
|---|---|---|---|---|
| [#275](https://github.com/kumarclaude4-hash/DuoFatass/pull/275) | Security handoff refresh | `b401be50892f2e1adc55235bf6a840daa55669a1` | [42453798811 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453798811) | [42453798810 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453798810) |
| [#276](https://github.com/kumarclaude4-hash/DuoFatass/pull/276) | Fail closed on known app-owned virtual return overrides | `79aacea3a05131f7c5afcc6c64eef182322eda3f` | [42453996138 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996138) | [42453996152 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996152) |
| [#277](https://github.com/kumarclaude4-hash/DuoFatass/pull/277) | Fail closed on ambiguous helper overloads | `b596e965d4be54b4ea8079b4036012e68188f865` | [42454608770 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608770) | [42454608761 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608761) |
| [#278](https://github.com/kumarclaude4-hash/DuoFatass/pull/278) | Independent tracker/handoff reconciliation | `0f5ffe15518db29b4c5895dc42ca600647201f99` | [42457565977 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565977) | [42457565979 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565979) |
| [#279](https://github.com/kumarclaude4-hash/DuoFatass/pull/279) | Final tracker baseline alignment (documentation-only) | `3d47d39faac649e9d5197327a7db5cc145c2c1de` | [42510855878 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42510855878) | [42510855851 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42510855851) |
| [#280](https://github.com/kumarclaude4-hash/DuoFatass/pull/280) | Correct final repository/source-baseline references (documentation-only) | `21c5403799487915aea755365ff6e9bfd73d1cd3` | [42558744976 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42558744976) | [42558744833 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42558744833) |
| [#281](https://github.com/kumarclaude4-hash/DuoFatass/pull/281) | Resolve chained app-owned receiver types in S10-N2 AST analysis | `665146f8195409274b29b563333de25c591f10a9` | [42561844959 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844959) | [42561844900 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844900) |
| [#282](https://github.com/kumarclaude4-hash/DuoFatass/pull/282) | Reconcile tracker/handoff with PR #281 and distinguish source/docs baselines | `be55818a4b5c6d1c210f736a39fe38bb2b9e5cba` | [42562839236 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42562839236) | [42562839196 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42562839196) |

For each listed PR-head CI workflow, all ten configured jobs completed successfully: Server + Worker tests, vendored libsignal verification, Android release-log AST, Gradle wrapper validation, lint, Android unit tests, debug APK, Espresso, API 35 predictive-back, and minified R8 release build. Each Security workflow completed all three jobs: Gitleaks, CodeQL and SBOM.

Earlier same-day verification relevant to tracker corrections:
- PR #263 S06-H4 wiring regression: [CI 42439202158 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42439202158), [Security 42439202156 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42439202156).
- PR #264 S05-H2 invalid-invite revoke UI: [CI 42439015994 — 10/10](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42439015994), [Security 42439016010 — 3/3](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42439016010), [Firestore Rules/transactions 42439016021 — passed](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42439016021).

**Historical disposition:** some older PRs were intentionally closed unmerged because they were duplicates or superseded (including #266, per the owner's instruction, and #273 as a duplicate of #272; #262's implementation was carried by #265). “Zero open PRs” does not mean every historical PR was merged; it means no outstanding PR remains to be reviewed or merged.

PR #278's exact-head CI and Security workflows completed successfully (10/10 and 3/3). PR #279's exact-head CI and Security workflows also completed successfully (10/10 and 3/3); its post-merge Vercel status was successful. PR #280's exact-head CI and Security workflows completed successfully (10/10 and 3/3). PR #281's exact-head CI and Security workflows completed successfully (10/10 and 3/3), including the dedicated AST self-test/full source scan, Android lint/unit tests, debug APK, minified R8, Espresso, API 35 predictive-back, Server + Worker tests, Gradle wrapper and libsignal integrity. Its Vercel preview status failed; this is separate from GitHub Actions and no successful preview deployment is claimed. PR #282's exact-head CI and Security workflows also completed successfully (10/10 and 3/3), including the same Android build/emulator gates and all Server/Worker/security jobs. PR #282 is documentation-only and does not change the audited application-code tree.

## 2. Current finding inventory

The severity tables in the canonical tracker retain **116 unique findings**:

| Severity | Findings |
|---|---:|
| Critical | 4 |
| High | 31 |
| Medium | 27 |
| Low | 31 |
| Informational | 23 |
| **Total** | **116** |

| Disposition | Count |
|---|---:|
| Fixed | 86 |
| Partial | 17 |
| Open | 1 |
| Accepted | 12 |
| **Total** | **116** |

The counts were not increased or decreased merely because more PRs merged. These are unique security findings, not a PR/commit counter. In particular, PRs #269, #274, #276, #277 and #281 all improve different parts of the same S10-N2 release-log privacy finding. S10-N2 therefore remains one Partial finding while its known false-negative surface narrows.

## 3. Fresh source re-reads and corrected tracker evidence

| Finding | Fresh current-main evidence | Disposition |
|---|---|---|
| **S06-H2** | Re-read `FcmUnregisterWorker.enqueue(Context)`: it schedules jittered token deletion without UID, duress marker, custom input data or UID tag. The same job runs on ordinary and duress sign-out. Re-read `AccountLockWorker`: it carries the short-lived lock nonce, not a UID/duress marker. | Fixed |
| **S06-H3** | Re-read `BaseActivity.onStart()`, `DuressManager.maintainLockCredential()`, `performLogout()`, `PendingLockStore` and `AccountLockWorkerFailureTest`. Online unlocked foreground maintenance refreshes a one-hour UID-bound nonce; duress writes a durable same-UID lock intent before wipe; an unconfirmed remote lock leaves the intent for later retry. | Fixed, with the explicit connectivity/re-authentication limit retained |
| **S06-H4** | Re-read setter and UI result handling. PR #263 adds `SecurityFindingWiringTest.duressCodeSaveReportsSuccessOnlyAfterSecureWrite`; exact-head CI and Security passed. | Fixed |
| **S06-I2** | Re-read the live account-lock callback: `ok[0] = task.isSuccessful()`; `lockConfirmed = done[0] && ok[0]`; clear the durable lock intent only when confirmation is true. Timeout/failure falls through to nonce retry. No dedicated mocked-Task regression for this exact callback branch was found, so this is recorded as source-verified rather than as a separately unit-tested behavior. | Fixed |
| **S05-H2** | Re-read invite store/routes and emulator test paths. PR #261 adds explicit active-state/future-expiry fail-closed checks; PR #264 adds the UI Revoke action for invalid rows. Both exact-head checks and Firestore Rules/transaction tests passed. | Partial; product-contract confirmation, legacy UID cleanup and staging/Android onboarding acceptance remain |
| **S08-L2** | Re-read `ClipboardHelper` and `ClipboardSensitiveWiringTest`. The helper marks clips before publication; the regression covers message/contact/media and the upload-diagnostic copy path. The only unmarked write clears an empty value. PR #277's Android unit-test job passed. | Fixed; Android 13+ system-preview behavior remains physical-device release QA |
| **S10-N2** | Re-read `scripts/AndroidReleaseLogPrivacyPolicy.java` and its self-tests. PR #276 adds known app-owned virtual-override handling; PR #277 adds ambiguous same-name/same-arity app-owned overload handling; PR #281 resolves bounded chained receiver types through local variables, declared fields, casts/parentheses and uniquely summarized helper returns. A new fixture stores sensitive data via `wrapper.getChild().setMemo(profile.getUserId())`, reads it back through `wrapper.getChild().getMemo()`, and asserts the value is rejected at a `Log.w` sink. Exact-head PR #281 CI passed 10/10, including the AST job and app-source scan. | Partial; the analyzer remains bounded syntax-level rather than whole-program symbol-resolved analysis; external/library dispatch, reflection, dynamic loading, native and third-party callback paths, and physical release/minified APK logcat acceptance remain |

### S10-N2 residual boundary after PR #281

The analyzer now fails closed for:
1. Existing method-local aliases/containers and bounded app-owned helper summaries (earlier work).
2. Bodyless app-owned abstract/interface/native helper declarations (#269).
3. Typed cross-class field setter-to-read flow (#274).
4. Known indexed app-owned subtype overrides of a base/interface method (#276).
5. Ambiguous same-name/same-arity app-owned helper returns (#277).
6. Chained app-owned receiver expressions resolved through locals, declared fields, casts/parentheses and uniquely summarized helper return types, including a sensitive field setter/read sequence (#281).

It is **not** a symbol-resolved whole-program taint engine. External/library subclass dispatch, dynamic class loading, reflection, native implementations, third-party callback internals, arbitrary container/collection semantics and generic/qualified type resolution remain residual risks. Physical release/minified APK logcat review remains deferred until the owner declares the app feature-complete.

## 4. Live governance read-back — SC-12 remains Open

The live GitHub API returned active default-branch ruleset **`Hello` (ID `26629873`)**. It targets `~DEFAULT_BRANCH`, requires a pull request, and blocks deletion and non-fast-forward updates. It does **not** require approvals: `required_approving_review_count = 0`, no required reviewers, no CODEOWNERS approval, no last-push approval, no required review-thread resolution, and no required-status-check list. Thus the ruleset does not enforce human review or green CI before merge. Classic branch protection details and a protected `production-release` environment were not independently verified.

**SC-12 stays Open.** An authorized repository administrator must configure and read back required CI/Security checks, an approval minimum and suitable CODEOWNERS/last-push policy, immutable `v*` tag protection, and release-environment protection.

## 5. Gates not closed by GitHub PR merges

The following remain external, owner-controlled or feature-complete acceptance work; none is inferred complete from green CI:
- Production credential/key rotation and provider-console read-back (including the leaked GCP service-account and B2/Worker credentials called out in the sign-off runbook).
- Firebase App Check monitoring, service-level enforcement and isolated real-token staging acceptance (S10-N1).
- Render/Worker Durable Object secret pairing and live synthetic Durable Object path test (S02-I2).
- Firebase ID-token revocation integration against an isolated staging project (S02-I3).
- Cloudflare Worker + R2 + B2 scheduled migration/retry acceptance (S10-N3).
- Invite legacy UID migration dry-run/review, owner decision on invite-only onboarding and full synthetic staging lifecycle (S05-H2).
- Durable admin-session deployment/configuration and restart/outage staging acceptance (S05-M3).
- AccountLockWorker lost-response/unfreeze race and deployed Firestore config acceptance (S06-L4).
- Physical-device acceptance matrix, explicitly deferred until the owner declares the app feature-complete.
- Production sign-off remains **PENDING** in `security-remediation/FINAL_SIGNOFF.md`.

## 6. Verification scope and limitations

This pass independently reconciled the live PR/issue inventory, current main SHA/tree, the full set of finding IDs/disposition counts, live default-branch ruleset state, the current source paths listed in section 3, and exact-head Actions results for the PRs listed above. It also corrected stale tracker/handoff evidence where the current code or CI was independently checked.

This is **not** a claim that all 116 original exploit scenarios were re-executed end-to-end in production or that every historical source line and provider setting was re-audited during this one pass. For findings not listed in section 3, the canonical tracker retains its previously recorded source/test evidence and residuals; a future finding-specific pass should re-run its dedicated evidence before promoting or changing disposition. No production secrets were retrieved, no live production configuration was changed, no physical-device test was performed, and no production sign-off is claimed.

**Bottom line (2026-11-09):** PR #281 is the latest source-changing merge; its audited application-code baseline is `665146f8195409274b29b563333de25c591f10a9` (tree `c33b13b178fb92582834ade431cbe3d666bcb1f0`). PR #282 subsequently merged the documentation sync at `be55818a4b5c6d1c210f736a39fe38bb2b9e5cba`, and GitHub returned zero open PRs and zero open issues after that merge. Both PRs' exact-head CI/Security workflows are green (10/10 and 3/3 each). The register remains 116 unique findings (86/17/1/12); S10-N2 remains Partial. Historical closed-unmerged duplicates/superseded PRs are distinguished from outstanding PRs.

## Addendum — 2026-11-09 S10-N2 source update (PR #286)

This addendum supersedes the 2026-11-08 source baseline above **for S10-N2 only**. PR #286 added fail-closed taint handling for unresolved app-owned generic return variables and generic receiver chains, recognized Java reflection/class-loading surfaces, and opaque callback lambda parameters with lexical scope restoration. Regression fixtures cover `T getValue()` receiver chaining, `Class.forName` → `getMethod` → `Method.invoke`, callback payload logging, and safe exception-class-only callback diagnostics.

Exact PR head `91751692e7c491a1aba948b2b1da4f2bbada120c` merged as `26daea8e8b2e45764a68000d02a1f93a486d2fde`; resulting app-code tree `7bb1d14010ecc7702ac3323fab53646c9da06d57`. Exact-head CI [42575704977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704977) passed 10/10 and Security [42575704959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704959) passed 3/3. AST self-tests and production scan passed (205 Java files, 414 `Log.w/e/wtf` sinks, zero violations); Espresso 4 tests, API 35 predictive-back 1 test, debug APK, minified R8 and Server + Worker tests passed. R8 mapping verification generated a 453,260-line file.

**Disposition remains Partial.** The analyzer is not compiler-attributed, symbol-resolved whole-program taint analysis. Unrecognized reflection/dynamic paths, external/library dispatch, bytecode proxies, method references and opaque third-party callback internals, native behavior, arbitrary generic/container semantics, and unmodeled aliases/receivers remain residual. Physical release/minified APK logcat acceptance remains deferred until the owner declares the app feature-complete. `FINAL_SIGNOFF.md` remains PENDING.
