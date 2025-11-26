# INF-005: Docker Deployment Strategy and Container Best Practices

**Status:** Accepted  
**Date:** 2025-11-26  
**Authors:** Copilot, realcodywburns

## Context

Fukuii's Docker infrastructure has evolved organically over time, resulting in:
- Outdated Docker and docker-compose syntax
- Inconsistent image versioning across compose files
- Deprecated practices (apt-key, version field in compose files)
- Lack of build optimization (no caching, poor layer structure)
- Missing specialized deployment configurations (bootnode)
- Unnecessary complexity (mordor-miner image when bootnode is more valuable)

Modern Docker and container orchestration have established best practices that improve:
- Build performance (BuildKit caching)
- Security (signed packages, non-root users, minimal attack surface)
- Maintainability (consistent patterns, clear documentation)
- CI/CD efficiency (layer caching, multi-stage builds)

## Decision

We adopt the following Docker deployment strategy:

### 1. Docker Syntax and Build Optimization

**BuildKit Syntax Directive:**
```dockerfile
# syntax=docker/dockerfile:1.4
```
- Enables BuildKit features (cache mounts, improved layer handling)
- Future-proof syntax compatibility
- Better error messages during builds

**Build Caching Strategy:**
```dockerfile
# Cache apt packages
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install ...

# Cache sbt dependencies
RUN --mount=type=cache,target=/root/.ivy2 \
    --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.cache \
    sbt update
```

Benefits:
- 10-50x faster rebuild times
- Reduced CI/CD costs
- Better developer experience

**Layer Optimization:**
```dockerfile
# Copy dependency files first (changes infrequently)
COPY build.sbt version.sbt .jvmopts ./
COPY project/ ./project/

# Pre-download dependencies (cached layer)
RUN sbt update

# Copy source code (changes frequently)
COPY . /build

# Build distribution
RUN sbt dist
```

This ensures dependency downloads are cached separately from source changes.

### 2. Package Repository Configuration

**Modern GPG Key Handling:**
```dockerfile
# Old (deprecated):
curl -sL "..." | apt-key add

# New (secure):
curl -sL "..." | gpg --dearmor -o /usr/share/keyrings/sbt-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/sbt-archive-keyring.gpg] ..." | tee /etc/apt/sources.list.d/sbt.list
```

Benefits:
- Follows Debian/Ubuntu security best practices
- Eliminates deprecation warnings
- Better key isolation and management

### 3. OCI Image Metadata

All images include comprehensive OCI labels:
```dockerfile
LABEL org.opencontainers.image.title="Fukuii Ethereum Client"
LABEL org.opencontainers.image.description="Fukuii - A Scala-based Ethereum Classic client"
LABEL org.opencontainers.image.vendor="Chippr Robotics LLC"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.source="https://github.com/chippr-robotics/fukuii"
LABEL org.opencontainers.image.documentation="https://github.com/chippr-robotics/fukuii/blob/main/docs/deployment/docker.md"
```

Benefits:
- Better container registry integration
- Automatic documentation links
- License compliance tracking
- Source traceability

### 4. Docker Compose Modernization

**Remove Deprecated Features:**
- Remove `version` field (deprecated in Compose Spec)
- Remove `links` directive (use service names)
- Replace `restart: always` with `restart: unless-stopped`

**Add Modern Features:**
```yaml
services:
  service-name:
    container_name: explicit-name
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "health-check-command"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 4G
```

**Updated Component Versions:**
- Prometheus: v2.23.0 → v2.48.0
- Grafana: 7.3.6 → 10.2.2
- Push Gateway: v1.4.0 → v1.7.0

### 5. Specialized Deployment Configurations

**Bootnode Image (`Dockerfile.bootnode`):**
- Optimized for peer discovery and connection brokering
- Uses `bootnode.conf` for maximum peer capacity (500+ peers)
- Minimal disk usage (in-memory state, small persistent peer list)
- No RPC endpoints (reduced attack surface)
- Exposes only P2P ports (30303/udp, 9076/tcp)

Configuration highlights from `bootnode.conf`:
- High peer limits: 500 outgoing, 200 incoming
- Aggressive discovery: 30s scan interval, larger Kademlia buckets
- In-memory pruning: minimal disk footprint
- No blockchain sync: focuses on peer discovery only

