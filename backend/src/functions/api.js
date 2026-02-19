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
  cursor: 2,
  model: process.env.ROBOGENE_IMAGE_MODEL || 'gpt-image-1',
  referenceImageBytes: null,
};

function sceneBeatText(sceneNumber) {
  const beat = state.beats.find((b) => b.index === sceneNumber);
  return beat ? beat.text : `Scene ${sceneNumber}`;
}

function initializeState() {
  const storyboardText = readText(DEFAULT_STORYBOARD);
  const promptsText = readText(DEFAULT_PROMPTS);

  state.storyId = crypto.randomUUID();
  state.beats = parseBeats(storyboardText);
  state.visual = parseVisualPrompts(promptsText);
  state.history = [];
  state.cursor = 2;

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

function buildPromptForScene(sceneNumber, beatText) {
  const globalStyle = state.visual.globalStyle || '';
  const visualPrompt = state.visual.pagePrompts[sceneNumber] || '';
  const soFar = continuityWindow();

  return [
    'Create ONE comic story image for the next scene.',
    'Character lock: Robot Emperor must match the attached reference identity (powdered white wig with side curls, pale robotic face, cyan glowing eyes, red cape with blue underlayer).',
    globalStyle,
    `Storyboard beat for this scene: ${beatText}`,
    visualPrompt ? `Scene visual direction: ${visualPrompt}` : '',
    `Story continuity memory:\n${soFar}`,
    'Keep this image as the next chronological scene in the same story world.',
    'Avoid title/header text overlays.',
  ]
    .filter(Boolean)
    .join('\n\n');
}

async function generateImage(sceneNumber, beatText) {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    throw new Error('Missing OPENAI_API_KEY in Function App settings.');
  }

  const prompt = buildPromptForScene(sceneNumber, beatText);
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

  const imageDataUrl = `data:image/png;base64,${b64}`;
  return {
    sceneNumber,
    beatText,
    continuityNote: beatText,
    imageDataUrl,
    createdAt: new Date().toISOString(),
  };
}

function json(status, data) {
  return {
    status,
    jsonBody: data,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': process.env.ROBOGENE_ALLOWED_ORIGIN || '*',
    },
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
      history: state.history,
    });
  },
});

app.http('generate-next', {
  methods: ['POST'],
  authLevel: 'anonymous',
  route: 'generate-next',
  handler: async () => {
    try {
      if (state.cursor > state.beats.length) {
        return json(409, { done: true, error: 'Storyboard complete.', history: state.history });
      }

      const beatText = sceneBeatText(state.cursor);
      const scene = await generateImage(state.cursor, beatText);
      state.history.push(scene);
      state.cursor += 1;

      return json(200, {
        ok: true,
        scene,
        cursor: state.cursor,
        totalScenes: state.beats.length,
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
      headers: {
        'Access-Control-Allow-Origin': process.env.ROBOGENE_ALLOWED_ORIGIN || '*',
        'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type,Authorization',
      },
    };
  },
});
