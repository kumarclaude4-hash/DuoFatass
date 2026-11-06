"use strict";

// End-to-end HTTP + Firestore-emulator coverage for the extracted production
// invite router. The fast HTTP tests use a controlled fake store; this suite
// proves the same handlers operate against actual Firestore transactions,
// persisted invite documents and transactional audit writes.

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const http = require("node:http");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { createInviteStore } = require("./inviteStore");
const { createInviteRouteHandler } = require("./inviteRoutes");
const invite = require("./invite");

const emulatorHost = process.env.FIRESTORE_EMULATOR_HOST;
const requireEmulator = process.env.DUOSHIELD_REQUIRE_FIRESTORE_EMULATOR === "1";

if (!emulatorHost) {
  if (requireEmulator) {
    test("Firestore emulator must be configured for invite HTTP integration tests", () => {
      assert.fail("FIRESTORE_EMULATOR_HOST is required; refusing to silently skip invite HTTP integration coverage");
    });
  } else {
    test("Invite HTTP + Firestore integration (requires emulator)", { skip: true }, () => {});
  }
} else {
  test("invite HTTP routes create, list, validate and revoke against real Firestore with atomic audit", async (t) => {
    const projectId = process.env.GCLOUD_PROJECT || "duoshield-test";
    const suffix = crypto.randomBytes(8).toString("hex");
    const invitesCollection = "it_invite_http_" + suffix;
    const auditCollection = "it_invite_http_audit_" + suffix;
    const legacyIds = [
      crypto.randomBytes(16).toString("hex"),
      crypto.randomBytes(16).toString("hex"),
    ];
    const app = initializeApp({ projectId }, "invite-http-it-" + suffix);
    const db = getFirestore(app);
    const clock = { now: Date.now() };
    const state = { authorized: true, allowValidation: true, failAudit: false };
    const adminToken = "invite-http-test-admin-token";

    const inviteStore = createInviteStore({
      firestore: db,
      FieldValue,
      Timestamp,
      now: () => clock.now,
      invitesCollection,
      tagUserId: (uid) => crypto.createHash("sha256").update(uid, "utf8").digest("hex").slice(0, 12),
      stageAudit(tx, action, request, extra) {
        if (state.failAudit && action === "invite_created") {
          throw new Error("simulated audit staging failure");
        }
        const ref = db.collection(auditCollection).doc();
        tx.create(ref, {
          action,
          requestId: request && request.headers && request.headers["x-request-id"] || "missing",
          at: FieldValue.serverTimestamp(),
          ...extra,
        });
      },
    });

    const readBody = async (req, res) => {
      const chunks = [];
      let bytes = 0;
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > 1024 * 1024) {
          res.writeHead(413, { "Content-Type": "text/plain" });
          res.end("Request body too large");
          throw new Error("body_too_large");
        }
        chunks.push(chunk);
      }
      return Buffer.concat(chunks).toString("utf8");
    };

    const requireAdminAuth = async (req, res) => {
      if (state.authorized && req.headers["x-admin-token"] === adminToken) return true;
      res.writeHead(401, { "Content-Type": "text/plain" });
      res.end("Unauthorized");
      return false;
    };

    const requireAdminAuthThenBody = (req, res, onBody) => {
      void (async () => {
        if (!(await requireAdminAuth(req, res))) {
          req.resume();
          return;
        }
        try {
          const body = await readBody(req, res);
          if (!res.headersSent) await onBody(body);
        } catch {
          if (!res.headersSent) {
            res.writeHead(400, { "Content-Type": "text/plain" });
            res.end("Invalid request body");
          }
        }
      })();
    };

    const handleInviteRoute = createInviteRouteHandler({
      inviteStore,
      invite,
      db,
      requireAdminAuth,
      requireAdminAuthThenBody,
      checkWaitlistPollRateLimit: () => state.allowValidation,
      getClientIp: () => "127.0.0.1",
      readBody,
      sendServerError(res, route, error) {
        // Do not return or log backend detail. The request ID is useful for
        // correlating the separately persisted audit record without exposing
        // credentials or Firestore error text to the caller.
        if (res.headersSent) return;
        res.writeHead(500, { "Content-Type": "text/plain", "Cache-Control": "no-store" });
        res.end("Server error");
      },
      logger: { error() {} },
    });

    const server = http.createServer((req, res) => {
      if (handleInviteRoute(req, res)) return;
      res.writeHead(404);
      res.end("Not found");
    });

    const cleanupCollection = async (name) => {
      const snapshot = await db.collection(name).get();
      await Promise.all(snapshot.docs.map((doc) => doc.ref.delete()));
    };

    t.after(async () => {
      await new Promise((resolve) => {
        server.close(() => resolve());
        if (typeof server.closeAllConnections === "function") server.closeAllConnections();
      });
      try {
        await cleanupCollection(invitesCollection);
        await cleanupCollection(auditCollection);
        await Promise.all(legacyIds.map((id) => db.collection("waitlist").doc(id).delete()));
      } finally {
        await deleteApp(app);
      }
    });

    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", resolve);
    });
    const address = server.address();
    const baseUrl = "http://127.0.0.1:" + address.port;

    async function request(path, options = {}) {
      const headers = {
        ...(options.json === undefined ? {} : { "content-type": "application/json" }),
        ...(options.admin ? { "x-admin-token": adminToken } : {}),
        ...(options.requestId ? { "x-request-id": options.requestId } : {}),
      };
      const response = await fetch(baseUrl + path, {
        method: options.method || "GET",
        headers,
        body: options.json === undefined ? undefined :
          (typeof options.json === "string" ? options.json : JSON.stringify(options.json)),
      });
      return {
        status: response.status,
        headers: response.headers,
        text: await response.text(),
      };
    }

    // Admin listing is protected at the HTTP layer, not merely in the store.
    const deniedList = await request("/admin/api/invites");
    assert.equal(deniedList.status, 401);
    assert.equal((await db.collection(invitesCollection).count().get()).data().count, 0);

    // Creation returns the raw one-time bearer token to the operator but
    // Firestore contains only the hash and short fingerprint.
    const expiresAt = new Date(clock.now + 60 * 60 * 1000).toISOString();
    const createdResponse = await request("/admin/api/invites/create", {
      method: "POST",
      admin: true,
      requestId: "invite-http-create-" + suffix,
      json: { label: "HTTP lifecycle", notes: "Firestore-backed route test", expiresAt },
    });
    assert.equal(createdResponse.status, 201);
    assert.equal(createdResponse.headers.get("cache-control"), "no-store");
    const created = JSON.parse(createdResponse.text);
    assert.match(created.id, /^[A-Za-z0-9_-]{10,40}$/);
    assert.match(created.token, /^dsi_[0-9a-f]{64}$/);

    const stored = await db.collection(invitesCollection).doc(created.id).get();
    assert.equal(stored.exists, true);
    assert.equal(stored.data().tokenHash, invite.hashInviteToken(created.token));
    assert.equal(stored.data().tokenFingerprint, invite.tokenFingerprint(created.token));
    assert.equal(JSON.stringify(stored.data()).includes(created.token), false,
      "raw token must never be persisted in the invite document");

    // Validation is served through HTTP and consults the real Firestore store.
    const valid = await request("/invite/validate", {
      method: "POST", json: { inviteToken: created.token },
    });
    assert.equal(valid.status, 200);
    assert.equal(valid.headers.get("cache-control"), "no-store");
    assert.deepEqual(JSON.parse(valid.text), { valid: true });

    // Listing applies the HTTP filters, returns the Firestore-backed record,
    // and exposes only the documented fingerprint—not tokenHash or raw token.
    const listed = await request("/admin/api/invites?status=active&search=HTTP&pageSize=10", { admin: true });
    assert.equal(listed.status, 200);
    const page = JSON.parse(listed.text);
    assert.equal(page.totalCount, 1);
    assert.equal(page.items.length, 1);
    assert.equal(page.items[0].id, created.id);
    assert.equal(page.items[0].fingerprint, invite.tokenFingerprint(created.token));
    assert.equal(JSON.stringify(page).includes(created.token), false);
    assert.equal(JSON.stringify(page).includes(invite.hashInviteToken(created.token)), false);

    // A failure to stage the audit record must abort the real transaction;
    // there must be no unaudited invite document left behind.
    state.failAudit = true;
    const failedCreate = await request("/admin/api/invites/create", {
      method: "POST", admin: true,
      json: { label: "Must rollback", expiresAt: new Date(clock.now + 60 * 60 * 1000).toISOString() },
    });
    state.failAudit = false;
    assert.equal(failedCreate.status, 500);
    assert.equal(failedCreate.text, "Server error");
    assert.equal((await db.collection(invitesCollection).count().get()).data().count, 1,
      "failed audit must roll back invite creation");

    // Revocation is performed through the same HTTP handler and committed in
    // one transaction with its audit record. A second revoke must conflict.
    const revoked = await request("/admin/api/invites/revoke", {
      method: "POST", admin: true, json: { inviteId: created.id, reason: "owner requested" },
    });
    assert.equal(revoked.status, 200);
    assert.deepEqual(JSON.parse(revoked.text), { ok: true });

    const invalidAfterRevoke = await request("/invite/validate", {
      method: "POST", json: { inviteToken: created.token },
    });
    assert.equal(invalidAfterRevoke.status, 200);
    assert.deepEqual(JSON.parse(invalidAfterRevoke.text), { valid: false });

    const duplicateRevoke = await request("/admin/api/invites/revoke", {
      method: "POST", admin: true, json: { inviteId: created.id, reason: "duplicate" },
    });
    assert.equal(duplicateRevoke.status, 409);
    assert.match(duplicateRevoke.text, /revoked/);

    // Legacy compatibility is intentionally read-only at validation time and
    // only accepts an approved 32-hex waitlist ID.
    await db.collection("waitlist").doc(legacyIds[0]).set({ status: "approved" });
    await db.collection("waitlist").doc(legacyIds[1]).set({ status: "pending" });
    const approvedLegacy = await request("/invite/validate", {
      method: "POST", json: { inviteToken: legacyIds[0] },
    });
    const pendingLegacy = await request("/invite/validate", {
      method: "POST", json: { inviteToken: legacyIds[1] },
    });
    assert.deepEqual(JSON.parse(approvedLegacy.text), { valid: true });
    assert.deepEqual(JSON.parse(pendingLegacy.text), { valid: false });

    // Rate limiting retains the same generic response and does not reveal
    // whether a modern or legacy identifier exists.
    state.allowValidation = false;
    const limited = await request("/invite/validate", {
      method: "POST", json: { inviteToken: created.token },
    });
    assert.equal(limited.status, 429);
    assert.deepEqual(JSON.parse(limited.text), { valid: false });

    // Every committed lifecycle transition leaves exactly one durable audit
    // event; rolled-back creation leaves none.
    const audits = await db.collection(auditCollection).orderBy("at", "asc").get();
    assert.deepEqual(
      audits.docs.map((doc) => doc.data().action).sort(),
      ["invite_created", "invite_revoked"]
    );
    assert.equal(audits.docs.some((doc) => doc.data().requestId === "invite-http-create-" + suffix), true);
    assert.equal(JSON.stringify(audits.docs.map((doc) => doc.data())).includes(created.token), false);
  });
}
