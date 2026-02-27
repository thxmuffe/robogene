#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

robogene_require_command az
robogene_require_env AZ_RESOURCE_GROUP
robogene_require_env AZ_LOCATION
robogene_require_env AZ_SIGNALR_NAME

AZ_SIGNALR_SKU="${AZ_SIGNALR_SKU:-Standard_S1}"

robogene_set_subscription_if_present
robogene_ensure_resource_group "$AZ_RESOURCE_GROUP" "$AZ_LOCATION"

robogene_deploy_template \
  "$AZ_RESOURCE_GROUP" \
  "robogene-signalr" \
  "$SCRIPT_DIR/infra/modules/signalr.bicep" \
  signalrName="$AZ_SIGNALR_NAME" \
  location="$AZ_LOCATION" \
  skuName="$AZ_SIGNALR_SKU"

echo "Aux services ready"
echo "  SignalR: $AZ_SIGNALR_NAME ($AZ_SIGNALR_SKU)"
