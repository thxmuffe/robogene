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

SETTINGS_FILE="src/api_host/local.settings.json"
SETTINGS_EXAMPLE="src/api_host/local.settings.json.example"

if [ ! -f "$SETTINGS_FILE" ]; then
  echo "Missing $SETTINGS_FILE"
  echo "Create it from $SETTINGS_EXAMPLE and fill in real values."
  exit 1
fi

node -e '
const fs = require("fs");
const path = process.argv[1];
let parsed;
try {
  parsed = JSON.parse(fs.readFileSync(path, "utf8"));
} catch (err) {
  console.error("local.settings.json is not valid JSON:", err.message);
  process.exit(1);
}
const values = parsed && parsed.Values ? parsed.Values : {};
const storage = values.ROBOGENE_STORAGE_CONNECTION_STRING || values.AzureWebJobsStorage || "";
const signalr = values.AzureSignalRConnectionString || "";
const openai = values.OPENAI_API_KEY || "";
if (!storage) {
  console.error("local.settings.json missing ROBOGENE_STORAGE_CONNECTION_STRING or AzureWebJobsStorage.");
  process.exit(1);
}
if (String(storage).trim() === "UseDevelopmentStorage=true") {
  console.error("UseDevelopmentStorage=true requires Azurite and is not used in this setup.");
  process.exit(1);
}
if (!signalr || signalr.includes("<name>") || signalr.includes("<key>")) {
  console.error("local.settings.json must include a real AzureSignalRConnectionString.");
  process.exit(1);
}
if (!openai || String(openai).trim() === "") {
  console.warn("Warning: OPENAI_API_KEY is empty in local.settings.json");
}
' "$SETTINGS_FILE"

if lsof -iTCP:7071 -sTCP:LISTEN -n -P >/dev/null 2>&1; then
  echo "Port 7071 is already in use. Stop that process first."
  lsof -iTCP:7071 -sTCP:LISTEN -n -P || true
  exit 1
fi

if [[ "$WEBAPP_RUN_MODE" == "static" ]]; then
  if lsof -iTCP:8080 -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Port 8080 is already in use. Stop that process first."
    lsof -iTCP:8080 -sTCP:LISTEN -n -P || true
    exit 1
  fi

  if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required for release webapp static server on port 8080."
    exit 1
  fi

  echo "Building webapi ${MODE} bundle..."
  npm run "$WEBAPI_BUILD_CMD"
  echo "Building webapp ${MODE} bundle..."
  npm run "$WEBAPP_BUILD_CMD"
  mkdir -p "$WEBAPP_DIST_DIR"
  cp src/webapp/index.html src/webapp/styles.css "$WEBAPP_DIST_DIR/"

  python3 -m http.server 8080 --directory "$WEBAPP_DIST_DIR" >/dev/null 2>&1 &
  WEBAPP_PID=$!
else
  echo "Building webapi ${MODE} bundle..."
  npm run "$WEBAPI_BUILD_CMD"

  mkdir -p "$WEBAPP_DIST_DIR"
  cp src/webapp/index.html src/webapp/styles.css "$WEBAPP_DIST_DIR/"

  npm run watch &
  WEBAPP_PID=$!
fi

ROBOGENE_ALLOWED_ORIGIN="http://localhost:8080,http://127.0.0.1:8080,http://localhost:5500,http://127.0.0.1:5500,http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173" \
  npm run api_host:start &
API_PID=$!

trap 'kill $WEBAPP_PID $API_PID 2>/dev/null || true' EXIT INT TERM

echo "Webapp: http://localhost:8080/index.html"
echo "Web API: http://localhost:7071"

wait
