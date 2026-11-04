# START HERE

**You said: "head to this folder, open this md, and start working." This is that md.**
Do exactly what is below, in order. Do not improvise a different plan. Do not declare anything
`fixed` without running the verification named for it.

---

## What this program is

All 20 scheduled Round 3 remediation sessions have been worked through at the
source/documentation level; this is not equivalent to end-to-end sign-off. The Firestore Rules
catch-up gate S3-15b passed **186/186 emulator tests**. The broader Android catch-up gate S3-19b
remains outstanding. On 2026-11-04, security fixes #201–#205 and #207–#208, plus the separate
Espresso SDK setup fix #200, were merged to `main`. See the root `../BUG_TRACKER.md` for
finding-level dispositions and the current test evidence. `FINAL_SIGNOFF.md` remains **PENDING**
until all residual test and operator gates have been verified.

---

## Chain state — current next action

<!-- Current state as of 2026-11-04. Historical session narratives below are retained. -->

```
NEXT ACTION: S3-20 is the final scheduled session and is complete. Do not invent S3-21.
S3-15b (Firestore Rules catch-up) is DONE: 186/186 emulator tests passed. S3-19b (full Android
compile + unit-test catch-up for the Android changes in S3-15/16/17/18/19 and the S07-C1
Android compile gate) remains OPEN. PR #200's separate Espresso workflow passed 2 instrumentation
tests, but does not satisfy this broader catch-up gate. Security PRs #201–#205/#207/#208 and
Espresso PR #200 are merged. Post-merge CI and Security scan on main at
b17f26437bc0731b12f8d5bdb99d4268d860adc9 both completed successfully (runs 41792882092 and
41792882126). Remaining gates: Firebase revoke-token integration verification for S02-I3; SC-03
Gradle dependency hashes populated and enforced; SC-12 main branch protection; leaked GCP
service-account key and remaining Worker/B2/admin credential rotations; App Check enforcement
and Firebase/Firestore operator configuration; production environment/tag protections; trust-
boundary review; and coordinated server + Android build/release. Keep FINAL_SIGNOFF.md PENDING
until each item is verified from live evidence.
```

If `NEXT SESSION` above is `S3-20 complete`, do **not** start coding — go to the sign-off gate at the
bottom of the plan and verify operator items instead.

---

## Do this, in order

1. **Read the rules of engagement.** Open [`SESSION_PROTOCOL.md`](./SESSION_PROTOCOL.md) and read it
   in full. It is binding: source beats tracker, ≤4 tasks/session, never fabricate a PASS, never make
   `/mintToken` auth fields optional.
2. **Open the plan.** In [`ROUND3_REMEDIATION_PLAN.md`](./ROUND3_REMEDIATION_PLAN.md), find the
   session named in `NEXT SESSION` above. That session's finding IDs, code targets, verification lane,
   and exit criteria are your entire scope. **Do not pull work forward from a later session.**
3. **Recover state (SESSION_PROTOCOL §3 + §7).** Run:
   - `git status --short` and `git log -3 --oneline --stat`
   - For each finding ID in this session, read its row in [`../BUG_TRACKER.md`](../BUG_TRACKER.md) and
     run the smallest check that would falsify its current status (`grep` the vulnerable pattern; if a
     commit is cited, `git show --stat <hash>`). If source already satisfies it, record that and skip.
4. **Implement the smallest fix** for this session's findings only. Keep existing guards; add beside
   them. Honor the standing invariants in the plan (especially: `nonce`/`signatureHex` stay mandatory).
5. **Verify in this session's lane** (the plan names it):
   - `SRV` → `cd server && npm test` (must stay green; add cases for the findings).
   - `WORKER` → `node --check` + worker unit tests.
   - `RULES` → write emulator tests now, but the run is **BLOCKED** here → land as
     `partial — RULES verification BLOCKED`; promotion happens in **S3-15b**.
   - `AND` → write/adjust code + tests now, run is **BLOCKED** here → land as
     `partial — AND verification BLOCKED`; promotion happens in **S3-19b**.
   - `CI` → YAML lint + `git`/`gh` inspection + hash recompute; actual CI run is operator.
   - **If a toolchain is missing, mark BLOCKED and fall back to source review. Never invent a PASS.**
6. **Document + checkpoint** (mandatory before stopping):
   - Update each finding's status in [`../BUG_TRACKER.md`](../BUG_TRACKER.md) with real
     evidence (a command you ran this session, or a commit hash from `git log -1 --format=%H` **after**
     committing). Only write `fixed` if the lane's verification actually ran and passed.
   - Write `sessions/SESSION-<n>.md` with the end-of-session record block (format in
     `SESSION_PROTOCOL.md §7`).
   - Commit; confirm `git status` is clean.
   - **Update the Chain state block above**: set `NEXT SESSION` to the following session, set
     `LAST DONE` to the one you just finished.
7. **Stop.** One session = one scheduled batch. Do not start the next session because budget remains.

---

## Operator-only items (do not fake; only re-verify)

These cannot be completed by a session in this environment — revoke the leaked GCP service-account
key, rotate `WORKER_SECRET`/B2/admin creds, enable `SC-12` branch protection, Firestore TTL, App
Check enable, SBOM, and the Android build+release. A session may **re-check** whether an operator has
done one (e.g. `gh api repos/kumarclaude4-hash/DuoFatass/branches/main/protection`) and update the
disposition from live evidence — but must never report an operator action as done without that
evidence. Tracked in the plan's sign-off gate and `SESSION_PROTOCOL.md §6`.

---

## Reading order summary

`START_HERE.md` (this file, for `NEXT SESSION`) → `SESSION_PROTOCOL.md` (rules) →
`ROUND3_REMEDIATION_PLAN.md` (the session's scope) → `../BUG_TRACKER.md` (per-row truth) →
previous `sessions/SESSION-*.md` (inherited evidence). Then work.
