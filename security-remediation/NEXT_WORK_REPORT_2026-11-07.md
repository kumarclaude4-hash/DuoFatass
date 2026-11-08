# DuoShield — Next Work Report
**Updated:** 2026-11-08  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Current main:** `32175ccf86342a6c517459a0e2507f14c4d064e8`

## Current verified mainline — 2026-11-08

**Main HEAD:** `32175ccf86342a6c517459a0e2507f14c4d064e8`.

PR #265 merged S06-H5's bounded PIN-change attempt throttle. Its exact PR head `ed8e47550763cf7766acb0f6ef3d53dc15039df3` passed:
- CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067): all 10 jobs passed, including Server/Worker, Android unit tests/lint, Espresso, API 35 predictive-back, debug APK, minified R8/mapping, AST privacy and dependency-integrity checks.
- Security Scan [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048): CodeQL, Gitleaks and SBOM passed.
- Merge commit: `32175ccf86342a6c517459a0e2507f14c4d064e8`.

Post-merge mainline CI [42440446625](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440446625) and Security Scan [42440446649](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440446649) both completed successfully on exact main HEAD `32175ccf86342a6c517459a0e2507f14c4d064e8`. All 10 CI jobs passed, including Server/Worker, Android unit tests/lint, Espresso, API 35 predictive-back, debug APK, minified R8/mapping, AST privacy and dependency integrity. Security checks passed CodeQL, Gitleaks and SBOM.

Recent merged changes now reflected on main:
- PR #251 — bounded interprocedural Android release-log taint analysis; S10-N2 remains Partial for analysis limitations and physical release-logcat acceptance.
- PR #255 — pseudonymous invite redemption UID tags and dry-run-first legacy cleanup utility.
- PR #256 — bounded range-read accounting for chunked encrypted-video playback.
- PR #257 — S05-H2 privacy documentation.
- PR #260 — Firebase service-account private-key newline normalization in the legacy invite UID migration utility; synthetic regression fixture and expiry integration boundary hardened.
- PR #261 — invite lifecycle fails closed on unknown/malformed states; invalid records are filterable and backend-revocable.
- PR #263 — regression guard for failed duress-code save behavior (S06-H4).
- PR #264 — admin UI exposes audited revoke action for invalid invite rows.
- PR #265 — persisted UID-scoped PIN-change attempt throttle, stable boot identity and generic collision/cooldown/storage failure response (S06-H5).

There are **no open pull requests** at this snapshot. The older PRs #253 and #258 were closed as superseded by #261 and #260; #254 was closed as superseded by #256.

The canonical finding register remains `BUG_TRACKER.md`: **116 findings — 86 Fixed, 17 Partial, 1 Open, 12 Accepted**. S06-H4 remains Fixed; S06-H5 remains Partial because bounded attempts and a generic failure response reduce probing but do not prove all success/failure observations indistinguishable. S05-H2 remains Partial pending owner/product and staging acceptance.

## Priority 1 — code PR integration is complete

All current code PRs are merged and the repository has no open PRs. The final code batch:
- #260 — migration credential newline normalization; CI, Firestore integration and Security Scan passed.
- #261 — invite lifecycle invalid-state fail-closed checks; CI, Firestore integration and Security Scan passed.
- #263 — S06-H4 duress-code arming failure regression; all CI and Security jobs passed.
- #264 — invalid-invite admin revoke action; CI, Firestore integration and Security Scan passed.
- #265 — S06-H5 PIN-change attempt throttle; exact-head CI and Security Scan passed as recorded above.

PR #254's range-read objective was already implemented by #256. PRs #253/#258 were superseded by #261/#260. Do not reopen these historical branches. If a new regression is found, start a fresh narrow branch from the latest main.

## Priority 2 — close the remaining source-level gaps

