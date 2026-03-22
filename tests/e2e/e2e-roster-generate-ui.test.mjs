import assert from 'node:assert/strict';

const generationTimeoutMs = 30000;

export async function runRosterGenerateScenario({ openPage, actionTimeoutMs, logStep, seedIds }) {
  const { page, consoleGuard, close } = await openPage('roster-generate');
  try {
    logStep('roster-generate', 'navigating to roster page');
    if (!seedIds?.rosterId || !seedIds?.sagaId) {
      throw new Error('Roster ID or Saga ID not found in seed response');
    }
    await page.goto(`/#/roster/${encodeURIComponent(seedIds.rosterId)}?sagaId=${encodeURIComponent(seedIds.sagaId)}`, {
      waitUntil: 'domcontentloaded',
    });

    const characterName = 'Seed Character';

    logStep('roster-generate', 'waiting for roster page');
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    const chapter = page.locator('.sequence', { hasText: characterName }).first();
    await chapter.waitFor({ timeout: actionTimeoutMs });

    const frames = chapter.locator('.gallery .frame-panel[data-frame-id]');
    const beforeCount = await frames.count();
    await chapter.locator('.add-frame-tile[aria-label="New"]').click();
    logStep('roster-generate', 'waiting for new frame');
    const newFrame = chapter.locator('.gallery .frame-panel[data-frame-id]').nth(beforeCount);
    await newFrame.waitFor({ timeout: actionTimeoutMs });
    const frameId = await newFrame.getAttribute('data-frame-id');
    assert.ok(frameId, 'new roster frame should expose data-frame-id');

    const stableFrame = page.locator(`.frame-panel[data-frame-id="${frameId}"]`).first();
    await stableFrame.locator('.subtitle-display-text').click();
    const editableInput = stableFrame.locator('.subtitle-display-input:not([readonly])').first();
    await editableInput.waitFor({ timeout: actionTimeoutMs });
    await editableInput.fill('bill');
    logStep('roster-generate', 'triggering image generation');
    const generateButton = stableFrame.getByRole('button', { name: 'Generate image', exact: true });
    await generateButton.waitFor({ timeout: actionTimeoutMs });
    await generateButton.click({ force: true });

    await page.waitForFunction(
      (fid) => {
        const el = document.querySelector(`.frame-panel[data-frame-id="${fid}"] img`);
        const src = String(el?.getAttribute('src') || '');
        return !!el && src.length > 0;
      },
      frameId,
      { timeout: generationTimeoutMs }
    );

    logStep('roster-generate', 'generation completed');
    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
