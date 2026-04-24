#!/bin/bash
# Olympia treasury address — single source of truth for test scripts
# Set at Olympia activation — production address TBD
# Override via: TREASURY_ADDRESS=0x... ./test-ecip1112-treasury-address.sh
OLYMPIA_TREASURY_ADDRESS="${TREASURY_ADDRESS:-}"
