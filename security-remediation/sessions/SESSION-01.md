# REMEDIATION SESSION 01 — Round 1 (P0): stop the bleeding

Maps to `REMEDIATION_PLAN.md` Round 1 — the highest-risk trust-boundary failures and the audit
synthesis P0 set.

**Status:** EXECUTED — 9 of 11 `fixed`, 2 `open`
**Round:** 1 of 3 · **Findings in scope:** 11
**Test plan:** [`../test-plans/ROUND-01.md`](../test-plans/ROUND-01.md) — 20/20 automated checks pass
**Executed:** 2026-08-07 · **Branch:** `security-remediation`

> ### This file was rewritten 2026-08-07 (second time) — it is now an execution record
>
> The previous revision was a forward plan that read `Status: NOT STARTED`. That was already wrong
> when written: commits `ad5176d` and `74f3097` had landed most of the Round 1 code. This session
> verified every claim against source per gate **G-1** (source beats tracker) and found one
> **critical defect in the remediation itself** — see §5.
>
> The revision before that was invalid for different reasons (fabricated finding IDs); that notice is
> retained in [`../RECONCILIATION.md`](../RECONCILIATION.md) §1.

---

## 1. Objectives

Close the P0 set: **credential exposure** and **authentication bypass**. Until both are closed every
other control is unenforceable — the leaked GCP service-account key carries Firestore **Admin**
authority, which sits above the rules layer and therefore voids TB-2 entirely, and `/mintToken`
granted account takeover without the seed phrase.

Round 1 is the gate for Rounds 2 and 3. Neither may begin until Round 1 is closed and verified.

## 2. Outcome by finding

| ID | Sev | Root cause | Disposition | Verified by |
|---|---|---|---|---|
| `S08-C1` | **Critical** | Admin GCP service-account key written into `app/src/main/assets/`, shipped in every APK | `fixed+runbook` — code closed, **rotation outstanding** | Source: step deleted from `release.yml`. Artifact check #29 is MANUAL |
| `SC-02` | **Critical** | Release workflow bakes the full backend credential set into the client build | `fixed+runbook` — code closed, **rotation outstanding** | Source: `local.properties` block now writes only public URLs |
| `S07-C1` | **Critical** | `/mintToken` accepts a public value (identity pubkey) as proof of private-key ownership | **`fixed`** | 20 automated tests. **Was silently broken — see §5** |
| `S08-H1` | High | `WORKER_SECRET` in `BuildConfig`, accepted on Worker `/stats` | `fixed+runbook` — code closed, **rotation outstanding** | Source: `buildConfigField … ""`; Worker fails closed when unset |
| `S07-H1` | High | Existing-account key check fails **open** when the stored hash is falsy | `fixed` | Source: `if (!storedHash) throw … 403` |
| `S06-H1` | High | `accountLock` never enforced server-side; restore gate is client-side and post-auth | `fixed` | Source: `tx.get(lockRef)` inside the mint transaction. Race test #33 is MANUAL |
| `S02-M1` | Medium | Mint cooldown stamped **pre-auth** for a caller-supplied `userId` → targeted re-auth DoS | **`fixed`** | 8 automated tests. **A second instance was found and closed — see §5** |
| `S02-L1` | Low | Dup of `S07-H1` — same fail-open branch | `fixed` | Same fix as `S07-H1` |
| `S03-L1` | Low | Dup of `S08-H1` — `WORKER_SECRET` compiled into the APK | `fixed+runbook` | Same fix as `S08-H1` |
| `SC-12` | Low | CODEOWNERS present, branch protection unverified | **`open`** | `gh api` returned `404 Branch not protected` — see §6 |
| `S02-I3` | Info | No `checkRevoked` → locked sessions live until token expiry | `fixed` (residual `RR-06`) | Source: `verifyIdToken(idToken, true)` at 7 call sites |

**9 fixed · 2 open** (`SC-12`; and the rotation half of `S08-C1`/`SC-02`/`S08-H1`/`S03-L1`).

## 3. Folders / files in scope

- `.github/workflows/release.yml` — credential-emitting steps **(closed in `ad5176d`)**
- `app/build.gradle` — B2 / `worker.secret` plumbing **(closed in `ad5176d`)**
- `server/index.js` — `/mintToken` + `/mintChallenge`, `accountLock`, cooldown **(this session)**
- `server/lib/xed25519.js` — signature verification **(defect fixed this session)**
- `server/test/` — new; 20 tests **(this session)**
- `worker/src/index.js` — `/stats` authentication **(already operator-only, fails closed)**
- `app/src/main/java/com/duoshield/app/auth/AuthTokenHelper.java` — client challenge/sign flow
- Repository settings — branch protection on `main` **(still absent)**

`firestore.rules` was not touched. All rules work is Round 3.

> **Path correction.** The prior revision cited
> `app/src/main/java/com/duoshield/app/util/AuthTokenHelper.java` and
> `app/src/main/java/com/duoshield/app/RestoreFromSeedActivity.java`. Neither path exists. The real
> paths are `…/app/auth/AuthTokenHelper.java` and `…/app/ui/RestoreFromSeedActivity.java`.

