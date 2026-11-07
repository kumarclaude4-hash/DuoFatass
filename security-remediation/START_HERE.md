# START HERE

**You said: "head to this folder, open this md, and start working." This is that md.**
Do exactly what is below, in order. Do not improvise a different plan. Do not declare anything
`fixed` without running the verification named for it.

---

## What this program is

All 20 scheduled Round 3 remediation sessions are complete at the source/documentation level.
That is not equivalent to end-to-end sign-off. S3-15b Firestore Rules emulator coverage passed
**186/186 tests**, and S3-19b Android catch-up is verified on main CI (Android unit tests, lint,
debug APK, Espresso and minified R8).

As of 2026-11-07, main is `98f76cdf374eeaf5c8144befad068ef05805a20b`. Main CI
[42387261365](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261365) and
Security Scan [42387261135](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42387261135)
passed. PR #255 (invite UID pseudonymization), PR #256 (Worker range-read rate accounting) and
documentation PR #257 are merged. Current code PRs #253, #254 and #258 remain open; their exact
heads, stale merge bases and CI blockers are recorded in
[`AGENTIC_WORK_REMAINING_2026-11-06.md`](AGENTIC_WORK_REMAINING_2026-11-06.md).

The canonical `../BUG_TRACKER.md` now contains **116 findings: 86 Fixed, 17 Partial, 1 Open,
12 Accepted**. The two restored Session 06 findings S06-H4 and S06-H5 are included in that
count. `FINAL_SIGNOFF.md` remains **PENDING** until residual test, staging, operator, governance
and deferred physical-device acceptance gates have been evidenced or formally approved.

---

## Current state — 2026-11-07

```
MAIN: 98f76cdf374eeaf5c8144befad068ef05805a20b
CI: 42387261365 — passed
SECURITY: 42387261135 — passed
MERGED: PR #255 (invite UID pseudonymization), #256 (range-read rate accounting), #257 (docs).
OPEN: PR #253 (invite-state fail-closed; stale/dirty base), #254 (range-read allowance; stale/dirty base),
      #258 (migration credential normalizer; Gitleaks fixture + Firestore expiry test failures).
TRACKER: 116 findings — 86 Fixed, 17 Partial, 1 Open, 12 Accepted.
NEXT: Follow AGENTIC_WORK_REMAINING_2026-11-06.md for the active PRs and current acceptance gaps.
SCHEDULE: S3-20 is the final numbered Round 3 session. Do not invent S3-21 or reopen a fourth audit round.
PHYSICAL DEVICE TESTING: deferred until the owner explicitly declares the app feature-complete.
FINAL SIGN-OFF: PENDING.
```

## Chain state — historical Round 3 schedule

The block below records the scheduled-session closeout. It is not a claim that every
operator, staging, governance or device acceptance gate is complete.

```
NEXT ACTION: S3-20 is the final scheduled session and is complete. Do not invent S3-21.
S3-15b Firestore Rules catch-up: 186/186 emulator tests passed.
S3-19b Android catch-up: verified on main CI (lint, unit tests, debug APK, Espresso and release R8).
The 2026-11-04 main commit/run references in the original session notes are historical.
Remaining security, staging and operator actions are enumerated in
security-remediation/AGENTIC_WORK_REMAINING_2026-11-06.md and BUG_TRACKER.md.
```

If the historical `NEXT ACTION` above says S3-20 complete, do **not** start a new numbered Round 3
session. Follow the current agentic handoff for bounded code PRs and acceptance preparation; do not
treat the scheduled-session stop rule as a reason to ignore the active open PRs.


---

## Do this, in order

1. **Read the rules of engagement.** Open [`SESSION_PROTOCOL.md`](./SESSION_PROTOCOL.md) and read it
   in full. It is binding: source beats tracker, ≤4 tasks/session, never fabricate a PASS, never make
   `/mintToken` auth fields optional.
2. **Open the current handoff.** Use [`AGENTIC_WORK_REMAINING_2026-11-06.md`](./AGENTIC_WORK_REMAINING_2026-11-06.md)
   for current code PRs, source-only tasks, operator blockers and feature-complete device deferrals.
   The numbered Round 3 plan is complete; do not invent another scheduled session.
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
   - Update the current dated handoff and finding-level tracker. Do not advance or invent a new
     numbered Round 3 session after S3-20.
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

## Final mainline verification — 2026-11-04

PR #209 merged at `00aec631041643e0ce7b35b9c1220d35875a48ee`. A deterministic-clock fix for
Durable Object expiry tests followed; current main is `c38967540294355ae94adb3f913d8f637dd1693d`.
Main CI [41810314589](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41810314589)
and Security scan [41810314539](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41810314539)
are successful. Main CI ran the full Server + Worker suites, lint, Android unit tests, debug APK
build, Espresso (2 tests executed), Gradle wrapper validation, and libsignal integrity. See the
post-merge addendum in `../BUG_TRACKER.md` and the explicit unresolved items in `FINAL_SIGNOFF.md`.
