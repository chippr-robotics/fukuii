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
# NOT what hive probes. libhive takes the readiness port from the simulator's
# per-client parameters (internal/libhive/api.go), defaulting to 8545; this
# image ENV is never consulted for that. The `ethereum/sync` sink node is probed
# on 8551 (sync.go:97), everything else on 8545. fukuii.sh passes the value hive
# actually set through as fukuii.network.readiness-port, and StdNode binds that
# port last so a successful probe implies both RPC ports are listening. This
# default only supplies the fallback when a simulator sets nothing.
ENV HIVE_CHECK_LIVE_PORT=8545

ENTRYPOINT ["/fukuii.sh"]
