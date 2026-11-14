# S06-H6 — Primary and device PIN durability

**Date:** 2026-11-14  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Working branch:** `pin-persistence-fix-2026-11-14`  
**Status at latest documentation checkpoint (2026-11-14):** Implementation and expanded regression coverage are on open PR #303; final exact-head CI/Security are pending. S06-H6 remains **Partial**.

## Finding

A focused source review of the primary-PIN lifecycle found three connected durability gaps:

1. `PinManager.setPin()` wrote the account-scoped primary hash using `SharedPreferences.Editor.apply()` and returned success without checking a durable SecurePrefs tier, commit result or read-back.
2. When the device-gate PIN already existed, `setPin()` called the device PIN writer but ignored its result. That writer returned `void`, used `apply()`, and swallowed failures. The account PIN could therefore be changed while the device PIN stayed stale.
3. `promoteDevicePinToCurrentUser()` similarly copied a device PIN hash into the account store with `apply()` and returned true without durable confirmation. `DevicePinGateActivity` also advanced after device-PIN setup without checking whether the write had succeeded.

These were not the S06-H5 candidate-throttle issue or the S06-H4 secondary/duress-PIN arming issue. They are tracked as a separate supplementary finding, S06-H6. Existing S06-H4 and S06-H5 dispositions were not changed.

## Implementation

### Durable single-store writer

Added `app/src/main/java/com/duoshield/app/util/PinHashPersistence.java`.

- Requires a non-null `SecurePrefs.SecurityTier` whose `isDurable()` is true.
- Uses synchronous `commit()`; does not infer durability from the process-local SharedPreferences map.
- Reads the exact hash back and verifies every requested legacy/metadata removal.
- Rejects malformed salt/hash records (16-byte salt + 32-byte derived key in the established hex format).
- Catches persistence runtime failures and reports false rather than claiming success.

### Recoverable paired account/device update

The account-scoped and device-gate PIN stores are separate EncryptedSharedPreferences containers, so two commits cannot be a single native atomic transaction. For accounts that already have a device-gate PIN, the writer now stages:
- a UID-scoped pending transaction and account hash in the account store; and
- a transaction journal, target account key, account/device hashes and legacy cleanup metadata in the device-gate store.

Both stages must commit and read back before either active credential is changed. Recovery rolls both hashes forward idempotently after an interruption, verifies both active values and metadata cleanup, and removes the account stage before removing the device journal. A one-sided active update with an incomplete journal fails closed; an incomplete stage with neither active value advanced is discarded without changing the old credentials. Each newly derived account/device record receives its own salt.

### PIN flows and migration

- `PinManager.setPin()` captures one Firebase UID for the operation, recovers any pending transaction before mutation, and returns success only after the single/pair writer confirms persistence.
- `setDevicePin()` now returns boolean and uses the durable single-store writer.
- `hasPinSet()` migrates the old global account hash synchronously and scrubs old PIN-length metadata while retaining its read-only legacy fallback if migration cannot be committed.
- `hasDevicePinSet()` migrates the old device hash into the isolated device container only after verifying the destination; it retries cleanup of an old account-store copy before the stores are treated as aligned.
- `verifyPin()` and `verifyDevicePin()` recover pending paired updates before reading credentials and require the relevant store tier to be durable.
- Restore-time promotion re-commits and verifies the device hash before persisting the UID-scoped account copy; it refuses promotion while legacy device keys remain and removes the old global account PIN key.
- PBKDF2 derivation clears the locally controllable password, salt and derived byte-array buffers in `finally` blocks; verification clears decoded salt/expected/actual buffers.

### Device-gate UI

`DevicePinGateActivity` now performs the PIN derivation and persistence on its managed background executor. It disables the setup button while saving, displays an error and stays on setup if persistence fails, and proceeds only after a true result. The verify path also uses the managed executor and guards callbacks after Activity destruction.

## Regression coverage authored

New `app/src/test/java/com/duoshield/app/util/PinHashPersistenceTest.java` covers:
- durable tier enforcement and exact hash/cleanup read-back;
- a commit that updates the process map but reports failure;
- a read-back mismatch;
- independent account/device records and removal of legacy metadata;
- interrupted paired update recovery with cleanup instructions surviving process restart;
- failed device-journal staging leaving both active credentials unchanged;
- refusal of ephemeral tiers and malformed records;
- safe incomplete-stage discard; and
- fail-closed handling for ambiguous one-sided active state.

`SecurityFindingWiringTest.primaryAndDevicePinWritesRequireDurableVerifiedPersistence()` guards PinManager persistence/recovery wiring and the DevicePinGate failure branch. Existing device-gate isolation tests use SecurePrefs test overrides that explicitly model a durable SOFTWARE tier, while direct helper tests exercise the NONE tier.

### Follow-up hardening — 2026-11-14

The source review and initial CI feedback led to additional changes on the same PR branch:

- Orphan account-side stages are removed only when the transaction marker/hash pair is structurally
  valid and the staged value is not already the active account hash. If the active hash matches
  an orphaned staged hash without the device journal, recovery fails closed and preserves evidence.
- Completion cleanup now removes the account stage first and deletes the device-side transaction
  journal only after the account-stage commit succeeds and both keys read back as absent.
- Fault-injection coverage models a failed account-stage cleanup commit after both active credential
  writes; the test requires the device journal to remain and confirms a later recovery retry removes it.
- The device-gate Espresso tests were corrected for the new background executor. They now snapshot
  the captured-intent count and wait for a new SignInActivity hand-off after PIN save or unlock,
  preventing an old intent from satisfying a later step.
- Latest source/test head at this checkpoint: `e292362e0139c1a9291631985dc82ce2c82eb08f`. Current-head CI
  [43549525173](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43549525173) was queued and Security [43549525218](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43549525218) was in progress.
  A documentation synchronization will trigger another exact-head run; no final green result
  is claimed at this checkpoint.

## Verification and residuals

At this checkpoint, no local Gradle/Android toolchain execution is claimed. GitHub CI/Security are the verification path: the previous run passed the unit suite but surfaced stale Espresso intent timing assumptions, now corrected in the instrumented tests. The latest exact-head workflows are still running/queued and must be rechecked after documentation synchronization. Do not merge until all required checks pass and the PR head SHA is re-verified.

Repository CI cannot replace the following acceptance:
- real Android Keystore / EncryptedSharedPreferences durability behavior on supported API levels;
- process-kill/relaunch testing between each account/device journal phase;
- device-gate setup, PIN change, account restore/promotion and unlock flows on physical devices;
- OEM-specific behavior and device acceptance after the app is feature-complete.

S06-H6 remains **Partial** until exact-head CI/Security pass. Device/Keystore acceptance remains separate. `FINAL_SIGNOFF.md` remains **PENDING — NOT APPROVED FOR PRODUCTION SIGN-OFF**. No production secrets, provider configuration, staging resources, deployments or devices were changed.
