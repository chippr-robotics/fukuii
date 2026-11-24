#!/usr/bin/env bash
# Writes the configuration of the private ETC-config PoW net (spec 018 C6, T244; gate EG-6 / T258).
#
# The net: two or three fukuii miners (real Ethash, the DAG on each miner) plus one core-geth, every ETC fork from
# Homestead to Spiral active at genesis and Olympia (ECIP-1111/1112/1121/1122) at a block the operator names. Nothing is
# started here; start.sh starts it.
#
# The genesis is the core-geth genesis file the operator supplies (--geth-genesis). core-geth's fork keys for Olympia
# are not known to this repository, so that file is written from core-geth's own Olympia configuration and given here.
# fukuii's genesis is derived from its header fields and allocation, and its fork schedule is written from the flags
# below. The two clients agree only if both say the same thing: start.sh refuses a net whose genesis hashes differ,
# and check.sh fails a net whose canonical chains differ (a fork-rule mismatch shows there, at the first block it
# touches). ETC forks expected in the core-geth file, all at block 0: Homestead, EIP-150, EIP-155/160 (Die Hard),
# ECIP-1010 pause/ECIP-1041 removal, Atlantis, Agharta, Phoenix, ECIP-1099 (Thanos), Magneto, Mystique, Spiral;
# ECIP-1017 era rounds equal to --era-duration; Olympia at --olympia-block; the treasury at --treasury-address.
#
# Usage: scripts/etc-private-net/init.sh --net-dir DIR --fukuii-jar JAR --geth-bin GETH --geth-genesis FILE \
#          --olympia-block N --treasury-address 0x... --mess never|N [options]
# Run with --help for every option.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/etc-private-net/lib.sh
. "$HERE/lib.sh"

usage() {
  cat <<'EOF'
init.sh: write the private ETC-config PoW net's configuration (nothing is started).

Required:
  --net-dir DIR             Directory for the net's config, datadirs and logs (created).
  --fukuii-jar PATH         The fukuii assembly jar under test.
  --geth-bin PATH           The core-geth binary (white-b0x/core-geth main, with Olympia).
  --geth-genesis FILE       core-geth genesis JSON, written from core-geth's Olympia config (see the header).
  --olympia-block N         Olympia's activation block. 0 puts the genesis header on HefPostOlympia (base fee in the
                            genesis); N > 0 exercises the transition. EG-6 names both: run the net once with each.
  --treasury-address 0x..   ECIP-1112 treasury address; must equal the core-geth genesis's.
  --mess never|N            ECBP-1100 (MESS) on fukuii: never, or the block it is reactivated at (ECIP-1122 ties
                            it to Olympia). Required: it must match core-geth's config and this script cannot read
                            core-geth's key for it. MESS changes which side of a partition wins.

Options:
  --miners 2|3              fukuii miners (default 3).
  --geth-mines              core-geth mines too (default: it imports and serves only).
  --host IP                 Address every node binds and advertises (default 127.0.0.1: one host).
  --node-host NAME=IP       Address of one node, for a net spread over hosts (repeatable). Names: miner1..miner3, geth.
  --base-p2p-port P         First P2P port (default 30400); node i uses P+i.
  --base-rpc-port P         First JSON-RPC port (default 8600); node i uses P+i.
  --base-metrics-port P     First Prometheus port (fukuii only, default 13900); node i uses P+i.
  --era-duration N          ECIP-1017 era length (default 5000000, ETC mainnet's). Checked against the core-geth
                            genesis's ecip1017EraRounds when that key is present.
  --path-live true|false    fukuii's unified state layout guard (default true: the layout under test).
  --dag-dir DIR             Base directory for the Ethash DAG; each miner uses DIR/<name> (default NET_DIR/ethash).
  --java PATH               java binary (default: java on PATH).
  --force                   Overwrite an existing configuration. Datadirs are never deleted: a datadir made with a
                            different genesis is refused by the node at start; remove it yourself.
  --dry-run                 Print what would be written; write nothing.
  -h, --help                This text.
EOF
}

