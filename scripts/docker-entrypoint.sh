#!/usr/bin/env bash
# Docker entrypoint script for Fukuii

set -e

# Default configuration
FUKUII_DATA_DIR="${FUKUII_DATA_DIR:-/var/lib/mantis}"
FUKUII_NETWORK="${FUKUII_NETWORK:-etc}"
FUKUII_CONF_DIR="${FUKUII_CONF_DIR:-/opt/fukuii/conf}"

# Create data directory structure if it doesn't exist
mkdir -p "${FUKUII_DATA_DIR}"

# Create default config directory if needed
if [ ! -f "${FUKUII_CONF_DIR}/app.conf" ]; then
    echo "Warning: No configuration found in ${FUKUII_CONF_DIR}"
fi

# Support for environment variable overrides
# Users can set FUKUII_JAVA_OPTS to customize JVM settings
if [ -n "${FUKUII_JAVA_OPTS}" ]; then
    export JAVA_OPTS="${FUKUII_JAVA_OPTS}"
fi

# Set the data directory via system property
JAVA_OPTS="${JAVA_OPTS} -Dfukuii.datadir=${FUKUII_DATA_DIR}"

# If the first argument starts with a dash, assume it's a fukuii option
if [ "${1:0:1}" = '-' ]; then
    set -- fukuii "$@"
fi

# If the first argument is not 'fukuii', prepend it
if [ "$1" != "fukuii" ]; then
    # Assume it's a network name (etc, eth, mordor, etc.)
    FUKUII_NETWORK="$1"
    shift
    set -- fukuii "${FUKUII_NETWORK}" "$@"
fi

# Log startup information
echo "================================================"
echo "Starting Fukuii Ethereum Client"
echo "================================================"
echo "Network:     ${FUKUII_NETWORK}"
echo "Data Dir:    ${FUKUII_DATA_DIR}"
echo "Config Dir:  ${FUKUII_CONF_DIR}"
echo "Java Opts:   ${JAVA_OPTS}"
echo "================================================"

# Execute the command
exec "$@"
