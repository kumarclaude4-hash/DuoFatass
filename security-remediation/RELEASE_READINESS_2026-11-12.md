# DuoShield — Release Readiness and Remaining Work

**Updated:** 2026-11-12  
**Repository:** `kumarclaude4-hash/DuoFatass`  
**Current main:** `4341ecf84d8579b651d6853c480f0c1a6129a324`  
**Decision:** NOT SIGNED OFF — `security-remediation/FINAL_SIGNOFF.md` remains PENDING.

## 1. Verified current baseline

Current main push workflows:
- [CI 42983530186](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42983530186): 10/10 jobs passed — Server/Worker, release-log AST, vendored libsignal integrity, Gradle wrapper integrity, lint, Android unit tests, Espresso, debug APK, API 35 predictive-back and minified R8.
- [Security 42983530183](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42983530183): 3/3 jobs passed — CodeQL, Gitleaks and SBOM.

Recent merged work:
- PR #292: durable secondary/duress PIN persistence and runtime AccountLockWorker tests; CI 10/10, Security 3/3, Firestore Rules/server transactions passed.
- PR #295: corrected SharedPreferences failed-commit test semantics; CI 10/10, Security 3/3.
- PR #296: screenshots allowed globally until app finalization; exact-head CI 10/10, Security 3/3.
- PR #297: AppLockManager foreground lifecycle runtime tests and CallActivity/InCallChatActivity ordering regressions; exact-head CI 10/10, Security 3/3.

The canonical tracker retains **116 unique findings: 86 Fixed, 17 Partial, 1 Open, 12 Accepted**. Governance classification aliases in the severity display are not additional unique findings. No open GitHub issues. PR #293 is a stale documentation-only PR and is being superseded by this current-main closeout.

## 2. Remaining repository work

| Priority | Task | Owner | Acceptance evidence |
|---|---|---|---|
| P0 | Reconcile tracker/handoff and supersede stale PR #293 | Repository automation | Current-main documentation PR merged; SHA, counts, workflow IDs and residuals agree |
| P1 | Focused residual review of S06-H5 PIN collision observability | Security engineering | Reproducible channel fixed with regression, or residual formally accepted |
| P1 | Review S10-N2 analyzer residual surface without weakening fail-closed behavior | Security engineering | Source review, regression for any proven false negative, full AST self-test and CI |
| P1 | Review S07-L2 upstream libsignal explicit destruction API | Crypto engineering/upstream | Supported API and safe upgrade proof, or owner-approved residual-risk acceptance |
| P1 | Clarify S03-M3 reusable READ/WRITE capability policy | Product/security owner | Signed Option A risk acceptance or approved versioned protocol plan |
| P1 | Confirm invite-only onboarding contract | Product owner | Explicit product decision before historical invite data migration |
| P2 | Fix any newly reproducible code defect found in targeted residual review | Engineering | Bug-shaped regression, minimal fix, full exact-head CI/Security, tracker updated in same PR |

No additional code defect was established in the selected S06-H5, S07-L2 and S10-N2 source reviews for this status pass. Do not invent code changes to reduce the Partial count. These residuals are bounded analyzer limitations, pinned upstream API limitations, or require product/security decisions.

## 3. External operator and staging work

These actions require authorized provider/project access. Do not paste credentials or production user data into GitHub.

### Credential rotation
1. Identify which historically exposed Firebase/GCP service-account, B2, Worker, Render/admin/session and deployment credentials are still active.
2. Revoke obsolete key IDs and issue replacements only where the runtime still requires them.
3. Update provider secret stores (not repository files), deploy compatible services and verify configuration by key name/status only.
4. Retain sanitized audit evidence with no secret values.

### Firebase / Firestore
1. Read back the production Firestore Rules revision and compare it with reviewed main.
2. Verify/deploy composite indexes and TTL policies for admin sessions, invites and duress-lock nonce/receipt collections.
3. Review Firebase App Check monitoring and compatible-client adoption.
4. Enable service-level App Check enforcement only after an approved threshold and rollback plan; Firestore Rules must not use `request.app`.
5. In isolated staging, verify valid/missing/invalid App Check tokens and revoked Firebase ID tokens. For S02-I3, old revoked tokens must fail on all 12 protected routes and newly issued tokens must work.

### Render / Cloudflare
1. Verify the Render `ADMIN_LOCKOUT_WORKER_URL` and `ADMIN_LOCKOUT_WORKER_SECRET` are paired with Worker `ADMIN_LOCKOUT_SECRET` without exposing values.
2. Run a synthetic cross-instance Durable Object rate-limit test and verify fail-closed 503 when shared limiting is unavailable.
3. Verify deployed `duoshield-storage` Worker version, R2 binding, B2 secret names and scheduled cron.
4. In an isolated bucket, run R2-to-B2 migration tests for success, changed ETag, deleted source, B2 outage, failed compensation and scheduled retry.

