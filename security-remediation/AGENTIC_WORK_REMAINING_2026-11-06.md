# Agentic Work Remaining — current handoff, 2026-11-08

Repository: `kumarclaude4-hash/DuoFatass`  
Base branch: `main`  
Current main HEAD: `b596e965d4be54b4ea8079b4036012e68188f865` (PR #277 merge). See `security-remediation/INDEPENDENT_REVERIFICATION_2026-11-08.md` for the fresh evidence snapshot.

Recent merged work:
- PR #261 — invite lifecycle invalid-state fail-closed checks (S05-H2).
- PR #264 — invalid invite rows now expose the audited revoke action (S05-H2).
- PR #265 — generic Settings PIN-change failure response and durable successful/failed-attempt throttle (S06-H5); CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed.
- PR #267 — Firebase App Check / Firestore Rules correction (S10-N1), merged as `8d0082d004993278645f3eb7a72c43a8cdc14e59`. Exact PR-head CI [42443323887](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323887), Security [42443323988](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323988), Firestore Rules [42443323886](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323886) and Firestore Rules/server transactions [42443323962](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323962) all passed.
- PR #269 — S10-N2 release-log taint analysis now fails closed for bodyless app-owned abstract/interface/native helper declarations; merged as `afdf2463c496f14a5c33ee5694fc47eff97d5e3d`. Exact-head CI [42446136767](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136767) and Security [42446136770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136770) passed. S10-N2 remains Partial for inheritance/virtual dispatch, reflection, external callbacks and physical release-logcat residuals.
- PR #270 — refreshed the security handoff/tracker/sign-off snapshot after #269; merged as `1a425ad0aa2e7c36fa66a2afe4e46d85e78ee514`.
- PR #271 — bounded S07-L2 app-owned memory hygiene (defensive cache fingerprint copy/zeroization, serialized derivation/cache eviction, cleanup of derivation/HKDF intermediate arrays). Exact-head CI [42448400756](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400756) passed 10/10 jobs; Security [42448400818](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400818) passed 3/3; merged as `87e558c25bd810dafb8b47d97e52c2022a4a94fe`. S07-L2 remains Partial because native-key destruction is finalizer-timed.
- PR #272 — post-#271 agent handoff refresh, corrected libsignal dependency topology and 12-route inventory; merged as `0a985fb39570fea3b030da76417e67a01790f8b5`. PR #273 was a duplicate documentation PR and was closed unmerged.
- PR #274 — S10-N2 typed cross-class field taint correction. The analyzer now carries owner-qualified field-write summaries and resolves reads through the receiver's declared app-owned type; regression: sensitive `Profile.getUserId()` → `CrossClassHolder.setMemo()` → `Log.w(holder.memo)`. It also removes retry-count/protocol-type details from a `ChatMediaActivity` warning. Exact head `a26d4f1140532879c4e74590e6bb1ec16a34ebc4`; CI [42450251567](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251567) passed 10/10, Security [42450251553](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251553) passed 3/3; merged as `d1ee9ac01e0809a6e398958be423097bc082e9d7`. S10-N2 remains Partial for inheritance/virtual dispatch, arbitrary field aliases, reflection, native methods, third-party callbacks and physical release-logcat QA.

- PR #275 — security handoff refresh; exact head `e9ecaf3e4590dbcf7e701a628f1b4843d4a3f527`, merge `b401be50892f2e1adc55235bf6a840daa55669a1`; CI [42453798811](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453798811) 10/10 and Security [42453798810](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453798810) 3/3.
- PR #276 — S10-N2 fails closed for known app-owned subtype overrides; exact head `031ea9d0ecfbeba06bc9d14c2d5bbdaf0a8e58ed`, merge `79aacea3a05131f7c5afcc6c64eef182322eda3f`; CI [42453996138](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996138) 10/10 and Security [42453996152](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996152) 3/3.
- PR #277 — S10-N2 treats ambiguous same-name/same-arity app-owned helper returns as unknown/tainted; exact head `28ed49f2ae63d134eedbb6a73aa4cfa2930f9abc`, merge `b596e965d4be54b4ea8079b4036012e68188f865`; CI [42454608770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608770) 10/10 and Security [42454608761](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608761) 3/3. S10-N2 remains Partial for the explicit residuals recorded in the tracker.
- PR #266 was intentionally closed unmerged. Its documentation changes are not on main.

There are **no open code PRs** and **no open issues** after PR #277 merged. Some historical PRs were intentionally closed unmerged because they were superseded or duplicate (#266 per owner instruction; #273 duplicate of #272; #262 implementation carried by #265). Operator/staging, owner-decision and feature-complete physical-device gates remain open. The old #253/#254/#258 table and their historical CI blockers are superseded: #253's invite lifecycle work was replaced by merged #261, #254's range allowance was superseded by merged #256, and #258 was closed; do not reopen those branches. PR #262 was closed unmerged; its S06-H5 implementation was carried by merged PR #265.

The canonical tracker at main HEAD `b596e965d4be54b4ea8079b4036012e68188f865` has **116 findings: 86 Fixed, 17 Partial, 1 Open, 12 Accepted**. Use `BUG_TRACKER.md`, [Next Work Report](NEXT_WORK_REPORT_2026-11-08.md) and [Independent Re-verification](INDEPENDENT_REVERIFICATION_2026-11-08.md) for the current state; conflicting 2026-11-06/07 narrative below is historical.

**S10-N1 correction:** App Check is enforced at the Firebase service layer, before Firestore Rules evaluation. Firestore Rules cannot inspect App Check token metadata; `request.app` is unsupported and must not be added to Rules. PR #267 removed the invalid helper and false-positive emulator test arrangement. Console enforcement, monitoring review and isolated real-token staging acceptance remain operator work.

`security-remediation/FINAL_SIGNOFF.md` remains **PENDING**. Physical-device acceptance remains deferred until the owner explicitly declares the application feature-complete.

## 1. Binding instructions

1. Start from the latest main on a fresh branch. Do not commit directly to main, force-push, or reuse an unrelated PR branch.
2. Keep PRs narrow and independently reviewable. Add a regression test for every security behavior changed. Never promote a finding based on comments, source inspection alone, or a build that does not execute the relevant test.
3. Do not perform manual device testing yet. The owner explicitly deferred physical-device and app-flow acceptance until the application is feature-complete. Do not install APKs, begin manual test sessions, or spend time on the device matrix before that milestone. Existing automated CI emulator jobs remain enabled and must keep passing.
4. You may continue source-level work, pure JVM tests, backend tests, static analysis, lint/build checks, Firestore emulator tests and isolated local Worker tests. These do not count as physical-device acceptance.
5. DuoShield identity is seed-derived. Never request or use a real user's mnemonic, seed or private key. Use synthetic fixtures only in an isolated test/staging project with explicit authorization.
6. Never print, log, upload or commit mnemonics, seeds, private keys, Firebase ID tokens, session cookies, Worker secrets, service-account JSON, B2 credentials, or any bearer secret. Evidence may contain variable names, boolean presence checks, timestamps and redacted resource identifiers only.
7. Do not deploy production server, Worker, Firestore rules, Android APK, or change live credentials/configuration as a side effect of a code PR. Prepare runbooks and staging instructions; an authorized owner performs live changes.
8. Keep security-remediation/FINAL_SIGNOFF.md PENDING. Green CI is not production security acceptance.
9. Read security-remediation/SESSION_PROTOCOL.md, START_HERE.md, ROUND3_REMEDIATION_PLAN.md, the current BUG_TRACKER.md row, the relevant audit session report, source and tests before changing a finding.

## 2. Verified baseline

Historical main baseline at the PR #267 closeout: `8d0082d004993278645f3eb7a72c43a8cdc14e59`.

PR #267 exact-head verification:
- CI [42443323887](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323887): passed Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified release R8, Server + Worker tests, release-log AST, Gradle wrapper and vendored-libsignal integrity.
- Security [42443323988](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323988): passed CodeQL, Gitleaks and SBOM.
- Firestore Rules [42443323886](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323886): passed.
- Firestore Rules/server transaction integration [42443323962](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323962): passed, 200 Rules tests and 6 server transaction tests.

Post-merge main workflows:
- Security [42445582762](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582762): passed.
- Firestore rules [42445582759](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582759): passed.
- Firestore Rules Tests [42445582747](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582747): passed.
- Main CI [42445582763](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582763): in progress when this addendum was prepared. Refresh it before claiming full post-merge CI completion.

Latest PR #265 evidence (S06-H5): exact head `ed8e47550763cf7766acb0f6ef3d53dc15039df3`, merge `32175ccf86342a6c517459a0e2507f14c4d064e8`; CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed.

## 3. Remaining agentic/operator work — no physical-device acceptance yet

### A. S10-N2 — release-log guard and bounded interprocedural taint analysis (code merged)

PR #244 introduced the JDK compiler-AST release-log guard; PR #246 hardened identifier and tag/message coverage; PR #248 added method-local alias/container tracking; PR #251 added the bounded helper-summary extension and reduced `DeviceSecurityGate` log detail. PR #251 is merged to main at `fc3dcd948a2410887c23ff8c29913c6c5d92604a`.

The merged analyzer now:
- Tracks local aliases, sticky reassignment, concatenation, StringBuilder/StringBuffer flows and common Map/Bundle/JSON/container insertion and retrieval.
- Uses bounded fixed-point summaries for uniquely resolvable app-owned helper return values, parameter pass-through and same-class field writes/reads, including helper-mediated flows.
- Accepts explicit redaction only when source analysis proves the return is redacted.
- Treats ambiguous overloads and unresolved/external dispatch conservatively; recursive summaries are iterative and fail closed if not converged.
- Checks both `Log.w/e/wtf` tag and message arguments.

**Executed evidence on exact PR #251 head `d6041db2033b1950cdf006b900ef5cd8f2d95eba`:**
- CI [42363163588](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42363163588): **10/10 jobs passed**.
- Security Scan [42363163447](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42363163447): **3/3 jobs passed**.
- AST scan: **204 production Java files; 414 Log.w/Log.e/Log.wtf calls; zero violations**.
- Espresso: **4 tests executed**; API 35 predictive-back: **1 focused test executed**.
- Android unit tests, lint, debug APK, release R8/mapping, Server/Worker tests, CodeQL, Gitleaks, SBOM, dependency-integrity and Cloudflare Worker build validation passed.

**Remaining:** retain S10-N2 as **Partial**. The analyzer is syntax-only, not symbol-resolved whole-program taint analysis; inheritance/virtual dispatch, reflection, cross-class field aliasing, native methods, unresolved external calls and third-party callback internals remain outside proven coverage. Complete residual source review and physical-device release-logcat QA only after the owner declares the app feature-complete. Do not use real user seeds, tokens or credentials. Keep `security-remediation/FINAL_SIGNOFF.md` **PENDING**.

### B. S03-M3 — decide the media capability-token residual

Current token format carries a random jti; DELETE replay is single-use and fail-closed. The 10-minute TTL remains, and READ/WRITE tokens remain reusable to preserve normal upload/download/retry behavior. The analysis and owner-decision template are documented in `security-remediation/S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md`. A dated decision record is now available at `security-remediation/S03-M3_MEDIA_CAPABILITY_TOKEN_DECISION.md`.

The decision record compares the current residual, a versioned one-shot protocol and TTL-only shortening; it recommends retaining the current behavior until the owner either ratifies the residual or approves a protocol redesign. Do not shorten TTL or make READ/WRITE single-use without tracing Android upload, retry, range-read and re-fetch flows. The owner decision remains **PENDING**, so keep S03-M3 Partial.

### C. S10-N3 — expand deterministic R2/B2 race tests

The Worker suite now also has deterministic failure-injection cases for changed ETag, transient post-PUT R2 HEAD failure, B2 PUT failure and failed compensating B2 DELETE. The handler was corrected so a failed HEAD is not treated as proof of deletion: it retains the R2 source, counts its bytes and lets a later cron retry. Failed compensation now emits an operator-visible, key-free diagnostic, and migration warnings no longer expose object keys. PR #242 CI has now executed the new tests successfully: the Server + Worker tests job passed on commit `92cf469dd0b574cf87816cfd0d9c3df59be5cb20` (workflow run `42181494097`). The tracker records the four added regressions as CI-verified; S10-N3 remains Partial pending isolated live Worker/R2/B2 cron-retry acceptance.

Remaining: live isolated Cloudflare Worker + R2 + B2 acceptance, including cron retry/recovery and confirmation that the production runtime behaves like the deterministic fakes. Never use production bucket credentials in local tests. Keep S10-N3 Partial until isolated live-runtime evidence is recorded.

### D. S07-L2 — document and prove the key destruction boundary

WipeHelper calls `SeedPhraseHelper.clearDerivationCache()` on all local wipe paths. PR #271 also serializes derivation with cache eviction, defensively copies and zeroes the cached fingerprint, and zeroes app-owned derivation/HKDF intermediates; its exact-head CI and Security passed (see the current next-work report). The pinned libsignal 0.54.1 `ECPrivateKey.finalize()` still releases the native handle only on finalization; Rust `StaticSecret` zeroizes on drop, but Java exposes no deterministic `close()`/`destroy()` API and the native `PrivateKey` is cloneable. A later derivation after wipe can repopulate the cache, so lifecycle code must block new identity work. Provider-owned copies cannot be cleared by this Java code. Keep Partial; do not reflectively mutate native internals or invent a synchronous-erasure guarantee. See `security-remediation/sessions/SESSION-S07-L2-LIBSIGNAL-BOUNDARY-2026-11-06.md`.

### E. S05-M3 — durable admin-session emulator coverage

Existing JVM/server tests cover independent store instances, cross-instance validation, single/global revocation, concurrent global revoke, idle/absolute expiry and explicit Firestore outage propagation (callers can fail closed). The Firestore emulator integration covers durable transactional behavior across independent instances. The remaining gap is staging/production acceptance with deployed rules/indexes/TTL, restart/outage behavior in the actual environment and authorized synthetic session cookies; do not use a real operator cookie. Do not call emulator evidence production verification; production Firestore rules/index/TTL deployment and staging acceptance remain external.

### F. S02-I2 — ensure every authenticated route uses the shared limiter

The server uses the Cloudflare Durable Object through adminLockoutWorkerClient; local tests cover request construction, malformed responses, timeouts and fail-closed errors. The current PR adds a route-inventory regression to `server/lib/revokedIdTokenWiring.test.js`: all 12 protected routes must call `checkAuthRateLimit()` with their dedicated endpoint bucket, and the `AUTH_RATE_LIMITS` map must exactly match the inventory. The route-inventory guard passed in PR #242's Server + Worker CI (run `42181494097`). Its source-level coverage is verified; S02-I2 remains Partial until an authorized operator reads back the Render `ADMIN_LOCKOUT_WORKER_URL` / `ADMIN_LOCKOUT_WORKER_SECRET` pairing with the Worker's `ADMIN_LOCKOUT_SECRET`, without exposing values.

### G. S02-I3 — revoked Firebase ID-token integration acceptance

All protected verifyIdToken call sites pass checkRevoked=true and the static route-inventory guard is in CI. This is not proof of live Firebase token revocation. Prepare exact staging steps for an authorized operator using synthetic test identities: issue a token, revoke refresh tokens, prove old tokens are rejected on every protected route, and prove a newly issued token succeeds. Do not use a real user's identity or seed and do not claim this complete until redacted staging evidence is returned.

### H. S08-H5 / S07-M1 — legacy preference recovery source work

Review of migration callers, rollback paths and recovery routes is source-complete for the current code. Existing regressions cover unverified schema types, conflicting destination values, retry and preservation of original data. PR #243 added deterministic one-shot fault injection for failed encrypted destination commit, post-write read-back exception, and failed plaintext cleanup commit; retry behavior is covered. The merged PR #243 head `a98d79e46919fa4c472b36a9ec33075d09479a2a` passed CI run `42185303041` (9/9 jobs, including Android unit tests, Espresso, predictive-back and minified R8) plus security run `42185303045`. Do not delete retained legacy data before exact encrypted destination read-back is proven. S08-H5/S07-M1 remain Partial until the real Android Keystore/EncryptedSharedPreferences upgrade/recovery flow and a verified support/recovery path for un-migratable installs are accepted on physical devices after app feature completion.

### I. S05-H2 — invite-only backend contract and redemption privacy (core code merged)

PRs #226/#227 provide the opaque invite lifecycle, production-shared HTTP handlers and real Firestore transaction coverage. PR #255 (merged at `89ac44cf85df1c90530196fdbb01bf93ff2e8994`) corrected the redemption-linkage privacy gap: new invites persist only `usedByUserIdTag`; list responses mask legacy `usedByUserId` values; the admin UI renders only the pseudonymous tag. Exact PR #255 head passed CI [42385827187](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827187) 10/10 and Security [42385827198](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42385827198) 3/3, including Firestore invite/mintToken integrations, Server/Worker tests, Android unit/lint/build/R8, Espresso (4) and API 35 predictive-back (1).

A dry-run-first cleanup script at `server/scripts/pseudonymizeInviteRedemptionUids.js` is available for historical invite documents. It has not been run against any live project. An authorized operator must run `--dry-run` in the intended staging project, review aggregate counts, obtain approval before `--apply`, then repeat dry-run and require zero candidates.

**Subsequent lifecycle hardening:** PR #261 merged explicit `status === "active"` and finite future-expiry requirements, plus invalid-state filtering and audited revoke support. PR #264 merged the missing admin UI revoke action for invalid invite records. The older PR #253 was closed unmerged and superseded; do not revive it. PR #258 was closed; any migration utility execution remains an authorized staging/operator task.

**Remaining:** product owner confirmation that invite-only onboarding is intended; authorized staging create/list/search/paginate/revoke/expiry/validation and one-time redemption; synthetic Android new-user onboarding and existing-account restore after feature completion. Keep S05-H2 Partial until those gates pass.


### J. S06-H4 / S06-H5 — Session 06 findings restored and re-verified

- **S06-H4 — Fixed:** `ManageUnlockCodesActivity.onConfirmExplain()` checks the boolean from `DuressManager.setDuressPin()`; failure produces a generic retry message and does not finish the flow or claim success. The setter validates the supported 4–6 digit range. PR #263 added the focused source-wiring regression and was merged.
- **S06-H5 — Partial:** PR #265 merged the non-oracular Settings PIN-change failure path and durable `PinChangeThrottle`. Every existing-PIN change attempt is recorded before candidate inspection or storage; successful and rejected candidates both consume the same UID-scoped budget (three attempts per 15-minute window), with wall-clock plus same-boot elapsed-time deadlines and fail-closed persistence. Collision, cooldown and storage failures share one generic rejection. `PinChangeThrottleTest` and `SecurityFindingWiringTest` cover the throttle and call-site wiring. PR #265 CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed; PR #267's full Android CI on the newer source also passed. **Residual:** rate limiting reduces candidate-probing bandwidth but does not prove all observability impossible; keep Partial for independent security review and feature-complete device acceptance.

The tracker still contains 116 findings: 86 Fixed, 17 Partial, 1 Open and 12 Accepted. Do not drop either Session 06 ID in future summaries.

## 4. Physical-device acceptance — explicitly deferred

Do not run this section now. Once the owner declares the app feature-complete and freezes the relevant flows, perform one dedicated acceptance pass and record device model, OEM, Android version, app build, steps, expected/actual result and sanitized evidence.

- S11-H1: real predictive-back edge gestures on LockScreenActivity and every no-skip gate across supported Android/OEM versions. API 35 key dispatch is not a physical gesture test.
- S11-M1: start/minimize an active call beyond lock timeout, reopen through the ongoing-call notification, verify lock appears, unlock and return to call; verify incoming calls still work.
- S08-H2: fresh-install screenshot, screen-recording and recents-thumbnail protection defaults, plus explicit user opt-in behavior.
- S08-L3: 4-, 5- and 6-digit PIN flows, confirm/debounce, backspace, interrupted entry and duress-prefix safety.
- S08-L4: opaque lock transition cover blocks interaction and prevents underlying content flashes; it clears correctly on unlock, sign-out and no-PIN paths.
- S08-H5 / S07-M1: upgrade/recovery with Android Keystore and EncryptedSharedPreferences, including legacy un-migratable fixtures.
- S06-L4: duress wipe, reboot, sign-in, network loss, lost response/admin-unfreeze replay and durable pending intent.
- S05-H2: full Android invite onboarding against staging after backend contract is stable.

Do not mark these Fixed or Accepted based on JVM tests, emulator build success or source inspection.

## 5. Owner/operator actions

These require authorized access and must not be performed as side effects of a code PR:
- Revoke/rotate previously exposed Firebase/GCP, Worker, B2, admin/session and deployment credentials; record secret-manager/audit evidence without values.
- Verify production Firestore rules revision, indexes, TTL policies, Admin SDK service-account scopes and enforcement state.
- Review App Check monitoring; enable Firestore/Storage enforcement at the Firebase service layer only after compatible-client adoption and rollback readiness are proven. Do not add `request.app` or an App Check helper to Firestore Rules.
- Verify Render-to-Worker Durable Object URL/secret pairing and Cloudflare secrets without exposing values.
- Execute staging Firebase revoked-token, admin-session, invite and duress scenarios using authorized synthetic fixtures.
- Configure and read back SC-12 branch protections: required approving reviews, CODEOWNERS/last-push policy, required CI/security checks, immutable release tags and protected production-release environment.
- Re-check the Vercel preview quota before retrying; avoid repeated deployments while rate-limited.
- Ratify any accepted residual for S03-M3 and approve the final risk register.

## 6. PR evidence and definition of done

Every PR must state finding IDs, threat path, exact changed files, test names and commands, workflow run, result/count, compatibility/migration/retry behavior, residual risk, operator dependencies and the resulting tracker disposition. No secret values or personal data in evidence.

Close source-only tasks only after CI executes the tests. Keep device-only items deferred until app feature completion. Keep operator blockers explicit. Keep FINAL_SIGNOFF.md PENDING until every required criterion is evidenced or formally deferred and approved by the owner.