# syntax=docker/dockerfile:1.4
# Multi-stage Dockerfile for Fukuii Ethereum Client
# Stage 1: Build stage using full JDK
FROM eclipse-temurin:25.0.5.1_2-jdk-noble AS builder

# Install required build tools including SBT
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        git \
        gnupg2 \
        ca-certificates \
        unzip \
    && curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --dearmor -o /usr/share/keyrings/sbt-archive-keyring.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/sbt-archive-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list \
    && apt-get update \
    && apt-get install -y sbt

# Set up build environment
WORKDIR /build

# Create SBT cache directories with proper permissions
# Also set HOME explicitly to ensure SBT uses the correct paths
RUN mkdir -p /root/.cache/sbt /root/.ivy2 /root/.sbt && \
    chmod -R 755 /root/.cache /root/.ivy2 /root/.sbt
ENV HOME=/root
ENV SBT_OPTS="-Dsbt.global.base=/root/.sbt -Dsbt.ivy.home=/root/.ivy2 -Dsbt.boot.directory=/root/.sbt/boot"

# Copy dependency files first for better caching
COPY build.sbt version.sbt .jvmopts ./
COPY project/ ./project/

# Pre-download dependencies (cached layer)
RUN --mount=type=cache,target=/root/.ivy2 \
    --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.cache \
    sbt update

# Copy source code
COPY . /build

# Build distribution AND fat assembly JAR in a single sbt invocation so they
# share the compile cache and JVM startup. The dist gives us the launcher
# script + dependency JARs; the assembly JAR is what compose files and the
# hive adapter run via `java -jar`.
# Note: Submodules are already initialized by the GitHub Actions checkout step
# or should be initialized manually before building (git submodule update --init --recursive)
RUN --mount=type=cache,target=/root/.ivy2 \
    --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.cache \
    sbt "assembly; dist"

# Extract the distribution zip and drop the assembly JAR alongside the dist
# libs so the runtime stage has both layouts available under /fukuii-dist.
RUN cd target/universal && \
    DIST_FILE=$(ls fukuii-*.zip | head -1) && \
    unzip "$DIST_FILE" && \
    DIST_DIR=$(ls -d fukuii-*/ | head -1) && \
    mv "$DIST_DIR" /fukuii-dist && \
    cp /build/target/scala-*/fukuii-assembly-*.jar /fukuii-dist/lib/

# Stage 2: Runtime stage using slim JRE
FROM eclipse-temurin:25.0.5.1_2-jre-noble

# OCI labels for container metadata
LABEL org.opencontainers.image.title="Fukuii Ethereum Client"
LABEL org.opencontainers.image.description="Fukuii - A Scala-based Ethereum Classic client"
LABEL org.opencontainers.image.vendor="Chippr Robotics LLC"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.source="https://github.com/chippr-robotics/fukuii"
LABEL org.opencontainers.image.documentation="https://github.com/chippr-robotics/fukuii/blob/main/docs/deployment/docker.md"

# Create non-root user without hard-coding uid/gid (base image may already use 1000)
RUN groupadd -r fukuii && \
    useradd -r -g fukuii --home-dir=/home/fukuii --shell=/bin/bash fukuii && \
    mkdir -p /home/fukuii /app/data /app/conf && \
    chown -R fukuii:fukuii /home/fukuii /app

# Copy application from builder
COPY --from=builder --chown=fukuii:fukuii /fukuii-dist /app/fukuii

# Copy default configuration files to /app/conf for easy user override
# Users can mount their own config files at /app/conf to override defaults
COPY --from=builder --chown=fukuii:fukuii /fukuii-dist/conf /app/conf

# Stable, version-agnostic symlink for the assembly JAR. Compose files,
# hive adapters, and any other consumer should reference this path —
# never the versioned filename — so a version bump doesn't require a
# coordinated edit across every downstream config.
RUN cd /app/fukuii/lib && \
    ln -sf "$(ls fukuii-assembly-*.jar | head -1)" fukuii-assembly.jar && \
    basename "$(readlink -f fukuii-assembly.jar)" \
      | sed -E 's/^fukuii-assembly-(.*)\.jar$/fukuii-\1/' > /version.txt

# Healthcheck: the process, then JSON-RPC at FUKUII_HEALTH_URL, https and a client certificate included
# (docker/scripts/healthcheck.sh; spec 030 S11, B-149)
COPY --chmod=755 docker/scripts/healthcheck.sh /usr/local/bin/healthcheck.sh

# Install curl for healthcheck (minimal overhead)
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && \
    apt-get install -y --no-install-recommends curl procps

# Switch to non-root user
USER fukuii
WORKDIR /app

# Set up environment
# Read by conf/base/fukuii.conf (datadir). A datadir set in a mounted conf still wins.
# JSON-RPC stays on the container's loopback: a plaintext listener on 0.0.0.0 refuses the start unless it
# has tls.enabled or allow-insecure-bind (spec 030, docs/runbooks/tls-operations.md). To publish RPC, set
# FUKUII_RPC_INTERFACE=0.0.0.0 (read by network.conf) together with one of those in the conf.
ENV FUKUII_DATADIR=/app/data
# Not read by fukuii; kept for scripts that may reference it.
ENV FUKUII_DATA_DIR=/app/data
ENV FUKUII_CONF_DIR=/app/conf
# JDK-8385993: C2 ReduceAllocationMerges can silently miscompile field reads on
# JDK 23+ (fixed in JDK 28). JDK_JAVA_OPTIONS is read by every `java` launch in
# this container, including composes that override the entrypoint, and leaves
# JAVA_TOOL_OPTIONS free for operators. Side effect: java prints
# "NOTE: Picked up JDK_JAVA_OPTIONS: ..." on stderr at startup.
# CVE-2026-114938: the x86-64 Base64 decode intrinsic can be driven to memory
# corruption by crafted input. The pinned base image carries the fix; disabling
# the intrinsic also covers a host or image still on an affected JDK. Remove once
# no supported JDK predates 25.0.5.1 / 21.0.13.1.
# B-125: --sun-misc-unsafe-memory-access=allow stops JDK 24+ printing its
# sun.misc.Unsafe warning (Scala 3 lazy vals) over the TUI's first frame. This
# image's JDK is 25; JDK 22 and older would refuse the flag.
ENV JDK_JAVA_OPTIONS="-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges -XX:DisableIntrinsic=_base64_decodeBlock --sun-misc-unsafe-memory-access=allow"

# Expose default ports (conf/base/network.conf)
# 8546: JSON-RPC HTTP (also /health, /readiness, /buildinfo)
# 8552: JSON-RPC WebSocket (off unless network.rpc.ws.enabled = true)
# 30303: P2P (TCP) and discovery (UDP)
EXPOSE 8546 8552 30303/tcp 30303/udp

# Configure volumes for persistent data
VOLUME ["/app/data", "/app/conf"]

# Add healthcheck
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD ["/usr/local/bin/healthcheck.sh"]

# Set entrypoint to the fukuii binary
# Uses network-specific config file (etc.conf for Ethereum Classic mainnet)
ENTRYPOINT ["/app/fukuii/bin/fukuii"]
CMD ["-Dconfig.file=/app/conf/etc.conf", "-Dlogback.configurationFile=/app/conf/logback.xml"]