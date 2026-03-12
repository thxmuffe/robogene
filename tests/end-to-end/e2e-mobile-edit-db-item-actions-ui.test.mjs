import assert from 'node:assert/strict';

function inViewport(box, viewport) {
  if (!box || !viewport) return false;
  return box.x >= 0
    && box.y >= 0
    && box.x + box.width <= viewport.width
    && box.y + box.height <= viewport.height;
}

export async function runMobileActionsScenario({ openPage, actionTimeoutMs, logStep }) {
  const { page, consoleGuard, close } = await openPage('mobile-actions', {
    viewport: { width: 390, height: 844 },
  });
  try {
    logStep('mobile-actions', 'opening gallery on mobile viewport');
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    const frames = page.locator('.gallery .frame[data-frame-id]');
    const beforeCount = await frames.count();
    await page.getByRole('button', { name: 'Add New Frame' }).first().click();
    logStep('mobile-actions', 'waiting for new frame');
    await page.waitForFunction(
      (expected) => document.querySelectorAll('.gallery .frame[data-frame-id]').length >= expected,
      beforeCount + 1,
      { timeout: actionTimeoutMs }
    );

    const frame = page.locator('.gallery .frame[data-frame-id]').nth(beforeCount);
    await frame.waitFor({ timeout: actionTimeoutMs });
    logStep('mobile-actions', 'opening frame editor');
    await frame.locator('.subtitle-display-text').click();

    const generateButton = frame.getByRole('button', { name: 'Generate image', exact: true });
    const uploadButton = frame.getByRole('button', { name: 'Upload or take picture', exact: true });
    await generateButton.waitFor({ timeout: actionTimeoutMs });
    await uploadButton.waitFor({ timeout: actionTimeoutMs });

    assert.equal(await generateButton.isVisible(), true, 'Generate image button must be visible on mobile');
    assert.equal(await uploadButton.isVisible(), true, 'Upload button must be visible on mobile');

    const viewport = page.viewportSize();
    const generateBox = await generateButton.boundingBox();
    const uploadBox = await uploadButton.boundingBox();
    assert.equal(inViewport(generateBox, viewport), true, 'Generate image button must be inside mobile viewport');
    assert.equal(inViewport(uploadBox, viewport), true, 'Upload button must be inside mobile viewport');

    logStep('mobile-actions', 'mobile controls verified');
    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
