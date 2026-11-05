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
| `ADMIN_SESSION_SECRET` | Optional independent key material for signed admin-session cookies; final key is also bound to `ADMIN_TOKEN` | Derived from `ADMIN_TOKEN` | Explicit value shorter than 32 UTF-8 bytes refuses startup; generate with `openssl rand -hex 32` |
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

### Durable admin sessions (S05-M3)

Admin cookie sessions are backed by Firestore, not a process-local cache.
Each random 256-bit session identifier is HMAC-derived into a Firestore
document ID; the raw identifier and cookie are never stored. The session
record enforces a 30-minute sliding idle expiry and an 8-hour absolute expiry.
Every cookie-authenticated admin request validates the record and the
fleet-wide generation in a Firestore transaction, then refreshes the idle
expiry. A Firestore outage therefore returns **503** for cookie authentication
rather than accepting a signature alone or counting the outage as a bad
password. Direct `x-admin-token` authentication remains independent of the
session ledger.

`POST /admin/logout` deletes one durable session record. “Sign out everywhere”
atomically increments `adminSessionControl/global.generation`; sessions issued
under an earlier generation are rejected by every Render instance without a
collection scan. Session records live in `adminSessions`; both
`adminSessions` and `adminSessionControl` are denied to client SDKs by
`firestore.rules`. The `adminSessions.purgeAt` Firestore TTL policy only
removes expired records physically — authorization always checks the exact
idle and absolute timestamps in the request transaction and never relies on
TTL deletion timing.

Deploy the matching rules and TTL configuration with:

```sh
firebase deploy --only firestore:rules,firestore:indexes
```

The release invalidates pre-migration process-local admin cookies; the operator
must sign in again after rollout. Keep `ADMIN_SESSION_SECRET` identical across
all server instances. It is optional; if set, use at least 32 random bytes.
The final signing key also depends on `ADMIN_TOKEN`, so rotating the admin
token invalidates old signed cookies even when this variable is set. Keep IP
and User-Agent binding disabled by default: mobile network changes and Android
WebView request-header differences caused false session rejection. A stolen
live cookie remains a bearer credential; revoke it with “Sign out everywhere”
and consider a separate WebAuthn/step-up-authentication design for destructive
admin actions.
### Feature-scoped (absence disables one feature only)

| Variable | Purpose | Missing |
|---|---|---|
| `TURN_TOKEN_ID`, `TURN_API_TOKEN` | Cloudflare TURN credentials for calls | `/turnCredentials` returns `503`; client-side behaviour after that is not documented here (not verified) |
| `PUBLIC_BASE_URL` | Absolute origin of this server, used to build proxied preview-image URLs | Derived from `X-Forwarded-Proto`/`Host`; set it explicitly behind a proxy that rewrites `Host` |
| `ADMIN_LOCKOUT_WORKER_URL`, `ADMIN_LOCKOUT_WORKER_SECRET` | Cloudflare Worker + Durable Object endpoint backing the admin brute-force lockout and authenticated endpoint rate-limit buckets (`lib/adminLockoutStore.js`, `lib/authRateLimitStore.js`) | Falls back to bounded process-local counters — these do NOT survive a Render restart/redeploy or span multiple instances. Both must be set together; the server still boots and serves traffic either way. |

#### Cloudflare Durable Object setup (admin lockout + authenticated API rate-limit durability)

As of 2026-08-14 this is backed by a Cloudflare Durable Object
(`worker/src/adminLockoutDurableObject.js`), fronted by the `/adminLockout`
route on the project's existing Cloudflare Worker (`worker/src/index.js`).
This is **not** Cloudflare KV — an earlier same-day attempt to back this
counter with plain KV (`GET`→increment→`PUT`) was found to have a genuine
concurrency bug (concurrent failed admin-login attempts could undercount
the lockout, widening an attacker's guess budget) and was replaced before
ever shipping. Durable Objects guarantee that requests to the same opaque
bucket instance are processed one at a time, so the increment is atomic.
The Render server uses it for both admin IP lockout keys and SHA-256-derived
per-user/per-endpoint authenticated API rate-limit keys. Each counter's
fixed-window record expires through a Durable Object alarm, which deletes
all per-instance storage after expiry; see `adminLockoutDurableObject.js`. This also replaces the original Upstash Redis-backed store
(`KV_REST_API_URL`/`KV_REST_API_TOKEN` — **removed**, no longer read by
`index.js`; safe to delete from Render and from wherever the Upstash
integration provisioned them once this migration is deployed and verified).

1. **Deploy the Worker with the Durable Object** — from `/worker`:
   `npx wrangler deploy`. `wrangler.jsonc` already declares the
   `ADMIN_LOCKOUT_DO` binding and the `new_sqlite_classes` migration for
   `AdminLockoutCounter` — no manual dashboard step is needed to create the
   Durable Object itself; deploying the Worker provisions it. The SQLite
   storage backend Durable Objects use here is available on Cloudflare's
   **free** Workers plan — no paid plan upgrade is required for this.
2. **Generate and set the shared secret** — `openssl rand -base64 48`, then:
   - Worker side: `npx wrangler secret put ADMIN_LOCKOUT_SECRET` (from `/worker`)
   - Render side: set `ADMIN_LOCKOUT_WORKER_SECRET` to the same value
   These must be byte-identical; this is a server-to-server credential, not
   an operator-facing one, and is deliberately a different secret from
   `STATS_SECRET` (see `isAdminLockoutAuthorized` in `worker/src/index.js`).
3. **Set `ADMIN_LOCKOUT_WORKER_URL` on Render** — the deployed Worker's base
   URL (e.g. `https://duoshield-storage.<subdomain>.workers.dev`, or a
   custom domain if one is configured), with **no** path suffix — the
   client appends `/adminLockout` itself.
4. **Verify, then remove the old Upstash variables** — once a deploy with
   both Render variables above is confirmed working (check for `admin
   lockout: Durable-Object-backed (durable across restarts/instances, atomic
   under concurrency)` in the Render logs at startup), delete
   `KV_REST_API_URL`/`KV_REST_API_TOKEN` from Render and disconnect/delete
   the Upstash integration if it is not used for anything else in this
   project.

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

## Service account
Download from Firebase Console → Project Settings → Service Accounts → Generate
new private key. Paste the entire JSON content as the value of
`GOOGLE_APPLICATION_CREDENTIALS_JSON` on Render. Never commit this to the repo.

The service account needs:
- Firebase Cloud Messaging permission to send FCM messages.
- Firestore read/write permission to watch messages and mark delivery status.
