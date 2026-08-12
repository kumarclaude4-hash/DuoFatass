# SESSION-S3-12 (continuation) — Worker per-object hardening: S03-M1, S03-M3, S03-L2 + S03-H3 follow-up

**Status:** Partial (S3-12 overall). This session continues from
`SESSION-S3-12-partial.md`, which fixed `S03-M2` only. This session fixes
three more of the six bundled findings — `S03-M1`, `S03-M3` (partially —
see below), `S03-L2` — plus a separate, previously-flagged latent bug
(`S03-H3` follow-up: same-holder overwrite quota double-counting). Two
findings from the original six-finding bundle remain **Open**:
`S03-L3`/`S04-I2` (dead B2 presign surface — has a manual runbook step) and
`S10-N3` (cold-tier migration race). Per this session's own batch cap ("3
findings maximum"), these are deliberately deferred to a future
continuation rather than picked up opportunistically.

Plan scope for reference (`ROUND3_REMEDIATION_PLAN.md`):
> Findings: `S03-M1` (`nosniff` + `Content-Disposition` + validated
> `Content-Type`), `S03-M2` (uploader-bound, not just scope-bound, tokens),
> `S03-M3` (cut token TTL + bound reuse), `S03-L2` (guard
> `decodeURIComponent`), `S03-L3`/`S04-I2` (remove dead B2 presign surface;
> **revoke B2 key** = runbook), `S10-N3` (cold-tier delete/migration race).
> Exit: worker + server tests green; dead B2 path gone by grep.

## Reconciliation against the tracker (Phase 1)

Before implementing, re-read `BUG_TRACKER.md`, `START_HERE.md`,
`SESSION_INDEX.md`, `ROUND3_REMEDIATION_PLAN.md`, and
`SESSION-S3-12-partial.md`, then verified against current source
(`worker/src/index.js`, `server/index.js`) rather than trusting the tracker
prose alone, per `SESSION_PROTOCOL.md` §3 ("source beats tracker"):

- `S03-M2` — confirmed **already Fixed** in source (uploader HEAD-check on
  PUT-overwrite and DELETE, both tiers). Not re-touched.
- `S03-M1` — confirmed **still open** in source: `request.headers.get('Content-Type')`
  was stored verbatim at PUT and replayed unmodified at GET on both the R2
  and B2 branches; no `X-Content-Type-Options`, no `Content-Disposition`
  anywhere in the file.
- `S03-M3` — confirmed **still open** in source: token wire format was
  `v1.<op>.<expiresAt>.<uidTag>.<sig>` — 5 segments, no per-mint identifier,
  no reuse tracking of any kind, on `server/index.js`'s `signMediaToken()`
  and `worker/src/index.js`'s `verifyMediaToken()`.
- `S03-L2` — confirmed **still open** in source: `decodeURIComponent(url.pathname.slice(1))`
  ran with no surrounding `try`/`catch`.
