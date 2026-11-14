# S06-H6 — Primary and device PIN durability

**Date:** 2026-11-14  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Working branch:** `pin-persistence-fix-2026-11-14`  
**Status (2026-11-14 closeout):** Implementation merged in PR #303 and follow-up PR #304. Exact-head CI/Security and post-merge main CI/Security passed. S06-H6 remains **Partial** only for real Keystore/process-death/physical-device acceptance.

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
- This was an intermediate PR #303 checkpoint. It was superseded by final head
  `7974a197a03a929b336fbd2c0fb64c6a2a8f68fe`, merged as `eb1edcba2efc38481298a3a50e2b5eb789cc2b2a`.
  Exact-head CI [43550121483](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43550121483) passed 10/10
  and Security [43550121365](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43550121365) passed 3/3.

## Follow-up call-site hardening — PR #304

Source review after PR #303 identified two remaining callers of the new synchronous durable persistence helpers on the UI thread:

- `LockScreenActivity` invoked duress recovery teardown from a UI-thread callback.
- `SeedPhraseDisplayActivity` performed restore-time device-PIN state checks and promotion in its main-thread success callback, and did not gate navigation on promotion success.

PR #304 (`fix/security: move durable PIN call sites off main thread`) moved those paths to background execution. It also makes duress teardown fail closed: the recovery PIN must first be durably persisted to the wipe-surviving device gate; if that fails, no wipe, lock marker, navigation or worker side effect starts, and the existing account remains intact for retry. The deprecated no-PIN overload cannot bypass this guard. New source-wiring regressions prove the off-main-thread call ordering and success-only restore navigation.

PR #304 merged as `11bc01c863951152c559a72def3b0faa2a454b63` from head `6f9bf2f14cb48cf045577ca7b351c2ac241b4509`. Exact-head CI [43555992320](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43555992320) passed 10/10, including unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified R8, Server + Worker, AST and integrity checks. Security [43555992247](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43555992247) passed 3/3 (CodeQL, Gitleaks, SBOM).

## Verification and residuals

PR #303 merged as `eb1edcba2efc38481298a3a50e2b5eb789cc2b2a` from final source/test head `7974a197a03a929b336fbd2c0fb64c6a2a8f68fe`. Exact-head CI [43550121483](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43550121483) passed 10/10 and Security [43550121365](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43550121365) passed 3/3. PR #304 then closed the remaining main-thread persistence and pre-wipe side-effect gaps above; its CI/Security also passed. Post-merge main is `11bc01c863951152c559a72def3b0faa2a454b63`: CI [43556537823](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43556537823) passed 10/10 and Security [43556537826](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43556537826) passed 3/3; the Vercel status is successful. No local Gradle/Android toolchain execution is claimed; successful verification was performed by repository CI.

Automated verification covers durable-tier checks, commit/read-back failures, malformed records, paired staging/recovery, interrupted/orphaned states, failed cleanup commits, legacy metadata cleanup, async UI hand-off and call-site ordering. CI does not prove the behavior of every vendor's Keystore implementation or process death at every physical storage boundary.

Remaining acceptance:
- real Android Keystore / EncryptedSharedPreferences durability behavior on supported API levels and target OEMs;
- process-kill/relaunch at each account/device journal stage and cleanup boundary;
- device-gate setup, PIN change, account restore/promotion, unlock and duress recovery flows on physical devices;
- OEM-specific behavior and device acceptance after the app is feature-complete.

S06-H6 remains **Partial** until those real-device/Keystore acceptance gates are completed. `FINAL_SIGNOFF.md` remains **PENDING — NOT APPROVED FOR PRODUCTION SIGN-OFF**. No production secrets, provider configuration, staging resources, deployments or devices were changed.