#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${1:-robogene-func-prod}"
RESOURCE_GROUP="${2:-robogene-rg}"
LOOKBACK="${3:-24h}"

if ! command -v az >/dev/null 2>&1; then
  echo "Azure CLI (az) is required." >&2
  exit 1
fi

echo "Azure Monitor summary"
echo "  app: $APP_NAME"
echo "  rg:  $RESOURCE_GROUP"
echo "  since: $LOOKBACK"
echo

echo "== Requests by function =="
az monitor app-insights query \
  --app "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --analytics-query "requests | where timestamp > ago($LOOKBACK) | summarize total=count(), failed=countif(success==false), p95Ms=round(percentile(duration,95),2), avgMs=round(avg(duration),2) by name | order by total desc" \
  -o table || true

echo
echo "== Error rate (overall) =="
az monitor app-insights query \
  --app "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --analytics-query "requests | where timestamp > ago($LOOKBACK) | summarize total=count(), failed=countif(success==false), errorRatePct=round(100.0 * todouble(countif(success==false)) / iif(count()==0, 1.0, todouble(count())), 2)" \
  -o table || true

echo
echo "== Recent exceptions =="
az monitor app-insights query \
  --app "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --analytics-query "exceptions | where timestamp > ago($LOOKBACK) | project timestamp, type, outerMessage | order by timestamp desc | take 10" \
  -o table || true

echo
echo "== Recent robogene invocation traces =="
az monitor app-insights query \
  --app "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --analytics-query "traces | where timestamp > ago($LOOKBACK) | where message startswith '[robogene] invoke' | project timestamp, message | order by timestamp desc | take 10" \
  -o table || true
