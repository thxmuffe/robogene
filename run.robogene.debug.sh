#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

MODE="debug"
if [[ $# -gt 0 ]]; then
  case "$1" in
    --release) MODE="release" ;;
    --debug) MODE="debug" ;;
    *)
      echo "Usage: $0 [--debug|--release]"
      exit 1
      ;;
  esac
fi

WEBAPP_DIST_DIR="dist/${MODE}/webapp"
if [[ "$MODE" == "release" ]]; then
  WEBAPI_BUILD_CMD="build:webapi"
  WEBAPP_BUILD_CMD="build"
  WEBAPP_RUN_MODE="static"
else
  WEBAPI_BUILD_CMD="build:webapi:debug"
  WEBAPP_BUILD_CMD=""
  WEBAPP_RUN_MODE="watch"
fi

ENV_FILE="robogen.debug.env"
ENV_EXAMPLE="robogen.debug.env.example"
WEBAPP_PORT="${WEBAPP_PORT:-8080}"
WEBAPI_PORT="${WEBAPI_PORT:-7071}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE"
  echo "Create it from $ENV_EXAMPLE and fill in real values."
  exit 1
fi

set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

if [[ -z "${AzureWebJobsStorage:-}" && -n "${ROBOGENE_STORAGE_CONNECTION_STRING:-}" ]]; then
  export AzureWebJobsStorage="$ROBOGENE_STORAGE_CONNECTION_STRING"
fi

if [[ "${ROBOGENE_IMAGE_GENERATOR:-openai}" == "openai" && -z "${ROBOGENE_IMAGE_GENERATOR_KEY:-}" ]]; then
  echo "Missing ROBOGENE_IMAGE_GENERATOR_KEY in $ENV_FILE (required for ROBOGENE_IMAGE_GENERATOR=openai)."
  exit 1
fi

if lsof -iTCP:"$WEBAPI_PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
  echo "Port $WEBAPI_PORT is already in use. Stop that process first."
  lsof -iTCP:"$WEBAPI_PORT" -sTCP:LISTEN -n -P || true
  exit 1
fi

if [[ "$WEBAPP_RUN_MODE" == "static" ]]; then
  if lsof -iTCP:"$WEBAPP_PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Port $WEBAPP_PORT is already in use. Stop that process first."
    lsof -iTCP:"$WEBAPP_PORT" -sTCP:LISTEN -n -P || true
    exit 1
  fi

  if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required for release webapp static server on port $WEBAPP_PORT."
    exit 1
  fi

  echo "Building webapi ${MODE} bundle..."
  npm run "$WEBAPI_BUILD_CMD"
  echo "Building webapp ${MODE} bundle..."
  npm run "$WEBAPP_BUILD_CMD"
  mkdir -p "$WEBAPP_DIST_DIR"
  cp src/webapp/index.html src/webapp/styles.css "$WEBAPP_DIST_DIR/"

  python3 -m http.server "$WEBAPP_PORT" --directory "$WEBAPP_DIST_DIR" >/dev/null 2>&1 &
  WEBAPP_PID=$!
else
  echo "Building webapi ${MODE} bundle..."
  npm run "$WEBAPI_BUILD_CMD"

  mkdir -p "$WEBAPP_DIST_DIR"
  cp src/webapp/index.html src/webapp/styles.css "$WEBAPP_DIST_DIR/"

  npm run watch &
  WEBAPP_PID=$!
fi

if [[ -z "${ROBOGENE_ALLOWED_ORIGIN:-}" ]]; then
  echo "Missing ROBOGENE_ALLOWED_ORIGIN in $ENV_FILE"
  echo "Set it explicitly, e.g. ROBOGENE_ALLOWED_ORIGIN='http://localhost:${WEBAPP_PORT},http://127.0.0.1:${WEBAPP_PORT}'"
  exit 1
fi

if [[ "$WEBAPP_RUN_MODE" == "static" ]]; then
  npm run api_host:start -- --port "$WEBAPI_PORT" --cors "$ROBOGENE_ALLOWED_ORIGIN" &
  API_PID=$!
else
  ./scripts/run-api-host-watch.sh "$WEBAPI_PORT" "$ROBOGENE_ALLOWED_ORIGIN" "$WEBAPI_BUILD_CMD" &
  API_PID=$!
fi

trap 'kill $WEBAPP_PID $API_PID 2>/dev/null || true' EXIT INT TERM

echo "Webapp: http://localhost:${WEBAPP_PORT}/index.html"
echo "Web API: http://localhost:${WEBAPI_PORT}"

wait
