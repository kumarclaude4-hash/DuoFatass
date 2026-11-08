---
name: DuoShield libsignal dependency structure
description: Preserve the exact libsignal 0.54.1 Android AAR + compileOnly Maven client + stripped local client JAR topology; never simplify or independently upgrade it; use the public app-owned HKDF helper
---

# DuoShield libsignal dependency structure

## Exact dependency rule

The pinned `app/build.gradle` dependency topology is deliberately three-part. Preserve all three entries exactly and keep their versions aligned:

```groovy
implementation('org.signal:libsignal-android:0.54.1') {
    exclude group: 'org.signal', module: 'libsignal-client'
}
compileOnly 'org.signal:libsignal-client:0.54.1'
implementation files('libs/libsignal-client-0.54.1-stripped.jar')
```

- The Android AAR provides the native JNI libraries and does not provide the Java protocol classes in its `classes.jar`.
- The Maven Java client dependency is `compileOnly`, for compile-time symbol resolution.
- The local stripped Java client JAR is the runtime/DEX implementation. It is derived from the matching 0.54.1 client JAR with six `java.lang.Record` subclasses removed because those classes trigger the project's D8 desugaring failure when packaged through the normal external-JAR path.
- The exclusion on the AAR prevents a second transitive copy of `libsignal-client` and duplicate-class dexing.

Do **not** change the Maven client to `implementation`, remove the local stripped JAR, switch the local JAR to `compileOnly`, bump just one of the three versions, or assume a newer AGP makes this workaround unnecessary. Any dependency upgrade must regenerate the stripped artifact, update verification metadata/hash records, and pass the full Android/R8/integrity CI.

## HKDF boundary

Never import or call `org.signal.libsignal.protocol.kdf.HKDF` in application runtime code. It is a libsignal-internal implementation detail and is not the supported application API. Use the public app-owned helper:

```java
SeedPhraseHelper.hkdfSha256(ikm, info, length)
```

Callers that derive the 32-byte backup key must continue to use length `32`. The helper implements RFC 5869 HKDF-SHA256 with `javax.crypto.Mac`; its byte output is covered by regression tests. Keep the caller-owned input arrays unchanged.

## S07-L2 private-key destruction boundary

`SeedPhraseHelper.clearDerivationCache()` detaches the cached `IdentityKeyPair` reference and zeroes the app-owned cached fingerprint copy. PR #271 also serializes derivation with cache eviction and clears app-owned fingerprint, info, serialized private-key input, HKDF salt, PRK and intermediate output arrays.

This is **not** deterministic native-key destruction. The pinned Java `ECPrivateKey` wrapper exposes no public `close()`/`destroy()`; native destruction remains finalizer/GC-timed, native key objects may be cloned or retained by callers, and crypto providers may retain internal copies. A later derivation call can repopulate the cache, so application lifecycle code must prevent new identity work after wipe. Keep S07-L2 Partial unless upstream adds a supported explicit destruction API or the owner approves the residual.

Primary implementation references: `app/build.gradle`, `SeedPhraseHelper.java`, `SeedPhraseHelperTest.java`, and `security-remediation/sessions/SESSION-S07-L2-LIBSIGNAL-BOUNDARY-2026-11-06.md`.
