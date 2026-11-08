# S07-L2 — libsignal private-key destruction boundary

Date: 2026-11-06
Application dependency: Signal libsignal 0.54.1
Pinned upstream source commit: `ee321f2697666fce90f793a876226cad830585b3`

## Verified source behavior

DuoFat's `app/build.gradle` pins `org.signal:libsignal-android:0.54.1` and the matching 0.54.1 Java client artifact.

In the exact upstream v0.54.1 source:

1. `ECPrivateKey.java` implements `NativeHandleGuard.Owner`. Its `finalize()` calls `Native.ECPrivateKey_Destroy(unsafeHandle)`. The Java API does not expose a deterministic `close()` / `destroy()` method for this key wrapper. `NativeHandleGuard.close()` is only a reachability fence; it does not destroy the key.
2. Rust `PrivateKey` stores an `x25519_dalek::StaticSecret` and derives `Clone`.
3. The pinned Rust protocol crate selects `x25519-dalek = 2.0.0` with `static_secrets`. In x25519-dalek 2.0.0, `StaticSecret` is configured with `Zeroize` and `zeroize(drop)` when the crate's `zeroize` feature is enabled. That feature is included in the crate's default feature set. Therefore the native secret buffer is zeroized when each `StaticSecret` instance is actually dropped.

Primary references:
- [DuoFat dependency pin](../../app/build.gradle)
- [libsignal v0.54.1 ECPrivateKey.java](https://github.com/signalapp/libsignal/blob/ee321f2697666fce90f793a876226cad830585b3/java/shared/java/org/signal/libsignal/protocol/ecc/ECPrivateKey.java)
- [libsignal v0.54.1 curve25519.rs](https://github.com/signalapp/libsignal/blob/ee321f2697666fce90f793a876226cad830585b3/rust/protocol/src/curve/curve25519.rs)
- [libsignal v0.54.1 protocol Cargo.toml](https://github.com/signalapp/libsignal/blob/ee321f2697666fce90f793a876226cad830585b3/rust/protocol/Cargo.toml)
- [x25519-dalek 2.0.0 StaticSecret source](https://docs.rs/x25519-dalek/2.0.0/src/x25519_dalek/x25519.rs.html#560-579)
- [x25519-dalek 2.0.0 feature set](https://docs.rs/crate/x25519-dalek/2.0.0/features)

## What DuoFat's wipe does and does not prove

`WipeHelper.eraseLocalData()` calls `SeedPhraseHelper.clearDerivationCache()`, which removes the static Java reference to the cached derived identity key pair. That makes the object graph eligible for collection, but it does not synchronously invoke libsignal's finalizer or force the native handle to be destroyed at the instant the wipe method returns.

The native `StaticSecret` is zeroized on drop, but Java-side finalization is nondeterministic, `PrivateKey` is cloneable, and this wrapper has no public deterministic destruction primitive. The audit also does not prove that every transient byte-array copy outside the native `StaticSecret` is erased at wipe time.

## Status

Keep **S07-L2 Partial**. The supported native destruction/zeroization boundary is documented and no unsupported reflection or unsafe memory mutation is introduced. The remaining limitation is deterministic, synchronous destruction of every in-memory copy on wipe. Close only through an upstream-supported explicit destruction API or a recorded owner residual-risk decision; physical-device memory/remanence testing remains separate release QA.

## Implementation addendum — PR #271 (2026-11-08)

The source review found no public deterministic `close()`/`destroy()` API on the pinned 0.54.1 Java `ECPrivateKey` wrapper. No dependency upgrade, reflection, forced GC or JNI/native-memory workaround was introduced because the build uses an exact three-part Gradle dependency arrangement and a specially stripped client JAR for D8 compatibility.

PR #271 (`efceb00bc7fac0361804f98cf84458e69a75c0c3`, merged as `87e558c25bd810dafb8b47d97e52c2022a4a94fe`) adds bounded best-effort cleanup of **app-owned** memory:

- `CachedDerivation` stores its own defensive copy of the seed fingerprint and zeros that copy when evicted.
- `deriveIdentityKeyPair()` and `clearDerivationCache()` share the class monitor so cache eviction cannot overlap an executing derivation.
- Derivation-local fingerprint, HKDF info label and serialized private-key input are zeroed in `finally` blocks.
- HKDF clears its app-owned salt, PRK and intermediate output arrays and rejects null inputs / output lengths outside 0–32 bytes; byte-for-byte derivation output is unchanged.
- Regressions cover cache defensive-copy/zeroization, shared-monitor serialization, HKDF output stability, caller-input preservation and output-length checks.

Verification on exact PR head `efceb00bc7fac0361804f98cf84458e69a75c0c3`: CI [42448400756](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400756) passed all 10 jobs (unit, lint, debug APK, R8, Espresso, API 35 predictive-back, Server + Worker, AST privacy guard, vendored libsignal integrity and Gradle wrapper integrity); Security [42448400818](https://github.com/kumarclaude4-hash/DuoFatass/actions/runs/42448400818) passed CodeQL, Gitleaks and SBOM.

**Residual remains:** cache eviction does not synchronously destroy native key handles; other callers may retain `IdentityKeyPair` references, native handles can be cloned, Java crypto providers may retain internal copies, and a later derivation call can repopulate the cache. The application lifecycle must prevent new identity work after wipe. Native zeroization occurs when each native secret is actually dropped/finalized, not necessarily at wipe return. S07-L2 remains **Partial** pending a supported upstream destruction API or explicit owner residual-risk approval; physical memory/remanence testing is separate release QA.
