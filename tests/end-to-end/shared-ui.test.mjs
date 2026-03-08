import fs from 'node:fs';
import { spawn } from 'node:child_process';
import test from 'node:test';
import { stopProcess, waitForHttpOk } from '../shared/async.mjs';
import { attachConsoleFailureGuard } from '../shared/playwright.mjs';
import { getFreePort } from '../shared/ports.mjs';
import { commandAvailable, killByPattern } from '../shared/system.mjs';
import { runGalleryScenario } from './e2e-gallery-ui.test.mjs';
import { runMobileActionsScenario } from './e2e-mobile-edit-db-item-actions-ui.test.mjs';
import { runRosterPersistScenario } from './e2e-roster-description-persist-ui.test.mjs';
import { runRosterGenerateScenario } from './e2e-roster-generate-ui.test.mjs';

const shouldRun = process.env.ROBOGENE_RUN_E2E_UI === '1';
const startupTimeoutMs = 90000;
const actionTimeoutMs = 45000;
const onlyScenario = process.env.ROBOGENE_E2E_ONLY || '';
const mockSvg = "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><rect width='10' height='10' fill='#1496ff'/></svg>";
const mockSvgDataUrl = `data:image/svg+xml;base64,${Buffer.from(mockSvg, 'utf8').toString('base64')}`;

function logStep(scope, message) {
  console.log(`[e2e][${scope}] ${message}`);
}

async function loadPlaywright(t) {
  try {
    return await import('playwright');
  } catch {
    t.skip('Playwright not installed. Run: npm i -D playwright && npx playwright install chromium');
    return null;
  }
}

function createAppLogger() {
  let logs = '';
  return {
    append(chunk) {
      logs += chunk.toString();
      if (logs.length > 20000) logs = logs.slice(-20000);
    },
    get() {
      return logs;
    },
  };
}

test('ui e2e suite', { skip: !shouldRun, concurrency: false }, async (t) => {
  if (!commandAvailable('func')) {
    t.skip('Azure Functions Core Tools (`func`) not found.');
    return;
  }
  if (!fs.existsSync('robogen.debug.env')) {
    t.skip('robogen.debug.env is required to run npm start:release.');
    return;
  }

  const playwright = await loadPlaywright(t);
  if (!playwright) return;

  const webappPort = await getFreePort(0);
  const apiPort = await getFreePort(0);

  killByPattern('func start --script-root src/host');
  killByPattern('http-server dist/release/webapp -p');

  const appLogs = createAppLogger();
  const app = spawn('npm', ['run', 'start:release'], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      WEBAPP_PORT: String(webappPort),
      WEBAPI_PORT: String(apiPort),
      FUNCTIONS_WORKER_RUNTIME: 'node',
      ROBOGENE_IMAGE_GENERATOR: 'mock',
      ROBOGENE_IMAGE_GENERATOR_MOCK_DATA_URL: mockSvgDataUrl,
      ROBOGENE_IMAGE_GENERATOR_MOCK_DELAY_MS: '10',
      ROBOGENE_ALLOWED_ORIGIN: `http://localhost:${webappPort},http://127.0.0.1:${webappPort}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  app.stdout.on('data', (chunk) => {
    process.stdout.write(chunk);
    appLogs.append(chunk);
  });
  app.stderr.on('data', (chunk) => {
    process.stderr.write(chunk);
    appLogs.append(chunk);
  });

  let browser = null;
  try {
    logStep('suite', `starting shared app on web:${webappPort} api:${apiPort}`);
    await waitForHttpOk(`http://localhost:${webappPort}/index.html`, startupTimeoutMs);
    logStep('suite', 'web app is reachable');
    await waitForHttpOk(`http://localhost:${apiPort}/api/state`, startupTimeoutMs);
    logStep('suite', 'api is reachable');

    browser = await playwright.chromium.launch({ headless: true });
    logStep('suite', 'browser launched');

    const openPage = async (scope, options = {}) => {
      const context = await browser.newContext({
        ...options,
        baseURL: `http://localhost:${webappPort}`,
      });
      const page = await context.newPage();
      const consoleGuard = attachConsoleFailureGuard(page, {
        ignore: ['Download the React DevTools for a better development experience'],
      });
      await page.addInitScript((base) => {
        window.ROBOGENE_API_BASE = base;
      }, `http://localhost:${apiPort}`);
      return {
        page,
        consoleGuard,
        close: async () => {
          consoleGuard.detach();
          await context.close();
        },
      };
    };

    const ctx = { openPage, actionTimeoutMs, logStep };
    if (!onlyScenario || onlyScenario === 'gallery') {
      await t.test('ui e2e: gallery add frame and generate image', async () => runGalleryScenario(ctx));
    }
    if (!onlyScenario || onlyScenario === 'mobile') {
      await t.test('ui e2e: mobile frame description edit actions stay visible', async () => runMobileActionsScenario(ctx));
    }
    if (!onlyScenario || onlyScenario === 'roster-persist') {
      await t.test('ui e2e: roster character description persists after reload', async () => runRosterPersistScenario(ctx));
    }
    if (!onlyScenario || onlyScenario === 'roster-generate') {
      await t.test('ui e2e: roster add character and generate image', async () => runRosterGenerateScenario(ctx));
    }
  } catch (err) {
    throw new Error(`${String(err.message || err)}\n\nRecent app logs:\n${appLogs.get()}`);
  } finally {
    if (browser) {
      await browser.close();
    }
    await stopProcess(app);
    killByPattern('func start --script-root src/host');
    killByPattern('http-server dist/release/webapp -p');
  }
});
