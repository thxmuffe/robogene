#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${AZURE_CONFIG_DIR:-}" ]]; then
  echo "Set AZURE_CONFIG_DIR first (example: export AZURE_CONFIG_DIR=$(pwd)/../.azure)"
  exit 1
fi

if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <resource-group> <location> <function-app-name> [plan-name] [storage-account-name]"
  exit 1
fi

RG="$1"
LOC="$2"
APP="$3"
PLAN="${4:-${APP}-plan}"
ST="${5:-${APP//-/}st}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  for ENV_PATH in "$SCRIPT_DIR/../.env" "$SCRIPT_DIR/../../pop.env"; do
    if [[ -f "$ENV_PATH" ]]; then
      # shellcheck disable=SC1090
      set -a; source "$ENV_PATH"; set +a
      break
    fi
  done
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "OPENAI_API_KEY is required (not found in env, ../.env, or ../../pop.env)."
  exit 1
fi

az group create -n "$RG" -l "$LOC" >/dev/null
az storage account create -g "$RG" -n "$ST" -l "$LOC" --sku Standard_LRS >/dev/null
az functionapp plan create -g "$RG" -n "$PLAN" --location "$LOC" --number-of-workers 1 --sku B1 --is-linux >/dev/null
az functionapp create -g "$RG" -p "$PLAN" -n "$APP" -s "$ST" --runtime node --runtime-version 20 --functions-version 4 >/dev/null

az functionapp config appsettings set -g "$RG" -n "$APP" --settings \
  OPENAI_API_KEY="$OPENAI_API_KEY" \
  ROBOGENE_IMAGE_MODEL="gpt-image-1" \
  ROBOGENE_ALLOWED_ORIGIN="https://thxmuffe.github.io" \
  AzureWebJobsFeatureFlags="EnableWorkerIndexing" >/dev/null

npm install
rm -f deploy.zip
zip -r deploy.zip . -x '*.git*' 'local.settings.json' >/dev/null
az functionapp deployment source config-zip -g "$RG" -n "$APP" --src deploy.zip >/dev/null

echo "Deployed backend: https://${APP}.azurewebsites.net"
echo "Set frontend ROBOGENE_API_BASE to that URL."
