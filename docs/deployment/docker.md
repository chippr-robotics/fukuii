# Fukuii Docker Images

This directory contains Dockerfiles for building and running Fukuii Ethereum Client in containerized environments.

## Kong API Gateway

For production deployments with load balancing, authentication, and monitoring, see the [Kong API Gateway setup](kong.md) which provides:

- **API Gateway**: Kong Gateway for routing and managing all traffic
- **High Availability**: Load balancing across multiple Fukuii instances
- **Security**: Basic Auth, JWT, rate limiting, and CORS support
- **Monitoring**: Prometheus metrics and Grafana dashboards
- **Multi-Network Support**: HD wallet hierarchy routing for Bitcoin, Ethereum, and Ethereum Classic

Quick start: `cd kong && ./setup.sh`

Full documentation: [kong.md](kong.md)

## Container Registries

Fukuii maintains images in multiple container registries:

### Docker Hub (Recommended for Quick Start)
- **Registry:** `chipprbots/fukuii`
- **URL:** https://hub.docker.com/r/chipprbots/fukuii
- **Publishing:** Automated via `.github/workflows/release.yml` and `.github/workflows/docker.yml`
- **Images:** 
  - `chipprbots/fukuii` - Production image
  - `chipprbots/fukuii-dev` - Development image
  - `chipprbots/fukuii-base` - Base image
- **Tags:** Semantic versions (e.g., `v1.0.0`, `1.0`, `1`, `latest`), branch names, Git SHAs
- **Notes:** Unsigned images, suitable for general use and quick deployment

**Quick Start:**
```bash
docker pull chipprbots/fukuii:latest
docker run -d --name fukuii -p 8545:8545 chipprbots/fukuii:latest
```

