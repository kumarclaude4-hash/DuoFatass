# Next Agent Instructions — DuoShield Security Closeout

**Date:** 2026-11-04  
**Repository:** kumarclaude4-hash/DuoFatass  
**Purpose:** Continue the security work after the repository-side closeout. This is an execution plan, not a security sign-off.

> **Owner scope:** Work on everything remaining except the screenshot-permission item and actual account/identity testing. Those two tracks are deliberately excluded from this handoff. Do not change them or claim they are complete.

---

## 1. Binding instructions

1. **Do not modify, force-push, close, or merge PR #212.** It is a separate, currently open PR:
   - Title: Enhance security coverage for revocation routes
   - Head: security/operator-closeout-verification
   - Head commit observed: 65d3c5d39895f714551a4adbf61f7c35e397f914
   - Base: main
   - Its CI and Security scan both passed in runs 41860269637 and 41860269478.
   - PR #212 adds /acknowledgeRotation to the revoked-token static route inventory and reconciles the finding register. It is not part of this task's branch.
2. Work only on a **new branch from the latest main**. Use separate, small PRs. Do not commit directly to main or use PR #212's head branch.
3. Before coding, read:
   - security-remediation/SESSION_PROTOCOL.md in full.
   - security-remediation/START_HERE.md.
   - security-remediation/ROUND3_REMEDIATION_PLAN.md.
   - Root BUG_TRACKER.md.
   - The relevant audit session report and actual source/tests for every finding being touched.
4. **Source and executable evidence beat documents.** Re-check each finding against current source and tests. Do not trust old session notes, copied line numbers, or a status label without verifying them.
5. Respect the protocol's maximum of four tasks per session. Make each PR narrowly reviewable and include tests/evidence for the exact findings it changes.
6. **DuoShield identity is seed-derived.** The Android app derives the user identity and signing key from a BIP39 seed phrase. Do not create or request Gmail, email/password, Google, phone, or anonymous Firebase accounts as a substitute. Never request, print, log, upload, or commit a user's mnemonic, seed, private key, Firebase ID token, or other bearer credential.
7. Do not put secret values in this file, PR descriptions, commits, logs, shell output, CI artifacts, or sanitized evidence. Evidence may record variable names, secret-presence checks, timestamps, redacted resource IDs, and results—not values.
8. Do not deploy production server, Worker, Firestore rules, Android APK, or change live credentials as a side effect of a code PR. Use staging first and a documented rollback/cutover plan.
9. Keep security-remediation/FINAL_SIGNOFF.md **PENDING**. A green CI run or merged PR is not end-to-end production assurance.

### Explicit exclusions — do not touch in this workstream

- **S08-H2 screenshot protection default.** The owner is handling this separately. Do not change either app_screenshot_enabled default or mark S08-H2 complete.
- **S02-I3 live Firebase account/identity integration test.** Do not create test identities, perform seed-based account provisioning, or claim live revoked-token behavior has been proven. The static guard and route inventory are separate from this excluded live test.
- Do not ask the owner to share a real mnemonic or use a real user account for testing.

---

## 2. Verified starting point and documentation inconsistencies

### PR #212 is separate and remains untouched

The PR head observed on 2026-11-04 is 65d3c5d39895f714551a4adbf61f7c35e397f914; its two GitHub workflows completed successfully. The tracker reconciliation on that PR branch reports:

| Disposition | Count |
|---|---:|
| Fixed | 74 |
| Partial | 29 |
| Open | 1 |
| Accepted | 12 |
| **Total** | **116** |

Those are the figures proposed by PR #212, **not the old figures currently on main until that PR is merged**. Do not silently copy its edits or alter its branch. Once #212 is merged, re-fetch the canonical tracker and reconcile any concurrent changes normally.

### Documentation drift to reconcile

On main, the opening summary in security-remediation/START_HERE.md still says the broader S3-19b Android catch-up is outstanding, while a later section in FINAL_SIGNOFF.md says S3-19b is verified on main CI. The Round 3 plan also contains older statements that S3-19b remains outstanding and stale main commit/run references.

The 2026-11-04 closeout evidence says S3-19b was verified on main CI (lint, Android unit tests, debug APK, Espresso 2/2 and supply-chain checks). Re-check the actual successful workflow run and its jobs, then reconcile all forward-looking statements and historical-vs-current labels. Do not delete historical records; label them as historical and add a dated current-state addendum.

