# START HERE

**You said: "head to this folder, open this md, and start working." This is that md.**
Do exactly what is below, in order. Do not improvise a different plan. Do not declare anything
`fixed` without running the verification named for it.

---

## What this program is

All 20 scheduled Round 3 remediation sessions are complete at the source/documentation level. That is not equivalent to end-to-end sign-off. Firestore Rules emulator coverage, Android unit/lint/build/Espresso/API 35/R8 checks, server/Worker tests and security scanning remain part of the automated gate; operator, staging and physical-device acceptance are separate.

**Latest main merge (2026-11-11):** PR #292 merged as `b73cab8049983969b1996245600a95ff227da175`. Exact-head CI [42948877593](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877593) passed all 10 jobs (AST release-log policy, libsignal and wrapper integrity, Server + Worker, Android unit tests, lint, debug APK, Espresso, API 35 predictive-back and minified R8); Security [42948877583](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877583) passed 3/3; Firestore Rules/server transactions [42948877424](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877424) passed. PR #292 adds a synchronous, durable/read-back secondary-code write and real WorkManager worker-path tests. S06-H4 remains Fixed for its code implementation; S06-L4 remains Partial pending staging and physical-device recovery acceptance. The other outstanding items are documented residual/operator/device gates, not new confirmed code defects. See `../BUG_TRACKER.md`, `NEXT_WORK_REPORT_2026-11-08.md` and the 2026-11-11 session record.

PR #267 corrected the Firebase App Check integration: Firestore Rules cannot inspect App Check token metadata, so the unsupported `request.app` helper was removed. The committed rules are now tested verbatim; App Check remains a Firebase service-layer rollout requiring operator-controlled monitoring, enforcement and isolated staging evidence.

PR #279 and #280 refreshed the tracker baseline and handoff. PR #281 extended `scripts/AndroidReleaseLogPrivacyPolicy.java` to resolve chained app-owned receiver types through locals, fields, casts/parentheses and uniquely summarized helper returns; CI [42561844959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844959) passed 10/10 and Security [42561844900](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844900) passed 3/3. PR #282 and #283 merged documentation-only tracker/baseline corrections. PR #284 extended the analyzer through inherited app-owned methods/fields, explicit `super`, duplicate simple-name ambiguity (fail closed), and qualified `Outer.this` receivers; exact head `23eb17ac71ef6832195d77d6765106ca3bda143c` merged as `30329d7b56ec0b26fd9b40495cdbb3d3bd999cae`; CI [42567271589](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42567271589) 10/10 and Security [42567271633](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42567271633) 3/3. PR #285 was documentation-only. PR #286 further makes unresolved class/method generic returns and chained receivers, recognized Java reflection/class-loading operations, and opaque callback lambda parameters fail closed. The lambda taint is lexically scoped; the only exceptions are the safe exception-class-name projection and `VoiceMessagePlayer`'s MediaPlayer integer error-code parameters. Exact head `91751692e7c491a1aba948b2b1da4f2bbada120c` merged as `26daea8e8b2e45764a68000d02a1f93a486d2fde`; CI [42575704977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704977) passed 10/10 and Security [42575704959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704959) passed 3/3. Exact-head AST scanned 205 Java files / 414 log sinks with zero violations; Espresso executed 4 tests; API 35 predictive-back executed 1 test; debug APK and minified R8 passed. S10-N2 remains Partial: this remains bounded AST analysis, not symbol-resolved whole-program proof, and physical release/minified APK logcat acceptance is deferred.

PR #265 merged the successful/failed PIN-change throttle and generic failure contract for S06-H5. PR #267 subsequently passed the full CI and Security/Firestore workflows on its exact PR head before merging. PR #269 then hardened S10-N2 bodyless helper returns; its exact-head CI [42446136767](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136767) and Security [42446136770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136770) passed before merge. PR #270 refreshed the handoff on the then-current main. PR #271 added bounded app-owned buffer/cache hygiene for S07-L2; exact-head CI [42448400756](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400756) passed 10/10 jobs and Security [42448400818](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400818) passed 3/3 before merge. PR #272 refreshed agent instructions and handoff evidence; PR #273 was closed as a duplicate unmerged. PR #274 fixed a reproduced typed cross-class sensitive-field logging gap. PR #275 refreshed the handoff. PR #276 added fail-closed handling for known app-owned virtual overrides (CI [42453996138](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996138) 10/10; Security [42453996152](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996152) 3/3). PR #277 added fail-closed handling for ambiguous same-name/same-arity app-owned overloads (CI [42454608770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608770) 10/10; Security [42454608761](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608761) 3/3). PR #278 reconciled the independent tracker/handoff snapshot; exact-head CI [42457565977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565977) passed 10/10 and Security [42457565979](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565979) passed 3/3.

