"use strict";

const crypto = require("crypto");
const dns = require("dns").promises;
const net = require("net");

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.name = "HttpError";
    this.status = status;
  }
}

function requestPath(rawUrl) {
  try {
    return new URL(rawUrl || "/", "http://localhost").pathname;
  } catch {
    return "/";
  }
}

function safeTokenEqual(a, b) {
  const digestA = crypto.createHash("sha256").update(String(a)).digest();
  const digestB = crypto.createHash("sha256").update(String(b)).digest();
  return crypto.timingSafeEqual(digestA, digestB) && String(a).length === String(b).length;
}

function getClientIp(req, trustProxy = false) {
  if (trustProxy) {
    const forwarded = String(req.headers["x-forwarded-for"] || "");
    const first = forwarded.split(",")[0].trim();
    if (first && net.isIP(first)) return first;
  }
  return req.socket.remoteAddress || "unknown";
}

function fingerprint(value, secret = "") {
  return crypto.createHmac("sha256", secret || "duoshield-audit")
    .update(String(value))
    .digest("hex")
    .slice(0, 16);
}

function isBlockedIp(address) {
  if (!address) return true;
  const normalized = address.toLowerCase().replace(/^::ffff:/, "");
  if (net.isIPv4(normalized)) {
    const octets = normalized.split(".").map(Number);
    return octets[0] === 0 || octets[0] === 10 || octets[0] === 127 ||
      (octets[0] === 169 && octets[1] === 254) ||
      (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
      (octets[0] === 192 && octets[1] === 168) ||
      (octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127) ||
      octets[0] >= 224;
  }
  if (net.isIPv6(normalized)) {
    return normalized === "::" || normalized === "::1" ||
      normalized.startsWith("fc") || normalized.startsWith("fd") ||
      /^fe[89ab]/.test(normalized) || normalized.startsWith("ff");
  }
  return true;
}

function isBlockedHostname(hostname) {
  const host = String(hostname).toLowerCase().replace(/^\[|\]$/g, "");
  return host === "localhost" || host === "metadata.google.internal" ||
    host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".local") ||
    (net.isIP(host) ? isBlockedIp(host) : false);
}

async function assertPublicUrl(rawUrl, lookup = dns.lookup) {
  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    throw new HttpError(400, "Invalid URL");
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) {
    throw new HttpError(400, "Invalid URL");
  }
  if (isBlockedHostname(parsed.hostname)) throw new HttpError(403, "Forbidden address");
  const records = await lookup(parsed.hostname, { all: true, verbatim: true });
  if (!records.length || records.some((record) => isBlockedIp(record.address))) {
    throw new HttpError(403, "Forbidden address");
  }
  return parsed;
}

function readBody(req, { maxBytes = 64 * 1024, contentTypes = null } = {}) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers["content-length"] || 0);
    if (!Number.isFinite(declared) || declared < 0 || declared > maxBytes) {
      reject(new HttpError(413, "Request body too large"));
      return;
    }
    if (contentTypes) {
      const type = String(req.headers["content-type"] || "").split(";", 1)[0].trim().toLowerCase();
      if (!contentTypes.includes(type)) {
        reject(new HttpError(415, "Unsupported content type"));
        return;
      }
    }

    const chunks = [];
    let bytes = 0;
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    };
    req.on("aborted", () => fail(new HttpError(400, "Request aborted")));
    req.on("error", fail);
    req.on("data", (chunk) => {
      if (settled) return;
      bytes += chunk.length;
      if (bytes > maxBytes) {
        fail(new HttpError(413, "Request body too large"));
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      if (settled) return;
      settled = true;
      resolve(Buffer.concat(chunks, bytes).toString("utf8"));
    });
  });
}

async function readJson(req, options) {
  const raw = await readBody(req, {
    ...options,
    contentTypes: ["application/json"],
  });
  try {
    const value = JSON.parse(raw || "{}");
    if (!value || Array.isArray(value) || typeof value !== "object") throw new Error("not an object");
    return value;
  } catch {
    throw new HttpError(400, "Invalid JSON");
  }
}

function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return false;
  try {
    const expectedProto = String(req.headers["x-forwarded-proto"] || (req.socket.encrypted ? "https" : "http"))
      .split(",")[0].trim();
    return new URL(origin).origin === `${expectedProto}://${req.headers.host}`;
  } catch {
    return false;
  }
}

function trimMap(map, maxEntries) {
  while (map.size > maxEntries) map.delete(map.keys().next().value);
}

function timestampToIso(value) {
  if (!value) return null;
  const date = typeof value.toDate === "function" ? value.toDate() : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

module.exports = {
  HttpError,
  assertPublicUrl,
  fingerprint,
  getClientIp,
  isBlockedHostname,
  isBlockedIp,
  readBody,
  readJson,
  requestPath,
  safeTokenEqual,
  sameOrigin,
  timestampToIso,
  trimMap,
};
