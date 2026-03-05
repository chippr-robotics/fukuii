#!/usr/bin/env bash

# build-all-images.sh — Build Docker images for all 3 ETC clients
# Usage: build-all-images.sh <target>
#   target: pre-olympia | olympia
#
# Builds sequential (NUC resource constraint: one heavy task at a time).
# Images are tagged with both the target name and :local for compose defaults.

set -euo pipefail

# ─── Configuration ───────────────────────────────────────────────────────────
# Adjust these paths to match your local repo locations.

FUKUII_REPO="${FUKUII_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
COREGETH_REPO="${COREGETH_REPO:-/media/dev/2tb/dev/core-geth}"
BESU_REPO="${BESU_REPO:-/media/dev/2tb/dev/besu}"

# Branch mapping per target
declare -A FUKUII_BRANCH=( [pre-olympia]=alpha [olympia]=olympia )
declare -A COREGETH_BRANCH=( [pre-olympia]=etc [olympia]=olympia )
declare -A BESU_BRANCH=( [pre-olympia]=etc [olympia]=olympia )

# ─── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ─── Functions ───────────────────────────────────────────────────────────────

usage() {
  echo "Usage: $0 <target>"
  echo "  target: pre-olympia | olympia"
  echo ""
  echo "Environment variables (optional overrides):"
  echo "  FUKUII_REPO    Path to fukuii-client repo (default: auto-detected)"
  echo "  COREGETH_REPO  Path to core-geth repo     (default: /media/dev/2tb/dev/core-geth)"
  echo "  BESU_REPO      Path to besu repo           (default: /media/dev/2tb/dev/besu)"
  exit 1
}

preflight() {
  echo -e "${BLUE}=== Pre-flight checks ===${NC}"

  # Docker
  if ! docker info &>/dev/null; then
    echo -e "${RED}ERROR: Docker is not running${NC}" >&2
    exit 1
  fi
  echo -e "${GREEN}  Docker: OK${NC}"

  # Disk space (need ~5GB free)
  local free_gb
  free_gb=$(df --output=avail -BG "$FUKUII_REPO" | tail -1 | tr -d 'G ')
  if [ "$free_gb" -lt 5 ]; then
    echo -e "${RED}ERROR: Less than 5GB free disk space (${free_gb}GB)${NC}" >&2
    exit 1
  fi
  echo -e "${GREEN}  Disk: ${free_gb}GB free${NC}"

  # Repos exist
  for repo_var in FUKUII_REPO COREGETH_REPO BESU_REPO; do
    local repo_path="${!repo_var}"
    if [ ! -d "$repo_path/.git" ]; then
      echo -e "${RED}ERROR: $repo_var ($repo_path) is not a git repository${NC}" >&2
      exit 1
    fi
  done
  echo -e "${GREEN}  Repos: all found${NC}"

  # Load average
  local load
  load=$(awk '{print $1}' /proc/loadavg)
  echo -e "${GREEN}  Load: ${load}${NC}"

  echo ""
}

build_client() {
  local name="$1"
  local repo="$2"
  local branch="$3"
  local image_name="$4"
  local tag="$5"
  local build_cmd="$6"

  echo -e "${BLUE}=== Building ${name} (branch: ${branch}, tag: ${image_name}:${tag}) ===${NC}"

  # Checkout branch
  (cd "$repo" && git checkout "$branch" 2>/dev/null)

  # Build
  eval "$build_cmd"

  # Tag with :local alias
  docker tag "${image_name}:${tag}" "${image_name}:local"

  # Report size
  local size
  size=$(docker image inspect "${image_name}:${tag}" --format='{{.Size}}' | numfmt --to=iec-i --suffix=B)
  echo -e "${GREEN}  ${image_name}:${tag} — ${size}${NC}"
  echo ""
}

# ─── Main ────────────────────────────────────────────────────────────────────

if [ $# -lt 1 ]; then
  usage
fi

TARGET="$1"

if [[ "$TARGET" != "pre-olympia" && "$TARGET" != "olympia" ]]; then
  echo -e "${RED}ERROR: Unknown target '$TARGET'. Use 'pre-olympia' or 'olympia'.${NC}" >&2
  usage
fi

preflight

START_TIME=$(date +%s)

echo -e "${BLUE}=== Building all 3 ETC clients for target: ${TARGET} ===${NC}"
echo ""

# 1. Core-geth (~10 min)
build_client \
  "Core-geth" \
  "$COREGETH_REPO" \
  "${COREGETH_BRANCH[$TARGET]}" \
  "coregeth-etc" \
  "$TARGET" \
  "cd '$COREGETH_REPO' && docker build -t coregeth-etc:${TARGET} -f Dockerfile ."

# 2. Besu (~20 min)
# Besu uses Gradle's distDocker task which builds the image directly
build_client \
  "Besu" \
  "$BESU_REPO" \
  "${BESU_BRANCH[$TARGET]}" \
  "besu-etc" \
  "$TARGET" \
  "cd '$BESU_REPO' && ./gradlew distDocker -x test && docker tag hyperledger/besu:develop besu-etc:${TARGET}"

# 3. Fukuii (~60 min)
build_client \
  "Fukuii" \
  "$FUKUII_REPO" \
  "${FUKUII_BRANCH[$TARGET]}" \
  "fukuii-etc" \
  "$TARGET" \
  "cd '$FUKUII_REPO' && git submodule update --init --recursive && docker build -t fukuii-etc:${TARGET} -f docker/Dockerfile ."

# Summary
END_TIME=$(date +%s)
ELAPSED=$(( END_TIME - START_TIME ))

echo -e "${GREEN}=== Build complete (${ELAPSED}s) ===${NC}"
echo ""
echo "Images built:"
docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}" | grep -E "^(coregeth|besu|fukuii)-etc"
echo ""
echo -e "${YELLOW}Use with Gorgoroth:${NC}"
echo "  cd ops/gorgoroth"
echo "  docker compose --env-file .env.${TARGET} -f docker-compose-3nodes.yml up -d"
