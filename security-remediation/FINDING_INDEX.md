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
> - The **`Verify`** column starts as `pending` and is filled per-row when the fix is actually
>   verified against source and/or a test. Tasks 1–3 of Round 2 now have source verification
>   recorded; remaining Round 2 rows stay `pending` until their tasks execute.
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
| S01-L1 | Low | `firestore.rules:99-100` | TB-2 | `groups` create doesn't validate `createdBy` | open | P2 | R3 | pending | fixed |
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
| S03-H1 | High | `server/index.js:509-530`,`firestore.rules:99-100`,`worker/src/index.js` | TB-4 | Scope confusion: client-created `groups/{chatId}` self-asserts membership → media token for another conversation | partial (SEC-A01) | P1 | R2 | verified-source | fixed |
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
| S04-H1 | High | `server/lib/pure.js:60-68`,`server/index.js:2239,:723` | TB-3/G4 | SSRF predicate never resolves DNS, misses IPv6/literal forms | partial | P1 | R2 | verified-source | fixed |
| S04-H2 | High | `server/index.js:2256,:726-733` | TB-3 | `/linkPreview` reads body with no size cap and no timeout → OOM crash | open | P1 | R2 | verified-source | fixed |
| S04-H3 | High | `server/index.js:2278-2291`,`MessageAdapter.java:890-895` | G4/TB-3 | `og:image` fetched directly by both devices → recipient IP + read-timestamp beacon | open | P1 | R2 | verified-source | fixed |
| S04-M1 | Med→High | `server/index.js:643-655` + limiter callers | TB-3/TB-5 | IPv6 /64 defeats every IP-keyed limit incl. admin lockout | open | P2 | R3 | pending | fixed |
| S04-M2 | Medium | `server/index.js:2032` | TB-7 | 24h redistributable TURN creds, no aggregate cap, no outbound timeout | open | P2 | R3 | pending | fixed |
| S04-M3 | Medium | `server/index.js:643-655` | TB-3 | XFF trust hard-coded to one proxy | open | P2 | R3 | pending | fixed (made configurable) |
| S04-L1 | Low | `server/index.js:780` | TB-3 | `collectBody` counts chars not bytes; no `setEncoding` (dup of S02-L4) | open | P2 | R3 | pending | fixed |
| S04-L2 | Low | `server/index.js` `/duress-lock` | TB-3 | `/duress-lock` unauthenticated, no rate limit (dup of S06-L2) | open | P2 | R3 | pending | fixed |
| S04-L3 | Low | `server/index.js` limiters | TB-3 | All limiter state per-process/in-memory; `mintCooldown` never purged | open | P2 | R3 | pending | fixed (purge) + accepted (durable store deferred to ops) |
| S04-I1 | Info | `server/index.js:2112-2128` | TB-3 | `/status` and `/` unauthenticated, publish platform counters | open | P2 | R3 | pending | fixed |
| S04-I2 | Info | `server/index.js:2916-2928`,`server/lib/pure.js:88-131` | TB-8 | Dead B2 presign surface; B2 creds still expected in env | open | P1 | R2 | verified-source | fixed+runbook (revoke B2 key) |
| S04-I3 | Info | `server/index.js:2254,:2293-2299` | TB-3 | Preview provenance/failure indistinguishable to client | open | P2 | R3 | pending | fixed |

## Session 05 — Admin surface (`../audit/SESSION-05-ADMIN.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S05-H1 | High | `server/index.js:537,:610` | TB-5 | `ADMIN_TOKEN` has no entropy floor, no startup validation, no rotation/expiry, no working brute-force ceiling | open | P1 | R2 | verified-source | fixed (startup entropy gate + failure logging) + runbook (rotate token) |
| S05-H2 | High | `server/index.js` waitlist | TB-5 | Waitlist unreviewable: no requester info, no deny/expire/revoke path | open | P2 | R3 | pending | fixed (deny/expire path) + accepted (product minimalism) |
| S05-H3 | High | `server/index.js` admin actions | TB-5 | Admin actions not durably audited; admin auth not audited at all | open | P1 | R2 | verified-source | fixed |
| S05-M1 | Medium | `server/index.js` admin | TB-5 | Raw operator IPs + raw uids persisted to Firestore forever, uids to stdout | open | P2 | R3 | pending | fixed |
| S05-M2 | Medium | `server/index.js`,`firestore.rules` | TB-5 | `duressEligibility` enforced nowhere → enroll/revoke cosmetic (with S06-M1) | open | P2 | R3 | pending | fixed |
| S05-M3 | Medium | `server/index.js` admin sessions | TB-5 | Admin sessions: no absolute lifetime, refreshed unauthenticated, bound to nothing, no bulk revoke | open | P2 | R3 | pending | fixed |
| S05-L1 | Low | `server/index.js` `/admin/api/account/lookup` | TB-5 | Route skips `validAdminUid` → slash-bearing uid reaches `.doc()` | open | P2 | R3 | pending | fixed |
| S05-L2 | Low | `server/index.js` admin POST | TB-5 | `collectBody` runs before `requireAdminAuth` | open | P2 | R3 | pending | fixed |
| S05-L3 | Low | `server/index.js` `/admin/api/*` | TB-5 | No `Cache-Control` on admin responses | open | P2 | R3 | pending | fixed |
| S05-L4 | Low | `server/index.js` admin | TB-5 | Read-then-write TOCTOU on approve/unfreeze; unbounded `.get()` | open | P2 | R3 | pending | fixed |
| S05-I1 | Info | `server/README.md` | TB-5 | Operator secrets undocumented; server boots without them | open | P1 | R2 | pending | fixed |
| S05-I2 | Info | `server/index.js` comments | TB-5 | Stale comments describe a non-existent admin surface | open | P2 | R3 | pending | fixed |
| S05-I3 | Info | `server/index.js` cookies | TB-5 | CSRF rests on `SameSite=Strict`; `Secure` from client header; length oracle in `safeTokenEqual` | open | P2 | R3 | pending | fixed |

