# Remaining Work & Acceptance Report — 2026-11-05

## Scope and status

This report accompanies PR #222, **Security hardening: Firestore lifecycle tests and typed link previews**.

The PR adds:
- Firestore Emulator integration coverage for durable admin-session behavior across independent store instances, including validation, revocation, concurrent global revocation, opaque stored identifiers, idle expiry, and absolute expiry.
- A Firestore-backed invite lifecycle store with transactionally staged audit records, plus emulator tests for creation, token non-persistence, concurrent one-time redemption, pseudonymized audit linkage, pagination, revocation, and expiry.
- CI wiring to execute the Firestore rules suites and server Firestore integration suites in the same emulator process.
- Typed Android link-preview outcomes, backward-compatible parsing, fail-closed handling of unknown statuses, safe preview rendering, and JVM tests.
- Server-side link-preview failure-classification tests that avoid returning raw network exception details.

## Evidence available at review time

- PR #222 is open against `main`; its head branch is `security/firestore-session-integration-2026-11-05`.
- The PR's Firestore Rules Tests workflow run #57 completed successfully. Its job installed both dependency sets, ran the static App Check guard, and ran the Firestore Emulator rules + server integration suites successfully.
- The current `main` head observed during this review is commit `20d8c9faaddbb1969eeddc9ad056692d14193255` (merge of PR #221).
- No PR review submissions or inline review comments were returned by the GitHub API at the time of inspection.
- Multiple Vercel deployment notifications on this PR report the Vercel free-tier deployment quota/rate limit (more than 100 deployments in 24 hours). This is an external deployment-provider limit, not a failed GitHub Actions test.

A green emulator workflow is meaningful code-level evidence; it is not proof of production configuration or live-user acceptance.

## Remaining work — ordered by owner and priority

### P0 — Production Firestore configuration and rollout (operator / Firebase project owner)

1. Verify the production Firestore security rules actually deployed match the reviewed repository revision. The emulator workflow does not deploy production rules.
2. Review and deploy any required Firestore composite indexes and TTL policies for the new session/invite collections. Confirm exact collection/field names against the production project before applying.
3. Verify service-account permissions and runtime configuration for the Admin SDK paths used by session and invite stores.
4. Confirm production TTL behavior empirically; TTL deletion is asynchronous and must not be treated as the authorization boundary. Application-side expiry checks must remain authoritative.
5. Run a controlled staging smoke test for session creation, cross-instance validation, logout/revocation, global revocation, invite issuance, one-time redemption, pagination, and invite revocation/expiry.

**Acceptance evidence required:** deployed rules revision, index/TTL deployment status, sanitized staging test results, and confirmation that expiry/revocation decisions are enforced by application logic.

### P0 — Secrets and environment verification (operator)

1. Rotate any credentials identified by the security remediation plan, including Firebase/service credentials and deployment tokens where applicable.
2. Verify required production environment variables are present, correctly scoped, and not exposed in client bundles or logs.
3. Confirm rotation/revocation of old credentials and record the completion date without putting secret values in the repository.

**Acceptance evidence required:** operator attestation or secret-manager audit evidence. Never commit credential values.

### P1 — Invite route and identity-claim acceptance (developer + reviewer)

1. Perform a focused review of the invite-store integration boundary in `server/index.js`: route authorization, transaction boundaries, identity-claim behavior, and error mapping.
2. Confirm invite redemption remains atomic when concurrent requests target the same token and that no raw invite token is persisted or emitted in logs.
3. Confirm audit records are written in the same transaction as invite lifecycle mutations and contain only pseudonymized user linkage.
4. Add/retain route-level tests for the full HTTP request path, not only store-level emulator tests, covering unauthorized callers, malformed/expired/revoked tokens, duplicate redemption, and identity-claim conflicts.

The store-level emulator suite passes in CI, but it does not replace end-to-end HTTP route acceptance or a human review of the route/claim transaction boundary.

### P1 — Android device acceptance (developer / QA)

1. Run the Android JVM test suite containing `LinkPreviewStatusTest` and record the Gradle task/result.
2. Build and install on at least one supported physical Android device.
3. Exercise successful metadata, no metadata, unsupported content, blocked/timeout/upstream-error responses, malformed status values, older servers that omit the status field, and oversized/malformed responses.
4. Verify recycled message holders do not display stale previews and that failed/blocked results do not leave stale cards visible.
5. Verify connection cleanup and UI behavior under slow/interrupted requests.

The typed status mapping has JVM tests in the PR; physical-device acceptance is not established by the Firestore workflow.

### P1 — Deployment provider quota

Vercel PR deployment notifications report a free-tier quota limit (>100 deployments per 24 hours). Re-check deployment after the provider's quota window resets or use an authorized deployment path with available quota. Do not interpret these provider notifications as application test failures, and do not repeatedly trigger deployments while the quota is exhausted.

### P2 — Repository governance and operational readiness

1. Re-check `main` branch protection / rulesets and require the relevant CI checks before future merges. Earlier review found `main` unprotected; this should be re-verified and corrected by a repository administrator.
2. Confirm security scanning, dependency update monitoring, and deployment rollback procedures remain enabled and owned.
3. Maintain an incident-ready process for rotating the audit-log pseudonymization pepper and document the intended correlation window/retention trade-off.

## What is complete in this PR vs. what is not

| Area | Evidence / status |
|---|---|
| Firestore rules + App Check emulator tests | CI workflow passed |
| Admin-session Firestore integration tests | CI workflow passed |
| Invite-store Firestore integration tests | CI workflow passed |
| Link-preview server classification tests | Added in PR; included in server test suite (see CI result) |
| Android typed-status unit tests | Added; run Android JVM/Gradle task separately |
| Production rules/indexes/TTL | Not verified or deployed by this PR |
| Live Firebase/staging acceptance | Not performed |
| Credential rotation and production env audit | Operator evidence required |
| Full invite HTTP route + identity-claim acceptance | Human review and route-level acceptance remain |
| Physical Android device QA | Not performed |
| Vercel deployment | Blocked by provider quota notification |
| Main branch protection | Previously observed absent; re-check and configure |

## Release decision

**Code-level merge candidate; not a production security sign-off.**

The passing emulator workflow supports merging the code change subject to the repository's merge policy. Production rollout must remain gated on the P0 operator actions and the P1 route/device acceptance above. Do not claim the product is production-secure solely because the PR workflow is green.

## Suggested closure checklist

- [ ] Production Firestore rules reviewed and deployed
- [ ] Required indexes and TTL policies verified/deployed
- [ ] Production service-account permissions and environment variables verified
- [ ] Credential rotation completed and old credentials revoked
- [ ] Staging session/invite lifecycle smoke tests passed
- [ ] Invite HTTP route and identity-claim transaction reviewed; route tests recorded
- [ ] Android JVM tests passed and physical-device QA recorded
- [ ] Vercel deployment retried after quota reset (if preview deployment is required)
- [ ] Main branch protection/rulesets configured and required checks enforced
- [ ] Security owner signs off on production rollout


---

## Post-merge and Signal-storage closeout update — 2026-11-05

The original report above was written before PR #222 merged. The following evidence supersedes its pre-merge status snapshot:

- PR #222 is merged to `main` as `c2bb2b7d0c6788f7e776e333331d044ae9788569`.
- Post-merge CI for that commit passed Android unit tests, Espresso instrumentation, lint, debug APK build, server/Worker tests, Firestore rules/session integration tests, CodeQL, Gitleaks, SBOM generation, the storage Worker build and the Vercel deployment.
- PR #223 adds fail-closed Signal identity-trust recovery, strict canonical stored-key decoding, fingerprint-pointer integrity checks, corrupt-ratchet regression tests and a release-log privacy regression. On code head `bbc3797d1acaea37e86e45871b0d20557bfb9e95`, all 13 reported PR checks passed, including unit tests, lint, Espresso and debug APK build.
- The canonical tracker has been reconciled for S04-I3, S05-H2, S05-M3, S07-M2, S07-L4 and S10-N2. S05-H2 and S05-M3 remain partial because route/staging/production acceptance is still open; S10-N2 remains partial because the wider logging review is source-based rather than a complete AST-level audit.

The Vercel quota errors reported on PR #222 were transient pre-merge notifications; the merged main commit subsequently reported a successful Vercel deployment.

**Still not production sign-off:** production Firestore deployment, service-account/environment validation, credential rotation, full HTTP invite-route acceptance, staging tests, physical-device QA, signed/minified release acceptance and branch-protection verification remain separate gates. The 13 green checks on PR #223 are evidence for the tested code paths only.


---

## S06-L4 duress-lock reliability implementation — 2026-11-05

PR #225 adds a code-level closeout for the previously partial S06-L4 finding:
- Android treats only 200/204 as proof of a remote lock; permanent rejection is a real WorkManager failure and retains the durable intent.
- Retry attempts are bounded to seven within the one-hour nonce lifetime.
- Server nonce consumption, account lock and idempotency marker are transactionally coupled. Lost-response retries acknowledge the original commit without re-locking after an administrator unfreezes the account.
- Consumed receipts are retained during outstanding-nonce pruning until TTL, and the client drains a pending nonce before requesting a replacement.
- Same-UID refresh and compare-and-clear guards prevent cross-account retargeting and late workers deleting newer recovery state.

The new Firestore emulator/JVM/wiring regression suites and GitHub Actions results are the merge gates for this PR. **Remaining after code merge:** staging the lost-response/admin-unfreeze race, physical-device testing of wipe/reboot/sign-in/recovery, and production Firestore TTL/service-account/rules verification. A device that never regains connectivity and never authenticates again cannot be remotely locked; the local durable intent stays explicit and no success is fabricated.


---

## Consolidated implementation and acceptance update — 2026-11-06

This addendum supersedes earlier **code-work** status statements where later merged PRs provide stronger evidence. Historical sections above are retained as an audit trail; production and physical-device acceptance remain separate gates.

### Predictive-back runtime coverage — PR #237

PR #237 adds a focused Android 15 (API 35) instrumentation test for the security-critical back guard:

- `DevicePinGateActivity` registers `BackPressGuard.block(this)`.
- `DevicePinGateInstrumentedTest.devicePinGate_consumesPlatformBackDispatch` sends `KEYCODE_BACK` through Android's input dispatcher and asserts that the gate remains visible, keeps window focus, and is not finishing.
- The broad existing Espresso suite remains on API 30. A separate API 35 emulator job runs exactly the focused regression method, verifies exactly one test executed, and retains the Android test report for 14 days.
- The API 30 AVD cache is intentionally isolated from the API 35 job. The cache key remains scoped to the API 30 emulator, and the workflow documents when that key must be rotated. The API 35 job now explicitly asserts that the booted emulator reports SDK level 35 before executing the test.

**Verification recorded on the reviewed PR head `6f586bf72298155619d26345a52ad5b58d9e933e`:** 13 GitHub Actions/security checks passed, including API 30 Espresso, the focused API 35 instrumentation job, Android unit tests, lint, debug APK, minified release R8, Server + Worker tests, CodeQL, Gitleaks, SBOM, Gradle wrapper validation, libsignal JAR integrity and the Cloudflare Worker build. The separate Vercel preview status failed because the provider reported its free-tier deployment quota (>100 deployments in 24 hours); this is not a test failure. Cloudflare reported a successful production Worker deployment for this commit. The workflow hardening/report reconciliation commit is a separate head and must pass its own CI before merge.

**Acceptance boundary:** `KEYCODE_BACK` instrumentation exercises platform key dispatch, not a physical predictive-back edge gesture. It targets `DevicePinGateActivity`; it does not establish identical runtime behavior on all seven guarded activities or on OEM gesture implementations. Keep S11-H1 Partial until physical/API-level acceptance covers an edge gesture on `LockScreenActivity` and the supported device matrix.

### Code-level changes merged since the earlier report

The following PRs are merged to `main` and should no longer be listed as unimplemented code tasks:

| PR | Merged implementation |
|---|---|
| #225 | Duress-lock worker failure semantics, bounded retry, transactionally coupled nonce-consumption/idempotency and recovery-state protection |
| #226 | Invite list/create/revoke/validate HTTP routes exercised through the production-shared router and Firestore transactions |
| #227 | Production `POST /mintToken` HTTP handler with proof-of-possession, replay/forgery rejection and Firestore identity/invite claim coverage |
| #228 | Exact value-and-type read-back for legacy preference migration before plaintext cleanup |
| #230 | Opaque lock-screen transition cover that prevents underlying authenticated content flashing during lock handoff |
| #231 | Dedicated minified release R8 build and mapping-output verification in CI |
| #233 | Security tracker reconciliation for verified Android security gates |
| #236 | App-wide screenshot/screen-recording policy via Activity lifecycle callbacks, including direct `AppCompatActivity` screens |
| #238 | Sensitive clipboard marking for every app-owned user-data clipboard export, with regression coverage |

PRs #234 and #235 are closed predecessors of #237; PR #239 is closed as superseded by the already-merged #227 work. At the review snapshot, #237 was the only open PR in this repository.

### Current remaining work — not satisfied by repository CI alone

#### P0 — Production Firebase / Firestore and credential operations (operator)

1. Verify production Firestore rules match the reviewed repository revision; the emulator suites do not deploy production rules.
2. Verify and deploy the required Firestore indexes and TTL policies for admin sessions and duress-lock nonce/receipt collections. Application-side expiry checks must remain authoritative because TTL deletion is asynchronous.
3. Verify production Admin SDK service-account permissions and required environment-variable names/scopes without exposing values in Git, logs or client bundles.
4. Rotate and revoke any previously exposed Firebase, storage, Worker or deployment credentials identified by the security runbooks; record operator evidence, never secret values.
5. Run staging acceptance for admin-session creation/validation/revocation/global revocation and duress-lock lost-response/admin-unfreeze replay.

#### P1 — Android and end-to-end product acceptance (QA / developer)

1. **S11-H1:** physically perform predictive-back edge gestures on `LockScreenActivity` on supported Android/OEM devices; cover the other no-skip gates in the device matrix. PR #237's API 35 key-dispatch regression is useful runtime evidence but is not gesture-level sign-off.
2. **S08-H2:** verify screenshot, screen recording and recents-thumbnail behavior on fresh install and after explicitly opting in, across supported Android versions. PR #236 closes the app-wide lifecycle wiring gap; physical acceptance remains.
3. **S08-H5 / S07-M1:** test upgrade/recovery with real Android Keystore and EncryptedSharedPreferences, including an install whose legacy plaintext cannot be migrated. Retained source data must not be manually deleted.
4. **S06-L4:** test duress wipe, reboot, sign-in and recovery on physical devices, including network loss and the server lost-response/admin-unfreeze race.
5. **S05-H2:** confirm invite-only onboarding is the intended product contract and exercise the complete Android onboarding flow against staging; the invite and `/mintToken` HTTP/emulator code-coverage gaps are addressed by #226/#227.
6. **S02-I3 / S02-I2:** execute staging Firebase revoked-token acceptance (old token rejected, fresh token accepted) and verify the Render-to-Worker Durable Object limiter credentials/configuration across instances.
7. **S10-N1 / S10-N2:** complete App Check monitoring-to-enforcement rollout/operational verification and the broader logging privacy review; the existing source and targeted regression coverage do not constitute a full production telemetry audit.

#### P2 — Governance and deployment operations

- The active repository ruleset `Hello` (ID `26629873`) now requires PRs and blocks deletion/non-fast-forward updates on the default branch, but currently requires **zero approvals** and has **no required status checks**. An authorized administrator must configure required CI/security checks, approval/CODEOWNERS policy, immutable release-tag rules and a protected `production-release` environment, then verify the effective settings.
- The classic branch-protection endpoint returned 403 to the connected integration; a protected production release environment was not independently verified.
- Vercel preview deployments may remain blocked until the provider quota window resets or the account limit changes. Avoid retry loops while the quota is exhausted. The Cloudflare Worker deployment for the reviewed PR head succeeded.

### Release decision

**Code-level work in PR #237 is a merge candidate after the final updated branch checks pass. This report is not a production security sign-off.** Production Firebase/Firestore configuration, secret rotation, staging tests, physical-device acceptance and governance enforcement remain explicit release gates. Do not infer that CI green means the product is production-secure.


---

## Source-level closeout update — 2026-11-06

PR #237 is merged to main at 745f1601448a2b35ffe4ff024b8cde0e9454ebcb. Post-merge CI run [42162494191](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42162494191) passed **9/9 jobs**, including Server + Worker tests, Android JVM unit tests, lint, debug APK, Espresso, API 35 dispatch, minified release R8, vendored libsignal integrity and Gradle wrapper validation. Security scan [42162494131](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42162494131) also succeeded.

The current follow-up branch is security/remaining-code-and-agent-handoff-2026-11-06. It adds source-wiring tests for group-key authorization order, wipe/cache clearing, deep-link/clipboard/ContactManager Account ID validation, PIN metadata cleanup and Turkish-locale BIP39 derivation. It corrects an outdated no-toolchain comment in PinLengthNotPersistedTest. It also introduces LogRedact.metadata() and LogRedact.error(), extends redaction across multiple Android release-log call sites (call signaling, FCM/TURN, media playback/export/storage/restore, backup, outbox, duress/PIN, notifications, Signal key/session storage and app startup), and adds helper/call-site privacy tests. **These new branch changes have not yet passed their own CI**; do not promote S07-L1, S07-L3, S08-L1 or S10-N2 based only on their presence.

The root tracker has been reconciled from its 116 current rows: **83 Fixed, 20 Partial, 1 Open, 12 Accepted** at the pre-follow-up-CI snapshot. This replaces the older 2026-11-04 74/29/1/12 summary, which remains historical. S07-L2 stays Partial because nulling the Java cache reference does not prove libsignal internal key-byte zeroization. S03-M3 remains Partial: the 10-minute TTL and reusable READ/WRITE tokens need an owner-approved residual-risk decision or a versioned client/server redesign. S05-M3 and S02-I2/S02-I3 still need staging/live operator evidence. S10-N3 has two deterministic scheduled-handler race tests but still lacks isolated live Cloudflare/R2/B2 runtime evidence.

**Device-testing policy:** physical-device/manual app-flow acceptance is deliberately deferred until the owner declares the app feature-complete. Do not start that matrix early. Existing automated CI emulator tests remain required. A detailed current agent worklist is in [AGENTIC_WORK_REMAINING_2026-11-06.md](../security-remediation/AGENTIC_WORK_REMAINING_2026-11-06.md).

No production credentials, Firestore rules/indexes/TTL, Render configuration, Worker secrets, production deployment or Android release were changed by this branch. Vercel preview remains a separate quota/rate-limit concern. Production sign-off remains pending.

---

## Post-PR #240 merge and verification — 2026-11-06

PR #240 ([source-level closeout and agent handoff](https://github.com/kumarclaude4-hash/DuoFatass/pull/240)) was squash-merged to `main` at `f3694eff36ecd54e2112c253cb62d96523478de7` on 2026-11-06.

Validation on the PR head `32a32be06eb426fb7b1c4c22452be80b508e0c67`: CI run [42172475903](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42172475903) passed **9/9 jobs** (server/Worker tests, Android JVM tests, lint, vendored libsignal integrity, Gradle wrapper integrity, debug APK, minified R8, Espresso and API 35 predictive-back). Security scan [42172475877](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42172475877) passed **3/3** (CodeQL, Gitleaks and SBOM).

The root tracker was reconciled from the individual finding rows after this evidence: **116 total; 86 Fixed, 17 Partial, 1 Open and 12 Accepted**. S07-L1, S07-L3 and S08-L1 are now Fixed because their JVM policy/call-site regressions executed successfully in PR CI. S07-L2 remains Partial because nulling the Java cache reference does not prove byte-level zeroization inside libsignal. S08-L3 remains Partial because keypad timing, interrupted entry and duress edge cases require app-flow acceptance, intentionally deferred until the app is feature-complete. S10-N2 remains Partial until a complete AST-aware audit/guard covers all release-surviving `Log.w`/`Log.e` call sites.

Other source/agent residuals remain explicitly open in `security-remediation/AGENTIC_WORK_REMAINING_2026-11-06.md`: S03-M3's 10-minute TTL/reusable READ-WRITE capability token policy requires owner ratification or a tested protocol redesign; S10-N3's expanded Cloudflare/R2/B2 failure-injection and isolated live-runtime verification; S05-M3 staging/outage/restart acceptance; S02-I2 Render/Worker shared-limiter pairing; S02-I3 live revoked-token staging acceptance; S05-H2 invite onboarding acceptance; and a documented/supported libsignal key-destruction boundary.

Post-merge mainline security scan [42173185246](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42173185246) is successful. Mainline CI run [42173185263](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42173185263) was still running at the time of this addendum; check its final conclusion before final release reporting.

**Device testing rule:** no additional manual/physical-device acceptance was performed. The owner explicitly deferred it until the app is feature-complete. Existing automated CI emulator jobs are still required and passed on the PR head. Only after the owner declares feature completion should the device matrix in the agent handoff be executed.

**Production sign-off remains PENDING.** No production credentials, Firestore rules/indexes/TTL, Render configuration, Worker secrets, production deployment or Android release were changed by PR #240. The Vercel preview provider's build-rate limit remains an independent deployment-status issue.
---

## Additional agentic source work — 2026-11-06 (PR branch; CI pending)

Follow-up branch `security/agentic-followups-r2b2-logging-limiter-2026-11-06` adds the following source-level work; do not promote the related tracker dispositions until its PR CI passes:

- **S10-N3:** Four deterministic R2/B2 failure-injection tests cover changed ETag, transient R2 HEAD failure, B2 PUT failure and failed compensating B2 DELETE. A failed HEAD is no longer treated as proof of object deletion: the R2 source remains and its bytes stay charged until a later cron retry. Failed compensation now emits a sanitized status/network diagnostic; migration warnings omit raw media keys.
- **S02-I2:** A static route-inventory regression asserts all 12 token-authenticated route handlers call `checkAuthRateLimit()` with their dedicated endpoint bucket, and that `AUTH_RATE_LIMITS` matches the inventory exactly. Existing `authRateLimitStore.test.js` covers bounded local fallback, user/endpoint isolation, durable failure and degraded-mode reporting. Live Render/Worker secret pairing remains an operator task.
- **S03-M3:** Added `security-remediation/S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md`, documenting the current 10-minute TTL / reusable READ-WRITE residual, alternatives, recommendation and explicit pending owner approval. No owner risk acceptance is presumed.
- **Worker log privacy:** storage error paths now omit raw object keys and exception messages; error class/status remains available for diagnosis.
- **S05-M3:** Re-read existing tests: independent-instance creation/validation/revocation, global revocation, concurrent revocation and Firestore outage propagation are covered. Deployed Firestore/staging restart/outage acceptance remains separate.

PR-head CI/security must execute the added Worker and server route-guard tests before the tracker can describe them as verified. The full compiler/AST-aware audit of every Android release-surviving log call remains outstanding under S10-N2. Production Firestore/App Check, credential rotation, Render/Worker pairing, staging and release governance are still operator gates. Device acceptance remains deferred until app feature completion.