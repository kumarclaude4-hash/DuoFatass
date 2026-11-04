# DuoShield — Remaining Security Work
## Replit Agent Execution Runbook

**Prepared:** 4 November 2026  
**Repository:** https://github.com/kumarclaude4-hash/DuoFatass  
**Purpose:** Operator handoff for completing the remaining production, credential, Firebase, release-governance and legacy-device work after the security-remediation merge batch.

> **CRITICAL SECRET-HANDLING RULE:** This runbook contains secret NAMES, never secret VALUES. Put values only in Replit Secrets or the destination platform's encrypted secret manager. Never paste credentials into the agent prompt, this document, GitHub issues, source files, terminal output or logs.

## 1. Executive handoff

The source remediation is substantially merged. PRs #201–#205 and #207–#209 are merged; #206 was closed as a duplicate of #205; PR #200 was already merged separately. PR #209 was the final remediation PR.

- **Latest main SHA at handoff:** `0006313d18dae0b3a402083e92c3eff303476df3`
- **Code baseline with recorded green CI/security evidence:** `c38967540294355ae94adb3f913d8f637dd1693d`
- **Final remediation PR merge:** #209, merge commit `00aec631041643e0ce7b35b9c1220d35875a48ee`
- **CI run:** [#41810314589](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41810314589)
- **Security scan:** [#41810314539](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41810314539)
- Recorded checks included server and Worker tests, Android lint/unit tests, debug APK build, Espresso (2 tests executed), Gradle wrapper validation, libsignal integrity and security scan. The later main commits were audit-document-only at the time of handoff.
- **FINAL_SIGNOFF.md remains PENDING.** Green CI is not proof that live production configuration, credential revocation, App Check or release governance is complete.

Before acting, fetch current main and verify the SHAs, open PR queue, deployment SHAs and latest workflow runs. If code changed after the recorded baseline, rerun relevant checks.

## 2. Remaining work register

| Priority | Workstream | Finding / gate | Required closure evidence |
|---|---|---|---|
| P0 | Verify shared Durable Object limiter in Render production | S02-I2 | Render/Worker secret pair matches; DO binding/migration live; startup log confirms shared backend; smoke test passes. |
| P0 | Rotate exposed Firebase/GCP service-account key | S08-C1 / S09-C2 | Least-privilege replacement works; old exposed key revoked; audit logs reviewed. |
| P0 | Live Firebase revoked-token integration test | S02-I3 | Old ID token rejected after revoke; newly issued token accepted on every protected route in staging. |
| P0 | Rotate Worker, B2, media-signing and admin credentials | Credential migration gates | New credentials deployed and tested; old credentials revoked only after successful cutover. |
| P1 | App Check rollout and Firestore TTL/rules verification | S10-N1 / S06-M2 | Play Integrity metrics, staged enforcement and deployed TTL/rules evidence. |
| P1 | GitHub branch/tag/environment protection | SC-12 | Required checks/reviews, immutable v* tags and production-release approvals configured. |
| P1 | Android signing-key exposure assessment | S09-C1 | Play App Signing state confirmed; any upload-key reset proven update-compatible. |
| P1 | Legacy plaintext preference migration | S08-H5 / S07-M1 | Encrypted migration and read-back verified on devices, or residual risk formally accepted. |
| P2 | Tracker and documentation reconciliation | Closeout | Finding tally recounted, stale references corrected, evidence linked, sign-off still pending until gates close. |

## 3. Rules of engagement

1. Start read-only. Use a clean worktree; fetch origin/main; inspect status, history, open PRs, current Actions runs and live deployment SHAs.
2. Do not push directly to main. All code/document changes go on a named branch and through a PR.
3. Do not guess project IDs, resource IDs, key names, URLs, permissions or deployment state. Verify via authenticated consoles/APIs.
4. Never run `env`, `printenv`, `set`, shell tracing or commands that dump environment variables. Never echo/log secrets or upload secret files as artifacts.
5. Never put secrets in `.env`, `local.properties`, source, APKs, GitHub comments, artifacts or evidence reports.
6. Use staging/test identities for mutating tests. Never run destructive tests against real users, production media or customer accounts.
7. Do not disable security controls, weaken tests, lower required checks or turn fail-closed behavior into fail-open to make a test pass.
8. For production changes, capture before-state, identify rollback, coordinate paired-key cutovers, deploy in dependency order, test immediately and record timestamp/resource/result.
9. Prefer temporary, resource-scoped API tokens over Owner/global credentials. Revoke temporary Replit operator credentials after closeout.
10. **STOP and request explicit owner approval** before irreversible Android signing-key changes, deleting legacy user key material, disabling a live credential before its replacement works, enforcing App Check without telemetry, or triggering a production release.

### 3.1 Secret-safe workflow

- Owner adds required values to Replit → Secrets. Never put values in the agent prompt.
- Agent reads secrets only in process memory (e.g. `process.env.NAME` or `os.environ`).
- For paired secrets, compare in memory and report only **MATCH/MISMATCH**. Never print a value, prefix, suffix or reusable hash.
- Generate replacement secrets in memory or through provider UI and send directly to destination secret managers.
- Report only variable name, configured/missing, destination, rotation timestamp and validation result.

## 4. Access and secret inventory

Temporary Replit operator credentials are separate from DuoShield runtime secrets. Add only the smallest scopes needed for the current phase.

### 4.1 Temporary Replit operator credentials

| Replit secret name | Purpose | Scope guidance |
|---|---|---|
| `RENDER_API_KEY` | Inspect DuoFat, update Render env and inspect deployments | Limit to required workspace/service; revoke after closeout. |
| `CLOUDFLARE_API_TOKEN` | Manage/deploy Worker secrets, bindings and DO | Limit to relevant account and Worker/script permissions. Never use global API key. |
| `GCP_OPERATOR_CREDENTIALS_JSON` | Service-account rotation, TTL/rules and App Check administration | Separate operator identity, not runtime identity; least privilege and revoke after. |
| `FIREBASE_STAGING_TEST_EMAIL`, `FIREBASE_STAGING_TEST_PASSWORD` | Revoked-token and route integration tests | Dedicated staging identity only. |
| `B2_ROTATION_KEY_ID`, `B2_ROTATION_APPLICATION_KEY` | Backblaze key rotation | Bucket-restricted to duoshield-cold and only required capabilities. |
| `GITHUB_TOKEN` (only if integration unavailable) | PRs, Actions and repo settings | Repository-scoped minimum permissions; prefer connected GitHub integration. |

Non-secret identifiers may be supplied separately: `FIREBASE_PROJECT_ID`, `CLOUDFLARE_ACCOUNT_ID`, `RENDER_WORKSPACE_ID`, `RENDER_SERVICE_ID`.

Known Render target from handoff: workspace **My Workspace** / `tea-d8qhp40js32c7394cbsg`; service **DuoFat** / `srv-d958babtqb8s73ehoqi0`; URL https://duofat.onrender.com; root directory `server`; deploy branch `main`; auto-deploy enabled. Verify these against the live account before changes.

### 4.2 Render runtime variable names

- `GOOGLE_APPLICATION_CREDENTIALS_JSON`
- `ADMIN_TOKEN`
- `MEDIA_TOKEN_SECRET`
- `ADMIN_LOCKOUT_WORKER_URL`
- `ADMIN_LOCKOUT_WORKER_SECRET`
- `TURN_TOKEN_ID`
- `TURN_API_TOKEN`
- `LINK_PREVIEW_PROXY_SECRET)

Other runtime variables may exist; use `server/README.md` and actual code as source of truth. `PUBLIC_BASE_URL` and `MAX_INITIAL_MESSAGE_AGE_MS` are identifiers/configuration, not secrets.

### 4.3 Cloudflare Worker secrets and bindings

Worker: `duoshield-storage`; hot R2 bucket: `duoshield-hot`; cold B2 bucket: `duoshield-cold`; B2 endpoint: `https://s3.eu-central-003.backblazeb2.com`; region: `eu-central-003`; KV binding: `RATE_KV`.

Secret names:
- `B2_ACCESS_KEY_ID`
- `B2_SECRET_ACCESS_KEY`
- `MEDIA_TOKEN_SECRET`
- `ADMIN_LOCKOUT_SECRET`
- `STATS_SECRET`

Confirm the live account, bindings, DO class and migrations. Paired Render/Worker values must match in memory only.

### 4.4 GitHub Actions / release settings

Release secrets requiring inventory/rotation assessment:
- `GOOGLE_SERVICES_JSON`
- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Release configuration may include `B2_BUCKET`, `B2_REGION`, `PUSH_SERVER_URL`, `WORKER_URL`. Do not print values.

### 4.5 Obsolete or dangerous names

- `KV_REST_API_URL` and `KV_REST_API_TOKEN` are from the removed Upstash path. Verify no active consumer before removal.
- Historical `WORKER_SECRET` is compromised/obsolete. Never reuse it.
- Never reintroduce B2 keys, service-account JSON, Worker secrets or long-lived bearer credentials into Android source, `local.properties`, BuildConfig, APKs or artifacts.

## 5. Ordered execution plan

### Phase 0 — Read-only preflight

- [ ] Fetch origin/main; record current SHA, clean status and last commits.
- [ ] Confirm PRs #201–#205 and #207–#209 merged; #206 duplicate-closed; #200 already merged.
- [ ] Check current CI/Security runs. Preserve historical evidence links above, but do not treat them as proof for later code changes.
- [ ] Inspect Render DuoFat and Cloudflare `duoshield-storage` live deployment SHAs.
- [ ] Confirm Worker has the Durable Object class, binding and latest alarm cleanup/migration.
- [ ] Create a protected evidence folder, e.g. `evidence/operator-closeout/YYYY-MM-DD/`. Store redacted metadata only—no secrets, private keys, bearer tokens or raw user data.
- [ ] Report missing API scopes before requesting more credentials.

### Phase 1 — S02-I2: shared Durable Object rate limiter

The implementation is merged. If the paired URL/secret is missing or mismatched, the server intentionally falls back to a bounded process-local counter; that fallback is degraded and does not span Render instances or survive restarts.

1. Inspect Render variable **names only**. Confirm `ADMIN_LOCKOUT_WORKER_URL` and `ADMIN_LOCKOUT_WORKER_SECRET` exist.
2. Inspect Cloudflare Worker secret/bindings. Confirm `ADMIN_LOCKOUT_SECRET`, `ADMIN_LOCKOUT_DO` bound to `AdminLockoutCounter`, and migration `v1-admin-lockout-do` present in live deployment.
3. Compare Render `ADMIN_LOCKOUT_WORKER_SECRET` and Worker `ADMIN_LOCKOUT_SECRET` in memory. Result must be MATCH. If not, create one new high-entropy value and set both sides from the same in-memory source.
4. Set `ADMIN_LOCKOUT_WORKER_URL` to the verified Worker base URL with no path suffix; server appends `/adminLockout`.
5. Controlled restart/redeploy. Confirm Render startup log: **“admin lockout + authenticated API rate limits: Durable-Object-backed”**. Any fallback warning means not complete.
6. Smoke-test unauthorized rejection, authorized decision, independent user/endpoint buckets and logged degraded behavior during Worker/network outage.

### Phase 2 — P0: rotate exposed Firebase/GCP service-account key

1. Identify exposed service account/key IDs using IAM and Cloud Audit Logs. Never display private key material.
2. Create a dedicated Render push-server service account with only the Firestore and Firebase Cloud Messaging permissions actually required. Do not grant Owner/Editor for convenience.
3. Validate permissions in staging. Create a new key only if key-based credentials are required.
4. Transfer new JSON directly into Render `GOOGLE_APPLICATION_CREDENTIALS_JSON`; do not create a local file or commit JSON.
5. Deploy/restart Render. Verify startup, Firestore listener, delivery-status writes and FCM delivery to a controlled test device. Inspect logs for permission errors.
6. Only after successful verification, disable/delete the exposed old key. Review audit logs for unexpected use. Record key IDs/status and timestamps, never key values.

### Phase 3 — P0: rotate B2, Worker, media, admin and TURN credentials

1. Create a bucket-restricted B2 application key for `duoshield-cold`; update Worker `B2_ACCESS_KEY_ID` and `B2_SECRET_ACCESS_KEY`.
2. Test controlled PUT/GET/DELETE and tiering against a non-user test object. Confirm expected lifecycle behavior. Revoke old B2 key only after successful tests.
3. Rotate `MEDIA_TOKEN_SECRET` on Render and Worker in a coordinated cutover. Verify media-token issue/validation and existing client behavior.
4. Rotate paired `ADMIN_LOCKOUT_SECRET` / `ADMIN_LOCKOUT_WORKER_SECRET` together, then re-run Phase 1 verification.
5. Separately rotate `ADMIN_TOKEN`, `STATS_SECRET`, `TURN_TOKEN_ID`, `TURN_API_TOKEN` and `LINK_PREVIEW_PROXY_SECRET` if exposed or required by the incident response. Test each consumer before revoking its predecessor.
6. Confirm no old credential is present in source, APKs, CI artifacts or logs. Treat obsolete `WORKER_SECRET` as never-reusable.

### Phase 4 — P0: S02-I3 live Firebase token-revocation integration

1. Enumerate every live `verifyIdToken` call in `server/index.js`; compare with `server/lib/revokedIdTokenWiring.test.js`.
2. Use a dedicated staging test account. Obtain an ID token and invoke protected routes with valid route payloads.
3. Call Firebase Admin `revokeRefreshTokens(uid)`.
4. Prove old token is rejected on every protected route; obtain a fresh token and prove it succeeds.
5. Current route inventory to verify (re-discover current source before testing): `/migrateUid`, `/createChat`, `/turnCredentials`, `/mediaToken`, `/linkPreview`, `/removeGroupMember`, `/requestLockNonce`, `/signal/registerBundle`, `/signal/appendPreKeys`, `/signal/updatePreKey`, `/signal/claimPreKey`.
6. Preserve the special `/duress-lock` nonce-auth flow. Do not assume route count is fixed. Ensure tokens are not present in logs/artifacts.
7. Until all routes pass, S02-I3 remains Partial.

### Phase 5 — P1: Firebase App Check

- Verify Play Integrity is configured for release builds and debug provider only for debug builds.
- Inspect App Check metrics and verify compatible clients attach valid tokens.
- Roll out enforcement gradually by actually used Firebase service (Firestore and any other active service).
- Do not enable enforcement blindly or block existing clients without telemetry and an approved rollout/rollback plan.

### Phase 6 — P1: Firestore TTL and rules

- `firestore.indexes.json` specifies collection group `_duressNonces`, field `expiresAt`, TTL enabled.
- Confirm TTL is deployed to the correct Firebase project/database; do not infer deployment from checked-in JSON.
- Run Firestore emulator/rules tests first. Historical rules suite was 186/186; rerun current tests.
- Compare production rules with intended rules. Deploy rules and indexes separately, only after explicit owner approval and correct project confirmation.
- Index command: `firebase deploy --only firestore:indexes --project "$FIREBASE_PROJECT_ID"`
- Rules command: `firebase deploy --only firestore:rules --project "$FIREBASE_PROJECT_ID"`

### Phase 7 — P1: GitHub release governance

- Protect `main`: PR required, at least one review, dismiss stale approvals, require resolved conversations and all current required CI checks.
- Required checks should include Server + Worker tests, Lint, Unit tests, Build debug APK, Instrumented tests (Espresso), Validate Gradle wrapper JAR (SC-07), Verify vendored libsignal JAR (SC-01), Security scan.
- Disable direct pushes, force pushes and branch deletion.
- Add immutable `v*` tag ruleset.
- Configure `production-release` environment with required reviewers and allowed refs/tags.
- Verify settings by reading them back; preserve sanitized evidence.

### Phase 8 — P1: Android signing-key exposure

**IRREVERSIBLE — OWNER APPROVAL REQUIRED.** Determine whether Google Play App Signing is enabled and whether the exposed key is the upload key or app-signing key. Never generate a new key or overwrite release secrets until this is understood. If upload-key reset is appropriate, follow Play Console process and prove installed-app update compatibility before changing GitHub `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. A wrong rotation can prevent users from updating the app.

### Phase 9 — P1: legacy plaintext preference migration

- Assess S08-H5/S07-M1 on representative devices.
- New persistent plaintext fallback is removed, but existing plaintext preferences may remain on older devices.
- Do not delete legacy key material until encrypted migration completes and read-back verifies on-device.
- If durable encrypted storage is unavailable, preserve legacy data, block unsafe onboarding/restore and propose a user-approved recovery/consent flow. Escalate product decision to owner.

### Phase 10 — P2: tracker and sign-off reconciliation

- Recount every finding row in `BUG_TRACKER.md`; do not infer counts from old summaries.
- Reconcile stale references in `security-remediation/START_HERE.md` and `security-remediation/FINAL_SIGNOFF.md`.
- Add sanitized evidence with timestamps, resource identifiers, command/test outcomes and links.
- Mark each item Fixed / Partial / Open / Accepted only with evidence. Accepted requires explicit owner risk acceptance.
- Keep `FINAL_SIGNOFF.md` **PENDING** while any P0/P1 gate is open or unverified.

## 6. Safe verification commands

Run from a clean checkout and verify current package scripts before execution:

```bash
git fetch origin
git status --short
git rev-parse origin/main
git log -5 --oneline

cd server && node --check index.js && npm test
cd worker && node --check src/index.js && node --check src/adminLockoutDurableObject.js && npm test

./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest

cd firestore-tests && npm ci && npm test
firebase deploy --only firestore:indexes --project "$FIREBASE_PROJECT_ID"
firebase deploy --only firestore:rules --project "$FIREBASE_PROJECT_ID"

cd worker && npx wrangler deploy
curl -fsS https://duofat.onrender.com/health
curl -fsS https://duofat.onrender.com/status
```

Do not run deploy commands blindly against production. Confirm account/project, use staging first and obtain the relevant owner approval before production deployment. Do not print secret values while running any command.

## 7. Final acceptance matrix

| Gate | Closure condition | Handoff status |
|---|---|---|
| S02-I2 | Paired secret matches; Worker URL/DO live; production log and smoke test confirm shared backend. | Partial until live verification. |
| S02-I3 | Revoked old token rejected on all protected routes; fresh token accepted. | Partial until live integration. |
| GCP service account | Least-privilege replacement works; exposed old key revoked; audit logs reviewed. | Open operator task. |
| B2 / Worker / admin / TURN | New credentials deployed/tested; old exposed credentials revoked; paired values match. | Open operator task. |
| App Check | Play Integrity observed; enforcement rolled out safely per service. | Partial / operator rollout. |
| Firestore TTL/rules | Correct project/database confirmed; tests pass; deployment evidence recorded. | Open verification/deploy task. |
| SC-12 | Main protections, tag ruleset and production-release approvals verified. | Open operator task. |
| S09-C1 | Play App Signing/upload-key state confirmed; any rotation update-compatible. | Open investigation; owner approval required. |
| S08-H5/S07-M1 | Legacy preferences migrated safely or residual explicitly accepted. | Partial pending device/product decision. |
| Final sign-off | All findings recounted, evidence complete, residuals approved, smoke tests pass. | **PENDING** |

## 8. Copy/paste master prompt for the Replit agent

You are the infrastructure and security closeout agent for DuoShield. Read this entire runbook before acting.

**Repository:** https://github.com/kumarclaude4-hash/DuoFatass  
**Recorded code baseline:** `c38967540294355ae94adb3f913d8f637dd1693d`  
**Recorded latest main at handoff:** `0006313d18dae0b3a402083e92c3eff303476df3`  
**FINAL SIGN-OFF:** PENDING.

Begin read-only. Fetch origin/main and report current SHA, CI/Security run IDs, open PRs, live Render/Worker deployment identities, secret NAMES present/missing (never values), missing API scopes and the next safe task. Execute one phase at a time with before/after evidence and rollback plan.

Never print, echo, log, hash-display, upload, commit or paste secret values. Read secrets only from Replit Secrets/environment variables. Never use `env`, `printenv`, `set` or shell tracing. Never write secrets to files, source, APKs, GitHub comments, artifacts or evidence. Use staging identities for tests. Do not push directly to main. Do not weaken tests or security controls.

Execute Phases 0–10 in order. Stop for owner approval before signing-key changes, legacy key deletion, premature credential revocation, blind App Check enforcement or production release. Do not mark a finding Fixed without evidence. At the end report every finding as Fixed / Partial / Open / Accepted with evidence links. Do not claim completion while any P0/P1 gate or sign-off checkbox remains unresolved.

## 9. Owner handoff checklist

- [ ] Use a private Replit workspace; do not make the Repl public.
- [ ] Add only temporary operator credentials required for the current phase.
- [ ] Ask the agent to report missing scopes before adding more secrets.
- [ ] Use a dedicated staging Firebase test account/project.
- [ ] Keep Play Console/signing-key actions behind explicit approval.
- [ ] Review production changes and evidence phase-by-phase, not as one unreviewed batch.
- [ ] After closeout, remove temporary Replit operator secrets and revoke temporary control-plane tokens.

## 10. Source-of-truth repository references

- `BUG_TRACKER.md`
- `security-remediation/FINAL_SIGNOFF.md`
- `security-remediation/START_HERE.md`
- `security-remediation/migration/MIGRATION_PLAN.md`
- `server/README.md`
- `worker/README.md`
- `worker/wrangler.jsonc`
- `firestore.indexes.json`
- `firestore.rules`
- `server/lib/revokedIdTokenWiring.test.js`
- [PR #209](https://github.com/kumarclaude4-hash/DuoFatass/pull/209)
- [CI run #41810314589](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41810314589)
- [Security scan #41810314539](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41810314539)

> **FINAL RULE:** Merged code + green CI is necessary but not sufficient for production sign-off. The agent must verify deployed configuration, revoke exposed credentials, test real Firebase revocation behavior, enforce governance controls and preserve an auditable record. `FINAL_SIGNOFF.md` stays PENDING until every criterion is met or explicitly accepted by the owner.
