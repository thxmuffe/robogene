# RoboGene

Interactive story-to-image app.

- Webapp is ClojureScript + Re-frame/Reagent.
- Webapp sources: `src/webapp/`
- Webapp shell files: `src/webapp/index.html` and `src/webapp/styles.css`
- Webapp build output:
  - debug: `dist/debug/webapp/js/main.js` (watch/dev)
  - release: `dist/release/webapp/js/main.js` (CI/deploy)
- Build config files: `shadow-cljs.edn` and `deps.edn`
- API host is Azure Functions app in `src/api_host/`
- Services source is ClojureScript: `src/services/api.cljs`
- Azure entrypoint is `src/api_host/story_routes_host.js` (loads generated `dist/debug/webapi/webapi_compiled.js` or `dist/release/webapi/webapi_compiled.js`)
- Story/reference/generated material is consolidated under `ai/robot emperor/`

## Webapp build
Prereqs:
- Node 20+
- Java + Clojure CLI (needed by `shadow-cljs`)

From repo root:

```bash
npm install
npm run build
```

Build services release bundle (ClojureScript -> Azure Functions JS):

```bash
npm run build:webapi
```

Build services debug bundle:

```bash
npm run build:webapi:debug
```

Run tests:

```bash
# fast unit/integration tests
npm test

# services end-to-end test (starts Azure Functions locally on a test port)
npm run test:e2e

# everything
npm run test:all
```

For local dev watch (debug webapp build):

```bash
npm run watch
```

Or use the root helper script to run webapp + services local stack together:

```bash
./run.robogene.debug.sh
```

This starts:
- Webapp watcher (`shadow-cljs watch webapp-debug`)
- Services debug compile (`shadow-cljs compile webapi-debug`)
- Web API host (`npm run api_host:start`) on `http://localhost:7071`

It prints the webapp URL and keeps all local processes running.
When opened on `localhost`, the webapp automatically uses `http://localhost:7071` as API base.

## Webapp host (GitHub Pages)
Webapp and services deploy from one GitHub Actions workflow: `.github/workflows/deploy.yml`.
Set repository Pages source to `GitHub Actions`.

Set services origin in `src/webapp/index.html`:

```html
<script>
  window.ROBOGENE_API_BASE = "https://<your-function-app>.azurewebsites.net";
</script>
```

## API host local run (Azure Functions)
Prereqs:
- Node 20+
- Azure Functions Core Tools v4 (`func`)

From repo root:

```bash
cp src/api_host/local.settings.json.example src/api_host/local.settings.json
# set OPENAI_API_KEY, AzureWebJobsStorage, and AzureSignalRConnectionString in local.settings.json
npm run build:webapi
npm run api_host:start
```

API endpoints (local):
- `GET http://localhost:7071/api/state`
- `POST http://localhost:7071/api/generate-frame` with body `{ "frameId": "...", "direction": "..." }`
- `POST http://localhost:7071/api/add-frame` with body `{ "episodeId": "..." }`
- `POST http://localhost:7071/api/add-episode` with body `{ "description": "..." }`
- `POST http://localhost:7071/api/delete-frame` with body `{ "frameId": "..." }`
- `POST http://localhost:7071/api/clear-frame-image` with body `{ "frameId": "..." }`

Storage model:
- Metadata/state is persisted in Azure Tables (`robogeneMeta`, `robogeneEpisodes`, `robogeneFrames` by default).
- Generated images are persisted in Azure Blob Storage (`robogene-images` container by default).
- Blob URLs are returned as `frame.imageDataUrl` for webapp rendering.
- Tables/container are created automatically on first services start if missing.
- `AzureWebJobsStorage` is used unless `ROBOGENE_STORAGE_CONNECTION_STRING` is explicitly set.
- `UseDevelopmentStorage=true` requires Azurite; this project flow expects real Azure Storage for local runs too.

Note:
- `npm run build:webapi` builds release services output.
- `npm run build:webapi:debug` builds debug services output for local run.

## Deploy webapi to Azure Functions
Use a writable Azure CLI config path in this workspace:

```bash
export AZURE_CONFIG_DIR=/Users/penpa/Desktop/PDFs/robogene/.azure
az login
```