**Removal of Mordor Miner Image:**
- Mining on testnets is less valuable than peer discovery
- Bootnode provides more network utility
- Reduces maintenance burden (fewer images to update)
- Users who need mining can enable it via configuration

### 6. Health Check Strategy

**Process-based for standard nodes:**
```bash
# Check process running
pgrep -f "com.chipprbots.ethereum.App"

# Verify RPC endpoint (if enabled)
curl -f http://localhost:8545
```

**Simplified for bootnodes:**
```bash
# Only check process (no RPC on bootnodes)
pgrep -f "com.chipprbots.ethereum.App"
```

**For distroless images:**
- Use external health monitoring (Kubernetes probes)
- HTTP-based checks when possible

## Consequences

### Positive

1. **Build Performance:**
   - BuildKit cache mounts reduce build time by 10-50x
   - Layer optimization reduces rebuild frequency
   - CI/CD pipelines complete faster

2. **Security:**
   - Modern GPG key handling follows best practices
   - OCI labels improve supply chain transparency
   - Health checks detect failures faster
   - Bootnode reduces attack surface (no RPC)

3. **Maintainability:**
   - Consistent patterns across all Dockerfiles
   - Modern compose syntax aligns with industry standards
   - Better documentation through OCI labels
   - Specialized images serve clear purposes

4. **Network Health:**
   - Bootnode optimizes for peer discovery
   - High peer capacity improves network connectivity
   - Dedicated configuration ensures reliable operation

### Negative

1. **Migration Required:**
   - Existing compose files need updates
   - CI/CD pipelines may need adjustments
   - Users of mordor-miner must switch to bootnode or configure mining manually

2. **BuildKit Requirement:**
   - Requires Docker 18.09+ with BuildKit enabled
   - May need `DOCKER_BUILDKIT=1` environment variable
   - Some older CI systems may need updates

3. **Learning Curve:**
   - New syntax for cache mounts
   - Different approach to compose files (no version field)
   - Understanding bootnode vs regular node differences

### Neutral

1. **Documentation Updates:**
   - All Docker docs need revision
   - ADR provides clear migration path
   - Examples updated to show new patterns

2. **Image Reorganization:**
   - New bootnode image replaces mordor-miner
   - Clearer separation of concerns
   - Better naming conventions

## Implementation Notes

### Building with BuildKit

Enable BuildKit before building:
```bash
export DOCKER_BUILDKIT=1
docker build -f docker/Dockerfile -t fukuii:latest .
```

Or use the new syntax automatically with Docker 23.0+.

### Migrating Compose Files

Before:
```yaml
version: '3.8'
services:
  app:
    restart: always
    links:
      - db
```

After:
```yaml
# version field removed
services:
  app:
    restart: unless-stopped
    depends_on:
      - db
    healthcheck:
      test: ["CMD", "health-command"]
```

### Bootnode Deployment

Replace mordor-miner usage with bootnode:
```bash
# Old approach (mining)
docker run chipprbots/fukuii-mordor-miner:latest

# New approach (bootnode for network health)
docker run chipprbots/fukuii-bootnode:latest
```

For users who still need mining, use standard images with mining configuration:
```bash
docker run chipprbots/fukuii-mordor:latest -Dfukuii.mining.mining-enabled=true -Dfukuii.mining.coinbase=YOUR_ADDRESS
```

## References

- [Docker BuildKit Documentation](https://docs.docker.com/build/buildkit/)
- [Compose Specification](https://docs.docker.com/compose/compose-file/)
- [OCI Image Spec](https://github.com/opencontainers/image-spec)
- [Debian GPG Key Best Practices](https://wiki.debian.org/DebianRepository/UseThirdParty)
- ADR-011: RLPx Protocol Deviations and Peer Bootstrap Challenge
- Operating Modes Runbook: Boot Node section
- Peering Runbook: Best practices for peer management

## Related Documents

- `docker/Dockerfile.bootnode` - Bootnode image configuration
- `docker/bootnode/docker-compose.yml` - Bootnode deployment example
- `src/main/resources/conf/bootnode.conf` - Bootnode runtime configuration
- `docs/deployment/docker.md` - Docker deployment documentation
