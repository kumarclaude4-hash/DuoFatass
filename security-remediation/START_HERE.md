# START HERE

**You said: "head to this folder, open this md, and start working." This is that md.**
Do exactly what is below, in order. Do not improvise a different plan. Do not declare anything
`fixed` without running the verification named for it.

---

## What this program is

All 20 scheduled Round 3 remediation sessions are complete at the source/documentation level. That is not equivalent to end-to-end sign-off. Firestore Rules emulator coverage, Android unit/lint/build/Espresso/API 35/R8 checks, server/Worker tests and security scanning remain part of the automated gate; operator, staging and physical-device acceptance are separate.

**Current main baseline (2026-11-14):** main remains
`cf5b496e406c0d27bfd2408ec160661dbf7885cd`; the latest application-code merge is PR #299.
PR #301 documentation reconciliation merged after exact-head CI [43536863859](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43536863859)
10/10 and Security [43536864157](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43536864157)
3/3. PR #303 is open for S06-H6 primary/device PIN durability. Its latest source/test head at
this checkpoint is `e292362e0139c1a9291631985dc82ce2c82eb08f`; durable single-store writes, recoverable account/device pairing,
orphan-stage fail-closed behavior and metadata cleanup safeguards are implemented. The
instrumented test harness was updated to wait for a new SignInActivity intent after asynchronous
PIN save/verification rather than asserting synchronously. Exact-head CI [43549525173](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43549525173)
was queued and Security [43549525218](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43549525218) was in progress at this checkpoint; do not treat
either as passed. This documentation synchronization will trigger another exact-head run.
S06-H6 remains Partial until final-head CI/Security pass. Real Keystore/process-death/device
acceptance remains separate. PR #296/#297 retain their existing screenshot-policy and call-lock
lifecycle status; S08-H2 and S11-M1 residuals remain unchanged.

**Merged S06-H5 hardening:** PR #299 (branch `security/s06-h5-throttle-requires-durable-secureprefs-2026-11-13`) closes a fail-open storage-tier gap in candidate throttling. The helper rejects SecurePrefs' in-memory-only `NONE` tier before candidate inspection; the regression proves the attempt budget is not consumed or granted on ephemeral storage. See `sessions/SESSION-S06-H5-DURABLE-THROTTLE-2026-11-13.md`. S06-H5 remains Partial pending independent side-channel review and feature-complete device acceptance.

PR #267 corrected the Firebase App Check integration: Firestore Rules cannot inspect App Check token metadata, so the unsupported `request.app` helper was removed. The committed rules are now tested verbatim; App Check remains a Firebase service-layer rollout requiring operator-controlled monitoring, enforcement and isolated staging evidence.

PR #279 and #280 refreshed the tracker baseline and handoff. PR #281 extended `scripts/AndroidReleaseLogPrivacyPolicy.java` to resolve chained app-owned receiver types through locals, fields, casts/parentheses and uniquely summarized helper returns; CI [42561844959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844959) passed 10/10 and Security [42561844900](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844900) passed 3/3. PR #282 and #283 merged documentation-only tracker/baseline corrections. PR #284 extended the analyzer through inherited app-owned methods/fields, explicit `super`, duplicate simple-name ambiguity (fail closed), and qualified `Outer.this` receivers; exact head `23eb17ac71ef6832195d77d6765106ca3bda143c` merged as `30329d7b56ec0b26fd9b40495cdbb3d3bd999cae`; CI [42567271589](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42567271589) 10/10 and Security [42567271633](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42567271633) 3/3. PR #285 was documentation-only. PR #286 further makes unresolved class/method generic returns and chained receivers, recognized Java reflection/class-loading operations, and opaque callback lambda parameters fail closed. The lambda taint is lexically scoped; the only exceptions are the safe exception-class-name projection and `VoiceMessagePlayer`'s MediaPlayer integer error-code parameters. Exact head `91751692e7c491a1aba948b2b1da4f2bbada120c` merged as `26daea8e8b2e45764a68000d02a1f93a486d2fde`; CI [42575704977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704977) passed 10/10 and Security [42575704959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704959) passed 3/3. Exact-head AST scanned 205 Java files / 414 log sinks with zero violations; Espresso executed 4 tests; API 35 predictive-back executed 1 test; debug APK and minified R8 passed. S10-N2 remains Partial: this remains bounded AST analysis, not symbol-resolved whole-program proof, and physical release/minified APK logcat acceptance is deferred.

