import test, { after, before, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { startAzurite } from '../shared/azurite.mjs';
import { bootWebapiIntegration } from '../shared/webapi-integration.mjs';

let azurite;
let harness;
let fetchCalls = [];
let originalFetch;
let workspaceSeq = 0;

function nextWorkspace(prefix) {
  workspaceSeq += 1;
  return `${prefix}-${Date.now()}-${workspaceSeq}`;
}

before(async () => {
  azurite = await startAzurite();
  harness = bootWebapiIntegration();
});

beforeEach(() => {
  fetchCalls = [];
  originalFetch = global.fetch;
  global.fetch = async (url, options = {}) => {
    fetchCalls.push({
      url: String(url),
      options,
      body: options?.body ? JSON.parse(options.body) : null,
    });
    return {
      ok: true,
      status: 202,
      text: async () => '',
    };
  };
});

afterEach(() => {
  global.fetch = originalFetch;
});

after(async () => {
  global.fetch = originalFetch;
  await azurite?.close();
});

test('integration webapi save persists an entity to storage and get-state loads it back', async () => {
  const workspaceId = nextWorkspace('save');
  harness.resetEntityState(workspaceId);

  const saveResponse = await harness.api.handle_save_entity(
    harness.makeRequest({
      url: 'http://localhost/api/entity',
      body: {
        id: 'chapter-1',
        vanityRole: 'chapter',
        title: 'Chapter 1',
        children: [],
        payload: {},
      },
    }),
  );

  assert.equal(saveResponse.status, 200);

  const stored = await harness.loadStoredEntities(workspaceId);
  assert.equal(stored.length, 1);
  assert.equal(stored[0].id, 'chapter-1');
  assert.equal(stored[0].title, 'Chapter 1');

  harness.resetEntityState(workspaceId);

  const stateResponse = await harness.api.handle_get_state(
    harness.makeRequest({ url: 'http://localhost/api/state' }),
  );

  assert.equal(stateResponse.status, 200);
  assert.equal(stateResponse.jsonBody.entities['chapter-1'].title, 'Chapter 1');
});

test('integration webapi delete removes a frame from storage and updates its parent chapter', async () => {
  const workspaceId = nextWorkspace('delete');
  harness.resetEntityState(workspaceId);

  await harness.api.handle_save_entity(
    harness.makeRequest({
      url: 'http://localhost/api/entity',
      body: {
        id: 'chapter-1',
        vanityRole: 'chapter',
        title: 'Chapter 1',
        children: [],
        payload: {},
      },
    }),
  );
  await harness.api.handle_save_entity(
    harness.makeRequest({
      url: 'http://localhost/api/entity',
      body: {
        id: 'frame-1',
        vanityRole: 'frame',
        title: 'Frame 1',
        children: [],
        payload: { parentId: 'chapter-1' },
      },
    }),
  );

  const deleteResponse = await harness.api.handle_delete_entity(
    harness.makeDeleteRequest('frame-1'),
  );

  assert.equal(deleteResponse.status, 200);

  const stored = await harness.loadStoredEntities(workspaceId);
  assert.equal(stored.some((item) => item.id === 'frame-1'), false);
  assert.deepEqual(stored.find((item) => item.id === 'chapter-1')?.children, []);
});

test('integration webapi save publishes a SignalR stateChanged message', async () => {
  const workspaceId = nextWorkspace('signalr-save');
  harness.resetEntityState(workspaceId);

  await harness.api.handle_save_entity(
    harness.makeRequest({
      url: 'http://localhost/api/entity',
      body: {
        id: 'chapter-1',
        vanityRole: 'chapter',
        title: 'Chapter 1',
        children: [],
        payload: {},
      },
    }),
  );

  assert.equal(fetchCalls.length, 1);
  assert.equal(fetchCalls[0].url, 'https://signalr.integration.test/api/v1/hubs/robogene');
  assert.equal(fetchCalls[0].options.method, 'POST');
  assert.equal(fetchCalls[0].body.target, 'stateChanged');
  assert.equal(fetchCalls[0].body.arguments[0].reason, 'entity-updated');
  assert.equal(fetchCalls[0].body.arguments[0].entity.id, 'chapter-1');
});

test('integration webapi delete publishes a SignalR delete event', async () => {
  const workspaceId = nextWorkspace('signalr-delete');
  harness.resetEntityState(workspaceId);

  await harness.api.handle_save_entity(
    harness.makeRequest({
      url: 'http://localhost/api/entity',
      body: {
        id: 'chapter-1',
        vanityRole: 'chapter',
        title: 'Chapter 1',
        children: [],
        payload: {},
      },
    }),
  );
  await harness.api.handle_save_entity(
    harness.makeRequest({
      url: 'http://localhost/api/entity',
      body: {
        id: 'frame-1',
        vanityRole: 'frame',
        title: 'Frame 1',
        children: [],
        payload: { parentId: 'chapter-1' },
      },
    }),
  );

  fetchCalls = [];

  await harness.api.handle_delete_entity(harness.makeDeleteRequest('frame-1'));

  assert.equal(fetchCalls.length, 1);
  assert.equal(fetchCalls[0].body.target, 'stateChanged');
  assert.equal(fetchCalls[0].body.arguments[0].reason, 'entity-deleted');
  assert.equal(fetchCalls[0].body.arguments[0].id, 'frame-1');
  assert.deepEqual(fetchCalls[0].body.arguments[0].deletedIds, ['frame-1']);
});
