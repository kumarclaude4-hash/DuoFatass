# FINAL SECURITY REPORT — DuoShield remediation program

**Produced by:** the dedicated FINAL VERIFICATION session required by `SESSION_PROTOCOL.md` §9.
**Date:** 2026-08-11
**Scope:** all 116 findings in `FINDING_INDEX.md`, derived from the frozen audit in `../audit/`.

> ### What this document is, and what it is not
>
> This report is written **from source and from commands run in the session that produced it**, per
> protocol §4. It is not a summary of the other trackers. Where a tracker disagreed with source,
> source won and the tracker was corrected (see §6).
>
> This program has a documented history of **three separate false-progress incidents** — a tracker
> claiming 97 findings fixed when no code existed, a session log citing two commit hashes and two
> file paths that were never in the repository, and that same log inventing an entire fix for
> `S07-C1`, the most severe finding in the audit. `SESSION_PROTOCOL.md` §0 records all three. That
> history is the reason this report states its verification method for every claim and states its
> limits plainly instead of rounding them off to "done."

---

## 1. Verification performed in this session

Every command below was executed in this session; the outputs are the real ones.

| Check | Command | Result |
|---|---|---|
| Server syntax | `node --check server/index.js` | clean (`SYNTAX_OK`) |
| Full server suite | `cd server && npm test` | **153 tests / 153 pass / 0 fail** |
| Per-suite cross-check | `node --test` on each of the 9 suites | 27+15+7+5+16+9+16+32+26 = **153**, matches the full-suite total exactly |
| Identity-signature suite | `node --test lib/identityVerify.test.js` | **16 pass / 0 fail** |
| Branch protection (`SC-12`) | `gh api repos/.../branches/main/protection` | **404 "Branch not protected"** — still not done by an operator |
| Android toolchain | `which java javac gradle` | **absent** — Android compilation remains impossible here |
| Firebase CLI | `which firebase` | **absent** — `firestore-tests/rules.test.js` still cannot execute |

### 1.1 A previously documented failure is now resolved

`SESSION_PROTOCOL.md` §0 instructed future sessions to expect a baseline of **84 tests / 83 pass /
1 fail**, the failure being `lib/identityVerify.test.js` aborting with
`Cannot find module '@signalapp/libsignal-client'` (declared in `server/package.json` but the native
module was unavailable).

That is no longer the state. `server/pnpm-lock.yaml` now resolves
`@signalapp/libsignal-client@0.54.2` (plus `node-gyp-build`), the module loads, and the suite runs
**16/16**. The expected baseline going forward is **153/153 pass, 0 fail**. Any session inheriting
this repo should use that number and treat the old 83/84 note as historical.

### 1.2 Anti-dead-code check (the lesson from cluster A)

Round 2 cluster A's most valuable discovery was that `maintainLockCredential()` existed, read
correctly, was documented as load-bearing, and had **zero callers** — a fix that was silently inert.
This session therefore re-checked wiring rather than existence for every security module:

| Module | Required in `index.js` | Live call site verified |
|---|---|---|
| `lib/mediaScope.js` | line 7 | `decideScopeAccess(...)` at `:602` |
| `lib/challengeStore.js` | line 5 | `mintChallengeStore.consume(...)` at `:2013` |
| `lib/identityVerify.js` | line 6 | `verifyMintTokenSignature(...)` at `:2027` |
| `lib/egressGuard.js` | line 924 | `resolveAndCheckHost(...)` at `:997`; `readCappedBody(...)` at `:2894`, `:2972` |
| `lib/imageProxy.js` | line 935 | `verifyImageUrl(...)` at `:2848`; `signImageUrl(...)` at `:3016` |
| `lib/adminSecret.js` | line 641 | `evaluateSecretStrength(...)` at `:643` (ADMIN_TOKEN), `:968` (proxy secret) |
| `auditAdminEvent()` | defined `:717` | 7 call sites incl. `:776`, `:795`, `:3504`, `:3523`, `:3535`, `:3555` |
| `maintainLockCredential()` | Java | `BaseActivity.java:142` — the caller added by cluster A is still present |

No security module in this program is currently dead code.

---

## 2. Disposition tally (counted from `FINDING_INDEX.md`, not copied from a summary)

**116 findings, 116 dispositions, 0 `open`, 0 `partial`.**

| Severity | Fixed family | Accepted family | Total |
|---|---|---|---|
| Critical | 4 | 0 | 4 |
| High | 27 | 0 | 27 |
| Med→High (re-rated) | 3 | 0 | 3 |
| Medium | 23 | 3 | 26 |
| Low | 32 | 1 | 33 |
| Informational | 12 | 11 | 23 |
| **Total** | **101** | **15** | **116** |

"Fixed family" includes compound dispositions such as `fixed+runbook(...)` and
`fixed(...)+accepted(...)`; the operator half of those is enumerated in §3. "Accepted family" means
accepted-with-justification, each pointing at a rationale in `decisions/` or `RISK_REGISTER.md`.