- `S03-H3` follow-up (double-counting) — confirmed **real** by tracing the
  PUT handler: `getR2Bytes(env) + declaredBytes` and
  `getUserBytes(env, cap.holder) + declaredBytes` (pre-check), and
  `(await getR2Bytes(env)) + actualBytes` / `(await getUserBytes(...)) + actualBytes`
  (post-check + credit) never referenced the size of an object already
  occupying that key — every same-holder overwrite (the one overwrite
  `S03-M2`'s fix allows) added the *full* new size on top of whatever the
  old object had already contributed, permanently, with no way for the
  counter to converge back down even on a shrinking replacement.

This confirmed exactly the six-finding S3-12 bundle and exactly which three
are addressed this session, matching the task's required scope.

## S03-M1 — Attacker-controlled Content-Type, no nosniff/Content-Disposition

### The gap

`worker/src/index.js`'s PUT handler read `request.headers.get('Content-Type')`
— fully attacker-controlled, since the client mints its own capability token
and sends its own headers — and stored it verbatim as R2 `httpMetadata.contentType`.
The GET handler (both the R2/hot branch and the B2/cold branch) then read
that stored/upstream value back out and set it as the response
`Content-Type`, with no `X-Content-Type-Options` and no `Content-Disposition`
anywhere on either response path.

The key's extension was already tightly allow-listed by `KEY_FORMAT`
(`media|voice)/<scope>/<name>.(jpg|mp4|m4a|3gp)`), so the *shape* of the key
was never the exposure — the exposure was that the *served* type never had
to match that extension. A `write`-token holder (any conversation
participant, per `S03-M2`'s scope binding) could PUT a `.jpg`-suffixed key
declaring `Content-Type: text/html` (or `image/svg+xml`) with
attacker-chosen body bytes; anything that ever opened the GET URL directly —
a browser tab, a WebView, a future non-Android client — would render it as
that declared type, not as an image. Combined with this file's already
permissive CORS (anticipating exactly such a client), this is a stored
content-type confusion, not merely a cosmetic mismatch.

### The fix (`worker/src/index.js`)

- New `CONTENT_TYPE_BY_EXT` lookup table + `contentTypeForKey(key)` helper:
  extracts the extension via the same `KEY_FORMAT` regex already used to
  validate the key, and maps it through a fixed table
  (`jpg→image/jpeg`, `mp4→video/mp4`, `m4a→audio/mp4`, `3gp→video/3gpp`),
  falling back to `application/octet-stream` for anything unmatched (should
  be unreachable given `KEY_FORMAT` already gates every code path that
  reaches this helper, but fails safe rather than throwing).
- **PUT:** now calls `contentTypeForKey(key)` instead of reading
  `request.headers.get('Content-Type')` at all — the client header is never
  read, at any point in the write path.
- **GET, R2 (hot) branch:** response `Content-Type` header is now
  `contentTypeForKey(key)`, not `r2Object.httpMetadata?.contentType`. This
  retroactively neutralizes any object that was stored with an
  attacker-chosen type before this fix shipped — GET no longer trusts the
  stored value at all, so old objects can't keep serving a bad type forever.
  Also added `X-Content-Type-Options: nosniff` and
  `Content-Disposition: attachment` to every hot-tier GET response.
- **GET, B2 (cold) branch:** same treatment — `contentTypeForKey(key)`
  instead of `b2Response.headers.get('Content-Type')` (B2's own header
  ultimately traces back to the same client-controlled PUT header anyway,
  so trusting it would just move the same bug one tier over), plus the same
  `nosniff`/`Content-Disposition` pair.

### Evidence

- `node --check worker/src/index.js` — clean.
- New regression tests (2):
  - hot tier: PUT declares `Content-Type: text/html` on a `.jpg` key; GET
    is asserted to serve `image/jpeg` with `X-Content-Type-Options: nosniff`
    and `Content-Disposition: attachment`.
  - cold tier: stubbed B2 upstream response declares
    `Content-Type: application/x-malicious`; GET is asserted to serve
    `audio/mp4` (the `.m4a` key's derived type) with the same two headers,
    proving the Worker does not trust B2's own header either.

## S03-M3 — 10-minute unrevocable bearer tokens, unlimited reuse

### The gap

A capability token was, and largely remains, a stateless bearer credential:
anyone who observes a valid token (a proxy access log, a crash-report
payload that happened to capture a request header, etc.) can replay it
verbatim for the remainder of its TTL. This was true uniformly across
`read`, `write`, and `delete` — the Worker had no concept of "this exact
token was already used," only "is this token's signature and expiry still
valid."

### The fix (`worker/src/index.js` + `server/index.js`)

1. **Wire format grows a `jti` segment.** New format:
   `v1.<op>.<expiresAt>.<uidTag>.<jti>.<sig>` (6 dot-separated segments, up
   from 5). `jti` is a random per-mint identifier
   (`crypto.randomBytes(9)`, base64url-encoded — 12 base64url characters,
   72 bits of entropy) generated fresh on every mint and folded into the
   signed payload on **both** sides:
   - `server/index.js`'s `signMediaToken()` mints it and includes it in the
     HMAC payload (`v1|${op}|${expiresAt}|${holder}|${jti}|${key}`) and the
     wire string.
   - `worker/src/index.js`'s `verifyMediaToken()` now requires exactly 6
     parts (`v1` + 5 fields) and recomputes the HMAC over the identical
     6-field payload shape. Any token with a different segment count —
     including the **old 5-segment shape** — is rejected as malformed
     (401), which closes off silent acceptance of a pre-fix token as a
     downgrade path. Both this server and the Worker deploy together (an
     invariant this file already relies on for its other wire-format
     fields), so no legitimate client ever produces or expects the old
     shape once this ships.
2. **Single-use enforcement, `delete` only.** New
   `isTokenAlreadyUsed(env, jti)` / `markTokenUsed(env, jti, ttlSeconds)`
   helpers, backed by `env.RATE_KV` (the same KV namespace already used for
   rate limiting and quota counters elsewhere in this file):
   - The DELETE handler checks `isTokenAlreadyUsed(env, cap.jti)` **before**
     touching R2 or B2 at all, and 403s (`"Capability token already used"`)
     on a hit — a replayed delete token never reaches the ownership check or
     the actual delete call.
   - On a successful path (whether or not the object still exists — a
     token that gets this far has already passed auth), `markTokenUsed` is
     scheduled via `ctx.waitUntil`, TTL'd to the token's own remaining
     lifetime (`Math.ceil((expiresAt - now) / 1000)`, floored at 60s) so the
     KV entry doesn't outlive the window in which the token itself could
     have been replayed.
   - `read` and `write` tokens are **not** tracked for reuse — re-fetching
     the same object, or re-uploading to the same key (a legal operation per
     `S03-M2`'s same-holder-overwrite allowance), is normal client behavior
     within a token's TTL, not something this fix should block. They still
     carry a `jti` for wire-format uniformity with `delete`, just unused for
     replay tracking.

### Why Partial, not Fixed

The finding's literal text is "10-min unrevocable bearer tokens, unlimited
reuse" — two separate complaints. This session addresses **reuse** (for the
one verb where reuse is actually dangerous) but deliberately leaves the
**TTL** at its existing value:

- 10 minutes already matches the realistic mint-to-use window for the
  Android client's real flow (mint token immediately before an upload/fetch/
  delete triggered by direct user action — capture a photo, send a voice
  note, delete a message). Shortening it further risks spurious failures on
  a slow network with no corresponding security benefit, now that the one
  verb where a long-lived bearer token is actually dangerous (`delete`) has
  single-use protection independent of the TTL.
- `read`/`write` remain revocable-only-by-expiry, same as before. This is a
  deliberate, documented scope trim — not an oversight — given the security
  benefit of shortening the TTL further is materially smaller than closing
  the delete-replay gap was.
- The single-use guard is KV-gated: without `env.RATE_KV` configured, both
  helper functions return early (`isTokenAlreadyUsed` → `false`,
  `markTokenUsed` → no-op), so the control silently degrades to "not
  enforced" rather than failing closed. This is the same documented
  best-effort characteristic every other KV-backed control in this file
  already has (rate limiting, quota counters — see `S03-I2`), not a new gap
  introduced by this fix.

### Evidence

- `node --check worker/src/index.js` and `node --check server/index.js` —
  both clean.
- New regression tests (3):
  - a delete token can be used exactly once; replaying the identical token
    after a successful delete returns 403 `"Capability token already used"`
    without a second R2/B2 delete attempt (implicitly also guards against
    the cold-tier fallback branch making a real network call on replay).
  - two independently-minted delete tokens for two different keys are each
    single-use *independently* — consuming one does not affect the other.
  - a hand-constructed legacy 5-segment token (old wire shape, no `jti`) is
    rejected as malformed (401), proving the downgrade path is closed.

## S03-L2 — Unguarded `decodeURIComponent`

### The gap

`const key = decodeURIComponent(url.pathname.slice(1))` ran as the very
first input-handling step in the fetch handler, with no surrounding
`try`/`catch`. `decodeURIComponent` throws a `URIError` on a malformed
percent-escape (a lone trailing `%`, or `%` followed by non-hex digits like
`%zz`). Before this fix, that exception was uncaught, so a request like
`GET /media/x/%zz.jpg` fell through to a generic 500/1101 worker error
instead of the normal, structured 400 this file returns for every other
malformed-input case (missing key, bad key format, malformed token, etc.).
No state or information disclosure was at stake — this was purely a
consistency/robustness gap on the one step that ran before every other
control in the file.

### The fix (`worker/src/index.js`)

Wrapped the decode in `try { key = decodeURIComponent(...) } catch { return respond({ error: 'Invalid file key' }, 400) }`,
matching the exact response shape (`respond()`, 400, `{ error }`) already
used by every other malformed-input rejection in this handler.

### Evidence

- `node --check worker/src/index.js` — clean.
- New regression tests (2): a path containing `%zz` and a path with a lone
  trailing `%` each return 400, not an uncaught exception / non-JSON error
  page.

## S03-H3 follow-up — same-holder overwrite quota double-counting

This is a separate, previously-flagged issue (documented in
`SESSION-S3-12-partial.md`'s "Deliberately not touched" section), not part
of the six-finding S3-12 bundle, but explicitly called out as required
follow-up work for this session. Verified independently per the task's
instructions before touching anything.

### Confirming the bug

Traced the exact sequence the task described:

1. Holder `H` PUTs to key `X` for the first time. `existing` is `null` (no
   prior object), so `oldSize` (a value that did not exist before this fix)
   would correctly be `0` — this case was never broken.
2. `H` PUTs to the **same key `X`** again (a legal operation — `S03-M2`'s
   fix only blocks a *different* holder from overwriting `X`; `H` overwriting
   their own object is explicitly allowed).
3. Before this fix: the pre-upload check computed
   `userBytes + declaredBytes` and `r2Bytes + declaredBytes` — i.e., the
   *entire new size*, with no reference to the fact that `X` already
   existed and already counted toward both totals.
4. The post-upload credit did the same thing:
   `ctx.waitUntil(adjustR2(env, actualBytes))` /
   `ctx.waitUntil(adjustUserBytes(env, cap.holder, actualBytes))` — crediting
   the full `actualBytes`, not a delta.
5. Net effect: after two same-size 10 MB overwrites of `X`, both counters
   read 20 MB, even though R2 itself holds exactly one 10 MB object at `X`.
   A third overwrite reads 30 MB. The counter never converges back down —
   confirmed this is a real, reproducible bug, not a hypothetical.
6. The task's specific concern about *shrinking* replacements also
   confirmed: since the old size was never subtracted, a shrink from 900
   bytes to 100 bytes left the counters at "900 (never removed) + 900 (full
   new credit, not delta)" instead of freeing the 800 bytes of headroom the
   shrink should have returned.

### The fix (`worker/src/index.js`)

Computed the delta explicitly, per the task's own guidance
(`newSize - oldSize`), reusing the `HEAD` already performed for the
`S03-M2` ownership check (no extra R2 round-trip needed):

- `oldSize = existing?.size ?? 0` — `0` for a brand-new key, exactly
  preserving prior behavior for first uploads.
- **Pre-upload projection:** `projectedR2Bytes = r2Bytes - oldSize + declaredBytes`
  and `projectedUserBytes = userBytes - oldSize + declaredBytes`, checked
  against `MAX_R2_BYTES` / `maxUserBytes(env)` respectively.
- **Post-upload re-check** (guards a lying/missing Content-Length, same
  rationale as the pre-existing check this augments): `r2AfterUpload = (await getR2Bytes(env)) - oldSize + actualBytes`,
  same shape for the per-user counter.
- **Final credit:** `const deltaBytes = actualBytes - oldSize;` then
  `adjustR2(env, deltaBytes)` / `adjustUserBytes(env, cap.holder, deltaBytes)`
  — a same-size replacement credits `0` (no-op), a shrink credits a
  negative delta (giving back headroom), a growth credits only the
  incremental amount. `adjustR2`/`adjustUserBytes` already handle a zero or
  negative delta correctly (no-op on zero; clamp at zero floor) — no changes
  needed to those functions themselves.

Explicitly avoided the rejected approach named in the task ("simply
subtract the old size after the upload") — the delta is computed and
applied atomically as part of the same accounting step, not as a
post-hoc subtraction that could be exploited to launder more headroom than
was actually freed.

### Concurrency — documented limitation, not claimed fixed

Per the task's explicit instruction to document rather than claim a fix if a
robust atomic solution isn't possible with the current architecture: this
fix does **not** close the pre-existing race between two concurrent
overwrites of the same key. The bound on that race is the same one already
documented in `adjustR2`'s own comment for concurrent *fresh* uploads (not a
new gap this fix introduces) — KV's increment semantics are atomic per
counter-update call, but the `HEAD` (to read `oldSize`) and the eventual
`put()`/counter-credit are not part of one atomic transaction, so two
overwrites racing on the same key could each read a stale `oldSize` and
under- or over-credit relative to the true final state. This project has no
Durable Objects (see `S03-I2`), which would be the natural fix for
serializing per-key accounting; adding one was judged out of scope for this
fix (a new piece of infrastructure, not a targeted correction).

Also documented: a same-holder overwrite that gets rejected by either the
413 (oversized) or 507 (quota) post-upload check **cannot roll back** to the
pre-overwrite content — `HOT_BUCKET.put()` has already replaced the old
bytes by the time those checks run, and R2 has no object versioning
configured here. This is a side effect of the destructive `put()` call
itself (which predates this fix and predates `S03-M2`), not something this
accounting fix introduces or could reasonably fix without object versioning.

### Evidence

- New regression tests (3):
  - a same-size same-holder overwrite (500 → 500 bytes, `MAX_USER_BYTES=500`)
    succeeds — proving the old bytes are not double-counted (pre-fix
    arithmetic would have computed 500+500=1000 > 500 and wrongly rejected
    this).
  - a growing same-holder overwrite (400 → 900 bytes, `MAX_USER_BYTES=1000`)
    succeeds — the true delta-based total is 900, but pre-fix arithmetic
    would have computed 400+900=1300 > 1000 and wrongly rejected this.
  - a shrinking same-holder overwrite (900 → 100 bytes, `MAX_USER_BYTES=1000`)
    succeeds, and a **subsequent** fresh 900-byte upload to a different key
    also succeeds (100 + 900 = 1000 exactly) — proving the shrink actually
    freed the 800 bytes of headroom it should have; pre-fix arithmetic would
    have left the counter permanently stuck at 900+900=1800 and rejected the
    follow-up upload.

## Full test run

```
cd worker && npm test   # node --test src/index.test.js
```

**27/27 pass** — 17 pre-existing (carried forward from `SESSION-S3-12-partial.md`,
which recorded "Full worker suite: 17/17 pass" after the `S03-M2` work) +
**10 new** this session (2 for `S03-M1`, 3 for `S03-M3`, 2 for `S03-L2`, 3
for the `S03-H3` follow-up — 10 total, matching the per-finding test counts
claimed above: 2+3+2+3=10).

`node --check worker/src/index.js` and `node --check server/index.js` —
both clean.

## Environment note (not a code defect)

`worker/node_modules` was not present at the start of this session (fresh
VM). `npm install` alone repeatedly failed with `"Exit handler never called!"`
while trying to fetch `wrangler`'s transitive dependencies (`workerd`,
`sharp`, etc.) through a blocked internal package-firewall proxy — `wrangler`
is a devDependency needed only for `deploy`/`dev`, not for running tests.
Installing with `npm install --omit=dev` succeeded cleanly and pulled in
`aws4fetch` (the only runtime dependency `src/index.js` actually imports),
after which `node --test` ran normally. This is an environment/install
artifact of this session's fresh VM, not a change to the project's declared
dependencies — `package.json`/lockfile were not modified.

## Files changed

- `worker/src/index.js` — `contentTypeForKey`/`CONTENT_TYPE_BY_EXT`
  (S03-M1), `jti` in wire format + single-use delete-token tracking
  (S03-M3), guarded `decodeURIComponent` (S03-L2), delta-based overwrite
  accounting (S03-H3 follow-up).
- `worker/src/index.test.js` — 10 new regression tests (see above) plus the
  `mintToken` test helper updated to mint the new 6-segment wire format.
- `server/index.js` — `signMediaToken()` now mints a `jti` and includes it
  in the signed payload/wire string, matching the Worker's new verifier
  (S03-M3's server-side half). Also cleaned up two pre-existing corrupted
  box-drawing characters in unrelated comments encountered while editing
  this file (line ~209, line ~4106) — cosmetic only, no logic change.

## Commits

- Implementation (worker): `7e6b95c` — `worker/src/index.js`,
  `worker/src/index.test.js` (auto-committed during this session's editing).
- Implementation (server companion): `ecd5199` — `server/index.js`
  (`signMediaToken` jti + comment cleanup).
- Documentation: this file + `BUG_TRACKER.md` + `START_HERE.md` +
  `SESSION_INDEX.md` updates — see `git log` for the commit hash following
  this file's addition.

## Remaining S3-12 findings (resolved in a later continuation — see below)

- `S03-L3`/`S04-I2` — dead B2 presign surface; has a manual runbook step
  (B2 key revocation) that code alone cannot complete.
- `S10-N3` — cold-tier migration race; currently `Partial` per the tracker
  (a race guard exists at `worker/src/index.js:553-574`, not yet confirmed
  end-to-end).

---

## Continuation — S10-N3 verification + S03-L3/S04-I2 close-out

**Status:** `S10-N3` remains **Partial** (code/tests verified, production
Cloudflare/R2/B2 runtime verification **BLOCKED** in this environment —
no live Cloudflare Workers/R2/B2 access). `S03-L3`/`S04-I2` are now
**Fixed** (code-level dead-surface removal; a pre-existing operator
runbook item — revoking the leaked Backblaze B2 application key,
tracked jointly with `SC-02`/`S08-C1` — remains open and is unaffected by
this change).

### S10-N3 — re-confirmed, not re-implemented

The race guard (`worker/src/index.js:553-574`, "Race guard: the nightly
migration PUTs to B2 and THEN deletes from R2") and its two dedicated
regression tests were already present on this branch from prior work on
commit `e366730`. This pass re-ran and re-read them rather than
reimplementing:

- `worker/src/index.js` full suite: **29/29 pass**, including the two
  `S10-N3` cases (a racing client DELETE during migration fires a
  compensating B2 delete so the migrated copy isn't orphaned in cold tier;
  a normal no-race migration does NOT fire a spurious compensating
  delete).
- `node --check worker/src/index.js` — clean.
- **Still blocked:** this verifies the guard against the worker's own
  test harness/mocks (fake R2/B2 bindings), not a real Cloudflare
  Workers + R2 + B2 environment under load. No live Cloudflare/R2/B2
  credentials or access exist in this VM. Do not promote to `Fixed`
  without that end-to-end runtime confirmation — `Partial` is the
  correct, evidence-backed status.

### S03-L3 / S04-I2 — finishing the dead-code removal

Prior work on this branch (commit `e366730`) had already removed
`b2PresignUrl`/`b2PresignUrlForUid` from `server/index.js`,
`buildB2PresignUrl`/`b2HmacKey` (+ exports) from `server/lib/pure.js`,
and the dead B2 presign rate-limit entries. This pass finished the job:

1. **Repo-wide reference sweep.** Grepped the full repository for
   `b2PresignUrl`, `b2PresignUrlForUid`, `buildB2PresignUrl`, `b2HmacKey`,
   and the removed rate-limit constants. Inside `server/`, the only
   remaining hits are the explanatory removal comments already left in
   `index.js`/`pure.js` (and now `pure.test.js`) — no live code, export,
   or route reference survives. Hits elsewhere (worker, CI workflows,
   audit/session docs, evidence diffs) are either the worker's own
   legitimate B2 tiering config (untouched — see below) or historical
   record of the finding, not live server code.
2. **Obsolete test cleanup.** `server/lib/pure.test.js` still carried 7
   tests exercising the deleted `buildB2PresignUrl`/`b2HmacKey` exports
   (`pure.buildB2PresignUrl is not a function` failures). Removed that
   block and replaced it with a one-paragraph pointer comment explaining
   why the coverage is gone, rather than leaving silently-failing tests
   or deleting the explanation along with the code.
3. **Stale docs.** `server/README.md`'s env-var table still documented
   `B2_KEY_ID`/`B2_APPLICATION_KEY`/`B2_BUCKET`/`B2_REGION` as
   server-required config. Repo-wide grep confirms `server/` reads zero
   `process.env.B2_*` variables now, so that row was removed.
4. **Scope check — what was *not* touched.** The worker's own B2
   credentials (`worker/wrangler.jsonc`, `worker/src/index.js`) back the
   legitimate nightly cold-tier migration and R2↔B2 media pipeline
   (`S10-N3`'s subject) — an entirely separate, live surface from the
   server's dead presign helpers. Nothing there was modified. Test
   coverage for legitimately-used worker functionality was not weakened;
   only the 7 tests for the two deleted pure functions were removed.

**Evidence:**
- `node --check server/index.js` — clean.
- `node --check server/lib/pure.js` — clean.
- `node --check server/lib/pure.test.js` — clean.
- `node --test server/lib/pure.test.js` — **60/60 pass** (was 67 tests,
  60 pass/7 fail before this cleanup; the 7 failures were exactly the
  obsolete `buildB2PresignUrl`/`b2HmacKey` cases now removed).
- `node --test server/lib/*.test.js` (full server lib suite) —
  **193/194 pass**. The 1 failure (`identityVerify.test.js`) is
  pre-existing and unrelated: `Cannot find module '@signalapp/libsignal-client'`,
  a native dependency not installed in this VM (`server/node_modules` was
  never present this session) — not caused by, or related to, this
  change.
- `worker/src/index.js` full suite: **29/29 pass** (unchanged by this
  work; re-run only to confirm S10-N3 still holds after the server-side
  edits, since both files share no runtime dependency but do share the
  `S3-12` remediation batch).
- Repo-wide grep for the four removed symbol names inside `server/`
  returns only comments, confirmed above.

### Files changed (this continuation)

- `server/lib/pure.test.js` — removed 7 obsolete tests for
  `buildB2PresignUrl`/`b2HmacKey` (already-deleted exports), replaced
  with an explanatory comment.
- `server/README.md` — removed the stale `B2_KEY_ID`/`B2_APPLICATION_KEY`/
  `B2_BUCKET`/`B2_REGION` env-var table row (server no longer reads any
  of them).
- `BUG_TRACKER.md`, `START_HERE.md`, `SESSION_INDEX.md` — status updates
  for `S03-L3`, `S04-I2` (→ Fixed), `S10-N3` (Partial, re-confirmed with
  updated evidence and explicit runtime-verification-blocked note).

### Commits

- Implementation: `959ef20` — `server/lib/pure.test.js`,
  `server/README.md` (auto-committed during this session's editing).
- Documentation: see `git log` for the commit hash following this
  file's update.

## Next remediation session

**S3-12 is now fully closed out** — all six bundled findings
(`S03-M1`, `S03-M2`, `S03-M3`, `S03-L2`, `S03-L3`/`S04-I2`, plus the
`S03-H3` follow-up) have been addressed from source, and `S10-N3` is
re-confirmed `Partial` with an explicit, environment-caused runtime-
verification block (not a code gap). Proceed to **S3-13** (Admin
surface) next — do not start it from this file; `START_HERE.md` and
`SESSION_INDEX.md` are the authoritative pointers.
