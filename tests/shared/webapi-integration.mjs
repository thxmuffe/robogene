import {
  cljsToJs,
  importWebapiFiles,
  jsToCljs,
  loadWebapiNamespace,
} from './load-webapi-runtime.mjs';
import fs from 'node:fs';

export function renderHttpFixture(path, metaUrl, vars) {
  let text = fs.readFileSync(new URL(path, metaUrl), 'utf8');
  for (const [key, value] of Object.entries(vars)) {
    text = text.replaceAll(`{{$processEnv ${key}}}`, value);
  }

  const constants = {};
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^@(\w+)\s*=\s*(.+)$/);
    if (match) constants[match[1]] = match[2];
  }

  for (const [key, value] of Object.entries(constants)) {
    text = text.replaceAll(`{{${key}}}`, value);
  }

  return text;
}

export function requestBodiesFromHttpFixture(text) {
  return text
    .split(/^### .+$/m)
    .map((part) => {
      const jsonStart = part.indexOf('{');
      return jsonStart >= 0 ? JSON.parse(part.slice(jsonStart)) : null;
    })
    .filter(Boolean);
}

export async function seedFromHttpFixture(harness, path, metaUrl, vars) {
  const text = renderHttpFixture(path, metaUrl, vars);
  for (const body of requestBodiesFromHttpFixture(text)) {
    const response = await harness.api.handle_save_entity(
      harness.makeRequest({ url: 'http://localhost/api/entity', body }),
    );
    if (response.status !== 200) {
      throw new Error(`Failed to seed entity: ${JSON.stringify(body)}`);
    }
  }
}

export function bootWebapiIntegration() {
  const runtime = loadWebapiNamespace('imageGenerator');
  loadWebapiNamespace('realtime');
  loadWebapiNamespace('azureStore');
  importWebapiFiles(['services.entity.js', 'shadow.js.shim.module$$azure$functions.js', 'services.api.js']);

  const { cljs, services } = runtime;
  const entity = services.entity;
  const api = services.api;
  const azureStore = services.azure_store;

  const kw = (name) => cljs.core.keyword(name);

  function buildEntityMap(entities) {
    return entities.reduce(
      (acc, item) => cljs.core.assoc(acc, item.id, jsToCljs(item)),
      cljs.core.PersistentArrayMap.EMPTY,
    );
  }

  function resetEntityState(workspaceId, entities = []) {
    cljs.core.reset_BANG_(
      entity.state,
      cljs.core.assoc(
        cljs.core.assoc(
          cljs.core.assoc(
            cljs.core.assoc(
              cljs.core.assoc(
                cljs.core.assoc(
                  jsToCljs({}),
                  kw('workspaceId'),
                  workspaceId,
                ),
                kw('entities'),
                buildEntityMap(entities),
              ),
              kw('processing'),
              false,
            ),
            kw('revision'),
            0,
          ),
          kw('defaultImageGenerator'),
          'mock',
        ),
        kw('availableImageGenerators'),
        jsToCljs(['mock']),
      ),
    );
  }

  function makeRequest({ body, url, origin = 'http://integration.test' } = {}) {
    return {
      headers: new Map([['origin', origin]]),
      url,
      json: async () => body,
    };
  }

  function makeDeleteRequest(id, { origin = 'http://integration.test' } = {}) {
    return {
      headers: new Map([['origin', origin]]),
      url: `http://localhost/api/entity/${id}`,
      params: { id },
    };
  }

  async function loadStoredEntities(workspaceId) {
    return cljsToJs(await azureStore.load_entities(workspaceId));
  }

  return {
    api,
    azureStore,
    cljs,
    cljsToJs,
    entity,
    loadStoredEntities,
    makeDeleteRequest,
    makeRequest,
    resetEntityState,
    services,
    snapshot: () => cljsToJs(cljs.core.deref(entity.state)),
  };
}
