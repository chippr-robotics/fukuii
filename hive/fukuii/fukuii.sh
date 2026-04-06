#!/bin/bash
# Hive entry point for Fukuii
# Translates Hive environment variables into Fukuii -D system properties.
# No special modes or hacks — Fukuii runs as a normal node with config overrides.
set -e

DATADIR="/app/data"
GENESIS_FILE="/genesis.json"
JWT_SECRET_FILE="/jwtsecret"
CONFIG_DIR="/app/hive-conf"

mkdir -p "$DATADIR" "$CONFIG_DIR"

# ==============================================================================
# Fork configuration from HIVE_FORK_* environment variables
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

NETWORK_ID=${HIVE_NETWORK_ID:-1}
CHAIN_ID=${HIVE_CHAIN_ID:-1}
TTD=${HIVE_TERMINAL_TOTAL_DIFFICULTY:-0}
SHANGHAI_TS=${HIVE_SHANGHAI_TIMESTAMP:-}
CANCUN_TS=${HIVE_CANCUN_TIMESTAMP:-}
PRAGUE_TS=${HIVE_PRAGUE_TIMESTAMP:-}

# ==============================================================================
# Genesis: convert geth format to Fukuii format
# ==============================================================================

if [ -f "$GENESIS_FILE" ]; then
    jq -f /mapper.jq "$GENESIS_FILE" > "$CONFIG_DIR/genesis.json"
fi

# ==============================================================================
# JWT secret for Engine API
# ==============================================================================

echo "0x7365637265747365637265747365637265747365637265747365637265747365" > "$JWT_SECRET_FILE"

# ==============================================================================
# Build JVM flags — all configuration via -D system properties
# ==============================================================================

FLAGS=""
FLAGS="$FLAGS -Dfukuii.datadir=$DATADIR"
FLAGS="$FLAGS -Dfukuii.blockchains.network=mordor"

# Chain/network identity
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.chain-id=$CHAIN_ID"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.network-id=$NETWORK_ID"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.network-type=eth"

# Genesis override
if [ -f "$CONFIG_DIR/genesis.json" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.mordor.custom-genesis-file=$CONFIG_DIR/genesis.json"
fi

# Fork block overrides
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
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.olympia-block-number=$LONDON"
FLAGS="$FLAGS -Dfukuii.blockchains.mordor.terminal-total-difficulty=$TTD"

# Timestamp-based forks
[ -n "$SHANGHAI_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.mordor.shanghai-timestamp=$SHANGHAI_TS"
[ -n "$CANCUN_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.mordor.cancun-timestamp=$CANCUN_TS"
[ -n "$PRAGUE_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.mordor.prague-timestamp=$PRAGUE_TS"

# RPC
FLAGS="$FLAGS -Dfukuii.network.rpc.http.enabled=true"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.port=8545"
FLAGS="$FLAGS -Dfukuii.network.rpc.apis=eth,web3,net,debug"

# Engine API
FLAGS="$FLAGS -Dfukuii.network.engine-api.enabled=true"
FLAGS="$FLAGS -Dfukuii.network.engine-api.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.engine-api.port=8551"
FLAGS="$FLAGS -Dfukuii.network.engine-api.jwt-secret-path=$JWT_SECRET_FILE"
FLAGS="$FLAGS -Dfukuii.mining.protocol=engine-api"

# P2P
FLAGS="$FLAGS -Dfukuii.network.server-address.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.server-address.port=30303"
FLAGS="$FLAGS -Dfukuii.network.discovery.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.discovery.port=30303"

# Disable sync (hive provides chain data directly)
FLAGS="$FLAGS -Dfukuii.sync.do-fast-sync=false"
FLAGS="$FLAGS -Dfukuii.sync.do-snap-sync=false"

# Bootnode
[ -n "$HIVE_BOOTNODE" ] && FLAGS="$FLAGS -Dfukuii.network.peer.bootstrap-nodes.0=$HIVE_BOOTNODE"

# Mining
if [ -n "$HIVE_MINER" ]; then
    FLAGS="$FLAGS -Dfukuii.mining.mining-enabled=true"
    FLAGS="$FLAGS -Dfukuii.mining.coinbase=$HIVE_MINER"
fi

exec java \
    -Xmx2g \
    -Xms512m \
    -Xss4M \
    -XX:+UseG1GC \
    $FLAGS \
    -jar /app/fukuii/lib/fukuii-assembly-0.1.240.jar \
    mordor
