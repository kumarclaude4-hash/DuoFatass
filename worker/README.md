# DuoShield Storage Worker

Cloudflare Worker providing tiered media storage for DuoShield.

```
Android app
    │  HTTPS  (PUT / GET / DELETE)
    │  Authorization: Bearer <per-object capability token>   (SEC-A01)
    ▼
Cloudflare Worker  ← this directory
    │
    ├── R2 bucket "duoshield-hot"   (days 0–30, fast, free CF egress)
    │
    └── B2 bucket "duoshield-cold"  (permanent — no auto-expiry)
         └── Deleted only when the app explicitly sends DELETE
```

All blobs are **AES-256-GCM encrypted on the Android device** before upload.
The Worker only ever sees ciphertext — it never touches plaintext.

---

## Setup (one-time)

### 1. Prerequisites

```bash
node -v          # 18+
npm install -g wrangler
wrangler login
```

### 2. Create Backblaze B2 bucket

1. Log in to [Backblaze B2](https://secure.backblaze.com/b2_buckets.htm)
2. Create bucket **`duoshield-cold`** — set to **Private**
3. Create an application key with Read + Write for that bucket
4. Note your endpoint (e.g. `https://s3.eu-central-003.backblazeb2.com`) and region

### 3. Create the R2 bucket

```bash
npx wrangler r2 bucket create duoshield-hot
```

### 4. Create the KV namespace (rate limiting + storage counters)

```bash
npx wrangler kv namespace create RATE_KV
# Copy the "id" value and paste it into wrangler.jsonc → kv_namespaces[0].id
```

### 5. Edit wrangler.jsonc

Update these values:
- `kv_namespaces[0].id` → your KV namespace ID from step 4
- `vars.B2_ENDPOINT` → your B2 endpoint
- `vars.B2_BUCKET`   → your B2 bucket name (e.g. `duoshield-cold`)
- `vars.B2_REGION`   → your B2 region (e.g. `eu-central-003`)

### 6. Set secrets

```bash
npx wrangler secret put B2_ACCESS_KEY_ID      # paste your B2 keyID
npx wrangler secret put B2_SECRET_ACCESS_KEY  # paste your B2 applicationKey
npx wrangler secret put STATS_SECRET          # operator-only /stats token (S08-H1)
npx wrangler secret put MEDIA_TOKEN_SECRET    # per-object capability signing key (must match the push server)
npx wrangler secret put ADMIN_LOCKOUT_SECRET  # must match Render's ADMIN_LOCKOUT_WORKER_SECRET
```

**`STATS_SECRET`** is a random string you generate (e.g. `openssl rand -hex 32`)
that gates the operator-only `/stats` view. It is **never shipped to a client**
— it replaces the old `WORKER_SECRET`, which was compiled into every APK and is
therefore treated as a public, leaked value (S08-H1). Requests to `/stats` must
include `Authorization: Bearer <STATS_SECRET>`; without it `/stats` returns 401.

**`MEDIA_TOKEN_SECRET`** signs the per-object capability tokens the push server
mints for each media PUT/GET/DELETE (SEC-A01). It must be byte-identical to the
push server's `MEDIA_TOKEN_SECRET`. The data plane no longer uses any shared
bearer secret, so the client never holds a long-lived storage credential.

### 7. Validate and deploy

```bash
cd worker
npm ci
# Compile the exact Worker bundle and validate wrangler.jsonc without deploying.
npx wrangler deploy --dry-run --outdir /tmp/duoshield-worker-dry-run
# Apply the configured SQLite Durable Object migrations and deploy.
npx wrangler deploy
```

The Wrangler config declares two SQLite-backed Durable Object classes:
`AdminLockoutCounter` and `MediaMutationBudget`. Their versioned migrations
are applied by the first successful deploy. Workers Free supports SQLite-backed
Durable Objects; do not switch these bindings to the legacy KV storage backend.

Your Worker URL will be:
```
https://duoshield-storage.<your-subdomain>.workers.dev
```

### 8. Wire the Worker URL into the Android app

Add to `local.properties`:
```
worker.url=https://duoshield-storage.<your-subdomain>.workers.dev
```

Then rebuild the APK. `B2StorageHelper` automatically routes all storage calls
through the Worker when `WORKER_URL` is non-empty.

> **S08-H1 / SC-02:** do **not** add any `worker.secret` (or other credential)
> to `local.properties`. `local.properties` feeds `BuildConfig`, which is
> packaged into the public APK. The client authenticates each storage operation
> with a short-lived, per-object capability token fetched at runtime from the
> push server (`POST /mediaToken`), so it needs no baked-in secret. `worker.url`
> is a non-secret endpoint and is the only value that belongs here.

---

## Testing

# Data-plane ops (PUT/GET/DELETE) require a per-object capability token minted
# by the push server (POST /mediaToken) — bound to one key, one verb, one user,
# short expiry (SEC-A01). There is no shared data-plane secret to paste here;
# obtain a token for the exact key/verb under test and pass it as the Bearer.

```bash
# Check storage stats (operator-only) — gated by STATS_SECRET, never a client secret.
STATS_TOKEN="your-stats-secret"
curl "https://duoshield-storage.<sub>.workers.dev/stats" \
  -H "Authorization: Bearer $STATS_TOKEN"

# Trigger the cron manually (local dev only)
npx wrangler dev --test-scheduled
curl "http://localhost:8787/__scheduled?cron=0+2+*+*+*"
```

---

## Free-tier limits

| Resource              | Free limit        | Guard                              |
|-----------------------|-------------------|------------------------------------|
| R2 storage            | 10 GB-month        | Hard cap at 9.0 decimal GB (90%)   |
| R2 Class A ops         | 1 M/month         | Atomic monthly cap at 850K, including uploads and list pages |
| R2 Class B ops         | 10 M/month        | 80K Worker requests/day; up to 2 Class B calls/request (~4.8M/30 days) |
| B2 storage            | 10 GB             | Hard migration stop at 9 GB; bounded preflight + projected writes |
| B2 → CF egress        | Free (Bandwidth Alliance) | —                        |
| Worker requests       | 100 K/day         | Conservative daily gate at 80 K/day (80%) |
| KV reads              | 100 K/day         | Daily gate reads + bounded mutation lookups; keep under 100K/day |
| KV writes              | 1 K/day           | ~160 sampled writes/day + bounded storage accounting |
| SQLite Durable Objects | 100 K requests/day; 13,000 GB-s/day; 5 GB storage | Two SQLite-backed counters; media uploads/deletes capped at 120/150 per day |

**Target profile: 10 active users with bounded media mutations and usage; not unlimited traffic.**

> **Note on free-tier request accounting:** Workers KV allows 1,000 writes/day.
> Request counting samples 1 in 500 requests (~160 writes/day at the 80K request
> ceiling). Daily media mutation caps leave substantial KV-write headroom even
> when the scheduled tiering job runs. PUT and DELETE attempts have independent global caps of 120 uploads/day and
> 150 deletes/day in the SQLite-backed `MediaMutationBudget` Durable Object.
> Each account is additionally limited to 20 uploads and 60 deletes per UTC day,
> so one account cannot monopolise the shared pool. At those global operation
> caps, storage accounting and DELETE replay markers can consume up to roughly
> 690 KV writes/day; request sampling adds about 160 writes/day on average at
> the 80K request target. Sampling is probabilistic, not a strict write ceiling. If the quota DO is unavailable or
> unconfigured, media mutations fail closed. A separate atomic monthly counter
> reserves R2 Class A operations before each upload and each R2 inventory-list page,
> with an 850K ceiling below the 1M free allowance. The per-holder quota map is
> bounded to 500 operation/holder entries per UTC day. Both quota DOs use SQLite-backed
> Durable Objects available on the Workers Free plan; no paid upgrade is required.
> These are application safeguards, not protection against direct bucket writes or
> usage outside this Worker.

---

## Data lifecycle

| Age      | Location | Accessible       | How it leaves                         |
|----------|----------|------------------|---------------------------------------|
| 0–30 days | R2 (hot) | ✅ Fast          | Daily cron moves to B2 after 30 days  |
| 31+ days  | B2 (cold) | ✅ Slightly slower | App sends explicit DELETE request    |
| Deleted   | Neither  | ❌               | Removed from both tiers by DELETE     |

**B2 has no automatic expiry.** Files remain in cold storage until the application
explicitly deletes them (disappearing-message sweep, manual delete, etc.).
The nightly cron reconciles B2 storage bytes with a bounded preflight `ListObjectsV2` scan,
then adds only successful B2 writes to a conservative in-run projection. It caps the
B2 inventory at 10 pages and tiering at 15 objects/run, keeping the preflight, B2 uploads,
and worst-case compensating deletes below Workers Free's 50 external subrequests. The
R2 inventory is separately capped at 400 pages (each R2 page and DO reservation consume
internal subrequests). If either inventory cannot be completed within its budget or a
listing fails, the cron aborts without overwriting storage counters. B2 objects are never
auto-deleted; the next night's preflight reconciles projected-counter drift.

---

## Security

- **Authentication:** Data-plane operations (PUT/GET/DELETE) require a
  short-lived, per-object capability token minted by the push server
  (SEC-A01) — never a shared secret. The operator-only `/stats` view requires
  `Authorization: Bearer <STATS_SECRET>`, a secret that is never shipped to a
  client (it replaces the APK-leaked `WORKER_SECRET`, S08-H1). Health check
  (`/health`) is public.
- **Encryption:** All file content is AES-256-GCM encrypted client-side before
  upload. The Worker and both storage tiers only ever see ciphertext.
- **Rate limiting:** 120 ordinary req/min per verified capability-token holder;
  single byte-range GETs used by 1 MiB chunked-AEAD playback have a configurable
  240 req/min class. Both share a 240 combined req/min per-holder ceiling in each
  edge isolate (advisory across edge locations); the 80K/day global request
  safety gate and separate SQLite-backed daily media-mutation budgets remain.
- **Storage cap:** Uploads rejected when R2 reaches the 9 GB decimal safety
  ceiling. Actual stored size is verified via R2 HEAD after upload (client
  `Content-Length` is not trusted).
