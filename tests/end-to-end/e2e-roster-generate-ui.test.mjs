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

test('ui e2e: roster add character and generate image', { skip: !shouldRun }, async (t) => {
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

  const webappPort = await getFreePort(Number(process.env.ROBOGENE_E2E_UI_WEBAPP_PORT || 8087));
  const apiPort = await getFreePort(Number(process.env.ROBOGENE_E2E_UI_API_PORT || 7187));
  const svg = "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><rect width='10' height='10' fill='#1496ff'/></svg>";
  const mockSvgDataUrl = `data:image/svg+xml;base64,${Buffer.from(svg, 'utf8').toString('base64')}`;
  let logs = '';

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

    await page.getByRole('button', { name: 'Roster' }).waitFor({ timeout: actionTimeoutMs });
    await page.getByRole('button', { name: 'Roster' }).click();

    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });
    await page.locator('.add-frame-tile', { hasText: 'Add New Character' }).first().click();
    await page.locator('#new-character-name').fill('Bill');
    await page.getByRole('button', { name: 'Add New Character' }).click();

    const billChapter = page.locator('.chapter-block', { hasText: 'Bill' }).first();
    await billChapter.waitFor({ timeout: actionTimeoutMs });

    const framesInBill = billChapter.locator('.gallery .frame-panel[data-frame-id]');
    const beforeCount = await framesInBill.count();
    await billChapter.locator('.add-frame-tile[aria-label="Add new frame"]').click();
    await page.waitForFunction(
      ({ chapterText, expected }) => {
        const chapter = Array.from(document.querySelectorAll('.chapter-block'))
          .find((el) => String(el.textContent || '').includes(chapterText));
        if (!chapter) return false;
        return chapter.querySelectorAll('.gallery .frame-panel[data-frame-id]').length === expected;
      },
      { chapterText: 'Bill', expected: beforeCount + 1 },
      { timeout: actionTimeoutMs }
    );

    const newFrame = billChapter.locator('.gallery .frame-panel[data-frame-id]').nth(beforeCount);
    await newFrame.waitFor({ timeout: actionTimeoutMs });
    const frameId = await newFrame.getAttribute('data-frame-id');
    assert.ok(frameId, 'new roster frame should expose data-frame-id');

    await newFrame.locator('.subtitle-display').click();
    await newFrame.locator('.prompt-input textarea').fill('bill');
    await newFrame.getByRole('button', { name: 'Generate' }).click();

    await page.waitForFunction(
      (fid) => {
        const el = document.querySelector(`.frame-panel[data-frame-id="${fid}"] img`);
        const src = String(el?.getAttribute('src') || '');
        return !!el && src.length > 0;
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
    killByPattern(`func start --script-root src/host --port ${apiPort}`);
  }
});
