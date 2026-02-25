#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

WEBAPI_PORT="${1:-7071}"
ALLOWED_ORIGIN="${2:-http://localhost:8080,http://127.0.0.1:8080}"
WEBAPI_BUILD_CMD="${3:-build:webapi:debug}"

FUNC_PID=""

start_func() {
  npm run api_host:start -- --port "$WEBAPI_PORT" --cors "$ALLOWED_ORIGIN" &
  FUNC_PID=$!
}

stop_func() {
  if [[ -n "${FUNC_PID:-}" ]] && kill -0 "$FUNC_PID" 2>/dev/null; then
    kill "$FUNC_PID" 2>/dev/null || true
    wait "$FUNC_PID" 2>/dev/null || true
  fi
  FUNC_PID=""
}

cleanup() {
  stop_func
}

trap cleanup EXIT INT TERM

build_api() {
  echo "Building webapi bundle ($WEBAPI_BUILD_CMD)..."
  npm run "$WEBAPI_BUILD_CMD"
}

snapshot() {
  {
    find src/api_host src/services -type f 2>/dev/null || true
    printf '%s\n' shadow-cljs.edn deps.edn package.json package-lock.json
  } \
    | sed '/^$/d' \
    | while IFS= read -r path; do
        if [[ -f "$path" ]]; then
          stat -f '%m %N' "$path"
        fi
      done \
    | shasum \
    | awk '{print $1}'
}

build_api
start_func

last_snapshot="$(snapshot)"

while true; do
  sleep 1
  current_snapshot="$(snapshot)"
  if [[ "$current_snapshot" != "$last_snapshot" ]]; then
    echo "API source/config changed. Rebuilding and restarting Azure Functions host..."
    if build_api; then
      stop_func
      start_func
      last_snapshot="$current_snapshot"
    else
      echo "Webapi build failed. Keeping previous host process alive."
    fi
  fi
done
