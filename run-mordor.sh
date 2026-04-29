#!/bin/bash
# Run Fukuii on Mordor testnet (ETC, chain ID 63)
# Ports: 8554 (HTTP), 30306 (P2P/Discovery)
# Config overrides default ports for multi-client setup
# Requires: sbt assembly (builds target/scala-3.3.7/fukuii-assembly-*.jar)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAR="$SCRIPT_DIR/target/scala-3.3.7/fukuii-assembly-0.2.7.jar"
if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found at $JAR" >&2
  echo "Run 'sbt assembly' first." >&2
  exit 1
fi

# DO NOT pass a network name as a positional arg when using -Dconfig.file.
# setNetworkConfig() clears config.file when a network arg is present, discarding
# all run-mordor.conf overrides. Network is set inside run-mordor.conf.
# run-mordor.conf MUST use: include classpath("application.conf")
LOGDIR="$(grep -m1 'datadir' "$SCRIPT_DIR/run-mordor.conf" | grep -oP '"/[^"]+"' | tr -d '"')/logs"
mkdir -p "$LOGDIR"

exec java -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:InitiatingHeapOccupancyPercent=40 \
  "-Xlog:gc*:file=$LOGDIR/gc.log:time,uptime:filecount=5,filesize=20m" \
  -Dconfig.file="$SCRIPT_DIR/run-mordor.conf" \
  -jar "$JAR" "$@" \
  >> "$LOGDIR/stdout.log" 2>&1
