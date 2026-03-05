import test from 'node:test';
import assert from 'node:assert/strict';

const baseFromEnv = (process.env.ROBOGENE_API_BASE || '').replace(/\/+$/, '');

async function getState(base) {
  const res = await fetch(`${base}/api/state`, { headers: { 'Cache-Control': 'no-store' } });
  assert.equal(res.ok, true, `GET /api/state -> HTTP ${res.status}`);
  return res.json();
}

async function postJson(base, path, payload) {
  const res = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
    body: JSON.stringify(payload),
  });
  const body = await res.json().catch(() => ({}));
  return { res, body };
}

test('api mutation: add chapter works independently', async (t) => {
  if (!baseFromEnv) {
    t.skip('ROBOGENE_API_BASE is not set; skipping live mutation test.');
    return;
  }

  const unique = `Test chapter ${Date.now()}`;
  let createdChapterId = null;

  try {
    const before = await getState(baseFromEnv);
    const beforeCount = (before.saga || []).length;

    const { res, body } = await postJson(baseFromEnv, '/api/add-chapter', { description: unique });
    assert.equal(res.status, 201, `POST /api/add-chapter -> HTTP ${res.status}`);
    assert.equal(body.created, true, 'response.created should be true');
    assert.ok(body.chapter && body.chapter.chapterId, 'response should include chapter.chapterId');
    createdChapterId = body.chapter.chapterId;

    const after = await getState(baseFromEnv);
    const afterCount = (after.saga || []).length;
    assert.equal(afterCount, beforeCount + 1, 'chapter count should increment by one');
    assert.ok((after.saga || []).some((c) => c.chapterId === createdChapterId), 'new chapter should exist in saga list');
  } finally {
    if (createdChapterId) {
      await postJson(baseFromEnv, '/api/delete-chapter', { chapterId: createdChapterId });
    }
  }
});

test('api mutation: add character works independently', async (t) => {
  if (!baseFromEnv) {
    t.skip('ROBOGENE_API_BASE is not set; skipping live mutation test.');
    return;
  }

  const unique = `Test character ${Date.now()}`;
  let createdCharacterId = null;

  try {
    const before = await getState(baseFromEnv);
    const beforeCount = (before.characters || []).length;

    const { res, body } = await postJson(baseFromEnv, '/api/add-character', { description: unique });
    assert.equal(res.status, 201, `POST /api/add-character -> HTTP ${res.status}`);
    assert.equal(body.created, true, 'response.created should be true');
    assert.ok(body.character && body.character.characterId, 'response should include character.characterId');
    createdCharacterId = body.character.characterId;

    const after = await getState(baseFromEnv);
    const afterCount = (after.characters || []).length;
    assert.equal(afterCount, beforeCount + 1, 'character count should increment by one');
    assert.ok((after.characters || []).some((c) => c.characterId === createdCharacterId), 'new character should exist in characters list');
  } finally {
    if (createdCharacterId) {
      await postJson(baseFromEnv, '/api/delete-character', { characterId: createdCharacterId });
    }
  }
});
