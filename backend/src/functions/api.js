try {
  require('./api_compiled.js');
} catch (err) {
  console.warn('[robogene] Failed to load ClojureScript backend build, falling back to legacy JS backend.', err && err.message ? err.message : err);
  require('./api_legacy.js');
}
