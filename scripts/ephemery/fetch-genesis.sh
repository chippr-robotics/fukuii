#!/usr/bin/env bash
#
# fetch-genesis.sh — prepare fukuii for the CURRENT Ephemery iteration.
#
# Ephemery (https://github.com/ephemery-testnet/ephemery-resources) resets to a new genesis every
# 2,419,200 s (28 days). Each iteration N has chain id 39438000+N, a new genesis timestamp, new BPO
# timestamps and a new genesis hash, published as a GitHub release `ephemery-N` of
# https://github.com/ephemery-testnet/ephemery-genesis. Nothing iteration-specific is compiled into
# fukuii: this script downloads a release and GENERATES the files fukuii reads.
#
# Output layout (default OUT = $HOME/.fukuii/ephemery-network):
#   OUT/ephemery-N/testnet/                 the release's testnet-all.tar.gz, verbatim. Also a valid
#                                           Lighthouse --testnet-dir (config.yaml, genesis.ssz,
#                                           deploy_block.txt, boot_enr.yaml).
#   OUT/ephemery-N/chains/ephemery-chain.conf    generated fukuii chain config (custom-chains-dir)
#   OUT/ephemery-N/chains/ephemery-genesis.json  genesis.json re-encoded for GenesisDataLoader
#   OUT/ephemery-N/conf/ephemery.conf       operator conf: points fukuii at the chains dir above and a
#                                           per-iteration datadir; run with FUKUII_CONF_DIR=<this dir>
#   OUT/ephemery-N/release.env              ITERATION, CHAIN_ID, GENESIS_HASH, RESET_TIME, ... (shell)
#   OUT/current -> ephemery-N               symlink, so a systemd unit can use OUT/current/conf
#   OUT/ephemery-local.conf                 operator overrides (ports, engine-api, jwt). Created once
#                                           from a template, never overwritten; included by every
#                                           generated operator conf.
#
# Usage:
#   scripts/ephemery/fetch-genesis.sh [--release ephemery-N] [--out DIR] [--from-dir TESTNET_DIR]
#
#   --release TAG     a specific release (default: the latest release)
#   --out DIR         output root (default: $FUKUII_EPHEMERY_DIR or $HOME/.fukuii/ephemery-network)
#   --from-dir DIR    skip the download; generate from an already-extracted testnet-all directory
#                     (offline use; also how the test fixture under src/test/resources was produced)
#
# Fails loudly (non-zero exit, nothing written for that iteration) if the release's genesis config
# declares something fukuii cannot represent: a non-zero block fork, an unknown fork key, a fork after
# Amsterdam (bpo3Time...), a non-zero terminal total difficulty, or a header quantity it cannot
# re-encode. A blob schedule that differs from the values fukuii hard-codes is a WARNING (see the
# runbook, docs/runbooks/ephemery.md, "Known limitation: blob schedule").
#
# Requires: bash, curl, tar, jq.

set -euo pipefail

REPO="ephemery-testnet/ephemery-genesis"
RELEASE=""
OUT="${FUKUII_EPHEMERY_DIR:-$HOME/.fukuii/ephemery-network}"
FROM_DIR=""

die() { echo "fetch-genesis: ERROR: $*" >&2; exit 1; }
warn() { echo "fetch-genesis: WARNING: $*" >&2; }
info() { echo "fetch-genesis: $*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) RELEASE="${2:?--release needs a tag}"; shift 2 ;;
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    --from-dir) FROM_DIR="${2:?--from-dir needs a directory}"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

for tool in jq tar; do command -v "$tool" >/dev/null || die "$tool is required"; done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------------------------------
# 1. Obtain the release's testnet directory
# ---------------------------------------------------------------------------------------------------
if [[ -n "$FROM_DIR" ]]; then
  [[ -f "$FROM_DIR/genesis.json" ]] || die "$FROM_DIR has no genesis.json"
  mkdir -p "$WORK/testnet"
  cp -a "$FROM_DIR/." "$WORK/testnet/"
