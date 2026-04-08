import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { execa } from 'execa';
import { attachConsoleFailureGuard } from '../shared/playwright.mjs';
import { runGalleryScenario, runGalleryUploadScenario } from './e2e-gallery-ui.test.mjs';
import { runMobileActionsScenario } from './e2e-mobile-edit-db-item-actions-ui.test.mjs';
import { runRosterPersistScenario } from './e2e-roster-description-persist-ui.test.mjs';
import { runRosterGenerateScenario } from './e2e-roster-generate-ui.test.mjs';
import { runSmokeScenario } from './e2e-smoke-ui.test.mjs';

const apiPort = 7072;
const apiBase = `http://localhost:${apiPort}`;
const webappPort = 8081;

let seedIds = {};

function logStep(scope, message) {
  console.log(`[e2e][${scope}] ${message}`);
}

test.beforeAll(async () => {
  const fixturePath = process.env.ROBOGENE_E2E_SEED_FILE || 'tests/shared/fixtures/e2e-seed.http';
  if (fs.existsSync(fixturePath)) {
    logStep('seed', `running ${fixturePath}`);
    await execa('npx', ['httpyac', 'send', path.resolve(fixturePath), '--all'], {
      env: { ...process.env, ROBOGENE_API_BASE: apiBase },
      stdio: 'inherit',
    });
    logStep('seed', 'done');

    const stateResponse = await fetch(`${apiBase}/api/state`);
    if (stateResponse.ok) {
        const state = await stateResponse.json();
        const entities = state.entities || {};
        const values = Object.values(entities);
        const findId = (role, title) => values.find((entity) => entity?.vanityRole === role && entity?.title === title)?.id || null;
        seedIds = {
          sagaId: findId('saga', 'Smoke Test Saga'),
          rosterId: findId('roster', 'Test Roster'),
          chapterId: findId('chapter', 'Smoke Chapter'),
          characterId: findId('character', 'Seed Character'),
        };
        logStep('seed', `extracted sagaId: ${seedIds.sagaId}, rosterId: ${seedIds.rosterId}`);
    } else {
        throw new Error('Failed to fetch state after seeding');
    }
  }
});

function createOpenPage(browser) {
  return async (scope, options = {}) => {
    const context = await browser.newContext({
      ...options,
      baseURL: `http://localhost:${webappPort}`,
    });
    const page = await context.newPage();
    const consoleGuard = attachConsoleFailureGuard(page, {
      ignore: [
        'Download the React DevTools for a better development experience',
        'Warning: Error from HTTP request. TypeError: Failed to fetch.',
        'Failed to complete negotiation with the server: TypeError: Failed to fetch',
        'Failed to start the connection: Error: Failed to complete negotiation with the server: TypeError: Failed to fetch',
        '[robogene] SignalR start failed: Failed to complete negotiation with the server: TypeError: Failed to fetch',
      ],
    });
    await page.addInitScript((base) => {
      window.ROBOGENE_API_BASE = base;
    }, apiBase);
    return {
      page,
      consoleGuard,
      close: async () => {
        consoleGuard.detach();
        await context.close();
      },
    };
  };
}

test('ui e2e: smoke app boots and shows first frame', async ({ browser }) => {
  const openPage = createOpenPage(browser);
  await runSmokeScenario({ openPage, actionTimeoutMs: 15000, logStep, seedIds });
});

test('ui e2e: gallery add frame and generate image', async ({ browser }) => {
  const openPage = createOpenPage(browser);
  await runGalleryScenario({ openPage, actionTimeoutMs: 15000, logStep, seedIds });
});

test('ui e2e: gallery add frame upload image and persist description', async ({ browser }) => {
  const openPage = createOpenPage(browser);
  await runGalleryUploadScenario({ openPage, actionTimeoutMs: 15000, logStep, seedIds });
});

test('ui e2e: mobile frame description edit actions stay visible', async ({ browser }) => {
  const openPage = createOpenPage(browser);
  await runMobileActionsScenario({ openPage, actionTimeoutMs: 15000, logStep, seedIds });
});

test('ui e2e: roster character description persists after reload', async ({ browser }) => {
  const openPage = createOpenPage(browser);
  await runRosterPersistScenario({ openPage, actionTimeoutMs: 15000, logStep, seedIds });
});

test('ui e2e: roster add character and generate image', async ({ browser }) => {
  const openPage = createOpenPage(browser);
  await runRosterGenerateScenario({ openPage, actionTimeoutMs: 15000, logStep, seedIds });
});
