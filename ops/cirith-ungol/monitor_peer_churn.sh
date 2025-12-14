#!/usr/bin/env bash
# Monitor Fukuii logs for TCP churn errors and trigger log capture once detected.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PATTERN=${1:-"Stopping Connection"}

# Discover docker-compose / docker compose
if command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD="docker-compose"
elif docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
else
  echo "Error: Neither docker-compose nor docker compose is available." >&2
  exit 1
fi

LOG_SERVICE="fukuii"

echo "Monitoring '$LOG_SERVICE' logs for pattern: $PATTERN"
echo "Press Ctrl+C to abort."

# Use stdbuf if available to ensure line-buffered output from docker logs
if command -v stdbuf >/dev/null 2>&1; then
  LOG_CMD=(stdbuf -oL "$COMPOSE_CMD" logs -f "$LOG_SERVICE")
else
  LOG_CMD=("$COMPOSE_CMD" logs -f "$LOG_SERVICE")
fi

"${LOG_CMD[@]}" | while IFS= read -r line; do
  printf '%s\n' "$line"
  if [[ "$line" == *"$PATTERN"* ]]; then
    timestamp="$(date +%Y-%m-%dT%H:%M:%S%z)"
    echo "Pattern detected at $timestamp — capturing logs via start.sh collect-logs"
    ./start.sh collect-logs || echo "Log collection command exited with status $?" >&2
    echo "Log capture complete. Exiting monitor."
    break
  fi
done
