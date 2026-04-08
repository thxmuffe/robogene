import test from 'node:test';
import assert from 'node:assert/strict';

import {
  cljsToJs,
  importWebapiFiles,
  jsToCljs,
  loadWebapiNamespace,
} from '../shared/load-webapi-runtime.mjs';

const runtime = loadWebapiNamespace('imageGenerator');
loadWebapiNamespace('realtime');
loadWebapiNamespace('azureStore');

const { cljs, services } = runtime;

services.image_generator.require_startup_env_BANG_ = () => true;
services.azure_store.load_entities = () => Promise.resolve([]);

importWebapiFiles(['services.entity.js']);

const entity = services.entity;

let persistedIds = [];
let deletedIds = [];
let publishedEvents = [];
let generationRequests = [];

function snapshot() {
  return cljsToJs(cljs.core.deref(entity.state));
}

function buildEntityMap(entities) {
  return entities.reduce(
    (acc, item) => cljs.core.assoc(acc, item.id, jsToCljs(item)),
    cljs.core.PersistentArrayMap.EMPTY,
  );
}

function kw(name) {
  return cljs.core.keyword(name);
}

function resetEntityState(entities = []) {
  persistedIds = [];
  deletedIds = [];
  publishedEvents = [];
  generationRequests = [];

  services.azure_store.save_entity_BANG_ = async (_workspaceId, savedEntity) => {
    const saved = cljsToJs(savedEntity);
    persistedIds.push(saved.id);
    return savedEntity;
  };
  services.azure_store.delete_entity_BANG_ = async (_workspaceId, entityId) => {
    deletedIds.push(String(entityId));
    return true;
  };
  services.realtime.publish_state_update_BANG_ = async (payload) => {
    publishedEvents.push(payload);
    return true;
  };
  services.image_generator.generate_image_BANG_ = async (request) => {
    generationRequests.push(cljsToJs(request));
    return 'data:image/png;base64,generated';
  };

  cljs.core.reset_BANG_(
    entity.state,
    cljs.core.assoc(
      cljs.core.assoc(
        cljs.core.assoc(
          cljs.core.assoc(
            cljs.core.assoc(
              cljs.core.assoc(
                jsToCljs({}),
                kw('workspaceId'),
                'test-workspace',
              ),
              kw('entities'),
              buildEntityMap(entities),
            ),
            kw('processing'),
            false,
          ),
          kw('revision'),
          0,
        ),
        kw('defaultImageGenerator'),
        'mock',
      ),
      kw('availableImageGenerators'),
      jsToCljs(['mock']),
    ),
  );
}

async function waitForReadyFrame(frameId) {
  for (let i = 0; i < 40; i += 1) {
    const current = snapshot().entities[frameId];
    if (current?.payload?.imageStatus === 'ready') return current;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }

  throw new Error(`Frame ${frameId} did not reach ready state in time`);
}

test('entity save_entity_BANG_ adds a frame under its chapter and persists both', async () => {
  resetEntityState([
    { id: 'chapter-1', vanityRole: 'chapter', title: 'Chapter 1', children: [], payload: {} },
  ]);

  await entity.save_entity_BANG_(
    jsToCljs({
      id: 'frame-1',
      vanityRole: 'frame',
      title: 'Frame 1',
      description: 'Opening shot',
      children: [],
      payload: { parentId: 'chapter-1' },
    }),
  );

  const state = snapshot();
  assert.equal(state.entities['frame-1'].payload.parentId, 'chapter-1');
  assert.deepEqual(state.entities['chapter-1'].children, ['frame-1']);
  assert.deepEqual(persistedIds, ['frame-1', 'chapter-1']);
  assert.equal(publishedEvents.at(-1)?.reason, 'entity-updated');
});

test('entity save_entity_BANG_ updates a chapter title without touching its links', async () => {
  resetEntityState([
    { id: 'chapter-1', vanityRole: 'chapter', title: 'Old title', children: ['frame-1'], payload: {} },
    { id: 'frame-1', vanityRole: 'frame', title: 'Frame 1', children: [], payload: { parentId: 'chapter-1' } },
  ]);

  await entity.save_entity_BANG_(
    jsToCljs({
      id: 'chapter-1',
      vanityRole: 'chapter',
      title: 'Renamed chapter',
      children: ['frame-1'],
      payload: {},
    }),
  );

  const state = snapshot();
  assert.equal(state.entities['chapter-1'].title, 'Renamed chapter');
  assert.deepEqual(state.entities['chapter-1'].children, ['frame-1']);
  assert.deepEqual(persistedIds, ['chapter-1']);
});

test('entity delete_entity_BANG_ removes the frame and updates the parent chapter', async () => {
  resetEntityState([
    { id: 'chapter-1', vanityRole: 'chapter', title: 'Chapter 1', children: ['frame-1', 'frame-2'], payload: {} },
    { id: 'frame-1', vanityRole: 'frame', title: 'Frame 1', children: [], payload: { parentId: 'chapter-1' } },
    { id: 'frame-2', vanityRole: 'frame', title: 'Frame 2', children: [], payload: { parentId: 'chapter-1' } },
  ]);

  await entity.delete_entity_BANG_('frame-1');

  const state = snapshot();
  assert.equal(state.entities['frame-1'], undefined);
  assert.deepEqual(state.entities['chapter-1'].children, ['frame-2']);
  assert.deepEqual(deletedIds, ['frame-1']);
  assert.equal(publishedEvents.at(-1)?.reason, 'entity-deleted');
});

test('entity queue_frame_generation_BANG_ drives a frame from queued to ready image state', async () => {
  resetEntityState([
    { id: 'frame-1', vanityRole: 'frame', title: 'Frame 1', description: 'Old prompt', children: [], payload: {} },
  ]);

  await entity.queue_frame_generation_BANG_(
    jsToCljs({
      frameId: 'frame-1',
      direction: 'A better prompt',
      generator: 'mock',
    }),
  );

  const frame = await waitForReadyFrame('frame-1');
  assert.equal(frame.description, 'A better prompt');
  assert.equal(frame.payload.generator, 'mock');
  assert.equal(frame.payload.imageStatus, 'ready');
  assert.equal(frame.payload.imageUrl, 'data:image/png;base64,generated');
  assert.deepEqual(generationRequests, [
    {
      generator: 'mock',
      prompt: 'Create ONE comic image.\n\nSubject: Frame 1\n\nDetails: A better prompt\n\nAvoid text overlays.',
      refs: [],
    },
  ]);
});
