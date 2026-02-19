import test from 'node:test';
import assert from 'node:assert/strict';

function normalizeFrames(state) {
  return (state.frames || [])
    .map((f) => ({ ...f, frameNumber: f.frameNumber }))
    .sort((a, b) => b.frameNumber - a.frameNumber);
}

test('frame state normalizes and sorts by frame number desc', () => {
  const state = {
    frames: [
      { frameId: 'a', frameNumber: 1, status: 'ready', imageDataUrl: 'data:image/png;base64,aaa' },
      { frameId: 'b', frameNumber: 3, status: 'draft' },
      { frameId: 'c', frameNumber: 2, status: 'queued' },
    ],
  };

  const frames = normalizeFrames(state);
  assert.equal(frames.length, 3);
  assert.deepEqual(frames.map((f) => f.frameNumber), [3, 2, 1]);
  assert.ok(frames.every((f) => typeof f.frameNumber === 'number'));
  assert.ok(frames.every((f) => typeof f.status === 'string'));
});
