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
  model: process.env.ROBOGENE_IMAGE_MODEL || 'gpt-image-1',
  referenceImageBytes: null,
};

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

      try {
        const scene = await generateImage(job.sceneNumber, job.beatText, job.directionText);
        state.history.push(scene);
        state.history.sort((a, b) => a.sceneNumber - b.sceneNumber);
      } catch (err) {
        state.failedJobs.unshift({
          jobId: job.jobId,
          sceneNumber: job.sceneNumber,
          beatText: job.beatText,
          error: String(err.message || err),
          createdAt: new Date().toISOString(),
        });
        state.failedJobs = state.failedJobs.slice(0, 20);
      } finally {
        state.pendingJobs = state.pendingJobs.filter((j) => j.jobId !== job.jobId);
      }
    }
  } finally {
    state.processing = false;
  }
}

function corsHeaders() {
  return {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': process.env.ROBOGENE_ALLOWED_ORIGIN || '*',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type,Authorization',
    'Cache-Control': 'no-store, no-cache, must-revalidate, proxy-revalidate',
    Pragma: 'no-cache',
    Expires: '0',
  };
}

function json(status, data) {
  return {
    status,
    jsonBody: data,
    headers: corsHeaders(),
  };
}

app.http('state', {
  methods: ['GET'],
  authLevel: 'anonymous',
  route: 'state',
  handler: async () => {
    return json(200, {
      storyId: state.storyId,
      cursor: state.cursor,
      totalScenes: state.beats.length,
      nextSceneNumber: state.cursor,
      nextDefaultDirection: state.cursor <= state.beats.length ? defaultDirectionText(state.cursor) : '',
      processing: state.processing,
      pendingCount: state.pendingJobs.length,
      pending: state.pendingJobs,
      history: state.history,
      failed: state.failedJobs,
    });
  },
});

app.http('generate-next', {
  methods: ['POST'],
  authLevel: 'anonymous',
  route: 'generate-next',
  handler: async (request) => {
    try {
      if (state.cursor > state.beats.length) {
        return json(409, { done: true, error: 'Storyboard complete.', history: state.history });
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
      processQueue();

      return json(202, {
        accepted: true,
        job,
        pendingCount: state.pendingJobs.length,
        nextSceneNumber: state.cursor,
        nextDefaultDirection: state.cursor <= state.beats.length ? defaultDirectionText(state.cursor) : '',
      });
    } catch (err) {
      return json(500, { error: String(err.message || err) });
    }
  },
});

app.http('preflight', {
  methods: ['OPTIONS'],
  authLevel: 'anonymous',
  route: '{*path}',
  handler: async () => {
    return {
      status: 204,
      headers: corsHeaders(),
    };
  },
});
