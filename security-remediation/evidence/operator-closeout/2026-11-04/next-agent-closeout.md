# Next-Agent Closeout Evidence — 2026-11-04

This record is sanitized operational evidence for the first next-agent work gates. It contains no
secret values, bearer tokens, private keys, mnemonics, user content, or raw production identifiers.

## Scope and branch discipline

- Work branch: `security/next-agent-docs-reconcile`, created from `origin/main` at
  `58b29f16b0c368cb9dbd71512e98317022db4781`.
- PR #212 was left untouched. Live read-back: PR #212 is open, base `main`, head
  `security/operator-closeout-verification` at
  `65d3c5d39895f714551a4adbf61f7c35e397f914`.
- PR #212 checks were read only; all listed GitHub checks were successful, including CI run
  41860269637 and Security scan run 41860269478.

## Current main CI / S3-19b evidence

Current `main` commit: `58b29f16b0c368cb9dbd71512e98317022db4781`.

- CI run: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41863879619
  - Status/conclusion: completed / success.
  - Jobs observed successful: Server + Worker tests; Verify vendored libsignal JAR (SC-01);
    Validate Gradle wrapper JAR (SC-07); Unit tests; Lint; Instrumented tests (Espresso);
    Build debug APK.
- Security scan run: https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41863879529
  - Status/conclusion: completed / success.
  - Jobs observed successful: Secret scan (gitleaks); Generate SBOM; CodeQL (SAST) (javascript).

Disposition impact: this verifies the S3-19b Android CI compile/unit-test gate on current `main`.
It does not prove device-specific behavior, live Firebase revoked-token behavior, production
deployment state, or any finding-specific residual that requires live operator evidence.

## Tracker recount

Command class used: parse `BUG_TRACKER.md` section tables from `## Critical` through
`## Informational`, counting finding rows with at least four table cells and reading each row's
Status cell.

Current count:

| Severity | Total | Fixed | Partial | Open | Accepted |
|---|---:|---:|---:|---:|---:|
| Critical | 4 | 4 | 0 | 0 | 0 |
| High | 31 | 24 | 7 | 0 | 0 |
| Medium | 27 | 19 | 7 | 0 | 1 |
| Low | 31 | 18 | 11 | 1 | 1 |
| Informational | 23 | 9 | 4 | 0 | 10 |
| **Total** | **116** | **74** | **29** | **1** | **12** |

## SC-12 governance read-back

Live GitHub CLI read-back on 2026-11-04:

- `GET /repos/kumarclaude4-hash/DuoFatass/branches/main/protection` returned
  `404 Branch not protected`.
- `GET /repos/kumarclaude4-hash/DuoFatass/rulesets` returned `[]`.
- `GET /repos/kumarclaude4-hash/DuoFatass/environments` returned Preview and Production
  environments with empty `protection_rules` and no deployment branch policy.

The available GitHub credential is read-only for repository administration. SC-12 remains **Open /
blocked on an authorized repository admin**.

Required owner settings:

1. Protect `main`: require a pull request, require at least one independent approval, require
   conversation resolution, require the exact current `CI` and `Security scan` checks, prevent
   force-pushes, and prevent deletion.
2. Add an immutable `v*` tag ruleset: disallow tag updates and deletion; restrict tag creation to
   the intended release role.
3. Configure a protected production-release environment with required reviewer approval and only
   the intended branch/tag deployment policy.
4. Read back the effective branch protection, ruleset, and environment settings after saving and
   attach sanitized evidence to the tracker before marking SC-12 fixed.

## Operator credential/configuration access

The workspace shell did not expose Render, Cloudflare, Backblaze/B2, or GCP operator credentials.
Project runtime variable names may exist in the linked Vercel project, but production runtime
credentials are not operator credentials and were not read, printed, copied, or used for
control-plane changes.

Blocked pending separate operator credentials/access:

- S02-I2: verify Render `ADMIN_LOCKOUT_WORKER_URL` and `ADMIN_LOCKOUT_WORKER_SECRET` against the
  Worker `ADMIN_LOCKOUT_SECRET`; verify the Durable Object binding/migrations; prove a request
  updates the expected counter and expiry cleanup works; prove missing/mismatched configuration
  produces a monitored degraded-mode signal.
- Credential rotation: identify consumers, create least-privilege replacements, update runtime
  secret stores, deploy/restart, validate, review audit logs, then revoke old credentials. Record
  only key IDs, timestamps, scopes, consumers, and boolean validation/revocation results.
- S10-N1: verify Play Integrity registration, package identity, release signing certificates,
  server/Firestore enforcement points, staging/monitoring behavior, and rollback before
  enforcement.
- Firestore TTL/rules: verify `_duressNonces.expiresAt` TTL and the deployed `firestore.rules`
  revision in the intended Firebase project; keep emulator evidence separate from live deployment
  evidence.
- S10-N3: run the R2-to-B2 migration race test only against isolated synthetic buckets/objects;
  do not use real user media or production buckets.

## Reproducible build / supply-chain checks

Commands were run in temporary clean directories so existing `node_modules` directories were not
relied upon.

- Server: `npm ci --no-audit --no-fund` passed from `server/package.json` +
  `server/package-lock.json`.
- Server native module: after clean install, `npx --yes node@20 -e
  'require("@signalapp/libsignal-client")'` succeeded under Node `v20.20.2`.
- Worker: `npm ci --no-audit --no-fund` passed from `worker/package.json` +
  `worker/package-lock.json`.
- Lockfiles: server lockfileVersion 3 with 184 packages; worker lockfileVersion 3 with 106
  packages.
- Gradle verification metadata present with 491 component entries.
- `python3 scripts/strip_signal_records.py --check` passed; the vendored libsignal JAR matched its
  recorded SHA-256 (`fa7d3afe...d89e3`, 38,466,351 bytes).

Render build-command implication: `npm ci` is locally reproducible and the native libsignal module
loads under Node 20. Before changing the live Render build command, run the same clean build on a
Render staging service using the intended Node 20 Linux runtime, smoke-test it, and document
rollback. Do not change the auto-deploying production service as a side effect of this docs PR.

## Documentation changes made in this branch

- Reconciled S3-19b current-state wording in `START_HERE.md`, `ROUND3_REMEDIATION_PLAN.md`,
  `SESSION_INDEX.md`, `BUG_TRACKER.md`, and `FINAL_SIGNOFF.md`.
- Corrected the tracker summary to the direct 116-row recount and corrected the Low section count.
- Refreshed SC-12 with the live 2026-11-04 read-back and kept it Open/blocked.
- Kept `FINAL_SIGNOFF.md` **PENDING**.

## Remaining owner blockers

SC-12, S02-I2 live configuration, credential rotation, App Check enforcement, Firestore TTL/rules
deployment, isolated R2/B2 integration testing, trust-boundary review, and coordinated production
server/Worker/Firestore/Android release remain unclosed. No production deployment or live credential
change was performed.
