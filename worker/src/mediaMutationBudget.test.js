import test from 'node:test';
import assert from 'node:assert/strict';
import { MediaMutationBudget } from './mediaMutationBudget.js';

function makeStorage() {
  const data = new Map();
  return {
    data,
    async get(key) { return data.get(key); },
    async put(key, value) { data.set(key, value); },
  };
}

function makeBudget({ now = 1_800_000_000_000, limit = 3 } = {}) {
  const storage = makeStorage();
  const instance = new MediaMutationBudget({ storage }, {}, { now: () => now });
  return { storage, instance, limit };
}

function reserve(limit = 3, operation = 'upload', holderKey = '0123456789abcdef', holderLimit = limit) {
  return new Request('https://media-quota.internal/reserve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'reserve', operation, limit, holderKey, holderLimit }),
  });
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

const bodyOf = (response) => response.json();

test('reserves up to the configured daily mutation limit, then rejects without incrementing', async () => {
  const { instance } = makeBudget({ limit: 2 });
  const first = await bodyOf(await instance.fetch(reserve(2)));
  const second = await bodyOf(await instance.fetch(reserve(2)));
  const thirdResponse = await instance.fetch(reserve(2));
  const third = await bodyOf(thirdResponse);
  assert.equal(first.allowed, true);
  assert.equal(first.count, 1);
  assert.equal(second.allowed, true);
  assert.equal(second.remaining, 0);
  assert.equal(thirdResponse.status, 429);
  assert.equal(third.allowed, false);
  assert.equal(third.count, 2);
});

test('concurrent reservations serialize and cannot exceed the global limit', async () => {
  const { instance } = makeBudget({ limit: 25 });
  const stub = serializeFetch(instance);
  const results = await Promise.all(
    Array.from({ length: 100 }, () => stub.fetch(reserve(25)).then(async (response) => ({ status: response.status, ...(await response.json()) }))),
  );
  assert.equal(results.filter((result) => result.allowed).length, 25);
  assert.equal(results.filter((result) => result.status === 429).length, 75);
  assert.equal(Math.max(...results.map((result) => result.count)), 25);
});

test('a new UTC day starts a fresh reservation window', async () => {
  let now = Date.UTC(2026, 10, 7, 23, 59, 59);
  const storage = makeStorage();
  const instance = new MediaMutationBudget({ storage }, {}, { now: () => now });
  await instance.fetch(reserve(1));
  assert.equal((await instance.fetch(reserve(1))).status, 429);
  now += 2_000;
  const nextDay = await instance.fetch(reserve(1, 'upload'));
  assert.equal(nextDay.status, 200);
  assert.equal((await bodyOf(nextDay)).count, 1);
});

test('rejects malformed requests and unsupported methods', async () => {
  const { instance } = makeBudget();
  assert.equal((await instance.fetch(new Request('https://media-quota.internal/', { method: 'GET' }))).status, 405);
  assert.equal((await instance.fetch(new Request('https://media-quota.internal/', { method: 'POST', body: '{' }))).status, 400);
  assert.equal((await instance.fetch(reserve(0))).status, 400);
  assert.equal((await instance.fetch(reserve(1, 'purge'))).status, 400);
});


test('upload and deletion budgets are independent so uploads cannot exhaust cleanup capacity', async () => {
  const { instance, storage } = makeBudget({ limit: 2 });
  await instance.fetch(reserve(2, 'upload'));
  await instance.fetch(reserve(2, 'upload'));
  assert.equal((await instance.fetch(reserve(2, 'upload'))).status, 429);
  const deleteResponse = await instance.fetch(reserve(2, 'delete'));
  assert.equal(deleteResponse.status, 200);
  assert.equal((await bodyOf(deleteResponse)).count, 1);
  assert.deepEqual(storage.data.get('daily-media-mutations'), {
    dayKey: new Date(1_800_000_000_000).toISOString().slice(0, 10),
    uploads: 2,
    deletes: 1,
    holders: {
      'upload:0123456789abcdef': 2,
      'delete:0123456789abcdef': 1,
    },
  });
});


test('per-holder daily cap prevents one account from consuming the shared global allowance', async () => {
  const { instance } = makeBudget({ limit: 10 });
  const holderA = 'aaaaaaaaaaaaaaaa';
  const holderB = 'bbbbbbbbbbbbbbbb';
  assert.equal((await instance.fetch(reserve(10, 'upload', holderA, 2))).status, 200);
  assert.equal((await instance.fetch(reserve(10, 'upload', holderA, 2))).status, 200);
  const denied = await instance.fetch(reserve(10, 'upload', holderA, 2));
  assert.equal(denied.status, 429);
  const deniedBody = await bodyOf(denied);
  assert.equal(deniedBody.scope, 'holder');
  assert.equal(deniedBody.count, 2);
  const otherHolder = await instance.fetch(reserve(10, 'upload', holderB, 2));
  assert.equal(otherHolder.status, 200);
  assert.equal((await bodyOf(otherHolder)).count, 3,
    'another account can still use the shared allowance after holder A reaches its sub-limit');
});
