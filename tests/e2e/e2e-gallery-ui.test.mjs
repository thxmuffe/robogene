import assert from 'node:assert/strict';
async function clickFrameAction({ page, frame, actionLabel, menuLabel = 'Frame actions' }) {
  const inlineAction = frame.getByRole('button', { name: actionLabel, exact: true });
  if (await inlineAction.count()) {
    await inlineAction.click();
    return;
  }
  await frame.getByRole('button', { name: menuLabel, exact: true }).click();
  await page.getByRole('menuitem', { name: actionLabel, exact: true }).click();
}

async function expandFirstGalleryChapter(page) {
  const toggle = page.locator('.sequence-box-toggle').first();
  await toggle.waitFor();
  await toggle.click();
}

export async function runGalleryScenario({ openPage, actionTimeoutMs, logStep, seedIds }) {
  const { page, consoleGuard, close } = await openPage('gallery');
  try {
    logStep('gallery', 'opening saga page');
    if (!seedIds?.sagaId) throw new Error('Saga ID not found');
    await page.goto(`/#/saga/${encodeURIComponent(seedIds.sagaId)}`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: 'RoboGene' }).waitFor({ timeout: actionTimeoutMs });
    await expandFirstGalleryChapter(page);

    const frames = page.locator('.gallery .frame[data-frame-id]');
    const beforeCount = await frames.count();

    await page.getByRole('button', { name: 'Add New Frame' }).first().click();
    logStep('gallery', 'waiting for new frame');
    await page.waitForFunction(
      (expected) => document.querySelectorAll('.gallery .frame[data-frame-id]').length >= expected,
      beforeCount + 1,
      { timeout: actionTimeoutMs }
    );

    const newFrame = page.locator('.gallery .frame[data-frame-id]').nth(beforeCount);
    await newFrame.waitFor({ timeout: actionTimeoutMs });
    logStep('gallery', 'opening new frame editor');
    await newFrame.locator('.subtitle-display-text').click();

    const generateButton = newFrame.getByRole('button', { name: 'Generate image', exact: true });
    await generateButton.waitFor({ timeout: actionTimeoutMs });
    logStep('gallery', 'triggering image generation');
    await generateButton.click();

    const frameId = await newFrame.getAttribute('data-frame-id');
    assert.ok(frameId, 'new frame should expose data-frame-id');

    await page.waitForFunction(
      (fid) => {
        const el = document.querySelector(`.gallery .frame[data-frame-id="${fid}"] img`);
        return !!el && String(el.getAttribute('src') || '').length > 0;
      },
      frameId,
      { timeout: actionTimeoutMs }
    );
    logStep('gallery', 'generation completed');
    consoleGuard.assertClean();
  } finally {
    await close();
  }
}

const uploadSvg = "<svg xmlns='http://www.w3.org/2000/svg' width='24' height='16'><rect width='24' height='16' fill='#0f6dff'/><circle cx='8' cy='8' r='3' fill='#ffe28a'/></svg>";
const uploadPngLikeFile = {
  name: 'frame-upload.svg',
  mimeType: 'image/svg+xml',
  buffer: Buffer.from(uploadSvg, 'utf8'),
};

export async function runGalleryUploadScenario({ openPage, actionTimeoutMs, logStep, seedIds }) {
  const { page, consoleGuard, close } = await openPage('gallery-upload');
  try {
    logStep('gallery-upload', 'opening saga page');
    if (!seedIds?.sagaId) throw new Error('Saga ID not found');
    await page.goto(`/#/saga/${encodeURIComponent(seedIds.sagaId)}`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: 'RoboGene' }).waitFor({ timeout: actionTimeoutMs });
    await expandFirstGalleryChapter(page);

    const frames = page.locator('.gallery .frame[data-frame-id]');
    const beforeCount = await frames.count();
    await page.getByRole('button', { name: 'Add New Frame' }).first().click();
    logStep('gallery-upload', 'waiting for new frame');
    await page.waitForFunction(
      (expected) => document.querySelectorAll('.gallery .frame[data-frame-id]').length >= expected,
      beforeCount + 1,
      { timeout: actionTimeoutMs }
    );

    const newFrame = page.locator('.gallery .frame[data-frame-id]').nth(beforeCount);
    await newFrame.waitFor({ timeout: actionTimeoutMs });
    await page.waitForFunction(
      (index) => {
        const frames = document.querySelectorAll('.gallery .frame[data-frame-id]');
        const frame = frames[index];
        const frameId = String(frame?.getAttribute('data-frame-id') || '');
        return frameId.length > 0 && !frameId.startsWith('temp-frame-');
      },
      beforeCount,
      { timeout: actionTimeoutMs }
    );
    const frameId = await newFrame.getAttribute('data-frame-id');
    assert.ok(frameId, 'frame should expose stable data-frame-id after add completes');
    const stableFrameById = page.locator(`.gallery .frame[data-frame-id="${frameId}"]`).first();

    logStep('gallery-upload', 'opening frame actions');
    await stableFrameById.locator('.subtitle-display-text').click();
    const uploadButton = stableFrameById.getByRole('button', { name: 'Upload or take picture', exact: true });
    await uploadButton.waitFor({ timeout: actionTimeoutMs });

    logStep('gallery-upload', 'opening upload dialog');
    await uploadButton.click();
    const uploadDialog = page.getByRole('dialog');
    await uploadDialog.waitFor({ timeout: actionTimeoutMs });
    const fileInput = uploadDialog.locator('input.upload-file-input[type="file"]').first();
    const replaceResponse = page.waitForResponse(
      (response) => response.url().includes('/api/entity/') && response.request().method() === 'PATCH',
      { timeout: actionTimeoutMs }
    );
    await fileInput.setInputFiles(uploadPngLikeFile);
    await replaceResponse;
    await uploadDialog.waitFor({ state: 'hidden', timeout: actionTimeoutMs });

    logStep('gallery-upload', 'waiting for uploaded image');
    await page.waitForFunction(
      (fid) => {
        const frameEl = document.querySelector(`.gallery .frame[data-frame-id="${fid}"]`);
        if (!frameEl) return false;
        const img = frameEl.querySelector('img');
        const src = String(img?.getAttribute('src') || '');
        return src.length > 0;
      },
      frameId,
      { timeout: actionTimeoutMs }
    );

    const imgSrc = await stableFrameById.locator('img').getAttribute('src');
    assert.ok(String(imgSrc || '').length > 0, 'uploaded image should remain visible in the frame');

    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
