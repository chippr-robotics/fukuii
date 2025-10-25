# Docker Images

This directory contains Docker configurations for the Fukuii Ethereum client.

## Quick Start

Pull pre-built images from Docker Hub:

```bash
docker pull chipprbots/fukuii:latest
```

Build locally:

```bash
./build-base.sh && ./build-dev.sh && ./build.sh
```

## Documentation

Complete documentation is available in the `docs/` folder:

- **[Docker Policy](../docs/docker-policy.md)** - Comprehensive tagging and distribution policy
- **[Docker Hub Setup](../docs/docker-hub-setup.md)** - Configure Docker Hub credentials and secrets
- **[Setup Next Steps](../docs/docker-setup-next-steps.md)** - Post-merge action items

## Directory Structure

- `Dockerfile*` - Image definitions (base, dev, production)
- `besu/` - Hyperledger Besu test environment (uses official image)
- `geth/` - Go Ethereum test environment (uses official image)
- `fukuii/` - Fukuii-specific docker-compose setup
- `scripts/` - Installation and setup scripts

See also: [besu/README.md](besu/README.md) | [geth/README.md](geth/README.md)
