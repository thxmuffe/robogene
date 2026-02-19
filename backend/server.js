#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');

const APP_ROOT = path.resolve(__dirname, '..');
const PUBLIC_DIR = APP_ROOT;
const GENERATED_DIR = path.join(APP_ROOT, 'generated');
const DEFAULT_STORYBOARD = path.join(APP_ROOT, '..', 'episode-28-draft', '28_Municipal_Firmware_script.md');
const DEFAULT_PROMPTS = path.join(APP_ROOT, '..', 'episode-28-draft', '28_Municipal_Firmware_image_prompts.md');
const DEFAULT_REFERENCE_IMAGE = path.join(APP_ROOT, 'references', 'robot_emperor_ep22_p01.png');
const PAGE1_REFERENCE_IMAGE = path.join(APP_ROOT, '..', 'episode-28-draft', 'images', '28_page_01_openai_refined.png');

fs.mkdirSync(GENERATED_DIR, { recursive: true });

function loadEnvFile(envPath) {
  if (!fs.existsSync(envPath)) return;
  const lines = fs.readFileSync(envPath, 'utf8').split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq <= 0) continue;
    const key = trimmed.slice(0, eq).trim();
    const value = trimmed.slice(eq + 1).trim().replace(/^['\"]|['\"]$/g, '');
    if (!process.env[key]) process.env[key] = value;
  }
}

loadEnvFile(path.join(APP_ROOT, '.env'));
loadEnvFile(path.join(APP_ROOT, '..', 'pop.env'));

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

function detectMime(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  if (ext === '.png') return 'image/png';
  if (ext === '.jpg' || ext === '.jpeg') return 'image/jpeg';
  if (ext === '.webp') return 'image/webp';
  return 'application/octet-stream';
}

function safeName(name) {
  return name.replace(/[^a-zA-Z0-9._-]/g, '_');
}

function readText(filePath) {
  try {
    return fs.readFileSync(filePath, 'utf8');
  } catch (_) {
    return '';
  }
}

function writeJson(res, code, data) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data));
}

function serveFile(res, filePath) {
  if (!fs.existsSync(filePath)) {
    res.writeHead(404);
    res.end('Not found');
    return;
  }
  const ext = path.extname(filePath).toLowerCase();
  const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.svg': 'image/svg+xml',
    '.json': 'application/json; charset=utf-8',
  };
  res.writeHead(200, { 'Content-Type': types[ext] || 'application/octet-stream' });
  fs.createReadStream(filePath).pipe(res);
}

const state = {
  storyId: null,
  storyboardText: '',
  promptsText: '',
  beats: [],
  visual: { globalStyle: '', pagePrompts: {} },
  history: [],
  cursor: 1,
  model: 'gpt-image-1',
  referenceImagePath: DEFAULT_REFERENCE_IMAGE,
};

function sceneBeatText(sceneNumber) {
  const beat = state.beats.find((b) => b.index === sceneNumber);
  return beat ? beat.text : `Scene ${sceneNumber}`;
}

function loadExistingGeneratedScenes() {
  const files = fs.readdirSync(GENERATED_DIR)
    .filter((name) => /^scene_(\d+)\.png$/i.test(name))
    .sort((a, b) => Number(a.match(/(\d+)/)[1]) - Number(b.match(/(\d+)/)[1]));

  const scenes = [];
  for (const fileName of files) {
    const n = Number(fileName.match(/(\d+)/)[1]);
    if (n <= 1) continue;
    const filePath = path.join(GENERATED_DIR, fileName);
    scenes.push({
      sceneNumber: n,
      beatText: sceneBeatText(n),
      continuityNote: sceneBeatText(n),
      fileName,
      filePath,
      url: `/generated/${fileName}`,
      createdAt: new Date(fs.statSync(filePath).mtimeMs).toISOString(),
    });
  }
  return scenes;
}

function initializeDefaultStory() {
  state.storyId = crypto.randomUUID();
  state.storyboardText = readText(DEFAULT_STORYBOARD);
  state.promptsText = readText(DEFAULT_PROMPTS);
  state.beats = parseBeats(state.storyboardText);
  state.visual = parseVisualPrompts(state.promptsText);
  state.referenceImagePath = DEFAULT_REFERENCE_IMAGE;

  state.history = [];

  if (fs.existsSync(PAGE1_REFERENCE_IMAGE)) {
    state.history.push({
      sceneNumber: 1,
      beatText: sceneBeatText(1),
      continuityNote: sceneBeatText(1),
      fileName: 'reference_page_01.png',
      filePath: PAGE1_REFERENCE_IMAGE,
      url: '/reference/page1.png',
      createdAt: new Date(fs.statSync(PAGE1_REFERENCE_IMAGE).mtimeMs).toISOString(),
      reference: true,
    });
  }

  const existing = loadExistingGeneratedScenes();
  state.history.push(...existing);

  const maxScene = state.history.reduce((m, s) => Math.max(m, s.sceneNumber || 0), 0);
  state.cursor = Math.max(2, maxScene + 1);
}

