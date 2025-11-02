<div align="center">
  <img src="docs/images/fukuii-logo-cute.png" alt="Fukuii Logo" width="400"/>
</div>

# 🧠🐛 Fukuii Ethereum Client

[![CI](https://github.com/chippr-robotics/chordodes_fukuii/actions/workflows/ci.yml/badge.svg)](https://github.com/chippr-robotics/chordodes_fukuii/actions/workflows/ci.yml)

Fukuii is a continuation and re‑branding of the Ethereum Classic client previously known as Mantis. Mantis was developed by Input Output (HK) as a Scala client for the Ethereum Classic (ETC) network. This project is an independent fork maintained by Chippr Robotics LLC with the aim of modernising the codebase and ensuring long‑term support.

Fukuii retains the robust architecture and ETC compatibility of Mantis while introducing new features, updated dependencies and a streamlined build. This fork has been renamed throughout the code and documentation:

Executable scripts are renamed from mantis to fukuii.

Java/Scala packages under io.iohk have been moved to com.chipprbots.

Environment variables and configuration keys prefixed with mantis have been changed to fukuii.

Important Notes

Licence: This project continues to be distributed under the Apache 2.0 licence. A copy of the licence is included in the LICENSE file. The original NOTICE file from IOHK is preserved as required by the licence, and Chippr Robotics LLC has added its own attribution.

Origin: Fukuii is derived from the Mantis
 client. Mantis is a trademark of IOHK; we use the name here only to describe the origin of this fork. 

Chordoes Fukuii is a worm which controls a zombie mantis.

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
- [Workflow Documentation](.github/workflows/README.md)
- [Quick Start Guide](.github/QUICKSTART.md)
- [Branch Protection Setup](.github/BRANCH_PROTECTION.md)
- [Docker Documentation](docker/README.md)
- [Operations Runbooks](docs/runbooks/README.md) - Production operation guides

**For Contributors:** Before submitting a PR, run `sbt pp` to check formatting, style, and tests locally.

Getting started

## Option 1: Docker (Recommended for Production)

The easiest way to run Fukuii is using Docker:

```bash
# Pull a specific version (recommended - official releases are signed)
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

# Verify the image signature (requires cosign)
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

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
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
```

**Security Note:** Release images published to `ghcr.io/chippr-robotics/chordodes_fukuii` are:
- ✅ Signed with [Cosign](https://github.com/sigstore/cosign) (keyless, using GitHub OIDC)
- ✅ Include SLSA provenance attestations for supply chain verification
- ✅ Include Software Bill of Materials (SBOM)

See [docker/README.md](docker/README.md) for detailed Docker documentation, including:
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


The launcher accepts the same network names that Mantis did (etc, eth, mordor, testnet-internal). See the configuration files under src/universal/conf for more details.

Command line interface (CLI)

Fukuii’s CLI tool provides utilities for key generation and other functions. The entry point is:

./bin/fukuii cli [options]


For example, to generate a new private key:

./bin/fukuii cli generate-private-key

Configuration and Environment

Many configuration properties begin with the prefix fukuii instead of mantis. For example, the RPC settings are controlled by keys like fukuii.network.rpc.http.mode. Similarly, the environment variable FUKUII_DEV=true enables developer‑friendly settings during the build.

Migrating from Mantis

If you have an existing deployment of Mantis, follow these steps to migrate:

Update your configuration files by replacing mantis with fukuii in key names and environment variables.

Rename any directories or files under ~/.mantis to ~/.fukuii. The layout of the data directory remains the same.

Review custom scripts or automation to ensure they invoke fukuii instead of mantis.

Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for detailed information on:

- Setting up your development environment
- Code quality standards and formatting tools
- Pre-commit hooks for automated checks
- Testing and submitting pull requests

When modifying code derived from Mantis, include a notice in the header of changed files stating that you changed the file and add your own copyright line.

## Development and Future Plans

**Technology Stack**: This project uses **Scala 3.3.4 (LTS)** as the primary and only supported version. The migration from Scala 2.13 to Scala 3 was completed in October 2025, including:
- ✅ Migration from Akka to Apache Pekko (Scala 3 compatible)
- ✅ Migration from Monix to Cats Effect 3 IO
- ✅ Migration from Shapeless to native Scala 3 derivation
- ✅ Update to json4s 4.0.7 (Scala 3 compatible)
- ✅ Scalanet vendored locally in the `scalanet/` directory

For historical information about the migration, see [Migration History](docs/MIGRATION_HISTORY.md).

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

Contact

For questions or support, reach out to Chippr Robotics LLC via our GitHub repository.
