# S10-N2 Indirect Release-Log Review — 2026-11-07

## Status

**Partial — source review and two follow-up fixes committed; branch CI/security verification pending.** This is not a production security sign-off. Physical-device logcat acceptance remains deferred until the owner declares the application feature-complete.

- Base: `main` after PR #244 merge, commit `4fb33fd9978249761ba25f1c3d64f68c534978a4`.
- Work branch: `security/s10-n2-indirect-release-log-review-2026-11-07`.
- Branch head when this report was written: `2b429653157e9930212b61f52372654eec862086` (PR #246, Draft).
- PR #244: https://github.com/kumarclaude4-hash/DuoFatass/pull/244 (merged).
- PR #244 verification on exact pre-merge head `328731b948e15bb1cc93ca2e61343be4b2325c75`: CI run [42325532834](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42325532834), latest attempt 4, 10/10 jobs passed; Security Scan [42325532774](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42325532774), 3/3 jobs passed.
- New branch verification: **not yet completed for the latest head**. The earlier head's first run is superseded by the latest scanner extension. Do not mark the follow-up verified until its own CI and Security Scan complete successfully.

## Review scope and method

The review continued from the merged AST policy gate and examined the source files returned by the current repository search inventory for production Android `Log.w`, `Log.e`, and `Log.wtf` calls (45 distinct source paths in the final search inventory), plus the relevant helper implementations, call sites and build configuration. The AST policy job on PR #244 scans the complete production Java tree (204 Java source files; 407 warning/error/fatal calls on the earlier verified snapshot).

The review traced dynamic log inputs where they were identifiable from source:
- UID/peer identifiers and message/conversation identifiers.
- B2 object keys, paths, range labels, URLs and local preference/file metadata.
- Exception objects, messages and aliases copied from exception messages.
- Callbacks from Firebase/Firestore/WebRTC/MediaPlayer and external helpers.
- Storage operation labels and backup event labels.
- Device and Keystore metadata.
- Public Signal protocol key IDs and other numeric operational counters.

Cross-checks included `LogRedact`, `B2StorageHelper.redactOperationName`, `SignalKeyApi.post` and its four route call sites, `BackupManager.logEvent` and its event call sites, `DeviceSecurityGate`, `PinKeyGate`, `proguard-rules.pro` and `app/build.gradle`.

## Findings and changes

### 1. Device fingerprint metadata in a release-surviving log — fixed on this branch

`DeviceSecurityGate.evaluate()` logged `Build.MANUFACTURER` and `Build.MODEL` in a `Log.e` line when blocking onboarding/restore. These fields were unnecessary to explain the security decision and form device-fingerprinting metadata.

Change:
- Removed manufacturer and model from the error line.
- Retained the security tier and Android API level, which are sufficient operational context.
- Added a comment documenting why hardware make/model do not belong in this diagnostic.

Commit: `eacb727604a1ed23d2f7142663d41f68ed781649`.

### 2. Raw Keystore alias in a warning — fixed on this branch

`PinKeyGate.deleteAlias(String alias)` logged the alias when Keystore deletion failed. Current call sites use fixed internal aliases, not user-supplied content, but raw internal key names are unnecessary in release diagnostics.

Change:
- Replaced the raw alias with `LogRedact.metadata(alias)`.
- Kept the exception class for a useful type-only failure diagnostic.

Commit: `1a98953ef907bcdb22ff4e70304be0476df2471e`.

### 3. AST guard and regression coverage — extended

The sensitive-identifier policy now includes `alias`, `keyAlias`, `keystoreAlias`, `manufacturer`, `model`, `deviceManufacturer` and `deviceModel`.

Positive fixture coverage accepts the corresponding values only behind `LogRedact.metadata()` and `LogRedact.uid()` in tags; negative fixtures require detections for raw alias, `Build.MODEL`, `Build.MANUFACTURER` and a raw UID used as the tag.

Commits: `01f196e66d3be7261437dca100902a8d221d4ba7` (sensitive fields) and `2b429653157e9930212b61f52372654eec862086` (scan tag and message arguments).

The JVM `ReleaseLogPrivacyTest` now asserts that the device gate does not include manufacturer/model, keeps API-level diagnostics, and pseudonymises the alias in the Keystore deletion warning.

Test commits: `61bfb4799c0d77fb5d75746f763d208ce0cd2439`, `2f910e0668f269f75fc00f3424d0db1d4d533d2e`, `a1ad4e353d3155ab03f3611521d76485e2b30b57`.

### 4. Reviewed dynamic call sites classified

- `SignalKeyApi.post()` logs `path` on HTTP failure. The method is private; its four callers use fixed literal routes (`/signal/claimPreKey`, `/signal/registerBundle`, `/signal/appendPreKeys`, `/signal/updatePreKey`). It does not log recipient UID, bearer token, request body or server response payload.
- `BackupManager.logEvent()` logs the `event` label on write failure. Its observed call sites use fixed event constants (for example, `backup_started`, `backup_complete`, `backup_failed`, `backup_size_warning`); the dynamic error argument is not interpolated into logcat.
- MediaPlayer `what`/`extra` values and Signal pre-key/device identifiers are numeric status/protocol identifiers rather than message content or account identity.
- Reviewed exception diagnostics use `LogRedact.error()` or exception simple class names, not exception messages or stack traces.
- Reviewed user identifiers, message IDs, B2 paths, URLs and file/preference metadata use `LogRedact.uid()` or `LogRedact.metadata()`.
- The release build has `minifyEnabled true` and includes `proguard-rules.pro`. The `SEC-L03` `-assumenosideeffects` rule removes `Log.v/d/i` calls from the minified release. Search also surfaced identity-bearing `Log.i` statements in source; they are not part of the release-surviving warning/error sink set. This does not make raw identity logging acceptable in debug builds or external diagnostic captures.

## Commit sequence on the follow-up branch

- `eacb727604a1ed23d2f7142663d41f68ed781649` — remove manufacturer/model from gate error log.
- `1a98953ef907bcdb22ff4e70304be0476df2471e` — pseudonymise Keystore alias warning.
- `01f196e66d3be7261437dca100902a8d221d4ba7` — extend AST sensitive inventory and fixtures.
- `61bfb4799c0d77fb5d75746f763d208ce0cd2439` — add source regression test.
- `2f910e0668f269f75fc00f3424d0db1d4d533d2e` — tidy alias assertion.
- `a1ad4e353d3155ab03f3611521d76485e2b30b57` — correct assertion literal.
- `898aa7f14d7cd17df2743dad29a1114eedbedfb8` — update S10-N2 tracker row.
- `24330af3b9c878bd6e88b4bb9bfd4d690244e5c2` — refresh current agent handoff.

## Residual limitations and acceptance

1. The compiler AST policy is a source-policy tripwire, not whole-program taint analysis. It does not fully model arbitrary dataflow through builders, mutable maps, reflection, aliases across method boundaries, opaque helper returns or third-party callback internals.
2. The manual review is based on repository source inspection and current search inventory. It cannot establish runtime behavior for every device/library implementation.
3. Physical-device release-logcat acceptance, including real app workflows, must wait until the owner declares the app feature-complete. This review did not install an APK or perform physical-device testing.
4. Keep S10-N2 **Partial** until this branch's CI and Security Scan pass, remaining source-flow review is accepted, and post-feature-complete device logcat QA is completed.
5. Keep `security-remediation/FINAL_SIGNOFF.md` **PENDING**. Passing CI is not production security approval.
