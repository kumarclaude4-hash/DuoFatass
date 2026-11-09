# S06-H5 — Throttle all primary-PIN collision-check entry points

**Date:** 2026-11-09  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Branch:** `security/s06-h5-throttle-all-primary-pin-collision-paths-2026-11-09`  
**Finding:** S06-H5 — Primary-PIN candidate probing through hidden secondary-code collision checks  
**Status:** Partial; exact-head CI/Security passed, independent residual acceptance outstanding

## Source review

PR #265 added a durable UID-scoped candidate throttle to `SecurityPrivacySettingsActivity.doSavePin()`.
A fresh source review found two additional entry points that compared a proposed primary PIN against
the preserved slot-B secondary-code hash without consuming that shared budget:

- `SetupPinActivity`, which can be reached after restore when the old secondary-code hash survived
  the duress wipe.
- `ForcedPrimaryPinRotationActivity`, reached after account unfreeze to set a new primary PIN while
  the old secondary-code hash remains armed until screen 2 replaces it.

Both paths previously emitted a collision-specific rejection message distinct from storage failure,
providing an additional outcome signal and allowing repeated candidate checks without the Settings
throttle.

## Implementation

- Both entry points now call `PinManager.tryRecordPinChangeAttempt(this)` before inspecting the
  preserved secondary-code hash and before writing the candidate PIN.
- They reuse the same account-scoped three-attempt/15-minute fixed-window budget and cooldown that
  Settings already uses.
- The budget is consumed in both paths regardless of whether slot B is armed, preventing the
  presence/absence of a hidden secondary code from being inferred from whether cooldown applies.
- Collision, cooldown, missing auth context and storage failure all produce the same generic
  `Couldn't save your PIN. Try again later.` response. The UI no longer branches on the hidden-code
  collision result or emits the old collision-specific string.
- Existing success paths remain unchanged: setup only clears its pending marker and routes after a
  successful stored PIN; forced rotation only marks primary-rotation completion and advances to
  screen 2 after a successful stored PIN.
- Added two regressions to `SecurityFindingWiringTest` asserting throttle-before-secondary-check,
  writes only after allowance/non-collision, and no collision-specific branch or message in either
  entry point.
- Existing `PinChangeThrottleTest` continues to cover three-attempt limits, cooldown persistence,
  fixed-window expiry, wall-clock rollback/forward handling, reboot handling and account separation.

## Documentation updated in this PR

- `BUG_TRACKER.md` — S06-H5 now records all three entry points and the remaining residual.
- `security-remediation/START_HERE.md`
- `security-remediation/NEXT_WORK_REPORT_2026-11-08.md`
- `security-remediation/FINAL_SIGNOFF.md`
- `security-remediation/INDEPENDENT_REVERIFICATION_2026-11-08.md`
- `security-remediation/AGENTIC_WORK_REMAINING_2026-11-06.md`
- `security-remediation/SESSION_INDEX.md`
- This session record.

## Verification

Exact-head CI [42612569269](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42612569269)
passed 10/10 and Security [42612569340](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42612569340)
passed 3/3. Android unit tests and lint passed; debug APK and minified R8 passed (453,247-line
mapping); Espresso executed 4 tests and API 35 predictive-back executed 1 test. Server + Worker,
AST, CodeQL, Gitleaks, SBOM and dependency integrity gates passed. Physical-device acceptance remains
deferred until the owner declares the app feature-complete.

## Residual / disposition

S06-H5 remains Partial. The shared budget closes the code-level bypass in the two additional
primary-PIN entry points but does not prove every success/failure side channel impossible. Keep the
finding Partial pending independent residual review and feature-complete device acceptance.
`security-remediation/FINAL_SIGNOFF.md` remains PENDING.