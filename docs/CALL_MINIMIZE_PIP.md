# Call screen: back-to-minimise and Picture-in-Picture

Status: implemented in `CallActivity` / `CallForegroundService` / `AndroidManifest.xml`.
Inspired by Google Meet's behaviour for **video calls and in-call chat only** — not a feature-for-feature copy.

## Problem

`CallActivity` had no back handling. System back finished the activity, and `onDestroy()` calls
`CallManager.hangup()` when the call is still live, so **pressing back ended the call**. The
on-screen back arrow called `hangup()` directly. Users expect back to leave the call screen
without dropping the call.

## Behaviour

| Action | Voice call | Video call |
|---|---|---|
| System back / back gesture / on-screen arrow | Call task goes to the back — you land on the screen you came from; call continues | Shrinks to PiP; call continues |
| Home / Recents | Call goes to the background | Shrinks to PiP (Android 12+ auto-enter; 8–11 via `onUserLeaveHint`) |
| End button, notification **End**, PiP **End** | Hangs up | Hangs up |
| Open in-call chat (video calls) | Opens chat, **no PiP** | Opens chat, **no PiP** |
| Call already `ENDED` / `FAILED` | Back just closes the screen | Back just closes the screen |
| PiP unavailable (device lacks feature, or disabled by user) | — | Falls back to the voice-call behaviour |

The way back into a minimised call is the ongoing-call notification (`CallForegroundService`),
the call's card in Recents, or tapping the PiP window.

## How it works

### Back handling
`setupButtons()` registers an `OnBackPressedCallback` via `getOnBackPressedDispatcher()` and points the
on-screen arrow at the same `minimizeCall()`. **`onBackPressed()` is not used** because the manifest sets
`android:enableOnBackInvokedCallback="true"`; with predictive back opted in, overrides of
`Activity.onBackPressed()` are not invoked on Android 13+.

`minimizeCall()` asks `CallBackPolicy.onBack(callActive, pipAvailable)` (pure Java, unit-tested):
1. call not active → `CLOSE` → `finish()`
2. live video call on a PiP-capable device → `ENTER_PIP` (falls through to 3 if the system refuses)
3. otherwise `BACKGROUND` → release the proximity lock and `moveTaskToBack(true)`

Only `endCall()` hangs up. The End button, the notification **End** action and the PiP **End** action all
funnel into it; back / Home never do. (`CallManager.hangup()` is a no-op once the call is `ENDED`, so
reaching it more than once is safe — the cleanup merely replaced three copies of the same two lines.)

### Picture-in-Picture
- Manifest (`CallActivity`): `supportsPictureInPicture`, `resizeableActivity`, and
  `configChanges="screenSize|smallestScreenSize|screenLayout|orientation"`. The `configChanges` entry is
  required: without it the PiP resize recreates the activity, tearing down the `SurfaceViewRenderer`s
  mid-call.
- `buildPipParams()` — 9:16 aspect ratio, **Mute** and **End** `RemoteAction`s, and on Android 12+
  `setAutoEnterEnabled(isCallActive() && !suppressAutoPip)`.
- The PiP actions fire the **same broadcasts as the notification buttons**
  (`BROADCAST_TOGGLE_MUTE`, `BROADCAST_END_CALL`) through immutable, package-scoped `PendingIntent`s, so
  there is one code path for mute/end regardless of where the tap came from.
- `updatePipParams()` is re-run when mute toggles (icon), on every call-state change (auto-enter must turn
  off once the call ends), and around sub-screen launches.
- `applyPipUi(boolean)` hides the header, controls bar, self-view tile and banners in PiP and restores them
  on expand. The self-view and TURN-banner visibility are saved/restored rather than forced, so a camera-off
  or dismissed banner stays that way.

### PiP polish
- **No remote video yet / partner's camera off:** the full-screen avatar (128 dp) would overflow a PiP
  window, so `setCompactAvatar(true)` shrinks it to 56 dp, adds the partner's name (the header that normally
  carries it is hidden in PiP) and tightens the status line. Everything is restored on expand.
- **Smooth shrink animation:** `pipSourceRectHint()` supplies the video surface's on-screen rect, cropped
  (centred) to the 9:16 PiP ratio. It is refreshed when the surface is first laid out — the very first
  `onResume()` can run before layout, when there is no rect yet.

