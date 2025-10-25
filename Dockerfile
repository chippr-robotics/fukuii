# Multi-stage Dockerfile for Fukuii Ethereum Client
# Stage 1: Builder - Build the Fukuii distribution
FROM eclipse-temurin:11-jdk-jammy AS builder

# Install build dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    curl \
    git \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Install sbt
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy source code
COPY . .

# Update git submodules
RUN git submodule update --init --recursive

# Build the distribution (skip tests for faster build)
RUN sbt 'set test in Test := {}' dist

# Extract the distribution
RUN mkdir -p /fukuii-dist && \
    unzip -d /fukuii-dist target/universal/fukuii-*.zip && \
    mv /fukuii-dist/fukuii-*/* /fukuii-dist/ && \
    rmdir /fukuii-dist/fukuii-* || true

# Stage 2: Runtime - Minimal runtime image
FROM eclipse-temurin:11-jre-jammy

LABEL maintainer="Chippr Robotics LLC"
LABEL description="Fukuii Ethereum Classic Client"
LABEL org.opencontainers.image.source="https://github.com/chippr-robotics/chordodes_fukuii"
LABEL org.opencontainers.image.documentation="https://github.com/chippr-robotics/chordodes_fukuii/blob/main/README.md"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# Install runtime dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r fukuii && \
    useradd -r -g fukuii -m -d /home/fukuii -s /bin/bash fukuii

# Create data directory
RUN mkdir -p /var/lib/mantis && \
    chown -R fukuii:fukuii /var/lib/mantis

# Copy application from builder
COPY --from=builder --chown=fukuii:fukuii /fukuii-dist /opt/fukuii

# Copy healthcheck script
COPY --chown=fukuii:fukuii scripts/healthcheck.sh /usr/local/bin/healthcheck.sh
RUN chmod +x /usr/local/bin/healthcheck.sh

# Copy entrypoint script
COPY --chown=fukuii:fukuii scripts/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# Set up environment
ENV FUKUII_HOME=/opt/fukuii
ENV FUKUII_DATA_DIR=/var/lib/mantis
ENV PATH="${FUKUII_HOME}/bin:${PATH}"
ENV JAVA_OPTS="-Xmx2g"

# Switch to non-root user
USER fukuii
WORKDIR /home/fukuii

# Expose ports
# 9076: Ethereum protocol connections
# 8546: JSON-RPC over WebSocket
# 8545: JSON-RPC over HTTP
EXPOSE 9076 8546 8545

# Add healthcheck
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD /usr/local/bin/healthcheck.sh

# Volume for blockchain data
VOLUME ["/var/lib/mantis", "/opt/fukuii/conf"]

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["etc"]
