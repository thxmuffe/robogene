import test, { after, before, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

import { startAzurite } from '../shared/azurite.mjs';
import { bootWebapiIntegration, seedFromHttpFixture } from '../shared/webapi-integration.mjs';

const redPixelImage = 'data:image/raw-rgb;base64,/wAA';

let azurite;
let harness;
let originalFetch;
let workspaceSeq = 0;

function nextWorkspace(prefix) {
  workspaceSeq += 1;
  return `${prefix}-${Date.now()}-${workspaceSeq}`;
}

function fixtureImage(name) {
  const bytes = fs.readFileSync(new URL(`../shared/fixtures/${name}`, import.meta.url));
  return `data:image/png;base64,${bytes.toString('base64')}`;
}

async function waitForReadyFrame(frameId) {
  for (let i = 0; i < 80; i += 1) {
    const frame = harness.snapshot().entities[frameId];
    if (frame?.payload?.imageStatus === 'ready') return frame;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }

  throw new Error(`Frame ${frameId} did not become ready.`);
}

before(async () => {
  azurite = await startAzurite();
  harness = bootWebapiIntegration();
});

beforeEach(() => {
  originalFetch = global.fetch;
  global.fetch = async (url, options) => {
    if (String(url).includes('signalr')) {
      return { ok: true, status: 202, text: async () => '' };
    }
    return originalFetch(url, options);
  };
  harness.services.realtime.publish_state_update_BANG_ = async () => true;
});

afterEach(() => {
  global.fetch = originalFetch;
});

after(async () => {
  global.fetch = originalFetch;
  await azurite?.close();
});

test('chapter agent generation uses seeded roster reference for named character', async () => {
  const robotEmperorImage = fixtureImage('robotemperor1.png');
  const billImage = fixtureImage('bill1.png');

  const workspaceId = nextWorkspace('chapter-agent');
  harness.resetEntityState(workspaceId);

  await seedFromHttpFixture(harness, '../shared/fixtures/chapter-agent-seed.http', import.meta.url, {
    ROBOGENE_API_BASE: 'http://localhost',
    ROBOGENE_ROBOT_EMPEROR_IMAGE: robotEmperorImage,
    ROBOGENE_BILL_IMAGE: billImage,
  });

  await harness.api.handle_get_state(harness.makeRequest({ url: 'http://localhost/api/state' }));
  const agent = harness.entity.ensure_agent_exists_BANG_('chapter-agent-chapter');
  
  const selectedRefs = harness.cljsToJs(
    await harness.entity.selected_character_refs(agent, 'Robot Emperor with the red scarf enters.'),
  );
  assert.equal(selectedRefs.length, 1);
  assert.equal(selectedRefs[0].name, 'Robot Emperor.png');

  const generationRequests = [];
  harness.services.image_generator.generate_image_BANG_ = async (request) => {
    const requestData = harness.cljsToJs(request);
    generationRequests.push(requestData);
    if (
      requestData.prompt === 'Robot Emperor with the red scarf enters.' &&
      requestData.refs.length === 1 &&
      requestData.refs[0].name === 'Robot Emperor.png'
    ) {
      return redPixelImage;
    }
    return 'data:image/raw-rgb;base64,AAAA';
  };

  const response = await harness.api.handle_generate_frame(
    harness.makeRequest({
      url: 'http://localhost/api/generate-frame',
      body: {
        frameId: 'chapter-agent-frame',
        direction: 'Robot Emperor with the red scarf enters.',
        generator: 'mock',
        withoutRoster: false,
      },
    }),
  );

  assert.equal(response.status, 200);

  const frame = await waitForReadyFrame('chapter-agent-frame');
  assert.equal(generationRequests.length, 1);
  assert.equal(generationRequests[0].refs[0].name, 'Robot Emperor.png');
  assert.equal(frame.payload.imageUrl, redPixelImage);
  assert.equal(Buffer.from(frame.payload.imageUrl.split(',')[1], 'base64')[0], 255);
});
