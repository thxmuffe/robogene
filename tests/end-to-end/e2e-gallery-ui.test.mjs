import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import { getFreePort } from '../shared/ports.mjs';
import { commandAvailable, killByPattern } from '../shared/system.mjs';
import { stopProcess, waitForHttpOk } from '../shared/async.mjs';
import { attachConsoleFailureGuard } from '../shared/playwright.mjs';

const shouldRun = process.env.ROBOGENE_RUN_E2E_UI === '1';
const startupTimeoutMs = Number(process.env.ROBOGENE_E2E_UI_STARTUP_TIMEOUT_MS || 90000);
const actionTimeoutMs = Number(process.env.ROBOGENE_E2E_UI_ACTION_TIMEOUT_MS || 45000);

test('ui e2e: gallery add frame and generate image', { skip: !shouldRun }, async (t) => {
  if (!commandAvailable('func')) {
    t.skip('Azure Functions Core Tools (`func`) not found.');
    return;
  }
  if (!fs.existsSync('robogen.debug.env')) {
    t.skip('robogen.debug.env is required to run npm start:release.');
    return;
  }

  let chromium = null;
  try {
    ({ chromium } = await import('playwright'));
  } catch {
    t.skip('Playwright not installed. Run: npm i -D playwright && npx playwright install chromium');
    return;
  }

  const webappPort = await getFreePort(Number(process.env.ROBOGENE_E2E_UI_WEBAPP_PORT || 8086));
  const apiPort = await getFreePort(Number(process.env.ROBOGENE_E2E_UI_API_PORT || 7186));
  let logs = '';

  const app = spawn('node', ['scripts/run-robogene.js', '--release'], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      WEBAPP_PORT: String(webappPort),
      WEBAPI_PORT: String(apiPort),
      FUNCTIONS_WORKER_RUNTIME: 'node',
      ROBOGENE_ALLOWED_ORIGIN: `http://localhost:${webappPort},http://127.0.0.1:${webappPort}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  const appendLog = (chunk) => {
    logs += chunk.toString();
    if (logs.length > 20000) logs = logs.slice(-20000);
  };
  app.stdout.on('data', appendLog);
  app.stderr.on('data', appendLog);

  let browser = null;
  let consoleGuard = null;
  try {
    await waitForHttpOk(`http://localhost:${webappPort}/index.html`, startupTimeoutMs);
    await waitForHttpOk(`http://localhost:${apiPort}/api/state`, startupTimeoutMs);

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    consoleGuard = attachConsoleFailureGuard(page, {
      ignore: [
        'Download the React DevTools for a better development experience',
      ],
    });
    await page.addInitScript((base) => {
      window.ROBOGENE_API_BASE = base;
    }, `http://localhost:${apiPort}`);
    await page.goto(`http://localhost:${webappPort}/index.html`, { waitUntil: 'domcontentloaded' });

    await page.getByRole('heading', { name: 'RoboGene' }).waitFor({ timeout: actionTimeoutMs });

    const frames = page.locator('.gallery .frame');
    const beforeCount = await frames.count();
    assert.ok(beforeCount > 0, 'gallery should contain at least one frame');

    await page.getByRole('button', { name: 'Add New Frame' }).first().click();
    await page.waitForFunction(
      (expected) => document.querySelectorAll('.gallery .frame').length === expected,
      beforeCount + 1,
      { timeout: actionTimeoutMs }
    );

    const newFrame = page.locator('.gallery .frame').nth(beforeCount);
    await newFrame.waitFor({ timeout: actionTimeoutMs });

    const generateButton = newFrame.getByRole('button', { name: 'Generate frame' });
    await generateButton.waitFor({ timeout: actionTimeoutMs });
    await generateButton.click();

    const frameId = await newFrame.getAttribute('data-frame-id');
    assert.ok(frameId, 'new frame should expose data-frame-id');

    await page.waitForFunction(
      (fid) => {
        const el = document.querySelector(`.gallery .frame[data-frame-id="${fid}"] img`);
        return !!el && String(el.getAttribute('src') || '').startsWith('data:image/');
      },
      frameId,
      { timeout: actionTimeoutMs }
    );
    consoleGuard.assertClean();
  } catch (err) {
    throw new Error(`${String(err.message || err)}\n\nRecent app logs:\n${logs}`);
  } finally {
    if (consoleGuard) {
      consoleGuard.detach();
    }
    if (browser) {
      await browser.close();
    }
    await stopProcess(app);
    killByPattern(`func start --script-root src/api_host --port ${apiPort}`);
  }
});