## Session 06 — Duress & locks (`../audit/SESSION-06-DURESS.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S06-H1 | High | `RestoreFromSeedActivity.java:252-268`,`server/index.js:1436-1546` | TB-1 | `accountLock` never enforced server-side; restore gate client-side post-auth | open | P0 (with S07-C1) | R1 | pending | fixed |
| S06-H2 | High | `DuressManager.java:269-322`,`AccountLockWorker.java`,`FcmUnregisterWorker.java` | Theme F | Duress wipe leaves plaintext WorkManager records proving a duress code was entered | open | P1 | R2 | verified-source | fixed |
| S06-H3 | High | `DuressManager.java:192-256` | TB-1 | Offline duress trigger silently fails to lock; attacker controls network | open | P1 | R2 | verified-source | fixed |
| S06-M1 | Medium | `DuressManager.java`,`ManageUnlockCodesActivity.java`,`firestore.rules:321-324` | TB-1 | `duressEligibility` enforced nowhere; cached client bool only | open | P2 | R3 | pending | fixed |
| S06-M2 | Medium | `server/index.js:2394-2466` | TB-1 | `_duressNonces` grows without bound | open | P2 | R3 | pending | fixed (per-uid single nonce + drop-path delete) + runbook (TTL policy) |
| S06-M3 | Medium | `server/index.js:2398,:2476` | Theme F | Raw uids logged on both duress endpoints | open | P2 | R3 | pending | fixed |
| S06-L1 | Low | `server/index.js` nonce expiry | TB-1 | Nonce expiry check fails open on malformed `expiresAt` | open | P2 | R3 | pending | fixed |
| S06-L2 | Low | `server/index.js` `/duress-lock` | TB-1 | Unauthenticated, no rate limit (dup of S04-L2) | open | P2 | R3 | pending | fixed |
| S06-L3 | Low | `firestore-tests/` | TB-2 | `_duressNonces` has no rules-test coverage | open | P2 | R3 | pending | fixed |
| S06-L4 | Low | `AccountLockWorker.java` | TB-1 | Reports failure as success; retries 5xx without cap | open | P2 | R3 | pending | fixed |
| S06-I1 | Info | `firestore.rules` comment | TB-5 | Rules comment contradicts shipped admin unfreeze | open | P2 | R3 | pending | fixed |
| S06-I2 | Info | `DuressManager.java` | TB-1 | Step 1a can't distinguish success from failure | open | P2 | R3 | pending | fixed (subsumed by S06-H3 durable-intent fix) |
| S06-I3 | Info | `SecurePrefs.java` | Theme F | Duress PIN strength bounded by PIN space (answered by S08-H5) | open | P2 | R3 | pending | accepted (documented; PIN-space inherent) |