### S06-H5 — PIN-change duress oracle (Partial; mitigation merged in PR #265)
The Settings PIN-change flow records a durable, UID-scoped attempt before checking the proposed PIN against the hidden secondary code or writing the replacement. Three attempts are allowed in a fixed 15-minute window; a third attempt starts a 15-minute cooldown. Accepted and rejected candidates consume the same allowance. The throttle uses Android `Settings.Global.BOOT_COUNT` as its stable boot identifier, same-boot elapsed-time anchors to resist wall-clock changes, and a conservative elapsed-reset fallback when BOOT_COUNT is unavailable. Preference read/write failures fail closed. Collision, cooldown and secure-storage failures share one generic response; the collision-specific message is removed. PR #265 merged at `32175ccf86342a6c517459a0e2507f14c4d064e8`; exact-head CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed. Coverage includes the attempt/window/cooldown boundaries, same-boot clock changes, BOOT_COUNT-unavailable reboot fallback, elapsedRealtime boot-zero and account separation, plus Settings/PinManager wiring. Keep Partial: this bounds probing and removes the collision-specific response but does not prove all success/failure observations are indistinguishable; it also remains a local anti-probing control rather than server-side account throttling. Independent security review and later feature-complete device acceptance remain.

### S06-H4 — arming failure (Fixed; regression merged in PR #263)
`ManageUnlockCodesActivity.onConfirmExplain()` captures the result of `DuressManager.setDuressPin()`. On false, the UI reports a generic retry message and returns before the success toast or `finish()`; only a true result produces “Code saved.” and exits. `setDuressPin()` accepts the shared 4–6 digit range. PR #263's source-wiring regression verifies the false path returns before success/exit. Exact-head CI and Security Scan passed on PR #263. Keep Fixed.

### S10-N2 — release-log data flow (code merged)
PR #251 extends PRs #244/#246/#248 with bounded fixed-point summaries for uniquely resolvable app-owned helper returns, parameter pass-through and same-class field writes/reads. The exact head passed CI 10/10 and Security 3/3; the production AST pass scanned 204 Java files and 414 warning/error/fatal log sinks with zero violations. Residuals remain for reflection, inheritance/virtual dispatch, cross-class aliasing, native methods and third-party callbacks. Physical release-logcat acceptance remains deferred until feature-complete.

## Priority 3 — isolated staging/operator acceptance

| Finding | Required acceptance |
|---|---|
| **S05-H2** | Confirm invite-only product contract; test full create/list/search/paginate/revoke/expiry/validate and one-time redemption with synthetic identities; Android new-account invite onboarding and existing-account restore after feature completion. Historical UID cleanup: authorized staging dry-run, review aggregate counts, approved apply only, then repeat dry-run and require zero candidates. |
| **S05-M3** | Multi-instance admin-session create/validate/revoke/global revoke/restart/outage/expiry in staging; read back deployed Firestore rules, indexes and TTL. Synthetic sessions only. |
| **S06-L4** | Staging lost-response/admin-unfreeze replay, retry exhaustion, permanent invalid nonce and Firestore config read-back; device wipe/reboot/sign-in/network recovery after feature-complete. |
| **S10-N1** | App Check monitoring metrics, staged rules and console read-back; enforce only after compatible client adoption and rollback are proven. |
| **S10-N3** | Isolated Cloudflare Worker + R2 + B2 scheduled migration, cron retry and interleaved delete/ETag/HEAD/B2 failure acceptance. Never use production buckets/media. |
| **S02-I2** | Authorized read-back that Render `ADMIN_LOCKOUT_WORKER_URL` / `ADMIN_LOCKOUT_WORKER_SECRET` match the Worker secret and exercise the Durable Object path without exposing values. |
| **S02-I3** | Synthetic Firebase staging token: revoke refresh tokens, prove old token rejected across all 12 protected routes and newly issued token accepted. |
| **SC-12** | Require appropriate approving reviews, required CI/Security checks, protected version tags and production-release environment; read effective rules back after an authorized admin changes them. |

## Priority 4 — upstream/API and owner decisions

- **S03-M3:** owner must ratify the current 10-minute, reusable READ/WRITE, single-use DELETE policy or approve a versioned one-shot design. Do not change v1 semantics or shorten TTL without tracing Android retries, range reads and re-fetches.
- **S07-L2:** the 2026-11-08 upstream re-check found no supported caller-facing deterministic ECPrivateKey close/destroy API in either pinned libsignal 0.54.1 or the checked current upstream wrapper. Keep Partial; do not use reflection/unsafe hacks or claim synchronous native erasure. Obtain explicit owner acceptance of the GC-timed residual or wait for upstream API support. See `security-remediation/sessions/SESSION-S07-L2-LIBSIGNAL-BOUNDARY-2026-11-06.md`.
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
