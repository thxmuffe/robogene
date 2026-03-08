import fs from 'node:fs';
import { spawn, spawnSync } from 'node:child_process';
import test from 'node:test';
import path from 'node:path';
import { waitForHttpOk } from '../shared/async.mjs';
import { attachConsoleFailureGuard } from '../shared/playwright.mjs';
import { getFreePort } from '../shared/ports.mjs';
import { commandAvailable, killByPattern } from '../shared/system.mjs';
import { runGalleryScenario } from './e2e-gallery-ui.test.mjs';
import { runMobileActionsScenario } from './e2e-mobile-edit-db-item-actions-ui.test.mjs';
import { runRosterPersistScenario } from './e2e-roster-description-persist-ui.test.mjs';
import { runRosterGenerateScenario } from './e2e-roster-generate-ui.test.mjs';
import { runSmokeScenario } from './e2e-smoke-ui.test.mjs';

const shouldRun = process.env.ROBOGENE_RUN_E2E_UI === '1';
const startupTimeoutMs = 90000;
const actionTimeoutMs = 5000;
const shouldRunHeadless = process.env.ROBOGENE_E2E_HEADLESS === '1' || process.env.CI === 'true';
const shouldUsePrebuilt = process.env.ROBOGENE_E2E_USE_PREBUILT === '1';
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

function runNpmCommand(args, env) {
  const result = spawnSync('npm', args, {
    cwd: process.cwd(),
    env,
    stdio: 'inherit',
  });
  if (result.status !== 0) {
    throw new Error(`npm ${args.join(' ')} failed with exit code ${result.status}.`);
  }
}

async function stopAzureFunctionsHost() {
  if (!commandAvailable('pkill')) return;
  spawnSync('pkill', ['-INT', '-f', 'func start --script-root'], { stdio: 'ignore' });
  await new Promise((resolve) => setTimeout(resolve, 1000));
  spawnSync('pkill', ['-KILL', '-f', 'func start --script-root'], { stdio: 'ignore' });
}

async function stopChild(child) {
  if (!child || child.exitCode !== null) return;
  try {
    process.kill(-child.pid, 'SIGKILL');
  } catch {
    try {
      child.kill('SIGKILL');
    } catch {}
  }
  await new Promise((resolve) => {
    child.once('exit', resolve);
    setTimeout(resolve, 1000);
  });
}

async function forceStopApp(children) {
  await Promise.all((children || []).map((child) => stopChild(child)));
}

