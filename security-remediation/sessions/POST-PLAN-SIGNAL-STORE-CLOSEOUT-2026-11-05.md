# Post-plan Signal storage closeout — 2026-11-05

**Scope:** bounded code closeout for S07-M2, S07-L4 and S10-N2.
**Implementation branch:** `security/signal-store-fail-closed-regressions-2026-11-05`
**PR:** #223 — Security: fail closed on corrupt Signal trust state.
**Code head validated:** `bbc3797d1acaea37e86e45871b0d20557bfb9e95`.

## Changes

### S07-M2 — identity trust migration integrity

- Trust remains anchored to the SHA-256 fingerprint of the serialized identity key; the address-to-fingerprint preference is only a lookup pointer.
- Persisted identity records now require canonical Android `Base64.NO_WRAP` encoding. Android's permissive decoder is not trusted to reject ignored garbage characters.
- The pointer fingerprint is recomputed from the stored bytes and compared before the record is accepted.
- A dangling pointer may be repaired from the legacy record only when that legacy record fingerprints to the same value. A conflict or missing backing record raises `TrustRecordCorruptionException`.
- Only a state with neither pointer nor legacy record returns `null` and remains eligible for first-contact TOFU.
- The legacy migration log uses `LogRedact.uid()` and a numeric device ID rather than interpolating the raw peer address.

### S07-L4 — corrupt ratchet-row behavior

- Existing `loadSession` behavior deletes an unusable persisted row and throws `SessionDeserializationException`, rather than silently substituting an empty session.
- Regression tests now prove that corrupt bytes cause both row deletion and typed failure, while a genuinely missing row still returns a fresh, non-persisted session.

### S10-N2 — release-log privacy regression

- Added a source regression assertion that the Signal trust migration log uses the pseudonymized UID helper and does not interpolate the raw peer address.

## Regression coverage added

`DuoShieldSignalStoreRegressionTest` covers:
- genuine first contact;
- legacy UID-keyed migration and read-after-legacy-removal;
- dangling pointer with no target;
- pointer/backing-key fingerprint mismatch;
- dangling pointer conflicting with a different legacy identity;
- malformed/non-canonical legacy Base64;
- malformed fingerprint-backed serialized identity;
- corrupt ratchet-row deletion and typed failure;
- absent ratchet row behavior.

`ReleaseLogPrivacyTest` additionally guards the Signal migration log.

## CI evidence

On code head `bbc3797d1acaea37e86e45871b0d20557bfb9e95`, GitHub Actions completed all 13 reported checks successfully:
- Android unit tests;
- Android lint;
- Espresso instrumentation, including the explicit non-zero test-execution guard;
- debug APK build;
- server and Worker tests;
- Firestore-related repository checks are not part of this Android-only PR's workflow;
- vendored libsignal JAR integrity;
- Gradle wrapper checksum;
- CodeQL checks;
- Gitleaks secret scan;
- SBOM generation;
- storage Worker build;
- Vercel preview comment check.

The CI job logs show `BUILD SUCCESSFUL` for the Android unit-test task. The Espresso job's emulator task and the follow-up test-execution guard both completed successfully.

## Limits and remaining acceptance

This is code-level regression evidence, not a production sign-off. Still outstanding outside this closeout:
- full HTTP-level invite lifecycle acceptance through production handlers (the invite store has Firestore-emulator tests and routes have structural wiring tests);
- production Firestore rules/index/TTL deployment and staging acceptance;
- production secret/environment audit and credential rotation;
- physical-device QA and signed/minified release/R8 acceptance;
- repository branch-protection verification (the connected GitHub integration returned 403 for the protection endpoint);
- other separately tracked findings, including backup metadata/AEAD, FileProvider scope, App Check, predictive-back/app-lock lifecycle, and token/global-rate-limit acceptance.

No claim is made that every repository finding is closed or that the product is production secure.
