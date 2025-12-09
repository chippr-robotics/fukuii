# Container Build Strategy

## Overview

Fukuii maintains three primary container images for different use cases, built automatically through GitHub Actions workflows.

## Container Images

### 1. `fukuii:latest` - Production Release
**Image Names:**
- `ghcr.io/chippr-robotics/fukuii:latest`
- `chipprbots/fukuii:latest`

**Build Trigger:** When a PR is merged to the `main` branch

**Purpose:** Stable production releases for mainnet deployment

**Dockerfile:** `docker/Dockerfile`

**Workflow:** `.github/workflows/docker.yml` (job: `docker-build-main`)

**Tags Applied:**
- `latest` (only when built from main branch)
- `<branch-name>` (e.g., `main`, `master`)
- `<sha>` (git commit SHA)
- `<version>` (when released with semver tag)

**Use Case:** Production deployments, mainnet nodes, stable testing

---

### 2. `fukuii:nightly` - Nightly Builds
**Image Names:**
- `ghcr.io/chippr-robotics/fukuii:nightly`
- `chipprbots/fukuii:nightly`
- `ghcr.io/chippr-robotics/fukuii:nightly-YYYYMMDD`
- `chipprbots/fukuii:nightly-YYYYMMDD`

**Build Trigger:** Daily at 00:00 UTC (midnight) via cron schedule, or manual workflow dispatch

**Purpose:** Automated daily builds from the latest main branch code

**Dockerfile:** `docker/Dockerfile`

**Workflow:** `.github/workflows/nightly.yml`

**Tags Applied:**
- `nightly` (rolling tag, always points to latest nightly)
- `nightly-YYYYMMDD` (date-stamped for specific nightly versions)

**Use Case:** 
- Testing latest changes before release
- CI/CD integration testing
- Early access to new features
- Regression testing

---

### 3. `fukuii-dev` - Development Image
**Image Names:**
- `ghcr.io/chippr-robotics/fukuii-dev:latest`
- `chipprbots/fukuii-dev:latest`

**Build Trigger:** When a PR is merged to the `develop` branch

**Purpose:** Development environment image with full JDK and build tools (SBT)

**Dockerfile:** `docker/Dockerfile-dev`

**Workflow:** `.github/workflows/docker.yml` (job: `docker-build-dev`)

**Tags Applied:**
- `latest` (only when built from develop branch)
- `develop` (branch name)
- `<sha>` (git commit SHA)

**Use Case:**
- Development and testing
- Building Fukuii from source inside containers
- CI/CD build environments
- Contributing to Fukuii development

**Key Differences from Production Image:**
- Includes full JDK (not just JRE)
- Includes SBT and build tools
- No pre-built Fukuii binary
- Suitable for development workflows

---

## Additional Container Variants

The repository also maintains specialized container images for specific networks:

### Network-Specific Images
- **`fukuii-mainnet`** - Pre-configured for Ethereum Classic mainnet
- **`fukuii-mordor`** - Pre-configured for Mordor testnet
- **`fukuii-bootnode`** - Bootnode variant for network infrastructure

### Build Images
- **`fukuii-base`** - Base image used as foundation for other builds

These images follow the same build triggers and workflows as the main image but use different Dockerfiles with network-specific configurations.

---

## Branch Strategy

### `main` Branch
- **Purpose:** Stable, production-ready code
- **Protection:** Branch protection enabled, requires PR reviews
- **Triggers:** 
  - Builds `fukuii:latest` on merge
  - Builds `fukuii:nightly` on schedule

### `develop` Branch
- **Purpose:** Active development, integration of new features
- **Protection:** Should have branch protection for quality gates
- **Triggers:**
  - Builds `fukuii-dev:latest` on merge
  - All PR branches merge to `develop` first

### Feature Branches
- **Purpose:** Individual features and bug fixes
- **Workflow:** 
  1. Create feature branch from `develop`
  2. Submit PR to `develop` for code review
  3. After approval, merge to `develop`
  4. Periodically, merge `develop` to `main` for releases

---

## Workflow Configuration

### docker.yml
Primary workflow for building container images on branch pushes.

**Triggers:**
```yaml
on:
  push:
    branches: [main, master, develop]
  pull_request:
    branches: [main, master, develop]
```

**Jobs:**
- `docker-build-main`: Builds main production image
- `docker-build-dev`: Builds development image (only on develop branch)
- `docker-build-base`: Builds base image
- `docker-build-mainnet`: Builds mainnet-specific image
- `docker-build-mordor`: Builds Mordor testnet image
- `docker-build-bootnode`: Builds bootnode image

### nightly.yml
Scheduled workflow for nightly builds.

**Triggers:**
```yaml
on:
  schedule:
    - cron: '0 0 * * *'  # Daily at midnight UTC
  workflow_dispatch:      # Manual trigger
```

**Jobs:**
- `nightly-build`: Builds network-specific images with nightly tags
- `nightly-build-standard`: Builds main/dev/base images with nightly tags
- `nightly-comprehensive-tests`: Runs comprehensive test suite

---

## Pull Request Workflow

### For PRs to `main`:
1. PR is opened
2. Docker images are **built but not pushed** (test build only)
3. CI runs tests
4. After approval and merge, images are built and pushed with tags

### For PRs to `develop`:
1. PR is opened
2. Docker images are **built but not pushed** (test build only)
3. CI runs tests
4. After approval and merge, `fukuii-dev` image is built and pushed

---

## Image Registries

All images are pushed to two registries for redundancy and accessibility:

1. **GitHub Container Registry (ghcr.io)** - Primary registry
   - Integrated with GitHub
   - Better for GitHub Actions workflows
   - Requires GitHub authentication

2. **Docker Hub (docker.io)** - Secondary registry
   - Public container registry
   - Better discoverability
   - Easier for end users

---

## Security

- Images are signed with Cosign (keyless signing using GitHub OIDC)
- SBOM (Software Bill of Materials) attached to release images
- SLSA provenance generated for supply chain security
- CodeQL scanning runs on all code changes
- Dependency scanning via Dependabot

---

## Best Practices

### For Users:
1. **Production:** Use `fukuii:latest` or specific version tags (e.g., `fukuii:1.0.0`)
2. **Testing:** Use `fukuii:nightly` for latest features
3. **Development:** Use `fukuii-dev:latest` for building from source

### For Contributors:
1. Submit PRs to `develop` branch
2. Ensure tests pass before merging
3. Use conventional commit messages
4. Update documentation when changing behavior

### For Maintainers:
1. Merge `develop` to `main` when ready for release
2. Tag releases with semantic versioning (e.g., `v1.0.0`)
3. Monitor nightly build failures
4. Review and approve dependency updates

---

## Troubleshooting

### Image not updating after merge
- Check workflow run status in GitHub Actions
- Verify Docker Hub credentials are configured
- Check if paths-ignore rules are excluding your changes

### Build failures
- Review GitHub Actions logs
- Check if submodules are initialized
- Verify SBT dependencies are available
- Ensure Dockerfile syntax is valid

### Tag not appearing
- Confirm the branch matches the expected trigger
- For `latest` tag, ensure you're on the correct branch (main for production, develop for dev)
- Check the metadata-action configuration in the workflow

---

## Related Documentation

- [Docker Deployment Guide](docker.md)
- [GitHub Actions Workflows](.github/workflows/README.md)
- [Contributing Guide](../CONTRIBUTING.md)
- [Release Process](../RELEASE_PROCESS.md)
