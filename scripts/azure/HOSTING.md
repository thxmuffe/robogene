# Azure Hosting and Provisioning

Azure infra automation now lives under `scripts/azure`.

## Structure

Infrastructure as code (Bicep modules):
- `infra/modules/app_insights.bicep`
- `infra/modules/database.bicep`
- `infra/modules/function_app.bicep`
- `infra/modules/signalr.bicep`
- `infra/main.bicep`

Provision scripts (single responsibility):
- `provision_function_app.sh`
- `provision_database.sh`
- `provision_app_insights.sh`
- `provision_aux_services.sh`

Main deploy/orchestrator:
- `deploy.sh` (calls all provision scripts)

Monitoring summary:
- `monitor_summary.sh`

## Required Env Vars

- `AZ_SUBSCRIPTION_ID`
- `AZ_LOCATION`
- `AZ_RESOURCE_GROUP`
- `AZ_STORAGE_ACCOUNT`
- `AZ_FUNCTION_APP`
- `AZ_APPINSIGHTS_NAME`
- `AZ_SIGNALR_NAME`
- `ROBOGENE_IMAGE_GENERATOR_KEY`

Common optional:
- `ROBOGENE_ALLOWED_ORIGIN`
- `ROBOGENE_IMAGE_GENERATOR` (default `openai`)
- `ROBOGENE_SIGNALR_HUB` (default `robogene`)
- `AZ_SIGNALR_SKU` (default `Standard_S1`)

Static image defaults are in:
- `src/host/host.json` -> `robogene.imageDefaults`

## Commands

Rebuild/create infra:

```bash
./scripts/azure/deploy.sh --yes
```

Rebuild infra + build + deploy webapi zip:

```bash
./scripts/azure/deploy.sh --yes --build-and-deploy
```

Rebuild infra + deploy existing zip:

```bash
./scripts/azure/deploy.sh --yes --deploy-zip dist/release/webapi/webapi_dist.zip
```

Monitoring summary:

```bash
./scripts/azure/monitor_summary.sh robogene-func-prod robogene-rg 6h
```
