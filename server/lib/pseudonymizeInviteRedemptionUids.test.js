"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  TAG_PATTERN,
  createRunTagger,
  buildLegacyInviteUpdate,
  runMigration,
} = require("./pseudonymizeInviteRedemptionUids");

test("migration replaces legacy raw UID with a short non-reversible display tag", () => {
  const tagUserId = createRunTagger(Buffer.alloc(32, 7));
  const uid = "seed-derived-account-uid";
  const update = buildLegacyInviteUpdate(
    { usedByUserId: uid },
    tagUserId,
    "__DELETE__"
  );
  assert.deepEqual(update, {
    usedByUserId: "__DELETE__",
    usedByUserIdTag: tagUserId(uid),
  });
  assert.match(update.usedByUserIdTag, TAG_PATTERN);
  assert.equal(JSON.stringify(update).includes(uid), false);
});

test("migration preserves a valid existing tag while deleting the legacy raw field", () => {
  const update = buildLegacyInviteUpdate(
    { usedByUserId: "legacy-account-uid", usedByUserIdTag: "a1b2c3d4e5f6" },
    () => { throw new Error("existing tags must not be recomputed"); },
    "__DELETE__"
  );
  assert.deepEqual(update, {
    usedByUserId: "__DELETE__",
    usedByUserIdTag: "a1b2c3d4e5f6",
  });
});

test("migration removes malformed legacy values without inventing an identity tag", () => {
  const update = buildLegacyInviteUpdate(
    { usedByUserId: { unexpected: true }, usedByUserIdTag: "invalid" },
    () => { throw new Error("non-string legacy value must not be pseudonymized"); },
    "__DELETE__"
  );
  assert.deepEqual(update, { usedByUserId: "__DELETE__" });
});

test("migration leaves documents without the legacy field untouched", () => {
  assert.equal(buildLegacyInviteUpdate({ status: "used" }, () => "a".repeat(12), "__DELETE__"), null);
});

test("migration is dry-run by default and applies bounded batches only when requested", async () => {
  const docs = [
    { id: "a", data: () => ({ usedByUserId: "uid-a" }), ref: "ref-a" },
    { id: "b", data: () => ({ status: "active" }), ref: "ref-b" },
  ];
  const calls = { batchCreated: 0, writes: [], commits: 0 };
  const snapshot = { docs, size: docs.length, empty: false };
  const db = {
    collection(name) {
      assert.equal(name, "invites");
      return {
        orderBy() {
          return { limit: async () => snapshot };
        },
      };
    },
    batch() {
      calls.batchCreated++;
      return {
        update(ref, value) { calls.writes.push({ ref, value }); },
        async commit() { calls.commits++; },
      };
    },
  };
  const dry = await runMigration({ db, deleteField: "__DELETE__", tagUserId: () => "a".repeat(12) });
  assert.deepEqual(dry, { scanned: 2, candidates: 1, updated: 0, dryRun: true });
  assert.equal(calls.batchCreated, 0);
  const applied = await runMigration({ apply: true, db, deleteField: "__DELETE__", tagUserId: () => "a".repeat(12) });
  assert.deepEqual(applied, { scanned: 2, candidates: 1, updated: 1, dryRun: false });
  assert.equal(calls.batchCreated, 1);
  assert.equal(calls.commits, 1);
  assert.deepEqual(calls.writes, [{
    ref: "ref-a",
    value: { usedByUserId: "__DELETE__", usedByUserIdTag: "a".repeat(12) },
  }]);
});
