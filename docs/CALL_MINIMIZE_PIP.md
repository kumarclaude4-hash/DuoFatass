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
| System back / back gesture / on-screen arrow | App goes to background; call continues | Shrinks to PiP; call continues |
| Home / Recents | App goes to background | Shrinks to PiP (Android 12+ auto-enter; 8–11 via `onUserLeaveHint`) |
| End button, notification **End**, PiP **End** | Hangs up | Hangs up |
| Open in-call chat (video calls) | Opens chat, **no PiP** | Opens chat, **no PiP** |
| Call already `ENDED` / `FAILED` | Back just closes the screen | Back just closes the screen |
| PiP unavailable (device lacks feature, or disabled by user) | — | Falls back to the voice-call behaviour |

The way back into a minimised call is the ongoing-call notification (`CallForegroundService`),
or tapping the PiP window.

## How it works

### Back handling
`setupButtons()` registers an `OnBackPressedCallback` via `getOnBackPressedDispatcher()` and points the
on-screen arrow at the same `minimizeCall()`. **`onBackPressed()` is not used** because the manifest sets
`android:enableOnBackInvokedCallback="true"`; with predictive back opted in, overrides of
`Activity.onBackPressed()` are not invoked on Android 13+.

`minimizeCall()`:
1. call not active → `finish()`
2. `enterPip()` succeeded → done
3. otherwise release the proximity lock and `moveTaskToBack(true)`

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

## Manual test plan

Run on at least one Android 8–11 device and one Android 12+ device.

Video call:
- [ ] Back (button and gesture) → PiP, call audio/video continue
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
- [ ] Back / arrow / Home → app backgrounds, notification stays, audio continues
- [ ] Notification tap → call screen returns
- [ ] Notification **Mute** / **End** work
- [ ] Phone against ear in another app does not blank the screen; back in the call screen it does
- [ ] Back after the remote hung up → screen closes

Regression:
- [ ] Bluetooth permission prompt (Android 12+) does not trigger PiP, including after choosing "Don't allow"
- [ ] Rotating / split-screen does not recreate the call screen

## Known limitations / follow-ups

- **Closing the PiP window with the X** — behaviour is OEM/OS-version dependent. If the system destroys the
  activity, `onDestroy()` hangs up. Verify on device; if the call should survive, decouple `CallManager`
  from the activity lifecycle.
- Home from the **in-call chat screen** does not enter PiP (only `CallActivity` supports it).
- Back lands on the home screen / previous app, not this app's chat list, and there is no in-app "return to
  call" bar. Doing that properly needs `CallManager` owned by a service rather than `CallActivity`.
- Other screens override `onBackPressed()` (`LockScreenActivity`, `DevicePinGateActivity`, `SetupPinActivity`,
  `ForcedPrimaryPinRotationActivity`, `ForcedDuressRotationActivity`, `PermissionsOnboardingActivity`,
  `NoInternetActivity`). With predictive back enabled these overrides are likely not invoked on Android 13+.
  `LockScreenActivity` intentionally blocks back, so it is worth verifying on a device and migrating to
  `OnBackPressedCallback` in a separate change. Out of scope here.