### Render service configuration observed read-only on 2026-11-04

The connected Render account returned the following for service DuoFat (srv-d958babtqb8s73ehoqi0):

- Repository: https://github.com/kumarclaude4-hash/DuoFatass
- Branch: main; auto-deploy: enabled on commit
- Root directory: server
- Build command: npm install
- Start command: node index.js
- Node runtime; Singapore region; one instance
- Health-check path: empty
- Service URL: https://duofat.onrender.com

The repository has both server/package.json and a committed server/package-lock.json. Verify whether npm ci works in a clean Node 20 build (including the native @signalapp/libsignal-client dependency) and is the correct reproducible install command. If it is, prepare a controlled Render configuration change to use npm ci; do not change the live service until the clean build and a safe deployment/rollback plan are ready. Because auto-deploy is enabled on main, merging code can trigger a production deploy—coordinate this deliberately. Do not dump Render environment values or logs containing secrets.

The Render read-back did not expose environment variable names/values. Do not state that Render secrets are configured merely because the service exists.

---

## 3. Work that remains outside the two excluded tracks

The latest proposed tracker on PR #212 has 27 Partial rows other than S08-H2 and S02-I3, plus SC-12 as Open. Re-fetch the live tracker before acting; the following list is a work map, not permission to mark anything fixed without evidence.

### A. Governance and operator configuration — highest operational priority

#### SC-12 — GitHub branch/release governance (Open)

The PR #212 closeout evidence records a 2026-11-04 read-back reporting:
- main branch protection absent.
- No repository rulesets returned.
- No protected production-release environment; the listed Vercel environments had no protection rules.

