# Architecture

## Overview

DuoShield is a client-heavy Android application. The server is a thin, stateless FCM relay — all cryptographic operations happen on the device.

```
┌─────────────────────────────────────────────────────┐
│                   Android Client                    │
│                                                     │
│  ┌───────────┐  ┌──────────────┐  ┌─────────────┐  │
│  │  Signal   │  │  Room DB     │  │  Firestore  │  │
│  │  Protocol │  │  (SQLCipher) │  │  listener   │  │
│  └─────┬─────┘  └──────┬───────┘  └──────┬──────┘  │
│        │               │                 │          │
│        └───────────────┴─────────────────┘          │
│                    UI Layer                         │
└─────────────────────────────────────────────────────┘
         │                       │
         ▼                       ▼
  ┌─────────────┐        ┌──────────────┐
  │  Firebase   │        │  Backblaze   │
  │  Firestore  │        │  B2 Storage  │
  │  (metadata, │        │  (encrypted  │
  │   ciphertext│        │   media)     │
  │   only)     │        └──────────────┘
  └──────┬──────┘
         │ FCM
         ▼
  ┌─────────────┐
  │  Push relay │  (Node.js, server/)
  │  (no content│
  │   access)   │
  └─────────────┘
```

---

## Cryptography

### 1-to-1 messages — Signal Protocol

`SignalCipherHelper` wraps `libsignal-android 0.54.1`:

- **Session establishment** — X3DH using the contact's pre-key bundle fetched from Firestore
- **Ongoing messages** — Double Ratchet (WHISPER messages, `sigType = 1`)
- **First message** — PRE_KEY bundle message (`sigType = 3`); recipient processes on first receipt to establish ratchet
- **Legacy** — `sigType = 0` rows display `[Legacy message — not decryptable]`

Pre-key management:
- `SignalPreKeyRefresher` monitors remaining pre-keys (threshold 10); uploads a batch of 25 via `arrayUnion` when below threshold
- Signed pre-keys are rotated daily via WorkManager; the previous signed pre-key is kept in `signal_signed_prekey_prev` for a grace period

### Group messages

`GroupCipherHelper` implements AES-256-GCM with a shared group key:

1. Group creator generates a random 256-bit AES key
2. Key is encrypted individually for each member using their Signal session and stored in `group_keys/{groupId}/{memberUid}`
3. Members decrypt the group key on first receipt and cache it in `SecurePrefs`
4. All group messages are AES-256-GCM encrypted with this key

### Media

`B2StorageHelper` handles all media:

- **Upload** — AES-256-GCM encrypt on-device → SigV4-signed PUT to Backblaze B2
- **Download** — GET from B2 (public read) → AES-256-GCM decrypt on-device
- **In-flight deduplication** — `ConcurrentHashMap<String, List<MediaCallback>>` ensures N callers for the same path share exactly one network request

### Database

- SQLCipher database key: a random 32-byte key generated on first launch and stored in
  `EncryptedSharedPreferences` via `DatabaseKeyProvider` — NOT derived from the seed. It
  protects the on-device file at rest only; a fresh empty database always gets a fresh
  key, so nothing about restoring an identity depends on it being deterministic.
- Stored in `EncryptedSharedPreferences`; `SecurePrefs.get()` is a cached singleton
- Room schema version 12; all migrations are explicit (no destructive fallback)

### Authentication

- Firebase Auth using a **custom token** minted by the push server on first sign-in
- UID is derived deterministically: `HKDF-SHA256(seed) → BigInteger → base32(custom alphabet)` → `XXXXX-XXXXX-XXX`
- On restore: re-derive the same UID from seed phrase, mint a new custom token, migrate Firestore data

---

## Module layout

