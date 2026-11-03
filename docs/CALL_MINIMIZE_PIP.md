# Calls: back keeps the call, Picture-in-Picture, and a call that outlives its screen

Inspired by Google Meet's behaviour for **voice/video calls and in-call chat** — not a feature-for-feature copy.

> **Status: built and statically checked, not run on a device.** Work through the
> [manual test plan](#manual-test-plan) before merging. `BUG_TRACKER` entries `S11-H1` and `S11-M1` are **Partial** for the same reason.

## Problem

`CallActivity` owned the whole call. It created the `CallManager`, listened to it, wrote the history row, handled the notification's
End/Mute buttons, held audio focus — and hung up in `onDestroy()`. So **anything that destroyed the screen ended the call**:
pressing back, closing a PiP window, the system reclaiming the activity, a night-mode or locale change recreating it.

## Behaviour

| Action | Voice call | Video call |
|---|---|---|
| Back / back gesture / on-screen arrow | The call's task goes to the back → you land on the screen you came from; call continues | Shrinks to PiP (Mute / End on the window); call continues |
| Home / Recents | Call goes to the background | Shrinks to PiP (Android 12+ auto-enter; 8–11 via `onUserLeaveHint`) |
| Home **from the in-call chat** | Background | Chat shrinks to PiP showing the partner's video |
| End button · notification **End** · PiP **End** | Hangs up | Hangs up |
| Open in-call chat | Opens chat, no PiP | Opens chat, no PiP |
| PiP window closed with the **X**, activity reclaimed, night-mode change | Call **continues** (detached) | Call **continues** (detached) |
| Swipe the call's card away in Recents | Call **continues** (detached) — same as the PiP X | same |
| Call already `ENDED` / `FAILED` | Back closes the screen | Back closes the screen |
| PiP unavailable (no feature / disabled by the user) | — | Falls back to the voice behaviour |

Ways back into a minimised call: the ongoing-call **notification**, the **"Tap to return to call"** bar at the top of every in-app screen,
the call's **Recents** card, or tapping the **PiP** window.

A call that is **still ringing or connecting** is *not* detached when its screen is destroyed — the no-answer watchdog and TURN wait live on
the screen, so it behaves as before (hangs up).

## Architecture

```
                         ┌────────────────────────────── process ───────────────────────────────┐
 CallForegroundService ◄─┤ CallSession  (singleton)                                              │
  (notification,         │   owns: CallManager · identity · history row · audio focus            │
   keeps process alive)  │         headset + End/Mute receivers · mute/camera/speaker state      │
                         │   hub ──► forwards CallManager callbacks to the attached screen       │
                         └──────▲───────────────────────────────▲───────────────────────────────┘
                       attach/detach/adopt                       │ current()/isLive()
              CallActivity (UI only)  ·  InCallChatActivity      ReturnToCallBar ◄── BaseActivity
```

* **`CallSession`** — process-wide owner of the one live call. A call screen **attaches** (`attach`) and receives the same
  `CallManager.CallListener` callbacks as before; when the screen goes away while the call is *connected* it **detaches** and the call carries on.
  A new screen **adopts** the live session (`adoptSession`): it re-creates the renderers, re-attaches the live tracks, and repaints mute / camera /
  speaker / timer from the state the session and `CallManager` kept.
* **Teardown** (`CallSession.teardown()`, idempotent): stops the notification, ends the audio route, hangs up or releases the `CallManager`, abandons
  audio focus, clears `current()`. Triggered by the attached screen's `onDestroy()` (after it released its renderers), or by the session itself on
  `ENDED`/`FAILED` when no screen is attached (posted to the main thread — ENDED is raised from *inside* `CallManager.hangup()`).
* **History row** — written once by the session (`savedRecordId` guard), with or without a screen.
* **Decision rules** live in pure, unit-tested `CallSessionPolicy` (`shouldDetachOnDestroy`, `isResumeRequest`, `shouldLockOnReturn`, `formatDuration`)
  and `CallBackPolicy` (`onBack`, `autoEnterPip`).
* **One call at a time.** Launching the call screen while a call is live adopts it; if the launch was a request for a *new* call (not a resume of the
  live one) a toast says "You're already in a call".

### `onDestroy()` of the call screen
```
release screen-owned state → detachVideoSinks() → releaseVideoRenderers()        (always, in this order)
if shouldDetachOnDestroy(endedByUser, wasConnected, active)  →  session.detach(this)   // call continues
else                                                         →  session.teardown()     // hang up / free
```
`endedByUser` is set only by `endCall()` (End button, notification End, PiP End). `hangup()` is a no-op once the call is `ENDED`.

## Back handling
`minimizeCall()` asks `CallBackPolicy.onBack(callActive, pipAvailable)`: `CLOSE` (call over) · `ENTER_PIP` (live video call, PiP supported; falls through
if the system refuses) · `BACKGROUND` (`moveTaskToBack(true)`). Back never hangs up. System back is an `OnBackPressedCallback` because the manifest
opts in to predictive back, which bypasses `onBackPressed()` on Android 13+ (see `S11-H1`).

## The call runs in its own task
`CallActivity` declares `android:taskAffinity="${applicationId}.call"` and every launch site (`ChatMediaActivity`, `ContactDetailActivity`,
`IncomingCallActivity`, `CallSession.returnToCall`, the notification) uses `FLAG_ACTIVITY_NEW_TASK`. `moveTaskToBack` then sends *only the call* to the
back, revealing the screen the user came from. The affinity is ignored without `NEW_TASK` — `CallTaskIsolationTest` pins both. In-call chat starts
from `CallActivity` without `NEW_TASK`, so it stays in the call task. Trade-off: two Recents cards (chats + call) while a call is live.

## Picture-in-Picture
Shared by both call screens through `CallPip`: 9:16, **Mute** and **End** actions, auto-enter on Android 12+.

* The actions fire the **same broadcasts as the notification buttons**; `CallSession` owns the receiver (application context, `RECEIVER_NOT_EXPORTED`,
  immutable package-scoped `PendingIntent`s), so Mute/End work with or without a screen.
* **Call screen:** only remote video is shown; compact avatar + partner name when there is no remote video; source-rect hint (cropped to 9:16 by the
  unit-tested `CallPip.cropToPipAspect`) for a smooth shrink animation, refreshed once the surface has a size.
* **In-call chat screen** (video calls): on Home the chat column and the local half of the floating tile are hidden and the tile fills the window
  (partner video only); everything is restored on expand. The video sink is kept attached across `onPause` while floating, and a 1 s loop closes the
  window if the call ends.
* `suppressAutoPip` stops opening in-call chat, the Bluetooth permission dialog, or the lock screen from counting as "leaving the call"; it is cleared in
  `onResume()` **and** `onRequestPermissionsResult()` (a request that resolves without a dialog never pauses the activity).
* Manifest: `supportsPictureInPicture`, `resizeableActivity`, and `configChanges` on both screens, so PiP resizing does not recreate the WebRTC renderers.

## "Tap to return to call" bar
`ReturnToCallBar` (shown by `BaseActivity.onResume`, dismissed in `onPause`) adds a green strip with a live duration to the activity's content frame. The
content is **pushed down** by the bar's height rather than covered, so toolbars and back buttons stay usable; the bar is pulled back to the top with a
negative margin and `clipToPadding` is switched off while it is shown (both restored on dismiss). If a screen draws under the status bar the overlap is
added to the bar's own padding. It removes itself when the call ends. `FullScreenImageActivity` and `MediaViewerActivity` opt out
(`showsReturnToCallBar() == false`): shifting an immersive viewer would break it. Not shown on the lock/PIN/offline gates (they are not `BaseActivity`s).

## App lock
`CallActivity` and `InCallChatActivity` are not `BaseActivity`s, so they used to be invisible to `AppLockManager` (the lock timer ran for the whole
call; returning to a minimised call was never lock-checked — `S11-M1`). Now:

* They count as foreground activities — **only when no background timestamp is pending** (`AppLockManager.hasBackgroundTimestamp`). Otherwise an
  incoming call that wakes a locked app would overwrite the older timestamp when the call screen stops and quietly extend the lock window.
* `CallActivity.onStart()` checks `shouldLock()` on a **return** (stopped before, or a re-adopted instance) — never on the first start, so an incoming
  call is never blocked behind a lock screen — and raises `LockScreenActivity` over the call.
* After a **successful** unlock, `LockScreenActivity.unlock()` sends the user back to the call (extra `EXTRA_RETURN_TO_CALL`, only when `CallSession.isLive()`).
  It runs after `AppLockManager.onAppForegrounded`, i.e. after the credential check; nothing about authentication changed.
* Deliberately **not** done: making `CallActivity` a `BaseActivity`. Its `onStart()` would put the lock screen or the offline wall over a connecting
  call and can sign the user out and `finish()` the call.

## Notification
`CallForegroundService`'s content intent carries `FLAG_ACTIVITY_NEW_TASK | SINGLE_TOP | REORDER_TO_FRONT` and `EXTRA_RESUME`, so tapping it brings the
existing screen forward, or a new one adopts the live call. `CallSession.teardown()` asks the service to stop itself (`ACTION_STOP`) and falls back to
`stopService()` when the app is in the background and may not start services.

## Proximity wake lock (voice calls)
Released in `onUserLeaveHint()` and `minimizeCall()`; re-acquired in `onResume()`. **Not** released in `onPause()`/`onStop()`: the proximity sensor
blanking the screen pauses the activity, and releasing there would turn the screen back on and loop. Detached calls hold no lock (it is screen state).

## Automated tests (plain JVM — no Android SDK, no Robolectric)
`CallActivity.onCreate` initialises libwebrtc and Firebase, which cannot load on the JVM, so tests are pure-logic plus structural guards that read the sources.

| Test | Guards |
|---|---|
| `CallBackPolicyTest` | back decision table; back path never reaches `hangup()`; system back is an `OnBackPressedCallback`; only `endCall()` ends a call; screen registers no receivers |
| `CallSessionPolicyTest` | detach / resume / lock-on-return decision tables; `formatDuration`; PiP crop math |
| `CallSessionStructureTest` | `onDestroy` decides via policy and never hangs up itself; sinks detached before renderers released before teardown; teardown idempotent; history row written once; headless teardown posted after the manager call returns; lock participation rules; lock redirect only after authentication; chat counted by `AppLockManager`; bar shown/dismissed with the screen; viewers opt out; PiP declared on both screens; chat PiP shows only the remote tile and keeps its sink while floating |
| `CallTaskIsolationTest` | manifest `taskAffinity` + `FLAG_ACTIVITY_NEW_TASK` at every launch site |
| `PredictiveBackOverrideTest` | no `onBackPressed()` overrides; gate screens use `BackPressGuard` |

Every suite was re-run against bug-shaped variants and fails as intended (e.g. `onDestroy` hanging up, the lock redirect before authentication,
the call screen counting itself unconditionally, the bar never dismissed, an unguarded history row, PiP removed from the chat manifest entry).

## Review notes (checked, left as is)
* **Camera in PiP / detached:** the foreground service already declares the camera type and is started from `onCreate()` while the app is visible.
* **Detached video:** tracks keep running with no sink; the camera capturer keeps running while the call is detached (same as backgrounded today).
* **Two live screens:** if a second instance adopts while an older one still exists, `attach()` replaces the UI and the older screen's `detach()` is a
  no-op, so its eventual destruction does not hang up.
* **Chat banner while detached/PiP:** parked in `pendingBannerPreview`, shown on return (both `onResume()` and `applyPipUi(false)`).

## Manual test plan
Run on at least one Android 8–11 device and one Android 12+ device. Put the lock timeout at its shortest for the lock rows.

**Back / PiP — video**
- [ ] Back (button + gesture) → PiP, audio and video continue · Home → PiP · Recents → PiP
- [ ] PiP **Mute** toggles the mic and its icon; matches the in-app button when expanded · PiP **End** hangs up, window closes, partner sees it end
- [ ] Tap PiP → full screen, controls + self-view back, camera-off state preserved
- [ ] Open in-call chat → **no** PiP · Home **from the chat** → PiP with the partner's video only · tap it → chat back, tile restored
- [ ] Partner ends the call while in PiP (call screen and chat) → window closes
- [ ] PiP disabled in system settings → back falls back to the background; call survives
- [ ] Back during *Ringing…* / *Connecting…* → PiP, no crash

**Back — voice**
- [ ] Back / arrow → the screen you came from, call still running, notification present, audio continues
- [ ] Home → background, audio continues · notification tap returns · phone to ear in another app does **not** blank the screen

**Call outlives its screen (the new part)**
- [ ] Close the PiP window with the **X** → call keeps going; notification present; tap it → call screen with live video + correct timer + mute state
- [ ] Swipe the call's Recents card away → call keeps going; notification **End** hangs up
- [ ] Toggle dark mode mid-call → brief flicker, call survives, controls correct
- [ ] While detached: partner hangs up → notification disappears, **one** history row exists, camera light off
- [ ] While detached: notification **Mute** works; plug/unplug a wired headset → audio re-routes
- [ ] Detached, then tap the return bar from the chat list → call screen adopts the call
- [ ] Start a second call while one is live → "You're already in a call", live call shown
- [ ] Incoming call → accept → back → previous screen, no orphan task after hang-up

**Return bar**
- [ ] Visible on the chat list, chats, settings, contact details; shows a ticking duration; tap returns to the call
- [ ] Toolbars/back buttons still tappable (content pushed down, not covered); bar disappears when the call ends
- [ ] **Not** shown in the full-screen image and media viewers

**App lock** (short timeout)
- [ ] During a long call the lock screen does **not** appear while you are on the call or its chat
- [ ] Minimise, wait past the timeout, tap the notification → lock screen over the call; unlock → straight back to the call
- [ ] Locked app wakes for an incoming call → call is **not** blocked; after hang-up the lock still appears
- [ ] Back gesture on the lock screen does not dismiss it (Android 13+) — `S11-H1`

**Regression**
- [ ] Bluetooth permission prompt does not trigger PiP, including after "Don't allow"
- [ ] Rotating / split-screen does not recreate the call screen · call history shows correct outcome/duration for answered, missed, failed calls

## Known limitations
* Not run on a device. Behaviours that vary by OEM/OS version and need a look: what the system does when the PiP X is pressed (the design assumes the
  activity is destroyed or stopped — either way the call now survives), and auto-enter timing on Android 12+.
* Swiping the call's Recents card away leaves the call running (as WhatsApp does). Some OEMs kill the whole process on swipe — then the call ends.
* A call that is still ringing/connecting is not detached.
* The return bar's top-edge handling assumes the default (non-edge-to-edge) window for `targetSdk 34`; screens that draw under the status bar get the
  measured overlap, but this is worth a visual check on a notched device.
* Two Recents cards while a call is live (chats + call).
