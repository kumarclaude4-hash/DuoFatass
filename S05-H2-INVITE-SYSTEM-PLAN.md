# S05-H2 — Admin-Issued Invite System Implementation Plan

## Goal
Replace the anonymous, floodable self-request waitlist with a complete admin-issued invite lifecycle. Preserve existing-account restore, keep invite redemption private and single-use, and retain a bounded migration path for previously approved requests.

## Data model
Firestore `invites/{inviteId}` stores `tokenHash` (never the raw bearer token), `tokenFingerprint`, `status`, admin-provided `label` and optional `notes`, `createdAt`, `expiresAt`, and optional `usedAt`, a 12-character pseudonymous `usedByUserIdTag`, `revokedAt`, and `revocationReason`. The raw `usedByUserId` field is legacy-only and must not be written by current code. Expiration is derived and enforced at validation and redemption time.

## Server API
- Retire anonymous `POST /requestAccess` and `GET /waitlistStatus`.
- Add rate-limited `POST /invite/validate` with generic valid/invalid responses.
- Require `inviteToken` for new-account `/mintToken` calls and consume it transactionally with identity creation.
- Temporarily permit only already-approved legacy waitlist IDs so pre-deployment approvals can drain.
- Add authenticated admin create/list/search/filter/paginate/revoke endpoints.
- Return the raw token once at creation; list responses expose only a short fingerprint.
- Audit creation, revocation, and redemption without logging bearer tokens or notes.
- Store only a 12-character pseudonymous `usedByUserIdTag` on redemption; never persist or return the raw seed-derived UID.
- Mask legacy `usedByUserId` values in list projections. Before closing the migration, run `node scripts/pseudonymizeInviteRedemptionUids.js --dry-run` against the intended staging project, review counts, then use `--apply` only with explicit operator approval to pseudonymize tags and delete legacy raw UID fields. Repeat the dry run to confirm zero remaining candidates.

## Android
Refactor `RequestAccessActivity` into invite-code entry, replace waitlist request/poll networking with validation, and thread `inviteToken` through account creation in memory only. Do not persist or log the bearer token.

## Admin UI
Add invite creation, one-time token reveal/copy, status summaries and filters, label search, deterministic pagination, lifecycle metadata, and transactional revocation. Preserve CSP, no-store behavior, safe DOM output, and responsive behavior.

## Migration
Stop creating legacy waitlist documents immediately. Keep a narrowly scoped `/mintToken` compatibility branch for existing `approved` 32-hex request IDs; pending and denied records remain unusable. Remove this branch after the migration window.

## Verification
Add pure invite-domain tests and source/contract tests proving hash-only storage, generic invalid responses, auth-before-body admin mutations, transactional single-use redemption, expiry/revocation enforcement, audit coverage, retired public waitlist routes, Android non-persistence, and legacy-approved-only migration. Run server tests, Firestore emulator integration and available Gradle checks. Defer physical-device onboarding/manual app-flow acceptance until the owner declares the app feature-complete; before that, use isolated staging HTTP/Firestore checks with synthetic identities and invite tokens only.

## Security closeout update — 2026-11-07

PR #255 is merged at 89ac44cf85df1c90530196fdbb01bf93ff2e8994. On exact tested head af8f676c6bdbeb125eb46c01e4aa2b56af30c4f0, CI [42385827187](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827187) passed **10/10 jobs** and Security Scan [42385827198](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827198) passed **3/3 jobs**. The AST privacy gate scanned 204 production Java files and 414 Log.w/e/wtf calls with zero violations. Server/Worker tests and Firestore invite/mintToken transaction integration passed; Android unit tests, lint, debug APK, minified R8 (453,152-line mapping artifact uploaded), Espresso (**4 tests**) and API 35 predictive-back (**1 test**) passed.

The merge corrected a source/data discrepancy: new redemptions persist only usedByUserIdTag, not the raw seed-derived UID. The admin list API never returns a raw legacy usedByUserId value; it returns a tag derived with the configured UID pseudonymizer. The admin UI labels and renders this field as a pseudonymous tag.

Historical documents may still have the legacy raw field. The migration script is server/scripts/pseudonymizeInviteRedemptionUids.js. Run node scripts/pseudonymizeInviteRedemptionUids.js --dry-run from server/ in the intended staging project first. Review only the aggregate candidate/scanned counts. Use --apply only after explicit operator approval; it tags and deletes the legacy raw UID field in batches, prints counts only, and preserves any already-valid tag. Re-run dry-run and require zero candidates. No live Firestore migration has been performed by this PR.

S05-H2 remains **Partial** until the owner confirms the invite-only product contract and authorized staging/Android onboarding acceptance is recorded. Never use real user seeds, identities or invite tokens in test evidence.

### Follow-up lifecycle/utility PRs — current status 2026-11-07

- [PR #253](https://github.com/kumarclaude4-hash/DuoFatass/pull/253) adds explicit `status === "active"` and finite future-expiry checks; unknown, missing or malformed stored states are invalid. It also adds an admin invalid-state filter/count and audited revoke support. Its original CI (10/10), Security (3/3) and Firestore Rules/server transaction checks passed, but the branch is based on an older main and GitHub reports it dirty/non-mergeable. Rebase/resolve onto current main and rerun before merging.
- [PR #258](https://github.com/kumarclaude4-hash/DuoFatass/pull/258) normalizes escaped PEM newlines in the standalone UID migration utility to match the server bootstrap behavior. Its regular CI passed, but Gitleaks flagged a synthetic PEM-shaped test fixture and the Firestore integration workflow failed one invite-expiry test because the one-hour minimum elapsed during the scenario. Replace the synthetic fixture with clearly non-PEM placeholder data and use a deterministic injected clock or safe expiry margin, then rerun Gitleaks and the complete Firestore integration suite.
- Neither PR has been merged at this documentation snapshot. No migration has been run against staging or production.

## Completion criteria
S05-H2 is closed when anonymous queue flooding is impossible; admins can issue, identify, search, page, expire, revoke, and audit invitations; public responses expose no lifecycle oracle; tokens are hash-only at rest and single-use under concurrency; Android does not persist bearer tokens; and all automated/manual checks pass.