The canonical `../BUG_TRACKER.md` retains **116 findings: 86 Fixed, 17 Partial, 1 Open, 12 Accepted**. PR #292 is the latest source-changing merge: S06-H4 now requires a durable SecurePrefs tier, synchronous slot-B commit and successful read-back before claiming the secondary code is armed; PR #292 adds commit/read-back failure regressions. S06-L4 now has WorkManager `TestWorkerBuilder` runtime coverage for confirmed, permanent, retryable, exhausted, network-exception and missing-nonce paths, but remains Partial pending staging/device recovery acceptance. PR #290's S06-H5 throttle covers Settings, restored setup and forced rotation; independent side-channel/device acceptance remains. S02-I2 still needs authorized Render/Worker secret-pair read-back and production runtime confirmation. S07-L2's native destruction limitation, S10-N2's syntax-analysis/physical logcat residuals, S05-H2/S05-M3 staging and owner gates, SC-12 repository governance and other deferred device checks remain. `FINAL_SIGNOFF.md` remains **PENDING**.

---

## Current state — 2026-11-11 (post-PR #292 merged)

```
LATEST SOURCE-CHANGING MERGE: PR #292 — b73cab8049983969b1996245600a95ff227da175
LATEST VERIFIED APP-CODE BASELINE: PR #292; CI 10/10, Security 3/3, Firestore Rules/server transactions passed
MERGED: PR #292 (S06-H4 durable secondary-code persistence/read-back; S06-L4 WorkManager runtime coverage); PR #290 (S06-H5 all primary-PIN collision paths); PR #289 (S10-N2 same-owner method-reference callback); PR #288 (S02-I2 production fail-closed), plus prior remediation PRs listed above.
CI: 42948877593 — passed (AST, unit, lint, debug APK, Espresso, API 35 predictive-back, R8, Server + Worker, wrapper and libsignal integrity).
SECURITY: 42948877583 — passed (CodeQL, Gitleaks, SBOM).
FIRESTORE RULES + SERVER TRANSACTIONS: 42948877424 — passed.
TRACKER: 116 findings — 86 Fixed, 17 Partial, 1 Open, 12 Accepted.
S06-H4: Fixed for code/automated coverage; durable tier + synchronous commit + read-back required before UI success.
S06-L4: Partial; real worker path now executed with test fakes. Staging lost-response/admin-unfreeze and physical wipe/reboot/network-loss acceptance remain.
S06-H5: Partial; Settings, restored setup and forced rotation share the throttle; independent side-channel/device review remains.
S07-L2: Partial; no deterministic public native-key destruction API in pinned libsignal Java binding.
S10-N2: Partial; bounded AST analysis, unmodeled whole-program/external/native paths and physical release/minified logcat acceptance remain.
S05-H2 / S05-M3: Partial pending owner/product, historical-data migration and production staging acceptance.
OPEN ISSUES: 0 at the last verified inventory. This tracker/handoff reconciliation PR is open while being reviewed.
NEXT: use BUG_TRACKER.md, NEXT_WORK_REPORT_2026-11-08.md, COMPREHENSIVE_REMAINING_SECURITY_REVIEW_2026-11-07.md and the 2026-11-11 session record.
SCHEDULE: S3-20 is the final numbered Round 3 session. Do not invent S3-21 or reopen a fourth audit round.
PHYSICAL DEVICE TESTING: deferred until the owner explicitly declares the app feature-complete.
FINAL SIGN-OFF: PENDING.
```

PR #274 exact-head workflows passed before merge: CI [42450251567](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251567) and Security [42450251553](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251553). The combined post-merge Vercel status for `d1ee9ac01e0809a6e398958be423097bc082e9d7` was successful. No separate post-merge GitHub Actions workflow run was returned at this snapshot; do not infer one from PR-head checks.

## Chain state — historical Round 3 schedule

The block below records the scheduled-session closeout. It is not a claim that every
operator, staging, governance or device acceptance gate is complete.

```
NEXT ACTION: S3-20 is the final scheduled session and is complete. Do not invent S3-21.
S3-15b Firestore Rules catch-up: 186/186 emulator tests passed.
S3-19b Android catch-up: verified on main CI (lint, unit tests, debug APK, Espresso and release R8).
The 2026-11-04 main commit/run references in the original session notes are historical.
Remaining security, staging and operator actions are enumerated in
security-remediation/NEXT_WORK_REPORT_2026-11-08.md, the agentic handoff and BUG_TRACKER.md.
```

If the historical `NEXT ACTION` above says S3-20 complete, do **not** start a new numbered Round 3
session. Follow the refreshed work report for source-review priorities, owner decisions, operator
acceptance and feature-complete device deferrals. PR #290 is the only open PR; no open issues were returned. Continue from NEXT_WORK_REPORT_2026-11-08.md; do not reopen superseded branches.


---

## Do this, in order

1. **Read the rules of engagement.** Open [`SESSION_PROTOCOL.md`](./SESSION_PROTOCOL.md) and read it
   in full. It is binding: source beats tracker, ≤4 tasks/session, never fabricate a PASS, never make
   `/mintToken` auth fields optional.
2. **Open the current handoff.** Use [`NEXT_WORK_REPORT_2026-11-08.md`](./NEXT_WORK_REPORT_2026-11-08.md)
   and [`AGENTIC_WORK_REMAINING_2026-11-06.md`](./AGENTIC_WORK_REMAINING_2026-11-06.md)
   for the refreshed baseline, source-review priorities, operator blockers and feature-complete device deferrals.
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
