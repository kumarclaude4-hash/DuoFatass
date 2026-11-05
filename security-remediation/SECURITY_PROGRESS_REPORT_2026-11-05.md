# DuoShield Security Remediation — Verified Progress Report

**Assessment date:** 5 November 2026  
**Repository:** https://github.com/kumarclaude4-hash/DuoFatass  
**Baseline:** 8b4b66359558ac656270213dd9de0f58047e4c0d (main)  
**Report type:** Repository, CI, and connected Render read-only verification  
**Disposition:** **Improved, but not approved for production security sign-off**

---

## 1. Executive assessment

DuoShield has made substantial progress at the source and CI layers. The current main commit has passing server/Worker tests, Android unit tests and lint, a debug APK build, emulator-backed Espresso tests, wrapper/libsignal integrity checks, CodeQL, secret scanning, and SBOM generation. The connected Render service also reports its latest deployment for this exact commit as live.

That evidence is meaningful, but it is not equivalent to a production security sign-off. The most important remaining release blocker is governance: **GitHub currently reports main as unprotected and the repository has no rulesets**. Render is configured to auto-deploy commits from main, so a merge can become a live server deployment without a branch-protection gate.

Several high-impact operations still require owner-controlled cloud access: credential rotation, Render/Cloudflare Durable Object pairing, Firebase App Check and TTL configuration, deployed Firestore rules confirmation, and the revoked-token integration scenario. Those cannot be certified from the repository or the available read-only deployment metadata.

**Recommendation:** keep the final sign-off status at **PENDING**. Do not describe DuoShield as production security-complete based solely on merged PRs or green CI.

## 2. Scope and method

This pass inspected the current repository tree and targeted source files, the canonical finding tracker, the sign-off and handoff documents, the current main branch metadata, the latest GitHub Actions runs and their jobs, repository rulesets, and the connected Render service/deployment metadata.

No source security logic was changed for this report. The only submitted repository change is this report document.

The following were not performed:
- No production Firebase, Cloudflare, Backblaze, or Render secret values were read, printed, copied, or changed.
- No credential was rotated or revoked.
- No Firebase user identity was created, modified, or tested. The live S02-I3 identity/revocation scenario remains excluded from this pass as previously directed.
- No production Firestore rules, TTL policy, App Check enforcement, Worker binding, or Render environment variable was changed.
- No destructive test was run against real user data, production media, or production storage.
- No physical Android device test was performed.
- The screenshot-permission workstream S08-H2 was not modified in this pass, in accordance with the prior exclusion.

This is a repository/deployment evidence review, not an independent penetration test or a certification of the live production environment.

## 3. Current verified baseline

### 3.1 GitHub repository

| Item | Current evidence |
|---|---|
| main HEAD | 8b4b66359558ac656270213dd9de0f58047e4c0d |
| Latest commit | Complete invite lifecycle visibility and bounded pagination |
| Open PRs at pre-report inspection | None |
| main protected | **No** — branch API returned protected=false; required checks are off |
| Repository rulesets | **None returned** by the repository rulesets endpoint |
| PR #212 | Merged 5 Nov 2026; merge commit 832a5dab51a136a8dfb1a9f008fad51edf89c560 |
| PR #213 | Merged 4 Nov 2026; documentation handoff added |

