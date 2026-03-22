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

    const characterName = 'Seed Character';
    const initialDesc = 'Initial alias text';
    const updatedDesc = `Updated character description ${Date.now()}`;

    logStep('roster-persist', 'waiting for roster page');
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    const characterBlock = page.locator('.sequence', { hasText: characterName }).first();
    await characterBlock.waitFor({ timeout: actionTimeoutMs });
    await characterBlock.locator('.chapter-description', { hasText: initialDesc }).waitFor({ timeout: actionTimeoutMs });
    const descField = characterBlock.locator('.chapter-description-input').first();
    const updateResponse = page.waitForResponse(
      (response) => response.url().includes('/api/entity/') && response.request().method() === 'PATCH',
      { timeout: actionTimeoutMs }
    );
    await descField.click();
    await descField.fill(updatedDesc);
    await page.locator('.roster-page').click({ position: { x: 10, y: 10 } });
    await updateResponse;

    const updatedBlock = page.locator('.sequence', { hasText: updatedDesc }).first();
    await updatedBlock.waitFor({ timeout: actionTimeoutMs });
    await updatedBlock.locator('.chapter-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

    logStep('roster-persist', 'reloading page to verify persistence');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.locator('.roster-page').waitFor({ timeout: actionTimeoutMs });

    const reloadedBlock = page.locator('.sequence', { hasText: characterName }).first();
    await reloadedBlock.waitFor({ timeout: actionTimeoutMs });
    await reloadedBlock.locator('.chapter-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

    logStep('roster-persist', 'persistence verified');
    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
