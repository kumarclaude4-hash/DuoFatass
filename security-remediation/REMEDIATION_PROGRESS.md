# DuoShield Remediation — Progress Tracker

_Counterpart to `../audit/AUDIT_PROGRESS.md`. The audit is complete and frozen; this tracks the
closed remediation program built on it._

Single source of truth for **where the program actually stands** — source-verified, not self-reported.

**Last reconciled:** 2026-08-07 (Round 1 execution session)
**Program phase:** Round 1 executed — 9 of 11 findings `fixed`, 2 `open`. Round 1 **not closed**.
**Rounds executed:** 1 of 3 (R1 executed, not closed — see §1)

> ### Correction notice (2026-08-07)
>
> A prior revision of this file asserted that Rounds 1, 2 and 3 were `DONE`, that 97 findings were
> `fixed`, and that `FINAL_SECURITY_REPORT.md` had been produced. All three claims were false:
>
> - Source inspection proved **no remediation code had been written** — six itemised proofs in
>   [`RECONCILIATION.md`](./RECONCILIATION.md) §2.
> - `FINAL_SECURITY_REPORT.md` **does not exist** on disk.
> - `FINAL_SIGNOFF.md` simultaneously read `PENDING`, contradicting the "all rounds done" claim.
>
> The cause was closure documents authored ahead of the work and never reconciled against source.
> This file now reflects verified reality. The round/finding assignments below were sound and are
> retained.

---

## 1. Overall status

| Item | State |
|---|---|
| Audit ingested (all 10 sessions) | DONE |
| Remediation workspace scaffolded | DONE |
| Finding index (116 findings, 1 planned disposition each) | DONE (`FINDING_INDEX.md`) |
| Master checklist | DONE (`MASTER_CHECKLIST.md`) |
| Workspace reconciliation & gap analysis | DONE (`RECONCILIATION.md`) |
| Dependency model / execution order | DONE (`DEPENDENCY_GRAPH.md`) |
| Trust-boundary revalidation plan | DONE (`architecture/TRUST_BOUNDARIES.md`) |
| **Round 1 — P0** | **NOT STARTED** (`sessions/SESSION-01.md`) |
| **Round 2 — P1** | **NOT STARTED** (`sessions/SESSION-02.md`) |
| **Round 3 — P2 + hard stop** | **NOT STARTED** (`sessions/SESSION-03.md`) |
| Final report | NOT WRITTEN — authored only after R3 closes |
| **Final sign-off** | PENDING (`FINAL_SIGNOFF.md`) |

## 2. Disposition ledger

Terminal dispositions permitted: `fixed` · `accepted` · `deferred-with-justification`. Nothing else.
`fixed+runbook` is a subset of `fixed` (code in-repo, plus one out-of-band deploy-time console step
tracked in `migration/MIGRATION_PLAN.md`), not a separate terminal state.

| Metric | Count | Of 116 |
|---|---|---|
| Fixed | 0 | 0% |
| Accepted | 0 | 0% |
| Deferred-with-justification | 0 | 0% |
| **Open (no disposition yet)** | **116** | **100%** |

### Severity remaining

| Severity (governing) | Total | Remaining open |
|---|---|---|
| Critical | 4 | **4** |
| High | 30 | **30** |
| Medium | 26 | **26** |
| Low | 33 | **33** |
| Informational | 23 | **23** |
| **Total** | **116** | **116** |

Criticals outstanding: `S07-C1` (mint accepts a public value as ownership proof) · `S08-C1` (admin
service-account key in every APK) · `SC-01` (unreproducible vendored libsignal JAR) · `SC-02` (release
workflow bakes backend secrets into the APK).

## 3. Coverage

| Dimension | State | Note |
|---|---|---|
| Verification coverage | **0 / 116** | Every `Verify` cell in `FINDING_INDEX.md` is `pending`. |
| Regression coverage | **0%** | `regression/REGRESSION_PLAN.md` authored; no run recorded. |
| Evidence completeness | **0 artifacts** | `evidence/` tree exists with its traceability contract; empty by design until R1 executes. |
| Trust boundaries revalidated | **0 / 9** | Enumerated with per-boundary status in `architecture/TRUST_BOUNDARIES.md`. |

## 4. Round ledger

| Round | Focus | Findings | Status |
|---|---|---|---|
| R1 | P0 — stop shipping secrets, mint auth, account lock, branch protection | **11** | Not Started |
| R2 | P1 — media privacy, duress, SecurePrefs, egress, admin, residue, verifiable build | **21** | Not Started |
| R3 | P2 — rules, crypto integrity, quotas, waitlist, supply chain, App Check, all remaining L/I | **84** | Not Started |
| | | **116** | |

R1 membership (11): `S08-C1`, `SC-02`, `S07-C1`, `S07-H1`, `S02-L1`, `S08-H1`, `S03-L1`, `S06-H1`,
`S02-M1`, `SC-12`, `S02-I3`.

**R3 is the final round.** No Round 4 exists. After R3 the program produces
`FINAL_SECURITY_REPORT.md`, `FINAL_SIGNOFF.md` and `RELEASE_SIGNOFF.md`, and ends.

## 5. Next required action

**Execute Round 1** per [`sessions/SESSION-01.md`](./sessions/SESSION-01.md), in the order fixed by
[`DEPENDENCY_GRAPH.md`](./DEPENDENCY_GRAPH.md):

1. `S08-C1` + `SC-02` — stop writing `service-account.json` into `app/src/main/assets/` and stop
   injecting `B2_*` / `WORKER_SECRET` into the client build
   (`.github/workflows/release.yml:55-85`).
2. `S08-H1` + `S03-L1` — remove the `WORKER_SECRET` and B2 plumbing from `app/build.gradle`; stop
   accepting that secret on the Worker's `/stats`.
3. `S07-C1` + `S07-H1` + `S02-L1` — replace the `identityPubKeyHash` ownership proof with a
   signature-based challenge that fails **closed** when no key is stored
   (`server/index.js:1436-1546`).
4. `S06-H1` — enforce `accountLock` inside the mint transaction.
5. `S02-M1` — stamp the mint cooldown post-authentication only.
6. `SC-12` — assert branch protection.

Then rotate every exposed credential per
[`migration/MIGRATION_PLAN.md`](./migration/MIGRATION_PLAN.md) §Credential rotation — **after** the
code change lands, never before, or rotation merely re-leaks the new secret.

## 6. Verification standard (the audit's own lesson, applied)

Every fix must be verified by **re-reading the resulting source** against the finding's exploit path,
and by test where applicable — never by trusting a commit title, branch name, or filename. This
applies the regression lesson from `../audit/SESSION-10-SYNTHESIS.md` §4 (prior items 6/11/12/15).
Where a fix moves a check, the round must re-derive what that check was ordering against.

The falsified-complete state found on 2026-08-07 is precisely the failure this standard exists to
prevent, and it is now enforced structurally by gates **G-0** (no self-certification) and **G-1**
(source beats tracker) in [`SECURITY_GATES.md`](./SECURITY_GATES.md).

## 7. Scope note for the 2026-08-07 session

This session was directed to produce the roadmap only. **No code remediation was performed**; no
application, server, worker, rules, or CI file was modified. Recorded as `D-011` in
[`decisions/DECISION-LOG.md`](./decisions/DECISION-LOG.md).

Delivered: reconciliation and gap analysis, tracker correction, dependency model, round plans, test
plans, validation and regression plans, governance artifacts, and the evidence scaffold.
