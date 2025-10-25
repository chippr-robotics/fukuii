#!/bin/bash
set -e

# Build script for Fukuii Ethereum Client
# This script builds the fat JAR using sbt assembly

echo "=================================="
echo "Building Fukuii Ethereum Client"
echo "=================================="

# Check for required tools
if ! command -v sbt &> /dev/null; then
    echo "Error: sbt is not installed"
    echo "Please install sbt first. See README.md for instructions."
    exit 1
fi

if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed"
    echo "Please install JDK 11 or higher. See README.md for instructions."
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | awk -F '.' '{print $1}')
if [ "$JAVA_VERSION" -lt 11 ]; then
    echo "Error: Java 11 or higher is required (found Java $JAVA_VERSION)"
    exit 1
fi

echo "Java version: $(java -version 2>&1 | head -n 1)"
echo "SBT version: $(sbt --version 2>&1 | grep 'sbt script version')"
echo ""

# Navigate to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# Update git submodules if needed
if [ -f .gitmodules ]; then
    echo "Updating git submodules..."
    git submodule update --init --recursive
fi

# Build the fat JAR
echo ""
echo "Building fat JAR with sbt assembly..."
export FUKUII_DEV=true
sbt clean assembly

# Check if build was successful
if [ -f target/scala-2.13/fukuii-*.jar ]; then
    JAR_FILE=$(ls -1 target/scala-2.13/fukuii-*.jar | head -n 1)
    echo ""
    echo "=================================="
    echo "Build successful!"
    echo "Fat JAR created: $JAR_FILE"
    echo "=================================="
    exit 0
else
    echo ""
    echo "=================================="
    echo "Build failed - JAR file not found"
    echo "=================================="
    exit 1
fi
