#!/bin/bash
set -e

echo "=========================================="
echo "Fukuii Rebranding Script"
echo "Rebranding from IOHK Mantis to Chippr Robotics Fukuii"
echo "=========================================="
echo ""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Backup directory
BACKUP_DIR="./rebrand_backup_$(date +%Y%m%d_%H%M%S)"

echo -e "${YELLOW}Creating backup at: ${BACKUP_DIR}${NC}"
mkdir -p "$BACKUP_DIR"

# Function to backup a file before modifying
backup_file() {
    local file="$1"
    local backup_path="$BACKUP_DIR/$(dirname "$file")"
    mkdir -p "$backup_path"
    cp "$file" "$backup_path/" 2>/dev/null || true
}

echo ""
echo "Step 1: Renaming directory structure from io/iohk to com/chipprbots"
echo "----------------------------------------------------------------------"

# Find and rename directory structures
for module_dir in . bytes crypto rlp; do
    echo "Processing module: $module_dir"
    
    # Handle src/main/scala
    if [ -d "$module_dir/src/main/scala/io/iohk" ]; then
        echo "  - Moving $module_dir/src/main/scala/io/iohk to com/chipprbots"
        mkdir -p "$module_dir/src/main/scala/com/chipprbots"
        
        # Copy the ethereum directory structure
        if [ -d "$module_dir/src/main/scala/io/iohk/ethereum" ]; then
            cp -r "$module_dir/src/main/scala/io/iohk/ethereum" "$module_dir/src/main/scala/com/chipprbots/" || true
        fi
    fi
    
    # Handle src/test/scala
    if [ -d "$module_dir/src/test/scala/io/iohk" ]; then
        echo "  - Moving $module_dir/src/test/scala/io/iohk to com/chipprbots"
        mkdir -p "$module_dir/src/test/scala/com/chipprbots"
        
        # Copy the ethereum directory structure
        if [ -d "$module_dir/src/test/scala/io/iohk/ethereum" ]; then
            cp -r "$module_dir/src/test/scala/io/iohk/ethereum" "$module_dir/src/test/scala/com/chipprbots/" || true
        fi
    fi
    
    # Handle other test directories
    for test_dir in it evmTest rpcTest benchmark; do
        if [ -d "$module_dir/src/$test_dir/scala/io/iohk" ]; then
            echo "  - Moving $module_dir/src/$test_dir/scala/io/iohk to com/chipprbots"
            mkdir -p "$module_dir/src/$test_dir/scala/com/chipprbots"
            
            if [ -d "$module_dir/src/$test_dir/scala/io/iohk/ethereum" ]; then
                cp -r "$module_dir/src/$test_dir/scala/io/iohk/ethereum" "$module_dir/src/$test_dir/scala/com/chipprbots/" || true
            fi
        fi
    done
done

echo ""
echo "Step 2: Updating package declarations and imports in Scala files"
echo "----------------------------------------------------------------------"

# Find all Scala files and update package declarations and imports
find . -type f -name "*.scala" ! -path "*/target/*" ! -path "*/.git/*" | while read -r file; do
    if [ -f "$file" ]; then
        backup_file "$file"
        
        # Replace package declarations (at the start of lines)
        sed -i 's/^package io\.iohk\.ethereum/package com.chipprbots.ethereum/g' "$file"
        
        # Replace import statements
        sed -i 's/import io\.iohk\.ethereum/import com.chipprbots.ethereum/g' "$file"
        
        # Update Scaladoc references to package names
        sed -i 's/\[\[io\.iohk\.ethereum/[[com.chipprbots.ethereum/g' "$file"
    fi
done

echo ""
echo "Step 3: Updating protobuf package declarations"
echo "----------------------------------------------------------------------"

# Update protobuf files
find . -type f -name "*.proto" ! -path "*/target/*" ! -path "*/.git/*" | while read -r file; do
    if [ -f "$file" ]; then
        backup_file "$file"
        sed -i 's/^package io\.iohk\.ethereum/package com.chipprbots.ethereum/g' "$file"
    fi
done

echo ""
echo "Step 4: Updating build configuration files"
echo "----------------------------------------------------------------------"

