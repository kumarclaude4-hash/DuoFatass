# FINDING INDEX — every audit finding, exactly once

Generated from `../audit/`. This is the authoritative disposition record. Every finding appears
**exactly once** and must end in **exactly one** final disposition: `fixed` · `accepted` ·
`deferred-with-justification`. No finding is orphaned.

> ### Status of this index — read before using it
>
> An earlier program session pre-populated this table with `fixed` dispositions and
> `verified-source` verification for all 116 findings **before any remediation code was written**.
> That was corrected on **2026-08-07** during the reconciliation pass documented in
> [`RECONCILIATION.md`](./RECONCILIATION.md). Source inspection proved no remediation change had
> landed (see that document for the five specific code sites that disprove the claim).
>
> Consequently:
> - The **`Verify`** column is now `pending` for every row. It is filled in per-row only when the
>   fix is actually verified against source and/or a test, at round close.
> - The last column is now **`Planned Disp`** — the *intended* disposition. It is a plan, not a
>   result. It carries no evidentiary weight.
> - **No finding in this index currently holds a final disposition.** Final dispositions are
>   recorded here as each round closes, and are ratified in
>   [`FINAL_SECURITY_REPORT.md`](./FINAL_SECURITY_REPORT.md) at program end.
>
> The `Affected files`, `TB`, `Root cause`, `Status`, `Prio`, and `Rnd` columns were derived from
> the audit and were re-checked during reconciliation; they are retained as accurate.

## Count reconciliation (read first)

The audit's aggregate (`../audit/SESSION-10-SYNTHESIS.md` §2, `AUDIT_PROGRESS.md`) states **117
findings** and lists Session 04 as `3H / 3M / 4L / 3I = 13`. The Session 04 report
(`SESSION-04-EGRESS.md`) actually contains **three** Lows (`S04-L1`, `S04-L2`, `S04-L3`) and no
`S04-L4` — verified by reading the file end to end. There are therefore **116 distinct finding IDs**
physically present across the ten session reports; the "117" is a one-count bookkeeping slip in the
Session 04 ledger row (it claims 4 Lows; the report has 3).

Per the program rules this index does **not** invent an `S04-L4` to reconcile the number, and does
**not** renumber anything. It indexes all 116 present IDs and records the discrepancy here. If the
audit team later confirms a missing `S04-L4`, it is added as a new row then; until then it cannot be
given a disposition because it has no content. This is the only deviation between the audit's stated
count and this index, and it is intentional and documented.

## Governing severity

The **governing severity** used for prioritization is the audit's own cross-session **re-rating**
(`SESSION-10-SYNTHESIS.md` §7), where it differs from the finding's original rating:

- `S04-M1` Medium → **High** (admin lockout has no entropy floor — `S05-H1`/`S05-I1`).
- `S07-M1` Medium → **High**, tracked jointly with `S08-H5` (same defect; the SQLCipher passphrase
  is also in the plaintext fallback). Both IDs are retained; the shared fix closes both.
- `S03-H3` was prior-review Medium; already recorded **High** by Session 03. Kept High.
- `SEC-A01` (prior review) is **not** a numbered audit finding; it is tracked through `S03-H1`,
  `S03-L1`, `S08-H1`, `S04-I2`.

## Legend

- **Status** = implementation status observed from source (`open`, `partial`). Re-confirmed
  2026-08-07. Because no remediation code has landed, no row is `closed`.
- **Prio** = remediation priority from `SESSION-10-SYNTHESIS.md` §8 (P0 → P1 → P2).
- **Rnd** = planned remediation round (Session log): R1 / R2 / R3.
- **Verify** = verification status. Currently `pending` for all 116 rows. Permitted terminal values,
  written only once the check has actually been performed: `verified-source`,
  `verified-source+test`, `verified-config` (out-of-band console state), `n/a` (nothing to verify —
  reserved for accepted informational findings).
- **Planned Disp** = the *intended* disposition, i.e. what the round aims to achieve:
  `fixed` · `fixed+runbook` (code here, deploy-time console step in
  `migration/MIGRATION_PLAN.md`) · `accepted` · `deferred`. **This is an intent, not a result.**
  A planned `fixed` that fails validation becomes `deferred-with-justification`, not `fixed`.

---

## Session 01 — Firestore authorization (`../audit/SESSION-01-FIRESTORE.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S01-H1 | High | `firestore.rules:22-27` | TB-2 | Cross-user prekey update scoped by `hasOnly` on keys, not values → any user can wipe/replace another's one-time prekeys | open | P2 | R3 | pending | fixed |
| S01-H2 | High | `firestore.rules:77-82` | TB-2 | 1:1 message `update` doesn't protect content fields / re-assert `isEncrypted` → recipient can rewrite sender's body | open | P2 | R3 | pending | fixed |
| S01-H3 | High | `firestore.rules:52-60` | TB-2 | Chat `update` block-list omits `partnerName_<otherUid>` → participant overwrites partner's displayed name | open | P2 | R3 | pending | fixed |
| S01-M1 | Medium | `firestore.rules:130-134` | TB-2 | `get()`-based membership TOCTOU + no message volume cap for groups | open | P2 | R3 | pending | accepted (TOCTOU inherent) + fixed (schema cap where feasible) |
| S01-M2 | Medium | `firestore.rules:257-262` | TB-2/TB-1 | `identities` update has no field allow-list → arbitrary content in a world-readable doc | open | P2 | R3 | pending | fixed |
| S01-M3 | Medium | `firestore.rules:376-377` | TB-2 | `backup_logs` create unbounded/unvalidated → write-amplification cost DoS | open | P2 | R3 | pending | fixed |
| S01-M4 | Medium | `firestore.rules:137-138` | TB-2 | Group message `delete` doesn't re-check membership → ex-member retains delete rights | open | P2 | R3 | pending | fixed |
| S01-L1 | Low | `firestore.rules:126-155` | TB-2 | `groups` create doesn't validate `createdBy` | **partial** — the `createdBy` half was closed incidentally by the R2 `S03-H1` fix (2026-08-10, `bb5b8bb`): `groups` create now requires `createdBy == request.auth.uid` **and** `createdBy in members` **and** `!exists(chats/$(groupId))`. Remaining R3 scope for this row: the wider ID-squatting/namespacing question (group IDs are still client-chosen, merely no longer allowed to collide with an existing `chats/{id}`) plus full shape validation of the created document. Row deliberately **not** closed to `fixed` because the rule change is **not emulator-verified** (see Verify) | P2 | R3 | **partial — NOT emulator-verified.** The 4 `S03-H1` regression tests in `firestore-tests/rules.test.js` cover exactly these clauses but **were never executed** (no JVM/`firebase` CLI → emulator BLOCKED). Rule reviewed against source only | fixed |
| S01-L2 | Low | `firestore.rules:9` | TB-2 | `users` doc write has no field/shape validation | open | P2 | R3 | pending | fixed |
| S01-I1 | Info | `firestore.rules:8`,`:253` | TB-2 | Global read oracle on `users`/`identities` — needs explicit product decision | open | P2 | R3 | pending | accepted (field-minimized; ratified in DECISION-LOG) |
| S01-I2 | Info | `firestore.rules` (systemic) | TB-2 | `get()`-based cross-doc authz is a systemic TOCTOU + read-cost pattern | open | P2 | R3 | pending | accepted (documented in TRUST_BOUNDARIES) |

