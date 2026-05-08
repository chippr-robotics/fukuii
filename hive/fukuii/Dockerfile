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

# /version.txt is created by the base image (chipprbots/fukuii:latest) from
# the actual JAR filename, so a version bump propagates automatically.

EXPOSE 8545 8546 8551 30303 30303/udp

# Check Engine API authrpc port (8551) — last port to bind during startup.
# Hive uses this for its TCP-readiness check; once 8551 accepts connections, the
# sync simulator can POST engine_newPayload immediately without race. Using 8545
# (JSON-RPC HTTP) caused 0.7–2s connection-refused fast-fails in `ethereum/sync`
# tests 4/7/8/16 because fukuii's Engine API server binds AFTER JSON-RPC HTTP.
ENV HIVE_CHECK_LIVE_PORT=8551

ENTRYPOINT ["/fukuii.sh"]
