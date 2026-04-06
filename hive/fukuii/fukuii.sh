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

MAX="1000000000000000000"
HOMESTEAD=${HIVE_FORK_HOMESTEAD:-$MAX}
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
    jq -f /mapper.jq "$GENESIS_FILE" > "$CONFIG_DIR/genesis.json"
else
    echo "No genesis file provided, using default"
fi

# ==============================================================================
# 3. Set up JWT secret for Engine API
# ==============================================================================

# JWT secret is always written (Engine API always enabled for hive)
echo "Engine API mode: TTD=${TTD:-0}"

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
FLAGS="$FLAGS -Dfukuii.blockchains.network=mordor"

# Override chain ID and network ID from hive env vars
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.chain-id=$CHAIN_ID"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.network-id=$NETWORK_ID"

# Fork block overrides (override mordor defaults with hive values)
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.homestead-block-number=$HOMESTEAD"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.eip150-block-number=$TANGERINE"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.eip155-block-number=$SPURIOUS"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.eip160-block-number=$SPURIOUS"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.eip161-block-number=$SPURIOUS"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.byzantium-block-number=$BYZANTIUM"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.constantinople-block-number=$CONSTANTINOPLE"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.petersburg-block-number=$PETERSBURG"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.istanbul-block-number=$ISTANBUL"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.muir-glacier-block-number=$MUIRGLACIER"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.berlin-block-number=$BERLIN"
# London = Olympia in Fukuii (EIP-1559)
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.olympia-block-number=$LONDON"

# Network config
FLAGS="$FLAGS -Dfukuii.network.rpc.http.enabled=true"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.port=8545"
FLAGS="$FLAGS -Dfukuii.network.rpc.apis=eth,web3,net,personal,fukuii,debug,qa"
FLAGS="$FLAGS -Dfukuii.network.server-address.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.server-address.port=30303"
FLAGS="$FLAGS -Dfukuii.network.discovery.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.discovery.port=30303"

# Disable sync (hive provides chain data, we don't sync from peers)
FLAGS="$FLAGS -Dfukuii.sync.do-fast-sync=false"
FLAGS="$FLAGS -Dfukuii.sync.do-snap-sync=false"

# Override log level for visibility in hive
FLAGS="$FLAGS -Dfukuii.logging.logs-level=INFO"

# Bootnode
if [ -n "$HIVE_BOOTNODE" ]; then
    FLAGS="$FLAGS -Dfukuii.network.peer.bootstrap-nodes.0=$HIVE_BOOTNODE"
fi

# Mining
if [ -n "$HIVE_MINER" ]; then
    FLAGS="$FLAGS -Dfukuii.mining.mining-enabled=true"
    FLAGS="$FLAGS -Dfukuii.mining.coinbase=$HIVE_MINER"
fi

# Engine API — always enabled (hive RPC-compat tests are post-merge)
echo "0x7365637265747365637265747365637265747365637265747365637265747365" > "$JWT_SECRET_FILE"
FLAGS="$FLAGS -Dfukuii.network.engine-api.enabled=true"
FLAGS="$FLAGS -Dfukuii.network.engine-api.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.engine-api.port=8551"
FLAGS="$FLAGS -Dfukuii.network.engine-api.jwt-secret-path=$JWT_SECRET_FILE"
FLAGS="$FLAGS -Dfukuii.mining.protocol=engine-api"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.network-type=eth"

if [ -n "$TTD" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.mordor.terminal-total-difficulty=$TTD"
else
    # Default TTD=0 for post-merge tests
    FLAGS="$FLAGS -Dfukuii.blockchains.mordor.terminal-total-difficulty=0"
fi

# Timestamp forks
if [ -n "$SHANGHAI_TS" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.mordor.shanghai-timestamp=$SHANGHAI_TS"
fi
if [ -n "$CANCUN_TS" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.mordor.cancun-timestamp=$CANCUN_TS"
fi
if [ -n "$PRAGUE_TS" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.mordor.prague-timestamp=$PRAGUE_TS"
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

echo "Starting Fukuii for hive test"
echo "  Chain ID: $CHAIN_ID, Network ID: $NETWORK_ID"
echo "  Forks: London=$LONDON, Berlin=$BERLIN"
echo "  TTD: ${TTD:-none}"
echo "  Log level: $LOGLEVEL"

exec java \
    -Xmx2g \
    -Xms512m \
    -Xss4M \
    -XX:+UseG1GC \
    $FLAGS \
    -jar /app/fukuii/lib/fukuii-assembly-0.1.240.jar \
    mordor
