#!/usr/bin/env bash
set -euo pipefail

# Idempotent Azure bootstrap for RoboGene core infrastructure.
# Creates/updates: resource group, storage account, application insights,
# signalr service, function app, and required app settings.

usage() {
  cat <<USAGE
Usage:
  $0 [--deploy-zip <path>] [--yes]

Required env vars:
  AZ_SUBSCRIPTION_ID        Azure subscription id
  AZ_LOCATION               Azure location, e.g. eastus
  AZ_RESOURCE_GROUP         Resource group name, e.g. robogene-rg
  AZ_FUNCTION_APP           Function app name, e.g. robogene-func-prod
  AZ_STORAGE_ACCOUNT        Storage account name (3-24 lowercase/alnum)
  AZ_SIGNALR_NAME           SignalR resource name
  AZ_APPINSIGHTS_NAME       Application Insights name
  ROBOGENE_ALLOWED_ORIGIN   Comma-separated allowed origins
  ROBOGENE_IMAGE_GENERATOR_KEY  Image provider API key

Optional env vars:
  ROBOGENE_IMAGE_GENERATOR  default: openai
  ROBOGENE_IMAGE_MODEL      default: gpt-image-1-mini
  ROBOGENE_IMAGE_QUALITY    default: low
  ROBOGENE_IMAGE_SIZE       default: 1024x1024
  ROBOGENE_SIGNALR_HUB      default: robogene
  ROBOGENE_TABLE_EPISODES   default: robogeneEpisodes
  AZ_SIGNALR_SKU            default: Standard_S1

Example:
  export AZ_SUBSCRIPTION_ID='...'
  export AZ_LOCATION='eastus'
  export AZ_RESOURCE_GROUP='robogene-rg'
  export AZ_FUNCTION_APP='robogene-func-prod'
  export AZ_STORAGE_ACCOUNT='robogenefuncprodst'
  export AZ_SIGNALR_NAME='robogene-signalr-dev'
  export AZ_APPINSIGHTS_NAME='robogene-func-prod'
  export ROBOGENE_ALLOWED_ORIGIN='https://thxmuffe.github.io,http://localhost:8080'
  export ROBOGENE_IMAGE_GENERATOR_KEY='***'
  $0 --yes
USAGE
}

DEPLOY_ZIP=""
ASSUME_YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --deploy-zip)
      DEPLOY_ZIP="${2:-}"
      shift 2
      ;;
    --yes)
      ASSUME_YES=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

required=(
  AZ_SUBSCRIPTION_ID
  AZ_LOCATION
  AZ_RESOURCE_GROUP
  AZ_FUNCTION_APP
  AZ_STORAGE_ACCOUNT
  AZ_SIGNALR_NAME
  AZ_APPINSIGHTS_NAME
  ROBOGENE_ALLOWED_ORIGIN
  ROBOGENE_IMAGE_GENERATOR_KEY
)
for v in "${required[@]}"; do
  if [[ -z "${!v:-}" ]]; then
    echo "Missing required env var: $v" >&2
    usage
    exit 1
  fi
done

ROBOGENE_IMAGE_GENERATOR="${ROBOGENE_IMAGE_GENERATOR:-openai}"
ROBOGENE_IMAGE_MODEL="${ROBOGENE_IMAGE_MODEL:-gpt-image-1-mini}"
ROBOGENE_IMAGE_QUALITY="${ROBOGENE_IMAGE_QUALITY:-low}"
ROBOGENE_IMAGE_SIZE="${ROBOGENE_IMAGE_SIZE:-1024x1024}"
ROBOGENE_SIGNALR_HUB="${ROBOGENE_SIGNALR_HUB:-robogene}"
ROBOGENE_TABLE_EPISODES="${ROBOGENE_TABLE_EPISODES:-robogeneEpisodes}"
AZ_SIGNALR_SKU="${AZ_SIGNALR_SKU:-Standard_S1}"

if [[ -n "$DEPLOY_ZIP" && ! -f "$DEPLOY_ZIP" ]]; then
  echo "deploy zip not found: $DEPLOY_ZIP" >&2
  exit 1
fi

echo "Plan:"
echo "  subscription: $AZ_SUBSCRIPTION_ID"
echo "  location:     $AZ_LOCATION"
echo "  rg:           $AZ_RESOURCE_GROUP"
echo "  function app: $AZ_FUNCTION_APP"
echo "  storage:      $AZ_STORAGE_ACCOUNT"
echo "  signalr:      $AZ_SIGNALR_NAME ($AZ_SIGNALR_SKU)"
echo "  app insights: $AZ_APPINSIGHTS_NAME"
if [[ -n "$DEPLOY_ZIP" ]]; then
  echo "  deploy zip:   $DEPLOY_ZIP"
fi