## 4. Root-cause analysis

**Credential exposure (`S08-C1`, `SC-02`, `S08-H1`, `S03-L1`).** The release pipeline treated the
client build as a trusted environment: `release.yml` wrote `GOOGLE_APPLICATION_CREDENTIALS_JSON` to
`app/src/main/assets/service-account.json` and injected `B2_*` + `WORKER_SECRET` into
`local.properties`, which `build.gradle` compiled into `BuildConfig`. An APK is a public artifact, so
this was unconditional disclosure of server-side authority. Architectural — a boundary placement
error, not a line bug.

**Authentication bypass (`S07-C1`, `S07-H1`, `S02-L1`).** The server stored and compared
`identityPubKeyHash`. An identity **public** key is published by design — every peer fetches it from
`identities/{uid}` to start a session. Treating its hash as an ownership proof meant anyone who could
read a victim's public identity could mint a token without the seed. `S07-H1`/`S02-L1` compounded it:
when the stored hash was absent or falsy the comparison was skipped and the request **failed open**.

**Lock not enforced (`S06-H1`).** `accountLock` was read only on the duress-lock write and admin
unfreeze paths. There was **no** read in `/mintToken`, and the restore gate was client-side and
post-authentication, so an attacker who ignored the client simply minted a token. The duress lock was
decorative at the only boundary that mattered.

**Cooldown DoS (`S02-M1`).** The cooldown was stamped before authentication using the caller-supplied
`userId`, so an unauthenticated attacker could pin any victim's cooldown and deny them re-auth.

## 5. Defects found in the remediation itself

Both were introduced by the Round 1 fixes and would have shipped undetected.

### `DEF-R1-01` — proof-of-possession rejected every legitimate signature (`S07-C1`)

`server/lib/xed25519.js` prepended a 32-byte `0xFE` domain-separation constant to the message before
calling `crypto.verify`:

```js
const prefix      = Buffer.alloc(32, 0xfe);
const prefixedMsg = Buffer.concat([prefix, message]);
return crypto.verify(null, prefixedMsg, pubKeyObj, signature);
```

That prefix belongs to XEdDSA's `hash_1`, which the **signer** uses only to derive the secret nonce
`r` (spec §2.4). It never enters the challenge hash `h = SHA-512(R ‖ A ‖ M)`, so a verifier must use
the raw message (§2.5). Prefixing made verification fail for **every** correctly-formed signature.

Impact: `/mintToken` would reject all legitimate clients — a **total authentication outage**, not a
security hole. It is dangerous precisely because it is invisible to negative testing: a verifier that
rejects everything passes every "must deny" test. The five deny-case checks the plan called for in §6
all passed against the broken code.

Isolation, before changing anything — three independent measurements on one known-good signature:

| Measurement | Result |
|---|---|
| Pure group arithmetic, `R == sB − hA`, unprefixed | `true` |
| `crypto.verify`, message unprefixed | `true` |
| `crypto.verify`, message prefixed with 32 × `0xFE` | `false` |

This located the fault in the prefix, not in the key conversion or the test signer. The
Montgomery→Edwards conversion was separately confirmed correct: the module's `y = (u−1)/(u+1)`
reproduced the independently-derived Edwards key byte-for-byte
(`78320f4d…f72017`).

Fix: verify the message as signed. Guarded by
[`../../server/test/xed25519.test.js`](../../server/test/xed25519.test.js), which signs with an
XEdDSA implementation built from the spec — independent of the module under test, so it cannot pass
vacuously.

### `DEF-R1-02` — `S02-M1`'s DoS reintroduced through `/mintChallenge`

The `S02-M1` fix moved the cooldown post-auth, but the new challenge store kept **one nonce slot per
userId** and `/mintChallenge` overwrote it:

```js
mintChallenges.set(userId, { nonce, expiresAt });   // replaces any pending nonce
```

`/mintChallenge` cannot be authenticated — the caller has nothing to sign until it receives a nonce.
So an attacker who merely knows a victim's `userId` could call it in a loop and evict the victim's
nonce in the window between the victim's `/mintChallenge` and `/mintToken`, denying that account
re-authentication indefinitely. Same pre-auth denial-of-service as `S02-M1`, through a new door.

Fix: each `userId` holds a bounded **set** of outstanding single-use nonces
(`MAX_CHALLENGES_PER_UID = 16`), so an attacker's requests *add* entries instead of destroying the
victim's. Overflow evicts oldest-first, keeping the newest — the one a real client is signing. An
unknown nonce no longer disturbs valid outstanding nonces. The cap bounds heap growth on an
unauthenticated endpoint.

### `DEF-R1-03` — cooldown rejection burned a single-use invite

The cooldown gate sat **after** the Firestore transaction. A legitimate new user who retried within
60 s had their waitlist invite marked `used` and their identity binding written, then received a
`429` — leaving the invite spent and the account unrecoverable.