### GitHub Container Registry - Official Release (Recommended for Production)
- **Registry:** `ghcr.io/chippr-robotics/chordodes_fukuii`
- **Publishing:** Automated via `.github/workflows/release.yml` on version tags
- **Security Features:**
  - ✅ Images are signed with [Cosign](https://github.com/sigstore/cosign) (keyless signing using GitHub OIDC)
  - ✅ SLSA Level 3 provenance attestations attached
  - ✅ Software Bill of Materials (SBOM) included
  - ✅ Immutable digest references
- **Tags:** Semantic versions (e.g., `v1.0.0`, `1.0`, `1`, `latest`)

### GitHub Container Registry - Development
- **Registry:** `ghcr.io/chippr-robotics/fukuii`
- **Publishing:** Automated via `.github/workflows/docker.yml` on branch pushes
- **Images:** `fukuii`, `fukuii-dev`, `fukuii-base`
- **Tags:** Branch names, PR numbers, Git SHAs

## Image Signature Verification

Official release images are signed with Cosign for supply chain security.

### Install Cosign

**Option 1: Using Package Manager (Recommended)**
```bash
# macOS
brew install cosign

# Linux with snap
snap install cosign --classic
```

**Option 2: Manual Installation with Verification**
```bash
# Download cosign for Linux
VERSION="2.2.3"
wget "https://github.com/sigstore/cosign/releases/download/v${VERSION}/cosign-linux-amd64"
wget "https://github.com/sigstore/cosign/releases/download/v${VERSION}/cosign_checksums.txt"

# Verify checksum
grep cosign-linux-amd64 cosign_checksums.txt | sha256sum --check
# Expected output: cosign-linux-amd64: OK

# Install
sudo install -m 755 cosign-linux-amd64 /usr/local/bin/cosign

# Verify installation
cosign version
```

**Option 3: Using GitHub CLI (Automatically Verified)**
```bash
VERSION="2.2.3"
gh release download "v${VERSION}" --repo sigstore/cosign --pattern 'cosign-linux-amd64'
sudo install -m 755 cosign-linux-amd64 /usr/local/bin/cosign
```

### Verify Image Signature

```bash
# Verify a signed release image
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
```

**What this verifies:**
- The image was built by GitHub Actions in this repository
- The image has not been tampered with since it was signed
- The signature is valid and trusted

### Verify SLSA Provenance

```bash
# Install slsa-verifier
go install github.com/slsa-framework/slsa-verifier/v2/cli/slsa-verifier@latest

# Verify SLSA provenance
slsa-verifier verify-image \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0 \
  --source-uri github.com/chippr-robotics/fukuii
```

**What this verifies:**
- Build provenance meets SLSA Level 3 requirements
- The image was built from the expected source repository
- Build process integrity is maintained

## Available Images

### 1. Production Image (`Dockerfile`)
The main production-ready image for running Fukuii.

**Features:**
- Multi-stage build for optimal size and security
- Based on `eclipse-temurin:17-jre-jammy` (slim JRE)
- Runs as non-root user (`fukuii:fukuii`, UID/GID 1000)
- Includes built-in healthcheck script
- Exposes standard Ethereum ports (8545, 8546, 30303)
- Minimal attack surface with only required dependencies

**Build:**
```bash
# Important: Initialize submodules before building
git submodule update --init --recursive

# Build the Docker image
docker build -f docker/Dockerfile -t fukuii:latest .
```

**Note:** The build requires git submodules to be initialized before running Docker build. The GitHub Actions CI/CD pipeline handles this automatically via the checkout step with `submodules: recursive`.

**Run:**
```bash
# Start with default configuration (ETC network)
docker run -d \
  --name fukuii \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-data:/app/data \
  -v fukuii-conf:/app/conf \
  fukuii:latest

# Start with custom configuration
docker run -d \
  --name fukuii \
  -p 8545:8545 \
  -v fukuii-data:/app/data \
  -v /path/to/your/conf:/app/conf \
  fukuii:latest etc
```

### 2. Development Image (`Dockerfile-dev`)
A development image with JDK 21 and SBT for building and testing.

**Features:**
- Based on `eclipse-temurin:21-jdk-jammy` (full JDK)
- Includes SBT build tool
- Includes Git for source management
- Runs as non-root user
- Useful for CI/CD and local development

**Build:**
```bash
docker build -f docker/Dockerfile-dev -t fukuii-dev:latest .
```

**Run:**
```bash
# Interactive development shell
docker run -it --rm \
  -v $(pwd):/workspace \
  -w /workspace \
  fukuii-dev:latest /bin/bash

# Run tests
docker run --rm \
  -v $(pwd):/workspace \
  -w /workspace \
  fukuii-dev:latest sbt testAll
```

### 3. Base Image (`Dockerfile-base`)
A minimal base image with common dependencies.

**Features:**
- Based on `ubuntu:22.04` (Ubuntu Jammy)
- Minimal set of packages (curl, ca-certificates, locales)
- Non-root user configured
- Used as a foundation for other custom images

**Build:**
```bash
docker build -f docker/Dockerfile-base -t fukuii-base:latest .
```

### 4. Distroless Image (`Dockerfile.distroless`)
Maximum security image using Google's distroless base.

**Features:**
- Based on `gcr.io/distroless/java21-debian12:nonroot`
- Minimal attack surface - no shell, no package manager
- Smallest possible image size
- Direct Java execution (no bash wrapper)
- Best for production deployments with external orchestration

**Note:** Distroless images don't support shell-based healthchecks or bash scripts. The entrypoint invokes Java directly with the main class `com.chipprbots.ethereum.App`. Use external health monitoring (e.g., Kubernetes liveness/readiness probes).

**Build:**
```bash
docker build -f docker/Dockerfile.distroless -t fukuii:distroless .
```

### 5. Network-Specific Images

Pre-configured images for specific Ethereum Classic networks, making it easy to deploy nodes without manual configuration.

#### 5.1. ETC Mainnet Image (`Dockerfile.mainnet`)
Pre-configured for Ethereum Classic mainnet synchronization.

**Features:**
- Pre-configured for ETC mainnet
- Same features as production image
- Environment variable `FUKUII_NETWORK=etc` pre-set

**Docker Hub:**
- `chipprbots/fukuii-mainnet:latest` (latest build)
- `chipprbots/fukuii-mainnet:nightly` (nightly build)
- `chipprbots/fukuii-mainnet:nightly-YYYYMMDD` (specific nightly)

**GitHub Container Registry:**
- `ghcr.io/chippr-robotics/fukuii-mainnet:latest`

**Run:**
```bash
# From Docker Hub
docker run -d \
  --name fukuii-mainnet \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-mainnet-data:/app/data \
  chipprbots/fukuii-mainnet:latest

# From GitHub Container Registry
docker run -d \
  --name fukuii-mainnet \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-mainnet-data:/app/data \
  ghcr.io/chippr-robotics/fukuii-mainnet:latest
```

#### 5.2. Mordor Testnet Image (`Dockerfile.mordor`)
Pre-configured for Ethereum Classic Mordor testnet synchronization.

**Features:**
- Pre-configured for Mordor testnet
- Same features as production image
- Environment variable `FUKUII_NETWORK=mordor` pre-set
- Perfect for testing and development

**Docker Hub:**
- `chipprbots/fukuii-mordor:latest` (latest build)
- `chipprbots/fukuii-mordor:nightly` (nightly build)
- `chipprbots/fukuii-mordor:nightly-YYYYMMDD` (specific nightly)

**GitHub Container Registry:**
- `ghcr.io/chippr-robotics/fukuii-mordor:latest`

**Run:**
```bash
# From Docker Hub
docker run -d \
  --name fukuii-mordor \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-mordor-data:/app/data \
  chipprbots/fukuii-mordor:latest

# From GitHub Container Registry
docker run -d \
  --name fukuii-mordor \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-mordor-data:/app/data \
  ghcr.io/chippr-robotics/fukuii-mordor:latest
```

#### 5.3. Bootnode Image (`Dockerfile.bootnode`)
Pre-configured as a bootnode for peer discovery and network health.

**Features:**
- Optimized for peer discovery and connection brokering
- High peer capacity (500+ concurrent peers)
- Minimal resource footprint (2-4GB RAM, ~5GB disk)
- No RPC endpoints (reduced attack surface)
- In-memory pruning for minimal disk usage
- Exposes only P2P ports (30303/udp, 9076/tcp)

**Purpose:**
Bootnodes serve as network infrastructure, helping new nodes discover peers and join the network faster. They focus exclusively on:
- Maintaining connections to many peers
- Sharing peer information via discovery protocol
- Enabling faster network synchronization
- Providing stable entry points to the network

**Docker Hub:**
- `chipprbots/fukuii-bootnode:latest` (latest build)
- `chipprbots/fukuii-bootnode:nightly` (nightly build)
- `chipprbots/fukuii-bootnode:nightly-YYYYMMDD` (specific nightly)

**GitHub Container Registry:**
- `ghcr.io/chippr-robotics/fukuii-bootnode:latest`

**Run:**
```bash
# From Docker Hub
docker run -d \
  --name fukuii-bootnode \
  -p 30303:30303/udp \
  -p 9076:9076/tcp \
  -v fukuii-bootnode-data:/app/data \
  -e JAVA_OPTS="-Xmx2g -Xms2g" \
  chipprbots/fukuii-bootnode:latest

# From GitHub Container Registry
docker run -d \
  --name fukuii-bootnode \
  -p 30303:30303/udp \
  -p 9076:9076/tcp \
  -v fukuii-bootnode-data:/app/data \
  -e JAVA_OPTS="-Xmx2g -Xms2g" \
  ghcr.io/chippr-robotics/fukuii-bootnode:latest
```

**Docker Compose Example for Bootnode:**
```yaml
volumes:
  bootnode-data:

networks:
  fukuii-bootnode-net:
    driver: bridge

services:
  fukuii-bootnode:
    image: chipprbots/fukuii-bootnode:latest
    container_name: fukuii-bootnode
    restart: unless-stopped
    ports:
      - "30303:30303/udp"  # P2P discovery
      - "9076:9076/tcp"    # P2P networking
    volumes:
      - bootnode-data:/app/data
    environment:
      - JAVA_OPTS=-Xmx2g -Xms2g
    networks:
      - fukuii-bootnode-net
    healthcheck:
      test: ["CMD", "/usr/local/bin/healthcheck.sh"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 30s
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 4G
        reservations:
          cpus: '1'
          memory: 2G
```

**Getting Your Bootnode's Enode URL:**

After starting the bootnode, you can share its enode URL with others:

1. Your node ID is derived from `~/.fukuii/<network>/node.key`
2. Construct enode URL: `enode://<node-id>@<public-ip>:30303`
3. Share this URL with others who want to use your bootnode

**Firewall Configuration:**

Ensure these ports are open:
- UDP 30303 (discovery protocol) - REQUIRED
- TCP 9076 (P2P connections) - REQUIRED

Example firewall rules:
```bash
sudo ufw allow 30303/udp
sudo ufw allow 9076/tcp
```

**Monitoring Your Bootnode:**

Check logs for peer activity:
```bash
docker logs fukuii-bootnode | grep -E "peer|discovery"
```

Look for messages indicating healthy operation:
- "Discovery - Found N peers in routing table"
- "PeerManager - Connected to peer"
- Regular discovery activity

**Resource Requirements:**
- CPU: 2 cores (minimal)
- RAM: 2-4 GB
- Disk: ~5 GB (no blockchain data)
- Network: 50-100 Mbps (handles many peer connections)

**Best Practices:**
1. Use a static public IP or reliable hostname
2. Ensure high uptime (bootnodes should be always available)
3. Monitor disk space for knownNodes.json growth
4. Monitor network bandwidth (many connections = higher traffic)
5. Set up alerting if peer count drops below threshold
6. Consider running multiple bootnodes for redundancy
7. Keep the bootnode software updated

For detailed bootnode configuration and operations, see:
- `src/main/resources/conf/bootnode.conf` - Configuration file
- `docs/runbooks/operating-modes.md` - Operating modes documentation
- `docs/runbooks/peering.md` - Peering best practices
- `docs/adr/infrastructure/INF-005-docker-deployment-strategy.md` - Docker strategy ADR

#### 5.4. Mining Configuration (Alternative to Bootnode)

While we recommend running bootnodes to improve network health, you can also enable mining on any Fukuii node:

**Using Standard Mordor Image with Mining:**
```bash
docker run -d \
  --name fukuii-mordor-miner \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-mordor-data:/app/data \
  chipprbots/fukuii-mordor:latest \
  -Dfukuii.mining.mining-enabled=true \
  -Dfukuii.mining.coinbase=YOUR_ADDRESS_HERE \
  -Dconfig.file=/app/conf/mordor.conf \
  -Dlogback.configurationFile=/app/conf/logback.xml \
  mordor
```

**⚠️ IMPORTANT:** Always specify a valid coinbase address to receive mining rewards. Without it, rewards will be lost.

## Health Checks

The production image includes a built-in healthcheck script that:
1. Verifies the Fukuii process is running
2. Optionally tests the JSON-RPC endpoint (if enabled and accessible)

**Docker Healthcheck:**
```bash
# Check container health status
docker inspect --format='{{.State.Health.Status}}' fukuii

# View healthcheck logs
docker inspect --format='{{json .State.Health}}' fukuii | jq
```

**Kubernetes Probes:**
```yaml
livenessProbe:
  exec:
    command:
    - /usr/local/bin/healthcheck.sh
  initialDelaySeconds: 60
  periodSeconds: 30
  timeoutSeconds: 10

readinessProbe:
  exec:
    command:
    - /usr/local/bin/healthcheck.sh
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
```

For distroless images, use HTTP-based probes:
```yaml
livenessProbe:
  httpGet:
    path: /
    port: 8545
  initialDelaySeconds: 60
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /
    port: 8545
  initialDelaySeconds: 30
  periodSeconds: 10
```

## Security Considerations

### Trusted Supply Chain

Release images published to `ghcr.io/chippr-robotics/chordodes_fukuii` follow supply chain security best practices:

#### 1. Image Signing with Cosign
- All release images are signed using [Sigstore Cosign](https://docs.sigstore.dev/cosign/overview/)
- Uses keyless signing with GitHub OIDC (no keys to manage or rotate)
- Signatures are stored in the Sigstore transparency log (Rekor)
- Verifiable proof that images were built by our official GitHub Actions workflows

#### 2. SLSA Provenance
- [SLSA Level 3](https://slsa.dev/spec/v1.0/levels) provenance attestations are generated
- Provides verifiable metadata about how the image was built
- Includes source repository, commit SHA, build parameters, and builder identity
- Helps prevent supply chain attacks by ensuring build integrity

#### 3. Software Bill of Materials (SBOM)
- Automatically generated SBOM in SPDX format
- Lists all software components and dependencies in the image
- Enables vulnerability tracking and compliance reporting
- Attached as an attestation to the image

#### 4. Immutable References
- Every release includes an immutable digest reference (e.g., `sha256:abc123...`)
- Digest references cannot be changed or overwritten
- Provides strongest guarantee of image integrity

**Verification Example:**
```bash
# 1. Pull the image by version tag
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

# 2. Verify the signature
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

# 3. Verify SLSA provenance (optional)
slsa-verifier verify-image \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0 \
  --source-uri github.com/chippr-robotics/fukuii

# 4. Use the verified image with immutable digest
docker pull ghcr.io/chippr-robotics/chordodes_fukuii@sha256:abc123...
```

### Non-Root User
All images run as the `fukuii` user (UID 1000, GID 1000) for security. This prevents privilege escalation attacks.

### Image Scanning
Regularly scan images for vulnerabilities:
```bash
# Using Docker Scout (if available)
docker scout cves ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

# Using Trivy
trivy image ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

# Using Grype
grype ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
```

### Best Practices
- Always use specific version tags in production (avoid `:latest`)
- Verify image signatures before deploying to production
- Use immutable digest references for critical deployments
- Regularly update base images to get security patches
- Use distroless images when possible for maximum security
- Limit exposed ports to only what's necessary
- Use read-only root filesystem when possible
- Set resource limits (memory, CPU) appropriately
- Monitor the Sigstore transparency log for your images

## Environment Variables

- `FUKUII_DATA_DIR` - Data directory path (default: `/app/data`)
- `FUKUII_CONF_DIR` - Configuration directory path (default: `/app/conf`)
- `JAVA_OPTS` - Additional JVM options

## Volumes

- `/app/data` - Blockchain data and state
- `/app/conf` - Configuration files

## Ports

- `8545` - HTTP JSON-RPC API
- `8546` - WebSocket JSON-RPC API
- `30303` - P2P networking (TCP and UDP)

## Docker Compose Example

```yaml
version: '3.8'

services:
  fukuii:
    image: fukuii:latest
    container_name: fukuii
    restart: unless-stopped
    ports:
      - "8545:8545"
      - "8546:8546"
      - "30303:30303"
    volumes:
      - fukuii-data:/app/data
      - ./conf:/app/conf:ro
    environment:
      - JAVA_OPTS=-Xmx4g -Xms4g
    healthcheck:
      test: ["/usr/local/bin/healthcheck.sh"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

volumes:
  fukuii-data:
```

## CI/CD Integration

Fukuii uses automated workflows for container image publishing to both Docker Hub and GitHub Container Registry:

### Release Workflow (`.github/workflows/release.yml`)

**Triggered by:** Git tags starting with `v` (e.g., `v1.0.0`)

**Registries:** 
- `ghcr.io/chippr-robotics/chordodes_fukuii` (Official releases - signed)
- `chipprbots/fukuii` (Docker Hub - unsigned)

**Security Features (GHCR only):**
- ✅ Images signed with Cosign (keyless, GitHub OIDC)
- ✅ SLSA Level 3 provenance attestations
- ✅ SBOM (Software Bill of Materials) included
- ✅ Immutable digest references logged

**Tags Generated:**
- Semantic version tags:
  - `v1.0.0` - Full version
  - `1.0` - Major.minor
  - `1` - Major only (not applied to v0.x releases)
  - `latest` - Latest stable release (excludes alpha/beta/rc)

**Example Release:**
```bash
# Create and push a release tag
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin v1.0.0

# Workflow automatically:
# 1. Builds the application
# 2. Creates GitHub release with artifacts
# 3. Builds and pushes Docker images to both registries
# 4. Signs GHCR image with Cosign
# 5. Generates SLSA provenance
# 6. Logs immutable digest
```

### Development Workflow (`.github/workflows/docker.yml`)

**Triggered by:** Push to main/develop branches, Pull Requests

**Registries:**
- `ghcr.io/chippr-robotics/fukuii` (Development builds)
- `chipprbots/fukuii` (Docker Hub)

**Images:**
- **Main Image:** 
  - `ghcr.io/chippr-robotics/fukuii:latest`
  - `chipprbots/fukuii:latest`
- **Dev Image:** 
  - `ghcr.io/chippr-robotics/fukuii-dev:latest`
  - `chipprbots/fukuii-dev:latest`
- **Base Image:** 
  - `ghcr.io/chippr-robotics/fukuii-base:latest`
  - `chipprbots/fukuii-base:latest`
- **Mainnet Image:**
  - `ghcr.io/chippr-robotics/fukuii-mainnet:latest`
  - `chipprbots/fukuii-mainnet:latest`
- **Mordor Image:**
  - `ghcr.io/chippr-robotics/fukuii-mordor:latest`
  - `chipprbots/fukuii-mordor:latest`
- **Bootnode Image:**
  - `ghcr.io/chippr-robotics/fukuii-bootnode:latest`
  - `chipprbots/fukuii-bootnode:latest`

**Tags Generated:**
- Branch names (e.g., `main`, `develop`)
- Git SHA (e.g., `sha-a1b2c3d`)
- PR numbers (e.g., `pr-123`)
- `latest` for the default branch

**Note:** Development images are not signed and do not include provenance attestations. Use release images for production deployments.

### Nightly Build Workflow (`.github/workflows/nightly.yml`)

**Triggered by:** Scheduled daily at 00:00 GMT (midnight UTC), or manually via workflow_dispatch

**Purpose:** Provides automated nightly builds of all container images for testing and development purposes.

**Registries:**
- `ghcr.io/chippr-robotics/fukuii` (Development builds)
- `chipprbots/fukuii` (Docker Hub)

**Images Built:**
- Standard images (main, dev, base)
- Network-specific images (mainnet, mordor, bootnode)

**Tags Generated:**
- `nightly` - Always points to the latest nightly build
- `nightly-YYYYMMDD` - Specific nightly build date (e.g., `nightly-20250115`)

**Use Cases:**
- Testing latest changes before a release
- Automated testing pipelines
- Development environments requiring cutting-edge features
- Early access to bug fixes

**Example Usage:**
```bash
# Pull latest nightly build of mainnet image
docker pull chipprbots/fukuii-mainnet:nightly

# Pull specific nightly build of bootnode
docker pull chipprbots/fukuii-bootnode:nightly-20250115

# Use in Docker Compose for continuous testing
```yaml
services:
  fukuii:
    image: chipprbots/fukuii-mordor:nightly
    # ... rest of config
```

**Note:** Nightly images are intended for development and testing. For production use, prefer versioned release images or the `latest` tag from the release workflow.

## Migration from Old Images

If you're migrating from the old Nix-based images:

1. **Data compatibility:** The new images use the same data format. Mount your existing data volume at `/app/data`.

2. **Configuration:** Update configuration file paths if needed. The new images expect config in `/app/conf`.

3. **User/Group:** The new images use UID/GID 1000. If your volumes have different ownership:
   ```bash
   docker run --rm -v fukuii-data:/data alpine chown -R 1000:1000 /data
   ```

4. **Environment variables:** Update any Nix-specific environment variables to standard JVM options.

## Troubleshooting

### Container won't start
```bash
# Check logs
docker logs fukuii

# Run in foreground to see errors
docker run --rm -it fukuii:latest etc
```

### Permission denied errors
```bash
# Check volume ownership
docker run --rm -v fukuii-data:/data alpine ls -la /data

# Fix ownership if needed
docker run --rm -v fukuii-data:/data alpine chown -R 1000:1000 /data
```

### Health check failing
```bash
# Run health check manually
docker exec fukuii /usr/local/bin/healthcheck.sh

# Check if RPC is enabled in configuration
docker exec fukuii cat /app/conf/app.conf | grep rpc
```

## Support

For issues and questions:
- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
- Documentation: https://github.com/chippr-robotics/fukuii/blob/main/README.md
