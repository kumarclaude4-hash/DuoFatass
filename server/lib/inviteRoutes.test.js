"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { createInviteRouteHandler } = require("./inviteRoutes");
const invite = require("./invite");

const ADMIN_TOKEN = "test-admin-token-with-sufficient-entropy";
const ACTIVE_TOKEN = "dsi_" + "a".repeat(64);
const LEGACY_ID = "0123456789abcdef0123456789abcdef";

function createFixture(t, overrides = {}) {
  const calls = {
    auth: 0,
    bodyReads: 0,
    list: [],
    create: [],
    revoke: [],
    isActive: [],
    legacyReads: [],
  };
  const state = {
    authorized: true,
    allowValidation: true,
    legacyApproved: true,
    revokeOutcome: { status: "revoked" },
    ...overrides,
  };

  const inviteStore = {
    async list(options) {
      calls.list.push(options);
      if (state.listError) throw state.listError;
      return state.listResult || { items: [{ id: "invite_1234567890", status: "active" }], nextCursor: null, totalCount: 1 };
    },
    async create(input, request) {
      calls.create.push({ input, method: request.method, url: request.url });
      if (state.createError) throw state.createError;
      return {
        id: "invite_1234567890",
        token: ACTIVE_TOKEN,
        status: "active",
        expiresAt: input.expiresAt || "2030-01-01T00:00:00.000Z",
      };
    },
    async revoke(id, reason, request) {
      calls.revoke.push({ id, reason, method: request.method });
      if (state.revokeError) throw state.revokeError;
      return state.revokeOutcome;
    },
    async isActive(token) {
      calls.isActive.push(token);
      if (state.activeError) throw state.activeError;
      return token === ACTIVE_TOKEN;
    },
  };

  const db = {
    collection(name) {
      assert.equal(name, "waitlist");
      return {
        doc(id) {
          return {
            async get() {
              calls.legacyReads.push(id);
              return {
                exists: state.legacyApproved,
                data: () => ({ status: state.legacyApproved ? "approved" : "pending" }),
              };
            },
          };
        },
      };
    },
  };

  const readBody = async (req, res) => {
    calls.bodyReads++;
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
    calls.auth++;
    if (state.authorized && req.headers["x-admin-token"] === ADMIN_TOKEN) return true;
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
      } catch (error) {
        if (!res.headersSent) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid request body");
        }
      }
    })();
  };

  const handle = createInviteRouteHandler({
    inviteStore,
    invite,
    db,
    requireAdminAuth,
    requireAdminAuthThenBody,
    checkWaitlistPollRateLimit: () => state.allowValidation,
    getClientIp: () => "127.0.0.1",
    readBody,
    sendServerError(res, tag, error) {
      calls.serverError = { tag, message: error.message };
      if (res.headersSent) return;
      res.writeHead(500, { "Content-Type": "text/plain" });
      res.end("Server error");
    },
    logger: { error() {} },
  });

  const server = http.createServer((req, res) => {
    if (handle(req, res)) return;
    res.writeHead(404);
    res.end("Not found");
  });
  t.after(() => new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
  }));

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      resolve({
        url: "http://127.0.0.1:" + address.port,
        calls,
        state,
        request: async (path, options = {}) => {
          const response = await fetch("http://127.0.0.1:" + address.port + path, {
            method: options.method || "GET",
            headers: {
              ...(options.json === undefined ? {} : { "content-type": "application/json" }),
              ...(options.admin ? { "x-admin-token": ADMIN_TOKEN } : {}),
              ...(options.headers || {}),
            },
            body: options.json === undefined ? undefined :
              (typeof options.json === "string" ? options.json : JSON.stringify(options.json)),
          });
          return {
            status: response.status,
            headers: response.headers,
            text: await response.text(),
          };
        },
      });
    });
  });
}

