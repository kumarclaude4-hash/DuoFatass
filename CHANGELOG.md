# Changelog

All notable changes to DuoShield are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Added
- Picture-in-Picture for video calls — back / Home / Recents shrinks the call to a floating window showing the remote video, with Mute and End actions; tapping the window expands it back to the full call screen. Android 12+ uses `setAutoEnterEnabled`; Android 8–11 enters PiP from `onUserLeaveHint()`. See `docs/CALL_MINIMIZE_PIP.md`
- PiP window with no remote video shows a compact avatar and the partner's name; PiP shrink animation uses a source-rect hint
- `CallBackPolicy` + `CallBackPolicyTest`: the back/auto-PiP decision is pure, unit-tested logic, with structural guards that back can never reach `hangup()`

### Fixed
- Pressing back (system button, gesture, or the on-screen arrow) on the call screen ended the call. Back now minimises the call and keeps it running; only the End button (or the End action in the notification / PiP window) hangs up
- Proximity wake lock stayed held while a voice call was in the background, which could blank the screen in other apps; it is now released on minimise/Home and re-acquired on return
- Tapping the ongoing-call notification now sets `FLAG_ACTIVITY_NEW_TASK` so the call reliably returns to the front from the background

### Changed
- `CallActivity` now runs in its own task (`taskAffinity`, launched with `FLAG_ACTIVITY_NEW_TASK`): back on a voice call returns to the screen you came from while the call keeps running, and the call has its own Recents card
- `CallActivity` ends the call through a single `endCall()` path (End button, notification End, PiP End)
- `CallActivity` back handling uses `OnBackPressedCallback` — the manifest opts in to predictive back (`enableOnBackInvokedCallback`), which bypasses `Activity.onBackPressed()` on Android 13+
- `CallActivity` manifest entry declares `supportsPictureInPicture`, `resizeableActivity` and `configChanges` (so PiP resizing does not recreate the activity and its WebRTC renderers)

---

## [1.4.0] — 2026-07-29

### Added
- PQXDH hardening — corrupt-Kyber guard in `SessionBuilder.process()`; `ensureKyberKeyExists()` auto-upgrades legacy accounts to post-quantum keys on first launch
- Large-video streaming upload — files >50 MB stream-encrypt to disk instead of loading into memory; eliminates OOM on large video shares
- Forwarded message flag — `Message.forwarded` Room column (v21); `ForwardMessageHelper` sets the flag without adding a text prefix; forwarded badge rendered in `MessageAdapter`
- In-call text chat (`InCallChatActivity`) — real-time encrypted messaging during a voice/video call
- Cloudflare Worker tiered storage — R2 hot (30-day) → B2 cold (permanent); all media routes through the Worker when `WORKER_URL` is set; no SigV4 signing on device
- Shimmer skeleton loading in conversation list while Firestore data loads
- B2 media request deduplication — concurrent callers waiting on the same path share a single network fetch
- Avatar full-screen viewer (`FullScreenImageActivity`) with local disk cache to skip redundant B2 round-trips
- 500 MB multi-select media picker cap

### Fixed
- Group message delivery ACK — `acknowledgeDelivery()` now routes to `groups/{id}/messages` for `new_group_message` type instead of always writing to `chats/`; group delivered ticks now appear correctly for the sender
- WebRTC one-way audio — `onAddTrack` now explicitly enables the remote `AudioTrack` (some libwebrtc builds deliver it disabled)
- WebRTC 20-second call setup lag — TURN credentials are pre-fetched before `startCall()`; hard 3-second timeout prevents indefinite wait on slow SIM wake
- Video call blank PiP — `prepareEgl()` + `initVideoRenderers()` now run before `startCall()`/`acceptCall()`; renderer was uninitialised on add-sink
- WebRTC AEC/NS/AGC audio constraints now correctly applied to the local audio track
- ICE restart triggered automatically on `DISCONNECTED` → re-establishes calls after WiFi/LTE handover
- Remote `ENDED` call state leaks — `CallManager.release()` now called on `onSignalingChange(CLOSED)` to free native resources
- Audio focus acquired before call start; released after hang-up
- In-call chat Firestore rule — `calls/{callId}/chat` subcollection was missing a security rule (PERMISSION_DENIED); rule added gating on `callerId`/`calleeId` from the parent call doc
- B2 null-stream crash guards — `loadMedia()` null-checks `b2Path` before spawning a thread; `readBytes()` throws `IOException` on a null `InputStream`; async callbacks in viewers check `isDestroyed()`/`isFinishing()` before Glide/ExoPlayer calls
- Camera `TakePicture` contract updated to `ActivityResultContracts.TakePicture` (returns boolean)