PR #265 merged the successful/failed PIN-change throttle and generic failure contract for S06-H5. PR #267 subsequently passed the full CI and Security/Firestore workflows on its exact PR head before merging. PR #269 then hardened S10-N2 bodyless helper returns; its exact-head CI [42446136767](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136767) and Security [42446136770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136770) passed before merge. PR #270 refreshed the handoff on the then-current main. PR #271 added bounded app-owned buffer/cache hygiene for S07-L2; exact-head CI [42448400756](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400756) passed 10/10 jobs and Security [42448400818](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400818) passed 3/3 before merge. PR #272 refreshed agent instructions and handoff evidence; PR #273 was closed as a duplicate unmerged. PR #274 fixed a reproduced typed cross-class sensitive-field logging gap. PR #275 refreshed the handoff. PR #276 added fail-closed handling for known app-owned virtual overrides (CI [42453996138](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996138) 10/10; Security [42453996152](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996152) 3/3). PR #277 added fail-closed handling for ambiguous same-name/same-arity app-owned overloads (CI [42454608770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608770) 10/10; Security [42454608761](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608761) 3/3). PR #278 reconciled the independent tracker/handoff snapshot; exact-head CI [42457565977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565977) passed 10/10 and Security [42457565979](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565979) passed 3/3.

The canonical `../BUG_TRACKER.md` retains 117 unique findings: 86 Fixed, 18 Partial, 1 Open, 12 Accepted. PR #292/#295 strengthen S06-H4 durable SecurePrefs commit/read-back and S06-L4 WorkManager runtime testing; PR #297 adds S11-M1 foreground lifecycle runtime and call/chat ordering coverage. These do not replace physical-device, staging or provider acceptance. S08-H2 remains intentionally deferred under the all-screen screenshot-allowed product direction. PR #293 is a stale documentation-only PR superseded by the current-main closeout; PR #303 is the active S06-H6 PIN durability PR. No open GitHub issues were reported at the 2026-11-14 check. `FINAL_SIGNOFF.md` remains PENDING.

---

## Current state — 2026-11-09 (post-PR #290 merged)

