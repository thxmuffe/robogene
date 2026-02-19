import test from 'node:test';
import assert from 'node:assert/strict';

const baseFromEnv = (process.env.ROBOGENE_API_BASE || '').replace(/\/+$/, '');

function normalizeFrames(state) {
  if (Array.isArray(state.frames) && state.frames.length > 0) return state.frames;

  const ready = (state.history || []).map((h, idx) => ({
    frameId: h.frameId || `legacy-ready-${h.sceneNumber}-${idx}`,
    sceneNumber: h.sceneNumber,
    status: 'ready',
    imageDataUrl: h.imageDataUrl,
  }));

  const pending = (state.pending || []).map((p, idx) => ({
    frameId: p.frameId || p.jobId || `legacy-pending-${p.sceneNumber}-${idx}`,
    sceneNumber: p.sceneNumber,
    status: p.status || 'queued',
    imageDataUrl: p.imageDataUrl,
  }));

  const frames = [...ready, ...pending];
  if (!frames.some((f) => !f.imageDataUrl)) {
    const next = state.nextSceneNumber || (Math.max(0, ...frames.map((f) => f.sceneNumber || 0)) + 1);
    frames.push({ frameId: '__legacy_draft__', sceneNumber: next, status: 'draft' });
  }
  return frames;
}

test('backend state exposes at least one frame with valid types', async () => {
  const state = baseFromEnv
    ? await (async () => {
        const res = await fetch(`${baseFromEnv}/api/state`, { headers: { 'Cache-Control': 'no-store' } });
        assert.equal(res.ok, true, `HTTP ${res.status}`);
        return res.json();
      })()
    : {
        frames: [
          { frameId: 'f-1', sceneNumber: 1, status: 'ready', imageDataUrl: 'data:image/png;base64,AAA' },
          { frameId: 'f-2', sceneNumber: 2, status: 'draft', imageDataUrl: '' },
        ],
      };

  const frames = normalizeFrames(state);

  assert.ok(Array.isArray(frames), 'frames should be an array');
  assert.ok(frames.length >= 1, 'should expose at least one frame');
  assert.ok(frames.every((f) => typeof f.sceneNumber === 'number'), 'sceneNumber must be number');
  assert.ok(frames.every((f) => typeof f.status === 'string'), 'status must be string');
});
