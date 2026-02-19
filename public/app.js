const el = {
  nextBtn: document.getElementById('nextBtn'),
  status: document.getElementById('status'),
  gallery: document.getElementById('gallery'),
};

function setStatus(text) {
  el.status.textContent = text;
}

function cardHTML(scene) {
  const stamp = Date.now();
  const badge = scene.reference ? '<span class="badge">Reference</span>' : '';
  return `
    <article class="card" data-scene="${scene.sceneNumber}">
      <img src="${scene.url}?t=${stamp}" alt="Scene ${scene.sceneNumber}" />
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
  const res = await fetch('/api/state');
  const data = await res.json();
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
    const res = await fetch('/api/generate-next', { method: 'POST' });
    const data = await res.json();

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
