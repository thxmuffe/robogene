#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

robogene_require_command az
robogene_require_env AZ_RESOURCE_GROUP
robogene_require_env AZ_LOCATION
robogene_require_env AZ_APPINSIGHTS_NAME

robogene_set_subscription_if_present
robogene_ensure_resource_group "$AZ_RESOURCE_GROUP" "$AZ_LOCATION"

robogene_deploy_template \
  "$AZ_RESOURCE_GROUP" \
  "robogene-appinsights" \
  "$SCRIPT_DIR/infra/modules/app_insights.bicep" \
  appInsightsName="$AZ_APPINSIGHTS_NAME" \
  location="$AZ_LOCATION"

APPINSIGHTS_ID="$(az monitor app-insights component show -g "$AZ_RESOURCE_GROUP" -a "$AZ_APPINSIGHTS_NAME" --query id -o tsv)"

echo "Application Insights ready: $AZ_APPINSIGHTS_NAME"
echo "  portal: https://portal.azure.com/#resource${APPINSIGHTS_ID}/overview"
