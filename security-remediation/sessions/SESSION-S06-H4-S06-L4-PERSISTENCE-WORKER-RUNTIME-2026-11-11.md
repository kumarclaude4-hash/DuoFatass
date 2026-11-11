# Session — S06-H4 durable secondary-code persistence / S06-L4 worker runtime

**Date:** 2026-11-11  
**Merged PR:** [#292](https://github.com/kumarclaude4-hash/DuoFatass/pull/292)  
**Merge commit:** `b73cab8049983969b1996245600a95ff227da175`  
**Exact PR head:** `ab17f656f2ac352944f1f764af68280a249ced35`

## Scope and findings

A targeted source-and-test re-verification covered S10-N2, S07-L2, S06-H5, S06-H4, S05-M3, S05-H2 and S06-L4.

- **S06-H4:** confirmed a real durability gap. The UI already handled setter failure, but `DuressManager.setDuressPin()` used asynchronous `apply()` and returned true without disk confirmation.
- **S06-L4:** the retry policy and server transaction recovery were already implemented, but existing Android tests inspected source rather than executing the actual WorkManager worker.
- **S10-N2 / S07-L2 / S06-H5 / S05-M3 / S05-H2:** source review found the documented bounded analyzer/residual, libsignal API limitation, primary-PIN throttle coverage, and existing Firestore emulator coverage in place. No additional reproducible code defect was established in those areas during this pass. Their existing Partial dispositions remain where broader or external acceptance is required.

## Changes in PR #292

### S06-H4

- Refuse success if the account-scoped `SecurePrefs` tier is not durable.
- Commit slot-B hash, armed bit and legacy-key removal synchronously as one update.
- Read back the hash, armed bit and legacy-key absence before returning success.
- Zero app-owned salt and PBKDF2 output arrays in `finally`.
- Add `DuressPinPersistenceTest` for successful commit/read-back, failed commit, incomplete read-back and invalid input.
- Extend the UI wiring regression to require the durable-tier guard and persistence helper.

### S06-L4

- Add package-private injection seams for HTTP connection creation and lock-intent acknowledgement; production defaults retain the real `HttpURLConnection` and `PendingLockStore` implementation.
- Add WorkManager `TestWorkerBuilder` plus Robolectric runtime coverage for HTTP 200/204, permanent 401, retryable 503, retry exhaustion, network exception and missing nonce.
- Assert POST body/content type/timeouts, disconnect behavior, result mapping and compare-and-clear calls.
- Pin `androidx.work:work-testing:2.9.0` and its required module metadata in Gradle dependency verification.

## Verification evidence

- CI [42948877593](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877593): **10/10 jobs passed** — AST release-log policy, vendored libsignal integrity, Server + Worker, Gradle wrapper integrity, lint, Android unit tests, debug APK, API 35 predictive-back, Espresso and minified R8.
- Security [42948877583](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877583): **3/3 passed** — CodeQL, Gitleaks and SBOM.
- Firestore Rules/server transactions [42948877424](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877424): passed.
- PR status included a successful Vercel preview. No production deployment was performed.

## Final dispositions

- **S06-H4:** Fixed for source implementation and automated regression coverage. Physical-device/Keystore acceptance remains part of the deferred feature-complete device pass.
- **S06-L4:** Partial. Runtime worker execution is now covered, but staging lost-response/admin-unfreeze races, physical wipe/reboot/sign-in/network-loss recovery and production Firestore configuration remain.
- **S06-H5:** Partial pending independent side-channel review and device acceptance; throttle coverage already includes Settings, restored setup and forced rotation.
- **S07-L2:** Partial; pinned libsignal Java wrapper exposes no deterministic public native-key destruction API. No synchronous native erasure guarantee is made.
- **S10-N2:** Partial; analyzer remains bounded syntax-level analysis, with unmodeled external/native/third-party behavior and physical release/minified APK logcat acceptance remaining.
- **S05-M3 / S05-H2:** Partial pending staging/production and owner/product acceptance; existing Firestore emulator lifecycle coverage is retained.

No live secrets, real identity material, staging data migration, production service or physical-device test was used. `FINAL_SIGNOFF.md` remains **PENDING**.


## Follow-up PR #295 — failed SharedPreferences commit semantics

PR #295 merged as `93bd3b4a2f17007ddc7d0159c72bdbd0da7868a1` (exact head `a069832db3bd57ef97a895ba3a3faaef7182e0bf`). This test-only follow-up corrects the earlier fake's false rollback assumption:

- `FakeSharedPreferences.commit()` now applies the editor update to its in-memory map, then returns the configurable disk-write result.
- The failed-commit test asserts that `DuressPinPersistence.persist()` returns false and therefore cannot be surfaced as durable success.
- It explicitly allows the current process to observe the updated in-memory preference map after a false disk commit, matching Android SharedPreferences semantics; it does not promise rollback.
- Production code, runtime behavior, dependencies and deployment configuration were not changed by PR #295.

Exact-head CI [42955598180](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42955598180) passed all 10 jobs; Security [42955598156](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42955598156) passed all 3 jobs. S06-H4 remains Fixed for the source persistence contract and automated tests; physical Keystore/device acceptance remains deferred. S06-L4 remains Partial for staging and physical-device recovery acceptance.
