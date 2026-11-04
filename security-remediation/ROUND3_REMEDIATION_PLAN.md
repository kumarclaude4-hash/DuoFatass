# Round 3 Remediation Plan — 20 scheduled sessions

Status: **SCHEDULED WORK EXECUTED AT SOURCE/DOCUMENTATION LEVEL; sign-off gates remain.**
This plan was drafted 2026-08-11 as intent. All 20 scheduled sessions have since been touched
and S3-20 is complete. This is not a claim that every planned verification passed. Current-state
addendum (2026-11-04): S3-15b passed 186/186 Firestore emulator tests, and S3-19b is **verified on
main CI** by run 41863879619 at `58b29f16b0c368cb9dbd71512e98317022db4781` (lint, Android unit
tests, debug APK, Espresso, wrapper validation, and libsignal integrity). Older statements below
that call S3-19b outstanding are historical session context, not the current gate state. This
document creates **no** `fixed` disposition; per `SESSION_PROTOCOL.md` §0–§1, a row moves to
`fixed` only in its own session, only after the verification named here actually runs, and only
recorded in **[`../BUG_TRACKER.md`](../BUG_TRACKER.md)** (the successor to the deleted
`FINDING_INDEX.md`/`MASTER_CHECKLIST.md`/`REMEDIATION_PROGRESS.md`/`RISK_REGISTER.md`).

> **2026-08-11 re-verification note:** a fresh source pass for the tracker consolidation found some of
> this plan's "open" assumptions were already stale — e.g. `S08-H1` (WORKER_SECRET in the APK) is now
> fixed in source even though it wasn't yet reflected here. Before starting any session below, check
> that finding's row in `../BUG_TRACKER.md` first; some of the 20 sessions may already be partially or
> fully satisfied.

## Scope

- **Historical planning baseline:** 116 findings total. **13** were already closed and were **out of scope** here (do not re-open or
  re-litigate unless current source falsifies them): `S03-H1`, `S04-H1`, `S04-H2`, `S04-H3`,
  `S05-H1`, `S05-H3`, `S05-I1`, `S06-H2`, `S06-H3`, `S06-I2`, `S07-C1`, `S08-H4` (fixed) and
  `S01-L1` (partial — finish item, scheduled in S3-05).
- The original planning estimate of **103 open** findings is historical, not the current register state. Use the latest row dispositions in [`../BUG_TRACKER.md`](../BUG_TRACKER.md); the 2026-11-04 direct row recount reports **116 total: 74 Fixed, 29 Partial, 1 Open, 12 Accepted**. Recount again before sign-off if any row changes.
- Each session below is one unit of work = **fix + verify + document** exactly as the protocol
  defines: land the change, run the verification for that session's lane, then update the row's
  status in `../BUG_TRACKER.md` and write the session file under `sessions/`.

## Standing invariants (apply to every session)

1. **Never make `/mintToken` auth fields optional.** `nonce` and `signatureHex` stay mandatory
   (`server/index.js` ~`:1796`); a legacy client gets 400, never a bypass. Making them optional
   restores the `S07-C1` takeover.
2. **Source beats tracker.** If source already satisfies a row (as happened with `S06-H2`/`I2`),
   record `fixed` with source evidence and move on. If source contradicts a "fixed" claim, it is not
   fixed.
3. **Add, don't replace.** Keep existing guards (e.g. the `S07-H1` fail-closed hash branch) and layer
   new ones beside them.
4. **No unverifiable `fixed`.** A row whose only verification is BLOCKED (no toolchain) lands as
   `partial — <lane> verification BLOCKED` with source review recorded, never as `fixed`. It is
   promoted to `fixed` in the catch-up session when the toolchain exists.
5. **Server + Android ship together.** Any session that changes a server contract an APK depends on
   must note the co-release requirement.

## Verification lanes and their current availability

| Lane | Command | Available now? |
|---|---|---|
| **SRV** — `server/` | `cd server && npm test` | **Yes** |
| **WORKER** — `worker/` | `node --check` + unit tests; `wrangler` dry-run | **Yes** (Node); wrangler deploy = operator |
| **RULES** — `firestore.rules` | `firestore-tests` on the emulator | **DONE for S3-15b** — 186/186 passed |
| **AND** — Android | `./gradlew :app:assembleDebug` + unit tests | **DONE for S3-19b** — main CI run 41863879619 passed lint, unit tests, debug APK, and Espresso at `58b29f16b0c368cb9dbd71512e98317022db4781` |
| **CI** — workflows/supply chain | YAML lint, `git`/`gh` inspection, hash recompute | **Yes** (config); actual CI run = operator |

RULES and AND sessions produced source-verified changes. S3-15b subsequently ran and passed 186/186.
S3-19b is now verified on main CI by run 41863879619 at
`58b29f16b0c368cb9dbd71512e98317022db4781`; PR #200's separate Espresso workflow was not the basis
for that promotion.

