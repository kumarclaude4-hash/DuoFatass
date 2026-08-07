# TEST PLAN — Round 1 (P0)

Covers the ten checks in [`../sessions/SESSION-01.md`](../sessions/SESSION-01.md) §6.

Each row states what is being proven, how, and the **observed** result. A check that cannot be run
in this environment is marked `MANUAL` with the exact command an operator must run — it is **not**
counted as passing.

Run the automated checks with:

```bash
cd server && npm test
```

---

## 1. Automated — executed this session

| # | Check | Finding | File | Result |
|---|---|---|---|---|
| 1 | Genuine XEdDSA signature verifies | `S07-C1` | `server/test/xed25519.test.js` | **PASS** (after fix) |
| 2 | Signatures verify across 5 independent identities | `S07-C1` | same | **PASS** (after fix) |
| 3 | Signature over a *different* nonce rejected | `S07-C1` | same | PASS |
| 4 | Valid signature checked against another identity's key rejected | `S07-C1` | same | PASS |
| 5 | Tampered signature (first and last byte flipped) rejected | `S07-C1` | same | PASS |
| 6 | Malformed input returns `false` rather than throwing | `S07-C1` | same | PASS |
| 7 | All-zero public key does not verify arbitrary signatures | `S07-C1` | same | PASS |
| 8 | Nonce accepted exactly once (replay fails) | `S07-C1` | `server/test/mint-challenge.test.js` | PASS |
| 9 | Nonce not valid for a different `userId` | `S07-C1` | same | PASS |
| 10 | Unknown nonce rejected | `S07-C1` | same | PASS |
| 11 | Expired nonce reported expired **and** consumed | `S07-C1` | same | PASS |
| 12 | Nonce 1 ms before expiry still valid | `S07-C1` | same | PASS |
| 13 | Attacker flood cannot evict victim's in-flight nonce | `S02-M1` | same | PASS |
| 14 | 50-nonce guessing flood leaves valid nonce intact | `S02-M1` | same | PASS |
| 15 | Overflow evicts oldest, preserves newest | `S02-M1` | same | PASS |
| 16 | Per-user challenge count bounded under 5 000-request flood | `S02-M1` | same | PASS |
| 17 | Expired entries reclaimed on next issue | `S02-M1` | same | PASS |
| 18 | Empty per-user map removed after last consume | `S02-M1` | same | PASS |
| 19 | Consume for unknown user is a clean miss | `S02-M1` | same | PASS |
| 20 | Nonces are unique 32-byte hex over 200 issues | `S07-C1` | same | PASS |

Output: [`../evidence/tests/S07-C1-mint-pop-test-output.txt`](../evidence/tests/S07-C1-mint-pop-test-output.txt) — 20/20 passing.

### Why check 1 matters most

Checks 3–7 are **negative** tests; they passed even when verification was broken, because a verifier
that rejects everything rejects bad signatures too. Only checks 1–2 distinguish "correctly verifying"
from "always failing". They initially **FAILED**, exposing `DEF-R1-01` (see
[`../sessions/SESSION-01.md`](../sessions/SESSION-01.md) §5). The test signs with an independent
XEdDSA implementation derived from the spec, not with the module under test, so it cannot pass
vacuously.

## 2. Source-level assertions — verified by reading source

| # | Check | Finding | Evidence |
|---|---|---|---|
| 21 | `release.yml` has no `service-account.json` step | `S08-C1` | `.github/workflows/release.yml` — step removed |
| 22 | `release.yml` writes only `push.server.url` + `worker.url` to `local.properties` | `SC-02` | ibid. |
| 23 | `build.gradle` emits `B2_KEY_ID`/`B2_APPLICATION_KEY`/`WORKER_SECRET` as `""` | `SC-02`, `S08-H1`, `S03-L1` | `app/build.gradle` |
| 24 | Worker `/stats` requires bearer secret and fails closed when unset | `S08-H1` | `worker/src/index.js:76-99, 362-368` |
| 25 | Mint denies when stored hash is absent/falsy | `S07-H1`, `S02-L1` | `server/index.js` existing-account branch |
| 26 | `accountLock` read **inside** the mint transaction | `S06-H1` | `server/index.js` — `tx.get(lockRef)` |
| 27 | Cooldown gate unreachable before signature verification | `S02-M1` | `server/index.js` — gate placed after verify |
| 28 | `verifyIdToken(idToken, true)` on all authenticated routes | `S02-I3` | 7 call sites |

## 3. MANUAL — requires a build or operator access

These are **not** satisfied. Each states the exact command.

| # | Check | Finding | Command | Blocker |
|---|---|---|---|---|
| 29 | `service-account.json` absent from built APK | `S08-C1` | `unzip -l app-arm64-v8a-release.apk \| grep -i service-account` → expect no match | Needs Android SDK + signing keystore; not available here |
| 30 | No B2 key or `WORKER_SECRET` value in APK | `SC-02`, `S08-H1`, `S03-L1` | `strings app-*-release.apk \| grep -Ei '<b2-key-id>\|<worker-secret>'` → expect no match | ibid. |
| 31 | Worker `/stats` rejects a client-held secret | `S08-H1` | `curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer <old-apk-secret>' https://<worker>/stats` → expect `401` | Requires rotation to have happened first |
| 32 | Live mint deny-cases end-to-end | `S07-C1`, `S06-H1` | Exercise `/mintChallenge` + `/mintToken` against a staging server | Needs Firestore + Admin SDK credentials |
| 33 | Lock-write racing a mint | `S06-H1` | Concurrent `accountLock` write + `/mintToken` against staging | ibid. Transaction correctness is argued from source (#26), not proven under contention |
| 34 | Branch protection on `main` | `SC-12` | `gh api repos/kumarclaude4-hash/DuoFatass/branches/main/protection` | **RUN — returned `404 Branch not protected`.** See [`../evidence/logs/SC-12-branch-protection-probe.txt`](../evidence/logs/SC-12-branch-protection-probe.txt) |

Check 34 was executed and **failed**: `main` is unprotected. `SC-12` is therefore `open`, not `fixed`.

## 4. Regression checks (§9 of the session plan)

| # | Check | Status |
|---|---|---|
| 35 | Legitimate restore-from-seed on an unlocked account succeeds | **Now possible** — was impossible before `DEF-R1-01` was fixed. Full confirmation needs #32. |
| 36 | Existing accounts holding only the old hash still authenticate | Source-verified: hash comparison retained alongside signature check |
| 37 | Media upload/download works without client `WORKER_SECRET` | MANUAL — needs a build; capability-token path present in `B2StorageHelper` |
| 38 | Push registration works without the service-account asset | MANUAL — needs a build |
| 39 | CI produces a valid signed release artifact | MANUAL — needs a CI run |
