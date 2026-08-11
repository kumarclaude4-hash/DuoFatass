# START HERE

**You said: "head to this folder, open this md, and start working." This is that md.**
Do exactly what is below, in order. Do not improvise a different plan. Do not declare anything
`fixed` without running the verification named for it.

---

## What this program is

Rounds 1–2 of the security remediation are code-complete and server-test-verified (server suite
**153/153**). **Round 3 — 103 open findings — is not implemented.** Those 103 findings are scheduled
across **20 sessions** in [`ROUND3_REMEDIATION_PLAN.md`](./ROUND3_REMEDIATION_PLAN.md). One session =
**fix + verify + document** for one scheduled batch. `FINAL_SIGNOFF.md` is **PENDING** and stays that
way until every session and both catch-up gates (S3-15b, S3-19b) have actually run.

---

## Chain state — the ONE line that says what to do next

<!-- Update ONLY this block at the end of each session. It is the single source of truth for "next". -->

```
NEXT SESSION: S3-05 remainder  (Firestore rules field validation — S01-M4 group-delete membership re-check, S01-L1 finish: shape + ID-squatting namespacing, S01-L2 users doc shape — lane RULES; verify BLOCKED here → promoted in S3-15b)
LAST DONE:    S3-05 (first 3 of 6)  PARTIAL-BATCH COMPLETE (3 partial, RULES emulator BLOCKED → S3-15b): S01-M1 group-message per-write body size cap (text ≤ 64 KiB) + membership TOCTOU accepted/documented in-rule; S01-M2 identities create+update field allow-list hasOnly(['uid','identityPubKeyHash','updatedAt']) closing the world-readable-doc injection surface; S01-M3 backup_logs schema pin {uid,event,ts,count,error?} with event ≤ 64 / error ≤ 512 caps. Corrected 2 pre-existing tests that had MASKED S01-M2/M3 (they asserted a malformed happy-path succeeds), +12 regression tests. Verified: node --check on the test file OK, firestore.rules bracket balance = 0. Emulator run BLOCKED (no firebase CLI / no JVM / jest absent) → S3-15b. Session log sessions/SESSION-S3-05.md.
              S3-04  COMPLETE (3 partial, RULES-lane verify BLOCKED → S3-15b): S01-H1 prekey consume-only size-decrease; S01-H2 sender-only message content + isEncrypted pin; S01-H3 partnerName_/partnerPhotoUrl_ block-list. Commit 812813d (impl) + fd6ce2c (tracker), merged PR #70 (889be20).
              S3-03  COMPLETE (5 fixed + SC-03 partial, CI-lane verified from source): SC-06 JitPack scoped to com.github.* via includeGroupByRegex; SC-07 validate-gradle-wrapper job gating lint; SC-08 all GitHub Actions SHA-pinned (zero @vN refs remain); SC-09 dependabot.yml + security-scan.yml (CodeQL+gitleaks+SBOM); SC-10 npm ci + firebase-tools@15.26.0 in both firestore workflows. SC-03 stays PARTIAL — verification-metadata.xml scaffold committed, component-hash population BLOCKED (needs Gradle+Android SDK+network). Commits a3106df (impl) + 289c102 (tracker + log), merged PR #69 (62b5f5d).
              S3-02  COMPLETE (all 3 findings fixed & CI-lane verified): SC-05 scoped tag-clear (commit 8508746); SC-01 reproducible/hash-gated libsignal JAR + verify-libsignal-jar CI gate; SC-04 SHA256SUMS + signing-cert digest + attest-build-provenance on release (commit dcf85c5). Session log + chain state reconciled to committed source.
              S3-01  (APK/CI secrets: S08-C1, SC-02, S08-H1, S03-L1 code-fixed & verified; SC-12 re-checked live, still open/operator) — commit 60c8cde
BLOCKED GATES PENDING: S3-15b (RULES emulator — now covers S3-04 + S3-05 rule changes), S3-19b (Android build) — need operator toolchains
OPERATOR RUNBOOK (S3-01): revoke leaked GCP SA key; rotate WORKER_SECRET + baked B2 creds; enable branch protection on main (SC-12). (S3-03) populate + enforce Gradle dependency-verification hashes (`./gradlew --write-verification-metadata sha256 help`, then flip `<verify-signatures>true`) once Android SDK + network are available.
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
