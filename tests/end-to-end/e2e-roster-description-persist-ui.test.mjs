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
    await page.locator('.collection-page').waitFor({ timeout: actionTimeoutMs });

    await page.locator('.add-frame-tile', { hasText: 'Add new' }).first().click();
    logStep('roster-persist', 'character created');

    const characterBlock = page.locator('.sequence-block').first();
    await characterBlock.waitFor({ timeout: actionTimeoutMs });
    
    // Inline edit the name and description
    const nameField = characterBlock.locator('.sequence-title-input').first();
    await characterBlock.locator('.sequence-title').first().click();
    await nameField.fill(characterName);
    await page.locator('.collection-page').click({ position: { x: 10, y: 10 } });

    const descField = characterBlock.locator('.sequence-description-input').first();
    await characterBlock.locator('.sequence-description').first().click();
    await descField.fill(initialDesc);
    await page.locator('.collection-page').click({ position: { x: 10, y: 10 } });

    const updateResponse = page.waitForResponse(
      (response) => (response.url().includes('/api/update-character') || response.url().includes('/api/update-entity')) && response.request().method() === 'POST',
      { timeout: actionTimeoutMs }
    );
    await descField.click();
    await descField.fill(updatedDesc);
    await page.locator('.collection-page').click({ position: { x: 10, y: 10 } });
    await updateResponse;

    const updatedBlock = page.locator('.sequence-block', { hasText: updatedDesc }).first();
    await updatedBlock.waitFor({ timeout: actionTimeoutMs });
    await updatedBlock.locator('.sequence-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

    logStep('roster-persist', 'reloading page to verify persistence');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.locator('.collection-page').waitFor({ timeout: actionTimeoutMs });

    const reloadedBlock = page.locator('.sequence-block', { hasText: characterName }).first();
    await reloadedBlock.waitFor({ timeout: actionTimeoutMs });
    await reloadedBlock.locator('.sequence-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

    logStep('roster-persist', 'persistence verified');
    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
