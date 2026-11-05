# S05-M3 — Durable admin session ledger

**Branch:** `security/s05-m3-durable-admin-sessions-2026-11-05`  
**Status:** implementation committed; CI, Firestore emulator and staging evidence pending.  
**Scope:** replace the process-local admin session and revocation authority. This is intentionally separate from PR #218.

## Prior residual

The existing S05-M3 work had already introduced a signed cookie, a 30-minute idle timeout, an 8-hour absolute expiry, read-only `GET /admin` validation, single-session logout and a “Sign out everywhere” action. The remaining weakness was that session existence, individual revocation and bulk-revocation state lived in process-local Maps. A Render restart or a second instance could therefore disagree about whether a cookie was still valid; a bulk revoke on one instance did not invalidate a cookie at another.

## Implementation

- **Firestore is authoritative.** Every cookie-authenticated admin request reads the session record and global control document in one Firestore transaction. There is no process-local acceptance cache or signature-only fallback.
- **Opaque-at-rest identifiers.** The cookie contains a cryptographically random 256-bit session id inside a full-length HMAC-SHA-256 signed token. Firestore document IDs are HMAC-derived with a separate domain label. Neither the raw session id nor the complete cookie is persisted.
- **Atomic session creation.** Session creation reads the global generation and writes the session document in the same transaction. Concurrent first-login initialization and sign-out-everywhere are serialized by Firestore.
- **Idle and absolute expiry.** The 30-minute idle expiry is refreshed only on successful authenticated API requests. The 8-hour absolute expiry is immutable and embedded in the signed token and the server-side record. `GET /admin` remains read-only. Browser cookie Max-Age spans the absolute window so authenticated activity can refresh the server-side idle clock.
- **Single-session revocation.** Logout deletes the server-side record transactionally. If Firestore cannot confirm revocation, the browser cookie is cleared locally but the route returns 503 and does not claim that copied cookies were revoked.
- **Fleet-wide revocation.** “Sign out everywhere” increments `adminSessionControl/global.generation` in one transaction. Every older session is rejected on every instance without scanning or rewriting all session documents. A newly created session receives the current generation.
- **Credential rotation.** The final session signing key is derived with HKDF from `ADMIN_TOKEN` plus optional `ADMIN_SESSION_SECRET` key material. Rotating `ADMIN_TOKEN` invalidates cookies even when an explicit session secret is present. An explicit `ADMIN_SESSION_SECRET` shorter than 32 UTF-8 bytes refuses startup.
- **Fail closed.** Firestore errors during cookie authentication return 503, are not converted into a 401, and do not increment failed-login counters. Direct `x-admin-token` authentication remains independent of the session ledger.
- **Rules and cleanup.** `adminSessions` and `adminSessionControl` are denied to client SDKs. A TTL policy on `adminSessions.purgeAt` reclaims expired records physically; authorization checks exact timestamps and never relies on Firestore TTL scheduling.
- **Mobile compatibility.** IP and User-Agent enforcement remains disabled by default. Existing audit evidence documents real mobile IP re-NAT and Android WebView navigation-versus-fetch User-Agent differences that caused false session expiry.

## Files changed

- `server/lib/adminSessionStore.js` — Firestore-backed async store, HMAC-separated opaque document ids, generation check, expiry, revoke, sweep.
- `server/index.js` — inject Firestore; await create/validate/revoke paths; explicit fail-closed 503 handling; durable global generation; 8-hour cookie Max-Age; token-rotation key derivation.
- `server/lib/adminSessionStore.test.js` — fake-Firestore regression suite for cross-instance validation, opaque storage, malformed/tampered cookies, idle/absolute expiry, refresh, logout, global revocation, concurrent generation changes, Firestore errors, optional UA binding and cleanup.
- `server/lib/adminAuditWiring.test.js` — source-wiring checks for durable backing, awaited validation/revocation, fail-closed behavior and client-deny rules.
- `firestore.rules` — deny-all client rules for both new collections.
- `firestore.indexes.json` — TTL cleanup policy for expired admin session records.
- `server/README.md` — environment variable, architecture and rollout instructions.
- `BUG_TRACKER.md` — S05-M3 disposition corrected to Partial pending verification.

## Required verification before merge/deployment

1. Run the server test suite and relevant lint/static checks on the PR head. Confirm the pre-existing native `@signalapp/libsignal-client` test issue separately if still present; do not misattribute it to this change.
2. Run the Firestore Rules Emulator suite with cases proving anonymous and authenticated clients cannot read, create, update or delete either collection.
3. Run the session-store suite against the Firestore emulator (not only the fake implementation), including two independent store instances sharing one emulator project.
4. Deploy `firestore.rules` and `firestore.indexes.json` using `firebase deploy --only firestore:rules,firestore:indexes` before or as part of the server rollout.
5. In staging, sign in on one instance, validate on another, revoke a single session and prove rejection on both, then run “Sign out everywhere” and prove all pre-existing cookies fail across instances while a newly created session succeeds.
6. Simulate Firestore unavailability and confirm cookie auth and logout fail closed with 503, no false 401/failure-counter increment occurs, and direct token auth behavior is unchanged.
7. Verify actual Firestore TTL policy activation in the project; TTL is cleanup, not an access-control dependency.
8. Confirm the operator can re-authenticate after deployment. Old process-local cookies are deliberately rejected by the new token format and durable ledger.

## Residual risk / not claimed fixed

A stolen, unexpired HttpOnly session cookie is still a bearer credential until its record is revoked, the global generation advances, the idle timeout expires or the absolute ceiling is reached. HttpOnly/Secure/SameSite and short expiry reduce exposure but do not make a replayed cookie non-transferable. WebAuthn/passkey step-up for destructive admin actions is a separate enhancement and is not part of this patch. IP/UA binding is deliberately not re-enabled.

No production secrets were read or changed, no live Firestore rules/indexes were deployed, no staging environment was exercised, and this branch is not merged. Do not mark S05-M3 fully Fixed until CI, emulator and staging acceptance evidence is attached.
