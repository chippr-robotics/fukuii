# Hive client adapter for Fukuii
# Built by the ethereum/hive test framework.
# Receives test configuration via env vars and file uploads.

FROM chipprbots/fukuii:latest

USER root

RUN apt-get update && apt-get install -y --no-install-recommends \
    jq \
    && rm -rf /var/lib/apt/lists/*

COPY fukuii.sh /fukuii.sh
COPY mapper.jq /mapper.jq
COPY enode.sh /hive-bin/enode.sh
RUN chmod +x /fukuii.sh /hive-bin/enode.sh

RUN echo "fukuii-0.1.240" > /version.txt

EXPOSE 8545 8546 8551 30303 30303/udp

# Check HTTP RPC port — always available regardless of PoW/PoS mode
ENV HIVE_CHECK_LIVE_PORT=8545

ENTRYPOINT ["/fukuii.sh"]
