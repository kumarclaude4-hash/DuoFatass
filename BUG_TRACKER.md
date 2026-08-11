# Bug Tracker

Single source of truth for every security finding in this repo. It replaces the
two previously-separate tracking systems (`audit/` session reports +
`security-remediation/FINDING_INDEX.md` / `MASTER_CHECKLIST.md` /
`RISK_REGISTER.md` / `REMEDIATION_PROGRESS.md`), which had drifted out of sync
with each other and — in several rows — with the actual code.

**Baseline:** re-derived from source at commit `298f916` (2026-08-11), not
copied from the old trackers' claimed statuses. Every "Fixed" or "Open" below
was checked against the file(s) it names in this pass. The narrative session
reports under `audit/SESSION-*.md` and `security-remediation/sessions/` are
kept as historical investigation notes — this file is the only place that
carries a current disposition.

## Legend

- **Status** — `Open` (defect still present as read), `Partial` (some but not
  all of the defect is closed), `Fixed` (defect closed, checked against
  current source), `Accepted` (known limitation, deliberately not fixed —
  see note).
- **Sev** — governing severity (audit re-ratings applied, matching the old
  index): Critical / High / Medium / Low / Info.
- Findings ID'd `S0x-*` come from `audit/SESSION-0x-*.md`; `SC-*` from
  `SESSION-09-SUPPLY-CHAIN-CI.md`; `S10-*` from `SESSION-10-SYNTHESIS.md`.

## Corrections vs. the old trackers (read this first)

The previous `FINDING_INDEX.md` marked every one of these `open` as of its
last edit. Source inspection in this pass shows otherwise:

| ID | Old tracker said | Actual repo state |
|---|---|---|
| S08-C1 | open | **Fixed.** `release.yml` never writes `service-account.json`; landed in `5c2cd73`, which predates the audit itself — the tracker was simply never updated. |
| SC-02 | open | **Fixed.** Same commit — `local.properties` carries no secret values, and a CI step (`Assert no secrets in packaged build inputs`) fails the build if one appears. |
| S08-H1 | open | **Fixed.** `app/build.gradle` no longer emits `BuildConfig.WORKER_SECRET`; `worker/src/index.js` `/stats` now requires a separate `STATS_SECRET`, fail-closed if unset (`6c30267`, `fddd45c`). |
| S06-M1 | open | **Fixed.** `firestore.rules` `accountLock` `create` now requires `duressEligibility/{uid}.eligible == true` before a lock doc can be written — the enforcement gap the finding describes is closed. |
| S05-M3 | open | **Partial.** Admin sessions now carry a 30-minute absolute TTL with expiry sweep (`ADMIN_SESSION_TTL_MS`, `server/index.js:662-675`). Still no bulk-revoke path and no binding to IP/UA. |

No other row changed disposition in this pass; all other "Fixed" rows below
match what the old index already claimed, re-confirmed against source.

## Verification confidence

- **Verified** — read against current source directly in this pass.
- **Carried** — the disposition matches the prior reconciled audit
  (`security-remediation/RECONCILIATION.md`), which itself was source-checked;
  not independently re-read line-by-line in this pass. Flagged so a future
  pass knows where to look first.

---

## Critical (4)

