import assert from 'node:assert/strict';

export async function runGalleryScenario({ openPage, actionTimeoutMs, logStep }) {
  const { page, consoleGuard, close } = await openPage('gallery');
  try {
    logStep('gallery', 'opening gallery');
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: 'RoboGene' }).waitFor({ timeout: actionTimeoutMs });

    const frames = page.locator('.gallery .frame[data-frame-id]');
    const beforeCount = await frames.count();

    await page.getByRole('button', { name: 'Add New Frame' }).first().click();
    logStep('gallery', 'waiting for new frame');
    await page.waitForFunction(
      (expected) => document.querySelectorAll('.gallery .frame[data-frame-id]').length === expected,
      beforeCount + 1,
      { timeout: actionTimeoutMs }
    );

    const newFrame = page.locator('.gallery .frame[data-frame-id]').nth(beforeCount);
    await newFrame.waitFor({ timeout: actionTimeoutMs });
    logStep('gallery', 'opening new frame editor');
    await newFrame.locator('.subtitle-display').click();

    const generateButton = newFrame.getByRole('button', { name: 'Generate' });
    await generateButton.waitFor({ timeout: actionTimeoutMs });
    logStep('gallery', 'triggering image generation');
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
    logStep('gallery', 'generation completed');
    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