else
  command -v curl >/dev/null || die "curl is required"
  if [[ -z "$RELEASE" ]]; then
    RELEASE="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | jq -r '.tag_name')"
    [[ "$RELEASE" =~ ^ephemery-[0-9]+$ ]] || die "could not determine the latest release (got '$RELEASE')"
  fi
  url="https://github.com/$REPO/releases/download/$RELEASE/testnet-all.tar.gz"
  info "downloading $url"
  curl -fsSL -o "$WORK/testnet-all.tar.gz" "$url" || die "download failed: $url"
  mkdir -p "$WORK/testnet"
  tar -xzf "$WORK/testnet-all.tar.gz" -C "$WORK/testnet"
fi

T="$WORK/testnet"
G="$T/genesis.json"
[[ -f "$G" ]] || die "release has no genesis.json"

# retention.vars / nodevars_env.txt carry the iteration metadata (shell assignments).
ITERATION="" ; CHAIN_ID_VARS="" ; GENESIS_TIMESTAMP_VARS="" ; RESET_INTERVAL=""
if [[ -f "$T/retention.vars" ]]; then
  ITERATION="$(sed -n 's/^export ITERATION_NUMBER="\{0,1\}\([0-9]*\)"\{0,1\}$/\1/p' "$T/retention.vars")"
  GENESIS_TIMESTAMP_VARS="$(sed -n 's/^export GENESIS_TIMESTAMP="\{0,1\}\([0-9]*\)"\{0,1\}$/\1/p' "$T/retention.vars")"
  RESET_INTERVAL="$(sed -n 's/^export GENESIS_RESET_INTERVAL="\{0,1\}\([0-9]*\)"\{0,1\}$/\1/p' "$T/retention.vars")"
  CHAIN_ID_VARS="$(sed -n 's/^export CHAIN_ID="\{0,1\}\([0-9]*\)"\{0,1\}$/\1/p' "$T/retention.vars")"
fi

# ---------------------------------------------------------------------------------------------------
# 2. Validate the genesis config against what fukuii can represent
# ---------------------------------------------------------------------------------------------------
CHAIN_ID="$(jq -er '.config.chainId' "$G")" || die "genesis.json has no config.chainId"
[[ "$CHAIN_ID" =~ ^[0-9]+$ ]] || die "config.chainId is not an integer: $CHAIN_ID"
[[ -z "$CHAIN_ID_VARS" || "$CHAIN_ID_VARS" == "$CHAIN_ID" ]] ||
  die "retention.vars CHAIN_ID=$CHAIN_ID_VARS disagrees with genesis.json chainId=$CHAIN_ID"
if [[ -z "$ITERATION" ]]; then ITERATION=$((CHAIN_ID - 39438000)); fi
[[ -n "$RELEASE" ]] || RELEASE="ephemery-$ITERATION"

# Every key fukuii knows how to map. Anything else is a fork or parameter fukuii would silently ignore.
KNOWN_BLOCK_KEYS='["homesteadBlock","eip150Block","eip155Block","eip158Block","byzantiumBlock",
  "constantinopleBlock","petersburgBlock","istanbulBlock","muirGlacierBlock","berlinBlock","londonBlock",
  "arrowGlacierBlock","grayGlacierBlock","mergeNetsplitBlock"]'
KNOWN_TIME_KEYS='["shanghaiTime","cancunTime","pragueTime","osakaTime","bpo1Time","bpo2Time","amsterdamTime"]'
KNOWN_OTHER_KEYS='["chainId","terminalTotalDifficulty","terminalTotalDifficultyPassed","blobSchedule",
  "depositContractAddress","ethash"]'

unknown="$(jq -r --argjson b "$KNOWN_BLOCK_KEYS" --argjson t "$KNOWN_TIME_KEYS" --argjson o "$KNOWN_OTHER_KEYS" \
  '.config | keys - $b - $t - $o | join(",")' "$G")"
[[ -z "$unknown" ]] || die "genesis config declares keys fukuii cannot map: $unknown"