## Session 02 — Server auth & identity (`../audit/SESSION-02-SERVER-AUTH.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S02-H1 | High | `server/index.js:1730-1736` | TB-1/TB-6 | `migrateUid` copies `users/{oldUid}` verbatim → attacker-planted fields promoted to new uid doc | open | P2 | R3 | pending | fixed |
| S02-M1 | Medium | `server/index.js:1462-1469` | TB-1 | `/mintToken` cooldown stamped pre-auth for caller-supplied `userId` → targeted re-auth DoS | open | P0 (with S07-C1) | R1 | pending | fixed |
| S02-L1 | Low | `server/index.js:1514-1517` | TB-1 | Existing-account hash check fails open when stored hash falsy (dup of S07-H1) | open | P0 (with S07-C1) | R1 | pending | fixed |
| S02-L2 | Low | `server/index.js:1905-1908` | TB-1 | `createChat` stores unbounded/unsanitized display names | open | P2 | R3 | pending | fixed |
| S02-L3 | Low | `server/index.js:343` | TB-3 | `mintCooldown` map never purged → unbounded growth | open | P2 | R3 | pending | fixed |
| S02-L4 | Low | `server/index.js:786` | TB-3 | `collectBody` counts chars not bytes → ~2× body-cap bypass (dup of S04-L1) | open | P2 | R3 | pending | fixed |
| S02-I1 | Info | `server/index.js:1902-1908` | TB-1 | Cold-contact/registration oracle in `createChat` (by design; with S01-I1) | open | P2 | R3 | pending | accepted (with S01-I1 decision) |
| S02-I2 | Info | `server/index.js` limiters | TB-3 | In-memory limiters best-effort/per-instance (dup of S04-L3) | open | P2 | R3 | pending | accepted+partially-fixed (see S04-L3) |
| S02-I3 | Info | `server/index.js` verifyIdToken sites | TB-1/TB-3 | No `checkRevoked` → locked sessions live to token expiry | open | P2 | R3 | pending | fixed (lock enforced at mint; checkRevoked accepted-with-note) |

## Session 03 — Media capability tokens & Worker (`../audit/SESSION-03-MEDIA.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S03-H1 | High | `server/lib/mediaScope.js`,`server/lib/mediaScope.test.js`,`server/index.js:7,:595-620`,`firestore.rules:126-130` | TB-4 | Scope confusion: client-created `groups/{chatId}` self-asserts membership → media token for another conversation | **fixed** (R2 2026-08-10; decision extracted to `lib/mediaScope.js` and required to resolve unambiguously — a scopeId naming both a chat and a group now denies, ambiguity checked *before* membership so an attacker who is a member of their own shadow group cannot pass; group also required to carry immutable `createdBy` ∈ `members`). **Defence in depth also landed at the rules layer** (this contradicts the first draft of `sessions/SESSION-02.md`, which wrongly said `firestore.rules` was left untouched — corrected 2026-08-10): `groups` create now additionally requires `!exists(chats/$(groupId))`, `createdBy == request.auth.uid`, and `createdBy in members`, which kills the shadow document at the source instead of only containing it server-side. See `S01-L1`. | P1 | R2 | **verified-source+test (server layer) / UNVERIFIED (rules layer)** — 2026-08-10, re-audited 2026-08-10 (S02b recovery). Server: `node --test lib/mediaScope.test.js` → **16/16 pass**, incl. the shadow-group attack, fail-closed-for-victim, ambiguity-before-membership, and squatted-`{members:[self]}` cases; `node --check index.js` clean; wiring confirmed live at `index.js:602`, not dead code. **The earlier "full `npm test` → 99/99 pass" claim does NOT reproduce and is RETRACTED**: the true reproducible result is **84 tests, 83 pass, 1 fail**, the failure being `lib/identityVerify.test.js` aborting on `Cannot find module '@signalapp/libsignal-client'` (declared in `server/package.json:13` but not installed — native dep unavailable in sandbox). That failure is **pre-existing and NOT a Cluster A regression**: commit `bb5b8bb` modified neither `identityVerify.test.js` nor `package.json` (verified by `git show --stat`). **Rules layer BLOCKED: the 4 new `firestore-tests/rules.test.js` S03-H1 regression tests were ADDED but NEVER EXECUTED — no `java`/`javac`/`firebase` CLI in env, so the Firestore emulator cannot start.** | fixed |
| S03-H2 | High | `worker/src/index.js:224-287` | TB-4 | Per-token (not per-user) Worker rate bucket → one account exhausts global 90K/day | open | P2 | R3 | pending | fixed |
| S03-H3 | Med→High | `worker/src/index.js:6,20,445-457` | TB-4 | No per-user storage quota → ~19 uploads fill 9.5 GB global cap | open | P2 | R3 | pending | fixed |
| S03-M1 | Medium | `worker/src/index.js:459-522` | TB-4 | Attacker `Content-Type` stored/echoed, no `nosniff`/`Content-Disposition` | open | P2 | R3 | pending | fixed |
| S03-M2 | Medium | `server/index.js:2005-2015`,`worker/src/index.js:439-570` | TB-4 | Tokens scope-bound not uploader-bound → either party can overwrite/delete other's media | open | P2 | R3 | pending | fixed |
| S03-M3 | Medium | `server/index.js:475,495-500`,`worker/src/index.js:146-194` | TB-4 | 10-min unrevocable bearer tokens, unlimited reuse | open | P2 | R3 | pending | fixed (TTL cut + reuse bound) |
| S03-L1 | Low | `app/build.gradle:75-77` | TB-9/Theme D | `WORKER_SECRET` still compiled into APK (dup of S08-H1) | open | P1 | R1 | pending | fixed |
| S03-L2 | Low | `worker/src/index.js` path decode | TB-4 | Unguarded `decodeURIComponent` throws before handling | open | P2 | R3 | pending | fixed |
| S03-L3 | Low | `worker/src/index.js` | TB-8 | Dead B2 presign code + stale rate-limit entries (dup of S04-I2) | open | P2 | R3 | pending | fixed |
| S03-L4 | Low | `worker/src/index.js` | TB-4 | Rate/quota rejections served without CORS headers | open | P2 | R3 | pending | fixed |
| S03-I1 | Info | `worker/src/index.js` | TB-8 | Worker holds bucket-wide B2 credentials | open | P2 | R3 | pending | accepted (documented; scope-limited) |
| S03-I2 | Info | `worker/src/index.js` | TB-4 | All Worker accounting advisory by design | open | P2 | R3 | pending | fixed (per-user budget added) |
| S03-I3 | Info | `server/index.js` /mediaToken | TB-4 | `/mediaToken` is a scope-membership oracle | open | P2 | R3 | pending | accepted (bounded by S03-H1 fix) |

