# DuoShield Push Server

Node.js server deployed on Render.com. It watches Firestore with `onSnapshot()`
and sends FCM pushes through the Firebase Admin SDK. Keep the Render service
awake with UptimeRobot by polling `/status`.

## Deploy on Render
1. New Web Service → connect repo → set Root Directory to `server`
2. Build command: `npm install`
3. Start command: `npm start`
4. Add env var: `GOOGLE_APPLICATION_CREDENTIALS_JSON` = `<paste full service account JSON as one line>`
5. In UptimeRobot, monitor `https://<your-render-service>.onrender.com/status`

## Secrets and environment variables (S05-I1)

Every value below is read directly by `index.js`; the list is generated from
`grep -oE "process\.env\.[A-Z_0-9]+" index.js`, so if you add a new one, add it
here too. The **Missing** column is the actual runtime behaviour, not an
intention — the point of this table is that the server's failure modes are
*asymmetric*, and an operator who does not know which way each one fails cannot
tell a safe deployment from a silently degraded one.

Generate every secret with `openssl rand -hex 32`. Never reuse one value for two
variables: each grants a different capability, and sharing a key means a leak of
either widens the blast radius of both.

### Required

| Variable | Purpose | Missing |
|---|---|---|
| `GOOGLE_APPLICATION_CREDENTIALS_JSON` | Firebase Admin SDK service-account JSON, one line | Server cannot start |

### Security-critical (fail-closed by design)

| Variable | Purpose | Missing | Weak |
|---|---|---|---|
| `ADMIN_TOKEN` | Bearer token for the `/admin` panel and `/admin/api/*` | Panel returns `503`; server still runs and pushes notifications | **Server refuses to boot** (`process.exit(1)`) |
| `MEDIA_TOKEN_SECRET` | Signs B2 media capability tokens. Must match the Worker's copy | `/mediaToken` refuses to mint; media upload/download fails | **Not validated** — see below |
| `LINK_PREVIEW_PROXY_SECRET` | Signs `/linkPreviewImage` proxy URLs | Previews render **without images** — deliberately, so a recipient's IP is never leaked to the linked host (S04-H3) | Treated as unset, with an error logged |

`MEDIA_TOKEN_SECRET` is the one secret in this table with **no entropy gate**: as
of 2026-08-10 `index.js` only warns when it is *absent* and never inspects its
strength, so a weak value is accepted silently and yields forgeable media tokens.
That is a known gap, not a documented safeguard — do not read this table as
promising validation that the code does not perform. Set it from
`openssl rand -hex 32` and keep the server's and Worker's copies identical.

`ADMIN_TOKEN` is validated for entropy **at startup**, not per request: startup
is the only moment a weak token is still a fixable deployment error rather than
an invisible standing exposure. The floor is **128 bits** (`MIN_SECRET_BYTES = 16`
in `lib/adminSecret.js`), measured over the character classes actually present,
plus a Shannon check that rejects long-but-repetitive values — `ADMIN_TOKEN=admin`
and a 64-character run of `a` are both refused. An *unset* token does not abort,
because a deployment that never enables the admin panel is legitimate and already
safe at `503`; a *weak* one aborts, because it is open.

Note the deliberate asymmetry between `ADMIN_TOKEN` (weak ⇒ refuse to boot) and
`LINK_PREVIEW_PROXY_SECRET` (weak ⇒ disable the feature). A forgeable admin token
must never be deployed; link previews are cosmetic, and killing the whole
messaging server over one would trade a small exposure for a total outage.

### Feature-scoped (absence disables one feature only)

| Variable | Purpose | Missing |
|---|---|---|
| `TURN_TOKEN_ID`, `TURN_API_TOKEN` | Cloudflare TURN credentials for calls | `/turnCredentials` returns `503`; client-side behaviour after that is not documented here (not verified) |
| `YOUTUBE_API_KEY` | YouTube Data API v3 (Watch Together search) | `POST /youtubeSearch` returns `503` |
| `PUBLIC_BASE_URL` | Absolute origin of this server, used to build proxied preview-image URLs | Derived from `X-Forwarded-Proto`/`Host`; set it explicitly behind a proxy that rewrites `Host` |

### Rotation

Rotating any secret here is an **operator** action — see
`security-remediation/migration/MIGRATION_PLAN.md`. Rotate `ADMIN_TOKEN` on any
suspicion of exposure: it is a single static bearer token with no expiry, so the
real security property is "whoever holds it". Check `adminAuditLog` in Firestore
for `admin_login_succeeded` / `admin_login_failed` rows first — that collection
is server-write-only (`allow read, write: if false` in `firestore.rules`) and is
the only durable record of admin access (S05-H3).

## Optional environment variables
- `MAX_INITIAL_MESSAGE_AGE_MS` — startup grace window for recently-created
  messages. Default: `300000` (5 minutes). Older documents in Firestore's
  initial listener snapshot are skipped so Render restarts do not resend old
  notifications.
- `YOUTUBE_API_KEY` — YouTube Data API v3 key, used by `POST /youtubeSearch`
  for Watch Together search. When unset, that endpoint returns `503` and search
  is unavailable; nothing else is affected. **Set the value only here, never in
  the Android app** — see below.
- `YOUTUBE_REGION_CODE` — optional ISO 3166-1 alpha-2 code (e.g. `US`) to bias
  search results toward a region. Costs no extra quota.

## Watch Together YouTube search (`POST /youtubeSearch`)

Authenticated with a Firebase ID token, like every other client endpoint:

```
POST /youtubeSearch
Authorization: Bearer <Firebase ID token>
{ "q": "lofi beats", "maxResults": 10 }

200 → { "results": [ { "videoId", "title", "channel", "thumbnail" } ], "cached": false }
```

The key never leaves the server. The Android app sends a query string and gets
back video IDs, which it hands to the existing Watch Together player — so the
APK contains no YouTube credential and cannot be decompiled to recover one.

**Quota is the binding constraint.** A `search.list` call costs 100 units
against a 10,000/day free allowance — roughly **100 searches per day for the
entire deployment**. The endpoint therefore enforces, in this order:
auth → query validation (min 2 / max 100 chars) → 10-minute response cache →
per-user rate limit (6/min) → one YouTube call, never paginated. Cache hits are
served before the rate limiter, so repeating a search costs neither quota nor
budget.

To create the key: Google Cloud console → enable **YouTube Data API v3** →
Credentials → create an API key → restrict it to that single API (and
optionally to the Render egress IP). Add it to Render as `YOUTUBE_API_KEY`.

## Service account
Download from Firebase Console → Project Settings → Service Accounts → Generate
new private key. Paste the entire JSON content as the value of
`GOOGLE_APPLICATION_CREDENTIALS_JSON` on Render. Never commit this to the repo.

The service account needs:
- Firebase Cloud Messaging permission to send FCM messages.
- Firestore read/write permission to watch messages and mark delivery status.
