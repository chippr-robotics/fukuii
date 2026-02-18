#!/bin/bash
# Mining validation test script for Fukuii on Mordor testnet (Cirith Ungol)
# Tests that Fukuii can produce valid blocks via Ethash PoW mining
#
# Usage: ./test-mining-mordor.sh [--watch]
#   --watch: Continuously monitor mining status every 30 seconds

set -euo pipefail

FUKUII_RPC="http://localhost:8545"
COREGETH_RPC="http://localhost:18545"
EXPECTED_COINBASE="0x33d8f73570a229917cfd5e8c20ad057d1c19b38e"
WATCH_MODE=false

if [[ "${1:-}" == "--watch" ]]; then
    WATCH_MODE=true
fi

# JSON-RPC helper
rpc_call() {
    local url="$1"
    local method="$2"
    local params="${3:-[]}"
    curl -s -m 10 -X POST -H "Content-Type: application/json" \
        --data "{\"jsonrpc\":\"2.0\",\"method\":\"$method\",\"params\":$params,\"id\":1}" \
        "$url" 2>/dev/null
}

rpc_result() {
    local url="$1"
    local method="$2"
    local params="${3:-[]}"
    rpc_call "$url" "$method" "$params" | jq -r '.result // empty' 2>/dev/null
}

hex_to_dec() {
    local hex="${1#0x}"
    printf "%d" "0x$hex" 2>/dev/null || echo "0"
}

echo "=== Fukuii Mining Validation - Mordor Testnet ==="
echo "Fukuii RPC:   $FUKUII_RPC"
echo "Core-Geth RPC: $COREGETH_RPC"
echo "Expected coinbase: $EXPECTED_COINBASE"
echo ""

# --- Test 0: Node Connectivity ---
echo "--- Test 0: Node Connectivity ---"
ERRORS=0

FUKUII_BLOCK=$(rpc_result "$FUKUII_RPC" "eth_blockNumber")
if [[ -z "$FUKUII_BLOCK" ]]; then
    echo "  FAIL: Cannot reach Fukuii at $FUKUII_RPC"
    ERRORS=$((ERRORS + 1))
else
    echo "  OK: Fukuii reachable, block $(hex_to_dec "$FUKUII_BLOCK")"
fi

GETH_BLOCK=$(rpc_result "$COREGETH_RPC" "eth_blockNumber")
if [[ -z "$GETH_BLOCK" ]]; then
    echo "  WARN: Cannot reach Core-Geth at $COREGETH_RPC (cross-client checks disabled)"
else
    echo "  OK: Core-Geth reachable, block $(hex_to_dec "$GETH_BLOCK")"
fi

if [[ $ERRORS -gt 0 ]]; then
    echo ""
    echo "FAIL: Cannot reach Fukuii node. Is Cirith Ungol running?"
    exit 1
fi
echo ""

# --- Test 1: Sync Status ---
echo "--- Test 1: Sync Status ---"

FUKUII_DEC=$(hex_to_dec "$FUKUII_BLOCK")
SYNCING=$(rpc_result "$FUKUII_RPC" "eth_syncing")

if [[ "$SYNCING" == "false" ]]; then
    echo "  OK: Fukuii is fully synced at block $FUKUII_DEC"
else
    echo "  WARN: Fukuii is still syncing (mining will fail until sync completes)"
    if [[ -n "$SYNCING" && "$SYNCING" != "false" ]]; then
        CURRENT=$(echo "$SYNCING" | jq -r '.currentBlock // empty' 2>/dev/null)
        HIGHEST=$(echo "$SYNCING" | jq -r '.highestBlock // empty' 2>/dev/null)
        if [[ -n "$CURRENT" && -n "$HIGHEST" ]]; then
            echo "  Sync progress: $(hex_to_dec "$CURRENT") / $(hex_to_dec "$HIGHEST")"
        fi
    fi
fi

if [[ -n "$GETH_BLOCK" ]]; then
    GETH_DEC=$(hex_to_dec "$GETH_BLOCK")
    DIFF=$((GETH_DEC - FUKUII_DEC))
    if [[ $DIFF -lt 0 ]]; then DIFF=$((-DIFF)); fi
    if [[ $DIFF -lt 10 ]]; then
        echo "  OK: Fukuii and Core-Geth within $DIFF blocks of each other"
    else
        echo "  WARN: Fukuii is $DIFF blocks behind Core-Geth ($FUKUII_DEC vs $GETH_DEC)"
    fi
fi
echo ""

# --- Test 2: Mining Status ---
echo "--- Test 2: Mining Status ---"

IS_MINING=$(rpc_result "$FUKUII_RPC" "eth_mining")
if [[ "$IS_MINING" == "true" ]]; then
    echo "  OK: eth_mining = true (miner is active)"
else
    echo "  FAIL: eth_mining = $IS_MINING (miner is NOT active)"
    echo "  Check: Is mining-enabled=true in etc.conf?"
    ERRORS=$((ERRORS + 1))
fi

