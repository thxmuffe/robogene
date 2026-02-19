import test from 'node:test';
import assert from 'node:assert/strict';

const LEGACY_DRAFT_ID = '__legacy_draft__';

function frameFromHistory(idx, h) {
  return {
    frameId: h.frameId || `legacy-ready-${h.sceneNumber}-${idx}`,
    sceneNumber: h.sceneNumber,
    beatText: h.beatText,
    suggestedDirection: '',
    directionText: '',
    imageDataUrl: h.imageDataUrl,
    status: 'ready',
    reference: h.reference,
    createdAt: h.createdAt,
  };
}

function frameFromPending(idx, p) {
  return {
    frameId: p.frameId || p.jobId || `legacy-pending-${p.sceneNumber}-${idx}`,
    sceneNumber: p.sceneNumber,
    beatText: p.beatText,
    suggestedDirection: p.directionText || '',
    directionText: p.directionText || '',
    status: p.status || 'queued',
    createdAt: p.queuedAt,
  };
}

function legacyDraftFrame(state, existingFrames) {
  const maxScene = existingFrames.reduce((m, f) => Math.max(m, f.sceneNumber || 0), 0);
  const nextNum = state.nextSceneNumber || (maxScene + 1);
  return {
    frameId: LEGACY_DRAFT_ID,
    sceneNumber: nextNum,
    beatText: `Scene ${nextNum}`,
    suggestedDirection: state.nextDefaultDirection || '',
    directionText: state.nextDefaultDirection || '',
    status: 'draft',
  };
}

function frameModel(state) {
  if (Array.isArray(state.frames) && state.frames.length > 0) {
    return { backendMode: 'frames', frames: [...state.frames] };
  }

  const ready = (state.history || []).map((h, idx) => frameFromHistory(idx, h));
  const pending = (state.pending || []).map((p, idx) => frameFromPending(idx, p));
  const merged = [...ready, ...pending];
  const hasDraft = merged.some((f) => !f.imageDataUrl);
  const withDraft = hasDraft ? merged : [...merged, legacyDraftFrame(state, merged)];
  return {
    backendMode: 'legacy',
    frames: withDraft.length > 0 ? withDraft : [legacyDraftFrame(state, [])],
  };
}

test('legacy state converts to ready + draft frames', () => {
  const state = {
    history: [
      { sceneNumber: 1, beatText: 'Cold open', imageDataUrl: 'data:image/png;base64,abc' },
    ],
    pending: [],
    nextSceneNumber: 2,
    nextDefaultDirection: 'Scene 2 prompt',
  };

  const { backendMode, frames } = frameModel(state);
  assert.equal(backendMode, 'legacy');
  assert.equal(frames.length, 2);
  assert.equal(frames[0].status, 'ready');
  assert.equal(frames[1].status, 'draft');
  assert.equal(frames[1].beatText, 'Scene 2');
  assert.equal(typeof frames[0].sceneNumber, 'number');
  assert.equal(typeof frames[1].status, 'string');
});

test('new frame state passes through', () => {
  const state = {
    frames: [
      { frameId: 'a', sceneNumber: 1, status: 'ready', imageDataUrl: 'data:image/png;base64,aaa' },
      { frameId: 'b', sceneNumber: 2, status: 'draft' },
    ],
  };

  const { backendMode, frames } = frameModel(state);
  assert.equal(backendMode, 'frames');
  assert.equal(frames.length, 2);
  assert.ok(frames.every((f) => typeof f.sceneNumber === 'number'));
  assert.ok(frames.every((f) => typeof f.status === 'string'));
});
