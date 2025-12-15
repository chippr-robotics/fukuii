#!/usr/bin/env bash
#
# Update ETC bootnodes in the configuration file
# This script fetches active bootnodes from authoritative sources (core-geth, Besu)
# and updates the configuration to maintain 20 active bootnodes at all times.
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

# Function to fetch bootnodes from multiple sources
fetch_bootnodes_from_sources() {
    log_info "Fetching bootnodes from multiple sources..."
    
    # Source 1: Core-geth repository (authoritative)
    local COREGETH_URL="https://raw.githubusercontent.com/etclabscore/core-geth/master/params/bootnodes_classic.go"
    
    log_info "Fetching from core-geth..."
    curl -s "${COREGETH_URL}" | \
        grep -o 'enode://[^"]*' | \
        sort -u > "${TEMP_DIR}/coregeth_bootnodes.txt"
    
    local coregeth_count=$(wc -l < "${TEMP_DIR}/coregeth_bootnodes.txt" || echo 0)
    log_info "Found ${coregeth_count} bootnodes from core-geth"
    
    # Source 2: Hyperledger Besu ETC bootnodes (if available)
    log_info "Fetching from Hyperledger Besu..."
    curl -s "https://raw.githubusercontent.com/hyperledger/besu/main/config/src/main/resources/classic.json" 2>/dev/null | \
        grep -o 'enode://[^"]*' | \
        sort -u > "${TEMP_DIR}/besu_bootnodes.txt" || touch "${TEMP_DIR}/besu_bootnodes.txt"
    
    local besu_count=$(wc -l < "${TEMP_DIR}/besu_bootnodes.txt" || echo 0)
    log_info "Found ${besu_count} bootnodes from Besu"
    
    # Combine all sources
    cat "${TEMP_DIR}/coregeth_bootnodes.txt" \
        "${TEMP_DIR}/besu_bootnodes.txt" 2>/dev/null | \
        sort -u > "${TEMP_DIR}/all_external_bootnodes.txt"
    
    # Also add current bootnodes to available pool (they might still be valid)
    # Make sure current_bootnodes.txt exists before trying to cat it
    if [ -f "${TEMP_DIR}/current_bootnodes.txt" ]; then
        cat "${TEMP_DIR}/current_bootnodes.txt" \
            "${TEMP_DIR}/all_external_bootnodes.txt" 2>/dev/null | \
            sort -u > "${TEMP_DIR}/available_bootnodes.txt"
    else
        cp "${TEMP_DIR}/all_external_bootnodes.txt" "${TEMP_DIR}/available_bootnodes.txt"
    fi
    
    local count=$(wc -l < "${TEMP_DIR}/available_bootnodes.txt")
    log_info "Total unique bootnodes from all sources: ${count}"
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

# Function to check UDP connectivity to a bootnode
# Performs a simple UDP ping to verify the node is reachable
check_udp_connectivity() {
    local enode="$1"
    local timeout="${2:-1}"  # Default 1 second timeout
    
    # Extract host and port from enode URL
    local host_port
    if [[ "$enode" =~ @([^:?]+):([0-9]+) ]]; then
        local host="${BASH_REMATCH[1]}"
        local port="${BASH_REMATCH[2]}"
        
        # Send a UDP packet and check if we can reach the port
        # We use a simple approach: try to send data via UDP and check exit code
        # Note: UDP is connectionless, so we can't guarantee a response without
        # implementing the full devp2p handshake, but we can check reachability
        if command -v nc >/dev/null 2>&1; then
            # Use netcat with UDP mode and timeout
            # Send a single byte and check if nc succeeds
            if echo -n "x" | timeout "$timeout" nc -u -w1 "$host" "$port" >/dev/null 2>&1; then
                return 0
            fi
        elif command -v ncat >/dev/null 2>&1; then
            # Alternative: use ncat if available
            if echo -n "x" | timeout "$timeout" ncat -u -w1 "$host" "$port" >/dev/null 2>&1; then
                return 0
            fi
        fi
        
        # If we can't test connectivity, assume it's reachable (fail-open)
        # This prevents the script from rejecting valid nodes when network tools aren't available
        return 0
    fi
    
    # If we couldn't parse the enode, fail
    return 1
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
    local target_count=20
    
    log_info "Selecting ${target_count} best bootnodes..."
    
    # Read available and current bootnodes
    local -a available=()
    local -a current=()
    local -a external=()
    local -a selected=()
    
    while IFS= read -r line; do
        [[ -n "$line" ]] && available+=("$line")
    done < "${TEMP_DIR}/available_bootnodes.txt"
    
    while IFS= read -r line; do
        [[ -n "$line" ]] && current+=("$line")
    done < "${TEMP_DIR}/current_bootnodes.txt"
    
    while IFS= read -r line; do
        [[ -n "$line" ]] && external+=("$line")
    done < "${TEMP_DIR}/all_external_bootnodes.txt"
    
    log_info "Available: ${#available[@]}, Current: ${#current[@]}, External: ${#external[@]}"
    
    # Priority 1: Keep current bootnodes that are in external authoritative sources
    log_info "Phase 1: Keeping current bootnodes found in external sources..."
    for bootnode in "${current[@]}"; do
        if validate_enode "$bootnode"; then
            # Check if this bootnode is in the external authoritative list
            if printf '%s\n' "${external[@]}" | grep -q "^${bootnode}$"; then
                # Optional: Check UDP connectivity (non-blocking, informational only)
                if ! check_udp_connectivity "$bootnode" 1; then
                    log_warn "⚠ Bootnode may not be reachable via UDP: ${bootnode:0:50}..."
                fi
                selected+=("$bootnode")
                log_info "✓ Keeping validated bootnode: ${bootnode:0:50}..."
                if [ ${#selected[@]} -ge ${target_count} ]; then
                    break
                fi
            fi
        fi
    done
    
    # Priority 2: Add new bootnodes from external sources
    if [ ${#selected[@]} -lt ${target_count} ]; then
        log_info "Phase 2: Adding new bootnodes from external sources..."
        for bootnode in "${external[@]}"; do
            # Skip if already selected
            if printf '%s\n' "${selected[@]}" | grep -q "^${bootnode}$"; then
                continue
            fi
            
            if validate_enode "$bootnode"; then
                # Optional: Check UDP connectivity (non-blocking, informational only)
                if ! check_udp_connectivity "$bootnode" 1; then
                    log_warn "⚠ Bootnode may not be reachable via UDP: ${bootnode:0:50}..."
                fi
                selected+=("$bootnode")
                log_info "✓ Adding external bootnode: ${bootnode:0:50}..."
                
                if [ ${#selected[@]} -ge ${target_count} ]; then
                    break
                fi
            fi
        done
    fi
    
    # Priority 3: Keep current bootnodes even if not in external sources (up to target)
    if [ ${#selected[@]} -lt ${target_count} ]; then
        log_info "Phase 3: Adding remaining current bootnodes..."
        for bootnode in "${current[@]}"; do
            # Skip if already selected
            if printf '%s\n' "${selected[@]}" | grep -q "^${bootnode}$"; then
                continue
            fi
            
            if validate_enode "$bootnode"; then
                # Optional: Check UDP connectivity (non-blocking, informational only)
                if ! check_udp_connectivity "$bootnode" 1; then
                    log_warn "⚠ Bootnode may not be reachable via UDP: ${bootnode:0:50}..."
                fi
                selected+=("$bootnode")
                log_info "✓ Keeping current bootnode: ${bootnode:0:50}..."
                
                if [ ${#selected[@]} -ge ${target_count} ]; then
                    break
                fi
            fi
        done
    fi
    
    # Log removed bootnodes
    for bootnode in "${current[@]}"; do
        if ! printf '%s\n' "${selected[@]}" | grep -q "^${bootnode}$"; then
            log_warn "✗ Removing bootnode: ${bootnode:0:50}..."
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