---

## The 20 sessions

Ordering rule: P0/operator-paired Criticals first, then supply-chain (protects everything
downstream), then server (fully verifiable here), then rules, then worker, then Android (verification
BLOCKED, so batched last), then the accepted-disposition + reconciliation close-out.

### S3-01 — Get secrets out of the APK and CI  · lane CI/build · **P0**
Findings: `S08-C1` (Critical, Firebase SA key in APK), `SC-02` (Critical, full secret set baked into
release), `S08-H1` (WORKER_SECRET in BuildConfig + Worker still accepts it on `/stats`), `S03-L1`
(dup of S08-H1), `SC-12` (branch protection).
Code: strip secret injection from `.github/workflows/release.yml`, `build-release.sh`,
`build-apks.sh`, `app/build.gradle`; remove `WORKER_SECRET` acceptance path in `worker/src/index.js`.
Runbook (operator, tracked as `fixed+runbook`): **revoke the leaked GCP service-account key**, rotate
`WORKER_SECRET` + all baked creds, **enable `SC-12` branch protection** on `main`.
Exit: `grep` proves no secret reaches `BuildConfig`/APK; workflow YAML lints; runbook items checked
off from live evidence (GCP console, `gh api …/branches/main/protection`).

### S3-02 — Supply-chain integrity, release provenance  · lane CI
Findings: `SC-01` (Critical, vendored `libsignal` JAR not reproducible / unhashed), `SC-04`
(checksums + signature record + provenance), `SC-05` (workflow deletes all prior releases/tags).
Code: recompute + record JAR hash and gate it in CI from `scripts/strip_signal_records.py`; add
SHA256SUMS + provenance to release job; remove the destructive delete-all step.
Exit: JAR hash reproducible locally; release workflow emits checksums; no delete-all path remains.

### S3-03 — Dependency pinning & scanning  · lane CI
Findings: `SC-03` (Gradle dependency verification), `SC-06` (JitPack scoped `includeGroup`), `SC-07`
(wrapper JAR validation), `SC-08` (actions pinned to SHA), `SC-09` (SBOM + Dependabot + SAST/secret
scan), `SC-10` (`firestore.yml` pinned install, drop `audit=false`).
Code: `gradle/verification-metadata.xml` scaffold + CI wiring, scoped repositories, `gradle-wrapper`
validation action, SHA-pinned actions across all workflows, new scan workflow + `dependabot.yml`.
Exit: CI config valid; metadata scaffold present (operator generates full hashes — runbook half).

### S3-04 — Firestore rules: cross-user write protection  · lane RULES (verify BLOCKED → S3-15b)
Findings: `S01-H1` (prekey overwrite), `S01-H2` (1:1 message content rewrite), `S01-H3`
(`partnerName_<uid>` overwrite).
Code: value-scoped `update` rules that pin immutable fields and re-assert `isEncrypted`, replacing
keys-only `hasOnly`.
Exit: rules tests written in `firestore-tests/`; **source-reviewed now**, emulator run deferred to
S3-15b. Lands as `partial — RULES verification BLOCKED` until then.

### S3-05 — Firestore rules: field validation & abuse caps  · lane RULES (verify BLOCKED → S3-15b)
Findings: `S01-M1` (group membership TOCTOU + volume cap — accept TOCTOU, fix cap), `S01-M2`
(`identities` field allow-list), `S01-M3` (`backup_logs` bound/validate), `S01-M4` (group delete
re-check membership), `S01-L1` (**finish**: shape validation + ID-squatting namespacing), `S01-L2`
(`users` doc shape).
Exit: rules tests written; source-reviewed; emulator run deferred to S3-15b.

### S3-06 — Server auth & identity  · lane SRV · (co-release with APK)
Findings: `S02-H1` (`migrateUid` verbatim copy → field allow-list on migration), `S02-M1` (mint
cooldown stamped pre-auth → move after auth), `S02-L1`/`S07-H1` (existing-account hash fail-open →
fail-closed), `S02-L2` (`createChat` display-name bound/sanitize).
Guard: do **not** touch the mandatory `nonce`/`signatureHex` gate.
Exit: `cd server && npm test` green with new cases per finding (fail-open, pre-auth cooldown,
migration field injection); `node --check` clean; wiring grep-confirmed live.

### S3-07 — Server limits, memory growth, IP keying  · lane SRV
Findings: `S02-L3` (`mintCooldown` purge), `S02-L4`/`S04-L1` (byte-count body cap + `setEncoding`),
`S04-L3` (limiter purge; durable store accepted-to-ops), `S04-M1` (IPv6 /64 keying for all IP-keyed
limits incl. admin lockout), `S04-M3` (XFF trust configurable).
Exit: unit tests for byte-cap bypass, IPv6 /64 collapse, purge; suite green.

