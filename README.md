<div align="center">
  <img src="https://raw.githubusercontent.com/chippr-robotics/fukuii/HEAD/docs/images/fukuii-logo-cute.png" alt="Fukuii Logo" width="400"/>
</div>

# 🧠🐛 Fukuii Ethereum Client
# ALPHA TEST PHASE - DO NOT USE IN PRODUCTION 
[![CI](https://github.com/chippr-robotics/chordodes_fukuii/actions/workflows/ci.yml/badge.svg)](https://github.com/chippr-robotics/chordodes_fukuii/actions/workflows/ci.yml)
[![Docker Build](https://github.com/chippr-robotics/fukuii/actions/workflows/docker.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/docker.yml)
[![Nightly Build](https://github.com/chippr-robotics/fukuii/actions/workflows/nightly.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/nightly.yml)
[![codecov](https://codecov.io/gh/chippr-robotics/fukuii/graph/badge.svg)](https://codecov.io/gh/chippr-robotics/fukuii)

Fukuii is a continuation and re‑branding of the Ethereum Classic client previously known as Mantis. Mantis was developed by Input Output (HK) as a Scala client for the Ethereum Classic (ETC) network. This project is an independent fork maintained by Chippr Robotics LLC with the aim of modernising the codebase and ensuring long‑term support.

Fukuii retains the robust architecture and ETC compatibility of Mantis while introducing new features, updated dependencies and a streamlined build. This fork has been renamed throughout the code and documentation:
- Executable scripts are renamed from mantis to fukuii.
- Java/Scala packages under io.iohk have been moved to com.chipprbots.
- Environment variables and configuration keys prefixed with fukuii have been changed to fukuii.

#### Important Notes

<b>Licence:</b> This project continues to be distributed under the Apache 2.0 licence. A copy of the licence is included in the LICENSE file. The original NOTICE file from IOHK is preserved as required by the licence, and Chippr Robotics LLC has added its own attribution.

<b>Origin:</b> Fukuii is derived from the Mantis
 client. Mantis is a trademark of IOHK; we use the name here only to describe the origin of this fork. 

<b>Chordoes Fukuii is a worm which controls a zombie mantis.</b>

## CI/CD and Project Hygiene

This project uses GitHub Actions for continuous integration and delivery:

- ✅ **Automated Testing**: All tests run on every push and PR
- 🔍 **Code Quality**: Automated formatting and style checks
- 🐳 **Docker Builds**: Automatic container image builds
- 🚀 **One-Click Releases**: Automated releases with CHANGELOG, SBOM, and artifacts
- 📝 **Release Drafter**: Auto-generated release notes from PRs
- 📊 **Dependency Checks**: Weekly dependency monitoring

**Release Automation Features:**
- Auto-generated CHANGELOG from commit history
- JAR and distribution artifacts attached to releases
- Software Bill of Materials (SBOM) in CycloneDX format
- Signed Docker images with SLSA provenance
- Milestone tracking and automatic closure

**Quick Links:**
- [🌐 Documentation Site](https://chippr-robotics.github.io/fukuii/) - Hosted documentation (GitHub Pages)
- [📚 Documentation Index](docs/README.md) - Complete documentation guide
- [Repository Structure](docs/development/REPOSITORY_STRUCTURE.md) - Understand the codebase layout
- [Workflow Documentation](.github/workflows/README.md)
- [Quick Start Guide](.github/QUICKSTART.md)
- [Branch Protection Setup](.github/BRANCH_PROTECTION.md)
- [Docker Documentation](docs/deployment/docker.md)
- [Operations Runbooks](docs/runbooks/README.md) - Production operation guides

**For Contributors:** Before submitting a PR, run `sbt pp` to check formatting, style, and tests locally.

## Key Features

### 🚀 Fast Initial Sync with Bootstrap Checkpoints

**New in v1.1.0**: Fukuii now includes bootstrap checkpoints that significantly improve initial sync times:

- **No Peer Wait**: Begin syncing immediately without waiting for peer consensus
- **Trusted Reference Points**: Uses well-known fork activation blocks as starting points
- **Faster Time-to-Sync**: Eliminates the bootstrap delay that previously affected new nodes
- **Enabled by Default**: Works out-of-the-box for ETC mainnet and Mordor testnet
- **Optional Override**: Use `--force-pivot-sync` flag to disable if needed

See [CON-002: Bootstrap Checkpoints](docs/adr/consensus/CON-002-bootstrap-checkpoints.md) for technical details.

### 🛡️ Production-Ready

- **Scala 3.3.4 (LTS)** and **JDK 21 (LTS)** for long-term stability
- **Apache Pekko** actor system for reliable concurrency
- **Full EIP Support**: Includes Spiral (ECIP-1109), Mystique (ECIP-1104), Magneto (ECIP-1103), and more
- **Comprehensive Testing**: Unit, integration, and blockchain tests
- **Security-First**: Signed Docker images, CodeQL scanning, dependency monitoring

### 🎯 Developer-Friendly

- **Interactive Console UI**: Optional TUI for monitoring sync progress (use `--tui` flag)
- **Extensive CLI Tools**: Key generation, address derivation, and more
- **JSON-RPC API**: Full eth/web3/net API support
- **Custom Networks**: Deploy on private networks without modifying source code
- **Well-Documented**: Comprehensive runbooks and ADRs

Getting started

## Option 1: Docker (Recommended for Production)

The easiest way to run Fukuii is using Docker. Images are available on both GitHub Container Registry and Docker Hub:

### Using Docker Hub (Recommended for Quick Start)

```bash
# Pull the latest release
docker pull chipprbots/fukuii:latest

# Or pull a specific version
docker pull chipprbots/fukuii:v1.0.0

# Run Fukuii
docker run -d \
  --name fukuii \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-data:/app/data \
  -v fukuii-conf:/app/conf \
  chipprbots/fukuii:latest
```

**Docker Hub:** https://hub.docker.com/r/chipprbots/fukuii

### Using GitHub Container Registry (Recommended for Security-Critical Deployments)

```bash
# Pull a specific version (recommended - official releases are signed)
docker pull ghcr.io/chippr-robotics/fukuii:v1.0.0

# Verify the image signature (requires cosign)
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/fukuii:v1.0.0

# Or pull the latest development version
docker pull ghcr.io/chippr-robotics/fukuii:develop

# Run Fukuii
docker run -d \
  --name fukuii \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-data:/app/data \
  -v fukuii-conf:/app/conf \
  ghcr.io/chippr-robotics/fukuii:v1.0.0
```

**Security Note:** Release images published to `ghcr.io/chippr-robotics/fukuii` are:
- ✅ Signed with [Cosign](https://github.com/sigstore/cosign) (keyless, using GitHub OIDC)
- ✅ Include SLSA provenance attestations for supply chain verification
- ✅ Include Software Bill of Materials (SBOM)

See [Docker Documentation](docs/deployment/docker.md) for detailed Docker documentation, including:
- Available image variants (production, development, distroless)
- Health checks and monitoring
- Security considerations and signature verification
- Docker Compose examples

## Option 2: GitHub Codespaces (Recommended for Development)

The fastest way to start developing is using GitHub Codespaces, which provides a pre-configured development environment:

1. Click the green "Code" button on the repository page
2. Select "Open with Codespaces"
3. Wait for the environment to initialize (automatically installs JDK 21, SBT, and Scala)

See [.devcontainer/README.md](.devcontainer/README.md) for more details.

## Option 3: Local Development

To build Fukuii from source locally you will need:

- **JDK 21**
- **sbt** (Scala build tool, version 1.10.7+)
- **Python** (for certain auxiliary scripts)

### Scala Version Support

Fukuii is built with **Scala 3.3.4 (LTS)**, providing modern language features, improved type inference, and better performance.

### Building the client

Update git submodules:

```bash
git submodule update --init --recursive
```

Build the distribution using sbt:

```bash
sbt dist
```

After the build completes, a distribution zip archive will be placed under target/universal/. Unzip it to run the client.

### Running the client

The distribution’s bin/ directory contains a launcher script named fukuii. To join the ETC network:

./bin/fukuii etc


The launcher accepts the same network names that Fukuii did (etc, eth, mordor, testnet-internal). See the configuration files under src/universal/conf for more details.

#### Console UI

Fukuii includes an enhanced Terminal User Interface (TUI) for real-time monitoring:

```bash
# Start with standard logging (default)
./bin/fukuii etc

# Enable console UI for interactive monitoring
./bin/fukuii etc --tui
```

The console UI provides:
- Real-time peer connection status
- Blockchain sync progress with visual indicators
- Network information and status
- Keyboard commands (Q=quit, R=refresh, D=disable UI)
- Color-coded health indicators

**Note**: The console UI is currently disabled by default while under further development.

See [Console UI Documentation](docs/architecture/console-ui.md) for detailed information.


Command line interface (CLI)

Fukuii's CLI tool provides utilities for key generation and other cryptographic functions. To see all available commands and options:

```bash
./bin/fukuii --help          # Show all launcher commands
./bin/fukuii cli --help      # Show all CLI utilities
```

Examples:

```bash
# Generate a new private key
./bin/fukuii cli generate-private-key

# Derive address from a private key
./bin/fukuii cli derive-address <private-key-hex>

# Get help on any specific command
./bin/fukuii cli generate-key-pairs --help
```

For detailed CLI documentation, see the [Node Configuration Runbook](docs/runbooks/node-configuration.md#cli-subcommands).
Configuration and Environment

Many configuration properties begin with the prefix fukuii instead of mantis. For example, the RPC settings are controlled by keys like fukuii.network.rpc.http.mode. Similarly, the environment variable FUKUII_DEV=true enables developer‑friendly settings during the build.

Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for detailed information on:

- Setting up your development environment
- Code quality standards and formatting tools
- Pre-commit hooks for automated checks
- Testing and submitting pull requests

When modifying code derived from Mantis, include a notice in the header of changed files stating that you changed the file and add your own copyright line.

## Development and Future Plans

**Technology Stack**: This project uses **Scala 3.3.4 (LTS)** and **JDK 21 (LTS)** as the primary and only supported versions. The migration from Scala 2.13 to Scala 3 and JDK 17 to JDK 21 was completed in October 2025, including:
- ✅ Migration from Akka to Apache Pekko (Scala 3 compatible)
- ✅ Migration from Monix to Cats Effect 3 IO
- ✅ Migration from Shapeless to native Scala 3 derivation
- ✅ Update to json4s 4.0.7 (Scala 3 compatible)
- ✅ Scalanet vendored locally in the `scalanet/` directory

For the rationale behind these decisions, see [INF-001: Scala 3 Migration](docs/adr/infrastructure/INF-001-scala-3-migration.md). For historical information about the migration, see [Migration History](docs/historical/MIGRATION_HISTORY.md).

**Static Analysis**: We maintain a comprehensive static analysis toolchain including Scalafmt, Scalafix, Scapegoat, and Scoverage. See [Static Analysis Inventory](STATIC_ANALYSIS_INVENTORY.md) for details on our code quality tools.

## Operations and Maintenance

For production deployments, comprehensive operational runbooks are available covering:

- **[Metrics & Monitoring](docs/operations/metrics-and-monitoring.md)** - Structured logging, Prometheus metrics, JMX export, and Grafana dashboards
- **[First Start](docs/runbooks/first-start.md)** - Initial node setup and configuration
- **[Security](docs/runbooks/security.md)** - Node security, firewall configuration, and best practices
- **[Peering](docs/runbooks/peering.md)** - Network connectivity and peer management  
- **[Disk Management](docs/runbooks/disk-management.md)** - Storage, pruning, and optimization
- **[Backup & Restore](docs/runbooks/backup-restore.md)** - Data protection and disaster recovery
- **[Log Triage](docs/runbooks/log-triage.md)** - Log analysis and troubleshooting
- **[Known Issues](docs/runbooks/known-issues.md)** - Common problems and solutions (RocksDB, JVM, temp directories)

See the [Operations Runbooks](docs/runbooks/README.md) for complete operational documentation.

## Health & Readiness Endpoints

Fukuii provides HTTP endpoints for monitoring node health and readiness, enabling integration with modern orchestration platforms like Kubernetes, Docker Swarm, and monitoring systems.

### Available Endpoints

#### `/health` - Liveness Probe
Simple HTTP endpoint that returns `200 OK` if the server is running and responding to requests.

**Use case:** Liveness probes in Kubernetes/Docker to determine if the container should be restarted.

**Example:**
```bash
curl http://localhost:8546/health
```

**Response (200 OK):**
```json
{
  "checks": [
    {
      "name": "server",
      "status": "OK",
      "info": "running"
    }
  ]
}
```

#### `/readiness` - Readiness Probe
Checks if the node is ready to serve traffic. Returns `200 OK` when:
- Database is opened and accessible (stored block exists)
- Node has at least one peer connection
- Blockchain tip is advancing (block numbers are updating)

**Use case:** Readiness probes in Kubernetes/Docker to determine if the container should receive traffic.

**Example:**
```bash
curl http://localhost:8546/readiness
```

**Response (200 OK when ready):**
```json
{
  "checks": [
    {
      "name": "peerCount",
      "status": "OK",
      "info": "5"
    },
    {
      "name": "bestStoredBlock",
      "status": "OK",
      "info": "12345678"
    },
    {
      "name": "bestFetchingBlock",
      "status": "OK"
    }
  ]
}
```

**Response (503 Service Unavailable when not ready):**
```json
{
  "checks": [
    {
      "name": "peerCount",
      "status": "ERROR",
      "info": "peer count is 0"
    },
    ...
  ]
}
```

#### `/healthcheck` - Detailed Health Status
Comprehensive health check including all node subsystems:
- Peer count
- Best stored block
- Best known block
- Best fetching block
- Update status (tip advancing)
- Sync status

**Use case:** Detailed monitoring and diagnostics.

**Example:**
```bash
curl http://localhost:8546/healthcheck
```

### Kubernetes Configuration Example

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: fukuii-node
spec:
  containers:
  - name: fukuii
    image: ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
    ports:
    - containerPort: 8546
      name: rpc
    livenessProbe:
      httpGet:
        path: /health
        port: 8546
      initialDelaySeconds: 30
      periodSeconds: 10
      timeoutSeconds: 5
      failureThreshold: 3
    readinessProbe:
      httpGet:
        path: /readiness
        port: 8546
      initialDelaySeconds: 60
      periodSeconds: 10
      timeoutSeconds: 5
      failureThreshold: 3
```

### Docker Compose Configuration Example

```yaml
version: '3.8'
services:
  fukuii:
    image: ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
    ports:
      - "8546:8546"
      - "30303:30303"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8546/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
```

### Configuration

Health check behavior can be configured in `conf/base.conf`:

```hocon
fukuii.network.rpc {
  health {
    # If the best known block number stays the same for more time than this,
    # the healthcheck will consider the client to be stuck and return an error
    no-update-duration-threshold = 30.minutes
    
    # If the difference between the best stored block number and the best known block number
    # is less than this value, the healthcheck will report that the client is synced.
    syncing-status-threshold = 10
  }
}
```

## Contact

For questions or support, reach out to Chippr Robotics LLC via our GitHub repository.
