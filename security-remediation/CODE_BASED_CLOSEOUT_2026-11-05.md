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
- `IncomingCallWatcher.java` no longer writes the raw `callerId` Firebase UID or UID-scoped Firestore exception text to release logcat. It logs `LogRedact.uid(callerId)` plus the exception class, and its listener error path logs only the exception class. A new plain-JVM source regression test, `IncomingCallWatcherLogRedactionTest.java`, guards this call site.
- A broader review covered 76 Android production Java files containing release-surviving `Log.w`/`Log.e` calls. In addition to the caller UID, it removed the chat-export `conversationId`/B2 path from logs and suppressed backend exception text in UID-scoped Firestore logs in `ContactManager`, `PresenceThrottle`, `CallCleanupWorker` and `SettingsActivity`. `ReleaseLogPrivacyTest.java` guards these specific paths.
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

This branch changes comments, test finding labels, tracker and handoff documentation, plus log-output privacy behavior: caller UIDs are pseudonymised and user-scoped backend exception/path details are omitted from release-level logs. It does **not** change call routing, authentication, cryptography, retries or message/data handling. A source search returned 76 Android production Java files containing `Log.w`/`Log.e`; every returned file was inspected for direct concatenation of UID/account/sender/address/key-shaped identifiers. This search found the raw caller UID and a chat-export conversation identifier, both now removed/redacted; a numeric Signal device ID remains beside a pseudonymised peer tag. This was search-index-driven source review, not a compiler-backed AST audit.

No local build or test suite was executed in this editing pass. The PR's CI must validate the branch. Existing evidence referenced by the tracker/report includes:
- Firestore emulator suite: 186/186 passing (S3-15b historical run).
- Main CI run 41965880585: Android unit tests, lint, debug APK build and emulator-backed Espresso passed on the pre-report main source commit.
- Security scan run 41965880566: CodeQL, Gitleaks and SBOM generation passed.

Those are prior run results, not fresh results for this branch. Do not infer that a new CI run passed until GitHub reports it.

No production Firebase, Cloudflare, Backblaze, Render, GitHub secret or signing-key value was read, printed, changed, rotated or revoked. No production Firestore rules, Worker binding, environment variable, TTL policy or App Check enforcement setting was changed. No production identity or media operation was exercised.

**Deployment automation observation:** the Cloudflare GitHub bot reported a successful `duoshield-storage` deployment to its `production` target for PR-branch commit `2c9e1dd` at 2026-11-05 05:05 UTC ([PR deployment comment](https://github.com/kumarclaude4-hash/DuoFatass/pull/218#issuecomment-6650146270)). This PR diff contains no `worker/` source files, so no Worker source was changed here. The deployment notification nevertheless indicates that a production deployment event was associated with this branch commit. **Operator action:** verify the Cloudflare Workers Builds branch/production environment mapping and restrict production deployment to protected `main`/release refs before sign-off. This pass did not access Cloudflare deployment logs or alter the integration.

## Remaining work with code or test components

| Finding / area | Remaining engineering work | Why it is not closed here |
|---|---|---|
| S05-H2 invites | Firestore-backed emulator integration tests for full create/redeem/list/revoke/expiry lifecycle, concurrent redemption and durable audit assertions. | Current evidence is helper/unit and source-wiring coverage; a full Firestore lifecycle test is still needed. |
| S05-M3 admin sessions | Evaluate a compatible device-bound or replay-resistant session mechanism; otherwise obtain explicit owner acceptance of the stolen-cookie residual. | Naive IP/UA binding demonstrably breaks mobile/WebView sessions; changing the auth design without a validated client protocol would be unsafe. |
| S08-H2 screenshot controls | Physical-device tests for fresh-install default, explicit opt-in, recording and recents behavior across supported Android versions and activity transitions. | OS/device behavior cannot be proven by source inspection alone. |
| S08-I1 release build | Run minified `assembleRelease`/R8 and test WorkManager reflection, serialization and crypto paths under obfuscation. | Requires Android build environment and release-equivalent validation. |
| S06-L4 WorkManager | Runtime retry/idempotency tests for lock worker success, transient failure, permanent failure and duress/offline behavior. | Requires WorkManager runtime test harness and lifecycle execution. |
| S10-N2 logging | A source search returned 76 production Android Java files with release-surviving `Log.w`/`Log.e`; each was inspected for direct UID/account/sender/address/key interpolation. The raw caller UID, conversation identifier and sensitive path/error-detail outputs found in that pass were removed or redacted. Two plain-JVM regression tests now guard the identified paths. | Run Android unit-test CI, review remaining release-surviving `Throwable` stack traces for accidental identifier exposure, and treat the search-index scan as source review rather than an AST-complete audit. Keep Partial until validation is green. |
| S10-N3 media tiering | Observe migration and compensating-delete behavior against isolated deployed R2/B2 resources, including retry/cleanup. | Unit tests exist; live Worker/R2/B2 execution is still external verification. |

## Production gates that remain outside repository-only work

- Protect `main`, require approved reviews and required CI/security checks, prevent force-push/deletion, and configure protected immutable release tags/environment.
- Rotate/revoke credentials identified in the remediation runbooks and verify revocation/audit evidence.
- Verify Render/Worker shared lockout secret pairing and Durable Object binding/migration without exposing secret values.
- Run the Firebase revoked-token integration scenario in isolated staging; verify App Check rollout, Firestore TTL and deployed rules revision.
- Complete coordinated server/Worker/Firestore/Android release validation.

**Release decision remains: PENDING — NOT APPROVED FOR PRODUCTION SECURITY SIGN-OFF.** This branch does not satisfy or waive any operator-only, staging, or device-level sign-off gate.
