# Comprehensive Remaining Security Work — Independent Claude Review

**Prepared:** 2026-11-07  
**Repository:** https://github.com/kumarclaude4-hash/DuoFatass  
**Reviewed baseline:** `main` at `fa5431a6ea3cc7af7c4ba05b9b2c1c617c2c7101` (PR #248 merge)  
**Purpose:** Source-linked, evidence-aware inventory of incomplete security work, with precise next actions and acceptance criteria for an independent Claude review.

> This is a handoff report, not a new audit or sign-off. The canonical finding register is [`BUG_TRACKER.md`](../BUG_TRACKER.md). `FINAL_SIGNOFF.md` remains **PENDING — NOT APPROVED FOR PRODUCTION SIGN-OFF**. Do not invent finding IDs, change dispositions without evidence, infer owner approval, or mark a finding Fixed merely because a PR merged.

---

## 0. Instructions to Claude

Start from the exact reviewed `main` SHA above. Independently read the current source, tests and referenced documents; this report is a navigation map, not a substitute for code review.

For every item:
1. Confirm the current implementation and tests at the reviewed baseline.
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

## 1. Baseline and already-completed work

PR #248 merged at `fa5431a6ea3cc7af7c4ba05b9b2c1c617c2c7101`: https://github.com/kumarclaude4-hash/DuoFatass/pull/248

Exact PR head `787ed4a70008d61f819cd65a8c6424573d7f064f` passed:
- CI [42331424519](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42331424519): **10/10 jobs**.
- Security Scan [42331424496](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42331424496): **3/3 jobs**.
- Android release-log AST self-test/full scan: **204 production Java files, 407 release-surviving Log.w/e/wtf calls, zero violations**.

PR #248 enhanced `scripts/AndroidReleaseLogPrivacyPolicy.java` with conservative method-local tracking: local aliases/reassignment, concatenation, StringBuilder/StringBuffer, common Map/Bundle/JSON/builder flows, both log tag and message sinks, and positive/negative fixtures. This is **not** symbol-resolved whole-program/interprocedural taint analysis. See [S10-N2 tracker row](../BUG_TRACKER.md) and [session report](sessions/SESSION-S10-N2-METHOD-LOCAL-TAINT-2026-11-07.md).

Other relevant merged work includes PRs #222, #225–#228, #231, #236–#243, #244, #246 and #248. Consult the relevant tracker rows before touching those areas. Do not reimplement merged fixes.

A merge and green CI do **not** prove: production deployment, live Firebase/Render/Cloudflare configuration, credential rotation, live R2/B2 cron migration, physical-device acceptance, owner risk acceptance, governance enforcement or production sign-off.

---

## 2. Executive inventory

| Finding | Current status | Remaining gate |
|---|---|---|
| S03-M3 | Partial | Owner risk decision or versioned protocol |
| S05-M3 | Partial | Multi-instance staging + Firestore rules/index/TTL read-back |
| S05-H2 | Partial | Confirm invite-only product contract + full staging onboarding |
| S06-L4 | Partial | Staging retry/recovery race + Firestore config + deferred device matrix |
| S07-L2 | Partial | Supported deterministic native-key destruction or approved residual |
| S08-H2 | Partial | Screenshot/recording/recents device acceptance |
| S08-H5 | Partial | Real Keystore migration/recovery + support path |
| S07-M1 | Partial | Same migration acceptance as S08-H5; keep both tracker rows |
| S08-L3 | Partial | 4/5/6-digit keypad, debounce, backspace and duress device acceptance |
| S08-L4 | Partial | Lock-cover visual/touch/unlock/sign-out device acceptance |
| S10-N1 | Partial | App Check monitoring, staged rules and controlled enforcement |
| S10-N2 | Partial | Interprocedural residual review + release-logcat acceptance |
| S10-N3 | Partial | Isolated live Worker/R2/B2 scheduled migration acceptance |
| S11-H1 | Partial | Real predictive-back edge gesture and OEM matrix |
| S11-M1 | Partial | Call minimize/timeout/notification-return lock acceptance |
| S02-I2 | Partial | Render/Worker secret pairing read-back + Durable Object proof |
| S02-I3 | Partial | Firebase revoked-token integration across all 12 routes |
| SC-12 | Open | Required checks/reviews/tags/production environment governance |

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

## 3.4 S06-L4 — Account-lock worker retry/recovery

**Status:** Partial; implementation and emulator/JVM regressions merged, live acceptance pending.  
**Code:** `AccountLockWorker`, `DuressManager`, `WipeHelper`, server nonce/lock transaction, WorkManager and Firestore configuration.  
**Implementation:** PR #225.

Only HTTP 200/204 confirms remote lock. Permanent 4xx fails without clearing durable intent. Transient 408/425/429/5xx/network failures use bounded exponential retry (`MAX_ATTEMPTS = 7`). Nonce consumption, account lock and idempotency receipt are transactionally coupled. Lost-response replay acknowledges original commit without re-locking an account an administrator has unfrozen. Recovery drains existing nonce before replacement; compare-and-clear prevents stale workers deleting newer state.

**Staging cases:** lost response after commit; administrator unfreezes before retry; retry exhaustion; permanent invalid/expired nonce; reboot with pending work; production Firestore TTL/index/rules/service-account read-back.

**Device cases, only after feature-complete:** wipe, reboot, sign-in, network loss/recovery and lock reconciliation. Include duress and normal account paths.

**Residual:** if device never reconnects or same account never authenticates, remote lock cannot be guaranteed; local intent remains explicit. Keep Partial until staging, configuration and later device evidence exists.

## 3.5 S07-L2 — Deterministic native key destruction

**Status:** Partial; app cache clearing and native zeroize-on-drop reviewed; deterministic destruction unresolved.  
**Code:** `SeedPhraseHelper.clearDerivationCache()`, `WipeHelper.eraseLocalData()`, pinned libsignal 0.54.1 Java/Rust bindings.  
**Evidence:** [S07-L2 session](sessions/SESSION-S07-L2-LIBSIGNAL-BOUNDARY-2026-11-06.md), PR #240 [run 42172475903](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42172475903).

App cache references are cleared on wipe. Pinned libsignal Java `ECPrivateKey.finalize()` calls native destroy; Rust owns x25519-dalek `StaticSecret`, whose enabled zeroize feature wipes on drop. Java wrapper exposes no deterministic close/destroy API. Native destruction is GC/finalizer-timed; cache clearing does not synchronously wipe every clone/transient copy at wipe return.

**Next:** verify pinned upstream source; investigate a supported deterministic API in a compatible upstream version or upstream contribution; assess clone/thread/use-after-destroy semantics; retain cache-clear regression tests. Do not use reflection/unsafe hacks or claim deterministic zeroization. If no safe API exists, prepare explicit owner residual-risk decision.

**Acceptance:** tested supported deterministic destruction with upstream/API evidence, or approved residual naming GC-timed limitation. Keep Partial.

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

## 3.10 S10-N1 — Firebase App Check rollout

**Status:** Partial; Android provider wiring and monitoring helper exist, enforcement not enabled.  
**Code:** `DuoShieldApp.java`, `app/build.gradle`, `firestore.rules` helper `appCheckVerified()`.  
**Rules evidence:** S3-15b emulator 186/186; helper is deliberately not wired into allow clauses yet.

**Operator rollout:**
1. Confirm released client registers Play Integrity provider before Firebase SDK use; debug provider is debug-only.
2. Review Firebase App Check monitoring for Firestore/Storage; do not enforce blindly.
3. Measure adoption/missing-token traffic across supported clients/install channels.
4. Agree minimum adoption and support/exception policy.
5. Stage rules adding `request.app != null` to exact affected allow clauses; run positive/negative tests including missing App Check.
6. Deploy compatible clients first, then rules, then console enforcement with rollback.
7. Read back deployed rules version and console state.

App Check is defense in depth, not a trust boundary against compromised clients. **Acceptance:** redacted metrics, staged rules tests and deployment read-back. Keep Partial.

## 3.11 S10-N2 — Release-log data-flow residual

**Status:** Partial; PR #248 method-local analyzer merged; interprocedural residual and logcat acceptance remain.  
**Code:** `scripts/AndroidReleaseLogPrivacyPolicy.java`, release-log workflow and production Java logging sites.  
**Evidence:** CI [42331424519](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42331424519), Security [42331424496](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42331424496), [session record](sessions/SESSION-S10-N2-METHOD-LOCAL-TAINT-2026-11-07.md).

Current analyzer tracks local aliases/reassignment, concatenation, StringBuilder/StringBuffer, common Map/Bundle/JSON/builder patterns and both tag/message sinks. Sanitizers/projections are narrow and owner/type scoped.

**Remaining codeable work:** assess bounded method summaries for app-owned helpers; resolve owner/signature rather than method-name-only assumptions; simple fields only where reliable; fixtures for tainted parameter pass-through, sanitized helper return, tainted helper return, overloads, recursion limits and simple fields. Explicitly document reflection/native/unknown callback/third-party flows. Keep allow-lists exact and source-backed. Do not claim whole-program analysis.

**After feature-complete:** build release/minified APK, exercise identity/media/call/lock/migration/error flows, capture sanitized logcat and inspect for raw UID, peer identifiers, device fingerprint, tokens, media keys, user metadata and exception payloads.

**Acceptance:** fixtures + workflow evidence + residual review + post-feature-complete logcat. Keep Partial.

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
| S10-N1 | App Check metrics, staged rules, console read-back | Blind enforcement |
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
1. S10-N2: assess bounded method summaries for app-owned helpers; implement only if symbol/owner resolution is reliable and tests prove behavior. Otherwise document technical limit/residual.
2. S10-N1: prepare staged rules/test diff, but do not deploy/enforce.
3. S05-M3/S05-H2/S06-L4: add missing deterministic harness/tests only where source review proves a gap.
4. S10-N3: improve deterministic tests/observability only for concrete gaps.
5. Update tracker/session/handoff in the same PR as each change.

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
4. [Prioritized next-work report](NEXT_WORK_REPORT_2026-11-07.md) — S10-N2 residual and staging backlog.
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
