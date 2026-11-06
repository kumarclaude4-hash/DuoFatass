# S03-M3 — Media capability-token residual decision

**Date:** 2026-11-06  
**Status:** OWNER DECISION REQUIRED — NOT CLOSED  
**Finding:** S03-M3  
**Scope:** server minting, Worker verification/replay enforcement, Android token-use lifecycle

## 1. Current implementation and evidence

The media capability format is versioned and signed:

`v1.<op>.<expiresAt>.<holder>.<jti>.<sig>`

Each mint receives a random `jti`, and the `jti` is covered by the HMAC. The Worker fails closed for DELETE if the replay-marker KV is absent or cannot be read/written. A successful DELETE consumes that token's `jti`; replaying the same token is rejected before R2/B2 mutation. Separate fresh tokens are independent.

The current compatibility policy is:
- **DELETE:** one successful use per token, fail-closed replay protection.
- **READ:** reusable during the token lifetime. This supports repeat fetches, range requests, retries and re-opening an attachment without requiring a new token for each GET/range.
- **WRITE:** reusable during the token lifetime. This supports retry after transport interruption/ambiguous response and upload retry flows.
- **TTL:** 10 minutes, matching the current Android mint-to-use window for media upload/download on slow connections.

The Worker tests cover token format/downgrade rejection, unique JTI behavior, DELETE replay, missing KV, KV read/write failure and the R2/B2 media authorization paths. See `worker/src/index.test.js`, `server/lib/mediaToken.js` and the `/mediaToken` handler in `server/index.js`.

## 2. Residual risk

A party that obtains a valid READ or WRITE bearer token can reuse it until expiration. The JTI alone does not revoke or consume those two operation types. The 10-minute TTL bounds exposure but is not instantaneous revocation. This is a deliberate compatibility compromise, not proof that those tokens are single-use.

The app should continue to avoid placing capability tokens in URLs, log messages, analytics, crash breadcrumbs, clipboard data or durable app state. Any observed token leakage remains a security incident and requires credential/session-specific containment where possible.

## 3. Options

### Option A — Ratify the current compatibility policy

Keep the 10-minute TTL, reusable READ/WRITE, and single-use DELETE. Record the residual bearer exposure in the risk register with an explicit owner and review date. Retain the current client/server/Worker protocol. This avoids breaking legitimate range reads, download retry and upload retry behavior.

This option requires the owner to accept the residual risk explicitly. It must not be marked Fixed solely because DELETE replay protection is tested.

### Option B — Design a versioned one-shot protocol

Do not simply make every current token single-use. First map every Android client flow and define:
- a fresh-token/re-mint contract for each retry and each range request;
- an idempotency key or upload-session identifier so a lost success response does not create an unrecoverable upload;
- behavior for parallel chunk/range requests;
- behavior when the client loses connectivity after the server consumes a capability but before the client observes the response;
- bounded mint rate and replay storage lifecycle;
- backward-compatibility/rollout strategy for existing app versions and a rollback plan.

Implement as a new protocol version across Android, server and Worker, with contract tests and failure-injection tests. Never silently reinterpret a v1 token as one-shot.

### Option C — Shorten the TTL without changing reuse semantics

This reduces the theft window, but can reject legitimate slow uploads or downloads on unreliable networks. It should only be considered after measuring real request durations and defining client re-mint behavior. A shorter TTL by itself does not prevent repeated use within the new window.

## 4. Recommendation

Do not change the existing TTL or make READ/WRITE one-shot in isolation. The immediate safe choice is to retain the current behavior while the owner decides between Option A (documented/approved residual) and Option B (versioned protocol). Option C is not recommended as a standalone change.

This is a technical recommendation, **not** owner risk acceptance. S03-M3 remains **Partial** until the owner records the decision. If Option B is chosen, the follow-up implementation is agentic code work; no production rollout should occur before client/server/Worker compatibility tests and a staged migration plan pass.

## 5. Required owner record

- Decision: `PENDING`
- Approver: `PENDING`
- Date: `PENDING`
- Review/expiry date: `PENDING`
- Residual-risk acceptance or approved protocol plan: `PENDING`

Do not add personal data or any real bearer token to this document.
