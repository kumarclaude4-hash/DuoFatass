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
