#!/bin/bash
# Run Fukuii on Mordor testnet (ETC, chain ID 63)
# Ports: 8551 (HTTP), 30305 (P2P)
# Config overrides default ports for multi-client setup
# Requires: sbt assembly (builds target/scala-3.3.4/fukuii-assembly-*.jar)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAR="$SCRIPT_DIR/target/scala-3.3.4/fukuii-assembly-0.1.240.jar"
if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found at $JAR" >&2
  echo "Run 'sbt assembly' first." >&2
  exit 1
fi

exec java -Xmx4g \
  -Dconfig.file="$SCRIPT_DIR/run-mordor.conf" \
  -Dfukuii.datadir=/media/dev/2tb/data/blockchain/fukuii/mordor \
  -jar "$JAR" mordor "$@"
