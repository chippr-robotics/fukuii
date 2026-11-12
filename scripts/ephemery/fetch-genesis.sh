#!/usr/bin/env bash
#
# fetch-genesis.sh — prepare fukuii for the CURRENT Ephemery iteration.
#
# Ephemery (https://github.com/ephemery-testnet/ephemery-resources) resets to a new genesis every
# 2,419,200 s (28 days). Each iteration N has chain id 39438000+N, a new genesis timestamp, new BPO
# timestamps and a new genesis hash, published as a GitHub release `ephemery-N` of
# https://github.com/ephemery-testnet/ephemery-genesis. Nothing iteration-specific is compiled into
# fukuii: this script downloads a release and fukuii GENERATES the files it reads.
#
# This script downloads and unpacks the release. Every rule (what fukuii can represent, the genesis
# re-encoding, the chain config, the genesis-hash check against the published hash) is applied by the one
# rule engine in fukuii, `fukuii cli ephemery-fetch`, which this script runs (spec 028). The node applies
# the same rules at startup.
#
# Output layout (default OUT = $HOME/.fukuii/ephemery-network):
#   OUT/ephemery-N/testnet/                 the release's members, verbatim. Also a valid Lighthouse
#                                           --testnet-dir (config.yaml, genesis.ssz, deploy_block.txt,
#                                           boot_enr.yaml — Lighthouse reads its CL bootnodes there).
#   OUT/ephemery-N/chains/ephemery-chain.conf    generated fukuii chain config (custom-chains-dir)
#   OUT/ephemery-N/chains/ephemery-genesis.json  genesis.json re-encoded for GenesisDataLoader
#   OUT/ephemery-N/conf/ephemery.conf       operator conf: points fukuii at the chains dir above and a
#                                           per-iteration datadir; run with FUKUII_CONF_DIR=<this dir>
#   OUT/ephemery-N/release.env              ITERATION, CHAIN_ID, GENESIS_HASH, RESET_TIME, ... (shell)
#   OUT/ephemery-N/release.json             the same facts as JSON, with where the release came from
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
#                     (offline use; also how the test inputs under src/test/resources were produced)
#
# Environment:
#   FUKUII_BIN        the fukuii launcher to run (default: `fukuii` on PATH). From a checkout:
#                     FUKUII_BIN=target/universal/stage/bin/fukuii after `sbt stage`.
#
# Fails loudly (non-zero exit, nothing written for that iteration) when fukuii refuses the release; the
# message says why. Exit codes: 2 usage or no fukuii launcher; 78 the release is refused; 1 other errors.
#
# Requires: bash >= 4, tar, curl (to download), and the fukuii launcher.

set -euo pipefail

if [[ -z "${BASH_VERSINFO:-}" || "${BASH_VERSINFO[0]}" -lt 4 ]]; then
  echo "fetch-genesis: ERROR: bash >= 4 is required (running ${BASH_VERSION:-not bash})" >&2
  exit 1
fi

REPO="ephemery-testnet/ephemery-genesis"
RELEASE=""
OUT="${FUKUII_EPHEMERY_DIR:-$HOME/.fukuii/ephemery-network}"
FROM_DIR=""

die() { echo "fetch-genesis: ERROR: $*" >&2; exit 1; }
usage_error() { echo "fetch-genesis: ERROR: $*" >&2; exit 2; }
info() { echo "fetch-genesis: $*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) RELEASE="${2:?--release needs a tag}"; shift 2 ;;
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    --from-dir) FROM_DIR="${2:?--from-dir needs a directory}"; shift 2 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

# RELEASE and OUT are interpolated into paths, HOCON and release.env: accept only release tags of the
# published form and output paths without quoting/substitution metacharacters.
if [[ -n "$RELEASE" && ! "$RELEASE" =~ ^ephemery-[0-9]+$ ]]; then
  die "--release must look like ephemery-<N> (got '$RELEASE')"
