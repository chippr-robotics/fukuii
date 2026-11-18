#!/usr/bin/env bash
# Print a hive client file (hive --client-file / --config) that runs fukuii with the unified
# state layout guard on (fukuii.state.path-live = true, spec 018 Track C, B-314).
#
# Usage: scripts/hive/path-live-clients.sh [CLIENTS]    (default: fukuii)
#   CLIENTS is the comma-separated list hive's --client takes, e.g. fukuii,go-ethereum.
#
# fukuii gets the build arg FUKUII_PATH_LIVE=1 (hive/fukuii/Dockerfile ARG -> ENV ->
# fukuii.sh) and the nametag `pathlive`, so hive names it fukuii_pathlive in its results and
# logs, and its image apart from the default one. Every other client is listed unchanged.
# hive passes build args to a client ONLY through such a file, so pass it with
# `--client-file FILE` and WITHOUT --client (with --client, hive filters the file's entries).
set -euo pipefail

CLIENTS=${1:-fukuii}
found=0
IFS=',' read -r -a names <<< "$CLIENTS"
for name in "${names[@]}"; do
  name=${name//[[:space:]]/}
  [ -n "$name" ] || continue
  if ! printf '%s' "$name" | grep -Eq '^[A-Za-z0-9._-]+$'; then
    echo "path-live-clients.sh: bad client name '$name'" >&2
    exit 2
  fi
  if [ "$name" = fukuii ]; then
    found=1
    printf -- '- client: fukuii\n  nametag: pathlive\n  build_args:\n    FUKUII_PATH_LIVE: "1"\n'
  else
    printf -- '- client: %s\n' "$name"
  fi
done
if [ "$found" -ne 1 ]; then
  echo "path-live-clients.sh: '$CLIENTS' does not include fukuii" >&2
  exit 2
fi