References:
- [Current main commit](https://github.com/kumarclaude4-hash/DuoFatass/commit/8b4b66359558ac656270213dd9de0f58047e4c0d)
- [PR #212 — revocation-route security coverage](https://github.com/kumarclaude4-hash/DuoFatass/pull/212)
- [PR #213 — next-agent security closeout instructions](https://github.com/kumarclaude4-hash/DuoFatass/pull/213)
- [Canonical finding tracker](https://github.com/kumarclaude4-hash/DuoFatass/blob/main/BUG_TRACKER.md)
- [Final sign-off gate](https://github.com/kumarclaude4-hash/DuoFatass/blob/main/security-remediation/FINAL_SIGNOFF.md)

### 3.2 GitHub Actions on the exact current commit

Both current workflows completed successfully on 8b4b66359558ac656270213dd9de0f58047e4c0d.

**CI — run 41965880585: SUCCESS**
- Server + Worker tests
- Gradle wrapper JAR checksum validation
- Vendored libsignal JAR hash validation
- Android unit tests
- Android lint
- Debug APK build, including the packaged-build-input secret assertion
- Instrumented tests (Espresso), including the workflow step that verifies tests actually executed

[Open CI run 41965880585](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41965880585)

**Security scan — run 41965880566: SUCCESS**
- CodeQL (JavaScript)
- Gitleaks secret scan
- SBOM generation

[Open Security scan run 41965880566](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41965880566)

The commit also had a successful Vercel status check. Passing checks demonstrate that the current CI jobs ran successfully; they do not prove that every historical Partial finding has its own complete regression test, nor do they prove production configuration.

### 3.3 Render deployment read-back

The connected Render account returned the following metadata for the DuoFat web service:

| Setting | Read-back |
|---|---|
| Service | DuoFat |
| URL | https://duofat.onrender.com |
| Repository branch | main |
| Auto-deploy | Enabled on commit |
| Root directory | server |
| Runtime | Node |
| Build command | npm install |
| Start command | node index.js |
| Health-check path | Not configured |
| Latest deployment | dep-dbm028ks728c73f73ftg |
| Latest deployment commit | 8b4b66359558ac656270213dd9de0f58047e4c0d |
| Latest deployment status | **Live** |

This confirms that the latest main commit was deployed to the connected Render service. It does **not** prove that the Worker, Firebase rules, App Check, TTL policies, or paired secrets are correctly configured.

Two operational observations:
1. Render auto-deploys from main, making the unprotected-branch issue an immediate deployment-governance risk.
2. The service uses npm install although a package lockfile exists and server/package.json pins the runtime major to Node 20. Validate a clean npm ci build on the actual Render runtime before changing the build command. No build-setting change was made here.

## 4. Repository-side remediation confirmed

The following items were verified in current source, tests, merge history, or current CI. They are **not** a claim that the entire production environment is secure.

### 4.1 Firebase ID-token revocation guard — source and CI coverage present

PR #212 added an explicit route inventory for the live Firebase ID-token verification call sites, including /acknowledgeRotation, and guards that each call uses revocation checking. The change is merged as 832a5dab51a136a8dfb1a9f008fad51edf89c560. The current server test job passes.

**Still required:** S02-I3's live Firebase integration scenario remains outstanding: prove a previously issued token is rejected after refresh-token revocation and a newly issued token succeeds on every affected protected route. Source inspection and static route tests are not a substitute for that integration test. This live identity test was not run in this pass.

### 4.2 Operator-issued invite lifecycle — latest main contains the missing source work

The latest main commit adds and tests invite-listing visibility and pagination. The current source now uses operator-issued hashed invites in place of the legacy waitlist approve/deny write flow.

Verified in server/lib/invite.js, server/index.js, and server/lib/adminAuditWiring.test.js:
- Invite tokens use 32 bytes of cryptographically random material.
- The server validates token format and expiry bounds (1 hour to 90 days).
- The stored record is designed around a token hash and short fingerprint, not the raw token.
- Status logic distinguishes active, used, expired, and revoked invitations.
- Revocation is admin-gated, transactional, state-checked, and audited.
- Listing is newest-first, cursor-paginated, and capped at 1,000 records.
- The API returns a total count and explicitly reports when the bounded result set is capped.
- The admin panel exposes redemption linkage through usedByUserId, rendered via textContent.
- Tests cover token shape/normalization/hash, expiry/status boundaries, cursor validation, and route wiring.

**Tracker reconciliation required:** BUG_TRACKER.md still describes parts of S05-H2 as outstanding, including operator-issued invitations, expiry, revocation, pagination/counts, and redemption linkage. The latest code appears to address much of that gap, but the present evidence is primarily helper/unit and source-wiring coverage—not a full Firestore-backed lifecycle integration test. Confirm the intended product behavior (operator-issued invitations replacing the former public waitlist flow), add or run the appropriate integration tests, and only then revise the finding disposition.

### 4.3 Admin session lifecycle — bulk revocation and audit wiring present

Current code includes an admin session store with idle and absolute expiry behavior, a POST /admin/api/sessions/revoke-all path, and a durable admin_sessions_revoked_all audit action. Tests cover the session store and source wiring.

**Important compatibility detail:** current code deliberately does not enforce IP or User-Agent binding by default. The source comments document mobile network re-NAT and Android WebView User-Agent differences that caused valid admin sessions to fail. Do not re-enable IP/UA binding as a blanket policy without a tested mobile-client design.

### 4.4 Group-message AAD — tracker wording is stale/incomplete

Current firestore.rules requires group membership, sender == request.auth.uid, encrypted messages, and—on non-empty ciphertext—aadV1 == true with the message id matching the Firestore document ID. The nested rule path binds the group context. The Android GroupCipherHelper constructs AES-GCM AAD from group ID, sender UID, and message ID. Tests cover sender spoofing, AAD marker absence, message-ID mismatch, and cryptographic failure when group/sender/message context differs.

The current firestore.rules blob SHA is 2d8b436a02f56ce400d1fdf285578f3a5d11ba37, identical to the rules blob at the 4 November baseline. The historical S3-15b evidence records 186/186 Firestore emulator tests passed against that rules revision.

**Tracker reconciliation required:** the S07-H3 narrative in the tracker says the rules-side binding was never implemented. That statement does not match the current rules source and test suite. Reconcile the exact claim and evidence before retaining the finding as open/partial. The 186/186 result is historical evidence, not a new emulator run performed on 5 November.

### 4.5 Screenshot default — secure by default in current source

BaseActivity.SCREENSHOTS_ALLOWED_BY_DEFAULT is false. The shared screenshot-security helper sets FLAG_SECURE unless the preference explicitly allows screenshots. The settings activity reads the same constant.

The SecurityPrivacySettingsActivity comment still says the constant is temporarily true, which contradicts the current constant and is stale documentation. The S08-H2 workstream was excluded from code changes in this pass, so this comment was not modified. Confirm the intended product setting on a device before release; do not infer runtime behavior from that stale comment.

### 4.6 Other implemented controls visible in current source

Current source and the merged remediation history also include:
- Seed-derived identity and signed challenge proof-of-possession for token minting; no email/password identity substitution was used.
- Server-managed one-time Signal pre-key consumption and protections against consumed-key resurrection.
- Media capability-token lifecycle hardening and fail-closed delete replay protection.
- Shared Durable Object-backed authentication rate-limit implementation and expiry cleanup in Worker code.
- Duress-wipe derivation-cache clearing, locale-independent mnemonic normalization, and fail-closed corrupted Signal session handling.
- Account-lock worker retry/failure handling.
- Android decrypted-media cache hardening and legacy decrypted-cache cleanup.

The current passing Android/server/Worker CI is useful compilation and test evidence for the checked paths. It does not replace finding-specific runtime, restore/migration, or device verification where those are the actual acceptance criteria.

## 5. Open and partially verified gates

### P0 — resolve before production security sign-off

| Workstream | Finding / gate | Required evidence |
|---|---|---|
| GitHub governance | SC-12 | Protect main; require PR review and the actual CI/security checks; block force-push/deletion; configure immutable v* tag protection and a protected production-release environment. Current main is unprotected and the repository rulesets endpoint returned an empty list. |
| Render/Worker shared limiter | S02-I2 | Verify the Render ADMIN_LOCKOUT_WORKER_URL and ADMIN_LOCKOUT_WORKER_SECRET are paired with Worker ADMIN_LOCKOUT_SECRET; confirm Durable Object binding/migration and exercise the shared counter. Compare secrets in memory only; record MATCH/MISMATCH, never values. |
| Credential rotation | S08-C1 / S09-C2 and storage/admin credential gates | Rotate/revoke the previously exposed Firebase/GCP service-account key and affected B2, Worker, media-signing, admin, TURN, and link-preview credentials using a coordinated cutover. Confirm old credentials are revoked and audit logs reviewed. Code changes do not rotate deployed keys. |
| Revoked-token integration | S02-I3 | In an isolated staging project, prove old-token rejection after revocation and new-token acceptance on all guarded routes. Not run here per the explicit exclusion. |
| Firebase operator configuration | S10-N1 / S06-M2 | Verify Play Integrity/App Check provider and staged enforcement; confirm _duressNonces.expiresAt TTL and the deployed Firestore Rules revision in the intended project. Repository tests do not deploy these settings. |

### P1 — complete before declaring the security backlog closed

| Workstream | Finding | Required evidence |
|---|---|---|
| Storage migration race | S10-N3 | Run isolated R2-to-B2 migration/delete race tests, including retry and cleanup, without using production user objects. |
| Legacy encrypted-preference migration | S08-H5 / S07-M1 | Prove crash-safe migration and read-back on supported devices. Do not delete legacy data until the encrypted destination is durable and recovery behavior is defined. |
| Android lock behavior | S11-H1 | Verify predictive-back cannot dismiss the lock screen or mandatory gates on API 33+ physical/emulated devices. |
| Call/lock lifecycle | S11-M1 | Test a live call through backgrounding beyond lock timeout, notification return, lock, unlock, and call resumption. |
| Minified release | S08-I1 | Run a minified release/R8 build and verify WorkManager/reflection/serialization paths. Current CI builds a debug APK; that is not a minified release build. |
| FileProvider and sensitive exports | S08-M2 | Test every legitimate export/share path and confirm grants cannot expose arbitrary app files. |
| Link-preview failure semantics | S04-I3 | Add typed blocked-host/timeout/non-HTML/metadata-free outcomes and Android handling, without exposing internal DNS/network details. |
| Media token replay contract | S03-M3 | Reassess 10-minute token lifetime and reusable read/write token behavior against client retry semantics; do not make read/write tokens single-use without compatibility proof. |
| Android logging | S10-N2 | Complete a repo-wide release-log sweep for raw peer/account IDs and add a regression guard. |
| WorkManager runtime behavior | S06-L4 | Validate AccountLockWorker retry/idempotency on an actual WorkManager runtime, not only source-level tests. |
| Backup and trust migrations | S07-H2 / S07-M2 / S07-M3 | Verify legacy restore compatibility, HMAC/AAD tamper rejection, and fingerprint-keyed trust migration with dedicated tests. |

### P2 — reconcile the evidence and documentation

1. Reconcile BUG_TRACKER.md against current main, especially S05-H2, S07-H3, S08-H2, and Android findings whose previous Partial rationale was lack of an Android toolchain. The current main CI now provides lint, unit-test, debug-build, and Espresso evidence; promote only rows whose own required behavior is actually covered.
2. Refresh security-remediation/FINAL_SIGNOFF.md: its evidence snapshot still names the 4 November main SHA 45363ae8... and earlier workflow IDs. Current main is 8b4b663...; current CI/security runs are 41965880585 and 41965880566. Keep status PENDING.
3. Correct stale comments that contradict current source (including the screenshot-default comment) and ensure the trust-boundary document and runbook no longer imply that already-present source controls are absent.
4. Reconcile the tracker roll-up. Its last published count is 116 findings: 74 Fixed, 29 Partial, 1 Open, 12 Accepted. That is the 4 November published tally, not a fresh 5 November row-by-row disposition after the latest invite changes.
5. Correct the finding-ID mismatch in the admin audit wiring test: that test labels the optional User-Agent session behavior as S07-H3, while the canonical tracker uses S07-H3 for group-message AAD. This is a traceability/documentation defect and should be corrected without changing runtime behavior.

## 6. Owner access and safe execution requirements

The remaining cloud tasks require temporary least-privilege operator access in the relevant provider consoles or a controlled execution environment:
- Render service-level configuration and secret-presence read-back (without revealing values).
- Cloudflare account/Worker settings, Durable Object bindings, migrations, and secret pairing.
- Firebase/GCP project configuration, credential rotation, App Check, Firestore TTL and deployed rules.
- Backblaze B2 key rotation and isolated bucket/object test resources.

Do not paste production credentials, Firebase ID tokens, seed phrases, mnemonics, private keys, or admin bearer tokens into GitHub, chat, prompts, source files, or this report. For paired secrets, compare in memory and record only MATCH/MISMATCH.

The connected GitHub and Render read-backs were sufficient to verify branch/deployment metadata. They do not provide the missing Firebase/Cloudflare production proof. The GitHub fetch connection also did not expose the repository's Actions-environment endpoint for a fresh production-release protection read-back; the last recorded operator closeout on 4 November reported no production-release environment and no rulesets.

## 7. Release decision

**Current decision: NOT READY FOR FINAL SECURITY SIGN-OFF.**

Reasons:
1. main remains unprotected, while Render auto-deploys from main.
2. Cloud credential rotation and paired Render/Worker configuration have no fresh operator evidence.
3. S02-I3 live token-revocation integration evidence is still absent and was not attempted in this pass.
4. Firebase App Check, Firestore TTL, and deployed rules need live project read-back.
5. Android and storage migration/device-specific acceptance tests remain.
6. The canonical tracker and final sign-off snapshot have not been reconciled to the 5 November source and CI state.

The report-only branch deliberately makes no production configuration changes and does not change FINAL_SIGNOFF.md to COMPLETE. After the report is reviewed, the next safe sequence is: enforce GitHub governance; perform credential/configuration verification and rotations; complete isolated integration/device tests; reconcile the tracker; then obtain explicit owner sign-off.

---

## 8. Evidence index

- Main commit: https://github.com/kumarclaude4-hash/DuoFatass/commit/8b4b66359558ac656270213dd9de0f58047e4c0d
- CI: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41965880585
- Security scan: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41965880566
- Revocation route PR: https://github.com/kumarclaude4-hash/DuoFatass/pull/212
- Handoff PR: https://github.com/kumarclaude4-hash/DuoFatass/pull/213
- Finding tracker: https://github.com/kumarclaude4-hash/DuoFatass/blob/main/BUG_TRACKER.md
- Final sign-off: https://github.com/kumarclaude4-hash/DuoFatass/blob/main/security-remediation/FINAL_SIGNOFF.md
- Invite helper: https://github.com/kumarclaude4-hash/DuoFatass/blob/main/server/lib/invite.js
- Invite/admin wiring tests: https://github.com/kumarclaude4-hash/DuoFatass/blob/main/server/lib/adminAuditWiring.test.js
- Firestore rules: https://github.com/kumarclaude4-hash/DuoFatass/blob/main/firestore.rules
- Firestore rules tests: https://github.com/kumarclaude4-hash/DuoFatass/blob/main/firestore-tests/rules.test.js
- Group AAD unit tests: https://github.com/kumarclaude4-hash/DuoFatass/blob/main/app/src/test/java/com/duoshield/app/crypto/GroupCipherHelperAadTest.java
- Screenshot security implementation: https://github.com/kumarclaude4-hash/DuoFatass/blob/main/app/src/main/java/com/duoshield/app/BaseActivity.java
- Render service: https://dashboard.render.com/web/srv-d958babtqb8s73ehoqi0

**End of report.**