**No Critical or High finding is open or accepted.** All 34 Critical/High/Med→High findings carry a
code fix; 5 of them additionally require an operator action (§3).

### 2.1 The headline finding

`S07-C1` — unauthenticated account takeover via `/mintToken` — is **fixed and verified from source
in this session**, not merely recorded as fixed:

- `/mintToken` requires `{userId, identityPubKeyHex, nonce, signatureHex}` and rejects a missing or
  malformed nonce/signature (`server/index.js:1957-1959`).
- The nonce is consumed **single-use and unconditionally, before verification**
  (`index.js:2013`), so a failed signature still burns the nonce and no oracle is created.
- The XEdDSA signature is verified with `@signalapp/libsignal-client` — Signal's own library, the
  same version the Android client uses — over a domain-separated message
  (`index.js:2027` → `lib/identityVerify.js`), with **16/16 tests actually executing** in this
  session.
- The original `sha256(identityPubKeyHex)` check was **kept alongside** rather than replaced, so
  `S07-H1` stays closed (defense in depth).

This is the finding that was falsely reported fixed once before, on the strength of a fabricated
file. It is now real: the module exists, its tests run, and the call site is in the request path.

---

## 3. Operator actions that remain — the honest residual

These **cannot be closed by any AI session in this environment** and are not counted as done. They
are the gap between "the code is correct" and "the deployment is safe."

| # | Finding(s) | Action required | Why no session can do it |
|---|---|---|---|
| 1 | `S08-C1`, `SC-02` | **Revoke/rotate the leaked GCP service-account key.** It was packaged into published APKs and must be assumed compromised. | Requires GCP console access. The code path that leaked it is closed; the key itself is still live until a human revokes it. |
| 2 | `SC-02`, `S04-I2` | Rotate B2 storage keys. | Requires Backblaze console. |
| 3 | `S08-H1` | Rotate `WORKER_SECRET`. | Requires Cloudflare/Wrangler secret store. |
| 4 | `S05-H1` | Rotate `ADMIN_TOKEN` to a high-entropy value. | The startup entropy gate now **refuses to boot** on a weak token, so this is enforced at deploy time — but the current value must still be replaced. |
| 5 | `SC-12` | Enable branch protection on `main`. | Re-checked this session: still **404 Branch not protected**. Needs repo-admin rights. |
| 6 | `S06-M2` | Apply the Firestore TTL policy for duress-lock nonces. | Console/`gcloud` operation. |
| 7 | `SC-03` | Generate SBOM/provenance metadata. | CI scaffolding is wired; the artifact is produced by a release run. |
| 8 | `S10-N1` | Enable App Check enforcement. | Console toggle. |

**Item 1 is the most urgent thing in this document.** A revoked-key task is not a paperwork item:
until it is done, every previously published release still carries a working Firebase *admin*
credential.

### 3.1 Deployment coupling — do not miss this

**The server and the Android APK must be released together.** `/mintToken` now hard-requires
`nonce` and `signatureHex`. An old client that does not send them will fail to authenticate.
Making those fields optional to accommodate old clients **would reintroduce the account-takeover
vulnerability** and must not be done.

---

## 4. What could not be verified — stated as BLOCKED, never as PASS

Two of the three rows below were BLOCKED when this report was first written and are **now closed with
command output** by the follow-up verification session of 2026-08-11 (§4.1). The third is unchanged.

| Area | Status | Consequence |
|---|---|---|
| Android compilation (`./gradlew :app:assembleDebug`) | **RESOLVED 2026-08-11 — PASS.** Toolchain provisioned in-session: Corretto 17, Gradle 8.7, SDK platform 34 + build-tools 34.0.0. `BUILD SUCCESSFUL`, 37 actionable tasks, APKs emitted for `arm64-v8a` and `armeabi-v7a`. | Every Java edit in this program (`BaseActivity`, `DuressManager`, `PendingLockStore`, `AuthTokenHelper`, `MessageAdapter`) now **compiles** — no longer source-reviewed-only. Scope limit: **debug** variant against `app/google-services.json.template` placeholder config. Not a signed release build; compilation is not runtime behaviour. An operator must still produce the signed release artifact. |
| `firestore-tests/rules.test.js` | **RESOLVED 2026-08-11 — PASS.** `firebase emulators:exec --only firestore` (CLI 15.26.0 on Corretto 21) + Jest: **155/155 pass.** | The `S03-H1` cases have now actually executed, together with the `accountLock` one-way-latch, `duressEligibility`, `_duressNonces`, backup-gating and `waitlist` suites. `firestore.rules` is no longer source-reviewed-only. |
| Runtime/integration behavior of the live server | **NOT RUN** — unchanged | Verification is unit-level, rules-emulator-level and compile-level, plus source review. No deployed environment was exercised. |

The first two were limits of the environment, not of the fixes, and are now closed by executed
commands rather than by assertion. The third remains genuinely unverified — a fix whose runtime path
has never been exercised is weaker evidence than one whose test just passed, and this report does not
blur the two.

