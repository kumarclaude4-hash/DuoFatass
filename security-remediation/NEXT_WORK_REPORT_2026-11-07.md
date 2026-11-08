# DuoShield — Next Work Report
**Updated:** 2026-11-07  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Current main:** `98f76cdf374eeaf5c8144befad068ef05805a20b`

## Current verified mainline

Main CI [42387261365](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261365) and Security Scan [42387261135](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261135) both completed successfully on the exact current main SHA.

The current main CI check set passed Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified R8, Server/Worker tests, Android release-log AST, Cloudflare Worker build validation, Gradle-wrapper integrity and vendored-libsignal integrity. Security checks passed CodeQL, Gitleaks and SBOM.

Recent merged changes:
- PR #251: bounded interprocedural release-log taint analysis; S10-N2 remains Partial for syntax-only analysis limits and physical release-logcat acceptance.
- PR #255: new invite redemptions store a 12-hex pseudonymous UID tag; admin listing masks legacy raw UID; a dry-run-first cleanup script exists.
- PR #256: valid single-range Worker GETs cost half a request unit in the shared per-holder minute bucket, allowing chunked encrypted-video playback without adding a second quota bucket.
- PR #257: S05-H2 privacy closeout documentation.

The canonical finding register is `BUG_TRACKER.md`: **116 findings — 86 Fixed, 17 Partial, 1 Open, 12 Accepted**. S06-H4 and S06-H5 are restored as high-severity rows; H4 is Fixed on current source and H5 remains Partial because the Settings-based candidate oracle is not fully closed.

## Priority 1 — get the three open code PRs current and green

