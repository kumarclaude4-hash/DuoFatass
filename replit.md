# DuoShield

Military-grade end-to-end encrypted messaging for Android. Signal Protocol (Double Ratchet + PQXDH + Kyber-1024) with zero-knowledge local database (SQLCipher) and Cloudflare tiered media storage.

## Stack

| Layer | Technology |
|---|---|
| Android app | Java, minSdk 26, targetSdk 34, Room v21, SQLCipher 4.5 |
| Encryption | libsignal-android 0.54.1 (PQXDH + Kyber-1024) |
| Backend | Firebase Firestore + Auth + FCM |
| Push relay | Node.js on Render (`server/`) |
| Media storage | Cloudflare Worker (R2 hot → B2 cold) (`worker/`) |
| Build CI | GitHub Actions (`.github/workflows/`) |

## Project layout

```
app/              Android application source
  src/main/java/com/duoshield/app/
    auth/           Custom-token Firebase auth
    backup/         E2EE cloud backup (AES-256-GCM + GZIP)
    call/           WebRTC voice/video calling
    contacts/       Contact management (QR, deep-link, clipboard)
    crypto/         SeedPhraseHelper, SignalCipherHelper, GroupCipherHelper
    db/             Room database, DAOs, migrations
    models/         Data models (Contact, Conversation, Group, Message)
    notifications/  FCM service, NotificationStyler
    security/       PinManager, AppLockManager
    ui/             Custom views (WaveformView, TypingDotsView, etc.)
    util/           B2StorageHelper, FirebaseCostGuard, WipeHelper, etc.
server/           Node.js push relay (deployed on Render)
worker/           Cloudflare Worker — tiered media storage (R2 → B2)
scripts/          strip_signal_records.py, setup-android-sdk.sh
docs/             Architecture and security docs
firestore.rules   Firestore security rules
```

## Building the APK

APKs are built in GitHub Actions — not on Replit. The release workflow requires these GitHub Secrets:

| Secret | Purpose |
|---|---|
| `GOOGLE_SERVICES_JSON` | Firebase config (full `google-services.json` contents) |
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (`duoshield`) |
| `KEY_PASSWORD` | Key password |
| `WORKER_URL` | Cloudflare Worker URL |
| `WORKER_SECRET` | Shared auth token for Worker requests |
| `PUSH_SERVER_URL` | Push relay URL (default: `https://duofat.onrender.com`) |

The lint workflow uses `app/google-services.json.template` as a stub when `GOOGLE_SERVICES_JSON` is not set.

## Running the push server locally

```bash
cd server
npm install
node index.js   # runs on port 3000
```

Required environment variables for the server (set on Render for production):
- `FIREBASE_SERVICE_ACCOUNT` — service account JSON (stringified)
- `SESSION_SECRET` — express-session secret
- `WORKER_SECRET` — shared token (must match Android BuildConfig)
- `TURN_TOKEN_ID`, `TURN_API_TOKEN` — Cloudflare TURN API credentials

## Operator admin panel

`https://duofat.onrender.com/admin` — waitlist approval, account unfreeze, and duress-PIN
enrollment, gated by the `ADMIN_TOKEN` env var (set directly on Render, not in the APK).
The unlock form is a native POST to `/admin/login`; on success the server sets a
30-minute, sliding, HttpOnly admin-session cookie. The token is never placed in
page JavaScript, local storage, or a URL. Panels:
- **Pending waitlist requests** — approve a request id so that user can create an account
- **Locked accounts** — unfreeze an account by deleting its `accountLock/{uid}` doc
- **Duress PIN enrollment** — search an account by UID (`GET /admin/api/account/lookup`
  verifies it exists in `identities/{uid}` before showing an Enable/Disable action) to
  grant or revoke `duressEligibility/{uid}`. Enrollment is refused for a UID with no
  matching account — no phantom eligibility docs.

  App-side pickup: `DuressManager.refreshEligibility()` runs on sign-in **and** on
  resume of `SecurityPrivacySettingsActivity` / `ManageUnlockCodesActivity`, so an
  enrollment granted while the account is already signed in appears the next time the
  user opens Security & Privacy — no sign-out required. Revocation travels the same
  path. Once the user actually sets a second code, the "Manage unlock codes" row is
  hidden permanently and nothing in the running app references the feature again; it
  only exists as a hash in `SecurePrefs` and one branch in `LockScreenActivity`.

All actions were previously Firebase-console-only by design; this panel doesn't change
the access model (Firestore rules still deny clients read/write on the underlying
collections) — it just gives the operator a UI instead of hand-editing documents.

## Cloudflare Worker

```bash
cd worker
npm install
npx wrangler deploy   # deploys to kumarclaude4.workers.dev
```

Set `WORKER_SECRET` via `wrangler secret put WORKER_SECRET` before deploying.

## Key architecture rules (do not violate)

- **FirebaseCostGuard singleton** — all Firestore reads/writes must go through it; prevents quota exhaustion
- **One listener per screen** — detach in `onDestroy()`; never create multiple listeners for the same path
- **BaseActivity** — all sensitive activities must extend `BaseActivity` for app-lock enforcement; only pre-auth onboarding screens extend `AppCompatActivity`
- **libsignal dependency topology is fixed** — `libsignal-android:0.54.1` is `implementation` with its transitive `libsignal-client` excluded; Maven `libsignal-client:0.54.1` is `compileOnly`; `libs/libsignal-client-0.54.1-stripped.jar` is `implementation`. Preserve all three lines and the stripped-JAR workflow exactly as documented in `app/build.gradle`; do not simplify or bump one dependency independently. The stripped JAR removes six `java.lang.Record` subclasses to avoid the D8 desugaring failure.
- **HKDF off-limits** — use `SeedPhraseHelper.hkdfSha256()` (public, 3 args); never import `org.signal.libsignal.protocol.kdf.HKDF`
- **No Cloud Functions** — all server logic lives in `server/index.js` on Render
- **Room DB current version: 22** — always add a migration when bumping; SQLCipher passphrase is a random key managed (not seed-derived) in `DatabaseKeyProvider`
- **lastMessage is plaintext** — `ConversationMetaUpdater` writes a ≤80-char preview; never encrypt it again
- **Message status downgrade guard** — any `status` write must be transacted to prevent `read → delivered` regression

## User preferences

- Push every change to `origin/main` via `GIT_PAT` secret after completing work
- Never attempt to fix or run the push server on Replit — it lives on Render
- Ask before adding new secrets or environment variables; user controls what's in the Secrets tab
