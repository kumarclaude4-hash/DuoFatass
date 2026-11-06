import test from 'node:test';
import assert from 'node:assert/strict';
import { MediaTokenReplayGuard } from './mediaTokenReplayGuard.js';

function makeStorage() {
  const map = new Map();
  let alarmAt = null;
  return {
    map,
    async get(key) { return map.has(key) ? map.get(key) : undefined; },
    async put(key, value) { map.set(key, value); },
    async delete(key) { map.delete(key); },
    async list({ prefix = '' } = {}) {
      return new Map([...map.entries()].filter(([key]) => key.startsWith(prefix)));
    },
    async getAlarm() { return alarmAt; },
    async setAlarm(timestamp) { alarmAt = timestamp; },
    async deleteAlarm() { alarmAt = null; },
    get alarmAt() { return alarmAt; },
  };
}

function serializeFetch(instance) {
  let chain = Promise.resolve();
  return {
    fetch(request) {
      const result = chain.then(() => instance.fetch(request));
      chain = result.then(() => undefined, () => undefined);
      return result;
    },
  };
}

function claimRequest(jti, ttlSeconds = 60) {
  return new Request('https://media-replay.internal/consume', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jti, ttlSeconds }),
  });
}

test('first claim consumes a valid jti and schedules expiry', async () => {
  let now = 10_000;
  const storage = makeStorage();
  const guard = new MediaTokenReplayGuard({ storage }, {}, { now: () => now });
  const response = await guard.fetch(claimRequest('AbCdEf012_-x', 60));

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { consumed: true, expiresAt: now + 60_000 });
  assert.equal(storage.map.get('used:AbCdEf012_-x'), now + 60_000);
  assert.equal(storage.alarmAt, now + 60_000);
});

test('a duplicate claim is rejected until the original claim expires', async () => {
  let now = 20_000;
  const storage = makeStorage();
  const guard = new MediaTokenReplayGuard({ storage }, {}, { now: () => now });

  assert.deepEqual(await (await guard.fetch(claimRequest('AbCdEf012_-x', 30))).json(),
    { consumed: true, expiresAt: now + 30_000 });
  assert.deepEqual(await (await guard.fetch(claimRequest('AbCdEf012_-x', 30))).json(),
    { consumed: false });

  now += 30_001;
  assert.deepEqual(await (await guard.fetch(claimRequest('AbCdEf012_-x', 30))).json(),
    { consumed: true, expiresAt: now + 30_000 });
});

test('concurrent duplicate claims serialize to exactly one winner', async () => {
  const storage = makeStorage();
  const guard = serializeFetch(new MediaTokenReplayGuard({ storage }, {}));
  const results = await Promise.all(
    Array.from({ length: 32 }, () =>
      guard.fetch(claimRequest('ZyXwVu987_-a', 60)).then((response) => response.json())),
  );

  assert.equal(results.filter(result => result.consumed).length, 1);
  assert.equal(results.filter(result => result.consumed === false).length, 31);
  assert.equal(storage.map.size, 1);
});

test('alarm removes expired claims and preserves/reschedules live claims', async () => {
  let now = 100_000;
  const storage = makeStorage();
  const guard = new MediaTokenReplayGuard({ storage }, {}, { now: () => now });

  await guard.fetch(claimRequest('AbCdEf012_-x', 10));
  await guard.fetch(claimRequest('ZyXwVu987_-a', 30));
  now += 11_000;
  await guard.alarm();

  assert.equal(storage.map.has('used:AbCdEf012_-x'), false);
  assert.equal(storage.map.has('used:ZyXwVu987_-a'), true);
  assert.equal(storage.alarmAt, 130_000);

  now = 131_000;
  await guard.alarm();
  assert.equal(storage.map.size, 0);
  assert.equal(storage.alarmAt, null);
});

test('malformed jti, invalid TTL, and non-POST requests are rejected without storage writes', async () => {
  const storage = makeStorage();
  const guard = new MediaTokenReplayGuard({ storage }, {});

  assert.equal((await guard.fetch(claimRequest('short', 60))).status, 400);
  assert.equal((await guard.fetch(claimRequest('AbCdEf012_-x', 0))).status, 400);
  assert.equal((await guard.fetch(claimRequest('AbCdEf012_-x', 3601))).status, 400);
  assert.equal((await guard.fetch(new Request('https://media-replay.internal/consume'))).status, 404);
  assert.equal(storage.map.size, 0);
});
