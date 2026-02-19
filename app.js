const el = {
  nextBtn: document.getElementById('nextBtn'),
  status: document.getElementById('status'),
  gallery: document.getElementById('gallery'),
};

const API_BASE = (window.ROBOGENE_API_BASE || '').replace(/\/+$/, '');

function setStatus(text) {
  el.status.textContent = text;
}

function apiUrl(path) {
  if (!API_BASE) return path;
  return `${API_BASE}${path}`;
}

async function readJsonSafe(res) {
  const raw = await res.text();
  try {
    return JSON.parse(raw);
  } catch (_) {
    throw new Error(
      `Expected JSON from backend, got non-JSON response (${res.status}). Check ROBOGENE_API_BASE/CORS.`
    );
  }
}

function cardHTML(scene) {
  const stamp = Date.now();
  const badge = scene.reference ? '<span class="badge">Reference</span>' : '';
  const src = scene.imageDataUrl || `${scene.url}?t=${stamp}`;
  return `
    <article class="card" data-scene="${scene.sceneNumber}">
      <img src="${src}" alt="Scene ${scene.sceneNumber}" />
      <div class="meta">
        <strong>Scene ${scene.sceneNumber} ${badge}</strong>
        <div>${scene.beatText || ''}</div>
      </div>
    </article>
  `;
}

function renderHistory(history) {
  const sorted = [...history].sort((a, b) => b.sceneNumber - a.sceneNumber);
  el.gallery.innerHTML = sorted.map(cardHTML).join('');
}

function prependScene(scene) {
  el.gallery.insertAdjacentHTML('afterbegin', cardHTML(scene));
}

async function loadState() {
  const res = await fetch(apiUrl('/api/state'));
  const data = await readJsonSafe(res);
  if (!res.ok) {
    setStatus(`Load failed: ${data.error || 'Unknown error'}`);
    return null;
  }
  renderHistory(data.history || []);
  if (data.cursor > data.totalScenes) {
    setStatus('Storyboard complete.');
  } else {
    setStatus(`Ready. Next scene: ${data.cursor}/${data.totalScenes}`);
  }
  return data;
}

async function generateNext() {
  el.nextBtn.disabled = true;
  setStatus('Generating next scene...');
  try {
    const res = await fetch(apiUrl('/api/generate-next'), { method: 'POST' });
    const data = await readJsonSafe(res);

    if (res.status === 409 && data.done) {
      setStatus('Storyboard complete.');
      return;
    }
    if (!res.ok) {
      setStatus(`Generation failed: ${data.error || 'Unknown error'}`);
      return;
    }

    prependScene(data.scene);
    setStatus(`Generated scene ${data.scene.sceneNumber}. Next: ${data.cursor}/${data.totalScenes}`);
  } finally {
    el.nextBtn.disabled = false;
  }
}

el.nextBtn.addEventListener('click', generateNext);
loadState().catch((err) => setStatus(`Load failed: ${err.message}`));
