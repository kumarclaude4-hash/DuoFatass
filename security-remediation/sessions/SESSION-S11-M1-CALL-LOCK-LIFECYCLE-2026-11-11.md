# S11-M1 — Call lock lifecycle regression coverage

**Date:** 2026-11-11  
**Branch:** test/call-lock-lifecycle-regressions  
**Finding:** S11-M1  
**Disposition:** Partial — runtime counter/wiring regressions added; live-call device acceptance remains

## Existing implementation re-verified

- `CallActivity` participates in `AppLockManager` foreground counting only when no background timestamp is already pending.
- It evaluates `shouldLock()` on a return after being stopped/adopted, not on first start; a live call arriving while the app was backgrounded is not interrupted on first launch.
- A timed-out return launches `LockScreenActivity` with the opt-in return-to-call extra.
- `InCallChatActivity` participates in the same foreground count while visible and preserves an older pending timestamp.
- `LockScreenActivity.unlock()` returns to the call only after the credential path succeeds and after `AppLockManager.onAppForegrounded()`.
- The code deliberately does not convert CallActivity to BaseActivity, because that could apply its generic offline/lock gates over a connecting call.

## Changes in this PR

1. Added `AppLockManagerLifecycleTest` using Robolectric at SDK 28.
   - Two overlapping started Activities do not create a background timestamp when the first one stops.
   - The transition from one started Activity to zero records the background timestamp.
   - `onActivityStarted()` does not erase a previously pending timestamp before the caller can make its lock decision.
   - `onAppForegrounded()` clears the timestamp.
2. Extended `CallSessionStructureTest` to assert that both CallActivity and InCallChatActivity check the pending timestamp before incrementing the started-Activity counter and only decrement when their local `countsForLock` bit proves they incremented it.
3. Updated `BUG_TRACKER.md`, the comprehensive remaining review, the session index, START_HERE and `docs/CALL_MINIMIZE_PIP.md`.

## Verification scope and residual

The Robolectric tests exercise the actual AppLockManager implementation against Android SharedPreferences under Robolectric. The structural test guards Activity lifecycle wiring and ordering. These tests do not create a real WebRTC media session, tap the ongoing-call notification, or execute the complete LockScreenActivity credential/return sequence.

**Still required after feature completion:** start a real call, verify no premature timeout during call and in-call chat, minimise beyond the lock timeout, return via notification, verify lock overlays the call, authenticate and confirm the same live call/audio resumes. Also test incoming call while locked, process recreation, call end, stale notification and supported OEM/API matrix.

Do not mark S11-M1 Fixed from JVM/structural tests alone.
