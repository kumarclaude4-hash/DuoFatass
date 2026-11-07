# S05-H2 Invite Redemption UID Pseudonymization — 2026-11-07

**Finding:** S05-H2 — invite lifecycle and operator visibility  
**Code PR:** https://github.com/kumarclaude4-hash/DuoFatass/pull/255  
**Tested head:** af8f676c6bdbeb125eb46c01e4aa2b56af30c4f0  
**Merge commit:** 89ac44cf85df1c90530196fdbb01bf93ff2e8994  
**Disposition:** Partial — code correction and automated verification merged; product-contract and staging/Android onboarding acceptance remain.

## Finding re-check

The invite lifecycle already stored token hashes/fingerprints, transactionally audited creation/redemption/revocation, supported listing/pagination and validated one-time redemption. The privacy review found that the invite document still persisted the seed-derived user identifier in a field named usedByUserId, and the admin listing exposed that field verbatim. This conflicted with the tracker claim that redemption linkage was pseudonymized.

## Implemented in PR #255

- New redemption computes the UID pseudonym once through the existing injected tagUserId function.
- Firestore invite records store usedByUserIdTag only; the raw seed-derived UID is not written.
- The transaction-coupled invite_redeemed audit event uses the same tag.
- Admin list projection returns usedByUserIdTag. Legacy records with usedByUserId are masked by deriving a tag before the response projection; raw UID values are never returned by the list API.
- Admin UI labels the field “Redeemed user tag” and uses textContent to render it.
- HTTP, structural and Firestore integration tests prove that raw UIDs are not stored for new redemptions and are not returned for legacy records.

## Historical-data migration

Added server/scripts/pseudonymizeInviteRedemptionUids.js and tests.

- Default mode is read-only dry-run.
- --apply is mandatory for writes.
- Pagination is bounded to 200 documents per Firestore batch.
- Each legacy UID is transformed to a 12-hex HMAC display tag using a random process-local pepper; an existing valid tag is preserved.
- The raw usedByUserId field is deleted from each processed invite document.
- Console output contains aggregate counts only; no account identifiers or raw values.
- No staging or production Firestore migration was run as part of this PR.

The authorized operator must select the intended staging project, run from the server directory:
node scripts/pseudonymizeInviteRedemptionUids.js --dry-run
Review candidate/scanned counts. Only after explicit approval, run:
node scripts/pseudonymizeInviteRedemptionUids.js --apply
Then repeat dry-run and require zero candidates. Do not execute against production without a separately authorized migration/change window and rollback/evidence plan.

## Verification

On exact PR head af8f676c6bdbeb125eb46c01e4aa2b56af30c4f0:

- CI run 42385827187: 10/10 jobs passed.
- Security Scan run 42385827198: 3/3 jobs passed.
- AST scan: 204 production Java files, 414 Log.w/Log.e/Log.wtf calls, zero policy violations.
- Server + Worker suite passed.
- Firestore Rules and server-transaction integration passed, including invite lifecycle and mintToken identity-claim coverage.
- Android unit tests, lint and debug APK passed.
- Minified release R8 passed; mapping file contained 453,152 lines and the artifact uploaded successfully.
- Espresso: 4 tests executed.
- API 35 predictive-back regression: 1 test executed.

Links:
- CI: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827187
- Security: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827198

The emulator-backed tests are automated CI evidence, not physical-device acceptance.

## Remaining acceptance

Keep S05-H2 Partial until:
1. The owner confirms invite-only onboarding is the intended product contract.
2. Authorized staging verifies create/list/search/paginate/revoke/expiry/validate and one-time redemption.
3. The legacy UID dry-run and approved migration are completed in the intended environment, with a zero-candidate follow-up dry-run.
4. Android new-account invite onboarding and existing-account restore are accepted with synthetic identities/invites after the owner declares the app feature-complete.

Do not use real seeds, private keys, account tokens or real invite bearer tokens in test evidence. Do not claim production deployment or production migration based on this PR.