Fix: gate before the transaction (still after signature verification, so it stays unreachable
pre-auth) and stamp the cooldown only on the success path, so a request rejected for a locked account
or key mismatch does not start a 60 s lockout.

## 6. `SC-12` — asserted and failed

```
$ gh api repos/kumarclaude4-hash/DuoFatass/branches/main/protection
{"message":"Branch not protected", "status":"404"}
```

`.github/CODEOWNERS` exists and assigns `@kumarclaude4-hash` as reviewer, including dedicated rules
for `crypto/` and `backup/`. **CODEOWNERS without branch protection is advisory** — GitHub requests
review but nothing blocks a merge, and nothing prevents a direct push to `main`.

`SC-12` stays **`open`**. Enabling protection requires admin rights on the repository and is out of
band; per the session directive it is recorded as blocked rather than marked fixed. Runbook in
[`../migration/MIGRATION_PLAN.md`](../migration/MIGRATION_PLAN.md).

## 7. Rotation — outstanding, blocks four findings

Code no longer emits these secrets, but **every value exposed in a published APK is still live**. The
code fix stops future leakage; it does not invalidate what already leaked.

Required order — rotating before the code change would merely re-leak the new secret, and the code
change has now landed, so rotation is unblocked:

1. Revoke the GCP service-account key (`S08-C1`) — **Admin authority; highest priority**
2. Revoke the B2 application key (`SC-02`)
3. Rotate `WORKER_SECRET` on the Worker (`S08-H1`, `S03-L1`)
4. Invalidate tokens minted under the old secret

Needs GCP, Backblaze and Cloudflare console access — none available in this environment. Recorded as
blocked-on-operator. Until step 1 completes, `S08-C1` is **not** closed in practice: an Admin key in a
published artifact bypasses every Firestore rule, so TB-2 stays void and Round 3's rules work remains
unenforceable.

No secret values appear in this workspace. Record the rotation in
[`../evidence/notes/`](../evidence/notes/) as *what* was revoked and *when* — never the values.

## 8. Tests

20 automated checks, all passing — see [`../test-plans/ROUND-01.md`](../test-plans/ROUND-01.md) and
[`../evidence/tests/S07-C1-mint-pop-test-output.txt`](../evidence/tests/S07-C1-mint-pop-test-output.txt).

```bash
cd server && npm test
```

`server/package.json` previously declared `"test": "node --test"`, which crashed under Node 24
(`ERR_UNSUPPORTED_DIR_IMPORT`). Corrected to `node --test test/*.test.js`.

Checks 29–33 (APK inspection, live mint deny-cases, lock race, Worker `/stats` rejection) need a
signed build or staging credentials and remain **MANUAL** with exact commands recorded. They are not
counted as passing.

## 9. Exit criteria

| Criterion | State |
|---|---|
| No secret value in a built release APK, proven by artifact inspection | **NOT MET** — code closed; artifact check MANUAL (#29–30) |
| `S07-C1` mint requires proof of possession; deny-cases verified | **MET** — and the positive path now actually works (`DEF-R1-01`) |
| `S06-H1` `accountLock` enforced inside the mint transaction | **PARTIAL** — source-verified; race test MANUAL (#33) |
| `S02-M1` cooldown unreachable pre-auth | **MET** — plus `DEF-R1-02`/`DEF-R1-03` closed |
| All exposed credentials rotated; old ones confirmed dead | **NOT MET** — blocked on operator (§7) |
| `SC-12` branch protection asserted | **NOT MET** — asserted and failed (§6) |
| Evidence present for all 11 findings | **MET** |
| §10 regression checks pass | **PARTIAL** — 2 source-verified, 3 MANUAL |
| Trackers updated | **MET** |

**Round 1 is NOT closed.** Three criteria are unmet, all requiring out-of-band access. Per gate
**G-0**, this session cannot self-certify closure. Rounds 2 and 3 remain gated — most importantly,
rules work is pointless while a live Admin key sits in a published APK.

## 10. Regression checks

| Check | State |
|---|---|
| Legitimate restore-from-seed on an unlocked account succeeds | **Now possible** — `DEF-R1-01` made this impossible; full confirmation needs #32 |
| Existing accounts holding only the old hash still authenticate | Source-verified — hash comparison retained alongside the signature check |
| Media upload/download works without client `WORKER_SECRET` | MANUAL — capability-token path present in `B2StorageHelper` |
| Push registration works without the service-account asset | MANUAL — needs a build |
| CI produces a valid signed release artifact | MANUAL — needs a CI run |

## 11. Findings explicitly NOT touched this round

All `S01-*` Firestore rules (deliberately deferred — unenforceable while the SA key leaks), all
`S04-*` egress, all `S05-*` admin, `S03-H1/H2/H3/M*/L2/L3/L4/I*`, `S06-H2/H3/M*/L*/I*`,
`S07-H2/H3/M*/L*/I*`, `S08-H2..H5/M*/L*/I*`, `SC-01`, `SC-03`–`SC-11`, `S10-N1/N2/N3`.
