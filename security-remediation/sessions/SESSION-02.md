# REMEDIATION SESSION 02 — Round 2 (P1): Advertised Guarantees

**Status:** IN-PROGRESS — Tasks 1–3 executed; Tasks 4–7 and final verification remain
**Round:** 2 of 3
**Executed through:** 2026-08-07
**Branch:** `security-remediation-session`

## 1. Objectives

Close the Round 2 Android and server controls through Task 3: prevent screenshot opt-out, fail closed for account crypto preferences, redact Signal peer identifiers from logs, make duress lock intent durable and prune WorkManager residue, harden admin token/audit handling, and remove dead B2 rate-limit/helper code.

## 2. Outcome by finding

| ID | Sev | Root cause | Disposition | Verified by |
|---|---|---|---|---|
| `S08-H2` | High | Base activity actively cleared `FLAG_SECURE` | `fixed` | Source: `BaseActivity` now calls `addFlags(FLAG_SECURE)` |
| `S08-H5` / `S07-M1` | High | Account-scoped secure preferences silently fell back to plaintext | `fixed` | Source: account store throws on encryption fallback; device gate remains tolerant |
| `S10-N2` | Info | Signal peer address/name and session keys appeared in Logcat | `fixed` | Source: five log sites use `<redacted>` |
| `S06-H2` | High | Duress wipe left WorkManager records and queued work | `fixed` | Source: tagged cancellation and `pruneWork()` after wipe |
| `S06-H3` | High | Offline nonce failure prevented durable account-lock retry | `fixed` | Source: empty-nonce enqueue plus UID-only recovery in worker |
| `S05-H1` | High | Admin token had no startup entropy floor | `fixed` | Source: startup validation/logging added; no forced process exit |
| `S05-H3` | High | Successful admin actions were not durably audited | `fixed` | Source: awaited `_adminAudit` writes for four implemented actions and reader updated |
| `S04-I2` | Info | Dead B2 helper and limiter entries remained | `fixed+runbook` | Source: helper and B2 limiter entries removed; credential revocation remains operational |

Tasks 4–7 are not represented as closed here: SSRF/image proxy, CI release checksums, strip-script hashing, scope typing, and final documentation verification remain open until executed.

## 3. Files in scope through Task 3

- `app/src/main/java/com/duoshield/app/BaseActivity.java`
- `app/src/main/java/com/duoshield/app/util/SecurePrefs.java`
- `app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java`
- `app/src/main/java/com/duoshield/app/security/DuressManager.java`
- `app/src/main/java/com/duoshield/app/security/AccountLockWorker.java`
- `app/src/main/java/com/duoshield/app/ui/MessageAdapter.java` (Task 2 proxy-client change)
- `server/index.js`

## 4. Root-cause analysis

The Android findings were caused by security controls being opt-out or silently degraded: the base activity cleared screenshot protection, secure preferences returned plaintext after encryption failure, and diagnostic logs retained stable peer identifiers. Duress cleanup treated network availability and WorkManager history as secondary, so offline events could lose their durable lock intent and completed jobs could remain forensically visible.

The server findings were caused by missing operational guardrails around a powerful admin surface and stale code paths. `ADMIN_TOKEN` was accepted without a minimum length check, admin mutations were not written to an internal audit collection, and unused B2 signing code/rate-limit entries increased the exposed maintenance surface.

## 5. Defects found in this remediation

Pending final validation. The implementation must still be compiled/tested, especially the Android WorkManager API calls, `SecurePrefs` call sites, and the server startup/audit paths.

## 6. Exit criteria check through Task 3

| Criterion | State |
|---|---|
| Android screenshot control no longer actively clears `FLAG_SECURE` | MET — source change |
| Account crypto preferences fail closed on encryption failure | MET — source change |
| Signal peer identifiers removed from logs | MET — source change |
| Offline duress intent is durably queued | MET — source change; runtime test pending |
| WorkManager duress records are cancelled/pruned | MET — source change; runtime test pending |
| Admin token entropy is checked at startup | MET — source change |
| Admin mutations write `_adminAudit` records | MET — source change; Firestore integration test pending |
| Dead B2 helper/rate-limit entries removed | MET — source change |
| Round 2 complete and ratified | NOT MET — remaining tasks and verification pending |

## 7. Tests needed

- Java/Android compile and unit tests for `SecurePrefs`, `DuressManager`, and `AccountLockWorker`.
- Server syntax/tests and an admin route test asserting `_adminAudit` writes.
- Static checks for no peer identifier concatenation in Signal logs and no B2 helper/rate-limit references.
- Runtime WorkManager test covering empty nonce recovery and post-wipe pruning.

## 8. Findings not touched through Task 3

The remaining Round 2 scope is deferred until implementation continues: `S04-H1`, `S04-H2`, `S04-H3`, `S08-H4`, `SC-05`, `SC-04`, `SC-01`, and `S03-H1`. `S05-I1`, `S07-L4`, and `S10-N3` also require their planned verification/documentation work.
