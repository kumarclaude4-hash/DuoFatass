# SESSION S3-05 — Firestore rules: field validation & abuse caps (lane RULES)

Date: 2026-08-11
Model: Opus 5 · Budget: $5 max
Findings in scope (first 3 of the S3-05 cluster, per `ROUND3_REMEDIATION_PLAN.md` documented
ordering): `S01-M1`, `S01-M2`, `S01-M3`.

> The S3-05 cluster as scheduled in the plan is 6 findings (`S01-M1, S01-M2, S01-M3, S01-M4,
> S01-L1, S01-L2`). Per `SESSION_PROTOCOL.md` §5 (one cluster/session, ≤4 tasks, cut scope not
> verification) and the standing "first 3 in documented order" rule, this session implements the
> first three (`S01-M1/M2/M3`). `S01-M4`, `S01-L1` (finish), and `S01-L2` remain for the next
> session (S3-05 continuation). The chain state reflects this.

Preamble — chain reconciliation before starting: `START_HERE.md`'s chain block still read
`NEXT SESSION: S3-04` even though S3-04 landed in commit `812813d` (+ doc commit `fd6ce2c`,
merged PR #70 `889be20`). Verified S3-04 from source (`git show --stat 812813d`; `firestore.rules`
carries the S01-H1 size-decrease branch, the S01-H2 sender-only content clause, and the S01-H3
partnerName_/partnerPhotoUrl_ block-list entries; `BUG_TRACKER.md` rows S01-H1/H2/H3 = Partial
(S3-04)). Advanced the chain to S3-05 as part of this session's checkpoint.

## 1. Inherited-state falsification (SESSION_PROTOCOL §3)

The tracker listed all three as **Open**. Each was re-confirmed against current `firestore.rules`
before any change, so a stale "Open" could not cause redundant work:

- **S01-M1** — group `messages` create rule gated only
  `member ∧ sender==auth.uid ∧ isEncrypted==true`; **no size/volume bound** on the doc. → really Open.
- **S01-M2** — `identities/{userId}` update pinned `uid` + `identityPubKeyHash` only; **no `hasOnly`**
  on the full field set, and the doc is world-readable (contact-lookup oracle). → really Open.
- **S01-M3** — `backup_logs/{logId}` create checked only `uid == request.auth.uid`; **no shape/size
  validation**. → really Open.

Legitimate write shapes were read from the client before writing any allow-list (so the fixes
cannot break real functionality):
- Group messages (`GroupChatActivity`): `{id, sender, text, isEncrypted, type, status, timestamp,
  mediaType?, path?, mediaKey?, mediaItems?, caption?}` — text-message `text` is a small ciphertext;
  media messages carry `text:""`. → a strict `hasOnly` would be brittle, so M1 uses a per-field SIZE
  cap on `text`, not a whole-doc allow-list.
- `identities` (`SeedPhraseDisplayActivity`): the only client write is `set({uid}, merge)`;
  `identityPubKeyHash` is written by the mint server (Admin SDK, bypasses rules).
- `backup_logs` (`BackupManager.logEvent`): always `{uid, event, ts, count}` (+ optional `error`);
  `event` ∈ {backup_started, backup_complete, backup_failed, backup_size_warning}.

## 2. Changes implemented (smallest necessary; add-don't-remove — invariant #3)

- **S01-M1 — `firestore.rules` group `messages` create:** added
  `(!('text' in keys()) || (text is string && text.size() <= 65536))`. The MEMBERSHIP TOCTOU half
  is **accepted** and now documented in-rule (rules `get()` against a separately-mutated members
  array cannot be made atomic; doc-count throttling is server-side/quota). Existing member/sender/
  isEncrypted guards untouched.
- **S01-M2 — `firestore.rules` `identities` create + update:** added
  `hasOnly(['uid','identityPubKeyHash','updatedAt'])` (via `keys()` on create,
  `diff(resource.data).affectedKeys()` on update). The existing `uid==userId` and
  `identityPubKeyHash` immutability pins are kept beside it.
- **S01-M3 — `firestore.rules` `backup_logs` create:** added
  `keys().hasOnly(['uid','event','ts','count','error'])` + `keys().hasAll(['uid','event','ts','count'])`
  + `event is string && event.size() <= 64` + `ts is number` + `count is number` + optional
  `error is string && error.size() <= 512`. The `uid==auth.uid` check is kept.

