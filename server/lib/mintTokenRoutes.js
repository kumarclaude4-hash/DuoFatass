"use strict";

/**
 * HTTP boundary for POST /mintToken.
 *
 * The production server injects the real challenge store, libsignal verifier,
 * atomic Firestore identity-claim transaction and Firebase custom-token mint.
 * Tests run this exact handler over a real Node HTTP server with isolated
 * dependencies or a Firestore emulator.
 */
function createMintTokenRouteHandler(dependencies) {
  const {
    collectBody,
    getClientIp,
    checkIpRateLimit,
    ipTag,
    mintCooldown,
    mintCooldownMs,
    mintChallengeStore,
    verifyMintTokenSignature,
    sha256hex,
    claimIdentity,
    createCustomToken,
    uidTag,
    now = Date.now,
    logger = console,
  } = dependencies || {};

  for (const [name, value] of Object.entries({
    collectBody, getClientIp, checkIpRateLimit, ipTag, mintCooldown,
    mintCooldownMs, mintChallengeStore, verifyMintTokenSignature,
    sha256hex, claimIdentity, createCustomToken, uidTag,
  })) {
    if (value === undefined || value === null) {
      throw new TypeError("createMintTokenRouteHandler requires " + name);
    }
  }
  if (!(mintCooldown instanceof Map) || typeof mintCooldownMs !== "number" ||
      typeof collectBody !== "function" || typeof getClientIp !== "function" ||
      typeof checkIpRateLimit !== "function" || typeof mintChallengeStore.consume !== "function" ||
      typeof verifyMintTokenSignature !== "function" || typeof sha256hex !== "function" ||
      typeof claimIdentity !== "function" || typeof createCustomToken !== "function" ||
      typeof uidTag !== "function" || typeof ipTag !== "function" || typeof now !== "function") {
    throw new TypeError("createMintTokenRouteHandler received invalid dependencies");
  }

  return function handleMintTokenRoute(req, res) {
    if (req.method !== "POST" || req.url !== "/mintToken") return false;

    collectBody(req, res, async (body) => {
      let releaseCooldown = () => {};
      try {
        const clientIp = getClientIp(req);
        if (!checkIpRateLimit(clientIp)) {
          logger.warn("mintToken: IP rate limit hit ip=" + ipTag(clientIp));
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        const { userId, identityPubKeyHex, nonce, signatureHex, inviteToken } =
          JSON.parse(body);
        if (!userId || typeof userId !== "string" ||
            !identityPubKeyHex || typeof identityPubKeyHex !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId / identityPubKeyHex");
          return;
        }

        // The challenge and signature are mandatory. Shape failures happen
        // before the per-UID cooldown is reserved, preserving S07-C1 fail-closed
        // behaviour without allowing malformed requests to affect a victim.
        if (!nonce || typeof nonce !== "string" ||
            !signatureHex || typeof signatureHex !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid nonce / signatureHex — call POST /mintChallenge first");
          return;
        }

        // Reserve synchronously before the first await to close the concurrent
        // per-user mint race. Only successful token issuance retains the slot.
        const timestamp = now();
        const last = mintCooldown.get(userId) || 0;
        if (timestamp - last < mintCooldownMs) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests — wait 60 s and retry");
          return;
        }
        mintCooldown.set(userId, timestamp);
        releaseCooldown = () => {
          if (last === 0) mintCooldown.delete(userId);
          else mintCooldown.set(userId, last);
        };

        // Consume before verifying: each attempted signature spends the
        // outstanding nonce, preventing repeated guesses against one challenge.
        if (!mintChallengeStore.consume(userId, nonce)) {
          releaseCooldown();
          logger.warn("mintToken: no valid outstanding challenge for uid=" + uidTag(userId) +
            " (unknown, expired, or already-used nonce) — refusing to mint (S07-C1)");
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Key mismatch");
          return;
        }

        if (!verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex: nonce, signatureHex })) {
          releaseCooldown();
          logger.warn("mintToken: identity signature verification FAILED for uid=" + uidTag(userId) +
            " — refusing to mint (S07-C1)");
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Key mismatch");
          return;
        }

        const incomingHash = sha256hex(identityPubKeyHex);
        const claim = await claimIdentity({
          userId,
          incomingHash,
          inviteToken,
          requestContext: req,
        });
        const isNewAccount = claim.isNewAccount;

        // Mint the Firebase credential only after the atomic identity/invite
        // claim resolves successfully.
        const token = await createCustomToken(userId);
        logger.log("mintToken: issued token for userId=" + uidTag(userId) +
          " newAccount=" + isNewAccount);
        res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ token }));
      } catch (error) {
        releaseCooldown();
        if (error && error.status === 403) {
          let attemptedUid = "none";
          try { attemptedUid = uidTag(JSON.parse(body || "{}").userId); } catch { /* unparsable body */ }
          logger.warn("mintToken: 403 (" + error.message + ") for userId=" + attemptedUid);
          if (res.headersSent) return;
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end(error.message);
        } else {
          logger.error("mintToken error:", error && error.message);
          if (res.headersSent) return;
          res.writeHead(500, { "Content-Type": "text/plain" });
          res.end("Internal server error");
        }
      }
    });
    return true;
  };
}

module.exports = { createMintTokenRouteHandler };
