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
OSAKA_TS=${HIVE_OSAKA_TIMESTAMP:-}
# BPO1/BPO2 (EIP-7892 blob-parameter-only forks). These carry no EVM changes, but
# they ARE forks: they must enter the EIP-6122 fork-id checksum chain, and they
# select the blob target/max and base-fee update fraction. Omitting them made the
# node advertise a fork id computed over [cancun, prague, osaka] only, which peers
# reject with "wrong fork ID in status".
BPO1_TS=${HIVE_BPO1_TIMESTAMP:-}
BPO2_TS=${HIVE_BPO2_TIMESTAMP:-}
AMSTERDAM_TS=${HIVE_AMSTERDAM_TIMESTAMP:-}

# go-ethereum's --miner.gaslimit. hive's rpc-compat simulator sets this explicitly
# ("Match execution-apis' Geth default so all clients build the same next-block gas
# limit") and the execution-apis testing_* fixtures were recorded against it: the
# proposer steps the gas limit toward this target by parent/1024-1 per block, so a
# mismatch changes every built block's hash. Fukuii's own default is already 60M.
TARGET_GAS_LIMIT=${HIVE_TARGET_GAS_LIMIT:-}

# ==============================================================================
# Genesis: convert geth format to Fukuii format
# ==============================================================================

if [ -f "$GENESIS_FILE" ]; then
    jq -f /mapper.jq "$GENESIS_FILE" > "$CONFIG_DIR/genesis.json"
    # EIP-6110 deposit contract. Genesis-declared and NOT a universal constant — hive's
    # rpc-compat fixture declares the zero address while mainnet uses 0x00000000219ab540...
    # eth_config (EIP-7910) must advertise the chain's own value, so read it from the
    # genesis rather than falling back to the mainnet contract.
    DEPOSIT_CONTRACT=$(jq -r '.config.depositContractAddress // empty' "$GENESIS_FILE" 2>/dev/null || true)

    # Arrow Glacier (EIP-4345), Gray Glacier (EIP-5133) and the post-Merge net-split block
    # (EIP-3675). These have no EVM semantics, but go-ethereum's `gatherForks` enumerates
    # every *Block field of ChainConfig, so all three enter the EIP-2124 fork-id checksum
    # chain. hive exports HIVE_FORK_* only for the forks it models, and these three are not
    # among them, so read them from the genesis the simulator actually handed us.
    #
    # Omitting them is not cosmetic: on this fixture the correct checksum is 0xe272ecbe and
    # the checksum without them is 0x5e0cb820, which every geth peer rejects with
    # "wrong fork ID in status" — i.e. no peering at all.
    ARROW_GLACIER=$(jq -r '.config.arrowGlacierBlock // empty' "$GENESIS_FILE" 2>/dev/null || true)
    GRAY_GLACIER=$(jq -r '.config.grayGlacierBlock // empty' "$GENESIS_FILE" 2>/dev/null || true)
    MERGE_NETSPLIT=$(jq -r '.config.mergeNetsplitBlock // empty' "$GENESIS_FILE" 2>/dev/null || true)
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

# Terminal total difficulty: only set when the hive sim explicitly provides one.
# fukuii's SNAPSyncController treats `terminal-total-difficulty.isDefined` as
# "post-merge chain" and then waits for engine_forkchoiceUpdated from the CL
# before picking a SNAP pivot (engine-api-required = true is the sync.conf
# default — see #1208). Pre-merge hive sims (including ethereum/sync) have no
# CL, so passing the $MAX sentinel here would wedge the sink at head=0 for the
# full 60s simulator budget and fail every fukuii-tagged sync sub-test.
if [ "$TTD" != "$MAX" ]; then
    FLAGS="$FLAGS -Dfukuii.blockchains.hive.terminal-total-difficulty=$TTD"
fi

