# Comprehensive Remaining Security Work — Independent Claude Review

**Prepared:** 2026-11-08  
**Repository:** https://github.com/kumarclaude4-hash/DuoFatass  
**Original source-review baseline:** `main` at `fa5431a6ea3cc7af7c4ba05b9b2c1c617c2c7101` (PR #248 merge)  
**Current reconciliation baseline:** `main` at `d1ee9ac01e0809a6e398958be423097bc082e9d7` (PR #274 merge; includes #272, #271, #270, #269 and #267)  
**Purpose:** Source-linked, evidence-aware inventory of incomplete security work, with precise next actions and acceptance criteria for an independent Claude review.

> This is a handoff report, not a new audit or sign-off. The canonical finding register is [`BUG_TRACKER.md`](../BUG_TRACKER.md). `FINAL_SIGNOFF.md` remains **PENDING — NOT APPROVED FOR PRODUCTION SIGN-OFF**. Do not invent finding IDs, change dispositions without evidence, infer owner approval, or mark a finding Fixed merely because a PR merged.

---

## Current-main reconciliation addendum — 2026-11-08

This addendum supersedes conflicting 2026-11-07 baseline and open-PR statements below. The original review remains historical evidence; current dispositions are in `BUG_TRACKER.md`.

**Current main:** `d1ee9ac01e0809a6e398958be423097bc082e9d7` (after PR #274). There are no open code PRs in the latest repository issue/PR snapshot.

Merged after the original review:
- PR #261 — explicit active/future-expiry invite-state validation and audited invalid-state revocation (S05-H2).
- PR #264 — invalid invite rows expose audited revoke action (S05-H2).
- PR #265 — durable successful/failed Settings PIN-change throttle and generic failure response (S06-H5); CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed.
- PR #267 — App Check / Firestore Rules correction (S10-N1), merged as current main.
- PR #269 — S10-N2 bodyless helper-return hardening; exact-head CI [42446136767](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136767) and Security [42446136770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136770) passed; merged as `afdf2463c496f14a5c33ee5694fc47eff97d5e3d`. Inheritance/virtual dispatch, reflection, external callbacks and physical release-logcat residuals remain. Exact PR-head CI [42443323887](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323887), Security [42443323988](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323988), Firestore Rules [42443323886](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323886), and Rules/server transactions [42443323962](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323962) all passed.
- PR #270 — post-#269 security handoff reconciliation; merged as `1a425ad0aa2e7c36fa66a2afe4e46d85e78ee514`.
- PR #271 — S07-L2 app-owned buffer/cache cleanup; exact-head CI [42448400756](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400756) passed 10/10 and Security [42448400818](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400818) passed 3/3; merged as `87e558c25bd810dafb8b47d97e52c2022a4a94fe`. Native key destruction remains finalizer-timed; S07-L2 remains Partial.
- PR #272 — reconciled handoff, dependency guidance and 12-route static inventory; merged as `0a985fb39570fea3b030da76417e67a01790f8b5`.
- PR #274 — S10-N2 typed cross-class field-write/read taint coverage; exact head `a26d4f1140532879c4e74590e6bb1ec16a34ebc4`, CI [42450251567](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251567) passed 10/10, Security [42450251553](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251553) passed 3/3, merged as `d1ee9ac01e0809a6e398958be423097bc082e9d7`. A separate `ChatMediaActivity` warning no longer emits retry-count or protocol-type details. This closes the reproduced typed setter-to-field flow, not arbitrary alias or virtual-dispatch flows.
- PR #266 was intentionally closed unmerged; its documentation changes are not on main.

The old #253/#254/#258 table below is historical: #253 was superseded by #261, #254's range-read work was superseded by #256, and #258 is closed. PR #262 was closed unmerged; its PIN-throttle change was carried in #265. Do not reopen those branches without a fresh source review.

Post-merge main workflows on `8d0082d004993278645f3eb7a72c43a8cdc14e59`: Security [42445582762](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582762), Firestore rules [42445582759](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582759), and Firestore Rules Tests [42445582747](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582747) passed. Main CI [42445582763](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582763) passed on the exact main SHA, including Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified release R8, Server + Worker tests and integrity checks.

S06-H5 remains Partial, but the formerly described Settings collision oracle has been materially mitigated: PR #265 records all accepted and rejected existing-PIN replacement attempts in a UID-scoped three-attempt/15-minute throttle, uses a generic rejection result, and fails closed on storage errors. Residual security review and device acceptance remain; do not say the throttle proves complete non-observability.

S10-N1 has been corrected: Firebase App Check enforcement occurs at the Firebase service layer before Firestore Rules evaluation. Firestore Rules cannot inspect App Check metadata. The unsupported `request.app`/ `appCheckVerified()` expressions and the emulator test arrangement that hid them were removed by PR #267. App Check monitoring, compatible-client adoption, Console enforcement and real-token isolated-staging acceptance remain operator tasks. Never recommend adding `request.app` to Firestore Rules.

The current register remains 116 findings: 86 Fixed, 17 Partial, 1 Open, 12 Accepted. `FINAL_SIGNOFF.md` remains PENDING. No production configuration, live credentials, historical UID migration or physical-device acceptance is claimed.

**S10-N2 update from PR #274:** the reproducible typed cross-class setter-to-field path is now guarded. The static analyzer associates a summarized field write with its declaring app-owned class and checks a later field read through a receiver with a resolvable declared type. Its synthetic regression uses `CrossClassHolder.memo`, with a `Profile.getUserId()` source passed through `setMemo()` and logged later. This is still bounded syntax/AST analysis; inheritance/override dispatch, arbitrary multi-hop/aliased receiver field flows, reflection, native methods, third-party callbacks and physical release-logcat acceptance remain unproven. CI [42450251567](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251567) and Security [42450251553](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251553) passed on exact PR head `a26d4f1140532879c4e74590e6bb1ec16a34ebc4`.

---

## 0. Instructions to Claude

Start from the current reconciliation baseline above. The original PR #248 analysis is retained as historical context; the current-main sections and dated addenda supersede it where they differ. Independently read current source/tests before changing a finding.

For every item:
1. Confirm the current implementation and tests at the current reconciliation baseline; do not treat the original PR #248 baseline as current.
2. Separate **code**, **automated verification**, **staging/operator action**, **physical-device acceptance**, and **owner decision**. These are different gates.
3. If code is needed, create a narrow branch from current `main`; add a regression test that fails before the fix; run focused tests plus applicable CI/security workflows; open a PR. Never commit directly to main.
4. Capture exact commit SHA and reproducible evidence: workflow URL, test report, redacted staging record, provider-console read-back, or dated owner decision.
5. Keep a finding Partial/Open until every required gate passes or the owner formally accepts a documented residual.
6. Use synthetic identities, isolated Firebase projects, isolated Worker/R2/B2 resources and staging credentials. Never use production data or secrets.
7. **Do not begin physical-device/manual app-flow testing until the owner explicitly declares the app feature-complete.** Existing automated CI emulator jobs remain in scope.
8. Never expose bearer tokens, cookies, private keys, media keys, raw user identifiers or provider secret values.

### Required Claude deliverable
Return a finding-by-finding disposition (confirmed remaining / newer evidence satisfies / owner-blocked / operator-blocked / device-deferred), exact source paths/symbols/tests reviewed, discrepancies against this report, dependency-ordered plan, and proposed code/test/rollback details. Do not fabricate staging, device or production evidence.

---

## 1. Current baseline and already-completed work

**Historical baseline (2026-11-07):** `98f76cdf374eeaf5c8144befad068ef05805a20b`. The current-main baseline and workflow evidence are in the 2026-11-08 addendum at the top of this document.

This report was initially prepared against the earlier `fa5431a6ea3cc7af7c4ba05b9b2c1c617c2c7101` baseline. The current mainline includes the subsequent merges below; follow the current tracker and addenda rather than treating the original line-by-line review as current after those changes.

Relevant merged work:
- PR #251: bounded interprocedural extension to the Android release-log analyzer; S10-N2 remains Partial.
- PR #255: invite redemption UID pseudonymization and dry-run-first legacy cleanup utility; S05-H2 remains Partial.
- PR #256: valid single-range GETs cost half a request unit in the shared per-holder minute bucket to support chunked encrypted-video playback; global daily request and mutation budgets remain unchanged.
- PR #257: documentation-only S05-H2 privacy closeout.

Current main CI and Security Scan passed Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified R8, Server/Worker tests, release-log AST, Cloudflare Worker build validation, CodeQL, Gitleaks, SBOM and dependency-integrity checks.

### Historical PR status — no open code PRs at current snapshot

The earlier PR table is retained in prior handoffs only. At the 2026-11-08 current-main snapshot, #253/#254/#258 are closed and superseded/closed as described in the addendum above. No code PR is currently open.

## 2. Executive inventory

| Finding | Current status | Remaining gate |
|---|---|---|
| S03-M3 | Partial | Owner risk decision or versioned protocol |
| S05-M3 | Partial | Multi-instance staging + Firestore rules/index/TTL read-back |
| S05-H2 | Partial | Product contract; synthetic staging lifecycle/onboarding; authorized legacy UID cleanup dry-run/apply/read-back. Core invite lifecycle code and invalid-state fail-closed handling are merged in PRs #261/#264. |
| S06-L4 | Partial | PR #292 adds WorkManager `TestWorkerBuilder`/Robolectric execution of the real `AccountLockWorker#doWork()` path for HTTP 200/204, permanent 401, retryable 503, attempt exhaustion, network exception and missing nonce, with request/acknowledgement/disconnect assertions. Exact-head CI 10/10, Security 3/3 and Firestore Rules/server transactions passed. Remaining: authorized staging lost-response/admin-unfreeze race, production Firestore config and physical wipe/reboot/sign-in/network-loss recovery. |
| S07-L2 | Partial | Supported deterministic native-key destruction or approved residual |
| S08-H2 | Partial | Screenshot/recording/recents device acceptance |
| S08-H5 | Partial | Real Keystore migration/recovery + support path |
| S07-M1 | Partial | Same migration acceptance as S08-H5; keep both tracker rows |
| S08-L3 | Partial | 4/5/6-digit keypad, debounce, backspace and duress device acceptance |
| S08-L4 | Partial | Lock-cover visual/touch/unlock/sign-out device acceptance |
| S10-N1 | Partial | App Check monitoring, compatible-client adoption, Firebase Console service-level enforcement and isolated real-token staging acceptance. Do not use `request.app` in Firestore Rules. |
| S10-N2 | Partial | Syntax-only analysis residual review + feature-complete physical release-logcat acceptance |
| S10-N3 | Partial | Isolated live Worker/R2/B2 scheduled migration acceptance |
| S11-H1 | Partial | Real predictive-back edge gesture and OEM matrix |
| S11-M1 | Partial | Call minimize/timeout/notification-return lock acceptance |
| S02-I2 | Partial | Render/Worker secret pairing read-back + Durable Object proof |
| S02-I3 | Partial | Firebase revoked-token integration across all 12 routes |
| SC-12 | Open | Required checks/reviews/tags/production environment governance |
| S06-H4 | Fixed | PR #292 requires a durable `SecurePrefs` tier, synchronously commits the slot-B hash/armed bit/legacy-key removal, verifies all three by read-back, and clears app-owned salt/hash arrays in `finally`. PR #295 corrects the test fake to model Android's in-memory update before a disk-write result; false commit must remain failure and must not be surfaced as durable success, but no in-memory rollback is claimed. PR #292 exact-head CI 10/10, Security 3/3 and Firestore Rules/server transactions passed; PR #295 exact-head CI 10/10 and Security 3/3 passed. Physical Keystore/device-flow acceptance remains deferred. |
| S06-H5 | Partial | PR #265 adds a UID-scoped three-attempt/15-minute throttle that counts successful and rejected PIN changes, fails closed on storage errors and unifies collision/cooldown/storage failures. Residual side-channel review and feature-complete device acceptance remain; do not describe the pre-PR #265 Settings collision branch as current. |

S07-M1 and S08-H5 concern the same legacy migration stream from separate classifications. Coordinate them together but preserve both finding IDs/statuses. The tracker is authoritative if any narrative document differs.

---

## 3. Detailed work items

## 3.1 S03-M3 — Media capability-token reuse/TTL residual

**Status:** Partial; explicit owner decision required.  
**Decision record:** [S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md](S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md)  
**Code:** `server/lib/mediaToken.js`, `server/index.js` (`/mediaToken`), `worker/src/index.js`, `worker/src/index.test.js`.  
**Evidence:** PR #227 Worker suite 49/49, [run 42149339271](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42149339271).

Current wire format: `v1.<op>.<expiresAt>.<holder>.<jti>.<sig>`; random JTI is signed. DELETE is one-use and fail-closed if replay KV is missing or fails. READ and WRITE remain reusable for range requests, re-fetches, retries and ambiguous upload responses. TTL remains 10 minutes.

**Residual:** a stolen READ/WRITE bearer token can be reused until expiry. JTI is not consumed for those verbs.

**Next action — do not silently change v1 semantics.** Obtain written owner choice:
- **A: ratify current policy:** 10-minute TTL, reusable READ/WRITE, single-use DELETE; record approver, date, residual risk and review/expiry date.
- **B: versioned one-shot protocol:** design new Android/server/Worker protocol. Specify fresh-token behavior for retries/ranges, idempotency for lost success responses, parallel chunks, replay storage lifecycle, rate limits, backward compatibility and rollback.
- **C: shorten TTL:** only after measuring request duration and defining client re-mint. A shorter TTL alone does not prevent reuse inside the window.

**Acceptance:** signed/dated Option A decision, or approved Option B design followed by cross-component contract/failure-injection tests and rollout plan. Until then Partial.

## 3.2 S05-M3 — Durable admin-session ledger and fleet-wide revocation

**Status:** Partial; emulator integration verified, staging pending.  
**Code:** `server/lib/adminSessionStore.js`, admin routes, Firestore rules/indexes.  
**Test:** `adminSessionStore.firestore.integration.test.js`; PR #222.

Firestore is authoritative. Random 256-bit session IDs are HMAC-derived into document IDs; raw cookies/IDs are not stored. Transactions cover create, validation/idle refresh and single-session revoke. `adminSessionControl/global.generation` supports global revoke across instances/restarts. Firestore failures fail closed with 503; TTL is cleanup only.

**Remaining staging matrix, synthetic sessions only:**
1. Create on instance A; validate on independent instance B.
2. Revoke one session; prove B rejects it.
3. Global revoke; prove all instances reject old sessions.
4. Restart instances and repeat.
5. Simulate Firestore outage; prove fail-closed 503, no process-local authorization fallback.
6. Read back deployed rules, indexes and TTL configuration.
7. Verify idle and absolute expiry.

A stolen live HttpOnly cookie remains a bearer credential until logout/global revoke/expiry. IP/UA binding is intentionally disabled for mobile/WebView compatibility; WebAuthn/step-up is future hardening.

**Acceptance:** redacted multi-instance staging evidence, no cookie values, deployed rules/index version and TTL read-back. Keep Partial until proven.

## 3.3 S05-H2 — Invite lifecycle and operator visibility

**Status:** Partial; route/Firestore-emulator coverage exists, product contract and staging pending.  
**Code:** invite and identity routes in `server/index.js`/route modules, `server/lib/identityVerify.js`, invite/claim helpers and Firestore rules.  
**Evidence:** PRs #226/#227; real Firestore transaction + HTTP tests using genuine libsignal signatures.

Implemented: 256-bit opaque invites; only SHA-256 hashes/short fingerprints persisted; create/redeem/revoke audit records transaction-coupled; cursor-paginated listing; pseudonymized redemption linkage. Tests cover forgery, nonce replay, missing-invite rollback, valid consumption/identity binding/audit and existing-identity restore.

**Remaining:**
1. Product owner confirms invite-only onboarding is intended.
2. In isolated staging, exercise create/list/revoke/validate.
3. Complete Android new-user onboarding with synthetic invite.
4. Test expired, revoked, redeemed and malformed invite paths.
5. Verify exactly-once audit and no raw invite token in records.
6. Verify pagination and access control.

**Acceptance:** dated product decision plus redacted full lifecycle staging evidence. Keep Partial.

### S05-H2 — 2026-11-07 privacy correction and merged evidence

PR #255 (https://github.com/kumarclaude4-hash/DuoFatass/pull/255) merged at `89ac44cf85df1c90530196fdbb01bf93ff2e8994`. The previous source state contradicted the stated pseudonymization policy: invite documents stored raw `usedByUserId` and the admin list/UI exposed it. New redemptions now persist only a short `usedByUserIdTag`; list projections mask legacy raw UID fields; the admin UI displays only the tag. A dry-run-first, batched cleanup script is available at `server/scripts/pseudonymizeInviteRedemptionUids.js`.

Exact PR head `af8f676c6bdbeb125eb46c01e4aa2b56af30c4f0` passed CI [42385827187](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827187) **10/10 jobs** and Security [42385827198](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827198) **3/3 jobs**. The release-log AST scanned 204 Java files / 414 log sinks with zero violations; Firestore invite/mintToken integration, Server/Worker, Android unit/lint/debug/R8, Espresso (4) and API 35 predictive-back (1) passed.

**Not complete:** historical Firestore rows may still contain raw UID fields. No live migration was run. The operator must execute a staging dry-run, review aggregate counts, explicitly approve `--apply`, then repeat dry-run and confirm zero candidates. The product owner must confirm invite-only onboarding and complete synthetic staging/Android onboarding acceptance. Keep S05-H2 Partial.
 

### Subsequent invite lifecycle work — PRs #261 and #264 merged

PR #261 merged explicit `status === "active"` and finite future-expiry requirements; missing, unknown and malformed stored states are invalid, cannot redeem, can be filtered by admins and can be revoked through the audited backend path. PR #264 merged the UI action that exposes Revoke for invalid invite rows and uses generic confirmation wording. The older PR #253 was closed unmerged and superseded; PR #258 was closed. Do not reopen either historical branch without a new source review.

**Remaining:** product owner confirmation that invite-only onboarding is the intended contract; authorized staging create/list/search/paginate/revoke/expiry/validation/one-time redemption; synthetic Android onboarding/restore after feature completion; and an authorized dry-run-first legacy UID cleanup with aggregate review, explicit approval before apply, and a repeat dry-run proving zero candidates. Keep S05-H2 Partial until those external acceptance gates pass.


## 3.4 S06-L4 — Account-lock worker retry/recovery

**Status:** Partial; implementation and emulator/JVM regressions merged, live acceptance pending.  
**Code:** `AccountLockWorker`, `DuressManager`, `WipeHelper`, server nonce/lock transaction, WorkManager and Firestore configuration.  
**Implementation:** PR #225.

Only HTTP 200/204 confirms remote lock. Permanent 4xx fails without clearing durable intent. Transient 408/425/429/5xx/network failures use bounded exponential retry (`MAX_ATTEMPTS = 7`). Nonce consumption, account lock and idempotency receipt are transactionally coupled. Lost-response replay acknowledges original commit without re-locking an account an administrator has unfrozen. Recovery drains existing nonce before replacement; compare-and-clear prevents stale workers deleting newer state.

**Staging cases:** lost response after commit; administrator unfreezes before retry; retry exhaustion; permanent invalid/expired nonce; reboot with pending work; production Firestore TTL/index/rules/service-account read-back.

**Device cases, only after feature-complete:** wipe, reboot, sign-in, network loss/recovery and lock reconciliation. Include duress and normal account paths.

**Residual:** if device never reconnects or same account never authenticates, remote lock cannot be guaranteed; local intent remains explicit. Keep Partial until staging, configuration and later device evidence exists.

## 3.5 S07-L2 — Deterministic native key destruction

**Status:** Partial; PR #271 improves app-owned memory hygiene, but deterministic native-key destruction remains unavailable.  
**Code:** `SeedPhraseHelper.deriveIdentityKeyPair()`, `SeedPhraseHelper.clearDerivationCache()`, `WipeHelper.eraseLocalData()`, pinned libsignal 0.54.1 Java/Rust bindings.  
**Evidence:** [S07-L2 session](sessions/SESSION-S07-L2-LIBSIGNAL-BOUNDARY-2026-11-06.md), PR #271 exact-head CI [42448400756](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400756) and Security [42448400818](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400818), both passed.

PR #271 serializes identity derivation and cache eviction, stores a defensive copy of the cached seed fingerprint and zeroes it on eviction, and zeroes app-owned derivation-local fingerprint, HKDF info label and serialized private-key input arrays in `finally`. The HKDF helper clears its app-owned salt, PRK and intermediate output arrays; byte-for-byte output is regression-tested and caller inputs remain unchanged. Regressions cover cache defensive-copy/zeroization, monitor serialization, HKDF output and output-length validation.

This does **not** deterministically destroy native key handles. Pinned libsignal Java `ECPrivateKey.finalize()` invokes native destroy only when finalization occurs; the Rust `StaticSecret` zeroizes on drop, but the Java wrapper exposes no public deterministic `close()`/`destroy()` API. Other callers may retain `IdentityKeyPair` references, native keys can be cloned, Java crypto providers may retain internal copies, and a later post-wipe derivation can repopulate the cache. The app lifecycle must block new identity work after wipe. No complete in-memory erasure or synchronous destruction guarantee is claimed.

**Next:** retain the app-owned cleanup; request/track an upstream-supported explicit destruction API or record an owner-approved residual-risk decision. Do not use reflection, unsafe native access or forced GC. Keep Partial; physical memory/remanence testing remains separate release QA.

## 3.6 S08-H2 — Screenshot/screen-recording/recents protection

**Status:** Partial; lifecycle enforcement merged, device acceptance pending.  
**Code:** `DuoShieldApp` ActivityLifecycleCallbacks, `BaseActivity`, `FLAG_SECURE`, `app_screenshot_enabled`.  
**Test:** `ScreenshotProtectionLifecycleTest`; PR #236.

Protection is applied in `onActivityCreated` before first frame and `onActivityResumed` after settings/external UI; BaseActivity remains defense in depth.

**After feature-complete:** test fresh install/default policy; explicit opt-in; screenshots, screen recording and recents thumbnails; setting toggles while backgrounded; direct AppCompat screens; lock/call screens; supported API/OEM matrix; no first-frame leakage.

**Acceptance:** device/OEM/API/build, steps, expected/actual and sanitized evidence. Keep Partial.

## 3.7 S08-H5 + S07-M1 — Legacy plaintext migration/recovery

**Status:** Both Partial; one coordinated workstream, keep both rows.  
**Code:** `LegacyPlaintextMigrator`, secure preference storage, onboarding/restore gates, `SecurePrefs`.  
**Test:** `LegacyPlaintextMigratorTest`; PRs #228/#231/#243. CI [42185303041](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42185303041), Security [42185303045](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42185303045).

Migration requires exact value/type read-back before deleting legacy plaintext. Conflicts, unreadable sources and exceptions preserve source and keep migration gated. Fault injection covers destination commit failure, post-write read-back exception and cleanup commit failure with safe retry. Release R8 gate exists.

**Remaining:** physical-device upgrade with real Keystore/EncryptedSharedPreferences after feature-complete; process death at each phase; reboot/retry; locked/unavailable Keystore; corruption; support/recovery procedure for un-migratable installs. Never manually delete retained files or bypass gate.

**Acceptance:** prove source retained on failed verification, exact destination read-back on success, safe retry/recovery and documented support path. Keep both Partial.

## 3.8 S08-L3 — PIN-length privacy/keypad

**Status:** Partial; persistence fix/JVM test merged, device acceptance deferred.  
**Code:** `PinManager`, `LockScreenActivity`, `DevicePinGateActivity`, `DuressManager.setDuressPin()`, `ManageUnlockCodesActivity`.  
**Test:** `PinLengthNotPersistedTest`.

Real PIN length no longer persists beside hash; getters return fixed six-digit upper bound; legacy keys scrubbed. Short PINs use debounced auto-submit after pause once minimum length reached; digit/backspace cancels pending submit. Duress validation accepts min/max range.

**After feature-complete:** test 4/5/6 digit PINs; correct/wrong/duress; debounce/no premature submit; backspace; rapid/slow/interrupted input; both lock screen and device gate; no length in preferences/backups/logs/analytics.

**Acceptance:** reproducible device matrix. Keep Partial.

## 3.9 S08-L4 — Lock-screen cover and recents

**Status:** Partial; manifest/opaque cover wiring merged, runtime acceptance pending.  
**Code:** `LockScreenActivity`, `LockScreenCover`, `BaseActivity.onStart()`, manifest.  
**Test:** `LockScreenCoverWiringTest`.

Lock screen excluded from recents/singleTask. Opaque touch-consuming cover is installed before lock activity launch and removed after successful unlock path.

**After feature-complete:** no content flash; covered content receives no touch; correct PIN clears cover; duress state correct; no-PIN/sign-out clears safely; process death/recreation; recents snapshots; accessibility/focus; no stuck overlay.

**Acceptance:** API/OEM transition evidence. Keep Partial.

## 3.10 S10-N1 — Firebase App Check service-layer rollout

**Status:** Partial; Android provider wiring exists, but live monitoring, service-level enforcement and staging acceptance remain pending.

**Important Rules-language correction (2026-11-08):** Firebase App Check is enforced by the Firebase service layer before Firestore Security Rules are evaluated. Firestore Rules cannot inspect App Check token metadata. The `request.app` field is not supported in Firestore Rules; referencing it causes rule evaluation errors and denies access even for requests carrying valid App Check tokens. A Firebase contributor in the official Firebase Android SDK issue thread clarified the service-level boundary; the issue reporter subsequently confirmed that the field itself is not evaluable and causes denial even for valid tokens ([Firebase Android SDK issue #8393](https://github.com/firebase/firebase-android-sdk/issues/8393), comments dated 2026-07-07 and 2026-07-10).

The earlier `appCheckVerified()` implementation and its emulator denial suite were therefore incorrect: the rules expression itself caused denials, while the test interpreted those denials as evidence of missing App Check. The corrective branch removes the helper and every invocation, restores verbatim-rule emulator testing, and adds a static guard against executable `request.app`/`appCheckVerified()` expressions. Android Play Integrity provider wiring is retained; the debug provider remains debug-only.

**Rules invariant:** keep Firestore authorization in `firestore.rules` limited to Firebase Auth, ownership, data validation and account-lock checks. Never reference `request.app` or add an App Check wrapper function to Firestore Rules.

**Authorized rollout:**
1. Confirm the released Android client registers Play Integrity before any Firestore token request; confirm the debug provider is debug-only.
2. Review Firebase App Check monitoring metrics for Cloud Firestore (and Cloud Storage separately only if that service is used).
3. Measure verified, outdated, unknown and invalid traffic across supported client/install channels; investigate missing-token traffic before enforcement.
4. Agree the minimum adoption threshold, support/exception policy and rollback procedure.
5. Enable App Check enforcement for the relevant Firebase service in the Console only after compatible-client adoption and rollback readiness are proven. Do not modify Firestore Rules to enforce App Check.
6. Use synthetic identities in isolated staging to prove a valid token is accepted and missing/invalid tokens are rejected after service enforcement; record redacted route/resource, app version and timestamps.
7. Read back the deployed service enforcement state and retain redacted evidence.

The Firestore emulator cannot mint/validate real App Check tokens or model the Firebase Console enforcement toggle. Emulator tests now validate the exact committed Firestore Rules and keep App Check token validation out of Rules; source tests guard the Android provider packaging and ensure the unsupported Rules expression cannot return. **Acceptance:** redacted monitoring metrics, staged positive/negative token evidence, service enforcement read-back and rollback readiness. Keep Partial.
## 3.11 S10-N2 — Release-log data-flow residual

**Status:** Partial; bounded interprocedural extension merged, syntax-only and physical logcat residuals remain.  
**Code:** `scripts/AndroidReleaseLogPrivacyPolicy.java`, release-log workflow and production Java logging sites.  
**Code PR:** [#251](https://github.com/kumarclaude4-hash/DuoFatass/pull/251), merged at `fc3dcd948a2410887c23ff8c29913c6c5d92604a`.

The merged analyzer includes:
- PR #244 compiler-AST guard and PR #246 sensitive identifier/tag/message coverage.
- PR #248 method-local aliases, sticky reassignment, concatenation, builders and common Map/Bundle/JSON/container flow.
- PR #251 bounded fixed-point summaries for uniquely resolvable app-owned helper returns, parameter pass-through and same-class field writes/reads, including helper-mediated writes.
- Explicit redaction boundaries only when source analysis proves the returned value is redacted; ambiguous same-name/same-arity overloads and unresolved/external dispatch remain conservative. Recursive summaries are bounded and fail closed if they do not converge.

**Exact PR #251 evidence:** head `d6041db2033b1950cdf006b900ef5cd8f2d95eba`; CI [42363163588](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42363163588) **10/10**; Security [42363163447](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42363163447) **3/3**. AST scanned **204 production Java files / 414 Log.w/e/wtf calls / zero violations**. Espresso 4 tests and API 35 predictive-back 1 test passed.

**Remaining limitations:** syntax-only analysis is not symbol-resolved whole-program data-flow proof. Inheritance/virtual dispatch, reflection, cross-class field aliasing, native methods, unresolved external calls and third-party callback internals remain outside its guarantee. Complete residual source review. After the owner declares feature-complete, build the release/minified APK, exercise identity/media/call/lock/migration/error flows and inspect sanitized release logcat for raw UID, peer identifiers, device fingerprint, tokens, media keys, user metadata and exception payloads. Keep Partial until this acceptance; CI alone is not production sign-off.

## 3.12 S10-N3 — R2-to-B2 cold-tier migration race

**Status:** Partial; failure-interleaving tests merged, live runtime acceptance pending.  
**Code:** scheduled migration in `worker/src/index.js`, `worker/src/index.test.js`, Cloudflare Worker/R2/B2/cron.  
**Evidence:** PR #242 CI [42181494097](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42181494097).

Tests cover changed R2 ETag, transient post-PUT R2 HEAD failure, B2 PUT failure and failed compensating B2 DELETE. Failed HEAD is distinguished from confirmed missing; raw media keys removed from warnings.

**Isolated live acceptance:** verify Worker version/cron; migrate synthetic object and verify metadata/ETag/size; interleave overwrite and delete; simulate B2 PUT/DELETE and R2 HEAD failures; verify source preservation, no resurrection, idempotency, accounting and repeated cron reconciliation; inspect logs for raw keys/secrets. Use isolated buckets only.

**Acceptance:** redacted Worker/cron run IDs, deployment version, before/after object metadata and failure-injection/accounting evidence. Unit tests alone do not close it. Keep Partial.

## 3.13 S11-H1 — Predictive-back lock bypass

**Status:** Partial; source fix/API 35 key-dispatch test merged; physical edge gesture/OEM acceptance pending.  
**Code:** `util/BackPressGuard`, LockScreenActivity and six other guarded screens.  
**Tests:** `DevicePinGateInstrumentedTest.devicePinGate_consumesPlatformBackDispatch`, `PredictiveBackOverrideTest`; PR #237.

Seven legacy `onBackPressed()` overrides replaced with `OnBackPressedCallback`. API 35 instrumentation dispatches KEYCODE_BACK to DevicePinGateActivity and confirms gate stays visible.

**After feature-complete:** physical edge gesture on LockScreenActivity and all seven guarded screens; supported API/OEM matrix; gesture and 3-button navigation where supported; verify no protected-content preview or gate dismissal; confirm normal navigation remains functional elsewhere.

**Acceptance:** device/API/OEM/gesture evidence. Key dispatch is not equivalent to physical edge gesture. Keep Partial.

## 3.14 S11-M1 — Call lifecycle/app-lock timeout

**Status:** Partial; source implementation merged, runtime call/notification acceptance pending.  
**Code:** `CallActivity`, `InCallChatActivity`, `AppLockManager`, `LockScreenActivity`.

Call screens count as foreground without overwriting an existing background timestamp. CallActivity checks lock on return, not first start; after authentication LockScreenActivity can return to active call.

**After feature-complete:** short timeout; start call and prove no premature lock; minimize, wait beyond timeout, tap ongoing notification; verify lock before call UI/chat; unlock and confirm same call/audio resumes; incoming call while already locked; process recreation, call end and stale notification.

Do not refactor CallActivity into BaseActivity without proving it cannot place lock/offline wall over connecting calls or sign out mid-call. **Acceptance:** device call-state evidence. Keep Partial.

## 3.15 S02-I2 — Shared authenticated-route rate limiting

**Status:** Partial; route inventory regression merged, live Render/Worker config read-back pending.  
**Code:** `server/lib/adminLockoutWorkerClient`, 12 authenticated route call sites, Worker Durable Object, Render configuration.  
**Evidence:** PR #242 route inventory.

Twelve routes use SHA-256 namespaced UID+endpoint keys through Cloudflare Durable Object. Unbounded local Map removed; fallback bounded to 20,000 and logs degraded mode. DO counters expire and delete SQLite-backed storage.

**Operator acceptance:** verify Render `ADMIN_LOCKOUT_WORKER_URL` points to intended Worker; verify `ADMIN_LOCKOUT_WORKER_SECRET` matches Worker `ADMIN_LOCKOUT_SECRET` without printing values; verify DO binding; synthetic requests across two Render instances share counter; restart preserves global behavior; degraded fallback is visible; expiry cleanup works.

**Acceptance:** redacted key-name/match confirmation, Worker version, two-instance proof and DO expiry evidence. Route wiring alone does not close it. Keep Partial.

## 3.16 S02-I3 — Firebase revoked-ID-token enforcement

**Status:** Partial; all 12 call sites guarded, live Firebase staging integration pending.  
**Code:** twelve `verifyIdToken(..., true)` call sites in `server/index.js`; `server/lib/revokedIdTokenWiring.test.js`.  
**Prior tests:** 14/14 wiring tests; route inventory includes `/acknowledgeRotation`.

**Staging procedure:** use isolated Firebase project/synthetic user. Issue token A; verify all 12 routes accept it with valid route payloads. Revoke refresh tokens. Verify token A is rejected by every route. Reauthenticate and obtain token B; verify it succeeds where authorized. Ensure 401/403 from malformed payload is not misreported as revocation proof. Record route/status/timestamp and deployed server version, never tokens.

**Acceptance:** old-token rejection and new-token success for all 12 routes. Keep Partial.

## 3.17 SC-12 — GitHub governance

**Status:** Open.  
**Tracker evidence:** default ruleset `Hello`, ID `26629873`, has PR-required/deletion-blocked/non-fast-forward protections, but required approval count is 0 and required status checks are empty. Classic branch-protection read returned 403 to this integration; production-release environment was not verified.

**Administrator actions:** require at least one approving review; appropriate CODEOWNERS/owner review; required CI and Security Scan checks; preserve existing PR/deletion/non-fast-forward rules; protect immutable version tags; protect `production-release` with required reviewers/deployment restrictions; verify release workflow only publishes approved immutable refs/environment; read settings back after change.

**Acceptance:** admin-provided ruleset/environment read-back. Existence of a ruleset is not sufficient. Keep Open.

---

## 3.18 S06-H4 / S06-H5 — duress-code arming and Settings PIN-change probing

**S06-H4 — Fixed.** `ManageUnlockCodesActivity.onConfirmExplain()` checks the boolean from `DuressManager.setDuressPin()`: failure shows a generic retry message and returns before the success toast/finish path; only success reports “Code saved.” The setter accepts the supported 4–6 digit range. PR #263 added the focused source-wiring regression and is merged.

**S06-H5 — Partial.** PR #265 merged the PIN-change candidate throttle and common failure response. `PinManager.tryRecordPinChangeAttempt()` consumes a persisted UID-scoped attempt before checking or writing the proposed candidate. It allows three existing-PIN replacement attempts per 15-minute window, counts both successful and rejected attempts, combines wall-clock and same-boot elapsed-time deadlines using `Settings.Global.BOOT_COUNT`, and fails closed on persistence errors. Collision, cooldown and storage failures share a generic rejection; `PinChangeThrottleTest` and `SecurityFindingWiringTest` cover boundaries and call-site wiring. PR #265 exact head `ed8e47550763cf7766acb0f6ef3d53dc15039df3`, merged as `32175ccf86342a6c517459a0e2507f14c4d064e8`; CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed. PR #262 was closed unmerged; do not cite its head as the merged implementation. **Residual:** the throttle materially limits candidate-probing bandwidth but cannot establish that all success/failure observability is impossible. Keep Partial pending independent security review and feature-complete physical-device acceptance.

## 4. Coordinated physical-device matrix — strictly deferred

Do not begin until owner declares app feature-complete. Then perform one planned pass:

| Finding | Scenario |
|---|---|
| S11-H1 | Predictive-back edge gesture on LockScreenActivity and all no-skip gates |
| S11-M1 | Active/minimized call, timeout, notification return, unlock resumes call |
| S08-H2 | Fresh install/explicit opt-in; screenshots, recording, recents |
| S08-L3 | 4/5/6 digit PIN, debounce, backspace, interrupted entry, duress |
| S08-L4 | No content flash, cover blocks touch, clears on PIN/duress/no-PIN/sign-out |
| S08-H5 / S07-M1 | Real Keystore migration, process death, reboot, retry and recovery |
| S06-L4 | Wipe/reboot/sign-in/network-loss/lost-response/admin-unfreeze |
| S05-H2 | Full staging invite onboarding after contract approval |
| S10-N2 | Release/minified APK logcat privacy inspection |

Every record must include device/OEM, Android API, app build/commit, install/upgrade state, steps, expected/actual, sanitized evidence, tester/date and repeat count. No personal identifiers or tokens.

---

## 5. Operator/staging backlog — no production mutation by coding agent

| Workstream | Proof required | Prohibited shortcut |
|---|---|---|
| S05-M3 | Multi-instance admin session/revoke/restart/outage + Firestore config | Real admin cookies |
| S05-H2 | Invite lifecycle + Android staging onboarding | Real users/invites |
| S06-L4 | Lost-response/unfreeze/retry + Firestore config | Real-user wipe |
| S10-N1 | App Check monitoring metrics, compatible-client adoption, Firebase Console service-level enforcement and isolated real-token staging read-back; never add `request.app` to Firestore Rules | Blind enforcement or Rules-level `request.app` gates |
| S10-N3 | Worker/R2/B2 cron migration and failures | Production buckets/media |
| S02-I2 | Render/Worker secret pairing + DO proof | Print secret values |
| S02-I3 | Firebase revocation on 12 routes | Print bearer tokens |
| SC-12 | Ruleset/review/check/tag/environment read-back | Infer from ruleset existence |

### Credential rotation
Inspect [`migration/MIGRATION_PLAN.md`](migration/MIGRATION_PLAN.md) and related runbooks for exact active credential classes/order. The sign-off document explicitly calls out previously exposed Firebase/GCP service-account key revocation, obsolete Worker secret values, B2 application key, admin/session secrets and other listed credentials. Do not assume every historical secret remains active. Operator must identify exact key IDs in provider consoles, revoke/rotate, update secret stores, deploy compatible services, verify read-back without exposing values, and retain audit evidence. Never paste secrets into GitHub.

---

## 6. Dependency-ordered plan

### Phase A — decisions
1. S03-M3: owner chooses Option A or B.
2. S05-H2: confirm invite-only product contract.
3. S07-L2: supported deterministic-zeroization path or approved residual.
4. Confirm feature-complete milestone before device acceptance.

### Phase B — codeable agent work
1. S10-N2: perform a targeted residual review of the compiler-AST release-log analyzer's unresolved inter-class/virtual-dispatch/reflection/external-callback paths. Add a regression and fix only if a concrete false negative is reproducible.
2. S07-L2: investigate whether a supported libsignal API/version exposes deterministic native private-key destruction. Do not use reflection, unsafe native access or GC forcing as a substitute.
3. S06-H5: independently review residual observability after PR #265's merged throttle; do not re-implement the throttle or promote the finding from rate limiting alone.
4. Add a focused S06-H4 false/true arming UI regression only if a meaningful source/test seam remains; existing source guard and CI are already merged.
5. Re-check S05-M3/S05-H2/S06-L4 harness coverage against current source before adding any tests; their remaining acceptance is primarily staging/operator or device-bound.
6. Update the tracker and current handoff in the same PR as any newly justified code change.

### Phase C — isolated staging/operator
S02-I2 and S02-I3; S05-M3; S05-H2 after contract approval; S06-L4; S10-N1 controlled rollout; S10-N3 isolated Worker/R2/B2; credential rotation in runbook order.

### Phase D — governance
SC-12 required reviews/checks, immutable tags and production environment.

### Phase E — feature-complete device matrix
Run §4 only after explicit owner declaration.

### Phase F — final independent sign-off
Re-read every tracker row; recount statuses (do not reuse stale summary counts); revalidate `architecture/TRUST_BOUNDARIES.md`; attach CI/staging/operator/governance/device evidence; resolve or formally accept every Partial/Open with approver/date/review date. Update `FINAL_SIGNOFF.md` only when every criterion is evidenced. Otherwise leave PENDING.

---

## 7. Global release gates

Read [`FINAL_SIGNOFF.md`](FINAL_SIGNOFF.md). Its checklist requires:
- one current disposition for every finding and reconciled counts;
- Critical/High fixed or explicitly accepted;
- every Partial verified or formally risk-accepted;
- S02-I3 Firebase revocation integration;
- supply-chain/release provenance and operator configuration;
- SC-12 branch protection;
- credential rotation;
- App Check and Firestore configuration;
- coordinated server + Android + Worker + Firestore release;
- trust-boundary review and production-release approval.

PR merges and green CI are not a coordinated production release. Do not sign while any required evidence/decision remains unresolved.

---

## 8. Source-of-truth references

1. [Canonical finding register](../BUG_TRACKER.md) — IDs, statuses, severity, confidence, evidence.
2. [Final sign-off](FINAL_SIGNOFF.md) — release gates and non-approval status.
3. [Agent handoff](AGENTIC_WORK_REMAINING_2026-11-06.md) — execution rules; check for drift against main.
4. [Prioritized next-work report](NEXT_WORK_REPORT_2026-11-08.md) — S10-N2 residual and staging backlog.
5. [S03-M3 owner decision](S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md).
6. [S10-N2 session](sessions/SESSION-S10-N2-METHOD-LOCAL-TAINT-2026-11-07.md).
7. [S07-L2 native destruction boundary](sessions/SESSION-S07-L2-LIBSIGNAL-BOUNDARY-2026-11-06.md).
8. [Remediation plan](REMEDIATION_PLAN.md) and [Round 3 plan](ROUND3_REMEDIATION_PLAN.md).
9. [Trust boundaries](architecture/TRUST_BOUNDARIES.md).
10. [Migration plan](migration/MIGRATION_PLAN.md).

### Workflow/PR evidence
- PR #248 CI: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42331424519
- PR #248 Security: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42331424496
- PR #242 CI (S10-N3/S02-I2): https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42181494097
- PR #243 CI (migration fault injection): https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42185303041
- PR #243 Security: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42185303045
- PR #240 CI (S07-L2 cache clearing): https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42172475903
- PR #237: https://github.com/kumarclaude4-hash/DuoFatass/pull/237
- PR #236: https://github.com/kumarclaude4-hash/DuoFatass/pull/236
- PR #225: https://github.com/kumarclaude4-hash/DuoFatass/pull/225
- PR #222: https://github.com/kumarclaude4-hash/DuoFatass/pull/222
- PR #226/#227: https://github.com/kumarclaude4-hash/DuoFatass/pull/226 and https://github.com/kumarclaude4-hash/DuoFatass/pull/227

---

## 9. Explicit non-goals and safety constraints

- No fourth audit/remediation round, no invented finding IDs or renumbering.
- Do not reopen completed fixes without reproducible regression/new evidence.
- Do not silently change media-token semantics or App Check enforcement.
- No production credentials/data/accounts in tests.
- No physical-device testing before feature-complete declaration.
- Do not mark S10-N2 Fixed from AST green alone; S10-N3 Fixed from unit tests alone; S02-I2 Fixed from route wiring alone; S02-I3 Fixed from source call-site checks alone; or SC-12 Fixed from ruleset existence alone.
- Do not sign `FINAL_SIGNOFF.md` while required evidence/owner/operator gates remain.

**Bottom line:** remaining work is a portfolio of bounded code analysis, owner decisions, isolated staging verification, provider-console configuration, governance controls and deferred device acceptance. Each category needs its own evidence. Never substitute one kind of proof for another.


## Engineering re-verification and merged code closeout — 2026-11-11

PR #292 merged to main as `b73cab8049983969b1996245600a95ff227da175`.

### S06-H4 — durable secondary-code arming

The final verification pass found that the prior UI fix depended on a setter which used asynchronous `SharedPreferences.Editor.apply()` and returned success before disk persistence was confirmed. PR #292 closes this code gap:

- `DuressManager.setDuressPin()` refuses an in-memory-only `SecurePrefs` tier.
- Slot-B hash, armed state and legacy-key removal are committed in one synchronous preference transaction.
- Hash, armed state and legacy-key removal are read back before success is returned.
- App-owned salt and derived hash arrays are cleared in `finally`.
- Regression coverage includes success, failed commit, incomplete read-back, invalid input and call-site wiring.

Disposition remains **Fixed** for the source implementation. Device/Keystore acceptance remains part of the deferred feature-complete physical-device pass.

### S06-L4 — runtime worker execution

PR #292 added `AccountLockWorkerRuntimeTest` using WorkManager `TestWorkerBuilder`, a synthetic HTTP connection and a fake lock-intent acknowledger. It executes the real worker `doWork()` path for confirmed 200/204, permanent 401, retryable 503, bounded-attempt exhaustion, a network exception and missing nonce. It asserts the POST body/content type/timeouts, connection cleanup, result mapping and that only confirmed success clears the intent.

The final PR-head verification passed:
- CI run [42948877593](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877593): all 10 jobs passed — Android release-log AST, vendored libsignal, Gradle wrapper, Server + Worker, lint, Android unit tests, debug APK, API 35 predictive-back, Espresso and minified R8.
- Security run [42948877583](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877583): all 3 jobs passed — Gitleaks, CodeQL and SBOM.
- Firestore Rules/server transaction run [42948877424](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877424): passed.

Disposition remains **Partial** because runtime fakes and emulator jobs do not replace the authorized staging lost-response/admin-unfreeze scenario, physical wipe/reboot/sign-in/network-loss acceptance, or production Firestore configuration verification.

### Re-verification of the other requested workstreams

- **S10-N2:** Current analyzer/self-test and CI wiring are in place, including the latest method-reference callback regression. No new reproducible source defect was confirmed in this pass. Whole-program symbol resolution, unmodeled external/native/third-party callback paths and physical release/minified logcat acceptance remain residual; keep Partial.
- **S07-L2:** App-owned cache/intermediate cleanup is implemented. The pinned libsignal Java API still has no deterministic public native-key destroy method; do not claim synchronous native erasure. Keep Partial pending upstream API support or an approved residual-risk decision.
- **S06-H5:** Settings, restored setup and forced primary rotation all use the account-scoped attempt throttle before hidden secondary-code inspection. No missing candidate-check entry point was confirmed. Independent side-channel review and device acceptance remain; keep Partial.
- **S05-M3:** Admin-session emulator tests already exercise cross-instance validation/revocation, concurrent global revocation and expiry. Production rules/index/TTL deployment and staging/outage acceptance remain.
- **S05-H2:** Invite lifecycle, pseudonymous redemption and Firestore/HTTP integration tests are present. Owner confirmation of invite-only onboarding, historical UID cleanup execution and staging/Android onboarding acceptance remain.

No production deployment, live secret read-back, live data migration or physical-device acceptance was performed. The tracker baseline remains 116 findings: 86 Fixed, 17 Partial, 1 Open and 12 Accepted. `security-remediation/FINAL_SIGNOFF.md` remains **PENDING**.


### PR #295 — SharedPreferences failed-commit test semantics (2026-11-11)

PR #295 merged as `93bd3b4a2f17007ddc7d0159c72bdbd0da7868a1` (head `a069832db3bd57ef97a895ba3a3faaef7182e0bf`). This is a **test-only** correction to S06-H4, not a production-source change. `FakeSharedPreferences.commit()` now applies the in-memory editor update before returning its configurable disk-write result, matching Android SharedPreferences behavior. The failed-commit regression asserts that `DuressPinPersistence.persist()` returns false; it deliberately does not assert that the process-local preference map rolls back, because Android does not promise that. The production contract remains: a false commit must never be reported as a durable save, and the UI must stay on its generic failure path.

Exact-head CI [42955598180](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42955598180) passed **10/10 jobs**; Security [42955598156](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42955598156) passed **3/3 jobs**. PR #295 changed only `DuressPinPersistenceTest.java` and `FakeSharedPreferences.java`; no production behavior, dependency, runtime configuration or deployment setting changed.