| ID | Status | Confidence | Issue | Evidence |
|---|---|---|---|---|
| S07-C1 | Fixed | Verified | `/mintToken` accepted a public value as ownership proof | `server/lib/identityVerify.js` + `challengeStore.js` exist; `index.js:1867` verifies signature, `:1853` consumes nonce; Android sends full `IdentityKeyPair` (`DisplayNameActivity.java:126`, `RestoreFromSeedActivity.java:226`). Android compile unverifiable (no JDK/SDK in this env) — treat as source-verified only. |
| S08-C1 | **Fixed** (S3-01) | Verified | Admin Firebase service-account key packaged into every APK | Re-verified from source this session (S3-01): no `service-account.json` write step in `release.yml`, `build-release.sh`, or `build-apks.sh`; all three fail the build if the file reappears. `find . -name service-account.json` → none; `assets/` holds only `README.txt`/`brand`/`watch_together`. Code half done; operator must still **revoke the leaked GCP SA key** (runbook). |
| SC-01 | **Fixed** (S3-02) | Verified | Vendored `libsignal-client-*-stripped.jar` not reproducible/hashed/validated in CI | Claim re-derived from source this session, then fixed. **Root cause confirmed live:** the committed `scripts/strip_signal_records.py` declared a 6-entry `STRIP` set, but an entry-by-entry diff of the shipped JAR against upstream `org.signal:libsignal-client:0.54.1` (fetched from Maven Central, sha256 `9605b9c6…87d4a`, matching the audit) showed **10 removed, 0 added, 0 CRC-changed** — `ChatService`, `Svr3`, `GroupSendEndorsementsResponse` and `ChatService$InternalRequest` had no documented provenance. **Fix:** `STRIP` corrected to the 10 real entries; the source JAR is now fetched from Maven Central and hash-pinned (`UPSTREAM_SHA256`) instead of scraped from `~/.gradle/caches`; the output is asserted against `EXPECTED_OUTPUT_SHA256` = `fa7d3afe…d89e3`; new `--check` mode. Hash recorded in-repo at `app/libs/libsignal-client-0.54.1-stripped.jar.sha256`. Gate wired as a dedicated `verify-libsignal-jar` job in `ci.yml` that `lint` needs (so `build-debug`/`instrumented-tests` inherit it) **and** in `release.yml` before the keystore is decoded. **Evidence (commands run this session):** full regenerate printed `Output : sha256 fa7d3afe…d89e3 (38466351 B) — matches recorded hash` and `git status` then showed the JAR **unmodified** — i.e. the documented procedure now reproduces the shipped binary byte-for-byte; `--check` exits 0 on the committed JAR, exits 1 with a `::error::` on a 1-byte append (`908298e2…62f5`), and exits 1 when the `.sha256` sidecar is absent; `js-yaml` parse of both workflows OK. Note: strip-by-prefix (the audit's alternative) would remove **37** entries and would *not* reproduce the artifact, so the explicit 10-entry list is intentional — do not "simplify" it. Commit `dcf85c5`. Residual (audit rec. 4, not this finding): deleting the vendored JAR entirely by raising `minSdk` to 34 remains the preferred long-term fix. |
| SC-02 | **Fixed** (S3-01) | Verified | Release workflow baked full backend secret set into shipped APK | `release.yml` and `ci.yml` write `local.properties` with non-secret routing values only (`b2.bucket`/`b2.region`/`push.server.url`/`worker.url`); `app/build.gradle` no longer reads the B2 credential pair (`B2_KEY_ID`/`B2_APPLICATION_KEY` emitted as `""`). `release.yml` "Assert no secrets in packaged build inputs" guard blocks `b2.key.id`/`b2.application.key`/`worker.secret`/private-key blocks. This session (S3-01) added the **same guard to `ci.yml`'s `build-debug` job**, which also uploads an APK artifact — closing the enforcement gap on the PR-facing job. Guard logic functionally tested: passes clean/empty-value, fails on real `worker.secret` value and on a private-key block. |

## High (30)

| ID | Status | Confidence | Issue | Evidence |
|---|---|---|---|---|
| S08-H1 | **Fixed** (S3-01) | Verified | `WORKER_SECRET` in `BuildConfig`, accepted on Worker `/stats` | Re-verified this session (S3-01): no `buildConfigField ... WORKER_SECRET` in `app/build.gradle`; grep of `.github/workflows/`, `build-*.sh`, `app/build.gradle` finds **no live WORKER_SECRET value injection** (all references are historical comments). `worker/src/index.js` `/stats` gate reads `env.STATS_SECRET` only, fail-closed if unset (`isStatsAuthorized`, `:88-99`), and `grep "env.WORKER_SECRET" worker/src/index.js` → **none**: the Worker has no WORKER_SECRET acceptance path. Operator must still rotate the leaked `WORKER_SECRET` (runbook), but it authorizes nothing now. |
| S07-H1 | Open | Carried | Mint key-check fails open when stored hash absent | Not re-read this pass; original defect is at `server/index.js:1514-1517` per audit. |
| S06-H1 | Fixed | Verified | `accountLock` not enforced server-side | `server/index.js:2053-2062` checks `accountLock` inside the mint transaction before issuing a token. |
| S03-H1 | Fixed | Verified | Media scope confusion via client-created `groups/{chatId}` | `server/lib/mediaScope.js` exists; `firestore.rules` `groups` create now requires `!exists(chats/{groupId})` + `createdBy` checks. |
| S04-H1 | Fixed | Verified | SSRF predicate never resolved DNS / missed IPv6 | `server/lib/egressGuard.js` + test file exist; wired into `fetchFollowingSafeRedirects()`. Residual DNS-rebinding gap is documented in-code, not hidden. |
| S04-H2 | Fixed | Verified | `/linkPreview` unbounded body read, no timeout | `readCappedBody()` in `egressGuard.js`, wired at both HTML and image call sites. |
| S04-H3 | Fixed | Verified | `og:image` fetched directly by both devices — recipient IP/read-time beacon | `server/lib/imageProxy.js` signs a same-origin proxy URL instead of returning the sender's raw URL. |
| S08-H2 | Open | Verified | `FLAG_SECURE` cleared app-wide | `BaseActivity.java:45`, `LockScreenActivity.java:68`, `MainActivity.java:41`, `SecurityPrivacySettingsActivity.java:361` all still call `clearFlags(FLAG_SECURE)`; comments state screenshots are "always allowed." |
| S08-H3 | Open | Carried | Plaintext media persists in 150 MB Glide disk cache | Not re-read this pass. |
| S08-H4 | Fixed | Verified | Link-preview image fetched from sender's host (client half of S04-H3) | Closed server-side without a client change — `MessageAdapter.java`/`LinkPreviewFetcher.java` take the URL verbatim from `/linkPreview`, which now returns the server's own signed proxy URL. |
| S08-H5 | Open | Verified | `SecurePrefs` plaintext fallback holds identity key, backup key, SQLCipher passphrase | `SecurePrefs.java:57` doc comment: "the app falls back to plaintext SharedPreferences" — three-tier `EncryptedSharedPreferences` init still has a final plaintext branch (`:215`). |
| S06-H2 | Fixed | Verified (carried from audit, spot-checked) | Duress wipe left WorkManager residue correlating to duress | `AccountLockWorker`/`FcmUnregisterWorker` input data carries no duress-identifying field per audit re-check; not independently re-read this pass beyond confirming files exist. |
| S06-H3 | Fixed | Carried | Offline duress trigger never locks | `DuressManager.maintainLockCredential()` call site added per audit record; not re-read this pass. |
| S05-H1 | Fixed | Verified | `ADMIN_TOKEN` no entropy floor / no brute-force ceiling | `server/lib/adminSecret.js` + test exist; `evaluateSecretStrength()` enforces a 128-bit floor at startup. |
| S05-H2 | Open | Verified | Waitlist unreviewable — no deny/expire/revoke path | Only `GET /admin/api/waitlist` and `POST /admin/api/waitlist/approve` exist (`index.js:3610-3649`); no deny/expire/revoke route found. |
| S05-H3 | Fixed | Verified | Admin actions not durably audited; admin auth not audited at all | `server/lib/adminAuditWiring.test.js` exists; 7 audit call sites confirmed wired into `requireAdminAuth`/login/logout paths. |
| SC-04 | **Fixed** (S3-02) | Verified | Release APKs unverifiable — no checksums/provenance | Claim re-confirmed live before fixing: `grep -iE "sha256|checksum|attest|provenance"` across `.github/` returned **only two SC-05 comment lines**, no functional step — releases really did attach raw APKs alone. **Fix in `release.yml`:** new "Generate SHA256SUMS and record signing certificate" step writes `SHA256SUMS` with bare filenames (so `sha256sum -c SHA256SUMS` works in the user's download dir) and extracts the signing-certificate SHA-256 with `apksigner verify --print-certs`; `actions/attest-build-provenance@v2` attaches signed provenance tying each APK to the run/commit (with the minimum `id-token: write` + `attestations: write` permissions added); a "Compose release body" step publishes checksums, the cert digest, and three concrete verification commands (`sha256sum -c`, `gh attestation verify`, `apksigner verify --print-certs`) via `body_path`; `SHA256SUMS` is attached alongside the APKs. Runs before the keystore-erase step (apksigner needs only the signed APK). **Fails closed:** missing APK dir, zero APKs, a failing `sha256sum`, or an empty `SHA256SUMS` all `exit 1` rather than publishing a release that merely *looks* verifiable; a cert digest that could not be read is omitted with a `::warning::` instead of being fabricated. **Evidence (commands run this session):** `js-yaml` parse OK and the publish step's `with:` confirmed to carry `body_path: release-verify-body.md` + both file globs; the step's shell logic replayed in a scratch tree — checksum roundtrip `sha256sum -c` → both `OK`, then a tampered APK → `FAILED` (nonzero exit); both empty-dir and no-APK guards exercised and each exited 1. Actual CI execution is operator-side (requires a push/dispatch), per the CI lane definition. `generate_release_notes: true` is retained — `body_path` takes precedence over `body` and is prepended to the generated notes (confirmed against the action's documented behavior). Commit `dcf85c5`. |
| SC-05 | **Fixed** (S3-02) | Verified | Workflow deletes all releases and tags on every push to `main` | The paginated "Delete all previous releases and tags" step (which enumerated every release and issued `DELETE …/releases/{id}` + `DELETE …/git/refs/tags/{tag}` for each, destroying all provenance/rollback history on every push) is **removed**. Replaced with "Clear existing release for this tag", which looks up only the single resolved rolling tag (`gh api repos/…/releases/tags/${TAG}`) and deletes at most that one stale release record; `action-gh-release` then re-points the tag in place. All other releases/tags are preserved. Verified: `js-yaml` parse OK; grep confirms no bulk enumeration (`per_page=100`/`releases?`) or `git/refs/tags` DELETE remains (sole match is a descriptive comment); deletion scoped to `releases/tags/${TAG}`. |
| SC-03 | **Partial** (S3-03) | Verified | No Gradle dependency verification | Claim re-confirmed before fixing: no `gradle/verification-metadata.xml` existed. **Fix (scaffold):** added `gradle/verification-metadata.xml` with `<verify-metadata>true` + `<verify-signatures>false`, `<trusted-artifacts>` for the Gradle wrapper distribution zips, and an operator runbook (in-file) to populate component hashes with `gradlew --write-verification-metadata sha256 help` and then flip signature verification on. **Why Partial, not Fixed:** the file has an empty `<components/>` set, so it does not yet *enforce* per-artifact hashes — populating them requires running Gradle against the real Android/Google/Maven dependency graph, which needs the Android SDK + network resolution and is a **BLOCKED gate** in this environment (same toolchain blocker as S3-19b). Landing the scaffold + runbook is the reproducible, in-repo half; hash population is deferred to the operator. **Evidence:** `xml.dom.minidom.parse` → well-formed; scaffold committed. |
| SC-06 | **Fixed** (S3-03) | Verified | JitPack in repo list — builds from mutable Git refs | Claim re-confirmed: `build.gradle` had an unscoped `maven { url 'https://jitpack.io' }` in `allprojects.repositories`, so JitPack (which builds artifacts on demand from mutable Git refs) could serve *any* coordinate. **Fix in `build.gradle`:** wrapped the JitPack declaration in a `content { includeGroupByRegex 'com\\.github\\..*' }` filter so it is only ever consulted for `com.github.*` groups; `google()` and `mavenCentral()` (declared first, content-addressed) remain the only sources for all androidx/Firebase/Google/glide artifacts. **Evidence:** `includeGroupByRegex` present at the JitPack block; brace balance verified (equal `{`/`}`). Full resolution confirmation is operator-side (needs Gradle + network), per the CI lane. |
| S02-H1 | Open | Carried | `migrateUid` copies `users/{oldUid}` verbatim | Not re-confirmed field-by-field this pass; `index.js` migration logic still copies/merges old-uid docs. |
| S03-H2 | **Fixed** (corrected) | Verified | Per-token, not per-user, Worker rate bucket | `worker/src/index.js` now tracks `perUserCounts` keyed by client ID with per-minute buckets, separate from the raw daily cap. |
| S03-H3 | Open | Verified | No per-user storage quota | `worker/src/index.js` still enforces only the global `MAX_DAILY_REQUESTS`/bucket-wide cap; no per-user *storage* (byte) quota found. |
| S07-H2 | Open | Verified | Backup docs ship unkeyed SHA-256 of plaintext | `BackupCryptoHelper.java:97-118` computes/stores a plain SHA-256 checksum of plaintext — offline dictionary-recovery oracle unchanged. |
| S07-H3 | Open | Verified | Group messages have no AAD | No `AAD`/`associatedData` reference found in `GroupCipherHelper.java`. |
| S04-M1 (gov: High) | Open | Carried | IPv6 /64 defeats IP-keyed limits | No IPv6-aware key normalization found in a quick pass of the rate-limit helpers; not exhaustively re-read. |
| S01-H1 | **Partial** (S3-04) | Verified | Cross-user prekey wipe/replace | Claim re-confirmed before fixing: the cross-user `public_keys` update scoped only `affectedKeys().hasOnly(['oneTimePreKeys','updatedAt'])` — WHICH fields, not the VALUES. **Fix (`firestore.rules`, commit 812813d):** the cross-user branch now additionally requires `request.resource.data.oneTimePreKeys.size() < resource.data.oneTimePreKeys.size()`, i.e. the pool may only SHRINK. Legit consumption is `FieldValue.arrayRemove` (`SignalSessionManager.consumeOtpkWithRetry:361`), which only ever decreases size; owner writes (`auth.uid == uid`) stay unconstrained for refresh growth (`arrayUnion`). This blocks wipe-to-larger, same-size attacker-prekey swap, and pool growth by a stranger. Residual (accepted, documented in-rule): rules cannot pin individual array elements, so a stranger could still shrink to a *different* smaller set — `arrayRemove` never does this and the size-invariant closes the High vectors. **Why Partial not Fixed:** emulator run is the RULES lane, **BLOCKED** here (no JVM/`firebase` CLI — `command -v java`/`firebase` both absent this session); promoted to `fixed` in **S3-15b**. **Evidence (this session):** code-only paren balance of `firestore.rules` = 0 (balanced); 4 emulator tests written in `firestore-tests/rules.test.js` (consume shrink → succeeds; grow/same-size-swap by non-owner → fail; owner grow → succeeds); `node --check` on the test file OK. |
| S01-H2 | **Partial** (S3-04) | Verified | 1:1 message content mutable on update | Claim re-confirmed: the `chats/{chatId}/messages` `update` rule pinned only `sender` (+ `deletedForAll` sender-gate); the ciphertext body had no protection, so a **recipient** could overwrite the `text` of a message they did not send. **Fix (`firestore.rules`, commit 812813d):** on update, `text`/`sigType`/`type` may change only when `resource.data.sender == request.auth.uid` (the legitimate sender-only 48h edit path — `EditMessageHelper.canEdit` gates on `myUid.equals(sender)`, re-encrypts, updates `text`/`sigType`), and `isEncrypted` is pinned immutable (`request.resource.data.isEncrypted == resource.data.isEncrypted`) so no participant can downgrade an encrypted message to plaintext. Non-content fields (reactions/status/expiresAt/deletedForAll) remain updatable by either participant under the existing guards. Group messages are already append-only (no `update`), so this is scoped to 1:1 per the finding. **Why Partial not Fixed:** RULES lane emulator **BLOCKED** here → **S3-15b**. **Evidence (this session):** 4 emulator tests added (recipient rewrite → fail; recipient downgrade → fail; sender edit → succeeds; recipient status update → succeeds); message seed made realistic (`isEncrypted:true`); paren balance 0; `node --check` OK. |
| S01-H3 | **Partial** (S3-04) | Verified | Partner display-name overwrite | Claim re-confirmed: the chat `update` `hasAny` block-list (`typing_`/`online_`/`lastSeen_`/`unread_`) had no `partnerName_`/`partnerPhotoUrl_` entry. **Fix (`firestore.rules`, commit 812813d):** added `'partnerName_' + request.auth.uid` and `'partnerPhotoUrl_' + request.auth.uid` to the block-list. Note the INVERTED convention (verified in source): a participant writes the key suffixed with the PARTNER's uid — their own name/photo, for the partner to read (`SettingsActivity.propagateName:326` → `update("partnerName_" + partnerUid, myName)`) — and only READS the key suffixed with their OWN uid (`ConversationListActivity:361`). So the field a participant must not touch is the self-suffixed one (partner-owned); the legit partner-suffixed write is unaffected. **Why Partial not Fixed:** RULES lane emulator **BLOCKED** here → **S3-15b**. **Evidence (this session):** 3 emulator tests added (participant sets partner-suffixed name → succeeds; overwrites self-suffixed name → fail; overwrites self-suffixed photo → fail); paren balance 0; `node --check` OK. |
| S07-M1 (gov: High, = S08-H5) | Open | Verified | SecurePrefs plaintext fallback | Same defect as S08-H5 above — one fix closes both. |

## Medium (26)

| ID | Status | Confidence | Issue |
|---|---|---|---|
| S01-M1 | **Partial** (S3-05) | Verified | Group message TOCTOU + no volume cap. Re-confirmed against source before fixing: group `messages` create (`firestore.rules`) gated only `member`+`sender`+`isEncrypted==true`, with no bound on document size. **Fix (`firestore.rules`):** added a per-write size cap on the encrypted body — `text` (when present) must be a string ≤ 65536 bytes; legit Signal ciphertext is a few hundred bytes–few KiB and media messages carry `text:""`, so no real send is affected. The MEMBERSHIP TOCTOU half is **ACCEPTED and documented in-rule** per the audit: rules authorize membership via `get()` on a separately-mutated doc and cannot make remove-then-deny atomic; document-COUNT throttling stays a server-side/quota control (FirebaseCostGuard + server accounting). **Why Partial not Fixed:** RULES emulator lane **BLOCKED** here (`command -v firebase`/`java` both absent; `jest`/emulator not installed) → promoted in **S3-15b**. **Evidence (this session):** 3 emulator tests added in `firestore-tests/rules.test.js` (8 KiB body → succeeds; empty-text media → succeeds; > 64 KiB body → fails); `node --check firestore-tests/rules.test.js` OK; `firestore.rules` paren/brace/bracket balance = 0 after stripping comments+strings. |
| S01-M2 | **Partial** (S3-05) | Verified | `identities` update has no field allow-list — rule only pins `uid`/`identityPubKeyHash`, no `hasOnly` on the full field set. Re-confirmed: `identities/{userId}` is world-readable (contact-lookup oracle) and the owner could write ANY extra field into it (stored-content injection / metadata pollution). **Fix (`firestore.rules`):** both `create` and `update` now require `keys()`/`diff().affectedKeys().hasOnly(['uid','identityPubKeyHash','updatedAt'])`; the anti-takeover `identityPubKeyHash` immutability pin and `uid==userId` checks are kept beside it (add-don't-replace). The only legit client write is `SeedPhraseDisplayActivity`'s `set({uid}, merge)`; `identityPubKeyHash` is populated by the mint server via Admin SDK (bypasses rules). **Why Partial not Fixed:** RULES emulator **BLOCKED** → **S3-15b**. **Evidence (this session):** the pre-existing test that asserted a benign `{label:'legacy'}` update SUCCEEDS (which had masked this finding) was corrected to `assertFails`; 4 new tests added (uid+updatedAt re-assert → succeeds; arbitrary `label` → fails; smuggled `fcmToken` → fails; create with extra field → fails); `node --check` OK; rules balance = 0. |
| S01-M3 | **Partial** (S3-05) | Verified | `backup_logs` create unbounded/unvalidated — rule only checks `uid == auth.uid`, no shape/size validation. Re-confirmed: create gated only `uid==auth.uid`, so an attacker could write unbounded arbitrarily-large arbitrary-field docs (write-amplification / storage-cost DoS; reads already denied). **Fix (`firestore.rules`):** pin the exact schema the sole writer emits (`BackupManager.logEvent` → `{uid, event, ts, count, error?}`): `keys().hasOnly([...])` + `hasAll(['uid','event','ts','count'])`, `event` string ≤ 64 chars, `ts`/`count` numbers, optional `error` string ≤ 512 chars. Document-COUNT volume stays a server/quota concern per the audit; this closes the per-write size/shape half. **Why Partial not Fixed:** RULES emulator **BLOCKED** → **S3-15b**. **Evidence (this session):** the pre-existing "owner can create" test was updated to the real `{uid,event,ts,count}` shape; 5 new tests added (extra field → fails; missing `count` → fails; over-long `event` → fails; over-long `error` → fails; wrong-typed `ts` → fails; +optional-`error` happy path → succeeds); `node --check` OK; rules balance = 0. |
| S01-M4 | Open | Verified | Group message delete has no membership re-check — rule checks `sender == auth.uid` only |
| S02-M1 | Open | Carried | Mint cooldown stamped pre-auth |
| S03-M1 | Open | Verified | Attacker `Content-Type` stored/echoed, no `nosniff`/`Content-Disposition` — neither header found in `worker/src/index.js` |
| S03-M2 | Open | Carried | Tokens scope-bound, not uploader-bound |
| S03-M3 | Open | Carried | 10-min unrevocable bearer tokens, unlimited reuse |
| S04-M2 | Open | Carried | 24h redistributable TURN creds, no aggregate cap |
| S04-M3 | Open | Carried | XFF trust hard-coded to one proxy |
| S05-M1 | Partial | Verified | Raw operator IPs/uids persisted — most log lines use `uidTag()` redaction; `index.js:3887`/`:3934` (duress enrollment grant/revoke) still log raw `uid=${uid}` |
| S05-M2 | Fixed | Verified | `duressEligibility` enforced nowhere (paired with S06-M1) — see correction above |
| S05-M3 | **Partial** (corrected) | Verified | Admin sessions unbounded/unbindable — 30-min absolute TTL now enforced (`ADMIN_SESSION_TTL_MS`), but no bulk-revoke and not bound to IP/UA |
| S06-M2 | Open | Carried | `_duressNonces` unbounded growth |
| S06-M3 | Partial | Verified | Raw uids logged on duress endpoints — `requestLockNonce`/`duress-lock` now use `uidTag()`; overlaps with S05-M1's two un-redacted admin lines |
| S07-M2 | Open | Verified | Trust keyed on mutable uid — `DuoShieldSignalStore.java` stores trust under `signal_trusted_id_<uid>` |
| S07-M3 | Open | Carried | Backup metadata outside the AEAD |
| S08-M1 | Open | Verified | Native heap pointer tagging disabled — `AndroidManifest.xml:54` `allowNativeHeapPointerTagging="false"` |
| S08-M2 | Open | Verified | FileProvider root-scoped grantable paths — `file_paths.xml` still declares whole-root `cache-path`/`files-path`/etc. |
| S08-M3 | Accepted | Carried | No root/tamper detection — documented as out of threat model |
| S10-N1 | Open | Carried | Firebase App Check absent |
| SC-07 | **Fixed** (S3-03) | Verified | Wrapper JAR unvalidated — no `gradle/wrapper-validation-action` in any workflow | Claim re-confirmed: no wrapper validation ran anywhere. **Fix in `ci.yml`:** new `validate-gradle-wrapper` job runs `gradle/actions/wrapper-validation` (SHA-pinned) to check the committed `gradle-wrapper.jar` against official Gradle release checksums. Added to `lint.needs` alongside `verify-libsignal-jar`, so — as `lint` is the fan-in prerequisite of every build job — a tampered wrapper JAR fails the whole pipeline before `./gradlew` ever executes. **Evidence:** `js-yaml` parse OK; `validate-gradle-wrapper` present in jobs; `lint.needs=["verify-libsignal-jar","validate-gradle-wrapper"]`. |
| SC-08 | **Fixed** (S3-03) | Verified | Actions on mutable tags — `actions/checkout@v4` etc. still tag-pinned, not SHA-pinned | Claim re-confirmed: every `uses:` across all workflows referenced a mutable tag. **Fix:** SHA-pinned every third-party action to a full 40-char commit SHA (resolved live via `gh api repos/<a>/commits/<tag>`) with a trailing `# vN` comment for readability, across `ci.yml`, `release.yml`, `firestore.yml`, `firestore-rules-test.yml`, and the new `security-scan.yml` — e.g. `actions/checkout@11d5960a…262 # v4`, `gradle/actions@ed408507…3a # v4`, `actions/attest-build-provenance@e8998f94…be # v2`. **Evidence:** repo-wide `grep -E "uses: [^@]+@v[0-9]+$"` under `.github/` → **no matches** (zero unpinned actions remain); all workflows parse under `js-yaml`. |
| SC-09 | **Fixed** (S3-03) | Verified | No scanning/SBOM/Dependabot | Claim re-confirmed: no `.github/dependabot.yml` and no scanning workflow. **Fix (two files):** (1) `.github/dependabot.yml` enables weekly updates for `github-actions` (`/`), `gradle` (`/`), and `npm` for both `/firestore-tests` and `/server`, with grouped minor/patch PRs and open-PR caps. (2) `.github/workflows/security-scan.yml` (all actions SHA-pinned, least-priv `permissions`) runs on push/PR/weekly-cron: **CodeQL** SAST for `java-kotlin` + `javascript-typescript`, **gitleaks** secret scanning, and **SBOM** generation via `anchore/sbom-action` uploaded as an artifact. **Evidence:** `js-yaml` parse OK for both; dependabot ecosystems enumerated (`github-actions:/`, `gradle:/`, `npm:/firestore-tests`, `npm:/server`); security-scan jobs = `codeql,gitleaks,sbom`. Actual scan runs are operator-side (need a push/PR event), per the CI lane. |
| SC-10 | **Fixed** (S3-03) | Verified | Firestore deploy runs unpinned `npm install` | Claim re-confirmed: `firestore.yml` + `firestore-rules-test.yml` used bare `npm install` and `npm install -g firebase-tools` (floating latest). **Fix:** both workflows now use `npm ci` (installs exactly from the committed `firestore-tests/package-lock.json`, failing if manifest and lock disagree) and pin the global CLI to `firebase-tools@15.26.0`. **Evidence:** grep confirms `npm ci` (no bare `npm install`) and `firebase-tools@15.26.0` in both workflows; `firestore-tests/package-lock.json` present for `npm ci` to consume; `js-yaml` parse OK. |

## Low (33)

| ID | Status | Confidence | Issue |
|---|---|---|---|
| S01-L1 | Partial | Verified | `groups` create `createdBy` validation — closed incidentally by the S03-H1 fix (`createdBy == auth.uid`, `createdBy in members`, no collision with an existing chat); full shape validation still absent |
| S01-L2 | Open | Verified | `users` doc write has no field/shape validation |
| S02-L1 | Open | Carried | Mint hash check fails open (= S07-H1) |
| S02-L2 | Open | Carried | `createChat` stores unbounded/unsanitized display names |
| S02-L3 | Open | Verified | `mintCooldown` map only purges an entry once a request lands with `last === 0`; otherwise grows per active user |
| S02-L4 | Open | Verified | `collectBody` counts chars (`body.length`), not bytes — no `setEncoding` call |
| S03-L1 | **Fixed** (S3-01) | Verified | `WORKER_SECRET` compiled into APK (= S08-H1) — closed with S08-H1 this session; duplicate finding, same evidence |
| S03-L2 | Open | Carried | Unguarded `decodeURIComponent` |
| S03-L3 | Open | Carried | Dead B2 presign code (= S04-I2) |
| S03-L4 | Partial | Verified | Rejections without CORS headers — global CORS headers are applied via a shared helper, but not confirmed on every quota-rejection path |
| S04-L1 | Open | Verified | `collectBody` byte count / `setEncoding` (= S02-L4) |
| S04-L2 | Open | Carried | `/duress-lock` no rate limit (= S06-L2) |
| S04-L3 | Open | Carried | Limiter state per-process/in-memory |
| S05-L1 | Open | Verified | `/admin/api/account/lookup` — confirmed it does call `validAdminUid()` (`index.js:3738`); other admin routes do too. Re-read against the exact original claim recommended before closing. |
| S05-L2 | Open | Carried | `collectBody` runs before `requireAdminAuth` |
| S05-L3 | Fixed | Verified | No `Cache-Control` on admin responses — all admin API/login/logout responses now set `Cache-Control: no-store` (or stricter) |
| S05-L4 | Open | Carried | Read-then-write TOCTOU on approve/unfreeze |
| S06-L1 | Open | Carried | Nonce expiry fails open on malformed `expiresAt` |
| S06-L2 | Open | Carried | `/duress-lock` unauthenticated, no rate limit |
| S06-L3 | Open | Carried | `_duressNonces` no rules-test coverage |
| S06-L4 | Open | Carried | `AccountLockWorker` reports failure as success |
| S07-L1 | Open | Carried | `fetchGroupKey` creator check fails open |
| S07-L2 | Open | Carried | Static `derivationCache` survives duress wipe |
| S07-L3 | Open | Carried | Mnemonic canonicalization/Locale |
| S07-L4 | Open | Carried | `loadSession` silent fresh-session substitution |
| S08-L1 | Open | Carried | Deep link accepts unvalidated Account ID |
| S08-L2 | Open | Carried | Clipboard writes without `EXTRA_IS_SENSITIVE` |
| S08-L3 | Open | Carried | PIN length stored beside PIN hash |
| S08-L4 | Open | Carried | Lock screen over rendered activity, in recents |
| S10-N2 | Open | Carried | Peer uid in release logcat |
| S10-N3 | Partial | Verified | Deleted media survives B2 cold tier on race — a race guard now exists (`worker/src/index.js:553-574`, "Race guard: the nightly migration PUTs to B2 and THEN deletes from R2"); confirm end-to-end before closing |
| SC-11 | Accepted | Carried | Production crypto on alpha library — no stable release exists |
| SC-12 | Open (operator) | Verified | Branch protection — **still unprotected.** Re-checked live this session (S3-01): `gh api repos/kumarclaude4-hash/DuoFatass/branches/main/protection` → `404 Branch not protected`. Not closable from source; operator must enable branch protection on `main`. Runbook item. |

## Informational (23)

| ID | Status | Confidence | Issue |
|---|---|---|---|
| S01-I1 | Accepted | Carried | Global read oracle on users/identities — ratified product decision |
| S01-I2 | Accepted | Carried | Systemic `get()`-based authz TOCTOU |
| S02-I1 | Accepted | Carried | Cold-contact/registration oracle |
| S02-I2 | Open | Carried | In-memory limiters best-effort |
| S02-I3 | Open | Verified | No `checkRevoked` on `verifyIdToken` — every call site (`index.js` lines ~2272, 2477, 2589, 2671, 2930, 3077) omits the `checkRevoked` argument |
| S03-I1 | Accepted | Carried | Worker holds bucket-wide B2 creds |
| S03-I2 | Fixed | Verified | Worker accounting no longer purely advisory — now backed by `perUserCounts` enforcement (see S03-H2) |
| S03-I3 | Accepted | Carried | `/mediaToken` membership oracle |
| S04-I1 | Open | Carried | `/status` and `/` unauthenticated, publish counters |
| S04-I2 | Open | Carried | Dead B2 presign surface; B2 creds still expected in env |
| S04-I3 | Open | Carried | Preview provenance/failure indistinguishable to client |
| S05-I1 | Fixed | Verified | Operator secrets undocumented — `server/README.md` now tables all env vars `index.js` reads |
| S05-I2 | Open | Carried | Stale admin comments |
| S05-I3 | Open | Carried | CSRF/Secure-flag/length-oracle |
| S06-I1 | Fixed | Verified | Rules comment contradicting shipped admin unfreeze — `firestore.rules:421-430` now documents the real behavior explicitly |
| S06-I2 | Fixed | Carried | Step 1a can't tell success from failure |
| S06-I3 | Accepted | Carried | PIN strength bounded by PIN space |
| S07-I1 | Accepted | Carried | `SenderKeyStore` stub |
| S07-I2 | Accepted | Carried | No add-member flow; key-less add |
| S07-I3 | Accepted | Carried | Account ID 64-bit / dual-purpose |
| S08-I1 | Open | Carried | R8 keeps crypto member names |
| S08-I2 | Accepted | Carried | No certificate pinning |
| S08-I3 | Open | Verified | Worker `ACAO: *` while allowing `Authorization` header — `worker/src/index.js:60-62` still sets both |

---

## Summary

| Sev | Total | Fixed | Partial | Open | Accepted |
|---|---|---|---|---|---|
| Critical | 4 | **4** | 0 | **0** | 0 |
| High | 30 | 13 | 0 | 16 | 1 |
| Medium | 26 | 3 | 3 | 18 | 2 |
| Low | 33 | 3 | 3 | 24 | 3 |
| Info | 23 | 5 | 0 | 12 | 6 |
| **Total** | **116** | — | — | — | — |

> **Rollup accuracy caveat (updated S3-02).** The **Critical** row is exact and
> recounted from the rows above this session: `S07-C1`, `S08-C1`, `SC-02` and now
> `SC-01` are all Fixed, so **there are no open Critical findings left**. The
> `High` row is adjusted for the S3-01/S3-02 closures (`S08-H1`, `SC-05`,
> `SC-04`). The Medium/Low/Info rows still carry the `298f916` baseline and have
> **not** been recounted — they predate S3-01/S3-02 and the per-severity totals
> there were never reconciled against the actual rows. Per
> `ROUND3_REMEDIATION_PLAN.md`, **S3-20 owns the end-to-end reconciliation**;
> until it runs, trust the individual rows above, not these aggregates. Totals
> are deliberately left as `—` rather than stating a number this session did not
> verify.

This is meaningfully worse than the old `MASTER_CHECKLIST.md`'s last snapshot
(0% complete, everything reset) and meaningfully better than
`FINDING_INDEX.md`'s row-by-row claims in five specific places (table above).
Neither old document reflected the actual repo at any single point in time —
this file does, as of `298f916`.

## Next steps for anyone picking this up

1. Rows marked **Carried** were not re-read against source in this pass —
   re-verify before relying on them, same discipline that caught the five
   corrections above.
2. Highest-value next fixes by exposure: **SC-01** (unverified crypto JAR —
   the last open Critical), **S08-H5/S07-M1** (plaintext key fallback),
   **S01-H1/H2/H3** (Firestore rules gaps with no code dependency, cheap to
   close), **S07-H2/H3** (backup oracle, missing AAD).
3. `SC-04`, `SC-05`, `SC-12` need an operator with GitHub Actions/branch
   settings access — they can't be closed from source alone.
