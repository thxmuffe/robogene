#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <overlay-env-file> <command...>"
  exit 1
fi

BASE_ENV_FILE="robogen.debug.env"
OVERLAY_ENV_FILE="$1"
shift

if [[ ! -f "$BASE_ENV_FILE" ]]; then
  echo "Missing $BASE_ENV_FILE"
  exit 1
fi

if [[ ! -f "$OVERLAY_ENV_FILE" ]]; then
  echo "Missing $OVERLAY_ENV_FILE"
  exit 1
fi

set -a
# shellcheck source=/dev/null
source "$BASE_ENV_FILE"
# shellcheck source=/dev/null
source "$OVERLAY_ENV_FILE"
set +a

exec "$@"