NET_DIR_ARG="" FUKUII_JAR="" GETH_BIN="" GETH_GENESIS="" OLYMPIA_BLOCK="" TREASURY=""
MINERS=3 GETH_MINES=false HOST=127.0.0.1 BASE_P2P=30400 BASE_RPC=8600 BASE_METRICS=13900
ERA_DURATION=5000000 MESS="" PATH_LIVE=true DAG_DIR="" JAVA_BIN=java FORCE=false DRY_RUN=false
declare -A NODE_HOSTS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --net-dir) NET_DIR_ARG=$2; shift 2 ;;
    --fukuii-jar) FUKUII_JAR=$2; shift 2 ;;
    --geth-bin) GETH_BIN=$2; shift 2 ;;
    --geth-genesis) GETH_GENESIS=$2; shift 2 ;;
    --olympia-block) OLYMPIA_BLOCK=$2; shift 2 ;;
    --treasury-address) TREASURY=$2; shift 2 ;;
    --miners) MINERS=$2; shift 2 ;;
    --geth-mines) GETH_MINES=true; shift ;;
    --host) HOST=$2; shift 2 ;;
    --node-host)
      [[ $2 == *=* ]] || die "--node-host takes NAME=IP, got '$2'"
      NODE_HOSTS[${2%%=*}]=${2#*=}
      shift 2
      ;;
    --base-p2p-port) BASE_P2P=$2; shift 2 ;;
    --base-rpc-port) BASE_RPC=$2; shift 2 ;;
    --base-metrics-port) BASE_METRICS=$2; shift 2 ;;
    --era-duration) ERA_DURATION=$2; shift 2 ;;
    --mess) MESS=$2; shift 2 ;;
    --path-live) PATH_LIVE=$2; shift 2 ;;
    --dag-dir) DAG_DIR=$2; shift 2 ;;
    --java) JAVA_BIN=$2; shift 2 ;;
    --force) FORCE=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done

need_cmd jq python3

