#!/usr/bin/env bash
# ops/start.sh — Resource-aware fukuii launcher for bare-metal (non-Docker) use.
#
# USAGE:
#   ./ops/start.sh <network> [config-file] [extra-java-args...]
#
#   <network>     etc | mordor | sepolia | eth  (default: etc)
#   [config-file] Path to fukuii conf file (default: src/main/resources/conf/base/fukuii.conf)
#
# EXAMPLES:
#   ./ops/start.sh etc ops/barad-dur/fukuii-conf-1/etc.conf
#   ./ops/start.sh mordor ops/barad-dur/fukuii-conf-2/mordor.conf
#   FUKUII_HEAP=4g ./ops/start.sh etc  # explicit heap override
#
# HEAP SELECTION (same three-bound formula as ops/entrypoint.sh):
#   heap = max(FLOOR, min(50% of available RAM, CEILING))
#   "available" = total RAM minus OS_RESERVE_MB (default 2048 MB)
#   Override: set FUKUII_HEAP env var to bypass formula entirely.
#
# MEMORY MODEL: see ops/entrypoint.sh header comment for the RocksDB rationale.

set -euo pipefail

NETWORK="${1:-etc}"
CONFIG_FILE="${2:-src/main/resources/conf/base/fukuii.conf}"
JAR="${FUKUII_JAR:-target/scala-3.3.7/fukuii-assembly-0.6.18.jar}"

if [ ! -f "$JAR" ]; then
  echo "[start.sh] ERROR: JAR not found at ${JAR}. Run 'sbt assembly' first or set FUKUII_JAR." >&2
  exit 1
fi

# Network-appropriate heap bounds (MB). Source: Blockscout API, June 2026.
case "$NETWORK" in
  mordor)
    FLOOR_MB=1536; CEIL_MB=2048    # 5.2M txns, 0.09% utilization — tiny testnet
    ;;
  sepolia)
    FLOOR_MB=4096; CEIL_MB=6144    # 1B txns, 50% utilization — heavier than expected
    ;;
  eth|mainnet)
    FLOOR_MB=6144; CEIL_MB=10240   # 3.5B txns, 50% utilization, DeFi-heavy — estimate
    ;;
  etc|*)
    FLOOR_MB=3072; CEIL_MB=6144    # 143M txns, 0.53% utilization — empirically validated
    ;;
esac

if [ -n "${FUKUII_HEAP:-}" ]; then
  HEAP_ARG="-Xmx${FUKUII_HEAP}"
  INIT_ARG="-Xms${FUKUII_HEAP_INIT:-1g}"
  echo "[start.sh] Heap: explicit FUKUII_HEAP=${FUKUII_HEAP}" >&2
else
  # Read total RAM from /proc/meminfo (Linux only)
  if [ ! -f /proc/meminfo ]; then
    echo "[start.sh] WARNING: /proc/meminfo not found (non-Linux?). Using default 5g heap." >&2
    HEAP_MB=5120
  else
    TOTAL_RAM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
    OS_RESERVE_MB=2048  # reserve for OS, VS Code, other processes
    AVAILABLE_MB=$(( TOTAL_RAM_MB - OS_RESERVE_MB ))
    TARGET_MB=$(( AVAILABLE_MB / 2 ))

    if [ "$AVAILABLE_MB" -lt "$FLOOR_MB" ]; then
      echo "[start.sh] WARNING: ${AVAILABLE_MB}MB available (${TOTAL_RAM_MB}MB total - ${OS_RESERVE_MB}MB reserve) is below ${NETWORK} minimum ${FLOOR_MB}MB. Node may be unstable." >&2
      HEAP_MB=$FLOOR_MB
    elif [ "$TARGET_MB" -lt "$FLOOR_MB" ]; then
      echo "[start.sh] INFO: 50% rule gives ${TARGET_MB}MB — below ${NETWORK} floor ${FLOOR_MB}MB. Using floor; RocksDB native memory may be constrained." >&2
      HEAP_MB=$FLOOR_MB
    elif [ "$TARGET_MB" -gt "$CEIL_MB" ]; then
      HEAP_MB=$CEIL_MB
    else
      HEAP_MB=$TARGET_MB
    fi

    echo "[start.sh] network=${NETWORK}, heap=${HEAP_MB}MB (total=${TOTAL_RAM_MB}MB, available=${AVAILABLE_MB}MB, floor=${FLOOR_MB}MB, ceil=${CEIL_MB}MB)" >&2
  fi

  INIT_MB=$(( HEAP_MB / 2 ))
  HEAP_ARG="-Xmx${HEAP_MB}m"
  INIT_ARG="-Xms${INIT_MB}m"
fi

exec java \
  "$HEAP_ARG" \
  "$INIT_ARG" \
  -Xss1M \
  -XX:MaxDirectMemorySize=512M \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/fukuii-heapdump.hprof \
  -XX:+ExitOnOutOfMemoryError \
  -Dconfig.file="$CONFIG_FILE" \
  -jar "$JAR" \
  "$NETWORK"
