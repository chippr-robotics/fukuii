#!/usr/bin/env bash
# Run script for Fukuii Docker container
# Usage: ./scripts/dev/docker-run.sh [NETWORK]

set -euo pipefail

# Get the script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Configuration
IMAGE_NAME="${IMAGE_NAME:-fukuii}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
CONTAINER_NAME="${CONTAINER_NAME:-fukuii-node}"
FUKUII_NETWORK="${1:-etc}"

# Data and config directories
DATA_DIR="${DATA_DIR:-${REPO_ROOT}/docker-data/mantis}"
CONF_DIR="${CONF_DIR:-${REPO_ROOT}/src/main/resources/conf}"

# Create data directory if it doesn't exist
mkdir -p "${DATA_DIR}"

echo "================================================"
echo "Running Fukuii Docker Container"
echo "================================================"
echo "Image:       ${IMAGE_NAME}:${IMAGE_TAG}"
echo "Container:   ${CONTAINER_NAME}"
echo "Network:     ${FUKUII_NETWORK}"
echo "Data Dir:    ${DATA_DIR}"
echo "Config Dir:  ${CONF_DIR}"
echo "================================================"

# Stop and remove existing container if it exists
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Stopping and removing existing container..."
    docker stop "${CONTAINER_NAME}" >/dev/null 2>&1 || true
    docker rm "${CONTAINER_NAME}" >/dev/null 2>&1 || true
fi

# Run the container
docker run -d \
  --name "${CONTAINER_NAME}" \
  --restart unless-stopped \
  -p 9076:9076 \
  -p 8545:8545 \
  -p 8546:8546 \
  -v "${DATA_DIR}:/var/lib/mantis" \
  -v "${CONF_DIR}:/opt/fukuii/conf:ro" \
  -e FUKUII_DATA_DIR=/var/lib/mantis \
  -e FUKUII_NETWORK="${FUKUII_NETWORK}" \
  -e JAVA_OPTS="-Xmx2g -Xms1g" \
  "${IMAGE_NAME}:${IMAGE_TAG}" \
  "${FUKUII_NETWORK}"

echo ""
echo "================================================"
echo "Container Started Successfully!"
echo "================================================"
echo ""
echo "To view logs:"
echo "  docker logs -f ${CONTAINER_NAME}"
echo ""
echo "To check container status:"
echo "  docker ps -a | grep ${CONTAINER_NAME}"
echo ""
echo "To stop the container:"
echo "  docker stop ${CONTAINER_NAME}"
echo ""
echo "To remove the container:"
echo "  docker rm ${CONTAINER_NAME}"
echo ""
echo "Health check will be available after ~60 seconds"
echo "================================================"
