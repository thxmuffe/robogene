#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

HOST_SRC_DIR="$REPO_ROOT/src/api_host"
AI_SRC_DIR="$REPO_ROOT/ai/robot emperor"
WEBAPI_DIST_DIR="$REPO_ROOT/dist/release/webapi"
APP_DIST_DIR="$WEBAPI_DIST_DIR/app"
COMPILED_WEBAPI_JS="$WEBAPI_DIST_DIR/webapi_compiled.js"
WEBAPI_ZIP="$WEBAPI_DIST_DIR/webapi_dist.zip"

if [[ ! -f "$COMPILED_WEBAPI_JS" ]]; then
  echo "Missing compiled services output: $COMPILED_WEBAPI_JS"
  echo "Run: npm run build:webapi"
  exit 1
fi

mkdir -p "$WEBAPI_DIST_DIR"
rm -rf "$APP_DIST_DIR"
mkdir -p "$APP_DIST_DIR"

# Function host bootstrap/runtime files.
rsync -a --delete --exclude 'local.settings.json' "$HOST_SRC_DIR/" "$APP_DIST_DIR/"

# Install production-only runtime dependencies into deploy package.
# This avoids shipping full dev/build dependencies and significantly shrinks deploy zip size.
cp "$APP_DIST_DIR/package.json" "$APP_DIST_DIR/package.host.json"
cp "$REPO_ROOT/package.json" "$APP_DIST_DIR/package.json"
cp "$REPO_ROOT/package-lock.json" "$APP_DIST_DIR/package-lock.json"
(cd "$APP_DIST_DIR" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund)
mv "$APP_DIST_DIR/package.host.json" "$APP_DIST_DIR/package.json"
rm -f "$APP_DIST_DIR/package-lock.json"

# Compiled ClojureScript services loaded by story_routes_host.js.
mkdir -p "$APP_DIST_DIR/dist"
cp "$COMPILED_WEBAPI_JS" "$APP_DIST_DIR/dist/webapi_compiled.js"

# Story/reference assets consumed by services.
if [[ -d "$AI_SRC_DIR" ]]; then
  mkdir -p "$APP_DIST_DIR/ai/robot emperor"
  rsync -a --delete "$AI_SRC_DIR/" "$APP_DIST_DIR/ai/robot emperor/"
fi

rm -f "$WEBAPI_ZIP"
(cd "$APP_DIST_DIR" && zip -rq "$WEBAPI_ZIP" .)

echo "Assembled Function App: $APP_DIST_DIR"
echo "Packaged zip: $WEBAPI_ZIP"
