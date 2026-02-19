const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app } = require('@azure/functions');

const BACKEND_ROOT = path.resolve(__dirname, '..', '..');
const ASSETS = path.join(BACKEND_ROOT, 'assets');

const DEFAULT_STORYBOARD = path.join(ASSETS, '28_Municipal_Firmware_script.md');
const DEFAULT_PROMPTS = path.join(ASSETS, '28_Municipal_Firmware_image_prompts.md');
const DEFAULT_REFERENCE_IMAGE = path.join(ASSETS, 'robot_emperor_ep22_p01.png');
const PAGE1_REFERENCE_IMAGE = path.join(ASSETS, '28_page_01_openai_refined.png');

function readText(filePath) {
  try {
    return fs.readFileSync(filePath, 'utf8');
  } catch (_) {
    return '';
  }
}

function readBytes(filePath) {
  try {
    return fs.readFileSync(filePath);
  } catch (_) {
    return null;
  }
}

function parseBeats(markdown) {
  const sectionMatch = markdown.match(/##\s*Page-by-page beats[\s\S]*?(?:\n##\s|$)/i);
  const target = sectionMatch ? sectionMatch[0] : markdown;
  const beats = [];
  for (const line of target.split(/\r?\n/)) {
    const m = line.match(/^\s*(\d+)\.\s+(.+)$/);
    if (m) beats.push({ index: Number(m[1]), text: m[2].trim() });
  }
  return beats.sort((a, b) => a.index - b.index);
}

function parseVisualPrompts(markdown) {
  let globalStyle = '';
  const g = markdown.match(/##\s*Global style prompt.*?\n([^\n]+)/i);
  if (g) globalStyle = g[1].trim();

  const pagePrompts = {};
  const sectionMatch = markdown.match(/##\s*Page prompts[\s\S]*/i);
  const target = sectionMatch ? sectionMatch[0] : markdown;
  for (const line of target.split(/\r?\n/)) {
    const m = line.match(/^\s*(\d+)\.\s+(.+)$/);
    if (m) pagePrompts[Number(m[1])] = m[2].trim();
  }
  return { globalStyle, pagePrompts };
}

function pngDataUrl(buffer) {
  return `data:image/png;base64,${buffer.toString('base64')}`;
}

const state = {
  storyId: null,
  beats: [],
  visual: { globalStyle: '', pagePrompts: {} },
  history: [],
  pendingJobs: [],
  failedJobs: [],
  cursor: 2,
  processing: false,
  revision: 0,
  model: process.env.ROBOGENE_IMAGE_MODEL || 'gpt-image-1',
  referenceImageBytes: null,
};
const stateWaiters = [];

function sceneBeatText(sceneNumber) {
  const beat = state.beats.find((b) => b.index === sceneNumber);
  return beat ? beat.text : `Scene ${sceneNumber}`;
}

function defaultDirectionText(sceneNumber) {
  const beatText = sceneBeatText(sceneNumber);
  const pagePrompt = state.visual.pagePrompts[sceneNumber] || '';
  return [
    beatText,
    pagePrompt,
    'Keep continuity with previous scenes.',
  ]
    .filter(Boolean)
    .join('\n');
}

function initializeState() {
  const storyboardText = readText(DEFAULT_STORYBOARD);
  const promptsText = readText(DEFAULT_PROMPTS);

  state.storyId = crypto.randomUUID();
  state.beats = parseBeats(storyboardText);
  state.visual = parseVisualPrompts(promptsText);
  state.history = [];
  state.pendingJobs = [];
  state.failedJobs = [];
  state.cursor = 2;
  state.processing = false;
  state.revision = 1;

  const ref = readBytes(DEFAULT_REFERENCE_IMAGE);
  if (ref) state.referenceImageBytes = ref;

  const page1 = readBytes(PAGE1_REFERENCE_IMAGE);
  if (page1) {
    state.history.push({
      sceneNumber: 1,
      beatText: sceneBeatText(1),
      continuityNote: sceneBeatText(1),
      imageDataUrl: pngDataUrl(page1),
      reference: true,
      createdAt: new Date().toISOString(),
    });
  }
}

initializeState();

function clearStateWaiter(waiter) {
  const idx = stateWaiters.indexOf(waiter);
  if (idx >= 0) stateWaiters.splice(idx, 1);
}

function notifyStateChanged() {
  if (!stateWaiters.length) return;
  const waiters = stateWaiters.splice(0, stateWaiters.length);
  for (const waiter of waiters) {
    clearTimeout(waiter.timerId);
    waiter.resolve(json(200, { changed: true, revision: state.revision }, waiter.request));
  }
}

function bumpRevision() {
  state.revision += 1;
  notifyStateChanged();
}

function parseIntQuery(request, name, fallback) {
  try {
    const raw = new URL(request.url).searchParams.get(name);
    if (raw == null || raw === '') return fallback;
    const n = Number(raw);
    return Number.isFinite(n) ? n : fallback;
  } catch (_) {
    return fallback;
  }
}

function continuityWindow(limit = 6) {
  const tail = state.history.slice(-limit);
  if (!tail.length) return 'No previous scenes yet.';
  return tail.map((s) => `Scene ${s.sceneNumber}: ${s.beatText}.`).join('\n');
}

function buildPromptForScene(sceneNumber, beatText, directionText) {
  const globalStyle = state.visual.globalStyle || '';
  const soFar = continuityWindow();

  return [
    'Create ONE comic story image for the next scene.',
    'Character lock: Robot Emperor must match the attached reference identity (powdered white wig with side curls, pale robotic face, cyan glowing eyes, red cape with blue underlayer).',
    globalStyle,
    `Storyboard beat for this scene: ${beatText}`,
    `User direction for this scene:\n${directionText || defaultDirectionText(sceneNumber)}`,
    `Story continuity memory:\n${soFar}`,
    'Keep this image as the next chronological scene in the same story world.',
    'Avoid title/header text overlays.',
  ]
    .filter(Boolean)
    .join('\n\n');
}

async function generateImage(sceneNumber, beatText, directionText) {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    throw new Error('Missing OPENAI_API_KEY in Function App settings.');
  }

  const prompt = buildPromptForScene(sceneNumber, beatText, directionText);
  const refs = [];

  if (state.referenceImageBytes) {
    refs.push({ bytes: state.referenceImageBytes, name: 'character_ref.png' });
  }

  const previous = state.history[state.history.length - 1];
  if (previous && previous.imageDataUrl && previous.imageDataUrl.startsWith('data:image/png;base64,')) {
    const b64 = previous.imageDataUrl.replace('data:image/png;base64,', '');
    refs.push({ bytes: Buffer.from(b64, 'base64'), name: 'previous_scene.png' });
  }

  let response;
  if (refs.length > 0) {
    const form = new FormData();
    form.append('model', state.model);
    form.append('prompt', prompt);
    form.append('size', '1536x1024');

    for (const ref of refs) {
      const blob = new Blob([ref.bytes], { type: 'image/png' });
      form.append('image[]', blob, ref.name);
    }

    response = await fetch('https://api.openai.com/v1/images/edits', {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}` },
      body: form,
    });
  } else {
    response = await fetch('https://api.openai.com/v1/images/generations', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: state.model,
        prompt,
        size: '1536x1024',
      }),
    });
  }

  const body = await response.json();
  if (!response.ok) {
    throw new Error(`OpenAI error ${response.status}: ${JSON.stringify(body)}`);
  }

  const b64 = body?.data?.[0]?.b64_json;
  if (!b64) {
    throw new Error(`Unexpected OpenAI response: ${JSON.stringify(body)}`);
  }

  return {
    sceneNumber,
    beatText,
    continuityNote: beatText,
    imageDataUrl: `data:image/png;base64,${b64}`,
    createdAt: new Date().toISOString(),
  };
}

async function processQueue() {
  if (state.processing) return;
  state.processing = true;

  try {
    while (true) {
      const job = state.pendingJobs.find((j) => j.status === 'queued');
      if (!job) break;

      job.status = 'processing';
      job.startedAt = new Date().toISOString();
      bumpRevision();

      try {
        const scene = await generateImage(job.sceneNumber, job.beatText, job.directionText);
        state.history.push(scene);
        state.history.sort((a, b) => a.sceneNumber - b.sceneNumber);
        job.status = 'completed';
        job.completedAt = new Date().toISOString();
        bumpRevision();
      } catch (err) {
        job.status = 'failed';
        job.error = String(err.message || err);
        job.completedAt = new Date().toISOString();
        state.failedJobs.unshift({
          jobId: job.jobId,
          sceneNumber: job.sceneNumber,
          beatText: job.beatText,
          error: job.error,
          createdAt: new Date().toISOString(),
        });
        state.failedJobs = state.failedJobs.slice(0, 20);
        bumpRevision();
      }
    }
  } finally {
    state.processing = false;
  }
}

function cleanupPendingJobs() {
  const now = Date.now();
  const holdMs = 9000;
  const before = state.pendingJobs.length;
  state.pendingJobs = state.pendingJobs.filter((job) => {
    if (job.status === 'queued' || job.status === 'processing') return true;
    if (!job.completedAt) return true;
    const age = now - Date.parse(job.completedAt);
    return age < holdMs;
  });
  if (state.pendingJobs.length !== before) bumpRevision();
}

function allowedOrigins() {
  const raw =
    process.env.ROBOGENE_ALLOWED_ORIGIN ||
    'https://thxmuffe.github.io,http://localhost:8080,http://127.0.0.1:8080,http://localhost:5500,http://127.0.0.1:5500,http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173';
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

function corsOrigin(request) {
  const origins = allowedOrigins();
  const reqOrigin = request?.headers?.get('origin') || request?.headers?.get('Origin');
  if (!reqOrigin) return origins[0] || '*';
  if (origins.includes(reqOrigin)) return reqOrigin;
  return origins[0] || 'null';
}

function corsHeaders(request) {
  return {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': corsOrigin(request),
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type,Authorization',
    'Cache-Control': 'no-store, no-cache, must-revalidate, proxy-revalidate',
    Vary: 'Origin',
    Pragma: 'no-cache',
    Expires: '0',
  };
}

function json(status, data, request) {
  return {
    status,
    jsonBody: data,
    headers: corsHeaders(request),
  };
}

app.http('state', {
  methods: ['GET'],
  authLevel: 'anonymous',
  route: 'state',
  handler: async (request) => {
    cleanupPendingJobs();
    return json(200, {
      storyId: state.storyId,
      revision: state.revision,
      cursor: state.cursor,
      totalScenes: state.beats.length,
      nextSceneNumber: state.cursor,
      nextDefaultDirection: state.cursor <= state.beats.length ? defaultDirectionText(state.cursor) : '',
      processing: state.processing,
      pendingCount: state.pendingJobs.length,
      pending: state.pendingJobs,
      history: state.history,
      failed: state.failedJobs,
    }, request);
  },
});

app.http('wait-state', {
  methods: ['GET'],
  authLevel: 'anonymous',
  route: 'wait-state',
  handler: async (request) => {
    cleanupPendingJobs();
    const since = parseIntQuery(request, 'since', 0);
    const timeoutMs = Math.max(2000, Math.min(30000, parseIntQuery(request, 'timeoutMs', 25000)));

    if (state.revision > since) {
      return json(200, { changed: true, revision: state.revision }, request);
    }

    return await new Promise((resolve) => {
      const waiter = {
        request,
        resolve,
        timerId: setTimeout(() => {
          clearStateWaiter(waiter);
          resolve(json(200, { changed: false, revision: state.revision }, request));
        }, timeoutMs),
      };
      stateWaiters.push(waiter);
    });
  },
});

app.http('generate-next', {
  methods: ['POST'],
  authLevel: 'anonymous',
  route: 'generate-next',
  handler: async (request) => {
    try {
      cleanupPendingJobs();
      if (state.cursor > state.beats.length) {
        return json(409, { done: true, error: 'Storyboard complete.', history: state.history }, request);
      }

      let body = {};
      try {
        body = await request.json();
      } catch (_) {
        body = {};
      }

      const sceneNumber = state.cursor;
      state.cursor += 1;

      const job = {
        jobId: crypto.randomUUID(),
        sceneNumber,
        beatText: sceneBeatText(sceneNumber),
        directionText: (body.direction || '').toString().trim(),
        status: 'queued',
        queuedAt: new Date().toISOString(),
      };

      state.pendingJobs.push(job);
      bumpRevision();
      processQueue();

      return json(202, {
        accepted: true,
        job,
        revision: state.revision,
        pendingCount: state.pendingJobs.length,
        nextSceneNumber: state.cursor,
        nextDefaultDirection: state.cursor <= state.beats.length ? defaultDirectionText(state.cursor) : '',
      }, request);
    } catch (err) {
      return json(500, { error: String(err.message || err) }, request);
    }
  },
});

app.http('preflight', {
  methods: ['OPTIONS'],
  authLevel: 'anonymous',
  route: '{*path}',
  handler: async (request) => {
    return {
      status: 204,
      headers: corsHeaders(request),
    };
  },
});
