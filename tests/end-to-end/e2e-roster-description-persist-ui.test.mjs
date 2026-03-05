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

test('ui e2e: roster character description persists after reload', { skip: !shouldRun }, async (t) => {
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

  const webappPort = await getFreePort(Number(process.env.ROBOGENE_E2E_UI_WEBAPP_PORT || 8088));
  const apiPort = await getFreePort(Number(process.env.ROBOGENE_E2E_UI_API_PORT || 7188));
  let logs = '';

  const app = spawn('npm', ['run', 'start:release'], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      WEBAPP_PORT: String(webappPort),
      WEBAPI_PORT: String(apiPort),
      FUNCTIONS_WORKER_RUNTIME: 'node',
      ROBOGENE_IMAGE_GENERATOR: 'mock',
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

    const stamp = Date.now();
    const characterName = `Bill ${stamp}`;
    const initialDesc = 'Initial alias text';
    const updatedDesc = `Updated character description ${stamp}`;

    await page.getByRole('button', { name: 'Roster' }).waitFor({ timeout: actionTimeoutMs });
    await page.getByRole('button', { name: 'Roster' }).click();
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    await page.locator('.add-frame-tile', { hasText: 'Add New Character' }).first().click();
    await page.locator('#new-character-name').fill(characterName);
    await page.locator('#new-character-description').fill(initialDesc);
    await page.getByRole('button', { name: 'Add New Character' }).click();

    const characterBlock = page.locator('.chapter-block', { hasText: characterName }).first();
    await characterBlock.waitFor({ timeout: actionTimeoutMs });
    await characterBlock.locator('.chapter-description', { hasText: initialDesc }).waitFor({ timeout: actionTimeoutMs });

    await characterBlock.locator('.chapter-menu-trigger').click();
    await page.getByRole('menuitem', { name: 'Edit character' }).click();
    await characterBlock.locator('textarea.chapter-description-input').fill(updatedDesc);
    await characterBlock.getByRole('button', { name: 'Save name' }).click();

    await characterBlock.locator('.chapter-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    const reloadedBlock = page.locator('.chapter-block', { hasText: characterName }).first();
    await reloadedBlock.waitFor({ timeout: actionTimeoutMs });
    await reloadedBlock.locator('.chapter-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

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
    killByPattern(`func start --script-root src/host --port ${apiPort}`);
  }
});