Tests in `firestore-tests/rules.test.js`:
- **M1:** +3 (8 KiB body succeeds; empty-text media succeeds; > 64 KiB body fails).
- **M2:** corrected the pre-existing test that asserted a benign `{label:'legacy'}` update SUCCEEDS
  (it had masked this exact finding — see audit S01-M2) to `assertFails`; +4 (uid+updatedAt re-assert
  succeeds; arbitrary `label` fails; smuggled `fcmToken` fails; create with extra field fails).
- **M3:** updated "owner can create" to the real `{uid,event,ts,count}` shape; +6 (optional-`error`
  happy path succeeds; extra field fails; missing `count` fails; over-long `event` fails; over-long
  `error` fails; wrong-typed `ts` fails).

## 3. Verification (lane RULES — BLOCKED; source review + static checks run)

- **`node --check firestore-tests/rules.test.js`** → OK (test file syntactically valid).
- **`firestore.rules` structural balance** → after stripping `//` comments and quoted string
  literals, paren/brace/bracket counts all = **0** (balanced).
- **Legitimate-shape review** → each allow-list was derived from the actual client writer
  (`SeedPhraseDisplayActivity`, `BackupManager.logEvent`, `GroupChatActivity`), so no real send/log/
  identity write is rejected.
- **BLOCKED — emulator run:** `command -v firebase` → none; `command -v java` → none; `jest` not
  installed (`firestore-tests` `npm test` → `jest: command not found`). The `@firebase/rules-unit-testing`
  emulator suite cannot execute in this environment. Per the RULES lane definition these findings
  land **Partial — RULES verification BLOCKED** and are promoted to `fixed` in **S3-15b** when an
  operator provides JVM + `firebase` CLI. No PASS is claimed for the emulator run.

## 4. Dispositions written to `../BUG_TRACKER.md`

| Finding | New disposition | Basis |
|---|---|---|
| S01-M1 | **Partial** (S3-05) | per-write body size cap added; TOCTOU accepted+documented; emulator BLOCKED → S3-15b. |
| S01-M2 | **Partial** (S3-05) | create+update field allow-list added; masking test corrected; emulator BLOCKED → S3-15b. |
| S01-M3 | **Partial** (S3-05) | schema/size pin added; happy-path test fixed to real shape; emulator BLOCKED → S3-15b. |

## 5. Out of scope / left for next session

- `S01-M4` (group message delete membership re-check), `S01-L1` (finish: shape validation +
  ID-squatting namespacing), `S01-L2` (`users` doc shape) — the remaining half of the S3-05 cluster.
- `firestore-rules.md` (narrative mirror) was deliberately **not** updated — it is a documentation
  snapshot that already diverged from `firestore.rules` (S3-04 also left it untouched), and only
  `firestore.rules` is the enforced/deployed artifact. Not touching it keeps this batch scoped.

---

```
SESSION: S3-05  MODEL: Opus 5  BUDGET: $5 max  CLUSTER: S3-05 (Firestore field validation & abuse caps — first 3 of 6)  STATUS: 3 partial (RULES emulator BLOCKED → S3-15b)
CHANGES:
  - firestore.rules: group messages create — cap text body <= 65536 bytes; accept+document membership TOCTOU (S01-M1)
  - firestore.rules: identities create+update — hasOnly(['uid','identityPubKeyHash','updatedAt']) field allow-list (S01-M2)
  - firestore.rules: backup_logs create — pin {uid,event,ts,count,error?} schema + event<=64 / error<=512 size caps (S01-M3)
  - firestore-tests/rules.test.js: corrected 2 masking happy-path tests to real shapes; +12 regression tests (M1x3, M2x4, M3x5 + M3 error happy-path)
  - BUG_TRACKER.md: S01-M1/M2/M3 -> Partial (S3-05) with this-session evidence
  - security-remediation/{START_HERE.md,SESSION_INDEX.md}: reconcile S3-04 -> done, advance chain to S3-05 remainder
VERIFICATION:
  PASS: node --check firestore-tests/rules.test.js; firestore.rules paren/brace/bracket balance = 0 (comments+strings stripped); allow-lists cross-checked against real client writers
  FAIL: none
  BLOCKED: firestore emulator run (no firebase CLI / no JVM / jest not installed) -> RULES lane, promoted in S3-15b
  NOT RUN: live emulator test execution (operator toolchain)
COMMIT: 21e1609 (firestore.rules + rules.test.js + BUG_TRACKER.md + START_HERE.md + SESSION_INDEX.md + this log); hash recorded in follow-up reconciliation commit          WORKTREE: clean
NEXT SESSION: S3-05 remainder — S01-M4 (group delete membership re-check), S01-L1 (finish: shape + ID-squatting), S01-L2 (users doc shape); lane RULES, verification BLOCKED here -> S3-15b
```
