# Hive client adapter for Fukuii
# Used by the ethereum/hive test framework to run Fukuii as a test client
#
# This image is built by hive and receives test configuration via:
# - Environment variables (HIVE_FORK_*, HIVE_CHAIN_ID, etc.)
# - File uploads (/genesis.json, /chain.rlp, /blocks/*.rlp)

FROM chipprbots/fukuii:latest

USER root

# Install dependencies for the entry point scripts
RUN apt-get update && apt-get install -y --no-install-recommends \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Copy adapter scripts
COPY fukuii.sh /fukuii.sh
COPY mapper.jq /mapper.jq
COPY import-chain.sh /import-chain.sh
COPY enode.sh /hive-bin/enode.sh
RUN chmod +x /fukuii.sh /hive-bin/enode.sh /import-chain.sh

# Write version info
RUN echo "fukuii-0.1.240" > /version.txt

# Hive expects JSON-RPC on 8545 and Engine API on 8551
EXPOSE 8545 8546 8551 30303 30303/udp

# Hive checks TCP 8551 for readiness (Engine API port)
# This ensures Engine API is ready before tests start
ENV HIVE_CHECK_LIVE_PORT=8551

ENTRYPOINT ["/fukuii.sh"]
