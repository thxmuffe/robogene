import assert from 'node:assert/strict';

export async function runSmokeScenario({ openPage, actionTimeoutMs, logStep, seedIds }) {
  const { page, consoleGuard, close } = await openPage('smoke');
  try {
    logStep('smoke', 'navigating to saga page');
    if (!seedIds?.sagaId) {
      throw new Error('Saga ID not found in seed response');
    }
    await page.goto(`/#/saga/${encodeURIComponent(seedIds.sagaId)}`, { waitUntil: 'domcontentloaded' });

    logStep('smoke', 'waiting for RoboGene content');
    await page.getByText('RoboGene').waitFor({ timeout: actionTimeoutMs });

    logStep('smoke', 'expanding first chapter');
    await page.locator('.chapter-separator-toggle').first().click();

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
