import assert from 'node:assert/strict';

export async function runGalleryScenario({ openPage, actionTimeoutMs, logStep }) {
  const { page, consoleGuard, close } = await openPage('gallery');
  try {
    logStep('gallery', 'opening gallery');
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: 'RoboGene' }).waitFor({ timeout: actionTimeoutMs });

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

    const generateButton = newFrame.getByRole('button', { name: 'Generate' });
    await generateButton.waitFor({ timeout: actionTimeoutMs });
    logStep('gallery', 'triggering image generation');
    await generateButton.click();

    const frameId = await newFrame.getAttribute('data-frame-id');
    assert.ok(frameId, 'new frame should expose data-frame-id');

    await page.waitForFunction(
      (fid) => {
        const el = document.querySelector(`.gallery .frame[data-frame-id="${fid}"] img`);
        return !!el && String(el.getAttribute('src') || '').startsWith('data:image/');
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

export async function runGalleryUploadScenario({ openPage, actionTimeoutMs, logStep }) {
  const { page, consoleGuard, close } = await openPage('gallery-upload');
  try {
    logStep('gallery-upload', 'opening gallery');
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: 'RoboGene' }).waitFor({ timeout: actionTimeoutMs });

    const frames = page.locator('.gallery .frame[data-frame-id]');
    const beforeCount = await frames.count();
    await page.getByRole('button', { name: 'Add New Frame' }).first().click();
    await page.waitForFunction(
      (expected) => document.querySelectorAll('.gallery .frame[data-frame-id]').length >= expected,
      beforeCount + 1,
      { timeout: actionTimeoutMs }
    );

    const newFrame = page.locator('.gallery .frame[data-frame-id]').nth(beforeCount);
    await newFrame.waitFor({ timeout: actionTimeoutMs });
    await newFrame.locator('.subtitle-display-text').click();
    const textarea = newFrame.locator('.subtitle-display-input textarea');
    await textarea.waitFor({ timeout: actionTimeoutMs });

    const stamp = Date.now();
    const updatedDescription = `Uploaded frame description ${stamp}`;
    await textarea.fill(updatedDescription);
    logStep('gallery-upload', 'saving updated description');
    await newFrame.getByRole('button', { name: 'Submit' }).click();

    const stableFrame = page
      .locator('.gallery .frame[data-frame-id]')
      .filter({ has: page.locator('.subtitle-display-text', { hasText: updatedDescription }) })
      .first();
    await stableFrame.waitFor({ timeout: actionTimeoutMs });
    const frameId = await stableFrame.getAttribute('data-frame-id');
    assert.ok(frameId, 'frame should expose stable data-frame-id after add completes');
    const stableFrameById = page.locator(`.gallery .frame[data-frame-id="${frameId}"]`).first();

    logStep('gallery-upload', 'opening upload menu');
    await stableFrameById.locator('.subtitle-display-text').click();
    await stableFrameById.locator('.description-editor-actions-trigger').waitFor({ timeout: actionTimeoutMs });
    await stableFrameById.locator('.description-editor-actions-trigger').click();
    await page.getByRole('menuitem', { name: 'Replace with own photo' }).click();

    const uploadDialog = page.getByRole('dialog');
    await uploadDialog.waitFor({ timeout: actionTimeoutMs });
    const fileInput = uploadDialog.locator('input.upload-file-input[type="file"]').first();
    await fileInput.setInputFiles(uploadPngLikeFile);
    await page.waitForFunction(() => {
      const surface = document.querySelector('.upload-image-surface');
      const backgroundImage = String(surface?.style?.backgroundImage || '');
      return backgroundImage.includes('data:image/svg+xml');
    }, { timeout: actionTimeoutMs });
    const uploadSubmit = uploadDialog.getByRole('button', { name: 'Submit' });
    await uploadSubmit.waitFor({ timeout: actionTimeoutMs });
    await page.waitForFunction(() => {
      const submit = document.querySelector('[role="dialog"] button[aria-label="Submit"]');
      return !!submit && !submit.disabled;
    }, { timeout: actionTimeoutMs });
    await uploadSubmit.click();

    logStep('gallery-upload', 'waiting for uploaded image and description stability');
    await page.waitForFunction(
      ({ fid, expectedDescription }) => {
        const frameEl = document.querySelector(`.gallery .frame[data-frame-id="${fid}"]`);
        if (!frameEl) return false;
        const subtitle = String(frameEl.querySelector('.subtitle-display-text')?.textContent || '').trim();
        const img = frameEl.querySelector('img');
        const src = String(img?.getAttribute('src') || '');
        return subtitle === expectedDescription
          && src.startsWith('data:image/')
          && src.includes('image/svg+xml');
      },
      { fid: frameId, expectedDescription: updatedDescription },
      { timeout: actionTimeoutMs }
    );

    await page.waitForTimeout(1800);
    const subtitleText = await stableFrameById.locator('.subtitle-display-text').textContent();
    assert.equal(String(subtitleText || '').trim(), updatedDescription, 'saved description should remain stable');
    const imgSrc = await stableFrameById.locator('img').getAttribute('src');
    assert.ok(String(imgSrc || '').startsWith('data:image/'), 'uploaded image should remain visible in the frame');

    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
