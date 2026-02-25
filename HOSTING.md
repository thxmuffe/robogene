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

- `ROBOGENE_IMAGE_GENERATOR=openai`
- `ROBOGENE_IMAGE_GENERATOR_KEY`
- `AzureWebJobsStorage` (or `ROBOGENE_STORAGE_CONNECTION_STRING`)
- `AzureSignalRConnectionString`

Recommended app settings:

- `FUNCTIONS_WORKER_RUNTIME=node`
- `WEBSITE_NODE_DEFAULT_VERSION=~22`
- `WEBSITE_RUN_FROM_PACKAGE=1`
- `ROBOGENE_IMAGE_MODEL=gpt-image-1-mini`
- `ROBOGENE_IMAGE_QUALITY=low`
- `ROBOGENE_IMAGE_SIZE=1024x1024`
- `ROBOGENE_SIGNALR_HUB=robogene`
- `ROBOGENE_ALLOWED_ORIGIN=<your web origins>`

Storage names are currently hardcoded in code:
- meta table: `robogeneState`
- chapter table: `robogeneChapter`
- frame table: `robogeneFrame`
- blob container: `robogene-images`

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

## One-shot Infra Rebuild

If the resource group is deleted (or you need to recreate infra), use:

```bash
./scripts/azure-rebuild.sh --yes
```

This script is idempotent and creates/updates:
- Resource group
- Storage account
- Application Insights
- SignalR Service
- Function App
- Required Function App settings (including App Insights and SignalR wiring)

Optional deploy in same command:

```bash
./scripts/azure-rebuild.sh --yes --deploy-zip dist/release/webapi/webapi_dist.zip
```

Required env vars are listed at the top of:
- `scripts/azure-rebuild.sh`

## Webapp Hosting

Webapp static bundle is deployed by the same workflow to GitHub Pages.

Bundle location after build:
- `dist/release/webapp/`

## Quick Validation

After deploy:

```bash
curl -fsS "https://<function-app-name>.azurewebsites.net/api/state?t=$(date +%s)"
```

Should return JSON with chapter/frames state.