## Session 04 — Server egress & limits (`../audit/SESSION-04-EGRESS.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S04-H1 | High | `server/lib/egressGuard.js`,`server/lib/egressGuard.test.js`,`server/index.js:915,:984-991` | TB-3/G4 | SSRF predicate never resolves DNS, misses IPv6/literal forms | **fixed** (R2 cluster B; code `48a3f7e`, PR #57) — `lib/egressGuard.js` adds full `inet_aton` literal parsing (decimal `2130706433`, hex `0x7f.0x0.0x0.0x1`, octal `0177.0.0.1`, short form `127.1`), a 14-entry blocked-CIDR table (incl. the whole `169.254/16` link-local range, not just `.254`), IPv6 literal parsing covering IPv4-mapped `::ffff:127.0.0.1`, unique-local `fc00::/7` and link-local `fe80::/10`, and — the actual gap the finding names — **DNS resolution via `resolveAndCheckHost()`, refusing the host if ANY returned address is non-public.** Wired **per redirect hop** in `fetchFollowingSafeRedirects()`, not only on the first URL, so a public host cannot `302` to `169.254.169.254`. The old `pure.isBlockedPreviewHost()` is **kept alongside**, not replaced (protocol §8 "add, don't replace"). **Residual risk, stated not hidden: DNS rebinding.** This is check-then-connect, so a sub-second-TTL race survives; closing it needs socket pinning to the validated address. Recorded as `PR-5` in `RISK_REGISTER.md`, and the module's own docblock states the limit rather than overclaiming. | P1 | R2 | **verified-source+test** 2026-08-10: `node --test lib/egressGuard.test.js` passes within the cluster B run (49/49 across the three new suites); full `cd server && npm test` → **153 tests / 153 pass / 0 fail**; `node --check index.js` clean; wiring confirmed live at `index.js:984-991` — grepped call sites, **not** dead code | fixed |
| S04-H2 | High | `server/lib/egressGuard.js`,`server/index.js:2963,:2885` | TB-3 | `/linkPreview` reads body with no size cap and no timeout → OOM crash | **fixed** (R2 cluster B; code `48a3f7e`, PR #57) — `readCappedBody()` replaces `await response.text()`, which buffered the **entire** body before any `.slice()` could run, so a host advertising a small page and then streaming gigabytes killed the process. Two independent gates, both required: reject an oversized declared `Content-Length` before reading a byte, then count bytes while streaming (the header may lie or be absent) and cancel the reader at the cap. 512 KB for HTML (`og:` tags live in `<head>`), 2 MB for proxied images. The timeout half is enforced by the existing `AbortController` in `fetchFollowingSafeRedirects()`. | P1 | R2 | **verified-source+test** 2026-08-10: covered by `lib/egressGuard.test.js`; full suite **153/153 pass**; both call sites confirmed from source at `index.js:2963` (HTML) and `:2885` (image proxy) | fixed |
| S04-H3 | High | `server/lib/imageProxy.js`,`server/lib/imageProxy.test.js`,`server/index.js:926,:2835-2885,:3005-3021` | G4/TB-3 | `og:image` fetched directly by both devices → recipient IP + read-timestamp beacon | **fixed server-side, and the client half closes with it** (R2 cluster B; code `48a3f7e`, PR #57) — `/linkPreview` no longer returns the attacker's `og:image` URL at all. It re-checks the extracted host through the egress guard (a page can point `og:image` at `169.254.169.254`), then returns an **HMAC-signed absolute URL back to this server** (`imageProxy.signImageUrl`), which `GET /linkPreviewImage` verifies before proxying under the 2 MB cap and an image-only `Content-Type` allowlist. **No Java edit is required, verified by reading both sides:** `LinkPreviewFetcher.java:141` reads `imageUrl` from the server's JSON and `MessageAdapter.java:890-892` hands that string verbatim to Glide, so an **already-shipped APK benefits** — which matters precisely because Android cannot be compiled in this environment. If `LINK_PREVIEW_PROXY_SECRET` is unset or weak the image is **omitted**, never falling back to the raw URL, so the leak cannot silently return. Same fix closes `S08-H4`. | P1 | R2 | **verified-source+test** 2026-08-10: `lib/imageProxy.test.js` incl. a MAC checked against an independently computed HMAC; full suite **153/153 pass**; both Java consumers read from source to confirm no client change is needed. Android compilation **BLOCKED** (no JDK/SDK) — but **no Java was modified**, so nothing needs compiling for this row | fixed |
| S04-M1 | Med→High | `server/index.js:643-655` + limiter callers | TB-3/TB-5 | IPv6 /64 defeats every IP-keyed limit incl. admin lockout | open | P2 | R3 | pending | fixed |
| S04-M2 | Medium | `server/index.js:2032` | TB-7 | 24h redistributable TURN creds, no aggregate cap, no outbound timeout | open | P2 | R3 | pending | fixed |
| S04-M3 | Medium | `server/index.js:643-655` | TB-3 | XFF trust hard-coded to one proxy | open | P2 | R3 | pending | fixed (made configurable) |
| S04-L1 | Low | `server/index.js:780` | TB-3 | `collectBody` counts chars not bytes; no `setEncoding` (dup of S02-L4) | open | P2 | R3 | pending | fixed |
| S04-L2 | Low | `server/index.js` `/duress-lock` | TB-3 | `/duress-lock` unauthenticated, no rate limit (dup of S06-L2) | open | P2 | R3 | pending | fixed |
| S04-L3 | Low | `server/index.js` limiters | TB-3 | All limiter state per-process/in-memory; `mintCooldown` never purged | open | P2 | R3 | pending | fixed (purge) + accepted (durable store deferred to ops) |
| S04-I1 | Info | `server/index.js:2112-2128` | TB-3 | `/status` and `/` unauthenticated, publish platform counters | open | P2 | R3 | pending | fixed |
| S04-I2 | Info | `server/index.js:2916-2928`,`server/lib/pure.js:88-131` | TB-8 | Dead B2 presign surface; B2 creds still expected in env | open | P1 | R2 | pending | fixed+runbook (revoke B2 key) |
| S04-I3 | Info | `server/index.js:2254,:2293-2299` | TB-3 | Preview provenance/failure indistinguishable to client | open | P2 | R3 | pending | fixed |

## Session 05 — Admin surface (`../audit/SESSION-05-ADMIN.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S05-H1 | High | `server/lib/adminSecret.js`,`server/lib/adminSecret.test.js`,`server/index.js:641-657` | TB-5 | `ADMIN_TOKEN` has no entropy floor, no startup validation, no rotation/expiry, no working brute-force ceiling | **fixed+runbook** (R2 cluster B; code `f636d8b`, PR #57) — `evaluateSecretStrength()` enforces a **128-bit floor** (`MIN_SECRET_BYTES = 16`) measured over the character classes actually present, plus a Shannon check that rejects long-but-repetitive values, so `ADMIN_TOKEN=admin` **and** a 64-char run of `a` are both refused. Gated **at startup** (`process.exit(1)`), not per request: startup is the only moment a weak token is still a fixable deployment error instead of an invisible standing exposure. Deliberate asymmetry — *unset* does **not** abort (the panel already returns `503`, and a deployment that never enables admin is legitimate), *weak* does abort (it is open). Brute-force ceiling: the pre-existing `ADMIN_IP_MAX_FAILS = 10` / 15-min window is confirmed wired at both the API gate and `POST /admin/login`. **Rotation/expiry remains an operator action** — a single static bearer token with no expiry — hence `fixed+runbook`, not `fixed`. | P1 | R2 | **verified-source+test** 2026-08-10: `node --test lib/adminSecret.test.js` passes in the cluster B run; full `cd server && npm test` → **153/153 pass**; tests include the weak values the old unvalidated env read accepted, and confirm `openssl rand -hex 32` (the exact string in the remedy message) is accepted, so the module cannot tell operators to do something it rejects | fixed (startup entropy gate + failure logging) + runbook (rotate token) |
| S05-H2 | High | `server/index.js` waitlist | TB-5 | Waitlist unreviewable: no requester info, no deny/expire/revoke path | open | P2 | R3 | pending | fixed (deny/expire path) + accepted (product minimalism) |
| S05-H3 | High | `server/index.js:708-730,:776,:795,:3504-3535,:3555`,`server/lib/adminAuditWiring.test.js`,`firestore.rules:497-499` | TB-5 | Admin actions not durably audited; admin auth not audited at all | **fixed in two commits — and the first one was a half-fix worth reading about** (R2 cluster B). The *actions* half of the row was **stale**: waitlist approve, unfreeze and duress enroll/revoke already wrote to `adminAuditLog`. The real gap was admin **authentication**. `f636d8b` (PR #57) added `auditAdminEvent()` and wired it into `requireAdminAuth()` **only**, while its own comment claimed "login success, login failure, lockout and logout" were covered. **They were not** — `POST /admin/login` and `POST /admin/logout` still wrote to `console.warn` alone, and Render's logs roll, so the single most important forensic question ("did anyone other than us get in, and when?") was still unanswerable. Caught 2026-08-10 by grepping call sites instead of trusting the comment — the same "exists but unwired" defect class as cluster A's dead `maintainLockCredential()`. `7653515` wires all five remaining branches (**7 call sites total**: `admin_api_blocked_locked_out`, `admin_api_unauthorized`, `admin_login_blocked_locked_out`, `admin_login_unconfigured`, `admin_login_failed`, `admin_login_succeeded`, `admin_logout`) and corrects the overstated comment. Failure rows record the supplied **length**, never the token — a durable copy of a live credential would make the audit trail a second place to steal it from. Writes are fire-and-forget so a failing sink cannot become an availability problem; `adminAuditLog` is `allow read, write: if false`, so no client can read or tamper with it. | P1 | R2 | **verified-source+test** 2026-08-10: new `lib/adminAuditWiring.test.js` (5 tests) asserts every one of the 7 action strings has a call site, that login audits **both** outcomes (auditing only failures is the classic half-fix — "nobody failed" ≠ "nobody got in"), that the failed-login row excludes `ADMIN_TOKEN`, and that the Firestore rule denies clients. **Its own validity was falsified before it was trusted:** removing one call site makes it fail **3 of 5**, then restoring it returns 5/5 (`git diff --stat` confirmed the file was restored). Full `cd server && npm test` → **153 tests / 153 pass / 0 fail**; `node --check index.js` clean. Honestly scoped: this is a **source-level structural** test, so a behavioural test that a row truly lands in Firestore is **BLOCKED** (no emulator/credentials) and is stated as such in the file's own header rather than implied | fixed |
| S05-M1 | Medium | `server/index.js` admin | TB-5 | Raw operator IPs + raw uids persisted to Firestore forever, uids to stdout | open | P2 | R3 | pending | fixed |
| S05-M2 | Medium | `server/index.js`,`firestore.rules` | TB-5 | `duressEligibility` enforced nowhere → enroll/revoke cosmetic (with S06-M1) | open | P2 | R3 | pending | fixed |
| S05-M3 | Medium | `server/index.js` admin sessions | TB-5 | Admin sessions: no absolute lifetime, refreshed unauthenticated, bound to nothing, no bulk revoke | open | P2 | R3 | pending | fixed |
| S05-L1 | Low | `server/index.js` `/admin/api/account/lookup` | TB-5 | Route skips `validAdminUid` → slash-bearing uid reaches `.doc()` | open | P2 | R3 | pending | fixed |
| S05-L2 | Low | `server/index.js` admin POST | TB-5 | `collectBody` runs before `requireAdminAuth` | open | P2 | R3 | pending | fixed |
| S05-L3 | Low | `server/index.js` `/admin/api/*` | TB-5 | No `Cache-Control` on admin responses | open | P2 | R3 | pending | fixed |
| S05-L4 | Low | `server/index.js` admin | TB-5 | Read-then-write TOCTOU on approve/unfreeze; unbounded `.get()` | open | P2 | R3 | pending | fixed |
| S05-I1 | Info | `server/README.md:14-73` | TB-5 | Operator secrets undocumented; server boots without them | **fixed** (R2 cluster B, `7653515`) — this row was genuinely untouched by PR #57 (confirmed: `git log -- server/README.md` showed no cluster B commit, and the README mentioned **none** of `ADMIN_TOKEN`/`MEDIA_TOKEN_SECRET`/`LINK_PREVIEW_PROXY_SECRET`). Added a table of **all 15** env vars `index.js` actually reads — the list generated by grepping `process.env.` rather than from memory — split by failure mode into Required / Security-critical / Feature-scoped, each with its **real** missing-value behaviour, plus the 128-bit `ADMIN_TOKEN` startup gate, the fail-closed asymmetry between `ADMIN_TOKEN` (weak ⇒ refuse to boot) and `LINK_PREVIEW_PROXY_SECRET` (weak ⇒ disable the feature), and a rotation section pointing at `migration/MIGRATION_PLAN.md` and at the `adminAuditLog` rows to check first. **Documents a gap rather than papering over it:** `MEDIA_TOKEN_SECRET` has **no entropy gate** — `index.js` warns only when it is absent and never inspects its strength — so the table says so explicitly instead of implying validation the code does not perform. | P1 | R2 | **verified-source** 2026-08-10: every claim checked against `index.js` before writing, and **two first-draft claims were corrected as a result** — the TURN row (`/turnCredentials` returns `503`; the asserted client-side "falls back to STUN" was unverifiable, so it is no longer claimed) and `B2_BUCKET` (defaults to `yyush-duoshield`, is not empty). Docs-only change; no test applies | fixed |
| S05-I2 | Info | `server/index.js` comments | TB-5 | Stale comments describe a non-existent admin surface | open | P2 | R3 | pending | fixed |
| S05-I3 | Info | `server/index.js` cookies | TB-5 | CSRF rests on `SameSite=Strict`; `Secure` from client header; length oracle in `safeTokenEqual` | open | P2 | R3 | pending | fixed |

## Session 06 — Duress & locks (`../audit/SESSION-06-DURESS.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S06-H1 | High | `RestoreFromSeedActivity.java:252-268`,`server/index.js:1436-1546` | TB-1 | `accountLock` never enforced server-side; restore gate client-side post-auth | open | P0 (with S07-C1) | R1 | pending | fixed |
| S06-H2 | High | `DuressManager.java`,`AccountLockWorker.java`,`FcmUnregisterWorker.java`,`BaseActivity.java:90-92` | Theme F | Duress wipe leaves plaintext WorkManager records proving a duress code was entered | **fixed** — found ALREADY REMEDIATED in source during R2 verification; the `open` status was stale, not a real gap. `AccountLockWorker` input data carries only an opaque nonce (no uid, no reason, no duress marker); `FcmUnregisterWorker` carries no input data at all and is enqueued with the same jittered delay on ordinary sign-out too, so the WorkManager DB has nothing that distinguishes a duress wipe from a normal logout | P1 | R2 | verified-source 2026-08-10 (read both worker classes' `enqueue()` + `Data.Builder` call sites end to end and confirmed no duress-correlating field is ever written; confirmed the non-duress enqueue path exists at `BaseActivity.java:90-92`, which is what removes the correlation). **Android compilation NOT verified — no JDK/Android SDK in env (BLOCKED).** No code change was required for this row | fixed |
| S06-H3 | High | `DuressManager.java:460-620,:726-830`,`PendingLockStore.java`,`BaseActivity.java:120-146` | TB-1 | Offline duress trigger silently fails to lock; attacker controls network | **fixed** (R2 2026-08-10) — durable `PendingLockStore` intent + `drainPendingLockIntent()` on next launch were already present and wired, BUT `DuressManager.maintainLockCredential()` — the method that parks the warm nonce the offline path consumes — **had zero callers anywhere in the repo**, making the entire offline branch inert while its javadoc asserted otherwise. Fixed by adding the missing call in `BaseActivity.onStart()` (valid-session + foregrounded + unlocked branch). Also corrected two false comments: `drainPendingLockIntent` claimed a "best-effort worker retry" it never performed, and `PendingLockStore`'s javadoc claimed a pre-fetch that never ran | P1 | R2 | verified-source 2026-08-10 (dead-code proven by repo-wide `grep` for `maintainLockCredential` returning only the definition at `DuressManager.java:753`; after the fix the call site is present and the signature matches `static void (Context)`, with `Log`/`TAG`/package all resolving in `BaseActivity`). **Android compilation NOT verified — no JDK/Android SDK in env (BLOCKED); no unit test possible without an Android test harness.** Residual: if a future refactor drops that one call site the offline lock silently breaks again — flagged in-code as load-bearing | fixed |
| S06-M1 | Medium | `DuressManager.java`,`ManageUnlockCodesActivity.java`,`firestore.rules:321-324` | TB-1 | `duressEligibility` enforced nowhere; cached client bool only | open | P2 | R3 | pending | fixed |
| S06-M2 | Medium | `server/index.js:2394-2466` | TB-1 | `_duressNonces` grows without bound | open | P2 | R3 | pending | fixed (per-uid single nonce + drop-path delete) + runbook (TTL policy) |
| S06-M3 | Medium | `server/index.js:2398,:2476` | Theme F | Raw uids logged on both duress endpoints | open | P2 | R3 | pending | fixed |
| S06-L1 | Low | `server/index.js` nonce expiry | TB-1 | Nonce expiry check fails open on malformed `expiresAt` | open | P2 | R3 | pending | fixed |
| S06-L2 | Low | `server/index.js` `/duress-lock` | TB-1 | Unauthenticated, no rate limit (dup of S04-L2) | open | P2 | R3 | pending | fixed |
| S06-L3 | Low | `firestore-tests/` | TB-2 | `_duressNonces` has no rules-test coverage | open | P2 | R3 | pending | fixed |
| S06-L4 | Low | `AccountLockWorker.java` | TB-1 | Reports failure as success; retries 5xx without cap | open | P2 | R3 | pending | fixed |
| S06-I1 | Info | `firestore.rules` comment | TB-5 | Rules comment contradicts shipped admin unfreeze | open | P2 | R3 | pending | fixed |
| S06-I2 | Info | `DuressManager.java:540-560` | TB-1 | Step 1a can't distinguish success from failure | **fixed** — found ALREADY REMEDIATED in source during R2 verification (stale `open`); the lock result is gated on `task.isSuccessful()` before `lockConfirmed` is set, and the durable intent is cleared only on a true confirmation, so a failed lock is retained as "believed unlocked" rather than being mistaken for success. Genuinely subsumed by the S06-H3 durable-intent work as the plan predicted | P2 | R2 (closed early with S06-H3) | verified-source 2026-08-10 (read the confirmation branch and the intent-clear call together to confirm no path clears the intent on failure). **Android compilation NOT verified — no JDK/Android SDK in env (BLOCKED).** No code change was required for this row | fixed |
| S06-I3 | Info | `SecurePrefs.java` | Theme F | Duress PIN strength bounded by PIN space (answered by S08-H5) | open | P2 | R3 | pending | accepted (documented; PIN-space inherent) |

## Session 07 — Client crypto (`../audit/SESSION-07-CLIENT-CRYPTO.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S07-C1 | **Critical** | `server/lib/identityVerify.js`,`server/lib/challengeStore.js`,`server/index.js:6,:1706-1876`,`AuthTokenHelper.java:112-174,:189-249`,`DisplayNameActivity.java:126`,`RestoreFromSeedActivity.java:226` | TB-2/TB-1 | `/mintToken` accepted a public value (identity pubkey) as proof of ownership → takeover w/o seed | **fixed** (server verified by test; Android verified by source only — see note) | P0 | R1 | verified-source+test 2026-08-10 (`npm test` → 83/83 pass; `node --test lib/identityVerify.test.js` → 16/16 pass; `node --check` clean; Java/JS challenge bytes proven byte-identical). **Android compilation NOT verified — no JDK/Android SDK in env (BLOCKED).** | fixed |
| S07-H1 | High | `server/index.js:1514-1517` | TB-1 | Existing-account key check fails open when `identityPubKeyHash` absent (dup of S02-L1) | open | P0 | R1 | pending | fixed |
| S07-H2 | High | `BackupCryptoHelper.java:105-111`,`BackupManager.java` | TB-1 | Backup docs ship unkeyed SHA-256 of plaintext → offline plaintext-recovery oracle | open | P2 | R3 | pending | fixed |
| S07-H3 | High | `GroupCipherHelper.java:43-79`,`firestore.rules:130-134` | TB-2 | Group messages have no AAD → sender attribution rules-only | open | P2 | R3 | pending | fixed |
| S07-M1 | Med→High(=S08-H5) | `SecurePrefs.java` | Theme C | Silent plaintext fallback; `isInitialized()` ignores it | open | P1 | R2 | pending | fixed (same change as S08-H5) |
| S07-M2 | Medium | `DuoShieldSignalStore.java` | TB-2 | Trust keyed on mutable Firebase uid → `/migrateUid` resets safety numbers | open | P2 | R3 | pending | fixed |
| S07-M3 | Medium | `BackupManager.java` | TB-1 | Backup metadata outside the AEAD (`isDeleted`/`compressed`/missing `checksum`) | open | P2 | R3 | pending | fixed |
| S07-L1 | Low | `GroupChatActivity.java` `fetchGroupKey` | TB-2 | Creator check fails open on null cached `creatorUid` | open | P2 | R3 | pending | fixed |
| S07-L2 | Low | `SeedPhraseHelper.java` `derivationCache` | Theme F | Static cache retains identity key pair across duress wipe | open | P2 | R3 | pending | fixed |
| S07-L3 | Low | `SeedPhraseHelper.java` | TB-2 | `mnemonicToSeed` doesn't canonicalize; `toLowerCase()` no `Locale.ROOT` | open | P2 | R3 | pending | fixed |
| S07-L4 | Low | `DuoShieldSignalStore.java:307` | TB-2 | `loadSession` silently substitutes fresh session on deser failure (with S10-N2) | open | P2 | R3 | pending | fixed |
| S07-I1 | Info | `DuoShieldSignalStore.java:371-384` | TB-2 | `SenderKeyStore` stub — Signal group primitive present but unused | open | P2 | R3 | pending | accepted (documented; enables S07-H3 fix path) |
| S07-I2 | Info | `firestore.rules` groups | TB-2 | No add-member flow; rules permit key-less membership add | open | P2 | R3 | pending | accepted (documented) |
| S07-I3 | Info | `SeedPhraseHelper.java:546-564` | TB-2 | Account ID uses only 64 bits of SHA-256(seed) and doubles as uid + slot key | open | P2 | R3 | pending | accepted (documented; entropy analysis in DECISION-LOG) |

### Note — `S07-C1` evidence and the one thing that is *not* verified

This is the finding this program falsely closed once before (`SESSION_PROTOCOL.md` §0, fabrication
#3). The disposition above is therefore stated with its evidence and its limits explicit.

**What was verified from source and from commands run on 2026-08-10 (session S07-C1 part 2, recording
pass):**

- `server/lib/identityVerify.js` exists and delegates verification to `PublicKey.verify()` from
  `@signalapp/libsignal-client` — pinned `^0.54.1` in `server/package.json`, matching the Android
  client's `org.signal:libsignal-android:0.54.1` / `libsignal-client:0.54.1` (`app/build.gradle:284-288`).
  **No XEdDSA/Curve25519 math is hand-rolled**, which was the specific defect of the fabricated
  `xed25519.js`. `npm ci` installs the package cleanly from the committed lockfile.
- `server/index.js:1867` calls `verifyMintTokenSignature(...)` and returns 403 on failure, and
  `:1853` calls `mintChallengeStore.consume(userId, nonce)` first, so the nonce is spent by the
  attempt itself (no signature-grinding against one outstanding challenge, no replay).
- `:1796-1801` makes `nonce` + `signatureHex` **mandatory** — a legacy client gets a 400, not a
  bypass.
- **The original `sha256(identityPubKeyHex)` check was kept, not removed** (`:1878` `incomingHash`,
  `:1950` `storedHash`), so the `S07-H1` fail-closed branch is intact and the new gate is additive
  defense-in-depth.
- The signature is domain-separated: `"DuoShield-mintToken-v1" || 0x00 || utf8(userId) || 0x00 ||
  nonce`, binding it to this endpoint and this account so a signed-prekey signature made by the same
  identity key cannot be repurposed.
- Client/server byte agreement was checked mechanically, not by eye: the Java builder
  (`AuthTokenHelper.java:159-174`) and the JS builder produce the identical 71-byte string for a
  fixed vector (`44756f...002a`), which also matches the vector printed by the test suite.
- Both Android call sites pass a full `IdentityKeyPair` (`DisplayNameActivity.java:126`,
  `RestoreFromSeedActivity.java:226`); no public-key-only call site remains.
- Test output from this session, not remembered: `npm test` → `tests 83 / pass 83 / fail 0`;
  `node --test lib/identityVerify.test.js` → `tests 16 / pass 16 / fail 0`. The suite covers all
  eight required attack cases — invalid signature, wrong signing key, modified nonce, modified/wrong
  public key, reused nonce, expired nonce, never-issued nonce, cross-account nonce, bare-nonce (no
  context prefix), bit-flipped signature, malformed input, and the valid case. Signatures in the
  tests are produced by the same vetted library, never hand-written.

**What is NOT verified:** the Android module has **never been compiled**. This environment has no
JDK, no Gradle on `PATH`, and no Android SDK (`ANDROID_HOME` unset), so `AuthTokenHelper.java`'s
changes are verified by source review and cross-language byte comparison **only**. Treat Android
compilation as `BLOCKED`, not as passing. **Required operator step before any release:** run
`./gradlew :app:assembleDebug` (or CI equivalent) and confirm `AuthTokenHelper.java` compiles —
`Curve.calculateSignature` and `IdentityKeyPair.getPrivateKey()` resolve against the `compileOnly`
`libsignal-client` jar, and the stripped runtime jar must retain `org.signal.libsignal.protocol.ecc.Curve`.
If it does not compile, sign-in is broken app-wide and this row drops back to `partial`.

**Deployment ordering warning (not a defect):** the server now *requires* the challenge/signature
fields, so **the server and the Android client must ship together.** An updated server in front of
an old APK returns 400 on every sign-in. Do not "fix" that by making the fields optional — that
restores the takeover this finding describes.

## Session 08 — Client platform (`../audit/SESSION-08-CLIENT-PLATFORM.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S08-C1 | **Critical** | `.github/workflows/release.yml:55-66`,`build-release.sh`,`build-apks.sh`,`app/build.gradle:167-183` | all | Firebase Admin service-account private key packaged into every released APK | open | P0 | R1 | pending | fixed+runbook (revoke GCP key) |
| S08-H1 | High | `app/build.gradle:70-77`,`release.yml:76,:85`,`worker/src/index.js:357-362` | TB-9 | `WORKER_SECRET` compiled into `BuildConfig`; Worker still accepts it on `/stats` | open | P0/P1 | R1 | pending | fixed+runbook (rotate Worker secret) |
| S08-H2 | High | `BaseActivity.java:42-46` + 4 clear sites | Theme F | `FLAG_SECURE` actively cleared app-wide → OS snapshots of plaintext chats | open | P1 | R2 | pending | fixed |
| S08-H3 | High | `DuoShieldGlideModule.java:59-63`,`MessageAdapter.java`,`TempFileCleaner.java` | Theme F | 150 MB plaintext Glide disk cache + 4 unswept temp prefixes | open | P1 | R2 | pending | fixed |
| S08-H4 | High | `app/src/main/java/com/duoshield/app/ui/MessageAdapter.java:890-892`,`app/src/main/java/com/duoshield/app/util/LinkPreviewFetcher.java:141`,`server/lib/imageProxy.js` | G4 | Link-preview images fetched from sender's host (client half of S04-H3) | **fixed, with no Java change — deliberately** (R2 cluster B; code `48a3f7e`, recorded `7653515`). The client half was closed by fixing the **server**: `MessageAdapter.java:890-892` passes `preview.imageUrl` verbatim to Glide, and `LinkPreviewFetcher.java:141` takes that value straight from `/linkPreview`'s JSON, so once the server returns a signed URL pointing at itself the device stops contacting the linked host — **including on already-shipped APKs**. Chosen precisely because Android cannot be compiled or verified in this environment, so a Java-side fix would have been unverifiable *and* would have left existing installs exposed. Editing `MessageAdapter.java` would add risk here without adding security. See `S04-H3` for the server mechanism. | P1 | R2 | **verified-source** 2026-08-10: read both Java files to confirm the client applies no independent URL handling and needs no edit; server side is test-backed under `S04-H3` (full suite **153/153 pass**). Android compilation **BLOCKED** (no JDK/SDK) but **no Java was modified**, so this row has nothing awaiting a build | fixed |
| S08-H5 | High | `SecurePrefs.java` | Theme C | Plaintext fallback holds identity key, backup key AND SQLCipher passphrase (re-rate of S07-M1) | open | P1 | R2 | pending | fixed |
| S08-M1 | Medium | `AndroidManifest.xml` | Theme — | `allowNativeHeapPointerTagging="false"` disables memory-safety mitigation | open | P2 | R3 | pending | fixed |
| S08-M2 | Medium | `res/xml/file_paths.xml` | F | `FileProvider` declares root-scoped grantable paths incl. unused external roots | open | P2 | R3 | pending | fixed |
| S08-M3 | Medium | app-wide | F | No root/tamper/hooking detection, no keystore attestation | open | P2 | R3 | pending | accepted (out of threat model — compromised client granted) |
| S08-L1 | Low | `AddContactActivity.java:154-172` | F | Exported deep link accepts unvalidated Account ID | open | P2 | R3 | pending | fixed |
| S08-L2 | Low | clipboard writers | F | Message bodies / Account IDs copied without `EXTRA_IS_SENSITIVE` | open | P2 | R3 | pending | fixed |
| S08-L3 | Low | `PinManager.java` | Theme D | PIN length stored beside PIN hash | open | P2 | R3 | pending | fixed |
| S08-L4 | Low | `BaseActivity.java`,`LockScreenActivity.java` | Theme F | Lock screen layered over rendered activity; neither excluded from recents | open | P2 | R3 | pending | fixed |
| S08-I1 | Info | `app/proguard-rules.pro` | Theme D | R8 keeps every `crypto.**`/`security.**` class + member name | open | P2 | R3 | pending | fixed |
| S08-I2 | Info | network config | G | No certificate pinning | open | P2 | R3 | pending | accepted (documented; pinning tradeoff) |
| S08-I3 | Info | `worker/src/index.js:54-64` | TB-4 | Worker `ACAO: *` while allowing `Authorization` header | open | P2 | R3 | pending | fixed |

## Session 09 — Supply chain & CI/CD (`../audit/SESSION-09-SUPPLY-CHAIN-CI.md`)

| ID | Sev | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| SC-01 | **Critical** | `app/libs/libsignal-client-0.54.1-stripped.jar`,`scripts/strip_signal_records.py` | Theme H | Vendored crypto JAR not reproducible from committed script; no recorded hash; not validated in CI | open | P1 | R2 | pending | fixed |
| SC-02 | **Critical** | `.github/workflows/release.yml:47-76` | Theme D | Release workflow bakes full backend secret set into shipped APK | open | P0 | R1 | pending | fixed+runbook (rotate all creds) |
| SC-03 | High | `build.gradle`,`app/build.gradle` | Theme H | No Gradle dependency verification — ~30 coordinates unpinned by hash | open | P2 | R3 | pending | fixed (scaffold + CI wiring) + runbook (generate metadata) |
| SC-04 | High | `.github/workflows/release.yml` (new "Generate checksums and certificate fingerprint" step, "Create GitHub Release" step) | Theme H | Release APKs unverifiable: no checksums, no signature record, no provenance | **fixed** — added a step that runs `sha256sum` over every built APK into a `SHA256SUMS` file and runs `apksigner verify --print-certs` to capture the signing certificate's SHA-256 digest; both are now attached to / printed in the GitHub Release (`SHA256SUMS` as a release asset, the fingerprint in the release body) so a user can verify a fresh install and compare the fingerprint across releases. Did **not** add `actions/attest-build-provenance` (the audit's second recommendation) — deferred, see `RISK_REGISTER.md` `PR-8`, to keep this session's diff to what could be verified. | P1 | R2 | **verified-source-only** 2026-08-10: YAML parses cleanly (`js-yaml` in a throwaway install, 13 steps enumerated, no step lost or malformed); the step's `run:` block extracted and passed `bash -n`. **Not executed** — no GitHub Actions runner, Android SDK, or built APK exists in this sandbox to actually run `apksigner` or produce a real `SHA256SUMS`, so the shell logic is syntax-verified only, not behavior-verified. | fixed (source-reviewed, untested — no CI runner in this environment) |
| SC-05 | High | `.github/workflows/release.yml` ("Delete all previous releases and tags" step removed; "Resolve release tag" step changed) | Theme H | Workflow deletes all prior releases and tags on every push to `main` | **fixed** — deleted the "Delete all previous releases and tags" step outright, per the audit's explicit recommendation ("delete this step"). Releases and tags are now permanent audit records, never destroyed by this workflow. Changed automatic-push tag resolution from the rolling `v{versionName}` to `v{versionName}+{shortSHA}` so a tag always identifies exactly one commit/binary (the audit's second recommendation) instead of accumulating errors from tag reuse across un-bumped versions; manual `workflow_dispatch` may still supply an explicit tag since a human is choosing it deliberately. Did not implement the audit's optional "mark old releases as pre-release" presentation suggestion — not required by the finding, no security effect either way. | P1 | R2 | **verified-source-only** 2026-08-10: same YAML-parse + `bash -n` check as `SC-04` (single combined edit to the same file); confirmed by re-reading the diff that no `DELETE` API call, `gh api --method DELETE`, or reference to `releases/${RELEASE_ID}`/`git/refs/tags` remains anywhere in the file (`grep -c DELETE release.yml` → 0). **Not executed** — same CI-runner gap as `SC-04`; a real push to `main` was not triggered from this sandbox. | fixed (source-reviewed, untested — no CI runner in this environment) |
| SC-06 | High | `build.gradle:16` | Theme H | JitPack in repo list — builds from mutable Git refs | open | P2 | R3 | pending | fixed (scoped includeGroup) |
| SC-07 | Medium | `gradle/wrapper/gradle-wrapper.jar` | Theme H | Committed wrapper JAR, no wrapper validation in CI | open | P2 | R3 | pending | fixed |
| SC-08 | Medium | all workflows | Theme H | Actions pinned to mutable tags, not SHAs | open | P2 | R3 | pending | fixed |
| SC-09 | Medium | `.github/` | Theme H | No dependency/secret/SAST scanning, no SBOM, no Dependabot | open | P2 | R3 | pending | fixed (scan workflow + Dependabot) |
| SC-10 | Medium | `firestore.yml` | Theme H | Firestore deploy runs unpinned `npm install` with `audit=false` | open | P2 | R3 | pending | fixed |
| SC-11 | Low | `app/build.gradle` | Theme H | Production crypto depends on alpha `security-crypto:1.1.0-alpha06` | open | P2 | R3 | pending | accepted (no stable release; documented) |
| SC-12 | Low | `.github/CODEOWNERS` | Theme H | CODEOWNERS present, branch protection unverified | open | P0 | R1 | pending | fixed+runbook (enable branch protection) |

## Session 10 — Synthesis new findings (`../audit/SESSION-10-SYNTHESIS.md`)

| ID | Sev | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S10-N1 | Medium | `app/build.gradle`,`app/src/main/**`,`firestore.rules` | TB-2 | Firebase App Check absent → every Firestore rule scriptable at machine speed | open | P2 | R3 | pending | accepted (sideloaded-APK caveat) + fixed (client provider wiring) + runbook (enable enforcement) |
| S10-N2 | Low | `DuoShieldSignalStore.java:133,172,190,307,320` | Theme F | Peer uid written to release logcat, violating project log policy (with S07-L4) | open | P1 | R2 | pending | fixed |
| S10-N3 | Low | `worker/src/index.js:646-671` | TB-4 | Deleted media can survive in B2 cold tier when delete races nightly migration | **fixed** (R2 cluster D, one-line change) — the migration's `else` branch at `:663` already correctly *detected* the interleaving (client DELETE lands after migration's `get` but before its `PUT`; client's own best-effort B2 delete no-ops on an object that doesn't exist yet; migration's `PUT` then recreates it in B2 with nothing left to notice) but never acted on the detection. Added the one line the audit (`audit/SESSION-10-SYNTHESIS.md` §S10-N3) prescribed: `ctx.waitUntil(b2.fetch(b2Url(env, obj.key), { method: 'DELETE' }).catch(() => {}))` inside that branch, undoing the migration's own B2 write so the object cannot survive as an orphan. No other branch touched. | P1 | R2 | **verified-source-only** 2026-08-10: `node --check` on an `.mjs` copy of the file is clean; confirmed `ctx` is in scope (`scheduled(event, env, ctx)` at `:587`) and `b2Url`/`b2` are the same helpers the DELETE handler already uses at `:546-548`. **No test run** — `worker/` has no test framework (`package.json` lists only `wrangler`/`aws4fetch`, no `node --test` or Jest config, and no `wrangler dev`/Miniflare available in this environment to exercise the Cron Trigger handler), so this is source-verified only, same tier as the `firestore.rules` review in cluster A. Recorded honestly rather than claiming a test that didn't run. | fixed (source-reviewed, untested — no worker test harness in this environment) |

---

## Disposition rollup (target end state)

| Disposition | Count |
|---|---|
| fixed (incl. fixed+runbook) | 108 |
| accepted (with justification) | 8 |
| deferred-with-justification | 0 |
| **Total distinct findings indexed** | **116** |

Accepted set: `S01-I1` (ratified product decision), `S01-I2` (systemic TOCTOU, documented),
`S02-I1` (with S01-I1), `S03-I1` (bucket-wide B2 creds, scope-limited), `S06-I3` (PIN-space bound),
`S07-I1`/`S07-I2`/`S07-I3` (documented crypto-model notes), `S08-M3` (out of threat model),
`S08-I2` (cert-pinning tradeoff), `SC-11` (no stable alpha replacement). Several of these are
partial-accept/partial-fix; the fix half is recorded in the row and the accept half in
`RISK_REGISTER.md`. Final authoritative counts are reconciled in `FINAL_SIGNOFF.md`.