Create resources (example):

```bash
RG=robogene-rg
LOC=eastus
ST=robogenest$RANDOM
PLAN=robogene-plan
APP=robogene-func-$RANDOM

az group create -n $RG -l $LOC
az storage account create -g $RG -n $ST -l $LOC --sku Standard_LRS
az functionapp plan create -g $RG -n $PLAN --location $LOC --number-of-workers 1 --sku B1 --is-linux
az functionapp create -g $RG -p $PLAN -n $APP -s $ST --runtime node --runtime-version 20 --functions-version 4
```

Set app settings:

```bash
az functionapp config appsettings set -g $RG -n $APP --settings \
  OPENAI_API_KEY="<your_key>" \
  ROBOGENE_IMAGE_MODEL="gpt-image-1-mini" \
  ROBOGENE_IMAGE_QUALITY="low" \
  ROBOGENE_IMAGE_SIZE="1024x1024" \
  ROBOGENE_TABLE_META="robogeneMeta" \
  ROBOGENE_TABLE_EPISODES="robogeneEpisodes" \
  ROBOGENE_TABLE_FRAMES="robogeneFrames" \
  ROBOGENE_IMAGE_CONTAINER="robogene-images" \
  AzureSignalRConnectionString="<your_signalr_connection_string>" \
  ROBOGENE_SIGNALR_HUB="robogene" \
  ROBOGENE_ALLOWED_ORIGIN="https://thxmuffe.github.io,http://localhost:8080,http://127.0.0.1:8080,http://localhost:5500,http://127.0.0.1:5500,http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173"
```

Deploy from repo root:

```bash
npm install
npm run dist:webapi
# Produces:
# - dist/release/webapi/app/             (fully runnable Function App folder)
# - dist/release/webapi/webapi_dist.zip  (zip of dist/release/webapi/app)
az functionapp deployment source config-zip -g $RG -n $APP --src dist/release/webapi/webapi_dist.zip
```

Azure deploy in `.github/workflows/deploy.yml` supports two auth modes:

1. Publish profile (simplest):
   - Set repo secret `AZURE_FUNCTIONAPP_PUBLISH_PROFILE` from:
     - Azure Portal -> Function App -> `Get publish profile`
2. OIDC (no long-lived secret):
   - Set repo secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`
   - In Azure AD App Registration, add a Federated Credential for your GitHub repo/branch

If neither auth mode is configured, the workflow now fails early with a clear error listing missing secrets.

Then set webapp API base to:
- `https://$APP.azurewebsites.net`

Or run the helper script:

```bash
export AZURE_CONFIG_DIR=/Users/penpa/Desktop/PDFs/robogene/.azure
export OPENAI_API_KEY="<your_key>"
./.github/azure_deploy/deploy_azure.sh robogene-rg eastus robogene-func-prod
```

If your subscription has no Basic plan quota, use Consumption deploy:

```bash
export AZURE_CONFIG_DIR=/Users/penpa/Desktop/PDFs/robogene/.azure
export OPENAI_API_KEY="<your_key>"
./.github/azure_deploy/deploy_azure_consumption.sh robogene-rg eastus robogene-func-prod
```

## Notes
- Services store story state in-memory per function instance.
- Services state is frame-centric: every frame has a unique `frameId`.
- Generated images are returned as `frame.imageDataUrl` (base64) for portability.
- Realtime UI sync uses Azure SignalR (`/api/negotiate` + `stateChanged` events).
- Deploy package must include `node_modules` for this zip-deploy flow.
- `dist/debug/` holds local debug artifacts.
- `dist/release/` holds release artifacts used by CI/deploy.
- `dist/release/webapi/app/` is the assembled runnable Function App folder used by CI and local deploy scripts.

## Troubleshooting
- `500` with message like `... is not a function` on services routes:
  - Stop all `func start` processes.
  - Rebuild services from repo root: `npm run build:webapi`.
  - Restart services from repo root: `npm run api_host:start -- --verbose`.
- If `func start` fails because port `7071` is in use, stop the existing process first or pass `--port`.
- If `/api/negotiate` returns `500`, set `AzureSignalRConnectionString` in local/app settings.