## Session 07 — Client crypto (`../audit/SESSION-07-CLIENT-CRYPTO.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S07-C1 | **Critical** | `AuthTokenHelper.java:101-120`,`server/index.js:1471,:1512-1518` | TB-2/TB-1 | `/mintToken` accepts a public value (identity pubkey) as proof of ownership → takeover w/o seed | open | P0 | R1 | pending | fixed |
| S07-H1 | High | `server/index.js:1514-1517` | TB-1 | Existing-account key check fails open when `identityPubKeyHash` absent (dup of S02-L1) | open | P0 | R1 | pending | fixed |
| S07-H2 | High | `BackupCryptoHelper.java:105-111`,`BackupManager.java` | TB-1 | Backup docs ship unkeyed SHA-256 of plaintext → offline plaintext-recovery oracle | open | P2 | R3 | pending | fixed |
| S07-H3 | High | `GroupCipherHelper.java:43-79`,`firestore.rules:130-134` | TB-2 | Group messages have no AAD → sender attribution rules-only | open | P2 | R3 | pending | fixed |
| S07-M1 | Med→High(=S08-H5) | `SecurePrefs.java` | Theme C | Silent plaintext fallback; `isInitialized()` ignores it | open | P1 | R2 | verified-source | fixed (same change as S08-H5) |
| S07-M2 | Medium | `DuoShieldSignalStore.java` | TB-2 | Trust keyed on mutable Firebase uid → `/migrateUid` resets safety numbers | open | P2 | R3 | pending | fixed |
| S07-M3 | Medium | `BackupManager.java` | TB-1 | Backup metadata outside the AEAD (`isDeleted`/`compressed`/missing `checksum`) | open | P2 | R3 | pending | fixed |
| S07-L1 | Low | `GroupChatActivity.java` `fetchGroupKey` | TB-2 | Creator check fails open on null cached `creatorUid` | open | P2 | R3 | pending | fixed |
| S07-L2 | Low | `SeedPhraseHelper.java` `derivationCache` | Theme F | Static cache retains identity key pair across duress wipe | open | P2 | R3 | pending | fixed |
| S07-L3 | Low | `SeedPhraseHelper.java` | TB-2 | `mnemonicToSeed` doesn't canonicalize; `toLowerCase()` no `Locale.ROOT` | open | P2 | R3 | pending | fixed |
| S07-L4 | Low | `DuoShieldSignalStore.java:307` | TB-2 | `loadSession` silently substitutes fresh session on deser failure (with S10-N2) | open | P2 | R3 | pending | fixed |
| S07-I1 | Info | `DuoShieldSignalStore.java:371-384` | TB-2 | `SenderKeyStore` stub — Signal group primitive present but unused | open | P2 | R3 | pending | accepted (documented; enables S07-H3 fix path) |
| S07-I2 | Info | `firestore.rules` groups | TB-2 | No add-member flow; rules permit key-less membership add | open | P2 | R3 | pending | accepted (documented) |
| S07-I3 | Info | `SeedPhraseHelper.java:546-564` | TB-2 | Account ID uses only 64 bits of SHA-256(seed) and doubles as uid + slot key | open | P2 | R3 | pending | accepted (documented; entropy analysis in DECISION-LOG) |

## Session 08 — Client platform (`../audit/SESSION-08-CLIENT-PLATFORM.md`)

| ID | Sev (orig→gov) | Affected files | TB | Root cause | Status | Prio | Rnd | Verify | Planned Disp |
|---|---|---|---|---|---|---|---|---|---|
| S08-C1 | **Critical** | `.github/workflows/release.yml:55-66`,`build-release.sh`,`build-apks.sh`,`app/build.gradle:167-183` | all | Firebase Admin service-account private key packaged into every released APK | open | P0 | R1 | pending | fixed+runbook (revoke GCP key) |
| S08-H1 | High | `app/build.gradle:70-77`,`release.yml:76,:85`,`worker/src/index.js:357-362` | TB-9 | `WORKER_SECRET` compiled into `BuildConfig`; Worker still accepts it on `/stats` | open | P0/P1 | R1 | pending | fixed+runbook (rotate Worker secret) |
| S08-H2 | High | `BaseActivity.java:42-46` + 4 clear sites | Theme F | `FLAG_SECURE` actively cleared app-wide → OS snapshots of plaintext chats | open | P1 | R2 | verified-source | fixed |
| S08-H3 | High | `DuoShieldGlideModule.java:59-63`,`MessageAdapter.java`,`TempFileCleaner.java` | Theme F | 150 MB plaintext Glide disk cache + 4 unswept temp prefixes | open | P1 | R2 | pending | fixed |
| S08-H4 | High | `MessageAdapter.java:890-895` | G4 | Link-preview images fetched from sender's host (client half of S04-H3) | open | P1 | R2 | verified-source | fixed |
| S08-H5 | High | `SecurePrefs.java` | Theme C | Plaintext fallback holds identity key, backup key AND SQLCipher passphrase (re-rate of S07-M1) | open | P1 | R2 | verified-source | fixed |
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
| SC-01 | **Critical** | `app/libs/libsignal-client-0.54.1-stripped.jar`,`scripts/strip_signal_records.py` | Theme H | Vendored crypto JAR not reproducible from committed script; no recorded hash; not validated in CI | open | P1 | R2 | verified-source | fixed |
| SC-02 | **Critical** | `.github/workflows/release.yml:47-76` | Theme D | Release workflow bakes full backend secret set into shipped APK | open | P0 | R1 | pending | fixed+runbook (rotate all creds) |
| SC-03 | High | `build.gradle`,`app/build.gradle` | Theme H | No Gradle dependency verification — ~30 coordinates unpinned by hash | open | P2 | R3 | pending | fixed (scaffold + CI wiring) + runbook (generate metadata) |
| SC-04 | High | `release.yml:130,167` | Theme H | Release APKs unverifiable: no checksums, no signature record, no provenance | open | P1 | R2 | pending | fixed |
| SC-05 | High | `release.yml:126-162` | Theme H | Workflow deletes all prior releases and tags on every push to `main` | open | P1 | R2 | pending | fixed |
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
| S10-N2 | Low | `DuoShieldSignalStore.java:133,172,190,307,320` | Theme F | Peer uid written to release logcat, violating project log policy (with S07-L4) | open | P1 | R2 | verified-source | fixed |
| S10-N3 | Low | `worker/src/index.js:530-548,:646-665` | TB-4 | Deleted media can survive in B2 cold tier when delete races nightly migration | open | P1 | R2 | pending | fixed |

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
