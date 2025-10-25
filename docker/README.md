# Fukuii Docker Images

This directory contains Docker configurations for the Fukuii Ethereum client and related testing infrastructure.

## Directory Structure

```
docker/
├── Dockerfile              # Main production Fukuii image
├── Dockerfile-base         # Base Ubuntu image with system dependencies
├── Dockerfile-dev          # Development image with build tools
├── DOCKER_POLICY.md        # Comprehensive tagging and distribution policy
├── besu/                   # Hyperledger Besu test environment (uses official image)
├── geth/                   # Go Ethereum test environment (uses official image)
├── fukuii/                 # Fukuii-specific docker-compose setup
├── mantis/                 # Legacy Mantis reference (deprecated)
└── scripts/                # Installation and setup scripts
```

## Quick Start

### Pulling Pre-built Images

#### From Docker Hub (Recommended):
```bash
# Latest stable release
docker pull chipprbots/fukuii:latest

# Specific version
docker pull chipprbots/fukuii:v1.0.0

# Latest nightly build
docker pull chipprbots/fukuii:nightly
```

#### From GitHub Container Registry:
```bash
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:latest
```

### Building Locally

```bash
# Build all images (base, dev, and main)
./build-base.sh
./build-dev.sh
./build.sh

# Or build individually
cd docker
docker build -t fukuii-base:local -f Dockerfile-base .
docker build -t fukuii-dev:local -f Dockerfile-dev .
docker build -t fukuii:local -f Dockerfile .
```

## Fukuii Images

### Production Image (`fukuii`)

The main Fukuii Ethereum client image for production use.

**Available Tags**:
- `latest` - Latest stable release from main branch
- `v1.2.3` - Specific semantic version
- `nightly` - Latest nightly build
- `develop` - Latest development build

**Usage**:
```bash
docker run -it chipprbots/fukuii:latest fukuii etc
```

### Base Image (`fukuii-base`)

Contains Ubuntu 16.04 (Xenial) with system-level dependencies and Nix package manager.

**Purpose**: Foundation for all other Fukuii images

### Development Image (`fukuii-dev`)

Contains the full build environment with SBT, Scala, and cached dependencies.

**Purpose**: Fast incremental builds during development

## Third-Party Images

### Hyperledger Besu

Testing and interoperability environment for Hyperledger Besu.

**Image Source**: Official `hyperledger/besu` from Docker Hub  
**Location**: `besu/`  
**More Info**: See [besu/README.md](besu/README.md)

```bash
cd besu
./runBesu.sh
```

### Go Ethereum (Geth)

Testing and interoperability environment for Go Ethereum.

**Image Source**: Official `ethereum/client-go` from Docker Hub  
**Location**: `geth/`  
**More Info**: See [geth/README.md](geth/README.md)

```bash
cd geth
./runGeth.sh
```

## Automated Builds

Docker images are automatically built and published via GitHub Actions:

- **Trigger**: Push to main/develop, git tags, nightly schedule
- **Registries**: GitHub Container Registry (GHCR) + Docker Hub
- **Workflow**: `.github/workflows/docker.yml`

See [DOCKER_POLICY.md](DOCKER_POLICY.md) for complete tagging and versioning details.

## Image Hierarchy

```
fukuii-base (Ubuntu + Nix + system deps)
    ↓
fukuii-dev (+ SBT + Scala + build deps)
    ↓
fukuii (+ compiled application)
```

## Configuration

### Environment Variables

- `FUKUII_DEV`: Set to `true` for development mode
- `MANTIS_TAG`: Git tag/branch for Fukuii source (default: `phase/iele_testnet`)
- `MANTIS_DIST_ZIP_NAME`: Distribution zip name pattern (default: `fukuii-*`)

### Volumes

Mount configuration and data directories:

```bash
docker run -v /path/to/conf:/app/conf \
           -v /path/to/data:/app/data \
           chipprbots/fukuii:latest
```

## Development Workflow

1. **Modify code** in your local repository
2. **Build dev image** to cache dependencies: `./build-dev.sh`
3. **Build main image** with your changes: `./build.sh`
4. **Test locally** before pushing
5. **Push to GitHub** - CI/CD will build and publish automatically

## Scripts

- `build-base.sh` - Build base image
- `build-dev.sh` - Build development image
- `build.sh` - Build main production image
- `buildhelper.sh` - Helper functions for build scripts
- `scripts/` - Installation scripts used within Dockerfiles

## Security

All images should be scanned for vulnerabilities before deployment:

```bash
# Using Trivy
trivy image chipprbots/fukuii:latest

# Using Docker Scout
docker scout cves chipprbots/fukuii:latest
```

## Support

- **Issues**: https://github.com/chippr-robotics/chordodes_fukuii/issues
- **Documentation**: See project README and DOCKER_POLICY.md
- **Organization**: Chippr Robotics LLC

## Additional Resources

- [Complete Docker Policy](DOCKER_POLICY.md) - Tagging strategy and distribution policy
- [GitHub Workflow](.github/workflows/docker.yml) - Automated build configuration
- [Project README](../README.md) - Main project documentation
