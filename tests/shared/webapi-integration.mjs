import {
  cljsToJs,
  importWebapiFiles,
  jsToCljs,
  loadWebapiNamespace,
} from './load-webapi-runtime.mjs';

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
    entity,
    loadStoredEntities,
    makeDeleteRequest,
    makeRequest,
    resetEntityState,
    snapshot: () => cljsToJs(cljs.core.deref(entity.state)),
  };
}
