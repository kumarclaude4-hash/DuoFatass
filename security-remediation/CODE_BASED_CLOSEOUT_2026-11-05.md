# DuoShield — Code-Based Closeout Notes

**Date:** 5 November 2026  
**Base:** `main` at `2f2d705943c4f7bc594980c219ce5459f8dc9575`  
**Working branch:** `security/code-closeout-2026-11-05`  
**Purpose:** Complete safe repository-side cleanup discovered while reviewing remaining security work. This is not a production sign-off.

## Changes made on this branch

### 1. Corrected security documentation against current implementation

- `SECURITY.md` no longer says screenshot protection is absent. It now documents the current `FLAG_SECURE` default and the explicit user opt-in.
- `SecurityPrivacySettingsActivity.java` no longer says the screenshot default is temporarily true. Current `BaseActivity.SCREENSHOTS_ALLOWED_BY_DEFAULT` is `false`, and the shared helper applies `FLAG_SECURE` when the saved preference is absent or false.

No screenshot behavior or preference value was changed in this branch.

### 2. Corrected admin-session documentation and finding IDs

- `server/index.js` now accurately says that User-Agent is captured as context but IP/User-Agent binding is not enforced.
- The admin-session User-Agent compatibility notes in `server/lib/adminSessionStore.js`, `server/lib/adminSessionStore.test.js`, and `server/lib/adminAuditWiring.test.js` now reference **S05-M3**, not **S07-H3**. S07-H3 is the separate group-message AAD finding.
- No authentication, cookie, expiry, revocation, or session-validation logic was changed.

The non-binding behavior is intentional: mobile networks can re-NAT during a session, and Android WebView navigation and fetch/XHR requests can send different User-Agent strings. The current code retains the captured User-Agent context but does not treat it as a session factor.

### 3. Reconciled the canonical finding tracker

- **S05-H2 remains Partial.** The old waitlist-denial-only description was replaced with the current operator-issued invite implementation: high-entropy opaque tokens, hash-only persistence, bounded expiry, status handling, admin-gated transactional revocation, paginated/capped listing, totals, audit and redemption linkage. The remaining acceptance gate is Firestore-backed integration coverage for creation, redemption (including concurrency), listing, expiry/revocation and audit/linkage.
- **S05-M3 remains Partial.** The current idle/absolute expiry and revocation work is recorded accurately. IP/User-Agent binding is explicitly recorded as disabled, and the stolen-cookie replay residual is not hidden or called fixed.
- **S07-H3 is reconciled to Fixed.** The tracker now records both halves: Android AES-GCM AAD binds group ID, sender UID and message ID; Firestore rules enforce membership, sender identity, encrypted content, the AAD marker and message ID/document-path match. Legacy messages without the AAD marker remain readable. The documented emulator evidence is 186/186 rules tests passed; Android/CI evidence is recorded in the 5 November progress report.
- **S08-H2 remains Partial.** The stale claim that the current screenshot default is true has been removed. Current source defaults to `FLAG_SECURE` on; physical-device/lifecycle verification remains outstanding.

### 4. Refreshed the next-agent handoff

`security-remediation/NEXT_AGENT_WORK_INSTRUCTIONS.md` now distinguishes already-implemented code from the actual missing integration/device evidence. It no longer asks a future agent to implement a Firestore group/sender rule binding that is already present.

## Verification and limits

This branch changes comments, tests' finding labels, the tracker and handoff documentation; it does **not** change runtime security behavior. Source claims were checked against current files on `main` before editing.

No local build or test suite was executed in this editing pass. The PR's CI must validate the branch. Existing evidence referenced by the tracker/report includes:
- Firestore emulator suite: 186/186 passing (S3-15b historical run).
- Main CI run 41965880585: Android unit tests, lint, debug APK build and emulator-backed Espresso passed on the pre-report main source commit.
- Security scan run 41965880566: CodeQL, Gitleaks and SBOM generation passed.

Those are prior run results, not fresh results for this branch. Do not infer that a new CI run passed until GitHub reports it.

No production Firebase, Cloudflare, Backblaze, Render, GitHub secret or signing-key value was read, printed, changed, rotated or revoked. No production Firestore rules, Worker binding, environment variable, TTL policy or App Check enforcement setting was changed. No production identity or media operation was exercised.

## Remaining work with code or test components

| Finding / area | Remaining engineering work | Why it is not closed here |
|---|---|---|
| S05-H2 invites | Firestore-backed emulator integration tests for full create/redeem/list/revoke/expiry lifecycle, concurrent redemption and durable audit assertions. | Current evidence is helper/unit and source-wiring coverage; a full Firestore lifecycle test is still needed. |
| S05-M3 admin sessions | Evaluate a compatible device-bound or replay-resistant session mechanism; otherwise obtain explicit owner acceptance of the stolen-cookie residual. | Naive IP/UA binding demonstrably breaks mobile/WebView sessions; changing the auth design without a validated client protocol would be unsafe. |
| S08-H2 screenshot controls | Physical-device tests for fresh-install default, explicit opt-in, recording and recents behavior across supported Android versions and activity transitions. | OS/device behavior cannot be proven by source inspection alone. |
| S08-I1 release build | Run minified `assembleRelease`/R8 and test WorkManager reflection, serialization and crypto paths under obfuscation. | Requires Android build environment and release-equivalent validation. |
| S06-L4 WorkManager | Runtime retry/idempotency tests for lock worker success, transient failure, permanent failure and duress/offline behavior. | Requires WorkManager runtime test harness and lifecycle execution. |
| S10-N2 logging | Complete repo-wide review of release-surviving logs for raw peer/account identifiers and add regression coverage where gaps exist. | The prior redaction pass covered named call sites, not a complete current-tree sweep. |
| S10-N3 media tiering | Observe migration and compensating-delete behavior against isolated deployed R2/B2 resources, including retry/cleanup. | Unit tests exist; live Worker/R2/B2 execution is still external verification. |

## Production gates that remain outside repository-only work

- Protect `main`, require approved reviews and required CI/security checks, prevent force-push/deletion, and configure protected immutable release tags/environment.
- Rotate/revoke credentials identified in the remediation runbooks and verify revocation/audit evidence.
- Verify Render/Worker shared lockout secret pairing and Durable Object binding/migration without exposing secret values.
- Run the Firebase revoked-token integration scenario in isolated staging; verify App Check rollout, Firestore TTL and deployed rules revision.
- Complete coordinated server/Worker/Firestore/Android release validation.

**Release decision remains: PENDING — NOT APPROVED FOR PRODUCTION SECURITY SIGN-OFF.** This branch does not satisfy or waive any operator-only, staging, or device-level sign-off gate.
