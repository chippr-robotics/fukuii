#!/bin/bash
# Run Fukuii on ETC mainnet (chain ID 61)
# Ports: 8551 (HTTP), 30305 (P2P)
# Config overrides default ports for multi-client setup
# Requires: sbt assembly (builds target/scala-3.3.7/fukuii-assembly-*.jar)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAR="$SCRIPT_DIR/target/scala-3.3.7/fukuii-assembly-0.1.240.jar"
if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found at $JAR" >&2
  echo "Run 'sbt assembly' first." >&2
  exit 1
fi

# DO NOT pass a network name as a positional arg when using -Dconfig.file.
# setNetworkConfig() clears config.file when a network arg is present, discarding
# all run-classic.conf overrides. Network is set inside run-classic.conf.
# run-classic.conf must use: include classpath("application.conf")
LOGDIR="$(grep -m1 'datadir' "$SCRIPT_DIR/run-classic.conf" | grep -oP '"/[^"]+"' | tr -d '"')/logs"
mkdir -p "$LOGDIR"

exec java -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:InitiatingHeapOccupancyPercent=40 \
  "-Xlog:gc*:file=$LOGDIR/gc.log:time,uptime:filecount=5,filesize=20m" \
  -Dconfig.file="$SCRIPT_DIR/run-classic.conf" \
  -jar "$JAR" "$@" \
  >> "$LOGDIR/stdout.log" 2>&1
