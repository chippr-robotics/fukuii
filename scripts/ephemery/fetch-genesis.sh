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
# This script is a wrapper: it fills in the default --out and runs `fukuii cli ephemery-fetch`, which downloads
# the release over HTTPS (https only, a host allow-list, size caps), unpacks it with a strict reader, and applies
# every rule: what fukuii can represent, the genesis re-encoding, the chain config, and the genesis-hash check
# against the published hash (spec 028). The node applies the same rules at startup. You do not need this script
# to run `fukuii ephemery`: the node fetches the next iteration itself after a reset. Use it to pin or prepare an
# iteration by hand.
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
#   --release TAG     a specific release (default: the iteration whose window contains now)
#   --out DIR         output root (default: $FUKUII_EPHEMERY_DIR or $HOME/.fukuii/ephemery-network)
#   --from-dir DIR    skip the download; generate from an already-extracted testnet-all directory
#                     (offline use; also how the test inputs under src/test/resources were produced)
#
# Environment:
#   FUKUII_BIN        the fukuii launcher to run (default: `fukuii` on PATH). From a checkout:
#                     FUKUII_BIN=target/universal/stage/bin/fukuii after `sbt stage`.
#
# Fails loudly (non-zero exit, nothing written for that iteration) when fukuii refuses the release; the
# message says why. Exit codes: 2 usage or no fukuii launcher; 78 the release is refused; 69 the release is not
# published yet or the source is not reachable; 1 other errors.
#
# Settings the command reads: fukuii.ephemery.source (an https mirror, or a directory) and
# fukuii.ephemery.refresh-timeout, from conf/ephemery.conf, FUKUII_CONF_DIR or -D options in JAVA_OPTS. Proxies:
# https.proxyHost / https.proxyPort, or HTTPS_PROXY and NO_PROXY.
#
# Requires: bash >= 4 and the fukuii launcher.

set -euo pipefail

if [[ -z "${BASH_VERSINFO:-}" || "${BASH_VERSINFO[0]}" -lt 4 ]]; then
  echo "fetch-genesis: ERROR: bash >= 4 is required (running ${BASH_VERSION:-not bash})" >&2
  exit 1
fi

die() { echo "fetch-genesis: ERROR: $*" >&2; exit 1; }
usage_error() { echo "fetch-genesis: ERROR: $*" >&2; exit 2; }

# Only --out is looked at here (to default it, and to place the command's log); every flag goes to the command as given.
OUT=""
HAVE_OUT=0
prev=""
for arg in "$@"; do
  case "$arg" in
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    --out=*) OUT="${arg#--out=}"; HAVE_OUT=1 ;;
  esac
  if [[ "$prev" == "--out" ]]; then OUT="$arg"; HAVE_OUT=1; fi
  prev="$arg"
done
if [[ "$HAVE_OUT" == 0 ]]; then
  OUT="${FUKUII_EPHEMERY_DIR:-$HOME/.fukuii/ephemery-network}"
  set -- "$@" --out "$OUT"
fi

FUKUII="${FUKUII_BIN:-fukuii}"
if [[ "$FUKUII" == */* ]]; then fukuii_ok=0; [[ -x "$FUKUII" && -f "$FUKUII" ]] && fukuii_ok=1
else fukuii_ok=0; command -v "$FUKUII" >/dev/null 2>&1 && fukuii_ok=1; fi
if [[ "$fukuii_ok" != 1 ]]; then
  usage_error "no fukuii launcher: '$FUKUII' is not on PATH or executable. Set FUKUII_BIN to it (after 'sbt stage':
  FUKUII_BIN=target/universal/stage/bin/fukuii), or from a checkout run:
  sbt 'runMain com.chipprbots.ethereum.App cli ephemery-fetch --release ephemery-N --out DIR'"
fi

# The launcher initialises logging before it runs the command, and would append to the default network's node log:
# this run's log goes under the output directory instead. (`exec` ends this shell, so there is no scratch directory to
# clean up.) The command's own messages go to stderr; the release.env lines, to stdout.
exec "$FUKUII" -Dlogging.logs-dir="$OUT/logs" cli ephemery-fetch "$@"
