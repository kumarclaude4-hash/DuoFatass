# Round 2 Evidence Log — Tasks 1–3

**Date:** 2026-08-07  
**Remediation Round:** 2  
**Status:** IN-PROGRESS — evidence covers completed Tasks 1–3 only

## Source-verified changes

| Finding | Evidence | Result |
|---|---|---|
| `S08-H2` | `BaseActivity.java` no longer clears `FLAG_SECURE`; it adds the flag in `onCreate`. | Fixed in source |
| `S08-H5` / `S07-M1` | `SecurePrefs.get()` calls `buildTiered(..., true)` and throws on encryption fallback. `getDeviceGate()` explicitly uses `false`. | Fixed in source |
| `S10-N2` | `DuoShieldSignalStore.java` redacts peer address/name and session key identifiers at five log sites. | Fixed in source |
| `S06-H2` | `DuressManager.performLogout()` cancels account-lock/FCM work tags and calls `pruneWork()` after sign-out/wipe. | Fixed in source; runtime test pending |
| `S06-H3` | `DuressManager` enqueues with an empty nonce when acquisition fails; `AccountLockWorker` retries UID-only nonce recovery. | Fixed in source; runtime test pending |
| `S05-H1` | Server startup logs missing/short `ADMIN_TOKEN` and accepts only tokens meeting the 32-character floor for the healthy path. | Fixed in source |
| `S05-H3` | Successful waitlist approval, account unfreeze, duress enrollment, and duress revoke write awaited documents to `_adminAudit`; audit reader uses the same collection. | Fixed in source; Firestore test pending |
| `S04-I2` | Dead `b2PresignUrl` helper and B2 presign/delete limiter entries were removed from `server/index.js`. | Fixed in source; B2 credential revocation remains runbook work |

## Not yet evidenced

Tasks 4–7 remain open: per-hop DNS pinning, bounded/timed link-preview reads, image proxy endpoint completion, release retention/checksums, strip-script hashing, typed scope validation, and final tracker reconciliation.

## Verification commands to run at round close

```sh
node --check server/index.js
# Android Gradle compile/tests in the project’s configured Android environment
# Static grep checks for FLAG_SECURE, Signal log redaction, B2 references, and _adminAudit
```

No secret values are recorded in this evidence file.
