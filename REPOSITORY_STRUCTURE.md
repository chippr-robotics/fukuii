# Fukuii Repository Structure

This document provides an overview of the Fukuii repository organization to help contributors and users understand the layout of the codebase.

## Repository Organization

```
fukuii/
├── src/                    # Main application source code
├── bytes/                  # Vendored: Bytes manipulation library
├── crypto/                 # Vendored: Cryptographic utilities
├── rlp/                    # Vendored: RLP encoding/decoding
├── scalanet/               # Vendored: Networking library (from IOHK)
├── docker/                 # Docker configurations and compose files
├── docs/                   # Documentation (ADRs, runbooks, guides)
├── ets/                    # Ethereum Test Suite configuration
├── ops/                    # Operations configs (Grafana dashboards)
├── tls/                    # TLS certificates for testing
├── project/                # SBT build configuration
├── build.sbt               # Main SBT build definition
└── [config files]          # .scalafmt.conf, .scalafix.conf, etc.
```

## Directory Details

### Application Source Code

#### `src/`
The main Fukuii application source code, following standard SBT/Scala conventions:

- **`src/main/`** - Production code
  - `scala/com/chipprbots/ethereum/` - Main application package
  - `resources/` - Configuration files, genesis blocks, etc.
  - `protobuf/` & `protobuf_override/` - Protocol buffer definitions for external VM interface
  
- **`src/test/`** - Unit tests
- **`src/it/`** - Integration tests
- **`src/rpcTest/`** - RPC API-specific tests
- **`src/evmTest/`** - EVM execution tests
- **`src/benchmark/`** - Performance benchmarks
- **`src/universal/`** - Distribution files
  - `bin/` - Launcher scripts
  - `conf/` - Configuration templates

### Vendored Libraries

These libraries were vendored (copied into the repository) to provide better control over dependencies and ensure Scala 3 compatibility:

#### `bytes/`
Simple bytes manipulation utilities. A foundational library used throughout the codebase.

#### `crypto/`
Cryptographic utilities for Ethereum operations (hashing, signing, key derivation).

#### `rlp/`
Recursive Length Prefix (RLP) encoding and decoding, the serialization format used in Ethereum.

#### `scalanet/`
Network layer implementation originally from IOHK. Provides peer-to-peer networking functionality.

**Note**: These libraries are defined as SBT subprojects in `build.sbt` and maintain their own `src/main/` and `src/test/` structure.

### Testing & Integration

#### `ets/`
Ethereum Test Suite (ETS) integration:
- `ets/tests/` - Git submodule pointing to official Ethereum consensus tests
- `ets/config/fukuii/` - Retesteth configuration for Fukuii
- `ets/retesteth` - Wrapper script for running tests
- `test-ets.sh` - CI script for running the full test suite

See [ets/README.md](ets/README.md) for details on running ETS tests.

#### `tls/`
TLS certificates and scripts for secure RPC testing:
- Self-signed certificates for development/testing
- Certificate generation scripts

### Operations & Deployment

#### `docker/`
Docker and Docker Compose configurations:
- `docker/fukuii/` - Fukuii-specific Docker Compose setup with Prometheus/Grafana
- `docker/besu/` - Besu client setup (for comparison testing)
- `docker/geth/` - Geth client setup (for comparison testing)
- `docker/kong/` - Kong API gateway integration
- `docker/scripts/` - Helper scripts
- `Dockerfile*` - Various Dockerfile variants (prod, dev, distroless, etc.)

See [docker/README.md](docker/README.md) for comprehensive Docker documentation.

#### `ops/`
Operational configurations for production deployments:
- `ops/grafana/` - Pre-configured Grafana dashboards for monitoring Fukuii nodes

See [ops/README.md](ops/README.md) and [docs/operations/metrics-and-monitoring.md](docs/operations/metrics-and-monitoring.md) for details.

#### `docs/`
Comprehensive documentation:
- `docs/adr/` - Architecture Decision Records (ADRs), organized by category (infrastructure, vm, consensus, testing, operations)
- `docs/runbooks/` - Operational runbooks for production
- `docs/operations/` - Metrics, monitoring, and operational guides
- `docs/images/` - Logo and other images

Key documents:
- [docs/adr/README.md](docs/adr/README.md) - Index of architecture decisions
- [docs/runbooks/README.md](docs/runbooks/README.md) - Index of operational runbooks
- [docs/architecture-overview.md](docs/architecture-overview.md) - System architecture