```
LATEST SOURCE-CHANGING MERGE: PR #290 — 9b1c1dd48fb584d728491e8bfeee5ef380e9b834
LATEST VERIFIED APP-CODE BASELINE: PR #290; exact-head CI 10/10 and Security 3/3 passed
MERGED: PR #290 (S06-H5 all primary-PIN collision paths; CI 10/10, Security 3/3, Espresso 4, API 35 1, debug APK and R8 passed); PR #289 (S10-N2 same-owner method-reference callback taint; CI 10/10, Security 3/3); PR #288 (S02-I2 production fail-closed; CI 10/10, Security 3/3, Firestore Rules/server transactions passed); PR #287 (docs-only S10-N2 closeout); PR #286 (S10-N2 generic/reflection/lambda-callback fail-closed; CI 10/10, Security 3/3); PR #285 (docs-only closeout); PR #284 (S10-N2 inherited app-owned member/receiver resolution; CI 10/10, Security 3/3); PR #283/#282 (docs-only); PR #281 (S10-N2 chained app-owned receiver resolution; CI 10/10, Security 3/3); PRs #275-#280 and earlier remediations listed above.
PR #267 head: 213442825113013a85650de9ac24edf0e9ec9211
CI: 42443323887 — passed (Android unit, lint, debug APK, Espresso, API 35 predictive-back, R8, Server + Worker).
SECURITY: 42443323988 — passed (CodeQL, Gitleaks, SBOM).
FIRESTORE RULES: 42443323886 — passed.
FIRESTORE RULES + SERVER TRANSACTIONS: 42443323962 — passed (200 Rules tests + 6 server transaction tests).
OPEN PRS: none; OPEN ISSUES: none (rechecked 2026-11-09).
PR #266: intentionally closed unmerged; its documentation changes were not applied to main.
TRACKER: 116 findings — 86 Fixed, 17 Partial, 1 Open, 12 Accepted. S02-I2 code-side downgrade fixed by PR #288; live Worker/Render configuration read-back remains. S10-N2 method-reference code fix merged in PR #289; whole-program/device residuals remain. S06-H5 primary-PIN candidate throttle now covers Settings, Setup and forced rotation in merged PR #290; exact-head CI 10/10 and Security 3/3 passed. Independent side-channel and device-acceptance residuals remain.
S10-N2: remains Partial. PR #286's validated baseline covers unresolved generic returns/receivers, recognized reflection/class-loading surfaces and opaque lambda callback parameters. PR #289 adds same-owner method-reference callback target taint (`this::method`, `Owner::method`, `Owner.this::method`) with a synthetic `callbackArg` → `Log.w` regression; CI 10/10 and Security 3/3 passed. Remaining: external/library dispatch, unrecognized reflection/dynamic APIs, other method-reference receiver/target shapes, native behavior, arbitrary generic/container semantics, whole-program symbol resolution and feature-complete physical release/minified logcat acceptance.
NEXT: finish and independently review the S06-H6 primary/device PIN durability remediation on branch `pin-persistence-fix-2026-11-14`; run exact-head CI/Security, record evidence in BUG_TRACKER.md and FINAL_SIGNOFF.md, and keep device/Keystore acceptance separate. S06-H6 is Partial until automated verification passes.
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


## Current repository closeout — 2026-11-14

Current main is `4c4a4a8d0055277eaf1f7b6ba601b507225f1428` after documentation-only PR #300; latest source-changing merge is PR #299. PR #300 exact-head CI [43469709473](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43469709473) and Security [43469709660](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43469709660) passed 10/10 and 3/3. Post-PR #300 main CI [43536557072](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43536557072) and Security [43536557050](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/43536557050) were running at this checkpoint. The stale SC-03 eradication plan is superseded: current dependency verification has 493 components, includes the Linux aapt2 checksum and is exercised by current Gradle CI. README release instructions now correctly state that signed releases trigger on immutable `v*` tags or manual dispatch, not every push to main.

Remaining work is organized in `RELEASE_READINESS_2026-11-12.md`. Keep provider/secret rotation, Firebase/Firestore live configuration, Render/Worker pairing, staging acceptance, SC-12 governance, owner decisions and feature-complete physical-device acceptance separate from repository code changes. Do not mark `FINAL_SIGNOFF.md` complete until their evidence exists or a residual risk is formally approved.

## S06-H6 primary/device PIN durability — active code review (2026-11-14)

Source review found that PinManager.setPin() previously acknowledged an asynchronous account-hash write, ignored the return state of the device-PIN synchronization setter, and promoteDevicePinToCurrentUser() returned success after an asynchronous account write. DevicePinGateActivity advanced after calling the device-PIN setter without checking persistence. This is tracked separately as S06-H6; the existing S06-H4 duress-arm finding and S06-H5 candidate-throttle finding are not reclassified.

Working branch: pin-persistence-fix-2026-11-14 (PR not opened at this documentation checkpoint). The change introduces durable-tier checks, synchronous commits and exact read-back for single-store writes; an account/device journal with roll-forward recovery for paired PIN changes; independently salted primary/device records; verified promotion and legacy cleanup; and managed background work for device-gate setup/verification. New pure persistence tests and SecurityFindingWiringTest cover commit/read-back failures, failed staging, process-restart recovery, one-sided state, metadata cleanup and UI failure handling.

Status: implementation and regression coverage authored; GitHub CI/Security still pending. Do not mark S06-H6 Fixed until exact-head workflows pass. Real Android Keystore/EncryptedSharedPreferences and feature-complete physical-device acceptance remain separate; FINAL_SIGNOFF.md remains PENDING.

## Merged S06-H5 durable throttle — PR #299 (2026-11-13)

The PIN candidate budget previously accepted a successful in-memory `commit()` from
`SecurePrefs.SecurityTier.NONE`, allowing the attempt budget to disappear at process
death. PR #299 merged the resolved-tier check into `PinChangeThrottle`. The helper
denies attempts unless the tier is durable; pure and wiring regressions cover the guard.
See `sessions/SESSION-S06-H5-DURABLE-THROTTLE-2026-11-13.md`. This is a mitigation
within an existing Partial finding, not a new finding or a production sign-off.


## Post-PR #300 current-main checkpoint — 2026-11-14

PR #300 merged documentation-only handoff corrections as `4c4a4a8d0055277eaf1f7b6ba601b507225f1428`. Its exact-head
CI and Security passed 10/10 and 3/3. Main workflows triggered by this documentation
merge are tracked in BUG_TRACKER and RELEASE_READINESS; their then-current state was
in progress when this note was written. The last code-changing merge remains PR #299.
