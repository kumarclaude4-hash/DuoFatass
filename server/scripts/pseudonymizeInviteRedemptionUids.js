"use strict";

// One-time privacy migration for legacy invite documents that stored the
// seed-derived account UID in usedByUserId. Default mode is read-only; pass
// --apply explicitly to replace the raw value with a pseudonymous tag and
// delete the raw field. The generated pepper exists only in this process.
// Tags are display pseudonyms, not authentication identifiers.

const crypto = require("node:crypto");
const admin = require("firebase-admin");

const COLLECTION = "invites";
const PAGE_SIZE = 200;
const TAG_PATTERN = /^[0-9a-f]{12}$/;

function normalizeServiceAccountPrivateKey(serviceAccount) {
  if (!serviceAccount || typeof serviceAccount !== "object" || Array.isArray(serviceAccount)) {
    throw new TypeError("Service-account credentials must be a JSON object");
  }

  const normalized = { ...serviceAccount };
  if (typeof normalized.private_key === "string") {
    normalized.private_key = normalized.private_key.replace(/\\n/g, "\n");
  }
  return normalized;
}

function createRunTagger(pepper = crypto.randomBytes(32)) {
  return (uid) => crypto.createHmac("sha256", pepper)
    .update(String(uid), "utf8")
    .digest("hex")
    .slice(0, 12);
}

function buildLegacyInviteUpdate(data, tagUserId, deleteField) {
  if (!data || typeof data !== "object" ||
      !Object.prototype.hasOwnProperty.call(data, "usedByUserId")) {
    return null;
  }

  const update = { usedByUserId: deleteField };
  const existingTag = data.usedByUserIdTag;
  if (typeof existingTag === "string" && TAG_PATTERN.test(existingTag)) {
    update.usedByUserIdTag = existingTag;
  } else if (typeof data.usedByUserId === "string" && data.usedByUserId.length > 0) {
    update.usedByUserIdTag = tagUserId(data.usedByUserId);
  }

  return update;
}

async function runMigration({ apply = false, db, deleteField, tagUserId = createRunTagger() }) {
  if (!db || typeof db.collection !== "function" ||
      !deleteField || typeof tagUserId !== "function") {
    throw new TypeError("Migration dependencies are invalid");
  }

  let scanned = 0;
  let candidates = 0;
  let updated = 0;
  let query = db.collection(COLLECTION)
    .orderBy(admin.firestore.FieldPath.documentId())
    .limit(PAGE_SIZE);

  while (true) {
    const snapshot = await query.get();
    if (snapshot.empty) break;

    scanned += snapshot.size;
    const batch = apply ? db.batch() : null;
    let pageUpdates = 0;

    for (const document of snapshot.docs) {
      const update = buildLegacyInviteUpdate(document.data(), tagUserId, deleteField);
      if (!update) continue;
      candidates++;
      if (batch) {
        batch.update(document.ref, update);
        pageUpdates++;
      }
    }

    if (batch && pageUpdates > 0) {
      await batch.commit();
      updated += pageUpdates;
    }

    if (snapshot.size < PAGE_SIZE) break;
    query = db.collection(COLLECTION)
      .orderBy(admin.firestore.FieldPath.documentId())
      .startAfter(snapshot.docs[snapshot.docs.length - 1].id)
      .limit(PAGE_SIZE);
  }

  return { scanned, candidates, updated, dryRun: !apply };
}

async function main() {
  const args = process.argv.slice(2);
  const apply = args.includes("--apply");
  if (args.some((arg) => arg !== "--apply" && arg !== "--dry-run") ||
      (args.includes("--apply") && args.includes("--dry-run"))) {
    throw new Error("Usage: node scripts/pseudonymizeInviteRedemptionUids.js [--dry-run|--apply]");
  }

  const rawCredentials = process.env.GOOGLE_APPLICATION_CREDENTIALS_JSON;
  let credential;
  if (rawCredentials) {
    let parsedServiceAccount;
    try {
      parsedServiceAccount = JSON.parse(rawCredentials);
    } catch {
      throw new Error("GOOGLE_APPLICATION_CREDENTIALS_JSON is not valid JSON");
    }
    const serviceAccount = normalizeServiceAccountPrivateKey(parsedServiceAccount);
    credential = admin.credential.cert(serviceAccount);
  } else {
    credential = admin.credential.applicationDefault();
  }

  const app = admin.initializeApp({ credential });
  try {
    const result = await runMigration({
      apply,
      db: admin.firestore(app),
      deleteField: admin.firestore.FieldValue.delete(),
    });
    const verb = apply ? "Updated" : "Would update";
    process.stdout.write(
      `${verb} ${result.candidates} invite records; scanned ${result.scanned}.\n`
    );
  } finally {
    await app.delete();
  }
}

if (require.main === module) {
  main().catch(() => {
    process.stderr.write("Invite UID pseudonymization failed. Check the command and authorized Firestore access.\n");
    process.exitCode = 1;
  });
}

module.exports = {
  TAG_PATTERN,
  createRunTagger,
  buildLegacyInviteUpdate,
  normalizeServiceAccountPrivateKey,
  runMigration,
};
