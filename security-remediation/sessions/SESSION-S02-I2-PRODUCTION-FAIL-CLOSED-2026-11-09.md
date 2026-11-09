# S02-I2 — Production fail-closed authenticated endpoint rate limiting

Date: 2026-11-09
Repository: kumarclaude4-hash/DuoFatass
Branch: security/s02-i2-production-fail-closed-2026-11-09
Finding: S02-I2 — Per-process in-memory limiters are not globally consistent
Status: Partial pending exact-head CI/Security and authorized production configuration read-back

## Source review

Before this change, authRateLimitStore.check() fell back to a bounded process-local Map when
the Durable Object client was absent or when the Worker request failed. That fallback is bounded,
but it does not provide cross-instance or restart-persistent enforcement. A production request
could therefore consume only one Render instance's counter during an infrastructure/configuration
failure.

## Implementation

- Added requireDurable to createAuthRateLimitStore.
- Added a typed AuthRateLimitUnavailableError with code AUTH_RATE_LIMIT_UNAVAILABLE and status 503.
- In strict mode, a missing client or Worker/DO failure raises the typed error and never calls
  localCheck(). The existing local fallback remains available for tests and explicit local
  development.
- The HTTP server enables strict mode unless NODE_ENV is explicitly development or test.
  An unset NODE_ENV is therefore production-safe.
- Updated all 12 authenticated per-user endpoint routes. They return HTTP 503 with
  Cache-Control: no-store and Retry-After: 30 when the shared counter is unavailable; the
  normal HTTP 429 branch remains only for an actual configured quota breach.
- Added unit regressions for missing DO configuration and Worker failure, plus route-wiring
  regressions that require infrastructure-unavailability handling to precede quota rejection.

## Documentation updated in this PR

- BUG_TRACKER.md: S02-I2 implementation evidence and remaining external acceptance.
- server/README.md: production behavior, local development override and deployment expectations.
- security-remediation/START_HERE.md, NEXT_WORK_REPORT_2026-11-08.md,
  FINAL_SIGNOFF.md, INDEPENDENT_REVERIFICATION_2026-11-08.md,
  AGENTIC_WORK_REMAINING_2026-11-06.md and SESSION_INDEX.md.
- This session record is included in the same PR.

## Verification

- Regression tests added; exact-head CI/Security are pending at the time of this record.
- Static route inventory is still 12 protected endpoints and the configured endpoint-key table is
  unchanged.
- No production Render variables, Worker secrets, Firebase settings or deployed services were
  read, rotated, or changed in this PR.
- No physical-device or live production testing is claimed.

## Residual / disposition

Keep S02-I2 Partial until the PR's exact-head CI and Security workflows pass and an authorized
operator verifies that Render ADMIN_LOCKOUT_WORKER_URL / ADMIN_LOCKOUT_WORKER_SECRET match the
Worker ADMIN_LOCKOUT_SECRET, then confirms the server logs that authenticated endpoint rate
limiting is Durable-Object-backed. Admin-IP brute-force lockout retains its separately documented
fallback behavior; this PR changes the authenticated per-user/endpoint limiter only.

security-remediation/FINAL_SIGNOFF.md remains PENDING.