COINBASE=$(rpc_result "$FUKUII_RPC" "eth_coinbase")
COINBASE_LOWER=$(echo "$COINBASE" | tr '[:upper:]' '[:lower:]')
EXPECTED_LOWER=$(echo "$EXPECTED_COINBASE" | tr '[:upper:]' '[:lower:]')
if [[ "$COINBASE_LOWER" == "$EXPECTED_LOWER" ]]; then
    echo "  OK: eth_coinbase = $COINBASE (matches expected)"
else
    echo "  WARN: eth_coinbase = $COINBASE (expected $EXPECTED_COINBASE)"
fi

HASHRATE=$(rpc_result "$FUKUII_RPC" "eth_hashrate")
if [[ -n "$HASHRATE" && "$HASHRATE" != "0x0" ]]; then
    HR_DEC=$(hex_to_dec "$HASHRATE")
    echo "  OK: eth_hashrate = $HR_DEC H/s"
else
    echo "  INFO: eth_hashrate = 0 (miner may not have completed a round yet)"
fi
echo ""

# --- Test 3: Miner Status (Custom RPC) ---
echo "--- Test 3: Miner Status (Custom RPC) ---"

MINER_STATUS=$(rpc_call "$FUKUII_RPC" "miner_getStatus")
if [[ -n "$MINER_STATUS" ]]; then
    MINER_MINING=$(echo "$MINER_STATUS" | jq -r '.result.isMining // empty' 2>/dev/null)
    MINER_HR=$(echo "$MINER_STATUS" | jq -r '.result.hashRate // empty' 2>/dev/null)
    MINER_CB=$(echo "$MINER_STATUS" | jq -r '.result.coinbase // empty' 2>/dev/null)
    echo "  isMining:  ${MINER_MINING:-unknown}"
    echo "  hashRate:  ${MINER_HR:-unknown}"
    echo "  coinbase:  ${MINER_CB:-unknown}"
else
    echo "  WARN: miner_getStatus not available"
fi
echo ""

# --- Test 4: Block Producer Scan ---
echo "--- Test 4: Block Producer Scan ---"
echo "Scanning last 50 blocks for Fukuii-mined blocks..."

SCAN_COUNT=50
START_BLOCK=$((FUKUII_DEC - SCAN_COUNT + 1))
if [[ $START_BLOCK -lt 1 ]]; then START_BLOCK=1; fi

declare -A MINER_COUNTS
FUKUII_MINED=0
TOTAL_CHECKED=0

for ((i=START_BLOCK; i<=FUKUII_DEC; i++)); do
    BLOCK_HEX=$(printf "0x%x" $i)
    BLOCK_DATA=$(rpc_call "$FUKUII_RPC" "eth_getBlockByNumber" "[\"$BLOCK_HEX\",false]")

    MINER=$(echo "$BLOCK_DATA" | jq -r '.result.miner // empty' 2>/dev/null)
    EXTRA=$(echo "$BLOCK_DATA" | jq -r '.result.extraData // empty' 2>/dev/null)

    if [[ -n "$MINER" && "$MINER" != "null" ]]; then
        MINER_LOWER=$(echo "$MINER" | tr '[:upper:]' '[:lower:]')
        MINER_COUNTS[$MINER_LOWER]=$((${MINER_COUNTS[$MINER_LOWER]:-0} + 1))
        TOTAL_CHECKED=$((TOTAL_CHECKED + 1))

        if [[ "$MINER_LOWER" == "$EXPECTED_LOWER" ]]; then
            FUKUII_MINED=$((FUKUII_MINED + 1))
            # Decode extraData to check for our identifier
            EXTRA_ASCII=$(echo "$EXTRA" | sed 's/^0x//' | xxd -r -p 2>/dev/null || echo "")
            echo "  Block #$i: MINED BY FUKUII (extraData: $EXTRA_ASCII)"
        fi
    fi
done

echo ""
echo "Block distribution (last $TOTAL_CHECKED blocks):"
for miner in "${!MINER_COUNTS[@]}"; do
    count=${MINER_COUNTS[$miner]}
    if [[ $TOTAL_CHECKED -gt 0 ]]; then
        percentage=$((count * 100 / TOTAL_CHECKED))
    else
        percentage=0
    fi
    label="$miner"
    if [[ "$miner" == "$EXPECTED_LOWER" ]]; then
        label="$miner (FUKUII)"
    fi
    echo "  $label: $count blocks ($percentage%)"
done

if [[ $FUKUII_MINED -gt 0 ]]; then
    echo ""
    echo "  OK: Fukuii mined $FUKUII_MINED of last $TOTAL_CHECKED blocks"
else
    echo ""
    echo "  INFO: No Fukuii-mined blocks in last $TOTAL_CHECKED blocks"
    echo "  (This is normal - CPU mining on Mordor may take hours between blocks)"
fi
echo ""

