import test from 'node:test';
import assert from 'node:assert/strict';

function genericFrameText(text, frameNumber) {
  return (text || '').trim().toLowerCase() === `frame ${frameNumber}`;
}

function firstUsefulLine(text) {
  return (text || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line && !line.toLowerCase().startsWith('keep continuity'));
}

function clampText(text, limit) {
  const v = (text || '').trim();
  return v.length > limit ? `${v.slice(0, limit)}...` : v;
}

function frameDescription(frame) {
  const frameNumber = frame.frameNumber;
  const beat = (frame.beatText || '').trim();
  const preferred =
    beat && !genericFrameText(beat, frameNumber)
      ? beat
      : firstUsefulLine(frame.directionText) ||
        firstUsefulLine(frame.suggestedDirection) ||
        'No description yet.';
  return clampText(preferred, 180);
}

function defaultSuggestedDirection(beatText, pagePrompt) {
  return [beatText, pagePrompt, 'Keep continuity with previous frames.'].filter(Boolean).join('\n');
}

function enrichFrame(state, frame) {
  const frameNumber = frame.frameNumber;
  const beatFallback = (state.beats || []).find((b) => b.index === frameNumber)?.text?.trim();
  const pagePrompt = state.visual?.pagePrompts?.[frameNumber]?.trim();
  const base = { ...frame, frameNumber };
  const beat = (base.beatText || '').trim();
  const withBeat =
    !beat || genericFrameText(beat, frameNumber)
      ? { ...base, beatText: beatFallback || beat }
      : base;
  const suggested = (withBeat.suggestedDirection || '').trim();
  return !suggested
    ? { ...withBeat, suggestedDirection: defaultSuggestedDirection(withBeat.beatText, pagePrompt) }
    : withBeat;
}

function hydrateFrameInputs(existing, frames) {
  return frames.reduce((acc, frame) => {
    const frameId = frame.frameId;
    const existingVal = existing[frameId];
    const backendVal = frame.directionText || frame.suggestedDirection || '';
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
        beatText: 'Frame 1',
        suggestedDirection: 'Robot enters hall.\nKeep continuity with previous frames.',
        status: 'draft',
      },
      {
        frameId: 'b',
        frameNumber: 2,
        beatText: '',
        directionText: '',
        suggestedDirection: '',
        status: 'draft',
      },
      {
        frameId: 'c',
        frameNumber: 3,
        beatText: 'Frame 3',
        directionText: 'A'.repeat(240),
        status: 'draft',
      },
    ],
  };

  const frames = normalizeFrames(state);
  const map = Object.fromEntries(frames.map((f) => [f.frameId, f]));

  assert.equal(map.a.frameDescription, 'Robot enters hall.');
  assert.equal(map.b.frameDescription, 'No description yet.');
  assert.ok(map.c.frameDescription.length <= 183);
});

test('frame inputs prefill from suggestion when current value is blank', () => {
  const frames = [
    { frameId: 'f1', directionText: '', suggestedDirection: 'Suggested one' },
    { frameId: 'f2', directionText: '', suggestedDirection: 'Suggested two' },
  ];
  const existing = { f1: '', f2: 'Custom text' };

  const merged = hydrateFrameInputs(existing, frames);
  assert.equal(merged.f1, 'Suggested one');
  assert.equal(merged.f2, 'Custom text');
});

test('normalize uses beats/prompts fallback for generic frame text', () => {
  const state = {
    beats: [{ index: 1, text: 'Cold open with stuck traffic lights.' }],
    visual: { pagePrompts: { 1: 'Yellow lights forever.' } },
    frames: [{ frameId: 'f1', frameNumber: 1, beatText: 'Frame 1', suggestedDirection: '' }],
  };

  const [frame] = normalizeFrames(state);
  assert.equal(frame.beatText, 'Cold open with stuck traffic lights.');
  assert.ok(frame.suggestedDirection.includes('Yellow lights forever.'));
  assert.equal(frame.frameDescription, 'Cold open with stuck traffic lights.');
});
