import assert from 'node:assert/strict';

export async function runSmokeScenario({ openPage, actionTimeoutMs, logStep }) {
  const { page, consoleGuard, close } = await openPage('smoke');
  try {
    logStep('smoke', 'opening gallery');
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: 'RoboGene' }).waitFor({ timeout: actionTimeoutMs });

    logStep('smoke', 'waiting for first frame');
    await page.waitForFunction(
      () => document.querySelectorAll('.gallery .frame[data-frame-id]').length > 0,
      { timeout: actionTimeoutMs }
    );

    const frames = page.locator('.gallery .frame[data-frame-id]');
    assert.notEqual(await frames.count(), 0, 'gallery should contain at least one frame');
  } finally {
    consoleGuard.detach();
    await close();
  }
}
