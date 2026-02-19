const el = {
  nextBtn: document.getElementById('nextBtn'),
  status: document.getElementById('status'),
  gallery: document.getElementById('gallery'),
  directionInput: document.getElementById('directionInput'),
};

const API_BASE = (window.ROBOGENE_API_BASE || '').replace(/\/+$/, '');
let latestState = null;
let directionDirty = false;
let pollHandle = null;

function setStatus(text) {
  el.status.textContent = text;
}

function apiUrl(path) {
  if (!API_BASE) return path;
  return `${API_BASE}${path}`;
}

function apiStateUrl() {
  const sep = API_BASE.includes('?') ? '&' : '?';
  return `${apiUrl('/api/state')}${sep}t=${Date.now()}`;
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
  const badge = scene.reference ? '<span class="badge">Reference</span>' : '';
  const src = scene.imageDataUrl || '';
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

function pendingCardHTML(job) {
  const label = job.status === 'processing' ? 'Processing' : 'Queued';
  return `
    <article class="card pending" data-job="${job.jobId}">
      <div class="placeholder-img">
        <div class="spinner"></div>
        <div class="placeholder-text">${label} scene ${job.sceneNumber}...</div>
      </div>
      <div class="meta">
        <strong>Scene ${job.sceneNumber} <span class="badge queue">In Queue</span></strong>
        <div>${job.beatText || ''}</div>
      </div>
    </article>
  `;
}

function renderFromState(state) {
  const history = (state.history || []).map((s) => ({ type: 'history', sceneNumber: s.sceneNumber, html: cardHTML(s) }));
  const pending = (state.pending || []).map((p) => ({ type: 'pending', sceneNumber: p.sceneNumber, html: pendingCardHTML(p) }));

  const combined = [...history, ...pending].sort((a, b) => b.sceneNumber - a.sceneNumber);
  el.gallery.innerHTML = combined.map((x) => x.html).join('');

  const pendingCount = state.pendingCount || 0;
  if (state.cursor > state.totalScenes && pendingCount === 0) {
    setStatus('Storyboard complete.');
  } else {
    const queueText = pendingCount > 0 ? ` | Queue: ${pendingCount}` : '';
    setStatus(`Next scene: ${state.nextSceneNumber}/${state.totalScenes}${queueText}`);
  }

  if (!directionDirty || !el.directionInput.value.trim()) {
    el.directionInput.value = state.nextDefaultDirection || '';
  }
}

async function loadState() {
  const res = await fetch(apiStateUrl(), { cache: 'no-store' });
  const data = await readJsonSafe(res);
  if (!res.ok) {
    setStatus(`Load failed: ${data.error || 'Unknown error'}`);
    return null;
  }

  latestState = data;
  renderFromState(data);
  return data;
}

async function generateNext() {
  const direction = el.directionInput.value.trim();
  el.nextBtn.disabled = true;
  setStatus('Queued next scene...');

  try {
    const res = await fetch(apiUrl('/api/generate-next'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store',
      body: JSON.stringify({ direction }),
    });
    const data = await readJsonSafe(res);

    if (res.status === 409 && data.done) {
      setStatus('Storyboard complete.');
      return;
    }
    if (!res.ok) {
      setStatus(`Generation failed: ${data.error || 'Unknown error'}`);
      return;
    }

    directionDirty = false;
    el.directionInput.value = data.nextDefaultDirection || '';
    await loadState();
  } finally {
    el.nextBtn.disabled = false;
  }
}

function startPolling() {
  if (pollHandle) clearTimeout(pollHandle);

  const tick = async () => {
    try {
      await loadState();
    } catch (err) {
      setStatus(`Load failed: ${err.message}`);
    } finally {
      const fast = latestState && latestState.pendingCount > 0;
      const delay = fast ? 1200 : 3500;
      pollHandle = setTimeout(tick, delay);
    }
  };

  tick();
}

el.directionInput.addEventListener('input', () => {
  directionDirty = true;
});

el.nextBtn.addEventListener('click', () => {
  generateNext().catch((err) => setStatus(`Generation failed: ${err.message}`));
});

loadState()
  .then(() => startPolling())
  .catch((err) => setStatus(`Load failed: ${err.message}`));

window.addEventListener('focus', () => {
  loadState().catch((err) => setStatus(`Load failed: ${err.message}`));
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    loadState().catch((err) => setStatus(`Load failed: ${err.message}`));
  }
});