fi
if [[ "$OUT" == *[\"\\\$\`\'$'\n']* ]]; then
  die "--out must not contain quotes, backslashes, \$, backticks or newlines (got '$OUT')"
fi

FUKUII="${FUKUII_BIN:-fukuii}"
if [[ "$FUKUII" == */* ]]; then fukuii_ok=0; [[ -x "$FUKUII" && -f "$FUKUII" ]] && fukuii_ok=1
else fukuii_ok=0; command -v "$FUKUII" >/dev/null 2>&1 && fukuii_ok=1; fi
if [[ "$fukuii_ok" != 1 ]]; then
  usage_error "no fukuii launcher: '$FUKUII' is not on PATH or executable. Set FUKUII_BIN to it (after 'sbt stage':
  FUKUII_BIN=target/universal/stage/bin/fukuii), or from a checkout run:
  sbt 'runMain com.chipprbots.ethereum.App cli ephemery-fetch --from-dir DIR --out DIR'"
fi
command -v tar >/dev/null || die "tar is required"

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------------
# 1. Obtain the release's testnet directory
# ---------------------------------------------------------------------------------------------------
SOURCE_ARGS=()
if [[ -n "$FROM_DIR" ]]; then
  [[ -f "$FROM_DIR/genesis.json" ]] || die "$FROM_DIR has no genesis.json"
  T="$FROM_DIR"
  [[ -z "$RELEASE" ]] || SOURCE_ARGS+=(--release "$RELEASE")
else
  command -v curl >/dev/null || die "curl is required"
  if [[ -z "$RELEASE" ]]; then
    RELEASE="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" |
      sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
    [[ "$RELEASE" =~ ^ephemery-[0-9]+$ ]] || die "could not determine the latest release (got '$RELEASE')"
  fi
  url="https://github.com/$REPO/releases/download/$RELEASE/testnet-all.tar.gz"
  info "downloading $url"
  curl --proto =https --tlsv1.2 --max-time 120 --max-filesize 33554432 --retry 2 -fsSL -o "$WORK/testnet-all.tar.gz" "$url" || die "download failed: $url"
  mkdir -p "$WORK/testnet"
  # Deferred (spec 028 S4b): no bound on the decompressed size of this extraction. S4b replaces the download and
  # extraction with the CLI's strict reader (S4a).
  tar --no-same-owner --no-same-permissions -xzf "$WORK/testnet-all.tar.gz" -C "$WORK/testnet"
  T="$WORK/testnet"
  SOURCE_ARGS+=(--release "$RELEASE" --source "https://github.com/$REPO/releases/tag/$RELEASE")
  if command -v sha256sum >/dev/null; then
    SOURCE_ARGS+=(--archive-sha256 "$(sha256sum "$WORK/testnet-all.tar.gz" | cut -d' ' -f1)")
  elif command -v shasum >/dev/null; then
    SOURCE_ARGS+=(--archive-sha256 "$(shasum -a 256 "$WORK/testnet-all.tar.gz" | cut -d' ' -f1)")
  fi
fi

# ---------------------------------------------------------------------------------------------------
# 2. Validate, re-encode, render and write: the fukuii rule engine
# ---------------------------------------------------------------------------------------------------
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
# The launcher initialises logging before it runs the command, and would append to the default network's node log:
# send this run's log to the scratch directory instead (and the rule engine's own messages go to stderr).
STDOUT_FILE="$WORK/fetch.stdout"
"$FUKUII" -Dlogging.logs-dir="$WORK/logs" cli ephemery-fetch --from-dir "$T" --out "$OUT" ${SOURCE_ARGS[@]+"${SOURCE_ARGS[@]}"} >"$STDOUT_FILE" || {
  code=$?
  cat "$STDOUT_FILE" >&2
  exit "$code"
}
cat "$STDOUT_FILE" >&2

# The iteration directory the engine wrote: CHAINS_DIR="<dest>/chains" in the release.env lines it printed.
CHAINS="$(sed -n 's/^CHAINS_DIR="\(.*\)"$/\1/p' "$STDOUT_FILE" | head -n 1)"
[[ -n "$CHAINS" ]] || die "fukuii printed no CHAINS_DIR"
DEST="$(dirname "$CHAINS")"
info "wrote $DEST"
info "check it: block 0 on a node started from these files must have the genesis hash in $DEST/release.env. A mismatch means peers reject it"
echo "$DEST"