nonzero_blocks="$(jq -r --argjson b "$KNOWN_BLOCK_KEYS" \
  '.config as $c | [$b[] | select($c[.] != null and $c[.] != 0)] | join(",")' "$G")"
[[ -z "$nonzero_blocks" ]] ||
  die "block forks after genesis are not supported for Ephemery (all must be 0): $nonzero_blocks"
[[ "$(jq -r '.config.terminalTotalDifficulty' "$G")" == "0" ]] ||
  die "terminalTotalDifficulty must be 0 (Ephemery is merged at genesis)"

time_key() { # $1 = geth key; prints the value or "null"; dies on non-integer
  local v; v="$(jq -r --arg k "$1" '.config[$k] // "null"' "$G")"
  [[ "$v" == "null" || "$v" =~ ^[0-9]+$ ]] || die "config.$1 is not an integer: $v"
  echo "$v"
}
SHANGHAI="$(time_key shanghaiTime)"; CANCUN="$(time_key cancunTime)"; PRAGUE="$(time_key pragueTime)"
OSAKA="$(time_key osakaTime)"; BPO1="$(time_key bpo1Time)"; BPO2="$(time_key bpo2Time)"
AMSTERDAM="$(time_key amsterdamTime)"
for k in SHANGHAI CANCUN PRAGUE; do
  [[ "${!k}" != "null" ]] || die "Ephemery genesis without $k is not supported (expected it active at genesis)"
done

DEPOSIT="$(jq -r '.config.depositContractAddress // "null"' "$G")"
[[ "$DEPOSIT" =~ ^0x[0-9a-fA-F]{40}$ ]] || die "config.depositContractAddress missing or malformed: $DEPOSIT"

# Blob schedule: fukuii hard-codes the mainnet/Sepolia BPO parameters (EngineApiService, OpCode.BLOBBASEFEE,
# BlobGasUtils); it cannot take them from config. Report every difference.
declare -A FUKUII_BLOBS=(
  [cancun]="3/6/3338477" [prague]="6/9/5007716" [osaka]="6/9/5007716"
  [bpo1]="10/15/8346193" [bpo2]="14/21/11684671"
)
BLOB_MISMATCH=""
while IFS=$'\t' read -r fork value; do
  [[ -n "$fork" ]] || continue
  expected="${FUKUII_BLOBS[$fork]:-}"
  if [[ -z "$expected" ]]; then
    die "blobSchedule declares fork '$fork', which fukuii has no blob parameters for"
  elif [[ "$expected" != "$value" ]]; then
    BLOB_MISMATCH="${BLOB_MISMATCH:+$BLOB_MISMATCH,}$fork"
    warn "blobSchedule.$fork is target/max/updateFraction $value; fukuii hard-codes $expected." \
      "Blocks from that fork on can diverge once blob usage exceeds the target (see docs/runbooks/ephemery.md)."
  fi
done < <(jq -r '.config.blobSchedule // {} | to_entries[] | "\(.key)\t\(.value.target)/\(.value.max)/\(.value.baseFeeUpdateFraction)"' "$G")

# ---------------------------------------------------------------------------------------------------
# 3. Re-encode the genesis header for GenesisDataLoader
# ---------------------------------------------------------------------------------------------------
# GenesisDataLoader.prepareHeader parses timestamp, gasLimit, difficulty, baseFeePerGas, blobGasUsed and
# excessBlobGas as HEX whether or not they carry 0x. go-ethereum's genesis.json accepts decimal for these
# (Ephemery writes timestamp as the decimal string "1790276400"); loaded verbatim, that becomes timestamp
# 0x1790276400 and a genesis hash no peer accepts — silently. Convert every such field to 0x-hex here.
to_hex() { # $1 = field name, $2 = value (decimal or 0x-hex, string or number)
  local v="$2"
  if [[ "$v" =~ ^0[xX][0-9a-fA-F]+$ ]]; then echo "0x${v:2}" | tr 'A-F' 'a-f'
  elif [[ "$v" =~ ^[0-9]{1,18}$ ]]; then printf '0x%x\n' "$((10#$v))"
  else die "genesis.$1 is neither 0x-hex nor a decimal below 10^18: '$v'"
  fi
}
field() { jq -r --arg k "$1" 'if .[$k] == null then "null" else (.[$k] | tostring) end' "$G"; }

