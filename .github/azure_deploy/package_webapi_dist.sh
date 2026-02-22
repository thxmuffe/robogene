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
rsync -a --delete --delete-excluded \
  --exclude 'local.settings.json' \
  --exclude '*.example' \
  "$HOST_SRC_DIR/" "$APP_DIST_DIR/"

# Install production-only runtime dependencies into deploy package.
# This avoids shipping full dev/build dependencies and significantly shrinks deploy zip size.
cp "$APP_DIST_DIR/package.json" "$APP_DIST_DIR/package.host.json"
node > "$APP_DIST_DIR/package.json" <<'NODE'
const fs = require("fs");
const rootPkg = JSON.parse(fs.readFileSync("package.json", "utf8"));
const rootLock = JSON.parse(fs.readFileSync("package-lock.json", "utf8"));
const runtimeDeps = [
  "@azure/functions",
  "@azure/data-tables",
  "@azure/storage-blob"
];

const exactVersion = (dep) =>
  rootLock?.packages?.[`node_modules/${dep}`]?.version ||
  rootPkg?.dependencies?.[dep];

const dependencies = Object.fromEntries(
  runtimeDeps.map((dep) => [dep, exactVersion(dep)]).filter(([, v]) => !!v)
);

process.stdout.write(
  JSON.stringify(
    {
      name: "robogene-webapi-runtime",
      private: true,
      description: "Runtime-only dependencies for RoboGene Web API deployment",
      main: "story_routes_host.js",
      engines: rootPkg.engines,
      dependencies
    },
    null,
    2
  ) + "\n"
);
NODE
(cd "$APP_DIST_DIR" && npm install --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund)
mv "$APP_DIST_DIR/package.host.json" "$APP_DIST_DIR/package.json"
rm -f "$APP_DIST_DIR/package-lock.json"

# Conservative cleanup: remove clear non-runtime docs/tests only.
if [[ -d "$APP_DIST_DIR/node_modules" ]]; then
  for d in test tests __tests__ docs doc; do
    find "$APP_DIST_DIR/node_modules" -type d -name "$d" -prune -exec rm -rf {} +
  done
  find "$APP_DIST_DIR/node_modules" -type f \( -name "*.md" -o -name "*.markdown" \) -delete
fi

# Compiled ClojureScript services loaded by story_routes_host.js.
mkdir -p "$APP_DIST_DIR/dist"
cp "$COMPILED_WEBAPI_JS" "$APP_DIST_DIR/dist/webapi_compiled.js"

# Story/reference assets consumed by services.
if [[ -d "$AI_SRC_DIR" ]]; then
  mkdir -p "$APP_DIST_DIR/ai/robot emperor"
  rsync -a --delete --delete-excluded \
    --exclude '*.example' \
    "$AI_SRC_DIR/" "$APP_DIST_DIR/ai/robot emperor/"
fi

rm -f "$WEBAPI_ZIP"
(cd "$APP_DIST_DIR" && zip -rq "$WEBAPI_ZIP" .)

echo "Assembled Function App: $APP_DIST_DIR"
echo "Packaged zip: $WEBAPI_ZIP"
