#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

robogene_require_command az
robogene_require_env AZ_RESOURCE_GROUP
robogene_require_env AZ_LOCATION
robogene_require_env AZ_STORAGE_ACCOUNT

AZ_STORAGE_ACCOUNT="$(robogene_sanitize_storage_name "$AZ_STORAGE_ACCOUNT")"

robogene_set_subscription_if_present
robogene_ensure_resource_group "$AZ_RESOURCE_GROUP" "$AZ_LOCATION"

robogene_deploy_template \
  "$AZ_RESOURCE_GROUP" \
  "robogene-database" \
  "$SCRIPT_DIR/infra/modules/database.bicep" \
  storageAccountName="$AZ_STORAGE_ACCOUNT" \
  location="$AZ_LOCATION"

STORAGE_CONNECTION_STRING="$(robogene_storage_connection_string "$AZ_RESOURCE_GROUP" "$AZ_STORAGE_ACCOUNT")"
robogene_ensure_storage_schema "$STORAGE_CONNECTION_STRING"

echo "Database/storage ready"
echo "  storage: $AZ_STORAGE_ACCOUNT"
echo "  tables: ${ROBOGENE_TABLE_STATE:-robogeneState}, ${ROBOGENE_TABLE_CHAPTER:-robogeneChapter}, ${ROBOGENE_TABLE_FRAME:-robogeneFrame}, robogeneEpisodes"
echo "  container: ${ROBOGENE_BLOB_CONTAINER:-robogene-images}"
