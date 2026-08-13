"use strict";

// ── Link-preview image proxy: signed capability URLs ─────────────────────────
//
// Finding addressed: S04-H3 (+ its client half, S08-H4).
//
// THE ATTACK. /linkPreview returns `preview.imageUrl` pointing at whatever host
// the linked page named in its `og:image` tag, and `MessageAdapter.java:891`
// hands that URL straight to `Glide.with(ctx).load(...)`. So an attacker who
// gets a link into a conversation learns, from their own web-server logs:
//   - the RECIPIENT's IP address (approximate location, ISP, home-vs-mobile), and
//   - the exact moment the message was rendered — a read receipt the recipient
//     never consented to and the app otherwise does not expose.
// Sending one link to a group enumerates every member's IP the same way.
//
// THE FIX, AND WHY IT'S SHAPED THIS WAY. The server already fetches the page, so
// it can fetch the image too and let the client load it from us instead. The only
// hard part is authorising that proxy request: `Glide.load(String)` sends no
// Firebase bearer token, so a token-gated endpoint would simply break previews
// (and a preview that fails to load is a preview someone will "fix" by reverting
// to the direct fetch). Instead the server RETURNS AN ALREADY-AUTHORISED URL: the
// target is HMAC-signed with a short expiry, so the URL itself is the capability.
//
// The decisive property: because the *server* rewrites `preview.imageUrl`, the
// leak closes for clients that were never rebuilt. An existing APK does
// `Glide.load(preview.imageUrl)` and now reaches our proxy, not the attacker.
// That matters because this environment cannot compile the Android app at all
// (no JDK/SDK), so a fix requiring a client change could not have been verified.
//
// What this is NOT: an open proxy. The signature covers the exact URL, so a
// caller cannot substitute their own target; only a URL the server itself
// extracted from a page it already chose to fetch is ever signed. The proxy
// re-runs the full egressGuard check at fetch time regardless, so a signed URL
// is still not permission to reach internal addresses.

const crypto = require("node:crypto");

// Short by design. The URL only needs to survive from the /linkPreview response
// until the client renders that message; anything longer just widens the window
// in which a leaked chat log can be replayed to confirm a link was previewed.
const IMAGE_PROXY_TTL_MS = 60 * 60 * 1000; // 1 hour

const PROXY_PATH = "/linkPreviewImage";

// Base64url so the signed payload survives a query string untouched — plain
// base64's "+/=" would be re-encoded by some HTTP clients and break the MAC.
function b64u(buffer) {
  return Buffer.from(buffer).toString("base64")
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function unb64u(text) {
  const padded = String(text).replace(/-/g, "+").replace(/_/g, "/");
  return Buffer.from(padded, "base64");
}

/**
 * Canonical string that gets signed. Both fields are length-delimited via the
 * 0x00 separator rather than simply concatenated, so `("ab","c")` and
 * `("a","bc")` cannot produce the same MAC input. (Same construction as the
 * /mintToken challenge in lib/identityVerify.js — kept deliberately consistent.)
 */
function signingInput(targetUrl, expiresAt) {
  return Buffer.concat([
    Buffer.from("DuoShield-linkPreviewImage-v1", "utf8"),
    Buffer.from([0x00]),
    Buffer.from(String(targetUrl), "utf8"),
    Buffer.from([0x00]),
    Buffer.from(String(expiresAt), "utf8"),
  ]);
}

function computeMac(targetUrl, expiresAt, secret) {
  return crypto.createHmac("sha256", secret).update(signingInput(targetUrl, expiresAt)).digest();
}

/**
 * Builds the signed proxy path for `targetUrl`.
 * Returns a root-relative path (callers prefix their own origin), or null when
 * no secret is configured — a null result must make the caller OMIT the image,
 * never fall back to the raw attacker URL.
 */
function signImageUrl(targetUrl, secret, { now = Date.now(), ttlMs = IMAGE_PROXY_TTL_MS } = {}) {
  if (!secret) return null;
  const expiresAt = now + ttlMs;
  const mac = computeMac(targetUrl, expiresAt, secret);
  const params = new URLSearchParams({
    u: b64u(Buffer.from(String(targetUrl), "utf8")),
    e: String(expiresAt),
    s: b64u(mac),
  });
  return `${PROXY_PATH}?${params.toString()}`;
}

/**
 * Verifies a signed proxy request's query parameters.
 * Returns `{ ok: true, targetUrl }` or `{ ok: false, reason }`.
 *
 * Fails closed on every malformed input, and compares MACs in constant time so
 * the signature cannot be recovered byte-by-byte through timing.
 */
function verifyImageUrl(params, secret, { now = Date.now() } = {}) {
  if (!secret) return { ok: false, reason: "image proxy not configured" };

  const encodedUrl = params.get("u");
  const expiresRaw = params.get("e");
  const providedMac = params.get("s");
  if (!encodedUrl || !expiresRaw || !providedMac) {
    return { ok: false, reason: "missing signature parameters" };
  }

  const expiresAt = Number(expiresRaw);
  if (!Number.isFinite(expiresAt)) return { ok: false, reason: "malformed expiry" };
  if (expiresAt <= now) return { ok: false, reason: "signature expired" };

  let targetUrl;
  try {
    targetUrl = unb64u(encodedUrl).toString("utf8");
  } catch {
    return { ok: false, reason: "malformed target" };
  }
  if (!targetUrl) return { ok: false, reason: "empty target" };

  let parsedTarget;
  try {
    parsedTarget = new URL(targetUrl);
  } catch {
    return { ok: false, reason: "malformed target" };
  }
  if (!["http:", "https:"].includes(parsedTarget.protocol) || !parsedTarget.hostname) {
    return { ok: false, reason: "unsupported target protocol" };
  }
  const normalizedTargetUrl = parsedTarget.toString();

  const expectedMac = computeMac(normalizedTargetUrl, expiresAt, secret);
  let suppliedMac;
  try {
    suppliedMac = unb64u(providedMac);
  } catch {
    return { ok: false, reason: "malformed signature" };
  }
  // timingSafeEqual throws on a length mismatch, so gate on length first.
  if (suppliedMac.length !== expectedMac.length) {
    return { ok: false, reason: "bad signature" };
  }
  if (!crypto.timingSafeEqual(suppliedMac, expectedMac)) {
    return { ok: false, reason: "bad signature" };
  }
  return { ok: true, targetUrl: normalizedTargetUrl };
}

// Only real raster/vector image types a preview thumbnail can legitimately be.
// An allowlist (not a blocklist) keeps the proxy from being turned into a
// general-purpose content relay for HTML or scripts.
const ALLOWED_IMAGE_TYPES = new Set([
  "image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp",
]);

/** Pure content-type check for a proxied image response. */
function isAllowedImageType(contentTypeHeader) {
  const type = String(contentTypeHeader || "").split(";")[0].trim().toLowerCase();
  return ALLOWED_IMAGE_TYPES.has(type);
}

module.exports = {
  signImageUrl,
  verifyImageUrl,
  isAllowedImageType,
  signingInput,
  IMAGE_PROXY_TTL_MS,
  PROXY_PATH,
  ALLOWED_IMAGE_TYPES,
};
