import test from 'node:test';
import assert from 'node:assert/strict';
import { createCoalescedRunner } from '../src/robogene/frontend/events/fetch_coalescer.js';

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test('coalesced runner collapses burst calls to one inflight + one trailing call', async () => {
  let calls = 0;

  const run = createCoalescedRunner(async () => {
    calls += 1;
    await wait(25);
  });

  // Burst while first call is in-flight.
  void run();
  void run();
  void run();
  void run();

  await wait(80);

  // One immediate + one queued follow-up max.
  assert.equal(calls, 2);
});
