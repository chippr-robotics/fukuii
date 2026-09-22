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

# Check JSON-RPC HTTP (8545) — the last port to bind during startup, and the one
# hive itself talks to.
#
# StdNode.startNode binds 8551 first and 8545 second, and now AWAITS both
# bindings, so 8545 accepting a connection implies 8551 already does. Gating
# readiness on 8545 therefore covers both ports.
#
# This was 8551 for a while, to dodge connection-refused fast-fails in
# `ethereum/sync` caused by the Engine API binding after JSON-RPC HTTP. Binding
# 8551 first fixed that, but left the mirror-image race: 8545's bind was
# fire-and-forget, so readiness on 8551 fired before 8545 was listening and the
# harness's first eth_getBlockByNumber hit a closed port — "sync fukuii from
# go-ethereum" failing ~277ms after container start with `dial tcp :8545:
# connect: connection refused`, intermittently. Awaiting the 8545 bind is what
# makes checking it correct rather than a coin flip.
ENV HIVE_CHECK_LIVE_PORT=8545

ENTRYPOINT ["/fukuii.sh"]
