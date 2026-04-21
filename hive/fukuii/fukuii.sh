#!/bin/bash
# Hive entry point for Fukuii
# Uses the "hive" network config (vanilla Ethereum, no ETC baggage).
# Translates HIVE_* env vars to -Dfukuii.blockchains.hive.* overrides.
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
TTD=${HIVE_TERMINAL_TOTAL_DIFFICULTY:-$MAX}
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
# Build JVM flags — "hive" network with clean Ethereum defaults
# ==============================================================================

FLAGS=""
FLAGS="$FLAGS -Dfukuii.datadir=$DATADIR"
FLAGS="$FLAGS -Dfukuii.blockchains.network=hive"

# Chain/network identity
FLAGS="$FLAGS -Dfukuii.blockchains.hive.chain-id=$CHAIN_ID"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.network-id=$NETWORK_ID"

# Genesis override
if [ -f "$CONFIG_DIR/genesis.json" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.hive.custom-genesis-file=$CONFIG_DIR/genesis.json"
fi

# Standard Ethereum fork overrides
FLAGS="$FLAGS -Dfukuii.blockchains.hive.homestead-block-number=$HOMESTEAD"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.eip150-block-number=$TANGERINE"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.eip155-block-number=$SPURIOUS"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.eip160-block-number=$SPURIOUS"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.eip161-block-number=$SPURIOUS"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.byzantium-block-number=$BYZANTIUM"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.constantinople-block-number=$CONSTANTINOPLE"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.petersburg-block-number=$PETERSBURG"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.istanbul-block-number=$ISTANBUL"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.muir-glacier-block-number=$MUIRGLACIER"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.berlin-block-number=$BERLIN"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.olympia-block-number=$LONDON"
FLAGS="$FLAGS -Dfukuii.blockchains.hive.terminal-total-difficulty=$TTD"

# Timestamp-based forks
[ -n "$SHANGHAI_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.shanghai-timestamp=$SHANGHAI_TS"
[ -n "$CANCUN_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.cancun-timestamp=$CANCUN_TS"
[ -n "$PRAGUE_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.prague-timestamp=$PRAGUE_TS"

# RPC
FLAGS="$FLAGS -Dfukuii.network.rpc.http.enabled=true"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.port=8545"
FLAGS="$FLAGS -Dfukuii.network.rpc.apis=eth,web3,net,debug"

# Engine API — only enable for post-merge chains (TTD is not MAX)
if [ "$TTD" != "$MAX" ]; then
    FLAGS="$FLAGS -Dfukuii.network.engine-api.enabled=true"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.interface=0.0.0.0"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.port=8551"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.jwt-secret-path=$JWT_SECRET_FILE"
    FLAGS="$FLAGS -Dfukuii.mining.protocol=engine-api"
else
    # PoW chain — no engine API. Tests that use SealEngine=NoProof set
    # HIVE_SKIP_POW; honor it by switching to the non-validating 'mocked'
    # protocol so headers with fake Ethash seals aren't rejected.
    FLAGS="$FLAGS -Dfukuii.network.engine-api.enabled=false"
    if [ -n "$HIVE_SKIP_POW" ]; then
        FLAGS="$FLAGS -Dfukuii.mining.protocol=mocked"
    else
        FLAGS="$FLAGS -Dfukuii.mining.protocol=pow"
    fi
fi

# P2P
FLAGS="$FLAGS -Dfukuii.network.server-address.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.server-address.port=30303"
FLAGS="$FLAGS -Dfukuii.network.discovery.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.discovery.port=30303"

# Chain import — prefer /chain.rlp, otherwise concatenate /blocks/*.rlp (consensus sim).
if [ -f "/chain.rlp" ]; then
    FLAGS="$FLAGS -Dfukuii.import-chain-file=/chain.rlp"
elif ls /blocks/*.rlp >/dev/null 2>&1; then
    cat /blocks/*.rlp > /chain.rlp
    FLAGS="$FLAGS -Dfukuii.import-chain-file=/chain.rlp"
fi

# Bootnode — write to static-nodes.json in the datadir so the node dials it directly.
# HOCON arrays can't be populated via -D system properties, so file is the reliable path.
if [ -n "$HIVE_BOOTNODE" ]; then
    echo "[\"$HIVE_BOOTNODE\"]" > "$DATADIR/static-nodes.json"
fi

# Mining
if [ -n "$HIVE_MINER" ]; then
    FLAGS="$FLAGS -Dfukuii.mining.mining-enabled=true"
    FLAGS="$FLAGS -Dfukuii.mining.coinbase=$HIVE_MINER"
fi

exec java \
    -Xmx1g \
    -Xms256m \
    -Xss4M \
    -XX:+UseG1GC \
    $FLAGS \
    -jar /app/fukuii/lib/fukuii-assembly-0.1.240.jar \
    hive
