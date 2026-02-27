#!/usr/bin/env bash

robogene_default_allowed_origins() {
  echo "https://thxmuffe.github.io,http://localhost:8080,http://127.0.0.1:8080,http://localhost:5500,http://127.0.0.1:5500,http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173"
}

robogene_require_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

robogene_require_env() {
  local var_name="$1"
  if [[ -z "${!var_name:-}" ]]; then
    echo "Missing required env var: $var_name" >&2
    exit 1
  fi
}

robogene_set_subscription_if_present() {
  if [[ -n "${AZ_SUBSCRIPTION_ID:-}" ]]; then
    az account set --subscription "$AZ_SUBSCRIPTION_ID"
  fi
}

robogene_sanitize_storage_name() {
  local raw="$1"
  local name
  name="$(echo "$raw" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"
  if [[ ${#name} -lt 3 ]]; then
    name="robogene$RANDOM"
  fi
  if [[ ${#name} -gt 24 ]]; then
    name="${name:0:24}"
  fi
  echo "$name"
}

robogene_ensure_resource_group() {
  local rg="$1"
  local loc="$2"
  az group create -n "$rg" -l "$loc" -o none
}

robogene_deploy_template() {
  local rg="$1"
  local deployment_name="$2"
  local template_file="$3"
  shift 3
  az deployment group create \
    --resource-group "$rg" \
    --name "$deployment_name" \
    --template-file "$template_file" \
    --parameters "$@" \
    -o none
}

robogene_storage_connection_string() {
  local rg="$1"
  local storage="$2"
  az storage account show-connection-string -g "$rg" -n "$storage" --query connectionString -o tsv
}

robogene_ensure_storage_schema() {
  local connection_string="$1"
  local container_name="${ROBOGENE_BLOB_CONTAINER:-robogene-images}"

  local -a table_names=(
    "${ROBOGENE_TABLE_STATE:-robogeneState}"
    "${ROBOGENE_TABLE_CHAPTER:-robogeneChapter}"
    "${ROBOGENE_TABLE_FRAME:-robogeneFrame}"
    "robogeneEpisodes"
  )

  az storage container create \
    --name "$container_name" \
    --connection-string "$connection_string" \
    --auth-mode key \
    -o none

  local t
  for t in "${table_names[@]}"; do
    az storage table create \
      --name "$t" \
      --connection-string "$connection_string" \
      --auth-mode key \
      -o none
  done
}

robogene_deploy_webapi_zip() {
  local rg="$1"
  local app="$2"
  local zip_path="$3"

  az functionapp deployment source config-zip \
    -g "$rg" \
    -n "$app" \
    --src "$zip_path" \
    -o none
}
