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

    const stamp = Date.now();
    const characterName = `Bill ${stamp}`;

    logStep('roster-generate', 'waiting for roster page');
    await page.locator('.collection-page').waitFor({ timeout: actionTimeoutMs });

    await page.locator('.add-frame-tile', { hasText: 'Add new' }).first().click();
    logStep('roster-generate', 'character created');

    const chapter = page.locator('.sequence-block').first();
    await chapter.waitFor({ timeout: actionTimeoutMs });

    // Inline edit the name
    const nameField = chapter.locator('.sequence-title-input').first();
    await chapter.locator('.sequence-title').first().click();
    await nameField.fill(characterName);
    await page.locator('.collection-page').click({ position: { x: 10, y: 10 } });

    const frames = chapter.locator('.gallery .frame-panel[data-frame-id]');
    const beforeCount = await frames.count();
    await chapter.locator('.add-frame-tile[aria-label="Add new"]').click();
    logStep('roster-generate', 'waiting for new frame');
    await page.waitForFunction(
      ({ chapterText, expected }) => {
        const chapterEl = Array.from(document.querySelectorAll('.sequence-block'))
          .find((el) => String(el.textContent || '').includes(chapterText));
        if (!chapterEl) return false;
        return chapterEl.querySelectorAll('.gallery .frame-panel[data-frame-id]').length >= expected;
      },
      { chapterText: characterName, expected: beforeCount + 1 },
      { timeout: actionTimeoutMs }
    );

    const newFrame = chapter.locator('.gallery .frame-panel[data-frame-id]').nth(beforeCount);
    await newFrame.waitFor({ timeout: actionTimeoutMs });
    const frameId = await newFrame.getAttribute('data-frame-id');
    assert.ok(frameId, 'new roster frame should expose data-frame-id');

    await newFrame.locator('.subtitle-display-text').click();
    await newFrame.locator('.subtitle-display-input').fill('bill');
    logStep('roster-generate', 'triggering image generation');
    await newFrame.getByRole('button', { name: 'Generate image', exact: true }).click();

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