### `suppressAutoPip`
`onUserLeaveHint()` also fires when this activity starts another one, and Android 12+ auto-enter can trigger
the same way. Opening in-call chat or the Bluetooth permission dialog must **not** put the call in PiP, so
those paths set `suppressAutoPip` and push fresh params *before* launching. It is cleared in `onResume()`
and in `onRequestPermissionsResult()` (a permission request that resolves without a dialog — e.g. "don't
ask again" — never pauses the activity, so `onResume()` alone would leave PiP suppressed).

### Proximity wake lock (voice calls)
Released in `onUserLeaveHint()` and `minimizeCall()`; re-acquired in `onResume()` if it was dropped. It is
**not** released in `onPause()`/`onStop()`: the proximity sensor blanking the screen pauses the activity,
and releasing there would turn the screen back on and loop.

### The call runs in its own task
`CallActivity` declares `android:taskAffinity="${applicationId}.call"`, and every launch site
(`ChatMediaActivity`, `ContactDetailActivity`, `IncomingCallActivity`, the notification in
`CallForegroundService`) starts it with `FLAG_ACTIVITY_NEW_TASK`. Effect: `moveTaskToBack(true)` sends *only the
call* to the back, revealing the screen the user came from (WhatsApp-style) instead of dumping them on the home
screen, while the call stays alive in its own Recents card. In-call chat is started from `CallActivity` without
`NEW_TASK`, so it stays inside the call task and back from chat returns to the call.

The affinity is only honoured together with `NEW_TASK` — without the flag the call would silently land in the
caller's task and "send to back" would hide the whole app. `CallTaskIsolationTest` pins the manifest attribute and
every launch site so the two cannot drift apart.

Trade-off: Recents now shows two cards for the app (chats and the call) while a call is live; both go away when it ends.

### Notification
`CallForegroundService`'s content intent now adds `FLAG_ACTIVITY_NEW_TASK` alongside `SINGLE_TOP |
REORDER_TO_FRONT` so tapping it reliably brings the call back from the background.

## Review notes

Checked during review and deliberately left as-is:
- **Camera in PiP** keeps running — `CallForegroundService` already declares the camera foreground-service
  type and is started from `onCreate()` while the app is visible (required on Android 11+).
- **Chat banner in PiP** — `deliverNewMessageBanner()` parks the preview in `pendingBannerPreview` while in
  PiP; it is shown on expand from both `onResume()` and `applyPipUi(false)` (some OEMs keep a PiP activity
  resumed, so `onResume()` may not fire). The null-out makes the two paths idempotent.
- **TURN warning banner** is not shown while in PiP; the state is restored on expand.
- `notifActionReceiver` is `RECEIVER_NOT_EXPORTED`; PiP `PendingIntent`s run as this app, so delivery works.

## Automated tests

`CallBackPolicyTest` (plain JVM, no Android SDK — same style as `LockScreenManifestTest`):
- the decision table for `onBack(...)` and `autoEnterPip(...)`
- structural guards on `CallActivity.java`: `minimizeCall()` decides through `CallBackPolicy` and contains no
  `hangup(` / `endCall(`; system back is an `OnBackPressedCallback` (not an `onBackPressed()` override);
  the arrow routes to `minimizeCall()`; only `endCall()` hangs up and the End button + notification End use it

Robolectric was not used: `CallActivity.onCreate` initialises libwebrtc and Firebase, which cannot load on the JVM.
The structural tests were checked against bug-shaped variants (back calling `hangup()`; no back callback) and fail
as intended.

## App-lock interaction (`BaseActivity`) — analysis, not changed

`CallActivity` extends `AppCompatActivity`, not `BaseActivity`, so it skips everything `BaseActivity.onStart()`
does: session check, auto sign-out, the PIN lock, the offline wall, and the `AppLockManager.onActivityStarted()`
reference count. Consequences, given that back/Home now leave a live call running:

1. **Return to a minimised call is not lock-checked.** Tapping the ongoing-call notification or the PiP window
   shows the call (and the in-call chat) even if the app-lock timeout has elapsed. Before this change a user
   could already do this via Home; back previously ended the call, so it wasn't reachable that way.
2. **The lock timer appears to run during a call** (the call screen isn't counted as a foreground activity), so a
   long call can be followed by an immediate PIN prompt when the user returns to a chat screen. That direction is
   fail-safe, only mildly surprising.

Why `CallActivity` was **not** simply switched to `BaseActivity`: its `onStart()` would (a) launch
`LockScreenActivity` over an incoming/connecting call, (b) route to `NoInternetActivity` mid-call via the offline
wall, and (c) can sign the user out and `finish()` the call screen. Those are product decisions, not a refactor.

Suggested follow-up if (1) matters for your threat model: in `CallActivity`, only when returning from a minimise/PiP
(not on first start), check `AppLockManager.shouldLock(this)` and launch `LockScreenActivity` over the call the way
`BaseActivity` does. Needs a device test with the lock timeout set short. (I did not read `AppLockManager`'s
timeout logic in full, so confirm the semantics before relying on point 2.)

## Manual test plan

Run on at least one Android 8–11 device and one Android 12+ device.

Video call:
- [ ] Back (button and gesture) → PiP, call audio/video continue
- [ ] Call started from a chat (`ChatMediaActivity`) and from contact details: call opens as its **own Recents card**
- [ ] Home → PiP; Recents → PiP
- [ ] PiP **Mute** toggles mic and the icon; state matches the in-app mute button when expanded
- [ ] PiP **End** hangs up and closes the window; remote side sees the call end
- [ ] Tap PiP → full screen, controls and self-view back, camera-off state preserved
- [ ] Open in-call chat → **no** PiP; chevron-down returns to the call
- [ ] Chat message arrives while in PiP → pill shows after expanding
- [ ] Remote ends the call while in PiP → window closes
- [ ] PiP disabled in system settings → back falls back to background, call survives
- [ ] Back during *Ringing…* / *Connecting…* → PiP, no crash

Voice call:
- [ ] Back / arrow → you return to the screen you came from (chat), call still running, notification stays, audio continues
- [ ] Home → call goes to the background, audio continues
- [ ] Incoming call → accept → back → lands on the previous app/screen, not stuck on a blank task
- [ ] Notification tap → call screen returns
- [ ] Notification **Mute** / **End** work
- [ ] Phone against ear in another app does not blank the screen; back in the call screen it does
- [ ] Back after the remote hung up → screen closes

Regression:
- [ ] Bluetooth permission prompt (Android 12+) does not trigger PiP, including after choosing "Don't allow"
- [ ] Rotating / split-screen does not recreate the call screen

## Deliberately not built (and why)

An earlier suggestion was to move `CallManager` into the foreground service so a call outlives its activity. After
reading the lifecycle code that was **not** attempted here. `CallActivity` owns more than the UI: the call-record write
on `ENDED`, the no-answer watchdog, the duration timer, the audio-focus request, the headset and notification-action
receivers, and the in-call chat listener. Making the call survive its activity means moving or duplicating all of
that into a headless owner, re-adopting live WebRTC tracks into a new activity, and routing the notification's
End/Mute with no activity registered — roughly 600 lines in the most failure-prone path of the app, which cannot be
compiled or run without a device and SDK. The own-task approach above delivers the user-visible part (back goes to
the previous screen; the call keeps running; Recents/notification return) for a few lines and a pinned test.

What it does **not** give you, and the design if you want it:
- **Call survives the activity being destroyed** (e.g. PiP closed with the X on some OEMs, or a low-memory kill):
  needs a `CallSession` owner (process singleton or the service) holding `CallManager`, a headless
  `CallListener` that writes the call record and stops the service on `ENDED`/`FAILED`, `onDestroy()` that detaches
  instead of hanging up while the call is live, and an adopt path in `onCreate()` that re-attaches renderers and
  restores mute/camera/speaker/timer state. Restrict detaching to `CONNECTED` calls to avoid reimplementing the
  no-answer watchdog.
- **In-app "return to call" bar:** not added. A generic overlay in `BaseActivity` would sit on top of every screen's
  toolbar or input bar, and I can't see/verify each layout. Per-screen placement (e.g. the chat list only) is the
  safer route.

## Known limitations / follow-ups

- **Closing the PiP window with the X** — behaviour is OEM/OS-version dependent. If the system destroys the
  activity, `onDestroy()` hangs up. Verify on device; if the call should survive, decouple `CallManager`
  from the activity lifecycle.
- Home from the **in-call chat screen** does not enter PiP. Deliberately not done: PiP is per-activity, so it would
  mean duplicating the PiP wiring in `InCallChatActivity` (and handing the video surface across) for little gain —
  the call keeps running behind its notification.
- No in-app "return to call" bar (see above); use the notification or Recents.
- Other screens that overrode `onBackPressed()` (incl. `LockScreenActivity`) were bypassed by predictive back on
  Android 13+. That is a separate security fix: `fix/predictive-back-overrides` (`BUG_TRACKER` S11-H1).
