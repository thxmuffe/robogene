import assert from 'node:assert/strict';
import { BlobServiceClient } from '@azure/storage-blob';

const storageConnectionString = process.env.ROBOGENE_STORAGE_CONNECTION_STRING
  || process.env.AzureWebJobsStorage
  || 'UseDevelopmentStorage=true';
const imageContainer = BlobServiceClient
  .fromConnectionString(storageConnectionString, { serviceVersion: '2021-12-02' })
  .getContainerClient('robogene-images');

async function waitForCondition(check, { timeoutMs, intervalMs = 100 } = {}) {
  const startedAt = Date.now();
  for (;;) {
    if (await check()) return true;
    if (Date.now() - startedAt >= timeoutMs) {
      throw new Error(`Condition not met within ${timeoutMs}ms.`);
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

async function listFrameBlobNames(frameId) {
  const names = [];
  try {
    for await (const blob of imageContainer.listBlobsFlat()) {
      if (String(blob.name || '').includes(`${frameId}.`)) {
        names.push(blob.name);
      }
    }
  } catch (error) {
    if (error?.statusCode === 404 || error?.details?.errorCode === 'ContainerNotFound') {
      return [];
    }
    throw error;
  }
  return names;
}

async function maybeListFrameBlobNames(frameId) {
  try {
    return await listFrameBlobNames(frameId);
  } catch {
    return [];
  }
}

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
  const toggle = page.locator('.chapter-separator-toggle').first();
  await toggle.waitFor();
  await toggle.click();
}

export async function runGalleryScenario({ openPage, actionTimeoutMs, logStep }) {
  const { page, consoleGuard, close } = await openPage('gallery');
  try {
    logStep('gallery', 'opening gallery');
    await page.goto('/', { waitUntil: 'domcontentloaded' });
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
    await expandFirstGalleryChapter(page);

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
    await newFrame.locator('.subtitle-display-text').click();
    const textarea = newFrame.locator('.subtitle-display-input');
    await textarea.waitFor({ timeout: actionTimeoutMs });

    const stamp = Date.now();
    const updatedDescription = `Uploaded frame description ${stamp}`;
    await textarea.fill(updatedDescription);
    logStep('gallery-upload', 'saving updated description');
    await page.getByRole('heading', { name: 'RoboGene' }).click();
    const stableFrameById = page.locator(`.gallery .frame[data-frame-id="${frameId}"]`).first();
    await page.waitForFunction(
      ({ fid, expectedDescription }) => {
        const frameEl = document.querySelector(`.gallery .frame[data-frame-id="${fid}"]`);
        const subtitle = String(frameEl?.querySelector('.subtitle-display-text')?.textContent || '').trim();
        return subtitle === expectedDescription;
      },
      { fid: frameId, expectedDescription: updatedDescription },
      { timeout: actionTimeoutMs }
    );

    logStep('gallery-upload', 'opening upload dialog');
    await stableFrameById.locator('.subtitle-display-text').click();
    const uploadButton = stableFrameById.getByRole('button', { name: 'Upload or take picture', exact: true });
    await uploadButton.waitFor({ timeout: actionTimeoutMs });
    await uploadButton.click();

    const uploadDialog = page.getByRole('dialog');
    await uploadDialog.waitFor({ timeout: actionTimeoutMs });
    const fileInput = uploadDialog.locator('input.upload-file-input[type="file"]').first();
    const replaceResponse = page.waitForResponse(
      (response) => response.url().includes('/api/replace-frame-image') && response.request().method() === 'POST',
      { timeout: actionTimeoutMs }
    );
    await fileInput.setInputFiles(uploadPngLikeFile);
    await replaceResponse;
    await uploadDialog.waitFor({ state: 'hidden', timeout: actionTimeoutMs });

    logStep('gallery-upload', 'waiting for uploaded image and description stability');
    await page.waitForFunction(
      ({ fid, expectedDescription }) => {
        const frameEl = document.querySelector(`.gallery .frame[data-frame-id="${fid}"]`);
        if (!frameEl) return false;
        const subtitle = String(frameEl.querySelector('.subtitle-display-text')?.textContent || '').trim();
        const img = frameEl.querySelector('img');
        const src = String(img?.getAttribute('src') || '');
        return subtitle === expectedDescription
          && src.length > 0;
      },
      { fid: frameId, expectedDescription: updatedDescription },
      { timeout: actionTimeoutMs }
    );

    await page.waitForTimeout(1800);
    const subtitleText = await stableFrameById.locator('.subtitle-display-text').textContent();
    assert.equal(String(subtitleText || '').trim(), updatedDescription, 'saved description should remain stable');
    const imgSrc = await stableFrameById.locator('img').getAttribute('src');
    assert.ok(String(imgSrc || '').length > 0, 'uploaded image should remain visible in the frame');
    const uploadedBlobNames = await maybeListFrameBlobNames(frameId);

    logStep('gallery-upload', 'removing uploaded image');
    await stableFrameById.locator('.subtitle-display-text').click();
    await clickFrameAction({ page, frame: stableFrameById, actionLabel: 'Remove image' });
    const confirmDialog = page.getByRole('dialog').filter({ hasText: 'Remove image from this frame?' }).first();
    await confirmDialog.waitFor({ timeout: actionTimeoutMs });
    const clearResponse = page.waitForResponse(
      (response) => response.url().includes('/api/clear-frame-image') && response.request().method() === 'POST',
      { timeout: actionTimeoutMs }
    );
    await confirmDialog.getByRole('button', { name: 'Remove image', exact: true }).click();
    await clearResponse;
    await page.waitForFunction(
      (fid) => {
        const frameEl = document.querySelector(`.gallery .frame[data-frame-id="${fid}"]`);
        return !!frameEl && !frameEl.querySelector('img');
      },
      frameId,
      { timeout: actionTimeoutMs }
    );
    if (uploadedBlobNames.length > 0) {
      await waitForCondition(
        async () => (await listFrameBlobNames(frameId)).length === 0,
        { timeoutMs: actionTimeoutMs }
      );
    }

    consoleGuard.assertClean();
  } finally {
    await close();
  }
}