TS_RAW="$(field timestamp)"; [[ "$TS_RAW" != "null" ]] || die "genesis.timestamp missing"
TS_HEX="$(to_hex timestamp "$TS_RAW")"
GENESIS_TS=$((16#${TS_HEX:2}))
GL_HEX="$(to_hex gasLimit "$(field gasLimit)")"
DIFF_HEX="$(to_hex difficulty "$(field difficulty)")"
opt_hex() { local v; v="$(field "$1")"; if [[ "$v" == "null" ]]; then echo null; else to_hex "$1" "$v"; fi; }
BASEFEE_HEX="$(opt_hex baseFeePerGas)"
BLOBUSED_HEX="$(opt_hex blobGasUsed)"
EXCESS_HEX="$(opt_hex excessBlobGas)"

# Cross-checks against the other copies the release ships.
[[ -z "$GENESIS_TIMESTAMP_VARS" || "$GENESIS_TIMESTAMP_VARS" == "$GENESIS_TS" ]] ||
  die "retention.vars GENESIS_TIMESTAMP=$GENESIS_TIMESTAMP_VARS disagrees with genesis.json timestamp=$GENESIS_TS"
if [[ -f "$T/chainspec.json" ]]; then
  cs_ts="$(jq -r '.genesis.timestamp' "$T/chainspec.json")"
  [[ "$(to_hex chainspec.timestamp "$cs_ts")" == "$TS_HEX" ]] ||
    die "chainspec.json genesis timestamp $cs_ts disagrees with genesis.json $TS_RAW"
fi

# Account fields must be JSON strings for GenesisAccountSerializer.
bad_alloc="$(jq -r '[.alloc[] | (.balance, .nonce) | select(. != null and type != "string")] | length' "$G")"
[[ "$bad_alloc" == "0" ]] || die "$bad_alloc alloc balance/nonce values are not JSON strings"

if [[ "$AMSTERDAM" != "null" && "$AMSTERDAM" -le "$GENESIS_TS" ]]; then
  die "Amsterdam active at genesis: GenesisDataLoader would need slotNumber handling not verified for Ephemery"
fi

EXPECTED_HASH=""
[[ -f "$T/deposit_contract_block_hash.txt" ]] && EXPECTED_HASH="$(tr -d '[:space:]' < "$T/deposit_contract_block_hash.txt")"

# ---------------------------------------------------------------------------------------------------
# 4. Write the iteration directory
# ---------------------------------------------------------------------------------------------------
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
DEST="$OUT/$RELEASE"
STAGE="$WORK/stage"
mkdir -p "$STAGE/chains" "$STAGE/conf"
cp -a "$T" "$STAGE/testnet"

jq --arg ts "$TS_HEX" --arg gl "$GL_HEX" --arg diff "$DIFF_HEX" \
   --arg basefee "$BASEFEE_HEX" --arg blobused "$BLOBUSED_HEX" --arg excess "$EXCESS_HEX" '
  {
    nonce: (.nonce // "0x0"),
    mixHash: (.mixHash // .mixhash // "0x0000000000000000000000000000000000000000000000000000000000000000"),
    difficulty: $diff,
    extraData: (if (.extraData // "") == "" then "0x" else .extraData end),
    gasLimit: $gl,
    coinbase: (.coinbase // "0x0000000000000000000000000000000000000000"),
    timestamp: $ts
  }
  + (if $basefee == "null" then {} else {baseFeePerGas: $basefee} end)
  + (if $blobused == "null" then {} else {blobGasUsed: $blobused} end)
  + (if $excess == "null" then {} else {excessBlobGas: $excess} end)
  + {alloc: .alloc}
' "$G" > "$STAGE/chains/ephemery-genesis.json"

hocon_ts() { if [[ "$1" == "null" ]]; then echo "null"; else echo "$1"; fi; }
BOOTNODES=""
if [[ -f "$T/enodes.txt" ]]; then
  while IFS= read -r line; do
    line="${line%%#*}"; line="$(echo "$line" | tr -d '[:space:]')"
    [[ "$line" =~ ^enode:// ]] || continue
    BOOTNODES+="    \"$line\","$'\n'
  done < "$T/enodes.txt"
fi
[[ -n "$BOOTNODES" ]] || warn "release ships no EL enodes (enodes.txt); bootstrap-nodes will be empty"
BOOTNODES="${BOOTNODES%,$'\n'}"$'\n'

cat > "$STAGE/chains/ephemery-chain.conf" <<EOF
{
  # GENERATED by scripts/ephemery/fetch-genesis.sh from $RELEASE — do not edit; re-run the script.
  # Source: https://github.com/$REPO/releases/tag/$RELEASE (genesis.json config, enodes.txt).
  # Ephemery resets every ${RESET_INTERVAL:-2419200} s; the next iteration has a new chain id, genesis and
  # timestamps, so this file is valid for $RELEASE only.
  # Published genesis block hash: ${EXPECTED_HASH:-unknown}
  network-id = $CHAIN_ID
  chain-id = "0x$(printf '%x' "$CHAIN_ID")"
  network-type = "eth"

  # Merged at genesis (terminalTotalDifficulty 0).
  terminal-total-difficulty = "0"

  # Timestamp forks (EIP-6122), verbatim from genesis.json config.*Time. Forks at or before the genesis
  # timestamp ($GENESIS_TS) are part of the genesis ruleset and are not fork-id checksum entries.
  shanghai-timestamp = $(hocon_ts "$SHANGHAI")
  cancun-timestamp = $(hocon_ts "$CANCUN")
  prague-timestamp = $(hocon_ts "$PRAGUE")
  osaka-timestamp = $(hocon_ts "$OSAKA")
  bpo1-timestamp = $(hocon_ts "$BPO1")
  bpo2-timestamp = $(hocon_ts "$BPO2")
  amsterdam-timestamp = $(hocon_ts "$AMSTERDAM")

  merge-netsplit-block-number = "0"
  deposit-contract-address = "$DEPOSIT"

  sync {
    do-snap-sync = false
  }

  pow-target-time = null

  # Every block fork is active at genesis (the script rejects any other value). "0" and the 10^18 sentinel
  # are both filtered by ForkId.gatherBlockForks, so the checksum chain reaches the timestamp forks.
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip106-block-number = "1000000000000000000"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  eip161-block-number = "0"
  max-code-size = "24576"
  difficulty-bomb-pause-block-number = "1000000000000000000"
  difficulty-bomb-continue-block-number = "1000000000000000000"
  difficulty-bomb-removal-block-number = "1000000000000000000"
  byzantium-block-number = "0"
  constantinople-block-number = "0"
  petersburg-block-number = "0"
  istanbul-block-number = "0"
  muir-glacier-block-number = "1000000000000000000"
  berlin-block-number = "0"

  # ETC-specific forks (disabled)
  atlantis-block-number = "1000000000000000000"
  agharta-block-number = "1000000000000000000"
  phoenix-block-number = "1000000000000000000"
  ecip1099-block-number = "1000000000000000000"
  magneto-block-number = "1000000000000000000"
  mystique-block-number = "1000000000000000000"
  spiral-block-number = "1000000000000000000"

  # London (EIP-1559) from genesis. MUST be "0", not the 10^18 sentinel (see plataberget-chain.conf).
  olympia-block-number = "0"
  olympia-gas-limit-elasticity = 2
  treasury-address = "0000000000000000000000000000000000000000"
  account-start-nonce = "0"

  custom-genesis-file = { include required("ephemery-genesis.json") }

  monetary-policy {
    first-era-block-reward = "0"
    first-era-reduced-block-reward = "0"
    first-era-constantinople-reduced-block-reward = "0"
    era-duration = 2000000000
    reward-reduction-rate = 0
  }

  gas-tie-breaker = false
  eth-compatible-storage = true

  bootstrap-nodes = [
${BOOTNODES}  ]

  mess {
    enabled = false
  }
}
EOF

cat > "$STAGE/conf/ephemery.conf" <<EOF
# GENERATED by scripts/ephemery/fetch-genesis.sh for $RELEASE — do not edit; put overrides in
# $OUT/ephemery-local.conf. Launch: FUKUII_CONF_DIR=$DEST/conf fukuii ephemery
include classpath("conf/ephemery.conf")

fukuii {
  blockchains.custom-chains-dir = "$DEST/chains"
  # One datadir per iteration: a datadir holding another iteration's genesis is refused at startup.
  datadir = "$DEST/data"
}

include file("$OUT/ephemery-local.conf")
EOF

RESET_TIME=$((GENESIS_TS + ${RESET_INTERVAL:-2419200}))
{
  echo "# GENERATED by scripts/ephemery/fetch-genesis.sh"
  echo "ITERATION=\"$ITERATION\""
  echo "RELEASE=\"$RELEASE\""
  echo "CHAIN_ID=\"$CHAIN_ID\""
  echo "GENESIS_TIMESTAMP=\"$GENESIS_TS\""
  echo "RESET_TIME=\"$RESET_TIME\""
  echo "GENESIS_HASH=\"$EXPECTED_HASH\""
  echo "BLOB_SCHEDULE_MISMATCH=\"$BLOB_MISMATCH\""
  echo "OUT_DIR=\"$OUT\""
  echo "TESTNET_DIR=\"$DEST/testnet\""
  echo "CHAINS_DIR=\"$DEST/chains\""
  echo "FUKUII_CONF_DIR=\"$DEST/conf\""
  if [[ -f "$T/boot_enr.txt" ]]; then
    echo "BOOTNODE_ENR_LIST=\"$(grep -v '^\s*$' "$T/boot_enr.txt" | paste -sd, -)\""
  fi
} > "$STAGE/release.env"

if [[ -e "$DEST" ]]; then
  # Keep an existing datadir; replace only generated files.
  rm -rf "$DEST/testnet" "$DEST/chains" "$DEST/conf" "$DEST/release.env"
  mv "$STAGE/testnet" "$STAGE/chains" "$STAGE/conf" "$STAGE/release.env" "$DEST/"
else
  mv "$STAGE" "$DEST"
fi
ln -sfn "$RELEASE" "$OUT/current"

if [[ ! -e "$OUT/ephemery-local.conf" ]]; then
  cat > "$OUT/ephemery-local.conf" <<'EOF'
# Operator overrides for every Ephemery iteration (never overwritten by fetch-genesis.sh).
# Ports chosen not to collide with the other fukuii units / Lighthouse containers on this host
# (they use p2p 30303, RPC 8546, WS 8552, Engine API 8551, metrics 13798).
fukuii {
  network {
    server-address.port = 30313
    discovery.port = 30313
    rpc.http.port = 8556
    rpc.ws.port = 8557
    engine-api {
      enabled = true
      interface = "localhost"
      port = 8561
      # jwt-secret-path = "/path/to/ephemery/jwt.hex"
    }
  }
  metrics.port = 13799
}
EOF
  info "created $OUT/ephemery-local.conf (edit jwt-secret-path before the first start)"
fi

info "wrote $DEST (chain id $CHAIN_ID, genesis $GENESIS_TS, expected genesis hash ${EXPECTED_HASH:-unknown})"
info "next reset: $(date -u -d "@$RESET_TIME" 2>/dev/null || echo "$RESET_TIME")"
[[ -z "$BLOB_MISMATCH" ]] || warn "blob schedule differs from fukuii's for: $BLOB_MISMATCH"
echo "$DEST"