if [[ "$ASSUME_YES" -ne 1 ]]; then
  read -r -p "Proceed with create/update? [y/N] " reply
  if [[ ! "$reply" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
  fi
fi

az account set --subscription "$AZ_SUBSCRIPTION_ID"

echo "Ensuring resource group..."
az group create -n "$AZ_RESOURCE_GROUP" -l "$AZ_LOCATION" -o none

echo "Ensuring storage account..."
if ! az storage account show -g "$AZ_RESOURCE_GROUP" -n "$AZ_STORAGE_ACCOUNT" -o none 2>/dev/null; then
  az storage account create \
    -g "$AZ_RESOURCE_GROUP" \
    -n "$AZ_STORAGE_ACCOUNT" \
    -l "$AZ_LOCATION" \
    --sku Standard_LRS \
    --kind StorageV2 \
    --allow-blob-public-access false \
    -o none
fi

echo "Ensuring Application Insights..."
if ! az monitor app-insights component show -g "$AZ_RESOURCE_GROUP" -a "$AZ_APPINSIGHTS_NAME" -o none 2>/dev/null; then
  az monitor app-insights component create \
    -g "$AZ_RESOURCE_GROUP" \
    -a "$AZ_APPINSIGHTS_NAME" \
    -l "$AZ_LOCATION" \
    --application-type web \
    -o none
fi
APPINSIGHTS_CONN="$(az monitor app-insights component show -g "$AZ_RESOURCE_GROUP" -a "$AZ_APPINSIGHTS_NAME" --query connectionString -o tsv)"

echo "Ensuring SignalR..."
if ! az signalr show -g "$AZ_RESOURCE_GROUP" -n "$AZ_SIGNALR_NAME" -o none 2>/dev/null; then
  az signalr create \
    -g "$AZ_RESOURCE_GROUP" \
    -n "$AZ_SIGNALR_NAME" \
    -l "$AZ_LOCATION" \
    --sku "$AZ_SIGNALR_SKU" \
    --service-mode Default \
    --unit-count 1 \
    -o none
fi
SIGNALR_CONN="$(az signalr key list -g "$AZ_RESOURCE_GROUP" -n "$AZ_SIGNALR_NAME" --query primaryConnectionString -o tsv)"

echo "Ensuring Function App..."
if ! az functionapp show -g "$AZ_RESOURCE_GROUP" -n "$AZ_FUNCTION_APP" -o none 2>/dev/null; then
  az functionapp create \
    -g "$AZ_RESOURCE_GROUP" \
    -n "$AZ_FUNCTION_APP" \
    --consumption-plan-location "$AZ_LOCATION" \
    --storage-account "$AZ_STORAGE_ACCOUNT" \
    --runtime node \
    --runtime-version 22 \
    --functions-version 4 \
    -o none
fi

echo "Wiring app settings..."
az functionapp config appsettings set \
  -g "$AZ_RESOURCE_GROUP" \
  -n "$AZ_FUNCTION_APP" \
  --settings \
    FUNCTIONS_WORKER_RUNTIME=node \
    WEBSITE_NODE_DEFAULT_VERSION=~22 \
    WEBSITE_RUN_FROM_PACKAGE=1 \
    APPLICATIONINSIGHTS_CONNECTION_STRING="$APPINSIGHTS_CONN" \
    AzureSignalRConnectionString="$SIGNALR_CONN" \
    ROBOGENE_SIGNALR_HUB="$ROBOGENE_SIGNALR_HUB" \
    ROBOGENE_ALLOWED_ORIGIN="$ROBOGENE_ALLOWED_ORIGIN" \
    ROBOGENE_IMAGE_GENERATOR="$ROBOGENE_IMAGE_GENERATOR" \
    ROBOGENE_IMAGE_GENERATOR_KEY="$ROBOGENE_IMAGE_GENERATOR_KEY" \
    ROBOGENE_IMAGE_MODEL="$ROBOGENE_IMAGE_MODEL" \
    ROBOGENE_IMAGE_QUALITY="$ROBOGENE_IMAGE_QUALITY" \
    ROBOGENE_IMAGE_SIZE="$ROBOGENE_IMAGE_SIZE" \
    ROBOGENE_TABLE_EPISODES="$ROBOGENE_TABLE_EPISODES" \
  -o none

if [[ -n "$DEPLOY_ZIP" ]]; then
  echo "Deploying package zip..."
  az functionapp deployment source config-zip \
    -g "$AZ_RESOURCE_GROUP" \
    -n "$AZ_FUNCTION_APP" \
    --src "$DEPLOY_ZIP" \
    -o none
fi

HOSTNAME="$(az functionapp show -g "$AZ_RESOURCE_GROUP" -n "$AZ_FUNCTION_APP" --query defaultHostName -o tsv)"

echo
echo "Done."
echo "  Function URL: https://${HOSTNAME}/api/state"
echo "  App Insights: https://portal.azure.com/#resource$(az monitor app-insights component show -g "$AZ_RESOURCE_GROUP" -a "$AZ_APPINSIGHTS_NAME" --query id -o tsv)/overview"
echo ""
echo "If deploying from GitHub Actions, ensure these repository secrets/vars are set:"
echo "  secrets: AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_SUBSCRIPTION_ID (OIDC) OR AZURE_FUNCTIONAPP_PUBLISH_PROFILE"
echo "  secrets: ROBOGENE_IMAGE_GENERATOR_KEY"
echo "  vars:    ROBOGENE_IMAGE_GENERATOR, AZURE_DEPLOY_AUTH_MODE"