### S3-08 — Server egress, TURN, public endpoints  · lane SRV
Findings: `S04-M2` (TURN cred TTL + aggregate cap + outbound timeout), `S04-I1` (`/status` + `/`
auth / drop counters), `S04-I3` (preview provenance to client), `S04-L2`/`S06-L2` (`/duress-lock`
authenticated + rate-limited).
Exit: suite green with new endpoint tests.

### S3-09 — Duress server enforcement  · lane SRV
Findings: `S06-H1` (server-side `accountLock` enforcement — the restore gate is currently
client-side post-auth), `S06-M2` (`_duressNonces` per-uid single nonce + drop-path delete; TTL =
runbook), `S06-M3` (stop logging raw uids on both duress endpoints), `S06-L1` (nonce-expiry
fail-closed on malformed `expiresAt`), `S06-I1` (correct contradictory rules comment).
Exit: suite green; tests assert locked account cannot restore, malformed expiry denies.

### S3-10 — Duress eligibility & rules coverage  · lane SRV + RULES (rules verify → S3-15b)
Findings: `S05-M2` + `S06-M1` (`duressEligibility` actually enforced server-side + rules, not a
cached client bool), `S06-L3` (`_duressNonces` rules-test coverage).
Exit: server tests green; rules tests written (emulator deferred to S3-15b).

### S3-11 — Worker abuse controls (per-user)  · lane WORKER
Findings: `S03-H2` (per-user, not per-token, rate bucket), `S03-H3` (per-user storage quota),
`S03-I2` (per-user budget accounting), `S03-L4` (CORS headers on rate/quota rejections), `S08-I3`
(drop `ACAO: *` while allowing `Authorization`).
Exit: `worker` unit tests for per-user exhaustion + CORS on 429; `node --check` clean.

### S3-12 — Worker media object hardening  · lane WORKER + SRV
Findings: `S03-M1` (`nosniff` + `Content-Disposition` + validated `Content-Type`), `S03-M2`
(uploader-bound, not just scope-bound, tokens), `S03-M3` (cut token TTL + bound reuse), `S03-L2`
(guard `decodeURIComponent`), `S03-L3`/`S04-I2` (remove dead B2 presign surface; **revoke B2 key** =
runbook), `S10-N3` (cold-tier delete/migration race).
Exit: worker + server tests green; dead B2 path gone by grep.

### S3-13 — Admin surface, part 1 (lifecycle & PII)  · lane SRV
Findings: `S05-H2` (waitlist deny/expire/revoke path), `S05-M1` (stop persisting raw operator IPs +
uids forever / to stdout), `S05-M3` (admin session absolute lifetime + binding + bulk revoke;
authenticated refresh).
Exit: suite green with session-lifetime and deny-path tests.

### S3-14 — Admin surface, part 2 (input & headers)  · lane SRV
Findings: `S05-L1` (`validAdminUid` on account lookup), `S05-L2` (`collectBody` after
`requireAdminAuth`), `S05-L3` (`Cache-Control: no-store` on admin responses), `S05-L4` (approve/
unfreeze TOCTOU + bounded `.get()`), `S05-I2` (remove stale admin comments), `S05-I3` (cookie
`Secure` server-set + close `safeTokenEqual` length oracle).
Exit: suite green.

### S3-15 — App Check + client provider wiring  · lane AND/RULES (verify BLOCKED → S3-15b/S3-19b)
Findings: `S10-N1` (Firebase App Check provider wiring in client + rules enforcement scaffold;
enforcement enable = runbook; sideloaded-APK caveat = accepted).
Exit: client wiring + rules scaffold source-reviewed; enable step is operator.

### S3-15b — RULES catch-up verification gate  · lane RULES · **DONE**
Not new fixes: run `firestore-tests` on the emulator for every rule changed in S3-04, S3-05, S3-10,
S3-15 (and the pre-existing `S03-H1`/`S01-L1` regression tests never yet executed). Promote each
`partial — RULES verification BLOCKED` row to `fixed` (`verified-source+test`) or, on failure, to
`deferred-with-justification`. **Do not sign off rules work before this runs.**

**Ran this session:** installed Java 21 (Amazon Corretto) + `firebase-tools@15.26.0`, then ran
`firebase emulators:exec --only firestore --project duoshield-test "npm test"` against
`firestore-tests/` exactly as `.github/workflows/firestore-rules-test.yml` does. Result:
**186/186 tests passed, 0 failures.** Promoted `S01-H1`, `S01-H2`, `S01-H3` (S3-04), `S01-M1`,
`S01-M2`, `S01-M3`, `S01-M4`, `S01-L1`, `S01-L2` (S3-05) from `partial` to `fixed`
(`verified-source+test`) in `../BUG_TRACKER.md`. `S06-L3` (S3-10) stays `fixed` but its
never-executed caveat is closed. `S10-N1` (S3-15) and `S07-H3` (S3-17) stay `partial` — both still
have an open AND-lane half pending `S3-19b`, and `S07-H3`'s rules-side fix (binding sender/group at
the rules layer) was never implemented in code, so there is nothing rules-side for the emulator to
promote for that finding specifically. No failures occurred, so nothing routed to
`deferred-with-justification`.

