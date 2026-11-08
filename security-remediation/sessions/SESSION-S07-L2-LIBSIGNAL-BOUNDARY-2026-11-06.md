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


## Upstream API re-check — 2026-11-08

The upstream Java wrapper was re-checked after the original 0.54.1 analysis to determine whether a newer supported deterministic destroy/close API could close the residual without reflection or unsafe JNI access.

- Pinned application version remains libsignal 0.54.1; the pinned source commit is `ee321f2697666fce90f793a876226cad830585b3`. Its `ECPrivateKey` wrapper still has no public deterministic `close()`/`destroy()` method; native destruction is invoked through finalization.
- Current upstream source was inspected at commit `850942581c06fa65c0323fd52029afa02fa1a1ba`: [ECPrivateKey.kt](https://github.com/signalapp/libsignal/blob/850942581c06fa65c0323fd52029afa02fa1a1ba/java/shared/java/org/signal/libsignal/protocol/ecc/ECPrivateKey.kt) and [NativeHandleGuard.java](https://github.com/signalapp/libsignal/blob/850942581c06fa65c0323fd52029afa02fa1a1ba/java/shared/java/org/signal/libsignal/internal/NativeHandleGuard.java).
- The newer `NativeHandleGuard` contains a generic `CloseableOwner`, but `ECPrivateKey` still extends `NativeHandleGuard.SimpleOwner`; the wrapper does not expose deterministic close/destroy for this key type. This is not a usable API upgrade path for the application.
- No application source change is justified: reflection, private native-handle mutation or unsafe memory tricks would be unsupported and could corrupt key state. Keep S07-L2 **Partial** until upstream exposes a supported deterministic destruction API for ECPrivateKey or the owner explicitly accepts the GC-timed residual.

This is a source/API review only. It does not claim physical memory-remanence testing or synchronous erasure of Java/native copies. Physical-device acceptance remains deferred until feature-complete.

