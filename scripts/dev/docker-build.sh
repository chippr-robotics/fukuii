#!/usr/bin/env bash
# Build script for Fukuii Docker image
# Usage: ./scripts/dev/docker-build.sh [IMAGE_TAG]

set -euo pipefail

# Get the script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Configuration
IMAGE_NAME="${IMAGE_NAME:-fukuii}"
IMAGE_TAG="${1:-latest}"
BUILD_DATE="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
GIT_COMMIT="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)"
GIT_BRANCH="$(git -C "${REPO_ROOT}" rev-parse --abbrev-ref HEAD)"
VERSION="$(grep 'version :=' "${REPO_ROOT}/version.sbt" | sed 's/.*"\(.*\)".*/\1/' || echo 'unknown')"

echo "================================================"
echo "Building Fukuii Docker Image"
echo "================================================"
echo "Image:       ${IMAGE_NAME}:${IMAGE_TAG}"
echo "Version:     ${VERSION}"
echo "Commit:      ${GIT_COMMIT}"
echo "Branch:      ${GIT_BRANCH}"
echo "Build Date:  ${BUILD_DATE}"
echo "================================================"

# Build the Docker image
docker build \
  --build-arg BUILD_DATE="${BUILD_DATE}" \
  --build-arg VCS_REF="${GIT_COMMIT}" \
  --build-arg VERSION="${VERSION}" \
  --label "org.opencontainers.image.created=${BUILD_DATE}" \
  --label "org.opencontainers.image.revision=${GIT_COMMIT}" \
  --label "org.opencontainers.image.version=${VERSION}" \
  -t "${IMAGE_NAME}:${IMAGE_TAG}" \
  -t "${IMAGE_NAME}:${VERSION}" \
  -t "${IMAGE_NAME}:${GIT_COMMIT}" \
  -f "${REPO_ROOT}/Dockerfile" \
  "${REPO_ROOT}"

echo ""
echo "================================================"
echo "Build Complete!"
echo "================================================"
echo "Tagged as:"
echo "  - ${IMAGE_NAME}:${IMAGE_TAG}"
echo "  - ${IMAGE_NAME}:${VERSION}"
echo "  - ${IMAGE_NAME}:${GIT_COMMIT}"
echo ""
echo "To run the container, use:"
echo "  ./scripts/dev/docker-run.sh"
echo "================================================"
