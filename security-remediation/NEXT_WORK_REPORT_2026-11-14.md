# DuoShield — Next Work Report
**Date:** 2026-11-14  
**Main baseline:** `cf5b496e406c0d27bfd2408ec160661dbf7885cd` (PR #301, documentation-only)  
**Current engineering branch:** `security/primary-pin-durable-storage-2026-11-14-v2`

## Completed in this pass

- Re-reviewed account PIN, device-gate PIN, device-to-account promotion, and callers in setup, account creation, restoration and duress logout.
- Added a durable PIN record writer requiring a durable `SecurePrefs.SecurityTier`, synchronous `commit()`, exact read-back and successful removal of legacy/length metadata.
- Made existing account-PIN changes preflight both account and device stores, derive separately salted hashes, write the device gate then the account hash, and attempt to restore previous values if either write fails.
- Changed device PIN setup and device-to-account promotion to report persistence success explicitly. Legacy migration now copies and verifies the destination before cleaning up the source.
- Moved device PIN derivation/commit off the setup UI thread; failure prevents progression to Welcome.
- Required duress teardown to have the entered code and a confirmed durable device-gate replacement before any teardown side effect. The lock screen restores input state if teardown cannot start; the promotion runs on the background verification worker.
- Account creation only bypasses explicit PIN setup after confirmed device-to-account promotion.
- Added `PinPersistenceTest` plus `SecurityFindingWiringTest` regressions and recorded S06-H6 in the canonical tracker.

## Verification state

PR #301 was merged to main as `cf5b496e406c0d27bfd2408ec160661dbf7885cd`. Its exact-head CI [43536863859](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43536863859) passed 10/10 and Security [43536864157](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43536864157) passed 3/3. Post-PR #301 main CI [43538418308](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43538418308) and Security [43538418237](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43538418237) were running when this report was written.

The S06-H6 branch's exact-head CI/Security have not yet been run. Do not mark S06-H6 Fixed until the complete exact-head suite and separate residual/device acceptance have been evaluated. The test suite's presence is not a test result.

## Transaction boundary and acceptance limits

The account and device gate PINs live in different SecurePrefs files. The implementation checks both durability tiers before mutating either store, writes in a defined order, and attempts compensating rollback, but those operations cannot constitute one atomic cross-file transaction. Failure at the boundary may leave a store whose rollback cannot be persisted; the code logs that state instead of claiming success.

Still required:
1. Exact-head Android CI (AST, unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified R8 and server/Worker checks) and Security scan (CodeQL, Gitleaks, SBOM).
2. Independent review of the ordered cross-store PIN update and failure recovery.
3. Physical-device testing with real Android Keystore/EncryptedSharedPreferences: initial device-PIN setup; process death during a PIN change; storage failure; account creation and restore promotion; duress promotion and post-wipe unlock; recovery from failed promotion.
4. Existing external release gates: SC-12 branch protection, provider/runtime verification, credential rotation, staging evidence and release sign-off.

S06-H6 remains **Partial** and `security-remediation/FINAL_SIGNOFF.md` remains **PENDING**.
