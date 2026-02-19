import test from 'node:test';
import assert from 'node:assert/strict';

function genericFrameText(text, frameNumber) {
  return (text || '').trim().toLowerCase() === `frame ${frameNumber}`;
}

function genericFrameLabel(text) {
  return /^frame\s+\d+$/i.test((text || '').trim());
}

function clampText(text, limit) {
  const v = (text || '').trim();
  return v.length > limit ? `${v.slice(0, limit)}...` : v;
}

function frameDescription(frame) {
  const frameNumber = frame.frameNumber;
  const description = (frame.description || '').trim();
  const preferred =
    description && !genericFrameText(description, frameNumber)
      ? description
      : 'No description yet.';
  return clampText(preferred, 180);
}

function enrichFrame(state, frame) {
  const frameNumber = frame.frameNumber;
  const descriptionFallback = (state.descriptions || []).find((b) => b.index === frameNumber)?.text?.trim();
  const pagePrompt = state.visual?.pagePrompts?.[frameNumber]?.trim();
  const base = { ...frame, frameNumber };
  const description = (base.description || '').trim();
  return !description || genericFrameText(description, frameNumber)
    ? { ...base, description: pagePrompt || descriptionFallback || description }
    : base;
}

function hydrateFrameInputs(existing, frames) {
  return frames.reduce((acc, frame) => {
    const frameId = frame.frameId;
    const existingVal = existing[frameId];
    const description = (frame.description || '').trim();
    const backendVal = (description && !genericFrameLabel(description) && description) || '';
    acc[frameId] = !(existingVal || '').trim() ? backendVal : existingVal;
    return acc;
  }, {});
}

function normalizeFrames(state) {
  return (state.frames || [])
    .map((f) => {
      const enriched = enrichFrame(state, f);
      return { ...enriched, frameDescription: frameDescription(enriched) };
    })
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

test('frame description uses stable non-blank fallback and stays concise', () => {
  const state = {
    frames: [
      {
        frameId: 'a',
        frameNumber: 1,
        description: 'Frame 1',
        status: 'draft',
      },
      {
        frameId: 'b',
        frameNumber: 2,
        description: '',
        status: 'draft',
      },
      {
        frameId: 'c',
        frameNumber: 3,
        description: 'A'.repeat(240),
        status: 'draft',
      },
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
  const frames = [
    {
      frameId: 'f1',
      description: 'Frame 1',
    },
  ];

  const merged = hydrateFrameInputs({}, frames);
  assert.equal(merged.f1, '');
});

test('normalize uses descriptions/prompts fallback for generic frame text', () => {
  const state = {
    descriptions: [{ index: 1, text: 'Cold open with stuck traffic lights.' }],
    visual: { pagePrompts: { 1: 'City intersection gridlock prompt.' } },
    frames: [{ frameId: 'f1', frameNumber: 1, description: 'Frame 1' }],
  };

  const [frame] = normalizeFrames(state);
  assert.equal(frame.description, 'City intersection gridlock prompt.');
  assert.equal(frame.frameDescription, 'City intersection gridlock prompt.');
});