# Timestamp-based forks
[ -n "$SHANGHAI_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.shanghai-timestamp=$SHANGHAI_TS"
[ -n "$CANCUN_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.cancun-timestamp=$CANCUN_TS"
[ -n "$PRAGUE_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.prague-timestamp=$PRAGUE_TS"
[ -n "$OSAKA_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.osaka-timestamp=$OSAKA_TS"
[ -n "$BPO1_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.bpo1-timestamp=$BPO1_TS"
[ -n "$BPO2_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.bpo2-timestamp=$BPO2_TS"
[ -n "$AMSTERDAM_TS" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.amsterdam-timestamp=$AMSTERDAM_TS"

# EIP-6110 deposit contract address, as declared by the fixture genesis (see above).
[ -n "$DEPOSIT_CONTRACT" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.deposit-contract-address=$DEPOSIT_CONTRACT"

# Fork-id-only block forks read from the genesis above (see the comment there).
[ -n "$ARROW_GLACIER" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.arrow-glacier-block-number=$ARROW_GLACIER"
[ -n "$GRAY_GLACIER" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.gray-glacier-block-number=$GRAY_GLACIER"
[ -n "$MERGE_NETSPLIT" ] && FLAGS="$FLAGS -Dfukuii.blockchains.hive.merge-netsplit-block-number=$MERGE_NETSPLIT"

# RPC
[ -n "$TARGET_GAS_LIMIT" ] && FLAGS="$FLAGS -Dfukuii.mining.gas-limit-target=$TARGET_GAS_LIMIT"

FLAGS="$FLAGS -Dfukuii.network.rpc.http.enabled=true"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.rpc.http.port=8545"
# `txpool` added 2026-09-20 (#1407). The ethereum/rpc-compat suite exercises
# txpool_content, txpool_contentFrom and txpool_status; all three are
# implemented (JsonRpcController.scala:534-538, Apis.TxPool = "txpool", listed
# in NodeBuilder's `available`), but they were never served under hive because
# this line did not expose the namespace. Those three failures were a gap in
# the harness configuration, not in the client.
# `admin` added 2026-09-21 (#1407). hive/fukuii/enode.sh calls admin_nodeInfo to
# discover this node's P2P identity. admin_nodeInfo is implemented
# (JsonRpcController.scala:495, Apis.Admin = "admin") but the namespace was not
# exposed, so the call returned method-not-found, enode.sh fell through to its
# last-resort branch and emitted a literal "unknown" as the public key. hive then
# failed every devp2p test with `invalid public key (encoding/hex: invalid byte:
# U+0075 'u')` — the 'u' of "unknown". Same shape as the txpool gap above:
# implemented method, unexposed namespace, failure attributed to the client.
# `testing` added 2026-09-22. The ethereum/rpc-compat suite exercises the
# execution-apis testing_* namespace (testing_buildBlockV1, testing_commitBlockV1 —
# 9 test vectors). The namespace is implemented (jsonrpc/TestingService.scala,
# Apis.Testing = "testing") but is deliberately absent from every shipped config:
# the spec says it "MUST NOT be exposed on public-facing RPC APIs" and "is strongly
# recommended to be disabled by default". hive is exactly the environment it is for,
# so it is opted in here and ONLY here.
FLAGS="$FLAGS -Dfukuii.network.rpc.apis=eth,web3,net,debug,txpool,admin,testing"

# Engine API — only enable for post-merge chains (TTD is not MAX)
if [ "$TTD" != "$MAX" ]; then
    FLAGS="$FLAGS -Dfukuii.network.engine-api.enabled=true"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.interface=0.0.0.0"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.port=8551"
    FLAGS="$FLAGS -Dfukuii.network.engine-api.jwt-secret-path=$JWT_SECRET_FILE"
    FLAGS="$FLAGS -Dfukuii.mining.protocol=engine-api"
    # Hive pre-merge blocks ship with fake Ethash seals; skip PoW header check.
    FLAGS="$FLAGS -Dfukuii.mining.skip-pow-validation=true"
else
    # PoW chain — no engine API. Hive-generated chains always use fake
    # Ethash seals (HIVE_SKIP_POW honours the explicit signal; rpc-compat
    # and other sims don't set it but still feed us bogus seals), so use
    # the non-validating 'mocked' protocol unconditionally in hive mode.
    FLAGS="$FLAGS -Dfukuii.network.engine-api.enabled=false"
    FLAGS="$FLAGS -Dfukuii.mining.protocol=mocked"
fi

# P2P
FLAGS="$FLAGS -Dfukuii.network.server-address.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.server-address.port=30303"
FLAGS="$FLAGS -Dfukuii.network.discovery.interface=0.0.0.0"
FLAGS="$FLAGS -Dfukuii.network.discovery.port=30303"
# Workaround for `sync go-ethereum from fukuii` Hive gate: scalanet's discv4
# packet decoder rejects every one of geth's UDP packets with
# `PacketException: Failed to unpack message: Invalid hash` (~4 errors/sec for
# the entire 60s test window). Without a PONG, geth's discovery state machine
# never marks fukuii's bootnode alive and never TCP-dials it for RLPx — test
# times out at head=0. Hive already supplies the bootnode via static-nodes.json
# below (HIVE_BOOTNODE), so discovery isn't needed to find peers; disabling it
# sidesteps the parser bug. Underlying scalanet hash-validation regression
# tracked separately — restore discovery in hive runs once that is fixed.
FLAGS="$FLAGS -Dfukuii.network.discovery.discovery-enabled=true"

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

# Hive clients are ephemeral: one short sync run, then discarded. Cap JIT at C1
# (-XX:TieredStopAtLevel=1) so the JVM reaches readiness fast — full C2 optimization
# never pays off in a single 3000-block sync and only lengthens cold-start, which is
# what tips slow fukuii-sink sync tests over hive's --client.checktimelimit.
exec java \
    -Xmx512m \
    -Xms128m \
    -Xss2M \
    -XX:+UseG1GC \
    -XX:TieredStopAtLevel=1 \
    -XX:MaxMetaspaceSize=256m \
    -XX:+ExitOnOutOfMemoryError \
    $FLAGS \
    -jar /app/fukuii/lib/fukuii-assembly.jar \
    hive
