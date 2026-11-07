# S05-H2 — Admin-Issued Invite System Implementation Plan

## Goal
Replace the anonymous, floodable self-request waitlist with a complete admin-issued invite lifecycle. Preserve existing-account restore, keep invite redemption private and single-use, and retain a bounded migration path for previously approved requests.

## Data model
Firestore `invites/{inviteId}` stores `tokenHash` (never the raw bearer token), `tokenFingerprint`, `status`, admin-provided `label` and optional `notes`, `createdAt`, `expiresAt`, and optional `usedAt`, `usedByUserId`, `revokedAt`, and `revocationReason`. Eligibility requires an explicit `active` state and finite future expiry; unknown/missing states fail closed and appear as invalid in the admin list.

## Server API
- Retire anonymous `POST /requestAccess` and `GET /waitlistStatus`.
- Add rate-limited `POST /invite/validate` with generic valid/invalid responses.
- Require `inviteToken` for new-account `/mintToken` calls and consume it transactionally with identity creation.
- Temporarily permit only already-approved legacy waitlist IDs so pre-deployment approvals can drain.
- Add authenticated admin create/list/search/filter/paginate/revoke endpoints.
- Return the raw token once at creation; list responses expose only a short fingerprint.
- Audit creation, revocation, and redemption without logging bearer tokens or notes.

## Android
Refactor `RequestAccessActivity` into invite-code entry, replace waitlist request/poll networking with validation, and thread `inviteToken` through account creation in memory only. Do not persist or log the bearer token.

## Admin UI
Add invite creation, one-time token reveal/copy, status summaries and filters, label search, deterministic pagination, lifecycle metadata, and transactional revocation. Preserve CSP, no-store behavior, safe DOM output, and responsive behavior.

## Migration
Stop creating legacy waitlist documents immediately. Keep a narrowly scoped `/mintToken` compatibility branch for existing `approved` 32-hex request IDs; pending and denied records remain unusable. Remove this branch after the migration window.

## Verification
Add pure invite-domain tests and source/contract tests proving hash-only storage, generic invalid responses, auth-before-body admin mutations, transactional single-use redemption, expiry/revocation enforcement, fail-closed handling of unknown/missing persisted invite states, audit coverage, retired public waitlist routes, Android non-persistence, and legacy-approved-only migration. Run server tests and available Gradle checks, then manually exercise creation, filtering, pagination, revocation, expiration, concurrent redemption, new-account enrollment, and existing-account restore.

## Completion criteria
S05-H2 is closed when anonymous queue flooding is impossible; admins can issue, identify, search, page, expire, revoke, and audit invitations; public responses expose no lifecycle oracle; tokens are hash-only at rest and single-use under concurrency; Android does not persist bearer tokens; and all automated/manual checks pass.
