"use strict";

/**
 * Seed-identity proof-of-possession and Firebase custom-token issuance route.
 *
 * This endpoint intentionally does NOT require a Firebase bearer token: it is
 * the bootstrap endpoint that issues the first Firebase token. Ownership is
 * established by consuming a server-issued, one-use nonce and verifying the
 * XEdDSA signature made by the seed-derived Signal identity private key.
 * Production and HTTP tests use this same injected handler.
 */
function createMintTokenRouteHandler(dependencies) {
  const {
    collectBody,
    getClientIp,
    checkIpRateLimit,
    ipTag,
    uidTag,
    mintCooldown,
    mintCooldownMs,
    mintChallengeStore,
    verifyMintTokenSignature,
    sha256hex,
    db,
    FieldValue,
    inviteStore,
    invite,
    claimIdentityInTransaction,
    auditAdminEventInTransaction,
    createCustomToken,
    logger = console,
    now = Date.now,
  } = dependencies || {};

  const required = {
    collectBody,
    getClientIp,
    checkIpRateLimit,
    ipTag,
    uidTag,
    mintCooldown,
    mintCooldownMs,
    mintChallengeStore,
    verifyMintTokenSignature,
    sha256hex,
    db,
    FieldValue,
    inviteStore,
    invite,
    claimIdentityInTransaction,
    auditAdminEventInTransaction,
    createCustomToken,
  };
  for (const [name, value] of Object.entries(required)) {
    if (value === undefined || value === null) {
      throw new TypeError("createMintTokenRouteHandler requires " + name);
    }
  }
  if (typeof collectBody !== "function" ||
      typeof getClientIp !== "function" ||
      typeof checkIpRateLimit !== "function" ||
      typeof ipTag !== "function" ||
      typeof uidTag !== "function" ||
      typeof mintCooldown.get !== "function" ||
      typeof mintCooldown.set !== "function" ||
      typeof mintCooldown.delete !== "function" ||
      typeof mintCooldownMs !== "number" ||
      typeof mintChallengeStore.consume !== "function" ||
      typeof verifyMintTokenSignature !== "function" ||
      typeof sha256hex !== "function" ||
      typeof db.runTransaction !== "function" ||
      typeof FieldValue.serverTimestamp !== "function" ||
      typeof inviteStore !== "object" ||
      typeof claimIdentityInTransaction !== "function" ||
      typeof auditAdminEventInTransaction !== "function" ||
      typeof createCustomToken !== "function" ||
      typeof now !== "function") {
    throw new TypeError("createMintTokenRouteHandler received an invalid dependency");
  }

  async function processBody(req, res, body) {
    // Keep the release closure outside the try block so every failure after
    // reserving a per-user cooldown restores the previous timestamp.
    let releaseCooldown = () => {};
    try {
      // Preserve the existing ordering: the IP bucket is consulted before
      // JSON parsing, but after the shared request-body collector has enforced
      // the server's byte cap.
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

      // Proof-of-possession is mandatory. Shape validation happens before the
      // cooldown slot or nonce is touched, so a legacy/malformed request cannot
      // reserve a victim's account cooldown or consume a valid challenge.
      if (!nonce || typeof nonce !== "string" ||
          !signatureHex || typeof signatureHex !== "string") {
        res.writeHead(400, { "Content-Type": "text/plain" });
        res.end("Missing or invalid nonce / signatureHex — call POST /mintChallenge first");
        return;
      }

      // Reserve synchronously before the first await. This prevents concurrent
      // requests for the same seed-derived user ID from both passing cooldown.
      const timestamp = now();
      const last = mintCooldown.get(userId) || 0;
      if (timestamp - last < mintCooldownMs) {
        res.writeHead(429, { "Content-Type": "text/plain" });
        res.end("Too many requests — wait 60 s and retry");
        return;
      }
      mintCooldown.set(userId, timestamp);
      // Restore the previous value on every failure. Do not blindly delete a
      // pre-existing timestamp that may belong to a prior successful mint.
      releaseCooldown = () => {
        if (last === 0) mintCooldown.delete(userId);
        else mintCooldown.set(userId, last);
      };

      // Consume first, verify second. A failed signature spends the nonce,
      // preventing signature grinding against one outstanding challenge.
      if (!mintChallengeStore.consume(userId, nonce)) {
        releaseCooldown();
        logger.warn(
          "mintToken: no valid outstanding challenge for uid=" + uidTag(userId) +
          " (unknown, expired, or already-used nonce) — refusing to mint (S07-C1)"
        );
        res.writeHead(403, { "Content-Type": "text/plain" });
        res.end("Key mismatch");
        return;
      }

      if (!verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex: nonce, signatureHex })) {
        releaseCooldown();
        logger.warn(
          "mintToken: identity signature verification FAILED for uid=" + uidTag(userId) +
          " — refusing to mint (S07-C1)"
        );
        res.writeHead(403, { "Content-Type": "text/plain" });
        res.end("Key mismatch");
        return;
      }

      const incomingHash = sha256hex(identityPubKeyHex);

      // The helper owns the Firestore transaction: account-lock check, invite
      // consumption, identity binding and audit all commit or roll back
      // atomically. No custom token is issued before that transaction succeeds.
      const claim = await db.runTransaction((tx) => claimIdentityInTransaction({
        tx,
        firestore: db,
        FieldValue,
        inviteStore,
        invite,
        userId,
        incomingHash,
        inviteToken,
        requestContext: req,
        auditAdminEventInTransaction,
        tagUserId: uidTag,
      }));
      const isNewAccount = claim.isNewAccount;

      const token = await createCustomToken(userId);
      logger.log("mintToken: issued token for userId=" + uidTag(userId) + " newAccount=" + isNewAccount);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ token }));
    } catch (error) {
      // A failed attempt must not leave a cooldown stamp that can deny the
      // legitimate seed owner. A successful mint deliberately retains it.
      releaseCooldown();
      if (error && error.status === 403) {
        let attemptedUid = "none";
        try {
          attemptedUid = uidTag(JSON.parse(body || "{}").userId);
        } catch {
          // Malformed body is already handled by the generic failure below.
        }
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
  }

  return function handleMintTokenRoute(req, res) {
    if (req.method !== "POST" || req.url !== "/mintToken") return false;
    collectBody(req, res, (body) => {
      void processBody(req, res, body);
    });
    return true;
  };
}

module.exports = { createMintTokenRouteHandler };
