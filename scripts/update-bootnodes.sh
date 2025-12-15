#!/usr/bin/env bash
#
# Update ETC bootnodes in the configuration file
# This script fetches active bootnodes from the etcnodes API
# and updates the configuration to maintain 30 active bootnodes at all times.
# (1.5x the default max outgoing connections of 20)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_FILE="${REPO_ROOT}/src/main/resources/conf/chains/etc-chain.conf"
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*"
}

# Function to fetch bootnodes from etcnodes API
fetch_bootnodes_from_sources() {
    log_info "Fetching bootnodes from etcnodes API..."
    
    # Fetch from etcnodes API - the authoritative source of live ETC nodes
    local ETCNODES_API="https://api.etcnodes.org/peers"
    
    log_info "Fetching from ${ETCNODES_API}..."
    
    # Fetch full JSON and process it
    # Extract enode, replace port with 30303, and include last seen timestamp for sorting
    curl -s "${ETCNODES_API}" 2>/dev/null | \
        jq -r '.[] | "\(.contact.last.unix)|\(.enode)"' | \
        while IFS='|' read -r timestamp enode; do
            # Replace the port in the enode with 30303
            # Handle both formats: @ip:port and @ip:port?discport=X
            if [[ "$enode" =~ ^(enode://[a-fA-F0-9]{128}@[^:]+):[0-9]+(.*)$ ]]; then
                normalized_enode="${BASH_REMATCH[1]}:30303${BASH_REMATCH[2]}"
                echo "${timestamp}|${normalized_enode}"
            fi
        done | sort -t'|' -k1,1nr > "${TEMP_DIR}/etcnodes_bootnodes_with_timestamps.txt"
    
    # Extract just the enodes (without timestamps) for compatibility with existing code
    cut -d'|' -f2 "${TEMP_DIR}/etcnodes_bootnodes_with_timestamps.txt" | \
        sort -u > "${TEMP_DIR}/etcnodes_bootnodes.txt"
    
    local etcnodes_count=$(wc -l < "${TEMP_DIR}/etcnodes_bootnodes.txt" || echo 0)
    log_info "Found ${etcnodes_count} live bootnodes from etcnodes API (sorted by last seen)"
    
    # Use etcnodes as the ONLY external source (no geth/besu)
    cp "${TEMP_DIR}/etcnodes_bootnodes.txt" "${TEMP_DIR}/all_external_bootnodes.txt"
    
    local count=$(wc -l < "${TEMP_DIR}/all_external_bootnodes.txt")
    log_info "Total bootnodes from etcnodes API: ${count}"
}

# Function to extract current bootnodes from config
extract_current_bootnodes() {
    log_info "Extracting current bootnodes from ${CONFIG_FILE}..."
    
    # Extract enodes from the bootstrap-nodes array
    # Use awk to find start and end of the array reliably
    awk '/bootstrap-nodes = \[/,/^\s*\]/' "${CONFIG_FILE}" | \
        grep 'enode://' | \
        grep -o 'enode://[^"]*' | \
        sort -u > "${TEMP_DIR}/current_bootnodes.txt"
    
    local count=$(wc -l < "${TEMP_DIR}/current_bootnodes.txt")
    log_info "Current configuration has ${count} bootnodes"
}

# Function to validate enode URL format
validate_enode() {
    local enode="$1"
    
    # Basic validation: should start with enode:// and have @ and :
    # Node IDs are 128 hex characters (can be lowercase or uppercase)
    if [[ "$enode" =~ ^enode://[a-fA-F0-9]{128}@[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+ ]]; then
        return 0
    elif [[ "$enode" =~ ^enode://[a-fA-F0-9]{128}@[^:]+:[0-9]+(\?discport=[0-9]+)?$ ]]; then
        return 0
    else
        return 1
    fi
}

# Function to check if bootnode uses standard port (30303)
has_standard_port() {
    local enode="$1"
    if [[ "$enode" =~ :30303(\?|$) ]]; then
        return 0
    else
        return 1
    fi
}

# Function to select best bootnodes
select_bootnodes() {
    local target_count=30  # 1.5x the default max outgoing connections (20)
    
    log_info "Selecting ${target_count} best bootnodes..."
    
    # Read current and external bootnodes
    local -a current=()
    local -a external=()
    local -a selected=()
    
    while IFS= read -r line; do
        [[ -n "$line" ]] && current+=("$line")
    done < "${TEMP_DIR}/current_bootnodes.txt"
    
    while IFS= read -r line; do
        [[ -n "$line" ]] && external+=("$line")
    done < "${TEMP_DIR}/all_external_bootnodes.txt"
    
    log_info "Current: ${#current[@]}, External: ${#external[@]}"
    
    # Priority 1: Keep current bootnodes that are in external API (they're still alive)
    log_info "Phase 1: Keeping current bootnodes found in live API..."
    for bootnode in "${current[@]}"; do
        if validate_enode "$bootnode"; then
            # Check if this bootnode is in the external API list
            if printf '%s\n' "${external[@]}" | grep -q "^${bootnode}$"; then
                selected+=("$bootnode")
                log_info "✓ Keeping live bootnode: ${bootnode:0:50}..."
                if [ ${#selected[@]} -ge ${target_count} ]; then
                    break
                fi
            fi
        fi
    done
    
    # Priority 2: Add new bootnodes from API (sorted by last seen, most recent first)
    if [ ${#selected[@]} -lt ${target_count} ]; then
        log_info "Phase 2: Adding new bootnodes from live API..."
        for bootnode in "${external[@]}"; do
            # Skip if already selected
            if printf '%s\n' "${selected[@]}" | grep -q "^${bootnode}$"; then
                continue
            fi
            
            if validate_enode "$bootnode"; then
                selected+=("$bootnode")
                log_info "✓ Adding live bootnode: ${bootnode:0:50}..."
                
                if [ ${#selected[@]} -ge ${target_count} ]; then
                    break
                fi
            fi
        done
    fi
    
    # Log removed bootnodes (nodes not in API are considered dead)
    for bootnode in "${current[@]}"; do
        if ! printf '%s\n' "${selected[@]}" | grep -q "^${bootnode}$"; then
            log_warn "✗ Removing dead bootnode (not in live API): ${bootnode:0:50}..."
        fi
    done
    
    # Save selected bootnodes
    printf '%s\n' "${selected[@]}" > "${TEMP_DIR}/selected_bootnodes.txt"
    
    log_info "Selected ${#selected[@]} bootnodes for configuration"
    
    if [ ${#selected[@]} -lt ${target_count} ]; then
        log_warn "Warning: Only ${#selected[@]} bootnodes available (target: ${target_count})"
    fi
}

# Function to update config file
update_config_file() {
    log_info "Updating configuration file..."
    
    local backup_file="${CONFIG_FILE}.backup.$(date +%Y%m%d_%H%M%S)"
    cp "${CONFIG_FILE}" "${backup_file}"
    log_info "Created backup: ${backup_file}"
    
    # Create new bootnode section
    cat > "${TEMP_DIR}/new_bootnodes.conf" <<EOF
  # Set of initial nodes
  # Updated automatically by scripts/update-bootnodes.sh on nightly schedule
  # Last updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')
  # Combined from core-geth and other authoritative sources
  bootstrap-nodes = [
EOF
    
    # Read selected bootnodes and format them
    local first=true
    while IFS= read -r enode; do
        if [ "$first" = true ]; then
            first=false
        else
            echo "," >> "${TEMP_DIR}/new_bootnodes.conf"
        fi
        echo -n "    \"${enode}\"" >> "${TEMP_DIR}/new_bootnodes.conf"
    done < "${TEMP_DIR}/selected_bootnodes.txt"
    
    echo "" >> "${TEMP_DIR}/new_bootnodes.conf"
    echo "  ]" >> "${TEMP_DIR}/new_bootnodes.conf"
    
    # Replace the bootstrap-nodes section in the config file
    # Find the start and end of the bootstrap-nodes section
    local start_line=$(grep -n '# Set of initial nodes' "${CONFIG_FILE}" | head -1 | cut -d: -f1)
    
    if [ -z "$start_line" ]; then
        # Fallback: find bootstrap-nodes array start
        start_line=$(grep -n 'bootstrap-nodes = \[' "${CONFIG_FILE}" | cut -d: -f1)
        if [ -z "$start_line" ]; then
            log_error "Could not find bootstrap-nodes section in config file"
            return 1
        fi
        # Go back to capture comments
        start_line=$((start_line - 2))
    fi
    
    local end_line=$(tail -n +$((start_line + 1)) "${CONFIG_FILE}" | grep -n '^\s*\]' | head -1 | cut -d: -f1)
    end_line=$((start_line + end_line))
    
    # Create new config file
    head -n $((start_line - 1)) "${CONFIG_FILE}" > "${TEMP_DIR}/new_config.conf"
    cat "${TEMP_DIR}/new_bootnodes.conf" >> "${TEMP_DIR}/new_config.conf"
    tail -n +$((end_line + 1)) "${CONFIG_FILE}" >> "${TEMP_DIR}/new_config.conf"
    
    # Replace original file
    mv "${TEMP_DIR}/new_config.conf" "${CONFIG_FILE}"
    
    log_info "Configuration file updated successfully"
}

# Function to display summary
display_summary() {
    log_info "=== Bootnode Update Summary ==="
    
    local current_count=$(wc -l < "${TEMP_DIR}/current_bootnodes.txt")
    local selected_count=$(wc -l < "${TEMP_DIR}/selected_bootnodes.txt")
    
    echo "  Current bootnodes: ${current_count}"
    echo "  Updated bootnodes: ${selected_count}"
    
    # Count removed and added
    local removed=0
    local added=0
    
    while IFS= read -r bootnode; do
        if ! grep -q "^${bootnode}$" "${TEMP_DIR}/selected_bootnodes.txt"; then
            ((removed++)) || true
        fi
    done < "${TEMP_DIR}/current_bootnodes.txt"
    
    while IFS= read -r bootnode; do
        if ! grep -q "^${bootnode}$" "${TEMP_DIR}/current_bootnodes.txt"; then
            ((added++)) || true
        fi
    done < "${TEMP_DIR}/selected_bootnodes.txt"
    
    echo "  Removed: ${removed}"
    echo "  Added: ${added}"
    echo "  Kept: $((selected_count - added))"
}

# Main execution
main() {
    log_info "=== ETC Bootnode Update Script ==="
    log_info "Repository: ${REPO_ROOT}"
    log_info "Config file: ${CONFIG_FILE}"
    
    # Check if config file exists
    if [ ! -f "${CONFIG_FILE}" ]; then
        log_error "Configuration file not found: ${CONFIG_FILE}"
        exit 1
    fi
    
    # Extract current bootnodes first
    extract_current_bootnodes
    
    # Fetch available bootnodes
    fetch_bootnodes_from_sources
    
    # Select best bootnodes
    select_bootnodes
    
    # Update config file
    update_config_file
    
    # Display summary
    display_summary
    
    log_info "=== Update Complete ==="
}

# Run main function
main "$@"