# --- Test 5: Cross-Client Block Validation ---
if [[ -n "$GETH_BLOCK" ]]; then
    echo "--- Test 5: Cross-Client Block Validation ---"
    echo "Verifying last 10 blocks match between Fukuii and Core-Geth..."

    VALIDATION_ERRORS=0
    VALIDATED=0
    SAMPLE_SIZE=10
    # Use the lower of the two block heights
    GETH_DEC=$(hex_to_dec "$GETH_BLOCK")
    CHECK_HEIGHT=$((FUKUII_DEC < GETH_DEC ? FUKUII_DEC : GETH_DEC))
    CHECK_START=$((CHECK_HEIGHT - SAMPLE_SIZE + 1))

    for ((i=CHECK_START; i<=CHECK_HEIGHT; i++)); do
        BLOCK_HEX=$(printf "0x%x" $i)

        FUKUII_HASH=$(rpc_call "$FUKUII_RPC" "eth_getBlockByNumber" "[\"$BLOCK_HEX\",false]" | jq -r '.result.hash // empty' 2>/dev/null)
        GETH_HASH=$(rpc_call "$COREGETH_RPC" "eth_getBlockByNumber" "[\"$BLOCK_HEX\",false]" | jq -r '.result.hash // empty' 2>/dev/null)

        if [[ -n "$FUKUII_HASH" && -n "$GETH_HASH" ]]; then
            if [[ "$FUKUII_HASH" == "$GETH_HASH" ]]; then
                VALIDATED=$((VALIDATED + 1))
            else
                echo "  FAIL: Block #$i hash mismatch!"
                echo "    Fukuii:    $FUKUII_HASH"
                echo "    Core-Geth: $GETH_HASH"
                VALIDATION_ERRORS=$((VALIDATION_ERRORS + 1))
            fi
        fi
    done

    if [[ $VALIDATION_ERRORS -eq 0 ]]; then
        echo "  OK: All $VALIDATED blocks validated consistently across clients"
    else
        echo "  FAIL: $VALIDATION_ERRORS block hash mismatches found"
        ERRORS=$((ERRORS + 1))
    fi
    echo ""
fi

# --- Summary ---
echo "=== Mining Validation Summary ==="
echo "Sync status:     $(if [[ "$SYNCING" == "false" ]]; then echo "Synced"; else echo "Syncing"; fi)"
echo "Mining active:   $IS_MINING"
echo "Coinbase:        ${COINBASE:-unknown}"
echo "Hash rate:       $(if [[ -n "$HASHRATE" && "$HASHRATE" != "0x0" ]]; then echo "$(hex_to_dec "$HASHRATE") H/s"; else echo "0 H/s"; fi)"
echo "Fukuii blocks:   $FUKUII_MINED (last $TOTAL_CHECKED blocks)"
echo ""

if [[ $ERRORS -eq 0 ]]; then
    if [[ $FUKUII_MINED -gt 0 ]]; then
        echo "PASS: Mining is working - Fukuii is producing valid blocks on Mordor!"
    else
        echo "PARTIAL: Mining is configured and active, but no blocks mined yet."
        echo "CPU mining on Mordor may take hours. Re-run this script periodically."
    fi
else
    echo "FAIL: $ERRORS error(s) detected. Check configuration."
fi

# --- Watch Mode ---
if $WATCH_MODE; then
    echo ""
    echo "=== Watch Mode: Monitoring mining every 30 seconds (Ctrl+C to stop) ==="
    PREV_BLOCK=$FUKUII_DEC
    while true; do
        sleep 30
        NOW=$(date '+%H:%M:%S')
        BLOCK=$(rpc_result "$FUKUII_RPC" "eth_blockNumber")
        BLOCK_DEC=$(hex_to_dec "$BLOCK")
        MINING=$(rpc_result "$FUKUII_RPC" "eth_mining")
        HR=$(rpc_result "$FUKUII_RPC" "eth_hashrate")
        HR_DEC=$(hex_to_dec "${HR:-0x0}")
        NEW_BLOCKS=$((BLOCK_DEC - PREV_BLOCK))

        # Check if any new blocks were mined by us
        OUR_BLOCKS=0
        if [[ $NEW_BLOCKS -gt 0 ]]; then
            for ((i=PREV_BLOCK+1; i<=BLOCK_DEC; i++)); do
                BH=$(printf "0x%x" $i)
                M=$(rpc_call "$FUKUII_RPC" "eth_getBlockByNumber" "[\"$BH\",false]" | jq -r '.result.miner // empty' 2>/dev/null)
                M_LOWER=$(echo "$M" | tr '[:upper:]' '[:lower:]')
                if [[ "$M_LOWER" == "$EXPECTED_LOWER" ]]; then
                    OUR_BLOCKS=$((OUR_BLOCKS + 1))
                fi
            done
        fi

        OURS_STR=""
        if [[ $OUR_BLOCKS -gt 0 ]]; then
            OURS_STR=" *** MINED $OUR_BLOCKS BLOCK(S)! ***"
        fi

        echo "[$NOW] block=$BLOCK_DEC (+$NEW_BLOCKS) mining=$MINING hashrate=${HR_DEC}H/s${OURS_STR}"
        PREV_BLOCK=$BLOCK_DEC
    done
fi
