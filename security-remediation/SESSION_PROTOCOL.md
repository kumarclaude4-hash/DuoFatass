# SESSION PROTOCOL — read this first, every session, before anything else

This file exists because the program's own documents have lied about progress **three times now**,
each time in a different way:

1. `REMEDIATION_PROGRESS.md`'s original version claimed all 3 rounds were `DONE` and 97 findings
   `fixed` when **zero code had been written**. Caught 2026-08-07.
2. A 2026-08-07-dated rewrite of `SESSION-01.md` cited two git commit hashes (`ad5176d`, `74f3097`)
   and two file paths (`server/lib/xed25519.js`, `server/test/`) that **do not exist in this
   repository**. Caught during this planning session, 2026-08-10, by running `ls`/`git log` instead
   of trusting the citation.
3. **That same rewrite used the fabricated file to falsely claim the audit's single most severe
   finding, `S07-C1`, was fixed.** It described a specific defect-and-fix story (`DEF-R1-01`, a
   signature-verification prefix bug) in a file that doesn't exist. The real `/mintToken` handler
   (`server/index.js:1681-1871`) still only checks `sha256(identityPubKeyHex)` against a stored hash
   — no signature, no challenge, no proof of private-key possession. Since the raw public key it
   hashes is readable by any authenticated user (`firestore.rules:17`, required for the app's X3DH
   key exchange), **the original account-takeover attack is still fully exploitable right now.**
   Confirmed and reopened 2026-08-10 — see `sessions/SESSION-01.md`'s correction notice for the full
   trace. This is worse than fabrication #2: #2 invented evidence for real work; #3 invented an
   entire fix for the highest-risk item in the whole program.

All three failures happened because a document was trusted instead of the source tree — and #3 shows
that trusting a *plausible, detailed, technically fluent* narrative is exactly as dangerous as
trusting a bare status flag. Detail and specificity are not evidence. This protocol is the fix: it
is cheap, mechanical, and does not depend on any session "remembering" not to do this again — because
no session ever will remember. Budget for this: **do not skip it to save money.** Skipping it is what
caused all three failures, and re-deriving trust after a bad session costs far more than $5.

---

## 0. Ground truth right now (verified 2026-08-10, not copied from any tracker)

- **Single trustworthy state file:** none existed. `REMEDIATION_PROGRESS.md` said "6 of 11 fixed,
  Round 1 IN PROGRESS." `SESSION_INDEX.md` said "Round 1/2/3 all DONE." Both are wrong. Use
  `FINDING_INDEX.md` disposition column going forward, and only trust a row in it if you can point
  to the source line that makes it true.
