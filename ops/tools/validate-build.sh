#!/usr/bin/env bash
#
# validate-build.sh - Build-only validation for CI/CD environments
#
# This script validates the Fukuii build process without requiring Docker.
# It's designed for CI/CD pipelines and development environments (like
# GitHub Codespaces) where Docker might not be available.
#
# Usage: ./validate-build.sh
#
# Exit codes:
#   0 - All validations passed
#   1 - Build or validation failed
#

set -euo pipefail

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Function to print colored output
print_error() {
    echo -e "${RED}✗ ERROR: $1${NC}" >&2
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ WARNING: $1${NC}"
}

print_step() {
    echo
    echo -e "${BLUE}===================================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}===================================================${NC}"
}

# Cleanup function
cleanup() {
    if [ -n "${TEMP_EXTRACT_DIR:-}" ] && [ -d "$TEMP_EXTRACT_DIR" ]; then
        print_info "Cleaning up temporary files..."
        rm -rf "$TEMP_EXTRACT_DIR"
    fi
}

trap cleanup EXIT

# Change to project root
cd "$PROJECT_ROOT"

print_step "Fukuii Build-Only Validation"
echo "This script validates the build process without requiring Docker."
echo

# Step 1: Check Java
print_step "Step 1/6: Checking Java Installation"
if ! command -v java &> /dev/null; then
    print_error "Java is not installed"
    echo "Please install Java 21 LTS or later"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "\K[0-9]+' | head -1)
if [ "$JAVA_VERSION" -lt 11 ]; then
    print_error "Java version $JAVA_VERSION is too old (Java 11+ required, Java 21 LTS recommended)"
    exit 1
fi

print_success "Java $JAVA_VERSION installed"

# Step 2: Check SBT
print_step "Step 2/6: Checking SBT Installation"
if ! command -v sbt &> /dev/null; then
    print_warning "SBT is not installed globally"
    print_info "Will use project's SBT wrapper if available"
    # Check for sbt wrapper script
    if [ ! -f "project/build.properties" ]; then
        print_error "SBT is not available and project does not have SBT configuration"
        exit 1
    fi
    SBT_CMD="sbt"
else
    SBT_VERSION=$(sbt --version 2>&1 | grep "sbt version" | grep -oP '\d+\.\d+\.\d+')
    print_success "SBT $SBT_VERSION installed"
    SBT_CMD="sbt"
fi

# Step 3: Initialize Git Submodules
print_step "Step 3/6: Initializing Git Submodules"
if [ -f ".gitmodules" ]; then
    print_info "Updating git submodules..."
    if git submodule update --init --recursive; then
        print_success "Git submodules initialized"
    else
        print_error "Failed to initialize git submodules"
        exit 1
    fi
else
    print_info "No git submodules to initialize"
fi

# Step 4: Compile the project
print_step "Step 4/6: Compiling Fukuii"
print_info "This may take several minutes on first run..."

if $SBT_CMD compile; then
    print_success "Compilation successful"
else
    print_error "Compilation failed"
    exit 1
fi

# Step 5: Create distribution package
print_step "Step 5/6: Creating Distribution Package"
print_info "Building universal distribution package..."

if $SBT_CMD universal:packageBin; then
    print_success "Distribution package created"
else
    print_error "Failed to create distribution package"
    exit 1
fi

# Find the created package
PACKAGE_PATH=$(find target/universal -name "fukuii-*.zip" -type f | head -1)
if [ -z "$PACKAGE_PATH" ]; then
    print_error "Could not find distribution package"
    exit 1
fi

PACKAGE_SIZE=$(du -h "$PACKAGE_PATH" | cut -f1)
print_success "Package created: $PACKAGE_PATH ($PACKAGE_SIZE)"

# Step 6: Validate package contents
print_step "Step 6/6: Validating Package Contents"
print_info "Extracting and validating package..."

TEMP_EXTRACT_DIR=$(mktemp -d)
if ! unzip -q "$PACKAGE_PATH" -d "$TEMP_EXTRACT_DIR"; then
    print_error "Failed to extract package"
    exit 1
fi

# Find the extracted directory
EXTRACTED_DIR=$(find "$TEMP_EXTRACT_DIR" -maxdepth 1 -type d -name "fukuii-*" | head -1)
if [ -z "$EXTRACTED_DIR" ]; then
    print_error "Could not find extracted directory"
    exit 1
fi

# Check for required directories and files
VALIDATION_FAILED=0

check_exists() {
    local path="$1"
    local description="$2"
    
    if [ -e "$EXTRACTED_DIR/$path" ]; then
        print_success "$description exists"
    else
        print_error "$description not found: $path"
        VALIDATION_FAILED=1
    fi
}

check_exists "bin/fukuii" "Main executable"
check_exists "conf" "Configuration directory"
check_exists "lib" "Library directory"

# Check executable permissions
if [ -x "$EXTRACTED_DIR/bin/fukuii" ]; then
    print_success "Main executable has correct permissions"
else
    print_error "Main executable is not executable"
    VALIDATION_FAILED=1
fi

# Try to run help command
print_info "Testing executable..."
if "$EXTRACTED_DIR/bin/fukuii" --help &> /dev/null; then
    print_success "Executable runs successfully"
else
    print_error "Executable failed to run"
    VALIDATION_FAILED=1
fi

# Final result
echo
print_step "Validation Results"

if [ $VALIDATION_FAILED -eq 0 ]; then
    echo
    print_success "All build validations passed!"
    echo
    print_info "Build artifacts:"
    echo "  - Distribution: $PACKAGE_PATH"
    echo "  - Size: $PACKAGE_SIZE"
    echo
    print_info "Next steps:"
    echo "  - To run network tests, install Docker and use ops/tools/check-docker.sh"
    echo "  - To deploy, extract the package and run bin/fukuii"
    echo
    exit 0
else
    echo
    print_error "Some validations failed"
    echo
    exit 1
fi