# Update build.sbt
if [ -f "build.sbt" ]; then
    backup_file "build.sbt"
    
    # Update organization (but not in URLs or git references)
    sed -i 's/organization := "io\.iohk"/organization := "com.chipprbots"/g' build.sbt
    
    # Update package names in build configuration
    sed -i 's/buildInfoPackage := "io\.iohk\.ethereum\.utils"/buildInfoPackage := "com.chipprbots.ethereum.utils"/g' build.sbt
    
    # Update main class
    sed -i 's/mainClass.*:= Some("io\.iohk\.ethereum\.App")/mainClass) := Some("com.chipprbots.ethereum.App"/g' build.sbt
    
    # Update coverage excluded packages
    sed -i 's/coverageExcludedPackages := "io\\\\.iohk\\\\.ethereum\\\\.extvm\\\\.msg\.\*"/coverageExcludedPackages := "com\\\\.chipprbots\\\\.ethereum\\\\.extvm\\\\.msg.*"/g' build.sbt
    
    # Update compiler optimizations
    sed -i 's/-opt-inline-from:io\.iohk\.\*\*/-opt-inline-from:com.chipprbots.**/g' build.sbt
    
    # Update project homepage and SCM info to reflect new ownership
    sed -i 's|homepage := Some(url("https://github.com/input-output-hk/mantis"))|homepage := Some(url("https://github.com/chippr-robotics/chordodes_fukuii"))|g' build.sbt
    sed -i 's|ScmInfo(url("https://github.com/input-output-hk/mantis"), "git@github.com:input-output-hk/mantis.git")|ScmInfo(url("https://github.com/chippr-robotics/chordodes_fukuii"), "git@github.com:chippr-robotics/chordodes_fukuii.git")|g' build.sbt
fi

# Update .scalafix.conf
if [ -f ".scalafix.conf" ]; then
    backup_file ".scalafix.conf"
    sed -i 's/"io\.iohk\.ethereum\./"com.chipprbots.ethereum./g' .scalafix.conf
fi

echo ""
echo "Step 5: Renaming mantis to fukuii"
echo "----------------------------------------------------------------------"

# Update script names and content
# Note: Nix configuration files have been removed from the repository
# as the project now uses GitHub Actions for CI/CD instead of Buildkite with Nix

# Update shell scripts
for script in test-ets.sh ets/run; do
    if [ -f "$script" ]; then
        backup_file "$script"
        sed -i 's/\bmantis\b/fukuii/g' "$script" || true
        sed -i 's/\bMantis\b/Fukuii/g' "$script" || true
        sed -i 's/fukuii-log\.txt/fukuii-log.txt/g' "$script" || true
    fi
done

# Update ETS config
if [ -f "ets/config/mantis/config" ]; then
    backup_file "ets/config/mantis/config"
    sed -i 's/IOHK Mantis/Chippr Robotics Fukuii/g' "ets/config/mantis/config" || true
fi

# Rename mantis directory to fukuii in ets/config
if [ -d "ets/config/mantis" ] && [ ! -d "ets/config/fukuii" ]; then
    echo "  - Renaming ets/config/mantis to ets/config/fukuii"
    cp -r "ets/config/mantis" "ets/config/fukuii"
fi

# Update retesteth script
if [ -f "ets/retesteth" ]; then
    backup_file "ets/retesteth"
    sed -i 's/--clients mantis/--clients fukuii/g' "ets/retesteth" || true
fi

# Update Docker-related files
for dockerfile in docker/Dockerfile docker/Dockerfile-base docker/Dockerfile-dev; do
    if [ -f "$dockerfile" ]; then
        backup_file "$dockerfile"
        sed -i 's/\bmantis\b/fukuii/g' "$dockerfile" || true
        sed -i 's/\bMantis\b/Fukuii/g' "$dockerfile" || true
    fi
done