```
app/src/main/java/com/duoshield/app/
│
├── crypto/
│   ├── signal/
│   │   ├── SignalCipherHelper.java     # encrypt / decrypt 1-to-1 messages
│   │   ├── SignalKeyManager.java       # key generation, Firestore persistence
│   │   └── SignalPreKeyRefresher.java  # auto-replenish pre-key pool
│   ├── GroupCipherHelper.java          # AES-256-GCM group key
│   ├── BackupCryptoHelper.java         # PBKDF2 + AES-256-GCM backup
│   ├── DatabaseKeyProvider.java        # SQLCipher key derivation
│   └── SeedPhraseHelper.java          # BIP-39 + HKDF-SHA256 derivation
│
├── db/
│   ├── AppDatabase.java                # Room v12, migrations 1–12
│   └── dao/                            # ContactDao, MessageDao, GroupDao, …
│
├── models/
│   ├── Contact.java
│   ├── Conversation.java
│   ├── Group.java
│   ├── GroupMember.java
│   └── Message.java
│
├── call/                               # voice/video calling — see docs/CALL_MINIMIZE_PIP.md
│   ├── CallActivity.java               # call screen (UI only); attaches to / adopts the CallSession
│   ├── CallSession.java                # process-wide owner of the live call (outlives any screen)
│   ├── CallSessionPolicy.java          # pure decisions: detach / resume / lock-on-return
│   ├── CallBackPolicy.java             # pure decisions: what back does, when PiP auto-enters
│   ├── CallPip.java                    # shared PiP params, Mute/End actions, crop math
│   ├── ReturnToCallBar.java            # "Tap to return to call" strip (shown by BaseActivity)
│   ├── CallForegroundService.java      # ongoing-call notification, keeps process alive
│   ├── CallManager.java                # WebRTC peer connection + signalling
│   └── InCallChatActivity.java         # in-call text chat (also supports PiP)
│
├── ui/
│   ├── AddContactActivity.java
│   ├── CreateGroupActivity.java
│   └── SettingsActivity.java           # four sub-screens
│
├── util/
│   ├── FirebaseCostGuard.java          # singleton Firestore read/write guard
│   ├── B2StorageHelper.java            # Backblaze B2 client
│   ├── AppLockManager.java             # ref-count lifecycle, bgTs tracking
│   ├── ConversationMetaUpdater.java    # writes plaintext preview (≤80 chars)
│   ├── MessageBuilder.java             # always includes "id" field in Firestore doc
│   ├── NotificationStyler.java         # FCM notification with conversation_id extras
│   ├── SearchHelper.java               # shared executor, Future debounce
│   └── VoiceNoteHelper.java            # throws UnsupportedOperationException (dead code guard)
│
├── BaseActivity.java                   # lock check, auto sign-out, ref-count lifecycle
├── ChatMediaActivity.java              # 1-to-1 chat, voice, media
├── GroupChatActivity.java
├── ConversationListActivity.java
├── MainActivity.java                   # gates Firestore writes on !AppLockManager.shouldLock()
└── SplashActivity.java                 # addAuthStateListener (not getCurrentUser)
```

---

## Firestore data model

```
users/{uid}
  displayName, avatarPath, preKeys[], signedPreKey, identityKey, oneSignalId

chats/{chatId}                          # chatId = sorted([uid1, uid2]).join("_")
  participants[], lastMessage, lastTimestamp, disappearMs

chats/{chatId}/messages/{msgId}
  sender, recipient, ciphertext, sigType, timestamp,
  status (sent|delivered|read), type (text|image|voice|video),
  path (b2 key), mediaType, deletedForAll, reactions{}

groups/{groupId}
  name, members[], createdBy, disappearMs

groups/{groupId}/messages/{msgId}
  sender, ciphertext, timestamp, type, path, …

group_keys/{groupId}/{memberUid}
  encryptedKey (Signal-encrypted AES group key)
```

---

## Invariants every contributor must respect

1. **`FirebaseCostGuard`** — every `addSnapshotListener` / `get()` / `set()` / `update()` must go through this singleton. Never call Firestore directly.
2. **One listener per screen** — attach in `onCreate` / `setupChat`; detach in `onDestroy`. Never attach inside an `onResume` loop.
3. **DiffUtil** — `RecyclerView` adapters must use `DiffUtil.calculateDiff` in `setItems()`; no `notifyDataSetChanged()`.
4. **Background threads** — `Toast.makeText().show()` is forbidden off the main thread. Use `new Handler(Looper.getMainLooper()).post(...)` in helpers.
5. **`BaseActivity`** — all authenticated screens must extend `BaseActivity`. Pre-auth onboarding screens (SignIn, DisplayName, RestoreFromSeed, SeedPhraseDisplay) must extend `AppCompatActivity`.
6. **Executor lifecycle** — every `ExecutorService` field requires `shutdownNow()` in `onDestroy()`.
7. **Message `id` field** — `MessageBuilder` must always include `"id"` in the Firestore document. `listenForMessages()` silently skips docs where `id == null`.
8. **Status downgrade guard** — any write of message `status` must transaction-guard against downgrading `"read"` back to `"delivered"`.
