#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

usage() {
  cat <<USAGE
Usage:
  $0 [--deploy-zip <path>] [--build-and-deploy] [--yes]

Required env vars:
  AZ_SUBSCRIPTION_ID
  AZ_LOCATION
  AZ_RESOURCE_GROUP
  AZ_STORAGE_ACCOUNT
  AZ_FUNCTION_APP
  AZ_APPINSIGHTS_NAME
  AZ_SIGNALR_NAME
  ROBOGENE_IMAGE_GENERATOR_KEY
USAGE
}

DEPLOY_ZIP=""
BUILD_AND_DEPLOY=0
ASSUME_YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --deploy-zip)
      DEPLOY_ZIP="${2:-}"
      shift 2
      ;;
    --build-and-deploy)
      BUILD_AND_DEPLOY=1
      shift
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
  AZ_STORAGE_ACCOUNT
  AZ_FUNCTION_APP
  AZ_APPINSIGHTS_NAME
  AZ_SIGNALR_NAME
  ROBOGENE_IMAGE_GENERATOR_KEY
)
for v in "${required[@]}"; do
  if [[ -z "${!v:-}" ]]; then
    echo "Missing required env var: $v" >&2
    usage
    exit 1
  fi
done

if [[ -n "$DEPLOY_ZIP" && ! -f "$DEPLOY_ZIP" ]]; then
  echo "deploy zip not found: $DEPLOY_ZIP" >&2
  exit 1
fi

if [[ "$BUILD_AND_DEPLOY" -eq 1 && -n "$DEPLOY_ZIP" ]]; then
  echo "Use either --build-and-deploy or --deploy-zip, not both." >&2
  exit 1
fi

echo "Plan"
echo "  subscription: $AZ_SUBSCRIPTION_ID"
echo "  location:     $AZ_LOCATION"
echo "  rg:           $AZ_RESOURCE_GROUP"
echo "  storage:      $AZ_STORAGE_ACCOUNT"
echo "  app insights: $AZ_APPINSIGHTS_NAME"
echo "  signalr:      $AZ_SIGNALR_NAME"
echo "  function app: $AZ_FUNCTION_APP"

if [[ "$ASSUME_YES" -ne 1 ]]; then
  read -r -p "Proceed? [y/N] " reply
  if [[ ! "$reply" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
  fi
fi

"$SCRIPT_DIR/provision_app_insights.sh"
"$SCRIPT_DIR/provision_aux_services.sh"
"$SCRIPT_DIR/provision_database.sh"
"$SCRIPT_DIR/provision_function_app.sh"

if [[ "$BUILD_AND_DEPLOY" -eq 1 ]]; then
  echo "Building and packaging webapi..."
  cd "$REPO_ROOT"
  npm install >/dev/null
  npm run build:webapi >/dev/null
  npm run package:webapi >/dev/null
  DEPLOY_ZIP="$REPO_ROOT/dist/release/webapi/webapi_dist.zip"
fi

if [[ -n "$DEPLOY_ZIP" ]]; then
  echo "Deploying webapi zip..."
  az functionapp deployment source config-zip \
    -g "$AZ_RESOURCE_GROUP" \
    -n "$AZ_FUNCTION_APP" \
    --src "$DEPLOY_ZIP" \
    -o none
fi

echo "Done."
