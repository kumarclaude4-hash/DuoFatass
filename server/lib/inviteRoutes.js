"use strict";

/**
 * HTTP handlers for the invite lifecycle.
 *
 * This module is deliberately dependency-injected: production supplies the
 * real Firestore-backed invite store, admin-auth/body middleware and request
 * helpers; tests can drive the exact same handlers through a real Node HTTP
 * server without booting Firebase Admin or its background listeners.
 */
function createInviteRouteHandler(dependencies) {
  const {
    inviteStore,
    invite,
    db,
    requireAdminAuth,
    requireAdminAuthThenBody,
    checkWaitlistPollRateLimit,
    getClientIp,
    readBody,
    sendServerError,
    logger = console,
  } = dependencies || {};

  for (const [name, value] of Object.entries({
    inviteStore,
    invite,
    db,
    requireAdminAuth,
    requireAdminAuthThenBody,
    checkWaitlistPollRateLimit,
    getClientIp,
    readBody,
    sendServerError,
  })) {
    if (!value) throw new TypeError("createInviteRouteHandler requires " + name);
  }

  async function listInvites(req, res) {
    if (!(await requireAdminAuth(req, res))) return;

    try {
      const requestUrl = new URL(req.url, "http://localhost");
      const data = await inviteStore.list({
        status: requestUrl.searchParams.get("status") || "all",
        search: requestUrl.searchParams.get("search") || "",
        pageSize: requestUrl.searchParams.get("pageSize"),
        cursor: requestUrl.searchParams.get("cursor"),
      });
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(data));
    } catch (error) {
      if (error instanceof TypeError) {
        res.writeHead(400, { "Content-Type": "text/plain" });
        res.end(error.message);
      } else {
        sendServerError(res, "admin/api/invites", error);
      }
    }
  }

  function createInvite(req, res) {
    requireAdminAuthThenBody(req, res, async (body) => {
      try {
        let parsed;
        try {
          parsed = JSON.parse(body);
        } catch {
          throw new TypeError("Invalid JSON");
        }
        const created = await inviteStore.create(parsed, req);
        res.writeHead(201, {
          "Content-Type": "application/json",
          "Cache-Control": "no-store",
        });
        res.end(JSON.stringify(created));
      } catch (error) {
        if (error instanceof TypeError) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end(error.message);
        } else {
          sendServerError(res, "admin/api/invites/create", error);
        }
      }
    });
  }

  function revokeInvite(req, res) {
    requireAdminAuthThenBody(req, res, async (body) => {
      try {
        let parsed;
        try {
          parsed = JSON.parse(body);
        } catch {
          throw new TypeError("Invalid JSON");
        }
        const outcome = await inviteStore.revoke(
          parsed && parsed.inviteId,
          parsed && parsed.reason,
          req
        );
        if (outcome.status === "missing") {
          res.writeHead(404);
          res.end("Invite not found");
          return;
        }
        if (outcome.status === "conflict") {
          res.writeHead(409);
          res.end(`Invite is ${outcome.current}`);
          return;
        }
        if (outcome.status !== "revoked") {
          throw new Error("Invite store returned an invalid revoke outcome");
        }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (error) {
        if (error instanceof TypeError) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end(error.message);
        } else {
          sendServerError(res, "admin/api/invites/revoke", error);
        }
      }
    });
  }

  async function validateInvite(req, res) {
    try {
      if (!checkWaitlistPollRateLimit(getClientIp(req))) {
        res.writeHead(429, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ valid: false }));
        return;
      }

      const body = await readBody(req, res);
      let token = "";
      try {
        token = invite.normalizeInviteToken(JSON.parse(body).inviteToken);
      } catch {
        // Keep malformed JSON and malformed tokens indistinguishable from any
        // other invalid invite. This endpoint is not a lifecycle oracle.
      }

      let valid = false;
      if (invite.isInviteToken(token)) {
        valid = await inviteStore.isActive(token);
      } else if (/^[0-9a-f]{32}$/.test(token)) {
        // Bounded migration support for previously approved waitlist IDs.
        const legacy = await db.collection("waitlist").doc(token).get();
        valid = legacy.exists && legacy.data().status === "approved";
      }

      res.writeHead(200, {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
      });
      res.end(JSON.stringify({ valid: Boolean(valid) }));
    } catch (error) {
      logger.error("invite/validate error:", error.message);
      if (res.headersSent) return;
      res.writeHead(500, {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
      });
      res.end(JSON.stringify({ valid: false }));
    }
  }

  return function handleInviteRoute(req, res) {
    const pathname = (req.url || "").split("?")[0];

    if (req.method === "GET" && pathname === "/admin/api/invites") {
      void listInvites(req, res);
      return true;
    }
    if (req.method === "POST" && pathname === "/admin/api/invites/create") {
      createInvite(req, res);
      return true;
    }
    if (req.method === "POST" && pathname === "/admin/api/invites/revoke") {
      revokeInvite(req, res);
      return true;
    }
    if (req.method === "POST" && pathname === "/invite/validate") {
      void validateInvite(req, res);
      return true;
    }
    return false;
  };
}

module.exports = {
  createInviteRouteHandler,
};