missing=()
[ -n "$NET_DIR_ARG" ] || missing+=(--net-dir)
[ -n "$FUKUII_JAR" ] || missing+=(--fukuii-jar)
[ -n "$GETH_BIN" ] || missing+=(--geth-bin)
[ -n "$GETH_GENESIS" ] || missing+=(--geth-genesis)
[ -n "$OLYMPIA_BLOCK" ] || missing+=(--olympia-block)
[ -n "$TREASURY" ] || missing+=(--treasury-address)
[ -n "$MESS" ] || missing+=(--mess)
[ ${#missing[@]} -eq 0 ] || { usage >&2; die "missing required option(s): ${missing[*]}"; }

is_uint "$OLYMPIA_BLOCK" || die "--olympia-block must be a block number, got '$OLYMPIA_BLOCK'"
is_addr "$TREASURY" || die "--treasury-address must be a 0x-prefixed 20-byte address, got '$TREASURY'"
[[ $MINERS == 2 || $MINERS == 3 ]] || die "--miners must be 2 or 3, got '$MINERS'"
is_uint "$ERA_DURATION" && [ "$ERA_DURATION" -gt 0 ] || die "--era-duration must be a positive integer"
[[ $MESS == never ]] || is_uint "$MESS" || die "--mess takes never or a block number, got '$MESS'"
[[ $PATH_LIVE == true || $PATH_LIVE == false ]] || die "--path-live takes true or false"
for p in "$BASE_P2P" "$BASE_RPC" "$BASE_METRICS"; do is_uint "$p" || die "ports must be integers, got '$p'"; done
[ -f "$FUKUII_JAR" ] || die "--fukuii-jar: $FUKUII_JAR is not a file"
[ -x "$GETH_BIN" ] || die "--geth-bin: $GETH_BIN is not an executable file"
[ -f "$GETH_GENESIS" ] || die "--geth-genesis: $GETH_GENESIS is not a file"
jq -e . "$GETH_GENESIS" >/dev/null || die "--geth-genesis: $GETH_GENESIS is not JSON"
command -v "$JAVA_BIN" >/dev/null 2>&1 || die "--java: '$JAVA_BIN' not found"

need_cmd realpath sha256sum
NET_DIR=$(realpath -m "$NET_DIR_ARG")
[ -n "$DAG_DIR" ] || DAG_DIR=$NET_DIR/ethash
DAG_DIR=$(realpath -m "$DAG_DIR")
FUKUII_JAR=$(realpath "$FUKUII_JAR")
GETH_BIN=$(realpath "$GETH_BIN")

if [ -f "$NET_DIR/net.env" ] && [ "$FORCE" != true ] && [ "$DRY_RUN" != true ]; then
  die "$NET_DIR already holds a net (net.env): pass --force to overwrite its configuration"
fi

# --- The genesis: read from core-geth's file ---------------------------------------------------------------------
CHAIN_ID=$(jq -r '.config.chainId // empty' "$GETH_GENESIS")
is_uint "$CHAIN_ID" || die "--geth-genesis has no numeric .config.chainId"
case "$CHAIN_ID" in
  1 | 61 | 63 | 11155111) die "--geth-genesis uses chain id $CHAIN_ID, a public chain's: a private net needs its own" ;;
esac
NETWORK_ID=$(jq -r '.config.networkId // empty' "$GETH_GENESIS")
[ -n "$NETWORK_ID" ] || NETWORK_ID=$CHAIN_ID
is_uint "$NETWORK_ID" || die "--geth-genesis .config.networkId is not a number"
geth_era=$(jq -r '.config.ecip1017EraRounds // empty' "$GETH_GENESIS")
if [ -n "$geth_era" ] && [ "$geth_era" != "$ERA_DURATION" ]; then
  die "--era-duration $ERA_DURATION differs from the core-geth genesis's ecip1017EraRounds $geth_era"
fi
[ -n "$geth_era" ] || warn "the core-geth genesis has no ecip1017EraRounds: its era length is not checked here"
for f in difficulty gasLimit timestamp extraData; do
  jq -e --arg f "$f" 'has($f)' "$GETH_GENESIS" >/dev/null || die "--geth-genesis has no '$f'"
done
if [ "$OLYMPIA_BLOCK" = 0 ] && ! jq -e 'has("baseFeePerGas")' "$GETH_GENESIS" >/dev/null; then
  warn "Olympia at 0 and the core-geth genesis has no baseFeePerGas: fukuii then puts 1 gwei in the genesis header;" \
    "if core-geth puts another value the genesis hashes differ and start.sh refuses the net"
fi
alloc_has_treasury=$(jq -r --arg t "$(tr '[:upper:]' '[:lower:]' <<<"${TREASURY#0x}")" \
  '[.alloc // {} | keys[] | ascii_downcase | ltrimstr("0x")] | index($t) != null' "$GETH_GENESIS")
[ "$alloc_has_treasury" = true ] && log "the genesis allocates the treasury address (its starting balance is read by check.sh)"

# fukuii's genesis: the header fields and the allocation, as core-geth's file states them.
FUKUII_GENESIS_JSON=$(jq '{
    difficulty, gasLimit, timestamp, extraData,
    nonce: (.nonce // "0x0000000000000000"),
    mixHash: (.mixHash // "0x0000000000000000000000000000000000000000000000000000000000000000"),
    coinbase: (.coinbase // "0x0000000000000000000000000000000000000000"),
    ommersHash: "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347",
    alloc: (.alloc // {})
  } + (if has("baseFeePerGas") then {baseFeePerGas} else {} end)' "$GETH_GENESIS")

NEVER=1000000000000000000
if [ "$MESS" = never ]; then MESS_ENABLED=false MESS_AT=$NEVER; else MESS_ENABLED=true MESS_AT=$MESS; fi
CHAIN_CONF=$(
  cat <<EOF
# GENERATED by scripts/etc-private-net/init.sh — do not edit; re-run init.sh --force.
# Private ETC-config PoW net (spec 018 C6, T244). Every ETC fork through Spiral at genesis; Olympia at $OLYMPIA_BLOCK.
# The ETH-only forks stay off, as on Mordor (mordor-chain.conf), whose layout this file follows.
{
  network-type = "etc"
  network-id = $NETWORK_ID
  chain-id = "$(printf '0x%x' "$CHAIN_ID")"
  pow-target-time = null
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip106-block-number = "$NEVER"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  eip161-block-number = "$NEVER"
  max-code-size = "24576"
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"
  byzantium-block-number = "$NEVER"
  atlantis-block-number = "0"
  agharta-block-number = "0"
  phoenix-block-number = "0"
  constantinople-block-number = "$NEVER"
  petersburg-block-number = "$NEVER"
  istanbul-block-number = "$NEVER"
  treasury-address = "${TREASURY#0x}"
  # ECIP-1111 base-fee floor and ECIP-1122 minimum tip: Mordor's and ETC mainnet's values.
  base-fee-floor = "1000000000"
  min-tip = "1000000000"
  ecip1099-block-number = "0"
  muir-glacier-block-number = "$NEVER"
  magneto-block-number = "0"
  berlin-block-number = "$NEVER"
  mystique-block-number = "0"
  spiral-block-number = "0"
  olympia-block-number = "$OLYMPIA_BLOCK"
  dao = null
  account-start-nonce = "0"
  custom-genesis-file = "$NET_DIR/chains/$PRIVNET_NAME-genesis.json"
  monetary-policy {
    first-era-block-reward = "5000000000000000000"
    first-era-reduced-block-reward = "5000000000000000000"
    first-era-constantinople-reduced-block-reward = "5000000000000000000"
    era-duration = $ERA_DURATION
    reward-reduction-rate = 0.2
  }
  eth-compatible-storage = true
  dns-discovery-domains = []
  bootstrap-nodes = []
  mess {
    # --mess $MESS. The first window (ECBP-1100 to Spiral) is empty here; the second opens at the reactivation block.
    enabled = $MESS_ENABLED
    ecbp1100-block-number = "$NEVER"
    ecbp1100-deactivate-block-number = "$NEVER"
    ecbp1100-reactivate-block-number = "$MESS_AT"
  }
  checkpoint-expected-block-hash = ""
}
EOF
)

# --- Nodes ---------------------------------------------------------------------------------------------------------
NODES=()
for i in $(seq 1 "$MINERS"); do NODES+=("miner$i"); done
NODES+=(geth)
for n in "${!NODE_HOSTS[@]}"; do
  printf '%s\n' "${NODES[@]}" | grep -qx "$n" || die "--node-host names '$n', which is not a node of this net (${NODES[*]})"
done

# Coinbases: one address per miner that no one holds a key for and that is no precompile (0x...c0b101 to c0b104), so
# that check.sh can attribute each block. load.sh moves miner1's coinbase to a keystore account it creates, which funds
# the transactions the Olympia checks need.
coinbase_of() { printf '0x%040x' $((0xc0b100 + $1)); }

NODES_TSV=$'# name\tkind\thost\tp2p_port\trpc_port\tmetrics_port\tmine\tcoinbase'
i=0
for n in "${NODES[@]}"; do
  i=$((i + 1))
  host=${NODE_HOSTS[$n]:-$HOST}
  if [ "$n" = geth ]; then kind=geth mine=$GETH_MINES metrics=-; else kind=fukuii mine=true metrics=$((BASE_METRICS + i)); fi
  NODES_TSV+=$'\n'"$n"$'\t'"$kind"$'\t'"$host"$'\t'"$((BASE_P2P + i))"$'\t'"$((BASE_RPC + i))"$'\t'"$metrics"$'\t'"$mine"$'\t'"$(coinbase_of "$i")"
done

fukuii_node_conf() {
  local n=$1 host p2p rpcp metrics mine coinbase
  host=$(awk -F'\t' -v n="$n" '$1==n{print $3}' <<<"$NODES_TSV")
  p2p=$(awk -F'\t' -v n="$n" '$1==n{print $4}' <<<"$NODES_TSV")
  rpcp=$(awk -F'\t' -v n="$n" '$1==n{print $5}' <<<"$NODES_TSV")
  metrics=$(awk -F'\t' -v n="$n" '$1==n{print $6}' <<<"$NODES_TSV")
  mine=$(awk -F'\t' -v n="$n" '$1==n{print $7}' <<<"$NODES_TSV")
  coinbase=$(awk -F'\t' -v n="$n" '$1==n{print $8}' <<<"$NODES_TSV")
  cat <<EOF
# GENERATED by scripts/etc-private-net/init.sh for $n — do not edit; re-run init.sh --force.
include classpath("application.conf")

fukuii {
  datadir = "$NET_DIR/nodes/$n/data"
  blockchains {
    network = "$PRIVNET_NAME"
    custom-chains-dir = "$NET_DIR/chains"
  }
  network {
    server-address {
      interface = "$host"
      port = $p2p
      # A private net: advertise the configured address and never ask a router or a third party for another.
      advertised-address = "$host"
      external-ip-detection = none
    }
    automatic-port-forwarding = false
    peer {
      # make-fork.sh disconnects and reconnects siblings on purpose: a sibling must not stay blacklisted for an hour,
      # and a sibling still at the genesis while the DAG is built must not be evicted.
      short-blacklist-duration = 5.seconds
      long-blacklist-duration = 5.seconds
      genesis-head-eviction-grace = 0
    }
    discovery {
      # Static wiring only: start.sh connects the nodes with admin_addPeer, make-fork.sh cuts and restores links.
      discovery-enabled = false
      reuse-known-nodes = false
      interface = "$host"
      port = $p2p
      host = "$host"
    }
    rpc.http {
      enabled = true
      interface = "$host"
      port = $rpcp
      apis = "eth,net,web3,admin,debug,personal,miner,txpool"
    }
  }
  mining {
    mining-enabled = $mine
    protocol = "pow"
    coinbase = "${coinbase#0x}"
  }
  pow.ethash-dir = "$DAG_DIR/$n"
  sync {
    do-snap-sync = false
    blacklist-duration = 0.seconds
  }
  state.path-live = $PATH_LIVE
  metrics { enabled = true, port = $metrics }
}
EOF
}

# The shipped logback.xml keeps the consensus and regular-sync loggers at ERROR; check.sh counts REORG-SETTLE and
# REORG-EXEC-FAIL lines, so the fukuii nodes log at INFO into one plain file each (start.sh names it).
LOGBACK_XML=$(
  cat <<'EOF'
<configuration>
  <appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <file>${fukuii.privnet.logfile}</file>
    <append>true</append>
    <encoder><pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSX,UTC} %-5level [%logger{36}] - %msg%n</pattern></encoder>
  </appender>
  <logger name="io.netty" level="WARN"/>
  <logger name="org.apache.pekko" level="WARN"/>
  <logger name="org.jupnp" level="ERROR"/>
  <root level="INFO"><appender-ref ref="FILE"/></root>
</configuration>
EOF
)

NET_ENV=$(
  cat <<EOF
# GENERATED by scripts/etc-private-net/init.sh — do not edit; re-run init.sh --force.
CHAIN_ID=$CHAIN_ID
NETWORK_ID=$NETWORK_ID
OLYMPIA_BLOCK=$OLYMPIA_BLOCK
TREASURY=$TREASURY
ERA_DURATION=$ERA_DURATION
PATH_LIVE=$PATH_LIVE
MESS=$MESS
BASE_FEE_FLOOR=1000000000
FUKUII_JAR=$(printf '%q' "$FUKUII_JAR")
GETH_BIN=$(printf '%q' "$GETH_BIN")
JAVA_BIN=$(printf '%q' "$JAVA_BIN")
DAG_DIR=$(printf '%q' "$DAG_DIR")
FUKUII_JAR_SHA256=$(sha256sum "$FUKUII_JAR" | cut -d' ' -f1)
GETH_GENESIS_SHA256=$(sha256sum "$GETH_GENESIS" | cut -d' ' -f1)
EOF
)

if [ "$DRY_RUN" = true ]; then
  echo "== $NET_DIR/net.env"; echo "$NET_ENV"
  echo "== $NET_DIR/nodes.tsv"; echo "$NODES_TSV"
  echo "== $NET_DIR/chains/$PRIVNET_NAME-chain.conf"; echo "$CHAIN_CONF"
  echo "== $NET_DIR/chains/$PRIVNET_NAME-genesis.json"; echo "$FUKUII_GENESIS_JSON"
  for n in "${NODES[@]}"; do
    [ "$n" = geth ] && continue
    echo "== $NET_DIR/nodes/$n/node.conf"; fukuii_node_conf "$n"
  done
  echo "== core-geth: $GETH_BIN --datadir $NET_DIR/nodes/geth/data init $NET_DIR/chains/geth-genesis.json (at start)"
  exit 0
fi

mkdir -p "$NET_DIR/chains"
printf '%s\n' "$NET_ENV" >"$NET_DIR/net.env"
printf '%s\n' "$NODES_TSV" >"$NET_DIR/nodes.tsv"
printf '%s\n' "$CHAIN_CONF" >"$NET_DIR/chains/$PRIVNET_NAME-chain.conf"
printf '%s\n' "$FUKUII_GENESIS_JSON" >"$NET_DIR/chains/$PRIVNET_NAME-genesis.json"
printf '%s\n' "$LOGBACK_XML" >"$NET_DIR/logback.xml"
cp "$GETH_GENESIS" "$NET_DIR/chains/geth-genesis.json"
for n in "${NODES[@]}"; do
  mkdir -p "$NET_DIR/nodes/$n"
  [ "$n" = geth ] || { mkdir -p "$DAG_DIR/$n"; fukuii_node_conf "$n" >"$NET_DIR/nodes/$n/node.conf"; }
done

log "wrote the net to $NET_DIR: ${NODES[*]} (chain id $CHAIN_ID, Olympia at $OLYMPIA_BLOCK, path-live $PATH_LIVE)"
log "next: scripts/etc-private-net/start.sh --net-dir $NET_DIR"
