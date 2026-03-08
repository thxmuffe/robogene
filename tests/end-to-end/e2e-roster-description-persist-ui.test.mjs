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
    await page.locator('#new-character-name').fill(characterName);
    await page.locator('#new-character-description').fill(initialDesc);
    await page.getByRole('button', { name: 'Add New Character' }).click();
    logStep('roster-persist', 'character created');

    const characterBlock = page.locator('.chapter-block', { hasText: characterName }).first();
    await characterBlock.waitFor({ timeout: actionTimeoutMs });
    await characterBlock.locator('.chapter-description', { hasText: initialDesc }).waitFor({ timeout: actionTimeoutMs });

    await characterBlock.locator('.chapter-menu-trigger').click();
    await page.getByRole('menuitem', { name: 'Edit character' }).click();
    await characterBlock.locator('textarea.chapter-description-input').fill(updatedDesc);
    await characterBlock.getByRole('button', { name: 'Submit' }).click();
    await characterBlock.locator('.chapter-description', { hasText: updatedDesc }).waitFor({ timeout: actionTimeoutMs });

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
