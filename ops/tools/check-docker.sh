#!/usr/bin/env bash
#
# check-docker.sh - Pre-flight check for Docker requirements
#
# This script verifies that Docker and Docker Compose are installed
# and meet the minimum version requirements for running Fukuii
# Gorgoroth network tests.
#
# Usage: ./check-docker.sh
#
# Exit codes:
#   0 - All checks passed
#   1 - Docker or Docker Compose not found or version too old
#

set -euo pipefail

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Minimum required versions
MIN_DOCKER_VERSION="20.10"
MIN_COMPOSE_VERSION="2.0"

# Function to print colored output
print_error() {
    echo -e "${RED}ERROR: $1${NC}" >&2
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}WARNING: $1${NC}"
}

# Function to compare version numbers
version_ge() {
    # Returns 0 (true) if $1 >= $2
    printf '%s\n%s\n' "$2" "$1" | sort -V -C
}

# Check if Docker is installed
echo "Checking Docker prerequisites..."
echo

if ! command -v docker &> /dev/null; then
    print_error "Docker is not installed"
    echo "Install Docker from: https://www.docker.com/products/docker-desktop"
    echo "Or for Linux: https://docs.docker.com/engine/install/"
    exit 1
fi

# Check Docker version
DOCKER_VERSION=$(docker --version | grep -oP '\d+\.\d+\.\d+' | head -1)
if [ -z "$DOCKER_VERSION" ]; then
    print_warning "Could not determine Docker version"
else
    DOCKER_MAJOR_MINOR=$(echo "$DOCKER_VERSION" | grep -oP '\d+\.\d+')
    if version_ge "$DOCKER_MAJOR_MINOR" "$MIN_DOCKER_VERSION"; then
        print_success "Docker $DOCKER_VERSION installed (>= $MIN_DOCKER_VERSION required)"
    else
        print_error "Docker version $DOCKER_VERSION is too old (>= $MIN_DOCKER_VERSION required)"
        echo "Please upgrade Docker to continue"
        exit 1
    fi
fi

# Check if Docker daemon is running
if ! docker info &> /dev/null; then
    print_error "Docker daemon is not running"
    echo "Please start Docker and try again"
    exit 1
fi

print_success "Docker daemon is running"

# Check Docker Compose
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    print_error "Docker Compose is not installed"
    echo "Install Docker Compose from: https://docs.docker.com/compose/install/"
    exit 1
fi

# Check Docker Compose version (try both standalone and plugin)
COMPOSE_VERSION=""
if docker compose version &> /dev/null; then
    COMPOSE_VERSION=$(docker compose version | grep -oP '\d+\.\d+\.\d+' | head -1)
elif command -v docker-compose &> /dev/null; then
    COMPOSE_VERSION=$(docker-compose --version | grep -oP '\d+\.\d+\.\d+' | head -1)
fi

if [ -z "$COMPOSE_VERSION" ]; then
    print_warning "Could not determine Docker Compose version"
else
    COMPOSE_MAJOR_MINOR=$(echo "$COMPOSE_VERSION" | grep -oP '\d+\.\d+')
    if version_ge "$COMPOSE_MAJOR_MINOR" "$MIN_COMPOSE_VERSION"; then
        print_success "Docker Compose $COMPOSE_VERSION installed (>= $MIN_COMPOSE_VERSION required)"
    else
        print_error "Docker Compose version $COMPOSE_VERSION is too old (>= $MIN_COMPOSE_VERSION required)"
        echo "Please upgrade Docker Compose to continue"
        exit 1
    fi
fi

# Check available resources
echo
echo "Checking system resources..."

# Check available RAM (Linux only)
if [ -f /proc/meminfo ]; then
    TOTAL_RAM_KB=$(grep MemTotal /proc/meminfo | awk '{print $2}')
    TOTAL_RAM_GB=$(echo "scale=1; $TOTAL_RAM_KB / 1024 / 1024" | bc)
    if (( $(echo "$TOTAL_RAM_GB < 4" | bc -l) )); then
        print_warning "Available RAM: ${TOTAL_RAM_GB}GB (4GB recommended for Gorgoroth tests)"
    else
        print_success "Available RAM: ${TOTAL_RAM_GB}GB"
    fi
fi

# Check available disk space
DISK_AVAILABLE=$(df -BG . | tail -1 | awk '{print $4}' | sed 's/G//')
if [ "$DISK_AVAILABLE" -lt 5 ]; then
    print_warning "Available disk space: ${DISK_AVAILABLE}GB (5GB+ recommended)"
else
    print_success "Available disk space: ${DISK_AVAILABLE}GB"
fi

echo
echo -e "${GREEN}All Docker prerequisites are met!${NC}"
echo "You can now run the Gorgoroth network tests."
exit 0
