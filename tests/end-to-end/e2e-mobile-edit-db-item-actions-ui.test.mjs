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
      (expected) => document.querySelectorAll('.gallery .frame[data-frame-id]').length === expected,
      beforeCount + 1,
      { timeout: actionTimeoutMs }
    );

    const frame = page.locator('.gallery .frame[data-frame-id]').nth(beforeCount);
    await frame.waitFor({ timeout: actionTimeoutMs });
    logStep('mobile-actions', 'opening frame editor');
    await frame.locator('.subtitle-display').click();

    const submitButton = frame.getByRole('button', { name: 'Submit' });
    const cancelButton = frame.getByRole('button', { name: 'Cancel' });
    await submitButton.waitFor({ timeout: actionTimeoutMs });
    await cancelButton.waitFor({ timeout: actionTimeoutMs });

    assert.equal(await submitButton.isVisible(), true, 'Submit button must be visible on mobile');
    assert.equal(await cancelButton.isVisible(), true, 'Cancel button must be visible on mobile');

    const viewport = page.viewportSize();
    const submitBox = await submitButton.boundingBox();
    const cancelBox = await cancelButton.boundingBox();
    assert.equal(inViewport(submitBox, viewport), true, 'Submit button must be inside mobile viewport');
    assert.equal(inViewport(cancelBox, viewport), true, 'Cancel button must be inside mobile viewport');

    logStep('mobile-actions', 'mobile controls verified');
    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
