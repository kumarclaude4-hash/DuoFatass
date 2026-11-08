# FINAL SIGN-OFF

> Status: **PENDING — NOT APPROVED FOR PRODUCTION SIGN-OFF.**
> Updated 2026-11-08 after PR #271. The current evidence snapshot is dated 2026-11-08; older dated sections below are historical. This document is the release/sign-off gate, not a progress claim.

## Current evidence snapshot — 2026-11-08

The canonical register is [`../BUG_TRACKER.md`](../BUG_TRACKER.md). Its row-level roll-up remains **116 findings: 86 Fixed, 17 Partial, 1 Open, 12 Accepted** (Critical 4; High 31; Medium 27; Low 31; Informational 23). S06-H4 is Fixed. S06-H5 remains Partial: PR #265 added a UID-scoped successful/failed attempt throttle and unified rejection response, but bounded probing is not proof that every side channel is impossible.

Current `main`: `87e558c25bd810dafb8b47d97e52c2022a4a94fe` (PR #269 merged).
- PR #267 exact-head CI [42443323887](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323887): passed Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified release R8, Server + Worker tests, release-log AST and dependency integrity.
- PR #267 Security [42443323988](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323988): passed CodeQL, Gitleaks and SBOM.
- PR #267 Firestore Rules [42443323886](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323886): passed.
- PR #267 Firestore Rules/server transaction integration [42443323962](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323962): passed; log reported 200 Rules tests and 6 server transaction tests.
- PR #271 exact-head CI [42448400756](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400756) passed 10/10 jobs: Android unit, lint, debug APK, minified R8, Espresso, API 35 predictive-back, Server + Worker tests, release-log AST, vendored libsignal and Gradle wrapper integrity. Security [42448400818](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400818) passed CodeQL, Gitleaks and SBOM. PR #271 head `efceb00bc7fac0361804f98cf84458e69a75c0c3` merged as `87e558c25bd810dafb8b47d97e52c2022a4a94fe`. S07-L2 remains Partial; native destruction is finalizer-timed and complete synchronous erasure is not claimed.
- PR #270 — security handoff reconciliation after #269; merged as `1a425ad0aa2e7c36fa66a2afe4e46d85e78ee514`.
- PR #271's Vercel preview status separately reported a build-rate-limit failure; this was not a failing GitHub Actions CI or Security job and is not deployment acceptance.
- PR #269 exact-head CI [42446136767](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136767) and Security [42446136770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136770) passed; merged as `afdf2463c496f14a5c33ee5694fc47eff97d5e3d`. S10-N2 remains Partial; the analyzer is not whole-program proof and physical release-logcat acceptance remains deferred.
- Post-merge main Security [42445582762](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582762), Firestore rules [42445582759](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582759) and Firestore Rules Tests [42445582747](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582747): passed.
- Post-merge main CI [42445582763](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582763): passed on the exact main SHA, including Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified release R8, Server + Worker tests and integrity checks.

Recent merged work:
- PR #261 — explicit active-state/future-expiry fail-closed checks and audited invalid invite-state revocation (S05-H2).
- PR #264 — invalid invite rows expose an audited revoke action (S05-H2).
- PR #265 — generic PIN-change failure response and durable attempt throttle (S06-H5); CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed.
- PR #267 — correct Firebase App Check enforcement boundary (S10-N1); merged as current main. Firestore Rules no longer reference unsupported `request.app` or `appCheckVerified()`; emulator tests load the committed rules verbatim. Actual App Check enforcement remains a Firebase service-layer operator/staging gate.
- PR #266 was intentionally closed unmerged; its documentation changes are not part of main.

There are no open code PRs at this snapshot after PR #271. Historical PRs #253/#254/#258 were closed; the invite lifecycle scope from #253 was superseded by merged #261, and the range-read allowance from #254 was superseded by merged #256. Do not reopen old branches without fresh source review.

This evidence snapshot is not production sign-off. No live production deployment, credential rotation, Firebase Console enforcement change, provider-console read-back, historical Firestore UID migration, isolated live R2/B2 cron acceptance or physical-device acceptance is claimed here.


## Historical evidence snapshot — 2026-11-04

The canonical finding register is [`../BUG_TRACKER.md`](../BUG_TRACKER.md). Its row-level roll-up
was independently recounted on 2026-11-04: **116 findings: 74 Fixed, 29 Partial, 1 Open,
12 Accepted**. Severity buckets are Critical 4/4/0/0/0, High 31/24/7/0/0, Medium 27/19/7/0/1,
Low 31/18/11/1/1, and Info 23/9/4/0/10 (Total/Fixed/Partial/Open/Accepted).

Merged to `main` on 2026-11-04:
- #201 — screenshots blocked by default.
- #202 — reject revoked Firebase ID tokens at the affected authenticated endpoints.
- #203 — require AAD metadata on new group ciphertext.
- #204 — release publishing restricted to immutable version tags / approved environment.
- #205 — stop persisting decrypted message media; #206 was closed as a superseded duplicate.
- #207 — server-managed one-time pre-key consumption.
- #208 — media capability-token lifecycle and fail-closed delete replay protection.

PR #200 (`ci: fix Espresso SDK setup`) was also merged. Its CI run
[41766375329](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41766375329)
passed all jobs, including the emulator-backed **Instrumented tests (Espresso)** job; that job
reported **2 Espresso tests executed**.

Current `origin/main` HEAD verified on 2026-11-04:
`45363ae8f1be3b9bf2dbdfab861580d4f3b6038d`.
Both current workflows completed successfully:
[CI run 41842025684](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41842025684)
and [Security scan run 41842025761](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41842025761).

Firestore Rules catch-up gate S3-15b is **complete: 186/186 emulator tests passed**. The Android
catch-up gate S3-19b is now **verified on main CI**: lint, unit tests, debug APK build and the
emulator-backed Espresso job all passed. This closes the previously documented AND-lane CI gap;

## Sign-off criteria

- [ ] **Every finding has one current disposition.** Use row-level state in `../BUG_TRACKER.md`;
      independently recount section rows and reconcile the summary before signing.
- [ ] **All Critical and High findings are fixed or explicitly accepted with a ratified rationale.**
      Re-check every such row and its current source evidence, including operator dependencies.
- [ ] **Every Partial finding has passed its missing verification or has an approved residual-risk
      disposition.** S3-19b is now verified on main CI for the Android changes in S3-15/16/17/18/19
      and the S07-C1 Android compile gate. Reassess each Android Partial row against the actual CI
      evidence and any finding-specific residual. PR #209 also adds a Glide no-disk-cache migration.
- [ ] **S02-I3 revoked-token integration verification.** PR #202 added check-revocation behavior
      at all 12 live ID-token verification call sites, guarded by CI regression tests in PR #209.
      Still run the Firebase integration scenario:
      issue an ID token, revoke refresh tokens, prove the old token is rejected on each affected
      endpoint, and prove a newly issued token succeeds. Do not mark this complete from source
      inspection alone.
- [ ] **Supply-chain gates complete.** SC-03 dependency-verification hashes are populated and
      enforced, and wrapper/JAR integrity gates pass. Confirm release provenance/SBOM and any
      remaining operator configuration.
- [ ] **Branch protection verified.** SC-12 requires live confirmation that `main` protection and
      required reviews/checks are enabled. A previous check reported branch protection absent;
      no successful live re-check is recorded here.
- [ ] **Secrets and credentials rotated.** Revoke the leaked Firebase/GCP service-account key and
      rotate remaining Worker, Backblaze/B2, admin/session and other exposed credentials. Record
      console/API evidence; source changes do not rotate deployed credentials.
- [ ] **App Check and Firebase/Firestore operator configuration complete.** Enable enforcement only
      after compatible clients are deployed; verify Firestore TTL/configuration and deployment state.
      The rules-test workflow validates rules; it does not deploy them.
- [ ] **Coordinated server + Android release completed.** Build and test the Android client, deploy
      compatible server/Worker/Firestore rules in the documented order, and verify production
      behavior. PR merges and green CI do not themselves deploy the complete stack.
- [ ] **Trust-boundary review and release approval completed.** Revalidate `TRUST_BOUNDARIES.md`,
      review residual risks, and obtain the required production-release approval and immutable-tag
      ruleset/environment configuration.

## Why this remains PENDING

The remediation has materially advanced: all 20 scheduled Round 3 sessions have been worked through
at source/documentation level, Firestore Rules and Android CI emulator/build gates have passed on
recent PR heads, PR #267 corrected the App Check Rules defect, PR #269 hardened release-log helper analysis, and PR #271 improved app-owned key-buffer cleanup. That is not end-to-end assurance.
S02-I3 still needs live Firebase revoked-token integration verification; S02-I2 needs authorized
Render/Worker Durable Object pairing read-back; SC-12 branch protection still needs authorized
verification; credentials must be rotated; App Check service enforcement and coordinated production
release need live evidence. Main post-merge CI run 42445582763 has passed on the exact main SHA.

Do not change this status to COMPLETE based solely on merged PRs or CI. Sign only after every
unchecked criterion above is either satisfied with evidence or formally deferred with an explicit,
approved residual-risk decision.


## Evidence addendum — 2026-11-06

- Main HEAD: 745f1601448a2b35ffe4ff024b8cde0e9454ebcb (PR #237 merged).
- Main CI run 42162494191: 9/9 jobs passed. Main security scan 42162494131: successful.
- Tracker recount before follow-up branch CI: 116 rows, 83 Fixed, 20 Partial, 1 Open and 12 Accepted.
- Follow-up branch adds Android call-site wiring tests and targeted release-log privacy hardening. Its CI has not yet been recorded; do not promote affected findings until that CI succeeds.
- Physical-device testing is deferred until the owner declares the app feature-complete. Existing automated CI emulator tests remain required; no physical-device acceptance is claimed.
- Current ordered worklist: security-remediation/AGENTIC_WORK_REMAINING_2026-11-06.md.

This addendum does not change the sign-off decision. Production Firestore/App Check configuration, credential rotation, live Render/Worker pairing, staging acceptance, complete log-policy audit, S03-M3 residual approval, repository governance and the deferred device matrix remain outstanding. Keep this document PENDING.

## Post-PR #240 evidence — 2026-11-06

- PR #240 merged to `main`: `f3694eff36ecd54e2112c253cb62d96523478de7`.
- PR-head CI run [42172475903](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42172475903): **9/9 jobs passed**, including Android JVM tests, lint, backend tests, debug APK, R8, Espresso and API 35 predictive-back.
- PR-head Security scan [42172475877](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42172475877): **3/3 passed** (CodeQL, Gitleaks, SBOM).
- Finding register row-level recount after PR #240: **116 rows; 86 Fixed, 17 Partial, 1 Open, 12 Accepted**. S07-L1, S07-L3 and S08-L1 were promoted based on executed JVM evidence. S07-L2, S08-L3 and S10-N2 remain Partial for the explicitly stated residuals.
- Post-merge mainline Security scan [42173185246](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42173185246) passed. Mainline CI [42173185263](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42173185263) was in progress at the time this addendum was written.
- Manual/physical-device testing remains deferred until the owner declares the app feature-complete. Automated CI emulator jobs on the PR head passed; this is not physical-device acceptance.
- The next-agent execution plan is `security-remediation/AGENTIC_WORK_REMAINING_2026-11-06.md`.

These results do not satisfy production sign-off. Credential rotation, live Firestore/App Check configuration, Render/Worker pairing, staging integration, governance enforcement, the full AST-aware logging audit, S03-M3 residual approval, isolated/live storage-runtime verification and later device acceptance remain. Keep this document **PENDING**.
## PR #242 verification — 2026-11-06

PR #242 ([R2/B2 migration failure handling and authenticated-route limiter coverage](https://github.com/kumarclaude4-hash/DuoFatass/pull/242)) was validated at head `92cf469dd0b574cf87816cfd0d9c3df59be5cb20`. GitHub Actions CI run [42181494097](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42181494097) passed **9/9 jobs**, including Server + Worker tests, Android JVM tests, lint, debug APK, minified R8, Espresso, API 35 back-dispatch, Gradle wrapper integrity and vendored libsignal integrity. Security scan run [42181494099](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42181494099) passed; all **14/14 GitHub check runs** on the PR head completed successfully, including the standalone CodeQL check and Cloudflare Worker build.

The new S10-N3 tests passed: changed ETag, transient post-PUT R2 HEAD failure, B2 PUT failure and failed compensating B2 DELETE. The route-inventory regression passed and verifies all 12 Firebase-token-protected routes use the intended authenticated limiter bucket. Worker diagnostics in the touched migration/storage paths no longer include raw media keys or exception messages. These code/test gates are verified; the findings remain **Partial** pending live acceptance: S10-N3 requires isolated Cloudflare Worker + R2 + B2 cron/retry evidence; S02-I2 requires authorized read-back of the Render/Worker Durable Object secret pairing. S03-M3 remains Partial until the owner records a decision on reusable READ/WRITE tokens and the 10-minute TTL using `security-remediation/S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md`.

The Cloudflare Workers integration reported a successful deployment/build for `duoshield-storage` on this PR head. This is not evidence of the R2/B2 scheduled migration or its failure/retry behavior in the live environment; an authorized operator must confirm the deployed Worker version and runtime configuration. Vercel separately reported the free-tier daily deployment quota (`api-deployments-free-per-day`) for preview attempts; that provider status is not one of the 14 GitHub check runs and is not an application-test failure.

The tracker remains **116 findings: 86 Fixed, 17 Partial, 1 Open, 12 Accepted**. No owner risk decision is inferred. Physical-device/manual app-flow testing remains deferred until the owner declares the app feature-complete. Production security sign-off remains **PENDING**; CI and PR merge are not release approval.

## Historical current-main reconciliation addendum — 2026-11-07 (superseded by 2026-11-08 snapshot)

- Current main HEAD: `98f76cdf374eeaf5c8144befad068ef05805a20b`.
- Main CI [42387261365](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261365) and Security Scan [42387261135](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261135) both passed on that exact SHA.
- PR #251 interprocedural log-taint extension and PR #255 invite UID pseudonymization are merged and reflected in the tracker. PR #256 range-aware Worker rate accounting is also merged; the 80K/day global request guard and global daily mutation budgets remain intact. PR #257 refreshed S05-H2 privacy documentation.
- Tracker rows are reconciled to 116 findings: 86 Fixed, 17 Partial, 1 Open, 12 Accepted. S06-H4 and S06-H5 were restored from the Session 06 audit: H4 is Fixed on current source; H5 remains Partial because Settings-based candidate probing is still distinguishable.
- PRs #253, #254 and #258 are not merged. #253/#254 require rebase/merge-conflict resolution against current main and fresh checks. #258 requires correction of a Gitleaks fixture false positive and a flaky Firestore invite-expiry integration fixture.
- S05-H2 still needs invite-only product-contract confirmation, authorized staging lifecycle/onboarding, and review/execution of the legacy UID migration dry-run/approved apply/follow-up dry-run.
- S10-N2 remains Partial for syntax-only data-flow limitations and feature-complete physical release-logcat acceptance. S10-N3 requires isolated live Worker/R2/B2 cron acceptance; S02-I2 requires Render/Worker secret-pair read-back; S02-I3 requires Firebase revoked-token staging verification; S05-M3/S06-L4 require their staging/configuration matrices; S10-N1 requires controlled App Check rollout; SC-12 remains Open pending required reviews/checks/tags/release-environment read-back.
- No production deployment, credential rotation, Firestore rules/TTL change, provider-console mutation, historical UID migration or physical-device testing was performed as part of this documentation update.

All sign-off criteria remain unchecked until the described evidence exists or the owner formally approves a residual-risk disposition. Keep **PENDING**.