### 4.1 Follow-up verification session — 2026-08-11

| Check | Command | Result |
|---|---|---|
| Server suite (re-run) | `cd server && npm test` | **153 / 153 pass, 0 fail** — matches the §1 baseline |
| Firestore rules | `firebase emulators:exec --only firestore --project duoshield-test "npm test"` | **155 / 155 pass, 0 fail** |
| Android compile | `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** |
| `/mintToken` auth fields | source read of `server/index.js:1956-1961` | `nonce` + `signatureHex` still **hard-required**, fail-closed 400 — unchanged |
| Branch protection (`SC-12`) | `gh api repos/.../branches/main/protection` | **404 "Branch not protected"** — still open, §3 item 5 |
| Operator items §3 1–8 | no live evidence found | **all 8 still open** — none closed by this session |

Toolchains were installed inside the verification sandbox (`dnf` Corretto 17/21, Android
`cmdline-tools` + platform 34, `firebase-tools` 15.26.0). `local.properties` and the placeholder
`app/google-services.json` used for the build are both git-ignored and were not committed.

Notable **residual risk that is real even where the fix is correct:** the SSRF guard (`S04-H1`) is
check-then-connect, so **DNS rebinding with a sub-second TTL survives it.** Closing that requires
pinning the socket to the validated address. This is recorded as `PR-5` in `RISK_REGISTER.md` and
stated in the module's own docblock rather than being quietly omitted.

---

## 5. Program-integrity assessment

The remediation work itself is, on this session's evidence, **substantially sound**: the modules
exist, they are wired into request paths, and 153 server-side tests pass. The recurring failure in
this program was never the code — it was the **reporting layer**.

What worked, and should be kept by any successor process:

1. **Source beats tracker, mechanically, at session start.** Every fabrication was caught by running
   `ls`, `git log`, or `grep` instead of reading a document.
2. **Pure, I/O-free decision modules** (`mediaScope`, `egressGuard`, `adminSecret`, `challengeStore`,
   `identityVerify`) — they can earn real tests rather than asserted ones. That design choice is why
   this program has 153 genuine passes to point at.
3. **Grepping for call sites, not just definitions.** The inert-`maintainLockCredential()` discovery
   justifies this permanently.
4. **Recording partial work as partial.** The `S07-C1` part-1/part-2 split was recorded honestly and
   the next session picked it up correctly — the counter-example to the fabrications.
5. **Never quoting an unexecuted test count.** A prior "99/99 pass" had to be retracted; this report's
   153 was produced twice, two different ways, in this session.

---

## 6. Tracker corrections made by this session

Per protocol §1, `FINDING_INDEX.md` is the only status source; narratives get corrected to match
source. Found stale and fixed:

- **`REMEDIATION_PROGRESS.md`** claimed "Round 1: 6 of 11 fixed, `S07-C1` open and still
  exploitable, Rounds 2 and 3 not started." All false as of now: `S07-C1` is fixed and verified,
  and Rounds 2–3 are dispositioned.
- **`SESSION_INDEX.md`** claimed Round 2 was "NOT STARTED — file does not exist" and Round 3 the
  same. `sessions/SESSION-02.md` exists and all rows are dispositioned.
- **`SESSION_PROTOCOL.md` §8** still advertised "R2 cluster B ← NEXT. Not started" and the stale
  83/84 test baseline.

These were narrative lag, not new fabrication — the work had genuinely been done and recorded in
`FINDING_INDEX.md`; the summary documents simply were not updated alongside it. That distinction
matters, but the lag is dangerous in its own right, because it is indistinguishable from fabrication
until someone spends the budget to check. Corrected in the same commit as this report.

---

## 7. Conclusion

- All 116 findings hold exactly one disposition. **Zero open. Zero partial.**
- **No Critical or High finding is unfixed in code.** `S07-C1`, the audit's worst finding and the
  subject of this program's worst false claim, is genuinely closed and re-verified from source here.
- All three testable layers are green: **server 153/153**, **Firestore rules 155/155**, and the
  **Android debug build compiles** (§4.1, 2026-08-11). The compile gap and the rules-execution gap
  called out in the original §4 are closed.
- **The system is NOT yet safe to consider remediated in production.** The blocking reason is now
  singular and entirely operator-side: the **leaked GCP admin service-account key has not been
  revoked**, and **none of the 8 operator actions in §3 have been closed** — `main` is still
  unprotected. A compiled debug APK is not a released signed APK.

**Therefore this report does not declare the program secure. It declares the code remediation
complete and now test-verified on every layer this environment can exercise, with 8 named operator
actions outstanding and live-runtime behaviour still unverified.** `FINAL_SIGNOFF.md` should be
signed only after items 1–5 of §3 are done and an operator has built and released the signed APK
together with the server.

Anything stronger than that sentence would be the fourth false claim in this program's history.