Re-check current settings through an authorized GitHub admin interface/CLI. If still absent, configure and read back:
- Require pull requests and at least one independent approval (or the owner's chosen review policy).
- Require the actual CI and Security scan checks that run for this repository; use their exact current check names.
- Prevent force-pushes and deletion of main.
- Add immutable v* tag protection/ruleset, disallow tag update/deletion, and restrict who can create release tags as appropriate.
- Create a production-release environment with required reviewer approval and only the intended deployment branch/tag policy.
- Verify effective settings after saving; attach sanitized read-back evidence.

If the agent has no repository-admin permission, do not claim completion. Provide the exact settings and mark it BLOCKED pending an authorized owner.

#### S02-I2 — Render/Cloudflare shared rate-limit path (Partial)

Verify the Render/Worker secret pair and actual Durable Object route end-to-end:

- Render ADMIN_LOCKOUT_WORKER_URL must point to the intended Worker endpoint.
- Render ADMIN_LOCKOUT_WORKER_SECRET must match Worker ADMIN_LOCKOUT_SECRET.
- Verify the Worker Durable Object binding and migrations for the lockout counter namespace.
- Confirm a request reaches the Durable Object and updates the expected counter; verify expiry/cleanup behavior.
- Confirm that a missing or mismatched shared secret produces a visible, monitored degraded-mode signal, not an unnoticed assumption of global enforcement.
- Do not print either secret. Record only presence/match (boolean), endpoint host/path if safe, deployment IDs, and redacted test results.

If a live credential pair is unavailable, leave this Partial and state exactly what access is missing.

#### Credential rotation

The prior audit/closeout notes record an exposed Firebase/GCP service-account key and other credentials requiring rotation. Treat rotation as a controlled cutover, not a blind delete:

1. Identify the actual credential and every consumer from source/configuration. Never print the value.
2. Create a replacement with least privilege and minimum required scope.
3. Update the relevant runtime secret store.
4. Deploy/restart the consumer and prove the replacement works.
5. Review GCP/Cloudflare/Render/Backblaze audit logs for unexpected use as appropriate.
6. Revoke the old credential only after the new path is verified.
7. Record key IDs, timestamps, scope, consumer, validation result, and revocation result—never secret material.

Inventory includes, where present and actually used:
- Render Firebase Admin service-account JSON (GOOGLE_APPLICATION_CREDENTIALS_JSON) and GCP service-account key(s).
- Render admin/media/lockout/TURN/link-preview secrets: ADMIN_TOKEN, MEDIA_TOKEN_SECRET, ADMIN_LOCKOUT_WORKER_SECRET, TURN_TOKEN_ID, TURN_API_TOKEN, LINK_PREVIEW_PROXY_SECRET.
- Worker storage/admin/stats secrets: B2_ACCESS_KEY_ID, B2_SECRET_ACCESS_KEY, MEDIA_TOKEN_SECRET, ADMIN_LOCKOUT_SECRET, STATS_SECRET.
- Any legacy WORKER_SECRET or B2 key still present in provider configuration. Verify code no longer accepts obsolete credentials, then rotate/revoke them if exposed.

Use separate temporary operator credentials for control-plane work. Do not reuse production runtime credentials as operator credentials. Never put credential values in Replit/Vercel prompts or this repository.

#### S10-N1 — Firebase App Check (Partial)

- Confirm Android Play Integrity provider registration, package identity, and the release signing certificate(s) used by the real app.
- Confirm server/Firestore enforcement points and supported client versions.
- Begin in staging/monitoring mode; measure valid-client attestation coverage and failure modes.
- Enable enforcement only after the shipped client is compatible and rollback is documented.
- Record console read-back and test outcomes. Do not simply switch enforcement on against all existing clients.

#### Firestore TTL and rules deployment

- Confirm the _duressNonces.expiresAt TTL policy exists in the intended Firebase project and is enabled; a test in the repository does not deploy TTL.
- Confirm the intended firestore.rules revision is actually deployed to the intended project. Keep emulator tests and live deployed rules evidence separate.
- Before deployment, run the full Firestore emulator suite and review the diff. The historical S3-15b result was 186/186; confirm it corresponds to the exact rules commit being proposed.
- Never deploy rules or TTL to production solely because a PR is green.

#### S10-N3 — R2-to-B2 migration race (Partial)

The code contains a compensating-delete race guard and worker regression tests. Complete a controlled integration test using isolated test buckets/objects:
- Put an object in R2, start migration to B2, delete the object during the migration window, and verify that no B2 copy survives to resurrect it.
- Test normal migration and retry behavior.
- Verify cleanup/idempotency and record object IDs only as synthetic test data.
- Do not run destructive tests against real user media or production buckets.

### B. Server and Worker residual code findings

Work on each finding only after re-reading its row and current implementation. Use focused PRs with regression tests.

| Finding | Remaining question / required work |
|---|---|
| **S05-H2** | The operator-issued invite flow now replaces the waitlist write path: 256-bit opaque tokens, hash-only persistence, expiry/status handling, admin-gated transactional revocation, cursor-bounded listing with total/cap metadata, redemption linkage and audit are present, with helper/unit and route-wiring tests. Remaining: add/run Firestore-backed emulator integration tests across create, one-time/concurrent redemption, listing/status, revoke/expiry, audit and redemption-linkage behavior. Confirm the invite-only onboarding contract and keep Partial until integration evidence is recorded. |
| **S05-M3** | Current code has a 30-minute idle TTL, an 8-hour absolute TTL, a non-refreshing GET /admin check, per-session logout revocation, and an admin-gated revoke-all path. IP/UA binding is intentionally disabled: mobile NAT changes and Android WebView navigation-vs-fetch User-Agent differences caused legitimate session failures. Do not re-enable naive IP/UA binding. Remaining residual: a stolen valid cookie is not constrained by IP/UA; design a compatible device-bound/replay-resistant mechanism or obtain explicit risk acceptance. |
| **S07-H3** | The former claim that Firestore rules lacked group/sender binding is stale. Current rules enforce group membership and sender == auth.uid, require encrypted content and (for non-empty ciphertext) aadV1 == true plus message ID matching the document path. Android AES-GCM AAD binds group ID, sender UID and message ID; legacy messages without aadV1 remain readable. S3-15b records 186/186 Firestore emulator tests passing and the 2026-11-05 report records Android/CI verification. No further source change is currently identified; preserve the regression tests and reconcile the tracker as Fixed. |
| **S07-H2 / S07-M3** | Backup HMAC and AAD code exists, but verify the actual current Android build/test coverage. Add migration/restore tests for legacy checksum-only documents and new HMAC/AAD documents, including metadata tampering. Do not break restores of existing backups. |
| **S07-M2** | Trust is now fingerprint-keyed with a legacy migration path; add a dedicated migration test proving a UID cannot inherit trust for a different identity key and that the old record is handled safely. |
| **S03-M3** | Media tokens now have a jti, with single-use replay protection for delete. The 10-minute TTL remains, and read/write tokens remain reusable for legitimate retries. Assess the actual exposure and client retry contract. Either implement a safe, compatible mitigation or obtain an explicit residual-risk acceptance; do not make read/write tokens single-use without proving it will not break normal uploads/downloads. |
| **S04-I3** | Redirect provenance was fixed, but blocked host, timeout, non-HTML, and metadata-free cases still look alike to the client. Define a minimal typed failure response and Android handling without leaking internal network/DNS details. Add server and client tests. |
| **S07-L1** | Re-check fetchGroupKey creator authorization. It is recorded as fail-open; prove the rejection path with a regression test and make the authorization check fail closed if source still permits bypass. |
| **S07-L2** | Static derivationCache may survive a duress wipe. Trace every cache write/read/clear path and ensure duress handling clears all in-memory derived material reachable by the process. Add a deterministic test seam; never log key material. |
| **S07-L3** | Review mnemonic canonicalization and locale-sensitive casing. Use locale-independent normalization (e.g. Locale.ROOT) where appropriate and add Unicode/locale regression cases. Never log test mnemonics. |
| **S07-L4** | loadSession must not silently substitute a fresh session when persisted ratchet state is corrupt/missing. Confirm failure propagation and caller handling; add tests proving no silent trust reset or message-state downgrade. |
| **S06-L4** | AccountLockWorker must report real operation failures as failure/retry, not success. Trace WorkManager retry semantics, idempotency and duress behavior; add tests. |
| **S10-N2** | A targeted source search found and fixed an additional direct leak in `IncomingCallWatcher`: raw `callerId` is now logged only through `LogRedact.uid()`, and exception text is omitted from the UID-scoped listener logs. `IncomingCallWatcherLogRedactionTest` guards the call site. Still required: a comprehensive repository-wide scan of all release-surviving `Log.w`/`Log.e` argument expressions (including exception messages and path-like metadata) and passing Android unit-test CI before closing the finding. |
| **S02-I2** | In addition to live config above, verify all authenticated endpoints use the intended shared limiter and that fallback mode is bounded, observable, and explicitly treated as degraded. Do not mark globally consistent unless the shared Worker path is verified. |

### C. Android findings — re-evaluate CI first, then device-only gaps

S3-19b is reported as passing on main CI. Do **not** keep an Android finding Partial merely because an older session lacked JDK/SDK. Inspect the exact CI run, test names, source commit, and coverage. Promote only findings whose own required behavior is exercised. General compilation is not proof of runtime behavior.

| Finding | Required follow-up |
|---|---|
| **S11-H1** | Verify Android 13+ predictive-back behavior on API 33+ emulator/device: the lock screen and all no-skip gates must not be dismissible by gesture/back. The source-level BackPressGuard regression test is not a device test. |
| **S11-M1** | Test a live call: start/minimize a call, background the app beyond the lock timeout, tap the ongoing-call notification, confirm the lock screen appears and successful unlock returns to the call. Do not break incoming calls. |
| **S08-M1** | Confirm the native heap pointer-tagging manifest setting is removed and survives an actual release/minified build; inspect merged manifest, not just source text. |
| **S08-M2** | Narrow FileProvider grants to dedicated share/export subdirectories. Test every legitimate share/export flow and verify arbitrary cache/files paths cannot be granted to another app. |
| **S08-I1** | Run a minified release/R8 build. Verify crypto/security class names can be obfuscated while WorkManager reflective construction and required serialization paths still work. |
| **S08-L1** | Verify canonical Account ID validation at manual entry, clipboard, and deep-link boundaries; reject malformed/decorated IDs before Firestore lookup. |
| **S08-L2** | Test EXTRA_IS_SENSITIVE clipboard behavior on API 33+ and confirm all sensitive clipboard writes use the helper. |
| **S08-L3** | Test 4-, 5-, and 6-digit PIN flows after removing persisted PIN-length metadata; ensure debounced auto-submit/backspace does not cause accidental submission or lockout. |
| **S08-L4** | Verify lock screen excludes itself from recents and prevent sensitive underlying content from being rendered before lock enforcement/opaque cover. Review lifecycle ordering; test cold start and resume. |
| **S08-H5 / S07-M1** | Design a backward-compatible, crash-safe migration for legacy plaintext preference data. Write encrypted destination, read it back, validate schema, and only then mark migration complete. Do not delete legacy data until durable encrypted migration succeeds and recovery behavior is defined. |
| **S07-H2 / S07-M2 / S07-M3** | Verify Android unit tests actually execute the backup/trust migration cases; add any missing dedicated migration/restore tests; verify legacy read compatibility and tamper rejection. Do not break restores of existing backups. |

S08-H2 is no longer a source-default reversal: current main defaults to FLAG_SECURE on. It remains Partial only for fresh-install, opt-in and lifecycle verification on supported physical devices.

### D. Supply chain, build and deployment consistency

- Check current supply-chain workflows, dependency verification metadata, wrapper/JAR integrity, release SBOM/provenance, and Dependabot/SAST/secret scanning. Re-check current workflow runs, not only old run IDs.
- Confirm server/package-lock.json and worker/package-lock.json match their package manifests. Prefer reproducible npm ci builds where validated.
- For Render's DuoFat service, test npm ci under the configured Node 20 runtime before any live build-command change. Confirm native module installation works on Render's actual Linux runtime.
- Review whether a health-check path should be configured. Inspect GET /status behavior and ensure it does not disclose internal details or return healthy before required dependencies are ready. Configure only after testing.
- Because Render auto-deploys commits to main, treat every merge as a possible production deploy. Do not merge a server contract change without coordinated Android compatibility and rollout planning.
- Verify production server/Worker/Firestore rules/Android release order, smoke tests, and rollback procedure. Record actual deployment IDs and tested app/server versions.

---

## 4. Execution order

Use this order; do not try to finish every item in one giant PR.

1. **Reconcile source-of-truth docs.** Resolve the S3-19b stale statements and stale commit/run references. After PR #212 merges, re-count the canonical tracker and ensure the count matches the row table. Do not amend PR #212 to do this.
2. **Get governance and operator access resolved.** Re-check SC-12, Render/Worker pairing, service-account rotation, remaining credential rotations, App Check, Firestore TTL/deployed rules. If the current agent lacks permissions or credentials, produce a precise owner action list and keep the finding blocked.
3. **Complete narrowly scoped server/Worker fixes** (e.g. waitlist lifecycle, session revocation design, AAD rules binding, error semantics, log redaction, worker failure semantics) with focused tests.
4. **Complete Android code/test follow-ups**, using S3-19b evidence to retire only rows genuinely covered, then run device/emulator tests for behavior that cannot be proven by unit CI.
5. **Run isolated R2/B2 and operational integration checks.**
6. **Revalidate supply chain and release configuration.**
7. **Update tracker, evidence, and sign-off.** Keep accepted risks explicitly approved; keep all unverified work Partial/Open/Blocked. FINAL_SIGNOFF.md stays PENDING until all criteria are evidenced or formally deferred by the owner.

---

## 5. Evidence format required for every task

For each finding, add a short evidence entry containing:

- Finding ID and exact source files changed.
- Threat/attack scenario addressed.
- Regression test name and exact command/workflow run.
- Test count and result; link to workflow/run or device test record.
- Any backward compatibility or migration behavior.
- Residual risk and any operator dependency.
- Commit/PR reference.
- Disposition after verification: Fixed, Partial, Open, Accepted (with named approving owner), or Blocked (with exact missing access).

Never record secrets, tokens, private keys, mnemonics, user content, or raw production identifiers. For configuration checks, record redacted resource identifiers and boolean outcomes only.

## 6. Definition of done

This handoff is complete only when:
- PRs are small, reviewed, and all required checks pass.
- No unrelated changes are pushed to PR #212.
- All relevant Android rows have been re-evaluated against S3-19b and device-specific evidence.
- SC-12, credential/configuration tasks and deployment state have actual read-back evidence or an explicit owner blocker.
- R2/B2 migration behavior is verified against isolated resources.
- The root tracker recount matches every row and severity bucket.
- Current forward-looking docs agree on main commit, test run IDs and remaining gates.
- FINAL_SIGNOFF.md accurately remains PENDING until every required sign-off item is satisfied.

**Do not declare DuoShield production security-complete merely because code PRs and CI are green.**
