# DuoShield — Next Work Report

**Updated:** 2026-11-11 (PR #292 S06-H4 durability and S06-L4 worker runtime tests merged).
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Latest audited application-code merge:** `b73cab8049983969b1996245600a95ff227da175` (PR #292; durable secondary-code persistence and worker runtime regression coverage). GitHub inventory after merge: 0 open PRs and 0 open issues.

## Verified implementation PR #288 — S02-I2 production fail-closed rate limiting

PR #288 branch `security/s02-i2-production-fail-closed-2026-11-09` merged as
`78a99b478e90ec3337df3c92997de3ac16efbe5e`.

- authRateLimitStore now supports requireDurable. In strict mode, missing Worker configuration or a failed/malformed Durable Object response raises a typed 503 error instead of consuming a process-local counter.
- Strict mode is enabled unless NODE_ENV is explicitly development or test (including unset NODE_ENV). All 12 authenticated routes return HTTP 503 with Retry-After: 30 when shared limiting is unavailable; HTTP 429 remains reserved for an actual quota breach.
- Unit tests cover missing shared configuration and Worker outages; route-wiring regressions cover all 12 protected routes.
- Exact-head CI [42583301825](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301825) passed 10/10; Security [42583301910](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301910) passed 3/3; Firestore Rules/server transactions [42583301878](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42583301878) passed.
- S02-I2 remains Partial only for authorized Render/Worker secret-pair read-back and production runtime confirmation. No live secrets were read/changed and no production deployment was performed.

## Verified implementation PR #289 — S10-N2 same-owner method-reference callback taint

PR #289 merged as `ab7bbe954fab03c6ed1c55f7ad87b66c193dca85`.

- Extends `scripts/AndroidReleaseLogPrivacyPolicy.java` to recognize same-owner method-reference callback registrations: `this::method`, `Owner::method` and `Owner.this::method`.
- Marks the registered app-owned callback method's parameters opaque/tainted in the method-local analysis, while preserving the narrow MediaPlayer integer-code exception.
- Adds a synthetic AST fixture where `CallbackApi.register(this::callbackLog)` causes ordinary parameter `callbackArg` to reach `Log.w`.
- Exact-head CI [42585457006](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42585457006) passed 10/10 and Security [42585457009](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42585457009) passed 3/3. Server + Worker, AST, Android unit, lint, debug APK, R8, Espresso, API 35, CodeQL, Gitleaks and SBOM all passed.
- S10-N2 remains Partial for other method-reference receiver/target forms, external callback contracts, whole-program symbol-resolution limitations and feature-complete physical release/minified APK logcat acceptance.

## Merged implementation PR #290 — S06-H5 all primary-PIN collision paths

Branch: `security/s06-h5-throttle-all-primary-pin-collision-paths-2026-11-09`; merged to `main` as `9b1c1dd48fb584d728491e8bfeee5ef380e9b834`.

- PR #265 already applied the durable account-scoped PIN candidate throttle to Settings PIN changes.
- Source review found two additional paths that checked a proposed primary PIN against the preserved secondary-code hash without consuming the shared throttle: `SetupPinActivity` after restore/setup and `ForcedPrimaryPinRotationActivity` after account unfreeze.
- Both paths now consume the same UID-scoped three-attempt/15-minute budget before inspecting the hidden secondary-code hash. The attempt is consumed regardless of whether slot B is armed, so cooldown behavior does not reveal secondary-code presence.
- Collision, throttle denial and storage/auth failure now share one generic retry response; neither path branches on the collision result or emits a collision-specific message.
- Added two `SecurityFindingWiringTest` regressions requiring throttle-before-check, guarded storage and generic failure/no collision-specific branch in both flows. Existing `PinChangeThrottleTest` continues to cover durable limits, reset windows, boot/clock behavior and account separation.
- Updated in this same PR: BUG_TRACKER, START_HERE, this report, FINAL_SIGNOFF, independent re-verification, agent handoff, SESSION_INDEX and a new session record.
- Exact-head verification before merge: CI [42612569269](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42612569269) passed 10/10 and Security [42612569340](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42612569340) passed 3/3. The documentation-synchronized final head also passed CI [42613986140](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42613986140) 10/10 and Security [42613986331](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42613986331) 3/3. The Android unit suite (including both wiring regressions), lint, debug APK, minified R8 (453,247-line mapping), Espresso (4 executed tests), API 35 predictive-back (1 executed test), Server + Worker tests, AST, CodeQL, Gitleaks, SBOM and integrity checks all passed. PR #290 merged as `9b1c1dd48fb584d728491e8bfeee5ef380e9b834`. Keep S06-H5 Partial pending independent residual side-channel review and feature-complete device acceptance.
## Merged implementation PR #292 — S06-H4 durable secondary-code persistence and S06-L4 worker runtime tests

PR #292 merged to `main` as `b73cab8049983969b1996245600a95ff227da175` from exact head `ab17f656f2ac352944f1f764af68280a249ced35`.

- **S06-H4:** `DuressManager.setDuressPin()` now fails closed when SecurePrefs resolves to the ephemeral/in-memory tier. The slot-B hash, armed flag and legacy-key removal are committed synchronously in one preference transaction. The persistence helper reads back the hash and armed bit and confirms the legacy value is absent before success. App-owned salt and derived hash arrays are cleared in `finally`.
- Added `DuressPinPersistenceTest` for successful commit/read-back, commit failure, incomplete read-back and invalid input; `SecurityFindingWiringTest` verifies the Activity call path and durable-tier/helper wiring.
- **S06-L4:** WorkManager test harness support and a narrow HTTP/intent-acknowledger injection seam were added. Production defaults still use `HttpURLConnection` and compare-and-clear `PendingLockStore` operations. `AccountLockWorkerRuntimeTest` executes the actual `doWork()` path using deterministic fakes for 200/204 success, permanent 401, 503 retry, retry exhaustion, network exception and missing nonce. Assertions cover POST body/method, timeouts, disconnect, retry/failure result and intent-clear behavior.
- Exact-head CI [42948877593](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877593) passed all jobs, including Android unit tests, lint, debug APK, Espresso (4 tests), API 35 predictive-back (1 test), minified R8/mapping verification, Server + Worker, AST, wrapper and vendored-libsignal integrity. Security [42948877583](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877583) passed CodeQL, Gitleaks and SBOM. Firestore Rules/server transactions [42948877424](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877424) passed.
- **Disposition:** S06-H4 remains Fixed, now with the persistence/durability gap closed. S06-L4 remains Partial: fake-endpoint worker execution is not staging evidence for real network loss, lost-response/admin-unfreeze replay or production Firestore configuration. No production deployment, live secret read-back or physical-device acceptance was performed.

## Current verified mainline

PR #292 is the latest source-changing merge (`b73cab8049983969b1996245600a95ff227da175`). Exact-head CI [42948877593](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877593), Security [42948877583](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877583) and Firestore Rules/server transactions [42948877424](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877424) all passed. CI includes unit tests, lint, debug APK, Espresso (4), API 35 predictive-back (1), minified R8/mapping, Server + Worker, AST and integrity checks. Earlier PR #286–#290 evidence remains recorded above.

PR #267 was merged after its exact head `213442825113013a85650de9ac24edf0e9ec9211` passed all required pull-request workflows:

- CI [42443323887](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323887) — passed: Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified release R8, Server + Worker tests, release-log AST, Gradle wrapper and vendored-libsignal integrity.
- Security [42443323988](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323988) — passed: CodeQL, Gitleaks and SBOM.
- Firestore Rules [42443323886](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323886) — passed.
- Firestore Rules + server transactions [42443323962](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42443323962) — passed; log reported 200 Rules tests and 6 server transaction/integration tests.

Post-merge workflows on the previous PR #267 baseline `8d0082d004993278645f3eb7a72c43a8cdc14e59`:
- Security [42445582762](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582762) — passed.
- Firestore rules [42445582759](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582759) — passed.
- Firestore Rules Tests [42445582747](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582747) — passed.
- CI [42445582763](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42445582763) — passed on the exact current main SHA, including Android unit tests, lint, debug APK, Espresso, API 35 predictive-back, minified release R8, Server + Worker tests, release-log AST, Gradle wrapper and libsignal integrity.

## PR #269 verification and current main

PR #269 exact-head CI [42446136767](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136767) and Security [42446136770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136770) passed. The merge commit is `afdf2463c496f14a5c33ee5694fc47eff97d5e3d`. This report records exact PR-head checks; it does not claim separate post-merge main CI completion for the new commit.

## Recent merged work

- PR #292 — S06-H4 synchronous/durably verified slot-B persistence and S06-L4 `AccountLockWorker#doWork()` runtime tests; merged as `b73cab8049983969b1996245600a95ff227da175`. CI [42948877593](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877593) passed; Security [42948877583](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877583) passed; Firestore Rules/server transactions [42948877424](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42948877424) passed. S06-L4 remains Partial for staging/device acceptance.

- PR #261 — explicit active-state/future-expiry validation for invite redemption and audited invalid-state handling (S05-H2).
- PR #264 — admin UI revoke action for invalid invite rows (S05-H2).
- PR #265 — generic Settings PIN-change failure response and durable attempt throttle for accepted and rejected candidates (S06-H5); PR #290 extends the same budget to restored setup and forced primary rotation. Exact head `ed8e47550763cf7766acb0f6ef3d53dc15039df3`, merge `32175ccf86342a6c517459a0e2507f14c4d064e8`; CI [42440025067](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025067) and Security [42440025048](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42440025048) passed. PR #262 was closed unmerged; its implementation was carried by #265.
- PR #267 — corrected the App Check / Firestore Rules boundary (S10-N1); unsupported `request.app` and `appCheckVerified()` removed from executable rules, verbatim Rules emulator testing restored, device registration tests added, and rollout docs corrected. Merge `8d0082d004993278645f3eb7a72c43a8cdc14e59`.
- PR #269 — S10-N2 release-log analyzer now fails closed on bodyless app-owned abstract/interface/native declarations; exact head `51fc914111d89b1f7f69c1b374910cd1186cdf04`, merge `afdf2463c496f14a5c33ee5694fc47eff97d5e3d`; CI [42446136767](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136767) and Security [42446136770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42446136770) passed.
- PR #270 — reconciled the security handoff/tracker/sign-off after #269; merged as `1a425ad0aa2e7c36fa66a2afe4e46d85e78ee514`.
- PR #271 — bounded S07-L2 app-owned key-buffer/cache hygiene; exact head `efceb00bc7fac0361804f98cf84458e69a75c0c3`, merged as `87e558c25bd810dafb8b47d97e52c2022a4a94fe`; CI [42448400756](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400756) passed 10/10 and Security [42448400818](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400818) passed 3/3. S07-L2 remains Partial because native destruction is still finalizer-timed.
- PR #272 — refreshed the agent handoff after PR #271 (including exact libsignal dependency topology and corrected 12-route inventory); merged as `0a985fb39570fea3b030da76417e67a01790f8b5`. PR #273 was a duplicate documentation PR and was closed unmerged.
- PR #274 — S10-N2 owner-qualified typed cross-class field-write/read taint coverage; exact head `a26d4f1140532879c4e74590e6bb1ec16a34ebc4`, merged as `d1ee9ac01e0809a6e398958be423097bc082e9d7`; CI [42450251567](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251567) passed 10/10 and Security [42450251553](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42450251553) passed 3/3. It also removed retry-count and protocol-type details from a ChatMediaActivity warning. S10-N2 remains Partial.

- PR #275 — refreshed the security handoff after #274; exact head `e9ecaf3e4590dbcf7e701a628f1b4843d4a3f527`, merge `b401be50892f2e1adc55235bf6a840daa55669a1`; CI [42453798811](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453798811) 10/10 and Security [42453798810](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453798810) 3/3.
- PR #276 — S10-N2 fails closed for known app-owned subtype overrides; exact head `031ea9d0ecfbeba06bc9d14c2d5bbdaf0a8e58ed`, merge `79aacea3a05131f7c5afcc6c64eef182322eda3f`; CI [42453996138](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996138) 10/10 and Security [42453996152](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42453996152) 3/3.
- PR #277 — S10-N2 treats ambiguous same-name/same-arity app-owned helper returns as unknown/tainted; exact head `28ed49f2ae63d134eedbb6a73aa4cfa2930f9abc`, merged as `b596e965d4be54b4ea8079b4036012e68188f865`; CI [42454608770](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608770) 10/10 and Security [42454608761](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42454608761) 3/3. S10-N2 remains Partial for residual flows and physical release-logcat acceptance.
- PR #278 — independent tracker/handoff reconciliation; merged as `0f5ffe15518db29b4c5895dc42ca600647201f99`; exact-head CI [42457565977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565977) 10/10 and Security [42457565979](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565979) 3/3.
- PR #279 — final tracker baseline alignment; documentation-only, merged as `3d47d39faac649e9d5197327a7db5cc145c2c1de`; exact-head CI [42510855878](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42510855878) 10/10 and Security [42510855851](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42510855851) 3/3.
- PR #280 — final repository/source-baseline documentation correction; documentation-only, merged as `21c5403799487915aea755365ff6e9bfd73d1cd3`; exact-head CI [42558744976](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42558744976) 10/10 and Security [42558744833](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42558744833) 3/3.
- PR #281 — extends S10-N2's bounded AST analysis to resolve chained app-owned receiver types through locals, declared fields, casts/parentheses and uniquely summarized helper returns. Regression fixture exercises a sensitive setter/read chain through `wrapper.getChild()` to a `Log.w` sink. Exact head `c5dee06000db048e98cc21ee7c51ebacbb1bc923`, merged as `665146f8195409274b29b563333de25c591f10a9`; CI [42561844959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844959) passed 10/10 and Security [42561844900](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844900) passed 3/3. The Vercel preview status failed; this is not a GitHub Actions failure and no successful preview deployment is claimed.
- PR #282 — documentation-only tracker/handoff sync; merged as `be55818a4b5c6d1c210f736a39fe38bb2b9e5cba`; exact-head CI [42562839236](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42562839236) passed 10/10 and Security [42562839196](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42562839196) passed 3/3. It does not modify the audited application-code tree.
- PR #286 — S10-N2 fails closed on unresolved generic app-owned helper returns and generic receiver chains, common Java reflection/class-loading surfaces, and opaque callback-lambda parameters. It restores the method-local environment after each lambda to avoid name-scope contamination, allows only the safe exception-class-name projection and the narrow `VoiceMessagePlayer` MediaPlayer integer-code diagnostics, and keeps raw callback values tainted. New AST fixtures cover a generic `T` return, `Class.forName → getMethod → Method.invoke`, callback payload logging and a safe callback exception-class message. Exact head `91751692e7c491a1aba948b2b1da4f2bbada120c`, merged as `26daea8e8b2e45764a68000d02a1f93a486d2fde`; CI [42575704977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704977) passed 10/10, Security [42575704959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704959) passed 3/3. AST: 205 Java files / 414 `Log.w/e/wtf` sinks / zero violations; Espresso 4 tests; API 35 predictive-back 1 test; debug APK, minified R8 and Server + Worker passed. S10-N2 remains Partial because the AST guard is not symbol-resolved whole-program analysis and physical release/minified APK logcat acceptance remains deferred.

- PR #284 — S10-N2 extends bounded app-owned receiver resolution through inherited superclass/interface methods, inherited field types and sensitive fields, explicit `super` calls, duplicate simple-name receiver types (fail closed), and qualified enclosing receivers (`Outer.this`). Regressions cover inherited sensitive getter returns, inherited field setter/read chains, inherited direct-field writes, explicit superclass dispatch, duplicate simple-name ambiguity and qualified enclosing receiver dispatch. Exact head `23eb17ac71ef6832195d77d6765106ca3bda143c`, merged as `30329d7b56ec0b26fd9b40495cdbb3d3bd999cae`; CI [42567271589](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42567271589) passed 10/10 and Security [42567271633](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42567271633) passed 3/3. The AST job passed self-tests and scanned 205 Java files / 414 `Log.w/e/wtf` calls with zero violations; Espresso (4 tests), API 35 predictive-back (1 test), debug APK and minified R8 all passed.
- PR #266 — intentionally closed unmerged; its documentation changes were not applied to main.

After PR #284 merged, GitHub returned **zero open source PRs and zero open issues** before this documentation-only closeout (rechecked 2026-11-09). PRs #278-#280, #282, #283 and #285 are documentation-only; PR #284 is the latest S10-N2 source change. PR #266 was intentionally closed unmerged per owner instruction; PR #273 was a duplicate of merged PR #272; PR #262's implementation was carried by merged PR #265. These closed historical PRs do not represent outstanding work. Do not reopen superseded branches.

PR #278 independently reconciled the tracker/handoff snapshot and merged at `0f5ffe15518db29b4c5895dc42ca600647201f99`; exact-head CI [42457565977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565977) passed 10/10 and Security [42457565979](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42457565979) passed 3/3. PR #279 aligned the tracker/handoff baseline and merged at `3d47d39faac649e9d5197327a7db5cc145c2c1de`; exact-head CI [42510855878](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42510855878) passed 10/10 and Security [42510855851](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42510855851) passed 3/3. PR #280 made the following documentation-only correction and merged at `21c5403799487915aea755365ff6e9bfd73d1cd3`; exact-head CI [42558744976](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42558744976) passed 10/10 and Security [42558744833](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42558744833) passed 3/3. PR #281 then extended the S10-N2 AST analysis, merged at `665146f8195409274b29b563333de25c591f10a9`; exact-head CI [42561844959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844959) passed 10/10 and Security [42561844900](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42561844900) passed 3/3. Its Vercel preview status failed; no preview deployment is claimed. PR #282 subsequently synchronized tracker/handoff docs and merged at `be55818a4b5c6d1c210f736a39fe38bb2b9e5cba`; exact-head CI [42562839236](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42562839236) passed 10/10 and Security [42562839196](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42562839196) passed 3/3. The post-merge GitHub inventory reports zero open PRs and zero open issues.

## Canonical tracker

The five severity sections of `BUG_TRACKER.md` retain **116 unique findings: 86 Fixed, 17 Partial, 1 Open, 12 Accepted** (Critical 4; High 31; Medium 27; Low 31; Informational 23), reconciled against application-code baseline `30329d7b56ec0b26fd9b40495cdbb3d3bd999cae` (tree `03e780af884f4d123b06ffc68484970066597fba`). PR #285 is documentation-only and does not change that source baseline. PR #284 adds source-level S10-N2 coverage but does not create a new finding ID or justify promoting the finding to Fixed. The register is authoritative; this report does not promote findings based on PR merge alone.

Notable evidence corrections:
- **S06-H5 remains Partial:** PR #265's durable three-attempt/15-minute, UID-scoped throttle counts successful and failed replacement attempts and uses a generic failure response. This bounds probing but does not prove every success/failure side channel impossible.
- **S08-L4 remains Partial:** opaque lock-transition cover and manifest protections are implemented and CI-tested; actual no-flash/touch-blocking/unlock/sign-out flow acceptance is device-deferred.
- **S10-N1 remains Partial:** the invalid Rules-level App Check expression has been removed and regression-tested. Real enforcement is a Firebase service-layer Console operation and isolated staging acceptance remains outstanding.
- **S10-N2 remains Partial:** PR #269 closes the bodyless-helper false negative; PR #274 closes typed cross-class setter-to-field reads; PR #276/#277 harden known override and ambiguous overload handling; PR #281 resolves chained receiver types through locals, fields, casts/parentheses and helper returns. PR #284 adds inherited method and field lookup, explicit `super` dispatch, qualified `Outer.this` receivers and fail-closed handling for duplicate simple-name receivers. The exact-head AST run scanned 205 Java files / 414 log sinks with zero violations. The analyzer remains syntax/AST-based, not symbol-resolved whole-program proof; external/library subclass dispatch, arbitrary alias shapes outside modeled forms, reflection, native methods, third-party callback internals, dynamic loading, arbitrary generic container semantics and physical release-logcat acceptance remain.
- **S07-L2 remains Partial:** PR #271 clears app-owned fingerprint/derivation/HKDF intermediate arrays and serializes derivation/cache eviction. Native libsignal destruction remains finalizer-timed; callers can retain key objects and later calls can repopulate the cache. No synchronous complete-erasure claim is made.

## Next work — dependency ordered

### 1. Source-review / code-analysis candidates

- **S10-N2:** PR #284 additionally closes the reproduced inherited-app-owned-method/field receiver gaps, plain `super` dispatch, qualified `Outer.this` resolution and duplicate simple-name receiver fail-open path. Continue only with reproducible residuals: unmodeled alias/receiver syntax, external/library subclass dispatch, dynamic class loading, reflection/native paths, third-party callback internals, arbitrary generic collection semantics and full symbol/generic resolution. Keep the analyzer's syntax-only limitations explicit; physical release/minified APK logcat acceptance remains deferred until feature-complete.
- **S07-L2:** source review confirmed pinned libsignal 0.54.1 has no supported deterministic Java destruction API. PR #271 has merged bounded cleanup of app-owned arrays/cache metadata. Do not use reflection, unsafe native access or GC forcing; prepare an explicit owner residual-risk decision for finalizer-timed native destruction and ensure app lifecycle blocks post-wipe identity derivation.
- Re-check `BUG_TRACKER.md` and current source before taking any additional code task; the 2026-11-07 open-PR list is historical and must not be revived.

### 2. Owner decisions

- **S03-M3:** ratify reusable READ/WRITE media-token semantics and the 10-minute TTL, or approve a versioned protocol redesign. Do not silently change retry/range semantics.
- **S05-H2:** owner confirmation that invite-only onboarding is the intended product contract.

### 3. Authorized staging / operator acceptance

| Finding | Required acceptance |
|---|---|
| **S05-H2** | Synthetic invite create/list/search/paginate/revoke/expiry/validate/one-time redemption and Android onboarding/restore after feature-complete; authorized legacy UID migration dry-run, review, approved apply if safe, and repeat dry-run showing zero candidates. |
| **S05-M3** | Multi-instance admin-session create/validate/revoke/global revoke/restart/outage/expiry; read back deployed Firestore rules/indexes/TTL. Synthetic sessions only. |
| **S06-L4** | Staging lost-response/admin-unfreeze replay, retry exhaustion, permanent invalid nonce and Firestore configuration read-back. |
| **S10-N1** | Review App Check monitoring and client adoption; enable service-level enforcement only after rollback readiness; verify valid/missing/invalid tokens with synthetic identities and read back Console state. Never add `request.app` to Firestore Rules. |
| **S10-N3** | Isolated Cloudflare Worker + R2 + B2 cron/retry and interleaved delete/ETag/HEAD/B2 failure acceptance. Never use production buckets/media. |
| **S02-I2** | Authorized, redacted Render/Worker Durable Object URL-secret pairing read-back and an actual synthetic Durable Object path test. Never print secret values. |
| **S02-I3** | Synthetic Firebase staging token revocation: old token rejected across all 12 protected routes and newly issued token accepted. |
| **SC-12** | Authorized GitHub administrator configures and reads back required approving reviews, required CI/Security checks, CODEOWNERS/last-push policy, immutable version tags and protected production-release environment. |

### 4. Physical-device acceptance — deferred

Do not start this matrix until the owner explicitly declares the app feature-complete:
- S11-H1 predictive-back edge gestures on LockScreenActivity and all no-skip gates across supported OEM/API versions.
- S11-M1 call minimize/timeout/notification return/authentication and in-call continuation.
- S08-H2 screenshot, screen-recording and recents behavior on fresh install and explicit opt-in.
- S08-L3 4/5/6-digit PIN, debounce, backspace, interrupted entry and duress flows.
- S08-L4 lock-cover no-flash/touch-blocking/removal after unlock, duress, no-PIN and sign-out.
- S08-H5/S07-M1 real Keystore migration, interrupted upgrade, retry and recovery.
- S06-L4 wipe/reboot/sign-in/network-loss/lost-response behavior.
- S05-H2 complete Android invite onboarding and existing-account restore.
- S10-N2 release/minified APK logcat privacy inspection.

## Sign-off

`security-remediation/FINAL_SIGNOFF.md` remains **PENDING**. No PR merge, green CI run, source review or emulator pass constitutes production deployment, credential rotation, provider-console acceptance, live staging evidence or physical-device sign-off.

## Session record — 2026-11-09, S10-N2 inherited app-owned receiver resolution

```
SESSION: 2026-11-09  MODEL: GPT-6  CLUSTER: S10-N2  STATUS: partial
CHANGES:
- Added bounded inherited app-owned method and field resolution to the Android release-log AST analyzer.
- Added plain super dispatch, qualified Outer.this receiver resolution, and fail-closed handling for duplicate simple-name receiver ambiguity.
- Added regressions for inherited getter returns, inherited field setter/read chains, inherited direct field writes, explicit super calls, duplicate simple-name ambiguity, and qualified enclosing receivers.
VERIFICATION:
- PASS: PR #284 exact-head CI 10/10; Security 3/3.
- PASS: AST self-test; full scan of 205 Java files / 414 Log.w/e/wtf sinks / zero violations.
- PASS: Espresso 4 tests; API 35 predictive-back 1 test.
- PASS: debug APK, minified R8, server/Worker tests, unit tests, lint, Gradle wrapper and vendored libsignal integrity, CodeQL, Gitleaks, SBOM.
- NOT RUN: physical-device release/minified APK logcat acceptance; deferred until owner declares app feature-complete.
COMMIT: PR head 23eb17ac71ef6832195d77d6765106ca3bda143c; merge 30329d7b56ec0b26fd9b40495cdbb3d3bd999cae.
WORKTREE: remote main verified at merge commit; documentation closeout is on a separate PR branch.
NEXT SESSION: only pursue a newly reproduced S10-N2 residual within the bounded analyzer's source scope, or perform physical release-logcat acceptance after the owner's feature-complete declaration. Keep S10-N2 Partial until the remaining analysis limitations and device acceptance are resolved.
```

## S10-N2 follow-up — PR #286 (2026-11-09)

**Source change:** `scripts/AndroidReleaseLogPrivacyPolicy.java` only. The bounded resolver now fails closed on unresolved class/method generic return variables and their chained receivers; recognized Java reflection/dynamic loading operations; and lambda callback parameters, which are treated as opaque values within their lexical scope. The callback scope is restored after analysis to prevent same-name taint from leaking into unrelated locals. The safe exception class-name projection is scoped to the active callback path, and `what`/`extra` are exempt only in `VoiceMessagePlayer` for MediaPlayer's integer error codes.

**Regression coverage:** generic `T getValue()` receiver chain; `Class.forName` → `getMethod` → `Method.invoke`; opaque callback lambda payload logged as a sink; safe callback exception-class-only output accepted.

**Evidence:** PR #286 exact head `91751692e7c491a1aba948b2b1da4f2bbada120c`, merge `26daea8e8b2e45764a68000d02a1f93a486d2fde`. CI [42575704977](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704977) passed 10/10; Security [42575704959](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42575704959) passed 3/3. AST self-test passed; source scan: 205 Java files, 414 `Log.w/e/wtf` sinks, zero violations. Server + Worker tests, Android unit tests, lint, debug APK, minified R8 build, Espresso (4 tests) and API 35 predictive-back (1 test) passed. R8 mapping generation produced 453,260 lines.

**Disposition:** S10-N2 remains **Partial**. This is conservative syntax-level analysis rather than compiler-attributed, symbol-resolved whole-program analysis. External/library subclass dispatch, bytecode proxies, method references and opaque third-party callback internals, unrecognized reflection/dynamic-loading mechanisms, native behavior, arbitrary generic/container semantics, and receiver/alias forms outside the modeled paths remain residual. Physical release/minified APK logcat acceptance is still deferred until the owner declares the app feature-complete. Do not change `FINAL_SIGNOFF.md` from PENDING.