### Invite / admin-session / duress staging
1. Obtain product approval for invite-only onboarding.
2. Run invite UID pseudonymization script `--dry-run` in the intended staging project; review aggregate counts; get explicit approval before `--apply`; repeat dry-run and require zero candidates.
3. Test invite create/list/search/paginate/revoke/expiry/validation/one-time redemption with synthetic identities.
4. Test admin-session creation, cross-instance validation, logout/global revoke, expiry, restart and Firestore outage with synthetic sessions.
5. Test duress-lock lost-response/admin-unfreeze race, retry exhaustion and nonce recovery with synthetic accounts.
6. Read back deployed Firestore rules, indexes, TTL and service-account scopes.

## 4. Repository governance (SC-12 — Open)

Live default-branch ruleset `Hello` (ID `26629873`) requires PRs and blocks deletion/non-fast-forward updates, but currently has:
- zero required approvals;
- no required CI/Security status checks;
- no CODEOWNERS approval or last-push approval;
- no required review-thread resolution.

An authorized repository administrator must configure and verify:
1. At least one approving review, subject to the maintainer model.
2. Required CI and Security checks by exact workflow/check name.
3. CODEOWNERS/last-push approval and review-thread resolution.
4. Immutable `v*` release-tag protection.
5. Protected `production-release` environment with appropriate reviewers/deployment restrictions.

The connected GitHub integration cannot read classic branch protection (403) or the production-release environment configuration. Do not claim governance is complete until an administrator returns the effective settings.

## 5. Owner decisions and accepted residual risks

- **S03-M3:** choose between retaining the 10-minute reusable READ/WRITE token behavior with explicit residual-risk acceptance, or approving a versioned protocol redesign. Do not make current v1 READ/WRITE tokens one-shot without compatibility design.
- **S05-H2:** confirm invite-only onboarding is the intended product contract.
- **S07-L2:** pinned libsignal 0.54.1 Java wrapper has no public deterministic key-destruction method; native zeroization is drop/finalizer-timed. Approve the limitation or sponsor an upstream-supported API/upgrade.
- **S06-H5:** independently review remaining primary-PIN collision observability after throttling all known candidate-entry paths.
- **Accepted findings:** review all 12 accepted dispositions and ensure approver, rationale and review date remain valid. Notable accepted limitations include no root/attestation, TLS pinning, Sender Key group protocol and add-member-to-existing-group functionality.

## 6. Physical-device matrix — only after feature-complete declaration

Physical-device testing remains deliberately deferred. After the owner declares feature-complete, test:
- Real predictive-back edge gestures on LockScreenActivity and no-skip gates across supported OEM/API versions.
- Live-call minimize → timeout → ongoing notification → lock overlay → successful unlock → same call/audio resumes.
- Screenshot/screen-recording/recents behavior under the currently requested all-screen allowed policy; revisit blocking only after explicit product decision.
- 4/5/6 digit PIN entry, debounce, backspace, interrupted input and duress.
- Lock transition cover: no content flash/touch-through; correct removal on unlock, duress, no-PIN and sign-out.
- Real Keystore/EncryptedSharedPreferences upgrade/recovery, process death and legacy un-migratable fixtures.
- Duress wipe/reboot/sign-in/network-loss/retry recovery.
- Full invite onboarding with synthetic staging invites.
- Release/minified APK logcat review for UID/peer identifiers, tokens, media keys, device fingerprint, exception payloads and sensitive metadata.
- Signed in-app update install/upgrade with package, ABI and SHA256 verification.

Record device model, OEM, Android API, app build/commit, steps, expected/actual, sanitized evidence, tester/date and repeat count. No personal identifiers or secrets.

## 7. Signed release and deployment

The current release workflow triggers on an immutable `v*` tag or manual dispatch with an existing tag; an ordinary main push runs CI but does not publish a signed release. The latest GitHub Release visible in the repository inventory is v1.4 (2026-08-11).

Before publishing:
1. Verify repository secret presence (do not reveal values): `GOOGLE_SERVICES_JSON`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, and required non-secret endpoints.
2. Confirm `production-release` environment protection and immutable tag rules.
3. Build the approved version tag with the release workflow.
4. Verify ABI-specific signed APKs, ZIPs, `SHA256SUMS`, certificate fingerprint and build provenance.
5. Install and upgrade-test the signed APK on supported devices.
6. Deploy compatible server, Worker and Firestore configuration in documented order.
7. Run post-deploy smoke tests, monitor critical metrics and verify rollback readiness.

## 8. Final definition of done

DuoShield is ready for production sign-off only when:
- the canonical 116-finding register is reconciled and every Critical/High item is Fixed or formally approved;
- every Partial/Open item is verified or has explicit owner-approved residual risk with approver/date/review date;
- required credentials are rotated and configuration is read back;
- named staging scenarios pass with synthetic identities/data;
- SC-12 governance is verified;
- feature-complete device acceptance is recorded;
- signed APK, checksums, certificate identity and provenance are verified;
- Android/server/Worker/Firestore are deployed in a coordinated release and production smoke tests pass;
- trust-boundary review and owner release approval are recorded.

Until then, leave `FINAL_SIGNOFF.md` **PENDING**.