### S3-16 — Android crypto storage  · lane AND (verify BLOCKED → S3-19b)
Findings: `S08-H5`/`S07-M1` (SecurePrefs plaintext fallback holding identity key + backup key +
SQLCipher passphrase; `isInitialized()` must not ignore fallback), `S07-L2` (static derivation cache
surviving duress wipe), `S07-L3` (`mnemonicToSeed` canonicalization + `Locale.ROOT`).
Exit: source-reviewed; lands `partial — AND verification BLOCKED` pending S3-19b.

### S3-17 — Android backup & group crypto  · lane AND + RULES (verify BLOCKED)
Findings: `S07-H2` (unkeyed SHA-256 plaintext oracle in backup docs → keyed/removed), `S07-H3`
(group message AAD, not rules-only attribution), `S07-M2` (trust keyed on immutable identity, not
mutable Firebase uid), `S07-M3` (bring backup metadata inside the AEAD), `S07-L1` (creator check
fail-closed on null `creatorUid`), `S07-L4`/`S10-N2` (session deser no silent fresh-session
substitute; stop logging peer uid to release logcat).
Exit: source-reviewed; AND rows pending S3-19b, the `S07-H3` rules half pending S3-15b.

### S3-18 — Android platform privacy  · lane AND (verify BLOCKED → S3-19b)
Findings: `S08-H2` (stop clearing `FLAG_SECURE` app-wide), `S08-H3` (disable/scrub 150 MB plaintext
Glide disk cache + sweep 4 temp prefixes), `S08-L4` (exclude lock screen + rendered activity from
recents).
Exit: source-reviewed; pending S3-19b.

### S3-19 — Android surface hardening  · lane AND (verify BLOCKED → S3-19b)
Findings: `S08-M1` (re-enable heap pointer tagging), `S08-M2` (scope `file_paths.xml`, drop unused
external roots), `S08-L1` (validate deep-link Account ID), `S08-L2` (`EXTRA_IS_SENSITIVE` on
clipboard), `S08-L3` (don't store PIN length beside hash), `S08-I1` (stop R8 keeping
`crypto.**`/`security.**` names), `S06-L4` (`AccountLockWorker` report real failure + cap 5xx
retries).
Exit: source-reviewed; pending S3-19b.

### S3-19b — ANDROID catch-up verification gate  · lane AND · **DONE on main CI**
Not new fixes: `./gradlew :app:assembleDebug` + unit tests for every Android change in S3-15/16/17/
18/19 **and** the standing `S07-C1` Android-compile gate (`AuthTokenHelper.java`). Main CI run
[41863879619](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41863879619) at
`58b29f16b0c368cb9dbd71512e98317022db4781` passed lint, Android unit tests, debug APK build, and
emulator-backed Espresso; Security scan
[41863879529](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/41863879529) also passed.
This closes the CI compile/unit-test gate only. It does **not** by itself prove device-specific
behavior, live Firebase revocation, production deployment state, or every finding-specific residual;
promote rows only when their own required behavior is covered by this evidence.

### S3-20 — Accepted dispositions + final reconciliation  · lane DOC
**Completed.** The justification for every `accepted`/`accepted+partial` row was written into `../BUG_TRACKER.md` /
`decisions/`: `S01-I1`, `S01-I2`, `S02-I1`, `S02-I3` (checkRevoked half), `S03-I1`, `S03-I3`,
`S06-I3`, `S07-I1`, `S07-I2`, `S07-I3`, `S08-M3`, `S08-I2`, `SC-11`. The S3-20 reconciliation was completed. See the current tracker addendum for the 2026-11-04 merge
batch and workflow evidence; independently recount rows before final sign-off.

---

## Sign-off gate (unchanged from FINAL_SIGNOFF.md)

`FINAL_SIGNOFF.md` remains **PENDING**. S3-15b passed (186/186 Firestore emulator tests), and
S3-19b is verified on main CI by run 41863879619 at
`58b29f16b0c368cb9dbd71512e98317022db4781`; Security scan 41863879529 also passed. Remaining gates
include S02-I3 revoked-token integration verification; S02-I2 Render/Worker shared-secret and
Durable Object verification; SC-12 branch protection, immutable tag ruleset, and protected
production environment; GCP/Worker/B2/admin credential rotation; App Check and Firestore operator
settings; trust-boundary review; and coordinated server + Android release. See FINAL_SIGNOFF.md.
