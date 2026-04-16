#!/usr/bin/env bash
set -euo pipefail

HERE="$(dirname "${BASH_SOURCE[0]}")"

cd "$HERE/../../"
sbt 'set version := "latest"' docker:publishLocal

if docker compose version &>/dev/null; then
  docker compose -f docker/fukuii/docker-compose.yml up -d
elif command -v docker-compose &>/dev/null; then
  docker-compose -f docker/fukuii/docker-compose.yml up -d
else
  echo "ERROR: Neither 'docker compose' nor 'docker-compose' found" >&2
  exit 1
fi
