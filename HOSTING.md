# Hosting and Deploy

This file contains the full Azure hosting/deploy notes.  
The short project summary stays in `README.md`.

## Required Azure Resources

- Resource Group
- Storage Account
- Azure SignalR Service
- Azure Function App (Linux, Node 22, Functions v4)

## Required Function App Settings

Set these in Azure Function App Configuration:

- `OPENAI_API_KEY`
- `AzureWebJobsStorage` (or `ROBOGENE_STORAGE_CONNECTION_STRING`)
- `AzureSignalRConnectionString`

Recommended app settings:

- `FUNCTIONS_WORKER_RUNTIME=node`
- `WEBSITE_NODE_DEFAULT_VERSION=~22`
- `WEBSITE_RUN_FROM_PACKAGE=1`
- `ROBOGENE_IMAGE_MODEL=gpt-image-1-mini`
- `ROBOGENE_IMAGE_QUALITY=low`
- `ROBOGENE_IMAGE_SIZE=1024x1024`
- `ROBOGENE_TABLE_META=robogeneMeta`
- `ROBOGENE_TABLE_CHAPTERS=robogeneChapters`
- `ROBOGENE_TABLE_FRAMES=robogeneFrames`
- `ROBOGENE_IMAGE_CONTAINER=robogene-images`
- `ROBOGENE_SIGNALR_HUB=robogene`
- `ROBOGENE_ALLOWED_ORIGIN=<your web origins>`

## GitHub Actions Deployment

Workflow:
- `.github/workflows/deploy.yml`

Trigger:
- Push to `main` (or manual workflow dispatch)

Auth modes:
- Publish profile secret: `AZURE_FUNCTIONAPP_PUBLISH_PROFILE`
- OIDC secrets: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`

## CLI Deploy (Manual)

From repo root:

```bash
npm install
npm run dist:webapi
# output: dist/release/webapi/webapi_dist.zip
```

Deploy zip:

```bash
az functionapp deployment source config-zip \
  -g <resource-group> \
  -n <function-app-name> \
  --src dist/release/webapi/webapi_dist.zip
```

## Webapp Hosting

Webapp static bundle is deployed by the same workflow to GitHub Pages.

Bundle location after build:
- `dist/release/webapp/`

## Quick Validation

After deploy:

```bash
curl -fsS "https://<function-app-name>.azurewebsites.net/api/state?t=$(date +%s)"
```

Should return JSON with story/frames state.
