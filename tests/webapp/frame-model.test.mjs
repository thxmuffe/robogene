import test from 'node:test';
import assert from 'node:assert/strict';

function genericFrameLabel(text) {
  return /^frame\s+\d+$/i.test((text || '').trim());
}

function clampText(text, limit) {
  const v = (text || '').trim();
  return v.length > limit ? `${v.slice(0, limit)}...` : v;
}

function frameDescription(frame) {
  const description = (frame.description || '').trim();
  const preferred = description && !genericFrameLabel(description) ? description : 'No description yet.';
  return clampText(preferred, 180);
}

function normalizeFrames(state) {
  return (state.frames || []).map((f) => ({ ...f, frameDescription: frameDescription(f) }));
}

function hydrateFrameInputs(existing, frames) {
  return frames.reduce((acc, frame) => {
    const frameId = frame.frameId;
    const existingVal = existing[frameId];
    const description = (frame.description || '').trim();
    const servicesVal = (description && !genericFrameLabel(description) && description) || '';
    acc[frameId] = !(existingVal || '').trim() ? servicesVal : existingVal;
    return acc;
  }, {});
}

test('frame state normalizes without requiring frame numbers', () => {
  const state = {
    frames: [
      { frameId: 'a', status: 'ready', imageUrl: 'data:image/png;base64,aaa' },
      { frameId: 'b', status: 'draft' },
      { frameId: 'c', status: 'queued' },
    ],
  };

  const frames = normalizeFrames(state);
  assert.equal(frames.length, 3);
  assert.deepEqual(frames.map((f) => f.frameId), ['a', 'b', 'c']);
  assert.ok(frames.every((f) => typeof f.status === 'string'));
});

test('frame description uses stable non-blank fallback and stays concise', () => {
  const state = {
    frames: [
      { frameId: 'a', description: 'Frame 1', status: 'draft' },
      { frameId: 'b', description: '', status: 'draft' },
      { frameId: 'c', description: 'A'.repeat(240), status: 'draft' },
    ],
  };

  const frames = normalizeFrames(state);
  const map = Object.fromEntries(frames.map((f) => [f.frameId, f]));

  assert.equal(map.a.frameDescription, 'No description yet.');
  assert.equal(map.b.frameDescription, 'No description yet.');
  assert.ok(map.c.frameDescription.length <= 183);
});

test('frame inputs prefill from description when current value is blank', () => {
  const frames = [
    { frameId: 'f1', description: 'Prompt one' },
    { frameId: 'f2', description: 'Prompt two' },
  ];
  const existing = { f1: '', f2: 'Custom text' };

  const merged = hydrateFrameInputs(existing, frames);
  assert.equal(merged.f1, 'Prompt one');
  assert.equal(merged.f2, 'Custom text');
});

test('frame input prefill avoids generic frame labels', () => {
  const frames = [{ frameId: 'f1', description: 'Frame 1' }];
  const merged = hydrateFrameInputs({}, frames);
  assert.equal(merged.f1, '');
});
