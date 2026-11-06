# Duress-lock reliability closeout — 2026-11-05

**Finding:** S06-L4 — AccountLockWorker false success / unbounded retry / stale nonce recovery.
**Implementation branch:** `security/duress-lock-worker-failure-signaling-2026-11-05`
**Target PR:** #225 (CI validation pending).

## Code changes

### Android worker result semantics

- Only HTTP 200/204 is treated as a confirmed server-side lock.
- Missing nonce or missing server URL is a real `Result.failure()`, not `Result.success()`.
- Permanent HTTP 4xx responses (excluding 408/425/429) return `Result.failure()` and retain the durable intent.
- The one-hour nonce retry horizon is bounded to seven attempts with exponential backoff beginning at 30 seconds (about 31.5 minutes of backoff plus initial jitter). Exhaustion surfaces a WorkManager failure while preserving the intent.
- A response from an old worker may clear only the exact nonce it used. It cannot erase a newer pending token or warm nonce.

### Server nonce consumption and replay

- `consumeDuressLockNonceInTransaction` consumes the nonce and writes `accountLock` in one Firestore transaction.
- The `consumedAt` marker is an idempotency receipt. A retry after a lost HTTP response returns success for the original commit without re-locking the account.
- The helper also returns whether the duress lock is still active. If an administrator has unfrozen the account after the original commit, the retry does not re-lock it and the route does not revoke newly issued refresh tokens again.
- Expired and malformed nonce records are deleted by returning a failure result from the transaction, not by throwing after a write (which would roll back the delete).
- Stored UID values are validated before they are interpolated into Firestore document paths.

### Safe nonce rotation and recovery

- `pruneOutstandingDuressNonces` deletes only unconsumed credentials, in batches capped below Firestore's 500-write limit. Consumed idempotency receipts remain until their one-hour TTL.
- Foreground credential maintenance drains an existing pending nonce before requesting a replacement. A transient result preserves the existing nonce; a permanent rejection permits a fresh nonce.
- A fresh pending nonce replaces an old one only when the durable intent UID matches the current Firebase user.
- Nonce issuance is serialized between ordinary foreground maintenance and the duress trigger. Duplicate duress triggers and duplicate credential refreshes are guarded.
- `PendingLockStore` state changes are synchronized and compare-and-clear operations protect newer state from delayed WorkManager or HTTP responses.

## Regression coverage added

### Firestore emulator

`duressLock.firestore.integration.test.js` verifies:
- first nonce consumption and account-lock creation;
- replay after a lost response;
- concurrent requests across independent Firestore clients;
- replay after an administrator has unfrozen the account (no re-lock);
- retention of consumed receipts when unconsumed credentials are pruned;
- cleanup of expired/malformed nonce records;
- rejection of path-like UIDs;
- preservation of one-use semantics.

### Android JVM

`AccountLockWorkerFailureTest` verifies:
- success/permanent/retryable HTTP response classification;
- only confirmed 200/204 clears the intent;
- permanent failure retains the intent and invalidates only the matching warm nonce;
- bounded retry count;
- fresh nonce refresh limited to the same UID;
- transient drain failure does not rotate a possibly committed nonce;
- compare-and-clear and nonce-issuance serialization guards.

### Server wiring

`duressLockWiring.test.js` verifies the HTTP route delegates to the shared transaction helper, nonce pruning retains consumed receipts, replay after administrative unfreeze avoids duplicate token revocation, and worker result semantics remain fail-closed.

## Reliability boundary / residual limitation

A remote lock cannot be guaranteed if a device has no connectivity and never again establishes a valid authenticated session for the same account. The app preserves a durable local intent and explicitly does not claim that remote lock succeeded. When that account next establishes a valid foreground session, it can obtain a fresh nonce and retry. This is an explicit network/authentication dependency, not a silent success path.

## Acceptance still required

- GitHub Actions: Android compile/unit/lint/Espresso and server/Worker/Firestore/security checks must pass on the final PR head.
- Staging acceptance: simulate network loss after server commit but before HTTP response; confirm idempotent replay does not relock after admin unfreeze or revoke fresh sessions.
- Device acceptance: verify WorkManager retry, app wipe, reboot, sign-out/sign-in, same-UID recovery and connectivity restoration on physical devices.
- Production acceptance: confirm Firestore TTL policy for `expiresAt`, service-account permissions and the production lock rules.
- This code change is not a claim of production security sign-off.