# Update docker scripts
for script in docker/mantis/build.sh docker/build.sh docker/build-base.sh docker/build-dev.sh; do
    if [ -f "$script" ]; then
        backup_file "$script"
        sed -i 's/\bmantis\b/fukuii/g' "$script" || true
        sed -i 's/\bMantis\b/Fukuii/g' "$script" || true
        
        # Restore external references
        sed -i 's/fukuii-extvm-pb/mantis-extvm-pb/g' "$script" || true
        sed -i 's/fukuii-ops\.cachix\.org/mantis-ops.cachix.org/g' "$script" || true
        sed -i 's/fukuii-faucet-web/mantis-faucet-web/g' "$script" || true
        sed -i 's/fukuii-explorer/mantis-explorer/g' "$script" || true
    fi
done

# Update TLS certificate generation script
if [ -f "tls/gen-cert.sh" ]; then
    backup_file "tls/gen-cert.sh"
    sed -i 's/mantisCA\.p12/fukuiiCA.p12/g' "tls/gen-cert.sh" || true
fi

# Update Insomnia workspace
if [ -f "insomnia_workspace.json" ]; then
    backup_file "insomnia_workspace.json"
    sed -i 's/"mantis/"fukuii/g' "insomnia_workspace.json" || true
    sed -i 's/mantis_/fukuii_/g' "insomnia_workspace.json" || true
    sed -i 's/"Mantis"/"Fukuii"/g' "insomnia_workspace.json" || true
fi

# Note: nix-in-docker directory has been removed from the repository
# as the project now uses GitHub Actions for CI/CD

echo ""
echo "Step 6: Updating environment variable references"
echo "----------------------------------------------------------------------"

# Find and update environment variable references
find . -type f \( -name "*.scala" -o -name "*.conf" -o -name "*.sh" -o -name "*.md" \) \
    ! -path "*/target/*" ! -path "*/.git/*" | while read -r file; do
    if [ -f "$file" ]; then
        # Check if file contains MANTIS env vars
        if grep -q "FUKUII_" "$file" 2>/dev/null; then
            backup_file "$file"
            sed -i 's/FUKUII_/FUKUII_/g' "$file" || true
        fi
    fi
done

echo ""
echo "Step 7: Cleaning up old io/iohk directories"
echo "----------------------------------------------------------------------"

# After copying, remove old io/iohk directories
for module_dir in . bytes crypto rlp; do
    for src_type in main test it evmTest rpcTest benchmark; do
        if [ -d "$module_dir/src/$src_type/scala/io/iohk" ]; then
            echo "  - Removing $module_dir/src/$src_type/scala/io/iohk"
            rm -rf "$module_dir/src/$src_type/scala/io/iohk" || true
        fi
        
        # Remove io directory if empty
        if [ -d "$module_dir/src/$src_type/scala/io" ] && [ -z "$(ls -A "$module_dir/src/$src_type/scala/io" 2>/dev/null)" ]; then
            echo "  - Removing empty $module_dir/src/$src_type/scala/io"
            rmdir "$module_dir/src/$src_type/scala/io" 2>/dev/null || true
        fi
    done
done

echo ""
echo "Step 8: Renaming mantis directories to fukuii"
echo "----------------------------------------------------------------------"

# Rename docker/mantis to docker/fukuii
if [ -d "docker/mantis" ] && [ ! -d "docker/fukuii" ]; then
    echo "  - Renaming docker/mantis to docker/fukuii"
    cp -r "docker/mantis" "docker/fukuii"
    # Update references in the new directory
    find "docker/fukuii" -type f -exec sed -i 's/\bmantis\b/fukuii/g' {} + || true
    find "docker/fukuii" -type f -exec sed -i 's/\bMantis\b/Fukuii/g' {} + || true
fi

echo ""
echo -e "${GREEN}=========================================="
echo "Rebranding Complete!"
echo "==========================================${NC}"
echo ""
echo "Summary of changes:"
echo "  - Package structure: io.iohk.ethereum -> com.chipprbots.ethereum"
echo "  - Product name: mantis -> fukuii"
echo "  - Organization: IOHK -> Chippr Robotics, LLC"
echo ""
echo "Backup created at: $BACKUP_DIR"
echo ""
echo -e "${YELLOW}Note: External dependencies (GitHub URLs, external packages) have been preserved.${NC}"
echo ""
echo "Next steps:"
echo "  1. Review the NOTICE file and add attribution as needed"
echo "  2. Run: sbt clean"
echo "  3. Run: sbt compile"
echo "  4. Run: sbt test"
echo ""
