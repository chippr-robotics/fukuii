# Docker Image Tagging and Distribution Policy

## Overview

This document outlines the Docker image build, tagging, and distribution strategy for the Fukuii Ethereum client project managed by Chippr Robotics LLC.

## Image Repositories

### Chippr-Controlled Images

These images are built and maintained by Chippr Robotics and pushed to both GitHub Container Registry (GHCR) and Docker Hub:

#### 1. **fukuii-base**
- **Purpose**: Base Ubuntu image with system dependencies
- **GHCR**: `ghcr.io/chippr-robotics/chordodes_fukuii-base`
- **Docker Hub**: `chipprbots/fukuii-base`
- **Source**: `docker/Dockerfile-base`

#### 2. **fukuii-dev**
- **Purpose**: Development image with build tools and cached dependencies
- **GHCR**: `ghcr.io/chippr-robotics/chordodes_fukuii-dev`
- **Docker Hub**: `chipprbots/fukuii-dev`
- **Source**: `docker/Dockerfile-dev`

#### 3. **fukuii** (main image)
- **Purpose**: Production-ready Fukuii Ethereum client
- **GHCR**: `ghcr.io/chippr-robotics/chordodes_fukuii`
- **Docker Hub**: `chipprbots/fukuii`
- **Source**: `docker/Dockerfile`

### Third-Party Images

These images are **NOT** built by Chippr Robotics. We pull from their official repositories:

#### 1. **Hyperledger Besu**
- **Official Image**: `hyperledger/besu`
- **Purpose**: Used for testing and interoperability
- **Usage**: Specified in `docker/besu/docker-compose.yml`

#### 2. **Go Ethereum (Geth)**
- **Official Image**: `ethereum/client-go`
- **Purpose**: Used for testing and interoperability
- **Usage**: Specified in `docker/geth/docker-compose.yml`

#### 3. **Mantis**
- **Status**: Deprecated (original IOHK project)
- **Purpose**: Reference only, not actively maintained
- **Note**: Fukuii is a fork/continuation of Mantis

## Tagging Strategy

### Automated Tag Generation

All Chippr-controlled images follow this tagging strategy:

#### 1. **Semantic Version Tags** (on git tags)
When a version tag (e.g., `v1.2.3`) is pushed:
- `v1.2.3` - Full version
- `v1.2` - Major.minor version
- `v1` - Major version only
- `latest` - Always points to the latest stable release (from main branch)

#### 2. **Nightly Builds** (scheduled at 2 AM UTC)
- `nightly-YYYYMMDD` - Dated nightly build (e.g., `nightly-20251025`)
- `nightly` - Rolling tag that always points to the most recent nightly build

#### 3. **Branch Builds** (on push to branches)
- `main` - Latest build from the main branch
- `develop` - Latest build from the develop branch
- `<branch-name>` - Latest build from any other branch

#### 4. **SHA Tags** (on any push)
- `sha-<git-sha>` - Specific commit SHA for traceability (e.g., `sha-abc1234`)

#### 5. **Pull Request Builds**
- `pr-<number>` - Build from pull request (not pushed to registries)

## Build Triggers

### Automated Builds

Images are automatically built and pushed on:

1. **Push to main/master/develop branches**: Creates branch tags and `latest` (for main)
2. **Git tags starting with `v`**: Creates semantic version tags
3. **Nightly schedule** (2 AM UTC daily): Creates nightly tags
4. **Manual workflow dispatch**: Allows manual triggering when needed

### Pull Requests

Pull request builds:
- Build images to verify Dockerfile changes
- **Do not push** to registries (test only)
- Tagged as `pr-<number>`

## Required GitHub Secrets

To enable Docker Hub distribution, the following secrets must be configured in the GitHub repository:

1. **DOCKERHUB_USERNAME**: Docker Hub username for chipprbots organization
2. **DOCKERHUB_TOKEN**: Docker Hub access token with push permissions

**Note**: GitHub Container Registry (GHCR) authentication uses the built-in `GITHUB_TOKEN` and requires no additional configuration.

## Usage Examples

### Pulling Images

#### Latest stable release:
```bash
docker pull chipprbots/fukuii:latest
# or
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:latest
```

#### Specific version:
```bash
docker pull chipprbots/fukuii:v1.2.3
```

#### Latest nightly build:
```bash
docker pull chipprbots/fukuii:nightly
```

#### Specific nightly build:
```bash
docker pull chipprbots/fukuii:nightly-20251025
```

#### Development branch:
```bash
docker pull chipprbots/fukuii:develop
```

#### Specific commit:
```bash
docker pull chipprbots/fukuii:sha-abc1234
```

### Building Images Locally

For development and testing:

```bash
# Build base image
cd docker
docker build -t fukuii-base:local -f Dockerfile-base .

# Build dev image (requires base)
docker build -t fukuii-dev:local -f Dockerfile-dev .

# Build main image (requires base and dev)
docker build -t fukuii:local -f Dockerfile .
```

## Image Dependencies

The images have the following dependency chain:

```
fukuii-base (Dockerfile-base)
    ↓
fukuii-dev (Dockerfile-dev)
    ↓
fukuii (Dockerfile)
```

Each image builds upon the previous one, with:
- **base**: Operating system and system-level dependencies
- **dev**: Build tools, Scala/SBT, and cached build dependencies
- **fukuii**: Compiled application extracted from dev

## Maintenance and Updates

### Image Update Policy

1. **Base Image**: Update when Ubuntu security patches or system dependencies change
2. **Dev Image**: Update when build dependencies (SBT, Scala) change
3. **Main Image**: Update with every code change and release

### Tag Retention

- **Semantic versions**: Kept indefinitely
- **Nightly builds**: Recommended to keep last 30 days (configurable)
- **Branch builds**: Overwritten on each push
- **SHA tags**: Kept for traceability (configurable retention)

## Best Practices

1. **Production**: Always use semantic version tags (e.g., `v1.2.3`)
2. **Testing**: Use `nightly` or `develop` branch tags
3. **Development**: Build locally or use `dev` image
4. **CI/CD**: Use SHA tags for exact reproducibility
5. **Security**: Regularly update base images and scan for vulnerabilities

## Monitoring and Security

### Image Scanning

All images should be scanned for vulnerabilities before release:
- GitHub Advanced Security scanning (if enabled)
- Docker Hub automated security scanning
- Manual scans using tools like Trivy or Snyk

### Size Optimization

Images are optimized using multi-stage builds:
- Dev stage: Contains full build environment (~1-2 GB)
- Final stage: Only runtime dependencies and compiled app (~500 MB)

## Support and Contact

For issues related to Docker images:
- **GitHub Issues**: https://github.com/chippr-robotics/chordodes_fukuii/issues
- **Organization**: Chippr Robotics LLC

## Changelog

- **2025-10-25**: Initial Docker distribution policy established
  - Added Docker Hub as additional registry
  - Implemented nightly build schedule
  - Added comprehensive tagging strategy
  - Documented third-party image usage
