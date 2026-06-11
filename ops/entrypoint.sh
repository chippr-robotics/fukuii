#!/usr/bin/env bash
# ops/entrypoint.sh — Docker entrypoint for fukuii with optional hardware-adaptive heap sizing.
#
# MEMORY MODEL (important — read before changing):
#   fukuii uses RocksDB, whose block cache lives in native memory OUTSIDE the JVM heap and
#   OUTSIDE -XX:MaxDirectMemorySize. The container's mem_limit must cover:
#     1. JVM heap (-Xmx)
#     2. RocksDB native block cache (~2-4g at ETC mainnet steady state)
#     3. Netty direct buffers (-XX:MaxDirectMemorySize=512M)
#     4. OS overhead (~0.5g)
#   The safe split is ~50% of mem_limit for heap, ~50% for RocksDB native + OS.
#   -XX:MaxRAMPercentage=75 is WRONG for fukuii: it would give 7.5g heap on a 10g container,
#   leaving only 2.5g for RocksDB native + OS → cgroup OOM kill (Exited 137).
#
# HEAP SELECTION PRIORITY:
#   1. FUKUII_HEAP env var  — explicit override, highest priority
#   2. FUKUII_AUTO_HEAP=true — auto-detect from cgroup memory limit (three-bound formula)
#   3. Hardcoded default     — 5g for ETC mainnet, safe and empirically validated
#
# USAGE:
#   # Standard (explicit heap from docker-compose command:):
#   ENTRYPOINT ["ops/entrypoint.sh"]
#
#   # Auto-heap (reads cgroup limit, applies three-bound formula):
#   environment:
#     FUKUII_AUTO_HEAP: "true"
#   # Set mem_limit appropriately — that IS the budget.
#
#   # Explicit override:
#   environment:
#     FUKUII_HEAP: "4g"

set -euo pipefail

NETWORK="${FUKUII_NETWORK:-etc}"

# Network-appropriate heap bounds (MB). Derived from Blockscout chain activity data (June 2026).
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

# Determine heap from priority order
if [ -n "${FUKUII_HEAP:-}" ]; then
  HEAP_ARG="-Xmx${FUKUII_HEAP}"
  INIT_ARG="-Xms${FUKUII_HEAP_INIT:-$(echo "${FUKUII_HEAP}" | awk '{v=$1; if(sub(/g$/,"",v)) print int(v/2)"g"; else if(sub(/m$/,"",v)) print int(v/2)"m"; else print "1g"}')}"
  echo "[entrypoint] Heap: explicit FUKUII_HEAP=${FUKUII_HEAP}" >&2

elif [ "${FUKUII_AUTO_HEAP:-false}" = "true" ]; then
  # Read cgroup memory limit — try cgroup v2 first, fall back to v1
  CGROUP_LIMIT_BYTES=""
  if [ -f /sys/fs/cgroup/memory.max ]; then
    RAW=$(cat /sys/fs/cgroup/memory.max)
    if [ "$RAW" != "max" ]; then
      CGROUP_LIMIT_BYTES="$RAW"
    fi
  fi
  if [ -z "$CGROUP_LIMIT_BYTES" ] && [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
    RAW=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)
    # cgroup v1 uses a very large sentinel when unlimited (~9 EiB)
    if [ "$RAW" -lt 9000000000000 ]; then
      CGROUP_LIMIT_BYTES="$RAW"
    fi
  fi

  if [ -z "$CGROUP_LIMIT_BYTES" ]; then
    echo "[entrypoint] WARNING: FUKUII_AUTO_HEAP=true but no cgroup memory limit found — using default 5g" >&2
    HEAP_MB=5120
  else
    CGROUP_MB=$(( CGROUP_LIMIT_BYTES / 1024 / 1024 ))
    TARGET_MB=$(( CGROUP_MB / 2 ))  # 50% rule: leave half for RocksDB native + OS

    if [ "$CGROUP_MB" -lt "$FLOOR_MB" ]; then
      echo "[entrypoint] WARNING: Container limit ${CGROUP_MB}MB is below ${NETWORK} minimum ${FLOOR_MB}MB. Node may be unstable." >&2
      HEAP_MB=$FLOOR_MB
    elif [ "$TARGET_MB" -lt "$FLOOR_MB" ]; then
      echo "[entrypoint] INFO: 50% rule gives ${TARGET_MB}MB — below ${NETWORK} floor ${FLOOR_MB}MB. Using floor; RocksDB native memory may be constrained." >&2
      HEAP_MB=$FLOOR_MB
    elif [ "$TARGET_MB" -gt "$CEIL_MB" ]; then
      HEAP_MB=$CEIL_MB
    else
      HEAP_MB=$TARGET_MB
    fi

    echo "[entrypoint] Auto-heap: ${HEAP_MB}MB (cgroup=${CGROUP_MB}MB, floor=${FLOOR_MB}MB, ceil=${CEIL_MB}MB, network=${NETWORK})" >&2
  fi

  INIT_MB=$(( HEAP_MB / 2 ))
  HEAP_ARG="-Xmx${HEAP_MB}m"
  INIT_ARG="-Xms${INIT_MB}m"

else
  # Hardcoded safe defaults (empirically validated on ETC mainnet)
  case "$NETWORK" in
    mordor)   HEAP_ARG="-Xmx2g";  INIT_ARG="-Xms512m" ;;
    sepolia)  HEAP_ARG="-Xmx4g";  INIT_ARG="-Xms1g" ;;
    eth)      HEAP_ARG="-Xmx6g";  INIT_ARG="-Xms2g" ;;
    etc|*)    HEAP_ARG="-Xmx5g";  INIT_ARG="-Xms2g" ;;
  esac
  echo "[entrypoint] Heap: hardcoded default for network=${NETWORK} (${HEAP_ARG})" >&2
fi

exec java \
  "$HEAP_ARG" \
  "$INIT_ARG" \
  -Xss1M \
  -XX:MaxDirectMemorySize=512M \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/data/logs/heapdump.hprof \
  -XX:+ExitOnOutOfMemoryError \
  "$@"
