#!/bin/bash
set -e

# Run script for Fukuii Ethereum Client
# This script runs the fat JAR built by build.sh

# Navigate to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# Find the JAR file
JAR_FILE=$(ls -1 target/scala-2.13/fukuii-*.jar 2>/dev/null | head -n 1)

if [ -z "$JAR_FILE" ]; then
    echo "Error: Fat JAR not found in target/scala-2.13/"
    echo "Please run ./scripts/dev/build.sh first"
    exit 1
fi

echo "=================================="
echo "Running Fukuii Ethereum Client"
echo "=================================="
echo "JAR: $JAR_FILE"
echo ""

# Run the JAR with any passed arguments
# Default to showing help if no arguments provided
if [ $# -eq 0 ]; then
    echo "Usage: $0 [options]"
    echo ""
    echo "Examples:"
    echo "  $0 etc                    # Join ETC network"
    echo "  $0 cli --help             # Show CLI help"
    echo "  $0 cli generate-private-key  # Generate a private key"
    echo ""
    java -jar "$JAR_FILE" --help
else
    java -jar "$JAR_FILE" "$@"
fi
