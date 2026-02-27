#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

robogene_require_command az
robogene_require_env AZ_RESOURCE_GROUP
robogene_require_env AZ_LOCATION
robogene_require_env AZ_STORAGE_ACCOUNT
robogene_require_env AZ_FUNCTION_APP
robogene_require_env AZ_APPINSIGHTS_NAME
robogene_require_env AZ_SIGNALR_NAME
robogene_require_env ROBOGENE_IMAGE_GENERATOR_KEY

AZ_STORAGE_ACCOUNT="$(robogene_sanitize_storage_name "$AZ_STORAGE_ACCOUNT")"
ROBOGENE_ALLOWED_ORIGIN="${ROBOGENE_ALLOWED_ORIGIN:-$(robogene_default_allowed_origins)}"

robogene_set_subscription_if_present
robogene_ensure_resource_group "$AZ_RESOURCE_GROUP" "$AZ_LOCATION"

robogene_deploy_template \
  "$AZ_RESOURCE_GROUP" \
  "robogene-functionapp" \
  "$SCRIPT_DIR/infra/modules/function_app.bicep" \
  functionAppName="$AZ_FUNCTION_APP" \
  location="$AZ_LOCATION" \
  storageAccountName="$AZ_STORAGE_ACCOUNT" \
  appInsightsName="$AZ_APPINSIGHTS_NAME" \
  signalrName="$AZ_SIGNALR_NAME" \
  imageGeneratorKey="$ROBOGENE_IMAGE_GENERATOR_KEY" \
  allowedOrigin="$ROBOGENE_ALLOWED_ORIGIN" \
  imageGenerator="${ROBOGENE_IMAGE_GENERATOR:-openai}" \
  signalrHub="${ROBOGENE_SIGNALR_HUB:-robogene}"

HOSTNAME="$(az functionapp show -g "$AZ_RESOURCE_GROUP" -n "$AZ_FUNCTION_APP" --query defaultHostName -o tsv)"

echo "Function app ready: $AZ_FUNCTION_APP"
echo "  url: https://${HOSTNAME}"
echo "  state endpoint: https://${HOSTNAME}/api/state"
