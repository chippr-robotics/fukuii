# Multi-stage Dockerfile for Fukuii Ethereum Client
# Stage 1: Build stage using full JDK
FROM eclipse-temurin:17-jdk-jammy AS builder

# Install required build tools
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    curl \
    git \
    && rm -rf /var/lib/apt/lists/*

# Set up build environment
WORKDIR /build

# Copy source code
COPY . /build

# Initialize submodules and build distribution
RUN git submodule update --init --recursive && \
    ./sbt dist

# Extract the distribution zip
RUN cd target/universal && \
    DIST_FILE=$(ls fukuii-*.zip | head -1) && \
    unzip "$DIST_FILE" && \
    DIST_DIR=$(ls -d fukuii-*/ | head -1) && \
    mv "$DIST_DIR" /fukuii-dist

# Stage 2: Runtime stage using slim JRE
FROM eclipse-temurin:17-jre-jammy

LABEL org.opencontainers.image.title="Fukuii Ethereum Client"
LABEL org.opencontainers.image.description="Fukuii - A Scala-based Ethereum Classic client"
LABEL org.opencontainers.image.vendor="Chippr Robotics LLC"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# Create non-root user
RUN groupadd -r fukuii --gid=1000 && \
    useradd -r -g fukuii --uid=1000 --home-dir=/home/fukuii --shell=/bin/bash fukuii && \
    mkdir -p /home/fukuii /app/data /app/conf && \
    chown -R fukuii:fukuii /home/fukuii /app

# Copy application from builder
COPY --from=builder --chown=fukuii:fukuii /fukuii-dist /app/fukuii

# Create healthcheck script
RUN echo '#!/bin/bash\n\
# Health check script for Fukuii\n\
# Checks if the process is running and optionally tests RPC endpoint\n\
\n\
# Check if process is running\n\
if ! pgrep -f "com.chipprbots.ethereum.App" > /dev/null; then\n\
  echo "Fukuii process is not running"\n\
  exit 1\n\
fi\n\
\n\
# If curl is available and RPC is enabled, check RPC endpoint\n\
if command -v curl &> /dev/null; then\n\
  # Try to connect to default RPC port\n\
  if curl -sf -X POST -H "Content-Type: application/json" \\\n\
    --data '\''{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}'\'' \\\n\
    http://localhost:8545 > /dev/null 2>&1; then\n\
    echo "Fukuii is healthy - RPC responding"\n\
    exit 0\n\
  fi\n\
fi\n\
\n\
# If we reach here, process is running but RPC may not be enabled or curl not available\n\
echo "Fukuii process is running"\n\
exit 0\n\
' > /usr/local/bin/healthcheck.sh && \
    chmod +x /usr/local/bin/healthcheck.sh

# Install curl for healthcheck (minimal overhead)
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl procps && \
    rm -rf /var/lib/apt/lists/*

# Switch to non-root user
USER fukuii
WORKDIR /app

# Set up environment
ENV FUKUII_DATA_DIR=/app/data
ENV FUKUII_CONF_DIR=/app/conf

# Expose default ports
# 8545: HTTP RPC
# 8546: WebSocket RPC  
# 30303: P2P networking (TCP/UDP)
EXPOSE 8545 8546 30303

# Configure volumes for persistent data
VOLUME ["/app/data", "/app/conf"]

# Add healthcheck
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD ["/usr/local/bin/healthcheck.sh"]

# Set entrypoint to the fukuii binary
ENTRYPOINT ["/app/fukuii/bin/fukuii"]
CMD ["etc"]