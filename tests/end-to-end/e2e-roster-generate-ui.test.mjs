import assert from 'node:assert/strict';

const generationTimeoutMs = 15000;

export async function runRosterGenerateScenario({ openPage, actionTimeoutMs, logStep }) {
  const { page, consoleGuard, close } = await openPage('roster-generate');
  try {
    logStep('roster-generate', 'opening roster');
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    const stamp = Date.now();
    const characterName = `Bill ${stamp}`;

    await page.getByRole('button', { name: 'Open roster' }).waitFor({ timeout: actionTimeoutMs });
    await page.getByRole('button', { name: 'Open roster' }).click();
    logStep('roster-generate', 'roster opened');
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    await page.locator('.add-frame-tile', { hasText: 'Add New Character' }).first().click();
    await page.getByPlaceholder('Name this character...').fill(characterName);
    await page.locator('.new-chapter-panel h3').click();
    await page.waitForTimeout(300);
    await page.locator('.new-chapter-panel').getByRole('button', { name: 'Submit' }).click();
    logStep('roster-generate', 'character created');

    const chapter = page.locator('.chapter-block', { hasText: characterName }).first();
    await chapter.waitFor({ timeout: actionTimeoutMs });

    const frames = chapter.locator('.gallery .frame-panel[data-frame-id]');
    const beforeCount = await frames.count();
    await chapter.locator('.add-frame-tile[aria-label="Add image"]').click();
    logStep('roster-generate', 'waiting for new frame');
    await page.waitForFunction(
      ({ chapterText, expected }) => {
        const chapterEl = Array.from(document.querySelectorAll('.chapter-block'))
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

    await newFrame.locator('.subtitle-display').click();
    await newFrame.locator('.subtitle-display-input textarea').fill('bill');
    logStep('roster-generate', 'triggering image generation');
    await newFrame.getByRole('button', { name: 'Generate' }).click();

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