test("admin invite list is authenticated and passes filters and cursor to the store", async (t) => {
  const fixture = await createFixture(t);
  const denied = await fixture.request("/admin/api/invites?status=active");
  assert.equal(denied.status, 401);
  assert.equal(fixture.calls.list.length, 0);

  const cursor = Buffer.from(JSON.stringify([1_800_000_000_000, "invite_1234567890"])).toString("base64url");
  const accepted = await fixture.request(
    "/admin/api/invites?status=active&search=Alice&pageSize=10&cursor=" + encodeURIComponent(cursor),
    { admin: true }
  );
  assert.equal(accepted.status, 200);
  assert.deepEqual(fixture.calls.list[0], {
    status: "active",
    search: "Alice",
    pageSize: "10",
    cursor,
  });
  assert.equal(JSON.parse(accepted.text).totalCount, 1);

  const invalidStateFilter = await fixture.request(
    "/admin/api/invites?status=invalid",
    { admin: true }
  );
  assert.equal(invalidStateFilter.status, 200);
  assert.equal(fixture.calls.list[1].status, "invalid");
});

test("invite list API returns pseudonymous redemption tags and never raw UIDs", async (t) => {
  const fixture = await createFixture(t);
  const rawUid = "seed-derived-private-account-uid";
  fixture.state.listResult = {
    items: [{ id: "invite_1234567890", status: "used", usedByUserIdTag: "a1b2c3d4e5f6" }],
    nextCursor: null,
    totalCount: 1,
  };
  const response = await fixture.request("/admin/api/invites?status=used", { admin: true });
  assert.equal(response.status, 200);
  const payload = JSON.parse(response.text);
  assert.equal(payload.items[0].usedByUserIdTag, "a1b2c3d4e5f6");
  assert.equal(JSON.stringify(payload).includes(rawUid), false);
  assert.equal(Object.prototype.hasOwnProperty.call(payload.items[0], "usedByUserId"), false);
});

test("invite creation authenticates before reading the body and returns the one-time token", async (t) => {
  const fixture = await createFixture(t);
  const denied = await fixture.request("/admin/api/invites/create", {
    method: "POST",
    json: "{malformed",
  });
  assert.equal(denied.status, 401);
  assert.equal(fixture.calls.bodyReads, 0);
  assert.equal(fixture.calls.create.length, 0);

  const created = await fixture.request("/admin/api/invites/create", {
    method: "POST",
    admin: true,
    json: { label: "QA invite", expiresAt: "2030-01-01T00:00:00.000Z" },
  });
  assert.equal(created.status, 201);
  assert.equal(created.headers.get("cache-control"), "no-store");
  assert.equal(JSON.parse(created.text).token, ACTIVE_TOKEN);
  assert.equal(fixture.calls.create.length, 1);
  assert.deepEqual(fixture.calls.create[0].input, {
    label: "QA invite",
    expiresAt: "2030-01-01T00:00:00.000Z",
  });
});

test("invite creation maps malformed JSON and store validation errors to 400", async (t) => {
  const fixture = await createFixture(t);
  const malformed = await fixture.request("/admin/api/invites/create", {
    method: "POST",
    admin: true,
    json: "{malformed",
  });
  assert.equal(malformed.status, 400);
  assert.match(malformed.text, /Invalid JSON/);
  assert.equal(fixture.calls.create.length, 0);

  fixture.state.createError = new TypeError("Invalid expiry");
  const invalid = await fixture.request("/admin/api/invites/create", {
    method: "POST",
    admin: true,
    json: { label: "Bad expiry" },
  });
  assert.equal(invalid.status, 400);
  assert.match(invalid.text, /Invalid expiry/);
});

test("invite revocation maps missing, conflict and success without bypassing the store", async (t) => {
  const fixture = await createFixture(t);

  fixture.state.revokeOutcome = { status: "missing" };
  const missing = await fixture.request("/admin/api/invites/revoke", {
    method: "POST", admin: true, json: { inviteId: "invite_1234567890", reason: "test" },
  });
  assert.equal(missing.status, 404);

  fixture.state.revokeOutcome = { status: "conflict", current: "used" };
  const conflict = await fixture.request("/admin/api/invites/revoke", {
    method: "POST", admin: true, json: { inviteId: "invite_1234567890", reason: "test" },
  });
  assert.equal(conflict.status, 409);
  assert.match(conflict.text, /used/);

  fixture.state.revokeOutcome = { status: "revoked" };
  const revoked = await fixture.request("/admin/api/invites/revoke", {
    method: "POST", admin: true, json: { inviteId: "invite_1234567890", reason: "test" },
  });
  assert.equal(revoked.status, 200);
  assert.deepEqual(JSON.parse(revoked.text), { ok: true });
  assert.equal(fixture.calls.revoke.length, 3);
});

