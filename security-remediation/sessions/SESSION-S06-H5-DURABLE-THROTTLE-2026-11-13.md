# S06-H5 — Require durable storage for primary-PIN candidate throttling

**Date:** 2026-11-13  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Branch:** `security/s06-h5-throttle-requires-durable-secureprefs-2026-11-13`  
**Finding:** S06-H5 — primary-PIN candidate probing through hidden secondary-code collision checks  
**Disposition:** Partial; this commit adds fail-closed storage-tier enforcement and regression coverage. Independent side-channel review and feature-complete device acceptance remain required.

## Finding reproduced from source

The account PIN candidate throttle stores its count/window/cooldown state in the account-scoped `SecurePrefs` preferences. `SecurePrefs` has a documented `NONE` tier when every Keystore-backed EncryptedSharedPreferences strategy fails. That tier is an in-memory-only fallback and does not survive process death.

`PinChangeThrottle.tryRecord()` previously trusted `SharedPreferences.Editor.commit()` as proof that the attempt budget was persistent. With the `NONE` store, a commit can succeed for the in-memory map even though the entire budget disappears on process death. The caller could therefore obtain a fresh three-attempt budget after restarting the process on a device where secure persistence was unavailable.

The adjacent secondary-code arming path already prevents this condition: `DuressManager.setDuressPin()` refuses to report success unless `SecurePrefs.getTier().isDurable()`. The PIN candidate throttle lacked the equivalent guard.

## Fix

- `PinManager.tryRecordPinChangeAttempt()` now resolves the preference store once and passes the matching `SecurePrefs.SecurityTier` into the pure throttle helper.
- `PinChangeThrottle.tryRecord()` rejects a null or non-durable tier before reading or mutating attempt state. A successful in-memory commit is no longer treated as a durable attempt.
- The existing three-attempt, 15-minute fixed window and 15-minute cooldown are unchanged.
- The existing fail-closed response contract is unchanged: callers receive `false` and use their existing generic failure UI; the hidden secondary-code candidate check and primary-PIN write do not proceed.

## Regression coverage

- `PinChangeThrottleTest.ephemeralSecurePrefsTierCannotConsumeOrGrantAnAttempt` proves the `NONE` tier is rejected without creating an in-memory count. It then proves a subsequent `SOFTWARE`-tier call remains attempt one.
- All existing pure throttle tests now pass an explicit durable `SOFTWARE` tier, retaining coverage for limits, account scoping, fixed-window expiry, clock rollback/forward handling, reboot handling and cooldown.
- `SecurityFindingWiringTest.pinManagerUsesUidScopedDurablePinChangeThrottle` now asserts that the store is resolved first and its current tier is passed to `PinChangeThrottle`.

## Verification and limitations

The source and regression changes are pushed on this branch. The exact-head Android CI and Security workflows are the acceptance gates for the PR; do not promote S06-H5 based on source changes alone.

This patch does **not** prove all success/failure observability impossible, does not redesign the candidate policy, and does not replace the independent side-channel review. Physical-device testing remains deferred until the app is declared feature-complete. Keep S06-H5 **Partial** and keep `security-remediation/FINAL_SIGNOFF.md` **PENDING** until all remaining evidence or owner-approved residual decisions exist.