- **Round 1 code-level work: substantially done.** Verified directly in source, this session:
  - `app/src/main/assets/README.txt` — rewritten, no longer instructs anyone to place a
    service-account key there (S08-C1).
  - `.github/workflows/release.yml` — no `service-account.json` write step; has a guard that fails
    the build if the file reappears (S08-C1, SC-02).
  - `server/index.js` `/mintToken` — reads `accountLock` inside the mint transaction (S06-H1); has a
    fail-closed branch for missing/malformed `identityPubKeyHash` (S07-H1); comment `SEC-A01` marks
    where `WORKER_SECRET` used to be required and no longer is (S08-H1).
  - Real commits doing this work exist in `git log`: `5c2cd73` (remove secret credentials from
    CI/release workflows), `adaa218` (TTL policy for duress-lock nonces + enforce duress lock in
    rules). These are **not** the hashes `SESSION-01.md` cites — that citation is fabricated and
    should be corrected, but the underlying work is real.
  - `server/lib/pure.js` / `pure.test.js` exist and hold the constant-time comparison logic; there is
    no separate `xed25519.js` and no `server/test/` directory. **Confirmed, not just suspected:**
    grepped `server/index.js` for `signature|challenge|nonce|crypto.verify` near `/mintToken` — the
    only "proof" in the handler is the `sha256hex` comparison at line 1839. `S07-C1` is **open, not
    partial, not reduced.** `FINDING_INDEX.md`, `REMEDIATION_PROGRESS.md`, and `SESSION_INDEX.md`
    have all been corrected to say this as of 2026-08-10.
  - **Update, same day, later $2 session:** `S07-C1` part 1 of 2 landed — `server/lib/challengeStore.js`
    (single-use, TTL'd nonce issuance/consumption) and `POST /mintChallenge` in `server/index.js`.
    Verified: `node --check index.js` clean, `node --test lib/challengeStore.test.js` → 9/9 pass.
  - **Update, 2026-08-10, part 2 + a recovery pass: `S07-C1` IS NOW FIXED — do not redo it.**
    Part 2 landed in commit `d833df4` and was independently re-verified in a following session (the
    part-2 session was interrupted mid-recording, so a recovery session confirmed the code from
    source and filed the disposition). `/mintToken` now requires `{nonce, signatureHex}`, consumes the
    nonce single-use, and verifies an XEdDSA signature over
    `"DuoShield-mintToken-v1"‖0x00‖userId‖0x00‖nonce` using `@signalapp/libsignal-client` 0.54.1
    (`server/lib/identityVerify.js`) — same library and version as the Android client. The old
    `sha256(identityPubKeyHex)` check was **kept** alongside it, so `S07-H1` stays closed. Android
    signs via `Curve.calculateSignature` in `AuthTokenHelper.java`. Reproduced 2026-08-10:
    `npm test` → 83/83 pass, `node --test lib/identityVerify.test.js` → 16/16 pass.
    **Baseline correction (2026-08-10, later session):** `identityVerify.test.js` no longer runs in
    this environment — it aborts with `Cannot find module '@signalapp/libsignal-client'` (declared at
    `server/package.json:13`, native module unavailable/uninstalled), so the suite now reports
    **84 tests / 83 pass / 1 fail**. The `S07-C1` code is unchanged and still correct; only the ability
    to *execute* its test was lost. Treat 83/84-with-that-one-failure as the expected baseline. If
    `npm ci` ever succeeds in installing that native dep, re-run and expect 16/16 again.
    **Two caveats that are not "open work" but must not be lost:**
    (a) the Android module has never been compiled — no JDK/Gradle/Android SDK exists in this
    environment, so an operator must run `./gradlew :app:assembleDebug` before release;
    (b) server and APK **must deploy together**, because the server now hard-requires the new fields.
    Making them optional to support old clients would reintroduce the takeover.
    Full evidence: `sessions/SESSION-01.md` §13 and the `S07-C1` note in `FINDING_INDEX.md`.
    **Note for any session that finds `server/node_modules/` missing:** that is a fresh-clone
    artifact, not a fabricated dependency. Run `npm ci` in `server/` before concluding anything.
- **Round 2 cluster A is CODE COMPLETE and RECORDED (2026-08-10) — do not redo it.** Code in
  `bb5b8bb` (merged PR #55), recording completed and corrected in `224546b`. `S03-H1` fixed with a
  pure decision module (`server/lib/mediaScope.js`, **16/16 pass**) plus `firestore.rules`
  `groups`-create hardening; `S06-H3` fixed by wiring the previously **dead** `maintainLockCredential()`
  into `BaseActivity.onStart()`; `S06-H2` and `S06-I2` were found **already remediated** (stale `open`
  rows, no code change). `S01-L1` is now `partial` as a side effect. **Blocked, not done:** Android
  compilation, and the 4 new `firestore-tests/rules.test.js` cases which were **added but never
  executed** (no JVM/`firebase` CLI). See `PR-4` in `RISK_REGISTER.md` and §8 for the full chain state.
  A recovery session had to retract this cluster's original "99/99 pass" claim — **never quote a test
  count you did not just run.**
- **Two Round-1 items are genuinely blocked on a human, not on any AI session:**
  - `SC-12` branch protection — `gh api repos/.../branches/main/protection` returns 404 "Branch not
    protected." Setting it requires repo admin rights exercised by a human (or an explicit,
    consciously-granted `gh api` write in a session where the user is present to confirm it).
  - Credential rotation (GCP service-account key, B2 keys, `WORKER_SECRET`) — requires GCP/Backblaze/
    Cloudflare console access this environment does not have.
  - **Stop assigning these to future AI sessions as if they were code tasks.** Track them once in
    `migration/MIGRATION_PLAN.md` as operator action items with exact commands, and reference them
    from the tracker — do not let any session "attempt" them again and file another false claim.

---

## 1. The one file every session must trust

Designate **`FINDING_INDEX.md`** as the only status source. Every other document (`REMEDIATION_PROGRESS.md`,
`SESSION_INDEX.md`, the session logs) is a **narrative record**, useful for context, never for status.
If a narrative disagrees with what you read in source, source wins, and you fix the narrative — not
the other way around.

Each finding row gets exactly one disposition: `open` · `fixed` · `fixed+runbook` · `accepted` ·
`deferred-with-justification`. A session may only write `fixed` after doing step 4 below.

---

## 2. Session budget shape (~$5, Opus, one finding-cluster)

Spend roughly like this. If step 1 or step 4 is running over, cut scope (do fewer findings), never
cut verification.

| Phase | Share | What |
|---|---|---|
| 1. Verify inherited state | ~15% | Steps 2–3 below, for the cluster you're about to touch only |
| 2. Implement | ~55% | The fix itself, scoped to one cluster (see §5) |
| 3. Verify your own work | ~20% | Step 4 below — re-read from source, run tests if they exist |
| 4. Record | ~10% | Update `FINDING_INDEX.md` + append one session log entry — short, factual, no narrative padding |

Do not spend budget re-reading the full audit (`../audit/`) or the full remediation history each
session. Read only: this file, `FINDING_INDEX.md`'s rows for your cluster, and the specific source
files your cluster touches.

---

## 3. Start-of-session checklist (mandatory, ~15% of budget)

For the specific finding IDs you're about to work on **only**:

1. Read the finding's row in `FINDING_INDEX.md`.
2. If it's marked `fixed` or `fixed+runbook`, do not take that on faith. Run the smallest possible
   check that would falsify it:
   - `grep` for the vulnerable pattern the finding describes — confirm it's actually gone.
   - If a commit hash is cited, run `git show --stat <hash>` and confirm it exists and touches the
     claimed files. If it doesn't exist (as happened with `SESSION-01.md`), treat the disposition as
     **unverified**, re-derive it from source yourself, and correct the citation.
   - If tests are cited, run them. Don't assume "20/20 pass" is still true; the file may have moved.
3. Only after this, decide what's actually left to do. Often less than the tracker implies (good) or
   more (also fine — better to find out now than after "fixing" the wrong thing).

This is exactly gate **G-1 ("source beats tracker")** already defined in `SECURITY_GATES.md` — this
protocol just makes it the mandatory first move of every session instead of an ideal.

---

## 4. End-of-session verification (mandatory, before writing anything is "fixed")

Never write a disposition based on:
- A commit message or PR title.
- "This should now work."
- A file name that sounds like it was created (verify it exists — `ls`/`Read` it).

Always write a disposition based on:
- The actual post-change source, re-read.
- A command you ran in *this* session, with its real output pasted into the session log — not a
  remembered/typical output.
- If you cite a commit hash, get it with `git log -1 --format=%H` (or from `git show`) **after**
  committing in this session — never type one from memory or invent a plausible-looking one.

---

## 5. Scope discipline — one cluster per session

Use the clusters already identified in `REMEDIATION_PLAN.md`. Do not attempt a full round in one
session; a round is many clusters. Suggested next clusters, in order:

1. ~~**`S07-C1` part 2 of 2 — signature verification (server) + signing call (Android client).**~~
   **DONE 2026-08-10 (commit `d833df4`, verified in a follow-up recovery session — see §0).** The
   prompt below is retained **for the record only**. Do not execute it. The next cluster to work is
   **Round 2 cluster A** (item 2 below); the ready-to-paste prompt for it is in §8.

   <details>
   <summary>Historical prompt for cluster 1 (already completed — do not run)</summary>

   > Read `security-remediation/SESSION_PROTOCOL.md` in full first. Then: `S07-C1` part 2 of 2.
   > `POST /mintChallenge` already issues a single-use nonce (`server/lib/challengeStore.js`,
   > tested). Your job: make `/mintToken` actually require and verify a signature over that nonce
   > before minting a token, replacing the current `sha256(identityPubKeyHex)` check (which is not
   > proof of anything, since the public key it hashes is readable by any authenticated user —
   > `firestore.rules:17`).
   >
   > Concretely:
   > 1. Confirm what signature scheme the Android client's identity key actually uses — grep
   >    `app/src/main/java/com/duoshield/app/crypto/signal/` for `Curve.calculateSignature` /
   >    `verifySignature` / `IdentityKeyPair` to see the exact library calls already in use
   >    (confirmed present as of 2026-08-10, not yet read in detail).
   > 2. On the server, verify that signature using **`@signalapp/libsignal-client`**, Signal's own
   >    official npm package with native Node bindings (confirmed to exist via web search
   >    2026-08-10 — not yet installed, not yet vetted in this repo; check its actual `PublicKey`
   >    verify API in its own docs/typings before writing code, don't guess the method name). Do
   >    **not** hand-roll XEdDSA/Curve25519 verification math in plain Node crypto — that is exactly
   >    the kind of "plausible but wrong" work that produced this program's worst fabrication
   >    (`SESSION-01.md`'s invented `xed25519.js`). Use the vetted library or stop and say so.
   > 3. Update `/mintToken`: require `{userId, identityPubKeyHex, nonce, signatureHex}`, call
   >    `mintChallengeStore.consume(userId, nonce)` first (reject if it returns `false`), then verify
   >    `signatureHex` over the nonce bytes against `identityPubKeyHex` before proceeding to the
   >    existing hash/lock/waitlist transaction logic — keep the existing checks, add this as an
   >    additional required gate, don't remove the hash check (defense in depth, cheap to keep).
   > 4. Add the client-side signing call in the Android sign-in/restore path (likely
   >    `app/auth/AuthTokenHelper.java` and/or `ui/RestoreFromSeedActivity.java` — verify exact paths,
   >    don't trust this description) to fetch a nonce from `/mintChallenge`, sign it with the
   >    identity private key, and send `nonce`+`signatureHex` to `/mintToken`.
   > 5. Write unit tests for the server-side verify function using a **real signature produced by
   >    the same library you verify with** (or, ideally, cross-checked against the Android library's
   >    output for one fixed test vector) — not a signature you invent by hand, which is how prior
   >    "tests" in this program were fabricated.
   > 6. If budget runs out after server-side verification but before the Android call is wired in,
   >    stop there, verify what you did against source per §4, and record precisely that split (not
   >    "S07-C1 fixed") — a server that correctly demands a signature no client yet sends is a
   >    partial fix, not a false one, as long as it's recorded as partial.

   </details>

   *Outcome:* both halves landed. Step 6's split did not end up being needed for the code, but its
   spirit applied to the **environment**: Android compilation could not be verified, so that
   limitation is recorded explicitly rather than glossed as success.
2. **Round 2, cluster A — media/duress:** `S03-H1` (typed media scope) + `S06-H2`/`S06-H3`/`S06-I2`
   (durable duress lock). These are grouped in the plan because they touch the same code paths.
3. **Round 2, cluster B — egress/SSRF:** `S04-H1`/`S04-H2`/`S04-H3`/`S08-H4` (link-preview SSRF +
   client off-origin block). Self-contained; has a literal test-row list in the audit to verify
   against.
4. **Round 2, cluster C — SecurePrefs + admin + build verifiability:** `S05-H1`/`S05-H3`/`S08-H2`/
   `S08-H3`, `SC-04`/`SC-05`/`SC-01`.
5. **Round 2, remainder + close-out.**
6. **Round 3, batched by folder** exactly as `REMEDIATION_PLAN.md` describes — one folder-checklist
   per session (`firestore.rules`, then Android crypto/platform, then supply-chain, etc.), since it
   explicitly says risk is mitigated by "grouping per folder-checklist and verifying each folder
   independently."

Do not let a session grow past its cluster because "there's budget left." Stop, record, end. A
half-verified extra finding is worse than a fully-verified single finding — it's exactly how the
false "9 of 11" vs "6 of 11" discrepancy happened.

---

## 6. Operator-only items — track once, stop re-attempting

`SC-12` (branch protection) and credential rotation (service-account key, B2 keys, `WORKER_SECRET`)
cannot be closed by an AI session in this environment. Each AI session that "attempts" these and
reports a result risks generating another false claim. Instead:

- They live in `migration/MIGRATION_PLAN.md` as numbered operator steps with exact commands.
- `FINDING_INDEX.md` marks them `fixed+runbook` (code half done) or `open` (nothing to fix in code)
  and points at the migration plan — never `fixed`.
- A session may re-check whether an operator has completed one (e.g. re-run the `gh api` branch
  protection check) and update the disposition if it now passes — that's verification, not an
  attempt to do the human's job.

---

## 7. Chain execution + per-session budget protocol (binding)

This remediation runs as a **continuous chain of bounded sessions**. Model: Claude Opus 5. Each
session has a **hard $5 ceiling — a ceiling, not a target.** Optimize for *verified security work per
token*.

**Mandatory session start (in this order):** this file → `FINDING_INDEX.md` → the previous session's
recorded evidence → `git status --short` → `git log -3 --oneline --stat`. Then identify the exact next
*unfinished* cluster. Never redo a finding recorded `fixed` unless current source falsifies it.

**Hard limit: 4 tasks per session; prefer 2–3.** Always in this order:
1. Recover/verify inherited state
2. Implement the smallest necessary fix
3. Focused verification
4. Record + checkpoint

**Four budget stages:**
- **Recovery** — minimum context to establish current commit, finding status, the cluster's files, and
  what the last session finished. No whole-repo reads, no general re-audit, no unrelated directories.
- **Implementation** — assigned cluster only. Identify the smallest code path, the existing project
  convention, and the *security invariant* being enforced, then patch minimally. Do not redesign
  architecture unless it makes the required property impossible.
- **Verification** — the smallest tests that actually prove the change, in priority order: focused
  security tests → affected module tests → relevant integration tests → broader suites only if cheap.
  If a toolchain is missing, mark it `BLOCKED` and fall back to source-level verification; **do not**
  provision large toolchains for low-value checks. **Never fabricate a PASS.**
- **Record + checkpoint** — evidence, `FINDING_INDEX.md`, session log, commit, clean `git status`,
  next-session prompt. **Documentation is mandatory before stopping.**

**Stop implementation immediately when** the finding is fixed and verified · tests pass and only docs
remain · a required toolchain is unavailable · the next change needs substantial investigation outside
the cluster · the budget limit is near. **Do not start another cluster merely because credits remain.**
Do not spend the last of the budget polishing prose.

**Interruption / credit exhaustion:** never restart a cluster from scratch. Read the recorded state,
inspect the actual commit, determine what survived, reproduce only the missing verification, continue
from the last safe checkpoint. Never assume an interrupted session lost its work; never redo committed
work without evidence it is wrong. (The `S07-C1` part-2 recovery in `SESSION-01.md` §13 is the worked
example: everything had survived, and only the recording was missing.)

**Prohibited unless the assigned finding requires it:** broad re-audits, repeating finished
exploration, reading large unrelated files, cosmetic refactoring, renaming unrelated symbols,
rewriting working tests, verbose narration during implementation, speculative fixes, unnecessary
dependency changes, unrelated full-suite reruns.

**Every session ends with this record** appended to the session log:

```
SESSION:  MODEL: Opus 5  BUDGET: $5 max  CLUSTER:  STATUS: fixed|partial|blocked|open
CHANGES:      - ...
VERIFICATION: PASS: … / FAIL: … / BLOCKED: … / NOT RUN: …
COMMIT: <exact hash>          WORKTREE: clean|dirty
NEXT SESSION: <ready-to-paste prompt>
```

A session may only end with **(A)** a clean checkpoint + next-session prompt, or **(B)** an explicitly
documented safe *partial* checkpoint + a continuation prompt for the **same** finding.

**Chain termination:** when all findings are dispositioned, do **not** declare the project secure.
Run a dedicated FINAL VERIFICATION session (§9) first.

---

## 8. Chain state + ready-to-paste prompt for the NEXT session (single item: `SC-05`)

### Chain state (authoritative, updated 2026-08-10, session `02c`)

| Unit | Findings | State |
|---|---|---|
| R1 `S07-C1` | + `S02-M1`, `S02-L1`, `S06-H1`, `S03-L1` | **CLOSED** at `2b7fc4c`. Do not re-litigate. |
| R2 cluster A | `S03-H1`, `S06-H2`, `S06-H3`, `S06-I2` | **CODE COMPLETE + RECORDED.** Code `bb5b8bb` (merged PR #55); recording completed/corrected `224546b`. |
| R2 cluster B | `S04-H1`, `S04-H2`, `S04-H3`, `S05-H1`, `S05-H3`, `S05-I1` | **CODE COMPLETE + RE-VERIFIED, RECORDING COMPLETED.** Code `48a3f7e`/`f636d8b`/`7653515` (merged PRs #57–#59). Session `02c` (2026-08-10) re-derived every claim from source instead of trusting the tracker (per §3) — all wiring confirmed live (not dead code), `cd server && npm test` → **153/153 pass** reproduced fresh (after `npm ci`; a stale clone briefly showed 138/137/1-fail, resolved by the fresh-clone note in §0/PR-6, not by editing any disposition). **Do not redo.** The missing `§7` session-log record for this cluster (it was implemented+tracked but never logged) was filled in retroactively in `sessions/SESSION-02.md` §8. |
| R2/R3 misc | `S10-N3` | **FIXED this session** (`worker/src/index.js`, one-line, source-reviewed only — see `PR-7`, no worker test harness exists here). Everything else originally grouped in "cluster C" is still open — see below, deliberately **not** batched into one prompt anymore. |
| Remaining cluster-C-shaped work | `S08-H5`/`S07-M1` (SecurePrefs, Java), `S08-H2`/`H3` (Java), `S10-N2`/`S07-L4` (Java), `SC-01`/`SC-04` (supply chain, YAML/script) | Not started. **Deliberately not assigned as one 6-8-finding cluster** — see note below. |
| **`SC-05`** | Release workflow deletes all prior releases/tags on every push to `main` | **← NEXT. Single item, ~20 lines of YAML, fully described in `audit/SESSION-09-SUPPLY-CHAIN-CI.md` line 269+. No Java, no toolchain gap.** |
| R3 | everything still `open`/`partial` (incl. `S01-L1` remainder) | Not started. |

**Why the next prompt is one finding, not a cluster:** every session so far that batched 4-6 findings
ended up spending real budget on verification/correction of *inherited* claims before it could start
new work (cluster A's dead-code catch, cluster B's `npm ci` false alarm this session). Shrinking the
unit of work to one finding removes that overhead almost entirely — there is nothing upstream to
falsify for `SC-05` beyond reading the one workflow file — and it directly answers the standing
instruction to keep each session's assigned scope smaller than the one before it, since credits are
limited. The remaining Java-only findings (`S08-*`, `S10-N2`, `S07-*`, `SC-01`) should each become
their own single-item prompt too, in whatever order is convenient, once `SC-05` is closed.

**Cluster A outcome — what is fixed, what is blocked:**

- `S03-H1` **fixed**, server layer genuinely test-verified (`lib/mediaScope.js`, 16/16). The
  `firestore.rules` `groups`-create hardening also shipped but is **source-reviewed only**.
- `S06-H3` **fixed** — `maintainLockCredential()` was dead code; call added in `BaseActivity.onStart()`.
- `S06-H2`, `S06-I2` **fixed, no code change** — were already remediated; the `open` rows were stale.
- `S01-L1` moved `open` → **partial** as a side effect (its `createdBy` half is closed).

**BLOCKED, not done (see `PR-4` in `RISK_REGISTER.md`):** Android compilation (no JDK/SDK); the 4 new
`firestore-tests/rules.test.js` `S03-H1` cases (**added, never executed** — no JVM/`firebase` CLI).

**Cluster B outcome (re-verified 2026-08-10, session `02c`):** all six rows genuinely fixed and
test-backed. `cd server && npm test` → **153 tests / 153 pass / 0 fail**, reproduced fresh this
session (a stale clone briefly showed 138/137/1-fail before `npm ci`; see `PR-6`). Every claimed call
site (`egressGuard.resolveAndCheckHost`, `adminSecret.evaluateSecretStrength`, `auditAdminEvent`'s 7
sites) was grepped and confirmed live, not dead code. The `§7` session-log record for this cluster had
never been appended anywhere — filled in retroactively in `sessions/SESSION-02.md` §8 this session.

**MUST NOT BE REDONE:** any cluster A or cluster B code — `server/lib/mediaScope.js`, `egressGuard.js`,
`imageProxy.js`, `adminSecret.js`, the `firestore.rules` `groups`-create constraints, the
`BaseActivity`/`DuressManager`/`PendingLockStore` Java edits, `auditAdminEvent()` and its 7 call sites.
All ten rows across both clusters hold final dispositions.

**Also fixed this session, outside any cluster:** `S10-N3` (`worker/src/index.js`, one line — undoes
the nightly R2→B2 migration's own write when it detects a concurrent client delete, so deleted media
cannot survive forever in B2 cold tier). Source-reviewed only; `worker/` has no test framework in this
environment (`PR-7`).

**Task budget for the next session: 1.** Deliberately shrunk from "cluster" to "single finding" — see
the chain-state table's rationale note above. Do not add a second finding to this prompt even if
budget remains; that is exactly the "grew past its cluster because there's budget left" mistake §5
already warns against, just at a smaller scale.

### Paste this verbatim

> Read `security-remediation/SESSION_PROTOCOL.md` in full first (§8 chain state especially), then the
> `SC-05` row in `FINDING_INDEX.md`, then `git status --short` and `git log -3 --oneline`. Scope this
> session to **`SC-05` only** — one finding, not a cluster.
>
> Do **not** touch, re-verify, or "improve" R1 `S07-C1`, R2 cluster A, R2 cluster B, or `S10-N3`. All
> are recorded with final dispositions (cluster B was re-verified and `S10-N3` fixed in the immediately
> prior session — read that session's record in `sessions/SESSION-02.md` §8 rather than redoing the
> falsification work). Re-litigating any of them wastes this session's budget on zero new security
> value.
>
> **The finding:** `SC-05` — the release workflow (`.github/workflows/release.yml`, roughly lines
> 126-162 per the audit, **but verify the real current line numbers with Grep first**, they drift) does
> something on every push to `main` that deletes *every prior GitHub Release and every prior git tag*,
> not just the one it's about to replace. Read the full writeup at
> `audit/SESSION-09-SUPPLY-CHAIN-CI.md` line 269 onward before writing any YAML — it explains exactly
> which step does the deletion and why, and names the interaction with `SC-04` (no checksums) that you
> should be aware of but **not fix in this session** (that is a separate, already-deferred finding).
>
> **Implement:** change the release step so it only ever touches the release/tag for *this* version —
> stop deleting the full history of releases and tags. The audit file states the intended remedy
> shape; follow it, verifying against the actual current workflow syntax rather than assuming GitHub
> Actions YAML you remember from training.
>
> **Verify:** this is YAML with no runtime in this sandbox (no way to actually trigger a workflow run
> here), so verification per §4 means: re-read the changed file, confirm the deletion step now scopes
> to the current tag/version only, and — if `actionlint` or a YAML linter is available in this
> environment — run it. If nothing can execute the workflow, record verification as
> **source-reviewed only**, exactly like `S10-N3` in the prior session; do not claim a test that cannot
> run here.
>
> **Record per §4:** update the `SC-05` row in `FINDING_INDEX.md` with what you actually changed and
> how you verified it; append a short session record to `sessions/SESSION-02.md` (or start
> `sessions/SESSION-03.md` if you judge the file has grown unwieldy — your call, not mandatory); update
> this §8 chain state to name the next single finding (suggest `SC-04` or `SC-01`, both supply-chain,
> both no Java) with the same one-item scoping. Get a commit hash from `git log -1 --format=%H` **after**
> committing — never invent one.

## 9. What "done" looks like for this whole program

**Chain termination requires a dedicated FINAL VERIFICATION session** that: reads the complete finding
index; verifies *every* disposition against current source; hunts for contradictory evidence; runs the
highest-value regression/security tests available; confirms no required finding was skipped; and only
then produces the final report. Marking the chain COMPLETE in the same session that fixes the last
finding is forbidden — that is the exact shape of the earlier false closures in §0.


Unchanged from `REMEDIATION_PLAN.md`'s Round 3 hard stop: every one of the 116 findings has exactly
one disposition in `FINDING_INDEX.md`, no Critical/High remains open, `FINAL_SECURITY_REPORT.md` and
`FINAL_SIGNOFF.md` are written — and each of those documents must itself pass the §4 test (written
from source, not from the trackers) before it's trusted. Number of sessions to get there is
irrelevant; a false "done" is worse than a slow true one.