async function runSeedScript({ apiBase, fixturePath, logStep }) {
  if (!fixturePath || !fs.existsSync(fixturePath)) return;
  logStep('seed', `running ${fixturePath}`);
  const script = spawn(process.platform === 'win32' ? 'npx.cmd' : 'npx', ['httpyac', 'send', path.resolve(fixturePath), '--all'], {
    cwd: process.cwd(),
    env: { ...process.env, ROBOGENE_API_BASE: apiBase },
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  const exitCode = await new Promise((resolve, reject) => {
    script.on('exit', resolve);
    script.on('error', reject);
  });
  if (exitCode !== 0) {
    throw new Error(`Seed script failed with exit code ${exitCode}.`);
  }
  logStep('seed', 'done');
}

function spawnLoggedProcess(command, args, options, appLogs) {
  const child = spawn(command, args, {
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
    ...options,
  });

  child.stdout.on('data', (chunk) => {
    process.stdout.write(chunk);
    appLogs.append(chunk);
  });
  child.stderr.on('data', (chunk) => {
    process.stderr.write(chunk);
    appLogs.append(chunk);
  });

  return child;
}

test('ui e2e suite', { skip: !shouldRun, concurrency: false }, async (t) => {
  if (!commandAvailable('func')) {
    t.skip('Azure Functions Core Tools (`func`) not found.');
    return;
  }
  const playwright = await loadPlaywright(t);
  if (!playwright) return;

  const webappPort = await getFreePort(0);
  const apiPort = await getFreePort(0);

  const appLogs = createAppLogger();
  const appEnv = {
    ...process.env,
    WEBAPP_PORT: String(webappPort),
    WEBAPI_PORT: String(apiPort),
    FUNCTIONS_WORKER_RUNTIME: 'node',
    ROBOGENE_IMAGE_GENERATOR: 'mock',
    ROBOGENE_IMAGE_GENERATOR_MOCK_DATA_URL: mockSvgDataUrl,
    ROBOGENE_IMAGE_GENERATOR_MOCK_DELAY_MS: '10',
    ROBOGENE_ALLOWED_ORIGIN: `http://localhost:${webappPort},http://127.0.0.1:${webappPort}`,
  };

  let webRoot = path.resolve('dist/release/webapp');
  let apiScriptRoot = path.resolve('src/host');
  if (shouldUsePrebuilt) {
    webRoot = path.resolve(process.env.ROBOGENE_E2E_WEBAPP_DIR || webRoot);
    apiScriptRoot = path.resolve(process.env.ROBOGENE_E2E_WEBAPI_SCRIPT_ROOT || apiScriptRoot);
  } else {
    await stopAzureFunctionsHost();
    killByPattern('http-server dist/release/webapp -p');
    runNpmCommand(['run', 'stop:dev'], appEnv);
    runNpmCommand(['run', 'build:webapi:debug'], appEnv);
    runNpmCommand(['run', 'build'], appEnv);
  }

  if (!fs.existsSync(path.join(webRoot, 'index.html'))) {
    throw new Error(`Missing webapp build output at ${webRoot}.`);
  }
  if (!fs.existsSync(path.join(apiScriptRoot, 'host.json'))) {
    throw new Error(`Missing webapi host.json at ${apiScriptRoot}.`);
  }

  const httpServerCmd = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const webApp = spawnLoggedProcess(
    httpServerCmd,
    ['http-server', webRoot, '-p', String(webappPort), '-c-1'],
    { cwd: process.cwd(), env: appEnv, shell: false },
    appLogs
  );
  const apiApp = spawnLoggedProcess(
    process.platform === 'win32' ? 'func.cmd' : 'func',
    ['start', '--script-root', apiScriptRoot, '--port', String(apiPort), '--cors', appEnv.ROBOGENE_ALLOWED_ORIGIN],
    { cwd: apiScriptRoot, env: appEnv, shell: false },
    appLogs
  );
  const app = [webApp, apiApp];

  let browser = null;
  let suiteFailed = false;
  try {
    logStep('suite', `starting shared app on web:${webappPort} api:${apiPort}`);
    await waitForHttpOk(`http://localhost:${webappPort}/index.html`, startupTimeoutMs);
    logStep('suite', 'web app is reachable');
    await waitForHttpOk(`http://localhost:${apiPort}/api/state`, startupTimeoutMs);
    logStep('suite', 'api is reachable');
    await runSeedScript({
      apiBase: `http://localhost:${apiPort}`,
      fixturePath: process.env.ROBOGENE_E2E_SEED_FILE,
      logStep,
    });

    browser = await playwright.chromium.launch({ headless: shouldRunHeadless });
    logStep('suite', 'browser launched');

    const openPage = async (scope, options = {}) => {
      const context = await browser.newContext({
        ...options,
        baseURL: `http://localhost:${webappPort}`,
      });
      context.setDefaultTimeout(actionTimeoutMs);
      context.setDefaultNavigationTimeout(actionTimeoutMs);
      const page = await context.newPage();
      page.setDefaultTimeout(actionTimeoutMs);
      page.setDefaultNavigationTimeout(actionTimeoutMs);
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
    let smokeError = null;
    await t.test('ui e2e: smoke app boots and shows first frame', async () => {
      try {
        await runSmokeScenario(ctx);
      } catch (err) {
        smokeError = err;
        throw err;
      }
    });
    await t.test('ui e2e: gallery add frame and generate image', async () => {
      await runGalleryScenario(ctx);
    });
    await t.test('ui e2e: mobile frame description edit actions stay visible', async () => {
      await runMobileActionsScenario(ctx);
    });
    await t.test('ui e2e: roster character description persists after reload', async () => {
      await runRosterPersistScenario(ctx);
    });
    await t.test('ui e2e: roster add character and generate image', async () => {
      await runRosterGenerateScenario(ctx);
    });
    if (smokeError) {
      throw smokeError;
    }
  } catch (err) {
    suiteFailed = true;
    throw new Error(`${String(err.message || err)}\n\nRecent app logs:\n${appLogs.get()}`);
  } finally {
    if (browser) {
      await browser.close();
    }
    await forceStopApp(app);
    if (!shouldUsePrebuilt) {
      await stopAzureFunctionsHost();
      killByPattern('http-server dist/release/webapp -p');
    }
    if (suiteFailed) {
      process.nextTick(() => process.exit(1));
    }
  }
});
