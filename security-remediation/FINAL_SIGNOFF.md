# FINAL SIGN-OFF

> Status: **PENDING — NOT APPROVED FOR PRODUCTION SIGN-OFF.**
> Updated 2026-11-04 after the remediation merge batch and verification of the resulting
> `main` workflows. This document is the release/sign-off gate, not a progress claim.

## Current evidence snapshot

The canonical finding register is [`../BUG_TRACKER.md`](../BUG_TRACKER.md). Its latest in-file
roll-up is **118 findings: 64 Fixed, 36 Partial, 6 Open, 12 Accepted**. These figures are
carried from the current tracker table and were not independently recounted during this update;
recount the finding rows and severity buckets before treating them as a fresh audit-grade tally.

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

Post-merge `main` HEAD:
`b17f26437bc0731b12f8d5bdb99d4268d860adc9`.
Both post-merge workflows completed successfully:
[CI run 41792882092](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41792882092)
and [Security scan run 41792882126](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41792882126).

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