| PR | Scope | Current evidence | Required next action |
|---|---|---|---|
| [#253](https://github.com/kumarclaude4-hash/DuoFatass/pull/253) | Reject missing/unknown/corrupt invite lifecycle states; add invalid-state admin filter and audited revoke | Exact head `231af6f3a499b60286d567a6628876abfb5d6d31`; CI 10/10, Security 3/3 and Firestore Rules/server transaction suite passed | The branch is based on `3d41501` and GitHub reports it **dirty/non-mergeable** against current main. Rebase/resolve current changes and rerun all checks. |
| [#254](https://github.com/kumarclaude4-hash/DuoFatass/pull/254) | Range-read request allowance for 1 MiB encrypted video chunks | Exact head `1d5583584572859bcb07b39bba0628c554226c84`; CI 10/10 and Security 3/3 passed | The branch is also based on `3d41501` and **dirty/non-mergeable**. Rebase onto current main (including PR #255 and #256) and rerun. |
| [#258](https://github.com/kumarclaude4-hash/DuoFatass/pull/258) | Normalize escaped PEM newlines in the invite UID cleanup utility | Exact head `b20a5bf628c2a58b29b41f5cf71668d88d445b0d`; regular CI passed | Security failed because Gitleaks flagged a synthetic PEM-shaped test fixture in `pseudonymizeInviteRedemptionUids.test.js`. Firestore integration failed one mintToken test because its invite expiry hit the exact one-hour minimum during test execution. Replace the fixture with non-secret placeholder data that does not resemble a private key; make the expiry deterministic with a fixed injected clock or a safe margin. Rerun Security and Firestore integration. |

Workflow evidence for PR #258: CI [42387218211](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387218211), Security [42387218255](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387218255), Firestore Rules/server transactions [42387218230](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387218230).

Do not merge any of these three until the exact updated PR head has fresh green CI/Security/Firestore checks. Do not use production credentials or run the historical UID cleanup as part of PR validation.

## Priority 2 — close the remaining source-level gaps

### S06-H5 — PIN-change duress oracle (Partial; source mitigation prepared in PR #262)
The Settings PIN-change flow now records a durable, UID-scoped attempt before checking the candidate against the hidden secondary code or writing the replacement. The throttle permits three attempts per 15-minute window, counts both successful and rejected candidates, uses wall-clock plus same-boot elapsed-time deadlines, with Android `Settings.Global.BOOT_COUNT` as the stable boot identifier (rather than a wall-clock-derived approximation), and fails closed on preference read/write errors. Collision, cooldown and storage failures share one generic rejection message; the previous collision-specific toast/branch is removed. PR #262 adds JVM coverage for the attempt limit, persistence/expiry, same-boot wall-clock rollback and per-account separation, plus a source-wiring regression. Keep Partial: this materially bounds candidate probing and hides collision-specific failure text, but does not establish that every success/failure observation is impossible. Promote only after exact-head Android CI and independent security review; physical-device acceptance remains deferred until feature-complete.

### S06-H4 — arming failure (Fixed; source-wiring regression added in PR #263)
`ManageUnlockCodesActivity.onConfirmExplain()` captures the result of `DuressManager.setDuressPin()`. On false, the UI reports a generic retry message and returns before the success toast or `finish()`; only a true result produces “Code saved.” and exits. `setDuressPin()` accepts the shared 4–6 digit range. PR #263 adds a JVM source-wiring regression proving the failure branch returns before the success path and contains no finish call. Keep the behavioral disposition Fixed, but require exact-head Android CI to validate the new coverage.

### S10-N2 — release-log data flow (code merged)
PR #251 extends PRs #244/#246/#248 with bounded fixed-point summaries for uniquely resolvable app-owned helper returns, parameter pass-through and same-class field writes/reads. The exact head passed CI 10/10 and Security 3/3; the production AST pass scanned 204 Java files and 414 warning/error/fatal log sinks with zero violations. Residuals remain for reflection, inheritance/virtual dispatch, cross-class aliasing, native methods and third-party callbacks. Physical release-logcat acceptance remains deferred until feature-complete.

## Priority 3 — isolated staging/operator acceptance

| Finding | Required acceptance |
|---|---|
| **S05-H2** | Confirm invite-only product contract; test full create/list/search/paginate/revoke/expiry/validate and one-time redemption with synthetic identities; Android new-account invite onboarding and existing-account restore after feature completion. Historical UID cleanup: authorized staging dry-run, review aggregate counts, approved apply only, then repeat dry-run and require zero candidates. |
| **S05-M3** | Multi-instance admin-session create/validate/revoke/global revoke/restart/outage/expiry in staging; read back deployed Firestore rules, indexes and TTL. Synthetic sessions only. |
| **S06-L4** | Staging lost-response/admin-unfreeze replay, retry exhaustion, permanent invalid nonce and Firestore config read-back; device wipe/reboot/sign-in/network recovery after feature-complete. |
| **S10-N1** | App Check service-level monitoring metrics, compatible-client adoption and Firebase Console enforcement read-back; do not add `request.app` or App Check helper expressions to Firestore Rules. Validate real-token acceptance/rejection only in isolated staging after operator-approved enforcement. |
| **S10-N3** | Isolated Cloudflare Worker + R2 + B2 scheduled migration, cron retry and interleaved delete/ETag/HEAD/B2 failure acceptance. Never use production buckets/media. |
| **S02-I2** | Authorized read-back that Render `ADMIN_LOCKOUT_WORKER_URL` / `ADMIN_LOCKOUT_WORKER_SECRET` match the Worker secret and exercise the Durable Object path without exposing values. |
| **S02-I3** | Synthetic Firebase staging token: revoke refresh tokens, prove old token rejected across all 12 protected routes and newly issued token accepted. |
| **SC-12** | Require appropriate approving reviews, required CI/Security checks, protected version tags and production-release environment; read effective rules back after an authorized admin changes them. |

## Priority 4 — upstream/API and owner decisions

- **S03-M3:** owner must ratify the current 10-minute, reusable READ/WRITE, single-use DELETE policy or approve a versioned one-shot design. Do not change v1 semantics or shorten TTL without tracing Android retries, range reads and re-fetches.
- **S07-L2:** investigate a supported deterministic native-key destroy API in a compatible upstream libsignal version. Do not use reflection/unsafe hacks or claim synchronous native erasure. Otherwise obtain explicit owner acceptance of the GC-timed residual.
- **S05-H2 product contract:** owner confirms invite-only onboarding is the intended model.

## Priority 5 — physical-device acceptance after feature completion

Do not start this matrix until the owner explicitly declares the app feature-complete:
- S11-H1 predictive-back edge gestures and no-skip gates across OEM/API versions.
- S11-M1 call minimize/timeout/notification return/authentication and in-call continuation.
- S08-H2 screenshot, screen-recording and recents behavior on fresh install and explicit opt-in.
- S08-L3 4/5/6-digit PIN, debounce, backspace, interrupted entry and duress flows.
- S08-L4 lock cover no-flash/touch blocking/clear on unlock, duress, no-PIN and sign-out.
- S08-H5/S07-M1 real Keystore migration, interrupted upgrade, retry and recovery.
- S06-L4 wipe/reboot/sign-in/network-loss/lost-response behavior.
- S05-H2 complete Android invite onboarding and existing-account restore.
- S10-N2 release/minified APK logcat privacy inspection.

## Sign-off

`security-remediation/FINAL_SIGNOFF.md` remains **PENDING**. No PR merge, green CI run, source review or emulator pass constitutes production deployment, credential rotation, provider-console acceptance or physical-device sign-off. Historical handoff instructions remain useful only where they do not conflict with this current report.