### Build System

#### `project/`
SBT build configuration:
- `project/build.properties` - SBT version
- `project/plugins.sbt` - SBT plugins
- `project/Dependencies.scala` - Dependency management

#### Root Build Files
- `build.sbt` - Main build definition with subproject configuration
- `.jvmopts` - JVM options for SBT
- `.scalafmt.conf` - Code formatting rules (Scalafmt)
- `.scalafix.conf` - Linting and refactoring rules (Scalafix)
- `version.sbt` - Project version

### Configuration Files

- `.gitignore` - Git ignore patterns
- `.gitmodules` - Git submodules (ETS tests)
- `.dockerignore` - Docker ignore patterns
- `.devcontainer/` - VS Code Dev Container / GitHub Codespaces configuration

## Build System Architecture

Fukuii uses a multi-module SBT build with the following structure:

1. **Core Application** (`node` project in root)
   - Depends on: bytes, crypto, rlp, scalanet, scalanetDiscovery
   - Configurations: compile, test, it, evm, rpcTest, benchmark

2. **Vendored Libraries** (independent SBT subprojects)
   - Each has its own `src/main/` and `src/test/` structure
   - Published as separate artifacts (though currently disabled)
   - Can be built/tested independently

## Conventions and Standards

### Code Organization
- Package structure: `com.chipprbots.ethereum.*`
- Scala version: 3.3.4 (LTS)
- JDK version: 21 (LTS)

### Testing Conventions
- Unit tests: `src/test/scala/`
- Integration tests: `src/it/scala/`
- Test configurations: Use ScalaTest framework
- Ethereum tests: ETS submodule with retesteth

### Documentation Conventions
- Architecture decisions: ADRs in `docs/adr/`
- Operational guides: Runbooks in `docs/runbooks/`
- API documentation: ScalaDoc in source code
- External docs: Markdown in `docs/`

## Development Workflow

### Quick Start
```bash
# Clone with submodules
git clone --recursive https://github.com/chippr-robotics/fukuii.git
cd fukuii

# Build
sbt compile

# Run tests
sbt test

# Format and check code
sbt pp  # "prepare PR" - formats, checks, and tests

# Build distribution
sbt dist
```

### Testing
```bash
# Unit tests
sbt test

# Integration tests
sbt IntegrationTest/test

# RPC tests
sbt RpcTest/test

# EVM tests
sbt Evm/test

# Ethereum Test Suite
./test-ets.sh
```

### Code Quality
```bash
# Format all code
sbt formatAll

# Check formatting
sbt formatCheck

# Run static analysis
sbt runScapegoat

# Coverage report
sbt testCoverage
```

## Historical Context

This repository is a continuation of the Mantis Ethereum Classic client originally developed by Input Output (HK). Key changes:

1. **Rebranding** (2024): Mantis → Fukuii
   - Package: `io.iohk.ethereum` → `com.chipprbots.ethereum`
   - Binary: `mantis` → `fukuii`
   
2. **Scala 3 Migration** (October 2024)
   - Scala 2.13 → Scala 3.3.4 (LTS)
   - Akka → Apache Pekko
   - Monix → Cats Effect 3
   - See [docs/adr/infrastructure/INF-001-scala-3-migration.md](docs/adr/infrastructure/INF-001-scala-3-migration.md)

3. **Vendored Dependencies**
   - scalanet: Networking library (needed for Scala 3 compatibility)
   - bytes, crypto, rlp: Core utilities extracted as modules

## Related Documentation

- [README.md](README.md) - Getting started and features
- [CONTRIBUTING.md](CONTRIBUTING.md) - Contribution guidelines
- [CHANGELOG.md](CHANGELOG.md) - Version history
- [docs/ARCHITECTURE_DIAGRAMS.md](docs/ARCHITECTURE_DIAGRAMS.md) - C4 architecture diagrams
- [docs/VENDORED_MODULES_INTEGRATION_PLAN.md](docs/VENDORED_MODULES_INTEGRATION_PLAN.md) - Plan for integrating vendored modules
- [LICENSE](LICENSE) - Apache 2.0 license
- [NOTICE](NOTICE) - Attribution and notices

## Questions?

For questions about the repository structure or where to add new code:

1. Check existing code for similar functionality
2. Follow the package structure in `src/main/scala/`
3. Refer to [CONTRIBUTING.md](CONTRIBUTING.md)
4. Ask in GitHub Discussions or open an issue