test("invite validation returns the same cache-disabled shape for malformed, invalid and valid inputs", async (t) => {
  const fixture = await createFixture(t);

  const malformed = await fixture.request("/invite/validate", {
    method: "POST", json: "{malformed",
  });
  const unknown = await fixture.request("/invite/validate", {
    method: "POST", json: { inviteToken: "not-an-invite" },
  });
  const active = await fixture.request("/invite/validate", {
    method: "POST", json: { inviteToken: ACTIVE_TOKEN },
  });

  assert.equal(malformed.status, 200);
  assert.equal(unknown.status, 200);
  assert.equal(active.status, 200);
  assert.deepEqual(JSON.parse(malformed.text), { valid: false });
  assert.deepEqual(JSON.parse(unknown.text), { valid: false });
  assert.deepEqual(JSON.parse(active.text), { valid: true });
  assert.equal(malformed.headers.get("cache-control"), "no-store");
  assert.equal(unknown.headers.get("cache-control"), "no-store");
  assert.equal(active.headers.get("cache-control"), "no-store");
  assert.deepEqual(fixture.calls.isActive, [ACTIVE_TOKEN]);
});

test("invite validation supports only approved legacy waitlist IDs and keeps denials generic", async (t) => {
  const fixture = await createFixture(t);
  const approved = await fixture.request("/invite/validate", {
    method: "POST", json: { inviteToken: LEGACY_ID },
  });
  assert.equal(approved.status, 200);
  assert.deepEqual(JSON.parse(approved.text), { valid: true });
  assert.deepEqual(fixture.calls.legacyReads, [LEGACY_ID]);

  fixture.state.legacyApproved = false;
  const pending = await fixture.request("/invite/validate", {
    method: "POST", json: { inviteToken: LEGACY_ID },
  });
  assert.equal(pending.status, 200);
  assert.deepEqual(JSON.parse(pending.text), { valid: false });
});

test("invite validation rate limit is generic and avoids reading the body", async (t) => {
  const fixture = await createFixture(t, { allowValidation: false });
  const response = await fixture.request("/invite/validate", {
    method: "POST", json: { inviteToken: ACTIVE_TOKEN },
  });
  assert.equal(response.status, 429);
  assert.deepEqual(JSON.parse(response.text), { valid: false });
  assert.equal(fixture.calls.bodyReads, 0);
  assert.equal(fixture.calls.isActive.length, 0);
});

test("invite list returns 400 for invalid filters and 500 for store faults", async (t) => {
  const fixture = await createFixture(t);
  fixture.state.listError = new TypeError("Invalid filter");
  const invalid = await fixture.request("/admin/api/invites?status=unknown", { admin: true });
  assert.equal(invalid.status, 400);
  assert.match(invalid.text, /Invalid filter/);

  fixture.state.listError = new Error("Firestore unavailable");
  const failed = await fixture.request("/admin/api/invites", { admin: true });
  assert.equal(failed.status, 500);
  assert.equal(failed.text, "Server error");
  assert.equal(fixture.calls.serverError.tag, "admin/api/invites");
});

test("invite validation fails closed on store/database errors with a generic 500 response", async (t) => {
  const fixture = await createFixture(t);
  fixture.state.activeError = new Error("sensitive backend detail");
  const response = await fixture.request("/invite/validate", {
    method: "POST", json: { inviteToken: ACTIVE_TOKEN },
  });
  assert.equal(response.status, 500);
  assert.deepEqual(JSON.parse(response.text), { valid: false });
  assert.equal(response.text.includes("sensitive backend detail"), false);
});
