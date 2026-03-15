export async function runRosterPersistScenario({ openPage, actionTimeoutMs, logStep, seedIds }) {
  const { page, consoleGuard, close } = await openPage('roster-persist');
  try {
    logStep('roster-persist', 'navigating to roster page');
    if (!seedIds?.rosterId || !seedIds?.sagaId) {
      throw new Error('Roster ID or Saga ID not found in seed response');
    }
    await page.goto(`/#/roster/${encodeURIComponent(seedIds.rosterId)}?sagaId=${encodeURIComponent(seedIds.sagaId)}`, {
      waitUntil: 'domcontentloaded',
    });

    const stamp = Date.now();
    const characterName = `Bill ${stamp}`;
    const initialDesc = 'Initial alias text';
    const updatedDesc = `Updated character description ${stamp}`;

    logStep('roster-persist', 'waiting for roster page');
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    await page.locator('.add-frame-tile', { hasText: 'Add new' }).first().click();
    const createPanel = page.locator('.new-chapter-panel').first();
    await createPanel.getByRole('button', { name: 'Add new' }).click();
    logStep('roster-persist', 'character created');

    const characterBlock = page.locator('.chapter-block').first();
    await characterBlock.waitFor({ timeout: actionTimeoutMs });
    
    // Inline edit the name and description
    const nameField = characterBlock.locator('.chapter-name-input').first();
    await characterBlock.locator('.chapter-name').first().click();
    await nameField.fill(characterName);
    await page.locator('.roster-page').click({ position: { x: 10, y: 10 } });

    const descField = characterBlock.locator('.chapter-description-input').first();
    await characterBlock.locator('.chapter-description').first().click();
    await descField.fill(initialDesc);
    await page.locator('.roster-page').click({ position: { x: 10, y: 10 } });

    const updateResponse = page.waitForResponse(
      (response) => (response.url().includes('/api/update-character') || response.url().includes('/api/update-entity')) && response.request().method() === 'POST',
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
