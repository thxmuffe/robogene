export async function runRosterPersistScenario({ openPage, actionTimeoutMs, logStep }) {
  const { page, consoleGuard, close } = await openPage('roster-persist');
  try {
    logStep('roster-persist', 'opening roster');
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    const stamp = Date.now();
    const characterName = `Bill ${stamp}`;
    const initialDesc = 'Initial alias text';
    const updatedDesc = `Updated character description ${stamp}`;

    await page.getByRole('button', { name: 'Open roster' }).waitFor({ timeout: actionTimeoutMs });
    await page.getByRole('button', { name: 'Open roster' }).click();
    logStep('roster-persist', 'roster opened');
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    await page.locator('.add-frame-tile', { hasText: 'Add New Character' }).first().click();
    const createPanel = page.locator('.new-chapter-panel').first();
    await createPanel.getByPlaceholder('Name this character...').fill(characterName);
    await createPanel.getByPlaceholder('Describe aliases, style, and references...').fill(initialDesc);
    await page.locator('.new-chapter-panel h3').click();
    await page.waitForTimeout(300);
    await createPanel.getByRole('button', { name: 'Submit' }).click();
    logStep('roster-persist', 'character created');

    const characterBlock = page.locator('.chapter-block', { hasText: characterName }).first();
    await characterBlock.waitFor({ timeout: actionTimeoutMs });
    await characterBlock.locator('.chapter-description', { hasText: initialDesc }).waitFor({ timeout: actionTimeoutMs });
    const descField = characterBlock.locator('.chapter-description-input').first();
    const updateResponse = page.waitForResponse(
      (response) => response.url().includes('/api/update-character') && response.request().method() === 'POST',
      { timeout: actionTimeoutMs }
    );
    await descField.click();
    await descField.fill(updatedDesc);
    await page.locator('.roster-page').click({ position: { x: 10, y: 10 } });
    await updateResponse;

    const updatedBlock = page.locator('.chapter-block', { hasText: updatedDesc }).first();
    await updatedBlock.waitFor({ timeout: actionTimeoutMs });
    await updatedBlock.locator('.chapter-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

    logStep('roster-persist', 'reloading page to verify persistence');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    const reloadedBlock = page.locator('.chapter-block', { hasText: characterName }).first();
    await reloadedBlock.waitFor({ timeout: actionTimeoutMs });
    await reloadedBlock.locator('.chapter-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

    logStep('roster-persist', 'persistence verified');
    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
