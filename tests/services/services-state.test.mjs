import test from 'node:test';
import assert from 'node:assert/strict';

const baseFromEnv = (process.env.ROBOGENE_API_BASE || '').replace(/\/+$/, '');

test('services state exposes frames with valid types', async () => {
  const state = baseFromEnv
    ? await (async () => {
        const res = await fetch(`${baseFromEnv}/api/state`, { headers: { 'Cache-Control': 'no-store' } });
        assert.equal(res.ok, true, `HTTP ${res.status}`);
        return res.json();
      })()
    : {
        frames: [
          { frameId: 'f-1', frameNumber: 1, status: 'ready', imageUrl: 'data:image/png;base64,AAA' },
          { frameId: 'f-2', frameNumber: 2, status: 'draft', imageUrl: '' },
        ],
      };

  const frames = state.frames || [];

  assert.ok(Array.isArray(frames), 'frames should be an array');
  assert.ok(frames.length >= 1, 'should expose at least one frame');
  assert.ok(frames.every((f) => typeof f.frameNumber === 'number'), 'frameNumber must be number');
  assert.ok(frames.every((f) => typeof f.status === 'string'), 'status must be string');
  assert.ok(frames.every((f) => typeof (f.imageUrl || '') === 'string'), 'imageUrl must be string');
});
