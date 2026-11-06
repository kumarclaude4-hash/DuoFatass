# FINAL SIGN-OFF

> Status: **PENDING — NOT APPROVED FOR PRODUCTION SIGN-OFF.**
> Updated 2026-11-04 after the remediation merge batch and verification of the resulting
> `main` workflows. This document is the release/sign-off gate, not a progress claim.

## Current evidence snapshot

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
at source/documentation level, the S3-15b Firestore Rules catch-up passed, the security PR batch is
merged, PR #200's Espresso job passed, and post-merge CI plus security scanning are green. That is
not the same as end-to-end assurance. S3-19b is now verified on main CI. S02-I3 still needs live Firebase revoked-token integration
verification; S02-I2 needs confirmation that Render/Worker shared credentials are set correctly;
SC-12 branch protection still needs authorized verification; credentials must be rotated;
App Check/Firestore settings and coordinated production release need live evidence.

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
## Additional source work in progress — 2026-11-06

A separate follow-up branch adds deterministic S10-N3 migration failure-injection tests and fixes ambiguous R2 HEAD handling so transient failure retains/counts the hot source; sanitizes Worker media-key/error diagnostics; adds an S02-I2 route-inventory guard for all 12 shared authenticated limiters; and records the owner decision needed for the S03-M3 capability-token residual. Existing auth-rate-limit/admin-session tests were rechecked for bounded local fallback and Firestore outage propagation.

These additions are not yet verified until their own PR CI passes. The complete S10-N2 AST-aware Android log audit, S03-M3 owner acceptance, live R2/B2, staging/production operator evidence, SC-12 governance and deferred device matrix remain open. Keep this document **PENDING**.