function continuityWindow(limit = 6) {
  const tail = state.history.slice(-limit);
  if (!tail.length) return 'No previous scenes yet.';
  return tail
    .map((s) => `Scene ${s.sceneNumber}: ${s.beatText}.`) 
    .join('\n');
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
  ].filter(Boolean).join('\n\n');
}

async function generateImage(sceneNumber, beatText) {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) throw new Error('Missing OPENAI_API_KEY.');

  const prompt = buildPromptForScene(sceneNumber, beatText);
  const refs = [];

  if (state.referenceImagePath && fs.existsSync(state.referenceImagePath)) refs.push(state.referenceImagePath);

  const previous = state.history[state.history.length - 1];
  if (previous && previous.filePath && fs.existsSync(previous.filePath)) refs.push(previous.filePath);

  let response;
  if (refs.length > 0) {
    const form = new FormData();
    form.append('model', state.model);
    form.append('prompt', prompt);
    form.append('size', '1536x1024');
    for (const filePath of refs) {
      const buf = fs.readFileSync(filePath);
      const blob = new Blob([buf], { type: detectMime(filePath) });
      form.append('image[]', blob, path.basename(filePath));
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
      body: JSON.stringify({ model: state.model, prompt, size: '1536x1024' }),
    });
  }

  const body = await response.json();
  if (!response.ok) throw new Error(`OpenAI error ${response.status}: ${JSON.stringify(body)}`);

  const b64 = body?.data?.[0]?.b64_json;
  if (!b64) throw new Error(`Unexpected OpenAI response: ${JSON.stringify(body)}`);

  const fileName = `scene_${String(sceneNumber).padStart(2, '0')}.png`;
  const filePath = path.join(GENERATED_DIR, fileName);
  fs.writeFileSync(filePath, Buffer.from(b64, 'base64'));

  return {
    sceneNumber,
    beatText,
    continuityNote: beatText,
    fileName,
    filePath,
    url: `/generated/${fileName}`,
    createdAt: new Date().toISOString(),
  };
}

initializeDefaultStory();

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');

    if (req.method === 'GET' && url.pathname === '/api/state') {
      writeJson(res, 200, {
        storyId: state.storyId,
        cursor: state.cursor,
        totalScenes: state.beats.length,
        history: state.history,
      });
      return;
    }

    if (req.method === 'POST' && url.pathname === '/api/generate-next') {
      if (state.cursor > state.beats.length) {
        writeJson(res, 409, { done: true, error: 'Storyboard complete.', history: state.history });
        return;
      }

      const beatText = sceneBeatText(state.cursor);
      const scene = await generateImage(state.cursor, beatText);
      state.history.push(scene);
      state.cursor += 1;

      writeJson(res, 200, {
        ok: true,
        scene,
        cursor: state.cursor,
        totalScenes: state.beats.length,
      });
      return;
    }

    if (req.method === 'GET' && url.pathname === '/reference/page1.png') {
      serveFile(res, PAGE1_REFERENCE_IMAGE);
      return;
    }

    if (req.method === 'GET' && url.pathname.startsWith('/generated/')) {
      const fileName = safeName(path.basename(url.pathname));
      serveFile(res, path.join(GENERATED_DIR, fileName));
      return;
    }

    if (req.method === 'GET' && url.pathname === '/') {
      serveFile(res, path.join(PUBLIC_DIR, 'index.html'));
      return;
    }

    const publicPath = path.join(PUBLIC_DIR, safeName(path.basename(url.pathname)));
    if (req.method === 'GET' && fs.existsSync(publicPath)) {
      serveFile(res, publicPath);
      return;
    }

    res.writeHead(404);
    res.end('Not found');
  } catch (err) {
    writeJson(res, 500, { error: String(err.message || err) });
  }
});

const port = Number(process.env.PORT || 8787);
server.listen(port, () => {
  console.log(`robogene running on http://localhost:${port}`);
});
