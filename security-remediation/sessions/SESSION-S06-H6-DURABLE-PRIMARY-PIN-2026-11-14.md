# S06-H6 — Primary PIN durable persistence
**Date:** 2026-11-14  
**Status:** Partial — implementation prepared; exact-head CI/Security and physical-device acceptance pending  
**Branch:** `security/primary-pin-durable-storage-2026-11-14-v2`

## Finding

The primary account PIN and the device-gate PIN were not consistently treated as durable security state. `PinManager.setPin()`, `setDevicePin()` and `promoteDevicePinToCurrentUser()` used asynchronous `apply()` and could return success without proving that the resolved SecurePrefs tier was durable or that the intended record had been committed. A failed commit can update SharedPreferences' in-process map even while the disk write fails, so checking only a later in-process read is insufficient.

The affected callers amplified the issue:
- `DevicePinGateActivity` proceeded to Welcome without checking whether the device PIN write succeeded.
- `DuressManager.performLogout()` could continue its destructive wipe even if promotion of the entered duress PIN to the wipe-surviving device gate had not been saved.
- `SeedPhraseDisplayActivity` could skip explicit PIN setup after an unsuccessful device-to-account promotion.

## Implementation

- Added a shared `PinManager.persistPinHash()` guard. It rejects null/non-durable storage tiers (including SecurePrefs `NONE`), commits synchronously, checks the exact hash read-back and confirms requested legacy/length keys are absent.
- Added preference snapshots and typed best-effort rollback. An account PIN change that also updates an existing device gate preflights both durable stores, derives independently salted records before writes, persists the device gate first and then the account PIN, and attempts to restore both prior records if either commit fails.
- Changed device PIN writes to return a boolean and require durable commit/read-back. Device-gate and account legacy migrations copy to the destination before cleanup of the source, retaining the source when destination persistence fails.
- Device-PIN setup performs PBKDF2 and the synchronous commit off the main thread and only advances on verified success.
- Duress teardown requires a non-empty entered code and confirmed durable device-gate promotion before any destructive side effect. It reports whether teardown started; the lock-screen flow restores input when promotion is rejected. The teardown starts from the background verification worker.
- Account creation only skips explicit PIN setup after verified device-PIN promotion.

## Regression coverage

- `PinPersistenceTest`: durable device PIN commit/read-back and verification; rejection of `SecurityTier.NONE`; rollback of the current-process view after a failed commit; and the shared helper's commit/removal semantics.
- `SecurityFindingWiringTest`: durable account/device writer wiring, promotion guard, device setup success/failure ordering, duress abort-before-side-effects, lock-screen recovery, onboarding fallback and continued suppression of plaintext PIN-length metadata.

## Transaction boundary and residuals

The account-scoped and device-gate SecurePrefs files are physically separate. Android SharedPreferences does not offer a transaction spanning both files. The implementation preflights both tiers and orders the writes, then attempts to restore the prior records on failure; this is best-effort compensation, **not a mathematically atomic cross-file transaction**. A process death or storage failure at the exact cross-file boundary remains a residual to assess on physical devices.

This session does not establish production sign-off. Exact-head CI/Security, independent review, real Android Keystore/EncryptedSharedPreferences behavior, physical-device setup/restore/duress testing and production release acceptance remain outstanding. Keep S06-H6 **Partial** and `FINAL_SIGNOFF.md` **PENDING** until those gates are separately evidenced.
