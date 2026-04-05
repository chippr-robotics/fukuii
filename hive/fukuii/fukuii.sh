#!/bin/bash
# Hive entry point for Fukuii
# Translates Hive environment variables into Fukuii configuration
set -e

DATADIR="/app/data"
GENESIS_FILE="/genesis.json"
JWT_SECRET_FILE="/jwtsecret"
CONFIG_DIR="/app/hive-conf"

mkdir -p "$DATADIR" "$CONFIG_DIR"

# ==============================================================================
# 1. Determine fork configuration from HIVE_FORK_* environment variables
# ==============================================================================

FORK_ARGS=""

# Block-number forks (defaults to max = disabled)
MAX="1000000000000000000"
FRONTIER=0
HOMESTEAD=${HIVE_FORK_HOMESTEAD:-$MAX}
DAO_BLOCK=${HIVE_FORK_DAO_BLOCK:-$MAX}
TANGERINE=${HIVE_FORK_TANGERINE:-$MAX}
SPURIOUS=${HIVE_FORK_SPURIOUS:-$MAX}
BYZANTIUM=${HIVE_FORK_BYZANTIUM:-$MAX}
CONSTANTINOPLE=${HIVE_FORK_CONSTANTINOPLE:-$MAX}
PETERSBURG=${HIVE_FORK_PETERSBURG:-$MAX}
ISTANBUL=${HIVE_FORK_ISTANBUL:-$MAX}
MUIRGLACIER=${HIVE_FORK_MUIRGLACIER:-$MAX}
BERLIN=${HIVE_FORK_BERLIN:-$MAX}
LONDON=${HIVE_FORK_LONDON:-$MAX}

# Network/Chain IDs
NETWORK_ID=${HIVE_NETWORK_ID:-1}
CHAIN_ID=${HIVE_CHAIN_ID:-1}

# Engine API / PoS
TTD=${HIVE_TERMINAL_TOTAL_DIFFICULTY:-}
SHANGHAI_TS=${HIVE_SHANGHAI_TIMESTAMP:-}
CANCUN_TS=${HIVE_CANCUN_TIMESTAMP:-}
PRAGUE_TS=${HIVE_PRAGUE_TIMESTAMP:-}

# ==============================================================================
# 2. Process genesis file
# ==============================================================================

if [ -f "$GENESIS_FILE" ]; then
    echo "Processing genesis file..."
    # Use mapper.jq to convert geth-format genesis to fukuii format
    jq -f /mapper.jq "$GENESIS_FILE" > "$CONFIG_DIR/genesis.json"
else
    echo "No genesis file provided, using default"
fi

# ==============================================================================
# 3. Set up JWT secret for Engine API
# ==============================================================================

if [ -n "$TTD" ]; then
    echo "Engine API mode: TTD=$TTD"
    # Standard hive JWT secret
    echo "0x7365637265747365637265747365637265747365637265747365637265747365" > "$JWT_SECRET_FILE"
fi

# ==============================================================================
# 4. Import chain data if provided
# ==============================================================================

if [ -f "/chain.rlp" ]; then
    echo "Chain import requested (chain.rlp) — not yet supported, skipping"
fi

if [ -d "/blocks" ]; then
    echo "Block import requested (/blocks/) — not yet supported, skipping"
fi

# ==============================================================================
# 5. Build JVM flags
# ==============================================================================

FLAGS=""
FLAGS="$FLAGS -Dfukuii.datadir=$DATADIR"
FLAGS="$FLAGS -Dfukuii.blockchains.network=test"

# Network config
FLAGS="$FLAGS -Dfukuii.network.rpc.http.enabled=true"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.port=8545"
FLAGS="$FLAGS -Dfukuii.network.server-address.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.server-address.port=30303"
FLAGS="$FLAGS -Dfukuii.network.discovery.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.discovery.port=30303"

# Bootnode
if [ -n "$HIVE_BOOTNODE" ]; then
    FLAGS="$FLAGS -Dfukuii.network.peer.bootstrap-nodes.0=$HIVE_BOOTNODE"
fi

# Mining
if [ -n "$HIVE_MINER" ]; then
    FLAGS="$FLAGS -Dfukuii.mining.mining-enabled=true"
    FLAGS="$FLAGS -Dfukuii.mining.coinbase=$HIVE_MINER"
fi

# Engine API
if [ -n "$TTD" ]; then
    FLAGS="$FLAGS -Dfukuii.network.engine-api.enabled=true"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.interface=0.0.0.0"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.port=8551"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.jwt-secret-path=$JWT_SECRET_FILE"
    FLAGS="$FLAGS -Dfukuii.mining.protocol=engine-api"
fi

# Timestamp forks
if [ -n "$SHANGHAI_TS" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.test.shanghai-timestamp=$SHANGHAI_TS"
fi
if [ -n "$CANCUN_TS" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.test.cancun-timestamp=$CANCUN_TS"
fi
if [ -n "$PRAGUE_TS" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.test.prague-timestamp=$PRAGUE_TS"
fi
if [ -n "$TTD" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.test.terminal-total-difficulty=$TTD"
fi

# Log level
case "$HIVE_LOGLEVEL" in
    0|1) LOGLEVEL="ERROR" ;;
    2)   LOGLEVEL="WARN" ;;
    3)   LOGLEVEL="INFO" ;;
    4|5) LOGLEVEL="DEBUG" ;;
    *)   LOGLEVEL="INFO" ;;
esac

# ==============================================================================
# 6. Start Fukuii
# ==============================================================================

echo "Starting Fukuii with flags: $FLAGS"
echo "Network ID: $NETWORK_ID, Chain ID: $CHAIN_ID"
echo "Log level: $LOGLEVEL"

exec java \
    -Xmx2g \
    -Xms512m \
    -Xss4M \
    -XX:+UseG1GC \
    $FLAGS \
    -jar /app/fukuii/lib/fukuii-assembly-0.1.240.jar \
    test