### Changed
- Room database migrated to version 21 (`forwarded` column on `messages` table)
- `MessageBuilder` updated with 8-argument overloads for the forwarded-flag parameter

---

## [1.3.0] — 2026-07-14

### Added
- Swipe-to-archive conversations with undo Snackbar
- Animated typing indicator in conversation list rows
- Haptic feedback on message send
- Waveform scrubbing for voice messages (swipe-to-seek)
- Voice message waveform renders in lavender palette (`#9A81FF` played / `#3A3548` unplayed)
- Avatar tap opens full-screen image viewer (`FullScreenImageActivity`)
- Profile photo cached locally to avoid redundant B2 round-trips
- B2 media request deduplication — N callers waiting on the same path share a single network request
- Search executor reuse with Future cancellation to debounce rapid typing
- Bubble fade-in animation cached (one XML parse per session, not per message)
- Voice note temp-file cleanup on new playback and in `onDestroy`

### Fixed
- Black screen when viewing encrypted `b2:` avatar paths in full-screen viewer
- Waveform poll interval 200 ms → 50 ms (eliminates visible stutter)
- Missing `DiffUtil` import in `CreateGroupActivity`

### Changed
- UI accent colour promoted to vivid lavender `#9A81FF`; backgrounds shifted to blue-tinted darks (`#191620`, `#24202E`)
- Signed release build workflow consolidated and simplified

---

## [1.2.0] — 2026-07-12

### Added
- Per-conversation disappearing messages (timer button in chat header)
- Message action sheet (reactions, forward, edit, star, pin, delete locally, delete for everyone)
- Safety number verification banner on identity key change
- Delivery and read receipts via FCM data messages
- Conversation archiving (hidden from list, retrievable)
- Self-destruct sweep via scheduled Cloud Function (purges Firestore + B2 media)
- Settings split into four sub-screens (Account, Privacy, Notifications, Appearance)
- Secondary unlock code two-state UI with guard preventing accidental clear before removal

### Fixed
- Cold-start auto sign-out bypass via force-quit (removed `coldStart` guard from `shouldAutoSignOut`)
- BaseActivity sign-out path no longer calls `onAppForegrounded()` on Firebase-null state
- Send button double-tap guard (`setEnabled(false)` at send start, re-enabled in all three outcome paths)
- Background-thread `Toast` calls in helpers replaced with `Handler(Looper.getMainLooper()).post()`
- Contact backup not called on restore — fixed by wiring `restoreIfNeeded()` in `ConversationListActivity.onCreate()`

### Changed
- `SupabaseStorageHelper` fully retired; all media routed through `B2StorageHelper`
- `VoiceNoteHelper.uploadVoiceNote()` now throws `UnsupportedOperationException` to surface accidental misuse

---

## [1.1.0] — 2026-07-10

### Added
- Group chat — AES-256-GCM shared group key distributed via Signal encryption
- `CreateGroupActivity`, `GroupChatActivity` with DiffUtil-backed adapters
- Room DB v12 — `contacts`, `groups`, `group_members` tables
- `AddContactActivity` with QR gallery scan, deep-link (`duoshield://add/`), clipboard paste, and share sheet
- Backblaze B2 storage — SigV4 signed PUT / DELETE / GET with AES-256-GCM encryption
- PBKDF2-protected encrypted account backup with seed-phrase restore
- Scheduled pre-key rotation via `SignalPreKeyRefresher` (threshold 10, batch 25)
- Daily signed pre-key rotation via WorkManager with grace-period fallback

### Fixed
- Signal pre-key IDs tracked via `signal_prekey_next_id` in `SecurePrefs` to prevent reuse
- `registerIdentity()` called inside `onSuccess` lambda to guarantee UID is available
- `SplashActivity` uses `addAuthStateListener` instead of `getCurrentUser()` to fix cold-start false logout

### Changed
- All encryption migrated from `CryptoHelper`/`ECDH` to `SignalCipherHelper` (libsignal-android 0.54.1)
- `sigType` column added to `messages` table (0 = legacy, 1 = WHISPER, 3 = PREKEY)
- `MODIFIED` Firestore event handler decrypts edited partner messages and persists to Room

---

## [1.0.0] — 2026-07-08

### Added
- Initial release — one-to-one Signal Protocol encrypted messaging
- Custom-token Firebase Auth with seed-phrase-derived UID (HKDF-SHA256)
- SQLCipher-encrypted Room database
- FCM push notifications via Node.js relay server
- OneSignal 5.x coexistence with `FCMBroadcastReceiver`
- App lock with biometric / PIN and configurable auto-lock timeout
- `FirebaseCostGuard` singleton — rate-limits and deduplicates all Firestore listeners
- Firestore security rules with emulator-based test suite
- Signed release APK CI pipeline with ABI splits (arm64-v8a / armeabi-v7a)
